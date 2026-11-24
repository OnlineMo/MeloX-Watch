package io.github.onlinemo.meloxwatch.ui.fonts

import io.github.onlinemo.meloxwatch.data.fonts.LoadedLyricsFont
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontIssue
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontRole
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSettings
import io.github.onlinemo.meloxwatch.data.themes.ThemeIssue

/** 这是展示来源，不是新的持久字体设置；个人 null 选择始终表示系统字体。 */
enum class LyricsFontPresentationKind { THEME_UI, PERSONAL_LYRICS, EDITOR, PREVIEW }

enum class LyricsFontRequestedSource { SYSTEM, PERSONAL_FILE, THEME_FILE }

/** requestedSource 与 font.usesSystemFont 分开，失败回退不能伪报自选字体已经生效。 */
data class LyricsFontRuntimeRole(
    val font: LoadedLyricsFont = LoadedLyricsFont(),
    val requestedSource: LyricsFontRequestedSource = LyricsFontRequestedSource.SYSTEM,
    val themeIssue: ThemeIssue? = null,
)

/** 只保有当前展示的两种 face；不保有旧主题、轴组合、文件、句柄或额外预览字体缓存。 */
data class LyricsFontRuntimeState(
    val kind: LyricsFontPresentationKind? = null,
    val original: LyricsFontRuntimeRole = LyricsFontRuntimeRole(),
    val translation: LyricsFontRuntimeRole = LyricsFontRuntimeRole(),
    val saved: LyricsFontSettings? = null,
    val loading: Boolean = false,
    val issue: LyricsFontIssue? = null,
) {
    fun role(role: LyricsFontRole): LyricsFontRuntimeRole = when (role) {
        LyricsFontRole.ORIGINAL -> original
        LyricsFontRole.TRANSLATION -> translation
    }

    val hasIssue: Boolean get() = issue != null || original.font.issue != null ||
        translation.font.issue != null || original.themeIssue != null || translation.themeIssue != null

    val isBusy: Boolean get() = issue == LyricsFontIssue.BUSY || original.font.issue == LyricsFontIssue.BUSY ||
        translation.font.issue == LyricsFontIssue.BUSY || original.themeIssue == ThemeIssue.BUSY ||
        translation.themeIssue == ThemeIssue.BUSY
}

/** 仅用于明确主题作用域的预览请求；个人设置仍沿冻结 schema 1 保存。 */
data class LyricsFontRequestedWeights(val original: Float? = null, val translation: Float? = null) {
    fun forRole(role: LyricsFontRole): Float? = when (role) {
        LyricsFontRole.ORIGINAL -> original
        LyricsFontRole.TRANSLATION -> translation
    }
}

internal fun LoadedLyricsFont.withoutTypeface(): LoadedLyricsFont = copy(
    typeface = null,
    configuredAxisWeight = null,
    nativeStyleWeight = null,
)

internal fun LyricsFontEditorState.withoutTypefaces(): LyricsFontEditorState = copy(
    original = original.withoutTypeface(),
    translation = translation.withoutTypeface(),
    presentationActive = false,
)
