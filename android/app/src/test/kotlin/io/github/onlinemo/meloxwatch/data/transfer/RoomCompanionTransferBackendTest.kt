package io.github.onlinemo.meloxwatch.data.transfer

import android.content.Context
import io.github.onlinemo.meloxwatch.data.database.withTestWriterSql
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistDao
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistGenerationEntity
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMemberEntity
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutation
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutationRequest
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistReference
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistReferenceIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistViewOwner
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

/** Actual native owners and writer rollback, independent of Android device acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomCompanionTransferBackendTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var fixture: CompanionRoomFixture

    @Before fun open() = runBlocking {
        fixture = CompanionRoomFixture(RuntimeEnvironment.getApplication() as Context, File(temporary.root, "companion-room.db"))
        fixture.initialize()
    }
    @After fun close() { fixture.close() }

    @Test(timeout = 30_000L) fun allPagesUseOneRealFenceAndIncludeTracksBeyondTheFirstHundredAfterAConcurrentScan() = runBlocking {
        val source = seed(205)
        val expected = fixture.tracks().map { it.trackKey.trackId }
        val first = fixture.firstPage(limit = 37)
        assertEquals(205L, first.totalCount)
        val second = fixture.nextPage(first, limit = 37)
        val retried = fixture.nextPage(first, limit = 37)
        assertEquals(trackIds(second), trackIds(retried))
        assertEquals(second.nextCursor, retried.nextCursor)
        fixture.append(source, listOf("later" to LocalAudioMetadata(title = "000 Later", album = "Album", artist = "Artist")))
        val actual = trackIds(first).toMutableList()
        var page = second
        while (true) {
            assertTrue(first.libraryRevision.sameAs(page.libraryRevision))
            assertEquals(first.revision, page.revision)
            assertEquals(actual.size.toLong(), page.firstOrdinal)
            actual += trackIds(page)
            if (page.nextCursor == null) break
            page = fixture.nextPage(page, limit = 37)
        }
        assertEquals(expected, actual)
        assertEquals(206L, fixture.library.overview().totalTracks)
        val output = ByteArrayOutputStream()
        TransferProtocolCodec.writeResponse(output, TransferResponse.Page(CompanionRoomFixture.uuid(), first))
        assertFalse(output.toString(Charsets.ISO_8859_1.name()).contains("content://"))
    }

    @Test(timeout = 30_000L) fun foreignCursorsChangedQueryAndRulesFenceAreRejectedWithoutReplacingTheView() = runBlocking {
        seed(120)
        val first = fixture.firstPage(limit = 20)
        val permit = fixture.permit(TransferCapability.READ_LIBRARY)
        suspend fun read(cursor: String?, search: String = "", limit: Int = 20,
            fence: TransferLibraryRevision = first.libraryRevision) = fixture.backend.readPage(
            TransferRequest.ReadPage(CompanionRoomFixture.uuid(), fixture.scope, first.kind, first.viewId, first.revision,
                fence, cursor, null, search, limit), permit)
        expectTransferIssue(TransferIssue.CONFLICT) { read(CompanionRoomFixture.uuid()) }
        expectTransferIssue(TransferIssue.CONFLICT) { read(first.nextCursor, search = "changed") }
        expectTransferIssue(TransferIssue.CONFLICT) { read(first.nextCursor, limit = 21) }
        expectTransferIssue(TransferIssue.CONFLICT) { read(first.nextCursor,
            fence = TransferLibraryRevision(first.libraryRevision.catalogRevision, first.libraryRevision.rulesRevision + 1L)) }
        assertEquals(20L, fixture.nextPage(first, limit = 20).firstOrdinal)
        fixture.authority.revokeForLocalUi(fixture.peer)
        expectTransferIssue(TransferIssue.NOT_APPROVED) { read(first.nextCursor) }
        fixture.backend.closePeerViews(fixture.peer, fixture.scope.sessionId)
        fixture.newSession(setOf(TransferCapability.READ_LIBRARY))
        expectTransferIssue(TransferIssue.EXPIRED) { fixture.backend.readPage(
            TransferRequest.ReadPage(CompanionRoomFixture.uuid(), fixture.scope, first.kind, first.viewId,
                first.revision, first.libraryRevision, first.nextCursor, null, "", 20), fixture.permit(TransferCapability.READ_LIBRARY)) }
    }

    @Test(timeout = 30_000L) fun playlistWriteOnlyCreatesAndReadsAnEmptyListButCannotReadTracksOrAnotherTarget() = runBlocking {
        fixture.newSession(setOf(TransferCapability.WRITE_PLAYLISTS))
        val receipt = fixture.edit("write-only", 0L, TransferPlaylistEdit.Create("Phone draft"))
        val rows = fixture.rows(TransferCatalogKind.PLAYLISTS, capability = TransferCapability.WRITE_PLAYLISTS)
        val row = rows.filterIsInstance<TransferCatalogRow.Playlist>().single()
        assertEquals(receipt.resultId, row.playlistId)
        assertEquals(0L, row.memberCount)
        assertEquals(0L, fixture.firstPage(TransferCatalogKind.PLAYLIST_MEMBERS, row.playlistId,
            capability = TransferCapability.WRITE_PLAYLISTS).totalCount)
        expectTransferIssue(TransferIssue.PERMISSION_DENIED) { fixture.firstPage(capability = TransferCapability.WRITE_PLAYLISTS) }
        val wrong = TransferScope(TransferFingerprint.digest("other-synthetic-target".toByteArray()),
            fixture.scope.sessionId, fixture.scope.bearer)
        expectTransferIssue(TransferIssue.WRONG_TARGET) { fixture.backend.readPage(
            TransferRequest.ReadPage(CompanionRoomFixture.uuid(), wrong, TransferCatalogKind.PLAYLISTS, null,
                null, null, null, null, "", 100), fixture.permit(TransferCapability.WRITE_PLAYLISTS)) }
        assertEquals(0L, fixture.library.overview().totalTracks)
    }

    @Test(timeout = 30_000L) fun realPlaylistCreateRenameAddMoveRemoveAndDeletePreserveLibraryFavoritesAndOtherLists() = runBlocking {
        seed(3)
        val tracks = fixture.tracks()
        val refs = tracks.map { PlaylistReference(trackKey = it.trackKey, titleHint = it.title) }
        val other = fixture.nativePlaylist("other-list", refs)
        fixture.playlists.apply(PlaylistMutationRequest(CompanionRoomFixture.uuid(), "system:favorites", 0L,
            PlaylistMutation.SetFavorite(refs.first(), true)))
        val favoriteCount = fixture.playlists.overview().favoriteCount
        fixture.newSession(setOf(TransferCapability.WRITE_PLAYLISTS))
        val created = fixture.edit("phone-list", 0L, TransferPlaylistEdit.Create("Initial"))
        val renamed = fixture.edit("phone-list", created.revision, TransferPlaylistEdit.Rename("Renamed"))
        val overview = fixture.library.overview()
        val fence = TransferLibraryRevision(overview.revision, overview.rulesRevision)
        val ids = listOf(tracks[2].trackKey.trackId, tracks[0].trackKey.trackId, tracks[2].trackKey.trackId)
        val added = fixture.edit("phone-list", renamed.revision, TransferPlaylistEdit.Add(ids), fence = fence)
        assertEquals(2L, checkNotNull(fixture.playlists.snapshot("phone-list")).memberCount)
        val members = fixture.rows(TransferCatalogKind.PLAYLIST_MEMBERS, "phone-list",
            capability = TransferCapability.WRITE_PLAYLISTS).filterIsInstance<TransferCatalogRow.Member>()
        assertEquals(ids.take(2), members.map { it.trackId })
        val moved = fixture.edit("phone-list", added.revision, TransferPlaylistEdit.Move(members.first().memberId, 1L))
        val reordered = fixture.rows(TransferCatalogKind.PLAYLIST_MEMBERS, "phone-list",
            capability = TransferCapability.WRITE_PLAYLISTS).filterIsInstance<TransferCatalogRow.Member>()
        assertEquals(ids.take(2).reversed(), reordered.map { it.trackId })
        val removed = fixture.edit("phone-list", moved.revision, TransferPlaylistEdit.Remove(reordered.last().memberId, true))
        expectTransferIssue(TransferIssue.INVALID_INPUT) { fixture.edit("phone-list", removed.revision, TransferPlaylistEdit.Delete(false)) }
        val deleted = fixture.edit("phone-list", removed.revision, TransferPlaylistEdit.Delete(true))
        assertEquals(TransferReceiptKind.PLAYLIST_COMMITTED, deleted.result)
        assertNull(fixture.playlists.snapshot("phone-list"))
        assertEquals(tracks, fixture.tracks())
        assertEquals(favoriteCount, fixture.playlists.overview().favoriteCount)
        assertEquals(other, fixture.playlists.snapshot(other.playlistId))
    }

    @Test(timeout = 30_000L) fun completedExactPlaylistRetryAfterNewGrantAndRestartReturnsTheOriginalReceiptWithoutRestoringOldName() = runBlocking {
        val operation = CompanionRoomFixture.uuid()
        val created = fixture.edit("replay-list", 0L, TransferPlaylistEdit.Create("Original"), operation)
        fixture.edit("replay-list", created.revision, TransferPlaylistEdit.Rename("Later"))
        fixture.reopen()
        val replay = fixture.edit("replay-list", 0L, TransferPlaylistEdit.Create("Original"), operation)
        assertEquals(created.revision, replay.revision)
        assertEquals(created.resultId, replay.resultId)
        assertTrue(created.binding.sameOperation(replay.binding))
        assertEquals("Later", checkNotNull(fixture.playlists.snapshot("replay-list")).name)
        expectTransferIssue(TransferIssue.CONFLICT) { fixture.edit("replay-list", 0L, TransferPlaylistEdit.Create("Changed"), operation) }
        assertEquals(1L, fixture.playlists.overview().playlistCount)
    }

    @Test(timeout = 30_000L) fun failingTheGeneralReceiptWriteRollsBackTheRealNativeHeadAndNativeReceiptThenExactRetryCanCommit() = runBlocking {
        val created = fixture.edit("rollback-list", 0L, TransferPlaylistEdit.Create("Before"))
        val before = checkNotNull(fixture.playlists.snapshot("rollback-list"))
        val operation = CompanionRoomFixture.uuid()
        fixture.database.withTestWriterSql { execute("""
            CREATE TRIGGER controlled_playlist_receipt BEFORE UPDATE ON transfer_room_operations
            WHEN NEW.resultKind IS NOT NULL BEGIN SELECT RAISE(ABORT, 'controlled receipt failure'); END
        """.trimIndent()) }
        try {
            expectTransferIssue(TransferIssue.RECOVERY_REQUIRED) { fixture.edit("rollback-list", created.revision,
                TransferPlaylistEdit.Rename("After"), operation) }
            assertEquals(before, fixture.playlists.snapshot("rollback-list"))
            fixture.database.withTestWriterSql {
                assertEquals(0L, scalarLong("SELECT COUNT(*) FROM playlist_receipts WHERE operationId = ?", operation))
                assertNull(scalarText("SELECT resultKind FROM transfer_room_operations WHERE operationId = ?", operation))
                assertEquals(1L, scalarLong("SELECT COUNT(*) FROM playlist_generations WHERE playlistId = ? AND state = 'PUBLISHED' AND validTo IS NULL", "rollback-list"))
                execute("DROP TRIGGER controlled_playlist_receipt")
            }
            val accepted = fixture.edit("rollback-list", created.revision, TransferPlaylistEdit.Rename("After"), operation)
            assertEquals("After", checkNotNull(fixture.playlists.snapshot("rollback-list")).name)
            assertEquals(accepted.revision, fixture.database.withTestWriterSql {
                scalarLong("SELECT appliedRevision FROM playlist_receipts WHERE operationId = ?", operation) })
        } finally { fixture.database.withTestWriterSql { execute("DROP TRIGGER IF EXISTS controlled_playlist_receipt") } }
    }

    @Test(timeout = 30_000L) fun nativeMemberPagesKeepUnavailableAndUnmatchedPositionsWhileLocalEditingPublishesANewGeneration() = runBlocking {
        seed(201)
        val tracks = fixture.tracks()
        val references = tracks.map { PlaylistReference(trackKey = it.trackKey, titleHint = it.title) }.toMutableList()
        references.add(50, PlaylistReference(placeholderId = "unmatched-fixture", issue = PlaylistReferenceIssue.UNMATCHED, titleHint = "Unmatched"))
        references.add(151, PlaylistReference(trackKey = TrackKey(TrackKey.LOCAL_PROVIDER, "missing-fixture-track"), titleHint = "Missing"))
        val native = fixture.nativePlaylist("large-members", references)
        val first = fixture.firstPage(TransferCatalogKind.PLAYLIST_MEMBERS, native.playlistId)
        val removed = first.rows.filterIsInstance<TransferCatalogRow.Member>().first()
        fixture.playlists.apply(PlaylistMutationRequest(CompanionRoomFixture.uuid(), native.playlistId, native.revision,
            PlaylistMutation.Remove(removed.memberId, true)))
        val rows = first.rows.filterIsInstance<TransferCatalogRow.Member>().toMutableList()
        var page = first
        while (page.nextCursor != null) {
            page = fixture.nextPage(page, native.playlistId)
            rows += page.rows.filterIsInstance<TransferCatalogRow.Member>()
        }
        assertEquals(203, rows.size)
        assertEquals((0L until 203L).toList(), rows.map { it.ordinal })
        assertNull(rows[50].trackId)
        assertFalse(rows[50].available)
        assertEquals("missing-fixture-track", rows[151].trackId)
        assertFalse(rows[151].available)
        assertTrue(rows[200].available)
        assertEquals(202L, checkNotNull(fixture.playlists.snapshot(native.playlistId)).memberCount)
        for (search in listOf("Track", " ")) {
            expectTransferIssue(TransferIssue.INVALID_INPUT) { fixture.firstPage(TransferCatalogKind.PLAYLIST_MEMBERS,
                native.playlistId, search = search) }
        }
    }

    @Test(timeout = 30_000L) fun realCatalogSearchUsesLiteralWildcardsAndFrozenNativeNamesRatherThanFilteringTheFirstPageInMemory() = runBlocking {
        fixture.nativePlaylist("catalog-a", emptyList(), "Mix one")
        val b = fixture.nativePlaylist("catalog-b", emptyList(), "mix two")
        fixture.nativePlaylist("catalog-c", emptyList(), "50% Other")
        val first = fixture.firstPage(TransferCatalogKind.PLAYLISTS, search = "mix", limit = 1)
        assertEquals(2L, first.totalCount)
        fixture.playlists.apply(PlaylistMutationRequest(CompanionRoomFixture.uuid(), b.playlistId, b.revision,
            PlaylistMutation.Rename("Later")))
        val second = fixture.nextPage(first, search = "mix", limit = 1)
        assertEquals("mix two", (second.rows.single() as TransferCatalogRow.Playlist).name)
        assertEquals(first.revision, second.revision)
        assertNull(second.nextCursor)
        fixture.backend.closePeerViews(fixture.peer, fixture.scope.sessionId)
        assertEquals(listOf("50% Other"), fixture.rows(TransferCatalogKind.PLAYLISTS, search = "%")
            .filterIsInstance<TransferCatalogRow.Playlist>().map { it.name })
    }

    @Test(timeout = 30_000L) fun revokedGrantStopsCopyingALargeNativePlaylistBetweenRealBatchesBeforeAnyVisibleCommit() = runBlocking {
        seed(206)
        val tracks = fixture.tracks()
        val native = fixture.nativePlaylist("revocation-list", tracks.take(205).map { PlaylistReference(trackKey = it.trackKey) })
        val actual = fixture.database.playlistDao()
        var copied = 0
        val wrapped = object : PlaylistDao by actual {
            override suspend fun insertMembers(members: List<PlaylistMemberEntity>) {
                actual.insertMembers(members)
                if (members.size == 100) { copied++; fixture.authority.revokeForLocalUi(fixture.peer) }
            }
        }
        val backend = fixture.backendWithPlaylistDao(wrapped)
        val revision = fixture.library.overview()
        val request = TransferRequest.EditPlaylist(CompanionRoomFixture.uuid(), fixture.scope, native.playlistId, native.revision,
            TransferLibraryRevision(revision.revision, revision.rulesRevision), TransferPlaylistEdit.Add(listOf(tracks.last().trackKey.trackId)))
        val binding = TransferProtocolCodec.operationBinding(fixture.peer, request)
        val permit = fixture.permit(TransferCapability.WRITE_PLAYLISTS)
        expectTransferIssue(TransferIssue.NOT_APPROVED) { backend.editPlaylist(binding, request, permit) }
        assertEquals(1, copied)
        assertEquals(native, fixture.playlists.snapshot(native.playlistId))
        fixture.database.withTestWriterSql {
            assertEquals(0L, scalarLong("SELECT COUNT(*) FROM playlist_receipts WHERE operationId = ?", request.requestId))
            assertNull(scalarText("SELECT resultKind FROM transfer_room_operations WHERE operationId = ?", request.requestId))
        }
    }

    @Test(timeout = 30_000L) fun callerCancellationDuringARealStagingBatchLeavesTheNativeHeadAndGeneralReceiptUncommitted() = runBlocking {
        seed(206)
        val tracks = fixture.tracks()
        val native = fixture.nativePlaylist("cancel-list", tracks.take(205).map { PlaylistReference(trackKey = it.trackKey) })
        lateinit var mutation: Job
        val actual = fixture.database.playlistDao()
        val wrapped = object : PlaylistDao by actual {
            override suspend fun insertMembers(members: List<PlaylistMemberEntity>) {
                actual.insertMembers(members)
                if (members.size == 100) mutation.cancel()
            }
        }
        val backend = fixture.backendWithPlaylistDao(wrapped)
        val current = fixture.library.overview()
        val request = TransferRequest.EditPlaylist(CompanionRoomFixture.uuid(), fixture.scope, native.playlistId, native.revision,
            TransferLibraryRevision(current.revision, current.rulesRevision), TransferPlaylistEdit.Add(listOf(tracks.last().trackKey.trackId)))
        val binding = TransferProtocolCodec.operationBinding(fixture.peer, request)
        val permit = fixture.permit(TransferCapability.WRITE_PLAYLISTS)
        mutation = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { backend.editPlaylist(binding, request, permit) }
        mutation.start()
        withTimeout(10_000L) { mutation.join() }
        assertTrue(mutation.isCancelled)
        assertEquals(native, fixture.playlists.snapshot(native.playlistId))
        fixture.database.withTestWriterSql {
            assertEquals(0L, scalarLong("SELECT COUNT(*) FROM playlist_receipts WHERE operationId = ?", request.requestId))
            assertNull(scalarText("SELECT resultKind FROM transfer_room_operations WHERE operationId = ?", request.requestId))
            assertEquals(0L, scalarLong("SELECT COUNT(*) FROM playlist_generations WHERE operationId = ? AND state IN ('STAGING', 'SEALED')", request.requestId))
        }
    }

    @Test(timeout = 30_000L) fun failedLeaseRetirementKeepsItsBoundedSlotsUntilANewCloseSucceedsAndLeavesOtherOwnersAlone() = runBlocking {
        seed(1)
        val track = fixture.tracks().single()
        val native = fixture.nativePlaylist("retirement-list", listOf(PlaylistReference(trackKey = track.trackKey)))
        val uiLibrary = fixture.library.openView()
        val uiPlaylist = fixture.playlists.openView(native.playlistId, PlaylistViewOwner.UI)
        repeat(4) { fixture.firstPage() }
        expectTransferIssue(TransferIssue.BUSY) { fixture.firstPage() }
        fixture.database.withTestWriterSql { execute("""
            CREATE TRIGGER controlled_view_retirement BEFORE DELETE ON library_views
            BEGIN SELECT RAISE(ABORT, 'controlled retirement failure'); END
        """.trimIndent()) }
        try {
            fixture.backend.closePeerViews(fixture.peer, CompanionRoomFixture.uuid())
            expectTransferIssue(TransferIssue.RECOVERY_REQUIRED) { fixture.backend.closePeerViews(fixture.peer, fixture.scope.sessionId) }
            expectTransferIssue(TransferIssue.RECOVERY_REQUIRED) { fixture.firstPage() }
            fixture.database.withTestWriterSql {
                assertEquals(4L, scalarLong("SELECT COUNT(*) FROM library_views WHERE owner = 'TRANSFER'"))
                assertEquals(1L, scalarLong("SELECT COUNT(*) FROM library_views WHERE owner = 'UI'"))
                execute("DROP TRIGGER controlled_view_retirement")
            }
            fixture.backend.closePeerViews(fixture.peer, fixture.scope.sessionId)
            fixture.backend.closePeerViews(fixture.peer, fixture.scope.sessionId)
            assertEquals(listOf(track.trackKey), fixture.library.page(uiLibrary).tracks.map { it.trackKey })
            assertEquals(1, fixture.playlists.page(uiPlaylist, 0L).members.size)
            fixture.database.withTestWriterSql { assertEquals(0L, scalarLong("SELECT COUNT(*) FROM library_views WHERE owner = 'TRANSFER'")) }
        } finally {
            fixture.database.withTestWriterSql { execute("DROP TRIGGER IF EXISTS controlled_view_retirement") }
            fixture.library.closeView(uiLibrary.viewId)
            fixture.playlists.closeView(uiPlaylist.viewId)
        }
    }

    @Test(timeout = 30_000L) fun cancellationInsideTheFinalRoomTransactionBeforeClaimRollsBackTheNativePublicationAndReceipt() = runBlocking {
        seed(2)
        val tracks = fixture.tracks()
        val native = fixture.nativePlaylist("cancel-before-claim", listOf(PlaylistReference(trackKey = tracks.first().trackKey)))
        val current = fixture.library.overview()
        val request = TransferRequest.EditPlaylist(CompanionRoomFixture.uuid(), fixture.scope, native.playlistId, native.revision,
            TransferLibraryRevision(current.revision, current.rulesRevision), TransferPlaylistEdit.Add(listOf(tracks.last().trackKey.trackId)))
        lateinit var mutation: Job
        var boundaryReached = false
        val actual = fixture.database.playlistDao()
        val wrapped = object : PlaylistDao by actual {
            override suspend fun generation(id: String): PlaylistGenerationEntity? {
                val row = actual.generation(id)
                if (!boundaryReached && row?.operationId == request.requestId && row.state == "SEALED") {
                    boundaryReached = true
                    mutation.cancel()
                }
                return row
            }
        }
        val backend = fixture.backendWithPlaylistDao(wrapped)
        val binding = TransferProtocolCodec.operationBinding(fixture.peer, request)
        val permit = fixture.permit(TransferCapability.WRITE_PLAYLISTS)
        mutation = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { backend.editPlaylist(binding, request, permit) }
        mutation.start()
        withTimeout(10_000L) { mutation.join() }
        assertTrue(boundaryReached)
        assertTrue(mutation.isCancelled)
        assertEquals(native, fixture.playlists.snapshot(native.playlistId))
        fixture.database.withTestWriterSql {
            assertEquals(0L, scalarLong("SELECT COUNT(*) FROM playlist_receipts WHERE operationId = ?", request.requestId))
            assertNull(scalarText("SELECT resultKind FROM transfer_room_operations WHERE operationId = ?", request.requestId))
            assertEquals(0L, scalarLong("SELECT COUNT(*) FROM playlist_generations WHERE operationId = ? AND state IN ('STAGING', 'SEALED')", request.requestId))
        }
    }

    @Test(timeout = 30_000L) fun aDamagedCompletedPersistentReceiptCannotBecomeASuccessfulPlaylistReplay() = runBlocking {
        val operation = CompanionRoomFixture.uuid()
        fixture.edit("damaged-receipt", 0L, TransferPlaylistEdit.Create("Stored"), operation)
        val request = TransferRequest.EditPlaylist(operation, fixture.scope, "damaged-receipt", 0L, null, TransferPlaylistEdit.Create("Stored"))
        val binding = TransferProtocolCodec.operationBinding(fixture.peer, request)
        fixture.database.withTestWriterSql { execute("UPDATE transfer_room_operations SET resultId = NULL WHERE operationId = ?", operation) }
        expectTransferIssue(TransferIssue.RECOVERY_REQUIRED) { fixture.backend.committed(binding, fixture.permit(TransferCapability.WRITE_PLAYLISTS)) }
        assertNotNull(fixture.playlists.snapshot("damaged-receipt"))
    }

    private suspend fun seed(count: Int): io.github.onlinemo.meloxwatch.data.library.LibrarySource {
        val source = fixture.source()
        fixture.append(source, (0 until count).map { index -> "track-$index" to LocalAudioMetadata(
            title = "Track ${index.toString().padStart(4, '0')}", artist = "Artist", album = "Album", durationMs = 120_000L) })
        return source
    }
    private fun trackIds(page: TransferCatalogPage) = page.rows.filterIsInstance<TransferCatalogRow.Track>().map { it.trackId }
}
