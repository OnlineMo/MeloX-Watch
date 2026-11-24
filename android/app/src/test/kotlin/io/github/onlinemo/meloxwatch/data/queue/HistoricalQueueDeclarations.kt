package io.github.onlinemo.meloxwatch.data.queue

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query

/** 精确保留 CUE 接入前的队列结构，使 V3–V6 验证独立于当前可空引用。 */
@Entity(
    tableName = "queue_entries",
    primaryKeys = ["generationId", "queueEntryId"],
    foreignKeys = [ForeignKey(entity = QueueGenerationEntity::class, parentColumns = ["generationId"],
        childColumns = ["generationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["generationId", "originalOrder"], unique = true),
        Index(value = ["generationId", "playbackOrder"], unique = true)],
)
data class HistoricalQueueEntryEntity(
    val generationId: String,
    val queueEntryId: String,
    val providerId: String,
    val trackId: String,
    val originalOrder: Long,
    val playbackOrder: Long,
    val displayName: String?,
    val durationMs: Long?,
    val accountId: String?,
    val locationId: String?,
    val locationUri: String?,
    val grantUri: String?,
    val grantFlags: Int?,
    val access: String?,
    val contentRevision: Long?,
)

@Entity(
    tableName = "queue_head",
    foreignKeys = [
        ForeignKey(entity = QueueGenerationEntity::class, parentColumns = ["generationId"],
            childColumns = ["activeGenerationId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = HistoricalQueueEntryEntity::class, parentColumns = ["generationId", "queueEntryId"],
            childColumns = ["activeGenerationId", "currentEntryId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["activeGenerationId"]), Index(value = ["activeGenerationId", "currentEntryId"])],
)
data class HistoricalQueueHeadEntity(
    @PrimaryKey val id: Int = 1,
    val activeGenerationId: String? = null,
    val queueRevision: Long = 0L,
    val checkpointRevision: Long = 0L,
    val currentEntryId: String? = null,
    val positionMs: Long = 0L,
    val completedCycles: Int = 0,
)

/** 历史读取查询不得引用任何 V8 CUE 表或列。 */
@Dao
interface HistoricalQueueDao {
    @Query("SELECT * FROM queue_head WHERE id = 1") suspend fun head(): HistoricalQueueHeadEntity?
    @Query("SELECT * FROM queue_generations WHERE generationId = :generationId") suspend fun generation(generationId: String): QueueGenerationEntity?
    @Query("SELECT * FROM queue_generations WHERE operationId = :operationId") suspend fun operation(operationId: String): QueueGenerationEntity?
    @Query("SELECT * FROM queue_entries WHERE generationId = :generationId AND queueEntryId = :entryId")
    suspend fun entry(generationId: String, entryId: String): HistoricalQueueEntryEntity?
    @Query("SELECT * FROM queue_entries WHERE generationId = :generationId AND originalOrder = :originalOrder")
    suspend fun entryAtOriginalOrder(generationId: String, originalOrder: Long): HistoricalQueueEntryEntity?
    @Query("SELECT * FROM queue_entries WHERE generationId = :generationId AND playbackOrder >= :first ORDER BY playbackOrder LIMIT :limit")
    suspend fun page(generationId: String, first: Long, limit: Int): List<HistoricalQueueEntryEntity>
}

internal fun HistoricalQueueEntryEntity.toCurrentQueueEntry() = QueueEntryEntity(generationId, queueEntryId, providerId,
    trackId, originalOrder, playbackOrder, displayName, durationMs, accountId, locationId, locationUri, grantUri, grantFlags,
    access, contentRevision, cueReference = null)
internal fun HistoricalQueueHeadEntity.toCurrentQueueHead() = QueueHeadEntity(id, activeGenerationId, queueRevision,
    checkpointRevision, currentEntryId, positionMs, completedCycles)
