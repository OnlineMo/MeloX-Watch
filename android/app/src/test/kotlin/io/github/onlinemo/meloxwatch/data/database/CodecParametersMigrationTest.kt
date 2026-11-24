package io.github.onlinemo.meloxwatch.data.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** 样本使用真实云端V6结构，与当前实体和新迁移DDL分开。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CodecParametersMigrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private val schema by lazy { FrozenRoomSchemaTestFixture.load("/database/melox-schema-6.json", 6,
        "9b57e00ca554f04be6be24eaeac10b2400bba13f23ca9e9104a0e3f84fdb6cc2", "6db4450fd1a9ec7e98d43051e553d9a6") }

    @Test(timeout = 30_000L)
    fun bundledMigrationPreservesHistoricalCellsAndLyricsThenReopensWithUnknownCodecParameters() = runBlocking {
        val file = File(temporary.root, "codec-v6-to-v8.db")
        val before = seed(file)
        val database = open(file)
        try {
            database.withTestWriterSql {
                schema.assertRowsPreserved(before.rows, this)
                assertEquals(8L, scalarLong("PRAGMA user_version"))
                assertEquals("ok", scalarText("PRAGMA integrity_check"))
                query("PRAGMA foreign_key_check") { assertFalse(it.step()) }
                assertEquals(1L, scalarLong("SELECT COUNT(*) FROM local_track_versions WHERE codecParameters IS NULL"))
                assertTrue(scalarText(IDENTITY) != before.identity)
                assertEquals(before.unchangedV8Catalog, query(UNCHANGED_V8_CATALOG) { rows ->
                    buildList { while (rows.step()) add(rows.getText(0)) }
                })
            }
            assertNull(checkNotNull(database.libraryDao().currentTrack("historical-audio")).codecParameters)
        } finally { database.close() }
        val reopened = open(file)
        try {
            reopened.withTestWriterSql { schema.assertRowsPreserved(before.rows, this) }
            assertNull(checkNotNull(reopened.libraryDao().currentTrack("historical-audio")).codecParameters)
        } finally { reopened.close() }
    }

    @Test(timeout = 30_000L)
    fun supportSqliteMigrationAddsOnlyTheNullableTextColumnAndKeepsHistoricalRowsAndMaster() {
        val file = File(temporary.root, "codec-support.db")
        val before = seed(file)
        val helper = supportOpen(file, fail = false)
        try {
            val database = helper.writableDatabase
            assertEquals(7, database.version)
            schema.assertRowsPreserved(before.rows) { database.query(it) }
            database.query("PRAGMA table_info(local_track_versions)").use { rows ->
                var found = false
                while (rows.moveToNext()) if (rows.getString(rows.getColumnIndexOrThrow("name")) == "codecParameters") {
                    assertFalse(found)
                    found = true
                    assertEquals("TEXT", rows.getString(rows.getColumnIndexOrThrow("type")))
                    assertEquals(0, rows.getInt(rows.getColumnIndexOrThrow("notnull")))
                    assertEquals("NULL", rows.getString(rows.getColumnIndexOrThrow("dflt_value")))
                    assertEquals(0, rows.getInt(rows.getColumnIndexOrThrow("pk")))
                }
                assertTrue(found)
            }
            database.query(IDENTITY).use { assertTrue(it.moveToFirst())
            assertEquals(before.identity, it.getString(0)) }
            assertEquals(before.unchangedCatalog, catalog { database.query(it) })
        } finally { helper.close() }
    }

    @Test(timeout = 30_000L)
    fun failureAfterAlterRollsBackColumnVersionMasterAndCellsBeforeRetryForBothDrivers() = runBlocking {
        for (support in listOf(false, true)) {
            val file = File(temporary.root, "codec-rollback-$support.db")
            val before = seed(file)
            if (support) {
                val helper = supportOpen(file, fail = true)
                try { expectFailure { helper.writableDatabase } }
                finally { helper.close() }
            } else {
                val last = object : Migration(6, 7) {
                    override fun migrate(connection: SQLiteConnection) {
                        MeloXDatabase.MIGRATION_6_7.migrate(connection)
                        throw ControlledFailure()
                    }
                }
                val database = open(file, last)
                try { expectFailure { database.withTestWriterSql { scalarLong("PRAGMA user_version") } } }
                finally { database.close() }
            }
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { old ->
                assertEquals(6, old.version)
                schema.assertStructure { old.rawQuery(it, null) }
                schema.assertRowsPreserved(before.rows) { old.rawQuery(it, null) }
                old.rawQuery(IDENTITY, null).use { assertTrue(it.moveToFirst())
                assertEquals(before.identity, it.getString(0)) }
            }
            val retried = open(file)
            try { retried.withTestWriterSql { schema.assertRowsPreserved(before.rows, this) } }
            finally { retried.close() }
        }
    }

    private fun seed(file: File): Before = SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
        database.setForeignKeyConstraintsEnabled(true)
        schema.create(database)
        database.version = 6
        schema.assertStructure { database.rawQuery(it, null) }
        database.execSQL("INSERT INTO local_tracks(trackId, providerId, firstAddedEpochMs, firstAddedSequence) VALUES ('historical-audio', 'local', 11, 2)")
        val row = ContentValues().apply {
            put("versionId", "historical-version")
            put("trackId", "historical-audio")
            put("providerId", "local")
            put("validFrom", 1L)
            putNull("validTo")
            put("member", 1)
            put("firstAddedEpochMs", 11L)
            put("firstAddedSequence", 2L)
            put("filename", "Before.ape")
            put("displayTitle", "Original title")
            put("title", "Original title")
            put("artist", "Original artist")
            put("artists", "[\"Original artist\"]")
            put("album", "Original album")
            put("albumArtist", "Original artist")
            put("durationMs", 61_000L)
            put("discNumber", 1)
            put("trackNumber", 2)
            put("compilation", 0)
            put("mimeType", "audio/ape")
            put("parserVersion", 1)
            putNull("metadataIssue")
            put("titleKey", "0:a")
            put("artistKey", "0:b")
            put("albumSortKey", "0:c")
            put("filenameKey", "0:f")
            put("albumKey", "original-album")
            put("searchText", "Original labels")
            put("titleSection", "A")
            put("artistSection", "B")
            put("albumSection", "C")
            put("filenameSection", "F")
            put("playCount", 7L)
            put("listeningMs", 12_345L)
            put("statisticsEpoch", 0L)
        }
        database.insertOrThrow("local_track_versions", null, row)
        database.execSQL("INSERT INTO lyrics_track_preferences(trackId, schemaVersion, revision, userDelayMs) VALUES ('historical-audio', 1, 7, -300)")
        val identity = database.rawQuery(IDENTITY, null).use { rows -> assertTrue(rows.moveToFirst())
        rows.getString(0) }
        Before(schema.snapshotRows { database.rawQuery(it, null) }, identity, catalog { database.rawQuery(it, null) },
            database.rawQuery(UNCHANGED_V8_CATALOG, null).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0)) } })
    }

    private fun open(file: File, last: Migration = MeloXDatabase.MIGRATION_6_7): MeloXDatabase = Room.databaseBuilder(
        context, MeloXDatabase::class.java, file.absolutePath,
    ).setDriver(BundledSQLiteDriver()).addMigrations(last, MeloXDatabase.MIGRATION_7_8).build()

    private fun supportOpen(file: File, fail: Boolean): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(7) {
            override fun onConfigure(db: SupportSQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
            override fun onCreate(db: SupportSQLiteDatabase) { throw AssertionError("The frozen V6 file is required") }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                assertEquals(6, oldVersion)
                assertEquals(7, newVersion)
                MeloXDatabase.MIGRATION_6_7.migrate(db)
                if (fail) throw ControlledFailure()
            }
        }
        return FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(file.absolutePath).callback(callback).build())
    }

    private fun catalog(query: (String) -> android.database.Cursor): List<String> = query(UNCHANGED_CATALOG).use { rows ->
        buildList { while (rows.moveToNext()) add(rows.getString(0)) }
    }

    private suspend fun expectFailure(action: suspend () -> Any?) {
        try { action()
        throw AssertionError("Expected failure after ALTER") }
        catch (failure: Exception) { assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it is ControlledFailure }) }
    }

    private class ControlledFailure : IllegalStateException("Controlled failure after codec column")
    private data class Before(val rows: Map<String, FrozenTableRows>, val identity: String, val unchangedCatalog: List<String>,
        val unchangedV8Catalog: List<String>)
    companion object {
        private const val IDENTITY = "SELECT identity_hash FROM room_master_table WHERE id = 42"
        private const val UNCHANGED_CATALOG = "SELECT sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' " +
            "AND name NOT IN ('room_master_table', 'android_metadata') AND type = 'table' " +
            "AND name != 'local_track_versions' ORDER BY name"
        private const val UNCHANGED_V8_CATALOG = "SELECT sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' " +
            "AND name NOT IN ('room_master_table', 'android_metadata', 'local_track_versions', 'queue_entries', 'playlist_members', 'playlist_exchange_rows', " +
            "'local_cue_documents', 'local_cue_group_versions', 'local_cue_native_segments', 'local_cue_aliases', " +
            "'local_cue_alias_versions', 'local_cue_alias_segments') AND type = 'table' ORDER BY name"
    }
}
