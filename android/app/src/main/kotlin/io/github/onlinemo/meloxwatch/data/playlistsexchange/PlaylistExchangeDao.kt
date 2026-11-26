package io.github.onlinemo.meloxwatch.data.playlistsexchange

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface PlaylistExchangeDao {
    @Insert suspend fun insertPreview(preview: PlaylistExchangePreviewEntity)
    @Update suspend fun updatePreview(preview: PlaylistExchangePreviewEntity): Int
    @Query("SELECT * FROM playlist_exchange_previews WHERE previewId = :id")
    suspend fun preview(id: String): PlaylistExchangePreviewEntity?
    @Query("SELECT * FROM playlist_exchange_previews WHERE processId != :processId OR state = 'DISCARDED' ORDER BY previewId LIMIT 100")
    suspend fun abandoned(processId: String): List<PlaylistExchangePreviewEntity>
    @Query("UPDATE playlist_exchange_previews SET state = 'DISCARDED' WHERE previewId = :id")
    suspend fun discard(id: String): Int
    @Insert suspend fun insertRows(rows: List<PlaylistExchangeRowEntity>)
    @Query("SELECT * FROM playlist_exchange_rows WHERE previewId = :id AND ordinal >= :first ORDER BY ordinal LIMIT :limit")
    suspend fun page(id: String, first: Long, limit: Int): List<PlaylistExchangeRowEntity>
    // 待重新关联的 CUE 仍保留原 TrackKey；逻辑标识不构成当前内容已匹配的证明。
    @Query("""SELECT COUNT(*) AS memberCount,
        COUNT(CASE WHEN trackId IS NOT NULL AND importIssue = 'NONE' THEN 1 END) AS matchedCount,
        COALESCE(SUM(CASE WHEN importIssue = 'AMBIGUOUS' THEN 1 ELSE 0 END), 0) AS ambiguousCount,
        COALESCE(SUM(CASE WHEN importIssue = 'BLOCKED_REFERENCE' THEN 1 ELSE 0 END), 0) AS blockedReferenceCount,
        COUNT(trackId) - COUNT(DISTINCT trackId) AS duplicateCount
        FROM playlist_exchange_rows WHERE previewId = :id""")
    suspend fun counts(id: String): PlaylistExchangeCounts
    @Query("DELETE FROM playlist_exchange_rows WHERE rowid IN (SELECT rowid FROM playlist_exchange_rows WHERE previewId = :id LIMIT 100)")
    suspend fun deleteRowsBatch(id: String): Int
    // Deleting a parent is allowed only after bounded row reclamation; a large cascade is never scheduled.
    @Query("DELETE FROM playlist_exchange_previews WHERE previewId = :id AND NOT EXISTS (SELECT 1 FROM playlist_exchange_rows WHERE previewId = :id)")
    suspend fun deleteEmptyPreview(id: String): Int
    @Query("SELECT * FROM playlist_export_receipts WHERE operationId = :id")
    suspend fun exportReceipt(id: String): PlaylistExportReceiptEntity?
    @Insert suspend fun insertExportReceipt(receipt: PlaylistExportReceiptEntity)
}
