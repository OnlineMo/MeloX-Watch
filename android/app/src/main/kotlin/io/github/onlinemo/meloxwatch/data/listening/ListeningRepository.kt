package io.github.onlinemo.meloxwatch.data.listening

import io.github.onlinemo.meloxwatch.model.TrackKey
import kotlinx.coroutines.flow.Flow

/** Cumulative acknowledgements and clear epochs remain separate from playback controls. */
interface ListeningRepository {
    suspend fun state(): ListeningEpochs
    suspend fun summary(): ListeningSummary
    /** The result carries the statistics epoch read in the same transaction as its totals. */
    suspend fun today(localDate: String, currentZoneId: String): ListeningTodaySummary
    fun observeSummary(): Flow<ListeningSummary>
    suspend fun submit(update: ListeningRoundUpdate): ListeningCommit
    suspend fun historyPage(after: ListeningHistoryCursor? = null, limit: Int = ListeningLimits.PAGE_SIZE): ListeningHistoryPage
    suspend fun trackSummary(trackKey: TrackKey): ListeningTrackSummary
    suspend fun trackPage(after: ListeningTrackCursor? = null, limit: Int = ListeningLimits.PAGE_SIZE): ListeningTrackPage
    suspend fun dayPage(after: ListeningDayCursor? = null, limit: Int = ListeningLimits.PAGE_SIZE): ListeningDayPage
    /** Called only after the separate history confirmation, bound to the reviewed epoch. */
    suspend fun clearHistory(expectedHistoryEpoch: Long): ListeningEpochs
    /** Called only after the separate statistics confirmation; history and files remain independent. */
    suspend fun clearStatistics(expectedStatisticsEpoch: Long): ListeningEpochs
}
