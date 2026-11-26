package io.github.onlinemo.meloxwatch.playback.resources

import io.github.onlinemo.meloxwatch.model.LocalCueReference
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue

/** 有界token仅持有持久binding的lease，不能替代Room实体标识与边界核验。 */
internal class CueRuntimeResourceState(
    val owner: RuntimePlaybackResources,
    val key: String,
    val entry: QueueEntry,
) {
    val reference: LocalCueReference = requireNotNull(entry.cueReference).also {
        it.requireEntry(entry.trackKey, entry.location)
        require(it.isBound)
    }
    var references = 1
    var revoked = false
    val failedReadOwners = LinkedHashSet<AutoCloseable>()

    fun check() {
        if (revoked || references <= 0) throw MediaTransportException(QueuePlaybackIssue.SOURCE_CHANGED)
    }
    override fun toString(): String = "CueRuntimeResourceState(redacted)"
}
