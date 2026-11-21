package io.github.onlinemo.meloxwatch.data.playlists

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import androidx.room.Room
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.onlinemo.meloxwatch.data.database.HistoricalMeloXV3Database
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.database.TestDatabaseSql
import io.github.onlinemo.meloxwatch.data.database.cursorStorageType
import io.github.onlinemo.meloxwatch.data.database.hasCurrentWriterTransaction
import io.github.onlinemo.meloxwatch.data.database.withPreservedTransaction
import io.github.onlinemo.meloxwatch.data.database.withTestWriterTransaction
import io.github.onlinemo.meloxwatch.data.library.LibraryCodec
import io.github.onlinemo.meloxwatch.data.library.LibraryMetaEntity
import io.github.onlinemo.meloxwatch.data.library.LibrarySortKeys
import io.github.onlinemo.meloxwatch.data.library.RoomPlaylistFences
import io.github.onlinemo.meloxwatch.data.listening.ListeningMetaEntity
import io.github.onlinemo.meloxwatch.data.local.LocalGrantObservation
import io.github.onlinemo.meloxwatch.data.local.LocalNodeKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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

/** 旧DDL只取历史云导出的1/2 schema；先独立观察历史v3，再验证生产v5仓储。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PlaylistMigrationTest {
    @get:Rule val folder = TemporaryFolder()

    @Test(timeout = 30_000L)
    fun realV1ThroughV2ToV3PreservesItsQueueAndAddsUsablePlaylistTablesWithoutEnrollingFilesOrHistory() = runBlocking {
        val file = File(folder.root, "playlist-from-v1.db")
        val before = createOldDatabase(file, version = 1)
        assertFalse(before.any { it.name == "library_meta" || it.name == "listening_meta" })
        observeHistoricalVersionThree(file, before)
        val database = open(file)
        val accepted = try {
            assertProductionPreserved(database, before)
            database.withTestWriterTransaction {
                assertEquals(LibraryMetaEntity(), database.libraryDao().meta())
                assertEquals(ListeningMetaEntity(), database.listeningDao().meta())
                for (table in listOf("local_sources", "local_tracks", "listening_rounds", "listening_history")) {
                    assertEquals(0L, count(this, table))
                }
            }
            val result = exerciseAllPlaylistTables(database, "from-v1")
            assertProductionPreserved(database, before)
            result
        } finally { database.close() }
        assertReopened(file, before, accepted, "from-v1")
    }

    @Test(timeout = 30_000L)
    fun realPopulatedV2ToV3PreservesEveryOldColumnAndHistoryWhileAddingRootAndNfcProjectionsAndDurablePlaylists() = runBlocking {
        val file = File(folder.root, "playlist-from-v2.db")
        val before = createOldDatabase(file, version = 2)
        assertTrue(before.single { it.name == "local_track_versions" }.rows.isNotEmpty())
        assertTrue(before.single { it.name == "listening_history" }.rows.isNotEmpty())
        observeHistoricalVersionThree(file, before)
        val database = open(file)
        val accepted = try {
            assertProductionPreserved(database, before)
            database.withTestWriterTransaction {
                query("SELECT rootObjectId FROM local_source_versions WHERE sourceId = ? AND validTo IS NULL", SOURCE_ID) {
                    assertTrue(it.step())
                    assertEquals(ROOT_OBJECT, it.getText(0))
                    assertFalse(it.step())
                }
                val audio = checkNotNull(database.libraryDao().currentNode(AUDIO_NODE))
                assertEquals(ORIGINAL_NAME, audio.name)
                assertEquals("Café.mp3", audio.pathName)
                val track = checkNotNull(database.libraryDao().currentTrack(TRACK_ID))
                assertEquals("Kept actual title", track.displayTitle)
                assertEquals(9L, track.playCount)
                assertEquals(87_654L, track.listeningMs)
                val history = checkNotNull(database.listeningDao().history(2L, 12L, 2L))
                assertEquals(TRACK_ID, history.trackId)
                assertEquals(15_000L, history.listeningMs)
                assertEquals("Asia/Shanghai", history.firstProgressZoneId)
            }
            val result = exerciseAllPlaylistTables(database, "from-v2")
            assertProductionPreserved(database, before)
            result
        } finally { database.close() }
        assertReopened(file, before, accepted, "from-v2")
    }

    private suspend fun observeHistoricalVersionThree(file: File, before: List<TableRows>) {
        val context = RuntimeEnvironment.getApplication() as Context
        val historical = HistoricalMeloXV3Database.open(context, file.absolutePath)
        val identity = try {
            assertPreserved(historical, before)
            assertInitialHistoricalPlaylistTables(historical)
            historical.withTestWriterTransaction {
                assertEquals(39L, scalarLong("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table', 'android_metadata')"))
                val value = checkNotNull(scalarText("SELECT identity_hash FROM room_master_table WHERE id = 42"))
                assertTrue(Regex("[0-9a-f]{32}").matches(value))
                value
            }
        } finally { historical.close() }
        val reopened = HistoricalMeloXV3Database.open(context, file.absolutePath)
        try {
            assertPreserved(reopened, before)
            assertInitialHistoricalPlaylistTables(reopened)
            reopened.withTestWriterTransaction {
                assertEquals(identity, scalarText("SELECT identity_hash FROM room_master_table WHERE id = 42"))
            }
        } finally { reopened.close() }
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            assertEquals(3, sqlite.version)
            for (table in before) {
                sqlite.rawQuery(select(table), null).use { assertEquals(table.name, table.rows, cells(it)) }
            }
            sqlite.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
            sqlite.rawQuery("PRAGMA integrity_check", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("ok", it.getString(0))
            }
        }
    }

    private suspend fun assertInitialHistoricalPlaylistTables(database: HistoricalMeloXV3Database) {
        database.withTestWriterTransaction {
            val sqlite = this
            assertEquals(3, sqlite.scalarLong("PRAGMA user_version").toInt())
            val names = sqlite.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'playlist_%' ORDER BY name") { statement ->
                buildList { while (statement.step()) add(statement.getText(0)) }
            }
            assertEquals(PLAYLIST_TABLES.sorted(), names)
            for (table in PLAYLIST_TABLES) assertEquals(0L, count(sqlite, table))
        }
    }

    private suspend fun exerciseAllPlaylistTables(database: MeloXDatabase, suffix: String): PlaylistCommit {
        database.withTestWriterTransaction {
            val sqlite = this
            assertEquals(5, sqlite.scalarLong("PRAGMA user_version").toInt())
            val names = sqlite.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'playlist_%' ORDER BY name") { statement ->
                buildList { while (statement.step()) add(statement.getText(0)) }
            }
            assertEquals(PLAYLIST_TABLES.sorted(), names)
            for (table in PLAYLIST_TABLES) assertEquals(0L, count(sqlite, table))
        }
        val repository = repository(database)
        assertEquals(PlaylistOverview(0L, 0L, 0L), repository.initialize())
        assertEquals(PlaylistKind.FAVORITES, checkNotNull(repository.snapshot(PlaylistLimits.FAVORITES_ID)).kind)
        val references = listOf(
            PlaylistReference(TrackKey(TrackKey.LOCAL_PROVIDER, TRACK_ID), titleHint = "Kept actual title"),
            PlaylistReference(placeholderId = "unresolved-$suffix", issue = PlaylistReferenceIssue.REQUIRES_REASSOCIATION,
                titleHint = "Preserved unresolved member"),
        )
        val input = FrozenPlaylistInput("migration-input-$suffix", 10L, references.size.toLong())
        val request = request(suffix, input)
        val accepted = repository.apply(request, PlaylistPageSource { offset, limit ->
            assertFalse(database.hasCurrentWriterTransaction())
            val end = minOf(references.size.toLong(), offset + limit).toInt()
            PlaylistInputPage(input, offset, references.subList(offset.toInt(), end), end.toLong() == input.totalCount)
        })
        assertFalse(accepted.wasReplay)
        assertEquals(PlaylistOperationResult.CREATED, accepted.result)
        val snapshot = checkNotNull(accepted.snapshot)
        val view = repository.openView(snapshot.playlistId)
        try {
            val page = repository.page(view)
            assertEquals(references, page.members.map { it.reference })
            assertEquals(listOf(0L, 1L), page.members.map { it.ordinal })
            assertNull(page.nextOrdinal)
            database.withTestWriterTransaction {
                val sqlite = this
                assertEquals(1L, count(sqlite, "playlist_meta"))
                assertEquals(2L, count(sqlite, "playlist_heads"))
                assertEquals(2L, count(sqlite, "playlist_generations"))
                assertEquals(2L, count(sqlite, "playlist_members"))
                assertEquals(1L, count(sqlite, "playlist_views"))
                assertEquals(1L, count(sqlite, "playlist_receipts"))
            }
        } finally { repository.closeView(view.viewId) }
        val replay = repository.apply(request,
            PlaylistPageSource { _, _ -> throw AssertionError("A migrated receipt must not reread its input") })
        assertTrue(replay.wasReplay)
        assertEquals(snapshot, replay.snapshot)
        assertEquals(accepted.appliedRevision, replay.appliedRevision)
        return accepted
    }

    private suspend fun assertReopened(file: File, before: List<TableRows>, accepted: PlaylistCommit, suffix: String) {
        val database = open(file)
        try {
            assertProductionPreserved(database, before)
            val repository = repository(database)
            repository.initialize()
            val snapshot = checkNotNull(accepted.snapshot)
            assertEquals(snapshot, repository.snapshot(snapshot.playlistId))
            val receipt = database.withPreservedTransaction {
                checkNotNull(database.playlistDao().receipt("migration-create-$suffix"))
            }
            assertEquals(accepted.appliedRevision, receipt.appliedRevision)
            assertEquals(PlaylistOperationResult.CREATED.name, receipt.result)
            val replay = repository.apply(request(suffix, FrozenPlaylistInput("migration-input-$suffix", 10L, 2L)),
                PlaylistPageSource { _, _ -> throw AssertionError("DB reopen must replay the original durable receipt") })
            assertTrue(replay.wasReplay)
            assertEquals(snapshot, replay.snapshot)
            assertProductionPreserved(database, before)
        } finally { database.close() }
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            assertEquals(5, sqlite.version)
            for (table in before) {
                sqlite.rawQuery(select(table), null).use { assertEquals(table.name, table.rows, cells(it)) }
            }
            sqlite.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
            sqlite.rawQuery("PRAGMA integrity_check", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("ok", it.getString(0))
            }
        }
    }

    private fun repository(database: MeloXDatabase): RoomPlaylistRepository {
        val fences = RoomPlaylistFences(database)
        return RoomPlaylistRepository(database, database.playlistDao(), { 1_000L }, { EPOCH_MS },
            "playlist-migration-process", fences, fences)
    }

    private fun request(suffix: String, input: FrozenPlaylistInput) = PlaylistMutationRequest(
        "migration-create-$suffix", "migration-playlist-$suffix", 0L, PlaylistMutation.Create("Migrated playlist", input))

    private fun open(file: File): MeloXDatabase = Room.databaseBuilder(RuntimeEnvironment.getApplication() as Context,
        MeloXDatabase::class.java, file.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .addMigrations(MeloXDatabase.MIGRATION_1_2, MeloXDatabase.MIGRATION_2_3,
            MeloXDatabase.MIGRATION_3_4, MeloXDatabase.MIGRATION_4_5).build()

    private fun createOldDatabase(file: File, version: Int): List<TableRows> {
        val relative = "schemas/io.github.onlinemo.meloxwatch.data.database.MeloXDatabase/$version.json"
        val schemaFile = listOf(File(relative), File("app", relative), File("android/app", relative)).firstOrNull { it.isFile }
            ?: throw AssertionError("The exact historical cloud-exported Room schema $version is required")
        val schema = JSONObject(schemaFile.readText(Charsets.UTF_8)).getJSONObject("database")
        assertEquals(version, schema.getInt("version"))
        return SQLiteDatabase.openOrCreateDatabase(file, null).use { sqlite ->
            sqlite.setForeignKeyConstraintsEnabled(true)
            sqlite.beginTransaction()
            try {
                val entities = schema.getJSONArray("entities")
                val tables = buildList {
                    for (index in 0 until entities.length()) {
                        val entity = entities.getJSONObject(index)
                        val name = entity.getString("tableName")
                        assertFalse("Historical schema already contains playlist tables", name.startsWith("playlist_"))
                        sqlite.execSQL(entity.getString("createSql").replace("$" + "{TABLE_NAME}", name))
                        val indices = if (entity.has("indices")) entity.getJSONArray("indices") else null
                        if (indices != null) {
                            for (position in 0 until indices.length()) {
                                sqlite.execSQL(indices.getJSONObject(position).getString("createSql").replace("$" + "{TABLE_NAME}", name))
                            }
                        }
                        val fields = entity.getJSONArray("fields")
                        val columns = List(fields.length()) { fields.getJSONObject(it).getString("columnName") }
                        val keys = entity.getJSONObject("primaryKey").getJSONArray("columnNames")
                        add(TableRows(name, columns, List(keys.length()) { keys.getString(it) }, emptyList()))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (index in 0 until setup.length()) sqlite.execSQL(setup.getString(index))
                seedQueue(sqlite)
                if (version == 2) seedLibraryAndHistory(sqlite)
                sqlite.version = version
                val before = tables.map { table -> sqlite.rawQuery(select(table), null).use { table.copy(rows = cells(it)) } }
                sqlite.setTransactionSuccessful()
                before
            } finally { sqlite.endTransaction() }
        }
    }

    private suspend fun assertPreserved(database: HistoricalMeloXV3Database, before: List<TableRows>) = database.withTestWriterTransaction {
        // Acquiring the writer opens Room's real upgrade path and generated validation.
        val sqlite = this
        assertEquals(3, sqlite.scalarLong("PRAGMA user_version").toInt())
        for (table in before) sqlite.query(select(table)) { assertEquals(table.name, table.rows, cells(it)) }
        sqlite.query("PRAGMA foreign_key_check") { assertFalse(it.step()) }
        sqlite.query("PRAGMA integrity_check") {
            assertTrue(it.step())
            assertEquals("ok", it.getText(0))
        }
        val queue = checkNotNull(database.queueDao().operation("kept-queue-operation"))
        assertEquals("REPLACE", queue.operationKind)
        assertEquals("", queue.operationFingerprint)
        assertNull(queue.expectedCurrentEntryId)
        assertEquals("PRESERVE_SUPPLIED", queue.orderPolicy)
    }

    private suspend fun assertProductionPreserved(database: MeloXDatabase, before: List<TableRows>) = database.withTestWriterTransaction {
        // Acquiring the writer opens Room's real upgrade path and generated validation.
        val sqlite = this
        assertEquals(5, sqlite.scalarLong("PRAGMA user_version").toInt())
        for (table in before) sqlite.query(select(table)) { assertEquals(table.name, table.rows, cells(it)) }
        sqlite.query("PRAGMA foreign_key_check") { assertFalse(it.step()) }
        sqlite.query("PRAGMA integrity_check") {
            assertTrue(it.step())
            assertEquals("ok", it.getText(0))
        }
        val queue = checkNotNull(database.queueDao().operation("kept-queue-operation"))
        assertEquals("REPLACE", queue.operationKind)
        assertEquals("", queue.operationFingerprint)
        assertNull(queue.expectedCurrentEntryId)
        assertEquals("PRESERVE_SUPPLIED", queue.orderPolicy)
    }

    private fun select(table: TableRows): String = "SELECT " + table.columns.joinToString(", ", transform = ::identifier) +
        " FROM " + identifier(table.name) + " ORDER BY " + table.keys.joinToString(", ", transform = ::identifier)

    private fun identifier(value: String): String {
        require(value.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
        return "`$value`"
    }

    private fun cells(cursor: Cursor): List<List<Cell>> = buildList {
        while (cursor.moveToNext()) add(List(cursor.columnCount) { index ->
            val type = cursor.getType(index)
            Cell(type, when (type) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_BLOB -> Base64.encodeToString(cursor.getBlob(index), Base64.NO_WRAP)
                else -> cursor.getString(index)
            })
        })
    }

    private fun cells(statement: SQLiteStatement): List<List<Cell>> = buildList {
        while (statement.step()) add(List(statement.getColumnCount()) { index ->
            val type = statement.cursorStorageType(index)
            Cell(type, when (type) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_BLOB -> Base64.encodeToString(statement.getBlob(index), Base64.NO_WRAP)
                else -> statement.getText(index)
            })
        })
    }

    private suspend fun count(database: TestDatabaseSql, table: String): Long =
        database.query("SELECT COUNT(*) FROM " + identifier(table)) {
            assertTrue(it.step())
            it.getLong(0)
        }

    private fun seedQueue(sqlite: SQLiteDatabase) {
        insert(sqlite, "queue_generations", "generationId" to "kept-queue-generation", "operationId" to "kept-queue-operation",
            "expectedGenerationId" to null, "expectedQueueRevision" to 0L, "viewId" to "kept-queue-input", "viewRevision" to 7L,
            "totalCount" to 5L, "shuffle" to 1, "repeatMode" to "SINGLE", "cycleLimit" to 2, "startOriginalOrder" to 2L,
            "initialPositionMs" to 100L, "allowEmpty" to 0, "phase" to 2, "writtenCount" to 5L, "committedQueueRevision" to 1L)
        repeat(5) { index ->
            insert(sqlite, "queue_entries", "generationId" to "kept-queue-generation", "queueEntryId" to "kept-entry-$index",
                "providerId" to TrackKey.LOCAL_PROVIDER, "trackId" to TRACK_ID, "originalOrder" to index.toLong(),
                "playbackOrder" to 4L - index, "displayName" to "Kept duplicate $index", "durationMs" to 180_000L,
                "accountId" to null, "locationId" to null, "locationUri" to null, "grantUri" to null,
                "grantFlags" to null, "access" to null, "contentRevision" to null)
        }
        insert(sqlite, "queue_head", "id" to 1, "activeGenerationId" to "kept-queue-generation", "queueRevision" to 1L,
            "checkpointRevision" to 3L, "currentEntryId" to "kept-entry-2", "positionMs" to 4_321L, "completedCycles" to 1)
    }

    private fun seedLibraryAndHistory(sqlite: SQLiteDatabase) {
        val source = LocalSourceSpec(SOURCE_ID, LocalSourceKind.TREE, "content://migration-fixture/tree/music",
            authority = "migration-fixture", rootObjectId = ROOT_OBJECT, providerIncarnation = "kept-provider-instance")
        val grant = LocalGrantObservation(read = ObservedCapability.AVAILABLE, enumerate = ObservedCapability.AVAILABLE)
        val spec = LibraryCodec.source(source)
        insert(sqlite, "library_meta", "id" to 1, "revision" to 10L, "rulesRevision" to 2L, "nextAddedSequence" to 6L,
            "nextScanSequence" to 8L, "shortAudioFilter" to 0, "statisticsEpoch" to 3L)
        insert(sqlite, "local_sources", "sourceId" to SOURCE_ID, "spec" to spec, "grantId" to "kept-grant",
            "revision" to 4L, "authEpoch" to 2L, "state" to "ACTIVE", "activeScanId" to null,
            "latestScanId" to "kept-scan", "absenceScanId" to "kept-scan", "absenceScanSequence" to 7L, "watermark" to "kept-watermark")
        insert(sqlite, "local_grants", "grantId" to "kept-grant", "sourceId" to SOURCE_ID, "uri" to source.uri,
            "observation" to LibraryCodec.grant(grant), "readCapability" to "AVAILABLE", "acceptedPersistedFlags" to 0, "appOwned" to 0)
        insert(sqlite, "local_source_versions", "sourceId" to SOURCE_ID, "validFrom" to 10L, "validTo" to null,
            "state" to "ACTIVE", "spec" to spec, "grantId" to "kept-grant", "absenceScanId" to "kept-scan", "absenceScanSequence" to 7L)
        insert(sqlite, "local_locations", "locationId" to "kept-location", "identityKey" to "kept-location-identity",
            "contentRevision" to 3L, "contentToken" to "native:kept:3", "verifiedContentToken" to "verified:kept:3",
            "previousVerifiedContentToken" to "verified:kept:2", "sizeBytes" to 128L, "modifiedEpochMs" to EPOCH_MS, "appOwned" to 0)
        insert(sqlite, "local_location_versions", "versionId" to "kept-location-version", "locationId" to "kept-location",
            "contentRevision" to 3L, "contentToken" to "native:kept:3", "verifiedContentToken" to "verified:kept:3",
            "sizeBytes" to 128L, "modifiedEpochMs" to EPOCH_MS, "validFrom" to 10L, "validTo" to null)
        insert(sqlite, "local_tracks", "trackId" to TRACK_ID, "providerId" to TrackKey.LOCAL_PROVIDER,
            "firstAddedEpochMs" to EPOCH_MS - 100_000L, "firstAddedSequence" to 5L)
        insert(sqlite, "local_track_locations", "relationId" to "kept-relation", "trackId" to TRACK_ID,
            "locationId" to "kept-location", "validFrom" to 10L, "validTo" to null)
        insert(sqlite, "local_track_versions", "versionId" to "kept-track-version", "trackId" to TRACK_ID,
            "providerId" to TrackKey.LOCAL_PROVIDER, "validFrom" to 10L, "validTo" to null, "member" to 1,
            "firstAddedEpochMs" to EPOCH_MS - 100_000L, "firstAddedSequence" to 5L, "filename" to ORIGINAL_NAME,
            "displayTitle" to "Kept actual title", "title" to "Kept actual title", "artist" to "Kept artist",
            "artists" to LibraryCodec.strings(listOf("Kept artist")), "album" to "Kept album", "albumArtist" to "Kept artist",
            "durationMs" to 180_000L, "discNumber" to 1, "trackNumber" to 2, "compilation" to 0, "mimeType" to "audio/mpeg",
            "parserVersion" to 1, "metadataIssue" to null, "titleKey" to LibrarySortKeys.text("Kept actual title"),
            "artistKey" to LibrarySortKeys.text("Kept artist"), "albumSortKey" to LibrarySortKeys.text("Kept album"),
            "filenameKey" to LibrarySortKeys.natural(ORIGINAL_NAME), "albumKey" to "kept-album-key", "searchText" to "kept actual title kept artist",
            "titleSection" to "K", "artistSection" to "K", "albumSection" to "K", "filenameSection" to "C",
            "playCount" to 9L, "listeningMs" to 87_654L, "statisticsEpoch" to 3L)
        insert(sqlite, "local_artist_values", "versionId" to "kept-track-version", "ordinal" to 0,
            "artistKey" to LibrarySortKeys.text("Kept artist"), "displayName" to "Kept artist")
        insert(sqlite, "local_nodes", "nodeId" to "kept-root-node", "sourceId" to SOURCE_ID,
            "objectId" to ROOT_OBJECT, "lastKnownName" to "Music")
        insert(sqlite, "local_nodes", "nodeId" to AUDIO_NODE, "sourceId" to SOURCE_ID,
            "objectId" to "kept-audio-object", "lastKnownName" to ORIGINAL_NAME)
        insert(sqlite, "local_node_versions", "versionId" to "kept-root-version", "nodeId" to "kept-root-node",
            "sourceId" to SOURCE_ID, "parentNodeId" to null, "name" to "Music", "uri" to source.uri,
            "directory" to 1, "validFrom" to 10L, "validTo" to null)
        insert(sqlite, "local_node_versions", "versionId" to "kept-audio-version", "nodeId" to AUDIO_NODE,
            "sourceId" to SOURCE_ID, "parentNodeId" to "kept-root-node", "name" to ORIGINAL_NAME,
            "uri" to "content://migration-fixture/document/kept-audio-object", "directory" to 0, "validFrom" to 10L, "validTo" to null)
        insert(sqlite, "local_access_aliases", "aliasId" to "kept-alias", "sourceId" to SOURCE_ID,
            "objectId" to "kept-audio-object", "locationId" to "kept-location")
        insert(sqlite, "local_alias_versions", "versionId" to "kept-alias-version", "aliasId" to "kept-alias", "sourceId" to SOURCE_ID,
            "locationId" to "kept-location", "nodeId" to AUDIO_NODE, "uri" to "content://migration-fixture/document/kept-audio-object",
            "filename" to ORIGINAL_NAME, "readIssue" to null, "lastSeenSequence" to 7L, "contentRevision" to 3L,
            "providerContentToken" to "native:kept:3", "sizeBytes" to 128L, "validFrom" to 10L, "validTo" to null)
        insert(sqlite, "scan_generations", "scanId" to "kept-scan", "sourceId" to SOURCE_ID, "sourceRevision" to 4L,
            "authEpoch" to 2L, "rulesRevision" to 2L, "mode" to "FULL", "phase" to "COMPLETED", "sequence" to 7L,
            "startedRevision" to 1L, "seen" to 2L, "processed" to 1L, "added" to 1L, "skipped" to 0L, "failed" to 0L,
            "enumerationComplete" to 1, "authorizationValid" to 1, "rulesUnchanged" to 1, "sourceUnchanged" to 1,
            "excludedSubtrees" to 0, "absenceAfterAliasId" to null, "absenceDone" to 1, "watermark" to "kept-watermark", "issue" to null)
        insert(sqlite, "scan_seen", "scanId" to "kept-scan", "objectId" to "kept-audio-object", "nodeId" to AUDIO_NODE,
            "aliasId" to "kept-alias", "metadataProcessed" to 1, "metadataPayload" to null, "metadataAfterRelationId" to null)
        val root = ObservedCandidate(SOURCE_ID, ROOT_OBJECT, source.uri, null, "Music",
            "vnd.android.document/directory", LocalNodeKind.DIRECTORY)
        insert(sqlite, "scan_frontier", "scanId" to "kept-scan", "objectId" to ROOT_OBJECT,
            "candidate" to LibraryCodec.candidate(root), "state" to "DONE")
        insert(sqlite, "scan_rules", "versionId" to "kept-rule-version", "ruleId" to "kept-rule", "sourceId" to SOURCE_ID,
            "nodeId" to "kept-other-hidden-node", "subtree" to 1, "validFrom" to 2L, "validTo" to null)
        insert(sqlite, "library_views", "viewId" to "kept-old-ui-view", "sessionId" to "old-v2-process", "revision" to 10L,
            "totalCount" to 1L, "fingerprint" to "kept-old-view-fingerprint", "owner" to "UI", "expiresElapsedMs" to 601_000L,
            "sortRuleVersion" to 1)
        seedHistory(sqlite)
    }

    private fun seedHistory(sqlite: SQLiteDatabase) {
        insert(sqlite, "listening_meta", "id" to 1, "historyEpoch" to 2L, "statisticsEpoch" to 3L, "rulesVersion" to 1,
            "nextHistorySequence" to 13L, "historyFloorSequence" to 2L, "totalPlayCount" to 9L, "totalListeningMs" to 87_654L)
        insert(sqlite, "listening_rounds", "roundId" to "kept-round", "providerId" to TrackKey.LOCAL_PROVIDER, "trackId" to TRACK_ID,
            "displayName" to "Kept actual title", "historyEpoch" to 2L, "statisticsEpoch" to 3L, "rulesVersion" to 1,
            "firstProgressEpochMs" to EPOCH_MS, "firstProgressElapsedMs" to 1_000L, "firstProgressZoneId" to "Asia/Shanghai",
            "sampledEpochMs" to EPOCH_MS + 15_000L, "sampledElapsedMs" to 16_000L, "sampledZoneId" to "Asia/Shanghai",
            "lastProgressEpochMs" to EPOCH_MS + 15_000L, "totalListeningMs" to 15_000L, "knownDurationMs" to 180_000L,
            "playCounted" to 1, "countedLocalDate" to "2026-11-16", "countedZoneId" to "Asia/Shanghai", "historyId" to 12L)
        insert(sqlite, "listening_round_segments", "roundId" to "kept-round", "ordinal" to 0L, "localDate" to "2026-11-16",
            "zoneId" to "Asia/Shanghai", "listeningMs" to 15_000L)
        insert(sqlite, "listening_history", "historyEpoch" to 2L, "historyId" to 12L, "roundId" to "kept-round",
            "providerId" to TrackKey.LOCAL_PROVIDER, "trackId" to TRACK_ID, "displayName" to "Kept actual title",
            "firstProgressEpochMs" to EPOCH_MS, "firstProgressZoneId" to "Asia/Shanghai", "lastProgressEpochMs" to EPOCH_MS + 15_000L,
            "listeningMs" to 15_000L, "knownDurationMs" to 180_000L)
        insert(sqlite, "listening_track_totals", "statisticsEpoch" to 3L, "providerId" to TrackKey.LOCAL_PROVIDER, "trackId" to TRACK_ID,
            "playCount" to 9L, "listeningMs" to 87_654L, "lastProgressEpochMs" to EPOCH_MS + 15_000L, "displayName" to "Kept actual title")
        insert(sqlite, "listening_day_totals", "statisticsEpoch" to 3L, "localDate" to "2026-11-16", "zoneId" to "Asia/Shanghai",
            "playCount" to 9L, "listeningMs" to 87_654L)
    }

    private fun insert(sqlite: SQLiteDatabase, table: String, vararg fields: Pair<String, Any?>) {
        val values = ContentValues().apply {
            for ((name, value) in fields) when (value) {
                null -> putNull(name)
                is String -> put(name, value)
                is Int -> put(name, value)
                is Long -> put(name, value)
                else -> throw AssertionError("Unsupported fixture value for $table.$name")
            }
        }
        sqlite.insertOrThrow(table, null, values)
    }

    private data class Cell(val storageType: Int, val value: String?)
    private data class TableRows(val name: String, val columns: List<String>, val keys: List<String>, val rows: List<List<Cell>>)

    companion object {
        private const val SOURCE_ID = "kept-source"
        private const val ROOT_OBJECT = "kept-root-object"
        private const val AUDIO_NODE = "kept-audio-node"
        private const val TRACK_ID = "kept-local-track"
        private const val ORIGINAL_NAME = "Cafe\u0301.mp3"
        private const val EPOCH_MS = 1_794_787_200_000L
        private val PLAYLIST_TABLES = listOf("playlist_meta", "playlist_heads", "playlist_generations",
            "playlist_members", "playlist_views", "playlist_receipts", "playlist_exchange_previews",
            "playlist_exchange_rows", "playlist_export_receipts")
    }
}
