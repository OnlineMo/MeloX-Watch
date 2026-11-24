package io.github.onlinemo.meloxwatch.data.lyrics.online

import io.github.onlinemo.meloxwatch.data.lyrics.LyricsDocument
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsLimits
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsSourceFence
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsTranslation
import io.github.onlinemo.meloxwatch.model.QueueSeekBinding
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.model.onlineCanonicalId
import kotlinx.coroutines.flow.Flow

enum class OnlineLyricsOrigin { NETEASE_LRC, OPENED_METING_LRC }
enum class OnlineLyricsIssue {
    NO_LYRICS, OFFLINE, LOGIN_REQUIRED, SESSION_UNAVAILABLE, TIMEOUT, CERTIFICATE, RATE_LIMITED,
    SOURCE_CHANGED, UNSUPPORTED, INPUT_LIMIT, INVALID_ENCODING, READ_FAILED, MEMORY_LIMIT, BUSY, CLOSED,
}

data class OnlineLyricsRequest(
    val trackKey: TrackKey,
    val queueGenerationId: String,
    val queueRevision: Long,
    val queueEntryId: String,
    val sourceGeneration: Long,
    val accountId: String,
    val maxAccountedBytes: Long,
    val allowNetwork: Boolean = false,
) {
    init {
        require(trackKey.providerId == "netease" && onlineCanonicalId(trackKey.trackId))
        require(queueGenerationId.isNotBlank() && queueEntryId.isNotBlank() && accountId.isNotBlank())
        require(queueRevision >= 0L && sourceGeneration >= 0L && maxAccountedBytes in 1L..LyricsLimits.ACCOUNTED_BYTES)
    }
    override fun toString(): String = "OnlineLyricsRequest(redacted)"
}

/** Queue/account/configuration/actual-opened identities remain independent of local file authority. */
data class OnlineLyricsFence(
    override val trackKey: TrackKey,
    val queueGenerationId: String,
    val queueRevision: Long,
    val queueEntryId: String,
    override val sourceGeneration: Long,
    val ownerHash: String,
    val accountGeneration: Long,
    val origin: OnlineLyricsOrigin,
    val configurationGeneration: Long? = null,
    val openedOperationId: String? = null,
) : LyricsSourceFence {
    init {
        require(trackKey.providerId == "netease" && onlineCanonicalId(trackKey.trackId))
        require(queueGenerationId.isNotBlank() && queueEntryId.isNotBlank())
        require(queueRevision >= 0L && sourceGeneration >= 0L && accountGeneration >= 0L)
        require(ownerHash.matches(Regex("[0-9a-f]{64}")))
        require(if (origin == OnlineLyricsOrigin.OPENED_METING_LRC)
            configurationGeneration != null && configurationGeneration >= 0L && !openedOperationId.isNullOrBlank()
            else configurationGeneration == null)
    }

    override fun matchesSeekBinding(binding: QueueSeekBinding): Boolean =
        binding.generationId == queueGenerationId && binding.queueRevision == queueRevision &&
            binding.queueEntryId == queueEntryId && binding.locationId == null && binding.contentRevision == 0L

    override fun toString(): String = "OnlineLyricsFence(redacted)"
}

sealed interface OnlineLyricsResult {
    class Loaded(
        val fence: OnlineLyricsFence,
        val document: LyricsDocument,
        val translation: LyricsTranslation?,
        val accountedBytes: Long,
        val fromCache: Boolean,
        val translationIssue: OnlineLyricsIssue? = null,
    ) : OnlineLyricsResult {
        init { require(accountedBytes in 1L..LyricsLimits.ACCOUNTED_BYTES) }
    }
    class Unavailable(val issue: OnlineLyricsIssue) : OnlineLyricsResult
}

/** Only a visible display or authenticated external reading authorizes the current-track intent. */
interface OnlineLyricsPort {
    val changes: Flow<Unit>
    suspend fun load(request: OnlineLyricsRequest, isCurrent: () -> Boolean): OnlineLyricsResult
    suspend fun validate(loaded: OnlineLyricsResult.Loaded): Boolean
}
