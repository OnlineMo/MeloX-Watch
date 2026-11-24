package io.github.onlinemo.meloxwatch.data.fonts

enum class SfntOutline { TRUE_TYPE, CFF, CFF2 }

/** Immutable, bounded cmap representation; the original font byte array is never retained. */
class SfntFontInfo internal constructor(
    val outline: SfntOutline,
    val glyphCount: Int,
    val weightAxis: FontWeightAxis?,
    private val characters: FontCharacterMap,
) {
    fun hasGlyph(codePoint: Int): Boolean = characters.contains(codePoint)

    internal fun checkText(text: String, checkCancellation: () -> Unit): FontTextCoverage {
        if (text.length > LyricsFontLimits.TEXT_CHARACTERS) fontFail(LyricsFontIssue.INVALID_INPUT)
        var offset = 0
        var checked = 0
        var missing = 0
        var firstMissing: Int? = null
        while (offset < text.length) {
            if (checked++ and 255 == 0) checkCancellation()
            val codePoint = Character.codePointAt(text, offset)
            val ignored = Character.isWhitespace(codePoint) || Character.isISOControl(codePoint) ||
                Character.getType(codePoint) == Character.FORMAT.toInt()
            if (!ignored && !hasGlyph(codePoint)) {
                missing++
                if (firstMissing == null) firstMissing = codePoint
            }
            offset += Character.charCount(codePoint)
        }
        checkCancellation()
        return FontTextCoverage(missing, firstMissing)
    }

    internal fun weightProbeText(checkCancellation: () -> Unit): String {
        val result = StringBuilder()
        var count = 0
        for (codePoint in PROBE_CODE_POINTS) {
            checkCancellation()
            if (hasGlyph(codePoint)) {
                result.appendCodePoint(codePoint)
                if (++count == PROBE_GLYPHS) return result.toString()
            }
        }
        // A non-Latin face still gets a bounded probe from its actual Unicode cmap.
        characters.firstPrintable(PROBE_GLYPHS, checkCancellation).forEach { codePoint ->
            if (count < PROBE_GLYPHS && PROBE_CODE_POINTS.none { it == codePoint }) {
                result.appendCodePoint(codePoint)
                count++
            }
        }
        return result.toString()
    }

    companion object {
        private const val PROBE_GLYPHS = 8
        private val PROBE_CODE_POINTS = intArrayOf(
            'H'.code, 'M'.code, 'a'.code, 'e'.code, 's'.code, '0'.code, '8'.code, 'W'.code,
            0x6C38, 0x062F, 0x092E,
        )
    }
}

internal data class FontTextCoverage(val missing: Int, val firstMissing: Int?)

internal interface FontCharacterMap {
    fun contains(codePoint: Int): Boolean
    fun firstPrintable(limit: Int, checkCancellation: () -> Unit): IntArray
}

internal class BmpFontCharacterMap(private val present: LongArray) : FontCharacterMap {
    override fun contains(codePoint: Int): Boolean = codePoint in 0..0xFFFF &&
        present[codePoint ushr 6] and (1L shl (codePoint and 63)) != 0L

    override fun firstPrintable(limit: Int, checkCancellation: () -> Unit): IntArray {
        val result = ArrayList<Int>(limit)
        for (codePoint in 0x21..0xFFFF) {
            if (codePoint and 255 == 0) checkCancellation()
            if (contains(codePoint) && fontPrintable(codePoint)) result += codePoint
            if (result.size == limit) break
        }
        return result.toIntArray()
    }
}

internal class FullFontCharacterMap(private val starts: IntArray, private val ends: IntArray) : FontCharacterMap {
    override fun contains(codePoint: Int): Boolean {
        if (codePoint !in 0..0x10FFFF || codePoint in 0xD800..0xDFFF) return false
        var low = 0
        var high = starts.lastIndex
        while (low <= high) {
            val middle = (low + high) ushr 1
            when {
                codePoint < starts[middle] -> high = middle - 1
                codePoint > ends[middle] -> low = middle + 1
                else -> return true
            }
        }
        return false
    }

    override fun firstPrintable(limit: Int, checkCancellation: () -> Unit): IntArray {
        val result = ArrayList<Int>(limit)
        for (index in starts.indices) {
            checkCancellation()
            var codePoint = maxOf(starts[index], 0x21)
            while (codePoint <= ends[index] && result.size < limit) {
                if (codePoint and 255 == 0) checkCancellation()
                if (fontPrintable(codePoint)) result += codePoint
                codePoint++
            }
            if (result.size == limit) break
        }
        return result.toIntArray()
    }
}

private fun fontPrintable(codePoint: Int): Boolean = codePoint !in 0xD800..0xDFFF &&
    !Character.isWhitespace(codePoint) && !Character.isISOControl(codePoint) &&
    Character.getType(codePoint) != Character.FORMAT.toInt()
