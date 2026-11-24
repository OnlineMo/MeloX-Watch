package io.github.onlinemo.meloxwatch.ui.playlists

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistKind
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMember
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistOperationResult
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistQueueAvailability
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistSnapshot
import io.github.onlinemo.meloxwatch.model.QueueMode
import io.github.onlinemo.meloxwatch.playback.PlaylistQueueAction
import io.github.onlinemo.meloxwatch.playback.PlaylistQueueCommand
import io.github.onlinemo.meloxwatch.ui.watch.WatchActionRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchCheckRow

/** 放在已处理圆/方形状、系统inset及首尾中央滚动空间的共享WatchList内。 */
fun LazyListScope.playlistsContent(
    state: PlaylistUiState,
    model: PlaylistsViewModel,
    queue: PlaylistQueueBinding?,
    mode: QueueMode?,
    commandPending: Boolean,
    onPlay: (PlaylistQueueCommand) -> Unit,
    onChooseFolder: (FolderPlaylistPurpose) -> Unit,
    onChooseImport: (PlaylistTransferFormat, PlaylistTransferEncoding) -> Unit,
    onChooseExport: (PlaylistTransferFormat, PlaylistTransferEncoding) -> Unit,
    onRecover: (PlaylistRecoveryTarget) -> Unit,
    onAddToPlaylist: (PlaylistMember) -> Unit,
) {
    item(key = "playlists:status") { PlaylistStatus(state, model) }
    when (state.screen) {
        PlaylistScreen.CATALOG, PlaylistScreen.ADD_TARGETS -> {
            item(key = "playlists:catalog-actions") { PlaylistCatalogActions(state, model, onChooseImport) }
            val snapshots = state.catalogPage?.playlists.orEmpty()
            items(snapshots, key = { "playlist:${it.playlistId}" }) { snapshot ->
                PlaylistCatalogRow(snapshot, state, model)
            }
            item(key = "playlists:catalog-paging") {
                if (snapshots.isEmpty() && !state.loading && state.issue == null) PlaylistText(stringResource(R.string.playlist_no_playlists))
                PlaylistButton(stringResource(R.string.playlist_first_page), !state.loading && !state.saving, model::firstCatalogPage)
                PlaylistButton(stringResource(R.string.playlist_next_page), !state.loading && !state.saving &&
                    state.catalogPage?.nextCursor != null, model::nextCatalogPage)
            }
        }
        PlaylistScreen.DETAILS -> {
            item(key = "playlists:details-actions") {
                PlaylistDetailsActions(state, model, queue, mode, commandPending)
            }
            items(state.rows, key = { "member:${it.member.memberId}" }) { row ->
                PlaylistButton(stringResource(R.string.playlist_member_label, row.member.ordinal + 1L,
                    row.title ?: stringResource(R.string.playlist_unnamed_track)), state.canUseView) { model.selectMember(row) }
                PlaylistText(stringResource(playlistAvailabilityText(row.availability)))
            }
            item(key = "playlists:member-paging") {
                if (state.rows.isEmpty() && !state.loading && state.issue == null) PlaylistText(stringResource(R.string.playlist_empty))
                val count = state.view?.snapshot?.memberCount
                if (count != null && state.rows.isNotEmpty()) PlaylistText(stringResource(R.string.playlist_page_range,
                    state.pageStart + 1L, state.pageStart + state.rows.size, count))
                PlaylistButton(stringResource(R.string.playlist_previous_page), state.hasPrevious, model::previousMemberPage)
                PlaylistButton(stringResource(R.string.playlist_next_page), state.hasNext, model::nextMemberPage)
            }
        }
        PlaylistScreen.MEMBER -> item(key = "playlists:member-actions") {
            PlaylistMemberActions(state, model, queue, mode, commandPending, onRecover, onAddToPlaylist)
        }
        PlaylistScreen.NAME -> item(key = "playlists:name") { PlaylistNameEditor(state, model) }
        PlaylistScreen.DELETE -> item(key = "playlists:delete-confirmation") {
            PlaylistText(playlistTitle(state.view?.snapshot), primary = true)
            PlaylistText(stringResource(R.string.playlist_delete_explanation))
            PlaylistButton(stringResource(R.string.playlist_confirm_delete), !state.saving && !state.loading, model::confirmDelete)
            PlaylistButton(stringResource(R.string.playlist_cancel), !state.saving, model::returnToDetails)
        }
        PlaylistScreen.REMOVE -> item(key = "playlists:remove-confirmation") {
            PlaylistText(state.selected?.title ?: stringResource(R.string.playlist_unnamed_track), primary = true)
            PlaylistText(stringResource(R.string.playlist_remove_explanation))
            PlaylistButton(stringResource(R.string.playlist_confirm_remove), !state.saving && !state.loading, model::confirmRemove)
            PlaylistButton(stringResource(R.string.playlist_cancel), !state.saving, model::returnToDetails)
        }
        PlaylistScreen.MOVE -> item(key = "playlists:move") { PlaylistMoveEditor(state, model) }
        PlaylistScreen.FOLDER -> item(key = "playlists:folder") { PlaylistFolderEditor(state, model, onChooseFolder) }
        PlaylistScreen.KEYWORD -> item(key = "playlists:keyword") { PlaylistKeywordEditor(state, model) }
        PlaylistScreen.ADD_CONFIRM -> item(key = "playlists:add-confirmation") {
            PlaylistText(playlistTitle(state.addDraft?.target), primary = true)
            val count = state.addCount ?: 0L
            PlaylistText(pluralStringResource(R.plurals.playlist_add_explanation, playlistPluralQuantity(count), count))
            PlaylistButton(stringResource(R.string.playlist_confirm_add), !state.saving && !state.loading, model::confirmAdd)
            PlaylistButton(stringResource(R.string.playlist_choose_another_playlist), !state.saving, model::returnToAddTargets)
        }
        PlaylistScreen.IMPORT -> {
            item(key = "playlists:import-confirmation") { PlaylistImportControls(state, model, onChooseImport) }
            items(state.importPage?.rows.orEmpty(), key = { "import:${it.ordinal}" }) { row ->
                PlaylistText(stringResource(R.string.playlist_member_label, row.ordinal + 1L,
                    row.title ?: stringResource(R.string.playlist_unnamed_track)), primary = true)
                row.artist?.let { PlaylistText(it) }
                PlaylistText(stringResource(playlistAvailabilityText(row.availability)))
                if (row.issue != PlaylistImportMemberIssue.NONE) PlaylistText(stringResource(playlistImportIssueText(row.issue)))
            }
            item(key = "playlists:import-paging") {
                PlaylistButton(stringResource(R.string.playlist_previous_page), !state.loading && !state.saving &&
                    state.importPage?.firstOrdinal?.let { it > 0L } == true, model::previousImportPage)
                PlaylistButton(stringResource(R.string.playlist_next_page), !state.loading && !state.saving &&
                    state.importPage?.nextOrdinal != null, model::nextImportPage)
            }
        }
        PlaylistScreen.EXPORT -> item(key = "playlists:export") { PlaylistExportControls(state, model, onChooseExport) }
    }
    if (state.playDraft != null) item(key = "playlists:play-confirmation") {
        PlaylistPlayConfirmation(state, model, commandPending, queue != null && mode != null, onPlay)
    }
}

/** 接线层调用一次即可；页面隐藏和系统STOP均取消页面任务，播放仍归服务所有。 */
@Composable
fun PlaylistPageLifecycle(model: PlaylistsViewModel, visible: Boolean) {
    LifecycleStartEffect(model, visible) {
        if (visible) model.onVisible()
        onStopOrDispose { if (visible) model.onHidden() }
    }
}

@Composable
private fun PlaylistStatus(state: PlaylistUiState, model: PlaylistsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PlaylistText(stringResource(R.string.playlist_heading), primary = true)
        if (state.loading) PlaylistText(stringResource(R.string.playlist_loading))
        if (state.saving) PlaylistText(stringResource(R.string.playlist_saving))
        if (state.showingPreviousData) PlaylistText(stringResource(R.string.playlist_previous_data))
        if (state.hasNewData) PlaylistText(stringResource(R.string.playlist_new_data))
        if (state.previewStale) PlaylistText(stringResource(R.string.playlist_error_changed), error = true)
        state.issue?.let { PlaylistText(stringResource(playlistIssueText(it)), error = true) }
        state.lastCommit?.let { commit ->
            val result = when (commit.result) {
                PlaylistOperationResult.CREATED -> R.string.playlist_created
                PlaylistOperationResult.DELETED -> R.string.playlist_deleted
                PlaylistOperationResult.UNCHANGED -> R.string.playlist_unchanged
                else -> R.string.playlist_saved
            }
            PlaylistText(stringResource(result))
            if (commit.sameNameCount > 0L) PlaylistText(pluralStringResource(R.plurals.playlist_same_name_result,
                playlistPluralQuantity(commit.sameNameCount), commit.sameNameCount))
            if (commit.addedCount > 0L || commit.duplicateCount > 0L) PlaylistText(stringResource(R.string.playlist_add_result,
                commit.addedCount, commit.duplicateCount))
        }
        PlaylistButton(stringResource(R.string.playlist_all_playlists), !state.saving, model::showCatalog)
        PlaylistButton(stringResource(R.string.playlist_reload), !state.saving, model::reload)
    }
}

@Composable
private fun PlaylistCatalogActions(state: PlaylistUiState, model: PlaylistsViewModel,
    onChooseImport: (PlaylistTransferFormat, PlaylistTransferEncoding) -> Unit) {
    if (state.screen == PlaylistScreen.ADD_TARGETS) {
        state.addCount?.let { PlaylistText(pluralStringResource(R.plurals.playlist_choose_target_count,
            playlistPluralQuantity(it), it), primary = true) }
        PlaylistText(stringResource(R.string.playlist_manual_targets_only))
        return
    }
    val enabled = !state.loading && !state.saving && !state.showingPreviousData && state.catalogPage != null
    val count = state.overview?.favoriteCount
    PlaylistButton(if (count == null) stringResource(R.string.playlist_favorites) else
        pluralStringResource(R.plurals.playlist_favorites_count, playlistPluralQuantity(count), count), enabled, model::openFavorites)
    PlaylistButton(stringResource(R.string.playlist_create), enabled, model::beginCreate)
    PlaylistButton(stringResource(R.string.playlist_create_folder), enabled, model::beginFolderCreate)
    PlaylistButton(stringResource(R.string.playlist_create_keyword), enabled, model::beginKeywordCreate)
    PlaylistImportButtons(enabled) { format, encoding ->
        model.beginImport(format, encoding)
        onChooseImport(format, encoding)
    }
}

@Composable
private fun PlaylistCatalogRow(snapshot: PlaylistSnapshot, state: PlaylistUiState, model: PlaylistsViewModel) {
    val selecting = state.screen == PlaylistScreen.ADD_TARGETS
    val enabled = !state.loading && !state.saving && !state.showingPreviousData &&
        (!selecting || snapshot.kind == PlaylistKind.ORDINARY)
    PlaylistButton(playlistTitle(snapshot), enabled) {
        if (selecting) model.chooseAddTarget(snapshot) else model.openPlaylist(snapshot)
    }
    PlaylistText(pluralStringResource(R.plurals.playlist_track_count, playlistPluralQuantity(snapshot.memberCount), snapshot.memberCount))
    PlaylistText(stringResource(if (snapshot.kind == PlaylistKind.FOLDER) R.string.playlist_folder_kind else R.string.playlist_manual_kind))
}

@Composable
private fun PlaylistDetailsActions(state: PlaylistUiState, model: PlaylistsViewModel, queue: PlaylistQueueBinding?,
    mode: QueueMode?, commandPending: Boolean) {
    PlaylistText(playlistTitle(state.view?.snapshot), primary = true)
    state.view?.snapshot?.let { PlaylistText(pluralStringResource(R.plurals.playlist_track_count,
        playlistPluralQuantity(it.memberCount), it.memberCount)) }
    val canSubmit = playlistQueueControlsEnabled(state, queue, mode, commandPending)
    PlaylistButton(stringResource(R.string.playlist_play_complete), canSubmit) {
        if (queue != null && mode != null) model.preparePlay(queue, mode, PlaylistQueueAction.REPLACE_COMPLETE_LIST, selected = false)
    }
    PlaylistButton(stringResource(R.string.playlist_insert_complete), canSubmit) {
        if (queue != null && mode != null) model.preparePlay(queue, mode, PlaylistQueueAction.INSERT_NEXT, selected = false)
    }
    if (state.view?.snapshot?.kind != PlaylistKind.FAVORITES) {
        PlaylistButton(stringResource(R.string.playlist_rename), state.canUseView, model::beginRename)
        PlaylistButton(stringResource(R.string.playlist_delete), state.canUseView, model::beginDelete)
    }
    if (state.view?.snapshot?.kind == PlaylistKind.FOLDER) {
        PlaylistText(stringResource(R.string.playlist_folder_members_explanation))
        PlaylistButton(stringResource(R.string.playlist_sync_folder), state.canUseView, model::beginFolderSync)
        PlaylistButton(stringResource(R.string.playlist_change_folder), state.canUseView, model::beginFolderRebind)
    }
    PlaylistButton(stringResource(R.string.playlist_export_json), state.canUseView) { model.beginExport(PlaylistTransferFormat.JSON) }
    PlaylistButton(stringResource(R.string.playlist_export_m3u8), state.canUseView) { model.beginExport(PlaylistTransferFormat.M3U8) }
    PlaylistButton(stringResource(R.string.playlist_export_m3u), state.canUseView) { model.beginExport(PlaylistTransferFormat.M3U) }
    if (state.canEditMembers) {
        PlaylistButton(stringResource(R.string.playlist_merge_json), true) { model.beginImport(PlaylistTransferFormat.JSON, mergeIntoCurrent = true) }
        PlaylistButton(stringResource(R.string.playlist_merge_m3u), true) { model.beginImport(PlaylistTransferFormat.M3U8, mergeIntoCurrent = true) }
    }
}

@Composable
private fun PlaylistMemberActions(state: PlaylistUiState, model: PlaylistsViewModel, queue: PlaylistQueueBinding?,
    mode: QueueMode?, commandPending: Boolean, onRecover: (PlaylistRecoveryTarget) -> Unit,
    onAddToPlaylist: (PlaylistMember) -> Unit) {
    val row = state.selected ?: return
    PlaylistText(row.title ?: stringResource(R.string.playlist_unnamed_track), primary = true)
    PlaylistText(stringResource(R.string.playlist_position, row.member.ordinal + 1L))
    PlaylistText(stringResource(playlistAvailabilityText(row.availability)))
    val canSubmit = playlistQueueControlsEnabled(state, queue, mode, commandPending)
    PlaylistButton(stringResource(R.string.playlist_play_from_member), canSubmit) {
        if (queue != null && mode != null) model.preparePlay(queue, mode, PlaylistQueueAction.REPLACE_COMPLETE_LIST, selected = true)
    }
    PlaylistButton(stringResource(R.string.playlist_insert_member), canSubmit) {
        if (queue != null && mode != null) model.preparePlay(queue, mode, PlaylistQueueAction.INSERT_NEXT, selected = true)
    }
    PlaylistButton(stringResource(R.string.playlist_insert_member_play), canSubmit) {
        if (queue != null && mode != null) model.preparePlay(queue, mode, PlaylistQueueAction.INSERT_AND_PLAY, selected = true)
    }
    if (row.member.reference.trackKey != null) {
        val favorite = state.favorite
        PlaylistButton(stringResource(if (favorite == true) R.string.playlist_unfavorite else R.string.playlist_favorite),
            state.canUseView && favorite != null && !state.favoritePending, model::toggleFavorite)
        PlaylistButton(stringResource(R.string.playlist_add_to_playlist), state.canUseView) { onAddToPlaylist(row.member) }
    }
    if (row.availability == PlaylistQueueAvailability.NEEDS_AUTHORIZATION) {
        PlaylistButton(stringResource(R.string.playlist_reauthorize), state.canUseView) {
            model.recoveryTarget(PlaylistRecoveryAction.REAUTHORIZE)?.let(onRecover)
        }
    }
    if (row.availability != PlaylistQueueAvailability.AVAILABLE) {
        PlaylistButton(stringResource(R.string.playlist_reassociate), state.canUseView) {
            model.recoveryTarget(PlaylistRecoveryAction.REASSOCIATE)?.let(onRecover)
        }
        PlaylistText(stringResource(R.string.playlist_reassociation_explanation))
    }
    if (state.canMoveMembers) PlaylistButton(stringResource(R.string.playlist_move), true, model::beginMove)
    if (state.view?.snapshot?.kind == PlaylistKind.FOLDER) PlaylistText(stringResource(R.string.playlist_folder_members_explanation))
    if (state.canEditMembers) PlaylistButton(stringResource(R.string.playlist_remove), true, model::beginRemove)
    PlaylistButton(stringResource(R.string.playlist_back_to_details), !state.saving, model::returnToDetails)
}

@Composable
private fun PlaylistNameEditor(state: PlaylistUiState, model: PlaylistsViewModel) {
    val draft = state.nameDraft ?: return
    PlaylistText(stringResource(if (draft.purpose == PlaylistNamePurpose.RENAME) R.string.playlist_rename else R.string.playlist_create), primary = true)
    if (draft.purpose == PlaylistNamePurpose.RENAME) PlaylistText(playlistTitle(state.view?.snapshot))
    PlaylistNameField(draft.name, !state.saving, model::changeName, model::saveName)
    PlaylistText(stringResource(R.string.playlist_name_explanation))
    PlaylistButton(stringResource(R.string.playlist_save), !state.saving && !state.loading, model::saveName)
    PlaylistButton(stringResource(R.string.playlist_cancel), !state.saving) {
        if (draft.purpose == PlaylistNamePurpose.RENAME) model.returnToDetails() else model.showCatalog()
    }
}

@Composable
private fun PlaylistMoveEditor(state: PlaylistUiState, model: PlaylistsViewModel) {
    val draft = state.moveDraft ?: return
    val count = state.view?.snapshot?.memberCount ?: 0L
    PlaylistText(state.selected?.title ?: draft.titleHint ?: stringResource(R.string.playlist_unnamed_track), primary = true)
    PlaylistText(stringResource(R.string.playlist_move_explanation))
    if (state.view != null && !state.loading && count == 0L) PlaylistText(stringResource(R.string.playlist_empty))
    val label = if (count > 0L) stringResource(R.string.playlist_destination, count) else stringResource(R.string.playlist_move)
    PlaylistInput(draft.destination, label, !state.saving,
        KeyboardType.Number, model::changeMoveDestination, model::saveMove)
    val position = draft.destination.toLongOrNull()
    val canMove = state.canUseView && count > 0L
    PlaylistButton(stringResource(R.string.playlist_move_up), canMove && position != null && position > 1L, model::moveUp)
    PlaylistButton(stringResource(R.string.playlist_move_down), canMove && position != null && position < count, model::moveDown)
    PlaylistButton(stringResource(R.string.playlist_save), canMove, model::saveMove)
    PlaylistButton(stringResource(R.string.playlist_cancel), !state.saving, model::returnToDetails)
}

@Composable
private fun PlaylistFolderEditor(state: PlaylistUiState, model: PlaylistsViewModel,
    onChooseFolder: (FolderPlaylistPurpose) -> Unit) {
    val draft = state.folderDraft ?: return
    val heading = when (draft.purpose) {
        FolderPlaylistPurpose.CREATE -> R.string.playlist_create_folder
        FolderPlaylistPurpose.SYNCHRONIZE -> R.string.playlist_sync_folder
        FolderPlaylistPurpose.REBIND -> R.string.playlist_change_folder
    }
    PlaylistText(stringResource(heading), primary = true)
    if (draft.purpose == FolderPlaylistPurpose.CREATE) {
        PlaylistNameField(state.nameDraft?.name.orEmpty(), !state.saving, model::changeName, model::previewFolder)
        PlaylistText(stringResource(R.string.playlist_name_explanation))
    }
    PlaylistText(draft.choice?.label ?: stringResource(R.string.playlist_choose_authorized_folder))
    if (draft.purpose != FolderPlaylistPurpose.SYNCHRONIZE) {
        PlaylistButton(stringResource(R.string.playlist_choose_folder), !state.saving && !state.loading) { onChooseFolder(draft.purpose) }
    }
    PlaylistText(stringResource(R.string.playlist_folder_sync_explanation))
    if (draft.purpose == FolderPlaylistPurpose.REBIND) PlaylistText(stringResource(R.string.playlist_rebind_explanation))
    PlaylistButton(stringResource(R.string.playlist_preview_again), !state.saving && !state.loading && draft.choice != null, model::previewFolder)
    draft.preview?.let { preview ->
        PlaylistText(stringResource(R.string.playlist_folder_preview, preview.memberCount, preview.addedCount, preview.removedCount))
        PlaylistButton(stringResource(if (draft.purpose == FolderPlaylistPurpose.REBIND) R.string.playlist_confirm_rebind else
            R.string.playlist_confirm_folder), !state.saving && !state.loading && !state.previewStale, model::confirmFolder)
    }
    PlaylistButton(stringResource(R.string.playlist_cancel), !state.saving) {
        if (draft.purpose == FolderPlaylistPurpose.CREATE) model.showCatalog() else model.returnToDetails()
    }
}

@Composable
private fun PlaylistKeywordEditor(state: PlaylistUiState, model: PlaylistsViewModel) {
    val draft = state.keywordDraft ?: return
    PlaylistText(stringResource(R.string.playlist_create_keyword), primary = true)
    PlaylistNameField(state.nameDraft?.name.orEmpty(), !state.saving, model::changeName, model::previewKeyword)
    PlaylistInput(draft.keyword, stringResource(R.string.playlist_keyword), !state.saving, KeyboardType.Text,
        model::changeKeyword, model::previewKeyword)
    PlaylistText(stringResource(R.string.playlist_keyword_explanation))
    PlaylistButton(stringResource(R.string.playlist_preview_matches), !state.loading && !state.saving, model::previewKeyword)
    draft.preview?.let { preview ->
        PlaylistText(pluralStringResource(R.plurals.playlist_keyword_count, playlistPluralQuantity(preview.memberCount), preview.memberCount))
        if (preview.requiresBroadConfirmation) PlaylistCheck(stringResource(R.string.playlist_confirm_broad_keyword),
            draft.broadMatchConfirmed, !state.saving, model::confirmBroadKeyword)
        if (preview.memberCount == 0L) PlaylistCheck(stringResource(R.string.playlist_confirm_empty_keyword),
            draft.emptyMatchConfirmed, !state.saving, model::confirmEmptyKeyword)
        val confirmed = (!preview.requiresBroadConfirmation || draft.broadMatchConfirmed) &&
            (preview.memberCount != 0L || draft.emptyMatchConfirmed)
        PlaylistButton(stringResource(R.string.playlist_create_snapshot), confirmed && !state.saving && !state.loading &&
            !state.previewStale, model::confirmKeyword)
    }
    PlaylistButton(stringResource(R.string.playlist_cancel), !state.saving, model::showCatalog)
}

@Composable
private fun PlaylistImportControls(state: PlaylistUiState, model: PlaylistsViewModel,
    onChooseImport: (PlaylistTransferFormat, PlaylistTransferEncoding) -> Unit) {
    val draft = state.importDraft ?: return
    PlaylistText(stringResource(R.string.playlist_import_heading), primary = true)
    PlaylistText(stringResource(if (draft.target == null) R.string.playlist_import_new_explanation else R.string.playlist_import_merge_explanation))
    PlaylistText(stringResource(R.string.playlist_import_permission_explanation))
    PlaylistButton(stringResource(R.string.playlist_choose_import_document), !state.loading && !state.saving) {
        onChooseImport(draft.format, draft.encoding)
    }
    if (draft.document != null) PlaylistButton(stringResource(R.string.playlist_preview_again), !state.loading && !state.saving, model::previewImportAgain)
    val preview = draft.preview
    if (preview != null) {
        PlaylistText(stringResource(R.string.playlist_import_counts, preview.memberCount, preview.matchedCount, preview.unresolvedCount))
        if (preview.ambiguousCount > 0L) PlaylistText(pluralStringResource(R.plurals.playlist_import_ambiguous_count,
            playlistPluralQuantity(preview.ambiguousCount), preview.ambiguousCount))
        if (preview.blockedReferenceCount > 0L) PlaylistText(pluralStringResource(R.plurals.playlist_import_blocked_count,
            playlistPluralQuantity(preview.blockedReferenceCount), preview.blockedReferenceCount))
        if (preview.duplicateCount > 0L) PlaylistText(pluralStringResource(R.plurals.playlist_import_duplicate_count,
            playlistPluralQuantity(preview.duplicateCount), preview.duplicateCount))
        if (draft.target == null) PlaylistNameField(draft.name, !state.saving, model::changeImportName) {}
        if (preview.unresolvedCount > 0L) PlaylistCheck(pluralStringResource(R.plurals.playlist_keep_unresolved,
            playlistPluralQuantity(preview.unresolvedCount), preview.unresolvedCount),
            draft.unresolvedConfirmed, !state.saving, model::confirmUnresolvedImport)
        PlaylistButton(stringResource(if (draft.target == null) R.string.playlist_confirm_import_new else R.string.playlist_confirm_import_merge),
            !state.loading && !state.saving && !state.previewStale && (preview.unresolvedCount == 0L || draft.unresolvedConfirmed), model::confirmImport)
    }
    PlaylistButton(stringResource(R.string.playlist_cancel), !state.saving) {
        if (state.playlistId == null) model.showCatalog() else model.returnToDetails()
    }
}

@Composable
private fun PlaylistExportControls(state: PlaylistUiState, model: PlaylistsViewModel,
    onChooseExport: (PlaylistTransferFormat, PlaylistTransferEncoding) -> Unit) {
    val draft = state.exportDraft ?: return
    PlaylistText(stringResource(R.string.playlist_export_heading), primary = true)
    PlaylistText(playlistTitle(draft.originalView.snapshot), primary = true)
    val count = draft.originalView.snapshot.memberCount
    PlaylistText(pluralStringResource(R.plurals.playlist_export_explanation, playlistPluralQuantity(count), count))
    if (draft.format != PlaylistTransferFormat.JSON) PlaylistText(stringResource(R.string.playlist_m3u_portability))
    if (state.exportReceipt != null) {
        PlaylistText(pluralStringResource(R.plurals.playlist_export_completed,
            playlistPluralQuantity(state.exportReceipt.memberCount), state.exportReceipt.memberCount))
    } else {
        PlaylistButton(stringResource(R.string.playlist_choose_export_document), !state.loading && !state.saving) {
            onChooseExport(draft.format, draft.encoding)
        }
        if (draft.destination != null) PlaylistButton(stringResource(R.string.playlist_retry_export), !state.loading && !state.saving, model::exportSelectedDocument)
    }
    PlaylistButton(stringResource(R.string.playlist_back_to_details), !state.saving, model::returnToDetails)
}

@Composable
private fun PlaylistPlayConfirmation(state: PlaylistUiState, model: PlaylistsViewModel, commandPending: Boolean, queueKnown: Boolean,
    onPlay: (PlaylistQueueCommand) -> Unit) {
    val draft = state.playDraft ?: return
    PlaylistText(draft.title ?: playlistTitle(draft.command.view.snapshot), primary = true)
    val count = if (draft.command.action == PlaylistQueueAction.REPLACE_COMPLETE_LIST || draft.command.selection == null)
        draft.command.view.snapshot.memberCount else 1L
    val text = when (draft.command.action) {
        PlaylistQueueAction.REPLACE_COMPLETE_LIST -> pluralStringResource(R.plurals.playlist_replace_queue_explanation,
            playlistPluralQuantity(count), count, draft.previousCount)
        PlaylistQueueAction.INSERT_NEXT -> pluralStringResource(R.plurals.playlist_insert_queue_explanation, playlistPluralQuantity(count), count)
        PlaylistQueueAction.INSERT_AND_PLAY -> pluralStringResource(R.plurals.playlist_insert_play_explanation, playlistPluralQuantity(count), count)
    }
    PlaylistText(text)
    if (state.playPending) PlaylistText(stringResource(R.string.playlist_play_pending))
    PlaylistButton(stringResource(if (draft.submitted) R.string.playlist_retry_play else R.string.playlist_confirm_play),
        state.canPlay && !commandPending && queueKnown) { model.confirmPlay(onPlay) }
    PlaylistButton(stringResource(R.string.playlist_cancel), !state.playPending && !commandPending, model::cancelPlay)
}

@Composable
private fun PlaylistImportButtons(enabled: Boolean, choose: (PlaylistTransferFormat, PlaylistTransferEncoding) -> Unit) {
    PlaylistButton(stringResource(R.string.playlist_import_json), enabled) { choose(PlaylistTransferFormat.JSON, PlaylistTransferEncoding.UTF8) }
    PlaylistButton(stringResource(R.string.playlist_import_m3u8), enabled) { choose(PlaylistTransferFormat.M3U8, PlaylistTransferEncoding.UTF8) }
    PlaylistButton(stringResource(R.string.playlist_import_m3u), enabled) { choose(PlaylistTransferFormat.M3U, PlaylistTransferEncoding.UTF8) }
    PlaylistButton(stringResource(R.string.playlist_import_m3u_gb), enabled) { choose(PlaylistTransferFormat.M3U, PlaylistTransferEncoding.GB18030) }
}

@Composable
private fun playlistTitle(snapshot: PlaylistSnapshot?): String = when {
    snapshot == null -> stringResource(R.string.playlist_unnamed_playlist)
    snapshot.kind == PlaylistKind.FAVORITES -> stringResource(R.string.playlist_favorites)
    else -> snapshot.name
}

@Composable
private fun PlaylistNameField(name: String, enabled: Boolean, change: (String) -> Unit, done: () -> Unit) {
    PlaylistInput(name, stringResource(R.string.playlist_name), enabled, KeyboardType.Text, change, done)
}

@Composable
private fun PlaylistInput(value: String, label: String, enabled: Boolean, keyboardType: KeyboardType,
    change: (String) -> Unit, done: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(value, change, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = enabled,
        label = { Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp)) },
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            keyboard?.hide()
            done()
        }),
    )
}

@Composable
private fun PlaylistCheck(label: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    WatchCheckRow(stringResource(if (checked) R.string.playlist_checked_option else R.string.playlist_unchecked_option, label),
        checked, enabled, change)
}

@Composable
private fun PlaylistButton(label: String, enabled: Boolean = true, click: () -> Unit) {
    WatchActionRow(label, enabled, onClick = click)
}

@Composable
private fun PlaylistText(text: String, primary: Boolean = false, error: Boolean = false) {
    Text(text, Modifier.fillMaxWidth().padding(vertical = 4.dp),
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
        style = if (primary) MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp) else
            MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), textAlign = TextAlign.Center)
}

private fun playlistPluralQuantity(count: Long): Int = count.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

/** Unknown queue state blocks only playback; independent playlist edits retain their own contracts. */
internal fun playlistQueueControlsEnabled(state: PlaylistUiState, queue: PlaylistQueueBinding?, mode: QueueMode?,
    commandPending: Boolean): Boolean = state.canPlay && queue != null && mode != null && !commandPending

@StringRes
private fun playlistAvailabilityText(availability: PlaylistQueueAvailability): Int = when (availability) {
    PlaylistQueueAvailability.AVAILABLE -> R.string.playlist_available
    PlaylistQueueAvailability.MISSING -> R.string.playlist_missing
    PlaylistQueueAvailability.NEEDS_AUTHORIZATION -> R.string.playlist_needs_authorization
    PlaylistQueueAvailability.UNAVAILABLE -> R.string.playlist_unavailable
    PlaylistQueueAvailability.UNRESOLVED -> R.string.playlist_unresolved
}

@StringRes
private fun playlistImportIssueText(issue: PlaylistImportMemberIssue): Int = when (issue) {
    PlaylistImportMemberIssue.NONE -> R.string.playlist_available
    PlaylistImportMemberIssue.UNMATCHED -> R.string.playlist_import_unmatched
    PlaylistImportMemberIssue.AMBIGUOUS -> R.string.playlist_import_ambiguous
    PlaylistImportMemberIssue.BLOCKED_REFERENCE -> R.string.playlist_import_blocked
    PlaylistImportMemberIssue.REQUIRES_REASSOCIATION -> R.string.playlist_reassociation_explanation
}

@StringRes
private fun playlistIssueText(issue: PlaylistUiIssue): Int = when (issue) {
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
