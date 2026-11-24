package io.github.onlinemo.meloxwatch.data.fileoperations

import io.github.onlinemo.meloxwatch.data.library.LibraryCursor
import io.github.onlinemo.meloxwatch.data.library.LibraryViewToken
import io.github.onlinemo.meloxwatch.data.library.RoomLocalLibraryRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** 独立精确视图租约供给完整选择；不能把可见 UI 页当作全部。 */
class LibraryFileSelectionSource private constructor(
    private val library: RoomLocalLibraryRepository,
    private val view: LibraryViewToken,
    private val ownsView: Boolean,
) : FileSelectionPageSource {
    val selection = FileSelectionToken(view.viewId, view.revision, view.totalCount)
    private var nextOffset = 0L
    private var cursor: LibraryCursor? = null
    private var closed = false

    override suspend fun read(offset: Long, limit: Int): FileSelectionPage {
        if (closed || offset != nextOffset || limit !in 1..FileOperationLimits.PAGE_SIZE) throw FileOperationException(FileOperationIssue.INVALID_INPUT)
        val page = library.page(view, cursor, limit)
        if (page.view != view) throw FileOperationException(FileOperationIssue.SOURCE_CHANGED)
        val tracks = page.tracks.map { FileSelectedTrack(it.trackKey, it.location?.locationId, it.location?.contentRevision) }
        nextOffset = Math.addExact(nextOffset, tracks.size.toLong())
        cursor = page.nextCursor
        return FileSelectionPage(selection, offset, tracks, nextOffset == selection.totalCount && cursor == null)
    }

    override suspend fun close() = withContext(NonCancellable) {
        if (!closed) {
            if (ownsView) library.closePlaylistView(view.viewId, 2_500L)
            closed = true
        }
    }

    companion object {
        suspend fun open(library: RoomLocalLibraryRepository, fromViewId: String): LibraryFileSelectionSource =
            LibraryFileSelectionSource(library, library.openQueueView(fromViewId), ownsView = true)

        /** 草稿已有唯一 QUEUE 视图租约；producer 借用该租约，不再向 QUEUE 视图复制。 */
        internal fun borrow(library: RoomLocalLibraryRepository, view: LibraryViewToken): LibraryFileSelectionSource =
            LibraryFileSelectionSource(library, view, ownsView = false)
    }
}
