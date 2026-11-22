package io.github.onlinemo.meloxwatch.data.queue

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface QueueDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHead(head: QueueHeadEntity): Long

    @Query("SELECT * FROM queue_head WHERE id = 1")
    suspend fun head(): QueueHeadEntity?

    @Query("SELECT * FROM queue_head WHERE id = 1")
    fun observeHead(): Flow<QueueHeadEntity?>

    @Insert
    suspend fun insertGeneration(generation: QueueGenerationEntity)

    @Query("SELECT * FROM queue_generations WHERE generationId = :generationId")
    suspend fun generation(generationId: String): QueueGenerationEntity?

    @Query("SELECT * FROM queue_generations WHERE operationId = :operationId")
    suspend fun operation(operationId: String): QueueGenerationEntity?

    @Insert
    suspend fun insertEntries(entries: List<QueueEntryEntity>)

    @Query("SELECT * FROM queue_entries WHERE generationId = :generationId AND queueEntryId = :entryId")
    suspend fun entry(generationId: String, entryId: String): QueueEntryEntity?

    @Query("SELECT * FROM queue_entries WHERE generationId = :generationId AND originalOrder = :originalOrder")
    suspend fun entryAtOriginalOrder(generationId: String, originalOrder: Long): QueueEntryEntity?

    @Query(
        "SELECT * FROM queue_entries WHERE generationId = :generationId AND playbackOrder >= :first " +
            "ORDER BY playbackOrder LIMIT :limit",
    )
    suspend fun page(generationId: String, first: Long, limit: Int): List<QueueEntryEntity>

    @Query(
        "SELECT * FROM queue_entries WHERE generationId = :generationId " +
            "AND playbackOrder BETWEEN :first AND :last ORDER BY playbackOrder LIMIT 3",
    )
    suspend fun window(generationId: String, first: Long, last: Long): List<QueueEntryEntity>

    @Query(
        "SELECT COUNT(*) AS entryCount, MIN(originalOrder) AS firstOriginalOrder, " +
            "MAX(originalOrder) AS lastOriginalOrder, MIN(playbackOrder) AS firstPlaybackOrder, " +
            "MAX(playbackOrder) AS lastPlaybackOrder FROM queue_entries WHERE generationId = :generationId",
    )
    suspend fun orderStats(generationId: String): QueueOrderStats

    @Query(
        "UPDATE queue_generations SET writtenCount = :nextCount " +
            "WHERE generationId = :generationId AND phase = 0 AND writtenCount = :expectedCount",
    )
    suspend fun advanceStaging(generationId: String, expectedCount: Long, nextCount: Long): Int

    @Query("UPDATE queue_generations SET phase = 1 WHERE generationId = :generationId AND phase = 0")
    suspend fun seal(generationId: String): Int

    @Query(
        "UPDATE queue_generations SET phase = 2, committedQueueRevision = :revision " +
            "WHERE generationId = :generationId AND phase = 1",
    )
    suspend fun markPublished(generationId: String, revision: Long): Int

    @Query(
        "UPDATE queue_head SET activeGenerationId = :generationId, queueRevision = :nextRevision, " +
            "checkpointRevision = 0, currentEntryId = :currentEntryId, positionMs = :positionMs, completedCycles = 0 " +
            "WHERE id = 1 AND queueRevision = :expectedRevision " +
            "AND ((activeGenerationId IS NULL AND :expectedGenerationId IS NULL) OR activeGenerationId = :expectedGenerationId)",
    )
    suspend fun publish(
        generationId: String,
        expectedGenerationId: String?,
        expectedRevision: Long,
        nextRevision: Long,
        currentEntryId: String?,
        positionMs: Long,
    ): Int

    @Query(
        "UPDATE queue_head SET currentEntryId = :currentEntryId, positionMs = :positionMs, " +
            "completedCycles = :completedCycles, checkpointRevision = :nextRevision " +
            "WHERE id = 1 AND activeGenerationId = :generationId AND checkpointRevision = :expectedRevision",
    )
    suspend fun checkpoint(
        generationId: String,
        expectedRevision: Long,
        nextRevision: Long,
        currentEntryId: String?,
        positionMs: Long,
        completedCycles: Int,
    ): Int

    @Query(
        "UPDATE queue_generations SET phase = 3 WHERE phase IN (0, 1) " +
            "AND NOT EXISTS (SELECT 1 FROM queue_head WHERE activeGenerationId = queue_generations.generationId)",
    )
    suspend fun abandonInterruptedGenerations(): Int

    @Query(
        "UPDATE queue_generations SET phase = 3 WHERE generationId = :generationId AND phase IN (0, 1) " +
            "AND NOT EXISTS (SELECT 1 FROM queue_head WHERE activeGenerationId = :generationId)",
    )
    suspend fun abort(generationId: String): Int

    @Query(
        "DELETE FROM queue_entries WHERE rowid IN (SELECT rowid FROM queue_entries " +
            "WHERE generationId = :generationId " +
            "AND NOT EXISTS (SELECT 1 FROM queue_head WHERE activeGenerationId = :generationId) LIMIT :limit)",
    )
    suspend fun deleteAbandonedBatch(generationId: String, limit: Int): Int

    @Query(
        "DELETE FROM queue_entries WHERE rowid IN (SELECT queue_entries.rowid FROM queue_entries " +
            "INNER JOIN queue_generations USING (generationId) WHERE queue_generations.phase IN (2, 3) " +
            "AND NOT EXISTS (SELECT 1 FROM queue_head WHERE activeGenerationId = queue_entries.generationId) LIMIT :limit)",
    )
    suspend fun deleteInactiveBatch(limit: Int): Int

    @Query(
        "UPDATE queue_generations SET phase = 0, writtenCount = 0 WHERE generationId = :generationId AND phase = 3 " +
            "AND NOT EXISTS (SELECT 1 FROM queue_entries WHERE generationId = :generationId)",
    )
    suspend fun restartAborted(generationId: String): Int
}
