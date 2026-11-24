package io.github.onlinemo.meloxwatch.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkException
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkIssue
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkLimits
import io.github.onlinemo.meloxwatch.data.artwork.png
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Controlled physical-channel scheduling verifies ownership; it is not a device I/O timing test. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class LocalSessionBitmapLoaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var dispatcher: QueuedDispatcher
    private lateinit var io: LocalProviderIo
    private lateinit var scope: CoroutineScope
    private lateinit var loader: LocalSessionBitmapLoader

    @Before fun createLoader() {
        dispatcher = QueuedDispatcher()
        io = LocalProviderIo(dispatcher)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        loader = LocalSessionBitmapLoader(context(), io, scope)
    }

    @After fun closeLoader() {
        loader.close()
        scope.cancel()
        while (dispatcher.pending > 0) dispatcher.runNext()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun embeddedAndContentPngUseTheSameBoundedDecoder() {
        val embedded = loader.decodeBitmap(png(8, 4))
        assertFalse(embedded.isDone)
        dispatcher.runNext()
        embedded.get(1, TimeUnit.SECONDS).let { bitmap ->
            try { assertEquals(8, bitmap.width); assertEquals(4, bitmap.height) }
            finally { bitmap.recycle() }
        }
        val provider = provider("local-session-cover", png(12, 6))
        val content = loader.loadBitmap(Uri.parse("content://local-session-cover/image"))
        dispatcher.runNext()
        content.get(1, TimeUnit.SECONDS).let { bitmap ->
            try {
                assertEquals(12, bitmap.width)
                assertEquals(6, bitmap.height)
                assertTrue(bitmap.allocationByteCount <= ArtworkLimits.BITMAP_BYTES)
            } finally { bitmap.recycle() }
        }
        assertEquals(1, provider.opens)
    }

    @Test fun httpFileAndMalformedContentNeverScheduleAProviderOrNetworkOperation() {
        listOf("https://example.invalid/image.png", "http://example.invalid/image.png", "file:///image.png", "content:/image").forEach { uri ->
            expectIssue(ArtworkIssue.SOURCE_UNAVAILABLE, loader.loadBitmap(Uri.parse(uri)))
        }
        expectIssue(ArtworkIssue.INVALID_IMAGE, loader.decodeBitmap(byteArrayOf()))
        expectIssue(ArtworkIssue.INPUT_TOO_LARGE, loader.decodeBitmap(ByteArray(ArtworkLimits.INPUT_BYTES + 1)))
        assertEquals(0, dispatcher.pending)
    }

    @Test fun onlyOnePhysicalReadAndOneWaitingTaskCanBeAccepted() {
        val first = loader.decodeBitmap(png())
        val second = loader.decodeBitmap(png())
        expectIssue(ArtworkIssue.BUSY, loader.decodeBitmap(png()))
        assertEquals(1, dispatcher.pending)
        assertFalse(second.isDone)
        loader.close()
        assertTrue(first.isCancelled)
        assertTrue(second.isCancelled)
        expectIssue(ArtworkIssue.BUSY, loader.decodeBitmap(png()))
        dispatcher.runNext()
        assertEquals(0, dispatcher.pending)
    }

    @Test fun cancellationDoesNotFreeAnIgnoringProviderUntilItsPhysicalOpenReturns() {
        val provider = provider("cancelled-session-cover", png())
        val first = loader.loadBitmap(Uri.parse("content://cancelled-session-cover/image"))
        lateinit var replacement: ListenableFuture<android.graphics.Bitmap>
        provider.beforeOpen = {
            first.cancel(false)
            replacement = loader.decodeBitmap(png(10, 5))
            assertFalse(replacement.isDone)
            assertEquals("no second physical worker while cancelled open still owns the channel", 0, dispatcher.pending)
            try { runBlocking { io.read { "overlap" } }; throw AssertionError("Expected occupied physical channel") }
            catch (error: SourceReadException) { assertEquals(SourceReadIssue.BUSY, error.issue) }
        }
        dispatcher.runNext()
        assertTrue(first.isCancelled)
        assertEquals(1, provider.opens)
        assertEquals(1, dispatcher.pending)
        dispatcher.runNext()
        replacement.get(1, TimeUnit.SECONDS).let { bitmap ->
            try { assertEquals(10, bitmap.width); assertEquals(5, bitmap.height) }
            finally { bitmap.recycle() }
        }
        assertEquals(0, dispatcher.pending)
    }

    @Test fun aCancelledScopeCompletesItsLazyFutureWithoutDispatchingIO() {
        scope.cancel()
        val result = loader.decodeBitmap(png())
        assertTrue(result.isDone)
        assertTrue(result.isCancelled)
        assertEquals(0, dispatcher.pending)
    }

    private fun provider(authority: String, bytes: ByteArray): SessionArtworkProvider {
        val file = File(temporary.root, "$authority.png").apply { writeBytes(bytes) }
        val info = ProviderInfo().apply { this.authority = authority; exported = false }
        return Robolectric.buildContentProvider(SessionArtworkProvider::class.java).create(info).get().also { it.image = file }
    }

    private fun expectIssue(issue: ArtworkIssue, future: ListenableFuture<*>) {
        try { future.get(1, TimeUnit.SECONDS); throw AssertionError("Expected $issue") }
        catch (error: ExecutionException) { assertEquals(issue, (error.cause as ArtworkException).issue) }
    }

    private fun context(): Context = RuntimeEnvironment.getApplication()

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val work = ArrayDeque<Runnable>()
        val pending: Int get() = work.size
        override fun dispatch(context: CoroutineContext, block: Runnable) { work.addLast(block) }
        fun runNext() {
            work.removeFirst().run()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }
}

/** Opens can deliberately ignore CancellationSignal; the loader must keep physical ownership. */
class SessionArtworkProvider : ContentProvider() {
    lateinit var image: File
    var beforeOpen: (() -> Unit)? = null
    var opens = 0
    override fun onCreate(): Boolean = true
    override fun getType(uri: Uri): String = "image/png"
    // Framework read mode reaches this entry through the typed */* fallback.
    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        opens += 1
        beforeOpen?.invoke()
        if (mode != "r" || uri.path != "/image" || !image.isFile) throw FileNotFoundException()
        return AssetFileDescriptor(ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_READ_ONLY), 0, image.length())
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
