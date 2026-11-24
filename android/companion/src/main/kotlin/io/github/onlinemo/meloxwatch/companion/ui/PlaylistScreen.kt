package io.github.onlinemo.meloxwatch.companion.ui

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import io.github.onlinemo.meloxwatch.companion.R
import io.github.onlinemo.meloxwatch.companion.data.CompanionEditPhase
import io.github.onlinemo.meloxwatch.companion.data.CompanionPlaylistDraft
import io.github.onlinemo.meloxwatch.companion.data.companionRowIdentity
import io.github.onlinemo.meloxwatch.data.transfer.TransferCapability
import io.github.onlinemo.meloxwatch.data.transfer.TransferCatalogKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferCatalogRow
import io.github.onlinemo.meloxwatch.data.transfer.TransferPlaylistEdit

internal fun LazyListScope.playlistItems(state: CompanionUiState, model: CompanionViewModel) {
    item(key = "playlist-create") {
        var name by remember { mutableStateOf("") }
        MeloXCard {
            MeloXText(stringResource(R.string.create_playlist), heading = true)
            MeloXText(stringResource(R.string.playlist_authority), secondary = true)
            if (!state.allowed(TransferCapability.WRITE_PLAYLISTS)) MeloXText(stringResource(R.string.playlist_permission_needed), secondary = true)
            MeloXField(stringResource(R.string.playlist_name), name, { name = it }, maximum = 160)
            MeloXAction(stringResource(R.string.save_create_draft), { model.createPlaylist(name) },
                enabled = state.available && state.allowed(TransferCapability.WRITE_PLAYLISTS) && name.isNotBlank(), prominent = true)
        }
    }
    item(key = "playlist-target") {
        var search by remember(state.pageWindow.search) { mutableStateOf(state.pageWindow.search) }
        val selected = state.playlist
        var name by remember(selected?.row?.playlistId, selected?.row?.revision) { mutableStateOf(selected?.row?.name.orEmpty()) }
        MeloXCard {
            if (selected == null) {
                MeloXText(stringResource(R.string.playlists), heading = true)
                MeloXText(stringResource(R.string.playlist_read_permission), secondary = true)
                MeloXField(stringResource(R.string.search), search, { search = it })
                MeloXAction(stringResource(R.string.search_on_watch), { model.refresh(TransferCatalogKind.PLAYLISTS, search, null) },
                    enabled = state.available && state.allowed(TransferCapability.READ_LIBRARY))
            } else {
                MeloXText(selected.row.name.ifBlank { stringResource(R.string.unnamed_playlist) }, heading = true)
                MeloXText(stringResource(R.string.playlist_id, selected.row.playlistId), secondary = true, mono = true)
                MeloXText(stringResource(R.string.device_revision, selected.row.revision), secondary = true)
                MeloXText(stringResource(R.string.track_count, selected.row.memberCount), secondary = true)
                if (selected.row.automatic) MeloXText(stringResource(R.string.automatic_playlist_locked), secondary = true)
                val canWrite = state.available && state.allowed(TransferCapability.WRITE_PLAYLISTS)
                MeloXField(stringResource(R.string.new_playlist_name), name, { name = it }, enabled = canWrite, maximum = 160)
                MeloXAction(stringResource(R.string.save_rename_draft), { model.renamePlaylist(name) }, enabled = canWrite && name.isNotBlank())
                if (!selected.row.automatic) {
                    MeloXAction(stringResource(R.string.choose_tracks_for_playlist), model::choosePlaylistTracks,
                        enabled = canWrite && state.allowed(TransferCapability.READ_LIBRARY))
                }
                MeloXAction(stringResource(R.string.save_delete_draft), model::deletePlaylist, enabled = canWrite)
                MeloXAction(stringResource(R.string.back_to_playlists), model::showPlaylists)
            }
        }
    }
    pageItems(state, model)
    val page = state.pageWindow.current?.page
    if (page != null) items(page.rows, key = ::companionRowIdentity) { row ->
        when (row) {
            is TransferCatalogRow.Playlist -> PlaylistRowView(row, state, model)
            is TransferCatalogRow.Member -> PlaylistMemberView(row, state, model)
            is TransferCatalogRow.Track -> TrackView(row, state, model)
            is TransferCatalogRow.Group -> GroupView(row, state, model)
        }
    }
    state.local?.playlist?.let { draft -> item(key = "durable-playlist-draft") { PlaylistDraftView(draft, state, model) } }
}

@Composable
private fun PlaylistMemberView(row: TransferCatalogRow.Member, state: CompanionUiState, model: CompanionViewModel) {
    var destination by remember(row.memberId, state.playlist?.row?.revision) { mutableStateOf((row.ordinal + 1L).toString()) }
    MeloXCard {
        MeloXText(row.titleHint.ifBlank { stringResource(R.string.untitled_track) })
        MeloXText(row.artistHint, secondary = true)
        MeloXText(stringResource(R.string.member_position, row.ordinal + 1L), secondary = true)
        if (!row.available) MeloXText(stringResource(R.string.track_unavailable), secondary = true)
        if (state.playlist != null) {
            val enabled = state.available && state.allowed(TransferCapability.WRITE_PLAYLISTS)
            MeloXField(stringResource(R.string.destination_position), destination, { destination = it }, enabled = enabled, keyboard = KeyboardType.Number, maximum = 19)
            MeloXText(stringResource(R.string.position_is_whole_playlist), secondary = true)
            MeloXAction(stringResource(R.string.save_move_draft), { model.moveMember(row.memberId, destination) }, enabled = enabled)
            if (!state.playlist.row.automatic) {
                MeloXAction(stringResource(R.string.save_remove_member_draft), { model.removeMember(row.memberId) }, enabled = enabled)
            }
        }
    }
}

@Composable
private fun PlaylistDraftView(draft: CompanionPlaylistDraft, state: CompanionUiState, model: CompanionViewModel) {
    var submit by remember(draft.operationId, draft.phase) { mutableStateOf(false) }
    var discard by remember(draft.operationId) { mutableStateOf(false) }
    val summary = playlistEditSummary(draft.edit)
    val target = draft.target.forLocalDisplay().chunked(8).joinToString(" ")
    MeloXCard {
        MeloXText(stringResource(R.string.playlist_draft), heading = true)
        MeloXText(editPhaseName(draft.phase))
        MeloXText(stringResource(R.string.playlist_name_value, draft.playlistName))
        MeloXText(stringResource(R.string.playlist_id, draft.playlistId), secondary = true, mono = true)
        MeloXText(stringResource(R.string.upload_bound_target, target), secondary = true, mono = true)
        MeloXText(stringResource(R.string.device_revision, draft.expectedRevision), secondary = true)
        MeloXText(summary)
        val edit = draft.edit
        if (edit is TransferPlaylistEdit.Add) edit.trackIds.forEach { MeloXText(it, secondary = true, mono = true) }
        if (draft.phase == CompanionEditPhase.UNCERTAIN) MeloXText(stringResource(R.string.exact_retry_required), secondary = true)
        if (draft.phase == CompanionEditPhase.CONFLICT) {
            MeloXText(stringResource(R.string.conflict_reload_keeps_draft), secondary = true)
            MeloXAction(stringResource(R.string.reload_draft_target), model::reloadPlaylistDraftTarget,
                enabled = state.available && state.allowed(TransferCapability.READ_LIBRARY) && state.connection.target?.fingerprint == draft.target)
        }
        draft.receipt?.let { ReceiptView(it) }
        val sendable = draft.phase in setOf(CompanionEditPhase.DRAFT, CompanionEditPhase.UNCERTAIN)
        MeloXAction(stringResource(if (draft.phase == CompanionEditPhase.UNCERTAIN) R.string.retry_same_operation else R.string.review_and_submit), { submit = true },
            enabled = state.available && state.allowed(TransferCapability.WRITE_PLAYLISTS) && state.connection.target?.fingerprint == draft.target && sendable, prominent = true)
        MeloXAction(stringResource(R.string.discard_draft), { discard = true }, enabled = state.available)
    }
    if (submit) MeloXConfirmation(stringResource(R.string.submit_playlist_title),
        stringResource(R.string.submit_playlist_message, draft.playlistName, summary, target), stringResource(R.string.apply_to_watch),
    onConfirm = {
            submit = false
            model.submitPlaylist()
        }, onDismiss = {
            submit = false
        })
    if (discard) MeloXConfirmation(stringResource(R.string.discard_draft),
        stringResource(if (draft.phase == CompanionEditPhase.UNCERTAIN) R.string.discard_uncertain_message else R.string.discard_draft_message), stringResource(R.string.discard_draft),
    onConfirm = {
            discard = false
            model.discardPlaylistDraft()
        }, onDismiss = {
            discard = false
        })
}

@Composable
private fun playlistEditSummary(edit: TransferPlaylistEdit): String = when (edit) {
    is TransferPlaylistEdit.Create -> stringResource(R.string.edit_playlist_create, edit.name)
    is TransferPlaylistEdit.Rename -> stringResource(R.string.edit_playlist_rename, edit.name)
    is TransferPlaylistEdit.Delete -> stringResource(R.string.edit_playlist_delete)
    is TransferPlaylistEdit.Add -> companionPlural(R.plurals.edit_playlist_add, edit.trackIds.size.toLong())
    is TransferPlaylistEdit.Remove -> stringResource(R.string.edit_playlist_remove, edit.memberId)
    is TransferPlaylistEdit.Move -> stringResource(R.string.edit_playlist_move, edit.memberId, edit.destinationOrdinal + 1L)
}
