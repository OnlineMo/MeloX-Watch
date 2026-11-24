package io.github.onlinemo.meloxwatch.data.library

import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutation
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutationRequest
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistSnapshot
import io.github.onlinemo.meloxwatch.data.scan.ScanMode
import io.github.onlinemo.meloxwatch.data.scan.ScanPhase
import io.github.onlinemo.meloxwatch.data.scan.ScanToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Real Room fence races; the extra playlist writer is an external-write fault, never application wiring. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomFolderPlaylistMaintenanceConcurrencyTest : FolderPlaylistMaintenanceRoomFixture() {
    @Test(timeout = 45_000L)
    fun aDelayedCompletionEventCannotSynchronizeAfterANewerScanHasStarted() = runBlocking {
        val folder = createFolder()
        val retained = members(folder)
        val oldEvent = checkNotNull(library.latestScan(source.spec.sourceId))
        val current = checkNotNull(library.source(source.spec.sourceId))
        val newer = library.beginScan(current.spec.sourceId, current.authEpoch, library.rules().revision, ScanMode.FULL)
        val recorded = recordingIndex()
        val maintenance = maintainer(recorded)
        val result = maintenance.afterScan(oldEvent)
        assertEquals(PlaylistIssue.SOURCE_CHANGED, result.issue)
        assertEquals(0L, result.examined)
        assertEquals(0, recorded.opened)
        assertEquals(1L, maintenance.resume().deferred)
        assertEquals(folder, playlists.snapshot(folder.playlistId))
        assertEquals(retained, members(folder))
        assertEquals(0L, synchronizedReceipts())
        library.setScanState(newer, ScanPhase.CANCELLED)
        integrity()
    }

    @Test(timeout = 45_000L)
    fun aNewScanStartedAfterTheRealInputReadIsRejectedAtTheFinalScanFence() = runBlocking {
        val folder = createFolder()
        val retained = members(folder)
        val candidates = originalCandidates() + audio("new-file", "New2.mp3")
        val event = refresh(candidates)
        val recorded = recordingIndex()
        var next: ScanToken? = null
        recorded.onPage = { _, _ ->
            if (next == null) {
                val current = checkNotNull(library.source(source.spec.sourceId))
                next = library.beginScan(current.spec.sourceId, current.authEpoch, library.rules().revision, ScanMode.FULL)
            }
        }
        val maintenance = maintainer(recorded)
        val rejected = maintenance.afterScan(event)
        assertNotNull(next)
        assertEquals(1, recorded.read)
        assertEquals(PlaylistIssue.SOURCE_CHANGED, rejected.issue)
        assertEquals(1L, rejected.deferred)
        assertEquals(folder, playlists.snapshot(folder.playlistId))
        assertEquals(retained, members(folder))
        assertEquals(0L, synchronizedReceipts())
        assertEquals(0, database.libraryDao().views().size)
        recorded.onPage = { _, _ -> }
        val nextEvent = refresh(candidates)
        assertEquals(next, nextEvent.token)
        assertEquals(1L, maintenance.afterScan(nextEvent).synchronized)
        assertEquals(retained.map { it.memberId },
            members(checkNotNull(playlists.snapshot(folder.playlistId))).take(2).map { it.memberId })
        assertEquals(track("new-file"), members(checkNotNull(playlists.snapshot(folder.playlistId))).last().reference.trackKey)
        integrity()
    }

    @Test(timeout = 45_000L)
    fun aConcurrentDurableManualOrderWinsTheRevisionFenceAndTheNextResumePreservesThatOrder() = runBlocking {
        val folder = createFolder()
        val oldMembers = members(folder)
        val event = refresh(originalCandidates() + audio("new-file", "New2.mp3"))
        val competing = repository()
        val recorded = recordingIndex()
        var moved: PlaylistSnapshot? = null
        recorded.onPage = { _, _ ->
            if (moved == null) moved = checkNotNull(competing.apply(PlaylistMutationRequest("concurrent-order", folder.playlistId,
                folder.revision, PlaylistMutation.Move(oldMembers.last().memberId, 0L))).snapshot)
        }
        val maintenance = maintainer(recorded)
        val rejected = maintenance.afterScan(event)
        val acceptedOrder = checkNotNull(moved)
        assertEquals(PlaylistIssue.CONFLICT, rejected.issue)
        assertEquals(1L, rejected.deferred)
        assertEquals(acceptedOrder, playlists.snapshot(folder.playlistId))
        assertEquals(oldMembers.reversed().map { it.memberId }, members(acceptedOrder).map { it.memberId })
        assertEquals(0L, synchronizedReceipts())
        recorded.onPage = { _, _ -> }
        assertEquals(1L, maintenance.resume().synchronized)
        val synchronized = members(checkNotNull(playlists.snapshot(folder.playlistId)))
        assertEquals(oldMembers.reversed().map { it.memberId }, synchronized.take(2).map { it.memberId })
        assertEquals(track("new-file"), synchronized.last().reference.trackKey)
        assertEquals(1L, synchronizedReceipts())
        integrity()
    }

    @Test(timeout = 45_000L)
    fun aConfirmedBindingChangeDuringCopyCannotBeOverwrittenByTheOldFolderSynchronization() = runBlocking {
        val album = listOf(directory("album", "root-object", "Album"), audio("album-song", "Album2.mp3", "album"))
        refresh(originalCandidates() + album)
        val folder = createFolder("rebound-folder", binding(subtree = false))
        val event = refresh(originalCandidates() + album + audio("new-root-file", "Root2.mp3"))
        val competing = repository()
        val recorded = recordingIndex()
        var rebound: PlaylistSnapshot? = null
        val newBinding = binding("album")
        recorded.onPage = { _, _ ->
            if (rebound == null) rebound = rebindFolder(folder, newBinding, competing)
        }
        val maintenance = maintainer(recorded)
        val rejected = maintenance.afterScan(event)
        val acceptedBinding = checkNotNull(rebound)
        assertEquals(PlaylistIssue.CONFLICT, rejected.issue)
        assertEquals(1L, rejected.deferred)
        assertEquals(acceptedBinding, playlists.snapshot(folder.playlistId))
        assertEquals(newBinding, acceptedBinding.folderBinding)
        assertEquals(listOf(track("album-song")), members(acceptedBinding).map { it.reference.trackKey })
        assertEquals(0L, synchronizedReceipts())
        recorded.onPage = { _, _ -> }
        assertEquals(1L, maintenance.resume().synchronized)
        assertEquals(acceptedBinding, playlists.snapshot(folder.playlistId))
        assertEquals(listOf(track("album-song")), members(acceptedBinding).map { it.reference.trackKey })
        assertEquals(1L, synchronizedReceipts())
        integrity()
    }

    @Test(timeout = 45_000L)
    fun permissionRevocationAfterTheRealIndexReadCannotPublishTheNewMembers() = runBlocking {
        val folder = createFolder()
        val retained = members(folder)
        val event = refresh(originalCandidates() + audio("new-file", "New2.mp3"))
        val recorded = recordingIndex()
        var revoked = false
        recorded.onPage = { _, _ ->
            if (!revoked) {
                revoked = true
                val current = checkNotNull(library.source(source.spec.sourceId))
                library.setSourceIssue(current.spec.sourceId, current.authEpoch, SourceReadIssue.PERMISSION_REVOKED)
            }
        }
        val maintenance = maintainer(recorded)
        val result = maintenance.afterScan(event)
        assertTrue(revoked)
        assertTrue(result.issue in setOf(PlaylistIssue.SOURCE_CHANGED, PlaylistIssue.SOURCE_UNAVAILABLE))
        assertEquals(1L, result.deferred)
        assertEquals(folder, playlists.snapshot(folder.playlistId))
        assertEquals(retained, members(folder))
        assertEquals(0L, synchronizedReceipts())
        assertEquals(0, database.libraryDao().views().size)
        recorded.onPage = { _, _ -> }
        assertEquals(1L, maintenance.resume().deferred)
        assertEquals(1, recorded.opened)
        integrity()
    }

    @Test(timeout = 45_000L)
    fun cancellationDuringRealCopyPropagatesAndAllowsOnlyOneWorkerUntilItsCleanupFinishes() = runBlocking {
        val folder = createFolder()
        val retained = members(folder)
        val event = refresh(originalCandidates() + audio("new-file", "New2.mp3"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val recorded = recordingIndex()
        recorded.onPage = { _, _ -> entered.complete(Unit); release.await() }
        val maintenance = maintainer(recorded)
        var cancellation: CancellationException? = null
        var completed = false
        val worker = launch {
            try { maintenance.afterScan(event); completed = true }
            catch (error: CancellationException) { cancellation = error; throw error }
        }
        try {
            withTimeout(10_000L) { entered.await() }
            expectIssue(PlaylistIssue.BUSY) { maintenance.resume() }
            expectIssue(PlaylistIssue.BUSY) { maintenance.close() }
            assertEquals(1, recorded.opened)
            assertEquals(1, database.libraryDao().views().size)
            withTimeout(10_000L) { worker.cancelAndJoin() }
        } finally {
            release.complete(Unit)
            withTimeout(10_000L) { worker.cancelAndJoin() }
        }
        assertNotNull(cancellation)
        assertFalse(completed)
        assertEquals(folder, playlists.snapshot(folder.playlistId))
        assertEquals(retained, members(folder))
        assertEquals(0L, synchronizedReceipts())
        assertEquals(0, database.libraryDao().views().size)
        recorded.onPage = { _, _ -> }
        val resumed = maintenance.resume()
        assertEquals(1L, resumed.synchronized)
        assertNull(resumed.issue)
        assertEquals(2, recorded.opened)
        assertEquals(1L, synchronizedReceipts())
        maintenance.close()
        integrity()
    }
}
