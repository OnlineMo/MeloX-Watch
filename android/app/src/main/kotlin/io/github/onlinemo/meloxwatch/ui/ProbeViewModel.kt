package io.github.onlinemo.meloxwatch.ui

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionError
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.onlinemo.meloxwatch.MeloXApplication
import io.github.onlinemo.meloxwatch.data.ProbeAccess
import io.github.onlinemo.meloxwatch.data.ProbeAudio
import io.github.onlinemo.meloxwatch.data.ProbeDocument
import io.github.onlinemo.meloxwatch.data.ProbeFileIssue
import io.github.onlinemo.meloxwatch.data.ProbeFileResult
import io.github.onlinemo.meloxwatch.data.ProbeFiles
import io.github.onlinemo.meloxwatch.data.ProbeTreeSample
import io.github.onlinemo.meloxwatch.data.queue.QueueStoreException
import io.github.onlinemo.meloxwatch.data.listening.ListeningIssue
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesState
import io.github.onlinemo.meloxwatch.model.LocalAccess
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueMutationRequest
import io.github.onlinemo.meloxwatch.model.QueueSeekBinding
import io.github.onlinemo.meloxwatch.model.QueueSeekRequest
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import io.github.onlinemo.meloxwatch.model.PlaybackControlsPreferences
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.playback.PlaybackCommands
import io.github.onlinemo.meloxwatch.playback.PlaybackBackendState
import io.github.onlinemo.meloxwatch.playback.LibraryQueueCommand
import io.github.onlinemo.meloxwatch.playback.PlaylistQueueCommand
import io.github.onlinemo.meloxwatch.playback.ProbePlaybackService
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue
import io.github.onlinemo.meloxwatch.playback.QueueSeekSessionState
import io.github.onlinemo.meloxwatch.playback.input.RotaryAppliedStep
import io.github.onlinemo.meloxwatch.playback.input.RotaryHapticGate
import io.github.onlinemo.meloxwatch.playback.input.RotaryHapticState
import io.github.onlinemo.meloxwatch.playback.input.RotaryInputContext
import io.github.onlinemo.meloxwatch.playback.input.RotaryStep
import io.github.onlinemo.meloxwatch.ui.playback.PlaybackPageInput
import io.github.onlinemo.meloxwatch.ui.playback.PlaybackPageInputContext
import io.github.onlinemo.meloxwatch.ui.playback.SystemMediaVolume
import io.github.onlinemo.meloxwatch.playback.sleep.SleepTimerCommands
import io.github.onlinemo.meloxwatch.playback.sleep.SleepTimerEndMode
import io.github.onlinemo.meloxwatch.playback.sleep.SleepTimerSessionState
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistUiIssue
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

enum class ProbePage {
    HOME,
    MEDIA,
    TREE,
    INPUT,
    PLAYBACK,
    DEVICE,
    SETTINGS,
    QUEUE,
    LIBRARY,
    PLAYLISTS,
    SEEK,
    VOLUME,
    SLEEP_TIMER,
    SLEEP_TIMER_NOTIFICATIONS,
}

enum class ProbeNotice {
    READING,
    READY,
    CANCELLED,
    TIMEOUT,
    NO_DOCUMENT_PICKER,
    NO_TREE_PICKER,
    PERMISSION_DENIED,
    CONNECTION_FAILED,
    COMMAND_REJECTED,
    BACKGROUND_START_DENIED,
}

data class ProbePlaybackState(
    val isConnected: Boolean = false,
    val hasMedia: Boolean = false,
    val mediaId: String? = null,
    val title: String? = null,
    val isPlaying: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val positionMs: Long = 0L,
    val durationMs: Long? = null,
    val canSeek: Boolean = false,
    val hasError: Boolean = false,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val artist: String? = null,
)

enum class SleepTimerCommandIssue { CONNECTION, BUSY, CONFLICT, INVALID_INPUT, FAILED }

data class ProbeUiState(
    val page: ProbePage = ProbePage.HOME,
    val selectedAudio: ProbeAudio? = null,
    val selectedQueueEntry: QueueEntry? = null,
    val mediaSample: List<ProbeDocument> = emptyList(),
    val treeSample: ProbeTreeSample? = null,
    val isReading: Boolean = false,
    val notice: ProbeNotice? = null,
    val fileIssue: ProbeFileIssue? = null,
    val needsPermissionSettings: Boolean = false,
    val hasDocumentPicker: Boolean = false,
    val hasTreePicker: Boolean = false,
    val playback: ProbePlaybackState = ProbePlaybackState(),
    val playbackCommandPending: Boolean = false,
    val queueIssue: QueuePlaybackIssue? = null,
    val listeningIssue: ListeningIssue? = null,
    val backend: PlaybackBackendState? = null,
    val seekAuthority: QueueSeekSessionState? = null,
    val inputControls: PlaybackControlsPreferences = PlaybackControlsPreferences(),
    val controlsStateKey: String = "loading",
    val settingsRevision: Long = 0L,
    val inputFocusEpoch: String? = null,
    val sleepTimer: SleepTimerSessionState? = null,
    val sleepTimerElapsedMs: Long = 0L,
    val sleepTimerCommandPending: Boolean = false,
    val sleepTimerCommandIssue: SleepTimerCommandIssue? = null,
)

/**
 * 页面只拥有来源检查任务和 MediaController 连接，服务仍是唯一播放状态所有者。
 * Media3 的连接与命令始终在主 Looper；读文件在 ProbeFiles 的 IO 通道执行。
 */
@androidx.annotation.OptIn(UnstableApi::class)
class ProbeViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val files = ProbeFiles(application)
    private val queues = (application as MeloXApplication).queues
    private val restoredPage = ProbePage.entries.firstOrNull {
        it.name == (savedStateHandle.get<Any>(KEY_PAGE) as? String)
    } ?: ProbePage.HOME
    private val _uiState = MutableStateFlow(ProbeUiState(page = restoredPage))
    val uiState = _uiState.asStateFlow()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> mainHandler.post(command) }
    private var connectionFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var connectionGeneration = 0L
    private var fileGeneration = 0L
    private var fileJob: Job? = null
    private var progressJob: Job? = null
    private var isForeground = false
    private var playbackCommandJob: Job? = null
    private var playbackCommandGeneration = 0L
    private var pendingPlaylistCommand: PendingPlaylistCommand? = null
    private var sleepTimerCommandJob: Job? = null
    private var sleepTimerCommandGeneration = 0L
    private var windowFocused = false
    private var playbackInputFocused = false
    private var rotaryInputFocused = false
    private var playbackListHasPriority = true
    private var inputFocusToken = UUID.randomUUID().toString()
    private var hapticState = RotaryHapticState()
    val mediaVolume = SystemMediaVolume(application, ::canUseMediaVolume)
    val playbackInput = PlaybackPageInput(viewModelScope, ::playbackInputContext, ::acquireSeekBinding,
        ::isSeekBindingCurrent, ::dispatchSeek, ::applyRotaryVolume, ::onRotaryApplied)

    init {
        viewModelScope.launch {
            (application as MeloXApplication).preferences.state.collect { storage ->
                val saved = when (storage) {
                    is PreferencesState.Ready -> storage.preferences
                    is PreferencesState.Unavailable -> storage.lastKnown
                    PreferencesState.Loading -> null
                }
                val status = when (storage) {
                    is PreferencesState.Ready -> "ready:${storage.canWrite}:${storage.recovery}"
                    is PreferencesState.Unavailable -> "unavailable:${storage.failure}"
                    PreferencesState.Loading -> "loading"
                }
                val controls = saved?.controls ?: PlaybackControlsPreferences()
                _uiState.update { it.copy(inputControls = controls, settingsRevision = saved?.revision ?: 0L,
                    controlsStateKey = "$status:${saved?.revision}:$controls") }
                playbackInput.contextChanged()
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            capturePlayback()
        }
    }

    val audioPermission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun onForeground() {
        if (isForeground) return
        isForeground = true
        updateVolumeVisibility()
        refreshCapabilities()
        connectionGeneration += 1L
        val generation = connectionGeneration
        val token = SessionToken(getApplication(), ComponentName(getApplication(), ProbePlaybackService::class.java))
        val connectionListener = object : MediaController.Listener {
            override fun onDisconnected(disconnected: MediaController) {
                if (generation != connectionGeneration || !isForeground) return
                rejectPlaylistCommand(PlaylistUiIssue.SOURCE_UNAVAILABLE)
                controller = null
                playbackCommandGeneration += 1L
                playbackCommandJob?.cancel()
                progressJob?.cancel()
                progressJob = null
                playbackInput.invalidate()
                mediaVolume.onHidden()
                retireSleepTimerCommand()
                _uiState.update { state ->
                    state.copy(
                        playback = state.playback.copy(isConnected = false),
                        playbackCommandPending = false,
                        backend = null,
                        seekAuthority = null,
                        sleepTimer = null,
                        sleepTimerCommandPending = false,
                        notice = ProbeNotice.CONNECTION_FAILED,
                    )
                }
            }

            override fun onError(activeController: MediaController, sessionError: SessionError) {
                if (generation != connectionGeneration || !isForeground) return
                if (sessionError.message.startsWith("QUEUE_")) {
                    val issue = queueIssue(sessionError.message.removePrefix("QUEUE_"))
                    _uiState.update { state -> state.copy(queueIssue = issue) }
                    return
                }
                if (sessionError.message.startsWith("LISTENING_")) {
                    val issue = ListeningIssue.entries.firstOrNull { it.name == sessionError.message.removePrefix("LISTENING_") }
                        ?: ListeningIssue.STORAGE_UNAVAILABLE
                    _uiState.update { it.copy(listeningIssue = issue) }
                    return
                }
                val backgroundDenied = sessionError.code == SessionError.ERROR_INVALID_STATE &&
                    sessionError.message == "BACKGROUND_START_DENIED"
                _uiState.update { state ->
                    state.copy(
                        notice = if (backgroundDenied) ProbeNotice.BACKGROUND_START_DENIED
                        else ProbeNotice.COMMAND_REJECTED,
                    )
                }
            }

            override fun onExtrasChanged(activeController: MediaController, extras: Bundle) {
                if (generation != connectionGeneration || !isForeground || controller !== activeController) return
                capturePlayback()
                if (_uiState.value.page == ProbePage.SLEEP_TIMER) updateProgressSampling()
            }
        }
        val future = MediaController.Builder(getApplication(), token).setListener(connectionListener).buildAsync()
        connectionFuture = future
        future.addListener({
            if (generation != connectionGeneration || !isForeground || future.isCancelled) return@addListener
            try {
                val connected = future.get()
                controller = connected
                connected.addListener(playerListener)
                capturePlayback()
                updateProgressSampling()
                if (_uiState.value.page == ProbePage.SLEEP_TIMER) refreshSleepTimer()
            } catch (_: ExecutionException) {
                _uiState.update { state -> state.copy(notice = ProbeNotice.CONNECTION_FAILED) }
            } catch (_: CancellationException) {
                // releaseFuture 负责取消的连接；旧页面不消费其结果。
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                _uiState.update { state -> state.copy(notice = ProbeNotice.CONNECTION_FAILED) }
            }
        }, mainExecutor)
    }

    fun onBackground() {
        rejectPlaylistCommand(PlaylistUiIssue.BUSY)
        isForeground = false
        connectionGeneration += 1L
        playbackCommandGeneration += 1L
        playbackCommandJob?.cancel()
        progressJob?.cancel()
        progressJob = null
        playbackInput.invalidate()
        mediaVolume.onHidden()
        retireSleepTimerCommand()
        if (_uiState.value.isReading) cancelFileRead()
        controller?.removeListener(playerListener)
        controller = null
        connectionFuture?.let { future -> MediaController.releaseFuture(future) }
        connectionFuture = null
        _uiState.update { state ->
            state.copy(playback = state.playback.copy(isConnected = false), playbackCommandPending = false,
                backend = null, seekAuthority = null, sleepTimer = null, sleepTimerCommandPending = false)
        }
    }

    fun showPage(page: ProbePage) {
        val currentState = _uiState.value
        if (currentState.page != page) {
            retirePageInputFocus()
        }
        if (currentState.page != page && currentState.isReading) {
            // 离页使检查代次失效；不合作的provider晚到结果不能导航回播放页。
            cancelFileRead()
        }
        savedStateHandle[KEY_PAGE] = page.name
        _uiState.update { state -> state.copy(page = page) }
        playbackInput.contextChanged()
        updateVolumeVisibility()
        updateProgressSampling()
        if (page == ProbePage.SLEEP_TIMER && isForeground) refreshSleepTimer()
    }

    fun armSleepTimer(minutes: Int, mode: SleepTimerEndMode, expectedGeneration: String?, onAccepted: () -> Unit) {
        startSleepTimerCommand(SleepTimerCommands.arm,
            SleepTimerCommands.armArguments(minutes, mode, expectedGeneration), onAccepted = onAccepted)
    }

    /** Cancellation has its own slot and never waits for queue/provider work or an old timer receipt. */
    fun cancelSleepTimer(expectedGeneration: String?) {
        startSleepTimerCommand(SleepTimerCommands.cancel, SleepTimerCommands.expectedArguments(expectedGeneration),
            replacePending = true)
    }

    fun retrySleepTimerPersistence(expectedGeneration: String?) {
        startSleepTimerCommand(SleepTimerCommands.retrySave, SleepTimerCommands.expectedArguments(expectedGeneration))
    }

    fun refreshSleepTimer() {
        startSleepTimerCommand(SleepTimerCommands.refresh, SleepTimerCommands.refreshArguments())
    }

    private fun startSleepTimerCommand(command: SessionCommand, arguments: Bundle, replacePending: Boolean = false,
        onAccepted: () -> Unit = {}) {
        val active = controller
        if (!isForeground || active == null || !active.isConnected) {
            _uiState.update { it.copy(sleepTimerCommandIssue = SleepTimerCommandIssue.CONNECTION) }
            return
        }
        if (sleepTimerCommandJob?.isActive == true && !replacePending) {
            _uiState.update { it.copy(sleepTimerCommandIssue = SleepTimerCommandIssue.BUSY) }
            return
        }
        retireSleepTimerCommand()
        val generation = sleepTimerCommandGeneration
        _uiState.update { it.copy(sleepTimerCommandPending = true, sleepTimerCommandIssue = null) }
        val job = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val result = awaitResult(active.sendCustomCommand(command, arguments))
                if (generation != sleepTimerCommandGeneration || controller !== active || !isForeground) return@launch
                capturePlayback()
                val issue = when {
                    result.resultCode == SessionResult.RESULT_SUCCESS -> null
                    result.resultCode == SessionError.ERROR_BAD_VALUE -> SleepTimerCommandIssue.INVALID_INPUT
                    PlaybackCommands.readFailureCode(result.extras) == "CONFLICT" -> SleepTimerCommandIssue.CONFLICT
                    PlaybackCommands.readFailureCode(result.extras) == "BUSY" -> SleepTimerCommandIssue.BUSY
                    else -> SleepTimerCommandIssue.FAILED
                }
                _uiState.update { it.copy(sleepTimerCommandIssue = issue) }
                if (issue == null && _uiState.value.page == ProbePage.SLEEP_TIMER) onAccepted()
                updateProgressSampling()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                if (generation == sleepTimerCommandGeneration && controller === active && isForeground) {
                    capturePlayback()
                    _uiState.update { it.copy(sleepTimerCommandIssue = SleepTimerCommandIssue.FAILED) }
                }
            } finally {
                if (generation == sleepTimerCommandGeneration) {
                    sleepTimerCommandJob = null
                    _uiState.update { it.copy(sleepTimerCommandPending = false) }
                }
            }
        }
        sleepTimerCommandJob = job
        job.start()
    }

    private fun retireSleepTimerCommand() {
        sleepTimerCommandGeneration += 1L
        sleepTimerCommandJob?.cancel()
        sleepTimerCommandJob = null
    }

    fun reconnectPlayback() {
        onBackground()
        onForeground()
    }

    fun setWindowFocused(focused: Boolean) {
        if (windowFocused == focused) return
        windowFocused = focused
        inputFocusToken = UUID.randomUUID().toString()
        _uiState.update { it.copy(inputFocusEpoch = inputFocusToken) }
        playbackInput.invalidate()
        mediaVolume.invalidate()
        updateVolumeVisibility()
        updateProgressSampling()
    }

    private fun retirePageInputFocus() {
        playbackInputFocused = false
        rotaryInputFocused = false
        playbackListHasPriority = true
        inputFocusToken = UUID.randomUUID().toString()
        _uiState.update { it.copy(inputFocusEpoch = inputFocusToken) }
        playbackInput.invalidate()
        mediaVolume.invalidate()
    }

    fun setPlaybackInputFocused(page: ProbePage, focused: Boolean, rotaryFocused: Boolean) {
        if (_uiState.value.page != page) return
        if (playbackInputFocused == focused && rotaryInputFocused == rotaryFocused) return
        playbackInputFocused = focused
        rotaryInputFocused = rotaryFocused
        inputFocusToken = UUID.randomUUID().toString()
        _uiState.update { it.copy(inputFocusEpoch = inputFocusToken) }
        playbackInput.invalidate()
        mediaVolume.invalidate()
    }

    fun setPlaybackListPriority(page: ProbePage, priority: Boolean) {
        if (_uiState.value.page != page || playbackListHasPriority == priority) return
        playbackListHasPriority = priority
        playbackInput.contextChanged()
    }

    fun pickerCancelled() {
        _uiState.update { state -> state.copy(notice = ProbeNotice.CANCELLED, fileIssue = null) }
    }

    fun noPicker(isTree: Boolean) {
        _uiState.update { state ->
            state.copy(notice = if (isTree) ProbeNotice.NO_TREE_PICKER else ProbeNotice.NO_DOCUMENT_PICKER)
        }
    }

    fun permissionDenied(permanently: Boolean) {
        _uiState.update { state ->
            state.copy(
                notice = ProbeNotice.PERMISSION_DENIED,
                fileIssue = null,
                needsPermissionSettings = permanently,
            )
        }
    }

    fun hasAudioPermission(): Boolean =
        getApplication<Application>().checkSelfPermission(audioPermission) == PackageManager.PERMISSION_GRANTED

    fun readMediaSample() {
        if (!hasAudioPermission()) {
            permissionDenied(permanently = false)
            return
        }
        showPage(ProbePage.MEDIA)
        startFileRead(
            operation = { files.mediaSample() },
            applyResult = { state, documents -> state.copy(mediaSample = documents, needsPermissionSettings = false) },
        )
    }

    fun readTreeSample(uri: Uri, flags: Int) {
        showPage(ProbePage.TREE)
        startFileRead(
            operation = { files.treeSample(uri, flags) },
            applyResult = { state, sample -> state.copy(treeSample = sample) },
        )
    }

    fun selectDocument(uri: Uri, flags: Int) = selectAudio(uri, grantUri = uri, flags = flags)

    fun selectMedia(document: ProbeDocument) = selectAudio(document.uri, usesMediaPermission = true)

    fun selectTreeAudio(document: ProbeDocument) {
        val sample = _uiState.value.treeSample ?: return
        selectAudio(document.uri, sample.treeUri, sample.grantFlags)
    }

    fun persistSelectedAccess() {
        val selected = _uiState.value.selectedAudio ?: return
        if (selected.access == ProbeAccess.MEDIA_PERMISSION) return
        startFileRead(
            operation = { files.persistReadAccess(selected.grantUri, selected.grantFlags) },
            applyResult = { state, access ->
                val current = state.selectedAudio
                if (current == null || current.mediaId != selected.mediaId) {
                    state
                } else {
                    state.copy(
                        selectedAudio = current.copy(access = access),
                        selectedQueueEntry = state.selectedQueueEntry?.withAccess(access),
                    )
                }
            },
        )
    }

    fun persistTreeAccess() {
        val sample = _uiState.value.treeSample ?: return
        startFileRead(
            operation = { files.persistReadAccess(sample.treeUri, sample.grantFlags) },
            applyResult = { state, access ->
                val currentSample = state.treeSample
                if (currentSample == null || currentSample.treeUri != sample.treeUri) {
                    state
                } else {
                    val selected = state.selectedAudio
                    state.copy(
                        treeSample = currentSample.copy(access = access),
                        selectedAudio = if (selected?.grantUri == sample.treeUri) selected.copy(access = access)
                        else selected,
                        selectedQueueEntry = if (selected?.grantUri == sample.treeUri) {
                            state.selectedQueueEntry?.withAccess(access)
                        } else {
                            state.selectedQueueEntry
                        },
                    )
                }
            },
        )
    }

    fun playSelection() {
        val activeController = controller ?: return
        val selected = _uiState.value.selectedQueueEntry
        if (_uiState.value.playbackCommandPending) return
        if (selected != null && activeController.currentMediaItem?.mediaId != selected.queueEntryId) {
            startQueueCommand {
                val saved = queues.snapshot()
                // 发布后的音源失败重试当前实例；持久操作重放不会重新装载播放器。
                if (saved?.entryCount == 1L && saved.currentEntryId == selected.queueEntryId) {
                    PlaybackCommands.selectEntry to PlaybackCommands.selectionArguments(saved, selected.queueEntryId)
                } else {
                    PlaybackCommands.replaceLocal to PlaybackCommands.localArguments(
                        selected,
                        saved,
                        "selection:${UUID.randomUUID()}",
                    )
                }
            }
            return
        }
        resumeCurrent()
    }

    /** Transport resumes the current session item; another browsed document remains a separate choice. */
    fun resumeCurrent() {
        val activeController = controller ?: return
        if (!isForeground || _uiState.value.playbackCommandPending || activeController.currentMediaItem == null) return
        playbackInput.invalidate()
        try {
            _uiState.update { state -> state.copy(queueIssue = null) }
            // 服务把恢复/尾部重播和本次播放意图作为同一命令；预先prepare/seek会异步抢占它。
            activeController.play()
        } catch (_: IllegalStateException) {
            _uiState.update { state -> state.copy(notice = ProbeNotice.COMMAND_REJECTED) }
        }
        capturePlayback()
    }

    fun selectQueueEntry(snapshot: QueueSnapshot, entryId: String) {
        if (_uiState.value.playbackCommandPending) return
        _uiState.update { state -> state.copy(selectedAudio = null, selectedQueueEntry = null) }
        showPage(ProbePage.PLAYBACK)
        startQueueCommand(PlaybackCommands.selectEntry) { PlaybackCommands.selectionArguments(snapshot, entryId) }
    }

    fun retryQueueRestore() = startQueueCommand(PlaybackCommands.retryRestore) { Bundle.EMPTY }

    fun playLibraryView(command: LibraryQueueCommand) {
        _uiState.update { it.copy(selectedAudio = null, selectedQueueEntry = null) }
        startQueueCommand(onAccepted = { showPage(ProbePage.PLAYBACK) }) {
            PlaybackCommands.replaceLibraryView to PlaybackCommands.libraryArguments(command)
        }
    }

    fun playPlaylistView(command: PlaylistQueueCommand, onResult: (PlaylistUiIssue?) -> Unit) {
        if (controller == null || !isForeground) {
            onResult(PlaylistUiIssue.SOURCE_UNAVAILABLE)
            return
        }
        if (playbackCommandJob != null || pendingPlaylistCommand != null) {
            onResult(PlaylistUiIssue.BUSY)
            return
        }
        pendingPlaylistCommand = PendingPlaylistCommand(command.operationId, onResult)
        _uiState.update { it.copy(selectedAudio = null, selectedQueueEntry = null) }
        startQueueCommand(
            onFailure = { code -> completePlaylistCommand(command.operationId, playlistCommandIssue(code)) },
            onResult = { result ->
                val issue = playlistCommandResultIssue(result, command.operationId)
                if (issue != null && result.resultCode == SessionResult.RESULT_SUCCESS) {
                    _uiState.update { it.copy(queueIssue = QueuePlaybackIssue.SOURCE_CHANGED) }
                }
                // ACK先交给仍可见的意图所有者；导航后隐藏页面会丢弃该运行时草稿。
                if (completePlaylistCommand(command.operationId, issue) && issue == null) showPage(ProbePage.PLAYBACK)
            },
        ) {
            PlaybackCommands.playlistQueue to PlaybackCommands.playlistArguments(command)
        }
    }

    private fun completePlaylistCommand(operationId: String, issue: PlaylistUiIssue?): Boolean {
        val pending = pendingPlaylistCommand ?: return false
        if (pending.operationId != operationId) return false
        pendingPlaylistCommand = null
        pending.onResult(issue)
        return true
    }

    private fun rejectPlaylistCommand(issue: PlaylistUiIssue) {
        val pending = pendingPlaylistCommand ?: return
        completePlaylistCommand(pending.operationId, issue)
    }

    fun editQueue(request: QueueMutationRequest, onAccepted: () -> Unit) {
        startQueueCommand(onAccepted = { onAccepted() }) {
            PlaybackCommands.mutateQueue to PlaybackCommands.mutationArguments(request)
        }
    }

    fun playTemporaryEntry(entry: QueueEntry) {
        startQueueCommand(onAccepted = { showPage(ProbePage.PLAYBACK) }) {
            PlaybackCommands.replaceLocal to PlaybackCommands.localArguments(entry, queues.snapshot(), "preview:${UUID.randomUUID()}")
        }
    }

    fun previous() {
        val active = controller ?: return
        playbackInput.invalidate()
        if (active.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)) active.seekToPreviousMediaItem()
        capturePlayback()
    }

    fun next() {
        val active = controller ?: return
        playbackInput.invalidate()
        if (active.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)) active.seekToNextMediaItem()
        capturePlayback()
    }

    private fun startQueueCommand(command: SessionCommand, arguments: suspend () -> Bundle) =
        startQueueCommand { command to arguments() }

    private fun startQueueCommand(onAccepted: (SessionResult) -> Unit = {}, onFailure: (String) -> Unit = {},
        onResult: ((SessionResult) -> Unit)? = null, request: suspend () -> Pair<SessionCommand, Bundle>) {
        val active = controller
        if (active == null || !isForeground) {
            onFailure("SOURCE_UNAVAILABLE")
            return
        }
        if (playbackCommandJob != null) {
            onFailure("BUSY")
            return
        }
        playbackInput.invalidate()
        mediaVolume.invalidate()
        playbackCommandGeneration += 1L
        val generation = playbackCommandGeneration
        _uiState.update { state -> state.copy(playbackCommandPending = true, queueIssue = null) }
        val job = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val (command, args) = request()
                if (generation != playbackCommandGeneration || controller !== active || !isForeground) return@launch
                val result = awaitResult(active.sendCustomCommand(command, args))
                if (generation != playbackCommandGeneration || controller !== active || !isForeground) return@launch
                _uiState.update { state ->
                    state.copy(queueIssue = if (result.resultCode == SessionResult.RESULT_SUCCESS) state.queueIssue else {
                        queueIssue(PlaybackCommands.readFailureCode(result.extras))
                    })
                }
                capturePlayback()
                if (onResult != null) onResult(result)
                else if (result.resultCode == SessionResult.RESULT_SUCCESS) onAccepted(result)
                else onFailure(PlaybackCommands.readFailureCode(result.extras) ?: "SOURCE_UNAVAILABLE")
            } catch (error: kotlinx.coroutines.CancellationException) {
                if (generation == playbackCommandGeneration) onFailure("CANCELLED")
                throw error
            } catch (error: QueueStoreException) {
                if (generation == playbackCommandGeneration) {
                    _uiState.update { state -> state.copy(queueIssue = queueIssue(error.issue.name)) }
                    onFailure(error.issue.name)
                }
            } catch (_: Exception) {
                if (generation == playbackCommandGeneration) {
                    _uiState.update { state -> state.copy(queueIssue = QueuePlaybackIssue.SOURCE_UNAVAILABLE) }
                    onFailure("SOURCE_UNAVAILABLE")
                }
            } finally {
                if (generation == playbackCommandGeneration) {
                    _uiState.update { state -> state.copy(playbackCommandPending = false) }
                }
            }
        }
        playbackCommandJob = job
        job.invokeOnCompletion { if (playbackCommandJob === job) playbackCommandJob = null }
        job.start()
    }

    private suspend fun awaitResult(future: ListenableFuture<SessionResult>): SessionResult =
        suspendCancellableCoroutine { continuation ->
            future.addListener({
                if (continuation.isActive) {
                    try {
                        continuation.resumeWith(Result.success(future.get()))
                    } catch (error: Exception) {
                        continuation.resumeWith(Result.failure(error))
                    }
                }
            }, mainExecutor)
            continuation.invokeOnCancellation { future.cancel(false) }
        }

    fun pause() {
        rejectPlaylistCommand(PlaylistUiIssue.BUSY)
        playbackCommandGeneration += 1L
        playbackCommandJob?.cancel()
        playbackInput.invalidate()
        mediaVolume.invalidate()
        _uiState.update { state ->
            state.copy(
                playbackCommandPending = false,
                notice = if (state.playbackCommandPending) ProbeNotice.CANCELLED else state.notice,
            )
        }
        try {
            // 已暂停的会话也接收暂停意图，阻止晚到的队列提交自动播放。
            controller?.pause()
        } catch (_: IllegalStateException) {
            _uiState.update { state -> state.copy(notice = ProbeNotice.COMMAND_REJECTED) }
        }
        capturePlayback()
    }

    private fun playbackInputContext(): PlaybackPageInputContext {
        val state = _uiState.value
        return PlaybackPageInputContext(state.seekAuthority?.ownerId,
            inputFocusToken.takeIf { windowFocused && playbackInputFocused },
            isForeground && windowFocused, state.page == ProbePage.PLAYBACK, state.page == ProbePage.SEEK,
            state.playbackCommandPending || mediaVolume.busy, state.playback.positionMs, state.playback.durationMs,
            state.inputControls, state.controlsStateKey, state.settingsRevision, playbackListHasPriority, rotaryInputFocused)
    }

    private suspend fun acquireSeekBinding(): QueueSeekBinding? {
        val active = controller ?: return null
        if (!isForeground || !active.isConnected) return null
        val result = withTimeoutOrNull(INPUT_COMMAND_TIMEOUT_MS) {
            awaitResult(active.sendCustomCommand(PlaybackCommands.beginSeek, PlaybackCommands.beginSeekArguments()))
        } ?: return null
        if (controller !== active || !isForeground || result.resultCode != SessionResult.RESULT_SUCCESS) return null
        capturePlayback()
        return PlaybackCommands.readSeekBinding(result.extras).takeIf(::isSeekBindingCurrent)
    }

    /** Validate existing service extras; requesting another binding would replace the preview. */
    private fun isSeekBindingCurrent(binding: QueueSeekBinding): Boolean {
        val active = controller ?: return false
        val authority = QueueSeekSessionState.fromSessionExtras(active.sessionExtras) ?: return false
        val state = _uiState.value
        return isForeground && active.isConnected && authority.ownerId != null && authority.previewId == binding.previewId &&
            state.seekAuthority?.ownerId == authority.ownerId && state.playback.mediaId == binding.queueEntryId &&
            state.playback.durationMs == binding.durationMs && state.backend?.let {
                !it.switching && it.actualBackend != null && it.generation == binding.backendGeneration
            } == true
    }

    private suspend fun dispatchSeek(request: QueueSeekRequest): Boolean {
        val active = controller ?: return false
        if (!isSeekBindingCurrent(request.binding)) return false
        val result = withTimeoutOrNull(INPUT_COMMAND_TIMEOUT_MS) {
            awaitResult(active.sendCustomCommand(PlaybackCommands.seekQueue, PlaybackCommands.seekArguments(request)))
        } ?: return false
        if (controller !== active || !isForeground) return false
        capturePlayback()
        return result.resultCode == SessionResult.RESULT_SUCCESS
    }

    private fun canUseMediaVolume(): Boolean = isForeground && windowFocused && !playbackInput.busy &&
        !_uiState.value.playbackCommandPending && _uiState.value.page in setOf(ProbePage.PLAYBACK, ProbePage.VOLUME)

    private fun applyRotaryVolume(step: RotaryStep): Boolean = mediaVolume.rotary(step)

    private fun updateVolumeVisibility() {
        if (isForeground && windowFocused && _uiState.value.page in setOf(ProbePage.PLAYBACK, ProbePage.VOLUME)) mediaVolume.onVisible()
        else mediaVolume.onHidden()
    }

    private fun onRotaryApplied(applied: RotaryAppliedStep, context: RotaryInputContext) {
        val application = getApplication<Application>()
        val vibrator = try {
            if (Build.VERSION.SDK_INT >= 31) application.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else application.getSystemService(Vibrator::class.java)
        } catch (_: RuntimeException) { null }
        val hasVibrator = try { vibrator?.hasVibrator() == true } catch (_: RuntimeException) { false }
        val decision = RotaryHapticGate.evaluate(hapticState, applied, context, SystemClock.elapsedRealtime(),
            enabled = _uiState.value.inputControls.rotaryHaptics, hasVibrator = hasVibrator)
        hapticState = decision.state
        if (decision.requestPulse) {
            try { vibrator?.vibrate(VibrationEffect.createOneShot(RotaryHapticGate.PULSE_DURATION_MS, VibrationEffect.DEFAULT_AMPLITUDE)) }
            catch (_: RuntimeException) { }
        }
    }

    fun cancelFileRead() {
        fileGeneration += 1L
        fileJob?.cancel()
        fileJob = null
        _uiState.update { state -> state.copy(isReading = false, notice = ProbeNotice.CANCELLED, fileIssue = null) }
    }

    private fun selectAudio(
        uri: Uri,
        grantUri: Uri = uri,
        flags: Int = 0,
        usesMediaPermission: Boolean = false,
    ) {
        startFileRead(
            operation = { files.inspectAudio(uri, grantUri, flags, usesMediaPermission) },
            applyResult = { state, audio ->
                state.copy(selectedAudio = audio, selectedQueueEntry = audio.queueEntry(), page = ProbePage.PLAYBACK)
            },
        )
    }

    private fun <T> startFileRead(
        operation: suspend () -> ProbeFileResult<T>,
        applyResult: (ProbeUiState, T) -> ProbeUiState,
    ) {
        fileGeneration += 1L
        val generation = fileGeneration
        fileJob?.cancel()
        _uiState.update { state -> state.copy(isReading = true, notice = ProbeNotice.READING, fileIssue = null) }
        fileJob = viewModelScope.launch {
            try {
                val result = withTimeoutOrNull(FILE_CHECK_TIMEOUT_MS) { operation() }
                if (generation != fileGeneration) return@launch
                if (result == null) {
                    _uiState.update { state -> state.copy(isReading = false, notice = ProbeNotice.TIMEOUT) }
                    return@launch
                }
                val previousPage = _uiState.value.page
                _uiState.update { state ->
                    when (result) {
                        is ProbeFileResult.Success -> applyResult(state, result.value).copy(
                            isReading = false,
                            notice = ProbeNotice.READY,
                        )
                        is ProbeFileResult.Failure -> state.copy(
                            isReading = false,
                            notice = null,
                            fileIssue = result.issue,
                        )
                    }
                }
                if (_uiState.value.page != previousPage) retirePageInputFocus()
                playbackInput.contextChanged()
                savedStateHandle[KEY_PAGE] = _uiState.value.page.name
                updateVolumeVisibility()
                updateProgressSampling()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            }
        }
    }

    private fun refreshCapabilities() {
        val application = getApplication<Application>()
        val document = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*")
        val tree = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        _uiState.update { state ->
            state.copy(
                hasDocumentPicker = document.resolveActivity(application.packageManager) != null,
                hasTreePicker = tree.resolveActivity(application.packageManager) != null,
            )
        }
    }

    private fun capturePlayback() {
        val activeController = controller ?: return
        val durationMs = activeController.duration.takeIf { duration -> duration != C.TIME_UNSET && duration >= 0L }
        val playback = ProbePlaybackState(
            isConnected = activeController.isConnected,
            hasMedia = activeController.currentMediaItem != null,
            mediaId = activeController.currentMediaItem?.mediaId,
            title = activeController.mediaMetadata.title?.toString(),
            isPlaying = activeController.isPlaying,
            playbackState = activeController.playbackState,
            positionMs = activeController.currentPosition.coerceAtLeast(0L),
            durationMs = durationMs,
            canSeek = durationMs != null && durationMs > 0L &&
                activeController.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
            hasError = activeController.playerError != null,
            hasPrevious = activeController.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM),
            hasNext = activeController.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM),
            artist = activeController.mediaMetadata.artist?.toString(),
        )
        val backendState = PlaybackBackendState.fromSessionExtras(activeController.sessionExtras)
        _uiState.update { state ->
            state.copy(playback = playback, backend = backendState,
                seekAuthority = QueueSeekSessionState.fromSessionExtras(activeController.sessionExtras),
                sleepTimer = SleepTimerSessionState.fromSessionExtras(activeController.sessionExtras),
                sleepTimerElapsedMs = SystemClock.elapsedRealtime())
        }
        playbackInput.contextChanged()
    }

    private fun updateProgressSampling() {
        progressJob?.cancel()
        progressJob = null
        if (!isForeground || !windowFocused || controller == null) return
        val page = _uiState.value.page
        if (page !in setOf(ProbePage.PLAYBACK, ProbePage.SEEK, ProbePage.VOLUME, ProbePage.SLEEP_TIMER)) return
        progressJob = viewModelScope.launch {
            while (isActive) {
                capturePlayback()
                if (page != ProbePage.SLEEP_TIMER) {
                    if (page == ProbePage.VOLUME) mediaVolume.refresh()
                    delay(PROGRESS_SAMPLE_INTERVAL_MS)
                }
                else {
                    val timer = _uiState.value.sleepTimer ?: break
                    val remaining = (timer.deadlineElapsedMs ?: break) - SystemClock.elapsedRealtime()
                    if (!timer.active || timer.waitingForTrackEnd || remaining <= 0L) break
                    // Only the visible timer page updates its minute bucket; hidden UI owns no ticker.
                    delay((remaining % 60_000L).takeIf { it > 0L } ?: 60_000L)
                }
            }
        }
    }

    override fun onCleared() {
        onBackground()
        playbackInput.close()
        super.onCleared()
    }

    companion object {
        private const val KEY_PAGE = "navigation.page"
        private const val FILE_CHECK_TIMEOUT_MS = 8000L
        private const val PROGRESS_SAMPLE_INTERVAL_MS = 1000L
        private const val INPUT_COMMAND_TIMEOUT_MS = 12_000L
    }

    private data class PendingPlaylistCommand(val operationId: String, val onResult: (PlaylistUiIssue?) -> Unit)
}

internal fun playlistCommandResultIssue(result: SessionResult, operationId: String): PlaylistUiIssue? {
    return try {
        if (PlaybackCommands.readReceiptOperation(result.extras) != operationId) PlaylistUiIssue.SOURCE_CHANGED
        else if (result.resultCode == SessionResult.RESULT_SUCCESS) null
        else if (result.resultCode == SessionError.ERROR_BAD_VALUE) PlaylistUiIssue.INVALID_INPUT
        else {
            // A malformed issue on a matching receipt does not identify another operation.
            val code = try { PlaybackCommands.readFailureCode(result.extras) }
            catch (_: RuntimeException) { null }
            playlistCommandIssue(code)
        }
    } catch (_: RuntimeException) {
        PlaylistUiIssue.SOURCE_CHANGED
    }
}

private fun playlistCommandIssue(code: String?): PlaylistUiIssue = when (code) {
    "INCOMPLETE" -> PlaylistUiIssue.INCOMPLETE_SCAN
    "REVISION_EXHAUSTED" -> PlaylistUiIssue.RECOVERY_REQUIRED
    "CANCELLED", "NOT_READY" -> PlaylistUiIssue.BUSY
    "PERMISSION_DENIED", "FILE_MISSING", "LOCATION_MISSING", "CLOSED", "OUTPUT_ERROR" -> PlaylistUiIssue.SOURCE_UNAVAILABLE
    else -> PlaylistUiIssue.entries.firstOrNull { it.name == code } ?: PlaylistUiIssue.SOURCE_UNAVAILABLE
}

private fun ProbeAudio.queueEntry(): QueueEntry = QueueEntry(
    queueEntryId = UUID.randomUUID().toString(),
    trackKey = TrackKey.newLocal(),
    originalOrder = 0L,
    playbackOrder = 0L,
    displayName = displayName,
    durationMs = durationMs,
    location = LocalLocationReference(
        locationId = UUID.randomUUID().toString(),
        uri = uri.toString(),
        grantUri = grantUri.toString(),
        grantFlags = grantFlags,
        access = localAccess(access),
    ),
)

private fun QueueEntry.withAccess(access: ProbeAccess): QueueEntry =
    copy(location = location?.copy(access = localAccess(access)))

private fun localAccess(access: ProbeAccess): LocalAccess = when (access) {
    ProbeAccess.TEMPORARY -> LocalAccess.TEMPORARY
    ProbeAccess.PERSISTED -> LocalAccess.PERSISTED
    ProbeAccess.MEDIA_PERMISSION -> LocalAccess.MEDIA_PERMISSION
    ProbeAccess.PERSISTENCE_REJECTED -> LocalAccess.PERSISTENCE_REJECTED
}

private fun queueIssue(name: String?): QueuePlaybackIssue =
    QueuePlaybackIssue.entries.firstOrNull { it.name == name } ?: QueuePlaybackIssue.SOURCE_UNAVAILABLE
