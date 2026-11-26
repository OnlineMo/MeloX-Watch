package io.github.onlinemo.meloxwatch.ui.playlists

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.onlinemo.meloxwatch.data.library.LibraryCursor
import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryFolder
import io.github.onlinemo.meloxwatch.data.library.LibraryFolderPage
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.library.LibraryLimits
import io.github.onlinemo.meloxwatch.data.library.LibraryPage
import io.github.onlinemo.meloxwatch.data.library.LibraryQuery
import io.github.onlinemo.meloxwatch.data.library.LibrarySource
import io.github.onlinemo.meloxwatch.data.library.LibrarySourceState
import io.github.onlinemo.meloxwatch.data.library.LibraryTrack
import io.github.onlinemo.meloxwatch.data.library.LibraryViewOwner
import io.github.onlinemo.meloxwatch.data.library.LibraryViewToken
import io.github.onlinemo.meloxwatch.data.library.LocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.local.allowsFileReadValidation
import io.github.onlinemo.meloxwatch.data.playlists.FrozenPlaylistInput
import io.github.onlinemo.meloxwatch.data.playlists.LocalPlaylistRepository
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistException
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistFolderBinding
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistInputPage
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistLimits
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMember
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutation
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutationRequest
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistOperationResult
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistPageSource
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistReference
import io.github.onlinemo.meloxwatch.data.queue.QueuePageSource
import io.github.onlinemo.meloxwatch.data.queue.QueueStore
import io.github.onlinemo.meloxwatch.data.queue.QueueStoreException
import io.github.onlinemo.meloxwatch.data.playlistsexchange.PlaylistExchangeException
import io.github.onlinemo.meloxwatch.data.playlistsexchange.PlaylistExchangeIssue
import io.github.onlinemo.meloxwatch.model.QueueMode
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** MainActivity delegates this port to the real Android document registry. Tokens are runtime only. */
interface PlaylistDocumentPort {
    suspend fun registerImport(uri: Uri, returnedFlags: Int, rootBinding: PlaylistFolderBinding? = null): AuthorizedPlaylistDocument
    suspend fun registerExport(uri: Uri, returnedFlags: Int, operationId: String): AuthorizedPlaylistDocument
    suspend fun discard(selectionId: String)
}

enum class PlaylistEntrySurface { OTHER, LIBRARY, PLAYBACK, PLAYLISTS }
enum class PlaylistPickerScreen { NONE, FOLDER, IMPORT_ROOT, REPLACEMENT, REASSOCIATION, REAUTHORIZE }
enum class PlaylistEntryNotice { PICKER_CANCELLED, NO_PICKER, REASSOCIATED }
enum class PlaylistDocumentPurpose { IMPORT, EXPORT }

data class PlaylistDocumentRequest(
    val requestId: String,
    val generation: Long,
    val purpose: PlaylistDocumentPurpose,
    val operationId: String,
    val format: PlaylistTransferFormat,
    val encoding: PlaylistTransferEncoding,
    val root: PlaylistFolderChoice? = null,
    val suggestedName: String? = null,
)

data class PlaylistEntryUiState(
    val screen: PlaylistPickerScreen = PlaylistPickerScreen.NONE,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val issue: PlaylistUiIssue? = null,
    val notice: PlaylistEntryNotice? = null,
    val queue: PlaylistQueueBinding? = null,
    val mode: QueueMode? = null,
    val queueIssue: PlaylistUiIssue? = null,
    val favoriteReference: PlaylistReference? = null,
    val playingTrack: LibraryTrack? = null,
    val favorite: Boolean? = null,
    val favoritePending: Boolean = false,
    val favoriteIssue: PlaylistUiIssue? = null,
    val addPreparing: Boolean = false,
    val folder: LibraryFolder? = null,
    val folders: LibraryFolderPage? = null,
    val search: String = "",
    val replacements: LibraryPage? = null,
    val recovery: PlaylistRecoveryTarget? = null,
    val reauthorization: LibrarySource? = null,
    val reassociation: PlaylistReassociationPreview? = null,
    val reassociationConfirmed: Boolean = false,
    val previewStale: Boolean = false,
    val documentRequest: PlaylistDocumentRequest? = null,
    val pickerPending: Boolean = false,
)

/**
 * Owns watch entry points and picker grants, while PlaylistsViewModel owns relationship drafts.
 * STOP releases index views and observers. A launched picker keeps only its exact runtime intent;
 * actual navigation, cancellation and completed transfers retire its document token.
 */
class PlaylistEntryViewModel(
    private val model: PlaylistsViewModel,
    private val playlists: LocalPlaylistRepository,
    private val library: LocalLibraryRepository,
    private val queues: QueueStore,
    private val documents: PlaylistDocumentPort,
    private val reassociation: PlaylistRecoveryController,
    private val scopeOverride: CoroutineScope? = null,
    private val closeLibraryView: suspend (String) -> Unit,
) : ViewModel() {
    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope
    private val mutableState = MutableStateFlow(PlaylistEntryUiState())
    val uiState = mutableState.asStateFlow()
    private var visible = false
    private var surface = PlaylistEntrySurface.OTHER
    private val visibleSurface = MutableStateFlow<PlaylistEntrySurface?>(null)
    private var visibilityGeneration = 0L
    private var intentGeneration = 0L
    private var observer: Job? = null
    private var queueObserver: Job? = null
    private var favoriteObserver: Job? = null
    private var favoriteWrite: Job? = null
    private var favoriteGeneration = 0L
    private var favoriteRetry: PlaylistMutationRequest? = null
    private var libraryFavorite: PlaylistReference? = null
    private var playingEntryId: String? = null
    private var latestQueue: QueueSnapshot? = null
    private var playingResolution: Job? = null
    private var resolvedPlaying: Triple<String, Long, String>? = null
    private var retiredPlayingView: LibraryViewToken? = null
    private val playingLeaseMutex = Mutex()
    private var documentJob: Job? = null
    private var documentCleanup: Job? = null
    private var flight: DocumentFlight? = null
    private var requestAnchor: DocumentAnchor? = null
    private val ownedDocuments = LinkedHashSet<String>()
    private var chooserGeneration = 0L
    private var chooserJob: Job? = null
    private var chooser: ChooserSession? = null
    private val retiredChoosers = ArrayDeque<ChooserSession>()
    private var addJob: Job? = null
    private var pendingAdd: PlaylistAddSelection? = null
    private var retiredSelection: PlaylistAddSelection? = null
    private var cleanupJob: Job? = null
    private var chooserIntent: ChooserIntent? = null

    fun onVisible(nextSurface: PlaylistEntrySurface) {
        if (visible && surface == nextSurface) return
        if (visible) onHidden()
        if (surface == PlaylistEntrySurface.PLAYLISTS && nextSurface != surface) leavePlaylists()
        visible = true
        surface = nextSurface
        visibleSurface.value = nextSurface
        val generation = ++visibilityGeneration
        if (surface == PlaylistEntrySurface.PLAYLISTS) {
            pruneDocuments(model.uiState.value)
            observer = scope.launch {
                launch {
                    model.uiState.collect { state ->
                        if (!currentVisibility(generation)) return@collect
                        pruneDocuments(state)
                        invalidateChangedIntent(state)
                    }
                }
                launch {
                    try {
                        library.observeOverview().collect { overview ->
                            if (!currentVisibility(generation)) return@collect
                            val preview = mutableState.value.reassociation ?: return@collect
                            val changed = preview.revision.catalogRevision != overview.revision ||
                                preview.revision.rulesRevision != overview.rulesRevision
                            if (changed) mutableState.update { it.copy(previewStale = true, reassociationConfirmed = false) }
                        }
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) {
                        if (currentVisibility(generation)) {
                            mutableState.update { it.copy(previewStale = true, reassociationConfirmed = false, issue = entryIssue(error)) }
                        }
                    }
                }
            }
        }
        if (surface in setOf(PlaylistEntrySurface.PLAYLISTS, PlaylistEntrySurface.PLAYBACK)) {
            startQueueObserver(generation)
        }
        if (surface == PlaylistEntrySurface.LIBRARY) watchFavorite(libraryFavorite)
    }

    fun retryQueueRead() {
        if (visible && surface in setOf(PlaylistEntrySurface.PLAYLISTS, PlaylistEntrySurface.PLAYBACK) && queueObserver?.isActive != true) {
            startQueueObserver(visibilityGeneration)
        }
    }

    private fun startQueueObserver(generation: Long) {
        mutableState.update { it.copy(queue = null, mode = null, queueIssue = null) }
        queueObserver = scope.launch {
            try {
                queues.snapshots().distinctUntilChanged { left, right -> sameQueueIntent(left, right) }.collect { snapshot ->
                    if (!currentVisibility(generation)) return@collect
                    latestQueue = snapshot
                    // null from the store is an observed empty queue; a failed or unread store stays unknown.
                    mutableState.update { it.copy(queue = snapshot?.let {
                        PlaylistQueueBinding(it.generationId, it.queueRevision, it.currentEntryId, it.entryCount)
                    } ?: PlaylistQueueBinding(null, 0L, null, 0L), mode = snapshot?.mode ?: QueueMode(), queueIssue = null) }
                    if (surface == PlaylistEntrySurface.PLAYBACK) resolvePlayingFavorite()
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (currentVisibility(generation)) {
                    latestQueue = null
                    playingResolution?.cancel()
                    resolvedPlaying = null
                    mutableState.update { it.copy(queue = null, mode = null, playingTrack = null, queueIssue = entryIssue(error)) }
                    watchFavorite(null)
                }
            }
        }
    }

    fun onHidden() {
        visible = false
        visibleSurface.value = null
        visibilityGeneration += 1L
        observer?.cancel(); observer = null
        queueObserver?.cancel(); queueObserver = null
        playingResolution?.cancel()
        latestQueue = null
        resolvedPlaying = null
        stopFavorite()
        closePicker()
        if (pendingAdd == null) addJob?.cancel()
        mutableState.update { it.copy(queue = null, mode = null, playingTrack = null, queueIssue = null) }
    }

    fun setPlayingEntry(entryId: String?) {
        if (playingEntryId == entryId) return
        playingEntryId = entryId
        resolvedPlaying = null
        mutableState.update { it.copy(playingTrack = null) }
        if (surface == PlaylistEntrySurface.PLAYBACK) watchFavorite(null)
        if (visible && surface == PlaylistEntrySurface.PLAYBACK) resolvePlayingFavorite()
    }

    fun setLibraryFavorite(reference: PlaylistReference?) {
        libraryFavorite = reference
        if (visible && surface == PlaylistEntrySurface.LIBRARY) watchFavorite(reference)
    }

    fun watchFavorite(reference: PlaylistReference?) {
        val allowed = visible && surface in setOf(PlaylistEntrySurface.LIBRARY, PlaylistEntrySurface.PLAYBACK)
        val selected = reference?.takeIf { allowed && it.trackKey != null }
        if (mutableState.value.favoriteReference == selected && favoriteObserver?.isActive == true) return
        stopFavorite()
        mutableState.update { it.copy(favoriteReference = selected, favorite = null, favoriteIssue = null) }
        val key = selected?.trackKey ?: return
        val generation = ++favoriteGeneration
        favoriteObserver = scope.launch {
            try {
                playlists.initialize()
                playlists.observeFavorite(key).collect { favorite ->
                    if (generation == favoriteGeneration && visible && !mutableState.value.favoritePending) {
                        mutableState.update { it.copy(favorite = favorite) }
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (generation == favoriteGeneration) mutableState.update { it.copy(favoriteIssue = entryIssue(error)) }
            }
        }
    }

    fun toggleFavorite() {
        val state = mutableState.value
        val reference = state.favoriteReference ?: return
        val key = reference.trackKey ?: return
        val previous = state.favorite ?: return
        if (!visible || state.favoritePending) return
        val generation = favoriteGeneration
        val enabled = !previous
        mutableState.update { it.copy(favorite = enabled, favoritePending = true, favoriteIssue = null) }
        favoriteWrite = scope.launch {
            try {
                val mutation = PlaylistMutation.SetFavorite(reference, enabled)
                val retry = favoriteRetry
                val request = if (retry?.mutation == mutation) retry else {
                    val snapshot = playlists.snapshot(PlaylistLimits.FAVORITES_ID) ?: throw PlaylistException(PlaylistIssue.NOT_FOUND)
                    PlaylistMutationRequest(newId(), snapshot.playlistId, snapshot.revision, mutation)
                }
                favoriteRetry = request
                playlists.apply(request)
                val actual = playlists.isFavorite(key)
                if (generation == favoriteGeneration && visible) {
                    favoriteRetry = null
                    mutableState.update { it.copy(favorite = actual, favoritePending = false, favoriteIssue = null) }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (generation == favoriteGeneration && visible) {
                    if (error is PlaylistException && error.issue == PlaylistIssue.CONFLICT) favoriteRetry = null
                    mutableState.update { it.copy(favorite = previous, favoritePending = false, favoriteIssue = entryIssue(error)) }
                }
            }
        }
    }

    fun retryFavoriteRead() {
        if (!visible || mutableState.value.favoritePending) return
        stopFavorite()
        if (surface == PlaylistEntrySurface.LIBRARY) watchFavorite(libraryFavorite)
        if (surface == PlaylistEntrySurface.PLAYBACK) {
            resolvedPlaying = null
            resolvePlayingFavorite()
        }
    }

    private fun stopFavorite() {
        favoriteGeneration += 1L
        favoriteObserver?.cancel(); favoriteObserver = null
        favoriteWrite?.cancel(); favoriteWrite = null
        favoriteRetry = null
        mutableState.update { it.copy(favoriteReference = null, favorite = null, favoritePending = false, favoriteIssue = null) }
    }

    private fun resolvePlayingFavorite() {
        val previous = playingResolution
        previous?.cancel()
        val snapshot = latestQueue
        val instance = playingEntryId
        if (!visible || surface != PlaylistEntrySurface.PLAYBACK || snapshot == null || instance == null ||
            snapshot.currentEntryId != instance) {
            mutableState.update { it.copy(playingTrack = null) }
            watchFavorite(null)
            return
        }
        val binding = Triple(snapshot.generationId, snapshot.queueRevision, instance)
        if (resolvedPlaying == binding && mutableState.value.favoriteReference != null) return
        mutableState.update { it.copy(playingTrack = null) }
        watchFavorite(null)
        val generation = visibilityGeneration
        playingResolution = scope.launch {
            previous?.join()
            chooserJob?.takeIf { !it.isActive }?.join()
            playingLeaseMutex.withLock {
                var view: LibraryViewToken? = null
                try {
                    currentCoroutineContext().ensureActive()
                    closeRetiredPlayingView()
                    val window = queues.window(snapshot.generationId, instance)
                    val entry = window.current
                    if (window.generationId != snapshot.generationId || window.queueRevision != snapshot.queueRevision ||
                        window.totalCount != snapshot.entryCount || entry.queueEntryId != instance) {
                        throw PlaylistException(PlaylistIssue.SOURCE_CHANGED)
                    }
                    val key = entry.trackKey
                    if (key.providerId != TrackKey.LOCAL_PROVIDER) return@withLock
                    // The short DB acquisition must return its token before cancellation can discard ownership.
                    view = withContext(NonCancellable) { library.openView(LibraryQuery(includeHidden = true)) }
                    val found = library.locate(checkNotNull(view), key)?.track ?: return@withLock
                    if (found.trackKey != key) throw PlaylistException(PlaylistIssue.SOURCE_CHANGED)
                    currentCoroutineContext().ensureActive()
                    if (currentVisibility(generation) && surface == PlaylistEntrySurface.PLAYBACK && playingEntryId == instance &&
                        latestQueue?.generationId == snapshot.generationId && latestQueue?.queueRevision == snapshot.queueRevision &&
                        latestQueue?.currentEntryId == instance) {
                        resolvedPlaying = binding
                        mutableState.update { it.copy(playingTrack = found) }
                        watchFavorite(found.playlistReference())
                    }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    if (currentVisibility(generation) && surface == PlaylistEntrySurface.PLAYBACK && playingEntryId == instance &&
                        latestQueue?.generationId == snapshot.generationId && latestQueue?.queueRevision == snapshot.queueRevision) {
                        watchFavorite(null)
                        mutableState.update { it.copy(playingTrack = null, favoriteIssue = entryIssue(error)) }
                    }
                } finally {
                    view?.let { opened -> withContext(NonCancellable) {
                        try { closeWithinDeadline { closeLibraryView(opened.viewId) } }
                        catch (error: Exception) { retiredPlayingView = opened; fail(entryIssue(error)) }
                    } }
                }
            }
        }
    }

    private suspend fun closeRetiredPlayingView() {
        retiredPlayingView?.let { retired ->
            closeWithinDeadline { closeLibraryView(retired.viewId) }
            retiredPlayingView = null
        }
    }

    private suspend fun closeWithinDeadline(close: suspend () -> Unit) {
        try { withTimeout(CLOSE_TIMEOUT_MS) { close() } }
        catch (error: TimeoutCancellationException) { throw PlaylistException(PlaylistIssue.BUSY, error) }
    }

    fun addFromLibrary(view: LibraryViewToken, selected: TrackKey?, label: String?, showPlaylists: () -> Unit) {
        if (!visible || surface != PlaylistEntrySurface.LIBRARY) return
        prepareAdd(showPlaylists) {
            val adopted = withContext(NonCancellable) { library.openQueueView(view.viewId) }
            val leaseOwner = LibraryPlaylistAddSelection(library, adopted, label, closeLibraryView)
            var transferred = false
            try {
                if (adopted.copy(viewId = view.viewId, owner = view.owner) != view || adopted.owner != LibraryViewOwner.QUEUE) {
                    throw LibraryException(LibraryIssue.SOURCE_CHANGED)
                }
                val selection = if (selected == null) leaseOwner else {
                    val track = library.locate(adopted, selected)?.track ?: throw LibraryException(LibraryIssue.SOURCE_CHANGED)
                    if (track.trackKey != selected) throw LibraryException(LibraryIssue.SOURCE_CHANGED)
                    ReferencePlaylistAddSelection(track.playlistReference(), adopted.revision, label, leaseOwner::close)
                }
                transferred = true
                selection
            } finally {
                if (!transferred) closeSelection(leaseOwner)
            }
        }
    }

    fun addFromMember(member: PlaylistMember) {
        val state = model.uiState.value
        if (!visible || surface != PlaylistEntrySurface.PLAYLISTS || !state.canUseView ||
            state.selected?.member != member || member.reference.trackKey == null) return
        prepareAdd({}) { ReferencePlaylistAddSelection(member.reference, checkNotNull(state.view).snapshot.revision, member.reference.titleHint) }
    }

    fun addPlaying(showPlaylists: () -> Unit) {
        val reference = mutableState.value.favoriteReference ?: return
        val snapshot = latestQueue ?: return
        if (!visible || surface != PlaylistEntrySurface.PLAYBACK || snapshot.currentEntryId != playingEntryId) return
        prepareAdd(showPlaylists) { ReferencePlaylistAddSelection(reference, snapshot.queueRevision, reference.titleHint) }
    }

    private fun prepareAdd(showPlaylists: () -> Unit, create: suspend () -> PlaylistAddSelection) {
        if (addJob?.isActive == true || pendingAdd != null || mutableState.value.addPreparing || retiredSelection != null || cleanupJob?.isActive == true) {
            fail(PlaylistUiIssue.BUSY); return
        }
        val generation = visibilityGeneration
        mutableState.update { it.copy(addPreparing = true, issue = null, notice = null) }
        addJob = scope.launch {
            var selection: PlaylistAddSelection? = null
            try {
                val acquired = create()
                selection = acquired
                currentCoroutineContext().ensureActive()
                if (!currentVisibility(generation) || acquired.input.totalCount == 0L) throw PlaylistException(PlaylistIssue.SOURCE_CHANGED)
                pendingAdd = acquired
                showPlaylists()
                withTimeout(ADD_HANDOFF_TIMEOUT_MS) {
                    visibleSurface.first { it == PlaylistEntrySurface.PLAYLISTS }
                    model.uiState.first { state -> !state.loading && !state.saving && !state.showingPreviousData &&
                        (state.catalogPage != null || state.canUseView || state.issue != null) }
                }
                currentCoroutineContext().ensureActive()
                if (surface != PlaylistEntrySurface.PLAYLISTS || !visible || !model.beginAdd(acquired)) throw PlaylistException(PlaylistIssue.BUSY)
                pendingAdd = null
                selection = null
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { fail(entryIssue(error)) }
            finally {
                if (pendingAdd === selection) pendingAdd = null
                selection?.let { closeSelection(it) }
                mutableState.update { it.copy(addPreparing = false) }
            }
        }
    }

    private suspend fun closeSelection(selection: PlaylistAddSelection) = withContext(NonCancellable) {
        try { closeWithinDeadline { selection.close() } }
        catch (error: Exception) { retiredSelection = selection; fail(entryIssue(error)) }
    }

    fun chooseImport(format: PlaylistTransferFormat, encoding: PlaylistTransferEncoding) {
        val state = model.uiState.value
        val draft = state.importDraft ?: return
        if (!canStartIntent() || state.screen != PlaylistScreen.IMPORT || state.saving || state.loading ||
            draft.format != format || draft.encoding != encoding) return
        val anchor = DocumentAnchor.Import(draft.operationId, draft.newPlaylistId, draft.target, format, encoding)
        requestAnchor = anchor
        intentGeneration += 1L
        if (format == PlaylistTransferFormat.JSON) readyDocument(anchor, null)
        else openFolderChooser(anchor)
    }

    fun chooseExport(format: PlaylistTransferFormat, encoding: PlaylistTransferEncoding) {
        if (!canStartIntent()) return
        val state = model.uiState.value
        var draft = state.exportDraft ?: return
        if (state.screen != PlaylistScreen.EXPORT || !state.canUseView || draft.format != format || draft.encoding != encoding) return
        // A new CREATE_DOCUMENT choice is a new export intent; an existing target remains retry-only.
        if (draft.destination != null) {
            model.beginExport(format, encoding)
            draft = model.uiState.value.exportDraft ?: return
        }
        val anchor = DocumentAnchor.Export(draft.operationId, draft.originalView, draft.revision, format, encoding)
        requestAnchor = anchor
        intentGeneration += 1L
        readyDocument(anchor, null)
    }

    private fun readyDocument(anchor: DocumentAnchor, root: PlaylistFolderChoice?) {
        if (!anchor.matches(model.uiState.value) || surface != PlaylistEntrySurface.PLAYLISTS) return
        val request = PlaylistDocumentRequest(newId(), intentGeneration,
            if (anchor is DocumentAnchor.Import) PlaylistDocumentPurpose.IMPORT else PlaylistDocumentPurpose.EXPORT,
            anchor.operationId, anchor.format, anchor.encoding, root,
            if (anchor is DocumentAnchor.Export) suggestedExportName(anchor) else null)
        mutableState.update { it.copy(screen = PlaylistPickerScreen.NONE, loading = false, folders = null, folder = null,
            documentRequest = request, issue = null, notice = null) }
    }

    /** Consuming before launch prevents recomposition/rotation from launching the same picker twice. */
    fun takeDocumentRequest(requestId: String): PlaylistDocumentRequest? {
        val request = mutableState.value.documentRequest ?: return null
        val anchor = requestAnchor ?: return null
        if (!visible || surface != PlaylistEntrySurface.PLAYLISTS || request.requestId != requestId || flight != null ||
            request.generation != intentGeneration || !anchor.matches(model.uiState.value)) return null
        flight = DocumentFlight(request, anchor)
        mutableState.update { it.copy(documentRequest = null, pickerPending = true) }
        return request
    }

    fun pickerUnavailable(requestId: String) {
        if (flight?.request?.requestId != requestId) return
        flight = null
        requestAnchor = null
        mutableState.update { it.copy(pickerPending = false, notice = PlaylistEntryNotice.NO_PICKER) }
    }

    fun documentResult(purpose: PlaylistDocumentPurpose, uri: Uri?, returnedFlags: Int) {
        val launched = flight ?: return
        if (launched.request.purpose != purpose) return
        flight = null
        mutableState.update { it.copy(pickerPending = false) }
        if (uri == null) {
            requestAnchor = null
            mutableState.update { it.copy(notice = PlaylistEntryNotice.PICKER_CANCELLED) }
            return
        }
        if (!currentDocument(launched)) return
        documentJob = scope.launch {
            var selected: AuthorizedPlaylistDocument? = null
            var attached = false
            try {
                selected = when (purpose) {
                    PlaylistDocumentPurpose.IMPORT -> documents.registerImport(uri, returnedFlags, launched.request.root?.binding)
                    PlaylistDocumentPurpose.EXPORT -> documents.registerExport(uri, returnedFlags, launched.request.operationId)
                }
                currentCoroutineContext().ensureActive()
                if (!currentDocument(launched)) return@launch
                ownedDocuments.add(selected.selectionId)
                if (purpose == PlaylistDocumentPurpose.IMPORT) model.inspectImportDocument(selected) else model.exportTo(selected)
                attached = if (purpose == PlaylistDocumentPurpose.IMPORT) model.uiState.value.importDraft?.document == selected
                    else model.uiState.value.exportDraft?.destination == selected
                if (!attached) throw PlaylistException(PlaylistIssue.SOURCE_CHANGED)
                requestAnchor = null
                pruneDocuments(model.uiState.value)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (currentDocument(launched)) fail(entryIssue(error)) }
            finally {
                if (!attached) selected?.let { retireDocument(it.selectionId) }
            }
        }
    }

    private fun canStartIntent(): Boolean {
        if (!visible || surface != PlaylistEntrySurface.PLAYLISTS) return false
        if (flight != null || documentJob?.isActive == true || documentCleanup?.isActive == true || cleanupJob?.isActive == true ||
            mutableState.value.documentRequest != null || retiredChoosers.isNotEmpty() || ownedDocuments.size >= 2) {
            fail(PlaylistUiIssue.BUSY); return false
        }
        return true
    }

    private fun currentDocument(launched: DocumentFlight): Boolean = surface == PlaylistEntrySurface.PLAYLISTS &&
        launched.request.generation == intentGeneration && launched.anchor.matches(model.uiState.value)

    private fun retainedDocumentIds(state: PlaylistUiState): Set<String> =
        if (surface == PlaylistEntrySurface.PLAYLISTS) setOfNotNull(
            state.importDraft?.takeIf { state.screen == PlaylistScreen.IMPORT }?.document?.selectionId,
            state.exportDraft?.takeIf { state.screen == PlaylistScreen.EXPORT && state.exportReceipt == null }?.destination?.selectionId,
        ) else emptySet()

    private fun pruneDocuments(state: PlaylistUiState) {
        val keep = retainedDocumentIds(state)
        val retired = ownedDocuments.filter { it !in keep }
        if (retired.isEmpty() || documentCleanup?.isActive == true) return
        documentCleanup = scope.launch {
            for (id in retired) retireDocument(id)
            documentCleanup = null
            if (retired.none { it in ownedDocuments }) pruneDocuments(model.uiState.value)
        }
    }

    private suspend fun retireDocument(id: String) = withContext(NonCancellable) {
        ownedDocuments.add(id)
        try {
            closeWithinDeadline { documents.discard(id) }
            ownedDocuments.remove(id)
        } catch (error: Exception) { fail(entryIssue(error)) }
    }

    private fun leavePlaylists() {
        intentGeneration += 1L
        requestAnchor = null
        documentJob?.cancel()
        model.onLeave()
        mutableState.update { it.copy(documentRequest = null, notice = null) }
        pruneDocuments(PlaylistUiState())
        closePicker()
    }

    private fun invalidateChangedIntent(state: PlaylistUiState) {
        val anchor = requestAnchor
        if (anchor != null && !anchor.matches(state) && documentJob?.isActive != true) {
            intentGeneration += 1L
            requestAnchor = null
            mutableState.update { it.copy(documentRequest = null) }
            if (mutableState.value.screen == PlaylistPickerScreen.IMPORT_ROOT) closePicker()
        }
        val active = chooser ?: return
        if (!active.anchor.matches(state)) closePicker()
        else if (state.hasNewData && mutableState.value.reassociation != null) {
            mutableState.update { it.copy(previewStale = true, reassociationConfirmed = false) }
        }
    }

    private fun currentVisibility(generation: Long): Boolean = visible && generation == visibilityGeneration
    private fun fail(issue: PlaylistUiIssue) { mutableState.update { it.copy(issue = issue) } }

    fun chooseFolder(purpose: FolderPlaylistPurpose) {
        val state = model.uiState.value
        val draft = state.folderDraft ?: return
        if (!canStartIntent() || state.screen != PlaylistScreen.FOLDER || state.saving || state.loading || draft.purpose != purpose) return
        startChooser(ChooserAnchor.Folder(draft.operationId, draft.purpose, draft.playlistId, draft.expectedRevision), PlaylistPickerScreen.FOLDER)
    }

    private fun openFolderChooser(anchor: DocumentAnchor.Import) = startChooser(ChooserAnchor.Import(anchor), PlaylistPickerScreen.IMPORT_ROOT)

    fun recover(target: PlaylistRecoveryTarget) {
        if (!canStartIntent() || model.recoveryTarget(target.action) != target) return
        startChooser(ChooserAnchor.Recovery(target), if (target.action == PlaylistRecoveryAction.REAUTHORIZE)
            PlaylistPickerScreen.REAUTHORIZE else PlaylistPickerScreen.REPLACEMENT)
    }

    private fun startChooser(anchor: ChooserAnchor, screen: PlaylistPickerScreen, search: String = "") {
        val generation = ++chooserGeneration
        val previous = chooserJob
        previous?.cancel()
        chooser = null
        val owned = ChooserSession(generation, anchor, screen)
        chooserIntent = ChooserIntent(anchor, screen, search)
        mutableState.update { it.copy(screen = screen, loading = true, saving = false, issue = null, notice = null,
            folder = null, folders = null, search = search, replacements = null,
            recovery = (anchor as? ChooserAnchor.Recovery)?.target, reauthorization = null,
            reassociation = null, reassociationConfirmed = false, previewStale = false) }
        chooserJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                previous?.join()
                playingResolution?.takeIf { !it.isActive }?.join()
                playingLeaseMutex.withLock { closeRetiredPlayingView() }
                while (retiredChoosers.isNotEmpty()) {
                    val retired = retiredChoosers.first()
                    if (!closeChooserResources(retired)) throw PlaylistException(PlaylistIssue.BUSY)
                    retiredChoosers.removeFirst()
                }
                currentCoroutineContext().ensureActive()
                if (!currentChooser(owned)) return@launch
                coroutineScope {
                    owned.scope = this
                    chooser = owned
                    library.initialize()
                    owned.view = withContext(NonCancellable) { library.openView(LibraryQuery(search = search, includeHidden = true)) }
                    currentCoroutineContext().ensureActive()
                    if (!currentChooser(owned)) return@coroutineScope
                    when (screen) {
                        PlaylistPickerScreen.FOLDER, PlaylistPickerScreen.IMPORT_ROOT -> readFolders(owned, null, null)
                        PlaylistPickerScreen.REPLACEMENT -> readReplacements(owned, null)
                        PlaylistPickerScreen.REAUTHORIZE -> readReauthorization(owned)
                        else -> throw PlaylistException(PlaylistIssue.INVALID_INPUT)
                    }
                    awaitCancellation()
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (currentChooser(owned)) mutableState.update { it.copy(loading = false, saving = false, issue = entryIssue(error)) }
            }
            finally {
                if (chooser === owned) chooser = null
                owned.scope = null
                if (!closeChooserResources(owned)) retiredChoosers.addLast(owned)
            }
        }
    }

    fun browseFolder(folder: LibraryFolder) {
        val owned = chooser ?: return
        if (!currentChooser(owned) || mutableState.value.loading || mutableState.value.folders?.folders?.none { it == folder } != false) return
        runChooserWork(owned) {
            val exact = library.folder(checkNotNull(owned.view), folder.nodeId)
            if (exact != folder) throw PlaylistException(PlaylistIssue.SOURCE_CHANGED)
            readFolders(owned, folder, null)
        }
    }

    fun parentFolder() {
        val owned = chooser ?: return
        val folder = mutableState.value.folder ?: return
        if (!currentChooser(owned) || mutableState.value.loading) return
        runChooserWork(owned) {
            val parent = folder.parentNodeId?.let { library.folder(checkNotNull(owned.view), it) }
            if (folder.parentNodeId != null && parent == null) throw PlaylistException(PlaylistIssue.SOURCE_CHANGED)
            readFolders(owned, parent, null)
        }
    }

    fun nextFolders() {
        val owned = chooser ?: return
        val state = mutableState.value
        val after = state.folders?.nextNodeId ?: return
        if (!currentChooser(owned) || state.loading) return
        runChooserWork(owned) { readFolders(owned, state.folder, after) }
    }

    fun firstFolders() {
        val owned = chooser ?: return
        if (!currentChooser(owned) || mutableState.value.loading) return
        runChooserWork(owned) { readFolders(owned, mutableState.value.folder, null) }
    }

    private suspend fun readFolders(owned: ChooserSession, parent: LibraryFolder?, after: String?) {
        val view = checkNotNull(owned.view)
        val page = library.folders(view, parent?.nodeId, after, LibraryLimits.PAGE_SIZE)
        currentCoroutineContext().ensureActive()
        if (page.view != view || page.folders.size > LibraryLimits.PAGE_SIZE || page.folders.any { it.parentNodeId != parent?.nodeId }) {
            throw PlaylistException(PlaylistIssue.SOURCE_CHANGED)
        }
        if (currentChooser(owned)) mutableState.update { it.copy(folder = parent, folders = page, loading = false, issue = null) }
    }

    fun selectFolder() {
        val owned = chooser ?: return
        val state = mutableState.value
        val folder = state.folder ?: return
        if (!currentChooser(owned) || state.loading || state.saving) return
        runChooserWork(owned) {
            val exact = library.folder(checkNotNull(owned.view), folder.nodeId)
            val source = library.source(folder.sourceId)
            if (exact != folder || source == null || source.state != LibrarySourceState.ACTIVE ||
                !source.grant.allowsFileReadValidation(source.spec.kind) || source.grant.enumerate != ObservedCapability.AVAILABLE) {
                throw PlaylistException(PlaylistIssue.SOURCE_UNAVAILABLE)
            }
            currentCoroutineContext().ensureActive()
            if (!currentChooser(owned)) return@runChooserWork
            val choice = PlaylistFolderChoice(PlaylistFolderBinding(folder.sourceId, folder.nodeId), folder.name)
            when (val anchor = owned.anchor) {
                is ChooserAnchor.Folder -> { closePicker(); model.chooseFolder(choice) }
                is ChooserAnchor.Import -> { closePicker(); readyDocument(anchor.document, choice) }
                else -> throw PlaylistException(PlaylistIssue.INVALID_INPUT)
            }
        }
    }

    fun changeReplacementSearch(search: String) {
        if (search.length > PlaylistLimits.MAX_FIELD_CHARACTERS || mutableState.value.saving) return
        mutableState.update { it.copy(search = search) }
    }

    fun searchReplacements() {
        val owned = chooser ?: return
        val anchor = owned.anchor as? ChooserAnchor.Recovery ?: return
        if (!currentChooser(owned) || mutableState.value.saving) return
        startChooser(anchor, PlaylistPickerScreen.REPLACEMENT, mutableState.value.search)
    }

    fun nextReplacements() {
        val owned = chooser ?: return
        val state = mutableState.value
        val after = state.replacements?.nextCursor ?: return
        if (!currentChooser(owned) || state.loading || state.saving) return
        runChooserWork(owned) { readReplacements(owned, after) }
    }

    fun previousReplacements() {
        val owned = chooser ?: return
        val state = mutableState.value
        val before = state.replacements?.previousCursor ?: return
        if (!currentChooser(owned) || state.loading || state.saving) return
        runChooserWork(owned) { readReplacements(owned, before, previous = true) }
    }

    private suspend fun readReplacements(owned: ChooserSession, cursor: LibraryCursor?, previous: Boolean = false) {
        val view = checkNotNull(owned.view)
        val page = if (previous) library.previousPage(view, checkNotNull(cursor), LibraryLimits.PAGE_SIZE)
            else library.page(view, cursor, LibraryLimits.PAGE_SIZE)
        currentCoroutineContext().ensureActive()
        if (page.view != view || page.tracks.size > LibraryLimits.PAGE_SIZE) throw PlaylistException(PlaylistIssue.SOURCE_CHANGED)
        if (currentChooser(owned)) mutableState.update { it.copy(replacements = page, loading = false, issue = null) }
    }

    fun previewReplacement(track: LibraryTrack) {
        val owned = chooser ?: return
        val anchor = owned.anchor as? ChooserAnchor.Recovery ?: return
        val state = mutableState.value
        if (!currentChooser(owned) || state.loading || state.saving || state.replacements?.tracks?.none { it == track } != false) return
        runChooserWork(owned) {
            closeReassociationPreview(owned)
            val preview = reassociation.preview(anchor.target, track.trackKey)
            owned.previewId = preview.previewId
            owned.operationId = newId()
            currentCoroutineContext().ensureActive()
            val view = checkNotNull(owned.view)
            if (preview.target != anchor.target || preview.replacement != track.trackKey ||
                preview.revision != PlaylistPreviewRevision(view.revision, view.rulesRevision)) throw PlaylistException(PlaylistIssue.SOURCE_CHANGED)
            if (currentChooser(owned)) mutableState.update { it.copy(screen = PlaylistPickerScreen.REASSOCIATION,
                reassociation = preview, reassociationConfirmed = false, previewStale = false, loading = false, issue = null) }
        }
    }

    fun confirmReassociationChoice(confirmed: Boolean) {
        val state = mutableState.value
        if (visible && state.screen == PlaylistPickerScreen.REASSOCIATION && state.reassociation != null &&
            !state.saving && !state.loading && !state.previewStale) mutableState.update { it.copy(reassociationConfirmed = confirmed) }
    }

    fun chooseAnotherReplacement() {
        val owned = chooser ?: return
        val anchor = owned.anchor as? ChooserAnchor.Recovery ?: return
        if (!currentChooser(owned) || mutableState.value.saving || mutableState.value.loading) return
        startChooser(anchor, PlaylistPickerScreen.REPLACEMENT, mutableState.value.search)
    }

    fun confirmReassociation() {
        val owned = chooser ?: return
        val preview = mutableState.value.reassociation ?: return
        val state = mutableState.value
        if (!currentChooser(owned) || state.loading || state.saving || state.previewStale || !state.reassociationConfirmed ||
            preview.previewId != owned.previewId) return
        runChooserWork(owned, saving = true) {
            val commit = reassociation.confirm(owned.operationId, preview.previewId, confirmed = true)
            if (commit.playlistId != preview.target.view.snapshot.playlistId || commit.result !in setOf(
                    PlaylistOperationResult.REFERENCE_REPLACED, PlaylistOperationResult.UNCHANGED)) throw PlaylistException(PlaylistIssue.SOURCE_CHANGED)
            currentCoroutineContext().ensureActive()
            if (currentChooser(owned)) {
                closePicker()
                mutableState.update { it.copy(notice = PlaylistEntryNotice.REASSOCIATED) }
                model.reload()
            }
        }
    }

    private suspend fun readReauthorization(owned: ChooserSession) {
        val target = (owned.anchor as ChooserAnchor.Recovery).target
        val key = target.member.reference.trackKey ?: throw PlaylistException(PlaylistIssue.SOURCE_UNAVAILABLE)
        val track = library.locate(checkNotNull(owned.view), key)?.track ?: throw PlaylistException(PlaylistIssue.SOURCE_UNAVAILABLE)
        if (track.trackKey != key) throw PlaylistException(PlaylistIssue.SOURCE_CHANGED)
        val source = track.sourceId?.let { library.source(it) } ?: throw PlaylistException(PlaylistIssue.SOURCE_UNAVAILABLE)
        currentCoroutineContext().ensureActive()
        if (currentChooser(owned)) mutableState.update { it.copy(reauthorization = source, loading = false, issue = null) }
    }

    fun openReauthorization(openSource: (LibrarySource) -> Unit) {
        val owned = chooser ?: return
        val source = mutableState.value.reauthorization ?: return
        if (!currentChooser(owned) || mutableState.value.loading || mutableState.value.saving) return
        openSource(source)
        closePicker()
    }

    fun closePicker(): Boolean {
        val open = mutableState.value.screen != PlaylistPickerScreen.NONE
        chooserGeneration += 1L
        chooserJob?.cancel()
        chooser = null
        chooserIntent = null
        mutableState.update { it.copy(screen = PlaylistPickerScreen.NONE, loading = false, saving = false, folder = null,
            folders = null, replacements = null, recovery = null, reauthorization = null, reassociation = null,
            reassociationConfirmed = false, previewStale = false) }
        return open
    }

    fun back(): Boolean {
        if (mutableState.value.screen == PlaylistPickerScreen.NONE) return false
        if (!mutableState.value.saving) closePicker()
        return true
    }

    fun clearNotice() { mutableState.update { it.copy(notice = null, issue = null) } }

    fun retryCleanup() {
        if (mutableState.value.saving || documentCleanup?.isActive == true || cleanupJob?.isActive == true ||
            documentJob?.isActive == true || addJob?.isActive == true) return
        val retryChooser = chooserIntent?.takeIf { mutableState.value.issue != null &&
            mutableState.value.screen != PlaylistPickerScreen.REASSOCIATION }
        if (retryChooser != null) closePicker()
        cleanupJob = scope.launch {
            try {
                chooserJob?.takeIf { retryChooser != null || !it.isActive }?.join()
                while (retiredChoosers.isNotEmpty()) {
                    if (!closeChooserResources(retiredChoosers.first())) return@launch
                    retiredChoosers.removeFirst()
                }
                retiredSelection?.let { selection ->
                    closeWithinDeadline { selection.close() }
                    retiredSelection = null
                }
                playingLeaseMutex.withLock { closeRetiredPlayingView() }
                pruneDocuments(model.uiState.value)
                documentCleanup?.join()
                val state = model.uiState.value
                if (ownedDocuments.any { it !in retainedDocumentIds(state) }) return@launch
                mutableState.update { it.copy(issue = null) }
                if (retryChooser != null && visible && surface == PlaylistEntrySurface.PLAYLISTS &&
                    retryChooser.anchor.matches(model.uiState.value)) {
                    startChooser(retryChooser.anchor, retryChooser.screen, retryChooser.search)
                }
                if (visible && surface == PlaylistEntrySurface.PLAYBACK && mutableState.value.favoriteReference == null) {
                    resolvedPlaying = null
                    resolvePlayingFavorite()
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { fail(entryIssue(error)) }
        }
    }

    private fun currentChooser(owned: ChooserSession): Boolean = visible && surface == PlaylistEntrySurface.PLAYLISTS &&
        owned.generation == chooserGeneration && owned.anchor.matches(model.uiState.value)

    private fun runChooserWork(owned: ChooserSession, saving: Boolean = false, block: suspend () -> Unit) {
        val parent = owned.scope ?: return
        val previous = owned.work
        previous?.cancel()
        val generation = ++owned.workGeneration
        mutableState.update { it.copy(loading = !saving, saving = saving, issue = null) }
        owned.work = parent.launch {
            try {
                previous?.join()
                currentCoroutineContext().ensureActive()
                block()
            }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (currentChooser(owned) && owned.workGeneration == generation) {
                    val issue = entryIssue(error)
                    val stale = mutableState.value.reassociation != null && issue in setOf(
                        PlaylistUiIssue.CONFLICT, PlaylistUiIssue.SOURCE_CHANGED, PlaylistUiIssue.VIEW_EXPIRED,
                        PlaylistUiIssue.SOURCE_UNAVAILABLE, PlaylistUiIssue.INCOMPLETE_SCAN,
                    )
                    mutableState.update { it.copy(issue = issue, previewStale = it.previewStale || stale,
                        reassociationConfirmed = if (stale) false else it.reassociationConfirmed) }
                }
            }
            finally {
                if (currentChooser(owned) && owned.workGeneration == generation) mutableState.update { it.copy(loading = false, saving = false) }
            }
        }
    }

    private suspend fun closeReassociationPreview(owned: ChooserSession) {
        owned.previewId?.let { id -> reassociation.closePreview(id); owned.previewId = null }
    }

    private suspend fun closeChooserResources(owned: ChooserSession): Boolean = withContext(NonCancellable) {
        var closed = true
        try {
            closeWithinDeadline { closeReassociationPreview(owned) }
        } catch (error: Exception) { fail(entryIssue(error)); closed = false }
        try {
            closeWithinDeadline { owned.view?.let { view -> closeLibraryView(view.viewId); owned.view = null } }
        } catch (error: Exception) { fail(entryIssue(error)); closed = false }
        closed
    }

    override fun onCleared() {
        onHidden()
        leavePlaylists()
        addJob?.cancel()
        cleanupJob?.cancel()
        scope.launch(NonCancellable) {
            try {
                withTimeout(CLOSE_TIMEOUT_MS * 4L) {
                    chooserJob?.join()
                    addJob?.join()
                    documentJob?.join()
                    documentCleanup?.join()
                    cleanupJob?.join()
                    playingResolution?.join()
                }
            } catch (error: Exception) { fail(entryIssue(error)) }
            for (owned in retiredChoosers.toList()) closeChooserResources(owned)
            retiredSelection?.let { closeSelection(it) }
            try {
                closeWithinDeadline { playingLeaseMutex.withLock { closeRetiredPlayingView() } }
            } catch (error: Exception) { fail(entryIssue(error)) }
            for (id in ownedDocuments.toList()) retireDocument(id)
        }
        super.onCleared()
    }

    // Chooser operations share one retained index view and never enumerate the external provider.
    private sealed interface ChooserAnchor {
        fun matches(state: PlaylistUiState): Boolean
        data class Folder(val operationId: String, val purpose: FolderPlaylistPurpose, val playlistId: String,
            val revision: Long) : ChooserAnchor {
            override fun matches(state: PlaylistUiState): Boolean = state.screen == PlaylistScreen.FOLDER &&
                state.folderDraft?.let { it.operationId == operationId && it.purpose == purpose && it.playlistId == playlistId &&
                    it.expectedRevision == revision } == true
        }
        data class Import(val document: DocumentAnchor.Import) : ChooserAnchor {
            override fun matches(state: PlaylistUiState): Boolean = document.matches(state)
        }
        data class Recovery(val target: PlaylistRecoveryTarget) : ChooserAnchor {
            override fun matches(state: PlaylistUiState): Boolean = state.screen == PlaylistScreen.MEMBER && state.view == target.view &&
                state.selected?.member == target.member && state.selected?.availability == target.availability && state.canUseView
        }
    }

    private sealed interface DocumentAnchor {
        val operationId: String
        val format: PlaylistTransferFormat
        val encoding: PlaylistTransferEncoding
        fun matches(state: PlaylistUiState): Boolean
        data class Import(override val operationId: String, val newPlaylistId: String, val target: PlaylistImportTarget?,
            override val format: PlaylistTransferFormat, override val encoding: PlaylistTransferEncoding) : DocumentAnchor {
            override fun matches(state: PlaylistUiState): Boolean = state.screen == PlaylistScreen.IMPORT && state.importDraft?.let {
                it.operationId == operationId && it.newPlaylistId == newPlaylistId && it.target == target && it.format == format && it.encoding == encoding
            } == true
        }
        data class Export(override val operationId: String, val view: io.github.onlinemo.meloxwatch.data.playlists.PlaylistViewToken,
            val revision: PlaylistPreviewRevision, override val format: PlaylistTransferFormat,
            override val encoding: PlaylistTransferEncoding) : DocumentAnchor {
            override fun matches(state: PlaylistUiState): Boolean = state.screen == PlaylistScreen.EXPORT && state.exportDraft?.let {
                it.operationId == operationId && it.originalView == view && it.revision == revision && it.format == format && it.encoding == encoding
            } == true
        }
    }

    private data class DocumentFlight(val request: PlaylistDocumentRequest, val anchor: DocumentAnchor)
    private data class ChooserIntent(val anchor: ChooserAnchor, val screen: PlaylistPickerScreen, val search: String)
    private fun suggestedExportName(anchor: DocumentAnchor.Export): String {
        val base = anchor.view.snapshot.name.replace(Regex("[\\\\/\\p{Cntrl}]"), "_").trim().ifBlank { "playlist" }
        val suffix = when (anchor.format) {
            PlaylistTransferFormat.JSON -> ".json"
            PlaylistTransferFormat.M3U -> ".m3u"
            PlaylistTransferFormat.M3U8 -> ".m3u8"
        }
        return base + suffix
    }
    private class ChooserSession(val generation: Long, val anchor: ChooserAnchor, val screen: PlaylistPickerScreen) {
        var scope: CoroutineScope? = null
        var view: LibraryViewToken? = null
        var previewId: String? = null
        var operationId: String = newId()
        var work: Job? = null
        var workGeneration = 0L
    }

    companion object {
        private const val CLOSE_TIMEOUT_MS = 2_500L
        private const val ADD_HANDOFF_TIMEOUT_MS = 8_000L
        private fun newId(): String = UUID.randomUUID().toString()
    }
}

/** Copies one already indexed stable reference; its source can be replayed without reopening media. */
internal class ReferencePlaylistAddSelection(reference: PlaylistReference, revision: Long, override val label: String?,
    private val onClose: suspend () -> Unit = {},
) : PlaylistAddSelection {
    init { require(reference.trackKey != null) }
    private val frozenReference = reference.copy()
    override val input = FrozenPlaylistInput(UUID.randomUUID().toString(), revision, 1L)
    private var closed = false
    override val source = PlaylistPageSource { offset, limit ->
        check(!closed)
        require(offset in 0L..1L && limit in 1..PlaylistLimits.PAGE_SIZE)
        PlaylistInputPage(input, offset, if (offset == 0L) listOf(frozenReference) else emptyList(), true)
    }
    override suspend fun close() { if (!closed) { onClose(); closed = true } }
}

/** The entire exact catalog lease is streamed in order; a failed add restarts the same lease at zero. */
internal class LibraryPlaylistAddSelection(
    private val library: LocalLibraryRepository,
    private val view: LibraryViewToken,
    override val label: String?,
    private val closeLibraryView: suspend (String) -> Unit,
) : PlaylistAddSelection {
    override val input = FrozenPlaylistInput(UUID.randomUUID().toString(), view.revision, view.totalCount)
    private val mutex = Mutex()
    private var closed = false
    private var delegate: QueuePageSource? = null
    private var nextOffset = 0L
    private var lastPage: PlaylistInputPage? = null
    private var lastLimit = 0
    override val source = PlaylistPageSource { offset, limit -> mutex.withLock {
        check(!closed)
        require(offset in 0L..input.totalCount && limit in 1..PlaylistLimits.PAGE_SIZE)
        val cached = lastPage
        if (cached?.offset == offset && lastLimit == limit) return@withLock cached
        if (offset == 0L) { delegate = library.queueSource(view); nextOffset = 0L; lastPage = null }
        if (offset != nextOffset) throw PlaylistException(PlaylistIssue.INVALID_INPUT)
        try {
            val page = checkNotNull(delegate).read(offset, minOf(limit.toLong(), input.totalCount - offset).toInt().coerceAtLeast(1))
            val size = minOf(limit.toLong(), input.totalCount - offset).toInt()
            if (page.view.id != view.viewId || page.view.revision != view.revision || page.view.totalCount != view.totalCount ||
                page.offset != offset || page.entries.size != size || page.entries.withIndex().any { it.value.originalOrder != offset + it.index }) {
                throw PlaylistException(PlaylistIssue.INCOMPLETE)
            }
            val references = page.entries.map { entry ->
                val key = entry.trackKey
                if (key.providerId != TrackKey.LOCAL_PROVIDER) throw PlaylistException(PlaylistIssue.SOURCE_CHANGED)
                PlaylistReference(trackKey = key, titleHint = entry.displayName, durationMs = entry.durationMs)
            }
            nextOffset = offset + references.size
            PlaylistInputPage(input, offset, references, nextOffset == input.totalCount).also { lastPage = it; lastLimit = limit }
        } catch (error: Exception) {
            // A sequential source may have advanced before failing. Retry rebuilds it on this exact lease.
            delegate = null
            nextOffset = 0L
            lastPage = null
            throw error
        }
    } }
    override suspend fun close() = mutex.withLock {
        if (!closed) { closeLibraryView(view.viewId); closed = true; delegate = null; lastPage = null }
    }
}

fun LibraryTrack.playlistReference(): PlaylistReference = PlaylistReference(trackKey = trackKey, titleHint = title,
    artistHint = metadata.artist, albumHint = metadata.album, durationMs = metadata.durationMs)

private fun sameQueueIntent(left: QueueSnapshot?, right: QueueSnapshot?): Boolean =
    left?.generationId == right?.generationId && left?.queueRevision == right?.queueRevision &&
        left?.currentEntryId == right?.currentEntryId && left?.entryCount == right?.entryCount && left?.mode == right?.mode

private fun entryIssue(error: Exception): PlaylistUiIssue = when (error) {
    is TimeoutCancellationException -> PlaylistUiIssue.BUSY
    is PlaylistException -> when (error.issue) {
        PlaylistIssue.INCOMPLETE -> PlaylistUiIssue.INCOMPLETE_SCAN
        PlaylistIssue.REVISION_EXHAUSTED -> PlaylistUiIssue.RECOVERY_REQUIRED
        PlaylistIssue.DUPLICATE_REFERENCE -> PlaylistUiIssue.CONFLICT
        else -> PlaylistUiIssue.entries.firstOrNull { it.name == error.issue.name } ?: PlaylistUiIssue.SOURCE_UNAVAILABLE
    }
    is LibraryException -> when (error.issue) {
        LibraryIssue.INCOMPLETE -> PlaylistUiIssue.INCOMPLETE_SCAN
        LibraryIssue.PERMISSION_REVOKED, LibraryIssue.FILE_MISSING -> PlaylistUiIssue.SOURCE_UNAVAILABLE
        else -> PlaylistUiIssue.entries.firstOrNull { it.name == error.issue.name } ?: PlaylistUiIssue.SOURCE_UNAVAILABLE
    }
    is QueueStoreException -> PlaylistUiIssue.entries.firstOrNull { it.name == error.issue.name } ?: PlaylistUiIssue.SOURCE_UNAVAILABLE
    is PlaylistExchangeException -> when (error.issue) {
        PlaylistExchangeIssue.UNSUPPORTED_FORMAT, PlaylistExchangeIssue.UNSUPPORTED_VERSION -> PlaylistUiIssue.UNSUPPORTED_DOCUMENT
        PlaylistExchangeIssue.LIMIT_EXCEEDED -> PlaylistUiIssue.DOCUMENT_TOO_LARGE
        PlaylistExchangeIssue.READ_FAILED -> PlaylistUiIssue.DOCUMENT_READ
        PlaylistExchangeIssue.WRITE_FAILED -> PlaylistUiIssue.DOCUMENT_WRITE
        else -> PlaylistUiIssue.INVALID_DOCUMENT
    }
    is IllegalArgumentException -> PlaylistUiIssue.INVALID_INPUT
    else -> PlaylistUiIssue.STORAGE_UNAVAILABLE
}
