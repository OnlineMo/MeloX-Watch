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
import io.github.onlinemo.meloxwatch.model.LocalAccess
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.playback.PlaybackCommands
import io.github.onlinemo.meloxwatch.playback.ProbePlaybackService
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue
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
)

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
        refreshCapabilities()
        connectionGeneration += 1L
        val generation = connectionGeneration
        val token = SessionToken(getApplication(), ComponentName(getApplication(), ProbePlaybackService::class.java))
        val connectionListener = object : MediaController.Listener {
            override fun onDisconnected(disconnected: MediaController) {
                if (generation != connectionGeneration || !isForeground) return
                controller = null
                progressJob?.cancel()
                progressJob = null
                _uiState.update { state ->
                    state.copy(
                        playback = state.playback.copy(isConnected = false),
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
                val backgroundDenied = sessionError.code == SessionError.ERROR_INVALID_STATE &&
                    sessionError.message == "BACKGROUND_START_DENIED"
                _uiState.update { state ->
                    state.copy(
                        notice = if (backgroundDenied) ProbeNotice.BACKGROUND_START_DENIED
                        else ProbeNotice.COMMAND_REJECTED,
                    )
                }
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
        isForeground = false
        connectionGeneration += 1L
        playbackCommandGeneration += 1L
        playbackCommandJob?.cancel()
        playbackCommandJob = null
        progressJob?.cancel()
        progressJob = null
        if (_uiState.value.isReading) cancelFileRead()
        controller?.removeListener(playerListener)
        controller = null
        connectionFuture?.let { future -> MediaController.releaseFuture(future) }
        connectionFuture = null
        _uiState.update { state ->
            state.copy(playback = state.playback.copy(isConnected = false), playbackCommandPending = false)
        }
    }

    fun showPage(page: ProbePage) {
        val currentState = _uiState.value
        if (currentState.page != page && currentState.isReading) {
            // 离页使检查代次失效；不合作的provider晚到结果不能导航回播放页。
            cancelFileRead()
        }
        savedStateHandle[KEY_PAGE] = page.name
        _uiState.update { state -> state.copy(page = page) }
        updateProgressSampling()
    }

    fun reconnectPlayback() {
        onBackground()
        onForeground()
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
        try {
            _uiState.update { state -> state.copy(queueIssue = null) }
            if (activeController.playbackState == Player.STATE_IDLE && activeController.currentMediaItem != null) {
                activeController.prepare()
            }
            if (activeController.playbackState == Player.STATE_ENDED) activeController.seekTo(0L)
            if (selected != null || activeController.currentMediaItem != null) activeController.play()
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

    fun previous() {
        val active = controller ?: return
        if (active.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)) active.seekToPreviousMediaItem()
        capturePlayback()
    }

    fun next() {
        val active = controller ?: return
        if (active.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)) active.seekToNextMediaItem()
        capturePlayback()
    }

    private fun startQueueCommand(command: SessionCommand, arguments: suspend () -> Bundle) =
        startQueueCommand { command to arguments() }

    private fun startQueueCommand(request: suspend () -> Pair<SessionCommand, Bundle>) {
        val active = controller ?: return
        if (!isForeground || playbackCommandJob?.isActive == true) return
        playbackCommandGeneration += 1L
        val generation = playbackCommandGeneration
        _uiState.update { state -> state.copy(playbackCommandPending = true, queueIssue = null) }
        playbackCommandJob = viewModelScope.launch {
            try {
                val (command, args) = request()
                if (generation != playbackCommandGeneration || controller !== active || !isForeground) return@launch
                val result = awaitResult(active.sendCustomCommand(command, args))
                if (generation != playbackCommandGeneration || controller !== active || !isForeground) return@launch
                _uiState.update { state ->
                    state.copy(queueIssue = if (result.resultCode == SessionResult.RESULT_SUCCESS) state.queueIssue else {
                        queueIssue(result.extras.getString(PlaybackCommands.FAILURE))
                    })
                }
                capturePlayback()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: QueueStoreException) {
                if (generation == playbackCommandGeneration) {
                    _uiState.update { state -> state.copy(queueIssue = queueIssue(error.issue.name)) }
                }
            } catch (_: Exception) {
                if (generation == playbackCommandGeneration) {
                    _uiState.update { state -> state.copy(queueIssue = QueuePlaybackIssue.SOURCE_UNAVAILABLE) }
                }
            } finally {
                if (generation == playbackCommandGeneration) {
                    _uiState.update { state -> state.copy(playbackCommandPending = false) }
                }
            }
        }
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
        playbackCommandGeneration += 1L
        playbackCommandJob?.cancel()
        playbackCommandJob = null
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

    fun seekTo(mediaId: String?, positionMs: Long) {
        val activeController = controller ?: return
        if (activeController.currentMediaItem?.mediaId != mediaId || !_uiState.value.playback.canSeek) return
        val durationMs = _uiState.value.playback.durationMs ?: return
        activeController.seekTo(positionMs.coerceIn(0L, durationMs))
        capturePlayback()
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
                savedStateHandle[KEY_PAGE] = _uiState.value.page.name
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
        )
        _uiState.update { state -> state.copy(playback = playback) }
    }

    private fun updateProgressSampling() {
        progressJob?.cancel()
        progressJob = null
        if (!isForeground || _uiState.value.page != ProbePage.PLAYBACK || controller == null) return
        progressJob = viewModelScope.launch {
            while (isActive) {
                capturePlayback()
                delay(PROGRESS_SAMPLE_INTERVAL_MS)
            }
        }
    }

    override fun onCleared() {
        onBackground()
        super.onCleared()
    }

    companion object {
        private const val KEY_PAGE = "navigation.page"
        private const val FILE_CHECK_TIMEOUT_MS = 8000L
        private const val PROGRESS_SAMPLE_INTERVAL_MS = 1000L
    }
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
