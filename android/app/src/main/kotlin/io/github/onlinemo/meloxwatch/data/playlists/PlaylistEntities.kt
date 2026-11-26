package io.github.onlinemo.meloxwatch.data.playlists

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "playlist_meta")
data class PlaylistMetaEntity(
    @PrimaryKey val id: Int = 1,
    val revision: Long = 0L,
    val nextPlaylistSequence: Long = 1L,
)

@Entity(tableName = "playlist_heads", foreignKeys = [
    ForeignKey(entity = PlaylistGenerationEntity::class, parentColumns = ["generationId"],
        childColumns = ["activeGenerationId"], onDelete = ForeignKey.RESTRICT),
], indices = [
    Index(value = ["activeGenerationId"], name = "index_playlist_heads_activeGenerationId"),
    Index(value = ["createdSequence"], unique = true, name = "index_playlist_heads_createdSequence"),
])
data class PlaylistHeadEntity(
    @PrimaryKey val playlistId: String,
    val activeGenerationId: String?,
    val revision: Long,
    val createdSequence: Long,
    val deleted: Boolean = false,
)

@Entity(tableName = "playlist_generations", indices = [
    Index(value = ["playlistId", "validFrom"], name = "index_playlist_generations_playlistId_validFrom"),
    Index(value = ["state"], name = "index_playlist_generations_state"),
    Index(value = ["dataGenerationId"], name = "index_playlist_generations_dataGenerationId"),
    Index(value = ["operationId"], name = "index_playlist_generations_operationId"),
    Index(value = ["nameKey"], name = "index_playlist_generations_nameKey"),
])
data class PlaylistGenerationEntity(
    @PrimaryKey val generationId: String,
    val playlistId: String,
    val dataGenerationId: String,
    val operationId: String,
    val fingerprint: String,
    val state: String,
    val revision: Long,
    val name: String,
    val nameKey: String,
    val kind: String,
    val createdEpochMs: Long,
    val updatedEpochMs: Long,
    val memberCount: Long,
    val validFrom: Long?,
    val validTo: Long?,
    val processId: String,
    val bindingSourceId: String? = null,
    val bindingNodeId: String? = null,
    val bindingSubtree: Boolean? = null,
)

@Entity(tableName = "playlist_members", primaryKeys = ["generationId", "memberId"], foreignKeys = [
    ForeignKey(entity = PlaylistGenerationEntity::class, parentColumns = ["generationId"],
        childColumns = ["generationId"], onDelete = ForeignKey.RESTRICT),
], indices = [
    Index(value = ["generationId", "ordinal"], unique = true, name = "index_playlist_members_generationId_ordinal"),
    Index(value = ["generationId", "referenceKey"], unique = true, name = "index_playlist_members_generationId_referenceKey"),
    Index(value = ["providerId", "trackId", "generationId", "memberId"],
        name = "index_playlist_members_providerId_trackId_generationId_memberId"),
])
data class PlaylistMemberEntity(
    val generationId: String,
    val memberId: String,
    val ordinal: Long,
    val referenceKey: String,
    val providerId: String?,
    val trackId: String?,
    val placeholderId: String?,
    val issue: String?,
    val titleHint: String?,
    val artistHint: String?,
    val albumHint: String?,
    val relativePathHint: String?,
    val descriptionHint: String?,
    val durationMs: Long?,
    @ColumnInfo(defaultValue = "NULL") val cueReference: String? = null,
)

@Entity(tableName = "playlist_views", foreignKeys = [
    ForeignKey(entity = PlaylistGenerationEntity::class, parentColumns = ["generationId"],
        childColumns = ["generationId"], onDelete = ForeignKey.RESTRICT),
], indices = [
    Index(value = ["generationId"], name = "index_playlist_views_generationId"),
    Index(value = ["kind", "revision"], name = "index_playlist_views_kind_revision"),
    Index(value = ["owner"], name = "index_playlist_views_owner"),
])
data class PlaylistViewEntity(
    @PrimaryKey val viewId: String,
    val kind: String,
    val generationId: String?,
    val revision: Long,
    val totalCount: Long,
    val owner: String,
    val expiresElapsedMs: Long,
    val processId: String,
)

@Entity(tableName = "playlist_receipts")
data class PlaylistReceiptEntity(
    @PrimaryKey val operationId: String,
    val playlistId: String,
    val fingerprint: String,
    val appliedRevision: Long,
    val result: String,
    val addedCount: Long,
    val removedCount: Long,
    val duplicateCount: Long,
    val sameNameCount: Long,
)
