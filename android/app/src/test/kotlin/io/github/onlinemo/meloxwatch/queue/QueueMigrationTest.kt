package io.github.onlinemo.meloxwatch.queue

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.onlinemo.meloxwatch.data.database.HistoricalMeloXV3Database
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.database.withTestWriterSql
import io.github.onlinemo.meloxwatch.data.queue.QueueReplacementRequest
import io.github.onlinemo.meloxwatch.data.queue.RoomQueueStore
import io.github.onlinemo.meloxwatch.model.FrozenQueueView
import io.github.onlinemo.meloxwatch.model.QueueMode
import io.github.onlinemo.meloxwatch.model.QueueOrderPolicy
import io.github.onlinemo.meloxwatch.model.QueueRepeatMode
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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

/** Builds the old database from its cloud-exported schema, never from a handwritten schema fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class QueueMigrationTest {
    @get:Rule val folder = TemporaryFolder()

    @Test(timeout = 30000L)
    fun realV2MigrationRetainsDuplicateInstancesSavedRandomOrderPositionAndHistoricalReceipt() = runBlocking {
        val file = File(folder.root, "v2.db")
        createOldDatabase(file)
        observeHistoricalVersionThree(file)
        val database = open(file)
        try {
            database.withTestWriterSql {
                assertEquals(7L, scalarLong("PRAGMA user_version"))
                assertEquals(0L, scalarLong("SELECT COUNT(*) FROM lyrics_track_preferences"))
                assertEquals(48L, scalarLong("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table', 'android_metadata')"))
                assertEquals(0L, scalarLong("SELECT COUNT(*) FROM transferred_import_operations"))
                for (table in listOf(
                    "display_groups", "display_names", "display_album_links", "display_artist_links",
                    "transfer_room_operations", "transfer_display_previews", "transferred_theme_candidates",
                )) {
                    assertEquals(0L, scalarLong("SELECT COUNT(*) FROM `$table`"))
                }
            }
            val store = RoomQueueStore(database)
            val restored = store.initialize()!!
            assertEquals(5L, restored.entryCount)
            assertEquals("old-entry-2", restored.currentEntryId)
            assertEquals(4321L, restored.positionMs)
            assertEquals(1, restored.checkpoint.completedCycles)
            assertEquals(QueueMode(shuffle = true, repeat = QueueRepeatMode.SINGLE, cycleLimit = 2), restored.mode)
            assertEquals(listOf(4L, 3L, 2L, 1L, 0L), store.page(restored.generationId, 0L).map { it.originalOrder })
            val request = QueueReplacementRequest("old-operation", null, 0L, FrozenQueueView("old-view", 7L, 5L),
                restored.mode, startOriginalOrder = 2L, initialPositionMs = 100L, orderPolicy = QueueOrderPolicy.PRESERVE_SUPPLIED)
            val replay = store.replace(request,
                { _, _ -> throw AssertionError("Migrated receipts must not reread old input") },
                { throw AssertionError("Migrated receipts must not repeat preflight") },
            )
            assertTrue(replay.wasReplay)
            assertEquals(restored, replay.snapshot)
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { independent ->
                assertEquals(7, independent.version)
                independent.rawQuery("PRAGMA integrity_check", null).use {
                    assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0))
                }
                independent.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
                independent.rawQuery("SELECT orderPolicy, operationKind FROM queue_generations WHERE generationId = 'old-generation'", null).use {
                    assertTrue(it.moveToFirst()); assertEquals("PRESERVE_SUPPLIED", it.getString(0)); assertEquals("REPLACE", it.getString(1))
                }
            }
        } finally { database.close() }
        val reopened = open(file)
        try {
            val store = RoomQueueStore(reopened)
            val restored = checkNotNull(store.initialize())
            assertEquals(5L, restored.entryCount)
            assertEquals("old-entry-2", restored.currentEntryId)
            assertEquals(4321L, restored.positionMs)
            assertEquals(1, restored.checkpoint.completedCycles)
            assertEquals(listOf(4L, 3L, 2L, 1L, 0L), store.page(restored.generationId, 0L).map { it.originalOrder })
            val request = QueueReplacementRequest("old-operation", null, 0L, FrozenQueueView("old-view", 7L, 5L),
                restored.mode, startOriginalOrder = 2L, initialPositionMs = 100L, orderPolicy = QueueOrderPolicy.PRESERVE_SUPPLIED)
            val replay = store.replace(request,
                { _, _ -> throw AssertionError("Reopen must replay the original queue input") },
                { throw AssertionError("Reopen must not repeat queue preflight") })
            assertTrue(replay.wasReplay)
            assertEquals(restored, replay.snapshot)
        } finally { reopened.close() }
    }

    private fun open(file: File): MeloXDatabase = Room.databaseBuilder(
        RuntimeEnvironment.getApplication() as Context, MeloXDatabase::class.java, file.absolutePath,
    ).setDriver(BundledSQLiteDriver())
        .addMigrations(MeloXDatabase.MIGRATION_1_2, MeloXDatabase.MIGRATION_2_3,
            MeloXDatabase.MIGRATION_3_4, MeloXDatabase.MIGRATION_4_5, MeloXDatabase.MIGRATION_5_6,
            MeloXDatabase.MIGRATION_6_7).build()

    private suspend fun observeHistoricalVersionThree(file: File) {
        val context = RuntimeEnvironment.getApplication() as Context
        val historical = HistoricalMeloXV3Database.open(context, file.absolutePath)
        val identity = try {
            assertHistoricalQueueRows(historical)
            historical.withTestWriterSql {
                val value = checkNotNull(scalarText("SELECT identity_hash FROM room_master_table WHERE id = 42"))
                assertTrue(Regex("[0-9a-f]{32}").matches(value))
                value
            }
        } finally { historical.close() }
        val reopened = HistoricalMeloXV3Database.open(context, file.absolutePath)
        try {
            assertHistoricalQueueRows(reopened)
            reopened.withTestWriterSql {
                assertEquals(identity, scalarText("SELECT identity_hash FROM room_master_table WHERE id = 42"))
            }
        } finally { reopened.close() }
    }

    private suspend fun assertHistoricalQueueRows(database: HistoricalMeloXV3Database) {
        database.withTestWriterSql {
            assertEquals(3L, scalarLong("PRAGMA user_version"))
            assertEquals(39L, scalarLong("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table', 'android_metadata')"))
            query("PRAGMA foreign_key_check") { assertFalse(it.step()) }
            assertEquals("ok", scalarText("PRAGMA integrity_check"))
        }
        val dao = database.queueDao()
        val head = checkNotNull(dao.head())
        assertEquals("old-entry-2", head.currentEntryId)
        assertEquals(4321L, head.positionMs)
        assertEquals(1, head.completedCycles)
        assertEquals(3L, head.checkpointRevision)
        val page = dao.page("old-generation", 0L, 10)
        assertEquals(listOf(4L, 3L, 2L, 1L, 0L), page.map { it.originalOrder })
        assertEquals(5, page.map { it.queueEntryId }.distinct().size)
        assertEquals(setOf("same-track"), page.map { it.trackId }.toSet())
        val generation = checkNotNull(dao.operation("old-operation"))
        assertEquals("REPLACE", generation.operationKind)
        assertEquals("PRESERVE_SUPPLIED", generation.orderPolicy)
        assertEquals("SINGLE", generation.repeatMode)
        assertEquals(2, generation.cycleLimit)
    }

    private fun createOldDatabase(file: File) {
        val relative = "schemas/io.github.onlinemo.meloxwatch.data.database.MeloXDatabase/2.json"
        val schemaFile = listOf(File(relative), File("app", relative), File("android/app", relative)).firstOrNull { it.isFile }
            ?: throw AssertionError("The exact cloud-exported v2 Room schema is required")
        val schema = JSONObject(schemaFile.readText(Charsets.UTF_8)).getJSONObject("database")
        assertEquals(2, schema.getInt("version"))
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.beginTransaction()
            try {
                val entities = schema.getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index)
                    old.execSQL(entity.getString("createSql").replace("$" + "{TABLE_NAME}", entity.getString("tableName")))
                    val indices = if (entity.has("indices")) entity.getJSONArray("indices") else null
                    if (indices != null) {
                        for (position in 0 until indices.length()) {
                            old.execSQL(indices.getJSONObject(position).getString("createSql").replace("$" + "{TABLE_NAME}", entity.getString("tableName")))
                        }
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (index in 0 until setup.length()) old.execSQL(setup.getString(index))
                old.insertOrThrow("queue_generations", null, ContentValues().apply {
                    put("generationId", "old-generation"); put("operationId", "old-operation"); putNull("expectedGenerationId")
                    put("expectedQueueRevision", 0L); put("viewId", "old-view"); put("viewRevision", 7L); put("totalCount", 5L)
                    put("shuffle", 1); put("repeatMode", "SINGLE"); put("cycleLimit", 2); put("startOriginalOrder", 2L)
                    put("initialPositionMs", 100L); put("allowEmpty", 0); put("phase", 2); put("writtenCount", 5L)
                    put("committedQueueRevision", 1L)
                })
                repeat(5) { index ->
                    old.insertOrThrow("queue_entries", null, ContentValues().apply {
                        put("generationId", "old-generation"); put("queueEntryId", "old-entry-" + index)
                        put("providerId", "local"); put("trackId", "same-track"); put("originalOrder", index.toLong())
                        put("playbackOrder", 4L - index); put("displayName", "Audio " + index); put("durationMs", 100000L)
                        putNull("accountId"); putNull("locationId"); putNull("locationUri"); putNull("grantUri")
                        putNull("grantFlags"); putNull("access"); putNull("contentRevision")
                    })
                }
                old.insertOrThrow("queue_head", null, ContentValues().apply {
                    put("id", 1); put("activeGenerationId", "old-generation"); put("queueRevision", 1L)
                    put("checkpointRevision", 3L); put("currentEntryId", "old-entry-2"); put("positionMs", 4321L); put("completedCycles", 1)
                })
                old.version = 2
                old.setTransactionSuccessful()
            } finally { old.endTransaction() }
        }
    }
}
