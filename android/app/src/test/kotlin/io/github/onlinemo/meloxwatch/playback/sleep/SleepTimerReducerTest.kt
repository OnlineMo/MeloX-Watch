package io.github.onlinemo.meloxwatch.playback.sleep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerReducerTest {
    private val track = SleepTimerTrackBinding("session-1", "queue-1", "entry-1", 4L)
    private fun clock(elapsed: Long, boot: String? = "boot-1") = SleepTimerClockSample(boot, elapsed)
    private fun timer(mode: SleepTimerEndMode = SleepTimerEndMode.AFTER_CURRENT_TRACK, boot: String? = "boot-1") =
        SleepTimerReducer.arm(1, mode, clock(1_000L, boot)).state
    private fun token(state: SleepTimerState) = requireNotNull(state.active).token
    private fun waiting(state: SleepTimerState) = SleepTimerReducer.onDeadline(state, token(state), clock(61_000L), track).state

    @Test fun immediateDeadlinePausesAndSettlesTheTimer() {
        val state = timer(SleepTimerEndMode.PAUSE_IMMEDIATELY)
        val result = SleepTimerReducer.onDeadline(state, token(state), clock(61_000L), track)
        assertTrue(result.pauseRequired)
        assertNull(result.state.active)
        assertEquals(SleepTimerStopReason.DEADLINE_REACHED, result.state.lastStop?.reason)
    }

    @Test fun earlyDeadlineTaskKeepsTheOriginalDeadlineWithoutPausing() {
        val state = timer()
        val result = SleepTimerReducer.onDeadline(state, token(state), clock(60_999L), track)
        assertSame(state, result.state)
        assertFalse(result.pauseRequired)
        assertEquals(61_000L, token(result.state).deadlineElapsedMs)
    }

    @Test fun waitingModeBindsTheExactTraversalAtExpiration() {
        val state = timer()
        val result = SleepTimerReducer.onDeadline(state, token(state), clock(61_000L), track)
        assertEquals(track, result.state.active?.waitingFor)
        assertFalse(result.pauseRequired)
        val repeated = SleepTimerReducer.onDeadline(result.state, token(state), clock(61_500L), track)
        assertSame(result.state, repeated.state)
        assertFalse(repeated.pauseRequired)
    }

    @Test fun naturalEndBeforeTheDeadlineAllowsNormalRepeatOrAdvance() {
        val state = timer()
        val result = SleepTimerReducer.onNaturalEnd(state, clock(60_999L), track, track)
        assertSame(state, result.state)
        assertFalse(result.pauseRequired)
        assertFalse(result.blockNaturalAdvance)
    }

    @Test fun naturalEndAtTheDeadlineWinsBeforeADelayedTimerTask() {
        val state = timer()
        val ended = SleepTimerReducer.onNaturalEnd(state, clock(61_000L), track, track)
        assertTrue(ended.pauseRequired)
        assertTrue(ended.blockNaturalAdvance)
        assertEquals(SleepTimerStopReason.CURRENT_TRACK_ENDED, ended.state.lastStop?.reason)
        val late = SleepTimerReducer.onDeadline(ended.state, token(state), clock(61_001L), track)
        assertSame(ended.state, late.state)
        assertFalse(late.pauseRequired)
    }

    @Test fun deadlineThenNaturalEndAtTheSameTimeAlsoWinsBeforeRepeat() {
        val state = timer()
        val due = SleepTimerReducer.onDeadline(state, token(state), clock(61_000L), track)
        val ended = SleepTimerReducer.onNaturalEnd(due.state, clock(61_000L), track, track)
        assertTrue(ended.pauseRequired)
        assertTrue(ended.blockNaturalAdvance)
        assertNull(ended.state.active)
        assertEquals(SleepTimerStopReason.CURRENT_TRACK_ENDED, ended.state.lastStop?.reason)
    }

    @Test fun immediateModeAlsoBlocksANaturalEndFirstAtTheCutoff() {
        val ended = SleepTimerReducer.onNaturalEnd(timer(SleepTimerEndMode.PAUSE_IMMEDIATELY), clock(61_000L), track, track)
        assertTrue(ended.pauseRequired)
        assertTrue(ended.blockNaturalAdvance)
        assertEquals(SleepTimerStopReason.DEADLINE_REACHED, ended.state.lastStop?.reason)
    }

    @Test fun staleCompletionCannotStopAnotherTraversalOrSession() {
        val state = waiting(timer())
        for (current in listOf(track.copy(playbackCycle = 5L), track.copy(sessionId = "session-2"), track.copy(queueEntryId = "entry-2"))) {
            val stale = SleepTimerReducer.onNaturalEnd(state, clock(61_100L), track, current)
            assertSame(state, stale.state)
            assertFalse(stale.pauseRequired)
            assertFalse(stale.blockNaturalAdvance)
        }
    }

    @Test fun repeatedSettingsWithIdenticalDeadlinesStillInvalidateOldTasks() {
        val old = timer()
        val replacement = timer()
        assertEquals(token(old).deadlineElapsedMs, token(replacement).deadlineElapsedMs)
        assertNotEquals(token(old).generation, token(replacement).generation)
        val stale = SleepTimerReducer.onDeadline(replacement, token(old), clock(61_000L), track)
        assertSame(replacement, stale.state)
        assertFalse(stale.pauseRequired)
    }

    @Test fun cancelImmediatelyInvalidatesTheDeadlineTaskWithoutPausing() {
        val state = timer()
        val cancelled = SleepTimerReducer.cancel(state)
        assertFalse(cancelled.pauseRequired)
        assertEquals(SleepTimerStopReason.USER_CANCELLED, cancelled.state.lastStop?.reason)
        val late = SleepTimerReducer.onDeadline(cancelled.state, token(state), clock(61_000L), track)
        assertSame(cancelled.state, late.state)
        assertFalse(late.pauseRequired)
    }

    @Test fun manualPauseCancelsWaitingModeBeforeAndAfterExpiration() {
        val countdown = timer()
        for (state in listOf(countdown, waiting(countdown))) {
            val result = SleepTimerReducer.onUserPause(state)
            assertNull(result.state.active)
            assertFalse(result.pauseRequired)
            assertEquals(SleepTimerStopReason.USER_PAUSED, result.state.lastStop?.reason)
        }
    }

    @Test fun manualTrackChangeCancelsWaitingModeBeforeAndAfterExpiration() {
        val countdown = timer()
        for (state in listOf(countdown, waiting(countdown))) {
            val result = SleepTimerReducer.onManualTrackChange(state)
            assertNull(result.state.active)
            assertFalse(result.pauseRequired)
            assertEquals(SleepTimerStopReason.USER_CHANGED_TRACK, result.state.lastStop?.reason)
        }
    }

    @Test fun immediateModeKeepsItsCountdownAcrossManualPauseAndTrackChange() {
        val state = timer(SleepTimerEndMode.PAUSE_IMMEDIATELY)
        assertSame(state, SleepTimerReducer.onUserPause(state).state)
        assertSame(state, SleepTimerReducer.onManualTrackChange(state).state)
    }

    @Test fun expirationWithoutACurrentTrackSettlesPausedInsteadOfWaitingForever() {
        val state = timer()
        val result = SleepTimerReducer.onDeadline(state, token(state), clock(61_000L), null)
        assertNull(result.state.active)
        assertTrue(result.pauseRequired)
        assertEquals(SleepTimerStopReason.DEADLINE_REACHED, result.state.lastStop?.reason)
    }

    @Test fun aLostWaitingTraversalCannotPauseTheReplacementTrack() {
        val state = waiting(timer())
        val replacement = track.copy(queueEntryId = "entry-2", playbackCycle = 5L)
        val result = SleepTimerReducer.onDeadline(state, token(state), clock(62_000L), replacement)
        assertNull(result.state.active)
        assertFalse(result.pauseRequired)
        assertEquals(SleepTimerStopReason.WAITING_TRACK_LOST, result.state.lastStop?.reason)
        val natural = SleepTimerReducer.onNaturalEnd(state, clock(62_000L), replacement, replacement)
        assertFalse(natural.pauseRequired)
        assertFalse(natural.blockNaturalAdvance)
    }

    @Test fun sameBootFutureRecoveryRetainsOneDeadlineAndRequiresPause() {
        val state = timer()
        val recovered = SleepTimerReducer.restore(state, clock(20_000L))
        assertSame(state, recovered.state)
        assertTrue(recovered.pauseRequired)
        assertFalse(recovered.blockNaturalAdvance)
        assertEquals(token(state), token(recovered.state))
    }

    @Test fun expiredSameBootRecoverySettlesInsteadOfReschedulingOrPlaying() {
        val recovered = SleepTimerReducer.restore(timer(), clock(61_000L))
        assertTrue(recovered.pauseRequired)
        assertNull(recovered.state.active)
        assertEquals(SleepTimerStopReason.RECOVERED_EXPIRED, recovered.state.lastStop?.reason)
    }

    @Test fun waitingRecoveryNeverRebindsToANewServiceAndStartsPlayback() {
        val recovered = SleepTimerReducer.restore(waiting(timer()), clock(62_000L))
        assertTrue(recovered.pauseRequired)
        assertNull(recovered.state.active)
        assertEquals(SleepTimerStopReason.RECOVERED_EXPIRED, recovered.state.lastStop?.reason)
    }

    @Test fun rebootRecoveryReportsInterruptionEvenIfTheOldDeadlineLooksFuture() {
        val recovered = SleepTimerReducer.restore(timer(), clock(100L, "boot-2"))
        assertTrue(recovered.pauseRequired)
        assertNull(recovered.state.active)
        assertEquals(SleepTimerStopReason.DEVICE_RESTARTED, recovered.state.lastStop?.reason)
    }

    @Test fun unverifiedBootWorksInProcessButCannotResumeADurableDeadline() {
        val state = timer(SleepTimerEndMode.PAUSE_IMMEDIATELY, boot = null)
        val due = SleepTimerReducer.onDeadline(state, token(state), clock(61_000L, null), track)
        assertTrue(due.pauseRequired)
        assertEquals(SleepTimerStopReason.DEADLINE_REACHED, due.state.lastStop?.reason)
        val recovered = SleepTimerReducer.restore(state, clock(2_000L, null))
        assertTrue(recovered.pauseRequired)
        assertNull(recovered.state.active)
        assertEquals(SleepTimerStopReason.BOOT_UNVERIFIED, recovered.state.lastStop?.reason)
    }

    @Test fun regressingMonotonicTimeCannotExtendAnActiveOrExpiredWait() {
        for (state in listOf(timer(), waiting(timer()))) {
            val result = SleepTimerReducer.onDeadline(state, token(state), clock(999L), track)
            assertTrue(result.pauseRequired)
            assertNull(result.state.active)
            assertEquals(SleepTimerStopReason.INVALID_MONOTONIC_CLOCK, result.state.lastStop?.reason)
        }
        val wait = waiting(timer())
        val natural = SleepTimerReducer.onNaturalEnd(wait, clock(60_999L), track, track)
        assertTrue(natural.pauseRequired)
        assertTrue(natural.blockNaturalAdvance)
    }

    @Test fun matchedTasksWithAnUnverifiableBootFailPaused() {
        val state = timer()
        val result = SleepTimerReducer.onDeadline(state, token(state), clock(61_000L, null), track)
        assertTrue(result.pauseRequired)
        assertEquals(SleepTimerStopReason.BOOT_UNVERIFIED, result.state.lastStop?.reason)
    }

    @Test fun duplicateNaturalCompletionDoesNotIssueAnotherTimerPause() {
        val ended = SleepTimerReducer.onNaturalEnd(waiting(timer()), clock(61_100L), track, track)
        val duplicate = SleepTimerReducer.onNaturalEnd(ended.state, clock(61_200L), track, track)
        assertSame(ended.state, duplicate.state)
        assertFalse(duplicate.pauseRequired)
        assertFalse(duplicate.blockNaturalAdvance)
    }
}
