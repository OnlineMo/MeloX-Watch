package io.github.onlinemo.meloxwatch.data.fileoperations

import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteStatement
import io.github.onlinemo.meloxwatch.data.database.withPreservedTransaction
import io.github.onlinemo.meloxwatch.data.library.LocalReadDescriptor
import io.github.onlinemo.meloxwatch.data.library.RoomLocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.queue.QueueEntryEntity
import io.github.onlinemo.meloxwatch.data.queue.RoomQueueStore
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 在真实队列所有者的 preparation 租约及原生围栏内修复全部持久实例。 */
class RoomFileOperationQueueReferences(
    private val library: RoomLocalLibraryRepository,
    private val fileLibrary: RoomFileOperationLibrary,
    private val queues: RoomQueueStore,
    private val validateRead: suspend (LocalReadDescriptor) -> LocalReadDescriptor,
    private val journal: FileOperationJournal,
    private val io: LocalProviderIo,
) : FileOperationQueueReferences {
    private data class QueueRead(val location: LocalLocationReference?, val displayName: String?)
    private data class QueueRepair(val original: QueueEntryEntity, val read: QueueRead)
    private val database get() = library.database
    private val dao get() = database.queueDao()

    override suspend fun reconcile(item: FileOperationItem, command: FileOperationCommand, reservation: FilePlaybackReservation) {
        reservation.requireClosedResources()
        val old = item.target.descriptor ?: return
        if (item.phase !in setOf(FileItemPhase.EXTERNAL_APPLIED, FileItemPhase.COPY_VALIDATED)) throw FileOperationException(FileOperationIssue.RECOVERY_REQUIRED)
        queues.withFileReferenceRepair(reservation) {
            val role = when {
                item.artifacts.restoredOriginal -> FileQueueRepairRole.RESTORE_ORIGINAL
                command.kind == FileOperationKind.MOVE && item.artifacts.originalDeleted -> FileQueueRepairRole.DELETE_MOVE_SOURCE
                else -> FileQueueRepairRole.PUBLISH
            }
            val previous = io.read { journal.queueRepair(item.operationId, item.target.unitKey, role) }
            if (previous?.completed == true) return@withFileReferenceRepair
            val head = database.withPreservedTransaction { dao.head() }
            val binding = previous ?: io.read {
                val base = head?.queueRevision ?: 0L
                journal.bindQueueRepair(FileQueueRepairBinding(item.operationId, item.target.unitKey, role,
                    head?.activeGenerationId, base, if (head?.activeGenerationId == null) base else Math.addExact(base, 1L)))
            }
            if (head?.activeGenerationId != binding.generationId) throw FileOperationException(FileOperationIssue.CONFLICT)
            val generationId = binding.generationId
            if (generationId == null) {
                io.read { journal.completeQueueRepair(binding) }
                return@withFileReferenceRepair
            }
            val actualHead = head ?: throw FileOperationException(FileOperationIssue.RECOVERY_REQUIRED)
            if (actualHead.queueRevision == binding.finalRevision) {
                // 主事务已提交但私有确认因取消或空间不足未完成；
                // 按固定提交 revision 确认，不能再次递增。
                io.read { journal.completeQueueRepair(binding) }
                return@withFileReferenceRepair
            }
            if (actualHead.queueRevision != binding.baseRevision) throw FileOperationException(FileOperationIssue.CONFLICT)
            val generation = database.withPreservedTransaction { dao.generation(generationId) }
                ?: throw FileOperationException(FileOperationIssue.RECOVERY_REQUIRED)
            val preferred = item.artifacts.published?.let { fileLibrary.publishedLocationId(it) }
            var nextOrder = 0L
            while (nextOrder < generation.totalCount) {
                currentCoroutineContext().ensureActive()
                reservation.requireClosedResources()
                val limit = minOf(FileOperationLimits.PAGE_SIZE.toLong(), generation.totalCount - nextOrder).toInt()
                val page = database.withPreservedTransaction { dao.originalPage(generationId, nextOrder, limit) }
                if (page.size != limit || page.withIndex().any { (index, row) -> row.originalOrder != nextOrder + index }) {
                    throw FileOperationException(FileOperationIssue.RECOVERY_REQUIRED)
                }
                val checked = HashMap<TrackKey, QueueRead>()
                val updates = ArrayList<QueueRepair>(page.size)
                for (row in page) {
                    if (row.providerId == TrackKey.LOCAL_PROVIDER && row.locationId == old.location.locationId &&
                        (row.contentRevision == old.location.contentRevision || item.artifacts.restoredOriginal &&
                            old.location.contentRevision < Long.MAX_VALUE && row.contentRevision == old.location.contentRevision + 1L)) {
                        val key = TrackKey(row.providerId, row.trackId)
                        val reference = checked[key] ?: run {
                            val candidate = fileLibrary.retainedRead(key, preferred)
                            val actual = candidate?.let {
                                try { validateRead(it) }
                                catch (failure: SourceReadException) {
                                    if (failure.issue != SourceReadIssue.CONTENT_UNKNOWN) throw failure
                                    // 明确文件验证后协调真实发布 URI/revision；普通播放仍执行弱来源预算，
                                    // 不能自动全读大文件，可另行明确确认应用副本。
                                    it
                                }
                            }
                            val name = if (command.kind in setOf(FileOperationKind.WRITE_TAGS, FileOperationKind.RENAME, FileOperationKind.MOVE) &&
                                item.artifacts.published != null) fileLibrary.queueDisplayName(key) else null
                            val route = actual?.location ?: fileLibrary.unavailableQueueLocation(key, old.location.locationId)
                            QueueRead(route, name).also { checked[key] = it }
                        }
                        updates += QueueRepair(row, reference)
                    }
                }
                if (updates.isNotEmpty()) database.withPreservedTransaction {
                    reservation.requireClosedResources()
                    val current = dao.head() ?: throw FileOperationException(FileOperationIssue.CONFLICT)
                    if (current.activeGenerationId != generationId || current.queueRevision != binding.baseRevision) {
                        throw FileOperationException(FileOperationIssue.CONFLICT)
                    }
                    for (update in updates) {
                        val row = update.original
                        val reference = update.read.location
                        val name = update.read.displayName
                        execute("""UPDATE queue_entries SET locationId=?,locationUri=?,grantUri=?,grantFlags=?,access=?,contentRevision=?,displayName=COALESCE(?,displayName)
                            WHERE generationId=? AND queueEntryId=? AND providerId=? AND trackId=? AND locationId=? AND contentRevision=?""",
                            reference?.locationId, reference?.uri, reference?.grantUri, reference?.grantFlags, reference?.access?.name, reference?.contentRevision, name,
                            generationId, row.queueEntryId, row.providerId, row.trackId, row.locationId, row.contentRevision)
                    }
                }
                nextOrder = Math.addExact(page.last().originalOrder, 1L)
            }
            // 保留选择、顺序与 generation；revision 使旧 UI 命令失效，
            // 不另建队列或改变 checkpoint/循环值。
            database.withPreservedTransaction {
                reservation.requireClosedResources()
                val current = dao.head() ?: throw FileOperationException(FileOperationIssue.CONFLICT)
                if (current.activeGenerationId != generationId || current.queueRevision != binding.baseRevision) throw FileOperationException(FileOperationIssue.CONFLICT)
                execute("UPDATE queue_generations SET committedQueueRevision=? WHERE generationId=? AND phase=2", binding.finalRevision, generationId)
                execute("UPDATE queue_head SET queueRevision=? WHERE id=1 AND activeGenerationId=? AND queueRevision=?", binding.finalRevision, generationId, binding.baseRevision)
            }
            io.read { journal.completeQueueRepair(binding) }
        }
    }

    private suspend fun execute(sql: String, vararg values: Any?) = database.useWriterConnection { writer ->
        writer.usePrepared(sql) { statement ->
            values.forEachIndexed { index, value -> bind(statement, index + 1, value) }
            statement.step()
        }
    }
    private fun bind(statement: SQLiteStatement, index: Int, value: Any?) {
        when (value) {
            null -> statement.bindNull(index)
            is String -> statement.bindText(index, value)
            is Long -> statement.bindLong(index, value)
            is Int -> statement.bindLong(index, value.toLong())
            else -> throw FileOperationException(FileOperationIssue.INVALID_INPUT)
        }
    }
}
