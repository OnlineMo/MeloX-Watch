package io.github.onlinemo.meloxwatch.data.library

import io.github.onlinemo.meloxwatch.data.database.withTestWriterTransaction
import io.github.onlinemo.meloxwatch.data.playlists.LocalPlaylistRepository
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCatalogView
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistException
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistViewOwner
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistViewToken
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Real Room tokens with delegated timing faults; tail gates do not execute actual reclamation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PlaylistPreviewLeasesRoomTest : PlaylistRecoveryRoomFixture() {
    @Test(timeout = 30_000L)
    fun cancelledLibraryAcquisitionRecordsTheActualRoomTokenBeforePropagatingCancellation() = runBlocking {
        val entered = CompletableDeferred<String>()
        val release = CompletableDeferred<Unit>()
        val real = library
        val gated = object : LocalLibraryRepository by real {
            override suspend fun openView(query: LibraryQuery, sort: LibrarySort, owner: LibraryViewOwner): LibraryViewToken {
                val token = real.openView(query, sort, owner)
                entered.complete(token.viewId)
                release.await()
                return token
            }
        }
        val leases = PlaylistPreviewLeases("cancelled-index-preview", gated,
            closeLibraryView = { library.closePlaylistView(it, 5_000L) })
        var cancellation: CancellationException? = null
        var cleanupIssue: LibraryIssue? = null
        val job = launch {
            try { leases.openLibrary(LibraryQuery(readableOnly = true), LibrarySort()) }
            catch (error: CancellationException) { cancellation = error; throw error }
            finally {
                try { leases.closeAll() } catch (error: LibraryException) { cleanupIssue = error.issue }
            }
        }
        val tokenId: String
        try {
            tokenId = withTimeout(10_000L) { entered.await() }
            rejectLibraryClose()
            job.cancel()
            assertFalse(job.isCompleted)
            assertEquals(listOf(tokenId), database.libraryDao().views().map { it.viewId })
        } finally {
            release.complete(Unit)
            withTimeout(10_000L) { job.cancelAndJoin() }
        }
        assertTrue(cancellation != null)
        assertEquals(LibraryIssue.INVALID_INPUT, cleanupIssue)
        assertFalse(leases.isReleased)
        expectIssue(PlaylistIssue.BUSY) { leases.openLibrary(LibraryQuery(), LibrarySort()) }
        allowLibraryClose()
        leases.closeAll()
        assertTrue(leases.isReleased)
        assertEquals(0, database.libraryDao().views().size)
        integrity()
    }

    @Test(timeout = 30_000L)
    fun cancelledOverlapAcquisitionRetainsItsActualPlaylistTokenAndReleasesTheIndependentIndexLease() = runBlocking {
        val target = target("cancelled-overlap-list")
        val base = target.view.snapshot
        playlists.closeView(target.view.viewId)
        val entered = CompletableDeferred<String>()
        val release = CompletableDeferred<Unit>()
        val real = playlists
        val gated = object : LocalPlaylistRepository by real {
            override suspend fun openView(playlistId: String, owner: PlaylistViewOwner, fromCatalog: PlaylistCatalogView?): PlaylistViewToken {
                val token = real.openView(playlistId, owner, fromCatalog)
                entered.complete(token.viewId)
                release.await()
                return token
            }
        }
        val leases = PlaylistPreviewLeases("cancelled-overlap-preview", library,
            closeLibraryView = { library.closePlaylistView(it, 5_000L) }, playlists = gated)
        leases.openLibrary(LibraryQuery(readableOnly = true), LibrarySort())
        var blockCalls = 0
        var cancellation: CancellationException? = null
        var cleanupIssue: PlaylistIssue? = null
        val job = launch {
            try { leases.useOverlap(base.playlistId) { blockCalls += 1 } }
            catch (error: CancellationException) { cancellation = error; throw error }
            finally {
                try { leases.closeAll() } catch (error: PlaylistException) { cleanupIssue = error.issue }
            }
        }
        val tokenId: String
        try {
            tokenId = withTimeout(10_000L) { entered.await() }
            execute("""CREATE TRIGGER reject_helper_overlap_close BEFORE DELETE ON playlist_views BEGIN
                SELECT RAISE(ABORT, 'fixture overlap close failure'); END""")
            job.cancel()
            assertFalse(job.isCompleted)
            assertEquals(1L, scalar("SELECT COUNT(*) FROM playlist_views"))
        } finally {
            release.complete(Unit)
            withTimeout(10_000L) { job.cancelAndJoin() }
        }
        assertTrue(cancellation != null)
        assertEquals(0, blockCalls)
        assertEquals(PlaylistIssue.RECOVERY_REQUIRED, cleanupIssue)
        assertEquals(0, database.libraryDao().views().size)
        assertEquals(1L, scalar("SELECT COUNT(*) FROM playlist_views WHERE viewId = '$tokenId'"))
        assertFalse(leases.isReleased)
        expectIssue(PlaylistIssue.BUSY) { leases.openLibrary(LibraryQuery(), LibrarySort()) }
        execute("DROP TRIGGER reject_helper_overlap_close")
        leases.closeAll()
        assertTrue(leases.isReleased)
        assertEquals(0L, scalar("SELECT COUNT(*) FROM playlist_views"))
        assertEquals(base, playlists.snapshot(base.playlistId))
        integrity()
    }

    @Test(timeout = 30_000L)
    fun closeDeadlineCancelsTheCooperativeWaitAndRetriesTheSameActualRoomToken() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val blocked = AtomicBoolean(true)
        val closedIds = mutableListOf<String>()
        val real = library
        val gated = object : LocalLibraryRepository by real {
            override suspend fun closeView(viewId: String) {
                closedIds += viewId
                if (blocked.get()) {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                }
                real.closeView(viewId)
            }
        }
        val leases = PlaylistPreviewLeases("deadline-index-preview", gated,
            closeLibraryView = gated::closeView, closeTimeoutMs = 2_000L)
        val token = leases.openLibrary(LibraryQuery(readableOnly = true), LibrarySort())
        expectIssue(PlaylistIssue.BUSY) { leases.closeAll() }
        assertTrue(entered.isCompleted)
        assertTrue(cancelled.isCompleted)
        assertFalse(leases.isReleased)
        assertEquals(listOf(token.viewId), database.libraryDao().views().map { it.viewId })
        expectIssue(PlaylistIssue.BUSY) { leases.openLibrary(LibraryQuery(), LibrarySort()) }
        blocked.set(false)
        leases.closeAll()
        assertEquals(listOf(token.viewId, token.viewId), closedIds)
        assertTrue(leases.isReleased)
        assertEquals(0, database.libraryDao().views().size)
        integrity()
    }

    @Test(timeout = 30_000L)
    fun aDeadlineAfterRealRoomReleaseKeepsCleanupOwnershipForAnIdempotentRetry() = runBlocking {
        val tail = AtomicBoolean(true)
        val tailCancelled = CompletableDeferred<Unit>()
        val closedIds = mutableListOf<String>()
        val real = library
        val gated = object : LocalLibraryRepository by real {
            override suspend fun closeView(viewId: String) {
                closedIds += viewId
                real.closeView(viewId)
                if (tail.getAndSet(false)) {
                    try { awaitCancellation() } finally { tailCancelled.complete(Unit) }
                }
            }
        }
        val leases = PlaylistPreviewLeases("deadline-after-release", gated,
            closeLibraryView = gated::closeView, closeTimeoutMs = 2_000L)
        val token = leases.openLibrary(LibraryQuery(readableOnly = true), LibrarySort())
        expectIssue(PlaylistIssue.BUSY) { leases.closeAll() }
        assertTrue(tailCancelled.isCompleted)
        assertEquals(0, database.libraryDao().views().size)
        assertFalse(leases.isReleased)
        expectIssue(PlaylistIssue.BUSY) { leases.openLibrary(LibraryQuery(), LibrarySort()) }
        leases.closeAll()
        assertEquals(listOf(token.viewId, token.viewId), closedIds)
        assertTrue(leases.isReleased)
        integrity()
    }

    @Test(timeout = 30_000L)
    fun deadlineWaitsForTheProtectedReleaseToFinishAndDoesNotDetachItsPhysicalWork() = runBlocking {
        val entered = CompletableDeferred<Job>()
        val release = CompletableDeferred<Unit>()
        val protectedFinished = CompletableDeferred<Unit>()
        val protectOnce = AtomicBoolean(true)
        val closedIds = mutableListOf<String>()
        val real = library
        val gated = object : LocalLibraryRepository by real {
            override suspend fun closeView(viewId: String) {
                closedIds += viewId
                if (protectOnce.getAndSet(false)) {
                    val deadlineJob = checkNotNull(currentCoroutineContext()[Job])
                    withContext(NonCancellable) {
                        entered.complete(deadlineJob)
                        try { release.await() } finally { protectedFinished.complete(Unit) }
                    }
                }
                real.closeView(viewId)
            }
        }
        val leases = PlaylistPreviewLeases("protected-release-preview", gated,
            closeLibraryView = gated::closeView, closeTimeoutMs = 2_000L)
        val token = leases.openLibrary(LibraryQuery(readableOnly = true), LibrarySort())
        val job = launch { expectIssue(PlaylistIssue.BUSY) { leases.closeAll() } }
        try {
            val deadlineJob = withTimeout(10_000L) { entered.await() }
            withTimeout(10_000L) { while (!deadlineJob.isCancelled) delay(10L) }
            assertFalse(job.isCompleted)
            assertFalse(protectedFinished.isCompleted)
            assertEquals(listOf(token.viewId), database.libraryDao().views().map { it.viewId })
            assertEquals(listOf(token.viewId), closedIds)
        } finally {
            release.complete(Unit)
            withTimeout(10_000L) { job.join() }
        }
        assertTrue(protectedFinished.isCompleted)
        assertFalse(leases.isReleased)
        assertEquals(0, database.libraryDao().views().size)
        leases.closeAll()
        assertEquals(listOf(token.viewId, token.viewId), closedIds)
        assertTrue(leases.isReleased)
        integrity()
    }

    private suspend fun rejectLibraryClose() = execute("""CREATE TRIGGER reject_helper_library_close BEFORE DELETE ON library_views BEGIN
        SELECT RAISE(ABORT, 'fixture library close failure'); END""")

    private suspend fun allowLibraryClose() = execute("DROP TRIGGER reject_helper_library_close")

    private suspend fun execute(sql: String) = database.withTestWriterTransaction {
        this.execute(sql)
    }
}
