package io.github.onlinemo.meloxwatch.data.lyrics.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LyricsDao {
    @Query("SELECT EXISTS(SELECT 1 FROM local_tracks WHERE trackId = :trackId AND providerId = 'local')")
    suspend fun localTrackExists(trackId: String): Boolean

    /** Recovery retains raw URI references even when the containing preferences cannot decode. */
    @Query("SELECT EXISTS(SELECT 1 FROM lyrics_track_preferences WHERE primaryUri = :uri OR translationUri = :uri)")
    suspend fun isDocumentUriReferenced(uri: String): Boolean

    /** SQLite bounds code points; the decoder separately enforces the stricter UTF-16 input limits. */
    @Query("""
        SELECT
            CASE WHEN typeof(schemaVersion) = 'integer' AND typeof(revision) = 'integer'
                AND typeof(userDelayMs) = 'integer'
                AND typeof(primaryDocumentId) IN ('text', 'null')
                AND typeof(primarySourceId) IN ('text', 'null')
                AND typeof(primaryUri) IN ('text', 'null')
                AND typeof(primaryReadFlags) IN ('integer', 'null')
                AND typeof(primaryEncoding) IN ('text', 'null')
                AND typeof(primaryConfirmedContentSha256) IN ('text', 'null')
                AND typeof(translationDocumentId) IN ('text', 'null')
                AND typeof(translationSourceId) IN ('text', 'null')
                AND typeof(translationUri) IN ('text', 'null')
                AND typeof(translationReadFlags) IN ('integer', 'null')
                AND typeof(translationEncoding) IN ('text', 'null')
                AND typeof(translationConfirmedContentSha256) IN ('text', 'null')
                THEN 1 ELSE 0 END AS validTypes,
            CASE WHEN typeof(schemaVersion) = 'integer' THEN schemaVersion ELSE 0 END AS schemaVersion,
            CASE WHEN typeof(revision) = 'integer' THEN revision ELSE 0 END AS revision,
            CASE WHEN typeof(primaryDocumentId) = 'text' THEN substr(primaryDocumentId, 1, 8193) ELSE NULL END AS primaryDocumentId,
            CASE WHEN typeof(primarySourceId) = 'text' THEN substr(primarySourceId, 1, 129) ELSE NULL END AS primarySourceId,
            CASE WHEN typeof(primaryUri) = 'text' THEN substr(primaryUri, 1, 8193) ELSE NULL END AS primaryUri,
            CASE WHEN typeof(primaryReadFlags) = 'integer' THEN primaryReadFlags ELSE NULL END AS primaryReadFlags,
            CASE WHEN typeof(primaryEncoding) = 'text' THEN substr(primaryEncoding, 1, 33) ELSE NULL END AS primaryEncoding,
            CASE WHEN typeof(primaryConfirmedContentSha256) = 'text' THEN substr(primaryConfirmedContentSha256, 1, 65) ELSE NULL END AS primaryConfirmedContentSha256,
            CASE WHEN typeof(translationDocumentId) = 'text' THEN substr(translationDocumentId, 1, 8193) ELSE NULL END AS translationDocumentId,
            CASE WHEN typeof(translationSourceId) = 'text' THEN substr(translationSourceId, 1, 129) ELSE NULL END AS translationSourceId,
            CASE WHEN typeof(translationUri) = 'text' THEN substr(translationUri, 1, 8193) ELSE NULL END AS translationUri,
            CASE WHEN typeof(translationReadFlags) = 'integer' THEN translationReadFlags ELSE NULL END AS translationReadFlags,
            CASE WHEN typeof(translationEncoding) = 'text' THEN substr(translationEncoding, 1, 33) ELSE NULL END AS translationEncoding,
            CASE WHEN typeof(translationConfirmedContentSha256) = 'text' THEN substr(translationConfirmedContentSha256, 1, 65) ELSE NULL END AS translationConfirmedContentSha256,
            CASE WHEN typeof(userDelayMs) = 'integer' THEN userDelayMs ELSE 0 END AS userDelayMs
        FROM lyrics_track_preferences WHERE trackId = :trackId LIMIT 1
    """)
    suspend fun row(trackId: String): LyricsTrackRow?

    /** This query runs inside the same short write transaction as the final revision CAS. */
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM local_tracks t
            JOIN local_locations l ON l.locationId = :locationId
            JOIN local_sources s ON s.sourceId = :sourceId
            JOIN queue_head h ON h.id = 1
            JOIN queue_generations g ON g.generationId = h.activeGenerationId
            JOIN queue_entries e ON e.generationId = g.generationId AND e.queueEntryId = h.currentEntryId
            WHERE t.trackId = :trackId AND t.providerId = 'local'
                AND typeof(s.revision) = 'integer' AND s.revision = :sourceRevision
                AND typeof(s.authEpoch) = 'integer' AND s.authEpoch = :authEpoch AND s.state = 'ACTIVE'
                AND typeof(l.contentRevision) = 'integer' AND l.contentRevision = :contentRevision
                AND h.activeGenerationId = :generationId AND h.currentEntryId = :entryId
                AND typeof(h.queueRevision) = 'integer' AND h.queueRevision = :queueRevision
                AND g.phase = 2 AND typeof(g.committedQueueRevision) = 'integer'
                AND g.committedQueueRevision = :queueRevision
                AND e.providerId = 'local' AND e.trackId = :trackId AND e.accountId IS NULL
                AND e.locationId = :locationId AND typeof(e.contentRevision) = 'integer'
                AND e.contentRevision = :contentRevision
                AND EXISTS(SELECT 1 FROM local_track_locations r WHERE r.trackId = :trackId
                    AND r.locationId = :locationId AND r.validTo IS NULL)
                AND EXISTS(SELECT 1 FROM local_alias_versions a WHERE a.sourceId = :sourceId
                    AND a.locationId = :locationId AND a.contentRevision = :contentRevision AND a.validTo IS NULL)
        )
    """)
    suspend fun fenceMatches(
        trackId: String,
        locationId: String,
        contentRevision: Long,
        sourceId: String,
        sourceRevision: Long,
        authEpoch: Long,
        generationId: String,
        queueRevision: Long,
        entryId: String,
    ): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: LyricsTrackPreferencesEntity): Long

    @Query("""
        UPDATE lyrics_track_preferences SET revision = :nextRevision,
            primaryDocumentId = :primaryDocumentId, primarySourceId = :primarySourceId,
            primaryUri = :primaryUri, primaryReadFlags = :primaryReadFlags,
            primaryEncoding = :primaryEncoding, primaryConfirmedContentSha256 = :primaryConfirmedContentSha256,
            translationDocumentId = :translationDocumentId, translationSourceId = :translationSourceId,
            translationUri = :translationUri, translationReadFlags = :translationReadFlags,
            translationEncoding = :translationEncoding, translationConfirmedContentSha256 = :translationConfirmedContentSha256,
            userDelayMs = :userDelayMs
        WHERE trackId = :trackId AND revision = :expectedRevision AND schemaVersion = 1
    """)
    suspend fun updateIfRevision(
        trackId: String,
        expectedRevision: Long,
        nextRevision: Long,
        primaryDocumentId: String?,
        primarySourceId: String?,
        primaryUri: String?,
        primaryReadFlags: Int?,
        primaryEncoding: String?,
        primaryConfirmedContentSha256: String?,
        translationDocumentId: String?,
        translationSourceId: String?,
        translationUri: String?,
        translationReadFlags: Int?,
        translationEncoding: String?,
        translationConfirmedContentSha256: String?,
        userDelayMs: Int,
    ): Int
}
