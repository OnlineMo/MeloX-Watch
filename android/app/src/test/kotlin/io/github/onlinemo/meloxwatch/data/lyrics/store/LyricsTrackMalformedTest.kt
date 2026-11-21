package io.github.onlinemo.meloxwatch.data.lyrics.store

import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import io.github.onlinemo.meloxwatch.data.database.cursorStorageType
import io.github.onlinemo.meloxwatch.data.database.hasCurrentWriterTransaction
import io.github.onlinemo.meloxwatch.data.database.withPreservedTransaction
import io.github.onlinemo.meloxwatch.data.database.withTestWriterSql
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsEncodingChoice
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
internal class LyricsTrackMalformedTest : LyricsRoomPlaybackFixture() {
    @Test(timeout = 30000L)
    fun aPartialPrimaryOrTranslationAssociationIsRejectedWithoutReplacingItsRow() = runBlocking {
        val current = saveBoth()
        execute("UPDATE lyrics_track_preferences SET primaryReadFlags = NULL")
        assertRejectedAndPreserved(current, LyricsTrackIssue.CORRUPT_RECORD)
        execute("UPDATE lyrics_track_preferences SET primaryReadFlags = 1, translationUri = NULL")
        assertRejectedAndPreserved(current, LyricsTrackIssue.CORRUPT_RECORD)
    }

    @Test(timeout = 30000L)
    fun wrongSqliteStorageTypesNeverNarrowIntoValidModelValues() = runBlocking {
        val current = saveBoth()
        for ((change, repair) in listOf(
            "schemaVersion = X'01'" to "schemaVersion = 1",
            "revision = 'invalid'" to "revision = 1",
            "userDelayMs = X'64'" to "userDelayMs = 100",
            "primaryUri = X'636F6E74656E74'" to "primaryUri = '${current.primary?.uri}'",
            "translationReadFlags = 'invalid'" to "translationReadFlags = 1",
        )) {
            execute("UPDATE lyrics_track_preferences SET $change")
            val row = database.lyricsDao().row(trackKey.trackId) ?: throw AssertionError("Stored row required")
            assertFalse(row.validTypes)
            if (change.startsWith("primaryUri")) assertNull(row.primaryUri)
            assertRejectedAndPreserved(current, LyricsTrackIssue.CORRUPT_RECORD)
            execute("UPDATE lyrics_track_preferences SET $repair")
            assertEquals(current, ready())
        }
    }

    @Test(timeout = 30000L)
    fun unknownSchemaEncodingInvalidUriHashFlagsAndDelayStayReadOnly() = runBlocking {
        val current = saveBoth()
        val faults = listOf(
            Triple("schemaVersion = 0", "schemaVersion = 1", LyricsTrackIssue.UNSUPPORTED_SCHEMA),
            Triple("schemaVersion = 2", "schemaVersion = 1", LyricsTrackIssue.UNSUPPORTED_SCHEMA),
            Triple("primaryEncoding = 'UTF16_BE'", "primaryEncoding = '${LyricsEncodingChoice.AUTO_BOM_OR_UTF8.name}'", LyricsTrackIssue.CORRUPT_RECORD),
            Triple("primaryUri = 'file:///fixture/song.lrc'", "primaryUri = '${current.primary?.uri}'", LyricsTrackIssue.CORRUPT_RECORD),
            Triple("translationConfirmedContentSha256 = '${"B".repeat(64)}'", "translationConfirmedContentSha256 = '${"b".repeat(64)}'", LyricsTrackIssue.CORRUPT_RECORD),
            Triple("primaryReadFlags = 3", "primaryReadFlags = 1", LyricsTrackIssue.CORRUPT_RECORD),
            Triple("primaryReadFlags = 2147483648", "primaryReadFlags = 1", LyricsTrackIssue.CORRUPT_RECORD),
            Triple("userDelayMs = 150", "userDelayMs = 100", LyricsTrackIssue.CORRUPT_RECORD),
            Triple("userDelayMs = 10001", "userDelayMs = 100", LyricsTrackIssue.CORRUPT_RECORD),
            Triple("revision = -1", "revision = 1", LyricsTrackIssue.CORRUPT_RECORD),
        )
        for ((change, repair, issue) in faults) {
            execute("UPDATE lyrics_track_preferences SET $change")
            assertRejectedAndPreserved(current, issue)
            execute("UPDATE lyrics_track_preferences SET $repair")
            assertEquals(current, ready())
        }
    }

    @Test(timeout = 30000L)
    fun giantPersistedTextIsProjectedWithABoundAndIsNeverSilentlyTruncatedToAValidAssociation() = runBlocking {
        val current = saveBoth()
        val giant = "content://lyrics-fixture/document/" + "x".repeat(32768)
        execute("UPDATE lyrics_track_preferences SET primaryUri = ?, translationDocumentId = ?", giant, giant)
        val row = database.lyricsDao().row(trackKey.trackId) ?: throw AssertionError("Stored row required")
        assertEquals(8193, row.primaryUri?.length)
        assertEquals(8193, row.translationDocumentId?.length)
        assertTrue(scalar("SELECT length(primaryUri) FROM lyrics_track_preferences") > 8193L)
        assertRejectedAndPreserved(current, LyricsTrackIssue.CORRUPT_RECORD)
    }

    @Test(timeout = 30000L)
    fun exactAssociationLimitsRoundTripAndClearingDoesNotEraseTheOffset() = runBlocking {
        val prefix = "content://lyrics-fixture/document/"
        val association = LyricsAssociation("d".repeat(8192), "s".repeat(128), prefix + "u".repeat(8192 - prefix.length),
            1, LyricsEncodingChoice.UTF8, "a".repeat(64))
        val next = LyricsTrackPreferences(trackKey, primary = association, translation = association, userDelayMs = 10_000)
        assertEquals(LyricsTrackCommitResult.Saved(next.copy(revision = 1L)), store.commit(0L, next, fence) { true })
        reopen()
        assertEquals(next.copy(revision = 1L), ready())
        val clear = ready().copy(primary = null, translation = null)
        assertEquals(LyricsTrackCommitResult.Saved(clear.copy(revision = 2L)), store.commit(1L, clear, fence) { true })
        reopen()
        assertEquals(LyricsTrackPreferences(trackKey, revision = 2L, userDelayMs = 10_000), ready())
        assertIntegrity()
    }

    @Test(timeout = 30000L)
    fun actualForeignKeysRejectMissingParentsAndRestrictDeletionOfALyricsOnlyTrack() = runBlocking {
        assertEquals(1L, scalar("PRAGMA foreign_keys"))
        val absent = TrackKey(TrackKey.LOCAL_PROVIDER, "lyrics-absent-parent")
        try {
            database.withPreservedTransaction {
                database.lyricsDao().insert(LyricsTrackPreferences(absent, revision = 1L, userDelayMs = 100).entity())
            }
            fail("A missing local track parent must be rejected by SQLite")
        } catch (_: SQLiteConstraintException) { }
        val solo = TrackKey(TrackKey.LOCAL_PROVIDER, "lyrics-only-parent")
        execute("INSERT INTO local_tracks(trackId, providerId, firstAddedEpochMs, firstAddedSequence) VALUES(?, 'local', 0, 3)", solo.trackId)
        database.withPreservedTransaction {
            database.lyricsDao().insert(LyricsTrackPreferences(solo, revision = 1L, userDelayMs = 100).entity())
        }
        try {
            execute("DELETE FROM local_tracks WHERE trackId = ?", solo.trackId)
            fail("The lyrics foreign key alone must restrict parent deletion")
        } catch (_: SQLiteConstraintException) { }
        assertEquals(LyricsTrackPreferences(solo, revision = 1L, userDelayMs = 100), ready(solo))
        assertIntegrity()
    }

    @Test(timeout = 30000L)
    fun anOnlineProviderRowCannotBeReadOrWrittenAsALocalTrackWithTheSameId() = runBlocking {
        execute("UPDATE local_tracks SET providerId = 'netease' WHERE trackId = ?", trackKey.trackId)
        assertEquals(LyricsTrackReadResult.Unavailable(LyricsTrackIssue.TRACK_MISSING), store.read(trackKey))
        assertEquals(LyricsTrackCommitResult.Stale(null),
            store.commit(0L, LyricsTrackPreferences(trackKey, userDelayMs = 100), fence) { true })
        assertEquals(0L, scalar("SELECT COUNT(*) FROM lyrics_track_preferences"))
    }

    @Test(timeout = 30000L)
    fun aRealSqliteWriteAbortRollsBackAndExplicitRetryCanSave() = runBlocking {
        execute("CREATE TRIGGER lyrics_controlled_failure BEFORE INSERT ON lyrics_track_preferences " +
            "BEGIN SELECT RAISE(ABORT, 'controlled write failure'); END")
        val next = LyricsTrackPreferences(trackKey, userDelayMs = 100)
        assertEquals(LyricsTrackCommitResult.Failed(LyricsTrackPreferences(trackKey), LyricsTrackIssue.IO),
            store.commit(0L, next, fence) { true })
        assertEquals(0L, scalar("SELECT COUNT(*) FROM lyrics_track_preferences"))
        assertEquals(1200L, scalar("SELECT positionMs FROM queue_head"))
        execute("DROP TRIGGER lyrics_controlled_failure")
        assertEquals(LyricsTrackCommitResult.Saved(next.copy(revision = 1L)), store.commit(0L, next, fence) { true })
        reopen()
        assertEquals(next.copy(revision = 1L), ready())
        assertIntegrity()
    }

    @Test(timeout = 30000L)
    fun aRealDeferredForeignKeyCommitFailureKeepsOldSettingsAndReusesTheWriterForExplicitRetry() = runBlocking {
        val calls = AtomicInteger()
        val injectionCompleted = AtomicInteger()
        val missingParent = "lyrics-commit-missing-parent"
        val next = LyricsTrackPreferences(trackKey, primary = LyricsRoomRows.association(), userDelayMs = 100)
        val result = store.commit(0L, next, fence) {
            if (calls.incrementAndGet() == 4) {
                assertTrue(database.hasCurrentWriterTransaction())
                assertEquals(1L, scalarInside("SELECT revision FROM lyrics_track_preferences"))
                // Prove the deferred orphan exists before returning to the native COMMIT path.
                database.withTestWriterSql {
                    execute("PRAGMA defer_foreign_keys = ON")
                    assertEquals(1L, scalarLong("PRAGMA defer_foreign_keys"))
                    execute("UPDATE lyrics_track_preferences SET trackId = ? WHERE trackId = ?",
                        missingParent, trackKey.trackId)
                    assertEquals(0L, scalarLong("SELECT COUNT(*) FROM local_tracks WHERE trackId = ?", missingParent))
                    query("SELECT revision, userDelayMs FROM lyrics_track_preferences WHERE trackId = ?", missingParent) {
                        assertTrue(it.step())
                        assertEquals(1L, it.getLong(0))
                        assertEquals(100L, it.getLong(1))
                        assertFalse(it.step())
                    }
                    query("PRAGMA foreign_key_check(lyrics_track_preferences)") {
                        assertTrue(it.step())
                        assertEquals("lyrics_track_preferences", it.getText(0))
                        assertEquals("local_tracks", it.getText(2))
                        assertEquals(0L, it.getLong(3))
                        assertFalse(it.step())
                    }
                }
                injectionCompleted.set(1)
            }
            true
        }
        assertEquals("The deferred mutation must finish before the transaction attempts COMMIT", 1, injectionCompleted.get())
        assertEquals(LyricsTrackCommitResult.Failed(LyricsTrackPreferences(trackKey), LyricsTrackIssue.IO), result)
        assertEquals(4, calls.get())
        assertFalse(database.hasCurrentWriterTransaction())
        assertEquals(0L, scalar("SELECT COUNT(*) FROM lyrics_track_preferences"))
        assertEquals(0L, scalar("PRAGMA defer_foreign_keys"))
        assertEquals(1200L, scalar("SELECT positionMs FROM queue_head"))
        assertEquals(LyricsRoomRows.QUEUE_REVISION, scalar("SELECT queueRevision FROM queue_head"))
        assertEquals(LyricsTrackPreferences(trackKey), ready())
        assertIntegrity()
        // Retry on the currently open database before an explicit close/reopen.
        assertEquals(LyricsTrackCommitResult.Saved(next.copy(revision = 1L)), store.commit(0L, next, fence) { true })
        reopen()
        assertEquals(next.copy(revision = 1L), ready())
        assertIntegrity()
    }

    @Test(timeout = 30000L)
    fun aClosedDatabaseReportsStorageFailureWithoutClaimingASave() = runBlocking {
        database.close()
        assertEquals(LyricsTrackReadResult.Unavailable(LyricsTrackIssue.IO), store.read(trackKey))
        assertEquals(LyricsTrackCommitResult.Failed(null, LyricsTrackIssue.IO),
            store.commit(0L, LyricsTrackPreferences(trackKey, userDelayMs = 100), fence) { true })
        reopen()
        assertEquals(LyricsTrackPreferences(trackKey), ready())
    }

    private suspend fun saveBoth(): LyricsTrackPreferences {
        val next = LyricsTrackPreferences(trackKey, primary = LyricsRoomRows.association(),
            translation = LyricsRoomRows.association(translation = true), userDelayMs = 100)
        return (store.commit(0L, next, fence) { true } as LyricsTrackCommitResult.Saved).preferences
    }

    private suspend fun assertRejectedAndPreserved(current: LyricsTrackPreferences, issue: LyricsTrackIssue) {
        val fingerprint = rawRecordFingerprint()
        assertEquals(LyricsTrackReadResult.Unavailable(issue), store.read(trackKey))
        assertEquals(LyricsTrackCommitResult.Failed(null, issue), store.commit(1L, current.copy(userDelayMs = 200), fence) { true })
        assertEquals(fingerprint, rawRecordFingerprint())
    }

    private suspend fun rawRecordFingerprint(): String = database.withTestWriterSql {
        val digest = MessageDigest.getInstance("SHA-256")
        query("SELECT * FROM lyrics_track_preferences ORDER BY trackId") { statement ->
            while (statement.step()) repeat(statement.getColumnCount()) { column ->
                val type = statement.cursorStorageType(column)
                digest.update(type.toByte())
                val bytes = when (type) {
                    Cursor.FIELD_TYPE_NULL -> ByteArray(0)
                    Cursor.FIELD_TYPE_BLOB -> statement.getBlob(column)
                    else -> statement.getText(column).toByteArray(Charsets.UTF_8)
                }
                digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
                digest.update(0.toByte())
                digest.update(bytes)
            }
        }
        digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
