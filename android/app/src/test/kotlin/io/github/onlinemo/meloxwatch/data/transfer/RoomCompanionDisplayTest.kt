package io.github.onlinemo.meloxwatch.data.transfer

import android.content.Context
import io.github.onlinemo.meloxwatch.data.database.withTestWriterSql
import io.github.onlinemo.meloxwatch.data.library.LibraryGroupKind
import io.github.onlinemo.meloxwatch.data.library.LibraryQuery
import io.github.onlinemo.meloxwatch.data.library.LibrarySort
import io.github.onlinemo.meloxwatch.data.library.LibrarySortField
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

/** Display-only edits through the real Room owner; none of these cases writes an original tag. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomCompanionDisplayTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var fixture: CompanionRoomFixture
    @Before fun open() = runBlocking {
        fixture = CompanionRoomFixture(RuntimeEnvironment.getApplication() as Context, File(temporary.root, "display-room.db"))
        fixture.initialize()
    }
    @After fun close() { fixture.close() }

    @Test(timeout = 30_000L) fun longSameNamedAlbumsHaveStableSeparateWireIdsAndCompleteParentPages() = runBlocking {
        val source = fixture.source()
        val name = "同名专辑" + "长".repeat(1_000)
        fixture.append(source, (0 until 201).map { i -> "a-$i" to LocalAudioMetadata(title = "A $i", album = name,
            albumArtist = "Credit A", artist = "Alpha", trackNumber = i + 1) } +
            listOf("b" to LocalAudioMetadata(title = "B", album = name, albumArtist = "Credit B", artist = "Beta")))
        val wireName = name.take(TransferLimits.FIELD_CHARACTERS)
        val a = fixture.group(TransferDisplayKind.ALBUM, wireName, "Credit A")
        val b = fixture.group(TransferDisplayKind.ALBUM, wireName, "Credit B")
        assertNotEquals(a.entityId, b.entityId)
        assertTrue(a.entityId.length <= TransferLimits.IDENTIFIER_CHARACTERS)
        assertEquals(a.entityId, fixture.group(TransferDisplayKind.ALBUM, wireName, "Credit A").entityId)
        assertEquals(name, fixture.tracks().first { it.metadata.albumArtist == "Credit A" }.metadata.album)
        val first = fixture.firstPage(TransferCatalogKind.ALBUM_TRACKS, a.entityId)
        assertEquals(201L, first.totalCount)
        assertEquals(checkNotNull(first.nextCursor), UUID.fromString(first.nextCursor).toString())
        val second = fixture.nextPage(first, a.entityId)
        val third = fixture.nextPage(second, a.entityId)
        assertEquals(201, first.rows.size + second.rows.size + third.rows.size)
        assertNull(third.nextCursor)
        assertEquals(1L, fixture.firstPage(TransferCatalogKind.ALBUM_TRACKS, b.entityId).totalCount)
    }

    @Test(timeout = 30_000L) fun aliasRenameChangesClassificationSearchAndTrackDisplayWithoutChangingScannedMetadataOrSameNamedAlbum() = runBlocking {
        val source = fixture.source()
        val original = LocalAudioMetadata(title = "Before", artists = listOf("Alpha", "Guest"), artist = "Alpha",
            album = "Same", albumArtist = "Credit A", durationMs = 120_000L)
        fixture.append(source, listOf("a" to original,
            "b" to LocalAudioMetadata(title = "Other", album = "Same", albumArtist = "Credit B", artist = "Beta")))
        val a = fixture.group(TransferDisplayKind.ALBUM, "Same", "Credit A")
        val b = fixture.group(TransferDisplayKind.ALBUM, "Same", "Credit B")
        val oldView = fixture.library.openView()
        fixture.apply(fixture.preview(fixture.state(TransferDisplayKind.ALBUM, a.entityId), TransferDisplayPatch.Rename("Favorite album")))
        assertEquals("Favorite album", fixture.state(TransferDisplayKind.ALBUM, a.entityId).currentName)
        assertEquals("Same", fixture.state(TransferDisplayKind.ALBUM, b.entityId).currentName)
        val changed = fixture.tracks().single { it.title == "Before" }
        assertEquals(original, changed.metadata)
        assertEquals("Favorite album", changed.displayAlbum)
        assertEquals(changed.trackKey, fixture.tracks(LibraryQuery(search = "Favorite album")).single().trackKey)
        assertEquals("Same", fixture.library.page(oldView).tracks.single { it.title == "Before" }.displayAlbum)
        fixture.library.closeView(oldView.viewId)
        val alpha = fixture.group(TransferDisplayKind.ARTIST, "Alpha")
        fixture.apply(fixture.preview(fixture.state(TransferDisplayKind.ARTIST, alpha.entityId), TransferDisplayPatch.Rename("Zulu")))
        assertEquals("Zulu", fixture.tracks().single { it.title == "Before" }.displayArtist)
        assertEquals(1L, fixture.group(TransferDisplayKind.ARTIST, "Guest").trackCount)
        fixture.append(source, listOf("a" to original.copy(title = "Rescanned")))
        val rescanned = fixture.tracks().single { it.title == "Rescanned" }
        assertEquals("Same", rescanned.metadata.album)
        assertEquals(listOf("Alpha", "Guest"), rescanned.metadata.artists)
        assertEquals("Favorite album", rescanned.displayAlbum)
        assertEquals("Zulu", rescanned.displayArtist)
        fixture.reopen()
        assertEquals("Favorite album", fixture.tracks().single { it.title == "Rescanned" }.displayAlbum)
    }

    @Test(timeout = 30_000L) fun alphabetSectionsUseActualAsciiFromScannedAndRenamedArtistKeys() = runBlocking {
        val source = fixture.source()
        fixture.append(source, listOf("a" to LocalAudioMetadata(title = "Song", artist = "Alpha")))
        val before = fixture.library.openView(sort = LibrarySort(LibrarySortField.ARTIST))
        assertEquals(listOf("A"), fixture.library.sections(before).map { it.key })
        fixture.library.closeView(before.viewId)
        val alpha = fixture.group(TransferDisplayKind.ARTIST, "Alpha")
        fixture.apply(fixture.preview(fixture.state(TransferDisplayKind.ARTIST, alpha.entityId), TransferDisplayPatch.Rename("Zulu")))
        val after = fixture.library.openView(sort = LibrarySort(LibrarySortField.ARTIST))
        assertEquals(listOf("Z"), fixture.library.sections(after).map { it.key })
        fixture.library.closeView(after.viewId)
    }

    @Test(timeout = 30_000L) fun separateHundredTrackAlbumPatchesCanGrowAndEmptyTheWholeGroupWhileRawAlbumsSurvive() = runBlocking {
        val source = fixture.source()
        fixture.append(source, listOf("anchor" to LocalAudioMetadata(title = "Anchor", album = "Target")) +
            (0 until 202).map { i -> "b-$i" to LocalAudioMetadata(title = "B $i", album = "Original") })
        val tracks = fixture.tracks()
        val target = fixture.group(TransferDisplayKind.ALBUM, "Target")
        for (batch in tracks.filter { it.metadata.album == "Original" }.map { it.trackKey.trackId }.chunked(100)) {
            fixture.apply(fixture.preview(fixture.state(TransferDisplayKind.ALBUM, target.entityId), TransferDisplayPatch.Add(batch)))
        }
        assertEquals(203L, fixture.state(TransferDisplayKind.ALBUM, target.entityId).trackCount)
        assertEquals(tracks.associate { it.trackKey to it.metadata }, fixture.tracks().associate { it.trackKey to it.metadata })
        for (batch in tracks.map { it.trackKey.trackId }.chunked(100)) {
            fixture.apply(fixture.preview(fixture.state(TransferDisplayKind.ALBUM, target.entityId), TransferDisplayPatch.Remove(batch)))
        }
        val empty = fixture.state(TransferDisplayKind.ALBUM, target.entityId)
        assertEquals(0L, empty.trackCount)
        assertEquals(target.entityId, fixture.group(TransferDisplayKind.ALBUM, "Target").entityId)
        assertEquals(0L, fixture.firstPage(TransferCatalogKind.ALBUM_TRACKS, target.entityId).totalCount)
        assertEquals(203L, fixture.group(TransferDisplayKind.ALBUM, "Unknown album").trackCount)
        assertEquals(tracks.associate { it.trackKey to it.metadata }, fixture.tracks().associate { it.trackKey to it.metadata })
    }

    @Test(timeout = 30_000L) fun artistPatchesChangeOnlyOneAssociationAndPreserveOtherArtistsUntilUnknownIsTheRealFallback() = runBlocking {
        val source = fixture.source()
        fixture.append(source, listOf("multi" to LocalAudioMetadata(title = "Multi", artists = listOf("Alpha", "Beta")),
            "anchor" to LocalAudioMetadata(title = "Anchor", artist = "Gamma")))
        val multi = fixture.tracks().single { it.title == "Multi" }
        val gamma = fixture.group(TransferDisplayKind.ARTIST, "Gamma")
        fixture.apply(fixture.preview(fixture.state(TransferDisplayKind.ARTIST, gamma.entityId), TransferDisplayPatch.Add(listOf(multi.trackKey.trackId))))
        assertEquals(2L, fixture.state(TransferDisplayKind.ARTIST, gamma.entityId).trackCount)
        for (name in listOf("Alpha", "Beta")) {
            val group = fixture.group(TransferDisplayKind.ARTIST, name)
            fixture.apply(fixture.preview(fixture.state(TransferDisplayKind.ARTIST, group.entityId), TransferDisplayPatch.Remove(listOf(multi.trackKey.trackId))))
            assertEquals(0L, fixture.state(TransferDisplayKind.ARTIST, group.entityId).trackCount)
        }
        assertEquals("Gamma", fixture.tracks().single { it.trackKey == multi.trackKey }.displayArtist)
        fixture.apply(fixture.preview(fixture.state(TransferDisplayKind.ARTIST, gamma.entityId), TransferDisplayPatch.Remove(listOf(multi.trackKey.trackId))))
        val current = fixture.tracks().single { it.trackKey == multi.trackKey }
        assertNull(current.displayArtist)
        assertEquals(multi.metadata, current.metadata)
        assertEquals(1L, fixture.group(TransferDisplayKind.ARTIST, "Unknown artist").trackCount)
        assertEquals(1L, fixture.state(TransferDisplayKind.ARTIST, gamma.entityId).trackCount)
    }

    @Test(timeout = 30_000L) fun theFinalAlbumRemovalKeepsOnlyLaterViewsAwareOfTheExplicitEmptyGroup() = runBlocking {
        val source = fixture.source()
        fixture.append(source, listOf("a" to LocalAudioMetadata(title = "A", album = "Only")))
        val group = fixture.group(TransferDisplayKind.ALBUM, "Only")
        val old = fixture.library.openView()
        val track = fixture.tracks().single()
        fixture.apply(fixture.preview(fixture.state(TransferDisplayKind.ALBUM, group.entityId),
            TransferDisplayPatch.Remove(listOf(track.trackKey.trackId))))
        assertEquals(1L, fixture.library.groups(old, LibraryGroupKind.ALBUM).groups.single().trackCount)
        assertEquals(0L, fixture.state(TransferDisplayKind.ALBUM, group.entityId).trackCount)
        assertEquals(group.entityId, fixture.group(TransferDisplayKind.ALBUM, "Only").entityId)
        fixture.library.closeView(old.viewId)
        fixture.reopen()
        assertEquals(0L, fixture.state(TransferDisplayKind.ALBUM, group.entityId).trackCount)
    }

    @Test(timeout = 30_000L) fun previewRetryBindsTheFullPatchAndGrantAndCancelNeverChangesTheLibrary() = runBlocking {
        val source = fixture.source()
        fixture.append(source, listOf("a" to LocalAudioMetadata(title = "A", album = "Album")))
        val group = fixture.group(TransferDisplayKind.ALBUM, "Album")
        val state = fixture.state(TransferDisplayKind.ALBUM, group.entityId)
        val previewId = CompanionRoomFixture.uuid()
        val patch = TransferDisplayPatch.Rename("Proposal")
        val preview = fixture.preview(state, patch, previewId)
        val retry = fixture.preview(state, patch, previewId)
        assertEquals(preview.previewId, retry.previewId)
        assertTrue(preview.patch.sameAs(retry.patch))
        expectTransferIssue(TransferIssue.CONFLICT) { fixture.preview(state, TransferDisplayPatch.Rename("Different"), previewId) }
        expectTransferIssue(TransferIssue.NOT_APPROVED) { fixture.apply(preview, confirmed = false) }
        assertEquals(state.revision, fixture.library.overview().revision)
        assertEquals("Album", fixture.state(TransferDisplayKind.ALBUM, group.entityId).currentName)
        fixture.newSession(setOf(TransferCapability.WRITE_DISPLAY_METADATA))
        expectTransferIssue(TransferIssue.WRONG_TARGET) { fixture.preview(state, patch, previewId) }
        assertEquals("Album", fixture.state(TransferDisplayKind.ALBUM, group.entityId).currentName)
    }

    @Test(timeout = 30_000L) fun expiredAndPreviousProcessPreviewsCannotBeAppliedEvenWithAFreshValidToken() = runBlocking {
        val source = fixture.source()
        fixture.append(source, listOf("a" to LocalAudioMetadata(title = "A", album = "Album")))
        val group = fixture.group(TransferDisplayKind.ALBUM, "Album")
        val state = fixture.state(TransferDisplayKind.ALBUM, group.entityId)
        val requestId = CompanionRoomFixture.uuid()
        val preview = fixture.preview(state, TransferDisplayPatch.Rename("Pending"), requestId)
        val restarted = fixture.backendWithPlaylistDao(fixture.database.playlistDao(), processId = "other-room-process")
        expectTransferIssue(TransferIssue.EXPIRED) { restarted.previewDisplay(
            TransferRequest.PreviewDisplay(requestId, fixture.scope, state.kind, state.entityId, state.revision,
                state.libraryRevision, TransferDisplayPatch.Rename("Pending")), fixture.permit(TransferCapability.WRITE_DISPLAY_METADATA)) }
        fixture.elapsed += TransferLimits.TOKEN_MS + 1L
        fixture.renewToken()
        expectTransferIssue(TransferIssue.EXPIRED) { fixture.apply(preview) }
        assertEquals("Album", fixture.state(TransferDisplayKind.ALBUM, group.entityId).currentName)
    }

    @Test(timeout = 30_000L) fun aRulesChangeInvalidatesAProposalEvenWhenItsObjectNameAndMembershipDidNotChange() = runBlocking {
        val source = fixture.source()
        fixture.append(source, listOf("a" to LocalAudioMetadata(title = "A", album = "Album", durationMs = 120_000L)))
        val group = fixture.group(TransferDisplayKind.ALBUM, "Album")
        val state = fixture.state(TransferDisplayKind.ALBUM, group.entityId)
        val preview = fixture.preview(state, TransferDisplayPatch.Rename("Stale"))
        fixture.library.setShortAudioFilter(fixture.library.rules().revision, true)
        expectTransferIssue(TransferIssue.CONFLICT) { fixture.apply(preview) }
        assertEquals("Album", fixture.state(TransferDisplayKind.ALBUM, group.entityId).currentName)
        assertNotNull(fixture.database.transferRoomDao().preview(preview.previewId))
        assertNull(fixture.database.displayDao().currentName(group.entityId))
    }

    @Test(timeout = 30_000L) fun failingReceiptWriteRollsBackMarkerAssociationsRevisionAndPreviewConsumptionAsOneRealTransaction() = runBlocking {
        val source = fixture.source()
        fixture.append(source, listOf("a" to LocalAudioMetadata(title = "A", album = "Target"),
            "b" to LocalAudioMetadata(title = "B", album = "Original")))
        val b = fixture.tracks().single { it.title == "B" }
        val group = fixture.group(TransferDisplayKind.ALBUM, "Target")
        val state = fixture.state(TransferDisplayKind.ALBUM, group.entityId)
        val preview = fixture.preview(state, TransferDisplayPatch.Add(listOf(b.trackKey.trackId)))
        val operation = CompanionRoomFixture.uuid()
        fixture.database.withTestWriterSql { execute("""
            CREATE TRIGGER controlled_display_receipt BEFORE UPDATE ON transfer_room_operations
            WHEN NEW.resultKind IS NOT NULL BEGIN SELECT RAISE(ABORT, 'controlled display receipt failure'); END
        """.trimIndent()) }
        try {
            expectTransferIssue(TransferIssue.RECOVERY_REQUIRED) { fixture.apply(preview, operation) }
            assertEquals(state.revision, fixture.library.overview().revision)
            assertNull(fixture.database.displayDao().currentAlbum(b.trackKey.trackId))
            assertNull(checkNotNull(fixture.database.displayDao().group(group.entityId)).editedFrom)
            assertNotNull(fixture.database.transferRoomDao().preview(preview.previewId))
            fixture.database.withTestWriterSql {
                assertEquals(0L, scalarLong("SELECT COUNT(*) FROM transfer_room_operations WHERE operationId = ?", operation))
                execute("DROP TRIGGER controlled_display_receipt")
            }
            val accepted = fixture.apply(preview, operation)
            assertEquals(group.entityId, accepted.resultId)
            assertEquals(2L, fixture.state(TransferDisplayKind.ALBUM, group.entityId).trackCount)
            assertEquals("Original", fixture.tracks().single { it.trackKey == b.trackKey }.metadata.album)
            assertNull(fixture.database.transferRoomDao().preview(preview.previewId))
        } finally { fixture.database.withTestWriterSql { execute("DROP TRIGGER IF EXISTS controlled_display_receipt") } }
    }

    @Test(timeout = 30_000L) fun exactCompletedDisplayReplayAfterRestartRetainsTheOriginalReceiptAndLaterAlias() = runBlocking {
        val source = fixture.source()
        fixture.append(source, listOf("a" to LocalAudioMetadata(title = "A", album = "Album")))
        val group = fixture.group(TransferDisplayKind.ALBUM, "Album")
        val preview = fixture.preview(fixture.state(TransferDisplayKind.ALBUM, group.entityId), TransferDisplayPatch.Rename("First"))
        val operation = CompanionRoomFixture.uuid()
        val receipt = fixture.apply(preview, operation)
        fixture.apply(fixture.preview(fixture.state(TransferDisplayKind.ALBUM, group.entityId), TransferDisplayPatch.Rename("Later")))
        fixture.reopen()
        val replay = fixture.apply(preview, operation)
        assertEquals(receipt.revision, replay.revision)
        assertEquals(receipt.resultId, replay.resultId)
        assertTrue(receipt.binding.sameOperation(replay.binding))
        assertEquals("Later", fixture.state(TransferDisplayKind.ALBUM, group.entityId).currentName)
        assertEquals(1L, fixture.library.overview().totalTracks)
    }

    @Test(timeout = 30_000L) fun displayWriteOnlyCanRenameUnknownFallbacksButCannotAssignOrRemoveTheirMembership() = runBlocking {
        val source = fixture.source()
        fixture.append(source, listOf("a" to LocalAudioMetadata(title = "No tags")))
        val track = fixture.tracks().single()
        fixture.newSession(setOf(TransferCapability.WRITE_DISPLAY_METADATA))
        val album = fixture.group(TransferDisplayKind.ALBUM, "Unknown album")
        val state = fixture.state(TransferDisplayKind.ALBUM, album.entityId)
        expectTransferIssue(TransferIssue.INVALID_INPUT) { fixture.preview(state, TransferDisplayPatch.Add(listOf(track.trackKey.trackId))) }
        fixture.apply(fixture.preview(state, TransferDisplayPatch.Rename("未标专辑")))
        val artist = fixture.group(TransferDisplayKind.ARTIST, "Unknown artist")
        fixture.apply(fixture.preview(fixture.state(TransferDisplayKind.ARTIST, artist.entityId), TransferDisplayPatch.Rename("未标艺人")))
        val after = fixture.tracks().single()
        assertEquals("未标专辑", after.displayAlbum)
        assertEquals("未标艺人", after.displayArtist)
        assertEquals(track.metadata, after.metadata)
        expectTransferIssue(TransferIssue.PERMISSION_DENIED) { fixture.firstPage(capability = TransferCapability.WRITE_DISPLAY_METADATA) }
    }

    @Test(timeout = 30_000L) fun corruptedStoredPreviewReturnsRecoveryInsteadOfPretendingTheClientSentBadInput() = runBlocking {
        val source = fixture.source()
        fixture.append(source, listOf("a" to LocalAudioMetadata(title = "A", album = "Target"),
            "b" to LocalAudioMetadata(title = "B", album = "Original")))
        val b = fixture.tracks().single { it.title == "B" }
        val group = fixture.group(TransferDisplayKind.ALBUM, "Target")
        val preview = fixture.preview(fixture.state(TransferDisplayKind.ALBUM, group.entityId), TransferDisplayPatch.Add(listOf(b.trackKey.trackId)))
        fixture.database.withTestWriterSql { execute("UPDATE transfer_display_previews SET patchTrackIds = ? WHERE previewId = ?",
            b.trackKey.trackId + "\n" + b.trackKey.trackId, preview.previewId) }
        expectTransferIssue(TransferIssue.RECOVERY_REQUIRED) { fixture.apply(preview) }
        assertNull(fixture.database.displayDao().currentAlbum(b.trackKey.trackId))
        assertEquals(1L, fixture.state(TransferDisplayKind.ALBUM, group.entityId).trackCount)
    }

    @Test(timeout = 30_000L) fun malformedDisplayFieldsAreSanitizedOnlyOnTheWireAndStoredScanMetadataIsRetained() = runBlocking {
        val source = fixture.source()
        fixture.append(source, listOf("a" to LocalAudioMetadata(title = "Song\nname", artist = "Artist\uD800", album = "Album\u0001")))
        val before = fixture.tracks().single().metadata
        val page = fixture.firstPage()
        val row = page.rows.single() as TransferCatalogRow.Track
        assertFalse(listOf(row.title, row.artist, row.album).any { value -> value.any { it.isISOControl() } })
        val output = ByteArrayOutputStream()
        TransferProtocolCodec.writeResponse(output, TransferResponse.Page(CompanionRoomFixture.uuid(), page))
        assertTrue(output.size() > 0)
        assertEquals(before, fixture.tracks().single().metadata)
    }
}
