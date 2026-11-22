package io.github.onlinemo.meloxwatch.data.local

import android.os.CancellationSignal
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.Locale

data class AudioTextTags(
    val title: String? = null,
    val artist: String? = null,
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: String? = null,
    val discNumber: String? = null,
    val compilation: Boolean? = null,
)

/** Bounded ID3v2.3/v2.4 and FLAC/Vorbis text. Unknown formats remain native-reader inputs. */
object AudioTagReader {
    const val PARSER_VERSION = 2
    const val MAX_TEXT_BYTES = 1024 * 1024
    const val MAX_PICTURE_BYTES = 16 * 1024 * 1024
    private const val MAX_TAG_SPAN = 32 * 1024 * 1024
    private const val MAX_FRAMES = 4096
    private const val CHUNK = 64 * 1024

    fun readText(input: InputStream, signal: CancellationSignal? = null): AudioTextTags =
        parse(input, false, signal).first

    fun readPicture(input: InputStream, signal: CancellationSignal? = null): ByteArray? =
        parse(input, true, signal).second

    private fun parse(input: InputStream, pictures: Boolean, signal: CancellationSignal?): Pair<AudioTextTags, ByteArray?> {
        val start = read(input, 4, signal, allowEnd = true) ?: return AudioTextTags() to null
        return when {
            start[0] == 'I'.code.toByte() && start[1] == 'D'.code.toByte() && start[2] == '3'.code.toByte() ->
                id3(input, start[3].toInt() and 255, pictures, signal)
            start.contentEquals("fLaC".toByteArray(Charsets.US_ASCII)) -> flac(input, pictures, signal)
            else -> AudioTextTags() to null
        }
    }

    private fun id3(input: InputStream, version: Int, pictures: Boolean, signal: CancellationSignal?): Pair<AudioTextTags, ByteArray?> {
        val tail = read(input, 6, signal) ?: fail()
        if (version !in 3..4) return AudioTextTags() to null
        val flags = tail[1].toInt() and 255
        val total = syncSafe(tail, 2)
        if (total > MAX_TAG_SPAN) fail()
        // Header-wide unsynchronisation changes frame offsets; native text fallback remains available.
        if (flags and 128 != 0) return AudioTextTags() to null
        var remaining = total
        if (flags and 64 != 0) {
            val size = read(input, 4, signal) ?: fail()
            val amount = if (version == 4) syncSafe(size, 0) - 4 else unsignedInt(size, 0)
            if (amount < 0 || amount > remaining - 4) fail()
            skip(input, amount, signal)
            remaining -= amount + 4
        }
        val fields = LinkedHashMap<String, List<String>>()
        var textBytes = 0
        var frames = 0
        var picture: ByteArray? = null
        while (remaining >= 10) {
            if (++frames > MAX_FRAMES) fail()
            val header = read(input, 10, signal) ?: fail()
            if (header[0].toInt() == 0) break
            val frameId = String(header, 0, 4, Charsets.US_ASCII)
            if (!frameId.all { it in 'A'..'Z' || it in '0'..'9' }) fail()
            val size = if (version == 4) syncSafe(header, 4) else unsignedInt(header, 4)
            remaining -= 10
            if (size < 0 || size > remaining) fail()
            remaining -= size
            val formatFlags = header[9].toInt() and 255
            val unsupportedFlags = if (version == 4) formatFlags and 0x4d != 0 else formatFlags != 0
            val desired = !unsupportedFlags && (frameId in TEXT_FRAMES || (pictures && frameId == "APIC"))
            if (!desired) { skip(input, size, signal); continue }
            if (frameId == "APIC" && size > MAX_PICTURE_BYTES) fail()
            if (frameId in TEXT_FRAMES) {
                textBytes = Math.addExact(textBytes, size)
                if (textBytes > MAX_TEXT_BYTES) fail()
            }
            var payload = read(input, size, signal) ?: fail()
            if (version == 4 && formatFlags and 2 != 0) payload = deUnsynchronise(payload)
            if (frameId == "APIC") {
                val found = id3Picture(payload)
                if (found != null && (picture == null || found.first == 3)) picture = found.second
                if (found?.first == 3) break
            } else {
                val values = textFrame(payload, version)
                if (values.isNotEmpty()) fields[frameId] = values
            }
        }
        return fieldsToTags(fields) to picture
    }

    private fun flac(input: InputStream, pictures: Boolean, signal: CancellationSignal?): Pair<AudioTextTags, ByteArray?> {
        var consumed = 0
        var blocks = 0
        var fields: Map<String, List<String>> = emptyMap()
        var picture: ByteArray? = null
        while (true) {
            if (++blocks > MAX_FRAMES) fail()
            val header = read(input, 4, signal) ?: fail()
            val length = ((header[1].toInt() and 255) shl 16) or ((header[2].toInt() and 255) shl 8) or (header[3].toInt() and 255)
            consumed = Math.addExact(consumed, length + 4)
            if (consumed > MAX_TAG_SPAN) fail()
            val type = header[0].toInt() and 127
            when {
                type == 4 && !pictures -> {
                    if (length > MAX_TEXT_BYTES) fail()
                    fields = vorbis(read(input, length, signal) ?: fail())
                }
                type == 6 && pictures -> {
                    if (length > MAX_PICTURE_BYTES) fail()
                    val found = flacPicture(read(input, length, signal) ?: fail())
                    if (found != null && (picture == null || found.first == 3)) picture = found.second
                    if (found?.first == 3) break
                }
                else -> skip(input, length, signal)
            }
            if (header[0].toInt() and 128 != 0) break
        }
        return fieldsToTags(fields) to picture
    }

    private fun textFrame(payload: ByteArray, version: Int): List<String> {
        if (payload.isEmpty()) return emptyList()
        val charset: Charset = when (payload[0].toInt()) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> return emptyList()
        }
        val raw = String(payload, 1, payload.size - 1, charset).trimEnd('\u0000')
        // ID3v2.3 slash-delimited conventions are ambiguous; retain the original string.
        return (if (version == 4) raw.split('\u0000', limit = LocalInputLimits.TAG_VALUES + 1) else listOf(raw))
            .take(LocalInputLimits.TAG_VALUES).map(::boundedText).filter(String::isNotBlank)
    }

    private fun vorbis(bytes: ByteArray): Map<String, List<String>> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun amount(): Int {
            if (buffer.remaining() < 4) fail()
            return buffer.int.takeIf { it >= 0 && it <= buffer.remaining() } ?: fail()
        }
        val vendorLength = amount()
        buffer.position(buffer.position() + vendorLength)
        if (buffer.remaining() < 4) fail()
        val count = buffer.int
        if (count !in 0..4096) fail()
        val fields = LinkedHashMap<String, MutableList<String>>()
        repeat(count) {
            val length = amount()
            val comment = ByteArray(length)
            buffer.get(comment)
            val text = String(comment, Charsets.UTF_8)
            val equals = text.indexOf('=')
            if (equals > 0) {
                val key = text.substring(0, equals).uppercase(Locale.ROOT)
                if (key in VORBIS_KEYS) {
                    val values = fields.getOrPut(VORBIS_KEYS.getValue(key)) { ArrayList() }
                    if (values.size < LocalInputLimits.TAG_VALUES) values += boundedText(text.substring(equals + 1))
                }
            }
        }
        return fields
    }

    private fun fieldsToTags(fields: Map<String, List<String>>): AudioTextTags {
        fun first(key: String): String? = fields[key]?.firstOrNull()?.takeIf(String::isNotBlank)
        val artists = fields["TPE1"].orEmpty().filter(String::isNotBlank)
        return AudioTextTags(first("TIT2"), artists.takeIf { it.isNotEmpty() }?.joinToString("\u0000")?.let(::boundedText),
            artists, first("TALB"), first("TPE2"), first("TRCK"), first("TPOS"),
            first("TCMP")?.let { it == "1" || it.equals("true", true) })
    }

    private fun id3Picture(payload: ByteArray): Pair<Int, ByteArray>? {
        if (payload.size < 5) return null
        val encoding = payload[0].toInt() and 255
        val mimeEnd = payload.indexOf(0, 1)
        if (mimeEnd !in 2..128 || mimeEnd + 2 >= payload.size) return null
        val type = payload[mimeEnd + 1].toInt() and 255
        val unit = if (encoding == 1 || encoding == 2) 2 else 1
        var position = mimeEnd + 2
        while (position + unit <= payload.size) {
            val terminal = payload[position].toInt() == 0 && (unit == 1 || payload[position + 1].toInt() == 0)
            position += unit
            if (terminal) break
        }
        if (position >= payload.size) return null
        return type to payload.copyOfRange(position, payload.size)
    }

    private fun flacPicture(payload: ByteArray): Pair<Int, ByteArray>? {
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        fun number(): Int = if (buffer.remaining() >= 4) buffer.int.takeIf { it >= 0 } ?: fail() else fail()
        val type = number()
        repeat(2) { val length = number(); if (length > buffer.remaining()) fail(); buffer.position(buffer.position() + length) }
        val width = number().toLong()
        val height = number().toLong()
        number(); number()
        if (width * height > 16_000_000L) fail()
        val length = number()
        if (length > MAX_PICTURE_BYTES || length != buffer.remaining()) fail()
        return type to ByteArray(length).also { buffer.get(it) }
    }

    private fun unsignedInt(bytes: ByteArray, offset: Int): Int {
        var result = 0L
        repeat(4) { result = (result shl 8) or (bytes[offset + it].toLong() and 255) }
        return result.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: fail()
    }

    private fun syncSafe(bytes: ByteArray, offset: Int): Int {
        var value = 0
        repeat(4) {
            val part = bytes[offset + it].toInt() and 255
            if (part and 128 != 0) fail()
            value = (value shl 7) or part
        }
        return value
    }

    private fun read(input: InputStream, count: Int, signal: CancellationSignal?, allowEnd: Boolean = false): ByteArray? {
        if (count < 0 || count > MAX_PICTURE_BYTES) fail()
        val bytes = ByteArray(count)
        var position = 0
        while (position < count) {
            signal?.throwIfCanceled()
            val read = input.read(bytes, position, minOf(CHUNK, count - position))
            if (read < 0) { if (allowEnd) return null; throw EOFException("Incomplete audio tag") }
            if (read == 0) throw EOFException("Audio tag made no progress")
            position += read
        }
        signal?.throwIfCanceled()
        return bytes
    }

    private fun skip(input: InputStream, count: Int, signal: CancellationSignal?) {
        if (count < 0) fail()
        val scratch = ByteArray(minOf(CHUNK, count))
        var remaining = count
        while (remaining > 0) {
            signal?.throwIfCanceled()
            val amount = input.read(scratch, 0, minOf(scratch.size, remaining))
            if (amount <= 0) throw EOFException("Incomplete audio tag")
            remaining -= amount
        }
    }

    private fun deUnsynchronise(bytes: ByteArray): ByteArray {
        val result = ByteArray(bytes.size)
        var written = 0
        var index = 0
        while (index < bytes.size) {
            val current = bytes[index++]
            result[written++] = current
            if (current.toInt() and 255 == 255 && index < bytes.size && bytes[index].toInt() == 0) index++
        }
        return result.copyOf(written)
    }

    private fun ByteArray.indexOf(value: Byte, start: Int): Int {
        for (index in start until size) if (this[index] == value) return index
        return -1
    }

    private fun boundedText(text: String): String {
        val end = minOf(text.length, LocalInputLimits.TAG_CHARACTERS)
        val safe = if (end < text.length && end > 0 && text[end - 1].isHighSurrogate()) end - 1 else end
        return text.substring(0, safe)
    }

    private fun fail(): Nothing = throw SourceReadException(SourceReadIssue.METADATA_UNREADABLE)
    private val TEXT_FRAMES = setOf("TIT2", "TPE1", "TPE2", "TALB", "TRCK", "TPOS", "TCMP")
    private val VORBIS_KEYS = mapOf("TITLE" to "TIT2", "ARTIST" to "TPE1", "ALBUMARTIST" to "TPE2",
        "ALBUM ARTIST" to "TPE2", "ALBUM" to "TALB", "TRACKNUMBER" to "TRCK", "DISCNUMBER" to "TPOS", "COMPILATION" to "TCMP")
}
