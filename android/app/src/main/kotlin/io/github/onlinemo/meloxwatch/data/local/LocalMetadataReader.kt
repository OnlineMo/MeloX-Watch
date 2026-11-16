package io.github.onlinemo.meloxwatch.data.local

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.CancellationSignal
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import java.io.EOFException
import java.io.File
import java.io.FileInputStream

class LocalMetadataReader(
    private val context: Context,
    private val io: LocalProviderIo,
) {
    suspend fun contentToken(candidate: ObservedCandidate): String? {
        if (candidate.sizeBytes != null && candidate.sizeBytes > LocalInputLimits.READ_PROOF_BYTES) return null
        return io.read { signal ->
            val file = context.contentResolver.openAssetFileDescriptor(AndroidLocalSources.contentUri(candidate.uri), "r", signal)
                ?: throw SourceReadException(SourceReadIssue.SOURCE_UNAVAILABLE)
            file.use { descriptor ->
                descriptor.createInputStream().use { ContentFingerprint.read(it, signal, candidate.sizeBytes) }
            }
        }
    }

    suspend fun read(candidate: ObservedCandidate): LocalAudioMetadata = io.read { signal ->
        val uri = AndroidLocalSources.contentUri(candidate.uri)
        context.contentResolver.openAssetFileDescriptor(uri, "r", signal)?.use { descriptor ->
            descriptor.createInputStream().use { stream ->
                val tags = readTags(stream, signal)
                val retriever = MediaMetadataRetriever()
                try {
                    if (descriptor.declaredLength >= 0) {
                        retriever.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.declaredLength)
                    } else if (descriptor.startOffset == 0L) {
                        retriever.setDataSource(descriptor.fileDescriptor)
                    } else {
                        throw SourceReadException(SourceReadIssue.UNSUPPORTED_FORMAT)
                    }
                    extract(retriever, tags, signal)
                } catch (error: IllegalArgumentException) {
                    throw SourceReadException(SourceReadIssue.UNSUPPORTED_FORMAT, error)
                } catch (error: OutOfMemoryError) {
                    throw SourceReadException(SourceReadIssue.METADATA_UNREADABLE, error)
                } finally { retriever.release() }
            }
        } ?: throw SourceReadException(SourceReadIssue.SOURCE_UNAVAILABLE)
    }

    /** Import validation uses a private staging file; it never publishes a staging content URI. */
    internal fun readPrivateFile(file: File, signal: CancellationSignal): LocalAudioMetadata {
        val tags = FileInputStream(file).use { readTags(it, signal) }
        val retriever = MediaMetadataRetriever()
        try {
            return FileInputStream(file).use { retriever.setDataSource(it.fd); extract(retriever, tags, signal) }
        } catch (error: IllegalArgumentException) {
            throw SourceReadException(SourceReadIssue.UNSUPPORTED_FORMAT, error)
        } catch (error: OutOfMemoryError) {
            throw SourceReadException(SourceReadIssue.METADATA_UNREADABLE, error)
        } finally { retriever.release() }
    }

    private fun readTags(input: java.io.InputStream, signal: CancellationSignal): AudioTextTags = try {
        AudioTagReader.readText(input, signal)
    } catch (error: EOFException) {
        throw SourceReadException(SourceReadIssue.METADATA_UNREADABLE, error)
    }

    private fun extract(retriever: MediaMetadataRetriever, tags: AudioTextTags, signal: CancellationSignal): LocalAudioMetadata {
        try {
            signal.throwIfCanceled()
            if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != "yes" ||
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            ) throw SourceReadException(SourceReadIssue.NOT_AUDIO)
            fun field(key: Int): String? = text(retriever.extractMetadata(key))
            val artist = tags.artist ?: field(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            return LocalAudioMetadata(
                title = tags.title ?: field(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = artist,
                artists = tags.artists,
                album = tags.album ?: field(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                albumArtist = tags.albumArtist ?: field(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                durationMs = field(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 },
                discNumber = number(tags.discNumber ?: field(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)),
                trackNumber = number(tags.trackNumber ?: field(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)),
                compilation = tags.compilation ?: (field(MediaMetadataRetriever.METADATA_KEY_COMPILATION) == "1"),
                mimeType = field(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                parserVersion = AudioTagReader.PARSER_VERSION,
            )
        } catch (error: OutOfMemoryError) {
            throw SourceReadException(SourceReadIssue.METADATA_UNREADABLE, error)
        }
    }

    private fun number(value: String?): Int? = value?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 }
    private fun text(value: String?): String? = AndroidLocalSources.bounded(value, LocalInputLimits.TAG_CHARACTERS)
        .takeIf { it.isNotBlank() && it != "<unknown>" }
}
