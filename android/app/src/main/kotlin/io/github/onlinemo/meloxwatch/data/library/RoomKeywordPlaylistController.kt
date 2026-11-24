package io.github.onlinemo.meloxwatch.data.library

import android.os.SystemClock
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.database.withPreservedTransaction
import io.github.onlinemo.meloxwatch.data.playlists.LocalPlaylistRepository
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCommit
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistException
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistLibraryRevision
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistLimits
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutation
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutationRequest
import io.github.onlinemo.meloxwatch.ui.playlists.KeywordPlaylistController
import io.github.onlinemo.meloxwatch.ui.playlists.KeywordPlaylistCreateRequest
import io.github.onlinemo.meloxwatch.ui.playlists.KeywordPlaylistPreview
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistPreviewRevision
import java.text.Normalizer
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

/** Literal, case-sensitive NFC path snapshots over one authorized index lease. */
class RoomKeywordPlaylistController(
    private val database: MeloXDatabase,
    private val playlists: LocalPlaylistRepository,
    private val library: RoomLocalLibraryRepository,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
    private val closeTimeoutMs: Long = 5_000L,
) : KeywordPlaylistController {
    private val owner = Mutex()
    private var retained: PreviewState? = null
    private var leases: PlaylistPreviewLeases? = null
    private var attempt: Attempt? = null

    init { require(closeTimeoutMs > 0L) }
    private data class PreviewState(
        val preview: KeywordPlaylistPreview, val view: LibraryViewToken,
        val source: LibraryPlaylistInput, val expiresElapsedMs: Long,
    )
    private data class Attempt(val intent: KeywordPlaylistCreateRequest, val mutation: PlaylistMutationRequest)

    override suspend fun preview(keyword: String): KeywordPlaylistPreview = playlistControllerAction(owner) {
        require(keyword.isNotBlank() && keyword.length <= PlaylistLimits.MAX_FIELD_CHARACTERS)
        val normalized = Normalizer.normalize(keyword, Normalizer.Form.NFC)
        require(normalized.length <= PlaylistLimits.MAX_FIELD_CHARACTERS)
        release()
        attempt = null
        val acquired = PlaylistPreviewLeases(UUID.randomUUID().toString(), library,
            closeLibraryView = { viewId -> library.closePlaylistView(viewId, closeTimeoutMs) },
            closeTimeoutMs = closeTimeoutMs)
        leases = acquired
        var handedOff = false
        var failure: Throwable? = null
        try {
            val view = acquired.openLibrary(LibraryQuery(relativePathKeyword = normalized, readableOnly = true),
                LibrarySort(LibrarySortField.FILENAME_NATURAL))
            val availableScope = library.playlistAvailableScopeCount(view)
            val broad = view.totalCount >= 1000L || availableScope >= 100L &&
                view.totalCount >= availableScope - availableScope / 5L
            val preview = KeywordPlaylistPreview(acquired.previewId, normalized,
                PlaylistPreviewRevision(view.revision, view.rulesRevision), view.totalCount, broad)
            library.validatePlaylistView(view)
            val expiry = database.withPreservedTransaction {
                database.libraryDao().view(view.viewId)?.expiresElapsedMs ?: fail(PlaylistIssue.VIEW_EXPIRED)
            }
            currentCoroutineContext().ensureActive()
            retained = PreviewState(preview, view, LibraryPlaylistInput(library, view), expiry)
            handedOff = true
            preview
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (!handedOff) closeUnaccepted(failure)
        }
    }

    override suspend fun create(request: KeywordPlaylistCreateRequest): PlaylistCommit = playlistControllerAction(owner) {
        val prior = attempt?.takeIf { it.intent.operationId == request.operationId }
        if (prior != null && prior.intent != request) fail(PlaylistIssue.INVALID_INPUT)
        val receiptExists = database.withPreservedTransaction { database.playlistDao().receipt(request.operationId) != null }
        if (receiptExists) {
            if (prior == null) fail(PlaylistIssue.INVALID_INPUT)
            val result = playlists.apply(checkNotNull(prior).mutation)
            if (leases?.previewId == request.previewId) releaseAfterCommit()
            return@playlistControllerAction result
        }
        val state = retained?.takeIf { it.preview.previewId == request.previewId } ?: fail(PlaylistIssue.VIEW_EXPIRED)
        if (state.expiresElapsedMs <= elapsedMs()) fail(PlaylistIssue.VIEW_EXPIRED)
        require(!state.preview.requiresBroadConfirmation || request.broadMatchConfirmed)
        require(state.preview.memberCount != 0L || request.emptyMatchConfirmed)
        library.validatePlaylistView(state.view)
        val mutation = prior?.mutation ?: PlaylistMutationRequest(request.operationId, request.playlistId, 0L,
            PlaylistMutation.Create(request.name, state.source.input),
            expectedLibraryRevision = PlaylistLibraryRevision(state.view.revision, state.view.rulesRevision))
        attempt = Attempt(request, mutation)
        try {
            currentCoroutineContext().ensureActive()
            val result = playlists.apply(mutation, state.source)
            releaseAfterCommit()
            result
        } catch (error: CancellationException) {
            try { release() } catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
            throw error
        }
    }

    override suspend fun closePreview(previewId: String) = playlistControllerAction(owner) {
        if (leases?.previewId == previewId) release()
    }

    private suspend fun release() {
        retained = null
        val previous = leases ?: return
        try { previous.closeAll() }
        finally { if (previous.isReleased) leases = null }
    }

    private suspend fun releaseAfterCommit() {
        try { release() } catch (error: CancellationException) { throw error } catch (_: Exception) {
            // The committed relationship remains successful even when expiring its read lease fails.
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
