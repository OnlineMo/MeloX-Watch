package io.github.onlinemo.meloxwatch.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.onlinemo.meloxwatch.data.library.LibraryAbsenceEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryAliasEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryAliasVersionEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryArtistEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryDao
import io.github.onlinemo.meloxwatch.data.library.LibraryFrontierEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryGrantEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryImportEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryLocationEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryLocationVersionEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryMetaEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryNodeEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryNodeVersionEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryPathMigrations
import io.github.onlinemo.meloxwatch.data.library.LibraryRuleEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryScanEntity
import io.github.onlinemo.meloxwatch.data.library.LibrarySeenEntity
import io.github.onlinemo.meloxwatch.data.library.LibrarySourceEntity
import io.github.onlinemo.meloxwatch.data.library.LibrarySourceVersionEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryTrackEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryTrackLocationEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryTrackVersionEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryViewEntity
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDao
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsMigrations
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackPreferencesEntity
import io.github.onlinemo.meloxwatch.data.listening.ListeningDao
import io.github.onlinemo.meloxwatch.data.listening.ListeningDayEntity
import io.github.onlinemo.meloxwatch.data.listening.ListeningHistoryEntity
import io.github.onlinemo.meloxwatch.data.listening.ListeningMetaEntity
import io.github.onlinemo.meloxwatch.data.listening.ListeningRoundEntity
import io.github.onlinemo.meloxwatch.data.listening.ListeningSegmentEntity
import io.github.onlinemo.meloxwatch.data.listening.ListeningTrackEntity
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistDao
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistGenerationEntity
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistHeadEntity
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMemberEntity
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMetaEntity
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMigrations
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistReceiptEntity
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistViewEntity
import io.github.onlinemo.meloxwatch.data.playlistsexchange.PlaylistExchangeDao
import io.github.onlinemo.meloxwatch.data.playlistsexchange.PlaylistExchangeMigrations
import io.github.onlinemo.meloxwatch.data.playlistsexchange.PlaylistExchangePreviewEntity
import io.github.onlinemo.meloxwatch.data.playlistsexchange.PlaylistExchangeRowEntity
import io.github.onlinemo.meloxwatch.data.playlistsexchange.PlaylistExportReceiptEntity
import io.github.onlinemo.meloxwatch.data.queue.QueueDao
import io.github.onlinemo.meloxwatch.data.queue.QueueEntryEntity
import io.github.onlinemo.meloxwatch.data.queue.QueueGenerationEntity
import io.github.onlinemo.meloxwatch.data.queue.QueueHeadEntity
import io.github.onlinemo.meloxwatch.data.queue.QueueMigrations

/** Source-frozen V4 declaration: generated historical validation, never a fabricated schema export. */
@Database(
    entities = [
        QueueGenerationEntity::class, QueueEntryEntity::class, QueueHeadEntity::class,
        LibraryMetaEntity::class, LibrarySourceEntity::class, LibraryGrantEntity::class, LibrarySourceVersionEntity::class,
        LibraryLocationEntity::class, LibraryLocationVersionEntity::class, LibraryTrackEntity::class,
        LibraryTrackLocationEntity::class, LibraryTrackVersionEntity::class, LibraryArtistEntity::class,
        LibraryNodeEntity::class, LibraryNodeVersionEntity::class, LibraryAliasEntity::class, LibraryAliasVersionEntity::class,
        LibraryScanEntity::class, LibrarySeenEntity::class, LibraryFrontierEntity::class, LibraryAbsenceEntity::class,
        LibraryRuleEntity::class, LibraryViewEntity::class, LibraryImportEntity::class,
        ListeningMetaEntity::class, ListeningRoundEntity::class, ListeningSegmentEntity::class,
        ListeningHistoryEntity::class, ListeningTrackEntity::class, ListeningDayEntity::class,
        PlaylistMetaEntity::class, PlaylistHeadEntity::class, PlaylistGenerationEntity::class,
        PlaylistMemberEntity::class, PlaylistViewEntity::class, PlaylistReceiptEntity::class,
        PlaylistExchangePreviewEntity::class, PlaylistExchangeRowEntity::class, PlaylistExportReceiptEntity::class,
        LyricsTrackPreferencesEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class HistoricalMeloXV4Database : RoomDatabase() {
    abstract fun queueDao(): QueueDao
    abstract fun libraryDao(): LibraryDao
    abstract fun listeningDao(): ListeningDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playlistExchangeDao(): PlaylistExchangeDao
    abstract fun lyricsDao(): LyricsDao

    companion object {
        const val FILE_NAME = "melox.db"
        val MIGRATION_1_2 = LibraryMigrations.V1_TO_V2
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                QueueMigrations.applyV2ToV3(db)
                LibraryPathMigrations.applyV2ToV3(db)
                PlaylistMigrations.applyV2ToV3(db)
                PlaylistExchangeMigrations.applyV2ToV3(db)
            }

            override fun migrate(connection: SQLiteConnection) {
                QueueMigrations.applyV2ToV3(connection)
                LibraryPathMigrations.applyV2ToV3(connection)
                PlaylistMigrations.applyV2ToV3(connection)
                PlaylistExchangeMigrations.applyV2ToV3(connection)
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                LyricsMigrations.applyV3ToV4(db)
            }

            override fun migrate(connection: SQLiteConnection) {
                LyricsMigrations.applyV3ToV4(connection)
            }
        }

        fun open(context: Context, filePath: String): HistoricalMeloXV4Database = Room.databaseBuilder(
            context.applicationContext,
            HistoricalMeloXV4Database::class.java,
            filePath,
        ).setDriver(BundledSQLiteDriver()).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
    }
}
