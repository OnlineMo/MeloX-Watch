package io.github.onlinemo.meloxwatch.data.fonts.store

import androidx.datastore.preferences.core.intPreferencesKey
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontIssue
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontRole
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSaveResult
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontTestFixtures as Fixture
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** File-backed editor recovery, not synthetic persistence or native glyph acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
internal class FontSettingsEditorIntegrationTest : FontSettingsFileFixture() {
    @Test(timeout = 30000L)
    fun bothRealPrivateSelectionsRestoreFromTheReopenedSettingsFile() = runBlocking {
        val file = settingsFile()
        val session = openSettings(file)
        val fixture = FontSettingsEditorFixture(temporary.newFolder("restore"), session.store)
        fixture.choose(LyricsFontRole.ORIGINAL, Fixture.VARIABLE, 400f)
        fixture.choose(LyricsFontRole.TRANSLATION, Fixture.STATIC, null)
        val saved = session.store.read()
        session.close()
        val reopened = openSettings(file)
        val restored = fixture.newEditor(reopened.store)
        restored.restore(fixture.text)
        assertEquals(saved, restored.state.value.saved)
        assertEquals(400f, restored.state.value.original.configuredAxisWeight)
        assertNull(restored.state.value.translation.configuredAxisWeight)
        assertNotNull(restored.state.value.original.typeface)
        assertNotNull(restored.state.value.translation.typeface)
        fixture.assertOriginalsUnchanged()
    }

    @Test(timeout = 30000L)
    fun cancellingPreviewKeepsThePersistedBytesAndRemovesOnlyTheUnconfirmedPrivateCopy() = runBlocking {
        val file = settingsFile()
        val session = openSettings(file)
        val fixture = FontSettingsEditorFixture(temporary.newFolder("preview-cancel"), session.store)
        fixture.choose(LyricsFontRole.ORIGINAL, Fixture.VARIABLE, 400f)
        fixture.choose(LyricsFontRole.TRANSLATION, Fixture.STATIC, null)
        val actual = session.store.read()
        val bytes = file.readBytes()
        fixture.editor.begin(LyricsFontRole.ORIGINAL, fixture.text)
        fixture.editor.importFont(fixture.request(Fixture.VARIABLE), fixture.text)
        fixture.editor.previewWeight(650f, fixture.text)
        val proposal = checkNotNull(fixture.editor.state.value.draft?.selection?.asset)
        fixture.editor.cancel(fixture.text)
        assertEquals(actual, fixture.editor.state.value.saved)
        assertEquals(400f, fixture.editor.state.value.original.configuredAxisWeight)
        assertArrayEquals(bytes, file.readBytes())
        assertFalse(fixture.assetFile(proposal).exists())
        assertTrue(fixture.assetFile(checkNotNull(actual.translation.asset)).isFile)
        fixture.assertOriginalsUnchanged()
    }

    @Test(timeout = 30000L)
    fun aRealDiskFailureRestoresTheSavedPreviewAndExplicitRetryPreservesTheOtherRole() = runBlocking {
        val file = settingsFile()
        val session = openSettings(file)
        val fixture = FontSettingsEditorFixture(temporary.newFolder("failed-write"), session.store)
        fixture.choose(LyricsFontRole.ORIGINAL, Fixture.VARIABLE, 400f)
        fixture.choose(LyricsFontRole.TRANSLATION, Fixture.STATIC, null)
        val actual = session.store.read()
        val bytes = file.readBytes()
        fixture.editor.begin(LyricsFontRole.ORIGINAL, fixture.text)
        fixture.editor.previewWeight(650f, fixture.text)
        val scratch = File(file.absolutePath + ".tmp")
        assertTrue(scratch.mkdir())
        val blocker = File(scratch, "blocker").apply { writeText("actual controlled editor write failure") }
        fixture.editor.confirm(fixture.text)
        assertEquals(LyricsFontIssue.SAVE_FAILED, fixture.editor.state.value.issue)
        assertEquals(actual, fixture.editor.state.value.saved)
        assertEquals(400f, fixture.editor.state.value.original.configuredAxisWeight)
        assertEquals(650f, fixture.editor.state.value.retry?.selection?.requestedWeight)
        assertArrayEquals(bytes, file.readBytes())
        assertTrue(blocker.delete())
        assertTrue(scratch.delete())
        fixture.editor.retrySave(fixture.text)
        val saved = session.store.read()
        assertEquals(actual.revision + 1L, saved.revision)
        assertEquals(650f, saved.original.requestedWeight)
        assertEquals(actual.translation, saved.translation)
        assertNull(fixture.editor.state.value.retry)
        fixture.assertOriginalsUnchanged()
    }

    @Test(timeout = 30000L)
    fun anErrorAfterDurableCasIsReadBackWithoutASecondEditorWrite() = runBlocking {
        val file = settingsFile()
        lateinit var controls: ControlledFontSettingsDataStore
        val session = openSettings(file) { ControlledFontSettingsDataStore(it).also { controls = it } }
        val fixture = FontSettingsEditorFixture(temporary.newFolder("committed-error"), session.store)
        fixture.choose(LyricsFontRole.ORIGINAL, Fixture.VARIABLE, 400f)
        fixture.choose(LyricsFontRole.TRANSLATION, Fixture.STATIC, null)
        val actual = session.store.read()
        val writes = controls.writeCalls.get()
        fixture.editor.begin(LyricsFontRole.ORIGINAL, fixture.text)
        fixture.editor.previewWeight(650f, fixture.text)
        controls.failAfterNextWrite.set(true)
        fixture.editor.confirm(fixture.text)
        assertEquals(writes + 1, controls.writeCalls.get())
        assertEquals(actual.revision + 1L, session.store.read().revision)
        assertEquals(session.store.read(), fixture.editor.state.value.saved)
        assertEquals(actual.translation, fixture.editor.state.value.saved?.translation)
        assertEquals(650f, fixture.editor.state.value.original.configuredAxisWeight)
        assertNull(fixture.editor.state.value.retry)
        assertNull(fixture.editor.state.value.issue)
        fixture.assertOriginalsUnchanged()
    }

    @Test(timeout = 30000L)
    fun cancellationBeforeRealCasRetainsTheProposalUntilAnExplicitFreshRetry() = runBlocking {
        val file = settingsFile()
        lateinit var controls: ControlledFontSettingsDataStore
        val session = openSettings(file) { ControlledFontSettingsDataStore(it).also { controls = it } }
        val fixture = FontSettingsEditorFixture(temporary.newFolder("cancel-before"), session.store)
        fixture.choose(LyricsFontRole.ORIGINAL, Fixture.VARIABLE, 400f)
        fixture.choose(LyricsFontRole.TRANSLATION, Fixture.STATIC, null)
        val actual = session.store.read()
        val bytes = file.readBytes()
        fixture.editor.begin(LyricsFontRole.ORIGINAL, fixture.text)
        fixture.editor.importFont(fixture.request(Fixture.VARIABLE), fixture.text)
        fixture.editor.previewWeight(650f, fixture.text)
        val proposal = checkNotNull(fixture.editor.state.value.draft?.selection?.asset)
        val gate = FontSettingsWriteGate()
        controls.holdNextWrite(gate)
        val caller = async { fixture.editor.confirm(fixture.text) }
        try {
            withTimeout(10000L) { gate.entered.await() }
            caller.cancel()
            assertFalse(caller.isCompleted)
            expectFontSettingsIssue(LyricsFontSettingsStorageIssue.BUSY) { session.store.read() }
        } finally { gate.release.complete(Unit) }
        withTimeout(10000L) { caller.join() }
        assertTrue(caller.isCancelled)
        assertNull(fixture.editor.state.value.saved)
        assertEquals(proposal, fixture.editor.state.value.retry?.selection?.asset)
        assertTrue(fixture.assetFile(proposal).isFile)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(actual, session.store.read())
        fixture.editor.retrySave(fixture.text)
        val committed = session.store.read()
        assertEquals(actual.revision + 1L, committed.revision)
        assertEquals(proposal, committed.original.asset)
        assertEquals(650f, committed.original.requestedWeight)
        assertEquals(actual.translation, committed.translation)
        assertTrue(fixture.assetFile(proposal).isFile)
        assertNull(fixture.editor.state.value.retry)
        fixture.assertOriginalsUnchanged()
    }

    @Test(timeout = 30000L)
    fun cancellationAfterRealCasRetainsItsCommittedAssetAndRetryOnlyReadsBack() = runBlocking {
        val file = settingsFile()
        lateinit var controls: ControlledFontSettingsDataStore
        val session = openSettings(file) { ControlledFontSettingsDataStore(it).also { controls = it } }
        val fixture = FontSettingsEditorFixture(temporary.newFolder("cancel-after"), session.store)
        fixture.choose(LyricsFontRole.ORIGINAL, Fixture.VARIABLE, 400f)
        fixture.choose(LyricsFontRole.TRANSLATION, Fixture.STATIC, null)
        val actual = session.store.read()
        fixture.editor.begin(LyricsFontRole.ORIGINAL, fixture.text)
        fixture.editor.importFont(fixture.request(Fixture.VARIABLE), fixture.text)
        fixture.editor.previewWeight(650f, fixture.text)
        val proposal = checkNotNull(fixture.editor.state.value.draft?.selection?.asset)
        val gate = FontSettingsWriteGate(afterCommit = true)
        controls.holdNextWrite(gate)
        val caller = async { fixture.editor.confirm(fixture.text) }
        try {
            withTimeout(10000L) { gate.entered.await() }
            caller.cancel()
            assertFalse(caller.isCompleted)
            expectFontSettingsIssue(LyricsFontSettingsStorageIssue.BUSY) { session.store.read() }
        } finally { gate.release.complete(Unit) }
        withTimeout(10000L) { caller.join() }
        assertTrue(caller.isCancelled)
        assertNull(fixture.editor.state.value.saved)
        assertEquals(proposal, fixture.editor.state.value.retry?.selection?.asset)
        assertTrue(fixture.assetFile(proposal).isFile)
        val writes = controls.writeCalls.get()
        assertEquals(actual.revision + 1L, session.store.read().revision)
        fixture.editor.retrySave(fixture.text)
        assertEquals(writes, controls.writeCalls.get())
        assertEquals(actual.revision + 1L, fixture.editor.state.value.saved?.revision)
        assertEquals(proposal, fixture.editor.state.value.saved?.original?.asset)
        assertEquals(actual.translation, fixture.editor.state.value.saved?.translation)
        assertTrue(fixture.assetFile(proposal).isFile)
        assertNull(fixture.editor.state.value.retry)
        fixture.assertOriginalsUnchanged()
    }

    @Test(timeout = 30000L)
    fun aRealConflictingWriterKeepsItsOtherRoleDuringTheEditorsExplicitRetry() = runBlocking {
        val file = settingsFile()
        val session = openSettings(file)
        val fixture = FontSettingsEditorFixture(temporary.newFolder("conflict"), session.store)
        fixture.choose(LyricsFontRole.ORIGINAL, Fixture.VARIABLE, 400f)
        fixture.choose(LyricsFontRole.TRANSLATION, Fixture.VARIABLE, 600f)
        fixture.editor.begin(LyricsFontRole.ORIGINAL, fixture.text)
        fixture.editor.previewWeight(650f, fixture.text)
        val actual = session.store.read()
        val other = DataStoreLyricsFontSettingsStore(session.dataStore)
        val otherReplacement = actual.copy(translation = actual.translation.copy(requestedWeight = 250f))
        val otherSaved = (other.compareAndSet(actual.revision, otherReplacement) as LyricsFontSaveResult.Saved).settings
        fixture.editor.confirm(fixture.text)
        assertEquals(LyricsFontIssue.SETTINGS_CONFLICT, fixture.editor.state.value.issue)
        assertEquals(otherSaved, fixture.editor.state.value.saved)
        assertEquals(650f, fixture.editor.state.value.retry?.selection?.requestedWeight)
        assertEquals(250f, fixture.editor.state.value.translation.configuredAxisWeight)
        fixture.editor.retrySave(fixture.text)
        val saved = session.store.read()
        assertEquals(otherSaved.revision + 1L, saved.revision)
        assertEquals(otherSaved.translation, saved.translation)
        assertEquals(650f, saved.original.requestedWeight)
        assertNull(fixture.editor.state.value.retry)
        fixture.assertOriginalsUnchanged()
    }

    @Test(timeout = 30000L)
    fun unreadableStorageAfterACommittedErrorKeepsItsProposalAndRetriesWithoutDuplicatingTheCas() = runBlocking {
        val file = settingsFile()
        lateinit var controls: ControlledFontSettingsDataStore
        val session = openSettings(file) { ControlledFontSettingsDataStore(it).also { controls = it } }
        val fixture = FontSettingsEditorFixture(temporary.newFolder("unknown-after-write"), session.store)
        fixture.choose(LyricsFontRole.ORIGINAL, Fixture.VARIABLE, 400f)
        fixture.choose(LyricsFontRole.TRANSLATION, Fixture.STATIC, null)
        fixture.editor.begin(LyricsFontRole.ORIGINAL, fixture.text)
        fixture.editor.importFont(fixture.request(Fixture.VARIABLE), fixture.text)
        fixture.editor.previewWeight(650f, fixture.text)
        val proposal = checkNotNull(fixture.editor.state.value.draft?.selection?.asset)
        controls.failAfterNextWrite.set(true)
        controls.failReads.set(true)
        fixture.editor.confirm(fixture.text)
        assertNull(fixture.editor.state.value.saved)
        assertTrue(fixture.editor.state.value.original.usesSystemFont)
        assertTrue(fixture.editor.state.value.translation.usesSystemFont)
        assertEquals(LyricsFontIssue.SETTINGS_UNAVAILABLE, fixture.editor.state.value.issue)
        assertEquals(proposal, fixture.editor.state.value.retry?.selection?.asset)
        assertTrue(fixture.assetFile(proposal).isFile)
        val writes = controls.writeCalls.get()
        controls.failReads.set(false)
        fixture.editor.retrySave(fixture.text)
        assertEquals(writes, controls.writeCalls.get())
        assertEquals(proposal, session.store.read().original.asset)
        assertEquals(session.store.read(), fixture.editor.state.value.saved)
        assertNull(fixture.editor.state.value.retry)
        fixture.assertOriginalsUnchanged()
    }

    @Test(timeout = 30000L)
    fun aCancelledLateRestoreCannotPublishItsSnapshotOrOverlapANewerEditorRead() = runBlocking {
        val file = settingsFile()
        lateinit var controls: ControlledFontSettingsDataStore
        val session = openSettings(file) { ControlledFontSettingsDataStore(it).also { controls = it } }
        val fixture = FontSettingsEditorFixture(temporary.newFolder("late-restore"), session.store)
        fixture.choose(LyricsFontRole.ORIGINAL, Fixture.VARIABLE, 400f)
        val actual = session.store.read()
        val gate = FontSettingsReadGate(ignoreCancellation = true)
        controls.holdNextRead(gate)
        val restored = fixture.newEditor(session.store)
        val caller = async { restored.restore(fixture.text) }
        try {
            withTimeout(10000L) { gate.captured.await() }
            caller.cancel()
            assertFalse(caller.isCompleted)
            restored.restore(fixture.text)
            assertEquals(LyricsFontIssue.BUSY, restored.state.value.issue)
            assertNull(restored.state.value.saved)
        } finally { gate.release.complete(Unit) }
        withTimeout(10000L) { caller.cancelAndJoin() }
        assertNull(restored.state.value.saved)
        val replacement = actual.copy(original = actual.original.copy(requestedWeight = 650f))
        val saved = (session.store.compareAndSet(actual.revision, replacement) as LyricsFontSaveResult.Saved).settings
        restored.restore(fixture.text)
        assertEquals(saved, restored.state.value.saved)
        assertEquals(650f, restored.state.value.original.configuredAxisWeight)
        fixture.assertOriginalsUnchanged()
    }

    @Test(timeout = 30000L)
    fun aRealUnknownSchemaRestoresASafeUnavailableStateWithoutPruningSavedFontBytes() = runBlocking {
        val file = settingsFile()
        val session = openSettings(file)
        val fixture = FontSettingsEditorFixture(temporary.newFolder("unsupported-envelope"), session.store)
        fixture.choose(LyricsFontRole.ORIGINAL, Fixture.VARIABLE, 400f)
        fixture.choose(LyricsFontRole.TRANSLATION, Fixture.STATIC, null)
        val actual = session.store.read()
        session.close()
        writeSettingsFixture(file, LyricsFontSettingsCodec.encode(actual).toMutablePreferences().apply {
            this[intPreferencesKey("schema_version")] = 2
        })
        val bytes = file.readBytes()
        val reopened = openSettings(file)
        val restored = fixture.newEditor(reopened.store)
        restored.restore(fixture.text)
        assertNull(restored.state.value.saved)
        assertTrue(restored.state.value.original.usesSystemFont)
        assertTrue(restored.state.value.translation.usesSystemFont)
        assertFalse(restored.state.value.canConfirm)
        assertEquals(LyricsFontIssue.SETTINGS_UNAVAILABLE, restored.state.value.issue)
        assertArrayEquals(bytes, file.readBytes())
        actual.assetIds().forEach { id -> assertTrue(File(fixture.directory, "$id.font").isFile) }
        fixture.assertOriginalsUnchanged()
    }

    @Test(timeout = 30000L)
    fun cancellingAnEditorDuringScratchWriteRetainsItsProposalUntilTheCompletedWriteIsReadBack() = runBlocking {
        val file = settingsFile()
        val storage = FontSettingsPhysicalStorage(file)
        val session = openStorageSettings(storage)
        val fixture = FontSettingsEditorFixture(temporary.newFolder("cancel-physical"), session.store)
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
        val gate = FontSettingsPhysicalWriteGate(afterScratchWrite = true)
        storage.holdNextWrite(gate)
        val caller = async { fixture.editor.confirm(fixture.text) }
        try {
            val captured = withTimeout(10000L) { gate.entered.await() }
            assertEquals(proposal, LyricsFontSettingsCodec.decode(captured).original.asset)
            assertTrue(File(file.absolutePath + ".tmp").length() > 0L)
            caller.cancel()
            assertFalse(caller.isCompleted)
            expectFontSettingsIssue(LyricsFontSettingsStorageIssue.BUSY) { session.store.read() }
            expectFontSettingsIssue(LyricsFontSettingsStorageIssue.BUSY) { session.store.compareAndSet(actual.revision, actual) }
            fixture.editor.cancel(fixture.text)
            assertEquals(LyricsFontIssue.BUSY, fixture.editor.state.value.issue)
            assertEquals(proposal, fixture.editor.state.value.draft?.selection?.asset)
            val restored = fixture.newEditor(session.store)
            restored.restore(fixture.text)
            assertNull(restored.state.value.saved)
            assertEquals(LyricsFontIssue.SETTINGS_UNAVAILABLE, restored.state.value.issue)
            assertTrue(fixture.assetFile(proposal).isFile)
            actual.assetIds().forEach { id -> assertTrue(File(fixture.directory, "$id.font").isFile) }
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(writes, storage.committedWrites.get())
        } finally { gate.release.complete(Unit) }
        withTimeout(10000L) { caller.join() }
        assertTrue(caller.isCancelled)
        assertNull(fixture.editor.state.value.saved)
        assertEquals(proposal, fixture.editor.state.value.retry?.selection?.asset)
        assertTrue(fixture.assetFile(proposal).isFile)
        val committed = actual.withSelection(LyricsFontRole.ORIGINAL, draft.selection).copy(revision = actual.revision + 1L)
        assertEquals(committed, session.store.read())
        assertEquals(writes + 1, storage.committedWrites.get())
        fixture.editor.retrySave(fixture.text)
        assertEquals(committed, fixture.editor.state.value.saved)
        assertNull(fixture.editor.state.value.retry)
        assertEquals(writes + 1, storage.committedWrites.get())
        fixture.editor.cancel(fixture.text)
        assertEquals(committed, fixture.editor.state.value.saved)
        assertTrue(fixture.assetFile(proposal).isFile)
        assertTrue(fixture.assetFile(checkNotNull(actual.translation.asset)).isFile)
        session.close()
        val reopened = openSettings(file)
        assertEquals(committed, reopened.store.read())
        val restored = fixture.newEditor(reopened.store)
        restored.restore(fixture.text)
        assertEquals(committed, restored.state.value.saved)
        assertTrue(fixture.assetFile(proposal).isFile)
        assertTrue(fixture.assetFile(checkNotNull(actual.translation.asset)).isFile)
        fixture.assertOriginalsUnchanged()
    }
}
