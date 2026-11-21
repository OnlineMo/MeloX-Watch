package io.github.onlinemo.meloxwatch.ui.watch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.data.fonts.FontWeightSupport
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontIssue
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontRole
import io.github.onlinemo.meloxwatch.ui.fonts.LyricsFontEditorState

/** Uses the single font editor; the preview slot consumes its decoded faces without file I/O. */
@Composable
fun WatchLyricsFontSettings(
    state: LyricsFontEditorState,
    licenseAcknowledged: Boolean,
    visible: Boolean,
    onBegin: (LyricsFontRole) -> Unit,
    onLicenseAcknowledgment: (Boolean) -> Unit,
    onChooseFont: () -> Unit,
    onWeight: (Float?) -> Unit,
    onSystemFont: () -> Unit,
    onConfirm: () -> Unit,
    onRetrySave: () -> Unit,
    onReadAgain: () -> Unit,
    onCancel: () -> Unit,
    preview: @Composable (LyricsFontRole) -> Unit,
    modifier: Modifier = Modifier,
    // The host passes an observed capability alongside face-free display metadata.
    canAdjustFontWeight: Boolean = state.canAdjustWeight,
) {
    if (!visible) return
    val draft = state.draft
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WatchSettingTitle(stringResource(R.string.lyrics_fonts_title))
        if (state.working) WatchText(stringResource(R.string.lyrics_settings_working))
        state.issue?.let { WatchText(stringResource(fontIssueMessage(it)), error = true) }
        if (draft == null) {
            if (state.saved == null) {
                WatchActionRow(stringResource(R.string.lyrics_settings_retry_read),
                    enabled = !state.working, onClick = onReadAgain)
            }
            for (role in LyricsFontRole.entries) {
                WatchActionRow(stringResource(roleLabel(role)),
                    enabled = !state.working && state.retry == null && state.saved != null,
                    onClick = { onBegin(role) })
                preview(role)
            }
        } else {
            val role = draft.role
            val font = state.font(role)
            WatchText(stringResource(roleLabel(role)), primary = true)
            WatchText(font.asset?.displayName ?: stringResource(R.string.lyrics_font_system))
            WatchText(stringResource(R.string.lyrics_unsaved_preview))
            preview(role)
            WatchCheckRow(stringResource(R.string.lyrics_font_license_acknowledgment), licenseAcknowledged,
                enabled = !state.working && state.retry == null, onChange = onLicenseAcknowledgment)
            WatchActionRow(stringResource(R.string.lyrics_font_choose),
                enabled = !state.working && state.retry == null && licenseAcknowledged, onClick = onChooseFont)
            WatchActionRow(stringResource(R.string.lyrics_font_system_preview),
                enabled = !state.working && state.retry == null, onClick = onSystemFont)
            val axis = font.capability.axis
            if (axis != null && canAdjustFontWeight) {
                val value = font.clampedWeight ?: axis.default
                WatchSettingStepper(stringResource(R.string.lyrics_font_weight),
                    stringResource(R.string.lyrics_font_weight_value, value), !state.working,
                    value > axis.minimum, value < axis.maximum,
                    onDecrease = { onWeight((value - 50f).coerceAtLeast(axis.minimum)) },
                    onIncrease = { onWeight((value + 50f).coerceAtMost(axis.maximum)) })
                WatchActionRow(stringResource(R.string.lyrics_font_weight_default),
                    enabled = !state.working, onClick = { onWeight(null) })
            } else if (font.capability.support != FontWeightSupport.AVAILABLE) {
                WatchText(stringResource(when (font.capability.support) {
                    FontWeightSupport.NO_AXIS -> R.string.lyrics_font_no_axis
                    FontWeightSupport.PLATFORM_UNAVAILABLE -> R.string.lyrics_font_axis_unavailable
                    FontWeightSupport.NO_OBSERVED_CHANGE -> R.string.lyrics_font_weight_unobserved
                    FontWeightSupport.AVAILABLE -> R.string.lyrics_font_axis_unavailable
                }))
            }
            WatchActionRow(stringResource(R.string.lyrics_settings_save), state.canConfirm,
                primary = true, onClick = onConfirm)
        }
        if (state.retry != null) WatchActionRow(stringResource(R.string.lyrics_font_retry_save),
            enabled = state.canRetrySave, onClick = onRetrySave)
        val exitLabel = if (draft == null && state.retry == null) {
            R.string.lyrics_back
        } else {
            R.string.lyrics_settings_cancel
        }
        WatchActionRow(stringResource(exitLabel),
            enabled = !state.working, onClick = onCancel)
    }
}

private fun roleLabel(role: LyricsFontRole): Int = when (role) {
    LyricsFontRole.ORIGINAL -> R.string.lyrics_font_original
    LyricsFontRole.TRANSLATION -> R.string.lyrics_font_translation
}

private fun fontIssueMessage(issue: LyricsFontIssue): Int = when (issue) {
    LyricsFontIssue.BUSY -> R.string.lyrics_settings_working
    LyricsFontIssue.LICENSE_ACKNOWLEDGMENT_REQUIRED -> R.string.lyrics_font_license_required
    LyricsFontIssue.PERMISSION_DENIED, LyricsFontIssue.SOURCE_UNAVAILABLE -> R.string.lyrics_font_read_failed
    LyricsFontIssue.TOO_LARGE, LyricsFontIssue.QUOTA_EXCEEDED -> R.string.lyrics_font_size_failed
    LyricsFontIssue.STORAGE_FULL -> R.string.lyrics_font_storage_full
    LyricsFontIssue.MISSING_GLYPHS -> R.string.lyrics_font_missing_glyphs
    LyricsFontIssue.LOW_MEMORY -> R.string.lyrics_settings_low_memory
    LyricsFontIssue.FONT_BUDGET_UNAVAILABLE -> R.string.appearance_font_budget_unavailable
    LyricsFontIssue.SETTINGS_UNAVAILABLE -> R.string.lyrics_settings_read_required
    LyricsFontIssue.SAVE_FAILED -> R.string.lyrics_settings_save_failed
    LyricsFontIssue.SETTINGS_CONFLICT -> R.string.lyrics_settings_conflict
    LyricsFontIssue.INVALID_INPUT, LyricsFontIssue.UNSUPPORTED_FORMAT, LyricsFontIssue.INVALID_FONT,
    LyricsFontIssue.INTEGRITY_FAILED, LyricsFontIssue.NATIVE_DECODE_FAILED -> R.string.lyrics_font_invalid
}
