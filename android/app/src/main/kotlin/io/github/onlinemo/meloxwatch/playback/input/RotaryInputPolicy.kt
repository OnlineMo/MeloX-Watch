package io.github.onlinemo.meloxwatch.playback.input

import io.github.onlinemo.meloxwatch.model.QueueSeekBinding
import io.github.onlinemo.meloxwatch.model.QueueSeekRequest
import kotlin.math.abs

enum class RotaryTarget { MEDIA_VOLUME, SEEK }
enum class RotaryDirection { NORMAL, REVERSED }

data class RotaryInputSettings(
    val enabled: Boolean = false,
    val target: RotaryTarget = RotaryTarget.MEDIA_VOLUME,
    val direction: RotaryDirection = RotaryDirection.NORMAL,
)

data class RotaryInputContext(
    val focusToken: String?,
    val settingsRevision: Long,
    val foreground: Boolean,
    val listHasPriority: Boolean = false,
    val commandPending: Boolean = false,
) {
    init {
        require(focusToken == null || focusToken.isNotBlank())
        require(settingsRevision >= 0L)
    }
}

/** The Android adapter must normalize a standard encoder's axis to detent units after measurement. */
data class RotaryInputEvent(
    val focusToken: String,
    val elapsedMs: Long,
    val deltaSteps: Double,
    val standardRotaryEvent: Boolean = true,
) {
    init { require(focusToken.isNotBlank() && elapsedMs >= 0L) }
}

data class RotaryInputState(
    val focusToken: String? = null,
    val settingsRevision: Long? = null,
    val settings: RotaryInputSettings? = null,
    val residualSteps: Double = 0.0,
    val lastEventElapsedMs: Long? = null,
    val lastIssuedElapsedMs: Long? = null,
) {
    init {
        require(residualSteps.isFinite() && abs(residualSteps) < 1.0)
        require(focusToken == null || focusToken.isNotBlank())
        require((focusToken == null) == (settingsRevision == null) && (focusToken == null) == (settings == null))
        require(settingsRevision == null || settingsRevision >= 0L)
        require(lastEventElapsedMs == null || lastEventElapsedMs >= 0L)
        require(lastIssuedElapsedMs == null || (lastEventElapsedMs != null && lastIssuedElapsedMs in 0L..lastEventElapsedMs))
    }
}

data class RotaryStep(
    val focusToken: String,
    val settingsRevision: Long,
    val target: RotaryTarget,
    val delta: Int,
    val issuedElapsedMs: Long,
) {
    init {
        require(focusToken.isNotBlank() && settingsRevision >= 0L && issuedElapsedMs >= 0L)
        require(delta == -1 || delta == 1)
    }
}

data class RotaryInputDecision(val state: RotaryInputState, val consume: Boolean, val step: RotaryStep? = null)

object RotaryInputPolicy {
    const val MIN_STEP_INTERVAL_MS = 100L
    const val RESIDUAL_EXPIRY_MS = 250L
    const val SEEK_STEP_MS = 5_000L

    fun evaluate(
        state: RotaryInputState,
        event: RotaryInputEvent,
        context: RotaryInputContext,
        settings: RotaryInputSettings,
    ): RotaryInputDecision {
        if (!settings.enabled || !context.foreground || context.focusToken == null || context.listHasPriority || context.commandPending) {
            return RotaryInputDecision(clearOwner(state), consume = false)
        }
        if (!event.standardRotaryEvent) return RotaryInputDecision(state, consume = false)
        if (event.focusToken != context.focusToken) return RotaryInputDecision(clearOwner(state), consume = false)
        if (!event.deltaSteps.isFinite()) return RotaryInputDecision(clearOwner(state), consume = true)
        val owned = if (state.focusToken == context.focusToken && state.settingsRevision == context.settingsRevision && state.settings == settings)
            state else RotaryInputState(context.focusToken, context.settingsRevision, settings,
                lastEventElapsedMs = state.lastEventElapsedMs, lastIssuedElapsedMs = state.lastIssuedElapsedMs)
        if (owned.lastEventElapsedMs != null && event.elapsedMs < owned.lastEventElapsedMs) {
            return RotaryInputDecision(clearOwner(owned), consume = true)
        }
        val recent = owned.copy(lastEventElapsedMs = event.elapsedMs)
        if (owned.lastIssuedElapsedMs != null && event.elapsedMs - owned.lastIssuedElapsedMs < MIN_STEP_INTERVAL_MS) {
            // Discard throttled input, including fractions: it must not become a delayed jump.
            return RotaryInputDecision(recent.copy(residualSteps = 0.0), consume = true)
        }
        val residual = if (owned.lastEventElapsedMs != null && event.elapsedMs - owned.lastEventElapsedMs > RESIDUAL_EXPIRY_MS)
            0.0 else owned.residualSteps
        val sign = if (settings.direction == RotaryDirection.NORMAL) 1.0 else -1.0
        val total = residual + event.deltaSteps.coerceIn(-1.0, 1.0) * sign
        if (abs(total) < 1.0 - 1e-9) return RotaryInputDecision(recent.copy(residualSteps = total), consume = true)
        // One step at most per event, with no burst queue or retained excess.
        val step = RotaryStep(context.focusToken, context.settingsRevision, settings.target, if (total > 0.0) 1 else -1, event.elapsedMs)
        return RotaryInputDecision(recent.copy(residualSteps = 0.0, lastIssuedElapsedMs = event.elapsedMs), consume = true, step = step)
    }

    fun seekRequest(step: RotaryStep, binding: QueueSeekBinding, positionMs: Long): QueueSeekRequest? {
        if (step.target != RotaryTarget.SEEK || positionMs !in 0L..binding.durationMs) return null
        val target = if (step.delta > 0) positionMs + minOf(SEEK_STEP_MS, binding.durationMs - positionMs)
            else positionMs - minOf(SEEK_STEP_MS, positionMs)
        return if (target == positionMs) null else QueueSeekRequest(binding, target, explicitPlay = false)
    }

    fun mediaVolumeTarget(step: RotaryStep, current: Int, maximum: Int, fixedVolume: Boolean): Int? {
        if (step.target != RotaryTarget.MEDIA_VOLUME || fixedVolume || maximum <= 0 || current !in 0..maximum) return null
        if ((step.delta < 0 && current == 0) || (step.delta > 0 && current == maximum)) return null
        return current + step.delta
    }

    // Focus/settings changes discard fractions, while the global rate watermark still applies.
    private fun clearOwner(state: RotaryInputState) = RotaryInputState(
        lastEventElapsedMs = state.lastEventElapsedMs, lastIssuedElapsedMs = state.lastIssuedElapsedMs)
}
