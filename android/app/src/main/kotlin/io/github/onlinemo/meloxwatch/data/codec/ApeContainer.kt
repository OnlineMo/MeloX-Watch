package io.github.onlinemo.meloxwatch.data.codec

internal data class ApeLayout(
    val version: Int,
    val compression: Int,
    val sampleRate: Int,
    val channels: Int,
    val blocksPerFrame: Int,
    val lastFrameBlocks: Int,
    val sourceFrames: Int,
    val seekBytes: IntArray,
    val audioEnd: Long,
    val tagMinimumOffset: Long,
    val leadingId3EndExclusive: Long? = null,
) {
    val format: LocalCodecFormat get() = LocalCodecFormat("APE", "Monkey's Audio", sampleRate, 16, channels,
        PcmFormat(sampleRate, channels), sourceFrames.toLong(), sourceFrames.toLong(), version, compression,
        tagOffset = leadingId3EndExclusive?.let { 0L }, tagEndExclusive = leadingId3EndExclusive,
        apeTagMinimumOffset = tagMinimumOffset)
}

/** Validate modern header dimensions before the Java decoder can allocate any frame or seek table. */
internal object ApeContainer {
    private val rates = setOf(8_000L, 11_025L, 16_000L, 22_050L, 32_000L, 44_100L, 48_000L, 88_200L, 96_000L)

    fun read(input: CodecInput, macOffset: Long = 0): ApeLayout {
        codecRequire(input.length in 1..Int.MAX_VALUE.toLong(), CodecReadIssue.RESOURCE_LIMIT)
        input.moveTo(macOffset)
        val descriptor = input.fixed(52)
        codecRequire(descriptor.fourCc() == "MAC ")
        val version = descriptor.u16(4, true)
        codecRequire(version == 3990, CodecReadIssue.UNSUPPORTED_VERSION)
        codecRequire(descriptor.u16(6, true) == 0)
        val descriptorBytes = descriptor.u32(8, true)
        val headerBytes = descriptor.u32(12, true)
        val seekBytes = descriptor.u32(16, true)
        val headerDataBytes = descriptor.u32(20, true)
        val audioBytes = codecAdd(descriptor.u32(24, true), codecMultiply(descriptor.u32(28, true), 1L shl 32))
        val terminatingBytes = descriptor.u32(32, true)
        codecRequire(descriptorBytes in 52L..4096L && headerBytes in 24L..4096L &&
            headerDataBytes <= 64 * 1024 && terminatingBytes <= 64 * 1024, CodecReadIssue.RESOURCE_LIMIT)
        input.moveTo(codecAdd(macOffset, descriptorBytes))
        val header = input.fixed(24)
        val compression = header.u16(0, true)
        // Faster levels have a bounded predictor and CRC frame. Higher levels need a separately
        // measured implementation; they fail before allocations or hot loops, never silently play.
        codecRequire(compression == 1000 || compression == 2000, CodecReadIssue.UNSUPPORTED_ENCODING)
        val flags = header.u16(2, true)
        codecRequire(flags == 0 || flags == 32)
        codecRequire(if (flags == 32) headerDataBytes == 0L else headerDataBytes >= 44L)
        val blocks = header.u32(4, true)
        val last = header.u32(8, true)
        val frames = header.u32(12, true)
        codecRequire(blocks in 1L..294_912L && last in 1L..blocks && frames in 1L..16_384L,
            CodecReadIssue.RESOURCE_LIMIT)
        codecRequire(header.u16(16, true) == 16, CodecReadIssue.UNSUPPORTED_ENCODING)
        val channels = header.u16(18, true)
        codecRequire(channels in 1..2, CodecReadIssue.UNSUPPORTED_CHANNELS)
        val rate = header.u32(20, true)
        codecRequire(rate in rates, CodecReadIssue.UNSUPPORTED_RATE)
        val totalBlocks = codecAdd(codecMultiply(frames - 1, blocks), last)
        codecRequire(totalBlocks <= Int.MAX_VALUE, CodecReadIssue.RESOURCE_LIMIT)
        codecRequire(seekBytes == codecMultiply(frames, 4))
        val seekStart = codecAdd(codecAdd(macOffset, descriptorBytes), headerBytes)
        val dataStart = codecAdd(codecAdd(seekStart, seekBytes), headerDataBytes)
        val dataEnd = codecAdd(dataStart, audioBytes)
        codecRequire(audioBytes >= codecMultiply(frames, 12) && codecAdd(dataEnd, terminatingBytes) <= input.length)
        input.moveTo(seekStart)
        val table = input.fixed(seekBytes.toInt())
        val seeks = IntArray(frames.toInt()) { at ->
            if (at and 255 == 0) input.checkCancellation()
            val absolute = codecAdd(macOffset, table.u32(at * 4, true))
            codecRequire(absolute >= dataStart && absolute < dataEnd && absolute <= Int.MAX_VALUE)
            absolute.toInt()
        }
        codecRequire(seeks[0].toLong() == dataStart)
        for (index in 1 until seeks.size) codecRequire(seeks[index] > seeks[index - 1])
        for (index in seeks.indices) {
            val end = if (index + 1 < seeks.size) seeks[index + 1].toLong() else dataEnd
            codecRequire(end - seeks[index] >= 12)
        }
        return ApeLayout(version, compression, rate.toInt(), channels, blocks.toInt(), last.toInt(),
            totalBlocks.toInt(), seeks, dataEnd, codecAdd(dataEnd, terminatingBytes), macOffset.takeIf { it > 0 })
    }
}
