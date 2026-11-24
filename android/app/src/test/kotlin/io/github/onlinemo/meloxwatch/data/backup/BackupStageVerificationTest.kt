package io.github.onlinemo.meloxwatch.data.backup

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistLimits
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Corrupt private projections must be rejected before any restore can use a partial inner join. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BackupStageVerificationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val lyricsBytes = "[00:01.00]Fixture line\n".toByteArray()
    private val lyricsHash = backupSha256(lyricsBytes)
    private val lyricsResource = BackupResource(BackupLogicalSchema.resourceId(BackupResourceKind.LYRICS, lyricsHash),
        BackupResourceKind.LYRICS, lyricsBytes.size.toLong(), lyricsHash, false)

    private fun populatedStage(): BackupPrivateDirectory {
        val root = BackupPrivateDirectory.open(temporary.newFolder())
        SqliteBackupStage.create(root) {}.use { stage ->
            stage.begin(BackupTestFixtures.header(BackupScope.LIBRARY, BackupScope.FAVORITES, BackupScope.PLAYLISTS,
                BackupScope.QUEUE, BackupScope.HISTORY, BackupScope.LYRICS, BackupScope.RESOURCES))
            stage.record(BackupRecord(BackupRecordKind.LIBRARY_META, "library", mapOf("shortAudioFilter" to BackupCell.Flag(false))))
            stage.record(BackupTestFixtures.track("fixture-track-a", 1L))
            stage.record(BackupTestFixtures.track("fixture-track-b", 2L))
            stage.record(BackupRecord(BackupRecordKind.SOURCE_HINT, "fixture-source", mapOf(
                "kind" to BackupCell.Text(LocalSourceKind.entries.first().name), "uriHint" to BackupCell.Null,
                "mediaVolume" to BackupCell.Text("fixture-volume"), "authorityHint" to BackupCell.Null,
                "rootObjectIdHint" to BackupCell.Null, "state" to BackupCell.Text("NEEDS_AUTHORIZATION"), "ownedTokenHint" to BackupCell.Null)))
            for ((id, parent, directory) in listOf(Triple("fixture-root", null, true), Triple("fixture-leaf", "fixture-root", false))) {
                stage.record(BackupRecord(BackupRecordKind.NODE_HINT, id, mapOf("sourceId" to BackupCell.Text("fixture-source"),
                    "objectIdHint" to BackupCell.Null, "parentNodeId" to (parent?.let(BackupCell::Text) ?: BackupCell.Null),
                    "name" to BackupCell.Text(id), "uriHint" to BackupCell.Null, "directory" to BackupCell.Flag(directory), "pathName" to BackupCell.Null)))
            }
            stage.record(BackupRecord(BackupRecordKind.LOCATION_HINT, "fixture-location", mapOf(
                "sizeBytes" to BackupCell.Integer(1024L), "modifiedEpochMs" to BackupCell.Null,
                "contentSha256" to BackupCell.Text("4".repeat(64)), "providerContentTokenHint" to BackupCell.Null)))
            stage.record(BackupRecord(BackupRecordKind.TRACK_LOCATION, "fixture-relation", mapOf(
                "trackId" to BackupCell.Text("fixture-track-a"), "locationId" to BackupCell.Text("fixture-location"))))
            stage.record(BackupRecord(BackupRecordKind.ALIAS_HINT, "fixture-alias", mapOf(
                "sourceId" to BackupCell.Text("fixture-source"), "objectIdHint" to BackupCell.Null,
                "locationId" to BackupCell.Text("fixture-location"), "nodeId" to BackupCell.Text("fixture-leaf"),
                "uriHint" to BackupCell.Null, "filename" to BackupCell.Text("fixture.mp3"))))
            stage.record(BackupTestFixtures.playlistHead("fixture-playlist", 2L))
            stage.record(BackupTestFixtures.playlistMember("fixture-playlist", 0L, "fixture-track-a"))
            stage.record(BackupTestFixtures.playlistMember("fixture-playlist", 1L, "fixture-track-b"))
            stage.record(BackupTestFixtures.playlistHead(PlaylistLimits.FAVORITES_ID, 1L, favorites = true))
            stage.record(BackupTestFixtures.playlistMember(PlaylistLimits.FAVORITES_ID, 0L, "fixture-track-a", favorites = true))
            stage.record(BackupTestFixtures.queueMeta(2L))
            stage.record(BackupTestFixtures.queueEntry(0L, 2L, "fixture-track-a"))
            stage.record(BackupTestFixtures.queueEntry(1L, 2L, "fixture-track-b"))
            stage.record(BackupTestFixtures.historyMeta())
            stage.record(BackupTestFixtures.historyEntry(1L))
            stage.record(BackupTestFixtures.historyEntry(2L, "fixture-track-b"))
            stage.record(BackupTestFixtures.trackTotal(count = 0L, ms = 0L))
            stage.record(BackupTestFixtures.dayTotal(count = 0L, ms = 0L))
            stage.record(BackupTestFixtures.lyrics("fixture-track-a", lyricsHash, lyricsResource.id))
            stage.record(BackupLogicalSchema.resourceRecord(lyricsResource))
            stage.writeResource(lyricsResource.id).use { it.write(lyricsBytes) }
            stage.sealSnapshot()
            BackupArchiveWriter().write(stage, root.child("export.zip")) {}
        }
        return root
    }

    private fun logicalRowsFingerprint(root: BackupPrivateDirectory): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BundledSQLiteDriver().open(root.child("rows.db").absolutePath).use { connection ->
            connection.prepare("SELECT kind,recordKey,scope,bytes,payload FROM logical_records ORDER BY kind,recordKey").use {
                while (it.step()) {
                    digest.update(ByteBuffer.allocate(24).putLong(it.getLong(0)).putLong(it.getLong(2)).putLong(it.getLong(3)).array())
                    val key = it.getText(1).toByteArray()
                    val payload = it.getBlob(4)
                    digest.update(ByteBuffer.allocate(8).putInt(key.size).putInt(payload.size).array())
                    digest.update(key); digest.update(payload)
                }
            }
        }
        return backupHex(digest.digest())
    }

    private fun changedProjection(sql: String, logicalRowsUnchanged: Boolean = true): BackupPrivateDirectory {
        val root = populatedStage()
        val archiveHash = backupFileSha256(root.child("export.zip")) {}
        val originalRows = logicalRowsFingerprint(root)
        SqliteBackupStage.openVerified(root) {}.close()
        BundledSQLiteDriver().open(root.child("rows.db").absolutePath).use { connection ->
            connection.execute(sql)
            assertEquals("ok", connection.prepare("PRAGMA quick_check(1)").use { it.step(); it.getText(0) })
        }
        assertEquals(archiveHash, backupFileSha256(root.child("export.zip")) {})
        if (logicalRowsUnchanged) assertEquals(originalRows, logicalRowsFingerprint(root))
        return root
    }

    @Test(timeout = 60_000L)
    fun normalSealedStageReopensWithEveryOrderAndResourceComplete() {
        val root = populatedStage()
        SqliteBackupStage.openVerified(root) {}.use { stage ->
            assertEquals(2, stage.orderedPage(BackupRecordKind.PLAYLIST_MEMBER, "playlist", "fixture-playlist", null).size)
            assertEquals(2, stage.orderedPage(BackupRecordKind.QUEUE_ENTRY, "queue-original", "queue", null).size)
            assertEquals(2, stage.orderedPage(BackupRecordKind.QUEUE_ENTRY, "queue-playback", "queue", null).size)
            assertEquals(2, stage.orderedPage(BackupRecordKind.TRACK, "track-added", "library", null).size)
            assertEquals(2, stage.orderedPage(BackupRecordKind.HISTORY_ENTRY, "history", "history", null).size)
            assertEquals(lyricsResource.sha256, stage.resource(lyricsResource.id).sha256)
        }
    }

    @Test(timeout = 120_000L)
    fun missingExtraOrSubstitutedProjectionRowsCannotPassSealedReopen() {
        val mutations = listOf(
            "DELETE FROM logical_ordinals WHERE family='track-added' AND ordinal=2",
            "DELETE FROM logical_ordinals WHERE family='playlist' AND owner='fixture-playlist' AND ordinal=1",
            "DELETE FROM logical_ordinals WHERE family='playlist' AND owner='${PlaylistLimits.FAVORITES_ID}' AND ordinal=0",
            "DELETE FROM logical_ordinals WHERE family='history' AND ordinal=2",
            "DELETE FROM logical_ordinals WHERE family='queue-original' AND ordinal=1",
            "DELETE FROM logical_ordinals WHERE family='queue-playback' AND ordinal=1",
            "UPDATE logical_ordinals SET owner='wrong-owner' WHERE family='playlist' AND owner='fixture-playlist' AND ordinal=1",
            "UPDATE logical_ordinals SET recordKey='absent-record' WHERE family='queue-playback' AND ordinal=0",
            "INSERT INTO logical_ordinals(family,owner,ordinal,recordKey) VALUES('extra-family','queue',0,'absent-record')",
            "DELETE FROM logical_edges WHERE fromKind=${BackupRecordKind.TRACK_LOCATION.ordinal} AND field='trackId'",
            "UPDATE logical_identities SET identity='wrong-identity' WHERE family='track-location'",
            "UPDATE node_parents SET parent='fixture-leaf' WHERE child='fixture-root'",
            "DELETE FROM resource_usage",
            "UPDATE resources SET bytes=bytes+1",
        )
        for (sql in mutations) {
            val root = changedProjection(sql)
            expectBackupIssue(BackupIssue.INTEGRITY_MISMATCH) { SqliteBackupStage.openVerified(root) {}.close() }
        }
    }

    @Test(timeout = 120_000L)
    fun realSQLiteColumnTypesSealedFlagsAndAdvertisedBytesAreRechecked() {
        val mutations = listOf(
            "UPDATE logical_records SET bytes=bytes+0.5 WHERE kind=${BackupRecordKind.HISTORY_ENTRY.ordinal}",
            "UPDATE logical_records SET scope=CAST(scope AS BLOB) WHERE kind=${BackupRecordKind.PLAYLIST_MEMBER.ordinal}",
            "UPDATE logical_records SET kind=CAST(kind AS BLOB) WHERE kind=${BackupRecordKind.QUEUE_ENTRY.ordinal}",
            "UPDATE logical_records SET payload=CAST(payload AS TEXT) WHERE kind=${BackupRecordKind.LYRICS_TRACK.ordinal}",
            "UPDATE logical_records SET recordKey=CAST(recordKey AS BLOB) WHERE kind=${BackupRecordKind.TRACK.ordinal}",
            "UPDATE stage_meta SET logicalBytes=logicalBytes+0.5",
            "UPDATE stage_meta SET logicalBytes=logicalBytes+1",
        )
        for (sql in mutations) {
            val root = changedProjection(sql, logicalRowsUnchanged = false)
            expectBackupIssue(BackupIssue.INTEGRITY_MISMATCH) { SqliteBackupStage.openVerified(root) {}.close() }
        }
        val unsealed = changedProjection("UPDATE resources SET sealed=0")
        expectBackupIssue(BackupIssue.INCOMPLETE_ARCHIVE) { SqliteBackupStage.openVerified(unsealed) {}.close() }
    }
}
