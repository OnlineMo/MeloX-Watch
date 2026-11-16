package io.github.onlinemo.meloxwatch.data.lyrics

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** ID3 strings have field-relative UTF-16 terminators; this does not apply LRC BOM heuristics. */
internal class EmbeddedId3TextDecoder(
    encoding: Int,
    version: Int,
    private val budget: EmbeddedLyricsBudget,
    private val check: () -> Unit,
) {
    val wireEncoding: EmbeddedLyricsWireEncoding = when (encoding) {
        0 -> EmbeddedLyricsWireEncoding.ISO_8859_1
        1 -> EmbeddedLyricsWireEncoding.UTF16_BOM
        2 -> if (version == 4) EmbeddedLyricsWireEncoding.UTF16_BE else embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_ENCODING)
        3 -> if (version == 4) EmbeddedLyricsWireEncoding.UTF8 else embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_ENCODING)
        else -> embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_ENCODING)
    }
    private val width = if (encoding == 1 || encoding == 2) 2 else 1
    private var bomCharset: Charset? = null

    fun language(cursor: EmbeddedLyricsCursor): String {
        if (cursor.remaining < 3) embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_LANGUAGE)
        val start = cursor.position
        repeat(3) {
            check()
            val character = cursor.byte()
            if (character !in 'a'.code..'z'.code && character !in 'A'.code..'Z'.code) {
                embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_LANGUAGE)
            }
        }
        return String(cursor.bytes, start, 3, Charsets.US_ASCII).lowercase(Locale.ROOT)
    }

    fun terminated(cursor: EmbeddedLyricsCursor, descriptor: Boolean = false): String {
        val start = cursor.position
        var end = start
        while (end <= cursor.bytes.size - width) {
            if ((end - start) % 256 == 0) check()
            if (cursor.bytes[end] == 0.toByte() && (width == 1 || cursor.bytes[end + 1] == 0.toByte())) {
                val text = decode(cursor.bytes, start, end, descriptor)
                cursor.position = end + width
                return text
            }
            end += width
            if (descriptor && end - start > budget.limits.maxDescriptorCodePoints * 4 + 2) {
                embeddedLyricsFailure(EmbeddedLyricsIssue.DESCRIPTOR_TOO_LONG)
            }
        }
        embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_TEXT)
    }

    /** USLT permits a final string terminator; embedded NULs are not silently truncated. */
    fun body(cursor: EmbeddedLyricsCursor): String {
        val start = cursor.position
        var end = cursor.bytes.size
        if (width == 2 && (end - start) % 2 != 0) embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_ENCODING)
        if (end - start >= width && cursor.bytes[end - 1] == 0.toByte() &&
            (width == 1 || cursor.bytes[end - 2] == 0.toByte())) end -= width
        val text = decode(cursor.bytes, start, end, false)
        cursor.position = cursor.bytes.size
        return text
    }

    private fun decode(bytes: ByteArray, start: Int, end: Int, descriptor: Boolean): String {
        check()
        if (start == end) return ""
        if (descriptor && end - start > budget.limits.maxDescriptorCodePoints * 4 + 2) {
            embeddedLyricsFailure(EmbeddedLyricsIssue.DESCRIPTOR_TOO_LONG)
        }
        var contentStart = start
        val charset = when (wireEncoding) {
            EmbeddedLyricsWireEncoding.ISO_8859_1 -> Charsets.ISO_8859_1
            EmbeddedLyricsWireEncoding.UTF8 -> Charsets.UTF_8
            EmbeddedLyricsWireEncoding.UTF16_BE -> {
                if ((end - start) % 2 != 0) embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_ENCODING)
                if (isBom(bytes, start, end, 0xff, 0xfe) || isBom(bytes, start, end, 0xfe, 0xff)) {
                    embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_ENCODING)
                }
                Charsets.UTF_16BE
            }
            EmbeddedLyricsWireEncoding.UTF16_BOM -> {
                if ((end - start) % 2 != 0) embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_ENCODING)
                val actual = when {
                    isBom(bytes, start, end, 0xff, 0xfe) -> Charsets.UTF_16LE
                    isBom(bytes, start, end, 0xfe, 0xff) -> Charsets.UTF_16BE
                    else -> embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_ENCODING)
                }
                if (bomCharset != null && bomCharset != actual) embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_ENCODING)
                bomCharset = actual
                contentStart += 2
                actual
            }
        }
        budget.decoding(end - start)
        val text = try {
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, contentStart, end - contentStart)).toString()
        } catch (_: CharacterCodingException) {
            embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_ENCODING)
        }
        validate(text, descriptor)
        check()
        return text
    }

    private fun validate(text: String, descriptor: Boolean) {
        var position = 0
        var points = 0
        while (position < text.length) {
            if (position % 256 == 0) check()
            val character = text[position]
            if (character < ' ' && (descriptor || character != '\t' && character != '\n' && character != '\r')) {
                embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_TEXT)
            }
            position += if (Character.isHighSurrogate(character) && position + 1 < text.length &&
                Character.isLowSurrogate(text[position + 1])) 2 else 1
            if (descriptor && ++points > budget.limits.maxDescriptorCodePoints) {
                embeddedLyricsFailure(EmbeddedLyricsIssue.DESCRIPTOR_TOO_LONG)
            }
        }
    }

    private fun isBom(bytes: ByteArray, start: Int, end: Int, first: Int, second: Int): Boolean = end - start >= 2 &&
        (bytes[start].toInt() and 0xff) == first && (bytes[start + 1].toInt() and 0xff) == second
}
