package io.github.onlinemo.meloxwatch.data.lyrics.store

import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.lyrics.active.LyricsDocumentCopies

/** All preference changes share recovery's reference/delete gate, including clear and delay edits. */
class ReferenceGatedLyricsTrackStore(
    private val delegate: LyricsTrackStore,
    private val copies: LyricsDocumentCopies,
) : LyricsTrackStore by delegate {
    override suspend fun commit(
        expectedRevision: Long,
        next: LyricsTrackPreferences,
        fence: LyricsTrackWriteFence,
        stillCurrent: suspend () -> Boolean,
    ): LyricsTrackCommitResult {
        var entered = false
        return try {
            copies.withReferenceWrite {
                entered = true
                delegate.commit(expectedRevision, next, fence, stillCurrent)
            }
        } catch (error: SourceReadException) {
            // Only acquisition BUSY is a store result; delegate failures and cancellation propagate.
            if (entered || error.issue != SourceReadIssue.BUSY) throw error
            LyricsTrackCommitResult.Failed(null, LyricsTrackIssue.BUSY)
        }
    }
}
