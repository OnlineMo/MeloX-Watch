package io.github.onlinemo.meloxwatch.data.database

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import io.github.onlinemo.meloxwatch.data.library.LibraryLocationEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryMetaEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryTrackEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryTrackLocationEntity
import io.github.onlinemo.meloxwatch.data.listening.ListeningMetaEntity
import io.github.onlinemo.meloxwatch.data.queue.QueueEntryEntity
import io.github.onlinemo.meloxwatch.data.queue.QueueGenerationEntity
import io.github.onlinemo.meloxwatch.data.queue.QueueHeadEntity
import io.github.onlinemo.meloxwatch.data.queue.RoomQueueStore
import io.github.onlinemo.meloxwatch.model.FrozenQueueView
import io.github.onlinemo.meloxwatch.model.LocalAccess
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import io.github.onlinemo.meloxwatch.model.QueueCheckpoint
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueMode
import io.github.onlinemo.meloxwatch.model.QueueRepeatMode
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Historical 1→2 structure and the application's complete 1→2→3 Room open are separate checks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RoomMigrationOneToTwoTest {
    @Test(timeout = 30000L)
    fun productionOneToTwoMigrationExactlyMatchesTheFrozenV2SchemaAndPreservesEveryOldCell() {
        val context = RuntimeEnvironment.getApplication() as Context
        val file = context.getDatabasePath(HISTORICAL_FILE_NAME)
        assertFalse("The historical file must be created by this fixture", file.exists())
        file.parentFile?.mkdirs()
        try {
            seedVersionOne(file)
            val before = assertVersionOneFile(file)
            val helper = openHistoricalVersionTwo(context)
            try {
                assertHistoricalVersionTwo(helper.writableDatabase, file, before)
            } finally {
                helper.close()
            }
            val reopened = openHistoricalVersionTwo(context)
            try {
                assertHistoricalVersionTwo(reopened.writableDatabase, file, before)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(HISTORICAL_FILE_NAME)
        }
    }

    @Test(timeout = 30000L)
    fun applicationOpenMigratesASeededV1FileThroughV2ToV3AndReopenPreservesQueueReferencesAndNewRows() = runBlocking(Dispatchers.IO) {
        val context = RuntimeEnvironment.getApplication() as Context
        // Robolectric owns this application's database directory; the production filename and
        // applicationContext path are used so the test cannot bypass MeloXDatabase.open.
        val file = context.getDatabasePath(MeloXDatabase.FILE_NAME)
        assertFalse("The old file must be created by this fixture", file.exists())
        file.parentFile?.mkdirs()
        try {
            seedVersionOne(file)
            val before = assertVersionOneFile(file)

            val migrated = MeloXDatabase.open(context)
            val identity = try {
                val persistedIdentity = assertVersionThreeSchema(migrated, file)
                assertLegacyQueueRows(migrated, before)
                assertQueueRestoration(migrated)
                assertEquals(initialLibraryMeta, migrated.libraryDao().meta())
                assertEquals(initialListeningMeta, migrated.listeningDao().meta())
                // v1 carried references inside queue_entries, without enrolling a local library.
                assertNull(migrated.libraryDao().track(repeatedTrack.trackId))
                assertNull(migrated.libraryDao().location(persistedLocation.locationId))
                assertNull(migrated.libraryDao().location(temporaryLocation.locationId))
                migrated.withTransaction {
                    migrated.libraryDao().insertTrack(newTrack)
                    migrated.libraryDao().putLocation(newLocation)
                    migrated.libraryDao().insertRelation(newRelation)
                    assertEquals(1, migrated.libraryDao().updateMeta(updatedLibraryMeta))
                }
                assertNewLibraryRows(migrated)
                assertLegacyQueueRows(migrated, before)
                assertVersionThreeSchema(migrated, file, persistedIdentity)
                persistedIdentity
            } finally {
                migrated.close()
            }

            val reopened = MeloXDatabase.open(context)
            try {
                assertVersionThreeSchema(reopened, file, identity)
                assertLegacyQueueRows(reopened, before)
                assertQueueRestoration(reopened)
                assertNewLibraryRows(reopened)
                assertEquals(initialListeningMeta, reopened.listeningDao().meta())
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(MeloXDatabase.FILE_NAME)
        }
    }

    private fun seedVersionOne(file: File) {
        assertEquals(VERSION_ONE_TABLES, schemaOne.tableNames)

        SQLiteDatabase.openOrCreateDatabase(file, null).use { legacy ->
            legacy.setForeignKeyConstraintsEnabled(true)
            legacy.beginTransaction()
            try {
                schemaOne.create(legacy)
                legacy.execSQL("""
                    INSERT INTO queue_generations (
                        generationId, operationId, expectedGenerationId, expectedQueueRevision,
                        viewId, viewRevision, totalCount, shuffle, repeatMode, cycleLimit,
                        startOriginalOrder, initialPositionMs, allowEmpty, phase, writtenCount, committedQueueRevision
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(), arrayOf<Any?>(
                    legacyGeneration.generationId, legacyGeneration.operationId, legacyGeneration.expectedGenerationId,
                    legacyGeneration.expectedQueueRevision, legacyGeneration.viewId, legacyGeneration.viewRevision,
                    legacyGeneration.totalCount, 1, legacyGeneration.repeatMode, legacyGeneration.cycleLimit,
                    legacyGeneration.startOriginalOrder, legacyGeneration.initialPositionMs, 0,
                    legacyGeneration.phase, legacyGeneration.writtenCount, legacyGeneration.committedQueueRevision,
                ))
                for (row in legacyRows) legacy.execSQL("""
                    INSERT INTO queue_entries (
                        generationId, queueEntryId, providerId, trackId, originalOrder, playbackOrder,
                        displayName, durationMs, accountId, locationId, locationUri, grantUri, grantFlags, access, contentRevision
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(), arrayOf<Any?>(
                    row.generationId, row.queueEntryId, row.providerId, row.trackId, row.originalOrder, row.playbackOrder,
                    row.displayName, row.durationMs, row.accountId, row.locationId, row.locationUri,
                    row.grantUri, row.grantFlags, row.access, row.contentRevision,
                ))
                legacy.execSQL("""
                    INSERT INTO queue_head (
                        id, activeGenerationId, queueRevision, checkpointRevision, currentEntryId, positionMs, completedCycles
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(), arrayOf<Any?>(
                    legacyHead.id, legacyHead.activeGenerationId, legacyHead.queueRevision, legacyHead.checkpointRevision,
                    legacyHead.currentEntryId, legacyHead.positionMs, legacyHead.completedCycles,
                ))
                legacy.setTransactionSuccessful()
            } finally {
                legacy.endTransaction()
            }
            legacy.version = 1
        }
    }

    private fun assertVersionOneFile(file: File): Map<String, FrozenTableRows> {
        assertTrue(file.isFile && file.length() > 0L)
        return SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { legacy ->
            assertEquals(1, legacy.version)
            assertEquals(VERSION_ONE_TABLES, applicationTables(legacy.rawQuery(TABLES_QUERY, null)))
            assertIdentity(legacy.rawQuery(IDENTITY_QUERY, null), VERSION_ONE_IDENTITY)
            schemaOne.assertStructure { legacy.rawQuery(it, null) }
            assertNoForeignKeyViolations(legacy.rawQuery("PRAGMA foreign_key_check", null))
            assertIntegrity(legacy.rawQuery("PRAGMA integrity_check", null))
            schemaOne.snapshotRows { legacy.rawQuery(it, null) }
        }
    }

    private fun openHistoricalVersionTwo(context: Context): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(2) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                db.setForeignKeyConstraintsEnabled(true)
            }

            override fun onCreate(db: SupportSQLiteDatabase) {
                throw AssertionError("The helper must migrate the independently seeded v1 file")
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                assertEquals(1, oldVersion)
                assertEquals(2, newVersion)
                MeloXDatabase.MIGRATION_1_2.migrate(db)
                schemaTwo.assertStructure { db.query(it) }
            }
        }
        return FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(HISTORICAL_FILE_NAME).callback(callback).build())
    }

    private fun assertHistoricalVersionTwo(sqlite: SupportSQLiteDatabase, file: File, before: Map<String, FrozenTableRows>) {
        assertEquals(file.canonicalPath, File(requireNotNull(sqlite.path)).canonicalPath)
        assertEquals(2, sqlite.version)
        assertEquals(VERSION_TWO_TABLES, schemaTwo.tableNames)
        assertEquals(VERSION_TWO_TABLES, applicationTables(sqlite.query(TABLES_QUERY)))
        schemaTwo.assertStructure { sqlite.query(it) }
        schemaOne.assertRowsPreserved(before) { sqlite.query(it) }
        // This is direct production migration plus a frozen-schema structure check, not a
        // compiled Room v2 open. Migration does not update Room's old master identity; the
        // independent v2 identity source is checked in the pinned JSON, never written here.
        assertIdentity(sqlite.query(IDENTITY_QUERY), VERSION_ONE_IDENTITY)
        assertSingleIntegerRow(sqlite.query("SELECT * FROM library_meta"), longArrayOf(1, 0, 0, 1, 1, 0, 0))
        assertSingleIntegerRow(sqlite.query("SELECT * FROM listening_meta"), longArrayOf(1, 0, 0, 1, 1, 1, 0, 0))
        assertNoForeignKeyViolations(sqlite.query("PRAGMA foreign_key_check"))
        assertIntegrity(sqlite.query("PRAGMA integrity_check"))
    }

    private fun assertVersionThreeSchema(database: MeloXDatabase, file: File, expectedIdentity: String? = null): String {
        // Forcing the actual application open runs both registered migrations and Room's
        // generated validation of the current 39 entities. The v3 identity is checked for
        // persisted stability, not presented as a separately pinned historical v3 export.
        val sqlite = database.openHelper.writableDatabase
        assertEquals(file.canonicalPath, File(requireNotNull(sqlite.path)).canonicalPath)
        assertEquals(3, sqlite.version)
        assertEquals(VERSION_THREE_TABLES, applicationTables(sqlite.query(TABLES_QUERY)))
        val identity = sqlite.query(IDENTITY_QUERY).use { rows ->
            assertTrue(rows.moveToFirst())
            val value = rows.getString(0)
            assertTrue(Regex("[0-9a-f]{32}").matches(value))
            assertFalse(value == VERSION_ONE_IDENTITY || value == VERSION_TWO_IDENTITY)
            if (expectedIdentity != null) assertEquals(expectedIdentity, value)
            assertFalse(rows.moveToNext())
            value
        }
        sqlite.query("""
            SELECT operationKind, operationFingerprint, expectedCurrentEntryId, orderPolicy
            FROM queue_generations WHERE generationId = '$GENERATION_ID'
        """.trimIndent()).use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals("REPLACE", rows.getString(0))
            assertEquals("", rows.getString(1))
            assertTrue(rows.isNull(2))
            assertEquals("PRESERVE_SUPPLIED", rows.getString(3))
            assertFalse(rows.moveToNext())
        }
        for (table in VERSION_THREE_TABLES - VERSION_TWO_TABLES) {
            assertSingleIntegerRow(sqlite.query("SELECT COUNT(*) FROM `$table`"), longArrayOf(0))
        }
        assertNoForeignKeyViolations(sqlite.query("PRAGMA foreign_key_check"))
        assertIntegrity(sqlite.query("PRAGMA integrity_check"))
        return identity
    }

    private suspend fun assertLegacyQueueRows(database: MeloXDatabase, before: Map<String, FrozenTableRows>) {
        schemaOne.assertRowsPreserved(before) { database.openHelper.writableDatabase.query(it) }
        val dao = database.queueDao()
        assertEquals(legacyGeneration, dao.generation(GENERATION_ID))
        assertEquals(legacyGeneration, dao.operation(legacyGeneration.operationId))
        assertEquals(legacyHead, dao.head())
        assertEquals(legacyRows.sortedBy { it.playbackOrder }, dao.page(GENERATION_ID, 0L, 10))
        for (row in legacyRows) {
            assertEquals(row, dao.entry(GENERATION_ID, row.queueEntryId))
            assertEquals(row, dao.entryAtOriginalOrder(GENERATION_ID, row.originalOrder))
        }
    }

    private suspend fun assertQueueRestoration(database: MeloXDatabase) {
        val store = RoomQueueStore(database)
        assertEquals(legacySnapshot, store.initialize())
        val ordered = legacyEntries.sortedBy { it.playbackOrder }
        val page = store.page(GENERATION_ID, 0L, 10)
        assertEquals(ordered, page)
        assertEquals(4, page.map { it.queueEntryId }.distinct().size)
        assertEquals(3, page.count { it.trackKey == repeatedTrack })
        val window = store.window(GENERATION_ID, "legacy-entry-d")
        assertEquals(ordered.take(3), window.entries)
        assertEquals(1, window.currentIndex)
        assertEquals("legacy-entry-d", window.current.queueEntryId)
        assertTrue(window.hasPrevious && window.hasNext)
        assertEquals(legacySnapshot, store.snapshot())
    }

    private suspend fun assertNewLibraryRows(database: MeloXDatabase) {
        val dao = database.libraryDao()
        assertEquals(updatedLibraryMeta, dao.meta())
        assertEquals(newTrack, dao.track(newTrack.trackId))
        assertEquals(newLocation, dao.location(newLocation.locationId))
        assertEquals(newLocation, dao.locationByIdentity(newLocation.identityKey))
        assertEquals(listOf(newRelation), dao.locationRelations(newLocation.locationId, null, 10))
    }

    private fun applicationTables(cursor: Cursor): Set<String> = cursor.use { rows ->
        val names = mutableSetOf<String>()
        while (rows.moveToNext()) {
            val name = rows.getString(0)
            if (!name.startsWith("sqlite_") && name != "room_master_table" && name != "android_metadata") names.add(name)
        }
        names
    }

    private fun assertIdentity(cursor: Cursor, expected: String) = cursor.use { rows ->
        assertTrue(rows.moveToFirst())
        assertEquals(expected, rows.getString(0))
        assertFalse(rows.moveToNext())
    }

    private fun assertNoForeignKeyViolations(cursor: Cursor) = cursor.use { rows ->
        assertFalse("All stored parent references must resolve", rows.moveToFirst())
    }

    private fun assertIntegrity(cursor: Cursor) = cursor.use { rows ->
        assertTrue(rows.moveToFirst())
        assertEquals("ok", rows.getString(0))
        assertFalse(rows.moveToNext())
    }

    private fun assertSingleIntegerRow(cursor: Cursor, expected: LongArray) = cursor.use { rows ->
        assertTrue(rows.moveToFirst())
        assertEquals(expected.size, rows.columnCount)
        for (index in expected.indices) {
            assertEquals(Cursor.FIELD_TYPE_INTEGER, rows.getType(index))
            assertEquals(expected[index], rows.getLong(index))
        }
        assertFalse(rows.moveToNext())
    }

    private val schemaOne by lazy {
        FrozenRoomSchemaTestFixture.load("/database/melox-schema-1.json", 1, SCHEMA_ONE_SHA256, VERSION_ONE_IDENTITY)
    }
    private val schemaTwo by lazy {
        FrozenRoomSchemaTestFixture.load("/database/melox-schema-2.json", 2, SCHEMA_TWO_SHA256, VERSION_TWO_IDENTITY)
    }

    private val repeatedTrack = TrackKey("local", "legacy-local-track")
    private val persistedLocation = LocalLocationReference(
        locationId = "legacy-location-main", uri = "content://migration-fixture/document/music%2Fa",
        grantUri = "content://migration-fixture/tree/music", grantFlags = 3,
        access = LocalAccess.PERSISTED, contentRevision = 12L,
    )
    private val temporaryLocation = LocalLocationReference(
        locationId = "legacy-location-alias", uri = "content://migration-fixture/document/alias-a",
        grantUri = "content://migration-fixture/document/alias-a", grantFlags = 1,
        access = LocalAccess.TEMPORARY, contentRevision = 17L,
    )
    private val firstEntry = QueueEntry(
        "legacy-entry-a", repeatedTrack, originalOrder = 0L, playbackOrder = 2L,
        displayName = "旧曲目 A", durationMs = 180000L, location = persistedLocation,
    )
    private val legacyEntries = listOf(
        firstEntry,
        firstEntry.copy(queueEntryId = "legacy-entry-b", originalOrder = 1L, playbackOrder = 0L),
        QueueEntry("legacy-entry-c", TrackKey("netease", "legacy-online-track"), originalOrder = 2L,
            playbackOrder = 3L, accountId = "legacy-account"),
        firstEntry.copy(queueEntryId = "legacy-entry-d", originalOrder = 3L, playbackOrder = 1L,
            displayName = "旧曲目别名", location = temporaryLocation),
    )
    // The old DDL and bound values stay independent of the production migration and codecs.
    private val legacyRows = legacyEntries.map { entry ->
        QueueEntryEntity(
            generationId = GENERATION_ID, queueEntryId = entry.queueEntryId, providerId = entry.trackKey.providerId,
            trackId = entry.trackKey.trackId, originalOrder = entry.originalOrder, playbackOrder = entry.playbackOrder,
            displayName = entry.displayName, durationMs = entry.durationMs, accountId = entry.accountId,
            locationId = entry.location?.locationId, locationUri = entry.location?.uri, grantUri = entry.location?.grantUri,
            grantFlags = entry.location?.grantFlags, access = entry.location?.access?.name,
            contentRevision = entry.location?.contentRevision,
        )
    }
    private val legacyGeneration = QueueGenerationEntity(
        generationId = GENERATION_ID, operationId = "legacy-operation", expectedGenerationId = null,
        expectedQueueRevision = 0L, viewId = "legacy-view", viewRevision = 17L, totalCount = 4L,
        shuffle = true, repeatMode = "LIST", cycleLimit = 5, startOriginalOrder = 0L, initialPositionMs = 5000L,
        allowEmpty = false, phase = 2, writtenCount = 4L, committedQueueRevision = 1L,
    )
    private val legacyHead = QueueHeadEntity(
        id = 1, activeGenerationId = GENERATION_ID, queueRevision = 1L, checkpointRevision = 7L,
        currentEntryId = "legacy-entry-d", positionMs = 54321L, completedCycles = 2,
    )
    private val legacySnapshot = QueueSnapshot(
        generationId = GENERATION_ID, queueRevision = 1L, checkpointRevision = 7L,
        view = FrozenQueueView("legacy-view", 17L, 4L), mode = QueueMode(true, QueueRepeatMode.LIST, 5),
        checkpoint = QueueCheckpoint("legacy-entry-d", 54321L, 2), currentOriginalOrder = 3L, currentPlaybackOrder = 1L,
    )
    private val initialLibraryMeta = LibraryMetaEntity(
        id = 1, revision = 0L, rulesRevision = 0L, nextAddedSequence = 1L, nextScanSequence = 1L,
        shortAudioFilter = false, statisticsEpoch = 0L,
    )
    private val initialListeningMeta = ListeningMetaEntity(
        id = 1, historyEpoch = 0L, statisticsEpoch = 0L, rulesVersion = 1, nextHistorySequence = 1L,
        historyFloorSequence = 1L, totalPlayCount = 0L, totalListeningMs = 0L,
    )
    private val newTrack = LibraryTrackEntity("new-local-track", "local", 1790000000000L, 1L)
    private val newLocation = LibraryLocationEntity(
        locationId = "new-local-location", identityKey = "migration:fixture-location", contentRevision = 3L,
        contentToken = "native:fixture:1", verifiedContentToken = null, previousVerifiedContentToken = null,
        sizeBytes = 128L, modifiedEpochMs = 1790000000000L, appOwned = false,
    )
    private val newRelation = LibraryTrackLocationEntity("new-relation", newTrack.trackId, newLocation.locationId, 1L, null)
    private val updatedLibraryMeta = initialLibraryMeta.copy(revision = 1L, nextAddedSequence = 2L)

    private companion object {
        const val GENERATION_ID = "legacy-generation"
        const val HISTORICAL_FILE_NAME = "melox-history-1-to-2.db"
        const val SCHEMA_ONE_SHA256 = "3a36d662a8ee80e70d76180d564f8d9858243631dd20e2648c2492df8ff39c79"
        const val SCHEMA_TWO_SHA256 = "bab895d032d46556e4615a44130dc523e8942e942cd34ab68832969ecc7696bf"
        const val VERSION_ONE_IDENTITY = "b7264fee4b8a13461a12aed516b5dcaf"
        const val VERSION_TWO_IDENTITY = "f785aceb921839fb08cede7dd1eba81a"
        const val TABLES_QUERY = "SELECT name FROM sqlite_master WHERE type = 'table'"
        const val IDENTITY_QUERY = "SELECT identity_hash FROM room_master_table WHERE id = 42"
        val VERSION_ONE_TABLES = setOf("queue_generations", "queue_entries", "queue_head")
        val VERSION_TWO_TABLES = VERSION_ONE_TABLES + setOf(
            "library_meta", "local_sources", "local_grants", "local_source_versions", "local_locations",
            "local_location_versions", "local_tracks", "local_track_locations", "local_track_versions",
            "local_artist_values", "local_nodes", "local_node_versions", "local_access_aliases", "local_alias_versions",
            "scan_generations", "scan_seen", "scan_frontier", "scan_absence_candidates", "scan_rules", "library_views",
            "import_operations", "listening_meta", "listening_rounds", "listening_round_segments", "listening_history",
            "listening_track_totals", "listening_day_totals",
        )
        val VERSION_THREE_TABLES = VERSION_TWO_TABLES + setOf(
            "playlist_meta", "playlist_heads", "playlist_generations", "playlist_members", "playlist_views", "playlist_receipts",
            "playlist_exchange_previews", "playlist_exchange_rows", "playlist_export_receipts",
        )
    }
}
