package io.github.onlinemo.meloxwatch.ui.watch

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

internal data class HeroText(val top: Float, val width: Float, val height: Float)
internal data class HeroArtwork(val top: Float, val size: Float)

internal data class WatchPageMargins(val horizontalDp: Float, val verticalDp: Float)

/** App margins reserve a usable scrolling lane; they never resize the physical screen. */
internal fun watchPageMargins(width: Float, height: Float, horizontal: Float, vertical: Float): WatchPageMargins {
    if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return WatchPageMargins(0f, 0f)
    fun applied(request: Float, dimension: Float, minimum: Float): Float {
        if (!request.isFinite()) return 0f
        val room = (dimension - minOf(minimum, dimension)) / 2f
        return request.coerceIn(0f, 24f).coerceAtMost(room)
    }
    return WatchPageMargins(applied(horizontal, width, 96f), applied(vertical, height, 104f))
}

/** 各位置和尺寸以 dp 表示，原点为去除系统 inset 和应用 margin 后的 viewport 左上角。 */
internal data class NowPlayingGeometry(
    val centerX: Float,
    val menuLeft: Float,
    val menuTop: Float,
    val transportTop: Float,
    val transportWidth: Float,
    val centerSize: Float,
    val title: HeroText,
    val artist: HeroText?,
    val artwork: HeroArtwork?,
)

/**
 * 使用调用方通过 TextMeasurer 得到的当前字形高度，安排一屏的菜单、文字及独立圆形控制。
 *
 * 输入及输出均为 dp；[width]/[height] 是当前可见窗口，物理圆仍由 [windowWidth]/[windowHeight]
 * 和 [leftInset]/[topInset] 决定，输入法缩窗不能改变它。方屏保留矩形边角。
 * 无法保留完整触区、曲名及艺人时返回 null，由页面提供完整滚动路径，不缩小文字或控制。
 */
internal fun nowPlayingGeometry(
    width: Float,
    height: Float,
    windowWidth: Float,
    windowHeight: Float,
    leftInset: Float,
    topInset: Float,
    round: Boolean,
    titleHeight: Float,
    artistHeight: Float?,
    spacingScale: Float = 1f,
): NowPlayingGeometry? {
    val dimensions = listOf(width, height, windowWidth, windowHeight, leftInset, topInset, titleHeight)
    if (dimensions.any { !it.isFinite() } || artistHeight?.isFinite() == false ||
        width <= 0f || height < 48f || windowWidth <= 0f || windowHeight <= 0f ||
        leftInset < 0f || topInset < 0f || titleHeight <= 0f || (artistHeight != null && artistHeight <= 0f) ||
        !spacingScale.isFinite() || spacingScale !in 0.85f..1.2f) return null

    val centerX = if (round) windowWidth / 2f - leftInset else width / 2f
    val centerY = if (round) windowHeight / 2f - topInset else height / 2f
    val radius = minOf(windowWidth, windowHeight) / 2f - maxOf(8f, 8f * spacingScale)
    val innerRadius = radius - maxOf(1f, spacingScale)
    val centerSize = maxOf(48f, (if (width >= 208f) 64f else 56f) * spacingScale)
    val controlGap = 8f * spacingScale
    val clearance = maxOf(6f, 6f * spacingScale)
    val textGap = 2f * spacingScale
    val edgeSpacing = 12f * spacingScale
    val sideDistance = centerSize / 2f + controlGap + 24f
    val transportWidth = 96f + centerSize + controlGap * 2f
    if (width < transportWidth || (round && innerRadius <= 24f)) return null
    // A finite extreme value can still lose a 48dp separation in Float coordinates.
    if (centerX + sideDistance == centerX || centerX - sideDistance == centerX) return null
    // Even before the round edge and menu are considered, these regions must fit without
    // overlap. Let the scrolling page retain large text rather than discard the artist.
    if (titleHeight + (artistHeight ?: 0f) + centerSize + controlGap > height) return null

    fun containsCircle(x: Float, y: Float, size: Float): Boolean {
        val half = size / 2f
        if (x - half < 0f || x + half > width || y - half < 0f || y + half > height) return false
        return !round || hypot(x - centerX, y - centerY) + half <= radius
    }

    fun containsRectangle(left: Float, top: Float, rectangleWidth: Float, rectangleHeight: Float): Boolean {
        if (left < 0f || top < 0f || left + rectangleWidth > width || top + rectangleHeight > height) return false
        if (!round) return true
        val farthestX = maxOf(abs(left - centerX), abs(left + rectangleWidth - centerX))
        val farthestY = maxOf(abs(top - centerY), abs(top + rectangleHeight - centerY))
        return hypot(farthestX, farthestY) <= radius
    }

    // 48dp 菜单比 Swift 参考的大；沿左上圆边内移，不能占掉整条封面行。
    val menuRoom = innerRadius - 24f
    val preferredTransportCenter = if (round) {
        val sideRoom = radius - 24f
        if (sideDistance > sideRoom) return null
        val verticalRoom = minOf(sqrt(sideRoom.toDouble() * sideRoom - sideDistance.toDouble() * sideDistance).toFloat(),
            radius - centerSize / 2f)
        minOf(centerY + verticalRoom - textGap, height - centerSize / 2f - edgeSpacing)
    } else height - centerSize / 2f - edgeSpacing

    // A small round window can retain both text rows by moving the menu nearer the top and
    // the controls to the center. These six fixed placements keep layout work bounded.
    val menuFractions = if (round) listOf(0.75f, 0.64f, 0.44f) else listOf(0f)
    val transportCenters = listOf(preferredTransportCenter, centerY).distinct()
    var fallback: NowPlayingGeometry? = null
    for (menuFraction in menuFractions) {
        for (transportCenter in transportCenters) {
            val menuHorizontal = if (round) menuRoom * menuFraction else 0f
            val menuVertical = if (round) {
                sqrt(menuRoom.toDouble() * menuRoom - menuHorizontal.toDouble() * menuHorizontal).toFloat()
            } else 0f
            val menuCenterX = if (round) (centerX - menuHorizontal).coerceIn(24f, width - 24f) else 24f + edgeSpacing
            val menuCenterY = if (round) (centerY - menuVertical).coerceIn(24f, height - 24f) else 24f + edgeSpacing
            if (!containsCircle(menuCenterX, menuCenterY, 48f)) continue

            fun separatedFromMenu(left: Float, top: Float, rectangleWidth: Float, rectangleHeight: Float): Boolean {
                val nearestX = menuCenterX.coerceIn(left, left + rectangleWidth)
                val nearestY = menuCenterY.coerceIn(top, top + rectangleHeight)
                return hypot(menuCenterX - nearestX, menuCenterY - nearestY) >= 24f + clearance
            }

            fun textSlot(top: Float, textHeight: Float): HeroText? {
                if (top < 0f || top + textHeight > height) return null
                val available = if (round) {
                    val farthestY = maxOf(abs(top - centerY), abs(top + textHeight - centerY))
                    if (farthestY >= innerRadius) return null
                    minOf(width * 0.78f,
                        (2.0 * sqrt(innerRadius.toDouble() * innerRadius - farthestY.toDouble() * farthestY)).toFloat())
                } else width - edgeSpacing * 2f
                val textWidth = minOf(available, 2f * minOf(centerX, width - centerX))
                val left = centerX - textWidth / 2f
                if (textWidth < 48f || !containsRectangle(left, top, textWidth, textHeight) ||
                    !separatedFromMenu(left, top, textWidth, textHeight)) return null
                return HeroText(top, textWidth, textHeight)
            }

            // 逐个检查实际圆形热区；三键整行的空白外角不构成触区。
            val controlsFit = listOf(centerX to centerSize, centerX - sideDistance to 48f, centerX + sideDistance to 48f)
                .all { (x, size) ->
                    containsCircle(x, transportCenter, size) &&
                        hypot(x - menuCenterX, transportCenter - menuCenterY) >= 24f + size / 2f + clearance
                }
            if (!controlsFit) continue

            val transportTop = transportCenter - centerSize / 2f
            val transportBottom = transportCenter + centerSize / 2f
            val combinedTop = transportTop - clearance - titleHeight - (artistHeight?.let { it + textGap } ?: 0f)
            val combinedTitle = textSlot(combinedTop, titleHeight)
            val combinedArtist = artistHeight?.let { textSlot(combinedTop + titleHeight + textGap, it) }
            val title: HeroText
            val artist: HeroText?
            if (combinedTitle != null && (artistHeight == null || combinedArtist != null)) {
                title = combinedTitle
                artist = combinedArtist
            } else {
                val titleBeforeControls = textSlot(transportTop - clearance - titleHeight, titleHeight)
                if (titleBeforeControls != null) {
                    title = titleBeforeControls
                    artist = artistHeight?.let { textSlot(transportBottom + clearance, it) }
                } else {
                    title = textSlot(transportBottom + clearance, titleHeight) ?: continue
                    artist = artistHeight?.let { textSlot(title.top + titleHeight + textGap, it) }
                }
            }
            if (artistHeight != null && artist == null) continue

            // 封面只用有界的静态尺寸候选；先保留实测文字和控件，再检查圆边及菜单间隔。
            var artwork: HeroArtwork? = null
            if (title.top < transportTop) {
                for (baseSize in listOf(104f, 96f, 88f, 80f, 72f, 64f, 56f, 48f)) {
                    val size = maxOf(48f, baseSize * spacingScale)
                    if (size > width * 0.44f || (round && size / 2f >= innerRadius)) continue
                    val top = if (round) {
                        maxOf(0f, centerY - sqrt(innerRadius.toDouble() * innerRadius - size.toDouble() * size / 4.0).toFloat())
                    } else edgeSpacing
                    val left = centerX - size / 2f
                    if (top + size + clearance > title.top || !containsRectangle(left, top, size, size) ||
                        !separatedFromMenu(left, top, size, size)) continue
                    artwork = HeroArtwork(top, size)
                    break
                }
            }
            val geometry = NowPlayingGeometry(centerX, menuCenterX - 24f, menuCenterY - 24f, transportTop, transportWidth,
                centerSize, title, artist, artwork)
            if (artwork != null) return geometry
            // Prefer a complete cover layout; otherwise keep the first complete text layout
            // so an inset change does not move the menu merely to change empty space.
            if (fallback == null) fallback = geometry
        }
    }
    return fallback
}
