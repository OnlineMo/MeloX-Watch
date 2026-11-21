package io.github.onlinemo.meloxwatch.ui.themes

import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.themes.BackgroundCrop
import io.github.onlinemo.meloxwatch.data.themes.BytesThemeSource
import io.github.onlinemo.meloxwatch.data.themes.LocalBackgroundImages
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundException
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundIssue
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundOptions
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundSaveResult
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundSettings
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundSnapshot
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundStore
import io.github.onlinemo.meloxwatch.data.themes.ThemeBackgroundMode
import io.github.onlinemo.meloxwatch.data.themes.ThemeBitmapOwner
import io.github.onlinemo.meloxwatch.data.themes.ThemeTestFixtures
import io.github.onlinemo.meloxwatch.data.themes.backgroundImportRequest
import io.github.onlinemo.meloxwatch.data.themes.managedBackgroundSources
import io.github.onlinemo.meloxwatch.data.themes.retireBackgroundTestScope
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaybackBackgroundEditorTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun realImagePreviewCropAndMaskSaveOnlyOnConfirmAndResetKeepsTheAcceptedCopy() = runBlocking {
        withTimeout(15_000) {
            val fixture = fixture()
            val scope = CoroutineScope(Job() + Dispatchers.Default)
            val editor = PlaybackBackgroundEditor(scope, fixture.io, fixture.store)
            try {
                editor.open()
                awaitEditor(editor) { it.saved.settings?.revision == 0L }
                assertTrue(editor.importImage(backgroundImportRequest()))
                val preview = checkNotNull(awaitEditor(editor) { it.preview != null }.preview)
                assertTrue(editor.changeCrop(BackgroundCrop(2f, 0f, 1f)))
                assertTrue(editor.changeBlur(8))
                assertTrue(editor.changeMask(80))
                val draft = editor.state.value.draft
                assertEquals(PlaybackBackgroundSettings(), fixture.store.read().settings)
                assertTrue(editor.state.value.canConfirm)
                assertTrue(editor.confirm())
                val saved = awaitEditor(editor) { it.saved.settings?.revision == 1L }
                assertEquals(draft, saved.saved.effective)
                assertEquals(draft, saved.draft)
                assertNull(saved.preview)
                assertEquals(1L, saved.customImageKey()?.settingsRevision)
                assertTrue(fixture.source.bytes.contentEquals(fixture.original))
                assertTrue(editor.changeMask(20))
                editor.cancel()
                assertEquals(draft, editor.state.value.draft)
                assertEquals(draft, fixture.store.read().effective)
                assertTrue(editor.resetDefault())
                assertEquals(ThemeBackgroundMode.DEFAULT, editor.state.value.draft.mode)
                assertEquals(preview.image, editor.state.value.draft.customImage)
                assertTrue(editor.confirm())
                val reset = awaitEditor(editor) { it.saved.settings?.revision == 2L }
                assertEquals(preview.image, reset.saved.effective.customImage)
                assertTrue(File(fixture.directory, preview.image.id + ".image").isFile)
                val restarted = LocalBackgroundImages(fixture.directory, fixture.io, fixture.owner, fixture.source,
                    availableBytes = { ThemeTestFixtures.ROOM })
                assertEquals(reset.saved.settings, restarted.read().settings)
                assertTrue(fixture.source.bytes.contentEquals(fixture.original))
            } finally { editor.close(); retireBackgroundTestScope(fixture.io, scope, owner = fixture.owner) }
        }
    }

    @Test fun cancelOfAnUnsavedSelectionKeepsTheRealFormerRecordAndRetiresOnlyItsPrivatePreview() = runBlocking {
        withTimeout(10_000) {
            val fixture = fixture()
            val scope = CoroutineScope(Job() + Dispatchers.Default)
            val editor = PlaybackBackgroundEditor(scope, fixture.io, fixture.store)
            try {
                editor.open()
                awaitEditor(editor) { it.saved.settings != null }
                editor.importImage(backgroundImportRequest())
                val preview = checkNotNull(awaitEditor(editor) { it.preview != null }.preview)
                assertEquals(ThemeBackgroundMode.CUSTOM_IMAGE, editor.state.value.draft.mode)
                editor.cancel()
                assertNull(editor.state.value.preview)
                assertEquals(PlaybackBackgroundOptions(), editor.state.value.draft)
                assertNull(fixture.store.preview.value)
                assertTrue(File(fixture.directory, preview.image.id + ".image").isFile)
                assertEquals(PlaybackBackgroundSettings(), fixture.store.read().settings)
                assertFalse(File(fixture.directory, preview.image.id + ".image").exists())
                assertTrue(fixture.source.bytes.contentEquals(fixture.original))
            } finally { editor.close(); retireBackgroundTestScope(fixture.io, scope, owner = fixture.owner) }
        }
    }

    @Test fun realCasConflictRetainsDraftAndCandidateUntilAnExplicitRetryUsesTheNewRevision() = runBlocking {
        withTimeout(15_000) {
            val fixture = fixture()
            val scope = CoroutineScope(Job() + Dispatchers.Default)
            val editor = PlaybackBackgroundEditor(scope, fixture.io, fixture.store)
            try {
                editor.open()
                awaitEditor(editor) { it.saved.settings != null }
                editor.importImage(backgroundImportRequest())
                val preview = checkNotNull(awaitEditor(editor) { it.preview != null }.preview)
                editor.changeMask(70)
                val draft = editor.state.value.draft
                val other = PlaybackBackgroundOptions(mode = ThemeBackgroundMode.BLACK)
                fixture.store.compareAndSet(0, other)
                assertTrue(editor.confirm())
                val conflict = awaitEditor(editor) { it.issue == PlaybackBackgroundIssue.SETTINGS_CONFLICT }
                assertEquals(PlaybackBackgroundSettings(1, other), conflict.saved.settings)
                assertEquals(draft, conflict.draft)
                assertEquals(preview, conflict.preview)
                assertTrue(conflict.canConfirm)
                assertTrue(editor.confirm())
                val saved = awaitEditor(editor) { it.saved.settings?.revision == 2L }
                assertEquals(draft, saved.saved.effective)
                assertNull(saved.preview)
                assertEquals(2L, saved.customImageKey()?.settingsRevision)
            } finally { editor.close(); retireBackgroundTestScope(fixture.io, scope, owner = fixture.owner) }
        }
    }

    @Test fun failedWriteReadsActualSettingsAndKeepsTheNativeImageCandidateForRetry() = runBlocking {
        withTimeout(15_000) {
            val fixture = fixture()
            val store = SaveFaultStore(fixture.store, loseAfterCommit = false)
            val scope = CoroutineScope(Job() + Dispatchers.Default)
            val editor = PlaybackBackgroundEditor(scope, fixture.io, store)
            try {
                editor.open()
                awaitEditor(editor) { it.saved.settings != null }
                editor.importImage(backgroundImportRequest())
                val preview = checkNotNull(awaitEditor(editor) { it.preview != null }.preview)
                editor.changeCrop(BackgroundCrop(3f, 1f, 0f))
                val draft = editor.state.value.draft
                editor.confirm()
                val failed = awaitEditor(editor) { it.issue == PlaybackBackgroundIssue.SAVE_FAILED }
                assertEquals(PlaybackBackgroundSettings(), failed.saved.settings)
                assertEquals(draft, failed.draft)
                assertEquals(preview, failed.preview)
                assertEquals(1, store.saves.get())
                assertTrue(store.reads.get() >= 2)
                assertTrue(failed.canConfirm)
                editor.confirm()
                val saved = awaitEditor(editor) { it.saved.settings?.revision == 1L }
                assertEquals(draft, saved.saved.effective)
                assertEquals(2, store.saves.get())
            } finally { editor.close(); retireBackgroundTestScope(fixture.io, scope, owner = fixture.owner) }
        }
    }

    @Test fun responseLostAfterActualCommitIsRecognizedFromExactBytesWithoutASecondCas() = runBlocking {
        withTimeout(15_000) {
            val fixture = fixture()
            val store = SaveFaultStore(fixture.store, loseAfterCommit = true)
            val scope = CoroutineScope(Job() + Dispatchers.Default)
            val editor = PlaybackBackgroundEditor(scope, fixture.io, store)
            try {
                editor.open()
                awaitEditor(editor) { it.saved.settings != null }
                editor.importImage(backgroundImportRequest())
                awaitEditor(editor) { it.preview != null }
                editor.changeMask(60)
                val draft = editor.state.value.draft
                editor.confirm()
                val actual = awaitEditor(editor) { it.saved.settings?.revision == 1L }
                assertEquals(draft, actual.saved.effective)
                assertEquals(draft, actual.draft)
                assertNull(actual.preview)
                assertNull(actual.issue)
                assertFalse(actual.requiresRead)
                assertFalse(editor.confirm())
                assertEquals(1, store.saves.get())
                assertTrue(store.reads.get() >= 2)
                assertEquals(PlaybackBackgroundSettings(1, draft), fixture.store.read().settings)
            } finally { editor.close(); retireBackgroundTestScope(fixture.io, scope, owner = fixture.owner) }
        }
    }

    @Test fun unreadableFailureReadbackCannotTurnTheSafeDefaultIntoASuccessfulSave() = runBlocking {
        withTimeout(15_000) {
            val fixture = fixture()
            val store = SaveFaultStore(fixture.store, loseAfterCommit = false).also { it.failFailureRead = true }
            val scope = CoroutineScope(Job() + Dispatchers.Default)
            val editor = PlaybackBackgroundEditor(scope, fixture.io, store)
            try {
                editor.open()
                awaitEditor(editor) { it.saved.settings != null }
                editor.importImage(backgroundImportRequest())
                val preview = checkNotNull(awaitEditor(editor) { it.preview != null }.preview)
                val draft = editor.state.value.draft
                editor.confirm()
                val unreadable = awaitEditor(editor) { it.issue == PlaybackBackgroundIssue.STORAGE_UNAVAILABLE }
                assertNull(unreadable.saved.settings)
                assertTrue(unreadable.requiresRead)
                assertEquals(draft, unreadable.draft)
                assertEquals(preview, unreadable.preview)
                assertFalse(unreadable.canConfirm)
                assertFalse(editor.confirm())
                store.failFailureRead = false
                editor.retryRead()
                val recovered = awaitEditor(editor) { it.saved.settings != null }
                assertEquals(PlaybackBackgroundSettings(), recovered.saved.settings)
                assertEquals(draft, recovered.draft)
                assertTrue(recovered.canConfirm)
                editor.confirm()
                awaitEditor(editor) { it.saved.settings?.revision == 1L }
            } finally { editor.close(); retireBackgroundTestScope(fixture.io, scope, owner = fixture.owner) }
        }
    }

    @Test(timeout = 30_000L) fun hidingAfterPhysicalSaveBeforeEditorDeliveryReconcilesOnReopenWithoutHiddenWork() = runBlocking {
        withTimeout(15_000) {
            val dispatcher = BackgroundPhysicalDispatcher()
            val io = LocalProviderIo(dispatcher)
            val fixture = fixture(io)
            val scheduler = TestCoroutineScheduler()
            val scope = CoroutineScope(Job() + StandardTestDispatcher(scheduler))
            val editor = PlaybackBackgroundEditor(scope, io, fixture.store)
            val gates = ArrayList<BackgroundWorkerGate>()
            try {
                val read = dispatcher.gateNext().also(gates::add)
                editor.open()
                scheduler.runCurrent()
                finishEditorWorker(read)
                scheduler.runCurrent()
                assertEquals(0L, editor.state.value.saved.settings?.revision)
                assertTrue(editor.changeMode(ThemeBackgroundMode.BLACK))
                val saved = dispatcher.gateNext().also(gates::add)
                assertTrue(editor.confirm())
                scheduler.runCurrent()
                finishEditorWorker(saved)
                assertEquals(1L, fixture.store.state.value.settings?.revision)
                assertEquals(0L, editor.state.value.saved.settings?.revision)
                editor.setVisible(false)
                scheduler.runCurrent()
                scheduler.advanceTimeBy(60_000)
                scheduler.runCurrent()
                assertEquals(2, dispatcher.submitted.get())
                assertTrue(editor.state.value.requiresRead)
                val reopen = dispatcher.gateNext().also(gates::add)
                editor.open()
                scheduler.runCurrent()
                finishEditorWorker(reopen)
                scheduler.runCurrent()
                assertEquals(1L, editor.state.value.saved.settings?.revision)
                assertEquals(ThemeBackgroundMode.BLACK, editor.state.value.draft.mode)
                assertNull(editor.state.value.issue)
                assertFalse(editor.state.value.requiresRead)
                assertFalse(editor.state.value.dirty)
                assertEquals(3, dispatcher.submitted.get())
            } finally {
                gates.forEach { it.release.countDown() }
                editor.close()
                retireBackgroundTestScope(io, scope, scheduler, fixture.owner, dispatcher)
            }
        }
    }

    @Test(timeout = 30_000L) fun hidingAnUndeliveredNativeImagePreviewRetiresItAndNextReadPrunesItsPrivateCopy() = runBlocking {
        withTimeout(15_000) {
            val dispatcher = BackgroundPhysicalDispatcher()
            val io = LocalProviderIo(dispatcher)
            val fixture = fixture(io)
            val scheduler = TestCoroutineScheduler()
            val scope = CoroutineScope(Job() + StandardTestDispatcher(scheduler))
            val editor = PlaybackBackgroundEditor(scope, io, fixture.store)
            val gates = ArrayList<BackgroundWorkerGate>()
            try {
                val read = dispatcher.gateNext().also(gates::add)
                editor.open()
                scheduler.runCurrent()
                finishEditorWorker(read)
                scheduler.runCurrent()
                val imported = dispatcher.gateNext().also(gates::add)
                assertTrue(editor.importImage(backgroundImportRequest()))
                scheduler.runCurrent()
                finishEditorWorker(imported)
                assertEquals(1, managedBackgroundSources(fixture.directory).size)
                assertNull(editor.state.value.preview)
                editor.setVisible(false)
                scheduler.runCurrent()
                assertNull(fixture.store.preview.value)
                assertEquals(2, dispatcher.submitted.get())
                val reopen = dispatcher.gateNext().also(gates::add)
                editor.open()
                scheduler.runCurrent()
                finishEditorWorker(reopen)
                scheduler.runCurrent()
                assertEquals(PlaybackBackgroundSettings(), editor.state.value.saved.settings)
                assertEquals(0, managedBackgroundSources(fixture.directory).size)
                assertEquals(1, fixture.source.inputCloses.get())
                assertEquals(1, fixture.source.descriptorCloses.get())
                assertTrue(fixture.source.bytes.contentEquals(fixture.original))
                assertEquals(0L, fixture.owner.usage().occupiedBytes)
            } finally {
                gates.forEach { it.release.countDown() }
                editor.close()
                retireBackgroundTestScope(io, scope, scheduler, fixture.owner, dispatcher)
            }
        }
    }

    @Test(timeout = 30_000L) fun cancellingBeforeCasDispatchReopensTheUnchangedRecordWithoutASaveFailure() = runBlocking {
        withTimeout(15_000) {
            val dispatcher = BackgroundPhysicalDispatcher()
            val io = LocalProviderIo(dispatcher)
            val fixture = fixture(io)
            val observed = CountingBackgroundSaveStore(fixture.store)
            val scheduler = TestCoroutineScheduler()
            val scope = CoroutineScope(Job() + StandardTestDispatcher(scheduler))
            val editor = PlaybackBackgroundEditor(scope, io, observed)
            val gates = ArrayList<BackgroundWorkerGate>()
            try {
                val initial = dispatcher.gateNext().also(gates::add)
                editor.open()
                scheduler.runCurrent()
                finishEditorWorker(initial)
                scheduler.runCurrent()
                assertEquals(PlaybackBackgroundSettings(), editor.state.value.saved.settings)
                assertTrue(editor.changeMode(ThemeBackgroundMode.BLACK))
                assertTrue(editor.confirm())
                editor.cancel()
                scheduler.runCurrent()
                assertEquals(0, observed.saves.get())
                assertEquals(1, dispatcher.submitted.get())
                assertEquals(PlaybackBackgroundSettings(), fixture.store.state.value.settings)
                assertTrue(editor.state.value.requiresRead)
                val reopen = dispatcher.gateNext().also(gates::add)
                editor.open()
                scheduler.runCurrent()
                finishEditorWorker(reopen)
                scheduler.runCurrent()
                val actual = editor.state.value
                assertEquals(PlaybackBackgroundSettings(), actual.saved.settings)
                assertEquals(PlaybackBackgroundOptions(), actual.draft)
                assertNull(actual.issue)
                assertFalse(actual.requiresRead)
                assertFalse(actual.dirty)
                assertFalse(editor.confirm())
                assertEquals(0, observed.saves.get())
                assertEquals(2, dispatcher.submitted.get())
                assertFalse(File(fixture.directory, "settings.bin").exists())
            } finally {
                gates.forEach { it.release.countDown() }
                editor.close()
                retireBackgroundTestScope(io, scope, scheduler, fixture.owner, dispatcher)
            }
        }
    }

    @Test(timeout = 30_000L) fun cancellingAfterPhysicalCasBeforeDeliveryRecognizesTheExactCommitAndKeepsItsImage() = runBlocking {
        withTimeout(15_000) {
            val dispatcher = BackgroundPhysicalDispatcher()
            val io = LocalProviderIo(dispatcher)
            val fixture = fixture(io)
            val observed = CountingBackgroundSaveStore(fixture.store)
            val scheduler = TestCoroutineScheduler()
            val scope = CoroutineScope(Job() + StandardTestDispatcher(scheduler))
            val editor = PlaybackBackgroundEditor(scope, io, observed)
            val gates = ArrayList<BackgroundWorkerGate>()
            try {
                val initial = dispatcher.gateNext().also(gates::add)
                editor.open()
                scheduler.runCurrent()
                finishEditorWorker(initial)
                scheduler.runCurrent()
                val imported = dispatcher.gateNext().also(gates::add)
                assertTrue(editor.importImage(backgroundImportRequest()))
                scheduler.runCurrent()
                finishEditorWorker(imported)
                scheduler.runCurrent()
                val preview = checkNotNull(editor.state.value.preview)
                assertTrue(editor.changeMask(60))
                assertTrue(editor.changeBlur(8))
                val requested = editor.state.value.draft
                val saved = dispatcher.gateNext().also(gates::add)
                assertTrue(editor.confirm())
                scheduler.runCurrent()
                awaitBackgroundWorker(saved.entered)
                assertEquals(1, observed.saves.get())
                assertEquals(0L, fixture.store.state.value.settings?.revision)
                saved.release.countDown()
                awaitBackgroundWorker(saved.returned)
                saved.checkFailure()
                assertEquals(PlaybackBackgroundSettings(1, requested), fixture.store.state.value.settings)
                assertEquals(0L, editor.state.value.saved.settings?.revision)
                val record = File(fixture.directory, "settings.bin").readBytes()
                editor.cancel()
                scheduler.runCurrent()
                assertEquals(3, dispatcher.submitted.get())
                assertTrue(editor.state.value.requiresRead)
                assertNull(editor.state.value.preview)
                val reopen = dispatcher.gateNext().also(gates::add)
                editor.open()
                scheduler.runCurrent()
                finishEditorWorker(reopen)
                scheduler.runCurrent()
                val actual = editor.state.value
                assertEquals(PlaybackBackgroundSettings(1, requested), actual.saved.settings)
                assertEquals(requested, actual.draft)
                assertEquals(1L, actual.customImageKey()?.settingsRevision)
                assertNull(actual.issue)
                assertFalse(actual.requiresRead)
                assertFalse(actual.dirty)
                assertFalse(editor.confirm())
                assertEquals(1, observed.saves.get())
                assertEquals(4, dispatcher.submitted.get())
                assertTrue(File(fixture.directory, "settings.bin").readBytes().contentEquals(record))
                assertTrue(File(fixture.directory, preview.image.id + ".image").readBytes().contentEquals(fixture.original))
                assertTrue(fixture.source.bytes.contentEquals(fixture.original))
                assertEquals(1, fixture.source.opens.get())
                assertEquals(0L, fixture.owner.usage().occupiedBytes)
            } finally {
                gates.forEach { it.release.countDown() }
                editor.close()
                retireBackgroundTestScope(io, scope, scheduler, fixture.owner, dispatcher)
            }
        }
    }

    @Test(timeout = 30_000L) fun aCancelledPendingSaveDoesNotClaimDifferentOptionsOrALaterRevisionAsItsCommit() = runBlocking {
        withTimeout(15_000) {
            for (laterRevision in listOf(false, true)) {
                val dispatcher = BackgroundPhysicalDispatcher()
                val io = LocalProviderIo(dispatcher)
                val fixture = fixture(io)
                val observed = CountingBackgroundSaveStore(fixture.store)
                val scheduler = TestCoroutineScheduler()
                val scope = CoroutineScope(Job() + StandardTestDispatcher(scheduler))
                val editor = PlaybackBackgroundEditor(scope, io, observed)
                val gates = ArrayList<BackgroundWorkerGate>()
                try {
                    val initial = dispatcher.gateNext().also(gates::add)
                    editor.open()
                    scheduler.runCurrent()
                    finishEditorWorker(initial)
                    scheduler.runCurrent()
                    assertTrue(editor.changeMode(ThemeBackgroundMode.BLACK))
                    val requested = editor.state.value.draft
                    assertTrue(editor.confirm())
                    val other = PlaybackBackgroundOptions(darkMaskPercent = 20)
                    fixture.store.compareAndSet(0, other)
                    if (laterRevision) fixture.store.compareAndSet(1, requested)
                    val durable = checkNotNull(fixture.store.state.value.settings)
                    val record = File(fixture.directory, "settings.bin").readBytes()
                    editor.cancel()
                    scheduler.runCurrent()
                    assertEquals(0, observed.saves.get())
                    val reopen = dispatcher.gateNext().also(gates::add)
                    editor.open()
                    scheduler.runCurrent()
                    finishEditorWorker(reopen)
                    scheduler.runCurrent()
                    val actual = editor.state.value
                    assertEquals(durable, actual.saved.settings)
                    assertEquals(durable.options, actual.draft)
                    assertEquals(PlaybackBackgroundIssue.SETTINGS_CONFLICT, actual.issue)
                    assertFalse(actual.requiresRead)
                    assertFalse(actual.dirty)
                    assertFalse(editor.confirm())
                    assertEquals(0, observed.saves.get())
                    assertTrue(File(fixture.directory, "settings.bin").readBytes().contentEquals(record))
                } finally {
                    gates.forEach { it.release.countDown() }
                    editor.close()
                    retireBackgroundTestScope(io, scope, scheduler, fixture.owner, dispatcher)
                }
            }
        }
    }

    private fun fixture(io: LocalProviderIo = LocalProviderIo()): EditorFixture {
        val directory = temporary.newFolder()
        val original = ThemeTestFixtures.png()
        val source = BytesThemeSource(original)
        val owner = ThemeBitmapOwner()
        val store = LocalBackgroundImages(directory, io, owner, source, availableBytes = { ThemeTestFixtures.ROOM })
        return EditorFixture(directory, original, source, io, owner, store)
    }

    private data class EditorFixture(val directory: File, val original: ByteArray, val source: BytesThemeSource,
        val io: LocalProviderIo, val owner: ThemeBitmapOwner, val store: LocalBackgroundImages)
}

private suspend fun awaitEditor(editor: PlaybackBackgroundEditor, condition: (PlaybackBackgroundEditorState) -> Boolean): PlaybackBackgroundEditorState =
    withTimeout(5_000) { editor.state.first { it.visible && !it.busy && condition(it) } }

private suspend fun finishEditorWorker(gate: BackgroundWorkerGate) {
    awaitBackgroundWorker(gate.entered)
    gate.release.countDown()
    awaitBackgroundWorker(gate.returned)
    gate.checkFailure()
}

/** Fault delivery surrounds the actual private-file CAS/read; it does not replace durable records. */
private class SaveFaultStore(private val delegate: PlaybackBackgroundStore, private val loseAfterCommit: Boolean) : PlaybackBackgroundStore by delegate {
    val reads = AtomicInteger()
    val saves = AtomicInteger()
    @Volatile var failFailureRead = false
    override suspend fun read(): PlaybackBackgroundSnapshot {
        reads.incrementAndGet()
        if (saves.get() != 0 && failFailureRead) throw PlaybackBackgroundException(PlaybackBackgroundIssue.STORAGE_UNAVAILABLE)
        return delegate.read()
    }
    override suspend fun compareAndSet(expectedRevision: Long, replacement: PlaybackBackgroundOptions, previewToken: String?): PlaybackBackgroundSaveResult {
        if (saves.incrementAndGet() == 1) {
            if (loseAfterCommit) delegate.compareAndSet(expectedRevision, replacement, previewToken)
            throw PlaybackBackgroundException(PlaybackBackgroundIssue.SAVE_FAILED)
        }
        return delegate.compareAndSet(expectedRevision, replacement, previewToken)
    }
}

private class CountingBackgroundSaveStore(private val delegate: PlaybackBackgroundStore) : PlaybackBackgroundStore by delegate {
    val saves = AtomicInteger()
    override suspend fun compareAndSet(expectedRevision: Long, replacement: PlaybackBackgroundOptions, previewToken: String?): PlaybackBackgroundSaveResult {
        saves.incrementAndGet()
        return delegate.compareAndSet(expectedRevision, replacement, previewToken)
    }
}
