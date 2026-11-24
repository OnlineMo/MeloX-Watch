package io.github.onlinemo.meloxwatch.data.fileoperations

import io.github.onlinemo.meloxwatch.data.library.LibraryViewToken
import io.github.onlinemo.meloxwatch.data.library.RoomLocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 持有真实精确视图租约及持久选择；进程重建不恢复执行草稿。 */
internal class FileSelectionDraftOwner(
    private val library: RoomLocalLibraryRepository,
    private val journal: FileOperationJournal,
    private val io: LocalProviderIo,
) {
    private val memory = Any()
    private val views = HashMap<String, LibraryViewToken>()
    private val opening = Mutex()
    private var orphanAfter: String? = null

    suspend fun open(fromViewId: String): FileSelectionDraft {
        if (!opening.tryLock()) throw FileOperationException(FileOperationIssue.BUSY)
        try {
            return openLocked(fromViewId)
        } finally {
            opening.unlock()
        }
    }

    private suspend fun openLocked(fromViewId: String): FileSelectionDraft {
        // 首次明确打开选择时才回收无运行时所有者的旧草稿；启动仍只读日志。
        val activeIds = synchronized(memory) { views.keys.toSet() }
        orphanAfter = io.read { journal.retireOrphanDraftPage(activeIds, orphanAfter) }
        io.read { journal.reclaimRetiredDraftPage() }
        val view = library.openQueueView(fromViewId)
        val draft = FileSelectionDraft(UUID.randomUUID().toString(), FileSelectionToken(view.viewId, view.revision, view.totalCount))
        try {
            withContext(NonCancellable) {
                io.read { journal.createSelectionDraft(draft) }
                synchronized(memory) { views[draft.draftId] = view }
            }
            currentCoroutineContext().ensureActive()
            return draft
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                try { io.awaitIdle()
                    io.read { journal.retireSelectionDraft(draft.draftId) } }
                finally { library.closePlaylistView(view.viewId, 2_500L)
                    synchronized(memory) { views.remove(draft.draftId) } }
            }
            throw failure
        }
    }

    suspend fun toggle(draft: FileSelectionDraft, key: TrackKey): FileSelectionDraft {
        val view = view(draft)
        val selected = library.locate(view, key)?.track ?: throw FileOperationException(FileOperationIssue.SOURCE_CHANGED)
        return io.read { journal.toggleSelectionDraft(draft.draftId, draft.revision,
            FileSelectedTrack(selected.trackKey, selected.location?.locationId, selected.location?.contentRevision)) }
    }

    suspend fun selectAll(draft: FileSelectionDraft, selected: Boolean): FileSelectionDraft {
        view(draft)
        return io.read { journal.selectAllDraft(draft.draftId, draft.revision, selected) }
    }

    suspend fun membership(draft: FileSelectionDraft, keys: List<TrackKey>): Set<TrackKey> {
        view(draft)
        require(keys.size <= FileOperationLimits.PAGE_SIZE)
        val exceptions = io.read { journal.draftExceptions(draft.draftId, draft.revision, keys) }
        return if (draft.allSelected) keys.filterNot { it in exceptions }.toSet() else exceptions
    }

    suspend fun producer(draft: FileSelectionDraft): FileSelectionPageSource {
        val ownedView = view(draft)
        val current = io.read { journal.selectionDraft(draft.draftId) }
        if (current != draft) throw FileOperationException(FileOperationIssue.SOURCE_CHANGED)
        val full = if (draft.allSelected) LibraryFileSelectionSource.borrow(library, ownedView) else null
        return object : FileSelectionPageSource {
            private var nextOffset = 0L
            private var subsetAfter: Long? = null
            private var fullOffset = 0L
            private var fullComplete = false
            private var buffered: Iterator<FileSelectedTrack> = emptyList<FileSelectedTrack>().iterator()
            private var closed = false

            override suspend fun read(offset: Long, limit: Int): FileSelectionPage {
                if (closed || offset != nextOffset || limit !in 1..FileOperationLimits.PAGE_SIZE) throw FileOperationException(FileOperationIssue.INVALID_INPUT)
                if (io.read { journal.selectionDraft(draft.draftId) } != draft) throw FileOperationException(FileOperationIssue.SOURCE_CHANGED)
                val expected = minOf(limit.toLong(), draft.selectedCount - nextOffset).toInt()
                if (expected <= 0) throw FileOperationException(FileOperationIssue.INCOMPLETE_SCOPE)
                val selected = ArrayList<FileSelectedTrack>(expected)
                if (full == null) {
                    val page = io.read { journal.selectedDraftPage(draft.draftId, draft.revision, subsetAfter, expected) }
                    if (page.size != expected) throw FileOperationException(FileOperationIssue.INCOMPLETE_SCOPE)
                    var previous = subsetAfter ?: -1L
                    for (row in page) {
                        if (row.ordinal <= previous) throw FileOperationException(FileOperationIssue.INCOMPLETE_SCOPE)
                        previous = row.ordinal
                        selected += row.selected
                    }
                    subsetAfter = previous
                } else {
                    suspend fun refill(): Boolean {
                        while (!buffered.hasNext()) {
                            if (fullComplete) return false
                            val page = full.read(fullOffset, FileOperationLimits.PAGE_SIZE)
                            if (page.tracks.isEmpty()) throw FileOperationException(FileOperationIssue.INCOMPLETE_SCOPE)
                            fullOffset = Math.addExact(fullOffset, page.tracks.size.toLong())
                            fullComplete = page.complete
                            val excluded = io.read { journal.draftExceptions(draft.draftId, draft.revision, page.tracks.map { it.trackKey }) }
                            buffered = page.tracks.filterNot { it.trackKey in excluded }.iterator()
                        }
                        return true
                    }
                    while (selected.size < expected) {
                        currentCoroutineContext().ensureActive()
                        if (!refill()) throw FileOperationException(FileOperationIssue.INCOMPLETE_SCOPE)
                        selected += buffered.next()
                    }
                    if (nextOffset + expected == draft.selectedCount && refill()) throw FileOperationException(FileOperationIssue.INCOMPLETE_SCOPE)
                }
                nextOffset = Math.addExact(nextOffset, selected.size.toLong())
                if (full == null && nextOffset == draft.selectedCount &&
                    io.read { journal.selectedDraftPage(draft.draftId, draft.revision, subsetAfter, 1).isNotEmpty() }) {
                    throw FileOperationException(FileOperationIssue.INCOMPLETE_SCOPE)
                }
                return FileSelectionPage(draft.selection, offset, selected, nextOffset == draft.selectedCount)
            }

            override suspend fun close() = withContext(NonCancellable) {
                if (!closed) { full?.close()
                    closed = true }
            }
        }
    }

    suspend fun close(draft: FileSelectionDraft) = withContext(NonCancellable) {
        val view = synchronized(memory) { views[draft.draftId] } ?: return@withContext
        io.awaitIdle()
        io.read { journal.retireSelectionDraft(draft.draftId) }
        library.closePlaylistView(view.viewId, 2_500L)
        synchronized(memory) { views.remove(draft.draftId) }
        // 每次 native 调用只删除一页，超时留下不可执行的 retired 行供下次明确打开回收。
        withTimeoutOrNull(2_500L) {
            while (io.read { journal.reclaimRetiredDraftPage() }) currentCoroutineContext().ensureActive()
        }
    }

    private fun view(draft: FileSelectionDraft): LibraryViewToken = (synchronized(memory) { views[draft.draftId] }
        ?: throw FileOperationException(FileOperationIssue.SOURCE_CHANGED)).also {
        if (it.viewId != draft.view.viewId || it.revision != draft.view.revision || it.totalCount != draft.view.totalCount) {
            throw FileOperationException(FileOperationIssue.SOURCE_CHANGED)
        }
    }
}
