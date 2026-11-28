package io.github.onlinemo.meloxwatch.ui.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.data.backup.BackupIssue
import io.github.onlinemo.meloxwatch.data.backup.BackupMergeMode
import io.github.onlinemo.meloxwatch.data.backup.BackupRelinkMatch
import io.github.onlinemo.meloxwatch.data.backup.BackupScope
import io.github.onlinemo.meloxwatch.data.backup.BackupStage
import io.github.onlinemo.meloxwatch.data.backup.BackupStartupState
import io.github.onlinemo.meloxwatch.model.ScreenShape
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsDisplayOptions
import io.github.onlinemo.meloxwatch.ui.lyrics.lyricsViewportGeometry
import io.github.onlinemo.meloxwatch.ui.themes.LocalWatchAppearance
import io.github.onlinemo.meloxwatch.ui.themes.LocalWatchThemeColors
import io.github.onlinemo.meloxwatch.ui.watch.WatchActionRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchCheckRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchChoiceRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchContentRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchText
import io.github.onlinemo.meloxwatch.ui.watch.protectsPhysicalRoundBounds
import io.github.onlinemo.meloxwatch.ui.watch.rememberWatchRotaryScroll
import io.github.onlinemo.meloxwatch.ui.watch.watchPageMargins
import io.github.onlinemo.meloxwatch.ui.watch.watchRotaryInput
import io.github.onlinemo.meloxwatch.ui.watch.watchSpacing

/** 共用 Foundation 整行控件；固定中央阅读弦和首尾留白，使大字/长说明可滚到中央。 */
@Composable
fun WatchBackupSettings(
    state: BackupUiState,
    screenShape: ScreenShape,
    visible: Boolean,
    actions: BackupUiActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onInputFocus: (Boolean, Boolean) -> Unit = { _, _ -> },
) {
    val listState = rememberLazyListState()
    if (!visible) {
        Box(modifier.fillMaxSize().clearAndSetSemantics { })
        return
    }
    LaunchedEffect(state.page) { listState.scrollToItem(0) }
    val colors = LocalWatchThemeColors.current
    val appearance = LocalWatchAppearance.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val window = LocalWindowInfo.current.containerSize
    val insets = WindowInsets.safeDrawing.union(WindowInsets.ime).asPaddingValues()
    val windowWidth = with(density) { window.width.toDp().value }
    val windowHeight = with(density) { window.height.toDp().value }
    val leftInset = insets.calculateLeftPadding(direction).value
    val topInset = insets.calculateTopPadding().value
    val round = protectsPhysicalRoundBounds(screenShape, configuration.isScreenRound)
    val scroll = rememberWatchRotaryScroll(listState, visible)
    BoxWithConstraints(modifier.fillMaxSize().background(colors.background).safeDrawingPadding().imePadding()) {
        if (maxWidth.value <= 0f || maxHeight.value <= 0f || !maxWidth.value.isFinite() || !maxHeight.value.isFinite()) return@BoxWithConstraints
        val margins = watchPageMargins(maxWidth.value, maxHeight.value,
            appearance.horizontalMarginDp.toFloat(), appearance.verticalMarginDp.toFloat())
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = margins.horizontalDp.dp, vertical = margins.verticalDp.dp)) {
            val geometry = lyricsViewportGeometry(maxWidth.value, maxHeight.value, windowWidth, windowHeight,
                leftInset + margins.horizontalDp, topInset + margins.verticalDp, round, 0,
                LyricsDisplayOptions(), appearance.scale)
            val width = (geometry?.lineWidthDp ?: maxWidth.value).coerceIn(minOf(48f, maxWidth.value), maxWidth.value).dp
            val left = (geometry?.lineLeftDp ?: 0f).coerceIn(0f, (maxWidth - width).value).dp
            val before = if (round) (geometry?.beforePaddingDp ?: maxHeight.value / 2f).dp else watchSpacing(12.dp)
            val after = if (round) (geometry?.afterPaddingDp ?: maxHeight.value / 2f).dp else watchSpacing(12.dp)
            LazyColumn(
                Modifier.fillMaxSize().watchRotaryInput(visible, "backup:${state.page}", onInputFocus, scroll),
                state = listState,
                contentPadding = PaddingValues(top = before, bottom = after + watchSpacing(16.dp)),
                verticalArrangement = Arrangement.spacedBy(watchSpacing(8.dp)),
                horizontalAlignment = AbsoluteAlignment.Left,
            ) {
                backupItem("heading", left, width) {
                    WatchText(stringResource(pageTitle(state.page)), primary = true, textAlign = TextAlign.Center)
                }
                backupItem("leave", left, width) {
                    WatchActionRow(stringResource(R.string.backup_ui_leave), onClick = onBack)
                }
                backupStatus(state, actions, left, width)
                when (state.page) {
                    BackupUiPage.HOME -> backupHome(state, actions, left, width)
                    BackupUiPage.EXPORT -> backupExport(state, actions, left, width)
                    BackupUiPage.RESTORE -> backupRestore(state, actions, left, width)
                    BackupUiPage.MISSING_FILES -> backupMissing(state, actions, left, width)
                    BackupUiPage.RELINK -> backupRelink(state, actions, left, width)
                }
                if (state.working) backupItem("cancel", left, width) {
                    if (state.cancellable) WatchActionRow(stringResource(R.string.backup_ui_cancel), onClick = actions.cancel)
                    else WatchText(stringResource(if (state.cancelling) R.string.backup_ui_cancelling else R.string.backup_ui_closing))
                }
                if (state.page != BackupUiPage.HOME) backupItem("home", left, width) {
                    WatchActionRow(stringResource(R.string.backup_ui_back), enabled = !state.working && !state.pickerPending &&
                        !state.targetPickerPending, onClick = actions.home)
                }
            }
        }
    }
}

private fun LazyListScope.backupStatus(state: BackupUiState, actions: BackupUiActions, left: Dp, width: Dp) {
    when (val startup = state.startup) {
        BackupStartupState.Loading -> backupItem("loading", left, width) {
            WatchText(stringResource(R.string.backup_ui_loading))
        }
        is BackupStartupState.Unavailable -> backupItem("unavailable", left, width) {
            WatchText(stringResource(backupIssueMessage(startup.issue)), error = true)
            WatchActionRow(stringResource(R.string.backup_ui_retry_storage), enabled = !state.working, onClick = actions.retryStorage)
        }
        is BackupStartupState.Ready -> if (startup.recoveredPendingRun) backupItem("recovered", left, width) {
            WatchText(stringResource(R.string.backup_ui_recovered))
        }
    }
    if (state.pickerPending || state.targetPickerPending) backupItem("picker", left, width) {
        WatchText(stringResource(R.string.backup_ui_picker_pending))
    }
    if (state.pickerUnavailable) backupItem("picker-unavailable", left, width) {
        WatchText(stringResource(R.string.backup_ui_picker_unavailable), error = true)
        WatchActionRow(stringResource(R.string.backup_ui_sources), enabled = !state.working, onClick = actions.openSources)
    }
    state.progress?.let { progress -> backupItem("progress", left, width) {
        WatchText(stringResource(stageMessage(progress.stage)), primary = true)
        WatchText(stringResource(R.string.backup_ui_progress_counts, progress.records, progress.bytes))
    } }
    state.issue?.let { issue -> backupItem("issue", left, width) {
        WatchText(stringResource(backupIssueMessage(issue)), error = true)
        if (issue == BackupIssue.OUTPUT_CLEANUP_REQUIRED) {
            WatchText(stringResource(R.string.backup_ui_cleanup_explanation))
            WatchActionRow(stringResource(R.string.backup_ui_retry_cleanup), enabled = state.canEdit, onClick = actions.retryOutputCleanup)
        }
    } }
    if (state.cancelled) backupItem("cancelled", left, width) {
        WatchText(stringResource(R.string.backup_ui_cancelled))
    }
}

private fun LazyListScope.backupHome(state: BackupUiState, actions: BackupUiActions, left: Dp, width: Dp) {
    backupItem("home-intro", left, width) { WatchText(stringResource(R.string.backup_ui_home_intro)) }
    backupItem("export", left, width) {
        WatchActionRow(stringResource(R.string.backup_ui_export), enabled = state.canEdit, primary = true, onClick = actions.beginExport)
    }
    backupItem("restore", left, width) {
        WatchActionRow(stringResource(R.string.backup_ui_restore), enabled = state.canEdit, onClick = actions.beginRestore)
    }
    backupItem("missing", left, width) {
        WatchActionRow(stringResource(R.string.backup_ui_missing), enabled = state.canEdit, onClick = actions.showMissing)
    }
}

private fun LazyListScope.backupExport(state: BackupUiState, actions: BackupUiActions, left: Dp, width: Dp) {
    backupItem("export-intro", left, width) {
        WatchText(stringResource(R.string.backup_ui_select_scopes), primary = true)
        WatchText(stringResource(R.string.backup_ui_no_audio))
    }
    backupScopes(BackupScope.entries, state, actions, left, width)
    backupItem("privacy", left, width) {
        WatchText(stringResource(R.string.backup_ui_readable_warning), primary = true)
        WatchText(stringResource(R.string.backup_ui_excluded_fields))
        for (scope in state.scopes) WatchText(stringResource(scopePrivacyMessage(scope)))
        if (state.scopes.isEmpty()) WatchText(stringResource(R.string.backup_ui_empty_selection), error = true)
        WatchCheckRow(stringResource(R.string.backup_ui_privacy_ack), state.privacyAcknowledged,
            enabled = state.canEdit && state.scopes.isNotEmpty(), onChange = actions.privacy)
        WatchActionRow(stringResource(R.string.backup_ui_choose_destination), enabled = state.canChooseExport,
            primary = true, onClick = actions.chooseDestination)
    }
    state.exportReceipt?.let { receipt -> backupItem("export-complete", left, width) {
        WatchText(stringResource(R.string.backup_ui_export_complete), primary = true)
        WatchText(stringResource(R.string.backup_ui_export_bytes, receipt.bytes))
        WatchText(stringResource(R.string.backup_ui_no_audio))
    } }
}

private fun LazyListScope.backupRestore(state: BackupUiState, actions: BackupUiActions, left: Dp, width: Dp) {
    backupItem("restore-intro", left, width) {
        WatchText(stringResource(R.string.backup_ui_restore_intro))
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(watchSpacing(8.dp))) {
            WatchChoiceRow(stringResource(R.string.backup_ui_merge), state.mode == BackupMergeMode.MERGE_KEEP_CURRENT,
                enabled = state.canEdit, onSelect = { actions.mode(BackupMergeMode.MERGE_KEEP_CURRENT) })
            WatchChoiceRow(stringResource(R.string.backup_ui_replace), state.mode == BackupMergeMode.REPLACE_SELECTED,
                enabled = state.canEdit, onSelect = { actions.mode(BackupMergeMode.REPLACE_SELECTED) })
        }
        WatchActionRow(stringResource(R.string.backup_ui_choose_input), enabled = state.canEdit, onClick = actions.chooseInput)
    }
    val preview = state.restorePreview
    if (preview != null) {
        backupItem("verified", left, width) {
            WatchText(stringResource(R.string.backup_ui_verified), primary = true)
            if (preview.readableWithoutPassword) WatchText(stringResource(R.string.backup_ui_readable_warning))
            WatchText(stringResource(R.string.backup_ui_restore_effects))
        }
        backupScopes(preview.header.scopes.toList(), state, actions, left, width)
        for (impact in preview.impacts) backupItem("impact:${impact.scope}", left, width) {
            WatchText(stringResource(scopeLabel(impact.scope)), primary = true)
            WatchText(stringResource(R.string.backup_ui_impact_counts, impact.incoming, impact.existing))
            WatchText(stringResource(R.string.backup_ui_impact_conflicts, impact.conflicts, impact.missingReferences))
        }
        backupItem("restore-confirm", left, width) {
            if (!state.previewMatchesSelection) WatchText(stringResource(R.string.backup_ui_recheck_required), error = true)
            WatchActionRow(stringResource(R.string.backup_ui_recheck), enabled = state.canEdit && state.scopes.isNotEmpty(),
                onClick = actions.recheckRestore)
            WatchCheckRow(stringResource(R.string.backup_ui_effects_ack), state.effectsAcknowledged,
                enabled = state.canEdit && state.previewMatchesSelection, onChange = actions.effects)
            if (preview.needsFontLicenseAcknowledgment) WatchCheckRow(stringResource(R.string.backup_ui_font_license_ack),
                state.fontLicenseAcknowledged, enabled = state.canEdit && state.previewMatchesSelection, onChange = actions.fontLicense)
            WatchActionRow(stringResource(R.string.backup_ui_confirm_restore), enabled = state.canRestore,
                primary = true, onClick = actions.confirmRestore)
        }
    } else if (state.hasRestoreInput && state.restoreReceipt == null) backupItem("restore-recheck", left, width) {
        WatchText(stringResource(R.string.backup_ui_recheck_required))
        WatchActionRow(stringResource(R.string.backup_ui_recheck), enabled = state.canEdit && state.scopes.isNotEmpty(),
            onClick = actions.recheckRestore)
    }
    state.restoreReceipt?.let { receipt -> backupItem("restore-complete", left, width) {
        WatchText(stringResource(R.string.backup_ui_restore_complete), primary = true)
        WatchText(stringResource(R.string.backup_ui_restored_paused))
        if (receipt.missingReferences > 0) {
            WatchText(stringResource(R.string.backup_ui_remaining_references, receipt.missingReferences))
            WatchActionRow(stringResource(R.string.backup_ui_missing), enabled = state.canEdit, onClick = actions.showMissing)
            WatchActionRow(stringResource(R.string.backup_ui_appearance), enabled = state.canEdit, onClick = actions.openAppearance)
        }
    } }
}

private fun LazyListScope.backupMissing(state: BackupUiState, actions: BackupUiActions, left: Dp, width: Dp) {
    backupItem("missing-intro", left, width) {
        WatchText(stringResource(R.string.backup_ui_missing_explanation))
        WatchActionRow(stringResource(R.string.backup_ui_sources), enabled = state.canEdit, onClick = actions.openSources)
    }
    state.relinkReceipt?.let { receipt -> backupItem("relink-complete", left, width) {
        WatchText(stringResource(R.string.backup_ui_relink_complete, receipt.repairedQueueEntries), primary = true)
    } }
    if (state.missingPageLoaded && state.missingFiles.isEmpty() && !state.working) backupItem("missing-empty", left, width) {
        WatchText(stringResource(R.string.backup_ui_missing_empty))
    }
    for (file in state.missingFiles) backupItem("missing:${file.hintId}", left, width) {
        WatchContentRow(enabled = state.canEdit, onClick = { actions.selectMissing(file) }) {
            WatchText(file.title.ifBlank { stringResource(R.string.backup_ui_untitled) }, primary = true)
            WatchText(stringResource(if (file.isCue) R.string.cue_relink_action else R.string.backup_ui_choose_relink))
        }
    }
    backupItem("missing-pagination", left, width) {
        WatchActionRow(stringResource(if (state.missingPageAfter == null) R.string.backup_ui_refresh else R.string.backup_ui_first_page),
            enabled = state.canEdit, onClick = actions.firstMissingPage)
        if (state.nextMissingPage != null) WatchActionRow(stringResource(R.string.backup_ui_next_page),
            enabled = state.canEdit, onClick = actions.nextMissingPage)
    }
}

private fun LazyListScope.backupRelink(state: BackupUiState, actions: BackupUiActions, left: Dp, width: Dp) {
    backupItem("relink-target", left, width) {
        val missing = state.selectedMissing
        if (missing == null) {
            WatchText(stringResource(R.string.backup_ui_choose_missing_first))
            WatchActionRow(stringResource(R.string.backup_ui_missing), enabled = state.canEdit, onClick = actions.showMissing)
        } else {
            WatchText(missing.title.ifBlank { stringResource(R.string.backup_ui_untitled) }, primary = true)
            WatchText(stringResource(if (missing.isCue) R.string.cue_relink_pending else R.string.backup_ui_missing_explanation))
            WatchActionRow(stringResource(R.string.backup_ui_existing_target), enabled = state.canEdit,
                onClick = actions.chooseExistingTarget)
            WatchActionRow(stringResource(R.string.backup_ui_sources), enabled = state.canEdit, onClick = actions.openSources)
        }
    }
    for (member in state.relinkPreview?.cueMembers.orEmpty()) backupItem("cue-member:${member.trackNumber}", left, width) {
        WatchText(stringResource(R.string.cue_relink_member_title, member.trackNumber, member.targetTitle), primary = true)
        member.originalTitle?.let { WatchText(stringResource(R.string.backup_ui_original_title, it)) }
        member.originalStartFrame?.let { start -> WatchText(stringResource(R.string.cue_relink_old_bounds, start,
            member.originalEndFrameExclusive?.toString() ?: stringResource(R.string.cue_relink_end_of_audio),
            checkNotNull(member.originalEndUsExclusive))) }
        WatchText(stringResource(R.string.cue_relink_new_bounds, member.targetStartFrame,
            member.targetEndFrameExclusive?.toString() ?: stringResource(R.string.cue_relink_end_of_audio), member.targetEndUsExclusive))
    }
    state.relinkPreview?.let { preview -> backupItem("relink-confirm", left, width) {
        WatchText(stringResource(R.string.backup_ui_original_title, preview.originalTitle))
        WatchText(stringResource(R.string.backup_ui_target_title, preview.targetTitle), primary = true)
        WatchText(stringResource(when (preview.match) {
            BackupRelinkMatch.VERIFIED_MATCH -> R.string.backup_ui_match_verified
            BackupRelinkMatch.DIFFERENT_CONTENT -> R.string.backup_ui_match_different
            BackupRelinkMatch.UNKNOWN_CONTENT -> R.string.backup_ui_match_unknown
        }))
        if (preview.match != BackupRelinkMatch.VERIFIED_MATCH) WatchCheckRow(stringResource(R.string.backup_ui_different_ack),
            state.allowDifferentContent, enabled = state.canEdit, onChange = actions.allowDifferent)
        preview.cueDifferences?.let { differences ->
            WatchText(stringResource(R.string.cue_relink_group_counts, preview.cueOriginalMembers,
                preview.cueTargetMembers, preview.affectedQueueEntries))
            WatchText(stringResource(R.string.cue_relink_playlist_counts, preview.affectedPlaylistEntries))
            if (differences.identityChanged) WatchText(stringResource(R.string.cue_relink_identity_change))
            if (differences.cueContentChanged) WatchText(stringResource(R.string.cue_relink_cue_change))
            if (differences.audioContentChanged > 0) WatchText(stringResource(R.string.cue_relink_audio_change, differences.audioContentChanged))
            if (differences.unknownAudioContent > 0) WatchText(stringResource(R.string.cue_relink_audio_unknown, differences.unknownAudioContent))
            if (differences.boundsChanged > 0) WatchText(stringResource(R.string.cue_relink_bounds_change, differences.boundsChanged))
            if (differences.extraNativeTracks > 0) WatchText(stringResource(R.string.cue_relink_extra_members, differences.extraNativeTracks))
            if (differences.requiresExplicitConfirmation) WatchCheckRow(stringResource(R.string.cue_relink_group_ack),
                state.allowCueDifferences, enabled = state.canEdit, onChange = actions.allowCueDifferences)
        }
        WatchActionRow(stringResource(R.string.backup_ui_confirm_relink), enabled = state.canEdit &&
            (preview.match == BackupRelinkMatch.VERIFIED_MATCH || state.allowDifferentContent) &&
            (preview.cueDifferences?.requiresExplicitConfirmation != true || state.allowCueDifferences), primary = true,
            onClick = actions.confirmRelink)
    } }
}

private fun LazyListScope.backupScopes(scopes: List<BackupScope>, state: BackupUiState, actions: BackupUiActions, left: Dp, width: Dp) {
    for (scope in scopes) backupItem("scope:$scope", left, width) {
        val resourcesAllowed = BackupScope.LIBRARY in state.scopes || BackupScope.PREFERENCES in state.scopes || BackupScope.LYRICS in state.scopes
        WatchCheckRow(stringResource(scopeLabel(scope)), scope in state.scopes,
            enabled = state.canEdit && (scope != BackupScope.RESOURCES || resourcesAllowed), onChange = { actions.scope(scope, it) })
        if (scope == BackupScope.RESOURCES && !resourcesAllowed) WatchText(stringResource(R.string.backup_ui_resource_dependency))
    }
}

private fun LazyListScope.backupItem(key: String, left: Dp, width: Dp, content: @Composable ColumnScope.() -> Unit) {
    item(key) {
        Column(Modifier.absoluteOffset(x = left).width(width), verticalArrangement = Arrangement.spacedBy(watchSpacing(8.dp)), content = content)
    }
}

private fun pageTitle(page: BackupUiPage): Int = when (page) {
    BackupUiPage.HOME -> R.string.backup_ui_title
    BackupUiPage.EXPORT -> R.string.backup_ui_export
    BackupUiPage.RESTORE -> R.string.backup_ui_restore
    BackupUiPage.MISSING_FILES -> R.string.backup_ui_missing
    BackupUiPage.RELINK -> R.string.backup_ui_choose_relink
}

private fun scopeLabel(scope: BackupScope): Int = when (scope) {
    BackupScope.LIBRARY -> R.string.backup_ui_scope_library
    BackupScope.FAVORITES -> R.string.backup_ui_scope_favorites
    BackupScope.PLAYLISTS -> R.string.backup_ui_scope_playlists
    BackupScope.QUEUE -> R.string.backup_ui_scope_queue
    BackupScope.HISTORY -> R.string.backup_ui_scope_history
    BackupScope.PREFERENCES -> R.string.backup_ui_scope_preferences
    BackupScope.LYRICS -> R.string.backup_ui_scope_lyrics
    BackupScope.RESOURCES -> R.string.backup_ui_scope_resources
}

private fun scopePrivacyMessage(scope: BackupScope): Int = when (scope) {
    BackupScope.LIBRARY -> R.string.backup_ui_private_library
    BackupScope.FAVORITES -> R.string.backup_ui_private_favorites
    BackupScope.PLAYLISTS -> R.string.backup_ui_private_playlists
    BackupScope.QUEUE -> R.string.backup_ui_private_queue
    BackupScope.HISTORY -> R.string.backup_ui_private_history
    BackupScope.PREFERENCES -> R.string.backup_ui_private_preferences
    BackupScope.LYRICS -> R.string.backup_ui_private_lyrics
    BackupScope.RESOURCES -> R.string.backup_ui_private_resources
}

private fun stageMessage(stage: BackupStage): Int = when (stage) {
    BackupStage.CAPTURING -> R.string.backup_ui_stage_capturing
    BackupStage.WRITING -> R.string.backup_ui_stage_writing
    BackupStage.VERIFYING -> R.string.backup_ui_stage_verifying
    BackupStage.PREVIEW -> R.string.backup_ui_stage_preview
    BackupStage.APPLYING -> R.string.backup_ui_stage_applying
    BackupStage.RECOVERING -> R.string.backup_ui_stage_recovering
    BackupStage.COMPLETE -> R.string.backup_ui_stage_complete
    BackupStage.FAILED -> R.string.backup_ui_stage_failed
}

private fun backupIssueMessage(issue: BackupIssue): Int = when (issue) {
    BackupIssue.BUSY -> R.string.backup_ui_issue_busy
    BackupIssue.PERMISSION_DENIED -> R.string.backup_ui_issue_permission
    BackupIssue.LOCAL_DESTINATION_REQUIRED -> R.string.backup_ui_issue_local_provider
    BackupIssue.STORAGE_FULL -> R.string.backup_ui_issue_storage
    BackupIssue.TOO_LARGE -> R.string.backup_ui_issue_size
    BackupIssue.UNSUPPORTED_VERSION -> R.string.backup_ui_issue_version
    BackupIssue.STALE_PREVIEW, BackupIssue.CONFLICT -> R.string.backup_ui_issue_stale
    BackupIssue.PENDING_FILE_OPERATION -> R.string.backup_ui_issue_file_operation
    BackupIssue.RECOVERY_REQUIRED -> R.string.backup_ui_issue_recovery
    BackupIssue.OUTPUT_CLEANUP_REQUIRED -> R.string.backup_ui_issue_cleanup
    BackupIssue.FONT_LICENSE_REQUIRED -> R.string.backup_ui_issue_font_license
    BackupIssue.INVALID_RESOURCE, BackupIssue.UNSUPPORTED_RESOURCE -> R.string.backup_ui_issue_resource
    BackupIssue.IO -> R.string.backup_ui_issue_io
    BackupIssue.INVALID_INPUT, BackupIssue.CORRUPT_ARCHIVE, BackupIssue.INCOMPLETE_ARCHIVE, BackupIssue.PATH_ESCAPE,
    BackupIssue.DUPLICATE_RECORD, BackupIssue.INVALID_REFERENCE, BackupIssue.INTEGRITY_MISMATCH -> R.string.backup_ui_issue_invalid
}
