package io.github.onlinemo.meloxwatch.data.codec

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** DSD64 -> interleaved PCM16/88.2kHz. A centered FIR compensates delay without discarding tail frames. */
internal class DsdPcmDecoder(private val input: CodecInput, private val layout: DsdLayout) : PcmFrameDecoder {
    override val format = layout.format
    override var nextFrame = 0L
        private set
    private val bytes = DsdByteWindow(input, layout)
    private var closed = false
    private val filter = DsdFilterBank

    override fun seekFrame(frame: Long) {
        input.checkCancellation()
        codecRequire(!closed && frame in 0..format.pcmFrames)
        nextFrame = frame
        // FIR output depends only on this finite source window. Seeking reconstructs the identical
        // 512-bit preroll and lookahead, rather than resetting history to audible zero at the target.
        bytes.clear()
    }

    override fun readFrames(bytes: ByteArray, offset: Int, frames: Int): Int {
        codecRequire(!closed && frames in 0..512 && offset >= 0 &&
            offset <= bytes.size - frames * format.pcm.frameBytes)
        val count = minOf(frames.toLong(), format.pcmFrames - nextFrame).toInt()
        val deadline = System.nanoTime() + 2_000_000_000L
        var out = offset
        repeat(count) { at ->
            if (at and 31 == 0) {
                input.checkCancellation()
                codecRequire(System.nanoTime() - deadline < 0, CodecReadIssue.RESOURCE_LIMIT)
            }
            val center = codecMultiply(nextFrame, 32)
            val firstByte = center / 8 - 64
            repeat(layout.channels) { channel ->
                var sample = 0f
                repeat(128) { row ->
                    val index = firstByte + row
                    val bit = index * 8
                    if (index >= 0 && bit < layout.bitSamples) {
                        val value = this.bytes.byte(channel, index)
                        val valid = minOf(8L, layout.bitSamples - bit).toInt()
                        sample += if (valid == 8) filter.lookup[row * 256 + value]
                        else filter.partial(row * 8, value, valid)
                    }
                }
                val lastBit = codecAdd(center, 512)
                if (lastBit < layout.bitSamples) {
                    val value = this.bytes.byte(channel, lastBit / 8)
                    sample += filter.coefficients[1024].toFloat() * if (value and 1 != 0) 1f else -1f
                }
                val pcm = (sample * 32768f).roundToInt().coerceIn(-32768, 32767)
                bytes[out++] = pcm.toByte()
                bytes[out++] = (pcm shr 8).toByte()
            }
            nextFrame++
        }
        input.checkCancellation()
        return count
    }

    override fun close() { closed = true; bytes.clear() }
}

/** Fixed linear-phase low-pass: 1025 taps, 28kHz cutoff, four-term Blackman-Harris window.
 * Output Nyquist is 44.1kHz; suppression before downsampling prevents ultrasonic DSD noise folding
 * into the audible band. Byte lookup tables change computation only, never the filter definition. */
internal object DsdFilterBank {
    val coefficients: DoubleArray = DoubleArray(1025) { index ->
        val x = index - 512
        val cutoff = 28_000.0 / 2_822_400.0
        val sinc = if (x == 0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
        val phase = 2 * PI * index / 1024
        val window = 0.35875 - 0.48829 * cos(phase) + 0.14128 * cos(2 * phase) - 0.01168 * cos(3 * phase)
        sinc * window
    }.also { taps ->
        val sum = taps.sum()
        taps.indices.forEach { taps[it] /= sum }
    }
    val lookup: FloatArray = FloatArray(128 * 256) { index ->
        partial(index / 256 * 8, index and 255, 8)
    }
    fun partial(firstTap: Int, value: Int, bits: Int): Float {
        var sum = 0.0
        repeat(bits) { bit -> sum += coefficients[firstTap + bit] * if (value and (1 shl bit) != 0) 1.0 else -1.0 }
        return sum.toFloat()
    }
}

/** Two fixed slabs cover a filter window crossing a channel block without per-sample provider I/O. */
private class DsdByteWindow(private val input: CodecInput, private val layout: DsdLayout) {
    private val channelBytes = (layout.bitSamples - 1) / 8 + 1
    private val block = layout.channelBlockBytes ?: 4096
    private val slabBytes = block * layout.channels
    private val slabs = arrayOf(ByteArray(slabBytes), ByteArray(slabBytes))
    private val groups = longArrayOf(-1, -1)
    private var replacement = 0

    fun clear() { groups.fill(-1); replacement = 0 }

    fun byte(channel: Int, index: Long): Int {
        codecRequire(channel in 0 until layout.channels && index in 0 until channelBytes)
        val group = index / block
        var slot = groups.indexOf(group)
        if (slot < 0) {
            input.checkCancellation()
            slot = replacement
            replacement = 1 - replacement
            val start = codecMultiply(group, slabBytes.toLong())
            val count = minOf(slabBytes.toLong(), layout.audioBytes - start).toInt()
            codecRequire(count > 0)
            input.moveTo(codecAdd(layout.audioOffset, start))
            input.readFully(slabs[slot], 0, count)
            groups[slot] = group
        }
        val within = (index % block).toInt()
        val position = if (layout.channelBlockBytes != null) channel * block + within else within * layout.channels + channel
        var value = slabs[slot][position].toInt() and 255
        if (layout.bitOrder == DsdBitOrder.MSB_FIRST) value = Integer.reverse(value) ushr 24
        return value
    }
}
