package io.github.onlinemo.meloxwatch.playback

import androidx.core.net.toUri
import android.os.Looper
import android.os.SystemClock
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
import io.github.onlinemo.meloxwatch.playback.sleep.SleepTimerTrackBinding
import io.github.onlinemo.meloxwatch.playback.resources.PlaybackResolutionBudget
import io.github.onlinemo.meloxwatch.playback.resources.PlaybackSourceIntent
import io.github.onlinemo.meloxwatch.playback.resources.PreparedPlaybackWindow
import io.github.onlinemo.meloxwatch.playback.resources.RuntimePlaybackResource
import io.github.onlinemo.meloxwatch.playback.resources.SourcePlaybackResolver
import io.github.onlinemo.meloxwatch.playback.resources.StreamingQuality
import io.github.onlinemo.meloxwatch.playback.resources.mediaTransportIssue
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.IdentityHashMap

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
    private val onManualTrackChange: () -> Unit = {},
    private val onRetainedTrackBinding: (SleepTimerTrackBinding, SleepTimerTrackBinding) -> Unit = { _, _ -> },
    private val beforeNaturalAdvance: (SleepTimerTrackBinding) -> Boolean = { false },
    private val sourceResolver: SourcePlaybackResolver? = null,
    private val checkCueBinding: suspend (QueueEntry) -> Unit = { entry ->
        if (entry.cueReference != null) throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.SOURCE_UNSUPPORTED, entry.queueEntryId))
    },
    private val resolveMetadata: suspend (QueueEntry) -> MediaMetadata? = { null },
    private val mayContinuousPlayback: () -> Boolean = { true },
    private val mayBeginContinuousRound: () -> Boolean = { true },
    private val mayKeepContinuousTail: () -> Boolean = mayBeginContinuousRound,
    private val onContinuousRoundChanged: (QueueEntry, Long) -> Unit = { _, _ -> },
) {
    private var backend: Player = backend
    private var operations: PlaybackBackendOperations = operations
    private var backendBound = true
    private var backendGeneration = 0L
    private var switchingBackend = false
    private var activeBackendSwitch: QueueBackendCheckpoint? = null
    private val _state = MutableStateFlow(QueuePlaybackState())
    val state = _state.asStateFlow()
    private val _seekState = MutableStateFlow(QueueSeekSessionState())
    val seekState: StateFlow<QueueSeekSessionState> = _seekState.asStateFlow()
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
    private var pausedOnlineReference: QueuePausedOnlineReference? = null
    private var loadedResources: Map<String, RuntimePlaybackResource> = emptyMap()
    private var loadedMetadata: Map<String, MediaMetadata> = emptyMap()
    private val preparedWindows = mutableSetOf<PreparedPlaybackWindow>()
    private val preparedMetadata = IdentityHashMap<PreparedPlaybackWindow, Map<String, MediaMetadata>>()
    private var sourceIntent = PlaybackSourceIntent.LOCAL_ONLY
    private var streamingQuality = StreamingQuality.STANDARD
    private var streamWatchdog: Job? = null
    private var watchedMediaUri: String? = null
    private var watchedBackendGeneration = -1L
    private var loadingOnlineForPlay = false
    private var resolutionAttempt: ResolutionAttempt? = null
    private var applyingWindow = false
    private var resumeIntent = false
    private var playbackCycle = 0L
    private var handledNaturalCycle = -1L
    private var manualEndCycle = -1L
    private val sessionId = UUID.randomUUID().toString()
    private var activeSeekBinding: QueueSeekBinding? = null
    private var seekReceipt: SeekReceipt? = null
    private var seekOwner: SeekOwner? = null
    private var seekActionId = UUID.randomUUID().toString()
    private var seeking = false
    private var pendingCommand: (suspend () -> Unit)? = null
    private var pendingNatural: NaturalCompletion? = null
    private var settlingNatural: NaturalSettlement? = null
    private var pendingSwitchSettlement: NaturalSettlement? = null
    private var pendingRecovery: RecoveryRequest? = null
    private var resourceRefreshUsed = false
    private var consecutiveSkipped = 0
    private var continuousBoundary: ContinuousBoundary? = null
    private var pendingContinuous: ContinuousHandover? = null
    private var continuousWriteFailed = false
    private var boundaryPaused = true
    private var nativeBoundaryPaused: Boolean? = null
    private var continuousPreparation: ContinuousPreparation? = null
    private var continuousTailHold: ContinuousTailHold? = null
    private var finalizedRollingStream: FinalizedRollingStream? = null
    private var retainedEndScope: RetainedEndScope? = null

    private fun backendListener(bound: Player, generation: Long) = object : Player.Listener {
        private fun current(): Boolean = !isDetached && backendBound && backend === bound && backendGeneration == generation

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!current()) return
            if (isPlaying && !applyingWindow) resetRecoveryBudget()
            updateTicker()
            refreshContinuousBoundary()
            if (!isPlaying && !applyingWindow) requestCheckpoint()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!current() || backend.playWhenReady != playWhenReady) return
            if (applyingWindow) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) captureRetainedNaturalEnd()
                return
            }
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
            if (current()) {
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    witnessContinuousHandover(oldPosition, newPosition)
                }
                if (applyingWindow) return
                sessionBridge.backendPositionDiscontinuity(oldPosition.contentPositionMs, newPosition.contentPositionMs, reason)
                if (reason in listOf(Player.DISCONTINUITY_REASON_SEEK, Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT)) requestCheckpoint()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (current() && !applyingWindow) {
                requestCheckpoint()
                refreshContinuousBoundary()
            }
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
                pendingRecovery = RecoveryRequest(failure, playbackCycle, backendGeneration, failureBudget(failure))
                startPendingCommands()
            } else if (!wasApplying) {
                resumeIntent = false
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            if (current()) {
                refreshSeekState()
                refreshContinuousBoundary()
                sessionBridge.logicalStateChanged()
            }
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
        setNativeBoundaryPaused(true)
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
        sourceIntent = PlaybackSourceIntent.LOCAL_ONLY
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
            if (!isDetached && token == intentRevision) {
                if (window.current.trackKey.providerId != TrackKey.LOCAL_PROVIDER) {
                    restoreOnlineReference(saved, window, if (restorePosition) saved.positionMs else 0L)
                }
                else applyWindow(saved, window, if (restorePosition) saved.positionMs else 0L, explicitPlay = false, token)
            }
        }
        saved
    }

    suspend fun replace(
        request: QueueReplacementRequest,
        source: QueuePageSource,
        explicitPlay: Boolean,
        sourceIntent: PlaybackSourceIntent = PlaybackSourceIntent.LOCAL_ONLY,
        quality: StreamingQuality = StreamingQuality.STANDARD,
    ): QueueCommit = action(clearFailure = false) { token ->
        var prepared: PreparedPlaybackWindow? = null
        val commit = queueStore.replace(request, source) { candidate ->
            onUserIntentChanged()
            resetRecoveryBudget()
            prepared = candidate?.let { prepareWindow(it, intent = sourceIntent, quality = quality) }
            prepared?.resources?.values?.forEach(RuntimePlaybackResource::checkCurrent)
            currentCoroutineContext().ensureActive()
            if (isDetached || token != intentRevision) throw CancellationException("Queue preparation intent changed")
        }
        // 历史 operation 的确认不能重装窗口，也不代表修复了此前的加载失败。
        if (commit.wasReplay) return@action commit
        retireContinuousTraversal()
        this@QueuePlaybackCoordinator.sourceIntent = sourceIntent
        streamingQuality = quality
        onManualTrackChange()
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
                        if (!isDetached) {
                            clearBackend()
                            if (window.current.trackKey.providerId != TrackKey.LOCAL_PROVIDER) {
                                restoreOnlineReference(commit.snapshot, window.window, commit.snapshot.positionMs)
                            }
                        }
                        report(if (error is QueuePlaybackException) error.failure else
                            QueuePlaybackFailure(QueuePlaybackIssue.OUTPUT_ERROR, commit.snapshot.currentEntryId))
                    }
                }
            }
        }
        commit
    }

    suspend fun select(
        generationId: String,
        expectedQueueRevision: Long,
        entryId: String,
        explicitPlay: Boolean,
        sourceIntent: PlaybackSourceIntent? = null,
    ): QueueSnapshot = action { token ->
        onUserIntentChanged()
        resetRecoveryBudget()
        selectInternal(generationId, expectedQueueRevision, entryId, explicitPlay, completedCycles = 0, token,
            manualTrackChange = true, selectedIntent = sourceIntent)
    }

    /** 构造代次时旧音频继续；纯编辑只更新有界邻项，当前交接才暂停/准备/seek。 */
    suspend fun mutate(
        request: QueueMutationRequest,
        source: QueuePageSource? = null,
        explicitPlay: Boolean = false,
        sourceIntent: PlaybackSourceIntent? = null,
        streamingQuality: StreamingQuality? = null,
    ): QueueCommit = action(clearFailure = false) { token ->
        var prepared: PreparedPlaybackWindow? = null
        var keepsLoadedCurrent = false
        var keepsCurrent = false
        var currentRemoved = false
        var retainedFailure: QueuePlaybackFailure? = null
        val pausedReference = sessionPausedOnlineReference()
        val pausedBinding = if (pausedReference != null) sleepTimerTrackBinding() else null
        var keptReferenceWindow: QueueWindow? = null
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
                    if (pausedReference != null && loaded != null && loaded.generationId == candidate.previous?.generationId &&
                        loaded.current.queueEntryId == pausedReference.mediaItem.mediaId && sameSource(loaded.current, window.current)) {
                        keptReferenceWindow = window
                    } else try {
                        prepared = prepareWindow(window, retainCurrent = keepsLoadedCurrent,
                            intent = sourceIntent ?: this@QueuePlaybackCoordinator.sourceIntent,
                            quality = streamingQuality ?: this@QueuePlaybackCoordinator.streamingQuality)
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
                        prepared = prepareWindow(window, intent = sourceIntent ?: this@QueuePlaybackCoordinator.sourceIntent,
                            quality = streamingQuality ?: this@QueuePlaybackCoordinator.streamingQuality)
                        break
                    } catch (error: QueuePlaybackException) {
                        if (retainedFailure == null) retainedFailure = error.failure
                    }
                }
                if (prepared != null) retainedFailure = null
            } else {
                prepared = candidate.window?.let { prepareWindow(it,
                    intent = sourceIntent ?: this@QueuePlaybackCoordinator.sourceIntent,
                    quality = streamingQuality ?: this@QueuePlaybackCoordinator.streamingQuality) }
            }
            currentCoroutineContext().ensureActive()
            if (isDetached || token != intentRevision || !backendBound) throw CancellationException("Queue edit intent changed")
            val current = prepared?.current
            if (keptReferenceWindow != null) {
                val cycles = if (candidate.preservesCycles) _state.value.snapshot?.checkpoint?.completedCycles ?: 0 else 0
                QueueCheckpoint(candidate.defaultCheckpoint.queueEntryId, requireNotNull(pausedReference).positionMs, cycles)
            } else if (candidate.keepsCurrent && keepsLoadedCurrent) {
                val position = capturedBackendPosition()
                val cycles = if (candidate.preservesCycles) _state.value.snapshot?.checkpoint?.completedCycles ?: 0 else 0
                QueueCheckpoint(candidate.defaultCheckpoint.queueEntryId, position, cycles)
            } else if (!candidate.keepsCurrent && current != null) {
                QueueCheckpoint(current.queueEntryId, 0L)
            } else {
                null
            }
        }
        if (commit.wasReplay) return@action commit
        if (!keepsCurrent) retireContinuousTraversal()
        sourceIntent?.let { this@QueuePlaybackCoordinator.sourceIntent = it }
        streamingQuality?.let { this@QueuePlaybackCoordinator.streamingQuality = it }
        if (!keepsCurrent) onManualTrackChange()
        checkpointEpoch += 1L
        publishSnapshot(commit.snapshot)
        if (!isDetached && backendBound) {
            val window = prepared
            try {
                if (commit.snapshot.entryCount == 0L) {
                    onUserIntentChanged()
                    clearBackend()
                } else if (keptReferenceWindow != null) {
                    val referenceWindow = requireNotNull(keptReferenceWindow)
                    if (referenceWindow.generationId != commit.snapshot.generationId ||
                        referenceWindow.queueRevision != commit.snapshot.queueRevision ||
                        referenceWindow.current.queueEntryId != commit.snapshot.currentEntryId) {
                        throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.RECOVERY_REQUIRED, commit.snapshot.currentEntryId))
                    }
                    restoreOnlineReference(commit.snapshot, referenceWindow, commit.snapshot.positionMs)
                    if (pausedBinding != null) onRetainedTrackBinding(pausedBinding,
                        SleepTimerTrackBinding(sessionId, referenceWindow.generationId, referenceWindow.current.queueEntryId, playbackCycle))
                } else if (keepsCurrent && pendingContinuous != null) {
                    // 已提交保留当前的编辑遇到真实 AUTO：交接 actor 先按同源新代次结清。
                    // 旧当前项的准备窗口不能再装回去，以免重播刚完成的尾部。
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
        refreshSeekState()
        if (!canSeekFromSession()) return null
        val owner = seekOwner ?: return null
        val identity = owner.identity
        return QueueSeekBinding(UUID.randomUUID().toString(), identity.sessionId, identity.backendGeneration,
            identity.generationId, identity.queueRevision, identity.queueEntryId, identity.locationId,
            identity.contentRevision, identity.durationMs, identity.playbackCycle).also {
            activeSeekBinding = it
            seekReceipt = null
            _seekState.value = QueueSeekSessionState(owner.id, it.previewId)
        }
    }

    internal fun seekSessionState(): QueueSeekSessionState {
        assertMain()
        refreshSeekState()
        return _seekState.value
    }

    /** 确认只提交实际 seek 完成后的时钟；相同确认回读一次有界 receipt。 */
    suspend fun seek(request: QueueSeekRequest): QueueSnapshot = action(clearFailure = false, preserveSeekOwner = true) { token ->
        seekReceipt?.takeIf { it.request.binding.previewId == request.binding.previewId }?.let { receipt ->
            if (receipt.request != request) throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.CONFLICT))
            return@action _state.value.snapshot ?: receipt.snapshot
        }
        validateSeekBinding(request.binding)
        val current = requireNotNull(loadedWindow).current
        onUserIntentChanged()
        resetRecoveryBudget()
        if (current.trackKey.providerId == TrackKey.LOCAL_PROVIDER) {
            val resolved = resolveEntry(current)
            currentCoroutineContext().ensureActive()
            if (!sameSource(current, resolved)) throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.SOURCE_CHANGED, current.queueEntryId))
        } else {
            loadedResources[current.queueEntryId]?.checkCurrent()
                ?: throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.NOT_READY, current.queueEntryId))
        }
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
            // 已完成且同绑定的seek更新暂停捕获；CAS失败也不能退回旧位置。
            captureFinalizedRollingPosition(position)
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
        pendingContinuous?.let { continuous ->
            if (backendBound && backend.currentMediaItem?.mediaId == continuous.incoming.queueEntryId) {
                continuous.incomingPositionMs = capturedBackendPosition()
            }
        }
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
        invalidateSeekOwner()
        val running = currentOperation
        val runningCommand = commandJob
        try {
            // A noncancellable receipt may already name the next item while the ending is still
            // loaded. Capture before cancellation; a failed platform getter also releases the
            // switching reservation through this catch, so an explicit retry remains possible.
            val natural = (settlingNatural ?: pendingSwitchSettlement)?.takeIf(::hasLoadedSettlement)
            pendingSwitchSettlement = natural
            resumeIntent = false
            pendingCommand = null
            pendingRecovery = null
            onUserIntentChanged()
            invalidateIntent()
            // 连续后继在 CAS 中仍可推进；先暂停并捕获，不能等旧事务退出后才响应切后端。
            if (backendBound) pauseForWindowChange()
            requestCheckpoint()
            sessionBridge.logicalStateChanged()
            if (runningCommand !== currentCoroutineContext()[Job]) runningCommand?.cancelAndJoin()
            if (running != null && running !== runningCommand && running !== currentCoroutineContext()[Job]) running.cancelAndJoin()
            actions.withLock {
                if (backendBound) pauseForWindowChange()
                pendingContinuous?.let { settleContinuousHandover(it) }
                settleNaturalBeforeSwitch(natural)
                pendingSwitchSettlement = null
                flush()
                val saved = queueStore.snapshot()
                publishSnapshot(saved)
                val position = if (backendBound && backend.currentMediaItem?.mediaId == saved?.currentEntryId &&
                    loadedWindow?.generationId == saved?.generationId) capturedBackendPosition() else saved?.positionMs ?: 0L
                QueueBackendCheckpoint(
                    saved, position, saved?.checkpoint?.completedCycles ?: 0, playbackCycle, handledNaturalCycle,
                    checkpointEpoch, intentRevision, backendGeneration,
                    mediaMetadata = sessionBridge.mediaMetadata,
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
            refreshSeekState()
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
        invalidateSeekOwner()
        ticker?.cancel()
        ticker = null
        releaseRuntimeWindows()
        loadedWindow = null
        continuousPreparation = null
        retireTailHold(finalize = true)
        nativeBoundaryPaused = null
        finalizedRollingStream = null
        retainedEndScope = null
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
        nativeBoundaryPaused = null
        setNativeBoundaryPaused(true)
        boundaryPaused = true
        finalizedRollingStream = null
        continuousPreparation = null
        retireTailHold(finalize = true)
        continuousBoundary = null
        backend.addListener(listener)
        playbackCycle = checkpoint.playbackCycle
        handledNaturalCycle = checkpoint.handledNaturalCycle
        checkpointEpoch = checkpoint.checkpointEpoch + 1L
        publishSnapshot(live)
        try {
            if (live == null || live.entryCount == 0L) clearBackend() else {
                val window = queueStore.window(live.generationId, requireNotNull(live.currentEntryId))
                if (window.current.trackKey.providerId != TrackKey.LOCAL_PROVIDER) restoreOnlineReference(live, window, checkpoint.positionMs, checkpoint.mediaMetadata)
                else {
                    val prepared = prepareWindow(window)
                    currentCoroutineContext().ensureActive()
                    requireBackendSwitch(checkpoint)
                    applyPreparedWindow(live, prepared, checkpoint.positionMs, explicitPlay = false, intentRevision, newRound = false)
                }
            }
            currentCoroutineContext().ensureActive()
            requireBackendSwitch(checkpoint)
            pauseForWindowChange()
            switchingBackend = false
            try {
                refreshSeekState()
                sessionBridge.logicalStateChanged()
                currentCoroutineContext().ensureActive()
                if (isDetached || activeBackendSwitch !== checkpoint) {
                    throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.CONFLICT))
                }
            } catch (error: Exception) {
                switchingBackend = true
                invalidateSeekOwner()
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
            refreshSeekState()
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

    private suspend fun settleNaturalBeforeSwitch(captured: NaturalSettlement?) {
        val request = captured?.request ?: pendingNatural ?: return
        if (!backendBound || request.backendGeneration != backendGeneration || request.cycle != playbackCycle ||
            (captured == null && handledNaturalCycle == request.cycle) || manualEndCycle == request.cycle) {
            if (pendingNatural === request) pendingNatural = null
            return
        }
        if (captured != null && !hasLoadedSettlement(captured)) return
        checkpointWrites.withLock {
            val live = activeSnapshot()
            if (captured != null && (live.generationId != captured.ending.queueGenerationId ||
                live.queueRevision != captured.queueRevision ||
                (live.currentEntryId != captured.ending.queueEntryId && live.checkpoint != captured.submittedCheckpoint))) {
                throw QueueStoreException(QueueStoreIssue.CONFLICT)
            }
            if (captured == null && (live.currentEntryId != request.entryId || backend.currentMediaItem?.mediaId != request.entryId)) {
                if (pendingNatural === request) pendingNatural = null
                return@withLock
            }
            val ending = captured?.ending ?: sleepTimerTrackBinding() ?: return@withLock
            beforeNaturalAdvance(ending)
            val checkpoint = captured?.endingCheckpoint ?: QueueCheckpoint(request.entryId,
                backend.currentPosition.coerceAtLeast(0L), completedCycleCount(live.checkpoint.completedCycles))
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                val saved = if (live.checkpoint == checkpoint) live else
                    queueStore.saveCheckpoint(live.generationId, live.checkpointRevision, checkpoint)
                saved.also {
                    checkpointEpoch += 1L
                    publishSnapshot(it)
                    handledNaturalCycle = request.cycle
                    if (pendingNatural === request) pendingNatural = null
                }
            }
        }
    }

    private fun hasLoadedSettlement(settlement: NaturalSettlement): Boolean = backendBound &&
        settlement.request.backendGeneration == backendGeneration && settlement.request.cycle == playbackCycle &&
        loadedWindow?.generationId == settlement.ending.queueGenerationId &&
        loadedWindow?.current?.queueEntryId == settlement.ending.queueEntryId &&
        backend.currentMediaItem?.mediaId == settlement.ending.queueEntryId

    internal fun sessionBackend(): Player? = backend.takeIf { backendBound && !isDetached }

    /** 绑定实际仍装载的轮次；自然提交的短交接中逻辑checkpoint可能已指向后项。 */
    internal fun sleepTimerTrackBinding(): SleepTimerTrackBinding? {
        assertMain()
        if (isDetached) return null
        val window = loadedWindow
        val reference = sessionPausedOnlineReference()
        if (window != null && reference != null) {
            return SleepTimerTrackBinding(sessionId, window.generationId, reference.mediaItem.mediaId, playbackCycle)
        }
        if (backendBound && window != null && backend.currentMediaItem?.mediaId == window.current.queueEntryId && backend.playerError == null) {
            return SleepTimerTrackBinding(sessionId, window.generationId, window.current.queueEntryId, playbackCycle)
        }
        // Release and source resolution are temporary gaps in the same paused traversal.
        // Refresh must not turn them into a permanent no-track deadline. Only the live opaque
        // switch token supplies this identity; failure/cancellation/consumption clears it.
        val frozen = activeBackendSwitch?.takeIf { switchingBackend } ?: return null
        val saved = frozen.snapshot ?: return null
        val entryId = saved.currentEntryId ?: return null
        return SleepTimerTrackBinding(sessionId, saved.generationId, entryId, frozen.playbackCycle)
    }
    internal fun sessionBackendGeneration(): Long = backendGeneration
    internal fun isCurrentBackend(player: Player, generation: Long): Boolean =
        !isDetached && backendBound && backend === player && backendGeneration == generation
    internal fun acceptsSessionCommands(): Boolean = !isDetached && !switchingBackend && backendBound

    internal fun canSeekFromSession(): Boolean = acceptsSessionCommands() && !_state.value.preparing && _state.value.failure == null &&
        backend.playbackState in listOf(Player.STATE_READY, Player.STATE_ENDED) && backend.playerError == null &&
        backend.duration != C.TIME_UNSET && backend.duration > 0L &&
        backend.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)

    internal fun prepareFromSession() {
        assertMain()
        if (!acceptsSessionCommands() || _state.value.preparing) return
        if (sessionPausedOnlineReference() != null) return
        if (backend.playbackState == Player.STATE_READY && backend.playerError == null) return
        launchCommand {
            action { token ->
                val live = activeSnapshot()
                val entryId = live.currentEntryId ?: return@action
                val window = queueStore.window(live.generationId, entryId)
                pauseForWindowChange()
                if (window.current.trackKey.providerId != TrackKey.LOCAL_PROVIDER) restoreOnlineReference(live, window)
                else applyWindow(live, window, live.positionMs, explicitPlay = false, token, newRound = false)
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
            pendingContinuous?.let { continuous ->
                if (backendBound && backend.currentMediaItem?.mediaId == continuous.incoming.queueEntryId) {
                    continuous.incomingPositionMs = capturedBackendPosition()
                }
            }
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
            continuousPreparation = null
            cleanup { retireTailHold(finalize = true) }
            nativeBoundaryPaused = null
            finalizedRollingStream = null
            retainedEndScope = null
            cleanup(::releaseRuntimeWindows)
            resumeIntent = false
            switchingBackend = false
            activeBackendSwitch = null
            pendingCommand = null
            pendingNatural = null
            pendingRecovery = null
            activeSeekBinding = null
            invalidateSeekOwner()
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
            cleanup { pendingContinuous?.let { settleContinuousHandover(it) } }
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
                selectInternal(active.generationId, active.queueRevision, entry.queueEntryId, play, 0, token,
                    manualTrackChange = true)
            }
        }
    }

    internal fun playFromSession() {
        assertMain()
        if (!acceptsSessionCommands()) return
        if (pendingContinuous != null) {
            if (operations.supportsRollingQueueTail && !continuousWriteFailed && resumeIntent && backend.playWhenReady) return
            // 实际已过轨的写入失败只能重试结清；不能prepare/seek回刚播完的旧尾部。
            continuousWriteFailed = false
            intentRevision += 1L
            pendingContinuous?.explicitResumeIntent = intentRevision
            resumeIntent = true
            if (operations.supportsRollingQueueTail) queueContinuousResume(requireNotNull(pendingContinuous), intentRevision)
            startPendingCommands()
            return
        }
        val active = _state.value.snapshot ?: return
        val entryId = active.currentEntryId ?: return
        // 显式播放按实际音源放行网络意图；可用适配器及账户权限仍由 SourcePlaybackResolver 核验。
        val provider = loadedWindow?.current?.trackKey?.providerId
        if (provider != null && provider != TrackKey.LOCAL_PROVIDER) sourceIntent = PlaybackSourceIntent.ONLINE_ALLOWED
        if (_state.value.preparing) return
        val reference = sessionPausedOnlineReference()
        if (reference != null) {
            resetRecoveryBudget()
            launchCommand {
                action { token ->
                    if (sessionPausedOnlineReference() !== reference) throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.SOURCE_CHANGED, entryId))
                    val live = activeSnapshot()
                    val window = queueStore.window(live.generationId, entryId)
                    pauseForWindowChange()
                    applyWindow(live, window, reference.positionMs, explicitPlay = true, token, newRound = false)
                }
            }
            return
        }
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
        } else if (loadedWindow?.current?.cueReference != null) {
            val captured = requireNotNull(loadedWindow).current
            launchCommand {
                action { token ->
                    val resolved = resolveEntry(captured)
                    currentCoroutineContext().ensureActive()
                    val current = loadedWindow?.current
                    if (current == null || !sameSource(current, captured) || !sameSource(resolved, captured) ||
                        backend.currentMediaItem?.mediaId != captured.queueEntryId || backend.playbackState != Player.STATE_READY) {
                        throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.SOURCE_CHANGED, captured.queueEntryId))
                    }
                    loadedResources[captured.queueEntryId]?.checkCurrent()
                        ?: throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.NOT_READY, captured.queueEntryId))
                    resumeIntent = token == intentRevision
                    if (resumeIntent && !resumeFinalizedRollingStream(token)) authorizePlay()
                    if (token == intentRevision && pendingContinuous == null) resumeIntent = resumeIntent && backend.playWhenReady
                    updateTicker()
                }
            }
        } else if (finalizedRollingStreamMatches() && !backend.playWhenReady) {
            launchCommand {
                action { token ->
                    resumeIntent = true
                    if (!resumeFinalizedRollingStream(token) && token == intentRevision && pendingContinuous == null) authorizePlay()
                    updateTicker()
                }
            }
        } else {
            invalidateSeekOwner()
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
            refreshContinuousBoundary()
            refreshSeekState()
        }
    }

    /** 只检查有界已加载条目；进度tick不能创建第二份权威状态。 */
    internal fun cueEntriesSnapshot(): List<QueueEntry> {
        assertMain()
        if (isDetached || !backendBound || switchingBackend) return emptyList()
        return loadedWindow?.entries.orEmpty().filter { entry ->
            val resource = loadedResources[entry.queueEntryId]
            entry.cueReference != null && resource != null && try {
                resource.checkCurrent();
                true
            } catch (_: QueuePlaybackException) {
                false
            }
        }
    }

    internal fun invalidateCueReference(entry: QueueEntry, issue: QueuePlaybackIssue) {
        assertMain()
        val window = loadedWindow ?: return
        if (isDetached || !backendBound || switchingBackend || window.entries.none { sameSource(it, entry) }) return
        val resource = loadedResources[entry.queueEntryId] ?: return
        resource.cue?.owner?.invalidateCue(entry.queueEntryId)
        if (window.current.queueEntryId == entry.queueEntryId) {
            onUserPause()
            pauseForInterruption()
            invalidateSeekOwner()
            report(QueuePlaybackFailure(issue, entry.queueEntryId))
        } else {
            val wasApplying = applyingWindow
            applyingWindow = true
            try {
                val index = (0 until backend.mediaItemCount).firstOrNull { backend.getMediaItemAt(it).mediaId == entry.queueEntryId }
                if (index != null) backend.removeMediaItem(index)
                val kept = window.entries.filter { it.queueEntryId != entry.queueEntryId }
                loadedWindow = window.copy(entries = kept, currentIndex = kept.indexOfFirst { it.queueEntryId == window.current.queueEntryId })
                loadedResources = loadedResources - entry.queueEntryId
                loadedMetadata = loadedMetadata - entry.queueEntryId
                resource.close()
            } finally { applyingWindow = wasApplying }
            sessionBridge.logicalStateChanged()
        }
    }

    internal fun pauseFromSession() {
        assertMain()
        if (isDetached) return
        onUserPause()
        pauseForInterruption()
    }

    /** 焦点/noisy暂停不调用手动暂停hook。 */
    internal fun pauseForInterruption() {
        assertMain()
        if (isDetached) return
        pendingCommand = null
        pendingRecovery = null
        resumeIntent = false
        if (backendBound && backend.playWhenReady) rememberFinalizedRollingStream(backend.currentPosition)
        invalidateIntent()
        if (backendBound) backend.pause()
        requestCheckpoint()
        refreshSeekState()
    }

    /** 截止先废发声意图；旧自然结束的持久结清仍需完成，不能留下后项的0位置。 */
    internal fun pauseForSleepTimer() {
        assertMain()
        if (isDetached) return
        pendingCommand = null
        pendingRecovery = null
        resumeIntent = false
        invalidateSeekOwner()
        intentRevision += 1L
        val settling = settlingNatural
        if (settling == null || settling.ending != sleepTimerTrackBinding()) currentOperation?.cancel()
        // A synchronous pause callback must not cancel the natural completion we just retained.
        pauseForWindowChange()
        updateTicker()
        requestCheckpoint()
        refreshSeekState()
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
        launchCommand(preserveSeekOwner = true) { seek(QueueSeekRequest(binding, positionMs)) }
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
        manualTrackChange: Boolean = false,
        naturalSettlement: NaturalSettlement? = null,
        selectedIntent: PlaybackSourceIntent? = null,
    ): QueueSnapshot {
        val active = activeSnapshot()
        if (active.generationId != generationId || active.queueRevision != expectedQueueRevision) throw QueueStoreException(QueueStoreIssue.CONFLICT)
        val naturalEnding = naturalSettlement?.ending
        val endingCheckpoint = naturalEnding?.let {
            if (active.currentEntryId != it.queueEntryId || sleepTimerTrackBinding() != it) {
                throw QueueStoreException(QueueStoreIssue.CONFLICT)
            }
            requireNotNull(naturalSettlement).endingCheckpoint
        }
        val window = queueStore.window(generationId, entryId)
        currentCoroutineContext().ensureActive()
        if (isDetached) throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.CLOSED))
        pauseForWindowChange()
        var blockedAdvance = false
        var saved = checkpointWrites.withLock {
            val live = activeSnapshot()
            if (live.generationId != generationId || live.queueRevision != expectedQueueRevision) throw QueueStoreException(QueueStoreIssue.CONFLICT)
            currentCoroutineContext().ensureActive()
            if (isDetached) throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.CLOSED))
            blockedAdvance = naturalEnding != null && beforeNaturalAdvance(naturalEnding)
            val checkpoint = if (blockedAdvance) requireNotNull(endingCheckpoint)
                else QueueCheckpoint(entryId, 0L, completedCycles)
            naturalSettlement?.submittedCheckpoint = checkpoint
            withContext(NonCancellable) {
                queueStore.saveCheckpoint(generationId, live.checkpointRevision, checkpoint).also {
                    retireContinuousTraversal()
                    checkpointEpoch += 1L
                    publishSnapshot(it)
                    selectedIntent?.let { selected -> sourceIntent = selected }
                    onCheckpointCommitted()
                    if (manualTrackChange) onManualTrackChange()
                }
            }
        }
        suspend fun stopBeforeHandover(): Boolean {
            if (blockedAdvance) return true
            val ending = naturalEnding ?: return false
            if (isDetached || sleepTimerTrackBinding() != ending || !beforeNaturalAdvance(ending)) return false
            blockedAdvance = true
            resumeIntent = false
            pendingRecovery = null
            saved = checkpointWrites.withLock {
                val live = activeSnapshot()
                if (live.generationId != generationId || live.queueRevision != expectedQueueRevision || live.currentEntryId != entryId) {
                    throw QueueStoreException(QueueStoreIssue.CONFLICT)
                }
                withContext(NonCancellable) {
                    queueStore.saveCheckpoint(generationId, live.checkpointRevision, requireNotNull(endingCheckpoint)).also {
                        checkpointEpoch += 1L
                        publishSnapshot(it)
                    }
                }
            }
            return true
        }
        // Both the durable receipt and provider resolution can suspend while the old traversal
        // is still loaded. The last check must sit immediately before the actual backend handover.
        if (stopBeforeHandover()) {
            resumeIntent = false
            if (!isDetached) pauseForWindowChange()
        } else if (!isDetached) applyWindow(saved, window, 0L, explicitPlay, token,
            beforeHandover = { !stopBeforeHandover() })
        return saved
    }

    private suspend fun applyWindow(
        saved: QueueSnapshot,
        window: QueueWindow,
        positionMs: Long,
        explicitPlay: Boolean,
        token: Long,
        newRound: Boolean = true,
        beforeHandover: suspend () -> Boolean = { true },
    ) {
        if (isDetached) return
        val prepared = try { prepareWindow(window) }
        catch (error: QueuePlaybackException) {
            if (!beforeHandover()) return
            clearBackend()
            if (window.current.trackKey.providerId != TrackKey.LOCAL_PROVIDER) restoreOnlineReference(saved, window, positionMs)
            report(error.failure)
            requestRecovery(error.failure, explicitPlay && token == intentRevision)
            return
        }
        try {
            applyPreparedWindow(saved, prepared, positionMs, explicitPlay, token, newRound, beforeHandover)
        } catch (error: CancellationException) { throw error }
        catch (error: QueueStoreException) { throw error }
        catch (error: Exception) {
            clearBackend()
            if (window.current.trackKey.providerId != TrackKey.LOCAL_PROVIDER) restoreOnlineReference(saved, window, positionMs)
            val failure = playbackFailure(error, saved.currentEntryId)
            report(failure)
            requestRecovery(failure, explicitPlay && token == intentRevision)
        }
    }

    /** A current-resource failure rejects a staged replacement before publication; neighbors remain durable. */
    private suspend fun prepareWindow(
        window: QueueWindow,
        retainCurrent: Boolean = false,
        budget: PlaybackResolutionBudget? = null,
        intent: PlaybackSourceIntent = sourceIntent,
        quality: StreamingQuality = streamingQuality,
    ): PreparedPlaybackWindow {
        currentCoroutineContext().ensureActive()
        if (isDetached) throw CancellationException("Playback owner detached")
        val current = window.current
        val failure = referenceFailure(current)
        if (failure != null) throw QueuePlaybackException(failure)
        val resolved = mutableMapOf<String, RuntimePlaybackResource>()
        val metadata = mutableMapOf<String, MediaMetadata>()
        var transferred = false
        // An online current item gets first sound without neighbor HTTP or physical reads.
        val candidates = if (current.trackKey.providerId == TrackKey.LOCAL_PROVIDER) {
            listOf(current) + window.entries.filter {
                it.queueEntryId != current.queueEntryId && it.trackKey.providerId == TrackKey.LOCAL_PROVIDER
            }
        } else listOf(current)
        try {
            for (entry in candidates) {
                currentCoroutineContext().ensureActive()
                if (referenceFailure(entry) != null) continue
                try {
                    // 分轨token选择实际PCM窗口；注册不能由普通content资源包装代替。
                    val resource = if (entry.trackKey.providerId == TrackKey.LOCAL_PROVIDER && entry.cueReference == null) {
                        RuntimePlaybackResource(resolveEntry(entry))
                    } else if (retainCurrent && entry.queueEntryId == current.queueEntryId &&
                        loadedResources[entry.queueEntryId]?.let { it.isOnline || it.cue != null } == true) {
                        val retained = requireNotNull(loadedResources[entry.queueEntryId])
                        if (retained.cue == null) retained.retain(entry) else {
                            // 保留当前的编辑不重装内核URI；重核分轨后继续同一物理epoch。
                            val checked = resolveEntry(entry)
                            currentCoroutineContext().ensureActive()
                            retained.retain(checked)
                        }
                    } else {
                        val resolver = sourceResolver ?: throw QueuePlaybackException(
                            QueuePlaybackFailure(QueuePlaybackIssue.SOURCE_UNSUPPORTED, entry.queueEntryId))
                        val operation = budget ?: PlaybackResolutionBudget()
                        resolutionAttempt = ResolutionAttempt(window.generationId, entry.queueEntryId, operation)
                        resolver.resolve(entry, intent, operation, quality)
                    }
                    resolved[entry.queueEntryId] = resource
                    val validated = resource.entry
                    currentCoroutineContext().ensureActive()
                    if (validated.queueEntryId != entry.queueEntryId || validated.trackKey != entry.trackKey ||
                        validated.accountId != entry.accountId || validated.originalOrder != entry.originalOrder ||
                        validated.playbackOrder != entry.playbackOrder) {
                        throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.SOURCE_CHANGED, entry.queueEntryId))
                    }
                    referenceFailure(validated)?.let { throw QueuePlaybackException(it) }
                    resource.checkCurrent()
                    // Ancillary cached metadata cannot delay first sound for an HTTP request or fail an accepted resource.
                    val presentation = if (retainCurrent) loadedMetadata[entry.queueEntryId] else null
                    try {
                        (presentation ?: resolveMetadata(validated))?.let { metadata[entry.queueEntryId] = it }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { }
                } catch (error: CancellationException) { throw error }
                catch (error: QueuePlaybackException) {
                    currentCoroutineContext().ensureActive()
                    if (entry.queueEntryId == current.queueEntryId) throw error
                    resolved.remove(entry.queueEntryId)?.close()
                }
            }
            currentCoroutineContext().ensureActive()
            if (isDetached) throw CancellationException("Playback owner detached")
            val wanted = window.entries.mapNotNull { resolved[it.queueEntryId]?.entry }
            val targetIndex = wanted.indexOfFirst { it.queueEntryId == current.queueEntryId }
            check(targetIndex >= 0)
            return PreparedPlaybackWindow(window.copy(entries = wanted, currentIndex = targetIndex), resolved.toMap()).also {
                preparedWindows.add(it)
                preparedMetadata[it] = metadata.toMap()
                transferred = true
            }
        } finally { if (!transferred) resolved.values.forEach(RuntimePlaybackResource::close) }
    }

    private fun sameSource(left: QueueEntry, right: QueueEntry): Boolean = left.queueEntryId == right.queueEntryId &&
        left.trackKey == right.trackKey && left.accountId == right.accountId && left.location == right.location && left.cueReference == right.cueReference

    private fun windowMatches(window: PreparedPlaybackWindow, saved: QueueSnapshot): Boolean = window.generationId == saved.generationId &&
        window.queueRevision == saved.queueRevision && window.totalCount == saved.entryCount &&
        window.current.queueEntryId == saved.currentEntryId && window.current.originalOrder == saved.currentOriginalOrder &&
        window.current.playbackOrder == saved.currentPlaybackOrder

    /** 当前实例不执行 pause/seek/prepare，也不创建新一轮；窗口排列仍由持久序列决定。 */
    private fun applyKeptWindow(saved: QueueSnapshot, prepared: PreparedPlaybackWindow) {
        if (operations.supportsRollingQueueTail) {
            applyRollingKeptWindow(saved, prepared)
            return
        }
        val window = prepared.window
        val previous = loadedWindow ?: throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.NOT_READY))
        val currentId = window.current.queueEntryId
        val previousTimerBinding = SleepTimerTrackBinding(sessionId, previous.generationId, currentId, playbackCycle)
        if (backend.currentMediaItem?.mediaId != currentId || !sameSource(previous.current, window.current)) {
            throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.SOURCE_CHANGED, currentId))
        }
        prepared.resources.values.forEach(RuntimePlaybackResource::checkCurrent)
        adoptRuntimeWindow(prepared)
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
            onRetainedTrackBinding(previousTimerBinding,
                SleepTimerTrackBinding(sessionId, window.generationId, currentId, playbackCycle))
            _state.value = _state.value.copy(snapshot = saved, failure = null)
        } finally {
            applyingWindow = false
        }
        sessionBridge.logicalStateChanged()
        updateTicker()
        requestCheckpoint()
    }

    /** 先退休旧邻项，再发布新窗口/唯一许可；旧动态尾标记在append后才撤销。 */
    private fun applyRollingKeptWindow(saved: QueueSnapshot, prepared: PreparedPlaybackWindow, initialForResume: Boolean = false) {
        val window = prepared.window
        val previous = loadedWindow ?: throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.NOT_READY))
        val currentId = window.current.queueEntryId
        val cycle = playbackCycle
        val generation = backendGeneration
        val token = intentRevision
        val owner = currentOperation
        val previousBinding = SleepTimerTrackBinding(sessionId, previous.generationId, currentId, cycle)
        if (backend.currentMediaItem?.mediaId != currentId || !sameSource(previous.current, window.current) ||
            backend.currentMediaItem?.localConfiguration?.uri != prepared.resources[currentId]?.mediaUri) {
            throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.SOURCE_CHANGED, currentId))
        }
        prepared.resources.values.forEach(RuntimePlaybackResource::checkCurrent)
        val endScope = if (!initialForResume && owner != null) RetainedEndScope(owner, token, window) else null
        fun current(): Boolean = !isDetached && backendBound && backendGeneration == generation && playbackCycle == cycle &&
            intentRevision == token && currentOperation === owner && owner?.isActive == true && pendingContinuous == null &&
            backend.currentMediaItem?.mediaId == currentId && loadedWindow?.current?.queueEntryId == currentId &&
            !(endScope?.receipt != null && loadedWindow?.generationId == window.generationId && loadedWindow?.queueRevision == window.queueRevision)
        val oldById = previous.entries.associateBy { it.queueEntryId }
        val wantedById = window.entries.associateBy { it.queueEntryId }
        val wasApplying = applyingWindow
        retainedEndScope = endScope
        applyingWindow = true
        try {
            for (index in backend.mediaItemCount - 1 downTo 0) {
                if (!current()) return
                val item = backend.getMediaItemAt(index)
                if (item.mediaId == currentId) continue
                val old = oldById[item.mediaId]
                val wanted = wantedById[item.mediaId]
                if (old == null || wanted == null || !sameSource(old, wanted) || old.displayName != wanted.displayName) {
                    backend.removeMediaItem(index)
                    if (!current()) return
                }
            }
            adoptRuntimeWindow(prepared)
            loadedWindow = window
            _state.value = _state.value.copy(snapshot = saved, failure = null)
            onRetainedTrackBinding(previousBinding, SleepTimerTrackBinding(sessionId, window.generationId, currentId, cycle))
            if (!current()) return
            val preparation = beginContinuousPreparation(saved, window, token, initial = initialForResume, allowMissingSuccessor = true)
            if (!current()) return
            val target = if (preparation == null) window.copy(entries = window.entries.take(window.currentIndex + 1)) else window
            loadedWindow = target
            for ((index, entry) in target.entries.withIndex()) {
                if (!current()) return
                if (index > target.currentIndex && (continuousPreparation !== preparation || boundaryPaused || nativeBoundaryPaused != false)) return
                val found = (0 until backend.mediaItemCount).firstOrNull { backend.getMediaItemAt(it).mediaId == entry.queueEntryId }
                if (found == null) {
                    if (entry.queueEntryId == currentId) throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.SOURCE_CHANGED, currentId))
                    backend.addMediaItem(index, mediaItem(entry))
                } else if (found != index) backend.moveMediaItem(found, index)
                // add/move可同步AUTO；不能再用旧currentIndex、checkpoint或许可写回。
                if (!current()) return
            }
            for ((index, entry) in target.entries.withIndex()) {
                if (!current()) return
                val existing = backend.getMediaItemAt(index)
                val updated = mediaItem(entry).let { item ->
                    if (preparation == null && entry.queueEntryId == currentId)
                        item.buildUpon().setTag(QueueTailMarker(false)).build() else item
                }
                if (existing != updated) {
                    // 同id/URI原位更新tag与元数据；真实factory拒绝不能更新的child。
                    backend.replaceMediaItem(index, updated)
                    if (!current()) return
                }
            }
            backend.playerError?.let { throw it }
        } finally {
            applyingWindow = wasApplying
            if (retainedEndScope === endScope) retainedEndScope = null
            if (!isDetached && backendBound) {
                consumeRetainedNaturalEnd(endScope)
                noticePendingContinuousEnd()
            }
        }
        sessionBridge.logicalStateChanged()
        updateTicker()
        requestCheckpoint()
    }

    /** 仅retained补窗接收真实END理由；fresh/恢复/seek准备不保留旧结束。 */
    private fun captureRetainedNaturalEnd() {
        val scope = retainedEndScope ?: return
        if (!applyingWindow || isDetached || !backendBound || switchingBackend || !resumeIntent ||
            currentOperation !== scope.owner || scope.intent != intentRevision ||
            handledNaturalCycle == playbackCycle || manualEndCycle == playbackCycle) return
        val window = loadedWindow ?: return
        val item = backend.currentMediaItem ?: return
        val uri = item.localConfiguration?.uri?.toString() ?: return
        val handover = pendingContinuous
        val generationId: String
        val revision: Long
        if (handover != null) {
            // AUTO已合法发布的新incoming仍可在旧owner取消后结束，不能只绑入窗旧current。
            if (handover.boundary.backendGeneration != backendGeneration || handover.incomingCycle != playbackCycle ||
                item.mediaId != handover.incoming.queueEntryId || uri != handover.boundary.successorMediaUri ||
                !sameSource(window.current, handover.incoming) || window.generationId != handover.boundary.window.generationId ||
                window.queueRevision != handover.boundary.window.queueRevision) return
            generationId = handover.boundary.window.generationId
            revision = handover.boundary.window.queueRevision
        } else {
            val saved = _state.value.snapshot ?: return
            if (!scope.owner.isActive || !sameSource(window.current, scope.window.current) || item.mediaId != scope.window.current.queueEntryId ||
                uri != loadedResources[item.mediaId]?.mediaUri?.toString() || saved.currentEntryId != item.mediaId ||
                saved.generationId != scope.window.generationId || saved.queueRevision != scope.window.queueRevision) return
            generationId = scope.window.generationId
            revision = scope.window.queueRevision
        }
        // 同一结束保留首次真实钟；至多一条，合法新AUTO的较新轮次可取代旧记录。
        if (scope.receipt?.cycle?.let { it >= playbackCycle } == true) return
        scope.receipt = RetainedEndReceipt(item.mediaId, uri, backendGeneration, playbackCycle, intentRevision,
            generationId, revision, backend.currentPosition.coerceAtLeast(0L), handover)
    }

    private fun consumeRetainedNaturalEnd(scope: RetainedEndScope?) {
        val ownedScope = scope ?: return
        val receipt = ownedScope.receipt ?: return
        if (applyingWindow || isDetached || !backendBound || switchingBackend || !resumeIntent || backend.playWhenReady ||
            currentOperation !== ownedScope.owner || ownedScope.intent != intentRevision || receipt.intent != intentRevision ||
            receipt.backendGeneration != backendGeneration || receipt.cycle != playbackCycle ||
            loadedWindow?.generationId != receipt.generationId || loadedWindow?.queueRevision != receipt.queueRevision ||
            backend.currentMediaItem?.mediaId != receipt.entryId || backend.currentMediaItem?.localConfiguration?.uri?.toString() != receipt.mediaUri) return
        val handover = receipt.handover
        if (handover != null) {
            if (pendingContinuous !== handover || handover.incomingCycle != receipt.cycle ||
                handover.incoming.queueEntryId != receipt.entryId || handover.boundary.successorMediaUri != receipt.mediaUri) return
        } else {
            val saved = _state.value.snapshot ?: return
            if (!ownedScope.owner.isActive || pendingContinuous != null || saved.currentEntryId != receipt.entryId ||
                saved.generationId != receipt.generationId || saved.queueRevision != receipt.queueRevision) return
        }
        naturalEnd(receipt.positionMs)
    }

    private fun rememberFinalizedRollingStream(positionMs: Long? = null) {
        if (!operations.supportsRollingQueueTail || isDetached || !backendBound ||
            backend.playbackState !in listOf(Player.STATE_READY, Player.STATE_BUFFERING)) return
        val window = loadedWindow ?: return
        val current = window.current
        val item = backend.currentMediaItem ?: return
        if (current.trackKey.providerId != TrackKey.LOCAL_PROVIDER || current.queueEntryId != item.mediaId ||
            _state.value.snapshot?.currentEntryId != current.queueEntryId && pendingContinuous?.incoming?.queueEntryId != current.queueEntryId) return
        val uri = item.localConfiguration?.uri?.toString() ?: return
        val existing = finalizedRollingStream
        if (existing != null && existing.backendGeneration == backendGeneration && existing.playbackCycle == playbackCycle &&
            sameSource(existing.entry, current) && existing.mediaUri == uri) {
            // 普通重复收回不采纳flush后的masking clock；显式暂停的关闭前捕获可更新。
            if (positionMs != null) existing.positionMs = positionMs.coerceAtLeast(0L)
            return
        }
        finalizedRollingStream = FinalizedRollingStream(current, uri, backendGeneration, playbackCycle,
            (positionMs ?: backend.currentPosition).coerceAtLeast(0L))
    }

    private fun finalizedRollingStreamMatches(allowSwitching: Boolean = false): Boolean {
        val frozen = finalizedRollingStream ?: return false
        val current = loadedWindow?.current ?: return false
        return operations.supportsRollingQueueTail && !isDetached && backendBound && (allowSwitching || !switchingBackend) &&
            backendGeneration == frozen.backendGeneration && playbackCycle == frozen.playbackCycle &&
            sameSource(current, frozen.entry) && backend.currentMediaItem?.mediaId == frozen.entry.queueEntryId &&
            backend.currentMediaItem?.localConfiguration?.uri?.toString() == frozen.mediaUri
    }

    private fun capturedBackendPosition(): Long {
        val frozen = finalizedRollingStream
        return if (frozen != null && !backend.playWhenReady && backend.playbackState != Player.STATE_ENDED &&
            finalizedRollingStreamMatches(allowSwitching = true)) frozen.positionMs else backend.currentPosition.coerceAtLeast(0L)
    }

    private fun captureFinalizedRollingPosition(positionMs: Long) {
        if (finalizedRollingStreamMatches(allowSwitching = true)) finalizedRollingStream?.positionMs = positionMs.coerceAtLeast(0L)
    }

    private fun queueContinuousResume(request: ContinuousHandover, requestedIntent: Long) {
        // 复用唯一普通命令槽。取消中的CAS仍先结清，成功后才执行这个显式恢复。
        pendingCommand = {
            action { token ->
                if (token != requestedIntent + 1L || request.explicitResumeIntent != requestedIntent || !request.accepted ||
                    !resumeIntent || request.incomingEnded || backend.playbackState == Player.STATE_ENDED ||
                    backendGeneration != request.boundary.backendGeneration || playbackCycle != request.incomingCycle ||
                    backend.currentMediaItem?.mediaId != request.incoming.queueEntryId ||
                    backend.currentMediaItem?.localConfiguration?.uri?.toString() != request.boundary.successorMediaUri) return@action
                if (!resumeFinalizedRollingStream(token) && token == intentRevision && pendingContinuous == null) authorizePlay()
                updateTicker()
            }
        }
    }

    /** hard-finalize后的显式恢复只reset同一位置；自然连续边界从不进入此路径。 */
    private suspend fun resumeFinalizedRollingStream(token: Long): Boolean {
        if (!finalizedRollingStreamMatches() || backend.playWhenReady) return false
        val frozen = requireNotNull(finalizedRollingStream)
        val currentId = frozen.entry.queueEntryId
        val position = frozen.positionMs
        fun current(): Boolean = finalizedRollingStreamMatches() && token == intentRevision && resumeIntent &&
            backendGeneration == frozen.backendGeneration && playbackCycle == frozen.playbackCycle &&
            backend.currentMediaItem?.mediaId == currentId && backend.currentMediaItem?.localConfiguration?.uri?.toString() == frozen.mediaUri &&
            pendingContinuous == null && backend.playerError == null
        if (!current()) return true
        val saved = activeSnapshot()
        if (saved.currentEntryId != currentId) throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.CONFLICT, currentId))
        val window = queueStore.window(saved.generationId, currentId)
        val prepared = prepareWindow(window, retainCurrent = true)
        currentCoroutineContext().ensureActive()
        if (!current()) return true
        loadedResources[currentId]?.checkCurrent()
        if (frozen.entry.cueReference != null) checkCueBinding(frozen.entry)
        currentCoroutineContext().ensureActive()
        if (!current()) return true
        // 有界provider核验不是后端prepare；新许可/尾标记在当前reset前就绪。
        applyRollingKeptWindow(saved, prepared, initialForResume = true)
        currentCoroutineContext().ensureActive()
        if (!current()) return true
        sessionBridge.positionSourceChanging()
        try {
            operations.seekAndAwait(currentId, position)
            currentCoroutineContext().ensureActive()
            if (!current()) return true
            loadedResources[currentId]?.checkCurrent()
            finalizedRollingStream = null
            if (backend.duration > 0L && backend.duration != C.TIME_UNSET && backend.currentPosition >= backend.duration) {
                manualEndCycle = playbackCycle
                resumeIntent = false
                pauseForWindowChange()
                return true
            }
            authorizePlay()
        } finally {
            if (!isDetached && backendBound) sessionBridge.positionSourceChanged()
        }
        return true
    }

    private fun validateSeekBinding(binding: QueueSeekBinding) {
        if (binding.sessionId != sessionId || binding.backendGeneration != backendGeneration ||
            binding.playbackCycle != playbackCycle || isDetached || switchingBackend || !backendBound) {
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
        refreshSeekState()
        if (seekOwner == null || activeSeekBinding != binding) {
            throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.CONFLICT, binding.queueEntryId))
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

    /** 已接纳资源继续保留；后续CUE元数据变化仍可取消其使用。 */
    private suspend fun applyPreparedWindow(
        saved: QueueSnapshot,
        prepared: PreparedPlaybackWindow,
        positionMs: Long,
        explicitPlay: Boolean,
        token: Long,
        newRound: Boolean = true,
        beforeHandover: suspend () -> Boolean = { true },
    ) {
        currentCoroutineContext().ensureActive()
        if (isDetached) return
        val window = prepared.window
        prepared.resources.values.forEach(RuntimePlaybackResource::checkCurrent)
        val wanted = window.entries
        val current = window.current
        val targetIndex = window.currentIndex
        val previousEntries = loadedWindow?.entries?.associateBy { it.queueEntryId }.orEmpty()
        fun matches(existing: MediaItem, entry: QueueEntry): Boolean {
            val previous = previousEntries[entry.queueEntryId] ?: return false
            return existing.mediaId == entry.queueEntryId && previous.trackKey == entry.trackKey &&
                previous.location == entry.location && previous.cueReference == entry.cueReference && previous.displayName == entry.displayName &&
                existing.clippingConfiguration == cuePlaybackClipping(entry.cueReference, backend is SystemMediaPlayerAdapter) &&
                existing.localConfiguration?.uri == prepared.resources[entry.queueEntryId]?.mediaUri
        }
        applyingWindow = true
        try {
            sessionBridge.positionSourceChanging()
            backend.pause()
            // pause 可 flush 已发生的 AUTO 或随后手动暂停；不能再用旧当前项覆写实际后继。
            currentCoroutineContext().ensureActive()
            val oldCurrent = backend.currentMediaItem?.mediaId
            val existingIndex = (0 until backend.mediaItemCount).firstOrNull { index ->
                matches(backend.getMediaItemAt(index), current)
            }
            // Pause can flush synchronous callbacks. Check the timer after those callbacks,
            // immediately before replacing the old loaded traversal, including a repeated item.
            if (!beforeHandover()) return
            prepared.resources.values.forEach(RuntimePlaybackResource::checkCurrent)
            adoptRuntimeWindow(prepared)
            finalizedRollingStream = null
            loadedWindow = window.copy(entries = wanted, currentIndex = targetIndex)
            _state.value = _state.value.copy(snapshot = saved, failure = null,
                resourceStatus = prepared.resources[current.queueEntryId]?.status)
            loadingOnlineForPlay = explicitPlay && prepared.resources[current.queueEntryId]?.isOnline == true
            updateStreamWatchdog()
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
            if (operations.supportsRollingQueueTail) {
                val markerGeneration = backendGeneration
                val markerCycle = playbackCycle
                for ((index, entry) in wanted.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    if (isDetached || !backendBound || markerGeneration != backendGeneration || markerCycle != playbackCycle ||
                        token != intentRevision || loadedWindow?.generationId != window.generationId ||
                        loadedWindow?.queueRevision != window.queueRevision || backend.currentMediaItem?.mediaId != current.queueEntryId ||
                        backend.currentMediaItem?.localConfiguration?.uri != loadedResources[current.queueEntryId]?.mediaUri ||
                        index !in 0 until backend.mediaItemCount) return
                    val existing = backend.getMediaItemAt(index)
                    if (existing.mediaId != entry.queueEntryId || existing.localConfiguration?.uri != loadedResources[entry.queueEntryId]?.mediaUri) return
                    val marker = mediaItem(entry).localConfiguration?.tag as? QueueTailMarker ?: continue
                    // 普通收回会关闭预加载尾标记；复用同source安装须在再次解码前恢复frontier。
                    if (existing.localConfiguration?.tag != marker) backend.replaceMediaItem(index, existing.buildUpon().setTag(marker).build())
                }
                currentCoroutineContext().ensureActive()
                if (isDetached || !backendBound || markerGeneration != backendGeneration || markerCycle != playbackCycle ||
                    token != intentRevision || loadedWindow?.generationId != window.generationId ||
                    loadedWindow?.queueRevision != window.queueRevision || backend.currentMediaItem?.mediaId != current.queueEntryId ||
                    backend.currentMediaItem?.localConfiguration?.uri != loadedResources[current.queueEntryId]?.mediaUri) return
            }
            // 开放解码前建立同一轮次许可；READY后的全局门不能解除已sealed的stream。
            if (explicitPlay && token == intentRevision && operations.supportsRollingQueueTail) {
                beginContinuousPreparation(saved, loadedWindow ?: return, token, initial = true)
                if (pendingContinuous != null || backend.currentMediaItem?.mediaId != current.queueEntryId) return
                currentCoroutineContext().ensureActive()
            }
            operations.prepareAndAwait(current.queueEntryId)
            currentCoroutineContext().ensureActive()
            if (isDetached || !backendBound || backend.currentMediaItem?.mediaId != current.queueEntryId) return
            sessionBridge.positionSourceChanging()
            operations.seekAndAwait(current.queueEntryId, positionMs)
            currentCoroutineContext().ensureActive()
            if (isDetached || !backendBound) return
            if (token == intentRevision && backend.currentMediaItem?.mediaId == current.queueEntryId) captureFinalizedRollingPosition(backend.currentPosition)
            sessionBridge.positionSourceChanged()
            backend.playerError?.let { throw it }
            val atEnd = backend.duration > 0L && backend.duration != C.TIME_UNSET && backend.currentPosition >= backend.duration
            if (atEnd) {
                manualEndCycle = playbackCycle
                pauseContinuousBoundary()
            }
            if (current.cueReference != null) checkCueBinding(current)
            currentCoroutineContext().ensureActive()
            prepared.resources[current.queueEntryId]?.checkCurrent()
            resumeIntent = explicitPlay && token == intentRevision && !atEnd
            if (resumeIntent) authorizePlay()
            // setter/play可同步交接；该旧安装栈不再修改新后继的发声意图。
            if (pendingContinuous != null || backend.currentMediaItem?.mediaId != current.queueEntryId) return
            resumeIntent = resumeIntent && backend.playWhenReady
            if (backend.isPlaying) resetRecoveryBudget()
        } catch (error: CancellationException) {
            if (pendingContinuous == null || token != intentRevision) {
                resumeIntent = false
                if (!isDetached && backendBound) pauseForWindowChange()
            }
            throw error
        } catch (error: Exception) {
            resumeIntent = false
            if (!isDetached && backendBound) pauseForWindowChange()
            throw error
        } finally {
            applyingWindow = false
            loadingOnlineForPlay = false
            if (!isDetached && backendBound) {
                sessionBridge.positionSourceChanged()
                noticePendingContinuousEnd()
            }
        }
        updateTicker()
        requestCheckpoint()
    }

    private fun pauseForWindowChange() {
        if (isDetached || !backendBound) return
        if (backend.playWhenReady) rememberFinalizedRollingStream(backend.currentPosition)
        pauseContinuousBoundary()
        val wasApplying = applyingWindow
        applyingWindow = true
        try {
            // Media3 1.8 的个别回调在 setPlayWhenReady 内 flush；不能取消自己的切换。
            backend.pause()
            pendingContinuous?.let { continuous ->
                if (!isDetached && backendBound && backendGeneration == continuous.boundary.backendGeneration &&
                    backend.currentMediaItem?.mediaId == continuous.incoming.queueEntryId) {
                    continuous.incomingPositionMs = capturedBackendPosition()
                }
            }
        } finally {
            applyingWindow = wasApplying
        }
    }

    private fun clearBackend() {
        if (isDetached || !backendBound) return
        applyingWindow = true
        try {
            pauseContinuousBoundary()
            continuousBoundary = null
            resumeIntent = false
            loadedWindow = null
            finalizedRollingStream = null
            retainedEndScope = null
            releaseRuntimeWindows()
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

    /** 仅主线程读取已核验窗口；睡眠/设置变化直接收回下一个边界，不访问provider。 */
    internal fun refreshContinuousBoundary() {
        if (isDetached || !backendBound) return
        continuousTailHold?.let { hold ->
            val issue = tailHoldIssue(hold)
            if (issue == null) {
                boundaryPaused = true
                _state.value = _state.value.copy(gapless = GaplessPlaybackState(
                    issue = if (pendingContinuous === hold.request) GaplessPlaybackIssue.HANDOVER_PENDING else GaplessPlaybackIssue.PREPARING))
            } else pauseContinuousBoundary(issue)
            return
        }
        val preparation = continuousPreparation
        if (preparation != null && !ownsContinuousPreparation(preparation)) {
            pauseContinuousBoundary()
            return
        }
        val saved = _state.value.snapshot
        val window = loadedWindow
        val issue = continuousBoundaryIssue(saved, window,
            allowPreparation = preparation != null,
            allowPaused = preparation?.initial == true,
            allowMissingSuccessor = preparation?.allowMissingSuccessor == true)
        if (issue != null) {
            pauseContinuousBoundary(issue)
            return
        }
        val authorizedBoundary = preparation?.boundary ?: continuousBoundaryFor(requireNotNull(saved), requireNotNull(window))
        continuousBoundary = authorizedBoundary
        boundaryPaused = false
        setNativeBoundaryPaused(false)
        // setter可同步送达AUTO、暂停或偏好回调；旧栈不得覆盖新身份和收回状态。
        if (isDetached || !backendBound || boundaryPaused || nativeBoundaryPaused != false || pendingContinuous != null ||
            continuousBoundary !== authorizedBoundary || backendGeneration != authorizedBoundary.backendGeneration) return
        _state.value = _state.value.copy(gapless = if (preparation == null)
            GaplessPlaybackState(available = true, issue = null) else GaplessPlaybackState(issue = GaplessPlaybackIssue.PREPARING))
    }

    private fun gaplessRequested(): Boolean = when (val value = preferences.state.value) {
        is PreferencesState.Ready -> value.preferences.gaplessPlayback
        is PreferencesState.Unavailable -> value.lastKnown?.gaplessPlayback ?: false
        PreferencesState.Loading -> false
    }

    /** stage只放宽同owner安装中的READY/锁条件，初次准备另允许尚未最终发声的显式意图。 */
    private fun continuousBoundaryIssue(
        saved: QueueSnapshot?,
        window: QueueWindow?,
        allowPreparation: Boolean = false,
        allowPaused: Boolean = false,
        allowMissingSuccessor: Boolean = false,
    ): GaplessPlaybackIssue? {
        val next = window?.let { it.entries.getOrNull(it.currentIndex + 1) }
        val issue = when {
            !gaplessRequested() -> GaplessPlaybackIssue.PREFERENCE_DISABLED
            !operations.supportsContinuousPlayback -> GaplessPlaybackIssue.BACKEND_UNSUPPORTED
            !mayContinuousPlayback() -> GaplessPlaybackIssue.TIMER_ACTIVE
            pendingContinuous != null -> GaplessPlaybackIssue.HANDOVER_PENDING
            switchingBackend || _state.value.failure != null || backend.playerError != null || !mayBeginContinuousRound() ||
                !allowPreparation && (applyingWindow || actions.isLocked || _state.value.preparing || backend.playbackState != Player.STATE_READY) ||
                !allowPaused && (!resumeIntent || !backend.playWhenReady) -> GaplessPlaybackIssue.PREPARING
            saved == null || window == null || saved.currentEntryId != window.current.queueEntryId ||
                backend.currentMediaItem?.mediaId != window.current.queueEntryId ||
                saved.generationId != window.generationId || saved.queueRevision != window.queueRevision -> GaplessPlaybackIssue.PREPARING
            next == null || next.playbackOrder != window.current.playbackOrder + 1L -> GaplessPlaybackIssue.QUEUE_BOUNDARY
            naturalQueueAdvance(requireNotNull(saved.currentPlaybackOrder), saved.entryCount, saved.mode,
                saved.checkpoint.completedCycles).targetOrder != next.playbackOrder -> GaplessPlaybackIssue.MODE_REQUIRES_SETTLEMENT
            window.current.trackKey.providerId != TrackKey.LOCAL_PROVIDER ||
                next.trackKey.providerId != TrackKey.LOCAL_PROVIDER -> GaplessPlaybackIssue.SOURCE_UNSUPPORTED
            loadedResources[window.current.queueEntryId] == null || loadedResources[next.queueEntryId] == null -> GaplessPlaybackIssue.SUCCESSOR_UNAVAILABLE
            else -> null
        }
        if (issue != null) return issue
        val current = requireNotNull(window).current
        val successor = requireNotNull(next)
        val currentResource = requireNotNull(loadedResources[current.queueEntryId])
        val nextResource = requireNotNull(loadedResources[successor.queueEntryId])
        if (backend.currentMediaItem?.localConfiguration?.uri != currentResource.mediaUri) return GaplessPlaybackIssue.SUCCESSOR_UNAVAILABLE
        val nextIndex = backend.currentMediaItemIndex + 1
        if (nextIndex in 0 until backend.mediaItemCount) {
            val physical = backend.getMediaItemAt(nextIndex)
            if (physical.mediaId != successor.queueEntryId || physical.localConfiguration?.uri != nextResource.mediaUri) {
                return GaplessPlaybackIssue.SUCCESSOR_UNAVAILABLE
            }
        } else if (!allowMissingSuccessor) return GaplessPlaybackIssue.SUCCESSOR_UNAVAILABLE
        return try {
            currentResource.checkCurrent()
            nextResource.checkCurrent()
            null
        } catch (_: QueuePlaybackException) { GaplessPlaybackIssue.SUCCESSOR_UNAVAILABLE }
    }

    private fun continuousBoundaryFor(saved: QueueSnapshot, window: QueueWindow): ContinuousBoundary {
        val successor = window.entries[window.currentIndex + 1]
        return ContinuousBoundary(window, successor, playbackCycle, backendGeneration, saved.checkpoint.completedCycles,
            requireNotNull(loadedResources[window.current.queueEntryId]).mediaUri.toString(),
            requireNotNull(loadedResources[successor.queueEntryId]).mediaUri.toString())
    }

    private fun ownsContinuousPreparation(preparation: ContinuousPreparation): Boolean {
        val window = loadedWindow ?: return false
        return operations.supportsRollingQueueTail && preparation.owner === currentOperation && preparation.owner.isActive &&
            preparation.token == intentRevision && preparation.boundary.backendGeneration == backendGeneration &&
            preparation.boundary.endingCycle == playbackCycle && pendingContinuous == null &&
            window.generationId == preparation.boundary.window.generationId && window.queueRevision == preparation.boundary.window.queueRevision &&
            sameSource(window.current, preparation.boundary.window.current) &&
            backend.currentMediaItem?.mediaId == window.current.queueEntryId
    }

    private fun beginContinuousPreparation(
        saved: QueueSnapshot,
        window: QueueWindow,
        token: Long,
        initial: Boolean,
        allowMissingSuccessor: Boolean = false,
    ): ContinuousPreparation? {
        val owner = currentOperation
        if (!operations.supportsRollingQueueTail || owner == null || !owner.isActive || token != intentRevision) return null
        val issue = continuousBoundaryIssue(saved, window, allowPreparation = true,
            allowPaused = initial, allowMissingSuccessor = allowMissingSuccessor)
        if (issue != null) {
            pauseContinuousBoundary(issue)
            return null
        }
        val boundary = continuousBoundaryFor(saved, window)
        val preparation = ContinuousPreparation(boundary, owner, token, initial, allowMissingSuccessor, continuousTailHold?.request)
        // 许可和完整运行时窗口先发布，append的同步AUTO才有精确后继身份。
        continuousPreparation = preparation
        continuousBoundary = boundary
        retireTailHold(finalize = false)
        boundaryPaused = false
        setNativeBoundaryPaused(false)
        if (!ownsContinuousPreparation(preparation) || continuousPreparation !== preparation || continuousBoundary !== boundary ||
            boundaryPaused || nativeBoundaryPaused != false) return null
        _state.value = _state.value.copy(gapless = GaplessPlaybackState(issue = GaplessPlaybackIssue.PREPARING))
        return preparation
    }

    /** 逻辑门与native门分开；只有精确无future的hold可保持native开放。 */
    private fun pauseContinuousBoundary(
        issue: GaplessPlaybackIssue = GaplessPlaybackIssue.PREPARING,
        retainTailHold: Boolean = false,
    ) {
        if (retainTailHold && continuousTailHold?.let { tailHoldIssue(it) == null } == true) {
            boundaryPaused = true
            _state.value = _state.value.copy(gapless = GaplessPlaybackState(issue = issue))
            return
        }
        continuousPreparation?.handover?.let { request ->
            captureIncomingPosition(request)
            request.tailFinalized = true
        }
        continuousPreparation = null
        retireTailHold(finalize = true)
        boundaryPaused = true
        val bound = backend
        val generation = backendGeneration
        val cycle = playbackCycle
        val token = intentRevision
        val queueGeneration = loadedWindow?.generationId
        val queueRevision = loadedWindow?.queueRevision
        val currentId = if (!isDetached && backendBound) backend.currentMediaItem?.mediaId else null
        val currentUri = if (!isDetached && backendBound) backend.currentMediaItem?.localConfiguration?.uri else null
        fun current(): Boolean = !isDetached && backendBound && backend === bound && backendGeneration == generation &&
            playbackCycle == cycle && intentRevision == token && loadedWindow?.generationId == queueGeneration &&
            loadedWindow?.queueRevision == queueRevision && backend.currentMediaItem?.mediaId == currentId &&
            backend.currentMediaItem?.localConfiguration?.uri == currentUri
        // 先发布收回；setter/replace的同步AUTO或新意图保留其更新后的状态。
        _state.value = _state.value.copy(gapless = GaplessPlaybackState(issue = issue))
        if (current()) {
            rememberFinalizedRollingStream()
            setNativeBoundaryPaused(true)
            if (!current()) return
            finalizeRollingTails()
        }
        // 最后许可仍保留至源退休，以识别已发生但迟到的合法AUTO。
    }

    private fun setNativeBoundaryPaused(pause: Boolean) {
        if (nativeBoundaryPaused == pause) return
        val bound = backend
        val generation = backendGeneration
        nativeBoundaryPaused = pause
        try { operations.setPauseAtBoundary(pause) }
        catch (error: Exception) {
            // 失败不能缓存为已关门；下一次普通停止必须重试真实setter。
            if (backend === bound && backendGeneration == generation) nativeBoundaryPaused = null
            throw error
        }
    }

    private fun finalizeRollingTails() {
        if (!operations.supportsRollingQueueTail || isDetached || !backendBound) return
        val bound = backend
        val generation = backendGeneration
        val cycle = playbackCycle
        val token = intentRevision
        val queueGeneration = loadedWindow?.generationId
        val queueRevision = loadedWindow?.queueRevision
        val currentItem = backend.currentMediaItem ?: return
        val candidates = listOf(backend.currentMediaItemIndex, backend.mediaItemCount - 1).distinct()
            .filter { it in 0 until backend.mediaItemCount }.map { it to backend.getMediaItemAt(it) }
        fun current(): Boolean = !isDetached && backendBound && backend === bound && backendGeneration == generation &&
            playbackCycle == cycle && intentRevision == token && loadedWindow?.generationId == queueGeneration &&
            loadedWindow?.queueRevision == queueRevision && backend.currentMediaItem?.mediaId == currentItem.mediaId &&
            backend.currentMediaItem?.localConfiguration?.uri == currentItem.localConfiguration?.uri
        // 当前和已预加载尾项均关闭；同一个物理项去重，不能留下准备取消后的dynamic尾部。
        for ((index, expected) in candidates) {
            if (!current() || index !in 0 until backend.mediaItemCount) return
            val item = backend.getMediaItemAt(index)
            if (item.mediaId != expected.mediaId || item.localConfiguration?.uri != expected.localConfiguration?.uri ||
                item.clippingConfiguration != expected.clippingConfiguration) return
            val marker = item.localConfiguration?.tag as? QueueTailMarker ?: continue
            if (marker.pendingContinuation) {
                backend.replaceMediaItem(index, item.buildUpon().setTag(QueueTailMarker(false)).build())
                if (!current()) return
            }
        }
    }

    private fun captureIncomingPosition(request: ContinuousHandover) {
        if (!isDetached && backendBound && backendGeneration == request.boundary.backendGeneration &&
            backend.currentMediaItem?.mediaId == request.incoming.queueEntryId &&
            backend.currentMediaItem?.localConfiguration?.uri?.toString() == request.boundary.successorMediaUri) {
            request.incomingPositionMs = capturedBackendPosition()
        }
    }

    private fun beginTailHold(request: ContinuousHandover) {
        if (!operations.supportsRollingQueueTail) return
        val hold = ContinuousTailHold(request, intentRevision, SystemClock.elapsedRealtime())
        if (tailHoldIssue(hold) != null) {
            request.tailFinalized = true
            return
        }
        continuousTailHold = hold
        val guard = serviceScope.launch(Dispatchers.Main.immediate, start = CoroutineStart.LAZY) {
            while (isActive && continuousTailHold === hold) {
                delay(TAIL_HOLD_POLL_MS)
                if (!isDetached && backendBound && continuousTailHold === hold) refreshContinuousBoundary()
            }
        }
        hold.guard = guard
        guard.start()
    }

    private fun tailHoldIssue(hold: ContinuousTailHold): GaplessPlaybackIssue? {
        val request = hold.request
        if (!gaplessRequested()) return GaplessPlaybackIssue.PREFERENCE_DISABLED
        if (!operations.supportsRollingQueueTail || !operations.supportsContinuousPlayback) return GaplessPlaybackIssue.BACKEND_UNSUPPORTED
        if (!mayContinuousPlayback()) return GaplessPlaybackIssue.TIMER_ACTIVE
        if (isDetached || !backendBound || switchingBackend || request.tailFinalized || hold.token != intentRevision ||
            _state.value.failure != null || backend.playerError != null || !resumeIntent || !backend.playWhenReady ||
            !mayKeepContinuousTail() || nativeBoundaryPaused != false ||
            backendGeneration != request.boundary.backendGeneration || playbackCycle != request.incomingCycle ||
            backend.currentMediaItem?.mediaId != request.incoming.queueEntryId ||
            backend.currentMediaItem?.localConfiguration?.uri?.toString() != request.boundary.successorMediaUri ||
            loadedWindow?.current?.let { sameSource(it, request.incoming) } != true ||
            backend.currentMediaItemIndex != backend.mediaItemCount - 1 ||
            (backend.currentMediaItem?.localConfiguration?.tag as? QueueTailMarker)?.pendingContinuation != true ||
            backend.playbackState !in listOf(Player.STATE_READY, Player.STATE_BUFFERING) ||
            hold.owner != null && (hold.owner !== currentOperation || hold.owner?.isActive != true) ||
            pendingContinuous !== request && (!request.accepted || hold.owner == null || hold.owner !== currentOperation)) {
            return GaplessPlaybackIssue.PREPARING
        }
        if (request.incomingEnded || SystemClock.elapsedRealtime() - hold.startedElapsedMs >= MAX_TAIL_HOLD_MS) {
            return GaplessPlaybackIssue.HANDOVER_PENDING
        }
        val duration = backend.duration
        if (duration != C.TIME_UNSET && duration > 0L && backend.contentPosition >= duration) {
            // 到真实source时间边界只恢复final语义；自然完成仍须后端EOS/ENDED，不能由clock生成。
            return GaplessPlaybackIssue.HANDOVER_PENDING
        }
        return try {
            requireNotNull(loadedResources[request.incoming.queueEntryId]).checkCurrent()
            null
        } catch (_: QueuePlaybackException) { GaplessPlaybackIssue.SUCCESSOR_UNAVAILABLE }
    }

    private fun retireTailHold(finalize: Boolean) {
        val hold = continuousTailHold ?: return
        continuousTailHold = null
        hold.guard?.cancel()
        if (finalize) {
            captureIncomingPosition(hold.request)
            hold.request.tailFinalized = true
        }
    }

    private fun noticePendingContinuousEnd() {
        if (operations.supportsRollingQueueTail && !isDetached && backendBound &&
            backend.playbackState == Player.STATE_ENDED && resumeIntent) naturalEnd()
    }

    /** 新权威选曲已提交；旧许可、hold和交接不能覆盖它或堵住后续命令。 */
    private fun retireContinuousTraversal() {
        continuousPreparation = null
        finalizedRollingStream = null
        retainedEndScope = null
        retireTailHold(finalize = false)
        continuousBoundary = null
        pendingContinuous = null
        continuousWriteFailed = false
    }

    /** AUTO 的前后实例和位置是实际过轨证据；seek/repeat/元数据事件不能造完成。 */
    private fun witnessContinuousHandover(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo) {
        val boundary = continuousBoundary ?: return
        val window = loadedWindow ?: return
        val incoming = boundary.successor
        if (pendingContinuous != null || boundary.backendGeneration != backendGeneration || boundary.endingCycle != playbackCycle ||
            window.generationId != boundary.window.generationId || window.queueRevision != boundary.window.queueRevision ||
            !sameSource(window.current, boundary.window.current) ||
            oldPosition.mediaItem?.mediaId != window.current.queueEntryId ||
            newPosition.mediaItem?.mediaId != incoming.queueEntryId ||
            oldPosition.mediaItem?.localConfiguration?.uri?.toString() != boundary.endingMediaUri ||
            newPosition.mediaItem?.localConfiguration?.uri?.toString() != boundary.successorMediaUri ||
            backend.currentMediaItem?.mediaId != incoming.queueEntryId ||
            oldPosition.adGroupIndex != C.INDEX_UNSET || newPosition.adGroupIndex != C.INDEX_UNSET) return
        val advance = naturalQueueAdvance(window.current.playbackOrder, window.totalCount,
            requireNotNull(_state.value.snapshot).mode, boundary.completedCycles)
        if (advance.targetOrder != incoming.playbackOrder) {
            pauseForInterruption()
            report(QueuePlaybackFailure(QueuePlaybackIssue.CONFLICT, incoming.queueEntryId))
            return
        }
        val handover = ContinuousHandover(boundary, incoming, Math.addExact(playbackCycle, 1L),
            newPosition.contentPositionMs.coerceAtLeast(0L), advance.completedCycles)
        pendingContinuous = handover
        continuousWriteFailed = false
        continuousBoundary = null
        continuousPreparation = null
        finalizedRollingStream = null
        retireTailHold(finalize = false)
        val incomingWindow = if (window.entries.any { sameSource(it, incoming) }) window else boundary.window
        val incomingIndex = incomingWindow.entries.indexOfFirst { sameSource(it, incoming) }
        check(incomingIndex >= 0)
        loadedWindow = incomingWindow.copy(currentIndex = incomingIndex)
        playbackCycle = handover.incomingCycle
        manualEndCycle = -1L
        checkpointEpoch += 1L
        invalidateSeekOwner()
        // 已过轨的 CAS 优先于尚未发布的编辑；其 NonCancellable 提交仍按真实 generation 冲突处理。
        currentOperation?.cancel()
        try {
            onContinuousRoundChanged(incoming, handover.incomingPositionMs)
        } catch (_: Exception) {
            resumeIntent = false
            pauseForWindowChange()
            report(QueuePlaybackFailure(QueuePlaybackIssue.OUTPUT_ERROR, incoming.queueEntryId))
        }
        // 精确物理尾部没有future；逻辑关门即可保流，普通停止仍立即finalize。
        beginTailHold(handover)
        pauseContinuousBoundary(GaplessPlaybackIssue.HANDOVER_PENDING, retainTailHold = true)
        if (backend.playbackState == Player.STATE_ENDED && resumeIntent) naturalEnd()
        startPendingCommands()
    }

    /**
     * 已实际过轨的短数据库事务必须结清，包括取消、切后端和 teardown。
     * 此段只消费捕获值，不调用 provider/后端或重新播放。失败保留 pending，显式重试。
     */
    private suspend fun settleContinuousHandover(request: ContinuousHandover): QueueSnapshot =
        withContext(NonCancellable) {
            checkpointWrites.withLock {
                val live = activeSnapshot()
                val ending = request.boundary.window
                var reboundIncoming: QueueEntry? = null
                if (request.accepted) {
                    if (live.generationId != request.acceptedGenerationId || live.queueRevision != request.acceptedQueueRevision ||
                        live.currentEntryId != request.incoming.queueEntryId || live.checkpoint.completedCycles != request.completedCycles) {
                        throw QueueStoreException(QueueStoreIssue.CONFLICT)
                    }
                } else {
                    if (live.currentEntryId != ending.current.queueEntryId) throw QueueStoreException(QueueStoreIssue.CONFLICT)
                    if (live.generationId != ending.generationId || live.queueRevision != ending.queueRevision) {
                        // 已提交的保留当前编辑可先于过轨 receipt 抵达。只重绑定两份相同源，不能覆盖手动选曲。
                        val retainedEnding = queueStore.window(live.generationId, requireNotNull(live.currentEntryId)).current
                        val retainedIncoming = try { queueStore.window(live.generationId, request.incoming.queueEntryId).current }
                        catch (failure: QueueStoreException) {
                            if (failure.issue == QueueStoreIssue.CONFLICT) {
                                val latest = activeSnapshot()
                                if (latest.generationId == live.generationId && latest.queueRevision == live.queueRevision &&
                                    latest.currentEntryId == live.currentEntryId) retireContinuousTraversal()
                            }
                            throw failure
                        }
                        if (!sameSource(retainedEnding, ending.current) || !sameSource(retainedIncoming, request.incoming)) {
                            retireContinuousTraversal()
                            throw QueueStoreException(QueueStoreIssue.CONFLICT)
                        }
                        reboundIncoming = retainedIncoming
                    } else if (live.checkpoint.completedCycles != request.boundary.completedCycles) {
                        throw QueueStoreException(QueueStoreIssue.CONFLICT)
                    }
                }
                var saved = live
                if (!request.accepted) {
                    saved = queueStore.saveCheckpoint(live.generationId, live.checkpointRevision,
                        QueueCheckpoint(request.incoming.queueEntryId, request.incomingPositionMs, request.completedCycles))
                    request.accepted = true
                    request.acceptedGenerationId = saved.generationId
                    request.acceptedQueueRevision = saved.queueRevision
                    checkpointEpoch += 1L
                    val currentWindow = loadedWindow
                    if (reboundIncoming != null && !isDetached && backendBound &&
                        backendGeneration == request.boundary.backendGeneration &&
                        currentWindow != null && sameSource(currentWindow.current, request.incoming)) {
                        val previousBinding = SleepTimerTrackBinding(sessionId, currentWindow.generationId,
                            request.incoming.queueEntryId, playbackCycle)
                        // 这里只更新同一实际当前项的逻辑绑定；相邻项待后续可取消 preflight 补齐。
                        loadedWindow = QueueWindow(saved.generationId, saved.queueRevision, saved.entryCount,
                            listOf(requireNotNull(reboundIncoming)), 0)
                        onRetainedTrackBinding(previousBinding, SleepTimerTrackBinding(sessionId, saved.generationId,
                            request.incoming.queueEntryId, playbackCycle))
                    }
                    publishSnapshot(saved)
                }
                // 先前 IO 期间可能已结束或暂停后继。至多补一次位置；随后普通合并 writer 继续。
                if (saved.positionMs != request.incomingPositionMs) {
                    saved = queueStore.saveCheckpoint(saved.generationId, saved.checkpointRevision,
                        QueueCheckpoint(request.incoming.queueEntryId, request.incomingPositionMs, request.completedCycles))
                }
                handledNaturalCycle = request.boundary.endingCycle
                publishSnapshot(saved)
                if (pendingContinuous === request) pendingContinuous = null
                continuousWriteFailed = false
                saved
            }
        }

    private suspend fun processContinuousHandover(request: ContinuousHandover) =
        action(clearFailure = false, continuousHandover = true) { token ->
            val saved = settleContinuousHandover(request)
            currentCoroutineContext().ensureActive()
            if (!mayExtendContinuousHandover(request, token)) return@action
            if (operations.supportsRollingQueueTail) {
                // 当前round已有捕获epoch；新epoch读取只阻止下一份许可，不能放行额外future。
                while (!mayBeginContinuousRound()) {
                    val hold = continuousTailHold
                    if (hold == null || hold.request !== request || tailHoldIssue(hold) != null) {
                        pauseContinuousBoundary()
                        return@action
                    }
                    delay(TAIL_HOLD_POLL_MS)
                    currentCoroutineContext().ensureActive()
                    if (!mayExtendContinuousHandover(request, token)) return@action
                }
            }
            // 只对已接纳且未到真实终态的当前项补窗；无pause/prepare/seek或第二播放器。
            val window = queueStore.window(saved.generationId, request.incoming.queueEntryId)
            val prepared = prepareWindow(window, retainCurrent = true)
            currentCoroutineContext().ensureActive()
            if (!mayExtendContinuousHandover(request, token)) return@action
            applyKeptWindow(saved, prepared)
            if (pendingContinuous == null && pendingNatural == null && backend.currentMediaItem?.mediaId == request.incoming.queueEntryId &&
                resumeIntent && !backend.playWhenReady && token == intentRevision && !request.tailFinalized) authorizePlay()
        }

    private fun mayExtendContinuousHandover(request: ContinuousHandover, token: Long): Boolean =
        !isDetached && backendBound && !switchingBackend && token == intentRevision &&
            backendGeneration == request.boundary.backendGeneration && playbackCycle == request.incomingCycle &&
            backend.currentMediaItem?.mediaId == request.incoming.queueEntryId && !request.incomingEnded &&
            backend.playbackState != Player.STATE_ENDED && pendingNatural == null && resumeIntent &&
            (!operations.supportsRollingQueueTail || !request.tailFinalized)

    private fun naturalEnd(confirmedEndPositionMs: Long? = null) {
        if (isDetached || !backendBound || switchingBackend || !resumeIntent || applyingWindow ||
            handledNaturalCycle == playbackCycle || manualEndCycle == playbackCycle) return
        val saved = _state.value.snapshot ?: return
        val continuous = pendingContinuous
        val entryId = if (continuous != null && continuous.incomingCycle == playbackCycle &&
            backend.currentMediaItem?.mediaId == continuous.incoming.queueEntryId) {
            // 下一段可能短于前一段的 CAS；独立保存其完成，不能按旧持久 ID 丢掉结束。
            continuous.incomingEnded = true
            continuous.incoming.queueEntryId
        } else {
            if (backend.currentMediaItem?.mediaId != saved.currentEntryId) return
            saved.currentEntryId ?: return
        }
        // 真实END_OF_MEDIA_ITEM也可保持READY；现有完成准入通过后用实际末尾刷新暂停捕获。
        val endingPosition = confirmedEndPositionMs ?: backend.currentPosition.coerceAtLeast(0L)
        captureFinalizedRollingPosition(endingPosition)
        if (continuous != null && continuous.incomingCycle == playbackCycle && entryId == continuous.incoming.queueEntryId) {
            continuous.incomingPositionMs = endingPosition
        }
        if (pendingNatural?.cycle != playbackCycle) pendingNatural = NaturalCompletion(entryId, playbackCycle, backendGeneration)
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
        val ending = sleepTimerTrackBinding()
        if (ending == null) {
            if (pendingNatural === request) pendingNatural = null
            return@action
        }
        val settlement = NaturalSettlement(request, ending, active.queueRevision,
            QueueCheckpoint(ending.queueEntryId, backend.currentPosition.coerceAtLeast(0L),
                completedCycleCount(active.checkpoint.completedCycles)))
        settlingNatural = settlement
        try {
            val timerBlocked = beforeNaturalAdvance(ending)
            val advance = naturalQueueAdvance(order, active.entryCount, active.mode, active.checkpoint.completedCycles)
            fun accepted() {
                handledNaturalCycle = request.cycle
                if (pendingNatural === request) pendingNatural = null
            }
            if (timerBlocked || !resumeIntent || advance.targetOrder == null) {
                val position = backend.currentPosition.coerceAtLeast(0L)
                val cycles = if (resumeIntent && !timerBlocked) advance.completedCycles else completedCycleCount(active.checkpoint.completedCycles)
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
                selectInternal(active.generationId, active.queueRevision, entry.queueEntryId, true, advance.completedCycles, token,
                    ::accepted, naturalSettlement = settlement)
            }
        } finally {
            settlingNatural = null
        }
    }

    private fun requestRecovery(failure: QueuePlaybackFailure, wantedPlay: Boolean) {
        if (wantedPlay && !isDetached && backendBound && !switchingBackend && isRecoverableResourceIssue(failure.issue) &&
            failure.entryId != null && failure.entryId == _state.value.snapshot?.currentEntryId) {
            resumeIntent = true
            pendingRecovery = RecoveryRequest(failure, playbackCycle, backendGeneration, failureBudget(failure))
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
            val current = queueStore.window(active.generationId, requireNotNull(active.currentEntryId))
            val canRefresh = current.current.trackKey.providerId == TrackKey.LOCAL_PROVIDER || request.budget?.mayRefreshNative() == true
            if (canRefresh) try {
                val prepared = prepareWindow(current, budget = request.budget)
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
        if (failure.issue == QueuePlaybackIssue.RESOURCE_EXPIRED) {
            // Exhausted/expired resolution budgets require an explicit new operation for this same item.
            resumeIntent = false
            pauseForWindowChange()
            report(failure)
            return@action
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
                            retireContinuousTraversal()
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
        resolutionAttempt = null
    }

    private fun isRecoverableResourceIssue(issue: QueuePlaybackIssue): Boolean = issue in listOf(
        QueuePlaybackIssue.SOURCE_UNSUPPORTED, QueuePlaybackIssue.LOCATION_MISSING, QueuePlaybackIssue.PERMISSION_DENIED,
        QueuePlaybackIssue.FILE_MISSING, QueuePlaybackIssue.SOURCE_DAMAGED, QueuePlaybackIssue.SOURCE_UNAVAILABLE,
        QueuePlaybackIssue.RESOURCE_UNAVAILABLE, QueuePlaybackIssue.RESOURCE_EXPIRED, QueuePlaybackIssue.LOCAL_ONLY_ONLINE_SKIPPED,
    )

    private fun updateTicker() {
        updateStreamWatchdog()
        if (isDetached || !backendBound || !backend.isPlaying) {
            ticker?.cancel()
            ticker = null
        } else if (ticker?.isActive != true) {
            ticker = serviceScope.launch(Dispatchers.Main.immediate) {
                while (isActive && !isDetached) {
                    delay(CHECKPOINT_INTERVAL_MS)
                    if (!isDetached && backendBound && backend.isPlaying) {
                        requestCheckpoint()
                        refreshContinuousBoundary()
                    }
                }
            }
        }
    }

    private fun adoptRuntimeWindow(prepared: PreparedPlaybackWindow) {
        if (!preparedWindows.remove(prepared)) throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.CONFLICT))
        val previous = loadedResources
        loadedResources = prepared.resources
        loadedMetadata = preparedMetadata.remove(prepared).orEmpty()
        pausedOnlineReference = null
        previous.values.forEach(RuntimePlaybackResource::close)
    }

    private fun releaseRuntimeWindows() {
        streamWatchdog?.cancel()
        streamWatchdog = null
        watchedMediaUri = null
        loadingOnlineForPlay = false
        pausedOnlineReference = null
        val previous = loadedResources
        loadedResources = emptyMap()
        loadedMetadata = emptyMap()
        previous.values.forEach(RuntimePlaybackResource::close)
        preparedWindows.toList().forEach(PreparedPlaybackWindow::close)
        preparedWindows.clear()
        preparedMetadata.clear()
        _state.value = _state.value.copy(resourceStatus = null)
    }

    /** Restoring a stable online reference builds metadata only; it cannot warm HTTP or an account client. */
    private fun restoreOnlineReference(saved: QueueSnapshot, window: QueueWindow, positionMs: Long = saved.positionMs, retainedMetadata: MediaMetadata? = null) {
        val entry = window.current
        val metadata = retainedMetadata ?: pausedOnlineReference?.mediaItem?.takeIf { it.mediaId == entry.queueEntryId }?.mediaMetadata
            ?: loadedMetadata[entry.queueEntryId] ?: MediaMetadata.Builder().setTitle(entry.displayName).build()
        clearBackend()
        // ExoPlayer cannot create a MediaSource for a URI-less item. The session facade owns this paused projection.
        pausedOnlineReference = QueuePausedOnlineReference(MediaItem.Builder().setMediaId(entry.queueEntryId)
            .setMediaMetadata(metadata).build(), positionMs.coerceAtLeast(0L), entry.durationMs)
        loadedWindow = window.copy(entries = listOf(entry), currentIndex = 0)
        _state.value = _state.value.copy(snapshot = saved, failure = referenceFailure(entry), resourceStatus = null)
        sessionBridge.logicalStateChanged()
    }

    internal fun sessionPausedOnlineReference(): QueuePausedOnlineReference? {
        if (isDetached || !backendBound) return null
        val reference = pausedOnlineReference ?: return null
        val saved = _state.value.snapshot ?: return null
        val window = loadedWindow ?: return null
        return reference.takeIf { window.generationId == saved.generationId && window.queueRevision == saved.queueRevision &&
            window.current.queueEntryId == saved.currentEntryId && it.mediaItem.mediaId == saved.currentEntryId }
    }

    /** Logout/revalidation never touches a local traversal or its persistent queue/library. */
    internal fun onAccountStateChanged() {
        assertMain()
        if (isDetached || !backendBound) return
        val current = loadedWindow?.current ?: return
        val resource = loadedResources[current.queueEntryId]?.takeIf { it.isOnline } ?: return
        try { resource.checkCurrent() } catch (failure: QueuePlaybackException) {
            if (_state.value.snapshot?.currentEntryId != current.queueEntryId) {
                // A local handover may already be committed while the old online item is still loaded.
                // Pause that old item without cancelling the accepted local command.
                pauseForWindowChange()
                return
            }
            onUserIntentChanged()
            val saved = _state.value.snapshot ?: return
            val window = loadedWindow ?: return
            pauseForInterruption()
            val position = backend.currentPosition.coerceAtLeast(0L)
            restoreOnlineReference(saved, window, position)
            requestCheckpoint()
            report(failure.failure)
        }
    }

    private fun failureBudget(failure: QueuePlaybackFailure): PlaybackResolutionBudget? =
        failure.entryId?.let { loadedResources[it]?.budget } ?: resolutionAttempt?.takeIf {
            it.entryId == failure.entryId && it.generationId == _state.value.snapshot?.generationId
        }?.budget

    /** This is a service audio watchdog, independent of the hidden page's zero visual tick contract. */
    private fun updateStreamWatchdog() {
        val entry = loadedWindow?.current
        val resource = entry?.let { loadedResources[it.queueEntryId] }
        val wanted = !isDetached && backendBound && resource?.isOnline == true &&
            (loadingOnlineForPlay || resumeIntent || backend.playWhenReady)
        val mediaUri = if (wanted) resource?.mediaUri?.toString() else null
        if (mediaUri == null) {
            streamWatchdog?.cancel()
            streamWatchdog = null
            watchedMediaUri = null
            return
        }
        if (streamWatchdog?.isActive == true && watchedMediaUri == mediaUri && watchedBackendGeneration == backendGeneration) return
        streamWatchdog?.cancel()
        watchedMediaUri = mediaUri
        watchedBackendGeneration = backendGeneration
        val generation = backendGeneration
        val entryId = requireNotNull(entry).queueEntryId
        streamWatchdog = serviceScope.launch(Dispatchers.Main.immediate) {
            var lastProgress = SystemClock.elapsedRealtime()
            var lastPosition = backend.currentPosition.coerceAtLeast(0L)
            while (isActive) {
                delay(1_000L)
                if (isDetached || !backendBound || backendGeneration != generation ||
                    loadedResources[entryId]?.mediaUri?.toString() != mediaUri ||
                    !(loadingOnlineForPlay || resumeIntent || backend.playWhenReady)) break
                val now = SystemClock.elapsedRealtime()
                val position = backend.currentPosition.coerceAtLeast(0L)
                if (backend.isPlaying && position > lastPosition) lastProgress = now
                lastPosition = position
                if (now < lastProgress || now - lastProgress >= 15_000L) {
                    onUserIntentChanged()
                    pauseForInterruption()
                    backend.stop()
                    report(QueuePlaybackFailure(QueuePlaybackIssue.STREAM_STALLED, entryId))
                    break
                }
            }
        }
    }

    private fun capture(): CapturedCheckpoint? {
        if (isDetached || !backendBound || applyingWindow) return null
        val saved = _state.value.snapshot ?: return null
        val reference = sessionPausedOnlineReference()
        if (reference != null) return CapturedCheckpoint(saved.generationId, saved.queueRevision, intentRevision, checkpointEpoch,
            QueueCheckpoint(reference.mediaItem.mediaId, reference.positionMs, saved.checkpoint.completedCycles))
        val entryId = backend.currentMediaItem?.mediaId ?: return null
        if (loadedWindow?.generationId != saved.generationId || saved.currentEntryId != entryId) return null
        return CapturedCheckpoint(saved.generationId, saved.queueRevision, intentRevision, checkpointEpoch, QueueCheckpoint(entryId, capturedBackendPosition(), saved.checkpoint.completedCycles))
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
                // Pause/prepare callbacks can repeat an already durable clock. Advancing its
                // revision would turn read-only previews and the next real CAS into conflicts.
                if (active.checkpoint == captured.checkpoint) continue
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
        refreshSeekState()
        if (!isDetached) sessionBridge.logicalStateChanged()
    }

    private fun launchCommand(preserveSeekOwner: Boolean = false, command: suspend () -> Unit) {
        if (isDetached || switchingBackend || !backendBound) return
        if (!preserveSeekOwner) invalidateSeekOwner()
        pauseContinuousBoundary()
        // 快速连续标准命令仅保留最近一个；自然完成和恢复各有一个独立槽位。
        pendingCommand = command
        startPendingCommands()
    }

    private fun startPendingCommands() {
        if (isDetached || switchingBackend || !backendBound || currentOperation != null || actions.isLocked || commandJob != null) return
        val continuous = pendingContinuous
        if (continuous != null && continuousWriteFailed) return
        val ordinary = if (continuous == null) pendingCommand else null
        val recovery = if (ordinary == null) pendingRecovery else null
        val natural = if (ordinary == null && recovery == null) pendingNatural else null
        val command: suspend () -> Unit = when {
            continuous != null -> { { processContinuousHandover(continuous) } }
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
                if (pendingContinuous === continuous) continuousWriteFailed = true
                if (continuous != null) {
                    resumeIntent = false
                    pauseForWindowChange()
                    requestCheckpoint()
                }
            } catch (_: QueuePlaybackException) {
                if (pendingRecovery === recovery) pendingRecovery = null
                if (pendingNatural === natural) pendingNatural = null
                if (pendingContinuous === continuous) continuousWriteFailed = true
                if (continuous != null) {
                    resumeIntent = false
                    pauseForWindowChange()
                    requestCheckpoint()
                }
            } finally {
                commandJob = null
                startPendingCommands()
            }
        }
        commandJob = job
        job.start()
    }

    private suspend fun <T> action(
        clearFailure: Boolean = true,
        preserveSeekOwner: Boolean = false,
        continuousHandover: Boolean = false,
        block: suspend (Long) -> T,
    ): T = withContext(Dispatchers.Main.immediate) {
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
        if (!continuousHandover && pendingContinuous != null) {
            actions.unlock()
            throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.BUSY))
        }
        pauseContinuousBoundary(
            issue = if (continuousHandover) GaplessPlaybackIssue.HANDOVER_PENDING else GaplessPlaybackIssue.PREPARING,
            retainTailHold = continuousHandover,
        )
        if (!continuousHandover && pendingContinuous != null) {
            // setter 可同步 flush 真实 AUTO；准入须在它返回后重核，再开始任何 IO/意图修改。
            actions.unlock()
            startPendingCommands()
            throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.BUSY))
        }
        if (preserveSeekOwner) seeking = true else invalidateSeekOwner()
        if (!continuousHandover) intentRevision += 1L
        val token = intentRevision
        val operation = currentCoroutineContext()[Job]
        currentOperation = operation
        if (continuousHandover) continuousTailHold?.owner = operation
        _state.value = _state.value.copy(preparing = true, failure = if (clearFailure) null else _state.value.failure)
        try {
            refreshSeekState()
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
            preparedWindows.toList().forEach(PreparedPlaybackWindow::close)
            preparedWindows.clear()
            preparedMetadata.clear()
            actions.unlock()
            if (preserveSeekOwner) seeking = false
            _state.value = _state.value.copy(preparing = false)
            // stage跨越安装finally，清锁后由普通许可接续；同步AUTO的新hold不归旧owner。
            if (continuousPreparation?.owner === operation) continuousPreparation = null
            if (continuousTailHold?.owner === operation && pendingContinuous !== continuousTailHold?.request) {
                retireTailHold(finalize = true)
            }
            if (currentOperation === operation) currentOperation = null
            refreshContinuousBoundary()
            refreshSeekState()
            try { if (!isDetached) sessionBridge.logicalStateChanged() }
            finally { startPendingCommands() }
        }
    }

    private fun invalidateIntent() {
        invalidateSeekOwner()
        pauseContinuousBoundary()
        intentRevision += 1L
        currentOperation?.cancel()
    }

    private fun authorizePlay(): Boolean {
        if (isDetached || !backendBound || switchingBackend) return false
        if (sessionPausedOnlineReference() != null) return false
        try { loadedWindow?.current?.queueEntryId?.let { loadedResources[it]?.checkCurrent() } }
        catch (error: QueuePlaybackException) {
            resumeIntent = false
            pauseForWindowChange()
            report(error.failure)
            return false
        }
        val allowed = requestPlay?.invoke() ?: run { backend.play(); backend.playWhenReady }
        resumeIntent = allowed && backend.playWhenReady
        if (!resumeIntent) {
            pauseForWindowChange()
            requestCheckpoint()
        }
        refreshContinuousBoundary()
        return resumeIntent
    }

    private fun completedCycleCount(value: Int): Int = if (value == Int.MAX_VALUE) Int.MAX_VALUE else value + 1

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
        invalidateSeekOwner()
        if (!isDetached) onFailure(failure)
    }

    private fun invalidateSeekOwner() {
        seekActionId = UUID.randomUUID().toString()
        seekOwner = null
        activeSeekBinding = null
        seekReceipt = null
        _seekState.value = QueueSeekSessionState()
    }

    /** 只读有界内存/Player字段；checkpoint和进度不属于身份，不能使一次拖动每tick过期。 */
    private fun refreshSeekState() {
        val identity = try { currentSeekIdentity() } catch (_: RuntimeException) { null }
        if (identity == null) {
            seekOwner = null
            activeSeekBinding = null
            seekReceipt = null
            _seekState.value = QueueSeekSessionState()
            return
        }
        val previous = seekOwner
        if (seeking && previous?.identity != identity) {
            seekOwner = null
            activeSeekBinding = null
            seekReceipt = null
            _seekState.value = QueueSeekSessionState()
            return
        }
        val owner = previous?.takeIf { it.identity == identity } ?: run {
            activeSeekBinding = null
            seekReceipt = null
            SeekOwner(UUID.randomUUID().toString(), identity).also { seekOwner = it }
        }
        _seekState.value = QueueSeekSessionState(owner.id, activeSeekBinding?.previewId)
    }

    private fun currentSeekIdentity(): SeekOwnerIdentity? {
        if (isDetached || !backendBound || switchingBackend || _state.value.failure != null) return null
        // An external action permanently revokes an in-flight seek's owner. Its late callbacks
        // cannot mint a replacement owner until that physical operation has left its action.
        val ownSeek = seeking && seekOwner != null
        if (seeking && !ownSeek || (_state.value.preparing || applyingWindow) && !ownSeek) return null
        val playbackState = backend.playbackState
        if (backend.playerError != null || playbackState !in listOf(Player.STATE_READY, Player.STATE_ENDED) &&
            !(ownSeek && playbackState == Player.STATE_BUFFERING)) return null
        if (!ownSeek && !backend.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return null
        val saved = _state.value.snapshot ?: return null
        val window = loadedWindow ?: return null
        val current = window.current
        if (saved.generationId != window.generationId || saved.queueRevision != window.queueRevision ||
            saved.currentEntryId != current.queueEntryId || backend.currentMediaItem?.mediaId != current.queueEntryId) return null
        val observedDuration = backend.duration
        // Some backends temporarily remove duration/seek availability during their own buffer.
        // Retain only the already verified duration; completion still checks the actual backend.
        val duration = if (ownSeek && playbackState == Player.STATE_BUFFERING && observedDuration == C.TIME_UNSET)
            requireNotNull(seekOwner).identity.durationMs else observedDuration
        if (duration == C.TIME_UNSET || duration <= 0L || playbackCycle < 0L) return null
        return SeekOwnerIdentity(sessionId, backendGeneration, saved.generationId, saved.queueRevision,
            current.queueEntryId, current.location?.locationId, current.location?.contentRevision ?: 0L,
            duration, playbackCycle, seekActionId, current.cueReference)
    }

    private fun referenceFailure(entry: QueueEntry): QueuePlaybackFailure? {
        if (entry.trackKey.providerId == "netease" && sourceResolver != null) {
            return if (entry.location != null || entry.accountId == null) QueuePlaybackFailure(QueuePlaybackIssue.RESOURCE_INVALID, entry.queueEntryId) else null
        }
        if (entry.trackKey.providerId != TrackKey.LOCAL_PROVIDER) return QueuePlaybackFailure(QueuePlaybackIssue.SOURCE_UNSUPPORTED, entry.queueEntryId)
        val location = entry.location ?: return QueuePlaybackFailure(QueuePlaybackIssue.LOCATION_MISSING, entry.queueEntryId)
        val uri = location.uri.toUri()
        return if (uri.scheme != "content" || uri.authority.isNullOrBlank()) QueuePlaybackFailure(QueuePlaybackIssue.SOURCE_UNAVAILABLE, entry.queueEntryId) else null
    }

    private fun mediaItem(entry: QueueEntry): MediaItem = MediaItem.Builder()
        .setMediaId(entry.queueEntryId)
        .setUri(loadedResources[entry.queueEntryId]?.mediaUri ?: error("Validated runtime resource required"))
        .setMediaMetadata(loadedMetadata[entry.queueEntryId] ?: MediaMetadata.Builder().setTitle(entry.displayName).build())
        .setClippingConfiguration(cuePlaybackClipping(entry.cueReference, backend is SystemMediaPlayerAdapter))
        .apply {
            if (operations.supportsRollingQueueTail && entry.trackKey.providerId == TrackKey.LOCAL_PROVIDER) {
                val window = loadedWindow
                setTag(QueueTailMarker(window != null && window.entries.lastOrNull()?.queueEntryId == entry.queueEntryId &&
                    entry.playbackOrder < window.totalCount - 1L))
            }
        }
        .build()

    private fun errorIssue(error: PlaybackException): QueuePlaybackIssue = mediaTransportIssue(error) ?: when (error.errorCode) {
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
    private data class SeekOwner(val id: String, val identity: SeekOwnerIdentity)
    private data class SeekOwnerIdentity(
        val sessionId: String,
        val backendGeneration: Long,
        val generationId: String,
        val queueRevision: Long,
        val queueEntryId: String,
        val locationId: String?,
        val contentRevision: Long,
        val durationMs: Long,
        val playbackCycle: Long,
        val actionId: String,
        val cueReference: io.github.onlinemo.meloxwatch.model.LocalCueReference? = null,
    )
    private data class NaturalCompletion(val entryId: String, val cycle: Long, val backendGeneration: Long)
    private data class ContinuousBoundary(
        val window: QueueWindow,
        val successor: QueueEntry,
        val endingCycle: Long,
        val backendGeneration: Long,
        val completedCycles: Int,
        val endingMediaUri: String,
        val successorMediaUri: String,
    )
    private data class ContinuousPreparation(
        val boundary: ContinuousBoundary,
        val owner: Job,
        val token: Long,
        val initial: Boolean,
        val allowMissingSuccessor: Boolean,
        val handover: ContinuousHandover?,
    )
    private class ContinuousTailHold(
        val request: ContinuousHandover,
        val token: Long,
        val startedElapsedMs: Long,
        var owner: Job? = null,
        var guard: Job? = null,
    )
    private data class FinalizedRollingStream(
        val entry: QueueEntry,
        val mediaUri: String,
        val backendGeneration: Long,
        val playbackCycle: Long,
        var positionMs: Long,
    )

    private class RetainedEndScope(val owner: Job, val intent: Long, val window: QueueWindow) {
        var receipt: RetainedEndReceipt? = null
    }
    private data class RetainedEndReceipt(
        val entryId: String,
        val mediaUri: String,
        val backendGeneration: Long,
        val cycle: Long,
        val intent: Long,
        val generationId: String,
        val queueRevision: Long,
        val positionMs: Long,
        val handover: ContinuousHandover?,
    )

    private class ContinuousHandover(
        val boundary: ContinuousBoundary,
        val incoming: QueueEntry,
        val incomingCycle: Long,
        var incomingPositionMs: Long,
        val completedCycles: Int,
        var incomingEnded: Boolean = false,
        var accepted: Boolean = false,
        var acceptedGenerationId: String? = null,
        var acceptedQueueRevision: Long? = null,
        var tailFinalized: Boolean = false,
        var explicitResumeIntent: Long? = null,
    )
    private data class NaturalSettlement(
        val request: NaturalCompletion,
        val ending: SleepTimerTrackBinding,
        val queueRevision: Long,
        val endingCheckpoint: QueueCheckpoint,
        var submittedCheckpoint: QueueCheckpoint? = null,
    )
    private data class RecoveryRequest(val failure: QueuePlaybackFailure, val cycle: Long, val backendGeneration: Long,
        val budget: PlaybackResolutionBudget?)
    private data class ResolutionAttempt(val generationId: String, val entryId: String, val budget: PlaybackResolutionBudget)

    companion object {
        private const val CHECKPOINT_INTERVAL_MS = 5000L
        private const val MAX_CONSECUTIVE_SKIPS = 3
        private const val TAIL_HOLD_POLL_MS = 50L
        private const val MAX_TAIL_HOLD_MS = 15_000L
    }
}

internal data class QueuePausedOnlineReference(val mediaItem: MediaItem, val positionMs: Long, val durationMs: Long?)
