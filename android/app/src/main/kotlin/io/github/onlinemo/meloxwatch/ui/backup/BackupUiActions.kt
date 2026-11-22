package io.github.onlinemo.meloxwatch.ui.backup

import io.github.onlinemo.meloxwatch.data.backup.BackupMergeMode
import io.github.onlinemo.meloxwatch.data.backup.BackupMissingFile
import io.github.onlinemo.meloxwatch.data.backup.BackupScope

/** 导航/系统选择器由 Activity 提供；其余动作交回同一备份控制器，没有空默认回调。 */
class BackupUiActions(
    val home: () -> Unit,
    val beginExport: () -> Unit,
    val beginRestore: () -> Unit,
    val scope: (BackupScope, Boolean) -> Unit,
    val mode: (BackupMergeMode) -> Unit,
    val privacy: (Boolean) -> Unit,
    val effects: (Boolean) -> Unit,
    val fontLicense: (Boolean) -> Unit,
    val chooseDestination: () -> Unit,
    val chooseInput: () -> Unit,
    val recheckRestore: () -> Unit,
    val confirmRestore: () -> Unit,
    val cancel: () -> Unit,
    val retryOutputCleanup: () -> Unit,
    val showMissing: () -> Unit,
    val firstMissingPage: () -> Unit,
    val nextMissingPage: () -> Unit,
    val selectMissing: (BackupMissingFile) -> Unit,
    val chooseExistingTarget: () -> Unit,
    val allowDifferent: (Boolean) -> Unit,
    val confirmRelink: () -> Unit,
    val openSources: () -> Unit,
    val openAppearance: () -> Unit,
    val retryStorage: () -> Unit,
) {
    companion object {
        fun bind(
            model: BackupViewModel,
            chooseDestination: () -> Unit,
            chooseInput: () -> Unit,
            chooseExistingTarget: (BackupRelinkTargetRequest) -> Unit,
            openSources: () -> Unit,
            openAppearance: () -> Unit,
            retryStorage: () -> Unit,
        ): BackupUiActions = BackupUiActions(
            home = model::home,
            beginExport = model::beginExport,
            beginRestore = model::beginRestore,
            scope = model::setScope,
            mode = model::setMode,
            privacy = model::acknowledgePrivacy,
            effects = model::acknowledgeEffects,
            fontLicense = model::acknowledgeFontLicense,
            chooseDestination = {
                if (model.beginDestinationPicker()) chooseDestination()
            },
            chooseInput = {
                if (model.beginInputPicker()) chooseInput()
            },
            recheckRestore = model::recheckRestore,
            confirmRestore = model::confirmRestore,
            cancel = model::cancel,
            retryOutputCleanup = model::retryOutputCleanup,
            showMissing = model::showMissingFiles,
            firstMissingPage = model::firstMissingPage,
            nextMissingPage = model::nextMissingPage,
            selectMissing = model::selectMissing,
            chooseExistingTarget = {
                val request = model.beginRelinkTargetRequest()
                if (request != null) chooseExistingTarget(request)
            },
            allowDifferent = model::acknowledgeDifferentContent,
            confirmRelink = model::confirmRelink,
            openSources = openSources,
            openAppearance = openAppearance,
            retryStorage = retryStorage,
        )
    }
}
