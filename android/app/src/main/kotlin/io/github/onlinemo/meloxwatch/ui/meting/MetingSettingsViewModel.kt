package io.github.onlinemo.meloxwatch.ui.meting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import io.github.onlinemo.meloxwatch.data.meting.MetingConfigurationSnapshot
import io.github.onlinemo.meloxwatch.data.meting.MetingException
import io.github.onlinemo.meloxwatch.data.meting.MetingIssue
import io.github.onlinemo.meloxwatch.data.meting.MetingLimits
import io.github.onlinemo.meloxwatch.data.meting.MetingPlaybackTestState
import io.github.onlinemo.meloxwatch.data.meting.MetingRuntimeOwner
import io.github.onlinemo.meloxwatch.data.meting.MetingSettingsDraft
import io.github.onlinemo.meloxwatch.data.meting.MetingTestReport
import io.github.onlinemo.meloxwatch.data.meting.requireMetingTrackId
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.playback.resources.OpenedPlaybackResourceMetadata
import io.github.onlinemo.meloxwatch.playback.resources.PlaybackResolutionBudget
import io.github.onlinemo.meloxwatch.playback.resources.RuntimePlaybackResources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 由同一现有控制器投影；isPlaying 是播放状态，不代表已经实际听到或测到首声。 */
internal data class MetingPlaybackObservation(
    val connected: Boolean = false,
    val hasMedia: Boolean = false,
    val mediaId: String? = null,
    val isPlaying: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val hasError: Boolean = false,
)

/** host 只恢复核验过的当前会话项，不浏览、换曲、创建播放器或强制绕过原生解析。 */
internal class MetingSettingsEnvironment(
    val playback: Flow<MetingPlaybackObservation>,
    val currentSelection: suspend () -> QueueEntry?,
    val requestCurrentPlayback: suspend (QueueEntry) -> Boolean,
)

/** 原始 URL/新凭据只留在本次内存编辑；状态不进入 SavedState 或诊断文本。 */
internal data class MetingSettingsState(
    val saved: MetingConfigurationSnapshot = MetingConfigurationSnapshot(),
    val draft: MetingSettingsDraft? = null,
    val testTrackId: String = "",
    val working: Boolean = false,
    val deleteConfirmation: Boolean = false,
    val report: MetingTestReport? = null,
    val issue: MetingIssue? = null,
) {
    val canTest: Boolean get() = !working && draft == null && saved.configured && saved.issue == null && !saved.changing
    override fun toString(): String = "MetingSettingsState(redacted)"
}

/**
 * 一份可见订阅、一个命令。离页先废弃编辑及测试绑定，再取消真实请求；
 * 已接受的密文提交仍持有 command 引用到实际 finally，不因重新显示而并行写配置。
 */
internal class MetingSettingsViewModel(
    private val owner: MetingRuntimeOwner,
    private val resources: RuntimePlaybackResources,
    private val environment: MetingSettingsEnvironment,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val workScope = scope ?: viewModelScope
    private val memory = Any()
    private var visible = false
    private var closed = false
    private var visibilityGeneration = 0L
    private var observer: Job? = null
    private var active: Command? = null
    private var readOnShow = false
    private var playbackTest: PlaybackTest? = null
    private var latestPlayback = MetingPlaybackObservation()
    private val mutableState = MutableStateFlow(MetingSettingsState())
    val state = mutableState.asStateFlow()

    fun setVisible(value: Boolean) {
        val cancel = synchronized(memory) {
            if (closed || visible == value) return
            visible = value
            visibilityGeneration++
            playbackTest = null
            mutableState.value = mutableState.value.copy(draft = null, deleteConfirmation = false,
                report = null, issue = null, working = active != null)
            if (value) {
                readOnShow = true
                null
            } else {
                readOnShow = false
                listOfNotNull(observer, active?.job)
            }
        }
        cancel?.forEach { it.cancel() }
        if (value) { subscribe(); scheduleRead() }
    }

    private fun subscribe() {
        val job = synchronized(memory) {
            if (closed || !visible || observer != null || !workScope.isActive) return
            val generation = visibilityGeneration
            val created = workScope.launch(start = CoroutineStart.LAZY) {
                combine(owner.configuration.snapshot, resources.openedResource, environment.playback) { saved, opened, playback ->
                    Observation(saved, opened, playback)
                }.collect { observation ->
                    synchronized(memory) {
                        if (!closed && visible && visibilityGeneration == generation) {
                            latestPlayback = observation.playback
                            val previous = mutableState.value
                            val changed = previous.saved.generation != observation.saved.generation
                            if (changed) playbackTest = null
                            mutableState.value = previous.copy(saved = observation.saved,
                                draft = if (changed) null else previous.draft,
                                deleteConfirmation = !changed && previous.deleteConfirmation,
                                report = if (changed) null else previous.report,
                                issue = if (changed && previous.draft != null && active == null) MetingIssue.CONFIGURATION_CHANGED else previous.issue)
                            updatePlaybackReport(observation.opened)
                        }
                    }
                }
            }
            observer = created
            created.invokeOnCompletion { cause ->
                synchronized(memory) { if (observer === created) observer = null }
                // 只接续明确的隐藏/重显取消；异常订阅不能变成无界自动重试循环。
                if (cause is CancellationException) subscribe()
                else if (cause != null) synchronized(memory) {
                    if (visible && visibilityGeneration == generation) mutableState.value = mutableState.value.copy(issue = MetingIssue.CLOSED)
                }
            }
            created
        }
        job.start()
    }

    private fun scheduleRead() {
        val shouldRead = synchronized(memory) { visible && !closed && active == null && readOnShow }
        if (!shouldRead) return
        command {
            synchronized(memory) { readOnShow = false }
            owner.configuration.restore()
        }
    }

    fun readAgain() = command {
        owner.configuration.reload()
        publish(it) { current -> current.copy(draft = null, report = null, issue = null) }
    }

    fun beginEdit() = command { ticket ->
        val saved = owner.configuration.restore()
        val draft = owner.configuration.edit(saved.generation)
        currentCoroutineContext().ensureActive()
        publish(ticket) { it.copy(saved = saved, draft = draft, deleteConfirmation = false, report = null, issue = null) }
    }

    fun changeDraft(draft: MetingSettingsDraft) = synchronized(memory) {
        if (!visible || closed || active != null || mutableState.value.draft == null) return@synchronized
        if (draft.endpoint.length > MetingLimits.URL_CHARACTERS || draft.headerName.length > MetingLimits.HEADER_NAME_CHARACTERS ||
            draft.credentialInput.length > MetingLimits.CREDENTIAL_CHARACTERS) return@synchronized
        mutableState.value = mutableState.value.copy(draft = draft, issue = null, report = null)
    }

    fun cancelEdit() = synchronized(memory) {
        if (visible && !closed && active == null) mutableState.value = mutableState.value.copy(draft = null, issue = null)
    }

    fun save() {
        val input = synchronized(memory) {
            mutableState.value.takeIf { visible && !closed && active == null }?.let { state ->
                state.draft?.let { state.saved.generation to it }
            }
        } ?: return
        command { ticket ->
            val saved = owner.configuration.save(input.first, input.second)
            publish(ticket) { it.copy(saved = saved, draft = null, report = null, issue = null) }
        }
    }

    fun setEnabled(enabled: Boolean) {
        val generation = synchronized(memory) {
            mutableState.value.takeIf { visible && !closed && active == null && it.draft == null }?.saved?.generation
        } ?: return
        command { ticket ->
            val saved = owner.configuration.setEnabled(generation, enabled)
            publish(ticket) { it.copy(saved = saved, report = null, issue = null) }
        }
    }

    fun confirmDeletion(value: Boolean) = synchronized(memory) {
        if (visible && !closed && active == null) mutableState.value = mutableState.value.copy(deleteConfirmation = value)
    }

    fun delete() {
        val generation = synchronized(memory) {
            mutableState.value.takeIf { visible && !closed && active == null && it.deleteConfirmation }?.saved?.generation
        } ?: return
        command { ticket ->
            val saved = owner.configuration.delete(generation)
            publish(ticket) { it.copy(saved = saved, draft = null, deleteConfirmation = false, report = null, issue = null) }
        }
    }

    fun setTrackId(value: String) = synchronized(memory) {
        if (!visible || closed || active != null || value.length > MetingLimits.ID_CHARACTERS || value.any { it !in '0'..'9' }) return@synchronized
        playbackTest = null
        mutableState.value = mutableState.value.copy(testTrackId = value, report = null, issue = null)
    }

    fun useCurrentTrack() = command { ticket ->
        val entry = currentNeteaseEntry()
        publish(ticket) { it.copy(testTrackId = entry.trackKey.trackId, report = null, issue = null) }
    }

    fun test() {
        val input = synchronized(memory) { mutableState.value.takeIf { visible && !closed && it.canTest }?.testTrackId } ?: return
        command { ticket ->
            val report = owner.test(input)
            publish(ticket) { it.copy(report = report, issue = null) }
        }
    }

    fun playCurrentTrack() {
        val input = synchronized(memory) {
            mutableState.value.takeIf { visible && !closed && it.canTest && it.saved.enabled }?.let { it.testTrackId to it.saved.generation }
        } ?: return
        command { ticket ->
            requireMetingTrackId(input.first)
            val entry = currentNeteaseEntry()
            if (entry.trackKey.trackId != input.first) throw MetingException(MetingIssue.CONFLICTING_TRACK)
            val use = owner.configuration.capture(requireEnabled = true) ?: throw MetingException(MetingIssue.DISABLED)
            if (use.generation != input.second) throw MetingException(MetingIssue.CONFIGURATION_CHANGED)
            currentCoroutineContext().ensureActive()
            val opened = resources.openedResource.value
            val binding = PlaybackTest(entry.queueEntryId, entry.trackKey.trackId, entry.accountId, use.generation,
                PlaybackResolutionBudget.currentSequence(), opened?.operationId)
            synchronized(memory) {
                if (!accepts(ticket)) throw CancellationException("Meting settings hidden")
                playbackTest = binding
                val previous = mutableState.value.report ?: MetingTestReport(addressValid = true)
                mutableState.value = mutableState.value.copy(report = previous.copy(playback = MetingPlaybackTestState.WAITING_NEW_RESOURCE))
            }
            // host 在现有控制器线程核验该实例后明确 play；此处不直接发起补充解析。
            if (!environment.requestCurrentPlayback(entry)) {
                publish(ticket) { it.copy(report = it.report?.copy(playback = MetingPlaybackTestState.FAILED), issue = MetingIssue.BUSY) }
                return@command
            }
            synchronized(memory) { if (accepts(ticket)) updatePlaybackReport(resources.openedResource.value) }
        }
    }

    private suspend fun currentNeteaseEntry(): QueueEntry {
        val entry = environment.currentSelection() ?: throw MetingException(MetingIssue.ID_REQUIRED)
        if (entry.trackKey.providerId != "netease" || entry.accountId == null || entry.location != null) throw MetingException(MetingIssue.WRONG_SOURCE)
        requireMetingTrackId(entry.trackKey.trackId)
        return entry
    }

    // playback 与唯一物理 transport 同时匹配，旧已开流和旧 pending 均不能成为本次成功。
    private fun updatePlaybackReport(opened: OpenedPlaybackResourceMetadata?) {
        val binding = playbackTest ?: return
        val previous = mutableState.value.report ?: MetingTestReport(addressValid = true)
        if (mutableState.value.saved.generation != binding.configurationGeneration) { playbackTest = null; return }
        val observed = latestPlayback
        val matches = opened != null && opened.queueEntryId == binding.queueEntryId && opened.providerId == "netease" &&
            opened.trackId == binding.trackId && opened.accountId == binding.accountId && opened.isCurrentAccount() && opened.resolverId == "meting-custom" &&
            opened.configurationGeneration == binding.configurationGeneration && observed.mediaId == binding.queueEntryId
        val fresh = opened != null && matches && opened.operationSequence > binding.afterSequence && opened.operationId != binding.previousOperationId
        val state = when {
            !observed.connected || observed.hasError || opened?.isCurrentAccount() == false -> MetingPlaybackTestState.FAILED
            observed.hasMedia && observed.mediaId != binding.queueEntryId -> MetingPlaybackTestState.OTHER_SOURCE
            matches && !fresh -> MetingPlaybackTestState.EXISTING_STREAM
            opened != null && !matches && observed.mediaId == binding.queueEntryId -> MetingPlaybackTestState.OTHER_SOURCE
            !fresh -> MetingPlaybackTestState.WAITING_NEW_RESOURCE
            observed.isPlaying -> MetingPlaybackTestState.PLAYING
            observed.playbackState == Player.STATE_BUFFERING -> MetingPlaybackTestState.BUFFERING
            observed.playbackState == Player.STATE_ENDED -> MetingPlaybackTestState.ENDED
            observed.playbackState == Player.STATE_READY -> MetingPlaybackTestState.READY
            else -> MetingPlaybackTestState.PAUSED
        }
        mutableState.value = mutableState.value.copy(report = previous.copy(playback = state))
    }

    private fun command(action: suspend (Command) -> Unit) {
        val job = synchronized(memory) {
            if (!visible || closed || active != null || !workScope.isActive) return
            val ticket = Command(visibilityGeneration)
            val created = workScope.launch(start = CoroutineStart.LAZY) {
                try { action(ticket) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: MetingException) { publish(ticket) { it.copy(issue = error.issue) } }
                catch (_: Exception) { publish(ticket) { it.copy(issue = MetingIssue.STORAGE_UNAVAILABLE) } }
            }
            ticket.job = created
            active = ticket
            mutableState.value = mutableState.value.copy(working = true, issue = null)
            created.invokeOnCompletion {
                synchronized(memory) {
                    if (active === ticket) {
                        active = null
                        mutableState.value = mutableState.value.copy(working = false)
                    }
                }
                scheduleRead()
            }
            created
        }
        job.start()
    }

    private fun publish(ticket: Command, transform: (MetingSettingsState) -> MetingSettingsState) = synchronized(memory) {
        if (accepts(ticket)) mutableState.value = transform(mutableState.value)
    }
    private fun accepts(ticket: Command): Boolean = visible && !closed && active === ticket && visibilityGeneration == ticket.visibilityGeneration

    override fun onCleared() {
        val cancelled = synchronized(memory) {
            closed = true
            visible = false
            visibilityGeneration++
            playbackTest = null
            mutableState.value = MetingSettingsState(working = active != null)
            listOfNotNull(observer, active?.job)
        }
        cancelled.forEach { it.cancel() }
    }

    private class Command(val visibilityGeneration: Long) { var job: Job? = null }
    private class PlaybackTest(val queueEntryId: String, val trackId: String, val accountId: String?, val configurationGeneration: Long,
        val afterSequence: Long, val previousOperationId: String?)
    private class Observation(val saved: MetingConfigurationSnapshot, val opened: OpenedPlaybackResourceMetadata?,
        val playback: MetingPlaybackObservation)
}
