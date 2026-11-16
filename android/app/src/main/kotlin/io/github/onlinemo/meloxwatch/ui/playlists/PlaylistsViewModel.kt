package io.github.onlinemo.meloxwatch.ui.playlists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.library.LibraryOverview
import io.github.onlinemo.meloxwatch.data.library.LibraryQuery
import io.github.onlinemo.meloxwatch.data.library.LibraryViewOwner
import io.github.onlinemo.meloxwatch.data.library.LocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.playlists.LocalPlaylistRepository
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCatalogCursor
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCatalogView
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCommit
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistException
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistKind
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistLimits
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutation
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutationRequest
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistQueueCatalogToken
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistQueueMapper
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistSnapshot
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistViewToken
import io.github.onlinemo.meloxwatch.data.playlists.playlistName
import io.github.onlinemo.meloxwatch.data.playlistsexchange.PlaylistExchangeException
import io.github.onlinemo.meloxwatch.data.playlistsexchange.PlaylistExchangeIssue
import io.github.onlinemo.meloxwatch.model.QueueMode
import io.github.onlinemo.meloxwatch.playback.PlaylistQueueAction
import io.github.onlinemo.meloxwatch.playback.PlaylistQueueCommand
import io.github.onlinemo.meloxwatch.playback.PlaylistQueueSelection
import io.github.onlinemo.meloxwatch.playback.verifyPlaylistQueueResolutions
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 可见页面拥有一个分页会话；导航先取消并等待旧工作收尾，再开新lease。
 * 关系、曲库和导入页均只保留当前有界页面；播放命令只经同一个服务会话提交。
 */
class PlaylistsViewModel(
    private val playlists: LocalPlaylistRepository,
    private val library: LocalLibraryRepository,
    private val mapper: PlaylistQueueMapper,
    private val folders: FolderPlaylistController,
    private val keywords: KeywordPlaylistController,
    private val exchange: PlaylistExchangeController,
    private val saved: SavedStateHandle,
    private val scopeOverride: CoroutineScope? = null,
    private val closeLibraryView: suspend (String) -> Unit,
) : ViewModel() {
    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope
    private val mutableState = MutableStateFlow(restoreState())
    val uiState = mutableState.asStateFlow()
    private var visible = false
    private var visibilityGeneration = 0L
    private var pageGeneration = 0L
    private var observerJob: Job? = null
    private var sessionJob: Job? = null
    private var active: VisiblePlaylistSession? = null
    private val retired = ArrayDeque<VisiblePlaylistSession>()
    private var latestLibrary: LibraryOverview? = null
    private var favoriteRetry: PlaylistMutationRequest? = null
    private var pendingExportOperation: String? = null

    fun onVisible() {
        if (visible) return
        restoreHiddenMoveDraft()
        visible = true
        val generation = ++visibilityGeneration
        observerJob = scope.launch {
            try {
                playlists.initialize()
                val initializedLibrary = library.initialize()
                if (!isVisible(generation)) return@launch
                latestLibrary = initializedLibrary
                startSession(mutableState.value.playlistId)
                coroutineScope {
                    launch {
                        playlists.observeOverview().collect { overview ->
                            if (!isVisible(generation)) return@collect
                            mutableState.update { state ->
                                val showingRevision = state.catalogPage?.view?.revision
                                state.copy(overview = overview, hasNewData = state.hasNewData ||
                                    (showingRevision != null && overview != null && showingRevision != overview.revision))
                            }
                        }
                    }
                    launch {
                        library.observeOverview().collect { overview ->
                            if (!isVisible(generation)) return@collect
                            latestLibrary = overview
                            mutableState.update { state -> state.copy(previewStale = previewDiffers(state, overview), hasNewData = state.hasNewData ||
                                (state.catalog != null && (state.catalog.revision != overview.revision ||
                                    state.catalog.rulesRevision != overview.rulesRevision))) }
                        }
                    }
                    awaitCancellation()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isVisible(generation)) fail(error)
            }
        }
    }

    fun onHidden() {
        visible = false
        visibilityGeneration += 1L
        pageGeneration += 1L
        observerJob?.cancel()
        observerJob = null
        sessionJob?.cancel()
        active = null
        favoriteRetry = null
        mutableState.update { state -> state.copy(loading = false, saving = false, favoritePending = false, playPending = false,
            selected = null, favorite = null, playDraft = null, moveDraft = null, removeDraft = null, deleteDraft = null,
            screen = if (state.screen in setOf(PlaylistScreen.MEMBER, PlaylistScreen.MOVE, PlaylistScreen.REMOVE, PlaylistScreen.DELETE))
                PlaylistScreen.DETAILS else state.screen,
            folderDraft = state.folderDraft?.copy(preview = null), keywordDraft = state.keywordDraft?.copy(preview = null),
            importDraft = state.importDraft?.copy(preview = null), importPage = null,
            previewStale = false,
            showingPreviousData = state.view != null || state.catalogPage != null) }
    }

    /** Actual navigation retires runtime document grants; STOP keeps the exact picker intent. */
    fun onLeave() {
        onHidden()
        pendingExportOperation = null
        mutableState.update { state -> state.copy(
            importDraft = state.importDraft?.copy(operationId = newId(), document = null, preview = null, unresolvedConfirmed = false),
            importPage = null,
            exportDraft = state.exportDraft?.copy(operationId = newId(), destination = null),
        ) }
        mutableState.value.importDraft?.let(::persistImport)
    }

    fun reload() {
        if (!visible || mutableState.value.saving) return
        startSession(mutableState.value.playlistId, reviewNameRevision = true)
    }

    fun showCatalog() {
        if (mutableState.value.saving) return
        pendingExportOperation = null
        clearSavedDraft()
        mutableState.value = PlaylistUiState(overview = mutableState.value.overview)
        saved[PLAYLIST_ID] = null
        startSession(null)
    }

    fun openPlaylist(snapshot: PlaylistSnapshot) {
        if (!visible || mutableState.value.loading || mutableState.value.saving) return
        openDetails(snapshot.playlistId, snapshot)
    }

    fun openFavorites() {
        if (!visible || mutableState.value.saving) return
        openDetails(PlaylistLimits.FAVORITES_ID)
    }

    private fun openDetails(playlistId: String, expected: PlaylistSnapshot? = null) {
        clearSavedDraft()
        saved[PLAYLIST_ID] = playlistId
        mutableState.update { PlaylistUiState(screen = PlaylistScreen.DETAILS, playlistId = playlistId,
            overview = it.overview, lastCommit = it.lastCommit) }
        startSession(playlistId, expected)
    }

    fun nextCatalogPage() {
        val owned = currentSession() ?: return
        val cursor = mutableState.value.catalogPage?.nextCursor ?: return
        runWork(owned, loading = true) { readCatalogPage(owned, cursor) }
    }

    fun firstCatalogPage() {
        val owned = currentSession() ?: return
        runWork(owned, loading = true) { readCatalogPage(owned, null) }
    }

    fun nextMemberPage() {
        val owned = currentSession() ?: return
        if (!mutableState.value.hasNext) return
        val offset = mutableState.value.memberPage?.nextOrdinal ?: return
        runWork(owned, loading = true) { readMemberPage(owned, offset) }
    }

    fun previousMemberPage() {
        val owned = currentSession() ?: return
        if (!mutableState.value.hasPrevious) return
        val offset = (mutableState.value.pageStart - PlaylistLimits.PAGE_SIZE).coerceAtLeast(0L)
        runWork(owned, loading = true) { readMemberPage(owned, offset) }
    }

    fun selectMember(row: PlaylistMemberRow) {
        val owned = currentSession() ?: return
        val state = mutableState.value
        if (!state.canUseView || state.rows.none { it.member == row.member }) return
        owned.favoriteObserver?.cancel()
        favoriteRetry = null
        mutableState.update { it.copy(screen = PlaylistScreen.MEMBER, selected = row, favorite = null, issue = null,
            playDraft = null, playPending = false, moveDraft = null, removeDraft = null) }
        val key = row.member.reference.trackKey ?: return
        owned.favoriteObserver = checkNotNull(owned.scope).launch {
            try {
                playlists.observeFavorite(key).collect { favorite ->
                    if (current(owned) && mutableState.value.selected?.member?.memberId == row.member.memberId &&
                        !mutableState.value.favoritePending) mutableState.update { it.copy(favorite = favorite) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current(owned)) fail(error)
            }
        }
    }

    fun returnToDetails() {
        if (mutableState.value.saving) return
        pendingExportOperation = null
        active?.favoriteObserver?.cancel()
        active?.favoriteObserver = null
        favoriteRetry = null
        clearSavedDraft()
        mutableState.update { it.copy(screen = PlaylistScreen.DETAILS, nameDraft = null, deleteDraft = null,
            removeDraft = null, moveDraft = null, folderDraft = null, keywordDraft = null, importDraft = null,
            importPage = null, exportDraft = null, playDraft = null, selected = null, favorite = null,
            favoritePending = false, previewStale = false, issue = null) }
        val owned = currentSession() ?: return
        runWork(owned) { closePreviews(owned) }
    }

    fun beginCreate() = beginName(PlaylistNamePurpose.CREATE)
    fun beginRename() = beginName(PlaylistNamePurpose.RENAME)

    private fun beginName(purpose: PlaylistNamePurpose) {
        val state = mutableState.value
        if (currentSession() == null || state.saving || state.loading) return
        val snapshot = state.view?.snapshot
        if (purpose == PlaylistNamePurpose.RENAME && (!state.canUseView || snapshot?.kind == PlaylistKind.FAVORITES)) return
        val draft = PlaylistNameDraft(newId(), if (purpose == PlaylistNamePurpose.RENAME) checkNotNull(snapshot).playlistId else newId(),
            if (purpose == PlaylistNamePurpose.RENAME) checkNotNull(snapshot).revision else 0L,
            if (purpose == PlaylistNamePurpose.RENAME) checkNotNull(snapshot).name else "", purpose)
        mutableState.update { it.copy(screen = PlaylistScreen.NAME, nameDraft = draft, issue = null, lastCommit = null) }
        persistName(draft)
    }

    fun changeName(name: String) {
        val state = mutableState.value
        val draft = state.nameDraft ?: return
        if (state.saving || name.length > PlaylistLimits.MAX_FIELD_CHARACTERS) return
        val changed = draft.copy(name = name, operationId = if (name == draft.name) draft.operationId else newId())
        mutableState.update { it.copy(nameDraft = changed, issue = null,
            folderDraft = if (draft.purpose == PlaylistNamePurpose.FOLDER) it.folderDraft?.copy(operationId = changed.operationId)
                else it.folderDraft) }
        persistName(changed)
    }

    fun saveName() {
        val owned = currentSession() ?: return
        val draft = mutableState.value.nameDraft ?: return
        val name = validatedName(draft.name) ?: return
        val mutation = when (draft.purpose) {
            PlaylistNamePurpose.CREATE -> PlaylistMutation.Create(name)
            PlaylistNamePurpose.RENAME -> PlaylistMutation.Rename(name)
            else -> return
        }
        val request = PlaylistMutationRequest(draft.operationId, draft.playlistId, draft.expectedRevision, mutation)
        runWork(owned, saving = true) {
            val commit = playlists.apply(request)
            if (current(owned)) accept(commit) { openDetails(draft.playlistId) }
        }
    }

    fun beginDelete() {
        val state = mutableState.value
        val view = state.view ?: return
        if (!state.canUseView || view.snapshot.kind == PlaylistKind.FAVORITES) return
        mutableState.update { it.copy(screen = PlaylistScreen.DELETE,
            deleteDraft = PlaylistDeleteDraft(newId(), view.snapshot.playlistId, view.snapshot.revision), issue = null) }
    }

    fun confirmDelete() {
        val owned = currentSession() ?: return
        val draft = mutableState.value.deleteDraft ?: return
        val request = PlaylistMutationRequest(draft.operationId, draft.playlistId, draft.expectedRevision, PlaylistMutation.Delete(true))
        runWork(owned, saving = true) {
            val commit = playlists.apply(request)
            if (current(owned)) accept(commit, ::showCatalog)
        }
    }

    fun beginRemove() {
        val state = mutableState.value
        val member = state.selected?.member ?: return
        if (!state.canEditMembers) return
        mutableState.update { it.copy(screen = PlaylistScreen.REMOVE,
            removeDraft = PlaylistRemoveDraft(newId(), member.memberId, checkNotNull(state.view).snapshot.revision), issue = null) }
    }

    fun confirmRemove() {
        val owned = currentSession() ?: return
        val state = mutableState.value
        val draft = state.removeDraft ?: return
        val view = state.view ?: return
        val request = PlaylistMutationRequest(draft.operationId, view.snapshot.playlistId, draft.expectedRevision,
            PlaylistMutation.Remove(draft.memberId, true))
        runWork(owned, saving = true) {
            val commit = playlists.apply(request)
            if (current(owned)) accept(commit) { openDetails(view.snapshot.playlistId) }
        }
    }

    fun beginMove() {
        val state = mutableState.value
        val selected = state.selected ?: return
        val member = selected.member
        if (!state.canMoveMembers) return
        val snapshot = checkNotNull(state.view).snapshot
        val draft = PlaylistMoveDraft(newId(), member.memberId, member.ordinal, (member.ordinal + 1L).toString(),
            snapshot.revision, snapshot.playlistId, selected.title)
        clearSavedDraft()
        mutableState.update { it.copy(screen = PlaylistScreen.MOVE, issue = null,
            moveDraft = draft) }
        persistMove(draft)
    }

    fun changeMoveDestination(destination: String) {
        val state = mutableState.value
        val draft = state.moveDraft ?: return
        if (state.saving || destination.length > 20) return
        val changed = draft.copy(destination = destination,
            operationId = if (destination == draft.destination) draft.operationId else newId())
        mutableState.update { it.copy(moveDraft = changed, issue = null) }
        persistMove(changed)
    }

    fun moveUp() = adjustMove(-1)
    fun moveDown() = adjustMove(1)

    private fun adjustMove(direction: Int) {
        val state = mutableState.value
        val draft = state.moveDraft ?: return
        val count = state.view?.snapshot?.memberCount ?: return
        if (!state.canUseView || count <= 0L) return
        val position = draft.destination.toLongOrNull()?.coerceIn(1L, count)
            ?: draft.fromOrdinal.coerceIn(0L, count - 1L) + 1L
        val destination = if (direction < 0) (position - 1L).coerceAtLeast(1L)
            else if (position < count) position + 1L else count
        changeMoveDestination(destination.toString())
    }

    fun saveMove() {
        val owned = currentSession() ?: return
        val state = mutableState.value
        val draft = state.moveDraft ?: return
        val view = state.view ?: return
        if (view.snapshot.playlistId != draft.playlistId) {
            mutableState.update { it.copy(issue = PlaylistUiIssue.SOURCE_CHANGED) }
            return
        }
        val destination = draft.destination.toLongOrNull()
        if (destination == null || destination !in 1L..view.snapshot.memberCount) {
            mutableState.update { it.copy(issue = PlaylistUiIssue.INVALID_POSITION) }
            return
        }
        if (!state.canUseView) return
        val request = PlaylistMutationRequest(draft.operationId, draft.playlistId, draft.expectedRevision,
            PlaylistMutation.Move(draft.memberId, destination - 1L))
        runWork(owned, saving = true) {
            val commit = playlists.apply(request)
            if (current(owned)) accept(commit) { openDetails(view.snapshot.playlistId) }
        }
    }

    fun toggleFavorite() {
        val owned = currentSession() ?: return
        val state = mutableState.value
        val selected = state.selected ?: return
        val key = selected.member.reference.trackKey ?: return
        val previous = state.favorite ?: return
        if (!state.canUseView || state.favoritePending) return
        val enabled = !previous
        mutableState.update { it.copy(favorite = enabled, favoritePending = true) }
        runWork(owned, saving = true) {
            try {
                val retry = favoriteRetry
                val request = if (retry?.mutation == PlaylistMutation.SetFavorite(selected.member.reference, enabled)) retry else {
                    val snapshot = playlists.snapshot(PlaylistLimits.FAVORITES_ID) ?: throw PlaylistException(PlaylistIssue.NOT_FOUND)
                    PlaylistMutationRequest(newId(), PlaylistLimits.FAVORITES_ID, snapshot.revision,
                        PlaylistMutation.SetFavorite(selected.member.reference, enabled))
                }
                favoriteRetry = request
                val commit = playlists.apply(request)
                val actualFavorite = playlists.isFavorite(key)
                if (current(owned)) {
                    favoriteRetry = null
                    mutableState.update { it.copy(favorite = actualFavorite, favoritePending = false, saving = false,
                        issue = null, lastCommit = commit) }
                    if (state.view?.snapshot?.kind == PlaylistKind.FAVORITES) openDetails(PlaylistLimits.FAVORITES_ID)
                }
            } catch (error: Exception) {
                if (current(owned)) {
                    mutableState.update { it.copy(favorite = previous, favoritePending = false) }
                    if (error is PlaylistException && error.issue == PlaylistIssue.CONFLICT) favoriteRetry = null
                }
                throw error
            }
        }
    }

    fun recoveryTarget(action: PlaylistRecoveryAction): PlaylistRecoveryTarget? {
        val state = mutableState.value
        if (!state.canUseView) return null
        val selected = state.selected ?: return null
        return PlaylistRecoveryTarget(checkNotNull(state.view), selected.member, selected.availability, action)
    }

    fun preparePlay(binding: PlaylistQueueBinding, mode: QueueMode, action: PlaylistQueueAction, selected: Boolean) {
        val state = mutableState.value
        if (!state.canPlay) return
        val view = checkNotNull(state.view)
        val catalog = checkNotNull(state.catalog)
        val row = if (selected) state.selected ?: return else null
        val command = PlaylistQueueCommand(view, PlaylistQueueCatalogToken(catalog.viewId, catalog.revision, catalog.rulesRevision),
            newId(), binding.generationId, binding.revision, binding.currentEntryId,
            row?.member?.let { PlaylistQueueSelection(it.memberId, it.ordinal) }, mode, action)
        mutableState.update { it.copy(playDraft = PlaylistPlayDraft(command, binding.entryCount, row?.title), issue = null) }
    }

    fun confirmPlay(submit: (PlaylistQueueCommand) -> Unit) {
        val state = mutableState.value
        val draft = state.playDraft ?: return
        if (!state.canPlay) return
        mutableState.update { it.copy(playDraft = draft.copy(submitted = true), playPending = true, issue = null) }
        try {
            submit(draft.command)
        } catch (error: Exception) {
            acknowledgePlay(draft.command.operationId, playlistUiIssue(error))
        }
    }

    fun acknowledgePlay(operationId: String, issue: PlaylistUiIssue?) {
        val draft = mutableState.value.playDraft ?: return
        if (!visible || draft.command.operationId != operationId) return
        mutableState.update { it.copy(playPending = false, playDraft = if (issue == null) null else draft, issue = issue) }
    }

    fun cancelPlay() {
        if (mutableState.value.playPending) return
        mutableState.update { it.copy(playDraft = null) }
    }

    fun beginFolderCreate() {
        if (currentSession() == null || mutableState.value.loading || mutableState.value.saving) return
        val id = newId()
        val name = PlaylistNameDraft(newId(), id, 0L, "", PlaylistNamePurpose.FOLDER)
        mutableState.update { it.copy(screen = PlaylistScreen.FOLDER, nameDraft = name,
            folderDraft = PlaylistFolderDraft(name.operationId, FolderPlaylistPurpose.CREATE, id, 0L, null), issue = null) }
        persistName(name)
    }

    fun beginFolderSync() = beginFolderUpdate(FolderPlaylistPurpose.SYNCHRONIZE)
    fun beginFolderRebind() = beginFolderUpdate(FolderPlaylistPurpose.REBIND)

    private fun beginFolderUpdate(purpose: FolderPlaylistPurpose) {
        val state = mutableState.value
        val view = state.view ?: return
        val binding = view.snapshot.folderBinding ?: return
        if (!state.canUseView || view.snapshot.kind != PlaylistKind.FOLDER) return
        mutableState.update { it.copy(screen = PlaylistScreen.FOLDER, nameDraft = null, issue = null,
            folderDraft = PlaylistFolderDraft(newId(), purpose, view.snapshot.playlistId, view.snapshot.revision,
                if (purpose == FolderPlaylistPurpose.SYNCHRONIZE) PlaylistFolderChoice(binding, null) else null)) }
        if (purpose == FolderPlaylistPurpose.SYNCHRONIZE) previewFolder()
    }

    fun chooseFolder(choice: PlaylistFolderChoice) {
        val state = mutableState.value
        val draft = state.folderDraft ?: return
        if (state.saving) return
        mutableState.update { it.copy(folderDraft = draft.copy(choice = choice, operationId = newId(), preview = null), issue = null) }
        previewFolder()
    }

    fun previewFolder() {
        val owned = currentSession() ?: return
        val state = mutableState.value
        val draft = state.folderDraft ?: return
        val choice = draft.choice ?: return
        runWork(owned, loading = true) {
            closeFolderPreview(owned)
            val preview = folders.preview(choice, if (draft.purpose == FolderPlaylistPurpose.CREATE) null else state.view?.snapshot)
            owned.folderPreviewId = preview.previewId
            currentCoroutineContext().ensureActive()
            if (preview.choice != choice) throw PlaylistException(PlaylistIssue.SOURCE_CHANGED)
            if (current(owned)) {
                val operationId = newId()
                val name = mutableState.value.nameDraft?.copy(operationId = operationId)
                mutableState.update { it.copy(loading = false, nameDraft = name,
                    folderDraft = draft.copy(preview = preview, operationId = operationId),
                    previewStale = !isPreviewCurrent(PlaylistPreviewRevision(preview.fence.catalogRevision, preview.fence.rulesRevision)), issue = null) }
                if (name != null) persistName(name)
            }
        }
    }

    fun confirmFolder() {
        val owned = currentSession() ?: return
        val state = mutableState.value
        val draft = state.folderDraft ?: return
        val preview = draft.preview ?: return
        if (!isPreviewCurrent(PlaylistPreviewRevision(preview.fence.catalogRevision, preview.fence.rulesRevision))) {
            mutableState.update { it.copy(issue = PlaylistUiIssue.SOURCE_CHANGED) }
            return
        }
        val name = if (draft.purpose == FolderPlaylistPurpose.CREATE) validatedName(state.nameDraft?.name.orEmpty()) ?: return else null
        runWork(owned, saving = true) {
            val commit = when (draft.purpose) {
                FolderPlaylistPurpose.CREATE -> folders.create(FolderPlaylistCreateRequest(draft.operationId, draft.playlistId,
                    checkNotNull(name), preview.previewId))
                FolderPlaylistPurpose.SYNCHRONIZE -> folders.synchronize(FolderPlaylistUpdateRequest(draft.operationId, draft.playlistId,
                    draft.expectedRevision, preview.previewId))
                FolderPlaylistPurpose.REBIND -> folders.rebind(FolderPlaylistUpdateRequest(draft.operationId, draft.playlistId,
                    draft.expectedRevision, preview.previewId))
            }
            if (current(owned)) accept(commit) { openDetails(draft.playlistId) }
        }
    }

    fun beginKeywordCreate() {
        if (currentSession() == null || mutableState.value.loading || mutableState.value.saving) return
        val name = PlaylistNameDraft(newId(), newId(), 0L, "", PlaylistNamePurpose.KEYWORD)
        mutableState.update { it.copy(screen = PlaylistScreen.KEYWORD, nameDraft = name,
            keywordDraft = PlaylistKeywordDraft(), issue = null) }
        persistName(name)
        saved[KEYWORD] = ""
    }

    fun changeKeyword(keyword: String) {
        val state = mutableState.value
        val draft = state.keywordDraft ?: return
        if (state.saving || keyword.length > PlaylistLimits.MAX_FIELD_CHARACTERS) return
        active?.work?.cancel()
        val name = state.nameDraft?.copy(operationId = newId())
        mutableState.update { it.copy(keywordDraft = draft.copy(keyword = keyword, preview = null,
            broadMatchConfirmed = false, emptyMatchConfirmed = false), nameDraft = name, loading = false, issue = null) }
        if (name != null) persistName(name)
        saved[KEYWORD] = keyword
    }

    fun previewKeyword() {
        val owned = currentSession() ?: return
        val draft = mutableState.value.keywordDraft ?: return
        if (draft.keyword.isBlank()) {
            mutableState.update { it.copy(issue = PlaylistUiIssue.INVALID_KEYWORD) }
            return
        }
        runWork(owned, loading = true) {
            closeKeywordPreview(owned)
            val preview = keywords.preview(draft.keyword)
            owned.keywordPreviewId = preview.previewId
            currentCoroutineContext().ensureActive()
            if (preview.keyword != draft.keyword) throw PlaylistException(PlaylistIssue.SOURCE_CHANGED)
            if (current(owned)) {
                val name = mutableState.value.nameDraft?.copy(operationId = newId())
                mutableState.update { it.copy(loading = false, nameDraft = name,
                    keywordDraft = draft.copy(preview = preview, broadMatchConfirmed = false, emptyMatchConfirmed = false),
                    previewStale = !isPreviewCurrent(preview.revision), issue = null) }
                if (name != null) persistName(name)
            }
        }
    }

    fun confirmBroadKeyword(confirmed: Boolean) {
        if (mutableState.value.saving) return
        mutableState.update { state -> state.copy(keywordDraft = state.keywordDraft?.copy(broadMatchConfirmed = confirmed)) }
    }

    fun confirmEmptyKeyword(confirmed: Boolean) {
        if (mutableState.value.saving) return
        mutableState.update { state -> state.copy(keywordDraft = state.keywordDraft?.copy(emptyMatchConfirmed = confirmed)) }
    }

    fun confirmKeyword() {
        val owned = currentSession() ?: return
        val state = mutableState.value
        val draft = state.keywordDraft ?: return
        val preview = draft.preview ?: return
        val name = state.nameDraft ?: return
        val validName = validatedName(name.name) ?: return
        if (!isPreviewCurrent(preview.revision)) {
            mutableState.update { it.copy(issue = PlaylistUiIssue.SOURCE_CHANGED) }
            return
        }
        if ((preview.requiresBroadConfirmation && !draft.broadMatchConfirmed) ||
            (preview.memberCount == 0L && !draft.emptyMatchConfirmed)) return
        runWork(owned, saving = true) {
            val commit = keywords.create(KeywordPlaylistCreateRequest(name.operationId, name.playlistId, validName,
                preview.previewId, draft.broadMatchConfirmed, draft.emptyMatchConfirmed))
            if (current(owned)) accept(commit) { openDetails(name.playlistId) }
        }
    }

    fun beginAdd(selection: PlaylistAddSelection): Boolean {
        if (currentSession() == null || mutableState.value.loading || mutableState.value.saving || retired.isNotEmpty() ||
            active?.addSelection != null || selection.input.totalCount <= 0L) return false
        clearSavedDraft()
        mutableState.value = PlaylistUiState(screen = PlaylistScreen.ADD_TARGETS, overview = mutableState.value.overview,
            addCount = selection.input.totalCount, addLabel = selection.label)
        saved[PLAYLIST_ID] = null
        startSession(null, addSelection = selection)
        return true
    }

    fun chooseAddTarget(snapshot: PlaylistSnapshot) {
        val state = mutableState.value
        if (state.loading || state.saving || snapshot.kind != PlaylistKind.ORDINARY || active?.addSelection == null) return
        if (state.catalogPage?.playlists?.none { it == snapshot } != false) return
        mutableState.update { it.copy(screen = PlaylistScreen.ADD_CONFIRM, addDraft = PlaylistAddDraft(newId(), snapshot), issue = null) }
    }

    fun returnToAddTargets() {
        if (mutableState.value.saving) return
        mutableState.update { it.copy(screen = PlaylistScreen.ADD_TARGETS, addDraft = null, issue = null) }
    }

    fun confirmAdd() {
        val owned = currentSession() ?: return
        val draft = mutableState.value.addDraft ?: return
        val selection = owned.addSelection ?: return
        val request = PlaylistMutationRequest(draft.operationId, draft.target.playlistId, draft.target.revision,
            PlaylistMutation.Add(selection.input))
        runWork(owned, saving = true) {
            val commit = playlists.apply(request, selection.source)
            if (current(owned)) accept(commit) { openDetails(draft.target.playlistId) }
        }
    }

    fun beginImport(format: PlaylistTransferFormat, encoding: PlaylistTransferEncoding = PlaylistTransferEncoding.UTF8,
        mergeIntoCurrent: Boolean = false) {
        val state = mutableState.value
        if (currentSession() == null || state.loading || state.saving ||
            (format != PlaylistTransferFormat.M3U && encoding != PlaylistTransferEncoding.UTF8)) return
        val target = if (mergeIntoCurrent) {
            if (!state.canEditMembers) return
            val snapshot = checkNotNull(state.view).snapshot
            PlaylistImportTarget(snapshot.playlistId, snapshot.revision)
        } else null
        val draft = PlaylistImportDraft(newId(), newId(), format, encoding, target)
        clearSavedDraft()
        mutableState.update { it.copy(screen = PlaylistScreen.IMPORT, issue = null, importPage = null,
            importDraft = draft) }
        persistImport(draft)
    }

    fun inspectImportDocument(document: AuthorizedPlaylistDocument) {
        val state = mutableState.value
        val draft = state.importDraft ?: return
        if (state.saving) return
        val changed = draft.copy(document = document, preview = null, operationId = newId())
        mutableState.update { it.copy(importDraft = changed, importPage = null) }
        persistImport(changed)
        val owned = currentSession() ?: return
        if (!mutableState.value.loading || owned.work != null) readImportPreview(owned)
    }

    fun previewImportAgain() {
        val owned = currentSession() ?: return
        readImportPreview(owned)
    }

    private fun readImportPreview(owned: VisiblePlaylistSession) {
        val draft = mutableState.value.importDraft ?: return
        val document = draft.document ?: return
        runWork(owned, loading = true) {
            closeImportPreview(owned)
            val preview = exchange.inspectImport(draft.format, draft.encoding, document, draft.target)
            owned.importPreviewId = preview.previewId
            currentCoroutineContext().ensureActive()
            if (preview.format != draft.format || preview.encoding != draft.encoding || preview.target != draft.target) {
                throw PlaylistException(PlaylistIssue.SOURCE_CHANGED)
            }
            if (current(owned)) mutableState.update { state ->
                val name = state.importDraft?.name.orEmpty()
                state.copy(importDraft = draft.copy(preview = preview,
                    name = if (draft.preview == null && draft.name.isEmpty() && name == draft.name) preview.nameHint.orEmpty()
                        else name, unresolvedConfirmed = false,
                    operationId = newId()), importPage = null, previewStale = !isPreviewCurrent(preview.revision))
            }
            if (current(owned)) mutableState.value.importDraft?.let(::persistImport)
            readImportPage(owned, preview, 0L)
        }
    }

    fun changeImportName(name: String) {
        val state = mutableState.value
        val draft = state.importDraft ?: return
        if (state.saving || name.length > PlaylistLimits.MAX_FIELD_CHARACTERS) return
        val changed = draft.copy(name = name, operationId = if (name == draft.name) draft.operationId else newId())
        mutableState.update { it.copy(importDraft = changed, issue = null) }
        persistImport(changed)
    }

    fun confirmUnresolvedImport(confirmed: Boolean) {
        if (mutableState.value.saving) return
        mutableState.update { it.copy(importDraft = it.importDraft?.copy(unresolvedConfirmed = confirmed)) }
    }

    fun nextImportPage() {
        val owned = currentSession() ?: return
        val state = mutableState.value
        val preview = state.importDraft?.preview ?: return
        val offset = state.importPage?.nextOrdinal ?: return
        runWork(owned, loading = true) { readImportPage(owned, preview, offset) }
    }

    fun previousImportPage() {
        val owned = currentSession() ?: return
        val state = mutableState.value
        val preview = state.importDraft?.preview ?: return
        val offset = state.importPage?.firstOrdinal ?: return
        if (offset == 0L) return
        runWork(owned, loading = true) { readImportPage(owned, preview, (offset - PlaylistLimits.PAGE_SIZE).coerceAtLeast(0L)) }
    }

    fun confirmImport() {
        val owned = currentSession() ?: return
        val draft = mutableState.value.importDraft ?: return
        val preview = draft.preview ?: return
        if (!isPreviewCurrent(preview.revision)) {
            mutableState.update { it.copy(issue = PlaylistUiIssue.SOURCE_CHANGED) }
            return
        }
        if (preview.unresolvedCount > 0L && !draft.unresolvedConfirmed) return
        val destination = draft.target?.let { PlaylistImportDestination.Merge(it) } ?: PlaylistImportDestination.New(
            draft.newPlaylistId, validatedName(draft.name) ?: return,
        )
        runWork(owned, saving = true) {
            val commit = exchange.confirmImport(PlaylistImportConfirmation(draft.operationId, preview.previewId,
                destination, draft.unresolvedConfirmed))
            if (current(owned)) accept(commit) { openDetails(commit.playlistId) }
        }
    }

    fun beginExport(format: PlaylistTransferFormat, encoding: PlaylistTransferEncoding = PlaylistTransferEncoding.UTF8) {
        val state = mutableState.value
        if (!state.canUseView || (format != PlaylistTransferFormat.M3U && encoding != PlaylistTransferEncoding.UTF8)) return
        val view = checkNotNull(state.view)
        val catalog = checkNotNull(state.catalog)
        pendingExportOperation = null
        mutableState.update { it.copy(screen = PlaylistScreen.EXPORT, exportReceipt = null, issue = null,
            exportDraft = PlaylistExportDraft(newId(), view, PlaylistPreviewRevision(catalog.revision, catalog.rulesRevision), format, encoding)) }
    }

    fun exportTo(document: AuthorizedPlaylistDocument) {
        val draft = mutableState.value.exportDraft ?: return
        if (mutableState.value.saving) return
        val selected = draft.copy(destination = document,
            operationId = if (draft.destination == null || draft.destination == document) draft.operationId else newId())
        mutableState.update { it.copy(exportDraft = selected, exportReceipt = null) }
        pendingExportOperation = selected.operationId
        if (mutableState.value.canUseView) {
            pendingExportOperation = null
            exportSelectedDocument()
        }
    }

    fun exportSelectedDocument() {
        val owned = currentSession() ?: return
        if (!mutableState.value.canUseView) return
        val draft = mutableState.value.exportDraft ?: return
        val document = draft.destination ?: return
        val view = mutableState.value.view ?: return
        val catalog = mutableState.value.catalog ?: return
        if (view.snapshot != draft.originalView.snapshot || catalog.revision != draft.revision.catalogRevision ||
            catalog.rulesRevision != draft.revision.rulesRevision) {
            mutableState.update { it.copy(issue = PlaylistUiIssue.SOURCE_CHANGED) }
            return
        }
        runWork(owned, saving = true) {
            val receipt = exchange.export(draft.operationId, view, document, draft.format, draft.encoding)
            if (receipt.operationId != draft.operationId || receipt.memberCount != view.snapshot.memberCount) {
                throw PlaylistException(PlaylistIssue.SOURCE_CHANGED)
            }
            if (current(owned)) mutableState.update { it.copy(saving = false, exportReceipt = receipt, issue = null) }
        }
    }

    private fun startSession(playlistId: String?, expected: PlaylistSnapshot? = null, addSelection: PlaylistAddSelection? = null,
        reviewNameRevision: Boolean = false) {
        if (!visible) return
        val generation = ++pageGeneration
        val previous = sessionJob
        previous?.cancel()
        active = null
        favoriteRetry = null
        val owned = VisiblePlaylistSession(generation, addSelection, reviewNameRevision)
        mutableState.update { it.copy(loading = true, showingPreviousData = it.view != null || it.catalogPage != null,
            hasNewData = false, issue = null, playDraft = null, playPending = false, favoritePending = false,
            selected = if (it.screen in setOf(PlaylistScreen.MOVE, PlaylistScreen.REMOVE)) it.selected else null,
            favorite = null, saving = false,
            screen = if (it.screen == PlaylistScreen.MEMBER) PlaylistScreen.DETAILS else it.screen) }
        sessionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                previous?.join()
                while (retired.isNotEmpty()) {
                    val pendingClose = retired.first()
                    if (!closeOwned(pendingClose)) throw PlaylistException(PlaylistIssue.BUSY)
                    retired.removeFirst()
                }
                if (!current(owned)) return@launch
                coroutineScope {
                    owned.scope = this
                    active = owned
                    if (playlistId == null) {
                        owned.catalog = playlists.openCatalogView()
                        readCatalogPage(owned, null)
                    } else {
                        owned.playlist = playlists.openView(playlistId)
                        if (expected != null && owned.playlist?.snapshot != expected) throw PlaylistException(PlaylistIssue.SOURCE_CHANGED)
                        owned.library = library.openView(LibraryQuery(includeHidden = true))
                        val catalog = checkNotNull(owned.library)
                        if (catalog.query != LibraryQuery(includeHidden = true) || catalog.owner != LibraryViewOwner.UI) {
                            throw LibraryException(LibraryIssue.SOURCE_CHANGED)
                        }
                        val lastStart = ((checkNotNull(owned.playlist).snapshot.memberCount - 1L).coerceAtLeast(0L) /
                            PlaylistLimits.PAGE_SIZE) * PlaylistLimits.PAGE_SIZE
                        readMemberPage(owned, mutableState.value.pageStart.coerceAtMost(lastStart))
                        launch {
                            playlists.observePlaylist(playlistId).collect { snapshot ->
                                if (current(owned)) mutableState.update { state -> state.copy(hasNewData = state.hasNewData ||
                                    snapshot != owned.playlist?.snapshot) }
                            }
                        }
                    }
                    if (mutableState.value.screen == PlaylistScreen.IMPORT && mutableState.value.importDraft?.document != null) {
                        readImportPreview(owned)
                    }
                    val pendingExport = pendingExportOperation
                    if (pendingExport != null && mutableState.value.screen == PlaylistScreen.EXPORT &&
                        mutableState.value.exportDraft?.operationId == pendingExport) {
                        // 只接续本次系统选择器的显式结果；未持久化，开始一次后取消不会自动重试。
                        pendingExportOperation = null
                        exportSelectedDocument()
                    }
                    awaitCancellation()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current(owned)) {
                    fail(error)
                    mutableState.update { it.copy(showingPreviousData = it.view != null || it.catalogPage != null) }
                }
            } finally {
                if (active === owned) active = null
                owned.scope = null
                // 新会话在回收全部旧资源前不会开lease；失败资源最多来自旧会话和本次输入。
                if (!closeOwned(owned)) retired.addLast(owned)
            }
        }
    }

    private suspend fun readCatalogPage(owned: VisiblePlaylistSession, after: PlaylistCatalogCursor?) {
        val view = checkNotNull(owned.catalog)
        val page = playlists.catalogPage(view, after, PlaylistLimits.PAGE_SIZE)
        currentCoroutineContext().ensureActive()
        if (page.view != view || page.playlists.size > PlaylistLimits.PAGE_SIZE) throw PlaylistException(PlaylistIssue.SOURCE_CHANGED)
        if (current(owned)) mutableState.update { it.copy(catalogPage = page, view = null, catalog = null, memberPage = null,
            rows = emptyList(), loading = false, showingPreviousData = false, issue = null) }
    }

    private suspend fun readMemberPage(owned: VisiblePlaylistSession, offset: Long) {
        val view = checkNotNull(owned.playlist)
        val catalog = checkNotNull(owned.library)
        val page = playlists.page(view, offset, PlaylistLimits.PAGE_SIZE)
        currentCoroutineContext().ensureActive()
        val expectedSize = minOf(PlaylistLimits.PAGE_SIZE.toLong(), view.snapshot.memberCount - offset).toInt()
        if (page.view != view || page.members.size != expectedSize || page.members.withIndex().any { it.value.ordinal != offset + it.index }) {
            throw PlaylistException(PlaylistIssue.INCOMPLETE)
        }
        val next = offset + expectedSize
        val expectedNext = if (next == view.snapshot.memberCount) null else next
        if (page.nextOrdinal != expectedNext) throw PlaylistException(PlaylistIssue.INCOMPLETE)
        val mapped = mapper.map(PlaylistQueueCatalogToken(catalog.viewId, catalog.revision, catalog.rulesRevision), page.members)
        currentCoroutineContext().ensureActive()
        verifyPlaylistQueueResolutions(page.members, mapped)
        val rows = mapped.mapIndexed { index, resolution -> PlaylistMemberRow(page.members[index],
            resolution.entry.displayName ?: page.members[index].reference.titleHint, resolution.availability) }
        if (current(owned)) {
            owned.favoriteObserver?.cancel()
            owned.favoriteObserver = null
            favoriteRetry = null
            mutableState.update { it.copy(view = view, catalog = catalog, memberPage = page, pageStart = offset,
                rows = rows, catalogPage = null, loading = false, showingPreviousData = false,
                selected = if (it.screen in setOf(PlaylistScreen.MOVE, PlaylistScreen.REMOVE)) it.selected else null,
                favorite = null, favoritePending = false,
                screen = if (it.screen == PlaylistScreen.MEMBER) PlaylistScreen.DETAILS else it.screen, issue = null) }
            if (owned.reviewNameRevision) reviewReloadedName(view.snapshot)
        }
    }

    private fun reviewReloadedName(snapshot: PlaylistSnapshot) {
        val draft = mutableState.value.nameDraft ?: return
        if (draft.purpose != PlaylistNamePurpose.RENAME || draft.playlistId != snapshot.playlistId ||
            draft.expectedRevision == snapshot.revision) return
        // 只有显式重新加载才更新草稿基线；重新可见或进程恢复仍保原意图，不自动写入。
        val reviewed = draft.copy(expectedRevision = snapshot.revision, operationId = newId())
        mutableState.update { it.copy(nameDraft = reviewed) }
        persistName(reviewed)
    }

    private suspend fun readImportPage(owned: VisiblePlaylistSession, preview: PlaylistImportPreview, offset: Long) {
        val page = exchange.importPage(preview.previewId, offset, PlaylistLimits.PAGE_SIZE)
        currentCoroutineContext().ensureActive()
        val expectedSize = minOf(PlaylistLimits.PAGE_SIZE.toLong(), preview.memberCount - offset).toInt()
        if (page.previewId != preview.previewId || page.firstOrdinal != offset || page.rows.size != expectedSize ||
            page.rows.withIndex().any { it.value.ordinal != offset + it.index }) throw PlaylistException(PlaylistIssue.INCOMPLETE)
        val end = offset + expectedSize
        val expectedNext = if (end == preview.memberCount) null else end
        if (page.nextOrdinal != expectedNext) throw PlaylistException(PlaylistIssue.INCOMPLETE)
        if (current(owned)) mutableState.update { it.copy(importPage = page, loading = false, issue = null) }
    }

    private fun runWork(owned: VisiblePlaylistSession, loading: Boolean = false, saving: Boolean = false,
        work: suspend () -> Unit) {
        if (!current(owned) || mutableState.value.saving) return
        val previous = owned.work
        previous?.cancel()
        mutableState.update { it.copy(loading = loading, saving = saving, issue = null) }
        owned.work = checkNotNull(owned.scope).launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                previous?.join()
                if (current(owned)) work()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (current(owned)) fail(error)
            }
        }
    }

    private fun accept(commit: PlaylistCommit, next: () -> Unit) {
        mutableState.update { it.copy(lastCommit = commit, saving = false, issue = null) }
        next()
    }

    private fun currentSession(): VisiblePlaylistSession? {
        val owned = active ?: return null
        return owned.takeIf { current(it) && it.scope != null }
    }

    private fun current(owned: VisiblePlaylistSession): Boolean = visible && owned.generation == pageGeneration
    private fun isVisible(generation: Long): Boolean = visible && generation == visibilityGeneration

    private fun isPreviewCurrent(revision: PlaylistPreviewRevision): Boolean {
        val current = latestLibrary ?: return true
        return revision.catalogRevision == current.revision && revision.rulesRevision == current.rulesRevision
    }

    private fun previewDiffers(state: PlaylistUiState, overview: LibraryOverview): Boolean {
        val folder = state.folderDraft?.preview
        val keyword = state.keywordDraft?.preview
        val imported = state.importDraft?.preview
        val revision = when {
            folder != null -> PlaylistPreviewRevision(folder.fence.catalogRevision, folder.fence.rulesRevision)
            keyword != null -> keyword.revision
            imported != null -> imported.revision
            else -> return false
        }
        return revision.catalogRevision != overview.revision || revision.rulesRevision != overview.rulesRevision
    }

    private fun validatedName(raw: String): String? = try {
        playlistName(raw)
    } catch (_: IllegalArgumentException) {
        mutableState.update { it.copy(issue = PlaylistUiIssue.INVALID_NAME) }
        null
    }

    private fun fail(error: Exception) {
        mutableState.update { it.copy(loading = false, saving = false, favoritePending = false, issue = playlistUiIssue(error)) }
    }

    private suspend fun closeFolderPreview(owned: VisiblePlaylistSession) {
        val id = owned.folderPreviewId ?: return
        folders.closePreview(id)
        owned.folderPreviewId = null
    }

    private suspend fun closeKeywordPreview(owned: VisiblePlaylistSession) {
        val id = owned.keywordPreviewId ?: return
        keywords.closePreview(id)
        owned.keywordPreviewId = null
    }

    private suspend fun closeImportPreview(owned: VisiblePlaylistSession) {
        val id = owned.importPreviewId ?: return
        exchange.closePreview(id)
        owned.importPreviewId = null
    }

    private suspend fun closePreviews(owned: VisiblePlaylistSession) {
        closeFolderPreview(owned)
        closeKeywordPreview(owned)
        closeImportPreview(owned)
    }

    private suspend fun closeOwned(owned: VisiblePlaylistSession): Boolean = withContext(NonCancellable) {
        var complete = true
        suspend fun attempt(close: suspend () -> Unit) {
            try {
                withTimeout(CLOSE_TIMEOUT_MS) { close() }
            } catch (_: Exception) {
                complete = false
            }
        }
        attempt { closeFolderPreview(owned) }
        attempt { closeKeywordPreview(owned) }
        attempt { closeImportPreview(owned) }
        attempt {
            val selection = owned.addSelection
            if (selection != null) {
                selection.close()
                owned.addSelection = null
            }
        }
        attempt {
            val view = owned.playlist
            if (view != null) {
                playlists.closeView(view.viewId)
                owned.playlist = null
            }
        }
        attempt {
            val catalog = owned.catalog
            if (catalog != null) {
                playlists.closeView(catalog.viewId)
                owned.catalog = null
            }
        }
        attempt {
            val libraryView = owned.library
            if (libraryView != null) {
                closeLibraryView(libraryView.viewId)
                owned.library = null
            }
        }
        complete
    }

    private fun persistName(draft: PlaylistNameDraft) {
        saved[DRAFT_SCREEN] = when (draft.purpose) {
            PlaylistNamePurpose.CREATE, PlaylistNamePurpose.RENAME -> PlaylistScreen.NAME.name
            PlaylistNamePurpose.FOLDER -> PlaylistScreen.FOLDER.name
            PlaylistNamePurpose.KEYWORD -> PlaylistScreen.KEYWORD.name
        }
        saved[NAME] = draft.name
        saved[NAME_PURPOSE] = draft.purpose.name
        saved[NAME_OPERATION] = draft.operationId
        saved[NAME_PLAYLIST] = draft.playlistId
        saved[NAME_REVISION] = draft.expectedRevision
    }

    private fun persistMove(draft: PlaylistMoveDraft) {
        saved[DRAFT_SCREEN] = PlaylistScreen.MOVE.name
        saved[PLAYLIST_ID] = draft.playlistId
        saved[MOVE_OPERATION] = draft.operationId
        saved[MOVE_PLAYLIST] = draft.playlistId
        saved[MOVE_MEMBER] = draft.memberId
        saved[MOVE_ORDINAL] = draft.fromOrdinal
        saved[MOVE_DESTINATION] = draft.destination
        saved[MOVE_REVISION] = draft.expectedRevision
        saved[MOVE_TITLE] = draft.titleHint
    }

    private fun persistImport(draft: PlaylistImportDraft) {
        saved[DRAFT_SCREEN] = PlaylistScreen.IMPORT.name
        saved[IMPORT_OPERATION] = draft.operationId
        saved[IMPORT_NEW_PLAYLIST] = draft.newPlaylistId
        saved[IMPORT_FORMAT] = draft.format.name
        saved[IMPORT_ENCODING] = draft.encoding.name
        saved[IMPORT_NAME] = draft.name
        saved[IMPORT_TARGET] = draft.target?.playlistId
        saved[IMPORT_TARGET_REVISION] = draft.target?.expectedRevision
        // 文档token、URI、preview和针对旧preview的确认值均不进入SavedState。
    }

    private fun clearSavedDraft() {
        listOf(DRAFT_SCREEN, NAME, NAME_PURPOSE, NAME_OPERATION, NAME_PLAYLIST, KEYWORD,
            MOVE_OPERATION, MOVE_PLAYLIST, MOVE_MEMBER, MOVE_DESTINATION, MOVE_TITLE,
            IMPORT_OPERATION, IMPORT_NEW_PLAYLIST, IMPORT_FORMAT, IMPORT_ENCODING, IMPORT_NAME, IMPORT_TARGET)
            .forEach { key -> saved.remove<String>(key) }
        listOf(NAME_REVISION, MOVE_ORDINAL, MOVE_REVISION, IMPORT_TARGET_REVISION).forEach { key -> saved.remove<Long>(key) }
    }

    private fun restoreHiddenMoveDraft() {
        val state = mutableState.value
        if (state.moveDraft != null || savedText(DRAFT_SCREEN) != PlaylistScreen.MOVE.name) return
        val restored = restoreState()
        if (restored.moveDraft != null) mutableState.value = restored.copy(overview = state.overview, lastCommit = state.lastCommit)
    }

    private fun restoreState(): PlaylistUiState {
        val playlistId = savedIdentifier(PLAYLIST_ID)
        val requestedScreen = savedText(DRAFT_SCREEN)
        if (requestedScreen == PlaylistScreen.MOVE.name) {
            val draft = restoreMove()
            return if (draft == null) PlaylistUiState(playlistId = playlistId,
                screen = if (playlistId == null) PlaylistScreen.CATALOG else PlaylistScreen.DETAILS)
            else PlaylistUiState(screen = PlaylistScreen.MOVE, playlistId = draft.playlistId, moveDraft = draft,
                pageStart = (draft.fromOrdinal / PlaylistLimits.PAGE_SIZE) * PlaylistLimits.PAGE_SIZE)
        }
        if (requestedScreen == PlaylistScreen.IMPORT.name) {
            val draft = restoreImport()
            return PlaylistUiState(screen = if (draft != null) PlaylistScreen.IMPORT else
                if (playlistId == null) PlaylistScreen.CATALOG else PlaylistScreen.DETAILS,
                playlistId = playlistId, importDraft = draft)
        }
        val purpose = PlaylistNamePurpose.entries.firstOrNull { it.name == savedText(NAME_PURPOSE) }
        val name = if (purpose != null) PlaylistNameDraft(savedIdentifier(NAME_OPERATION) ?: newId(),
            savedIdentifier(NAME_PLAYLIST) ?: newId(), savedNonnegativeLong(NAME_REVISION) ?: 0L, savedText(NAME).orEmpty(), purpose) else null
        val screen = when (purpose) {
            PlaylistNamePurpose.CREATE, PlaylistNamePurpose.RENAME -> PlaylistScreen.NAME
            PlaylistNamePurpose.FOLDER -> PlaylistScreen.FOLDER
            PlaylistNamePurpose.KEYWORD -> PlaylistScreen.KEYWORD
            null -> if (playlistId == null) PlaylistScreen.CATALOG else PlaylistScreen.DETAILS
        }
        return PlaylistUiState(screen = screen, playlistId = playlistId, nameDraft = name,
            keywordDraft = if (purpose == PlaylistNamePurpose.KEYWORD) PlaylistKeywordDraft(savedText(KEYWORD).orEmpty()) else null,
            folderDraft = if (purpose == PlaylistNamePurpose.FOLDER && name != null)
                PlaylistFolderDraft(name.operationId, FolderPlaylistPurpose.CREATE, name.playlistId, 0L, null) else null)
    }

    private fun restoreMove(): PlaylistMoveDraft? {
        val operationId = savedIdentifier(MOVE_OPERATION) ?: return null
        val playlistId = savedIdentifier(MOVE_PLAYLIST) ?: return null
        val memberId = savedIdentifier(MOVE_MEMBER) ?: return null
        val ordinal = savedNonnegativeLong(MOVE_ORDINAL) ?: return null
        val revision = savedNonnegativeLong(MOVE_REVISION) ?: return null
        val destination = savedText(MOVE_DESTINATION, 20) ?: return null
        return PlaylistMoveDraft(operationId, memberId, ordinal, destination, revision, playlistId, savedText(MOVE_TITLE))
    }

    private fun restoreImport(): PlaylistImportDraft? {
        val operationId = savedIdentifier(IMPORT_OPERATION) ?: return null
        val newPlaylistId = savedIdentifier(IMPORT_NEW_PLAYLIST) ?: return null
        val format = PlaylistTransferFormat.entries.firstOrNull { it.name == savedText(IMPORT_FORMAT) } ?: return null
        val encoding = PlaylistTransferEncoding.entries.firstOrNull { it.name == savedText(IMPORT_ENCODING) } ?: return null
        if (format != PlaylistTransferFormat.M3U && encoding != PlaylistTransferEncoding.UTF8) return null
        val rawTarget = saved.get<Any?>(IMPORT_TARGET)
        val rawTargetRevision = saved.get<Any?>(IMPORT_TARGET_REVISION)
        if ((rawTarget == null) != (rawTargetRevision == null)) return null
        val target = if (rawTarget == null) null else PlaylistImportTarget(savedIdentifier(IMPORT_TARGET) ?: return null,
            savedNonnegativeLong(IMPORT_TARGET_REVISION) ?: return null)
        return PlaylistImportDraft(operationId, newPlaylistId, format, encoding, target, name = savedText(IMPORT_NAME).orEmpty())
    }

    private fun savedText(key: String, maxLength: Int = PlaylistLimits.MAX_FIELD_CHARACTERS): String? =
        (saved.get<Any?>(key) as? String)?.takeIf { it.length <= maxLength }

    private fun savedIdentifier(key: String): String? =
        savedText(key, PlaylistLimits.MAX_IDENTIFIER_CHARACTERS)?.takeIf { it.isNotBlank() }

    private fun savedNonnegativeLong(key: String): Long? = (saved.get<Any?>(key) as? Long)?.takeIf { it >= 0L }

    override fun onCleared() {
        onHidden()
        super.onCleared()
    }

    private class VisiblePlaylistSession(val generation: Long, var addSelection: PlaylistAddSelection? = null,
        val reviewNameRevision: Boolean = false) {
        var scope: CoroutineScope? = null
        var work: Job? = null
        var favoriteObserver: Job? = null
        var catalog: PlaylistCatalogView? = null
        var playlist: PlaylistViewToken? = null
        var library: io.github.onlinemo.meloxwatch.data.library.LibraryViewToken? = null
        var folderPreviewId: String? = null
        var keywordPreviewId: String? = null
        var importPreviewId: String? = null
    }

    companion object {
        private const val CLOSE_TIMEOUT_MS = 2_500L
        private const val PLAYLIST_ID = "playlists.current"
        private const val DRAFT_SCREEN = "playlists.draftScreen"
        private const val NAME = "playlists.name"
        private const val NAME_PURPOSE = "playlists.namePurpose"
        private const val NAME_OPERATION = "playlists.nameOperation"
        private const val NAME_PLAYLIST = "playlists.nameTarget"
        private const val NAME_REVISION = "playlists.nameRevision"
        private const val KEYWORD = "playlists.keyword"
        private const val MOVE_OPERATION = "playlists.moveOperation"
        private const val MOVE_PLAYLIST = "playlists.movePlaylist"
        private const val MOVE_MEMBER = "playlists.moveMember"
        private const val MOVE_ORDINAL = "playlists.moveOrdinal"
        private const val MOVE_DESTINATION = "playlists.moveDestination"
        private const val MOVE_REVISION = "playlists.moveRevision"
        private const val MOVE_TITLE = "playlists.moveTitle"
        private const val IMPORT_OPERATION = "playlists.importOperation"
        private const val IMPORT_NEW_PLAYLIST = "playlists.importNewPlaylist"
        private const val IMPORT_FORMAT = "playlists.importFormat"
        private const val IMPORT_ENCODING = "playlists.importEncoding"
        private const val IMPORT_NAME = "playlists.importName"
        private const val IMPORT_TARGET = "playlists.importTarget"
        private const val IMPORT_TARGET_REVISION = "playlists.importTargetRevision"
        private fun newId(): String = UUID.randomUUID().toString()
    }
}

private fun playlistUiIssue(error: Exception): PlaylistUiIssue = when (error) {
    is TimeoutCancellationException -> PlaylistUiIssue.BUSY
    is PlaylistException -> when (error.issue) {
        PlaylistIssue.BUSY -> PlaylistUiIssue.BUSY
        PlaylistIssue.CONFLICT -> PlaylistUiIssue.CONFLICT
        PlaylistIssue.INVALID_INPUT -> PlaylistUiIssue.INVALID_INPUT
        PlaylistIssue.NOT_FOUND -> PlaylistUiIssue.NOT_FOUND
        PlaylistIssue.AUTOMATIC_MEMBERS -> PlaylistUiIssue.AUTOMATIC_MEMBERS
        PlaylistIssue.VIEW_EXPIRED -> PlaylistUiIssue.VIEW_EXPIRED
        PlaylistIssue.SOURCE_CHANGED -> PlaylistUiIssue.SOURCE_CHANGED
        PlaylistIssue.SOURCE_UNAVAILABLE -> PlaylistUiIssue.SOURCE_UNAVAILABLE
        PlaylistIssue.INCOMPLETE -> PlaylistUiIssue.INCOMPLETE_SCAN
        PlaylistIssue.STORAGE_FULL -> PlaylistUiIssue.STORAGE_FULL
        PlaylistIssue.STORAGE_UNAVAILABLE -> PlaylistUiIssue.STORAGE_UNAVAILABLE
        PlaylistIssue.RECOVERY_REQUIRED, PlaylistIssue.REVISION_EXHAUSTED -> PlaylistUiIssue.RECOVERY_REQUIRED
    }
    is LibraryException -> when (error.issue) {
        LibraryIssue.BUSY -> PlaylistUiIssue.BUSY
        LibraryIssue.CONFLICT -> PlaylistUiIssue.CONFLICT
        LibraryIssue.INVALID_INPUT -> PlaylistUiIssue.INVALID_INPUT
        LibraryIssue.VIEW_EXPIRED -> PlaylistUiIssue.VIEW_EXPIRED
        LibraryIssue.SOURCE_CHANGED -> PlaylistUiIssue.SOURCE_CHANGED
        LibraryIssue.SOURCE_UNAVAILABLE, LibraryIssue.PERMISSION_REVOKED, LibraryIssue.FILE_MISSING -> PlaylistUiIssue.SOURCE_UNAVAILABLE
        LibraryIssue.INCOMPLETE -> PlaylistUiIssue.INCOMPLETE_SCAN
        LibraryIssue.STORAGE_FULL -> PlaylistUiIssue.STORAGE_FULL
        LibraryIssue.STORAGE_UNAVAILABLE -> PlaylistUiIssue.STORAGE_UNAVAILABLE
        LibraryIssue.RECOVERY_REQUIRED -> PlaylistUiIssue.RECOVERY_REQUIRED
    }
    is PlaylistExchangeException -> when (error.issue) {
        PlaylistExchangeIssue.INVALID_VALUE, PlaylistExchangeIssue.INVALID_ENCODING, PlaylistExchangeIssue.MALFORMED,
        PlaylistExchangeIssue.UNKNOWN_FIELD -> PlaylistUiIssue.INVALID_DOCUMENT
        PlaylistExchangeIssue.UNSUPPORTED_VERSION, PlaylistExchangeIssue.UNSUPPORTED_FORMAT -> PlaylistUiIssue.UNSUPPORTED_DOCUMENT
        PlaylistExchangeIssue.LIMIT_EXCEEDED -> PlaylistUiIssue.DOCUMENT_TOO_LARGE
        PlaylistExchangeIssue.READ_FAILED -> PlaylistUiIssue.DOCUMENT_READ
        PlaylistExchangeIssue.WRITE_FAILED -> PlaylistUiIssue.DOCUMENT_WRITE
    }
    is IllegalArgumentException -> PlaylistUiIssue.INVALID_INPUT
    else -> PlaylistUiIssue.STORAGE_UNAVAILABLE
}
