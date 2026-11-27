package io.github.onlinemo.meloxwatch.data.library

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.RoomRawQuery
import io.github.onlinemo.meloxwatch.data.cue.CueDocumentEntity
import kotlinx.coroutines.flow.Flow

data class LibraryRowRecord(
    @Embedded val track: LibraryTrackVersionEntity,
    val availabilityRank: Int,
    val missingRank: Int,
    val nullRank: Int,
    val sortKey: String,
    val sectionKey: String,
    val effectivePlayCount: Long,
    val effectiveListeningMs: Long,
    val selectedAliasId: String?,
    val selectedSourceId: String?,
    val selectedLocationId: String?,
    val selectedNodeId: String?,
    val selectedUri: String?,
    val selectedContentRevision: Long?,
    val selectedGrantUri: String?,
    val selectedGrantObservation: String?,
    val selectedSpec: String?,
    val selectedObjectId: String?,
    val selectedProviderContentToken: String?,
    val selectedVerifiedContentToken: String?,
    val selectedSizeBytes: Long?,
    val displayAlbumKey: String,
    val displayArtist: String?,
    val displayAlbum: String?,
    val displayAlbumSubtitle: String?,
)

data class LibraryGroupRecord(val groupKey: String, val title: String?, val subtitle: String?, val trackCount: Long)
data class LibrarySectionRecord(val sectionKey: String, val trackCount: Long)
data class LibraryTrackKeyRecord(val providerId: String, val trackId: String)
data class LibraryArtistLabelRecord(val providerId: String, val trackId: String, val artist: String?, val currentVersionCount: Long)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library_meta WHERE id = 1") suspend fun meta(): LibraryMetaEntity?
    @Query("SELECT * FROM library_meta WHERE id = 1") fun observeMeta(): Flow<LibraryMetaEntity?>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertMeta(value: LibraryMetaEntity): Long
    @Update suspend fun updateMeta(value: LibraryMetaEntity): Int
    @Query("UPDATE library_meta SET revision = :nextRevision, nextAddedSequence = :nextSequence WHERE id = 1 AND revision = :expectedRevision")
    suspend fun advanceCueHintMeta(expectedRevision: Long, nextRevision: Long, nextSequence: Long): Int
    @Query("SELECT * FROM local_sources WHERE sourceId = :id") suspend fun source(id: String): LibrarySourceEntity?
    @Query("SELECT * FROM local_sources WHERE (:after IS NULL OR sourceId > :after) ORDER BY sourceId LIMIT :limit") suspend fun sourcesPage(after: String?, limit: Int): List<LibrarySourceEntity>
    @Query("SELECT * FROM local_sources WHERE activeScanId IS NOT NULL AND (:after IS NULL OR sourceId > :after) ORDER BY sourceId LIMIT 100") suspend fun activeSourcesPage(after: String?): List<LibrarySourceEntity>
    @Upsert suspend fun putSource(value: LibrarySourceEntity)
    @Query("SELECT COUNT(*) FROM local_sources WHERE activeScanId IS NOT NULL") suspend fun activeScanCount(): Long
    @Query("SELECT * FROM local_grants WHERE grantId = :id") suspend fun grant(id: String): LibraryGrantEntity?
    @Insert suspend fun insertGrant(value: LibraryGrantEntity)
    @Query("UPDATE local_source_versions SET validTo = :revision - 1 WHERE sourceId = :id AND validTo IS NULL") suspend fun closeSourceVersion(id: String, revision: Long)
    @Insert suspend fun insertSourceVersion(value: LibrarySourceVersionEntity)
    @Query("SELECT * FROM local_locations WHERE identityKey = :key") suspend fun locationByIdentity(key: String): LibraryLocationEntity?
    @Query("SELECT * FROM local_locations WHERE locationId = :id") suspend fun location(id: String): LibraryLocationEntity?
    @Upsert suspend fun putLocation(value: LibraryLocationEntity)
    @Query("SELECT * FROM local_location_versions WHERE locationId = :id AND validTo IS NULL") suspend fun currentLocationVersion(id: String): LibraryLocationVersionEntity?
    @Query("UPDATE local_location_versions SET validTo = :revision - 1 WHERE locationId = :id AND validTo IS NULL") suspend fun closeLocationVersion(id: String, revision: Long)
    @Insert suspend fun insertLocationVersion(value: LibraryLocationVersionEntity)
    @Update suspend fun updateLocationVersion(value: LibraryLocationVersionEntity)
    @Query("SELECT * FROM local_tracks WHERE trackId = :id") suspend fun track(id: String): LibraryTrackEntity?
    @Insert suspend fun insertTrack(value: LibraryTrackEntity)
    @Insert suspend fun insertRelation(value: LibraryTrackLocationEntity)
    @Query("SELECT EXISTS (SELECT 1 FROM local_track_locations WHERE locationId = :id AND validTo IS NULL LIMIT 1)") suspend fun hasLocationRelation(id: String): Boolean
    @Query("SELECT * FROM local_track_locations WHERE locationId = :id AND validTo IS NULL AND (:after IS NULL OR relationId > :after) ORDER BY relationId LIMIT :limit") suspend fun locationRelations(id: String, after: String?, limit: Int = LibraryLimits.PAGE_SIZE): List<LibraryTrackLocationEntity>
    @Query("SELECT * FROM local_track_versions WHERE trackId = :id AND validTo IS NULL") suspend fun currentTrack(id: String): LibraryTrackVersionEntity?
    @Query("SELECT EXISTS (SELECT 1 FROM local_track_locations r JOIN local_track_versions t ON t.trackId = r.trackId AND t.validTo IS NULL WHERE r.locationId = :id AND r.validTo IS NULL AND t.cueReference IS NULL LIMIT 1)") suspend fun hasPhysicalLocationRelation(id: String): Boolean
    @Query("SELECT DISTINCT t.* FROM local_track_versions t JOIN local_track_locations r ON r.trackId = t.trackId AND r.validTo IS NULL WHERE r.locationId = :id AND t.validTo IS NULL AND t.cueReference IS NULL AND t.member = 1 ORDER BY t.trackId LIMIT 2") suspend fun physicalTracks(id: String): List<LibraryTrackVersionEntity>
    @Query("SELECT * FROM local_track_versions WHERE validTo IS NULL AND cueReference IS NOT NULL AND json_extract(cueReference, '$.identity.cueId') = :cueId ORDER BY trackId LIMIT 100") suspend fun currentCueTracks(cueId: String): List<LibraryTrackVersionEntity>
    @Query("SELECT * FROM local_track_versions WHERE validTo IS NULL AND cueReference IS NOT NULL AND json_extract(cueReference, '$.identity.sourceId') = :sourceId AND json_extract(cueReference, '$.identity.cueId') = :cueId ORDER BY trackId LIMIT 100")
    suspend fun currentLogicalCueTracks(sourceId: String, cueId: String): List<LibraryTrackVersionEntity>
    @Query("SELECT * FROM local_track_locations WHERE trackId = :id AND validTo IS NULL ORDER BY relationId LIMIT 100") suspend fun trackRelations(id: String): List<LibraryTrackLocationEntity>
    @Query("UPDATE local_track_locations SET validTo = :revision - 1 WHERE trackId = :id AND validTo IS NULL") suspend fun closeTrackRelations(id: String, revision: Long): Int
    @Query("SELECT EXISTS (SELECT 1 FROM local_track_locations WHERE trackId = :trackId AND locationId = :locationId AND validTo IS NULL LIMIT 1)") suspend fun hasTrackLocationRelation(trackId: String, locationId: String): Boolean
    /** Only cached labels for a bounded page; this projection grants no access to the original file. */
    @Query("""
        SELECT providerId, trackId,
               CASE WHEN COUNT(*) = 1 AND length(artist) <= 4096 THEN artist ELSE NULL END AS artist,
               COUNT(*) AS currentVersionCount
        FROM local_track_versions
        WHERE providerId = :providerId AND trackId IN (:trackIds) AND validTo IS NULL
        GROUP BY providerId, trackId
        ORDER BY trackId
        LIMIT :limit
    """)
    suspend fun artistLabels(providerId: String, trackIds: List<String>, limit: Int): List<LibraryArtistLabelRecord>
    @Query("UPDATE local_track_versions SET validTo = :revision - 1 WHERE trackId = :id AND validTo IS NULL") suspend fun closeTrackVersion(id: String, revision: Long)
    @Insert suspend fun insertTrackVersion(value: LibraryTrackVersionEntity)
    @Update suspend fun updateTrackVersion(value: LibraryTrackVersionEntity)
    @Insert suspend fun insertArtists(values: List<LibraryArtistEntity>)
    @Query("DELETE FROM local_artist_values WHERE versionId = :id") suspend fun deleteArtists(id: String)
    @Query("SELECT * FROM local_access_aliases WHERE sourceId = :sourceId AND objectId = :objectId") suspend fun alias(sourceId: String, objectId: String): LibraryAliasEntity?
    @Query("SELECT * FROM local_access_aliases WHERE aliasId = :id") suspend fun aliasById(id: String): LibraryAliasEntity?
    @Insert suspend fun insertAlias(value: LibraryAliasEntity)
    @Query("SELECT * FROM local_alias_versions WHERE aliasId = :id AND validTo IS NULL") suspend fun currentAlias(id: String): LibraryAliasVersionEntity?
    @Query("UPDATE local_alias_versions SET validTo = :revision - 1 WHERE aliasId = :id AND validTo IS NULL") suspend fun closeAliasVersion(id: String, revision: Long)
    @Insert suspend fun insertAliasVersion(value: LibraryAliasVersionEntity)
    @Update suspend fun updateAliasVersion(value: LibraryAliasVersionEntity)
    @Query("SELECT * FROM local_nodes WHERE sourceId = :sourceId AND objectId = :objectId") suspend fun node(sourceId: String, objectId: String): LibraryNodeEntity?
    @Query("SELECT * FROM local_nodes WHERE nodeId = :id") suspend fun nodeById(id: String): LibraryNodeEntity?
    @Insert suspend fun insertNode(value: LibraryNodeEntity)
    @Update suspend fun updateNode(value: LibraryNodeEntity)
    @Query("SELECT * FROM local_node_versions WHERE nodeId = :id AND validTo IS NULL") suspend fun currentNode(id: String): LibraryNodeVersionEntity?
    @Query("UPDATE local_node_versions SET validTo = :revision - 1 WHERE nodeId = :id AND validTo IS NULL") suspend fun closeNodeVersion(id: String, revision: Long)
    @Insert suspend fun insertNodeVersion(value: LibraryNodeVersionEntity)
    @Update suspend fun updateNodeVersion(value: LibraryNodeVersionEntity)
    @Query("SELECT * FROM scan_generations WHERE scanId = :id") suspend fun scan(id: String): LibraryScanEntity?
    @Query("SELECT t.* FROM local_track_versions t JOIN local_track_locations r ON r.trackId = t.trackId WHERE r.locationId = :locationId AND r.validTo IS NULL AND t.validTo IS NULL AND t.cueReference IS NULL ORDER BY t.trackId LIMIT 1")
    suspend fun physicalTrackAtLocation(locationId: String): LibraryTrackVersionEntity?
    @Query("SELECT * FROM scan_generations WHERE scanId = :id") fun observeScan(id: String): Flow<LibraryScanEntity?>
    @Insert suspend fun insertScan(value: LibraryScanEntity)
    @Update suspend fun updateScan(value: LibraryScanEntity)
    @Query("SELECT * FROM scan_seen WHERE scanId = :id AND objectId = :objectId") suspend fun seen(id: String, objectId: String): LibrarySeenEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSeen(value: LibrarySeenEntity): Long
    @Update suspend fun updateSeen(value: LibrarySeenEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertFrontier(value: LibraryFrontierEntity): Long
    @Query("SELECT * FROM scan_frontier WHERE scanId = :id AND state = 'QUEUED' ORDER BY objectId LIMIT 1") suspend fun nextFrontier(id: String): LibraryFrontierEntity?
    @Query("SELECT * FROM scan_frontier WHERE scanId = :id AND state = 'CUE_QUEUED' ORDER BY objectId LIMIT 1") suspend fun nextCueFrontier(id: String): LibraryFrontierEntity?
    @Query("SELECT COUNT(*) FROM scan_frontier WHERE scanId = :id AND state IN ('QUEUED', 'RUNNING')") suspend fun pendingDirectories(id: String): Long
    @Query("SELECT COUNT(*) FROM scan_frontier WHERE scanId = :id AND state IN ('CUE_RUNNING', 'CUE_VALIDATED')") suspend fun runningCueCount(id: String): Long
    @Query("SELECT * FROM scan_frontier WHERE scanId = :id AND objectId = :objectId") suspend fun frontier(id: String, objectId: String): LibraryFrontierEntity?
    @Update suspend fun updateFrontier(value: LibraryFrontierEntity)
    @Query("SELECT COUNT(*) FROM scan_frontier WHERE scanId = :id AND state != 'DONE'") suspend fun incompleteFrontier(id: String): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAbsence(values: List<LibraryAbsenceEntity>)
    @Query("SELECT COUNT(*) FROM scan_absence_candidates WHERE scanId = :id") suspend fun absenceCount(id: String): Long
    @Query("SELECT * FROM scan_rules WHERE validTo IS NULL AND (:after IS NULL OR ruleId > :after) ORDER BY ruleId LIMIT :limit") suspend fun rulesPage(after: String?, limit: Int): List<LibraryRuleEntity>
    @Query("SELECT EXISTS (SELECT 1 FROM scan_rules WHERE validTo IS NULL AND sourceId = :sourceId AND nodeId = :nodeId AND subtree = :subtree LIMIT 1)") suspend fun hasRule(sourceId: String, nodeId: String, subtree: Boolean): Boolean
    @Query("SELECT * FROM scan_rules WHERE ruleId = :id AND validTo IS NULL") suspend fun rule(id: String): LibraryRuleEntity?
    @Insert suspend fun insertRule(value: LibraryRuleEntity)
    @Query("UPDATE scan_rules SET validTo = :revision - 1 WHERE ruleId = :id AND validTo IS NULL") suspend fun closeRule(id: String, revision: Long)
    @Query("SELECT * FROM library_views WHERE viewId = :id") suspend fun view(id: String): LibraryViewEntity?
    @Query("SELECT * FROM library_views") suspend fun views(): List<LibraryViewEntity>
    @Insert suspend fun insertView(value: LibraryViewEntity)
    @Query("UPDATE library_views SET expiresElapsedMs = :expiry WHERE viewId = :id") suspend fun renewView(id: String, expiry: Long)
    @Query("DELETE FROM library_views WHERE viewId = :id") suspend fun deleteView(id: String)
    @Query("DELETE FROM library_views WHERE sessionId != :session OR expiresElapsedMs <= :now") suspend fun expireViews(session: String, now: Long)
    @RawQuery suspend fun rows(query: RoomRawQuery): List<LibraryRowRecord>
    @RawQuery suspend fun scalar(query: RoomRawQuery): Long
    @RawQuery suspend fun groupRows(query: RoomRawQuery): List<LibraryGroupRecord>
    @RawQuery suspend fun sectionRows(query: RoomRawQuery): List<LibrarySectionRecord>
    @RawQuery suspend fun nodeRows(query: RoomRawQuery): List<LibraryNodeVersionEntity>
    @RawQuery suspend fun aliasRows(query: RoomRawQuery): List<LibraryAliasEntity>
    @RawQuery suspend fun text(query: RoomRawQuery): String?
    @RawQuery suspend fun trackKeys(query: RoomRawQuery): List<LibraryTrackKeyRecord>
    @RawQuery suspend fun cuePathDocuments(query: RoomRawQuery): List<CueDocumentEntity>
    @RawQuery suspend fun aliasRowsForCue(query: RoomRawQuery): List<LibraryAliasVersionEntity>
    @Query("SELECT * FROM import_operations WHERE operationId = :id") suspend fun importRecord(id: String): LibraryImportEntity?
    @Query("SELECT * FROM import_operations WHERE ownedSourceId = :sourceId AND phase = 'COMMITTED'") suspend fun ownedImport(sourceId: String): LibraryImportEntity?
    @Insert suspend fun insertImport(value: LibraryImportEntity)
    @Update suspend fun updateImport(value: LibraryImportEntity)
    @Query("SELECT * FROM import_operations WHERE phase IN ('COPYING', 'VERIFIED', 'INSTALLED') AND (:after IS NULL OR operationId > :after) ORDER BY operationId LIMIT :limit") suspend fun pendingImports(after: String?, limit: Int): List<LibraryImportEntity>

    @Query("DELETE FROM local_track_versions WHERE versionId IN (SELECT p.versionId FROM local_track_versions p WHERE p.validTo IS NOT NULL AND NOT EXISTS (SELECT 1 FROM library_views v WHERE v.revision BETWEEN p.validFrom AND p.validTo) LIMIT 100)") suspend fun cleanTrackVersions(): Int
    @Query("DELETE FROM local_alias_versions WHERE versionId IN (SELECT p.versionId FROM local_alias_versions p WHERE p.validTo IS NOT NULL AND NOT EXISTS (SELECT 1 FROM library_views v WHERE v.revision BETWEEN p.validFrom AND p.validTo) LIMIT 100)") suspend fun cleanAliasVersions(): Int
    @Query("DELETE FROM local_location_versions WHERE versionId IN (SELECT p.versionId FROM local_location_versions p WHERE p.validTo IS NOT NULL AND NOT EXISTS (SELECT 1 FROM library_views v WHERE v.revision BETWEEN p.validFrom AND p.validTo) LIMIT 100)") suspend fun cleanLocationVersions(): Int
    @Query("DELETE FROM local_node_versions WHERE versionId IN (SELECT p.versionId FROM local_node_versions p WHERE p.validTo IS NOT NULL AND NOT EXISTS (SELECT 1 FROM library_views v WHERE v.revision BETWEEN p.validFrom AND p.validTo) LIMIT 100)") suspend fun cleanNodeVersions(): Int
    @Query("DELETE FROM local_track_locations WHERE relationId IN (SELECT p.relationId FROM local_track_locations p WHERE p.validTo IS NOT NULL AND NOT EXISTS (SELECT 1 FROM library_views v WHERE v.revision BETWEEN p.validFrom AND p.validTo) LIMIT 100)") suspend fun cleanRelations(): Int
    @Query("DELETE FROM scan_rules WHERE versionId IN (SELECT p.versionId FROM scan_rules p WHERE p.validTo IS NOT NULL AND NOT EXISTS (SELECT 1 FROM library_views v WHERE v.revision BETWEEN p.validFrom AND p.validTo) LIMIT 100)") suspend fun cleanRules(): Int
    @Query("DELETE FROM local_source_versions WHERE (sourceId, validFrom) IN (SELECT p.sourceId, p.validFrom FROM local_source_versions p WHERE p.validTo IS NOT NULL AND NOT EXISTS (SELECT 1 FROM library_views v WHERE v.revision BETWEEN p.validFrom AND p.validTo) LIMIT 100)") suspend fun cleanSourceVersions(): Int
    @Query("DELETE FROM local_grants WHERE grantId IN (SELECT g.grantId FROM local_grants g WHERE NOT EXISTS (SELECT 1 FROM local_sources s WHERE s.grantId = g.grantId) AND NOT EXISTS (SELECT 1 FROM local_source_versions v WHERE v.grantId = g.grantId) AND NOT EXISTS (SELECT 1 FROM local_cue_group_versions c WHERE c.grantId = g.grantId) LIMIT 100)") suspend fun cleanGrants(): Int
    @Query("SELECT scanId FROM scan_generations p WHERE NOT EXISTS (SELECT 1 FROM local_sources s WHERE s.activeScanId = p.scanId OR s.latestScanId = p.scanId OR s.absenceScanId = p.scanId) AND NOT EXISTS (SELECT 1 FROM local_source_versions v WHERE v.absenceScanId = p.scanId) ORDER BY scanId LIMIT 1") suspend fun obsoleteScanId(): String?
    @Query("DELETE FROM scan_seen WHERE scanId = :id AND objectId IN (SELECT objectId FROM scan_seen WHERE scanId = :id LIMIT 100)") suspend fun cleanSeen(id: String): Int
    @Query("DELETE FROM scan_frontier WHERE scanId = :id AND objectId IN (SELECT objectId FROM scan_frontier WHERE scanId = :id LIMIT 100)") suspend fun cleanFrontier(id: String): Int
    @Query("DELETE FROM scan_absence_candidates WHERE scanId = :id AND aliasId IN (SELECT aliasId FROM scan_absence_candidates WHERE scanId = :id LIMIT 100)") suspend fun cleanAbsence(id: String): Int
    @Query("DELETE FROM scan_generations WHERE scanId = :id AND NOT EXISTS (SELECT 1 FROM scan_seen WHERE scanId = :id) AND NOT EXISTS (SELECT 1 FROM scan_frontier WHERE scanId = :id) AND NOT EXISTS (SELECT 1 FROM scan_absence_candidates WHERE scanId = :id) AND NOT EXISTS (SELECT 1 FROM local_sources s WHERE s.activeScanId = :id OR s.latestScanId = :id OR s.absenceScanId = :id) AND NOT EXISTS (SELECT 1 FROM local_source_versions v WHERE v.absenceScanId = :id)") suspend fun deleteEmptyScan(id: String): Int
}
