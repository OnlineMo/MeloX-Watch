package io.github.onlinemo.meloxwatch.data.listening

import io.github.onlinemo.meloxwatch.model.TrackKey
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

object ListeningLimits {
    const val RULES_VERSION = 1
    const val PAGE_SIZE = 100
    const val HISTORY_ITEMS = 1000L
    const val ROUND_THRESHOLD_MS = 30_000L
}

data class ListeningEpochs(
    val historyEpoch: Long = 0L,
    val statisticsEpoch: Long = 0L,
    val rulesVersion: Int = ListeningLimits.RULES_VERSION,
)

/** An ordinal binds a real contiguous date/zone segment; only changed totals are submitted. */
data class ListeningDayAccumulation(
    val ordinal: Long,
    val localDate: String,
    val zoneId: String,
    val listeningMs: Long,
) {
    init {
        require(ordinal >= 0L && listeningMs >= 0L)
        require(localDate.length <= 16 && LocalDate.parse(localDate).toString() == localDate)
        require(zoneId.length <= 256 && ZoneId.of(zoneId).id == zoneId)
    }
}

/** The unique playback service supplies actual advance and monotonic elapsed time, never seek distance. */
data class ListeningRoundUpdate(
    val roundId: String,
    val trackKey: TrackKey,
    val displayName: String?,
    val epochs: ListeningEpochs,
    val firstProgressEpochMs: Long,
    val firstProgressElapsedMs: Long,
    val firstProgressZoneId: String,
    val sampledEpochMs: Long,
    val sampledElapsedMs: Long,
    val sampledZoneId: String,
    val totalListeningMs: Long,
    val knownDurationMs: Long?,
    val daySegments: List<ListeningDayAccumulation>,
) {
    init {
        require(roundId.length == 36 && UUID.fromString(roundId).toString() == roundId)
        require(displayName == null || displayName.length <= 4096)
        require(firstProgressElapsedMs >= 0L && sampledElapsedMs >= firstProgressElapsedMs)
        require(totalListeningMs > 0L && (knownDurationMs == null || knownDurationMs > 0L))
        require(daySegments.size <= ListeningLimits.PAGE_SIZE)
        require(daySegments.zipWithNext().all { (a, b) -> a.ordinal < b.ordinal })
        require(firstProgressZoneId.length <= 256 && ZoneId.of(firstProgressZoneId).id == firstProgressZoneId)
        require(sampledZoneId.length <= 256 && ZoneId.of(sampledZoneId).id == sampledZoneId)
    }
}

data class ListeningSummary(
    val epochs: ListeningEpochs,
    val totalPlayCount: Long,
    val totalListeningMs: Long,
    val historyCount: Long,
)

/** Current calendar date selects saved segments; the display zone never rewrites their dates. */
data class ListeningTodaySummary(
    val localDate: String,
    val currentZoneId: String,
    val playCount: Long,
    val listeningMs: Long,
    val statisticsEpoch: Long = 0L,
)

data class ListeningCommit(
    val epochs: ListeningEpochs,
    val acceptedListeningMs: Long,
    val historyRecorded: Boolean,
    val playCounted: Boolean,
    val wasReplay: Boolean,
)

/** A stable key and saved title keep unavailable/deindexed tracks reachable in history. */
data class ListeningHistoryItem(
    val historyId: Long,
    val roundId: String,
    val trackKey: TrackKey,
    val displayName: String?,
    val firstProgressEpochMs: Long,
    val firstProgressZoneId: String,
    val lastProgressEpochMs: Long,
    val listeningMs: Long,
    val knownDurationMs: Long?,
)

data class ListeningHistoryCursor(val historyEpoch: Long, val beforeHistoryId: Long)
data class ListeningHistoryPage(val items: List<ListeningHistoryItem>, val nextCursor: ListeningHistoryCursor?)

data class ListeningTrackSummary(
    val trackKey: TrackKey,
    val playCount: Long,
    val listeningMs: Long,
    val lastProgressEpochMs: Long?,
    val displayName: String? = null,
)
data class ListeningTrackCursor(val statisticsEpoch: Long, val listeningMs: Long, val providerId: String, val trackId: String)
data class ListeningTrackPage(val items: List<ListeningTrackSummary>, val nextCursor: ListeningTrackCursor?)

data class ListeningDaySummary(val localDate: String, val zoneId: String, val playCount: Long, val listeningMs: Long)
data class ListeningDayCursor(val statisticsEpoch: Long, val localDate: String, val zoneId: String)
data class ListeningDayPage(val items: List<ListeningDaySummary>, val nextCursor: ListeningDayCursor?)

enum class ListeningIssue { INVALID_INPUT, CONFLICT, SOURCE_CHANGED, STORAGE_FULL, STORAGE_UNAVAILABLE, RECOVERY_REQUIRED }
class ListeningException(val issue: ListeningIssue, cause: Throwable? = null) : Exception(issue.name, cause)
