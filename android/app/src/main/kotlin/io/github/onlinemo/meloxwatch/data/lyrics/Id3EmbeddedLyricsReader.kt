package io.github.onlinemo.meloxwatch.data.lyrics

/** ID3v2.3/v2.4 only. Encoded/transformed tags are not guessed or applied to playback bytes. */
internal class Id3EmbeddedLyricsReader(
    private val input: EmbeddedLyricsInput,
    private val budget: EmbeddedLyricsBudget,
    private val builder: EmbeddedLyricsDocumentBuilder,
    private val collector: EmbeddedLyricsCollector,
) {
    suspend fun read(prefix: ByteArray) {
        val header = ByteArray(10)
        prefix.copyInto(header)
        input.exactly(header, offset = 4, length = 6)
        val version = header[3].toInt() and 0xff
        if (version != 3 && version != 4 || header[4] != 0.toByte()) {
            embeddedLyricsFailure(EmbeddedLyricsIssue.UNSUPPORTED_ID3_VERSION)
        }
        if (header[5] != 0.toByte()) embeddedLyricsFailure(EmbeddedLyricsIssue.UNSUPPORTED_ID3_TAG_FLAGS)
        val tag = input.segment(synchsafe(header, 6).toLong())
        val frameHeader = ByteArray(10)
        while (tag.remaining != 0L) {
            input.check()
            if (tag.remaining < 10L) {
                tag.skip(requireZero = true)
                break
            }
            budget.structure()
            tag.read(frameHeader)
            if (frameHeader[0] == 0.toByte()) {
                if (frameHeader.any { it != 0.toByte() }) embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_ID3_FRAME)
                tag.skip(requireZero = true)
                break
            }
            for (index in 0..3) {
                val character = frameHeader[index].toInt() and 0xff
                if (character !in 'A'.code..'Z'.code && character !in '0'.code..'9'.code) {
                    embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_ID3_FRAME)
                }
            }
            val size = if (version == 4) synchsafe(frameHeader, 4).toLong() else unsigned32(frameHeader, 4)
            if (size == 0L) embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_ID3_FRAME)
            val frame = tag.take(size)
            val field = when {
                identifier(frameHeader, "USLT") -> EmbeddedLyricsField.ID3_USLT
                identifier(frameHeader, "SYLT") -> EmbeddedLyricsField.ID3_SYLT
                else -> null
            }
            if (field == null) {
                frame.skip()
                continue
            }
            val ordinal = budget.candidate()
            val status = frameHeader[8].toInt() and 0xff
            val format = frameHeader[9].toInt() and 0xff
            val statusMask = if (version == 3) 0xe0 else 0x70
            if ((status and statusMask.inv()) != 0 || format != 0) {
                budget.payload(size.toInt())
                collector.rejected(EmbeddedLyricsIssue.UNSUPPORTED_ID3_FRAME_FLAGS, ordinal)
                frame.skip()
                continue
            }
            val bytes = frame.payload(size.toInt(), budget)
            val parsed = try {
                if (field == EmbeddedLyricsField.ID3_USLT) builder.uslt(bytes, version) else builder.sylt(bytes, version)
            } catch (error: EmbeddedLyricsReadException) {
                input.check()
                if (!error.issue.isCandidateFailure()) throw error
                collector.rejected(error.issue, ordinal)
                continue
            }
            input.check()
            budget.charge(512L + parsed.descriptor.length.toLong() * 2L)
            val source = EmbeddedLyricsSource(
                EmbeddedLyricsContainer.MP3_ID3, field, ordinal, parsed.language, parsed.descriptor,
                parsed.wireEncoding, parsed.contentSha256,
            )
            collector.add(EmbeddedLyricsCandidate(source, parsed.document))
        }
    }

    private fun identifier(header: ByteArray, expected: String): Boolean = (0..3).all {
        (header[it].toInt() and 0xff) == expected[it].code
    }

    private fun synchsafe(bytes: ByteArray, start: Int): Int {
        var value = 0
        for (index in start until start + 4) {
            val byte = bytes[index].toInt() and 0xff
            if ((byte and 0x80) != 0) embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_CONTAINER)
            value = (value shl 7) or byte
        }
        return value
    }

    private fun unsigned32(bytes: ByteArray, start: Int): Long {
        var value = 0L
        for (index in start until start + 4) value = (value shl 8) or (bytes[index].toLong() and 0xffL)
        return value
    }
}
