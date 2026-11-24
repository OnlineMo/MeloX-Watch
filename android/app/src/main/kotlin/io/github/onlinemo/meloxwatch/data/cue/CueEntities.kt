package io.github.onlinemo.meloxwatch.data.cue

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.onlinemo.meloxwatch.data.library.LibraryAliasEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryGrantEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryLocationEntity
import io.github.onlinemo.meloxwatch.data.library.LibrarySourceEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryTrackEntity

/** 稳定文档登记不包含可播放的整文件曲目或生成音频。 */
@Entity(tableName = "local_cue_documents", foreignKeys = [
    ForeignKey(entity = LibrarySourceEntity::class, parentColumns = ["sourceId"], childColumns = ["sourceId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = LibraryAliasEntity::class, parentColumns = ["aliasId"], childColumns = ["aliasId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = LibraryLocationEntity::class, parentColumns = ["locationId"], childColumns = ["locationId"], onDelete = ForeignKey.RESTRICT),
], indices = [
    Index(value = ["sourceId", "objectId"], unique = true, name = "index_local_cue_documents_sourceId_objectId"),
    Index(value = ["aliasId"], unique = true, name = "index_local_cue_documents_aliasId"),
    Index(value = ["locationId"], name = "index_local_cue_documents_locationId"),
])
data class CueDocumentEntity(
    @PrimaryKey val cueId: String,
    val sourceId: String,
    val objectId: String,
    val aliasId: String,
    val locationId: String,
    @ColumnInfo(defaultValue = "0") val lastGroupRevision: Long = 0L,
    @ColumnInfo(defaultValue = "NULL") val lastIssue: String? = null,
    @ColumnInfo(defaultValue = "NULL") val lastEncoding: String? = null,
    @ColumnInfo(defaultValue = "0") val reassociationPending: Boolean = false,
)

/** 完整组发布与音乐库视图共用同一 MVCC 修订域。 */
@Entity(tableName = "local_cue_group_versions", foreignKeys = [
    ForeignKey(entity = CueDocumentEntity::class, parentColumns = ["cueId"], childColumns = ["cueId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = LibraryGrantEntity::class, parentColumns = ["grantId"], childColumns = ["grantId"], onDelete = ForeignKey.RESTRICT),
], indices = [
    Index(value = ["cueId", "groupRevision"], unique = true, name = "index_local_cue_group_versions_cueId_groupRevision"),
    Index(value = ["cueId", "validFrom"], unique = true, name = "index_local_cue_group_versions_cueId_validFrom"),
    Index(value = ["grantId"], name = "index_local_cue_group_versions_grantId"),
    Index(value = ["validTo"], name = "index_local_cue_group_versions_validTo"),
])
data class CueGroupVersionEntity(
    @PrimaryKey val versionId: String,
    val cueId: String,
    val groupRevision: Long,
    val signature: String,
    val rawContentToken: String,
    val segmentCount: Int,
    val sourceRevision: Long,
    val authEpoch: Long,
    val grantId: String,
    val documentContentRevision: Long,
    val encoding: String,
    val boundaryVersion: Int,
    val validFrom: Long,
    val validTo: Long?,
)

/** 保留的公开 TrackKey 后续关联另一文档时，原生事实保持不可变。 */
@Entity(tableName = "local_cue_native_segments", primaryKeys = ["groupVersionId", "trackNumber"],
    foreignKeys = [ForeignKey(entity = CueGroupVersionEntity::class, parentColumns = ["versionId"],
        childColumns = ["groupVersionId"], onDelete = ForeignKey.CASCADE)], indices = [
        Index(value = ["trackId", "groupVersionId"], name = "index_local_cue_native_segments_trackId_groupVersionId"),
    ])
data class CueNativeSegmentEntity(
    val groupVersionId: String,
    val trackNumber: Int,
    val trackId: String,
    val cueReference: String,
    val metadata: String,
    val filename: String,
    val displayTitle: String,
)

/** 逻辑来源标识可移植；实际权限仅属于引用的原生组。 */
@Entity(tableName = "local_cue_aliases", indices = [
    Index(value = ["logicalSourceId", "logicalCueId"], unique = true, name = "index_local_cue_aliases_logicalSourceId_logicalCueId"),
])
data class CueAliasEntity(
    @PrimaryKey val aliasId: String,
    val logicalSourceId: String,
    val logicalCueId: String,
    @ColumnInfo(defaultValue = "0") val lastGroupRevision: Long = 0L,
    @ColumnInfo(defaultValue = "0") val reassociationPending: Boolean = false,
)

@Entity(tableName = "local_cue_alias_versions", foreignKeys = [
    ForeignKey(entity = CueAliasEntity::class, parentColumns = ["aliasId"], childColumns = ["aliasId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = CueGroupVersionEntity::class, parentColumns = ["versionId"], childColumns = ["nativeGroupVersionId"], onDelete = ForeignKey.RESTRICT),
], indices = [
    Index(value = ["aliasId", "logicalGroupRevision"], unique = true, name = "index_local_cue_alias_versions_aliasId_logicalGroupRevision"),
    Index(value = ["operationId"], unique = true, name = "index_local_cue_alias_versions_operationId"),
    Index(value = ["nativeGroupVersionId"], name = "index_local_cue_alias_versions_nativeGroupVersionId"),
    Index(value = ["validTo"], name = "index_local_cue_alias_versions_validTo"),
])
data class CueAliasVersionEntity(
    @PrimaryKey val versionId: String,
    val aliasId: String,
    val logicalGroupRevision: Long,
    val nativeGroupVersionId: String,
    val nativeCueId: String,
    val nativeGroupRevision: Long,
    val memberCount: Int,
    val nativeMemberCount: Int,
    val signature: String,
    val intentSignature: String,
    val operationId: String,
    val repairedQueueEntries: Long,
    val validFrom: Long,
    val validTo: Long?,
)

@Entity(tableName = "local_cue_alias_segments", primaryKeys = ["aliasVersionId", "trackNumber"], foreignKeys = [
    ForeignKey(entity = CueAliasVersionEntity::class, parentColumns = ["versionId"], childColumns = ["aliasVersionId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = LibraryTrackEntity::class, parentColumns = ["trackId"], childColumns = ["logicalTrackId"], onDelete = ForeignKey.RESTRICT),
], indices = [Index(value = ["logicalTrackId", "aliasVersionId"], name = "index_local_cue_alias_segments_logicalTrackId_aliasVersionId")])
data class CueAliasSegmentEntity(
    val aliasVersionId: String,
    val trackNumber: Int,
    val logicalTrackId: String,
    val cueReference: String,
    val member: Boolean,
)
