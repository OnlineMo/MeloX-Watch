package io.github.onlinemo.meloxwatch.data.lyrics.store

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import io.github.onlinemo.meloxwatch.data.library.LibraryTrackEntity

/** Independent nullable columns permit bounded SQL projection before decoding a damaged row. */
@Entity(
    tableName = "lyrics_track_preferences",
    foreignKeys = [ForeignKey(
        entity = LibraryTrackEntity::class,
        parentColumns = ["trackId"],
        childColumns = ["trackId"],
        onDelete = ForeignKey.RESTRICT,
    )],
)
data class LyricsTrackPreferencesEntity(
    @PrimaryKey val trackId: String,
    val schemaVersion: Int,
    val revision: Long,
    val primaryDocumentId: String?,
    val primarySourceId: String?,
    val primaryUri: String?,
    val primaryReadFlags: Int?,
    val primaryEncoding: String?,
    val primaryConfirmedContentSha256: String?,
    val translationDocumentId: String?,
    val translationSourceId: String?,
    val translationUri: String?,
    val translationReadFlags: Int?,
    val translationEncoding: String?,
    val translationConfirmedContentSha256: String?,
    val userDelayMs: Int,
)

/** SQLite may contain wrong types/ranges; longs and bounded strings reach the checked decoder. */
data class LyricsTrackRow(
    val validTypes: Boolean,
    val schemaVersion: Long,
    val revision: Long,
    val primaryDocumentId: String?,
    val primarySourceId: String?,
    val primaryUri: String?,
    val primaryReadFlags: Long?,
    val primaryEncoding: String?,
    val primaryConfirmedContentSha256: String?,
    val translationDocumentId: String?,
    val translationSourceId: String?,
    val translationUri: String?,
    val translationReadFlags: Long?,
    val translationEncoding: String?,
    val translationConfirmedContentSha256: String?,
    val userDelayMs: Long,
)
