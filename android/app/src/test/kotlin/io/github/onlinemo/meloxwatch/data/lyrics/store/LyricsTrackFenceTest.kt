package io.github.onlinemo.meloxwatch.data.lyrics.store

import io.github.onlinemo.meloxwatch.data.database.hasCurrentWriterTransaction
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
internal class LyricsTrackFenceTest : LyricsRoomPlaybackFixture() {
    @Test(timeout = 30000L)
    fun sourceAuthorizationRevisionStateAndLocationContentAreReadFromTheRealDatabase() = runBlocking {
        assertFaults(listOf(
            Triple("source-revision", "UPDATE local_sources SET revision = 4", "UPDATE local_sources SET revision = 3"),
            Triple("authorization-epoch", "UPDATE local_sources SET authEpoch = 3", "UPDATE local_sources SET authEpoch = 2"),
            Triple("revoked-source", "UPDATE local_sources SET state = 'NEEDS_AUTHORIZATION'", "UPDATE local_sources SET state = 'ACTIVE'"),
            Triple("content-revision", "UPDATE local_locations SET contentRevision = 8", "UPDATE local_locations SET contentRevision = 7"),
            Triple("invalid-source-type", "UPDATE local_sources SET revision = X'03'", "UPDATE local_sources SET revision = 3"),
            Triple("invalid-content-type", "UPDATE local_locations SET contentRevision = 'invalid'", "UPDATE local_locations SET contentRevision = 7"),
        ))
    }

    @Test(timeout = 30000L)
    fun aDifferentQueueOrDuplicateInstanceCannotReuseTheOriginalAudioTicket() = runBlocking {
        val generation = "WHERE generationId = '${LyricsRoomRows.GENERATION_ID}'"
        val entry = "$generation AND queueEntryId = '${LyricsRoomRows.ENTRY_ID}'"
        assertFaults(listOf(
            Triple("queue-revision", "UPDATE queue_head SET queueRevision = 6", "UPDATE queue_head SET queueRevision = 5"),
            Triple("another-generation", "UPDATE queue_head SET activeGenerationId = '${LyricsRoomRows.OTHER_GENERATION_ID}', " +
                "currentEntryId = '${LyricsRoomRows.OTHER_ENTRY_ID}', queueRevision = 6", "UPDATE queue_head SET " +
                "activeGenerationId = '${LyricsRoomRows.GENERATION_ID}', currentEntryId = '${LyricsRoomRows.ENTRY_ID}', queueRevision = 5"),
            Triple("duplicate-instance", "UPDATE queue_head SET currentEntryId = '${LyricsRoomRows.DUPLICATE_ENTRY_ID}'",
                "UPDATE queue_head SET currentEntryId = '${LyricsRoomRows.ENTRY_ID}'"),
            Triple("unpublished-generation", "UPDATE queue_generations SET phase = 1 $generation", "UPDATE queue_generations SET phase = 2 $generation"),
            Triple("different-committed-revision", "UPDATE queue_generations SET committedQueueRevision = 6 $generation",
                "UPDATE queue_generations SET committedQueueRevision = 5 $generation"),
            Triple("different-track", "UPDATE queue_entries SET trackId = '${LyricsRoomRows.OTHER_TRACK_ID}' $entry",
                "UPDATE queue_entries SET trackId = '${LyricsRoomRows.TRACK_ID}' $entry"),
            Triple("different-location", "UPDATE queue_entries SET locationId = '${LyricsRoomRows.OTHER_LOCATION_ID}' $entry",
                "UPDATE queue_entries SET locationId = '${LyricsRoomRows.LOCATION_ID}' $entry"),
            Triple("different-entry-content", "UPDATE queue_entries SET contentRevision = 8 $entry",
                "UPDATE queue_entries SET contentRevision = 7 $entry"),
            Triple("online-entry", "UPDATE queue_entries SET providerId = 'netease' $entry", "UPDATE queue_entries SET providerId = 'local' $entry"),
            Triple("account-bound-entry", "UPDATE queue_entries SET accountId = 'fixture-account' $entry", "UPDATE queue_entries SET accountId = NULL $entry"),
        ))
    }

    @Test(timeout = 30000L)
    fun endedOrChangedLocationEdgesCannotConfirmAnOldAssociation() = runBlocking {
        assertFaults(listOf(
            Triple("ended-track-location", "UPDATE local_track_locations SET validTo = 1", "UPDATE local_track_locations SET validTo = NULL"),
            Triple("ended-source-location", "UPDATE local_alias_versions SET validTo = 1", "UPDATE local_alias_versions SET validTo = NULL"),
            Triple("changed-alias-content", "UPDATE local_alias_versions SET contentRevision = 8", "UPDATE local_alias_versions SET contentRevision = 7"),
            Triple("nonlocal-track-row", "UPDATE local_tracks SET providerId = 'netease'", "UPDATE local_tracks SET providerId = 'local'"),
        ))
    }

    @Test(timeout = 30000L)
    fun aRealDatabaseChangeAfterTheOutsideCheckIsRejectedInsideTheTransaction() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val next = LyricsTrackPreferences(trackKey, userDelayMs = 100)
        val write = async(Dispatchers.IO) {
            store.commit(0L, next, fence) {
                if (calls.incrementAndGet() == 1) {
                    assertFalse(database.hasCurrentWriterTransaction())
                    entered.countDown()
                    assertTrue(release.await(10L, TimeUnit.SECONDS))
                }
                true
            }
        }
        try {
            withContext(Dispatchers.IO) { assertTrue(entered.await(10L, TimeUnit.SECONDS)) }
            execute("UPDATE local_sources SET authEpoch = authEpoch + 1")
        } finally { release.countDown() }
        assertEquals(LyricsTrackCommitResult.Stale(null), withTimeout(10000L) { write.await() })
        assertEquals(0L, scalar("SELECT COUNT(*) FROM lyrics_track_preferences"))
        reopen()
        assertEquals(LyricsTrackPreferences(trackKey), ready())
        assertIntegrity()
    }

    @Test(timeout = 30000L)
    fun aTicketInvalidatedAfterTheDmlRollsBackTheWholeRoomTransaction() = runBlocking {
        val calls = AtomicInteger()
        val next = LyricsTrackPreferences(trackKey, primary = LyricsRoomRows.association())
        val result = store.commit(0L, next, fence) {
            val call = calls.incrementAndGet()
            if (call == 1) assertFalse(database.hasCurrentWriterTransaction()) else assertTrue(database.hasCurrentWriterTransaction())
            if (call == 4) {
                assertEquals(1L, scalarInside("SELECT revision FROM lyrics_track_preferences"))
                false
            } else true
        }
        assertEquals(LyricsTrackCommitResult.Stale(LyricsTrackPreferences(trackKey)), result)
        assertEquals(4, calls.get())
        assertEquals(0L, scalar("SELECT COUNT(*) FROM lyrics_track_preferences"))
        reopen()
        assertEquals(LyricsTrackPreferences(trackKey), ready())
        assertIntegrity()
    }

    @Test(timeout = 30000L)
    fun realCancellationAfterTheDmlRollsBackAndReleasesTheSingleMutationSlot() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val next = LyricsTrackPreferences(trackKey, userDelayMs = 100)
        val caller = async(Dispatchers.IO) {
            store.commit(0L, next, fence) {
                if (calls.incrementAndGet() == 4) {
                    assertEquals(1L, scalarInside("SELECT revision FROM lyrics_track_preferences"))
                    entered.countDown()
                    assertTrue(release.await(10L, TimeUnit.SECONDS))
                }
                true
            }
        }
        try {
            withContext(Dispatchers.IO) { assertTrue(entered.await(10L, TimeUnit.SECONDS)) }
            assertEquals(LyricsTrackCommitResult.Failed(null, LyricsTrackIssue.BUSY), store.commit(0L, next, fence) { true })
            caller.cancel()
        } finally { release.countDown() }
        withTimeout(10000L) { caller.cancelAndJoin() }
        assertTrue(caller.isCancelled)
        assertEquals(0L, scalar("SELECT COUNT(*) FROM lyrics_track_preferences"))
        assertEquals(LyricsTrackCommitResult.Saved(next.copy(revision = 1L)), store.commit(0L, next, fence) { true })
        reopen()
        assertEquals(next.copy(revision = 1L), ready())
        assertIntegrity()
    }

    @Test(timeout = 30000L)
    fun anAlreadyInvalidOwnerTicketDoesNotBeginAWrite() = runBlocking {
        val next = LyricsTrackPreferences(trackKey, primary = LyricsRoomRows.association())
        assertEquals(LyricsTrackCommitResult.Stale(null), store.commit(0L, next, fence) { false })
        assertEquals(0L, scalar("SELECT COUNT(*) FROM lyrics_track_preferences"))
        assertEquals(LyricsRoomRows.SOURCE_REVISION, scalar("SELECT revision FROM local_sources"))
    }

    private suspend fun assertFaults(faults: List<Triple<String, String, String>>) {
        val original = LyricsTrackPreferences(trackKey, primary = LyricsRoomRows.association(), userDelayMs = 100)
        val saved = (store.commit(0L, original, fence) { true } as LyricsTrackCommitResult.Saved).preferences
        for ((name, change, repair) in faults) {
            execute(change)
            assertEquals(name, LyricsTrackCommitResult.Stale(null), store.commit(1L, saved.copy(userDelayMs = 200), fence) { true })
            assertEquals(name, 1L, scalar("SELECT revision FROM lyrics_track_preferences"))
            assertEquals(name, 100L, scalar("SELECT userDelayMs FROM lyrics_track_preferences"))
            execute(repair)
            assertEquals(name, saved, ready())
        }
        assertEquals(LyricsTrackCommitResult.Saved(saved.copy(revision = 2L, userDelayMs = 200)),
            store.commit(1L, saved.copy(userDelayMs = 200), fence) { true })
        assertIntegrity()
    }
}
