package io.github.onlinemo.meloxwatch.ui.fonts

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import io.github.onlinemo.meloxwatch.data.fonts.FontWeightCapability
import io.github.onlinemo.meloxwatch.data.fonts.FontWeightSupport
import io.github.onlinemo.meloxwatch.data.fonts.LoadedLyricsFont
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontAsset
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontFiles
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontImportRequest
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontIssue
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontLimits
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontRole
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSelection
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSaveResult
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSettings
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSettingsStore
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontTestFixtures as Fixture
import io.github.onlinemo.meloxwatch.data.fonts.PreparedLyricsFont
import io.github.onlinemo.meloxwatch.data.fonts.SfntFontParser
import io.github.onlinemo.meloxwatch.data.themes.ThemePreview
import io.github.onlinemo.meloxwatch.data.themes.ThemeSnapshot
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Actual Editor/owner/host admission; the deterministic import seam does not prove native decode. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class ProbeFontPickerViewModelTest {
    @Test(timeout = 15_000L)
    fun pickerStopRejectsTheRetiredHostAndImportsOnceThroughTheResumedCurrentDraftWithoutSaving() = runBlocking {
        withTimeout(5_000L) {
            val fixture = FontPickerFixture()
            val routeKey = Any()
            val route = fixture.picker.enterFontsRoute(routeKey)
            val old = fixture.openHost()
            var next: FontPickerFixture.Host? = null
            try {
                assertTrue(old.value.edit { it.begin(LyricsFontRole.ORIGINAL, fixture.text) })
                val draft = checkNotNull(fixture.editor.state.value.draft)
                val saved = fixture.store.actual
                val ticket = fixture.launch(route, old.value)
                old.close()
                assertSame(ticket, fixture.picker.awaitingPlatformTicket())
                assertTrue(fixture.picker.onPickerResult(ticket, Activity.RESULT_OK, fixture.uri, READ))
                assertEquals(ProbeFontPickerConsumption.DEFERRED,
                    fixture.picker.consumeReturn(route, ticket, old.value))
                assertEquals(0, fixture.nameReads.get())
                assertEquals(0, fixture.files.imports.get())
                assertSame(route, fixture.picker.enterFontsRoute(routeKey))
                assertSame(route, fixture.picker.currentFontsRouteToken(routeKey))
                next = fixture.openHost()
                assertSame(draft, fixture.editor.state.value.draft)
                assertTrue(fixture.picker.licenseAcknowledgedFor(next.value.editorState?.draft))
                assertEquals(ProbeFontPickerConsumption.CONSUMED,
                    fixture.picker.consumeReturn(route, ticket, next.value))
                assertEquals(1, fixture.nameReads.get())
                assertEquals(1, fixture.files.imports.get())
                assertEquals(saved, fixture.store.actual)
                assertEquals(0, fixture.store.writes.get())
                assertEquals(fixture.files.prepared.asset, fixture.editor.state.value.draft?.selection?.asset)
                assertEquals(saved.translation, fixture.editor.state.value.saved?.translation)
                assertEquals(LyricsFontLimits.LICENSE_ACKNOWLEDGMENT_VERSION,
                    fixture.files.requests.single().licenseAcknowledgmentVersion)
                assertNull(fixture.picker.state.value.pendingReturn)
                assertFalse(fixture.picker.onPickerResult(ticket, Activity.RESULT_OK, fixture.uri, READ))
                assertEquals(ProbeFontPickerConsumption.REJECTED,
                    fixture.picker.consumeReturn(route, ticket, next.value))
                assertEquals(1, fixture.nameReads.get())
                assertEquals(1, fixture.files.imports.get())
                currentCoroutineContext().ensureActive()
            } finally {
                fixture.picker.leaveFontsRouteToken(route)
                old.close()
                next?.close()
            }
        }
    }

    @Test(timeout = 15_000L)
    fun consentAndReturnDoNotCrossIntoAnotherRoleOrAnEqualNewDraftOfTheSameRole() = runBlocking {
        withTimeout(5_000L) {
            for (replacementRole in LyricsFontRole.entries) {
                val fixture = FontPickerFixture()
                val route = fixture.picker.enterFontsRoute(Any())
                val host = fixture.openHost()
                try {
                    host.value.edit { it.begin(LyricsFontRole.ORIGINAL, fixture.text) }
                    val before = checkNotNull(fixture.editor.state.value.draft)
                    val saved = fixture.store.actual
                    val oldTicket = fixture.launch(route, host.value)
                    host.value.edit {
                        it.cancel(fixture.text)
                        it.begin(replacementRole, fixture.text)
                    }
                    val actual = checkNotNull(fixture.editor.state.value.draft)
                    assertNotSame(before, actual)
                    if (replacementRole == LyricsFontRole.ORIGINAL) assertEquals(before, actual)
                    assertFalse(fixture.picker.licenseAcknowledgedFor(actual))
                    assertTrue(fixture.picker.onPickerResult(oldTicket, Activity.RESULT_OK, fixture.uri, READ))
                    assertEquals(ProbeFontPickerConsumption.REJECTED,
                        fixture.picker.consumeReturn(route, oldTicket, host.value))
                    assertEquals(0, fixture.nameReads.get())
                    assertEquals(0, fixture.files.imports.get())
                    assertFalse(fixture.picker.launchFontPicker(route, host.value) { _, _ ->
                        throw AssertionError("Old role consent launched a replacement draft")
                    })
                    assertEquals(LyricsFontIssue.LICENSE_ACKNOWLEDGMENT_REQUIRED, fixture.picker.state.value.issue)
                    val nextTicket = fixture.launch(route, host.value)
                    assertFalse(fixture.picker.onPickerResult(oldTicket, Activity.RESULT_OK, fixture.uri, READ))
                    assertSame(nextTicket, fixture.picker.awaitingPlatformTicket())
                    assertTrue(fixture.picker.onPickerResult(nextTicket, Activity.RESULT_CANCELED, null, 0))
                    assertSame(actual, fixture.editor.state.value.draft)
                    assertEquals(saved, fixture.store.actual)
                    assertEquals(0, fixture.store.writes.get())
                    assertNull(fixture.picker.state.value.pendingReturn)
                } finally {
                    fixture.picker.leaveFontsRouteToken(route)
                    host.close()
                }
            }
        }
    }

    @Test(timeout = 15_000L)
    fun leavingTheRouteDrainsItsLaunchAndStaleResultOrLeaveCannotOverwriteTheSuccessor() = runBlocking {
        withTimeout(5_000L) {
            val fixture = FontPickerFixture()
            val routeKey = Any()
            val oldRoute = fixture.picker.enterFontsRoute(routeKey)
            var nextRoute: ProbeFontPickerRoute? = null
            val host = fixture.openHost()
            try {
                host.value.edit { it.begin(LyricsFontRole.ORIGINAL, fixture.text) }
                val draft = checkNotNull(fixture.editor.state.value.draft)
                val saved = fixture.store.actual
                val oldTicket = fixture.launch(oldRoute, host.value)
                fixture.picker.leaveFontsRoute(routeKey)
                val actualNextRoute = fixture.picker.enterFontsRoute(routeKey)
                nextRoute = actualNextRoute
                assertNotSame(oldRoute, actualNextRoute)
                assertFalse(fixture.picker.acknowledgeLicense(oldRoute, host.value, true))
                assertTrue(fixture.picker.acknowledgeLicense(actualNextRoute, host.value, true))
                assertFalse(fixture.picker.launchFontPicker(actualNextRoute, host.value) { _, _ ->
                    throw AssertionError("Overlapping platform font pickers were launched")
                })
                assertEquals(LyricsFontIssue.BUSY, fixture.picker.state.value.issue)
                assertSame(oldTicket, fixture.picker.awaitingPlatformTicket())
                assertFalse(fixture.picker.onPickerResult(oldTicket, Activity.RESULT_OK, fixture.uri, READ))
                assertNull(fixture.picker.awaitingPlatformTicket())
                var nextTicket: ProbeFontPickerTicket? = null
                assertTrue(fixture.picker.launchFontPicker(actualNextRoute, host.value) { ticket, _ -> nextTicket = ticket })
                val exactNext = checkNotNull(nextTicket)
                fixture.picker.leaveFontsRouteToken(oldRoute)
                assertTrue(fixture.picker.licenseAcknowledgedFor(draft))
                assertFalse(fixture.picker.onPickerResult(oldTicket, Activity.RESULT_OK, fixture.uri, READ))
                assertSame(exactNext, fixture.picker.awaitingPlatformTicket())
                assertTrue(fixture.picker.onPickerResult(exactNext, Activity.RESULT_CANCELED, null, 0))
                assertSame(draft, fixture.editor.state.value.draft)
                assertEquals(saved, fixture.store.actual)
                assertEquals(0, fixture.nameReads.get())
                assertEquals(0, fixture.files.imports.get())
                assertEquals(0, fixture.store.writes.get())
            } finally {
                fixture.picker.leaveFontsRouteToken(oldRoute)
                nextRoute?.let(fixture.picker::leaveFontsRouteToken)
                host.close()
            }
        }
    }

    @Test(timeout = 15_000L)
    fun delayedOrPromptHostCancellationCannotImportAfterALateNameReadEvenWhenTheSharedEditorIsCurrentAgain() = runBlocking {
        withTimeout(5_000L) {
            for (cancelBeforeReturn in listOf(false, true)) {
                val fixture = FontPickerFixture()
                val route = fixture.picker.enterFontsRoute(Any())
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                fixture.onNameRead = {
                    withContext(NonCancellable) {
                        entered.complete(Unit)
                        release.await()
                    }
                    "Selected.ttf"
                }
                val host = fixture.openHost()
                var next: FontPickerFixture.Host? = null
                host.value.edit { it.begin(LyricsFontRole.ORIGINAL, fixture.text) }
                val draft = checkNotNull(fixture.editor.state.value.draft)
                val saved = fixture.store.actual
                val ticket = fixture.launch(route, host.value)
                fixture.picker.onPickerResult(ticket, Activity.RESULT_OK, fixture.uri, READ)
                val consuming = async { fixture.picker.consumeReturn(route, ticket, host.value) }
                try {
                    entered.await()
                    assertTrue(fixture.nameJobs.single() !== consuming)
                    assertSame(ticket, fixture.picker.state.value.pendingReturn)
                    assertTrue(fixture.picker.state.value.consuming)
                    host.work.releaseMemory()
                    assertFalse(consuming.isCancelled)
                    next = fixture.openHost()
                    assertSame(draft, next.value.editorState?.draft)
                    assertTrue(next.value.editorState?.presentationActive == true)
                    if (cancelBeforeReturn) host.work.cancelRetiredWork()
                    release.complete(Unit)
                    consuming.join()
                    assertEquals(cancelBeforeReturn, consuming.isCancelled)
                    assertEquals(1, fixture.nameReads.get())
                    assertEquals(0, fixture.files.imports.get())
                    assertEquals(0, fixture.store.writes.get())
                    assertEquals(saved, fixture.store.actual)
                    assertSame(draft, next.value.editorState?.draft)
                    assertNull(host.value.state.original.font.typeface)
                    assertNull(fixture.picker.state.value.pendingReturn)
                    host.work.cancelRetiredWork()
                    assertTrue(next.value.editorState?.presentationActive == true)
                    currentCoroutineContext().ensureActive()
                } finally {
                    release.complete(Unit)
                    consuming.cancel()
                    withContext(NonCancellable) { withTimeout(1_000L) { consuming.join() } }
                    fixture.picker.leaveFontsRouteToken(route)
                    host.close()
                    next?.close()
                }
            }
        }
    }

    companion object { private const val READ = Intent.FLAG_GRANT_READ_URI_PERMISSION }
}

/** Deterministic Store/Loader seams keep picker ownership independent of native font decode. */
internal class FontPickerFixture {
    val text = LyricsFontPreviewText("Original music", "Translation music")
    val uri: Uri = Uri.parse("content://font-picker-fixture/selected")
    val owner = LyricsFontPresentationOwner()
    val store = Store()
    val files = ImportFiles()
    val loader = Loader(files)
    val runtime = LyricsFontRuntime(store, owner, loader)
    val editor = LyricsFontEditor(store, files, owner)
    val nameReads = AtomicInteger()
    val nameJobs = CopyOnWriteArrayList<Job>()
    var onNameRead: suspend (String) -> String = { "Selected.ttf" }
    val picker = ProbeFontPickerViewModel(RuntimeEnvironment.getApplication()) { encoded ->
        nameReads.incrementAndGet()
        nameJobs += checkNotNull(currentCoroutineContext()[Job])
        onNameRead(encoded)
    }

    suspend fun openHost(): Host = coroutineScope {
        val work = WatchFontWork(runtime, editor, WatchFontRequest.Editor(text)) { true }
        work.admit()
        check(work.bind(text))
        async {
            val job = checkNotNull(currentCoroutineContext()[Job])
            check(work.beginCaller(job))
            try { work.render(text) } finally { work.finishCaller(job) }
        }.await()
        Host(work, WatchFontHostValue(false, null, text, work))
    }

    suspend fun launch(route: ProbeFontPickerRoute, host: WatchFontHostValue): ProbeFontPickerTicket {
        check(picker.acknowledgeLicense(route, host, true))
        var launched: ProbeFontPickerTicket? = null
        check(picker.launchFontPicker(route, host) { ticket, intent ->
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
            assertTrue(intent.categories?.contains(Intent.CATEGORY_OPENABLE) == true)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            launched = ticket
        })
        return checkNotNull(launched)
    }

    class Host(val work: WatchFontWork, val value: WatchFontHostValue) : AutoCloseable {
        override fun close() {
            work.releaseMemory()
            work.cancelRetiredWork()
        }
    }

    /** Every CAS attempt is counted, so an accidental picker confirmation cannot pass as a read. */
    class Store : LyricsFontSettingsStore {
        var actual = LyricsFontSettings()
            private set
        val writes = AtomicInteger()

        override suspend fun read(): LyricsFontSettings {
            currentCoroutineContext().ensureActive()
            return actual
        }

        override suspend fun compareAndSet(expectedRevision: Long, replacement: LyricsFontSettings): LyricsFontSaveResult {
            currentCoroutineContext().ensureActive()
            require(expectedRevision >= 0L && replacement.revision == expectedRevision)
            writes.incrementAndGet()
            if (actual.revision != expectedRevision) return LyricsFontSaveResult.Conflict(actual)
            actual = replacement.copy(revision = Math.addExact(actual.revision, 1L))
            return LyricsFontSaveResult.Saved(actual)
        }
    }

    /** The personal host reuses the import seam; a theme read would violate this fixture's boundary. */
    class Loader(private val files: LyricsFontFiles) : LyricsFontRuntime.Loader {
        override suspend fun personal(selection: LyricsFontSelection, text: String): LoadedLyricsFont =
            files.loadFont(selection, text)

        override suspend fun themeUi(snapshot: ThemeSnapshot, role: LyricsFontRole, text: String): LoadedLyricsFont? =
            throw AssertionError("Font picker fixture must not read an applied theme")

        override suspend fun preview(preview: ThemePreview, role: LyricsFontRole, weight: Float?, text: String): LoadedLyricsFont =
            throw AssertionError("Font picker fixture must not read a theme preview")

        override suspend fun awaitIdle() {
            currentCoroutineContext().ensureActive()
        }
    }

    class ImportFiles : LyricsFontFiles {
        val imports = AtomicInteger()
        val requests = CopyOnWriteArrayList<LyricsFontImportRequest>()
        val importJobs = CopyOnWriteArrayList<Job>()
        val prepared = PreparedLyricsFont(Fixture.asset(name = "Selected.ttf"),
            SfntFontParser().parse(Fixture.bytes()), FontWeightCapability(FontWeightSupport.NO_AXIS, null))
        var onImport: suspend () -> Unit = { }
        override suspend fun importFont(request: LyricsFontImportRequest, retain: Set<String>): PreparedLyricsFont {
            imports.incrementAndGet()
            requests += request
            importJobs += checkNotNull(currentCoroutineContext()[Job])
            onImport()
            currentCoroutineContext().ensureActive()
            return prepared
        }
        override suspend fun loadFont(selection: LyricsFontSelection, text: String): LoadedLyricsFont =
            LoadedLyricsFont(typeface = Typeface.DEFAULT, asset = selection.asset)
        override suspend fun discard(asset: LyricsFontAsset, retain: Set<String>) = Unit
        override suspend fun prune(retain: Set<String>) = Unit
    }
}
