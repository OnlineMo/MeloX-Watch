package io.github.onlinemo.meloxwatch.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.onlinemo.meloxwatch.MeloXApplication
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.data.themes.ThemeSnapshot
import io.github.onlinemo.meloxwatch.ui.fonts.LyricsFontPresentationKind
import io.github.onlinemo.meloxwatch.ui.fonts.ThemeUiFontText
import io.github.onlinemo.meloxwatch.ui.fonts.WatchFontHost
import io.github.onlinemo.meloxwatch.ui.fonts.WatchFontHostValue
import io.github.onlinemo.meloxwatch.ui.fonts.WatchFontRequest
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsPageContent
import io.github.onlinemo.meloxwatch.ui.themes.AppearanceEditorState
import io.github.onlinemo.meloxwatch.ui.themes.AppearanceViewModel
import io.github.onlinemo.meloxwatch.ui.themes.LocalWatchThemeFonts
import io.github.onlinemo.meloxwatch.ui.themes.WatchThemePreview
import io.github.onlinemo.meloxwatch.ui.themes.WatchThemeScope
import io.github.onlinemo.meloxwatch.ui.themes.watchThemePreviewText
import io.github.onlinemo.meloxwatch.ui.watch.WatchText

/** 页面先注册两阶段释放，再借用 Application 的唯一字体所有者。 */
@Composable
internal fun ProbePageFontHost(
    app: MeloXApplication,
    page: ProbePageRuntime.PageBinding,
    request: WatchFontRequest,
    retry: Any = Unit,
    content: @Composable (WatchFontHostValue) -> Unit,
) {
    val visibleNow = remember(page) { { page.visibleNow } }
    val install = remember(page) {
        { kind: LyricsFontPresentationKind, memory: () -> Unit, cancel: () -> Unit ->
            val slot = when (kind) {
                LyricsFontPresentationKind.THEME_UI -> ProbeFontSlot.THEME_UI
                LyricsFontPresentationKind.PERSONAL_LYRICS -> ProbeFontSlot.PERSONAL_LYRICS
                LyricsFontPresentationKind.EDITOR -> ProbeFontSlot.EDITOR
                LyricsFontPresentationKind.PREVIEW -> ProbeFontSlot.THEME_PREVIEW
            }
            page.installFont(slot, memory, cancel)
        }
    }
    WatchFontHost(app.lyricsFontRuntime, app.lyricsFontPresentationOwner, app.lyricsFontEditor,
        request, page.epoch, visibleNow, install, retry, content)
}

/** 只捕获实际显示的动态文字；曲库大小、媒体位置和滚动 tick 不进入字体覆盖身份。 */
@Composable
internal fun ProbeThemeUiFonts(
    app: MeloXApplication,
    page: ProbePageRuntime.PageBinding?,
    snapshot: ThemeSnapshot,
    visibleText: List<String>,
    content: @Composable () -> Unit,
) {
    if (page == null || !page.visibleNow) {
        content()
        return
    }
    val resources = LocalContext.current.resources
    val localeKey = LocalConfiguration.current.locales.toLanguageTags()
    val captured = remember(visibleText, localeKey) { ThemeUiFontText.capture(visibleText) }
    val request = remember(snapshot, resources, localeKey, captured) {
        WatchFontRequest.ThemeUi(snapshot, resources, localeKey, captured)
    }
    ProbePageFontHost(app, page, request) { host ->
        CompositionLocalProvider(LocalWatchThemeFonts provides host.fonts) { ProbeTheme(content) }
    }
}

/** 个性歌词字体按完整原文和精确匹配译文覆盖，始终显式传给实际歌词文本。 */
@Composable
internal fun ProbePersonalLyricsFonts(
    app: MeloXApplication,
    page: ProbePageRuntime.PageBinding,
    ready: LyricsPageContent.Ready,
    content: @Composable (WatchFontHostValue) -> Unit,
) {
    val request = remember(ready.document.documentId, ready.translation) { WatchFontRequest.Personal(ready) }
    ProbePageFontHost(app, page, request, content = content)
}

/** EDITOR 与 PREVIEW 独占页面字体槽，外围设置控件继续使用系统字体。 */
@Composable
internal fun ProbeLyricsFontEditorHost(
    app: MeloXApplication,
    page: ProbePageRuntime.PageBinding,
    retry: Any,
    content: @Composable (WatchFontHostValue) -> Unit,
) {
    val produced = watchThemePreviewText()
    val sample = remember(produced.original, produced.translation) { produced }
    val request = remember(sample) { WatchFontRequest.Editor(sample) }
    ProbePageFontHost(app, page, request, retry, content)
}

@Composable
internal fun ProbeCandidateThemeFontPreview(
    app: MeloXApplication,
    page: ProbePageRuntime.PageBinding,
    state: AppearanceEditorState,
    model: AppearanceViewModel,
) {
    val preview = state.themePreview
    if (preview == null) {
        WatchText(stringResource(R.string.appearance_preview_choose_theme))
        return
    }
    WatchThemeScope(preview.manifest.palette, state.draftAppearance) {
        val produced = watchThemePreviewText()
        val sample = remember(produced.original, produced.translation) { produced }
        val request = remember(preview, sample) { WatchFontRequest.Preview(preview, sample) }
        ProbePageFontHost(app, page, request) { host ->
            val exactSample = checkNotNull(host.previewText)
            WatchThemePreview(state, model, app.themes, app.localProviderIo, app.themeBitmaps,
                host.fonts, exactSample, visible = page.visibleNow,
                windowVisibleNow = { page.visibleNow },
                onRetireFonts = host.releaseMemory,
                onCancelRetiredFonts = host.cancelRetiredWork)
        }
    }
}
