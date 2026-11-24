package io.github.onlinemo.meloxwatch.data.fonts

/**
 * An independent TTF/OTF directory, outline boundaries, fvar and Unicode cmap are checked before
 * native decoding. Collections/web fonts are not accepted by this declared import path.
 * The physical I/O worker supplies cancellation; no parser byte array escapes into UI state.
 */
internal class SfntFontParser {
    fun parse(bytes: ByteArray, checkCancellation: () -> Unit = {}): SfntFontInfo {
        if (bytes.size > LyricsFontLimits.FILE_BYTES) fontFail(LyricsFontIssue.TOO_LARGE)
        val input = FontBytes(bytes)
        input.requireSpan(0, 12)
        val signature = input.u32(0)
        if (signature != TRUE_TYPE && signature != OPEN_TYPE) fontFail(LyricsFontIssue.UNSUPPORTED_FORMAT)
        val count = input.u16(4)
        if (count !in 1..LyricsFontLimits.TABLES) fontFail(LyricsFontIssue.INVALID_FONT)
        val directoryEnd = 12 + count * 16
        input.requireSpan(0, directoryEnd)
        val power = Integer.highestOneBit(count)
        if (input.u16(6) != power * 16 || input.u16(8) != Integer.numberOfTrailingZeros(power) ||
            input.u16(10) != count * 16 - power * 16) fontFail(LyricsFontIssue.INVALID_FONT)
        val tables = LinkedHashMap<String, FontTable>(count)
        repeat(count) { index ->
            checkCancellation()
            val entry = 12 + index * 16
            val tag = input.tag(entry)
            val offset = input.boundedInt(entry + 8)
            val length = input.boundedInt(entry + 12)
            if (offset < directoryEnd || offset and 3 != 0) fontFail(LyricsFontIssue.INVALID_FONT)
            input.requireSpan(offset, length)
            if (tables.put(tag, FontTable(offset, length)) != null) fontFail(LyricsFontIssue.INVALID_FONT)
        }
        var previousEnd = directoryEnd
        tables.values.filter { it.length > 0 }.sortedBy { it.offset }.forEach { table ->
            if (table.offset < previousEnd) fontFail(LyricsFontIssue.INVALID_FONT)
            previousEnd = table.offset + table.length
        }
        val head = required(tables, "head", 54)
        if (input.u32(head.offset + 12) != HEAD_MAGIC || input.u16(head.offset + 18) !in 16..16384) {
            fontFail(LyricsFontIssue.INVALID_FONT)
        }
        val maximum = required(tables, "maxp", if (signature == TRUE_TYPE) 32 else 6)
        val maximumVersion = input.u32(maximum.offset)
        if (maximumVersion != MAXIMUM_10 && (signature == TRUE_TYPE || maximumVersion != MAXIMUM_05)) {
            fontFail(LyricsFontIssue.INVALID_FONT)
        }
        val glyphCount = input.u16(maximum.offset + 4)
        if (glyphCount == 0) fontFail(LyricsFontIssue.INVALID_FONT)
        val horizontal = required(tables, "hhea", 36)
        val metrics = input.u16(horizontal.offset + 34)
        if (metrics !in 1..glyphCount) fontFail(LyricsFontIssue.INVALID_FONT)
        required(tables, "hmtx", metrics * 4 + (glyphCount - metrics) * 2)
        val outline = if (signature == TRUE_TYPE) {
            trueType(input, tables, head, glyphCount, checkCancellation)
        } else cff(input, tables)
        val axis = tables["fvar"]?.let { weightAxis(input, it, checkCancellation) }
        val characters = characterMap(input, required(tables, "cmap", 4), glyphCount, checkCancellation)
        checkCancellation()
        return SfntFontInfo(outline, glyphCount, axis, characters)
    }

    private fun trueType(
        input: FontBytes,
        tables: Map<String, FontTable>,
        head: FontTable,
        glyphCount: Int,
        checkCancellation: () -> Unit,
    ): SfntOutline {
        val glyphs = required(tables, "glyf", 0)
        val format = input.s16(head.offset + 50)
        if (format !in 0..1) fontFail(LyricsFontIssue.INVALID_FONT)
        val stride = if (format == 0) 2 else 4
        val locations = required(tables, "loca", (glyphCount + 1) * stride)
        var previous = 0
        for (index in 0..glyphCount) {
            if (index and 127 == 0) checkCancellation()
            val offset = if (format == 0) input.u16(locations.offset + index * 2) * 2
            else input.boundedInt(locations.offset + index * 4)
            if (offset < previous || offset > glyphs.length || (offset > previous && offset - previous < 10)) {
                fontFail(LyricsFontIssue.INVALID_FONT)
            }
            previous = offset
        }
        return SfntOutline.TRUE_TYPE
    }

    private fun cff(input: FontBytes, tables: Map<String, FontTable>): SfntOutline {
        val classic = tables["CFF "]
        val modern = tables["CFF2"]
        if ((classic == null) == (modern == null)) fontFail(LyricsFontIssue.INVALID_FONT)
        if (classic != null) {
            classic.requireLength(4)
            if (input.u8(classic.offset) != 1 || input.u8(classic.offset + 2) !in 4..classic.length ||
                input.u8(classic.offset + 3) !in 1..4) fontFail(LyricsFontIssue.INVALID_FONT)
            return SfntOutline.CFF
        }
        val table = checkNotNull(modern)
        table.requireLength(5)
        val header = input.u8(table.offset + 2)
        if (input.u8(table.offset) != 2 || header !in 5..table.length ||
            input.u16(table.offset + 3) > table.length - header) fontFail(LyricsFontIssue.INVALID_FONT)
        return SfntOutline.CFF2
    }

    private fun weightAxis(input: FontBytes, table: FontTable, checkCancellation: () -> Unit): FontWeightAxis? {
        table.requireLength(16)
        if (input.u32(table.offset) != MAXIMUM_10 || input.u16(table.offset + 6) != 2) {
            fontFail(LyricsFontIssue.INVALID_FONT)
        }
        val axisOffset = input.u16(table.offset + 4)
        val axisCount = input.u16(table.offset + 8)
        val axisSize = input.u16(table.offset + 10)
        val instanceCount = input.u16(table.offset + 12)
        val instanceSize = input.u16(table.offset + 14)
        if (axisOffset < 16 || axisCount !in 1..LyricsFontLimits.AXES || axisSize != 20 ||
            instanceSize !in setOf(4 + axisCount * 4, 6 + axisCount * 4)) fontFail(LyricsFontIssue.INVALID_FONT)
        table.requireRelativeSpan(axisOffset, axisCount * axisSize)
        val instanceOffset = axisOffset + axisCount * axisSize
        table.requireRelativeSpan(instanceOffset, instanceCount * instanceSize)
        val names = HashSet<String>(axisCount)
        val minima = FloatArray(axisCount)
        val maxima = FloatArray(axisCount)
        var weight: FontWeightAxis? = null
        repeat(axisCount) { index ->
            checkCancellation()
            val offset = table.offset + axisOffset + index * axisSize
            val tag = input.tag(offset)
            val minimum = input.fixed(offset + 4)
            val default = input.fixed(offset + 8)
            val maximum = input.fixed(offset + 12)
            if (!names.add(tag) || minimum > default || default > maximum || input.u16(offset + 16) and 0xFFFE != 0) {
                fontFail(LyricsFontIssue.INVALID_FONT)
            }
            minima[index] = minimum
            maxima[index] = maximum
            if (tag == "wght") weight = FontWeightAxis(minimum, default, maximum)
        }
        repeat(instanceCount) { instance ->
            checkCancellation()
            val offset = table.offset + instanceOffset + instance * instanceSize
            if (input.u16(offset + 2) != 0) fontFail(LyricsFontIssue.INVALID_FONT)
            repeat(axisCount) { axis ->
                val value = input.fixed(offset + 4 + axis * 4)
                if (value < minima[axis] || value > maxima[axis]) fontFail(LyricsFontIssue.INVALID_FONT)
            }
        }
        return weight
    }

    private fun characterMap(
        input: FontBytes,
        table: FontTable,
        glyphCount: Int,
        checkCancellation: () -> Unit,
    ): FontCharacterMap {
        if (input.u16(table.offset) != 0) fontFail(LyricsFontIssue.INVALID_FONT)
        val count = input.u16(table.offset + 2)
        if (count !in 1..LyricsFontLimits.CMAP_RECORDS) fontFail(LyricsFontIssue.INVALID_FONT)
        val header = 4 + count * 8
        table.requireLength(header)
        var chosen: FontTable? = null
        var chosenFormat = 0
        var chosenScore = -1
        repeat(count) { index ->
            checkCancellation()
            val record = table.offset + 4 + index * 8
            val platform = input.u16(record)
            val encoding = input.u16(record + 2)
            val relative = input.boundedInt(record + 4)
            if (relative < header) fontFail(LyricsFontIssue.INVALID_FONT)
            table.requireRelativeSpan(relative, 2)
            val offset = table.offset + relative
            val format = input.u16(offset)
            val length = when (format) {
                0, 2, 4, 6 -> {
                    table.requireRelativeSpan(relative, 4)
                    input.u16(offset + 2)
                }
                8, 10, 12, 13 -> {
                    table.requireRelativeSpan(relative, 8)
                    input.boundedInt(offset + 4)
                }
                14 -> {
                    table.requireRelativeSpan(relative, 6)
                    input.boundedInt(offset + 2)
                }
                else -> fontFail(LyricsFontIssue.UNSUPPORTED_FORMAT)
            }
            if (length < 4) fontFail(LyricsFontIssue.INVALID_FONT)
            table.requireRelativeSpan(relative, length)
            val unicode = platform == 0 || platform == 3 && encoding in setOf(1, 10)
            val score = when {
                !unicode -> -1
                format == 12 && platform == 3 && encoding == 10 -> 120
                format == 12 -> 110
                format == 4 && platform == 3 && encoding == 1 -> 100
                format == 4 -> 90
                else -> -1
            }
            if (score > chosenScore) {
                chosen = FontTable(offset, length)
                chosenFormat = format
                chosenScore = score
            }
        }
        val selected = chosen ?: fontFail(LyricsFontIssue.UNSUPPORTED_FORMAT)
        return when (chosenFormat) {
            4 -> bmpMap(input, selected, glyphCount, checkCancellation)
            12 -> fullMap(input, selected, glyphCount, checkCancellation)
            else -> fontFail(LyricsFontIssue.UNSUPPORTED_FORMAT)
        }
    }

    private fun bmpMap(
        input: FontBytes,
        table: FontTable,
        glyphCount: Int,
        checkCancellation: () -> Unit,
    ): FontCharacterMap {
        table.requireLength(16)
        val twice = input.u16(table.offset + 6)
        val count = twice / 2
        if (twice and 1 != 0 || count !in 1..LyricsFontLimits.CMAP_SEGMENTS) fontFail(LyricsFontIssue.INVALID_FONT)
        table.requireLength(16 + count * 8)
        val endOffset = table.offset + 14
        val startOffset = endOffset + count * 2 + 2
        val deltaOffset = startOffset + count * 2
        val rangeOffset = deltaOffset + count * 2
        val glyphArray = rangeOffset + count * 2
        if (input.u16(endOffset + count * 2) != 0 || input.u16(endOffset + (count - 1) * 2) != 0xFFFF) {
            fontFail(LyricsFontIssue.INVALID_FONT)
        }
        val present = LongArray(1024)
        var previous = -1
        repeat(count) { index ->
            checkCancellation()
            val start = input.u16(startOffset + index * 2)
            val end = input.u16(endOffset + index * 2)
            val delta = input.s16(deltaOffset + index * 2)
            val rangeWord = rangeOffset + index * 2
            val range = input.u16(rangeWord)
            if (start > end || start <= previous || range and 1 != 0) fontFail(LyricsFontIssue.INVALID_FONT)
            if (range != 0 && (rangeWord + range < glyphArray ||
                    rangeWord + range + (end - start) * 2 + 2 > table.offset + table.length)) {
                fontFail(LyricsFontIssue.INVALID_FONT)
            }
            for (codePoint in start..end) {
                if (codePoint and 255 == 0) checkCancellation()
                val glyph = if (range == 0) (codePoint + delta) and 0xFFFF else {
                    val value = input.u16(rangeWord + range + (codePoint - start) * 2)
                    if (value == 0) 0 else (value + delta) and 0xFFFF
                }
                if (glyph >= glyphCount) fontFail(LyricsFontIssue.INVALID_FONT)
                if (glyph != 0 && codePoint !in 0xD800..0xDFFF) {
                    present[codePoint ushr 6] = present[codePoint ushr 6] or (1L shl (codePoint and 63))
                }
            }
            previous = end
        }
        return BmpFontCharacterMap(present)
    }

    private fun fullMap(
        input: FontBytes,
        table: FontTable,
        glyphCount: Int,
        checkCancellation: () -> Unit,
    ): FontCharacterMap {
        table.requireLength(16)
        if (input.u16(table.offset + 2) != 0) fontFail(LyricsFontIssue.INVALID_FONT)
        val count = input.boundedInt(table.offset + 12)
        if (count !in 1..LyricsFontLimits.CMAP_GROUPS) fontFail(LyricsFontIssue.INVALID_FONT)
        table.requireLength(16 + count * 12)
        val starts = IntArray(count)
        val ends = IntArray(count)
        var previous = -1
        var retained = 0
        repeat(count) { index ->
            checkCancellation()
            val offset = table.offset + 16 + index * 12
            val start = input.boundedInt(offset)
            val end = input.boundedInt(offset + 4)
            val glyph = input.u32(offset + 8)
            if (start > end || start <= previous || end > 0x10FFFF ||
                start <= 0xDFFF && end >= 0xD800 || glyph + (end - start) >= glyphCount.toLong()) {
                fontFail(LyricsFontIssue.INVALID_FONT)
            }
            val first = if (glyph == 0L) start + 1 else start
            if (first <= end) {
                starts[retained] = first
                ends[retained++] = end
            }
            previous = end
        }
        return FullFontCharacterMap(starts.copyOf(retained), ends.copyOf(retained))
    }

    private fun required(tables: Map<String, FontTable>, tag: String, minimum: Int): FontTable =
        (tables[tag] ?: fontFail(LyricsFontIssue.INVALID_FONT)).also { it.requireLength(minimum) }

    private data class FontTable(val offset: Int, val length: Int) {
        fun requireLength(minimum: Int) { if (length < minimum) fontFail(LyricsFontIssue.INVALID_FONT) }
        fun requireRelativeSpan(relative: Int, count: Int) {
            if (relative < 0 || count < 0 || relative > length || count > length - relative) {
                fontFail(LyricsFontIssue.INVALID_FONT)
            }
        }
    }

    private class FontBytes(private val bytes: ByteArray) {
        fun requireSpan(offset: Int, length: Int) {
            if (offset < 0 || length < 0 || offset > bytes.size || length > bytes.size - offset) {
                fontFail(LyricsFontIssue.INVALID_FONT)
            }
        }

        fun u8(offset: Int): Int { requireSpan(offset, 1); return bytes[offset].toInt() and 0xFF }
        fun u16(offset: Int): Int { requireSpan(offset, 2); return (u8(offset) shl 8) or u8(offset + 1) }
        fun s16(offset: Int): Int = u16(offset).toShort().toInt()
        fun u32(offset: Int): Long = (u16(offset).toLong() shl 16) or u16(offset + 2).toLong()
        fun boundedInt(offset: Int): Int = u32(offset).let {
            if (it > Int.MAX_VALUE) fontFail(LyricsFontIssue.INVALID_FONT)
            it.toInt()
        }

        fun fixed(offset: Int): Float = u32(offset).toInt().toFloat() / 65536f
        fun tag(offset: Int): String {
            requireSpan(offset, 4)
            val chars = CharArray(4) { index ->
                val value = u8(offset + index)
                if (value !in 0x20..0x7E) fontFail(LyricsFontIssue.INVALID_FONT)
                value.toChar()
            }
            return String(chars)
        }
    }

    companion object {
        private const val TRUE_TYPE = 0x00010000L
        private const val OPEN_TYPE = 0x4F54544FL
        private const val HEAD_MAGIC = 0x5F0F3CF5L
        private const val MAXIMUM_10 = 0x00010000L
        private const val MAXIMUM_05 = 0x00005000L
    }
}
