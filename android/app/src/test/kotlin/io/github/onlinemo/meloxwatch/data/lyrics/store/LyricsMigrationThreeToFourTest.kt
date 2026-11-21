package io.github.onlinemo.meloxwatch.data.lyrics.store

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.onlinemo.meloxwatch.data.database.FrozenCell
import io.github.onlinemo.meloxwatch.data.database.FrozenCursorRows
import io.github.onlinemo.meloxwatch.data.database.FrozenRoomSchemaTestFixture
import io.github.onlinemo.meloxwatch.data.database.HistoricalMeloXV3Database
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.database.TestDatabaseSql
import io.github.onlinemo.meloxwatch.data.database.cursorStorageType
import io.github.onlinemo.meloxwatch.data.database.withTestWriterSql
import io.github.onlinemo.meloxwatch.data.database.withTestWriterTransaction
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Historical 3→4 DDL, production 1→5 opens and pinned 1/2 schemas remain separate oracles. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
internal class LyricsMigrationThreeToFourTest {
    @get:Rule val folder = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private val key = TrackKey(TrackKey.LOCAL_PROVIDER, LyricsRoomRows.TRACK_ID)
    private val saved = LyricsTrackPreferences(key, revision = 7L, primary = LyricsRoomRows.association(),
        translation = LyricsRoomRows.association(translation = true), userDelayMs = -300)

    @Test(timeout = 30_000L)
    fun generatedV3ToProductionV5AddsLyricsAndTransfersAndKeepsEveryOldObjectCellAndReopenIdentity() = runBlocking {
        val file = File(folder.root, "from-generated-v3.db")
        val before = createVersionThree(file)
        val database = open(file)
        val identity = try {
            val value = assertCurrentSchema(database)
            assertFalse(value == before.identity)
            assertV3Unchanged(database, before)
            assertTrue(database.lyricsDao().insert(saved.entity()) != -1L)
            assertEquals(LyricsTrackReadResult.Ready(saved), RoomLyricsTrackStore(database, database.lyricsDao()).read(key))
            assertLyricsForeignKey(database)
            assertV3Unchanged(database, before)
            value
        } finally { database.close() }
        val reopened = open(file)
        try {
            assertEquals(identity, assertCurrentSchema(reopened, expectedLyricsRows = 1L))
            assertV3Unchanged(reopened, before)
            assertEquals(LyricsTrackReadResult.Ready(saved), RoomLyricsTrackStore(reopened, reopened.lyricsDao()).read(key))
        } finally { reopened.close() }
    }

    @Test(timeout = 30_000L)
    fun frozenVersionOneOpensThroughTheFullProductionChainAndReopensWithDurableLyrics() = runBlocking {
        exerciseFrozenChain(version = 1)
    }

    @Test(timeout = 30_000L)
    fun frozenVersionTwoOpensThroughTheFullProductionChainAndReopensWithDurableLyrics() = runBlocking {
        exerciseFrozenChain(version = 2)
    }

    @Test(timeout = 30_000L)
    fun supportSqliteOverloadAddsTheSameLyricsStructureAndPreservesTheV3CellsAndCatalog() = runBlocking {
        val file = File(folder.root, "support-v3-to-v4.db")
        val before = createVersionThree(file)
        val helper = supportOpen(file, failAfterDdl = false)
        try {
            val sqlite = helper.writableDatabase
            assertEquals(4, sqlite.version)
            assertEquals(before.identity, sqlite.query(IDENTITY_QUERY).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(0)
            })
            assertEquals(before.catalog, cursorRows(sqlite.query(CATALOG_QUERY)))
            for ((table, rows) in before.rows) assertEquals(table, rows, cursorRows(sqlite.query(select(table))))
            sqlite.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            sqlite.query("PRAGMA integrity_check").use { assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0)) }
            // A direct SupportSQLite migration does not forge Room's generated v4 master identity.
            val reference = open(File(folder.root, "generated-current-structure.db"))
            try {
                for (sql in listOf("PRAGMA table_info(lyrics_track_preferences)", "PRAGMA foreign_key_list(lyrics_track_preferences)")) {
                    val expected = reference.withTestWriterSql { query(sql, block = ::statementRows) }
                    assertEquals(expected, cursorRows(sqlite.query(sql)))
                }
            } finally { reference.close() }
        } finally { helper.close() }
    }

    @Test(timeout = 30_000L)
    fun failedBundledMigrationRollsBackItsRealDdlVersionIdentityAndRowsBeforeAProductionRetry() = runBlocking {
        val file = File(folder.root, "bundled-rollback.db")
        val before = createVersionThree(file)
        val entered = AtomicBoolean(false)
        val failure = ControlledMigrationFailure()
        val failing = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                MeloXDatabase.MIGRATION_3_4.migrate(connection)
                connection.prepare("SELECT COUNT(*) FROM lyrics_track_preferences").use {
                    assertTrue(it.step())
                    assertEquals(0L, it.getLong(0))
                }
                entered.set(true)
                throw failure
            }
        }
        val database = open(file, failing)
        try {
            val thrown = failureFrom { database.withTestWriterSql { scalarLong("PRAGMA user_version") } }
            assertTrue(entered.get())
            assertTrue(generateSequence<Throwable>(thrown) { it.cause }.any { it === failure })
        } finally { database.close() }
        assertRollbackAndRetry(file, before)
    }

    @Test(timeout = 30_000L)
    fun failedSupportSqliteMigrationAlsoRollsBackTheTableAndKeepsTheOriginalRoomIdentity() = runBlocking {
        val file = File(folder.root, "support-rollback.db")
        val before = createVersionThree(file)
        val helper = supportOpen(file, failAfterDdl = true)
        try {
            val failure = failureFrom { helper.writableDatabase }
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it is ControlledMigrationFailure })
        } finally { helper.close() }
        assertRollbackAndRetry(file, before)
    }

    private suspend fun exerciseFrozenChain(version: Int) {
        val file = File(folder.root, "from-frozen-v$version.db")
        val schema = frozenSchema(version)
        val before = SQLiteDatabase.openOrCreateDatabase(file, null).use { sqlite ->
            sqlite.setForeignKeyConstraintsEnabled(true)
            schema.create(sqlite)
            seedOldQueue(sqlite)
            if (version == 2) {
                sqlite.execSQL("INSERT INTO library_meta VALUES (1, 0, 0, 1, 1, 0, 0)")
                sqlite.execSQL("INSERT INTO listening_meta VALUES (1, 0, 0, 1, 1, 1, 0, 0)")
                sqlite.execSQL("INSERT INTO local_tracks VALUES (?, 'local', 1794787200000, 1)", arrayOf(key.trackId))
            }
            sqlite.version = version
            schema.snapshotRows { sqlite.rawQuery(it, null) }
        }
        val database = open(file)
        val identity = try {
            val value = assertCurrentSchema(database)
            database.withTestWriterSql { schema.assertRowsPreserved(before, this) }
            val dao = database.queueDao()
            assertEquals(listOf(2L, 1L, 0L), dao.page("migration-generation", 0L, 10).map { it.originalOrder })
            assertEquals("migration-entry-1", checkNotNull(dao.head()).currentEntryId)
            assertEquals(4_321L, checkNotNull(dao.head()).positionMs)
            assertEquals(3, dao.page("migration-generation", 0L, 10).map { it.queueEntryId }.distinct().size)
            if (version == 1) database.withTestWriterTransaction {
                execute("INSERT INTO local_tracks VALUES (?, 'local', 1794787200000, 1)", key.trackId)
            }
            assertTrue(database.lyricsDao().insert(saved.entity()) != -1L)
            assertEquals(LyricsTrackReadResult.Ready(saved), RoomLyricsTrackStore(database, database.lyricsDao()).read(key))
            value
        } finally { database.close() }
        val reopened = open(file)
        try {
            assertEquals(identity, assertCurrentSchema(reopened, expectedLyricsRows = 1L))
            // v1's historical schema has only queue rows, so enrolling one new lyric track is independent.
            reopened.withTestWriterSql { schema.assertRowsPreserved(before, this) }
            assertEquals(LyricsTrackReadResult.Ready(saved), RoomLyricsTrackStore(reopened, reopened.lyricsDao()).read(key))
        } finally { reopened.close() }
    }

    private suspend fun createVersionThree(file: File): V3Observation {
        val historical = HistoricalMeloXV3Database.open(context, file.absolutePath)
        try {
            historical.withTestWriterTransaction {
                LyricsRoomRows.seed { table, values -> insert(table, values) }
                execute("INSERT INTO listening_meta VALUES (1, 2, 3, 1, 13, 2, 9, 87654)")
                execute("INSERT INTO listening_track_totals VALUES (3, 'local', ?, 9, 87654, 1794787200000, 'Preserved title')", key.trackId)
                execute("INSERT INTO playlist_meta VALUES (1, 2, 3)")
                execute("INSERT INTO playlist_generations VALUES ('migration-playlist-generation', 'migration-playlist', 'migration-playlist-generation', " +
                    "'migration-playlist-operation', 'fixed-fingerprint', 'PUBLISHED', 2, 'Preserved list', 'preserved list', 'USER', " +
                    "1794787200000, 1794787200000, 1, 2, NULL, 'historical-process', NULL, NULL, NULL)")
                execute("INSERT INTO playlist_heads VALUES ('migration-playlist', 'migration-playlist-generation', 2, 2, 0)")
                execute("INSERT INTO playlist_members VALUES ('migration-playlist-generation', 'migration-member', 0, 'local-ref', 'local', ?, " +
                    "NULL, NULL, 'Preserved title', NULL, NULL, NULL, NULL, 180000)", key.trackId)
                execute("INSERT INTO playlist_views VALUES ('migration-playlist-view', 'MEMBERS', 'migration-playlist-generation', 2, 1, 'UI', 601000, 'historical-process')")
                execute("INSERT INTO playlist_receipts VALUES ('migration-playlist-operation', 'migration-playlist', 'fixed-fingerprint', 2, 'CREATED', 1, 0, 0, 0)")
                execute("INSERT INTO playlist_exchange_previews VALUES ('migration-preview', 'historical-process', 'READY', 1, 0, 601000, 1, 'Preserved list', 128, ?)", "a".repeat(64))
                execute("INSERT INTO playlist_exchange_rows VALUES ('migration-preview', 0, 'migration-member', ?, NULL, NULL, 'Preserved title', " +
                    "NULL, NULL, NULL, NULL, 180000, 'NONE', 'READABLE')", key.trackId)
                execute("INSERT INTO playlist_export_receipts VALUES ('migration-export', 'fixed-export-fingerprint', 1, 128, ?, 'Selected document', 1794787200000)", "b".repeat(64))
            }
            return historical.withTestWriterSql {
                assertEquals(3L, scalarLong("PRAGMA user_version"))
                val tables = applicationTables()
                assertEquals(V3_TABLES, tables.toSet())
                V3Observation(tables.associateWith { query(select(it), block = ::statementRows) },
                    query(CATALOG_QUERY, block = ::statementRows), checkNotNull(scalarText(IDENTITY_QUERY)))
            }
        } finally { historical.close() }
    }

    private suspend fun assertV3Unchanged(database: RoomDatabase, before: V3Observation) = database.withTestWriterSql {
        assertEquals(before.catalog, query(CATALOG_QUERY, block = ::statementRows))
        for ((table, rows) in before.rows) assertEquals(table, rows, query(select(table), block = ::statementRows))
    }

    private suspend fun assertRollbackAndRetry(file: File, before: V3Observation) {
        val historical = HistoricalMeloXV3Database.open(context, file.absolutePath)
        try {
            historical.withTestWriterSql {
                assertEquals(3L, scalarLong("PRAGMA user_version"))
                assertEquals(before.identity, scalarText(IDENTITY_QUERY))
                assertEquals(V3_TABLES, applicationTables().toSet())
                assertEquals(0L, scalarLong("SELECT COUNT(*) FROM sqlite_master WHERE name = 'lyrics_track_preferences'"))
            }
            assertV3Unchanged(historical, before)
        } finally { historical.close() }
        val retried = open(file)
        try { assertCurrentSchema(retried); assertV3Unchanged(retried, before) }
        finally { retried.close() }
    }

    private suspend fun assertCurrentSchema(database: RoomDatabase, expectedLyricsRows: Long = 0L): String = database.withTestWriterSql {
        assertEquals(5L, scalarLong("PRAGMA user_version"))
        assertEquals(V3_TABLES + setOf("lyrics_track_preferences", "transferred_import_operations"), applicationTables().toSet())
        assertEquals(expectedLyricsRows, scalarLong("SELECT COUNT(*) FROM lyrics_track_preferences"))
        query("PRAGMA table_info(lyrics_track_preferences)") { rows ->
            val actual = buildList { while (rows.step()) add(Column(rows.getText(1), rows.getText(2), rows.getLong(3), rows.getLong(5))) }
            assertEquals(LYRICS_COLUMNS, actual)
        }
        query("PRAGMA foreign_key_list(lyrics_track_preferences)") {
            assertTrue(it.step())
            assertEquals("local_tracks", it.getText(2))
            assertEquals("trackId", it.getText(3))
            assertEquals("trackId", it.getText(4))
            assertEquals("NO ACTION", it.getText(5))
            assertEquals("RESTRICT", it.getText(6))
            assertFalse(it.step())
        }
        query("PRAGMA foreign_key_check") { assertFalse(it.step()) }
        assertEquals("ok", scalarText("PRAGMA integrity_check"))
        checkNotNull(scalarText(IDENTITY_QUERY)).also { assertTrue(Regex("[0-9a-f]{32}").matches(it)) }
    }

    private suspend fun assertLyricsForeignKey(database: MeloXDatabase) {
        val probeKey = TrackKey(TrackKey.LOCAL_PROVIDER, "lyrics-foreign-key-probe")
        val probe = LyricsTrackPreferences(probeKey, userDelayMs = 100)
        database.withTestWriterTransaction { execute("INSERT INTO local_tracks VALUES (?, 'local', 1794787200000, 99)", probeKey.trackId) }
        assertTrue(database.lyricsDao().insert(probe.entity()) != -1L)
        failureFrom { database.withTestWriterTransaction { execute("DELETE FROM local_tracks WHERE trackId = ?", probeKey.trackId) } }
        database.withTestWriterSql {
            assertEquals(1L, scalarLong("SELECT COUNT(*) FROM local_tracks WHERE trackId = ?", probeKey.trackId))
            assertEquals(1L, scalarLong("SELECT COUNT(*) FROM lyrics_track_preferences WHERE trackId = ?", probeKey.trackId))
        }
        database.withTestWriterTransaction {
            execute("DELETE FROM lyrics_track_preferences WHERE trackId = ?", probeKey.trackId)
            execute("DELETE FROM local_tracks WHERE trackId = ?", probeKey.trackId)
        }
        failureFrom { database.lyricsDao().insert(probe.entity()) }
    }

    private fun open(file: File, lastMigration: Migration = MeloXDatabase.MIGRATION_3_4): MeloXDatabase = Room.databaseBuilder(
        context, MeloXDatabase::class.java, file.absolutePath,
    ).setDriver(BundledSQLiteDriver()).addMigrations(MeloXDatabase.MIGRATION_1_2, MeloXDatabase.MIGRATION_2_3, lastMigration, MeloXDatabase.MIGRATION_4_5).build()

    private fun supportOpen(file: File, failAfterDdl: Boolean): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(4) {
            override fun onConfigure(db: SupportSQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
            override fun onCreate(db: SupportSQLiteDatabase) { throw AssertionError("A real generated v3 file is required") }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                assertEquals(3, oldVersion)
                assertEquals(4, newVersion)
                MeloXDatabase.MIGRATION_3_4.migrate(db)
                db.query("SELECT COUNT(*) FROM lyrics_track_preferences").use { assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0)) }
                if (failAfterDdl) throw ControlledMigrationFailure()
            }
        }
        return FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(file.absolutePath).callback(callback).build())
    }

    private fun seedOldQueue(sqlite: SQLiteDatabase) {
        sqlite.execSQL("INSERT INTO queue_generations VALUES ('migration-generation', 'migration-operation', NULL, 0, 'migration-view', " +
            "7, 3, 1, 'SINGLE', 2, 1, 100, 0, 2, 3, 1)")
        repeat(3) { index ->
            sqlite.execSQL("INSERT INTO queue_entries VALUES ('migration-generation', ?, 'local', ?, ?, ?, 'Historical duplicate', 180000, " +
                "NULL, NULL, NULL, NULL, NULL, NULL, NULL)", arrayOf<Any?>("migration-entry-$index", key.trackId, index, 2 - index))
        }
        sqlite.execSQL("INSERT INTO queue_head VALUES (1, 'migration-generation', 1, 3, 'migration-entry-1', 4321, 1)")
    }

    private fun frozenSchema(version: Int): FrozenRoomSchemaTestFixture = if (version == 1) {
        FrozenRoomSchemaTestFixture.load("/database/melox-schema-1.json", 1,
            "3a36d662a8ee80e70d76180d564f8d9858243631dd20e2648c2492df8ff39c79", "b7264fee4b8a13461a12aed516b5dcaf")
    } else {
        assertEquals(2, version)
        FrozenRoomSchemaTestFixture.load("/database/melox-schema-2.json", 2,
            "bab895d032d46556e4615a44130dc523e8942e942cd34ab68832969ecc7696bf", "f785aceb921839fb08cede7dd1eba81a")
    }

    private suspend fun TestDatabaseSql.insert(table: String, values: ContentValues) {
        val columns = values.keySet().toList()
        execute("INSERT INTO `$table` (${columns.joinToString(", ") { "`$it`" }}) VALUES (${columns.joinToString(", ") { "?" }})",
            *columns.map(values::get).toTypedArray())
    }

    private suspend fun TestDatabaseSql.applicationTables(): List<String> = query(TABLES_QUERY) {
        buildList { while (it.step()) add(it.getText(0)) }
    }

    private fun select(table: String): String = "SELECT * FROM `$table` ORDER BY " + when (table) {
        "queue_entries" -> "generationId, queueEntryId"
        "local_source_versions" -> "sourceId, validFrom"
        "local_artist_values" -> "versionId, ordinal"
        "scan_seen", "scan_frontier" -> "scanId, objectId"
        "scan_absence_candidates" -> "scanId, aliasId"
        "listening_round_segments" -> "roundId, ordinal"
        "listening_history" -> "historyEpoch, historyId"
        "listening_track_totals" -> "statisticsEpoch, providerId, trackId"
        "listening_day_totals" -> "statisticsEpoch, localDate, zoneId"
        "playlist_members" -> "generationId, memberId"
        "playlist_exchange_rows" -> "previewId, ordinal"
        else -> "1"
    }

    private fun statementRows(rows: SQLiteStatement): FrozenCursorRows = FrozenCursorRows(
        (0 until rows.getColumnCount()).map(rows::getColumnName), buildList {
            while (rows.step()) add((0 until rows.getColumnCount()).map { index ->
                val type = rows.cursorStorageType(index)
                FrozenCell(type, when (type) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_BLOB -> rows.getBlob(index).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
                    else -> rows.getText(index)
                })
            })
        })

    private fun cursorRows(cursor: Cursor): FrozenCursorRows = cursor.use { rows ->
        FrozenCursorRows(rows.columnNames.toList(), buildList {
            while (rows.moveToNext()) add((0 until rows.columnCount).map { index ->
                val type = rows.getType(index)
                FrozenCell(type, when (type) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_BLOB -> rows.getBlob(index).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
                    else -> rows.getString(index)
                })
            })
        })
    }

    private suspend fun failureFrom(action: suspend () -> Any?): Exception = try {
        action()
        throw AssertionError("A native migration or constraint failure is required")
    } catch (failure: Exception) { failure }

    private class ControlledMigrationFailure : IllegalStateException("Controlled failure after real migration DDL")
    private data class V3Observation(val rows: Map<String, FrozenCursorRows>, val catalog: FrozenCursorRows, val identity: String)
    private data class Column(val name: String, val type: String, val notNull: Long, val primaryKey: Long = 0L)

    companion object {
        private const val IDENTITY_QUERY = "SELECT identity_hash FROM room_master_table WHERE id = 42"
        private const val TABLES_QUERY = "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
            "AND name NOT IN ('room_master_table', 'android_metadata') ORDER BY name"
        private const val CATALOG_QUERY = "SELECT type, name, tbl_name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' " +
            "AND name NOT IN ('room_master_table', 'android_metadata') AND tbl_name NOT IN ('lyrics_track_preferences', 'transferred_import_operations') ORDER BY type, name"
        private val V3_TABLES = setOf(
            "queue_generations", "queue_entries", "queue_head", "library_meta", "local_sources", "local_grants", "local_source_versions",
            "local_locations", "local_location_versions", "local_tracks", "local_track_locations", "local_track_versions", "local_artist_values",
            "local_nodes", "local_node_versions", "local_access_aliases", "local_alias_versions", "scan_generations", "scan_seen", "scan_frontier",
            "scan_absence_candidates", "scan_rules", "library_views", "import_operations", "listening_meta", "listening_rounds",
            "listening_round_segments", "listening_history", "listening_track_totals", "listening_day_totals", "playlist_meta", "playlist_heads",
            "playlist_generations", "playlist_members", "playlist_views", "playlist_receipts", "playlist_exchange_previews", "playlist_exchange_rows",
            "playlist_export_receipts",
        )
        private val LYRICS_COLUMNS = listOf(
            Column("trackId", "TEXT", 1L, 1L), Column("schemaVersion", "INTEGER", 1L), Column("revision", "INTEGER", 1L),
            Column("primaryDocumentId", "TEXT", 0L), Column("primarySourceId", "TEXT", 0L), Column("primaryUri", "TEXT", 0L),
            Column("primaryReadFlags", "INTEGER", 0L), Column("primaryEncoding", "TEXT", 0L), Column("primaryConfirmedContentSha256", "TEXT", 0L),
            Column("translationDocumentId", "TEXT", 0L), Column("translationSourceId", "TEXT", 0L), Column("translationUri", "TEXT", 0L),
            Column("translationReadFlags", "INTEGER", 0L), Column("translationEncoding", "TEXT", 0L), Column("translationConfirmedContentSha256", "TEXT", 0L),
            Column("userDelayMs", "INTEGER", 1L),
        )
    }
}
