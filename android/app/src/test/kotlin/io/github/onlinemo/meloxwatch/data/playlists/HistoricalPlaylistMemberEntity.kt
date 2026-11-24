package io.github.onlinemo.meloxwatch.data.playlists

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** 精确保留 CUE 接入前的成员字段，使历史 V3–V6 声明保持不变。 */
@Entity(tableName = "playlist_members", primaryKeys = ["generationId", "memberId"], foreignKeys = [
    ForeignKey(entity = PlaylistGenerationEntity::class, parentColumns = ["generationId"],
        childColumns = ["generationId"], onDelete = ForeignKey.RESTRICT),
], indices = [
    Index(value = ["generationId", "ordinal"], unique = true, name = "index_playlist_members_generationId_ordinal"),
    Index(value = ["generationId", "referenceKey"], unique = true, name = "index_playlist_members_generationId_referenceKey"),
])
data class HistoricalPlaylistMemberEntity(
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
)
