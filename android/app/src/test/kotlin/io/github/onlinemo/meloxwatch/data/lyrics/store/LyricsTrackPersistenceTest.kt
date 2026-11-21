package io.github.onlinemo.meloxwatch.data.lyrics.store

import io.github.onlinemo.meloxwatch.model.TrackKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
internal class LyricsTrackPersistenceTest : LyricsRoomPlaybackFixture() {
    @Test(timeout = 30000L)
    fun twoAssociationsAndSignedDelaySurviveTheRealDatabaseReopen() = runBlocking {
        val next = LyricsTrackPreferences(trackKey, primary = LyricsRoomRows.association(),
            translation = LyricsRoomRows.association(translation = true), userDelayMs = -2500)
        assertEquals(LyricsTrackCommitResult.Saved(next.copy(revision = 1L)), store.commit(0L, next, fence) { true })
        reopen()
        assertEquals(next.copy(revision = 1L), ready())
        assertEquals(1L, scalar("SELECT COUNT(*) FROM lyrics_track_preferences"))
        assertIntegrity()
    }

    @Test(timeout = 30000L)
    fun clearingEitherAssociationLeavesTheOtherAndTheOffsetIndependent() = runBlocking {
        val initial = LyricsTrackPreferences(trackKey, primary = LyricsRoomRows.association(),
            translation = LyricsRoomRows.association(translation = true), userDelayMs = 1500)
        val saved = (store.commit(0L, initial, fence) { true } as LyricsTrackCommitResult.Saved).preferences
        val removePrimary = saved.copy(primary = null)
        assertEquals(LyricsTrackCommitResult.Saved(removePrimary.copy(revision = 2L)),
            store.commit(1L, removePrimary, fence) { true })
        reopen()
        val translationOnly = ready()
        assertNull(translationOnly.primary)
        assertEquals(initial.translation, translationOnly.translation)
        assertEquals(1500, translationOnly.userDelayMs)
        assertEquals(LyricsTrackCommitResult.Saved(LyricsTrackPreferences(trackKey, revision = 3L, userDelayMs = 1500)),
            store.commit(2L, translationOnly.copy(translation = null), fence) { true })
        reopen()
        assertEquals(LyricsTrackPreferences(trackKey, revision = 3L, userDelayMs = 1500), ready())
    }

    @Test(timeout = 30000L)
    fun replayDoesNotIncrementAndAnOldEditorCannotOverwriteANewerRevision() = runBlocking {
        val next = LyricsTrackPreferences(trackKey, primary = LyricsRoomRows.association(), userDelayMs = 100)
        val saved = (store.commit(0L, next, fence) { true } as LyricsTrackCommitResult.Saved).preferences
        assertEquals(LyricsTrackCommitResult.Unchanged(saved), store.commit(1L, saved, fence) { true })
        assertEquals(LyricsTrackCommitResult.Conflict(saved), store.commit(0L, next.copy(userDelayMs = 200), fence) { true })
        reopen()
        assertEquals(saved, ready())
        assertEquals(1L, scalar("SELECT revision FROM lyrics_track_preferences"))
    }

    @Test(timeout = 30000L)
    fun offsetFollowsTheStableTrackAndAnOldCurrentInstanceCannotSaveToAnotherTrack() = runBlocking {
        val first = LyricsTrackPreferences(trackKey, userDelayMs = 10_000)
        val firstSaved = (store.commit(0L, first, fence) { true } as LyricsTrackCommitResult.Saved).preferences
        execute("UPDATE queue_head SET activeGenerationId = '${LyricsRoomRows.OTHER_GENERATION_ID}', " +
            "currentEntryId = '${LyricsRoomRows.OTHER_ENTRY_ID}', queueRevision = 6 WHERE id = 1")
        val otherFence = LyricsRoomRows.fence(other = true)
        val otherKey = otherFence.audio.trackKey
        assertEquals(LyricsTrackPreferences(otherKey), ready(otherKey))
        val second = LyricsTrackPreferences(otherKey, userDelayMs = -10_000)
        assertEquals(LyricsTrackCommitResult.Saved(second.copy(revision = 1L)), store.commit(0L, second, otherFence) { true })
        assertEquals(LyricsTrackCommitResult.Stale(null), store.commit(1L, firstSaved.copy(userDelayMs = 0), fence) { true })
        assertEquals(LyricsTrackCommitResult.Failed(null, LyricsTrackIssue.INVALID_INPUT),
            store.commit(1L, firstSaved, otherFence) { true })
        reopen()
        assertEquals(firstSaved, ready())
        assertEquals(second.copy(revision = 1L), ready(otherKey))
    }

    @Test(timeout = 30000L)
    fun positionCheckpointAdvancementKeepsTheAudioInstanceFenceValid() = runBlocking {
        execute("UPDATE queue_head SET checkpointRevision = checkpointRevision + 1, positionMs = 98765 WHERE id = 1")
        val next = LyricsTrackPreferences(trackKey, userDelayMs = 200)
        assertEquals(LyricsTrackCommitResult.Saved(next.copy(revision = 1L)), store.commit(0L, next, fence) { true })
        assertEquals(98765L, scalar("SELECT positionMs FROM queue_head WHERE id = 1"))
        assertEquals(3L, scalar("SELECT checkpointRevision FROM queue_head WHERE id = 1"))
        assertEquals(LyricsRoomRows.QUEUE_REVISION, scalar("SELECT queueRevision FROM queue_head WHERE id = 1"))
    }

    @Test(timeout = 30000L)
    fun exhaustedRevisionRejectsChangesAndKeepsTheOriginalRow() = runBlocking {
        val initial = LyricsTrackPreferences(trackKey, userDelayMs = 100)
        store.commit(0L, initial, fence) { true }
        execute("UPDATE lyrics_track_preferences SET revision = ?", Long.MAX_VALUE)
        val current = initial.copy(revision = Long.MAX_VALUE)
        assertEquals(LyricsTrackCommitResult.Unchanged(current), store.commit(Long.MAX_VALUE, current, fence) { true })
        assertEquals(LyricsTrackCommitResult.Failed(current, LyricsTrackIssue.REVISION_EXHAUSTED),
            store.commit(Long.MAX_VALUE, current.copy(userDelayMs = 200), fence) { true })
        reopen()
        assertEquals(current, ready())
        assertIntegrity()
    }

    @Test(timeout = 30000L)
    fun nonlocalOrMissingTrackDoesNotAcquireALyricsRecord() = runBlocking {
        assertEquals(LyricsTrackReadResult.Unavailable(LyricsTrackIssue.NOT_LOCAL), store.read(TrackKey("netease", "song")))
        val missing = TrackKey(TrackKey.LOCAL_PROVIDER, "missing-track")
        assertEquals(LyricsTrackReadResult.Unavailable(LyricsTrackIssue.TRACK_MISSING), store.read(missing))
        assertEquals(LyricsTrackReadResult.Unavailable(LyricsTrackIssue.INVALID_INPUT),
            store.read(TrackKey(TrackKey.LOCAL_PROVIDER, "x".repeat(129))))
        val missingFence = fence.copy(audio = fence.audio.copy(trackKey = missing))
        assertEquals(LyricsTrackCommitResult.Stale(null), store.commit(0L, LyricsTrackPreferences(missing, userDelayMs = 100), missingFence) { true })
        assertEquals(0L, scalar("SELECT COUNT(*) FROM lyrics_track_preferences"))
    }
}
