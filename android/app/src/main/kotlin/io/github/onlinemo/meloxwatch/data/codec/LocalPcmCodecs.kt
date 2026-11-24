package io.github.onlinemo.meloxwatch.data.codec

/** Select by actual signatures. An extension, reported MIME or indexed title never qualifies audio. */
internal object LocalPcmCodecs {
    fun open(input: CodecInput, beforeDecode: () -> Unit = {}): PcmFrameDecoder? {
        input.checkCancellation()
        input.seek(0)
        val prefix = prefix(input) ?: return null
        return when (prefix.fourCc()) {
            "DSD " -> DsdContainer.dsf(input).let { beforeDecode(); DsdPcmDecoder(input, it) }
            "FRM8" -> DsdContainer.dff(input).let { beforeDecode(); DsdPcmDecoder(input, it) }
            "MAC " -> ApeContainer.read(input).let { beforeDecode(); ApePcmDecoder(input, it) }
            else -> if (prefix[0] == 'I'.code.toByte() && prefix[1] == 'D'.code.toByte() && prefix[2] == '3'.code.toByte()) {
                apeAfterId3(input, prefix, beforeDecode)
            } else null
        }
    }

    private fun apeAfterId3(input: CodecInput, prefix: ByteArray, beforeDecode: () -> Unit): PcmFrameDecoder? {
        if (prefix[3].toInt() !in 3..4) return null
        val tail = input.fixed(6)
        var size = 0L
        for (index in 2..5) {
            val value = tail[index].toInt() and 255
            codecRequire(value and 128 == 0)
            size = (size shl 7) or value.toLong()
        }
        // This is a sniff for a local APE header, not a new large ID3 parser or a linear audio walk.
        if (size > 1024 * 1024 || input.length <= 0) return null
        val footer = if (prefix[3].toInt() == 4 && tail[1].toInt() and 16 != 0) 10L else 0L
        val start = codecAdd(codecAdd(10, size), footer)
        if (start > input.length - 4) return null
        input.moveTo(start)
        return if (input.fixed(4).fourCc() == "MAC ") ApeContainer.read(input, start).let {
            beforeDecode(); ApePcmDecoder(input, it)
        } else null
    }

    private fun prefix(input: CodecInput): ByteArray? {
        val bytes = ByteArray(4)
        var count = 0
        while (count < bytes.size) {
            input.checkCancellation()
            val read = input.read(bytes, count, bytes.size - count)
            if (read < 0) return null
            codecRequire(read > 0)
            count += read
        }
        return bytes
    }
}
