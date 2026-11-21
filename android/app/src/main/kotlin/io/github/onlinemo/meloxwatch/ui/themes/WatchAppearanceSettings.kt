package io.github.onlinemo.meloxwatch.ui.themes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.data.themes.AppLanguage
import io.github.onlinemo.meloxwatch.data.themes.AppearanceIssue
import io.github.onlinemo.meloxwatch.data.themes.BuiltinTheme
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundIssue
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundLimits
import io.github.onlinemo.meloxwatch.data.themes.ThemeBackgroundMode
import io.github.onlinemo.meloxwatch.data.themes.ThemeChoice
import io.github.onlinemo.meloxwatch.data.themes.ThemeIssue
import io.github.onlinemo.meloxwatch.data.themes.ThemePreview
import io.github.onlinemo.meloxwatch.ui.watch.WatchActionRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchCheckRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchChoiceRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchSettingStepper
import io.github.onlinemo.meloxwatch.ui.watch.WatchSettingTitle
import io.github.onlinemo.meloxwatch.ui.watch.WatchSwitchRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchText
import kotlin.math.roundToInt

private enum class AppearanceSection { OVERVIEW, THEMES, BACKGROUND, LAYOUT }

/** The host supplies round-safe scrolling, the shared resource preview and the actual picker. */
@Composable
fun WatchAppearanceSettings(
    state: AppearanceEditorState,
    model: AppearanceViewModel,
    visible: Boolean,
    onLaunchPicker: (AppearancePickerTicket) -> Unit,
    onOpenScreenShape: () -> Unit,
    onBack: () -> Unit,
    preview: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    var sectionName by rememberSaveable { mutableStateOf(AppearanceSection.OVERVIEW.name) }
    var showSavedValues by rememberSaveable { mutableStateOf(false) }
    var confirmRecovery by remember(state.appearance.recoveryToken) { mutableStateOf(false) }
    val section = AppearanceSection.entries.firstOrNull { it.name == sectionName } ?: AppearanceSection.OVERVIEW
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        WatchSettingTitle(stringResource(R.string.appearance_title))
        if (state.busy) WatchText(stringResource(R.string.appearance_working))
        if (state.pickerResultPending) WatchText(stringResource(R.string.appearance_picker_pending))
        else if (state.picker != null) WatchText(stringResource(R.string.appearance_picker_open))
        if (state.requiresRead) WatchText(stringResource(R.string.appearance_read_required), error = true)
        state.appearanceIssue?.let { WatchText(stringResource(appearanceIssueMessage(it)), error = true) }
        state.themeIssue?.let { WatchText(stringResource(themeIssueMessage(it)), error = true) }
        state.backgroundIssue?.let { WatchText(stringResource(backgroundIssueMessage(it)), error = true) }
        state.pickerUnavailable?.let {
            WatchText(stringResource(if (it == AppearancePicker.THEME) R.string.appearance_theme_picker_unavailable
                else R.string.appearance_image_picker_unavailable), error = true)
        }
        state.savedFeedback?.let { saved ->
            WatchText(stringResource(when (saved) {
                AppearanceOperation.APPLY_THEME -> R.string.appearance_theme_saved
                AppearanceOperation.SAVE_BACKGROUND -> R.string.appearance_background_saved
                else -> R.string.appearance_options_saved
            }))
        }
        val needsSavedValues = state.requiresRead || state.appearanceIssue != null || state.themeIssue != null || state.backgroundIssue != null
        if (needsSavedValues || state.appearance.issue != null || state.theme.issue != null || state.background.issue != null) {
            WatchActionRow(stringResource(R.string.appearance_retry_read), !state.busy && state.foreground,
                onClick = model::retryRead)
        }
        if (state.appearance.issue == AppearanceIssue.INVALID_SETTINGS && state.appearance.recoveryToken != null) {
            if (confirmRecovery) {
                WatchText(stringResource(R.string.appearance_recovery_question), primary = true)
                WatchActionRow(stringResource(R.string.appearance_recovery_confirm), state.editable, primary = true) {
                    confirmRecovery = false
                    model.recoverAppearanceDefaults()
                }
                WatchActionRow(stringResource(R.string.appearance_recovery_cancel), onClick = { confirmRecovery = false })
            } else WatchActionRow(stringResource(R.string.appearance_recovery), state.editable) { confirmRecovery = true }
        }
        if (section != AppearanceSection.OVERVIEW) {
            WatchActionRow(stringResource(R.string.appearance_sections)) { sectionName = AppearanceSection.OVERVIEW.name }
        }
        if (section == AppearanceSection.OVERVIEW) {
            CurrentThemeValue(state)
            if (state.background.settings != null) WatchText(stringResource(R.string.appearance_saved_background,
                stringResource(backgroundModeLabel(state.background.effective.mode))))
            else WatchText(stringResource(R.string.appearance_saved_unavailable))
            WatchActionRow(stringResource(R.string.appearance_themes)) { sectionName = AppearanceSection.THEMES.name }
            WatchActionRow(stringResource(R.string.appearance_background)) { sectionName = AppearanceSection.BACKGROUND.name }
            WatchActionRow(stringResource(R.string.appearance_layout)) { sectionName = AppearanceSection.LAYOUT.name }
        } else {
            WatchActionRow(stringResource(if (showSavedValues) R.string.appearance_hide_saved else R.string.appearance_show_saved)) {
                showSavedValues = !showSavedValues
            }
            if (needsSavedValues || showSavedValues) CurrentValues(state)
            WatchSwitchRow(stringResource(R.string.appearance_show_preview), state.previewing, state.editable,
                onChange = { model.showPreview(it) })
            if (state.previewing) {
                WatchText(stringResource(R.string.appearance_unsaved_preview))
                preview()
            }
            when (section) {
                AppearanceSection.THEMES -> ThemeSettings(state, model, onLaunchPicker)
                AppearanceSection.BACKGROUND -> BackgroundSettings(state, model, onLaunchPicker)
                AppearanceSection.LAYOUT -> LayoutSettings(state, model, onOpenScreenShape)
                AppearanceSection.OVERVIEW -> Unit
            }
        }
        WatchActionRow(stringResource(R.string.appearance_cancel_and_back), onClick = {
            model.cancelDraft()
            onBack()
        })
    }
}

@Composable
private fun CurrentValues(state: AppearanceEditorState) {
    WatchText(stringResource(R.string.appearance_current_values), primary = true)
    CurrentThemeValue(state)
    val appearance = state.appearance.settings?.options
    if (appearance == null) WatchText(stringResource(R.string.appearance_saved_unavailable))
    else {
        WatchText(stringResource(R.string.appearance_current_layout, appearance.scalePercent,
            appearance.horizontalMarginDp, appearance.verticalMarginDp))
        WatchText(stringResource(R.string.appearance_current_compact,
            stringResource(if (appearance.compactPlaybackTitle) R.string.appearance_on else R.string.appearance_off)))
        WatchText(stringResource(R.string.appearance_current_bright,
            stringResource(if (appearance.lyricsKeepBright) R.string.appearance_on else R.string.appearance_off)))
        WatchText(stringResource(R.string.appearance_current_language, stringResource(languageLabel(appearance.language))))
    }
    val background = state.background.settings?.options
    if (background == null) WatchText(stringResource(R.string.appearance_saved_unavailable))
    else WatchText(stringResource(R.string.appearance_current_background, stringResource(backgroundModeLabel(background.mode)),
        background.blurLevel, background.darkMaskPercent))
}

@Composable
private fun CurrentThemeValue(state: AppearanceEditorState) {
    val selected = state.theme.selected
    if (selected != null && state.theme.revision != null) {
        val name = when (selected) {
            is ThemeChoice.Builtin -> stringResource(if (selected.theme == BuiltinTheme.DARK)
                R.string.appearance_theme_dark else R.string.appearance_theme_light)
            is ThemeChoice.Installed -> state.theme.installed.firstOrNull { it.ref == selected.ref }?.displayName
                ?: stringResource(R.string.appearance_theme_missing)
        }
        WatchText(stringResource(R.string.appearance_saved_theme, name))
    }
    if (selected == null || state.theme.revision == null || selected != state.theme.effective)
        WatchText(stringResource(R.string.appearance_fallback_theme, state.theme.effectiveManifest.displayName))
}

@Composable
private fun ThemeSettings(state: AppearanceEditorState, model: AppearanceViewModel, onLaunchPicker: (AppearancePickerTicket) -> Unit) {
    WatchSettingTitle(stringResource(R.string.appearance_themes))
    WatchText(stringResource(R.string.appearance_theme_preview_help))
    if (state.needsThemeSelection && !state.busy) WatchText(stringResource(R.string.appearance_theme_reselect), error = true)
    val chosen = state.themePreview?.installedChoice ?: state.theme.selected
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WatchChoiceRow(stringResource(R.string.appearance_theme_dark), chosen == ThemeChoice.Builtin(BuiltinTheme.DARK), state.editable) {
            model.previewTheme(ThemeChoice.Builtin(BuiltinTheme.DARK))
        }
        WatchChoiceRow(stringResource(R.string.appearance_theme_light), chosen == ThemeChoice.Builtin(BuiltinTheme.LIGHT), state.editable) {
            model.previewTheme(ThemeChoice.Builtin(BuiltinTheme.LIGHT))
        }
        state.theme.installed.forEach { installed ->
            val choice = ThemeChoice.Installed(installed.ref)
            WatchChoiceRow(installed.displayName, chosen == choice, state.editable) { model.previewTheme(choice) }
        }
    }
    WatchActionRow(stringResource(R.string.appearance_import_theme), state.editable) {
        model.requestPicker(AppearancePicker.THEME)?.let(onLaunchPicker)
    }
    state.theme.previous?.let { previous ->
        WatchActionRow(stringResource(R.string.appearance_previous_theme), state.editable) { model.previewTheme(previous) }
    }
    state.themePreview?.let { candidate ->
        WatchText(stringResource(R.string.appearance_candidate_theme, candidate.manifest.displayName), primary = true)
        WatchText(stringResource(R.string.appearance_theme_author, candidate.manifest.author))
        ThemeLicenses(candidate)
        if (candidate.sameIdInstallations.isNotEmpty()) {
            WatchText(stringResource(R.string.appearance_replacement_help), primary = true)
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                candidate.sameIdInstallations.forEach { ref ->
                    val name = state.theme.installed.firstOrNull { it.ref == ref }?.displayName ?: ref.themeId
                    WatchChoiceRow(stringResource(R.string.appearance_replace_theme, name), state.replacement == ref, state.editable) {
                        model.chooseReplacement(ref)
                    }
                }
            }
        }
        if (candidate.manifest.fonts.isNotEmpty()) {
            WatchText(stringResource(R.string.appearance_theme_font_help))
            WatchCheckRow(stringResource(R.string.lyrics_font_license_acknowledgment), state.fontLicenseAccepted, state.editable,
                onChange = { model.acknowledgeFontLicense(it) })
        }
        if (candidate.expectedRevision != state.theme.revision) WatchText(stringResource(R.string.appearance_theme_changed), error = true)
        WatchActionRow(stringResource(R.string.appearance_apply_theme), state.canApplyTheme, primary = true) { model.applyTheme() }
    }
}

/** Only one resource notice is laid out at a time; a package cannot compose 64 long licenses. */
@Composable
private fun ThemeLicenses(preview: ThemePreview) {
    var expanded by remember(preview.token) { mutableStateOf(false) }
    var index by remember(preview.token) { mutableIntStateOf(-1) }
    WatchActionRow(stringResource(if (expanded) R.string.appearance_hide_licenses else R.string.appearance_show_licenses)) { expanded = !expanded }
    if (!expanded) return
    val resources = preview.manifest.resources
    val resource = resources.getOrNull(index)
    WatchSettingStepper(stringResource(R.string.appearance_license),
        resource?.id ?: stringResource(R.string.appearance_package_license), enabled = true,
        canDecrease = index >= 0, canIncrease = index < resources.lastIndex,
        onDecrease = { index = (index - 1).coerceAtLeast(-1) },
        onIncrease = { index = (index + 1).coerceAtMost(resources.lastIndex) },
        decreaseLabel = stringResource(R.string.appearance_previous_notice), increaseLabel = stringResource(R.string.appearance_next_notice))
    val notice = resource?.license ?: preview.manifest.license
    WatchText(notice.name, primary = true)
    WatchText(notice.text)
}

@Composable
private fun BackgroundSettings(state: AppearanceEditorState, model: AppearanceViewModel, onLaunchPicker: (AppearancePickerTicket) -> Unit) {
    WatchSettingTitle(stringResource(R.string.appearance_background))
    WatchText(stringResource(R.string.appearance_background_help))
    if (state.needsImageSelection && !state.busy) WatchText(stringResource(R.string.appearance_image_reselect), error = true)
    val options = state.draftBackground
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeBackgroundMode.entries.forEach { mode ->
            WatchChoiceRow(stringResource(backgroundModeLabel(mode)), options.mode == mode, state.editable) {
                model.changeBackground(options.copy(mode = mode))
            }
        }
    }
    if (options.mode == ThemeBackgroundMode.CUSTOM_IMAGE) {
        WatchActionRow(stringResource(R.string.appearance_select_image), state.editable) {
            model.requestPicker(AppearancePicker.IMAGE)?.let(onLaunchPicker)
        }
        if (options.customImage == null) WatchText(stringResource(R.string.appearance_image_required))
        else {
            state.imagePreview?.let { WatchText(stringResource(R.string.appearance_image_preview_name, it.displayName)) }
            WatchText(stringResource(R.string.appearance_crop_help))
            WatchSettingStepper(stringResource(R.string.appearance_crop_zoom),
                stringResource(R.string.appearance_percent, (options.crop.zoom * 100).roundToInt()), state.editable,
                options.crop.zoom > 1f, options.crop.zoom < 4f,
                onDecrease = { model.changeCrop(options.crop.copy(zoom = (options.crop.zoom - 0.25f).coerceAtLeast(1f))) },
                onIncrease = { model.changeCrop(options.crop.copy(zoom = (options.crop.zoom + 0.25f).coerceAtMost(4f))) })
            WatchSettingStepper(stringResource(R.string.appearance_crop_horizontal),
                stringResource(R.string.appearance_percent, (options.crop.horizontal * 100).roundToInt()), state.editable,
                options.crop.horizontal > 0f, options.crop.horizontal < 1f,
                onDecrease = { model.changeCrop(options.crop.copy(horizontal = (options.crop.horizontal - 0.1f).coerceAtLeast(0f))) },
                onIncrease = { model.changeCrop(options.crop.copy(horizontal = (options.crop.horizontal + 0.1f).coerceAtMost(1f))) })
            WatchSettingStepper(stringResource(R.string.appearance_crop_vertical),
                stringResource(R.string.appearance_percent, (options.crop.vertical * 100).roundToInt()), state.editable,
                options.crop.vertical > 0f, options.crop.vertical < 1f,
                onDecrease = { model.changeCrop(options.crop.copy(vertical = (options.crop.vertical - 0.1f).coerceAtLeast(0f))) },
                onIncrease = { model.changeCrop(options.crop.copy(vertical = (options.crop.vertical + 0.1f).coerceAtMost(1f))) })
        }
    }
    if (options.supportsBlur) {
        WatchSettingStepper(stringResource(R.string.appearance_blur),
            stringResource(R.string.appearance_blur_level, options.blurLevel, PlaybackBackgroundLimits.BLUR_MAX), state.editable,
            options.blurLevel > 0, options.blurLevel < PlaybackBackgroundLimits.BLUR_MAX,
            onDecrease = { model.changeBackground(options.copy(blurLevel = options.blurLevel - 1)) },
            onIncrease = { model.changeBackground(options.copy(blurLevel = options.blurLevel + 1)) })
    } else WatchText(stringResource(R.string.appearance_blur_unavailable))
    WatchSettingStepper(stringResource(R.string.appearance_dark_mask),
        stringResource(R.string.appearance_percent, options.darkMaskPercent), state.editable,
        options.darkMaskPercent > 0, options.darkMaskPercent < 100,
        onDecrease = { model.changeBackground(options.copy(darkMaskPercent = (options.darkMaskPercent - 5).coerceAtLeast(0))) },
        onIncrease = { model.changeBackground(options.copy(darkMaskPercent = (options.darkMaskPercent + 5).coerceAtMost(100))) })
    WatchText(stringResource(R.string.appearance_mask_help))
    if (state.background.settings != null && state.backgroundBaseRevision != state.background.settings.revision) {
        WatchText(stringResource(R.string.appearance_background_conflict_help), error = true)
        WatchActionRow(stringResource(R.string.appearance_review_changes), state.editable) { model.reviewBackgroundDraft() }
    }
    WatchActionRow(stringResource(R.string.appearance_background_defaults), state.editable) { model.resetBackgroundDraft() }
    WatchActionRow(stringResource(R.string.appearance_save_background), state.canSaveBackground, primary = true) { model.saveBackground() }
}

@Composable
private fun LayoutSettings(state: AppearanceEditorState, model: AppearanceViewModel, onOpenScreenShape: () -> Unit) {
    WatchSettingTitle(stringResource(R.string.appearance_layout))
    val options = state.draftAppearance
    WatchSettingStepper(stringResource(R.string.appearance_scale), stringResource(R.string.appearance_percent, options.scalePercent), state.editable,
        options.scalePercent > 85, options.scalePercent < 120,
        onDecrease = { model.changeAppearance(options.copy(scalePercent = (options.scalePercent - 5).coerceAtLeast(85))) },
        onIncrease = { model.changeAppearance(options.copy(scalePercent = (options.scalePercent + 5).coerceAtMost(120))) })
    WatchText(stringResource(R.string.appearance_scale_help))
    WatchSettingStepper(stringResource(R.string.appearance_margin_horizontal), stringResource(R.string.appearance_dp, options.horizontalMarginDp), state.editable,
        options.horizontalMarginDp > 0, options.horizontalMarginDp < 24,
        onDecrease = { model.changeAppearance(options.copy(horizontalMarginDp = (options.horizontalMarginDp - 2).coerceAtLeast(0))) },
        onIncrease = { model.changeAppearance(options.copy(horizontalMarginDp = (options.horizontalMarginDp + 2).coerceAtMost(24))) })
    WatchSettingStepper(stringResource(R.string.appearance_margin_vertical), stringResource(R.string.appearance_dp, options.verticalMarginDp), state.editable,
        options.verticalMarginDp > 0, options.verticalMarginDp < 24,
        onDecrease = { model.changeAppearance(options.copy(verticalMarginDp = (options.verticalMarginDp - 2).coerceAtLeast(0))) },
        onIncrease = { model.changeAppearance(options.copy(verticalMarginDp = (options.verticalMarginDp + 2).coerceAtMost(24))) })
    WatchText(stringResource(R.string.appearance_margin_help))
    WatchSwitchRow(stringResource(R.string.appearance_compact_title), options.compactPlaybackTitle, state.editable,
        onChange = { model.changeAppearance(options.copy(compactPlaybackTitle = it)) })
    WatchText(stringResource(R.string.appearance_compact_title_help))
    WatchSwitchRow(stringResource(R.string.appearance_lyrics_bright), options.lyricsKeepBright, state.editable,
        onChange = { model.changeAppearance(options.copy(lyricsKeepBright = it)) })
    WatchText(stringResource(R.string.appearance_lyrics_bright_help))
    WatchText(stringResource(R.string.appearance_language), primary = true)
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppLanguage.entries.forEach { language ->
            WatchChoiceRow(stringResource(languageLabel(language)), options.language == language, state.editable) {
                model.changeAppearance(options.copy(language = language))
            }
        }
    }
    WatchText(stringResource(R.string.appearance_language_help))
    if (state.appearance.settings != null && state.appearanceBaseRevision != state.appearance.settings.revision) {
        WatchText(stringResource(R.string.appearance_options_conflict_help), error = true)
        WatchActionRow(stringResource(R.string.appearance_review_changes), state.editable) { model.reviewAppearanceDraft() }
    }
    WatchActionRow(stringResource(R.string.appearance_options_defaults), state.editable) { model.resetAppearanceDraft() }
    WatchActionRow(stringResource(R.string.appearance_save_options), state.canSaveAppearance, primary = true) { model.saveAppearance() }
    WatchActionRow(stringResource(R.string.appearance_screen_shape), !state.busy && state.picker == null, onClick = onOpenScreenShape)
    WatchText(stringResource(R.string.appearance_shape_help))
}

private fun backgroundModeLabel(mode: ThemeBackgroundMode): Int = when (mode) {
    ThemeBackgroundMode.DEFAULT -> R.string.appearance_background_default
    ThemeBackgroundMode.BLACK -> R.string.appearance_background_black
    ThemeBackgroundMode.COVER_BLUR -> R.string.appearance_background_cover
    ThemeBackgroundMode.FLUID -> R.string.appearance_background_fluid
    ThemeBackgroundMode.CUSTOM_IMAGE -> R.string.appearance_background_image
}

private fun languageLabel(language: AppLanguage): Int = when (language) {
    AppLanguage.SYSTEM -> R.string.appearance_language_system
    AppLanguage.SIMPLIFIED_CHINESE -> R.string.appearance_language_chinese
    AppLanguage.ENGLISH -> R.string.appearance_language_english
}

private fun appearanceIssueMessage(issue: AppearanceIssue): Int = when (issue) {
    AppearanceIssue.INVALID_SETTINGS -> R.string.appearance_invalid_settings
    AppearanceIssue.NEWER_SCHEMA -> R.string.appearance_newer_schema
    AppearanceIssue.SETTINGS_TOO_LARGE -> R.string.appearance_settings_too_large
    AppearanceIssue.UNRECOVERABLE_SETTINGS -> R.string.appearance_unrecoverable_settings
    AppearanceIssue.STORAGE_UNAVAILABLE -> R.string.appearance_storage_failed
    AppearanceIssue.REVISION_EXHAUSTED -> R.string.appearance_revision_exhausted
    AppearanceIssue.CONFLICT -> R.string.appearance_options_conflict_help
}

private fun backgroundIssueMessage(issue: PlaybackBackgroundIssue): Int = when (issue) {
    PlaybackBackgroundIssue.BUSY -> R.string.appearance_resource_busy
    PlaybackBackgroundIssue.INVALID_INPUT, PlaybackBackgroundIssue.INVALID_IMAGE -> R.string.appearance_invalid_image
    PlaybackBackgroundIssue.NO_IMAGE -> R.string.appearance_image_required
    PlaybackBackgroundIssue.PERMISSION_DENIED -> R.string.appearance_permission_denied
    PlaybackBackgroundIssue.SOURCE_UNAVAILABLE -> R.string.appearance_source_unavailable
    PlaybackBackgroundIssue.IMAGE_TOO_LARGE -> R.string.appearance_image_too_large
    PlaybackBackgroundIssue.LOW_MEMORY -> R.string.appearance_low_memory
    PlaybackBackgroundIssue.INPUT_CLOSE_FAILED -> R.string.appearance_input_close_failed
    PlaybackBackgroundIssue.STORAGE_FULL -> R.string.appearance_storage_full
    PlaybackBackgroundIssue.STORAGE_UNAVAILABLE, PlaybackBackgroundIssue.SAVE_FAILED -> R.string.appearance_storage_failed
    PlaybackBackgroundIssue.INTEGRITY_FAILED -> R.string.appearance_integrity_failed
    PlaybackBackgroundIssue.PREVIEW_NOT_FOUND -> R.string.appearance_preview_expired
    PlaybackBackgroundIssue.SETTINGS_CONFLICT, PlaybackBackgroundIssue.OPERATION_CONFLICT -> R.string.appearance_background_conflict_help
    PlaybackBackgroundIssue.RECOVERY_REQUIRED, PlaybackBackgroundIssue.CLEANUP_REQUIRED -> R.string.appearance_cleanup_required
}

internal fun themeIssueMessage(issue: ThemeIssue): Int = when (issue) {
    ThemeIssue.BUSY -> R.string.appearance_resource_busy
    ThemeIssue.PERMISSION_DENIED -> R.string.appearance_permission_denied
    ThemeIssue.SOURCE_UNAVAILABLE -> R.string.appearance_source_unavailable
    ThemeIssue.INPUT_CLOSE_FAILED -> R.string.appearance_input_close_failed
    ThemeIssue.PACKAGE_TOO_LARGE, ThemeIssue.EXPANDED_TOO_LARGE, ThemeIssue.EXPANSION_RATIO, ThemeIssue.TOO_MANY_RESOURCES,
    ThemeIssue.IMAGE_TOO_LARGE, ThemeIssue.STORAGE_QUOTA_EXCEEDED -> R.string.appearance_theme_too_large
    ThemeIssue.UNSUPPORTED_ARCHIVE, ThemeIssue.UNSUPPORTED_SCHEMA, ThemeIssue.UNSUPPORTED_FIELD, ThemeIssue.UNSUPPORTED_RESOURCE -> R.string.appearance_theme_unsupported
    ThemeIssue.INVALID_INPUT, ThemeIssue.INVALID_ARCHIVE, ThemeIssue.INVALID_PATH, ThemeIssue.DUPLICATE_PATH,
    ThemeIssue.LINK_OR_SPECIAL_FILE, ThemeIssue.MALFORMED_MANIFEST, ThemeIssue.MISSING_RESOURCE, ThemeIssue.UNDECLARED_RESOURCE,
    ThemeIssue.INVALID_IMAGE, ThemeIssue.INVALID_ICON, ThemeIssue.INVALID_FONT -> R.string.appearance_theme_invalid
    ThemeIssue.INTEGRITY_FAILED -> R.string.appearance_integrity_failed
    ThemeIssue.FONT_BUDGET_UNAVAILABLE -> R.string.appearance_font_budget_unavailable
    ThemeIssue.FONT_QUOTA_EXCEEDED -> R.string.appearance_font_quota
    ThemeIssue.FONT_LICENSE_ACKNOWLEDGMENT_REQUIRED -> R.string.lyrics_font_license_required
    ThemeIssue.UNREADABLE_PALETTE -> R.string.appearance_palette_unreadable
    ThemeIssue.LOW_MEMORY -> R.string.appearance_low_memory
    ThemeIssue.STORAGE_FULL -> R.string.appearance_storage_full
    ThemeIssue.STORAGE_UNAVAILABLE, ThemeIssue.SAVE_FAILED -> R.string.appearance_storage_failed
    ThemeIssue.CORRUPT_STORAGE, ThemeIssue.RECOVERY_REQUIRED, ThemeIssue.CLEANUP_REQUIRED -> R.string.appearance_cleanup_required
    ThemeIssue.PREVIEW_NOT_FOUND -> R.string.appearance_preview_expired
    ThemeIssue.THEME_NOT_FOUND -> R.string.appearance_theme_missing
    ThemeIssue.THEME_ID_CONFLICT -> R.string.appearance_replacement_help
    ThemeIssue.REVISION_CONFLICT, ThemeIssue.OPERATION_CONFLICT -> R.string.appearance_theme_changed
    ThemeIssue.IN_USE -> R.string.appearance_theme_in_use
}
