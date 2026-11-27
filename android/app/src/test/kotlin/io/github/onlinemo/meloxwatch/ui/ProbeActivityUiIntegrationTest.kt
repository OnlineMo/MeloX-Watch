package io.github.onlinemo.meloxwatch.ui

import android.app.Activity
import android.content.Intent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.Player
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsEncodingChoice
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontRole
import io.github.onlinemo.meloxwatch.data.lyrics.active.LocalLyricsContext
import io.github.onlinemo.meloxwatch.data.lyrics.active.LyricsDocuments
import io.github.onlinemo.meloxwatch.data.lyrics.active.LyricsLoadAuthority
import io.github.onlinemo.meloxwatch.data.lyrics.active.PreparedLyricsAssociation
import io.github.onlinemo.meloxwatch.data.lyrics.load.LocalLyricsLoadFixture
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsDocumentInput
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsSiblingScope
import io.github.onlinemo.meloxwatch.data.lyrics.store.DisplayMutationGate
import io.github.onlinemo.meloxwatch.data.lyrics.store.ForwardingDisplayDataStore
import io.github.onlinemo.meloxwatch.data.lyrics.store.HeldDisplayMutationDataStore
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsAssociation
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayCodec
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayFileFixture
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayPhysicalStorage
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayPhysicalWriteGate
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayPreferences
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayStore
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayTestSession
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayWriteResult
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackCommitResult
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackPreferences
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackReadResult
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackStore
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackWriteFence
import io.github.onlinemo.meloxwatch.data.queue.QueueCommit
import io.github.onlinemo.meloxwatch.data.queue.QueuePageSource
import io.github.onlinemo.meloxwatch.data.queue.QueueReplacementRequest
import io.github.onlinemo.meloxwatch.data.queue.QueueStore
import io.github.onlinemo.meloxwatch.data.themes.AppearanceSettings
import io.github.onlinemo.meloxwatch.data.themes.AppearanceSnapshot
import io.github.onlinemo.meloxwatch.data.themes.BackgroundViewport
import io.github.onlinemo.meloxwatch.model.FrozenQueueView
import io.github.onlinemo.meloxwatch.model.QueueCheckpoint
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueMode
import io.github.onlinemo.meloxwatch.model.QueueMutationCandidate
import io.github.onlinemo.meloxwatch.model.QueueMutationRequest
import io.github.onlinemo.meloxwatch.model.QueueSeekBinding
import io.github.onlinemo.meloxwatch.model.QueueSeekRequest
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import io.github.onlinemo.meloxwatch.model.QueueWindow
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.ui.fonts.FontPickerFixture
import io.github.onlinemo.meloxwatch.ui.fonts.ProbeFontPickerConsumption
import io.github.onlinemo.meloxwatch.ui.fonts.WatchFontHostValue
import io.github.onlinemo.meloxwatch.ui.fonts.WatchFontRequest
import io.github.onlinemo.meloxwatch.ui.fonts.WatchFontWork
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsPageContent
import io.github.onlinemo.meloxwatch.ui.lyrics.entry.LyricsDocumentPurpose
import io.github.onlinemo.meloxwatch.ui.lyrics.entry.LyricsEntrySurface
import io.github.onlinemo.meloxwatch.ui.lyrics.entry.LyricsEntryViewModel
import io.github.onlinemo.meloxwatch.ui.lyrics.entry.LyricsMediaClock
import io.github.onlinemo.meloxwatch.ui.lyrics.entry.LyricsPlaybackPort
import io.github.onlinemo.meloxwatch.ui.lyrics.options.LyricsDisplayEditor
import io.github.onlinemo.meloxwatch.ui.lyrics.options.LyricsDisplayNotice
import io.github.onlinemo.meloxwatch.ui.themes.PlaybackBackgroundDisplay
import io.github.onlinemo.meloxwatch.ui.watch.WatchWindowVisibility
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.buffer
import okio.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Main's production callback owner → actual bindings/VM/Store. Definitions remain NOT_RUN. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
internal class ProbeActivityUiIntegrationTest : LyricsDisplayFileFixture() {
    @Test(timeout = 20_000L)
    fun routeDepartureKeepsTheCapturedSaveUntilRealScratchWriteAndAcknowledgmentHaveEnded() = runBlocking {
        withTimeout(7_000L) {
            for (afterScratch in listOf(false, true)) {
                val ack = DisplayMutationGate()
                val display = display(ack)
                val fixture = MainCallbacksFixture(this, display.vm)
                val activity = fixture.install(fixture.prepareActivity())
                val page = activity.page()
                val proposal = display.propose()
                val physical = LyricsDisplayPhysicalWriteGate(afterScratch)
                display.storage.holdNextWrite(physical)
                ack.arm()
                val saving = async { display.vm.save(page) }
                try {
                    physical.entered.await()
                    assertEquals(display.original, readDisplayFile(display.file))
                    fixture.route(ProbePage.PLAYBACK)
                    assertFalse(page.visibleNow)
                    display.assertHeld(saving, cancelled = true)
                    assertEquals(0, display.storage.exitedWrites.get())
                    currentCoroutineContext().ensureActive()
                    physical.release.complete(Unit)
                    ack.entered.await()
                    assertEquals(1, display.storage.committedWrites.get())
                    assertEquals(1, display.storage.exitedWrites.get())
                    assertEquals(proposal.copy(revision = proposal.revision + 1), readDisplayFile(display.file))
                    display.assertHeld(saving, cancelled = true)
                    fixture.route(ProbePage.LYRICS_SETTINGS)
                    val next = activity.page()
                    async { display.vm.save(next) }.await()
                    assertEquals(1, display.calls.callers.size)
                    ack.release.complete(Unit)
                    saving.join()
                    assertTrue(saving.isCancelled)
                    assertFalse(display.vm.editor.state.value.working)
                    assertNull(display.vm.editor.state.value.draft)
                    assertEquals(LyricsDisplayNotice.READ_REQUIRED, display.vm.editor.state.value.notice)
                    activity.callbacks.onUiStateChanged(fixture.state)
                    assertEquals(1, display.storage.committedWrites.get())
                    assertEquals(1, display.calls.callers.size)
                    assertTrue(next.visibleNow)
                } finally {
                    physical.release.complete(Unit)
                    ack.release.complete(Unit)
                    finish(saving)
                    fixture.close()
                }
            }
        }
    }

    @Test(timeout = 15_000L)
    fun aHiddenVisibleRoundTripCannotReleaseTheOldAckOrReplaySaveThroughAFreshPage() = runBlocking {
        withTimeout(5_000L) {
            val ack = DisplayMutationGate()
            val display = display(ack)
            val fixture = MainCallbacksFixture(this, display.vm)
            val activity = fixture.install(fixture.prepareActivity())
            val old = activity.page()
            val proposal = display.propose()
            ack.arm()
            val saving = async { display.vm.save(old) }
            try {
                ack.entered.await()
                activity.window(HIDDEN)
                activity.window(VISIBLE)
                assertFalse(old.visibleNow)
                val next = activity.page()
                assertTrue(next.visibleNow)
                display.assertHeld(saving, cancelled = true)
                async { display.vm.save(next) }.await()
                assertEquals(1, display.calls.callers.size)
                ack.release.complete(Unit)
                saving.join()
                activity.callbacks.onWindowVisibilityChanged()
                activity.callbacks.onUiStateChanged(fixture.state)
                assertEquals(proposal.copy(revision = proposal.revision + 1), readDisplayFile(display.file))
                assertEquals(1, display.storage.committedWrites.get())
                assertEquals(1, display.calls.callers.size)
                assertNull(display.vm.editor.state.value.draft)
                assertEquals(LyricsDisplayNotice.READ_REQUIRED, display.vm.editor.state.value.notice)
                currentCoroutineContext().ensureActive()
            } finally {
                ack.release.complete(Unit)
                finish(saving)
                fixture.close()
            }
        }
    }

    @Test(timeout = 15_000L)
    fun aRealCommitWithFailedAckKeepsMainOwnershipThroughItsVerifiedReadbackAcknowledgment() = runBlocking {
        withTimeout(5_000L) {
            val readback = DisplayReadbackGate()
            val display = display(readback = readback)
            val fixture = MainCallbacksFixture(this, display.vm)
            val activity = fixture.install(fixture.prepareActivity())
            val page = activity.page()
            val proposal = display.propose()
            val saving = async {
                withContext(CoroutineName(READBACK_CALLER)) { display.vm.save(page) }
            }
            try {
                readback.entered.await()
                assertEquals(1, display.storage.committedWrites.get())
                assertEquals(1, display.storage.exitedWrites.get())
                assertEquals(proposal.copy(revision = proposal.revision + 1), readDisplayFile(display.file))
                fixture.route(ProbePage.PLAYBACK)
                display.assertHeld(saving, cancelled = true)
                fixture.route(ProbePage.LYRICS_SETTINGS)
                val next = activity.page()
                assertTrue(next.visibleNow)
                async { display.vm.save(next) }.await()
                assertEquals(1, display.calls.callers.size)
                readback.release.complete(Unit)
                saving.join()
                assertTrue(saving.isCancelled)
                assertFalse(display.vm.editor.state.value.working)
                assertEquals(LyricsDisplayNotice.READ_REQUIRED, display.vm.editor.state.value.notice)
                assertNull(display.vm.editor.state.value.draft)
                assertEquals(proposal.copy(revision = proposal.revision + 1), readDisplayFile(display.file))
                assertEquals(1, display.calls.callers.size)
                assertEquals(1, display.storage.committedWrites.get())
                currentCoroutineContext().ensureActive()
            } finally {
                readback.release.complete(Unit)
                finish(saving)
                fixture.close()
            }
        }
    }

    @Test(timeout = 15_000L)
    fun registryReplacementRetiresTheOldSaveAndInitialBindCannotEnterTheFontRouteBeforeAssignment() = runBlocking {
        withTimeout(5_000L) {
            val ack = DisplayMutationGate()
            val display = display(ack)
            val fixture = MainCallbacksFixture(this, display.vm)
            val old = fixture.install(fixture.prepareActivity())
            val oldPage = old.page()
            display.propose()
            ack.arm()
            val saving = async { display.vm.save(oldPage) }
            try {
                ack.entered.await()
                // The retained state can change before the successor assigns its Activity cell.
                fixture.state = fixture.state.copy(page = ProbePage.LYRICS_FONTS)
                val next = fixture.prepareActivity()
                val returned = next.bindBeforeAssignment()
                assertFalse(checkNotNull(old.binding).isCurrent)
                assertNull(fixture.fonts.picker.currentFontsRouteToken(ProbePage.LYRICS_FONTS))
                display.assertHeld(saving, cancelled = true)
                assertEquals(0, fixture.fonts.files.imports.get())
                assertEquals(0, fixture.fonts.store.writes.get())
                next.assignAndAttach(returned)
                assertNotNull(fixture.fonts.picker.currentFontsRouteToken(ProbePage.LYRICS_FONTS))
                assertTrue(next.page().visibleNow)
                ack.release.complete(Unit)
                saving.join()
                assertTrue(saving.isCancelled)
                assertEquals(1, display.storage.committedWrites.get())
                currentCoroutineContext().ensureActive()
            } finally {
                ack.release.complete(Unit)
                finish(saving)
                fixture.close()
            }
        }
    }

    @Test(timeout = 15_000L)
    fun obsoleteActivityCallbacksCannotCancelASuccessorsCurrentSave() = runBlocking {
        withTimeout(5_000L) {
            val ack = DisplayMutationGate()
            val display = display(ack)
            val fixture = MainCallbacksFixture(this, display.vm)
            val old = fixture.install(fixture.prepareActivity())
            old.page()
            val next = fixture.install(fixture.prepareActivity())
            val nextPage = next.page()
            val proposal = display.propose()
            ack.arm()
            val saving = async { display.vm.save(nextPage) }
            try {
                ack.entered.await()
                old.callbacks.onUiStateChanged(fixture.state.copy(page = ProbePage.PLAYBACK))
                old.window(HIDDEN)
                old.callbacks.close()
                checkNotNull(old.binding).close()
                old.callbacks.close()
                display.assertHeld(saving, cancelled = false)
                assertTrue(nextPage.visibleNow)
                ack.release.complete(Unit)
                saving.await()
                assertFalse(saving.isCancelled)
                assertEquals(LyricsDisplayNotice.SAVED, display.vm.editor.state.value.notice)
                assertEquals(proposal.copy(revision = proposal.revision + 1), readDisplayFile(display.file))
                assertEquals(1, display.calls.callers.size)
                assertEquals(1, display.storage.committedWrites.get())
            } finally {
                ack.release.complete(Unit)
                finish(saving)
                fixture.close()
            }
        }
    }

    @Test(timeout = 20_000L)
    fun failingRouteOrWindowRetirementStillCancelsTheInvisibleDisplayChildAndPreservesTheFailure() = runBlocking {
        withTimeout(7_000L) {
            for (windowLoss in listOf(false, true)) {
                val ack = DisplayMutationGate()
                val display = display(ack)
                val fixture = MainCallbacksFixture(this, display.vm)
                val activity = fixture.install(fixture.prepareActivity())
                val page = activity.page()
                val failure = IllegalStateException("Controlled font memory retirement failure")
                var cancelledFont = 0
                page.installFont(ProbeFontSlot.THEME_UI, { throw failure }, { cancelledFont++ })!!
                display.propose()
                ack.arm()
                val saving = async { display.vm.save(page) }
                try {
                    ack.entered.await()
                    try {
                        if (windowLoss) activity.window(HIDDEN) else fixture.route(ProbePage.PLAYBACK)
                        throw AssertionError("The original retirement failure must be observable")
                    } catch (observed: IllegalStateException) { assertSame(failure, observed) }
                    assertFalse(page.visibleNow)
                    assertEquals(1, cancelledFont)
                    display.assertHeld(saving, cancelled = true)
                    currentCoroutineContext().ensureActive()
                    ack.release.complete(Unit)
                    saving.join()
                    assertTrue(saving.isCancelled)
                    assertFalse(display.vm.editor.state.value.working)
                    assertEquals(1, display.storage.committedWrites.get())
                } finally {
                    ack.release.complete(Unit)
                    finish(saving)
                    fixture.close()
                }
            }
        }
    }

    @Test(timeout = 15_000L)
    fun clearingTheRealViewModelClosesAdmissionUntilAFreshExplicitOwnerIsCreated() = runBlocking {
        withTimeout(5_000L) {
            val ack = DisplayMutationGate()
            val display = display(ack)
            val fixture = MainCallbacksFixture(this, display.vm)
            val activity = fixture.install(fixture.prepareActivity())
            val page = activity.page()
            val models = ViewModelStore()
            val retained = ViewModelProvider(models, viewModelFactory {
                initializer { display.vm }
            })[ProbeLyricsDisplayViewModel::class.java]
            assertSame(display.vm, retained)
            val proposal = display.propose()
            ack.arm()
            val saving = async { retained.save(page) }
            try {
                ack.entered.await()
                models.clear()
                display.assertHeld(saving, cancelled = true)
                ack.release.complete(Unit)
                saving.join()
                assertTrue(saving.isCancelled)
                assertFalse(display.vm.editor.state.value.working)
                display.session.ready()
                assertEquals(proposal, display.vm.editor.state.value.retry)
                assertFalse(display.vm.editor.begin())
                assertTrue(display.vm.editor.reviewRetry())
                assertEquals(LyricsDisplayNotice.SAVED, display.vm.editor.state.value.notice)
                assertNull(display.vm.editor.state.value.retry)
                assertTrue(display.vm.editor.begin())
                val draft = checkNotNull(display.vm.editor.state.value.draft)
                val explicit = draft.copy(textSizeSp = 26f)
                assertTrue(display.vm.editor.preview(explicit))
                retained.save(page)
                assertEquals(1, display.calls.callers.size)
                assertSame(explicit, display.vm.editor.state.value.draft)
                val fresh = ProbeLyricsDisplayViewModel(RuntimeEnvironment.getApplication(), display.vm.editor)
                fresh.save(page)
                assertEquals(2, display.calls.callers.size)
                assertEquals(2, display.storage.committedWrites.get())
                assertEquals(explicit.copy(revision = explicit.revision + 1), readDisplayFile(display.file))
                fresh.events.close()
                currentCoroutineContext().ensureActive()
            } finally {
                ack.release.complete(Unit)
                finish(saving)
                models.clear()
                fixture.close()
            }
        }
    }

    @Test(timeout = 15_000L)
    fun aRetainedDisplayDraftSurvivesWindowAndLocaleActivityReplacementWithoutWriting() = runBlocking {
        withTimeout(5_000L) {
            val display = display()
            val fixture = MainCallbacksFixture(this, display.vm)
            val old = fixture.install(fixture.prepareActivity())
            old.page()
            val proposal = display.propose()
            val originalBytes = display.file.readBytes()
            val qualifiers = RuntimeEnvironment.getQualifiers()
            try {
                old.window(HIDDEN)
                old.window(VISIBLE)
                old.page()
                assertSame(proposal, display.vm.editor.state.value.draft)
                RuntimeEnvironment.setQualifiers("zh-rCN")
                val next = fixture.install(fixture.prepareActivity())
                assertEquals("zh", RuntimeEnvironment.getApplication()
                    .resources.configuration.locales[0].language)
                assertTrue(next.page().visibleNow)
                old.callbacks.onUiStateChanged(fixture.state.copy(page = ProbePage.PLAYBACK))
                old.callbacks.close()
                assertSame(proposal, display.vm.editor.state.value.draft)
                assertEquals(display.original.revision, proposal.revision)
                assertEquals(0, display.calls.callers.size)
                assertEquals(0, display.storage.committedWrites.get())
                assertTrue(originalBytes.contentEquals(display.file.readBytes()))
                assertEquals(display.original, readDisplayFile(display.file))
            } finally {
                RuntimeEnvironment.setQualifiers(qualifiers)
                fixture.close()
            }
        }
    }

    @Test(timeout = 15_000L)
    fun fontReturnAfterStopAndReplacementUsesOnlyTheNewRegisteredHostAndDoesNotSave() = runBlocking {
        withTimeout(5_000L) {
            val display = display()
            val fixture = MainCallbacksFixture(this, display.vm, ProbePage.LYRICS_FONTS)
            val oldActivity = fixture.install(fixture.prepareActivity())
            val old = fontHost(oldActivity.page(), fixture.fonts)
            var next: RegisteredFontHost? = null
            val picker = fixture.fonts.picker
            try {
                old.value.edit { it.begin(LyricsFontRole.ORIGINAL, fixture.fonts.text) }
                val draft = checkNotNull(fixture.fonts.editor.state.value.draft)
                val saved = fixture.fonts.store.actual
                val route = checkNotNull(picker.currentFontsRouteToken(ProbePage.LYRICS_FONTS))
                val ticket = fixture.fonts.launch(route, old.value)
                oldActivity.window(HIDDEN)
                val nextActivity = fixture.install(fixture.prepareActivity())
                assertSame(route, picker.currentFontsRouteToken(ProbePage.LYRICS_FONTS))
                assertSame(ticket, picker.awaitingPlatformTicket())
                assertTrue(picker.onPickerResult(ticket, Activity.RESULT_OK, fixture.fonts.uri, READ))
                assertEquals(ProbeFontPickerConsumption.DEFERRED, picker.consumeReturn(route, ticket, old.value))
                assertEquals(0, fixture.fonts.nameReads.get())
                next = fontHost(nextActivity.page(), fixture.fonts)
                assertSame(draft, next.value.editorState?.draft)
                assertEquals(ProbeFontPickerConsumption.CONSUMED, picker.consumeReturn(route, ticket, next.value))
                assertEquals(1, fixture.fonts.nameReads.get())
                assertEquals(1, fixture.fonts.files.imports.get())
                assertEquals(fixture.fonts.files.prepared.asset, fixture.fonts.editor.state.value.draft?.selection?.asset)
                assertEquals(saved, fixture.fonts.store.actual)
                assertEquals(0, fixture.fonts.store.writes.get())
                assertEquals(0, display.calls.callers.size)
                assertNull(picker.state.value.pendingReturn)
                assertFalse(picker.onPickerResult(ticket, Activity.RESULT_OK, fixture.fonts.uri, READ))
                assertEquals(1, fixture.fonts.files.imports.get())
            } finally {
                old.close()
                next?.close()
                fixture.close()
            }
        }
    }

    @Test(timeout = 15_000L)
    fun actualFontRouteDepartureDrainsAnOldTicketAndObsoleteCallbacksCannotRetireTheSuccessor() = runBlocking {
        withTimeout(5_000L) {
            val display = display()
            val fixture = MainCallbacksFixture(this, display.vm, ProbePage.LYRICS_FONTS)
            val obsolete = fixture.install(fixture.prepareActivity())
            val activity = fixture.install(fixture.prepareActivity())
            val host = fontHost(activity.page(), fixture.fonts)
            var next: RegisteredFontHost? = null
            val picker = fixture.fonts.picker
            try {
                host.value.edit { it.begin(LyricsFontRole.ORIGINAL, fixture.fonts.text) }
                val draft = checkNotNull(fixture.fonts.editor.state.value.draft)
                val oldRoute = checkNotNull(picker.currentFontsRouteToken(ProbePage.LYRICS_FONTS))
                val oldTicket = fixture.fonts.launch(oldRoute, host.value)
                fixture.route(ProbePage.PLAYBACK)
                fixture.route(ProbePage.LYRICS_FONTS)
                val route = checkNotNull(picker.currentFontsRouteToken(ProbePage.LYRICS_FONTS))
                assertNotSame(oldRoute, route)
                next = fontHost(activity.page(), fixture.fonts)
                assertTrue(picker.acknowledgeLicense(route, next.value, true))
                assertFalse(picker.launchFontPicker(route, next.value) { _, _ ->
                    throw AssertionError("Overlapping font platform launches")
                })
                assertSame(oldTicket, picker.awaitingPlatformTicket())
                assertFalse(picker.onPickerResult(oldTicket, Activity.RESULT_OK, fixture.fonts.uri, READ))
                val ticket = fixture.fonts.launch(route, next.value)
                obsolete.callbacks.onUiStateChanged(fixture.state.copy(page = ProbePage.PLAYBACK))
                obsolete.window(HIDDEN)
                obsolete.callbacks.close()
                assertSame(route, picker.currentFontsRouteToken(ProbePage.LYRICS_FONTS))
                assertSame(ticket, picker.awaitingPlatformTicket())
                assertSame(draft, next.value.editorState?.draft)
                assertFalse(picker.onPickerResult(oldTicket, Activity.RESULT_OK, fixture.fonts.uri, READ))
                assertTrue(picker.onPickerResult(ticket, Activity.RESULT_CANCELED, null, 0))
                assertEquals(0, fixture.fonts.nameReads.get())
                assertEquals(0, fixture.fonts.files.imports.get())
                assertEquals(0, fixture.fonts.store.writes.get())
            } finally {
                host.close()
                next?.close()
                fixture.close()
            }
        }
    }

    @Test(timeout = 15_000L)
    fun lateFontNameReadCannotImportAfterMainHasRetiredItsExactPageAndCaller() = runBlocking {
        withTimeout(5_000L) {
            val display = display()
            val fixture = MainCallbacksFixture(this, display.vm, ProbePage.LYRICS_FONTS)
            val activity = fixture.install(fixture.prepareActivity())
            val old = fontHost(activity.page(), fixture.fonts)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.fonts.onNameRead = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
                "Late.ttf"
            }
            val picker = fixture.fonts.picker
            old.value.edit { it.begin(LyricsFontRole.ORIGINAL, fixture.fonts.text) }
            val draft = checkNotNull(fixture.fonts.editor.state.value.draft)
            val route = checkNotNull(picker.currentFontsRouteToken(ProbePage.LYRICS_FONTS))
            val ticket = fixture.fonts.launch(route, old.value)
            assertTrue(picker.onPickerResult(ticket, Activity.RESULT_OK, fixture.fonts.uri, READ))
            val consuming = async { picker.consumeReturn(route, ticket, old.value) }
            var next: RegisteredFontHost? = null
            try {
                entered.await()
                fixture.route(ProbePage.PLAYBACK)
                val exactCaller = fixture.fonts.nameJobs.single()
                assertNotSame(consuming, exactCaller)
                assertTrue(exactCaller.isCancelled)
                assertFalse(exactCaller.isCompleted)
                assertFalse(consuming.isCompleted)
                fixture.route(ProbePage.LYRICS_FONTS)
                next = fontHost(activity.page(), fixture.fonts)
                assertSame(draft, next.value.editorState?.draft)
                assertTrue(next.value.editorState?.presentationActive == true)
                release.complete(Unit)
                consuming.join()
                assertTrue(consuming.isCancelled)
                assertEquals(1, fixture.fonts.nameReads.get())
                assertEquals(0, fixture.fonts.files.imports.get())
                assertEquals(0, fixture.fonts.store.writes.get())
                assertNull(picker.state.value.pendingReturn)
                assertNull(old.value.state.original.font.typeface)
                assertSame(draft, next.value.editorState?.draft)
                old.close()
                assertTrue(next.value.editorState?.presentationActive == true)
                currentCoroutineContext().ensureActive()
            } finally {
                release.complete(Unit)
                finish(consuming)
                old.close()
                next?.close()
                fixture.close()
            }
        }
    }

    @Test(timeout = 20_000L)
    fun lyricsPickerStopCanResumeButActualRouteLeaveRejectsItsLateReturnWithoutAssociatingOrRelaunching() = runBlocking {
        withTimeout(7_000L) {
            for (leaveRoute in listOf(false, true)) {
                val display = display()
                val fixture = MainCallbacksFixture(this, display.vm, ProbePage.LYRICS_OPTIONS)
                val old = fixture.install(fixture.prepareActivity())
                var shown = fixture.showEntry(old.page())
                val picker = ProbeLyricsPickerViewModel()
                var launches = 0
                try {
                    fixture.entry.ready()
                    assertNotNull(fixture.entry.vm.requestDocument(LyricsDocumentPurpose.PRIMARY))
                    val request = checkNotNull(fixture.entry.vm.uiState.value.documentRequest)
                    picker.launch(request, fixture.entry.vm) { intent ->
                        launches++
                        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
                        assertEquals(READ, intent.flags and READ)
                        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }
                    assertEquals(1, launches)
                    old.window(HIDDEN)
                    assertTrue(fixture.entry.vm.uiState.value.pickerLaunched)
                    if (leaveRoute) {
                        fixture.route(ProbePage.PLAYBACK)
                        fixture.route(ProbePage.LYRICS_OPTIONS)
                        assertNull(fixture.entry.vm.uiState.value.documentRequest)
                    }
                    picker.returned(fixture.entry.vm, Activity.RESULT_OK,
                        Intent().setData(android.net.Uri.parse(fixture.entry.selected.candidate.uri)).addFlags(READ))
                    assertEquals(0, fixture.entry.previews)
                    val next = fixture.install(fixture.prepareActivity())
                    shown.close()
                    shown = fixture.showEntry(next.page())
                    if (leaveRoute) {
                        fixture.entry.ready()
                        assertNull(fixture.entry.vm.uiState.value.associationPreview)
                        assertEquals(0, fixture.entry.previews)
                    } else {
                        withTimeout(2_000L) {
                            fixture.entry.vm.uiState.first { it.associationPreview != null && !it.loading }
                        }
                        assertEquals(1, fixture.entry.previews)
                    }
                    picker.returned(fixture.entry.vm, Activity.RESULT_OK,
                        Intent().setData(android.net.Uri.parse(fixture.entry.selected.candidate.uri)).addFlags(READ))
                    assertEquals(1, launches)
                    assertEquals(0, fixture.entry.associations)
                    assertEquals(0, fixture.entry.tracks.commits)
                    assertNull(fixture.entry.vm.uiState.value.documentRequest)
                    assertEquals(0, display.calls.callers.size)
                    currentCoroutineContext().ensureActive()
                } finally {
                    shown.close()
                    fixture.close()
                }
            }
        }
    }

    @Test(timeout = 15_000L)
    fun actualFourEntryRouteCallbacksPreserveAnOffsetAndDepartureDropsItBeforeReentry() = runBlocking {
        withTimeout(5_000L) {
            val display = display()
            val fixture = MainCallbacksFixture(this, display.vm, ProbePage.LYRICS)
            val obsolete = fixture.install(fixture.prepareActivity())
            val activity = fixture.install(fixture.prepareActivity())
            var shown = fixture.showEntry(activity.page())
            try {
                fixture.entry.ready()
                assertTrue(fixture.entry.vm.beginOffset())
                fixture.entry.vm.adjustOffset(9)
                assertEquals(900, fixture.entry.vm.uiState.value.offsetDraftMs)
                for (route in listOf(ProbePage.LYRICS_OPTIONS, ProbePage.LYRICS_OFFSET,
                        ProbePage.LYRICS_ASSOCIATION, ProbePage.LYRICS)) {
                    fixture.route(route)
                    assertFalse(shown.isCurrent)
                    shown.close()
                    shown = fixture.showEntry(activity.page())
                    withTimeout(2_000L) {
                        fixture.entry.vm.uiState.first { it.offsetDraftMs == 900 && !it.loading }
                    }
                    obsolete.callbacks.onUiStateChanged(fixture.state.copy(page = ProbePage.PLAYBACK))
                    obsolete.window(HIDDEN)
                    obsolete.callbacks.close()
                    assertEquals(900, fixture.entry.vm.uiState.value.offsetDraftMs)
                    assertTrue(shown.isCurrent)
                }
                fixture.route(ProbePage.LIBRARY)
                assertNull(fixture.entry.vm.uiState.value.offsetDraftMs)
                fixture.route(ProbePage.LYRICS_OPTIONS)
                shown.close()
                shown = fixture.showEntry(activity.page())
                fixture.entry.ready()
                assertNull(fixture.entry.vm.uiState.value.offsetDraftMs)
                assertEquals(0, fixture.entry.tracks.commits)
                assertEquals(0, fixture.entry.associations)
                assertEquals(0, display.calls.callers.size)
            } finally {
                shown.close()
                fixture.close()
            }
        }
    }

    private suspend fun display(ack: DisplayMutationGate? = null, readback: DisplayReadbackGate? = null): DisplayCase {
        check(ack == null || readback == null)
        val file = preferencesFile()
        val original = LyricsDisplayPreferences(revision = 9L)
        writeDisplayFixture(file, LyricsDisplayCodec.encode(original))
        val storage = LyricsDisplayPhysicalStorage(file)
        val session = openPhysicalDisplay(storage) { backing ->
            when {
                readback != null -> PostCommitReadbackDataStore(backing, readback)
                ack != null -> HeldDisplayMutationDataStore(backing, ack, afterCommit = true)
                else -> backing
            }
        }
        assertEquals(original, session.ready().preferences)
        val calls = DisplayCalls(session.store)
        val vm = ProbeLyricsDisplayViewModel(RuntimeEnvironment.getApplication(), LyricsDisplayEditor(calls))
        return DisplayCase(file, original, storage, session, calls, vm)
    }

    private suspend fun readDisplayFile(file: File): LyricsDisplayPreferences = file.source().buffer().use {
        checkNotNull(LyricsDisplayCodec.decode(PreferencesSerializer.readFrom(it)).preferences)
    }

    private suspend fun fontHost(page: ProbePageRuntime.PageBinding, fixture: FontPickerFixture): RegisteredFontHost = coroutineScope {
        val work = WatchFontWork(fixture.runtime, fixture.editor, WatchFontRequest.Editor(fixture.text)) { page.visibleNow }
        check(work.canInstall())
        val registration = checkNotNull(page.installFont(ProbeFontSlot.EDITOR, work::releaseMemory, work::cancelRetiredWork))
        work.admit()
        try {
            async {
                val caller = checkNotNull(currentCoroutineContext()[Job])
                check(work.beginCaller(caller))
                try {
                    check(work.bind(fixture.text))
                    work.render(fixture.text)
                } finally { work.finishCaller(caller) }
            }.await()
            RegisteredFontHost(WatchFontHostValue(false, null, fixture.text, work), registration)
        } catch (failure: Throwable) {
            registration.close()
            throw failure
        }
    }

    private suspend fun finish(job: Job) = withContext(NonCancellable) {
        job.cancel()
        withTimeout(2_000L) { job.join() }
    }

    private class RegisteredFontHost(
        val value: WatchFontHostValue,
        private val registration: ProbePageRuntime.FontRegistration,
    ) : AutoCloseable {
        override fun close() = registration.close()
    }

    private class DisplayCase(
        val file: File,
        val original: LyricsDisplayPreferences,
        val storage: LyricsDisplayPhysicalStorage,
        val session: LyricsDisplayTestSession,
        val calls: DisplayCalls,
        val vm: ProbeLyricsDisplayViewModel,
    ) {
        fun propose(): LyricsDisplayPreferences {
            assertTrue(vm.editor.begin())
            val proposal = checkNotNull(vm.editor.state.value.draft).copy(textSizeSp = 24f, emphasizeWords = true)
            assertTrue(vm.editor.preview(proposal))
            return proposal
        }

        fun assertHeld(parent: Job, cancelled: Boolean) {
            val exact = calls.callers.single()
            assertNotSame(parent, exact)
            assertEquals(cancelled, exact.isCancelled)
            assertFalse(exact.isCompleted)
            assertFalse(parent.isCompleted)
            assertTrue(vm.editor.state.value.working)
        }
    }

    /** Records the actual pre-NonCancellable caller and delegates every byte/ACK to production. */
    private class DisplayCalls(private val actual: LyricsDisplayStore) : LyricsDisplayStore by actual {
        val callers = ArrayList<Job>()
        override suspend fun save(expectedRevision: Long, next: LyricsDisplayPreferences): LyricsDisplayWriteResult {
            callers += checkNotNull(currentCoroutineContext()[Job])
            return actual.save(expectedRevision, next)
        }
    }

    private class DisplayReadbackGate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    /** Real DataStore writes/reads first; only this caller's ACK/error scheduling is controlled. */
    private class PostCommitReadbackDataStore(
        delegate: DataStore<Preferences>,
        private val readback: DisplayReadbackGate,
    ) : ForwardingDisplayDataStore(delegate) {
        private val failed = AtomicBoolean(false)
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val belongsToSave = currentCoroutineContext()[CoroutineName]?.name == READBACK_CALLER
            var changed = false
            val actual = delegate.updateData { before ->
                transform(before).also { changed = it != before }
            }
            if (belongsToSave && changed && failed.compareAndSet(false, true)) {
                throw IOException("Controlled ACK failure after real durable commit")
            }
            if (belongsToSave && !changed && failed.get()) {
                readback.entered.complete(Unit)
                readback.release.await()
            }
            return actual
        }
    }

    /** External Window/effect boundaries are controlled; no Activity/route owner is replicated. */
    private class MainCallbacksFixture(
        parent: CoroutineScope,
        val display: ProbeLyricsDisplayViewModel,
        route: ProbePage = ProbePage.LYRICS_SETTINGS,
    ) {
        val fonts = FontPickerFixture()
        val entry = EntryBoundary(parent)
        val registry = ProbeUiPresentation()
        var state = ProbeUiState(page = route, playback = ProbePlaybackState(isConnected = true,
            hasMedia = true, mediaId = "callback-fixture-media", playbackState = Player.STATE_READY))
        private val activities = ArrayList<ExactActivity>()

        fun prepareActivity(): ExactActivity = ExactActivity().also { activities += it }

        fun install(activity: ExactActivity): ExactActivity {
            activity.assignAndAttach(activity.bindBeforeAssignment())
            return activity
        }

        fun route(next: ProbePage) {
            state = state.copy(page = next)
            registry.update(state)
        }

        fun showEntry(page: ProbePageRuntime.PageBinding): LyricsEntryViewModel.Presentation {
            val presentation = entry.vm.bindPresentation()
            val registration = page.installUiWork(presentation::close)
            if (registration == null || !registration.isCurrent) {
                presentation.close()
                throw AssertionError("Entry cannot start without its actual Page registration")
            }
            presentation.show(if (state.page == ProbePage.LYRICS) LyricsEntrySurface.PAGE else LyricsEntrySurface.OPTIONS)
            return presentation
        }

        suspend fun close() = withContext(NonCancellable) {
            withTimeout(2_500L) {
                try { activities.asReversed().forEach { it.callbacks.close() } }
                finally {
                    try { registry.close() }
                    finally {
                        fonts.picker.leaveFontsRoute(ProbePage.LYRICS_FONTS)
                        display.events.close()
                        entry.close()
                    }
                }
            }
        }

        inner class ExactActivity {
            var binding: ProbeUiPresentation.Binding? = null
                private set
            private var visibility = VISIBLE
            val runtime = ProbePageRuntime({ state }, { visibility }, { binding }, CallbackEffects())
            val callbacks = ProbeActivityUiCallbacks(runtime, { binding }, entry.vm, fonts.picker, display.events)

            fun bindBeforeAssignment(): ProbeUiPresentation.Binding =
                registry.bind(state, callbacks::onUiStateChanged, callbacks::close)

            fun assignAndAttach(exact: ProbeUiPresentation.Binding) {
                check(binding == null)
                binding = exact
                callbacks.noteActualRoute(state)
                runtime.attachActivity(exact)
            }

            fun window(next: WatchWindowVisibility) {
                visibility = next
                callbacks.onWindowVisibilityChanged()
            }

            fun page(): ProbePageRuntime.PageBinding {
                val selection = checkNotNull(runtime.bindSelection {
                    ProbePageSelectionSnapshot(state.page, current = true, settled = true, scrolling = false)
                })
                val kind = when (state.page) {
                    ProbePage.PLAYBACK -> ProbePresentedPageKind.PLAYBACK
                    ProbePage.LYRICS -> ProbePresentedPageKind.LYRICS
                    ProbePage.APPEARANCE -> ProbePresentedPageKind.APPEARANCE
                    ProbePage.LYRICS_FONTS -> ProbePresentedPageKind.LYRICS_FONT
                    else -> ProbePresentedPageKind.THEME_UI
                }
                return checkNotNull(runtime.bindPage(kind, selection))
            }
        }
    }

    /** Only OS-side effects are replaced; real Runtime owns and retires every binding. */
    private class CallbackEffects : ProbePageRuntime.Resources {
        override val savedAppearance = AppearanceSnapshot(AppearanceSettings())
        override fun setForeground(value: Boolean) = Unit
        override fun setEditorVisible(value: Boolean) = Unit
        override fun cancelRetiredFonts() = Unit
        override fun acquireLyricsLease(): ProbePageRuntime.LyricsLease = object : ProbePageRuntime.LyricsLease {
            override fun update(requested: Boolean) = Unit
            override fun close() = Unit
        }
        override fun setBackgroundVisible(owner: Any, value: Boolean): Boolean = false
        override fun updateBackground(owner: Any, scene: ProbePageRuntime.BackgroundScene): Boolean = false
        override fun isBackgroundPresented(owner: Any, display: PlaybackBackgroundDisplay, viewport: BackgroundViewport?): Boolean = false
    }

    /** Actual VM + actual local loader; queue/track/selection ports are explicit controlled boundaries. */
    private class EntryBoundary(parent: CoroutineScope) {
        private val job = SupervisorJob()
        private val scope = CoroutineScope(parent.coroutineContext + job)
        val provider = LocalLyricsLoadFixture()
        val selected = provider.document("[00:01]chosen\n[00:02]chosen second", nativeToken = "selected-v1")
        private val sibling = provider.sibling("[00:01]source\n[00:02]source second")
        val queues = OneEntryQueue(QueueEntry("callback-entry", provider.audio.trackKey, 0L, 0L, location = provider.audio.location))
        val tracks = ReadOnlyTracks(provider.audio.trackKey)
        var previews = 0
        var associations = 0
        private val authority = LyricsLoadAuthority()
        private val loader = provider.loader()
        val vm = LyricsEntryViewModel(
            queues = queues,
            resolveContext = { entry ->
                check(entry.trackKey == provider.audio.trackKey)
                LocalLyricsContext(provider.audio, LyricsSiblingScope("folder", "Track.mp3"))
            },
            validateContext = { it.audio == provider.audio },
            tracks = tracks,
            documents = object : LyricsDocuments {
                override suspend fun preview(uri: String, returnedFlags: Int, generation: Long, encoding: LyricsEncodingChoice): LyricsDocumentInput {
                    check(uri == selected.candidate.uri && returnedFlags and READ != 0)
                    previews++
                    return selected.copy(sourceGeneration = generation, encoding = encoding)
                }
                override suspend fun resolve(association: LyricsAssociation, generation: Long): LyricsDocumentInput =
                    throw AssertionError("No association was saved in the callback integration fixture")
                override suspend fun prepareAssociation(input: LyricsDocumentInput, confirmedSha256: String): PreparedLyricsAssociation {
                    associations++
                    throw AssertionError("A picker callback implicitly associated its preview")
                }
            },
            authority = authority,
            load = loader::load,
            validate = { request, loaded -> authority.isCurrent(request) && loader.validate(request, loaded) },
            playback = object : LyricsPlaybackPort {
                override val changes: Flow<Unit> = emptyFlow()
                override val busy = false
                override fun clock(): LyricsMediaClock = LyricsMediaClock(queues.entry.queueEntryId, 0L, 180_000L, false, 1f, true)
                override suspend fun acquireSeekBinding(): QueueSeekBinding? = null
                override fun isSeekBindingCurrent(binding: QueueSeekBinding): Boolean = false
                override suspend fun dispatchSeek(request: QueueSeekRequest): Boolean =
                    throw AssertionError("Callbacks must not dispatch playback commands")
            },
            scopeOverride = scope,
            awaitDisplayFrame = {},
        )

        init { provider.directory = listOf(provider.audioRow, sibling) }

        suspend fun ready() = withTimeout(2_000L) {
            vm.uiState.first { it.content is LyricsPageContent.Ready && it.canChoose && !it.loading }
        }

        suspend fun close() {
            vm.close()
            job.cancelAndJoin()
            withTimeout(2_000L) { provider.io.awaitIdle() }
        }
    }

    private class ReadOnlyTracks(key: TrackKey) : LyricsTrackStore {
        private val actual = LyricsTrackPreferences(key)
        var commits = 0
        override suspend fun read(trackKey: TrackKey): LyricsTrackReadResult {
            check(trackKey == actual.trackKey)
            return LyricsTrackReadResult.Ready(actual)
        }
        override suspend fun commit(expectedRevision: Long, next: LyricsTrackPreferences, fence: LyricsTrackWriteFence,
            stillCurrent: suspend () -> Boolean): LyricsTrackCommitResult {
            commits++
            throw AssertionError("A lifecycle callback implicitly committed a lyric proposal")
        }
    }

    private class OneEntryQueue(val entry: QueueEntry) : QueueStore {
        private val current = QueueSnapshot("callback-queue", 3L, 0L, FrozenQueueView("callback-view", 1L, 1L),
            QueueMode(), QueueCheckpoint(entry.queueEntryId, 0L), 0L, 0L)
        private val state = MutableStateFlow<QueueSnapshot?>(current)
        override suspend fun initialize(): QueueSnapshot = current
        override suspend fun snapshot(): QueueSnapshot = current
        override fun snapshots(): Flow<QueueSnapshot?> = state
        override suspend fun window(generationId: String, currentEntryId: String): QueueWindow {
            check(generationId == current.generationId && currentEntryId == entry.queueEntryId)
            return QueueWindow(generationId, current.queueRevision, 1L, listOf(entry), 0)
        }
        override suspend fun page(generationId: String, firstPlaybackOrder: Long, limit: Int): List<QueueEntry> =
            throw AssertionError("Callbacks must not request a whole queue page")
        override suspend fun replace(request: QueueReplacementRequest, source: QueuePageSource,
            beforePublish: suspend (QueueWindow?) -> Unit): QueueCommit = throw AssertionError("No queue replacement")
        override suspend fun mutate(request: QueueMutationRequest, source: QueuePageSource?,
            beforePublish: suspend (QueueMutationCandidate) -> QueueCheckpoint?): QueueCommit = throw AssertionError("No queue mutation")
        override suspend fun saveCheckpoint(generationId: String, expectedCheckpointRevision: Long,
            checkpoint: QueueCheckpoint): QueueSnapshot = throw AssertionError("No second checkpoint owner")
    }

    private companion object {
        const val READ = Intent.FLAG_GRANT_READ_URI_PERMISSION
        const val READBACK_CALLER = "main-display-readback-fixture"
        val VISIBLE = WatchWindowVisibility(resumed = true, focused = true, interactive = true, unlocked = true)
        val HIDDEN = VISIBLE.copy(resumed = false)
    }
}
