package io.github.onlinemo.meloxwatch.data.backup

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.onlinemo.meloxwatch.data.themes.ThemeCatalog
import io.github.onlinemo.meloxwatch.data.themes.ThemeStorageRecords
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BackupRestoreJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun root(): BackupPrivateDirectory = BackupPrivateDirectory.open(temporary.newFolder())
    private fun newRun(root: BackupPrivateDirectory, journal: BackupRestoreJournal, scopes: Set<BackupScope> = setOf(BackupScope.QUEUE),
        sha256: String = BackupTestFixtures.ARCHIVE_SHA256, mode: BackupMergeMode = BackupMergeMode.REPLACE_SELECTED): BackupRestoreRun {
        val incoming = root.newGeneration()
        val before = root.newGeneration()
        journal.registerGeneration(incoming.ownership()); journal.registerGeneration(before.ownership())
        return BackupRestoreRun(stableUuid("fixture-run", incoming.root.name), stableUuid("fixture-export", sha256), sha256,
            incoming.root.name, before.root.name, scopes, mode, "1".repeat(64)).also {
            journal.begin(it, BackupTestFixtures.preferences(), ThemeStorageRecords.encode(ThemeCatalog()))
        }
    }
    private fun confirmNoopConfigurations(journal: BackupRestoreJournal, run: BackupRestoreRun) {
        val versions = BackupTestFixtures.preferences()
        journal.configureTargets(run.id, versions.mapValues { it.value.record })
        for ((_, version) in versions) {
            journal.planConfig(run.id, BackupConfigDirection.FORWARD, version, version.record)
            journal.confirmConfig(run.id, version)
        }
    }
    private fun retireFinished(root: BackupPrivateDirectory, journal: BackupRestoreJournal, id: String) {
        val run = journal.readRun()!!
        for (generation in setOfNotNull(run.incomingGeneration, run.beforeGeneration, run.targetGeneration)) {
            root.openOwnedGeneration(journal.generationOwner(generation)!!)?.let(root::retireGeneration)
        }
        journal.clearFinished(id)
    }

    @Test(timeout = 30_000L)
    fun aPlannedNativeCasSurvivesReopenWithoutAnInventedAcknowledgment() {
        val root = root()
        var journal = BackupRestoreJournal.open(root)
        try {
            val run = newRun(root, journal)
            val versions = BackupTestFixtures.preferences()
            val before = versions.getValue("player")
            val target = BackupRecord(before.record.kind, before.record.key, before.record.values + ("restorePosition" to BackupCell.Flag(false)))
            journal.configureTargets(run.id, versions.mapValues { if (it.key == "player") target else it.value.record })
            journal.planConfig(run.id, BackupConfigDirection.FORWARD, before, target)
            journal.close()
            journal = BackupRestoreJournal.open(root)
            val step = journal.configs().single { it.key == "player" }
            assertNotNull(step.attemptBefore); assertNotNull(step.attemptTarget)
            assertNull(step.confirmedRevision)
            assertTrue(step.attemptTarget!!.sameContent(target))
            journal.confirmConfig(run.id, BackupConfigVersion(1L, target))
            journal.close()
            journal = BackupRestoreJournal.open(root)
            assertEquals(1L, journal.configs().single { it.key == "player" }.confirmedRevision)
        } finally { journal.close() }
    }

    @Test(timeout = 30_000L)
    fun everyForwardPhaseCanReopenAndRollbackWithoutDeletingItsRunEarly() {
        val root = root()
        var journal = BackupRestoreJournal.open(root)
        try {
            val run = newRun(root, journal)
            journal.close(); journal = BackupRestoreJournal.open(root)
            assertEquals(BackupRestorePhase.PREPARING, journal.readRun()!!.phase)
            confirmNoopConfigurations(journal, run)
            journal.close(); journal = BackupRestoreJournal.open(root)
            assertEquals(BackupRestorePhase.CONFIGURING, journal.readRun()!!.phase)
            val target = root.newGeneration(); journal.registerGeneration(target.ownership())
            journal.prepareMain(run.id, target.root.name, "2".repeat(64), mapOf("mainMissingReferences" to BackupCell.Integer(0),
                "resourceMissingReferences" to BackupCell.Integer(0)))
            journal.close(); journal = BackupRestoreJournal.open(root)
            assertEquals(BackupRestorePhase.MAIN_PREPARED, journal.readRun()!!.phase)
            journal.beginRollback(run.id)
            for (step in journal.configs()) {
                journal.planConfig(run.id, BackupConfigDirection.ROLLBACK, step.before, step.before.record)
                journal.confirmConfig(run.id, step.before)
            }
            journal.finishDirection(run.id, BackupConfigDirection.ROLLBACK, mapOf("themeCleanupRevision" to BackupCell.Integer(0),
                "themeCleanupAddedCount" to BackupCell.Integer(0)))
            journal.close(); journal = BackupRestoreJournal.open(root)
            assertEquals(BackupRestorePhase.FINISHING_ROLLBACK, journal.readRun()!!.phase)
            assertNotNull(journal.generationOwner(run.incomingGeneration))
            retireFinished(root, journal, run.id)
            journal.close(); journal = BackupRestoreJournal.open(root)
            assertNull(journal.readRun())
            assertTrue(journal.generationPage(null).isEmpty())
        } finally { journal.close() }
    }

    private fun finishHistory(root: BackupPrivateDirectory, journal: BackupRestoreJournal, sha: String,
        oldHistory: Long, newHistory: Long, oldStatistics: Long, newStatistics: Long,
        historyApplied: Boolean = true, statisticsApplied: Boolean = true) {
        val run = newRun(root, journal, setOf(BackupScope.HISTORY), sha, BackupMergeMode.MERGE_KEEP_CURRENT)
        confirmNoopConfigurations(journal, run)
        val target = root.newGeneration(); journal.registerGeneration(target.ownership())
        journal.prepareMain(run.id, target.root.name, "3".repeat(64), mapOf(
            "mainMissingReferences" to BackupCell.Integer(0), "resourceMissingReferences" to BackupCell.Integer(0),
            "beforeHistoryEpoch" to BackupCell.Integer(oldHistory), "historyEpoch" to BackupCell.Integer(newHistory),
            "beforeStatisticsEpoch" to BackupCell.Integer(oldStatistics), "statisticsEpoch" to BackupCell.Integer(newStatistics),
            "historyMergeApplied" to BackupCell.Flag(historyApplied), "statisticsMergeApplied" to BackupCell.Flag(statisticsApplied),
        ))
        journal.finishDirection(run.id, BackupConfigDirection.FORWARD, mapOf("themeCleanupRevision" to BackupCell.Integer(0),
            "themeCleanupAddedCount" to BackupCell.Integer(0)))
        journal.finishHistoryMerge(run.id)
        // A lost projection acknowledgment must be idempotent on the same committed generation.
        journal.finishHistoryMerge(run.id)
        retireFinished(root, journal, run.id)
    }

    @Test(timeout = 30_000L)
    fun independentHistoryAndStatisticsClearsDoNotReviveOrGrowObsoleteDomainReceipts() {
        val root = root()
        BackupRestoreJournal.open(root).use { journal ->
            val a = "a".repeat(64)
            val b = "b".repeat(64)
            finishHistory(root, journal, a, 0, 1, 0, 1)
            assertTrue(journal.historyDomainMerged(a, 1)); assertTrue(journal.statisticsDomainMerged(a, 1))
            // Clear history only: H1 -> H2, retaining S1. A's statistics must still deduplicate.
            finishHistory(root, journal, b, 2, 3, 1, 2)
            assertFalse(journal.historyDomainMerged(a, 3)); assertTrue(journal.statisticsDomainMerged(a, 2))
            finishHistory(root, journal, a, 3, 4, 2, 3, historyApplied = true, statisticsApplied = false)
            assertTrue(journal.historyDomainMerged(a, 4)); assertTrue(journal.statisticsDomainMerged(a, 3))
            assertTrue(journal.historyDomainMerged(b, 4)); assertTrue(journal.statisticsDomainMerged(b, 3))
            // Clear statistics only: S3 -> S4. H4 still retains both archives' closed history.
            finishHistory(root, journal, a, 4, 5, 4, 5, historyApplied = false, statisticsApplied = true)
            assertTrue(journal.historyDomainMerged(b, 5)); assertFalse(journal.statisticsDomainMerged(b, 5))
        }
        BundledSQLiteDriver().open(root.child("restore.db").absolutePath).use { connection ->
            fun count(table: String) = connection.prepare("SELECT COUNT(*) FROM $table").use { check(it.step()); it.getLong(0) }
            assertEquals(2L, count("history_merge_receipts"))
            assertEquals(1L, count("statistics_merge_receipts"))
        }
    }

    @Test(timeout = 30_000L)
    fun missingSchemaAndOrphanedStepsAreNeverRecreatedAsAnEmptyHealthyJournal() {
        val root = root()
        BackupRestoreJournal.open(root).close()
        BundledSQLiteDriver().open(root.child("restore.db").absolutePath).use { it.execute("DROP TABLE config_steps") }
        expectBackupIssue(BackupIssue.RECOVERY_REQUIRED) { BackupRestoreJournal.open(root).close() }
        BundledSQLiteDriver().open(root.child("restore.db").absolutePath).use { connection ->
            connection.prepare("SELECT COUNT(*) FROM sqlite_schema WHERE name='config_steps'").use { check(it.step()); assertEquals(0L, it.getLong(0)) }
        }
        val orphanRoot = root()
        BackupRestoreJournal.open(orphanRoot).close()
        BundledSQLiteDriver().open(orphanRoot.child("restore.db").absolutePath).use { connection ->
            connection.prepare("INSERT INTO config_steps(configKey,beforeRevision,beforePayload) VALUES('player',0,?)").use {
                it.bindBlob(1, BackupJsonCodec.record(BackupTestFixtures.preferences().getValue("player").record)); it.step()
            }
        }
        expectBackupIssue(BackupIssue.RECOVERY_REQUIRED) { BackupRestoreJournal.open(orphanRoot).close() }
    }

    @Test(timeout = 30_000L)
    fun inactiveJournalStillValidatesEveryOwnershipAndRelinkRowBeforeReady() {
        val root = root()
        BackupRestoreJournal.open(root).close()
        BundledSQLiteDriver().open(root.child("restore.db").absolutePath).use { connection ->
            connection.prepare("INSERT INTO generation_owners(generationId,device,inode) VALUES(?,0,0)").use {
                it.bindText(1, "z".repeat(36)); it.step()
            }
        }
        expectBackupIssue(BackupIssue.RECOVERY_REQUIRED) { BackupRestoreJournal.open(root).close() }
        val relinkRoot = root()
        BackupRestoreJournal.open(relinkRoot).close()
        val payload = BackupRecord(BackupRecordKind.LOCATION_HINT, "fixture-location", mapOf(
            "sizeBytes" to BackupCell.Integer(128), "modifiedEpochMs" to BackupCell.Null,
            "contentSha256" to BackupCell.Null, "providerContentTokenHint" to BackupCell.Null,
        ))
        BundledSQLiteDriver().open(relinkRoot.child("restore.db").absolutePath).use { connection ->
            connection.prepare("INSERT INTO relink_hints(hintId,trackId,locationId,archiveSha256,payload) VALUES(?,?,?,?,?)").use {
                it.bindText(1, stableUuid("wrong-hint")); it.bindText(2, "fixture-track-a"); it.bindText(3, "wrong-owned-location")
                it.bindText(4, "c".repeat(64)); it.bindBlob(5, BackupJsonCodec.record(payload)); it.step()
            }
        }
        expectBackupIssue(BackupIssue.RECOVERY_REQUIRED) { BackupRestoreJournal.open(relinkRoot).close() }
    }

    @Test(timeout = 30_000L)
    fun aReplacementDirectoryWithTheSameUuidCannotBeRetiredByTheOldOwner() {
        val root = root()
        val original = root.newGeneration()
        val ownership = original.ownership()
        root.retireGeneration(original)
        Files.createDirectory(root.child(ownership.id).toPath())
        expectBackupIssue(BackupIssue.INTEGRITY_MISMATCH) { root.openOwnedGeneration(ownership) }
        assertTrue(root.child(ownership.id).isDirectory)
    }
}
