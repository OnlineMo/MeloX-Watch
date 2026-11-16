package io.github.onlinemo.meloxwatch.data.lyrics

/**
 * Read-only headers of the first, unmultiplexed Vorbis/Opus logical stream. LYRICS keeps the
 * native FLAC/Vorbis text contract; this does not validate setup/audio packets or later chains.
 */
internal class OggEmbeddedLyricsReader(
    private val input: EmbeddedLyricsInput,
    private val budget: EmbeddedLyricsBudget,
    private val builder: EmbeddedLyricsDocumentBuilder,
    private val collector: EmbeddedLyricsCollector,
) {
    private val packet = OggLyricsPacketInput(input, budget)
    private val key = ByteArray(budget.limits.maxVorbisKeyBytes)

    suspend fun read(prefix: ByteArray) {
        packet.identificationPage(prefix)
        val container = identification()
        packet.beginComments(container)
        if (container == EmbeddedLyricsContainer.OGG_VORBIS) {
            if (packet.byte() != 3) embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_CONTAINER)
            signature("vorbis")
        } else signature("OpusTags")

        packet.skip(packet.uint32())
        val count = packet.uint32()
        if (count > budget.limits.maxStructures.toLong()) embeddedLyricsFailure(EmbeddedLyricsIssue.TOO_MANY_STRUCTURES)
        packet.requireLength(count * 4L + if (container == EmbeddedLyricsContainer.OGG_VORBIS) 1L else 0L)
        var index = 0L
        while (index < count) {
            input.check()
            budget.structure()
            val length = packet.uint32()
            packet.requireLength(length)
            val (lyrics, valueLength) = readKey(length)
            if (lyrics) {
                val ordinal = budget.candidate()
                // The shared payload limit makes this conversion safe; packet input also rejects
                // impossible physical spans before any candidate-sized allocation.
                if (valueLength > Int.MAX_VALUE.toLong()) embeddedLyricsFailure(EmbeddedLyricsIssue.LYRICS_INPUT_TOO_LARGE)
                val bytes = packet.payload(valueLength.toInt())
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
            } else packet.skip(valueLength)
            index++
        }

        if (container == EmbeddedLyricsContainer.OGG_VORBIS) {
            if (packet.byte() != 1) embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_CONTAINER)
            packet.finish(requirePageBoundary = false)
        } else {
            // OpusTags has no Vorbis framing bit. RFC 7845 permits binary trailing data/padding.
            packet.skipTail()
            packet.finish(requirePageBoundary = true)
        }
    }

    private fun identification(): EmbeddedLyricsContainer {
        if (packet.identificationMatches(1, "vorbis")) {
            if (packet.identificationSize != 30 || packet.identificationByte(0) != 1) invalid()
            if (packet.identificationUint32(7) != 0L) embeddedLyricsFailure(EmbeddedLyricsIssue.UNSUPPORTED_CONTAINER)
            if (packet.identificationByte(11) == 0 || packet.identificationUint32(12) == 0L) invalid()
            val blocks = packet.identificationByte(28)
            val small = blocks and 0x0f
            val large = blocks ushr 4
            if (small !in 6..13 || large !in 6..13 || small > large || (packet.identificationByte(29) and 1) == 0) invalid()
            return EmbeddedLyricsContainer.OGG_VORBIS
        }
        if (!packet.identificationMatches(0, "OpusHead")) embeddedLyricsFailure(EmbeddedLyricsIssue.UNSUPPORTED_CONTAINER)
        if (packet.identificationSize < 19) invalid()
        val version = packet.identificationByte(8)
        if (version > 15) embeddedLyricsFailure(EmbeddedLyricsIssue.UNSUPPORTED_CONTAINER)
        val channels = packet.identificationByte(9)
        if (channels == 0) invalid()
        val mapping = packet.identificationByte(18)
        val required = when (mapping) {
            0 -> 19
            1, 255 -> 21 + channels
            else -> embeddedLyricsFailure(EmbeddedLyricsIssue.UNSUPPORTED_CONTAINER)
        }
        if (packet.identificationSize < required || version <= 1 && packet.identificationSize != required) invalid()
        if (mapping == 0) {
            if (channels > 2) invalid()
        } else {
            if (mapping == 1 && channels > 8) invalid()
            val streams = packet.identificationByte(19)
            val coupled = packet.identificationByte(20)
            val codedChannels = streams + coupled
            if (streams == 0 || coupled > streams || codedChannels > 255) invalid()
            for (index in 0 until channels) {
                input.check()
                val mapped = packet.identificationByte(21 + index)
                if (mapped != 255 && mapped >= codedChannels) invalid()
            }
        }
        // Input rate is informational. Pre-skip, output gain and granules never infer lyric times.
        return EmbeddedLyricsContainer.OGG_OPUS
    }

    private suspend fun signature(expected: String) {
        for (character in expected) if (packet.byte() != character.code) invalid()
    }

    /** Only the exact ASCII-insensitive LYRICS key is selected; unrelated values stay undecoded. */
    private suspend fun readKey(fieldLength: Long): Pair<Boolean, Long> {
        var remaining = fieldLength
        var length = 0
        while (remaining != 0L) {
            val byte = packet.byte()
            remaining--
            if (byte == '='.code) {
                if (length == 0) invalid()
                val expected = "LYRICS"
                val lyrics = length == expected.length && (0 until length).all {
                    val actual = key[it].toInt() and 0xff
                    val upper = if (actual in 'a'.code..'z'.code) actual - 32 else actual
                    upper == expected[it].code
                }
                return lyrics to remaining
            }
            if (byte !in 0x20..0x7d || length == key.size) invalid()
            key[length++] = byte.toByte()
        }
        invalid()
    }

    private fun invalid(): Nothing = embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_CONTAINER)
}
