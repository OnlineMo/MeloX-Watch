package io.github.onlinemo.meloxwatch.data.codec

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** Footer-only APEv2 lookup keeps bounded labels and literal ReplayGain fields for LP-06 tag wiring. */
internal object ApeTagFields {
    private val wanted = setOf("TITLE", "ARTIST", "ALBUM", "ALBUM ARTIST", "ALBUMARTIST", "TRACK", "DISC",
        "COMPILATION", "REPLAYGAIN_TRACK_GAIN", "REPLAYGAIN_ALBUM_GAIN", "REPLAYGAIN_TRACK_PEAK", "REPLAYGAIN_ALBUM_PEAK")

    fun read(input: CodecInput, minimumOffset: Long = 0): Map<String, String> {
        input.checkCancellation()
        codecRequire(input.length > 0, CodecReadIssue.UNKNOWN_LENGTH)
        codecRequire(minimumOffset in 0..input.length)
        if (input.length - minimumOffset < 32) return emptyMap()
        var end = input.length
        if (end - minimumOffset >= 128) {
            input.moveTo(end - 128)
            if (String(input.fixed(3), Charsets.US_ASCII) == "TAG") end -= 128
        }
        if (end - minimumOffset < 32) return emptyMap()
        input.moveTo(end - 32)
        val footer = input.fixed(32)
        if (String(footer, 0, 8, Charsets.US_ASCII) != "APETAGEX") return emptyMap()
        codecRequire(footer.u32(8, true) == 2000L)
        val size = footer.u32(12, true)
        val fields = footer.u32(16, true)
        val flags = footer.u32(20, true)
        codecRequire(size in 32L..1024 * 1024L && fields <= 256, CodecReadIssue.RESOURCE_LIMIT)
        codecRequire(flags and 0x2000_0000L == 0L && footer.sliceArray(24..31).all { it.toInt() == 0 })
        val start = end - size
        codecRequire(start >= minimumOffset)
        if (flags and 0x8000_0000L != 0L) {
            codecRequire(start - minimumOffset >= 32)
            input.moveTo(start - 32)
            val header = input.fixed(32)
            codecRequire(String(header, 0, 8, Charsets.US_ASCII) == "APETAGEX" &&
                header.u32(8, true) == 2000L && header.u32(12, true) == size && header.u32(16, true) == fields)
        }
        input.moveTo(start)
        val result = LinkedHashMap<String, String>()
        val one = ByteArray(1)
        repeat(fields.toInt()) {
            codecRequire(end - 32 - input.position >= 9)
            val field = input.fixed(8)
            val valueBytes = field.u32(0, true)
            val fieldFlags = field.u32(4, true)
            val name = StringBuilder()
            while (true) {
                codecRequire(input.position < end - 32 && name.length <= 128)
                input.readFully(one)
                val value = one[0].toInt() and 255
                if (value == 0) break
                codecRequire(value in 32..126)
                name.append(value.toChar())
            }
            codecRequire(name.isNotEmpty() && valueBytes <= end - 32 - input.position)
            val key = name.toString().uppercase(Locale.ROOT)
            if (key in wanted && fieldFlags and 6L == 0L) {
                codecRequire(valueBytes <= 8192, CodecReadIssue.RESOURCE_LIMIT)
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                val value = decoder.decode(ByteBuffer.wrap(input.fixed(valueBytes.toInt()))).toString()
                codecRequire(!result.containsKey(key))
                val end = minOf(value.length, 4096)
                val safe = if (end < value.length && end > 0 && value[end - 1].isHighSurrogate()) end - 1 else end
                // Gain parsing must see the entire admitted literal, including invalid trailing text.
                result[key] = if (key.startsWith("REPLAYGAIN_")) value else value.substring(0, safe)
            } else input.moveTo(codecAdd(input.position, valueBytes))
        }
        codecRequire(input.position == end - 32)
        input.checkCancellation()
        return result
    }
}
