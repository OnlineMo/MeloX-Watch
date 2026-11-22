package io.github.onlinemo.meloxwatch.data.listening

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "listening_meta")
data class ListeningMetaEntity(
    @PrimaryKey val id: Int = 1,
    val historyEpoch: Long = 0L,
    val statisticsEpoch: Long = 0L,
    val rulesVersion: Int = ListeningLimits.RULES_VERSION,
    val nextHistorySequence: Long = 1L,
    val historyFloorSequence: Long = 1L,
    val totalPlayCount: Long = 0L,
    val totalListeningMs: Long = 0L,
)

/** This ledger outlives history retention and clearing so an old callback cannot count again. */
@Entity(tableName = "listening_rounds")
data class ListeningRoundEntity(
    @PrimaryKey val roundId: String,
    val providerId: String,
    val trackId: String,
    val displayName: String?,
    val historyEpoch: Long,
    val statisticsEpoch: Long,
    val rulesVersion: Int,
    val firstProgressEpochMs: Long,
    val firstProgressElapsedMs: Long,
    val firstProgressZoneId: String,
    val sampledEpochMs: Long,
    val sampledElapsedMs: Long,
    val sampledZoneId: String,
    val lastProgressEpochMs: Long,
    val totalListeningMs: Long,
    val knownDurationMs: Long?,
    val playCounted: Boolean,
    val countedLocalDate: String?,
    val countedZoneId: String?,
    val historyId: Long?,
)

@Entity(
    tableName = "listening_round_segments",
    primaryKeys = ["roundId", "ordinal"],
    foreignKeys = [ForeignKey(entity = ListeningRoundEntity::class, parentColumns = ["roundId"],
        childColumns = ["roundId"], onDelete = ForeignKey.RESTRICT)],
)
data class ListeningSegmentEntity(
    val roundId: String,
    val ordinal: Long,
    val localDate: String,
    val zoneId: String,
    val listeningMs: Long,
)

/** No mandatory library/queue foreign key: unavailable and deindexed tracks retain their history. */
@Entity(
    tableName = "listening_history",
    primaryKeys = ["historyEpoch", "historyId"],
    foreignKeys = [ForeignKey(entity = ListeningRoundEntity::class, parentColumns = ["roundId"],
        childColumns = ["roundId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["roundId"], unique = true, name = "index_listening_history_roundId")],
)
data class ListeningHistoryEntity(
    val historyEpoch: Long,
    val historyId: Long,
    val roundId: String,
    val providerId: String,
    val trackId: String,
    val displayName: String?,
    val firstProgressEpochMs: Long,
    val firstProgressZoneId: String,
    val lastProgressEpochMs: Long,
    val listeningMs: Long,
    val knownDurationMs: Long?,
)

@Entity(
    tableName = "listening_track_totals",
    primaryKeys = ["statisticsEpoch", "providerId", "trackId"],
    indices = [Index(value = ["statisticsEpoch", "listeningMs", "providerId", "trackId"],
        name = "index_listening_track_totals_epoch_time_key")],
)
data class ListeningTrackEntity(
    val statisticsEpoch: Long,
    val providerId: String,
    val trackId: String,
    val playCount: Long,
    val listeningMs: Long,
    val lastProgressEpochMs: Long?,
    val displayName: String?,
)

@Entity(tableName = "listening_day_totals", primaryKeys = ["statisticsEpoch", "localDate", "zoneId"])
data class ListeningDayEntity(
    val statisticsEpoch: Long,
    val localDate: String,
    val zoneId: String,
    val playCount: Long,
    val listeningMs: Long,
)
