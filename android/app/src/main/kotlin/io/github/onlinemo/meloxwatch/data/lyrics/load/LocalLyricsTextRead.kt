package io.github.onlinemo.meloxwatch.data.lyrics.load

import android.os.CancellationSignal
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive

internal class LyricsReadBytes(val bytes: ByteArray, val sha256: String, val acquisitionOverheadBytes: Long)
internal data class LyricsTextFingerprint(val sha256: String, val sizeBytes: Int)

/** Full bounded reads, including one extra byte to distinguish exact-limit EOF from truncation. */
internal class LocalLyricsTextRead(private val limits: LocalLyricsLoadLimits) {
    fun read(input: InputStream, declaredSize: Long?, signal: CancellationSignal, context: CoroutineContext): LyricsReadBytes {
        checkDeclaredSize(declaredSize)
        val chunks = ArrayList<ByteArray>()
        var retainedChunkBytes = 0L
        val baseOverhead = LYRICS_LOAD_ENVELOPE_BYTES + limits.ioChunkBytes + 512L
        checkMemory(baseOverhead)
        val scratch = ByteArray(limits.ioChunkBytes)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0
        var used = 0
        var operations = 0

        fun retain() {
            if (used == 0) return
            val next = retainedChunkBytes + used
            // Include the future exact result allocation while all original chunks still exist.
            checkMemory(baseOverhead + next + total + (chunks.size + 1L) * 64L)
            chunks += scratch.copyOf(used)
            retainedChunkBytes = next
            used = 0
        }

        while (true) {
            context.ensureActive()
            signal.throwIfCanceled()
            if (operations++ == limits.maxReadOperations) lyricsLoadFail(LyricsLoadIssue.READ_LIMIT)
            val remaining = limits.lyrics.maxInputBytes - total
            val request = if (remaining == 0) 1 else minOf(scratch.size - used, remaining)
            val count = input.read(scratch, if (remaining == 0) 0 else used, request)
            context.ensureActive()
            signal.throwIfCanceled()
            if (count == -1) break
            if (count <= 0 || count > request) lyricsLoadFail(LyricsLoadIssue.INVALID_READ)
            if (remaining == 0) lyricsLoadFail(LyricsLoadIssue.INPUT_TOO_LARGE)
            digest.update(scratch, used, count)
            used += count
            total += count
            if (used == scratch.size || total == limits.lyrics.maxInputBytes) retain()
        }
        if (declaredSize != null && declaredSize != total.toLong()) lyricsLoadFail(LyricsLoadIssue.SOURCE_CHANGED)
        retain()
        val overhead = baseOverhead + retainedChunkBytes + chunks.size.toLong() * 64L
        checkMemory(overhead + total)
        val bytes = ByteArray(total)
        var offset = 0
        for (chunk in chunks) {
            context.ensureActive()
            chunk.copyInto(bytes, offset)
            offset += chunk.size
        }
        context.ensureActive()
        return LyricsReadBytes(bytes, hex(digest.digest()), overhead)
    }

    /** Weak sources re-read a complete SHA baseline without retaining a second lyric payload. */
    fun fingerprint(input: InputStream, declaredSize: Long?, signal: CancellationSignal, context: CoroutineContext): LyricsTextFingerprint {
        checkDeclaredSize(declaredSize)
        checkMemory(LYRICS_LOAD_ENVELOPE_BYTES + limits.ioChunkBytes + 512L)
        val digest = MessageDigest.getInstance("SHA-256")
        val scratch = ByteArray(limits.ioChunkBytes)
        var total = 0
        var operations = 0
        while (true) {
            context.ensureActive()
            signal.throwIfCanceled()
            if (operations++ == limits.maxReadOperations) lyricsLoadFail(LyricsLoadIssue.READ_LIMIT)
            val remaining = limits.lyrics.maxInputBytes - total
            val request = if (remaining == 0) 1 else minOf(scratch.size, remaining)
            val count = input.read(scratch, 0, request)
            context.ensureActive()
            signal.throwIfCanceled()
            if (count == -1) break
            if (count <= 0 || count > request) lyricsLoadFail(LyricsLoadIssue.INVALID_READ)
            if (remaining == 0) lyricsLoadFail(LyricsLoadIssue.INPUT_TOO_LARGE)
            digest.update(scratch, 0, count)
            total += count
        }
        if (declaredSize != null && declaredSize != total.toLong()) lyricsLoadFail(LyricsLoadIssue.SOURCE_CHANGED)
        return LyricsTextFingerprint(hex(digest.digest()), total)
    }

    private fun checkDeclaredSize(size: Long?) {
        if (size != null && size < 0L) lyricsLoadFail(LyricsLoadIssue.INVALID_INPUT)
        if (size != null && size > limits.lyrics.maxInputBytes) lyricsLoadFail(LyricsLoadIssue.INPUT_TOO_LARGE)
    }

    private fun checkMemory(bytes: Long) {
        if (bytes > limits.lyrics.maxAccountedBytes) lyricsLoadFail(LyricsLoadIssue.MEMORY_LIMIT)
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
