package io.github.onlinemo.meloxwatch.data.lyrics

import java.io.InputStream
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/** Dedicated authorized metadata stream. No seeking, mark/reset, audio reads or ownership transfer. */
internal class EmbeddedLyricsInput(
    private val stream: InputStream,
    private val budget: EmbeddedLyricsBudget,
    private val context: CoroutineContext,
) {
    private val scratch = ByteArray(budget.limits.ioChunkBytes)
    private val scalar = ByteArray(8)
    var consumedBytes = 0L
        private set
    private var operations = 0

    fun check() { context.ensureActive() }

    fun validateSpan(length: Long) {
        if (length < 0L) embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_CONTAINER)
        if (length > budget.limits.maxMetadataBytes.toLong() - consumedBytes) {
            embeddedLyricsFailure(EmbeddedLyricsIssue.METADATA_TOO_LARGE)
        }
    }

    suspend fun readPrefix(destination: ByteArray): Int {
        validateSpan(destination.size.toLong())
        var count = 0
        while (count < destination.size) {
            val next = read(destination, count, destination.size - count)
            if (next < 0) break
            count += next
        }
        return count
    }

    suspend fun exactly(destination: ByteArray, offset: Int = 0, length: Int = destination.size - offset) {
        require(offset >= 0 && length >= 0 && offset <= destination.size - length)
        validateSpan(length.toLong())
        var count = 0
        while (count < length) {
            val next = read(destination, offset + count, length - count)
            if (next < 0) embeddedLyricsFailure(EmbeddedLyricsIssue.TRUNCATED_METADATA)
            count += next
        }
    }

    suspend fun byte(): Int {
        exactly(scalar, length = 1)
        return scalar[0].toInt() and 0xff
    }

    suspend fun uint32(littleEndian: Boolean): Long {
        exactly(scalar, length = 4)
        var value = 0L
        for (index in 0..3) {
            val source = if (littleEndian) 3 - index else index
            value = (value shl 8) or (scalar[source].toLong() and 0xffL)
        }
        return value
    }

    suspend fun skip(length: Long, requireZero: Boolean = false) {
        validateSpan(length)
        var remaining = length
        while (remaining > 0L) {
            check()
            val count = minOf(remaining, scratch.size.toLong()).toInt()
            exactly(scratch, length = count)
            if (requireZero) {
                for (index in 0 until count) {
                    if (index % 256 == 0) check()
                    if (scratch[index] != 0.toByte()) embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_ID3_FRAME)
                }
            }
            remaining -= count
        }
    }

    fun segment(length: Long): EmbeddedLyricsSegment {
        validateSpan(length)
        return EmbeddedLyricsSegment(this, length)
    }

    private suspend fun read(destination: ByteArray, offset: Int, length: Int): Int {
        check()
        if (operations == budget.limits.maxReadOperations) embeddedLyricsFailure(EmbeddedLyricsIssue.READ_LIMIT)
        operations++
        val requested = minOf(length, budget.limits.ioChunkBytes)
        val count = stream.read(destination, offset, requested)
        check()
        // A broken provider cannot corrupt cursor accounting or escape the requested span.
        if (count < -1 || count > requested) embeddedLyricsFailure(EmbeddedLyricsIssue.IO_FAILURE)
        if (count > 0) consumedBytes += count
        if (operations % 64 == 0) yield()
        return count
    }
}

internal class EmbeddedLyricsSegment(private val input: EmbeddedLyricsInput, length: Long) {
    var remaining: Long = length
        private set

    fun requireLength(length: Long) {
        if (length < 0L || length > remaining) embeddedLyricsFailure(EmbeddedLyricsIssue.TRUNCATED_METADATA)
        input.validateSpan(length)
    }

    suspend fun read(destination: ByteArray, length: Int = destination.size) {
        requireLength(length.toLong())
        input.exactly(destination, length = length)
        remaining -= length
    }

    suspend fun byte(): Int {
        requireLength(1L)
        return input.byte().also { remaining-- }
    }

    suspend fun uint32(littleEndian: Boolean): Long {
        requireLength(4L)
        return input.uint32(littleEndian).also { remaining -= 4L }
    }

    fun take(length: Long): EmbeddedLyricsSegment {
        requireLength(length)
        remaining -= length
        return input.segment(length)
    }

    suspend fun skip(length: Long = remaining, requireZero: Boolean = false) {
        requireLength(length)
        input.skip(length, requireZero)
        remaining -= length
    }

    suspend fun payload(length: Int, budget: EmbeddedLyricsBudget): ByteArray {
        requireLength(length.toLong())
        budget.payload(length)
        budget.rawAllocation(length)
        val bytes = ByteArray(length)
        read(bytes)
        return bytes
    }
}

/** A byte cursor over one already-bounded lyric payload; no range copies. */
internal class EmbeddedLyricsCursor(val bytes: ByteArray) {
    var position = 0
    val remaining: Int get() = bytes.size - position

    fun byte(): Int {
        if (remaining < 1) embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_TEXT)
        return bytes[position++].toInt() and 0xff
    }

    fun uint32(): Long {
        if (remaining < 4) embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_TIMESTAMP)
        var value = 0L
        repeat(4) { value = (value shl 8) or byte().toLong() }
        return value
    }
}
