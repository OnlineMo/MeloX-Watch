package io.github.onlinemo.meloxwatch.data.lyrics.store

import io.github.onlinemo.meloxwatch.data.local.LocalInputLimits
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsEncodingChoice
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsUserOffset
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsLoadBinding
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.net.URI
import java.net.URISyntaxException

object LyricsTrackLimits {
    const val SCHEMA_VERSION = 1
    const val TRACK_ID_CHARACTERS = 128
    const val SOURCE_ID_CHARACTERS = 128
    const val DOCUMENT_CHARACTERS = LocalInputLimits.URI_CHARACTERS
    const val QUEUE_ID_CHARACTERS = 128
    const val READ_PERMISSION_FLAG = 1
}

/** A saved route and last confirmed bytes are records, never a grant or a current read proof. */
data class LyricsAssociation(
    val documentId: String,
    val sourceId: String,
    val uri: String,
    val readFlags: Int,
    val encoding: LyricsEncodingChoice,
    val confirmedContentSha256: String,
) {
    init {
        require(boundedLyricsIdentity(documentId, LyricsTrackLimits.DOCUMENT_CHARACTERS))
        require(boundedLyricsIdentity(sourceId, LyricsTrackLimits.SOURCE_ID_CHARACTERS))
        require(boundedLyricsIdentity(uri, LyricsTrackLimits.DOCUMENT_CHARACTERS) && isLyricsDocumentUri(uri))
        require(readFlags == 0 || readFlags == LyricsTrackLimits.READ_PERMISSION_FLAG)
        require(confirmedContentSha256.matches(Regex("[0-9a-f]{64}")))
    }
}

/** Revision belongs to this local TrackKey; neither URI nor queue instance supplies its identity. */
data class LyricsTrackPreferences(
    val trackKey: TrackKey,
    val revision: Long = 0L,
    val primary: LyricsAssociation? = null,
    val translation: LyricsAssociation? = null,
    val userDelayMs: Int = 0,
) {
    init {
        require(validLyricsTrackKey(trackKey))
        require(revision >= 0L)
        require(userDelayMs in -LyricsUserOffset.MAX_DELAY_MS..LyricsUserOffset.MAX_DELAY_MS)
        require(userDelayMs % LyricsUserOffset.STEP_MS == 0)
    }
}

/** Checkpoint position changes preserve this fence; a different current instance does not. */
data class LyricsTrackWriteFence(
    val audio: LyricsLoadBinding,
    val queueGenerationId: String,
    val queueRevision: Long,
    val queueEntryId: String,
) {
    init {
        require(validLyricsTrackKey(audio.trackKey))
        require(boundedLyricsIdentity(queueGenerationId, LyricsTrackLimits.QUEUE_ID_CHARACTERS))
        require(boundedLyricsIdentity(queueEntryId, LyricsTrackLimits.QUEUE_ID_CHARACTERS))
        require(queueRevision >= 0L)
    }
}

enum class LyricsTrackIssue {
    NOT_LOCAL, INVALID_INPUT, BUSY, TRACK_MISSING, CORRUPT_RECORD, UNSUPPORTED_SCHEMA,
    REVISION_EXHAUSTED, IO, LOW_MEMORY,
}

sealed interface LyricsTrackReadResult {
    data class Ready(val preferences: LyricsTrackPreferences) : LyricsTrackReadResult
    data class Unavailable(val issue: LyricsTrackIssue) : LyricsTrackReadResult
}

sealed interface LyricsTrackCommitResult {
    data class Saved(val preferences: LyricsTrackPreferences) : LyricsTrackCommitResult
    data class Unchanged(val preferences: LyricsTrackPreferences) : LyricsTrackCommitResult
    data class Conflict(val current: LyricsTrackPreferences) : LyricsTrackCommitResult
    data class Stale(val current: LyricsTrackPreferences?) : LyricsTrackCommitResult
    /** current is the last verified read; after an IO failure read again before explicit retry. */
    data class Failed(val current: LyricsTrackPreferences?, val issue: LyricsTrackIssue) : LyricsTrackCommitResult
}

interface LyricsTrackStore {
    suspend fun read(trackKey: TrackKey): LyricsTrackReadResult

    /**
     * next retains expectedRevision; a changed commit increments it once in the Room transaction.
     * The caller validates the audio and every newly associated document before this call.
     * Removing an association may retain an already unavailable, unchanged other association;
     * that removal neither grants access nor asserts that the retained document is readable.
     * stillCurrent is a fast, side-effect-free, thread-safe owner ticket, checked in the transaction.
     * It may suspend to keep native state checks in the confined coroutine; it must not perform
     * provider I/O, parsing or network work while the writer is held.
     * The database fence is re-read there too; no provider I/O or parser work belongs in that block.
     * Cancellation propagates. A completed durable commit cannot be undone by later cancellation.
     */
    suspend fun commit(
        expectedRevision: Long,
        next: LyricsTrackPreferences,
        fence: LyricsTrackWriteFence,
        stillCurrent: suspend () -> Boolean,
    ): LyricsTrackCommitResult
}

internal fun boundedLyricsIdentity(value: String, maximum: Int): Boolean =
    value.isNotBlank() && value.length <= maximum && value.none(Char::isISOControl)

internal fun validLyricsTrackKey(key: TrackKey): Boolean = key.providerId == TrackKey.LOCAL_PROVIDER &&
    boundedLyricsIdentity(key.trackId, LyricsTrackLimits.TRACK_ID_CHARACTERS)

private fun isLyricsDocumentUri(value: String): Boolean = try {
    val uri = URI(value)
    uri.scheme == "content" && !uri.rawAuthority.isNullOrBlank()
} catch (_: URISyntaxException) { false }

internal class LyricsTrackException(val issue: LyricsTrackIssue) : Exception(issue.name)
internal fun lyricsTrackFail(issue: LyricsTrackIssue): Nothing = throw LyricsTrackException(issue)
