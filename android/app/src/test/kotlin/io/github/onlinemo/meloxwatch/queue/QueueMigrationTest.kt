package io.github.onlinemo.meloxwatch.queue

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
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
        val database = Room.databaseBuilder(RuntimeEnvironment.getApplication() as Context,
            MeloXDatabase::class.java, file.absolutePath).addMigrations(MeloXDatabase.MIGRATION_2_3).build()
        try {
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
                independent.rawQuery("PRAGMA integrity_check", null).use {
                    assertTrue(it.moveToFirst()); assertEquals("ok", it.getString(0))
                }
                independent.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
                independent.rawQuery("SELECT orderPolicy, operationKind FROM queue_generations WHERE generationId = 'old-generation'", null).use {
                    assertTrue(it.moveToFirst()); assertEquals("PRESERVE_SUPPLIED", it.getString(0)); assertEquals("REPLACE", it.getString(1))
                }
            }
        } finally { database.close() }
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
