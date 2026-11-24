package io.github.onlinemo.meloxwatch.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.onlinemo.meloxwatch.data.database.withTestReaderSql
import io.github.onlinemo.meloxwatch.data.database.withTestWriterTransaction
import io.github.onlinemo.meloxwatch.data.themes.ThemeStorageRecords
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class BackupRoomRestoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var fixture: NativeBackupFixture
    @Before fun open() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        fixture = NativeBackupFixture(RuntimeEnvironment.getApplication() as Context, temporary.newFolder("app"))
        check(fixture.coordinator.initializeStorage() is BackupStartupState.Ready)
    }
    @After fun close() {
        try { runBlocking { fixture.close() } } finally { Dispatchers.resetMain() }
    }
    private fun input() = BackupDocumentInput(Uri.parse("content://fixture.documents/backup"), Intent.FLAG_GRANT_READ_URI_PERMISSION)
    private fun directory(name: String): BackupPrivateDirectory = BackupPrivateDirectory.open(temporary.newFolder(name))
    private fun archive(root: BackupPrivateDirectory, write: (SqliteBackupStage) -> Unit): File = SqliteBackupStage.create(root) {}.use { stage ->
        write(stage); stage.sealSnapshot()
        root.child("export.zip").also { BackupArchiveWriter().write(stage, it) {} }
    }

    @Test(timeout = 60_000L)
    fun realRoomRestoresCompletePlaylistQueueAndMissingStableIdentitiesThenMergeKeepsUserEdits() = runBlocking {
        val tracks = 257L
        val queueCount = 250L
        val root = directory("source")
        fixture.archiveInput = archive(root) { stage ->
            stage.begin(BackupTestFixtures.header(BackupScope.LIBRARY, BackupScope.FAVORITES, BackupScope.PLAYLISTS, BackupScope.QUEUE))
            stage.record(BackupRecord(BackupRecordKind.LIBRARY_META, "library", mapOf("shortAudioFilter" to BackupCell.Flag(false))))
            for (ordinal in 0 until tracks) stage.record(BackupTestFixtures.track("fixture-track-$ordinal", ordinal + 1L))
            stage.record(BackupTestFixtures.playlistHead("system:favorites", 2L, favorites = true))
            stage.record(BackupTestFixtures.playlistMember("system:favorites", 0L, "fixture-track-0", favorites = true))
            stage.record(BackupTestFixtures.playlistMember("system:favorites", 1L, "fixture-track-1", favorites = true))
            stage.record(BackupTestFixtures.playlistHead("fixture-playlist", tracks))
            for (ordinal in 0 until tracks) stage.record(BackupTestFixtures.playlistMember("fixture-playlist", ordinal, "fixture-track-$ordinal"))
            stage.record(BackupTestFixtures.queueMeta(queueCount, 123L))
            for (ordinal in 0 until queueCount) stage.record(BackupTestFixtures.queueEntry(ordinal, queueCount, "fixture-track-$ordinal"))
        }
        fixture.attachClosedPlayback()
        val preview = fixture.coordinator.previewRestore(input(), BackupMergeMode.REPLACE_SELECTED)
        assertEquals(tracks, preview.impacts.single { it.scope == BackupScope.LIBRARY }.missingReferences)
        val receipt = fixture.coordinator.confirmRestore(preview.token)
        assertEquals(tracks + queueCount, receipt.missingReferences)
        val head = fixture.database.queueDao().head()!!
        val queueId = head.activeGenerationId!!
        assertEquals(BackupTestFixtures.queueId(123L), head.currentEntryId)
        assertEquals(1500L, head.positionMs); assertEquals(3, head.completedCycles)
        fixture.database.withTestReaderSql {
            assertEquals(queueCount, scalarLong("SELECT COUNT(*) FROM queue_entries WHERE generationId=?", queueId))
            assertEquals(tracks, scalarLong("SELECT g.memberCount FROM playlist_heads h JOIN playlist_generations g ON g.generationId=h.activeGenerationId WHERE h.playlistId='fixture-playlist'"))
            assertEquals("fixture-track-256", scalarText("SELECT m.trackId FROM playlist_heads h JOIN playlist_generations g ON g.generationId=h.activeGenerationId JOIN playlist_members m ON m.generationId=g.dataGenerationId WHERE h.playlistId='fixture-playlist' AND m.ordinal=256"))
            assertEquals(2L, scalarLong("SELECT g.memberCount FROM playlist_heads h JOIN playlist_generations g ON g.generationId=h.activeGenerationId WHERE h.playlistId='system:favorites'"))
        }
        var count = 0L
        var after: String? = null
        do {
            val page = fixture.coordinator.missingRelinks(after, 100)
            count += page.items.size
            after = page.next
        } while (after != null)
        assertEquals(tracks, count)
        fixture.database.withTestWriterTransaction {
            execute("UPDATE local_track_versions SET title='Fixture user edit',displayTitle='Fixture user edit' WHERE trackId='fixture-track-0' AND validTo IS NULL")
        }
        fixture.attachClosedPlayback()
        val second = fixture.coordinator.previewRestore(input(), BackupMergeMode.MERGE_KEEP_CURRENT)
        fixture.coordinator.confirmRestore(second.token)
        assertEquals("Fixture user edit", fixture.database.libraryDao().currentTrack("fixture-track-0")!!.displayTitle)
        assertEquals(queueId, fixture.database.queueDao().head()!!.activeGenerationId)
        fixture.database.withTestReaderSql {
            assertEquals(tracks, scalarLong("SELECT g.memberCount FROM playlist_heads h JOIN playlist_generations g ON g.generationId=h.activeGenerationId WHERE h.playlistId='fixture-playlist'"))
        }
    }

    @Test(timeout = 30_000L)
    fun cancellationAfterDurableProspectiveDigestRollsBackRealMainRowsAndRetainsRecoveryProof() = runBlocking {
        val root = directory("journal")
        val incoming = root.newGeneration()
        val before = root.newGeneration()
        val target = root.newGeneration()
        val caller = currentCoroutineContext()
        SqliteBackupStage.create(incoming) { caller.ensureActive() }.use { stage ->
            stage.begin(BackupTestFixtures.header(BackupScope.LIBRARY))
            stage.record(BackupRecord(BackupRecordKind.LIBRARY_META, "library", mapOf("shortAudioFilter" to BackupCell.Flag(false))))
            stage.record(BackupTestFixtures.track("fixture-new-track", 1L)); stage.sealSnapshot()
            BackupRestoreJournal.open(root).use { journal ->
                for (generation in listOf(incoming, before, target)) journal.registerGeneration(generation.ownership())
                fixture.barrier.exclusively {
                    val baseline = RoomBackupCapture(fixture.database).mainDigest(checkCancelled = {})
                    val versions = fixture.fonts.withSettings { fixture.configurations.readAll(it) }
                    val run = BackupRestoreRun(stableUuid("cancel-run"), stage.header.exportId, "8".repeat(64), incoming.root.name,
                        before.root.name, stage.header.scopes, BackupMergeMode.REPLACE_SELECTED, baseline)
                    journal.begin(run, versions, ThemeStorageRecords.encode(fixture.themes.readCatalogForBackup()))
                    fixture.permissions.prepare(stage, run.archiveSha256)
                    journal.configureTargets(run.id, versions.mapValues { it.value.record })
                    for ((_, version) in versions) {
                        journal.planConfig(run.id, BackupConfigDirection.FORWARD, version, version.record)
                        journal.confirmConfig(run.id, version)
                    }
                    val cancellation = CancellationException("Synthetic caller cancelled before Room COMMIT")
                    try {
                        RoomBackupMaterializer(fixture.context.packageName, fixture.barrier, fixture.database, fixture.library,
                            fixture.permissions, fixture.resources::restoredLyrics).apply(run, stage, journal) { bindings ->
                            val proposed = RoomBackupCapture(fixture.database).mainDigest(checkCancelled = {}, insideWriter = true)
                            journal.prepareMain(run.id, target.root.name, proposed, bindings + mapOf(
                                "mainMissingReferences" to BackupCell.Integer(0), "resourceMissingReferences" to BackupCell.Integer(0)))
                            throw cancellation
                        }
                        throw AssertionError("Expected cancellation before native COMMIT")
                    } catch (failure: CancellationException) { assertSame(cancellation, failure) }
                    assertNull(fixture.database.libraryDao().currentTrack("fixture-new-track"))
                    assertEquals(baseline, RoomBackupCapture(fixture.database).mainDigest(checkCancelled = {}))
                    assertEquals(BackupRestorePhase.MAIN_PREPARED, journal.readRun()!!.phase)
                    assertNotEquals(baseline, journal.readRun()!!.targetMainDigest)
                }
            }
        }
    }

    @Test(timeout = 60_000L)
    fun realHistoryAndStatisticsMergeOnceThenIndependentClearsReapplyOnlyTheClearedDomain() = runBlocking {
        val root = directory("history-source")
        fixture.archiveInput = archive(root) { stage ->
            stage.begin(BackupTestFixtures.header(BackupScope.HISTORY))
            stage.record(BackupTestFixtures.historyMeta(2L, 45_000L))
            stage.record(BackupTestFixtures.historyEntry(1L))
            stage.record(BackupTestFixtures.trackTotal())
            stage.record(BackupTestFixtures.dayTotal())
        }
        suspend fun restore(mode: BackupMergeMode) {
            fixture.attachClosedPlayback()
            val preview = fixture.coordinator.previewRestore(input(), mode)
            fixture.coordinator.confirmRestore(preview.token)
        }
        restore(BackupMergeMode.REPLACE_SELECTED)
        val first = fixture.listening.state()
        assertEquals(2L, fixture.listening.summary().totalPlayCount)
        restore(BackupMergeMode.MERGE_KEEP_CURRENT)
        assertEquals(first, fixture.listening.state())
        assertEquals(2L, fixture.listening.summary().totalPlayCount)
        fixture.listening.clearHistory(first.historyEpoch)
        restore(BackupMergeMode.MERGE_KEEP_CURRENT)
        val historyRestored = fixture.listening.state()
        assertEquals(2L, fixture.listening.summary().totalPlayCount)
        fixture.database.withTestReaderSql {
            assertEquals(1L, scalarLong("SELECT COUNT(*) FROM listening_history h JOIN listening_meta m ON h.historyEpoch=m.historyEpoch WHERE h.historyId>=m.historyFloorSequence"))
            assertEquals(1L, scalarLong("SELECT COUNT(*) FROM listening_rounds WHERE rulesVersion=0"))
        }
        fixture.listening.clearStatistics(historyRestored.statisticsEpoch)
        assertEquals(0L, fixture.listening.summary().totalPlayCount)
        restore(BackupMergeMode.MERGE_KEEP_CURRENT)
        assertEquals(2L, fixture.listening.summary().totalPlayCount)
        assertEquals(45_000L, fixture.listening.summary().totalListeningMs)
        fixture.database.withTestReaderSql {
            assertEquals(1L, scalarLong("SELECT COUNT(*) FROM listening_history h JOIN listening_meta m ON h.historyEpoch=m.historyEpoch WHERE h.historyId>=m.historyFloorSequence"))
            assertEquals(1L, scalarLong("SELECT COUNT(*) FROM listening_rounds WHERE rulesVersion=0"))
        }
    }
}
