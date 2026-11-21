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
import io.github.onlinemo.meloxwatch.data.library.LibraryTransferredImportEntity
import io.github.onlinemo.meloxwatch.data.library.transferModel
import io.github.onlinemo.meloxwatch.data.local.LocalGrantObservation
import io.github.onlinemo.meloxwatch.data.local.LocalNodeKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.transfer.TransferHash
import io.github.onlinemo.meloxwatch.data.transfer.TransferIssue
import io.github.onlinemo.meloxwatch.data.transfer.TransferTestFixtures
import java.io.File
import java.util.UUID
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

/** Generated historical V5 files hold original audio rows; production V6 DDL must preserve them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TransferRoomMigrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication() as Context

    @Test(timeout = 30_000L) fun bundledMigrationPreservesEveryOldCellAndOriginalSchemaOneAndTwoReceiptThenReopens() = runBlocking {
        val file = File(temporary.root, "v5-to-v6.db")
        val before = seedV5(file)
        val database = open(file)
        val identity = try {
            assertOldData(database, before)
            assertTransferredRows(database, before)
            database.withTestWriterSql {
                assertEquals(6L, scalarLong("PRAGMA user_version"))
                assertEquals("ok", scalarText("PRAGMA integrity_check"))
                query("PRAGMA foreign_key_check") { assertFalse(it.step()) }
                assertEquals(0L, scalarLong("SELECT COUNT(*) FROM display_names"))
                assertEquals(0L, scalarLong("SELECT COUNT(*) FROM transfer_display_previews"))
                assertEquals(0L, scalarLong("SELECT COUNT(*) FROM transferred_theme_candidates"))
                checkNotNull(scalarText(IDENTITY)).also { assertFalse(it == before.identity) }
            }
        } finally { database.close() }
        val reopened = open(file)
        try {
            assertOldData(reopened, before)
            assertTransferredRows(reopened, before)
            assertEquals(identity, reopened.withTestWriterSql { scalarText(IDENTITY) })
        } finally { reopened.close() }
    }

    @Test(timeout = 30_000L) fun bundledFailureAfterBothDomainTablesRollsBackAllDdlIdentityAndRowsBeforeExactRetry() = runBlocking {
        val file = File(temporary.root, "v5-bundled-rollback.db")
        val before = seedV5(file)
        val last = object : Migration(5, 6) {
            override fun migrate(connection: SQLiteConnection) {
                MeloXDatabase.MIGRATION_5_6.migrate(connection)
                throw ControlledFailure()
            }
        }
        val failing = open(file, last)
        try { expectFailure(ControlledFailure::class.java) { failing.withTestWriterSql { scalarLong("PRAGMA user_version") } } }
        finally { failing.close() }
        assertRollback(file, before)
        val retried = open(file)
        try { assertOldData(retried, before); assertTransferredRows(retried, before) }
        finally { retried.close() }
    }

    @Test(timeout = 30_000L) fun supportSqliteCreatesTheSameV6SchemaWhilePreservingTheV5MasterAndEverySavedAudioField() = runBlocking {
        val file = File(temporary.root, "v5-support.db")
        val before = seedV5(file)
        val helper = supportOpen(file, fail = false)
        val reference = open(File(temporary.root, "generated-v6.db"))
        try {
            val sqlite = helper.writableDatabase
            assertEquals(6, sqlite.version)
            assertEquals(before.catalog, cursorCells(sqlite.query(oldCatalog(before.tables))))
            for ((table, rows) in before.rows) assertEquals(table, rows, cursorCells(sqlite.query(select(table))))
            assertEquals(listOf(listOf("s:${before.identity}")), cursorCells(sqlite.query(IDENTITY)))
            for (table in NEW_TABLES) for (pragma in listOf("table_info", "index_list", "foreign_key_list")) {
                val sql = "PRAGMA $pragma($table)"
                assertEquals(table, reference.withTestWriterSql { query(sql, block = ::statementCells) }, cursorCells(sqlite.query(sql)))
            }
            val expected = before.audioRows.map { original -> operationCells(original) }
            assertEquals(expected, cursorCells(sqlite.query(transferRows)))
        } finally { reference.close(); helper.close() }
    }

    @Test(timeout = 30_000L) fun supportSqliteFailurePreservesVersionIdentityAndAllOriginalRowsThenRoomCanRetry() = runBlocking {
        val file = File(temporary.root, "v5-support-rollback.db")
        val before = seedV5(file)
        val helper = supportOpen(file, fail = true)
        try { expectFailure(ControlledFailure::class.java) { helper.writableDatabase } } finally { helper.close() }
        assertRollback(file, before)
        val retried = open(file)
        try { assertOldData(retried, before); assertTransferredRows(retried, before) }
        finally { retried.close() }
    }

    @Test(timeout = 30_000L) fun zeroCompletedRevisionIsRejectedByBothDdlOverloadsWithoutAddingAReplayableReceipt() = runBlocking {
        for (support in listOf(false, true)) {
            val file = File(temporary.root, "v5-zero-$support.db")
            val original = seedV5(file)
            val damaged = damage(file, "UPDATE transferred_import_operations SET committedRevision = 0 WHERE phase = 'COMMITTED'")
            rejectAndCheckRollback(file, damaged, support, IllegalStateException::class.java)
            val historical = HistoricalMeloXV5Database.open(context, file.absolutePath)
            try {
                for (record in original.audioRows.filter { it.phase == ImportPhase.COMMITTED.name }) {
                    historical.transferredImportDao().updateTransferredImport(record)
                }
            } finally { historical.close() }
            val recovered = open(file)
            try { assertOldData(recovered, original); assertTransferredRows(recovered, original) }
            finally { recovered.close() }
        }
    }

    @Test(timeout = 30_000L) fun damagedSerializedMetadataIsRejectedBeforeEitherOverloadCanPromoteTheOldMarker() = runBlocking {
        for (support in listOf(false, true)) {
            val file = File(temporary.root, "v5-bad-metadata-$support.db")
            seedV5(file)
            val damaged = damage(file, "UPDATE transferred_import_operations SET metadata = 'not-json' WHERE phase = 'COMMITTED'")
            rejectAndCheckRollback(file, damaged, support, Exception::class.java)
        }
    }

    @Test(timeout = 30_000L) fun savedDigestMismatchIsRejectedWithTheOriginalRowsRetainedForRecovery() = runBlocking {
        for (support in listOf(false, true)) {
            val file = File(temporary.root, "v5-digest-mismatch-$support.db")
            seedV5(file)
            val damaged = damage(file, "UPDATE transferred_import_operations SET sha256 = ? WHERE phase = 'COMMITTED'", "f".repeat(64))
            rejectAndCheckRollback(file, damaged, support, IllegalArgumentException::class.java)
        }
    }

    private suspend fun seedV5(file: File): Before {
        val database = HistoricalMeloXV5Database.open(context, file.absolutePath)
        try {
            database.libraryDao().insertMeta(LibraryMetaEntity(revision = 17L))
            val selection = UUID.randomUUID().toString()
            val external = LocalSourceSpec("saved-saf-selection", LocalSourceKind.DOCUMENT,
                "content://migration-fixture/document/selected", authority = "migration-fixture", rootObjectId = "selected")
            val candidate = ObservedCandidate(external.sourceId, "selected", external.uri, null, "Selected.wav", "audio/wav", LocalNodeKind.AUDIO, 1_024L)
            val intent = ImportIntent(selection, external, candidate, "fixture-selected-content")
            database.libraryDao().insertImport(LibraryImportEntity(selection, LibraryCodec.source(external), LibraryCodec.candidate(candidate),
                intent.sourceIdentity, null, selection, ImportPhase.COPYING.name, 0L, null, null, null,
                null, null, null, null, null, null))
            val bytes = TransferTestFixtures.wav()
            val digest = TransferHash.digest(bytes).forLocalStorage()
            val metadata = io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata(title = "Saved audio", mimeType = "audio/wav", durationMs = 100L)
            for (schema in 1..2) for (phase in ImportPhase.entries) {
                val operation = UUID.randomUUID().toString()
                val committed = phase == ImportPhase.COMMITTED
                val verified = phase in setOf(ImportPhase.VERIFIED, ImportPhase.INSTALLED, ImportPhase.COMMITTED)
                val track = "saved-audio-$schema"
                val owned = LocalSourceSpec("transfer:$operation", LocalSourceKind.APP_OWNED,
                    "content://migration-audio-fixture/$operation", authority = "migration-audio-fixture", rootObjectId = operation)
                val grant = LocalGrantObservation(appOwned = true, read = ObservedCapability.AVAILABLE,
                    offlineRead = ObservedCapability.AVAILABLE, enumerate = ObservedCapability.UNAVAILABLE,
                    persistedRead = ObservedCapability.UNAVAILABLE, persistedWrite = ObservedCapability.UNAVAILABLE)
                val installed = ObservedCandidate(owned.sourceId, operation, owned.uri, null, "Saved.wav", "audio/wav",
                    LocalNodeKind.AUDIO, bytes.size.toLong(), providerContentToken = "sha256:$digest",
                    entityProof = "owned:migration-audio-fixture:$operation")
                if (committed) {
                    database.libraryDao().insertTrack(LibraryTrackEntity(track, "local", 1L, 1L))
                    database.withTestWriterTransaction { execute("INSERT INTO lyrics_track_preferences(trackId, schemaVersion, revision, userDelayMs) VALUES (?, 1, 7, -300)", track) }
                }
                val original = LibraryTransferredImportEntity(operation, schema, 1, "a".repeat(64), "b".repeat(64),
                    TransferHash.digest("original-schema-$schema-${phase.name}".toByteArray()).forLocalStorage(), "Saved.wav", "audio/wav",
                    if (schema == 1) null else bytes.size.toLong(), digest, 0L, operation, phase.name,
                    if (verified) bytes.size.toLong() else 0L, if (verified) digest else null,
                    if (verified) LibraryCodec.metadata(metadata) else null,
                    when (phase) { ImportPhase.FAILED -> TransferIssue.INTEGRITY_FAILED.name; ImportPhase.CANCELLED -> TransferIssue.INTERRUPTED.name; else -> null },
                    if (committed) "local" else null, if (committed) track else null, if (committed) owned.sourceId else null,
                    if (committed) LibraryCodec.source(owned) else null, if (committed) LibraryCodec.grant(grant) else null,
                    if (committed) LibraryCodec.candidate(installed) else null, if (committed) 15L + schema else null)
                // A saved historical fixture is validated as a complete original audio record,
                // never by fabricating a new general TransferReceipt in the migration.
                original.transferModel()
                database.transferredImportDao().insertTransferredImport(original)
            }
            return snapshot(database)
        } finally { database.close() }
    }

    private suspend fun snapshot(database: HistoricalMeloXV5Database): Before {
        val audio = database.withTestWriterSql {
            query("SELECT operationId FROM transferred_import_operations ORDER BY operationId") { rows -> buildList { while (rows.step()) add(rows.getText(0)) } }
        }.map { checkNotNull(database.transferredImportDao().transferredImportRecord(it)) }
        return database.withTestWriterSql {
            assertEquals(5L, scalarLong("PRAGMA user_version"))
            val tables = query(TABLES) { rows -> buildList { while (rows.step()) add(rows.getText(0)) } }
            Before(tables, tables.associateWith { query(select(it), block = ::statementCells) },
                query(oldCatalog(tables), block = ::statementCells), checkNotNull(scalarText(IDENTITY)), audio)
        }
    }

    private suspend fun damage(file: File, sql: String, vararg args: Any?): Before {
        val historical = HistoricalMeloXV5Database.open(context, file.absolutePath)
        try { historical.withTestWriterSql { execute(sql, *args) }; return snapshot(historical) }
        finally { historical.close() }
    }
    private suspend fun assertOldData(database: RoomDatabase, before: Before) = database.withTestWriterSql {
        assertEquals(before.catalog, query(oldCatalog(before.tables), block = ::statementCells))
        for ((table, rows) in before.rows) assertEquals(table, rows, query(select(table), block = ::statementCells))
    }
    private suspend fun assertTransferredRows(database: MeloXDatabase, before: Before) {
        assertEquals(before.audioRows.map(::operationCells), database.withTestWriterSql { query(transferRows, block = ::statementCells) })
        for (original in before.audioRows) {
            val migrated = checkNotNull(database.transferredImportDao().transferredImportRecord(original.operationId))
            assertEquals(original, migrated)
            assertEquals(original.payloadHash, checkNotNull(database.transferRoomDao().operation(original.operationId)).payloadHash)
            val expected = original.transferModel().receipt()
            val stored = checkNotNull(database.transferRoomDao().operation(original.operationId))
            if (expected == null) { assertNull(stored.resultKind); assertNull(stored.appliedRevision); assertNull(stored.resultId); assertNull(stored.actualBytes) }
            else { assertEquals(expected.result.wire, stored.resultKind); assertEquals(expected.revision, stored.appliedRevision);
                assertEquals(expected.resultId, stored.resultId); assertEquals(expected.actualBytes, stored.actualBytes) }
        }
    }
    private fun operationCells(original: LibraryTransferredImportEntity): List<String?> {
        val committed = original.phase == ImportPhase.COMMITTED.name
        return listOf("s:${original.operationId}", "i:${original.protocolSchema}", "i:${original.operationKind}",
            "s:${original.targetFingerprint}", "s:${original.peerFingerprint}", "s:${original.payloadHash}",
            if (committed) "i:1" else null, if (committed) "i:${original.committedRevision}" else null,
            if (committed) "s:${original.trackId}" else null, if (committed) "i:${original.actualBytes}" else null)
    }
    private suspend fun rejectAndCheckRollback(file: File, before: Before, support: Boolean, expected: Class<out Throwable>) {
        if (support) {
            val helper = supportOpen(file, fail = false)
            try { expectFailure(expected) { helper.writableDatabase } } finally { helper.close() }
        } else {
            val database = open(file)
            try { expectFailure(expected) { database.withTestWriterSql { scalarLong("PRAGMA user_version") } } }
            finally { database.close() }
        }
        assertRollback(file, before)
    }
    private suspend fun assertRollback(file: File, before: Before) {
        val historical = HistoricalMeloXV5Database.open(context, file.absolutePath)
        try {
            assertOldData(historical, before)
            historical.withTestWriterSql {
                assertEquals(5L, scalarLong("PRAGMA user_version")); assertEquals(before.identity, scalarText(IDENTITY))
                for (table in NEW_TABLES) assertEquals(0L, scalarLong("SELECT COUNT(*) FROM sqlite_master WHERE name = ?", table))
            }
        } finally { historical.close() }
    }
    private fun open(file: File, last: Migration = MeloXDatabase.MIGRATION_5_6): MeloXDatabase = Room.databaseBuilder(
        context, MeloXDatabase::class.java, file.absolutePath,
    ).setDriver(BundledSQLiteDriver()).addMigrations(MeloXDatabase.MIGRATION_1_2, MeloXDatabase.MIGRATION_2_3,
        MeloXDatabase.MIGRATION_3_4, MeloXDatabase.MIGRATION_4_5, last).build()
    private fun supportOpen(file: File, fail: Boolean): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(6) {
            override fun onConfigure(db: SupportSQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
            override fun onCreate(db: SupportSQLiteDatabase) { throw AssertionError("A generated historical V5 file is required") }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                assertEquals(5, oldVersion); assertEquals(6, newVersion)
                MeloXDatabase.MIGRATION_5_6.migrate(db)
                if (fail) throw ControlledFailure()
            }
        }
        return FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(file.absolutePath).callback(callback).build())
    }
    private suspend fun expectFailure(expected: Class<out Throwable>, action: suspend () -> Any?) {
        try { action(); throw AssertionError("Expected migration rejection") }
        catch (error: Exception) { assertTrue(generateSequence<Throwable>(error) { it.cause }.any { expected.isInstance(it) }) }
    }
    private fun select(table: String) = "SELECT * FROM `$table` ORDER BY rowid"
    private fun oldCatalog(tables: List<String>) = "SELECT type, name, tbl_name, sql FROM sqlite_master WHERE tbl_name IN (" +
        tables.joinToString(",") { "'$it'" } + ") ORDER BY type, name"
    private fun statementCells(rows: SQLiteStatement): List<List<String?>> = buildList {
        while (rows.step()) add((0 until rows.getColumnCount()).map { column -> when (rows.cursorStorageType(column)) {
            Cursor.FIELD_TYPE_NULL -> null; Cursor.FIELD_TYPE_INTEGER -> "i:${rows.getLong(column)}"
            Cursor.FIELD_TYPE_FLOAT -> "f:${rows.getDouble(column)}"; Cursor.FIELD_TYPE_STRING -> "s:${rows.getText(column)}"
            else -> "b:" + rows.getBlob(column).joinToString("") { "%02x".format(it.toInt() and 255) }
        } })
    }
    private fun cursorCells(cursor: Cursor): List<List<String?>> = cursor.use { rows -> buildList {
        while (rows.moveToNext()) add((0 until rows.columnCount).map { column -> when (rows.getType(column)) {
            Cursor.FIELD_TYPE_NULL -> null; Cursor.FIELD_TYPE_INTEGER -> "i:${rows.getLong(column)}"
            Cursor.FIELD_TYPE_FLOAT -> "f:${rows.getDouble(column)}"; Cursor.FIELD_TYPE_STRING -> "s:${rows.getString(column)}"
            else -> "b:" + rows.getBlob(column).joinToString("") { "%02x".format(it.toInt() and 255) }
        } })
    } }
    private class ControlledFailure : IllegalStateException("Controlled failure after V6 DDL")
    private data class Before(val tables: List<String>, val rows: Map<String, List<List<String?>>>,
        val catalog: List<List<String?>>, val identity: String, val audioRows: List<LibraryTransferredImportEntity>)
    companion object {
        private const val IDENTITY = "SELECT identity_hash FROM room_master_table WHERE id = 42"
        private const val TABLES = "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
            "AND name NOT IN ('room_master_table', 'android_metadata') ORDER BY name"
        private const val transferRows = "SELECT operationId, protocolSchema, operationKind, targetFingerprint, peerFingerprint, payloadHash, " +
            "resultKind, appliedRevision, resultId, actualBytes FROM transfer_room_operations ORDER BY operationId"
        private val NEW_TABLES = listOf("display_groups", "display_names", "display_album_links", "display_artist_links",
            "transfer_room_operations", "transfer_display_previews", "transferred_theme_candidates")
    }
}
