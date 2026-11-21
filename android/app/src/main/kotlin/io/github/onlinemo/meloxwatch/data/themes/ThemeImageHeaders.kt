package io.github.onlinemo.meloxwatch.data.themes

import io.github.onlinemo.meloxwatch.data.artwork.ArtworkLimits
import java.util.zip.CRC32

/** 静态位图容器先于原生解码检查；不接受动画帧、压缩文字/压缩 ICC 或尾随的另一种载荷。 */
internal object ThemeImageHeaders {
    fun verify(bytes: ByteArray, mediaType: String, checkCancellation: () -> Unit) {
        when (mediaType) {
            "image/png" -> png(bytes, checkCancellation)
            "image/jpeg" -> jpeg(bytes, checkCancellation)
            "image/webp" -> webp(bytes, checkCancellation)
            else -> themeFail(ThemeIssue.UNSUPPORTED_RESOURCE)
        }
        checkCancellation()
    }

    private fun png(bytes: ByteArray, checkCancellation: () -> Unit) {
        if (bytes.size < 33 || !bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) themeFail(ThemeIssue.INVALID_IMAGE)
        var offset = 8
        var chunks = 0
        var hasData = false
        var hasEnd = false
        var hasHeader = false
        val singleton = HashSet<String>()
        while (offset < bytes.size) {
            checkCancellation()
            if (++chunks > 4096 || offset > bytes.size - 12) themeFail(ThemeIssue.INVALID_IMAGE)
            val length = big32(bytes, offset)
            if (length > bytes.size - offset - 12) themeFail(ThemeIssue.INVALID_IMAGE)
            val count = length.toInt()
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            if (type !in PNG_CHUNKS) themeFail(ThemeIssue.UNSUPPORTED_RESOURCE)
            if (type != "IDAT" && !singleton.add(type)) themeFail(ThemeIssue.INVALID_IMAGE)
            val crc = CRC32()
            var checked = 0
            while (checked < count + 4) {
                checkCancellation()
                val next = minOf(ThemeLimits.IO_BYTES, count + 4 - checked)
                crc.update(bytes, offset + 4 + checked, next)
                checked += next
            }
            if (crc.value != big32(bytes, offset + 8 + count)) themeFail(ThemeIssue.INTEGRITY_FAILED)
            when (type) {
                "IHDR" -> {
                    if (hasHeader || offset != 8 || count != 13) themeFail(ThemeIssue.INVALID_IMAGE)
                    pixels(big32(bytes, offset + 8), big32(bytes, offset + 12))
                    if (u8(bytes, offset + 18) != 0 || u8(bytes, offset + 19) != 0 ||
                        u8(bytes, offset + 20) !in 0..1) themeFail(ThemeIssue.UNSUPPORTED_RESOURCE)
                    hasHeader = true
                }
                "IDAT" -> hasData = true
                "IEND" -> {
                    if (count != 0 || !hasHeader || !hasData || offset + 12 != bytes.size) {
                        themeFail(ThemeIssue.INVALID_IMAGE)
                    }
                    hasEnd = true
                }
            }
            offset += count + 12
        }
        if (!hasHeader || !hasData || !hasEnd) themeFail(ThemeIssue.INVALID_IMAGE)
    }

    /** JPEG 不靠首尾两字节认定容器完整；只接受原生可验证的静态帧、JFIF/Adobe 及单片有界 ICC 颜色配置。 */
    private fun jpeg(bytes: ByteArray, checkCancellation: () -> Unit) {
        if (bytes.size < 4 || u8(bytes, 0) != 255 || u8(bytes, 1) != 216) themeFail(ThemeIssue.INVALID_IMAGE)
        var offset = 2
        var segments = 0
        var frame = false
        var scan = false
        var hasIcc = false
        while (offset < bytes.size) {
            checkCancellation()
            if (++segments > 4096 || u8(bytes, offset) != 255) themeFail(ThemeIssue.INVALID_IMAGE)
            while (offset < bytes.size && u8(bytes, offset) == 255) offset++
            if (offset == bytes.size) themeFail(ThemeIssue.INVALID_IMAGE)
            val marker = u8(bytes, offset++)
            if (marker == 217) {
                if (!frame || !scan || offset != bytes.size) themeFail(ThemeIssue.INVALID_IMAGE)
                return
            }
            if (marker !in JPEG_MARKERS || offset > bytes.size - 2) themeFail(ThemeIssue.UNSUPPORTED_RESOURCE)
            val count = big16(bytes, offset)
            if (count < 2 || count > bytes.size - offset) themeFail(ThemeIssue.INVALID_IMAGE)
            val end = offset + count
            when (marker) {
                192, 193, 194 -> {
                    if (frame || count < 11 || u8(bytes, offset + 2) != 8) themeFail(ThemeIssue.INVALID_IMAGE)
                    val components = u8(bytes, offset + 7)
                    if (components !in 1..4 || count != 8 + 3 * components) themeFail(ThemeIssue.INVALID_IMAGE)
                    pixels(big16(bytes, offset + 5).toLong(), big16(bytes, offset + 3).toLong())
                    frame = true
                }
                224 -> {
                    if (count < 16 || String(bytes, offset + 2, 5, Charsets.US_ASCII) != "JFIF\u0000") {
                        themeFail(ThemeIssue.UNSUPPORTED_RESOURCE)
                    }
                    if (count != 16 + u8(bytes, offset + 14) * u8(bytes, offset + 15) * 3) {
                        themeFail(ThemeIssue.INVALID_IMAGE)
                    }
                }
                226 -> {
                    if (hasIcc || scan || count < 16) themeFail(ThemeIssue.INVALID_IMAGE)
                    if (String(bytes, offset + 2, 12, Charsets.US_ASCII) != "ICC_PROFILE\u0000" ||
                        u8(bytes, offset + 14) != 1 || u8(bytes, offset + 15) != 1) {
                        themeFail(ThemeIssue.UNSUPPORTED_RESOURCE)
                    }
                    verifyIcc(bytes, offset + 16, count - 16, checkCancellation)
                    hasIcc = true
                }
                238 -> if (count != 14 || String(bytes, offset + 2, 5, Charsets.US_ASCII) != "Adobe") {
                    themeFail(ThemeIssue.UNSUPPORTED_RESOURCE)
                }
                218 -> {
                    if (!frame || count < 6 || count != 6 + 2 * u8(bytes, offset + 2)) themeFail(ThemeIssue.INVALID_IMAGE)
                    scan = true
                    offset = end
                    while (offset < bytes.size) {
                        if ((offset and 4095) == 0) checkCancellation()
                        if (u8(bytes, offset) != 255) {
                            offset++
                            continue
                        }
                        val start = offset
                        while (offset < bytes.size && u8(bytes, offset) == 255) offset++
                        if (offset == bytes.size) themeFail(ThemeIssue.INVALID_IMAGE)
                        val next = u8(bytes, offset)
                        if (next == 0 || next in 208..215) offset++ else {
                            offset = start
                            break
                        }
                    }
                    continue
                }
            }
            offset = end
        }
        themeFail(ThemeIssue.INVALID_IMAGE)
    }

    /** Android encoders include raw ICC profiles; bound the shared validator without decompression or multipart assembly. */
    private fun verifyIcc(bytes: ByteArray, start: Int, size: Int, checkCancellation: () -> Unit) {
        if (size !in 132..MAX_ICC_BYTES || big32(bytes, start) != size.toLong() || ascii(bytes, start + 36) != "acsp") {
            themeFail(ThemeIssue.INVALID_IMAGE)
        }
        val tags = big32(bytes, start + 128)
        if (tags !in 1L..128L || tags > (size - 132) / 12L) themeFail(ThemeIssue.INVALID_IMAGE)
        val tableEnd = 132L + 12L * tags
        repeat(tags.toInt()) { index ->
            checkCancellation()
            val record = start + 132 + index * 12
            val offset = big32(bytes, record + 4)
            val length = big32(bytes, record + 8)
            if (offset < tableEnd || offset > size || (offset and 3L) != 0L ||
                length < 8L || length > size - offset) themeFail(ThemeIssue.INVALID_IMAGE)
        }
    }

    private fun webp(bytes: ByteArray, checkCancellation: () -> Unit) {
        if (bytes.size < 20 || ascii(bytes, 0) != "RIFF" || ascii(bytes, 8) != "WEBP" ||
            little32(bytes, 4) != bytes.size.toLong() - 8) themeFail(ThemeIssue.INVALID_IMAGE)
        var offset = 12
        var chunks = 0
        var imageChunks = 0
        var extendedFlags: Int? = null
        var hasIcc = false
        var hasAlpha = false
        var lossless = false
        val seen = HashSet<String>()
        while (offset < bytes.size) {
            checkCancellation()
            if (++chunks > 16 || offset > bytes.size - 8) themeFail(ThemeIssue.INVALID_IMAGE)
            val type = ascii(bytes, offset)
            val length = little32(bytes, offset + 4)
            if (type !in WEBP_CHUNKS) themeFail(ThemeIssue.UNSUPPORTED_RESOURCE)
            if (!seen.add(type) || length > bytes.size - offset - 8) themeFail(ThemeIssue.INVALID_IMAGE)
            val count = length.toInt()
            if (type == "VP8X") {
                if (count != 10 || offset != 12 || (u8(bytes, offset + 8) and 0xcf) != 0 ||
                    u8(bytes, offset + 9) != 0 || u8(bytes, offset + 10) != 0 || u8(bytes, offset + 11) != 0) {
                    themeFail(ThemeIssue.UNSUPPORTED_RESOURCE)
                }
                extendedFlags = u8(bytes, offset + 8)
                pixels(little24(bytes, offset + 12) + 1, little24(bytes, offset + 15) + 1)
            }
            if (type == "ICCP") {
                // The color flag and its single raw profile must agree, before alpha/image data.
                if (extendedFlags == null || (extendedFlags and 0x20) == 0 || imageChunks != 0 || "ALPH" in seen) {
                    themeFail(ThemeIssue.INVALID_IMAGE)
                }
                verifyIcc(bytes, offset + 8, count, checkCancellation)
                hasIcc = true
            }
            if (type == "ALPH") {
                if (extendedFlags == null || (extendedFlags and 0x10) == 0 || imageChunks != 0 || count < 2 ||
                    (u8(bytes, offset + 8) and 0xc0) != 0) themeFail(ThemeIssue.INVALID_IMAGE)
                hasAlpha = true
            }
            if (type == "VP8L") {
                if (hasAlpha || count < 5 || u8(bytes, offset + 8) != 0x2f ||
                    (u8(bytes, offset + 12) and 0xe0) != 0) themeFail(ThemeIssue.INVALID_IMAGE)
                val packed = little32(bytes, offset + 9)
                pixels((packed and 0x3fff) + 1, ((packed shr 14) and 0x3fff) + 1)
                // VP8L carries alpha itself. Its alpha_is_used bit is only a hint, never an ALPH requirement.
                lossless = true
            }
            if (type == "VP8 " || type == "VP8L") imageChunks++
            val padded = count + (count and 1)
            if (padded > bytes.size - offset - 8) themeFail(ThemeIssue.INVALID_IMAGE)
            if ((count and 1) != 0 && bytes[offset + 8 + count] != 0.toByte()) themeFail(ThemeIssue.INVALID_IMAGE)
            offset += padded + 8
        }
        if (imageChunks != 1 || (extendedFlags?.and(0x20) == 0x20) != hasIcc ||
            !lossless && extendedFlags != null && ((extendedFlags and 0x10) != 0) != hasAlpha) {
            themeFail(ThemeIssue.INVALID_IMAGE)
        }
    }

    private fun u8(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 255
    private fun big16(bytes: ByteArray, offset: Int): Int = (u8(bytes, offset) shl 8) or u8(bytes, offset + 1)
    private fun little24(bytes: ByteArray, offset: Int): Long = u8(bytes, offset).toLong() or
        (u8(bytes, offset + 1).toLong() shl 8) or (u8(bytes, offset + 2).toLong() shl 16)
    private fun pixels(width: Long, height: Long) {
        if (width <= 0 || height <= 0) themeFail(ThemeIssue.INVALID_IMAGE)
        if (width > ArtworkLimits.INPUT_PIXELS / height) themeFail(ThemeIssue.IMAGE_TOO_LARGE)
    }
    private fun big32(bytes: ByteArray, offset: Int): Long = (u8(bytes, offset).toLong() shl 24) or
        (u8(bytes, offset + 1).toLong() shl 16) or (u8(bytes, offset + 2).toLong() shl 8) or u8(bytes, offset + 3).toLong()

    private fun little32(bytes: ByteArray, offset: Int): Long = u8(bytes, offset).toLong() or
        (u8(bytes, offset + 1).toLong() shl 8) or (u8(bytes, offset + 2).toLong() shl 16) or
        (u8(bytes, offset + 3).toLong() shl 24)

    private fun ascii(bytes: ByteArray, offset: Int): String = String(bytes, offset, 4, Charsets.US_ASCII)
    private val PNG_SIGNATURE = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
    private val PNG_CHUNKS = setOf("IHDR", "PLTE", "tRNS", "IDAT", "IEND", "pHYs", "sRGB", "gAMA", "cHRM", "bKGD", "sBIT")
    private const val MAX_ICC_BYTES = 64 * 1024
    private val WEBP_CHUNKS = setOf("VP8X", "ICCP", "VP8 ", "VP8L", "ALPH")
    private val JPEG_MARKERS = setOf(192, 193, 194, 196, 218, 219, 221, 224, 226, 238)
}
