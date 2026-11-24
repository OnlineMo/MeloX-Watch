package io.github.onlinemo.meloxwatch.model

/** 新列表点选和恢复/交换已保存排列是两种不同意图，恢复不能重洗牌。 */
enum class QueueOrderPolicy {
    MATERIALIZE_MODE,
    PRESERVE_SUPPLIED,
}

/** 编辑针对实例；相同 TrackKey 的其它队列位置不受影响。 */
sealed interface QueueMutation {
    data class InsertNext(
        val view: FrozenQueueView,
        val anchorEntryId: String?,
        val startInserted: Boolean = false,
    ) : QueueMutation {
        init {
            require(view.totalCount > 0L)
            require(anchorEntryId == null || anchorEntryId.isNotBlank())
        }
    }

    data class Remove(val entryId: String, val confirmedCurrent: Boolean = false) : QueueMutation {
        init { require(entryId.isNotBlank()) }
    }

    data class Move(val entryId: String, val destinationPlaybackOrder: Long) : QueueMutation {
        init {
            require(entryId.isNotBlank())
            require(destinationPlaybackOrder >= 0L)
        }
    }

    data class SetMode(val mode: QueueMode) : QueueMutation
}

/**
 * currentEntryId 只围栏切歌，周期 checkpoint 不使编辑冲突。插入 producer 的冻结视图也是
 * 不可变意图的一部分；播放的实时 position 不进入幂等身份。
 */
data class QueueMutationRequest(
    val operationId: String,
    val expectedGenerationId: String?,
    val expectedQueueRevision: Long,
    val expectedCurrentEntryId: String?,
    val mutation: QueueMutation,
) {
    init {
        require(operationId.isNotBlank())
        require(expectedGenerationId == null || expectedGenerationId.isNotBlank())
        require(expectedCurrentEntryId == null || expectedCurrentEntryId.isNotBlank())
        require(expectedQueueRevision >= 0L)
        require(expectedGenerationId != null || (expectedQueueRevision == 0L && expectedCurrentEntryId == null))
    }
}

/**
 * 窗口来自已封存代次；当前移除最多提供默认后项与三个候选，不扫描整队列的资源。
 * 管理性删除即使候选均失效仍能保存；显式切入新插入项必须先通过正常资源核验。
 */
data class QueueMutationCandidate(
    val previous: QueueSnapshot?,
    val windows: List<QueueWindow>,
    val defaultCheckpoint: QueueCheckpoint,
    val keepsCurrent: Boolean,
    val preservesCycles: Boolean,
    val currentRemoved: Boolean,
    val firstInsertedEntryId: String?,
) {
    init {
        require(windows.size <= 4)
        require(windows.isEmpty() == (defaultCheckpoint.queueEntryId == null))
        require(defaultCheckpoint.queueEntryId == null || windows.any { it.current.queueEntryId == defaultCheckpoint.queueEntryId })
    }

    val window: QueueWindow? get() = windows.firstOrNull()
}
