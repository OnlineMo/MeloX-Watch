package io.github.onlinemo.meloxwatch.data.library

import io.github.onlinemo.meloxwatch.data.playlists.LocalPlaylistRepository
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCommit
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistException
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistLimits
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutation
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutationRequest
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistPageSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Native SQLite failures and real acceptance, with explicit delegated/clock faults where needed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomFolderPlaylistMaintenanceRecoveryTest : FolderPlaylistMaintenanceRoomFixture() {
    @Test(timeout = 45_000L)
    fun anAcceptedSynchronizationSurvivesRealLeaseCloseFailureAndRetriesTheSameOwnerBeforeAnyNewAcquisition() = runBlocking {
        val folder = createFolder()
        val event = refresh(originalCandidates() + audio("new-file", "New2.mp3"))
        val recorded = recordingIndex()
        val maintenance = maintainer(recorded)
        execute("""CREATE TRIGGER reject_maintenance_close BEFORE DELETE ON library_views BEGIN
            SELECT RAISE(ABORT, 'fixture maintenance close failure'); END""")
        val accepted = maintenance.afterScan(event)
        assertEquals(1L, accepted.synchronized)
        assertEquals(PlaylistIssue.INVALID_INPUT, accepted.issue)
        assertTrue(accepted.cleanupPending)
        val updated = checkNotNull(playlists.snapshot(folder.playlistId))
        assertEquals(3L, updated.memberCount)
        assertEquals(track("new-file"), members(updated).last().reference.trackKey)
        assertEquals(1L, synchronizedReceipts())
        val retainedViewId = database.libraryDao().views().single().viewId
        val pending = maintenance.resume()
        assertEquals(0L, pending.examined)
        assertTrue(pending.cleanupPending)
        assertEquals(PlaylistIssue.INVALID_INPUT, pending.issue)
        assertEquals(listOf(retainedViewId), database.libraryDao().views().map { it.viewId })
        assertEquals(1, recorded.opened)
        assertEquals(1, recorded.read)
        assertEquals(updated, playlists.snapshot(folder.playlistId))
        expectIssue(PlaylistIssue.INVALID_INPUT) { maintenance.close() }
        assertEquals(listOf(retainedViewId), database.libraryDao().views().map { it.viewId })
        execute("DROP TRIGGER reject_maintenance_close")
        val recovered = maintenance.resume()
        assertEquals(1L, recovered.alreadySynchronized)
        assertFalse(recovered.cleanupPending)
        assertNull(recovered.issue)
        assertEquals(1, recorded.opened)
        assertEquals(1, recorded.read)
        assertEquals(0, database.libraryDao().views().size)
        assertEquals(1L, synchronizedReceipts())
        assertEquals(updated, playlists.snapshot(folder.playlistId))
        maintenance.close()
        integrity()
    }

    @Test(timeout = 45_000L)
    fun aDelegatedTailFailureAfterRealRoomAcceptanceUsesItsReceiptAndNeverCopiesTheSourceAgain() = runBlocking {
        val folder = createFolder()
        val event = refresh(originalCandidates() + audio("new-file", "New2.mp3"))
        val actual = playlists
        var calls = 0
        val failingTail = object : LocalPlaylistRepository by actual {
            override suspend fun apply(request: PlaylistMutationRequest, source: PlaylistPageSource?): PlaylistCommit {
                val result = actual.apply(request, source)
                if (request.mutation is PlaylistMutation.SynchronizeFolder) {
                    calls++
                    // This injects an ambiguous caller result after a real commit, not physical I/O timing.
                    if (calls == 1) throw PlaylistException(PlaylistIssue.STORAGE_UNAVAILABLE)
                }
                return result
            }
        }
        val recorded = recordingIndex()
        val maintenance = maintainer(recorded, failingTail)
        val accepted = maintenance.afterScan(event)
        assertEquals(1L, accepted.synchronized)
        assertEquals(PlaylistIssue.STORAGE_UNAVAILABLE, accepted.issue)
        assertFalse(accepted.cleanupPending)
        val updated = checkNotNull(playlists.snapshot(folder.playlistId))
        assertEquals(3L, updated.memberCount)
        assertEquals(1L, synchronizedReceipts())
        val duplicate = maintenance.afterScan(event)
        assertEquals(1L, duplicate.alreadySynchronized)
        assertNull(duplicate.issue)
        assertEquals(1, calls)
        assertEquals(1, recorded.opened)
        assertEquals(1, recorded.read)
        assertEquals(updated, playlists.snapshot(folder.playlistId))
        assertEquals(0, database.libraryDao().views().size)
        integrity()
    }

    @Test(timeout = 120_000L)
    fun aFiniteIndexedHeadSweepCrossesHundredWithoutAnExpiringCatalogueLeaseOrChasingNewHeads() = runBlocking {
        repeat(101) {
            if (it > 0 && it % 25 == 0) {
                val gap = checkNotNull(playlists.apply(PlaylistMutationRequest("create-gap-$it", "ordinary-gap-$it", 0L,
                    PlaylistMutation.Create("Ordinary gap $it"))).snapshot)
                if (it % 50 == 0) playlists.apply(PlaylistMutationRequest("delete-gap-$it", gap.playlistId, gap.revision,
                    PlaylistMutation.Delete(confirmed = true)))
            }
            createFolder("sequence-${it.toString().padStart(3, '0')}")
        }
        val recorded = recordingIndex()
        var createdDuringSweep = false
        recorded.onOpen = {
            if (!createdDuringSweep) {
                createdDuringSweep = true
                createFolder("created-after-sweep-start")
            }
        }
        // Simulated elapsed time spans many catalogue lease periods while each real index lease is
        // touched/closed promptly. It proves ownership semantics, not device duration/performance.
        recorded.onPage = { _, _ -> elapsed += LibraryLimits.VIEW_LEASE_MS / 3L }
        val maintenance = maintainer(recorded)
        val first = maintenance.resume()
        assertTrue(createdDuringSweep)
        assertEquals(101L, first.examined)
        assertEquals(101L, first.synchronized)
        assertEquals(0L, first.deferred)
        assertNull(first.issue)
        assertEquals(101, recorded.opened)
        assertEquals(101, recorded.read)
        assertTrue(recorded.largestLimit in 1..PlaylistLimits.PAGE_SIZE)
        assertEquals(101L, synchronizedReceipts())
        assertEquals(0L, scalar("SELECT COUNT(*) FROM playlist_views WHERE kind = 'CATALOG'"))
        assertEquals(0, database.libraryDao().views().size)
        assertEquals(104L, playlists.overview().playlistCount)
        val resumed = maintenance.resume()
        assertEquals(102L, resumed.examined)
        assertEquals(101L, resumed.alreadySynchronized)
        assertEquals(1L, resumed.synchronized)
        assertNull(resumed.issue)
        assertEquals(102, recorded.opened)
        assertEquals(102, recorded.read)
        assertEquals(102L, synchronizedReceipts())
        assertEquals(0L, scalar("SELECT COUNT(*) FROM playlist_views"))
        maintenance.close()
        integrity()
    }

    @Test(timeout = 60_000L)
    fun anotherSourcesCompletionResumesEligibleWorkCancelledByAnEarlierCoalescedEvent() = runBlocking {
        val first = createFolder("first-source-folder")
        val firstEvent = refresh(originalCandidates() + audio("first-new", "New2.mp3"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val recorded = recordingIndex()
        recorded.onPage = { _, _ -> entered.complete(Unit); release.await() }
        val maintenance = maintainer(recorded)
        val worker = launch { maintenance.afterScan(firstEvent) }
        try {
            withTimeout(10_000L) { entered.await() }
            withTimeout(10_000L) { worker.cancelAndJoin() }
        } finally {
            release.complete(Unit)
            withTimeout(10_000L) { worker.cancelAndJoin() }
        }
        assertEquals(first, playlists.snapshot(first.playlistId))
        assertEquals(0L, synchronizedReceipts())
        assertEquals(0, database.libraryDao().views().size)
        val other = secondSource()
        val otherEvent = refresh(listOf(root(other), audio("other-song", "Other2.mp3", "other-root", other)), owner = other)
        val second = createFolder("second-source-folder", binding("other-root", other))
        recorded.onPage = { _, _ -> }
        val recovered = maintenance.afterScan(otherEvent)
        assertEquals(2L, recovered.examined)
        assertEquals(2L, recovered.synchronized)
        assertNull(recovered.issue)
        assertEquals(3L, checkNotNull(playlists.snapshot(first.playlistId)).memberCount)
        assertEquals(track("first-new"), members(checkNotNull(playlists.snapshot(first.playlistId))).last().reference.trackKey)
        assertEquals(second, playlists.snapshot(second.playlistId))
        assertEquals(2L, synchronizedReceipts())
        assertEquals(0, database.libraryDao().views().size)
        assertEquals(3, recorded.opened)
        maintenance.close()
        integrity()
    }
}
