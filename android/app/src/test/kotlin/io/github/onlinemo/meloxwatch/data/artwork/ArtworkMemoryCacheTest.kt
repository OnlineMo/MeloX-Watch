package io.github.onlinemo.meloxwatch.data.artwork

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class ArtworkMemoryCacheTest {
    @Test fun pinnedAndRetiredDisplayFramesShareTheSameBudgetAndAreNotRecycled() {
        val cache = ArtworkMemoryCache(limitBytes = 32, limitEntries = 2)
        val old = cache.putAndAcquire("a", bitmap())
        val other = cache.putAndAcquire("b", bitmap())
        assertEquals(32L, cache.occupiedBytes())
        val rejected = bitmap()
        try { cache.putAndAcquire("c", rejected); throw AssertionError("Expected pinned-budget rejection") }
        catch (error: ArtworkException) { assertEquals(ArtworkIssue.BUSY, error.issue) }
        finally { rejected.recycle() }
        other.close()
        val replacement = cache.putAndAcquire("a", bitmap())
        assertEquals(32L, cache.occupiedBytes())
        assertFalse(old.bitmap.isRecycled)
        old.close()
        old.close()
        assertEquals(16L, cache.occupiedBytes())
        assertFalse(old.bitmap.isRecycled)
        cache.trimUnused()
        assertEquals(16L, cache.occupiedBytes())
        replacement.close()
        cache.trimUnused()
        assertEquals(0L, cache.occupiedBytes())
        assertFalse(replacement.bitmap.isRecycled)
    }

    @Test fun lruEvictsOnlyUnusedEntriesAndLeaseCountIsBounded() {
        val cache = ArtworkMemoryCache(limitBytes = 64, limitEntries = 2)
        val first = cache.putAndAcquire("a", bitmap()).also { it.close() }
        cache.putAndAcquire("b", bitmap()).close()
        cache.acquire("a")!!.use { assertSame(first.bitmap, it.bitmap) }
        cache.putAndAcquire("c", bitmap()).close()
        assertNull(cache.acquire("b"))
        val leases = List(ArtworkLimits.LIVE_LEASES) { cache.acquire("a")!! }
        try {
            try { cache.acquire("a"); throw AssertionError("Expected lease bound") }
            catch (error: ArtworkException) { assertEquals(ArtworkIssue.BUSY, error.issue) }
        } finally { leases.forEach { it.close() } }
        cache.acquire("a")!!.close()
        cache.trimUnused()
        assertEquals(0L, cache.occupiedBytes())
    }

    private fun bitmap() = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
}
