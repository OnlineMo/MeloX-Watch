package io.github.onlinemo.meloxwatch.data.backup

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.onlinemo.meloxwatch.data.themes.ThemeCatalog
import io.github.onlinemo.meloxwatch.data.themes.ThemeStorageRecords
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
class MetadataBackupJournalMigrationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test(timeout = 30_000L)
    fun exactLegacyJournalMigratesWithAllOwnerOrdinalsPayloadsReceiptsAndRunPreserved() {
        val root = root()
        val payloads = legacy(root, pending = true)
        assertEquals(listOf("FONT", "BACKGROUND", "THEME", "LYRICS"), BackupResourceOwner.entries.take(4).map { it.name })
        BackupRestoreJournal.open(root).use { journal ->
            assertEquals(BackupRestorePhase.PREPARING, journal.readRun()!!.phase)
            assertEquals(stableUuid("legacy-run"), journal.readRun()!!.id)
            assertEquals(7, journal.configs().size)
            for ((ordinal, expected) in payloads.withIndex()) {
                val owner = BackupResourceOwner.entries[ordinal]
                val plans = journal.resourcePage(owner, null)
                assertEquals(1, plans.size)
                assertTrue(plans.single().confirmed)
                assertArrayEquals(expected, BackupJsonCodec.encodeValues(plans.single().metadata))
            }
            assertTrue(journal.historyDomainMerged("a".repeat(64), 4))
            assertTrue(journal.statisticsDomainMerged("b".repeat(64), 9))
            val id = BackupLogicalSchema.resourceId(BackupResourceKind.IMAGE, "5".repeat(64))
            journal.planResource(stableUuid("legacy-run"), BackupResourcePlan(BackupResourceOwner.METADATA_ARTWORK, id, id,
                mapOf("id" to BackupCell.Text(stableUuid("new-metadata-asset")), "sha256" to BackupCell.Text("5".repeat(64)),
                    "bytes" to BackupCell.Integer(128), "added" to BackupCell.Flag(true))))
            journal.confirmResource(stableUuid("legacy-run"), BackupResourceOwner.METADATA_ARTWORK, id)
        }
        BundledSQLiteDriver().open(root.child("restore.db").absolutePath).use { connection ->
            connection.prepare("PRAGMA user_version").use { assertTrue(it.step()); assertEquals(2L, it.getLong(0)) }
            connection.prepare("SELECT metadata FROM resource_plans WHERE owner=?").use { statement ->
                for ((ordinal, expected) in payloads.withIndex()) {
                    statement.reset(); statement.bindLong(1, ordinal.toLong()); assertTrue(statement.step())
                    assertArrayEquals(expected, statement.getBlob(0))
                }
            }
            connection.prepare("SELECT COUNT(*) FROM sqlite_schema WHERE name='legacy_resource_plans'").use {
                assertTrue(it.step()); assertEquals(0L, it.getLong(0))
            }
        }
        BackupRestoreJournal.open(root).use { assertEquals(1, it.resourcePage(BackupResourceOwner.METADATA_ARTWORK, null).size) }
    }

    @Test(timeout = 30_000L)
    fun completedLegacyReceiptsMigrateWithoutCreatingAPendingRun() {
        val root = root()
        legacy(root, pending = false)
        BackupRestoreJournal.open(root).use {
            assertNull(it.readRun())
            assertTrue(it.historyDomainMerged("a".repeat(64), 4))
            assertTrue(it.statisticsDomainMerged("b".repeat(64), 9))
            assertTrue(it.resourcePage(BackupResourceOwner.METADATA_ARTWORK, null).isEmpty())
        }
    }

    @Test(timeout = 30_000L)
    fun damagedOrForeignLegacySchemaIsRejectedBeforeAnyMigrationOrTableRepair() {
        for (mutation in listOf("DROP TABLE config_steps", "CREATE TABLE foreign_extension(value TEXT)",
                "ALTER TABLE resource_plans ADD COLUMN guessedState TEXT")) {
            val root = root()
            legacy(root, pending = false)
            BundledSQLiteDriver().open(root.child("restore.db").absolutePath).use { it.execute(mutation) }
            assertEquals(BackupIssue.RECOVERY_REQUIRED,
                assertThrows(BackupException::class.java) { BackupRestoreJournal.open(root).close() }.issue)
            BundledSQLiteDriver().open(root.child("restore.db").absolutePath).use { connection ->
                connection.prepare("PRAGMA user_version").use { assertTrue(it.step()); assertEquals(1L, it.getLong(0)) }
                if (mutation.startsWith("DROP")) connection.prepare("SELECT COUNT(*) FROM sqlite_schema WHERE name='config_steps'").use {
                    assertTrue(it.step()); assertEquals(0L, it.getLong(0))
                }
            }
        }
    }

    @Test(timeout = 30_000L)
    fun invalidReceiptAndUnknownJournalVersionNeverBecomeFreshHealthyState() {
        val corrupt = root()
        legacy(corrupt, pending = false)
        BundledSQLiteDriver().open(corrupt.child("restore.db").absolutePath).use { connection ->
            connection.prepare("UPDATE history_merge_receipts SET archiveSha256=?").use { it.bindText(1, "z".repeat(64)); it.step() }
        }
        assertEquals(BackupIssue.RECOVERY_REQUIRED,
            assertThrows(BackupException::class.java) { BackupRestoreJournal.open(corrupt).close() }.issue)
        val foreign = root()
        legacy(foreign, pending = false)
        BundledSQLiteDriver().open(foreign.child("restore.db").absolutePath).use { it.execute("PRAGMA user_version=3") }
        assertEquals(BackupIssue.RECOVERY_REQUIRED,
            assertThrows(BackupException::class.java) { BackupRestoreJournal.open(foreign).close() }.issue)
        BundledSQLiteDriver().open(foreign.child("restore.db").absolutePath).use { connection ->
            connection.prepare("PRAGMA user_version").use { assertTrue(it.step()); assertEquals(3L, it.getLong(0)) }
        }
    }

    private fun root(): BackupPrivateDirectory = BackupPrivateDirectory.open(temporary.newFolder())

    /** Frozen private v1 schema and real SQLite rows exercise migration without deriving the fixture from v2. */
    private fun legacy(root: BackupPrivateDirectory, pending: Boolean): List<ByteArray> {
        val payloads = legacyPayloads().map { BackupJsonCodec.encodeValues(it) }
        val incoming = root.newGeneration().ownership()
        val before = root.newGeneration().ownership()
        BundledSQLiteDriver().open(root.child("restore.db").absolutePath).use { connection ->
            for (sql in LEGACY_SCHEMA) connection.execute(sql)
            connection.execute("PRAGMA user_version=1")
            connection.execute("INSERT INTO history_merge_receipts(archiveSha256,epoch) VALUES('" + "a".repeat(64) + "',4)")
            connection.execute("INSERT INTO statistics_merge_receipts(archiveSha256,epoch) VALUES('" + "b".repeat(64) + "',9)")
            if (pending) {
                for (generation in listOf(incoming, before)) connection.prepare(
                    "INSERT INTO generation_owners(generationId,device,inode) VALUES(?,?,?)").use {
                    it.bindText(1, generation.id); it.bindLong(2, generation.device); it.bindLong(3, generation.inode); it.step()
                }
                connection.prepare("INSERT INTO restore_run(id,runId,exportId,archiveSha256,incomingGeneration,beforeGeneration,scopes,mode,beforeMainDigest,phase,targetGeneration,targetMainDigest,nativeBindings,themeBaseline) VALUES(1,?,?,?,?,?,?,1,?,0,NULL,NULL,?,?)").use {
                    it.bindText(1, stableUuid("legacy-run")); it.bindText(2, stableUuid("legacy-export")); it.bindText(3, "c".repeat(64))
                    it.bindText(4, incoming.id); it.bindText(5, before.id)
                    it.bindBlob(6, byteArrayOf(BackupScope.PREFERENCES.ordinal.toByte(), BackupScope.RESOURCES.ordinal.toByte()))
                    it.bindText(7, "d".repeat(64)); it.bindBlob(8, BackupJsonCodec.encodeValues(emptyMap()))
                    it.bindBlob(9, ThemeStorageRecords.encode(ThemeCatalog())); it.step()
                }
                for ((key, value) in BackupTestFixtures.preferences()) connection.prepare(
                    "INSERT INTO config_steps(configKey,beforeRevision,beforePayload) VALUES(?,0,?)").use {
                    it.bindText(1, key); it.bindBlob(2, BackupJsonCodec.record(value.record)); it.step()
                }
                for ((ordinal, payload) in payloads.withIndex()) connection.prepare(
                    "INSERT INTO resource_plans(owner,slot,resourceId,metadata,confirmed) VALUES(?,?,?,?,1)").use {
                    val kind = listOf(BackupResourceKind.FONT, BackupResourceKind.IMAGE, BackupResourceKind.THEME_PACKAGE, BackupResourceKind.LYRICS)[ordinal]
                    val id = BackupLogicalSchema.resourceId(kind, (ordinal + 1).toString().repeat(64))
                    it.bindLong(1, ordinal.toLong()); it.bindText(2, if (ordinal == 3) id else stableUuid("legacy-slot", ordinal.toString()))
                    it.bindText(3, id); it.bindBlob(4, payload); it.step()
                }
            }
        }
        return payloads
    }

    private fun legacyPayloads(): List<Map<String, BackupCell>> {
        val uri = "content://fixture.owned-lyrics/" + stableUuid("legacy-lyrics-asset")
        return listOf(
            mapOf("id" to BackupCell.Text(stableUuid("legacy-font")), "sha256" to BackupCell.Text("1".repeat(64)),
                "bytes" to BackupCell.Integer(128), "displayName" to BackupCell.Text("Fixture font"), "license" to BackupCell.Integer(1)),
            mapOf("id" to BackupCell.Text(stableUuid("legacy-background")), "sha256" to BackupCell.Text("2".repeat(64)),
                "bytes" to BackupCell.Integer(128), "mediaType" to BackupCell.Text("image/png"), "width" to BackupCell.Integer(16),
                "height" to BackupCell.Integer(16), "orientation" to BackupCell.Integer(1), "previewToken" to BackupCell.Text(stableUuid("legacy-image-preview"))),
            mapOf("id" to BackupCell.Text(stableUuid("legacy-theme")), "themeId" to BackupCell.Text("fixture-theme"),
                "manifestSha256" to BackupCell.Text("6".repeat(64)), "packageSha256" to BackupCell.Text("3".repeat(64)),
                "resourceSha256" to BackupCell.Text("3".repeat(64)), "displayName" to BackupCell.Text("Fixture theme"),
                "resourceBytes" to BackupCell.Integer(128), "fontBytes" to BackupCell.Integer(0), "added" to BackupCell.Flag(false)),
            mapOf("uri" to BackupCell.Text(uri), "documentId" to BackupCell.Text("uri:$uri"),
                "sourceId" to BackupCell.Text("lyrics:" + stableUuid("legacy-lyrics-source")), "sha256" to BackupCell.Text("4".repeat(64)),
                "bytes" to BackupCell.Integer(128), "added" to BackupCell.Flag(true)),
        )
    }

    companion object {
        private val LEGACY_SCHEMA = listOf(
            "CREATE TABLE generation_owners(generationId TEXT NOT NULL PRIMARY KEY CHECK(length(generationId)=36),device INTEGER NOT NULL CHECK(device>=0),inode INTEGER NOT NULL CHECK(inode>=0)) WITHOUT ROWID, STRICT",
            "CREATE TABLE restore_run(id INTEGER NOT NULL PRIMARY KEY CHECK(id=1),runId TEXT NOT NULL,exportId TEXT NOT NULL,archiveSha256 TEXT NOT NULL,incomingGeneration TEXT NOT NULL,beforeGeneration TEXT NOT NULL,scopes BLOB NOT NULL CHECK(length(scopes) BETWEEN 1 AND 8),mode INTEGER NOT NULL CHECK(mode IN (0,1)),beforeMainDigest TEXT NOT NULL,phase INTEGER NOT NULL CHECK(phase BETWEEN 0 AND 5),targetGeneration TEXT,targetMainDigest TEXT,nativeBindings BLOB NOT NULL CHECK(length(nativeBindings)<=1048576),themeBaseline BLOB NOT NULL CHECK(length(themeBaseline)<=65536),CHECK((targetGeneration IS NULL)=(targetMainDigest IS NULL))) STRICT",
            "CREATE TABLE config_steps(configKey TEXT NOT NULL PRIMARY KEY,beforeRevision INTEGER NOT NULL CHECK(beforeRevision>=0),beforePayload BLOB NOT NULL CHECK(length(beforePayload)<=1048576),forwardPayload BLOB CHECK(length(forwardPayload)<=1048576),direction INTEGER CHECK(direction IN (0,1)),attemptRevision INTEGER CHECK(attemptRevision>=0),attemptBefore BLOB CHECK(length(attemptBefore)<=1048576),attemptTarget BLOB CHECK(length(attemptTarget)<=1048576),confirmedRevision INTEGER CHECK(confirmedRevision>=0)) WITHOUT ROWID, STRICT",
            "CREATE TABLE resource_plans(owner INTEGER NOT NULL CHECK(owner BETWEEN 0 AND 3),slot TEXT NOT NULL CHECK(length(slot) BETWEEN 1 AND 128),resourceId TEXT NOT NULL CHECK(length(resourceId)=64),metadata BLOB NOT NULL CHECK(length(metadata)<=32768),confirmed INTEGER NOT NULL CHECK(confirmed IN (0,1)),PRIMARY KEY(owner,slot)) WITHOUT ROWID, STRICT",
            "CREATE TABLE history_merge_receipts(archiveSha256 TEXT NOT NULL CHECK(length(archiveSha256)=64),epoch INTEGER NOT NULL CHECK(epoch>=0),PRIMARY KEY(archiveSha256,epoch)) WITHOUT ROWID, STRICT",
            "CREATE TABLE statistics_merge_receipts(archiveSha256 TEXT NOT NULL CHECK(length(archiveSha256)=64),epoch INTEGER NOT NULL CHECK(epoch>=0),PRIMARY KEY(archiveSha256,epoch)) WITHOUT ROWID, STRICT",
            "CREATE TABLE pending_relinks(hintId TEXT NOT NULL PRIMARY KEY CHECK(length(hintId)=36),trackId TEXT NOT NULL CHECK(length(trackId) BETWEEN 1 AND 128),locationId TEXT NOT NULL CHECK(length(locationId) BETWEEN 1 AND 128),archiveSha256 TEXT NOT NULL CHECK(length(archiveSha256)=64),payload BLOB NOT NULL CHECK(length(payload)<=1048576)) WITHOUT ROWID, STRICT",
            "CREATE TABLE relink_hints(hintId TEXT NOT NULL PRIMARY KEY CHECK(length(hintId)=36),trackId TEXT NOT NULL CHECK(length(trackId) BETWEEN 1 AND 128),locationId TEXT NOT NULL CHECK(length(locationId) BETWEEN 1 AND 128),archiveSha256 TEXT NOT NULL CHECK(length(archiveSha256)=64),payload BLOB NOT NULL CHECK(length(payload)<=1048576)) WITHOUT ROWID, STRICT",
        )
    }
}
