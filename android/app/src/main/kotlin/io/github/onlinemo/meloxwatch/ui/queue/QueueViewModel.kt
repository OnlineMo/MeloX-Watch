package io.github.onlinemo.meloxwatch.ui.queue

import android.app.Application
import androidx.annotation.MainThread
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.onlinemo.meloxwatch.MeloXApplication
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkLimits
import io.github.onlinemo.meloxwatch.data.queue.QueueArtistReader
import io.github.onlinemo.meloxwatch.data.queue.QueueStoreException
import io.github.onlinemo.meloxwatch.data.queue.QueueStoreIssue
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueLimits
import io.github.onlinemo.meloxwatch.model.QueueMode
import io.github.onlinemo.meloxwatch.model.QueueMutation
import io.github.onlinemo.meloxwatch.model.QueueMutationRequest
import io.github.onlinemo.meloxwatch.model.QueueRepeatMode
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

enum class QueueViewerFailure {
    CONFLICT,
    STORAGE_FULL,
    STORAGE_UNAVAILABLE,
    RECOVERY_REQUIRED,
    READ_FAILED,
}

enum class QueueEditAction { MENU, CHOOSE_ENTRY, CHOOSE, REMOVE, MOVE, MODE }
enum class QueueEditMessage { REMOVED, CURRENT_REMOVED_PAUSED, MOVED, MODE_SAVED }
enum class QueueArtistPhase { IDLE, LOADING, READY, FAILED }

/** Editing keeps only one instance and its revision; the queue itself stays in durable rows. */
data class QueueEditDraft(
    val base: QueueSnapshot,
    val operationId: String,
    val action: QueueEditAction,
    val entry: QueueEntry? = null,
    val destinationText: String = "",
    val shuffle: Boolean = base.mode.shuffle,
    val repeat: QueueRepeatMode = base.mode.repeat,
    val limitEnabled: Boolean = base.mode.cycleLimit != null,
    val limitText: String = (base.mode.cycleLimit ?: 2).toString(),
    val submitted: Boolean = false,
) {
    val destinationOrder: Long? get() = destinationText.toLongOrNull()?.takeIf {
        it in 1L..base.entryCount
    }?.minus(1L)
    val mode: QueueMode? get() {
        val limit = if (limitEnabled) limitText.toIntOrNull()?.takeIf { it in 1..99 } ?: return null else null
        return QueueMode(shuffle, repeat, limit)
    }
    val canSubmit: Boolean get() = when (action) {
        QueueEditAction.MENU, QueueEditAction.CHOOSE_ENTRY, QueueEditAction.CHOOSE -> false
        QueueEditAction.MOVE -> destinationOrder != null
        QueueEditAction.MODE -> mode != null
        QueueEditAction.REMOVE -> entry != null
    }

    fun request(): QueueMutationRequest {
        check(canSubmit)
        val edit = when (action) {
            QueueEditAction.REMOVE -> checkNotNull(entry).let {
                QueueMutation.Remove(it.queueEntryId, it.queueEntryId == base.currentEntryId)
            }
            QueueEditAction.MOVE -> QueueMutation.Move(checkNotNull(entry).queueEntryId, checkNotNull(destinationOrder))
            QueueEditAction.MODE -> QueueMutation.SetMode(checkNotNull(mode))
            QueueEditAction.MENU, QueueEditAction.CHOOSE_ENTRY, QueueEditAction.CHOOSE -> error("Choose a queue edit first")
        }
        return QueueMutationRequest(operationId, base.generationId, base.queueRevision, base.currentEntryId, edit)
    }
}

/** snapshot 与 entries 只成对发布；保留一页，不把 UI 窗口当作完整队列。 */
data class QueueViewerState(
    val snapshot: QueueSnapshot? = null,
    val entries: List<QueueEntry> = emptyList(),
    val pageStart: Long = 0L,
    val loading: Boolean = false,
    val failure: QueueViewerFailure? = null,
    val showingPreviousData: Boolean = false,
    val hasLoaded: Boolean = false,
    val editor: QueueEditDraft? = null,
    val editMessage: QueueEditMessage? = null,
    val rowsIdentity: Any? = null,
    val visibleEntryIds: Set<String> = emptySet(),
    val artists: Map<TrackKey, String?> = emptyMap(),
    val artistPhase: QueueArtistPhase = QueueArtistPhase.IDLE,
) {
    val canSelect: Boolean
        get() = hasLoaded && snapshot != null && !loading && failure == null && !showingPreviousData

    val hasPreviousPage: Boolean
        get() = canSelect && pageStart > 0L

    val hasNextPage: Boolean
        get() = canSelect && snapshot != null && snapshot.entryCount - pageStart > entries.size.toLong()

    val editorConflict: Boolean get() = editor?.base?.let { base ->
        val actual = snapshot ?: return@let true
        actual.generationId != base.generationId || actual.queueRevision != base.queueRevision ||
            actual.currentEntryId != base.currentEntryId
    } == true
}

/**
 * 只在队列页可见时观察/查询。失败锁住旧的完整页，明确重载才接受后续快照。
 * 请求身份同时约束分页和观察错误，取消后迟到的结果不能覆盖后来页面。
 */
class QueueViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MeloXApplication
    private val queues = app.queues
    private val artistReader = QueueArtistReader(app.library.database.libraryDao())
    private val mutableUiState = MutableStateFlow(QueueViewerState())
    val uiState: StateFlow<QueueViewerState> = mutableUiState.asStateFlow()

    private var visible = false
    private var reloadRequired = false
    private var observationIdentity: Any? = null
    private var requestIdentity: Any? = null
    private var observationJob: Job? = null
    private var pageJob: Job? = null
    private var artistIdentity: Any? = null
    private var artistJob: Job? = null
    private var target: PageTarget? = null

    @MainThread
    fun onVisible() {
        if (visible) return
        visible = true
        startObserving()
    }

    @MainThread
    fun onHidden() {
        visible = false
        cancelReads()
        mutableUiState.value = mutableUiState.value.copy(loading = false, editor = null)
    }

    /** The host reports laid-out keys, with the exact published page token; prefetch grants no artwork work. */
    @MainThread
    fun setVisibleItemKeys(rowsIdentity: Any?, keys: List<Any>) {
        val state = mutableUiState.value
        if (!visible || !state.canSelect || rowsIdentity == null || state.rowsIdentity !== rowsIdentity) return
        val rowKeys = state.entries.associate { "queue:entry:${it.queueEntryId}" to it.queueEntryId }
        val ids = keys.asSequence().take(QueueLimits.PAGE_SIZE).filterIsInstance<String>().mapNotNull { rowKeys[it] }
            .distinct().take(ArtworkLimits.VISIBLE_REFRESH_ITEMS).toSet()
        if (ids != state.visibleEntryIds) mutableUiState.value = state.copy(visibleEntryIds = ids)
    }

    @MainThread
    fun previousPage() {
        val state = mutableUiState.value
        if (!visible || !state.hasPreviousPage) return
        loadPage(state.snapshot, maxOf(0L, state.pageStart - QueueLimits.PAGE_SIZE))
    }

    @MainThread
    fun nextPage() {
        val state = mutableUiState.value
        if (!visible || !state.hasNextPage) return
        // hasNextPage 保证和小于完整 Long 数量，包括 Long.MAX_VALUE 的末页。
        loadPage(state.snapshot, state.pageStart + state.entries.size.toLong())
    }

    @MainThread
    fun retry() {
        if (!visible || mutableUiState.value.loading) return
        reloadRequired = false
        mutableUiState.value = mutableUiState.value.copy(failure = null, editor = null)
        startObserving()
    }

    fun locateCurrent() {
        val state = mutableUiState.value
        if (visible && state.canSelect) loadPage(state.snapshot, currentSegment(state.snapshot))
    }

    fun beginQueueControls() {
        val state = mutableUiState.value
        val base = state.snapshot ?: return
        if (!visible || !state.canSelect || state.editor != null) return
        mutableUiState.value = state.copy(
            editor = QueueEditDraft(base, "queue:" + UUID.randomUUID(), QueueEditAction.MENU),
            editMessage = null,
        )
    }

    /** 管理选择只保留一个实例草稿；迟到的菜单/行回调不能重新进入已退出的任务。 */
    fun beginEntryManagement() {
        val state = mutableUiState.value
        val base = state.snapshot ?: return
        if (!visible || !state.canSelect || state.editorConflict || state.editor?.action != QueueEditAction.MENU) return
        mutableUiState.value = state.copy(
            editor = QueueEditDraft(base, "queue:" + UUID.randomUUID(), QueueEditAction.CHOOSE_ENTRY),
            editMessage = null,
        )
    }

    fun beginEntryEdit(entry: QueueEntry) {
        val state = mutableUiState.value
        val base = state.snapshot ?: return
        if (!visible || !state.canSelect || state.editorConflict ||
            state.editor?.action != QueueEditAction.CHOOSE_ENTRY || state.entries.none { it == entry }
        ) return
        mutableUiState.value = state.copy(editor = QueueEditDraft(
            base, "queue:" + UUID.randomUUID(), QueueEditAction.CHOOSE, entry,
            destinationText = (entry.playbackOrder + 1L).toString(),
        ), editMessage = null)
    }

    fun chooseEntryAction(action: QueueEditAction) {
        if (action != QueueEditAction.REMOVE && action != QueueEditAction.MOVE) return
        editDraft { if (it.entry == null) it else it.copy(action = action) }
    }

    fun beginModeEdit() {
        val state = mutableUiState.value
        val base = state.snapshot ?: return
        if (!visible || !state.canSelect || state.editorConflict || state.editor?.action != QueueEditAction.MENU) return
        mutableUiState.value = state.copy(editor = QueueEditDraft(base, "queue:" + UUID.randomUUID(), QueueEditAction.MODE),
            editMessage = null)
    }

    fun setDestination(value: String) {
        if (value.length <= 19 && value.all { it in '0'..'9' }) editDraft { it.copy(destinationText = value) }
    }

    fun stepDestination(delta: Int) {
        val draft = mutableUiState.value.editor ?: return
        val current = draft.destinationText.toLongOrNull()?.takeIf { it in 1L..draft.base.entryCount } ?: return
        val next = when {
            delta < 0 && current > 1L -> current - 1L
            delta > 0 && current < draft.base.entryCount -> current + 1L
            else -> return
        }
        setDestination(next.toString())
    }

    fun setShuffle(value: Boolean) = editDraft { it.copy(shuffle = value) }
    fun setRepeat(value: QueueRepeatMode) = editDraft { it.copy(repeat = value) }
    fun setCycleLimitEnabled(value: Boolean) = editDraft { it.copy(limitEnabled = value) }
    fun setCycleLimitText(value: String) {
        if (value.length <= 2 && value.all { it in '0'..'9' }) editDraft { it.copy(limitText = value) }
    }

    fun cancelEdit() { mutableUiState.value = mutableUiState.value.copy(editor = null) }

    fun submitEdit(onSubmit: (QueueMutationRequest) -> Unit) {
        val state = mutableUiState.value
        val draft = state.editor ?: return
        if (visible && state.canSelect && !state.editorConflict && draft.canSubmit) {
            mutableUiState.value = state.copy(editor = draft.copy(submitted = true))
            onSubmit(draft.request())
        }
    }

    fun finishEdit(operationId: String) {
        val state = mutableUiState.value
        val draft = state.editor?.takeIf { it.operationId == operationId } ?: return
        val message = when (draft.action) {
            QueueEditAction.REMOVE -> if (draft.entry?.queueEntryId == draft.base.currentEntryId)
                QueueEditMessage.CURRENT_REMOVED_PAUSED else QueueEditMessage.REMOVED
            QueueEditAction.MOVE -> QueueEditMessage.MOVED
            QueueEditAction.MODE -> QueueEditMessage.MODE_SAVED
            QueueEditAction.MENU, QueueEditAction.CHOOSE_ENTRY, QueueEditAction.CHOOSE -> return
        }
        mutableUiState.value = state.copy(editor = null, editMessage = message)
    }

    private fun editDraft(change: (QueueEditDraft) -> QueueEditDraft) {
        if (!visible) return
        mutableUiState.value = mutableUiState.value.let { state -> state.copy(editor = state.editor?.let { draft ->
            val changed = change(draft)
            if (changed == draft) draft else changed.copy(
                operationId = if (draft.submitted) "queue:" + UUID.randomUUID() else draft.operationId,
                submitted = false,
            )
        }) }
    }

    private fun startObserving() {
        cancelReads()
        val identity = Any()
        observationIdentity = identity
        if (!reloadRequired) {
            mutableUiState.value = mutableUiState.value.let { state ->
                state.copy(loading = true, showingPreviousData = state.hasLoaded)
            }
        }
        observationJob = viewModelScope.launch {
            try {
                queues.snapshots().collect { snapshot ->
                    if (ownsObservation(identity) && !reloadRequired) {
                        val state = mutableUiState.value
                        val pending = target
                        if (state.hasLoaded && !state.loading && !state.showingPreviousData && pending == null &&
                            sameRows(state.snapshot, snapshot)
                        ) {
                            // A checkpoint update does not change rows. Avoid rereading a page every five seconds.
                            mutableUiState.value = state.copy(snapshot = snapshot)
                            return@collect
                        }
                        val start = when {
                            pending != null && pending.generationId == snapshot?.generationId -> pending.pageStart
                            state.hasLoaded && state.snapshot?.generationId == snapshot?.generationId -> state.pageStart
                            else -> currentSegment(snapshot)
                        }
                        loadPage(snapshot, start)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (ownsObservation(identity)) fail(error)
            }
        }
    }

    private fun loadPage(snapshot: QueueSnapshot?, requestedStart: Long) {
        if (!visible || reloadRequired) return
        val start = boundedStart(requestedStart, snapshot?.entryCount ?: 0L)
        val identity = Any()
        requestIdentity = identity
        pageJob?.cancel()
        cancelArtistRead()
        target = PageTarget(snapshot?.generationId, start)
        mutableUiState.value = mutableUiState.value.let { state ->
            state.copy(loading = true, showingPreviousData = state.hasLoaded)
        }
        pageJob = viewModelScope.launch {
            try {
                val entries = if (snapshot == null || snapshot.entryCount == 0L) {
                    emptyList()
                } else {
                    queues.page(snapshot.generationId, start, QueueLimits.PAGE_SIZE)
                }
                val verified = queues.snapshot()
                // 行属于冻结代次；独立 checkpoint 可更新，但不能把新代次与旧行拼在一起。
                if (!sameRows(snapshot, verified)) throw QueueStoreException(QueueStoreIssue.CONFLICT)
                verifyPage(verified, entries, start)
                if (ownsRequest(identity)) {
                    target = null
                    val previousState = mutableUiState.value
                    mutableUiState.value = QueueViewerState(
                        snapshot = verified,
                        entries = entries,
                        pageStart = start,
                        hasLoaded = true,
                        editor = previousState.editor,
                        editMessage = previousState.editMessage,
                        rowsIdentity = identity,
                        artistPhase = QueueArtistPhase.LOADING,
                    )
                    loadArtists(identity, entries)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (ownsRequest(identity)) fail(error)
            }
        }
    }

    private fun fail(error: Exception) {
        reloadRequired = true
        requestIdentity = null
        pageJob?.cancel()
        cancelArtistRead()
        target = null
        val failure = when ((error as? QueueStoreException)?.issue) {
            QueueStoreIssue.CONFLICT, QueueStoreIssue.SOURCE_CHANGED -> QueueViewerFailure.CONFLICT
            QueueStoreIssue.STORAGE_FULL -> QueueViewerFailure.STORAGE_FULL
            QueueStoreIssue.STORAGE_UNAVAILABLE -> QueueViewerFailure.STORAGE_UNAVAILABLE
            QueueStoreIssue.RECOVERY_REQUIRED, QueueStoreIssue.INCOMPLETE -> QueueViewerFailure.RECOVERY_REQUIRED
            else -> QueueViewerFailure.READ_FAILED
        }
        mutableUiState.value = mutableUiState.value.let { state ->
            state.copy(loading = false, failure = failure, showingPreviousData = state.hasLoaded)
        }
    }

    private fun cancelReads() {
        observationIdentity = null
        requestIdentity = null
        observationJob?.cancel()
        pageJob?.cancel()
        cancelArtistRead()
        observationJob = null
        pageJob = null
        target = null
    }

    private fun loadArtists(rowsIdentity: Any, entries: List<QueueEntry>) {
        val identity = Any()
        artistIdentity = identity
        artistJob = viewModelScope.launch {
            try {
                val artists = artistReader.read(entries)
                if (ownsArtistRead(identity, rowsIdentity)) {
                    mutableUiState.value = mutableUiState.value.copy(artists = artists, artistPhase = QueueArtistPhase.READY)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (ownsArtistRead(identity, rowsIdentity)) {
                    mutableUiState.value = mutableUiState.value.copy(artists = emptyMap(), artistPhase = QueueArtistPhase.FAILED)
                }
            }
        }
    }

    private fun cancelArtistRead() {
        artistIdentity = null
        artistJob?.cancel()
        artistJob = null
        mutableUiState.value = mutableUiState.value.let { state ->
            state.copy(rowsIdentity = null, visibleEntryIds = emptySet(),
                artistPhase = if (state.artistPhase == QueueArtistPhase.LOADING) QueueArtistPhase.IDLE else state.artistPhase)
        }
    }

    private fun ownsArtistRead(identity: Any, rowsIdentity: Any): Boolean =
        visible && !reloadRequired && artistIdentity === identity && mutableUiState.value.rowsIdentity === rowsIdentity

    private fun ownsObservation(identity: Any): Boolean = visible && observationIdentity === identity

    private fun ownsRequest(identity: Any): Boolean = visible && requestIdentity === identity && !reloadRequired

    private fun currentSegment(snapshot: QueueSnapshot?): Long =
        ((snapshot?.currentPlaybackOrder ?: 0L) / QueueLimits.PAGE_SIZE) * QueueLimits.PAGE_SIZE

    private fun boundedStart(requestedStart: Long, count: Long): Long {
        if (count == 0L) return 0L
        val lastPage = ((count - 1L) / QueueLimits.PAGE_SIZE) * QueueLimits.PAGE_SIZE
        return requestedStart.coerceIn(0L, lastPage)
    }

    private fun sameRows(expected: QueueSnapshot?, actual: QueueSnapshot?): Boolean {
        if (expected == null || actual == null) return expected == actual
        return expected.generationId == actual.generationId && expected.queueRevision == actual.queueRevision &&
            expected.view == actual.view && expected.mode == actual.mode &&
            expected.schemaVersion == actual.schemaVersion
    }

    private fun verifyPage(snapshot: QueueSnapshot?, entries: List<QueueEntry>, start: Long) {
        val remaining = (snapshot?.entryCount ?: 0L) - start
        val expectedSize = minOf(QueueLimits.PAGE_SIZE.toLong(), remaining)
        if (remaining < 0L || entries.size.toLong() != expectedSize) {
            throw QueueStoreException(QueueStoreIssue.RECOVERY_REQUIRED)
        }
        val ids = HashSet<String>()
        entries.forEachIndexed { index, entry ->
            if (entry.playbackOrder != start + index.toLong() || !ids.add(entry.queueEntryId)) {
                throw QueueStoreException(QueueStoreIssue.RECOVERY_REQUIRED)
            }
        }
    }

    private data class PageTarget(val generationId: String?, val pageStart: Long)
}
