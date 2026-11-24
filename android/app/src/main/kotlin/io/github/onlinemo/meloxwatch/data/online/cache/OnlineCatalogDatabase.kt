package io.github.onlinemo.meloxwatch.data.online.cache

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Online cache has its own no-backup database; account cache cleanup cannot address local music tables. */
@Entity(tableName = "online_catalog_view", indices = [Index(value = ["ownerHash", "queryKey", "published"])])
internal data class OnlineCatalogViewEntity(
    @PrimaryKey val id: String,
    val ownerHash: String,
    val queryKey: String,
    val kind: String,
    val revision: Long = 0L,
    val count: Long = 0L,
    val rawOffset: Long = 0L,
    val reportedTotal: Long? = null,
    val indexed: Boolean = false,
    val complete: Boolean = false,
    val metadataCursor: Long = 0L,
    val missingMetadataCount: Long = 0L,
    val cachedAtEpochMs: Long,
    val published: Boolean = false,
    val usedBytes: Long = 0L,
)

@Entity(tableName = "online_catalog_row", primaryKeys = ["viewId", "position"],
    foreignKeys = [ForeignKey(entity = OnlineCatalogViewEntity::class, parentColumns = ["id"],
        childColumns = ["viewId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["viewId", "itemId"])])
internal data class OnlineCatalogRowEntity(
    val viewId: String,
    val position: Long,
    val kind: String,
    val itemId: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val trackCount: Long? = null,
    val artworkKey: String? = null,
    val metadataMissing: Boolean = false,
) {
    fun estimatedBytes(): Long = 160L + listOf(itemId, title, artist, album, artworkKey)
        .sumOf { it?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L }
}

@Dao
internal interface OnlineCatalogDao {
    // A completed refresh wins even if the wall clock repeats or moves backwards.
    @Query("SELECT * FROM online_catalog_view WHERE ownerHash = :owner AND queryKey = :queryKey AND published = 1 ORDER BY rowid DESC LIMIT 1")
    suspend fun cached(owner: String, queryKey: String): OnlineCatalogViewEntity?
    @Query("SELECT * FROM online_catalog_view WHERE id = :id")
    suspend fun view(id: String): OnlineCatalogViewEntity?
    @Query("SELECT * FROM online_catalog_view ORDER BY cachedAtEpochMs ASC, id ASC LIMIT 16")
    suspend fun oldest(): List<OnlineCatalogViewEntity>
    @Query("SELECT COALESCE(SUM(usedBytes), 0) FROM online_catalog_view")
    suspend fun usedBytes(): Long
    @Query("SELECT COUNT(*) FROM online_catalog_view")
    suspend fun viewCount(): Long
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertView(view: OnlineCatalogViewEntity)
    @Update
    suspend fun putView(view: OnlineCatalogViewEntity): Int
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRows(rows: List<OnlineCatalogRowEntity>)
    @Query("DELETE FROM online_catalog_view WHERE id = :id")
    suspend fun delete(id: String)
    @Query("SELECT * FROM online_catalog_row WHERE viewId = :viewId AND position >= :position ORDER BY position ASC LIMIT :limit")
    suspend fun rows(viewId: String, position: Long, limit: Int): List<OnlineCatalogRowEntity>
    @Query("SELECT itemId FROM online_catalog_row WHERE viewId = :viewId AND itemId IN (:ids)")
    suspend fun existingIds(viewId: String, ids: List<String>): List<String>
    @Query("SELECT r.* FROM online_catalog_row r JOIN online_catalog_view v ON v.id = r.viewId WHERE v.ownerHash = :owner AND v.published = 1 AND r.kind = 'TRACK' AND r.itemId = :id ORDER BY v.rowid DESC, r.position ASC LIMIT 1")
    suspend fun track(owner: String, id: String): OnlineCatalogRowEntity?
}

@Database(entities = [OnlineCatalogViewEntity::class, OnlineCatalogRowEntity::class], version = 1, exportSchema = true)
internal abstract class OnlineCatalogDatabase : RoomDatabase() {
    abstract fun catalog(): OnlineCatalogDao

    companion object {
        suspend fun open(context: Context): OnlineCatalogDatabase = withContext(Dispatchers.IO) {
            val application = context.applicationContext
            Room.databaseBuilder(application, OnlineCatalogDatabase::class.java,
                application.noBackupFilesDir.resolve("online-catalog.db").absolutePath)
                .setJournalMode(JournalMode.TRUNCATE).build()
        }
    }
}
