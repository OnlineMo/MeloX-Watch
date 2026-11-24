package io.github.onlinemo.meloxwatch.data.themes

import android.graphics.Bitmap
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkLimits
import java.util.concurrent.atomic.AtomicBoolean

/** One injected owner accounts for source, work buffers, cache, display and retired leases. */
class ThemeBitmapOwner(
    private val limitBytes: Long = ArtworkLimits.MEMORY_BYTES,
    private val limitEntries: Int = 4,
    private val limitLeases: Int = 4,
) {
    init {
        require(limitBytes in 1..ArtworkLimits.MEMORY_BYTES)
        require(limitEntries in 1..4 && limitLeases in 1..4)
    }

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>(4, 0.75f, true)
    private val retired = HashSet<Entry>()
    private var occupied = 0L
    private var leases = 0

    fun reserve(bytes: Long): ThemeBitmapReservation = synchronized(lock) {
        if (bytes !in 1..limitBytes) themeFail(ThemeIssue.BUSY)
        evictUnused(bytes, requireEntry = false)
        if (bytes > limitBytes - occupied) themeFail(ThemeIssue.BUSY)
        occupied += bytes
        ThemeBitmapReservation(this, bytes)
    }

    fun acquire(key: String): ThemeBitmapLease? = synchronized(lock) {
        val entry = entries[key] ?: return null
        if (entry.bitmap.isRecycled) themeFail(ThemeIssue.INVALID_IMAGE)
        lease(key, entry)
    }

    fun owns(lease: ThemeBitmapLease): Boolean = lease.owner === this

    /** Reservation precedes allocation. Publication transfers it to the cache/display owner. */
    fun publish(
        key: String,
        bitmap: Bitmap,
        reservation: ThemeBitmapReservation,
        tintColor: Int? = null,
    ): ThemeBitmapLease = synchronized(lock) {
        if (!isThemeDigest(key) || bitmap.isRecycled || bitmap.isMutable || reservation.owner !== this ||
            reservation.state != ReservationState.RESERVED) themeFail(ThemeIssue.INVALID_INPUT)
        val bytes = bitmap.allocationByteCount.toLong()
        if (bytes <= 0 || bytes > reservation.bytes || leases >= limitLeases) themeFail(ThemeIssue.BUSY)
        evictUnused(0, requireEntry = key !in entries)
        if (key !in entries && entries.size >= limitEntries) themeFail(ThemeIssue.BUSY)
        resizeLocked(reservation, bytes)
        entries.remove(key)?.let { previous ->
            if (previous.references == 0) releaseEntry(previous) else retired += previous
        }
        reservation.state = ReservationState.PUBLISHED
        val entry = Entry(bitmap, reservation, tintColor)
        entries[key] = entry
        lease(key, entry)
    }

    fun trimUnused() = synchronized(lock) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (entry.references != 0) continue
            iterator.remove()
            releaseEntry(entry)
        }
    }

    fun usage(): ThemeBitmapUsage = synchronized(lock) {
        ThemeBitmapUsage(occupied, entries.values.sumOf { it.reservation.bytes },
            retired.sumOf { it.reservation.bytes }, entries.size, leases)
    }

    internal fun resize(reservation: ThemeBitmapReservation, bytes: Long) = synchronized(lock) {
        if (reservation.owner !== this || reservation.state != ReservationState.RESERVED ||
            bytes !in 1..reservation.bytes) themeFail(ThemeIssue.INVALID_INPUT)
        resizeLocked(reservation, bytes)
    }

    internal fun release(reservation: ThemeBitmapReservation) = synchronized(lock) {
        if (reservation.owner !== this || reservation.state != ReservationState.RESERVED) return@synchronized
        occupied -= reservation.bytes
        reservation.state = ReservationState.CLOSED
    }

    private fun resizeLocked(reservation: ThemeBitmapReservation, bytes: Long) {
        occupied -= reservation.bytes - bytes
        reservation.bytes = bytes
    }

    private fun evictUnused(requiredBytes: Long, requireEntry: Boolean) {
        val iterator = entries.entries.iterator()
        while ((requiredBytes > limitBytes - occupied || requireEntry && entries.size >= limitEntries) && iterator.hasNext()) {
            val entry = iterator.next().value
            if (entry.references != 0) continue
            iterator.remove()
            releaseEntry(entry)
        }
    }

    private fun lease(key: String, entry: Entry): ThemeBitmapLease {
        if (leases >= limitLeases) themeFail(ThemeIssue.BUSY)
        entry.references++
        leases++
        return ThemeBitmapLease(entry.bitmap, key, entry.tintColor, this) {
            synchronized(lock) {
                entry.references--
                leases--
                if (entry.references == 0 && retired.remove(entry)) releaseEntry(entry)
            }
        }
    }

    private fun releaseEntry(entry: Entry) {
        check(entry.references == 0)
        occupied -= entry.reservation.bytes
        entry.reservation.state = ReservationState.CLOSED
        // RenderThread may still retain a submitted frame. Published bitmaps are released to GC.
    }

    internal enum class ReservationState { RESERVED, PUBLISHED, CLOSED }
    private class Entry(val bitmap: Bitmap, val reservation: ThemeBitmapReservation, val tintColor: Int?, var references: Int = 0)
}

class ThemeBitmapReservation internal constructor(
    internal val owner: ThemeBitmapOwner,
    internal var bytes: Long,
) : AutoCloseable {
    internal var state = ThemeBitmapOwner.ReservationState.RESERVED
    fun resize(bytes: Long) { owner.resize(this, bytes) }
    override fun close() { owner.release(this) }
}

/** close only changes bounded memory ownership; it never does file or native work on Main. */
class ThemeBitmapLease internal constructor(
    val bitmap: Bitmap,
    val key: String,
    val tintColor: Int?,
    internal val owner: ThemeBitmapOwner,
    private val release: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    override fun close() { if (closed.compareAndSet(false, true)) release() }
}

data class ThemeBitmapUsage(val occupiedBytes: Long, val cachedBytes: Long, val retiredBytes: Long, val entries: Int, val leases: Int)

typealias AppliedThemeImageLease = ThemeBitmapLease
