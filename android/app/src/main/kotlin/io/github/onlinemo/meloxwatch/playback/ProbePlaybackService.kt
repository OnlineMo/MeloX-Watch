package io.github.onlinemo.meloxwatch.playback

import android.app.PendingIntent
import android.os.Bundle
import android.os.Process
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionError
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import io.github.onlinemo.meloxwatch.MeloXApplication
import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.queue.QueueReplacementRequest
import io.github.onlinemo.meloxwatch.data.queue.QueueStoreException
import io.github.onlinemo.meloxwatch.model.FrozenQueueView
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 由 DEV-004-A 演进的唯一音频所有者；页面只发送命令，队列先持久发布再作用于内核。
 *
 * 当前探针将焦点/noisy 交给唯一 ExoPlayer。LP-05 接入另一后端前，DEV-103
 * 必须把这些职责移交统一协调器，不能再叠加第二套监听。
 */
@UnstableApi
class ProbePlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var backend: ExoPlayer? = null
    private var coordinator: QueuePlaybackCoordinator? = null
    private var listening: ServiceListeningObserver? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeCommand: Job? = null
    private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        setListener(object : Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                val session = mediaSession ?: return
                // 前台提升失败必须停声，不让系统拒绝变成无通知后台播放。
                session.player.pause()
                session.sendError(SessionError(SessionError.ERROR_INVALID_STATE, "BACKGROUND_START_DENIED"))
            }
        })
        // 不提供 HTTP 数据源；错误 URI 不能使离线探针隐式发起网络请求。
        val localDataSource = DataSource.Factory { ContentDataSource(this) }
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(localDataSource))
            .build()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        player.setAudioAttributes(audioAttributes, true)
        player.setHandleAudioBecomingNoisy(true)
        player.setWakeMode(C.WAKE_MODE_LOCAL)
        backend = player
        val application = application as MeloXApplication
        val statistics = ServiceListeningObserver(player, application.listening, serviceScope) { issue ->
            mediaSession?.sendError(SessionError(SessionError.ERROR_INVALID_STATE, "LISTENING_${issue.name}"))
        }
        listening = statistics
        val queues = QueuePlaybackCoordinator(
            player,
            application.queues,
            application.preferences,
            serviceScope,
            resolveEntry = LocalPlaybackResolver(application)::resolve,
            onRoundChanged = statistics::beginRound,
        ) { failure ->
            mediaSession?.sendError(SessionError(SessionError.ERROR_INVALID_STATE, "QUEUE_${failure.issue.name}"))
        }
        coordinator = queues

        val builder = MediaSession.Builder(this, queues.sessionPlayer)
            .setCallback(LocalPlaybackCallback())
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            PlaybackCommands.playbackActivityIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        builder.setSessionActivity(sessionActivity)
        mediaSession = builder.build()
        serviceScope.launch {
            try {
                queues.initialize()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (queues.state.value.failure == null) {
                    mediaSession?.sendError(
                        SessionError(SessionError.ERROR_INVALID_STATE, "QUEUE_RECOVERY_REQUIRED"),
                    )
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        destroyed = true
        activeCommand?.cancel()
        val session = mediaSession
        val player = backend
        val queues = coordinator
        val statistics = listening
        mediaSession = null
        coordinator = null
        backend = null
        listening = null
        player?.pause()
        statistics?.detach()
        queues?.detach()
        session?.release()
        player?.release()
        // 主要保存路径是5秒及暂停/seek/切歌；销毁只补一次已捕获状态，不阻塞主线程。
        serviceScope.launch {
            try {
                supervisorScope {
                    listOf(
                        async {
                            try { withTimeoutOrNull(1500L) { queues?.close() } }
                            catch (error: CancellationException) { throw error }
                            catch (_: Exception) { null }
                        },
                        async {
                            try { withTimeoutOrNull(1500L) { statistics?.close() } }
                            catch (error: CancellationException) { throw error }
                            catch (_: Exception) { null }
                        },
                    ).awaitAll()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // 销毁阶段已停声并释放内核；保存失败保留上一次完整 checkpoint，不重建服务。
            } finally {
                serviceScope.cancel()
            }
        }
        super.onDestroy()
    }

    private fun commandFuture(operation: suspend () -> Unit): ListenableFuture<SessionResult> {
        if (destroyed) return Futures.immediateFuture(failureResult("CLOSED"))
        if (activeCommand?.isActive == true) return Futures.immediateFuture(failureResult("BUSY"))
        val future = SettableFuture.create<SessionResult>()
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                operation()
                val failure = coordinator?.state?.value?.failure
                future.set(
                    if (failure == null) SessionResult(SessionResult.RESULT_SUCCESS)
                    else failureResult(failure.issue.name, failure.queueStoreIssue?.name),
                )
            } catch (error: CancellationException) {
                future.cancel(false)
                throw error
            } catch (error: QueueStoreException) {
                val issue = coordinator?.state?.value?.failure?.issue ?: QueuePlaybackIssue.SOURCE_UNAVAILABLE
                future.set(failureResult(issue.name, error.issue.name))
            } catch (error: LibraryException) {
                val issue = when (error.issue) {
                    LibraryIssue.BUSY -> "BUSY"
                    LibraryIssue.CONFLICT -> "CONFLICT"
                    LibraryIssue.VIEW_EXPIRED -> "VIEW_EXPIRED"
                    LibraryIssue.SOURCE_CHANGED -> "SOURCE_CHANGED"
                    LibraryIssue.PERMISSION_REVOKED -> "PERMISSION_DENIED"
                    LibraryIssue.FILE_MISSING -> "FILE_MISSING"
                    LibraryIssue.STORAGE_FULL -> "STORAGE_FULL"
                    LibraryIssue.RECOVERY_REQUIRED -> "RECOVERY_REQUIRED"
                    else -> "SOURCE_UNAVAILABLE"
                }
                future.set(failureResult(issue))
            } catch (_: IllegalArgumentException) {
                future.set(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
            } catch (_: Exception) {
                future.set(failureResult(coordinator?.state?.value?.failure?.issue?.name ?: "OUTPUT_ERROR"))
            }
        }
        activeCommand = job
        future.addListener({ if (future.isCancelled) job.cancel() }, Executor { it.run() })
        job.start()
        return future
    }

    private fun failureResult(issue: String, storeIssue: String? = null): SessionResult {
        val extras = Bundle().apply {
            putString(PlaybackCommands.FAILURE, issue)
            storeIssue?.let { putString(PlaybackCommands.STORE_ISSUE, it) }
        }
        return SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE, extras)
    }

    private inner class LocalPlaybackCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            if (controller.uid != Process.myUid() && !controller.isTrusted) {
                return MediaSession.ConnectionResult.reject()
            }
            val result = MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            // 任一控制器都不能绕过完整队列发布直接修改3项后端窗口。
            val commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .remove(Player.COMMAND_SET_MEDIA_ITEM)
                .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                .build()
            result.setAvailablePlayerCommands(commands)
            if (controller.uid == Process.myUid()) {
                result.setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(PlaybackCommands.replaceLocal)
                    .add(PlaybackCommands.replaceLibraryView)
                    .add(PlaybackCommands.selectEntry)
                    .add(PlaybackCommands.retryRestore)
                    .build())
            }
            return result.build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (controller.uid != Process.myUid()) {
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_PERMISSION_DENIED))
            }
            return when (customCommand.customAction) {
                PlaybackCommands.replaceLocal.customAction -> commandFuture {
                    val command = PlaybackCommands.readLocal(args)
                    val queues = checkNotNull(coordinator)
                    queues.replace(command.request, command.source, explicitPlay = true)
                }
                PlaybackCommands.replaceLibraryView.customAction -> commandFuture {
                    val command = PlaybackCommands.readLibrary(args)
                    val application = application as MeloXApplication
                    val library = application.library
                    val view = library.openQueueView(command.viewId)
                    try {
                        if (view.totalCount == 0L) throw LibraryException(LibraryIssue.INVALID_INPUT)
                        val start = command.selected?.let { selected ->
                            library.locate(view, selected)?.ordinal
                                ?: throw LibraryException(LibraryIssue.SOURCE_CHANGED)
                        } ?: 0L
                        val saved = application.queues.snapshot()
                        val request = QueueReplacementRequest(
                            operationId = command.operationId,
                            expectedGenerationId = command.expectedGenerationId,
                            expectedQueueRevision = command.expectedQueueRevision,
                            view = FrozenQueueView(view.viewId, view.revision, view.totalCount),
                            mode = saved?.mode ?: io.github.onlinemo.meloxwatch.model.QueueMode(),
                            startOriginalOrder = start,
                        )
                        checkNotNull(coordinator).replace(request, library.queueSource(view), explicitPlay = true)
                    } finally {
                        library.closeView(view.viewId)
                    }
                }
                PlaybackCommands.selectEntry.customAction -> commandFuture {
                    val command = PlaybackCommands.readSelection(args)
                    val queues = checkNotNull(coordinator)
                    queues.select(command.generationId, command.queueRevision, command.entryId, explicitPlay = true)
                }
                PlaybackCommands.retryRestore.customAction -> commandFuture {
                    checkNotNull(coordinator).initialize()
                }
                else -> super.onCustomCommand(session, controller, customCommand, args)
            }
        }
    }
}
