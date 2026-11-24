package io.github.onlinemo.meloxwatch.data.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import io.github.onlinemo.meloxwatch.data.library.LibraryImportEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryLocationEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryMetaEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryTrackEntity

/** 冻结旧曲目与艺人实体，新增列不能改变历史迁移的结构预期。 */
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
data class HistoricalTrackVersionEntity(
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
)

@Entity(
    tableName = "local_artist_values",
    primaryKeys = ["versionId", "ordinal"],
    foreignKeys = [ForeignKey(entity = HistoricalTrackVersionEntity::class, parentColumns = ["versionId"], childColumns = ["versionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["artistKey", "versionId"], name = "index_local_artist_values_artistKey_versionId")],
)
data class HistoricalArtistEntity(val versionId: String, val ordinal: Int, val artistKey: String, val displayName: String?)

/** 历史迁移样本只使用这些旧版本读取和初始化写入。 */
@Dao
interface HistoricalLibraryDao {
    @Query("SELECT * FROM library_meta WHERE id = 1") suspend fun meta(): LibraryMetaEntity?
    @Query("SELECT * FROM local_tracks WHERE trackId = :id") suspend fun track(id: String): LibraryTrackEntity?
    @Query("SELECT * FROM local_locations WHERE locationId = :id") suspend fun location(id: String): LibraryLocationEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertMeta(value: LibraryMetaEntity): Long
    @Insert suspend fun insertTrack(value: LibraryTrackEntity)
    @Insert suspend fun insertImport(value: LibraryImportEntity)
}
