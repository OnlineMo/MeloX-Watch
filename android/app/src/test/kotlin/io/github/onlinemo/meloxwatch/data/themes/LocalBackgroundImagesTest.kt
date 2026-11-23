package io.github.onlinemo.meloxwatch.data.themes

import android.graphics.Color
import android.os.CancellationSignal
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.themes.ThemeTestFixtures as Fixture
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
class LocalBackgroundImagesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun selectedOriginalIsCopiedReadOnlyAndSavedCropSurvivesRestartAndReset() = runBlocking {
        val original = temporary.newFile()
        val bytes = Fixture.png()
        original.writeBytes(bytes)
        val source = object : ThemePackageOpener {
            override fun open(uri: String, signal: CancellationSignal): OpenedThemePackage {
                signal.throwIfCanceled()
                return OpenedThemePackage(FileInputStream(original), original.length()) {}
            }
        }
        val directory = temporary.newFolder()
        val io = LocalProviderIo()
        val owner = ThemeBitmapOwner()
        val store = store(directory, source, io, owner)
        val preview = store.prepareImage(backgroundImportRequest())
        val options = PlaybackBackgroundOptions(ThemeBackgroundMode.CUSTOM_IMAGE, 8, 35,
            BackgroundCrop(1.5f, 0.2f, 0.8f), preview.image)
        val result = store.compareAndSet(0, options, preview.token) as PlaybackBackgroundSaveResult.Saved
        assertNull(store.preview.value)
        assertTrue(original.readBytes().contentEquals(bytes))
        val restarted = store(directory, source, io, owner)
        val actual = restarted.read()
        assertEquals(result.snapshot.settings, actual.settings)
        val saved = checkNotNull(actual.settings)
        val lease = restarted.loadImage(PlaybackBackgroundImageKey.Custom(preview.image, settingsRevision = saved.revision), 32)
        try { assertEquals(Color.rgb(40, 90, 140), lease.bitmap.getPixel(16, 16)) }
        finally { lease.close() }
        val defaults = PlaybackBackgroundOptions(customImage = preview.image)
        val reset = restarted.compareAndSet(saved.revision, defaults) as PlaybackBackgroundSaveResult.Saved
        assertEquals(preview.image, reset.snapshot.effective.customImage)
        assertTrue(File(directory, preview.image.id + ".image").isFile)
        assertTrue(original.readBytes().contentEquals(bytes))
        val record = File(directory, "settings.bin").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(record.contains("content://background-fixture/selected"))
    }

    @Test fun conflictingSaveReadsTheActualRecordAndKeepsTheCandidateForExplicitRetry() = runBlocking {
        val store = store(temporary.newFolder(), BytesThemeSource(Fixture.png()))
        val candidate = store.prepareImage(backgroundImportRequest())
        val other = PlaybackBackgroundOptions(mode = ThemeBackgroundMode.BLACK)
        store.compareAndSet(0, other)
        val replacement = PlaybackBackgroundOptions(mode = ThemeBackgroundMode.CUSTOM_IMAGE, customImage = candidate.image)
        val conflict = store.compareAndSet(0, replacement, candidate.token) as PlaybackBackgroundSaveResult.Conflict
        assertEquals(other, conflict.snapshot.effective)
        assertEquals(candidate, store.preview.value)
        val actualRevision = checkNotNull(conflict.snapshot.settings).revision
        val saved = store.compareAndSet(actualRevision, replacement, candidate.token) as PlaybackBackgroundSaveResult.Saved
        assertEquals(replacement, saved.snapshot.effective)
        assertEquals(2L, saved.snapshot.settings?.revision)
    }

    @Test fun damagedAndOverPixelLimitInputsLeaveSavedSettingsAndNoNewManagedCopy() = runBlocking {
        val directory = temporary.newFolder()
        val source = BytesThemeSource(byteArrayOf(1, 2, 3, 4))
        val store = store(directory, source)
        expectBackground(PlaybackBackgroundIssue.INVALID_IMAGE) { store.prepareImage(backgroundImportRequest()) }
        val giant = Fixture.png().copyOf()
        val header = ByteBuffer.wrap(giant).order(ByteOrder.BIG_ENDIAN)
        header.putInt(16, 4_097)
        header.putInt(20, 4_097)
        val checksum = CRC32().apply { update(giant, 12, 17) }.value
        header.putInt(29, checksum.toInt())
        source.bytes = giant
        source.declared = giant.size.toLong()
        expectBackground(PlaybackBackgroundIssue.IMAGE_TOO_LARGE) { store.prepareImage(backgroundImportRequest()) }
        assertEquals(PlaybackBackgroundSettings(), store.read().settings)
        assertNull(store.preview.value)
        assertEquals(0, managedBackgroundSources(directory).size)
        assertEquals(2, source.inputCloses.get())
        assertEquals(2, source.descriptorCloses.get())
    }

    @Test fun declaredOversizeAndRevokedProviderCloseOnlyResourcesThatWereActuallyOpened() = runBlocking {
        val source = BytesThemeSource(Fixture.png(), declared = 16L * 1024 * 1024 + 1)
        val store = store(temporary.newFolder(), source)
        expectBackground(PlaybackBackgroundIssue.IMAGE_TOO_LARGE) { store.prepareImage(backgroundImportRequest()) }
        assertEquals(1, source.inputCloses.get())
        assertEquals(1, source.descriptorCloses.get())
        source.openFailure = SecurityException("Fixture revoked access")
        expectBackground(PlaybackBackgroundIssue.PERMISSION_DENIED) { store.prepareImage(backgroundImportRequest()) }
        assertEquals(2, source.opens.get())
        assertEquals(1, source.inputCloses.get())
        assertEquals(PlaybackBackgroundSettings(), store.read().settings)
    }

    @Test fun lowStorageAndARejectedNativeReservationDoNotReplaceTheRecoverableSelection() = runBlocking {
        val bytes = Fixture.png()
        val directory = temporary.newFolder()
        val source = BytesThemeSource(bytes)
        val lowStorage = LocalBackgroundImages(directory, LocalProviderIo(), ThemeBitmapOwner(), source,
            availableBytes = { ThemeLimits.FREE_RESERVE_BYTES - 1 })
        expectBackground(PlaybackBackgroundIssue.STORAGE_FULL) { lowStorage.prepareImage(backgroundImportRequest()) }
        val lowMemory = store(directory, source, owner = ThemeBitmapOwner(limitBytes = 1_024))
        expectBackground(PlaybackBackgroundIssue.BUSY) { lowMemory.prepareImage(backgroundImportRequest()) }
        assertEquals(PlaybackBackgroundSettings(), lowMemory.read().settings)
        assertTrue(source.bytes.contentEquals(bytes))
        assertEquals(0, managedBackgroundSources(directory).size)
    }

    @Test fun closeFailureIsReportedAndNeverAcknowledgedAsACompleteCopiedImage() = runBlocking {
        val directory = temporary.newFolder()
        val source = BytesThemeSource(Fixture.png()).also { it.closeFailure = IllegalStateException("Fixture close failure") }
        val store = store(directory, source)
        expectBackground(PlaybackBackgroundIssue.INPUT_CLOSE_FAILED) { store.prepareImage(backgroundImportRequest()) }
        assertEquals(1, source.inputCloses.get())
        assertEquals(1, source.descriptorCloses.get())
        assertEquals(0, managedBackgroundSources(directory).size)
        assertNull(store.preview.value)
    }

    @Test fun corruptSavedCopyUsesAnIntegrityFailureWhileItsSelectionAndOriginalRemainRecoverable() = runBlocking {
        val directory = temporary.newFolder()
        val bytes = Fixture.png()
        val store = store(directory, BytesThemeSource(bytes))
        val preview = store.prepareImage(backgroundImportRequest())
        val options = PlaybackBackgroundOptions(mode = ThemeBackgroundMode.CUSTOM_IMAGE, customImage = preview.image)
        store.compareAndSet(0, options, preview.token)
        val image = File(directory, preview.image.id + ".image")
        image.writeBytes(ByteArray(bytes.size))
        val actual = store.read()
        assertEquals(options, actual.effective)
        expectBackground(PlaybackBackgroundIssue.INTEGRITY_FAILED) {
            store.loadImage(PlaybackBackgroundImageKey.Custom(preview.image, settingsRevision = 1), 32)
        }
        assertEquals(preview.image, store.read().effective.customImage)
        assertTrue(image.exists())
    }

    @Test fun corruptedSettingsChecksumDoesNotPruneThePreviouslySavedPrivateImage() = runBlocking {
        val directory = temporary.newFolder()
        val store = store(directory, BytesThemeSource(Fixture.png()))
        val preview = store.prepareImage(backgroundImportRequest())
        store.compareAndSet(0, PlaybackBackgroundOptions(mode = ThemeBackgroundMode.CUSTOM_IMAGE, customImage = preview.image), preview.token)
        val file = File(directory, "settings.bin")
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        val actual = store.read()
        assertNull(actual.settings)
        assertEquals(PlaybackBackgroundIssue.RECOVERY_REQUIRED, actual.issue)
        assertTrue(File(directory, preview.image.id + ".image").isFile)
    }

    @Test fun retiredPreviewDoesOnlyMemoryWorkUntilTheNextPhysicalReadPrunesIt() = runBlocking {
        val directory = temporary.newFolder()
        val store = store(directory, BytesThemeSource(Fixture.png()))
        val preview = store.prepareImage(backgroundImportRequest())
        val image = File(directory, preview.image.id + ".image")
        store.retirePreview(preview.token)
        store.retirePreview(preview.token)
        assertNull(store.preview.value)
        assertTrue(image.isFile)
        expectBackground(PlaybackBackgroundIssue.PREVIEW_NOT_FOUND) {
            store.loadImage(PlaybackBackgroundImageKey.Custom(preview.image, previewToken = preview.token), 32)
        }
        assertEquals(0L, store.read().settings?.revision)
        assertFalse(image.exists())
        val next = store.prepareImage(backgroundImportRequest())
        store.discardPreview(next.token)
    }

    @Test(timeout = 30_000L) fun cancelledProviderReadKeepsTheSinglePhysicalSlotAndClosesOnThatWorker() = runBlocking {
        withTimeout(15_000) {
            val source = BlockingBackgroundSource(Fixture.png())
            val dispatcher = ThemeDeliveryIoDispatcher()
            val io = LocalProviderIo(dispatcher)
            val store = store(temporary.newFolder(), source, io)
            store.read()
            val scheduler = TestCoroutineScheduler()
            val scope = CoroutineScope(Job() + StandardTestDispatcher(scheduler))
            val returned = CountDownLatch(1)
            dispatcher.arm(returned)
            val caller = scope.async { store.prepareImage(backgroundImportRequest()) }
            try {
                scheduler.runCurrent()
                awaitThemeLatch(source.entered)
                caller.cancel()
                scheduler.runCurrent()
                assertTrue(caller.isCancelled)
                assertFalse(caller.isCompleted)
                expectBackground(PlaybackBackgroundIssue.BUSY) { store.read() }
                assertEquals(0, source.inputCloses.get())
                assertEquals(0, source.descriptorCloses.get())
                assertEquals(1, source.opens.get())
                source.release.countDown()
                awaitThemeLatch(returned)
                scheduler.runCurrent()
                caller.join()
                assertEquals(1, source.inputCloses.get())
                assertEquals(1, source.descriptorCloses.get())
                assertTrue(source.readThread.get() === source.closeThread.get())
                assertTrue(source.readThread.get() !== Thread.currentThread())
                assertNull(source.gateFailure.get())
                assertNull(store.preview.value)
                assertEquals(PlaybackBackgroundSettings(), store.read().settings)
            } finally {
                source.release.countDown()
                retireBackgroundTestScope(io, scope, scheduler)
            }
        }
    }

    @Test(timeout = 30_000L) fun cancellationAfterNativePreviewWorkerReturnRetiresTheUndeliveredCopy() = runBlocking {
        withTimeout(15_000) {
            val directory = temporary.newFolder()
            val source = BytesThemeSource(Fixture.png())
            val dispatcher = ThemeDeliveryIoDispatcher()
            val io = LocalProviderIo(dispatcher)
            val store = store(directory, source, io)
            store.read()
            val scheduler = TestCoroutineScheduler()
            val scope = CoroutineScope(Job() + StandardTestDispatcher(scheduler))
            val returned = CountDownLatch(1)
            dispatcher.arm(returned)
            val caller = scope.async { store.prepareImage(backgroundImportRequest()) }
            try {
                scheduler.runCurrent()
                awaitThemeLatch(returned)
                assertFalse(caller.isCompleted)
                assertEquals(1, managedBackgroundSources(directory).size)
                caller.cancel()
                scheduler.runCurrent()
                caller.join()
                assertNull(store.preview.value)
                assertEquals(PlaybackBackgroundSettings(), store.read().settings)
                assertEquals(0, managedBackgroundSources(directory).size)
                val retry = store.prepareImage(backgroundImportRequest())
                assertEquals(2, source.opens.get())
                store.discardPreview(retry.token)
            } finally {
                retireBackgroundTestScope(io, scope, scheduler)
            }
        }
    }

    @Test(timeout = 30_000L) fun aSaveWhoseResultDeliveryIsCancelledStillReadsBackItsExactDurableRecord() = runBlocking {
        withTimeout(15_000) {
            val directory = temporary.newFolder()
            val source = BytesThemeSource(Fixture.png())
            val dispatcher = ThemeDeliveryIoDispatcher()
            val io = LocalProviderIo(dispatcher)
            val owner = ThemeBitmapOwner()
            val store = store(directory, source, io, owner)
            val preview = store.prepareImage(backgroundImportRequest())
            val options = PlaybackBackgroundOptions(ThemeBackgroundMode.CUSTOM_IMAGE, 9, 60,
                BackgroundCrop(2f, 0f, 1f), preview.image)
            val scheduler = TestCoroutineScheduler()
            val scope = CoroutineScope(Job() + StandardTestDispatcher(scheduler))
            val returned = CountDownLatch(1)
            dispatcher.arm(returned)
            val caller = scope.async { store.compareAndSet(0, options, preview.token) }
            try {
                scheduler.runCurrent()
                awaitThemeLatch(returned)
                assertFalse(caller.isCompleted)
                assertEquals(PlaybackBackgroundSettings(1, options), store.state.value.settings)
                caller.cancel()
                scheduler.runCurrent()
                caller.join()
                val actual = store(directory, source, io, owner).read()
                assertEquals(PlaybackBackgroundSettings(1, options), actual.settings)
                assertTrue(File(directory, preview.image.id + ".image").isFile)
                assertNull(store.preview.value)
                assertEquals(1, source.opens.get())
            } finally {
                retireBackgroundTestScope(io, scope, scheduler)
            }
        }
    }

    private fun store(directory: File, source: ThemePackageOpener, io: LocalProviderIo = LocalProviderIo(),
        owner: ThemeBitmapOwner = ThemeBitmapOwner()): LocalBackgroundImages = LocalBackgroundImages(directory, io, owner, source,
        availableBytes = { Fixture.ROOM })
}

internal fun backgroundImportRequest(): CustomBackgroundImportRequest =
    CustomBackgroundImportRequest(Fixture.id(), "content://background-fixture/selected", "Original fixture.png")

internal suspend fun expectBackground(issue: PlaybackBackgroundIssue, action: suspend () -> Unit): PlaybackBackgroundException {
    try { action(); throw AssertionError("Expected background failure $issue") }
    catch (error: PlaybackBackgroundException) { assertEquals(issue, error.issue); return error }
}

internal fun managedBackgroundSources(directory: File): List<File> = checkNotNull(directory.listFiles()).filter {
    it.name.endsWith(".image") || it.name.endsWith(".part")
}

private class BlockingBackgroundSource(private val bytes: ByteArray) : ThemePackageOpener {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val opens = AtomicInteger()
    val inputCloses = AtomicInteger()
    val descriptorCloses = AtomicInteger()
    val readThread = AtomicReference<Thread?>()
    val closeThread = AtomicReference<Thread?>()
    val gateFailure = AtomicReference<Throwable?>()
    private val blocked = AtomicBoolean(false)

    override fun open(uri: String, signal: CancellationSignal): OpenedThemePackage {
        opens.incrementAndGet()
        val input = object : FilterInputStream(ByteArrayInputStream(bytes)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                readThread.set(Thread.currentThread())
                if (blocked.compareAndSet(false, true)) {
                    entered.countDown()
                    try {
                        check(release.await(5, TimeUnit.SECONDS)) { "Background fixture worker was not released" }
                    } catch (failure: Throwable) {
                        gateFailure.set(failure)
                        throw failure
                    }
                }
                return super.read(buffer, offset, length)
            }
            override fun close() {
                closeThread.set(Thread.currentThread())
                inputCloses.incrementAndGet()
                super.close()
            }
        }
        return OpenedThemePackage(input, bytes.size.toLong()) { descriptorCloses.incrementAndGet() }
    }
}
