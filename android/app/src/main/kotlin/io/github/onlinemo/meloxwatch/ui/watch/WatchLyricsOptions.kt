package io.github.onlinemo.meloxwatch.ui.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsEncodingChoice
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsKind
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsUserOffset
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsSourceOrigin
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsPageContent
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsPageFailure
import io.github.onlinemo.meloxwatch.ui.lyrics.entry.LyricsDocumentPurpose
import io.github.onlinemo.meloxwatch.ui.lyrics.entry.LyricsEntryNotice
import io.github.onlinemo.meloxwatch.ui.lyrics.entry.LyricsEntryUiState

/** A local source menu; the caller owns picker tickets, track fences and the round-safe scroller. */
@Composable
fun WatchLyricsOptions(
    state: LyricsEntryUiState,
    encoding: LyricsEncodingChoice,
    visible: Boolean,
    onEncoding: (LyricsEncodingChoice) -> Unit,
    onChoose: (LyricsDocumentPurpose, LyricsEncodingChoice) -> Unit,
    onClear: (LyricsDocumentPurpose) -> Unit,
    onOffset: () -> Unit,
    onDisplay: () -> Unit,
    onFonts: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onLookup: (() -> Unit)? = null,
) {
    if (!visible) return
    val enabled = state.canChoose && !state.loading && !state.saving && state.documentRequest == null
    val timed = (state.content as? LyricsPageContent.Ready)?.document?.kind == LyricsKind.SYNCHRONIZED
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WatchSettingTitle(stringResource(R.string.lyrics_settings))
        LyricsEntryStatus(state)
        WatchText(stringResource(R.string.lyrics_source_title), primary = true)
        WatchText(stringResource(when (state.sourceOrigin) {
            LyricsSourceOrigin.EXPLICIT_DOCUMENT -> R.string.lyrics_source_selected
            LyricsSourceOrigin.SAME_NAME_LRC -> R.string.lyrics_source_same_name
            LyricsSourceOrigin.EMBEDDED_SYNCHRONIZED -> R.string.lyrics_source_embedded_timed
            LyricsSourceOrigin.EMBEDDED_PLAIN_TEXT -> R.string.lyrics_source_embedded_text
            null -> R.string.lyrics_source_none
        }))
        WatchActionRow(stringResource(R.string.lyrics_choose), enabled, onClick = {
            onChoose(LyricsDocumentPurpose.PRIMARY, encoding)
        })
        WatchActionRow(stringResource(R.string.lyrics_choose_translation), enabled && timed, onClick = {
            onChoose(LyricsDocumentPurpose.TRANSLATION, encoding)
        })
        if (!timed) WatchText(stringResource(R.string.lyrics_translation_requires_timing))
        WatchActionRow(stringResource(R.string.lyrics_use_automatic), enabled && state.preferences?.primary != null,
            onClick = { onClear(LyricsDocumentPurpose.PRIMARY) })
        WatchActionRow(stringResource(R.string.lyrics_clear_translation), enabled && state.preferences?.translation != null,
            onClick = { onClear(LyricsDocumentPurpose.TRANSLATION) })
        WatchActionRow(stringResource(R.string.lyrics_retry), !state.loading && !state.saving, onClick = onRetry)
        if (onLookup != null) {
            WatchActionRow(stringResource(R.string.metadata_lookup_lyrics_title), !state.saving, onClick = onLookup)
        }
        WatchText(stringResource(R.string.lyrics_encoding_title), primary = true)
        WatchChoiceRow(stringResource(R.string.lyrics_encoding_auto), encoding == LyricsEncodingChoice.AUTO_BOM_OR_UTF8,
            enabled, onSelect = { onEncoding(LyricsEncodingChoice.AUTO_BOM_OR_UTF8) })
        WatchChoiceRow(stringResource(R.string.lyrics_encoding_utf8), encoding == LyricsEncodingChoice.UTF8,
            enabled, onSelect = { onEncoding(LyricsEncodingChoice.UTF8) })
        WatchChoiceRow(stringResource(R.string.lyrics_encoding_gb18030), encoding == LyricsEncodingChoice.GB18030,
            enabled, onSelect = { onEncoding(LyricsEncodingChoice.GB18030) })
        WatchActionRow(stringResource(R.string.lyrics_offset_title), enabled && timed, onClick = onOffset)
        if (!timed) WatchText(stringResource(R.string.lyrics_offset_requires_timing))
        WatchActionRow(stringResource(R.string.lyrics_display_title), !state.saving, onClick = onDisplay)
        WatchActionRow(stringResource(R.string.lyrics_fonts_title), !state.saving, onClick = onFonts)
        WatchActionRow(stringResource(R.string.lyrics_back), onClick = onBack)
    }
}

/** Preview and its confirmation are runtime tickets; composition never saves an association. */
@Composable
fun WatchLyricsAssociationConfirmation(
    state: LyricsEntryUiState,
    visible: Boolean,
    onConfirm: (String) -> Unit,
    onCancel: (String) -> Unit,
    preview: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val ticket = state.associationPreview ?: return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WatchSettingTitle(stringResource(when (ticket.purpose) {
            LyricsDocumentPurpose.PRIMARY -> R.string.lyrics_association_preview
            LyricsDocumentPurpose.TRANSLATION -> R.string.lyrics_translation_preview
        }))
        LyricsEntryStatus(state)
        preview()
        WatchActionRow(stringResource(R.string.lyrics_association_confirm),
            enabled = !state.loading && !state.saving, primary = true,
            onClick = { onConfirm(ticket.previewId) })
        WatchActionRow(stringResource(R.string.lyrics_settings_cancel),
            enabled = !state.saving, onClick = { onCancel(ticket.previewId) })
    }
}

/** Offset changes use the bound entry owner; these buttons never seek or alter an original file. */
@Composable
fun WatchLyricsOffsetSettings(
    state: LyricsEntryUiState,
    visible: Boolean,
    onAdjust: (Int) -> Unit,
    onZero: () -> Unit,
    onSave: () -> Unit,
    onReview: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    preview: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val draft = state.offsetDraftMs
    val timed = (state.content as? LyricsPageContent.Ready)?.document?.kind == LyricsKind.SYNCHRONIZED
    val enabled = draft != null && timed && !state.loading && !state.saving && !state.offsetDraftNeedsReview
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WatchSettingTitle(stringResource(R.string.lyrics_offset_title))
        LyricsEntryStatus(state)
        WatchText(stringResource(R.string.lyrics_offset_explanation))
        if (state.offsetDraftNeedsReview) {
            WatchText(stringResource(if (state.notice == LyricsEntryNotice.STALE) R.string.lyrics_offset_draft_other_entry
                else R.string.lyrics_offset_draft_review_required))
            WatchActionRow(stringResource(R.string.lyrics_offset_draft_review),
                state.canReviewOffsetDraft && !state.loading && !state.saving, onClick = onReview)
            WatchActionRow(stringResource(R.string.lyrics_settings_retry_read),
                !state.loading && !state.saving, onClick = onRetry)
        }
        WatchSettingStepper(stringResource(R.string.lyrics_offset_title),
            stringResource(R.string.lyrics_offset_ms, draft ?: state.preferences?.userDelayMs ?: 0), enabled,
            draft != null && draft > -LyricsUserOffset.MAX_DELAY_MS,
            draft != null && draft < LyricsUserOffset.MAX_DELAY_MS,
            onDecrease = { onAdjust(-1) }, onIncrease = { onAdjust(1) },
            decreaseLabel = stringResource(R.string.lyrics_offset_earlier),
            increaseLabel = stringResource(R.string.lyrics_offset_later))
        preview()
        WatchActionRow(stringResource(R.string.lyrics_offset_zero), enabled, onClick = onZero)
        WatchActionRow(stringResource(R.string.lyrics_settings_save), enabled, primary = true, onClick = onSave)
        WatchActionRow(stringResource(R.string.lyrics_settings_cancel), !state.saving, onClick = onCancel)
    }
}

@Composable
private fun LyricsEntryStatus(state: LyricsEntryUiState) {
    if (state.loading || state.saving) WatchText(stringResource(R.string.lyrics_settings_working))
    state.notice?.let { notice ->
        WatchText(stringResource(when (notice) {
            LyricsEntryNotice.SAVED -> R.string.lyrics_settings_saved
            LyricsEntryNotice.CANCELLED -> R.string.lyrics_settings_cancelled
            LyricsEntryNotice.STALE -> R.string.lyrics_entry_changed
            LyricsEntryNotice.CONFLICT -> R.string.lyrics_settings_conflict
            LyricsEntryNotice.NO_PICKER -> R.string.lyrics_picker_unavailable
        }))
    }
    when (val content = state.content) {
        LyricsPageContent.Loading -> if (!state.loading) WatchText(stringResource(R.string.lyrics_loading))
        LyricsPageContent.Missing -> WatchText(stringResource(R.string.lyrics_missing))
        is LyricsPageContent.Ready -> Unit
        is LyricsPageContent.Failed -> WatchText(stringResource(when (content.reason) {
            LyricsPageFailure.PERMISSION_REQUIRED -> R.string.lyrics_permission_required
            LyricsPageFailure.INVALID_ENCODING -> R.string.lyrics_invalid_encoding
            LyricsPageFailure.INPUT_LIMIT -> R.string.lyrics_input_limit
            LyricsPageFailure.UNSUPPORTED -> R.string.lyrics_unsupported
            LyricsPageFailure.READ_FAILED -> R.string.lyrics_read_failed
        }), error = true)
    }
    if (state.trackIssue != null) WatchText(stringResource(R.string.lyrics_entry_save_failed), error = true)
    if (state.translationFailure != null) WatchText(stringResource(R.string.lyrics_translation_read_failed), error = true)
}
