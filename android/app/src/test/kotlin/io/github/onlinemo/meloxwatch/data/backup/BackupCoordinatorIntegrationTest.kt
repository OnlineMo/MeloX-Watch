package io.github.onlinemo.meloxwatch.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutation
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutationRequest
import io.github.onlinemo.meloxwatch.data.themes.ThemeStorageRecords
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** Coordinator regression with real Room/native stores; platform document provider acceptance is separate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class BackupCoordinatorIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var fixture: NativeBackupFixture
    @Before fun open() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        fixture = NativeBackupFixture(RuntimeEnvironment.getApplication() as Context, temporary.newFolder("app"))
    }
    @After fun close() {
        try { runBlocking { withTimeout(20_000L) { fixture.close() } } }
        finally { Dispatchers.resetMain() }
    }

    @Test(timeout = 60_000L)
    fun completedPreviewCallerCanConfirmFromADifferentCallerUsingTheSameFrozenArchive() = runBlocking {
        val source = BackupPrivateDirectory.open(temporary.newFolder("source"))
        SqliteBackupStage.create(source) {}.use { stage ->
            stage.begin(BackupTestFixtures.header(BackupScope.HISTORY))
            stage.record(BackupTestFixtures.historyMeta(0L, 0L)); stage.sealSnapshot()
            val archive = source.child("export.zip")
            BackupArchiveWriter().write(stage, archive) {}
            fixture.archiveInput = archive
        }
        assertTrue(withTimeout(20_000L) { fixture.coordinator.initializeStorage() } is BackupStartupState.Ready)
        fixture.attachClosedPlayback()
        val previewCaller = async(Dispatchers.IO) { fixture.coordinator.previewRestore(
            BackupDocumentInput(Uri.parse("content://fixture.documents/backup"), Intent.FLAG_GRANT_READ_URI_PERMISSION),
            BackupMergeMode.REPLACE_SELECTED,
        ) }
        val preview = withTimeout(20_000L) { previewCaller.await() }
        assertTrue(previewCaller.isCompleted)
        assertFalse(previewCaller.isActive)
        val confirmationCaller = async(Dispatchers.IO) { fixture.coordinator.confirmRestore(preview.token) }
        val receipt = withTimeout(20_000L) { confirmationCaller.await() }
        assertEquals(setOf(BackupScope.HISTORY), receipt.restoredScopes)
        assertEquals(0L, receipt.missingReferences)
        assertEquals(1, fixture.closedPlaybackCalls)
        assertEquals(BackupAccessState.READY, fixture.barrier.state.value)
        assertEquals(1L, fixture.listening.state().historyEpoch)
    }

    @Test(timeout = 60_000L)
    fun allThreeRestoreHooksOpenOnlyTheFileOwnersBeforeBackupStartupCanInitializeMainRoom() = runBlocking {
        assertEquals(BackupAccessState.RECOVERY_REQUIRED, fixture.barrier.state.value)
        fixture.attachClosedPlayback()
        var admitted = false
        withTimeout(20_000L) {
            fixture.fileOperations.withExclusiveLibraryMutation {
                fixture.fileOperations.withClosedPlayback { reservation ->
                    reservation.requireClosedResources()
                    fixture.fileOperations.clearIndexUndoAfterLibraryReplacement()
                    admitted = true
                }
            }
        }
        assertTrue(admitted)
        assertEquals(1, fixture.closedPlaybackCalls)
        assertEquals(BackupAccessState.RECOVERY_REQUIRED, fixture.barrier.state.value)
        val startup = withTimeout(20_000L) { fixture.coordinator.initializeStorage() }
        assertTrue(startup is BackupStartupState.Ready && !startup.recoveredPendingRun)
        assertEquals(BackupAccessState.READY, fixture.barrier.state.value)
    }

    @Test(timeout = 60_000L)
    fun pendingRestoreStartupReplaysBeforeAnyOrdinaryMainWriterAndDoesNotAwaitItsOwnReady() = runBlocking {
        val baseline = fixture.barrier.exclusively(recovery = true) {
            fixture.initializeDatabaseOwners()
            val versions = fixture.fonts.withSettings { fixture.configurations.readAll(it) }
            val digest = RoomBackupCapture(fixture.database).mainDigest(checkCancelled = {})
            Triple(versions, digest, ThemeStorageRecords.encode(fixture.themes.readCatalogForBackup()))
        }
        assertEquals(BackupAccessState.RECOVERY_REQUIRED, fixture.barrier.state.value)
        val root = BackupPrivateDirectory.createBelow(fixture.context.noBackupFilesDir, "logical-backups-v1")
        BackupRestoreJournal.open(root).use { journal ->
            val incoming = root.newGeneration()
            val before = root.newGeneration()
            journal.registerGeneration(incoming.ownership()); journal.registerGeneration(before.ownership())
            journal.begin(BackupRestoreRun(stableUuid("pending-run"), stableUuid("pending-export"), "f".repeat(64),
                incoming.root.name, before.root.name, setOf(BackupScope.HISTORY), BackupMergeMode.REPLACE_SELECTED,
                baseline.second), baseline.first, baseline.third)
        }
        fixture.attachClosedPlayback()
        val startup = withTimeout(20_000L) { fixture.coordinator.initializeStorage() }
        assertTrue(startup is BackupStartupState.Ready && startup.recoveredPendingRun)
        assertEquals(1, fixture.closedPlaybackCalls)
        assertEquals(BackupAccessState.READY, fixture.barrier.state.value)
        assertEquals(baseline.second, RoomBackupCapture(fixture.database).mainDigest(checkCancelled = {}))
        assertTrue(root.root.listFiles()!!.none { it.isDirectory })
    }

    @Test(timeout = 60_000L)
    fun confirmationCannotReplaceAnExistingPlaylistWithAnIndexTruncatedIncomingPlaylist() = runBlocking {
        assertTrue(withTimeout(20_000L) { fixture.coordinator.initializeStorage() } is BackupStartupState.Ready)
        fixture.playlists.apply(PlaylistMutationRequest("fixture-create-preserved", "fixture-playlist", 0L,
            PlaylistMutation.Create("Preserved playlist")))
        val preserved = checkNotNull(fixture.playlists.snapshot("fixture-playlist"))
        val source = BackupPrivateDirectory.open(temporary.newFolder("source-playlist"))
        SqliteBackupStage.create(source) {}.use { stage ->
            stage.begin(BackupTestFixtures.header(BackupScope.PLAYLISTS))
            stage.record(BackupTestFixtures.playlistHead("fixture-playlist", 2L))
            stage.record(BackupTestFixtures.playlistMember("fixture-playlist", 0L, "fixture-track-a"))
            stage.record(BackupTestFixtures.playlistMember("fixture-playlist", 1L, "fixture-track-b"))
            stage.sealSnapshot()
            fixture.archiveInput = source.child("export.zip").also { BackupArchiveWriter().write(stage, it) {} }
        }
        val input = BackupDocumentInput(Uri.parse("content://fixture.documents/backup"), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val preview = fixture.coordinator.previewRestore(input, BackupMergeMode.REPLACE_SELECTED)
        val mainBefore = RoomBackupCapture(fixture.database).mainDigest(checkCancelled = {})
        val root = BackupPrivateDirectory.createBelow(fixture.context.noBackupFilesDir, "logical-backups-v1")
        val incoming = root.root.listFiles()!!.single { it.isDirectory && File(it, "input.zip").isFile }
        val unchangedArchive = backupFileSha256(File(incoming, "input.zip")) {}
        BundledSQLiteDriver().open(File(incoming, "rows.db").absolutePath).use { connection ->
            connection.execute("DELETE FROM logical_ordinals WHERE family='playlist' AND owner='fixture-playlist' AND ordinal=1")
            assertEquals(1L, connection.prepare("SELECT changes()").use { it.step(); it.getLong(0) })
            assertEquals("ok", connection.prepare("PRAGMA quick_check(1)").use { it.step(); it.getText(0) })
        }
        assertEquals(unchangedArchive, backupFileSha256(File(incoming, "input.zip")) {})
        fixture.attachClosedPlayback()
        try {
            fixture.coordinator.confirmRestore(preview.token)
            throw AssertionError("A missing ordinal must fail before main/native restore commits")
        } catch (failure: BackupException) { assertEquals(BackupIssue.INTEGRITY_MISMATCH, failure.issue) }
        assertEquals(preserved, fixture.playlists.snapshot("fixture-playlist"))
        assertEquals(mainBefore, RoomBackupCapture(fixture.database).mainDigest(checkCancelled = {}))
        assertEquals(BackupAccessState.READY, fixture.barrier.state.value)
        fixture.coordinator.discardRestorePreview(preview.token)
    }
}
