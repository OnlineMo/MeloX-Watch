package io.github.onlinemo.meloxwatch.data.backup

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** The worker has returned a real owner, but its queued continuation has not delivered it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BackupPhysicalOwnerTest {
    @get:Rule val temporary = TemporaryFolder()
    private class HeldDispatcher : CoroutineDispatcher() {
        private val lock = Any()
        private val queued = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { synchronized(lock) { queued.addLast(block) } }
        fun runOne(): Boolean {
            val block = synchronized(lock) { queued.pollFirst() } ?: return false
            block.run()
            return true
        }
    }
    private suspend fun deliverUntil(dispatcher: HeldDispatcher, complete: () -> Boolean) = withTimeout(20_000L) {
        while (!complete()) { if (!dispatcher.runOne()) delay(1L) }
    }

    private class FailFirstStageClose : AutoCloseable {
        var stage: SqliteBackupStage? = null
        val closed = AtomicBoolean(false)
        private val fail = AtomicBoolean(true)
        override fun close() {
            if (fail.getAndSet(false)) throw IOException("Synthetic delivered stage close failure")
            stage?.close(); closed.set(true)
        }
        // Equal wrapper values still own different native connections and must both survive.
        override fun equals(other: Any?): Boolean = other is FailFirstStageClose
        override fun hashCode(): Int = 1
    }

    private suspend fun deliveredStage(io: LocalProviderIo, directory: BackupPrivateDirectory,
        retentions: BackupCloseRetentions): FailFirstStageClose = openBackupPhysicalOwner(io,
        onCloseFailure = { owner, _ -> retentions.retain(owner) }) { _, register ->
        val owner = FailFirstStageClose()
        register(owner)
        owner.stage = SqliteBackupStage.create(directory, onOpened = { owner.stage = it }) {}
        checkNotNull(owner.stage).begin(BackupTestFixtures.header(BackupScope.HISTORY))
        checkNotNull(owner.stage).record(BackupTestFixtures.historyMeta())
        owner
    }

    private fun requireStageLocked(directory: BackupPrivateDirectory) {
        var locked = false
        BundledSQLiteDriver().open(directory.child("rows.db").absolutePath).use { connection ->
            try { connection.execute("BEGIN IMMEDIATE"); connection.execute("ROLLBACK") }
            catch (_: Exception) { locked = true }
        }
        assertTrue(locked)
    }

    private fun requireStageReleased(directory: BackupPrivateDirectory) {
        BundledSQLiteDriver().open(directory.child("rows.db").absolutePath).use { connection ->
            connection.execute("BEGIN IMMEDIATE"); connection.execute("ROLLBACK")
        }
    }

    @Test(timeout = 30_000L)
    fun cancellingQueuedDeliveryClosesTheActualStageBeforeDirectoryRetirementAndBarrierRelease() = runBlocking {
        val worker = HeldDispatcher()
        val caller = HeldDispatcher()
        val io = LocalProviderIo(worker)
        val barrier = BackupMutationBarrier()
        barrier.initializeStorage {}
        val root = BackupPrivateDirectory.open(temporary.newFolder())
        val generation = root.newGeneration()
        val closeStarted = CompletableDeferred<Unit>()
        val closeAllowed = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        val retired = AtomicBoolean(false)
        val delivered = AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + caller)
        val operation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            barrier.exclusively {
                try {
                    openBackupPhysicalOwner(io) { _, _ ->
                        val stage = SqliteBackupStage.create(generation) {}
                        AutoCloseable {
                            closeStarted.complete(Unit)
                            try { check(closeAllowed.await(20L, TimeUnit.SECONDS)) { "Synthetic close release was not received" } }
                            finally { stage.close(); closed.set(true) }
                        }
                    }
                    delivered.set(true)
                } finally {
                    assertTrue(closed.get())
                    root.retireGeneration(generation)
                    retired.set(true)
                }
            }
        }
        assertTrue(worker.runOne())
        operation.cancel(CancellationException("Synthetic cancellation before owner delivery"))
        assertTrue(caller.runOne())
        try {
            withTimeout(20_000L) { closeStarted.await() }
            assertEquals(BackupAccessState.EXCLUSIVE_OPERATION, barrier.state.value)
            assertFalse(operation.isCompleted)
            assertTrue(generation.root.exists())
            assertFalse(retired.get())
        } finally { closeAllowed.countDown() }
        deliverUntil(caller) { operation.isCompleted }
        assertTrue(closed.get()); assertTrue(retired.get())
        assertFalse(delivered.get())
        assertFalse(generation.root.exists())
        assertEquals(BackupAccessState.READY, barrier.state.value)
    }

    @Test(timeout = 30_000L)
    fun cancelledJournalDeliveryReleasesItsNativeLockBeforeTheCallerCanLeaveItsOwner() = runBlocking {
        val worker = HeldDispatcher()
        val caller = HeldDispatcher()
        val io = LocalProviderIo(worker)
        val barrier = BackupMutationBarrier()
        barrier.initializeStorage {}
        val root = BackupPrivateDirectory.open(temporary.newFolder())
        val reopened = AtomicBoolean(false)
        val delivered = AtomicBoolean(false)
        val scope = CoroutineScope(SupervisorJob() + caller)
        val operation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            barrier.exclusively {
                try {
                    val journal = openBackupPhysicalOwner(io) { _, register -> BackupRestoreJournal.open(root, onOpened = register) }
                    delivered.set(true)
                    journal.close()
                } finally { BackupRestoreJournal.open(root).use { reopened.set(true) } }
            }
        }
        assertTrue(worker.runOne())
        expectBackupIssue(BackupIssue.BUSY) { BackupRestoreJournal.open(root).close() }
        operation.cancel(CancellationException("Synthetic cancelled journal delivery"))
        deliverUntil(caller) { operation.isCompleted }
        assertFalse(delivered.get()); assertTrue(reopened.get())
        assertEquals(BackupAccessState.READY, barrier.state.value)
        BackupRestoreJournal.open(root).use { assertEquals(null, it.readRun()) }
    }

    @Test(timeout = 30_000L)
    fun failedUndeliveredClosePreservesCancellationAndTheSameRealOwnerForRetry() = runBlocking {
        val worker = HeldDispatcher()
        val caller = HeldDispatcher()
        val io = LocalProviderIo(worker)
        val root = BackupPrivateDirectory.open(temporary.newFolder())
        val pending = AtomicReference<AutoCloseable?>(null)
        val actual = AtomicReference<AutoCloseable?>(null)
        val observed = AtomicReference<Throwable?>(null)
        val closingFailure = IOException("Synthetic first close failure")
        val failFirstClose = AtomicBoolean(true)
        val scope = CoroutineScope(SupervisorJob() + caller)
        val operation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                openBackupPhysicalOwner<AutoCloseable>(io, onCloseFailure = { owner, _ -> pending.set(owner) }) { _, _ ->
                    val journal = BackupRestoreJournal.open(root)
                    AutoCloseable {
                        if (failFirstClose.getAndSet(false)) throw closingFailure
                        journal.close()
                    }.also(actual::set)
                }
            } catch (failure: Throwable) { observed.set(failure); throw failure }
        }
        assertTrue(worker.runOne())
        operation.cancel(CancellationException("Synthetic cancellation with failed cleanup"))
        deliverUntil(caller) { operation.isCompleted }
        val original = checkNotNull(observed.get())
        assertTrue(original is CancellationException)
        assertTrue(original.suppressed.any { it === closingFailure })
        assertSame(actual.get(), pending.get())
        expectBackupIssue(BackupIssue.BUSY) { BackupRestoreJournal.open(root).close() }
        checkNotNull(pending.get()).close()
        BackupRestoreJournal.open(root).use { assertEquals(null, it.readRun()) }
    }

    @Test(timeout = 30_000L)
    fun successfulDeliveryKeepsJournalOwnershipUntilItsCallerClosesItIdempotently() = runBlocking {
        val root = BackupPrivateDirectory.open(temporary.newFolder())
        val journal = openBackupPhysicalOwner(LocalProviderIo()) { _, register -> BackupRestoreJournal.open(root, onOpened = register) }
        expectBackupIssue(BackupIssue.BUSY) { BackupRestoreJournal.open(root).close() }
        journal.close(); journal.close()
        BackupRestoreJournal.open(root).use { assertEquals(null, it.readRun()) }
    }

    @Test(timeout = 30_000L)
    fun failureAfterFactoryRegistrationKeepsItsActualOwnerDespiteTwoFailedCloseAcknowledgements() = runBlocking {
        val worker = HeldDispatcher()
        val caller = HeldDispatcher()
        val io = LocalProviderIo(worker)
        val root = BackupPrivateDirectory.open(temporary.newFolder())
        val original = BackupException(BackupIssue.INTEGRITY_MISMATCH)
        val firstClose = IOException("Synthetic factory close failure")
        val secondClose = IOException("Synthetic cleanup close failure")
        val attempts = AtomicInteger(0)
        val pending = AtomicReference<AutoCloseable?>(null)
        val actual = AtomicReference<AutoCloseable?>(null)
        val observed = AtomicReference<Throwable?>(null)
        val scope = CoroutineScope(SupervisorJob() + caller)
        val operation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                openBackupPhysicalOwner<AutoCloseable>(io, onCloseFailure = { owner, _ -> pending.set(owner) }) { _, register ->
                    val journal = BackupRestoreJournal.open(root)
                    val owner = AutoCloseable {
                        when (attempts.getAndIncrement()) {
                            0 -> throw firstClose
                            1 -> throw secondClose
                            else -> journal.close()
                        }
                    }
                    actual.set(owner)
                    register(owner)
                    try { owner.close() } catch (closing: Throwable) { original.addSuppressed(closing) }
                    throw original
                }
            } catch (failure: Throwable) { observed.set(failure) }
        }
        assertTrue(worker.runOne())
        deliverUntil(caller) { operation.isCompleted }
        assertSame(original, observed.get())
        assertTrue(original.suppressed.any { it === firstClose })
        assertTrue(original.suppressed.any { it === secondClose })
        assertSame(actual.get(), pending.get())
        expectBackupIssue(BackupIssue.BUSY) { BackupRestoreJournal.open(root).close() }
        checkNotNull(pending.get()).close()
        BackupRestoreJournal.open(root).use { assertEquals(null, it.readRun()) }
    }

    @Test(timeout = 30_000L)
    fun deliveredCloseFailureRetainsTheSameNativeStageUntilAConfirmedRetry() = runBlocking {
        val io = LocalProviderIo()
        val barrier = BackupMutationBarrier()
        barrier.initializeStorage {}
        val retentions = BackupCloseRetentions(io, barrier::requireRecovery)
        val root = BackupPrivateDirectory.open(temporary.newFolder())
        val generation = root.newGeneration()
        val owner = deliveredStage(io, generation, retentions)
        try { retentions.close(owner); throw AssertionError("Synthetic first close must fail") }
        catch (_: IOException) { }
        assertTrue(retentions.contains(owner)); assertEquals(1, retentions.pendingCount)
        assertEquals(BackupAccessState.RECOVERY_REQUIRED, barrier.state.value)
        assertFalse(owner.closed.get()); assertTrue(generation.root.exists())
        requireStageLocked(generation)
        retentions.retryAll()
        assertFalse(retentions.hasPending); assertTrue(owner.closed.get())
        requireStageReleased(generation)
        root.retireGeneration(generation)
        barrier.initializeStorage {}
        assertEquals(BackupAccessState.READY, barrier.state.value)
    }

    @Test(timeout = 60_000L)
    fun twoDeliveredStagesWithEqualWrapperValuesBothKeepTheirOwnNativeHandlesForRetry() = runBlocking {
        val io = LocalProviderIo()
        val barrier = BackupMutationBarrier()
        barrier.initializeStorage {}
        val retentions = BackupCloseRetentions(io, barrier::requireRecovery)
        val root = BackupPrivateDirectory.open(temporary.newFolder())
        val before = root.newGeneration()
        val incoming = root.newGeneration()
        val beforeOwner = deliveredStage(io, before, retentions)
        val incomingOwner = deliveredStage(io, incoming, retentions)
        for (owner in listOf(beforeOwner, incomingOwner)) {
            try { retentions.close(owner); throw AssertionError("Each synthetic first close must fail") }
            catch (_: IOException) { }
        }
        assertTrue(beforeOwner == incomingOwner)
        assertTrue(beforeOwner !== incomingOwner)
        assertEquals(2, retentions.pendingCount)
        assertTrue(retentions.contains(beforeOwner)); assertTrue(retentions.contains(incomingOwner))
        requireStageLocked(before); requireStageLocked(incoming)
        assertEquals(BackupAccessState.RECOVERY_REQUIRED, barrier.state.value)
        retentions.retryAll()
        assertEquals(0, retentions.pendingCount)
        assertTrue(beforeOwner.closed.get()); assertTrue(incomingOwner.closed.get())
        requireStageReleased(before); requireStageReleased(incoming)
        root.retireGeneration(before); root.retireGeneration(incoming)
        barrier.initializeStorage {}
        assertEquals(BackupAccessState.READY, barrier.state.value)
    }
}
