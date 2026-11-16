package io.github.onlinemo.meloxwatch.data

import java.io.IOException
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeReadChannelTest {
    @Test fun successReleasesPhysicalOwnershipBeforeAnInlineSequentialRead() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val channel = ProbeReadChannel(dispatcher)
        val reader = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            assertEquals("first", channel.read({}) { "first" })
            channel.read({}) { "second" }
        }
        dispatcher.runNext()
        assertFalse(reader.isCompleted)
        dispatcher.runNext()
        assertEquals("second", reader.await())
        assertTrue(dispatcher.empty)
    }

    @Test fun failureKeepsItsCauseAndReleasesBeforeAnInlineRetry() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val channel = ProbeReadChannel(dispatcher)
        val failure = IOException("controlled provider failure")
        val reader = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try {
                channel.read<String>({}) { throw failure }
                throw AssertionError("Expected failure")
            } catch (error: IOException) {
                assertEquals(failure.javaClass, error.javaClass)
                assertEquals(failure.message, error.message)
                assertTrue(error === failure || error.cause === failure)
            }
            channel.read({}) { "retry" }
        }
        dispatcher.runNext()
        assertFalse(reader.isCompleted)
        dispatcher.runNext()
        assertEquals("retry", reader.await())
    }

    @Test fun cancellationCannotReleaseAnIgnoringPhysicalWorkerOrQueueANewRequest() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val channel = ProbeReadChannel(dispatcher)
        var cancelled = false
        var returned = false
        val reader = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            channel.read({ cancelled = true }) {
                returned = true
                "late"
            }
        }
        reader.cancel()
        reader.join()
        assertTrue(cancelled)
        assertFalse(returned)
        try {
            channel.read({}) { "early" }
            throw AssertionError("Expected busy")
        } catch (_: ProbeReadBusyException) {
            assertFalse(dispatcher.empty)
        }
        dispatcher.runNext()
        assertTrue(returned)
        val next = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { channel.read({}) { "new" } }
        dispatcher.runNext()
        assertEquals("new", next.await())
        assertTrue(dispatcher.empty)
    }

    @Test fun dispatcherRejectionReleasesOwnershipAndPreservesTheOriginalError() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val channel = ProbeReadChannel(dispatcher)
        val failure = IllegalStateException("controlled dispatcher rejection")
        dispatcher.rejection = failure
        try {
            channel.read({}) { "rejected" }
            throw AssertionError("Expected rejection")
        } catch (error: IllegalStateException) {
            assertEquals(failure.javaClass, error.javaClass)
            assertEquals(failure.message, error.message)
            assertTrue(error === failure || error.cause === failure)
        }
        dispatcher.rejection = null
        val next = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { channel.read({}) { "retry" } }
        dispatcher.runNext()
        assertEquals("retry", next.await())
    }

    @Test fun nativeAllocationFailureIsReportedAndDoesNotLeaveTheChannelOccupied() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val channel = ProbeReadChannel(dispatcher)
        val failure = OutOfMemoryError("controlled native allocation")
        val reader = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try {
                channel.read<String>({}) { throw failure }
                throw AssertionError("Expected allocation failure")
            } catch (error: ProbeReadAllocationException) {
                assertSame(failure, error.cause)
            }
            channel.read({}) { "retry" }
        }
        dispatcher.runNext()
        assertFalse(reader.isCompleted)
        dispatcher.runNext()
        assertEquals("retry", reader.await())
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val work = ArrayDeque<Runnable>()
        var rejection: RuntimeException? = null
        val empty: Boolean get() = work.isEmpty()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            rejection?.let { throw it }
            work.addLast(block)
        }
        fun runNext() {
            check(work.isNotEmpty())
            work.removeFirst().run()
        }
    }
}
