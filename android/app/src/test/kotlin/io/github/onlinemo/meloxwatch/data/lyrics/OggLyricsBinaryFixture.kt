package io.github.onlinemo.meloxwatch.data.lyrics

import java.io.ByteArrayOutputStream

/** Metadata-only Ogg v0 layouts. Bitwise CRC is independent of the production lookup table. */
internal object OggLyricsBinaryFixture {
    private val binary = EmbeddedLyricsBinaryFixture
    const val SERIAL = 0xa1b2c3d4L

    fun page(
        data: ByteArray,
        laces: IntArray = packetLaces(data.size),
        flags: Int = 0,
        granule: Long = 0L,
        serial: Long = SERIAL,
        sequence: Long = 0L,
        version: Int = 0,
    ): ByteArray {
        require(laces.size <= 255 && laces.all { it in 0..255 } && laces.sum() == data.size)
        val header = ByteArray(27)
        "OggS".toByteArray(Charsets.US_ASCII).copyInto(header)
        header[4] = version.toByte()
        header[5] = flags.toByte()
        for (index in 0..7) header[6 + index] = (granule ushr (index * 8)).toByte()
        binary.uint32(serial, littleEndian = true).copyInto(header, 14)
        binary.uint32(sequence, littleEndian = true).copyInto(header, 18)
        header[26] = laces.size.toByte()
        return checksum(binary.join(header, ByteArray(laces.size) { laces[it].toByte() }, data))
    }

    fun checksum(page: ByteArray): ByteArray = page.copyOf().also { bytes ->
        for (index in 22..25) bytes[index] = 0
        var crc = 0
        for (byte in bytes) {
            crc = crc xor ((byte.toInt() and 0xff) shl 24)
            repeat(8) { crc = (crc shl 1) xor (if (crc < 0) 0x04c11db7 else 0) }
        }
        for (index in 0..3) bytes[22 + index] = (crc ushr (index * 8)).toByte()
    }

    fun packetLaces(length: Int): IntArray {
        require(length >= 0)
        return IntArray(length / 255 + 1) { index -> if (index < length / 255) 255 else length % 255 }
    }

    fun vorbisId(
        version: Long = 0L,
        channels: Int = 2,
        sampleRate: Long = 44_100L,
        blocks: Int = 0xb8,
        framing: Int = 1,
    ): ByteArray = binary.join(
        byteArrayOf(1), "vorbis".toByteArray(Charsets.US_ASCII), binary.uint32(version, true),
        byteArrayOf(channels.toByte()), binary.uint32(sampleRate, true), ByteArray(12),
        byteArrayOf(blocks.toByte(), framing.toByte()),
    )

    fun opusId(
        version: Int = 1,
        channels: Int = 2,
        preSkip: Int = 312,
        inputRate: Long = 48_000L,
        gain: Int = 0,
        family: Int = 0,
        streams: Int = 1,
        coupled: Int = 0,
        mapping: ByteArray = ByteArray(channels) { it.toByte() },
        extra: ByteArray = ByteArray(0),
    ): ByteArray = binary.join(
        "OpusHead".toByteArray(Charsets.US_ASCII), byteArrayOf(version.toByte(), channels.toByte()),
        byteArrayOf(preSkip.toByte(), (preSkip ushr 8).toByte()), binary.uint32(inputRate, true),
        byteArrayOf(gain.toByte(), (gain ushr 8).toByte(), family.toByte()),
        if (family == 0) ByteArray(0) else binary.join(byteArrayOf(streams.toByte(), coupled.toByte()), mapping), extra,
    )

    fun opusTags(fields: List<ByteArray>, vendor: ByteArray = "fixture".toByteArray(), tail: ByteArray = ByteArray(0)): ByteArray =
        binary.join("OpusTags".toByteArray(Charsets.US_ASCII), binary.comments(fields, vendor), tail)

    fun identification(id: ByteArray, sequence: Long = 0L): ByteArray = page(id, flags = 2, sequence = sequence)

    fun metadata(
        id: ByteArray,
        comments: ByteArray,
        opus: Boolean = false,
        segmentsPerPage: Int = 255,
        firstSequence: Long = 0L,
        finalPacket: ByteArray? = null,
    ): ByteArray {
        require(segmentsPerPage in 1..255)
        val allLaces = packetLaces(comments.size)
        return ByteArrayOutputStream().use { output ->
            output.write(identification(id, firstSequence))
            var laceOffset = 0
            var dataOffset = 0
            var pageIndex = 1L
            while (laceOffset < allLaces.size) {
                val count = minOf(segmentsPerPage, allLaces.size - laceOffset)
                val laces = allLaces.copyOfRange(laceOffset, laceOffset + count)
                val length = laces.sum()
                val last = laceOffset + count == allLaces.size
                val suffix = if (last) finalPacket else null
                val data = comments.copyOfRange(dataOffset, dataOffset + length)
                output.write(page(
                    if (suffix == null) data else binary.join(data, suffix),
                    if (suffix == null) laces else laces + packetLaces(suffix.size),
                    flags = if (pageIndex == 1L) 0 else 1,
                    granule = if (opus && !last) -1L else 0L,
                    sequence = (firstSequence + pageIndex) and 0xffff_ffffL,
                ))
                laceOffset += count
                dataOffset += length
                pageIndex++
            }
            output.toByteArray()
        }
    }

    fun vorbis(fields: List<ByteArray>, segmentsPerPage: Int = 255): ByteArray =
        metadata(vorbisId(), binary.vorbisPacket(fields), segmentsPerPage = segmentsPerPage)

    fun opus(fields: List<ByteArray>, segmentsPerPage: Int = 255, tail: ByteArray = ByteArray(0)): ByteArray =
        metadata(opusId(), opusTags(fields, tail = tail), opus = true, segmentsPerPage = segmentsPerPage)
}
