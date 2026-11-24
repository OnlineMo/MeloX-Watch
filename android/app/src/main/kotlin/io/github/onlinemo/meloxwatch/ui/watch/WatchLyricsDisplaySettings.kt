package io.github.onlinemo.meloxwatch.ui.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayAlignment
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayIssue
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayLimits
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayPreferences
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayState
import io.github.onlinemo.meloxwatch.ui.lyrics.options.LyricsDisplayEditorState
import io.github.onlinemo.meloxwatch.ui.lyrics.options.LyricsDisplayNotice
import kotlin.math.roundToInt

/** The caller owns the round-safe scroller, actual preview renderer and cancellable save coroutine. */
@Composable
fun WatchLyricsDisplaySettings(
    state: LyricsDisplayEditorState,
    settings: LyricsDisplayState,
    visible: Boolean,
    onBegin: () -> Unit,
    onPreview: (LyricsDisplayPreferences) -> Unit,
    onDefaults: () -> Unit,
    onSave: () -> Unit,
    onReviewRetry: () -> Unit,
    onRetryRead: () -> Unit,
    onCancel: () -> Unit,
    preview: @Composable (LyricsDisplayPreferences) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val saved = when (settings) {
        is LyricsDisplayState.Ready -> settings.preferences
        is LyricsDisplayState.Unavailable -> settings.lastKnown
        LyricsDisplayState.Loading -> null
    }
    val shown = state.draft ?: saved
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        WatchSettingTitle(stringResource(R.string.lyrics_display_title))
        if (state.working) WatchText(stringResource(R.string.lyrics_settings_working))
        state.notice?.let { notice ->
            WatchText(stringResource(when (notice) {
                LyricsDisplayNotice.SAVED -> R.string.lyrics_settings_saved
                LyricsDisplayNotice.CANCELLED -> R.string.lyrics_settings_cancelled
                LyricsDisplayNotice.CONFLICT -> R.string.lyrics_settings_conflict
                LyricsDisplayNotice.READ_REQUIRED -> R.string.lyrics_settings_read_required
            }))
        }
        val issue = state.issue ?: (settings as? LyricsDisplayState.Unavailable)?.issue
        if (issue != null) WatchText(stringResource(displayIssueMessage(issue)), error = true)
        if (shown != null) {
            WatchText(stringResource(
                if (state.draft == null) R.string.lyrics_saved_preview else R.string.lyrics_unsaved_preview,
            ))
            preview(shown)
        }
        val draft = state.draft
        if (draft == null) {
            if (state.retry != null) {
                WatchActionRow(stringResource(R.string.lyrics_settings_review_retry),
                    enabled = !state.working && settings is LyricsDisplayState.Ready, onClick = onReviewRetry)
            } else {
                WatchActionRow(stringResource(R.string.lyrics_settings_edit),
                    enabled = !state.working && settings is LyricsDisplayState.Ready, onClick = onBegin)
            }
            if (settings !is LyricsDisplayState.Ready) {
                WatchActionRow(stringResource(R.string.lyrics_settings_retry_read),
                    enabled = !state.working, onClick = onRetryRead)
            }
        } else {
            val enabled = !state.working
            WatchSettingStepper(stringResource(R.string.lyrics_text_size),
                stringResource(R.string.lyrics_value_sp, draft.textSizeSp), enabled,
                draft.textSizeSp > LyricsDisplayLimits.MIN_TEXT_SP, draft.textSizeSp < LyricsDisplayLimits.MAX_TEXT_SP,
                onDecrease = {
                    onPreview(draft.copy(
                        textSizeSp = (draft.textSizeSp - 1f).coerceAtLeast(LyricsDisplayLimits.MIN_TEXT_SP),
                    ))
                },
                onIncrease = {
                    onPreview(draft.copy(
                        textSizeSp = (draft.textSizeSp + 1f).coerceAtMost(LyricsDisplayLimits.MAX_TEXT_SP),
                    ))
                })
            WatchSettingStepper(stringResource(R.string.lyrics_block_spacing),
                stringResource(R.string.lyrics_value_dp, draft.blockSpacingDp), enabled,
                draft.blockSpacingDp > LyricsDisplayLimits.MIN_BLOCK_DP,
                draft.blockSpacingDp < LyricsDisplayLimits.MAX_BLOCK_DP,
                onDecrease = {
                    onPreview(draft.copy(
                        blockSpacingDp = (draft.blockSpacingDp - 2f).coerceAtLeast(LyricsDisplayLimits.MIN_BLOCK_DP),
                    ))
                },
                onIncrease = {
                    onPreview(draft.copy(
                        blockSpacingDp = (draft.blockSpacingDp + 2f).coerceAtMost(LyricsDisplayLimits.MAX_BLOCK_DP),
                    ))
                })
            WatchText(stringResource(R.string.lyrics_alignment), primary = true)
            WatchChoiceRow(stringResource(R.string.lyrics_align_start), draft.alignment == LyricsDisplayAlignment.START,
                enabled, onSelect = { onPreview(draft.copy(alignment = LyricsDisplayAlignment.START)) })
            WatchChoiceRow(stringResource(R.string.lyrics_align_center), draft.alignment == LyricsDisplayAlignment.CENTER,
                enabled, onSelect = { onPreview(draft.copy(alignment = LyricsDisplayAlignment.CENTER)) })
            WatchSettingStepper(stringResource(R.string.lyrics_anchor),
                stringResource(R.string.lyrics_value_percent, (draft.anchorFraction * 100f).roundToInt()), enabled,
                draft.anchorFraction > LyricsDisplayLimits.MIN_ANCHOR_FRACTION,
                draft.anchorFraction < LyricsDisplayLimits.MAX_ANCHOR_FRACTION,
                onDecrease = {
                    onPreview(draft.copy(anchorFraction = (draft.anchorFraction - 0.05f)
                        .coerceAtLeast(LyricsDisplayLimits.MIN_ANCHOR_FRACTION)))
                },
                onIncrease = {
                    onPreview(draft.copy(anchorFraction = (draft.anchorFraction + 0.05f)
                        .coerceAtMost(LyricsDisplayLimits.MAX_ANCHOR_FRACTION)))
                })
            WatchSettingStepper(stringResource(R.string.lyrics_horizontal_margin),
                stringResource(R.string.lyrics_value_dp, draft.horizontalMarginDp), enabled,
                draft.horizontalMarginDp > LyricsDisplayLimits.MIN_MARGIN_DP,
                draft.horizontalMarginDp < LyricsDisplayLimits.MAX_MARGIN_DP,
                onDecrease = {
                    onPreview(draft.copy(horizontalMarginDp = (draft.horizontalMarginDp - 2f)
                        .coerceAtLeast(LyricsDisplayLimits.MIN_MARGIN_DP)))
                },
                onIncrease = {
                    onPreview(draft.copy(horizontalMarginDp = (draft.horizontalMarginDp + 2f)
                        .coerceAtMost(LyricsDisplayLimits.MAX_MARGIN_DP)))
                })
            WatchSwitchRow(stringResource(R.string.lyrics_show_translation), draft.showTranslation, enabled,
                onChange = { onPreview(draft.copy(showTranslation = it)) })
            WatchSwitchRow(stringResource(R.string.lyrics_emphasize_words), draft.emphasizeWords, enabled,
                onChange = { onPreview(draft.copy(emphasizeWords = it)) })
            WatchText(stringResource(R.string.lyrics_real_word_timing))
            WatchActionRow(stringResource(R.string.lyrics_settings_defaults_preview), enabled, onClick = onDefaults)
            WatchActionRow(stringResource(R.string.lyrics_settings_save), enabled, primary = true, onClick = onSave)
        }
        val exitLabel = if (draft == null && state.retry == null) {
            R.string.lyrics_back
        } else {
            R.string.lyrics_settings_cancel
        }
        WatchActionRow(stringResource(exitLabel),
            enabled = !state.working, onClick = onCancel)
    }
}

private fun displayIssueMessage(issue: LyricsDisplayIssue): Int = when (issue) {
    LyricsDisplayIssue.BUSY -> R.string.lyrics_settings_working
    LyricsDisplayIssue.LOW_MEMORY -> R.string.lyrics_settings_low_memory
    LyricsDisplayIssue.IO -> R.string.lyrics_settings_save_failed
    LyricsDisplayIssue.CORRUPT_FILE, LyricsDisplayIssue.UNSUPPORTED_SCHEMA, LyricsDisplayIssue.INVALID_FIELDS,
    LyricsDisplayIssue.INVALID_REVISION, LyricsDisplayIssue.REVISION_EXHAUSTED -> R.string.lyrics_settings_read_required
}
