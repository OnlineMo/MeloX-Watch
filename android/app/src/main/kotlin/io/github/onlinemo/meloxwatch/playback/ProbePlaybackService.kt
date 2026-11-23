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
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.MediaMetadata
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
import io.github.onlinemo.meloxwatch.data.backup.BackupException
import io.github.onlinemo.meloxwatch.data.backup.BackupIssue
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationException
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationIssue
import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistException
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesState
import io.github.onlinemo.meloxwatch.data.sleep.SleepTimerStoreFactory
import io.github.onlinemo.meloxwatch.data.sleep.readSleepTimerBootId
import io.github.onlinemo.meloxwatch.playback.sleep.AndroidSleepTimerNotification
import io.github.onlinemo.meloxwatch.playback.sleep.AndroidSleepTimerScheduler
import io.github.onlinemo.meloxwatch.playback.sleep.ServiceSleepTimer
import io.github.onlinemo.meloxwatch.playback.sleep.SleepTimerClockSample
import io.github.onlinemo.meloxwatch.playback.sleep.SleepTimerCommands
import io.github.onlinemo.meloxwatch.playback.sleep.SleepTimerSessionState
import io.github.onlinemo.meloxwatch.data.queue.QueueStoreException
import io.github.onlinemo.meloxwatch.data.queue.QueueStoreIssue
import io.github.onlinemo.meloxwatch.model.OnlineCatalogException
import io.github.onlinemo.meloxwatch.playback.route.MediaVolumeCommands
import io.github.onlinemo.meloxwatch.playback.route.MediaVolumeResult
import io.github.onlinemo.meloxwatch.playback.route.ServiceMediaVolumeIssue
import io.github.onlinemo.meloxwatch.playback.resources.SourcePlaybackResolver
import io.github.onlinemo.meloxwatch.playback.resources.toSessionExtras
import io.github.onlinemo.meloxwatch.playback.dsp.ServiceDspSettingsOwner
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 由 DEV-004-A 演进的唯一音频所有者；页面只发送命令，队列先持久发布再作用于内核。
 *
 * 服务统一持有后端、焦点、noisy 和前台通知；后端切换不替换会话或复制播放器。
 */
@UnstableApi
class ProbePlaybackService : MediaSessionService() {
    private class PlaybackStartup {
        var bootId: String? = null
        var bootIdentityRead = false
        var dspInitialized = false
        var preferencesInitialized = false
    }

    private var mediaSession: MediaSession? = null
    private var playbackOwner: ServicePlaybackOwner? = null
    private var bitmapLoader: LocalSessionBitmapLoader? = null
    private var coordinator: QueuePlaybackCoordinator? = null
    private var listening: ServiceListeningObserver? = null
    private var sleepTimer: ServiceSleepTimer? = null
    private var dspSettingsOwner: ServiceDspSettingsOwner? = null
    private var playbackStartup: PlaybackStartup? = null
    private var lyricsPlaybackBinding: AutoCloseable? = null
    private var timerSaveCommand: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeCommand: Job? = null
    private var initialization: Job? = null
    private var extrasObserver: Job? = null
    private var routeStateObserver: Job? = null
    private var seekStateObserver: Job? = null
    private var commandPreferencesObserver: Job? = null
    private var onlineAccountObserver: Job? = null
    private var resourceStatusObserver: Job? = null
    private var initializing = true
    private var initializationComplete = false
    private var mediaButtonsEnabled = true
    private var destroyed = false
    private var quiescing = false
    private var resourcesClosed = false
    private var closing: Deferred<Unit>? = null
    private var retiredPlaylistProducer: PlaylistQueueProducer? = null
    private val mediaVolumeCommands = ServiceMediaVolumeCommandHandler(
        ownerUid = Process.myUid(),
        read = { withMediaVolumeOwner(ServicePlaybackOwner::readMediaVolume) },
        set = { request -> withMediaVolumeOwner { owner -> owner.setMediaVolume(request) } },
        adjust = { delta -> withMediaVolumeOwner { owner -> owner.adjustMediaVolume(delta) } },
        onResult = ::refreshSessionState,
    )

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
        createPlaybackResources()
    }

    /** 新 controller 连接可重建空 owner；初始化只恢复暂停状态。 */
    private fun createPlaybackResources() {
        val application = application as MeloXApplication
        if (!application.filePlaybackHost.attach(this, ::closeForFileMutation)) {
            // 此入口尚未分配资源；围栏解除后，同一服务可再次申请空 owner。
            resourcesClosed = true
            initializing = false
            return
        }
        initializing = true
        initializationComplete = false
        quiescing = false
        resourcesClosed = false
        closing = null
        val dsp = ServiceDspSettingsOwner(application.dspSettings, application.dspRuntime, serviceScope)
        dspSettingsOwner = dsp
        val owner = ServicePlaybackOwner(this, serviceScope, application.preferences, application.localProviderIo,
            ensureForeground = ::ensurePlaybackForeground,
            onFailure = { issue ->
                mediaSession?.sendError(SessionError(SessionError.ERROR_INVALID_STATE, "BACKEND_${issue.name}"))
            },
            runtimeResources = application.runtimePlaybackResources,
            mayCreateBackend = { application.filePlaybackHost.allowsOwner(this) },
            dspRuntime = application.dspRuntime,
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
            onUserPause = {
                owner.userPause()
                sleepTimer?.onUserPause()
            },
            onUserIntentChanged = owner::userIntentChanged,
            onManualTrackChange = { sleepTimer?.onManualTrackChange() },
            onRetainedTrackBinding = { previous, current -> sleepTimer?.onRetainedTrackBinding(previous, current) },
            beforeNaturalAdvance = { ending -> sleepTimer?.beforeNaturalAdvance(ending) ?: true },
            mayContinuousPlayback = {
                sleepTimer?.state?.value?.let { it.initialized && it.timer.active == null } == true
            },
            mayBeginContinuousRound = statistics::canBeginContinuousRound,
            onContinuousRoundChanged = statistics::beginContinuousRound,
            sourceResolver = SourcePlaybackResolver(LocalPlaybackResolver(application)::resolve) {
                application.onlineMusic().playbackResolver
            },
            resolveMetadata = { entry ->
                val accountId = entry.accountId
                if (entry.trackKey.providerId == "netease" && accountId != null) {
                    application.existingOnlineCatalog()?.catalog?.presentation(accountId, entry.trackKey.trackId)?.let { metadata ->
                        MediaMetadata.Builder().setTitle(metadata.title ?: entry.displayName).setArtist(metadata.artist)
                            .setAlbumTitle(metadata.album).build()
                    }
                } else null
            },
        )
        coordinator = queues
        statistics.onContinuousRoundReady = queues::refreshContinuousBoundary
        owner.attach(queues, statistics)
        onlineAccountObserver = serviceScope.launch {
            application.onlineAccountChanges.revision.collect { if (!destroyed) queues.onAccountStateChanged() }
        }
        val startup = PlaybackStartup()
        playbackStartup = startup
        val timer = ServiceSleepTimer(SleepTimerStoreFactory.get(application), serviceScope,
            now = { SleepTimerClockSample(startup.bootId, SystemClock.elapsedRealtime()) },
            currentTrack = queues::sleepTimerTrackBinding,
            scheduler = AndroidSleepTimerScheduler(),
            notification = AndroidSleepTimerNotification(this),
            pausePlayback = {
                owner.userPause()
                queues.pauseForSleepTimer()
            },
            cancelResumeIntent = owner::userPause,
            onChanged = {
                queues.refreshContinuousBoundary()
                refreshSessionState()
            },
        )
        sleepTimer = timer
        val artwork = LocalSessionBitmapLoader(this, application.localProviderIo, serviceScope)
        bitmapLoader = artwork

        val builder = MediaSession.Builder(this, queues.sessionPlayer)
            .setCallback(LocalPlaybackCallback())
            .setBitmapLoader(artwork)
            .setSessionExtras(sessionExtras())
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
                if (!destroyed) {
                    refreshSessionState()
                    refreshControllerCommands()
                }
            }
        }
        routeStateObserver = serviceScope.launch {
            owner.routeState.collect { if (!destroyed) refreshSessionState() }
        }
        seekStateObserver = serviceScope.launch {
            queues.seekState.collect { if (!destroyed) refreshSessionState() }
        }
        resourceStatusObserver = serviceScope.launch {
            queues.state.map { it.resourceStatus }.distinctUntilChanged().collect {
                if (!destroyed) refreshSessionState()
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
                if (!destroyed) {
                    val summaryEnabled = when (saved) {
                        is PreferencesState.Ready -> saved.preferences.controls.sleepTimerSummary
                        is PreferencesState.Unavailable -> saved.lastKnown?.controls?.sleepTimerSummary
                            ?: owner.currentPreferences.controls.sleepTimerSummary
                        PreferencesState.Loading -> false
                    }
                    timer.setSummaryEnabled(summaryEnabled)
                }
            }
        }
        initialization = serviceScope.launch {
            try {
                initializePlaybackResources(retryBackend = false)
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

    /** 首次启动和显式恢复共用分阶段入口；失败重试保留本轮 owner、boot 证据和已完成阶段。 */
    private suspend fun initializePlaybackResources(retryBackend: Boolean) {
        val application = application as MeloXApplication
        val owner = checkNotNull(playbackOwner)
        val queues = checkNotNull(coordinator)
        val timer = checkNotNull(sleepTimer)
        val dsp = checkNotNull(dspSettingsOwner)
        val startup = checkNotNull(playbackStartup)
        initializing = true
        initializationComplete = false
        try {
            application.requireBackupStorageReady()
            application.fileOperations.assertPlaybackReadable()
            currentCoroutineContext().ensureActive()
            if (!startup.bootIdentityRead) {
                startup.bootId = readSleepTimerBootId(this)
                startup.bootIdentityRead = true
            }
            // Timer.initialize 自身保留已恢复的 lease；再次重试不能重新启用磁盘旧定时。
            timer.initialize()
            if (!startup.dspInitialized) {
                dsp.initialize()
                startup.dspInitialized = true
            }
            if (!startup.preferencesInitialized) {
                owner.initializePreferences()
                startup.preferencesInitialized = true
            }
            val restoredBackend = retryBackend && owner.retryBackendIfNeeded()
            if (!restoredBackend) queues.initialize()
            currentCoroutineContext().ensureActive()
            if (destroyed || quiescing || playbackOwner !== owner) {
                throw CancellationException("Playback service owner retired")
            }
            initializationComplete = true
            if (lyricsPlaybackBinding == null) {
                lyricsPlaybackBinding = application.lyricsEntryOwner.playback.bindService(queues.sessionPlayer)
            }
            dsp.observe()
            owner.observePreferences()
        } finally {
            if (!destroyed && !quiescing && playbackOwner === owner) {
                initializing = false
                refreshSessionState()
                refreshControllerCommands()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        val application = application as MeloXApplication
        if (mediaSession == null && !destroyed && resourcesClosed && !application.filePlaybackHost.isReadBlocked()) {
            createPlaybackResources()
        }
        return mediaSession
    }

    override fun onDestroy() {
        destroyed = true
        dspSettingsOwner?.close()
        dspSettingsOwner = null
        startClosure()
        super.onDestroy()
    }

    /** 由确认关闭仍绑定的同一个服务；单独 stopService 不能证明资源已释放。 */
    private suspend fun closeForFileMutation() {
        // 收尾 job 不受调用方取消影响，host 独立限制等候时间。
        startClosure().await()
    }

    private fun startClosure(): Deferred<Unit> {
        closing?.let { return it }
        quiescing = true
        initializationComplete = false
        // Native closure retires the observer before a later empty-owner rebuild.
        dspSettingsOwner?.close()
        dspSettingsOwner = null
        val application = application as MeloXApplication
        val lyricsBinding = lyricsPlaybackBinding
        lyricsPlaybackBinding = null
        val pendingJobs = listOfNotNull(activeCommand, initialization, extrasObserver, routeStateObserver,
            seekStateObserver, commandPreferencesObserver, onlineAccountObserver, resourceStatusObserver, timerSaveCommand).distinct()
        pendingJobs.forEach { it.cancel() }
        val session = mediaSession
        val owner = playbackOwner
        val artwork = bitmapLoader
        val queues = coordinator
        val statistics = listening
        val timer = sleepTimer
        mediaSession = null
        coordinator = null
        playbackOwner = null
        bitmapLoader = null
        listening = null
        sleepTimer = null
        playbackStartup = null
        var failure: Throwable? = null
        fun captureFailure(error: Throwable) {
            val first = failure
            if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
        }
        fun cleanup(action: () -> Unit) { try { action() } catch (error: Throwable) { captureFailure(error) } }
        cleanup { application.stopBluetoothLyricsIfStarted() }
        cleanup { lyricsBinding?.close() }
        cleanup { owner?.userPause() }
        cleanup { timer?.detach() }
        cleanup { session?.player?.pause() }
        cleanup { statistics?.detach() }
        cleanup { queues?.detach() }
        cleanup { session?.release() }
        cleanup { owner?.close() }
        cleanup { artwork?.close() }
        cleanup { stopForeground(STOP_FOREGROUND_REMOVE) }
        val completion = serviceScope.async(NonCancellable, start = CoroutineStart.LAZY) {
            suspend fun finish(action: suspend () -> Unit) {
                try { action() } catch (error: Throwable) { captureFailure(error) }
            }
            try {
                for (pending in pendingJobs) finish { pending.join() }
                finish { application.quiesceBluetoothLyricsIfStarted() }
                val producer = retiredPlaylistProducer
                if (producer != null) finish {
                    if (!closePlaylistProducer(producer)) throw FileOperationException(FileOperationIssue.PLAYBACK_RELEASE_UNCONFIRMED)
                    if (retiredPlaylistProducer === producer) retiredPlaylistProducer = null
                }
                finish { owner?.awaitClosed() }
                finish { queues?.close() }
                finish { timer?.close() }
                finish { statistics?.close() }
                finish { application.localProviderIo.awaitIdle() }
                failure?.let { throw FileOperationException(FileOperationIssue.PLAYBACK_RELEASE_UNCONFIRMED, it) }
                resourcesClosed = true
                application.filePlaybackHost.detach(this@ProbePlaybackService)
            } finally {
                if (destroyed) serviceScope.cancel()
            }
        }
        closing = completion
        completion.start()
        return completion
    }

    private fun ensurePlaybackForeground(): Boolean {
        val session = mediaSession ?: return false
        if (destroyed || quiescing || (application as MeloXApplication).filePlaybackHost.isReadBlocked()) return false
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
        if (destroyed || quiescing || initializing || !initializationComplete ||
            (application as MeloXApplication).filePlaybackHost.isReadBlocked()) return false
        val accepted = sleepTimer?.beforePlay() == true && playbackOwner?.requestPlay() == true
        if (!accepted) mediaSession?.let { session ->
            // A denied or delayed request stays paused. Media3 stops any warm-up foreground
            // promotion; no cached boolean can incorrectly authorize a later focus request.
            try { onUpdateNotification(session, false) }
            catch (_: RuntimeException) { playbackOwner?.foregroundDenied() }
        }
        return accepted
    }

    private fun sessionExtras(): Bundle = Bundle(playbackOwner?.sessionExtras() ?: Bundle.EMPTY).apply {
        coordinator?.let { putAll(it.seekSessionState().toSessionExtras()) }
        coordinator?.state?.value?.resourceStatus?.let { putAll(it.toSessionExtras()) }
        sleepTimer?.let { putAll(SleepTimerSessionState.fromService(it.state.value).toSessionExtras()) }
    }

    private fun withMediaVolumeOwner(operation: (ServicePlaybackOwner) -> MediaVolumeResult): MediaVolumeResult {
        val owner = playbackOwner.takeUnless { destroyed || quiescing }
        return if (owner == null) MediaVolumeResult(null, ServiceMediaVolumeIssue.UNAVAILABLE) else operation(owner)
    }

    private fun refreshSessionState() {
        if (!destroyed) mediaSession?.setSessionExtras(sessionExtras())
    }

    /** 定时取消不等待队列/provider命令；运行时接受与独立写盘状态分别呈现。 */
    private fun timerResult(operation: (ServiceSleepTimer) -> Boolean): ListenableFuture<SessionResult> {
        if (destroyed || quiescing) return Futures.immediateFuture(failureResult("CLOSED"))
        val timer = sleepTimer
        if (initializing || timer == null || !timer.state.value.initialized) return Futures.immediateFuture(failureResult("BUSY"))
        return try {
            Futures.immediateFuture(if (operation(timer)) SessionResult(SessionResult.RESULT_SUCCESS) else failureResult("CONFLICT"))
        } catch (_: IllegalArgumentException) {
            Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
        }
    }

    private fun retryTimerSave(arguments: Bundle): ListenableFuture<SessionResult> {
        val timer = sleepTimer ?: return Futures.immediateFuture(failureResult("NOT_READY"))
        val expected = try { SleepTimerCommands.readExpected(arguments) }
        catch (_: IllegalArgumentException) { return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE)) }
        if (destroyed || quiescing || initializing || timerSaveCommand != null || expected != timer.generationId) {
            return Futures.immediateFuture(failureResult(if (destroyed) "CLOSED" else "CONFLICT"))
        }
        val future = SettableFuture.create<SessionResult>()
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                future.set(if (timer.retryPersistence(expected)) SessionResult(SessionResult.RESULT_SUCCESS) else failureResult("NOT_READY"))
            } catch (error: CancellationException) {
                future.cancel(false)
                throw error
            }
        }
        timerSaveCommand = job
        future.addListener({ if (future.isCancelled) job.cancel() }, Executor { it.run() })
        job.invokeOnCompletion {
            if (!future.isDone) future.cancel(false)
            if (timerSaveCommand === job) timerSaveCommand = null
        }
        job.start()
        return future
    }

    private fun commandFuture(
        operationId: String? = null,
        retryInitialization: Boolean = false,
        operation: suspend () -> SessionResult,
    ): ListenableFuture<SessionResult> {
        if (destroyed || quiescing) return Futures.immediateFuture(receiptResult(failureResult("CLOSED"), operationId))
        if (!retryInitialization && (application as MeloXApplication).filePlaybackHost.isReadBlocked()) {
            return Futures.immediateFuture(receiptResult(failureResult("RECOVERY_REQUIRED"), operationId))
        }
        // Cancellation can still own noncancellable provider/receipt cleanup. Only completion
        // releases this slot; isActive would admit a new command while that cleanup is running.
        if (initializing || playbackOwner?.state?.value?.switching == true || activeCommand != null) {
            return Futures.immediateFuture(receiptResult(failureResult("BUSY"), operationId))
        }
        if (!retryInitialization && !initializationComplete) {
            return Futures.immediateFuture(receiptResult(failureResult("NOT_READY"), operationId))
        }
        val future = SettableFuture.create<SessionResult>()
        fun complete(result: SessionResult) {
            // Publish consumed previews and new owners before acknowledging the command.
            // Position/checkpoint updates retain the same owner, including our own seek.
            refreshSessionState()
            future.set(receiptResult(result, operationId))
        }
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            try {
                complete(operation())
            } catch (error: CancellationException) {
                future.cancel(false)
                throw error
            } catch (error: QueuePlaybackException) {
                complete(failureResult(error.failure.issue.name, error.failure.queueStoreIssue?.name))
            } catch (error: QueueStoreException) {
                val issue = if (error.issue == QueueStoreIssue.INVALID_INPUT) "CONFLICT" else error.issue.name
                complete(failureResult(issue, error.issue.name))
            } catch (error: OnlineCatalogException) {
                complete(failureResult(error.issue.name))
            } catch (error: BackupException) {
                complete(failureResult(if (error.issue == BackupIssue.BUSY) "BUSY" else "RECOVERY_REQUIRED"))
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
                complete(failureResult(issue))
            } catch (error: PlaylistException) {
                complete(failureResult(error.issue.name))
            } catch (_: IllegalArgumentException) {
                complete(SessionResult(SessionError.ERROR_BAD_VALUE))
            } catch (_: Exception) {
                complete(failureResult("OUTPUT_ERROR"))
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
            withTimeoutOrNull(PLAYLIST_CLOSE_TIMEOUT_MS) {
                producer.close()
                true
            } == true
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
        if (initializing || !initializationComplete || quiescing || (application as MeloXApplication).filePlaybackHost.isReadBlocked() ||
            playbackOwner?.hasUsableBackend != true || externalButtonsDisabled) {
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
                .add(OnlineQueueCommands.play).add(OnlineQueueCommands.cancel)
                .add(PlaybackCommands.playlistQueue)
                .add(PlaybackCommands.selectEntry).add(PlaybackCommands.retryRestore).add(PlaybackCommands.mutateQueue)
                .add(PlaybackCommands.beginSeek).add(PlaybackCommands.seekQueue)
                .add(SleepTimerCommands.arm).add(SleepTimerCommands.cancel)
                .add(SleepTimerCommands.retrySave).add(SleepTimerCommands.refresh)
                .add(MediaVolumeCommands.read).add(MediaVolumeCommands.set).add(MediaVolumeCommands.adjust)
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
        ): Boolean = initializing || !initializationComplete || quiescing ||
            (application as MeloXApplication).filePlaybackHost.isReadBlocked() ||
            !mediaButtonsEnabled && !session.isMediaNotificationController(controllerInfo)

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            mediaVolumeCommands.handle(controller.uid, customCommand.customAction, args)?.let { result ->
                return Futures.immediateFuture(result)
            }
            if (controller.uid != Process.myUid()) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
            }
            return when (customCommand.customAction) {
                OnlineQueueCommands.cancel.customAction -> {
                    val operationId = try { OnlineQueueCommands.readCancellation(args) }
                    catch (_: RuntimeException) { return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE)) }
                    val cancelled = (application as MeloXApplication).onlineQueues.cancel(operationId)
                    Futures.immediateFuture(if (cancelled) SessionResult(SessionResult.RESULT_SUCCESS) else failureResult("STALE_VIEW"))
                }
                OnlineQueueCommands.play.customAction -> {
                    val command = try { OnlineQueueCommands.read(args) }
                    catch (_: RuntimeException) { return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE)) }
                    commandFuture(command.operationId) {
                        val directory = (application as MeloXApplication).onlineQueues
                        if (directory.wasAccepted(command)) return@commandFuture SessionResult(SessionResult.RESULT_SUCCESS)
                        val owner = checkNotNull(currentCoroutineContext()[Job])
                        val claimed = directory.claim(command) { owner.cancel() }
                        try {
                            claimed.commit(checkNotNull(coordinator))
                            SessionResult(SessionResult.RESULT_SUCCESS)
                        } finally { claimed.close() }
                    }
                }
                SleepTimerCommands.arm.customAction -> timerResult { timer ->
                    val command = SleepTimerCommands.readArm(args)
                    timer.arm(command.minutes, command.mode, command.expectedGeneration)
                }
                SleepTimerCommands.cancel.customAction -> timerResult { timer ->
                    timer.cancel(SleepTimerCommands.readExpected(args))
                }
                SleepTimerCommands.refresh.customAction -> timerResult { timer ->
                    SleepTimerCommands.readRefresh(args)
                    timer.refresh()
                    true
                }
                SleepTimerCommands.retrySave.customAction -> retryTimerSave(args)
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
                    if (destroyed || quiescing || initializing || !initializationComplete ||
                        (application as MeloXApplication).filePlaybackHost.isReadBlocked() ||
                        playbackOwner?.state?.value?.switching == true || activeCommand != null) {
                        Futures.immediateFuture(failureResult(if (destroyed) "CLOSED" else "BUSY"))
                    } else try {
                        PlaybackCommands.readBeginSeek(args)
                        val queues = checkNotNull(coordinator)
                        val binding = queues.seekBinding()
                        refreshSessionState()
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
                // 恢复命令可检查尚未加载的 file journal；内部仍先核验 Backup 和真实释放围栏。
                PlaybackCommands.retryRestore.customAction -> commandFuture(retryInitialization = true) {
                    initializePlaybackResources(retryBackend = true)
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
    }
}
