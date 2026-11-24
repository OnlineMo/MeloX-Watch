package io.github.onlinemo.meloxwatch.data.fonts.store

import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontRole
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSaveResult
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSelection
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSettings
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontTestFixtures as Fixture
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class FontSettingsPersistenceTest : FontSettingsFileFixture() {
    @Test(timeout = 30000L)
    fun readingAnActuallyEmptyFileDoesNotWriteAndTheFirstConfirmedDefaultIsRevisionOne() = runBlocking {
        val file = settingsFile()
        val session = openSettings(file)
        assertEquals(LyricsFontSettings(), session.store.read())
        assertFalse(file.exists())
        val saved = LyricsFontSettings(revision = 1L)
        assertEquals(LyricsFontSaveResult.Saved(saved), session.store.compareAndSet(0L, LyricsFontSettings()))
        assertTrue(file.isFile)
        session.close()
        assertEquals(saved, openSettings(file).store.read())
    }

    @Test(timeout = 30000L)
    fun bothRolesAndTheirExactFiniteRequestsSurviveTheRealFileReopen() = runBlocking {
        val file = settingsFile()
        val session = openSettings(file)
        val original = LyricsFontSelection(Fixture.asset(name = Fixture.VARIABLE), 200f)
        val translation = LyricsFontSelection(Fixture.asset(Fixture.bytes(Fixture.STATIC), Fixture.STATIC), 650f)
        val next = LyricsFontSettings(original = original, translation = translation)
        assertEquals(LyricsFontSaveResult.Saved(next.copy(revision = 1L)), session.store.compareAndSet(0L, next))
        session.close()
        assertEquals(next.copy(revision = 1L), openSettings(file).store.read())
    }

    @Test(timeout = 30000L)
    fun changingOneRoleKeepsTheOtherAssetAndRequestUnderTheSharedRevision() = runBlocking {
        val file = settingsFile()
        val session = openSettings(file)
        val first = LyricsFontSettings(original = LyricsFontSelection(Fixture.asset(), 400f),
            translation = LyricsFontSelection(Fixture.asset(), 600f))
        val saved = (session.store.compareAndSet(0L, first) as LyricsFontSaveResult.Saved).settings
        val originalOnly = saved.withSelection(LyricsFontRole.ORIGINAL, saved.original.copy(requestedWeight = 250f))
        val second = (session.store.compareAndSet(1L, originalOnly) as LyricsFontSaveResult.Saved).settings
        assertEquals(saved.translation, second.translation)
        val translationOnly = second.withSelection(LyricsFontRole.TRANSLATION, second.translation.copy(requestedWeight = 650f))
        val third = (session.store.compareAndSet(2L, translationOnly) as LyricsFontSaveResult.Saved).settings
        assertEquals(second.original, third.original)
        assertEquals(3L, third.revision)
        session.close()
        assertEquals(third, openSettings(file).store.read())
    }

    @Test(timeout = 30000L)
    fun clearingOneCustomRolePreservesTheOtherAndOptionalRequestsNeverClaimAnActiveAxis() = runBlocking {
        val file = settingsFile()
        val session = openSettings(file)
        val asset = Fixture.asset().copy(displayName = "F".repeat(512))
        val first = LyricsFontSettings(original = LyricsFontSelection(asset, Float.MAX_VALUE),
            translation = LyricsFontSelection(requestedWeight = -Float.MAX_VALUE))
        val saved = (session.store.compareAndSet(0L, first) as LyricsFontSaveResult.Saved).settings
        val clear = saved.withSelection(LyricsFontRole.ORIGINAL, LyricsFontSelection())
        val cleared = (session.store.compareAndSet(1L, clear) as LyricsFontSaveResult.Saved).settings
        assertEquals(saved.translation, cleared.translation)
        assertEquals(LyricsFontSelection(), cleared.original)
        session.close()
        assertEquals(cleared, openSettings(file).store.read())
    }

    @Test(timeout = 30000L)
    fun aStaleAdapterReadsTheRealConflictWithoutRewritingBytesOrLosingTheOtherRole() = runBlocking {
        val file = settingsFile()
        val session = openSettings(file)
        val other = DataStoreLyricsFontSettingsStore(session.dataStore)
        val old = other.read()
        val replacement = old.copy(translation = LyricsFontSelection(Fixture.asset(), 600f))
        val saved = (session.store.compareAndSet(0L, replacement) as LyricsFontSaveResult.Saved).settings
        val bytes = file.readBytes()
        assertTrue(file.setLastModified(123456789000L))
        val modified = file.lastModified()
        assertEquals(LyricsFontSaveResult.Conflict(saved), other.compareAndSet(0L, old.copy(original = LyricsFontSelection(Fixture.asset(), 300f))))
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(modified, file.lastModified())
        session.close()
        assertEquals(saved, openSettings(file).store.read())
    }

    @Test(timeout = 30000L)
    fun invalidAndExhaustedRevisionsPreserveTheOriginalFileAndNeverWrap() = runBlocking {
        val empty = settingsFile()
        lateinit var controls: ControlledFontSettingsDataStore
        val fresh = openSettings(empty) { ControlledFontSettingsDataStore(it).also { controls = it } }
        expectFontSettingsIssue(LyricsFontSettingsStorageIssue.INVALID_REVISION) {
            fresh.store.compareAndSet(-1L, LyricsFontSettings())
        }
        expectFontSettingsIssue(LyricsFontSettingsStorageIssue.INVALID_REVISION) {
            fresh.store.compareAndSet(2L, LyricsFontSettings(revision = 1L))
        }
        assertEquals(0, controls.writeCalls.get())
        assertEquals(0, controls.readSubscriptions.get())
        assertFalse(empty.exists())

        val exhausted = settingsFile()
        val current = LyricsFontSettings(revision = Long.MAX_VALUE, original = LyricsFontSelection(Fixture.asset(), 400f))
        writeSettingsFixture(exhausted, LyricsFontSettingsCodec.encode(current))
        val bytes = exhausted.readBytes()
        val session = openSettings(exhausted)
        for (next in listOf(current, current.copy(original = current.original.copy(requestedWeight = 600f)))) {
            expectFontSettingsIssue(LyricsFontSettingsStorageIssue.REVISION_EXHAUSTED) {
                session.store.compareAndSet(Long.MAX_VALUE, next)
            }
        }
        assertArrayEquals(bytes, exhausted.readBytes())
        assertEquals(current, session.store.read())
    }

    @Test(timeout = 30000L)
    fun successfulEqualCasIncrementsOnceAndThisFileCannotChangeUnrelatedSettingsOrResources() = runBlocking {
        val file = settingsFile()
        val sentinels = listOf("lyrics_display.preferences_pb", "user_preferences.preferences_pb", "library.db", "audio-original", "font-original").map { name ->
            File(temporary.root, name).apply { writeText("preserved-$name") }
        }.associateWith(File::readBytes)
        val session = openSettings(file)
        val first = (session.store.compareAndSet(0L, LyricsFontSettings()) as LyricsFontSaveResult.Saved).settings
        val second = (session.store.compareAndSet(1L, first) as LyricsFontSaveResult.Saved).settings
        assertEquals(first.copy(revision = 2L), second)
        assertEquals(LyricsFontSaveResult.Conflict(second), session.store.compareAndSet(1L, first))
        sentinels.forEach { (sentinel, bytes) -> assertArrayEquals(bytes, sentinel.readBytes()) }
        session.close()
        assertEquals(second, openSettings(file).store.read())
    }
}
