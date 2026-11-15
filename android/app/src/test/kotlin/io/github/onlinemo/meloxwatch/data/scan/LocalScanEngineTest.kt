package io.github.onlinemo.meloxwatch.data.scan

import android.content.Context
import androidx.room.Room
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.library.LibraryAvailability
import io.github.onlinemo.meloxwatch.data.library.LibraryQuery
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.library.RoomLocalLibraryRepository
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
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LocalScanEngineTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var database: MeloXDatabase
    private lateinit var library: RoomLocalLibraryRepository
    private lateinit var io: LocalProviderIo
    private lateinit var scope: CoroutineScope
    private val treeSpec = LocalSourceSpec("tree", LocalSourceKind.TREE, "content://scan-audio/tree/root",
        authority = "scan-audio", rootObjectId = "root")

    @Before fun setup() = runBlocking {
        database = Room.databaseBuilder(RuntimeEnvironment.getApplication() as Context, MeloXDatabase::class.java,
            File(temporary.root, "scan.db").absolutePath).build()
        library = RoomLocalLibraryRepository(database)
        library.initialize()
        io = LocalProviderIo()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
    @After fun cleanup() { scope.cancel(); database.close() }

    @Test fun completeRecursiveScanDeduplicatesCyclesAndPublishesAcknowledgedCounters() = runBlocking {
        val access = Graph(treeSpec)
        val music = access.node("music", "root", LocalNodeKind.DIRECTORY, "Music")
        val first = access.node("one", "root")
        val second = access.node("two", "music")
        access.children["root"] = listOf(music, first, first, access.node("notes", "root", LocalNodeKind.OTHER, "notes.txt"))
        access.children["music"] = listOf(second, access.root)
        val source = library.registerSource(treeSpec, access.grant)
        val engine = engine(access)
        val token = engine.start(source, library.rules().revision)
        val state = completed(engine, token)
        assertEquals(ScanCounters(seen = 5, processed = 2, added = 2, skipped = 1), state.counters)
        assertEquals(state, library.latestScan(treeSpec.sourceId))
        assertTrue(state.coverage.permitsAbsence)
        assertEquals(listOf("root", "music"), access.opened)
        assertTrue(access.largestBatch <= 100)
        assertEquals(2L, library.overview().totalTracks)
        val view = library.openView()
        try {
            val tracks = library.page(view).tracks
            assertEquals(setOf("one.mp3", "two.mp3"), tracks.map { it.title }.toSet())
            assertTrue(tracks.all { library.resolveLocalRead(it.trackKey).verifiedContentToken != null })
        } finally { library.closeView(view.viewId) }
    }

    @Test fun unreadableMetadataKeepsSeenExistenceAndPreviouslyValidMetadata() = runBlocking {
        val access = Graph(treeSpec)
        access.children["root"] = listOf(access.node("one", "root"))
        var bad = false
        val engine = engine(access, metadata = {
            if (bad) throw SourceReadException(SourceReadIssue.METADATA_UNREADABLE)
            LocalAudioMetadata(title = "Original title", mimeType = "audio/mpeg")
        })
        val source = library.registerSource(treeSpec, access.grant)
        completed(engine, engine.start(source, library.rules().revision))
        bad = true
        val state = completed(engine, engine.start(library.source(treeSpec.sourceId)!!, library.rules().revision))
        assertEquals(1L, state.counters.failed)
        assertTrue(state.coverage.permitsAbsence)
        val view = library.openView()
        try {
            val track = library.page(view).tracks.single()
            assertEquals("Original title", track.metadata.title)
            assertEquals(SourceReadIssue.METADATA_UNREADABLE.name, track.metadataIssue)
            assertEquals(LibraryAvailability.AVAILABLE, track.availability)
        } finally { library.closeView(view.viewId) }
    }

    @Test fun partialSubtreeFailureDoesNotActivateMissingButALaterCompleteScanCan() = runBlocking {
        val access = Graph(treeSpec)
        access.children["root"] = listOf(access.node("music", "root", LocalNodeKind.DIRECTORY, "Music"))
        access.children["music"] = listOf(access.node("one", "music"))
        val source = library.registerSource(treeSpec, access.grant)
        val engine = engine(access)
        completed(engine, engine.start(source, library.rules().revision))
        access.failParents += "music"
        val partial = completed(engine, engine.start(library.source(treeSpec.sourceId)!!, library.rules().revision))
        assertFalse(partial.coverage.enumerationComplete)
        assertFalse(partial.coverage.permitsAbsence)
        assertEquals(1L, partial.counters.failed)
        assertEquals(LibraryAvailability.AVAILABLE, onlyAvailability())
        access.failParents.clear()
        access.children["root"] = emptyList()
        val full = completed(engine, engine.start(library.source(treeSpec.sourceId)!!, library.rules().revision))
        assertTrue(full.coverage.permitsAbsence)
        assertEquals(LibraryAvailability.MISSING, onlyAvailability())
        assertEquals(1L, library.overview().totalTracks)
    }

    @Test fun excludedSubtreeIsHiddenWithoutPrefixMatchingOrFalseAbsenceReconciliation() = runBlocking {
        val access = Graph(treeSpec)
        access.children["root"] = listOf(access.node("music", "root", LocalNodeKind.DIRECTORY, "Music"),
            access.node("backup", "root", LocalNodeKind.DIRECTORY, "MusicBackup"))
        access.children["music"] = listOf(access.node("one", "music"))
        access.children["backup"] = listOf(access.node("two", "backup"))
        val engine = engine(access)
        val source = library.registerSource(treeSpec, access.grant)
        completed(engine, engine.start(source, library.rules().revision))
        val view = library.openView(LibraryQuery(sourceId = treeSpec.sourceId))
        val musicNode = try {
            val root = library.folders(view, null).folders.single()
            library.folders(view, root.nodeId).folders.single { it.name == "Music" }.nodeId
        } finally { library.closeView(view.viewId) }
        val rules = library.addExclusion(library.rules().revision, treeSpec.sourceId, musicNode, true)
        access.opened.clear()
        val result = completed(engine, engine.start(library.source(treeSpec.sourceId)!!, rules.revision))
        assertTrue(result.coverage.excludedSubtrees)
        assertFalse(result.coverage.permitsAbsence)
        assertEquals(1L, result.counters.skipped)
        assertEquals(listOf("root", "backup"), access.opened)
        assertEquals(2L, library.overview().totalTracks)
        assertEquals(1L, library.overview().visibleTracks)
        val visible = library.openView()
        try { assertEquals("two.mp3", library.page(visible).tracks.single().title) }
        finally { library.closeView(visible.viewId) }
    }

    @Test fun aChangedFullProofDuringMetadataReadIsNotStoredAsStableContent() = runBlocking {
        val access = Graph(treeSpec)
        access.children["root"] = listOf(access.node("one", "root"))
        var proofs = 0
        val engine = engine(access, proof = { proofs++; "sha256:" + (if (proofs == 1) "1" else "2").repeat(64) })
        val source = library.registerSource(treeSpec, access.grant)
        val result = completed(engine, engine.start(source, library.rules().revision))
        assertEquals(1L, result.counters.failed)
        val view = library.openView()
        try {
            assertEquals(SourceReadIssue.SOURCE_CHANGED.name, library.page(view).tracks.single().metadataIssue)
        } finally { library.closeView(view.viewId) }
    }

    @Test fun aProviderTokenChangedDuringExtractionPreservesPreviouslyValidMetadata() = runBlocking {
        val access = Graph(treeSpec)
        val candidate = access.node("one", "root").copy(providerContentToken = "provider:1")
        access.children["root"] = listOf(candidate)
        var actualToken = candidate.providerContentToken
        var changeDuringRead = false
        var verifications = 0
        val engine = engine(access, metadata = {
            if (changeDuringRead) { actualToken = "provider:2"; LocalAudioMetadata(title = "Changed bytes", mimeType = "audio/mpeg") }
            else LocalAudioMetadata(title = "Original title", mimeType = "audio/mpeg")
        }, proof = { throw AssertionError("a reliable provider token must be checked through its actual provider") }, verify = { source, expected ->
            assertEquals(treeSpec, source)
            assertEquals(candidate, expected)
            verifications += 1
            if (expected.providerContentToken != actualToken) throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
        })
        val source = library.registerSource(treeSpec, access.grant)
        completed(engine, engine.start(source, library.rules().revision))
        assertEquals(2, verifications)
        changeDuringRead = true
        val changed = completed(engine, engine.start(library.source(treeSpec.sourceId)!!, library.rules().revision))
        assertEquals(4, verifications)
        assertEquals(1L, changed.counters.failed)
        assertTrue(changed.coverage.permitsAbsence)
        val view = library.openView()
        try {
            val track = library.page(view).tracks.single()
            assertEquals("Original title", track.metadata.title)
            assertEquals(SourceReadIssue.SOURCE_CHANGED.name, track.metadataIssue)
            assertEquals(LibraryAvailability.AVAILABLE, track.availability)
        } finally { library.closeView(view.viewId) }
    }

    @Test fun incrementalMediaObservationDoesNotMarkUnreportedOldRowsMissing() = runBlocking {
        val spec = LocalSourceSpec("media", LocalSourceKind.MEDIA_STORE, "content://media/external_primary/audio/media",
            authority = "media", rootObjectId = "media-root", mediaVolume = "external_primary", providerIncarnation = "db1")
        val access = Graph(spec).apply { providerVersion = "db1"; mediaGeneration = 1 }
        access.children["media-root"] = listOf(access.node("one", "media-root"))
        val source = library.registerSource(spec, access.grant)
        val engine = engine(access)
        completed(engine, engine.start(source, library.rules().revision))
        access.children["media-root"] = listOf(access.node("two", "media-root"))
        access.mediaGeneration = 2
        val state = completed(engine, engine.start(library.source(spec.sourceId)!!, library.rules().revision,
            ScanMode.INCREMENTAL, sinceMediaGeneration = 1))
        assertEquals(ScanMode.INCREMENTAL, state.token.mode)
        assertEquals(1L, state.counters.added)
        assertEquals(2L, library.overview().totalTracks)
        val view = library.openView()
        try { assertTrue(library.page(view).tracks.all { it.availability == LibraryAvailability.AVAILABLE }) }
        finally { library.closeView(view.viewId) }
    }

    @Test fun stopAndDuplicateRefreshKeepThePhysicalChannelOwnedUntilCursorReturn() = runBlocking {
        withTimeout(20_000) {
            val access = Graph(treeSpec)
            access.children["root"] = listOf(access.node("one", "root"))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            access.beforeBatch = {
                io.read { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            }
            val source = library.registerSource(treeSpec, access.grant)
            val engine = engine(access)
            val token = engine.start(source, library.rules().revision)
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertEquals(token, engine.start(source, library.rules().revision))
                expect(SourceReadIssue.BUSY) { engine.start(source, library.rules().revision, ScanMode.INCREMENTAL) }
                val changedRules = library.setShortAudioFilter(library.rules().revision, true)
                expect(SourceReadIssue.SOURCE_CHANGED) { engine.start(source, changedRules.revision) }
                engine.cancel(token.scanId)
                assertEquals(ScanPhase.STOPPING, engine.state.value?.phase)
                expect(SourceReadIssue.BUSY) { engine.start(source, library.rules().revision) }
                expect(SourceReadIssue.BUSY) { io.read { Unit } }
                release.countDown()
                val terminal = await(engine, token)
                assertEquals(ScanPhase.CANCELLED, terminal.phase)
                assertEquals(terminal, library.latestScan(treeSpec.sourceId))
                assertEquals(0L, library.overview().totalTracks)
                access.beforeBatch = null
                completed(engine, engine.start(library.source(treeSpec.sourceId)!!, library.rules().revision))
                assertEquals(1L, library.overview().totalTracks)
            } finally { release.countDown() }
        }
    }

    private fun engine(
        access: Graph,
        metadata: suspend (ObservedCandidate) -> LocalAudioMetadata = { LocalAudioMetadata(title = it.displayName, mimeType = "audio/mpeg") },
        proof: suspend (ObservedCandidate) -> String? = { "sha256:" + "1".repeat(64) },
        verify: suspend (LocalSourceSpec, ObservedCandidate) -> Unit = { _, _ -> throw SourceReadException(SourceReadIssue.CONTENT_UNKNOWN) },
    ) = LocalScanEngine(access, metadata, library, io, scope, proof, verify)

    private suspend fun completed(engine: LocalScanEngine, token: ScanToken): ScanState = await(engine, token).also {
        assertEquals(ScanPhase.COMPLETED, it.phase)
    }
    private suspend fun await(engine: LocalScanEngine, token: ScanToken): ScanState = withTimeout(20_000) {
        val state = engine.state.filterNotNull().first { it.token == token && it.phase in
            setOf(ScanPhase.COMPLETED, ScanPhase.CANCELLED, ScanPhase.PAUSED_BACKGROUND, ScanPhase.FAILED, ScanPhase.INTERRUPTED) }
        scope.coroutineContext[Job]!!.children.toList().joinAll()
        state
    }
    private suspend fun onlyAvailability(): LibraryAvailability {
        val view = library.openView()
        return try { library.page(view).tracks.single().availability } finally { library.closeView(view.viewId) }
    }
    private suspend fun expect(issue: SourceReadIssue, action: suspend () -> Any?) {
        try { action(); throw AssertionError("Expected $issue") }
        catch (error: SourceReadException) { assertEquals(issue, error.issue) }
    }

    private class Graph(val spec: LocalSourceSpec) : LocalSourceAccess {
        val grant = LocalGrantObservation(read = ObservedCapability.AVAILABLE, enumerate = ObservedCapability.AVAILABLE)
        val children = mutableMapOf<String, List<ObservedCandidate>>()
        val failParents = mutableSetOf<String>()
        val opened = mutableListOf<String>()
        var providerVersion: String? = null
        var mediaGeneration: Long? = null
        var largestBatch = 0
        var beforeBatch: (suspend () -> Unit)? = null
        val root get() = node(spec.rootObjectId!!, null, LocalNodeKind.DIRECTORY, "Root")

        fun node(id: String, parent: String?, kind: LocalNodeKind = LocalNodeKind.AUDIO, name: String = "$id.mp3") =
            ObservedCandidate(spec.sourceId, id, "content://${spec.authority}/tree/${spec.rootObjectId}/document/$id", parent,
                name, if (kind == LocalNodeKind.AUDIO) "audio/mpeg" else null, kind, sizeBytes = if (kind == LocalNodeKind.AUDIO) 123 else null,
                entityProof = "document:${spec.authority}:$id")

        override suspend fun observe(source: LocalSourceSpec): LocalSourceObservation =
            LocalSourceObservation(spec, AndroidLocalSources.sourceIdentity(spec), grant, root, providerVersion, mediaGeneration)

        override suspend fun open(source: LocalSourceSpec, parentObjectId: String?, sinceMediaGeneration: Long?): LocalCandidateCursor {
            val parent = parentObjectId ?: spec.rootObjectId!!
            opened += parent
            if (parent in failParents) throw SourceReadException(SourceReadIssue.SOURCE_UNAVAILABLE)
            val rows = children[parent].orEmpty()
            return object : LocalCandidateCursor {
                var offset = 0
                override var exhausted = false
                override suspend fun nextBatch(limit: Int): List<ObservedCandidate> {
                    beforeBatch?.invoke()
                    val end = minOf(rows.size, offset + limit)
                    val page = rows.subList(offset, end)
                    offset = end
                    exhausted = offset == rows.size
                    largestBatch = maxOf(largestBatch, page.size)
                    return page
                }
                override fun close() { exhausted = true }
            }
        }
    }
}
