package io.github.onlinemo.meloxwatch.playback.sleep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SleepTimerSummaryTest {
    private fun clock(elapsed: Long, boot: String? = "boot-1") = SleepTimerClockSample(boot, elapsed)
    private fun timer(minutes: Int = 2, start: Long = 1_000L) =
        SleepTimerReducer.arm(minutes, SleepTimerEndMode.AFTER_CURRENT_TRACK, clock(start)).state

    @Test fun defaultOffRemovesOnlyTheSummary() {
        val state = timer()
        assertNull(sleepTimerSummary(state, clock(2_000L)))
        assertNotNull(state.active)
        assertNull(state.lastStop)
    }

    @Test fun notificationRefusalDoesNotCancelOrDuplicateTheTimer() {
        val state = timer()
        val token = requireNotNull(state.active).token
        assertNull(sleepTimerSummary(state, clock(2_000L), enabled = true, notificationAllowed = false))
        assertEquals(token, state.active?.token)
        assertNotNull(sleepTimerSummary(state, clock(2_000L), enabled = true, notificationAllowed = true))
    }

    @Test fun remainingMinutesRoundUpWithoutChangingTheDeadlineOrMode() {
        val state = timer()
        val first = requireNotNull(sleepTimerSummary(state, clock(1_000L), enabled = true))
        val almost = requireNotNull(sleepTimerSummary(state, clock(60_999L), enabled = true))
        val next = requireNotNull(sleepTimerSummary(state, clock(61_000L), enabled = true))
        assertEquals(2, first.remainingMinutes)
        assertEquals(2, almost.remainingMinutes)
        assertEquals(1, next.remainingMinutes)
        assertEquals(requireNotNull(state.active).token, next.token)
        assertEquals(SleepTimerEndMode.AFTER_CURRENT_TRACK, next.endMode)
    }

    @Test fun nextUpdatesUseMinuteBucketBoundariesAndTheFinalDeadline() {
        val state = timer()
        val first = requireNotNull(sleepTimerSummary(state, clock(1_000L), enabled = true))
        val between = requireNotNull(sleepTimerSummary(state, clock(1_500L), enabled = true))
        val last = requireNotNull(sleepTimerSummary(state, clock(61_000L), enabled = true))
        assertEquals(61_000L, first.nextUpdateElapsedMs)
        assertEquals(first.nextUpdateElapsedMs, between.nextUpdateElapsedMs)
        assertEquals(121_000L, last.nextUpdateElapsedMs)
    }

    @Test fun theCountdownSummaryClearsAtTheDeadlineEvenBeforeWaitingEnds() {
        val state = timer()
        assertNull(sleepTimerSummary(state, clock(121_000L), enabled = true))
        assertNotNull(state.active)
    }

    @Test fun aWaitingTrackHasNoCountdownRefreshTask() {
        val state = timer()
        val waiting = SleepTimerReducer.onDeadline(state, requireNotNull(state.active).token, clock(121_000L),
            SleepTimerTrackBinding("session", "queue", "entry", 0L)).state
        assertNotNull(waiting.active?.waitingFor)
        assertNull(sleepTimerSummary(waiting, clock(122_000L), enabled = true))
    }

    @Test fun cancellationAndInterruptedRecoveryClearTheSummary() {
        val state = timer()
        assertNull(sleepTimerSummary(SleepTimerReducer.cancel(state).state, clock(2_000L), enabled = true))
        val interrupted = SleepTimerReducer.restore(state, clock(2_000L, "boot-2")).state
        assertNull(sleepTimerSummary(interrupted, clock(2_000L, "boot-2"), enabled = true))
    }

    @Test fun invalidClockDomainsCannotDisplayAFalseRemainingTime() {
        val state = timer()
        assertNull(sleepTimerSummary(state, clock(999L), enabled = true))
        assertNull(sleepTimerSummary(state, clock(2_000L, "boot-2"), enabled = true))
        assertNull(sleepTimerSummary(state, clock(2_000L, null), enabled = true))
    }

    @Test fun maximumDurationNearLongBoundaryKeepsAValidBoundedUpdate() {
        val start = Long.MAX_VALUE - 180 * 60_000L
        val state = timer(180, start)
        val summary = requireNotNull(sleepTimerSummary(state, clock(start), enabled = true))
        assertEquals(180, summary.remainingMinutes)
        assertEquals(start + 60_000L, summary.nextUpdateElapsedMs)
        val final = requireNotNull(sleepTimerSummary(state, clock(Long.MAX_VALUE - 1L), enabled = true))
        assertEquals(1, final.remainingMinutes)
        assertEquals(Long.MAX_VALUE, final.nextUpdateElapsedMs)
    }
}
