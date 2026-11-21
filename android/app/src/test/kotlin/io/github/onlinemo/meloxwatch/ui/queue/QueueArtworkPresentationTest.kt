package io.github.onlinemo.meloxwatch.ui.queue

import android.graphics.Bitmap
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkLease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Lease transitions only; these do not exercise Compose layout, Lifecycle or provider cancellation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class QueueArtworkPresentationTest {
    @Test(timeout = 5000L)
    fun aLateLeaseAfterRetirementIsClosedWithoutPublication() {
        val cell = QueueArtworkPresentation()
        var releases = 0
        val lease = artwork { releases++ }
        cell.retire()
        assertFalse(cell.publish(lease))
        assertNull(cell.lease)
        assertEquals(1, releases)
        cell.release(lease)
        cell.retire()
        assertEquals(1, releases)
    }

    @Test(timeout = 5000L)
    fun oldRequestCleanupCannotClearANewerPresentation() {
        val oldRequest = QueueArtworkPresentation()
        val newRequest = QueueArtworkPresentation()
        var oldReleases = 0
        var newReleases = 0
        val oldLease = artwork { oldReleases++ }
        val newLease = artwork { newReleases++ }
        assertTrue(oldRequest.publish(oldLease))
        assertTrue(newRequest.publish(newLease))
        oldRequest.retire()
        oldRequest.release(oldLease)
        assertNull(oldRequest.lease)
        assertSame(newLease, newRequest.lease)
        assertEquals(1, oldReleases)
        assertEquals(0, newReleases)
        newRequest.retire()
        assertEquals(1, newReleases)
    }

    @Test(timeout = 5000L)
    fun stoppedLifecycleCleanupIsIdempotentAfterANewLeaseWasAttached() {
        val cell = QueueArtworkPresentation()
        var oldReleases = 0
        var newReleases = 0
        val oldLease = artwork { oldReleases++ }
        val newLease = artwork { newReleases++ }
        assertTrue(cell.publish(oldLease))
        cell.release(oldLease)
        assertTrue(cell.publish(newLease))
        cell.release(oldLease)
        assertSame(newLease, cell.lease)
        assertEquals(1, oldReleases)
        assertEquals(0, newReleases)
        cell.retire()
        assertEquals(1, newReleases)
    }

    @Test(timeout = 5000L)
    fun repeatedTrackInstancesHaveIndependentDisplayLeases() {
        val first = QueueArtworkPresentation()
        val second = QueueArtworkPresentation()
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        var releases = 0
        val firstLease = ArtworkLease(bitmap, "synthetic-shared-cover") { releases++ }
        val secondLease = ArtworkLease(bitmap, "synthetic-shared-cover") { releases++ }
        first.publish(firstLease)
        second.publish(secondLease)
        first.retire()
        assertSame(secondLease, second.lease)
        assertEquals(1, releases)
        second.retire()
        assertEquals(2, releases)
    }

    private fun artwork(release: () -> Unit): ArtworkLease =
        ArtworkLease(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888), "synthetic-cover", release)
}
