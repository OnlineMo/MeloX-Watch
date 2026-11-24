package io.github.onlinemo.meloxwatch.ui.themes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import io.github.onlinemo.meloxwatch.data.themes.ThemePalette
import io.github.onlinemo.meloxwatch.data.themes.validateThemePalette
import kotlin.math.roundToInt

/** Static decoration only. The controller, rather than composition, owns/retire its lease. */
@Composable
fun WatchPlaybackBackground(
    display: PlaybackBackgroundDisplay,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier) {
        Canvas(Modifier.matchParentSize().clearAndSetSemantics {}) { drawWatchPlaybackBackground(display) }
        content()
    }
}

/** Required text/actions sit on these opaque validated surfaces, never directly on a photo. */
@Composable
fun WatchThemeOpaqueSurface(
    palette: ThemePalette,
    modifier: Modifier = Modifier,
    raised: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    validateThemePalette(palette)
    Box(modifier.background(Color(if (raised) palette.surfaceRaised else palette.surface)), content = content)
}

/** Shared by the real composable and native graphics definitions; it never starts a resource load. */
internal fun DrawScope.drawWatchPlaybackBackground(display: PlaybackBackgroundDisplay) {
    val frame = display.frame
    drawRect(Color(frame?.baseColor ?: display.palette?.background ?: 0xff000000.toInt()))
    if (!display.visible || frame == null || size.width <= 0f || size.height <= 0f) return
    val request = display.request ?: return
    val image = frame.image?.bitmap ?: return
    if (image.isRecycled) return
    fun DrawScope.paint() {
        drawImage(image.asImageBitmap(), dstSize = IntSize(size.width.roundToInt().coerceAtLeast(1),
            size.height.roundToInt().coerceAtLeast(1)), filterQuality = FilterQuality.Low)
        val mask = display.darkMaskPercent
        if (mask != 0) drawRect(Color.Black.copy(alpha = mask / 100f))
    }
    val circle = request.viewport.roundClip
    if (circle == null) paint()
    else {
        val scaleX = size.width / request.viewport.widthPx
        val scaleY = size.height / request.viewport.heightPx
        val path = Path().apply {
            addOval(Rect((circle.centerX - circle.radius) * scaleX, (circle.centerY - circle.radius) * scaleY,
                (circle.centerX + circle.radius) * scaleX, (circle.centerY + circle.radius) * scaleY))
        }
        clipPath(path) { paint() }
    }
}
