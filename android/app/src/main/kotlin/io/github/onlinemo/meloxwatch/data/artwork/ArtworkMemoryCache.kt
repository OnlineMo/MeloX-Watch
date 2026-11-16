package io.github.onlinemo.meloxwatch.data.artwork

import android.graphics.Bitmap

/** Retired display leases count against the same byte budget as live LRU entries. */
internal class ArtworkMemoryCache(
    private val limitBytes: Long = ArtworkLimits.MEMORY_BYTES,
    private val limitEntries: Int = ArtworkLimits.MEMORY_ENTRIES,
) {
    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private val retired = HashSet<Entry>()
    private var bytes = 0L
    private var leases = 0

    fun acquire(key: String): ArtworkLease? = synchronized(lock) {
        val entry = entries[key] ?: return null
        lease(key, entry)
    }

    fun putAndAcquire(key: String, bitmap: Bitmap): ArtworkLease = synchronized(lock) {
        val size = bitmap.allocationByteCount.toLong()
        if (bitmap.isRecycled || size > ArtworkLimits.BITMAP_BYTES || size > limitBytes || leases >= ArtworkLimits.LIVE_LEASES) {
            throw ArtworkException(ArtworkIssue.BUSY)
        }
        val iterator = entries.entries.iterator()
        while ((bytes + size > limitBytes || entries.size >= limitEntries) && iterator.hasNext()) {
            val entry = iterator.next().value
            if (entry.references != 0) continue
            bytes -= entry.size
            iterator.remove()
        }
        if (bytes + size > limitBytes || (key !in entries && entries.size >= limitEntries)) throw ArtworkException(ArtworkIssue.BUSY)
        entries.remove(key)?.let { old ->
            if (old.references == 0) bytes -= old.size else retired += old
        }
        val entry = Entry(bitmap, size)
        entries[key] = entry
        bytes += size
        lease(key, entry)
    }

    fun trimUnused() = synchronized(lock) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            if (entry.references == 0) { bytes -= entry.size; iterator.remove() }
        }
    }

    internal fun occupiedBytes(): Long = synchronized(lock) { bytes }

    private fun lease(key: String, entry: Entry): ArtworkLease {
        if (leases >= ArtworkLimits.LIVE_LEASES || entry.bitmap.isRecycled) throw ArtworkException(ArtworkIssue.BUSY)
        entry.references++
        leases++
        return ArtworkLease(entry.bitmap, key) {
            synchronized(lock) {
                entry.references--
                leases--
                if (entry.references == 0 && retired.remove(entry)) bytes -= entry.size
                // A RenderThread may still reference a just-removed frame; leased bitmaps use GC.
            }
        }
    }

    private class Entry(val bitmap: Bitmap, val size: Long, var references: Int = 0)
}
