package io.github.onlinemo.meloxwatch.data.playlists

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoricalPlaylistDao {
    @Query("SELECT * FROM playlist_meta WHERE id = 1")
    suspend fun meta(): PlaylistMetaEntity?

    @Query("SELECT * FROM playlist_meta WHERE id = 1")
    fun observeMeta(): Flow<PlaylistMetaEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMeta(meta: PlaylistMetaEntity): Long

    @Update
    suspend fun updateMeta(meta: PlaylistMetaEntity): Int

    @Query("SELECT * FROM playlist_heads WHERE playlistId = :id")
    suspend fun head(id: String): PlaylistHeadEntity?

    @Insert
    suspend fun insertHead(head: PlaylistHeadEntity)

    @Query("""
        UPDATE playlist_heads SET activeGenerationId = :nextGeneration, revision = :nextRevision, deleted = :deleted
        WHERE playlistId = :id AND revision = :expectedRevision AND deleted = 0
            AND activeGenerationId = :expectedGeneration
    """)
    suspend fun publishHead(
        id: String,
        expectedRevision: Long,
        expectedGeneration: String,
        nextGeneration: String?,
        nextRevision: Long,
        deleted: Boolean,
    ): Int

    @Query("SELECT * FROM playlist_generations WHERE generationId = :id")
    suspend fun generation(id: String): PlaylistGenerationEntity?

    @Query("""
        SELECT g.* FROM playlist_heads h JOIN playlist_generations g ON g.generationId = h.activeGenerationId
        WHERE h.playlistId = :id AND h.deleted = 0
    """)
    suspend fun currentGeneration(id: String): PlaylistGenerationEntity?

    @Query("""
        SELECT g.* FROM playlist_heads h JOIN playlist_generations g ON g.generationId = h.activeGenerationId
        WHERE h.playlistId = :id AND h.deleted = 0
    """)
    fun observeCurrentGeneration(id: String): Flow<PlaylistGenerationEntity?>

    @Query("""
        SELECT * FROM playlist_generations WHERE playlistId = :id AND state = 'PUBLISHED'
            AND validFrom <= :revision AND (validTo IS NULL OR validTo >= :revision) LIMIT 2
    """)
    suspend fun generationAt(id: String, revision: Long): List<PlaylistGenerationEntity>

    @Query("""
        SELECT * FROM playlist_generations WHERE state = 'PUBLISHED' AND kind != 'FAVORITES'
            AND validFrom <= :revision AND (validTo IS NULL OR validTo >= :revision)
            AND (:afterId IS NULL OR playlistId > :afterId) ORDER BY playlistId LIMIT :limit
    """)
    suspend fun catalogPage(revision: Long, afterId: String?, limit: Int): List<PlaylistGenerationEntity>

    @Query("""
        SELECT COUNT(*) FROM playlist_generations WHERE state = 'PUBLISHED' AND kind != 'FAVORITES'
            AND validFrom <= :revision AND (validTo IS NULL OR validTo >= :revision)
    """)
    suspend fun catalogCount(revision: Long): Long

    @Query("""
        SELECT EXISTS(SELECT 1 FROM playlist_generations WHERE state = 'PUBLISHED' AND kind != 'FAVORITES'
            AND validFrom <= :revision AND (validTo IS NULL OR validTo >= :revision)
            AND playlistId > :afterId LIMIT 1)
    """)
    suspend fun catalogHasNext(revision: Long, afterId: String): Boolean

    @Query("""
        SELECT COUNT(*) FROM playlist_heads h JOIN playlist_generations g ON g.generationId = h.activeGenerationId
        WHERE h.deleted = 0 AND g.kind != 'FAVORITES' AND g.nameKey = :nameKey AND h.playlistId != :exceptId
    """)
    suspend fun sameNameCount(nameKey: String, exceptId: String): Long

    @Insert
    suspend fun insertGeneration(generation: PlaylistGenerationEntity)

    @Query("UPDATE playlist_generations SET state = 'SEALED', memberCount = :count WHERE generationId = :id AND state = 'STAGING'")
    suspend fun seal(id: String, count: Long): Int

    @Query("UPDATE playlist_generations SET state = 'PUBLISHED', validFrom = :revision WHERE generationId = :id AND state = 'SEALED'")
    suspend fun publish(id: String, revision: Long): Int

    @Query("UPDATE playlist_generations SET validTo = :revision WHERE generationId = :id AND state = 'PUBLISHED' AND validTo IS NULL")
    suspend fun closeGeneration(id: String, revision: Long): Int

    @Query("UPDATE playlist_generations SET state = 'ABORTED' WHERE generationId = :id AND state IN ('STAGING', 'SEALED')")
    suspend fun abort(id: String): Int

    @Query("UPDATE playlist_generations SET state = 'ABORTED' WHERE state IN ('STAGING', 'SEALED')")
    suspend fun abortAbandoned(): Int

    @Insert
    suspend fun insertMembers(members: List<HistoricalPlaylistMemberEntity>)

    @Query("SELECT * FROM playlist_members WHERE generationId = :generation AND ordinal >= :first ORDER BY ordinal LIMIT :limit")
    suspend fun membersPage(generation: String, first: Long, limit: Int): List<HistoricalPlaylistMemberEntity>

    @Query("SELECT * FROM playlist_members WHERE generationId = :generation AND memberId = :id")
    suspend fun member(generation: String, id: String): HistoricalPlaylistMemberEntity?

    @Query("SELECT * FROM playlist_members WHERE generationId = :generation AND referenceKey = :key")
    suspend fun memberByReference(generation: String, key: String): HistoricalPlaylistMemberEntity?

    @Query("SELECT referenceKey FROM playlist_members WHERE generationId = :generation AND referenceKey IN (:keys)")
    suspend fun existingReferences(generation: String, keys: List<String>): List<String>

    @Query("SELECT COUNT(*) AS count, MIN(ordinal) AS firstOrdinal, MAX(ordinal) AS lastOrdinal FROM playlist_members WHERE generationId = :generation")
    suspend fun orderSummary(generation: String): PlaylistOrderSummary

    @Query("""
        SELECT EXISTS(SELECT 1 FROM playlist_heads h
            JOIN playlist_generations g ON g.generationId = h.activeGenerationId
            JOIN playlist_members m ON m.generationId = g.dataGenerationId
            WHERE h.playlistId = 'system:favorites' AND h.deleted = 0 AND m.referenceKey = :key LIMIT 1)
    """)
    suspend fun favoriteContains(key: String): Boolean

    @Query("""
        SELECT EXISTS(SELECT 1 FROM playlist_heads h
            JOIN playlist_generations g ON g.generationId = h.activeGenerationId
            JOIN playlist_members m ON m.generationId = g.dataGenerationId
            WHERE h.playlistId = 'system:favorites' AND h.deleted = 0 AND m.referenceKey = :key LIMIT 1)
    """)
    fun observeFavoriteContains(key: String): Flow<Boolean>

    @Query("SELECT * FROM playlist_views WHERE viewId = :id")
    suspend fun view(id: String): PlaylistViewEntity?

    @Insert
    suspend fun insertView(view: PlaylistViewEntity)

    @Query("SELECT COUNT(*) FROM playlist_views WHERE owner = :owner")
    suspend fun viewCount(owner: String): Long

    @Query("DELETE FROM playlist_views WHERE viewId = :id")
    suspend fun deleteView(id: String): Int

    @Query("DELETE FROM playlist_views WHERE processId != :processId OR expiresElapsedMs <= :elapsedMs")
    suspend fun expireViews(processId: String, elapsedMs: Long): Int

    @Query("SELECT * FROM playlist_receipts WHERE operationId = :id")
    suspend fun receipt(id: String): PlaylistReceiptEntity?

    @Insert
    suspend fun insertReceipt(receipt: PlaylistReceiptEntity)

    @Query("""
        SELECT g.* FROM playlist_generations g WHERE g.state IN ('ABORTED', 'PUBLISHED')
            AND NOT EXISTS(SELECT 1 FROM playlist_heads h WHERE h.activeGenerationId = g.generationId)
            AND NOT EXISTS(SELECT 1 FROM playlist_generations other
                WHERE other.generationId != g.generationId AND other.dataGenerationId = g.generationId)
            AND NOT EXISTS(SELECT 1 FROM playlist_views v WHERE v.generationId = g.generationId)
            AND NOT EXISTS(SELECT 1 FROM playlist_views v WHERE v.kind = 'CATALOG' AND g.kind != 'FAVORITES'
                AND g.validFrom IS NOT NULL AND v.revision >= g.validFrom
                AND (g.validTo IS NULL OR v.revision <= g.validTo))
            ORDER BY CASE WHEN g.state = 'ABORTED' THEN 0 ELSE 1 END, g.generationId LIMIT 1
    """)
    suspend fun reclaimCandidate(): PlaylistGenerationEntity?

    @Query("""
        DELETE FROM playlist_members WHERE generationId = :generation AND memberId IN (
            SELECT memberId FROM playlist_members WHERE generationId = :generation ORDER BY ordinal LIMIT 100)
    """)
    suspend fun deleteMembersBatch(generation: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM playlist_members WHERE generationId = :generation LIMIT 1)")
    suspend fun hasMembers(generation: String): Boolean

    @Query("DELETE FROM playlist_generations WHERE generationId = :id")
    suspend fun deleteGeneration(id: String): Int
}
