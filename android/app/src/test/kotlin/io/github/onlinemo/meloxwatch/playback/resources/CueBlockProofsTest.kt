package io.github.onlinemo.meloxwatch.playback.resources

import io.github.onlinemo.meloxwatch.data.cue.CueReadLimits
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CueBlockProofsTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun finalClassicRiffBlockFitsItsDigestAndFixedHeaderInsteadOfAnEightMiBTruncation() {
        assertEquals(262_145, CueBlockProofIndex.blocks(CueReadLimits.RIFF_MAX_BYTES))
        assertEquals(8_388_640L + CueBlockProofIndex.HEADER_BYTES, CueBlockProofIndex.bytes(CueReadLimits.RIFF_MAX_BYTES))
        assertTrue(CueBlockProofIndex.bytes(CueReadLimits.RIFF_MAX_BYTES) > 8L * 1024L * 1024L)
    }
    @Test fun onlyAnExactCompleteIndexCanSealAndItsLastDigestRejectsChangedBytes() {
        val owner = CueBlockProofs.privateDirectory(File(temporary.root, "proof"))
        val index = owner.allocate(CueReadLimits.STREAM_BYTES.toLong() + 1L)
        try {
            index.initialize()
            index.append(ByteArray(32) { 1 })
            assertThrows(IllegalStateException::class.java) { index.seal() }
            index.append(ByteArray(32) { 2 })
            index.seal()
            assertTrue(index.matches(1, ByteArray(32) { 2 }))
            assertFalse(index.matches(1, ByteArray(32) { 3 }))
            assertThrows(IllegalArgumentException::class.java) { index.matches(2, ByteArray(32)) }
        } finally { index.close() }
        assertTrue(File(temporary.root, "proof").listFiles().orEmpty().isEmpty())
    }
    @Test fun sixActualStagingFilesBoundAdmissionAndRetirementFreesOnlyTheSameReservation() {
        val owner = CueBlockProofs.privateDirectory(File(temporary.root, "proof"))
        val indices = (0 until 6).map { owner.allocate(44L).also { it.initialize() } }
        try {
            assertEquals(QueuePlaybackIssue.BUSY, assertThrows(MediaTransportException::class.java) {
                owner.allocate(44L).initialize()
            }.issue)
            indices[0].close()
            val next = owner.allocate(44L)
            next.initialize();
            next.close()
        } finally { indices.forEach { it.close() } }
    }
    @Test fun deletionFailureKeepsTheExactIndexReservationUntilActualCleanupSucceeds() {
        val directory = File(temporary.root, "proof")
        val owner = CueBlockProofs.privateDirectory(directory)
        val failed = owner.allocate(44L)
        failed.initialize()
        val path = directory.listFiles().orEmpty().single()
        assertTrue(path.delete())
        assertTrue(path.mkdir())
        val child = File(path, "controlled-blocker").apply { writeText("derived-test-data") }
        val others = (0 until 5).map { owner.allocate(44L).also { it.initialize() } }
        try {
            assertThrows(IOException::class.java) { failed.close() }
            assertEquals(QueuePlaybackIssue.BUSY, assertThrows(MediaTransportException::class.java) {
                owner.allocate(44L).initialize()
            }.issue)
            assertTrue(child.delete());
            assertTrue(path.delete())
            failed.close()
            val next = owner.allocate(44L)
            next.initialize();
            next.close()
        } finally {
            child.delete();
            path.delete();
            failed.close();
            others.forEach {
                it.close()
            }
        }
    }
}
