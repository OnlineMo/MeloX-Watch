package io.github.onlinemo.meloxwatch.data.codec

import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.Id3Decoder
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import io.github.onlinemo.meloxwatch.playback.resources.metadata.Id3Admission
import io.github.onlinemo.meloxwatch.playback.resources.metadata.MetadataAdmissionException
import io.github.onlinemo.meloxwatch.playback.resources.metadata.MetadataAdmissionIssue
import io.github.onlinemo.meloxwatch.playback.resources.metadata.MetadataBudget
import io.github.onlinemo.meloxwatch.playback.resources.metadata.MetadataCursor
import io.github.onlinemo.meloxwatch.playback.resources.metadata.MetadataLimits
import io.github.onlinemo.meloxwatch.playback.resources.metadata.admit
import java.nio.charset.CharacterCodingException
import java.util.Locale

/** A real APEv2 text field; it never masquerades as a Vorbis or ID3 entry. */
@UnstableApi
internal data class ApeReplayGainEntry(val key: String, val value: String) : Metadata.Entry

@UnstableApi
internal data class CodecReplayGainReadFailure(val resourceLimit: Boolean) : Metadata.Entry

/** Loader-owned reads on the current authorized codec input, before its first PCM seek. */
@UnstableApi
internal object CodecReplayGainMetadata {
    private const val MAX_GAIN_ENTRIES = 64
    private val fields = setOf("REPLAYGAIN_TRACK_GAIN", "REPLAYGAIN_TRACK_PEAK",
        "REPLAYGAIN_ALBUM_GAIN", "REPLAYGAIN_ALBUM_PEAK")

    fun read(input: CodecInput, format: LocalCodecFormat): Metadata? {
        input.checkCancellation()
        val savedPosition = input.position
        var originalFailure: Throwable? = null
        try {
            val result = try {
                readAdmitted(input, format)
            } catch (error: MetadataAdmissionException) {
                Metadata(CodecReplayGainReadFailure(error.issue == MetadataAdmissionIssue.RESOURCE_LIMIT))
            } catch (error: CodecReadException) {
                if (error.issue != CodecReadIssue.INVALID_CONTAINER && error.issue != CodecReadIssue.RESOURCE_LIMIT) throw error
                Metadata(CodecReplayGainReadFailure(error.issue == CodecReadIssue.RESOURCE_LIMIT))
            } catch (_: CharacterCodingException) {
                Metadata(CodecReplayGainReadFailure(false))
            }
            input.checkCancellation()
            return result
        } catch (error: Throwable) {
            originalFailure = error
            throw error
        } finally {
            try {
                // External tag seeks cannot change JMAC's buffered decoder position or its next read.
                // A failed restore aborts open; the existing owner then confirms physical retirement.
                input.moveTo(savedPosition)
                input.checkCancellation()
            } catch (restore: Throwable) {
                val previous = originalFailure
                if (previous == null) throw restore
                if (previous !== restore) previous.addSuppressed(restore)
            }
        }
    }

    private fun readAdmitted(input: CodecInput, format: LocalCodecFormat): Metadata? {
        val entries = ArrayList<Metadata.Entry>()
        format.tagOffset?.let { start ->
            val end = format.tagEndExclusive ?: throw CodecReadException(CodecReadIssue.INVALID_CONTAINER)
            val id3 = readId3(input, start, end)
            for (index in 0 until id3.length()) {
                input.checkCancellation()
                val entry = id3[index]
                if (entry is TextInformationFrame && entry.id == "TXXX") {
                    val key = entry.description
                    if (key != null && key.length <= 64 && key.trim().uppercase(Locale.ROOT) in fields) {
                        admit(entries.size < MAX_GAIN_ENTRIES, MetadataAdmissionIssue.RESOURCE_LIMIT)
                        entries += entry
                    }
                }
            }
        }
        if (format.container == "APE") {
            val minimum = format.apeTagMinimumOffset ?: throw CodecReadException(CodecReadIssue.INVALID_CONTAINER)
            // Leading ID3 and the independent APEv2 footer can both exist on this same source.
            for ((key, value) in ApeTagFields.read(input, minimum)) if (key in fields) {
                admit(entries.size < MAX_GAIN_ENTRIES, MetadataAdmissionIssue.RESOURCE_LIMIT)
                entries += ApeReplayGainEntry(key, value)
            }
        }
        input.checkCancellation()
        return if (entries.isEmpty()) null else Metadata(entries)
    }

    private fun readId3(input: CodecInput, start: Long, endExclusive: Long): Metadata {
        codecRequire(start >= 0 && endExclusive >= start && endExclusive <= input.length && endExclusive - start >= 10)
        input.moveTo(start)
        val header = input.fixed(10)
        codecRequire(header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte())
        val admission = Id3Admission(MetadataBudget())
        // Header dimensions and the actual DFF/asset end are checked before the raw tag allocation.
        admission.streamHeader(start, header, endExclusive)
        val size = MetadataCursor(header, 6, 10).syncSafe()
        val bytes = ByteArray(size + 10)
        header.copyInto(bytes)
        input.readFully(bytes, 10, size)
        admission.returned(start + 10, bytes, 10, size, false, endExclusive)
        input.checkCancellation()
        val metadata = Id3Decoder { version, a, b, c, d ->
            a == 'T'.code && b == 'X'.code && c == 'X'.code && (version == 2 || d == 'X'.code)
        }.decode(bytes, bytes.size) ?: throw CodecReadException(CodecReadIssue.INVALID_CONTAINER)
        input.checkCancellation()
        admit(metadata.length() <= MetadataLimits.ENTRIES, MetadataAdmissionIssue.RESOURCE_LIMIT)
        return metadata
    }
}
