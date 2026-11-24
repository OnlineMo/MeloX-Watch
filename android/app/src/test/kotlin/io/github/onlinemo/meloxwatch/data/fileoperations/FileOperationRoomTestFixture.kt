package io.github.onlinemo.meloxwatch.data.fileoperations

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.library.LibraryCursor
import io.github.onlinemo.meloxwatch.data.library.LibraryQuery
import io.github.onlinemo.meloxwatch.data.library.LibrarySource
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.library.RoomLocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.local.AndroidLocalSources
import io.github.onlinemo.meloxwatch.data.local.LocalNodeKind
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.queue.RoomQueueStore
import io.github.onlinemo.meloxwatch.data.scan.MetadataOutcome
import io.github.onlinemo.meloxwatch.data.scan.ScanCounters
import io.github.onlinemo.meloxwatch.data.scan.ScanCoverage
import io.github.onlinemo.meloxwatch.data.scan.ScanMode
import io.github.onlinemo.meloxwatch.data.fileoperations.fixture.FixtureDocumentTree
import io.github.onlinemo.meloxwatch.data.fileoperations.fixture.fixtureFileTarget
import io.github.onlinemo.meloxwatch.playback.PlaybackEngineReleaseGuard
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before

/** 真实 Room-6/native SQLite、主库桥、journal 与共享队列 owner；测试 dispatcher 不代表设备服务已释放。 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class FileOperationRoomTestFixture : FileOperationPhysicalTestFixture() {
    protected lateinit var database: MeloXDatabase
    protected lateinit var library: RoomLocalLibraryRepository
    protected lateinit var bridge: RoomFileOperationLibrary
    protected lateinit var queues: RoomQueueStore
    protected lateinit var io: LocalProviderIo
    protected lateinit var journal: FileOperationJournal
    protected lateinit var guard: FileOperationMutationGuard
    protected lateinit var host: FileOperationPlaybackHost
    private lateinit var releaseGuard: PlaybackEngineReleaseGuard
    protected lateinit var source: LibrarySource
    protected var readinessIssue: FileOperationIssue? = null

    @Before fun openRoomAndJournal() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        database = Room.databaseBuilder(context, MeloXDatabase::class.java, File(temporaryFolder.root, "file-operation-library.db").absolutePath)
            .setDriver(BundledSQLiteDriver()).build()
        library = RoomLocalLibraryRepository(database, { 1_000L }, { 1_794_787_200_000L })
        library.initialize()
        library.setShortAudioFilter(library.rules().revision, false)
        bridge = RoomFileOperationLibrary(library) { }
        queues = RoomQueueStore(database)
        queues.initialize()
        io = LocalProviderIo()
        journal = io.read { SqliteFileOperationJournal.open(File(temporaryFolder.root, "file-operation-journal.db")) }
        guard = FileOperationMutationGuard(journal, io)
        host = FileOperationPlaybackHost()
        host.updateRecoveryBlock(false)
        releaseGuard = PlaybackEngineReleaseGuard()
    }

    protected suspend fun scan(vararg documentIds: String) {
        val first = fixtureFileTarget(context, authority, tree, documentIds.first())
        val template = checkNotNull(first.descriptor).source
        source = library.registerSource(template.spec, template.grant)
        val token = library.beginScan(source.spec.sourceId, source.authEpoch, library.rules().revision, ScanMode.FULL)
        val root = ObservedCandidate(source.spec.sourceId, FixtureDocumentTree.ROOT_ID, source.spec.uri, null, tree.root.name,
            "vnd.android.document/directory", LocalNodeKind.DIRECTORY)
        val candidates = documentIds.map { id ->
            val descriptor = checkNotNull(fixtureFileTarget(context, authority, tree, id).descriptor)
            ObservedCandidate(source.spec.sourceId, id, descriptor.location.uri, FixtureDocumentTree.ROOT_ID, tree.name(id),
                if (tree.name(id).endsWith(".flac")) "audio/flac" else "audio/mpeg", LocalNodeKind.AUDIO, tree.file(id).length(),
                providerContentToken = "fixture-file:$id")
        }
        library.commitSeen(token, listOf(root))
        for (page in candidates.chunked(100)) library.commitSeen(token, page)
        for (page in documentIds.toList().chunked(100)) {
            library.commitMetadata(token, page.map { MetadataOutcome(it, LocalAudioMetadata(title = "Before", artists = listOf("Before artist"), album = "Keep album")) })
        }
        while (true) {
            val next = library.nextDirectory(token) ?: break
            library.completeDirectory(token, next.objectId, true)
        }
        val coverage = ScanCoverage(true, true, true, true, false)
        library.setCoverage(token, coverage)
        var after: String? = null
        while (true) {
            val page = library.stageAbsence(token, after)
            if (page.complete) break
            after = page.nextAliasId
        }
        library.finishScan(token, ScanCounters(), coverage)
        source = checkNotNull(library.source(source.spec.sourceId))
    }

    protected suspend fun selected(id: String): FileSelectedTrack {
        val alias = checkNotNull(database.libraryDao().alias(source.spec.sourceId, id))
        val view = library.openView(LibraryQuery(includeHidden = true))
        try {
            var cursor: LibraryCursor? = null
            var selected: FileSelectedTrack? = null
            do {
                val page = library.page(view, cursor)
                for (track in page.tracks) if (track.aliasId == alias.aliasId) {
                    check(selected == null) { "The selected alias must resolve to one complete-view track" }
                    selected = FileSelectedTrack(track.trackKey, track.location?.locationId, track.location?.contentRevision)
                }
                cursor = page.nextCursor
            } while (cursor != null)
            return checkNotNull(selected) { "The selected alias must exist in the complete frozen view" }
        } finally { library.closeView(view.viewId) }
    }

    protected fun repository(journalPort: FileOperationJournal = journal, libraryPort: FileOperationLibrary = bridge): FileOperationsRepository {
        val references = RoomFileOperationQueueReferences(library, bridge, queues, AndroidLocalSources(context, io)::validateRead, journalPort, io)
        return FileOperationsRepository(libraryPort, journalPort, io, guard, adapter,
            ServiceClosingFileOperationGate(host, releaseGuard), references,
            { readinessIssue?.let { throw FileOperationException(it) } }, { })
    }

    protected suspend fun prepare(port: FileOperationsRepository, selected: FileSelectedTrack, command: FileOperationCommand): FileOperationPreview {
        val token = FileSelectionToken("room-file-operation-view", library.overview().revision, 1L)
        return port.prepare(UUID.randomUUID().toString(), token, command, FileSelectionPageSource { offset, limit ->
            assertEquals(0L, offset)
            assertEquals(100, limit)
            FileSelectionPage(token, offset, listOf(selected), true)
        })
    }

    protected fun confirmation(preview: FileOperationPreview) = FileOperationConfirmation(preview.operationId, preview.binding,
        preview.kind, true, true, true)

    protected suspend fun expectSuspendIssue(expected: FileOperationIssue, action: suspend () -> Unit) {
        try { action() } catch (failure: FileOperationException) {
            assertEquals(expected, failure.issue)
            return
        }
        throw AssertionError("Expected $expected")
    }

    @After fun closeRoomAndJournal() = runBlocking {
        try {
            io.awaitIdle()
            io.read { journal.close() }
            database.close()
        } finally { Dispatchers.resetMain() }
    }
}
