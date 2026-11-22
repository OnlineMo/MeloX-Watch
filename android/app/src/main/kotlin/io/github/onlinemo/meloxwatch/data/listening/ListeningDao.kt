package io.github.onlinemo.meloxwatch.data.listening

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class ListeningTodayRecord(val playCount: Long, val listeningMs: Long)

@Dao
interface ListeningDao {
    @Query("SELECT * FROM listening_meta WHERE id = 1") suspend fun meta(): ListeningMetaEntity?
    @Query("SELECT * FROM listening_meta WHERE id = 1") fun observeMeta(): Flow<ListeningMetaEntity?>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertMeta(value: ListeningMetaEntity): Long
    @Update suspend fun updateMeta(value: ListeningMetaEntity)
    @Query("SELECT * FROM listening_rounds WHERE roundId = :id") suspend fun round(id: String): ListeningRoundEntity?
    @Upsert suspend fun putRound(value: ListeningRoundEntity)
    @Query("SELECT * FROM listening_round_segments WHERE roundId = :id AND ordinal = :ordinal") suspend fun segment(id: String, ordinal: Long): ListeningSegmentEntity?
    @Query("SELECT * FROM listening_round_segments WHERE roundId = :id ORDER BY ordinal DESC LIMIT 1") suspend fun lastSegment(id: String): ListeningSegmentEntity?
    @Upsert suspend fun putSegments(values: List<ListeningSegmentEntity>)
    @Upsert suspend fun putHistory(value: ListeningHistoryEntity)
    @Query("SELECT * FROM listening_history WHERE historyEpoch = :epoch AND historyId = :id AND historyId >= :floor") suspend fun history(epoch: Long, id: Long, floor: Long): ListeningHistoryEntity?
    @Query("SELECT COUNT(*) FROM listening_history WHERE historyEpoch = :epoch AND historyId >= :floor") suspend fun historyCount(epoch: Long, floor: Long): Long
    @Query("SELECT * FROM listening_history WHERE historyEpoch = :epoch AND historyId >= :floor AND (:before IS NULL OR historyId < :before) ORDER BY historyId DESC LIMIT :limit") suspend fun historyPage(epoch: Long, floor: Long, before: Long?, limit: Int): List<ListeningHistoryEntity>
    @Query("SELECT * FROM listening_track_totals WHERE statisticsEpoch = :epoch AND providerId = :provider AND trackId = :track") suspend fun track(epoch: Long, provider: String, track: String): ListeningTrackEntity?
    @Upsert suspend fun putTrack(value: ListeningTrackEntity)
    @Query("SELECT * FROM listening_track_totals WHERE statisticsEpoch = :epoch AND (:time IS NULL OR listeningMs < :time OR (listeningMs = :time AND (providerId > :provider OR (providerId = :provider AND trackId > :track)))) ORDER BY listeningMs DESC, providerId, trackId LIMIT :limit") suspend fun trackPage(epoch: Long, time: Long?, provider: String?, track: String?, limit: Int): List<ListeningTrackEntity>
    @Query("SELECT * FROM listening_day_totals WHERE statisticsEpoch = :epoch AND localDate = :date AND zoneId = :zone") suspend fun day(epoch: Long, date: String, zone: String): ListeningDayEntity?
    @Query("SELECT COALESCE(SUM(playCount), 0) AS playCount, COALESCE(SUM(listeningMs), 0) AS listeningMs FROM listening_day_totals WHERE statisticsEpoch = :epoch AND localDate = :date") suspend fun today(epoch: Long, date: String): ListeningTodayRecord
    @Upsert suspend fun putDay(value: ListeningDayEntity)
    @Query("SELECT * FROM listening_day_totals WHERE statisticsEpoch = :epoch AND (:date IS NULL OR localDate < :date OR (localDate = :date AND zoneId > :zone)) ORDER BY localDate DESC, zoneId LIMIT :limit") suspend fun dayPage(epoch: Long, date: String?, zone: String?, limit: Int): List<ListeningDayEntity>

    @Query("DELETE FROM listening_history WHERE (historyEpoch, historyId) IN (SELECT historyEpoch, historyId FROM listening_history WHERE historyEpoch < :epoch OR historyId < :floor LIMIT 100)") suspend fun cleanHistory(epoch: Long, floor: Long): Int
    @Query("DELETE FROM listening_track_totals WHERE (statisticsEpoch, providerId, trackId) IN (SELECT statisticsEpoch, providerId, trackId FROM listening_track_totals WHERE statisticsEpoch < :epoch LIMIT 100)") suspend fun cleanTracks(epoch: Long): Int
    @Query("DELETE FROM listening_day_totals WHERE (statisticsEpoch, localDate, zoneId) IN (SELECT statisticsEpoch, localDate, zoneId FROM listening_day_totals WHERE statisticsEpoch < :epoch LIMIT 100)") suspend fun cleanDays(epoch: Long): Int
}
