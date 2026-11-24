package io.github.onlinemo.meloxwatch.data.library

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "library_meta")
data class LibraryMetaEntity(
    @PrimaryKey val id: Int = 1,
    val revision: Long = 0L,
    val rulesRevision: Long = 0L,
    val nextAddedSequence: Long = 1L,
    val nextScanSequence: Long = 1L,
    val shortAudioFilter: Boolean = false,
    val statisticsEpoch: Long = 0L,
)

@Entity(tableName = "local_sources", indices = [
    Index(value = ["activeScanId"], name = "index_local_sources_activeScanId"),
    Index(value = ["latestScanId"], name = "index_local_sources_latestScanId"),
    Index(value = ["absenceScanId"], name = "index_local_sources_absenceScanId"),
])
data class LibrarySourceEntity(
    @PrimaryKey val sourceId: String,
    val spec: String,
    val grantId: String,
    val revision: Long,
    val authEpoch: Long,
    val state: String,
    val activeScanId: String?,
    val latestScanId: String?,
    val absenceScanId: String?,
    val absenceScanSequence: Long?,
    val watermark: String?,
)

@Entity(
    tableName = "local_grants",
    foreignKeys = [ForeignKey(entity = LibrarySourceEntity::class, parentColumns = ["sourceId"], childColumns = ["sourceId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["sourceId"], name = "index_local_grants_sourceId")],
)
data class LibraryGrantEntity(
    @PrimaryKey val grantId: String,
    val sourceId: String,
    val uri: String,
    val observation: String,
    val readCapability: String,
    val acceptedPersistedFlags: Int,
    val appOwned: Boolean,
)

@Entity(
    tableName = "local_source_versions",
    primaryKeys = ["sourceId", "validFrom"],
    foreignKeys = [
        ForeignKey(entity = LibrarySourceEntity::class, parentColumns = ["sourceId"], childColumns = ["sourceId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = LibraryGrantEntity::class, parentColumns = ["grantId"], childColumns = ["grantId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["grantId"], name = "index_local_source_versions_grantId"),
        Index(value = ["validTo"], name = "index_local_source_versions_validTo"),
        Index(value = ["absenceScanId"], name = "index_local_source_versions_absenceScanId"),
    ],
)
data class LibrarySourceVersionEntity(
    val sourceId: String,
    val validFrom: Long,
    val validTo: Long?,
    val state: String,
    val spec: String,
    val grantId: String,
    val absenceScanId: String?,
    val absenceScanSequence: Long?,
    val rootObjectId: String? = null,
)

@Entity(tableName = "local_locations", indices = [Index(value = ["identityKey"], unique = true, name = "index_local_locations_identityKey")])
data class LibraryLocationEntity(
    @PrimaryKey val locationId: String,
    val identityKey: String,
    val contentRevision: Long,
    val contentToken: String?,
    val verifiedContentToken: String?,
    val previousVerifiedContentToken: String?,
    val sizeBytes: Long?,
    val modifiedEpochMs: Long?,
    val appOwned: Boolean,
)

@Entity(tableName = "local_location_versions",
    foreignKeys = [ForeignKey(entity = LibraryLocationEntity::class, parentColumns = ["locationId"], childColumns = ["locationId"], onDelete = ForeignKey.RESTRICT)],
    indices = [
        Index(value = ["locationId", "validFrom"], unique = true, name = "index_local_location_versions_locationId_validFrom"),
        Index(value = ["validTo"], name = "index_local_location_versions_validTo"),
    ])
data class LibraryLocationVersionEntity(
    @PrimaryKey val versionId: String,
    val locationId: String,
    val contentRevision: Long,
    val contentToken: String?,
    val verifiedContentToken: String?,
    val sizeBytes: Long?,
    val modifiedEpochMs: Long?,
    val validFrom: Long,
    val validTo: Long?,
)

@Entity(tableName = "local_tracks", indices = [Index(value = ["firstAddedSequence"], unique = true, name = "index_local_tracks_firstAddedSequence")])
data class LibraryTrackEntity(
    @PrimaryKey val trackId: String,
    val providerId: String,
    val firstAddedEpochMs: Long,
    val firstAddedSequence: Long,
)

@Entity(
    tableName = "local_track_locations",
    foreignKeys = [
        ForeignKey(entity = LibraryTrackEntity::class, parentColumns = ["trackId"], childColumns = ["trackId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = LibraryLocationEntity::class, parentColumns = ["locationId"], childColumns = ["locationId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["trackId", "validFrom"], name = "index_local_track_locations_trackId_validFrom"),
        Index(value = ["locationId", "validFrom"], name = "index_local_track_locations_locationId_validFrom"),
        Index(value = ["locationId", "validTo", "relationId"], name = "index_local_track_locations_locationId_validTo_relationId"),
        Index(value = ["validTo"], name = "index_local_track_locations_validTo"),
    ],
)
data class LibraryTrackLocationEntity(
    @PrimaryKey val relationId: String,
    val trackId: String,
    val locationId: String,
    val validFrom: Long,
    val validTo: Long?,
)

@Entity(
    tableName = "local_track_versions",
    foreignKeys = [ForeignKey(entity = LibraryTrackEntity::class, parentColumns = ["trackId"], childColumns = ["trackId"], onDelete = ForeignKey.RESTRICT)],
    indices = [
        Index(value = ["trackId", "validFrom"], unique = true, name = "index_local_track_versions_trackId_validFrom"),
        Index(value = ["validTo"], name = "index_local_track_versions_validTo"),
        Index(value = ["titleKey", "trackId"], name = "index_local_track_versions_titleKey_trackId"),
        Index(value = ["albumKey", "trackId"], name = "index_local_track_versions_albumKey_trackId"),
    ],
)
data class LibraryTrackVersionEntity(
    @PrimaryKey val versionId: String,
    val trackId: String,
    val providerId: String,
    val validFrom: Long,
    val validTo: Long?,
    val member: Boolean,
    val firstAddedEpochMs: Long,
    val firstAddedSequence: Long,
    val filename: String,
    val displayTitle: String,
    val title: String?,
    val artist: String?,
    val artists: String,
    val album: String?,
    val albumArtist: String?,
    val durationMs: Long?,
    val discNumber: Int?,
    val trackNumber: Int?,
    val compilation: Boolean,
    val mimeType: String?,
    val parserVersion: Int,
    val metadataIssue: String?,
    val titleKey: String,
    val artistKey: String,
    val albumSortKey: String,
    val filenameKey: String,
    val albumKey: String,
    val searchText: String,
    val titleSection: String,
    val artistSection: String,
    val albumSection: String,
    val filenameSection: String,
    val playCount: Long,
    val listeningMs: Long,
    val statisticsEpoch: Long = 0L,
    @ColumnInfo(defaultValue = "NULL") val codecParameters: String? = null,
    @ColumnInfo(defaultValue = "NULL") val cueReference: String? = null,
)

@Entity(
    tableName = "local_artist_values",
    primaryKeys = ["versionId", "ordinal"],
    foreignKeys = [ForeignKey(entity = LibraryTrackVersionEntity::class, parentColumns = ["versionId"], childColumns = ["versionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["artistKey", "versionId"], name = "index_local_artist_values_artistKey_versionId")],
)
data class LibraryArtistEntity(val versionId: String, val ordinal: Int, val artistKey: String, val displayName: String?)

@Entity(
    tableName = "local_nodes",
    foreignKeys = [ForeignKey(entity = LibrarySourceEntity::class, parentColumns = ["sourceId"], childColumns = ["sourceId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["sourceId", "objectId"], unique = true, name = "index_local_nodes_sourceId_objectId")],
)
data class LibraryNodeEntity(
    @PrimaryKey val nodeId: String,
    val sourceId: String,
    val objectId: String,
    val lastKnownName: String? = null,
)

@Entity(
    tableName = "local_node_versions",
    foreignKeys = [ForeignKey(entity = LibraryNodeEntity::class, parentColumns = ["nodeId"], childColumns = ["nodeId"], onDelete = ForeignKey.RESTRICT)],
    indices = [
        Index(value = ["nodeId", "validFrom"], unique = true, name = "index_local_node_versions_nodeId_validFrom"),
        Index(value = ["sourceId", "parentNodeId"], name = "index_local_node_versions_sourceId_parentNodeId"),
        Index(value = ["validTo"], name = "index_local_node_versions_validTo"),
    ],
)
data class LibraryNodeVersionEntity(
    @PrimaryKey val versionId: String,
    val nodeId: String,
    val sourceId: String,
    val parentNodeId: String?,
    val name: String?,
    val uri: String,
    val directory: Boolean,
    val validFrom: Long,
    val validTo: Long?,
    val pathName: String? = null,
)

@Entity(
    tableName = "local_access_aliases",
    foreignKeys = [
        ForeignKey(entity = LibrarySourceEntity::class, parentColumns = ["sourceId"], childColumns = ["sourceId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = LibraryLocationEntity::class, parentColumns = ["locationId"], childColumns = ["locationId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["sourceId", "objectId"], unique = true, name = "index_local_access_aliases_sourceId_objectId"), Index(value = ["locationId"], name = "index_local_access_aliases_locationId")],
)
data class LibraryAliasEntity(@PrimaryKey val aliasId: String, val sourceId: String, val objectId: String, val locationId: String)

@Entity(
    tableName = "local_alias_versions",
    foreignKeys = [ForeignKey(entity = LibraryAliasEntity::class, parentColumns = ["aliasId"], childColumns = ["aliasId"], onDelete = ForeignKey.RESTRICT)],
    indices = [
        Index(value = ["aliasId", "validFrom"], unique = true, name = "index_local_alias_versions_aliasId_validFrom"),
        Index(value = ["sourceId", "locationId"], name = "index_local_alias_versions_sourceId_locationId"),
        Index(value = ["validTo"], name = "index_local_alias_versions_validTo"),
    ],
)
data class LibraryAliasVersionEntity(
    @PrimaryKey val versionId: String,
    val aliasId: String,
    val sourceId: String,
    val locationId: String,
    val nodeId: String,
    val uri: String,
    val filename: String,
    val readIssue: String?,
    val lastSeenSequence: Long,
    val contentRevision: Long,
    val providerContentToken: String?,
    val sizeBytes: Long?,
    val validFrom: Long,
    val validTo: Long?,
)

@Entity(tableName = "scan_generations", indices = [Index(value = ["sourceId"], name = "index_scan_generations_sourceId")])
data class LibraryScanEntity(
    @PrimaryKey val scanId: String,
    val sourceId: String,
    val sourceRevision: Long,
    val authEpoch: Long,
    val rulesRevision: Long,
    val mode: String,
    val phase: String,
    val sequence: Long,
    val startedRevision: Long,
    val seen: Long,
    val processed: Long,
    val added: Long,
    val skipped: Long,
    val failed: Long,
    val enumerationComplete: Boolean,
    val authorizationValid: Boolean,
    val rulesUnchanged: Boolean,
    val sourceUnchanged: Boolean,
    val excludedSubtrees: Boolean,
    val absenceAfterAliasId: String?,
    val absenceDone: Boolean,
    val watermark: String?,
    val issue: String?,
)

@Entity(
    tableName = "scan_seen",
    primaryKeys = ["scanId", "objectId"],
    indices = [Index(value = ["scanId", "aliasId"], name = "index_scan_seen_scanId_aliasId")],
)
data class LibrarySeenEntity(
    val scanId: String,
    val objectId: String,
    val nodeId: String,
    val aliasId: String?,
    val metadataProcessed: Boolean,
    val metadataPayload: String? = null,
    val metadataAfterRelationId: String? = null,
)

@Entity(
    tableName = "scan_frontier",
    primaryKeys = ["scanId", "objectId"],
    indices = [Index(value = ["scanId", "state", "objectId"], name = "index_scan_frontier_scanId_state_objectId")],
)
data class LibraryFrontierEntity(val scanId: String, val objectId: String, val candidate: String, val state: String)

@Entity(tableName = "scan_absence_candidates", primaryKeys = ["scanId", "aliasId"], indices = [Index(value = ["aliasId"], name = "index_scan_absence_candidates_aliasId")])
data class LibraryAbsenceEntity(val scanId: String, val aliasId: String)

@Entity(tableName = "scan_rules", indices = [
    Index(value = ["ruleId", "validFrom"], unique = true, name = "index_scan_rules_ruleId_validFrom"),
    Index(value = ["sourceId", "nodeId"], name = "index_scan_rules_sourceId_nodeId"),
    Index(value = ["validTo"], name = "index_scan_rules_validTo"),
    Index(value = ["validTo", "ruleId"], name = "index_scan_rules_validTo_ruleId"),
])
data class LibraryRuleEntity(@PrimaryKey val versionId: String, val ruleId: String, val sourceId: String, val nodeId: String, val subtree: Boolean, val validFrom: Long, val validTo: Long?)

@Entity(tableName = "library_views", indices = [Index(value = ["revision"], name = "index_library_views_revision")])
data class LibraryViewEntity(
    @PrimaryKey val viewId: String,
    val sessionId: String,
    val revision: Long,
    val totalCount: Long,
    val fingerprint: String,
    val owner: String,
    val expiresElapsedMs: Long,
    val sortRuleVersion: Int,
)
