package io.github.onlinemo.meloxwatch.ui.watch

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

internal data class HeroText(val top: Float, val width: Float, val height: Float)
internal data class HeroArtwork(val top: Float, val size: Float)

/** 各位置和尺寸以 dp 表示，原点为去除系统 inset 后的可见窗口左上角。 */
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
): NowPlayingGeometry? {
    val dimensions = listOf(width, height, windowWidth, windowHeight, leftInset, topInset, titleHeight)
    if (dimensions.any { !it.isFinite() } || artistHeight?.isFinite() == false ||
        width <= 0f || height < 48f || windowWidth <= 0f || windowHeight <= 0f ||
        leftInset < 0f || topInset < 0f || titleHeight <= 0f || (artistHeight != null && artistHeight <= 0f)) return null

    val centerX = if (round) windowWidth / 2f - leftInset else width / 2f
    val centerY = if (round) windowHeight / 2f - topInset else height / 2f
    val radius = minOf(windowWidth, windowHeight) / 2f - 8f
    val innerRadius = radius - 1f
    val centerSize = if (width >= 208f) 64f else 56f
    val sideDistance = centerSize / 2f + 8f + 24f
    val transportWidth = 96f + centerSize + 16f
    if (width < transportWidth || (round && innerRadius <= 24f)) return null
    // Even before the round edge and menu are considered, these regions must fit without
    // overlap. Let the scrolling page retain large text rather than discard the artist.
    if (titleHeight + (artistHeight ?: 0f) + centerSize + 8f > height) return null

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
        val verticalRoom = minOf(sqrt(sideRoom * sideRoom - sideDistance * sideDistance), radius - centerSize / 2f)
        minOf(centerY + verticalRoom - 2f, height - centerSize / 2f - 12f)
    } else height - centerSize / 2f - 12f

    // A small round window can retain both text rows by moving the menu nearer the top and
    // the controls to the center. These six fixed placements keep layout work bounded.
    val menuFractions = if (round) listOf(0.75f, 0.64f, 0.44f) else listOf(0f)
    val transportCenters = listOf(preferredTransportCenter, centerY).distinct()
    var fallback: NowPlayingGeometry? = null
    for (menuFraction in menuFractions) {
        for (transportCenter in transportCenters) {
            val menuHorizontal = if (round) menuRoom * menuFraction else 0f
            val menuVertical = if (round) sqrt(menuRoom * menuRoom - menuHorizontal * menuHorizontal) else 0f
            val menuCenterX = if (round) (centerX - menuHorizontal).coerceIn(24f, width - 24f) else 36f
            val menuCenterY = if (round) (centerY - menuVertical).coerceIn(24f, height - 24f) else 36f
            if (!containsCircle(menuCenterX, menuCenterY, 48f)) continue

            fun separatedFromMenu(left: Float, top: Float, rectangleWidth: Float, rectangleHeight: Float): Boolean {
                val nearestX = menuCenterX.coerceIn(left, left + rectangleWidth)
                val nearestY = menuCenterY.coerceIn(top, top + rectangleHeight)
                return hypot(menuCenterX - nearestX, menuCenterY - nearestY) >= 30f
            }

            fun textSlot(top: Float, textHeight: Float): HeroText? {
                if (top < 0f || top + textHeight > height) return null
                val available = if (round) {
                    val farthestY = maxOf(abs(top - centerY), abs(top + textHeight - centerY))
                    if (farthestY >= innerRadius) return null
                    minOf(width * 0.78f, 2f * sqrt(innerRadius * innerRadius - farthestY * farthestY))
                } else width - 24f
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
                        hypot(x - menuCenterX, transportCenter - menuCenterY) >= 24f + size / 2f + 6f
                }
            if (!controlsFit) continue

            val transportTop = transportCenter - centerSize / 2f
            val transportBottom = transportCenter + centerSize / 2f
            val combinedTop = transportTop - 6f - titleHeight - (artistHeight?.let { it + 2f } ?: 0f)
            val combinedTitle = textSlot(combinedTop, titleHeight)
            val combinedArtist = artistHeight?.let { textSlot(combinedTop + titleHeight + 2f, it) }
            val title: HeroText
            val artist: HeroText?
            if (combinedTitle != null && (artistHeight == null || combinedArtist != null)) {
                title = combinedTitle
                artist = combinedArtist
            } else {
                val titleBeforeControls = textSlot(transportTop - 6f - titleHeight, titleHeight)
                if (titleBeforeControls != null) {
                    title = titleBeforeControls
                    artist = artistHeight?.let { textSlot(transportBottom + 6f, it) }
                } else {
                    title = textSlot(transportBottom + 6f, titleHeight) ?: continue
                    artist = artistHeight?.let { textSlot(title.top + titleHeight + 2f, it) }
                }
            }
            if (artistHeight != null && artist == null) continue

            // 封面只用有界的静态尺寸候选；先保留实测文字和控件，再检查圆边及菜单间隔。
            var artwork: HeroArtwork? = null
            if (title.top < transportTop) {
                for (size in listOf(104f, 96f, 88f, 80f, 72f, 64f, 56f, 48f)) {
                    if (size > width * 0.44f || (round && size / 2f >= innerRadius)) continue
                    val top = if (round) maxOf(0f, centerY - sqrt(innerRadius * innerRadius - size * size / 4f)) else 12f
                    val left = centerX - size / 2f
                    if (top + size + 6f > title.top || !containsRectangle(left, top, size, size) ||
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
