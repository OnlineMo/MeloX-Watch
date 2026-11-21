package io.github.onlinemo.meloxwatch.companion.ui

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.onlinemo.meloxwatch.companion.R
import io.github.onlinemo.meloxwatch.companion.data.CompanionDisplayDraft
import io.github.onlinemo.meloxwatch.companion.data.CompanionEditPhase
import io.github.onlinemo.meloxwatch.companion.data.companionRowIdentity
import io.github.onlinemo.meloxwatch.data.transfer.TransferCapability
import io.github.onlinemo.meloxwatch.data.transfer.TransferCatalogKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferCatalogRow
import io.github.onlinemo.meloxwatch.data.transfer.TransferDisplayKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferDisplayPatch

internal fun LazyListScope.displayItems(state: CompanionUiState, model: CompanionViewModel) {
    item(key = "display-scope") {
        MeloXCard {
            MeloXText(stringResource(R.string.display_info), heading = true)
            MeloXText(stringResource(R.string.display_original_tags), secondary = true)
            if (!state.allowed(TransferCapability.WRITE_DISPLAY_METADATA)) MeloXText(stringResource(R.string.display_permission_needed), secondary = true)
        }
    }
    val selected = state.group
    if (selected == null) {
        item(key = "display-explicit-target") {
            var kind by remember { mutableStateOf(TransferDisplayKind.ALBUM) }
            var entityId by remember { mutableStateOf("") }
            MeloXCard {
                MeloXText(stringResource(R.string.read_display_target), heading = true)
                MeloXText(stringResource(R.string.display_minimum_context), secondary = true)
                TransferDisplayKind.entries.forEach { candidate ->
                    MeloXChoice(displayKindName(candidate), candidate == kind, { kind = candidate })
                }
                MeloXField(stringResource(R.string.display_target_id), entityId, { entityId = it }, maximum = 128)
                MeloXAction(stringResource(R.string.read_display_target), { model.loadDisplayTarget(kind, entityId) },
                    enabled = state.available && state.allowed(TransferCapability.WRITE_DISPLAY_METADATA) && entityId.isNotBlank(), prominent = true)
            }
        }
        item(key = "display-library-query") {
            var search by remember(state.pageWindow.kind, state.pageWindow.search) { mutableStateOf(state.pageWindow.search) }
            MeloXCard {
                MeloXText(stringResource(R.string.choose_display_from_library))
                listOf(TransferCatalogKind.ALBUMS, TransferCatalogKind.ARTISTS).forEach { kind ->
                    MeloXChoice(catalogName(kind), kind == state.pageWindow.kind, { model.refresh(kind, "", null) },
                        enabled = state.available && state.allowed(TransferCapability.READ_LIBRARY))
                }
                MeloXField(stringResource(R.string.search), search, { search = it })
                MeloXAction(stringResource(R.string.search_on_watch), { model.refresh(state.pageWindow.kind, search, null) },
                    enabled = state.available && state.allowed(TransferCapability.READ_LIBRARY) && state.pageWindow.kind in setOf(TransferCatalogKind.ALBUMS, TransferCatalogKind.ARTISTS))
            }
        }
    } else item(key = "display-current-target") {
        val context = selected.state
        var name by remember(selected.target, context.kind, context.entityId, context.revision) { mutableStateOf(context.currentName) }
        MeloXCard {
            MeloXText(context.currentName.ifBlank { stringResource(R.string.unnamed_group) }, heading = true)
            MeloXText(displayKindName(context.kind), secondary = true)
            MeloXText(stringResource(R.string.display_group_id, context.entityId), secondary = true, mono = true)
            MeloXText(stringResource(R.string.device_revision, context.revision), secondary = true)
            MeloXText(stringResource(R.string.library_fence, context.libraryRevision.catalogRevision, context.libraryRevision.rulesRevision), secondary = true)
            MeloXText(stringResource(R.string.track_count, context.trackCount), secondary = true)
            val canEdit = state.available && state.allowed(TransferCapability.WRITE_DISPLAY_METADATA) && state.connection.target?.fingerprint == selected.target
            MeloXField(stringResource(R.string.proposed_display_name), name, { name = it }, enabled = canEdit)
            MeloXAction(stringResource(R.string.save_display_rename_draft), { model.renameDisplay(name) }, enabled = canEdit && name.isNotBlank())
            MeloXAction(stringResource(R.string.select_display_add), { model.chooseDisplayTracks(remove = false) }, enabled = canEdit && state.allowed(TransferCapability.READ_LIBRARY))
            MeloXAction(stringResource(R.string.select_display_remove), { model.chooseDisplayTracks(remove = true) }, enabled = canEdit && state.allowed(TransferCapability.READ_LIBRARY))
            MeloXAction(stringResource(R.string.open_group_members), model::showGroupMembers, enabled = state.available && state.allowed(TransferCapability.READ_LIBRARY))
            MeloXAction(stringResource(R.string.back_to_groups), { model.showGroups(context.kind) })
        }
    }
    pageItems(state, model)
    val page = state.pageWindow.current?.page
    if (page != null) items(page.rows, key = ::companionRowIdentity) { row ->
        when (row) {
            is TransferCatalogRow.Group -> groupView(row, state, model)
            is TransferCatalogRow.Track -> trackView(row, state, model)
            is TransferCatalogRow.Playlist -> playlistRowView(row, state, model)
            is TransferCatalogRow.Member -> MeloXCard {
                MeloXText(row.titleHint)
                MeloXText(row.artistHint, secondary = true)
            }
        }
    }
    state.local?.display?.let { draft -> item(key = "durable-display-draft") { displayDraftView(draft, state, model) } }
}

@Composable
private fun displayDraftView(draft: CompanionDisplayDraft, state: CompanionUiState, model: CompanionViewModel) {
    var submit by remember(draft.operationId, draft.phase) { mutableStateOf(false) }
    var discard by remember(draft.operationId) { mutableStateOf(false) }
    val target = draft.target.forLocalDisplay().chunked(8).joinToString(" ")
    val summary = displayPatchSummary(draft.patch)
    val enabled = state.available && state.allowed(TransferCapability.WRITE_DISPLAY_METADATA) && state.connection.target?.fingerprint == draft.target
    MeloXCard {
        MeloXText(stringResource(R.string.display_draft), heading = true)
        MeloXText(editPhaseName(draft.phase))
        MeloXText(displayKindName(draft.kind), secondary = true)
        MeloXText(stringResource(R.string.display_group_id, draft.entityId), secondary = true, mono = true)
        MeloXText(stringResource(R.string.upload_bound_target, target), secondary = true, mono = true)
        MeloXText(stringResource(R.string.current_display_name, draft.currentName.ifBlank { stringResource(R.string.unnamed_group) }))
        MeloXText(stringResource(R.string.track_count, draft.currentTrackCount), secondary = true)
        MeloXText(stringResource(R.string.library_fence, draft.libraryRevision.catalogRevision, draft.libraryRevision.rulesRevision), secondary = true)
        MeloXText(summary)
        when (val patch = draft.patch) {
            is TransferDisplayPatch.Rename -> Unit
            is TransferDisplayPatch.Add -> patch.trackIds.forEach { MeloXText(it, secondary = true, mono = true) }
            is TransferDisplayPatch.Remove -> patch.trackIds.forEach { MeloXText(it, secondary = true, mono = true) }
        }
        if (draft.phase == CompanionEditPhase.PREVIEWED) MeloXText(stringResource(R.string.display_preview_bound), secondary = true)
        if (draft.phase == CompanionEditPhase.UNCERTAIN) MeloXText(stringResource(R.string.exact_retry_required), secondary = true)
        if (draft.phase == CompanionEditPhase.CONFLICT) MeloXText(stringResource(R.string.conflict_reload_keeps_draft), secondary = true)
        draft.receipt?.let { receiptView(it) }
        MeloXAction(stringResource(R.string.preview_on_watch), model::previewDisplay, enabled = enabled && draft.phase == CompanionEditPhase.DRAFT)
        val sendable = draft.phase in setOf(CompanionEditPhase.PREVIEWED, CompanionEditPhase.UNCERTAIN)
        MeloXAction(stringResource(if (draft.phase == CompanionEditPhase.UNCERTAIN) R.string.retry_same_operation else R.string.review_and_submit), { submit = true },
            enabled = enabled && sendable, prominent = true)
        MeloXAction(stringResource(R.string.reload_draft_target), model::reloadDisplayDraftTarget, enabled = enabled && draft.phase != CompanionEditPhase.SUBMITTING)
        MeloXAction(stringResource(R.string.discard_draft), { discard = true }, enabled = state.available)
    }
    if (submit) MeloXConfirmation(stringResource(R.string.submit_display_title),
        stringResource(R.string.submit_display_message, draft.currentName.ifBlank { stringResource(R.string.unnamed_group) }, summary, target), stringResource(R.string.apply_to_watch),
    onConfirm = {
            submit = false
            model.submitDisplay()
        }, onDismiss = {
            submit = false
        })
    if (discard) MeloXConfirmation(stringResource(R.string.discard_draft),
        stringResource(if (draft.phase == CompanionEditPhase.UNCERTAIN) R.string.discard_uncertain_message else R.string.discard_draft_message), stringResource(R.string.discard_draft),
    onConfirm = {
            discard = false
            model.discardDisplayDraft()
        }, onDismiss = {
            discard = false
        })
}

@Composable
private fun displayKindName(kind: TransferDisplayKind): String = stringResource(if (kind == TransferDisplayKind.ALBUM) R.string.albums else R.string.artists)

@Composable
private fun displayPatchSummary(patch: TransferDisplayPatch): String = when (patch) {
    is TransferDisplayPatch.Rename -> stringResource(R.string.edit_display_rename, patch.displayName)
    is TransferDisplayPatch.Add -> stringResource(R.string.edit_display_add, patch.trackIds.size)
    is TransferDisplayPatch.Remove -> stringResource(R.string.edit_display_remove, patch.trackIds.size)
}
