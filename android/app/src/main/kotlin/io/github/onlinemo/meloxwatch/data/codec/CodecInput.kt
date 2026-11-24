package io.github.onlinemo.meloxwatch.data.codec

import java.io.EOFException
import java.io.IOException

/** All implementations own one authorized input; unknown length never qualifies a custom codec. */
internal interface CodecInput : AutoCloseable {
    val length: Long
    val position: Long
    fun read(bytes: ByteArray, offset: Int, count: Int): Int
    fun seek(position: Long)
    fun checkCancellation()
}

internal enum class CodecReadIssue {
    UNKNOWN_LENGTH, INVALID_CONTAINER, UNSUPPORTED_VERSION, UNSUPPORTED_RATE,
    UNSUPPORTED_CHANNELS, UNSUPPORTED_ENCODING, RESOURCE_LIMIT, SOURCE_CHANGED, LOW_POWER_RESTRICTED,
}

internal class CodecReadException(val issue: CodecReadIssue) : IOException("Local codec: ${issue.name}")

internal fun codecRequire(condition: Boolean, issue: CodecReadIssue = CodecReadIssue.INVALID_CONTAINER) {
    if (!condition) throw CodecReadException(issue)
}

internal fun codecAdd(left: Long, right: Long): Long = try { Math.addExact(left, right) }
catch (_: ArithmeticException) { throw CodecReadException(CodecReadIssue.INVALID_CONTAINER) }

internal fun codecMultiply(left: Long, right: Long): Long = try { Math.multiplyExact(left, right) }
catch (_: ArithmeticException) { throw CodecReadException(CodecReadIssue.INVALID_CONTAINER) }

internal fun CodecInput.readFully(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset) {
    codecRequire(offset >= 0 && count >= 0 && offset <= bytes.size - count)
    var done = 0
    while (done < count) {
        checkCancellation()
        val read = read(bytes, offset + done, minOf(16 * 1024, count - done))
        if (read <= 0) throw CodecReadException(CodecReadIssue.INVALID_CONTAINER).also {
            it.initCause(EOFException("Incomplete local codec input"))
        }
        done += read
    }
    checkCancellation()
}

internal fun CodecInput.fixed(count: Int): ByteArray {
    codecRequire(count in 0..64 * 1024, CodecReadIssue.RESOURCE_LIMIT)
    return ByteArray(count).also { readFully(it) }
}

internal fun CodecInput.moveTo(next: Long) {
    codecRequire(length > 0, CodecReadIssue.UNKNOWN_LENGTH)
    codecRequire(next in 0..length)
    checkCancellation()
    if (next != position) seek(next)
    codecRequire(position == next, CodecReadIssue.SOURCE_CHANGED)
}

internal fun ByteArray.fourCc(offset: Int = 0): String = String(this, offset, 4, Charsets.US_ASCII)

internal fun ByteArray.u16(offset: Int, little: Boolean): Int {
    val first = this[offset].toInt() and 255
    val second = this[offset + 1].toInt() and 255
    return if (little) first or (second shl 8) else (first shl 8) or second
}

internal fun ByteArray.u32(offset: Int, little: Boolean): Long {
    var value = 0L
    repeat(4) { index ->
        val at = offset + if (little) 3 - index else index
        value = (value shl 8) or (this[at].toLong() and 255)
    }
    return value
}

/** Reject unsigned values that cannot be represented by the application's signed byte positions. */
internal fun ByteArray.u64(offset: Int, little: Boolean): Long {
    val high = offset + if (little) 7 else 0
    codecRequire(this[high].toInt() and 128 == 0)
    var value = 0L
    repeat(8) { index ->
        val at = offset + if (little) 7 - index else index
        value = (value shl 8) or (this[at].toLong() and 255)
    }
    return value
}

internal data class PcmFormat(val sampleRate: Int, val channels: Int, val bitsPerSample: Int = 16) {
    init { require(sampleRate > 0 && channels in 1..2 && bitsPerSample == 16) }
    val frameBytes: Int get() = channels * 2
}

internal data class LocalCodecFormat(
    val container: String,
    val encoding: String,
    val inputSampleRate: Int,
    val inputBitsPerSample: Int,
    val channels: Int,
    val pcm: PcmFormat,
    val sourceFrames: Long,
    val pcmFrames: Long,
    val version: Int? = null,
    val compressionLevel: Int? = null,
    val tagOffset: Long? = null,
) {
    val durationMs: Long get() = codecAdd(codecMultiply(sourceFrames / inputSampleRate, 1000),
        codecMultiply(sourceFrames % inputSampleRate, 1000) / inputSampleRate)
}

internal interface PcmFrameDecoder : AutoCloseable {
    val format: LocalCodecFormat
    val nextFrame: Long
    fun seekFrame(frame: Long)
    /** At most 512 complete interleaved PCM16 frames; zero means the declared end. */
    fun readFrames(bytes: ByteArray, offset: Int, frames: Int): Int
}
