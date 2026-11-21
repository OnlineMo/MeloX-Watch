package io.github.onlinemo.meloxwatch.ui.lyrics

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class LyricsToolbarSlot(val leftDp: Float, val topDp: Float)

/** 坐标均为去除系统 inset 和应用 margin 后的 viewport 内 dp；圆心仍来自完整物理窗口。 */
internal data class LyricsViewportGeometry(
    val bodyTopDp: Float,
    val bodyHeightDp: Float,
    val lineLeftDp: Float,
    val lineWidthDp: Float,
    val anchorDp: Float,
    val primaryToolbar: LyricsToolbarSlot?,
    val secondaryToolbar: LyricsToolbarSlot?,
) {
    val beforePaddingDp: Float get() = anchorDp
    val afterPaddingDp: Float get() = bodyHeightDp - anchorDp
}

/**
 * 使用固定中央阅读列避免滚动引起反复换行；端部内容可滚到该列的中央位置完整浏览。
 * 长块可以逐段滚动，几何辅助不承诺整块外框同屏在圆内。极小窗口回到完整滚动路径。
 */
internal fun lyricsViewportGeometry(
    widthDp: Float,
    heightDp: Float,
    windowWidthDp: Float,
    windowHeightDp: Float,
    leftInsetDp: Float,
    topInsetDp: Float,
    round: Boolean,
    toolbarControls: Int,
    options: LyricsDisplayOptions,
    spacingScale: Float = 1f,
): LyricsViewportGeometry? {
    val dimensions = listOf(widthDp, heightDp, windowWidthDp, windowHeightDp, leftInsetDp, topInsetDp)
    if (dimensions.any { !it.isFinite() } || widthDp <= 0f || heightDp <= 0f ||
        windowWidthDp <= 0f || windowHeightDp <= 0f || leftInsetDp < 0f || topInsetDp < 0f ||
        toolbarControls !in 0..2 || !spacingScale.isFinite() || spacingScale !in 0.85f..1.2f) return null

    val bounded = options.bounded()
    val centerX = if (round) windowWidthDp / 2f - leftInsetDp else widthDp / 2f
    val centerY = if (round) windowHeightDp / 2f - topInsetDp else heightDp / 2f
    val radius = minOf(windowWidthDp, windowHeightDp) / 2f - maxOf(8f, 8f * spacingScale)
    val spacing = 8f * spacingScale

    fun circleFits(x: Float, y: Float): Boolean {
        if (x - 24f < 0f || x + 24f > widthDp || y - 24f < 0f || y + 24f > heightDp) return false
        return !round || hypot(x.toDouble() - centerX, y.toDouble() - centerY) + 24.0 <= radius.toDouble()
    }

    fun toolbarSlots(two: Boolean): List<LyricsToolbarSlot> {
        if (widthDp < 48f || heightDp < 96f + spacing || (round && radius <= 24f + maxOf(1f, spacingScale))) {
            return emptyList()
        }
        // Both controls keep 48dp targets. Only the empty space between them changes.
        val horizontal = if (two) 24f + spacing else 0f
        val center = if (round) {
            val room = radius - 24f - maxOf(1f, spacingScale)
            if (horizontal > room) return emptyList()
            (centerY.toDouble() - sqrt(room.toDouble() * room - horizontal.toDouble() * horizontal))
                .toFloat().coerceIn(24f, heightDp - 24f)
        } else 24f + spacing
        val centers = if (two) listOf(centerX - horizontal, centerX + horizontal) else listOf(centerX)
        if (two && centers[1] - centers[0] < 54f) return emptyList()
        if (centers.any { !circleFits(it, center) } || center + 24f + spacing > heightDp - 48f) return emptyList()
        return centers.map { LyricsToolbarSlot(it - 24f, center - 24f) }
    }

    var toolbar = if (toolbarControls == 0) emptyList() else toolbarSlots(toolbarControls == 2)
    if (toolbar.isEmpty() && toolbarControls == 2) toolbar = toolbarSlots(false)
    val bodyTop = if (toolbar.isEmpty()) 0f else toolbar[0].topDp + 48f + spacing
    val bodyHeight = heightDp - bodyTop
    var anchor = bodyHeight * bounded.anchorFraction
    val minimumReadingWidth = minOf(96f, widthDp)
    if (round && radius > minimumReadingWidth / 2f) {
        val horizontal = minimumReadingWidth.toDouble() / 2.0
        val vertical = sqrt(radius.toDouble() * radius - horizontal * horizontal).toFloat() -
            minOf(24f, bodyHeight / 2f)
        if (vertical >= 0f) {
            val minimumAnchor = maxOf(0f, centerY - vertical - bodyTop)
            val maximumAnchor = minOf(bodyHeight, centerY + vertical - bodyTop)
            if (minimumAnchor <= maximumAnchor) anchor = anchor.coerceIn(minimumAnchor, maximumAnchor)
        }
    }
    val anchorWindowY = bodyTop + anchor

    // An undersized system viewport first gives up decoration, keeping the complete
    // scrolling lane and its 48dp controls whenever the real window can hold them.
    var availableLeft = minOf(spacing, widthDp / 4f, (widthDp - minimumReadingWidth) / 2f)
    var availableRight = widthDp - availableLeft
    if (round && radius > 0f) {
        val distance = abs(anchorWindowY - centerY) + minOf(24f, bodyHeight / 2f)
        if (distance < radius) {
            val halfChord = sqrt(radius.toDouble() * radius - distance.toDouble() * distance).toFloat()
            availableLeft = maxOf(availableLeft, centerX - halfChord)
            availableRight = minOf(availableRight, centerX + halfChord)
        } else {
            // 系统遮挡可能没有完整中央带；仍给真实窗口里的正宽度，不臆造缩小后的圆。
            availableLeft = maxOf(availableLeft, centerX - radius * 0.5f).coerceIn(0f, widthDp)
            availableRight = minOf(availableRight, centerX + radius * 0.5f).coerceIn(0f, widthDp)
        }
    }
    if (availableRight <= availableLeft) {
        availableLeft = 0f
        availableRight = widthDp
    }
    val availableWidth = availableRight - availableLeft
    val minimumWidth = minOf(minimumReadingWidth, availableWidth)
    val margin = minOf(bounded.horizontalMarginDp, (availableWidth - minimumWidth) / 2f)
    return LyricsViewportGeometry(
        bodyTopDp = bodyTop,
        bodyHeightDp = bodyHeight,
        lineLeftDp = availableLeft + margin,
        lineWidthDp = availableWidth - margin * 2f,
        anchorDp = anchor,
        primaryToolbar = toolbar.firstOrNull(),
        secondaryToolbar = toolbar.getOrNull(1),
    )
}

/** 短行按真实块高居中；超高块对齐可见起点，允许手动浏览全部文本，不反复追求不可达中心。 */
internal fun lyricsAnchorScrollOffset(itemHeightPx: Int, anchorPx: Float): Int {
    if (itemHeightPx <= 0 || !anchorPx.isFinite() || anchorPx <= 0f) return 0
    return minOf(itemHeightPx / 2f, anchorPx).roundToInt().coerceAtLeast(0)
}
