package io.github.onlinemo.meloxwatch.ui.dsp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.playback.dsp.BuiltinDspPresets
import io.github.onlinemo.meloxwatch.playback.dsp.DspActualState
import io.github.onlinemo.meloxwatch.playback.dsp.DspLimits
import io.github.onlinemo.meloxwatch.playback.dsp.DspPreset
import io.github.onlinemo.meloxwatch.playback.dsp.DspRuntimeIssue
import io.github.onlinemo.meloxwatch.playback.dsp.DspSettings
import io.github.onlinemo.meloxwatch.playback.dsp.DspSettingsIssue
import io.github.onlinemo.meloxwatch.playback.dsp.ReplayGainMode
import io.github.onlinemo.meloxwatch.playback.dsp.ReplayGainTagSource
import io.github.onlinemo.meloxwatch.ui.themes.LocalWatchThemeColors
import io.github.onlinemo.meloxwatch.ui.themes.LocalWatchThemeFonts
import io.github.onlinemo.meloxwatch.ui.watch.WatchActionRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchCheckRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchChoiceRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchSettingStepper
import io.github.onlinemo.meloxwatch.ui.watch.WatchSettingTitle
import io.github.onlinemo.meloxwatch.ui.watch.WatchSwitchRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchText
import io.github.onlinemo.meloxwatch.ui.watch.watchRowMinimum
import io.github.onlinemo.meloxwatch.ui.watch.watchSpacing
import java.util.Locale

private enum class DspSection { OVERVIEW, EQUALIZER, PRESETS, REPLAY_GAIN, CHANNELS, LIMITER }

/** 外层提供圆屏安全区及滚动；所有设置均复用完整触区的 Foundation 控件。 */
@Composable
fun WatchDspSettings(
    state: DspEditorState,
    actual: DspActualState,
    model: DspViewModel,
    visible: Boolean,
    onBack: () -> Unit,
) {
    if (!visible) return
    var sectionName by rememberSaveable { mutableStateOf(DspSection.OVERVIEW.name) }
    var confirmRecovery by remember(state.saved.recoveryToken) { mutableStateOf(false) }
    val section = DspSection.entries.firstOrNull { it.name == sectionName } ?: DspSection.OVERVIEW
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(watchSpacing(10.dp))) {
        WatchSettingTitle(stringResource(R.string.dsp_title))
        DspActualSummary(actual)
        if (state.working) WatchText(stringResource(R.string.dsp_working))
        if (state.backupBusy) WatchText(stringResource(R.string.dsp_backup_busy))
        state.storageIssue?.let { WatchText(stringResource(settingsIssueText(it)), error = true) }
        state.editorIssue?.let { WatchText(stringResource(editorIssueText(it)), error = true) }
        if (state.savedFeedback) WatchText(stringResource(R.string.dsp_saved))
        if (state.requiresRead || state.storageIssue != null) {
            WatchActionRow(stringResource(R.string.dsp_read), !state.working && !state.backupBusy, onClick = model::retryRead)
        }
        val recoveryToken = state.saved.recoveryToken
        if (state.saved.issue == DspSettingsIssue.INVALID_SETTINGS && recoveryToken != null) {
            if (confirmRecovery) {
                WatchText(stringResource(R.string.dsp_recovery_confirm))
                WatchActionRow(stringResource(R.string.dsp_recover), !state.working && !state.backupBusy,
                    onClick = {
                        model.recoverDefaults(recoveryToken)
                        confirmRecovery = false
                    })
                WatchActionRow(stringResource(R.string.dsp_keep), !state.working, onClick = { confirmRecovery = false })
            } else WatchActionRow(stringResource(R.string.dsp_recover), !state.working && !state.backupBusy,
                onClick = { confirmRecovery = true })
        }
        val draft = state.draft
        if (draft != null) {
            when (section) {
                DspSection.OVERVIEW -> {
                    WatchText(stringResource(R.string.dsp_scope))
                    WatchActionRow(stringResource(R.string.dsp_eq), onClick = { sectionName = DspSection.EQUALIZER.name })
                    WatchActionRow(stringResource(R.string.dsp_presets), onClick = { sectionName = DspSection.PRESETS.name })
                    WatchActionRow(stringResource(R.string.dsp_replay_gain), onClick = { sectionName = DspSection.REPLAY_GAIN.name })
                    WatchActionRow(stringResource(R.string.dsp_channels), onClick = { sectionName = DspSection.CHANNELS.name })
                    WatchActionRow(stringResource(R.string.dsp_limiter), onClick = { sectionName = DspSection.LIMITER.name })
                }
                DspSection.EQUALIZER -> EqualizerSettings(draft, actual, state.editable, model)
                DspSection.PRESETS -> PresetSettings(state, model)
                DspSection.REPLAY_GAIN -> ReplayGainSettings(draft, actual, state.editable, model)
                DspSection.CHANNELS -> ChannelSettings(draft, actual, state.editable, model)
                DspSection.LIMITER -> LimiterSettings(draft, state.editable, model)
            }
            if (section != DspSection.OVERVIEW) WatchActionRow(stringResource(R.string.dsp_overview),
                onClick = { sectionName = DspSection.OVERVIEW.name })
            WatchSwitchRow(stringResource(R.string.dsp_preview), state.previewEnabled,
                state.editable && actual.processing && actual.issue == null, model::enablePreview)
            WatchText(stringResource(R.string.dsp_preview_note))
            if (state.dirty) WatchText(stringResource(R.string.dsp_unsaved))
            WatchActionRow(stringResource(R.string.dsp_save), state.editable && state.dirty, primary = true, onClick = model::save)
            WatchActionRow(stringResource(R.string.dsp_defaults), state.editable, onClick = model::defaults)
            WatchActionRow(stringResource(R.string.dsp_cancel), !state.working, onClick = model::cancel)
        }
        WatchActionRow(stringResource(R.string.dsp_back), onClick = onBack)
    }
}

@Composable
private fun DspActualSummary(actual: DspActualState) {
    actual.issue?.let { WatchText(stringResource(when (it) {
        DspRuntimeIssue.WAITING_PCM -> R.string.dsp_waiting
        DspRuntimeIssue.BACKEND_UNSUPPORTED -> R.string.dsp_backend_unsupported
        DspRuntimeIssue.OUTPUT_UNSUPPORTED -> R.string.dsp_output_unsupported
        DspRuntimeIssue.CLOSED -> R.string.dsp_no_player
    })) }
    if (actual.processing) {
        WatchText(stringResource(R.string.dsp_format, actual.sampleRate ?: 0, actual.channels ?: 0))
        if (actual.requested.equalizerEnabled) WatchText(stringResource(R.string.dsp_actual_eq,
            actual.effectiveBandIndices.size))
        else WatchText(stringResource(R.string.dsp_actual_eq_off))
        WatchText(stringResource(if (actual.limiterEnabled) R.string.dsp_actual_limiter_on else R.string.dsp_actual_limiter_off))
    }
}

@Composable
private fun EqualizerSettings(draft: DspSettings, actual: DspActualState, editable: Boolean, model: DspViewModel) {
    WatchSettingTitle(stringResource(R.string.dsp_eq))
    WatchSwitchRow(stringResource(R.string.dsp_eq_enabled), draft.equalizerEnabled, editable,
        onChange = { model.change(draft.copy(equalizerEnabled = it)) })
    WatchText(stringResource(R.string.dsp_eq_range))
    for ((index, frequency) in DspLimits.FREQUENCIES_HZ.withIndex()) {
        val supported = actual.sampleRate?.let { frequency < it * 0.45 } ?: true
        val gain = draft.bandGainTenthsDb[index]
        WatchSettingStepper(stringResource(R.string.dsp_band, frequency), dbText(gain), editable && supported,
            gain > -120, gain < 120,
            onDecrease = { model.change(draft.copy(selectedPresetId = null,
                bandGainTenthsDb = draft.bandGainTenthsDb.mapIndexed { band, value ->
                    if (band == index) (value - 10).coerceIn(-120, 120) else value })) },
            onIncrease = { model.change(draft.copy(selectedPresetId = null,
                bandGainTenthsDb = draft.bandGainTenthsDb.mapIndexed { band, value ->
                    if (band == index) (value + 10).coerceIn(-120, 120) else value })) })
        if (!supported) WatchText(stringResource(R.string.dsp_band_unavailable))
    }
}

@Composable
private fun PresetSettings(state: DspEditorState, model: DspViewModel) {
    WatchSettingTitle(stringResource(R.string.dsp_presets))
    val draft = state.draft ?: return
    var name by remember { mutableStateOf("") }
    var renameTarget by remember(state.baseRevision) { mutableStateOf<DspPreset?>(null) }
    var deleteId by remember(state.baseRevision) { mutableStateOf<String?>(null) }
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(watchSpacing(8.dp))) {
        for (preset in BuiltinDspPresets.values) {
            WatchChoiceRow(stringResource(when (preset.id) {
                "builtin_bass" -> R.string.dsp_preset_bass
                "builtin_voice" -> R.string.dsp_preset_voice
                else -> R.string.dsp_preset_flat
            }), draft.selectedPresetId == preset.id, state.editable, onSelect = { model.selectPreset(preset.id) })
        }
        for (preset in state.presets) {
            WatchChoiceRow(preset.name, draft.selectedPresetId == preset.id, state.editable,
                onSelect = { model.selectPreset(preset.id) })
            WatchActionRow(stringResource(R.string.dsp_rename_preset, preset.name), state.editable, onClick = {
                renameTarget = preset
                name = preset.name
            })
            WatchActionRow(stringResource(R.string.dsp_replace_curve, preset.name), state.editable,
                onClick = { model.saveNamedPreset(preset.name, preset.id) })
            if (deleteId == preset.id) {
                WatchText(stringResource(R.string.dsp_delete_confirm, preset.name))
                WatchActionRow(stringResource(R.string.dsp_delete_yes), state.editable, onClick = {
                    model.deletePreset(preset.id)
                    deleteId = null
                })
                WatchActionRow(stringResource(R.string.dsp_keep), state.editable, onClick = { deleteId = null })
            } else WatchActionRow(stringResource(R.string.dsp_delete_preset, preset.name), state.editable,
                onClick = { deleteId = preset.id })
        }
    }
    PresetNameInput(name, state.editable) { if (it.length <= DspLimits.MAX_NAME_CHARACTERS) name = it }
    val renaming = renameTarget
    if (renaming != null) {
        WatchActionRow(stringResource(R.string.dsp_rename), state.editable && name.isNotBlank(), onClick = {
            model.renamePreset(renaming.id, name)
            renameTarget = null
        })
        WatchActionRow(stringResource(R.string.dsp_keep), state.editable, onClick = {
            renameTarget = null
            name = ""
        })
    } else {
        WatchActionRow(stringResource(R.string.dsp_named_draft), state.editable && name.isNotBlank(),
            onClick = { model.saveNamedPreset(name) })
    }
    WatchText(stringResource(R.string.dsp_named_note))
}

@Composable
private fun ReplayGainSettings(draft: DspSettings, actual: DspActualState, editable: Boolean, model: DspViewModel) {
    WatchSettingTitle(stringResource(R.string.dsp_replay_gain))
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(watchSpacing(8.dp))) {
        for (mode in ReplayGainMode.entries) WatchChoiceRow(stringResource(when (mode) {
            ReplayGainMode.OFF -> R.string.dsp_gain_off
            ReplayGainMode.TRACK -> R.string.dsp_gain_track
            ReplayGainMode.ALBUM -> R.string.dsp_gain_album
        }), draft.replayGainMode == mode, editable, onSelect = { model.change(draft.copy(replayGainMode = mode)) })
    }
    val preamp = draft.preampTenthsDb
    WatchSettingStepper(stringResource(R.string.dsp_preamp), dbText(preamp), editable, preamp > -120, preamp < 120,
        onDecrease = { model.change(draft.copy(preampTenthsDb = (preamp - 10).coerceIn(-120, 120))) },
        onIncrease = { model.change(draft.copy(preampTenthsDb = (preamp + 10).coerceIn(-120, 120))) })
    WatchCheckRow(stringResource(R.string.dsp_peak_protection), draft.preventClipping, editable,
        onChange = { model.change(draft.copy(preventClipping = it)) })
    WatchText(stringResource(R.string.dsp_peak_note))
    WatchText(stringResource(when (actual.replayGainSource) {
        ReplayGainTagSource.NONE -> R.string.dsp_tag_none
        ReplayGainTagSource.ID3 -> R.string.dsp_tag_id3
        ReplayGainTagSource.VORBIS -> R.string.dsp_tag_vorbis
        ReplayGainTagSource.MIXED -> R.string.dsp_tag_mixed
    }))
    actual.replayGainTags.trackGainDb?.let { WatchText(stringResource(R.string.dsp_track_tag, realDbText(it))) }
    actual.replayGainTags.albumGainDb?.let { WatchText(stringResource(R.string.dsp_album_tag, realDbText(it))) }
    actual.replayGainTags.trackPeak?.let {
        WatchText(stringResource(R.string.dsp_track_peak, String.format(Locale.ROOT, "%.4f", it)))
    }
    actual.replayGainTags.albumPeak?.let {
        WatchText(stringResource(R.string.dsp_album_peak, String.format(Locale.ROOT, "%.4f", it)))
    }
    if (actual.replayGainTagMissing) WatchText(stringResource(R.string.dsp_gain_missing))
    if (actual.replayGainTagIssue != null) WatchText(stringResource(R.string.dsp_gain_invalid), error = true)
    if (actual.replayGainPositiveGainHeld) WatchText(stringResource(R.string.dsp_gain_held))
    if (actual.replayGainClamped) WatchText(stringResource(R.string.dsp_gain_clamped))
}

@Composable
private fun ChannelSettings(draft: DspSettings, actual: DspActualState, editable: Boolean, model: DspViewModel) {
    WatchSettingTitle(stringResource(R.string.dsp_channels))
    val supported = actual.issue != DspRuntimeIssue.BACKEND_UNSUPPORTED &&
        actual.issue != DspRuntimeIssue.OUTPUT_UNSUPPORTED && (actual.channels == null || actual.channels == 2)
    if (!supported) WatchText(stringResource(R.string.dsp_channels_unavailable))
    WatchSettingStepper(stringResource(R.string.dsp_left), stringResource(R.string.dsp_percent, draft.leftPercent),
        editable && supported, draft.leftPercent > 0, draft.leftPercent < 100,
        onDecrease = { model.change(draft.copy(leftPercent = draft.leftPercent - 5)) },
        onIncrease = { model.change(draft.copy(leftPercent = draft.leftPercent + 5)) })
    WatchSettingStepper(stringResource(R.string.dsp_right), stringResource(R.string.dsp_percent, draft.rightPercent),
        editable && supported, draft.rightPercent > 0, draft.rightPercent < 100,
        onDecrease = { model.change(draft.copy(rightPercent = draft.rightPercent - 5)) },
        onIncrease = { model.change(draft.copy(rightPercent = draft.rightPercent + 5)) })
    WatchActionRow(stringResource(R.string.dsp_balance), editable, onClick = {
        model.change(draft.copy(leftPercent = 100, rightPercent = 100))
    })
}

@Composable
private fun LimiterSettings(draft: DspSettings, editable: Boolean, model: DspViewModel) {
    WatchSettingTitle(stringResource(R.string.dsp_limiter))
    WatchSwitchRow(stringResource(R.string.dsp_limiter_enabled), draft.limiterEnabled, editable,
        onChange = { model.change(draft.copy(limiterEnabled = it)) })
    WatchText(stringResource(R.string.dsp_limiter_note))
    val threshold = draft.limiterThresholdTenthsDb
    WatchSettingStepper(stringResource(R.string.dsp_threshold), dbText(threshold), editable, threshold > -120, threshold < 0,
        onDecrease = { model.change(draft.copy(limiterThresholdTenthsDb = (threshold - 5).coerceIn(-120, 0))) },
        onIncrease = { model.change(draft.copy(limiterThresholdTenthsDb = (threshold + 5).coerceIn(-120, 0))) })
    WatchSettingStepper(stringResource(R.string.dsp_attack), stringResource(R.string.dsp_micros, draft.limiterAttackMicros),
        editable, draft.limiterAttackMicros > 100, draft.limiterAttackMicros < 5_000,
        onDecrease = { model.change(draft.copy(limiterAttackMicros = (draft.limiterAttackMicros - 100).coerceIn(100, 5_000))) },
        onIncrease = { model.change(draft.copy(limiterAttackMicros = (draft.limiterAttackMicros + 100).coerceIn(100, 5_000))) })
    WatchSettingStepper(stringResource(R.string.dsp_release), stringResource(R.string.dsp_millis, draft.limiterReleaseMillis),
        editable, draft.limiterReleaseMillis > 20, draft.limiterReleaseMillis < 1_000,
        onDecrease = { model.change(draft.copy(limiterReleaseMillis = (draft.limiterReleaseMillis - 10).coerceIn(20, 1_000))) },
        onIncrease = { model.change(draft.copy(limiterReleaseMillis = (draft.limiterReleaseMillis + 10).coerceIn(20, 1_000))) })
    WatchSettingStepper(stringResource(R.string.dsp_lookahead), stringResource(R.string.dsp_millis, draft.limiterLookaheadMillis),
        editable, draft.limiterLookaheadMillis > 1, draft.limiterLookaheadMillis < 10,
        onDecrease = { model.change(draft.copy(limiterLookaheadMillis = draft.limiterLookaheadMillis - 1)) },
        onIncrease = { model.change(draft.copy(limiterLookaheadMillis = draft.limiterLookaheadMillis + 1)) })
}

@Composable
private fun PresetNameInput(value: String, enabled: Boolean, onValue: (String) -> Unit) {
    val colors = LocalWatchThemeColors.current
    val fonts = LocalWatchThemeFonts.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val label = stringResource(R.string.dsp_preset_name)
    WatchText(label, primary = true)
    BasicTextField(value, onValueChange = onValue, enabled = enabled, singleLine = true,
        textStyle = TextStyle(color = if (enabled) colors.text else colors.disabled,
            fontFamily = fonts.originalFamily, fontSynthesis = FontSynthesis.None, fontSize = 14.sp, lineHeight = 20.sp),
        cursorBrush = SolidColor(colors.text),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            keyboard?.hide()
            focus.clearFocus()
        }),
        modifier = Modifier.fillMaxWidth().heightIn(min = watchRowMinimum()).semantics { contentDescription = label }
            .background(colors.surface, RoundedCornerShape(watchSpacing(10.dp)))
            .border(1.dp, colors.outline, RoundedCornerShape(watchSpacing(10.dp)))
            .padding(watchSpacing(12.dp)))
}

private fun dbText(value: Int): String = realDbText(value / 10.0)
private fun realDbText(value: Double): String = String.format(Locale.ROOT, "%+.1f dB", value)

private fun settingsIssueText(issue: DspSettingsIssue): Int = when (issue) {
    DspSettingsIssue.BUSY -> R.string.dsp_backup_busy
    DspSettingsIssue.CONFLICT -> R.string.dsp_conflict
    DspSettingsIssue.INVALID_SETTINGS -> R.string.dsp_invalid_settings
    DspSettingsIssue.NEWER_SCHEMA -> R.string.dsp_newer_settings
    DspSettingsIssue.SETTINGS_TOO_LARGE -> R.string.dsp_large_settings
    DspSettingsIssue.STORAGE_UNAVAILABLE -> R.string.dsp_storage_unavailable
    DspSettingsIssue.UNRECOVERABLE_SETTINGS -> R.string.dsp_unrecoverable
    DspSettingsIssue.REVISION_EXHAUSTED -> R.string.dsp_revision_exhausted
}

private fun editorIssueText(issue: DspEditorIssue): Int = when (issue) {
    DspEditorIssue.PRESET_NAME_INVALID -> R.string.dsp_name_invalid
    DspEditorIssue.PRESET_NAME_CONFLICT -> R.string.dsp_name_conflict
    DspEditorIssue.PRESET_LIMIT -> R.string.dsp_preset_limit
    DspEditorIssue.PREVIEW_CONFLICT -> R.string.dsp_conflict
    DspEditorIssue.READ_REQUIRED -> R.string.dsp_read
}
