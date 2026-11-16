package io.github.onlinemo.meloxwatch.playback

import io.github.onlinemo.meloxwatch.model.QueueSnapshot

/** 服务只透传一次性对象；副本、旧切换和已成功恢复的对象均不能再控制后端。 */
data class QueueBackendCheckpoint internal constructor(
    val snapshot: QueueSnapshot?,
    val positionMs: Long,
    val completedCurrentCycles: Int,
    val playbackCycle: Long,
    internal val handledNaturalCycle: Long,
    internal val checkpointEpoch: Long,
    internal val intentToken: Long,
    internal val backendGeneration: Long,
)
