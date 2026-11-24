package io.github.onlinemo.meloxwatch.data.lyrics

import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedOggCancellationTest {
    private val binary = EmbeddedLyricsBinaryFixture
    private val ogg = OggLyricsBinaryFixture

    @Test fun cancellationBeforeDispatchDoesNotReadOrCloseTheAuthorizedOggStream() = runBlocking {
        val dispatcher = LyricsQueuedDispatcher()
        val reader = EmbeddedLyricsReader(EmbeddedLyricsLimits(), dispatcher, dispatcher)
        val stream = EmbeddedCountingStream(ogg.opus(listOf(binary.lyrics("text"))))
        val task = async(start = CoroutineStart.UNDISPATCHED) { reader.read(stream) }
        task.cancel()
        dispatcher.drain()
        expectLyricsCancellation { task.await() }
        assertEquals(0, stream.readCalls)
        assertFalse(stream.closed)
    }

    @Test fun cancellationAfterActualPageIoCannotPublishOrCloseAPartialResult() = runBlocking {
        val dispatcher = LyricsQueuedDispatcher()
        val reader = EmbeddedLyricsReader(EmbeddedLyricsLimits(ioChunkBytes = 16), dispatcher, dispatcher)
        val bytes = ogg.opus(listOf(binary.lyrics("text")), tail = ByteArray(12_000))
        val stream = EmbeddedCountingStream(bytes)
        var published: EmbeddedLyricsReadResult? = null
        val task = async(start = CoroutineStart.UNDISPATCHED) { reader.read(stream).also { published = it } }
        assertTrue(dispatcher.runNext())
        assertTrue(stream.bytesRead > ogg.identification(ogg.opusId()).size)
        assertTrue(stream.bytesRead < bytes.size)
        assertTrue(dispatcher.pendingTaskCount > 0)
        task.cancel()
        dispatcher.drain()
        expectLyricsCancellation { task.await() }
        assertNull(published)
        assertFalse(stream.closed)
    }

    @Test fun cancellationDuringRealCrcWorkCannotExposeTheBufferedPageLyrics() = runBlocking {
        val dispatcher = LyricsQueuedDispatcher()
        val reader = EmbeddedLyricsReader(EmbeddedLyricsLimits(), dispatcher, dispatcher)
        val bytes = ogg.opus(listOf(binary.lyrics("text")), tail = ByteArray(8_000))
        val stream = EmbeddedCountingStream(bytes)
        var published: EmbeddedLyricsReadResult? = null
        val task = async(start = CoroutineStart.UNDISPATCHED) { reader.read(stream).also { published = it } }
        assertTrue(dispatcher.runNext())
        assertEquals(bytes.size, stream.bytesRead)
        assertTrue(dispatcher.pendingTaskCount > 0)
        assertFalse(task.isCompleted)
        task.cancel()
        dispatcher.drain()
        expectLyricsCancellation { task.await() }
        assertNull(published)
        assertFalse(stream.closed)
    }

    @Test fun cancellationAtALaterPayloadPageCannotPublishAnEarlierCandidate() = runBlocking {
        val bytes = segmentedTwoFields()
        var published: EmbeddedLyricsReadResult? = null
        val task = async(start = CoroutineStart.UNDISPATCHED) {
            val owner = currentCoroutineContext().job
            val stream = OggBoundaryStream(bytes, firstCommentPageEnd()) {
                owner.cancel(CancellationException("controlled private cancellation"))
                0
            }
            try {
                unconfinedReader().read(stream).also { published = it }
            } finally {
                assertEquals(firstCommentPageEnd(), stream.bytesRead)
                assertFalse(stream.closed)
            }
        }
        expectLyricsCancellation { task.await() }
        assertNull(published)
    }

    @Test fun cancellationDuringOpusPaddingCannotPublishTheAlreadyParsedLyrics() = runBlocking {
        val bytes = ogg.opus(listOf(binary.lyrics("first")), segmentsPerPage = 1, tail = ByteArray(600))
        var published: EmbeddedLyricsReadResult? = null
        val task = async(start = CoroutineStart.UNDISPATCHED) {
            val owner = currentCoroutineContext().job
            val stream = OggBoundaryStream(bytes, firstCommentPageEnd()) {
                owner.cancel()
                0
            }
            unconfinedReader().read(stream).also { published = it }
        }
        expectLyricsCancellation { task.await() }
        assertNull(published)
    }

    @Test fun cancellationInsideRealLrcWorkCannotPublishAnEarlierEmptySource() = runBlocking {
        val parser = LyricsQueuedDispatcher()
        val reader = EmbeddedLyricsReader(EmbeddedLyricsLimits(), Dispatchers.Unconfined, parser)
        val bytes = ogg.opus(listOf(binary.lyrics(""), binary.lyrics(numberedLyrics(200, "next"))))
        val stream = EmbeddedCountingStream(bytes)
        var published: EmbeddedLyricsReadResult? = null
        val task = async(start = CoroutineStart.UNDISPATCHED) { reader.read(stream).also { published = it } }
        // Empty text completes its real first parser call; the next call reaches its line batch.
        assertTrue(parser.runNext())
        assertTrue(parser.runNext())
        assertEquals(bytes.size, stream.bytesRead)
        assertTrue(parser.pendingTaskCount > 0)
        assertFalse(task.isCompleted)
        task.cancel()
        parser.drain()
        expectLyricsCancellation { task.await() }
        assertNull(published)
        assertFalse(stream.closed)
    }

    @Test fun aLaterStreamCancellationExceptionPropagatesThroughTheAcquisition() = runBlocking {
        val stream = OggBoundaryStream(segmentedTwoFields(), firstCommentPageEnd()) {
            throw CancellationException("controlled private cancellation")
        }
        expectLyricsCancellation { EmbeddedLyricsReader().read(stream) }
        assertEquals(firstCommentPageEnd(), stream.bytesRead)
        assertFalse(stream.closed)
    }

    @Test fun concurrentCancellationWinsOverAnIoFailureDuringALaterPageRead() = runBlocking {
        val task = async(start = CoroutineStart.UNDISPATCHED) {
            val owner = currentCoroutineContext().job
            val stream = OggBoundaryStream(segmentedTwoFields(), firstCommentPageEnd()) {
                owner.cancel(CancellationException("controlled private cancellation"))
                throw IOException("controlled private path and text")
            }
            unconfinedReader().read(stream)
        }
        expectLyricsCancellation { task.await() }
        assertTrue(task.isCancelled)
    }

    @Test fun laterIoAndPermissionFailuresClearCandidatesAndDoNotLeakTheirMessages() = runBlocking {
        val detail = "controlled-private-path-and-lyrics"
        for ((error, issue) in listOf(
            IOException(detail) to EmbeddedLyricsIssue.IO_FAILURE,
            SecurityException(detail) to EmbeddedLyricsIssue.READ_PERMISSION_DENIED,
        )) {
            val stream = OggBoundaryStream(segmentedTwoFields(), firstCommentPageEnd()) { throw error }
            val result = EmbeddedLyricsReader().read(stream)
            assertEmbeddedFatal(result, issue)
            assertTrue(result.cumulativePayloadBytes >= 5)
            assertFalse(result.diagnostics.toString().contains(detail))
            assertFalse(stream.closed)
        }
    }

    @Test fun illegalProviderReadCountsCannotCorruptTheCursorOrReturnEarlierLyrics() = runBlocking {
        for (count in listOf<(Int) -> Int>({ -2 }, { requested -> requested + 1 })) {
            val stream = OggBoundaryStream(segmentedTwoFields(), firstCommentPageEnd(), count)
            val result = EmbeddedLyricsReader().read(stream)
            assertEmbeddedFatal(result, EmbeddedLyricsIssue.IO_FAILURE)
            assertEquals(firstCommentPageEnd(), stream.bytesRead)
            assertFalse(stream.closed)
        }
    }

    @Test fun aProviderReturningOnlyZeroAfterARealPageHitsTheFiniteReadBudget() = runBlocking {
        var zeroReads = 0
        val stream = OggBoundaryStream(segmentedTwoFields(), firstCommentPageEnd()) {
            zeroReads++
            0
        }
        val result = EmbeddedLyricsReader(EmbeddedLyricsLimits(maxReadOperations = 32)).read(stream)
        assertEmbeddedFatal(result, EmbeddedLyricsIssue.READ_LIMIT)
        assertTrue(zeroReads in 1..32)
        assertEquals(firstCommentPageEnd(), stream.bytesRead)
        assertFalse(stream.closed)
    }

    @Test fun intermittentZeroAndShortReadsStillHaveAnExactBoundedNormalPath() = runBlocking {
        val delegate = EmbeddedCountingStream(ogg.opus(listOf(binary.lyrics("text"))), shortRead = 2)
        var calls = 0
        var zeroReads = 0
        val stream = object : InputStream() {
            override fun read(): Int = delegate.read()
            override fun read(destination: ByteArray, offset: Int, length: Int): Int {
                calls++
                if (calls % 3 == 0) {
                    zeroReads++
                    return 0
                }
                return delegate.read(destination, offset, length)
            }
            override fun close() = delegate.close()
        }
        val result = EmbeddedLyricsReader(EmbeddedLyricsLimits(ioChunkBytes = 8, maxReadOperations = 256)).read(stream)
        assertTrue(result.metadataComplete)
        assertEquals("text", result.defaultCandidate!!.document.sourceLines.single().text)
        assertTrue(zeroReads > 0)
        assertTrue(delegate.largestRequest <= 8)
        assertFalse(delegate.closed)
    }

    private fun segmentedTwoFields(): ByteArray =
        ogg.opus(listOf(binary.lyrics("first"), binary.lyrics("x".repeat(600))), segmentsPerPage = 1)

    private fun firstCommentPageEnd(): Int = ogg.identification(ogg.opusId()).size + 27 + 1 + 255

    private fun unconfinedReader(): EmbeddedLyricsReader =
        EmbeddedLyricsReader(EmbeddedLyricsLimits(), Dispatchers.Unconfined, Dispatchers.Unconfined)
}

private class OggBoundaryStream(
    bytes: ByteArray,
    private val boundary: Int,
    private val onBoundary: (Int) -> Int,
) : InputStream() {
    private val delegate = EmbeddedCountingStream(bytes)
    val bytesRead: Int get() = delegate.bytesRead
    val closed: Boolean get() = delegate.closed

    override fun read(): Int = if (bytesRead >= boundary) onBoundary(1) else delegate.read()
    override fun read(destination: ByteArray, offset: Int, length: Int): Int =
        if (bytesRead >= boundary) onBoundary(length) else delegate.read(destination, offset, minOf(length, boundary - bytesRead))
    override fun close() = delegate.close()
}
