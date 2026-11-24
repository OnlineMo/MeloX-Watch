package io.github.onlinemo.meloxwatch.playback.input

import io.github.onlinemo.meloxwatch.model.QueueSeekBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RotaryInputPolicyTest {
    private val context = RotaryInputContext("playback", 1L, foreground = true)
    private val enabled = RotaryInputSettings(enabled = true)
    private fun event(time: Long, delta: Double, focus: String = "playback") = RotaryInputEvent(focus, time, delta)
    private fun evaluate(state: RotaryInputState = RotaryInputState(), time: Long = 0L, delta: Double = 1.0,
        owner: RotaryInputContext = context, settings: RotaryInputSettings = enabled) =
        RotaryInputPolicy.evaluate(state, event(time, delta, owner.focusToken ?: "absent"), owner, settings)
    private fun binding(duration: Long = 60_000L) =
        QueueSeekBinding("preview", "session", 2L, "queue", 3L, "entry", "location", 4L, duration)

    @Test fun defaultOffLeavesEventsAvailableToNormalControls() {
        val settings = RotaryInputSettings()
        assertEquals(RotaryTarget.MEDIA_VOLUME, settings.target)
        val result = evaluate(settings = settings)
        assertFalse(result.consume)
        assertNull(result.step)
    }

    @Test fun aNormalizedDetentIssuesOnlyOneSystemVolumeStep() {
        val result = evaluate()
        val step = requireNotNull(result.step)
        assertTrue(result.consume)
        assertEquals(RotaryTarget.MEDIA_VOLUME, step.target)
        assertEquals(1, step.delta)
        assertEquals(6, RotaryInputPolicy.mediaVolumeTarget(step, 5, 15, fixedVolume = false))
        assertEquals(context.focusToken, step.focusToken)
        assertEquals(context.settingsRevision, step.settingsRevision)
    }

    @Test fun directionAndTargetAreAppliedToTheSameDiscreteInput() {
        val settings = enabled.copy(target = RotaryTarget.SEEK, direction = RotaryDirection.REVERSED)
        val step = requireNotNull(evaluate(settings = settings).step)
        val request = requireNotNull(RotaryInputPolicy.seekRequest(step, binding(), 10_000L))
        assertEquals(-1, step.delta)
        assertEquals(5_000L, request.positionMs)
        assertFalse(request.explicitPlay)
    }

    @Test fun fractionalMovementRequiresOneNetDetent() {
        val first = evaluate(delta = 0.4)
        assertNull(first.step)
        val next = evaluate(first.state, time = 10L, delta = 0.6)
        assertEquals(1, next.step?.delta)
        val backward = evaluate(next.state, time = 110L, delta = -1.0)
        assertEquals(-1, backward.step?.delta)
    }

    @Test fun alternatingJitterCannotCreateASeekOrVolumeStep() {
        val first = evaluate(delta = 0.6)
        val reversed = evaluate(first.state, time = 10L, delta = -0.6)
        val again = evaluate(reversed.state, time = 20L, delta = 0.6)
        assertNull(first.step)
        assertNull(reversed.step)
        assertNull(again.step)
    }

    @Test fun fractionalMovementExpiresInsteadOfCombiningAcrossAQuietGap() {
        val first = evaluate(delta = 0.6)
        val later = evaluate(first.state, time = 251L, delta = 0.6)
        assertNull(later.step)
        assertEquals(0.6, later.state.residualSteps, 0.000000001)
        val complete = evaluate(later.state, time = 350L, delta = 0.4)
        assertEquals(1, complete.step?.delta)
    }

    @Test fun largeAndRapidBurstsHaveNoRetainedStepQueue() {
        val first = evaluate(delta = 1_000.0)
        assertEquals(1, first.step?.delta)
        val fast = evaluate(first.state, time = 1L, delta = 1_000.0)
        val almost = evaluate(fast.state, time = 99L, delta = 1.0)
        assertNull(fast.step)
        assertNull(almost.step)
        assertEquals(0.0, almost.state.residualSteps, 0.0)
        val next = evaluate(almost.state, time = 100L, delta = 1.0)
        assertEquals(1, next.step?.delta)
        val empty = evaluate(next.state, time = 200L, delta = 0.0)
        assertNull(empty.step)
    }

    @Test fun listsBackgroundMissingFocusAndPendingCommandsDoNotStealEvents() {
        for (owner in listOf(context.copy(listHasPriority = true), context.copy(foreground = false),
            context.copy(focusToken = null), context.copy(commandPending = true))) {
            val result = evaluate(owner = owner)
            assertFalse(result.consume)
            assertNull(result.step)
        }
    }

    @Test fun aLateEventFromThePreviousFocusCannotCompleteNewResidualInput() {
        val partial = evaluate(delta = 0.6)
        val newOwner = context.copy(focusToken = "volume-page")
        val old = RotaryInputPolicy.evaluate(partial.state, event(10L, 0.6), newOwner, enabled)
        assertFalse(old.consume)
        assertNull(old.step)
        val current = evaluate(old.state, time = 20L, delta = 0.6, owner = newOwner)
        assertNull(current.step)
    }

    @Test fun preferenceAndFocusChangesClearFractionsButPreserveTheRateLimit() {
        val partial = evaluate(delta = 0.6)
        val changed = evaluate(partial.state, time = 10L, delta = 0.6, owner = context.copy(settingsRevision = 2L))
        assertNull(changed.step)
        val issued = evaluate(time = 100L)
        val switched = evaluate(issued.state, time = 150L, owner = context.copy(focusToken = "volume-page", settingsRevision = 2L),
            settings = enabled.copy(direction = RotaryDirection.REVERSED))
        assertNull(switched.step)
        val off = evaluate(switched.state, time = 160L, settings = RotaryInputSettings())
        assertFalse(off.consume)
        val reenabled = evaluate(off.state, time = 199L)
        assertNull(reenabled.step)
        assertNotNull(evaluate(reenabled.state, time = 200L).step)
    }

    @Test fun nonstandardAndNonfiniteInputsNeverProduceAnAdjustment() {
        val foreign = RotaryInputPolicy.evaluate(RotaryInputState(), event(0L, 1.0).copy(standardRotaryEvent = false), context, enabled)
        assertFalse(foreign.consume)
        assertNull(foreign.step)
        for (delta in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull(evaluate(delta = delta).step)
        }
    }

    @Test fun regressingInputTimeDoesNotResetTheGlobalAdjustmentWatermark() {
        val issued = evaluate(time = 200L)
        val regression = evaluate(issued.state, time = 100L)
        val repeated = evaluate(regression.state, time = 101L)
        assertNull(regression.step)
        assertNull(repeated.step)
        assertEquals(200L, repeated.state.lastIssuedElapsedMs)
        assertNotNull(evaluate(repeated.state, time = 300L).step)
    }

    @Test fun volumeBoundsAndFixedRoutesDoNotIssueFalseEffectiveSteps() {
        val up = requireNotNull(evaluate().step)
        val down = up.copy(delta = -1)
        assertNull(RotaryInputPolicy.mediaVolumeTarget(up, 15, 15, fixedVolume = false))
        assertNull(RotaryInputPolicy.mediaVolumeTarget(down, 0, 15, fixedVolume = false))
        assertNull(RotaryInputPolicy.mediaVolumeTarget(up, 5, 15, fixedVolume = true))
        assertNull(RotaryInputPolicy.mediaVolumeTarget(up, -1, 15, fixedVolume = false))
        assertNull(RotaryInputPolicy.mediaVolumeTarget(up, 5, 0, fixedVolume = false))
        assertEquals(Int.MAX_VALUE, RotaryInputPolicy.mediaVolumeTarget(up, Int.MAX_VALUE - 1, Int.MAX_VALUE, fixedVolume = false))
    }

    @Test fun seekStepsUseTheExactServiceBindingAndStayInsideKnownDuration() {
        val step = requireNotNull(evaluate(settings = enabled.copy(target = RotaryTarget.SEEK)).step)
        val bound = binding(Long.MAX_VALUE)
        val end = requireNotNull(RotaryInputPolicy.seekRequest(step, bound, Long.MAX_VALUE - 1L))
        assertSame(bound, end.binding)
        assertEquals(Long.MAX_VALUE, end.positionMs)
        assertFalse(end.explicitPlay)
        assertNull(RotaryInputPolicy.seekRequest(step, bound, Long.MAX_VALUE))
        assertNull(RotaryInputPolicy.seekRequest(step, bound, -1L))
        val start = requireNotNull(RotaryInputPolicy.seekRequest(step.copy(delta = -1), bound, 1L))
        assertEquals(0L, start.positionMs)
        assertNull(RotaryInputPolicy.seekRequest(step.copy(target = RotaryTarget.MEDIA_VOLUME), bound, 1L))
    }
}
