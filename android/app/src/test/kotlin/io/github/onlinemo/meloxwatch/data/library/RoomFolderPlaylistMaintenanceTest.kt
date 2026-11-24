package io.github.onlinemo.meloxwatch.data.library

import io.github.onlinemo.meloxwatch.data.database.hasCurrentWriterTransaction
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutation
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutationRequest
import io.github.onlinemo.meloxwatch.data.queue.QueueInputPage
import io.github.onlinemo.meloxwatch.data.queue.QueuePageSource
import io.github.onlinemo.meloxwatch.data.queue.QueueReplacementRequest
import io.github.onlinemo.meloxwatch.data.queue.RoomQueueStore
import io.github.onlinemo.meloxwatch.data.scan.ScanMode
import io.github.onlinemo.meloxwatch.data.scan.ScanPhase
import io.github.onlinemo.meloxwatch.model.FrozenQueueView
import io.github.onlinemo.meloxwatch.model.QueueCheckpoint
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.ui.playlists.KeywordPlaylistCreateRequest
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistFolderChoice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Real Room scans, playlist commits and durable queue invariance; no playback/device acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomFolderPlaylistMaintenanceTest : FolderPlaylistMaintenanceRoomFixture() {
    @Test(timeout = 60_000L)
    fun successfulRefreshPreservesManualOrderAppendsNaturallyAndNeverReplacesTheActiveDurableQueue() = runBlocking {
        val original = createFolder()
        val firstMembers = members(original)
        val moved = checkNotNull(playlists.apply(PlaylistMutationRequest("manual-order", original.playlistId,
            original.revision, PlaylistMutation.Move(firstMembers.last().memberId, 0L))).snapshot)
        val manual = members(moved)
        assertEquals(firstMembers.reversed().map { it.memberId }, manual.map { it.memberId })

        val entries = manual.map { member -> QueueEntry("active-${member.memberId}", checkNotNull(member.reference.trackKey),
            member.ordinal, member.ordinal, member.reference.titleHint, member.reference.durationMs) }
        val queue = RoomQueueStore(database)
        assertNull(queue.initialize())
        val frozen = FrozenQueueView("accepted-folder-before-refresh", moved.revision, entries.size.toLong())
        val installed = queue.replace(QueueReplacementRequest("install-original-folder", null, 0L, frozen),
            QueuePageSource { offset, limit ->
                assertFalse(database.hasCurrentWriterTransaction())
                val end = minOf(entries.size.toLong(), offset + limit).toInt()
                QueueInputPage(frozen, offset, entries.subList(offset.toInt(), end).toList())
            }).snapshot
        val queued = queue.saveCheckpoint(installed.generationId, installed.checkpointRevision,
            QueueCheckpoint(entries.first().queueEntryId, 2_345L))
        val queuedEntries = queue.page(queued.generationId, 0L)

        val added = originalCandidates() + listOf(audio("added-ten", "Added10.mp3"), audio("added-two", "Added2.mp3"))
        val complete = refresh(added)
        val maintenance = RoomFolderPlaylistMaintainer(database, playlists, library)
        val result = maintenance.afterScan(complete)
        assertEquals(1L, result.synchronized)
        assertNull(result.issue)
        assertFalse(result.cleanupPending)
        val grown = checkNotNull(playlists.snapshot(moved.playlistId))
        val grownMembers = members(grown)
        assertEquals(manual.map { it.memberId }, grownMembers.take(2).map { it.memberId })
        assertEquals(listOf(track("added-two"), track("added-ten")), grownMembers.drop(2).map { it.reference.trackKey })
        assertEquals(queued, queue.snapshot())
        assertEquals(queuedEntries, queue.page(queued.generationId, 0L))

        val removedTrack = checkNotNull(manual.first().reference.trackKey)
        val removedObject = if (removedTrack == replacement) "selected-object" else "decoy-object"
        val withoutRemoved = added.filter { it.objectId != removedObject }
        assertEquals(1L, maintenance.afterScan(refresh(withoutRemoved)).synchronized)
        val trimmed = checkNotNull(playlists.snapshot(moved.playlistId))
        val retained = grownMembers.filter { it.reference.trackKey != removedTrack }
        assertEquals(retained.map { it.memberId }, members(trimmed).map { it.memberId })
        assertEquals(retained.map { it.reference.trackKey }, members(trimmed).map { it.reference.trackKey })
        assertEquals(queued, queue.snapshot())
        assertEquals(queuedEntries, queue.page(queued.generationId, 0L))
        maintenance.close()

        reopen()
        assertEquals(trimmed, playlists.snapshot(trimmed.playlistId))
        val reopenedQueue = RoomQueueStore(database)
        assertEquals(queued, reopenedQueue.initialize())
        assertEquals(queuedEntries, reopenedQueue.page(queued.generationId, 0L))
        assertEquals(1L, maintainer().resume().alreadySynchronized)
        assertEquals(2L, synchronizedReceipts())
        integrity()
    }

    @Test(timeout = 60_000L)
    fun sourceAndSubtreeBindingsStaySeparateAndKeywordCreatedOrdinaryPlaylistsKeepTheirSnapshot() = runBlocking {
        val nested = listOf(directory("album", "root-object", "Album"), audio("nested-one", "Nested1.mp3", "album"))
        refresh(originalCandidates() + nested)
        val other = secondSource()
        refresh(listOf(root(other), audio("other-one", "Else.mp3", "other-root", other)), owner = other)
        val direct = createFolder("direct-folder", binding(subtree = false))
        val tree = createFolder("tree-folder", binding())
        val child = createFolder("child-folder", binding("album"))
        val independent = createFolder("other-folder", binding("other-root", other))
        val keyword = RoomKeywordPlaylistController(database, playlists, library, { elapsed })
        val preview = keyword.preview("Same")
        val ordinary = checkNotNull(keyword.create(KeywordPlaylistCreateRequest("create-ordinary-keyword", "ordinary-keyword",
            "Ordinary keyword snapshot", preview.previewId, broadMatchConfirmed = true, emptyMatchConfirmed = false)).snapshot)
        val ordinaryMembers = members(ordinary)
        assertEquals(2, ordinaryMembers.size)

        val complete = refresh(listOf(root(), audio("selected-object", "Same.mp3"), audio("root-new", "Root2.mp3")) +
            nested + audio("nested-two", "Nested2.mp3", "album"))
        val result = maintainer().afterScan(complete)
        assertEquals(4L, result.synchronized)
        assertEquals(0L, result.deferred)
        assertNull(result.issue)
        assertEquals(setOf(replacement, track("root-new")),
            members(checkNotNull(playlists.snapshot(direct.playlistId))).map { it.reference.trackKey }.toSet())
        assertEquals(setOf(replacement, track("root-new"), track("nested-one"), track("nested-two")),
            members(checkNotNull(playlists.snapshot(tree.playlistId))).map { it.reference.trackKey }.toSet())
        assertEquals(listOf(track("nested-one"), track("nested-two")),
            members(checkNotNull(playlists.snapshot(child.playlistId))).map { it.reference.trackKey })
        assertEquals(independent, playlists.snapshot(independent.playlistId))
        assertEquals(listOf(track("other-one", other)), members(independent).map { it.reference.trackKey })
        assertEquals(ordinary, playlists.snapshot(ordinary.playlistId))
        assertEquals(ordinaryMembers, members(ordinary))
        assertEquals(0, database.libraryDao().views().size)
        integrity()
    }

    @Test(timeout = 45_000L)
    fun duplicateCompletionAndRestartUseTheAcceptedReceiptWithoutReadingANewIndexOrUndoingLaterManualOrder() = runBlocking {
        val folder = createFolder()
        val recorded = recordingIndex()
        val maintenance = maintainer(recorded)
        val event = checkNotNull(library.latestScan(source.spec.sourceId))
        val accepted = maintenance.afterScan(event)
        assertEquals(1L, accepted.synchronized)
        assertEquals(folder, playlists.snapshot(folder.playlistId))
        assertEquals(1, recorded.opened)
        assertEquals(1, recorded.read)
        val initialReceiptCount = synchronizedReceipts()
        val oldMembers = members(folder)
        val moved = checkNotNull(playlists.apply(PlaylistMutationRequest("later-order", folder.playlistId, folder.revision,
            PlaylistMutation.Move(oldMembers.last().memberId, 0L))).snapshot)
        assertNotEquals(folder, moved)
        assertEquals(1L, maintenance.afterScan(event).alreadySynchronized)
        assertEquals(1L, maintenance.resume().alreadySynchronized)
        assertEquals(moved, playlists.snapshot(folder.playlistId))
        assertEquals(1, recorded.opened)
        assertEquals(1, recorded.read)
        assertEquals(initialReceiptCount, synchronizedReceipts())
        maintenance.close()

        reopen()
        val nextIndex = recordingIndex()
        val resumed = maintainer(nextIndex).resume()
        assertEquals(1L, resumed.alreadySynchronized)
        assertEquals(0, nextIndex.opened)
        assertEquals(0, nextIndex.read)
        assertEquals(moved, playlists.snapshot(folder.playlistId))
        assertEquals(oldMembers.reversed().map { it.memberId }, members(moved).map { it.memberId })
        assertEquals(initialReceiptCount, synchronizedReceipts())
        integrity()
    }

    @Test(timeout = 60_000L)
    fun failedCancelledPartialIncrementalAndRevokedScansKeepAllPreviouslyAcceptedMembers() = runBlocking {
        val folder = createFolder()
        val retained = members(folder)
        val recorded = recordingIndex()
        val maintenance = maintainer(recorded)
        for (phase in listOf(ScanPhase.FAILED, ScanPhase.CANCELLED, ScanPhase.PAUSED_BACKGROUND, ScanPhase.INTERRUPTED)) {
            val current = checkNotNull(library.source(source.spec.sourceId))
            val token = library.beginScan(current.spec.sourceId, current.authEpoch, library.rules().revision, ScanMode.FULL)
            library.commitSeen(token, listOf(root(), audio("selected-object", "Same.mp3")))
            val event = library.setScanState(token, phase,
                if (phase == ScanPhase.FAILED) SourceReadIssue.METADATA_UNREADABLE else null)
            assertEquals(0L, maintenance.afterScan(event).examined)
            val result = maintenance.resume()
            assertEquals(0L, result.synchronized)
            assertEquals(1L, result.deferred)
            assertEquals(folder, playlists.snapshot(folder.playlistId))
            assertEquals(retained, members(folder))
        }
        val partial = refresh(listOf(root(), audio("selected-object", "Same.mp3")),
            coverage = completeCoverage.copy(enumerationComplete = false))
        assertEquals(0L, maintenance.afterScan(partial).examined)
        assertEquals(1L, maintenance.resume().deferred)
        val incremental = refresh(listOf(root(), audio("selected-object", "Same.mp3")), mode = ScanMode.INCREMENTAL)
        assertEquals(0L, maintenance.afterScan(incremental).examined)
        assertEquals(1L, maintenance.resume().deferred)
        val current = checkNotNull(library.source(source.spec.sourceId))
        library.setSourceIssue(current.spec.sourceId, current.authEpoch, SourceReadIssue.PERMISSION_REVOKED)
        val revoked = maintenance.resume()
        assertEquals(PlaylistIssue.SOURCE_UNAVAILABLE, revoked.issue)
        assertEquals(1L, revoked.deferred)
        assertEquals(folder, playlists.snapshot(folder.playlistId))
        assertEquals(retained, members(folder))
        assertEquals(0, recorded.opened)
        assertEquals(0, recorded.read)
        assertEquals(0L, synchronizedReceipts())
        integrity()
    }

    @Test(timeout = 45_000L)
    fun aCompletedFullScanWithRealMetadataFailureCannotAuthorizeMemberRemoval() = runBlocking {
        val folder = createFolder()
        val retained = members(folder)
        val failed = refresh(listOf(root(), audio("selected-object", "Same.mp3")),
            metadataFailureObjectId = "selected-object")
        assertEquals(ScanPhase.COMPLETED, failed.phase)
        assertTrue(failed.coverage.permitsAbsence)
        assertEquals(1L, failed.counters.failed)
        val recorded = recordingIndex()
        val maintenance = maintainer(recorded)
        assertEquals(0L, maintenance.afterScan(failed).examined)
        val resumed = maintenance.resume()
        assertEquals(PlaylistIssue.INCOMPLETE, resumed.issue)
        assertEquals(1L, resumed.deferred)
        assertEquals(folder, playlists.snapshot(folder.playlistId))
        assertEquals(retained, members(folder))
        assertEquals(0, recorded.opened)
        assertEquals(0L, synchronizedReceipts())
        integrity()
    }

    @Test(timeout = 45_000L)
    fun aMissingBoundDirectoryPreservesMembersWhileAnActuallyEnumeratedEmptyDirectoryCanClearThem() = runBlocking {
        val album = directory("album", "root-object", "Album")
        refresh(originalCandidates() + listOf(album, audio("inside", "Inside.mp3", "album")))
        val folder = createFolder("bound-album", binding("album"))
        val retained = members(folder)
        assertEquals(1, retained.size)
        val recorded = recordingIndex()
        val maintenance = maintainer(recorded)
        val missing = maintenance.afterScan(refresh(originalCandidates()))
        assertEquals(PlaylistIssue.SOURCE_UNAVAILABLE, missing.issue)
        assertEquals(1L, missing.deferred)
        assertEquals(folder, playlists.snapshot(folder.playlistId))
        assertEquals(retained, members(folder))
        assertEquals(0, recorded.opened)
        assertEquals(0L, synchronizedReceipts())
        val previewController = RoomFolderPlaylistController(database, playlists, library, { elapsed })
        expectIssue(PlaylistIssue.SOURCE_UNAVAILABLE) {
            previewController.preview(PlaylistFolderChoice(checkNotNull(folder.folderBinding), "Album"), folder)
        }
        assertEquals(folder, playlists.snapshot(folder.playlistId))
        assertEquals(retained, members(folder))
        assertEquals(0, database.libraryDao().views().size)

        val empty = maintenance.afterScan(refresh(originalCandidates() + album))
        assertEquals(1L, empty.synchronized)
        assertNull(empty.issue)
        val cleared = checkNotNull(playlists.snapshot(folder.playlistId))
        assertEquals(folder.folderBinding, cleared.folderBinding)
        assertEquals(0L, cleared.memberCount)
        assertTrue(members(cleared).isEmpty())
        assertEquals(1L, synchronizedReceipts())
        assertEquals(0, database.libraryDao().views().size)
        integrity()
    }
}
