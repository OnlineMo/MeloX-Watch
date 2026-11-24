package io.github.onlinemo.meloxwatch.data.fonts

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontVariationAxis
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import java.io.File

internal interface LyricsFontDecoder {
    fun validate(file: File, info: SfntFontInfo, checkCancellation: () -> Unit): FontWeightCapability
    fun decode(file: File, info: SfntFontInfo, capability: FontWeightCapability, requested: Float?): DecodedLyricsFont
}

internal data class DecodedLyricsFont(
    val typeface: Typeface,
    val nativeStyleWeight: Int?,
    val configuredAxisWeight: Float?,
)

/** Native work runs on the single provider/file worker, never inside composition. */
internal class AndroidLyricsFontDecoder : LyricsFontDecoder {
    override fun validate(file: File, info: SfntFontInfo, checkCancellation: () -> Unit): FontWeightCapability {
        checkCancellation()
        // A failed native build must throw; a builder fallback would disguise corrupt input.
        regular(file)
        val axis = info.weightAxis ?: return FontWeightCapability(FontWeightSupport.NO_AXIS, null)
        if (Build.VERSION.SDK_INT < 29) return FontWeightCapability(FontWeightSupport.PLATFORM_UNAVAILABLE, axis)
        val probe = info.weightProbeText(checkCancellation)
        if (probe.isEmpty() || axis.minimum == axis.maximum) {
            return FontWeightCapability(FontWeightSupport.NO_OBSERVED_CHANGE, axis)
        }
        checkCancellation()
        val minimum = variable(file, axis.minimum).typeface
        val maximum = variable(file, axis.maximum).typeface
        val observation = observeWeight(minimum, maximum, probe, checkCancellation)
        return FontWeightCapability(
            if (observation.changed) FontWeightSupport.AVAILABLE else FontWeightSupport.NO_OBSERVED_CHANGE,
            axis,
            observation,
        )
    }

    override fun decode(
        file: File,
        info: SfntFontInfo,
        capability: FontWeightCapability,
        requested: Float?,
    ): DecodedLyricsFont = if (capability.support == FontWeightSupport.AVAILABLE && Build.VERSION.SDK_INT >= 29) {
        variable(file, checkNotNull(info.weightAxis).clamp(requested))
    } else regular(file)

    private fun regular(file: File): DecodedLyricsFont = if (Build.VERSION.SDK_INT >= 29) {
        modern(file, null)
    } else {
        val typeface = Typeface.Builder(file).build() ?: fontFail(LyricsFontIssue.NATIVE_DECODE_FAILED)
        DecodedLyricsFont(typeface, if (Build.VERSION.SDK_INT >= 28) typeface.weight else null, null)
    }

    @RequiresApi(29)
    private fun variable(file: File, weight: Float): DecodedLyricsFont = modern(file, weight)

    @RequiresApi(29)
    private fun modern(file: File, weight: Float?): DecodedLyricsFont {
        val builder = Font.Builder(file)
        if (weight != null) builder.setFontVariationSettings(arrayOf(FontVariationAxis("wght", weight)))
        // setWeight is only style metadata. Font's own analysis supplies the actual family style.
        val font = builder.build()
        val family = FontFamily.Builder(font).build()
        val typeface = Typeface.CustomFallbackBuilder(family)
            .setStyle(font.style)
            .setSystemFallback("sans-serif")
            .build()
        return DecodedLyricsFont(typeface, font.style.weight, weight)
    }

    private fun observeWeight(
        minimum: Typeface,
        maximum: Typeface,
        text: String,
        checkCancellation: () -> Unit,
    ): FontWeightObservation {
        var first: Bitmap? = null
        var second: Bitmap? = null
        try {
            first = raster(minimum, text)
            checkCancellation()
            second = raster(maximum, text)
            val left = IntArray(PROBE_PIXELS)
            val right = IntArray(PROBE_PIXELS)
            first.getPixels(left, 0, PROBE_WIDTH, 0, 0, PROBE_WIDTH, PROBE_HEIGHT)
            second.getPixels(right, 0, PROBE_WIDTH, 0, 0, PROBE_WIDTH, PROBE_HEIGHT)
            var differences = 0
            var leftInk = 0
            var rightInk = 0
            for (index in left.indices) {
                if (index and 255 == 0) checkCancellation()
                if (left[index] != right[index]) differences++
                if (Color.alpha(left[index]) != 0) leftInk++
                if (Color.alpha(right[index]) != 0) rightInk++
            }
            checkCancellation()
            return FontWeightObservation(differences, leftInk, rightInk, text.codePointCount(0, text.length))
        } finally {
            first?.recycle()
            second?.recycle()
        }
    }

    private fun raster(typeface: Typeface, text: String): Bitmap {
        val bitmap = createBitmap(PROBE_WIDTH, PROBE_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.typeface = typeface
                textSize = 48f
                color = Color.WHITE
                isFakeBoldText = false
            }
            Canvas(bitmap).drawText(text, 8f, 68f, paint)
            return bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    companion object {
        private const val PROBE_WIDTH = 512
        private const val PROBE_HEIGHT = 96
        private const val PROBE_PIXELS = PROBE_WIDTH * PROBE_HEIGHT
    }
}
