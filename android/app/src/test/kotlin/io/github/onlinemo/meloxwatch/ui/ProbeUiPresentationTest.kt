package io.github.onlinemo.meloxwatch.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeUiPresentationTest {
    @Test fun replacementRetiresThePreviousActivityBeforeDeliveringItsSuccessorsState() {
        val owner = ProbeUiPresentation()
        val events = mutableListOf<String>()
        val first = owner.bind(ProbeUiState(page = ProbePage.PLAYBACK),
            { events += "first:${it.page}" }, { events += "retire:first" })
        val second = owner.bind(ProbeUiState(page = ProbePage.QUEUE),
            { events += "second:${it.page}" }, { events += "retire:second" })
        assertEquals(listOf("first:PLAYBACK", "retire:first", "second:QUEUE"), events)
        assertFalse(first.isCurrent)
        assertTrue(second.isCurrent)
        first.close()
        owner.update(ProbeUiState(page = ProbePage.HOME))
        assertEquals("second:HOME", events.last())
        assertTrue(second.isCurrent)
        second.close()
    }

    @Test fun leavingAndReturningAreBothDeliveredEvenWhenCompositionHasNotObservedEitherChange() {
        val owner = ProbeUiPresentation()
        val pages = mutableListOf<ProbePage>()
        val binding = owner.bind(ProbeUiState(page = ProbePage.PLAYBACK), { pages += it.page }, {})
        owner.update(ProbeUiState(page = ProbePage.HOME))
        owner.update(ProbeUiState(page = ProbePage.PLAYBACK))
        assertEquals(listOf(ProbePage.PLAYBACK, ProbePage.HOME, ProbePage.PLAYBACK), pages)
        binding.close()
    }

    @Test fun mediaInvalidationIsDeliveredBeforeTheProducerReturns() {
        val owner = ProbeUiPresentation()
        var observed: ProbePlaybackState? = null
        val initial = ProbeUiState(playback = ProbePlaybackState(isConnected = true, hasMedia = true, mediaId = "one"))
        val binding = owner.bind(initial, { observed = it.playback }, {})
        val disconnected = initial.copy(playback = initial.playback.copy(isConnected = false))
        owner.update(disconnected)
        assertEquals(disconnected.playback, observed)
        val failed = initial.copy(playback = initial.playback.copy(hasError = true))
        owner.update(failed)
        assertEquals(failed.playback, observed)
        binding.close()
    }

    @Test fun closingTheOwnerRetiresOnceAndRejectsFurtherUpdatesUntilANewActivityBinds() {
        val owner = ProbeUiPresentation()
        var updates = 0
        var retirements = 0
        val binding = owner.bind(ProbeUiState(), { updates++ }, { retirements++ })
        owner.close()
        owner.close()
        binding.close()
        owner.update(ProbeUiState(page = ProbePage.QUEUE))
        assertEquals(1, updates)
        assertEquals(1, retirements)
        assertFalse(binding.isCurrent)
        val successor = owner.bind(ProbeUiState(page = ProbePage.QUEUE), { updates++ }, { retirements++ })
        assertEquals(2, updates)
        assertTrue(successor.isCurrent)
        successor.close()
        assertEquals(2, retirements)
    }
}
