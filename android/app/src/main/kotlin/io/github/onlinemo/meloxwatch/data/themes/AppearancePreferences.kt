package io.github.onlinemo.meloxwatch.data.themes

/** 应用尺度只调整布局间隔；系统 sp 和 48dp 热区不随它缩小。 */
data class AppearanceOptions(
    val scalePercent: Int = 100,
    val horizontalMarginDp: Int = 0,
    val verticalMarginDp: Int = 0,
    val compactPlaybackTitle: Boolean = false,
    val lyricsKeepBright: Boolean = false,
    val language: AppLanguage = AppLanguage.SYSTEM,
) {
    init {
        require(scalePercent in 85..120 && scalePercent % 5 == 0)
        require(horizontalMarginDp in 0..24 && verticalMarginDp in 0..24)
    }

    val scale: Float get() = scalePercent / 100f
}

enum class AppLanguage { SYSTEM, SIMPLIFIED_CHINESE, ENGLISH }

data class AppearanceSettings(val revision: Long = 0L, val options: AppearanceOptions = AppearanceOptions()) {
    init { require(revision >= 0L) }
}

enum class AppearanceIssue {
    INVALID_SETTINGS, NEWER_SCHEMA, SETTINGS_TOO_LARGE, UNRECOVERABLE_SETTINGS,
    STORAGE_UNAVAILABLE, REVISION_EXHAUSTED, CONFLICT, BUSY,
}

/** null 表示读取失败，界面使用的安全默认不能被当作一次成功保存。 */
data class AppearanceSnapshot(
    val settings: AppearanceSettings?,
    val issue: AppearanceIssue? = null,
    val recoveryToken: String? = null,
) {
    val effective: AppearanceOptions get() = settings?.options ?: AppearanceOptions()
    val canWrite: Boolean get() = settings != null && (issue == null || issue == AppearanceIssue.INVALID_SETTINGS)
}

sealed interface AppearanceSaveResult {
    data class Saved(val snapshot: AppearanceSnapshot) : AppearanceSaveResult
    data class Conflict(val snapshot: AppearanceSnapshot) : AppearanceSaveResult
    data class Failed(val snapshot: AppearanceSnapshot, val issue: AppearanceIssue) : AppearanceSaveResult
}
