package io.github.onlinemo.meloxwatch.model

/** 这些是查询/播放器窗口预算，不是完整队列的数量上限。 */
object QueueLimits {
    const val PAGE_SIZE = 100
    const val PLAYER_WINDOW_SIZE = 3
    const val SCHEMA_VERSION = 1
}

enum class LocalAccess {
    TEMPORARY,
    PERSISTED,
    MEDIA_PERMISSION,
    PERSISTENCE_REJECTED,
    APP_OWNED,
}

/**
 * 本次队列使用的位置快照，不代表加入长期音乐库，也不恢复系统 URI 授权。
 * URI 和逻辑 TrackKey 分开保存，多个访问别名可以引用同一个曲目。
 */
data class LocalLocationReference(
    val locationId: String,
    val uri: String,
    val grantUri: String = uri,
    val grantFlags: Int = 0,
    val access: LocalAccess = LocalAccess.TEMPORARY,
    val contentRevision: Long = 0L,
) {
    init {
        require(locationId.isNotBlank())
        require(uri.isNotBlank())
        require(grantUri.isNotBlank())
        require(contentRevision >= 0L)
    }
}

/** 同曲重复入队使用不同 queueEntryId；重排同一个实例保留其 ID。 */
data class QueueEntry(
    val queueEntryId: String,
    val trackKey: TrackKey,
    val originalOrder: Long,
    val playbackOrder: Long,
    val displayName: String? = null,
    val durationMs: Long? = null,
    val location: LocalLocationReference? = null,
    val accountId: String? = null,
    val cueReference: LocalCueReference? = null,
) {
    init {
        require(queueEntryId.isNotBlank())
        require(originalOrder >= 0L && playbackOrder >= 0L)
        require(durationMs == null || durationMs >= 0L)
        require(accountId == null || accountId.isNotBlank())
        require(trackKey.providerId != TrackKey.LOCAL_PROVIDER || accountId == null)
        cueReference?.requireEntry(trackKey, location)
    }
}

enum class QueueRepeatMode {
    OFF,
    LIST,
    SINGLE,
}

data class QueueMode(
    val shuffle: Boolean = false,
    val repeat: QueueRepeatMode = QueueRepeatMode.OFF,
    val cycleLimit: Int? = null,
) {
    init {
        require(cycleLimit == null || cycleLimit in 1..99)
    }
}

/** 生产者须提供同一冻结视图；revision 相同但查询不同也必须有不同 id。 */
data class FrozenQueueView(val id: String, val revision: Long, val totalCount: Long) {
    init {
        require(id.isNotBlank())
        require(revision >= 0L && totalCount >= 0L)
    }
}

/** 不保存自动播放指令；进程恢复后只能恢复暂停状态。 */
data class QueueCheckpoint(
    val queueEntryId: String?,
    val positionMs: Long,
    val completedCycles: Int = 0,
) {
    init {
        require(queueEntryId == null || queueEntryId.isNotBlank())
        require(positionMs >= 0L && completedCycles >= 0)
        require(queueEntryId != null || (positionMs == 0L && completedCycles == 0))
    }
}

data class QueueSnapshot(
    val generationId: String,
    val queueRevision: Long,
    val checkpointRevision: Long,
    val view: FrozenQueueView,
    val mode: QueueMode,
    val checkpoint: QueueCheckpoint,
    val currentOriginalOrder: Long?,
    val currentPlaybackOrder: Long?,
    val schemaVersion: Int = QueueLimits.SCHEMA_VERSION,
) {
    val entryCount: Long get() = view.totalCount
    val currentEntryId: String? get() = checkpoint.queueEntryId
    val positionMs: Long get() = checkpoint.positionMs
}

data class QueueWindow(
    val generationId: String,
    val queueRevision: Long,
    val totalCount: Long,
    val entries: List<QueueEntry>,
    val currentIndex: Int,
) {
    init {
        require(entries.isNotEmpty() && entries.size <= QueueLimits.PLAYER_WINDOW_SIZE)
        require(currentIndex in entries.indices)
    }

    val current: QueueEntry get() = entries[currentIndex]
    val hasPrevious: Boolean get() = current.playbackOrder > 0L
    val hasNext: Boolean get() = current.playbackOrder < totalCount - 1L
}
