package io.github.onlinemo.meloxwatch.ui.playlists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.data.library.LibraryAvailability
import io.github.onlinemo.meloxwatch.data.library.LibrarySource
import io.github.onlinemo.meloxwatch.ui.watch.WatchActionRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchCheckRow

/** Adds rows to the shared watch list so keyboard, round edges and the last control stay reachable. */
fun LazyListScope.playlistPickerContent(
    state: PlaylistEntryUiState,
    model: PlaylistEntryViewModel,
    onSources: () -> Unit,
    onReauthorize: (LibrarySource) -> Unit,
) {
    item(key = "playlist-entry:state") { PlaylistEntryStatus(state, model) }
    when (state.screen) {
        PlaylistPickerScreen.FOLDER, PlaylistPickerScreen.IMPORT_ROOT -> {
            item(key = "playlist-entry:folder-purpose") {
                EntryText(stringResource(if (state.screen == PlaylistPickerScreen.IMPORT_ROOT)
                    R.string.playlist_entry_import_root else R.string.playlist_entry_folder_heading), primary = true)
                if (state.screen == PlaylistPickerScreen.IMPORT_ROOT) EntryText(stringResource(R.string.playlist_entry_import_root_explanation))
                state.folder?.let { folder ->
                    EntryText(folder.name ?: stringResource(R.string.library_unnamed_folder), primary = true)
                    EntryButton(stringResource(R.string.playlist_entry_use_folder), !state.loading && !state.saving, model::selectFolder)
                    EntryButton(stringResource(R.string.library_parent_folder), !state.loading && !state.saving, model::parentFolder)
                }
            }
            items(state.folders?.folders.orEmpty(), key = { "playlist-entry:folder:${it.nodeId}" }) { folder ->
                EntryButton(folder.name ?: stringResource(R.string.library_unnamed_folder), !state.loading && !state.saving) {
                    model.browseFolder(folder)
                }
            }
            item(key = "playlist-entry:folder-paging") {
                if (state.folders?.folders?.isEmpty() == true && !state.loading) EntryText(stringResource(R.string.playlist_entry_no_indexed_folders))
                EntryButton(stringResource(R.string.playlist_first_page), !state.loading && !state.saving, model::firstFolders)
                EntryButton(stringResource(R.string.playlist_next_page), !state.loading && !state.saving && state.folders?.nextNodeId != null, model::nextFolders)
                EntryButton(stringResource(R.string.library_sources), !state.loading && !state.saving, onSources)
                EntryButton(stringResource(R.string.playlist_cancel), !state.saving) { model.closePicker() }
            }
        }
        PlaylistPickerScreen.REPLACEMENT -> {
            item(key = "playlist-entry:replacement-search") {
                EntryText(stringResource(R.string.playlist_entry_replacement_heading), primary = true)
                EntryText(state.recovery?.member?.reference?.titleHint ?: stringResource(R.string.playlist_unnamed_track))
                EntryText(stringResource(R.string.playlist_entry_replacement_explanation))
                val keyboard = LocalSoftwareKeyboardController.current
                OutlinedTextField(value = state.search, onValueChange = model::changeReplacementSearch,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    label = { Text(stringResource(R.string.library_search_hint)) }, enabled = !state.saving, maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { model.searchReplacements(); keyboard?.hide() }))
                EntryButton(stringResource(R.string.library_search_submit), !state.loading && !state.saving) {
                    model.searchReplacements(); keyboard?.hide()
                }
            }
            items(state.replacements?.tracks.orEmpty(), key = { "playlist-entry:replacement:${it.trackKey.trackId}" }) { track ->
                EntryButton(track.title, !state.loading && !state.saving) { model.previewReplacement(track) }
                track.metadata.artist?.let { EntryText(it) }
                EntryText(stringResource(entryAvailabilityText(track.availability)))
            }
            item(key = "playlist-entry:replacement-paging") {
                if (state.replacements?.tracks?.isEmpty() == true && !state.loading) EntryText(stringResource(R.string.library_empty))
                EntryButton(stringResource(R.string.playlist_previous_page), !state.loading && !state.saving &&
                    state.replacements?.previousCursor != null, model::previousReplacements)
                EntryButton(stringResource(R.string.playlist_next_page), !state.loading && !state.saving &&
                    state.replacements?.nextCursor != null, model::nextReplacements)
                EntryButton(stringResource(R.string.playlist_cancel), !state.saving) { model.closePicker() }
            }
        }
        PlaylistPickerScreen.REASSOCIATION -> item(key = "playlist-entry:reassociation-confirmation") {
            val preview = state.reassociation
            EntryText(stringResource(R.string.playlist_entry_reassociation_heading), primary = true)
            if (preview != null) {
                EntryText(stringResource(R.string.playlist_entry_original_member,
                    preview.target.member.ordinal + 1L, preview.target.member.reference.titleHint ?: stringResource(R.string.playlist_unnamed_track)))
                EntryText(stringResource(R.string.playlist_entry_replacement_member,
                    preview.replacementTitle.ifBlank { stringResource(R.string.playlist_unnamed_track) }), primary = true)
                EntryText(stringResource(R.string.playlist_entry_reassociation_impact))
                if (state.previewStale) EntryText(stringResource(R.string.playlist_entry_preview_changed), error = true)
                WatchCheckRow(stringResource(if (state.reassociationConfirmed) R.string.playlist_entry_reassociation_checked
                    else R.string.playlist_entry_reassociation_unchecked), state.reassociationConfirmed,
                    !state.saving && !state.loading && !state.previewStale, model::confirmReassociationChoice)
                EntryButton(stringResource(R.string.playlist_entry_confirm_reassociation), !state.loading && !state.saving &&
                    !state.previewStale && state.reassociationConfirmed, model::confirmReassociation)
            }
            EntryButton(stringResource(R.string.playlist_entry_choose_another_track), !state.loading && !state.saving, model::chooseAnotherReplacement)
            EntryButton(stringResource(R.string.playlist_cancel), !state.saving) { model.closePicker() }
        }
        PlaylistPickerScreen.REAUTHORIZE -> item(key = "playlist-entry:reauthorization") {
            EntryText(stringResource(R.string.playlist_reauthorize), primary = true)
            state.reauthorization?.let { source ->
                EntryText(source.displayName ?: stringResource(R.string.library_unknown))
                EntryText(stringResource(R.string.playlist_entry_reauthorization_explanation))
                EntryButton(stringResource(R.string.playlist_entry_open_reauthorization), !state.loading && !state.saving) {
                    model.openReauthorization(onReauthorize)
                }
            }
            EntryButton(stringResource(R.string.library_sources), !state.loading && !state.saving, onSources)
            EntryButton(stringResource(R.string.playlist_cancel), !state.saving) { model.closePicker() }
        }
        PlaylistPickerScreen.NONE -> Unit
    }
}

@Composable
fun PlaylistEntryStatus(state: PlaylistEntryUiState, model: PlaylistEntryViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.loading || state.addPreparing) EntryText(stringResource(R.string.playlist_entry_preparing))
        if (state.saving) EntryText(stringResource(R.string.playlist_entry_saving))
        if (state.pickerPending || state.documentRequest != null) EntryText(stringResource(R.string.playlist_entry_picker_pending))
        state.issue?.let { issue ->
            EntryText(stringResource(entryIssueText(issue)), error = true)
            EntryButton(stringResource(R.string.playlist_entry_retry), !state.loading && !state.saving, model::retryCleanup)
        }
        state.notice?.let { notice ->
            EntryText(stringResource(when (notice) {
                PlaylistEntryNotice.PICKER_CANCELLED -> R.string.playlist_entry_picker_cancelled
                PlaylistEntryNotice.NO_PICKER -> R.string.playlist_entry_no_picker
                PlaylistEntryNotice.REASSOCIATED -> R.string.playlist_entry_reassociation_completed
            }))
            EntryButton(stringResource(R.string.playlist_entry_dismiss), true, model::clearNotice)
        }
        state.queueIssue?.let {
            EntryText(stringResource(entryIssueText(it)), error = true)
            EntryButton(stringResource(R.string.playback_reload_queue), true, model::retryQueueRead)
        }
    }
}

@Composable
fun PlaylistTrackControls(state: PlaylistEntryUiState, model: PlaylistEntryViewModel,
    onAdd: () -> Unit, onPlaylists: () -> Unit, canAdd: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.favoriteIssue?.let { EntryText(stringResource(entryIssueText(it)), error = true) }
        if (state.favoriteIssue != null && (state.favoriteReference == null || state.favorite == null)) {
            EntryButton(stringResource(R.string.playlist_entry_retry), !state.favoritePending, model::retryFavoriteRead)
        }
        if (state.favoriteReference == null) EntryText(stringResource(R.string.playlist_entry_identity_required))
        EntryButton(stringResource(if (state.favorite == true) R.string.playlist_unfavorite else R.string.playlist_favorite),
            state.favorite != null && !state.favoritePending, model::toggleFavorite)
        EntryButton(stringResource(R.string.playlist_add_to_playlist), canAdd && state.favoriteReference != null && !state.addPreparing, onAdd)
        EntryButton(stringResource(R.string.playlist_entry_browse), true, onPlaylists)
    }
}

@Composable
private fun EntryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    WatchActionRow(label, enabled, onClick = onClick)
}

@Composable
private fun EntryText(text: String, primary: Boolean = false, error: Boolean = false) {
    Text(text, modifier = Modifier.fillMaxWidth(),
        style = if (primary) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else if (primary) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun entryAvailabilityText(value: LibraryAvailability): Int = when (value) {
    LibraryAvailability.AVAILABLE -> R.string.library_available
    LibraryAvailability.UNKNOWN -> R.string.library_access_unknown
    LibraryAvailability.NEEDS_AUTHORIZATION -> R.string.library_permission_revoked
    LibraryAvailability.UNAVAILABLE -> R.string.library_source_unavailable
    LibraryAvailability.MISSING -> R.string.library_file_missing
    LibraryAvailability.REMOVED -> R.string.library_source_removed
}

private fun entryIssueText(issue: PlaylistUiIssue): Int = when (issue) {
    PlaylistUiIssue.BUSY -> R.string.playlist_error_busy
    PlaylistUiIssue.CONFLICT -> R.string.playlist_error_conflict
    PlaylistUiIssue.INVALID_NAME -> R.string.playlist_error_name
    PlaylistUiIssue.INVALID_POSITION -> R.string.playlist_error_position
    PlaylistUiIssue.INVALID_KEYWORD -> R.string.playlist_error_keyword
    PlaylistUiIssue.INVALID_INPUT -> R.string.playlist_error_input
    PlaylistUiIssue.NOT_FOUND -> R.string.playlist_error_not_found
    PlaylistUiIssue.AUTOMATIC_MEMBERS -> R.string.playlist_error_automatic
    PlaylistUiIssue.VIEW_EXPIRED -> R.string.playlist_error_expired
    PlaylistUiIssue.SOURCE_CHANGED -> R.string.playlist_error_changed
    PlaylistUiIssue.SOURCE_UNAVAILABLE -> R.string.playlist_error_source
    PlaylistUiIssue.INCOMPLETE_SCAN -> R.string.playlist_error_incomplete
    PlaylistUiIssue.STORAGE_FULL -> R.string.playlist_error_storage_full
    PlaylistUiIssue.STORAGE_UNAVAILABLE -> R.string.playlist_error_storage
    PlaylistUiIssue.RECOVERY_REQUIRED -> R.string.playlist_error_recovery
    PlaylistUiIssue.INVALID_DOCUMENT -> R.string.playlist_error_document
    PlaylistUiIssue.UNSUPPORTED_DOCUMENT -> R.string.playlist_error_document_unsupported
    PlaylistUiIssue.DOCUMENT_TOO_LARGE -> R.string.playlist_error_document_large
    PlaylistUiIssue.DOCUMENT_READ -> R.string.playlist_error_document_read
    PlaylistUiIssue.DOCUMENT_WRITE -> R.string.playlist_error_document_write
}
