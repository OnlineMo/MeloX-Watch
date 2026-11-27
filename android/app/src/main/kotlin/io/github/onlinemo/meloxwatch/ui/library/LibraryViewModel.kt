package io.github.onlinemo.meloxwatch.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.onlinemo.meloxwatch.MeloXApplication
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkRefreshController
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkRefreshPhase
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkRefreshRequest
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkRefreshState
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkException
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkIssue
import io.github.onlinemo.meloxwatch.data.imports.ImportIssue
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.library.LibraryCursor
import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryFolder
import io.github.onlinemo.meloxwatch.data.library.LibraryFolderPage
import io.github.onlinemo.meloxwatch.data.library.LibraryGroup
import io.github.onlinemo.meloxwatch.data.library.LibraryGroupKind
import io.github.onlinemo.meloxwatch.data.library.LibraryGroupPage
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.library.LibraryOverview
import io.github.onlinemo.meloxwatch.data.library.LibraryPage
import io.github.onlinemo.meloxwatch.data.library.LibraryQuery
import io.github.onlinemo.meloxwatch.data.library.LibrarySection
import io.github.onlinemo.meloxwatch.data.library.LibraryTrack
import io.github.onlinemo.meloxwatch.data.library.LibraryViewToken
import io.github.onlinemo.meloxwatch.data.library.LocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesState
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesWriteResult
import io.github.onlinemo.meloxwatch.data.queue.QueueStoreException
import io.github.onlinemo.meloxwatch.data.queue.QueueStoreIssue
import io.github.onlinemo.meloxwatch.model.LibraryLayout
import io.github.onlinemo.meloxwatch.model.LibraryListContext
import io.github.onlinemo.meloxwatch.model.LibrarySort
import io.github.onlinemo.meloxwatch.model.LibrarySortField
import io.github.onlinemo.meloxwatch.model.QueueMode
import io.github.onlinemo.meloxwatch.model.SongTapPolicy
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.model.UserPreferences
import io.github.onlinemo.meloxwatch.playback.LibraryQueueAction
import io.github.onlinemo.meloxwatch.playback.LibraryQueueCommand
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class LibraryScreen { ALL, SEARCH, ALBUMS, ARTISTS, FOLDERS, RECENT, DETAIL, SOURCES, HISTORY, STATISTICS }

data class LibrarySortEdit(val base: UserPreferences, val context: LibraryListContext, val sort: LibrarySort)

private data class LibrarySearchOrigin(
    val screen: LibraryScreen,
    val context: LibraryListContext,
    val query: LibraryQuery,
    val folder: LibraryFolder?,
    val detail: LibraryTrack?,
    val parentScreen: LibraryScreen,
    val anchor: TrackKey?,
)

/** Confirmation stays in memory; restoring navigation must never repeat a playback operation. */
data class LibraryPlayDraft(
    val command: LibraryQueueCommand,
    val previousCount: Long,
    val selectedTitle: String?,
    val submittedAction: LibraryQueueAction? = null,
)

data class LibraryUiState(
    val screen: LibraryScreen = LibraryScreen.ALL,
    val context: LibraryListContext = LibraryListContext.ALL,
    val query: LibraryQuery = LibraryQuery(),
    val searchDraft: String = "",
    val searchSubmitted: Boolean = false,
    val searchInputValid: Boolean = true,
    val overview: LibraryOverview? = null,
    val page: LibraryPage? = null,
    val pageStart: Long = 0L,
    val groupPage: LibraryGroupPage? = null,
    val folderPage: LibraryFolderPage? = null,
    val folder: LibraryFolder? = null,
    val sections: List<LibrarySection> = emptyList(),
    val detail: LibraryTrack? = null,
    val loading: Boolean = false,
    val showingPreviousData: Boolean = false,
    val hasNewData: Boolean = false,
    val issue: LibraryIssue? = null,
    val importRecoveryIssue: ImportIssue? = null,
    val preferences: UserPreferences = UserPreferences(),
    val preferencesWritable: Boolean = false,
    val sortEdit: LibrarySortEdit? = null,
    val sortSaving: Boolean = false,
    val sortConflict: Boolean = false,
    val artworkPreparing: Boolean = false,
    val artworkRefreshPreview: ArtworkRefreshRequest? = null,
    val artworkRefresh: ArtworkRefreshState? = null,
    val artworkRevision: Long = 0L,
    val visibleArtworkCount: Int = 0,
    val artworkSourceIssue: SourceReadIssue? = null,
    val playPreparing: Boolean = false,
    val playDraft: LibraryPlayDraft? = null,
    val playIssue: LibraryIssue? = null,
) {
    val searchIdle: Boolean get() = context == LibraryListContext.SEARCH && !searchSubmitted
    val canReload: Boolean get() = !loading && !searchIdle
    val canPlay: Boolean get() = !searchIdle && !loading && !playPreparing && !showingPreviousData && issue == null && page?.view?.totalCount?.let { it > 0L } == true
    val hasPrevious: Boolean get() = !loading && !showingPreviousData && pageStart > 0L && page?.previousCursor != null
    val hasNext: Boolean get() = !loading && !showingPreviousData && page?.nextCursor != null
    val layout: LibraryLayout get() = preferences.libraryLayout
}

/** A visible page owns one lease; navigation cancels work and releases it before opening another. */
class LibraryViewModel internal constructor(
    application: Application,
    private val saved: SavedStateHandle,
    private val library: LocalLibraryRepository,
) : AndroidViewModel(application) {
    constructor(application: Application, saved: SavedStateHandle) : this(application, saved, (application as MeloXApplication).library)

    private val app = application as MeloXApplication
    private val mutableState = MutableStateFlow(restoredState())
    val uiState = mutableState.asStateFlow()
    private var visible = false
    private var visibilityRevision = 0L
    private var generation = 0L
    private var leaseJob: Job? = null
    private val leaseMutex = Mutex()
    private var pageJob: Job? = null
    private var observers: Job? = null
    private var sortJob: Job? = null
    private var artworkJob: Job? = null
    private var playJob: Job? = null
    private val refreshOwner = lazy { ArtworkRefreshController(app.artworks, app.localProviderIo, viewModelScope) }
    private val refresh get() = refreshOwner.value
    private var visibleArtwork = emptyList<LibraryTrack>()
    private var lastArtworkCompletion: String? = null
    private var view: LibraryViewToken? = null
    private var searchOrigin: LibrarySearchOrigin? = null
    private var parentScreen = LibraryScreen.entries.firstOrNull { it.name == saved.get<String>("library.parent") } ?: LibraryScreen.ALL

    fun onVisible() {
        if (visible) return
        visible = true
        val visibilityToken = ++visibilityRevision
        refresh.onForeground()
        observers = viewModelScope.launch {
            try {
                app.requireBackupStorageReady()
                library.initialize()
                val initial = app.preferences.state.first { it !is PreferencesState.Loading }
                val initialPreferences = when (initial) {
                    is PreferencesState.Ready -> initial.preferences
                    is PreferencesState.Unavailable -> initial.lastKnown
                    PreferencesState.Loading -> null
                }
                if (!isVisible(visibilityToken)) return@launch
                mutableState.update { it.copy(preferences = initialPreferences ?: it.preferences,
                    preferencesWritable = initial is PreferencesState.Ready && initial.canWrite) }
                launch {
                    observeVisible(visibilityToken) { refresh.state.collect { result ->
                        if (!isVisible(visibilityToken)) return@collect
                        val terminal = result?.phase in setOf(ArtworkRefreshPhase.COMPLETED, ArtworkRefreshPhase.CANCELLED,
                            ArtworkRefreshPhase.PAUSED_BACKGROUND, ArtworkRefreshPhase.FAILED)
                        val changed = terminal && result?.refreshId != lastArtworkCompletion
                        if (changed) lastArtworkCompletion = result?.refreshId
                        mutableState.update { it.copy(artworkRefresh = result,
                            artworkRevision = it.artworkRevision + if (changed) 1L else 0L) }
                    } }
                }
                launch {
                    observeVisible(visibilityToken) { library.observeOverview().collect { overview ->
                        if (!isVisible(visibilityToken)) return@collect
                        mutableState.update { state -> state.copy(overview = overview, hasNewData = view?.revision?.let { it != overview.revision } == true) }
                    } }
                }
                launch {
                    observeVisible(visibilityToken) { app.preferences.state.collect { preferences ->
                        if (!isVisible(visibilityToken)) return@collect
                        val confirmed = when (preferences) {
                            is PreferencesState.Ready -> preferences.preferences
                            is PreferencesState.Unavailable -> preferences.lastKnown
                            PreferencesState.Loading -> null
                        }
                        mutableState.update { state -> state.copy(
                            preferences = confirmed ?: state.preferences,
                            preferencesWritable = preferences is PreferencesState.Ready && preferences.canWrite,
                            sortConflict = state.sortEdit?.let { confirmed == null || it.base.revision != confirmed.revision } == true,
                        ) }
                    } }
                }
                reload()
                launch {
                    try {
                        val issue = app.recoverLocalState()
                        if (isVisible(visibilityToken)) mutableState.update { it.copy(importRecoveryIssue = issue) }
                    } catch (error: CancellationException) { throw error }
                    catch (_: Exception) {
                        if (isVisible(visibilityToken)) mutableState.update { it.copy(importRecoveryIssue = ImportIssue.RECOVERY_REQUIRED) }
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (isVisible(visibilityToken)) fail(error) }
        }
    }

    private fun isVisible(token: Long): Boolean = visible && token == visibilityRevision

    private suspend fun observeVisible(token: Long, work: suspend () -> Unit) {
        try { work() }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { if (isVisible(token)) fail(error) }
    }

    fun onHidden() {
        cancelPlay()
        visible = false
        visibilityRevision += 1L
        generation += 1L
        observers?.cancel(); observers = null
        pageJob?.cancel(); pageJob = null
        leaseJob?.cancel()
        view = null
        artworkJob?.cancel(); artworkJob = null
        visibleArtwork = emptyList()
        if (refreshOwner.isInitialized()) refresh.onBackground()
        mutableState.update { it.copy(loading = false, showingPreviousData = it.page != null, sortEdit = null,
            artworkPreparing = false, artworkRefreshPreview = null, visibleArtworkCount = 0) }
        app.artworks.trimUnused()
    }

    fun show(screen: LibraryScreen) {
        if (screen == LibraryScreen.SEARCH) { beginSearch(); return }
        stopArtworkForNavigation()
        searchOrigin = null
        parentScreen = LibraryScreen.ALL
        val context = when (screen) {
            LibraryScreen.RECENT -> LibraryListContext.RECENT
            else -> LibraryListContext.ALL
        }
        val query = when (screen) {
            LibraryScreen.RECENT -> LibraryQuery(addedSinceEpochMs = System.currentTimeMillis() - RECENT_WINDOW_MS)
            else -> LibraryQuery()
        }
        mutableState.update { it.copy(screen = screen, context = context, query = query, detail = null, folder = null,
            sortEdit = null, searchDraft = "", searchSubmitted = false, searchInputValid = true) }
        rememberNavigation()
        openView()
    }

    private fun beginSearch() {
        val state = mutableState.value
        if (state.screen == LibraryScreen.SEARCH) return
        stopArtworkForNavigation()
        if (searchOrigin != null) {
            mutableState.update { it.copy(screen = LibraryScreen.SEARCH, detail = null, sortEdit = null) }
            openView()
            return
        }
        // The source navigation remains saved; a new process restores it without a search term.
        searchOrigin = LibrarySearchOrigin(state.screen, state.context, state.query, state.folder, state.detail,
            parentScreen, state.page?.tracks?.firstOrNull()?.trackKey)
        retireView()
        mutableState.update { it.copy(screen = LibraryScreen.SEARCH, context = LibraryListContext.SEARCH,
            query = LibraryQuery(), searchSubmitted = false, detail = null, folder = null, sortEdit = null,
            page = null, pageStart = 0L, groupPage = null, folderPage = null, sections = emptyList(),
            loading = false, showingPreviousData = false, hasNewData = false, issue = null) }
    }

    fun updateSearch(value: String) {
        beginSearch()
        val valid = value.length <= MAX_SEARCH_LENGTH
        val state = mutableState.value
        if (value == state.searchDraft && valid == state.searchInputValid) return
        stopArtworkForNavigation()
        retireView()
        // Editing invalidates the displayed lease immediately, including rejected oversized input.
        mutableState.update { it.copy(searchDraft = value.take(MAX_SEARCH_LENGTH), searchInputValid = valid,
            searchSubmitted = false, query = LibraryQuery(), page = null, pageStart = 0L, groupPage = null,
            folderPage = null, sections = emptyList(), detail = null, loading = false,
            showingPreviousData = false, hasNewData = false, issue = if (valid) null else LibraryIssue.INVALID_INPUT) }
    }

    fun clearSearch() = updateSearch("")

    fun submitSearch() {
        beginSearch()
        val state = mutableState.value
        if (!state.searchInputValid || state.searchDraft.isBlank()) return
        stopArtworkForNavigation()
        mutableState.update { it.copy(query = LibraryQuery(search = state.searchDraft.trim()), searchSubmitted = true) }
        openView()
    }

    fun cancelSearch() {
        val origin = searchOrigin ?: return
        stopArtworkForNavigation()
        searchOrigin = null
        parentScreen = origin.parentScreen
        mutableState.update { it.copy(screen = origin.screen, context = origin.context, query = origin.query,
            folder = origin.folder, detail = origin.detail, searchDraft = "", searchSubmitted = false,
            searchInputValid = true, sortEdit = null, page = null, pageStart = 0L, groupPage = null,
            folderPage = null, sections = emptyList(), loading = false, showingPreviousData = false,
            hasNewData = false, issue = null) }
        rememberNavigation()
        openView(anchor = origin.anchor)
    }

    fun openGroup(group: LibraryGroup, kind: LibraryGroupKind) {
        stopArtworkForNavigation()
        parentScreen = if (kind == LibraryGroupKind.ALBUM) LibraryScreen.ALBUMS else LibraryScreen.ARTISTS
        val query = if (kind == LibraryGroupKind.ALBUM) LibraryQuery(albumKey = group.key) else LibraryQuery(artistKey = group.key)
        mutableState.update { it.copy(screen = LibraryScreen.ALL, query = query,
            context = if (kind == LibraryGroupKind.ALBUM) LibraryListContext.ALBUM else LibraryListContext.ARTIST,
            sortEdit = null, detail = null) }
        rememberNavigation()
        openView()
    }

    fun openFolder(folder: LibraryFolder) {
        stopArtworkForNavigation()
        mutableState.update { it.copy(screen = LibraryScreen.FOLDERS, context = LibraryListContext.FOLDER,
            query = LibraryQuery(sourceId = folder.sourceId, folderNodeId = folder.nodeId), folder = folder, detail = null, sortEdit = null) }
        rememberNavigation()
        openView()
    }

    fun upFolder() {
        val currentView = view ?: return
        val current = mutableState.value.folder ?: run { show(LibraryScreen.FOLDERS); return }
        pageTask { token ->
            val parent = current.parentNodeId?.let { library.folder(currentView, it) }
            if (token == generation && visible) {
                if (parent == null) show(LibraryScreen.FOLDERS) else openFolder(parent)
            }
        }
    }

    fun openDetail(track: LibraryTrack) {
        if (!mutableState.value.canPlay) return
        stopArtworkForNavigation()
        parentScreen = mutableState.value.screen
        mutableState.update { it.copy(screen = LibraryScreen.DETAIL, detail = track) }
        if (searchOrigin == null) {
            saved["library.detail.provider"] = track.trackKey.providerId
            saved["library.detail.track"] = track.trackKey.trackId
        }
        rememberNavigation()
    }

    fun openTrackFromHistory(key: TrackKey) {
        stopArtworkForNavigation()
        parentScreen = mutableState.value.screen
        saved["library.detail.provider"] = key.providerId
        saved["library.detail.track"] = key.trackId
        mutableState.update { it.copy(screen = LibraryScreen.DETAIL, context = LibraryListContext.ALL,
            query = LibraryQuery(), folder = null, detail = null, sortEdit = null) }
        rememberNavigation()
        openView(anchor = key)
    }

    fun back(): Boolean {
        val state = mutableState.value
        if (state.playDraft != null || state.playPreparing) { cancelPlay(); return true }
        if (state.sortEdit != null) { cancelSort(); return true }
        if (state.screen == LibraryScreen.DETAIL) {
            stopArtworkForNavigation()
            mutableState.update { it.copy(screen = parentScreen, detail = null) }
            rememberNavigation()
            if (parentScreen in setOf(LibraryScreen.SOURCES, LibraryScreen.HISTORY, LibraryScreen.STATISTICS)) openView()
            return true
        }
        if (state.screen == LibraryScreen.SEARCH) { cancelSearch(); return true }
        if (state.screen == LibraryScreen.FOLDERS && state.folder != null) { upFolder(); return true }
        if (state.query.albumKey != null || state.query.artistKey != null) { show(parentScreen); return true }
        if (state.screen != LibraryScreen.ALL) { show(LibraryScreen.ALL); return true }
        return false
    }

    fun reload() = openView(anchor = mutableState.value.page?.tracks?.firstOrNull()?.trackKey)

    fun nextPage() {
        val state = mutableState.value
        val after = state.page?.nextCursor ?: return
        if (state.hasNext) loadPage(after = after, start = state.pageStart + requireNotNull(state.page).tracks.size)
    }

    fun previousPage() {
        val state = mutableState.value
        val before = state.page?.previousCursor ?: return
        if (state.hasPrevious) loadPage(before = before, start = (state.pageStart - 100L).coerceAtLeast(0L))
    }

    fun jumpSection(section: LibrarySection) {
        if (!mutableState.value.canPlay) return
        loadPage(after = section.cursorBefore, determineStart = true)
    }

    fun nextGroups() {
        val state = mutableState.value
        val token = view ?: return
        val key = state.groupPage?.nextKey ?: return
        val kind = if (state.screen == LibraryScreen.ALBUMS) LibraryGroupKind.ALBUM else LibraryGroupKind.ARTIST
        pageTask { generation -> val page = library.groups(token, kind, key); publish(generation) { it.copy(groupPage = page) } }
    }

    fun nextFolders() {
        val state = mutableState.value
        val token = view ?: return
        val key = state.folderPage?.nextNodeId ?: return
        pageTask { generation -> val page = library.folders(token, state.folder?.nodeId, key); publish(generation) { it.copy(folderPage = page) } }
    }

    /**
     * Freeze the visible source and observed queue once. Neither a later preference update nor a
     * changed current item may silently change an already displayed confirmation.
     */
    fun preparePlay(
        selected: TrackKey?,
        explicitAction: LibraryQueueAction? = null,
        onCommit: (LibraryQueueCommand) -> Unit,
    ) {
        val state = mutableState.value
        val token = view ?: return
        if (!visible || !state.canPlay || playJob?.isActive == true || state.page?.view != token) return
        val requestGeneration = generation
        mutableState.update { it.copy(playPreparing = true, playDraft = null, playIssue = null) }
        playJob = viewModelScope.launch {
            try {
                val location = selected?.let { library.locate(token, it) ?: throw LibraryException(LibraryIssue.SOURCE_CHANGED) }
                val queue = app.queues.snapshot()
                if (!visible || requestGeneration != generation || view != token) return@launch
                val action = explicitAction ?: if (selected == null) null else when (state.preferences.songTapPolicy) {
                    SongTapPolicy.ASK -> null
                    SongTapPolicy.INSERT_AND_PLAY -> LibraryQueueAction.INSERT_AND_PLAY
                    SongTapPolicy.REPLACE_COMPLETE_LIST -> LibraryQueueAction.REPLACE_COMPLETE_LIST
                }
                val command = LibraryQueueCommand(
                    viewId = token.viewId,
                    operationId = "library:" + UUID.randomUUID(),
                    expectedGenerationId = queue?.generationId,
                    expectedQueueRevision = queue?.queueRevision ?: 0L,
                    selected = selected,
                    viewRevision = token.revision,
                    viewCount = token.totalCount,
                    selectedOriginalOrder = location?.ordinal,
                    expectedCurrentEntryId = queue?.currentEntryId,
                    mode = queue?.mode ?: QueueMode(),
                    action = action ?: LibraryQueueAction.REPLACE_COMPLETE_LIST,
                )
                mutableState.update { it.copy(playPreparing = false, playDraft = LibraryPlayDraft(
                    command, queue?.entryCount ?: 0L, location?.track?.title, action,
                )) }
                if (action != null) onCommit(command)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (visible && requestGeneration == generation) {
                    val issue = when (error) {
                        is LibraryException -> error.issue
                        is QueueStoreException -> when (error.issue) {
                            QueueStoreIssue.CONFLICT, QueueStoreIssue.SOURCE_CHANGED -> LibraryIssue.CONFLICT
                            QueueStoreIssue.STORAGE_FULL -> LibraryIssue.STORAGE_FULL
                            QueueStoreIssue.RECOVERY_REQUIRED, QueueStoreIssue.INCOMPLETE -> LibraryIssue.RECOVERY_REQUIRED
                            else -> LibraryIssue.STORAGE_UNAVAILABLE
                        }
                        else -> LibraryIssue.STORAGE_UNAVAILABLE
                    }
                    mutableState.update { it.copy(playIssue = issue) }
                }
            } finally {
                if (requestGeneration == generation) mutableState.update { it.copy(playPreparing = false) }
            }
        }
    }

    fun confirmPlay(action: LibraryQueueAction, onCommit: (LibraryQueueCommand) -> Unit) {
        val draft = mutableState.value.playDraft ?: return
        if (!visible || mutableState.value.playPreparing) return
        val changesIntent = draft.submittedAction != null && draft.submittedAction != action
        val command = draft.command.copy(
            action = action,
            operationId = if (changesIntent) "library:" + UUID.randomUUID() else draft.command.operationId,
        )
        mutableState.update { it.copy(playDraft = draft.copy(command = command, submittedAction = action), playIssue = null) }
        onCommit(command)
    }

    fun cancelPlay() {
        playJob?.cancel()
        playJob = null
        mutableState.update { it.copy(playPreparing = false, playDraft = null, playIssue = null) }
    }

    fun beginSort() {
        val state = mutableState.value
        if (!state.preferencesWritable) return
        mutableState.update { it.copy(sortEdit = LibrarySortEdit(state.preferences, state.context,
            state.preferences.librarySorts.getValue(state.context)), sortConflict = false) }
    }

    fun chooseSort(field: LibrarySortField) = editSort { it.copy(field = field) }
    fun toggleSortDirection() = editSort { it.copy(descending = !it.descending) }
    private fun editSort(edit: (LibrarySort) -> LibrarySort) {
        if (mutableState.value.sortSaving) return
        mutableState.update { state -> state.copy(sortEdit = state.sortEdit?.let { it.copy(sort = edit(it.sort)) }) }
        openView()
    }

    fun cancelSort() {
        if (mutableState.value.sortSaving) return
        mutableState.update { it.copy(sortEdit = null, sortConflict = false) }
        openView()
    }

    fun saveSort() {
        val draft = mutableState.value.sortEdit ?: return
        if (sortJob?.isActive == true) return
        sortJob = viewModelScope.launch {
            mutableState.update { it.copy(sortSaving = true, issue = null) }
            try {
                val values = draft.base.librarySorts + (draft.context to draft.sort)
                when (val result = app.preferences.save(draft.base.revision, draft.base.asDraft().copy(librarySorts = values))) {
                    is PreferencesWriteResult.Saved -> mutableState.update { it.copy(preferences = result.preferences, sortEdit = null) }
                    is PreferencesWriteResult.Unchanged -> mutableState.update { it.copy(preferences = result.preferences, sortEdit = null) }
                    is PreferencesWriteResult.Conflict -> mutableState.update { it.copy(sortConflict = true) }
                    is PreferencesWriteResult.Failed -> mutableState.update { it.copy(issue = LibraryIssue.STORAGE_UNAVAILABLE) }
                    is PreferencesWriteResult.Cancelled -> Unit
                }
                if (mutableState.value.sortEdit == null && visible) openView()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { fail(error) }
            finally { mutableState.update { it.copy(sortSaving = false) } }
        }
    }

    /** The viewport supplies actual lazy item keys; a page-sized query is never a refresh scope. */
    fun setVisibleItemKeys(keys: List<Any>) {
        if (!visible) return
        val state = mutableState.value
        val visibleKeys = keys.toSet()
        visibleArtwork = if (state.screen == LibraryScreen.DETAIL) {
            listOfNotNull(state.detail)
        } else if (state.layout == LibraryLayout.GRID) {
            state.page?.tracks.orEmpty().chunked(2).filter { "pair:${it.first().trackKey.trackId}" in visibleKeys }.flatten()
        } else state.page?.tracks.orEmpty().filter { "track:${it.trackKey.providerId}:${it.trackKey.trackId}" in visibleKeys }
        mutableState.update { it.copy(visibleArtworkCount = visibleArtwork.size) }
    }

    fun prepareArtworkRefresh() {
        if (!visible || artworkJob?.isActive == true) return
        val targets = visibleArtwork.toList()
        if (targets.isEmpty() || targets.size > 32) {
            mutableState.update { it.copy(issue = LibraryIssue.INVALID_INPUT) }
            return
        }
        val token = generation
        val path = mutableState.value.preferences.artworkReadPath
        mutableState.update { it.copy(artworkPreparing = true, issue = null, artworkSourceIssue = null) }
        artworkJob = viewModelScope.launch {
            try {
                val descriptors = targets.map { track ->
                    val location = track.location ?: throw LibraryException(LibraryIssue.SOURCE_UNAVAILABLE)
                    val descriptor = library.resolveLocalRead(track.trackKey, location.locationId, location.contentRevision)
                    app.localSources.validateRead(descriptor)
                }
                if (visible && token == generation) {
                    val preview = refresh.prepareVisible(descriptors, path)
                    mutableState.update { it.copy(artworkRefreshPreview = preview) }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: SourceReadException) { publish(token) { it.copy(artworkSourceIssue = error.issue) } }
            catch (error: ArtworkException) { publish(token) { it.copy(issue = if (error.issue == ArtworkIssue.BUSY) LibraryIssue.BUSY else LibraryIssue.SOURCE_UNAVAILABLE) } }
            catch (error: Exception) { if (token == generation && visible) fail(error) }
            finally { if (token == generation) mutableState.update { it.copy(artworkPreparing = false) } }
        }
    }

    fun cancelArtworkPreview() { mutableState.update { it.copy(artworkRefreshPreview = null) } }
    fun confirmArtworkRefresh() {
        val preview = mutableState.value.artworkRefreshPreview ?: return
        if (!visible || artworkJob?.isActive == true) return
        artworkJob = viewModelScope.launch {
            try { refresh.start(preview); mutableState.update { it.copy(artworkRefreshPreview = null) } }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { fail(error) }
        }
    }
    fun cancelArtworkRefresh() {
        val id = mutableState.value.artworkRefresh?.refreshId ?: return
        viewModelScope.launch { refresh.cancel(id) }
    }

    private fun stopArtworkForNavigation() {
        cancelPlay()
        artworkJob?.cancel(); artworkJob = null
        visibleArtwork = emptyList()
        if (refreshOwner.isInitialized()) { refresh.onBackground(); if (visible) refresh.onForeground() }
        mutableState.update { it.copy(artworkPreparing = false, artworkRefreshPreview = null, visibleArtworkCount = 0, artworkSourceIssue = null) }
    }

    private fun retireView() {
        generation += 1L
        pageJob?.cancel(); pageJob = null
        leaseJob?.cancel()
        view = null
    }

    private fun openView(anchor: TrackKey? = null) {
        cancelPlay()
        retireView()
        val token = generation
        if (!visible) return
        val requested = mutableState.value
        if (requested.searchIdle) {
            mutableState.update { it.copy(loading = false, showingPreviousData = false) }
            return
        }
        if (requested.screen in setOf(LibraryScreen.SOURCES, LibraryScreen.HISTORY, LibraryScreen.STATISTICS)) {
            mutableState.update { it.copy(loading = false, issue = null, showingPreviousData = false) }
            return
        }
        mutableState.update { it.copy(loading = true, issue = null, showingPreviousData = it.page != null) }
        leaseJob = viewModelScope.launch {
            // Cancelled waiters cannot let a newer request overtake a lease still releasing metadata.
            leaseMutex.withLock {
                var opened: LibraryViewToken? = null
                try {
                    currentCoroutineContext().ensureActive()
                    val sort = requested.sortEdit?.sort ?: requested.preferences.librarySorts.getValue(requested.context)
                    val loaded = library.openView(requested.query, sort)
                    opened = loaded
                    if (token != generation || !visible) return@launch
                    view = loaded
                    val location = anchor?.let { library.locate(loaded, it) }
                    val tracks = library.page(loaded, location?.cursorBefore)
                    val groupKind = when (requested.screen) { LibraryScreen.ALBUMS -> LibraryGroupKind.ALBUM; LibraryScreen.ARTISTS -> LibraryGroupKind.ARTIST; else -> null }
                    val groups = groupKind?.let { library.groups(loaded, it) }
                    val folders = if (requested.screen == LibraryScreen.FOLDERS) library.folders(loaded, requested.folder?.nodeId) else null
                    val actualFolder = requested.query.folderNodeId?.let { library.folder(loaded, it) }
                    val sections = if (sort.field in setOf(LibrarySortField.TITLE, LibrarySortField.ARTIST, LibrarySortField.ALBUM, LibrarySortField.FILENAME_NATURAL)) library.sections(loaded) else emptyList()
                    val detail = if (requested.screen == LibraryScreen.DETAIL) (requested.detail?.trackKey ?: detailKey())?.let { library.locate(loaded, it)?.track } else null
                    if (token != generation || !visible) return@launch
                    mutableState.update { it.copy(page = tracks, pageStart = location?.ordinal ?: 0L, groupPage = groups, folderPage = folders,
                        folder = actualFolder, sections = sections, detail = detail, loading = false, showingPreviousData = false,
                        hasNewData = it.overview?.revision?.let { revision -> revision != loaded.revision } == true) }
                    awaitCancellation()
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { if (token == generation && visible) fail(error) }
                finally { opened?.let { library.closeView(it.viewId) } }
            }
        }
    }

    private fun loadPage(after: LibraryCursor? = null, before: LibraryCursor? = null, start: Long = 0L, determineStart: Boolean = false) {
        val currentView = view ?: return
        pageTask { token ->
            val page = if (before == null) library.page(currentView, after) else library.previousPage(currentView, before)
            val actualStart = if (determineStart) page.tracks.firstOrNull()?.let { library.locate(currentView, it.trackKey)?.ordinal } ?: 0L else start
            publish(token) { it.copy(page = page, pageStart = actualStart, showingPreviousData = false) }
        }
    }

    private fun pageTask(work: suspend (Long) -> Unit) {
        if (!visible || pageJob?.isActive == true) return
        val token = generation
        mutableState.update { it.copy(loading = true, issue = null) }
        pageJob = viewModelScope.launch {
            try { work(token) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (token == generation && visible) fail(error) }
            finally { if (token == generation) mutableState.update { it.copy(loading = false) } }
        }
    }

    private fun publish(token: Long, update: (LibraryUiState) -> LibraryUiState) {
        if (token == generation && visible) mutableState.update(update)
    }

    private fun fail(error: Exception) {
        val issue = (error as? LibraryException)?.issue ?: LibraryIssue.STORAGE_UNAVAILABLE
        mutableState.update { it.copy(loading = false, issue = issue, showingPreviousData = it.page != null) }
    }

    private fun rememberNavigation() {
        val state = mutableState.value
        if (searchOrigin != null || state.context == LibraryListContext.SEARCH) return
        saved["library.screen"] = state.screen.name
        saved["library.context"] = state.context.name
        saved["library.source"] = state.query.sourceId
        saved["library.folder"] = state.query.folderNodeId
        saved["library.album"] = state.query.albumKey
        saved["library.artist"] = state.query.artistKey
        saved["library.parent"] = parentScreen.name
    }

    private fun detailKey(): TrackKey? {
        val provider = saved.get<String>("library.detail.provider") ?: return null
        val track = saved.get<String>("library.detail.track") ?: return null
        return TrackKey(provider, track)
    }

    private fun restoredState(): LibraryUiState {
        saved.remove<String>("library.search")
        var screen = LibraryScreen.entries.firstOrNull { it.name == saved.get<String>("library.screen") } ?: LibraryScreen.ALL
        var context = LibraryListContext.entries.firstOrNull { it.name == saved.get<String>("library.context") } ?: LibraryListContext.ALL
        if (screen == LibraryScreen.SEARCH || context == LibraryListContext.SEARCH ||
            screen == LibraryScreen.DETAIL && saved.get<String>("library.parent") == LibraryScreen.SEARCH.name) {
            screen = LibraryScreen.ALL
            context = LibraryListContext.ALL
            saved["library.screen"] = screen.name
            saved["library.context"] = context.name
            saved["library.parent"] = LibraryScreen.ALL.name
            for (key in listOf("library.source", "library.folder", "library.album", "library.artist",
                "library.detail.provider", "library.detail.track")) saved.remove<String>(key)
        }
        val source = saved.get<String>("library.source")
        return LibraryUiState(screen = screen, context = context, query = LibraryQuery(sourceId = source,
            folderNodeId = saved.get<String>("library.folder").takeIf { source != null },
            albumKey = saved.get<String>("library.album"), artistKey = saved.get<String>("library.artist"),
            addedSinceEpochMs = if (screen == LibraryScreen.RECENT) System.currentTimeMillis() - RECENT_WINDOW_MS else null,
        ))
    }

    companion object {
        private const val RECENT_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
        private const val MAX_SEARCH_LENGTH = 4096
    }
}
