package io.github.onlinemo.meloxwatch.companion.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.onlinemo.meloxwatch.companion.data.CompanionCachedPage
import io.github.onlinemo.meloxwatch.companion.data.CompanionColor
import io.github.onlinemo.meloxwatch.companion.data.CompanionConnectionPhase
import io.github.onlinemo.meloxwatch.companion.data.CompanionConnectionState
import io.github.onlinemo.meloxwatch.companion.data.CompanionDisplayDraft
import io.github.onlinemo.meloxwatch.companion.data.CompanionDocuments
import io.github.onlinemo.meloxwatch.companion.data.CompanionEditPhase
import io.github.onlinemo.meloxwatch.companion.data.CompanionEndpoint
import io.github.onlinemo.meloxwatch.companion.data.CompanionIconRole
import io.github.onlinemo.meloxwatch.companion.data.CompanionLimits
import io.github.onlinemo.meloxwatch.companion.data.CompanionLocalState
import io.github.onlinemo.meloxwatch.companion.data.CompanionPageWindow
import io.github.onlinemo.meloxwatch.companion.data.CompanionTarget
import io.github.onlinemo.meloxwatch.companion.data.CompanionThemeBackground
import io.github.onlinemo.meloxwatch.companion.data.CompanionThemePreview
import io.github.onlinemo.meloxwatch.companion.data.CompanionUploadPhase
import io.github.onlinemo.meloxwatch.companion.data.CompanionUploadProgress
import io.github.onlinemo.meloxwatch.companion.data.localFingerprint
import io.github.onlinemo.meloxwatch.companion.transfer.CompanionTransferClient
import io.github.onlinemo.meloxwatch.data.transfer.TransferCapability
import io.github.onlinemo.meloxwatch.data.transfer.TransferCatalogKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferCatalogRow
import io.github.onlinemo.meloxwatch.data.transfer.TransferDisplayKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferDisplayPatch
import io.github.onlinemo.meloxwatch.data.transfer.TransferDisplayState
import io.github.onlinemo.meloxwatch.data.transfer.TransferException
import io.github.onlinemo.meloxwatch.data.transfer.TransferFingerprint
import io.github.onlinemo.meloxwatch.data.transfer.TransferIssue
import io.github.onlinemo.meloxwatch.data.transfer.TransferLibraryRevision
import io.github.onlinemo.meloxwatch.data.transfer.TransferPlaylistEdit
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal enum class CompanionScreen { CONNECTION, LIBRARY, PLAYLIST, DISPLAY, THEME }
internal enum class CompanionTrackSelection { NONE, PLAYLIST_ADD, DISPLAY_ADD, DISPLAY_REMOVE }

/** 连接输入只留内存，不进入实例恢复或本地记录。 */
internal class CompanionConnectionForm(
    val address: String = "", val port: String = "", val label: String = "", val fingerprint: String = "", val code: String = "",
    val requested: Set<TransferCapability> = emptySet(),
) {
    fun edited(
        address: String = this.address, port: String = this.port, label: String = this.label,
        fingerprint: String = this.fingerprint, code: String = this.code, requested: Set<TransferCapability> = this.requested,
    ): CompanionConnectionForm = CompanionConnectionForm(address, port, label, fingerprint, code, requested.toSet())
    override fun toString(): String = "<local-connection-input>"
}

internal class CompanionPlaylistSelection(
    val target: TransferFingerprint, val row: TransferCatalogRow.Playlist, val libraryRevision: TransferLibraryRevision,
    val sessionId: String,
) { override fun toString(): String = "<selected-device-playlist>" }

internal class CompanionGroupSelection(
    val target: TransferFingerprint, val state: TransferDisplayState,
    val sessionId: String,
) { override fun toString(): String = "<selected-device-group>" }

internal data class CompanionUiState(
    val screen: CompanionScreen = CompanionScreen.CONNECTION,
    val local: CompanionLocalState? = null,
    val connection: CompanionConnectionState = CompanionConnectionState(),
    val form: CompanionConnectionForm = CompanionConnectionForm(),
    val busy: Boolean = false,
    val stopping: Boolean = false,
    val recovering: Boolean = false,
    val documentIssue: TransferIssue? = null,
    val issue: TransferIssue? = null,
    val pickerUnavailable: Boolean = false,
    val pageWindow: CompanionPageWindow = emptyWindow(TransferCatalogKind.TRACKS),
    val playlist: CompanionPlaylistSelection? = null,
    val group: CompanionGroupSelection? = null,
    val trackSelection: CompanionTrackSelection = CompanionTrackSelection.NONE,
    val selectedTracks: Set<String> = emptySet(),
    val selectedTrackRevision: TransferLibraryRevision? = null,
    val uploadProgress: CompanionUploadProgress? = null,
    val themePreview: CompanionThemePreview? = null,
) {
    fun allowed(capability: TransferCapability): Boolean = connection.phase == CompanionConnectionPhase.READY && capability in connection.capabilities
    val available: Boolean get() = local != null && !busy && !stopping && !recovering && documentIssue != TransferIssue.RECOVERY_REQUIRED && connection.phase != CompanionConnectionPhase.RECOVERY_REQUIRED
    override fun toString(): String = "<companion-ui-state>"
}

internal fun emptyWindow(kind: TransferCatalogKind, search: String = "", parentId: String? = null): CompanionPageWindow =
    CompanionPageWindow(kind, search, parentId, emptyList(), -1)

/**
 * 明确意图只接纳一个业务任务，停止与恢复使用独立有界控制任务。
 * 未退出的物理句柄仍可触达关闭控制；后台或恢复状态不自动连接、
 * 提交草稿或修改手表的播放会话。
 */
internal class CompanionViewModel(
    private val documents: CompanionDocuments,
    private val transfer: CompanionTransferClient,
) : ViewModel() {
    private val mutableState = MutableStateFlow(CompanionUiState())
    val state: StateFlow<CompanionUiState> = mutableState.asStateFlow()
    private val controlScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null
    private var stopWork: Job? = null
    private var recoveryWork: Job? = null
    private var forgetAfterStop = false
    private var viewGeneration = 0L

    init {
        viewModelScope.launch { transfer.state.collect { connection -> mutableState.update { it.copy(connection = connection) } } }
        viewModelScope.launch { documents.issue.collect { issue -> mutableState.update { it.copy(documentIssue = issue) } } }
        reloadLocal()
    }

    fun reloadLocal() {
        if (work != null || state.value.local != null) return
        launchWork {
            val local = documents.initialize()
            mutableState.update { old -> old.copy(local = local, form = old.form.edited(
                label = local.target?.label.orEmpty(), fingerprint = local.target?.fingerprint?.forLocalDisplay().orEmpty())) }
        }
    }

    fun editConnection(change: (CompanionConnectionForm) -> CompanionConnectionForm) {
        mutableState.update { it.copy(form = change(it.form), issue = null) }
    }

    fun navigate(screen: CompanionScreen) {
        viewGeneration = Math.addExact(viewGeneration, 1L)
        if (work != null || recoveryWork != null) stop()
        val kind = when (screen) {
            CompanionScreen.PLAYLIST -> TransferCatalogKind.PLAYLISTS
            CompanionScreen.DISPLAY -> TransferCatalogKind.ALBUMS
            else -> TransferCatalogKind.TRACKS
        }
        mutableState.update { it.copy(screen = screen, pageWindow = emptyWindow(kind), playlist = null, group = null, trackSelection = CompanionTrackSelection.NONE,
            selectedTracks = emptySet(), selectedTrackRevision = null, themePreview = null, issue = null, pickerUnavailable = false) }
    }

    fun back() {
        val old = state.value
        when {
            old.trackSelection != CompanionTrackSelection.NONE -> cancelTrackSelection()
            old.screen == CompanionScreen.PLAYLIST && old.playlist != null -> showPlaylists()
            old.group != null && old.screen in setOf(CompanionScreen.DISPLAY, CompanionScreen.LIBRARY) -> showGroups(old.group.state.kind)
            else -> navigate(CompanionScreen.CONNECTION)
        }
    }

    fun dismissIssue() {
        transfer.clearIssue()
        mutableState.update {
            it.copy(issue = null, pickerUnavailable = false)
        }
    }
    fun pickerUnavailable() { mutableState.update { it.copy(pickerUnavailable = true) } }

    fun pair() = launchWork {
        val form = state.value.form
        if (!form.code.matches(Regex("[0-9]{8}")) || form.requested.isEmpty()) throw TransferException(TransferIssue.INVALID_INPUT)
        val address = CompanionEndpoint.parse(form.address, form.port)
        val target = CompanionTarget(localFingerprint(form.fingerprint), form.label)
        clearCatalog()
        transfer.pair(address, target, form.code, form.requested)
        local(documents.update { it.copy(target = target) })
        mutableState.update { it.copy(form = it.form.edited(code = "")) }
    }

    fun authenticate() = launchWork {
        val form = state.value.form
        val target = transfer.authenticate(CompanionEndpoint.parse(form.address, form.port))
        local(documents.update { it.copy(target = target) })
    }

    /** 本地撤配对立即清缓存；持久草稿仍绑定原目标。 */
    fun stop(forget: Boolean = false) {
        forgetAfterStop = forgetAfterStop || forget
        if (forget) clearCatalog()
        recoveryWork?.cancel(CancellationException("Companion recovery stopped"))
        val operation = work
        operation?.cancel(CancellationException("Companion stopped"))
        documents.cancel()
        val network = transfer.disconnect(forget)
        if (stopWork?.isActive == true) return
        mutableState.update { it.copy(stopping = true, form = it.form.edited(code = "")) }
        val control = controlScope.launch(start = CoroutineStart.LAZY) {
            try {
                withTimeout(CompanionLimits.RETIREMENT_WAIT_MS) {
                    var failed = false
                    try { network.await() }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { failed = true }
                    try { documents.awaitStopped() }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { failed = true }
                    if (failed) throw TransferException(TransferIssue.RECOVERY_REQUIRED)
                    operation?.join()
                    if (forgetAfterStop) {
                        local(documents.update {
                            it.copy(target = null)
                        })
                        forgetAfterStop = false
                    }
                }
            } catch (_: TimeoutCancellationException) {
                documents.markRecoveryRequired()
                show(TransferException(TransferIssue.RECOVERY_REQUIRED))
            }
              catch (error: CancellationException) { throw error }
              catch (error: Exception) { show(error) }
            finally {
                mutableState.update { it.copy(stopping = false, form = it.form.edited(address = "", port = "", code = "")) }
                stopWork = null
            }
        }
        stopWork = control
        control.start()
    }

    fun background() {
        mutableState.update {
            it.copy(themePreview = null)
        }
        stop()
    }

    fun retryRecovery() {
        if (recoveryWork != null || stopWork?.isActive == true) {
            show(TransferException(TransferIssue.BUSY))
            return
        }
        mutableState.update { it.copy(recovering = true, issue = null) }
        val control = controlScope.launch(start = CoroutineStart.LAZY) {
            try {
                withTimeout(CompanionLimits.RETIREMENT_WAIT_MS) {
                    // 旧业务可能正在等 TLS 退出，先触达网络关闭，不用 busy 封死恢复入口。
                    transfer.retryRetirement()
                    documents.retryRetirement()
                    val restored = documents.update { recoverDrafts(it).let { local -> if (forgetAfterStop) local.copy(target = null) else local } }
                    forgetAfterStop = false
                    local(restored)
                }
            } catch (_: TimeoutCancellationException) {
                documents.markRecoveryRequired()
                show(TransferException(TransferIssue.RECOVERY_REQUIRED))
            }
              catch (error: CancellationException) { throw error }
              catch (error: Exception) { show(error) }
            finally {
                recoveryWork = null
                mutableState.update {
                    it.copy(recovering = false)
                }
            }
        }
        recoveryWork = control
        control.start()
    }

    fun prepareAudio(uri: Uri) = launchWork {
        val draft = documents.prepareAudio(uri)
        local(documents.update {
            old -> if (old.upload?.operationId != draft.operationId) throw TransferException(TransferIssue.CONFLICT)
            old
        })
        mutableState.update { it.copy(uploadProgress = null, pickerUnavailable = false) }
    }

    fun prepareIcon(uri: Uri, role: CompanionIconRole, licenseName: String, licenseText: String) = launchWork {
        documents.prepareIcon(uri, role, licenseName, licenseText)
        local(documents.update { it })
        mutableState.update { it.copy(pickerUnavailable = false, themePreview = null) }
    }

    fun saveTheme(
        name: String, author: String, licenseName: String, licenseText: String, colors: Map<CompanionColor, String>,
        background: CompanionThemeBackground, blur: String, mask: String,
    ) = launchWork {
        val blurLevel = blur.toIntOrNull() ?: throw TransferException(TransferIssue.INVALID_INPUT)
        val maskPercent = mask.toIntOrNull() ?: throw TransferException(TransferIssue.INVALID_INPUT)
        local(documents.update { old -> old.copy(theme = old.theme.edited(name, author, licenseName, licenseText, colors, background, blurLevel, maskPercent)) })
        mutableState.update { it.copy(themePreview = null) }
    }

    fun removeIcon(role: CompanionIconRole) = launchWork {
        local(documents.update { old -> old.copy(theme = old.theme.edited(icons = old.theme.icons.filter { it.role != role })) })
        documents.retryRetirement()
        mutableState.update { it.copy(themePreview = null) }
    }

    fun previewTheme() = launchWork {
        val generation = viewGeneration
        val preview = documents.previewTheme()
        if (generation != viewGeneration) throw CancellationException("Theme view changed")
        mutableState.update { it.copy(themePreview = preview) }
    }

    fun prepareTheme() = launchWork {
        documents.prepareTheme()
        local(documents.update {
            it
        })
        mutableState.update {
            it.copy(uploadProgress = null)
        }
    }

    fun sendUpload() = launchWork {
        val old = state.value.local?.upload ?: throw TransferException(TransferIssue.NOT_FOUND)
        val target = ready(old.declaration.kind.capability)
        if (old.target != null && old.target != target) throw TransferException(TransferIssue.WRONG_TARGET)
        if (old.phase !in setOf(CompanionUploadPhase.READY, CompanionUploadPhase.UNCERTAIN, CompanionUploadPhase.FAILED, CompanionUploadPhase.CANCELLED)) throw TransferException(TransferIssue.BUSY)
        val draft = old.bind(target).withResult(CompanionUploadPhase.SENDING)
        local(documents.update {
            record -> if (record.upload?.operationId != draft.operationId) throw TransferException(TransferIssue.CONFLICT)
            record.copy(upload = draft)
        })
        mutableState.update { it.copy(uploadProgress = CompanionUploadProgress(CompanionUploadPhase.SENDING, 0L, checkNotNull(draft.declaration.declaredBytes))) }
        try {
            val receipt = transfer.upload(draft, { documents.verifiedFile(draft) }) { progress -> mutableState.update { it.copy(uploadProgress = progress) } }
            local(documents.update {
                record -> if (record.upload?.operationId != draft.operationId) throw TransferException(TransferIssue.CONFLICT)
                record.copy(upload = draft.withResult(CompanionUploadPhase.COMPLETED, receipt), lastReceipt = receipt)
            })
            mutableState.update { it.copy(uploadProgress = CompanionUploadProgress(CompanionUploadPhase.COMPLETED, checkNotNull(draft.declaration.declaredBytes), checkNotNull(draft.declaration.declaredBytes))) }
        } catch (error: Exception) {
            preserveAfterFailure { record ->
                val actual = record.upload
                if (actual?.operationId != draft.operationId || actual.receipt != null) record
                else record.copy(upload = actual.withResult(if (uncertain(error)) CompanionUploadPhase.UNCERTAIN else CompanionUploadPhase.FAILED))
            }
            mutableState.update { current -> current.copy(uploadProgress = current.uploadProgress?.let { progress ->
                    CompanionUploadProgress(if (uncertain(error)) CompanionUploadPhase.UNCERTAIN else CompanionUploadPhase.FAILED, progress.sentBytes, progress.totalBytes,
                    when (error) {
                        is CancellationException -> null
                        is TransferException -> error.issue
                        else -> TransferIssue.INVALID_INPUT
                    })
                }) }
            throw error
        }
    }

    fun discardUpload() = launchWork {
        local(documents.discardPreparedCopy())
        mutableState.update {
            it.copy(uploadProgress = null)
        }
    }

    fun refresh(kind: TransferCatalogKind = state.value.pageWindow.kind, search: String = state.value.pageWindow.search, parentId: String? = state.value.pageWindow.parentId) = launchWork {
        performRead(kind, search, parentId, previous = false)
    }

    fun nextPage() {
        val window = state.value.pageWindow
        window.cachedNext()?.let {
            next -> mutableState.update {
                it.copy(pageWindow = next)
            }
            return
        }
        if (window.current?.page?.nextCursor == null) return
        launchWork { performRead(window.kind, window.search, window.parentId, previous = true) }
    }

    fun previousPage() { mutableState.update { it.copy(pageWindow = it.pageWindow.previous()) } }

    private suspend fun performRead(kind: TransferCatalogKind, search: String, parentId: String?, previous: Boolean) {
        val connection = state.value.connection
        val target = ready(TransferCapability.READ_LIBRARY)
        val session = connection.sessionId ?: throw TransferException(TransferIssue.EXPIRED)
        val generation = viewGeneration
        val old = state.value.pageWindow
        if (previous && (old.kind != kind || old.search != search || old.parentId != parentId || old.current?.target != target || old.current?.sessionId != session)) throw TransferException(TransferIssue.CONFLICT)
        val page = transfer.readPage(kind, search, parentId, if (previous) old.current?.page else null)
        if (generation != viewGeneration) throw CancellationException("Catalog view changed")
        val cached = CompanionCachedPage(target, session, page, System.currentTimeMillis())
        val window = if (previous) old.append(cached) else emptyWindow(kind, search, parentId).append(cached)
        mutableState.update { it.copy(pageWindow = window, issue = null) }
    }

    fun selectPlaylist(row: TransferCatalogRow.Playlist) = launchWork {
        val cached = currentPage(TransferCatalogKind.PLAYLISTS)
        if (cached.page.rows.none { it === row }) throw TransferException(TransferIssue.CONFLICT)
        endTrackSelection()
        mutableState.update { it.copy(screen = CompanionScreen.PLAYLIST, playlist = CompanionPlaylistSelection(cached.target, row, cached.page.libraryRevision, cached.sessionId), group = null) }
        performRead(TransferCatalogKind.PLAYLIST_MEMBERS, "", row.playlistId, previous = false)
    }

    fun showPlaylists() {
        viewGeneration = Math.addExact(viewGeneration, 1L)
        if (work != null) stop()
        endTrackSelection()
        mutableState.update { it.copy(screen = CompanionScreen.PLAYLIST, playlist = null, pageWindow = emptyWindow(TransferCatalogKind.PLAYLISTS)) }
        if (state.value.available && state.value.allowed(TransferCapability.READ_LIBRARY)) refresh(TransferCatalogKind.PLAYLISTS, "", null)
    }

    fun choosePlaylistTracks() {
        val selected = state.value.playlist
        if (!state.value.available || selected == null || selected.row.automatic || !state.value.allowed(TransferCapability.WRITE_PLAYLISTS)) {
            show(TransferException(TransferIssue.PERMISSION_DENIED))
            return
        }
        if (selected.target != state.value.connection.target?.fingerprint) {
            show(TransferException(TransferIssue.WRONG_TARGET))
            return
        }
        endTrackSelection()
        mutableState.update { it.copy(screen = CompanionScreen.LIBRARY, trackSelection = CompanionTrackSelection.PLAYLIST_ADD, pageWindow = emptyWindow(TransferCatalogKind.TRACKS)) }
        refresh(TransferCatalogKind.TRACKS, "", null)
    }

    fun chooseDisplayTracks(remove: Boolean) {
        val selected = state.value.group
        if (!state.value.available || selected == null || !state.value.allowed(TransferCapability.WRITE_DISPLAY_METADATA)) {
            show(TransferException(TransferIssue.PERMISSION_DENIED))
            return
        }
        if (selected.target != state.value.connection.target?.fingerprint) {
            show(TransferException(TransferIssue.WRONG_TARGET))
            return
        }
        endTrackSelection()
        val kind = if (remove) membersKind(selected.state.kind) else TransferCatalogKind.TRACKS
        val parent = if (remove) selected.state.entityId else null
        mutableState.update { it.copy(screen = CompanionScreen.LIBRARY,
            trackSelection = if (remove) CompanionTrackSelection.DISPLAY_REMOVE else CompanionTrackSelection.DISPLAY_ADD, pageWindow = emptyWindow(kind, parentId = parent)) }
        refresh(kind, "", parent)
    }

    fun cancelTrackSelection() {
        val old = state.value
        val screen = if (old.trackSelection == CompanionTrackSelection.PLAYLIST_ADD) CompanionScreen.PLAYLIST else CompanionScreen.DISPLAY
        val kind = if (screen == CompanionScreen.PLAYLIST) TransferCatalogKind.PLAYLIST_MEMBERS else old.group?.state?.kind?.let(::membersKind) ?: TransferCatalogKind.ALBUMS
        val parent = if (screen == CompanionScreen.PLAYLIST) old.playlist?.row?.playlistId else old.group?.state?.entityId
        endTrackSelection()
        viewGeneration = Math.addExact(viewGeneration, 1L)
        if (work != null) stop()
        mutableState.update { it.copy(screen = screen, pageWindow = if (parent == null) emptyWindow(if (screen == CompanionScreen.PLAYLIST) TransferCatalogKind.PLAYLISTS else TransferCatalogKind.ALBUMS) else emptyWindow(kind, parentId = parent)) }
    }

    fun toggleTrack(row: TransferCatalogRow.Track) {
        try {
            if (!state.value.available) throw TransferException(TransferIssue.BUSY)
            val selected = toggleCompanionTrack(state.value, row)
            mutableState.update { it.copy(selectedTracks = selected.selectedTracks, selectedTrackRevision = selected.selectedTrackRevision) }
        } catch (error: Exception) { show(error) }
    }

    fun clearTrackSelection() { mutableState.update { it.copy(selectedTracks = emptySet(), selectedTrackRevision = null) } }
    private fun endTrackSelection() { mutableState.update { it.copy(selectedTracks = emptySet(), selectedTrackRevision = null, trackSelection = CompanionTrackSelection.NONE) } }

    fun createPlaylist(name: String) = launchWork { preparePlaylistDraft(TransferPlaylistEdit.Create(name)) }
    fun renamePlaylist(name: String) = launchWork { preparePlaylistDraft(TransferPlaylistEdit.Rename(name)) }
    fun deletePlaylist() = launchWork { preparePlaylistDraft(TransferPlaylistEdit.Delete(confirmed = true)) }
    fun removeMember(memberId: String) = launchWork { preparePlaylistDraft(TransferPlaylistEdit.Remove(memberId, confirmed = true)) }
    fun moveMember(memberId: String, destination: String) = launchWork {
        val count = state.value.playlist?.row?.memberCount ?: throw TransferException(TransferIssue.NOT_FOUND)
        val displayedOrdinal = destination.toLongOrNull() ?: throw TransferException(TransferIssue.INVALID_INPUT)
        if (displayedOrdinal !in 1..count) throw TransferException(TransferIssue.INVALID_INPUT)
        preparePlaylistDraft(TransferPlaylistEdit.Move(memberId, displayedOrdinal - 1L))
    }

    private suspend fun preparePlaylistDraft(edit: TransferPlaylistEdit) {
        val selected = state.value.playlist
        val draft = prepareCompanionPlaylistDraft(state.value, edit)
        local(documents.update {
            old -> if (old.playlist?.phase in setOf(CompanionEditPhase.SUBMITTING, CompanionEditPhase.UNCERTAIN)) throw TransferException(TransferIssue.BUSY)
            old.copy(playlist = draft)
        })
        endTrackSelection()
        mutableState.update { it.copy(screen = CompanionScreen.PLAYLIST, playlist = if (edit is TransferPlaylistEdit.Create) null else selected,
            pageWindow = if (selected == null || edit is TransferPlaylistEdit.Create) emptyWindow(TransferCatalogKind.PLAYLISTS) else emptyWindow(TransferCatalogKind.PLAYLIST_MEMBERS, parentId = selected.row.playlistId)) }
    }

    fun prepareSelectedTracks() = launchWork {
        val old = state.value
        val tracks = old.selectedTracks.toList()
        if (tracks.isEmpty()) throw TransferException(TransferIssue.INVALID_INPUT)
        when (old.trackSelection) {
            CompanionTrackSelection.PLAYLIST_ADD -> preparePlaylistDraft(TransferPlaylistEdit.Add(tracks))
            CompanionTrackSelection.DISPLAY_ADD -> prepareDisplayDraft(TransferDisplayPatch.Add(tracks), old.selectedTrackRevision)
            CompanionTrackSelection.DISPLAY_REMOVE -> prepareDisplayDraft(TransferDisplayPatch.Remove(tracks), old.selectedTrackRevision)
            CompanionTrackSelection.NONE -> throw TransferException(TransferIssue.INVALID_INPUT)
        }
    }

    fun submitPlaylist() = launchWork {
        val old = state.value.local?.playlist ?: throw TransferException(TransferIssue.NOT_FOUND)
        ready(TransferCapability.WRITE_PLAYLISTS)
        if (old.phase !in setOf(CompanionEditPhase.DRAFT, CompanionEditPhase.UNCERTAIN)) throw TransferException(TransferIssue.CONFLICT)
        val draft = old.withPhase(CompanionEditPhase.SUBMITTING)
        local(documents.update {
            record -> if (record.playlist?.operationId != draft.operationId) throw TransferException(TransferIssue.CONFLICT)
            record.copy(playlist = draft)
        })
        try {
            val receipt = transfer.editPlaylist(draft)
            local(documents.update {
                record -> if (record.playlist?.operationId != draft.operationId) throw TransferException(TransferIssue.CONFLICT)
                record.copy(playlist = draft.withPhase(CompanionEditPhase.COMPLETED, receipt), lastReceipt = receipt)
            })
            endTrackSelection()
            mutableState.update { it.copy(playlist = null, pageWindow = emptyWindow(TransferCatalogKind.PLAYLISTS)) }
            if (state.value.allowed(TransferCapability.READ_LIBRARY)) performRead(TransferCatalogKind.PLAYLISTS, "", null, previous = false)
        } catch (error: Exception) {
            preserveAfterFailure {
                record -> val actual = record.playlist
                if (actual?.operationId != draft.operationId || actual.receipt != null) record else record.copy(playlist = actual.withPhase(if (error is TransferException && error.issue == TransferIssue.CONFLICT) CompanionEditPhase.CONFLICT else CompanionEditPhase.UNCERTAIN))
            }
            throw error
        }
    }

    fun discardPlaylistDraft() = launchWork { local(documents.update { it.copy(playlist = null) }) }

    fun reloadPlaylistDraftTarget() = launchWork {
        val draft = state.value.local?.playlist ?: throw TransferException(TransferIssue.NOT_FOUND)
        if (ready(TransferCapability.READ_LIBRARY) != draft.target) throw TransferException(TransferIssue.WRONG_TARGET)
        endTrackSelection()
        mutableState.update { it.copy(screen = CompanionScreen.PLAYLIST, playlist = null) }
        performRead(TransferCatalogKind.PLAYLISTS, "", null, previous = false)
    }

    fun selectGroup(row: TransferCatalogRow.Group) = launchWork {
        val cached = currentGroupPage(row)
        val kind = displayKind(cached.page.kind)
        openDisplay(kind, row.entityId)
    }

    fun loadDisplayTarget(kind: TransferDisplayKind, entityId: String) = launchWork { openDisplay(kind, entityId) }

    private suspend fun openDisplay(kind: TransferDisplayKind, entityId: String) {
        val target = ready(TransferCapability.WRITE_DISPLAY_METADATA)
        val context = transfer.readDisplay(kind, entityId)
        endTrackSelection()
        val sessionId = state.value.connection.sessionId ?: throw TransferException(TransferIssue.EXPIRED)
        mutableState.update { it.copy(screen = CompanionScreen.DISPLAY, group = CompanionGroupSelection(target, context, sessionId), playlist = null,
            pageWindow = emptyWindow(membersKind(kind), parentId = entityId)) }
    }

    fun browseGroup(row: TransferCatalogRow.Group) = launchWork {
        val cached = currentGroupPage(row)
        val kind = displayKind(cached.page.kind)
        val context = TransferDisplayState(kind, row.entityId, row.revision, cached.page.libraryRevision, row.title, row.trackCount)
        endTrackSelection()
        mutableState.update { it.copy(screen = CompanionScreen.LIBRARY, group = CompanionGroupSelection(cached.target, context, cached.sessionId), playlist = null) }
        performRead(membersKind(kind), "", row.entityId, previous = false)
    }

    fun showGroupMembers() = launchWork {
        val selected = state.value.group ?: throw TransferException(TransferIssue.NOT_FOUND)
        if (selected.target != ready(TransferCapability.READ_LIBRARY)) throw TransferException(TransferIssue.WRONG_TARGET)
        mutableState.update { it.copy(screen = CompanionScreen.DISPLAY) }
        performRead(membersKind(selected.state.kind), "", selected.state.entityId, previous = false)
    }

    fun showGroups(kind: TransferDisplayKind) {
        viewGeneration = Math.addExact(viewGeneration, 1L)
        if (work != null) stop()
        endTrackSelection()
        val catalog = if (kind == TransferDisplayKind.ALBUM) TransferCatalogKind.ALBUMS else TransferCatalogKind.ARTISTS
        mutableState.update { it.copy(screen = CompanionScreen.DISPLAY, group = null, pageWindow = emptyWindow(catalog)) }
        if (state.value.available && state.value.allowed(TransferCapability.READ_LIBRARY)) refresh(catalog, "", null)
    }

    fun reloadDisplayDraftTarget() = launchWork {
        val draft = state.value.local?.display ?: throw TransferException(TransferIssue.NOT_FOUND)
        if (ready(TransferCapability.WRITE_DISPLAY_METADATA) != draft.target) throw TransferException(TransferIssue.WRONG_TARGET)
        openDisplay(draft.kind, draft.entityId)
    }

    fun renameDisplay(name: String) = launchWork { prepareDisplayDraft(TransferDisplayPatch.Rename(name)) }

    private suspend fun prepareDisplayDraft(patch: TransferDisplayPatch, selectedFence: TransferLibraryRevision? = null) {
        val draft = prepareCompanionDisplayDraft(state.value, patch, selectedFence)
        local(documents.update {
            old -> if (old.display?.phase in setOf(CompanionEditPhase.SUBMITTING, CompanionEditPhase.UNCERTAIN)) throw TransferException(TransferIssue.BUSY)
            old.copy(display = draft)
        })
        endTrackSelection()
        mutableState.update { it.copy(screen = CompanionScreen.DISPLAY, pageWindow = emptyWindow(membersKind(draft.kind), parentId = draft.entityId)) }
    }

    fun previewDisplay() = launchWork {
        val draft = state.value.local?.display ?: throw TransferException(TransferIssue.NOT_FOUND)
        try {
            val preview = transfer.previewDisplay(draft)
            local(documents.update {
                old -> if (old.display?.operationId != draft.operationId) throw TransferException(TransferIssue.CONFLICT)
                old.copy(display = draft.withPreview(preview))
            })
        } catch (error: Exception) {
            if (error is TransferException && error.issue in setOf(TransferIssue.CONFLICT, TransferIssue.NOT_FOUND)) preserveAfterFailure { record ->
                if (record.display?.operationId != draft.operationId) record else record.copy(display = draft.withPhase(CompanionEditPhase.CONFLICT))
            }
            throw error
        }
    }

    fun submitDisplay() = launchWork {
        val old = state.value.local?.display ?: throw TransferException(TransferIssue.NOT_FOUND)
        ready(TransferCapability.WRITE_DISPLAY_METADATA)
        if (old.phase !in setOf(CompanionEditPhase.PREVIEWED, CompanionEditPhase.UNCERTAIN)) throw TransferException(TransferIssue.CONFLICT)
        val draft = old.withPhase(CompanionEditPhase.SUBMITTING)
        local(documents.update {
            record -> if (record.display?.operationId != draft.operationId) throw TransferException(TransferIssue.CONFLICT)
            record.copy(display = draft)
        })
        try {
            val receipt = transfer.applyDisplay(draft)
            local(documents.update {
                record -> if (record.display?.operationId != draft.operationId) throw TransferException(TransferIssue.CONFLICT)
                record.copy(display = draft.withPhase(CompanionEditPhase.COMPLETED, receipt), lastReceipt = receipt)
            })
            mutableState.update { it.copy(group = null) }
            if (state.value.allowed(TransferCapability.READ_LIBRARY)) performRead(if (draft.kind == TransferDisplayKind.ALBUM) TransferCatalogKind.ALBUMS else TransferCatalogKind.ARTISTS, "", null, previous = false)
        } catch (error: Exception) {
            preserveAfterFailure {
                record -> val actual = record.display
                if (actual?.operationId != draft.operationId || actual.receipt != null) record else record.copy(display = actual.withPhase(if (error is TransferException && error.issue in setOf(TransferIssue.CONFLICT, TransferIssue.NOT_FOUND)) CompanionEditPhase.CONFLICT else CompanionEditPhase.UNCERTAIN))
            }
            throw error
        }
    }

    fun discardDisplayDraft() = launchWork { local(documents.update { it.copy(display = null) }) }

    private fun currentGroupPage(row: TransferCatalogRow.Group): CompanionCachedPage {
        val cached = currentPage(state.value.pageWindow.kind)
        displayKind(cached.page.kind)
        if (cached.page.rows.none { it === row } || row.revision != cached.page.libraryRevision.catalogRevision) throw TransferException(TransferIssue.CONFLICT)
        return cached
    }

    private fun currentPage(kind: TransferCatalogKind): CompanionCachedPage {
        return currentCompanionPage(state.value, kind)
    }

    private fun ready(capability: TransferCapability): TransferFingerprint {
        return readyCompanionTarget(state.value, capability)
    }

    private suspend fun preserveAfterFailure(change: (CompanionLocalState) -> CompanionLocalState) = withContext(NonCancellable) {
        try {
            withTimeout(CompanionLimits.RETIREMENT_WAIT_MS) {
                documents.awaitStopped()
                local(documents.update(change))
            }
        }
        catch (_: Exception) {
            documents.markRecoveryRequired()
            mutableState.update {
                it.copy(issue = TransferIssue.RECOVERY_REQUIRED)
            }
        }
    }

    private fun uncertain(error: Exception): Boolean = error is CancellationException || error !is TransferException || error.issue in setOf(
        TransferIssue.INTERRUPTED, TransferIssue.CLOSED, TransferIssue.EXPIRED, TransferIssue.TRANSPORT_FAILED,
        TransferIssue.RECOVERY_REQUIRED, TransferIssue.STORAGE_UNAVAILABLE,
    )

    private fun recoverDrafts(record: CompanionLocalState): CompanionLocalState = record.copy(
        upload = record.upload?.let { if (it.phase in setOf(CompanionUploadPhase.SENDING, CompanionUploadPhase.VERIFYING, CompanionUploadPhase.STOPPING)) it.withResult(CompanionUploadPhase.UNCERTAIN) else it },
        playlist = record.playlist?.let { if (it.phase == CompanionEditPhase.SUBMITTING) it.withPhase(CompanionEditPhase.UNCERTAIN) else it },
        display = record.display?.let { if (it.phase == CompanionEditPhase.SUBMITTING) it.withPhase(CompanionEditPhase.UNCERTAIN) else it },
    )

    private fun clearCatalog() { mutableState.update { it.copy(pageWindow = emptyWindow(TransferCatalogKind.TRACKS), playlist = null, group = null, selectedTracks = emptySet(), selectedTrackRevision = null, trackSelection = CompanionTrackSelection.NONE) } }
    private fun local(record: CompanionLocalState) { mutableState.update { it.copy(local = record) } }
    private fun show(error: Exception) {
        val issue = companionVisibleIssue(error)
        mutableState.update { it.copy(issue = issue) }
    }

    private fun launchWork(action: suspend () -> Unit) {
        if (work != null || stopWork?.isActive == true || recoveryWork != null || documents.working.value || transfer.state.value.phase == CompanionConnectionPhase.RECOVERY_REQUIRED || documents.issue.value == TransferIssue.RECOVERY_REQUIRED) {
            show(TransferException(TransferIssue.BUSY))
            return
        }
        mutableState.update { it.copy(busy = true, issue = null) }
        val generation = viewGeneration
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try { action() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                coroutineContext.ensureActive()
                if (generation == viewGeneration) show(error)
            }
            catch (_: OutOfMemoryError) {
                coroutineContext.ensureActive()
                if (generation == viewGeneration) show(TransferException(TransferIssue.STORAGE_UNAVAILABLE))
            }
            finally {
                if (work === coroutineContext[Job]) work = null
                mutableState.update { it.copy(busy = false) }
            }
        }
        work = job
        job.start()
    }

    override fun onCleared() {
        background()
        super.onCleared()
    }
}

internal fun membersKind(kind: TransferDisplayKind): TransferCatalogKind = if (kind == TransferDisplayKind.ALBUM) TransferCatalogKind.ALBUM_TRACKS else TransferCatalogKind.ARTIST_TRACKS
internal fun displayKind(kind: TransferCatalogKind): TransferDisplayKind = when (kind) {
    TransferCatalogKind.ALBUMS -> TransferDisplayKind.ALBUM
    TransferCatalogKind.ARTISTS -> TransferDisplayKind.ARTIST
    else -> throw TransferException(TransferIssue.INVALID_INPUT)
}
