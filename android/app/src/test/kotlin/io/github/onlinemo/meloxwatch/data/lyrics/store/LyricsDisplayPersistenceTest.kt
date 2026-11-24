package io.github.onlinemo.meloxwatch.data.lyrics.store

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class LyricsDisplayPersistenceTest : LyricsDisplayFileFixture() {
    @Test(timeout = 30000L)
    fun emptyFileIsNotReportedAsAnAlreadySavedDefault() = runBlocking {
        val file = preferencesFile()
        val session = openDisplay(file)
        assertEquals(LyricsDisplayState.Ready(LyricsDisplayPreferences(), persisted = false), session.ready())
        assertFalse(file.exists())

        val result = session.store.save(0L, LyricsDisplayPreferences())
        assertEquals(LyricsDisplayWriteResult.Saved(LyricsDisplayPreferences(revision = 1L)), result)
        assertTrue(file.isFile)
        session.close()
        assertEquals(LyricsDisplayState.Ready(LyricsDisplayPreferences(revision = 1L), persisted = true), openDisplay(file).ready())
    }

    @Test(timeout = 30000L)
    fun allDisplayChoicesSurviveClosingAndReopeningTheRealFile() = runBlocking {
        val file = preferencesFile()
        val session = openDisplay(file)
        session.ready()
        val next = LyricsDisplayPreferences(textSizeSp = 32f, blockSpacingDp = 4f,
            alignment = LyricsDisplayAlignment.START, anchorFraction = 0.25f, horizontalMarginDp = 24f,
            showTranslation = false, emphasizeWords = true, legacyKeepScreenOn = true)
        val saved = (session.store.save(0L, next) as LyricsDisplayWriteResult.Saved).preferences
        assertEquals(next.copy(revision = 1L), saved)
        session.close()
        assertEquals(LyricsDisplayState.Ready(saved, persisted = true), openDisplay(file).ready())
    }

    @Test(timeout = 30000L)
    fun repeatingAConfirmedValueDoesNotIncrementRevisionOrRewriteBytes() = runBlocking {
        val file = preferencesFile()
        val session = openDisplay(file)
        session.ready()
        val saved = (session.store.save(0L, LyricsDisplayPreferences(textSizeSp = 22f)) as LyricsDisplayWriteResult.Saved).preferences
        val bytes = file.readBytes()
        assertTrue(file.setLastModified(123456789000L))
        val modified = file.lastModified()

        assertEquals(LyricsDisplayWriteResult.Unchanged(saved), session.store.save(saved.revision, saved))
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(modified, file.lastModified())
    }

    @Test(timeout = 30000L)
    fun staleEditorRevisionCannotOverwriteARealCommittedValue() = runBlocking {
        val file = preferencesFile()
        val session = openDisplay(file)
        session.ready()
        val saved = (session.store.save(0L, LyricsDisplayPreferences(textSizeSp = 18f)) as LyricsDisplayWriteResult.Saved).preferences
        val bytes = file.readBytes()
        assertEquals(LyricsDisplayWriteResult.Conflict(saved), session.store.save(0L, LyricsDisplayPreferences(blockSpacingDp = 20f)))
        assertArrayEquals(bytes, file.readBytes())
        session.close()
        assertEquals(saved, openDisplay(file).ready().preferences)
    }

    @Test(timeout = 30000L)
    fun mismatchedExpectedRevisionIsRejectedBeforeAnyWrite() = runBlocking {
        val file = preferencesFile()
        val session = openDisplay(file)
        session.ready()
        for ((expected, next) in listOf(-1L to LyricsDisplayPreferences(), 2L to LyricsDisplayPreferences(revision = 1L))) {
            assertEquals(LyricsDisplayWriteResult.Failed(LyricsDisplayPreferences(), LyricsDisplayIssue.INVALID_REVISION),
                session.store.save(expected, next))
        }
        assertFalse(file.exists())
    }

    @Test(timeout = 30000L)
    fun exhaustedRevisionAllowsOnlyUnchangedAndPreservesOriginalBytes() = runBlocking {
        val file = preferencesFile()
        val original = LyricsDisplayPreferences(revision = Long.MAX_VALUE, textSizeSp = 28f)
        writeDisplayFixture(file, LyricsDisplayCodec.encode(original))
        val bytes = file.readBytes()
        val session = openDisplay(file)
        assertEquals(original, session.ready().preferences)
        assertEquals(LyricsDisplayWriteResult.Unchanged(original), session.store.save(Long.MAX_VALUE, original))
        assertEquals(LyricsDisplayWriteResult.Failed(original, LyricsDisplayIssue.REVISION_EXHAUSTED),
            session.store.save(Long.MAX_VALUE, original.copy(blockSpacingDp = 18f)))
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test(timeout = 30000L)
    fun resetCancellationPreservesTheDisplayAndConfirmedResetTouchesOnlyItsFile() = runBlocking {
        val file = preferencesFile()
        val unrelated = listOf("font.preferences_pb", "library.db", "lyric-association-sentinel", "grant-sentinel").map { name ->
            File(temporaryFolder.root, name).apply { writeText("preserved-$name") }
        }.associateWith { it.readBytes() }
        val session = openDisplay(file)
        session.ready()
        val saved = (session.store.save(0L, LyricsDisplayPreferences(textSizeSp = 30f, legacyKeepScreenOn = true)) as
            LyricsDisplayWriteResult.Saved).preferences
        val bytes = file.readBytes()
        assertEquals(LyricsDisplayWriteResult.Cancelled(saved), session.store.reset(1L, LyricsDisplayResetConfirmation.CANCELLED))
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(LyricsDisplayWriteResult.Saved(LyricsDisplayPreferences(revision = 2L)),
            session.store.reset(1L, LyricsDisplayResetConfirmation.CONFIRMED))
        unrelated.forEach { (sentinel, original) -> assertArrayEquals(original, sentinel.readBytes()) }
        session.close()
        assertEquals(LyricsDisplayPreferences(revision = 2L), openDisplay(file).ready().preferences)
    }
}
