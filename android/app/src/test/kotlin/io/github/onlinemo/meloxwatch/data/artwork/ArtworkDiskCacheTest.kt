package io.github.onlinemo.meloxwatch.data.artwork

import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.OperationCanceledException
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class ArtworkDiskCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)

    @Test fun completedPngAndItsAccessOrderSurviveRestart() {
        val directory = File(temporary.root, "cache")
        val cache = ArtworkDiskCache(directory)
        put(cache, a)
        put(cache, b)
        val expected = cache.load(a, CancellationSignal())!!
        val restarted = ArtworkDiskCache(directory)
        assertArrayEquals(expected, restarted.load(a, CancellationSignal()))
        assertNotNull(restarted.load(b, CancellationSignal()))
        assertTrue(restarted.occupiedBytes() > 0)
        assertTrue(restarted.occupiedBytes() <= ArtworkLimits.DISK_BYTES)
        assertTrue(directory.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test fun anInitializationFailureCanBeRetriedWithoutDeletingPreviouslyIndexedCovers() {
        val directory = File(temporary.root, "cache")
        val cache = ArtworkDiskCache(directory)
        put(cache, a)
        put(cache, b)
        val expected = cache.load(a, CancellationSignal())!!
        val outside = File(temporary.root, "outside").apply { writeText("not cache data") }
        val symlink = File(directory, "unexpected-link")
        Files.createSymbolicLink(symlink.toPath(), outside.toPath())
        val restarted = ArtworkDiskCache(directory)
        try { restarted.load(a, CancellationSignal()); throw AssertionError("Expected confined-cache failure") }
        catch (error: ArtworkException) { assertEquals(ArtworkIssue.CACHE_UNAVAILABLE, error.issue) }
        Files.delete(symlink.toPath())
        assertArrayEquals(expected, restarted.load(a, CancellationSignal()))
        assertNotNull(restarted.load(b, CancellationSignal()))
        assertEquals("not cache data", outside.readText())
    }

    @Test fun cancellationKeepsOldEntriesAndLengthMismatchRetiresOnlyThatEntry() {
        val directory = File(temporary.root, "cache")
        val cache = ArtworkDiskCache(directory)
        put(cache, a)
        put(cache, b)
        val cancelled = CancellationSignal().apply { cancel() }
        try { ArtworkDiskCache(directory).load(a, cancelled); throw AssertionError("Expected cancellation") }
        catch (_: OperationCanceledException) { }
        val restarted = ArtworkDiskCache(directory)
        assertNotNull(restarted.load(a, CancellationSignal()))
        File(directory, "$a.png").appendBytes(byteArrayOf(9))
        assertNull(restarted.load(a, CancellationSignal()))
        assertNotNull(restarted.load(b, CancellationSignal()))
    }

    private fun put(cache: ArtworkDiskCache, key: String) {
        val bitmap = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888)
        try { cache.put(key, bitmap, CancellationSignal()) } finally { bitmap.recycle() }
    }
}
