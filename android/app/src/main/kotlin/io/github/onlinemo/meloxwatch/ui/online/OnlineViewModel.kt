package io.github.onlinemo.meloxwatch.ui.online

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import io.github.onlinemo.meloxwatch.data.auth.AuthException
import io.github.onlinemo.meloxwatch.data.auth.SessionSnapshot
import io.github.onlinemo.meloxwatch.data.auth.SessionValidity
import io.github.onlinemo.meloxwatch.data.themes.ThemeBitmapLease
import io.github.onlinemo.meloxwatch.data.online.OnlineCatalogFeature
import io.github.onlinemo.meloxwatch.data.online.OnlineCatalogQueueLease
import io.github.onlinemo.meloxwatch.data.online.OnlineLoginPresenter
import io.github.onlinemo.meloxwatch.data.online.OnlineLoginSnapshot
import io.github.onlinemo.meloxwatch.data.online.OnlineNetworkMonitor
import io.github.onlinemo.meloxwatch.data.online.OnlineQueueDirectory
import io.github.onlinemo.meloxwatch.data.online.OnlineQueueTicket
import io.github.onlinemo.meloxwatch.data.online.authCatalogIssue
import io.github.onlinemo.meloxwatch.model.OnlineCatalogEntry
import io.github.onlinemo.meloxwatch.model.OnlineCatalogEntryKind
import io.github.onlinemo.meloxwatch.model.OnlineCatalogException
import io.github.onlinemo.meloxwatch.model.OnlineCatalogIssue
import io.github.onlinemo.meloxwatch.model.OnlineCatalogKind
import io.github.onlinemo.meloxwatch.model.OnlineCatalogProgress
import io.github.onlinemo.meloxwatch.model.OnlineCatalogQuery
import io.github.onlinemo.meloxwatch.model.OnlineCatalogSnapshot
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import io.github.onlinemo.meloxwatch.playback.OnlineQueueAction
import io.github.onlinemo.meloxwatch.playback.OnlineQueueCommand
import io.github.onlinemo.meloxwatch.playback.resources.StreamingQuality
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class OnlineScreen { HOME, LOGIN, SEARCH, CATALOG, TRACK_ACTION, LOGOUT_CONFIRM }

internal data class OnlineUiState(
    val screen: OnlineScreen = OnlineScreen.HOME,
    val account: SessionSnapshot = SessionSnapshot(),
    val online: Boolean = false,
    val login: OnlineLoginSnapshot = OnlineLoginSnapshot(),
    val kind: OnlineCatalogKind? = null,
    val title: String? = null,
    val searchDraft: String = "",
    val snapshot: OnlineCatalogSnapshot? = null,
    val rows: List<OnlineCatalogEntry> = emptyList(),
    val pageStart: Long = 0L,
    val selected: OnlineCatalogEntry? = null,
    val quality: StreamingQuality = StreamingQuality.STANDARD,
    val busy: Boolean = false,
    val progress: OnlineCatalogProgress? = null,
    val issue: OnlineCatalogIssue? = null,
    val submission: OnlineQueueSubmission? = null,
) {
    override fun toString(): String = "OnlineUiState(screen=$screen, busy=$busy, issue=$issue, redacted)"
}

/** One visible-page operation and one bounded login run; no saved URL, credential or independent Player. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class OnlineViewModel(
    private val featureProvider: suspend () -> OnlineCatalogFeature,
    private val queues: suspend () -> QueueSnapshot?,
    private val directory: OnlineQueueDirectory,
    private val submit: suspend (OnlineQueueCommand) -> OnlineQueueSubmission,
    private val onAccepted: (OnlineQueueAction) -> Unit,
    private val networkFactory: () -> OnlineNetworkMonitor,
) : ViewModel() {
    private val mutableState = MutableStateFlow(OnlineUiState())
    val state = mutableState.asStateFlow()
    @Volatile private var visible = false
    @Volatile private var intent = 0L
    @Volatile private var operationIntent: Long? = null
    private var feature: OnlineCatalogFeature? = null
    private var query: OnlineCatalogQuery? = null
    private var returnToPlaylists = false
    private var operation: Job? = null
    private var pendingStart: PendingStart? = null
    private var sessionObserver: Job? = null
    private var networkObserver: Job? = null
    private var loginObserver: Job? = null
    private var network: OnlineNetworkMonitor? = null
    private var login: OnlineLoginPresenter? = null
    private var queueOperationId: String? = null

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        intent += 1L
        cancelOperation()
        if (!value) {
            login?.leave()
            loginObserver?.cancel()
            loginObserver = null
            sessionObserver?.cancel()
            sessionObserver = null
            networkObserver?.cancel()
            networkObserver = null
            network?.close()
            network = null
            mutableState.value = mutableState.value.copy(login = OnlineLoginSnapshot(), busy = false, progress = null, selected = null,
                screen = if (mutableState.value.screen == OnlineScreen.TRACK_ACTION) OnlineScreen.CATALOG else mutableState.value.screen)
            return
        }
        start {
            val current = feature ?: featureProvider().also { feature = it }
            current.sessions.restore()
            observe(current)
            if (mutableState.value.screen == OnlineScreen.LOGIN) enterLogin(current)
            else if (query != null && mutableState.value.screen == OnlineScreen.CATALOG) load(current, refresh = false)
        }
    }

    private fun observe(current: OnlineCatalogFeature) {
        sessionObserver?.cancel()
        sessionObserver = viewModelScope.launch {
            current.sessions.snapshot.collect { account ->
                if (!visible) return@collect
                val previous = mutableState.value.account
                mutableState.value = mutableState.value.copy(account = account)
                val scopeChanged = previous.account?.let { old ->
                    old.providerId != account.account?.providerId || old.accountId != account.account?.accountId
                } == true
                if (scopeChanged || account.validity == SessionValidity.SIGNED_OUT || account.validity == SessionValidity.UNAVAILABLE) {
                    mutableState.value = mutableState.value.copy(rows = emptyList(), snapshot = null, selected = null, title = null)
                }
                if (account.validity == SessionValidity.VERIFIED && mutableState.value.screen == OnlineScreen.LOGIN) {
                    login?.leave()
                    mutableState.value = mutableState.value.copy(screen = OnlineScreen.HOME, login = OnlineLoginSnapshot(), issue = null)
                }
            }
        }
        val monitor = network ?: networkFactory().also { network = it }
        monitor.start()
        networkObserver?.cancel()
        networkObserver = viewModelScope.launch {
            monitor.online.collect { online ->
                if (visible) {
                    mutableState.value = mutableState.value.copy(online = online)
                    login?.networkChanged(online)
                }
            }
        }
    }

    fun beginLogin() {
        if (!visible) return
        navigate(OnlineScreen.LOGIN)
        start { enterLogin(requireFeature()) }
    }

    private fun enterLogin(current: OnlineCatalogFeature) {
        val presenter = login ?: current.login(viewModelScope).also { login = it }
        loginObserver?.cancel()
        loginObserver = viewModelScope.launch {
            presenter.state.collect { value ->
                if (visible && mutableState.value.screen == OnlineScreen.LOGIN) {
                    mutableState.value = mutableState.value.copy(login = value)
                }
            }
        }
        presenter.enter(network?.online?.value == true)
    }

    fun refreshLogin() {
        if (visible && mutableState.value.screen == OnlineScreen.LOGIN) {
            if (login == null) beginLogin() else login?.refresh()
        }
    }

    fun showLogoutConfirmation() { if (visible) navigate(OnlineScreen.LOGOUT_CONFIRM) }
    fun confirmLogout() {
        if (!visible || mutableState.value.screen != OnlineScreen.LOGOUT_CONFIRM) return
        start {
            val current = requireFeature()
            val presenter = login ?: current.login(viewModelScope).also { login = it }
            presenter.logout().join()
            query = null
            mutableState.value = mutableState.value.copy(screen = OnlineScreen.HOME, account = current.sessions.snapshot.value,
                rows = emptyList(), snapshot = null, selected = null, searchDraft = "", issue = current.sessions.snapshot.value.issue?.let(::authCatalogIssue)
                    ?: presenter.lyricsCleanupIssue?.let { OnlineCatalogIssue.STORAGE_UNAVAILABLE })
        }
    }

    fun showSearch() {
        if (!visible) return
        query = null
        navigate(OnlineScreen.SEARCH)
        mutableState.value = mutableState.value.copy(rows = emptyList(), snapshot = null, title = null, kind = OnlineCatalogKind.SEARCH)
    }
    fun editSearch(value: String) {
        if (value.length <= 256 && value.none { it.code < 32 }) mutableState.value = mutableState.value.copy(searchDraft = value)
    }
    fun search() {
        val value = mutableState.value.searchDraft.trim()
        if (value.isBlank() || !visible) return
        open(OnlineCatalogQuery(OnlineCatalogKind.SEARCH, value))
    }
    fun open(kind: OnlineCatalogKind) { if (kind != OnlineCatalogKind.PLAYLIST && kind != OnlineCatalogKind.SEARCH) open(OnlineCatalogQuery(kind)) }
    private fun open(value: OnlineCatalogQuery, title: String? = null) {
        if (!visible) return
        query = value
        navigate(OnlineScreen.CATALOG)
        mutableState.value = mutableState.value.copy(kind = value.kind, title = title, snapshot = null, rows = emptyList(), pageStart = 0L)
        start { load(requireFeature(), refresh = false) }
    }

    fun choose(row: OnlineCatalogEntry) {
        if (!visible || mutableState.value.busy || row !in mutableState.value.rows) return
        if (row.kind == OnlineCatalogEntryKind.PLAYLIST) {
            returnToPlaylists = true
            open(OnlineCatalogQuery(OnlineCatalogKind.PLAYLIST, row.id), row.title)
        } else mutableState.value = mutableState.value.copy(screen = OnlineScreen.TRACK_ACTION, selected = row, issue = null, submission = null)
    }
    fun setQuality(value: StreamingQuality) { if (visible && !mutableState.value.busy) mutableState.value = mutableState.value.copy(quality = value) }
    suspend fun artwork(row: OnlineCatalogEntry, targetPx: Int): ThemeBitmapLease? {
        if (!visible || row !in mutableState.value.rows && row != mutableState.value.selected) return null
        val current = feature ?: return null
        val key = row.artworkKey
        if (key != null) {
            current.artworks.load(key, targetPx, mutableState.value.online)?.let { return it }
            if (current.artworks.hasTarget(key)) return null
        }
        if (!mutableState.value.online || row.kind != OnlineCatalogEntryKind.TRACK) return null
        val rebound = current.catalog.artworkKey(row.id) ?: return null
        return current.artworks.load(rebound, targetPx, allowNetwork = true)
    }
    fun refresh() { if (visible && query != null) start { load(requireFeature(), refresh = true) } }
    fun page(direction: Int) {
        if (!visible || direction !in listOf(-1, 1)) return
        start {
            val current = requireFeature()
            var snapshot = mutableState.value.snapshot ?: throw OnlineCatalogException(OnlineCatalogIssue.STALE_VIEW)
            val first = if (direction > 0) mutableState.value.pageStart + mutableState.value.rows.size
                else (mutableState.value.pageStart - PAGE_SIZE).coerceAtLeast(0L)
            val indexed = snapshot.kind in setOf(OnlineCatalogKind.LIKED, OnlineCatalogKind.PLAYLIST)
            if (direction > 0 && first >= snapshot.count && !snapshot.complete && !indexed) {
                if (!mutableState.value.online) return@start
                snapshot = current.catalog.next(snapshot, requireNotNull(query), ::progress)
            }
            if (first >= snapshot.count && snapshot.count > 0L) return@start
            loadPage(current, snapshot, first)
        }
    }

    private suspend fun load(current: OnlineCatalogFeature, refresh: Boolean) {
        val snapshot = current.catalog.load(requireNotNull(query), refresh, ::progress)
        val first = if (refresh) 0L else mutableState.value.pageStart.takeIf { it < snapshot.count } ?: 0L
        loadPage(current, snapshot, first)
    }

    private suspend fun loadPage(current: OnlineCatalogFeature, snapshot: OnlineCatalogSnapshot, first: Long) {
        var updated = snapshot
        var rows = current.catalog.page(updated, first)
        val indexed = updated.kind in setOf(OnlineCatalogKind.LIKED, OnlineCatalogKind.PLAYLIST)
        if (mutableState.value.online && indexed && first < updated.count && first >= updated.metadataCount) {
            // Offline browsing can leave this page ahead of the metadata cursor; fetch its one batch directly.
            updated = current.catalog.next(updated, requireNotNull(query), ::progress, first = first)
            rows = current.catalog.page(updated, first)
        }
        mutableState.value = mutableState.value.copy(snapshot = updated, pageStart = first, rows = rows)
    }

    fun playAll() = prepareQueue(0L, OnlineQueueAction.REPLACE_COMPLETE_LIST)
    fun playSelected(action: OnlineQueueAction) {
        val selected = mutableState.value.selected ?: return
        prepareQueue(selected.order, action, selected.id)
    }
    private fun prepareQueue(order: Long, action: OnlineQueueAction, selectedId: String? = null) {
        if (!visible || mutableState.value.kind == OnlineCatalogKind.PLAYLISTS) return
        start {
            val current = requireFeature()
            val expected = queues()
            val old = mutableState.value.snapshot ?: throw OnlineCatalogException(OnlineCatalogIssue.STALE_VIEW)
            val complete = current.catalog.finish(old, requireNotNull(query), ::progress)
            val currentRows = current.catalog.page(complete, mutableState.value.pageStart)
            mutableState.value = mutableState.value.copy(snapshot = complete, rows = currentRows)
            if (order !in 0 until complete.count) throw OnlineCatalogException(OnlineCatalogIssue.STALE_VIEW)
            if (selectedId != null && current.catalog.page(complete, order).firstOrNull()?.id != selectedId) {
                throw OnlineCatalogException(OnlineCatalogIssue.STALE_VIEW)
            }
            var lease: OnlineCatalogQueueLease? = null
            var ticket: OnlineQueueTicket? = null
            try {
                val frozen = current.catalog.freeze(complete)
                lease = frozen
                val operationId = UUID.randomUUID().toString()
                queueOperationId = operationId
                val registered = directory.register(frozen, operationId, order, action, expected, mutableState.value.quality)
                ticket = registered
                lease = null
                val result = submit(registered.command)
                mutableState.value = mutableState.value.copy(submission = result, issue = result.catalogIssue,
                    screen = if (result.accepted) OnlineScreen.CATALOG else mutableState.value.screen,
                    selected = if (result.accepted) null else mutableState.value.selected)
                if (result.accepted) onAccepted(action)
            } finally {
                queueOperationId?.let(directory::cancel)
                queueOperationId = null
                ticket?.close()
                lease?.close()
            }
        }
    }

    fun cancel() {
        intent += 1L
        cancelOperation()
    }
    private fun cancelOperation() {
        pendingStart = null
        queueOperationId?.let(directory::cancel)
        operation?.cancel()
    }
    fun back(): Boolean {
        if (!visible || mutableState.value.screen == OnlineScreen.HOME) return false
        when (mutableState.value.screen) {
            OnlineScreen.TRACK_ACTION -> mutableState.value = mutableState.value.copy(screen = OnlineScreen.CATALOG, selected = null)
            OnlineScreen.CATALOG -> {
                if (returnToPlaylists && mutableState.value.kind == OnlineCatalogKind.PLAYLIST) {
                    returnToPlaylists = false
                    open(OnlineCatalogKind.PLAYLISTS)
                } else if (mutableState.value.kind == OnlineCatalogKind.SEARCH) showSearch()
                else home()
            }
            else -> home()
        }
        return true
    }
    private fun home() {
        query = null
        returnToPlaylists = false
        navigate(OnlineScreen.HOME)
        mutableState.value = mutableState.value.copy(rows = emptyList(), snapshot = null, selected = null, title = null, kind = null)
    }
    private fun navigate(screen: OnlineScreen) {
        intent += 1L
        cancelOperation()
        if (screen != OnlineScreen.LOGIN) login?.leave()
        mutableState.value = mutableState.value.copy(screen = screen, selected = null, issue = null, submission = null, progress = null)
    }

    private fun progress(value: OnlineCatalogProgress) {
        // Navigation invalidates old progress while the request still owns its cleanup slot.
        mutableState.update { current ->
            if (visible && operationIntent == intent) current.copy(progress = value) else current
        }
    }
    private fun requireFeature(): OnlineCatalogFeature = feature ?: throw OnlineCatalogException(OnlineCatalogIssue.CLOSED)
    private fun start(action: suspend () -> Unit) {
        if (!visible) return
        if (operation != null) {
            // Retain the old slot through noncancellable cleanup; one newest explicit intent may follow it.
            pendingStart = PendingStart(intent, action)
            mutableState.value = mutableState.value.copy(busy = true, issue = null)
            return
        }
        val owner = intent
        operationIntent = owner
        mutableState.value = mutableState.value.copy(busy = true, issue = null, submission = null)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                action()
                currentCoroutineContext().ensureActive()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: OnlineCatalogException) { if (visible && intent == owner) mutableState.value = mutableState.value.copy(issue = error.issue) }
            catch (error: AuthException) { if (visible && intent == owner) mutableState.value = mutableState.value.copy(issue = authCatalogIssue(error.issue)) }
            catch (_: Exception) { if (visible && intent == owner) mutableState.value = mutableState.value.copy(issue = OnlineCatalogIssue.STORAGE_UNAVAILABLE) }
            finally {
                if (visible && intent == owner) mutableState.value = mutableState.value.copy(busy = false, progress = null)
            }
        }
        operation = job
        job.invokeOnCompletion {
            if (operation === job) {
                operation = null
                operationIntent = null
                val pending = pendingStart
                pendingStart = null
                if (visible && pending != null && pending.intent == intent) start(pending.action)
                else if (visible) mutableState.value = mutableState.value.copy(busy = false, progress = null)
            }
        }
        job.start()
    }

    override fun onCleared() {
        setVisible(false)
        login?.close()
        super.onCleared()
    }
    companion object { private const val PAGE_SIZE = 100 }
    private class PendingStart(val intent: Long, val action: suspend () -> Unit)
}
