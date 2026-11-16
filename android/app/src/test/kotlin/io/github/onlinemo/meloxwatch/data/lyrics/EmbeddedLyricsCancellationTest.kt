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

class EmbeddedLyricsCancellationTest {
    private val binary = EmbeddedLyricsBinaryFixture

    @Test fun cancellationBeforeDispatcherWorkDoesNotReadTheAuthorizedStream() = runBlocking {
        val dispatcher = LyricsQueuedDispatcher()
        val reader = EmbeddedLyricsReader(EmbeddedLyricsLimits(), dispatcher, dispatcher)
        val stream = EmbeddedCountingStream(binary.resource("id3-v23-uslt-latin1.bin"))
        val task = async(start = CoroutineStart.UNDISPATCHED) { reader.read(stream) }
        task.cancel()
        dispatcher.drain()
        expectLyricsCancellation { task.await() }
        assertEquals(0, stream.readCalls)
        assertFalse(stream.closed)
    }

    @Test fun cancellationAfterRealBoundedIoDoesNotPublishAPartialAcquisition() = runBlocking {
        val dispatcher = LyricsQueuedDispatcher()
        val reader = EmbeddedLyricsReader(EmbeddedLyricsLimits(ioChunkBytes = 32), dispatcher, dispatcher)
        val bytes = binary.id3(binary.frame("APIC", ByteArray(20_000)), binary.frame("USLT", binary.uslt("later")))
        val stream = EmbeddedCountingStream(bytes)
        var published: EmbeddedLyricsReadResult? = null
        val task = async(start = CoroutineStart.UNDISPATCHED) { reader.read(stream).also { published = it } }
        assertTrue(dispatcher.runNext())
        assertTrue(stream.bytesRead > 20)
        assertTrue(stream.bytesRead < bytes.size)
        assertTrue(dispatcher.pendingTaskCount > 0)
        task.cancel()
        dispatcher.drain()
        expectLyricsCancellation { task.await() }
        assertNull(published)
        assertFalse(stream.closed)
    }

    @Test fun cancellationAfterRealSyltDraftWorkCannotPublishFirstRows() = runBlocking {
        val dispatcher = LyricsQueuedDispatcher()
        val reader = EmbeddedLyricsReader(EmbeddedLyricsLimits(), dispatcher, dispatcher)
        val events = (0 until 400).map { index -> "\nword$index" to index.toLong() * 1_000L }
        val stream = EmbeddedCountingStream(binary.id3(binary.frame("SYLT", binary.sylt(events), 4), version = 4))
        var published: EmbeddedLyricsReadResult? = null
        val task = async(start = CoroutineStart.UNDISPATCHED) { reader.read(stream).also { published = it } }
        assertTrue(dispatcher.runNext())
        assertTrue(dispatcher.pendingTaskCount > 0)
        assertTrue(stream.bytesRead > 20)
        assertFalse(task.isCompleted)
        task.cancel()
        dispatcher.drain()
        expectLyricsCancellation { task.await() }
        assertNull(published)
    }

    @Test fun cancellationInsideRealVorbisTextParsingCannotReturnTheEarlierCandidate() = runBlocking {
        val dispatcher = LyricsQueuedDispatcher()
        val reader = EmbeddedLyricsReader(EmbeddedLyricsLimits(), dispatcher, dispatcher)
        val fields = listOf(binary.lyrics("first"), binary.lyrics(numberedLyrics(400, "second")))
        val stream = EmbeddedCountingStream(binary.flacComments(fields))
        var published: EmbeddedLyricsReadResult? = null
        val task = async(start = CoroutineStart.UNDISPATCHED) { reader.read(stream).also { published = it } }
        assertTrue(dispatcher.runNext())
        assertTrue(dispatcher.pendingTaskCount > 0)
        assertFalse(task.isCompleted)
        task.cancel()
        dispatcher.drain()
        expectLyricsCancellation { task.await() }
        assertNull(published)
        assertFalse(stream.closed)
    }

    @Test fun streamCancellationExceptionIsNotConvertedToAnIoDiagnostic() = runBlocking {
        val stream = object : InputStream() {
            override fun read(): Int = throw CancellationException("controlled fixture cancellation")
            override fun read(destination: ByteArray, offset: Int, length: Int): Int = read()
        }
        expectLyricsCancellation { EmbeddedLyricsReader().read(stream) }
    }

    @Test fun concurrentCancellationWinsOverAnIoExceptionAndPreservesCancellation() = runBlocking {
        val task = async(start = CoroutineStart.UNDISPATCHED) {
            val owner = currentCoroutineContext().job
            val stream = object : InputStream() {
                override fun read(): Int {
                    owner.cancel(CancellationException("controlled fixture cancellation"))
                    throw IOException("controlled private fixture detail")
                }
                override fun read(destination: ByteArray, offset: Int, length: Int): Int = read()
            }
            EmbeddedLyricsReader(EmbeddedLyricsLimits(), Dispatchers.Unconfined, Dispatchers.Unconfined).read(stream)
        }
        expectLyricsCancellation { task.await() }
        assertTrue(task.isCancelled)
    }

    @Test fun ioAndPermissionMessagesDoNotEscapeIntoReturnedDiagnostics() = runBlocking {
        val detail = "controlled-private-fixture-detail"
        val io = object : InputStream() {
            override fun read(): Int = throw IOException(detail)
            override fun read(destination: ByteArray, offset: Int, length: Int): Int = read()
        }
        val denied = object : InputStream() {
            override fun read(): Int = throw SecurityException(detail)
            override fun read(destination: ByteArray, offset: Int, length: Int): Int = read()
        }
        val ioResult = EmbeddedLyricsReader().read(io)
        val deniedResult = EmbeddedLyricsReader().read(denied)
        assertEmbeddedFatal(ioResult, EmbeddedLyricsIssue.IO_FAILURE)
        assertEmbeddedFatal(deniedResult, EmbeddedLyricsIssue.READ_PERMISSION_DENIED)
        assertFalse(ioResult.diagnostics.toString().contains(detail))
        assertFalse(deniedResult.diagnostics.toString().contains(detail))
    }
}
