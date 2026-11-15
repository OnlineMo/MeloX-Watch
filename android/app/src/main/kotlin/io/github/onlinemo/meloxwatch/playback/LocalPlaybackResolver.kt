package io.github.onlinemo.meloxwatch.playback

import io.github.onlinemo.meloxwatch.MeloXApplication
import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.model.QueueEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** At most the current and two adjacent entries are validated, on the shared physical I/O channel. */
class LocalPlaybackResolver(private val app: MeloXApplication) {
    suspend fun resolve(entry: QueueEntry): QueueEntry {
        val location = entry.location ?: throw failure(QueuePlaybackIssue.LOCATION_MISSING, entry)
        try {
            // Preview observations are not transported as durable content proof by this command.
            val descriptor = if (location.contentRevision == 0L) null else
                app.library.resolveLocalRead(entry.trackKey, location.locationId, location.contentRevision)
            repeat(3) { attempt ->
                currentCoroutineContext().ensureActive()
                try {
                    if (descriptor == null) {
                        app.localSources.verifyTransientRead(location)
                        return entry
                    }
                    val verified = app.localSources.validateRead(descriptor)
                    return entry.copy(location = verified.location)
                } catch (error: SourceReadException) {
                    if (error.issue != SourceReadIssue.BUSY || attempt == 2) throw error
                    app.localProviderIo.awaitIdle()
                }
            }
            throw failure(QueuePlaybackIssue.SOURCE_UNAVAILABLE, entry)
        } catch (error: CancellationException) { throw error }
        catch (error: LibraryException) {
            val issue = when (error.issue) {
                LibraryIssue.PERMISSION_REVOKED -> QueuePlaybackIssue.PERMISSION_DENIED
                LibraryIssue.FILE_MISSING -> QueuePlaybackIssue.FILE_MISSING
                LibraryIssue.SOURCE_CHANGED -> QueuePlaybackIssue.SOURCE_CHANGED
                LibraryIssue.STORAGE_FULL -> QueuePlaybackIssue.STORAGE_FULL
                LibraryIssue.RECOVERY_REQUIRED -> QueuePlaybackIssue.RECOVERY_REQUIRED
                else -> QueuePlaybackIssue.SOURCE_UNAVAILABLE
            }
            throw failure(issue, entry)
        } catch (error: SourceReadException) {
            val issue = when (error.issue) {
                SourceReadIssue.BUSY -> QueuePlaybackIssue.BUSY
                SourceReadIssue.PERMISSION_DENIED, SourceReadIssue.PERMISSION_REVOKED -> QueuePlaybackIssue.PERMISSION_DENIED
                SourceReadIssue.FILE_MISSING -> QueuePlaybackIssue.FILE_MISSING
                SourceReadIssue.SOURCE_CHANGED -> QueuePlaybackIssue.SOURCE_CHANGED
                SourceReadIssue.CONTENT_UNKNOWN -> QueuePlaybackIssue.CONTENT_UNKNOWN
                SourceReadIssue.UNSUPPORTED_FORMAT -> QueuePlaybackIssue.SOURCE_UNSUPPORTED
                SourceReadIssue.METADATA_UNREADABLE -> QueuePlaybackIssue.SOURCE_DAMAGED
                else -> QueuePlaybackIssue.SOURCE_UNAVAILABLE
            }
            throw failure(issue, entry)
        }
    }

    private fun failure(issue: QueuePlaybackIssue, entry: QueueEntry) = QueuePlaybackException(QueuePlaybackFailure(issue, entry.queueEntryId))
}
