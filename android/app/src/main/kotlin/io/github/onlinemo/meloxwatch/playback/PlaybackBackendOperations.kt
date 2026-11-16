package io.github.onlinemo.meloxwatch.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/** Queue commands finish only after the active backend has completed preparation or seeking. */
@UnstableApi
interface PlaybackBackendOperations {
    val player: Player

    /** Ordinary completion remains paused unless the queue has authorized a continuous boundary. */
    fun setPauseAtBoundary(pause: Boolean)

    suspend fun prepareAndAwait(expectedMediaId: String)

    suspend fun seekAndAwait(expectedMediaId: String, positionMs: Long)
}
