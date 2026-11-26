package io.github.onlinemo.meloxwatch.data.playlistsexchange

import androidx.room.Entity
import androidx.room.ColumnInfo
import io.github.onlinemo.meloxwatch.data.cue.CueReferenceCodec
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistReference
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistReferenceIssue
import io.github.onlinemo.meloxwatch.model.TrackKey

/** Private staging has no URI grants. A process restart discards it instead of applying old consent. */
@Entity(tableName = "playlist_exchange_previews", indices = [Index(value = ["processId", "state"])])
data class PlaylistExchangePreviewEntity(
    @PrimaryKey val previewId: String,
    val processId: String,
    val state: String,
    val catalogRevision: Long,
    val rulesRevision: Long,
    val expiresElapsedMs: Long,
    val memberCount: Long = 0L,
    val nameHint: String? = null,
    val byteCount: Long = 0L,
    val sha256: String? = null,
)

@Entity(
    tableName = "playlist_exchange_rows",
    primaryKeys = ["previewId", "ordinal"],
    foreignKeys = [ForeignKey(entity = PlaylistExchangePreviewEntity::class,
        parentColumns = ["previewId"], childColumns = ["previewId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["previewId", "memberId"], unique = true), Index(value = ["previewId", "trackId"])],
)
data class PlaylistExchangeRowEntity(
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
    @ColumnInfo(defaultValue = "NULL") val cueReference: String? = null,
) {
    fun reference(): PlaylistReference = PlaylistReference(
        trackKey = trackId?.let { TrackKey(TrackKey.LOCAL_PROVIDER, it) },
        placeholderId = placeholderId,
        issue = referenceIssue?.let(PlaylistReferenceIssue::valueOf),
        titleHint = title, artistHint = artist, albumHint = album,
        relativePathHint = relativePath, descriptionHint = description, durationMs = durationMs,
        cueReference = cueReference?.let { CueReferenceCodec.decode(it, portableOnly = true) },
    )
}

/** A receipt represents independently reopened bytes; neither a writer return nor an HTTP response qualifies. */
@Entity(tableName = "playlist_export_receipts")
data class PlaylistExportReceiptEntity(
    @PrimaryKey val operationId: String,
    val intentFingerprint: String,
    val memberCount: Long,
    val byteCount: Long,
    val sha256: String,
    val destinationLabel: String?,
    val completedEpochMs: Long,
)

data class PlaylistExchangeCounts(
    val memberCount: Long,
    val matchedCount: Long,
    val ambiguousCount: Long,
    val blockedReferenceCount: Long,
    val duplicateCount: Long,
)
