package io.github.onlinemo.meloxwatch.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.onlinemo.meloxwatch.data.cue.AndroidCueLocalDocuments
import io.github.onlinemo.meloxwatch.data.cue.CueByteFixture
import io.github.onlinemo.meloxwatch.data.cue.CueDocumentsProviderFixture
import io.github.onlinemo.meloxwatch.data.cue.CuePathMatcher
import io.github.onlinemo.meloxwatch.data.cue.CueScanReader
import io.github.onlinemo.meloxwatch.data.cue.CueReferenceCodec
import io.github.onlinemo.meloxwatch.data.cue.fixtureCueDocuments
import io.github.onlinemo.meloxwatch.data.database.withTestReaderSql
import io.github.onlinemo.meloxwatch.data.database.withTestWriterSql
import io.github.onlinemo.meloxwatch.data.library.LibraryTrack
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.local.ContentFingerprint
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.playlists.FrozenPlaylistInput
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistInputPage
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutation
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutationRequest
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistPageSource
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistReference
import io.github.onlinemo.meloxwatch.data.queue.QueueEntryEntity
import io.github.onlinemo.meloxwatch.data.queue.QueueGenerationEntity
import io.github.onlinemo.meloxwatch.data.scan.LocalScanEngine
import io.github.onlinemo.meloxwatch.data.scan.ScanPhase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
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

/** 云端测试定义使用真实提供方字节、Room 和 ZIP；音频输出与设备门槛仍须分别验证。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class CueBackupRoundTripTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var fixture: NativeBackupFixture
    private lateinit var provider: CueDocumentsProviderFixture
    private val bytes = CueByteFixture()
    @Before fun open() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        fixture = NativeBackupFixture(RuntimeEnvironment.getApplication() as Context, temporary.newFolder("app"))
        check(fixture.coordinator.initializeStorage() is BackupStartupState.Ready)
        provider = fixtureCueDocuments(fixture.context, "cue-backup-documents")
    }
    @After fun close() {
        try { runBlocking { fixture.close() } } finally { Dispatchers.resetMain() }
    }
    @Test(timeout = 60_000L)
    fun fullArchiveRestoresPendingThenExplicitlyAdoptsANewVerifiedSourceForEveryDuplicateQueueInstance() = runBlocking {
        val original = scan("old")
        seedRelations(original)
        val sourceCueId = requireNotNull(original.first().cueReference).identity.cueId
        val archive = capture()
        fixture.archiveInput = archive
        fixture.attachClosedPlayback()
        val restore = fixture.coordinator.previewRestore(input(), BackupMergeMode.REPLACE_SELECTED)
        fixture.coordinator.confirmRestore(restore.token)
        for (track in original) {
            val pending = CueReferenceCodec.decode(requireNotNull(fixture.database.libraryDao().currentTrack(track.trackKey.trackId)?.cueReference))
            assertFalse(pending.isBound)
            assertEquals(track.trackKey, pending.trackKey)
            assertEquals(requireNotNull(track.cueReference).portable().identity, pending.identity)
        }
        assertTrue(requireNotNull(fixture.database.cueDao().documentById(sourceCueId)).reassociationPending)
        val next = scan("new")
        assertNotEquals(original.first().trackKey, next.first().trackKey)
        val missing = fixture.coordinator.missingRelinks().items.filter { it.isCue }
        assertEquals(original.map { it.trackKey.trackId }.toSet(), missing.map { it.trackId }.toSet())
        val preview = fixture.coordinator.previewCueRelink(original.first().trackKey.trackId, next.first().trackKey)
        assertEquals(BackupRelinkMatch.VERIFIED_MATCH, preview.match)
        assertTrue(requireNotNull(preview.cueDifferences).identityChanged)
        assertEquals(2, preview.cueOriginalMembers);
        assertEquals(2, preview.cueTargetMembers)
        assertEquals(10_003L, preview.affectedQueueEntries)
        assertThrows(BackupException::class.java) { runBlocking { fixture.coordinator.confirmRelink(preview.token) } }
        // 真实存储收束可能推进检查点围栏；显式重新预览后须单独确认。
        val reviewed = fixture.coordinator.previewCueRelink(original.first().trackKey.trackId, next.first().trackKey)
        val committed = fixture.coordinator.confirmRelink(reviewed.token, allowCueDifferences = true)
        assertEquals(10_003L, committed.repairedQueueEntries)
        for (old in original) {
            val read = fixture.library.resolveCueRead(old.trackKey)
            assertEquals(sourceCueId, read.reference.identity.cueId)
            assertEquals(old.trackKey, read.reference.trackKey)
            assertEquals(requireNotNull(old.cueReference).identity.trackNumber, read.reference.identity.trackNumber)
            assertEquals(requireNotNull(old.cueReference).identity.startFrame, read.reference.identity.startFrame)
            assertEquals("source-new", read.reference.actualDocument.sourceId)
            assertNotNull(read.reference.binding?.adoptionAliasId)
        }
        val head = requireNotNull(fixture.database.queueDao().head())
        assertEquals("q-10002", head.currentEntryId);
        assertEquals(777L, head.positionMs);
        assertEquals(3, head.completedCycles)
        fixture.database.withTestReaderSql {
            assertEquals(10_003L, scalarLong("SELECT COUNT(*) FROM queue_entries WHERE generationId=?", head.activeGenerationId!!))
            assertEquals(10_003L, scalarLong("SELECT COUNT(*) FROM queue_entries WHERE generationId=? AND cueReference IS NOT NULL AND json_extract(cueReference,'$.state')='BOUND'", head.activeGenerationId!!))
            assertEquals(10_002L, scalarLong("SELECT playbackOrder FROM queue_entries WHERE generationId=? AND queueEntryId='q-0'", head.activeGenerationId!!))
            assertEquals(original.first().trackKey.trackId, scalarText("SELECT m.trackId FROM playlist_heads h JOIN playlist_generations g ON g.generationId=h.activeGenerationId JOIN playlist_members m ON m.generationId=g.dataGenerationId WHERE h.playlistId='cue-list' AND m.ordinal=0"))
            assertEquals(1L, scalarLong("SELECT g.memberCount FROM playlist_heads h JOIN playlist_generations g ON g.generationId=h.activeGenerationId WHERE h.playlistId='system:favorites'"))
        }
        assertEquals(0, provider.activeCursors.get())
        assertTrue(provider.opened.any { it == "new-cue" } && provider.opened.any { it == "new-wave" })
    }
    @Test(timeout = 30_000L)
    fun cueReassociationRejectsBareAudioAndLatePermissionLossWithoutWritingTheMapping() = runBlocking {
        val original = scan("old")
        fixture.archiveInput = capture()
        val restore = fixture.coordinator.previewRestore(input(), BackupMergeMode.REPLACE_SELECTED)
        fixture.coordinator.confirmRestore(restore.token)
        val next = scan("new")
        val view = fixture.library.openView()
        val bare = try { fixture.library.page(view).tracks.single { it.cueReference == null && it.sourceId == "source-new" } }
            finally { fixture.library.closeView(view.viewId) }
        assertThrows(BackupException::class.java) {
            runBlocking { fixture.coordinator.previewCueRelink(original.first().trackKey.trackId, bare.trackKey) }
        }
        val plan = fixture.coordinator.previewCueRelink(original.first().trackKey.trackId, next.first().trackKey)
        provider.denied = "new-cue"
        assertThrows(BackupException::class.java) {
            runBlocking { fixture.coordinator.confirmRelink(plan.token, allowCueDifferences = true) }
        }
        assertFalse(CueReferenceCodec.decode(requireNotNull(fixture.database.libraryDao().currentTrack(original.first().trackKey.trackId)?.cueReference)).isBound)
        val old = requireNotNull(original.first().cueReference).identity
        assertEquals(null, fixture.database.cueDao().alias(old.sourceId, old.cueId))
    }

    @Test(timeout = 30_000L)
    fun archiveReplacementFailureDuringNativeCueRetirementRollsBackTheGroupAndAllPreservedLogicalRelations() = runBlocking {
        val original = scan("old")
        fixture.playlists.apply(PlaylistMutationRequest("cue-rollback-favorite", "system:favorites", 0,
            PlaylistMutation.SetFavorite(PlaylistReference(trackKey = original.first().trackKey), true)))
        fixture.archiveInput = capture()
        fixture.attachClosedPlayback()
        val cueId = checkNotNull(original.first().cueReference).identity.cueId
        val document = checkNotNull(fixture.database.cueDao().documentById(cueId))
        val groups = fixture.database.cueDao().currentGroups(cueId)
        assertEquals(1, groups.size)
        val before = RoomBackupCapture(fixture.database).mainDigest(checkCancelled = {})
        fixture.database.withTestWriterSql {
            execute("CREATE TRIGGER abort_cue_restore_retirement AFTER UPDATE ON local_cue_group_versions " +
                "WHEN NEW.validTo IS NOT NULL BEGIN SELECT RAISE(ABORT, 'cue-native-retirement-rollback'); END")
        }
        val preview = fixture.coordinator.previewRestore(input(), BackupMergeMode.REPLACE_SELECTED)
        try {
            val failure = assertThrows(BackupException::class.java) {
                runBlocking { fixture.coordinator.confirmRestore(preview.token) }
            }
            assertTrue(generateSequence<Throwable>(failure) { it.cause }
                .any { it.message?.contains("cue-native-retirement-rollback") == true })
            assertEquals(document, fixture.database.cueDao().documentById(cueId))
            assertEquals(groups, fixture.database.cueDao().currentGroups(cueId))
            assertEquals(before, RoomBackupCapture(fixture.database).mainDigest(checkCancelled = {}))
            assertEquals(BackupAccessState.READY, fixture.barrier.state.value)
            for (track in original) {
                assertEquals(track.cueReference, fixture.library.resolveCueRead(track.trackKey).reference)
                assertEquals(track.cueReference, CueReferenceCodec.decode(checkNotNull(
                    fixture.database.libraryDao().currentTrack(track.trackKey.trackId)?.cueReference)))
            }
        } finally {
            fixture.database.withTestWriterSql { execute("DROP TRIGGER abort_cue_restore_retirement") }
            fixture.coordinator.discardRestorePreview(preview.token)
        }
    }

    private fun input() = BackupDocumentInput(Uri.parse("content://fixture.documents/archive"), Intent.FLAG_GRANT_READ_URI_PERMISSION)
    private suspend fun scan(root: String): List<LibraryTrack> {
        provider.roots.add(root)
        val folder = temporary.newFolder("audio-$root")
        provider.documents["$root-cue"] = File(folder, "disc.cue").apply { writeBytes(bytes.cue) }
        provider.documents["$root-wave"] = File(folder, "disc.wav").apply { writeBytes(bytes.wave) }
        val observation = fixture.sources.observe(LocalSourceSpec("source-$root", LocalSourceKind.TREE,
            "content://cue-backup-documents/tree/$root", Intent.FLAG_GRANT_READ_URI_PERMISSION))
        val source = fixture.fileOperations.mutations.registerSource(observation.source, observation.access)
        val reader = CueScanReader(fixture.fileOperations.cueMutations, CuePathMatcher(fixture.sources),
            AndroidCueLocalDocuments(fixture.sources))
        val scanner = LocalScanEngine(fixture.sources, { LocalAudioMetadata(title = "Whole file", durationMs = 2000, mimeType = "audio/wav") },
            fixture.fileOperations.mutations, fixture.io, fixture.scope, readContentToken = { candidate ->
                fixture.io.read { signal ->
                    fixture.context.contentResolver.openAssetFileDescriptor(Uri.parse(candidate.uri), "r", signal)!!.use { descriptor ->
                        descriptor.createInputStream().use { ContentFingerprint.read(it, signal, candidate.sizeBytes) }
                    }
                }
            }, verifyMetadataCandidate = fixture.sources::verifyObservedCandidate, scanCue = reader::read)
        val token = scanner.start(source, fixture.library.rules().revision)
        val state = withTimeout(20_000) { scanner.state.filterNotNull().first { it.token.scanId == token.scanId &&
            it.phase in setOf(ScanPhase.COMPLETED, ScanPhase.FAILED, ScanPhase.CANCELLED) } }
        assertEquals(ScanPhase.COMPLETED, state.phase)
        val view = fixture.library.openView()
        return try { fixture.library.page(view).tracks.filter { it.cueReference?.identity?.sourceId == "source-$root" }
            .sortedBy { requireNotNull(it.cueReference).identity.trackNumber }.also { assertEquals(2, it.size) } }
        finally { fixture.library.closeView(view.viewId) }
    }
    private suspend fun seedRelations(tracks: List<LibraryTrack>) {
        fixture.playlists.apply(PlaylistMutationRequest("cue-favorite", "system:favorites", 0,
            PlaylistMutation.SetFavorite(PlaylistReference(trackKey = tracks.first().trackKey), true)))
        val input = FrozenPlaylistInput("cue-list-input", 0, tracks.size.toLong())
        fixture.playlists.apply(PlaylistMutationRequest("cue-list-create", "cue-list", 0, PlaylistMutation.Create("CUE list", input)),
            PlaylistPageSource { offset, limit ->
                val page = tracks.drop(offset.toInt()).take(limit)
                PlaylistInputPage(input, offset, page.map { PlaylistReference(trackKey = it.trackKey) }, offset + page.size == input.totalCount)
            })
        fixture.library.libraryTransaction {
            val queue = fixture.database.queueDao()
            val old = requireNotNull(queue.head())
            queue.insertGeneration(QueueGenerationEntity("cue-source-queue", "cue-source-queue-op", old.activeGenerationId,
                old.queueRevision, "cue-source-view", 0L, 10_003L, true, "LIST", 7, 0L, 777L, false))
            var first = 0L
            while (first < 10_003L) {
                val page = (first until minOf(first + 100L, 10_003L)).map { ordinal ->
                    val track = tracks[(ordinal % 2L).toInt()]
                    val cue = requireNotNull(track.cueReference)
                    val location = requireNotNull(cue.binding).audio.location
                    QueueEntryEntity("cue-source-queue", "q-$ordinal", "local", track.trackKey.trackId, ordinal, 10_002L - ordinal,
                        track.title, track.metadata.durationMs, null, location.locationId, location.uri, location.grantUri,
                        location.grantFlags, location.access.name, location.contentRevision, CueReferenceCodec.encode(cue))
                }
                queue.insertEntries(page);
                first += page.size
            }
            check(queue.advanceStaging("cue-source-queue", 0L, 10_003L) == 1)
            check(queue.seal("cue-source-queue") == 1)
            check(queue.publish("cue-source-queue", old.activeGenerationId, old.queueRevision, old.queueRevision + 1,
                "q-10002", 777L, 3) == 1)
            check(queue.markPublished("cue-source-queue", old.queueRevision + 1) == 1)
        }
    }
    private suspend fun capture(): File {
        val directory = BackupPrivateDirectory.open(temporary.newFolder("archive"))
        return SqliteBackupStage.create(directory) {}.use { stage ->
            stage.begin(BackupHeader(scopes = RoomBackupCapture.MAIN_SCOPES, createdEpochMs = BackupTestFixtures.EPOCH_MS))
            RoomBackupCapture(fixture.database).captureMain(stage)
            stage.sealSnapshot()
            directory.child("archive.zip").also { BackupArchiveWriter().write(stage, it) {} }
        }
    }
}
