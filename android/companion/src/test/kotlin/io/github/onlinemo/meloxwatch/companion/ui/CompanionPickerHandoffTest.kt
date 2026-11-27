package io.github.onlinemo.meloxwatch.companion.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.onlinemo.meloxwatch.companion.data.CompanionConnectionPhase
import io.github.onlinemo.meloxwatch.companion.data.CompanionDocuments
import io.github.onlinemo.meloxwatch.companion.data.CompanionEndpoint
import io.github.onlinemo.meloxwatch.companion.data.CompanionIconRole
import io.github.onlinemo.meloxwatch.companion.data.CompanionLocalStore
import io.github.onlinemo.meloxwatch.companion.data.CompanionTarget
import io.github.onlinemo.meloxwatch.companion.data.CompanionUploadPhase
import io.github.onlinemo.meloxwatch.companion.transfer.CompanionTransferClient
import io.github.onlinemo.meloxwatch.companion.transfer.CompanionWireConnection
import io.github.onlinemo.meloxwatch.companion.transfer.CompanionWireFactory
import io.github.onlinemo.meloxwatch.data.transfer.TransferException
import io.github.onlinemo.meloxwatch.data.transfer.TransferFingerprint
import io.github.onlinemo.meloxwatch.data.transfer.TransferHash
import io.github.onlinemo.meloxwatch.data.transfer.TransferIssue
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowContentResolver

/** Real local preparation follows the existing stop/document handshake; the wire gate opens no socket. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionPickerHandoffTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fixtures = ArrayList<Fixture>()

    @After fun retire() {
        fixtures.forEach { fixture ->
            fixture.factory.release()
            fixture.model.stop()
            awaitUi { !fixture.model.state.value.stopping && !fixture.model.state.value.busy && !fixture.documents.working.value }
            fixture.models.clear()
            awaitUi {
                val field = CompanionViewModel::class.java.getDeclaredField("controlScope").apply { isAccessible = true }
                val scope = field.get(fixture.model) as CoroutineScope
                scope.coroutineContext[Job]?.isActive != true && !fixture.documents.working.value
            }
        }
    }

    @Test(timeout = 30_000L)
    fun audioResultWaitsForActualStopAndIsPreparedOnceWithoutAConnection() {
        val fixture = fixture()
        val model = fixture.model
        val ticket = checkNotNull(model.requestAudioPicker())
        model.background()
        model.foreground()
        assertTrue(model.pickerReturned(ticket, fixture.uri))
        assertTrue(model.state.value.pickerPending)
        assertEquals(0, fixture.provider.opens.get())
        assertNull(model.state.value.local?.upload)

        fixture.factory.release()
        awaitUi { model.state.value.local?.upload != null && !model.state.value.busy }
        val draft = checkNotNull(model.state.value.local?.upload)
        assertEquals(CompanionUploadPhase.READY, draft.phase)
        assertEquals(TransferHash.digest(fixture.original), draft.declaration.sha256)
        assertArrayEquals(fixture.original, fixture.store.audioFile(draft.fileId).readBytes())
        assertArrayEquals(fixture.original, fixture.selected.readBytes())
        assertEquals(1, fixture.provider.opens.get())
        assertEquals(0, fixture.factory.opens.get())
        assertFalse(model.state.value.pickerPending)
        assertFalse(model.pickerReturned(ticket, fixture.uri))
        assertSame(draft, model.state.value.local?.upload)
        assertFalse(File(fixture.directory, "state.v2").readText().contains(fixture.uri.toString()))
    }

    @Test(timeout = 30_000L)
    fun aReturnedDocumentRemainsIdleUntilTheActivityIsForeground() {
        val fixture = fixture()
        val ticket = checkNotNull(fixture.model.requestAudioPicker())
        fixture.model.background()
        fixture.factory.release()
        assertTrue(fixture.model.pickerReturned(ticket, fixture.uri))
        awaitUi { !fixture.model.state.value.stopping }
        assertEquals(0, fixture.provider.opens.get())
        assertTrue(fixture.model.state.value.pickerPending)
        fixture.model.foreground()
        awaitUi { fixture.model.state.value.local?.upload != null && !fixture.model.state.value.busy }
        assertEquals(1, fixture.provider.opens.get())
        assertEquals(0, fixture.factory.opens.get())
    }

    @Test(timeout = 30_000L)
    fun cancellingAReturnedSelectionDoesNotPrepareItAfterTheOldStopCompletes() {
        val fixture = fixture()
        val ticket = checkNotNull(fixture.model.requestAudioPicker())
        fixture.model.background()
        fixture.model.foreground()
        assertTrue(fixture.model.pickerReturned(ticket, fixture.uri))
        fixture.model.stop()
        fixture.factory.release()
        awaitUi { !fixture.model.state.value.stopping && !fixture.model.state.value.busy }
        assertFalse(fixture.model.state.value.pickerPending)
        assertNull(fixture.model.state.value.local?.upload)
        assertEquals(0, fixture.provider.opens.get())
        assertEquals(0, fixture.factory.opens.get())
        assertArrayEquals(fixture.original, fixture.selected.readBytes())
    }

    @Test(timeout = 30_000L)
    fun aRetiredLaunchOwnsItsLateResultBeforeAnotherPickerCanStart() {
        val fixture = fixture()
        val model = fixture.model
        val old = checkNotNull(model.requestAudioPicker())
        model.navigate(CompanionScreen.LIBRARY)
        model.navigate(CompanionScreen.CONNECTION)
        assertNull(model.requestAudioPicker())
        assertSame(old, model.pickerTicket(CompanionPickerKind.AUDIO))
        assertTrue(model.pickerReturned(old, fixture.uri))
        assertEquals(0, fixture.provider.opens.get())
        val next = checkNotNull(model.requestAudioPicker())
        assertNotSame(old, next)
        assertFalse(model.pickerReturned(old, fixture.uri))
        assertSame(next, model.pickerTicket(CompanionPickerKind.AUDIO))
        assertTrue(model.pickerReturned(next, null))
        assertFalse(model.state.value.pickerPending)
        assertNull(model.state.value.local?.upload)
    }

    @Test(timeout = 30_000L)
    fun changingTheTargetInputRetiresAPendingLocalSelection() {
        val fixture = fixture()
        val model = fixture.model
        val ticket = checkNotNull(model.requestAudioPicker())
        model.background()
        model.foreground()
        assertTrue(model.pickerReturned(ticket, fixture.uri))
        model.editConnection { it.edited(fingerprint = TransferFingerprint.digest(byteArrayOf(2)).forLocalDisplay()) }
        fixture.factory.release()
        awaitUi { !model.state.value.stopping && !model.state.value.busy }
        assertFalse(model.state.value.pickerPending)
        assertNull(model.state.value.local?.upload)
        assertEquals(0, fixture.provider.opens.get())
    }

    @Test(timeout = 30_000L)
    fun forgettingThePairingCancelsTheSelectionBeforeClearingTheSavedTarget() {
        val fixture = fixture(paired = true)
        val model = fixture.model
        val ticket = checkNotNull(model.requestAudioPicker())
        model.background()
        model.foreground()
        assertTrue(model.pickerReturned(ticket, fixture.uri))
        model.stop(forget = true)
        fixture.factory.release()
        awaitUi { !model.state.value.stopping && !model.state.value.busy }
        assertNull(model.state.value.local?.target)
        assertNull(fixture.store.read().target)
        assertNull(model.state.value.local?.upload)
        assertEquals(0, fixture.provider.opens.get())
        assertEquals(0, fixture.factory.opens.get())
    }

    @Test(timeout = 30_000L)
    fun anIconTicketKeepsItsRoleAndLicenseAcrossAReplacedActivityOwner() {
        val fixture = fixture(icon = true)
        val first = fixture.model
        first.navigate(CompanionScreen.THEME)
        val ticket = checkNotNull(first.requestIconPicker(CompanionIconRole.PAUSE, "Fixture license", "Fixture artwork only."))
        first.background()
        val recreated = ViewModelProvider(fixture.models, viewModelFactory {
            initializer<CompanionViewModel> { error("A retained Activity owner must reuse its ViewModel") }
        })[CompanionViewModel::class.java]
        assertSame(first, recreated)
        assertSame(ticket, recreated.pickerTicket(CompanionPickerKind.ICON))
        recreated.foreground()
        assertTrue(recreated.pickerReturned(ticket, fixture.uri))
        assertEquals(0, fixture.provider.opens.get())
        fixture.factory.release()
        awaitUi { recreated.state.value.local?.theme?.icons?.size == 1 && !recreated.state.value.busy }
        val icon = checkNotNull(recreated.state.value.local?.theme?.icons?.single())
        assertEquals(CompanionIconRole.PAUSE, icon.role)
        assertEquals("Fixture license", icon.licenseName)
        assertEquals("Fixture artwork only.", icon.licenseText)
        assertTrue(fixture.store.iconFile(icon.fileId).isFile)
        assertArrayEquals(fixture.original, fixture.selected.readBytes())
        assertEquals(1, fixture.provider.opens.get())
        assertEquals(0, fixture.factory.opens.get())
    }

    @Test(timeout = 30_000L)
    fun aLaunchFailureAndANullResultReleaseOnlyTheirOwnTicket() {
        val fixture = fixture()
        val model = fixture.model
        val old = checkNotNull(model.requestAudioPicker())
        model.pickerLaunchFailed(old)
        assertTrue(model.state.value.pickerUnavailable)
        val next = checkNotNull(model.requestAudioPicker())
        model.pickerLaunchFailed(old)
        assertSame(next, model.pickerTicket(CompanionPickerKind.AUDIO))
        assertTrue(model.pickerReturned(next, null))
        assertFalse(model.state.value.pickerPending)
        assertFalse(model.state.value.pickerUnavailable)
        assertEquals(0, fixture.provider.opens.get())
    }

    @Test(timeout = 30_000L)
    fun anInvalidUriIsRejectedBeforeAnyProviderReadAndAnExplicitRetryWorks() {
        val fixture = fixture()
        val model = fixture.model
        val ticket = checkNotNull(model.requestAudioPicker())
        assertTrue(model.pickerReturned(ticket, Uri.parse("file:///fixture.invalid/audio.wav")))
        assertEquals(TransferIssue.INVALID_INPUT, model.state.value.issue)
        assertEquals(0, fixture.provider.opens.get())
        val retry = checkNotNull(model.requestAudioPicker())
        assertTrue(model.pickerReturned(retry, fixture.uri))
        awaitUi { model.state.value.local?.upload != null && !model.state.value.busy }
        assertEquals(1, fixture.provider.opens.get())
        assertEquals(0, fixture.factory.opens.get())
    }

    @Test(timeout = 30_000L)
    fun aPermissionFailurePreservesTheOriginalAndAllowsAnotherExplicitChoice() {
        val fixture = fixture()
        fixture.provider.denied = true
        val model = fixture.model
        val ticket = checkNotNull(model.requestAudioPicker())
        assertTrue(model.pickerReturned(ticket, fixture.uri))
        awaitUi { !model.state.value.busy }
        assertEquals(TransferIssue.PERMISSION_DENIED, model.state.value.issue)
        assertNull(model.state.value.local?.upload)
        assertArrayEquals(fixture.original, fixture.selected.readBytes())
        fixture.provider.denied = false
        val retry = checkNotNull(model.requestAudioPicker())
        assertTrue(model.pickerReturned(retry, fixture.uri))
        awaitUi { model.state.value.local?.upload != null && !model.state.value.busy }
        assertEquals(1, fixture.provider.opens.get())
        assertEquals(0, fixture.factory.opens.get())
    }

    @Test(timeout = 30_000L)
    fun aFailedRetirementRequiresExplicitRecoveryBeforeTheSameChoiceCanPrepare() {
        val fixture = fixture()
        val model = fixture.model
        val ticket = checkNotNull(model.requestAudioPicker())
        model.background()
        model.foreground()
        assertTrue(model.pickerReturned(ticket, fixture.uri))
        fixture.factory.failStop()
        awaitUi { model.state.value.connection.phase == CompanionConnectionPhase.RECOVERY_REQUIRED && !model.state.value.stopping }
        assertEquals(0, fixture.provider.opens.get())
        assertEquals(0, fixture.factory.retries.get())
        assertTrue(model.state.value.pickerPending)
        model.retryRecovery()
        awaitUi { model.state.value.local?.upload != null && !model.state.value.recovering && !model.state.value.busy }
        assertTrue(fixture.factory.retries.get() > 0)
        assertEquals(1, fixture.provider.opens.get())
        assertEquals(0, fixture.factory.opens.get())
    }

    @Test(timeout = 30_000L)
    fun aCancelledNonCooperativeDocumentWorkerMustExitBeforeTheSelectedUriIsRead() {
        val fixture = fixture()
        val model = fixture.model
        val ticket = checkNotNull(model.requestAudioPicker())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val held = scope.async {
            fixture.documents.onWorker { operation ->
                entered.countDown()
                assertTrue(release.await(5L, TimeUnit.SECONDS))
                operation.check()
            }
        }
        try {
            assertTrue(entered.await(2L, TimeUnit.SECONDS))
            model.background()
            model.foreground()
            assertTrue(model.pickerReturned(ticket, fixture.uri))
            fixture.factory.release()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(fixture.documents.working.value)
            assertTrue(model.state.value.pickerPending)
            assertEquals(0, fixture.provider.opens.get())
            release.countDown()
            awaitUi { held.isCompleted && model.state.value.local?.upload != null && !model.state.value.busy }
            assertEquals(1, fixture.provider.opens.get())
            assertEquals(0, fixture.factory.opens.get())
        } finally {
            release.countDown()
            runBlocking { withTimeout(5000L) { held.join() } }
            scope.cancel()
        }
    }

    @Test(timeout = 30_000L)
    fun clearingTheViewModelDropsTheUriAndClosesControlAfterActualRetirement() {
        val fixture = fixture()
        val ticket = checkNotNull(fixture.model.requestAudioPicker())
        fixture.model.background()
        fixture.model.foreground()
        assertTrue(fixture.model.pickerReturned(ticket, fixture.uri))
        fixture.models.clear()
        assertFalse(fixture.model.state.value.pickerPending)
        fixture.factory.release()
        awaitUi { !fixture.model.state.value.stopping }
        assertEquals(0, fixture.provider.opens.get())
        assertNull(fixture.store.read().upload)
        assertEquals(0, fixture.factory.opens.get())
    }

    @Test(timeout = 30_000L)
    fun aScopeClosedBeforeOnClearedCannotConsumeTheUriAfterStopAndDocumentExit() {
        val fixture = fixture()
        val model = fixture.model
        val ticket = checkNotNull(model.requestAudioPicker())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val held = scope.async {
            fixture.documents.onWorker { operation ->
                entered.countDown()
                assertTrue(release.await(5L, TimeUnit.SECONDS))
                operation.check()
            }
        }
        try {
            assertTrue(entered.await(2L, TimeUnit.SECONDS))
            model.background()
            model.foreground()
            assertTrue(model.pickerReturned(ticket, fixture.uri))
            // Framework disposal closes this scope before onCleared; physical owners still retire.
            model.viewModelScope.cancel()
            fixture.factory.release()
            release.countDown()
            awaitUi { held.isCompleted && !model.state.value.stopping && !fixture.documents.working.value }
            assertTrue(model.state.value.pickerPending)
            assertNull(model.requestAudioPicker())
            assertNull(model.state.value.local?.upload)
            assertEquals(0, fixture.provider.opens.get())
            assertEquals(0, fixture.factory.opens.get())
            fixture.models.clear()
            assertFalse(model.state.value.pickerPending)
            awaitUi { !model.state.value.stopping }
            assertNull(fixture.store.read().upload)
            assertArrayEquals(fixture.original, fixture.selected.readBytes())
        } finally {
            release.countDown()
            runBlocking { withTimeout(5000L) { held.join() } }
            scope.cancel()
        }
    }

    private fun fixture(icon: Boolean = false, paired: Boolean = false): Fixture {
        val directory = temporary.newFolder()
        val selected = temporary.newFile(if (icon) "fixture-${fixtures.size}.png" else "fixture-${fixtures.size}.wav")
        val original = if (icon) {
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.RED)
                ByteArrayOutputStream().use { output ->
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    output.toByteArray()
                }
            } finally { bitmap.recycle() }
        } else pcmWave()
        selected.writeBytes(original)
        val authority = "phone-picker-fixture-${fixtures.size}"
        val info = ProviderInfo().apply { this.authority = authority; exported = false }
        val provider = Robolectric.buildContentProvider(CompanionPickerFixtureProvider::class.java).create(info).get().also {
            it.selected = selected
            it.mime = if (icon) "image/png" else "audio/wav"
        }
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val store = CompanionLocalStore(directory)
        if (paired) store.update { it.copy(target = CompanionTarget(TransferFingerprint.digest(byteArrayOf(1)), "Fixture watch")) }
        val documents = CompanionDocuments(RuntimeEnvironment.getApplication().contentResolver, store)
        val factory = HeldStopFactory()
        val models = ViewModelStore()
        val model = ViewModelProvider(models, viewModelFactory {
            initializer { CompanionViewModel(documents, CompanionTransferClient(factory)) }
        })[CompanionViewModel::class.java]
        return Fixture(directory, selected, original, Uri.parse("content://$authority/selected"), provider, store,
            documents, factory, models, model).also {
            fixtures += it
            model.foreground()
            awaitUi { model.state.value.local != null && !model.state.value.busy }
        }
    }

    private class Fixture(
        val directory: File, val selected: File, val original: ByteArray, val uri: Uri,
        val provider: CompanionPickerFixtureProvider, val store: CompanionLocalStore,
        val documents: CompanionDocuments, val factory: HeldStopFactory,
        val models: ViewModelStore, val model: CompanionViewModel,
    )

    private class HeldStopFactory : CompanionWireFactory {
        @Volatile private var gate = CompletableDeferred<Unit>()
        @Volatile private var failed = false
        val opens = AtomicInteger()
        val retries = AtomicInteger()
        override val retirementRequired: Boolean get() = failed
        override suspend fun open(endpoint: CompanionEndpoint, target: TransferFingerprint, expectedSession: String?): CompanionWireConnection {
            opens.incrementAndGet()
            throw TransferException(TransferIssue.TRANSPORT_FAILED)
        }
        override fun stop(): Deferred<Unit> = gate
        override suspend fun retryRetirement() {
            retries.incrementAndGet()
            failed = false
            gate = CompletableDeferred(Unit)
        }
        fun release() { gate.complete(Unit) }
        fun failStop() {
            failed = true
            gate.completeExceptionally(TransferException(TransferIssue.RECOVERY_REQUIRED))
        }
    }

    private fun awaitUi(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L)
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            assertTrue("The controlled operation did not retire within its test deadline", System.nanoTime() < deadline)
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L))
        }
    }

    private fun pcmWave(): ByteArray = ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII))
        putInt(52)
        put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        putInt(16)
        putShort(1)
        putShort(1)
        putInt(8000)
        putInt(16000)
        putShort(2)
        putShort(16)
        put("data".toByteArray(Charsets.US_ASCII))
        putInt(16)
        repeat(8) { putShort(0) }
    }.array()
}

/** Only the test-created audio/image is available; selected URI strings are never stored. */
class CompanionPickerFixtureProvider : ContentProvider() {
    lateinit var selected: File
    var mime: String = "audio/wav"
    @Volatile var denied = false
    val opens = AtomicInteger()
    override fun onCreate(): Boolean = true
    override fun getType(uri: Uri): String = mime
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        if (denied) throw SecurityException("Controlled fixture permission denial")
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            val values: List<Any?> = columns.map { column -> when (column) {
                OpenableColumns.DISPLAY_NAME -> selected.name
                OpenableColumns.SIZE -> selected.length()
                else -> null
            } }
            addRow(values)
        }
    }
    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        if (denied) throw SecurityException("Controlled fixture permission denial")
        opens.incrementAndGet()
        return AssetFileDescriptor(ParcelFileDescriptor.open(selected, ParcelFileDescriptor.MODE_READ_ONLY), 0L, selected.length())
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException("Read-only fixture")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only fixture")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only fixture")
}
