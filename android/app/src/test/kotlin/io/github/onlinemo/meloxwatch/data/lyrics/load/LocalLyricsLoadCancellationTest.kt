package io.github.onlinemo.meloxwatch.data.lyrics.load

import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.lyrics.EmbeddedLyricsBinaryFixture
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class LocalLyricsLoadCancellationTest {
    @Test fun cancelledTextReadAndItsNonCooperativeCloseRetainBothOwners() = runBlocking { checkReadAndCloseOwnership(embedded = false) }

    @Test fun cancelledEmbeddedSuspendBridgeAndItsCloseRetainBothOwners() = runBlocking { checkReadAndCloseOwnership(embedded = true) }

    @Test fun cancelledCursorCloseCannotReleaseTheLoaderOrGlobalChannelEarly() = runBlocking {
        withTimeout(10_000) {
            val fixture = LocalLyricsLoadFixture()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            fixture.cursorCloseHook = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            val loader = fixture.loader()
            val request = fixture.request(LyricsLoadSelection.Automatic(siblingScope = LyricsSiblingScope("folder", "Track.mp3")))
            val caller = async(Dispatchers.Default) { loader.load(request) }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                caller.cancel()
                assertFalse(caller.isCompleted)
                assertEquals(LyricsLoadIssue.BUSY, failure(loader.load(request)).issue)
                assertPhysicalBusy(fixture.io)
                assertTrue(fixture.openedUris.isEmpty())
                release.countDown()
                caller.join()
                assertEquals(1, fixture.cursorCloses.get())
                assertTrue(fixture.openedUris.isEmpty())
                assertEquals("available", fixture.io.read { "available" })
            } finally {
                release.countDown()
                caller.cancel()
            }
        }
    }

    @Test fun cancelledQueuedValidationKeepsOwnershipUntilItsWorkerActuallyDrains() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val fixture = LocalLyricsLoadFixture(LocalProviderIo(dispatcher))
        val loader = fixture.loader()
        val request = fixture.request()
        val caller = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { loader.load(request) }
        try {
            assertFalse(caller.isCompleted)
            caller.cancel()
            assertEquals(LyricsLoadIssue.BUSY, failure(loader.load(request)).issue)
            assertFalse(caller.isCompleted)
            dispatcher.runNext()
            caller.join()
            assertEquals(0, fixture.validationCalls.get())
            assertTrue(fixture.openedUris.isEmpty())
            assertTrue(dispatcher.empty)
        } finally {
            caller.cancel()
            while (!dispatcher.empty) dispatcher.runNext()
        }
    }

    @Test fun externalProviderBusyReturnsImmediatelyWithoutAnAdmissionWaitQueue() = runBlocking {
        withTimeout(10_000) {
            val fixture = LocalLyricsLoadFixture()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val external = async(Dispatchers.Default) { fixture.io.read {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            } }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val result = withTimeout(2_000) { fixture.loader().load(fixture.request()) }
                assertEquals(LyricsLoadIssue.BUSY, failure(result).issue)
                assertEquals(SourceReadIssue.BUSY, failure(result).sourceIssue)
                assertFalse(external.isCompleted)
                assertTrue(fixture.openedUris.isEmpty())
            } finally {
                release.countDown()
                external.cancel()
                external.join()
            }
        }
    }

    private suspend fun checkReadAndCloseOwnership(embedded: Boolean) = kotlinx.coroutines.coroutineScope {
        withTimeout(10_000) {
            val fixture = LocalLyricsLoadFixture()
            val bytes = if (embedded) EmbeddedLyricsBinaryFixture.id3(EmbeddedLyricsBinaryFixture.frame("USLT", EmbeddedLyricsBinaryFixture.uslt("late")))
                else "[00:01]late".toByteArray()
            val input = if (embedded) null else fixture.document("[00:01]late", nativeToken = "native-text-v1")
            if (embedded) fixture.setAudio(bytes)
            val uri = input?.candidate?.uri ?: fixture.audio.location.uri
            val entered = CountDownLatch(1)
            val releaseRead = CountDownLatch(1)
            val closing = CountDownLatch(1)
            val releaseClose = CountDownLatch(1)
            val published = AtomicInteger()
            fixture.streams[uri] = { object : InputStream() {
                private val delegate = ByteArrayInputStream(bytes)
                override fun read(): Int = throw AssertionError("Expected bounded bulk read")
                override fun read(destination: ByteArray, offset: Int, length: Int): Int {
                    entered.countDown()
                    check(releaseRead.await(5, TimeUnit.SECONDS))
                    return delegate.read(destination, offset, length)
                }
                override fun close() {
                    closing.countDown()
                    check(releaseClose.await(5, TimeUnit.SECONDS))
                    delegate.close()
                }
            } }
            val loader = fixture.loader()
            val request = fixture.request(input?.let { LyricsLoadSelection.TextDocument(it) } ?: LyricsLoadSelection.Embedded())
            val caller = async(Dispatchers.Default) { loader.load(request).also { published.incrementAndGet() } }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                caller.cancel()
                assertFalse(caller.isCompleted)
                assertEquals(LyricsLoadIssue.BUSY, failure(loader.load(request)).issue)
                assertPhysicalBusy(fixture.io)
                releaseRead.countDown()
                assertTrue(closing.await(5, TimeUnit.SECONDS))
                assertFalse(caller.isCompleted)
                assertEquals(LyricsLoadIssue.BUSY, failure(loader.load(request)).issue)
                assertPhysicalBusy(fixture.io)
                releaseClose.countDown()
                caller.join()
                try {
                    caller.await()
                    throw AssertionError("Cancelled result must not escape")
                }
                catch (_: CancellationException) { }
                assertEquals(0, published.get())
                assertEquals(1, fixture.closedStreams.get())
                fixture.streams[uri] = { ByteArrayInputStream(bytes) }
                assertEquals("late", loaded(loader.load(request)).document.sourceLines.single().text)
            } finally {
                releaseRead.countDown()
                releaseClose.countDown()
                caller.cancel()
            }
        }
    }

    private suspend fun assertPhysicalBusy(io: LocalProviderIo) {
        try {
            io.read { "must not execute" }
            throw AssertionError("Expected occupied physical channel")
        }
        catch (error: SourceReadException) { assertEquals(SourceReadIssue.BUSY, error.issue) }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val work = ArrayDeque<Runnable>()
        val empty: Boolean get() = work.isEmpty()
        override fun dispatch(context: CoroutineContext, block: Runnable) { work.addLast(block) }
        fun runNext() {
            check(work.isNotEmpty())
            work.removeFirst().run()
        }
    }
}
