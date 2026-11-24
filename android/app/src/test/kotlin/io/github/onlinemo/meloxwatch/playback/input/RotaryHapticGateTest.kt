package io.github.onlinemo.meloxwatch.playback.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RotaryHapticGateTest {
    private val context = RotaryInputContext("playback", 1L, foreground = true)
    private fun result(issue: Long, changed: Boolean = true) =
        RotaryAppliedStep(RotaryStep("playback", 1L, RotaryTarget.MEDIA_VOLUME, 1, issue), changed)
    private fun evaluate(state: RotaryHapticState = RotaryHapticState(), issue: Long = 0L, now: Long = issue,
        changed: Boolean = true, enabled: Boolean = true, vibrator: Boolean = true, owner: RotaryInputContext = context) =
        RotaryHapticGate.evaluate(state, result(issue, changed), owner, now, enabled, vibrator)

    @Test fun actualEffectiveDiscreteAdjustmentRequestsOneShortPulse() {
        assertTrue(evaluate().requestPulse)
        assertTrue(RotaryHapticGate.PULSE_DURATION_MS in 1L..20L)
    }

    @Test fun defaultOffConsumesTheOutcomeWithoutAnyPulseOrLaterReplay() {
        val off = RotaryHapticGate.evaluate(RotaryHapticState(), result(0L), context, 0L)
        assertFalse(off.requestPulse)
        val enabled = evaluate(off.state, issue = 0L, now = 200L)
        assertFalse(enabled.requestPulse)
    }

    @Test fun unchangedVolumeRejectedSeekAndAbsentVibratorNeverRequestFeedback() {
        assertFalse(evaluate(changed = false).requestPulse)
        assertFalse(evaluate(vibrator = false).requestPulse)
        val empty = RotaryHapticGate.evaluate(RotaryHapticState(), null, context, 0L, enabled = true)
        assertFalse(empty.requestPulse)
    }

    @Test fun rapidEffectiveAdjustmentsAreLimitedToThePulseInterval() {
        val first = evaluate()
        val rapid = evaluate(first.state, issue = 100L)
        val next = evaluate(rapid.state, issue = 150L)
        assertTrue(first.requestPulse)
        assertFalse(rapid.requestPulse)
        assertTrue(next.requestPulse)
    }

    @Test fun duplicateOrOutOfOrderAcknowledgementsCannotVibrateAgain() {
        val first = evaluate(issue = 100L)
        val duplicate = evaluate(first.state, issue = 100L, now = 500L)
        val old = evaluate(duplicate.state, issue = 50L, now = 600L)
        assertFalse(duplicate.requestPulse)
        assertFalse(old.requestPulse)
        assertSame(first.state, duplicate.state)
    }

    @Test fun backgroundListAndChangedFocusOrPreferencesRejectDelayedFeedback() {
        for (owner in listOf(context.copy(foreground = false), context.copy(listHasPriority = true),
            context.copy(focusToken = null), context.copy(focusToken = "other"), context.copy(settingsRevision = 2L))) {
            assertFalse(evaluate(owner = owner).requestPulse)
        }
    }

    @Test fun feedbackOlderThanItsBoundAndFutureEventsAreDiscarded() {
        assertFalse(evaluate(issue = 0L, now = 1_001L).requestPulse)
        assertFalse(evaluate(issue = 100L, now = 99L).requestPulse)
        assertTrue(evaluate(issue = 0L, now = 1_000L).requestPulse)
    }

    @Test fun switchingOffAndOnDoesNotResetTheGlobalPulseInterval() {
        val first = evaluate()
        val off = evaluate(first.state, issue = 100L, enabled = false)
        val on = evaluate(off.state, issue = 149L)
        assertFalse(off.requestPulse)
        assertFalse(on.requestPulse)
        assertTrue(evaluate(on.state, issue = 150L).requestPulse)
    }

    @Test fun regressingTimeCannotCreateASecondPulse() {
        val first = evaluate(issue = 200L)
        val regressed = evaluate(first.state, issue = 201L, now = 100L)
        assertFalse(regressed.requestPulse)
        assertSame(first.state, regressed.state)
        assertFalse(evaluate(regressed.state, issue = 300L, now = 300L).requestPulse)
        assertTrue(evaluate(regressed.state, issue = 350L, now = 350L).requestPulse)
    }
}
