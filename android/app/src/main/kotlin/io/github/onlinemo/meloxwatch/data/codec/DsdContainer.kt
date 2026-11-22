package io.github.onlinemo.meloxwatch.data.codec

internal enum class DsdBitOrder { LSB_FIRST, MSB_FIRST }

internal data class DsdLayout(
    val container: String,
    val channels: Int,
    val sampleRate: Int,
    val bitSamples: Long,
    val audioOffset: Long,
    val audioBytes: Long,
    val bitOrder: DsdBitOrder,
    val channelBlockBytes: Int?,
    val tagOffset: Long? = null,
) {
    val format: LocalCodecFormat get() = LocalCodecFormat(container, "DSD", sampleRate, 1, channels,
        PcmFormat(88_200, channels), bitSamples, (bitSamples - 1) / 32 + 1, tagOffset = tagOffset)
}

/** Structural extraction reads fixed headers and seeks over bounded-count chunks; audio stays on disk. */
internal object DsdContainer {
    private const val DSD64 = 2_822_400
    private const val MAX_CHUNKS = 128

    fun dsf(input: CodecInput): DsdLayout {
        input.moveTo(0)
        val header = input.fixed(28)
        codecRequire(header.fourCc() == "DSD " && header.u64(4, true) == 28L)
        codecRequire(header.u64(12, true) == input.length)
        val metadata = header.u64(20, true)
        val fmt = input.fixed(52)
        codecRequire(fmt.fourCc() == "fmt " && fmt.u64(4, true) == 52L)
        codecRequire(fmt.u32(12, true) == 1L && fmt.u32(16, true) == 0L,
            CodecReadIssue.UNSUPPORTED_VERSION)
        val channelType = fmt.u32(20, true)
        val channels = fmt.u32(24, true)
        codecRequire(channels in 1L..2L && channelType == channels, CodecReadIssue.UNSUPPORTED_CHANNELS)
        val rate = fmt.u32(28, true)
        codecRequire(rate == DSD64.toLong(), CodecReadIssue.UNSUPPORTED_RATE)
        val bits = fmt.u32(32, true)
        codecRequire(bits == 1L || bits == 8L, CodecReadIssue.UNSUPPORTED_ENCODING)
        val samples = fmt.u64(36, true)
        codecRequire(samples > 0 && samples <= Long.MAX_VALUE - 1024)
        val block = fmt.u32(44, true)
        codecRequire(block == 4096L && fmt.u32(48, true) == 0L)
        val dataHeader = input.fixed(12)
        codecRequire(dataHeader.fourCc() == "data")
        val dataSize = dataHeader.u64(4, true)
        codecRequire(dataSize >= 12)
        val dataOffset = input.position
        val audioBytes = dataSize - 12
        val channelBytes = (samples - 1) / 8 + 1
        val blocks = (channelBytes - 1) / block + 1
        codecRequire(audioBytes == codecMultiply(codecMultiply(blocks, block), channels))
        val dataEnd = codecAdd(dataOffset, audioBytes)
        codecRequire(dataEnd <= input.length)
        validateDsfPadding(input, dataOffset, samples, channels.toInt(), block.toInt(), bits == 1L)
        if (metadata == 0L) codecRequire(dataEnd == input.length)
        else {
            codecRequire(metadata == dataEnd && input.length - metadata >= 10L)
            input.moveTo(metadata)
            codecRequire(input.fixed(3).let { String(it, Charsets.US_ASCII) } == "ID3")
        }
        return DsdLayout("DSF", channels.toInt(), rate.toInt(), samples, dataOffset, audioBytes,
            if (bits == 1L) DsdBitOrder.LSB_FIRST else DsdBitOrder.MSB_FIRST, block.toInt(),
            metadata.takeIf { it > 0 })
    }

    fun dff(input: CodecInput): DsdLayout {
        input.moveTo(0)
        val form = input.fixed(16)
        codecRequire(form.fourCc() == "FRM8" && form.fourCc(12) == "DSD ")
        codecRequire(codecAdd(form.u64(4, false), 12L) == input.length)
        var versionSeen = false
        var properties: Properties? = null
        var dataOffset: Long? = null
        var dataBytes = 0L
        var tagOffset: Long? = null
        var chunks = 0
        while (input.position < input.length) {
            codecRequire(++chunks <= MAX_CHUNKS, CodecReadIssue.RESOURCE_LIMIT)
            val chunk = chunk(input, input.length)
            when (chunk.id) {
                "FVER" -> {
                    codecRequire(!versionSeen && chunk.size == 4L)
                    val version = input.fixed(4).u32(0, false)
                    codecRequire(version == 0x01040000L || version == 0x01050000L,
                        CodecReadIssue.UNSUPPORTED_VERSION)
                    versionSeen = true
                }
                "PROP" -> {
                    codecRequire(properties == null && dataOffset == null)
                    properties = readProperties(input, chunk.end)
                }
                "DSD " -> {
                    val props = properties ?: throw CodecReadException(CodecReadIssue.INVALID_CONTAINER)
                    codecRequire(dataOffset == null && chunk.size > 0 && chunk.size % props.channels == 0L)
                    dataOffset = input.position
                    dataBytes = chunk.size
                }
                "DST ", "DSTI" -> throw CodecReadException(CodecReadIssue.UNSUPPORTED_ENCODING)
                "ID3 " -> {
                    codecRequire(tagOffset == null && chunk.size >= 10L)
                    tagOffset = input.position
                }
            }
            finishChunk(input, chunk)
        }
        val props = properties ?: throw CodecReadException(CodecReadIssue.INVALID_CONTAINER)
        codecRequire(versionSeen && input.position == input.length && dataOffset != null)
        val samples = codecMultiply(dataBytes / props.channels, 8)
        codecRequire(samples > 0 && samples <= Long.MAX_VALUE - 1024)
        return DsdLayout("DFF", props.channels, props.rate, samples, checkNotNull(dataOffset), dataBytes,
            DsdBitOrder.MSB_FIRST, null, tagOffset)
    }

    private data class Properties(val channels: Int, val rate: Int)
    private data class Chunk(val id: String, val size: Long, val end: Long, val next: Long)

    private fun chunk(input: CodecInput, limit: Long): Chunk {
        codecRequire(limit - input.position >= 12)
        val header = input.fixed(12)
        val id = header.fourCc()
        codecRequire(id.all { it in ' '..'~' })
        val size = header.u64(4, false)
        val end = codecAdd(input.position, size)
        val next = codecAdd(end, size and 1L)
        codecRequire(next <= limit)
        return Chunk(id, size, end, next)
    }

    private fun finishChunk(input: CodecInput, chunk: Chunk) {
        input.moveTo(chunk.end)
        if (chunk.next != chunk.end) codecRequire(input.fixed(1)[0].toInt() == 0)
        input.moveTo(chunk.next)
    }

    private fun validateDsfPadding(input: CodecInput, start: Long, samples: Long, channels: Int, block: Int, lsb: Boolean) {
        val bytes = (samples - 1) / 8 + 1
        val group = (bytes - 1) / block
        val used = ((bytes - 1) % block + 1).toInt()
        val base = codecAdd(start, codecMultiply(group, block.toLong() * channels))
        repeat(channels) { channel ->
            val channelStart = codecAdd(base, channel.toLong() * block)
            val validBits = (samples and 7L).toInt()
            if (validBits != 0) {
                input.moveTo(codecAdd(channelStart, used - 1L))
                val last = input.fixed(1)[0].toInt() and 255
                val mask = if (lsb) (1 shl validBits) - 1 else (255 shl (8 - validBits)) and 255
                codecRequire(last and (mask xor 255) == 0)
            }
            if (used < block) {
                input.moveTo(codecAdd(channelStart, used.toLong()))
                codecRequire(input.fixed(block - used).all { it.toInt() == 0 })
            }
        }
    }

    private fun readProperties(input: CodecInput, end: Long): Properties {
        codecRequire(end - input.position >= 4 && input.fixed(4).fourCc() == "SND ")
        var rate: Int? = null
        var channels: Int? = null
        var compression = false
        var chunks = 0
        while (input.position < end) {
            codecRequire(++chunks <= MAX_CHUNKS, CodecReadIssue.RESOURCE_LIMIT)
            val chunk = chunk(input, end)
            when (chunk.id) {
                "FS  " -> {
                    codecRequire(rate == null && chunk.size == 4L)
                    val value = input.fixed(4).u32(0, false)
                    codecRequire(value == DSD64.toLong(), CodecReadIssue.UNSUPPORTED_RATE)
                    rate = value.toInt()
                }
                "CHNL" -> {
                    codecRequire(channels == null && chunk.size in 6L..10L)
                    val amount = input.fixed(2).u16(0, false)
                    codecRequire(amount in 1..2, CodecReadIssue.UNSUPPORTED_CHANNELS)
                    codecRequire(chunk.size == 2L + amount * 4L)
                    val ids = List(amount) { input.fixed(4).fourCc() }
                    codecRequire(ids.distinct().size == ids.size && ids.all { id -> id.all { it in ' '..'~' } })
                    if (amount == 2) codecRequire(ids == listOf("SLFT", "SRGT"), CodecReadIssue.UNSUPPORTED_CHANNELS)
                    channels = amount
                }
                "CMPR" -> {
                    codecRequire(!compression && chunk.size in 5L..260L)
                    val header = input.fixed(5)
                    codecRequire(header.fourCc() == "DSD ", CodecReadIssue.UNSUPPORTED_ENCODING)
                    codecRequire(chunk.size == 5L + (header[4].toInt() and 255))
                    compression = true
                }
            }
            finishChunk(input, chunk)
        }
        codecRequire(compression && input.position == end && rate != null && channels != null)
        return Properties(checkNotNull(channels), checkNotNull(rate))
    }
}
