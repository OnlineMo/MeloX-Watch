package io.github.onlinemo.meloxwatch.playback

import androidx.core.net.toUri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
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
import io.github.onlinemo.meloxwatch.model.QueueMutationRequest
import io.github.onlinemo.meloxwatch.model.QueueSeekBinding
import io.github.onlinemo.meloxwatch.model.QueueSeekRequest
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import io.github.onlinemo.meloxwatch.model.QueueWindow
import io.github.onlinemo.meloxwatch.model.TrackKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
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
import java.util.UUID

/**
 * 唯一主 Looper 后端的队列所有者。数据库有全部引用，内存只有当前窗口与一个合并写入。
 * 焦点/noisy 由 Service 的唯一所有者处理，后端切换不替换会话。
 */
@UnstableApi
class QueuePlaybackCoordinator(
    backend: Player,
    private val queueStore: QueueStore,
    private val preferences: UserPreferencesRepository,
    private val serviceScope: CoroutineScope,
    private val resolveEntry: suspend (QueueEntry) -> QueueEntry = { it },
    private val onRoundChanged: suspend (QueueEntry) -> Unit = {},
    private val onFailure: (QueuePlaybackFailure) -> Unit = {},
    operations: PlaybackBackendOperations = Media3PlaybackOperations(backend),
    private val requestPlay: (() -> Boolean)? = null,
    private val onUserPause: () -> Unit = {},
    private val onUserIntentChanged: () -> Unit = {},
) {
    private var backend: Player = backend
    private var operations: PlaybackBackendOperations = operations
    private var backendBound = true
    private var backendGeneration = 0L
    private var switchingBackend = false
    private var activeBackendSwitch: QueueBackendCheckpoint? = null
    private val _state = MutableStateFlow(QueuePlaybackState())
    val state = _state.asStateFlow()
    private val sessionBridge = QueueSessionPlayer(this)
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
    private var manualEndCycle = -1L
    private val sessionId = UUID.randomUUID().toString()
    private var activeSeekBinding: QueueSeekBinding? = null
    private var seekReceipt: SeekReceipt? = null
    private var pendingCommand: (suspend () -> Unit)? = null
    private var pendingNatural: NaturalCompletion? = null
    private var pendingRecovery: RecoveryRequest? = null
    private var resourceRefreshUsed = false
    private var consecutiveSkipped = 0

    private fun backendListener(bound: Player, generation: Long) = object : Player.Listener {
        private fun current(): Boolean = !isDetached && backendBound && backend === bound && backendGeneration == generation

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!current()) return
            if (isPlaying && !applyingWindow) resetRecoveryBudget()
            updateTicker()
            if (!isPlaying && !applyingWindow) requestCheckpoint()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!current() || applyingWindow || backend.playWhenReady != playWhenReady) return
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                naturalEnd()
            } else if (!playWhenReady && backend.playerError != null) {
                // Error 的 pause 回调顺序因后端而异；恢复预算由 onPlayerError 唯一消费。
                requestCheckpoint()
            } else if (!playWhenReady) {
                resumeIntent = false
                invalidateIntent()
                requestCheckpoint()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (current() && !applyingWindow && playbackState == Player.STATE_ENDED && backend.playbackState == Player.STATE_ENDED) naturalEnd()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (current() && !applyingWindow) {
                sessionBridge.backendPositionDiscontinuity(oldPosition.contentPositionMs, newPosition.contentPositionMs, reason)
                if (reason in listOf(Player.DISCONTINUITY_REASON_SEEK, Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT)) requestCheckpoint()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (current() && !applyingWindow) requestCheckpoint()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!current()) return
            val wasApplying = applyingWindow
            val wantedPlay = resumeIntent
            val failure = QueuePlaybackFailure(errorIssue(error), backend.currentMediaItem?.mediaId)
            pauseForWindowChange()
            requestCheckpoint()
            report(failure)
            if (!wasApplying && wantedPlay && isRecoverableResourceIssue(failure.issue) && !switchingBackend) {
                pendingRecovery = RecoveryRequest(failure, playbackCycle, backendGeneration)
                startPendingCommands()
            } else if (!wasApplying) {
                resumeIntent = false
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (current()) sessionBridge.logicalStateChanged()
        }
    }
    private var listener = backendListener(backend, backendGeneration)

    private val checkpointWorker: Job

    init {
        check(Looper.myLooper() == Looper.getMainLooper() && backend.applicationLooper == Looper.getMainLooper())
        check(operations.player === backend)
        backend.repeatMode = Player.REPEAT_MODE_OFF
        backend.shuffleModeEnabled = false
        // 自然完成由完整队列决策；不能让三项窗口自动重复或先播出额外一轮。
        operations.setPauseAtBoundary(true)
        backend.addListener(listener)
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
            onUserIntentChanged()
            resetRecoveryBudget()
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
        onUserIntentChanged()
        resetRecoveryBudget()
        selectInternal(generationId, expectedQueueRevision, entryId, explicitPlay, completedCycles = 0, token)
    }

    /** 构造代次时旧音频继续；纯编辑只更新有界邻项，当前交接才暂停/准备/seek。 */
    suspend fun mutate(
        request: QueueMutationRequest,
        source: QueuePageSource? = null,
        explicitPlay: Boolean = false,
    ): QueueCommit = action(clearFailure = false) { token ->
        var prepared: QueueWindow? = null
        var keepsLoadedCurrent = false
        var keepsCurrent = false
        var currentRemoved = false
        var retainedFailure: QueuePlaybackFailure? = null
        val commit = queueStore.mutate(request, source) { candidate ->
            currentRemoved = candidate.currentRemoved
            keepsCurrent = candidate.keepsCurrent
            if (!candidate.keepsCurrent && candidate.defaultCheckpoint.queueEntryId != null) {
                onUserIntentChanged()
                resetRecoveryBudget()
            }
            val loaded = loadedWindow
            val liveCurrent = backend.currentMediaItem?.mediaId
            keepsLoadedCurrent = candidate.keepsCurrent && loaded != null &&
                loaded.generationId == candidate.previous?.generationId && liveCurrent == candidate.previous?.currentEntryId &&
                loaded.current.queueEntryId == liveCurrent && backend.playerError == null
            if (candidate.keepsCurrent) {
                val window = candidate.window
                if (window != null) {
                    try {
                        prepared = prepareWindow(window)
                        if (keepsLoadedCurrent && !sameSource(requireNotNull(loaded).current, requireNotNull(prepared).current)) {
                            throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.SOURCE_CHANGED, liveCurrent))
                        }
                    } catch (error: QueuePlaybackException) {
                        if (keepsLoadedCurrent) throw error
                        retainedFailure = error.failure
                    }
                }
            } else if (candidate.currentRemoved) {
                // 失效引用仍可管理删除；最多默认后项和三个候选，不绕回前方无限扫描。
                for (window in candidate.windows) {
                    try {
                        prepared = prepareWindow(window)
                        break
                    } catch (error: QueuePlaybackException) {
                        if (retainedFailure == null) retainedFailure = error.failure
                    }
                }
                if (prepared != null) retainedFailure = null
            } else {
                prepared = candidate.window?.let { prepareWindow(it) }
            }
            currentCoroutineContext().ensureActive()
            if (isDetached || token != intentRevision || !backendBound) throw CancellationException("Queue edit intent changed")
            val current = prepared?.current
            if (candidate.keepsCurrent && keepsLoadedCurrent) {
                val position = backend.currentPosition.coerceAtLeast(0L)
                val cycles = if (candidate.preservesCycles) _state.value.snapshot?.checkpoint?.completedCycles ?: 0 else 0
                QueueCheckpoint(candidate.defaultCheckpoint.queueEntryId, position, cycles)
            } else if (!candidate.keepsCurrent && current != null) {
                QueueCheckpoint(current.queueEntryId, 0L)
            } else {
                null
            }
        }
        if (commit.wasReplay) return@action commit
        checkpointEpoch += 1L
        publishSnapshot(commit.snapshot)
        if (!isDetached && backendBound) {
            val window = prepared
            try {
                if (commit.snapshot.entryCount == 0L) {
                    onUserIntentChanged()
                    clearBackend()
                } else if (window == null) {
                    clearBackend()
                    retainedFailure?.let(::report)
                } else if (!windowMatches(window, commit.snapshot)) {
                    clearBackend()
                    report(QueuePlaybackFailure(QueuePlaybackIssue.RECOVERY_REQUIRED, commit.snapshot.currentEntryId))
                } else if (keepsLoadedCurrent) {
                    applyKeptWindow(commit.snapshot, window)
                } else {
                    pauseForWindowChange()
                    if (currentCoroutineContext().isActive && token == intentRevision) {
                        applyPreparedWindow(commit.snapshot, window, commit.snapshot.positionMs,
                            explicitPlay = explicitPlay && !currentRemoved, token,
                            newRound = !keepsCurrent)
                    } else clearBackend()
                }
            } catch (_: CancellationException) {
                if (!isDetached) pauseForWindowChange()
            } catch (error: Exception) {
                if (!isDetached) clearBackend()
                report(playbackFailure(error, commit.snapshot.currentEntryId))
            }
        }
        commit
    }

    /** 只读取实际播放状态，不写库、访问 provider 或改变音频；替代上一份未确认预览。 */
    fun seekBinding(): QueueSeekBinding? {
        assertMain()
        if (!canSeekFromSession()) return null
        val saved = _state.value.snapshot ?: return null
        val current = loadedWindow?.current ?: return null
        if (saved.generationId != loadedWindow?.generationId || saved.currentEntryId != current.queueEntryId ||
            backend.currentMediaItem?.mediaId != current.queueEntryId) return null
        return QueueSeekBinding(UUID.randomUUID().toString(), sessionId, backendGeneration,
            saved.generationId, saved.queueRevision, current.queueEntryId, current.location?.locationId,
            current.location?.contentRevision ?: 0L, backend.duration).also { activeSeekBinding = it }
    }

    /** 确认只提交实际 seek 完成后的时钟；相同确认回读一次有界 receipt。 */
    suspend fun seek(request: QueueSeekRequest): QueueSnapshot = action(clearFailure = false) { token ->
        seekReceipt?.takeIf { it.request.binding.previewId == request.binding.previewId }?.let { receipt ->
            if (receipt.request != request) throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.CONFLICT))
            return@action _state.value.snapshot ?: receipt.snapshot
        }
        validateSeekBinding(request.binding)
        val current = requireNotNull(loadedWindow).current
        onUserIntentChanged()
        resetRecoveryBudget()
        val resolved = resolveEntry(current)
        currentCoroutineContext().ensureActive()
        if (!sameSource(current, resolved)) throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.SOURCE_CHANGED, current.queueEntryId))
        validateSeekBinding(request.binding)
        val wantedPlay = request.explicitPlay || resumeIntent || backend.playWhenReady
        checkpointEpoch += 1L
        applyingWindow = true
        try {
            // Hold the completed seek through its durable checkpoint. Playback reaching the
            // tail during that IO must not become an ignored natural end inside applyingWindow.
            backend.pause()
            sessionBridge.positionSourceChanging()
            operations.seekAndAwait(current.queueEntryId, request.positionMs.coerceIn(0L, request.binding.durationMs))
            currentCoroutineContext().ensureActive()
            validateSeekBinding(request.binding)
            val position = backend.currentPosition.coerceIn(0L, request.binding.durationMs)
            sessionBridge.positionSourceChanged(Player.DISCONTINUITY_REASON_SEEK, position)
            currentCoroutineContext().ensureActive()
            val atEnd = backend.playbackState == Player.STATE_ENDED || position >= request.binding.durationMs
            manualEndCycle = if (atEnd) playbackCycle else -1L
            pendingNatural = null
            val saved = checkpointWrites.withLock {
                val live = activeSnapshot()
                requireSeekSnapshot(live, request.binding)
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    queueStore.saveCheckpoint(live.generationId, live.checkpointRevision,
                        QueueCheckpoint(live.currentEntryId, position, live.checkpoint.completedCycles)).also {
                        seekReceipt = SeekReceipt(request, it)
                        activeSeekBinding = null
                        publishSnapshot(it)
                    }
                }
            }
            resumeIntent = wantedPlay && !atEnd && token == intentRevision
            if (resumeIntent) authorizePlay() else backend.pause()
            return@action saved
        } catch (error: Exception) {
            if (!isDetached && backendBound) {
                resumeIntent = false
                backend.pause()
            }
            throw error
        } finally {
            applyingWindow = false
            if (!isDetached) {
                updateTicker()
                requestCheckpoint()
                sessionBridge.positionSourceChanged()
            }
        }
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

    /** 暂停并等待已接受命令静止；取消在释放前仍保留原实例，绝不自动恢复播放。 */
    suspend fun freezeForBackendSwitch(): QueueBackendCheckpoint = withContext(Dispatchers.Main.immediate) {
        if (isDetached) throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.CLOSED))
        if (switchingBackend) throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.BUSY))
        switchingBackend = true
        val running = currentOperation
        val runningCommand = commandJob
        try {
            resumeIntent = false
            pendingCommand = null
            pendingRecovery = null
            onUserIntentChanged()
            invalidateIntent()
            sessionBridge.logicalStateChanged()
            if (runningCommand !== currentCoroutineContext()[Job]) runningCommand?.cancelAndJoin()
            if (running != null && running !== runningCommand && running !== currentCoroutineContext()[Job]) running.cancelAndJoin()
            actions.withLock {
                if (backendBound) pauseForWindowChange()
                settleNaturalBeforeSwitch()
                flush()
                val saved = queueStore.snapshot()
                publishSnapshot(saved)
                val position = if (backendBound && backend.currentMediaItem?.mediaId == saved?.currentEntryId &&
                    loadedWindow?.generationId == saved?.generationId) backend.currentPosition.coerceAtLeast(0L) else saved?.positionMs ?: 0L
                QueueBackendCheckpoint(
                    saved, position, saved?.checkpoint?.completedCycles ?: 0, playbackCycle, handledNaturalCycle,
                    checkpointEpoch, intentRevision, backendGeneration,
                ).also { activeBackendSwitch = it }
            }
        } catch (error: Exception) {
            switchingBackend = false
            activeBackendSwitch = null
            resumeIntent = false
            try { if (!isDetached && backendBound) pauseForWindowChange() }
            catch (cleanup: Exception) { if (cleanup !== error) error.addSuppressed(cleanup) }
            try { if (!isDetached) sessionBridge.logicalStateChanged() }
            catch (cleanup: Exception) { if (cleanup !== error) error.addSuppressed(cleanup) }
            throw error
        }
    }

    /** 必须在 Service release 前调用；所有旧 listener/supplier 先失效，再移交生命周期。 */
    fun unbindBackendForSwitch(checkpoint: QueueBackendCheckpoint) {
        assertMain()
        requireBackendSwitch(checkpoint)
        if (!backendBound) return
        val old = backend
        backendBound = false
        backendGeneration += 1L
        ticker?.cancel()
        ticker = null
        loadedWindow = null
        try {
            old.removeListener(listener)
        } finally {
            // Even a failed listener removal must leave the old callbacks and clock suppliers
            // fenced before the service releases that instance.
            sessionBridge.logicalStateChanged()
        }
    }

    suspend fun restoreAfterBackendSwitch(
        player: Player,
        operations: PlaybackBackendOperations,
        checkpoint: QueueBackendCheckpoint,
    ) = withContext(Dispatchers.Main.immediate) {
        requireBackendSwitch(checkpoint)
        check(!backendBound && player.applicationLooper == Looper.getMainLooper() && operations.player === player)
        val live = queueStore.snapshot()
        if (live?.generationId != checkpoint.snapshot?.generationId || live?.queueRevision != checkpoint.snapshot?.queueRevision ||
            live?.currentEntryId != checkpoint.snapshot?.currentEntryId || (live?.checkpoint?.completedCycles ?: 0) != checkpoint.completedCurrentCycles
        ) throw QueueStoreException(QueueStoreIssue.CONFLICT)
        backend = player
        this@QueuePlaybackCoordinator.operations = operations
        backendGeneration += 1L
        backendBound = true
        listener = backendListener(player, backendGeneration)
        backend.repeatMode = Player.REPEAT_MODE_OFF
        backend.shuffleModeEnabled = false
        operations.setPauseAtBoundary(true)
        backend.addListener(listener)
        playbackCycle = checkpoint.playbackCycle
        handledNaturalCycle = checkpoint.handledNaturalCycle
        checkpointEpoch = checkpoint.checkpointEpoch + 1L
        publishSnapshot(live)
        try {
            if (live == null || live.entryCount == 0L) clearBackend() else {
                val window = queueStore.window(live.generationId, requireNotNull(live.currentEntryId))
                val prepared = prepareWindow(window)
                currentCoroutineContext().ensureActive()
                requireBackendSwitch(checkpoint)
                applyPreparedWindow(live, prepared, checkpoint.positionMs, explicitPlay = false, intentRevision, newRound = false)
            }
            currentCoroutineContext().ensureActive()
            requireBackendSwitch(checkpoint)
            pauseForWindowChange()
            switchingBackend = false
            try {
                sessionBridge.logicalStateChanged()
                currentCoroutineContext().ensureActive()
                if (isDetached || activeBackendSwitch !== checkpoint) {
                    throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.CONFLICT))
                }
            } catch (error: Exception) {
                switchingBackend = true
                throw error
            }
            // No player getter, listener notification or suspension may throw after consumption.
            activeBackendSwitch = null
        } catch (error: Exception) {
            if (!isDetached && backendBound) {
                try { pauseForWindowChange() }
                catch (cleanup: Exception) { if (cleanup !== error) error.addSuppressed(cleanup) }
            }
            throw error
        }
    }

    fun cancelBackendSwitch(checkpoint: QueueBackendCheckpoint) {
        assertMain()
        requireBackendSwitch(checkpoint)
        check(backendBound && backendGeneration == checkpoint.backendGeneration)
        try {
            pauseForWindowChange()
        } finally {
            resumeIntent = false
            activeBackendSwitch = null
            switchingBackend = false
            sessionBridge.logicalStateChanged()
        }
    }

    /** 失败实例先从桥解绑；Service随后release它。旧快照和计数仍可供明确重试。 */
    fun failBackendSwitch(checkpoint: QueueBackendCheckpoint, failure: QueuePlaybackFailure) {
        assertMain()
        requireBackendSwitch(checkpoint)
        try {
            if (backendBound) {
                try { pauseForWindowChange() }
                finally { unbindBackendForSwitch(checkpoint) }
            }
        } finally {
            resumeIntent = false
            activeBackendSwitch = null
            switchingBackend = false
            _state.value = _state.value.copy(snapshot = checkpoint.snapshot, preparing = false)
            try { report(failure) }
            finally { sessionBridge.logicalStateChanged() }
        }
    }

    private fun requireBackendSwitch(checkpoint: QueueBackendCheckpoint) {
        if (isDetached || !switchingBackend || activeBackendSwitch !== checkpoint) {
            throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.CONFLICT))
        }
    }

    private suspend fun settleNaturalBeforeSwitch() {
        val request = pendingNatural ?: return
        if (!backendBound || request.backendGeneration != backendGeneration || request.cycle != playbackCycle ||
            handledNaturalCycle == request.cycle || manualEndCycle == request.cycle) {
            pendingNatural = null
            return
        }
        checkpointWrites.withLock {
            val live = activeSnapshot()
            if (live.currentEntryId != request.entryId || backend.currentMediaItem?.mediaId != request.entryId) {
                pendingNatural = null
                return@withLock
            }
            val cycles = if (live.checkpoint.completedCycles == Int.MAX_VALUE) Int.MAX_VALUE else live.checkpoint.completedCycles + 1
            val position = backend.currentPosition.coerceAtLeast(0L)
            withContext(NonCancellable) {
                queueStore.saveCheckpoint(live.generationId, live.checkpointRevision, QueueCheckpoint(live.currentEntryId, position, cycles)).also {
                    checkpointEpoch += 1L
                    publishSnapshot(it)
                    handledNaturalCycle = request.cycle
                    pendingNatural = null
                }
            }
        }
    }

    internal fun sessionBackend(): Player? = backend.takeIf { backendBound && !isDetached }
    internal fun sessionBackendGeneration(): Long = backendGeneration
    internal fun isCurrentBackend(player: Player, generation: Long): Boolean =
        !isDetached && backendBound && backend === player && backendGeneration == generation
    internal fun acceptsSessionCommands(): Boolean = !isDetached && !switchingBackend && backendBound

    internal fun canSeekFromSession(): Boolean = acceptsSessionCommands() && !_state.value.preparing &&
        backend.playbackState in listOf(Player.STATE_READY, Player.STATE_ENDED) && backend.playerError == null &&
        backend.duration != C.TIME_UNSET && backend.duration > 0L &&
        backend.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)

    internal fun prepareFromSession() {
        assertMain()
        if (!acceptsSessionCommands() || _state.value.preparing) return
        if (backend.playbackState == Player.STATE_READY && backend.playerError == null) return
        launchCommand {
            action { token ->
                val live = activeSnapshot()
                val entryId = live.currentEntryId ?: return@action
                val window = queueStore.window(live.generationId, entryId)
                pauseForWindowChange()
                applyWindow(live, window, live.positionMs, explicitPlay = false, token, newRound = false)
            }
        }
    }

    /** Service 在释放 backend 前调用；其后所有关闭/写入路径只消费已捕获值。 */
    fun detach() {
        assertMain()
        if (isDetached) return
        var failure: Throwable? = null
        fun cleanup(block: () -> Unit) {
            try { block() }
            catch (error: Throwable) {
                val first = failure
                if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
            }
        }
        try {
            capture()?.let { pendingCheckpoint = it }
        } catch (error: Throwable) {
            failure = error
        } finally {
            // Capture may fail on a broken backend. Fence every callback and supplier before
            // any cancellation/listener cleanup can fail and before Service releases it.
            val previous = backend.takeIf { backendBound }
            detachedIntentRevision = intentRevision
            isDetached = true
            intentRevision += 1L
            backendBound = false
            backendGeneration += 1L
            loadedWindow = null
            resumeIntent = false
            switchingBackend = false
            activeBackendSwitch = null
            pendingCommand = null
            pendingNatural = null
            pendingRecovery = null
            activeSeekBinding = null
            _state.value = _state.value.copy(preparing = false)
            cleanup { currentOperation?.cancel() }
            cleanup { commandJob?.cancel() }
            cleanup { ticker?.cancel() }
            ticker = null
            cleanup { previous?.removeListener(listener) }
            cleanup { sessionBridge.detachListeners() }
        }
        failure?.let { throw it }
    }

    suspend fun close() {
        val initiatingJob = currentCoroutineContext()[Job]
        withContext(Dispatchers.Main.immediate) {
            val running = currentOperation
            val runningCommand = commandJob
            val caller = currentCoroutineContext()[Job]
            var failure: Throwable? = null
            suspend fun cleanup(block: suspend () -> Unit) {
                try { block() }
                catch (error: Throwable) {
                    val first = failure
                    if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
                }
            }
            cleanup { if (!isDetached) detach() }
            // Cancellation cannot interrupt a provider or durable transaction's NonCancellable
            // section. No final drain or owning database close may race those accepted actors.
            cleanup { if (runningCommand !== initiatingJob && runningCommand !== caller) runningCommand?.cancelAndJoin() }
            cleanup {
                if (running != null && running !== runningCommand && running !== initiatingJob && running !== caller) running.cancelAndJoin()
            }
            cleanup { checkpointWorker.cancelAndJoin() }
            checkpointRequests.close()
            cleanup { drainCheckpoints() }
            failure?.let { throw it }
        }
    }

    internal fun canNavigate(direction: Int): Boolean {
        if (!acceptsSessionCommands() || _state.value.preparing) return false
        val snapshot = _state.value.snapshot ?: return false
        val order = snapshot.currentPlaybackOrder ?: return false
        return manualQueueTarget(order, snapshot.entryCount, snapshot.mode.repeat, direction) != null
    }

    internal fun navigateFromSession(direction: Int) {
        assertMain()
        if (!acceptsSessionCommands()) return
        require(direction == -1 || direction == 1)
        val play = backend.playWhenReady || resumeIntent
        launchCommand {
            action { token ->
                val active = activeSnapshot()
                val order = active.currentPlaybackOrder ?: return@action
                val target = manualQueueTarget(order, active.entryCount, active.mode.repeat, direction) ?: return@action
                onUserIntentChanged()
                resetRecoveryBudget()
                val entry = queueStore.page(active.generationId, target, 1).singleOrNull() ?: throw QueueStoreException(QueueStoreIssue.RECOVERY_REQUIRED)
                selectInternal(active.generationId, active.queueRevision, entry.queueEntryId, play, 0, token)
            }
        }
    }

    internal fun playFromSession() {
        assertMain()
        if (!acceptsSessionCommands()) return
        val active = _state.value.snapshot ?: return
        val entryId = active.currentEntryId ?: return
        if (_state.value.preparing) return
        val atEnd = backend.playbackState == Player.STATE_ENDED ||
            (backend.duration != C.TIME_UNSET && backend.duration > 0L && backend.currentPosition >= backend.duration)
        resetRecoveryBudget()
        if (backend.currentMediaItem?.mediaId != entryId || atEnd) {
            launchCommand { select(active.generationId, active.queueRevision, entryId, explicitPlay = true) }
        } else if (backend.playbackState != Player.STATE_READY || backend.playerError != null) {
            launchCommand {
                action { token ->
                    val live = activeSnapshot()
                    val current = live.currentEntryId ?: return@action
                    val window = queueStore.window(live.generationId, current)
                    pauseForWindowChange()
                    applyWindow(live, window, live.positionMs, explicitPlay = true, token, newRound = false)
                }
            }
        } else {
            intentRevision += 1L
            resumeIntent = true
            _state.value = _state.value.copy(failure = null)
            if (backend.playerError == null) {
                authorizePlay()
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
        onUserPause()
        pendingCommand = null
        pendingRecovery = null
        resumeIntent = false
        invalidateIntent()
        if (backendBound) backend.pause()
        requestCheckpoint()
    }

    internal fun stopFromSession() {
        assertMain()
        pauseFromSession()
        if (!backendBound || switchingBackend) return
        applyingWindow = true
        try {
            // Pause captured the position first. Stop releases loading/decoder work while the
            // durable queue and its checkpoint remain available for a later paused prepare.
            sessionBridge.positionSourceChanging()
            backend.stop()
        } catch (error: Exception) {
            report(playbackFailure(error, _state.value.snapshot?.currentEntryId))
        } finally {
            applyingWindow = false
            updateTicker()
            if (!isDetached) sessionBridge.positionSourceChanged()
        }
    }

    internal fun seekFromSession(positionMs: Long) {
        assertMain()
        val binding = seekBinding() ?: return
        launchCommand { seek(QueueSeekRequest(binding, positionMs)) }
    }

    internal fun seekByFromSession(forward: Boolean) {
        assertMain()
        if (!acceptsSessionCommands() || _state.value.preparing) return
        val position = backend.currentPosition
        val offset = if (forward) backend.seekForwardIncrement else -backend.seekBackIncrement
        val target = if (offset > 0L && position > Long.MAX_VALUE - offset) Long.MAX_VALUE else position + offset
        seekFromSession(target)
    }

    internal fun seekFromSession(mediaItemIndex: Int, positionMs: Long) {
        assertMain()
        if (!acceptsSessionCommands() || mediaItemIndex !in 0 until backend.mediaItemCount) return
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
        onCheckpointCommitted: () -> Unit = {},
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
                queueStore.saveCheckpoint(generationId, live.checkpointRevision, QueueCheckpoint(entryId, 0L, completedCycles)).also {
                    checkpointEpoch += 1L
                    publishSnapshot(it)
                    onCheckpointCommitted()
                }
            }
        }
        if (!isDetached) applyWindow(saved, window, 0L, explicitPlay, token)
        return saved
    }

    private suspend fun applyWindow(saved: QueueSnapshot, window: QueueWindow, positionMs: Long, explicitPlay: Boolean, token: Long, newRound: Boolean = true) {
        if (isDetached) return
        val prepared = try { prepareWindow(window) }
        catch (error: QueuePlaybackException) {
            clearBackend()
            report(error.failure)
            requestRecovery(error.failure, explicitPlay && token == intentRevision)
            return
        }
        try {
            applyPreparedWindow(saved, prepared, positionMs, explicitPlay, token, newRound)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            clearBackend()
            val failure = playbackFailure(error, saved.currentEntryId)
            report(failure)
            requestRecovery(failure, explicitPlay && token == intentRevision)
        }
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
                val validated = resolveEntry(entry)
                currentCoroutineContext().ensureActive()
                if (validated.queueEntryId != entry.queueEntryId || validated.trackKey != entry.trackKey ||
                    validated.accountId != entry.accountId || validated.originalOrder != entry.originalOrder ||
                    validated.playbackOrder != entry.playbackOrder) {
                    throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.SOURCE_CHANGED, entry.queueEntryId))
                }
                localFailure(validated)?.let { throw QueuePlaybackException(it) }
                resolved[entry.queueEntryId] = validated
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

    private fun sameSource(left: QueueEntry, right: QueueEntry): Boolean = left.queueEntryId == right.queueEntryId &&
        left.trackKey == right.trackKey && left.accountId == right.accountId && left.location == right.location

    private fun windowMatches(window: QueueWindow, saved: QueueSnapshot): Boolean = window.generationId == saved.generationId &&
        window.queueRevision == saved.queueRevision && window.totalCount == saved.entryCount &&
        window.current.queueEntryId == saved.currentEntryId && window.current.originalOrder == saved.currentOriginalOrder &&
        window.current.playbackOrder == saved.currentPlaybackOrder

    /** 当前实例不执行 pause/seek/prepare，也不创建新一轮；窗口排列仍由持久序列决定。 */
    private fun applyKeptWindow(saved: QueueSnapshot, window: QueueWindow) {
        val previous = loadedWindow ?: throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.NOT_READY))
        val currentId = window.current.queueEntryId
        if (backend.currentMediaItem?.mediaId != currentId || !sameSource(previous.current, window.current)) {
            throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.SOURCE_CHANGED, currentId))
        }
        val oldById = previous.entries.associateBy { it.queueEntryId }
        val wantedById = window.entries.associateBy { it.queueEntryId }
        applyingWindow = true
        try {
            for (index in backend.mediaItemCount - 1 downTo 0) {
                val item = backend.getMediaItemAt(index)
                if (item.mediaId == currentId) continue
                val old = oldById[item.mediaId]
                val wanted = wantedById[item.mediaId]
                if (old == null || wanted == null || !sameSource(old, wanted) || old.displayName != wanted.displayName) backend.removeMediaItem(index)
            }
            window.entries.forEachIndexed { index, entry ->
                val found = (0 until backend.mediaItemCount).firstOrNull { backend.getMediaItemAt(it).mediaId == entry.queueEntryId }
                if (found == null) {
                    if (entry.queueEntryId == currentId) throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.SOURCE_CHANGED, currentId))
                    backend.addMediaItem(index, mediaItem(entry))
                } else if (found != index) backend.moveMediaItem(found, index)
            }
            if (backend.currentMediaItem?.mediaId != currentId) throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.OUTPUT_ERROR, currentId))
            backend.playerError?.let { throw it }
            loadedWindow = window
            _state.value = _state.value.copy(snapshot = saved, failure = null)
        } finally {
            applyingWindow = false
        }
        sessionBridge.logicalStateChanged()
        updateTicker()
        requestCheckpoint()
    }

    private fun validateSeekBinding(binding: QueueSeekBinding) {
        if (activeSeekBinding != binding || binding.sessionId != sessionId || binding.backendGeneration != backendGeneration ||
            isDetached || switchingBackend || !backendBound) {
            throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.CONFLICT, binding.queueEntryId))
        }
        val saved = _state.value.snapshot ?: throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.NOT_READY))
        requireSeekSnapshot(saved, binding)
        val current = loadedWindow?.current ?: throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.NOT_READY, binding.queueEntryId))
        if (loadedWindow?.generationId != binding.generationId || current.queueEntryId != binding.queueEntryId ||
            current.location?.locationId != binding.locationId || (current.location?.contentRevision ?: 0L) != binding.contentRevision ||
            backend.currentMediaItem?.mediaId != binding.queueEntryId || backend.duration != binding.durationMs ||
            backend.duration <= 0L || backend.playerError != null ||
            backend.playbackState !in listOf(Player.STATE_READY, Player.STATE_ENDED) ||
            !backend.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
            throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.SOURCE_CHANGED, binding.queueEntryId))
        }
    }

    private fun requireSeekSnapshot(saved: QueueSnapshot, binding: QueueSeekBinding) {
        if (saved.generationId != binding.generationId || saved.queueRevision != binding.queueRevision || saved.currentEntryId != binding.queueEntryId) {
            throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.CONFLICT, binding.queueEntryId))
        }
    }

    private fun playbackFailure(error: Exception, entryId: String?): QueuePlaybackFailure = when (error) {
        is QueuePlaybackException -> error.failure
        is PlaybackException -> QueuePlaybackFailure(errorIssue(error), entryId)
        else -> QueuePlaybackFailure(QueuePlaybackIssue.OUTPUT_ERROR, entryId)
    }

    /** No provider/database resource reads after queue acceptance; ownership and play intent still apply. */
    private suspend fun applyPreparedWindow(
        saved: QueueSnapshot,
        window: QueueWindow,
        positionMs: Long,
        explicitPlay: Boolean,
        token: Long,
        newRound: Boolean = true,
    ) {
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
            sessionBridge.positionSourceChanging()
            backend.pause()
            val oldCurrent = backend.currentMediaItem?.mediaId
            val existingIndex = (0 until backend.mediaItemCount).firstOrNull { index ->
                matches(backend.getMediaItemAt(index), current)
            }
            loadedWindow = window.copy(entries = wanted, currentIndex = targetIndex)
            _state.value = _state.value.copy(snapshot = saved, failure = null)
            if (existingIndex == null) {
                backend.setMediaItems(wanted.map(::mediaItem), targetIndex, 0L)
            } else {
                if (oldCurrent != current.queueEntryId) backend.seekTo(existingIndex, 0L)
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
                // READY 必须先成立，再提交保存的位置。提前 seek 到尾部会让 prepare
                // 永远等不到 READY；恢复尾部位置不能被计为自然完成。
                backend.seekTo(targetIndex, 0L)
            }
            if (newRound) {
                playbackCycle += 1L
                manualEndCycle = -1L
                onRoundChanged(wanted[targetIndex])
            }
            currentCoroutineContext().ensureActive()
            if (isDetached || !backendBound) return
            operations.prepareAndAwait(current.queueEntryId)
            currentCoroutineContext().ensureActive()
            if (isDetached || !backendBound || backend.currentMediaItem?.mediaId != current.queueEntryId) return
            sessionBridge.positionSourceChanging()
            operations.seekAndAwait(current.queueEntryId, positionMs)
            currentCoroutineContext().ensureActive()
            if (isDetached || !backendBound) return
            sessionBridge.positionSourceChanged()
            backend.playerError?.let { throw it }
            val atEnd = backend.duration > 0L && backend.duration != C.TIME_UNSET && backend.currentPosition >= backend.duration
            if (atEnd) manualEndCycle = playbackCycle
            resumeIntent = explicitPlay && token == intentRevision && !atEnd
            if (resumeIntent) authorizePlay()
            resumeIntent = resumeIntent && backend.playWhenReady
            if (backend.isPlaying) resetRecoveryBudget()
        } catch (error: Exception) {
            resumeIntent = false
            if (!isDetached && backendBound) backend.pause()
            throw error
        } finally {
            applyingWindow = false
            if (!isDetached && backendBound) sessionBridge.positionSourceChanged()
        }
        updateTicker()
        requestCheckpoint()
    }

    private fun pauseForWindowChange() {
        if (isDetached || !backendBound) return
        val wasApplying = applyingWindow
        applyingWindow = true
        try {
            // Media3 1.8 的个别回调在 setPlayWhenReady 内 flush；不能取消自己的切换。
            backend.pause()
        } finally {
            applyingWindow = wasApplying
        }
    }

    private fun clearBackend() {
        if (isDetached || !backendBound) return
        applyingWindow = true
        try {
            resumeIntent = false
            loadedWindow = null
            _state.value = _state.value.copy(failure = null)
            sessionBridge.positionSourceChanging()
            backend.pause()
            backend.clearMediaItems()
        } finally {
            applyingWindow = false
            if (!isDetached && backendBound) sessionBridge.positionSourceChanged()
        }
        updateTicker()
    }

    private fun naturalEnd() {
        if (isDetached || !backendBound || switchingBackend || !resumeIntent || applyingWindow ||
            handledNaturalCycle == playbackCycle || manualEndCycle == playbackCycle) return
        val saved = _state.value.snapshot ?: return
        if (backend.currentMediaItem?.mediaId != saved.currentEntryId) return
        if (pendingNatural?.cycle != playbackCycle) pendingNatural = NaturalCompletion(requireNotNull(saved.currentEntryId), playbackCycle, backendGeneration)
        startPendingCommands()
    }

    private suspend fun processNatural(request: NaturalCompletion) = action(clearFailure = false) { token ->
        val active = activeSnapshot()
        if (pendingNatural !== request || request.backendGeneration != backendGeneration || request.cycle != playbackCycle ||
            active.currentEntryId != request.entryId || handledNaturalCycle == request.cycle || manualEndCycle == request.cycle) {
            if (pendingNatural === request) pendingNatural = null
            return@action
        }
        val order = active.currentPlaybackOrder ?: return@action
        val advance = naturalQueueAdvance(order, active.entryCount, active.mode, active.checkpoint.completedCycles)
        fun accepted() {
            handledNaturalCycle = request.cycle
            if (pendingNatural === request) pendingNatural = null
        }
        if (!resumeIntent || advance.targetOrder == null) {
            val position = backend.currentPosition.coerceAtLeast(0L)
            val cycles = if (resumeIntent) advance.completedCycles else
                if (active.checkpoint.completedCycles == Int.MAX_VALUE) Int.MAX_VALUE else active.checkpoint.completedCycles + 1
            checkpointWrites.withLock {
                val live = activeSnapshot()
                if (live.generationId != active.generationId || live.queueRevision != active.queueRevision || live.currentEntryId != active.currentEntryId) {
                    throw QueueStoreException(QueueStoreIssue.CONFLICT)
                }
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    queueStore.saveCheckpoint(live.generationId, live.checkpointRevision, QueueCheckpoint(live.currentEntryId, position, cycles)).also {
                        checkpointEpoch += 1L
                        publishSnapshot(it)
                        accepted()
                    }
                }
            }
            resumeIntent = false
            if (!isDetached) pauseForWindowChange()
        } else {
            val entry = queueStore.page(active.generationId, advance.targetOrder, 1).singleOrNull() ?: throw QueueStoreException(QueueStoreIssue.RECOVERY_REQUIRED)
            selectInternal(active.generationId, active.queueRevision, entry.queueEntryId, true, advance.completedCycles, token, ::accepted)
        }
    }

    private fun requestRecovery(failure: QueuePlaybackFailure, wantedPlay: Boolean) {
        if (wantedPlay && !isDetached && backendBound && !switchingBackend && isRecoverableResourceIssue(failure.issue) &&
            failure.entryId != null && failure.entryId == _state.value.snapshot?.currentEntryId) {
            resumeIntent = true
            pendingRecovery = RecoveryRequest(failure, playbackCycle, backendGeneration)
            startPendingCommands()
        } else resumeIntent = false
    }

    /** 一个刷新和最多三个连续后项；失败和窗口更新不会自行重置预算或绕回队首。 */
    private suspend fun processRecovery(request: RecoveryRequest) = action(clearFailure = false) { token ->
        var active = activeSnapshot()
        if (!resumeIntent || request.backendGeneration != backendGeneration || request.cycle != playbackCycle ||
            request.failure.entryId != active.currentEntryId) {
            if (pendingRecovery === request) pendingRecovery = null
            return@action
        }
        if (pendingRecovery === request) pendingRecovery = null
        var failure = request.failure
        val position = if (backend.currentMediaItem?.mediaId == active.currentEntryId) backend.currentPosition.coerceAtLeast(0L) else active.positionMs
        if (!resourceRefreshUsed) {
            resourceRefreshUsed = true
            try {
                val prepared = prepareWindow(queueStore.window(active.generationId, requireNotNull(active.currentEntryId)))
                applyPreparedWindow(active, prepared, position, explicitPlay = true, token, newRound = false)
                return@action
            } catch (error: CancellationException) { throw error }
            catch (error: QueueStoreException) { throw error }
            catch (error: Exception) {
                failure = playbackFailure(error, active.currentEntryId)
                clearBackend()
                if (!isRecoverableResourceIssue(failure.issue)) {
                    report(failure)
                    return@action
                }
            }
        }
        var order = active.currentPlaybackOrder ?: return@action
        while (consecutiveSkipped < MAX_CONSECUTIVE_SKIPS && order < active.entryCount - 1L) {
            currentCoroutineContext().ensureActive()
            order += 1L
            consecutiveSkipped += 1
            val entry = queueStore.page(active.generationId, order, 1).singleOrNull() ?: throw QueueStoreException(QueueStoreIssue.RECOVERY_REQUIRED)
            try {
                val prepared = prepareWindow(queueStore.window(active.generationId, entry.queueEntryId))
                pauseForWindowChange()
                val saved = checkpointWrites.withLock {
                    val live = activeSnapshot()
                    if (live.generationId != active.generationId || live.queueRevision != active.queueRevision || live.currentEntryId != active.currentEntryId) {
                        throw QueueStoreException(QueueStoreIssue.CONFLICT)
                    }
                    currentCoroutineContext().ensureActive()
                    withContext(NonCancellable) {
                        queueStore.saveCheckpoint(live.generationId, live.checkpointRevision, QueueCheckpoint(entry.queueEntryId, 0L)).also {
                            checkpointEpoch += 1L
                            publishSnapshot(it)
                        }
                    }
                }
                active = saved
                applyPreparedWindow(saved, prepared, 0L, explicitPlay = true, token)
                return@action
            } catch (error: CancellationException) { throw error }
            catch (error: QueueStoreException) { throw error }
            catch (error: Exception) {
                failure = playbackFailure(error, entry.queueEntryId)
                clearBackend()
                if (!isRecoverableResourceIssue(failure.issue)) break
            }
        }
        resumeIntent = false
        pauseForWindowChange()
        report(failure)
    }

    private fun resetRecoveryBudget() {
        resourceRefreshUsed = false
        consecutiveSkipped = 0
        pendingRecovery = null
    }

    private fun isRecoverableResourceIssue(issue: QueuePlaybackIssue): Boolean = issue in listOf(
        QueuePlaybackIssue.SOURCE_UNSUPPORTED, QueuePlaybackIssue.LOCATION_MISSING, QueuePlaybackIssue.PERMISSION_DENIED,
        QueuePlaybackIssue.FILE_MISSING, QueuePlaybackIssue.SOURCE_DAMAGED, QueuePlaybackIssue.SOURCE_UNAVAILABLE,
    )

    private fun updateTicker() {
        if (isDetached || !backendBound || !backend.isPlaying) {
            ticker?.cancel()
            ticker = null
        } else if (ticker?.isActive != true) {
            ticker = serviceScope.launch(Dispatchers.Main.immediate) {
                while (isActive && !isDetached) {
                    delay(CHECKPOINT_INTERVAL_MS)
                    if (!isDetached && backendBound && backend.isPlaying) requestCheckpoint()
                }
            }
        }
    }

    private fun capture(): CapturedCheckpoint? {
        if (isDetached || !backendBound || applyingWindow) return null
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
        if (isDetached || switchingBackend || !backendBound) return
        // 快速连续标准命令仅保留最近一个；自然完成和恢复各有一个独立槽位。
        pendingCommand = command
        startPendingCommands()
    }

    private fun startPendingCommands() {
        if (isDetached || switchingBackend || !backendBound || currentOperation != null || actions.isLocked || commandJob?.isActive == true) return
        val ordinary = pendingCommand
        val recovery = if (ordinary == null) pendingRecovery else null
        val natural = if (ordinary == null && recovery == null) pendingNatural else null
        val command: suspend () -> Unit = when {
            ordinary != null -> { pendingCommand = null; ordinary }
            recovery != null -> { { processRecovery(recovery) } }
            natural != null -> { { processNatural(natural) } }
            else -> return
        }
        val job = serviceScope.launch(Dispatchers.Main.immediate, start = CoroutineStart.LAZY) {
            try {
                command()
            } catch (error: CancellationException) {
                throw error
            } catch (_: QueueStoreException) {
                // 存储失败不能触发同一待处理事件的无限隐式重试。
                if (pendingRecovery === recovery) pendingRecovery = null
                if (pendingNatural === natural) pendingNatural = null
            } catch (_: QueuePlaybackException) {
                if (pendingRecovery === recovery) pendingRecovery = null
                if (pendingNatural === natural) pendingNatural = null
            } finally {
                commandJob = null
                startPendingCommands()
            }
        }
        commandJob = job
        job.start()
    }

    private suspend fun <T> action(clearFailure: Boolean = true, block: suspend (Long) -> T): T = withContext(Dispatchers.Main.immediate) {
        if (isDetached || switchingBackend || !backendBound) {
            val failure = QueuePlaybackFailure(when {
                isDetached -> QueuePlaybackIssue.CLOSED
                switchingBackend -> QueuePlaybackIssue.BUSY
                else -> QueuePlaybackIssue.NOT_READY
            })
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
        try {
            sessionBridge.logicalStateChanged()
            block(token)
        } catch (error: QueueStoreException) {
            reportStore(error)
            throw error
        } catch (error: QueuePlaybackException) {
            report(error.failure)
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val failure = playbackFailure(error, _state.value.snapshot?.currentEntryId)
            report(failure)
            throw QueuePlaybackException(failure)
        } finally {
            currentOperation = null
            actions.unlock()
            _state.value = _state.value.copy(preparing = false)
            try { if (!isDetached) sessionBridge.logicalStateChanged() }
            finally { startPendingCommands() }
        }
    }

    private fun invalidateIntent() {
        intentRevision += 1L
        currentOperation?.cancel()
    }

    private fun authorizePlay(): Boolean {
        if (isDetached || !backendBound || switchingBackend) return false
        val allowed = requestPlay?.invoke() ?: run { backend.play(); backend.playWhenReady }
        resumeIntent = allowed && backend.playWhenReady
        return resumeIntent
    }

    private fun reportStore(error: QueueStoreException) {
        val issue = when (error.issue) {
            QueueStoreIssue.BUSY -> QueuePlaybackIssue.BUSY
            QueueStoreIssue.CONFLICT, QueueStoreIssue.SOURCE_CHANGED, QueueStoreIssue.INVALID_INPUT -> QueuePlaybackIssue.CONFLICT
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
    private data class SeekReceipt(val request: QueueSeekRequest, val snapshot: QueueSnapshot)
    private data class NaturalCompletion(val entryId: String, val cycle: Long, val backendGeneration: Long)
    private data class RecoveryRequest(val failure: QueuePlaybackFailure, val cycle: Long, val backendGeneration: Long)

    companion object {
        private const val CHECKPOINT_INTERVAL_MS = 5000L
        private const val MAX_CONSECUTIVE_SKIPS = 3
    }
}
