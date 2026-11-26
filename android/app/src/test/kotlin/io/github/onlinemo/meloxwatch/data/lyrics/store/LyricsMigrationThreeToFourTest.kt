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

/** 历史 3→4 DDL、生产 1→8 打开过程和固定的 1/2 schema 分别作为独立判定依据。 */
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
    fun generatedV3ToProductionV8AddsLyricsTransfersAndCueAndKeepsEveryOldObjectCellAndReopenIdentity() = runBlocking {
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
            assertEquals(before.fullCatalog, cursorRows(sqlite.query(FULL_V3_CATALOG_QUERY)))
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
                    query(CATALOG_QUERY, block = ::statementRows), checkNotNull(scalarText(IDENTITY_QUERY)),
                    listOf("queue_entries", "local_track_versions", "playlist_members", "playlist_exchange_rows")
                        .associateWith { query("PRAGMA table_info($it)", block = ::statementRows) },
                    query(FULL_V3_CATALOG_QUERY, block = ::statementRows))
            }
        } finally { historical.close() }
    }

    private suspend fun assertV3Unchanged(database: RoomDatabase, before: V3Observation) = database.withTestWriterSql {
        val version = scalarLong("PRAGMA user_version")
        val codecAdded = version == 7L || version == 8L
        val cueAdded = version == 8L
        val codecCatalog = if (codecAdded) catalogWithCodecColumn(before.fullCatalog) else before.fullCatalog
        val expectedCatalog = if (cueAdded) catalogWithCueAdditions(codecCatalog) else codecCatalog
        assertEquals(expectedCatalog, query(FULL_V3_CATALOG_QUERY, block = ::statementRows))
        assertEquals(before.catalog, query(CATALOG_QUERY, block = ::statementRows))
        if (codecAdded) {
            val columns = query("PRAGMA table_info(local_track_versions)") { statement ->
                buildList { while (statement.step()) add(statement.getText(1)) }
            }
            val addedColumns = if (cueAdded) listOf("codecParameters", "cueReference") else listOf("codecParameters")
            assertEquals(before.rows.getValue("local_track_versions").columns + addedColumns, columns)
            assertEquals(0L, scalarLong("SELECT COUNT(*) FROM local_track_versions WHERE codecParameters IS NOT NULL"))
        }
        for ((table, rows) in before.rows) assertEquals(table, rows, query(select(table, rows.columns), block = ::statementRows))
        for ((table, structure) in before.changedTableStructure) {
            val current = query("PRAGMA table_info($table)", block = ::statementRows)
            val oldColumns = structure.rows.map { it[1].value }.toSet()
            assertEquals(table, structure, current.copy(rows = current.rows.filter { it[1].value in oldColumns }))
            if (cueAdded) {
                val addedColumns = if (table == "local_track_versions") listOf("codecParameters", "cueReference") else listOf("cueReference")
                assertEquals(table, before.rows.getValue(table).columns + addedColumns, current.rows.map { it[1].value })
                assertEquals(0L, scalarLong("SELECT COUNT(*) FROM $table WHERE cueReference IS NOT NULL"))
            }
        }
    }

    /** V7仅增加这一个nullable列；旧定义、索引和原单元格仍逐字比较。 */
    private fun catalogWithCodecColumn(before: FrozenCursorRows): FrozenCursorRows {
        val typeColumn = before.columns.indexOf("type")
        val nameColumn = before.columns.indexOf("name")
        val sqlColumn = before.columns.indexOf("sql")
        check(listOf(typeColumn, nameColumn, sqlColumn).all { it >= 0 })
        var changed = 0
        val rows = before.rows.map { row ->
            if (row[typeColumn].value == "table" && row[nameColumn].value == "local_track_versions") {
                changed++
                val sql = checkNotNull(row[sqlColumn].value)
                check(sql.endsWith(")"))
                // SQLite 3.50.1在首个表约束前插入新列，原列定义、表约束和索引仍逐字比较。
                val boundary = ", PRIMARY KEY("
                val insertAt = sql.indexOf(boundary)
                check(insertAt > 0 && sql.indexOf(boundary, insertAt + 1) < 0)
                check(sql.substring(0, insertAt).endsWith("`statisticsEpoch` INTEGER NOT NULL"))
                val expected = sql.substring(0, insertAt) + ", codecParameters TEXT DEFAULT NULL" + sql.substring(insertAt)
                row.mapIndexed { index, cell ->
                    if (index == sqlColumn) cell.copy(value = expected)
                    else cell
                }
            } else row
        }
        assertEquals(1, changed)
        return before.copy(rows = rows)
    }

    /** V8只给四张旧表增加nullable列及两个索引；原DDL的约束、索引和单元格继续精确比较。 */
    private fun catalogWithCueAdditions(before: FrozenCursorRows): FrozenCursorRows {
        assertEquals(listOf("type", "name", "tbl_name", "sql"), before.columns)
        val changedTables = setOf("local_track_versions", "queue_entries", "playlist_members", "playlist_exchange_rows")
        val changed = mutableSetOf<String>()
        val rows = before.rows.map { row ->
            val name = row[1].value
            if (row[0].value == "table" && name in changedTables) {
                check(changed.add(checkNotNull(name)))
                val sql = checkNotNull(row[3].value)
                check(sql.endsWith(")") && !sql.contains("cueReference"))
                // SQLite 3.50.1仍在首个表约束前插入列，codec列必须已经按6→7增加。
                val boundary = ", PRIMARY KEY("
                val insertAt = sql.indexOf(boundary)
                check(insertAt > 0 && sql.indexOf(boundary, insertAt + 1) < 0)
                if (name == "local_track_versions") check(sql.substring(0, insertAt).endsWith("codecParameters TEXT DEFAULT NULL"))
                val expected = sql.substring(0, insertAt) + ", cueReference TEXT DEFAULT NULL" + sql.substring(insertAt)
                row.mapIndexed { index, cell -> if (index == 3) cell.copy(value = expected) else cell }
            } else row
        }
        assertEquals(changedTables, changed)
        val addedIndices = listOf(
            listOf("index", "index_playlist_members_providerId_trackId_generationId_memberId", "playlist_members",
                "CREATE INDEX index_playlist_members_providerId_trackId_generationId_memberId ON playlist_members(providerId, trackId, generationId, memberId)"),
            listOf("index", "index_queue_entries_generationId_providerId_trackId_queueEntryId", "queue_entries",
                "CREATE INDEX index_queue_entries_generationId_providerId_trackId_queueEntryId ON queue_entries(generationId, providerId, trackId, queueEntryId)"),
        )
        for (index in addedIndices) assertFalse(before.rows.any { it[1].value == index[1] })
        val expectedIndices = addedIndices.map { row -> row.map { FrozenCell(Cursor.FIELD_TYPE_STRING, it) } }
        return before.copy(rows = (rows + expectedIndices).sortedWith(compareBy({ it[0].value }, { it[1].value })))
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
        assertEquals(8L, scalarLong("PRAGMA user_version"))
        assertEquals(
            V3_TABLES + setOf("lyrics_track_preferences", "transferred_import_operations") + VERSION_SIX_NEW_TABLES + VERSION_EIGHT_CUE_TABLES,
            applicationTables().toSet(),
        )
        assertEquals(expectedLyricsRows, scalarLong("SELECT COUNT(*) FROM lyrics_track_preferences"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM transferred_import_operations"))
        for (table in VERSION_SIX_NEW_TABLES + VERSION_EIGHT_CUE_TABLES) {
            assertEquals(0L, scalarLong("SELECT COUNT(*) FROM `$table`"))
        }
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM queue_entries WHERE cueReference IS NOT NULL"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM local_track_versions WHERE cueReference IS NOT NULL OR codecParameters IS NOT NULL"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM playlist_members WHERE cueReference IS NOT NULL"))
        assertEquals(0L, scalarLong("SELECT COUNT(*) FROM playlist_exchange_rows WHERE cueReference IS NOT NULL"))
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
    ).setDriver(BundledSQLiteDriver()).addMigrations(
        MeloXDatabase.MIGRATION_1_2, MeloXDatabase.MIGRATION_2_3, lastMigration,
        MeloXDatabase.MIGRATION_4_5, MeloXDatabase.MIGRATION_5_6, MeloXDatabase.MIGRATION_6_7, MeloXDatabase.MIGRATION_7_8,
    ).build()

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

    private fun select(table: String, columns: List<String>? = null): String = "SELECT " +
        (columns?.joinToString(", ") { "`$it`" } ?: "*") + " FROM `$table` ORDER BY " + when (table) {
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
    private data class V3Observation(val rows: Map<String, FrozenCursorRows>, val catalog: FrozenCursorRows, val identity: String,
        val changedTableStructure: Map<String, FrozenCursorRows>, val fullCatalog: FrozenCursorRows)
    private data class Column(val name: String, val type: String, val notNull: Long, val primaryKey: Long = 0L)

    companion object {
        private const val IDENTITY_QUERY = "SELECT identity_hash FROM room_master_table WHERE id = 42"
        private const val TABLES_QUERY = "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
            "AND name NOT IN ('room_master_table', 'android_metadata') ORDER BY name"
        private const val FULL_V3_CATALOG_QUERY = "SELECT type, name, tbl_name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' " +
            "AND name NOT IN ('room_master_table', 'android_metadata') AND tbl_name NOT IN (" +
            "'lyrics_track_preferences', 'transferred_import_operations', 'display_groups', 'display_names', " +
            "'display_album_links', 'display_artist_links', 'transfer_room_operations', 'transfer_display_previews', " +
            "'transferred_theme_candidates', 'local_cue_documents', 'local_cue_group_versions', " +
            "'local_cue_native_segments', 'local_cue_aliases', 'local_cue_alias_versions', 'local_cue_alias_segments') ORDER BY type, name"
        // 可空新增列会改变四张旧表的声明；旧列与原单元格另行核验。
        private const val CATALOG_QUERY = "SELECT type, name, tbl_name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' " +
            "AND name NOT IN ('room_master_table', 'android_metadata') AND tbl_name NOT IN (" +
            "'lyrics_track_preferences', 'transferred_import_operations', " +
            "'display_groups', 'display_names', 'display_album_links', 'display_artist_links', " +
            "'transfer_room_operations', 'transfer_display_previews', 'transferred_theme_candidates', " +
            "'local_cue_documents', 'local_cue_group_versions', 'local_cue_native_segments', 'local_cue_aliases', " +
            "'local_cue_alias_versions', 'local_cue_alias_segments') " +
            "AND NOT (type = 'table' AND name IN ('queue_entries', 'local_track_versions', 'playlist_members', 'playlist_exchange_rows')) " +
            "AND name NOT IN ('index_queue_entries_generationId_providerId_trackId_queueEntryId', " +
            "'index_playlist_members_providerId_trackId_generationId_memberId') ORDER BY type, name"
        private val V3_TABLES = setOf(
            "queue_generations", "queue_entries", "queue_head", "library_meta", "local_sources", "local_grants", "local_source_versions",
            "local_locations", "local_location_versions", "local_tracks", "local_track_locations", "local_track_versions", "local_artist_values",
            "local_nodes", "local_node_versions", "local_access_aliases", "local_alias_versions", "scan_generations", "scan_seen", "scan_frontier",
            "scan_absence_candidates", "scan_rules", "library_views", "import_operations", "listening_meta", "listening_rounds",
            "listening_round_segments", "listening_history", "listening_track_totals", "listening_day_totals", "playlist_meta", "playlist_heads",
            "playlist_generations", "playlist_members", "playlist_views", "playlist_receipts", "playlist_exchange_previews", "playlist_exchange_rows",
            "playlist_export_receipts",
        )
        private val VERSION_SIX_NEW_TABLES = setOf(
            "display_groups", "display_names", "display_album_links", "display_artist_links",
            "transfer_room_operations", "transfer_display_previews", "transferred_theme_candidates",
        )
        private val VERSION_EIGHT_CUE_TABLES = setOf("local_cue_documents", "local_cue_group_versions", "local_cue_native_segments",
            "local_cue_aliases", "local_cue_alias_versions", "local_cue_alias_segments")
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
