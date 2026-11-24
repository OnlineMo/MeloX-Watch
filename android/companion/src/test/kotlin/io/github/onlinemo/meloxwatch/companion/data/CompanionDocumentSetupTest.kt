package io.github.onlinemo.meloxwatch.companion.data

import android.content.Context
import io.github.onlinemo.meloxwatch.data.transfer.TransferException
import io.github.onlinemo.meloxwatch.data.transfer.TransferIssue
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 通过真实 onWorker 建立/完成/收尾路径注入故障；不以控制等待结束冒充 provider 已退出。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class CompanionDocumentSetupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val executors = CopyOnWriteArrayList<ScheduledThreadPoolExecutor>()
    private var documents: CompanionDocuments? = null

    @After fun close() {
        runBlocking {
            withContext(NonCancellable + Dispatchers.IO) {
                documents?.cancel()
                executors.forEach { it.shutdownNow() }
                executors.forEach { assertTrue(it.awaitTermination(2L, TimeUnit.SECONDS)) }
            }
            withContext(NonCancellable) {
                withTimeout(5000L) {
                    val owner = documents ?: return@withTimeout
                    val retained = field(owner, "active") ?: field(owner, "retirement")
                    val operation = retained as? CompanionDocuments.Operation
                    operation?.worker?.join()
                    operation?.cancellationWorker?.join()
                    try { owner.awaitStopped() }
                    catch (error: TransferException) {
                        assertEquals(TransferIssue.RECOVERY_REQUIRED, error.issue)
                        owner.retryRetirement()
                    }
                }
            }
        }
    }

    @Test(timeout = 30_000L)
    fun executorConstructorOomCompletesTheCallerAndTheSameDocumentOwnerCanWorkAgain() = runBlocking {
        val saved = savedStore()
        val calls = AtomicInteger()
        val actions = AtomicInteger()
        val observed = AtomicReference<CompanionDocuments.Operation>()
        lateinit var owner: CompanionDocuments
        owner = documentOwner(saved.store, factory = {
            observed.set(active(owner))
            if (calls.getAndIncrement() == 0) throw OutOfMemoryError("Controlled local executor setup failure")
            executor()
        })
        withTimeout(2000L) {
            issue(TransferIssue.STORAGE_UNAVAILABLE) { owner.onWorker { actions.incrementAndGet() } }
        }
        withTimeout(2000L) { observed.get().worker!!.join() }
        assertEquals(0, actions.get())
        assertFalse(owner.working.value)
        assertNull(field(owner, "active"))
        assertNull(field(owner, "retirement"))
        assertNull(field(observed.get(), "deadline"))
        assertTrue(executors.isEmpty())
        assertSaved(saved)
        assertEquals(7, withTimeout(2000L) {
            owner.onWorker {
                actions.incrementAndGet()
                7
            }
        })
        assertEquals(1, actions.get())
        assertFalse(owner.working.value)
        assertTrue(executors.all { it.isTerminated })
        assertSaved(saved)
    }

    @Test(timeout = 30_000L)
    fun scheduleThreadSetupOomRetiresTheAlreadyOwnedExecutorBeforeClearingOccupancy() = runBlocking {
        val saved = savedStore()
        val calls = AtomicInteger()
        val actions = AtomicInteger()
        val threadCreations = AtomicInteger()
        val observed = AtomicReference<CompanionDocuments.Operation>()
        val failed = AtomicReference<ScheduledThreadPoolExecutor>()
        lateinit var owner: CompanionDocuments
        owner = documentOwner(saved.store, factory = {
            if (calls.getAndIncrement() != 0) executor()
            else ScheduledThreadPoolExecutor(1, ThreadFactory {
                threadCreations.incrementAndGet()
                val operation = active(owner)
                observed.set(operation)
                val deadline = checkNotNull(field(operation, "deadline"))
                assertSame(failed.get(), field(deadline, "executor"))
                throw OutOfMemoryError("Controlled local deadline thread setup failure")
            }).also { executor ->
                failed.set(executor)
                executors.add(executor)
            }
        })
        withTimeout(2000L) {
            issue(TransferIssue.STORAGE_UNAVAILABLE) { owner.onWorker { actions.incrementAndGet() } }
        }
        withTimeout(2000L) { observed.get().worker!!.join() }
        assertEquals(1, threadCreations.get())
        assertEquals(0, actions.get())
        assertTrue(failed.get().isTerminated)
        assertFalse(owner.working.value)
        assertNull(field(owner, "active"))
        assertNull(field(owner, "retirement"))
        assertNull(field(observed.get(), "deadline"))
        assertSaved(saved)
        assertEquals(9, withTimeout(2000L) { owner.onWorker { 9 } })
        assertTrue(executors.all { it.isTerminated })
        assertSaved(saved)
    }

    @Test(timeout = 30_000L)
    fun failedDeadlineRetirementKeepsItsExactExecutorUntilExplicitRecoveryRetiresIt() = runBlocking {
        val saved = savedStore()
        val calls = AtomicInteger()
        val actions = AtomicInteger()
        val observed = AtomicReference<CompanionDocuments.Operation>()
        val inherited = AtomicReference<CompanionDocuments.Operation>()
        val oldDeadline = AtomicReference<Any>()
        lateinit var owner: CompanionDocuments
        lateinit var failed: FailOnceShutdownExecutor
        failed = FailOnceShutdownExecutor(ThreadFactory {
            val operation = active(owner)
            observed.set(operation)
            oldDeadline.set(checkNotNull(field(operation, "deadline")))
            assertSame(failed, field(oldDeadline.get(), "executor"))
            throw OutOfMemoryError("Controlled local deadline setup failure")
        }) {
            val operation = active(owner)
            inherited.set(operation)
            assertNotSame(observed.get(), operation)
            assertSame(oldDeadline.get(), field(operation, "deadline"))
        }
        executors.add(failed)
        owner = documentOwner(saved.store, factory = {
            if (calls.getAndIncrement() == 0) failed else {
                assertTrue(failed.isTerminated)
                executor()
            }
        })
        withTimeout(2000L) {
            issue(TransferIssue.STORAGE_UNAVAILABLE) { owner.onWorker { actions.incrementAndGet() } }
        }
        val operation = observed.get()
        val originalWorker = checkNotNull(operation.worker)
        withTimeout(2000L) { originalWorker.join() }
        assertEquals(0, actions.get())
        assertTrue(owner.working.value)
        assertEquals(TransferIssue.RECOVERY_REQUIRED, owner.issue.value)
        assertNull(field(owner, "active"))
        assertSame(operation, field(owner, "retirement"))
        assertSame(oldDeadline.get(), field(operation, "deadline"))
        assertSame(failed, field(oldDeadline.get(), "executor"))
        assertFalse(failed.isTerminated)
        withTimeout(1000L) { issue(TransferIssue.RECOVERY_REQUIRED) { owner.awaitStopped() } }
        assertSaved(saved)
        withTimeout(5000L) { owner.retryRetirement() }
        assertEquals(2, failed.shutdownCalls.get())
        assertNotSame(operation, inherited.get())
        assertTrue(originalWorker.isCompleted)
        assertNull(operation.worker)
        assertTrue(failed.isTerminated)
        assertTrue(executors.all { it.isTerminated })
        assertFalse(owner.working.value)
        assertNull(owner.issue.value)
        assertNull(field(owner, "active"))
        assertNull(field(owner, "retirement"))
        assertSaved(saved)
    }

    @Test(timeout = 30_000L)
    fun rejectedWorkerDispatchCompletesWithoutCallingTheActionOrAbandoningTheAdmissionSlot() = runBlocking {
        val saved = savedStore()
        val dispatches = AtomicInteger()
        val factories = AtomicInteger()
        val observed = AtomicReference<CompanionDocuments.Operation>()
        lateinit var owner: CompanionDocuments
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (dispatches.getAndIncrement() == 0) {
                    observed.set(active(owner))
                    throw RejectedExecutionException("Controlled local worker dispatch failure")
                }
                Dispatchers.IO.dispatch(context, block)
            }
        }
        owner = documentOwner(saved.store, dispatcher = dispatcher, factory = {
            factories.incrementAndGet()
            executor()
        })
        withTimeout(2000L) {
            issue(TransferIssue.STORAGE_UNAVAILABLE) { owner.onWorker { fail("Rejected worker ran") } }
        }
        withTimeout(2000L) { observed.get().worker!!.join() }
        assertTrue(observed.get().worker!!.isCompleted)
        assertEquals(0, factories.get())
        assertFalse(owner.working.value)
        assertNull(field(owner, "active"))
        assertNull(field(owner, "retirement"))
        assertSaved(saved)
        assertEquals(11, withTimeout(2000L) { owner.onWorker { 11 } })
        assertTrue(executors.all { it.isTerminated })
        assertSaved(saved)
    }

    @Test(timeout = 30_000L)
    fun scheduleSetupErrorCompletesAfterItsFinallyHasReleasedTheSlot() = runBlocking {
        val saved = savedStore()
        val calls = AtomicInteger()
        val actions = AtomicInteger()
        val observed = AtomicReference<CompanionDocuments.Operation>()
        val failed = AtomicReference<ScheduledThreadPoolExecutor>()
        lateinit var owner: CompanionDocuments
        owner = documentOwner(saved.store, factory = {
            if (calls.getAndIncrement() != 0) executor()
            else ScheduledThreadPoolExecutor(1, ThreadFactory {
                val operation = active(owner)
                observed.set(operation)
                assertSame(failed.get(), field(checkNotNull(field(operation, "deadline")), "executor"))
                throw LinkageError("Controlled local schedule setup error")
            }).also { executor ->
                failed.set(executor)
                executors.add(executor)
            }
        })
        withTimeout(2000L) {
            issue(TransferIssue.STORAGE_UNAVAILABLE) { owner.onWorker { actions.incrementAndGet() } }
        }
        withTimeout(2000L) { observed.get().worker!!.join() }
        assertTrue(observed.get().worker!!.isCompleted)
        assertTrue(failed.get().isTerminated)
        assertEquals(0, actions.get())
        assertFalse(owner.working.value)
        assertNull(field(owner, "active"))
        assertNull(field(owner, "retirement"))
        assertNull(field(observed.get(), "deadline"))
        assertSaved(saved)
        assertEquals(13, withTimeout(2000L) { owner.onWorker { 13 } })
        assertTrue(executors.all { it.isTerminated })
        assertSaved(saved)
    }

    private class FailOnceShutdownExecutor(
        factory: ThreadFactory,
        private val onRetry: () -> Unit,
    ) : ScheduledThreadPoolExecutor(1, factory) {
        val shutdownCalls = AtomicInteger()
        override fun shutdown() {
            if (shutdownCalls.incrementAndGet() == 1) throw SecurityException("Controlled local timer close failure")
            onRetry()
            super.shutdown()
        }
    }

    private data class SavedStore(val store: CompanionLocalStore, val record: File, val bytes: ByteArray)

    private suspend fun savedStore(): SavedStore = withContext(Dispatchers.IO) {
        val directory = temporary.newFolder()
        val store = CompanionLocalStore(directory)
        store.update { it.copy(theme = it.theme.edited(name = "Retained local theme")) }
        val record = File(directory, "state.v2")
        SavedStore(store, record, record.readBytes())
    }

    private suspend fun assertSaved(saved: SavedStore) = withContext(Dispatchers.IO) {
        assertArrayEquals(saved.bytes, saved.record.readBytes())
    }

    private fun documentOwner(
        store: CompanionLocalStore,
        dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
        factory: () -> ScheduledThreadPoolExecutor,
    ): CompanionDocuments = CompanionDocuments(
        resolver = (RuntimeEnvironment.getApplication() as Context).contentResolver,
        store = store,
        dispatcher = dispatcher,
        retirementWaitMs = 1000L,
        deadlineFactory = factory,
    ).also { documents = it }

    private fun executor(): ScheduledThreadPoolExecutor = ScheduledThreadPoolExecutor(1) {
        Thread(it, "Controlled local document timer").apply { isDaemon = true }
    }.also { executors.add(it) }

    private fun field(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    private fun active(owner: CompanionDocuments): CompanionDocuments.Operation =
        checkNotNull(field(owner, "active")) as CompanionDocuments.Operation

    private suspend fun issue(expected: TransferIssue, action: suspend () -> Any?) {
        try {
            action()
            fail("Expected bounded local setup failure")
        }
        catch (error: TransferException) { assertEquals(expected, error.issue) }
    }
}
