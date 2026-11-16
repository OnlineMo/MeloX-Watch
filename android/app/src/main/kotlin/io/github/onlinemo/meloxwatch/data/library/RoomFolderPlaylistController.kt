package io.github.onlinemo.meloxwatch.data.library

import android.os.SystemClock
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.database.withPreservedTransaction
import io.github.onlinemo.meloxwatch.data.playlists.LocalPlaylistRepository
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCatalogFence
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCommit
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistException
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistFolderSynchronization
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistFolderBinding
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistKind
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistLibraryRevision
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutation
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutationRequest
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistSnapshot
import io.github.onlinemo.meloxwatch.ui.playlists.FolderPlaylistController
import io.github.onlinemo.meloxwatch.ui.playlists.FolderPlaylistCreateRequest
import io.github.onlinemo.meloxwatch.ui.playlists.FolderPlaylistPreview
import io.github.onlinemo.meloxwatch.ui.playlists.FolderPlaylistUpdateRequest
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistFolderChoice
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

/** One retained index view and one explicit attempt; provider enumeration never occurs here. */
class RoomFolderPlaylistController(
    private val database: MeloXDatabase,
    private val playlists: LocalPlaylistRepository,
    private val library: RoomLocalLibraryRepository,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
    private val closeTimeoutMs: Long = 5_000L,
) : FolderPlaylistController {
    private val owner = Mutex()
    private val fences = RoomPlaylistFences(database)
    private var retained: PreviewState? = null
    private var leases: PlaylistPreviewLeases? = null
    private var attempt: Attempt? = null

    init { require(closeTimeoutMs > 0L) }

    private data class PreviewState(
        val preview: FolderPlaylistPreview,
        val base: PlaylistSnapshot?,
        val view: LibraryViewToken,
        val source: LibraryPlaylistInput,
        val expiresElapsedMs: Long,
    )
    private enum class Action { CREATE, SYNCHRONIZE, REBIND }
    private data class Intent(
        val action: Action, val operationId: String, val playlistId: String,
        val expectedRevision: Long, val previewId: String, val name: String? = null,
    )
    private data class Attempt(val intent: Intent, val mutation: PlaylistMutationRequest)

    override suspend fun preview(choice: PlaylistFolderChoice, base: PlaylistSnapshot?): FolderPlaylistPreview =
        playlistControllerAction(owner) {
            release()
            attempt = null
            if (base != null && (base.kind != PlaylistKind.FOLDER || playlists.snapshot(base.playlistId) != base)) {
                fail(PlaylistIssue.CONFLICT)
            }
            val acquired = PlaylistPreviewLeases(UUID.randomUUID().toString(), library,
                closeLibraryView = { viewId -> library.closePlaylistView(viewId, closeTimeoutMs) },
                playlists = playlists, closeTimeoutMs = closeTimeoutMs)
            leases = acquired
            var handedOff = false
            var failure: Throwable? = null
            try {
                val view = acquired.openLibrary(
                    query = LibraryQuery(sourceId = choice.binding.sourceId, folderNodeId = choice.binding.nodeId,
                        folderSubtree = choice.binding.subtree, readableOnly = true),
                    sort = LibrarySort(LibrarySortField.FILENAME_NATURAL),
                )
                val folder = library.folder(view, choice.binding.nodeId) ?: fail(PlaylistIssue.SOURCE_UNAVAILABLE)
                if (folder.sourceId != choice.binding.sourceId) fail(PlaylistIssue.INVALID_INPUT)
                val fence = catalogFence(view, choice.binding)
                val overlap = if (base == null) 0L else acquired.useOverlap(base.playlistId) { original ->
                    if (original.snapshot != base) fail(PlaylistIssue.CONFLICT)
                    library.playlistOverlap(view, original)
                }
                if (overlap !in 0L..view.totalCount || base != null && overlap > base.memberCount) {
                    fail(PlaylistIssue.RECOVERY_REQUIRED)
                }
                val preview = FolderPlaylistPreview(acquired.previewId, choice.copy(label = folder.name),
                    fence, view.totalCount, view.totalCount - overlap, (base?.memberCount ?: 0L) - overlap)
                library.validatePlaylistView(view)
                val expiry = database.withPreservedTransaction {
                    database.libraryDao().view(view.viewId)?.expiresElapsedMs ?: fail(PlaylistIssue.VIEW_EXPIRED)
                }
                currentCoroutineContext().ensureActive()
                retained = PreviewState(preview, base, view, LibraryPlaylistInput(library, view), expiry)
                handedOff = true
                preview
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                if (!handedOff) closeUnaccepted(failure)
            }
        }

    override suspend fun create(request: FolderPlaylistCreateRequest): PlaylistCommit = playlistControllerAction(owner) {
        commit(Intent(Action.CREATE, request.operationId, request.playlistId, 0L, request.previewId, request.name))
    }

    override suspend fun synchronize(request: FolderPlaylistUpdateRequest): PlaylistCommit = playlistControllerAction(owner) {
        commit(Intent(Action.SYNCHRONIZE, request.operationId, request.playlistId, request.expectedRevision, request.previewId))
    }

    override suspend fun rebind(request: FolderPlaylistUpdateRequest): PlaylistCommit = playlistControllerAction(owner) {
        commit(Intent(Action.REBIND, request.operationId, request.playlistId, request.expectedRevision, request.previewId))
    }

    override suspend fun closePreview(previewId: String) = playlistControllerAction(owner) {
        if (leases?.previewId == previewId) release()
    }

    private suspend fun commit(intent: Intent): PlaylistCommit {
        val prior = attempt?.takeIf { it.intent.operationId == intent.operationId }
        if (prior != null && prior.intent != intent) fail(PlaylistIssue.INVALID_INPUT)
        val receiptExists = database.withPreservedTransaction { database.playlistDao().receipt(intent.operationId) != null }
        if (receiptExists) {
            if (prior == null) fail(PlaylistIssue.INVALID_INPUT)
            val result = playlists.apply(checkNotNull(prior).mutation)
            if (leases?.previewId == intent.previewId) releaseAfterCommit()
            return result
        }
        val state = retained?.takeIf { it.preview.previewId == intent.previewId } ?: fail(PlaylistIssue.VIEW_EXPIRED)
        if (state.expiresElapsedMs <= elapsedMs()) fail(PlaylistIssue.VIEW_EXPIRED)
        library.validatePlaylistView(state.view)
        if (intent.action == Action.CREATE && state.base != null) fail(PlaylistIssue.INVALID_INPUT)
        if (intent.action != Action.CREATE) {
            val base = state.base ?: fail(PlaylistIssue.CONFLICT)
            if (base.playlistId != intent.playlistId || base.revision != intent.expectedRevision) fail(PlaylistIssue.CONFLICT)
        }
        val synchronization = PlaylistFolderSynchronization(state.preview.choice.binding, state.preview.fence, state.source.input)
        val mutation = prior?.mutation ?: PlaylistMutationRequest(intent.operationId, intent.playlistId, intent.expectedRevision,
            when (intent.action) {
                Action.CREATE -> PlaylistMutation.CreateFolder(checkNotNull(intent.name), synchronization)
                Action.SYNCHRONIZE -> PlaylistMutation.SynchronizeFolder(synchronization)
                // This entry is invoked only by the separate binding-change confirmation page.
                Action.REBIND -> PlaylistMutation.RebindFolder(synchronization, confirmed = true)
            }, expectedLibraryRevision = PlaylistLibraryRevision(state.view.revision, state.view.rulesRevision),
            expectedSourceFence = state.preview.fence)
        attempt = Attempt(intent, mutation)
        try {
            currentCoroutineContext().ensureActive()
            val result = playlists.apply(mutation, state.source)
            releaseAfterCommit()
            return result
        } catch (error: CancellationException) {
            try { release() } catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
            throw error
        }
    }

    private suspend fun catalogFence(view: LibraryViewToken, binding: PlaylistFolderBinding): PlaylistCatalogFence =
        database.withPreservedTransaction {
            val dao = database.libraryDao()
            val source = dao.source(binding.sourceId) ?: fail(PlaylistIssue.SOURCE_UNAVAILABLE)
            val scanId = source.latestScanId ?: fail(PlaylistIssue.INCOMPLETE)
            val fence = PlaylistCatalogFence(view.revision, view.rulesRevision, binding.sourceId,
                source.revision, source.authEpoch, scanId)
            fences.verify(fence)
            // Historical nodes survive absence for recovery; they cannot prove a still-bound directory.
            val node = dao.nodeById(binding.nodeId) ?: fail(PlaylistIssue.SOURCE_UNAVAILABLE)
            val current = dao.currentNode(binding.nodeId) ?: fail(PlaylistIssue.SOURCE_UNAVAILABLE)
            if (node.sourceId != binding.sourceId || !current.directory ||
                dao.seen(scanId, node.objectId)?.nodeId != binding.nodeId) fail(PlaylistIssue.SOURCE_UNAVAILABLE)
            if (dao.frontier(scanId, node.objectId)?.state != "DONE") fail(PlaylistIssue.INCOMPLETE)
            fence
        }

    private suspend fun release() {
        retained = null
        val previous = leases ?: return
        try { previous.closeAll() }
        finally { if (previous.isReleased) leases = null }
    }

    private suspend fun releaseAfterCommit() {
        try { release() } catch (error: CancellationException) { throw error } catch (_: Exception) {
            // A lease cleanup failure cannot turn the durable successful receipt into a failed write.
        }
    }

    private suspend fun closeUnaccepted(original: Throwable?) {
        try { release() }
        catch (cleanup: Throwable) {
            if (original == null) throw cleanup
            if (cleanup !== original) original.addSuppressed(cleanup)
        }
    }

    private fun fail(issue: PlaylistIssue): Nothing = throw PlaylistException(issue)
}
