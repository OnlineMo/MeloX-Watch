package io.github.onlinemo.meloxwatch.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.onlinemo.meloxwatch.MeloXApplication
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsKind
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayPreferences
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayState
import io.github.onlinemo.meloxwatch.model.ScreenShape
import io.github.onlinemo.meloxwatch.ui.fonts.WatchSystemFontControls
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsDisplayOptions
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsFonts
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsPageContent
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsTextBlock
import io.github.onlinemo.meloxwatch.ui.lyrics.entry.LyricsEntrySurface
import io.github.onlinemo.meloxwatch.ui.lyrics.entry.LyricsEntryUiState
import io.github.onlinemo.meloxwatch.ui.lyrics.entry.LyricsEntryViewModel
import io.github.onlinemo.meloxwatch.ui.lyrics.entry.LyricsPlayPhase
import io.github.onlinemo.meloxwatch.ui.lyrics.entry.LyricsPlayState
import io.github.onlinemo.meloxwatch.ui.lyrics.rememberLyricsPageState
import io.github.onlinemo.meloxwatch.ui.watch.lyricsDisplayOptions
import io.github.onlinemo.meloxwatch.ui.watch.WatchActionRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchLyricsPage
import io.github.onlinemo.meloxwatch.ui.watch.WatchText
import io.github.onlinemo.meloxwatch.ui.themes.watchThemePreviewText
import kotlinx.coroutines.flow.distinctUntilChanged

internal val ProbeLyricsEntryRoutes = setOf(ProbePage.LYRICS, ProbePage.LYRICS_OPTIONS,
    ProbePage.LYRICS_OFFSET, ProbePage.LYRICS_ASSOCIATION)

/** Admission stores both exact closes before this ticket can start subscriptions or media sampling. */
@Composable
internal fun rememberProbeLyricsEntry(
    page: ProbePageRuntime.PageBinding?,
    route: ProbePage,
    entry: LyricsEntryViewModel,
    probe: ProbeViewModel,
): LyricsEntryViewModel.Presentation? {
    var bound by remember(entry, route) { mutableStateOf<LyricsEntryViewModel.Presentation?>(null) }
    DisposableEffect(page, route, entry, probe) {
        val exact = if (page != null && route in ProbeLyricsEntryRoutes) entry.bindPresentation() else null
        val seek = if (exact != null && route == ProbePage.LYRICS) probe.bindLyricsSeekVisibility {
            page?.visibleNow == true && exact.isCurrent
        } else null
        val close = {
            try { seek?.close() }
            finally { exact?.close() }
        }
        val registration = if (exact != null) page?.installUiWork(close) else null
        if (registration?.isCurrent == true && page?.visibleNow == true) {
            bound = exact
            exact?.show(if (route == ProbePage.LYRICS) LyricsEntrySurface.PAGE else LyricsEntrySurface.OPTIONS)
        } else close()
        onDispose {
            if (registration != null) registration.close() else close()
            if (bound === exact) bound = null
        }
    }
    return bound?.takeIf { page?.visibleNow == true && it.isCurrent }
}

internal fun LyricsDisplayState.confirmedPreferences(): LyricsDisplayPreferences? = when (this) {
    is LyricsDisplayState.Ready -> preferences
    is LyricsDisplayState.Unavailable -> lastKnown
    LyricsDisplayState.Loading -> null
}

/** This is the actual third pager page. The Entry owner supplies its sole visible-page clock. */
@Composable
internal fun ProbeLyricsScreen(
    app: MeloXApplication,
    page: ProbePageRuntime.PageBinding?,
    presentation: LyricsEntryViewModel.Presentation?,
    entry: LyricsEntryViewModel,
    state: ProbeUiState,
    shape: ScreenShape,
    presented: Boolean,
    onBack: () -> Unit,
    onOptions: () -> Unit,
    confirmation: @Composable (LyricsPlayState, () -> Unit) -> Unit,
) {
    val visible = page?.visibleNow == true && presentation?.isCurrent == true
    if (!visible) {
        WatchLyricsPage(null, LyricsPageContent.Missing, -1L, null, shape, visible = presented,
            fonts = LyricsFonts(), onBack = onBack, onChooseLyrics = onOptions, onSettings = onOptions)
        return
    }
    val current by entry.uiState.collectAsStateWithLifecycle()
    val clock by entry.mediaClock.collectAsStateWithLifecycle()
    val display by app.lyricsDisplay.state.collectAsStateWithLifecycle()
    val play by entry.playConfirmation.state.collectAsStateWithLifecycle()
    var dismissed by remember(entry) { mutableStateOf<LyricsPlayState?>(null) }
    val showConfirmation = play.phase != LyricsPlayPhase.IDLE || play.issue != null && dismissed != play
    val options = display.confirmedPreferences()?.let(::lyricsDisplayOptions) ?: LyricsDisplayOptions()
    val lyricsState = rememberLyricsPageState(current.identity, current.documentId)
    val ready = current.content as? LyricsPageContent.Ready
    LaunchedEffect(page, presentation, options.emphasizeWords, showConfirmation) {
        if (page?.visibleNow == true && presentation?.isCurrent == true) {
            entry.setWordEmphasis(options.emphasizeWords && !showConfirmation)
        }
    }
    LaunchedEffect(page, presentation, lyricsState, ready?.document?.documentId, showConfirmation) {
        if (showConfirmation || ready?.document?.kind != LyricsKind.SYNCHRONIZED) return@LaunchedEffect
        snapshotFlow {
            // WatchLyricsPage has one context row before its timeline; notes follow all timed rows.
            lyricsState.listState.layoutInfo.visibleItemsInfo.map { it.index - 1 }
                .filter { it in 0 until ready.document.timeline.size }
        }.distinctUntilChanged().collect { lines ->
            if (page?.visibleNow == true && presentation?.isCurrent == true) entry.setVisibleLines(lines)
        }
    }
    if (showConfirmation) {
        WatchSystemFontControls {
            confirmation(play) {
                val id = play.confirmationId
                if (id != null && play.phase != LyricsPlayPhase.IDLE) entry.playConfirmation.cancel(id)
                else dismissed = play
            }
        }
    } else {
        val body: @Composable (LyricsFonts) -> Unit = { fonts ->
            WatchLyricsPage(identity = current.identity, content = current.content,
                mediaPositionMs = clock?.positionMs ?: state.playback.positionMs,
                mediaDurationMs = clock?.durationMs ?: state.playback.durationMs,
                screenShape = shape, visible = page?.visibleNow == true && presentation?.isCurrent == true,
                clockMapping = current.mapping, options = options, fonts = fonts, state = lyricsState,
                canSeek = state.playback.canSeek && !state.playbackCommandPending && !current.loading && !current.saving,
                onPreviewPlayFromLine = { if (page?.visibleNow == true) entry.previewPlay(it) },
                onBack = onBack, onChooseLyrics = onOptions, onSettings = onOptions,
                onRetry = { if (page?.visibleNow == true) entry.retry() })
        }
        if (ready != null && page != null) ProbePersonalLyricsFonts(app, page, ready) { body(it.fonts) }
        else body(LyricsFonts())
    }
}

/** Preview is a bounded static excerpt; it has no second media clock or playback action. */
@Composable
internal fun ProbeLyricsExcerpt(state: LyricsEntryUiState, options: LyricsDisplayOptions = LyricsDisplayOptions()) {
    val ready = state.content as? LyricsPageContent.Ready ?: return
    val document = ready.document
    val text = if (document.timeline.size > 0) document.timeline.line(0).text
        else document.sourceLines.firstOrNull()?.text ?: return
    val translation = if (document.timeline.size > 0) ready.translation?.line(0, document.documentId,
        options.showTranslation)?.text else null
    LyricsTextBlock(text, translation, active = true, wordRange = null, options = options, fonts = LyricsFonts(),
        currentLabel = stringResource(R.string.lyrics_current_line), actionLabel = null, onPreview = null,
        visible = true, modifier = Modifier.fillMaxWidth())
}

@Composable
internal fun ProbeLyricsDisplayPreview(options: LyricsDisplayOptions) {
    val text = watchThemePreviewText()
    LyricsTextBlock(text.original, text.translation.takeIf { options.showTranslation }, active = true,
        wordRange = null, options = options, fonts = LyricsFonts(),
        currentLabel = stringResource(R.string.lyrics_current_line), actionLabel = null, onPreview = null,
        visible = true, modifier = Modifier.fillMaxWidth())
}

@Composable
internal fun ProbeLyricsPlayControls(state: LyricsPlayState, entry: LyricsEntryViewModel, onCancel: () -> Unit) {
    val target = state.preview?.target?.mediaPositionMs
    val label = target?.let { stringResource(R.string.lyrics_play_at, elapsedTime(it)) }
        ?: stringResource(R.string.lyrics_line_play_unavailable)
    WatchText(label, primary = true)
    WatchText(stringResource(R.string.lyrics_play_warning))
    if (state.issue != null) WatchText(stringResource(R.string.lyrics_entry_changed), error = true)
    val id = state.confirmationId
    if (state.phase == LyricsPlayPhase.CONFIRMATION && id != null) {
        WatchActionRow(label, primary = true,
            onClick = { entry.playConfirmation.confirm(id) })
    } else if (state.phase != LyricsPlayPhase.IDLE) WatchText(stringResource(R.string.lyrics_settings_working))
    WatchActionRow(stringResource(R.string.lyrics_settings_cancel), onClick = onCancel)
}
