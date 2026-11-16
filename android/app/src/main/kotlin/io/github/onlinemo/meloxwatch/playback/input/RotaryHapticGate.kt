package io.github.onlinemo.meloxwatch.playback.input

/** Construct only from a real volume readback change or an accepted service seek result. */
data class RotaryAppliedStep(val step: RotaryStep, val changed: Boolean)

data class RotaryHapticState(val lastObservedIssueElapsedMs: Long? = null, val lastPulseElapsedMs: Long? = null) {
    init {
        require(lastObservedIssueElapsedMs == null || lastObservedIssueElapsedMs >= 0L)
        require(lastPulseElapsedMs == null || lastPulseElapsedMs >= 0L)
    }
}
data class RotaryHapticDecision(val state: RotaryHapticState, val requestPulse: Boolean)

object RotaryHapticGate {
    const val MIN_PULSE_INTERVAL_MS = 150L
    const val MAX_FEEDBACK_AGE_MS = 1_000L
    const val PULSE_DURATION_MS = 8L

    fun evaluate(
        state: RotaryHapticState,
        applied: RotaryAppliedStep?,
        context: RotaryInputContext,
        nowElapsedMs: Long,
        enabled: Boolean = false,
        hasVibrator: Boolean = true,
    ): RotaryHapticDecision {
        require(nowElapsedMs >= 0L)
        val outcome = applied ?: return RotaryHapticDecision(state, requestPulse = false)
        val step = outcome.step
        if (step.issuedElapsedMs > nowElapsedMs || (state.lastObservedIssueElapsedMs != null && step.issuedElapsedMs <= state.lastObservedIssueElapsedMs)) {
            return RotaryHapticDecision(state, requestPulse = false)
        }
        // Disabled/failed outcomes are consumed too; toggling later cannot replay old feedback.
        val observed = state.copy(lastObservedIssueElapsedMs = step.issuedElapsedMs)
        if (!enabled || !hasVibrator || !outcome.changed || !context.foreground || context.listHasPriority ||
            step.focusToken != context.focusToken || step.settingsRevision != context.settingsRevision ||
            nowElapsedMs - step.issuedElapsedMs > MAX_FEEDBACK_AGE_MS) {
            return RotaryHapticDecision(observed, requestPulse = false)
        }
        if (state.lastPulseElapsedMs != null && (nowElapsedMs < state.lastPulseElapsedMs || nowElapsedMs - state.lastPulseElapsedMs < MIN_PULSE_INTERVAL_MS)) {
            return RotaryHapticDecision(observed, requestPulse = false)
        }
        return RotaryHapticDecision(observed.copy(lastPulseElapsedMs = nowElapsedMs), requestPulse = true)
    }
}
