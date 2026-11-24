package io.github.onlinemo.meloxwatch.data.themes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.themes.ThemeTestFixtures as Fixture
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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
class ThemePreviewImageNativeTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun maskTintContractProducesActualForegroundPixelsWithoutCopyingOrChangingTheSourceBitmap() = runBlocking {
        val icon = Fixture.Asset("play", "assets/play.png", Fixture.png(icon = true))
        val value = Fixture.packageOf(assets = listOf(icon), icons = mapOf("play" to icon.id))
        val repository = Fixture.repository(temporary.newFolder(), BytesThemeSource(value.zip()))
        val preview = repository.prepareImport(Fixture.request())
        val lease = repository.loadPreviewImage(preview.token, icon.id, 32)
        val output = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            val tint = checkNotNull(lease.tintColor)
            assertEquals(preview.manifest.palette.textPrimary, tint)
            val paint = Paint().apply { colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN) }
            Canvas(output).drawBitmap(lease.bitmap, 0f, 0f, paint)
            assertEquals(tint, output.getPixel(16, 16))
            assertEquals(0, Color.alpha(output.getPixel(0, 0)))
            assertEquals(Color.RED, lease.bitmap.getPixel(16, 16))
        } finally {
            output.recycle()
            lease.close()
        }
        assertTrue(lease.bitmap.isRecycled)
        lease.close()
        repository.discardPreview(preview.token)
    }

    @Test fun twoTemporaryImagesAreBoundedAndClosingEitherLeaseMakesItsSlotAvailable() = runBlocking {
        val image = Fixture.Asset("cover", "assets/cover.png", Fixture.png())
        val value = Fixture.packageOf(assets = listOf(image))
        val repository = Fixture.repository(temporary.newFolder(), BytesThemeSource(value.zip()))
        val preview = repository.prepareImport(Fixture.request())
        val first = repository.loadPreviewImage(preview.token, image.id, 32)
        val second = repository.loadPreviewImage(preview.token, image.id, 32)
        try {
            assertNull(first.tintColor)
            assertNull(second.tintColor)
            Fixture.expectSuspending(ThemeIssue.BUSY) { repository.loadPreviewImage(preview.token, image.id, 32) }
            first.close()
            first.close()
            val third = repository.loadPreviewImage(preview.token, image.id, 32)
            try {
                assertFalse(third.bitmap.isRecycled)
                assertTrue(first.bitmap.isRecycled)
            } finally {
                third.close()
            }
        } finally {
            first.close()
            second.close()
        }
        repository.discardPreview(preview.token)
    }

    @Test(timeout = 30000L) fun cancellingAfterDecodeBeforeResultDeliveryReturnsItsRealBitmapSlot() = runBlocking {
        withTimeout(15000) {
            val image = Fixture.Asset("cover", "assets/cover.png", Fixture.png())
            val value = Fixture.packageOf(assets = listOf(image))
            val dispatcher = ThemeDeliveryIoDispatcher()
            val io = LocalProviderIo(dispatcher)
            val repository = Fixture.repository(temporary.newFolder(), BytesThemeSource(value.zip()), io)
            val preview = repository.prepareImport(Fixture.request())
            val scheduler = TestCoroutineScheduler()
            val scope = CoroutineScope(Job() + StandardTestDispatcher(scheduler))
            val done = CountDownLatch(1)
            dispatcher.arm(done)
            val caller = scope.async { repository.loadPreviewImage(preview.token, image.id, 32) }
            try {
                scheduler.runCurrent()
                awaitThemeLatch(done)
                assertFalse(caller.isCompleted)
                caller.cancel()
                scheduler.runCurrent()
                caller.join()
                val first = repository.loadPreviewImage(preview.token, image.id, 32)
                val second = repository.loadPreviewImage(preview.token, image.id, 32)
                try {
                    Fixture.expectSuspending(ThemeIssue.BUSY) { repository.loadPreviewImage(preview.token, image.id, 32) }
                } finally {
                    first.close()
                    second.close()
                }
                repository.discardPreview(preview.token)
            } finally {
                scope.cancel()
                scheduler.runCurrent()
                io.awaitIdle()
            }
        }
    }
}
