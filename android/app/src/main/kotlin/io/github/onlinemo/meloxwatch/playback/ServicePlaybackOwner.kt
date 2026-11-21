package io.github.onlinemo.meloxwatch.playback

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesState
import io.github.onlinemo.meloxwatch.data.preferences.UserPreferencesRepository
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.model.PlaybackBackendKind
import io.github.onlinemo.meloxwatch.model.UserPreferences
import io.github.onlinemo.meloxwatch.playback.route.AndroidMusicVolume
import io.github.onlinemo.meloxwatch.playback.route.MediaVolumeResult
import io.github.onlinemo.meloxwatch.playback.route.MediaVolumeSetRequest
import io.github.onlinemo.meloxwatch.playback.route.MusicVolumePort
import io.github.onlinemo.meloxwatch.playback.route.PlaybackAudioRoute
import io.github.onlinemo.meloxwatch.playback.route.PlaybackRouteSessionState
import io.github.onlinemo.meloxwatch.playback.route.RoutingRenderersFactory
import io.github.onlinemo.meloxwatch.playback.route.ServiceMediaVolume
import io.github.onlinemo.meloxwatch.playback.route.ServiceMediaVolumeIssue
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 服务在主线程创建并持有唯一后端，每个内核 lease 绑定一份实际路由观察者。
 *
 * 切换先释放旧实例和观察者，再重建目标或回退后端；对外状态不携带原生对象或设备身份。
 */
@UnstableApi
internal class ServicePlaybackOwner(
    context: Context,
    private val scope: CoroutineScope,
    private val preferences: UserPreferencesRepository,
    private val localIo: LocalProviderIo,
    ensureForeground: () -> Boolean,
    private val onFailure: (PlaybackBackendIssue) -> Unit,
    private val releaseGuard: PlaybackEngineReleaseGuard = PlaybackEngineReleaseGuard.process,
    musicVolumePort: MusicVolumePort = AndroidMusicVolume(context.applicationContext),
) {
    private class Backend(
        val kind: PlaybackBackendKind,
        val player: Player,
        val operations: PlaybackBackendOperations,
        val releaseGuard: PlaybackEngineReleaseGuard,
        val releaseLease: PlaybackEngineReleaseGuard.Lease,
        val route: PlaybackAudioRoute,
        val media3: Media3PlaybackOperations? = null,
        val system: SystemMediaPlayerAdapter? = null,
    ) {
        private val release = ScheduledPlaybackRelease(releaseGuard, releaseLease, player,
            closeOperations = { media3?.close() },
            releasePlayer = { if (system != null) system.releaseOwnedPlayer() else player.release() })

        fun releaseLater() = run {
            route.close()
            release.start()
        }

        suspend fun releaseAndAwait() = withContext(NonCancellable) { releaseLater().await() }
    }

    private val applicationContext = context.applicationContext.also { checkMain() }
    private val routeHandler = Handler(Looper.getMainLooper())
    private val routeExecutor = Executor { command -> routeHandler.post(command) }
    private val _state = MutableStateFlow(PlaybackBackendState())
    val state = _state.asStateFlow()
    private val _routeState = MutableStateFlow(PlaybackRouteSessionState())
    val routeState = _routeState.asStateFlow()
    private val mediaVolume = ServiceMediaVolume(musicVolumePort, ::readCurrentRoute)
    private var quarantined: Backend? = null
    private val pausedBackend = QuarantinedPlaybackBackend(releaseGuard)
    private var active: Backend? = createInitialBackend()
    private var coordinator: QueuePlaybackCoordinator? = null
    private var listening: ServiceListeningObserver? = null
    private var observer: Player.Listener? = null
    private var speedObserver: Job? = null
    private var preferenceObserver: Job? = null
    // An explicit retry and the preference observer must not freeze the same queue concurrently.
    private val preferenceTransactions = Mutex()
    private var closed = false
    private var preferencesInitialized = false
    var currentPreferences: UserPreferences = UserPreferences()
        private set
    private val focus = try { PlaybackAudioFocusOwner(applicationContext,
        player = { active?.player },
        backendGeneration = { _state.value.generation },
        ensureForeground = ensureForeground,
        pauseQueue = { coordinator?.pauseForInterruption() },
        checkpoint = { coordinator?.requestCheckpoint() },
        resumeQueue = { coordinator?.playFromSession() },
        onFailure = ::report,
    ) } catch (error: Exception) {
        val failed = active
        active = null
        preserveFailure(error) { failed?.releaseLater() }
        throw error
    }

    init {
        checkMain()
        try {
            _state.value = _state.value.copy(actualBackend = active?.kind, generation = 1L)
            active?.let(::bindObserver)
        } catch (error: Exception) {
            preserveFailure(error, focus::close)
            preserveFailure(error, ::unbindObserver)
            val failed = active
            active = null
            preserveFailure(error) { failed?.releaseLater() }
            throw error
        }
    }

    val initialPlayer: Player get() = active?.player ?: pausedBackend
    val initialOperations: PlaybackBackendOperations get() = active?.operations ?: pausedBackend
    val hasUsableBackend: Boolean get() = !closed && active != null && !_state.value.switching && !releaseGuard.hasUnconfirmedRelease()

    fun attach(queues: QueuePlaybackCoordinator, statistics: ServiceListeningObserver) {
        checkMain()
        check(coordinator == null && listening == null)
        coordinator = queues
        listening = statistics
    }

    /** 会话seek使用协调器的绑定代次；内部焦点/监听代次不与它混用。 */
    fun sessionExtras(): Bundle {
        checkMain()
        val queues = checkNotNull(coordinator)
        return _state.value.copy(generation = queues.sessionBackendGeneration()).toSessionExtras().apply {
            putAll(currentRouteSessionState().toSessionExtras())
        }
    }

    /** 主线程新读当前路由、媒体流约束和读回路由；关闭后不再访问平台。 */
    fun readMediaVolume(): MediaVolumeResult {
        checkMain()
        return if (closed) unavailableMediaVolume() else mediaVolume.read()
    }

    /** 主线程绝对值绑定请求快照；平台调用中路由仍可能变化，writeAttempted 不表示可回滚已写音量。 */
    fun setMediaVolume(request: MediaVolumeSetRequest): MediaVolumeResult {
        checkMain()
        return if (closed) unavailableMediaVolume() else mediaVolume.setAbsolute(request)
    }

    /** 主线程按本次新读媒体流增减一格；暂停或路由未知时也不触发播放。 */
    fun adjustMediaVolume(delta: Int): MediaVolumeResult {
        checkMain()
        require(delta == -1 || delta == 1)
        return if (closed) unavailableMediaVolume() else mediaVolume.adjust(delta)
    }

    suspend fun initializePreferences() {
        val saved = preferences.state.first { it !is PreferencesState.Loading }
        applyPreferences(snapshot(saved))
        preferencesInitialized = true
    }

    fun observePreferences() {
        checkMain()
        if (closed || preferenceObserver != null) return
        preferenceObserver = scope.launch(Dispatchers.Main.immediate) {
            preferences.state.collectLatest { saved ->
                if (saved !is PreferencesState.Loading) {
                    val value = snapshot(saved)
                    if (value != currentPreferences) applyPreferences(value)
                }
            }
        }
    }

    fun requestPlay(): Boolean {
        if (!hasUsableBackend || !preferencesInitialized) return false
        val accepted = focus.requestPlay()
        if (accepted) _state.value = _state.value.copy(issue =
            if (active?.kind == currentPreferences.playbackBackend) null else PlaybackBackendIssue.SWITCH_FAILED)
        active?.let(::updateRouteGate)
        return accepted
    }

    fun userPause() {
        checkMain()
        active?.route?.setPlaybackActive(false)
        refreshRouteState()
        focus.userPause()
    }

    fun userIntentChanged() = focus.cancelResumeIntent()

    fun foregroundDenied() {
        userPause()
        coordinator?.pauseForInterruption()
        report(PlaybackBackendIssue.FOREGROUND_DENIED)
    }

    /** True means this retry handled backend recovery, including a guarded rejection. */
    suspend fun retryBackendIfNeeded(): Boolean = withContext(Dispatchers.Main.immediate) {
        preferenceTransactions.withLock {
            if (closed) return@withLock false
            if (blockedByPriorRelease()) {
                report(PlaybackBackendIssue.SWITCH_FAILED)
                return@withLock true
            }
            val needsBackend = active == null || active?.kind != currentPreferences.playbackBackend || active?.player?.playerError != null
            if (needsBackend) applyPreferencesLocked(currentPreferences, forceRetry = true)
            needsBackend
        }
    }

    fun close() {
        checkMain()
        if (closed) return
        closed = true
        preferenceObserver?.cancel()
        preferenceObserver = null
        val previous = active
        val retained = quarantined
        var failure: Exception? = null
        fun attempt(operation: () -> Unit) {
            try { operation() }
            catch (error: Exception) {
                val first = failure
                if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
            }
        }
        attempt { previous?.route?.close() }
        if (retained !== previous) attempt { retained?.route?.close() }
        refreshRouteState()
        attempt(focus::close)
        attempt { previous?.player?.pause() }
        attempt(::unbindObserver)
        active = null
        quarantined = null
        attempt { previous?.releaseLater() }
        if (retained !== previous) attempt { retained?.releaseLater() }
        attempt(pausedBackend::release)
        _state.value = _state.value.copy(actualBackend = null, switching = false, issue = PlaybackBackendIssue.CLOSED,
            gaplessAvailable = false, generation = _state.value.generation + 1L)
        failure?.let { throw it }
    }

    private suspend fun applyPreferences(saved: UserPreferences) = withContext(Dispatchers.Main.immediate) {
        preferenceTransactions.withLock { applyPreferencesLocked(saved, forceRetry = false) }
    }

    private suspend fun applyPreferencesLocked(saved: UserPreferences, forceRetry: Boolean) {
        if (closed) return
        val backendRequestChanged = saved.playbackBackend != currentPreferences.playbackBackend
        currentPreferences = saved
        _state.value = _state.value.copy(requestedBackend = saved.playbackBackend,
            gaplessRequested = saved.gaplessPlayback,
            speed = _state.value.speed.copy(requestedTenths = saved.playbackSpeedTenths))
        focus.setManagementEnabled(saved.audioFocusManagement)
        if (quarantined != null || blockedByPriorRelease()) {
            report(PlaybackBackendIssue.SWITCH_FAILED)
            return
        }
        val previous = active
        if (previous != null && previous.kind != saved.playbackBackend && (backendRequestChanged || forceRetry) ||
            previous == null && (backendRequestChanged || forceRetry) ||
            previous?.player?.playerError != null && forceRetry) {
            switchBackend(saved)
        } else if (backendRequestChanged && previous?.kind == saved.playbackBackend) {
            _state.value = _state.value.copy(issue = null)
        }
        val current = active ?: return
        applySpeed(current, saved.playbackSpeedTenths)
    }

    private suspend fun applySpeed(backend: Backend, tenths: Int) {
        if (closed || active !== backend) return
        val platform = backend.system
        if (platform != null) {
            platform.requestSpeed(tenths)
            _state.value = _state.value.copy(speed = platform.speedState.value)
            return
        }
        val operations = checkNotNull(backend.media3)
        val current = backend.player.playbackParameters
        if (kotlin.math.abs(current.speed - tenths / 10f) < 0.001f && current.pitch == 1f) {
            _state.value = _state.value.copy(speed = PlaybackSpeedState(tenths, current.speed, current.pitch))
            return
        }
        _state.value = _state.value.copy(speed = PlaybackSpeedState(tenths, current.speed, current.pitch, pending = true))
        try {
            val actual = operations.applyPlaybackParameters(tenths)
            if (!closed && active === backend) _state.value = _state.value.copy(speed = actual)
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            if (!closed && active === backend) _state.value = _state.value.copy(
                speed = _state.value.speed.copy(pending = false, issue = PlaybackBackendIssue.SPEED_UNSUPPORTED),
            )
        }
    }

    private suspend fun switchBackend(saved: UserPreferences) {
        val queues = checkNotNull(coordinator)
        val statistics = checkNotNull(listening)
        var checkpoint: QueueBackendCheckpoint? = null
        var unbound = false
        val previous = active
        val entryId = queues.state.value.snapshot?.currentEntryId
        previous?.route?.setPlaybackActive(false)
        _state.value = _state.value.copy(switching = true, issue = null)
        refreshRouteState()
        try {
            val frozen = queues.freezeForBackendSwitch()
            checkpoint = frozen
            currentCoroutineContext().ensureActive()
            focus.unbindBackend()
            // The queue invalidates its backend binding before any listener removal or refresh
            // can fail. Mark this stage first so cleanup also covers a partially completed unbind.
            unbound = true
            queues.unbindBackendForSwitch(frozen)
            statistics.unbindBackend()
            unbindObserver()
            active = null
            _state.value = _state.value.copy(actualBackend = null, gaplessAvailable = false,
                generation = _state.value.generation + 1L)
            try { previous?.releaseAndAwait() }
            catch (error: Exception) {
                quarantined = previous
                throw error
            }
            currentCoroutineContext().ensureActive()
            if (closed) throw CancellationException("Playback service closed")
            val replacement = createBackend(saved.playbackBackend, saved.playbackSpeedTenths)
            active = replacement
            _state.value = _state.value.copy(actualBackend = replacement.kind)
            bindObserver(replacement)
            replacement.player.pause()
            // Restoration is paused throughout. Bind before it consumes the opaque token so
            // every fallible player/listener operation can still use the same failure cleanup.
            statistics.bindBackend(replacement.player)
            queues.restoreAfterBackendSwitch(replacement.player, replacement.operations, frozen)
            _state.value = _state.value.copy(switching = false, issue = null)
        } catch (error: Exception) {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                if (!unbound) {
                    preserveFailure(error) { if (!closed) checkpoint?.let(queues::cancelBackendSwitch) }
                    _state.value = _state.value.copy(switching = false)
                } else {
                    releaseSwitchBinding(queues, statistics, checkNotNull(checkpoint), error)
                }
            }
            val frozen = checkpoint
            if (unbound && frozen != null && !closed) {
                if (error !is CancellationException && quarantined == null && previous != null && !releaseGuard.blocksCreation()) {
                    // One bounded rollback rebuilds the old kind only after both prior instances
                    // have been released. It rechecks sources and restores paused through the same
                    // token; the user's saved requested kind remains unchanged and visible.
                    var restored = false
                    try {
                        currentCoroutineContext().ensureActive()
                        val fallback = createBackend(previous.kind, saved.playbackSpeedTenths)
                        active = fallback
                        _state.value = _state.value.copy(actualBackend = fallback.kind)
                        bindObserver(fallback)
                        fallback.player.pause()
                        statistics.bindBackend(fallback.player)
                        queues.restoreAfterBackendSwitch(fallback.player, fallback.operations, frozen)
                        _state.value = _state.value.copy(switching = false)
                        restored = true
                    } catch (fallbackError: Exception) {
                        withContext(NonCancellable + Dispatchers.Main.immediate) {
                            releaseSwitchBinding(queues, statistics, frozen, fallbackError)
                            preserveFailure(fallbackError) {
                                if (!closed) queues.failBackendSwitch(frozen,
                                    QueuePlaybackFailure(QueuePlaybackIssue.OUTPUT_ERROR, entryId))
                            }
                            _state.value = _state.value.copy(switching = false)
                        }
                        if (fallbackError is CancellationException) throw fallbackError
                    }
                    // A successful restore has consumed the token. Notification failure must
                    // propagate without sending that token through a second cleanup attempt.
                    if (restored) {
                        report(PlaybackBackendIssue.SWITCH_FAILED)
                        return
                    }
                    if (!closed) report(PlaybackBackendIssue.SWITCH_FAILED)
                } else {
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        preserveFailure(error) {
                            if (!closed) queues.failBackendSwitch(frozen,
                                QueuePlaybackFailure(QueuePlaybackIssue.OUTPUT_ERROR, entryId))
                        }
                        _state.value = _state.value.copy(switching = false)
                        if (!closed) report(PlaybackBackendIssue.SWITCH_FAILED)
                    }
                }
            } else if (!closed) report(PlaybackBackendIssue.SWITCH_FAILED)
            if (error is CancellationException) throw error
        }
    }

    private suspend fun releaseSwitchBinding(
        queues: QueuePlaybackCoordinator,
        statistics: ServiceListeningObserver,
        checkpoint: QueueBackendCheckpoint,
        failure: Exception,
    ) {
        preserveFailure(failure) { if (!closed) queues.unbindBackendForSwitch(checkpoint) }
        preserveFailure(failure, statistics::unbindBackend)
        preserveFailure(failure, ::unbindObserver)
        val failed = active
        active = null
        try { failed?.releaseAndAwait() }
        catch (cleanup: Exception) {
            quarantined = failed
            if (cleanup !== failure) failure.addSuppressed(cleanup)
        }
        _state.value = _state.value.copy(actualBackend = null, gaplessAvailable = false,
            generation = _state.value.generation + 1L)
    }

    private fun preserveFailure(failure: Exception, cleanup: () -> Unit) {
        try { cleanup() }
        catch (error: Exception) { if (error !== failure) failure.addSuppressed(error) }
    }

    private fun bindObserver(backend: Backend) {
        val generation = _state.value.generation
        val listener = object : Player.Listener {
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                if (!isCurrent(backend, generation) || backend.system != null) return
                _state.value = _state.value.copy(speed = _state.value.speed.copy(
                    actualSpeed = playbackParameters.speed, actualPitch = playbackParameters.pitch,
                ))
            }
            override fun onPlayerError(error: PlaybackException) {
                if (isCurrent(backend, generation)) {
                    backend.route.setPlaybackActive(false)
                    refreshRouteState()
                    focus.userPause()
                    report(PlaybackBackendIssue.SOURCE_UNAVAILABLE)
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (!isCurrent(backend, generation)) return
                // 自然换曲可以不经过服务命令；复用原生轨道时仍需新来源票据隔离其排队监听回调。
                if (backend.media3 != null) backend.route.sourceChanged(keepSource = true)
                updateRouteGate(backend)
            }

            override fun onEvents(player: Player, events: Player.Events) {
                if (isCurrent(backend, generation)) updateRouteGate(backend)
            }
        }
        observer = listener
        backend.player.addListener(listener)
        updateRouteGate(backend)
        backend.system?.let { platform ->
            speedObserver = scope.launch(Dispatchers.Main.immediate) {
                platform.speedState.collect { speed ->
                    if (isCurrent(backend, generation)) _state.value = _state.value.copy(speed = speed)
                }
            }
        }
    }

    private fun unbindObserver() {
        speedObserver?.cancel()
        speedObserver = null
        val previous = active
        val listener = observer
        observer = null
        if (previous != null && listener != null) previous.player.removeListener(listener)
    }

    private fun isCurrent(backend: Backend, generation: Long): Boolean = !closed && active === backend && _state.value.generation == generation

    private fun updateRouteGate(backend: Backend) {
        val playing = try { backend.player.isPlaying && backend.player.playerError == null }
        catch (_: RuntimeException) { false }
        backend.route.setPlaybackActive(active === backend && hasUsableBackend && playing)
        refreshRouteState()
    }

    private fun readCurrentRoute(): PlaybackRouteSessionState {
        checkMain()
        val backend = active
        if (backend == null || !hasUsableBackend) {
            backend?.route?.setPlaybackActive(false)
            refreshRouteState()
            return PlaybackRouteSessionState()
        }
        updateRouteGate(backend)
        val fresh = backend.route.read()
        val current = if (active === backend && hasUsableBackend) fresh else PlaybackRouteSessionState()
        _routeState.value = current
        return current
    }

    private fun currentRouteSessionState(): PlaybackRouteSessionState =
        if (hasUsableBackend) active?.route?.cachedState() ?: PlaybackRouteSessionState() else PlaybackRouteSessionState()

    private fun refreshRouteState() {
        checkMain()
        _routeState.value = currentRouteSessionState()
    }

    private fun unavailableMediaVolume() = MediaVolumeResult(null, ServiceMediaVolumeIssue.UNAVAILABLE)

    private fun blockedByPriorRelease(): Boolean = releaseGuard.hasUnconfirmedRelease() || active == null && releaseGuard.blocksCreation()

    private fun createInitialBackend(): Backend? = try {
        createBackend(PlaybackBackendKind.MEDIA3, 10)
    } catch (_: Exception) {
        // The process guard must yield an inspectable paused service, not an onCreate crash.
        _state.value = _state.value.copy(actualBackend = null, issue = PlaybackBackendIssue.SWITCH_FAILED)
        null
    }

    private fun createBackend(kind: PlaybackBackendKind, speedTenths: Int): Backend {
        val lease = releaseGuard.acquire()
        val route = PlaybackAudioRoute(lease, releaseGuard, routeExecutor, ::refreshRouteState)
        return try {
            createLeasedBackend(kind, speedTenths, lease, route)
        } catch (error: Exception) {
            route.close()
            // If allocation failed before an owned instance could be released, its resources are
            // unknown. A configuration failure with confirmed cleanup has already cleared lease.
            if (releaseGuard.isRetained(lease) && !releaseGuard.releaseStarted(lease)) releaseGuard.quarantine(lease)
            throw error
        }
    }

    private fun createLeasedBackend(
        kind: PlaybackBackendKind,
        speedTenths: Int,
        lease: PlaybackEngineReleaseGuard.Lease,
        route: PlaybackAudioRoute,
    ): Backend = when (kind) {
        PlaybackBackendKind.SYSTEM_MEDIA_PLAYER -> {
            val platform = SystemMediaPlayerAdapter(applicationContext, localIo, scope, route, speedTenths)
            val backend = Backend(kind, platform, platform, releaseGuard, lease, route, system = platform)
            try {
                platform.initializePlatform()
                backend
            } catch (error: Exception) {
                try { backend.releaseLater() }
                catch (cleanup: Exception) {
                    quarantined = backend
                    error.addSuppressed(cleanup)
                }
                throw error
            }
        }
        PlaybackBackendKind.MEDIA3 -> {
            val localDataSource = DataSource.Factory { ContentDataSource(applicationContext) }
            val renderers = RoutingRenderersFactory(applicationContext, route)
                .setEnableAudioFloatOutput(false).setEnableAudioTrackPlaybackParams(false)
            val player = ExoPlayer.Builder(applicationContext).setRenderersFactory(renderers)
                .setMediaSourceFactory(DefaultMediaSourceFactory(localDataSource)).setReleaseTimeoutMs(1500L).build()
            val routedPlayer = RoutingPlaybackPlayer(player, route)
            val operations = Media3PlaybackOperations(routedPlayer)
            val backend = Backend(kind, routedPlayer, operations, releaseGuard, lease, route, media3 = operations)
            try {
                player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
                player.setHandleAudioBecomingNoisy(false)
                player.setWakeMode(C.WAKE_MODE_LOCAL)
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setAudioOffloadPreferences(TrackSelectionParameters.AudioOffloadPreferences.Builder()
                        .setAudioOffloadMode(TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED).build()).build()
                backend
            } catch (error: Exception) {
                try { backend.releaseLater() }
                catch (cleanup: Exception) {
                    quarantined = backend
                    error.addSuppressed(cleanup)
                }
                throw error
            }
        }
    }

    private fun snapshot(state: PreferencesState): UserPreferences = when (state) {
        is PreferencesState.Ready -> state.preferences
        is PreferencesState.Unavailable -> state.lastKnown ?: currentPreferences
        PreferencesState.Loading -> currentPreferences
    }

    private fun report(issue: PlaybackBackendIssue) {
        if (closed) return
        _state.value = _state.value.copy(issue = issue)
        onFailure(issue)
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())
}
