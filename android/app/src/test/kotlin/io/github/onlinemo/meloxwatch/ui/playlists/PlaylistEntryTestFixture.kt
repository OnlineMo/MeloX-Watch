package io.github.onlinemo.meloxwatch.ui.playlists

import android.net.Uri
import io.github.onlinemo.meloxwatch.data.library.LibraryAvailability
import io.github.onlinemo.meloxwatch.data.library.LibraryCursor
import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryFolder
import io.github.onlinemo.meloxwatch.data.library.LibraryFolderPage
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.library.LibraryLocation
import io.github.onlinemo.meloxwatch.data.library.LibraryPage
import io.github.onlinemo.meloxwatch.data.library.LibrarySource
import io.github.onlinemo.meloxwatch.data.library.LibrarySourceState
import io.github.onlinemo.meloxwatch.data.library.LibraryTrack
import io.github.onlinemo.meloxwatch.data.library.LibraryViewToken
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.library.LocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.local.LocalGrantObservation
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCommit
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistFolderBinding
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistOperationResult
import io.github.onlinemo.meloxwatch.data.queue.QueueCommit
import io.github.onlinemo.meloxwatch.data.queue.QueueInputPage
import io.github.onlinemo.meloxwatch.data.queue.QueuePageSource
import io.github.onlinemo.meloxwatch.data.queue.QueueReplacementRequest
import io.github.onlinemo.meloxwatch.data.queue.QueueStore
import io.github.onlinemo.meloxwatch.model.FrozenQueueView
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import io.github.onlinemo.meloxwatch.model.QueueCheckpoint
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueMode
import io.github.onlinemo.meloxwatch.model.QueueMutationCandidate
import io.github.onlinemo.meloxwatch.model.QueueMutationRequest
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import io.github.onlinemo.meloxwatch.model.QueueWindow
import io.github.onlinemo.meloxwatch.model.TrackKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/** Timing and ownership fixtures only; they do not prove Android provider, Room or device behavior. */
internal class PlaylistEntryTestFixture(parent: CoroutineScope) {
    private val task = SupervisorJob(parent.coroutineContext[Job])
    val scope = CoroutineScope(parent.coroutineContext + task)
    val ui = PlaylistUiTestFixture(scope)
    val library = EntryLibrary(ui.ports.library)
    val queues = EntryQueueStore()
    val documents = EntryDocuments()
    val recovery = EntryRecovery(ui)
    val entry = PlaylistEntryViewModel(ui.model, ui.ports.playlists, library, queues, documents, recovery, scope,
        closeLibraryView = library::closeView)

    suspend fun showCatalog() {
        ui.model.onVisible()
        entry.onVisible(PlaylistEntrySurface.PLAYLISTS)
        ui.await { it.catalogPage != null && !it.loading }
    }

    suspend fun importRequest(format: PlaylistTransferFormat = PlaylistTransferFormat.JSON): PlaylistDocumentRequest {
        ui.model.beginImport(format)
        entry.chooseImport(format, PlaylistTransferEncoding.UTF8)
        return checkNotNull(entry.uiState.value.documentRequest)
    }

    suspend fun await(predicate: (PlaylistEntryUiState) -> Boolean): PlaylistEntryUiState =
        withTimeout(5_000L) { entry.uiState.first(predicate) }

    suspend fun close() {
        entry.onVisible(PlaylistEntrySurface.OTHER)
        entry.onHidden()
        ui.model.onHidden()
        withTimeout(5_000L) { task.cancelAndJoin() }
    }
}

internal suspend fun awaitEntryCondition(predicate: () -> Boolean) = withTimeout(5_000L) {
    while (!predicate()) yield()
}

internal class EntryDocuments : PlaylistDocumentPort {
    data class Registration(val uri: Uri, val flags: Int, val root: PlaylistFolderBinding?, val operationId: String?)
    val registered = mutableListOf<Registration>()
    val discarded = mutableListOf<String>()
    var discardFailures = 0
    var registration: suspend (AuthorizedPlaylistDocument) -> AuthorizedPlaylistDocument = { it }
    override suspend fun registerImport(uri: Uri, returnedFlags: Int, rootBinding: PlaylistFolderBinding?): AuthorizedPlaylistDocument {
        registered.add(Registration(uri, returnedFlags, rootBinding, null))
        return registration(AuthorizedPlaylistDocument("document-${registered.size}", "Input"))
    }
    override suspend fun registerExport(uri: Uri, returnedFlags: Int, operationId: String): AuthorizedPlaylistDocument {
        registered.add(Registration(uri, returnedFlags, null, operationId))
        return registration(AuthorizedPlaylistDocument("document-${registered.size}", "Output"))
    }
    override suspend fun discard(selectionId: String) {
        if (discardFailures > 0) {
            discardFailures -= 1
            throw LibraryException(LibraryIssue.STORAGE_UNAVAILABLE)
        }
        discarded.add(selectionId)
    }
}

internal class EntryLibrary(private val base: LocalLibraryRepository) : LocalLibraryRepository by base {
    val root = LibraryFolder("indexed-root", "tree", "Library root", null)
    val child = LibraryFolder("indexed-child", "tree", "Subfolder", root.nodeId)
    var sourceState = LibrarySourceState.ACTIVE
    var sourceRead = ObservedCapability.AVAILABLE
    var sourceEnumerate = ObservedCapability.AVAILABLE
    var missingTrack = false
    var locateGate: CompletableDeferred<Unit>? = null
    val locateCalls = mutableListOf<TrackKey>()
    var beforeLocate: suspend (TrackKey) -> Unit = { locateGate?.await(); Unit }
    val queueSourceViews = mutableListOf<LibraryViewToken>()
    val queueOffsets = mutableListOf<Long>()
    val queueSizes = mutableListOf<Int>()
    var failQueueOffset: Long? = null
    var pageTransform: (QueueInputPage) -> QueueInputPage = { it }

    fun track(ordinal: Long = 0L): LibraryTrack {
        val key = TrackKey(TrackKey.LOCAL_PROVIDER, "track-$ordinal")
        return LibraryTrack(key, "Indexed title $ordinal", "$ordinal.mp3",
            LocalAudioMetadata(artist = "Indexed artist", album = "Indexed album", durationMs = 120_000L),
            LibraryAvailability.AVAILABLE, null, 1L, ordinal + 1L, "album", location(ordinal), root.sourceId,
            "alias-$ordinal", "node-$ordinal", 0L, 0L)
    }

    private fun location(ordinal: Long) = LocalLocationReference("location-$ordinal", "content://test/$ordinal")

    override suspend fun source(sourceId: String): LibrarySource? = if (sourceId == root.sourceId) LibrarySource(
        LocalSourceSpec(root.sourceId, LocalSourceKind.TREE, "content://source/root", rootObjectId = "object-root"),
        LocalGrantObservation(read = sourceRead, enumerate = sourceEnumerate), "grant", 1L, 1L, sourceState,
        "completed-scan", "completed-scan", "Indexed tree") else null

    override suspend fun folders(view: LibraryViewToken, parentNodeId: String?, afterNodeId: String?, limit: Int): LibraryFolderPage =
        LibraryFolderPage(view, if (afterNodeId != null) emptyList() else when (parentNodeId) {
            null -> listOf(root)
            root.nodeId -> listOf(child)
            else -> emptyList()
        }, null)

    override suspend fun folder(view: LibraryViewToken, nodeId: String): LibraryFolder? =
        listOf(root, child).firstOrNull { it.nodeId == nodeId }

    override suspend fun page(view: LibraryViewToken, after: LibraryCursor?, limit: Int): LibraryPage =
        LibraryPage(view, listOf(track()), null)

    override suspend fun previousPage(view: LibraryViewToken, before: LibraryCursor, limit: Int): LibraryPage =
        LibraryPage(view, listOf(track()), null)

    override suspend fun locate(view: LibraryViewToken, trackKey: TrackKey): LibraryLocation? {
        locateCalls.add(trackKey)
        beforeLocate(trackKey)
        if (missingTrack || trackKey.providerId != TrackKey.LOCAL_PROVIDER) return null
        val ordinal = trackKey.trackId.substringAfterLast('-').toLongOrNull() ?: return null
        return LibraryLocation(track(ordinal), ordinal, null)
    }

    override fun queueSource(view: LibraryViewToken): QueuePageSource {
        queueSourceViews.add(view)
        var next = 0L
        return QueuePageSource { offset, limit ->
            check(offset == next)
            queueOffsets.add(offset)
            if (offset == failQueueOffset) throw LibraryException(LibraryIssue.STORAGE_UNAVAILABLE)
            val end = minOf(view.totalCount, offset + limit)
            val rows = (offset until end).map { index -> QueueEntry("entry-$index",
                TrackKey(TrackKey.LOCAL_PROVIDER, "track-$index"), index, index, "Track $index", 120_000L, location(index)) }
            queueSizes.add(rows.size)
            next = end
            pageTransform(QueueInputPage(FrozenQueueView(view.viewId, view.revision, view.totalCount), offset, rows))
        }
    }
}

internal class EntryQueueStore : QueueStore {
    val state = MutableStateFlow<QueueSnapshot?>(null)
    var gate: CompletableDeferred<Unit>? = null
    var readFailure: Exception? = null
    var windowTransform: (QueueWindow) -> QueueWindow = { it }
    override suspend fun initialize(): QueueSnapshot? = state.value
    override suspend fun snapshot(): QueueSnapshot? = state.value
    override fun snapshots(): Flow<QueueSnapshot?> = flow {
        gate?.await()
        readFailure?.let { throw it }
        state.collect { emit(it) }
    }
    fun current(key: TrackKey = TrackKey(TrackKey.LOCAL_PROVIDER, "track-0")): QueueSnapshot {
        currentKey = key
        return QueueSnapshot("queue", 7L, 1L, FrozenQueueView("view", 3L, 1L), QueueMode(),
            QueueCheckpoint("current", 0L), 0L, 0L).also { state.value = it }
    }
    private var currentKey = TrackKey(TrackKey.LOCAL_PROVIDER, "track-0")
    override suspend fun window(generationId: String, currentEntryId: String): QueueWindow = windowTransform(QueueWindow(
        generationId, checkNotNull(state.value).queueRevision, 1L,
        listOf(QueueEntry(currentEntryId, currentKey, 0L, 0L, "Queue title", 120_000L,
            LocalLocationReference("location-0", "content://test/0"))), 0))
    override suspend fun page(generationId: String, firstPlaybackOrder: Long, limit: Int): List<QueueEntry> =
        error("Unexpected queue page")
    override suspend fun replace(request: QueueReplacementRequest, source: QueuePageSource,
        beforePublish: suspend (QueueWindow?) -> Unit): QueueCommit = error("Unexpected queue replacement")
    override suspend fun mutate(request: QueueMutationRequest, source: QueuePageSource?,
        beforePublish: suspend (QueueMutationCandidate) -> QueueCheckpoint?): QueueCommit = error("Unexpected queue mutation")
    override suspend fun saveCheckpoint(generationId: String, expectedCheckpointRevision: Long,
        checkpoint: QueueCheckpoint): QueueSnapshot = error("Unexpected checkpoint write")
}

internal class EntryRecovery(private val ui: PlaylistUiTestFixture) : PlaylistRecoveryController {
    val previews = mutableListOf<PlaylistReassociationPreview>()
    val confirmations = mutableListOf<Pair<String, String>>()
    val closed = mutableListOf<String>()
    var closeFailures = 0
    var beforePreview: suspend () -> Unit = {}
    var confirm: suspend (String, PlaylistReassociationPreview) -> PlaylistCommit = { _, preview ->
        val snapshot = preview.target.view.snapshot.copy(revision = preview.target.view.snapshot.revision + 1L)
        ui.ports.playlists.detail.value = snapshot
        PlaylistCommit(snapshot.playlistId, snapshot.revision, PlaylistOperationResult.REFERENCE_REPLACED, snapshot, false)
    }
    override suspend fun preview(target: PlaylistRecoveryTarget, replacement: TrackKey): PlaylistReassociationPreview {
        beforePreview()
        return PlaylistReassociationPreview("recovery-${previews.size + 1}", target, replacement, "Indexed title 0", ui.revision())
            .also { previews.add(it) }
    }
    override suspend fun confirm(operationId: String, previewId: String, confirmed: Boolean): PlaylistCommit {
        check(confirmed)
        confirmations.add(operationId to previewId)
        return confirm(operationId, previews.single { it.previewId == previewId })
    }
    override suspend fun closePreview(previewId: String) {
        if (closeFailures > 0) {
            closeFailures -= 1
            throw LibraryException(LibraryIssue.STORAGE_UNAVAILABLE)
        }
        closed.add(previewId)
    }
}
