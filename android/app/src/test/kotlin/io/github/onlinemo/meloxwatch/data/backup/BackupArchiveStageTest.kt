package io.github.onlinemo.meloxwatch.data.backup

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Native staging/ZIP definitions run in the existing cloud unit lane; no device gates are claimed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BackupArchiveStageTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun directory(): BackupPrivateDirectory = BackupPrivateDirectory.open(temporary.newFolder())
    private fun stage(directory: BackupPrivateDirectory) = SqliteBackupStage.create(directory) {}

    @Test(timeout = 60_000L)
    fun queueBeyondTenThousandItemsRoundTripsAllInstancesAndBothOrders() {
        val count = 10_037L
        val source = directory()
        val target = directory()
        stage(source).use { original ->
            original.begin(BackupTestFixtures.header(BackupScope.QUEUE))
            original.record(BackupTestFixtures.queueMeta(count, 4321L))
            for (ordinal in 0 until count) original.record(BackupTestFixtures.queueEntry(ordinal, count))
            original.sealSnapshot()
            val archive = source.child("export.zip")
            val receipt = BackupArchiveWriter().write(original, archive) {}
            assertEquals(receipt.sha256, backupFileSha256(archive) {})
            stage(target).use { restored ->
                assertEquals(original.header, BackupArchiveReader().stage(archive, restored) {})
                assertEquals(count, restored.count(BackupRecordKind.QUEUE_ENTRY))
                assertTrue(original.row(BackupRecordKind.QUEUE_META, "queue")!!.sameContent(restored.row(BackupRecordKind.QUEUE_META, "queue")!!))
                for (ordinal in listOf(0L, 99L, 100L, 4321L, 9999L, count - 1L)) {
                    val item = restored.row(BackupRecordKind.QUEUE_ENTRY, BackupTestFixtures.queueId(ordinal))!!
                    assertEquals(ordinal, item.long("originalOrder"))
                    assertEquals(count - ordinal - 1L, item.long("playbackOrder"))
                    assertEquals("fixture-track-${ordinal % 17L}", item.text("trackId"))
                }
            }
        }
    }

    @Test(timeout = 30_000L)
    fun ownedLyricContentRoundTripsDespiteOmittedDocumentIdentityHint() {
        val bytes = "[00:01.00]Fixture line\n[00:02.00]Second fixture line\n".toByteArray()
        val hash = backupSha256(bytes)
        val resource = BackupResource(BackupLogicalSchema.resourceId(BackupResourceKind.LYRICS, hash),
            BackupResourceKind.LYRICS, bytes.size.toLong(), hash, false)
        val source = directory()
        val target = directory()
        stage(source).use { original ->
            original.begin(BackupTestFixtures.header(BackupScope.LYRICS, BackupScope.RESOURCES))
            original.record(BackupTestFixtures.lyrics("fixture-track-a", hash, resource.id))
            original.record(BackupLogicalSchema.resourceRecord(resource))
            original.writeResource(resource.id).use { it.write(bytes) }
            original.sealSnapshot()
            val archive = source.child("export.zip")
            BackupArchiveWriter().write(original, archive) {}
            stage(target).use { restored ->
                BackupArchiveReader().stage(archive, restored) {}
                assertEquals(hash, restored.row(BackupRecordKind.LYRICS_TRACK, "fixture-track-a")!!.text("primarySha256"))
                assertArrayEquals(bytes, restored.openResource(resource.id).use { it.readBytes() })
            }
        }
    }

    @Test(timeout = 30_000L)
    fun halfArchiveAbortsItsOwnStageAndLeavesTheVerifiedSourceIntact() {
        val root = directory()
        stage(root).use { source ->
            source.begin(BackupTestFixtures.header(BackupScope.HISTORY))
            source.record(BackupTestFixtures.historyMeta())
            source.record(BackupTestFixtures.historyEntry(1L))
            source.sealSnapshot()
            val archive = root.child("export.zip")
            BackupArchiveWriter().write(source, archive) {}
            val incomplete = root.child("half.zip")
            ZipFile(archive).use { zip -> ZipOutputStream(FileOutputStream(incomplete)).use { output ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name == "complete.json") continue
                    output.putNextEntry(ZipEntry(entry.name).apply { time = 1577836800000L })
                    zip.getInputStream(entry).use { it.copyTo(output) }
                    output.closeEntry()
                }
            } }
            val target = directory()
            stage(target).use { restored ->
                expectBackupIssue(BackupIssue.INCOMPLETE_ARCHIVE) { BackupArchiveReader().stage(incomplete, restored) {} }
                expectBackupIssue(BackupIssue.INCOMPLETE_ARCHIVE) { restored.page(BackupRecordKind.HISTORY_ENTRY, null) }
            }
            assertEquals(1L, source.count(BackupRecordKind.HISTORY_ENTRY))
            assertEquals(source.header, BackupArchiveReader().verify(archive) {})
        }
    }

    @Test(timeout = 30_000L)
    fun escapingEntryAndDuplicateLogicalIdentityAreRejectedBeforeAnyLiveWrite() {
        val bad = File(temporary.root, "escape.zip")
        ZipOutputStream(FileOutputStream(bad)).use { zip ->
            zip.putNextEntry(ZipEntry(BACKUP_HEADER_NAME)); zip.write(BackupJsonCodec.header(BackupTestFixtures.header(BackupScope.HISTORY))); zip.closeEntry()
            zip.putNextEntry(ZipEntry("../outside.json")); zip.write("{}".toByteArray()); zip.closeEntry()
        }
        expectBackupIssue(BackupIssue.PATH_ESCAPE) { BackupArchiveReader().verify(bad) {} }
        stage(directory()).use { staged ->
            staged.begin(BackupTestFixtures.header(BackupScope.HISTORY))
            staged.record(BackupTestFixtures.historyMeta())
            val first = BackupTestFixtures.trackTotal()
            staged.record(first)
            expectBackupIssue(BackupIssue.DUPLICATE_RECORD) {
                staged.record(BackupRecord(first.kind, "second-key-for-same-track", first.values))
            }
        }
        assertFalse(File(temporary.root.parentFile, "outside.json").exists())
    }

    @Test(timeout = 30_000L)
    fun resourceDigestFailureCannotSealAnArchiveWithCompletedRows() {
        val root = directory()
        stage(root).use { staged ->
            val hash = "e".repeat(64)
            val id = BackupLogicalSchema.resourceId(BackupResourceKind.LYRICS, hash)
            staged.begin(BackupTestFixtures.header(BackupScope.LYRICS, BackupScope.RESOURCES))
            staged.record(BackupTestFixtures.lyrics("fixture-track-a", hash, id))
            staged.record(BackupLogicalSchema.resourceRecord(BackupResource(id, BackupResourceKind.LYRICS, 4L, hash, false)))
            expectBackupIssue(BackupIssue.INTEGRITY_MISMATCH) { staged.writeResource(id).use { it.write(byteArrayOf(1, 2, 3, 4)) } }
            expectBackupIssue(BackupIssue.INCOMPLETE_ARCHIVE) { staged.sealSnapshot() }
        }
        assertFalse(root.child("export.zip").exists())
    }

    @Test(timeout = 30_000L)
    fun verifiedStageCanReopenWithANewCallerAfterThePreviewCallerHasCompleted() = runBlocking {
        val root = directory()
        val previewJob = Job()
        withContext(previewJob) {
            val context = currentCoroutineContext()
            SqliteBackupStage.create(root) { context.ensureActive() }.use {
                it.begin(BackupTestFixtures.header(BackupScope.HISTORY))
                it.record(BackupTestFixtures.historyMeta()); it.sealSnapshot()
            }
        }
        previewJob.complete()
        assertFalse(previewJob.isActive)
        val confirmationContext = currentCoroutineContext()
        SqliteBackupStage.openVerified(root) { confirmationContext.ensureActive() }.use {
            assertEquals(1L, it.count(BackupRecordKind.HISTORY_META))
            assertEquals(BackupTestFixtures.historyMeta().values, it.page(BackupRecordKind.HISTORY_META, null).single().values)
        }
    }

    @Test(timeout = 30_000L)
    fun unexpectedStageTriggerIsRejectedBeforeAnyScratchBindingIsWritten() {
        val root = directory()
        stage(root).use { it.begin(BackupTestFixtures.header(BackupScope.HISTORY)); it.record(BackupTestFixtures.historyMeta()); it.sealSnapshot() }
        BundledSQLiteDriver().open(root.child("rows.db").absolutePath).use { connection ->
            connection.execute("CREATE TRIGGER injected AFTER INSERT ON restore_bindings BEGIN DELETE FROM logical_records; END")
        }
        expectBackupIssue(BackupIssue.CORRUPT_ARCHIVE) { SqliteBackupStage.openVerified(root) {}.close() }
    }

    @Test(timeout = 30_000L)
    fun payloadKeyAndOrdinalSubstitutionCannotTrapOrRedirectDiskPagination() {
        val root = directory()
        stage(root).use { staged ->
            staged.begin(BackupTestFixtures.header(BackupScope.HISTORY))
            staged.record(BackupTestFixtures.historyMeta()); staged.record(BackupTestFixtures.historyEntry(1L)); staged.sealSnapshot()
        }
        val row = BackupTestFixtures.historyEntry(1L)
        BundledSQLiteDriver().open(root.child("rows.db").absolutePath).use { connection ->
            connection.prepare("UPDATE logical_records SET payload=? WHERE kind=? AND recordKey=?").use {
                it.bindBlob(1, BackupJsonCodec.record(BackupRecord(row.kind, "wrong-indexed-key", row.values)))
                it.bindLong(2, row.kind.ordinal.toLong()); it.bindText(3, row.key); it.step()
            }
        }
        expectBackupIssue(BackupIssue.INTEGRITY_MISMATCH) { SqliteBackupStage.openVerified(root) {}.close() }
        BundledSQLiteDriver().open(root.child("rows.db").absolutePath).use { connection ->
            connection.prepare("UPDATE logical_records SET payload=? WHERE kind=? AND recordKey=?").use {
                it.bindBlob(1, BackupJsonCodec.record(row)); it.bindLong(2, row.kind.ordinal.toLong()); it.bindText(3, row.key); it.step()
            }
            connection.execute("UPDATE logical_ordinals SET ordinal=999 WHERE family='history'")
        }
        expectBackupIssue(BackupIssue.INTEGRITY_MISMATCH) { SqliteBackupStage.openVerified(root) {}.close() }
    }

    @Test(timeout = 30_000L)
    fun disagreeingStatisticsSummariesCannotBecomeACompletedLogicalStage() {
        stage(directory()).use { staged ->
            staged.begin(BackupTestFixtures.header(BackupScope.HISTORY))
            staged.record(BackupTestFixtures.historyMeta(2L, 45_000L))
            staged.record(BackupTestFixtures.trackTotal(count = 3L))
            staged.record(BackupTestFixtures.dayTotal())
            expectBackupIssue(BackupIssue.INVALID_REFERENCE) { staged.sealSnapshot() }
        }
    }
}
