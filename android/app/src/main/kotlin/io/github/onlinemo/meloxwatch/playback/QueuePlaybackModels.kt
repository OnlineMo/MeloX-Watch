package io.github.onlinemo.meloxwatch.playback

import io.github.onlinemo.meloxwatch.data.queue.QueueStoreIssue
import io.github.onlinemo.meloxwatch.model.QueueMode
import io.github.onlinemo.meloxwatch.model.QueueRepeatMode
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import io.github.onlinemo.meloxwatch.playback.resources.PlaybackResourceStatus

enum class QueuePlaybackIssue {
    BUSY,
    CONFLICT,
    VIEW_EXPIRED,
    SOURCE_CHANGED,
    CONTENT_UNKNOWN,
    NOT_READY,
    CLOSED,
    STORAGE_FULL,
    STORAGE_UNAVAILABLE,
    RECOVERY_REQUIRED,
    SOURCE_UNSUPPORTED,
    LOCATION_MISSING,
    PERMISSION_DENIED,
    FILE_MISSING,
    SOURCE_DAMAGED,
    SOURCE_UNAVAILABLE,
    SOURCE_READ_TIMEOUT,
    OUTPUT_ERROR,
    NETWORK_UNAVAILABLE,
    NETWORK_TIMEOUT,
    NETWORK_CERTIFICATE,
    NETWORK_RATE_LIMITED,
    LOGIN_REQUIRED,
    SESSION_UNAVAILABLE,
    LOCAL_ONLY_ONLINE_SKIPPED,
    RESOURCE_UNAVAILABLE,
    RESOURCE_AUTH_REQUIRED,
    RESOURCE_INVALID,
    RESOURCE_EXPIRED,
    RESOURCE_REDIRECT,
    RESOURCE_RETRY_EXHAUSTED,
    STREAM_STALLED,
}

data class QueuePlaybackFailure(
    val issue: QueuePlaybackIssue,
    val entryId: String? = null,
    val queueStoreIssue: QueueStoreIssue? = null,
)

data class QueuePlaybackState(
    val snapshot: QueueSnapshot? = null,
    val preparing: Boolean = false,
    val failure: QueuePlaybackFailure? = null,
    val resourceStatus: PlaybackResourceStatus? = null,
    val gapless: GaplessPlaybackState = GaplessPlaybackState(),
)

class QueuePlaybackException(val failure: QueuePlaybackFailure) : Exception(failure.issue.name)

/** 逻辑序号属于完整冻结序列，绝不是 ExoPlayer 的三项窗口索引。 */
internal fun manualQueueTarget(current: Long, count: Long, repeat: QueueRepeatMode, direction: Int): Long? {
    require(direction == -1 || direction == 1)
    if (count == 0L) return null
    require(current in 0L until count)
    return if (direction > 0) {
        if (current < count - 1L) current + 1L else if (repeat == QueueRepeatMode.LIST) 0L else null
    } else {
        if (current > 0L) current - 1L else if (repeat == QueueRepeatMode.LIST) count - 1L else null
    }
}

internal data class NaturalQueueAdvance(val targetOrder: Long?, val completedCycles: Int)

internal fun naturalQueueAdvance(current: Long, count: Long, mode: QueueMode, completedCycles: Int): NaturalQueueAdvance {
    require(count > 0L && current in 0L until count && completedCycles >= 0)
    val cycles = if (completedCycles == Int.MAX_VALUE) Int.MAX_VALUE else completedCycles + 1
    if (mode.repeat == QueueRepeatMode.SINGLE && (mode.cycleLimit == null || cycles < mode.cycleLimit)) {
        return NaturalQueueAdvance(current, cycles)
    }
    val next = manualQueueTarget(current, count, mode.repeat, 1)
    return NaturalQueueAdvance(next, if (next == null && mode.repeat == QueueRepeatMode.SINGLE) cycles else 0)
}
