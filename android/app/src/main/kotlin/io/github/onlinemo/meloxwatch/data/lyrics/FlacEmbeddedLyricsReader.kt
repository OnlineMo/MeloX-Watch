package io.github.onlinemo.meloxwatch.data.lyrics

/** Native FLAC metadata and a separately demuxed Vorbis comment packet; no Ogg page scanning. */
internal class FlacEmbeddedLyricsReader(
    private val input: EmbeddedLyricsInput,
    private val budget: EmbeddedLyricsBudget,
    private val builder: EmbeddedLyricsDocumentBuilder,
    private val collector: EmbeddedLyricsCollector,
) {
    private val key = ByteArray(budget.limits.maxVorbisKeyBytes)

    suspend fun flac() {
        val header = ByteArray(4)
        var first = true
        var commentsSeen = false
        while (true) {
            input.check()
            budget.structure()
            input.exactly(header)
            val type = header[0].toInt() and 0x7f
            val last = (header[0].toInt() and 0x80) != 0
            val length = ((header[1].toInt() and 0xff) shl 16) or
                ((header[2].toInt() and 0xff) shl 8) or (header[3].toInt() and 0xff)
            if (type > 6 || first && (type != 0 || length != 34) || !first && type == 0) {
                embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_CONTAINER)
            }
            val block = input.segment(length.toLong())
            if (type == 4) {
                if (commentsSeen) embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_CONTAINER)
                commentsSeen = true
                comments(block, EmbeddedLyricsContainer.FLAC, framed = false)
            } else block.skip()
            first = false
            if (last) return
        }
    }

    suspend fun vorbisPacket(packet: EmbeddedLyricsSegment) {
        val identification = ByteArray(7)
        packet.read(identification)
        val expected = byteArrayOf(
            3, 'v'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(),
            'b'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte(),
        )
        if (!identification.contentEquals(expected)) embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_CONTAINER)
        comments(packet, EmbeddedLyricsContainer.VORBIS_COMMENT_PACKET, framed = true)
    }

    private suspend fun comments(block: EmbeddedLyricsSegment, container: EmbeddedLyricsContainer, framed: Boolean) {
        val vendorLength = block.uint32(littleEndian = true)
        block.skip(vendorLength)
        val count = block.uint32(littleEndian = true)
        if (count > budget.limits.maxStructures.toLong()) embeddedLyricsFailure(EmbeddedLyricsIssue.TOO_MANY_STRUCTURES)
        val framingBytes = if (framed) 1L else 0L
        if (block.remaining < framingBytes || count > (block.remaining - framingBytes) / 4L) {
            embeddedLyricsFailure(EmbeddedLyricsIssue.TRUNCATED_METADATA)
        }
        var index = 0L
        while (index < count) {
            input.check()
            budget.structure()
            val field = block.take(block.uint32(littleEndian = true))
            val lyrics = readKey(field)
            if (lyrics) {
                val ordinal = budget.candidate()
                val bytes = field.payload(field.remaining.toInt(), budget)
                val (hash, document) = builder.vorbis(bytes)
                input.check()
                budget.charge(512L)
                collector.add(EmbeddedLyricsCandidate(
                    EmbeddedLyricsSource(
                        container, EmbeddedLyricsField.VORBIS_LYRICS, ordinal,
                        null, null, EmbeddedLyricsWireEncoding.UTF8, hash,
                    ),
                    document,
                ))
            } else field.skip()
            index++
        }
        if (framed) {
            if (block.remaining != 1L || block.byte() != 1) embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_CONTAINER)
        } else if (block.remaining != 0L) embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_CONTAINER)
    }

    /** Only the exact ASCII-insensitive LYRICS key maps to lyrics; aliases are not guessed. */
    private suspend fun readKey(field: EmbeddedLyricsSegment): Boolean {
        var length = 0
        while (field.remaining != 0L) {
            input.check()
            val byte = field.byte()
            if (byte == '='.code) {
                if (length == 0) embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_CONTAINER)
                if (length != 6) return false
                val expected = "LYRICS"
                return (0 until length).all {
                    val actual = key[it].toInt() and 0xff
                    val upper = if (actual in 'a'.code..'z'.code) actual - 32 else actual
                    upper == expected[it].code
                }
            }
            if (byte !in 0x20..0x7d || length == key.size) embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_CONTAINER)
            key[length++] = byte.toByte()
        }
        embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_CONTAINER)
    }
}
