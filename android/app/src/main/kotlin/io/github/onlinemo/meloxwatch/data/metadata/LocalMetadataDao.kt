package io.github.onlinemo.meloxwatch.data.metadata

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalMetadataDao {
    /** Removing an owned association needs an exact logical selection, not a new audio read grant. */
    @Query("""
        SELECT EXISTS(SELECT 1 FROM queue_head h JOIN queue_generations g ON g.generationId = h.activeGenerationId
            JOIN queue_entries e ON e.generationId = g.generationId AND e.queueEntryId = h.currentEntryId
            JOIN local_tracks t ON t.trackId = e.trackId AND t.providerId = 'local'
            WHERE h.id = 1 AND h.activeGenerationId = :generationId AND h.currentEntryId = :entryId
                AND typeof(h.queueRevision) = 'integer' AND h.queueRevision = :queueRevision
                AND g.phase = 2 AND g.committedQueueRevision = :queueRevision
                AND e.providerId = 'local' AND e.trackId = :trackId AND e.accountId IS NULL)
    """)
    suspend fun selectionMatches(trackId: String, generationId: String, queueRevision: Long, entryId: String): Boolean

    @Query("""
        SELECT
            CASE WHEN typeof(schemaVersion) = 'integer' AND typeof(revision) = 'integer'
                AND typeof(assetId) IN ('text', 'null') AND typeof(sha256) IN ('text', 'null')
                AND typeof(byteCount) IN ('integer', 'null') AND typeof(provenance) IN ('text', 'null')
                THEN 1 ELSE 0 END AS validTypes,
            CASE WHEN typeof(schemaVersion) = 'integer' THEN schemaVersion ELSE 0 END AS schemaVersion,
            CASE WHEN typeof(revision) = 'integer' THEN revision ELSE 0 END AS revision,
            CASE WHEN typeof(assetId) = 'text' THEN substr(assetId, 1, 37) ELSE NULL END AS assetId,
            CASE WHEN typeof(sha256) = 'text' THEN substr(sha256, 1, 65) ELSE NULL END AS sha256,
            CASE WHEN typeof(byteCount) = 'integer' THEN byteCount ELSE NULL END AS byteCount,
            CASE WHEN typeof(provenance) = 'text' THEN substr(provenance, 1, 8193) ELSE NULL END AS provenance
        FROM local_metadata_associations WHERE trackId = :trackId AND purpose = :purpose LIMIT 1
    """)
    suspend fun row(trackId: String, purpose: String): LocalMetadataAssociationRow?

    /** Raw references remain protective even if the accompanying provenance cannot decode. */
    @Query("SELECT EXISTS(SELECT 1 FROM local_metadata_associations WHERE assetId = :assetId AND purpose = :purpose)")
    suspend fun isAssetReferenced(assetId: String, purpose: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM local_metadata_associations WHERE assetId = :assetId)")
    suspend fun isAnyAssetReferenced(assetId: String): Boolean

    @Query("SELECT revision FROM local_metadata_associations WHERE trackId = :trackId AND purpose = :purpose LIMIT 1")
    fun revisions(trackId: String, purpose: String): Flow<Long?>

    @Query("SELECT revision FROM lyrics_track_preferences WHERE trackId = :trackId LIMIT 1")
    fun lyricsRevisions(trackId: String): Flow<Long?>

    /** An undecodable owner cannot grant permission to remove an immutable file during recovery. */
    @Query("""
        SELECT EXISTS(SELECT 1 FROM local_metadata_associations WHERE
            (typeof(purpose) != 'text' OR purpose NOT IN ('LYRICS', 'ARTWORK')) OR (purpose = :purpose AND
            (typeof(assetId) NOT IN ('text', 'null') OR typeof(schemaVersion) != 'integer' OR schemaVersion != 1
             OR typeof(revision) != 'integer' OR revision < 0
             OR (assetId IS NOT NULL AND (length(assetId) != 36 OR assetId != lower(assetId)
                 OR substr(assetId, 9, 1) != '-' OR substr(assetId, 14, 1) != '-'
                 OR substr(assetId, 19, 1) != '-' OR substr(assetId, 24, 1) != '-'
                 OR length(replace(assetId, '-', '')) != 32 OR replace(assetId, '-', '') GLOB '*[^0-9a-f]*')))))
    """)
    suspend fun recoveryBlocked(purpose: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: LocalMetadataAssociationEntity): Long

    @Query("""
        UPDATE local_metadata_associations SET revision = :nextRevision, assetId = :assetId,
            sha256 = :sha256, byteCount = :byteCount, provenance = :provenance
        WHERE trackId = :trackId AND purpose = :purpose AND schemaVersion = 1 AND revision = :expectedRevision
    """)
    suspend fun updateIfRevision(trackId: String, purpose: String, expectedRevision: Long,
        nextRevision: Long, assetId: String?, sha256: String?, byteCount: Long?, provenance: String?): Int
}
