package io.github.onlinemo.meloxwatch.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.data.imports.ImportIssue
import io.github.onlinemo.meloxwatch.data.imports.ImportPhase
import io.github.onlinemo.meloxwatch.data.library.LibrarySource
import io.github.onlinemo.meloxwatch.data.library.LibrarySourceState
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.scan.ScanPhase
import io.github.onlinemo.meloxwatch.ui.fileoperations.fileIssueText
import io.github.onlinemo.meloxwatch.model.QueueEntry
import java.util.Locale

internal fun LazyListScope.sourcesContent(
    state: SourcesUiState,
    model: SourcesViewModel,
    commandPending: Boolean,
    onPlayPreview: (QueueEntry) -> Unit,
    onChooseFile: () -> Unit,
    onChooseTree: () -> Unit,
    onMediaStore: () -> Unit,
    onPermissionSettings: () -> Unit,
) {
    item(key = "sources:status") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.working) {
                val progressText = when {
                    state.stopping -> R.string.library_stopping
                    state.waitingForFileOperation -> R.string.library_busy
                    else -> R.string.library_reading
                }
                LibraryText(stringResource(progressText))
            }
            state.sourceIssue?.let { LibraryText(stringResource(sourceIssueText(it)), error = true) }
            if (state.sourceIssue in setOf(SourceReadIssue.PERMISSION_DENIED, SourceReadIssue.PERMISSION_REVOKED)) {
                LibraryButton(stringResource(R.string.probe_permission_settings), true, onPermissionSettings)
            }
            state.libraryIssue?.let { LibraryText(stringResource(libraryIssueText(it)), error = true) }
            state.importIssue?.let { LibraryText(stringResource(importIssueText(it)), error = true) }
            state.fileIssue?.let { LibraryText(stringResource(fileIssueText(it)), error = true) }
            state.notice?.let { LibraryText(stringResource(noticeText(it))) }
            if (state.working) LibraryButton(stringResource(R.string.probe_cancel), !state.stopping, model::cancelRead)
            LibraryButton(stringResource(R.string.library_reload_sources), !state.working, model::reload)
        }
    }
    if (state.reauthorization != null) item(key = "sources:reauthorize") {
        LibraryText(stringResource(R.string.library_reauthorize_scope, sourceName(state.reauthorization)))
        val isTree = state.reauthorization.spec.kind == LocalSourceKind.TREE
        LibraryButton(stringResource(if (isTree) R.string.library_choose_original_folder else R.string.library_choose_original_file), !state.working,
            if (isTree) onChooseTree else onChooseFile)
        LibraryButton(stringResource(R.string.probe_cancel), !state.working, model::cancelReauthorization)
    } else item(key = "sources:add") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LibraryText(stringResource(R.string.library_offline))
            LibraryButton(stringResource(R.string.library_add_media), !state.working, onMediaStore)
            LibraryText(stringResource(R.string.library_media_permission))
            LibraryButton(stringResource(R.string.library_choose_file), !state.working, onChooseFile)
            LibraryButton(stringResource(R.string.library_choose_folder), !state.working, onChooseTree)
            LibraryText(stringResource(R.string.library_no_picker_alternative))
        }
    }
    val selected = state.selected
    if (selected != null) item(key = "sources:selection") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LibraryText(selected.observation.root.displayName, primary = true)
            LibraryText(stringResource(R.string.library_selection_silent))
            selected.metadata?.let { metadata ->
                LibraryText(metadata.title ?: selected.observation.root.displayName)
                LibraryText(metadata.artist ?: stringResource(R.string.library_unknown_artist))
                LibraryText(metadata.durationMs?.let(::formatDuration) ?: stringResource(R.string.probe_unknown_duration))
            }
            LibraryText(stringResource(if (selected.observation.access.persistedRead == ObservedCapability.AVAILABLE)
                R.string.library_access_persisted else R.string.library_access_temporary))
            if (selected.observation.access.persistenceRejected) LibraryText(stringResource(R.string.library_persistence_rejected), error = true)
            if (selected.observation.source.kind in setOf(LocalSourceKind.DOCUMENT, LocalSourceKind.TREE)) {
                LibraryButton(stringResource(R.string.library_keep_access), !state.working, model::persistSelectedAccess)
            }
            if (selected.original != null) LibraryButton(stringResource(R.string.library_commit_reauthorization), !state.working, model::commitReauthorization)
            else {
                LibraryButton(stringResource(R.string.library_add_reference), !state.working, model::addSelected)
                if (selected.entry != null) {
                    LibraryButton(stringResource(R.string.library_preview_copy), !state.working, model::previewCopy)
                    LibraryButton(stringResource(R.string.library_play_preview), !state.working && !commandPending) { model.playSelected(onPlayPreview) }
                }
            }
        }
    }
    state.importPreview?.let { preview -> item(key = "sources:copy-confirmation") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LibraryText(stringResource(R.string.library_copy_confirmation), primary = true)
            LibraryText(stringResource(R.string.library_copy_ownership))
            LibraryText(stringResource(R.string.library_copy_extra_space, preview.declaredBytes?.let(::formatBytes) ?: stringResource(R.string.library_unknown)))
            LibraryText(stringResource(R.string.library_copy_space, formatBytes(preview.ownedBytes), formatBytes(preview.quotaBytes),
                formatBytes(preview.availableBytes), formatBytes(preview.reserveBytes)))
            if (preview.declaredBytes == null) LibraryText(stringResource(R.string.library_copy_unknown_length))
            LibraryButton(stringResource(R.string.library_confirm_copy), !state.working && state.importProgress?.stopping != true, model::confirmCopy)
            LibraryButton(stringResource(R.string.probe_cancel), true, model::discardPreview)
        }
    } }
    state.importRecord?.let { record -> item(key = "sources:copy-progress") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LibraryText(stringResource(importPhaseText(record.phase)), primary = true)
            LibraryText(stringResource(R.string.library_copied_bytes, formatBytes(state.importProgress?.copiedBytes ?: record.actualBytes)))
            record.issue?.let { LibraryText(stringResource(importIssueText(it)), error = true) }
            if (record.phase == ImportPhase.COPYING) LibraryButton(stringResource(R.string.probe_cancel), state.importProgress?.stopping != true, model::cancelCopy)
            if (state.importProgress?.stopping == true) LibraryText(stringResource(R.string.library_stopping))
            if (record.phase == ImportPhase.INSTALLED || record.issue == ImportIssue.RECOVERY_REQUIRED) {
                LibraryButton(stringResource(R.string.library_recover_import), !state.working, model::retryImportRecovery)
            }
        }
    } }
    state.scan?.let { scan -> item(key = "sources:scan") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LibraryText(stringResource(scanPhaseText(scan.phase)), primary = true)
            val count = scan.counters
            LibraryText(stringResource(R.string.library_scan_counts, count.seen, count.processed, count.added, count.skipped, count.failed))
            if (scan.phase in setOf(ScanPhase.PREPARING, ScanPhase.RUNNING, ScanPhase.STAGING_ABSENCE)) {
                LibraryText(stringResource(R.string.library_scan_unknown_total))
                LibraryButton(stringResource(R.string.probe_cancel), true, model::cancelScan)
            }
            scan.issue?.let { LibraryText(stringResource(sourceIssueText(it)), error = true) }
            if (scan.phase == ScanPhase.COMPLETED && !scan.coverage.permitsAbsence) LibraryText(stringResource(R.string.library_partial))
        }
    } }
    state.removeConfirmation?.let { source -> item(key = "sources:remove-confirmation") {
        LibraryText(stringResource(R.string.library_remove_source_confirmation, sourceName(source)), primary = true)
        state.removePreview?.let { preview ->
            LibraryText(stringResource(R.string.file_source_impact, preview.affectedTracks, preview.affectedAliases))
        }
        LibraryButton(stringResource(R.string.library_confirm_remove_source), !state.working && state.removePreview != null, model::confirmRemoveSource)
        LibraryButton(stringResource(R.string.probe_cancel), !state.working, model::cancelRemoveSource)
    } }
    items(state.page?.sources.orEmpty(), key = { "source:${it.spec.sourceId}" }) { source ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LibraryText(sourceName(source), primary = true)
            LibraryText(stringResource(sourceStateText(source.state)))
            LibraryText(stringResource(if (source.grant.persistedRead == ObservedCapability.AVAILABLE) R.string.library_access_persisted
                else if (source.spec.kind == LocalSourceKind.APP_OWNED) R.string.library_access_owned else R.string.library_access_temporary))
            LibraryButton(stringResource(R.string.library_scan_source), !state.working && source.state != LibrarySourceState.REMOVED) { model.scan(source) }
            if (source.spec.kind == LocalSourceKind.TREE) {
                LibraryText(stringResource(R.string.cue_scan_utf8))
                LibraryText(stringResource(R.string.cue_scan_gb18030_explanation))
                LibraryButton(stringResource(R.string.cue_scan_gb18030), !state.working && source.state == LibrarySourceState.ACTIVE &&
                    (state.scan == null || state.scan.phase in setOf(ScanPhase.COMPLETED, ScanPhase.CANCELLED,
                        ScanPhase.FAILED, ScanPhase.INTERRUPTED, ScanPhase.PAUSED_BACKGROUND))) { model.retryCueGb18030(source) }
            }
            if (source.spec.kind in setOf(LocalSourceKind.DOCUMENT, LocalSourceKind.TREE)) {
                LibraryButton(stringResource(R.string.library_reauthorize), !state.working) { model.beginReauthorization(source) }
            }
            LibraryButton(stringResource(R.string.library_remove_source), !state.working && source.state != LibrarySourceState.REMOVED) { model.askRemoveSource(source) }
        }
    }
    item { LibraryButton(stringResource(R.string.library_more_sources), !state.working && state.page?.nextSourceId != null, model::nextSources) }
    state.rules?.let { rules -> item(key = "sources:filter") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LibraryText(stringResource(R.string.library_filter_scope))
            val requested = state.filterDraft ?: rules.shortAudioFilter
            LibraryText(stringResource(R.string.library_filter_value, stringResource(if (requested) R.string.prefs_on else R.string.prefs_off)))
            LibraryButton(stringResource(R.string.library_edit_filter), !state.working, model::editFilter)
            if (state.filterDraft != null) {
                LibraryButton(stringResource(R.string.prefs_save), !state.working, model::saveFilter)
                LibraryButton(stringResource(R.string.prefs_cancel_edit), !state.working, model::cancelFilter)
            }
        }
    } }
    items(state.exclusions?.exclusions.orEmpty(), key = { "exclusion:${it.ruleId}" }) { rule ->
        LibraryText(rule.displayName ?: stringResource(if (rule.subtree) R.string.library_unnamed_folder else R.string.library_unnamed_file), primary = true)
        rule.sourceDisplayName?.let { LibraryText(it) }
        LibraryText(stringResource(if (rule.subtree) R.string.library_folder_exclusion else R.string.library_file_exclusion))
        LibraryButton(stringResource(R.string.library_remove_exclusion), !state.working) { model.removeExclusion(rule.ruleId) }
    }
    item { LibraryButton(stringResource(R.string.library_more_exclusions), !state.working && state.exclusions?.nextRuleId != null, model::nextExclusions) }
}

@Composable
private fun sourceName(source: LibrarySource): String = source.displayName?.takeIf { it.isNotBlank() }
    ?: stringResource(when (source.spec.kind) {
        LocalSourceKind.MEDIA_STORE -> R.string.library_system_library
        LocalSourceKind.TREE -> R.string.library_unnamed_folder
        LocalSourceKind.DOCUMENT -> R.string.library_unnamed_file
        LocalSourceKind.APP_OWNED -> R.string.library_owned_copy
    })

private fun sourceStateText(state: LibrarySourceState): Int = when (state) {
    LibrarySourceState.ACTIVE -> R.string.library_source_active
    LibrarySourceState.NEEDS_AUTHORIZATION -> R.string.library_permission_revoked
    LibrarySourceState.UNAVAILABLE -> R.string.library_source_unavailable
    LibrarySourceState.REMOVED -> R.string.library_source_removed
}

internal fun sourceIssueText(issue: SourceReadIssue): Int = when (issue) {
    SourceReadIssue.BUSY -> R.string.library_busy
    SourceReadIssue.PERMISSION_DENIED -> R.string.library_permission_denied
    SourceReadIssue.PERMISSION_REVOKED -> R.string.library_permission_revoked
    SourceReadIssue.SOURCE_CHANGED -> R.string.library_source_changed
    SourceReadIssue.CONTENT_UNKNOWN -> R.string.library_content_unknown
    SourceReadIssue.INVALID_SOURCE -> R.string.library_invalid_input
    SourceReadIssue.NOT_AUDIO -> R.string.library_not_audio
    SourceReadIssue.METADATA_UNREADABLE -> R.string.library_metadata_unreadable
    SourceReadIssue.FILE_MISSING -> R.string.library_file_missing
    SourceReadIssue.UNSUPPORTED_FORMAT -> R.string.library_unsupported_format
    SourceReadIssue.STORAGE_FULL -> R.string.library_storage_full
    SourceReadIssue.SOURCE_UNAVAILABLE -> R.string.library_source_unavailable
}

private fun importIssueText(issue: ImportIssue): Int = when (issue) {
    ImportIssue.BUSY -> R.string.library_busy
    ImportIssue.INVALID_INPUT -> R.string.library_invalid_input
    ImportIssue.CONFLICT -> R.string.library_conflict
    ImportIssue.PERMISSION_DENIED -> R.string.library_permission_denied
    ImportIssue.PERMISSION_REVOKED -> R.string.library_permission_revoked
    ImportIssue.SOURCE_UNAVAILABLE -> R.string.library_source_unavailable
    ImportIssue.SOURCE_CHANGED -> R.string.library_source_changed
    ImportIssue.NOT_AUDIO -> R.string.library_not_audio
    ImportIssue.METADATA_UNREADABLE -> R.string.library_metadata_unreadable
    ImportIssue.UNSUPPORTED_FORMAT -> R.string.library_unsupported_format
    ImportIssue.STORAGE_FULL -> R.string.library_storage_full
    ImportIssue.QUOTA_EXCEEDED -> R.string.library_import_quota
    ImportIssue.INTEGRITY_FAILED -> R.string.library_import_integrity
    ImportIssue.INTERRUPTED -> R.string.library_import_interrupted
    ImportIssue.CANCELLED -> R.string.library_cancelled
    ImportIssue.RECOVERY_REQUIRED -> R.string.library_recover_import_required
}

private fun importPhaseText(phase: ImportPhase): Int = when (phase) {
    ImportPhase.COPYING -> R.string.library_copying
    ImportPhase.VERIFIED -> R.string.library_copy_verified
    ImportPhase.INSTALLED -> R.string.library_copy_installed
    ImportPhase.COMMITTED -> R.string.library_copy_committed
    ImportPhase.CANCELLED -> R.string.library_cancelled
    ImportPhase.FAILED -> R.string.library_copy_failed
}

private fun scanPhaseText(phase: ScanPhase): Int = when (phase) {
    ScanPhase.PREPARING -> R.string.library_scan_preparing
    ScanPhase.RUNNING -> R.string.library_scanning
    ScanPhase.STOPPING -> R.string.library_stopping
    ScanPhase.STAGING_ABSENCE -> R.string.library_scan_reconciling
    ScanPhase.COMPLETED -> R.string.library_scan_completed
    ScanPhase.CANCELLED -> R.string.library_cancelled
    ScanPhase.PAUSED_BACKGROUND -> R.string.library_scan_background
    ScanPhase.FAILED -> R.string.library_scan_failed
    ScanPhase.INTERRUPTED -> R.string.library_scan_interrupted
}

private fun noticeText(notice: SourcesNotice): Int = when (notice) {
    SourcesNotice.CANCELLED -> R.string.library_cancelled
    SourcesNotice.NO_FILE_PICKER -> R.string.library_no_file_picker
    SourcesNotice.NO_TREE_PICKER -> R.string.library_no_tree_picker
    SourcesNotice.ADDED -> R.string.library_reference_added
    SourcesNotice.REAUTHORIZED -> R.string.library_reauthorized
    SourcesNotice.REMOVED -> R.string.library_source_removed
    SourcesNotice.RULES_SAVED -> R.string.library_rules_saved
    SourcesNotice.RULES_CONFLICT -> R.string.library_rules_conflict
}

private fun formatBytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f MiB", bytes.toDouble() / (1024 * 1024))
