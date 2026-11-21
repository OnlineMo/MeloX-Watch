package io.github.onlinemo.meloxwatch.data.lyrics.store

import android.database.sqlite.SQLiteException
import androidx.room.RoomDatabase
import io.github.onlinemo.meloxwatch.data.database.withPreservedTransaction
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The database transaction owns a bounded row read, real playback fence and SQL revision CAS. */
class RoomLyricsTrackStore(
    private val database: RoomDatabase,
    private val dao: LyricsDao,
) : LyricsTrackStore {
    private val occupied = AtomicBoolean(false)

    /** An unknown database result must abort recovery instead of becoming permission to delete. */
    suspend fun isDocumentUriReferenced(uri: String): Boolean {
        currentCoroutineContext().ensureActive()
        val referenced = dao.isDocumentUriReferenced(uri)
        currentCoroutineContext().ensureActive()
        return referenced
    }

    override suspend fun read(trackKey: TrackKey): LyricsTrackReadResult {
        if (trackKey.providerId != TrackKey.LOCAL_PROVIDER) return LyricsTrackReadResult.Unavailable(LyricsTrackIssue.NOT_LOCAL)
        if (!validLyricsTrackKey(trackKey)) return LyricsTrackReadResult.Unavailable(LyricsTrackIssue.INVALID_INPUT)
        return try {
            database.withPreservedTransaction {
                currentCoroutineContext().ensureActive()
                if (!dao.localTrackExists(trackKey.trackId)) lyricsTrackFail(LyricsTrackIssue.TRACK_MISSING)
                val current = dao.row(trackKey.trackId)?.decode(trackKey) ?: LyricsTrackPreferences(trackKey)
                currentCoroutineContext().ensureActive()
                LyricsTrackReadResult.Ready(current)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: LyricsTrackException) { LyricsTrackReadResult.Unavailable(error.issue) }
        catch (_: SQLiteException) { LyricsTrackReadResult.Unavailable(LyricsTrackIssue.IO) }
        catch (_: IllegalStateException) { LyricsTrackReadResult.Unavailable(LyricsTrackIssue.IO) }
        catch (_: OutOfMemoryError) { LyricsTrackReadResult.Unavailable(LyricsTrackIssue.LOW_MEMORY) }
    }

    override suspend fun commit(
        expectedRevision: Long,
        next: LyricsTrackPreferences,
        fence: LyricsTrackWriteFence,
        stillCurrent: suspend () -> Boolean,
    ): LyricsTrackCommitResult {
        if (expectedRevision < 0L || next.revision != expectedRevision || next.trackKey != fence.audio.trackKey) {
            return LyricsTrackCommitResult.Failed(null, LyricsTrackIssue.INVALID_INPUT)
        }
        if (!occupied.compareAndSet(false, true)) return LyricsTrackCommitResult.Failed(null, LyricsTrackIssue.BUSY)
        var known: LyricsTrackPreferences? = null
        try {
            currentCoroutineContext().ensureActive()
            if (!stillCurrent()) return LyricsTrackCommitResult.Stale(null)
            return database.withPreservedTransaction {
                currentCoroutineContext().ensureActive()
                if (!stillCurrent() || !matches(fence)) throw StaleTransaction(null)
                if (!dao.localTrackExists(next.trackKey.trackId)) lyricsTrackFail(LyricsTrackIssue.TRACK_MISSING)
                val before = dao.row(next.trackKey.trackId)
                val current = before?.decode(next.trackKey) ?: LyricsTrackPreferences(next.trackKey)
                known = current
                if (current.revision != expectedRevision) return@withPreservedTransaction LyricsTrackCommitResult.Conflict(current)
                if (!stillCurrent() || !matches(fence)) throw StaleTransaction(current)
                if (current == next) return@withPreservedTransaction LyricsTrackCommitResult.Unchanged(current)
                if (current.revision == Long.MAX_VALUE) lyricsTrackFail(LyricsTrackIssue.REVISION_EXHAUSTED)
                val replacement = next.copy(revision = current.revision + 1L)
                val row = replacement.entity()
                val changed = if (before == null) dao.insert(row) != -1L else dao.update(expectedRevision, row) == 1
                if (!changed) {
                    val actual = dao.row(next.trackKey.trackId)?.decode(next.trackKey)
                        ?: lyricsTrackFail(LyricsTrackIssue.CORRUPT_RECORD)
                    return@withPreservedTransaction LyricsTrackCommitResult.Conflict(actual)
                }
                // Leave the native writer by throwing so a late owner/source/instance change rolls back the DML.
                if (!stillCurrent() || !matches(fence)) throw StaleTransaction(current)
                currentCoroutineContext().ensureActive()
                LyricsTrackCommitResult.Saved(replacement)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (stale: StaleTransaction) { return LyricsTrackCommitResult.Stale(stale.current) }
        catch (error: LyricsTrackException) { return LyricsTrackCommitResult.Failed(known, error.issue) }
        catch (_: SQLiteException) { return LyricsTrackCommitResult.Failed(known, LyricsTrackIssue.IO) }
        catch (_: IllegalStateException) { return LyricsTrackCommitResult.Failed(known, LyricsTrackIssue.IO) }
        catch (_: OutOfMemoryError) { return LyricsTrackCommitResult.Failed(known, LyricsTrackIssue.LOW_MEMORY) }
        finally { occupied.set(false) }
    }

    private suspend fun matches(fence: LyricsTrackWriteFence): Boolean = dao.fenceMatches(
        trackId = fence.audio.trackKey.trackId,
        locationId = fence.audio.locationId,
        contentRevision = fence.audio.contentRevision,
        sourceId = fence.audio.sourceId,
        sourceRevision = fence.audio.sourceRevision,
        authEpoch = fence.audio.authorizationEpoch,
        generationId = fence.queueGenerationId,
        queueRevision = fence.queueRevision,
        entryId = fence.queueEntryId,
    )

    private class StaleTransaction(val current: LyricsTrackPreferences?) : Exception()
}
