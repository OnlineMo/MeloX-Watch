package io.github.onlinemo.meloxwatch.data.codec

import androidx.media3.common.util.UnstableApi
import io.github.onlinemo.meloxwatch.playback.dsp.ReplayGainTagIssue
import io.github.onlinemo.meloxwatch.playback.dsp.ReplayGainTagReader
import io.github.onlinemo.meloxwatch.playback.dsp.ReplayGainTagSource
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

@UnstableApi
class CodecReplayGainMetadataTest {
    private val gain = "REPLAYGAIN_TRACK_GAIN"
    private fun tagged() = CodecFixtures.apeTags(CodecFixtures.apeOne(first = 128), listOf(gain to "-6 dB"))

    @Test fun tagReadsRestoreTheCurrentInputBeforeTheRealApeCrcDecode() {
        val input = MemoryCodecInput(tagged())
        checkNotNull(LocalPcmCodecs.open(input)).use { decoder ->
            val originalPosition = input.position
            val metadata = CodecReplayGainMetadata.read(input, decoder.format)
            assertEquals(originalPosition, input.position)
            assertEquals(ReplayGainTagSource.APE, ReplayGainTagReader.read(metadata).source)
            val sample = ByteArray(2)
            assertEquals(1, decoder.readFrames(sample, 0, 1))
            assertEquals(128, ByteBuffer.wrap(sample).order(ByteOrder.LITTLE_ENDIAN).short.toInt())
        }
    }

    @Test fun aFailedPositionRestoreCannotReturnApparentlyUsableMetadata() {
        val base = MemoryCodecInput(tagged())
        val decoder = checkNotNull(LocalPcmCodecs.open(base))
        val saved = base.position
        val failing = object : CodecInput by base {
            override fun seek(position: Long) {
                if (position == saved) throw CodecReadException(CodecReadIssue.SOURCE_CHANGED)
                base.seek(position)
            }
        }
        try {
            CodecReplayGainMetadata.read(failing, decoder.format)
            fail("The decoder cannot continue after an unconfirmed raw position restore")
        } catch (error: CodecReadException) {
            assertEquals(CodecReadIssue.SOURCE_CHANGED, error.issue)
        } finally { decoder.close() }
    }

    @Test fun cancellationDuringTheFooterReadRemainsCancellation() {
        val raw = tagged()
        val base = MemoryCodecInput(raw)
        val decoder = checkNotNull(LocalPcmCodecs.open(base))
        val cancelled = object : CodecInput by base {
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                val result = base.read(bytes, offset, count)
                if (base.position == raw.size.toLong()) base.cancelled = true
                return result
            }
        }
        try {
            CodecReplayGainMetadata.read(cancelled, decoder.format)
            fail("Cancellation cannot become a neutral tag result")
        } catch (_: InterruptedIOException) {
        } finally { decoder.close() }
    }

    @Test fun aKnownExtentChangeDuringMetadataSeekCannotBecomeAnOptionalTagFailure() {
        val base = MemoryCodecInput(tagged())
        val decoder = checkNotNull(LocalPcmCodecs.open(base))
        val changed = object : CodecInput by base {
            override fun seek(position: Long) { throw CodecReadException(CodecReadIssue.SOURCE_CHANGED) }
        }
        try {
            CodecReplayGainMetadata.read(changed, decoder.format)
            fail("Source change must abort open")
        } catch (error: CodecReadException) {
            assertEquals(CodecReadIssue.SOURCE_CHANGED, error.issue)
        } finally { decoder.close() }
    }

    @Test fun aLargeAdmittedLiteralIsKeptWholeAndCannotAuthorizeAGain() {
        val raw = "0.5".padEnd(5_000, ' ')
        val bytes = CodecFixtures.apeTags(CodecFixtures.apeOne(), listOf("REPLAYGAIN_TRACK_PEAK" to raw))
        val input = MemoryCodecInput(bytes)
        checkNotNull(LocalPcmCodecs.open(input)).use { decoder ->
            val metadata = checkNotNull(CodecReplayGainMetadata.read(input, decoder.format))
            val entry = metadata[0] as ApeReplayGainEntry
            assertEquals(raw, entry.value)
            val result = ReplayGainTagReader.read(metadata)
            assertEquals(ReplayGainTagSource.APE, result.source)
            assertEquals(ReplayGainTagIssue.INVALID_VALUE, result.issue)
            assertNull(result.tags.trackPeak)
        }
    }

    @Test fun aFooterWhoseStartCrossesTheApeAudioEndIsNeverTrusted() {
        val bytes = tagged()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size - 32 + 12, bytes.size)
        val input = MemoryCodecInput(bytes)
        checkNotNull(LocalPcmCodecs.open(input)).use { decoder ->
            val result = ReplayGainTagReader.read(CodecReplayGainMetadata.read(input, decoder.format))
            assertEquals(ReplayGainTagSource.NONE, result.source)
            assertEquals(ReplayGainTagIssue.TAG_UNREADABLE, result.issue)
            assertNull(result.tags.trackGainDb)
        }
    }
}
