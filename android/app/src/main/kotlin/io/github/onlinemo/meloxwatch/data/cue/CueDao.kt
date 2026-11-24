package io.github.onlinemo.meloxwatch.data.cue

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface CueDao {
    @Query("SELECT * FROM local_cue_documents WHERE sourceId = :sourceId AND objectId = :objectId")
    suspend fun document(sourceId: String, objectId: String): CueDocumentEntity?
    @Query("SELECT * FROM local_cue_documents WHERE cueId = :cueId")
    suspend fun documentById(cueId: String): CueDocumentEntity?
    @Insert suspend fun insertDocument(value: CueDocumentEntity)
    @Update suspend fun updateDocument(value: CueDocumentEntity): Int
    /** 恢复所有者先在同一保留数据的 Room 写事务中退役精确的活动组。 */
    @Query("UPDATE local_cue_documents SET reassociationPending = 1 WHERE cueId = :cueId")
    suspend fun markReassociationPending(cueId: String): Int
    @Query("SELECT * FROM local_cue_group_versions WHERE cueId = :cueId AND validTo IS NULL ORDER BY groupRevision LIMIT 2")
    suspend fun currentGroups(cueId: String): List<CueGroupVersionEntity>
    @Query("SELECT * FROM local_cue_group_versions WHERE cueId = :cueId AND groupRevision = :revision")
    suspend fun group(cueId: String, revision: Long): CueGroupVersionEntity?
    @Query("SELECT * FROM local_cue_group_versions WHERE versionId = :id")
    suspend fun groupById(id: String): CueGroupVersionEntity?
    @Query("UPDATE local_cue_group_versions SET validTo = :revision - 1 WHERE cueId = :cueId AND validTo IS NULL")
    suspend fun closeGroup(cueId: String, revision: Long): Int
    @Insert suspend fun insertGroup(value: CueGroupVersionEntity)
    @Query("DELETE FROM local_cue_group_versions WHERE versionId IN (SELECT p.versionId FROM local_cue_group_versions p WHERE p.validTo IS NOT NULL AND NOT EXISTS (SELECT 1 FROM local_cue_documents d WHERE d.cueId = p.cueId AND d.reassociationPending = 1) AND NOT EXISTS (SELECT 1 FROM library_views v WHERE v.revision BETWEEN p.validFrom AND p.validTo) AND NOT EXISTS (SELECT 1 FROM local_cue_alias_versions a WHERE a.nativeGroupVersionId = p.versionId) AND NOT EXISTS (SELECT 1 FROM queue_entries e JOIN queue_head h ON h.activeGenerationId = e.generationId WHERE COALESCE(json_extract(e.cueReference, '$.binding.actualDocument.cueId'), json_extract(e.cueReference, '$.identity.cueId')) = p.cueId AND COALESCE(json_extract(e.cueReference, '$.binding.actualDocument.groupRevision'), json_extract(e.cueReference, '$.identity.groupRevision')) = p.groupRevision) LIMIT 100)")
    suspend fun cleanGroupVersions(): Int
    @Insert suspend fun insertNativeSegments(values: List<CueNativeSegmentEntity>)
    @Query("SELECT * FROM local_cue_native_segments WHERE groupVersionId = :id ORDER BY trackNumber LIMIT 100")
    suspend fun nativeSegments(id: String): List<CueNativeSegmentEntity>
    @Query("SELECT * FROM local_cue_aliases WHERE logicalSourceId = :sourceId AND logicalCueId = :cueId")
    suspend fun alias(sourceId: String, cueId: String): CueAliasEntity?
    @Query("SELECT * FROM local_cue_aliases WHERE aliasId = :id") suspend fun aliasById(id: String): CueAliasEntity?
    @Insert suspend fun insertAlias(value: CueAliasEntity)
    @Update suspend fun updateAlias(value: CueAliasEntity): Int
    @Query("UPDATE local_cue_aliases SET reassociationPending = 1 WHERE aliasId = :id")
    suspend fun markAliasReassociationPending(id: String): Int
    @Query("SELECT * FROM local_cue_alias_versions WHERE aliasId = :id AND validTo IS NULL ORDER BY logicalGroupRevision LIMIT 2")
    suspend fun currentAliasVersions(id: String): List<CueAliasVersionEntity>
    @Query("SELECT * FROM local_cue_alias_versions WHERE aliasId = :id AND logicalGroupRevision = :revision")
    suspend fun aliasVersion(id: String, revision: Long): CueAliasVersionEntity?
    @Query("SELECT * FROM local_cue_alias_versions WHERE operationId = :id") suspend fun adoptionOperation(id: String): CueAliasVersionEntity?
    @Query("UPDATE local_cue_alias_versions SET validTo = :revision - 1 WHERE aliasId = :id AND validTo IS NULL")
    suspend fun closeAliasVersion(id: String, revision: Long): Int
    @Insert suspend fun insertAliasVersion(value: CueAliasVersionEntity)
    @Insert suspend fun insertAliasSegments(values: List<CueAliasSegmentEntity>)
    @Query("SELECT * FROM local_cue_alias_segments WHERE aliasVersionId = :id ORDER BY trackNumber LIMIT 100")
    suspend fun aliasSegments(id: String): List<CueAliasSegmentEntity>
    @Query("DELETE FROM local_cue_alias_versions WHERE versionId IN (SELECT p.versionId FROM local_cue_alias_versions p WHERE p.validTo IS NOT NULL AND NOT EXISTS (SELECT 1 FROM local_cue_aliases a WHERE a.aliasId = p.aliasId AND a.reassociationPending = 1) AND NOT EXISTS (SELECT 1 FROM library_views v WHERE v.revision BETWEEN p.validFrom AND p.validTo) AND NOT EXISTS (SELECT 1 FROM queue_entries e JOIN queue_head h ON h.activeGenerationId = e.generationId WHERE json_extract(e.cueReference, '$.binding.adoptionAliasId') = p.aliasId AND json_extract(e.cueReference, '$.identity.groupRevision') = p.logicalGroupRevision) LIMIT 100)")
    suspend fun cleanAliasVersions(): Int
}
