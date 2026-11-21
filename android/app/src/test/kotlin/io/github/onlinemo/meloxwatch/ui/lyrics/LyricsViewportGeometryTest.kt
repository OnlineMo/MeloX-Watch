package io.github.onlinemo.meloxwatch.ui.lyrics

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsViewportGeometryTest {
    @Test
    fun representativeRoundWindowsKeepIndependentTouchTargetsAndTheReadingBandInsideThePhysicalCircle() {
        for (size in listOf(192f, 260f)) {
            val window = Window(size, size, round = true)
            val geometry = checkNotNull(layout(window))

            assertTrue(geometry.primaryToolbar != null && geometry.secondaryToolbar != null)
            assertTrue(geometry.lineWidthDp >= 96f - TOLERANCE)
            assertSafe(window, geometry)
        }
    }

    @Test
    fun rectangularWindowsCanUseTheirCornersAndKeepTheFullReadingColumn() {
        for (height in listOf(192f, 260f)) {
            val window = Window(192f, height, round = false)
            val geometry = checkNotNull(layout(window))

            assertTrue(geometry.lineWidthDp >= 160f)
            assertSafe(window, geometry)
        }
        val round = checkNotNull(layout(Window(192f, 192f, round = true)))
        val rectangular = checkNotNull(layout(Window(192f, 192f, round = false)))
        assertTrue(rectangular.lineWidthDp > round.lineWidthDp)
    }

    @Test
    fun systemInsetsPreserveThePhysicalPositionsOfTheUpperControls() {
        val full = checkNotNull(layout(Window(240f, 240f, round = true)))
        val insetWindow = Window(220f, 218f, 240f, 240f, 12f, 8f, round = true)
        val inset = checkNotNull(layout(insetWindow))

        val fullPrimary = checkNotNull(full.primaryToolbar)
        val insetPrimary = checkNotNull(inset.primaryToolbar)
        assertEquals(fullPrimary.leftDp, insetPrimary.leftDp + 12f, TOLERANCE)
        assertEquals(fullPrimary.topDp, insetPrimary.topDp + 8f, TOLERANCE)
        assertSafe(insetWindow, inset)
    }

    @Test
    fun imeAndTopOcclusionDoNotCreateASmallerPhysicalRoundScreen() {
        val full = checkNotNull(layout(Window(240f, 240f, round = true)))
        val imeWindow = Window(240f, 200f, 240f, 240f, round = true)
        val ime = checkNotNull(layout(imeWindow))

        assertEquals(checkNotNull(full.primaryToolbar).leftDp, checkNotNull(ime.primaryToolbar).leftDp, TOLERANCE)
        assertEquals(checkNotNull(full.primaryToolbar).topDp, checkNotNull(ime.primaryToolbar).topDp, TOLERANCE)
        assertTrue(ime.bodyHeightDp < full.bodyHeightDp)
        assertSafe(imeWindow, ime)
        val occludedWindow = Window(240f, 152f, 240f, 240f, topInset = 80f, round = true)
        assertSafe(occludedWindow, checkNotNull(layout(occludedWindow)))
    }

    @Test
    fun anchorAndMarginChoicesKeepACentralReadableBandRatherThanClippingItsCorners() {
        val window = Window(192f, 192f, round = true)
        for (anchor in listOf(0.25f, 0.5f, 0.75f)) {
            val options = LyricsDisplayOptions(anchorFraction = anchor, horizontalMarginDp = 24f)
            val geometry = checkNotNull(layout(window, options = options))

            assertTrue(geometry.lineWidthDp >= 96f - TOLERANCE)
            assertSafe(window, geometry)
        }
    }

    @Test
    fun pagesWithoutToolbarActionsAndTinyViewportsKeepTheScrollingPath() {
        val window = Window(192f, 192f, round = true)
        val noToolbar = checkNotNull(layout(window, toolbarControls = 0))
        assertNull(noToolbar.primaryToolbar)
        assertNull(noToolbar.secondaryToolbar)
        assertEquals(0f, noToolbar.bodyTopDp, TOLERANCE)
        assertEquals(window.height, noToolbar.bodyHeightDp, TOLERANCE)

        val tiny = checkNotNull(layout(Window(48f, 80f, round = false)))
        assertNull(tiny.primaryToolbar)
        assertNull(tiny.secondaryToolbar)
        assertTrue(tiny.bodyHeightDp > 0f && tiny.lineWidthDp > 0f)
    }

    @Test
    fun invalidInputsCannotProduceInvalidGeometryAndFiniteLargeInputsCannotOverflowSquaredDistances() {
        assertNull(layout(Window(Float.NaN, 192f, round = true)))
        assertNull(layout(Window(192f, Float.POSITIVE_INFINITY, round = true)))
        assertNull(layout(Window(192f, 0f, round = true)))
        assertNull(layout(Window(192f, 192f, leftInset = -1f, round = true)))
        assertNull(layout(Window(192f, 192f, round = true), toolbarControls = 3))

        val options = LyricsDisplayOptions(anchorFraction = Float.NaN, horizontalMarginDp = Float.POSITIVE_INFINITY)
        val normalWindow = Window(192f, 192f, round = true)
        assertSafe(normalWindow, checkNotNull(layout(normalWindow, options = options)))
        val large = checkNotNull(layout(Window(Float.MAX_VALUE, Float.MAX_VALUE, round = true)))
        assertTrue(listOf(large.bodyTopDp, large.bodyHeightDp, large.lineLeftDp, large.lineWidthDp,
            large.anchorDp, large.beforePaddingDp, large.afterPaddingDp).all { it.isFinite() })
        assertTrue(large.lineWidthDp > 0f)
        assertNull(large.secondaryToolbar)
    }

    @Test
    fun measuredLongBlocksStartAtTheVisibleTopAndShortBlocksRemainCentered() {
        val geometry = checkNotNull(layout(Window(192f, 192f, round = true)))
        val anchor = geometry.anchorDp
        val shortHeight = 40
        val shortOffset = lyricsAnchorScrollOffset(shortHeight, anchor)
        assertEquals(anchor, anchor - shortOffset + shortHeight / 2f, TOLERANCE)

        val longHeight = 320
        val longOffset = lyricsAnchorScrollOffset(longHeight, anchor)
        assertTrue(kotlin.math.abs(anchor - longOffset) <= 0.5f)
        assertTrue(longHeight > geometry.bodyHeightDp)
        assertEquals(0, lyricsAnchorScrollOffset(longHeight, Float.NaN))
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

    private fun layout(
        window: Window,
        toolbarControls: Int = 2,
        options: LyricsDisplayOptions = LyricsDisplayOptions(),
    ): LyricsViewportGeometry? = lyricsViewportGeometry(
        window.width, window.height, window.physicalWidth, window.physicalHeight,
        window.leftInset, window.topInset, window.round, toolbarControls, options,
    )

    // 断言实际触区和中央阅读带的边界，不复制布局选择算法；这些定义留给云端执行。
    private fun assertSafe(window: Window, geometry: LyricsViewportGeometry) {
        assertTrue(geometry.bodyTopDp >= -TOLERANCE)
        assertTrue(geometry.bodyHeightDp > 0f)
        assertTrue(geometry.bodyTopDp + geometry.bodyHeightDp <= window.height + TOLERANCE)
        assertTrue(geometry.beforePaddingDp >= 0f && geometry.afterPaddingDp >= 0f)
        assertTrue(geometry.lineLeftDp >= -TOLERANCE)
        assertTrue(geometry.lineWidthDp > 0f)
        assertTrue(geometry.lineLeftDp + geometry.lineWidthDp <= window.width + TOLERANCE)
        val centerX = window.physicalWidth / 2.0 - window.leftInset
        val centerY = window.physicalHeight / 2.0 - window.topInset
        val radius = minOf(window.physicalWidth, window.physicalHeight) / 2.0 - 8.0
        val slots = listOfNotNull(geometry.primaryToolbar, geometry.secondaryToolbar)
        for (slot in slots) {
            assertTrue(slot.leftDp >= -TOLERANCE && slot.leftDp + 48f <= window.width + TOLERANCE)
            assertTrue(slot.topDp >= -TOLERANCE && slot.topDp + 48f <= window.height + TOLERANCE)
            assertTrue(slot.topDp + 48f + 8f <= geometry.bodyTopDp + TOLERANCE)
            if (window.round) {
                assertTrue(hypot(slot.leftDp + 24.0 - centerX, slot.topDp + 24.0 - centerY) + 24.0 <= radius + TOLERANCE)
            }
        }
        if (slots.size == 2) {
            val first = slots[0]
            val second = slots[1]
            assertTrue(hypot(first.leftDp - second.leftDp, first.topDp - second.topDp) >= 54f - TOLERANCE)
        }
        val readingCenter = geometry.bodyTopDp + geometry.anchorDp
        val halfBand = minOf(24f, geometry.bodyHeightDp / 2f)
        assertTrue(readingCenter - halfBand >= -TOLERANCE)
        assertTrue(readingCenter + halfBand <= window.height + TOLERANCE)
        if (window.round) {
            for (x in listOf(geometry.lineLeftDp, geometry.lineLeftDp + geometry.lineWidthDp)) {
                for (y in listOf(readingCenter - halfBand, readingCenter + halfBand)) {
                    assertTrue(hypot(x.toDouble() - centerX, y.toDouble() - centerY) <= radius + TOLERANCE)
                }
            }
        }
    }

    companion object { private const val TOLERANCE = 0.002f }
}
