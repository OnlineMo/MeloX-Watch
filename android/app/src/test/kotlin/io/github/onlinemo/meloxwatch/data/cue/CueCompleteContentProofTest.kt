package io.github.onlinemo.meloxwatch.data.cue

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CueCompleteContentProofTest {
    @Test fun completeDigestRequiresRealEofAndRejectsADeclaredPrefixOrShortRead() {
        val bytes = ByteArray(50_000) { (it % 127).toByte() }
        assertEquals(CueByteFixture.sha(bytes), CueCompleteContentProof.hash(ByteArrayInputStream(bytes), bytes.size.toLong()) {})
        for (extent in listOf(bytes.size - 1L, bytes.size + 1L)) {
            assertEquals(CueIssue.SOURCE_CHANGED, assertThrows(CueException::class.java) {
                CueCompleteContentProof.hash(ByteArrayInputStream(bytes), extent) {}
            }.issue)
        }
    }
    @Test fun completionUsesTheMonotonicDeadlineEvenWhenTheSchedulerHasNotRun() {
        var elapsed = 0L
        val deadline = CueReadDeadline(1L) { elapsed }
        var retirementRequested = false
        val watchdog = CueReadWatchdog(deadline) { retirementRequested = true }
        try {
            elapsed = deadline.timeoutMs
            assertFalse(watchdog.complete())
            assertFalse(retirementRequested)
        } finally { watchdog.close() }
    }
    @Test fun completedProofCancelsItsOwnTicketAndCannotBecomeAnExpiredSuccessorPhase() {
        var elapsed = 0L
        val first = CueReadDeadline(1L) { elapsed }
        var retired = false
        val watchdog = CueReadWatchdog(first) { retired = true }
        try {
            assertTrue(watchdog.complete())
            elapsed = first.timeoutMs + 1L
            assertTrue(watchdog.complete())
            assertFalse(retired)
        } finally { watchdog.close() }
    }
    @Test fun canceledStreamingProofNeverReturnsAPrefixDigest() {
        var checkpoints = 0
        val bytes = ByteArray(100_000)
        assertThrows(InterruptedException::class.java) {
            CueCompleteContentProof.hash(ByteArrayInputStream(bytes), bytes.size.toLong()) {
                if (++checkpoints == 3) throw InterruptedException("Controlled proof cancellation")
            }
        }
        assertEquals(3, checkpoints)
    }
}
