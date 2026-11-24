package io.github.onlinemo.meloxwatch.playback

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkException
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkIssue
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkLimits
import io.github.onlinemo.meloxwatch.data.artwork.BoundedArtworkDecoder
import io.github.onlinemo.meloxwatch.data.artwork.readArtworkBytes
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Session artwork shares the local decoder limits; the Media3 default also permits HTTP. */
@UnstableApi
internal class LocalSessionBitmapLoader(
    context: Context,
    private val io: LocalProviderIo,
    private val scope: CoroutineScope,
) : BitmapLoader, AutoCloseable {
    private val applicationContext = context.applicationContext
    private val targetPx = maxOf(
        context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width),
        context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_height),
    ).coerceIn(1, ArtworkLimits.OUTPUT_EDGE)
    private val lock = Any()
    private val tasks = LinkedHashMap<ListenableFuture<Bitmap>, Job>()
    private val serial = Mutex()
    private var closed = false

    override fun supportsMimeType(mimeType: String): Boolean = Util.isBitmapFactorySupportedMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        if (data.isEmpty()) return failure(ArtworkIssue.INVALID_IMAGE)
        if (data.size > ArtworkLimits.INPUT_BYTES) return failure(ArtworkIssue.INPUT_TOO_LARGE)
        return submit { signal -> BoundedArtworkDecoder.decode(data, targetPx, signal) }
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        if (uri.scheme != "content" || uri.authority.isNullOrBlank()) return failure(ArtworkIssue.SOURCE_UNAVAILABLE)
        return submit { signal ->
            signal.throwIfCanceled()
            val asset = applicationContext.contentResolver.openAssetFileDescriptor(uri, "r", signal)
                ?: throw FileNotFoundException()
            val bytes = asset.use { descriptor ->
                if (descriptor.declaredLength > ArtworkLimits.INPUT_BYTES) throw ArtworkException(ArtworkIssue.INPUT_TOO_LARGE)
                descriptor.createInputStream().use { input ->
                    // Cancellation interrupts provider opening and closes an already returned stream.
                    // A queued replacement cannot create a second concurrent decoder or reader.
                    signal.setOnCancelListener { try { input.close() } catch (_: Exception) { } }
                    try { readArtworkBytes(input, signal) }
                    finally { signal.setOnCancelListener(null) }
                }
            }
            signal.throwIfCanceled()
            BoundedArtworkDecoder.decode(bytes, targetPx, signal)
        }
    }

    override fun close() {
        val running = synchronized(lock) {
            if (closed) return
            closed = true
            tasks.toMap()
        }
        running.forEach { (future, job) -> job.cancel(); future.cancel(false) }
    }

    private fun submit(operation: (CancellationSignal) -> Bitmap): ListenableFuture<Bitmap> = synchronized(lock) {
        if (closed || tasks.size >= 2) return failure(ArtworkIssue.BUSY)
        val future = SettableFuture.create<Bitmap>()
        val signal = CancellationSignal()
        val job = scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.LAZY) {
            try {
                val bitmap = serial.withLock { readShared(signal, operation) }
                if (!future.set(bitmap)) bitmap.recycle()
            } catch (error: CancellationException) {
                future.cancel(false)
                throw error
            } catch (error: Exception) {
                future.setException(error)
            }
        }
        tasks[future] = job
        future.addListener({ if (future.isCancelled) job.cancel() }, MoreExecutors.directExecutor())
        job.invokeOnCompletion {
            if (!future.isDone) future.cancel(false)
            synchronized(lock) { tasks.remove(future) }
        }
        job.start()
        future
    }

    private suspend fun readShared(signal: CancellationSignal, operation: (CancellationSignal) -> Bitmap): Bitmap {
        // Library thumbnails, scanning and this session use the same physical provider/decode
        // channel. A provider ignoring cancellation still holds it, so a replacement cannot overlap.
        return withTimeoutOrNull(ARTWORK_TIMEOUT_MS) {
            repeat(3) { attempt ->
                currentCoroutineContext().ensureActive()
                try { return@withTimeoutOrNull io.read(signal, abandoned = Bitmap::recycle, action = operation) }
                catch (error: SourceReadException) {
                    if (error.issue != SourceReadIssue.BUSY || attempt == 2) throw error
                    io.awaitIdle()
                }
            }
            throw ArtworkException(ArtworkIssue.BUSY)
        } ?: throw ArtworkException(ArtworkIssue.SOURCE_UNAVAILABLE)
    }

    private fun failure(issue: ArtworkIssue): ListenableFuture<Bitmap> = Futures.immediateFailedFuture(ArtworkException(issue))

    companion object { private const val ARTWORK_TIMEOUT_MS = 5000L }
}
