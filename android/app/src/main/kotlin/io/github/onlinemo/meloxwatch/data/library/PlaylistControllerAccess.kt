package io.github.onlinemo.meloxwatch.data.library

import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistException
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex

internal suspend fun <T> playlistControllerAction(owner: Mutex, block: suspend () -> T): T = playlistControllerAccess {
    if (!owner.tryLock()) throw PlaylistException(PlaylistIssue.BUSY)
    try { block() } finally { owner.unlock() }
}

internal suspend fun <T> playlistControllerAccess(block: suspend () -> T): T = withContext(Dispatchers.IO) {
    try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: PlaylistException) {
        throw error
    } catch (error: LibraryException) {
        val issue = when (error.issue) {
            LibraryIssue.VIEW_EXPIRED -> PlaylistIssue.VIEW_EXPIRED
            LibraryIssue.CONFLICT, LibraryIssue.SOURCE_CHANGED -> PlaylistIssue.SOURCE_CHANGED
            LibraryIssue.INVALID_INPUT -> PlaylistIssue.INVALID_INPUT
            LibraryIssue.STORAGE_FULL -> PlaylistIssue.STORAGE_FULL
            LibraryIssue.STORAGE_UNAVAILABLE -> PlaylistIssue.STORAGE_UNAVAILABLE
            LibraryIssue.RECOVERY_REQUIRED -> PlaylistIssue.RECOVERY_REQUIRED
            LibraryIssue.BUSY -> PlaylistIssue.BUSY
            else -> PlaylistIssue.SOURCE_UNAVAILABLE
        }
        throw PlaylistException(issue, error)
    } catch (error: SQLiteFullException) {
        throw PlaylistException(PlaylistIssue.STORAGE_FULL, error)
    } catch (error: SQLiteException) {
        throw PlaylistException(PlaylistIssue.STORAGE_UNAVAILABLE, error)
    } catch (error: IllegalArgumentException) {
        throw PlaylistException(PlaylistIssue.INVALID_INPUT, error)
    }
}
