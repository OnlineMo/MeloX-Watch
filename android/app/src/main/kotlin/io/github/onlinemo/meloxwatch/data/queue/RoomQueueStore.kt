package io.github.onlinemo.meloxwatch.data.queue

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteFullException
import androidx.sqlite.SQLiteException
import io.github.onlinemo.meloxwatch.data.database.toPlatformSQLiteException
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.database.withPreservedTransaction
import io.github.onlinemo.meloxwatch.model.QueueCheckpoint
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueLimits
import io.github.onlinemo.meloxwatch.model.QueueMutation
import io.github.onlinemo.meloxwatch.model.QueueMutationCandidate
import io.github.onlinemo.meloxwatch.model.QueueMutationRequest
import io.github.onlinemo.meloxwatch.model.QueueOrderPolicy
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import io.github.onlinemo.meloxwatch.model.QueueWindow
import java.util.UUID
import kotlin.random.Random
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
class RoomQueueStore(
    private val database: MeloXDatabase,
    private val shuffleIndex: (Long) -> Long = { last -> Random.Default.nextLong(1L, last + 1L) },
) : QueueStore {
    private val dao = database.queueDao()
    private val preparation = Mutex()
    @Volatile
    private var initialized = false

    override suspend fun initialize(): QueueSnapshot? = preparing {
        initializeLocked()
        snapshot()
    }

    override suspend fun snapshot(): QueueSnapshot? = databaseAccess {
        database.withPreservedTransaction {
            readSnapshot()?.also { active ->
                if (!initialized) validateOrders(dao.orderStats(active.generationId), active.entryCount, QueueStoreIssue.RECOVERY_REQUIRED)
            }
        }
    }

    override fun snapshots(): Flow<QueueSnapshot?> = dao.observeHead()
        .map { snapshot() }
        .distinctUntilChanged()

    override suspend fun replace(
        request: QueueReplacementRequest,
        source: QueuePageSource,
        beforePublish: suspend (QueueWindow?) -> Unit,
    ): QueueCommit = preparing {
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
        // Hand off a committed queue without another full-generation IO pass. Reclaim the previous
        // inactive rows before staging this one, keeping one constructor and bounded generations.
        cleanupInactive()
        val generationId = previousOperation?.generationId ?: UUID.randomUUID().toString()
        var published = false
        try {
            if (previousOperation != null) cleanupAbandoned(generationId)
            begin(request, generationId, previousOperation != null)
            stage(request, source, generationId)
            if (request.orderPolicy == QueueOrderPolicy.MATERIALIZE_MODE) {
                validateStaged(generationId, request.view.totalCount)
                if (request.mode.shuffle && request.view.totalCount > 0L) {
                    val selected = databaseAccess { dao.entryAtOriginalOrder(generationId, request.startOriginalOrder) } ?: recoveryRequired()
                    materializeShuffle(generationId, request.view.totalCount, selected.queueEntryId)
                } else restoreOriginalOrder(generationId, request.view.totalCount)
            }
            val sealed = seal(request, generationId)
            // The producer is a forward-only cursor. Validate the selected staged rows after sealing,
            // outside any SQLite transaction; a rejected resource must not replace the active pointer.
            beforePublish(sealed.window)
            currentCoroutineContext().ensureActive()
            // 提交点开始后完成短事务，避免“已换指针但被当作未提交取消”的歧义。
            val committed = withContext(NonCancellable) {
                val committed = publish(request, generationId, sealed.current)
                published = true
                QueueCommit(committed, generationId, false)
            }
            committed
        } finally {
            if (!published) {
                withContext(NonCancellable) {
                    try {
                        databaseAccess { dao.abort(generationId) }
                        databaseAccess { database.withPreservedTransaction { dao.deleteAbandonedBatch(generationId, QueueLimits.PAGE_SIZE) } }
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

    override suspend fun mutate(
        request: QueueMutationRequest,
        source: QueuePageSource?,
        beforePublish: suspend (QueueMutationCandidate) -> QueueCheckpoint?,
    ): QueueCommit = preparing {
        if (!initialized) initializeLocked()
        if (request.expectedQueueRevision == Long.MAX_VALUE) throw QueueStoreException(QueueStoreIssue.INVALID_INPUT)
        val previousOperation = databaseAccess { dao.operation(request.operationId) }
        if (previousOperation != null) {
            if (!previousOperation.matches(request)) throw QueueStoreException(QueueStoreIssue.INVALID_INPUT)
            if (previousOperation.phase == QueuePhase.PUBLISHED) {
                return@preparing QueueCommit(snapshot() ?: recoveryRequired(), previousOperation.generationId, true)
            }
            if (previousOperation.phase in listOf(QueuePhase.STAGING, QueuePhase.SEALED)) throw QueueStoreException(QueueStoreIssue.BUSY)
            if (previousOperation.phase != QueuePhase.ABORTED) recoveryRequired()
        }
        if ((request.mutation is QueueMutation.InsertNext) != (source != null)) throw QueueStoreException(QueueStoreIssue.INVALID_INPUT)
        cleanupInactive()
        val generationId = previousOperation?.generationId ?: UUID.randomUUID().toString()
        var published = false
        try {
            if (previousOperation != null) cleanupAbandoned(generationId)
            val plan = beginMutation(request, generationId, previousOperation != null)
            stageMutation(plan, source, generationId)
            if (plan.materializesShuffle && plan.totalCount > 0L) {
                materializeShuffle(generationId, plan.totalCount, requireNotNull(plan.previous?.currentEntryId))
            }
            val candidate = sealMutation(plan, generationId)
            val continuation = beforePublish(candidate)
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                val committed = publishMutation(plan, generationId, candidate, continuation)
                published = true
                QueueCommit(committed, generationId, false)
            }
        } finally {
            if (!published) discardUnpublished(generationId)
        }
    }

    override suspend fun page(generationId: String, firstPlaybackOrder: Long, limit: Int): List<QueueEntry> {
        if (firstPlaybackOrder < 0L || limit !in 1..QueueLimits.PAGE_SIZE) throw QueueStoreException(QueueStoreIssue.INVALID_INPUT)
        return databaseAccess {
            database.withPreservedTransaction {
                requireActive(generationId)
                dao.page(generationId, firstPlaybackOrder, limit).map { it.model() }
            }
        }
    }

    override suspend fun window(generationId: String, currentEntryId: String): QueueWindow = databaseAccess {
        database.withPreservedTransaction {
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
            database.withPreservedTransaction {
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
            database.withPreservedTransaction {
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
        database.withPreservedTransaction {
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
                database.withPreservedTransaction {
                    dao.insertEntries(entries.map { it.record(generationId) })
                    if (dao.advanceStaging(generationId, offset, offset + entries.size) != 1) recoveryRequired()
                }
            }
            offset += entries.size
        }
    }

    private suspend fun beginMutation(
        request: QueueMutationRequest,
        generationId: String,
        restart: Boolean,
    ): QueueMutationPlan = databaseAccess {
        database.withPreservedTransaction {
            requireMutationHead(request)
            val previous = readSnapshot()
            val count = previous?.entryCount ?: 0L
            val referenceId = when (val edit = request.mutation) {
                is QueueMutation.InsertNext -> {
                    if (edit.anchorEntryId != request.expectedCurrentEntryId) throw QueueStoreException(QueueStoreIssue.INVALID_INPUT)
                    edit.anchorEntryId
                }
                is QueueMutation.Remove -> edit.entryId
                is QueueMutation.Move -> edit.entryId
                is QueueMutation.SetMode -> null
            }
            val referenced = referenceId?.let { id ->
                val parent = previous?.generationId ?: throw QueueStoreException(QueueStoreIssue.CONFLICT)
                dao.entry(parent, id) ?: throw QueueStoreException(QueueStoreIssue.CONFLICT)
            }
            val total = when (val edit = request.mutation) {
                is QueueMutation.InsertNext -> {
                    if (count > Long.MAX_VALUE - edit.view.totalCount) throw QueueStoreException(QueueStoreIssue.INVALID_INPUT)
                    count + edit.view.totalCount
                }
                is QueueMutation.Remove -> {
                    if (count == 0L || (edit.entryId == previous?.currentEntryId && !edit.confirmedCurrent)) {
                        throw QueueStoreException(QueueStoreIssue.INVALID_INPUT)
                    }
                    count - 1L
                }
                is QueueMutation.Move -> {
                    if (count == 0L || edit.destinationPlaybackOrder >= count) throw QueueStoreException(QueueStoreIssue.INVALID_INPUT)
                    count
                }
                is QueueMutation.SetMode -> count
            }
            val plan = QueueMutationPlan(request, previous, referenced, total)
            if (restart) {
                if (dao.restartAborted(generationId) != 1) recoveryRequired()
            } else dao.insertGeneration(plan.record(generationId))
            plan
        }
    }

    private suspend fun requireMutationHead(request: QueueMutationRequest): QueueHeadEntity {
        val head = dao.head() ?: recoveryRequired()
        if (head.activeGenerationId != request.expectedGenerationId || head.queueRevision != request.expectedQueueRevision ||
            head.currentEntryId != request.expectedCurrentEntryId
        ) throw QueueStoreException(QueueStoreIssue.CONFLICT)
        return head
    }

    private suspend fun stageMutation(plan: QueueMutationPlan, source: QueuePageSource?, generationId: String) {
        var cursor = 0L
        var written = 0L
        val previous = plan.previous
        while (previous != null && cursor < previous.entryCount) {
            currentCoroutineContext().ensureActive()
            val expected = minOf(QueueLimits.PAGE_SIZE.toLong(), previous.entryCount - cursor).toInt()
            val nextWritten = databaseAccess {
                database.withPreservedTransaction {
                    requireMutationHead(plan.request)
                    val page = dao.page(previous.generationId, cursor, expected)
                    if (page.size != expected || page.first().playbackOrder != cursor ||
                        page.last().playbackOrder != cursor + expected - 1L
                    ) recoveryRequired()
                    val transformed = page.mapNotNull { plan.transform(it, generationId) }
                    if (transformed.isNotEmpty()) dao.insertEntries(transformed)
                    val next = written + transformed.size
                    if (dao.advanceStaging(generationId, written, next) != 1) recoveryRequired()
                    next
                }
            }
            written = nextWritten
            cursor += expected
            yield()
        }
        val insertion = plan.request.mutation as? QueueMutation.InsertNext ?: return
        val producer = source ?: throw QueueStoreException(QueueStoreIssue.INVALID_INPUT)
        var inputOrder = 0L
        while (inputOrder < insertion.view.totalCount) {
            currentCoroutineContext().ensureActive()
            val limit = minOf(QueueLimits.PAGE_SIZE.toLong(), insertion.view.totalCount - inputOrder).toInt()
            val page = try { producer.read(inputOrder, limit) }
            catch (error: CancellationException) { throw error }
            catch (error: QueueStoreException) { throw error }
            catch (error: Exception) { throw QueueStoreException(QueueStoreIssue.SOURCE_UNAVAILABLE, error) }
            if (page.view != insertion.view) throw QueueStoreException(QueueStoreIssue.SOURCE_CHANGED)
            if (page.offset != inputOrder || page.entries.size > limit) throw QueueStoreException(QueueStoreIssue.INVALID_INPUT)
            if (page.entries.size != limit) throw QueueStoreException(QueueStoreIssue.INCOMPLETE)
            val entries = page.entries.toList()
            entries.forEachIndexed { index, entry ->
                if (entry.originalOrder != inputOrder + index) throw QueueStoreException(QueueStoreIssue.INVALID_INPUT)
            }
            currentCoroutineContext().ensureActive()
            databaseAccess(inputRows = true) {
                database.withPreservedTransaction {
                    requireMutationHead(plan.request)
                    dao.insertEntries(entries.map { plan.inserted(it, generationId) })
                    if (dao.advanceStaging(generationId, written, written + entries.size) != 1) recoveryRequired()
                }
            }
            written += entries.size
            inputOrder += entries.size
            yield()
        }
    }

    private suspend fun sealMutation(plan: QueueMutationPlan, generationId: String): QueueMutationCandidate = databaseAccess {
        database.withPreservedTransaction {
            requireMutationHead(plan.request)
            validateStagedInside(generationId, plan.totalCount)
            val previous = readSnapshot()
            val firstInserted = (plan.request.mutation as? QueueMutation.InsertNext)?.let { insertedEntryId(generationId, 0L) }
            val target = when {
                plan.totalCount == 0L -> null
                plan.keepsCurrent -> dao.entry(generationId, requireNotNull(previous?.currentEntryId)) ?: recoveryRequired()
                firstInserted != null -> dao.entry(generationId, firstInserted) ?: recoveryRequired()
                else -> dao.page(generationId, minOf(requireNotNull(plan.referenced).playbackOrder, plan.totalCount - 1L), 1).singleOrNull() ?: recoveryRequired()
            }
            if (dao.seal(generationId) != 1) recoveryRequired()
            val targets = if (target == null) emptyList() else if (plan.currentRemoved) {
                dao.page(generationId, target.playbackOrder, 4)
            } else listOf(target)
            val windows = targets.map { stagedWindow(generationId, plan.request.expectedQueueRevision + 1L, plan.totalCount, it) }
            val checkpoint = QueueCheckpoint(
                target?.queueEntryId,
                if (plan.keepsCurrent) previous?.positionMs ?: 0L else 0L,
                if (plan.preservesCycles) previous?.checkpoint?.completedCycles ?: 0 else 0,
            )
            QueueMutationCandidate(previous, windows, checkpoint, plan.keepsCurrent, plan.preservesCycles, plan.currentRemoved, firstInserted)
        }
    }

    private suspend fun stagedWindow(generationId: String, revision: Long, count: Long, current: QueueEntryEntity): QueueWindow {
        val first = maxOf(0L, current.playbackOrder - 1L)
        val last = minOf(count - 1L, current.playbackOrder + 1L)
        val entries = dao.window(generationId, first, last).map { it.model() }
        if (entries.size.toLong() != last - first + 1L) recoveryRequired()
        val currentIndex = entries.indexOfFirst { it.queueEntryId == current.queueEntryId }
        if (currentIndex < 0) recoveryRequired()
        return QueueWindow(generationId, revision, count, entries, currentIndex)
    }

    private suspend fun publishMutation(
        plan: QueueMutationPlan,
        generationId: String,
        candidate: QueueMutationCandidate,
        continuation: QueueCheckpoint?,
    ): QueueSnapshot = databaseAccess {
        database.withPreservedTransaction {
            val oldHead = requireMutationHead(plan.request)
            val checkpoint = continuation ?: if (candidate.keepsCurrent) QueueCheckpoint(
                oldHead.currentEntryId, oldHead.positionMs, if (candidate.preservesCycles) oldHead.completedCycles else 0,
            ) else candidate.defaultCheckpoint
            val current = checkpoint.queueEntryId?.let { id ->
                if (candidate.windows.none { it.current.queueEntryId == id }) throw QueueStoreException(QueueStoreIssue.INVALID_INPUT)
                dao.entry(generationId, id) ?: recoveryRequired()
            }
            if ((plan.totalCount == 0L) != (current == null)) throw QueueStoreException(QueueStoreIssue.INVALID_INPUT)
            val position = current?.durationMs?.let { checkpoint.positionMs.coerceAtMost(it) } ?: checkpoint.positionMs
            val cycles = if (candidate.preservesCycles) checkpoint.completedCycles else 0
            val revision = plan.request.expectedQueueRevision + 1L
            if (dao.publish(
                    generationId, plan.request.expectedGenerationId, plan.request.expectedQueueRevision, revision,
                    current?.queueEntryId, position, cycles, checkCurrent = true,
                    expectedCurrentEntryId = plan.request.expectedCurrentEntryId,
                ) != 1
            ) throw QueueStoreException(QueueStoreIssue.CONFLICT)
            if (dao.markPublished(generationId, revision) != 1) recoveryRequired()
            readSnapshot() ?: recoveryRequired()
        }
    }

    private suspend fun validateStaged(generationId: String, count: Long) = databaseAccess {
        database.withPreservedTransaction { validateStagedInside(generationId, count) }
    }

    private suspend fun validateStagedInside(generationId: String, count: Long) {
        val generation = dao.generation(generationId) ?: recoveryRequired()
        if (generation.phase != QueuePhase.STAGING) recoveryRequired()
        if (generation.writtenCount != count) throw QueueStoreException(QueueStoreIssue.INCOMPLETE)
        validateOrders(dao.orderStats(generationId), count, QueueStoreIssue.INVALID_INPUT)
    }

    /** External-memory Fisher–Yates: only two slots per swap, 100 swaps per writer transaction. */
    private suspend fun materializeShuffle(generationId: String, count: Long, currentEntryId: String) {
        if (count < 2L) return
        databaseAccess {
            database.withPreservedTransaction {
                val current = dao.entry(generationId, currentEntryId) ?: recoveryRequired()
                swapStagingSlots(generationId, current.playbackOrder, 0L)
            }
        }
        var last = count - 1L
        while (last > 1L) {
            currentCoroutineContext().ensureActive()
            val swaps = minOf(QueueLimits.PAGE_SIZE.toLong(), last - 1L).toInt()
            databaseAccess {
                database.withPreservedTransaction {
                    repeat(swaps) {
                        val other = shuffleIndex(last)
                        if (other !in 1L..last) throw QueueStoreException(QueueStoreIssue.INVALID_INPUT)
                        swapStagingSlots(generationId, last, other)
                        last -= 1L
                    }
                }
            }
            yield()
        }
    }

    private suspend fun swapStagingSlots(generationId: String, first: Long, second: Long) {
        if (first == second) return
        if (dao.moveStagingSlot(generationId, first, -1L) != 1 ||
            dao.moveStagingSlot(generationId, second, first) != 1 ||
            dao.moveStagingSlot(generationId, -1L, second) != 1
        ) recoveryRequired()
    }

    private suspend fun restoreOriginalOrder(generationId: String, count: Long) {
        if (count == 0L || databaseAccess { dao.nonOriginalOrders(generationId) } == 0L) return
        // Two bounded passes keep unique playback slots disjoint while original order is restored.
        for (temporary in listOf(true, false)) {
            var cursor = 0L
            while (cursor < count) {
                currentCoroutineContext().ensureActive()
                val limit = minOf(QueueLimits.PAGE_SIZE.toLong(), count - cursor).toInt()
                databaseAccess {
                    database.withPreservedTransaction {
                        val entries = dao.originalPage(generationId, cursor, limit)
                        if (entries.size != limit) recoveryRequired()
                        for (entry in entries) {
                            val order = if (temporary) -entry.originalOrder - 1L else entry.originalOrder
                            if (dao.setStagingOrder(generationId, entry.queueEntryId, order) != 1) recoveryRequired()
                        }
                    }
                }
                cursor += limit
                yield()
            }
        }
    }

    private suspend fun discardUnpublished(generationId: String) {
        withContext(NonCancellable) {
            try {
                databaseAccess { dao.abort(generationId) }
                databaseAccess { database.withPreservedTransaction { dao.deleteAbandonedBatch(generationId, QueueLimits.PAGE_SIZE) } }
            } catch (_: QueueStoreException) {
                // Unpublished rows are recoverable; cleanup must not replace the original failure.
            }
        }
        if (currentCoroutineContext().isActive) {
            try { cleanupAbandoned(generationId) }
            catch (_: CancellationException) { }
            catch (_: QueueStoreException) { }
        }
    }

    private data class SealedQueue(val current: QueueEntryEntity?, val window: QueueWindow?)

    private suspend fun seal(request: QueueReplacementRequest, generationId: String): SealedQueue = databaseAccess {
        database.withPreservedTransaction {
            val generation = dao.generation(generationId) ?: recoveryRequired()
            val stats = dao.orderStats(generationId)
            val total = request.view.totalCount
            if (generation.writtenCount != total || stats.entryCount != total) throw QueueStoreException(QueueStoreIssue.INCOMPLETE)
            // 唯一索引 + count/min/max 校验完整排列，API26 无需 SQLite 窗口函数。
            validateOrders(stats, total, QueueStoreIssue.INVALID_INPUT)
            val current = if (total == 0L) null else dao.entryAtOriginalOrder(generationId, request.startOriginalOrder) ?: recoveryRequired()
            if (dao.seal(generationId) != 1) recoveryRequired()
            val window = current?.let {
                val first = maxOf(0L, it.playbackOrder - 1L)
                val last = minOf(total - 1L, it.playbackOrder + 1L)
                val entries = dao.window(generationId, first, last).map { row -> row.model() }
                if (entries.size.toLong() != last - first + 1L) recoveryRequired()
                val currentIndex = entries.indexOfFirst { row -> row.queueEntryId == it.queueEntryId }
                if (currentIndex < 0) recoveryRequired()
                QueueWindow(generationId, request.expectedQueueRevision + 1L, total, entries, currentIndex)
            }
            SealedQueue(current, window)
        }
    }

    private suspend fun publish(
        request: QueueReplacementRequest,
        generationId: String,
        current: QueueEntryEntity?,
    ): QueueSnapshot = databaseAccess {
        database.withPreservedTransaction {
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
            val deleted = databaseAccess { database.withPreservedTransaction { dao.deleteInactiveBatch(QueueLimits.PAGE_SIZE) } }
            if (deleted > 0) yield()
        } while (deleted > 0)
    }

    private suspend fun cleanupAbandoned(generationId: String) {
        do {
            val deleted = databaseAccess { database.withPreservedTransaction { dao.deleteAbandonedBatch(generationId, QueueLimits.PAGE_SIZE) } }
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
    } catch (error: SQLiteException) {
        val failure = error.toPlatformSQLiteException()
        val issue = when (failure) {
            is SQLiteFullException -> QueueStoreIssue.STORAGE_FULL
            is SQLiteConstraintException -> if (inputRows) QueueStoreIssue.INVALID_INPUT else QueueStoreIssue.RECOVERY_REQUIRED
            else -> QueueStoreIssue.STORAGE_UNAVAILABLE
        }
        throw QueueStoreException(issue, failure)
    } catch (error: IllegalStateException) {
        // 缺迁移、关闭的连接或损坏结构都不能通过 destructive fallback 丢弃旧队列。
        throw QueueStoreException(QueueStoreIssue.RECOVERY_REQUIRED, error)
    } catch (error: IllegalArgumentException) {
        throw QueueStoreException(QueueStoreIssue.RECOVERY_REQUIRED, error)
    }
}
