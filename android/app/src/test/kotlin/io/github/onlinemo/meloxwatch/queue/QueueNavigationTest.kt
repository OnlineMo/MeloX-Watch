package io.github.onlinemo.meloxwatch.queue

import io.github.onlinemo.meloxwatch.model.QueueMode
import io.github.onlinemo.meloxwatch.model.QueueRepeatMode
import io.github.onlinemo.meloxwatch.playback.manualQueueTarget
import io.github.onlinemo.meloxwatch.playback.naturalQueueAdvance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueNavigationTest {
    @Test
    fun manualNextUsesTheFullOrderBeyondThePlayerWindowAnd10000Boundary() {
        assertEquals(10000L, manualQueueTarget(9999L, 10001L, QueueRepeatMode.OFF, 1))
        assertEquals(9998L, manualQueueTarget(9999L, 10001L, QueueRepeatMode.SINGLE, -1))
        assertNull(manualQueueTarget(10000L, 10001L, QueueRepeatMode.SINGLE, 1))
    }

    @Test
    fun onlyListRepeatWrapsManualCommandsAndPreviousNeverHasAReplayThreshold() {
        assertEquals(10000L, manualQueueTarget(0L, 10001L, QueueRepeatMode.LIST, -1))
        assertEquals(0L, manualQueueTarget(10000L, 10001L, QueueRepeatMode.LIST, 1))
        assertEquals(4L, manualQueueTarget(5L, 10L, QueueRepeatMode.OFF, -1))
        assertNull(manualQueueTarget(0L, 1L, QueueRepeatMode.OFF, -1))
        assertNull(manualQueueTarget(0L, 0L, QueueRepeatMode.LIST, 1))
    }

    @Test
    fun finiteSingleRepeatCountsTheCurrentCompletionAndThenMovesForward() {
        val mode = QueueMode(true, QueueRepeatMode.SINGLE, 2)
        val first = naturalQueueAdvance(9999L, 10001L, mode, 0)
        assertEquals(9999L, first.targetOrder)
        assertEquals(1, first.completedCycles)
        val second = naturalQueueAdvance(9999L, 10001L, mode, first.completedCycles)
        assertEquals(10000L, second.targetOrder)
        assertEquals(0, second.completedCycles)
    }

    @Test
    fun finiteSingleItemStopsAtTheLimitWithoutStartingAnotherCycle() {
        val result = naturalQueueAdvance(0L, 1L, QueueMode(false, QueueRepeatMode.SINGLE, 2), 1)
        assertNull(result.targetOrder)
        assertEquals(2, result.completedCycles)
    }

    @Test
    fun naturalOffStopsListWrapsAndUnlimitedSingleKeepsItsInstance() {
        assertNull(naturalQueueAdvance(2L, 3L, QueueMode(), 0).targetOrder)
        assertEquals(0L, naturalQueueAdvance(2L, 3L, QueueMode(repeat = QueueRepeatMode.LIST), 0).targetOrder)
        assertEquals(2L, naturalQueueAdvance(2L, 3L, QueueMode(repeat = QueueRepeatMode.SINGLE), Int.MAX_VALUE).targetOrder)
    }
}
