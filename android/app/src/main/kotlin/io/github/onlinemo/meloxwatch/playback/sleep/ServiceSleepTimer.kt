package io.github.onlinemo.meloxwatch.playback.sleep

import io.github.onlinemo.meloxwatch.data.sleep.SleepTimerStore
import io.github.onlinemo.meloxwatch.data.sleep.SleepTimerStoreIssue
import io.github.onlinemo.meloxwatch.data.sleep.SleepTimerStoreLease
import io.github.onlinemo.meloxwatch.data.sleep.SleepTimerStoreOpenResult
import io.github.onlinemo.meloxwatch.data.sleep.SleepTimerStoreRecovery
import io.github.onlinemo.meloxwatch.data.sleep.SleepTimerStoreWriteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal interface SleepTimerScheduler {
    fun deadline(token: SleepTimerToken, atElapsedMs: Long, task: () -> Unit)
    fun summary(token: SleepTimerToken, atElapsedMs: Long, task: () -> Unit)
    fun clearDeadline()
    fun clearSummary()
    fun close()
}

internal interface SleepTimerNotification {
    fun isAllowed(): Boolean
    fun show(summary: SleepTimerSummary?): Boolean
}

enum class SleepTimerPersistence { LOADING, SAVING, SAVED, FAILED }

internal data class ServiceSleepTimerState(
    val timer: SleepTimerState = SleepTimerState(),
    val initialized: Boolean = false,
    val persistence: SleepTimerPersistence = SleepTimerPersistence.LOADING,
    val issue: SleepTimerStoreIssue? = null,
    val conflict: Boolean = false,
    val recovery: SleepTimerStoreRecovery? = null,
    val summaryEnabled: Boolean = false,
    val notificationAllowed: Boolean = true,
)

/**
 * 全部入口由 Service 主线程调用。运行时立即废旧 token，保存状态独立显示，不能把 IO 失败当回滚。
 *
 * 一个写入 actor 只保留最新目标；旧写入的 receipt 更新租约，不覆盖后来取消或替换的定时。
 * Handler 只安排截止和分钟边界。两后端播放时持有已有音频 wake lock，暂停后不为倒计时另保活；
 * 恢复和每次发声前仍核验 elapsed clock，自然结束在队列重复/前进之前再次核验。
 */
internal class ServiceSleepTimer(
    private val store: SleepTimerStore,
    private val scope: CoroutineScope,
    private val now: () -> SleepTimerClockSample,
    private val currentTrack: () -> SleepTimerTrackBinding?,
    private val scheduler: SleepTimerScheduler,
    private val notification: SleepTimerNotification,
    private val pausePlayback: () -> Unit,
    private val cancelResumeIntent: () -> Unit,
    private val onChanged: () -> Unit = {},
) {
    private val mutableState = MutableStateFlow(ServiceSleepTimerState())
    val state = mutableState.asStateFlow()
    private var lease: SleepTimerStoreLease? = null
    private var writer: Job? = null
    private var writeRequested = false
    private var initializing = false
    private var reopening = false
    private var closed = false
    private var lastSummary: SleepTimerSummary? = null
    private var summaryWasShown = false
    private var blockedTraversal: SleepTimerTrackBinding? = null

    suspend fun initialize() {
        if (closed || initializing || mutableState.value.initialized) return
        initializing = true
        try {
            pausePlayback()
            when (val opened = store.open()) {
                is SleepTimerStoreOpenResult.Failed -> {
                    if (!closed) publish(mutableState.value.copy(initialized = true,
                        persistence = SleepTimerPersistence.FAILED, issue = opened.issue))
                }
                is SleepTimerStoreOpenResult.Ready -> {
                    lease = opened.lease
                    if (closed) {
                        withContext(NonCancellable) { store.retire(opened.lease) }
                        return
                    }
                    val saved = opened.lease.stored
                    val restored = SleepTimerReducer.restore(saved.state, now())
                    publish(mutableState.value.copy(timer = restored.state, initialized = true,
                        persistence = if (saved.canWrite) SleepTimerPersistence.SAVED else SleepTimerPersistence.FAILED,
                        issue = if (saved.canWrite) null else SleepTimerStoreIssue.UNSUPPORTED_SCHEMA,
                        recovery = saved.recovery))
                    // 安全结清已经到期/失去启动证据的 timer。普通损坏字段仍保留到显式修改，不读即修盘。
                    if (restored.state != saved.state && saved.canWrite) requestWrite()
                    refreshScheduling()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            lease = null
            if (!closed) publish(mutableState.value.copy(initialized = true,
                persistence = SleepTimerPersistence.FAILED, issue = SleepTimerStoreIssue.IO))
        } finally {
            initializing = false
        }
    }

    val generationId: String? get() = mutableState.value.timer.let {
        it.active?.token?.generation ?: it.lastStop?.token?.generation
    }

    /** True confirms runtime acceptance. The snapshot separately reports pending/failed persistence. */
    fun arm(minutes: Int, mode: SleepTimerEndMode, expectedGeneration: String?): Boolean {
        if (closed || !mutableState.value.initialized || lease?.stored?.canWrite != true ||
            mutableState.value.issue == SleepTimerStoreIssue.RETIRED_OWNER || expectedGeneration != generationId) return false
        blockedTraversal = null
        accept(SleepTimerReducer.arm(minutes, mode, now()).state)
        return true
    }

    fun cancel(expectedGeneration: String?): Boolean {
        if (closed || !mutableState.value.initialized || expectedGeneration != generationId) return false
        blockedTraversal = null
        accept(SleepTimerReducer.cancel(mutableState.value.timer).state, explicitWrite = true)
        return true
    }

    fun onUserPause() {
        if (!closed && mutableState.value.initialized) accept(SleepTimerReducer.onUserPause(mutableState.value.timer).state)
    }

    fun onManualTrackChange() {
        blockedTraversal = null
        if (!closed && mutableState.value.initialized) accept(SleepTimerReducer.onManualTrackChange(mutableState.value.timer).state)
    }

    /** 队列编辑保留同一实体/实例/轮次时，只移交等待绑定，不把模式编辑当手动切歌。 */
    fun onRetainedTrackBinding(previous: SleepTimerTrackBinding, current: SleepTimerTrackBinding) {
        if (closed || previous.sessionId != current.sessionId || previous.queueEntryId != current.queueEntryId ||
            previous.playbackCycle != current.playbackCycle) return
        if (blockedTraversal == previous) blockedTraversal = current
        val active = mutableState.value.timer.active ?: return
        if (active.waitingFor == previous) accept(SleepTimerState(active = active.copy(waitingFor = current)))
    }

    /** A stale deadline must not authorize a delayed focus gain or a resumed preparation. */
    fun beforePlay(): Boolean {
        if (closed || !mutableState.value.initialized) return false
        val transition = deadlineTransition()
        if (transition.pauseRequired) blockedTraversal = currentTrack() else blockedTraversal = null
        accept(transition.state)
        if (transition.pauseRequired) cancelResumeIntent()
        return !transition.pauseRequired
    }

    /** Queue owns the final pause/checkpoint, so this hook cannot cancel its natural-end transaction. */
    fun beforeNaturalAdvance(ending: SleepTimerTrackBinding): Boolean {
        if (closed || !mutableState.value.initialized) return true
        if (blockedTraversal == ending) {
            cancelResumeIntent()
            return true
        }
        val transition = SleepTimerReducer.onNaturalEnd(mutableState.value.timer, now(), ending, currentTrack())
        if (transition.blockNaturalAdvance) blockedTraversal = ending
        accept(transition.state)
        if (transition.pauseRequired) cancelResumeIntent()
        return transition.blockNaturalAdvance
    }

    fun setSummaryEnabled(enabled: Boolean) {
        if (closed) return
        if (mutableState.value.summaryEnabled != enabled) publish(mutableState.value.copy(summaryEnabled = enabled))
        refreshScheduling()
    }

    /** Only a foreground request/state change refreshes permission evidence; there is no permission poller. */
    fun refresh() {
        if (closed) return
        if (mutableState.value.initialized) onDeadline()
        else refreshSummary()
    }

    suspend fun retryPersistence(expectedGeneration: String?): Boolean {
        if (closed || !mutableState.value.initialized || reopening || writer != null ||
            mutableState.value.issue == SleepTimerStoreIssue.RETIRED_OWNER || expectedGeneration != generationId) return false
        // Failed/cancelled IO may have committed. Reopen for the real revision, then explicitly
        // save the runtime state the user is currently managing; never reactivate an old disk timer.
        reopening = true
        try {
            when (val opened = store.open()) {
                is SleepTimerStoreOpenResult.Failed -> {
                    if (!closed) publish(mutableState.value.copy(persistence = SleepTimerPersistence.FAILED,
                        issue = opened.issue, conflict = false))
                    return false
                }
                is SleepTimerStoreOpenResult.Ready -> {
                    lease = opened.lease
                    if (closed) {
                        withContext(NonCancellable) { store.retire(opened.lease) }
                        return false
                    }
                    if (!opened.lease.stored.canWrite) {
                        publish(mutableState.value.copy(persistence = SleepTimerPersistence.FAILED,
                            issue = SleepTimerStoreIssue.UNSUPPORTED_SCHEMA, conflict = false))
                        return false
                    }
                    publish(mutableState.value.copy(issue = null, conflict = false))
                    reopening = false
                    requestWrite()
                    return true
                }
            }
        } catch (error: CancellationException) {
            failWithoutReceipt()
            throw error
        } catch (_: Exception) {
            failWithoutReceipt()
            return false
        } finally {
            reopening = false
        }
    }

    /** Stop callbacks synchronously without turning Service destruction into a user cancellation. */
    fun detach() {
        if (closed) return
        closed = true
        scheduler.close()
        notification.show(null)
        lastSummary = null
        summaryWasShown = false
    }

    suspend fun close() {
        detach()
        try {
            writer?.join()
        } finally {
            // A new Service open fences this lease too. Teardown never retires somebody else's owner.
            withContext(NonCancellable) { lease?.let { store.retire(it) } }
        }
    }

    private fun deadlineTransition(): SleepTimerTransition {
        val timer = mutableState.value.timer
        val token = timer.active?.token ?: return SleepTimerTransition(timer)
        return SleepTimerReducer.onDeadline(timer, token, now(), currentTrack())
    }

    private fun onDeadline() {
        if (closed || !mutableState.value.initialized) return
        val transition = deadlineTransition()
        if (transition.pauseRequired) blockedTraversal = currentTrack()
        accept(transition.state)
        if (transition.pauseRequired) pausePlayback()
        refreshScheduling()
    }

    private fun accept(timer: SleepTimerState, explicitWrite: Boolean = false) {
        if (closed) return
        if (timer != mutableState.value.timer) {
            publish(mutableState.value.copy(timer = timer))
            requestWrite()
            refreshScheduling()
        } else if (explicitWrite) requestWrite()
    }

    private fun requestWrite() {
        writeRequested = true
        if (reopening) {
            publish(mutableState.value.copy(persistence = SleepTimerPersistence.SAVING))
            return
        }
        val current = lease
        val issue = when {
            mutableState.value.issue == SleepTimerStoreIssue.RETIRED_OWNER -> SleepTimerStoreIssue.RETIRED_OWNER
            current == null -> SleepTimerStoreIssue.IO
            !current.stored.canWrite -> SleepTimerStoreIssue.UNSUPPORTED_SCHEMA
            else -> mutableState.value.issue
        }
        if (issue != null || mutableState.value.conflict) {
            publish(mutableState.value.copy(persistence = SleepTimerPersistence.FAILED, issue = issue))
            return
        }
        publish(mutableState.value.copy(persistence = SleepTimerPersistence.SAVING))
        // A cancelled writer may still be inside a noncooperative store call. Its finally block,
        // not Job.isActive, releases the one physical write slot.
        if (writer != null) return
        val job = scope.launch(start = CoroutineStart.LAZY) { persistLatest() }
        writer = job
        job.start()
    }

    private suspend fun persistLatest() {
        try {
            while (writeRequested) {
                val current = lease ?: break
                val target = mutableState.value.timer
                writeRequested = false
                when (val saved = store.save(current, target)) {
                    is SleepTimerStoreWriteResult.Saved -> {
                        lease = saved.lease
                        if (mutableState.value.timer != target) writeRequested = true
                        publish(mutableState.value.copy(persistence = if (writeRequested) SleepTimerPersistence.SAVING
                            else SleepTimerPersistence.SAVED, issue = null, conflict = false,
                            recovery = saved.lease.stored.recovery))
                    }
                    is SleepTimerStoreWriteResult.Conflict -> {
                        writeRequested = true
                        publish(mutableState.value.copy(persistence = SleepTimerPersistence.FAILED, conflict = true))
                        break
                    }
                    is SleepTimerStoreWriteResult.Failed -> {
                        writeRequested = true
                        publish(mutableState.value.copy(persistence = SleepTimerPersistence.FAILED, issue = saved.issue))
                        break
                    }
                }
            }
        } catch (error: CancellationException) {
            // No receipt means no saved/rollback claim. Explicit retry must reopen before CAS.
            failWithoutReceipt()
            throw error
        } catch (_: Exception) {
            failWithoutReceipt()
        } finally {
            writer = null
        }
    }

    private fun failWithoutReceipt() {
        lease = null
        writeRequested = true
        publish(mutableState.value.copy(persistence = SleepTimerPersistence.FAILED,
            issue = SleepTimerStoreIssue.IO, conflict = false))
    }

    private fun refreshScheduling() {
        if (closed) return
        val active = mutableState.value.timer.active
        scheduler.clearDeadline()
        if (active != null && active.waitingFor == null) {
            scheduler.deadline(active.token, active.token.deadlineElapsedMs) {
                if (!closed && mutableState.value.timer.active?.token == active.token) onDeadline()
            }
        }
        refreshSummary()
    }

    private fun refreshSummary() {
        if (closed) return
        val enabled = mutableState.value.summaryEnabled
        val allowed = !enabled || notification.isAllowed()
        if (mutableState.value.notificationAllowed != allowed) publish(mutableState.value.copy(notificationAllowed = allowed))
        val summary = sleepTimerSummary(mutableState.value.timer, now(), enabled, allowed)
        scheduler.clearSummary()
        if (summary != lastSummary || summaryWasShown && summary == null) {
            if (!notification.show(summary)) {
                lastSummary = null
                summaryWasShown = false
                if (enabled && allowed) publish(mutableState.value.copy(notificationAllowed = false))
                return
            }
            lastSummary = summary
            summaryWasShown = summary != null
        }
        if (summary != null) scheduler.summary(summary.token, summary.nextUpdateElapsedMs) {
            if (!closed && mutableState.value.timer.active?.token == summary.token) onDeadline()
        }
    }

    private fun publish(next: ServiceSleepTimerState) {
        if (mutableState.value == next) return
        mutableState.value = next
        if (!closed) onChanged()
    }
}
