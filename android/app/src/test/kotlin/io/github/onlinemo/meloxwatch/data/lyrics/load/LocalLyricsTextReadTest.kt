package io.github.onlinemo.meloxwatch.data.lyrics.load

import android.os.CancellationSignal
import io.github.onlinemo.meloxwatch.data.lyrics.EmbeddedCountingStream
import io.github.onlinemo.meloxwatch.data.lyrics.EmbeddedLyricsBinaryFixture
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsLimits
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class LocalLyricsTextReadTest {
    @Test fun shortReadsRemainCompleteAndEveryRequestIsBounded() = runBlocking {
        val bytes = "[00:01]complete".toByteArray()
        val stream = EmbeddedCountingStream(bytes, shortRead = 1)
        val result = LocalLyricsTextRead(LocalLyricsLoadLimits(ioChunkBytes = 3)).read(stream, bytes.size.toLong(), CancellationSignal(), currentCoroutineContext())
        assertArrayEquals(bytes, result.bytes)
        assertEquals(EmbeddedLyricsBinaryFixture.sha256(bytes), result.sha256)
        assertTrue(stream.largestRequest <= 3)
        assertEquals(bytes.size + 1, stream.readCalls)
    }

    @Test fun exactInputCeilingNeedsAndCompletesTheSingleByteEofProbe() = runBlocking {
        val bytes = "12345678".toByteArray()
        val stream = EmbeddedCountingStream(bytes)
        val limits = LocalLyricsLoadLimits(lyrics = LyricsLimits(maxInputBytes = 8), ioChunkBytes = 8)
        assertArrayEquals(bytes, LocalLyricsTextRead(limits).read(stream, null, CancellationSignal(), currentCoroutineContext()).bytes)
        assertEquals(8, stream.bytesRead)
        assertEquals(2, stream.readCalls)
    }

    @Test fun unknownLengthOverflowStopsAfterOnlyOneExcessByte() = runBlocking {
        val stream = EmbeddedCountingStream("123456789more".toByteArray())
        val limits = LocalLyricsLoadLimits(lyrics = LyricsLimits(maxInputBytes = 8), ioChunkBytes = 8)
        expectIssue(LyricsLoadIssue.INPUT_TOO_LARGE) { LocalLyricsTextRead(limits).read(stream, null, CancellationSignal(), currentCoroutineContext()) }
        assertEquals(9, stream.bytesRead)
        assertEquals(2, stream.readCalls)
    }

    @Test fun hugeDeclaredSizeIsRejectedBeforeReadingOrAllocatingItsClaim() = runBlocking {
        val stream = EmbeddedCountingStream(byteArrayOf(1))
        expectIssue(LyricsLoadIssue.INPUT_TOO_LARGE) {
            LocalLyricsTextRead(LocalLyricsLoadLimits()).read(stream, Long.MAX_VALUE, CancellationSignal(), currentCoroutineContext())
        }
        assertEquals(0, stream.readCalls)
    }

    @Test fun actualCompleteSizeMustMatchItsKnownCandidateSize() = runBlocking {
        expectIssue(LyricsLoadIssue.SOURCE_CHANGED) {
            LocalLyricsTextRead(LocalLyricsLoadLimits()).read(ByteArrayInputStream(byteArrayOf(1, 2)), 3L, CancellationSignal(), currentCoroutineContext())
        }
    }

    @Test fun zeroProgressIsAnErrorWithoutAReadLoop() = runBlocking {
        val stream = InvalidCountStream(0)
        expectIssue(LyricsLoadIssue.INVALID_READ) { LocalLyricsTextRead(LocalLyricsLoadLimits()).read(stream, null, CancellationSignal(), currentCoroutineContext()) }
        assertEquals(1, stream.readCalls)
    }

    @Test fun illegalNegativeOrExcessProviderCountsAreRejected() = runBlocking {
        for (count in listOf(-2, 9)) {
            val stream = InvalidCountStream(count)
            expectIssue(LyricsLoadIssue.INVALID_READ) {
                LocalLyricsTextRead(LocalLyricsLoadLimits(ioChunkBytes = 8)).read(stream, null, CancellationSignal(), currentCoroutineContext())
            }
            assertEquals(1, stream.readCalls)
        }
    }

    @Test fun operationLimitIncludesTheRequiredEofReadAndDoesNotPretendCompleteness() = runBlocking {
        val stream = EmbeddedCountingStream(byteArrayOf(1, 2), shortRead = 1)
        val limits = LocalLyricsLoadLimits(lyrics = LyricsLimits(maxInputBytes = 2), ioChunkBytes = 1, maxReadOperations = 2)
        expectIssue(LyricsLoadIssue.READ_LIMIT) { LocalLyricsTextRead(limits).read(stream, null, CancellationSignal(), currentCoroutineContext()) }
        assertEquals(2, stream.readCalls)
    }

    @Test fun cancellationAfterPhysicalReadStopsBeforeAnotherChunkOrResult() = runBlocking {
        val job = Job()
        val stream = object : ByteArrayInputStream(byteArrayOf(1, 2)) {
            var reads = 0
            override fun read(destination: ByteArray, offset: Int, length: Int): Int {
                reads++
                val count = super.read(destination, offset, length)
                job.cancel()
                return count
            }
        }
        try {
            LocalLyricsTextRead(LocalLyricsLoadLimits()).read(stream, null, CancellationSignal(), job)
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(1, stream.reads)
    }

    @Test fun representationLimitIsCheckedBeforeAllocatingTheReadScratch() = runBlocking {
        val stream = EmbeddedCountingStream(byteArrayOf(1))
        val limits = LocalLyricsLoadLimits(lyrics = LyricsLimits(maxAccountedBytes = 1_024L))
        expectIssue(LyricsLoadIssue.MEMORY_LIMIT) { LocalLyricsTextRead(limits).read(stream, null, CancellationSignal(), currentCoroutineContext()) }
        assertEquals(0, stream.readCalls)
    }

    @Test fun streamingWeakProofHasTheSameCompleteShaAndBoundsAsAcquisition() = runBlocking {
        val bytes = "proof".toByteArray()
        val stream = EmbeddedCountingStream(bytes, shortRead = 1)
        val fingerprint = LocalLyricsTextRead(LocalLyricsLoadLimits(ioChunkBytes = 2)).fingerprint(stream, bytes.size.toLong(), CancellationSignal(), currentCoroutineContext())
        assertEquals(EmbeddedLyricsBinaryFixture.sha256(bytes), fingerprint.sha256)
        assertEquals(bytes.size, fingerprint.sizeBytes)
        assertTrue(stream.largestRequest <= 2)
        assertEquals(bytes.size + 1, stream.readCalls)
    }

    private suspend fun expectIssue(expected: LyricsLoadIssue, action: suspend () -> Unit) {
        try {
            action()
            throw AssertionError("Expected $expected")
        }
        catch (error: LyricsLoadException) { assertEquals(expected, error.failure.issue) }
    }

    private class InvalidCountStream(private val count: Int) : InputStream() {
        var readCalls = 0
        override fun read(): Int = throw AssertionError("Bulk reads must remain bounded")
        override fun read(destination: ByteArray, offset: Int, length: Int): Int {
            readCalls++
            return count
        }
    }
}
