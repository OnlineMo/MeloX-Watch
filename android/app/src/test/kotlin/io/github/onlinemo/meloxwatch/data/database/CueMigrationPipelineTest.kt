package io.github.onlinemo.meloxwatch.data.database

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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** 已通过云端准入的 V6 作为独立前像；精确 V7 云端准入仍是单独门禁。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CueMigrationPipelineTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private val schema by lazy { FrozenRoomSchemaTestFixture.load("/database/melox-schema-6.json", 6,
        "9b57e00ca554f04be6be24eaeac10b2400bba13f23ca9e9104a0e3f84fdb6cc2", "6db4450fd1a9ec7e98d43051e553d9a6") }

    @Test(timeout = 30_000L)
    fun realV6CellsSurviveBothNewMigrationsAndAllFourReferencesStayNullOnReopen() = runBlocking {
        val file = File(temporary.root, "cue-v6-to-v9.db")
        val before = seed(file)
        val database = open(file)
        try { assertCurrent(database, before) } finally { database.close() }
        val reopened = open(file)
        try { assertCurrent(reopened, before) } finally { reopened.close() }
    }

    @Test(timeout = 30_000L)
    fun failureAfterTheLastCueTableRollsBackNewColumnsIndexesTablesVersionAndEveryOldCellForBothDrivers() = runBlocking {
        for (support in listOf(false, true)) {
            val file = File(temporary.root, "cue-rollback-$support.db")
            val before = seed(file)
            if (support) {
                val helper = supportOpen(file)
                try { rejected { helper.writableDatabase } } finally { helper.close() }
            } else {
                val failed = object : Migration(7, 8) {
                    override fun migrate(connection: SQLiteConnection) {
                        MeloXDatabase.MIGRATION_7_8.migrate(connection)
                        throw CueDdlFailure()
                    }
                }
                val database = open(file, failed)
                try { rejected { database.withTestWriterSql { scalarLong("PRAGMA user_version") } } }
                finally { database.close() }
            }
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { old ->
                assertEquals(6, old.version)
                schema.assertStructure { old.rawQuery(it, null) }
                schema.assertRowsPreserved(before) { old.rawQuery(it, null) }
                old.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE name LIKE 'local_cue_%'", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0L, it.getLong(0))
                }
            }
            val retried = open(file)
            try { assertCurrent(retried, before) } finally { retried.close() }
        }
    }

    private suspend fun assertCurrent(database: MeloXDatabase, before: Map<String, FrozenTableRows>) = database.withTestWriterSql {
        assertEquals(9L, scalarLong("PRAGMA user_version"))
        schema.assertRowsPreserved(before, this)
        for (table in listOf("local_track_versions", "queue_entries", "playlist_members", "playlist_exchange_rows")) {
            assertEquals(0L, scalarLong("SELECT COUNT(*) FROM `$table` WHERE cueReference IS NOT NULL"))
            query("PRAGMA table_info($table)") { rows ->
                var found = false
                while (rows.step()) if (rows.getText(1) == "cueReference") {
                    assertFalse(found)
                    found = true
                    assertEquals("TEXT", rows.getText(2))
                    assertEquals(0L, rows.getLong(3))
                    assertEquals("NULL", rows.getText(4))
                }
                assertTrue(found)
            }
        }
        for (table in CUE_TABLES) assertEquals(0L, scalarLong("SELECT COUNT(*) FROM `$table`"))
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_queue_entries_generationId_providerId_trackId_queueEntryId'"))
        assertEquals(1L, scalarLong("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'index_playlist_members_providerId_trackId_generationId_memberId'"))
        query("PRAGMA foreign_key_check") { assertFalse(it.step()) }
        assertEquals("ok", scalarText("PRAGMA integrity_check"))
    }

    private fun seed(file: File): Map<String, FrozenTableRows> = SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
        old.setForeignKeyConstraintsEnabled(true)
        schema.create(old)
        old.version = 6
        old.execSQL("INSERT INTO queue_generations VALUES ('before-queue', 'before-operation', NULL, 0, 'before-view', 1, 1, 0, " +
            "'LIST', NULL, 0, 0, 0, 2, 1, 1, 'REPLACE', '', NULL, 'PRESERVE_SUPPLIED')")
        old.execSQL("INSERT INTO queue_entries VALUES ('before-queue', 'before-entry', 'local', 'before-track', 0, 0, " +
            "'Before audio', 2000, NULL, NULL, NULL, NULL, NULL, NULL, NULL)")
        old.execSQL("INSERT INTO queue_head VALUES (1, 'before-queue', 1, 4, 'before-entry', 700, 2)")
        old.execSQL("INSERT INTO playlist_generations VALUES ('before-list', 'list-id', 'before-list', 'list-operation', 'before', " +
            "'PUBLISHED', 1, 'Before list', 'before list', 'USER', 1000, 1000, 1, 1, NULL, 'historical-process', NULL, NULL, NULL)")
        old.execSQL("INSERT INTO playlist_heads VALUES ('list-id', 'before-list', 1, 1, 0)")
        old.execSQL("INSERT INTO playlist_members VALUES ('before-list', 'before-member', 0, 'local-before-track', 'local', " +
            "'before-track', NULL, NULL, 'Before title', 'Before artist', 'Before album', 'Before.wav', 'Before note', 2000)")
        old.execSQL("INSERT INTO playlist_exchange_previews VALUES ('before-preview', 'historical-process', 'READY', 1, 1, 601000, 1, 'Before list', 1, NULL)")
        old.execSQL("INSERT INTO playlist_exchange_rows VALUES ('before-preview', 0, 'before-member', 'before-track', NULL, NULL, " +
            "'Before title', 'Before artist', 'Before album', 'Before.wav', 'Before note', 2000, 'NONE', 'READABLE')")
        schema.assertStructure { old.rawQuery(it, null) }
        schema.snapshotRows { old.rawQuery(it, null) }
    }
    private fun open(file: File, last: Migration = MeloXDatabase.MIGRATION_7_8): MeloXDatabase = Room.databaseBuilder(
        context, MeloXDatabase::class.java, file.absolutePath,
    ).setDriver(BundledSQLiteDriver()).addMigrations(MeloXDatabase.MIGRATION_6_7, last, MeloXDatabase.MIGRATION_8_9).build()
    private fun supportOpen(file: File): SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context).name(file.absolutePath).callback(object : SupportSQLiteOpenHelper.Callback(8) {
            override fun onConfigure(db: SupportSQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
            override fun onCreate(db: SupportSQLiteDatabase) { throw AssertionError("Qualified historical V6 required") }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                assertEquals(6, oldVersion)
                assertEquals(8, newVersion)
                MeloXDatabase.MIGRATION_6_7.migrate(db)
                MeloXDatabase.MIGRATION_7_8.migrate(db)
                throw CueDdlFailure()
            }
        }).build(),
    )
    private suspend fun rejected(action: suspend () -> Any?) {
        try {
            action()
            throw AssertionError("A real migration failure is required")
        }
        catch (error: Exception) { assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it is CueDdlFailure }) }
    }
    private class CueDdlFailure : IllegalStateException("After all native and alias DDL")
    private companion object {
        val CUE_TABLES = listOf("local_cue_documents", "local_cue_group_versions", "local_cue_native_segments",
            "local_cue_aliases", "local_cue_alias_versions", "local_cue_alias_segments")
    }
}
