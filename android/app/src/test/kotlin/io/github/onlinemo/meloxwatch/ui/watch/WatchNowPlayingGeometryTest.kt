package io.github.onlinemo.meloxwatch.ui.watch

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchNowPlayingGeometryTest {
    @Test
    fun normalRoundViewportKeepsArtworkAndTheMenuInTheUpperLeft() {
        val window = Window(240f, 240f, round = true)
        val geometry = checkNotNull(layout(window))
        val artwork = checkNotNull(geometry.artwork)

        assertTrue(artwork.size >= 48f)
        assertTrue(geometry.menuLeft + 24f < geometry.centerX)
        assertTrue(geometry.menuTop + 24f < 120f)
        assertNotNull(geometry.artist)
        assertSafe(window, geometry)
    }

    @Test
    fun narrowRoundViewportKeepsAllThreeControlsAndMovesTheTitleBelowThem() {
        val window = Window(192f, 192f, round = true)
        val geometry = checkNotNull(layout(window))

        assertTrue(geometry.title.top >= geometry.transportTop + geometry.centerSize + 6f - TOLERANCE)
        assertNull(geometry.artwork)
        assertSafe(window, geometry)
    }

    @Test
    fun largeMeasuredTextSelectsTheScrollingPathWhenItCannotFit() {
        assertNull(layout(Window(192f, 192f, round = true), titleHeight = 48f, artistHeight = 40f))
        assertNull(layout(Window(240f, 240f, round = true), titleHeight = 128f, artistHeight = 80f))
    }

    @Test
    fun currentGlyphHeightsAreReservedInsteadOfReplacingThemWithDefaultValues() {
        val window = Window(240f, 240f, round = true)
        val geometry = checkNotNull(layout(window, titleHeight = 31f, artistHeight = 23f))

        assertEquals(31f, geometry.title.height, TOLERANCE)
        geometry.artist?.let { assertEquals(23f, it.height, TOLERANCE) }
        val lastTextBeforeControls = geometry.artist?.takeIf { it.top < geometry.transportTop } ?: geometry.title
        assertEquals(geometry.transportTop - 6f, lastTextBeforeControls.top + lastTextBeforeControls.height, TOLERANCE)
        assertNotNull(geometry.artwork)
        assertSafe(window, geometry)
    }

    @Test
    fun squareAndRectangularWindowsKeepTheirCornersAvailable() {
        for (height in listOf(192f, 260f)) {
            val window = Window(192f, height, round = false)
            val geometry = checkNotNull(layout(window))

            assertEquals(12f, geometry.menuLeft, TOLERANCE)
            assertEquals(12f, geometry.menuTop, TOLERANCE)
            assertNotNull(geometry.artwork)
            assertSafe(window, geometry)
        }
    }

    @Test
    fun systemInsetsDoNotMoveThePhysicalRoundCenterOrItsUpperControls() {
        val fullWindow = Window(240f, 240f, round = true)
        val insetWindow = Window(220f, 218f, 240f, 240f, 12f, 8f, round = true)
        val full = checkNotNull(layout(fullWindow))
        val inset = checkNotNull(layout(insetWindow))

        assertEquals(full.centerX, inset.centerX + 12f, TOLERANCE)
        assertEquals(full.menuLeft, inset.menuLeft + 12f, TOLERANCE)
        assertEquals(full.menuTop, inset.menuTop + 8f, TOLERANCE)
        assertEquals(full.transportTop, inset.transportTop + 8f, TOLERANCE)
        assertNotNull(inset.artwork)
        assertSafe(insetWindow, inset)
    }

    @Test
    fun imeResizesTheViewportWithoutCreatingASmallerRoundScreen() {
        val normal = checkNotNull(layout(Window(240f, 240f, round = true)))
        val resizedWindow = Window(240f, 210f, 240f, 240f, round = true)
        val resized = checkNotNull(layout(resizedWindow))

        assertEquals(normal.menuLeft, resized.menuLeft, TOLERANCE)
        assertEquals(normal.menuTop, resized.menuTop, TOLERANCE)
        assertTrue(resized.transportTop < normal.transportTop)
        assertSafe(resizedWindow, resized)
        assertNull(layout(Window(240f, 116f, 240f, 240f, round = true)))
    }

    @Test
    fun changedWindowsAndFontHeightsKeepEveryRenderedRegionInsideItsBounds() {
        val windows = listOf(
            Window(192f, 192f, round = true),
            Window(220f, 220f, round = true),
            Window(240f, 240f, round = true),
            Window(260f, 260f, round = true),
            Window(192f, 192f, round = false),
            Window(192f, 260f, round = false),
            Window(220f, 218f, 240f, 240f, 12f, 8f, round = true),
            Window(240f, 200f, 240f, 240f, round = true),
            Window(240f, 152f, 240f, 240f, 0f, 80f, round = true),
        )
        for (window in windows) {
            for ((titleHeight, artistHeight) in listOf(24f to 20f, 31f to 23f, 48f to 40f, 24f to null)) {
                layout(window, titleHeight, artistHeight)?.let { assertSafe(window, it) }
            }
        }
    }

    @Test
    fun invalidAndTooSmallViewportsCannotProduceShrunkenOrInvalidTargets() {
        assertNull(layout(Window(167f, 240f, round = false)))
        assertNull(layout(Window(240f, 47f, round = false)))
        assertNull(layout(Window(Float.NaN, 240f, round = true)))
        assertNull(layout(Window(240f, Float.POSITIVE_INFINITY, round = true)))
        assertNull(layout(Window(240f, 240f, round = true), titleHeight = Float.NaN))
    }

    private data class Window(
        val width: Float,
        val height: Float,
        val physicalWidth: Float = width,
        val physicalHeight: Float = height,
        val leftInset: Float = 0f,
        val topInset: Float = 0f,
        val round: Boolean,
    )

    private data class Circle(val x: Float, val y: Float, val diameter: Float)
    private data class Rectangle(val left: Float, val top: Float, val width: Float, val height: Float)

    private fun layout(window: Window, titleHeight: Float = 24f, artistHeight: Float? = 20f): NowPlayingGeometry? =
        nowPlayingGeometry(window.width, window.height, window.physicalWidth, window.physicalHeight,
            window.leftInset, window.topInset, window.round, titleHeight, artistHeight)

    // 只断言可观察的触区和文字边界，不复制布局选择算法；这些定义仅在云端执行。
    private fun assertSafe(window: Window, geometry: NowPlayingGeometry) {
        val centerY = geometry.transportTop + geometry.centerSize / 2f
        val rowLeft = geometry.centerX - geometry.transportWidth / 2f
        val circles = listOf(
            Circle(geometry.menuLeft + 24f, geometry.menuTop + 24f, 48f),
            Circle(rowLeft + 24f, centerY, 48f),
            Circle(geometry.centerX, centerY, geometry.centerSize),
            Circle(rowLeft + geometry.transportWidth - 24f, centerY, 48f),
        )
        val physicalCenterX = window.physicalWidth / 2f - window.leftInset
        val physicalCenterY = window.physicalHeight / 2f - window.topInset
        val radius = minOf(window.physicalWidth, window.physicalHeight) / 2f - 8f
        assertTrue(geometry.centerSize >= 56f)
        for (circle in circles) {
            val half = circle.diameter / 2f
            assertTrue(circle.diameter >= 48f)
            assertTrue(circle.x - half >= -TOLERANCE && circle.x + half <= window.width + TOLERANCE)
            assertTrue(circle.y - half >= -TOLERANCE && circle.y + half <= window.height + TOLERANCE)
            if (window.round) {
                assertTrue(hypot(circle.x - physicalCenterX, circle.y - physicalCenterY) + half <= radius + TOLERANCE)
            }
        }
        for (first in circles.indices) {
            for (second in first + 1 until circles.size) {
                val a = circles[first]
                val b = circles[second]
                assertTrue(hypot(a.x - b.x, a.y - b.y) >= (a.diameter + b.diameter) / 2f + 6f - TOLERANCE)
            }
        }

        fun textRectangle(text: HeroText) = Rectangle(geometry.centerX - text.width / 2f, text.top, text.width, text.height)
        val rectangles = listOfNotNull(textRectangle(geometry.title), geometry.artist?.let(::textRectangle),
            geometry.artwork?.let { Rectangle(geometry.centerX - it.size / 2f, it.top, it.size, it.size) })
        for (rectangle in rectangles) {
            assertTrue(rectangle.width >= 48f && rectangle.height > 0f)
            assertTrue(rectangle.left >= -TOLERANCE && rectangle.left + rectangle.width <= window.width + TOLERANCE)
            assertTrue(rectangle.top >= -TOLERANCE && rectangle.top + rectangle.height <= window.height + TOLERANCE)
            if (window.round) {
                for (x in listOf(rectangle.left, rectangle.left + rectangle.width)) {
                    for (y in listOf(rectangle.top, rectangle.top + rectangle.height)) {
                        assertTrue(hypot(x - physicalCenterX, y - physicalCenterY) <= radius + TOLERANCE)
                    }
                }
            }
            for (circle in circles) {
                val nearestX = circle.x.coerceIn(rectangle.left, rectangle.left + rectangle.width)
                val nearestY = circle.y.coerceIn(rectangle.top, rectangle.top + rectangle.height)
                assertTrue(hypot(circle.x - nearestX, circle.y - nearestY) >= circle.diameter / 2f + 6f - TOLERANCE)
            }
        }
        geometry.artist?.let { assertTrue(it.top >= geometry.title.top + geometry.title.height + 2f - TOLERANCE) }
        geometry.artwork?.let { assertTrue(it.top + it.size + 6f <= geometry.title.top + TOLERANCE) }
    }

    companion object { private const val TOLERANCE = 0.002f }
}
