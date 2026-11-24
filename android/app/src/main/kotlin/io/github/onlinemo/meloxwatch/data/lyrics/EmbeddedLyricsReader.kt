package io.github.onlinemo.meloxwatch.data.lyrics

import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Offline, read-only F039 acquisition parser. The caller supplies a dedicated, legally authorized
 * local stream, owns closing it, serializes acquisitions and binds the complete result to its
 * TrackKey/content generation. This parser does not share a playback stream, write tags, cache,
 * discover files or contact a service. Cancellation propagates between bounded reads and work.
 */
class EmbeddedLyricsReader internal constructor(
    private val limits: EmbeddedLyricsLimits,
    private val ioDispatcher: CoroutineDispatcher,
    private val parserDispatcher: CoroutineDispatcher,
) {
    constructor(limits: EmbeddedLyricsLimits = EmbeddedLyricsLimits()) : this(limits, Dispatchers.IO, Dispatchers.Default)

    suspend fun read(stream: InputStream): EmbeddedLyricsReadResult = acquire(stream) { input, budget, builder, collector ->
        val prefix = ByteArray(4)
        val count = input.readPrefix(prefix)
        if (count != 4) {
            val partialId3 = count == 3 && prefix[0] == 'I'.code.toByte() &&
                prefix[1] == 'D'.code.toByte() && prefix[2] == '3'.code.toByte()
            val flac = "fLaC"
            val partialFlac = count != 0 && (0 until count).all { prefix[it] == flac[it].code.toByte() }
            val ogg = "OggS"
            val partialOgg = count != 0 && (0 until count).all { prefix[it] == ogg[it].code.toByte() }
            embeddedLyricsFailure(
                if (partialId3 || partialFlac || partialOgg) EmbeddedLyricsIssue.TRUNCATED_METADATA else EmbeddedLyricsIssue.UNSUPPORTED_CONTAINER,
            )
        }
        when {
            prefix[0] == 'I'.code.toByte() && prefix[1] == 'D'.code.toByte() && prefix[2] == '3'.code.toByte() ->
                Id3EmbeddedLyricsReader(input, budget, builder, collector).read(prefix)
            prefix.contentEquals(byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte())) ->
                FlacEmbeddedLyricsReader(input, budget, builder, collector).flac()
            prefix.contentEquals(byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte())) ->
                OggEmbeddedLyricsReader(input, budget, builder, collector).read(prefix)
            else -> embeddedLyricsFailure(EmbeddedLyricsIssue.UNSUPPORTED_CONTAINER)
        }
    }

    /** Packet length must come from a separately controlled demuxer; this is not an Ogg reader. */
    suspend fun readVorbisCommentPacket(stream: InputStream, packetLength: Long): EmbeddedLyricsReadResult =
        acquire(stream) { input, budget, builder, collector ->
            if (packetLength < 0L) embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_CONTAINER)
            FlacEmbeddedLyricsReader(input, budget, builder, collector).vorbisPacket(input.segment(packetLength))
        }

    private suspend fun acquire(
        stream: InputStream,
        read: suspend (EmbeddedLyricsInput, EmbeddedLyricsBudget, EmbeddedLyricsDocumentBuilder, EmbeddedLyricsCollector) -> Unit,
    ): EmbeddedLyricsReadResult = withContext(ioDispatcher) {
        val context = currentCoroutineContext()
        context.ensureActive()
        val budget = try {
            EmbeddedLyricsBudget(limits)
        } catch (error: EmbeddedLyricsReadException) {
            context.ensureActive()
            return@withContext EmbeddedLyricsReadResult(emptyList(), listOf(EmbeddedLyricsDiagnostic(error.issue)), false, null, 0)
        }
        val collector = EmbeddedLyricsCollector(budget)
        val input = EmbeddedLyricsInput(stream, budget, context)
        val builder = EmbeddedLyricsDocumentBuilder(budget, context, parserDispatcher)
        try {
            read(input, budget, builder, collector)
            context.ensureActive()
            collector.result()
        } catch (error: CancellationException) {
            throw error
        } catch (error: EmbeddedLyricsReadException) {
            context.ensureActive()
            collector.result(error.issue)
        } catch (_: IOException) {
            context.ensureActive()
            collector.result(EmbeddedLyricsIssue.IO_FAILURE)
        } catch (_: SecurityException) {
            context.ensureActive()
            collector.result(EmbeddedLyricsIssue.READ_PERMISSION_DENIED)
        }
    }
}
