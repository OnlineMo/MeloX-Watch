package io.github.onlinemo.meloxwatch.ui.themes

import android.graphics.Color
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.themes.BytesThemeSource
import io.github.onlinemo.meloxwatch.data.themes.LocalBackgroundImages
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundException
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundImageKey
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundImageSource
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundIssue
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundOptions
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundSaveResult
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundSettings
import io.github.onlinemo.meloxwatch.data.themes.ThemeBackgroundMode
import io.github.onlinemo.meloxwatch.data.themes.ThemeBitmapOwner
import io.github.onlinemo.meloxwatch.data.themes.ThemeTestFixtures
import io.github.onlinemo.meloxwatch.data.themes.backgroundImportRequest
import io.github.onlinemo.meloxwatch.data.themes.backgroundKey
import io.github.onlinemo.meloxwatch.data.themes.nativeBackgroundBitmap
import io.github.onlinemo.meloxwatch.data.themes.retireBackgroundTestScope
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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
class PlaybackBackgroundControllerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test(timeout = 30_000L) fun maskDragDuringActualDerivativeWorkDoesNotCancelOrSubmitAnotherDecode() = runBlocking {
        withTimeout(15_000) {
            val dispatcher = BackgroundPhysicalDispatcher()
            val io = LocalProviderIo(dispatcher)
            val owner = ThemeBitmapOwner()
            val source = NativeBackgroundSource(io, owner) { x, _ -> if (x < 16) Color.RED else Color.BLUE }
            val renderer = PlaybackBackgroundRenderer(io, source, owner)
            val scheduler = TestCoroutineScheduler()
            val scope = CoroutineScope(Job() + StandardTestDispatcher(scheduler))
            val controller = PlaybackBackgroundController(scope, io, renderer)
            val sourceGate = dispatcher.gateNext()
            var derivativeGate: BackgroundWorkerGate? = null
            val request = backgroundRenderRequest(ThemeBackgroundMode.COVER_BLUR, blur = 8, mask = 10)
            try {
                controller.update(request)
                controller.setVisible(true)
                scheduler.runCurrent()
                awaitBackgroundWorker(sourceGate.entered)
                sourceGate.release.countDown()
                awaitBackgroundWorker(sourceGate.returned)
                sourceGate.checkFailure()
                val next = dispatcher.gateNext()
                derivativeGate = next
                scheduler.runCurrent()
                awaitBackgroundWorker(next.entered)
                for (percent in 20..90 step 10) controller.update(request.copy(options = request.options.copy(darkMaskPercent = percent)))
                scheduler.runCurrent()
                assertEquals(2, dispatcher.submitted.get())
                assertEquals(1, source.loads.get())
                assertEquals(90, controller.state.value.darkMaskPercent)
                assertTrue(controller.state.value.loading)
                next.release.countDown()
                awaitBackgroundWorker(next.returned)
                next.checkFailure()
                scheduler.runCurrent()
                assertFalse(controller.state.value.loading)
                assertEquals(90, controller.state.value.darkMaskPercent)
                assertTrue(controller.state.value.frame?.image != null)
                assertEquals(2, dispatcher.returned.get())
            } finally {
                sourceGate.release.countDown()
                derivativeGate?.release?.countDown()
                controller.close()
                retireBackgroundTestScope(io, scope, scheduler, owner, dispatcher)
            }
        }
    }

    @Test(timeout = 30_000L) fun aRealMaskSaveKeepsTheDisplayedCustomFrameWithoutAnotherRenderOrDecode() = runBlocking {
        withTimeout(15_000) {
            val dispatcher = BackgroundPhysicalDispatcher()
            val io = LocalProviderIo(dispatcher)
            val owner = ThemeBitmapOwner()
            val original = ThemeTestFixtures.png()
            val store = LocalBackgroundImages(temporary.newFolder(), io, owner, BytesThemeSource(original),
                availableBytes = { ThemeTestFixtures.ROOM })
            val loads = AtomicInteger()
            val renders = AtomicInteger()
            val renderer = PlaybackBackgroundRenderer(io, PlaybackBackgroundImageSource { input ->
                loads.incrementAndGet()
                store.loadImage(input.key as PlaybackBackgroundImageKey.Custom, input.targetPx)
            }, owner)
            val scheduler = TestCoroutineScheduler()
            val scope = CoroutineScope(Job() + StandardTestDispatcher(scheduler))
            val controller = PlaybackBackgroundController(scope, io, {
                renders.incrementAndGet()
                renderer.render(it)
            }, 3_000)
            val gates = ArrayList<BackgroundWorkerGate>()
            try {
                val preview = store.prepareImage(backgroundImportRequest())
                val options = PlaybackBackgroundOptions(mode = ThemeBackgroundMode.CUSTOM_IMAGE, blurLevel = 8, customImage = preview.image)
                val saved = store.compareAndSet(0, options, preview.token) as PlaybackBackgroundSaveResult.Saved
                val request = backgroundRenderRequest(ThemeBackgroundMode.CUSTOM_IMAGE).copy(options = options,
                    image = PlaybackBackgroundImageKey.Custom(preview.image, settingsRevision = checkNotNull(saved.snapshot.settings).revision))
                val source = dispatcher.gateNext().also(gates::add)
                controller.update(request)
                controller.setVisible(true)
                scheduler.runCurrent()
                finishControllerWorker(source)
                val derivative = dispatcher.gateNext().also(gates::add)
                scheduler.runCurrent()
                finishControllerWorker(derivative)
                scheduler.runCurrent()
                val frame = checkNotNull(controller.state.value.frame)
                val bitmap = checkNotNull(frame.image).bitmap
                val pixels = backgroundFramePixels(frame)
                assertFalse(controller.state.value.loading)
                assertEquals(1, renders.get())
                assertEquals(1, loads.get())
                val next = store.compareAndSet(1, options.copy(darkMaskPercent = 60)) as PlaybackBackgroundSaveResult.Saved
                val nextSettings = checkNotNull(next.snapshot.settings)
                assertEquals(2L, nextSettings.revision)
                val updated = request.copy(options = nextSettings.options,
                    image = PlaybackBackgroundImageKey.Custom(preview.image, settingsRevision = nextSettings.revision))
                val submissions = dispatcher.submitted.get()
                controller.update(updated)
                assertSame(frame, controller.state.value.frame)
                assertFalse(controller.state.value.loading)
                scheduler.runCurrent()
                assertSame(frame, controller.state.value.frame)
                assertSame(bitmap, controller.state.value.frame?.image?.bitmap)
                assertTrue(pixels.contentEquals(backgroundFramePixels(checkNotNull(controller.state.value.frame))))
                assertEquals(60, controller.state.value.darkMaskPercent)
                assertEquals(updated, controller.state.value.request)
                assertEquals(1, renders.get())
                assertEquals(1, loads.get())
                assertEquals(submissions, dispatcher.submitted.get())
            } finally {
                gates.forEach { it.release.countDown() }
                controller.close()
                retireBackgroundTestScope(io, scope, scheduler, owner, dispatcher)
            }
        }
    }

    @Test(timeout = 30_000L) fun rapidSourceChangesKeepOnePhysicalWorkerAndOnlyTheLatestReplacement() = runBlocking {
        withTimeout(15_000) {
            val dispatcher = BackgroundPhysicalDispatcher()
            val io = LocalProviderIo(dispatcher)
            val owner = ThemeBitmapOwner()
            val render = BlockingNativeBackgroundRender(io, owner)
            val scheduler = TestCoroutineScheduler()
            val scope = CoroutineScope(Job() + StandardTestDispatcher(scheduler))
            val controller = PlaybackBackgroundController(scope, io, render::render, 3_000)
            val first = backgroundRenderRequest(ThemeBackgroundMode.FLUID)
            val intermediate = first.copy(image = PlaybackBackgroundImageKey.Cover(backgroundKey("intermediate")))
            val latest = first.copy(image = PlaybackBackgroundImageKey.Cover(backgroundKey("latest")))
            val firstGate = dispatcher.gateNext()
            var replacementGate: BackgroundWorkerGate? = null
            try {
                controller.update(first)
                controller.setVisible(true)
                scheduler.runCurrent()
                awaitBackgroundWorker(firstGate.entered)
                firstGate.release.countDown()
                awaitBackgroundWorker(render.entered)
                assertEquals(1, owner.usage().leases)
                controller.update(intermediate)
                controller.update(latest)
                scheduler.runCurrent()
                assertNull(controller.state.value.frame?.image)
                assertEquals(latest.renderKey(), controller.state.value.request?.renderKey())
                assertEquals(1, dispatcher.submitted.get())
                assertEquals(listOf(first.renderKey()), render.physicalRequests.toList())
                render.release.countDown()
                awaitBackgroundWorker(firstGate.returned)
                firstGate.checkFailure()
                assertNull(render.gateFailure.get())
                assertEquals(0, owner.usage().leases)
                val next = dispatcher.gateNext()
                replacementGate = next
                scheduler.runCurrent()
                awaitBackgroundWorker(next.entered)
                next.release.countDown()
                awaitBackgroundWorker(next.returned)
                next.checkFailure()
                scheduler.runCurrent()
                assertEquals(listOf(first.renderKey(), latest.renderKey()), render.physicalRequests.toList())
                assertEquals(latest.renderKey(), controller.state.value.frame?.key)
                assertEquals(1, owner.usage().leases)
                assertFalse(controller.state.value.loading)
            } finally {
                render.release.countDown()
                firstGate.release.countDown()
                replacementGate?.release?.countDown()
                controller.close()
                retireBackgroundTestScope(io, scope, scheduler, owner, dispatcher)
            }
        }
    }

    @Test(timeout = 30_000L) fun hiddenScreenRetiresAnUnresponsiveNativeResultAndStartsNoHiddenWork() = runBlocking {
        withTimeout(15_000) {
            val dispatcher = BackgroundPhysicalDispatcher()
            val io = LocalProviderIo(dispatcher)
            val owner = ThemeBitmapOwner()
            val render = BlockingNativeBackgroundRender(io, owner)
            val scheduler = TestCoroutineScheduler()
            val scope = CoroutineScope(Job() + StandardTestDispatcher(scheduler))
            val controller = PlaybackBackgroundController(scope, io, render::render, 3_000)
            val firstGate = dispatcher.gateNext()
            try {
                controller.update(backgroundRenderRequest(ThemeBackgroundMode.FLUID))
                controller.setVisible(true)
                scheduler.runCurrent()
                awaitBackgroundWorker(firstGate.entered)
                firstGate.release.countDown()
                awaitBackgroundWorker(render.entered)
                controller.setVisible(false)
                scheduler.runCurrent()
                controller.update(backgroundRenderRequest(ThemeBackgroundMode.COVER_BLUR, blur = 20))
                controller.retry()
                scheduler.advanceTimeBy(60_000)
                scheduler.runCurrent()
                assertEquals(1, dispatcher.submitted.get())
                assertFalse(controller.state.value.visible)
                assertNull(controller.state.value.frame)
                assertFalse(controller.state.value.loading)
                render.release.countDown()
                awaitBackgroundWorker(firstGate.returned)
                firstGate.checkFailure()
                assertNull(render.gateFailure.get())
                scheduler.runCurrent()
                assertEquals(0, owner.usage().leases)
                assertEquals(1, render.physicalRequests.size)
                assertEquals(1, dispatcher.submitted.get())
            } finally {
                render.release.countDown()
                firstGate.release.countDown()
                controller.close()
                retireBackgroundTestScope(io, scope, scheduler, owner, dispatcher)
            }
        }
    }

    @Test fun aBusyBudgetAtAnIdleProviderReceivesOnlyOneFiniteRetry() = runBlocking {
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(Job() + StandardTestDispatcher(scheduler))
        val attempts = AtomicInteger()
        val controller = PlaybackBackgroundController(scope, LocalProviderIo(), {
            attempts.incrementAndGet()
            throw PlaybackBackgroundException(PlaybackBackgroundIssue.BUSY)
        }, 100)
        try {
            controller.update(backgroundRenderRequest(ThemeBackgroundMode.FLUID))
            controller.setVisible(true)
            scheduler.runCurrent()
            assertEquals(2, attempts.get())
            assertEquals(PlaybackBackgroundIssue.BUSY, controller.state.value.issue)
            assertFalse(controller.state.value.loading)
            scheduler.advanceTimeBy(60_000)
            scheduler.runCurrent()
            assertEquals(2, attempts.get())
        } finally { controller.close(); scope.cancel(); scheduler.runCurrent() }
    }

    @Test(timeout = 10_000L) fun sourceCancellationCannotCreateAnAutomaticRetryLoopForAnUnchangedVisibleIntent() = runBlocking {
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(Job() + StandardTestDispatcher(scheduler))
        val attempts = AtomicInteger()
        val controller = PlaybackBackgroundController(scope, LocalProviderIo(), {
            attempts.incrementAndGet()
            throw CancellationException("The source cancelled its own request")
        }, 100)
        try {
            controller.update(backgroundRenderRequest(ThemeBackgroundMode.FLUID))
            controller.setVisible(true)
            scheduler.runCurrent()
            assertEquals(1, attempts.get())
            assertEquals(PlaybackBackgroundIssue.SOURCE_UNAVAILABLE, controller.state.value.issue)
            assertFalse(controller.state.value.loading)
            scheduler.advanceTimeBy(60_000)
            scheduler.runCurrent()
            assertEquals(1, attempts.get())
        } finally { controller.close(); scope.cancel(); scheduler.runCurrent() }
    }

    @Test fun currentDisplayLeaseIsRetiredAtCloseAndFutureIntentsCannotRestartTheNativeRenderer() = runBlocking {
        withTimeout(5_000) {
            val io = LocalProviderIo()
            val owner = ThemeBitmapOwner()
            val source = NativeBackgroundSource(io, owner) { _, _ -> Color.RED }
            val scope = CoroutineScope(Job() + Dispatchers.Default)
            val controller = PlaybackBackgroundController(scope, io, PlaybackBackgroundRenderer(io, source, owner))
            try {
                controller.update(backgroundRenderRequest(ThemeBackgroundMode.FLUID))
                controller.setVisible(true)
                val display = controller.state.first { !it.loading && it.frame?.image != null }
                val bitmap = checkNotNull(display.frame?.image).bitmap
                assertEquals(1, owner.usage().leases)
                controller.close()
                controller.update(backgroundRenderRequest(ThemeBackgroundMode.COVER_BLUR, blur = 20))
                controller.setVisible(true)
                assertNull(controller.state.value.frame)
                assertEquals(0, owner.usage().leases)
                assertEquals(1, source.loads.get())
                assertFalse(bitmap.isRecycled)
            } finally { controller.close(); retireBackgroundTestScope(io, scope, owner = owner) }
        }
    }

    @Test(timeout = 30_000L) fun aQueuedCustomRenderUsesTheRealMaskRevisionCommittedBeforeItStarts() = runBlocking {
        withTimeout(15_000) {
            val fixture = ManagedControllerBackgroundFixture(temporary.newFolder())
            try {
                val preview = fixture.store.prepareImage(backgroundImportRequest())
                val options = PlaybackBackgroundOptions(mode = ThemeBackgroundMode.CUSTOM_IMAGE,
                    blurLevel = 8, customImage = preview.image)
                val first = fixture.store.compareAndSet(0, options, preview.token) as PlaybackBackgroundSaveResult.Saved
                val firstSettings = checkNotNull(first.snapshot.settings)
                assertEquals(1L, firstSettings.revision)
                val firstRequest = savedControllerBackgroundRequest(firstSettings)
                val beforeQueue = fixture.dispatcher.submitted.get()
                fixture.controller.update(firstRequest)
                fixture.controller.setVisible(true)
                assertTrue(fixture.controller.state.value.loading)
                assertTrue(fixture.renderRequests.isEmpty())
                assertTrue(fixture.loadedKeys.isEmpty())
                assertEquals(beforeQueue, fixture.dispatcher.submitted.get())

                val saved = fixture.store.compareAndSet(1, options.copy(darkMaskPercent = 60)) as PlaybackBackgroundSaveResult.Saved
                val settings = checkNotNull(saved.snapshot.settings)
                assertEquals(2L, settings.revision)
                val current = savedControllerBackgroundRequest(settings)
                assertEquals(firstRequest.renderKey(), current.renderKey())
                fixture.controller.update(current)
                val beforeRender = fixture.dispatcher.submitted.get()
                val source = fixture.gateNext()
                fixture.scheduler.runCurrent()
                finishControllerWorker(source)
                val derivative = fixture.gateNext()
                fixture.scheduler.runCurrent()
                finishControllerWorker(derivative)
                fixture.scheduler.runCurrent()

                val display = fixture.controller.state.value
                assertFalse(display.loading)
                assertEquals(current, display.request)
                assertEquals(60, display.darkMaskPercent)
                assertNull(display.issue)
                assertTrue(display.frame?.image != null)
                assertFalse(checkNotNull(display.frame?.image).bitmap.isRecycled)
                assertEquals(listOf(current), fixture.renderRequests.toList())
                assertEquals(listOf(current.image), fixture.loadedKeys.toList())
                assertEquals(beforeRender + 2, fixture.dispatcher.submitted.get())
                assertEquals(1, fixture.owner.usage().leases)
                assertEquals(settings, fixture.store.state.value.settings)
                fixture.scheduler.advanceTimeBy(60_000)
                fixture.scheduler.runCurrent()
                assertEquals(listOf(current), fixture.renderRequests.toList())
                assertEquals(beforeRender + 2, fixture.dispatcher.submitted.get())
            } finally { fixture.close() }
        }
    }

    @Test(timeout = 30_000L) fun aNewSavedAccessRecoversAfterTheOldAccessFailureHasAlreadyCompleted() = runBlocking {
        withTimeout(15_000) {
            val fixture = ManagedControllerBackgroundFixture(temporary.newFolder())
            try {
                val preview = fixture.store.prepareImage(backgroundImportRequest())
                val options = PlaybackBackgroundOptions(mode = ThemeBackgroundMode.CUSTOM_IMAGE,
                    blurLevel = 8, customImage = preview.image)
                val first = fixture.store.compareAndSet(0, options, preview.token) as PlaybackBackgroundSaveResult.Saved
                val firstRequest = savedControllerBackgroundRequest(checkNotNull(first.snapshot.settings))
                fixture.controller.update(firstRequest)
                fixture.controller.setVisible(true)
                val saved = fixture.store.compareAndSet(1, options.copy(darkMaskPercent = 60)) as PlaybackBackgroundSaveResult.Saved
                val current = savedControllerBackgroundRequest(checkNotNull(saved.snapshot.settings))
                val staleSource = fixture.gateNext()
                fixture.scheduler.runCurrent()
                finishControllerWorker(staleSource)
                fixture.scheduler.runCurrent()
                assertEquals(PlaybackBackgroundIssue.SETTINGS_CONFLICT, fixture.controller.state.value.issue)
                assertFalse(fixture.controller.state.value.loading)
                assertNull(fixture.controller.state.value.frame?.image)
                assertEquals(listOf(firstRequest), fixture.renderRequests.toList())
                assertEquals(listOf(firstRequest.image), fixture.loadedKeys.toList())

                val beforeRecovery = fixture.dispatcher.submitted.get()
                fixture.controller.update(current)
                assertTrue(fixture.controller.state.value.loading)
                assertNull(fixture.controller.state.value.issue)
                val source = fixture.gateNext()
                fixture.scheduler.runCurrent()
                finishControllerWorker(source)
                val derivative = fixture.gateNext()
                fixture.scheduler.runCurrent()
                finishControllerWorker(derivative)
                fixture.scheduler.runCurrent()

                assertEquals(current, fixture.controller.state.value.request)
                assertEquals(60, fixture.controller.state.value.darkMaskPercent)
                assertFalse(fixture.controller.state.value.loading)
                assertNull(fixture.controller.state.value.issue)
                assertTrue(fixture.controller.state.value.frame?.image != null)
                assertEquals(listOf(firstRequest, current), fixture.renderRequests.toList())
                assertEquals(listOf(firstRequest.image, current.image), fixture.loadedKeys.toList())
                assertEquals(beforeRecovery + 2, fixture.dispatcher.submitted.get())
                fixture.controller.update(current)
                fixture.scheduler.advanceTimeBy(60_000)
                fixture.scheduler.runCurrent()
                assertEquals(2, fixture.renderRequests.size)
                assertEquals(2, fixture.loadedKeys.size)
                assertEquals(beforeRecovery + 2, fixture.dispatcher.submitted.get())
            } finally { fixture.close() }
        }
    }

    @Test(timeout = 30_000L) fun anExecutingOldAccessFailureCannotCompleteTheNewSavedPixelIntent() = runBlocking {
        withTimeout(15_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fixture = ManagedControllerBackgroundFixture(temporary.newFolder()) { key ->
                if (key.settingsRevision == 1L) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            try {
                val preview = fixture.store.prepareImage(backgroundImportRequest())
                val options = PlaybackBackgroundOptions(mode = ThemeBackgroundMode.CUSTOM_IMAGE,
                    blurLevel = 8, customImage = preview.image)
                val first = fixture.store.compareAndSet(0, options, preview.token) as PlaybackBackgroundSaveResult.Saved
                val firstRequest = savedControllerBackgroundRequest(checkNotNull(first.snapshot.settings))
                val beforeSource = fixture.dispatcher.submitted.get()
                fixture.controller.update(firstRequest)
                fixture.controller.setVisible(true)
                fixture.scheduler.runCurrent()
                assertTrue(entered.isCompleted)
                assertEquals(beforeSource, fixture.dispatcher.submitted.get())
                assertEquals(listOf(firstRequest.image), fixture.loadedKeys.toList())

                val saved = fixture.store.compareAndSet(1, options.copy(darkMaskPercent = 60)) as PlaybackBackgroundSaveResult.Saved
                val current = savedControllerBackgroundRequest(checkNotNull(saved.snapshot.settings))
                fixture.controller.update(current)
                val beforeResume = fixture.dispatcher.submitted.get()
                val staleSource = fixture.gateNext()
                release.complete(Unit)
                fixture.scheduler.runCurrent()
                finishControllerWorker(staleSource)
                val source = fixture.gateNext()
                fixture.scheduler.runCurrent()
                awaitBackgroundWorker(source.entered)
                assertEquals(current, fixture.controller.state.value.request)
                assertEquals(60, fixture.controller.state.value.darkMaskPercent)
                assertTrue(fixture.controller.state.value.loading)
                assertNull(fixture.controller.state.value.issue)
                finishControllerWorker(source)
                val derivative = fixture.gateNext()
                fixture.scheduler.runCurrent()
                finishControllerWorker(derivative)
                fixture.scheduler.runCurrent()

                assertFalse(fixture.controller.state.value.loading)
                assertNull(fixture.controller.state.value.issue)
                assertTrue(fixture.controller.state.value.frame?.image != null)
                assertEquals(listOf(firstRequest, current), fixture.renderRequests.toList())
                assertEquals(listOf(firstRequest.image, current.image), fixture.loadedKeys.toList())
                assertEquals(beforeResume + 3, fixture.dispatcher.submitted.get())
                fixture.scheduler.advanceTimeBy(60_000)
                fixture.scheduler.runCurrent()
                assertEquals(2, fixture.renderRequests.size)
                assertEquals(beforeResume + 3, fixture.dispatcher.submitted.get())
            } finally {
                release.complete(Unit)
                fixture.close()
            }
        }
    }

    @Test(timeout = 30_000L) fun aSuccessfulOldSourceSurvivesTheRealMaskSaveBeforeItsDerivativeCompletes() = runBlocking {
        withTimeout(15_000) {
            val fixture = ManagedControllerBackgroundFixture(temporary.newFolder())
            try {
                val preview = fixture.store.prepareImage(backgroundImportRequest())
                val options = PlaybackBackgroundOptions(mode = ThemeBackgroundMode.CUSTOM_IMAGE,
                    blurLevel = 8, customImage = preview.image)
                val first = fixture.store.compareAndSet(0, options, preview.token) as PlaybackBackgroundSaveResult.Saved
                val firstRequest = savedControllerBackgroundRequest(checkNotNull(first.snapshot.settings))
                fixture.controller.update(firstRequest)
                fixture.controller.setVisible(true)
                val source = fixture.gateNext()
                fixture.scheduler.runCurrent()
                finishControllerWorker(source)
                assertEquals(listOf(firstRequest.image), fixture.loadedKeys.toList())
                assertEquals(1, fixture.owner.usage().leases)

                val saved = fixture.store.compareAndSet(1, options.copy(darkMaskPercent = 60)) as PlaybackBackgroundSaveResult.Saved
                val current = savedControllerBackgroundRequest(checkNotNull(saved.snapshot.settings))
                assertEquals(firstRequest.renderKey(), current.renderKey())
                fixture.controller.update(current)
                val beforeDerivative = fixture.dispatcher.submitted.get()
                val derivative = fixture.gateNext()
                fixture.scheduler.runCurrent()
                finishControllerWorker(derivative)
                fixture.scheduler.runCurrent()

                val display = fixture.controller.state.value
                val frame = checkNotNull(display.frame)
                assertFalse(display.loading)
                assertEquals(current, display.request)
                assertEquals(60, display.darkMaskPercent)
                assertNull(display.issue)
                assertTrue(frame.image != null)
                assertEquals(current.renderKey(), frame.key)
                assertEquals(listOf(firstRequest), fixture.renderRequests.toList())
                assertEquals(listOf(firstRequest.image), fixture.loadedKeys.toList())
                assertEquals(beforeDerivative + 1, fixture.dispatcher.submitted.get())
                assertEquals(1, fixture.owner.usage().leases)
                fixture.controller.update(current)
                fixture.scheduler.advanceTimeBy(60_000)
                fixture.scheduler.runCurrent()
                assertSame(frame, fixture.controller.state.value.frame)
                assertEquals(1, fixture.renderRequests.size)
                assertEquals(1, fixture.loadedKeys.size)
                assertEquals(beforeDerivative + 1, fixture.dispatcher.submitted.get())
            } finally { fixture.close() }
        }
    }

    @Test(timeout = 30_000L) fun aQueuedPreviewUsesTheActualSavedAccessAfterConfirmationRetiresItsToken() = runBlocking {
        withTimeout(15_000) {
            val fixture = ManagedControllerBackgroundFixture(temporary.newFolder())
            try {
                val preview = fixture.store.prepareImage(backgroundImportRequest())
                val options = PlaybackBackgroundOptions(mode = ThemeBackgroundMode.CUSTOM_IMAGE,
                    blurLevel = 8, customImage = preview.image)
                val previewRequest = backgroundRenderRequest(ThemeBackgroundMode.CUSTOM_IMAGE).copy(options = options,
                    image = PlaybackBackgroundImageKey.Custom(preview.image, previewToken = preview.token))
                fixture.controller.update(previewRequest)
                fixture.controller.setVisible(true)
                assertTrue(fixture.renderRequests.isEmpty())
                val saved = fixture.store.compareAndSet(0, options.copy(darkMaskPercent = 60), preview.token) as PlaybackBackgroundSaveResult.Saved
                val settings = checkNotNull(saved.snapshot.settings)
                assertEquals(1L, settings.revision)
                assertNull(fixture.store.preview.value)
                val current = savedControllerBackgroundRequest(settings)
                assertEquals(previewRequest.renderKey(), current.renderKey())
                assertFalse(previewRequest.image?.stableKey() == current.image?.stableKey())
                fixture.controller.update(current)
                val beforeRender = fixture.dispatcher.submitted.get()
                val source = fixture.gateNext()
                fixture.scheduler.runCurrent()
                finishControllerWorker(source)
                val derivative = fixture.gateNext()
                fixture.scheduler.runCurrent()
                finishControllerWorker(derivative)
                fixture.scheduler.runCurrent()

                assertFalse(fixture.controller.state.value.loading)
                assertEquals(current, fixture.controller.state.value.request)
                assertEquals(60, fixture.controller.state.value.darkMaskPercent)
                assertNull(fixture.controller.state.value.issue)
                assertTrue(fixture.controller.state.value.frame?.image != null)
                assertEquals(listOf(current), fixture.renderRequests.toList())
                assertEquals(listOf(current.image), fixture.loadedKeys.toList())
                assertNull(fixture.loadedKeys.single().previewToken)
                assertEquals(1L, fixture.loadedKeys.single().settingsRevision)
                assertEquals(beforeRender + 2, fixture.dispatcher.submitted.get())
            } finally { fixture.close() }
        }
    }

    @Test(timeout = 30_000L) fun sourceCancellationPermitsTheNewRealAccessOnceWithoutRestartingItAutomatically() = runBlocking {
        withTimeout(15_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fixture = ManagedControllerBackgroundFixture(temporary.newFolder()) { key ->
                if (key.settingsRevision == 1L) {
                    entered.complete(Unit)
                    release.await()
                }
                throw CancellationException("Managed source cancelled its current access")
            }
            try {
                val preview = fixture.store.prepareImage(backgroundImportRequest())
                val options = PlaybackBackgroundOptions(mode = ThemeBackgroundMode.CUSTOM_IMAGE,
                    blurLevel = 8, customImage = preview.image)
                val first = fixture.store.compareAndSet(0, options, preview.token) as PlaybackBackgroundSaveResult.Saved
                val firstRequest = savedControllerBackgroundRequest(checkNotNull(first.snapshot.settings))
                fixture.controller.update(firstRequest)
                fixture.controller.setVisible(true)
                fixture.scheduler.runCurrent()
                assertTrue(entered.isCompleted)
                assertEquals(listOf(firstRequest), fixture.renderRequests.toList())
                assertTrue(fixture.controller.state.value.loading)

                val saved = fixture.store.compareAndSet(1, options.copy(darkMaskPercent = 60)) as PlaybackBackgroundSaveResult.Saved
                val current = savedControllerBackgroundRequest(checkNotNull(saved.snapshot.settings))
                fixture.controller.update(current)
                val beforeCancel = fixture.dispatcher.submitted.get()
                release.complete(Unit)
                fixture.scheduler.runCurrent()
                assertEquals(listOf(firstRequest, current), fixture.renderRequests.toList())
                assertEquals(listOf(firstRequest.image, current.image), fixture.loadedKeys.toList())
                assertEquals(current, fixture.controller.state.value.request)
                assertEquals(60, fixture.controller.state.value.darkMaskPercent)
                assertEquals(PlaybackBackgroundIssue.SOURCE_UNAVAILABLE, fixture.controller.state.value.issue)
                assertFalse(fixture.controller.state.value.loading)
                assertNull(fixture.controller.state.value.frame?.image)
                assertEquals(0, fixture.owner.usage().leases)
                assertEquals(beforeCancel, fixture.dispatcher.submitted.get())
                fixture.controller.update(current)
                fixture.scheduler.advanceTimeBy(60_000)
                fixture.scheduler.runCurrent()
                assertEquals(2, fixture.renderRequests.size)
                assertEquals(2, fixture.loadedKeys.size)
                assertEquals(beforeCancel, fixture.dispatcher.submitted.get())
            } finally {
                release.complete(Unit)
                fixture.close()
            }
        }
    }
}

private suspend fun finishControllerWorker(gate: BackgroundWorkerGate) {
    awaitBackgroundWorker(gate.entered)
    gate.release.countDown()
    awaitBackgroundWorker(gate.returned)
    gate.checkFailure()
}

private fun savedControllerBackgroundRequest(settings: PlaybackBackgroundSettings): PlaybackBackgroundRequest =
    backgroundRenderRequest(ThemeBackgroundMode.CUSTOM_IMAGE).copy(options = settings.options,
        image = PlaybackBackgroundImageKey.Custom(checkNotNull(settings.options.customImage), settingsRevision = settings.revision))

/** 共用真实私有图像/CAS/native renderer；调度 gate 只决定交付次序，不代替源校验。 */
private class ManagedControllerBackgroundFixture(
    directory: File,
    beforeLoad: suspend (PlaybackBackgroundImageKey.Custom) -> Unit = {},
) {
    val dispatcher = BackgroundPhysicalDispatcher()
    val io = LocalProviderIo(dispatcher)
    val owner = ThemeBitmapOwner()
    val store = LocalBackgroundImages(directory, io, owner, BytesThemeSource(ThemeTestFixtures.png()),
        availableBytes = { ThemeTestFixtures.ROOM })
    val loadedKeys = ConcurrentLinkedQueue<PlaybackBackgroundImageKey.Custom>()
    val renderRequests = ConcurrentLinkedQueue<PlaybackBackgroundRequest>()
    val renderer = PlaybackBackgroundRenderer(io, PlaybackBackgroundImageSource { input ->
        val key = input.key as PlaybackBackgroundImageKey.Custom
        loadedKeys += key
        beforeLoad(key)
        store.loadImage(key, input.targetPx)
    }, owner)
    val scheduler = TestCoroutineScheduler()
    val scope = CoroutineScope(Job() + StandardTestDispatcher(scheduler))
    val controller = PlaybackBackgroundController(scope, io, { request ->
        renderRequests += request
        renderer.render(request)
    }, 3_000)
    private val gates = ArrayList<BackgroundWorkerGate>()

    fun gateNext(): BackgroundWorkerGate {
        val gate = dispatcher.gateNext()
        gates += gate
        return gate
    }

    suspend fun close() {
        gates.forEach { it.release.countDown() }
        controller.close()
        retireBackgroundTestScope(io, scope, scheduler, owner, dispatcher)
    }
}

/** An actual native result deliberately ignores cancellation while the one physical action runs. */
private class BlockingNativeBackgroundRender(private val io: LocalProviderIo, private val owner: ThemeBitmapOwner) {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val physicalRequests = ConcurrentLinkedQueue<String>()
    val gateFailure = AtomicReference<Throwable?>()
    private val first = AtomicBoolean(true)
    suspend fun render(request: PlaybackBackgroundRequest): BackgroundFrameLease = io.read(abandoned = BackgroundFrameLease::close) {
        physicalRequests += request.renderKey()
        val lease = nativeBackgroundBitmap(owner, request.renderKey(), 32, 32) { _, _ -> Color.RED }
        var transferred = false
        try {
            if (first.compareAndSet(true, false)) {
                entered.countDown()
                try {
                    check(release.await(5, TimeUnit.SECONDS)) { "Unresponsive native fixture was not released" }
                } catch (failure: Throwable) {
                    gateFailure.set(failure)
                    throw failure
                }
            }
            val result = BackgroundFrameLease(request.renderKey(), request.options.mode,
                request.snapshot.effectiveManifest.palette, request.snapshot.effectiveManifest.palette.background, lease)
            transferred = true
            result
        } finally { if (!transferred) lease.close() }
    }
}
