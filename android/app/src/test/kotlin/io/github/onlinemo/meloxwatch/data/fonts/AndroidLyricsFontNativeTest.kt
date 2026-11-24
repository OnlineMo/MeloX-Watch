package io.github.onlinemo.meloxwatch.data.fonts

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontTestFixtures as Fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.nativeruntime.DefaultNativeRuntimeLoader

/** Real unmodified font bytes and native pixels, never getAxes/getWeight request echoes as proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidLyricsFontNativeTest {
    @get:Rule val temporary = TemporaryFolder()
    private val decoder = AndroidLyricsFontDecoder()

    @Before fun initializeNativeFonts() = initializeNativeLyricsFonts()

    @Test fun realVariableFontRendersThreeDistinctWeightsWithinItsActualAxisRange() {
        val file = Fixture.file(temporary.root)
        val info = SfntFontParser().parse(file.readBytes())
        val capability = decoder.validate(file, info, {})
        assertEquals(FontWeightSupport.AVAILABLE, capability.support)
        assertTrue(checkNotNull(capability.observation).changed)
        val minimum = decoder.decode(file, info, capability, 100f)
        val regular = decoder.decode(file, info, capability, 400f)
        val maximum = decoder.decode(file, info, capability, 700f)
        val lowPixels = pixels(minimum.typeface)
        val middlePixels = pixels(regular.typeface)
        val highPixels = pixels(maximum.typeface)
        assertFalse(lowPixels.contentEquals(middlePixels))
        assertFalse(middlePixels.contentEquals(highPixels))
        assertFalse(lowPixels.contentEquals(highPixels))
        assertTrue(lowPixels.any { Color.alpha(it) > 0 })
        assertTrue(highPixels.any { Color.alpha(it) > 0 })
    }

    @Test fun outOfRangeRequestsClampToRealEndpointGlyphsAndDefaultUsesRealDefault() {
        val file = Fixture.file(temporary.root)
        val info = SfntFontParser().parse(file.readBytes())
        val capability = decoder.validate(file, info, {})
        val below = decoder.decode(file, info, capability, -500f)
        val above = decoder.decode(file, info, capability, 5000f)
        val default = decoder.decode(file, info, capability, null)
        assertEquals(100f, below.configuredAxisWeight)
        assertEquals(700f, above.configuredAxisWeight)
        assertEquals(400f, default.configuredAxisWeight)
        assertTrue(pixels(below.typeface).contentEquals(pixels(decoder.decode(file, info, capability, 100f).typeface)))
        assertTrue(pixels(above.typeface).contentEquals(pixels(decoder.decode(file, info, capability, 700f).typeface)))
        assertTrue(pixels(default.typeface).contentEquals(pixels(decoder.decode(file, info, capability, 400f).typeface)))
    }

    @Test fun realStaticOtfDecodesRegularWithoutSynthesizingThePreviousVariableWeight() {
        val file = Fixture.file(temporary.root, Fixture.STATIC)
        val info = SfntFontParser().parse(file.readBytes())
        val capability = decoder.validate(file, info, {})
        assertEquals(FontWeightSupport.NO_AXIS, capability.support)
        assertNull(capability.axis)
        val requested = decoder.decode(file, info, capability, 700f)
        val regular = decoder.decode(file, info, capability, null)
        assertNull(requested.configuredAxisWeight)
        assertTrue(pixels(requested.typeface).contentEquals(pixels(regular.typeface)))
        assertTrue(pixels(regular.typeface).any { Color.alpha(it) > 0 })
    }

    @Test fun nativeDecoderRejectsBadOriginalBytesInsteadOfReturningAHiddenSystemFallback() {
        val file = Fixture.file(temporary.root, Fixture.STATIC)
        val info = SfntFontParser().parse(file.readBytes())
        file.writeBytes(ByteArray(32))
        try {
            decoder.validate(file, info, {})
            throw AssertionError("Expected native font rejection")
        } catch (error: Exception) { assertNotNull(error) }
    }

    private fun pixels(typeface: Typeface): IntArray = nativeFontPixels(typeface)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidLyricsFontStaticApi26Test {
    @get:Rule val temporary = TemporaryFolder()

    @Before fun initializeNativeFonts() = initializeNativeLyricsFonts()

    @Test fun api26BuildsTheRealStaticTtfAndOtfNormalPaths() {
        val decoder = AndroidLyricsFontDecoder()
        for (name in listOf(Fixture.VARIABLE, Fixture.STATIC)) {
            val file = Fixture.file(temporary.root, name)
            val info = SfntFontParser().parse(file.readBytes())
            val capability = decoder.validate(file, info, {})
            val face = decoder.decode(file, info, capability, null)
            assertNotNull(face.typeface)
            assertTrue(nativeFontPixels(face.typeface).any { Color.alpha(it) > 0 })
            assertNull(face.configuredAxisWeight)
        }
    }

    @Test fun api26VariableWeightReportsItsPlatformLimitAndKeepsRegularGlyphs() {
        val file = Fixture.file(temporary.root)
        val info = SfntFontParser().parse(file.readBytes())
        val decoder = AndroidLyricsFontDecoder()
        val capability = decoder.validate(file, info, {})
        assertEquals(FontWeightSupport.PLATFORM_UNAVAILABLE, capability.support)
        assertEquals(FontWeightAxis(100f, 400f, 700f), capability.axis)
        assertNull(capability.observation)
        val requested = decoder.decode(file, info, capability, 700f)
        val regular = decoder.decode(file, info, capability, null)
        assertNull(requested.configuredAxisWeight)
        assertTrue(nativeFontPixels(requested.typeface).contentEquals(nativeFontPixels(regular.typeface)))
    }
}

/** Load outside font builders so fallback construction cannot reenter an unfinished native registry. */
internal fun initializeNativeLyricsFonts() {
    DefaultNativeRuntimeLoader.injectAndLoad()
    Paint()
    assertNotNull(Typeface.DEFAULT)
}

internal fun nativeFontPixels(typeface: Typeface): IntArray {
    val bitmap = Bitmap.createBitmap(256, 96, Bitmap.Config.ARGB_8888)
    try {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = 54f
            color = Color.WHITE
            isFakeBoldText = false
        }
        Canvas(bitmap).drawText("HMa08", 4f, 72f, paint)
        return IntArray(256 * 96).also { bitmap.getPixels(it, 0, 256, 0, 0, 256, 96) }
    } finally { bitmap.recycle() }
}
