package io.github.onlinemo.meloxwatch.ui.themes

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.themes.BackgroundCrop
import io.github.onlinemo.meloxwatch.data.themes.BackgroundImageRef
import io.github.onlinemo.meloxwatch.data.themes.BackgroundRoundClip
import io.github.onlinemo.meloxwatch.data.themes.BackgroundViewport
import io.github.onlinemo.meloxwatch.data.themes.BuiltinTheme
import io.github.onlinemo.meloxwatch.data.themes.BytesThemeSource
import io.github.onlinemo.meloxwatch.data.themes.LocalBackgroundImages
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundImageKey
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundImageRequest
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundImageSource
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundIssue
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundLimits
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundOptions
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundSaveResult
import io.github.onlinemo.meloxwatch.data.themes.ThemeBackgroundMode
import io.github.onlinemo.meloxwatch.data.themes.ThemeBitmapLease
import io.github.onlinemo.meloxwatch.data.themes.ThemeBitmapOwner
import io.github.onlinemo.meloxwatch.data.themes.ThemeBuiltins
import io.github.onlinemo.meloxwatch.data.themes.ThemeChoice
import io.github.onlinemo.meloxwatch.data.themes.ThemeSnapshot
import io.github.onlinemo.meloxwatch.data.themes.ThemeTestFixtures
import io.github.onlinemo.meloxwatch.data.themes.backgroundKey
import io.github.onlinemo.meloxwatch.data.themes.backgroundImportRequest
import io.github.onlinemo.meloxwatch.data.themes.nativeBackgroundBitmap
import io.github.onlinemo.meloxwatch.data.themes.retireBackgroundTestScope
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
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
import org.junit.Assert.assertSame
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
class PlaybackBackgroundRendererTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun defaultAndBlackHaveRealDistinctLightThemePixelsWithoutLoadingAnImage() = runBlocking {
        val owner = ThemeBitmapOwner()
        val source = PlaybackBackgroundImageSource { throw AssertionError("Solid background must not load an image") }
        val renderer = PlaybackBackgroundRenderer(LocalProviderIo(), source, owner)
        val defaults = renderer.render(backgroundRenderRequest(ThemeBackgroundMode.DEFAULT, theme = BuiltinTheme.LIGHT))
        val black = renderer.render(backgroundRenderRequest(ThemeBackgroundMode.BLACK, theme = BuiltinTheme.LIGHT))
        try {
            assertEquals(Color.WHITE, drawnBackgroundPixels(defaults, backgroundRenderRequest(ThemeBackgroundMode.DEFAULT, theme = BuiltinTheme.LIGHT))[0])
            assertEquals(Color.BLACK, drawnBackgroundPixels(black, backgroundRenderRequest(ThemeBackgroundMode.BLACK, theme = BuiltinTheme.LIGHT))[0])
            assertNull(defaults.image)
            assertNull(black.image)
            assertEquals(0L, owner.usage().occupiedBytes)
        } finally { defaults.close(); black.close() }
    }

    @Test fun coverBlurChangesActualPixelsAndMaskOnlyChangesNeverReloadOrRecalculateThem() = runBlocking {
        val io = LocalProviderIo()
        val owner = ThemeBitmapOwner()
        val source = NativeBackgroundSource(io, owner) { x, _ -> if (x < 16) Color.RED else Color.BLUE }
        val renderer = PlaybackBackgroundRenderer(io, source, owner)
        val request = backgroundRenderRequest(ThemeBackgroundMode.COVER_BLUR, blur = 0, mask = 0)
        val sharp = renderer.render(request)
        val blurredRequest = request.copy(options = request.options.copy(blurLevel = 8))
        val blurred = renderer.render(blurredRequest)
        val maskedRequest = blurredRequest.copy(options = blurredRequest.options.copy(darkMaskPercent = 80))
        val masked = renderer.render(maskedRequest)
        try {
            assertFalse(backgroundFramePixels(sharp).contentEquals(backgroundFramePixels(blurred)))
            assertTrue(blurred.image?.bitmap === masked.image?.bitmap)
            assertEquals(blurredRequest.renderKey(), maskedRequest.renderKey())
            assertEquals(2, source.loads.get())
            assertEquals(1, source.allocations.get())
            val before = drawnBackgroundPixels(blurred, blurredRequest)
            val after = drawnBackgroundPixels(masked, maskedRequest)
            assertTrue(Color.red(after[16 * 32 + 8]) < Color.red(before[16 * 32 + 8]))
            assertTrue(Color.blue(after[16 * 32 + 24]) < Color.blue(before[16 * 32 + 24]))
        } finally { sharp.close(); blurred.close(); masked.close(); owner.trimUnused() }
    }

    @Test fun staticFluidHasRealColorRegionsAndIgnoresBlurWithoutAnyNewWork() = runBlocking {
        val io = LocalProviderIo()
        val owner = ThemeBitmapOwner()
        val source = NativeBackgroundSource(io, owner) { x, y ->
            when { y >= 16 -> Color.BLUE; x < 16 -> Color.RED; else -> Color.GREEN }
        }
        val renderer = PlaybackBackgroundRenderer(io, source, owner)
        val request = backgroundRenderRequest(ThemeBackgroundMode.FLUID, mask = 0)
        val first = renderer.render(request)
        val unsupportedBlur = renderer.render(request.copy(options = request.options.copy(blurLevel = 20)))
        try {
            val pixels = backgroundFramePixels(first)
            assertTrue(pixels.toSet().size > 64)
            assertTrue(Color.red(pixels[2 * 32 + 2]) > Color.red(pixels[2 * 32 + 29]))
            assertTrue(Color.green(pixels[2 * 32 + 29]) > Color.green(pixels[2 * 32 + 2]))
            assertTrue(first.image?.bitmap === unsupportedBlur.image?.bitmap)
            assertEquals(1, source.loads.get())
            assertEquals(1, source.allocations.get())
        } finally { first.close(); unsupportedBlur.close(); owner.trimUnused() }
    }

    @Test fun customZoomAndAnchorsSelectDifferentRealSourcePixelsWithoutChangingTheSource() = runBlocking {
        val io = LocalProviderIo()
        val owner = ThemeBitmapOwner()
        val source = NativeBackgroundSource(io, owner, width = 64, height = 32) { x, _ -> Color.rgb(x * 4, 20, 40) }
        val renderer = PlaybackBackgroundRenderer(io, source, owner)
        val request = backgroundRenderRequest(ThemeBackgroundMode.CUSTOM_IMAGE, mask = 0)
        val leftRequest = request.copy(options = request.options.copy(crop = BackgroundCrop(2f, 0f, 0.5f)))
        val rightRequest = request.copy(options = request.options.copy(crop = BackgroundCrop(2f, 1f, 0.5f)))
        val left = renderer.render(leftRequest)
        val right = renderer.render(rightRequest)
        try {
            assertTrue(Color.red(backgroundFramePixels(left)[16 * 32 + 16]) < Color.red(backgroundFramePixels(right)[16 * 32 + 16]))
            val sourceLease = checkNotNull(source.last.get())
            assertEquals(Color.rgb(0, 20, 40), sourceLease.bitmap.getPixel(0, 0))
            assertEquals(Color.rgb(252, 20, 40), sourceLease.bitmap.getPixel(63, 0))
            assertEquals(1, source.allocations.get())
        } finally { left.close(); right.close(); owner.trimUnused() }
    }

    @Test fun maskExtremesAreDrawnFromTheSameImageAndCannotChangeValidatedOpaqueColors() = runBlocking {
        val io = LocalProviderIo()
        val owner = ThemeBitmapOwner()
        val source = NativeBackgroundSource(io, owner) { _, _ -> Color.rgb(160, 100, 60) }
        val renderer = PlaybackBackgroundRenderer(io, source, owner)
        val request = backgroundRenderRequest(ThemeBackgroundMode.CUSTOM_IMAGE, mask = 0)
        val frame = renderer.render(request)
        try {
            val plain = drawnBackgroundPixels(frame, request)
            val half = drawnBackgroundPixels(frame, request.copy(options = request.options.copy(darkMaskPercent = 50)))
            val dark = drawnBackgroundPixels(frame, request.copy(options = request.options.copy(darkMaskPercent = 100)))
            assertEquals(Color.rgb(160, 100, 60), plain[16 * 32 + 16])
            assertTrue(Color.red(half[16 * 32 + 16]) in 78..82)
            assertEquals(Color.BLACK, dark[16 * 32 + 16])
            assertEquals(1, source.loads.get())
            assertEquals(255, Color.alpha(frame.palette.surface))
            assertEquals(255, Color.alpha(frame.palette.textPrimary))
        } finally { frame.close(); owner.trimUnused() }
    }

    @Test fun roundRectangularAndImeViewsUsePhysicalCircleGeometryAndCorrectClipping() = runBlocking {
        val io = LocalProviderIo()
        val owner = ThemeBitmapOwner()
        val source = NativeBackgroundSource(io, owner) { _, y -> Color.rgb(120, y * 8, 40) }
        val renderer = PlaybackBackgroundRenderer(io, source, owner)
        val fullRequest = backgroundRenderRequest(ThemeBackgroundMode.CUSTOM_IMAGE, mask = 0,
            viewport = BackgroundViewport(32, 32, BackgroundRoundClip(16f, 16f, 16f)))
        val imeRequest = fullRequest.copy(viewport = BackgroundViewport(32, 20, fullRequest.viewport.roundClip))
        val rectangleRequest = fullRequest.copy(viewport = BackgroundViewport(32, 32))
        val full = renderer.render(fullRequest)
        val ime = renderer.render(imeRequest)
        val rectangle = renderer.render(rectangleRequest)
        try {
            val roundPixels = drawnBackgroundPixels(full, fullRequest)
            val imePixels = drawnBackgroundPixels(ime, imeRequest)
            val rectangularPixels = drawnBackgroundPixels(rectangle, rectangleRequest)
            assertEquals(Color.BLACK, roundPixels[0])
            assertTrue(rectangularPixels[0] != Color.BLACK)
            assertEquals(roundPixels[16 * 32 + 16], imePixels[16 * 32 + 16])
            assertEquals(fullRequest.viewport.roundClip, imeRequest.viewport.roundClip)
            assertTrue(rectangularPixels.last() != Color.BLACK)
        } finally { full.close(); ime.close(); rectangle.close(); owner.trimUnused() }
    }

    @Test fun aLargerActualWindowProducesABoundedDerivativeRatherThanADeviceWhitelist() = runBlocking {
        val io = LocalProviderIo()
        val owner = ThemeBitmapOwner()
        val source = NativeBackgroundSource(io, owner, width = 256, height = 256) { _, _ -> Color.RED }
        val renderer = PlaybackBackgroundRenderer(io, source, owner)
        val request = backgroundRenderRequest(ThemeBackgroundMode.COVER_BLUR, viewport = BackgroundViewport(1_024, 2_048))
        val frame = renderer.render(request)
        try {
            val bitmap = checkNotNull(frame.image).bitmap
            assertEquals(256, bitmap.width)
            assertEquals(PlaybackBackgroundLimits.DERIVED_EDGE, bitmap.height)
            assertTrue(bitmap.width.toLong() * bitmap.height <= PlaybackBackgroundLimits.DERIVED_PIXELS)
            assertTrue(owner.usage().occupiedBytes <= 8L * 1024 * 1024)
        } finally { frame.close(); owner.trimUnused() }
    }

    @Test fun missingImagesAndRealMemoryFailuresHaveStableSolidFallbacks() = runBlocking {
        val owner = ThemeBitmapOwner()
        val request = backgroundRenderRequest(ThemeBackgroundMode.COVER_BLUR).copy(image = null)
        val missing = PlaybackBackgroundRenderer(LocalProviderIo(), PlaybackBackgroundImageSource {
            throw AssertionError("A missing key must not load an image")
        }, owner).render(request)
        val memory = PlaybackBackgroundRenderer(LocalProviderIo(), PlaybackBackgroundImageSource {
            throw OutOfMemoryError("Controlled allocation failure")
        }, owner).render(request.copy(image = PlaybackBackgroundImageKey.Cover(backgroundKey("cover"))))
        try {
            assertEquals(PlaybackBackgroundIssue.NO_IMAGE, missing.issue)
            assertEquals(PlaybackBackgroundIssue.LOW_MEMORY, memory.issue)
            assertNull(missing.image)
            assertNull(memory.image)
            assertEquals(missing.baseColor, memory.baseColor)
            assertEquals(0L, owner.usage().occupiedBytes)
        } finally { missing.close(); memory.close() }
    }

    @Test fun aForeignImageOwnerIsRejectedAndItsLeaseIsRetired() = runBlocking {
        val io = LocalProviderIo()
        val expected = ThemeBitmapOwner()
        val foreign = ThemeBitmapOwner()
        val source = NativeBackgroundSource(io, foreign) { _, _ -> Color.RED }
        val renderer = PlaybackBackgroundRenderer(io, source, expected)
        val frame = renderer.render(backgroundRenderRequest(ThemeBackgroundMode.FLUID))
        try {
            assertEquals(PlaybackBackgroundIssue.INVALID_INPUT, frame.issue)
            assertNull(frame.image)
            assertEquals(0, foreign.usage().leases)
            assertEquals(0L, expected.usage().occupiedBytes)
        } finally { frame.close(); foreign.trimUnused() }
    }

    @Test fun cachedCustomDerivativeCannotBypassTheSavedPrivateCopyIntegrityCheck() = runBlocking {
        val io = LocalProviderIo()
        val owner = ThemeBitmapOwner()
        val directory = temporary.newFolder()
        val original = ThemeTestFixtures.png()
        val store = LocalBackgroundImages(directory, io, owner, BytesThemeSource(original), availableBytes = { ThemeTestFixtures.ROOM })
        val preview = store.prepareImage(backgroundImportRequest())
        val options = PlaybackBackgroundOptions(mode = ThemeBackgroundMode.CUSTOM_IMAGE, customImage = preview.image)
        val saved = store.compareAndSet(0, options, preview.token) as PlaybackBackgroundSaveResult.Saved
        val request = backgroundRenderRequest(ThemeBackgroundMode.CUSTOM_IMAGE).copy(options = options,
            image = PlaybackBackgroundImageKey.Custom(preview.image, settingsRevision = checkNotNull(saved.snapshot.settings).revision))
        val renderer = PlaybackBackgroundRenderer(io, PlaybackBackgroundImageSource { input ->
            store.loadImage(input.key as PlaybackBackgroundImageKey.Custom, input.targetPx)
        }, owner)
        val valid = renderer.render(request)
        try {
            assertTrue(valid.image != null)
            File(directory, preview.image.id + ".image").writeBytes(ByteArray(original.size))
            val damaged = renderer.render(request)
            try {
                assertEquals(PlaybackBackgroundIssue.INTEGRITY_FAILED, damaged.issue)
                assertNull(damaged.image)
                assertEquals(preview.image, store.read().effective.customImage)
            } finally { damaged.close() }
        } finally { valid.close(); owner.trimUnused() }
    }

    @Test(timeout = 30_000L) fun durableMaskRevisionReusesCustomPixelsAfterValidatingTheCurrentRecordAndImage() = runBlocking {
        withTimeout(15_000) {
            val io = LocalProviderIo()
            val owner = ThemeBitmapOwner()
            val directory = temporary.newFolder()
            val original = ThemeTestFixtures.png()
            val provider = BytesThemeSource(original)
            val store = LocalBackgroundImages(directory, io, owner, provider, availableBytes = { ThemeTestFixtures.ROOM })
            val preview = store.prepareImage(backgroundImportRequest())
            val options = PlaybackBackgroundOptions(mode = ThemeBackgroundMode.CUSTOM_IMAGE, blurLevel = 8, customImage = preview.image)
            val saved = store.compareAndSet(0, options, preview.token) as PlaybackBackgroundSaveResult.Saved
            val request = backgroundRenderRequest(ThemeBackgroundMode.CUSTOM_IMAGE).copy(options = options,
                image = PlaybackBackgroundImageKey.Custom(preview.image, settingsRevision = checkNotNull(saved.snapshot.settings).revision))
            val loads = AtomicInteger()
            val renderer = PlaybackBackgroundRenderer(io, PlaybackBackgroundImageSource { input ->
                loads.incrementAndGet()
                store.loadImage(input.key as PlaybackBackgroundImageKey.Custom, input.targetPx)
            }, owner)
            val first = renderer.render(request)
            try {
                assertNull(first.issue)
                val originalPixels = backgroundFramePixels(first)
                val occupied = owner.usage().occupiedBytes
                val masked = options.copy(darkMaskPercent = 60)
                val next = store.compareAndSet(1, masked) as PlaybackBackgroundSaveResult.Saved
                val nextSettings = checkNotNull(next.snapshot.settings)
                assertEquals(2L, nextSettings.revision)
                assertEquals(masked, nextSettings.options)
                val updated = request.copy(options = nextSettings.options,
                    image = PlaybackBackgroundImageKey.Custom(preview.image, settingsRevision = nextSettings.revision))
                val reused = renderer.render(updated)
                try {
                    assertNull(reused.issue)
                    assertEquals(request.renderKey(), updated.renderKey())
                    assertSame(first.image?.bitmap, reused.image?.bitmap)
                    assertTrue(originalPixels.contentEquals(backgroundFramePixels(reused)))
                    assertEquals(occupied, owner.usage().occupiedBytes)
                    assertEquals(2, loads.get())
                    assertTrue(Color.red(drawnBackgroundPixels(reused, updated)[16 * 32 + 16]) <
                        Color.red(drawnBackgroundPixels(first, request)[16 * 32 + 16]))
                    val stale = renderer.render(request)
                    try {
                        assertEquals(PlaybackBackgroundIssue.SETTINGS_CONFLICT, stale.issue)
                        assertNull(stale.image)
                        assertEquals(3, loads.get())
                    } finally { stale.close() }
                    File(directory, preview.image.id + ".image").writeBytes(ByteArray(original.size))
                    val damaged = renderer.render(updated)
                    try {
                        assertEquals(PlaybackBackgroundIssue.INTEGRITY_FAILED, damaged.issue)
                        assertNull(damaged.image)
                        assertEquals(4, loads.get())
                        assertEquals(masked, store.read().effective)
                        assertTrue(provider.bytes.contentEquals(original))
                    } finally { damaged.close() }
                } finally { reused.close() }
            } finally { first.close(); owner.trimUnused() }
        }
    }

    @Test(timeout = 30_000L) fun cancellingBeforeDerivativeActionKeepsSourceOwnedUntilTheRealWorkerReturns() = runBlocking {
        withTimeout(15_000) {
            val dispatcher = BackgroundPhysicalDispatcher()
            val io = LocalProviderIo(dispatcher)
            val owner = ThemeBitmapOwner()
            val source = NativeBackgroundSource(io, owner) { _, _ -> Color.RED }
            val renderer = PlaybackBackgroundRenderer(io, source, owner)
            val scheduler = TestCoroutineScheduler()
            val scope = CoroutineScope(Job() + StandardTestDispatcher(scheduler))
            val sourceGate = dispatcher.gateNext()
            val caller = scope.async { renderer.render(backgroundRenderRequest(ThemeBackgroundMode.FLUID)) }
            var derivativeGate: BackgroundWorkerGate? = null
            try {
                scheduler.runCurrent()
                awaitBackgroundWorker(sourceGate.entered)
                sourceGate.release.countDown()
                awaitBackgroundWorker(sourceGate.returned)
                sourceGate.checkFailure()
                derivativeGate = dispatcher.gateNext()
                scheduler.runCurrent()
                awaitBackgroundWorker(derivativeGate.entered)
                assertEquals(1, owner.usage().leases)
                caller.cancel()
                scheduler.runCurrent()
                caller.join()
                assertEquals(1, owner.usage().leases)
                derivativeGate.release.countDown()
                awaitBackgroundWorker(derivativeGate.returned)
                derivativeGate.checkFailure()
                assertEquals(0, owner.usage().leases)
                assertEquals(2, dispatcher.returned.get())
                assertTrue(dispatcher.workerThread.get() !== Thread.currentThread())
            } finally {
                sourceGate.release.countDown()
                derivativeGate?.release?.countDown()
                retireBackgroundTestScope(io, scope, scheduler, owner, dispatcher)
            }
        }
    }
}

internal fun backgroundRenderRequest(
    mode: ThemeBackgroundMode,
    blur: Int = 0,
    mask: Int = 50,
    theme: BuiltinTheme = BuiltinTheme.DARK,
    viewport: BackgroundViewport = BackgroundViewport(32, 32),
): PlaybackBackgroundRequest {
    val choice = ThemeChoice.Builtin(theme)
    val snapshot = ThemeSnapshot(0, choice, null, choice, emptyList(), effectiveManifest = ThemeBuiltins.manifest(theme))
    val image = BackgroundImageRef("6a28e8b8-2857-46af-888b-ec5bcfb628fe", backgroundKey("fixture-image"), 1, "image/png", 32, 32)
    val key = if (mode == ThemeBackgroundMode.CUSTOM_IMAGE) {
        PlaybackBackgroundImageKey.Custom(image, previewToken = "4f3c5db9-5106-4776-b8d9-2e681829a4de")
    } else PlaybackBackgroundImageKey.Cover(backgroundKey("fixture-cover"))
    return PlaybackBackgroundRequest(snapshot, PlaybackBackgroundOptions(mode, blur, mask,
        customImage = if (mode == ThemeBackgroundMode.CUSTOM_IMAGE) image else null), key, viewport)
}

internal class NativeBackgroundSource(
    private val io: LocalProviderIo,
    private val owner: ThemeBitmapOwner,
    private val width: Int = 32,
    private val height: Int = 32,
    private val color: (Int, Int) -> Int,
) : PlaybackBackgroundImageSource {
    val loads = AtomicInteger()
    val allocations = AtomicInteger()
    val last = AtomicReference<ThemeBitmapLease?>()
    override suspend fun load(request: PlaybackBackgroundImageRequest): ThemeBitmapLease {
        loads.incrementAndGet()
        val name = request.key.stableKey() + ":" + request.targetPx + ":" + width + ":" + height
        return io.read(abandoned = ThemeBitmapLease::close) { signal ->
            signal.throwIfCanceled()
            val key = backgroundKey("native-test-source", name)
            val lease = owner.acquire(key) ?: nativeBackgroundBitmap(owner, name, width, height, color).also { allocations.incrementAndGet() }
            last.set(lease)
            lease
        }
    }
}

internal fun backgroundFramePixels(frame: BackgroundFrameLease): IntArray {
    val bitmap = checkNotNull(frame.image).bitmap
    return IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
}

internal fun drawnBackgroundPixels(frame: BackgroundFrameLease, request: PlaybackBackgroundRequest): IntArray {
    val width = request.viewport.widthPx
    val height = request.viewport.heightPx
    val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, ComposeCanvas(output.asImageBitmap()), Size(width.toFloat(), height.toFloat())) {
            drawWatchPlaybackBackground(PlaybackBackgroundDisplay(request, frame, visible = true))
        }
        return IntArray(width * height).also { output.getPixels(it, 0, width, 0, 0, width, height) }
    } finally { output.recycle() }
}

internal class BackgroundWorkerGate {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val returned = CountDownLatch(1)
    internal val failure = AtomicReference<Throwable?>()
    fun checkFailure() { failure.get()?.let { throw AssertionError("Physical background worker failed", it) } }
}

/** Each gate acknowledges the complete physical Runnable, including result publication. */
internal class BackgroundPhysicalDispatcher : CoroutineDispatcher(), AutoCloseable {
    private val next = AtomicReference<BackgroundWorkerGate?>()
    private val executor = Executors.newSingleThreadExecutor { action -> Thread(action, "background-test-physical-io").apply { isDaemon = true } }
    val submitted = AtomicInteger()
    val returned = AtomicInteger()
    val workerThread = AtomicReference<Thread?>()
    fun gateNext(): BackgroundWorkerGate = BackgroundWorkerGate().also { check(next.compareAndSet(null, it)) }
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val gate = next.getAndSet(null)
        submitted.incrementAndGet()
        executor.execute {
            workerThread.compareAndSet(null, Thread.currentThread())
            try {
                gate?.entered?.countDown()
                if (gate != null) check(gate.release.await(5, TimeUnit.SECONDS)) { "Physical background worker gate timed out" }
                block.run()
            } catch (error: Throwable) {
                if (gate == null) throw error else gate.failure.set(error)
            } finally {
                returned.incrementAndGet()
                gate?.returned?.countDown()
            }
        }
    }
    override fun close() {
        next.getAndSet(null)?.release?.countDown()
        executor.shutdown()
        check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Background physical dispatcher did not stop" }
    }
}

internal suspend fun awaitBackgroundWorker(checkpoint: CountDownLatch) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    check(checkpoint.await(5, TimeUnit.SECONDS)) { "Background physical worker checkpoint was not reached" }
}
