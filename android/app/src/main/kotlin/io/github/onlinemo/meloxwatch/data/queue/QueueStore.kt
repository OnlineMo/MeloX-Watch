package io.github.onlinemo.meloxwatch.data.queue

import io.github.onlinemo.meloxwatch.model.FrozenQueueView
import io.github.onlinemo.meloxwatch.model.QueueCheckpoint
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueLimits
import io.github.onlinemo.meloxwatch.model.QueueMode
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import io.github.onlinemo.meloxwatch.model.QueueWindow
import kotlinx.coroutines.flow.Flow

data class QueueReplacementRequest(
    val operationId: String,
    val expectedGenerationId: String?,
    val expectedQueueRevision: Long,
    val view: FrozenQueueView,
    val mode: QueueMode = QueueMode(),
    val startOriginalOrder: Long = 0L,
    val initialPositionMs: Long = 0L,
    // 仅明确清队列/移除最后一个实例的动作可设为 true，空列表点歌不能清旧队列。
    val allowEmpty: Boolean = false,
) {
    init {
        require(operationId.isNotBlank())
        require(expectedGenerationId == null || expectedGenerationId.isNotBlank())
        require(expectedQueueRevision >= 0L)
        require(startOriginalOrder >= 0L && initialPositionMs >= 0L)
        require(view.totalCount == 0L || startOriginalOrder < view.totalCount)
        require(view.totalCount != 0L || startOriginalOrder == 0L)
        require(view.totalCount != 0L || initialPositionMs == 0L)
    }
}

data class QueueInputPage(
    val view: FrozenQueueView,
    val offset: Long,
    val entries: List<QueueEntry>,
)

/** 文件/网络读取发生在调用方，Store 不在 SQLite 事务内调用生产者。 */
fun interface QueuePageSource {
    suspend fun read(offset: Long, limit: Int): QueueInputPage
}

/** 重放历史 operation 时 snapshot 是当前活动队列，不会重新激活旧 generation。 */
data class QueueCommit(
    val snapshot: QueueSnapshot,
    val appliedGenerationId: String,
    val wasReplay: Boolean,
)

enum class QueueStoreIssue {
    BUSY,
    CONFLICT,
    SOURCE_CHANGED,
    SOURCE_UNAVAILABLE,
    INCOMPLETE,
    INVALID_INPUT,
    STORAGE_FULL,
    STORAGE_UNAVAILABLE,
    RECOVERY_REQUIRED,
}

class QueueStoreException(val issue: QueueStoreIssue, cause: Throwable? = null) :
    Exception(issue.name, cause)

/**
 * 应用范围只有一个 Store 和一个暂存构造者；队列与媒体会话的权威播放状态分开。
 *
 * replace 按 100 条构造待发布代次，校验完整排列后短事务切活动指针。
 * 取消或失败发生在提交点之前时保留旧队列；提交点之后结果是完整新队列。
 * snapshot 不清库、不查询 URI，也不授予访问权；调用者恢复播放器时保持暂停。
 */
interface QueueStore {
    /** 应用初始化时调用；回收上次中断的暂存及非活动大队列，保留幂等记录。 */
    suspend fun initialize(): QueueSnapshot?

    suspend fun snapshot(): QueueSnapshot?

    fun snapshots(): Flow<QueueSnapshot?>

    suspend fun replace(request: QueueReplacementRequest, source: QueuePageSource): QueueCommit

    /** 以已冻结播放序号作数据库锚点，返回至多 100 条，不以 SQL OFFSET 走过前页。 */
    suspend fun page(
        generationId: String,
        firstPlaybackOrder: Long,
        limit: Int = QueueLimits.PAGE_SIZE,
    ): List<QueueEntry>

    /** 返回当前及前后各一个实例；hasNext/hasPrevious 指完整逻辑队列边界。 */
    suspend fun window(generationId: String, currentEntryId: String): QueueWindow

    /** checkpointRevision 独立递增，旧 IO 完成不能覆盖后来确认的切歌/seek。 */
    suspend fun saveCheckpoint(
        generationId: String,
        expectedCheckpointRevision: Long,
        checkpoint: QueueCheckpoint,
    ): QueueSnapshot
}
