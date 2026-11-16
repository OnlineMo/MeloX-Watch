package io.github.onlinemo.meloxwatch.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionError
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import io.github.onlinemo.meloxwatch.MeloXApplication
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistException
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesState
import io.github.onlinemo.meloxwatch.data.queue.QueueStoreException
import io.github.onlinemo.meloxwatch.data.queue.QueueStoreIssue
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 由 DEV-004-A 演进的唯一音频所有者；页面只发送命令，队列先持久发布再作用于内核。
 *
 * 服务统一持有后端、焦点、noisy 和前台通知；后端切换不替换会话或复制播放器。
 */
@UnstableApi
class ProbePlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var playbackOwner: ServicePlaybackOwner? = null
    private var bitmapLoader: LocalSessionBitmapLoader? = null
    private var coordinator: QueuePlaybackCoordinator? = null
    private var listening: ServiceListeningObserver? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeCommand: Job? = null
    private var initialization: Job? = null
    private var extrasObserver: Job? = null
    private var commandPreferencesObserver: Job? = null
    private var initializing = true
    private var mediaButtonsEnabled = true
    private var destroyed = false
    private var retiredPlaylistProducer: PlaylistQueueProducer? = null

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(NOTIFICATION_ID)
            .setChannelId(NOTIFICATION_CHANNEL)
            .setChannelName(R.string.app_name)
            .build())
        setListener(object : Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                val session = mediaSession ?: return
                // 前台提升失败必须停声，不让系统拒绝变成无通知后台播放。
                playbackOwner?.foregroundDenied()
                session.sendError(SessionError(SessionError.ERROR_INVALID_STATE, "BACKGROUND_START_DENIED"))
            }
        })
        val application = application as MeloXApplication
        val owner = ServicePlaybackOwner(this, serviceScope, application.preferences, application.localProviderIo,
            ensureForeground = ::ensurePlaybackForeground,
            onFailure = { issue ->
                mediaSession?.sendError(SessionError(SessionError.ERROR_INVALID_STATE, "BACKEND_${issue.name}"))
            },
        )
        playbackOwner = owner
        val player = owner.initialPlayer
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
            onFailure = { failure ->
                mediaSession?.sendError(SessionError(SessionError.ERROR_INVALID_STATE, "QUEUE_${failure.issue.name}"))
            },
            operations = owner.initialOperations,
            requestPlay = ::requestPlayback,
            onUserPause = owner::userPause,
            onUserIntentChanged = owner::userIntentChanged,
        )
        coordinator = queues
        owner.attach(queues, statistics)
        val artwork = LocalSessionBitmapLoader(this, application.localProviderIo, serviceScope)
        bitmapLoader = artwork

        val builder = MediaSession.Builder(this, queues.sessionPlayer)
            .setCallback(LocalPlaybackCallback())
            .setBitmapLoader(artwork)
            .setSessionExtras(owner.sessionExtras())
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            PlaybackCommands.playbackActivityIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        builder.setSessionActivity(sessionActivity)
        mediaSession = builder.build()
        extrasObserver = serviceScope.launch {
            owner.state.collect {
                if (!destroyed) mediaSession?.setSessionExtras(owner.sessionExtras())
            }
        }
        commandPreferencesObserver = serviceScope.launch {
            application.preferences.state.collect { saved ->
                val enabled = when (saved) {
                    is PreferencesState.Ready -> saved.preferences.mediaButtonsEnabled
                    is PreferencesState.Unavailable -> saved.lastKnown?.mediaButtonsEnabled ?: mediaButtonsEnabled
                    PreferencesState.Loading -> mediaButtonsEnabled
                }
                if (!destroyed && enabled != mediaButtonsEnabled) {
                    mediaButtonsEnabled = enabled
                    refreshControllerCommands()
                }
            }
        }
        initialization = serviceScope.launch {
            try {
                owner.initializePreferences()
                queues.initialize()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (queues.state.value.failure == null) {
                    mediaSession?.sendError(
                        SessionError(SessionError.ERROR_INVALID_STATE, "QUEUE_RECOVERY_REQUIRED"),
                    )
                }
            } finally {
                if (!destroyed) {
                    initializing = false
                    owner.observePreferences()
                    refreshControllerCommands()
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        destroyed = true
        val pendingCommand = activeCommand
        pendingCommand?.cancel()
        initialization?.cancel()
        extrasObserver?.cancel()
        commandPreferencesObserver?.cancel()
        val session = mediaSession
        val owner = playbackOwner
        val artwork = bitmapLoader
        val queues = coordinator
        val statistics = listening
        mediaSession = null
        coordinator = null
        playbackOwner = null
        bitmapLoader = null
        listening = null
        try {
            try { session?.player?.pause() }
            finally {
                try { statistics?.detach() }
                finally {
                    try { queues?.detach() }
                    finally {
                        try { session?.release() }
                        finally { try { owner?.close() } finally { artwork?.close() } }
                    }
                }
            }
        } catch (_: Exception) {
            // Each teardown was attempted. Writers below consume captured values even when a
            // platform release reports an error; no player is recreated during destruction.
        }
        // 主要保存路径是5秒及暂停/seek/切歌；销毁只补一次已捕获状态，不阻塞主线程。
        serviceScope.launch {
            try {
                // 被取消的命令先完成有界lease收尾，再补偿最后一个失败的关闭尝试。
                withTimeoutOrNull(PLAYLIST_SHUTDOWN_TIMEOUT_MS) { pendingCommand?.join() }
                val producer = retiredPlaylistProducer
                if (producer != null && closePlaylistProducer(producer)) retiredPlaylistProducer = null
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

    private fun ensurePlaybackForeground(): Boolean {
        val session = mediaSession ?: return false
        if (destroyed) return false
        if (isPlaybackOngoing) return true
        return try {
            val manager = checkNotNull(getSystemService(NotificationManager::class.java))
            manager.createNotificationChannel(NotificationChannel(
                NOTIFICATION_CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW,
            ))
            val notification = Notification.Builder(this, NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(session.player.mediaMetadata.title?.take(256) ?: getString(R.string.app_name))
                .setContentText(getString(R.string.probe_buffering))
                .setContentIntent(session.sessionActivity)
                .setStyle(Notification.MediaStyle().setMediaSession(session.platformToken))
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(true)
                .build()
            // Target API35+ requires an actual foreground service before requesting audio focus.
            // This uses the same ID/channel as Media3; later updates own the ongoing notification.
            ContextCompat.startForegroundService(this, Intent(this, ProbePlaybackService::class.java))
            if (Build.VERSION.SDK_INT >= 29) startForeground(
                NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            ) else startForeground(NOTIFICATION_ID, notification)
            true
        } catch (_: RuntimeException) {
            // A successful startForegroundService followed by failed promotion must not leave
            // a pending foreground deadline. Bound controllers may still inspect/retry paused.
            stopSelf()
            false
        }
    }

    private fun requestPlayback(): Boolean {
        val accepted = playbackOwner?.requestPlay() == true
        if (!accepted) mediaSession?.let { session ->
            // A denied or delayed request stays paused. Media3 stops any warm-up foreground
            // promotion; no cached boolean can incorrectly authorize a later focus request.
            try { onUpdateNotification(session, false) }
            catch (_: RuntimeException) { playbackOwner?.foregroundDenied() }
        }
        return accepted
    }

    private fun commandFuture(operationId: String? = null, operation: suspend () -> SessionResult): ListenableFuture<SessionResult> {
        if (destroyed) return Futures.immediateFuture(receiptResult(failureResult("CLOSED"), operationId))
        if (initializing || playbackOwner?.state?.value?.switching == true || activeCommand?.isActive == true) {
            return Futures.immediateFuture(receiptResult(failureResult("BUSY"), operationId))
        }
        val future = SettableFuture.create<SessionResult>()
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                future.set(receiptResult(operation(), operationId))
            } catch (error: CancellationException) {
                future.cancel(false)
                throw error
            } catch (error: QueuePlaybackException) {
                future.set(receiptResult(failureResult(error.failure.issue.name, error.failure.queueStoreIssue?.name), operationId))
            } catch (error: QueueStoreException) {
                val issue = if (error.issue == QueueStoreIssue.INVALID_INPUT) "CONFLICT" else error.issue.name
                future.set(receiptResult(failureResult(issue, error.issue.name), operationId))
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
                future.set(receiptResult(failureResult(issue), operationId))
            } catch (error: PlaylistException) {
                future.set(receiptResult(failureResult(error.issue.name), operationId))
            } catch (_: IllegalArgumentException) {
                future.set(receiptResult(SessionResult(SessionError.ERROR_BAD_VALUE), operationId))
            } catch (_: Exception) {
                future.set(receiptResult(failureResult("OUTPUT_ERROR"), operationId))
            }
        }
        activeCommand = job
        future.addListener({ if (future.isCancelled) job.cancel() }, Executor { it.run() })
        job.invokeOnCompletion {
            if (!future.isDone) future.cancel(false)
            if (activeCommand === job) activeCommand = null
        }
        job.start()
        return future
    }

    private fun receiptResult(result: SessionResult, operationId: String?): SessionResult {
        if (operationId == null) return result
        val extras = Bundle(result.extras).apply { putAll(PlaybackCommands.operationReceiptArguments(operationId)) }
        return SessionResult(result.resultCode, extras)
    }

    private suspend fun closePlaylistProducer(producer: PlaylistQueueProducer): Boolean = withContext(NonCancellable) {
        try {
            withTimeoutOrNull(PLAYLIST_CLOSE_TIMEOUT_MS) { producer.close(); true } == true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun retryRetiredPlaylistProducer() {
        val producer = retiredPlaylistProducer ?: return
        if (!closePlaylistProducer(producer)) throw PlaylistException(PlaylistIssue.BUSY)
        if (retiredPlaylistProducer === producer) retiredPlaylistProducer = null
    }

    private fun playbackResult(): SessionResult {
        val failure = coordinator?.state?.value?.failure
        if (failure != null) return failureResult(failure.issue.name, failure.queueStoreIssue?.name)
        val issue = playbackOwner?.state?.value?.issue
        return if (issue == null) SessionResult(SessionResult.RESULT_SUCCESS) else failureResult(issue.name)
    }

    private fun failureResult(issue: String, storeIssue: String? = null): SessionResult {
        val extras = Bundle().apply {
            putString(PlaybackCommands.FAILURE, issue)
            storeIssue?.let { putString(PlaybackCommands.STORE_ISSUE, it) }
        }
        return SessionResult(SessionError.ERROR_INVALID_STATE, extras)
    }

    private fun playerCommands(session: MediaSession, controller: MediaSession.ControllerInfo): Player.Commands {
        val commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
            .remove(Player.COMMAND_SET_MEDIA_ITEM)
            .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
            .remove(Player.COMMAND_SET_SPEED_AND_PITCH)
        val externalButtonsDisabled = !mediaButtonsEnabled && controller.uid != Process.myUid() &&
            !session.isMediaNotificationController(controller)
        if (initializing || externalButtonsDisabled) {
            commands.remove(Player.COMMAND_PLAY_PAUSE).remove(Player.COMMAND_PREPARE).remove(Player.COMMAND_STOP)
                .remove(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM).remove(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
                .remove(Player.COMMAND_SEEK_TO_MEDIA_ITEM).remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS).remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_TO_NEXT).remove(Player.COMMAND_SEEK_BACK).remove(Player.COMMAND_SEEK_FORWARD)
        }
        return commands.build()
    }

    private fun sessionCommands(controller: MediaSession.ControllerInfo): SessionCommands {
        val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
        if (controller.uid == Process.myUid()) {
            commands.add(PlaybackCommands.replaceLocal).add(PlaybackCommands.replaceLibraryView)
                .add(PlaybackCommands.playlistQueue)
                .add(PlaybackCommands.selectEntry).add(PlaybackCommands.retryRestore).add(PlaybackCommands.mutateQueue)
                .add(PlaybackCommands.beginSeek).add(PlaybackCommands.seekQueue)
        }
        return commands.build()
    }

    private fun refreshControllerCommands() {
        val session = mediaSession ?: return
        session.connectedControllers.forEach { controller ->
            session.setAvailableCommands(controller, sessionCommands(controller), playerCommands(session, controller))
        }
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
            // 任一控制器都不能绕过完整队列发布直接修改后端窗口或全局速度。
            result.setAvailablePlayerCommands(playerCommands(session, controller))
            result.setAvailableSessionCommands(sessionCommands(controller))
            return result.build()
        }

        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean = initializing || !mediaButtonsEnabled && !session.isMediaNotificationController(controllerInfo)

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (controller.uid != Process.myUid()) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            }
            return when (customCommand.customAction) {
                PlaybackCommands.playlistQueue.customAction -> {
                    val command = try { PlaybackCommands.readPlaylist(args) }
                    catch (_: RuntimeException) {
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    }
                    commandFuture(command.operationId) {
                        retryRetiredPlaylistProducer()
                        currentCoroutineContext().ensureActive()
                        val application = application as MeloXApplication
                        val producer = PlaylistQueueProducer(application.playlists, application.library,
                            application.playlistQueueMapper, command)
                        try {
                            producer.commit(checkNotNull(coordinator))
                            SessionResult(SessionResult.RESULT_SUCCESS)
                        } finally {
                            if (!closePlaylistProducer(producer)) {
                                retiredPlaylistProducer = producer
                                // 已完成的持久提交仍被接受；资源失败阻止下一次创建新lease。
                                try {
                                    mediaSession?.sendError(SessionError(SessionError.ERROR_INVALID_STATE, "PLAYLIST_VIEW_CLEANUP_FAILED"))
                                } catch (_: RuntimeException) {
                                    // 会话销毁不掩盖提交/取消的原结果；producer仍保留待回收。
                                }
                            }
                        }
                    }
                }
                PlaybackCommands.replaceLocal.customAction -> commandFuture {
                    val command = PlaybackCommands.readLocal(args)
                    val queues = checkNotNull(coordinator)
                    queues.replace(command.request, command.source, explicitPlay = true)
                    // Durable publication remains successful when a later decoder/output fails.
                    // Runtime failures travel through sendError and the observed backend extras.
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }
                PlaybackCommands.replaceLibraryView.customAction -> commandFuture {
                    val command = PlaybackCommands.readLibrary(args)
                    val application = application as MeloXApplication
                    val producer = LibraryQueueProducer(application.library, command)
                    var primary: Exception? = null
                    try {
                        producer.commit(checkNotNull(coordinator))
                        SessionResult(SessionResult.RESULT_SUCCESS)
                    } catch (error: Exception) {
                        primary = error
                        throw error
                    } finally {
                        try { withContext(NonCancellable) { producer.close() } }
                        catch (cleanup: Exception) {
                            val original = primary
                            if (original != null) original.addSuppressed(cleanup)
                            else mediaSession?.sendError(SessionError(SessionError.ERROR_INVALID_STATE, "LIBRARY_VIEW_CLEANUP_FAILED"))
                        }
                    }
                }
                PlaybackCommands.selectEntry.customAction -> commandFuture {
                    val command = PlaybackCommands.readSelection(args)
                    val queues = checkNotNull(coordinator)
                    queues.select(command.generationId, command.queueRevision, command.entryId, explicitPlay = true)
                    playbackResult()
                }
                PlaybackCommands.mutateQueue.customAction -> commandFuture {
                    checkNotNull(coordinator).mutate(PlaybackCommands.readMutation(args), source = null, explicitPlay = false)
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }
                PlaybackCommands.beginSeek.customAction -> {
                    if (destroyed || initializing || playbackOwner?.state?.value?.switching == true || activeCommand?.isActive == true) {
                        Futures.immediateFuture(failureResult(if (destroyed) "CLOSED" else "BUSY"))
                    } else try {
                        PlaybackCommands.readBeginSeek(args)
                        val queues = checkNotNull(coordinator)
                        val binding = queues.seekBinding()
                        if (binding != null) Futures.immediateFuture(SessionResult(
                            SessionResult.RESULT_SUCCESS, PlaybackCommands.seekBindingArguments(binding),
                        )) else {
                            val state = queues.sessionPlayer.playbackState
                            val notReady = queues.state.value.preparing || state != Player.STATE_READY && state != Player.STATE_ENDED
                            val extras = Bundle().apply {
                                putString(PlaybackCommands.FAILURE, if (notReady) "NOT_READY" else "NOT_SUPPORTED")
                            }
                            Futures.immediateFuture(SessionResult(
                                if (notReady) SessionError.ERROR_INVALID_STATE else SessionError.ERROR_NOT_SUPPORTED, extras,
                            ))
                        }
                    } catch (_: IllegalArgumentException) {
                        Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    } catch (error: QueuePlaybackException) {
                        Futures.immediateFuture(failureResult(error.failure.issue.name, error.failure.queueStoreIssue?.name))
                    } catch (_: Exception) {
                        Futures.immediateFuture(failureResult("OUTPUT_ERROR"))
                    }
                }
                PlaybackCommands.seekQueue.customAction -> commandFuture {
                    checkNotNull(coordinator).seek(PlaybackCommands.readSeek(args))
                    playbackResult()
                }
                PlaybackCommands.retryRestore.customAction -> commandFuture {
                    val rebuilt = checkNotNull(playbackOwner).retryBackendIfNeeded()
                    if (!rebuilt) checkNotNull(coordinator).initialize()
                    playbackResult()
                }
                else -> super.onCustomCommand(session, controller, customCommand, args)
            }
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL = "melox_playback"
        private const val PLAYLIST_CLOSE_TIMEOUT_MS = 2_500L
        private const val PLAYLIST_SHUTDOWN_TIMEOUT_MS = 3_000L
    }
}
