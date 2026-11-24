package io.github.onlinemo.meloxwatch.playback.resources.metadata

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** 按格式字段自生成小样本；未导入录音，也不以 extractor sample 输出冒充设备解码结果。 */
internal object AudioMetadataFixtures {
    val pcm: ByteArray = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
        repeat(4) {
            putShort(1000)
            putShort(-1000)
        }
    }.array()

    fun wave(extraChunk: ByteArray = byteArrayOf()): ByteArray {
        val body = "WAVE".toByteArray() + extraChunk +
            chunk("fmt ", le16(1) + le16(2) + le32(44_100) + le32(176_400) + le16(4) + le16(16)) +
            chunk("data", pcm)
        return "RIFF".toByteArray() + le32(body.size) + body
    }

    fun mp3(prefix: ByteArray = id3Title(), frames: Int = 8): ByteArray {
        val frame = ByteArray(417).apply {
            this[0] = 0xff.toByte()
            this[1] = 0xfb.toByte()
            this[2] = 0x90.toByte()
        }
        return prefix + concatenate(List(frames) { frame })
    }

    fun id3Title(version: Int = 4, extended: Boolean = false, footer: Boolean = false, unsync: Boolean = false): ByteArray {
        val original = byteArrayOf(0) + "Guarded sample".toByteArray(Charsets.ISO_8859_1) +
            if (unsync) byteArrayOf(0xff.toByte()) else byteArrayOf()
        val payload = if (unsync && version == 4) escape(original) else original
        val title = id3Frame(if (version == 2) "TT2" else "TIT2", payload, version, if (unsync && version == 4) 2 else 0)
        val extension = if (!extended) byteArrayOf() else if (version == 3) {
            be32(6) + ByteArray(6)
        } else {
            syncSafe(6) + byteArrayOf(1, 0)
        }
        val flags = (if (extended) 64 else 0) or (if (footer) 16 else 0) or
            (if (unsync && version < 4) 128 else 0)
        val rawBody = extension + title
        val body = if (unsync && version < 4) escape(rawBody) else rawBody
        val size = body.size + if (footer) 10 else 0
        val header = id3Header(size, version, flags)
        val tail = if (footer) "3DI".toByteArray() + header.copyOfRange(3, 10) else byteArrayOf()
        return header + body + tail
    }

    fun id3Header(size: Int, version: Int = 4, flags: Int = 0): ByteArray =
        "ID3".toByteArray() + byteArrayOf(version.toByte(), 0, flags.toByte()) + syncSafe(size)

    fun id3Frame(id: String, payload: ByteArray, version: Int = 4, flags: Int = 0): ByteArray {
        val size = if (version == 2) be24(payload.size) else if (version == 4) syncSafe(payload.size) else be32(payload.size)
        return id.toByteArray() + size + (if (version == 2) byteArrayOf() else be16(flags)) + payload
    }

    fun nestedId3(depth: Int): ByteArray {
        var frame = id3Frame("TIT2", byteArrayOf(0) + "Nested".toByteArray())
        repeat(depth) {
            frame = id3Frame("CHAP", byteArrayOf(99, 0) + be32(0) + be32(1000) + be32(-1) + be32(-1) + frame)
        }
        return id3Header(frame.size) + frame
    }

    fun manyId3Values(count: Int): ByteArray {
        val values = concatenate(List(count) { byteArrayOf(118, 0) })
        val frame = id3Frame("TXXX", byteArrayOf(3) + "KEY\u0000".toByteArray() + values)
        return id3Header(frame.size) + frame
    }

    private fun escape(bytes: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        for (value in bytes) {
            write(value.toInt() and 255)
            if (value.toInt() and 255 == 255) write(0)
        }
    }.toByteArray()

    fun flac(extra: ByteArray? = null): ByteArray {
        val info = flacInfo(48)
        val header = block(0, info, last = extra == null)
        return "fLaC".toByteArray() + header + (extra ?: byteArrayOf()) +
            concatenate(List(3) { flacFrame(it) })
    }

    fun flacInfo(samples: Long): ByteArray {
        val packed = (44_100L shl 44) or (15L shl 36) or samples
        return be16(16) + be16(16) + be24(12) + be24(12) + be64(packed) + ByteArray(16)
    }

    fun flacFrame(number: Int): ByteArray {
        val header = byteArrayOf(0xff.toByte(), 0xf8.toByte(), 0x69, 0x08, number.toByte(), 15)
        val checkedHeader = header + byteArrayOf(crc(header, 8, 0x07).toByte())
        val body = checkedHeader + byteArrayOf(0) + be16(1234)
        return body + be16(crc(body, 16, 0x8005))
    }

    fun block(type: Int, payload: ByteArray, last: Boolean = true): ByteArray =
        byteArrayOf((type or if (last) 128 else 0).toByte()) + be24(payload.size) + payload

    fun comments(values: List<String> = listOf("TITLE=Guarded sample", "REPLAYGAIN_TRACK_GAIN=-3 dB")): ByteArray {
        val vendor = "MeloX fixtures".toByteArray()
        return le32(vendor.size) + vendor + le32(values.size) + concatenate(values.map {
            val text = it.toByteArray()
            le32(text.size) + text
        })
    }

    fun picture(data: ByteArray = png(), declaredSize: Int = data.size): ByteArray {
        val mime = "image/png".toByteArray()
        return be32(3) + be32(mime.size) + mime + be32(0) + be32(1) + be32(1) + be32(32) + be32(0) +
            be32(declaredSize) + data
    }

    private fun png(): ByteArray {
        val header = be32(1) + be32(1) + byteArrayOf(8, 6, 0, 0, 0)
        val data = ByteArrayOutputStream()
        DeflaterOutputStream(data).use { it.write(byteArrayOf(0, 10, 20, 30, 0xff.toByte())) }
        fun pngChunk(type: String, content: ByteArray): ByteArray {
            val named = type.toByteArray() + content
            return be32(content.size) + named + be32(CRC32().apply { update(named) }.value.toInt())
        }
        return byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10) +
            pngChunk("IHDR", header) + pngChunk("IDAT", data.toByteArray()) + pngChunk("IEND", byteArrayOf())
    }

    val aacSample: ByteArray = byteArrayOf(0x21, 0x10, 0x04, 0x60, 0x8c.toByte(), 0x1c)

    fun adts(crcHeader: Boolean = false, inband: ByteArray = byteArrayOf(), frames: Int = 40): ByteArray {
        val headerSize = if (crcHeader) 9 else 7
        val frameSize = headerSize + aacSample.size
        val header = byteArrayOf(
            0xff.toByte(), if (crcHeader) 0xf0.toByte() else 0xf1.toByte(), 0x50,
            (0x80 or (frameSize ushr 11)).toByte(), (frameSize ushr 3).toByte(),
            (((frameSize and 7) shl 5) or 31).toByte(), 0xfc.toByte(),
        ) + if (crcHeader) byteArrayOf(0, 0) else byteArrayOf()
        val frame = header + aacSample
        return concatenate(List(frames) { index -> frame + if (index == 3) inband else byteArrayOf() })
    }

    fun opusPackets(tags: ByteArray = comments(), emptyGap: Boolean = false): ByteArray {
        val head = "OpusHead".toByteArray() + byteArrayOf(1, 2) + le16(0) + le32(48_000) + le16(0) + byteArrayOf(0)
        val tagPacket = "OpusTags".toByteArray() + tags
        val audio = byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte())
        val gap = if (emptyGap) page(byteArrayOf(), 0, 1, 0, byteArrayOf()) else byteArrayOf()
        val shift = if (emptyGap) 1 else 0
        return page(head, 2, 0, 0) + gap + page(tagPacket, 0, 1 + shift, 0) +
            page(audio, 0, 2 + shift, 960) + page(audio, 4, 3 + shift, 1920)
    }

    fun vorbisPackets(setup: ByteArray = vorbisSetup(), commentBody: ByteArray = comments()): ByteArray {
        val head = byteArrayOf(1) + "vorbis".toByteArray() + le32(0) + byteArrayOf(1) + le32(44_100) +
            ByteArray(12) + byteArrayOf(0x66, 1)
        val comment = byteArrayOf(3) + "vorbis".toByteArray() + commentBody + byteArrayOf(1)
        return page(head, 2, 0, 0) + page(comment, 0, 1, 0) + page(setup, 0, 2, 0) +
            page(byteArrayOf(0, 0), 0, 3, 0) + page(byteArrayOf(0, 0), 4, 4, 32)
    }

    fun oggFlac(): ByteArray {
        val head = byteArrayOf(127) + "FLAC".toByteArray() + byteArrayOf(1, 0) + be16(1) +
            "fLaC".toByteArray() + block(0, flacInfo(32))
        return page(head, 2, 0, 0) + page(flacFrame(0), 0, 1, 16) + page(flacFrame(1), 4, 2, 32)
    }

    fun oversizedContinuedOpusTag(): ByteArray {
        val head = "OpusHead".toByteArray() + byteArrayOf(1, 2) + le16(0) + le32(48_000) + le16(0) + byteArrayOf(0)
        val pieces = List(17) { index ->
            val body = ByteArray(65_025)
            if (index == 0) "OpusTags".toByteArray().copyInto(body)
            page(body, if (index == 0) 0 else 1, index + 1, 0, ByteArray(255) { 255.toByte() })
        }
        return page(head, 2, 0, 0) + concatenate(pieces)
    }

    fun emptyOpusPages(count: Int): ByteArray {
        val head = "OpusHead".toByteArray() + byteArrayOf(1, 2) + le16(0) + le32(48_000) + le16(0) + byteArrayOf(0)
        return page(head, 2, 0, 0) + concatenate(List(count) { page(byteArrayOf(), 0, it + 1, 0, byteArrayOf()) })
    }

    fun page(body: ByteArray, flags: Int, sequence: Int, granule: Long, laces: ByteArray = pageLacing(body.size)): ByteArray {
        check(laces.size <= 255 && laces.sumOf { it.toInt() and 255 } == body.size)
        val header = ByteBuffer.allocate(27).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("OggS".toByteArray())
            put(0)
            put(flags.toByte())
            putLong(granule)
            putInt(42)
            putInt(sequence)
            putInt(0)
            put(laces.size.toByte())
        }.array()
        val result = header + laces + body
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putInt(22, crc(result, 32, 0x04c11db7))
        return result
    }

    private fun pageLacing(size: Int): ByteArray = ByteArray(size / 255 + 1) { index ->
        if (index == size / 255) (size % 255).toByte() else 255.toByte()
    }

    fun vorbisSetup(entries: Int = 1): ByteArray {
        val bits = LittleWriter()
        bits.write(0x564342, 24)
        bits.write(1, 16)
        bits.write(entries, 24)
        if (entries == 1) {
            bits.write(0, 1)
            bits.write(0, 1)
            bits.write(0, 5)
            bits.write(0, 4)
            bits.write(0, 6)
            bits.write(0, 16)
            bits.write(0, 6)
            bits.write(1, 16)
            bits.write(0, 5)
            bits.write(0, 2)
            bits.write(5, 4)
            bits.write(0, 6)
            bits.write(1, 16)
            bits.write(0, 24)
            bits.write(32, 24)
            bits.write(31, 24)
            bits.write(0, 6)
            bits.write(0, 8)
            bits.write(0, 3)
            bits.write(0, 1)
            bits.write(0, 6)
            bits.write(0, 16)
            bits.write(0, 1)
            bits.write(0, 1)
            bits.write(0, 2)
            repeat(3) { bits.write(0, 8) }
            bits.write(0, 6)
            bits.write(0, 1)
            bits.write(0, 16)
            bits.write(0, 16)
            bits.write(0, 8)
            bits.write(1, 1)
        }
        return byteArrayOf(5) + "vorbis".toByteArray() + byteArrayOf(0) + bits.bytes()
    }

    private class LittleWriter {
        private val values = ArrayList<Int>()
        fun write(value: Int, bits: Int) {
            repeat(bits) { values += (value ushr it) and 1 }
        }
        fun bytes(): ByteArray = ByteArray((values.size + 7) / 8).also { bytes ->
            values.forEachIndexed { index, value -> bytes[index / 8] = (bytes[index / 8].toInt() or (value shl (index and 7))).toByte() }
        }
    }

    private fun crc(bytes: ByteArray, width: Int, polynomial: Int): Int {
        var value = 0
        val mask = if (width == 32) -1 else (1 shl width) - 1
        val high = 1 shl (width - 1)
        for (byte in bytes) {
            value = value xor ((byte.toInt() and 255) shl (width - 8))
            repeat(8) { value = ((value shl 1) xor if (value and high != 0) polynomial else 0) and mask }
        }
        return value
    }

    fun chunk(type: String, data: ByteArray): ByteArray = type.toByteArray() + le32(data.size) + data +
        if (data.size and 1 != 0) byteArrayOf(0) else byteArrayOf()
    fun box(type: String, data: ByteArray): ByteArray = be32(data.size + 8) + type.toByteArray() + data
    fun concatenate(values: List<ByteArray>): ByteArray = ByteArrayOutputStream().apply { values.forEach(::write) }.toByteArray()
    fun le16(value: Int): ByteArray = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
    fun le32(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    fun be16(value: Int): ByteArray = ByteBuffer.allocate(2).putShort(value.toShort()).array()
    fun be24(value: Int): ByteArray = byteArrayOf((value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
    fun be32(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()
    fun be64(value: Long): ByteArray = ByteBuffer.allocate(8).putLong(value).array()
    fun syncSafe(value: Int): ByteArray = byteArrayOf(
        ((value ushr 21) and 127).toByte(), ((value ushr 14) and 127).toByte(),
        ((value ushr 7) and 127).toByte(), (value and 127).toByte(),
    )
}
