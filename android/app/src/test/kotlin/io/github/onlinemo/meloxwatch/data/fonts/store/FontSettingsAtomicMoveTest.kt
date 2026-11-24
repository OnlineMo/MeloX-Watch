package io.github.onlinemo.meloxwatch.data.fonts.store

import android.os.Build
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontIssue
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontRole
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSaveResult
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSelection
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSettings
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontTestFixtures as Fixture
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real FileStorage scratch/sync/failed move and file reopen; no native glyph/device acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], manifest = Config.NONE)
internal class FontSettingsAtomicMoveTest : FontSettingsFileFixture() {
    @Test(timeout = 30000L)
    fun failedAtomicMoveKeepsTheDurableRolesEvenWhenDataStoreCachesItsProposal() = runBlocking {
        assertTrue(Build.VERSION.SDK_INT >= 26)
        val file = settingsFile()
        val current = LyricsFontSettings(revision = 2L,
            original = LyricsFontSelection(Fixture.asset(), 400f), translation = LyricsFontSelection(Fixture.asset(), 600f))
        writeSettingsFixture(file, LyricsFontSettingsCodec.encode(current))
        val bytes = file.readBytes()
        assertTrue(file.setLastModified(123456789000L))
        val modified = file.lastModified()
        val storage = FontSettingsPhysicalStorage(file)
        val session = openStorageSettings(storage)
        assertEquals(current, session.store.read())
        assertEquals(0, storage.writeScopes.get())
        val replacement = current.copy(original = current.original.copy(requestedWeight = 650f))
        val proposed = replacement.copy(revision = current.revision + 1L)
        val destination = FontSettingsAtomicMoveDestination(file)
        val gate = FontSettingsPhysicalWriteGate(afterScratchWrite = true)
        storage.holdNextWrite(gate)
        storage.afterNextFailedWrite {
            assertTrue(file.isDirectory)
            destination.assertPreserved(bytes)
            assertFalse(File(file.absolutePath + ".tmp").exists())
            destination.restoreOriginal()
        }
        val caller = async {
            expectFontSettingsIssue(LyricsFontSettingsStorageIssue.IO) {
                session.store.compareAndSet(current.revision, replacement)
            }
        }
        try {
            try {
                val captured = withTimeout(10000L) { gate.entered.await() }
                assertEquals(proposed, LyricsFontSettingsCodec.decode(captured))
                assertTrue(File(file.absolutePath + ".tmp").length() > 0L)
                destination.obstruct()
                destination.assertPreserved(bytes)
                expectFontSettingsIssue(LyricsFontSettingsStorageIssue.BUSY) { session.store.read() }
                expectFontSettingsIssue(LyricsFontSettingsStorageIssue.BUSY) { session.store.compareAndSet(current.revision, replacement) }
                assertEquals(0, storage.exitedWrites.get())
                assertEquals(0, storage.failedWrites.get())
                assertEquals(0, storage.committedWrites.get())
            } finally { gate.release.complete(Unit) }
            withTimeout(10000L) { caller.await() }
            assertEquals(1, storage.writeScopes.get())
            assertEquals(1, storage.exitedWrites.get())
            assertEquals(1, storage.failedWrites.get())
            assertEquals(0, storage.committedWrites.get())
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(modified, file.lastModified())
            // The pinned library's cache remains poisoned even after identity reads. Only the
            // adapter's locked file result can establish which role record is actually durable.
            assertEquals(proposed, LyricsFontSettingsCodec.decode(session.dataStore.data.first()))
            repeat(2) { assertEquals(current, session.store.read()) }
            assertEquals(proposed, LyricsFontSettingsCodec.decode(session.dataStore.data.first()))
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(modified, file.lastModified())
            assertEquals(1, storage.writeScopes.get())
            assertEquals(0, storage.committedWrites.get())
            assertEquals(LyricsFontSaveResult.Saved(proposed), session.store.compareAndSet(current.revision, replacement))
            assertEquals(2, storage.writeScopes.get())
            assertEquals(1, storage.committedWrites.get())
            assertEquals(1, storage.failedWrites.get())
            session.close()
            assertEquals(proposed, openSettings(file).store.read())
        } finally {
            gate.release.complete(Unit)
            destination.restoreOriginal()
        }
    }

    @Test(timeout = 30000L)
    fun failedAtomicMoveRetainsTheEditorsSavedFontsAndProposalUntilCancelOrAnExplicitRetry() = runBlocking {
        assertTrue(Build.VERSION.SDK_INT >= 26)
        for (retry in listOf(false, true)) {
            val file = settingsFile()
            val storage = FontSettingsPhysicalStorage(file)
            val session = openStorageSettings(storage)
            val fixture = FontSettingsEditorFixture(temporary.newFolder("move-failure-$retry"), session.store)
            fixture.choose(LyricsFontRole.ORIGINAL, Fixture.VARIABLE, 400f)
            fixture.choose(LyricsFontRole.TRANSLATION, Fixture.STATIC, null)
            val actual = session.store.read()
            val bytes = file.readBytes()
            val writes = storage.committedWrites.get()
            fixture.editor.begin(LyricsFontRole.ORIGINAL, fixture.text)
            fixture.editor.importFont(fixture.request(Fixture.VARIABLE), fixture.text)
            fixture.editor.previewWeight(650f, fixture.text)
            val draft = checkNotNull(fixture.editor.state.value.draft)
            val proposal = checkNotNull(draft.selection.asset)
            val proposed = actual.withSelection(LyricsFontRole.ORIGINAL, draft.selection).copy(revision = actual.revision + 1L)
            val destination = FontSettingsAtomicMoveDestination(file)
            val gate = FontSettingsPhysicalWriteGate(afterScratchWrite = true)
            storage.holdNextWrite(gate)
            storage.afterNextFailedWrite {
                assertTrue(file.isDirectory)
                destination.assertPreserved(bytes)
                assertFalse(File(file.absolutePath + ".tmp").exists())
                destination.restoreOriginal()
            }
            val caller = async { fixture.editor.confirm(fixture.text) }
            try {
                try {
                    val captured = withTimeout(10000L) { gate.entered.await() }
                    assertEquals(proposed, LyricsFontSettingsCodec.decode(captured))
                    assertTrue(File(file.absolutePath + ".tmp").length() > 0L)
                    destination.obstruct()
                    destination.assertPreserved(bytes)
                    expectFontSettingsIssue(LyricsFontSettingsStorageIssue.BUSY) { session.store.read() }
                    actual.assetIds().forEach { id -> assertTrue(File(fixture.directory, "$id.font").isFile) }
                    assertTrue(fixture.assetFile(proposal).isFile)
                } finally { gate.release.complete(Unit) }
                withTimeout(10000L) { caller.await() }
                assertEquals(1, storage.failedWrites.get())
                assertEquals(writes, storage.committedWrites.get())
                assertEquals(actual, fixture.editor.state.value.saved)
                assertEquals(LyricsFontIssue.SAVE_FAILED, fixture.editor.state.value.issue)
                assertEquals(proposal, fixture.editor.state.value.retry?.selection?.asset)
                assertNull(fixture.editor.state.value.draft)
                assertEquals(actual.translation, fixture.editor.state.value.saved?.translation)
                assertArrayEquals(bytes, file.readBytes())
                actual.assetIds().forEach { id -> assertTrue(File(fixture.directory, "$id.font").isFile) }
                assertTrue(fixture.assetFile(proposal).isFile)
                assertEquals(proposed, LyricsFontSettingsCodec.decode(session.dataStore.data.first()))
                assertEquals(actual, session.store.read())
                val expected = if (retry) {
                    fixture.editor.retrySave(fixture.text)
                    assertEquals(proposed, fixture.editor.state.value.saved)
                    assertEquals(writes + 1, storage.committedWrites.get())
                    assertTrue(fixture.assetFile(proposal).isFile)
                    assertFalse(fixture.assetFile(checkNotNull(actual.original.asset)).exists())
                    proposed
                } else {
                    fixture.editor.cancel(fixture.text)
                    assertEquals(actual, fixture.editor.state.value.saved)
                    assertEquals(writes, storage.committedWrites.get())
                    assertFalse(fixture.assetFile(proposal).exists())
                    actual.assetIds().forEach { id -> assertTrue(File(fixture.directory, "$id.font").isFile) }
                    assertArrayEquals(bytes, file.readBytes())
                    actual
                }
                assertNull(fixture.editor.state.value.retry)
                assertNull(fixture.editor.state.value.issue)
                assertTrue(fixture.assetFile(checkNotNull(actual.translation.asset)).isFile)
                assertEquals(1, storage.failedWrites.get())
                assertEquals(expected, session.store.read())
                session.close()
                val reopened = openSettings(file)
                val restored = fixture.newEditor(reopened.store)
                restored.restore(fixture.text)
                assertEquals(expected, restored.state.value.saved)
                assertNull(restored.state.value.issue)
                expected.assetIds().forEach { id -> assertTrue(File(fixture.directory, "$id.font").isFile) }
                assertTrue(fixture.assetFile(checkNotNull(actual.translation.asset)).isFile)
                fixture.assertOriginalsUnchanged()
            } finally {
                gate.release.complete(Unit)
                destination.restoreOriginal()
            }
        }
    }
}

/** A nonempty destination makes the actual Files.move fail; the old record is preserved beside it. */
private class FontSettingsAtomicMoveDestination(private val file: File) {
    private val preserved = File(checkNotNull(file.parentFile), file.name + ".held")
    private val blocker = File(file, "atomic-move-blocker")
    private var displaced = false

    fun obstruct() {
        check(file.isFile)
        check(!preserved.exists())
        check(file.renameTo(preserved))
        displaced = true
        check(file.mkdir())
        blocker.writeText("Keep the actual atomicMove destination nonempty")
    }

    fun assertPreserved(bytes: ByteArray) {
        assertTrue(preserved.isFile)
        assertArrayEquals(bytes, preserved.readBytes())
    }

    fun restoreOriginal() {
        if (!displaced) return
        if (blocker.exists()) {
            check(blocker.isFile)
            check(blocker.delete())
        }
        if (file.exists()) {
            // A surprising successful install must not be overwritten by fixture cleanup.
            check(file.isDirectory)
            check(file.list()?.isEmpty() == true)
            check(file.delete())
        }
        check(preserved.renameTo(file))
        displaced = false
    }
}
