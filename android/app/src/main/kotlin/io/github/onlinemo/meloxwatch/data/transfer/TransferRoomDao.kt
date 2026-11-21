package io.github.onlinemo.meloxwatch.data.transfer

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistGenerationEntity

@Dao
interface TransferRoomDao {
    @Query("SELECT * FROM transfer_room_operations WHERE operationId = :id")
    suspend fun operation(id: String): TransferRoomOperationEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOperation(value: TransferRoomOperationEntity): Long

    @Update suspend fun updateOperation(value: TransferRoomOperationEntity): Int

    @Query("SELECT * FROM transfer_display_previews WHERE previewId = :id")
    suspend fun preview(id: String): TransferDisplayPreviewEntity?

    @Insert suspend fun insertPreview(value: TransferDisplayPreviewEntity)

    @Query("SELECT COUNT(*) FROM transfer_display_previews")
    suspend fun previewCount(): Long

    @Query("""
        DELETE FROM transfer_display_previews WHERE previewId IN (
            SELECT previewId FROM transfer_display_previews
            WHERE processId != :processId OR expiresElapsedMs <= :now LIMIT 100)
    """)
    suspend fun expirePreviews(processId: String, now: Long): Int

    @Query("""
        DELETE FROM transfer_display_previews WHERE previewId IN (
            SELECT previewId FROM transfer_display_previews
            WHERE peerFingerprint = :peer AND sessionId = :session LIMIT 100)
    """)
    suspend fun deletePeerPreviews(peer: String, session: String): Int

    @Query("DELETE FROM transfer_display_previews WHERE previewId = :id")
    suspend fun deletePreview(id: String): Int

    @Query("""
        SELECT COUNT(*) FROM playlist_generations WHERE state = 'PUBLISHED' AND kind != 'FAVORITES'
        AND validFrom <= :revision AND (validTo IS NULL OR validTo >= :revision)
        AND nameKey LIKE :search ESCAPE '\'
    """)
    suspend fun playlistCatalogCount(revision: Long, search: String): Long

    @Query("""
        SELECT * FROM playlist_generations WHERE state = 'PUBLISHED' AND kind != 'FAVORITES'
        AND validFrom <= :revision AND (validTo IS NULL OR validTo >= :revision)
        AND nameKey LIKE :search ESCAPE '\' AND (:after IS NULL OR playlistId > :after)
        ORDER BY playlistId LIMIT :limit
    """)
    suspend fun playlistCatalogRows(revision: Long, search: String, after: String?, limit: Int): List<PlaylistGenerationEntity>

    @Query("""
        SELECT EXISTS(SELECT 1 FROM playlist_generations WHERE state = 'PUBLISHED' AND kind != 'FAVORITES'
        AND validFrom <= :revision AND (validTo IS NULL OR validTo >= :revision)
        AND nameKey LIKE :search ESCAPE '\' AND playlistId > :after LIMIT 1)
    """)
    suspend fun playlistCatalogHasNext(revision: Long, search: String, after: String): Boolean

}
