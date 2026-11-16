package io.github.onlinemo.meloxwatch.data.library

import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.database.withPreservedTransaction
import io.github.onlinemo.meloxwatch.data.local.LocalGrantObservation
import io.github.onlinemo.meloxwatch.data.local.LocalNodeKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.playlists.LocalPlaylistRepository
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCatalogFence
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistFolderBinding
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistFolderSynchronization
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistLibraryRevision
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutation
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutationRequest
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistSnapshot
import io.github.onlinemo.meloxwatch.data.scan.MetadataOutcome
import io.github.onlinemo.meloxwatch.data.scan.ScanCounters
import io.github.onlinemo.meloxwatch.data.scan.ScanCoverage
import io.github.onlinemo.meloxwatch.data.scan.ScanMode
import io.github.onlinemo.meloxwatch.data.scan.ScanState
import io.github.onlinemo.meloxwatch.model.TrackKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse

/** Real scan acknowledgements and Room relationships; no provider, player or device acceptance. */
abstract class FolderPlaylistMaintenanceRoomFixture : PlaylistRecoveryRoomFixture() {
    protected val completeCoverage = ScanCoverage(true, true, true, true, false)

    protected suspend fun binding(objectId: String = "root-object", owner: LibrarySource = source,
        subtree: Boolean = true): PlaylistFolderBinding {
        val node = checkNotNull(database.libraryDao().node(owner.spec.sourceId, objectId))
        return PlaylistFolderBinding(owner.spec.sourceId, node.nodeId, subtree)
    }

    protected fun root(owner: LibrarySource = source): ObservedCandidate = directory(
        checkNotNull(owner.spec.rootObjectId), null, "Music", owner)

    protected fun directory(objectId: String, parent: String?, name: String, owner: LibrarySource = source) =
        ObservedCandidate(owner.spec.sourceId, objectId, "content://recovery-fixture/document/$objectId", parent,
            name, "vnd.android.document/directory", LocalNodeKind.DIRECTORY)

    protected fun audio(objectId: String, name: String, parent: String? = "root-object", owner: LibrarySource = source) =
        ObservedCandidate(owner.spec.sourceId, objectId, "content://recovery-fixture/document/$objectId", parent,
            name, "audio/mpeg", LocalNodeKind.AUDIO, sizeBytes = 128L, providerContentToken = "native:$objectId:1")

    protected fun originalCandidates(): List<ObservedCandidate> = listOf(root(),
        audio("selected-object", "Same.mp3"), audio("decoy-object", "Same.mp3"))

    protected suspend fun refresh(
        candidates: List<ObservedCandidate> = originalCandidates(),
        owner: LibrarySource = source,
        coverage: ScanCoverage = completeCoverage,
        mode: ScanMode = ScanMode.FULL,
        metadataFailureObjectId: String? = null,
    ): ScanState {
        val current = checkNotNull(library.source(owner.spec.sourceId))
        val token = library.beginScan(current.spec.sourceId, current.authEpoch, library.rules().revision, mode)
        candidates.chunked(LibraryLimits.PAGE_SIZE).forEach { library.commitSeen(token, it) }
        candidates.filter { it.kind == LocalNodeKind.AUDIO }.map {
            if (it.objectId == metadataFailureObjectId) MetadataOutcome(it.objectId, issue = SourceReadIssue.METADATA_UNREADABLE)
            else MetadataOutcome(it.objectId, LocalAudioMetadata(title = "Actual ${it.objectId}", durationMs = 120_000L))
        }.chunked(LibraryLimits.PAGE_SIZE).forEach { library.commitMetadata(token, it) }
        while (true) {
            val next = library.nextDirectory(token) ?: break
            library.completeDirectory(token, next.objectId, true)
        }
        library.setCoverage(token, coverage)
        if (mode == ScanMode.FULL && coverage.permitsAbsence) {
            var after: String? = null
            while (true) {
                val page = library.stageAbsence(token, after)
                if (page.complete) break
                after = page.nextAliasId
            }
        }
        val result = library.finishScan(token, ScanCounters(), coverage)
        assertEquals(token, result.token)
        assertEquals(candidates.size.toLong(), result.counters.seen)
        assertEquals(candidates.count { it.kind == LocalNodeKind.AUDIO }.toLong(), result.counters.processed)
        assertEquals(if (metadataFailureObjectId == null) 0L else 1L, result.counters.failed)
        return result
    }

    protected suspend fun track(objectId: String, owner: LibrarySource = source): TrackKey = database.withPreservedTransaction {
        val alias = checkNotNull(database.libraryDao().alias(owner.spec.sourceId, objectId))
        val relation = database.libraryDao().locationRelations(alias.locationId, null, 2).single()
        TrackKey(TrackKey.LOCAL_PROVIDER, relation.trackId)
    }

    protected suspend fun secondSource(): LibrarySource = library.registerSource(
        LocalSourceSpec("other-source", LocalSourceKind.TREE, "content://recovery-fixture/tree/other",
            authority = "recovery-fixture", rootObjectId = "other-root", providerIncarnation = "fixture-instance"),
        LocalGrantObservation(read = ObservedCapability.AVAILABLE, enumerate = ObservedCapability.AVAILABLE))

    protected suspend fun createFolder(id: String = "automatic-folder", selection: PlaylistFolderBinding? = null): PlaylistSnapshot =
        mutateFolder(id, selection ?: binding(), null, playlists)

    protected suspend fun rebindFolder(base: PlaylistSnapshot, selection: PlaylistFolderBinding,
        playlistPort: LocalPlaylistRepository = playlists): PlaylistSnapshot =
        mutateFolder(base.playlistId, selection, base, playlistPort)

    private suspend fun mutateFolder(id: String, selection: PlaylistFolderBinding, base: PlaylistSnapshot?,
        playlistPort: LocalPlaylistRepository): PlaylistSnapshot {
        val view = library.openView(LibraryQuery(sourceId = selection.sourceId, folderNodeId = selection.nodeId,
            folderSubtree = selection.subtree, readableOnly = true), LibrarySort(LibrarySortField.FILENAME_NATURAL))
        try {
            val fence = database.withPreservedTransaction {
                val current = checkNotNull(database.libraryDao().source(selection.sourceId))
                PlaylistCatalogFence(view.revision, view.rulesRevision, selection.sourceId, current.revision,
                    current.authEpoch, checkNotNull(current.latestScanId)).also { RoomPlaylistFences(database).verify(it) }
            }
            val input = LibraryPlaylistInput(library, view)
            val synchronization = PlaylistFolderSynchronization(selection, fence, input.input)
            val operation = if (base == null) "create-$id" else "rebind-$id-${base.revision}"
            val mutation = if (base == null) PlaylistMutation.CreateFolder("Folder $id", synchronization)
                else PlaylistMutation.RebindFolder(synchronization, confirmed = true)
            return checkNotNull(playlistPort.apply(PlaylistMutationRequest(operation, id, base?.revision ?: 0L, mutation,
                PlaylistLibraryRevision(view.revision, view.rulesRevision), fence), input).snapshot)
        } finally { library.closePlaylistView(view.viewId, 5_000L) }
    }

    protected fun maintainer(
        index: LocalLibraryRepository = library,
        playlistPort: LocalPlaylistRepository = playlists,
        closer: suspend (String) -> Unit = { viewId -> library.closePlaylistView(viewId, 5_000L) },
    ) = RoomFolderPlaylistMaintainer(database, playlistPort, index, closer)

    protected fun recordingIndex(): RecordingIndex = RecordingIndex(database, library)

    protected class RecordingIndex(private val database: MeloXDatabase, private val actual: LocalLibraryRepository) :
        LocalLibraryRepository by actual {
        var opened = 0
        var read = 0
        var largestLimit = 0
        var onOpen: suspend () -> Unit = {}
        var onPage: suspend (LibraryViewToken, LibraryPage) -> Unit = { _, _ -> }

        override suspend fun openView(query: LibraryQuery, sort: LibrarySort, owner: LibraryViewOwner): LibraryViewToken {
            assertFalse(database.inTransaction())
            opened++
            onOpen()
            return actual.openView(query, sort, owner)
        }

        override suspend fun page(view: LibraryViewToken, after: LibraryCursor?, limit: Int): LibraryPage {
            assertFalse(database.inTransaction())
            read++
            largestLimit = maxOf(largestLimit, limit)
            val page = actual.page(view, after, limit)
            onPage(view, page)
            return page
        }

        override suspend fun closeView(viewId: String) {
            throw AssertionError("Maintenance must use the explicitly injected bounded Room closer")
        }
    }

    protected suspend fun synchronizedReceipts(): Long = scalar(
        "SELECT COUNT(*) FROM playlist_receipts WHERE operationId LIKE 'folder-scan-v1-%' AND result = 'SYNCHRONIZED'")

    protected suspend fun execute(sql: String) = database.withPreservedTransaction {
        database.openHelper.writableDatabase.execSQL(sql)
    }
}
