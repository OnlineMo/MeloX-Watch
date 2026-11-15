package io.github.onlinemo.meloxwatch.data.queue

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import androidx.room.withTransaction
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.model.QueueCheckpoint
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueLimits
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import io.github.onlinemo.meloxwatch.model.QueueWindow
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/** 一次只有一个构造者；SQLite 完整引用与播放器窗口独立，不读取文件或联网。 */
class RoomQueueStore(private val database: MeloXDatabase) : QueueStore {
    private val dao = database.queueDao()
    private val preparation = Mutex()
    @Volatile
    private var initialized = false

    override suspend fun initialize(): QueueSnapshot? = preparing {
        initializeLocked()
        snapshot()
    }

    override suspend fun snapshot(): QueueSnapshot? = databaseAccess {
        database.withTransaction {
            readSnapshot()?.also { active ->
                if (!initialized) validateOrders(dao.orderStats(active.generationId), active.entryCount, QueueStoreIssue.RECOVERY_REQUIRED)
            }
        }
    }

    override fun snapshots(): Flow<QueueSnapshot?> = dao.observeHead()
        .map { snapshot() }
        .distinctUntilChanged()

    override suspend fun replace(request: QueueReplacementRequest, source: QueuePageSource): QueueCommit = preparing {
        if (!initialized) initializeLocked()
        if ((request.view.totalCount == 0L && !request.allowEmpty) || request.expectedQueueRevision == Long.MAX_VALUE) {
            throw QueueStoreException(QueueStoreIssue.INVALID_INPUT)
        }
        val previousOperation = databaseAccess { dao.operation(request.operationId) }
        if (previousOperation != null) {
            if (!previousOperation.matches(request)) throw QueueStoreException(QueueStoreIssue.INVALID_INPUT)
            if (previousOperation.phase == QueuePhase.PUBLISHED) {
                // 幂等只承认曾经完成；旧操作不能重新发布已经被新意图替换的代次。
                return@preparing QueueCommit(snapshot() ?: recoveryRequired(), previousOperation.generationId, true)
            }
            if (previousOperation.phase == QueuePhase.STAGING || previousOperation.phase == QueuePhase.SEALED) {
                throw QueueStoreException(QueueStoreIssue.BUSY)
            }
            if (previousOperation.phase != QueuePhase.ABORTED) recoveryRequired()
        }
        val generationId = previousOperation?.generationId ?: UUID.randomUUID().toString()
        var published = false
        try {
            if (previousOperation != null) cleanupAbandoned(generationId)
            begin(request, generationId, previousOperation != null)
            stage(request, source, generationId)
            val current = seal(request, generationId)
            currentCoroutineContext().ensureActive()
            // 提交点开始后完成短事务，避免“已换指针但被当作未提交取消”的歧义。
            val committed = withContext(NonCancellable) {
                val committed = publish(request, generationId, current)
                published = true
                QueueCommit(committed, generationId, false)
            }
            try {
                // 可取消的分页清理不属于原子提交；关闭不必等非活动大队列逐条回收。
                cleanupInactive()
            } catch (_: CancellationException) {
                // 已提交结果保持完整，下次 initialize/replace 再回收剩余行。
            } catch (_: QueueStoreException) {
                // 完整新队列已经提交；清理故障不能报提交失败。
            }
            committed
        } finally {
            if (!published) {
                withContext(NonCancellable) {
                    try {
                        databaseAccess { dao.abort(generationId) }
                        databaseAccess { database.withTransaction { dao.deleteAbandonedBatch(generationId, QueueLimits.PAGE_SIZE) } }
                    } catch (_: QueueStoreException) {
                        // 只回收一个预算批，剩余非活动行留给下次恢复；旧完整队列不受影响。
                    }
                }
                if (currentCoroutineContext().isActive) {
                    try {
                        cleanupAbandoned(generationId)
                    } catch (_: CancellationException) {
                        // 清理被新取消打断时，finally 的短批与 ABORTED 日志已足够保留恢复路径。
                    } catch (_: QueueStoreException) {
                        // 非活动残行由下一次恢复继续，不改变这次失败的原始错误。
                    }
                }
            }
        }
    }

    override suspend fun page(generationId: String, firstPlaybackOrder: Long, limit: Int): List<QueueEntry> {
        if (firstPlaybackOrder < 0L || limit !in 1..QueueLimits.PAGE_SIZE) throw QueueStoreException(QueueStoreIssue.INVALID_INPUT)
        return databaseAccess {
            database.withTransaction {
                requireActive(generationId)
                dao.page(generationId, firstPlaybackOrder, limit).map { it.model() }
            }
        }
    }

    override suspend fun window(generationId: String, currentEntryId: String): QueueWindow = databaseAccess {
        database.withTransaction {
            val active = requireActive(generationId)
            val current = dao.entry(generationId, currentEntryId) ?: throw QueueStoreException(QueueStoreIssue.CONFLICT)
            val first = maxOf(0L, current.playbackOrder - 1L)
            val last = minOf(active.entryCount - 1L, current.playbackOrder + 1L)
            val entries = dao.window(generationId, first, last).map { it.model() }
            if (entries.size.toLong() != last - first + 1L) recoveryRequired()
            val currentIndex = entries.indexOfFirst { it.queueEntryId == currentEntryId }
            if (currentIndex < 0) recoveryRequired()
            QueueWindow(generationId, active.queueRevision, active.entryCount, entries, currentIndex)
        }
    }

    override suspend fun saveCheckpoint(
        generationId: String,
        expectedCheckpointRevision: Long,
        checkpoint: QueueCheckpoint,
    ): QueueSnapshot {
        if (expectedCheckpointRevision < 0L || expectedCheckpointRevision == Long.MAX_VALUE) {
            throw QueueStoreException(QueueStoreIssue.INVALID_INPUT)
        }
        return databaseAccess {
            database.withTransaction {
                val active = requireActive(generationId)
                if (active.checkpointRevision != expectedCheckpointRevision) throw QueueStoreException(QueueStoreIssue.CONFLICT)
                val current = checkpoint.queueEntryId?.let { dao.entry(generationId, it) }
                if (active.entryCount == 0L) {
                    if (checkpoint.queueEntryId != null) throw QueueStoreException(QueueStoreIssue.INVALID_INPUT)
                } else if (current == null) {
                    throw QueueStoreException(QueueStoreIssue.CONFLICT)
                }
                val position = current?.durationMs?.let { checkpoint.positionMs.coerceAtMost(it) } ?: checkpoint.positionMs
                val changed = dao.checkpoint(
                    generationId,
                    expectedCheckpointRevision,
                    expectedCheckpointRevision + 1L,
                    checkpoint.queueEntryId,
                    position,
                    checkpoint.completedCycles,
                )
                if (changed != 1) throw QueueStoreException(QueueStoreIssue.CONFLICT)
                readSnapshot() ?: recoveryRequired()
            }
        }
    }

    private suspend fun initializeLocked() {
        databaseAccess {
            database.withTransaction {
                dao.insertHead(QueueHeadEntity())
                readSnapshot()?.let { active ->
                    validateOrders(dao.orderStats(active.generationId), active.entryCount, QueueStoreIssue.RECOVERY_REQUIRED)
                }
                dao.abandonInterruptedGenerations()
            }
        }
        cleanupInactive()
        initialized = true
    }

    private suspend fun begin(request: QueueReplacementRequest, generationId: String, restart: Boolean) = databaseAccess {
        database.withTransaction {
            val head = dao.head() ?: recoveryRequired()
            if (head.activeGenerationId != request.expectedGenerationId || head.queueRevision != request.expectedQueueRevision) {
                throw QueueStoreException(QueueStoreIssue.CONFLICT)
            }
            if (restart) {
                if (dao.restartAborted(generationId) != 1) recoveryRequired()
            } else {
                dao.insertGeneration(request.record(generationId))
            }
        }
    }

    private suspend fun stage(request: QueueReplacementRequest, source: QueuePageSource, generationId: String) {
        var offset = 0L
        while (offset < request.view.totalCount) {
            currentCoroutineContext().ensureActive()
            val limit = minOf(QueueLimits.PAGE_SIZE.toLong(), request.view.totalCount - offset).toInt()
            val page = try {
                source.read(offset, limit)
            } catch (error: CancellationException) {
                throw error
            } catch (error: QueueStoreException) {
                throw error
            } catch (error: Exception) {
                throw QueueStoreException(QueueStoreIssue.SOURCE_UNAVAILABLE, error)
            }
            if (page.view != request.view) throw QueueStoreException(QueueStoreIssue.SOURCE_CHANGED)
            if (page.offset != offset) throw QueueStoreException(QueueStoreIssue.INVALID_INPUT)
            if (page.entries.size < limit) throw QueueStoreException(QueueStoreIssue.INCOMPLETE)
            if (page.entries.size > limit) throw QueueStoreException(QueueStoreIssue.INVALID_INPUT)
            val entries = page.entries.toList()
            entries.forEachIndexed { index, entry ->
                if (entry.originalOrder != offset + index || entry.playbackOrder >= request.view.totalCount) {
                    throw QueueStoreException(QueueStoreIssue.INVALID_INPUT)
                }
            }
            currentCoroutineContext().ensureActive()
            databaseAccess(inputRows = true) {
                database.withTransaction {
                    dao.insertEntries(entries.map { it.record(generationId) })
                    if (dao.advanceStaging(generationId, offset, offset + entries.size) != 1) recoveryRequired()
                }
            }
            offset += entries.size
        }
    }

    private suspend fun seal(request: QueueReplacementRequest, generationId: String): QueueEntryEntity? = databaseAccess {
        database.withTransaction {
            val generation = dao.generation(generationId) ?: recoveryRequired()
            val stats = dao.orderStats(generationId)
            val total = request.view.totalCount
            if (generation.writtenCount != total || stats.entryCount != total) throw QueueStoreException(QueueStoreIssue.INCOMPLETE)
            // 唯一索引 + count/min/max 校验完整排列，API26 无需 SQLite 窗口函数。
            validateOrders(stats, total, QueueStoreIssue.INVALID_INPUT)
            val current = if (total == 0L) null else dao.entryAtOriginalOrder(generationId, request.startOriginalOrder) ?: recoveryRequired()
            if (dao.seal(generationId) != 1) recoveryRequired()
            current
        }
    }

    private suspend fun publish(
        request: QueueReplacementRequest,
        generationId: String,
        current: QueueEntryEntity?,
    ): QueueSnapshot = databaseAccess {
        database.withTransaction {
            val position = current?.durationMs?.let { request.initialPositionMs.coerceAtMost(it) } ?: request.initialPositionMs
            val nextRevision = request.expectedQueueRevision + 1L
            if (dao.publish(generationId, request.expectedGenerationId, request.expectedQueueRevision, nextRevision, current?.queueEntryId, position) != 1) {
                throw QueueStoreException(QueueStoreIssue.CONFLICT)
            }
            if (dao.markPublished(generationId, nextRevision) != 1) recoveryRequired()
            readSnapshot() ?: recoveryRequired()
        }
    }

    private suspend fun readSnapshot(): QueueSnapshot? {
        val head = dao.head() ?: return null
        val generationId = head.activeGenerationId ?: run {
            if (head.queueRevision != 0L || head.currentEntryId != null || head.positionMs != 0L ||
                head.checkpointRevision != 0L || head.completedCycles != 0
            ) recoveryRequired()
            return null
        }
        val generation = dao.generation(generationId) ?: recoveryRequired()
        val current = head.currentEntryId?.let { dao.entry(generationId, it) ?: recoveryRequired() }
        return generation.snapshot(head, current)
    }

    private suspend fun requireActive(generationId: String): QueueSnapshot {
        val active = readSnapshot() ?: throw QueueStoreException(QueueStoreIssue.CONFLICT)
        if (active.generationId != generationId) throw QueueStoreException(QueueStoreIssue.CONFLICT)
        return active
    }

    private fun validateOrders(stats: QueueOrderStats, total: Long, issue: QueueStoreIssue) {
        val complete = if (total == 0L) {
            stats.entryCount == 0L && stats.firstOriginalOrder == null && stats.lastOriginalOrder == null &&
                stats.firstPlaybackOrder == null && stats.lastPlaybackOrder == null
        } else {
            stats.entryCount == total && stats.firstOriginalOrder == 0L && stats.lastOriginalOrder == total - 1L &&
                stats.firstPlaybackOrder == 0L && stats.lastPlaybackOrder == total - 1L
        }
        if (!complete) throw QueueStoreException(issue)
    }

    private suspend fun cleanupInactive() {
        do {
            val deleted = databaseAccess { database.withTransaction { dao.deleteInactiveBatch(QueueLimits.PAGE_SIZE) } }
            if (deleted > 0) yield()
        } while (deleted > 0)
    }

    private suspend fun cleanupAbandoned(generationId: String) {
        do {
            val deleted = databaseAccess { database.withTransaction { dao.deleteAbandonedBatch(generationId, QueueLimits.PAGE_SIZE) } }
            if (deleted > 0) yield()
        } while (deleted > 0)
    }

    private suspend fun <T> preparing(block: suspend () -> T): T {
        if (!preparation.tryLock()) throw QueueStoreException(QueueStoreIssue.BUSY)
        return try {
            block()
        } finally {
            preparation.unlock()
        }
    }

    private suspend fun <T> databaseAccess(inputRows: Boolean = false, block: suspend () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: QueueStoreException) {
        throw error
    } catch (error: SQLiteFullException) {
        throw QueueStoreException(QueueStoreIssue.STORAGE_FULL, error)
    } catch (error: SQLiteConstraintException) {
        throw QueueStoreException(if (inputRows) QueueStoreIssue.INVALID_INPUT else QueueStoreIssue.RECOVERY_REQUIRED, error)
    } catch (error: SQLiteException) {
        throw QueueStoreException(QueueStoreIssue.STORAGE_UNAVAILABLE, error)
    } catch (error: IllegalStateException) {
        // 缺迁移、关闭的连接或损坏结构都不能通过 destructive fallback 丢弃旧队列。
        throw QueueStoreException(QueueStoreIssue.RECOVERY_REQUIRED, error)
    } catch (error: IllegalArgumentException) {
        throw QueueStoreException(QueueStoreIssue.RECOVERY_REQUIRED, error)
    }
}
