package io.github.onlinemo.meloxwatch.data.themes

import android.graphics.Bitmap
import android.graphics.Color
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.themes.ThemeTestFixtures as Fixture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeBitmapOwnerTest {
    @Test fun nativeAllocationIsReservedBeforePublicationAndAnUnleasedCacheCanBeEvicted() {
        val owner = ThemeBitmapOwner(limitBytes = 1_024)
        val lease = nativeBackgroundBitmap(owner, "first", 16, 16) { _, _ -> Color.RED }
        assertEquals(1_024L, lease.bitmap.allocationByteCount.toLong())
        assertFalse(lease.bitmap.isMutable)
        Fixture.expect(ThemeIssue.BUSY) { owner.reserve(1) }
        assertEquals(1_024L, owner.usage().occupiedBytes)
        lease.close()
        lease.close()
        val work = owner.reserve(1)
        try {
            assertEquals(1L, owner.usage().occupiedBytes)
            assertEquals(0, owner.usage().entries)
            assertFalse(lease.bitmap.isRecycled)
        } finally { work.close() }
        assertEquals(0L, owner.usage().occupiedBytes)
    }

    @Test fun replacedNativeImageRemainsCountedUntilItsLastRealLeaseIsRetired() {
        val owner = ThemeBitmapOwner(limitBytes = 2_048)
        val first = nativeBackgroundBitmap(owner, "same", 16, 16) { _, _ -> Color.RED }
        val additional = checkNotNull(owner.acquire(first.key))
        val replacement = nativeBackgroundBitmap(owner, "same", 16, 16) { _, _ -> Color.BLUE }
        try {
            assertEquals(Color.RED, first.bitmap.getPixel(0, 0))
            assertEquals(Color.BLUE, replacement.bitmap.getPixel(0, 0))
            assertEquals(1_024L, owner.usage().retiredBytes)
            assertEquals(2_048L, owner.usage().occupiedBytes)
            first.close()
            assertEquals(1_024L, owner.usage().retiredBytes)
            additional.close()
            assertEquals(0L, owner.usage().retiredBytes)
            assertEquals(1_024L, owner.usage().occupiedBytes)
            assertFalse(first.bitmap.isRecycled)
        } finally {
            first.close()
            additional.close()
            replacement.close()
            owner.trimUnused()
        }
        assertEquals(0L, owner.usage().occupiedBytes)
    }

    @Test fun fourEntryAndFourLeaseCeilingsCannotBeBypassedBySmallImages() {
        val owner = ThemeBitmapOwner()
        val leases = (0 until 4).map { index -> nativeBackgroundBitmap(owner, "image-$index", 1, 1) { _, _ -> Color.RED } }
        try {
            Fixture.expect(ThemeIssue.BUSY) { owner.acquire(leases.first().key) }
            Fixture.expect(ThemeIssue.BUSY) { nativeBackgroundBitmap(owner, "fifth", 1, 1) { _, _ -> Color.BLUE } }
            assertEquals(4, owner.usage().entries)
            assertEquals(4, owner.usage().leases)
            leases.first().close()
            val fifth = nativeBackgroundBitmap(owner, "fifth", 1, 1) { _, _ -> Color.BLUE }
            try { assertEquals(4, owner.usage().entries) }
            finally { fifth.close() }
        } finally {
            leases.forEach(ThemeBitmapLease::close)
            owner.trimUnused()
        }
        assertEquals(0, owner.usage().leases)
        assertEquals(0L, owner.usage().occupiedBytes)
    }

    @Test fun mutableOrUnreservedNativeBytesNeverEnterTheSharedOwner() {
        val owner = ThemeBitmapOwner(limitBytes = 1_024)
        val mutable = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val reservation = owner.reserve(1_024)
        try {
            assertTrue(mutable.isMutable)
            Fixture.expect(ThemeIssue.INVALID_INPUT) { owner.publish(backgroundKey("mutable"), mutable, reservation) }
            assertEquals(0, owner.usage().entries)
            assertEquals(0, owner.usage().leases)
        } finally {
            mutable.recycle()
            reservation.close()
        }
        val small = owner.reserve(1)
        val immutable = Bitmap.createBitmap(IntArray(16 * 16), 16, 16, Bitmap.Config.ARGB_8888)
        try {
            Fixture.expect(ThemeIssue.BUSY) { owner.publish(backgroundKey("under-reserved"), immutable, small) }
            assertEquals(0, owner.usage().entries)
        } finally {
            immutable.recycle()
            small.close()
        }
        assertEquals(0L, owner.usage().occupiedBytes)
    }

    @Test fun aForeignReservationOrLeaseDoesNotSilentlyCreateASecondBudget() {
        val first = ThemeBitmapOwner()
        val second = ThemeBitmapOwner()
        val reservation = first.reserve(4)
        val bitmap = Bitmap.createBitmap(intArrayOf(Color.RED), 1, 1, Bitmap.Config.ARGB_8888)
        try {
            Fixture.expect(ThemeIssue.INVALID_INPUT) { second.publish(backgroundKey("foreign"), bitmap, reservation) }
            assertEquals(4L, first.usage().occupiedBytes)
            assertEquals(0L, second.usage().occupiedBytes)
        } finally { bitmap.recycle(); reservation.close() }
        val lease = nativeBackgroundBitmap(first, "owned", 1, 1) { _, _ -> Color.RED }
        try {
            assertTrue(first.owns(lease))
            assertFalse(second.owns(lease))
        } finally { lease.close(); first.trimUnused() }
    }
}

/** Fixture colors are external input; actual immutable bitmap bytes are reserved before creation. */
internal fun nativeBackgroundBitmap(
    owner: ThemeBitmapOwner,
    key: String,
    width: Int,
    height: Int,
    color: (Int, Int) -> Int,
): ThemeBitmapLease {
    val colors = IntArray(width * height) { index -> color(index % width, index / width) }
    val reservation = owner.reserve(width * height * 4L)
    var bitmap: Bitmap? = null
    try {
        bitmap = Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888)
        val lease = owner.publish(backgroundKey("native-test-source", key), checkNotNull(bitmap), reservation)
        bitmap = null
        return lease
    } finally {
        bitmap?.recycle()
        reservation.close()
    }
}

/** Gate owners release their latches first; deadline cancellation cannot skip worker retirement. */
internal suspend fun retireBackgroundTestScope(
    io: LocalProviderIo,
    scope: CoroutineScope,
    scheduler: TestCoroutineScheduler? = null,
    owner: ThemeBitmapOwner? = null,
    dispatcher: AutoCloseable? = null,
) = withContext(NonCancellable) {
    scope.cancel()
    scheduler?.runCurrent()
    try {
        withTimeout(5_000) {
            io.awaitIdle()
            scope.coroutineContext[Job]?.join()
        }
    } finally {
        owner?.trimUnused()
        dispatcher?.close()
    }
}
