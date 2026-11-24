package io.github.onlinemo.meloxwatch.playback.resources

import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackException
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackFailure
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

fun interface OnlinePlaybackResolver {
    suspend fun resolve(entry: QueueEntry, budget: PlaybackResolutionBudget, quality: StreamingQuality): RuntimePlaybackResource
}

/** Provider creation is lazy: resolving local content never constructs an account store or HTTP client. */
class SourcePlaybackResolver(
    private val local: suspend (QueueEntry) -> QueueEntry,
    private val online: suspend () -> OnlinePlaybackResolver,
) {
    suspend fun resolve(
        entry: QueueEntry,
        intent: PlaybackSourceIntent,
        budget: PlaybackResolutionBudget,
        quality: StreamingQuality,
    ): RuntimePlaybackResource {
        currentCoroutineContext().ensureActive()
        return when (entry.trackKey.providerId) {
            TrackKey.LOCAL_PROVIDER -> RuntimePlaybackResource(local(entry))
            "netease" -> {
                if (intent != PlaybackSourceIntent.ONLINE_ALLOWED) throw QueuePlaybackException(
                    QueuePlaybackFailure(QueuePlaybackIssue.LOCAL_ONLY_ONLINE_SKIPPED, entry.queueEntryId))
                if (entry.location != null || entry.accountId == null || !canonicalId(entry.trackKey.trackId) || !canonicalId(entry.accountId)) {
                    throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.RESOURCE_INVALID, entry.queueEntryId))
                }
                budget.within(onAbandoned = RuntimePlaybackResource::close) { online().resolve(entry, budget, quality) }
            }
            else -> throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.SOURCE_UNSUPPORTED, entry.queueEntryId))
        }
    }

    private fun canonicalId(value: String): Boolean = value.length in 1..32 && value.first() in '1'..'9' && value.all { it in '0'..'9' }
}
