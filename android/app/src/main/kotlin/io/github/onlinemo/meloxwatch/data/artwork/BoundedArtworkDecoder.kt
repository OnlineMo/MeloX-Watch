package io.github.onlinemo.meloxwatch.data.artwork

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.CancellationSignal
import java.io.InputStream

internal object BoundedArtworkDecoder {
    fun decode(bytes: ByteArray, targetPx: Int, signal: CancellationSignal, orientation: Int = 0): Bitmap {
        if (bytes.size > ArtworkLimits.INPUT_BYTES) throw ArtworkException(ArtworkIssue.INPUT_TOO_LARGE)
        signal.throwIfCanceled()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sample = sample(bounds.outWidth, bounds.outHeight, targetPx)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
            inScaled = false
        }
        var bitmap: Bitmap? = null
        try {
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw ArtworkException(ArtworkIssue.INVALID_IMAGE)
            signal.throwIfCanceled()
            if (maxOf(bitmap.width, bitmap.height) > ArtworkLimits.OUTPUT_EDGE || bitmap.allocationByteCount > ArtworkLimits.BITMAP_BYTES) {
                throw ArtworkException(ArtworkIssue.INPUT_TOO_LARGE)
            }
            val rotation = (orientation % 360 + 360) % 360
            if (rotation !in setOf(0, 90, 180, 270)) throw ArtworkException(ArtworkIssue.INVALID_IMAGE)
            if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated !== bitmap) { bitmap.recycle(); bitmap = rotated }
            }
            signal.throwIfCanceled()
            return bitmap
        } catch (error: OutOfMemoryError) {
            bitmap?.recycle()
            throw ArtworkException(ArtworkIssue.LOW_MEMORY, error)
        } catch (error: Exception) {
            bitmap?.recycle()
            throw error
        }
    }

    fun sample(width: Int, height: Int, targetPx: Int): Int {
        if (targetPx !in 1..ArtworkLimits.OUTPUT_EDGE) throw ArtworkException(ArtworkIssue.INVALID_IMAGE)
        if (width <= 0 || height <= 0) throw ArtworkException(ArtworkIssue.INVALID_IMAGE)
        if (width.toLong() * height > ArtworkLimits.INPUT_PIXELS) throw ArtworkException(ArtworkIssue.INPUT_TOO_LARGE)
        var sample = 1
        while (true) {
            val outWidth = (width.toLong() + sample - 1) / sample
            val outHeight = (height.toLong() + sample - 1) / sample
            if (maxOf(outWidth, outHeight) <= targetPx && outWidth * outHeight * 4 <= ArtworkLimits.BITMAP_BYTES) return sample
            sample *= 2
        }
    }
}

/** Unknown lengths use bounded chunks; no unbounded ByteArrayOutputStream or provider rewind. */
internal fun readArtworkBytes(input: InputStream, signal: CancellationSignal): ByteArray {
    val chunks = ArrayList<ByteArray>(ArtworkLimits.INPUT_BYTES / (64 * 1024))
    var total = 0
    while (true) {
        signal.throwIfCanceled()
        val chunk = ByteArray(minOf(64 * 1024, ArtworkLimits.INPUT_BYTES - total + 1))
        var count = 0
        while (count < chunk.size) {
            val read = input.read(chunk, count, chunk.size - count)
            if (read < 0) break
            if (read == 0) throw ArtworkException(ArtworkIssue.INVALID_IMAGE)
            count += read
            if (total + count > ArtworkLimits.INPUT_BYTES) throw ArtworkException(ArtworkIssue.INPUT_TOO_LARGE)
            signal.throwIfCanceled()
        }
        if (count == 0) break
        chunks += if (count == chunk.size) chunk else chunk.copyOf(count)
        total += count
        if (count < chunk.size) break
    }
    if (total == 0) throw ArtworkException(ArtworkIssue.NO_ARTWORK)
    val result = ByteArray(total)
    var offset = 0
    for (chunk in chunks) { System.arraycopy(chunk, 0, result, offset, chunk.size); offset += chunk.size }
    return result
}
