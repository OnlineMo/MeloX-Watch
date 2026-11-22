package io.github.onlinemo.meloxwatch.playback

import androidx.core.net.toUri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesState
import io.github.onlinemo.meloxwatch.data.preferences.UserPreferencesRepository
import io.github.onlinemo.meloxwatch.data.queue.QueueCommit
import io.github.onlinemo.meloxwatch.data.queue.QueuePageSource
import io.github.onlinemo.meloxwatch.data.queue.QueueReplacementRequest
import io.github.onlinemo.meloxwatch.data.queue.QueueStore
import io.github.onlinemo.meloxwatch.data.queue.QueueStoreException
import io.github.onlinemo.meloxwatch.data.queue.QueueStoreIssue
import io.github.onlinemo.meloxwatch.model.QueueCheckpoint
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import io.github.onlinemo.meloxwatch.model.QueueWindow
import io.github.onlinemo.meloxwatch.model.TrackKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 唯一主 Looper 后端的队列所有者。数据库有全部引用，内存只有当前窗口与一个合并写入。
 * 焦点/noisy 暂沿用服务的单一 ExoPlayer 所有者；LP-05 接入双后端前再统一移交。
 */
@UnstableApi
class QueuePlaybackCoordinator(
    private val backend: ExoPlayer,
    private val queueStore: QueueStore,
    private val preferences: UserPreferencesRepository,
    private val serviceScope: CoroutineScope,
    private val resolveEntry: suspend (QueueEntry) -> QueueEntry = { it },
    private val onRoundChanged: suspend (QueueEntry) -> Unit = {},
    private val onFailure: (QueuePlaybackFailure) -> Unit = {},
) {
    private val _state = MutableStateFlow(QueuePlaybackState())
    val state = _state.asStateFlow()
    private val sessionBridge = QueueSessionPlayer(backend, this)
    val sessionPlayer: Player = sessionBridge
    internal var isDetached = false
        private set
    private val actions = Mutex()
    private val checkpointWrites = Mutex()
    private val checkpointRequests = Channel<Unit>(Channel.CONFLATED)
    private var pendingCheckpoint: CapturedCheckpoint? = null
    private var currentOperation: Job? = null
    private var commandJob: Job? = null
    private var ticker: Job? = null
    private var intentRevision = 0L
    private var checkpointEpoch = 0L
    private var detachedIntentRevision = 0L
    private var loadedWindow: QueueWindow? = null
    private var applyingWindow = false
    private var resumeIntent = false
    private var playbackCycle = 0L
    private var handledNaturalCycle = -1L

    private val backendListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isDetached) return
            updateTicker()
            if (!isPlaying && !applyingWindow) requestCheckpoint()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (isDetached || applyingWindow || backend.playWhenReady != playWhenReady) return
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                naturalEnd()
            } else if (!playWhenReady) {
                resumeIntent = false
                invalidateIntent()
                requestCheckpoint()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (!isDetached && !applyingWindow && playbackState == Player.STATE_ENDED && backend.playbackState == Player.STATE_ENDED) naturalEnd()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (!isDetached && !applyingWindow && reason in listOf(Player.DISCONTINUITY_REASON_SEEK, Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT)) {
                requestCheckpoint()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!isDetached && !applyingWindow) requestCheckpoint()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (isDetached) return
            resumeIntent = false
            invalidateIntent()
            backend.pause()
            requestCheckpoint()
            report(QueuePlaybackFailure(errorIssue(error), backend.currentMediaItem?.mediaId))
        }
    }

    private val checkpointWorker: Job

    init {
        check(Looper.myLooper() == Looper.getMainLooper() && backend.applicationLooper == Looper.getMainLooper())
        backend.repeatMode = Player.REPEAT_MODE_OFF
        backend.shuffleModeEnabled = false
        // 自然完成由完整队列决策；不能让三项窗口自动重复或先播出额外一轮。
        backend.setPauseAtEndOfMediaItems(true)
        backend.addListener(backendListener)
        checkpointWorker = serviceScope.launch(Dispatchers.Main.immediate) {
            while (isActive) {
                checkpointRequests.receive()
                drainCheckpoints()
            }
        }
    }

    suspend fun initialize(): QueueSnapshot? = action { token ->
        resumeIntent = false
        pauseForWindowChange()
        val saved = queueStore.initialize()
        val preferenceState = preferences.state.first { it !is PreferencesState.Loading }
        val restorePosition = when (preferenceState) {
            is PreferencesState.Ready -> preferenceState.preferences.restorePosition
            is PreferencesState.Unavailable -> preferenceState.lastKnown?.restorePosition ?: true
            PreferencesState.Loading -> true
        }
        publishSnapshot(saved)
        currentCoroutineContext().ensureActive()
        if (isDetached) return@action saved
        if (saved == null || saved.entryCount == 0L) {
            clearBackend()
        } else {
            val entryId = saved.currentEntryId ?: throw QueueStoreException(QueueStoreIssue.RECOVERY_REQUIRED)
            val window = queueStore.window(saved.generationId, entryId)
            if (!isDetached && token == intentRevision) applyWindow(saved, window, if (restorePosition) saved.positionMs else 0L, explicitPlay = false, token)
        }
        saved
    }

    suspend fun replace(request: QueueReplacementRequest, source: QueuePageSource, explicitPlay: Boolean): QueueCommit = action(clearFailure = false) { token ->
        var prepared: QueueWindow? = null
        val commit = queueStore.replace(request, source) { candidate ->
            prepared = candidate?.let { prepareWindow(it) }
            currentCoroutineContext().ensureActive()
            if (isDetached || token != intentRevision) throw CancellationException("Queue preparation intent changed")
        }
        // 历史 operation 的确认不能重装窗口，也不代表修复了此前的加载失败。
        if (commit.wasReplay) return@action commit
        checkpointEpoch += 1L
        publishSnapshot(commit.snapshot)
        if (!isDetached) {
            // The pointer is now committed. Pause the old source before any suspension, including
            // cancellation after commit; loading consumes exactly the preflight window, not new IO.
            pauseForWindowChange()
            if (commit.snapshot.entryCount == 0L) clearBackend() else {
                val window = prepared
                if (window == null || window.generationId != commit.snapshot.generationId ||
                    window.queueRevision != commit.snapshot.queueRevision || window.totalCount != commit.snapshot.entryCount ||
                    window.current.queueEntryId != commit.snapshot.currentEntryId ||
                    window.current.originalOrder != commit.snapshot.currentOriginalOrder ||
                    window.current.playbackOrder != commit.snapshot.currentPlaybackOrder) {
                    clearBackend()
                    report(QueuePlaybackFailure(QueuePlaybackIssue.RECOVERY_REQUIRED, commit.snapshot.currentEntryId))
                } else if (!currentCoroutineContext().isActive || token != intentRevision) {
                    clearBackend()
                } else {
                    try {
                        applyPreparedWindow(commit.snapshot, window, commit.snapshot.positionMs, explicitPlay, token)
                    } catch (_: CancellationException) {
                        // The accepted snapshot remains complete. A user pause/detach cannot become
                        // late playback or a false prepublication failure/rollback.
                        if (!isDetached) pauseForWindowChange()
                    } catch (error: Exception) {
                        // A partial backend mutation can retain the old URI under the same entry
                        // ID. Clear that window so explicit retry loads the accepted queue again.
                        if (!isDetached) clearBackend()
                        report(if (error is QueuePlaybackException) error.failure else
                            QueuePlaybackFailure(QueuePlaybackIssue.OUTPUT_ERROR, commit.snapshot.currentEntryId))
                    }
                }
            }
        }
        commit
    }

    suspend fun select(generationId: String, expectedQueueRevision: Long, entryId: String, explicitPlay: Boolean): QueueSnapshot = action { token ->
        selectInternal(generationId, expectedQueueRevision, entryId, explicitPlay, completedCycles = 0, token)
    }

    /** 调用点立即捕获播放器位置，后续 IO 不再读取活跃对象。最多一个等待值。 */
    fun requestCheckpoint() {
        assertMain()
        if (isDetached) return
        capture()?.let { captured ->
            pendingCheckpoint = captured
            checkpointRequests.trySend(Unit)
        }
    }

    suspend fun flush() = withContext(Dispatchers.Main.immediate) {
        if (!isDetached) requestCheckpoint()
        drainCheckpoints()
    }

    /** Service 在释放 backend 前调用；其后所有关闭/写入路径只消费已捕获值。 */
    fun detach() {
        assertMain()
        if (isDetached) return
        capture()?.let { pendingCheckpoint = it }
        detachedIntentRevision = intentRevision
        isDetached = true
        intentRevision += 1L
        currentOperation?.cancel()
        commandJob?.cancel()
        ticker?.cancel()
        ticker = null
        backend.removeListener(backendListener)
        sessionBridge.detachListeners()
        loadedWindow = null
        _state.value = _state.value.copy(preparing = false)
    }

    suspend fun close() = withContext(Dispatchers.Main.immediate) {
        if (!isDetached) detach()
        checkpointWorker.cancelAndJoin()
        checkpointRequests.close()
        drainCheckpoints()
    }

    internal fun canNavigate(direction: Int): Boolean {
        if (isDetached || _state.value.preparing) return false
        val snapshot = _state.value.snapshot ?: return false
        val order = snapshot.currentPlaybackOrder ?: return false
        return manualQueueTarget(order, snapshot.entryCount, snapshot.mode.repeat, direction) != null
    }

    internal fun navigateFromSession(direction: Int) {
        assertMain()
        if (!canNavigate(direction)) return
        val play = backend.playWhenReady
        launchCommand {
            action { token ->
                val active = activeSnapshot()
                val order = active.currentPlaybackOrder ?: return@action
                val target = manualQueueTarget(order, active.entryCount, active.mode.repeat, direction) ?: return@action
                val entry = queueStore.page(active.generationId, target, 1).singleOrNull() ?: throw QueueStoreException(QueueStoreIssue.RECOVERY_REQUIRED)
                selectInternal(active.generationId, active.queueRevision, entry.queueEntryId, play, 0, token)
            }
        }
    }

    internal fun playFromSession() {
        assertMain()
        if (isDetached) return
        val active = _state.value.snapshot ?: return
        val entryId = active.currentEntryId ?: return
        if (_state.value.preparing) return
        val atEnd = backend.playbackState == Player.STATE_ENDED ||
            (backend.duration != C.TIME_UNSET && backend.duration > 0L && backend.currentPosition >= backend.duration)
        if (backend.currentMediaItem?.mediaId != entryId || atEnd) {
            launchCommand { select(active.generationId, active.queueRevision, entryId, explicitPlay = true) }
        } else {
            intentRevision += 1L
            resumeIntent = true
            _state.value = _state.value.copy(failure = null)
            if (backend.playbackState == Player.STATE_IDLE) backend.prepare()
            if (backend.playerError == null) {
                backend.play()
                resumeIntent = backend.playWhenReady
            } else {
                resumeIntent = false
                backend.playerError?.let { report(QueuePlaybackFailure(errorIssue(it), entryId)) }
            }
            updateTicker()
        }
    }

    internal fun pauseFromSession() {
        assertMain()
        if (isDetached) return
        resumeIntent = false
        invalidateIntent()
        backend.pause()
        requestCheckpoint()
    }

    internal fun seekFromSession(positionMs: Long) {
        assertMain()
        if (isDetached || _state.value.preparing) return
        val duration = backend.duration
        if (duration == C.TIME_UNSET || duration <= 0L || !backend.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return
        intentRevision += 1L
        checkpointEpoch += 1L
        backend.seekTo(positionMs.coerceIn(0L, duration))
        requestCheckpoint()
    }

    internal fun seekByFromSession(forward: Boolean) {
        assertMain()
        if (isDetached || _state.value.preparing) return
        val position = backend.currentPosition
        val offset = if (forward) backend.seekForwardIncrement else -backend.seekBackIncrement
        val target = if (offset > 0L && position > Long.MAX_VALUE - offset) Long.MAX_VALUE else position + offset
        seekFromSession(target)
    }

    internal fun seekFromSession(mediaItemIndex: Int, positionMs: Long) {
        assertMain()
        if (isDetached || mediaItemIndex !in 0 until backend.mediaItemCount) return
        if (mediaItemIndex == backend.currentMediaItemIndex) seekFromSession(positionMs) else {
            val entryId = backend.getMediaItemAt(mediaItemIndex).mediaId
            val active = _state.value.snapshot ?: return
            val play = backend.playWhenReady
            launchCommand { select(active.generationId, active.queueRevision, entryId, play) }
        }
    }

    private suspend fun selectInternal(
        generationId: String,
        expectedQueueRevision: Long,
        entryId: String,
        explicitPlay: Boolean,
        completedCycles: Int,
        token: Long,
    ): QueueSnapshot {
        val active = activeSnapshot()
        if (active.generationId != generationId || active.queueRevision != expectedQueueRevision) throw QueueStoreException(QueueStoreIssue.CONFLICT)
        val window = queueStore.window(generationId, entryId)
        currentCoroutineContext().ensureActive()
        if (isDetached) throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.CLOSED))
        pauseForWindowChange()
        val saved = checkpointWrites.withLock {
            val live = activeSnapshot()
            if (live.generationId != generationId || live.queueRevision != expectedQueueRevision) throw QueueStoreException(QueueStoreIssue.CONFLICT)
            currentCoroutineContext().ensureActive()
            if (isDetached) throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.CLOSED))
            withContext(NonCancellable) {
                queueStore.saveCheckpoint(generationId, live.checkpointRevision, QueueCheckpoint(entryId, 0L, completedCycles))
            }
        }
        checkpointEpoch += 1L
        publishSnapshot(saved)
        if (!isDetached) applyWindow(saved, window, 0L, explicitPlay, token)
        return saved
    }

    private suspend fun applyWindow(saved: QueueSnapshot, window: QueueWindow, positionMs: Long, explicitPlay: Boolean, token: Long) {
        if (isDetached) return
        val prepared = try { prepareWindow(window) }
        catch (error: QueuePlaybackException) {
            clearBackend()
            report(error.failure)
            return
        }
        applyPreparedWindow(saved, prepared, positionMs, explicitPlay, token)
    }

    /** A current-resource failure rejects a staged replacement before publication; neighbors remain durable. */
    private suspend fun prepareWindow(window: QueueWindow): QueueWindow {
        currentCoroutineContext().ensureActive()
        if (isDetached) throw CancellationException("Playback owner detached")
        val current = window.current
        val failure = localFailure(current)
        if (failure != null) throw QueuePlaybackException(failure)
        val resolved = mutableMapOf<String, QueueEntry>()
        // Current audio gets the channel before adjacent items; a failed neighbour cannot block it.
        val candidates = listOf(current) + window.entries.filter { it.queueEntryId != current.queueEntryId }
        for (entry in candidates) {
            currentCoroutineContext().ensureActive()
            if (localFailure(entry) != null) continue
            try {
                resolved[entry.queueEntryId] = resolveEntry(entry)
                currentCoroutineContext().ensureActive()
            } catch (error: CancellationException) { throw error }
            catch (error: QueuePlaybackException) {
                currentCoroutineContext().ensureActive()
                if (entry.queueEntryId == current.queueEntryId) {
                    throw error
                }
            }
        }
        currentCoroutineContext().ensureActive()
        if (isDetached) throw CancellationException("Playback owner detached")
        val wanted = window.entries.mapNotNull { resolved[it.queueEntryId] }
        val targetIndex = wanted.indexOfFirst { it.queueEntryId == current.queueEntryId }
        check(targetIndex >= 0)
        return window.copy(entries = wanted, currentIndex = targetIndex)
    }

    /** No provider/database resource reads after queue acceptance; ownership and play intent still apply. */
    private suspend fun applyPreparedWindow(saved: QueueSnapshot, window: QueueWindow, positionMs: Long, explicitPlay: Boolean, token: Long) {
        if (isDetached) return
        val wanted = window.entries
        val current = window.current
        val targetIndex = window.currentIndex
        val previousEntries = loadedWindow?.entries?.associateBy { it.queueEntryId }.orEmpty()
        fun matches(existing: MediaItem, entry: QueueEntry): Boolean {
            val previous = previousEntries[entry.queueEntryId] ?: return false
            return existing.mediaId == entry.queueEntryId && previous.trackKey == entry.trackKey &&
                previous.location == entry.location && previous.displayName == entry.displayName &&
                existing.localConfiguration?.uri.toString() == entry.location?.uri
        }
        applyingWindow = true
        try {
            backend.pause()
            val oldCurrent = backend.currentMediaItem?.mediaId
            val existingIndex = (0 until backend.mediaItemCount).firstOrNull { index ->
                matches(backend.getMediaItemAt(index), current)
            }
            loadedWindow = window.copy(entries = wanted, currentIndex = targetIndex)
            _state.value = _state.value.copy(snapshot = saved, failure = null)
            if (existingIndex == null) {
                backend.setMediaItems(wanted.map(::mediaItem), targetIndex, positionMs)
            } else {
                if (oldCurrent != current.queueEntryId) backend.seekTo(existingIndex, positionMs)
                val wantedById = wanted.associateBy { it.queueEntryId }
                for (index in backend.mediaItemCount - 1 downTo 0) {
                    val existing = backend.getMediaItemAt(index)
                    val replacement = wantedById[existing.mediaId]
                    // 同实例的位置/内容代次变化也必须替换，不能保留邻项的旧 URI source。
                    if (replacement == null || !matches(existing, replacement)) backend.removeMediaItem(index)
                }
                wanted.forEachIndexed { index, entry ->
                    val found = (0 until backend.mediaItemCount).firstOrNull { backend.getMediaItemAt(it).mediaId == entry.queueEntryId }
                    if (found == null) backend.addMediaItem(index, mediaItem(entry)) else if (found != index) backend.moveMediaItem(found, index)
                }
                backend.seekTo(targetIndex, positionMs)
            }
            playbackCycle += 1L
            onRoundChanged(wanted[targetIndex])
            currentCoroutineContext().ensureActive()
            if (isDetached) return
            if (backend.playbackState == Player.STATE_IDLE || backend.playbackState == Player.STATE_ENDED) backend.prepare()
            resumeIntent = explicitPlay && token == intentRevision && backend.playerError == null
            if (resumeIntent) backend.play()
            resumeIntent = resumeIntent && backend.playWhenReady
            backend.playerError?.let { error ->
                val actual = QueuePlaybackFailure(errorIssue(error), current.queueEntryId)
                if (_state.value.failure != actual) report(actual)
            }
        } finally {
            applyingWindow = false
        }
        sessionBridge.logicalStateChanged()
        updateTicker()
        requestCheckpoint()
    }

    private fun pauseForWindowChange() {
        if (isDetached) return
        applyingWindow = true
        try {
            // Media3 1.8 的个别回调在 setPlayWhenReady 内 flush；不能取消自己的切换。
            backend.pause()
        } finally {
            applyingWindow = false
        }
    }

    private fun clearBackend() {
        if (isDetached) return
        applyingWindow = true
        try {
            resumeIntent = false
            loadedWindow = null
            _state.value = _state.value.copy(failure = null)
            backend.pause()
            backend.clearMediaItems()
        } finally {
            applyingWindow = false
        }
        updateTicker()
    }

    private fun naturalEnd() {
        if (isDetached || !resumeIntent || applyingWindow || handledNaturalCycle == playbackCycle) return
        val saved = _state.value.snapshot ?: return
        if (backend.currentMediaItem?.mediaId != saved.currentEntryId) return
        handledNaturalCycle = playbackCycle
        launchCommand {
            action { token ->
                val active = activeSnapshot()
                val order = active.currentPlaybackOrder ?: return@action
                val advance = naturalQueueAdvance(order, active.entryCount, active.mode, active.checkpoint.completedCycles)
                if (advance.targetOrder == null) {
                    currentCoroutineContext().ensureActive()
                    if (isDetached) return@action
                    val position = backend.currentPosition.coerceAtLeast(0L)
                    resumeIntent = false
                    val committed = checkpointWrites.withLock {
                        val live = activeSnapshot()
                        if (live.generationId != active.generationId || live.queueRevision != active.queueRevision ||
                            live.currentEntryId != active.currentEntryId
                        ) throw QueueStoreException(QueueStoreIssue.CONFLICT)
                        currentCoroutineContext().ensureActive()
                        if (isDetached) throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.CLOSED))
                        withContext(NonCancellable) {
                            queueStore.saveCheckpoint(live.generationId, live.checkpointRevision, QueueCheckpoint(live.currentEntryId, position, advance.completedCycles))
                        }
                    }
                    checkpointEpoch += 1L
                    publishSnapshot(committed)
                    if (!isDetached) pauseForWindowChange()
                } else {
                    val entry = queueStore.page(active.generationId, advance.targetOrder, 1).singleOrNull() ?: throw QueueStoreException(QueueStoreIssue.RECOVERY_REQUIRED)
                    selectInternal(active.generationId, active.queueRevision, entry.queueEntryId, true, advance.completedCycles, token)
                }
            }
        }
    }

    private fun updateTicker() {
        if (isDetached || !backend.isPlaying) {
            ticker?.cancel()
            ticker = null
        } else if (ticker?.isActive != true) {
            ticker = serviceScope.launch(Dispatchers.Main.immediate) {
                while (isActive && !isDetached) {
                    delay(CHECKPOINT_INTERVAL_MS)
                    if (!isDetached && backend.isPlaying) requestCheckpoint()
                }
            }
        }
    }

    private fun capture(): CapturedCheckpoint? {
        if (isDetached || applyingWindow) return null
        val saved = _state.value.snapshot ?: return null
        val entryId = backend.currentMediaItem?.mediaId ?: return null
        if (loadedWindow?.generationId != saved.generationId || saved.currentEntryId != entryId) return null
        return CapturedCheckpoint(saved.generationId, saved.queueRevision, intentRevision, checkpointEpoch, QueueCheckpoint(entryId, backend.currentPosition.coerceAtLeast(0L), saved.checkpoint.completedCycles))
    }

    private suspend fun drainCheckpoints() = checkpointWrites.withLock {
        while (pendingCheckpoint != null) {
            val captured = pendingCheckpoint ?: break
            pendingCheckpoint = null
            if (!captureIsCurrent(captured)) continue
            try {
                val active = queueStore.snapshot() ?: continue
                if (!captureIsCurrent(captured) || active.generationId != captured.generationId || active.queueRevision != captured.queueRevision ||
                    active.currentEntryId != captured.checkpoint.queueEntryId
                ) continue
                val written = queueStore.saveCheckpoint(active.generationId, active.checkpointRevision, captured.checkpoint)
                if (captureIsCurrent(captured)) publishCheckpoint(written)
            } catch (error: CancellationException) {
                // close 会取消 writer 再冲刷；未完成的最终捕获不能被“已取走”丢失。
                if (captureIsCurrent(captured) && pendingCheckpoint == null) pendingCheckpoint = captured
                throw error
            } catch (error: QueueStoreException) {
                // 换代冲突说明旧写已失效；不能把旧快照重新装回播放器。
                if (error.issue != QueueStoreIssue.CONFLICT) reportStore(error)
            }
        }
    }

    private fun captureIsCurrent(captured: CapturedCheckpoint): Boolean = captured.epoch == checkpointEpoch &&
        captured.intent == (if (isDetached) detachedIntentRevision else intentRevision)

    private fun publishCheckpoint(written: QueueSnapshot) {
        val known = _state.value.snapshot ?: return
        if (known.generationId == written.generationId && known.queueRevision == written.queueRevision && written.checkpointRevision >= known.checkpointRevision) {
            publishSnapshot(written)
        }
    }

    private suspend fun activeSnapshot(): QueueSnapshot = queueStore.snapshot() ?: throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.NOT_READY))

    private fun publishSnapshot(snapshot: QueueSnapshot?) {
        _state.value = _state.value.copy(snapshot = snapshot)
        if (!isDetached) sessionBridge.logicalStateChanged()
    }

    private fun launchCommand(command: suspend () -> Unit) {
        if (isDetached) return
        if (commandJob?.isActive == true) {
            report(QueuePlaybackFailure(QueuePlaybackIssue.BUSY))
            return
        }
        commandJob = serviceScope.launch(Dispatchers.Main.immediate) {
            try {
                command()
            } catch (error: CancellationException) {
                throw error
            } catch (_: QueueStoreException) {
                // action 已发出有类型的失败；不重复上报或启动后台重试。
            } catch (_: QueuePlaybackException) {
                // 公开 action 与 session 共用同一次失败报告。
            }
        }
    }

    private suspend fun <T> action(clearFailure: Boolean = true, block: suspend (Long) -> T): T = withContext(Dispatchers.Main.immediate) {
        if (isDetached) {
            val failure = QueuePlaybackFailure(QueuePlaybackIssue.CLOSED)
            report(failure)
            throw QueuePlaybackException(failure)
        }
        if (!actions.tryLock()) {
            val failure = QueuePlaybackFailure(QueuePlaybackIssue.BUSY)
            report(failure)
            throw QueuePlaybackException(failure)
        }
        intentRevision += 1L
        val token = intentRevision
        currentOperation = currentCoroutineContext()[Job]
        _state.value = _state.value.copy(preparing = true, failure = if (clearFailure) null else _state.value.failure)
        sessionBridge.logicalStateChanged()
        try {
            block(token)
        } catch (error: QueueStoreException) {
            reportStore(error)
            throw error
        } catch (error: QueuePlaybackException) {
            report(error.failure)
            throw error
        } finally {
            currentOperation = null
            actions.unlock()
            _state.value = _state.value.copy(preparing = false)
            if (!isDetached) sessionBridge.logicalStateChanged()
        }
    }

    private fun invalidateIntent() {
        intentRevision += 1L
        currentOperation?.cancel()
    }

    private fun reportStore(error: QueueStoreException) {
        val issue = when (error.issue) {
            QueueStoreIssue.BUSY -> QueuePlaybackIssue.BUSY
            QueueStoreIssue.CONFLICT, QueueStoreIssue.SOURCE_CHANGED -> QueuePlaybackIssue.CONFLICT
            QueueStoreIssue.STORAGE_FULL -> QueuePlaybackIssue.STORAGE_FULL
            QueueStoreIssue.STORAGE_UNAVAILABLE -> QueuePlaybackIssue.STORAGE_UNAVAILABLE
            QueueStoreIssue.RECOVERY_REQUIRED -> QueuePlaybackIssue.RECOVERY_REQUIRED
            else -> QueuePlaybackIssue.SOURCE_UNAVAILABLE
        }
        report(QueuePlaybackFailure(issue, _state.value.snapshot?.currentEntryId, error.issue))
    }

    private fun report(failure: QueuePlaybackFailure) {
        _state.value = _state.value.copy(failure = failure)
        if (!isDetached) onFailure(failure)
    }

    private fun localFailure(entry: QueueEntry): QueuePlaybackFailure? {
        if (entry.trackKey.providerId != TrackKey.LOCAL_PROVIDER) return QueuePlaybackFailure(QueuePlaybackIssue.SOURCE_UNSUPPORTED, entry.queueEntryId)
        val location = entry.location ?: return QueuePlaybackFailure(QueuePlaybackIssue.LOCATION_MISSING, entry.queueEntryId)
        val uri = location.uri.toUri()
        return if (uri.scheme != "content" || uri.authority.isNullOrBlank()) QueuePlaybackFailure(QueuePlaybackIssue.SOURCE_UNAVAILABLE, entry.queueEntryId) else null
    }

    private fun mediaItem(entry: QueueEntry): MediaItem = MediaItem.Builder()
        .setMediaId(entry.queueEntryId)
        .setUri(entry.location?.uri ?: error("Validated local location required"))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(entry.displayName).build())
        .build()

    private fun errorIssue(error: PlaybackException): QueuePlaybackIssue = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> QueuePlaybackIssue.PERMISSION_DENIED
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> QueuePlaybackIssue.FILE_MISSING
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_DECODING_FAILED -> QueuePlaybackIssue.SOURCE_DAMAGED
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> QueuePlaybackIssue.SOURCE_UNSUPPORTED
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED, PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED -> QueuePlaybackIssue.OUTPUT_ERROR
        else -> QueuePlaybackIssue.SOURCE_UNAVAILABLE
    }

    private fun assertMain() = check(Looper.myLooper() == Looper.getMainLooper())

    private data class CapturedCheckpoint(val generationId: String, val queueRevision: Long, val intent: Long, val epoch: Long, val checkpoint: QueueCheckpoint)

    companion object {
        private const val CHECKPOINT_INTERVAL_MS = 5000L
    }
}
