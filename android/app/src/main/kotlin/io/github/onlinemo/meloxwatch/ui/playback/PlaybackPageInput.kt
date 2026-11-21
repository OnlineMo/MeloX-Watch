package io.github.onlinemo.meloxwatch.ui.playback

import androidx.annotation.MainThread
import io.github.onlinemo.meloxwatch.model.PlaybackControlsPreferences
import io.github.onlinemo.meloxwatch.model.QueueSeekBinding
import io.github.onlinemo.meloxwatch.model.QueueSeekRequest
import io.github.onlinemo.meloxwatch.playback.input.RotaryAppliedStep
import io.github.onlinemo.meloxwatch.playback.input.RotaryInputContext
import io.github.onlinemo.meloxwatch.playback.input.RotaryInputEvent
import io.github.onlinemo.meloxwatch.playback.input.RotaryInputPolicy
import io.github.onlinemo.meloxwatch.playback.input.RotaryInputState
import io.github.onlinemo.meloxwatch.playback.input.RotaryStep
import io.github.onlinemo.meloxwatch.playback.input.RotaryTarget
import io.github.onlinemo.meloxwatch.playback.input.SeekGestureSurface
import io.github.onlinemo.meloxwatch.playback.input.asRotaryInputSettings
import io.github.onlinemo.meloxwatch.playback.input.asSeekProgressSettings
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** All identities are opaque session/UI tokens. Position ticks do not replace either owner. */
data class PlaybackPageInputContext(
    val serviceOwnerId: String?,
    val focusToken: String?,
    val foreground: Boolean,
    val mainVisible: Boolean,
    val independentVisible: Boolean,
    val commandPending: Boolean,
    val positionMs: Long,
    val durationMs: Long?,
    val controls: PlaybackControlsPreferences,
    val controlsStateKey: String,
    val settingsRevision: Long,
    val listHasPriority: Boolean = false,
    val rotaryFocused: Boolean = true,
) {
    init {
        require(serviceOwnerId == null || serviceOwnerId.isNotBlank())
        require(focusToken == null || focusToken.isNotBlank())
        require(positionMs >= 0L && (durationMs == null || durationMs >= 0L))
        require(controlsStateKey.isNotBlank() && settingsRevision >= 0L)
    }
}

/**
 * A single input slot covers both permanent progress helpers and either rotary target. Cancellation
 * keeps that slot occupied until the original callback finishes; changing pages cannot create
 * a replacement helper and admit overlapping work. The service remains the final authority.
 */
@MainThread
class PlaybackPageInput(
    private val scope: CoroutineScope,
    private val currentContext: () -> PlaybackPageInputContext,
    private val acquireBinding: suspend () -> QueueSeekBinding?,
    private val isBindingCurrent: (QueueSeekBinding) -> Boolean,
    private val dispatchSeek: suspend (QueueSeekRequest) -> Boolean,
    private val applyVolumeStep: suspend (RotaryStep) -> Boolean,
    private val onRotaryApplied: (RotaryAppliedStep, RotaryInputContext) -> Unit,
) {
    private var inputEpoch = UUID.randomUUID().toString()
    private var closed = false
    private var admitting = false
    private var rotaryState = RotaryInputState()
    private var rotaryJob: Job? = null
    private var rotaryOwner: RotaryOwner? = null
    private val mutableRotaryPending = MutableStateFlow(false)
    val rotaryPending = mutableRotaryPending.asStateFlow()
    private val main = progressInput(SeekGestureSurface.MAIN_PROGRESS)
    private val independent = progressInput(SeekGestureSurface.INDEPENDENT_CONTROLS)
    val mainState = main.state
    val independentState = independent.state

    val busy: Boolean
        get() = admitting || rotaryJob != null || main.state.value.phase != PlaybackProgressInputPhase.IDLE ||
            independent.state.value.phase != PlaybackProgressInputPhase.IDLE

    /** 宿主只可排除本次已拥有的音量请求；其他音量按钮/草稿仍阻止新的共同输入。 */
    val ownsRotaryVolume: Boolean
        get() = rotaryJob != null && rotaryOwner?.target == RotaryTarget.MEDIA_VOLUME

    fun begin(surface: SeekGestureSurface): String? = admit(null) { helper(surface).begin() }

    fun drag(surface: SeekGestureSurface, gestureId: String, fraction: Double): Boolean = helper(surface).drag(gestureId, fraction)
    fun release(surface: SeekGestureSurface, gestureId: String): Boolean = helper(surface).release(gestureId)
    fun tap(surface: SeekGestureSurface, gestureId: String, fraction: Double): Boolean = helper(surface).tap(gestureId, fraction)
    fun cancel(surface: SeekGestureSurface, gestureId: String) = helper(surface).cancel(gestureId)

    fun setProgress(surface: SeekGestureSurface, fraction: Double): Boolean = admit(false) { helper(surface).setProgress(fraction) }

    /** Buttons are explicit completed intents; they share the same binding checks as dragging. */
    fun seekBy(surface: SeekGestureSurface, deltaMs: Long): Boolean {
        val context = currentContext()
        val duration = context.durationMs?.takeIf { it > 0L } ?: return false
        val position = context.positionMs.coerceIn(0L, duration)
        val target = if (deltaMs >= 0L) position + minOf(deltaMs, duration - position)
            else position - minOf(if (deltaMs == Long.MIN_VALUE) Long.MAX_VALUE else -deltaMs, position)
        return target != position && setProgress(surface, target.toDouble() / duration.toDouble())
    }

    fun progressContext(surface: SeekGestureSurface): PlaybackProgressInputContext {
        val context = currentContext()
        val visible = when (surface) {
            SeekGestureSurface.MAIN_PROGRESS -> context.mainVisible
            SeekGestureSurface.INDEPENDENT_CONTROLS -> context.independentVisible
        }
        val owner = if (visible && context.focusToken != null && context.serviceOwnerId != null)
            "$inputEpoch:${context.focusToken}:${surface.name}:${context.serviceOwnerId}" else null
        return PlaybackProgressInputContext(owner, !closed && context.foreground && visible,
            context.serviceOwnerId != null && !context.commandPending, context.positionMs,
            context.controlsStateKey, context.controls.asSeekProgressSettings())
    }

    fun rotaryContext(): RotaryInputContext = rotaryContext(currentContext())

    private fun rotaryContext(context: PlaybackPageInputContext): RotaryInputContext {
        return RotaryInputContext(context.focusToken?.let { "$inputEpoch:$it" }, context.settingsRevision,
            !closed && context.foreground, listHasPriority = !context.mainVisible || context.listHasPriority || !context.rotaryFocused,
            commandPending = context.commandPending || busy)
    }

    /** The Compose adapter supplies measured, standard encoder units and the actual focus token. */
    fun rotary(elapsedMs: Long, deltaSteps: Double): Boolean {
        val context = currentContext()
        val rotaryInput = rotaryContext(context)
        val focus = rotaryInput.focusToken ?: return false
        if (closed || !scope.isActive) return false
        val decision = RotaryInputPolicy.evaluate(rotaryState,
            RotaryInputEvent(focus, elapsedMs, deltaSteps), rotaryInput, context.controls.asRotaryInputSettings())
        rotaryState = decision.state
        val step = decision.step ?: return decision.consume
        if (!isRotarySnapshotCurrent(context, rotaryInput)) {
            discardRotaryFractions()
            return decision.consume
        }
        startRotary(step, context, rotaryInput)
        return decision.consume
    }

    private fun isRotarySnapshotCurrent(snapshot: PlaybackPageInputContext, captured: RotaryInputContext): Boolean {
        val live = currentContext()
        // A context callback can change settings or focus synchronously. Both targets must
        // validate the same saved mapping immediately before entering the output operation.
        return !closed && scope.isActive && !busy && live.controls == snapshot.controls &&
            live.controlsStateKey == snapshot.controlsStateKey && rotaryContext(live) == captured
    }

    fun contextChanged() {
        main.contextChanged()
        independent.contextChanged()
        rotaryOwner?.takeIf { !matches(it, currentContext(), rotaryContext()) }?.let { rotaryJob?.cancel() }
        val context = currentContext()
        val focus = rotaryContext().focusToken
        if (rotaryState.focusToken != focus || rotaryState.settingsRevision != context.settingsRevision ||
            rotaryState.settings != context.controls.asRotaryInputSettings() || !context.foreground ||
            !context.mainVisible || context.listHasPriority || !context.rotaryFocused) {
            discardRotaryFractions()
        }
    }

    fun invalidate() {
        inputEpoch = UUID.randomUUID().toString()
        main.invalidate()
        independent.invalidate()
        rotaryJob?.cancel()
        discardRotaryFractions()
    }

    fun close() {
        closed = true
        invalidate()
        main.close()
        independent.close()
    }

    private fun progressInput(surface: SeekGestureSurface) = PlaybackProgressInput(scope, surface,
        currentContext = { progressContext(surface) }, acquireBinding = acquireBinding,
        isBindingCurrent = { _, binding -> isBindingCurrent(binding) }, dispatchSeek = dispatchSeek)

    private fun helper(surface: SeekGestureSurface) = when (surface) {
        SeekGestureSurface.MAIN_PROGRESS -> main
        SeekGestureSurface.INDEPENDENT_CONTROLS -> independent
    }

    private fun startRotary(step: RotaryStep, context: PlaybackPageInputContext, capturedRotary: RotaryInputContext) {
        if (busy || step.target == RotaryTarget.SEEK && (context.serviceOwnerId == null || context.durationMs == null)) return
        val owner = RotaryOwner(context.serviceOwnerId, context.controlsStateKey, context.controls, capturedRotary, step.target)
        rotaryOwner = owner
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val changed = when (step.target) {
                    RotaryTarget.MEDIA_VOLUME -> {
                        if (!matches(owner, currentContext(), rotaryContext())) return@launch
                        currentCoroutineContext().ensureActive()
                        applyVolumeStep(step)
                    }
                    RotaryTarget.SEEK -> {
                        val binding = acquireBinding() ?: return@launch
                        currentCoroutineContext().ensureActive()
                        val livePosition = currentContext().positionMs
                        val request = RotaryInputPolicy.seekRequest(step, binding, livePosition) ?: return@launch
                        if (!isBindingCurrent(binding)) return@launch
                        currentCoroutineContext().ensureActive()
                        if (!matches(owner, currentContext(), rotaryContext())) return@launch
                        // Synchronous validation/context callbacks can retire this job. Cancellation
                        // must be observed before even entering a dispatcher that may issue IPC.
                        currentCoroutineContext().ensureActive()
                        dispatchSeek(request)
                    }
                }
                currentCoroutineContext().ensureActive()
                val live = currentContext()
                val liveRotary = rotaryContext(live)
                if (matches(owner, live, liveRotary)) {
                    currentCoroutineContext().ensureActive()
                    onRotaryApplied(RotaryAppliedStep(step, changed), liveRotary)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Session/backend failures already have an observed, accessible status. An
                // unsuccessful or stale rotary action never requests a success vibration.
            }
        }
        rotaryJob = job
        mutableRotaryPending.value = true
        job.invokeOnCompletion {
            if (rotaryJob === job) {
                rotaryJob = null
                rotaryOwner = null
                mutableRotaryPending.value = false
            }
        }
        job.start()
    }

    private fun matches(owner: RotaryOwner, context: PlaybackPageInputContext, rotary: RotaryInputContext): Boolean =
        !closed && scope.isActive && context.foreground && context.mainVisible && context.rotaryFocused &&
            !context.listHasPriority && !context.commandPending &&
            context.serviceOwnerId == owner.serviceOwnerId && context.controlsStateKey == owner.controlsStateKey &&
            context.controls == owner.controls && rotary.focusToken == owner.context.focusToken &&
            rotary.settingsRevision == owner.context.settingsRevision

    private fun discardRotaryFractions() {
        rotaryState = RotaryInputState(lastEventElapsedMs = rotaryState.lastEventElapsedMs,
            lastIssuedElapsedMs = rotaryState.lastIssuedElapsedMs)
    }

    private inline fun <T> admit(rejected: T, operation: () -> T): T {
        if (closed || busy || !scope.isActive) return rejected
        admitting = true
        val epoch = inputEpoch
        try {
            val context = currentContext()
            if (closed || !scope.isActive || context.commandPending || inputEpoch != epoch) return rejected
            return operation()
        } finally {
            admitting = false
        }
    }

    private data class RotaryOwner(val serviceOwnerId: String?, val controlsStateKey: String,
        val controls: PlaybackControlsPreferences, val context: RotaryInputContext, val target: RotaryTarget)
}
