package io.github.onlinemo.meloxwatch.playback.dsp

import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.extractor.metadata.id3.Id3Decoder
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class ReplayGainMetadataTest {
    @Test fun actualId3DecoderSuppliesTheOriginalTxxxGainAndPeakFields() {
        val bytes = id3(listOf("REPLAYGAIN_TRACK_GAIN" to "+6.020599913 dB",
            "REPLAYGAIN_TRACK_PEAK" to "0.5", "REPLAYGAIN_ALBUM_GAIN" to "-3.0 dB"))
        val metadata = checkNotNull(Id3Decoder().decode(bytes, bytes.size))
        val result = ReplayGainTagReader.read(metadata)
        assertEquals(ReplayGainTagSource.ID3, result.source)
        assertNull(result.issue)
        assertEquals(6.020599913, result.tags.trackGainDb ?: 0.0, 0.0)
        assertEquals(0.5, result.tags.trackPeak ?: 0.0, 0.0)
        assertEquals(-3.0, result.tags.albumGainDb ?: 0.0, 0.0)
        val applied = result.tags.resolve(DspSettings(replayGainMode = ReplayGainMode.TRACK))
        assertEquals(2.0, applied.linearGain, 0.000001)
    }

    @Test fun vorbisFieldsRemainSeparateAndAlbumModeNeverBorrowsTrackGain() {
        val metadata = Metadata(VorbisComment("replaygain_track_gain", "-6.0 dB"),
            VorbisComment("REPLAYGAIN_ALBUM_PEAK", "0.8"))
        val result = ReplayGainTagReader.read(metadata)
        assertEquals(ReplayGainTagSource.VORBIS, result.source)
        assertNull(result.tags.albumGainDb)
        assertEquals(1.0, result.tags.resolve(DspSettings(replayGainMode = ReplayGainMode.ALBUM)).linearGain, 0.0)
    }

    @Test fun conflictingDuplicateFieldsCannotAuthorizeAGain() {
        val result = ReplayGainTagReader.read(Metadata(
            TextInformationFrame("TXXX", "REPLAYGAIN_TRACK_GAIN", listOf("+3 dB")),
            TextInformationFrame("TXXX", "replaygain_track_gain", listOf("+6 dB")),
            VorbisComment("REPLAYGAIN_ALBUM_GAIN", "-4 dB")))
        assertEquals(ReplayGainTagIssue.CONFLICTING_VALUES, result.issue)
        assertEquals(ReplayGainTagSource.MIXED, result.source)
        assertNull(result.tags.trackGainDb)
        assertEquals(-4.0, result.tags.albumGainDb ?: 0.0, 0.0)
    }

    @Test fun invalidOrExtremeNumericFieldsDoNotEscapeTheGainLimits() {
        for (value in listOf("NaN", "Infinity", "1e9", "+61 dB", "-61 dB", "1.0 dB trailing",
            "9".repeat(97))) {
            val result = ReplayGainTagReader.read(Metadata(VorbisComment("REPLAYGAIN_TRACK_GAIN", value)))
            assertEquals(ReplayGainTagIssue.INVALID_VALUE, result.issue)
            assertNull(result.tags.trackGainDb)
        }
        for (value in listOf("0", "-1", "65", "0.5 dB")) {
            val result = ReplayGainTagReader.read(Metadata(VorbisComment("REPLAYGAIN_TRACK_PEAK", value)))
            assertEquals(ReplayGainTagIssue.INVALID_VALUE, result.issue)
            assertNull(result.tags.trackPeak)
        }
        val tags = ReplayGainTags(trackGainDb = 60.0)
        val result = tags.resolve(DspSettings(replayGainMode = ReplayGainMode.TRACK,
            preampTenthsDb = 120, preventClipping = false))
        assertTrue(result.gainClamped)
        assertEquals(15.848931925, result.linearGain, 0.000001)
    }

    @Test fun oversizedMetadataIsRejectedBeforeTraversingItsEntries() {
        val entries = Array<Metadata.Entry>(65) { VorbisComment("REPLAYGAIN_TRACK_GAIN", "-6 dB") }
        val result = ReplayGainTagReader.read(Metadata(*entries))
        assertEquals(ReplayGainTagIssue.METADATA_TOO_LARGE, result.issue)
        assertEquals(ReplayGainTags(), result.tags)
    }

    private fun id3(fields: List<Pair<String, String>>): ByteArray {
        val frames = ByteArrayOutputStream()
        DataOutputStream(frames).use { output ->
            for ((key, value) in fields) {
                val payload = ByteArrayOutputStream()
                payload.write(0)
                payload.write(key.toByteArray(Charsets.ISO_8859_1))
                payload.write(0)
                payload.write(value.toByteArray(Charsets.ISO_8859_1))
                val bytes = payload.toByteArray()
                output.write("TXXX".toByteArray(Charsets.US_ASCII))
                output.writeInt(bytes.size)
                output.writeShort(0)
                output.write(bytes)
            }
        }
        val body = frames.toByteArray()
        val output = ByteArrayOutputStream()
        output.write("ID3".toByteArray(Charsets.US_ASCII))
        output.write(byteArrayOf(3, 0, 0))
        for (shift in listOf(21, 14, 7, 0)) output.write((body.size ushr shift) and 127)
        output.write(body)
        return output.toByteArray()
    }
}
