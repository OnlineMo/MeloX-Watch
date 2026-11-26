package io.github.onlinemo.meloxwatch.playback

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/** Queue commands finish only after the active backend has completed preparation or seeking. */
@UnstableApi
interface PlaybackBackendOperations {
    val player: Player

    /** 同一内核可自行衔接其已装载的相邻项；不代表任何编码/设备已通过波形验收。 */
    val supportsContinuousPlayback: Boolean get() = false

    /** 与真实rolling source factory成对；原位尾部finalize不替换当前物理源。 */
    val supportsRollingQueueTail: Boolean get() = false

    /** Ordinary completion remains paused unless the queue has authorized a continuous boundary. */
    fun setPauseAtBoundary(pause: Boolean)

    suspend fun prepareAndAwait(expectedMediaId: String)

    suspend fun seekAndAwait(expectedMediaId: String, positionMs: Long)
}
