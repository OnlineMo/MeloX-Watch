package io.github.onlinemo.meloxwatch.ui.queue

import android.app.Application
import androidx.annotation.MainThread
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.onlinemo.meloxwatch.MeloXApplication
import io.github.onlinemo.meloxwatch.data.queue.QueueStoreException
import io.github.onlinemo.meloxwatch.data.queue.QueueStoreIssue
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueLimits
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
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

/** snapshot 与 entries 只成对发布；保留一页，不把 UI 窗口当作完整队列。 */
data class QueueViewerState(
    val snapshot: QueueSnapshot? = null,
    val entries: List<QueueEntry> = emptyList(),
    val pageStart: Long = 0L,
    val loading: Boolean = false,
    val failure: QueueViewerFailure? = null,
    val showingPreviousData: Boolean = false,
    val hasLoaded: Boolean = false,
) {
    val canSelect: Boolean
        get() = hasLoaded && snapshot != null && !loading && failure == null && !showingPreviousData

    val hasPreviousPage: Boolean
        get() = canSelect && pageStart > 0L

    val hasNextPage: Boolean
        get() = canSelect && snapshot != null && snapshot.entryCount - pageStart > entries.size.toLong()
}

/**
 * 只在队列页可见时观察/查询。失败锁住旧的完整页，明确重载才接受后续快照。
 * 请求身份同时约束分页和观察错误，取消后迟到的结果不能覆盖后来页面。
 */
class QueueViewModel(application: Application) : AndroidViewModel(application) {
    private val queues = (application as MeloXApplication).queues
    private val mutableUiState = MutableStateFlow(QueueViewerState())
    val uiState: StateFlow<QueueViewerState> = mutableUiState.asStateFlow()

    private var visible = false
    private var reloadRequired = false
    private var observationIdentity: Any? = null
    private var requestIdentity: Any? = null
    private var observationJob: Job? = null
    private var pageJob: Job? = null
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
        mutableUiState.value = mutableUiState.value.copy(loading = false)
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
        mutableUiState.value = mutableUiState.value.copy(failure = null)
        startObserving()
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
                    mutableUiState.value = QueueViewerState(
                        snapshot = verified,
                        entries = entries,
                        pageStart = start,
                        hasLoaded = true,
                    )
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
        observationJob = null
        pageJob = null
        target = null
    }

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
