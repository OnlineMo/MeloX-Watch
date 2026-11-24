package io.github.onlinemo.meloxwatch.ui.watch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsClockMapping
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsKind
import io.github.onlinemo.meloxwatch.model.ScreenShape
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsDisplayFrame
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsDisplayOptions
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsFonts
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsPageContent
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsPageFailure
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsPageIdentity
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsPageState
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsPlayPreview
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsScrollObservation
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsTextBlock
import io.github.onlinemo.meloxwatch.ui.lyrics.lyricsAnchorScrollOffset
import io.github.onlinemo.meloxwatch.ui.lyrics.lyricsDisplayFrame
import io.github.onlinemo.meloxwatch.ui.lyrics.lyricsPlayPreview
import io.github.onlinemo.meloxwatch.ui.lyrics.lyricsViewportGeometry
import io.github.onlinemo.meloxwatch.ui.lyrics.lyricsWordRange
import io.github.onlinemo.meloxwatch.ui.lyrics.rememberLyricsPageState
import io.github.onlinemo.meloxwatch.ui.themes.LocalWatchAppearance
import io.github.onlinemo.meloxwatch.ui.themes.LocalWatchThemeColors
import io.github.onlinemo.meloxwatch.ui.themes.LocalWatchThemeFonts

/**
 * 完整换行的手表歌词页；透明页面之上的文字和动作使用已校验的不透明主题底色。
 * 只消费当前会话提供的媒体时间，没有播放器或独立时钟。
 * [visible] 须同时表达页面、前台和亮屏可见性。隐藏时不查询字位置、不跟随或收集滚动输入。
 * [onPreviewPlayFromLine] 只交预览；调用层必须告知确认会发声，并在确认时复核来源与会话安全。
 * [state] 可在分页外持有；传入旧曲状态时使用本曲后备状态，不借用其浏览位置或命令。
 */
@Composable
fun WatchLyricsPage(
    identity: LyricsPageIdentity?,
    content: LyricsPageContent,
    mediaPositionMs: Long,
    mediaDurationMs: Long?,
    screenShape: ScreenShape,
    visible: Boolean,
    modifier: Modifier = Modifier,
    clockMapping: LyricsClockMapping = LyricsClockMapping(),
    options: LyricsDisplayOptions = LyricsDisplayOptions(),
    fonts: LyricsFonts = LocalWatchThemeFonts.current,
    state: LyricsPageState? = null,
    canSeek: Boolean = false,
    onPreviewPlayFromLine: ((LyricsPlayPreview) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onChooseLyrics: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    /** Localized fixed status text from the source owner, never raw responses or credentials. */
    statusLabel: String? = null,
) {
    val ready = content as? LyricsPageContent.Ready
    val document = ready?.document
    val documentId = document?.documentId
    val localState = rememberLyricsPageState(identity, documentId)
    val pageState = if (state != null && state.belongsTo(identity, documentId)) state else localState
    val currentVisible by rememberUpdatedState(visible)
    val currentIdentity by rememberUpdatedState(identity)
    val currentContent by rememberUpdatedState(content)
    val currentMapping by rememberUpdatedState(clockMapping)
    val currentDuration by rememberUpdatedState(mediaDurationMs)
    val currentCanSeek by rememberUpdatedState(canSeek)
    val previewCallback by rememberUpdatedState(onPreviewPlayFromLine)
    if (!visible) {
        // 页面状态仍被持有；移除展示子树同时结束共用控件的交互订阅和辅助功能节点。
        Box(modifier.fillMaxSize().clearAndSetSemantics { })
        return
    }
    val colors = LocalWatchThemeColors.current
    val appearance = LocalWatchAppearance.current
    val bounded = remember(options) { options.bounded() }
    val synchronized = document?.kind == LyricsKind.SYNCHRONIZED
    val browsing = synchronized && !pageState.followsCurrent
    val mediaInput = if (identity != null && synchronized) mediaPositionMs else -1L
    val frame = if (document == null) LyricsDisplayFrame(null, null) else {
        remember(document, identity, mediaInput, clockMapping) {
            lyricsDisplayFrame(document, mediaInput, clockMapping, visible = true, bound = identity != null)
        }
    }
    val returnLabel = stringResource(R.string.lyrics_return_current)
    val backLabel = stringResource(R.string.lyrics_back)
    val settingsLabel = stringResource(R.string.lyrics_settings)
    val currentLabel = stringResource(R.string.lyrics_current_line)
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val window = LocalWindowInfo.current.containerSize
    val viewportInsets = WindowInsets.safeDrawing.union(WindowInsets.ime).asPaddingValues()
    val windowWidth = with(density) { window.width.toDp().value }
    val windowHeight = with(density) { window.height.toDp().value }
    val leftInset = viewportInsets.calculateLeftPadding(layoutDirection).value
    val topInset = viewportInsets.calculateTopPadding().value
    val round = protectsPhysicalRoundBounds(screenShape, configuration.isScreenRound)
    val contextGap = watchSpacing(8.dp)
    val blockGap = watchSpacing(bounded.blockSpacingDp.dp)
    val contextShape = RoundedCornerShape(watchSpacing(8.dp))
    val scrollRotary = rememberWatchRotaryScroll(pageState.listState, visible)
    val manualScroll = remember(pageState, visible) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (visible && source == NestedScrollSource.UserInput && available.y.isFinite() && available.y != 0f) {
                    pageState.beginBrowsing()
                }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(pageState, visible) {
        if (!visible) return@LaunchedEffect
        // 辅助滚动和键盘也可能通过程序化 scroll 进入列表；捕获同一快照里的自动定位标记。
        snapshotFlow {
            LyricsScrollObservation(pageState.listState.isScrollInProgress, pageState.automaticScrollInProgress)
        }.collect { observation ->
            if (observation.scrolling && !observation.automatic) pageState.beginBrowsing()
        }
    }

    val toolbarControls = when {
        onBack != null && (synchronized || onSettings != null) -> 2
        synchronized || onBack != null || onSettings != null -> 1
        else -> 0
    }
    // 几何使用物理左侧坐标；父级基点不能随 RTL 翻转，文字自身仍按语言方向排版。
    BoxWithConstraints(modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = AbsoluteAlignment.TopLeft) {
        if (!maxWidth.value.isFinite() || !maxHeight.value.isFinite() || maxWidth.value <= 0f || maxHeight.value <= 0f) {
            return@BoxWithConstraints
        }
        val margins = watchPageMargins(maxWidth.value, maxHeight.value,
            appearance.horizontalMarginDp.toFloat(), appearance.verticalMarginDp.toFloat())
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = margins.horizontalDp.dp,
            vertical = margins.verticalDp.dp), contentAlignment = AbsoluteAlignment.TopLeft) {
            val actualLeftInset = leftInset + margins.horizontalDp
            val actualTopInset = topInset + margins.verticalDp
            val geometry = remember(maxWidth, maxHeight, windowWidth, windowHeight, actualLeftInset, actualTopInset, round,
                bounded, toolbarControls, appearance.scale) {
                lyricsViewportGeometry(maxWidth.value, maxHeight.value, windowWidth, windowHeight, actualLeftInset, actualTopInset,
                    round, toolbarControls, bounded, spacingScale = appearance.scale)
            }
            val bodyTop = geometry?.bodyTopDp ?: 0f
            val bodyHeight = geometry?.bodyHeightDp ?: maxHeight.value
            val lineLeft = geometry?.lineLeftDp ?: 0f
            val lineWidth = geometry?.lineWidthDp ?: maxWidth.value
            val before = geometry?.beforePaddingDp ?: bodyHeight * bounded.anchorFraction
            val after = geometry?.afterPaddingDp ?: bodyHeight - before
            val anchorPx = with(density) { before.dp.toPx() }
            val lineModifier = Modifier.absoluteOffset(x = lineLeft.dp).width(lineWidth.dp)
            val contextHorizontal = minOf(contextGap, ((lineWidth - minOf(48f, lineWidth)) / 2f).dp)
            val contextModifier = lineModifier.clip(contextShape).background(colors.surface)
                .padding(horizontal = contextHorizontal, vertical = contextGap)

            // 每次行/布局改变最多两次静态定位；字边界不触发滚动，没有持续布局观察或排队动画。
            LaunchedEffect(pageState, visible, pageState.followsCurrent, frame.anchorIndex, geometry, bounded, fonts,
                density, appearance.scale,
                ready?.translation?.translationDocumentId) {
                val anchor = frame.anchorIndex ?: return@LaunchedEffect
                if (!visible || !pageState.followsCurrent || identity == null || !synchronized) return@LaunchedEffect
                val itemIndex = anchor + 1
                val previousHeight = pageState.listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == itemIndex }?.size
                val initial = if (previousHeight == null) 0 else lyricsAnchorScrollOffset(previousHeight, anchorPx)
                val token = pageState.beginAnchorScroll()
                try {
                    pageState.listState.scrollToItem(itemIndex, initial)
                    if (!currentVisible || !pageState.followsCurrent || currentIdentity != identity) return@LaunchedEffect
                    val measuredHeight = pageState.listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == itemIndex }?.size
                        ?: return@LaunchedEffect
                    val corrected = lyricsAnchorScrollOffset(measuredHeight, anchorPx)
                    if (corrected != initial) pageState.listState.scrollToItem(itemIndex, corrected)
                } finally {
                    pageState.endAnchorScroll(token)
                }
            }

            Box(Modifier.fillMaxWidth().absoluteOffset(y = bodyTop.dp).height(bodyHeight.dp)) {
                LazyColumn(
                    Modifier.fillMaxSize().nestedScroll(manualScroll)
                        .watchRotaryInput(visible, pageState) { event ->
                            if (!visible || !event.verticalScrollPixels.isFinite() || event.verticalScrollPixels == 0f) false else {
                                pageState.beginBrowsing()
                                scrollRotary(event)
                            }
                        }
                        .semantics {
                            if (visible && browsing) customActions = listOf(CustomAccessibilityAction(returnLabel) {
                                pageState.returnToCurrent()
                                true
                            })
                        },
                    state = pageState.listState,
                    userScrollEnabled = visible,
                    contentPadding = PaddingValues(top = before.dp, bottom = after.dp),
                    verticalArrangement = Arrangement.spacedBy(blockGap),
                    horizontalAlignment = AbsoluteAlignment.Left,
                ) {
                    item(key = "lyrics:context") {
                        Column(contextModifier, verticalArrangement = Arrangement.spacedBy(contextGap)) {
                            LyricsContext(content, visible, browsing, canSeek && onPreviewPlayFromLine != null,
                                onChooseLyrics, onSettings, onRetry, statusLabel)
                            if (onBack != null) WatchActionRow(backLabel, enabled = visible, onClick = onBack)
                            if (browsing) WatchActionRow(returnLabel, enabled = visible, primary = true,
                                onClick = pageState::returnToCurrent)
                        }
                    }
                    if (ready != null && document != null) {
                        if (synchronized) items(
                            count = document.timeline.size,
                            key = { index ->
                                val id = document.timeline.line(index).id
                                "lyrics:timed:${id.sourceLineNumber}:${id.timestampOccurrence}"
                            },
                        ) { index ->
                            val line = document.timeline.line(index)
                            val position = frame.position
                            val active = position?.isActive(index) == true
                            val word = if (visible && position != null && bounded.emphasizeWords) {
                                document.timeline.word(index, position)
                            } else null
                            val translation = ready.translation?.line(index, document.documentId, bounded.showTranslation)
                            val preview = if (visible && onPreviewPlayFromLine != null) {
                                lyricsPlayPreview(identity, document, index, mediaDurationMs, clockMapping, canSeek)
                            } else null
                            val action = if (preview == null) stringResource(R.string.lyrics_line_play_unavailable) else {
                                stringResource(R.string.lyrics_play_at, lyricsMediaTime(preview.target.mediaPositionMs))
                            }
                            val onPreview: (() -> Unit)? = if (preview == null) null else {
                                {
                                    val latest = currentContent as? LyricsPageContent.Ready
                                    if (currentVisible && currentCanSeek && currentIdentity == preview.identity &&
                                        latest?.document?.documentId == preview.target.documentId &&
                                        currentMapping == preview.clockMapping && currentDuration == preview.durationMs) {
                                        pageState.beginBrowsing()
                                        previewCallback?.invoke(preview)
                                    }
                                }
                            }
                            LyricsTextBlock(line.text, translation?.text, active, lyricsWordRange(line, word), bounded,
                                fonts, currentLabel, action, onPreview, visible, lineModifier)
                        } else if (document.kind == LyricsKind.PLAIN_TEXT) items(
                            count = document.sourceLines.size,
                            key = { index -> "lyrics:plain:${document.sourceLines[index].sourceLineNumber}" },
                        ) { index ->
                            LyricsTextBlock(document.sourceLines[index].text, null, false, null, bounded, fonts,
                                currentLabel, null, null, visible, lineModifier)
                        }
                        item(key = "lyrics:notes") {
                            Column(contextModifier, verticalArrangement = Arrangement.spacedBy(contextGap)) {
                                LyricsDocumentNotes(ready, bounded)
                                if (browsing) WatchActionRow(returnLabel, enabled = visible, primary = true,
                                    onClick = pageState::returnToCurrent)
                            }
                        }
                    }
                }
            }

            val primary = geometry?.primaryToolbar
            val secondary = geometry?.secondaryToolbar
            if (primary != null) {
                val primaryModifier = Modifier.absoluteOffset(primary.leftDp.dp, primary.topDp.dp)
                if (secondary != null && onBack != null) {
                    WatchCircleButton(backLabel, WatchGlyph.BACK, enabled = visible, modifier = primaryModifier, onClick = onBack)
                } else if (browsing) {
                    LyricsReturnButton(returnLabel, visible, primaryModifier, pageState::returnToCurrent)
                } else if (onBack != null) {
                    WatchCircleButton(backLabel, WatchGlyph.BACK, enabled = visible, modifier = primaryModifier, onClick = onBack)
                } else if (onSettings != null) {
                    WatchCircleButton(settingsLabel, WatchGlyph.MENU, enabled = visible, modifier = primaryModifier, onClick = onSettings)
                }
            }
            if (secondary != null) {
                val secondaryModifier = Modifier.absoluteOffset(secondary.leftDp.dp, secondary.topDp.dp)
                if (browsing) LyricsReturnButton(returnLabel, visible, secondaryModifier, pageState::returnToCurrent)
                else if (onSettings != null) WatchCircleButton(settingsLabel, WatchGlyph.MENU, enabled = visible,
                    modifier = secondaryModifier, onClick = onSettings)
            }
        }
    }
}

@Composable
private fun LyricsContext(
    content: LyricsPageContent,
    visible: Boolean,
    browsing: Boolean,
    canPreviewPlay: Boolean,
    onChoose: (() -> Unit)?,
    onSettings: (() -> Unit)?,
    onRetry: (() -> Unit)?,
    statusLabel: String?,
) {
    val message = when (content) {
        LyricsPageContent.Loading -> R.string.lyrics_loading
        LyricsPageContent.Missing -> R.string.lyrics_missing
        is LyricsPageContent.Failed -> when (content.reason) {
            LyricsPageFailure.PERMISSION_REQUIRED -> R.string.lyrics_permission_required
            LyricsPageFailure.INVALID_ENCODING -> R.string.lyrics_invalid_encoding
            LyricsPageFailure.INPUT_LIMIT -> R.string.lyrics_input_limit
            LyricsPageFailure.UNSUPPORTED -> R.string.lyrics_unsupported
            LyricsPageFailure.READ_FAILED -> R.string.lyrics_read_failed
        }
        is LyricsPageContent.Ready -> when (content.document.kind) {
            LyricsKind.EMPTY -> R.string.lyrics_empty
            LyricsKind.PLAIN_TEXT -> R.string.lyrics_plain_text
            LyricsKind.SYNCHRONIZED -> if (browsing) R.string.lyrics_browsing else R.string.lyrics_following
        }
    }
    WatchText(if (content is LyricsPageContent.Ready) stringResource(message)
        else statusLabel ?: stringResource(message), Modifier.fillMaxWidth(), primary = true,
        error = content is LyricsPageContent.Failed)
    if (content is LyricsPageContent.Ready) {
        val source = content.sourceLabel
        if (!source.isNullOrBlank()) WatchText(source, Modifier.fillMaxWidth())
        if (!statusLabel.isNullOrBlank()) WatchText(statusLabel, Modifier.fillMaxWidth(), error = true)
        if (content.document.kind == LyricsKind.SYNCHRONIZED && canPreviewPlay) {
            WatchText(stringResource(R.string.lyrics_play_warning), Modifier.fillMaxWidth())
        }
    }
    if (onChoose != null) WatchActionRow(stringResource(R.string.lyrics_choose), enabled = visible, onClick = onChoose)
    if (onSettings != null) WatchActionRow(stringResource(R.string.lyrics_settings), enabled = visible, onClick = onSettings)
    if ((content is LyricsPageContent.Failed || content is LyricsPageContent.Ready && !statusLabel.isNullOrBlank()) && onRetry != null) {
        WatchActionRow(stringResource(R.string.lyrics_retry), enabled = visible, onClick = onRetry)
    }
}

@Composable
private fun LyricsDocumentNotes(content: LyricsPageContent.Ready, options: LyricsDisplayOptions) {
    val document = content.document
    if (document.diagnostics.hasWarnings) WatchText(stringResource(R.string.lyrics_ignored_markers), Modifier.fillMaxWidth())
    if (document.kind == LyricsKind.SYNCHRONIZED && options.showTranslation) {
        val translation = content.translation
        if (translation == null || translation.primaryDocumentId != document.documentId ||
            translation.diagnostics.pairedLineCount == 0) {
            WatchText(stringResource(R.string.lyrics_no_translation), Modifier.fillMaxWidth())
        } else if (translation.diagnostics.unmatchedPrimaryLineCount != 0) {
            WatchText(stringResource(R.string.lyrics_partial_translation), Modifier.fillMaxWidth())
        }
    }
    if (document.kind == LyricsKind.SYNCHRONIZED && options.emphasizeWords && !document.hasWordTimings) {
        WatchText(stringResource(R.string.lyrics_line_timing_only), Modifier.fillMaxWidth())
    }
}

@Composable
private fun LyricsReturnButton(label: String, visible: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalWatchThemeColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed = if (visible) {
        val value by interaction.collectIsPressedAsState()
        value
    } else false
    val focused = if (visible) {
        val value by interaction.collectIsFocusedAsState()
        value
    } else false
    Box(
        modifier.size(48.dp).clip(CircleShape)
            .background(if (pressed) colors.pressed else colors.raised)
            .border(if (focused) 2.dp else 1.dp, if (focused) colors.focus else colors.outline, CircleShape)
            .semantics { contentDescription = label }
            .clickable(interactionSource = interaction, indication = null, enabled = visible, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(watchSpacing(24.dp))) {
            val color = if (visible) colors.text else colors.disabled
            val center = Offset(size.width / 2f, size.height / 2f)
            val stroke = 2.dp.toPx()
            drawCircle(color, radius = size.minDimension * 0.27f, center = center, style = Stroke(stroke))
            drawCircle(color, radius = size.minDimension * 0.07f, center = center)
            for (direction in listOf(-1f, 1f)) {
                drawLine(color, center + Offset(direction * size.width * 0.33f, 0f),
                    center + Offset(direction * size.width * 0.46f, 0f), strokeWidth = stroke)
                drawLine(color, center + Offset(0f, direction * size.height * 0.33f),
                    center + Offset(0f, direction * size.height * 0.46f), strokeWidth = stroke)
            }
        }
    }
}

private fun lyricsMediaTime(positionMs: Long): String {
    val seconds = positionMs / 1_000L
    val minutes = seconds / 60L
    val hours = minutes / 60L
    val time = if (hours == 0L) "$minutes:${(seconds % 60L).toString().padStart(2, '0')}" else {
        "$hours:${(minutes % 60L).toString().padStart(2, '0')}:${(seconds % 60L).toString().padStart(2, '0')}"
    }
    val fraction = positionMs % 1_000L
    return if (fraction == 0L) time else "$time.${fraction.toString().padStart(3, '0')}"
}
