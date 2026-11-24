package io.github.onlinemo.meloxwatch.data.library

import androidx.room.useWriterConnection
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.database.withPreservedTransaction
import io.github.onlinemo.meloxwatch.data.playlists.LocalPlaylistRepository
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCatalogFence
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistException
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistFolderBinding
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistFolderSynchronization
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistKind
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistLibraryRevision
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistLimits
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutation
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutationRequest
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistOperationResult
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistSnapshot
import io.github.onlinemo.meloxwatch.data.playlists.playlistDigest
import io.github.onlinemo.meloxwatch.data.playlists.requirePlaylistIdentifier
import io.github.onlinemo.meloxwatch.data.scan.ScanMode
import io.github.onlinemo.meloxwatch.data.scan.ScanPhase
import io.github.onlinemo.meloxwatch.data.scan.ScanState
import io.github.onlinemo.meloxwatch.data.scan.ScanToken
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/** Counters cover this finite sweep; durable receipts, rather than an in-memory history, deduplicate it. */
data class FolderPlaylistMaintenanceResult(
    val examined: Long = 0L,
    val synchronized: Long = 0L,
    val alreadySynchronized: Long = 0L,
    val deferred: Long = 0L,
    val ignored: Long = 0L,
    val issue: PlaylistIssue? = null,
    val cleanupPending: Boolean = false,
)

/**
 * One caller-owned coroutine, one index lease and no pending task queue. A completed scan triggers a
 * sweep of all currently eligible bindings, so coalesced scan events cannot strand another source.
 * Cancel and join that caller before close/on-background; resume starts one new finite sweep.
 */
class RoomFolderPlaylistMaintainer internal constructor(
    private val database: MeloXDatabase,
    private val playlists: LocalPlaylistRepository,
    private val library: LocalLibraryRepository,
    private val closeLibraryView: suspend (String) -> Unit,
    private val closeTimeoutMs: Long = 5_000L,
) {
    constructor(
        database: MeloXDatabase,
        playlists: LocalPlaylistRepository,
        library: RoomLocalLibraryRepository,
        closeTimeoutMs: Long = 5_000L,
    ) : this(database, playlists, library,
        { viewId -> library.closePlaylistView(viewId, closeTimeoutMs) }, closeTimeoutMs)

    private val owner = Mutex()
    private val fences = RoomPlaylistFences(database)
    private var leases: PlaylistPreviewLeases? = null

    init { require(closeTimeoutMs > 0L) }

    suspend fun afterScan(state: ScanState): FolderPlaylistMaintenanceResult {
        if (state.phase != ScanPhase.COMPLETED || state.token.mode != ScanMode.FULL ||
            !state.coverage.permitsAbsence || state.counters.failed != 0L || state.issue != null) {
            return FolderPlaylistMaintenanceResult()
        }
        return playlistControllerAction(owner) {
            try { catalogFence(state.token.sourceId, expectedToken = state.token) }
            catch (error: PlaylistException) {
                return@playlistControllerAction FolderPlaylistMaintenanceResult(issue = error.issue,
                    cleanupPending = leases != null)
            }
            sweep()
        }
    }

    /** Startup/foreground recovery reads the latest real full scan for each binding, once. */
    suspend fun resume(): FolderPlaylistMaintenanceResult = playlistControllerAction(owner) { sweep() }

    /** Failed release retains the exact token; a later explicit close/resume retries it first. */
    suspend fun close() = playlistControllerAction(owner) { release() }

    private data class Head(val playlistId: String, val sequence: Long, val folder: Boolean)
    private enum class Outcome { SYNCHRONIZED, ALREADY_SYNCHRONIZED, IGNORED }
    private data class ItemResult(val outcome: Outcome, val issue: PlaylistIssue? = null)

    private suspend fun sweep(): FolderPlaylistMaintenanceResult {
        try { release() }
        catch (error: PlaylistException) {
            return FolderPlaylistMaintenanceResult(issue = error.issue, cleanupPending = leases != null)
        }
        // Creation sequences never change. New heads are beyond this exclusive ceiling, so concurrent
        // creation cannot prolong this sweep or displace existing heads as a mutable count limit would.
        val ceiling = database.withPreservedTransaction {
            val meta = database.playlistDao().meta() ?: fail(PlaylistIssue.RECOVERY_REQUIRED)
            if (meta.revision < 0L || meta.nextPlaylistSequence <= 0L) fail(PlaylistIssue.RECOVERY_REQUIRED)
            meta.nextPlaylistSequence
        }
        var afterSequence = 0L
        var result = FolderPlaylistMaintenanceResult()
        while (afterSequence < ceiling - 1L) {
            currentCoroutineContext().ensureActive()
            val page = heads(afterSequence, ceiling)
            if (page.isEmpty()) break
            for (head in page) {
                currentCoroutineContext().ensureActive()
                if (!head.folder) {
                    afterSequence = head.sequence
                    continue
                }
                result = result.copy(examined = result.examined + 1L)
                try {
                    val item = playlistControllerAccess { synchronize(head.playlistId) }
                    result = when (item.outcome) {
                        Outcome.SYNCHRONIZED -> result.copy(synchronized = result.synchronized + 1L)
                        Outcome.ALREADY_SYNCHRONIZED -> result.copy(alreadySynchronized = result.alreadySynchronized + 1L)
                        Outcome.IGNORED -> result.copy(ignored = result.ignored + 1L)
                    }
                    if (item.issue != null) {
                        result = result.copy(issue = item.issue)
                        if (item.issue in STOP_ISSUES) return result.copy(cleanupPending = leases != null)
                    }
                } catch (error: PlaylistException) {
                    result = result.copy(deferred = result.deferred + 1L, issue = error.issue)
                    if (error.issue in STOP_ISSUES) return result.copy(cleanupPending = leases != null)
                }
                if (leases != null) return result.copy(cleanupPending = true)
                afterSequence = head.sequence
                yield()
            }
            yield()
        }
        return result
    }

    private suspend fun heads(afterSequence: Long, ceiling: Long): List<Head> = database.withPreservedTransaction {
        // Read at most 100 indexed heads, including ordinary/deleted ones. Filtering before LIMIT
        // could walk an arbitrarily large non-folder range in one transaction. Every actual folder
        // write still reads a fresh snapshot, without holding an expiring catalogue view.
        val sql = """
            SELECT h.playlistId, h.createdSequence, h.deleted, g.kind FROM playlist_heads h
            LEFT JOIN playlist_generations g ON g.generationId = h.activeGenerationId
            WHERE h.createdSequence > ? AND h.createdSequence < ?
            ORDER BY h.createdSequence LIMIT ?
        """.trimIndent()
        database.useWriterConnection { writer ->
            writer.usePrepared(sql) { statement ->
                statement.bindLong(1, afterSequence)
                statement.bindLong(2, ceiling)
                statement.bindLong(3, PlaylistLimits.PAGE_SIZE.toLong())
                val page = ArrayList<Head>(PlaylistLimits.PAGE_SIZE)
                var previous = afterSequence
                while (statement.step()) {
                    val id = statement.getText(0)
                    val sequence = statement.getLong(1)
                    val deleted = statement.getInt(2)
                    val kind = if (statement.isNull(3)) null else statement.getText(3)
                    requirePlaylistIdentifier(id)
                    if (sequence <= previous || sequence >= ceiling || page.size == PlaylistLimits.PAGE_SIZE ||
                        deleted !in 0..1 || deleted == 0 && kind == null) {
                        fail(PlaylistIssue.RECOVERY_REQUIRED)
                    }
                    page += Head(id, sequence, deleted == 0 && kind == PlaylistKind.FOLDER.name)
                    previous = sequence
                }
                page
            }
        }
    }

    private suspend fun synchronize(playlistId: String): ItemResult {
        val base = playlists.snapshot(playlistId)
        if (base == null || base.kind != PlaylistKind.FOLDER) return ItemResult(Outcome.IGNORED)
        val binding = base.folderBinding ?: fail(PlaylistIssue.RECOVERY_REQUIRED)
        val initial = catalogFence(binding.sourceId, binding = binding)
        val operationId = operationId(base.playlistId, binding, initial.scanId)
        if (acceptedReceipt(operationId, base)) return ItemResult(Outcome.ALREADY_SYNCHRONIZED)

        val acquired = PlaylistPreviewLeases(operationId, library, closeTimeoutMs = closeTimeoutMs,
            closeLibraryView = closeLibraryView)
        leases = acquired
        var outcome: ItemResult? = null
        var failure: Throwable? = null
        try {
            val view = acquired.openLibrary(LibraryQuery(sourceId = binding.sourceId, folderNodeId = binding.nodeId,
                folderSubtree = binding.subtree, readableOnly = true), LibrarySort(LibrarySortField.FILENAME_NATURAL))
            val folder = library.folder(view, binding.nodeId) ?: fail(PlaylistIssue.SOURCE_UNAVAILABLE)
            if (folder.sourceId != binding.sourceId) fail(PlaylistIssue.SOURCE_CHANGED)
            val fence = catalogFence(binding.sourceId, view = view, expectedScanId = initial.scanId, binding = binding)
            val input = LibraryPlaylistInput(library, view)
            val synchronization = PlaylistFolderSynchronization(binding, fence, input.input)
            val request = PlaylistMutationRequest(operationId, base.playlistId, base.revision,
                PlaylistMutation.SynchronizeFolder(synchronization),
                expectedLibraryRevision = PlaylistLibraryRevision(view.revision, view.rulesRevision),
                expectedSourceFence = fence)
            currentCoroutineContext().ensureActive()
            try {
                playlistControllerAccess { playlists.apply(request, input) }
                outcome = ItemResult(Outcome.SYNCHRONIZED)
            } catch (error: PlaylistException) {
                // A real receipt can already exist when tail reclamation reports failure. Do not rebuild
                // with a new ephemeral input or turn that accepted write into an uncommitted retry.
                if (!acceptedReceipt(operationId, base, minimumRevision = base.revision)) throw error
                outcome = ItemResult(Outcome.SYNCHRONIZED, error.issue)
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            try { release() }
            catch (cleanup: Throwable) {
                val original = failure
                if (original != null) {
                    if (cleanup !== original) original.addSuppressed(cleanup)
                } else if (cleanup is PlaylistException && outcome != null) {
                    outcome = checkNotNull(outcome).copy(issue = cleanup.issue)
                } else failure = cleanup
            }
        }
        failure?.let { throw it }
        return checkNotNull(outcome)
    }

    private suspend fun catalogFence(
        sourceId: String,
        view: LibraryViewToken? = null,
        expectedToken: ScanToken? = null,
        expectedScanId: String? = null,
        binding: PlaylistFolderBinding? = null,
    ): PlaylistCatalogFence = database.withPreservedTransaction {
        val dao = database.libraryDao()
        val meta = dao.meta() ?: fail(PlaylistIssue.SOURCE_UNAVAILABLE)
        if (view != null && (view.revision != meta.revision || view.rulesRevision != meta.rulesRevision)) {
            fail(PlaylistIssue.SOURCE_CHANGED)
        }
        val source = dao.source(sourceId) ?: fail(PlaylistIssue.SOURCE_UNAVAILABLE)
        val scanId = source.latestScanId ?: fail(PlaylistIssue.INCOMPLETE)
        if ((expectedScanId != null && expectedScanId != scanId) || expectedToken != null &&
            (expectedToken.sourceId != sourceId || expectedToken.scanId != scanId ||
                expectedToken.sourceRevision != source.revision || expectedToken.authEpoch != source.authEpoch ||
                expectedToken.rulesRevision != meta.rulesRevision)) fail(PlaylistIssue.SOURCE_CHANGED)
        val fence = PlaylistCatalogFence(meta.revision, meta.rulesRevision, sourceId, source.revision, source.authEpoch, scanId)
        fences.verify(fence)
        if (binding != null) {
            // Persistent nodes survive missing directories. Only a directory actually enumerated by
            // this exact successful scan can authorize an empty replacement of its old members.
            val node = dao.nodeById(binding.nodeId) ?: fail(PlaylistIssue.SOURCE_UNAVAILABLE)
            val current = dao.currentNode(binding.nodeId) ?: fail(PlaylistIssue.SOURCE_UNAVAILABLE)
            if (binding.sourceId != sourceId || node.sourceId != sourceId || !current.directory ||
                dao.seen(scanId, node.objectId)?.nodeId != binding.nodeId ||
                dao.frontier(scanId, node.objectId)?.state != "DONE") fail(PlaylistIssue.SOURCE_UNAVAILABLE)
        }
        fence
    }

    private suspend fun acceptedReceipt(
        operationId: String,
        base: PlaylistSnapshot,
        minimumRevision: Long = 0L,
    ): Boolean = database.withPreservedTransaction {
        val receipt = database.playlistDao().receipt(operationId) ?: return@withPreservedTransaction false
        if (receipt.playlistId != base.playlistId || receipt.result != PlaylistOperationResult.SYNCHRONIZED.name ||
            receipt.appliedRevision < minimumRevision || receipt.appliedRevision >
                (database.playlistDao().head(base.playlistId)?.revision ?: fail(PlaylistIssue.RECOVERY_REQUIRED)) ||
            listOf(receipt.addedCount, receipt.removedCount, receipt.duplicateCount, receipt.sameNameCount).any { it < 0L }) {
            fail(PlaylistIssue.RECOVERY_REQUIRED)
        }
        true
    }

    private fun operationId(playlistId: String, binding: PlaylistFolderBinding, scanId: String): String =
        "folder-scan-v1-" + playlistDigest(listOf(playlistId, binding.sourceId, binding.nodeId,
            binding.subtree.toString(), scanId))

    private suspend fun release() = withContext(NonCancellable) {
        val previous = leases ?: return@withContext
        // Enter protected cleanup before any dispatcher handoff: a cancelled caller must still run
        // the closer, whose own timeout keeps queued metadata and reclamation waits cancellable.
        try { playlistControllerAccess { previous.closeAll() } }
        finally { if (previous.isReleased) leases = null }
    }

    private fun fail(issue: PlaylistIssue): Nothing = throw PlaylistException(issue)

    private companion object {
        val STOP_ISSUES = setOf(PlaylistIssue.BUSY, PlaylistIssue.STORAGE_FULL, PlaylistIssue.STORAGE_UNAVAILABLE,
            PlaylistIssue.RECOVERY_REQUIRED, PlaylistIssue.INVALID_INPUT, PlaylistIssue.REVISION_EXHAUSTED)
    }
}
