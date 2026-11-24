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
        "SELECT * FROM queue_entries WHERE generationId = :generationId AND originalOrder >= :first " +
            "ORDER BY originalOrder LIMIT :limit",
    )
    suspend fun originalPage(generationId: String, first: Long, limit: Int): List<QueueEntryEntity>

    @Query("SELECT COUNT(*) FROM queue_entries WHERE generationId = :generationId AND providerId = :providerId AND trackId = :trackId")
    suspend fun cueTrackEntryCount(generationId: String, providerId: String, trackId: String): Long

    /** 按稳定实例标识分页覆盖每个重复项，不整体分配或截断完整队列。 */
    @Query("SELECT * FROM queue_entries WHERE generationId = :generationId AND providerId = :providerId " +
        "AND trackId = :trackId AND (:after IS NULL OR queueEntryId > :after) ORDER BY queueEntryId LIMIT 100")
    suspend fun cueTrackEntryPage(generationId: String, providerId: String, trackId: String, after: String?): List<QueueEntryEntity>

    @Query("UPDATE queue_entries SET cueReference = :reference, durationMs = :durationMs, " +
        "locationId = :locationId, locationUri = :locationUri, grantUri = :grantUri, grantFlags = :grantFlags, " +
        "access = :access, contentRevision = :contentRevision WHERE generationId = :generationId " +
        "AND queueEntryId = :entryId AND providerId = :providerId AND trackId = :trackId " +
        "AND cueReference IS :expectedReference AND EXISTS (SELECT 1 FROM queue_head WHERE id = 1 AND activeGenerationId = :generationId)")
    suspend fun repairCueEntry(generationId: String, entryId: String, providerId: String, trackId: String,
        expectedReference: String?, reference: String, durationMs: Long?, locationId: String, locationUri: String,
        grantUri: String, grantFlags: Int, access: String, contentRevision: Long): Int

    @Query("UPDATE queue_generations SET committedQueueRevision = :nextRevision WHERE generationId = :generationId " +
        "AND phase = 2 AND committedQueueRevision = :expectedRevision")
    suspend fun advanceCueRepairGeneration(generationId: String, expectedRevision: Long, nextRevision: Long): Int

    @Query("UPDATE queue_head SET queueRevision = :nextQueueRevision, checkpointRevision = :nextCheckpointRevision, " +
        "positionMs = :positionMs WHERE id = 1 AND activeGenerationId = :generationId " +
        "AND queueRevision = :expectedQueueRevision AND checkpointRevision = :expectedCheckpointRevision " +
        "AND currentEntryId IS :expectedCurrentEntryId")
    suspend fun finishCueRepair(generationId: String, expectedQueueRevision: Long, expectedCheckpointRevision: Long,
        expectedCurrentEntryId: String?, nextQueueRevision: Long, nextCheckpointRevision: Long, positionMs: Long): Int

    @Query("SELECT COUNT(*) FROM queue_entries WHERE generationId = :generationId AND originalOrder != playbackOrder")
    suspend fun nonOriginalOrders(generationId: String): Long

    /** 负临时槽只在未发布 STAGING 的短事务里使用；SEALED/PUBLISHED 永远不可改序。 */
    @Query(
        "UPDATE queue_entries SET playbackOrder = :destination WHERE generationId = :generationId " +
            "AND playbackOrder = :source AND EXISTS " +
            "(SELECT 1 FROM queue_generations WHERE generationId = :generationId AND phase = 0)",
    )
    suspend fun moveStagingSlot(generationId: String, source: Long, destination: Long): Int

    @Query(
        "UPDATE queue_entries SET playbackOrder = :playbackOrder WHERE generationId = :generationId " +
            "AND queueEntryId = :entryId AND EXISTS " +
            "(SELECT 1 FROM queue_generations WHERE generationId = :generationId AND phase = 0)",
    )
    suspend fun setStagingOrder(generationId: String, entryId: String, playbackOrder: Long): Int

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
            "checkpointRevision = 0, currentEntryId = :currentEntryId, positionMs = :positionMs, completedCycles = :completedCycles " +
            "WHERE id = 1 AND queueRevision = :expectedRevision " +
            "AND ((activeGenerationId IS NULL AND :expectedGenerationId IS NULL) OR activeGenerationId = :expectedGenerationId) " +
            "AND (:checkCurrent = 0 OR ((currentEntryId IS NULL AND :expectedCurrentEntryId IS NULL) OR currentEntryId = :expectedCurrentEntryId))",
    )
    suspend fun publish(
        generationId: String,
        expectedGenerationId: String?,
        expectedRevision: Long,
        nextRevision: Long,
        currentEntryId: String?,
        positionMs: Long,
        completedCycles: Int = 0,
        checkCurrent: Boolean = false,
        expectedCurrentEntryId: String? = null,
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
