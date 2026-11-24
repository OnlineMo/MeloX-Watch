package io.github.onlinemo.meloxwatch.data.database

import android.content.Context
import android.database.Cursor
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.onlinemo.meloxwatch.data.imports.ImportIntent
import io.github.onlinemo.meloxwatch.data.imports.ImportPhase
import io.github.onlinemo.meloxwatch.data.library.LibraryCodec
import io.github.onlinemo.meloxwatch.data.library.LibraryImportEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryMetaEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryTrackEntity
import io.github.onlinemo.meloxwatch.data.local.LocalNodeKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import java.io.File
import java.util.UUID
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

/** Generated historical V4 and V5 independently validate the production 4-to-5 DDL overloads. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TransferredImportMigrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication() as Context

    @Test(timeout = 30_000L) fun bundledMigrationKeepsEveryV4CellAndCatalogAndReopensWithTheNewIdentity() = runBlocking {
        val file = File(temporary.root, "bundled-v4-to-v5.db")
        val before = seedV4(file)
        val database = open(file)
        val identity = try {
            assertOldData(database, before)
            database.withTestWriterSql {
                assertEquals(5L, scalarLong("PRAGMA user_version"))
                assertEquals(0L, scalarLong("SELECT COUNT(*) FROM transferred_import_operations"))
                assertEquals("ok", scalarText("PRAGMA integrity_check"))
                query("PRAGMA foreign_key_check") { assertFalse(it.step()) }
                checkNotNull(scalarText(IDENTITY)).also { assertFalse(it == before.identity) }
            }
        } finally { database.close() }
        val reopened = open(file)
        try {
            assertOldData(reopened, before)
            assertEquals(identity, reopened.withTestWriterSql { scalarText(IDENTITY) })
        } finally { reopened.close() }
    }

    @Test(timeout = 30_000L) fun failedBundledDdlRollsBackVersionIdentityAndAllV4RowsBeforeRetry() = runBlocking {
        val file = File(temporary.root, "bundled-v4-rollback.db")
        val before = seedV4(file)
        val failure = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                MeloXDatabase.MIGRATION_4_5.migrate(connection)
                throw ControlledFailure()
            }
        }
        val database = open(file, failure)
        try { expectFailure { database.withTestWriterSql { scalarLong("PRAGMA user_version") } } }
        finally { database.close() }
        assertRollback(file, before)
        val retried = open(file)
        try { assertOldData(retried, before); assertEquals(5L, retried.withTestWriterSql { scalarLong("PRAGMA user_version") }) }
        finally { retried.close() }
    }

    @Test(timeout = 30_000L) fun supportSqliteDdlMatchesGeneratedV5AndKeepsTheRealV4RowsAndMasterIdentity() = runBlocking {
        val file = File(temporary.root, "support-v4-to-v5.db")
        val before = seedV4(file)
        val helper = supportOpen(file, fail = false)
        val reference = open(File(temporary.root, "generated-v5.db"))
        try {
            val sqlite = helper.writableDatabase
            assertEquals(5, sqlite.version)
            assertEquals(before.catalog, cursorCells(sqlite.query(CATALOG)))
            for ((table, rows) in before.rows) assertEquals(table, rows, cursorCells(sqlite.query(select(table))))
            assertEquals(listOf(listOf("s:${before.identity}")), cursorCells(sqlite.query(IDENTITY)))
            // Direct DDL never invents Room's generated V5 master identity.
            for (sql in listOf("PRAGMA table_info(transferred_import_operations)",
                "PRAGMA index_list(transferred_import_operations)", "PRAGMA foreign_key_list(transferred_import_operations)")) {
                val expected = reference.withTestWriterSql { query(sql, block = ::statementCells) }
                assertEquals(expected, cursorCells(sqlite.query(sql)))
            }
        } finally { reference.close(); helper.close() }
    }

    @Test(timeout = 30_000L) fun failedSupportSqliteDdlAlsoPreservesV4AndCanBeRetriedThroughProduction() = runBlocking {
        val file = File(temporary.root, "support-v4-rollback.db")
        val before = seedV4(file)
        val helper = supportOpen(file, fail = true)
        try { expectFailure { helper.writableDatabase } } finally { helper.close() }
        assertRollback(file, before)
        val retried = open(file)
        try { assertOldData(retried, before) } finally { retried.close() }
    }

    private suspend fun seedV4(file: File): Before {
        val historical = HistoricalMeloXV4Database.open(context, file.absolutePath)
        try {
            val operation = UUID.randomUUID().toString()
            val spec = LocalSourceSpec("historical-selection", LocalSourceKind.DOCUMENT, "content://migration-fixture/document/audio",
                authority = "migration-fixture", rootObjectId = "audio")
            val candidate = ObservedCandidate(spec.sourceId, "audio", spec.uri, null, "Selected.wav", "audio/wav", LocalNodeKind.AUDIO, 1024L)
            val intent = ImportIntent(operation, spec, candidate, "fixture-content-identity")
            historical.libraryDao().insertMeta(LibraryMetaEntity(revision = 2L))
            historical.libraryDao().insertTrack(LibraryTrackEntity("historical-lyric-track", "local", 1L, 1L))
            historical.libraryDao().insertImport(LibraryImportEntity(operation, LibraryCodec.source(spec), LibraryCodec.candidate(candidate),
                intent.sourceIdentity, null, operation, ImportPhase.COPYING.name, 0L, null, null, null,
                null, null, null, null, null, null))
            historical.withTestWriterTransaction {
                execute("INSERT INTO lyrics_track_preferences(trackId, schemaVersion, revision, userDelayMs) VALUES (?, 1, 7, -300)",
                    "historical-lyric-track")
            }
            return historical.withTestWriterSql {
                assertEquals(4L, scalarLong("PRAGMA user_version"))
                val tables = query(TABLES) { rows -> buildList { while (rows.step()) add(rows.getText(0)) } }
                assertFalse("transferred_import_operations" in tables)
                Before(tables.associateWith { query(select(it), block = ::statementCells) },
                    query(CATALOG, block = ::statementCells), checkNotNull(scalarText(IDENTITY)))
            }
        } finally { historical.close() }
    }

    private suspend fun assertOldData(database: RoomDatabase, before: Before) = database.withTestWriterSql {
        assertEquals(before.catalog, query(CATALOG, block = ::statementCells))
        for ((table, rows) in before.rows) assertEquals(table, rows, query(select(table), block = ::statementCells))
    }
    private suspend fun assertRollback(file: File, before: Before) {
        val historical = HistoricalMeloXV4Database.open(context, file.absolutePath)
        try {
            assertOldData(historical, before)
            historical.withTestWriterSql {
                assertEquals(4L, scalarLong("PRAGMA user_version"))
                assertEquals(before.identity, scalarText(IDENTITY))
                assertEquals(0L, scalarLong("SELECT COUNT(*) FROM sqlite_master WHERE name = 'transferred_import_operations'"))
            }
        } finally { historical.close() }
    }
    private fun open(file: File, last: Migration = MeloXDatabase.MIGRATION_4_5): HistoricalMeloXV5Database = Room.databaseBuilder(
        context, HistoricalMeloXV5Database::class.java, file.absolutePath,
    ).setDriver(BundledSQLiteDriver()).addMigrations(MeloXDatabase.MIGRATION_1_2, MeloXDatabase.MIGRATION_2_3,
        MeloXDatabase.MIGRATION_3_4, last).build()
    private fun supportOpen(file: File, fail: Boolean): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(5) {
            override fun onConfigure(db: SupportSQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
            override fun onCreate(db: SupportSQLiteDatabase) { throw AssertionError("A generated V4 file is required") }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                assertEquals(4, oldVersion); assertEquals(5, newVersion)
                MeloXDatabase.MIGRATION_4_5.migrate(db)
                if (fail) throw ControlledFailure()
            }
        }
        return FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(file.absolutePath).callback(callback).build())
    }
    private suspend fun expectFailure(action: suspend () -> Any?) {
        try { action(); throw AssertionError("Expected controlled migration failure") }
        catch (error: Exception) { assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it is ControlledFailure }) }
    }
    private fun select(table: String) = "SELECT * FROM `$table` ORDER BY rowid"
    private fun statementCells(rows: SQLiteStatement): List<List<String?>> = buildList {
        while (rows.step()) add((0 until rows.getColumnCount()).map { column -> when (rows.cursorStorageType(column)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> "i:${rows.getLong(column)}"
            Cursor.FIELD_TYPE_FLOAT -> "f:${rows.getDouble(column)}"
            Cursor.FIELD_TYPE_STRING -> "s:${rows.getText(column)}"
            else -> "b:" + rows.getBlob(column).joinToString("") { "%02x".format(it.toInt() and 255) }
        } })
    }
    private fun cursorCells(cursor: Cursor): List<List<String?>> = cursor.use { rows -> buildList {
        while (rows.moveToNext()) add((0 until rows.columnCount).map { column -> when (rows.getType(column)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> "i:${rows.getLong(column)}"
            Cursor.FIELD_TYPE_FLOAT -> "f:${rows.getDouble(column)}"
            Cursor.FIELD_TYPE_STRING -> "s:${rows.getString(column)}"
            else -> "b:" + rows.getBlob(column).joinToString("") { "%02x".format(it.toInt() and 255) }
        } })
    } }
    private class ControlledFailure : IllegalStateException("Controlled failure after transfer DDL")
    private data class Before(val rows: Map<String, List<List<String?>>>, val catalog: List<List<String?>>, val identity: String)
    companion object {
        private const val IDENTITY = "SELECT identity_hash FROM room_master_table WHERE id = 42"
        private const val TABLES = "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
            "AND name NOT IN ('room_master_table', 'android_metadata') ORDER BY name"
        private const val CATALOG = "SELECT type, name, tbl_name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' " +
            "AND name NOT IN ('room_master_table', 'android_metadata') AND tbl_name != 'transferred_import_operations' ORDER BY type, name"
    }
}
