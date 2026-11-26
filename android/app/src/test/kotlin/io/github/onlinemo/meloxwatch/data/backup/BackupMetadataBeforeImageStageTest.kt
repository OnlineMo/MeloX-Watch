package io.github.onlinemo.meloxwatch.data.backup

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.onlinemo.meloxwatch.data.metadata.LocalMetadataAssociationRow
import io.github.onlinemo.meloxwatch.data.metadata.MetadataProvenance
import io.github.onlinemo.meloxwatch.data.metadata.MetadataPurpose
import io.github.onlinemo.meloxwatch.data.metadata.decode
import io.github.onlinemo.meloxwatch.model.TrackKey
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Real file leases must preserve sealed v1 bytes and exact native identities across restore failure. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BackupMetadataBeforeImageStageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val provenance = MetadataProvenance("1024", "Fixture song", "Fixture artist", "Fixture album")
    private val rawProvenance = "  " + provenance.encode() + "\n"

    @Test(timeout = 60_000L)
    fun ordinaryFileBeforeImageKeepsEveryPageAndDoesNotMergeEqualContentIdentities() {
        val directory = directory()
        val original = (0 until 205).map { beforeRow(it) }
        val otherPurpose = BackupMetadataBeforeRow(original.first().trackId, MetadataPurpose.ARTWORK,
            original.first().row)
        val stage = sealedStage(directory)
        val sealedHash = backupFileSha256(directory.child("rows.db")) {}
        stage.use {
            it.beginMetadataBefore()
            val connection = connection(it)
            assertEquals(1L, scalar(connection, "PRAGMA user_version"))
            assertEquals(-1024L, scalar(connection, "PRAGMA main.cache_size"))
            assertTrue(scalar(connection, "PRAGMA main.cache_spill") > 0L)
            assertEquals(1L, scalar(connection, "SELECT COUNT(*) FROM main.sqlite_schema WHERE name='backup_metadata_before' AND type='table'"))
            assertEquals(0L, scalar(connection, "SELECT COUNT(*) FROM sqlite_temp_schema WHERE name LIKE 'backup_%'"))
            connection.prepare("PRAGMA database_list").use { databases ->
                var found = false
                while (databases.step()) if (databases.getText(1) == "main") {
                    assertFalse(found)
                    assertTrue(java.io.File(databases.getText(2)).canonicalFile == directory.child("rows.db").canonicalFile)
                    found = true
                }
                assertTrue(found)
            }
            expectBackupIssue(BackupIssue.INCOMPLETE_ARCHIVE) {
                it.metadataBefore(TrackKey("local", original.first().trackId), MetadataPurpose.LYRICS)
            }
            expectBackupIssue(BackupIssue.INCOMPLETE_ARCHIVE) { it.appendMetadataBefore(original.take(101)) }
            assertEquals(0L, scalar(connection, "SELECT COUNT(*) FROM main.backup_metadata_before"))
            for (page in original.chunked(100)) it.appendMetadataBefore(page)
            it.appendMetadataBefore(listOf(otherPurpose))
            it.sealMetadataBefore(206)
            var after: String? = null
            var offset = 0
            for (size in listOf(100, 100, 5)) {
                val page = it.metadataBeforePage(MetadataPurpose.LYRICS, after)
                assertEquals(size, page.size)
                for ((index, actual) in page.withIndex()) {
                    val expected = original[offset + index]
                    assertEquals(expected.row.decode(TrackKey("local", expected.trackId), expected.purpose), actual)
                }
                offset += size
                after = page.last().trackKey.trackId
            }
            assertTrue(it.metadataBeforePage(MetadataPurpose.LYRICS, after).isEmpty())
            assertEquals(205, offset)
            assertEquals(otherPurpose.row.decode(TrackKey("local", otherPurpose.trackId), MetadataPurpose.ARTWORK),
                it.metadataBefore(TrackKey("local", otherPurpose.trackId), MetadataPurpose.ARTWORK))
            connection.prepare("SELECT provenance FROM main.backup_metadata_before WHERE trackId=? AND purpose='LYRICS'").use { stored ->
                stored.bindText(1, original.first().trackId); assertTrue(stored.step())
                assertEquals(rawProvenance, stored.getText(0))
            }
            expectBackupIssue(BackupIssue.INCOMPLETE_ARCHIVE) { it.appendMetadataBefore(listOf(beforeRow(206))) }
            it.endMetadataBefore()
            assertEquals(0L, scalar(connection, "SELECT COUNT(*) FROM main.sqlite_schema WHERE name='backup_metadata_before'"))
        }
        assertEquals(sealedHash, backupFileSha256(directory.child("rows.db")) {})
        assertSealedReopens(directory)
    }

    @Test(timeout = 60_000L)
    fun cancellationRetiresOnlyTheOwnedPartialBeforeImageAndKeepsSealedBytes() {
        val directory = directory()
        var cancelled = false
        val cancellation = CancellationException("Cancelled native before-image fixture")
        val stage = sealedStage(directory) { if (cancelled) throw cancellation }
        val sealedHash = backupFileSha256(directory.child("rows.db")) {}
        stage.use {
            it.beginMetadataBefore()
            it.appendMetadataBefore((0 until 100).map { index -> beforeRow(index) })
            cancelled = true
            try { it.appendMetadataBefore(listOf(beforeRow(100))); throw AssertionError("Partial before-image accepted cancellation") }
            catch (failure: CancellationException) { assertSame(cancellation, failure) }
            it.endMetadataBefore()
        }
        assertEquals(sealedHash, backupFileSha256(directory.child("rows.db")) {})
        assertSealedReopens(directory)
    }

    @Test(timeout = 60_000L)
    fun invalidNativeRowsCannotSealOrBecomeReadableBeforeImages() {
        val original = beforeRow(0)
        val invalid = listOf(
            LocalMetadataAssociationRow(false, 1, 17, original.row.assetId, original.row.sha256, 128, rawProvenance),
            LocalMetadataAssociationRow(true, 2, 17, original.row.assetId, original.row.sha256, 128, rawProvenance),
            LocalMetadataAssociationRow(true, 1, -1, original.row.assetId, original.row.sha256, 128, rawProvenance),
            LocalMetadataAssociationRow(true, 1, 17, "invalid-native-id", original.row.sha256, 128, rawProvenance),
            LocalMetadataAssociationRow(true, 1, 17, original.row.assetId, original.row.sha256, 128, "invalid-native-provenance"),
        )
        for (row in invalid) {
            val directory = directory()
            val stage = sealedStage(directory)
            val sealedHash = backupFileSha256(directory.child("rows.db")) {}
            stage.use {
                it.beginMetadataBefore()
                expectBackupIssue(BackupIssue.RECOVERY_REQUIRED) {
                    it.appendMetadataBefore(listOf(BackupMetadataBeforeRow(original.trackId, MetadataPurpose.LYRICS, row)))
                }
                expectBackupIssue(BackupIssue.INCOMPLETE_ARCHIVE) {
                    it.metadataBefore(TrackKey("local", original.trackId), MetadataPurpose.LYRICS)
                }
                expectBackupIssue(BackupIssue.INTEGRITY_MISMATCH) { it.sealMetadataBefore(1) }
                it.endMetadataBefore()
            }
            assertEquals(sealedHash, backupFileSha256(directory.child("rows.db")) {})
            assertSealedReopens(directory)
        }
    }

    @Test(timeout = 60_000L)
    fun directSealedCallerRechecksStoredStateBeforeRestoreWrites() {
        val directory = directory()
        val stage = sealedStage(directory)
        BundledSQLiteDriver().open(directory.child("rows.db").absolutePath).use {
            it.execute("UPDATE stage_meta SET logicalBytes=logicalBytes+1 WHERE id=1")
        }
        val changedHash = backupFileSha256(directory.child("rows.db")) {}
        stage.use {
            expectBackupIssue(BackupIssue.INTEGRITY_MISMATCH) { it.clearActualIds() }
            expectBackupIssue(BackupIssue.INTEGRITY_MISMATCH) { it.beginMetadataBefore() }
        }
        assertEquals(changedHash, backupFileSha256(directory.child("rows.db")) {})
        BundledSQLiteDriver().open(directory.child("rows.db").absolutePath).use {
            assertEquals(0L, scalar(it, "SELECT COUNT(*) FROM main.sqlite_schema WHERE name='backup_metadata_before'"))
        }
    }

    @Test(timeout = 60_000L)
    fun unknownPersistedDerivedTableIsRejectedAndNeverDroppedAsCleanup() {
        val directory = directory()
        sealedStage(directory).close()
        BundledSQLiteDriver().open(directory.child("rows.db").absolutePath).use {
            it.execute("CREATE TABLE backup_metadata_before(stub INTEGER NOT NULL)")
            it.execute("INSERT INTO backup_metadata_before(stub) VALUES(19)")
        }
        val changedHash = backupFileSha256(directory.child("rows.db")) {}
        expectBackupIssue(BackupIssue.CORRUPT_ARCHIVE) { SqliteBackupStage.openVerified(directory) {}.close() }
        assertEquals(changedHash, backupFileSha256(directory.child("rows.db")) {})
        BundledSQLiteDriver().open(directory.child("rows.db").absolutePath).use {
            assertEquals(19L, scalar(it, "SELECT stub FROM backup_metadata_before"))
        }
    }

    private fun beforeRow(index: Int): BackupMetadataBeforeRow {
        val id = "fixture-before-" + index.toString().padStart(3, '0')
        return BackupMetadataBeforeRow(id, MetadataPurpose.LYRICS, LocalMetadataAssociationRow(true, 1, 17,
            stableUuid("native-before", id), "a".repeat(64), 128, rawProvenance))
    }

    private fun directory() = BackupPrivateDirectory.open(temporary.newFolder())

    private fun sealedStage(directory: BackupPrivateDirectory, checkCancelled: () -> Unit = {}): SqliteBackupStage =
        SqliteBackupStage.create(directory, checkCancelled).also {
            it.begin(BackupTestFixtures.header(BackupScope.LIBRARY, BackupScope.LYRICS))
            it.record(BackupRecord(BackupRecordKind.LIBRARY_META, "library", mapOf("shortAudioFilter" to BackupCell.Flag(false))))
            it.sealSnapshot()
        }

    private fun assertSealedReopens(directory: BackupPrivateDirectory) {
        SqliteBackupStage.openVerified(directory) {}.use { stage ->
            assertEquals(1L, stage.count(BackupRecordKind.LIBRARY_META))
            assertEquals(1L, scalar(connection(stage), "PRAGMA user_version"))
            assertEquals(0L, scalar(connection(stage), "SELECT COUNT(*) FROM main.sqlite_schema WHERE name LIKE 'backup_%'"))
            assertEquals(0L, scalar(connection(stage), "SELECT COUNT(*) FROM sqlite_temp_schema WHERE name LIKE 'backup_%'"))
        }
    }

    private fun connection(stage: SqliteBackupStage): SQLiteConnection = SqliteBackupStage::class.java
        .getDeclaredField("connection").apply { isAccessible = true }.get(stage) as SQLiteConnection

    private fun scalar(connection: SQLiteConnection, sql: String): Long = connection.prepare(sql).use {
        assertTrue(it.step()); it.getLong(0)
    }
}
