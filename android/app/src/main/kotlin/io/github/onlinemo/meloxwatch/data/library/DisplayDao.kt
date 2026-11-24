package io.github.onlinemo.meloxwatch.data.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Kept separate so historical V5 database declarations retain their original DAO contracts. */
@Dao
interface DisplayDao {
    @Query("SELECT * FROM display_groups WHERE entityId = :id")
    suspend fun group(id: String): DisplayGroupEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGroup(value: DisplayGroupEntity): Long

    @Query("UPDATE display_groups SET editedFrom = COALESCE(editedFrom, :revision) WHERE entityId = :id")
    suspend fun markEdited(id: String, revision: Long): Int

    @Query("SELECT * FROM display_names WHERE groupId = :id AND validTo IS NULL")
    suspend fun currentName(id: String): DisplayNameEntity?

    @Query("UPDATE display_names SET validTo = :revision - 1 WHERE groupId = :id AND validTo IS NULL")
    suspend fun closeName(id: String, revision: Long)

    @Insert suspend fun insertName(value: DisplayNameEntity)

    @Query("SELECT * FROM display_album_links WHERE trackId = :id AND validTo IS NULL")
    suspend fun currentAlbum(id: String): DisplayAlbumLinkEntity?

    @Query("UPDATE display_album_links SET validTo = :revision - 1 WHERE trackId = :id AND validTo IS NULL")
    suspend fun closeAlbum(id: String, revision: Long)

    @Insert suspend fun insertAlbum(value: DisplayAlbumLinkEntity)

    @Query("SELECT * FROM display_artist_links WHERE trackId = :trackId AND groupId = :groupId AND validTo IS NULL")
    suspend fun currentArtist(trackId: String, groupId: String): DisplayArtistLinkEntity?

    @Query("UPDATE display_artist_links SET validTo = :revision - 1 WHERE trackId = :trackId AND groupId = :groupId AND validTo IS NULL")
    suspend fun closeArtist(trackId: String, groupId: String, revision: Long)

    @Insert suspend fun insertArtist(value: DisplayArtistLinkEntity)

    @Query("""
        DELETE FROM display_names WHERE versionId IN (
            SELECT p.versionId FROM display_names p WHERE p.validTo IS NOT NULL
            AND NOT EXISTS (SELECT 1 FROM library_views v WHERE v.revision BETWEEN p.validFrom AND p.validTo)
            LIMIT 100)
    """)
    suspend fun cleanNames(): Int

    @Query("""
        DELETE FROM display_album_links WHERE versionId IN (
            SELECT p.versionId FROM display_album_links p WHERE p.validTo IS NOT NULL
            AND NOT EXISTS (SELECT 1 FROM library_views v WHERE v.revision BETWEEN p.validFrom AND p.validTo)
            LIMIT 100)
    """)
    suspend fun cleanAlbums(): Int

    @Query("""
        DELETE FROM display_artist_links WHERE versionId IN (
            SELECT p.versionId FROM display_artist_links p WHERE p.validTo IS NOT NULL
            AND NOT EXISTS (SELECT 1 FROM library_views v WHERE v.revision BETWEEN p.validFrom AND p.validTo)
            LIMIT 100)
    """)
    suspend fun cleanArtists(): Int
}
