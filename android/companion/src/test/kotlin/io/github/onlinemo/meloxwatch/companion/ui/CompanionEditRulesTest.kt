package io.github.onlinemo.meloxwatch.companion.ui

import io.github.onlinemo.meloxwatch.companion.data.CompanionCachedPage
import io.github.onlinemo.meloxwatch.companion.data.CompanionConnectionPhase
import io.github.onlinemo.meloxwatch.companion.data.CompanionConnectionState
import io.github.onlinemo.meloxwatch.companion.data.CompanionPageWindow
import io.github.onlinemo.meloxwatch.companion.data.CompanionTarget
import io.github.onlinemo.meloxwatch.data.transfer.TransferCapability
import io.github.onlinemo.meloxwatch.data.transfer.TransferCatalogKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferCatalogPage
import io.github.onlinemo.meloxwatch.data.transfer.TransferCatalogRow
import io.github.onlinemo.meloxwatch.data.transfer.TransferDisplayKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferDisplayPatch
import io.github.onlinemo.meloxwatch.data.transfer.TransferDisplayState
import io.github.onlinemo.meloxwatch.data.transfer.TransferException
import io.github.onlinemo.meloxwatch.data.transfer.TransferFingerprint
import io.github.onlinemo.meloxwatch.data.transfer.TransferIssue
import io.github.onlinemo.meloxwatch.data.transfer.TransferLibraryRevision
import io.github.onlinemo.meloxwatch.data.transfer.TransferLimits
import io.github.onlinemo.meloxwatch.data.transfer.TransferPlaylistEdit
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 直接调用界面共用生产规则；不宣称 Compose、设备或真实手表提交已验收。 */
class CompanionEditRulesTest {
    private val target = TransferFingerprint.digest(byteArrayOf(1))
    private val fence = TransferLibraryRevision(7L, 3L)
    private val permissions = setOf(TransferCapability.READ_LIBRARY, TransferCapability.WRITE_PLAYLISTS, TransferCapability.WRITE_DISPLAY_METADATA)
    private fun id(number: Long): String = UUID(0L, number).toString()

    private fun connection(capabilities: Set<TransferCapability> = permissions, phase: CompanionConnectionPhase = CompanionConnectionPhase.READY): CompanionConnectionState =
        CompanionConnectionState(phase, CompanionTarget(target, "Fixture watch"), id(1), capabilities)

    private fun playlistState(automatic: Boolean = true): CompanionUiState {
        val row = TransferCatalogRow.Playlist("playlist:fixture", "Folder", automatic, 11L, 20001L)
        val member = TransferCatalogRow.Member("member:100", 100L, null, "Unavailable member", "", false)
        val cached = CompanionCachedPage(target, id(1), TransferCatalogPage(TransferCatalogKind.PLAYLIST_MEMBERS, id(4), 11L,
            fence, 20001L, 100L, listOf(member), "cursor_101"), 1L)
        return CompanionUiState(connection = connection(), playlist = CompanionPlaylistSelection(target, row, fence, id(1)),
            pageWindow = CompanionPageWindow(TransferCatalogKind.PLAYLIST_MEMBERS, "", row.playlistId, listOf(cached), 0))
    }

    private fun trackState(selection: CompanionTrackSelection, rows: List<TransferCatalogRow.Track> = listOf(unavailable())): CompanionUiState {
        val remove = selection == CompanionTrackSelection.DISPLAY_REMOVE
        val kind = if (remove) TransferCatalogKind.ALBUM_TRACKS else TransferCatalogKind.TRACKS
        val parent = if (remove) "album:fixture" else null
        val cached = CompanionCachedPage(target, id(1), TransferCatalogPage(kind, id(4), 5L, fence,
            20001L, 0L, rows, "cursor_${rows.size}"), 1L)
        val group = CompanionGroupSelection(target, TransferDisplayState(TransferDisplayKind.ALBUM, "album:fixture", 7L, fence, "", 20001L), id(1))
        return playlistState(automatic = false).copy(trackSelection = selection,
            pageWindow = CompanionPageWindow(kind, "", parent, listOf(cached), 0), group = group)
    }

    private fun unavailable(): TransferCatalogRow.Track = TransferCatalogRow.Track("track:missing", "Known missing track", "", "", null, false)
    private fun selectedRow(state: CompanionUiState): TransferCatalogRow.Track = checkNotNull(state.pageWindow.current).page.rows.first() as TransferCatalogRow.Track

    @Test fun automaticFolderMoveSupportsAbsoluteFirstAndLastOrdinals() {
        val state = playlistState()
        for (destination in listOf(0L, 20000L)) {
            val edit = TransferPlaylistEdit.Move("member:100", destination)
            val draft = prepareCompanionPlaylistDraft(state, edit, id(2))
            assertSame(edit, draft.edit)
            assertEquals(destination, (draft.edit as TransferPlaylistEdit.Move).destinationOrdinal)
            assertEquals(11L, draft.expectedRevision)
            assertEquals("playlist:fixture", draft.playlistId)
            assertTrue(checkNotNull(draft.libraryRevision).sameAs(fence))
            assertTrue(checkNotNull(state.pageWindow.current).page.libraryRevision.sameAs(fence))
        }
    }

    @Test fun automaticRenameAndDeleteReachTheAuthoritativeWatchTypeDecision() {
        val state = playlistState()
        val renamed = prepareCompanionPlaylistDraft(state, TransferPlaylistEdit.Rename("Renamed folder"), id(2))
        val deleted = prepareCompanionPlaylistDraft(state, TransferPlaylistEdit.Delete(confirmed = true), id(3))
        assertEquals("Renamed folder", renamed.playlistName)
        assertEquals(11L, renamed.expectedRevision)
        assertEquals(11L, deleted.expectedRevision)
        assertTrue((deleted.edit as TransferPlaylistEdit.Delete).confirmed)
        // 这里只允许提交候选；系统类型保护必须由真实 receiver 正常/异常用例证明。
        assertEquals(target, deleted.target)
    }

    @Test fun automaticMemberAddAndRemoveStayForbidden() {
        val automatic = playlistState()
        issue(TransferIssue.PERMISSION_DENIED) {
            prepareCompanionPlaylistDraft(automatic, TransferPlaylistEdit.Remove("member:100", confirmed = true), id(2))
        }
        val tracks = trackState(CompanionTrackSelection.PLAYLIST_ADD).copy(playlist = automatic.playlist)
        issue(TransferIssue.PERMISSION_DENIED) { toggleCompanionTrack(tracks, selectedRow(tracks)) }
        issue(TransferIssue.PERMISSION_DENIED) {
            prepareCompanionPlaylistDraft(tracks.copy(selectedTracks = setOf("track:missing"), selectedTrackRevision = fence), TransferPlaylistEdit.Add(listOf("track:missing")), id(2))
        }
    }

    @Test fun memberMoveAndRemoveRejectStaleSessionParentRevisionFenceAndIdentity() {
        val base = playlistState(automatic = false)
        val selected = checkNotNull(base.playlist)
        val cached = checkNotNull(base.pageWindow.current)
        fun replaced(page: TransferCatalogPage): CompanionUiState = base.copy(pageWindow = CompanionPageWindow(TransferCatalogKind.PLAYLIST_MEMBERS, "", selected.row.playlistId,
            listOf(CompanionCachedPage(target, id(1), page, 1L)), 0))
        val wrongRevision = TransferCatalogPage(TransferCatalogKind.PLAYLIST_MEMBERS, id(4), 12L, fence, 20001L, 100L, cached.page.rows, "cursor_101")
        val wrongFence = TransferCatalogPage(TransferCatalogKind.PLAYLIST_MEMBERS, id(4), 11L, TransferLibraryRevision(7L, 4L), 20001L, 100L, cached.page.rows, "cursor_101")
        val cases = listOf(
            base.copy(playlist = CompanionPlaylistSelection(target, selected.row, fence, id(9))),
            base.copy(pageWindow = CompanionPageWindow(TransferCatalogKind.PLAYLIST_MEMBERS, "", "playlist:other", listOf(cached), 0)),
            replaced(wrongRevision), replaced(wrongFence),
        )
        for (state in cases) {
            issue(TransferIssue.CONFLICT) { prepareCompanionPlaylistDraft(state, TransferPlaylistEdit.Move("member:100", 0L), id(2)) }
            issue(TransferIssue.CONFLICT) { prepareCompanionPlaylistDraft(state, TransferPlaylistEdit.Remove("member:100", true), id(2)) }
        }
        issue(TransferIssue.CONFLICT) { prepareCompanionPlaylistDraft(base, TransferPlaylistEdit.Move("member:missing", 0L), id(2)) }
        issue(TransferIssue.INVALID_INPUT) { prepareCompanionPlaylistDraft(base, TransferPlaylistEdit.Move("member:100", 20001L), id(2)) }
        issue(TransferIssue.WRONG_TARGET) {
            prepareCompanionPlaylistDraft(base.copy(playlist = CompanionPlaylistSelection(TransferFingerprint.digest(byteArrayOf(9)), selected.row, fence, id(1))),
                TransferPlaylistEdit.Move("member:100", 0L), id(2))
        }
    }

    @Test fun unavailableKnownTrackCanBeSelectedAndAddedToAnOrdinaryPlaylist() {
        val state = trackState(CompanionTrackSelection.PLAYLIST_ADD)
        val row = selectedRow(state)
        assertFalse(row.available)
        val selected = toggleCompanionTrack(state, row)
        val draft = prepareCompanionPlaylistDraft(selected, TransferPlaylistEdit.Add(selected.selectedTracks.toList()), id(2))
        assertEquals(listOf(row.trackId), (draft.edit as TransferPlaylistEdit.Add).trackIds)
        assertTrue(checkNotNull(draft.libraryRevision).sameAs(fence))
        assertEquals(11L, draft.expectedRevision)
    }

    @Test fun unavailableKnownGroupTrackCanBeRemovedWithoutAudioAvailability() {
        val state = trackState(CompanionTrackSelection.DISPLAY_REMOVE)
        val row = selectedRow(state)
        val selected = toggleCompanionTrack(state, row)
        val draft = prepareCompanionDisplayDraft(selected, TransferDisplayPatch.Remove(selected.selectedTracks.toList()), selected.selectedTrackRevision, id(2))
        assertFalse(row.available)
        assertEquals("album:fixture", draft.entityId)
        assertEquals(listOf(row.trackId), (draft.patch as TransferDisplayPatch.Remove).trackIds)
        assertTrue(draft.libraryRevision.sameAs(fence))
    }

    @Test fun unavailableKnownTrackCanBeAddedToTheSelectedDisplayGroup() {
        val state = trackState(CompanionTrackSelection.DISPLAY_ADD)
        val selected = toggleCompanionTrack(state, selectedRow(state))
        val draft = prepareCompanionDisplayDraft(selected, TransferDisplayPatch.Add(selected.selectedTracks.toList()), selected.selectedTrackRevision, id(2))
        assertEquals(listOf("track:missing"), (draft.patch as TransferDisplayPatch.Add).trackIds)
        assertEquals(7L, draft.expectedRevision)
    }

    @Test fun relationshipSelectionStillRequiresBothReadAndTheRelevantWriteGrant() {
        for (selection in listOf(CompanionTrackSelection.PLAYLIST_ADD, CompanionTrackSelection.DISPLAY_ADD, CompanionTrackSelection.DISPLAY_REMOVE)) {
            val state = trackState(selection)
            val write = if (selection == CompanionTrackSelection.PLAYLIST_ADD) TransferCapability.WRITE_PLAYLISTS else TransferCapability.WRITE_DISPLAY_METADATA
            for (capabilities in listOf(setOf(write), setOf(TransferCapability.READ_LIBRARY))) {
                issue(TransferIssue.PERMISSION_DENIED) { toggleCompanionTrack(state.copy(connection = connection(capabilities)), selectedRow(state)) }
            }
            issue(TransferIssue.PERMISSION_DENIED) { toggleCompanionTrack(state.copy(connection = connection(phase = CompanionConnectionPhase.OFF)), selectedRow(state)) }
        }
    }

    @Test fun relationshipSelectionRejectsAClonedRowAndStaleTargetSessionOrFence() {
        val state = trackState(CompanionTrackSelection.DISPLAY_REMOVE)
        issue(TransferIssue.NOT_FOUND) { toggleCompanionTrack(state, unavailable()) }
        issue(TransferIssue.CONFLICT) { toggleCompanionTrack(state.copy(selectedTrackRevision = TransferLibraryRevision(7L, 4L)), selectedRow(state)) }
        val group = checkNotNull(state.group)
        issue(TransferIssue.WRONG_TARGET) {
            toggleCompanionTrack(state.copy(group = CompanionGroupSelection(TransferFingerprint.digest(byteArrayOf(9)), group.state, id(1))), selectedRow(state))
        }
        issue(TransferIssue.CONFLICT) {
            toggleCompanionTrack(state.copy(group = CompanionGroupSelection(target, group.state, id(9))), selectedRow(state))
        }
        issue(TransferIssue.CONFLICT) {
            toggleCompanionTrack(state.copy(group = CompanionGroupSelection(target,
                TransferDisplayState(TransferDisplayKind.ALBUM, "album:other", 7L, fence, "", 20001L), id(1))), selectedRow(state))
        }
    }

    @Test fun displayDraftRejectsAChangedSelectionFenceSessionOrOrderedBatch() {
        val base = trackState(CompanionTrackSelection.DISPLAY_REMOVE)
        val selected = toggleCompanionTrack(base, selectedRow(base))
        val patch = TransferDisplayPatch.Remove(selected.selectedTracks.toList())
        issue(TransferIssue.CONFLICT) { prepareCompanionDisplayDraft(selected, patch, TransferLibraryRevision(7L, 4L), id(2)) }
        issue(TransferIssue.CONFLICT) { prepareCompanionDisplayDraft(selected.copy(trackSelection = CompanionTrackSelection.DISPLAY_ADD), patch, fence, id(2)) }
        issue(TransferIssue.CONFLICT) { prepareCompanionDisplayDraft(selected, TransferDisplayPatch.Remove(listOf("track:other")), fence, id(2)) }
        val group = checkNotNull(selected.group)
        issue(TransferIssue.CONFLICT) {
            prepareCompanionDisplayDraft(selected.copy(group = CompanionGroupSelection(target, group.state, id(9))), patch, fence, id(2))
        }
    }

    @Test fun selectionLimitBoundsOneMutationBatchWithoutCappingTheLibrary() {
        val selectedIds = (0 until TransferLimits.PAGE_SIZE).map { "track:$it" }.toSet()
        val state = trackState(CompanionTrackSelection.PLAYLIST_ADD).copy(selectedTracks = selectedIds, selectedTrackRevision = fence)
        assertEquals(20001L, checkNotNull(state.pageWindow.current).page.totalCount)
        issue(TransferIssue.QUOTA_EXCEEDED) { toggleCompanionTrack(state, selectedRow(state)) }
        val included = TransferCatalogRow.Track("track:0", "", "", "", null, false)
        val includedState = trackState(CompanionTrackSelection.PLAYLIST_ADD, listOf(included)).copy(selectedTracks = selectedIds, selectedTrackRevision = fence)
        assertEquals(99, toggleCompanionTrack(includedState, included).selectedTracks.size)
    }

    @Test fun playlistAddRejectsAnOldCachedFenceOrChangedOrderedBatch() {
        val state = trackState(CompanionTrackSelection.PLAYLIST_ADD)
        val selected = toggleCompanionTrack(state, selectedRow(state))
        issue(TransferIssue.CONFLICT) { prepareCompanionPlaylistDraft(selected.copy(selectedTrackRevision = TransferLibraryRevision(7L, 4L)), TransferPlaylistEdit.Add(listOf("track:missing")), id(2)) }
        issue(TransferIssue.CONFLICT) { prepareCompanionPlaylistDraft(selected, TransferPlaylistEdit.Add(listOf("track:other")), id(2)) }
    }

    @Test fun parentCancellationIsRethrownAsTheOriginalControlFlowObject() {
        val original = CancellationException("Fixture parent cancellation")
        try {
            companionVisibleIssue(original)
            fail("Expected original cancellation")
        } catch (observed: CancellationException) { assertSame(original, observed) }
    }

    @Test fun ordinaryStableIssuesRetainTheirCodeWithoutProviderDetails() {
        assertEquals(TransferIssue.CONFLICT, companionVisibleIssue(TransferException(TransferIssue.CONFLICT)))
        assertEquals(TransferIssue.INVALID_INPUT, companionVisibleIssue(IllegalArgumentException("Fixture invalid input")))
    }

    private fun issue(expected: TransferIssue, action: () -> Unit) {
        try {
            action()
            fail("Expected a rule issue")
        } catch (error: TransferException) { assertEquals(expected, error.issue) }
    }
}
