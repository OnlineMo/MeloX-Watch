package io.github.onlinemo.meloxwatch.data.database

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistViewEntity
import io.github.onlinemo.meloxwatch.data.playlists.RoomPlaylistRepository
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** 原生 writer 竞争及嵌套 Room Job 切换；外层 watchdog 取消不能满足任何正常断言。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class RoomWriterCancellationNativeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private lateinit var database: MeloXDatabase
    private lateinit var playlists: RoomPlaylistRepository
    private lateinit var databaseRoot: Job

    @Before
    fun openDatabase() = runBlocking {
        val file = File(temporaryFolder.root, "room-writer-cancellation.db")
        database = Room.databaseBuilder(
            RuntimeEnvironment.getApplication() as Context,
            MeloXDatabase::class.java,
            file.absolutePath,
        )
            .setDriver(BundledSQLiteDriver())
            .addMigrations(MeloXDatabase.MIGRATION_1_2, MeloXDatabase.MIGRATION_2_3)
            .build()
        playlists = RoomPlaylistRepository(
            database,
            database.playlistDao(),
            elapsedMs = { 1_000L },
            epochMs = { 1_794_787_200_000L },
        )
        playlists.initialize()
        database.withTestWriterTransaction {
            execute("CREATE TABLE room_cancel_marker (id INTEGER PRIMARY KEY NOT NULL, value INTEGER NOT NULL)")
            execute("INSERT INTO room_cancel_marker (id, value) VALUES (1, 0)")
            execute("CREATE TABLE room_cancel_parent (id INTEGER PRIMARY KEY NOT NULL)")
            execute("CREATE TABLE room_cancel_child (id INTEGER PRIMARY KEY NOT NULL, parent_id INTEGER NOT NULL, " +
                "FOREIGN KEY (parent_id) REFERENCES room_cancel_parent(id) DEFERRABLE INITIALLY DEFERRED)")
        }
        // 沿公开连接执行 Job 的父链找到实际数据库根，不访问 Room restricted/internal API。
        databaseRoot = database.useWriterConnection {
            var execution = checkNotNull(currentCoroutineContext()[Job])
            while (execution.parent is Continuation<*>) execution = checkNotNull(execution.parent)
            checkNotNull(execution.parent)
        }
        assertFalse(databaseRoot is Continuation<*>)
        assertTrue(databaseRoot.isActive)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test(timeout = 30_000L)
    fun ordinaryCancellationRetiresARealQueuedWriterBeforeTheBlockerIsReleased() = runBlocking {
        val testCaller = checkNotNull(currentCoroutineContext()[Job])
        val gate = WriterGate()
        val holder = launch(Dispatchers.IO) { database.withPreservedTransaction { gate.hold(database) } }
        val ran = AtomicBoolean(false)
        val reported = CompletableDeferred<CancellationException>()
        val probe = RoomExecutionProbe(databaseRoot)
        var waiting: Job? = null
        try {
            withTimeout(WATCHDOG_MS) { gate.entered.await() }
            val worker = async(Dispatchers.IO + probe) {
                try {
                    database.withTestWriterTransaction {
                        ran.set(true)
                        execute("UPDATE room_cancel_marker SET value = 1 WHERE id = 1")
                    }
                } catch (error: CancellationException) {
                    reported.complete(error)
                    throw error
                }
            }
            waiting = worker
            val roomExecution = awaitQueued(probe, worker)
            val original = CancellationException("Native test caller cancelled")
            worker.cancel(original)
            val returned = withTimeout(WATCHDOG_MS) {
                worker.join()
                reported.await()
            }
            assertSame(original, returned)
            assertTrue(worker.isCancelled)
            assertTrue(worker.isCompleted)
            assertFalse(worker.children.any())
            assertTrue(roomExecution.isCompleted)
            assertFalse(ran.get())
            assertHeld(gate, holder)
            assertTrue(testCaller.isActive)
            assertTrue(databaseRoot.isActive)
        } finally {
            retire(gate, holder, waiting)
        }
        assertUnchangedAndRetry()
    }

    @Test(timeout = 30_000L)
    fun ownTimeoutReturnsNullAfterCancellingARealQueuedWriter() = runBlocking {
        val gate = WriterGate()
        val holder = launch(Dispatchers.IO) { database.withPreservedTransaction { gate.hold(database) } }
        val ran = AtomicBoolean(false)
        val probe = RoomExecutionProbe(databaseRoot)
        var waiting: Job? = null
        try {
            withTimeout(WATCHDOG_MS) { gate.entered.await() }
            val worker = async(Dispatchers.IO + probe) {
                withTimeoutOrNull(CLOSE_MS) {
                    database.withTestWriterTransaction {
                        ran.set(true)
                        execute("UPDATE room_cancel_marker SET value = 1 WHERE id = 1")
                    }
                    1
                }
            }
            waiting = worker
            val roomExecution = awaitQueued(probe, worker)
            assertNull(withTimeout(WATCHDOG_MS) { worker.await() })
            assertTrue(worker.isCompleted)
            assertFalse(worker.isCancelled)
            assertFalse(worker.children.any())
            assertTrue(roomExecution.isCompleted)
            assertFalse(ran.get())
            assertHeld(gate, holder)
            assertTrue(databaseRoot.isActive)
        } finally {
            retire(gate, holder, waiting)
        }
        assertUnchangedAndRetry()
    }

    @Test(timeout = 30_000L)
    fun throwingTimeoutRemainsAConcreteTimeoutAfterCancellingARealQueuedWriter() = runBlocking {
        val testCaller = checkNotNull(currentCoroutineContext()[Job])
        val gate = WriterGate()
        val holder = launch(Dispatchers.IO) { database.withPreservedTransaction { gate.hold(database) } }
        val probe = RoomExecutionProbe(databaseRoot)
        var waiting: Job? = null
        var timeoutCaller: Job? = null
        val restoredIdentityChecked = AtomicBoolean(false)
        try {
            withTimeout(WATCHDOG_MS) { gate.entered.await() }
            val worker = async(Dispatchers.IO + probe) {
                try {
                    withTimeout(CLOSE_MS) {
                        timeoutCaller = checkNotNull(currentCoroutineContext()[Job])
                        try {
                            database.withTestWriterTransaction {
                                execute("UPDATE room_cancel_marker SET value = 1 WHERE id = 1")
                            }
                        } catch (error: TimeoutCancellationException) {
                            val original = try {
                                checkNotNull(timeoutCaller).ensureActive()
                                throw AssertionError("Expected the operation Job to remain cancelled")
                            } catch (cancelled: CancellationException) {
                                cancelled
                            }
                            // 尚未跨 enclosing withTimeout 的栈恢复边界，直接验证桥接恢复身份。
                            assertSame(original, error)
                            restoredIdentityChecked.set(true)
                            throw error
                        }
                    }
                    throw AssertionError("Expected this operation's own timeout")
                } catch (error: TimeoutCancellationException) {
                    error
                }
            }
            waiting = worker
            val roomExecution = awaitQueued(probe, worker)
            // watchdog 在被捕获的操作外，不可能提供这里返回的超时异常。
            val returned = withTimeout(WATCHDOG_MS) { worker.await() }
            assertEquals(TimeoutCancellationException::class.java, returned.javaClass)
            assertTrue(restoredIdentityChecked.get())
            assertTrue(checkNotNull(timeoutCaller).isCancelled)
            assertTrue(checkNotNull(timeoutCaller).isCompleted)
            assertTrue(worker.isCompleted)
            assertFalse(worker.isCancelled)
            assertFalse(worker.children.any())
            assertTrue(roomExecution.isCompleted)
            assertHeld(gate, holder)
            assertTrue(testCaller.isActive)
            assertTrue(databaseRoot.isActive)
        } finally {
            retire(gate, holder, waiting)
        }
        assertUnchangedAndRetry()
    }

    @Test(timeout = 30_000L)
    fun writerInspectionTimeoutAlsoRetiresItsRealConnectionWait() = runBlocking {
        val gate = WriterGate()
        val holder = launch(Dispatchers.IO) { database.withPreservedTransaction { gate.hold(database) } }
        val probe = RoomExecutionProbe(databaseRoot)
        var waiting: Job? = null
        try {
            withTimeout(WATCHDOG_MS) { gate.entered.await() }
            val worker = async(Dispatchers.IO + probe) {
                withTimeoutOrNull(CLOSE_MS) { database.hasCurrentWriterTransaction() }
            }
            waiting = worker
            val roomExecution = awaitQueued(probe, worker)
            assertNull(withTimeout(WATCHDOG_MS) { worker.await() })
            assertFalse(worker.isCancelled)
            assertFalse(worker.children.any())
            assertTrue(roomExecution.isCompleted)
            assertHeld(gate, holder)
        } finally {
            retire(gate, holder, waiting)
        }
        assertUnchangedAndRetry()
    }

    @Test(timeout = 30_000L)
    fun directPlaylistCloseTimeoutRetainsTheExactViewUntilAnExplicitRetry() = runBlocking {
        val view = playlists.openCatalogView()
        val original = checkNotNull(database.playlistDao().view(view.viewId))
        val gate = WriterGate(view.viewId)
        val holder = launch(Dispatchers.IO) { database.withPreservedTransaction { gate.hold(database) } }
        val probe = RoomExecutionProbe(databaseRoot)
        var waiting: Job? = null
        try {
            withTimeout(WATCHDOG_MS) { gate.entered.await() }
            val worker = async(Dispatchers.IO + probe) {
                withTimeoutOrNull(CLOSE_MS) {
                    playlists.closeView(view.viewId)
                    1
                }
            }
            waiting = worker
            val roomExecution = awaitQueued(probe, worker)
            assertNull(withTimeout(WATCHDOG_MS) { worker.await() })
            assertFalse(worker.isCancelled)
            assertFalse(worker.children.any())
            assertTrue(roomExecution.isCompleted)
            assertEquals(original, assertHeld(gate, holder).playlistView)
            assertTrue(databaseRoot.isActive)
        } finally {
            retire(gate, holder, waiting)
        }
        // 取消的等待完成后再排 writer 屏障；迟到的旧删除不能消耗该视图租约。
        withTimeout(WATCHDOG_MS) {
            database.withPreservedTransaction { assertEquals(original, database.playlistDao().view(view.viewId)) }
            playlists.closeView(view.viewId)
            assertNull(database.playlistDao().view(view.viewId))
            playlists.closeView(view.viewId)
        }
        assertTrue(databaseRoot.isActive)
        integrity()
    }

    @Test(timeout = 30_000L)
    fun nestedOwnTimeoutRollsBackItsSavepointAndKeepsTheOuterTransactionUsable() = runBlocking {
        withTimeout(WATCHDOG_MS) {
            database.withPreservedTransaction {
                val result = withTimeoutOrNull(CLOSE_MS) {
                    database.withTestWriterTransaction {
                        execute("UPDATE room_cancel_marker SET value = 1 WHERE id = 1")
                        awaitCancellation()
                    }
                }
                assertNull(result)
                assertTrue(database.hasCurrentWriterTransaction())
                database.withTestWriterSql {
                    assertEquals(0L, scalarLong("SELECT value FROM room_cancel_marker WHERE id = 1"))
                    execute("UPDATE room_cancel_marker SET value = 2 WHERE id = 1")
                }
            }
        }
        assertEquals(2L, marker())
        assertFalse(checkNotNull(currentCoroutineContext()[Job]).children.any())
        assertTrue(databaseRoot.isActive)
        integrity()
    }

    @Test(timeout = 30_000L)
    fun nestedProtectedCleanupSurvivesCancellationAndADispatcherHop() = runBlocking {
        val protectedReached = AtomicBoolean(false)
        withTimeout(WATCHDOG_MS) {
            database.withPreservedTransaction {
                val result = withTimeoutOrNull(CLOSE_MS) {
                    database.withPreservedTransaction {
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                withContext(Dispatchers.Default) {
                                    database.withTestWriterTransaction {
                                        execute("UPDATE room_cancel_marker SET value = 3 WHERE id = 1")
                                    }
                                    protectedReached.set(true)
                                }
                            }
                        }
                    }
                }
                assertNull(result)
                assertTrue(protectedReached.get())
                assertTrue(database.hasCurrentWriterTransaction())
                // 保护清理已完成，随后取消的外层 savepoint 回滚其全部写入。
                database.withTestWriterSql {
                    assertEquals(0L, scalarLong("SELECT value FROM room_cancel_marker WHERE id = 1"))
                    execute("UPDATE room_cancel_marker SET value = 2 WHERE id = 1")
                }
            }
        }
        assertEquals(2L, marker())
        assertFalse(checkNotNull(currentCoroutineContext()[Job]).children.any())
        assertTrue(databaseRoot.isActive)
        integrity()
    }

    @Test(timeout = 30_000L)
    fun protectedCleanupCommitsAfterExternalCancellationAndReleasesItsRoomContexts() = runBlocking {
        val testCaller = checkNotNull(currentCoroutineContext()[Job])
        val view = playlists.openCatalogView()
        val entered = CompletableDeferred<Unit>()
        val reported = CompletableDeferred<CancellationException>()
        val cleanupCommitted = AtomicBoolean(false)
        val probe = RoomExecutionProbe(databaseRoot)
        val worker = async(Dispatchers.IO + probe) {
            try {
                withRoomCallerCancellation {
                    try {
                        database.withTestWriterTransaction {
                            execute("UPDATE room_cancel_marker SET value = 1 WHERE id = 1")
                            entered.complete(Unit)
                            awaitCancellation()
                        }
                    } finally {
                        // 旧桥接已停止；父链断开的保护作用域仍须能建立新的事务及直接 DAO 桥接。
                        withContext(NonCancellable) {
                            withContext(Dispatchers.Default) {
                                database.withTestWriterTransaction {
                                    assertEquals(0L, scalarLong("SELECT value FROM room_cancel_marker WHERE id = 1"))
                                    execute("UPDATE room_cancel_marker SET value = 3 WHERE id = 1")
                                }
                                assertEquals(3L, marker())
                                playlists.closeView(view.viewId)
                                assertNull(database.playlistDao().view(view.viewId))
                                cleanupCommitted.set(true)
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                reported.complete(error)
                throw error
            }
        }
        try {
            withTimeout(WATCHDOG_MS) { entered.await() }
            val original = CancellationException("Native test external caller cancelled")
            worker.cancel(original)
            val returned = withTimeout(WATCHDOG_MS) {
                worker.join()
                reported.await()
            }
            assertSame(original, returned)
            assertTrue(worker.isCancelled)
            assertTrue(worker.isCompleted)
            assertFalse(worker.children.any())
            assertTrue(cleanupCommitted.get())
            assertTrue(probe.executions().isNotEmpty())
            assertTrue(probe.executions().all { it.isCompleted })
            assertTrue(testCaller.isActive)
            assertTrue(databaseRoot.isActive)
        } finally {
            withContext(NonCancellable) {
                withTimeout(WATCHDOG_MS) { worker.cancelAndJoin() }
            }
        }
        assertEquals(3L, marker())
        assertNull(database.playlistDao().view(view.viewId))
        assertFalse(testCaller.children.any())
        integrity()
    }

    @Test(timeout = 30_000L)
    fun callerCancellationRetainsBlockCleanupAfterAnEarlierEntryCancellation() = runBlocking {
        val testCaller = checkNotNull(currentCoroutineContext()[Job])
        val original = CancellationException("Native caller cancellation with cleanup evidence")
        val cleanup = IllegalStateException("Native captured cleanup failure")
        val entered = CompletableDeferred<Unit>()
        val caughtBlock = CompletableDeferred<CancellationException>()
        val releaseRethrow = CompletableDeferred<Unit>()
        val reported = CompletableDeferred<CancellationException>()
        val probe = RoomExecutionProbe(databaseRoot)
        val worker = async(Dispatchers.IO + probe) {
            try {
                withRoomCallerCancellation {
                    database.withTestWriterTransaction {
                        execute("UPDATE room_cancel_marker SET value = 1 WHERE id = 1")
                        entered.complete(Unit)
                        try {
                            awaitCancellation()
                        } catch (error: CancellationException) {
                            error.addSuppressed(cleanup)
                            caughtBlock.complete(error)
                            // 外部 cancel 必须先返回，避免 block CE 抢先成为入口的完成原因。
                            withContext(NonCancellable) { releaseRethrow.await() }
                            throw error
                        }
                    }
                }
            } catch (error: CancellationException) {
                // 这里紧邻桥接返回，尚未跨 async 的栈恢复边界。
                reported.complete(error)
                throw error
            }
        }
        try {
            withTimeout(WATCHDOG_MS) { entered.await() }
            worker.cancel(original)
            val observed = withTimeout(WATCHDOG_MS) { caughtBlock.await() }
            assertTrue(observed !== original)
            assertEquals(1, observed.suppressed.count { it === cleanup })
            assertFalse(releaseRethrow.isCompleted)
            assertFalse(worker.isCompleted)
            releaseRethrow.complete(Unit)
            val returned = withTimeout(WATCHDOG_MS) {
                worker.join()
                reported.await()
            }
            assertSame(original, returned)
            assertEquals(1, returned.suppressed.count { it === cleanup })
            assertNull(returned.cause)
            assertTrue(worker.isCancelled)
            assertTrue(worker.isCompleted)
            assertFalse(worker.children.any())
            assertTrue(probe.executions().isNotEmpty())
            assertTrue(probe.executions().all { it.isCompleted })
            assertTrue(testCaller.isActive)
            assertTrue(databaseRoot.isActive)
        } finally {
            releaseRethrow.complete(Unit)
            withContext(NonCancellable) {
                withTimeout(WATCHDOG_MS) { worker.cancelAndJoin() }
            }
        }
        assertFalse(testCaller.children.any())
        assertUnchangedAndRetry()
    }

    @Test(timeout = 30_000L)
    fun businessFailureKeepsIdentityAndRollsBackBeforeASeparateWriterRetry() = runBlocking {
        val original = IllegalStateException("Native transaction business failure")
        val returned = try {
            database.withPreservedTransaction {
                database.withTestWriterSql {
                    execute("UPDATE room_cancel_marker SET value = 1 WHERE id = 1")
                }
                throw original
            }
        } catch (error: IllegalStateException) {
            error
        }
        assertSame(original, returned)
        assertFalse(checkNotNull(currentCoroutineContext()[Job]).children.any())
        assertUnchangedAndRetry()
    }

    @Test(timeout = 30_000L)
    fun failedDeferredCommitRecyclesTheWriterAndReleasesTheCallerObserver() = runBlocking {
        try {
            database.withTestWriterTransaction {
                execute("INSERT INTO room_cancel_child (id, parent_id) VALUES (1, 7)")
            }
            throw AssertionError("Expected a real deferred foreign key COMMIT failure")
        } catch (_: SQLiteConstraintException) {
            // 真实 deferred FK 提交失败后检查同一 writer 已回收并能重试。
        }
        assertFalse(checkNotNull(currentCoroutineContext()[Job]).children.any())
        withTimeout(WATCHDOG_MS) {
            database.withTestWriterTransaction {
                assertEquals(0L, scalarLong("SELECT count(*) FROM room_cancel_child"))
                execute("INSERT INTO room_cancel_parent (id) VALUES (7)")
                execute("INSERT INTO room_cancel_child (id, parent_id) VALUES (1, 7)")
            }
        }
        assertTrue(databaseRoot.isActive)
        integrity()
    }

    @Test(timeout = 30_000L)
    fun sequentialDaoHopsReleaseCompletedContextsInsteadOfAccumulatingAProductLimit() = runBlocking {
        withTimeout(WATCHDOG_MS) {
            withRoomCallerCancellation {
                repeat(128) { index ->
                    database.withPreservedTransaction {
                        assertTrue(database.hasCurrentWriterTransaction())
                        assertTrue(checkNotNull(database.playlistDao().meta()).revision >= 0L)
                        database.withTestWriterSql {
                            execute("UPDATE room_cancel_marker SET value = ? WHERE id = 1", index + 1)
                        }
                    }
                }
            }
        }
        assertEquals(128L, marker())
        assertFalse(checkNotNull(currentCoroutineContext()[Job]).children.any())
        assertTrue(databaseRoot.isActive)
        integrity()
    }

    private suspend fun awaitQueued(probe: RoomExecutionProbe, waiting: Job): Job = withTimeout(WATCHDOG_MS) {
        val execution = probe.entered.await()
        // writer 被占用时，真实 acquireWithTimeout 子任务等待连接许可。
        // 等到该子任务存在，避免把 Room 尚未执行的取消误记为排队覆盖。
        while (execution.children.none()) {
            assertFalse("Operation ended before entering the occupied Room writer wait", waiting.isCompleted)
            yield()
        }
        assertTrue(execution.isActive)
        assertFalse(waiting.isCompleted)
        execution
    }

    private suspend fun assertHeld(gate: WriterGate, holder: Job): HeldState {
        assertFalse(holder.isCompleted)
        assertFalse(gate.release.isCompleted)
        gate.inspect.complete(Unit)
        val state = withTimeout(WATCHDOG_MS) { gate.observed.await() }
        assertEquals(0L, state.marker)
        assertFalse(holder.isCompleted)
        assertFalse(gate.release.isCompleted)
        return state
    }

    private suspend fun retire(gate: WriterGate, holder: Job, waiting: Job?) {
        gate.inspect.complete(Unit)
        gate.release.complete(Unit)
        withContext(NonCancellable) {
            withTimeout(WATCHDOG_MS) {
                holder.cancelAndJoin()
                waiting?.cancelAndJoin()
            }
        }
    }

    private suspend fun marker(): Long = database.withTestWriterTransaction {
        scalarLong("SELECT value FROM room_cancel_marker WHERE id = 1")
    }

    private suspend fun assertUnchangedAndRetry() {
        withTimeout(WATCHDOG_MS) {
            assertEquals(0L, marker())
            database.withTestWriterTransaction {
                execute("UPDATE room_cancel_marker SET value = 1 WHERE id = 1")
            }
            assertEquals(1L, marker())
        }
        assertTrue(databaseRoot.isActive)
        integrity()
    }

    private suspend fun integrity() = database.withTestWriterTransaction {
        query("PRAGMA foreign_key_check") { assertFalse(it.step()) }
        query("PRAGMA integrity_check") {
            assertTrue(it.step())
            assertEquals("ok", it.getText(0))
        }
    }

    private data class HeldState(val marker: Long, val playlistView: PlaylistViewEntity?)

    private class WriterGate(private val playlistViewId: String? = null) {
        val entered = CompletableDeferred<Unit>()
        val inspect = CompletableDeferred<Unit>()
        val observed = CompletableDeferred<HeldState>()
        val release = CompletableDeferred<Unit>()

        suspend fun hold(database: MeloXDatabase) {
            check(database.hasCurrentWriterTransaction())
            entered.complete(Unit)
            inspect.await()
            val marker = database.withTestWriterSql {
                scalarLong("SELECT value FROM room_cancel_marker WHERE id = 1")
            }
            val view = playlistViewId?.let { database.playlistDao().view(it) }
            observed.complete(HeldState(marker, view))
            release.await()
        }
    }

    private class RoomExecutionProbe(private val databaseRoot: Job) :
        AbstractCoroutineContextElement(Key), ThreadContextElement<Unit> {
        companion object Key : CoroutineContext.Key<RoomExecutionProbe>
        private val observed = ConcurrentHashMap.newKeySet<Job>()
        val entered = CompletableDeferred<Job>()

        fun executions(): Set<Job> = observed.toSet()

        override fun updateThreadContext(context: CoroutineContext) {
            val execution = context[Job] ?: return
            if (execution.parent === databaseRoot) {
                observed.add(execution)
                entered.complete(execution)
            }
        }
        override fun restoreThreadContext(context: CoroutineContext, oldState: Unit) = Unit
    }

    private companion object {
        const val CLOSE_MS = 100L
        const val WATCHDOG_MS = 10_000L
    }
}
