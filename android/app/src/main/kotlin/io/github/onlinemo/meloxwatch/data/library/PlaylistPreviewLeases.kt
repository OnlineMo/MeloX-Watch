package io.github.onlinemo.meloxwatch.data.library

import io.github.onlinemo.meloxwatch.data.playlists.LocalPlaylistRepository
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistException
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistViewToken
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** One index lease and at most one temporary overlap lease, serialized by the controller's mutex. */
internal class PlaylistPreviewLeases(
    val previewId: String,
    private val library: LocalLibraryRepository,
    private val closeLibraryView: suspend (String) -> Unit,
    private val playlists: LocalPlaylistRepository? = null,
    private val closeTimeoutMs: Long = 5_000L,
) {
    init { require(closeTimeoutMs > 0L) }

    private var libraryView: LibraryViewToken? = null
    private var overlapView: PlaylistViewToken? = null
    val isReleased: Boolean get() = libraryView == null && overlapView == null

    suspend fun openLibrary(query: LibraryQuery, sort: LibrarySort): LibraryViewToken {
        if (!isReleased) throw PlaylistException(PlaylistIssue.BUSY)
        currentCoroutineContext().ensureActive()
        // Record the committed token before returning to an owner that may have been cancelled.
        val token = withContext(NonCancellable) {
            library.openView(query, sort).also { libraryView = it }
        }
        currentCoroutineContext().ensureActive()
        return token
    }

    suspend fun <T> useOverlap(playlistId: String, block: suspend (PlaylistViewToken) -> T): T {
        if (libraryView == null || overlapView != null) throw PlaylistException(PlaylistIssue.BUSY)
        val repository = checkNotNull(playlists)
        currentCoroutineContext().ensureActive()
        var failure: Throwable? = null
        try {
            val token = withContext(NonCancellable) {
                repository.openView(playlistId).also { overlapView = it }
            }
            currentCoroutineContext().ensureActive()
            return block(token)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try { closeOverlap() }
            catch (cleanup: Throwable) {
                val original = failure
                if (original == null) throw cleanup
                if (cleanup !== original) original.addSuppressed(cleanup)
            }
        }
    }

    suspend fun closeAll() {
        var failure: Throwable? = null
        try { closeOverlap() } catch (error: Throwable) { failure = error }
        try { closeLibrary() }
        catch (error: Throwable) {
            val original = failure
            if (original == null) failure = error else if (error !== original) original.addSuppressed(error)
        }
        failure?.let { throw it }
    }

    private suspend fun closeOverlap() {
        val previous = overlapView ?: return
        close({ checkNotNull(playlists).closeView(previous.viewId) }) { overlapView = null }
    }

    private suspend fun closeLibrary() {
        val previous = libraryView ?: return
        close({ closeLibraryView(previous.viewId) }) { libraryView = null }
    }

    private suspend fun close(action: suspend () -> Unit, onClosed: () -> Unit) = withContext(NonCancellable) {
        // Production index cleanup supplies its own deadline inside the protected metadata handoff.
        // Never forget ownership while a failed or timed-out close still needs an idempotent retry.
        try { withTimeout(closeTimeoutMs) { action() } }
        catch (_: TimeoutCancellationException) { throw PlaylistException(PlaylistIssue.BUSY) }
        // A timeout/failure retains the exact token for retry, even if metadata deletion already ran.
        onClosed()
    }
}
