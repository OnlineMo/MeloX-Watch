package io.github.onlinemo.meloxwatch.ui.watch

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.rotary.RotaryScrollEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import io.github.onlinemo.meloxwatch.model.ScreenShape
import io.github.onlinemo.meloxwatch.ui.ProbePage
import io.github.onlinemo.meloxwatch.ui.ProbePageRuntime
import io.github.onlinemo.meloxwatch.ui.ProbePageSelectionSnapshot
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsDisplayOptions
import io.github.onlinemo.meloxwatch.ui.lyrics.lyricsViewportGeometry
import io.github.onlinemo.meloxwatch.ui.themes.LocalWatchAppearance
import io.github.onlinemo.meloxwatch.ui.themes.LocalWatchThemeColors
import io.github.onlinemo.meloxwatch.ui.themes.LocalWatchThemeFonts
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive

private data class WatchPagerPosition(val settled: Int, val current: Int, val scrolling: Boolean)

/** Only a presented, settled route owns visual work; the operation lock only disables swiping. */
@Composable
internal fun WatchPlaybackPages(
    page: ProbePage,
    enabled: Boolean,
    onPage: (ProbePage) -> Unit,
    presented: Boolean = true,
    presentationOwner: Any? = null,
    bindPresentation: ((() -> ProbePageSelectionSnapshot) -> ProbePageRuntime.SelectionBinding?)? = null,
    onPresentationBound: (ProbePageRuntime.SelectionBinding) -> Unit = {},
    onPresentationRetired: (ProbePageRuntime.SelectionBinding) -> Unit = {},
    content: @Composable (ProbePage, Boolean) -> Unit,
) {
    val pages = remember { listOf(ProbePage.QUEUE, ProbePage.PLAYBACK, ProbePage.LYRICS) }
    val currentOnPage by rememberUpdatedState(onPage)
    val currentRoute by rememberUpdatedState(page)
    val currentPresented by rememberUpdatedState(presented)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentBindPresentation by rememberUpdatedState(bindPresentation)
    val currentOnBound by rememberUpdatedState(onPresentationBound)
    val currentOnRetired by rememberUpdatedState(onPresentationRetired)
    val pager = rememberPagerState(initialPage = pages.indexOf(page).coerceAtLeast(0), pageCount = { pages.size })
    var synchronizedRoute by remember { mutableStateOf<ProbePage?>(null) }
    var selectionBinding by remember(pager) { mutableStateOf<ProbePageRuntime.SelectionBinding?>(null) }
    if (!presented || page !in pages) {
        // Retain pager position, but remove observers, adjacent layouts and accessibility nodes.
        Box(Modifier.fillMaxSize().clearAndSetSemantics { })
        return
    }
    DisposableEffect(pager, presentationOwner) {
        val exact = currentBindPresentation?.invoke {
            val currentIndex = pager.currentPage
            ProbePageSelectionSnapshot(
                route = pages.getOrNull(currentIndex) ?: ProbePage.HOME,
                current = currentIndex in pages.indices && currentPresented,
                settled = pager.settledPage == currentIndex,
                scrolling = pager.isScrollInProgress,
            )
        }
        val retiredCallback = currentOnRetired
        selectionBinding = exact
        exact?.let(currentOnBound)
        // Resuming the same route must first synchronize the retained pager position.
        // A previous hidden window's settlement cannot publish another navigation.
        onDispose {
            exact?.close()
            if (selectionBinding === exact) selectionBinding = null
            exact?.let(retiredCallback)
            synchronizedRoute = null
        }
    }
    val motion = remember(pager, selectionBinding) {
        WatchPagerMotionRetirement(selectionBinding) { currentPresented && currentEnabled }
    }
    LaunchedEffect(page, pager, selectionBinding) {
        val exactSelection = selectionBinding
        val target = pages.indexOf(page)
        if (target < 0) return@LaunchedEffect
        try {
            if (pager.settledPage != target || pager.currentPage != target || pager.isScrollInProgress) {
                // This exact pager request retires memory before stopping/jumping the old motion.
                exactSelection?.invalidate()
                // External navigation retires a gesture that started under the old route.
                // A later gesture may still interrupt the following programmatic scroll.
                pager.stopScroll(MutatePriority.PreventUserInput)
                pager.scrollToPage(target)
            }
        } finally {
            // A real user gesture can cancel a programmatic scroll. Its later settlement remains
            // navigable; a route/window replacement cannot acknowledge this abandoned request.
            if (currentCoroutineContext().isActive && currentPresented && currentRoute == page &&
                selectionBinding === exactSelection) synchronizedRoute = page
        }
    }
    LaunchedEffect(pager, selectionBinding) {
        val exactSelection = selectionBinding
        snapshotFlow { synchronizedRoute to WatchPagerPosition(pager.settledPage, pager.currentPage, pager.isScrollInProgress) }
            .distinctUntilChanged().collect { (acknowledgedRoute, position) ->
                // Read actual state again; a queued observation cannot restore an old epoch.
                exactSelection?.refresh()
                motion.recheckIdle(pager.isScrollInProgress)
                // A queued observation may predate external navigation. Recheck current intent
                // and actual pager state before publishing a route, rather than undo that request.
                if (!currentPresented || currentRoute !in pages || acknowledgedRoute != synchronizedRoute ||
                    synchronizedRoute != currentRoute ||
                    position.scrolling || position.current != position.settled ||
                    pager.isScrollInProgress || pager.currentPage != position.current || pager.settledPage != position.settled) {
                    return@collect
                }
                val settled = pages.getOrNull(position.settled) ?: return@collect
                if (settled != currentRoute) currentOnPage(settled)
            }
    }
    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize().nestedScroll(motion), userScrollEnabled = enabled,
        beyondViewportPageCount = 0) { index ->
        val current = pages[index]
        val active = presented && current == page && pager.settledPage == index && pager.currentPage == index &&
            !pager.isScrollInProgress
        content(current, active)
    }
}

/** The first horizontal input retires before pager offset consumption; it consumes no gesture. */
private class WatchPagerMotionRetirement(
    private val exact: ProbePageRuntime.SelectionBinding?,
    private val canSwipe: () -> Boolean,
) : NestedScrollConnection {
    private var retiringMotion = false

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && available.x.isFinite() && available.x != 0f) beginMotion()
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (available.x.isFinite() && available.x != 0f) beginMotion()
        return Velocity.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        retiringMotion = false
        exact?.refresh()
        return Velocity.Zero
    }

    fun recheckIdle(actualScrolling: Boolean) {
        if (!actualScrolling) retiringMotion = false
    }

    private fun beginMotion() {
        if (retiringMotion || !canSwipe()) return
        retiringMotion = true
        exact?.invalidate()
    }
}

/**
 * Compact geometry reserves measured glyphs and independent targets before cover art. The
 * scrolling fallback preserves full controls and text; only decorative dp spacing is scaled.
 * Root places the transparent page above the same validated, lifecycle-owned background.
 */
@Composable
fun WatchNowPlayingPage(
    screenShape: ScreenShape,
    title: String,
    artist: String?,
    menuLabel: String,
    onMenu: () -> Unit,
    artwork: @Composable (Dp) -> Unit,
    transport: @Composable (Dp, Boolean) -> Unit,
    visible: Boolean = true,
    onInputFocus: (Boolean, Boolean) -> Unit = { _, _ -> },
    onListPriority: (Boolean) -> Unit = {},
    onRotaryPlayback: (RotaryScrollEvent) -> Boolean = { false },
    content: @Composable ColumnScope.() -> Unit,
) {
    val listState = rememberLazyListState()
    if (!visible) {
        // Keep scroll position, not text measurement, image content, focus or progress observers.
        Box(Modifier.fillMaxSize().clearAndSetSemantics { })
        return
    }
    val colors = LocalWatchThemeColors.current
    val appearance = LocalWatchAppearance.current
    val fonts = LocalWatchThemeFonts.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val windowSize = LocalWindowInfo.current.containerSize
    val drawingInsets = WindowInsets.safeDrawing.union(WindowInsets.ime).asPaddingValues()
    val windowWidth = with(density) { windowSize.width.toDp().value }
    val windowHeight = with(density) { windowSize.height.toDp().value }
    val leftInset = drawingInsets.calculateLeftPadding(layoutDirection).value
    val topInset = drawingInsets.calculateTopPadding().value
    val round = protectsPhysicalRoundBounds(screenShape, configuration.isScreenRound)
    val displayTitle = compactPlaybackTitle(title, appearance.compactPlaybackTitle)
    val titleStyle = MaterialTheme.typography.titleLarge.copy(color = colors.text,
        fontFamily = fonts.originalFamily, fontWeight = fonts.originalWeight, fontSynthesis = FontSynthesis.None)
    val artistStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.secondary,
        fontFamily = fonts.translationFamily, fontWeight = fonts.translationWeight, fontSynthesis = FontSynthesis.None)
    val captionHorizontal = watchSpacing(4.dp)
    val captionVertical = watchSpacing(2.dp)
    val captionShape = RoundedCornerShape(watchSpacing(4.dp))
    val measurer = rememberTextMeasurer()
    // Include opaque caption padding in the actual measured reservation. sp/fontScale stay intact.
    val titleHeight = with(density) {
        measurer.measure(AnnotatedString(displayTitle), style = titleStyle, maxLines = 1,
            overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = windowSize.width.coerceAtLeast(1)))
            .size.height.toDp().value
    } + 2f * captionVertical.value
    val artistHeight = artist?.let {
        with(density) {
            measurer.measure(AnnotatedString(it), style = artistStyle, maxLines = 1,
                overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = windowSize.width.coerceAtLeast(1)))
                .size.height.toDp().value
        } + 2f * captionVertical.value
    }
    val scrollRotary = rememberWatchRotaryScroll(listState, visible)
    val currentPriorityCallback by rememberUpdatedState(onListPriority)
    val gap = watchSpacing(10.dp)
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
        contentAlignment = AbsoluteAlignment.TopLeft) {
        if (!maxWidth.value.isFinite() || !maxHeight.value.isFinite() || maxWidth.value <= 0f || maxHeight.value <= 0f) {
            return@BoxWithConstraints
        }
        val margins = watchPageMargins(maxWidth.value, maxHeight.value,
            appearance.horizontalMarginDp.toFloat(), appearance.verticalMarginDp.toFloat())
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = margins.horizontalDp.dp,
            vertical = margins.verticalDp.dp), contentAlignment = AbsoluteAlignment.TopLeft) {
            val geometry = nowPlayingGeometry(maxWidth.value, maxHeight.value, windowWidth, windowHeight,
                leftInset + margins.horizontalDp, topInset + margins.verticalDp, round, titleHeight, artistHeight,
                spacingScale = appearance.scale)
            // Reuse the existing fixed central reading band for scrolling metadata/actions. Insets
            // and app margins translate its origin; they do not replace the physical circle.
            val reading = lyricsViewportGeometry(maxWidth.value, maxHeight.value, windowWidth, windowHeight,
                leftInset + margins.horizontalDp, topInset + margins.verticalDp, round, toolbarControls = 0,
                options = LyricsDisplayOptions(), spacingScale = appearance.scale)
            val desiredWidth = if (round) maxWidth.value * 0.78f else maxWidth.value - watchSpacing(24.dp).value
            val contentWidth = maxOf(minOf(48f, maxWidth.value),
                minOf(desiredWidth, reading?.lineWidthDp ?: maxWidth.value)).coerceAtMost(maxWidth.value)
            val contentLeft = ((reading?.lineLeftDp ?: 0f) +
                ((reading?.lineWidthDp ?: maxWidth.value) - contentWidth) / 2f).coerceIn(0f, maxWidth.value - contentWidth)
            val before = ((reading?.beforePaddingDp ?: maxHeight.value / 2f) - 24f).coerceAtLeast(0f).dp
            val after = (reading?.afterPaddingDp ?: maxHeight.value / 2f).coerceAtLeast(0f).dp + watchSpacing(12.dp)
            val detailsPadding = watchSpacing(8.dp)
            val detailsHorizontal = minOf(detailsPadding, ((contentWidth - minOf(48f, contentWidth)) / 2f).dp)
            val listHasPriority by remember(listState, geometry != null) {
                derivedStateOf {
                    geometry == null || listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0
                }
            }
            LaunchedEffect(listState, geometry != null) {
                snapshotFlow { geometry == null || listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0 }
                    .distinctUntilChanged().collect { currentPriorityCallback(it) }
            }
            LazyColumn(Modifier.fillMaxSize().watchRotaryInput(visible, "playback", onInputFocus) { event ->
                if (!listHasPriority && onRotaryPlayback(event)) true else scrollRotary(event)
            }, state = listState,
                contentPadding = PaddingValues(top = if (geometry == null) before else 0.dp, bottom = after),
                verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = AbsoluteAlignment.Left) {
                item(key = "playback:hero") {
                    if (geometry != null) {
                        Box(Modifier.fillMaxWidth().height(maxHeight), contentAlignment = AbsoluteAlignment.TopLeft) {
                            WatchCircleButton(menuLabel, WatchGlyph.MENU, modifier = Modifier.absoluteOffset(
                                geometry.menuLeft.dp, geometry.menuTop.dp), onClick = onMenu)
                            geometry.artwork?.let { cover ->
                                Box(Modifier.absoluteOffset((geometry.centerX - cover.size / 2f).dp, cover.top.dp)
                                    .width(cover.size.dp).height(cover.size.dp)) { artwork(cover.size.dp) }
                            }
                            BasicText(displayTitle,
                                Modifier.absoluteOffset((geometry.centerX - geometry.title.width / 2f).dp, geometry.title.top.dp)
                                    .width(geometry.title.width.dp).clip(captionShape).background(colors.surface)
                                    .padding(horizontal = captionHorizontal, vertical = captionVertical),
                                style = titleStyle.copy(textAlign = TextAlign.Center), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            geometry.artist?.let { slot ->
                                BasicText(artist.orEmpty(),
                                    Modifier.absoluteOffset((geometry.centerX - slot.width / 2f).dp, slot.top.dp)
                                        .width(slot.width.dp).clip(captionShape).background(colors.surface)
                                        .padding(horizontal = captionHorizontal, vertical = captionVertical),
                                    style = artistStyle.copy(textAlign = TextAlign.Center), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Box(Modifier.absoluteOffset((geometry.centerX - geometry.transportWidth / 2f).dp, geometry.transportTop.dp)
                                .width(geometry.transportWidth.dp)) { transport(geometry.centerSize.dp, true) }
                        }
                    } else {
                        Column(Modifier.absoluteOffset(x = contentLeft.dp).width(contentWidth.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(gap)) {
                            WatchCircleButton(menuLabel, WatchGlyph.MENU, onClick = onMenu)
                            transport(maxOf(48.dp, watchSpacing(56.dp)), false)
                            BasicText(displayTitle, Modifier.fillMaxWidth().clip(captionShape).background(colors.surface)
                                .padding(horizontal = captionHorizontal, vertical = captionVertical),
                                style = titleStyle.copy(textAlign = TextAlign.Center))
                            if (artist != null) BasicText(artist, Modifier.fillMaxWidth().clip(captionShape).background(colors.surface)
                                .padding(horizontal = captionHorizontal, vertical = captionVertical),
                                style = artistStyle.copy(textAlign = TextAlign.Center))
                        }
                    }
                }
                item(key = "playback:details-and-actions") {
                    Column(Modifier.absoluteOffset(x = contentLeft.dp).width(contentWidth.dp)
                        .clip(RoundedCornerShape(watchSpacing(12.dp))).background(colors.surface)
                        .padding(horizontal = detailsHorizontal, vertical = detailsPadding),
                        verticalArrangement = Arrangement.spacedBy(gap), content = content)
                }
            }
        }
    }
}
