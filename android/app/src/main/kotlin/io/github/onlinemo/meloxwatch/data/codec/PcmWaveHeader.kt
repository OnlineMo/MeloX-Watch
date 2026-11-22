package io.github.onlinemo.meloxwatch.data.codec

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** RIFF/RF64 is an in-memory transport header; converted audio is never written beside the original. */
internal class PcmWaveHeader(val format: PcmFormat, val frames: Long) {
    val dataBytes = codecMultiply(frames, format.frameBytes.toLong())
    private val rf64 = codecAdd(dataBytes, 36) > 0xffff_ffffL
    val bytes: ByteArray = ByteArray(if (rf64) 80 else 44).also { array ->
        val out = ByteBuffer.wrap(array).order(ByteOrder.LITTLE_ENDIAN)
        fun id(value: String) { out.put(value.toByteArray(Charsets.US_ASCII)) }
        id(if (rf64) "RF64" else "RIFF")
        out.putInt(if (rf64) -1 else (dataBytes + 36).toInt())
        id("WAVE")
        if (rf64) {
            id("ds64"); out.putInt(28)
            out.putLong(codecAdd(dataBytes, 72)); out.putLong(dataBytes); out.putLong(frames); out.putInt(0)
        }
        id("fmt "); out.putInt(16); out.putShort(1); out.putShort(format.channels.toShort())
        out.putInt(format.sampleRate); out.putInt(Math.multiplyExact(format.sampleRate, format.frameBytes))
        out.putShort(format.frameBytes.toShort()); out.putShort(16)
        id("data"); out.putInt(if (rf64) -1 else dataBytes.toInt())
    }
    val streamBytes = codecAdd(bytes.size.toLong(), dataBytes)
    init { codecRequire(frames > 0) }
}
