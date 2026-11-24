package io.github.onlinemo.meloxwatch.ui.lyrics.entry

import io.github.onlinemo.meloxwatch.model.QueueSeekBinding
import io.github.onlinemo.meloxwatch.model.QueueSeekRequest
import kotlinx.coroutines.flow.Flow

/** Reads the existing controller on its application looper; it never owns another player or clock. */
data class LyricsMediaClock(
    val queueEntryId: String,
    val positionMs: Long,
    val durationMs: Long?,
    val isPlaying: Boolean,
    val speed: Float,
    val canSeek: Boolean,
) {
    init {
        require(queueEntryId.isNotBlank() && positionMs >= 0L)
        require(durationMs == null || durationMs >= 0L)
        require(speed.isFinite() && speed > 0f)
    }
}

/** All methods and collectors belong to the same UI thread as the one MediaController. */
interface LyricsPlaybackPort {
    val changes: Flow<Unit>
    /** Other playback/input work, excluding this lyrics owner's own pending confirmation. */
    val busy: Boolean
    fun clock(): LyricsMediaClock?
    suspend fun acquireSeekBinding(): QueueSeekBinding?
    fun isSeekBindingCurrent(binding: QueueSeekBinding): Boolean
    suspend fun dispatchSeek(request: QueueSeekRequest): Boolean
}
