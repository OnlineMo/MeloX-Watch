package io.github.onlinemo.meloxwatch.ui.watch

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.rotary.RotaryScrollEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.onlinemo.meloxwatch.model.ScreenShape
import io.github.onlinemo.meloxwatch.ui.ProbePage
import kotlinx.coroutines.flow.distinctUntilChanged

/** Only settled navigation owns observers. Neighbor pages render already held rows without starting work. */
@Composable
fun WatchPlaybackPages(
    page: ProbePage,
    enabled: Boolean,
    onPage: (ProbePage) -> Unit,
    content: @Composable (ProbePage, Boolean) -> Unit,
) {
    val pages = listOf(ProbePage.QUEUE, ProbePage.PLAYBACK)
    val currentOnPage by rememberUpdatedState(onPage)
    val pager = rememberPagerState(initialPage = pages.indexOf(page).coerceAtLeast(0), pageCount = { pages.size })
    LaunchedEffect(page) {
        val target = pages.indexOf(page)
        if (target >= 0 && pager.settledPage != target) pager.scrollToPage(target)
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { settled -> currentOnPage(pages[settled]) }
    }
    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), userScrollEnabled = enabled,
        beyondViewportPageCount = 0) { index ->
        val current = pages[index]
        content(current, current == page)
    }
}

/**
 * 紧凑首屏先安排独立触区和实测文字，再为封面分配空间；菜单位于左上可触区域。
 * 字体或当前窗口无法容纳时使用完整滚动路径，详情仍由同一播放会话提供。
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
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val windowSize = LocalWindowInfo.current.containerSize
    val drawingInsets = WindowInsets.safeDrawing.asPaddingValues()
    val windowWidth = with(density) { windowSize.width.toDp().value }
    val windowHeight = with(density) { windowSize.height.toDp().value }
    val leftInset = drawingInsets.calculateLeftPadding(layoutDirection).value
    val topInset = drawingInsets.calculateTopPadding().value
    val round = when (screenShape) {
        ScreenShape.AUTO -> configuration.isScreenRound
        ScreenShape.ROUND -> true
        ScreenShape.RECTANGULAR -> false
    }
    val titleStyle = MaterialTheme.typography.titleLarge
    val artistStyle = MaterialTheme.typography.bodyMedium
    val measurer = rememberTextMeasurer()
    // 使用当前字体的实际字形高度，不能由 sp 或 fontScale 推算固定占位。
    val titleHeight = with(density) {
        measurer.measure(AnnotatedString(title), style = titleStyle, maxLines = 1,
            overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = windowSize.width.coerceAtLeast(1)))
            .size.height.toDp().value
    }
    val artistHeight = artist?.let {
        with(density) {
            measurer.measure(AnnotatedString(it), style = artistStyle, maxLines = 1,
                overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = windowSize.width.coerceAtLeast(1)))
                .size.height.toDp().value
        }
    }
    val listState = rememberLazyListState()
    val scrollRotary = rememberWatchRotaryScroll(listState, visible)
    val currentPriorityCallback by rememberUpdatedState(onListPriority)
    BoxWithConstraints(Modifier.fillMaxSize().background(WatchPalette.Background).safeDrawingPadding().imePadding()) {
        val detailsWidth = if (round) maxWidth * 0.78f else maxWidth - 24.dp
        val contentWidth = detailsWidth.coerceIn(minOf(48.dp, maxWidth), maxWidth)
        val geometry = nowPlayingGeometry(maxWidth.value, maxHeight.value, windowWidth, windowHeight,
            leftInset, topInset, round, titleHeight, artistHeight)
        val listHasPriority = geometry == null || listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0
        LaunchedEffect(listState, visible, geometry != null) {
            if (visible) snapshotFlow { geometry == null || listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0 }
                .distinctUntilChanged().collect { currentPriorityCallback(it) }
        }
        // IME reduces the viewport, not the physical round boundary or its central scroll padding.
        val scrollInset = if (round) maxOf(windowHeight.dp, maxHeight) * 0.22f else 12.dp
        LazyColumn(Modifier.fillMaxSize().watchRotaryInput(visible, "playback", onInputFocus) { event ->
            if (!listHasPriority && onRotaryPlayback(event)) true else scrollRotary(event)
        }, state = listState,
            contentPadding = PaddingValues(top = if (geometry == null) scrollInset else 0.dp,
                bottom = scrollInset + 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "playback:hero") {
                if (geometry != null) {
                    Box(Modifier.fillMaxWidth().height(maxHeight)) {
                        WatchCircleButton(menuLabel, WatchGlyph.MENU, modifier = Modifier.absoluteOffset(
                            geometry.menuLeft.dp, geometry.menuTop.dp), onClick = onMenu)
                        geometry.artwork?.let { cover ->
                            Box(Modifier.absoluteOffset((geometry.centerX - cover.size / 2f).dp, cover.top.dp)
                                .width(cover.size.dp).height(cover.size.dp)) { artwork(cover.size.dp) }
                        }
                        Text(title, Modifier.absoluteOffset((geometry.centerX - geometry.title.width / 2f).dp, geometry.title.top.dp)
                            .width(geometry.title.width.dp), color = WatchPalette.Text, style = titleStyle,
                            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        geometry.artist?.let { slot ->
                            Text(artist.orEmpty(), Modifier.absoluteOffset((geometry.centerX - slot.width / 2f).dp, slot.top.dp)
                                .width(slot.width.dp), color = WatchPalette.Secondary, style = artistStyle,
                                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box(Modifier.absoluteOffset((geometry.centerX - geometry.transportWidth / 2f).dp, geometry.transportTop.dp)
                            .width(geometry.transportWidth.dp)) { transport(geometry.centerSize.dp, true) }
                    }
                } else {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        Column(Modifier.width(contentWidth), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // 滚动后备保留居中触区，侧边系统遮挡不能让菜单成为唯一不可触操作。
                            WatchCircleButton(menuLabel, WatchGlyph.MENU, onClick = onMenu)
                            // 大字保留原字号和完整控制，不让封面挤压它们。
                            transport(56.dp, false)
                            Text(title, Modifier.fillMaxWidth(), color = WatchPalette.Text, style = titleStyle,
                                textAlign = TextAlign.Center)
                            if (artist != null) Text(artist, Modifier.fillMaxWidth(), color = WatchPalette.Secondary,
                                style = artistStyle, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            item(key = "playback:details-and-actions") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.width(contentWidth), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
                }
            }
        }
    }
}
