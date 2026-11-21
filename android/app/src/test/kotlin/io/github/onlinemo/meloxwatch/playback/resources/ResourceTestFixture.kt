package io.github.onlinemo.meloxwatch.playback.resources

import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.TrackKey

internal class FixturePlaybackClock(var now: Long = 0L) : PlaybackElapsedClock {
    override fun nowMs(): Long = now
}

internal fun onlineEntry(id: String = "201", order: Long = 0L, accountId: String = "101") = QueueEntry(
    "entry-$id-$order", TrackKey("netease", id), order, order, "Fixture track", 10_000L, accountId = accountId,
)

internal fun onlineResource(
    resources: RuntimePlaybackResources,
    entry: QueueEntry = onlineEntry(),
    budget: PlaybackResolutionBudget = PlaybackResolutionBudget(FixturePlaybackClock()),
    current: () -> Boolean = { true },
    expiry: Long? = null,
    url: String = "https://fixture.invalid/audio?signature=fixture",
) = resources.register(entry, MediaHttpTarget.https(url), budget, expiry,
    PlaybackResourceStatus("netease-native", ResourceMatch.PROVIDER_REPORTED, ResourceCompleteness.FULL_REPORTED,
        StreamingQuality.STANDARD, StreamingQuality.STANDARD, 128_000L), current)
