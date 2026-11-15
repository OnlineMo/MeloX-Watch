package io.github.onlinemo.meloxwatch.data.artwork

import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.OperationCanceledException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class ArtworkBoundsTest {
    @Test fun pixelDimensionsAndTargetSizeAreValidatedBeforeDecodeAllocation() {
        assertEquals(8, BoundedArtworkDecoder.sample(4000, 4000, ArtworkLimits.OUTPUT_EDGE))
        assertEquals(128, BoundedArtworkDecoder.sample(8000, 2000, 96))
        expect(ArtworkIssue.INPUT_TOO_LARGE) { BoundedArtworkDecoder.sample(4001, 4000, 96) }
        expect(ArtworkIssue.INPUT_TOO_LARGE) { BoundedArtworkDecoder.sample(Int.MAX_VALUE, Int.MAX_VALUE, 96) }
        expect(ArtworkIssue.INVALID_IMAGE) { BoundedArtworkDecoder.sample(0, 1, 96) }
        expect(ArtworkIssue.INVALID_IMAGE) { BoundedArtworkDecoder.sample(1, 1, 0) }
        expect(ArtworkIssue.INVALID_IMAGE) { BoundedArtworkDecoder.sample(1, 1, ArtworkLimits.OUTPUT_EDGE + 1) }
    }

    @Test fun unknownEncodedInputStopsAtTheLimitPlusOneUsingBoundedChunks() {
        var readBytes = 0
        var largest = 0
        val input = object : InputStream() {
            override fun read(): Int { readBytes++; return 1 }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                largest = maxOf(largest, length)
                buffer.fill(1, offset, offset + length)
                readBytes += length
                return length
            }
        }
        expect(ArtworkIssue.INPUT_TOO_LARGE) { readArtworkBytes(input, CancellationSignal()) }
        assertEquals(ArtworkLimits.INPUT_BYTES + 1, readBytes)
        assertTrue(largest <= 64 * 1024)
    }

    @Test fun completeBytesAreKeptAndEmptyOrCancelledInputCannotBecomeACover() {
        val bytes = ByteArray(100_001) { (it % 251).toByte() }
        assertArrayEquals(bytes, readArtworkBytes(ByteArrayInputStream(bytes), CancellationSignal()))
        expect(ArtworkIssue.NO_ARTWORK) { readArtworkBytes(ByteArrayInputStream(byteArrayOf()), CancellationSignal()) }
        val signal = CancellationSignal().apply { cancel() }
        try { readArtworkBytes(ByteArrayInputStream(bytes), signal); throw AssertionError("Expected cancellation") }
        catch (_: OperationCanceledException) { }
    }

    @Test fun actualPngDecodeHonorsRotationAndOutputBudgets() {
        val bytes = png(40, 20)
        val decoded = BoundedArtworkDecoder.decode(bytes, 96, CancellationSignal(), 90)
        try {
            assertEquals(20, decoded.width)
            assertEquals(40, decoded.height)
            assertTrue(decoded.allocationByteCount <= ArtworkLimits.BITMAP_BYTES)
        } finally { decoded.recycle() }
        expect(ArtworkIssue.INVALID_IMAGE) { BoundedArtworkDecoder.decode(bytes, 96, CancellationSignal(), 45) }
        expect(ArtworkIssue.INPUT_TOO_LARGE) {
            BoundedArtworkDecoder.decode(ByteArray(ArtworkLimits.INPUT_BYTES + 1), 96, CancellationSignal())
        }
    }

    private fun expect(issue: ArtworkIssue, action: () -> Any?) {
        try { action(); throw AssertionError("Expected $issue") }
        catch (error: ArtworkException) { assertEquals(issue, error.issue) }
    }
}

internal fun png(width: Int = 8, height: Int = 4): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    return try { ByteArrayOutputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray() } }
    finally { bitmap.recycle() }
}
