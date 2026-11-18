package io.github.onlinemo.meloxwatch.ui.playlists

import androidx.lifecycle.SavedStateHandle
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCatalogFence
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCommit
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistFolderBinding
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistKind
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistOperationResult
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistQueueAvailability
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistSnapshot
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistViewToken
import io.github.onlinemo.meloxwatch.playback.PlaylistProducerFixture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeout

/** 控制器测试替身只提供已校验的类型，不证明真实文档提交、扫描或SQL fence。 */
internal class PlaylistUiTestFixture(val scope: CoroutineScope, count: Long = 205L, val saved: SavedStateHandle = SavedStateHandle()) {
    val ports = PlaylistProducerFixture(count)
    val folders = TestFolderController(this)
    val keywords = TestKeywordController(this)
    val exchange = TestPlaylistExchangeController(this)
    val model = newModel()

    fun newModel(handle: SavedStateHandle = saved): PlaylistsViewModel = PlaylistsViewModel(
        ports.playlists, ports.library, ports.mapper, folders, keywords, exchange, handle, scope,
        closeLibraryView = ports.library::closeView,
    )

    fun copySavedState(): SavedStateHandle = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })

    suspend fun showDetails() {
        model.onVisible()
        await { it.catalogPage != null && !it.loading }
        model.openPlaylist(checkNotNull(ports.playlists.detail.value))
        await { it.view != null && it.screen == PlaylistScreen.DETAILS && !it.loading }
    }

    suspend fun await(predicate: (PlaylistUiState) -> Boolean): PlaylistUiState =
        withTimeout(5_000L) { model.uiState.first(predicate) }

    suspend fun close(vararg additional: PlaylistsViewModel) {
        model.onHidden()
        additional.forEach { it.onHidden() }
        // Capture before the timeout becomes a child of the shared runBlocking scope.
        // Enumerating inside its block would make cleanup join its own deadline job.
        val pending = scope.coroutineContext[Job]?.children?.toList().orEmpty()
        withTimeout(5_000L) { pending.joinAll() }
    }

    fun revision(): PlaylistPreviewRevision = PlaylistPreviewRevision(ports.library.overviewState.value.revision,
        ports.library.overviewState.value.rulesRevision)

    fun create(id: String, name: String, count: Long, kind: PlaylistKind = PlaylistKind.ORDINARY,
        binding: PlaylistFolderBinding? = null): PlaylistCommit {
        val snapshot = PlaylistSnapshot(id, "created-generation", 1L, name, kind, count, 1L, 2L, binding)
        ports.playlists.detail.value = snapshot
        return PlaylistCommit(id, 1L, PlaylistOperationResult.CREATED, snapshot, false, addedCount = count)
    }

    internal class TestFolderController(private val fixture: PlaylistUiTestFixture) : FolderPlaylistController {
        val previews = mutableListOf<PlaylistFolderChoice>()
        val creates = mutableListOf<FolderPlaylistCreateRequest>()
        val syncs = mutableListOf<FolderPlaylistUpdateRequest>()
        val rebindings = mutableListOf<FolderPlaylistUpdateRequest>()
        val closed = mutableListOf<String>()
        private val previewById = mutableMapOf<String, FolderPlaylistPreview>()
        var previewResult: suspend (PlaylistFolderChoice) -> FolderPlaylistPreview = { choice ->
            val revision = fixture.revision()
            FolderPlaylistPreview("folder-preview-${previews.size}", choice, PlaylistCatalogFence(revision.catalogRevision,
                revision.rulesRevision, choice.binding.sourceId, 1L, 1L, "scan-complete"), 205L, 2L, 1L)
        }

        override suspend fun preview(choice: PlaylistFolderChoice, base: PlaylistSnapshot?): FolderPlaylistPreview {
            previews.add(choice)
            val result = previewResult(choice)
            previewById[result.previewId] = result
            return result
        }

        override suspend fun create(request: FolderPlaylistCreateRequest): PlaylistCommit {
            creates.add(request)
            val preview = checkNotNull(previewById[request.previewId])
            return fixture.create(request.playlistId, request.name, preview.memberCount, PlaylistKind.FOLDER, preview.choice.binding)
        }

        override suspend fun synchronize(request: FolderPlaylistUpdateRequest): PlaylistCommit {
            syncs.add(request)
            val snapshot = checkNotNull(fixture.ports.playlists.detail.value)
            return PlaylistCommit(snapshot.playlistId, snapshot.revision, PlaylistOperationResult.UNCHANGED, snapshot, false)
        }

        override suspend fun rebind(request: FolderPlaylistUpdateRequest): PlaylistCommit {
            rebindings.add(request)
            val snapshot = checkNotNull(fixture.ports.playlists.detail.value)
            val preview = checkNotNull(previewById[request.previewId])
            val updated = snapshot.copy(revision = snapshot.revision + 1L, generationId = "rebound-generation",
                folderBinding = preview.choice.binding, memberCount = preview.memberCount)
            fixture.ports.playlists.detail.value = updated
            return PlaylistCommit(updated.playlistId, updated.revision, PlaylistOperationResult.REBOUND, updated, false)
        }

        override suspend fun closePreview(previewId: String) {
            closed.add(previewId)
            previewById.remove(previewId)
        }
    }

    internal class TestKeywordController(private val fixture: PlaylistUiTestFixture) : KeywordPlaylistController {
        val previews = mutableListOf<String>()
        val creates = mutableListOf<KeywordPlaylistCreateRequest>()
        val closed = mutableListOf<String>()
        var broad = false
        var count = 205L
        var previewResult: suspend (String) -> KeywordPlaylistPreview = { keyword ->
            KeywordPlaylistPreview("keyword-preview-${previews.size}", keyword, fixture.revision(), count, broad)
        }
        var createResult: suspend (KeywordPlaylistCreateRequest) -> PlaylistCommit = { request ->
            fixture.create(request.playlistId, request.name, count)
        }

        override suspend fun preview(keyword: String): KeywordPlaylistPreview {
            previews.add(keyword)
            return previewResult(keyword)
        }

        override suspend fun create(request: KeywordPlaylistCreateRequest): PlaylistCommit {
            creates.add(request)
            return createResult(request)
        }

        override suspend fun closePreview(previewId: String) { closed.add(previewId) }
    }

    internal class TestPlaylistExchangeController(private val fixture: PlaylistUiTestFixture) : PlaylistExchangeController {
        val imports = mutableListOf<AuthorizedPlaylistDocument>()
        val confirmations = mutableListOf<PlaylistImportConfirmation>()
        val pages = mutableListOf<Long>()
        val closed = mutableListOf<String>()
        val exports = mutableListOf<String>()
        var memberCount = 205L
        var unresolvedCount = 3L
        var inspectResult: suspend (PlaylistTransferFormat, PlaylistTransferEncoding, AuthorizedPlaylistDocument,
            PlaylistImportTarget?) -> PlaylistImportPreview = { format, encoding, _, target ->
            PlaylistImportPreview("import-preview-${imports.size}", format, encoding, "Imported", if (format == PlaylistTransferFormat.JSON) 1 else null,
                fixture.revision(), target, memberCount, memberCount - unresolvedCount, unresolvedCount, unresolvedCount, 0L, 0L)
        }
        var exportResult: suspend (String, PlaylistViewToken) -> PlaylistExportReceipt = { id, view ->
            PlaylistExportReceipt(id, view.snapshot.memberCount, 1_000L, "0".repeat(64), "Test output")
        }

        override suspend fun inspectImport(format: PlaylistTransferFormat, encoding: PlaylistTransferEncoding,
            authorizedDocument: AuthorizedPlaylistDocument, target: PlaylistImportTarget?): PlaylistImportPreview {
            imports.add(authorizedDocument)
            return inspectResult(format, encoding, authorizedDocument, target)
        }

        override suspend fun importPage(previewId: String, firstOrdinal: Long, limit: Int): PlaylistImportPage {
            pages.add(firstOrdinal)
            val end = minOf(memberCount, firstOrdinal + limit)
            val rows = (firstOrdinal until end).map { ordinal ->
                val unresolved = ordinal >= memberCount - unresolvedCount
                PlaylistImportRow(ordinal, "Imported $ordinal", null, if (unresolved) PlaylistQueueAvailability.UNRESOLVED else
                    PlaylistQueueAvailability.AVAILABLE, if (unresolved) PlaylistImportMemberIssue.AMBIGUOUS else PlaylistImportMemberIssue.NONE)
            }
            return PlaylistImportPage(previewId, firstOrdinal, rows, if (end == memberCount) null else end)
        }

        override suspend fun confirmImport(request: PlaylistImportConfirmation): PlaylistCommit {
            confirmations.add(request)
            return when (val destination = request.destination) {
                is PlaylistImportDestination.New -> fixture.create(destination.playlistId, destination.name, memberCount)
                is PlaylistImportDestination.Merge -> {
                    val snapshot = checkNotNull(fixture.ports.playlists.detail.value)
                    PlaylistCommit(snapshot.playlistId, snapshot.revision, PlaylistOperationResult.ADDED, snapshot, false)
                }
            }
        }

        override suspend fun export(operationId: String, view: PlaylistViewToken, authorizedDestination: AuthorizedPlaylistDocument,
            format: PlaylistTransferFormat, encoding: PlaylistTransferEncoding): PlaylistExportReceipt {
            exports.add(operationId)
            return exportResult(operationId, view)
        }

        override suspend fun closePreview(previewId: String) { closed.add(previewId) }
    }
}
