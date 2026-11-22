package io.github.onlinemo.meloxwatch.ui.fileoperations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.data.fileoperations.AudioTagField
import io.github.onlinemo.meloxwatch.data.fileoperations.FileItemPhase
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationIssue
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationKind
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationPhase
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationsState
import io.github.onlinemo.meloxwatch.data.fileoperations.FileRecoveryAction
import io.github.onlinemo.meloxwatch.data.fileoperations.FileResourceOwnership
import io.github.onlinemo.meloxwatch.data.library.LibrarySourceState
import io.github.onlinemo.meloxwatch.data.library.LibraryTrack
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.ui.watch.WatchActionRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchChoiceRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchSwitchRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchText

/** Activity 管理可见性；滚动导致 lazy 行摘除时不丢弃选择。 */
@Composable
fun FileOperationsControls(viewId: String?, model: FileOperationsViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    val idle = !state.working
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WatchActionRow(stringResource(R.string.file_manage), idle, onClick = { expanded = !expanded })
        if (!expanded && state.preview == null && state.progress == null && state.editor == null && state.draft == null) return@Column
        when (val runtime = state.runtime) {
            FileOperationsState.Loading -> WatchText(stringResource(R.string.library_loading))
            is FileOperationsState.Unavailable -> {
                WatchText(stringResource(fileIssueText(runtime.issue)), error = true)
                WatchActionRow(stringResource(R.string.file_retry_records), idle, onClick = model::retryInitialize)
            }
            is FileOperationsState.Ready -> if (runtime.recoveryRequired) WatchText(stringResource(R.string.file_recovery_block), error = true)
        }
        state.issue?.let { WatchText(stringResource(fileIssueText(it)), error = true) }
        if (state.working) {
            WatchText(stringResource(if (state.stopping) R.string.library_stopping else R.string.library_reading))
            WatchActionRow(stringResource(R.string.probe_cancel), !state.stopping, onClick = model::cancel)
        }
        val draft = state.draft
        if (draft == null) {
            if (viewId != null) WatchActionRow(stringResource(R.string.file_select), idle, onClick = { model.beginSelection(viewId) })
        } else {
            WatchText(stringResource(R.string.file_selection_count, draft.selectedCount, draft.view.totalCount), primary = true)
            WatchActionRow(stringResource(R.string.file_select_all), idle, onClick = { model.selectAll(true) })
            WatchActionRow(stringResource(R.string.file_select_none), idle, onClick = { model.selectAll(false) })
            WatchActionRow(stringResource(R.string.file_end_selection), idle, onClick = model::closeSelection)
            val selected = idle && draft.selectedCount > 0L
            WatchActionRow(stringResource(R.string.file_remove_index), selected, onClick = { model.prepareSelection(FileOperationKind.REMOVE_FROM_INDEX) })
            WatchActionRow(stringResource(R.string.file_delete_original), selected, onClick = { model.prepareSelection(FileOperationKind.DELETE_ORIGINAL) })
            WatchActionRow(stringResource(R.string.file_delete_copy), selected, onClick = { model.prepareSelection(FileOperationKind.DELETE_APP_COPY) })
            WatchActionRow(stringResource(R.string.file_move), selected, onClick = { model.beginMove() })
        }
        state.editor?.let { FileEditorControls(it, state, model) }
        state.preview?.let { preview ->
            var wholeAccepted by remember(preview.operationId, preview.binding) { mutableStateOf(false) }
            var playbackAccepted by remember(preview.operationId, preview.binding) { mutableStateOf(false) }
            val wholeRequired = preview.mayPauseAndClosePlayback && (preview.affectedAliases > 1L || preview.affectedCueSegments > 0L ||
                preview.affectedTracks > preview.selectedCount)
            WatchText(stringResource(kindText(preview.kind)), primary = true)
            WatchText(stringResource(R.string.file_preview_count, preview.selectedCount, preview.physicalCount))
            WatchText(stringResource(R.string.file_preview_impact, preview.affectedTracks, preview.affectedAliases, preview.affectedCueSegments))
            WatchText(stringResource(when (preview.kind) {
                FileOperationKind.REMOVE_FROM_INDEX -> R.string.file_index_scope
                FileOperationKind.REMOVE_SOURCE -> R.string.file_source_scope
                FileOperationKind.DELETE_ORIGINAL, FileOperationKind.DELETE_COMPLETED_MOVE_SOURCE -> R.string.file_delete_scope
                FileOperationKind.DELETE_APP_COPY -> R.string.file_copy_delete_scope
                FileOperationKind.WRITE_TAGS -> R.string.file_tag_scope
                FileOperationKind.RENAME, FileOperationKind.MOVE -> R.string.file_move_scope
            }))
            for (difference in state.tagDifferences) {
                WatchText(stringResource(R.string.file_tag_difference, stringResource(tagFieldText(difference.field)),
                    difference.before?.joinToString(" / ") ?: stringResource(R.string.file_tag_empty),
                    difference.after?.joinToString(" / ") ?: stringResource(R.string.file_tag_empty)))
            }
            if (preview.mayPauseAndClosePlayback) {
                WatchSwitchRow(stringResource(R.string.file_accept_playback_close), playbackAccepted, idle, { playbackAccepted = it })
                if (wholeRequired) WatchSwitchRow(stringResource(R.string.file_accept_whole_file), wholeAccepted, idle, { wholeAccepted = it })
            }
            WatchActionRow(stringResource(confirmText(preview.kind)), idle &&
                (!preview.mayPauseAndClosePlayback || playbackAccepted && (!wholeRequired || wholeAccepted)),
                onClick = { model.confirm(wholeAccepted, playbackAccepted) })
            WatchActionRow(stringResource(R.string.probe_cancel), idle, onClick = model::cancelPreview)
        }
        state.progress?.let { progress ->
            WatchText(stringResource(phaseText(progress.phase)), primary = true)
            WatchText(stringResource(R.string.file_result_counts, progress.completed, progress.failed, progress.notExecuted, progress.needsRecovery))
            if (progress.waitingSourceDeletion > 0L || progress.copiedWithSourceRetained > 0L) WatchText(stringResource(R.string.file_copy_retained,
                progress.waitingSourceDeletion + progress.copiedWithSourceRetained))
            if (progress.phase == FileOperationPhase.WAITING_SOURCE_CONFIRMATION || progress.phase == FileOperationPhase.PARTIAL &&
                progress.copiedWithSourceRetained > 0L) {
                WatchActionRow(stringResource(R.string.file_review_source_delete), idle, onClick = model::sourceDeletionPreview)
                WatchActionRow(stringResource(R.string.file_keep_source), idle, onClick = model::keepCompletedCopies)
            }
            if (progress.phase == FileOperationPhase.WAITING_SOURCE_CONFIRMATION && progress.failed > 0L) {
                WatchActionRow(stringResource(R.string.file_retry_review), idle, onClick = model::retryPreview)
            }
            if (progress.phase in setOf(FileOperationPhase.NEEDS_RECOVERY, FileOperationPhase.PARTIAL, FileOperationPhase.CONFIRMED,
                    FileOperationPhase.RUNNING)) WatchActionRow(stringResource(R.string.file_observe_recovery), idle, onClick = model::recover)
            if (progress.phase in setOf(FileOperationPhase.FAILED, FileOperationPhase.PARTIAL) && progress.copiedWithSourceRetained == 0L) {
                WatchActionRow(stringResource(R.string.file_retry_review), idle, onClick = model::retryPreview)
            }
            if (progress.phase == FileOperationPhase.COMPLETED && state.kind == FileOperationKind.REMOVE_FROM_INDEX) {
                WatchActionRow(stringResource(R.string.file_restore_index), idle, onClick = model::restoreIndex)
            }
        }
        for (item in state.items) {
            val label = if (item.target.ownership == FileResourceOwnership.APP_COPY) item.target.displayName else item.target.proof?.displayName ?: item.target.displayName
            WatchText(label ?: stringResource(R.string.library_unnamed_file), primary = true)
            WatchText(stringResource(itemPhaseText(item.phase)))
            item.artifacts.published?.proof?.displayName?.let { actual -> WatchText(stringResource(R.string.file_actual_name, actual)) }
            item.issue?.let { WatchText(stringResource(fileIssueText(it)), error = true) }
            if (item.phase == FileItemPhase.NEEDS_RECOVERY) {
                if (item.artifacts.originalBackup != null && item.artifacts.published == null) WatchActionRow(
                    stringResource(R.string.file_review_restore_original), idle, onClick = { model.recovery(item, FileRecoveryAction.RESTORE_RETAINED_ORIGINAL) })
                if (item.artifacts.staged != null && item.artifacts.originalBackup == null &&
                    (item.artifacts.published == null || item.artifacts.restoredOriginal)) WatchActionRow(
                    stringResource(R.string.file_review_discard_staging), idle, onClick = { model.recovery(item, FileRecoveryAction.DISCARD_STAGING) })
            }
        }
        if (state.items.isNotEmpty()) {
            WatchActionRow(stringResource(R.string.file_first_results), idle, onClick = model::firstItems)
            WatchActionRow(stringResource(R.string.file_more_results), idle, onClick = model::nextItems)
        }
        state.recovery?.let { recovery ->
            WatchText(stringResource(if (recovery.action == FileRecoveryAction.RESTORE_RETAINED_ORIGINAL) R.string.file_restore_original_scope
                else R.string.file_discard_staging_scope))
            WatchActionRow(stringResource(R.string.file_confirm_recovery_close), idle, onClick = model::confirmRecovery)
            WatchActionRow(stringResource(R.string.probe_cancel), idle, onClick = model::cancelRecovery)
        }
        WatchActionRow(stringResource(R.string.file_pending), idle, onClick = { model.pending() })
        for (record in state.pending) WatchActionRow(stringResource(R.string.file_pending_operation, stringResource(kindText(record.command.kind)),
            record.selectedCount), idle, onClick = { model.showOperation(record) })
        if (state.pending.isNotEmpty()) WatchActionRow(stringResource(R.string.file_more_pending), idle,
            onClick = { model.pending(state.pending.last().operationId) })
        WatchActionRow(stringResource(R.string.file_ignored), idle, onClick = { model.ignored() })
        state.ignored?.let { ignored ->
            for (track in ignored.tracks) WatchActionRow(stringResource(R.string.file_restore_ignored_track, track.title), idle,
                onClick = { model.restoreIgnored(track.trackKey) })
            if (ignored.nextTrackId != null) WatchActionRow(stringResource(R.string.file_more_ignored), idle,
                onClick = { model.ignored(ignored.nextTrackId) })
        }
    }
}

@Composable
fun FileSelectionTrackChoice(track: LibraryTrack, model: FileOperationsViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    if (state.draft != null) WatchSwitchRow(stringResource(R.string.file_select_track, track.title), track.trackKey in state.visibleSelected,
        !state.working, { model.toggle(track.trackKey) })
}

@Composable
fun FileTrackActions(track: LibraryTrack, revision: Long, model: FileOperationsViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val idle = !state.working && state.draft == null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WatchActionRow(stringResource(R.string.file_remove_index), idle, onClick = { model.prepareTrack(track, revision, FileOperationKind.REMOVE_FROM_INDEX) })
        WatchActionRow(stringResource(R.string.file_delete_original), idle, onClick = { model.prepareTrack(track, revision, FileOperationKind.DELETE_ORIGINAL) })
        WatchActionRow(stringResource(R.string.file_delete_copy), idle, onClick = { model.prepareTrack(track, revision, FileOperationKind.DELETE_APP_COPY) })
        WatchActionRow(stringResource(R.string.file_edit_tags), idle, onClick = { model.beginTags(track, revision) })
        WatchActionRow(stringResource(R.string.file_rename), idle, onClick = { model.beginRename(track, revision) })
        WatchActionRow(stringResource(R.string.file_move), idle, onClick = { model.beginMove(track, revision) })
    }
}

@Composable
private fun FileEditorControls(editor: FileEditor, state: FileOperationsUiState, model: FileOperationsViewModel) {
    val idle = !state.working
    when (editor.kind) {
        FileEditorKind.TAGS -> {
            WatchText(stringResource(R.string.file_supported_tags))
            for (field in AudioTagField.entries) OutlinedTextField(editor.tags[field].orEmpty(), { model.editTag(field, it) },
                modifier = Modifier.fillMaxWidth(), enabled = idle, label = { Text(stringResource(tagFieldText(field))) }, maxLines = 4)
        }
        FileEditorKind.RENAME -> {
            WatchText(stringResource(R.string.file_actual_name, editor.oldName))
            OutlinedTextField(editor.name, model::editName, modifier = Modifier.fillMaxWidth(), enabled = idle,
                label = { Text(stringResource(R.string.file_new_name)) }, maxLines = 4)
        }
        FileEditorKind.MOVE -> {
            WatchText(stringResource(R.string.file_choose_destination))
            for (source in state.destinations?.sources.orEmpty().filter { it.spec.kind == LocalSourceKind.TREE && it.state == LibrarySourceState.ACTIVE }) {
                WatchChoiceRow(source.displayName ?: stringResource(R.string.library_unnamed_folder), editor.destination?.spec?.sourceId == source.spec.sourceId,
                    idle, { model.destination(source) })
            }
            if (state.destinations?.nextSourceId != null) WatchActionRow(stringResource(R.string.file_more_destinations), idle, onClick = model::moreDestinations)
            WatchText(stringResource(R.string.file_destination_scope))
        }
    }
    WatchActionRow(stringResource(R.string.file_prepare_changes), idle, onClick = model::prepareEditor)
    WatchActionRow(stringResource(R.string.probe_cancel), idle, onClick = model::cancelEditor)
}

internal fun fileIssueText(issue: FileOperationIssue): Int = when (issue) {
    FileOperationIssue.BUSY -> R.string.library_busy
    FileOperationIssue.EMPTY_SELECTION -> R.string.file_empty_selection
    FileOperationIssue.INVALID_INPUT -> R.string.library_invalid_input
    FileOperationIssue.CONFLICT -> R.string.library_conflict
    FileOperationIssue.CONFIRMATION_REQUIRED, FileOperationIssue.SHARED_FILE_CONFIRMATION_REQUIRED,
    FileOperationIssue.PLAYBACK_CONFIRMATION_REQUIRED, FileOperationIssue.SOURCE_DELETE_CONFIRMATION_REQUIRED -> R.string.file_confirmation_required
    FileOperationIssue.PERMISSION_DENIED -> R.string.library_permission_denied
    FileOperationIssue.PERMISSION_REVOKED -> R.string.library_permission_revoked
    FileOperationIssue.FILE_MISSING -> R.string.library_file_missing
    FileOperationIssue.SOURCE_UNAVAILABLE -> R.string.library_source_unavailable
    FileOperationIssue.UNSUPPORTED_PROVIDER -> R.string.file_provider_unsupported
    FileOperationIssue.UNSUPPORTED_FORMAT, FileOperationIssue.INVALID_FORMAT -> R.string.file_format_unsupported
    FileOperationIssue.SOURCE_CHANGED -> R.string.library_source_changed
    FileOperationIssue.TARGET_EXISTS -> R.string.file_target_exists
    FileOperationIssue.INCOMPLETE_SCOPE -> R.string.file_scope_incomplete
    FileOperationIssue.STORAGE_FULL -> R.string.library_storage_full
    FileOperationIssue.INTEGRITY_FAILED -> R.string.file_integrity_failed
    FileOperationIssue.PLAYBACK_RELEASE_UNCONFIRMED -> R.string.file_playback_unconfirmed
    FileOperationIssue.PARTIAL_RESULT -> R.string.file_partial
    FileOperationIssue.CANCELLED -> R.string.library_cancelled
    FileOperationIssue.RECOVERY_REQUIRED -> R.string.file_recovery_block
    FileOperationIssue.STORAGE_UNAVAILABLE -> R.string.library_storage_unavailable
}

private fun kindText(kind: FileOperationKind): Int = when (kind) {
    FileOperationKind.REMOVE_FROM_INDEX -> R.string.file_remove_index
    FileOperationKind.REMOVE_SOURCE -> R.string.library_remove_source
    FileOperationKind.DELETE_ORIGINAL -> R.string.file_delete_original
    FileOperationKind.DELETE_APP_COPY -> R.string.file_delete_copy
    FileOperationKind.WRITE_TAGS -> R.string.file_edit_tags
    FileOperationKind.RENAME -> R.string.file_rename
    FileOperationKind.MOVE -> R.string.file_move
    FileOperationKind.DELETE_COMPLETED_MOVE_SOURCE -> R.string.file_review_source_delete
}
private fun confirmText(kind: FileOperationKind): Int = when (kind) {
    FileOperationKind.DELETE_ORIGINAL, FileOperationKind.DELETE_COMPLETED_MOVE_SOURCE -> R.string.file_confirm_delete_original
    FileOperationKind.DELETE_APP_COPY -> R.string.file_confirm_delete_copy
    FileOperationKind.REMOVE_FROM_INDEX -> R.string.file_confirm_remove_index
    FileOperationKind.REMOVE_SOURCE -> R.string.library_confirm_remove_source
    FileOperationKind.WRITE_TAGS -> R.string.file_confirm_write_tags
    FileOperationKind.RENAME -> R.string.file_confirm_rename
    FileOperationKind.MOVE -> R.string.file_confirm_move
}
private fun tagFieldText(field: AudioTagField): Int = when (field) {
    AudioTagField.TITLE -> R.string.file_tag_title
    AudioTagField.ARTISTS -> R.string.file_tag_artists
    AudioTagField.ALBUM -> R.string.file_tag_album
    AudioTagField.ALBUM_ARTIST -> R.string.file_tag_album_artist
    AudioTagField.TRACK_NUMBER -> R.string.file_tag_track
    AudioTagField.DISC_NUMBER -> R.string.file_tag_disc
}
private fun phaseText(phase: FileOperationPhase): Int = when (phase) {
    FileOperationPhase.REQUESTED, FileOperationPhase.PREVIEW -> R.string.file_waiting_confirmation
    FileOperationPhase.CONFIRMED, FileOperationPhase.RUNNING -> R.string.file_running
    FileOperationPhase.WAITING_SOURCE_CONFIRMATION -> R.string.file_waiting_source
    FileOperationPhase.COMPLETED -> R.string.file_completed
    FileOperationPhase.PARTIAL -> R.string.file_partial
    FileOperationPhase.CANCELLED -> R.string.library_cancelled
    FileOperationPhase.FAILED -> R.string.file_failed
    FileOperationPhase.NEEDS_RECOVERY -> R.string.file_recovery_block
}
private fun itemPhaseText(phase: FileItemPhase): Int = when (phase) {
    FileItemPhase.REQUESTED -> R.string.file_not_executed
    FileItemPhase.STAGING, FileItemPhase.VALIDATED -> R.string.file_staging
    FileItemPhase.APPLYING -> R.string.file_running
    FileItemPhase.EXTERNAL_APPLIED -> R.string.file_coordinating
    FileItemPhase.INDEX_RECONCILED -> R.string.file_completed
    FileItemPhase.COPY_VALIDATED, FileItemPhase.COPY_INDEX_RECONCILED -> R.string.file_waiting_source
    FileItemPhase.CANCELLED -> R.string.library_cancelled
    FileItemPhase.FAILED -> R.string.file_failed
    FileItemPhase.NEEDS_RECOVERY -> R.string.file_recovery_block
}
