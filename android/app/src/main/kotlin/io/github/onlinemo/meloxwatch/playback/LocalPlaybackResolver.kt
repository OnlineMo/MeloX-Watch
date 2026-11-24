package io.github.onlinemo.meloxwatch.playback

import io.github.onlinemo.meloxwatch.MeloXApplication
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationException
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationIssue
import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.cue.CueException
import io.github.onlinemo.meloxwatch.data.cue.CueIssue
import io.github.onlinemo.meloxwatch.model.QueueEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** At most the current and two adjacent entries are validated, on the shared physical I/O channel. */
class LocalPlaybackResolver(private val app: MeloXApplication) {
    suspend fun resolve(entry: QueueEntry): QueueEntry {
        if (entry.cueReference?.isBound == false) throw failure(QueuePlaybackIssue.SOURCE_CHANGED, entry)
        if (entry.trackKey.trackId.startsWith("cue-v1-") && entry.cueReference == null) throw failure(QueuePlaybackIssue.SOURCE_CHANGED, entry)
        val location = entry.location ?: throw failure(QueuePlaybackIssue.LOCATION_MISSING, entry)
        try {
            app.fileOperations.assertPlaybackReadable()
            if (entry.cueReference != null) return resolveCue(entry)
            // 预览观察不能由普通播放命令升级为持久内容证明。
            val descriptor = if (location.contentRevision == 0L) null else try {
                app.library.resolveLocalRead(entry.trackKey, location.locationId, location.contentRevision)
            } catch (error: LibraryException) {
                if (error.issue != LibraryIssue.SOURCE_CHANGED) throw error
                // 持久队列实例可以保留已忽略曲目；仍须校验确切位置、内容代次、来源及授权。
                app.fileOperations.retainedRead(entry.trackKey, location.locationId, location.contentRevision) ?: throw error
            }
            if (descriptor?.cueReference != null) throw failure(QueuePlaybackIssue.SOURCE_CHANGED, entry)
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
        catch (error: FileOperationException) {
            val issue = when (error.issue) {
                FileOperationIssue.BUSY -> QueuePlaybackIssue.BUSY
                FileOperationIssue.PERMISSION_DENIED, FileOperationIssue.PERMISSION_REVOKED -> QueuePlaybackIssue.PERMISSION_DENIED
                FileOperationIssue.FILE_MISSING -> QueuePlaybackIssue.FILE_MISSING
                FileOperationIssue.SOURCE_CHANGED, FileOperationIssue.INCOMPLETE_SCOPE -> QueuePlaybackIssue.SOURCE_CHANGED
                FileOperationIssue.STORAGE_FULL -> QueuePlaybackIssue.STORAGE_FULL
                FileOperationIssue.RECOVERY_REQUIRED, FileOperationIssue.PLAYBACK_RELEASE_UNCONFIRMED -> QueuePlaybackIssue.RECOVERY_REQUIRED
                else -> QueuePlaybackIssue.SOURCE_UNAVAILABLE
            }
            throw failure(issue, entry)
        } catch (error: LibraryException) {
            val issue = when (error.issue) {
                LibraryIssue.PERMISSION_REVOKED -> QueuePlaybackIssue.PERMISSION_DENIED
                LibraryIssue.FILE_MISSING -> QueuePlaybackIssue.FILE_MISSING
                LibraryIssue.SOURCE_CHANGED -> QueuePlaybackIssue.SOURCE_CHANGED
                LibraryIssue.STORAGE_FULL -> QueuePlaybackIssue.STORAGE_FULL
                LibraryIssue.RECOVERY_REQUIRED -> QueuePlaybackIssue.RECOVERY_REQUIRED
                else -> QueuePlaybackIssue.SOURCE_UNAVAILABLE
            }
            throw failure(issue, entry)
        } catch (error: CueException) {
            throw failure(if (error.issue == CueIssue.READ_TIMEOUT) QueuePlaybackIssue.SOURCE_READ_TIMEOUT
                else if (error.issue == CueIssue.UNVERIFIED_CONTENT) QueuePlaybackIssue.CONTENT_UNKNOWN else QueuePlaybackIssue.SOURCE_CHANGED, entry)
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

    private suspend fun resolveCue(entry: QueueEntry): QueueEntry {
        val expected = requireNotNull(entry.cueReference)
        val read = app.library.resolveCueRead(entry.trackKey, expected)
        repeat(3) { attempt ->
            currentCoroutineContext().ensureActive()
            try {
                app.localSources.validateCueRead(read.cue)
                val audio = app.localSources.validateCueRead(read.audio)
                if (app.library.resolveCueRead(entry.trackKey, expected).reference != read.reference) {
                    throw failure(QueuePlaybackIssue.SOURCE_CHANGED, entry)
                }
                return entry.copy(location = audio.location, cueReference = read.reference)
            } catch (failure: SourceReadException) {
                if (failure.issue != SourceReadIssue.BUSY || attempt == 2) throw failure
                app.localProviderIo.awaitIdle()
            }
        }
        throw failure(QueuePlaybackIssue.SOURCE_UNAVAILABLE, entry)
    }

    private fun failure(issue: QueuePlaybackIssue, entry: QueueEntry) = QueuePlaybackException(QueuePlaybackFailure(issue, entry.queueEntryId))
}
