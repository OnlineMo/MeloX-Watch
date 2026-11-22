package io.github.onlinemo.meloxwatch.ui.backup

import io.github.onlinemo.meloxwatch.data.backup.BackupExportReceipt
import io.github.onlinemo.meloxwatch.data.backup.BackupIssue
import io.github.onlinemo.meloxwatch.data.backup.BackupMergeMode
import io.github.onlinemo.meloxwatch.data.backup.BackupMissingFile
import io.github.onlinemo.meloxwatch.data.backup.BackupProgress
import io.github.onlinemo.meloxwatch.data.backup.BackupRelinkPreview
import io.github.onlinemo.meloxwatch.data.backup.BackupRelinkReceipt
import io.github.onlinemo.meloxwatch.data.backup.BackupRestorePreview
import io.github.onlinemo.meloxwatch.data.backup.BackupRestoreReceipt
import io.github.onlinemo.meloxwatch.data.backup.BackupScope
import io.github.onlinemo.meloxwatch.data.backup.BackupStartupState

enum class BackupUiPage { HOME, EXPORT, RESTORE, MISSING_FILES, RELINK }

/** 只投影当前一步；文档 URI 不进 UI state，列表只保当前有界页。 */
data class BackupUiState(
    val page: BackupUiPage = BackupUiPage.HOME,
    val startup: BackupStartupState = BackupStartupState.Loading,
    val progress: BackupProgress? = null,
    val scopes: Set<BackupScope> = BackupScope.entries.filterTo(linkedSetOf()) { it != BackupScope.RESOURCES },
    val mode: BackupMergeMode = BackupMergeMode.MERGE_KEEP_CURRENT,
    val privacyAcknowledged: Boolean = false,
    val effectsAcknowledged: Boolean = false,
    val fontLicenseAcknowledged: Boolean = false,
    val working: Boolean = false,
    val cancellable: Boolean = false,
    val cancelling: Boolean = false,
    val cancelled: Boolean = false,
    val issue: BackupIssue? = null,
    val pickerPending: Boolean = false,
    val targetPickerPending: Boolean = false,
    val pickerUnavailable: Boolean = false,
    val hasRestoreInput: Boolean = false,
    val restorePreview: BackupRestorePreview? = null,
    val exportReceipt: BackupExportReceipt? = null,
    val restoreReceipt: BackupRestoreReceipt? = null,
    val missingFiles: List<BackupMissingFile> = emptyList(),
    val missingPageLoaded: Boolean = false,
    val missingPageAfter: String? = null,
    val nextMissingPage: String? = null,
    val selectedMissing: BackupMissingFile? = null,
    val relinkPreview: BackupRelinkPreview? = null,
    val relinkReceipt: BackupRelinkReceipt? = null,
    val allowDifferentContent: Boolean = false,
) {
    val ready: Boolean get() = startup is BackupStartupState.Ready
    val canEdit: Boolean get() = ready && !working && !pickerPending && !targetPickerPending
    val canChooseExport: Boolean get() = canEdit && scopes.isNotEmpty() && privacyAcknowledged
    val previewMatchesSelection: Boolean get() = restorePreview?.let { it.scopes == scopes && it.mode == mode } == true
    val canRestore: Boolean get() = canEdit && previewMatchesSelection && effectsAcknowledged &&
        (restorePreview?.needsFontLicenseAcknowledgment != true || fontLicenseAcknowledged)
    override fun toString(): String = "BackupUiState($page,working=$working)"
}

/** 既有曲库选择器须回交同一票据；它没有文件 URI 或可恢复的播放意图。 */
class BackupRelinkTargetRequest internal constructor(
    internal val missingTrackId: String,
    internal val hintId: String,
    internal val draftRevision: Long,
) {
    internal var retired = false
    override fun toString(): String = "BackupRelinkTargetRequest"
}
