package io.github.onlinemo.meloxwatch.ui.about

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.data.about.DiagnosticCode
import io.github.onlinemo.meloxwatch.data.about.ProjectChannels
import io.github.onlinemo.meloxwatch.data.about.ProjectUpdateIssue
import io.github.onlinemo.meloxwatch.data.about.ProjectUpdateState
import io.github.onlinemo.meloxwatch.model.PrivacyNoticeChoice
import io.github.onlinemo.meloxwatch.model.HelpPreferenceLimits
import io.github.onlinemo.meloxwatch.ui.themes.LocalWatchThemeColors
import io.github.onlinemo.meloxwatch.ui.watch.WatchActionRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchContentRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchSwitchRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal fun LazyListScope.watchHelpContent(
    state: AboutUiState,
    model: AboutViewModel,
    onBack: () -> Unit,
    onLibrary: () -> Unit,
    onExternal: (String) -> Unit,
    onSettings: () -> Unit,
) {
    item("help:back") {
        WatchActionRow(stringResource(R.string.help_back), onClick = {
            when (state.page) {
                HelpPage.OVERVIEW -> onBack()
                HelpPage.LICENSE_TEXT -> model.show(HelpPage.LICENSES)
                else -> model.show(HelpPage.OVERVIEW)
            }
        })
    }
    item("help:version") { WatchText(stringResource(R.string.help_version, state.version.name, state.version.code)) }
    if (state.working || state.reading) item("help:working") { WatchText(stringResource(R.string.help_working)) }
    val ready = state.storage as? io.github.onlinemo.meloxwatch.data.preferences.PreferencesState.Ready
    if (ready != null && (ready.recovery != null || !ready.canWrite)) {
        item("help:settings-recovery") {
            WatchText(stringResource(if (ready.canWrite) R.string.help_settings_recovery else R.string.help_settings_readonly), error = true)
            WatchActionRow(stringResource(R.string.help_open_settings), onClick = onSettings)
        }
    }
    state.feedback?.let { feedback ->
        item("help:feedback") { WatchText(stringResource(feedbackLabel(feedback)), error = feedback !in
            setOf(HelpFeedback.SAVED, HelpFeedback.EXPORTED, HelpFeedback.CANCELLED)) }
    }
    when (state.page) {
        HelpPage.OVERVIEW -> {
            item("help:core") { WatchActionRow(stringResource(R.string.help_core), onClick = { model.show(HelpPage.CORE_HELP) }) }
            item("help:guide") { WatchActionRow(stringResource(R.string.help_guide), onClick = { model.beginGuide(restart = true) }) }
            item("help:privacy") { WatchActionRow(stringResource(R.string.help_privacy), onClick = { model.show(HelpPage.PRIVACY) }) }
            item("help:licenses") { WatchActionRow(stringResource(R.string.help_licenses), onClick = { model.show(HelpPage.LICENSES) }) }
            item("help:update") { WatchActionRow(stringResource(R.string.help_updates), onClick = { model.show(HelpPage.UPDATE) }) }
            item("help:diagnostics") { WatchActionRow(stringResource(R.string.help_diagnostics), onClick = { model.show(HelpPage.DIAGNOSTICS) }) }
            item("help:support") { WatchActionRow(stringResource(R.string.help_support), onClick = { model.show(HelpPage.SUPPORT) }) }
            item("help:tips") {
                WatchSwitchRow(stringResource(R.string.help_tips), state.help?.showTips ?: true,
                    state.canWriteSettings, model::setTips)
            }
        }
        HelpPage.CORE_HELP -> {
            text("help:core-local", R.string.help_local_title, R.string.help_local_body)
            text("help:core-permission", R.string.help_permissions_title, R.string.help_permissions_body)
            text("help:core-background", R.string.help_background_title, R.string.help_background_body)
            text("help:core-formats", R.string.help_formats_title, R.string.help_formats_body)
            text("help:core-files", R.string.help_files_title, R.string.help_files_body)
            text("help:core-fonts", R.string.help_fonts_title, R.string.help_fonts_body)
            item("help:open-library") { WatchActionRow(stringResource(R.string.help_open_library), primary = true, onClick = onLibrary) }
        }
        HelpPage.PRIVACY -> {
            text("help:privacy-local", R.string.help_privacy_local_title, R.string.help_privacy_local_body)
            text("help:privacy-online", R.string.help_privacy_online_title, R.string.help_privacy_online_body)
            text("help:privacy-transfer", R.string.help_privacy_transfer_title, R.string.help_privacy_transfer_body)
            text("help:privacy-backup", R.string.help_privacy_backup_title, R.string.help_privacy_backup_body)
            item("help:privacy-choice") { WatchText(stringResource(R.string.help_privacy_choice)) }
            val currentChoice = state.help?.takeIf { it.privacyNoticeVersion == HelpPreferenceLimits.PRIVACY_NOTICE_VERSION }
            if (state.help?.privacyNoticeVersion?.let { it > 0 && it != HelpPreferenceLimits.PRIVACY_NOTICE_VERSION } == true) {
                item("help:privacy-changed") { WatchText(stringResource(R.string.help_notice_changed)) }
            }
            item("help:privacy-read") {
                WatchActionRow(stringResource(R.string.help_privacy_read), state.canWriteSettings,
                    selected = currentChoice?.privacyNoticeChoice == PrivacyNoticeChoice.READ,
                    onClick = { model.recordNotice(PrivacyNoticeChoice.READ) })
            }
            item("help:privacy-later") {
                WatchActionRow(stringResource(R.string.help_privacy_later), state.canWriteSettings,
                    selected = currentChoice?.privacyNoticeChoice == PrivacyNoticeChoice.LATER,
                    onClick = { model.recordNotice(PrivacyNoticeChoice.LATER) })
            }
        }
        HelpPage.LICENSES -> {
            item("help:license-info") { WatchText(stringResource(R.string.help_license_info)) }
            state.licenses.forEach { entry ->
                item("help:license:${entry.id}") {
                    WatchContentRow(onClick = { model.showLicense(entry.id) }) {
                        BasicText(entry.name, style = TextStyle(color = LocalWatchThemeColors.current.text, fontSize = 16.sp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        BasicText("${entry.version} · ${entry.license}",
                            style = TextStyle(color = LocalWatchThemeColors.current.secondary, fontSize = 14.sp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (state.feedback == HelpFeedback.LICENSE_UNAVAILABLE) item("help:license-retry") {
                WatchActionRow(stringResource(R.string.help_retry), !state.reading, onClick = model::retryPage)
            }
        }
        HelpPage.LICENSE_TEXT -> {
            state.licenses.firstOrNull { it.id == state.licenseId }?.let { entry ->
                item("help:license-name") { WatchText(entry.name, primary = true) }
                item("help:license-version") { WatchText("${entry.version} · ${entry.license}") }
            }
            state.licenseLines.forEachIndexed { index, line ->
                item("help:license-line:$index") {
                    // 许可原文用系统字体完整呈现，避免导入字体缺字改变法律说明。
                    BasicText(line.ifEmpty { " " }, style = TextStyle(color = LocalWatchThemeColors.current.secondary,
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp))
                }
            }
            if (state.feedback == HelpFeedback.LICENSE_UNAVAILABLE) item("help:license-retry") {
                WatchActionRow(stringResource(R.string.help_retry), !state.reading, onClick = model::retryPage)
            }
        }
        HelpPage.UPDATE -> updates(state, model, onExternal)
        HelpPage.GUIDE -> guide(state, model, onLibrary)
        HelpPage.DIAGNOSTICS -> {
            item("help:diag-info") { WatchText(stringResource(R.string.help_diagnostics_info)) }
            if (state.waitingForPicker) item("help:diag-picker-wait") { WatchText(stringResource(R.string.help_picker_waiting)) }
            if (state.diagnostics.isEmpty() && !state.reading) item("help:diag-empty") {
                WatchText(stringResource(R.string.help_diagnostics_empty))
            }
            state.diagnostics.asReversed().forEachIndexed { index, event ->
                item("help:diag:$index") {
                    WatchText("${event.area.name} · ${event.code.name}", primary = true)
                    WatchText(stringResource(diagnosticLabel(event.code)))
                    WatchText(timestamp(event.epochMinute * 60_000))
                }
            }
            item("help:diag-export") {
                WatchActionRow(stringResource(R.string.help_diagnostics_export), state.canExport && !state.reading,
                    onClick = model::requestExport)
            }
            item("help:diag-clear") {
                WatchActionRow(stringResource(R.string.help_diagnostics_clear), state.canExport && !state.reading,
                    onClick = model::clearDiagnostics)
            }
            item("help:diag-cleanup") {
                WatchActionRow(stringResource(R.string.help_diagnostics_cleanup), state.canExport, onClick = model::recoverExport)
            }
            if (state.feedback == HelpFeedback.IO) item("help:diag-retry") {
                WatchActionRow(stringResource(R.string.help_retry), !state.reading, onClick = model::retryPage)
            }
        }
        HelpPage.SUPPORT -> {
            item("help:support-info") { WatchText(stringResource(R.string.help_support_info)) }
            item("help:source") {
                WatchActionRow(stringResource(R.string.help_project_page), onClick = { onExternal(ProjectChannels.SOURCE_PAGE) })
            }
            item("help:credits") { WatchText(stringResource(R.string.help_credits)) }
            item("help:support-offline") {
                WatchActionRow(stringResource(R.string.help_core), onClick = { model.show(HelpPage.CORE_HELP) })
            }
        }
    }
    if (state.storage is io.github.onlinemo.meloxwatch.data.preferences.PreferencesState.Unavailable) {
        item("help:settings-retry") {
            WatchActionRow(stringResource(R.string.help_retry), !state.working, onClick = model::retrySettings)
        }
    }
    if (state.page != HelpPage.OVERVIEW) item("help:footer-back") {
        WatchActionRow(stringResource(R.string.help_back), onClick = { model.show(HelpPage.OVERVIEW) })
    }
}

private fun LazyListScope.text(key: String, title: Int, body: Int) {
    item("$key:title") { WatchText(stringResource(title), primary = true) }
    item("$key:body") { WatchText(stringResource(body)) }
}

private fun LazyListScope.updates(state: AboutUiState, model: AboutViewModel, onExternal: (String) -> Unit) {
    item("help:update-info") { WatchText(stringResource(R.string.help_update_info)) }
    when (val status = state.updates) {
        ProjectUpdateState.Checking -> {
            item("help:update-checking") { WatchText(stringResource(R.string.help_update_checking)) }
            item("help:update-cancel") { WatchActionRow(stringResource(R.string.help_cancel), onClick = model::cancelUpdate) }
        }
        is ProjectUpdateState.Checked -> {
            item("help:update-result") {
                WatchText(stringResource(if (status.hasUpdate) R.string.help_update_available else R.string.help_update_current,
                    status.release.version.name), primary = true)
                WatchText(timestamp(status.checkedAtEpochMs))
                if (status.release.prerelease) WatchText(stringResource(R.string.help_update_prerelease))
            }
            if (status.release.notes.isNotBlank()) item("help:update-notes") { WatchText(status.release.notes) }
            item("help:update-release") {
                WatchActionRow(stringResource(R.string.help_update_release_page), onClick = { onExternal(status.release.releasePage) })
            }
        }
        is ProjectUpdateState.Failed -> item("help:update-failure") {
            WatchText(stringResource(updateIssueLabel(status.issue)), error = true)
            WatchText(timestamp(status.checkedAtEpochMs))
        }
        ProjectUpdateState.Cancelled -> item("help:update-cancelled") { WatchText(stringResource(R.string.help_cancelled)) }
        ProjectUpdateState.Idle -> Unit
    }
    if (state.updates != ProjectUpdateState.Checking) item("help:update-check") {
        WatchActionRow(stringResource(R.string.help_update_check), primary = true, onClick = model::checkUpdates)
    }
}

private fun LazyListScope.guide(state: AboutUiState, model: AboutViewModel, onLibrary: () -> Unit) {
    val step = state.guideStep
    item("help:guide-position") { WatchText(stringResource(R.string.help_guide_position, step.ordinal + 1, GuideStep.entries.size)) }
    val (title, body) = when (step) {
        GuideStep.LOCAL_MUSIC -> R.string.help_local_title to R.string.help_local_body
        GuideStep.PERMISSIONS -> R.string.help_permissions_title to R.string.help_permissions_body
        GuideStep.PLAYBACK -> R.string.help_background_title to R.string.help_background_body
        GuideStep.PRIVACY -> R.string.help_privacy_local_title to R.string.help_privacy_local_body
    }
    text("help:guide-body", title, body)
    if (step == GuideStep.LOCAL_MUSIC) item("help:guide-library") {
        WatchActionRow(stringResource(R.string.help_open_library), onClick = onLibrary)
    }
    if (step.ordinal > 0) item("help:guide-previous") {
        WatchActionRow(stringResource(R.string.help_previous), !state.working,
            onClick = { model.guideStep(GuideStep.entries[step.ordinal - 1]) })
    }
    item("help:guide-next") {
        if (step == GuideStep.entries.last()) {
            WatchActionRow(stringResource(R.string.help_guide_done), state.canWriteSettings, primary = true, onClick = model::finishGuide)
        } else {
            WatchActionRow(stringResource(R.string.help_continue), !state.working, primary = true,
                onClick = { model.guideStep(GuideStep.entries[step.ordinal + 1]) })
        }
    }
    item("help:guide-later") { WatchActionRow(stringResource(R.string.help_later), state.canWriteSettings, onClick = model::dismissGuide) }
}

private fun timestamp(epochMs: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT)
    .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(epochMs))

private fun feedbackLabel(value: HelpFeedback): Int = when (value) {
    HelpFeedback.SAVED -> R.string.help_saved
    HelpFeedback.CONFLICT -> R.string.help_conflict
    HelpFeedback.SETTINGS_UNAVAILABLE -> R.string.help_settings_unavailable
    HelpFeedback.IO -> R.string.help_io
    HelpFeedback.CANCELLED -> R.string.help_cancelled
    HelpFeedback.EXPORTED -> R.string.help_exported
    HelpFeedback.LOCAL_FOLDER_REQUIRED -> R.string.help_local_folder_required
    HelpFeedback.CLEANUP_REQUIRED -> R.string.help_cleanup_required
    HelpFeedback.NO_FOLDER_PICKER -> R.string.help_no_folder_picker
    HelpFeedback.EXTERNAL_UNAVAILABLE -> R.string.help_external_unavailable
    HelpFeedback.LICENSE_UNAVAILABLE -> R.string.help_license_unavailable
}

private fun updateIssueLabel(value: ProjectUpdateIssue): Int = when (value) {
    ProjectUpdateIssue.NO_RELEASE -> R.string.help_update_no_release
    ProjectUpdateIssue.TIMEOUT -> R.string.help_update_timeout
    ProjectUpdateIssue.BUSY -> R.string.help_update_busy
    ProjectUpdateIssue.INVALID_RELEASE, ProjectUpdateIssue.UNTRUSTED_SOURCE,
    ProjectUpdateIssue.CERTIFICATE_MISMATCH, ProjectUpdateIssue.DOWNGRADE -> R.string.help_update_untrusted
    ProjectUpdateIssue.TOO_LARGE -> R.string.help_update_too_large
    ProjectUpdateIssue.UNAVAILABLE -> R.string.help_update_unavailable
}

private fun diagnosticLabel(value: DiagnosticCode): Int = when (value) {
    DiagnosticCode.PERMISSION -> R.string.help_diag_permission
    DiagnosticCode.STORAGE -> R.string.help_diag_storage
    DiagnosticCode.BUSY -> R.string.help_diag_busy
    DiagnosticCode.SOURCE_CHANGED -> R.string.help_diag_source_changed
    DiagnosticCode.SOURCE_MISSING -> R.string.help_diag_source_missing
    DiagnosticCode.SOURCE_DAMAGED -> R.string.help_diag_source_damaged
    DiagnosticCode.UNSUPPORTED -> R.string.help_diag_unsupported
    DiagnosticCode.TIMEOUT -> R.string.help_diag_timeout
    DiagnosticCode.CONNECTION -> R.string.help_diag_connection
    DiagnosticCode.INVALID_DATA -> R.string.help_diag_invalid
    DiagnosticCode.RECOVERY_REQUIRED -> R.string.help_diag_recovery
    DiagnosticCode.INTERNAL -> R.string.help_diag_internal
}
