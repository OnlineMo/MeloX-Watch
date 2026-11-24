package io.github.onlinemo.meloxwatch.ui.playback

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture
import io.github.onlinemo.meloxwatch.playback.input.RotaryStep
import io.github.onlinemo.meloxwatch.playback.input.RotaryTarget
import io.github.onlinemo.meloxwatch.playback.route.MediaVolumeCommands
import io.github.onlinemo.meloxwatch.playback.route.MediaVolumeSetRequest
import io.github.onlinemo.meloxwatch.playback.route.MediaVolumeSnapshot
import io.github.onlinemo.meloxwatch.playback.route.PlaybackRouteSessionState
import io.github.onlinemo.meloxwatch.playback.route.ServiceMediaVolumeIssue
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive

/** ownerId 由宿主的页面、焦点与输入设置代次组成；时间采样不能改变它。 */
data class MediaVolumeClientContext(
    val connectionGeneration: Long,
    val ownerId: String?,
    val backendGeneration: Long?,
    val route: PlaybackRouteSessionState,
    val eligible: Boolean,
) {
    init {
        require(connectionGeneration >= 0L && (backendGeneration == null || backendGeneration >= 0L))
        require(ownerId == null || ownerId.isNotBlank())
    }
}

enum class MediaVolumeClientPhase { IDLE, READING, PREVIEW, COMMITTING, CANCELLING }

data class ServiceMediaVolumeUiState(
    val snapshot: MediaVolumeSnapshot? = null,
    val preview: Int? = null,
    val gestureId: String? = null,
    val ownerId: String? = null,
    val phase: MediaVolumeClientPhase = MediaVolumeClientPhase.IDLE,
    val issue: ServiceMediaVolumeIssue? = null,
) {
    val current: Int? get() = snapshot?.values?.current
    val minimum: Int get() = snapshot?.values?.minimum ?: 0
    val maximum: Int? get() = snapshot?.values?.maximum
    val muted: Boolean get() = snapshot?.values?.muted == true
    val relativeAdjustable: Boolean
        get() = ownerId != null && issue != ServiceMediaVolumeIssue.UNAVAILABLE &&
            phase == MediaVolumeClientPhase.IDLE && snapshot?.values?.adjustable == true
    val absoluteAdjustable: Boolean
        get() = ownerId != null && issue != ServiceMediaVolumeIssue.UNAVAILABLE &&
            (phase == MediaVolumeClientPhase.IDLE || phase == MediaVolumeClientPhase.PREVIEW) &&
            snapshot?.absoluteAdjustable == true
}

/**
 * 界面只持一份草稿和一个会话请求；实际媒体流、路由与音量由现有服务核验。
 *
 * 所有调用及状态提交在主 Looper。[dispatch] 必须在控制器自己的 application Looper 核对
 * [MediaVolumeClientContext.connectionGeneration] 后发送同一 READ/SET/ADJUST 协议。
 * 按钮返回值仅表示接纳意图；[rotary] 等真实 accepted && changed 后才返回成功。
 *
 * Media3 1.8.0 的 future.cancel 不撤销远端命令，也不移除 pending sequence。
 * 因此取消已发送命令只退役 UI/ACK，原 future 结束前仍占用唯一槽，不重发写或假称回滚。
 * 单次回复截止为 3 秒；[onUnsettledReply] 必须释放该代次的宿主控制器连接。
 * 固定 Media3 的释放收尾最多 30 秒；期间只保留一个回执与一次必要的截止任务，无轮询。
 * 控制器属于宿主，关闭本客户端不会释放服务的播放器。
 */
@MainThread
@androidx.annotation.OptIn(UnstableApi::class)
class ServiceMediaVolumeClient(
    private val scope: CoroutineScope,
    private val currentContext: () -> MediaVolumeClientContext,
    private val dispatch: (MediaVolumeClientContext, SessionCommand, Bundle) -> ListenableFuture<SessionResult>?,
    private val onUnsettledReply: (connectionGeneration: Long) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val callbackExecutor = Executor { command -> handler.post(command) }
    private val mutableState = MutableStateFlow(ServiceMediaVolumeUiState())
    val state = mutableState.asStateFlow()
    private var visible = false
    private var closed = false
    private var admitting = false
    private var uiEpoch = UUID.randomUUID().toString()
    private var lastContext: MediaVolumeClientContext? = null
    private var snapshotContext: MediaVolumeClientContext? = null
    private var draft: VolumeDraft? = null
    private var pending: VolumeRequest? = null
    private var refreshRequested = false
    private var retryRequired = false
    private var readCorrectionsRemaining = 1

    init { checkMain() }

    val busy: Boolean get() = admitting || draft != null || pending != null

    fun onVisible() {
        checkMain()
        if (closed) return
        val entered = !visible
        visible = true
        synchronizeContext()
        if (entered) refresh() else sample()
    }

    fun onHidden() {
        checkMain()
        visible = false
        refreshRequested = false
        retire(ServiceMediaVolumeIssue.UNAVAILABLE)
    }

    /** 宿主连接、页面、焦点、后端或 route extras 变化后立即调用，不依赖下一次采样。 */
    fun contextChanged() {
        checkMain()
        if (synchronizeContext()) {
            readCorrectionsRemaining = 1
            requestFreshRead()
        }
    }

    /** 显式重试最多合并一份鲜读；不会重放已发送的 SET/ADJUST。 */
    fun refresh() {
        checkMain()
        retryRequired = false
        readCorrectionsRemaining = 1
        requestFreshRead()
    }

    /** 可见页面沿用其最多 1Hz 的采样；超时后等待显式重试，不能形成重连循环。 */
    fun sample() {
        checkMain()
        if (!retryRequired) requestFreshRead()
    }

    fun begin(): String? {
        checkMain()
        if (busy) return null
        admitting = true
        val epoch = uiEpoch
        try {
            val observed = mutableState.value.snapshot ?: return null
            if (!mutableState.value.absoluteAdjustable) return null
            val captured = currentContext()
            if (!eligible(captured) || uiEpoch != epoch || pending != null || draft != null ||
                captured.route.known && captured.route != observed.route) return null
            if (snapshotContext?.let { sameOwner(it, captured) } != true) {
                retire(ServiceMediaVolumeIssue.UNAVAILABLE)
                return null
            }
            val value = VolumeDraft(UUID.randomUUID().toString(), epoch, captured, observed, observed.values.current)
            draft = value
            mutableState.value = mutableState.value.copy(preview = value.target, gestureId = value.id,
                ownerId = epoch, phase = MediaVolumeClientPhase.PREVIEW, issue = null)
            return value.id
        } finally {
            admitting = false
        }
    }

    fun drag(gestureId: String, fraction: Double): Boolean {
        checkMain()
        val active = editable(gestureId) ?: return false
        if (!fraction.isFinite() || fraction !in 0.0..1.0) {
            cancel(gestureId)
            return false
        }
        val values = active.observed.values
        active.target = values.minimum + (fraction * (values.maximum - values.minimum)).toInt()
        mutableState.value = mutableState.value.copy(preview = active.target)
        return draft === active
    }

    fun release(gestureId: String): Boolean {
        checkMain()
        val active = editable(gestureId) ?: return false
        // 静音实读 0 可能低于平台最小值；只开始再放开不能因此自动解除静音。
        if (active.target !in active.observed.values.minimum..active.observed.values.maximum) {
            cancel(gestureId)
            return false
        }
        return startRequest(MediaVolumeCommands.set, MediaVolumeCommands.setArguments(
            MediaVolumeSetRequest(active.observed, active.target)), expectedDraft = active) != null
    }

    fun tap(gestureId: String, fraction: Double): Boolean = drag(gestureId, fraction) && release(gestureId)

    fun setProgress(fraction: Double): Boolean {
        checkMain()
        if (!fraction.isFinite() || fraction !in 0.0..1.0) return false
        val id = begin() ?: return false
        return tap(id, fraction)
    }

    fun cancel(gestureId: String) {
        checkMain()
        if (draft?.id != gestureId) return
        draft = null
        uiEpoch = UUID.randomUUID().toString()
        mutableState.value = mutableState.value.copy(preview = null, gestureId = null, ownerId = uiEpoch,
            phase = MediaVolumeClientPhase.IDLE)
        sample()
    }

    fun change(delta: Int): Boolean {
        checkMain()
        if (delta != -1 && delta != 1) return false
        return startRequest(MediaVolumeCommands.adjust, MediaVolumeCommands.adjustArguments(delta)) != null
    }

    /**
     * 用异步 future 等回执，主线程不阻塞等待结果。取消调用者后仍等原回执，保持共同输入槽。
     * 晚到回执不产生成功触觉。
     * 这是不可撤销会话命令的有界回执收尾，回复截止会要求宿主释放连接，而非忽略取消继续写。
     */
    suspend fun rotary(step: RotaryStep): Boolean {
        checkMain()
        currentCoroutineContext().ensureActive()
        if (step.target != RotaryTarget.MEDIA_VOLUME) return false
        val caller = currentCoroutineContext()[Job]
        val changed = suspendCoroutine<Boolean> { continuation ->
            val request = startRequest(MediaVolumeCommands.adjust, MediaVolumeCommands.adjustArguments(step.delta),
                caller = caller, onSettled = { continuation.resume(it) })
            if (request == null) continuation.resume(false)
        }
        currentCoroutineContext().ensureActive()
        return changed
    }

    fun invalidate() {
        checkMain()
        retire(ServiceMediaVolumeIssue.OUTPUT_CHANGED)
    }

    fun close() {
        checkMain()
        closed = true
        onHidden()
    }

    private fun requestFreshRead() {
        val context = currentContext()
        if (retryRequired || !eligible(context) || draft != null) return
        if (pending != null || admitting) {
            if (pending?.retired == true) refreshRequested = true
            return
        }
        startRequest(MediaVolumeCommands.read, MediaVolumeCommands.readArguments())
    }

    private fun synchronizeContext(): Boolean {
        val actual = currentContext()
        val previous = lastContext
        lastContext = actual
        if (previous == null) return eligible(actual)
        if (!sameOwner(previous, actual) || !eligible(actual)) {
            retire(ServiceMediaVolumeIssue.UNAVAILABLE)
            return eligible(actual)
        }
        if (previous.route != actual.route) {
            draft = null
            snapshotContext = null
            uiEpoch = UUID.randomUUID().toString()
            // READ 本身可发现 route；保留其原回执，提交时再对照最新 extras。写命令的旧 ACK 必须废弃。
            pending?.let {
                if (it.command == MediaVolumeCommands.read) it.epoch = uiEpoch else it.retired = true
            }
            mutableState.value = ServiceMediaVolumeUiState(ownerId = uiEpoch,
                phase = pending?.let { if (it.retired) MediaVolumeClientPhase.CANCELLING else MediaVolumeClientPhase.READING }
                    ?: MediaVolumeClientPhase.IDLE, issue = ServiceMediaVolumeIssue.OUTPUT_CHANGED)
            return true
        }
        return false
    }

    private fun startRequest(
        command: SessionCommand,
        arguments: Bundle,
        expectedDraft: VolumeDraft? = null,
        caller: Job? = null,
        onSettled: ((Boolean) -> Unit)? = null,
    ): VolumeRequest? {
        if (admitting || pending != null || draft != null && draft !== expectedDraft) return null
        admitting = true
        val epoch = uiEpoch
        try {
            val context = currentContext()
            if (!eligible(context) || uiEpoch != epoch || caller?.isActive == false) return null
            if (expectedDraft != null && (draft !== expectedDraft || expectedDraft.epoch != epoch ||
                    !sameOwner(expectedDraft.context, context) ||
                    context.route.known && context.route != expectedDraft.observed.route)) return null
            val request = VolumeRequest(command, context, epoch, expectedDraft?.observed?.route, caller, onSettled)
            pending = request
            draft = null
            mutableState.value = mutableState.value.copy(preview = null, gestureId = null, ownerId = uiEpoch,
                phase = if (command == MediaVolumeCommands.read) MediaVolumeClientPhase.READING else MediaVolumeClientPhase.COMMITTING)
            // 状态观察者可同步离页/撤销；紧邻发送前再核对，不能把发布 pending 当作已授权写。
            if (!isCurrent(request, currentContext())) {
                finish(request, changed = false)
                return request
            }
            val future = try { dispatch(context, command, arguments) }
            catch (_: RuntimeException) { null }
            if (future == null) {
                failedReply(request)
                finish(request, changed = false)
                return request
            }
            request.future = future
            val deadline = Runnable {
                if (pending !== request || future.isDone) return@Runnable
                request.retired = true
                retryRequired = true
                refreshRequested = false
                snapshotContext = null
                mutableState.value = ServiceMediaVolumeUiState(phase = MediaVolumeClientPhase.CANCELLING,
                    issue = ServiceMediaVolumeIssue.UNAVAILABLE)
                try { onUnsettledReply(request.context.connectionGeneration) }
                catch (_: RuntimeException) {
                    // 释放失败不能令 UI 崩溃或提前空出原回执槽；宿主必须记录连接收尾失败。
                }
            }
            request.deadline = deadline
            handler.postDelayed(deadline, REPLY_DEADLINE_MS)
            future.addListener({ receive(request) }, callbackExecutor)
            return request
        } finally {
            admitting = false
        }
    }

    private fun receive(request: VolumeRequest) {
        checkMain()
        if (pending !== request) return
        val reply = try { checkNotNull(request.future).get() }
        catch (_: ExecutionException) { null }
        catch (_: CancellationException) { null }
        val result = if (reply?.resultCode == SessionResult.RESULT_SUCCESS) {
            try { MediaVolumeCommands.readResult(reply.extras) }
            catch (_: RuntimeException) { null }
        } else null
        val context = currentContext()
        var changed = false
        if (isCurrent(request, context)) {
            if (result == null) failedReply(request)
            else if (request.command == MediaVolumeCommands.read && context.route.known &&
                result.snapshot != null && result.snapshot.route != context.route) {
                request.retired = true
                snapshotContext = null
                // extras 与回复短暂不同步最多补一次 READ；持续不一致不能形成回执驱动的无限请求。
                if (readCorrectionsRemaining > 0) {
                    readCorrectionsRemaining -= 1
                    refreshRequested = true
                } else {
                    retryRequired = true
                    refreshRequested = false
                }
                mutableState.value = ServiceMediaVolumeUiState(phase = MediaVolumeClientPhase.CANCELLING,
                    issue = ServiceMediaVolumeIssue.OUTPUT_CHANGED)
            } else if (request.command != MediaVolumeCommands.read && result.snapshot != null &&
                (context.route.known && result.snapshot.route != context.route ||
                    request.expectedRoute != null && result.snapshot.route != request.expectedRoute)) {
                request.retired = true
                snapshotContext = null
                refreshRequested = true
                mutableState.value = ServiceMediaVolumeUiState(phase = MediaVolumeClientPhase.CANCELLING,
                    issue = ServiceMediaVolumeIssue.OUTPUT_CHANGED)
            } else {
                snapshotContext = context
                mutableState.value = ServiceMediaVolumeUiState(snapshot = result.snapshot, ownerId = uiEpoch,
                    phase = mutableState.value.phase, issue = result.issue)
                changed = result.accepted && result.changed
            }
        } else {
            val issue = mutableState.value.issue?.takeIf { request.retired }
                ?: if (!eligible(context) || !sameOwner(request.context, context)) ServiceMediaVolumeIssue.UNAVAILABLE
                else ServiceMediaVolumeIssue.OUTPUT_CHANGED
            request.retired = true
            snapshotContext = null
            mutableState.value = ServiceMediaVolumeUiState(phase = MediaVolumeClientPhase.CANCELLING,
                issue = issue)
            if (eligible(context) && !retryRequired) refreshRequested = true
        }
        finish(request, changed && isCurrent(request, currentContext()))
    }

    private fun failedReply(request: VolumeRequest) {
        if (!isCurrent(request, currentContext())) return
        retryRequired = true
        mutableState.value = mutableState.value.copy(preview = null, gestureId = null,
            issue = ServiceMediaVolumeIssue.UNAVAILABLE)
    }

    private fun finish(request: VolumeRequest, changed: Boolean) {
        if (pending !== request) return
        request.deadline?.let(handler::removeCallbacks)
        // IDLE 观察者也可能同步发起操作；回执结算返回前仍持原槽。
        mutableState.value = mutableState.value.copy(phase = MediaVolumeClientPhase.IDLE)
        val acceptedChange = changed && isCurrent(request, currentContext())
        try { request.onSettled?.invoke(acceptedChange) }
        finally {
            pending = null
            mutableState.value = mutableState.value.copy(phase = MediaVolumeClientPhase.IDLE)
        }
        if (refreshRequested && !retryRequired && !admitting) {
            refreshRequested = false
            requestFreshRead()
        }
    }

    private fun editable(gestureId: String): VolumeDraft? {
        if (admitting || pending != null) return null
        val active = draft?.takeIf { it.id == gestureId && it.epoch == uiEpoch } ?: return null
        val context = currentContext()
        if (draft !== active || active.epoch != uiEpoch) return null
        if (!eligible(context) || !sameOwner(active.context, context) ||
            context.route.known && context.route != active.observed.route) {
            retire(ServiceMediaVolumeIssue.OUTPUT_CHANGED)
            requestFreshRead()
            return null
        }
        return active
    }

    private fun retire(issue: ServiceMediaVolumeIssue) {
        draft = null
        snapshotContext = null
        uiEpoch = UUID.randomUUID().toString()
        pending?.retired = true
        mutableState.value = ServiceMediaVolumeUiState(phase = if (pending == null) MediaVolumeClientPhase.IDLE
            else MediaVolumeClientPhase.CANCELLING, issue = issue)
    }

    private fun isCurrent(request: VolumeRequest, context: MediaVolumeClientContext): Boolean =
        !request.retired && request.epoch == uiEpoch && request.caller?.isActive != false &&
            eligible(context) && sameOwner(request.context, context) &&
            (request.command == MediaVolumeCommands.read || request.context.route == context.route)

    private fun eligible(context: MediaVolumeClientContext): Boolean =
        !closed && visible && scope.isActive && context.eligible && context.ownerId != null

    private fun sameOwner(expected: MediaVolumeClientContext, actual: MediaVolumeClientContext): Boolean =
        expected.connectionGeneration == actual.connectionGeneration && expected.ownerId == actual.ownerId &&
            expected.backendGeneration == actual.backendGeneration

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())

    private class VolumeDraft(val id: String, val epoch: String, val context: MediaVolumeClientContext,
        val observed: MediaVolumeSnapshot, var target: Int)

    private class VolumeRequest(val command: SessionCommand, val context: MediaVolumeClientContext,
        var epoch: String, val expectedRoute: PlaybackRouteSessionState?,
        val caller: Job?, val onSettled: ((Boolean) -> Unit)?) {
        var future: ListenableFuture<SessionResult>? = null
        var deadline: Runnable? = null
        var retired = false
    }

    companion object {
        const val REPLY_DEADLINE_MS = 3_000L
    }
}
