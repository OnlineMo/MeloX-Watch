package io.github.onlinemo.meloxwatch.data.library

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.database.hasCurrentWriterTransaction
import io.github.onlinemo.meloxwatch.data.database.withPreservedTransaction
import io.github.onlinemo.meloxwatch.data.database.withTestWriterTransaction
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistException
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

/** Actual mutex and Room queue waits; a returned deadline must not leave a late metadata deletion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomPlaylistViewCloseTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var database: MeloXDatabase
    private lateinit var library: RoomLocalLibraryRepository

    @Before fun openDatabase() {
        runBlocking {
            val file = File(temporaryFolder.root, "playlist-view-close.db")
            database = Room.databaseBuilder(RuntimeEnvironment.getApplication() as Context,
                MeloXDatabase::class.java, file.absolutePath)
                .setDriver(BundledSQLiteDriver())
                .addMigrations(MeloXDatabase.MIGRATION_1_2, MeloXDatabase.MIGRATION_2_3)
                .build()
            library = RoomLocalLibraryRepository(database, { 1_000L }, { 1_794_787_200_000L })
            library.initialize()
        }
    }

    @After fun closeDatabase() { database.close() }

    @Test(timeout = 30_000L)
    fun libraryMutexWaitExpiresAndRetainsTheExactTokenUntilAnExplicitRetry() = runBlocking {
        val view = library.openView()
        val original = checkNotNull(database.libraryDao().view(view.viewId))
        val gate = TransactionGate()
        val holder = launch(Dispatchers.IO) {
            library.libraryTransaction { gate.hold(database, view.viewId) }
        }
        verifyDeadlineBeforeRelease(view, original, gate, holder)
    }

    @Test(timeout = 30_000L)
    fun independentlyQueuedRoomTransactionExpiresWithoutRunningALateDelete() = runBlocking {
        val view = library.openView()
        val original = checkNotNull(database.libraryDao().view(view.viewId))
        val gate = TransactionGate()
        val holder = launch(Dispatchers.IO) {
            // This transaction does not acquire the library mutex. The closer must wait for Room.
            database.withPreservedTransaction { gate.hold(database, view.viewId) }
        }
        verifyDeadlineBeforeRelease(view, original, gate, holder)
    }

    @Test(timeout = 30_000L)
    fun helperConsumesTheConcreteDeadlineAndKeepsOneTokenForAnExplicitCloseRetry() = runBlocking {
        verifyHelperOwnership(CLOSE_MS)
    }

    @Test(timeout = 30_000L)
    fun helperMapsTheConcreteInnerTimeoutEvenWhenItsOwnDeadlineHasNotExpired() = runBlocking {
        verifyHelperOwnership(RETRY_MS)
    }

    @Test(timeout = 30_000L)
    fun cancelledCallerFinishesItsBoundedCleanupWhileTheRealLibraryMutexRemainsOwned() = runBlocking {
        val view = library.openView()
        val original = checkNotNull(database.libraryDao().view(view.viewId))
        val gate = TransactionGate()
        val holder = launch(Dispatchers.IO) {
            library.libraryTransaction { gate.hold(database, view.viewId) }
        }
        val callerEntered = CompletableDeferred<Unit>()
        val cleanupFinished = CompletableDeferred<TimeoutCancellationException>()
        var caller: Job? = null
        try {
            withTimeout(WATCHDOG_MS) { gate.entered.await() }
            val activeCaller = launch(Dispatchers.IO) {
                try {
                    callerEntered.complete(Unit)
                    awaitCancellation()
                } finally {
                    // The production closer supplies NonCancellable; this caller must not mask its cancellation.
                    cleanupFinished.complete(expectCloseDeadline(view.viewId))
                }
            }
            caller = activeCaller
            withTimeout(WATCHDOG_MS) { callerEntered.await() }
            activeCaller.cancel()
            withTimeout(WATCHDOG_MS) {
                activeCaller.join()
                cleanupFinished.await()
            }
            assertTrue(activeCaller.isCancelled)
            assertTrue(activeCaller.isCompleted)
            assertFalse(activeCaller.children.any())
            assertFalse(holder.isCompleted)
            assertFalse(gate.release.isCompleted)
            gate.inspect.complete(Unit)
            assertEquals(original, withTimeout(WATCHDOG_MS) { gate.observed.await() })
        } finally {
            gate.inspect.complete(Unit)
            gate.release.complete(Unit)
            withContext(NonCancellable) {
                withTimeout(WATCHDOG_MS) {
                    holder.cancelAndJoin()
                    caller?.cancelAndJoin()
                }
            }
        }
        assertRetainedAndRetry(view, original)
    }

    @Test(timeout = 30_000L)
    fun cancelledCallerCanCompleteAnAvailableRealMetadataRelease() = runBlocking {
        val view = library.openView()
        val callerEntered = CompletableDeferred<Unit>()
        val cleanupFinished = CompletableDeferred<Unit>()
        val caller = launch(Dispatchers.IO) {
            try {
                callerEntered.complete(Unit)
                awaitCancellation()
            } finally {
                library.closePlaylistView(view.viewId, RETRY_MS)
                cleanupFinished.complete(Unit)
            }
        }
        try {
            withTimeout(WATCHDOG_MS) { callerEntered.await() }
            caller.cancel()
            withTimeout(WATCHDOG_MS) {
                caller.join()
                cleanupFinished.await()
            }
            assertTrue(caller.isCancelled)
            assertTrue(caller.isCompleted)
            assertFalse(caller.children.any())
        } finally {
            withContext(NonCancellable) { withTimeout(WATCHDOG_MS) { caller.cancelAndJoin() } }
        }
        assertNull(database.libraryDao().view(view.viewId))
        expectExpired(view)
        library.closePlaylistView(view.viewId, RETRY_MS)
        assertEquals(0, database.libraryDao().views().size)
        integrity()
    }

    private suspend fun CoroutineScope.verifyHelperOwnership(closeTimeoutMs: Long) {
        val leases = PlaylistPreviewLeases("native-helper-preview", library,
            closeLibraryView = { library.closePlaylistView(it, CLOSE_MS) }, closeTimeoutMs = closeTimeoutMs)
        val view = leases.openLibrary(LibraryQuery(), LibrarySort())
        val original = checkNotNull(database.libraryDao().view(view.viewId))
        val gate = TransactionGate()
        val holder = launch(Dispatchers.IO) {
            library.libraryTransaction { gate.hold(database, view.viewId) }
        }
        var closer: Deferred<Unit>? = null
        try {
            withTimeout(WATCHDOG_MS) { gate.entered.await() }
            val waiting = async(Dispatchers.IO) { expectBusy { leases.closeAll() } }
            closer = waiting
            withTimeout(WATCHDOG_MS) { waiting.await() }
            assertTrue(waiting.isCompleted)
            assertFalse(waiting.isCancelled)
            assertFalse(waiting.children.any())
            assertFalse(leases.isReleased)
            expectBusy { leases.openLibrary(LibraryQuery(), LibrarySort()) }
            assertFalse(holder.isCompleted)
            assertFalse(gate.release.isCompleted)
            gate.inspect.complete(Unit)
            assertEquals(original, withTimeout(WATCHDOG_MS) { gate.observed.await() })
            assertEquals(listOf(original), withTimeout(WATCHDOG_MS) { gate.observedViews.await() })
        } finally {
            gate.inspect.complete(Unit)
            gate.release.complete(Unit)
            withContext(NonCancellable) {
                withTimeout(WATCHDOG_MS) {
                    holder.cancelAndJoin()
                    closer?.cancelAndJoin()
                }
            }
        }
        withTimeout(WATCHDOG_MS) {
            database.withPreservedTransaction {
                assertEquals(listOf(original), database.libraryDao().views())
            }
            library.validatePlaylistView(view)
            assertEquals(view, library.page(view).view)
        }
        assertFalse(leases.isReleased)
        expectBusy { leases.openLibrary(LibraryQuery(), LibrarySort()) }
        leases.closeAll()
        assertTrue(leases.isReleased)
        assertNull(database.libraryDao().view(view.viewId))
        expectExpired(view)
        leases.closeAll()
        assertEquals(0, database.libraryDao().views().size)
        integrity()
    }

    private suspend fun CoroutineScope.verifyDeadlineBeforeRelease(
        view: LibraryViewToken,
        original: LibraryViewEntity,
        gate: TransactionGate,
        holder: Job,
    ) {
        var closer: Deferred<TimeoutCancellationException>? = null
        try {
            withTimeout(WATCHDOG_MS) { gate.entered.await() }
            val waiting = async(Dispatchers.IO) { expectCloseDeadline(view.viewId) }
            closer = waiting
            // This watchdog is outside the captured closer call; its timeout cannot satisfy the test.
            withTimeout(WATCHDOG_MS) { waiting.await() }
            assertTrue(waiting.isCompleted)
            assertFalse(waiting.isCancelled)
            assertFalse(waiting.children.any())
            assertFalse(holder.isCompleted)
            assertFalse(gate.release.isCompleted)
            gate.inspect.complete(Unit)
            assertEquals(original, withTimeout(WATCHDOG_MS) { gate.observed.await() })
        } finally {
            gate.inspect.complete(Unit)
            gate.release.complete(Unit)
            withContext(NonCancellable) {
                withTimeout(WATCHDOG_MS) {
                    holder.cancelAndJoin()
                    closer?.cancelAndJoin()
                }
            }
        }
        assertRetainedAndRetry(view, original)
    }

    private suspend fun expectCloseDeadline(viewId: String): TimeoutCancellationException {
        try { library.closePlaylistView(viewId, CLOSE_MS) }
        catch (error: TimeoutCancellationException) { return error }
        throw AssertionError("Expected the closer's own deadline before releasing the blocker")
    }

    private suspend fun assertRetainedAndRetry(view: LibraryViewToken, original: LibraryViewEntity) {
        // Room serializes this barrier after queued work. A detached old delete must not consume the token.
        withTimeout(WATCHDOG_MS) {
            database.withPreservedTransaction {
                assertEquals(original, database.libraryDao().view(view.viewId))
            }
            library.validatePlaylistView(view)
            assertEquals(view, library.page(view).view)
            assertEquals(original, database.libraryDao().view(view.viewId))
        }
        library.closePlaylistView(view.viewId, RETRY_MS)
        assertNull(database.libraryDao().view(view.viewId))
        expectExpired(view)
        library.closePlaylistView(view.viewId, RETRY_MS)
        assertEquals(0, database.libraryDao().views().size)
        integrity()
    }

    private suspend fun expectExpired(view: LibraryViewToken) {
        try { library.validatePlaylistView(view) }
        catch (error: LibraryException) {
            assertEquals(LibraryIssue.VIEW_EXPIRED, error.issue)
            return
        }
        throw AssertionError("Closed token remained usable")
    }

    private suspend fun expectBusy(action: suspend () -> Unit) {
        try { action() }
        catch (error: PlaylistException) {
            assertEquals(PlaylistIssue.BUSY, error.issue)
            return
        }
        throw AssertionError("Expected BUSY with the original lease still retained")
    }

    private suspend fun integrity() = database.withTestWriterTransaction {
        query("PRAGMA foreign_key_check") { assertFalse(it.step()) }
        query("PRAGMA integrity_check") {
            assertTrue(it.step())
            assertEquals("ok", it.getText(0))
        }
    }

    private class TransactionGate {
        val entered = CompletableDeferred<Unit>()
        val inspect = CompletableDeferred<Unit>()
        val observed = CompletableDeferred<LibraryViewEntity?>()
        val observedViews = CompletableDeferred<List<LibraryViewEntity>>()
        val release = CompletableDeferred<Unit>()

        suspend fun hold(database: MeloXDatabase, viewId: String) {
            check(database.hasCurrentWriterTransaction())
            entered.complete(Unit)
            inspect.await()
            observed.complete(database.libraryDao().view(viewId))
            observedViews.complete(database.libraryDao().views())
            release.await()
        }
    }

    private companion object {
        const val CLOSE_MS = 100L
        const val RETRY_MS = 5_000L
        const val WATCHDOG_MS = 10_000L
    }
}
