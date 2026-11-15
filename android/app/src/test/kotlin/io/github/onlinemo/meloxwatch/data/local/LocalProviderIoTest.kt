package io.github.onlinemo.meloxwatch.data.local

import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class LocalProviderIoTest {
    @Test fun sequentialCallsObserveAReleasedPhysicalChannel() = runBlocking {
        val io = LocalProviderIo()
        repeat(100) { index -> assertEquals(index, io.read { index }) }
    }

    @Test fun callerCancellationDoesNotFreeAnIgnoringPhysicalWorker() = runBlocking {
        withTimeout(10_000) {
            val io = LocalProviderIo()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val caller = async(Dispatchers.Default) {
                io.read { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); "late" }
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                caller.cancel()
                caller.join()
                try { io.read { "second" }; throw AssertionError("Expected busy channel") }
                catch (error: SourceReadException) { assertEquals(SourceReadIssue.BUSY, error.issue) }
                release.countDown()
                io.awaitIdle()
                assertEquals("new", io.read { "new" })
            } finally { release.countDown(); caller.cancel() }
        }
    }

    @Test fun nativeAllocationFailureResumesCallerAndReleasesOwnership() = runBlocking {
        val io = LocalProviderIo()
        try { io.read<String> { throw OutOfMemoryError("controlled native allocation") }; throw AssertionError("Expected failure") }
        catch (error: SourceReadException) { assertEquals(SourceReadIssue.METADATA_UNREADABLE, error.issue) }
        assertEquals("available", io.read { "available" })
    }

    @Test fun idleCursorCloseAcknowledgementAllowsAnInlineSequentialReader() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val io = LocalProviderIo(dispatcher)
        val receipt = CompletableDeferred<Unit>()
        var closed = false
        var next: Deferred<String>? = null
        receipt.invokeOnCompletion {
            assertTrue(closed)
            next = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { io.read { "next" } }
        }
        io.retireCursor(receipt) { closed = true }
        assertFalse(receipt.isCompleted)
        dispatcher.runNext()
        assertTrue(receipt.isCompleted)
        assertFalse(checkNotNull(next).isCompleted)
        dispatcher.runNext()
        assertEquals("next", checkNotNull(next).await())
        assertTrue(dispatcher.empty)
    }

    @Test fun deferredCloseWaitsForTheActiveReadAndReleasesBeforeItsAcknowledgement() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val io = LocalProviderIo(dispatcher)
        val receipt = CompletableDeferred<Unit>()
        var returned = false
        var closes = 0
        var next: Deferred<String>? = null
        receipt.invokeOnCompletion {
            assertTrue(returned)
            assertEquals(1, closes)
            next = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { io.read { "next" } }
        }
        val active = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            io.read {
                io.retireCursor(receipt) { closes++ }
                assertFalse(receipt.isCompleted)
                returned = true
                "active"
            }
        }
        dispatcher.runNext()
        assertEquals("active", active.await())
        assertTrue(receipt.isCompleted)
        assertFalse(checkNotNull(next).isCompleted)
        dispatcher.runNext()
        assertEquals("next", checkNotNull(next).await())
        assertTrue(dispatcher.empty)
    }

    @Test fun failedCursorCloseKeepsTheCauseAndReleasesBeforeAnInlineRetry() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val io = LocalProviderIo(dispatcher)
        val receipt = CompletableDeferred<Unit>()
        val failure = IOException("controlled cursor close")
        var next: Deferred<String>? = null
        receipt.invokeOnCompletion { cause ->
            assertSame(failure, cause)
            next = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { io.read { "retry" } }
        }
        io.retireCursor(receipt) { throw failure }
        dispatcher.runNext()
        try {
            receipt.await()
            throw AssertionError("Expected cursor close failure")
        } catch (error: IOException) {
            // await 可以恢复协程栈并复制异常；物理关闭的原始原因必须保留。
            assertEquals(failure.javaClass, error.javaClass)
            assertEquals(failure.message, error.message)
            assertTrue(error === failure || error.cause === failure)
        }
        assertFalse(checkNotNull(next).isCompleted)
        dispatcher.runNext()
        assertEquals("retry", checkNotNull(next).await())
        assertTrue(dispatcher.empty)
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
