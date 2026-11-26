package io.github.onlinemo.meloxwatch.data.playlistsexchange

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** 历史交换行仅含原有 V3 字段，不携带原生文件绑定。 */
@Entity(
    tableName = "playlist_exchange_rows",
    primaryKeys = ["previewId", "ordinal"],
    foreignKeys = [ForeignKey(entity = PlaylistExchangePreviewEntity::class,
        parentColumns = ["previewId"], childColumns = ["previewId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["previewId", "memberId"], unique = true), Index(value = ["previewId", "trackId"])],
)
data class HistoricalPlaylistExchangeRowEntity(
    val previewId: String,
    val ordinal: Long,
    val memberId: String,
    val trackId: String?,
    val placeholderId: String?,
    val referenceIssue: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val relativePath: String?,
    val description: String?,
    val durationMs: Long?,
    val importIssue: String,
    val availability: String,
)
