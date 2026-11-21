package io.github.onlinemo.meloxwatch.data.themes

import android.graphics.Color
import io.github.onlinemo.meloxwatch.data.fonts.AndroidLyricsFontDecoder
import io.github.onlinemo.meloxwatch.data.fonts.FontWeightSupport
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontLimits
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontRole
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontTestFixtures
import io.github.onlinemo.meloxwatch.data.fonts.nativeFontPixels
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.themes.ThemeTestFixtures as Fixture
import io.github.onlinemo.meloxwatch.ui.themes.PlaybackBackgroundRenderer
import io.github.onlinemo.meloxwatch.ui.themes.PlaybackBackgroundRequest
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class AppliedThemeResourcesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun imageAndIconRemainLoadableFromActualInstallationAfterPreviewIsGone() = runBlocking {
        val image = Fixture.Asset("picture", "assets/picture.png", Fixture.png())
        val icon = Fixture.Asset("play", "assets/play.png", Fixture.png(icon = true))
        val value = Fixture.packageOf(assets = listOf(image, icon), icons = mapOf("play" to icon.id))
        val installed = install(value)
        assertNull(installed.repository.preview.value)
        val resources = AppliedThemeResources(installed.directory, installed.io, installed.owner)
        val picture = resources.loadImage(installed.snapshot, image.id, 32)
        val mask = resources.loadImage(installed.snapshot, icon.id, 32)
        try {
            assertEquals(Color.rgb(40, 90, 140), picture.bitmap.getPixel(16, 16))
            assertNull(picture.tintColor)
            assertEquals(Color.RED, mask.bitmap.getPixel(16, 16))
            assertEquals(installed.snapshot.effectiveManifest.palette.textPrimary, mask.tintColor)
            assertEquals(0, Color.alpha(mask.bitmap.getPixel(0, 0)))
            assertEquals(2, installed.owner.usage().leases)
        } finally { picture.close(); mask.close() }
        val afterRestart = AppliedThemeResources(installed.directory, installed.io, installed.owner)
            .loadImage(installed.repository.read(), image.id, 32)
        try { assertEquals(picture.key, afterRestart.key) }
        finally { afterRestart.close() }
    }

    @Test fun bothInstalledFontRolesProduceRealGlyphsWithoutRetainingTheThemePreview() = runBlocking {
        val original = Fixture.font(resourceId = "font.original")
        val translation = Fixture.font(LyricsFontTestFixtures.STATIC, "font.translation")
        val installed = install(Fixture.packageOf(assets = listOf(original, translation),
            fonts = mapOf("original" to original.id, "translation" to translation.id)))
        val resources = AppliedThemeResources(installed.directory, installed.io, installed.owner)
        val decoder = AndroidLyricsFontDecoder()
        val first = resources.consumeFont(installed.snapshot, LyricsFontRole.ORIGINAL, {}) { file, info, capability, signal ->
            signal.throwIfCanceled()
            assertEquals(FontWeightSupport.AVAILABLE, capability.support)
            assertTrue(checkNotNull(capability.observation).changed)
            decoder.decode(file, info, capability, 700f)
        }
        val second = resources.consumeFont(installed.snapshot, LyricsFontRole.TRANSLATION, {}) { file, info, capability, signal ->
            signal.throwIfCanceled()
            assertEquals(FontWeightSupport.NO_AXIS, capability.support)
            decoder.decode(file, info, capability, null)
        }
        assertNotNull(first)
        assertNotNull(second)
        assertTrue(nativeFontPixels(checkNotNull(first).typeface).any { Color.alpha(it) != 0 })
        assertTrue(nativeFontPixels(checkNotNull(second).typeface).any { Color.alpha(it) != 0 })
        assertEquals(0L, installed.owner.usage().occupiedBytes)
        assertNull(installed.repository.preview.value)
    }

    @Test fun builtinFontFallbackDoesNotCreateOrReadAnInstalledResourceDirectory() = runBlocking {
        val directory = File(temporary.root, Fixture.id())
        val resources = AppliedThemeResources(directory, LocalProviderIo(), ThemeBitmapOwner())
        val snapshot = ThemeSnapshot(0, ThemeBuiltins.defaultChoice, null, ThemeBuiltins.defaultChoice, emptyList())
        val result = resources.consumeFont(snapshot, LyricsFontRole.ORIGINAL, {}) { _, _, _, _ ->
            throw AssertionError("Built-in font must not invoke the installed-font consumer")
        }
        assertNull(result)
        assertFalse(directory.exists())
    }

    @Test fun cachedImageCannotBypassTheActualCatalogRevisionAfterAThemeSwitch() = runBlocking {
        val image = Fixture.Asset("picture", "assets/picture.png", Fixture.png())
        val installed = install(Fixture.packageOf(assets = listOf(image)))
        val resources = AppliedThemeResources(installed.directory, installed.io, installed.owner)
        val original = resources.loadImage(installed.snapshot, image.id, 32)
        try {
            val light = installed.repository.previewChoice(ThemeChoice.Builtin(BuiltinTheme.LIGHT))
            installed.repository.apply(Fixture.apply(light))
            Fixture.expectSuspending(ThemeIssue.REVISION_CONFLICT) { resources.loadImage(installed.snapshot, image.id, 32) }
            assertEquals(1, installed.owner.usage().leases)
            assertEquals(Color.rgb(40, 90, 140), original.bitmap.getPixel(16, 16))
        } finally { original.close() }
    }

    @Test(timeout = 30_000L) fun aCatalogOnlyCommitReusesVerifiedSourceAndDerivativePixelsButRejectsStaleAccess() = runBlocking {
        withTimeout(15_000) {
            val image = Fixture.Asset("picture", "assets/picture.png", Fixture.png())
            val installed = install(Fixture.packageOf(assets = listOf(image)))
            val resources = AppliedThemeResources(installed.directory, installed.io, installed.owner)
            val decoded = resources.loadImage(installed.snapshot, image.id, 32)
            val sourceBitmap = decoded.bitmap
            val sourceCacheKey = decoded.key
            decoded.close()
            val oldKey = PlaybackBackgroundImageKey.Theme(installed.snapshot, image.id)
            val request = PlaybackBackgroundRequest(installed.snapshot, PlaybackBackgroundOptions(mode = ThemeBackgroundMode.FLUID),
                oldKey, BackgroundViewport(32, 32))
            val renderer = PlaybackBackgroundRenderer(installed.io, PlaybackBackgroundImageSource { input ->
                val key = input.key as PlaybackBackgroundImageKey.Theme
                resources.loadImage(key.snapshot, key.resourceId, input.targetPx)
            }, installed.owner)
            val first = renderer.render(request)
            try {
                assertNull(first.issue)
                val occupied = installed.owner.usage().occupiedBytes
                val preview = installed.repository.previewChoice(installed.snapshot.effective)
                val actual = installed.repository.apply(Fixture.apply(preview)).snapshot
                assertEquals(checkNotNull(installed.snapshot.revision) + 1, actual.revision)
                assertEquals(installed.snapshot.effective, actual.effective)
                assertEquals(installed.snapshot.effectiveManifest, actual.effectiveManifest)
                val newKey = PlaybackBackgroundImageKey.Theme(actual, image.id)
                assertFalse(oldKey.stableKey() == newKey.stableKey())
                assertEquals(oldKey.pixelKey(), newKey.pixelKey())
                val currentSource = resources.loadImage(actual, image.id, 32)
                try {
                    assertEquals(sourceCacheKey, currentSource.key)
                    assertSame(sourceBitmap, currentSource.bitmap)
                } finally { currentSource.close() }
                val updated = request.copy(snapshot = actual, image = newKey)
                val reused = renderer.render(updated)
                try {
                    assertNull(reused.issue)
                    assertEquals(request.renderKey(), updated.renderKey())
                    assertSame(first.image?.bitmap, reused.image?.bitmap)
                    assertEquals(occupied, installed.owner.usage().occupiedBytes)
                    Fixture.expectSuspending(ThemeIssue.REVISION_CONFLICT) {
                        resources.loadImage(installed.snapshot, image.id, 32)
                    }
                    val stale = renderer.render(request)
                    try {
                        assertEquals(PlaybackBackgroundIssue.SETTINGS_CONFLICT, stale.issue)
                        assertNull(stale.image)
                    } finally { stale.close() }
                    val file = resourceFile(installed, image.path)
                    val bytes = file.readBytes()
                    bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                    file.writeBytes(bytes)
                    Fixture.expectSuspending(ThemeIssue.INTEGRITY_FAILED) { resources.loadImage(actual, image.id, 32) }
                    val damaged = renderer.render(updated)
                    try {
                        assertEquals(PlaybackBackgroundIssue.INVALID_IMAGE, damaged.issue)
                        assertNull(damaged.image)
                    } finally { damaged.close() }
                } finally { reused.close() }
            } finally { first.close(); installed.owner.trimUnused() }
        }
    }

    @Test fun modifiedResourceBytesAreRejectedEvenWhenTheOriginalBitmapIsCached() = runBlocking {
        val image = Fixture.Asset("picture", "assets/picture.png", Fixture.png())
        val installed = install(Fixture.packageOf(assets = listOf(image)))
        val resources = AppliedThemeResources(installed.directory, installed.io, installed.owner)
        val original = resources.loadImage(installed.snapshot, image.id, 32)
        original.close()
        val file = resourceFile(installed, image.path)
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        Fixture.expectSuspending(ThemeIssue.INTEGRITY_FAILED) { resources.loadImage(installed.snapshot, image.id, 32) }
        assertEquals(0, installed.owner.usage().leases)
    }

    @Test fun installedPayloadLinksAreRejectedWithoutFollowingTheirTarget() = runBlocking {
        val image = Fixture.Asset("picture", "assets/picture.png", Fixture.png())
        val installed = install(Fixture.packageOf(assets = listOf(image)))
        val file = resourceFile(installed, image.path)
        val outside = temporary.newFile()
        outside.writeBytes(file.readBytes())
        Files.delete(file.toPath())
        Files.createSymbolicLink(file.toPath(), outside.toPath())
        val resources = AppliedThemeResources(installed.directory, installed.io, installed.owner)
        Fixture.expectSuspending(ThemeIssue.LINK_OR_SPECIAL_FILE) { resources.loadImage(installed.snapshot, image.id, 32) }
        assertTrue(outside.readBytes().contentEquals(image.bytes))
    }

    @Test(timeout = 30_000L) fun fontResultCancelledAfterPhysicalWorkerReturnIsAbandonedExactlyOnce() = runBlocking {
        withTimeout(15_000) {
            val dispatcher = ThemeDeliveryIoDispatcher()
            val io = LocalProviderIo(dispatcher)
            val font = Fixture.font()
            val installed = install(Fixture.packageOf(assets = listOf(font), fonts = mapOf("original" to font.id)), io)
            val resources = AppliedThemeResources(installed.directory, io, installed.owner)
            val scheduler = TestCoroutineScheduler()
            val scope = CoroutineScope(Job() + StandardTestDispatcher(scheduler))
            val returned = CountDownLatch(1)
            val workerThread = AtomicReference<Thread?>()
            val abandoned = AtomicInteger()
            val callerThread = Thread.currentThread()
            dispatcher.arm(returned)
            val caller = scope.async {
                resources.consumeFont(installed.snapshot, LyricsFontRole.ORIGINAL, { abandoned.incrementAndGet() }) { file, info, capability, signal ->
                    workerThread.set(Thread.currentThread())
                    signal.throwIfCanceled()
                    AndroidLyricsFontDecoder().decode(file, info, capability, null)
                }
            }
            try {
                scheduler.runCurrent()
                awaitThemeLatch(returned)
                assertFalse(caller.isCompleted)
                assertTrue(workerThread.get() !== callerThread)
                caller.cancel()
                scheduler.runCurrent()
                caller.join()
                assertEquals(1, abandoned.get())
                assertEquals(0L, installed.owner.usage().occupiedBytes)
                assertEquals(installed.snapshot.revision, installed.repository.state.value.revision)
            } finally {
                retireBackgroundTestScope(io, scope, scheduler)
            }
        }
    }

    private suspend fun install(value: Fixture.Package, io: LocalProviderIo = LocalProviderIo()): Installation {
        val directory = temporary.newFolder()
        val owner = ThemeBitmapOwner()
        val repository = Fixture.repository(directory, BytesThemeSource(value.zip()), io)
        val preview = repository.prepareImport(Fixture.request())
        val snapshot = repository.apply(Fixture.apply(preview,
            acknowledgment = if (preview.manifest.fonts.isEmpty()) null else LyricsFontLimits.LICENSE_ACKNOWLEDGMENT_VERSION)).snapshot
        return Installation(directory, io, owner, repository, snapshot)
    }

    private fun resourceFile(installed: Installation, path: String): File {
        val ref = (installed.snapshot.effective as ThemeChoice.Installed).ref
        return File(ThemeDirectoryStore(installed.directory).installationDirectory(ref.installationId), path)
    }

    private data class Installation(val directory: File, val io: LocalProviderIo, val owner: ThemeBitmapOwner,
        val repository: LocalThemeRepository, val snapshot: ThemeSnapshot)
}
