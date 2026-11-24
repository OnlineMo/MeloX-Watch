package io.github.onlinemo.meloxwatch.data.queue

import io.github.onlinemo.meloxwatch.data.library.LibraryDao
import io.github.onlinemo.meloxwatch.data.local.LocalInputLimits
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueLimits
import io.github.onlinemo.meloxwatch.model.TrackKey

/** A single cached-label query per queue page, independent of provider reads and playback selection. */
internal class QueueArtistReader(private val library: LibraryDao) {
    suspend fun read(entries: List<QueueEntry>): Map<TrackKey, String?> {
        require(entries.size <= QueueLimits.PAGE_SIZE)
        val keys = entries.asSequence().map { it.trackKey }
            .filter { it.providerId == TrackKey.LOCAL_PROVIDER }.toSet()
        if (keys.isEmpty()) return emptyMap()
        val rows = library.artistLabels(TrackKey.LOCAL_PROVIDER, keys.map { it.trackId }, keys.size)
        check(rows.size <= keys.size)
        val labels = LinkedHashMap<TrackKey, String?>(rows.size)
        for (row in rows) {
            val key = TrackKey(row.providerId, row.trackId)
            check(key in keys && !labels.containsKey(key) && row.currentVersionCount == 1L)
            // SQLite length counts code points; Kotlin also guards the existing UTF-16 input limit.
            labels[key] = row.artist?.takeIf { it.length <= LocalInputLimits.TAG_CHARACTERS }
        }
        return labels
    }
}
