package io.github.onlinemo.meloxwatch.data.fileoperations

import android.os.CancellationSignal
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale

enum class WritableTagContainer { NATIVE_FLAC, MP3_ID3_V24 }
data class AudioTagFileFacts(val container: WritableTagContainer, val values: Map<AudioTagField, List<String>?>,
    val audioSha256: String, val audioBytes: Long, val metadataBytes: Long)
private data class AudioPayloadProof(val sha256: String, val bytes: Long)

/**
 * 只写完整候选，不以截断方式写原件。
 * 原生 FLAC 依据 RFC 9639 §8；ID3 依据 https://id3.org/id3v2.4.0-structure
 * 及 https://id3.org/id3v2.4.0-frames，没有复制上游实现。
 * 其他 ID3 版本、全局 unsynchronisation/扩展头、尾部标签、seek 链/音频加密帧
 * 及不支持的帧标志仍是明确格式缺口，不能宣称完整覆盖。
 */
class AudioTagFileCodec {
    fun rewrite(input: InputStream, output: OutputStream, patch: AudioTagPatch, cancellation: CancellationSignal,
        beforeWrite: (Int) -> Unit): AudioTagFileFacts {
        val stream = BufferedInputStream(input, FileOperationLimits.BUFFER_BYTES)
        val original = readHeader(stream, cancellation)
        val expected = original.values.toMutableMap().apply { putAll(patch.values) }
        val encoded = original.encode(patch)
        writeBounded(output, encoded, cancellation, beforeWrite)
        val audio = copyAudio(stream, output, original.prefix, original.container, cancellation, beforeWrite)
        return AudioTagFileFacts(original.container, immutableTags(expected), audio.sha256, audio.bytes, encoded.size.toLong())
    }

    /** 候选描述符同步并关闭后，适配器另开新流复读，不能把写入器自己的结果当验证。 */
    fun inspect(input: InputStream, cancellation: CancellationSignal): AudioTagFileFacts {
        val stream = BufferedInputStream(input, FileOperationLimits.BUFFER_BYTES)
        val header = readHeader(stream, cancellation)
        val audio = copyAudio(stream, null, header.prefix, header.container, cancellation) {}
        return AudioTagFileFacts(header.container, immutableTags(header.values), audio.sha256, audio.bytes, header.byteCount)
    }

    private sealed interface Header {
        val container: WritableTagContainer
        val values: Map<AudioTagField, List<String>?>
        val prefix: ByteArray
        val byteCount: Long
        fun encode(patch: AudioTagPatch): ByteArray
    }

    private data class FlacBlock(val type: Int, val bytes: ByteArray)
    private data class Comment(val raw: ByteArray, val key: String, val value: String?)
    private data class FlacHeader(val blocks: List<FlacBlock>, val vendor: ByteArray, val comments: List<Comment>,
        override val byteCount: Long) : Header {
        override val container = WritableTagContainer.NATIVE_FLAC
        override val prefix = byteArrayOf()
        override val values = emptyTagValues().toMutableMap().apply {
            for (field in AudioTagField.entries) {
                val found = comments.filter { flacField(it.key) == field }.map { checkNotNull(it.value) }
                if (found.isNotEmpty()) put(field, found)
            }
        }

        override fun encode(patch: AudioTagPatch): ByteArray {
            val edited = comments.filter { flacField(it.key) !in patch.values.keys }.map { it.raw }.toMutableList()
            for (field in AudioTagField.entries) if (field in patch.values) {
                patch.values[field]?.forEach { edited += (flacName(field) + "=" + it).toByteArray(Charsets.UTF_8) }
            }
            val commentPayload = ByteArrayOutputStream().apply {
                putLittle(vendor.size)
                write(vendor)
                putLittle(edited.size)
                for (entry in edited) { putLittle(entry.size)
                    write(entry) }
            }.toByteArray()
            if (commentPayload.size > 0xffffff || edited.size > FileOperationLimits.TAG_RECORDS) unsupported()
            val replaced = blocks.map { if (it.type == 4) FlacBlock(4, commentPayload) else it }.toMutableList()
            if (blocks.none { it.type == 4 }) replaced.add(FlacBlock(4, commentPayload))
            val total = replaced.sumOf { it.bytes.size.toLong() + 4L } + 4L
            if (total > FileOperationLimits.TAG_METADATA_BYTES) unsupported()
            return ByteArrayOutputStream().apply {
                write(byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte()))
                replaced.forEachIndexed { index, block ->
                    write(block.type or if (index == replaced.lastIndex) 0x80 else 0)
                    write(block.bytes.size ushr 16)
                    write(block.bytes.size ushr 8)
                    write(block.bytes.size)
                    write(block.bytes)
                }
            }.toByteArray()
        }
    }

    private data class Id3Frame(val id: String, val status: Int, val format: Int, val raw: ByteArray, val body: ByteArray)
    private data class Id3Header(val frames: List<Id3Frame>, override val prefix: ByteArray = byteArrayOf(),
        override val byteCount: Long) : Header {
        override val container = WritableTagContainer.MP3_ID3_V24
        override val values = emptyTagValues().toMutableMap().apply {
            for (frame in frames) id3Field(frame.id)?.let { field ->
                if (frame.format != 0) unsupported()
                if (get(field) != null) invalid()
                put(field, textValues(frame.body))
            }
        }

        override fun encode(patch: AudioTagPatch): ByteArray {
            for (frame in frames) {
                if (frame.status and 0x40 != 0 && id3Field(frame.id) == null) unsupported()
                val field = id3Field(frame.id)
                if (field != null && field in patch.values && frame.status and 0x10 != 0) unsupported()
            }
            val preserved = frames.filter { id3Field(it.id) !in patch.values.keys }
            if (preserved.size + patch.values.count { it.value != null } > FileOperationLimits.TAG_RECORDS) unsupported()
            val payload = ByteArrayOutputStream().apply {
                preserved.forEach { write(it.raw) }
                for (field in AudioTagField.entries) if (field in patch.values) {
                    patch.values[field]?.let { values ->
                        val body = byteArrayOf(3) + values.joinToString("\u0000").toByteArray(Charsets.UTF_8)
                        write(id3Name(field).toByteArray(Charsets.US_ASCII))
                        write(synchsafeBytes(body.size))
                        write(0)
                        write(0)
                        write(body)
                    }
                }
            }.toByteArray()
            if (payload.isEmpty()) return byteArrayOf()
            if (payload.size > FileOperationLimits.TAG_METADATA_BYTES - 10) unsupported()
            return byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0) + synchsafeBytes(payload.size) + payload
        }
    }

    private fun readHeader(input: InputStream, cancellation: CancellationSignal): Header {
        val first = readRequired(input, 4, cancellation)
        return when {
            first.contentEquals("fLaC".toByteArray(Charsets.US_ASCII)) -> readFlac(input, cancellation)
            first.copyOfRange(0, 3).contentEquals("ID3".toByteArray(Charsets.US_ASCII)) -> {
                if (first[3].toInt() != 4) unsupported()
                val rest = readRequired(input, 6, cancellation)
                if (rest[0].toInt() != 0 || rest[1].toInt() != 0) unsupported()
                val size = synchsafe(rest, 2)
                if (size > FileOperationLimits.TAG_METADATA_BYTES - 10) unsupported()
                val payload = readRequired(input, size, cancellation)
                val frames = ArrayList<Id3Frame>()
                var position = 0
                while (position < payload.size) {
                    cancellation.throwIfCanceled()
                    if (payload[position].toInt() == 0) {
                        if (payload.copyOfRange(position, payload.size).any { it.toInt() != 0 }) invalid()
                        break
                    }
                    if (payload.size - position < 10 || frames.size >= FileOperationLimits.TAG_RECORDS) invalid()
                    val id = String(payload, position, 4, Charsets.US_ASCII)
                    if (id.any { it !in 'A'..'Z' && it !in '0'..'9' }) invalid()
                    if (id in setOf("ASPI", "SEEK", "AENC")) unsupported()
                    val length = synchsafe(payload, position + 4)
                    if (length < 1 || length > payload.size - position - 10) invalid()
                    val status = payload[position + 8].toInt() and 255
                    val format = payload[position + 9].toInt() and 255
                    if (status and 0x8f != 0 || format and 0xb0 != 0) unsupported()
                    frames += Id3Frame(id, status, format, payload.copyOfRange(position, position + 10 + length),
                        if (id3Field(id) == null) byteArrayOf() else payload.copyOfRange(position + 10, position + 10 + length))
                    position += 10 + length
                }
                if (frames.isEmpty()) invalid()
                Id3Header(frames, byteCount = 10L + size)
            }
            isMpegLayer3(first) -> Id3Header(emptyList(), first, 0L)
            else -> unsupported()
        }
    }

    private fun readFlac(input: InputStream, cancellation: CancellationSignal): FlacHeader {
        val blocks = ArrayList<FlacBlock>()
        var bytes = 4L
        var last: Boolean
        do {
            val header = readRequired(input, 4, cancellation)
            val type = header[0].toInt() and 0x7f
            if (type == 127 || blocks.size >= FileOperationLimits.TAG_RECORDS || blocks.isEmpty() && type != 0) invalid()
            if (type in setOf(0, 3, 4, 5) && blocks.any { it.type == type }) invalid()
            val size = ((header[1].toInt() and 255) shl 16) or ((header[2].toInt() and 255) shl 8) or (header[3].toInt() and 255)
            bytes += 4L + size
            if (bytes > FileOperationLimits.TAG_METADATA_BYTES) unsupported()
            val payload = readRequired(input, size, cancellation)
            if (type == 0) validateStreaminfo(payload)
            if (type == 1 && payload.any { it.toInt() != 0 } || type == 2 && size < 4 || type == 3 && size % 18 != 0) invalid()
            blocks += FlacBlock(type, payload)
            last = header[0].toInt() and 0x80 != 0
        } while (!last)
        val comment = blocks.firstOrNull { it.type == 4 }?.bytes
        if (comment == null) return FlacHeader(blocks, "MeloX Watch".toByteArray(Charsets.UTF_8), emptyList(), bytes)
        var cursor = 0
        fun length(): Int {
            if (comment.size - cursor < 4) invalid()
            val value = (0..3).fold(0L) { result, index -> result or ((comment[cursor + index].toLong() and 255) shl (index * 8)) }
            cursor += 4
            if (value > comment.size - cursor || value > Int.MAX_VALUE) invalid()
            return value.toInt()
        }
        val vendorSize = length()
        val vendor = comment.copyOfRange(cursor, cursor + vendorSize)
        validateUtf8(ByteBuffer.wrap(vendor))
        cursor += vendorSize
        if (comment.size - cursor < 4) invalid()
        val count = (0..3).fold(0L) { result, index -> result or ((comment[cursor + index].toLong() and 255) shl (index * 8)) }
        cursor += 4
        if (count > FileOperationLimits.TAG_RECORDS) unsupported()
        val comments = ArrayList<Comment>()
        repeat(count.toInt()) {
            val size = length()
            val raw = comment.copyOfRange(cursor, cursor + size)
            cursor += size
            val separator = raw.indexOf('='.code.toByte())
            if (separator < 1 || raw.take(separator).any { (it.toInt() and 255) !in 0x20..0x7e }) invalid()
            val key = String(raw, 0, separator, Charsets.US_ASCII).uppercase(Locale.ROOT)
            validateUtf8(ByteBuffer.wrap(raw, separator + 1, raw.size - separator - 1))
            val value = if (flacField(key) == null) null else {
                if (raw.size - separator - 1 > FileOperationLimits.FIELD_CHARACTERS * 4) unsupported()
                decode(raw.copyOfRange(separator + 1, raw.size), Charsets.UTF_8).also {
                    if (it.length > FileOperationLimits.FIELD_CHARACTERS) unsupported()
                }
            }
            comments += Comment(raw, key, value)
        }
        if (cursor != comment.size) invalid()
        return FlacHeader(blocks, vendor, comments, bytes)
    }

    private fun copyAudio(input: InputStream, output: OutputStream?, prefix: ByteArray, container: WritableTagContainer,
        cancellation: CancellationSignal, beforeWrite: (Int) -> Unit): AudioPayloadProof {
        val first = if (prefix.isEmpty()) readRequired(input, 4, cancellation) else prefix
        if (container == WritableTagContainer.MP3_ID3_V24 && !isMpegLayer3(first)) invalid()
        if (container == WritableTagContainer.NATIVE_FLAC &&
            ((first[0].toInt() and 255) != 255 || (first[1].toInt() and 0xfe) != 0xf8)) invalid()
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(FileOperationLimits.BUFFER_BYTES)
        val tail = ByteArray(160)
        var tailSize = 0
        var count = 0L
        fun consume(bytes: ByteArray, size: Int) {
            cancellation.throwIfCanceled()
            output?.let { beforeWrite(size)
                it.write(bytes, 0, size) }
            hash.update(bytes, 0, size)
            count = Math.addExact(count, size.toLong())
            if (size >= tail.size) { System.arraycopy(bytes, size - tail.size, tail, 0, tail.size)
                tailSize = tail.size }
            else {
                val retain = minOf(tailSize, tail.size - size)
                System.arraycopy(tail, tailSize - retain, tail, 0, retain)
                System.arraycopy(bytes, 0, tail, retain, size)
                tailSize = retain + size
            }
        }
        consume(first, first.size)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            if (size == 0) throw FileOperationException(FileOperationIssue.SOURCE_UNAVAILABLE)
            consume(buffer, size)
        }
        if (container == WritableTagContainer.MP3_ID3_V24) {
            fun suffixTag(offset: Int, signature: String): Boolean = tailSize >= offset &&
                String(tail, tailSize - offset, signature.length, Charsets.US_ASCII) == signature
            if (suffixTag(128, "TAG") || suffixTag(32, "APETAGEX") || suffixTag(10, "3DI")) unsupported()
        }
        cancellation.throwIfCanceled()
        return AudioPayloadProof(fileOperationHex(hash.digest()), count)
    }

    private fun writeBounded(output: OutputStream, bytes: ByteArray, cancellation: CancellationSignal, beforeWrite: (Int) -> Unit) {
        var offset = 0
        while (offset < bytes.size) {
            cancellation.throwIfCanceled()
            val size = minOf(FileOperationLimits.BUFFER_BYTES, bytes.size - offset)
            beforeWrite(size)
            output.write(bytes, offset, size)
            offset += size
        }
    }

    companion object {
        private fun emptyTagValues(): Map<AudioTagField, List<String>?> = AudioTagField.entries.associateWith { null }
        private fun immutableTags(values: Map<AudioTagField, List<String>?>) = Collections.unmodifiableMap(
            values.mapValues { (_, value) -> value?.let { Collections.unmodifiableList(it.toList()) } })
        private fun flacName(field: AudioTagField): String = when (field) {
            AudioTagField.TITLE -> "TITLE"
            AudioTagField.ARTISTS -> "ARTIST"
            AudioTagField.ALBUM -> "ALBUM"
            AudioTagField.ALBUM_ARTIST -> "ALBUMARTIST"
            AudioTagField.TRACK_NUMBER -> "TRACKNUMBER"
            AudioTagField.DISC_NUMBER -> "DISCNUMBER"
        }
        private fun flacField(key: String): AudioTagField? = AudioTagField.entries.firstOrNull { flacName(it) == key }
            ?: AudioTagField.ALBUM_ARTIST.takeIf { key == "ALBUM_ARTIST" }
        private fun id3Name(field: AudioTagField): String = when (field) {
            AudioTagField.TITLE -> "TIT2"
            AudioTagField.ARTISTS -> "TPE1"
            AudioTagField.ALBUM -> "TALB"
            AudioTagField.ALBUM_ARTIST -> "TPE2"
            AudioTagField.TRACK_NUMBER -> "TRCK"
            AudioTagField.DISC_NUMBER -> "TPOS"
        }
        private fun id3Field(id: String): AudioTagField? = AudioTagField.entries.firstOrNull { id3Name(it) == id }
        private fun isMpegLayer3(bytes: ByteArray): Boolean = bytes.size >= 4 && (bytes[0].toInt() and 255) == 255 &&
            (bytes[1].toInt() and 0xe0) == 0xe0 && (bytes[1].toInt() ushr 3 and 3) != 1 &&
            (bytes[1].toInt() ushr 1 and 3) == 1 && (bytes[2].toInt() ushr 4 and 15) in 1..14 &&
            (bytes[2].toInt() ushr 2 and 3) != 3
        private fun validateStreaminfo(bytes: ByteArray) {
            if (bytes.size != 34) invalid()
            val minimum = ((bytes[0].toInt() and 255) shl 8) or (bytes[1].toInt() and 255)
            val maximum = ((bytes[2].toInt() and 255) shl 8) or (bytes[3].toInt() and 255)
            val rate = ((bytes[10].toInt() and 255) shl 12) or ((bytes[11].toInt() and 255) shl 4) or ((bytes[12].toInt() ushr 4) and 15)
            val depth = (((bytes[12].toInt() and 1) shl 4) or ((bytes[13].toInt() ushr 4) and 15)) + 1
            if (minimum !in 16..65535 || maximum < minimum || rate == 0 || depth !in 4..32) invalid()
        }
        private fun synchsafe(bytes: ByteArray, offset: Int): Int {
            if (offset < 0 || offset + 4 > bytes.size) invalid()
            var value = 0
            repeat(4) { index -> val byte = bytes[offset + index].toInt() and 255
                if (byte > 127) invalid()
                value = (value shl 7) or byte }
            return value
        }
        private fun synchsafeBytes(value: Int) = byteArrayOf((value ushr 21).toByte(), (value ushr 14 and 127).toByte(),
            (value ushr 7 and 127).toByte(), (value and 127).toByte())
        private fun textValues(body: ByteArray): List<String> {
            if (body.isEmpty()) invalid()
            if (body.size > FileOperationLimits.FIELD_CHARACTERS * FileOperationLimits.ARTIST_VALUES * 4 + 1) unsupported()
            val encoding = body[0].toInt() and 255
            if (encoding == 1 && (body.size < 3 ||
                !(body[1] == 0xfe.toByte() && body[2] == 0xff.toByte() || body[1] == 0xff.toByte() && body[2] == 0xfe.toByte()))) invalid()
            val charset = when (encoding) { 0 -> Charsets.ISO_8859_1
                1 -> Charsets.UTF_16
                2 -> Charsets.UTF_16BE
                3 -> Charsets.UTF_8
                else -> unsupported() }
            val decoded = decode(body.copyOfRange(1, body.size), charset).removeSuffix("\u0000")
            val values = decoded.split('\u0000').map { if (encoding == 1) it.removePrefix("\ufeff") else it }
            if (values.size > FileOperationLimits.ARTIST_VALUES || values.any { it.length > FileOperationLimits.FIELD_CHARACTERS }) unsupported()
            return values
        }
        private fun decode(bytes: ByteArray, charset: Charset): String = try {
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (failure: java.nio.charset.CharacterCodingException) { throw FileOperationException(FileOperationIssue.INVALID_FORMAT, failure) }
        private fun validateUtf8(input: ByteBuffer) {
            val decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val output = CharBuffer.allocate(4096)
            try {
                while (true) {
                    val result = decoder.decode(input, output, true)
                    if (result.isError) result.throwException()
                    output.clear()
                    if (result.isUnderflow) break
                }
                val result = decoder.flush(output)
                if (result.isError) result.throwException()
            } catch (failure: java.nio.charset.CharacterCodingException) {
                throw FileOperationException(FileOperationIssue.INVALID_FORMAT, failure)
            }
        }
        private fun readRequired(input: InputStream, size: Int, cancellation: CancellationSignal): ByteArray {
            val bytes = ByteArray(size)
            var position = 0
            while (position < size) {
                cancellation.throwIfCanceled()
                val read = input.read(bytes, position, size - position)
                if (read < 0) invalid()
                if (read == 0) throw FileOperationException(FileOperationIssue.SOURCE_UNAVAILABLE)
                position += read
            }
            return bytes
        }
        private fun ByteArrayOutputStream.putLittle(value: Int) { repeat(4) { write(value ushr (it * 8)) } }
        private fun invalid(): Nothing = throw FileOperationException(FileOperationIssue.INVALID_FORMAT)
        private fun unsupported(): Nothing = throw FileOperationException(FileOperationIssue.UNSUPPORTED_FORMAT)
    }
}
