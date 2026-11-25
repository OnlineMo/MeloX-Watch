package io.github.onlinemo.meloxwatch.data.library

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteFullException
import android.os.SystemClock
import androidx.sqlite.SQLiteException
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.database.toPlatformSQLiteException
import io.github.onlinemo.meloxwatch.data.database.withPreservedTransaction
import io.github.onlinemo.meloxwatch.data.playlists.LocalPlaylistRepository
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCatalogFence
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCommit
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistException
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistKind
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistLibraryRevision
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistLimits
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutation
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutationRequest
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistReference
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistViewOwner
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistPreviewRevision
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistReassociationPreview
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistRecoveryAction
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistRecoveryController
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistRecoveryTarget
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 单个有界预览所有者；只查询既有索引，最终写入仍由同Room的歌单仓库核围栏/CAS。 */
class RoomPlaylistRecoveryController(
    private val database: MeloXDatabase,
    private val playlists: LocalPlaylistRepository,
    private val library: LocalLibraryRepository,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
    private val closeLibraryView: suspend (String) -> Unit,
) : PlaylistRecoveryController {
    private val actions = Mutex()
    private val fences = RoomPlaylistFences(database)
    private var active: PreviewRecord? = null
    private var lastAccepted: FrozenAttempt? = null
    private var pendingClose: OwnedLibraryView? = null

    override suspend fun preview(target: PlaylistRecoveryTarget, replacement: TrackKey): PlaylistReassociationPreview =
        exclusive {
            requireTarget(target, replacement)
            retryPendingClose()
            active?.let { previous ->
                if (elapsedMs() < previous.expiresElapsedMs) fail(PlaylistIssue.BUSY)
                closeRecord(previous)
            }
            val page = playlists.page(target.view, target.member.ordinal, 1)
            val next = target.member.ordinal + 1L
            if (page.view != target.view || page.members.singleOrNull() != target.member ||
                page.nextOrdinal != next.takeIf { it < target.view.snapshot.memberCount }) {
                fail(PlaylistIssue.SOURCE_CHANGED)
            }
            currentCoroutineContext().ensureActive()
            // 短数据库acquire先交付所有权；取消不能丢掉已经保存却尚未返回的lease ID。
            val owned = withContext(NonCancellable) {
                OwnedLibraryView(UUID.randomUUID().toString(),
                    library.openView(LibraryQuery(includeHidden = true)))
            }
            val view = owned.view
            var retained = false
            var failure: Throwable? = null
            try {
                val located = library.locate(view, replacement) ?: fail(PlaylistIssue.NOT_FOUND)
                currentCoroutineContext().ensureActive()
                if (located.track.trackKey != replacement) fail(PlaylistIssue.SOURCE_CHANGED)
                if (located.track.availability != LibraryAvailability.AVAILABLE && located.track.availability != LibraryAvailability.UNKNOWN) {
                    fail(PlaylistIssue.SOURCE_UNAVAILABLE)
                }
                if (located.track.availability == LibraryAvailability.UNKNOWN && located.track.cueReference != null) {
                    val location = located.track.location ?: fail(PlaylistIssue.SOURCE_UNAVAILABLE)
                    val read = playlistControllerAccess {
                        library.resolveLocalRead(replacement, location.locationId, location.contentRevision)
                    }
                    if (read.location != location || read.cueReference != located.track.cueReference) fail(PlaylistIssue.SOURCE_CHANGED)
                }
                val selected = indexedReplacement(view, located.track)
                currentCoroutineContext().ensureActive()
                val now = elapsedMs()
                require(now >= 0L)
                if (now >= selected.expiresElapsedMs) fail(PlaylistIssue.VIEW_EXPIRED)
                val result = PlaylistReassociationPreview(owned.previewId, target, replacement, selected.title,
                    PlaylistPreviewRevision(view.revision, view.rulesRevision))
                active = PreviewRecord(result, owned, selected.reference, selected.fence,
                    selected.expiresElapsedMs)
                retained = true
                result
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                if (!retained) {
                    try { closeOwned(owned) }
                    catch (cleanup: Throwable) {
                        val original = failure
                        if (original == null) throw cleanup
                        if (original !== cleanup) original.addSuppressed(cleanup)
                    }
                }
            }
        }

    override suspend fun confirm(operationId: String, previewId: String, confirmed: Boolean): PlaylistCommit = exclusive {
        requireIdentifier(operationId)
        requireIdentifier(previewId)
        require(confirmed)
        val record = active?.takeIf { it.preview.previewId == previewId }
        if (record == null) {
            val previous = lastAccepted?.takeIf { it.previewId == previewId } ?: fail(PlaylistIssue.VIEW_EXPIRED)
            if (previous.request.operationId != operationId) fail(PlaylistIssue.INVALID_INPUT)
            if (!hasReceipt(operationId)) fail(PlaylistIssue.VIEW_EXPIRED)
            // 不读已释放的lease/来源；仓库核完整指纹后回原结果和当前snapshot。
            return@exclusive playlists.apply(previous.request)
        }
        val existing = record.attempt
        if (existing != null && existing.operationId != operationId) fail(PlaylistIssue.INVALID_INPUT)
        val request = existing ?: PlaylistMutationRequest(
            operationId, record.preview.target.view.snapshot.playlistId,
            record.preview.target.view.snapshot.revision,
            PlaylistMutation.ReplaceReference(record.preview.target.member.memberId, record.reference, confirmed),
            PlaylistLibraryRevision(record.preview.revision.catalogRevision, record.preview.revision.rulesRevision),
            record.fence,
        )
        if (!hasReceipt(operationId) && elapsedMs() >= record.expiresElapsedMs) fail(PlaylistIssue.VIEW_EXPIRED)
        record.attempt = request
        // 取消可能发生在NonCancellable提交之后；没有返回值时仍保原request供显式重试。
        val commit = playlists.apply(request)
        lastAccepted = FrozenAttempt(previewId, request)
        active = null
        try { closeOwned(record.owned) }
        catch (_: Exception) {
            // 已提交成功不被lease清理错误覆盖；后续新预览须先回收这唯一未释放lease。
        }
        commit
    }

    override suspend fun closePreview(previewId: String) = exclusive {
        requireIdentifier(previewId)
        val record = active?.takeIf { it.preview.previewId == previewId }
        if (record != null) closeRecord(record)
        else pendingClose?.takeIf { it.previewId == previewId }?.let { closeOwned(it) }
        Unit
    }

    private suspend fun indexedReplacement(view: LibraryViewToken, selected: LibraryTrack): IndexedReplacement =
        database.withPreservedTransaction {
            val dao = database.libraryDao()
            val lease = dao.view(view.viewId) ?: fail(PlaylistIssue.VIEW_EXPIRED)
            val now = elapsedMs()
            require(now >= 0L)
            if (lease.expiresElapsedMs <= now) fail(PlaylistIssue.VIEW_EXPIRED)
            if (lease.revision != view.revision || lease.totalCount != view.totalCount ||
                lease.owner != LibraryViewOwner.UI.name || view.owner != LibraryViewOwner.UI ||
                lease.sortRuleVersion != view.sortRuleVersion || view.sortRuleVersion != LibraryLimits.SORT_RULE_VERSION) {
                fail(PlaylistIssue.SOURCE_CHANGED)
            }
            val sourceId = selected.sourceId ?: fail(PlaylistIssue.SOURCE_UNAVAILABLE)
            val source = dao.source(sourceId) ?: fail(PlaylistIssue.SOURCE_UNAVAILABLE)
            val scanId = source.latestScanId ?: fail(PlaylistIssue.INCOMPLETE)
            val fence = PlaylistCatalogFence(view.revision, view.rulesRevision, source.sourceId,
                source.revision, source.authEpoch, scanId)
            fences.verify(fence)
            val track = dao.currentTrack(selected.trackKey.trackId) ?: fail(PlaylistIssue.NOT_FOUND)
            if (!track.member || track.providerId != TrackKey.LOCAL_PROVIDER) fail(PlaylistIssue.NOT_FOUND)
            val location = selected.location ?: fail(PlaylistIssue.SOURCE_UNAVAILABLE)
            val aliasId = selected.aliasId ?: fail(PlaylistIssue.SOURCE_UNAVAILABLE)
            val alias = dao.currentAlias(aliasId) ?: fail(PlaylistIssue.SOURCE_UNAVAILABLE)
            if (alias.sourceId != sourceId || alias.locationId != location.locationId ||
                alias.nodeId != selected.nodeId || alias.contentRevision != location.contentRevision || alias.readIssue != null) {
                fail(PlaylistIssue.SOURCE_CHANGED)
            }
            val related = dao.scalar(LibrarySql.raw("""
                SELECT EXISTS(SELECT 1 FROM local_track_locations
                    WHERE trackId = ? AND locationId = ? AND validTo IS NULL LIMIT 1)
            """.trimIndent(), arrayOf(selected.trackKey.trackId, location.locationId)))
            if (related != 1L) fail(PlaylistIssue.SOURCE_CHANGED)
            IndexedReplacement(PlaylistReference(selected.trackKey, titleHint = track.displayTitle,
                artistHint = track.artist, albumHint = track.album, durationMs = track.durationMs), track.displayTitle,
                fence, lease.expiresElapsedMs)
        }

    private fun requireTarget(target: PlaylistRecoveryTarget, replacement: TrackKey) {
        require(target.action == PlaylistRecoveryAction.REASSOCIATE)
        require(target.view.owner == PlaylistViewOwner.UI)
        if (target.view.snapshot.kind != PlaylistKind.ORDINARY) fail(PlaylistIssue.AUTOMATIC_MEMBERS)
        requireIdentifier(target.member.memberId)
        require(target.member.ordinal >= 0L && target.member.ordinal < target.view.snapshot.memberCount)
        require(replacement.providerId == TrackKey.LOCAL_PROVIDER)
        requireIdentifier(replacement.trackId)
    }

    private suspend fun hasReceipt(operationId: String): Boolean = database.withPreservedTransaction {
        database.playlistDao().receipt(operationId) != null
    }

    private suspend fun closeRecord(record: PreviewRecord) {
        val attempt = record.attempt
        if (attempt != null && hasReceipt(attempt.operationId)) {
            lastAccepted = FrozenAttempt(record.preview.previewId, attempt)
        }
        if (active === record) active = null
        closeOwned(record.owned)
    }

    private suspend fun retryPendingClose() {
        val owned = pendingClose ?: return
        try { closeOwned(owned) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { throw PlaylistException(PlaylistIssue.BUSY, error) }
    }

    private suspend fun closeOwned(owned: OwnedLibraryView) {
        pendingClose = owned
        withContext(NonCancellable) {
            try { withTimeout(CLOSE_TIMEOUT_MS) { closeLibraryView(owned.view.viewId) } }
            catch (error: TimeoutCancellationException) { throw PlaylistException(PlaylistIssue.BUSY, error) }
            if (pendingClose === owned) pendingClose = null
        }
    }

    private suspend fun <T> exclusive(operation: suspend () -> T): T {
        if (!actions.tryLock()) fail(PlaylistIssue.BUSY)
        try {
            return withContext(Dispatchers.IO) {
                try { operation() }
                catch (error: CancellationException) { throw error }
                catch (error: PlaylistException) { throw error }
                catch (error: SQLiteException) {
                    val failure = error.toPlatformSQLiteException()
                    val issue = when (failure) {
                        is SQLiteFullException -> PlaylistIssue.STORAGE_FULL
                        is SQLiteConstraintException -> PlaylistIssue.RECOVERY_REQUIRED
                        else -> PlaylistIssue.STORAGE_UNAVAILABLE
                    }
                    throw PlaylistException(issue, failure)
                }
                catch (error: IllegalArgumentException) { throw PlaylistException(PlaylistIssue.INVALID_INPUT, error) }
            }
        } finally { actions.unlock() }
    }

    private fun requireIdentifier(value: String) {
        require(value.isNotBlank() && value.length <= PlaylistLimits.MAX_IDENTIFIER_CHARACTERS)
    }

    private fun fail(issue: PlaylistIssue): Nothing = throw PlaylistException(issue)

    private data class IndexedReplacement(
        val reference: PlaylistReference,
        val title: String,
        val fence: PlaylistCatalogFence,
        val expiresElapsedMs: Long,
    )
    private data class OwnedLibraryView(val previewId: String, val view: LibraryViewToken)
    private data class FrozenAttempt(val previewId: String, val request: PlaylistMutationRequest)
    private class PreviewRecord(
        val preview: PlaylistReassociationPreview,
        val owned: OwnedLibraryView,
        val reference: PlaylistReference,
        val fence: PlaylistCatalogFence,
        val expiresElapsedMs: Long,
    ) {
        var attempt: PlaylistMutationRequest? = null
    }

    companion object { private const val CLOSE_TIMEOUT_MS = 2_500L }
}
