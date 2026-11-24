package io.github.onlinemo.meloxwatch.data.backup

import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
class BackupPhysicalAdmissionTest {
    private class HeldWorker : CoroutineDispatcher() {
        val work = ArrayDeque<Runnable>()
        var rejectOnce = false
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (rejectOnce) { rejectOnce = false; throw RejectedExecutionException("Synthetic dispatch rejected") }
            work.addLast(block)
        }
        fun runOne() { check(work.isNotEmpty()); work.removeFirst().run() }
    }

    @Test(timeout = 30_000L)
    fun cancellationBetweenSynchronousAdmissionAndWorkerEntryCannotReleaseTheBackupOwnerEarly() = runBlocking {
        val worker = HeldWorker()
        val io = LocalProviderIo(worker)
        val barrier = BackupMutationBarrier()
        barrier.initializeStorage {}
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val admitted = AtomicBoolean(false)
        val executed = AtomicBoolean(false)
        val owner = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            barrier.exclusively {
                try { io.read(admitted = { admitted.set(true) }) { executed.set(true); Unit } }
                finally { if (admitted.get()) withContext(NonCancellable) { io.awaitIdle() } }
            }
        }
        assertTrue(admitted.get()); assertFalse(executed.get())
        owner.cancel(CancellationException("Synthetic caller cancelled before worker entry"))
        assertFalse(owner.isCompleted)
        assertEquals(BackupAccessState.EXCLUSIVE_OPERATION, barrier.state.value)
        try {
            io.read { Unit }
            throw AssertionError("An accepted worker must still own the channel")
        } catch (failure: SourceReadException) { assertEquals(SourceReadIssue.BUSY, failure.issue) }
        worker.runOne()
        owner.join()
        assertFalse(executed.get()); assertTrue(owner.isCancelled)
        assertEquals(BackupAccessState.READY, barrier.state.value)
        val retry = scope.async(start = CoroutineStart.UNDISPATCHED) { io.read { 7 } }
        worker.runOne()
        assertEquals(7, retry.await())
    }

    @Test(timeout = 30_000L)
    fun throwingFailureCallbackCannotStrandAnAdmittedSlotOrHideItsOriginalFailure() = runBlocking {
        val worker = HeldWorker()
        val io = LocalProviderIo(worker)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val original = IOException("Synthetic source failure")
        val reporting = IllegalStateException("Synthetic reporting failure")
        val operation = scope.async(start = CoroutineStart.UNDISPATCHED) {
            io.read(failed = { throw reporting }) { throw original }
        }
        worker.runOne()
        try { operation.await(); throw AssertionError("Expected original source failure") }
        catch (failure: SourceReadException) {
            assertSame(original, failure.cause)
            assertTrue(original.suppressed.any { it === reporting })
        }
        io.awaitIdle()
        val retry = scope.async(start = CoroutineStart.UNDISPATCHED) { io.read { "retired" } }
        worker.runOne()
        assertEquals("retired", retry.await())
    }

    @Test(timeout = 30_000L)
    fun rejectedDispatchWithAThrowingReporterStillReleasesBeforeTheSequentialRetry() = runBlocking {
        val worker = HeldWorker().apply { rejectOnce = true }
        val io = LocalProviderIo(worker)
        try {
            io.read(failed = { throw IllegalStateException("Synthetic reporting failed") }) { Unit }
            throw AssertionError("Expected dispatcher rejection")
        } catch (_: RejectedExecutionException) { }
        io.awaitIdle()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val retry = scope.async(start = CoroutineStart.UNDISPATCHED) { io.read { 11 } }
        worker.runOne()
        assertEquals(11, retry.await())
    }

    @Test(timeout = 30_000L)
    fun anAdmissionCallbackFailureDoesNotQueueAWorkerOrKeepTheChannelBusy() = runBlocking {
        val worker = HeldWorker()
        val io = LocalProviderIo(worker)
        val original = IllegalStateException("Synthetic admission rejected")
        try { io.read(admitted = { throw original }) { Unit }; throw AssertionError("Expected admission failure") }
        catch (failure: IllegalStateException) { assertSame(original, failure) }
        assertTrue(worker.work.isEmpty())
        io.awaitIdle()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val retry = scope.async(start = CoroutineStart.UNDISPATCHED) { io.read { 13 } }
        worker.runOne()
        assertEquals(13, retry.await())
    }
}
