package io.github.onlinemo.meloxwatch.data.local

import android.os.CancellationSignal
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class ContentFingerprintTest {
    @Test fun fullUnknownLengthReadProducesActualDigestAndSameSizeReplacementDiffers() {
        val first = byteArrayOf(1, 2, 3, 4)
        val expected = "sha256:" + MessageDigest.getInstance("SHA-256").digest(first).joinToString("") { "%02x".format(it.toInt() and 255) }
        val token = ContentFingerprint.read(ByteArrayInputStream(first), CancellationSignal(), null)
        assertEquals(expected, token)
        assertNotEquals(token, ContentFingerprint.read(ByteArrayInputStream(byteArrayOf(4, 3, 2, 1)), CancellationSignal(), 4))
    }

    @Test fun largeUnknownInputDoesNotEmitPrefixProofOrReadTheWholeSource() {
        var readBytes = 0
        var largest = 0
        val source = object : InputStream() {
            override fun read(): Int { readBytes++; return 1 }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                largest = maxOf(largest, length)
                buffer.fill(1, offset, offset + length)
                readBytes += length
                return length
            }
        }
        assertNull(ContentFingerprint.read(source, CancellationSignal(), null))
        assertEquals(LocalInputLimits.READ_PROOF_BYTES + 1, readBytes)
        assertTrue(largest <= 64 * 1024)
    }

    @Test fun misleadingLengthIsSourceChanged() {
        try {
            ContentFingerprint.read(ByteArrayInputStream(byteArrayOf(1, 2, 3)), CancellationSignal(), 4)
            throw AssertionError("Expected source change")
        } catch (error: SourceReadException) { assertEquals(SourceReadIssue.SOURCE_CHANGED, error.issue) }
    }
}
