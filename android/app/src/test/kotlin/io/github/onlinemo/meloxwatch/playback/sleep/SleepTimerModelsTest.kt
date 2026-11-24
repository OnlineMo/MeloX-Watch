package io.github.onlinemo.meloxwatch.playback.sleep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class SleepTimerModelsTest {
    private val generation = "6f7abf23-e4da-4ef1-adf2-59bdf0843bb7"
    private val token = SleepTimerToken(generation, "boot-1", 61_000L)

    private fun rejected(action: () -> Unit) {
        try {
            action()
            fail("Expected invalid timer input to be rejected")
        }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun timerDefaultsToDisabledWithoutAPauseCommand() {
        val transition = SleepTimerTransition(SleepTimerState())
        assertNull(transition.state.active)
        assertNull(transition.state.lastStop)
        assertFalse(transition.pauseRequired)
    }

    @Test fun supportedMinuteBoundariesHaveTheExactMonotonicDuration() {
        for (minutes in listOf(1, 180)) {
            val active = requireNotNull(SleepTimerReducer.arm(minutes, SleepTimerEndMode.PAUSE_IMMEDIATELY,
                SleepTimerClockSample("boot-1", 1_000L)).state.active)
            assertEquals(minutes * 60_000L, active.token.deadlineElapsedMs - active.startedElapsedMs)
        }
        for (minutes in listOf(-1, 0, 181, Int.MAX_VALUE)) rejected {
            SleepTimerReducer.arm(minutes, SleepTimerEndMode.PAUSE_IMMEDIATELY, SleepTimerClockSample("boot-1", 0L))
        }
    }

    @Test fun deadlineAdditionRejectsOverflowAtBothDurationBounds() {
        for (minutes in listOf(1, 180)) rejected {
            SleepTimerReducer.arm(minutes, SleepTimerEndMode.PAUSE_IMMEDIATELY, SleepTimerClockSample("boot-1", Long.MAX_VALUE))
        }
        val edge = requireNotNull(SleepTimerReducer.arm(1, SleepTimerEndMode.PAUSE_IMMEDIATELY,
            SleepTimerClockSample("boot-1", Long.MAX_VALUE - 60_000L)).state.active)
        assertEquals(Long.MAX_VALUE, edge.token.deadlineElapsedMs)
    }

    @Test fun persistedGenerationMustBeACanonicalUuid() {
        rejected { SleepTimerToken("1-1-1-1-1", "boot-1", 61_000L) }
        rejected { SleepTimerToken(generation.uppercase(), "boot-1", 61_000L) }
        assertEquals(generation, token.generation)
    }

    @Test fun persistedDeadlineCannotExceedTheChosenDuration() {
        rejected { ActiveSleepTimer(token.copy(deadlineElapsedMs = 61_001L), 1_000L, 1, SleepTimerEndMode.AFTER_CURRENT_TRACK) }
        rejected { ActiveSleepTimer(token, 62_000L, 1, SleepTimerEndMode.AFTER_CURRENT_TRACK) }
    }

    @Test fun immediateModeCannotCarryAWaitingTrack() {
        val track = SleepTimerTrackBinding("session", "queue", "entry", 0L)
        rejected { ActiveSleepTimer(token, 1_000L, 1, SleepTimerEndMode.PAUSE_IMMEDIATELY, track) }
    }

    @Test fun activeAndCompletedStateCannotBothBeAuthoritative() {
        val active = ActiveSleepTimer(token, 1_000L, 1, SleepTimerEndMode.AFTER_CURRENT_TRACK)
        rejected { SleepTimerState(active, SleepTimerStop(token, SleepTimerStopReason.USER_CANCELLED)) }
        rejected { SleepTimerTransition(SleepTimerState(active), blockNaturalAdvance = true) }
    }

    @Test fun invalidClockAndTraversalIdentitiesAreRejected() {
        rejected { SleepTimerClockSample("", 0L) }
        rejected { SleepTimerClockSample("boot-1", -1L) }
        rejected { SleepTimerTrackBinding("session", "queue", "entry", -1L) }
        rejected { SleepTimerTrackBinding("session", "queue", " ", 0L) }
    }
}
