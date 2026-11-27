package io.github.onlinemo.meloxwatch.data.scan

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.database.withPreservedTransaction
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationGuardedMutationStore
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationMutationGuard
import io.github.onlinemo.meloxwatch.data.fileoperations.SqliteFileOperationJournal
import io.github.onlinemo.meloxwatch.data.library.LibraryAvailability
import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.library.LibraryTrack
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.library.RoomLocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.library.RoomPlaylistFences
import io.github.onlinemo.meloxwatch.data.local.AndroidLocalSources
import io.github.onlinemo.meloxwatch.data.local.LocalCandidateCursor
import io.github.onlinemo.meloxwatch.data.local.LocalGrantObservation
import io.github.onlinemo.meloxwatch.data.local.LocalNodeKind
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.LocalSourceAccess
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceObservation
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCatalogFence
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistException
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

/** Real Room transactions with a controlled provider boundary; format and device acceptance remain separate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class IncrementalLocalScanEngineTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var database: MeloXDatabase
    private lateinit var library: RoomLocalLibraryRepository
    private lateinit var io: LocalProviderIo
    private lateinit var scope: CoroutineScope
    private lateinit var engine: LocalScanEngine
    private val graph = MediaGraph()
    private val metadataReads = mutableListOf<String>()
    private val badMetadata = mutableSetOf<String>()

    @Before fun create() = runBlocking {
        openDatabase()
        io = LocalProviderIo()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        engine = LocalScanEngine(graph, { candidate ->
            metadataReads += candidate.objectId
            if (candidate.objectId in badMetadata) throw SourceReadException(SourceReadIssue.METADATA_UNREADABLE)
            LocalAudioMetadata(title = graph.titles.getValue(candidate.objectId), album = graph.albums[candidate.objectId],
                durationMs = 60_000L, mimeType = "audio/mpeg")
        }, library, io, scope, verifyMetadataCandidate = { _, expected -> graph.verify(expected) })
        library.registerSource(graph.spec, graph.grant)
    }

    @After fun close() = runBlocking {
        try {
            if (::scope.isInitialized) {
                scope.cancel()
                withTimeout(10_000L) { scope.coroutineContext[Job]!!.children.toList().joinAll() }
            }
        } finally { if (::database.isInitialized) database.close() }
    }

    @Test(timeout = 30_000L) fun newAndChangedRowsAreParsedWhileUnchangedMetadataAndIdentityAreRetained() = runBlocking {
        graph.put("1", 1L, "Original", "Old album")
        graph.put("2", 1L, "Unchanged")
        assertPlaylistFenceAndRejectModeForgery(full())
        val oldKeys = tracks().associate { it.metadata.title to it.trackKey }
        metadataReads.clear(); graph.queries.clear()
        graph.put("1", 2L, "Retagged", "New album")
        graph.put("3", 2L, "Added")
        graph.generation = 2L

        val state = update()
        assertEquals(ScanMode.INCREMENTAL, state.token.mode)
        assertPlaylistFenceAndRejectModeForgery(state)
        assertEquals(listOf<Long?>(1L, null), graph.queries)
        assertEquals(setOf(graph.id("1"), graph.id("3")), metadataReads.toSet())
        assertEquals(2, metadataReads.size)
        assertEquals(1L, state.counters.added)
        val observed = tracks().associateBy { it.metadata.title }
        assertEquals(3, observed.size)
        assertEquals(oldKeys["Original"], observed.getValue("Retagged").trackKey)
        assertEquals(oldKeys["Unchanged"], observed.getValue("Unchanged").trackKey)
        assertEquals("New album", observed.getValue("Retagged").metadata.album)
        assertEquals(2L, checkpoint().generation)

        metadataReads.clear(); graph.queries.clear()
        val unchanged = update()
        assertEquals(listOf<Long?>(2L, null), graph.queries)
        assertTrue(metadataReads.isEmpty())
        assertEquals(0L, unchanged.counters.processed)
        assertEquals(3L, unchanged.counters.skipped)
        assertEquals(observed.mapValues { it.value.trackKey }, tracks().associate { it.metadata.title to it.trackKey })
    }

    @Test(timeout = 30_000L) fun deletionIsReconciledOnlyAfterTheFullPresenceCursorAndDoesNotReparseSurvivors() = runBlocking {
        graph.put("1", 1L, "Kept"); graph.put("2", 1L, "Deleted")
        full()
        val deletedKey = tracks().single { it.metadata.title == "Deleted" }.trackKey
        metadataReads.clear(); graph.queries.clear()
        graph.rows.remove(graph.id("2")); graph.generation = 2L

        val state = update()
        assertTrue(state.coverage.permitsAbsence)
        assertEquals(listOf<Long?>(1L, null), graph.queries)
        assertTrue(metadataReads.isEmpty())
        assertEquals(LibraryAvailability.MISSING, tracks().single { it.trackKey == deletedKey }.availability)
        assertEquals(LibraryAvailability.AVAILABLE, tracks().single { it.metadata.title == "Kept" }.availability)
        assertEquals(2L, library.overview().totalTracks)
        assertEquals(2L, checkpoint().generation)
    }

    @Test(timeout = 30_000L) fun aFailedPresenceCursorKeepsTheCheckpointAndOldReferenceAvailability() = runBlocking {
        graph.put("1", 1L, "Kept"); graph.put("2", 1L, "Previously present")
        full()
        val before = checkpoint()
        graph.rows.remove(graph.id("2")); graph.put("3", 2L, "Added before failure")
        graph.generation = 2L; graph.failFull = true

        val partial = update()
        assertFalse(partial.coverage.permitsAbsence)
        assertFalse(partial.coverage.enumerationComplete)
        assertEquals(before, checkpoint())
        assertTrue(tracks().all { it.availability == LibraryAvailability.AVAILABLE })
        assertEquals("Added before failure", tracks().single { it.metadata.title == "Added before failure" }.metadata.title)

        graph.failFull = false
        val retried = update()
        assertTrue(retried.coverage.permitsAbsence)
        assertEquals(2L, checkpoint().generation)
        assertEquals(LibraryAvailability.MISSING, tracks().single { it.metadata.title == "Previously present" }.availability)
    }

    @Test(timeout = 30_000L) fun aFailedChangedMetadataReadDoesNotAdvanceTheWatermarkAndRemainsRetryable() = runBlocking {
        graph.put("1", 1L, "Original"); graph.put("2", 1L, "Unchanged")
        full()
        val before = checkpoint()
        graph.put("1", 2L, "Recovered tag"); graph.generation = 2L
        badMetadata += graph.id("1")

        val failed = update()
        assertEquals(1L, failed.counters.failed)
        assertEquals(before, checkpoint())
        assertEquals("Original", tracks().single { it.metadataIssue != null }.metadata.title)
        badMetadata.clear(); metadataReads.clear()
        update()
        assertEquals(listOf(graph.id("1")), metadataReads)
        assertEquals("Recovered tag", tracks().single { it.metadata.title != "Unchanged" }.metadata.title)
        assertEquals(2L, checkpoint().generation)
    }

    @Test(timeout = 30_000L) fun changedRulesLegacyWatermarksAndRegressedGenerationsRequireAFullRecheck() = runBlocking {
        graph.put("1", 1L, "One"); graph.put("2", 1L, "Two")
        full()
        library.setShortAudioFilter(library.rules().revision, true)
        metadataReads.clear(); graph.queries.clear()
        assertEquals(ScanMode.FULL, update().token.mode)
        assertEquals(listOf<Long?>(null), graph.queries)
        assertEquals(2, metadataReads.size)

        val source = database.libraryDao().source(graph.spec.sourceId)!!
        database.libraryDao().putSource(source.copy(watermark = "db1|999"))
        metadataReads.clear()
        assertEquals(ScanMode.FULL, update().token.mode)
        assertEquals(2, metadataReads.size)
        graph.generation = 0L
        val regressed = update()
        assertEquals(ScanMode.FULL, regressed.token.mode)
        assertFalse(regressed.coverage.permitsAbsence)
        assertEquals(1L, checkpoint().generation)
    }

    @Test(timeout = 30_000L) fun aGenerationChangeDuringPresenceEnumerationCannotPublishAbsenceOrANewCheckpoint() = runBlocking {
        graph.put("1", 1L, "Kept"); graph.put("2", 1L, "Previously present")
        full()
        val before = checkpoint()
        graph.rows.remove(graph.id("2")); graph.generation = 2L
        graph.afterFullBatch = { graph.generation = 3L }

        val changed = update()
        assertFalse(changed.coverage.sourceUnchanged)
        assertFalse(changed.coverage.permitsAbsence)
        assertEquals(before, checkpoint())
        assertTrue(tracks().all { it.availability == LibraryAvailability.AVAILABLE })
        graph.afterFullBatch = null
        assertTrue(update().coverage.permitsAbsence)
        assertEquals(3L, checkpoint().generation)
    }

    @Test(timeout = 30_000L) fun cancellationWhilePresenceIsPhysicallyReadingKeepsTheCommittedDeltaAndCheckpoint() = runBlocking {
        withTimeout(20_000L) {
            graph.put("1", 1L, "Kept"); full()
            val before = checkpoint()
            graph.put("2", 2L, "Committed delta"); graph.generation = 2L
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            graph.beforeFullBatch = { io.read { entered.countDown(); check(release.await(10L, TimeUnit.SECONDS)) } }
            val token = engine.update(library.source(graph.spec.sourceId)!!, library.rules().revision)
            try {
                assertTrue(entered.await(5L, TimeUnit.SECONDS))
                engine.cancel(token.scanId)
                assertEquals(ScanPhase.STOPPING, engine.state.value?.phase)
                assertEquals(before, checkpoint())
                release.countDown()
                assertEquals(ScanPhase.CANCELLED, await(token, ScanPhase.CANCELLED).phase)
                assertEquals(before, checkpoint())
                assertEquals(setOf("Kept", "Committed delta"), tracks().map { it.metadata.title }.toSet())
            } finally { release.countDown(); graph.beforeFullBatch = null }
        }
    }

    @Test(timeout = 30_000L) fun deltaDoneCannotAuthorizeAbsenceAndAReopenedStoreInterruptsItsOwner() = runBlocking {
        graph.put("1", 1L, "Kept"); full()
        val before = checkpoint()
        val token = library.beginScan(graph.spec.sourceId, library.source(graph.spec.sourceId)!!.authEpoch,
            library.rules().revision, ScanMode.INCREMENTAL)
        library.commitSeen(token, listOf(graph.root))
        val directory = library.nextDirectory(token)!!
        library.completeDirectory(token, directory.objectId, true)
        assertEquals("DELTA_DONE", database.libraryDao().frontier(token.scanId, directory.objectId)!!.state)
        library.setCoverage(token, ScanCoverage(true, true, true, true))
        expectLibrary(LibraryIssue.INCOMPLETE) { library.stageAbsence(token) }

        database.close(); openDatabase()
        assertEquals(ScanPhase.INTERRUPTED, library.latestScan(graph.spec.sourceId)!!.phase)
        assertEquals(before, checkpoint())
        expectLibrary(LibraryIssue.SOURCE_CHANGED) { library.completeDirectory(token, directory.objectId, true, fullEnumeration = true) }
        assertEquals(LibraryAvailability.AVAILABLE, tracks().single().availability)
    }

    @Test(timeout = 30_000L) fun changedRulesRetireThePendingUpdateWithoutPublishingItsCheckpoint() = runBlocking {
        graph.put("1", 1L, "Kept"); graph.put("2", 1L, "Previously present"); full()
        val before = database.libraryDao().source(graph.spec.sourceId)!!.watermark
        graph.rows.remove(graph.id("2")); graph.generation = 2L
        graph.beforeFullBatch = { library.setShortAudioFilter(library.rules().revision, true) }
        val token = engine.update(library.source(graph.spec.sourceId)!!, library.rules().revision)
        val failed = await(token, ScanPhase.FAILED)
        assertEquals(SourceReadIssue.SOURCE_CHANGED, failed.issue)
        assertEquals(before, database.libraryDao().source(graph.spec.sourceId)!!.watermark)
        assertTrue(tracks().all { it.availability == LibraryAvailability.AVAILABLE })
        graph.beforeFullBatch = null
        assertEquals(ScanMode.FULL, update().token.mode)
    }

    @Test(timeout = 30_000L) fun revokedObservationCannotConsumeTheCommittedWatermarkOrMetadata() = runBlocking {
        graph.put("1", 1L, "Kept"); full()
        val before = checkpoint()
        metadataReads.clear(); graph.observationIssue = SourceReadIssue.PERMISSION_REVOKED
        try { engine.update(library.source(graph.spec.sourceId)!!, library.rules().revision); throw AssertionError("Expected revoked source") }
        catch (failure: SourceReadException) { assertEquals(SourceReadIssue.PERMISSION_REVOKED, failure.issue) }
        assertEquals(before, checkpoint())
        assertTrue(metadataReads.isEmpty())
        assertEquals("Kept", tracks().single().metadata.title)
    }

    @Test(timeout = 30_000L) fun guardedCheckpointAndFullPresenceCallsUseTheSameOriginalMutationFence() = runBlocking {
        graph.put("1", 1L, "Kept"); full()
        val journal = SqliteFileOperationJournal.open(File(temporary.root, "file-guard.db"))
        try {
            val guard = FileOperationMutationGuard(journal, io)
            val guarded = FileOperationGuardedMutationStore(library, guard)
            val source = library.source(graph.spec.sourceId)!!
            assertEquals(checkpoint(), guarded.scanCheckpoint(source.spec.sourceId, source.authEpoch, library.rules().revision))
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val held = scope.launch { guard.originalMutation { entered.complete(Unit); release.await() } }
            try {
                withTimeout(5_000L) { entered.await() }
                expectLibrary(LibraryIssue.BUSY) { guarded.scanCheckpoint(source.spec.sourceId, source.authEpoch, library.rules().revision) }
            } finally { release.complete(Unit); held.join() }
            val token = guarded.beginScan(source.spec.sourceId, source.authEpoch, library.rules().revision, ScanMode.INCREMENTAL)
            guarded.commitSeen(token, listOf(graph.root))
            val directory = guarded.nextDirectory(token)!!
            guarded.completeDirectory(token, directory.objectId, true)
            assertEquals("DELTA_DONE", database.libraryDao().frontier(token.scanId, directory.objectId)!!.state)
            guarded.completeDirectory(token, directory.objectId, true, fullEnumeration = true)
            assertEquals("DONE", database.libraryDao().frontier(token.scanId, directory.objectId)!!.state)
            guarded.setScanState(token, ScanPhase.CANCELLED)
        } finally { journal.close() }
    }

    private suspend fun openDatabase() {
        database = Room.databaseBuilder(RuntimeEnvironment.getApplication() as Context, MeloXDatabase::class.java,
            File(temporary.root, "incremental.db").absolutePath).setDriver(BundledSQLiteDriver()).build()
        library = RoomLocalLibraryRepository(database)
        library.initialize()
    }

    private suspend fun full() = await(engine.start(library.source(graph.spec.sourceId)!!, library.rules().revision))
    private suspend fun update() = await(engine.update(library.source(graph.spec.sourceId)!!, library.rules().revision))
    private suspend fun checkpoint(): MediaScanCheckpoint = library.scanCheckpoint(graph.spec.sourceId,
        library.source(graph.spec.sourceId)!!.authEpoch, library.rules().revision).also { assertNotNull(it) }!!
    private suspend fun assertPlaylistFenceAndRejectModeForgery(state: ScanState) = database.withPreservedTransaction {
        val dao = database.libraryDao()
        val source = checkNotNull(dao.source(state.token.sourceId))
        val scan = checkNotNull(dao.scan(state.token.scanId))
        val meta = checkNotNull(dao.meta())
        assertTrue(checkNotNull(MediaScanCheckpoint.decode(source.watermark)).matchesScan(state.token))
        val fence = PlaylistCatalogFence(meta.revision, meta.rulesRevision, source.sourceId,
            source.revision, source.authEpoch, scan.scanId)
        val verifier = RoomPlaylistFences(database)
        verifier.verify(fence)
        val forgedMode = if (state.token.mode == ScanMode.FULL) ScanMode.INCREMENTAL else ScanMode.FULL
        dao.updateScan(scan.copy(mode = forgedMode.name))
        try {
            verifier.verify(fence)
            throw AssertionError("Expected the published scan mode to remain bound")
        } catch (error: PlaylistException) { assertEquals(PlaylistIssue.INCOMPLETE, error.issue) }
        finally { dao.updateScan(scan) }
        dao.putSource(source.copy(watermark = null))
        try {
            if (state.token.mode == ScanMode.FULL) verifier.verify(fence)
            else {
                try {
                    verifier.verify(fence)
                    throw AssertionError("Expected an incremental scan to require its published checkpoint")
                } catch (error: PlaylistException) { assertEquals(PlaylistIssue.INCOMPLETE, error.issue) }
            }
        } finally { dao.putSource(source) }
        verifier.verify(fence)
    }
    private suspend fun tracks(): List<LibraryTrack> {
        val view = library.openView()
        return try { library.page(view).tracks } finally { library.closeView(view.viewId) }
    }
    private suspend fun await(token: ScanToken, expectedPhase: ScanPhase = ScanPhase.COMPLETED): ScanState = withTimeout(20_000L) {
        val state = engine.state.filterNotNull().first { it.token == token && it.phase in setOf(
            ScanPhase.COMPLETED, ScanPhase.FAILED, ScanPhase.CANCELLED, ScanPhase.PAUSED_BACKGROUND, ScanPhase.INTERRUPTED) }
        scope.coroutineContext[Job]!!.children.toList().joinAll()
        assertEquals(expectedPhase, state.phase)
        state
    }
    private suspend fun expectLibrary(issue: LibraryIssue, action: suspend () -> Any?) {
        try { action(); throw AssertionError("Expected $issue") }
        catch (error: LibraryException) { assertEquals(issue, error.issue) }
    }

    internal class MediaGraph : LocalSourceAccess {
        val spec = LocalSourceSpec("media", LocalSourceKind.MEDIA_STORE, "content://media/external_primary/audio/media",
            authority = "media", rootObjectId = "media-volume:external_primary", mediaVolume = "external_primary", providerIncarnation = "db1")
        val grant = LocalGrantObservation(read = ObservedCapability.AVAILABLE, enumerate = ObservedCapability.AVAILABLE)
        val rows = linkedMapOf<String, ObservedCandidate>()
        val titles = mutableMapOf<String, String>()
        val albums = mutableMapOf<String, String>()
        val queries = mutableListOf<Long?>()
        var generation = 1L
        var failFull = false
        var observationIssue: SourceReadIssue? = null
        var beforeFullBatch: (suspend () -> Unit)? = null
        var afterFullBatch: (() -> Unit)? = null
        val root = ObservedCandidate(spec.sourceId, spec.rootObjectId!!, spec.uri, null, "Media", null, LocalNodeKind.DIRECTORY)
        fun id(id: String) = "media:external_primary:$id:1"
        fun put(id: String, changed: Long, title: String, album: String? = null) {
            val objectId = this.id(id)
            rows[objectId] = ObservedCandidate(spec.sourceId, objectId, spec.uri + "/" + id, root.objectId,
                "$id.mp3", "audio/mpeg", LocalNodeKind.AUDIO, sizeBytes = 123L, modifiedEpochMs = 0L,
                providerVersion = "db1", generationAdded = 1L, generationModified = changed,
                providerContentToken = "db1:$changed", entityProof = objectId)
            titles[objectId] = title
            if (album == null) albums.remove(objectId) else albums[objectId] = album
        }
        fun verify(expected: ObservedCandidate) {
            val current = rows[expected.objectId] ?: throw SourceReadException(SourceReadIssue.FILE_MISSING)
            if (current != expected) throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
        }
        override suspend fun observe(source: LocalSourceSpec): LocalSourceObservation {
            observationIssue?.let { throw SourceReadException(it) }
            return LocalSourceObservation(spec, AndroidLocalSources.sourceIdentity(spec), grant, root, "db1", generation)
        }
        override suspend fun open(source: LocalSourceSpec, parentObjectId: String?, sinceMediaGeneration: Long?): LocalCandidateCursor {
            queries += sinceMediaGeneration
            if (sinceMediaGeneration == null && failFull) throw SourceReadException(SourceReadIssue.SOURCE_UNAVAILABLE)
            val values = rows.values.filter { sinceMediaGeneration == null || it.generationModified!! > sinceMediaGeneration }
            return object : LocalCandidateCursor {
                private var offset = 0
                override var exhausted = false
                override suspend fun nextBatch(limit: Int): List<ObservedCandidate> {
                    if (sinceMediaGeneration == null) beforeFullBatch?.invoke()
                    val end = minOf(values.size, offset + limit)
                    val batch = values.subList(offset, end)
                    offset = end; exhausted = offset == values.size
                    if (sinceMediaGeneration == null) afterFullBatch?.invoke()
                    return batch
                }
                override fun close() { exhausted = true }
            }
        }
    }
}
