package io.github.onlinemo.meloxwatch.playback

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.system.Os
import android.system.OsConstants
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import io.github.onlinemo.meloxwatch.model.PlaybackPreferenceLimits
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.playback.route.AndroidRoutingSources
import io.github.onlinemo.meloxwatch.playback.route.PlaybackAudioRoute
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 一个系统播放器及有界队列窗口，以物理来源代次隔离旧回调；生命周期操作归服务主线程。
 *
 * [route] 必须绑定同一内核 lease，仅观察此实例；reset、失败和释放前退役输出身份。
 */
@UnstableApi
internal class SystemMediaPlayerAdapter(
    context: Context,
    private val io: LocalProviderIo,
    private val scope: CoroutineScope,
    private val route: PlaybackAudioRoute,
    requestedSpeedTenths: Int = PlaybackPreferenceLimits.DEFAULT_SPEED_TENTHS,
    createPlayer: () -> MediaPlayer = { MediaPlayer() },
) : SimpleBasePlayer(Looper.getMainLooper()), PlaybackBackendOperations {
    private data class WindowItem(val uid: String, val mediaItem: MediaItem, var durationMs: Long = C.TIME_UNSET)
    private data class SeekRequest(val positionMs: Long, val future: SettableFuture<Unit>)
    private data class ValidatedAsset(val descriptor: AssetFileDescriptor, val length: Long)

    private val applicationContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val items = ArrayList<WindowItem>()
    private val _speedState = MutableStateFlow(PlaybackSpeedState(requestedTenths = requestedSpeedTenths,
        pending = requestedSpeedTenths != PlaybackPreferenceLimits.DEFAULT_SPEED_TENTHS))
    val speedState = _speedState.asStateFlow()
    private val platform = run {
        checkMain()
        createPlayer()
    }
    private var platformReleased = false
    override val player: Player get() = this
    private var currentIndex = C.INDEX_UNSET
    private var sourceGeneration = 0L
    private var routingGeneration: Long? = null
    private var closed = false
    private var prepared = false
    private var preparing = false
    private var buffering = false
    private var ended = false
    private var seekable = false
    private var playRequested = false
    private var playReason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
    private var pendingPositionMs = 0L
    private var capturedPositionMs = 0L
    private var physicalDurationMs = C.TIME_UNSET
    private var volumeValue = 1f
    private var failure: PlaybackException? = null
    private var loadJob: Job? = null
    private var providerCancellation: CancellationSignal? = null
    private var attemptedSpeedTenths: Int? = null
    private var prepareFuture: SettableFuture<Unit>? = null
    private var inFlightSeek: SeekRequest? = null
    private var queuedSeek: SeekRequest? = null
    private var lastSeekFuture: ListenableFuture<Unit>? = null
    private var livePosition: LivePositionSupplier? = null
    private var boundaryCheck: Runnable? = null
    private val audioAttributesValue = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    /** Configure only after the service owns this instance and can quarantine a failed release. */
    fun initializePlatform() {
        checkMain()
        check(!closed)
        platform.setAudioAttributes(android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
        platform.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
    }

    val audioSessionId: Int get() {
        checkMain()
        return if (closed) C.AUDIO_SESSION_ID_UNSET else platform.audioSessionId
    }

    override fun setPauseAtBoundary(pause: Boolean) {
        checkMain()
        // setNextMediaPlayer would require another live player. Platform completion is always
        // paused and the full durable queue decides the successor through the same adapter.
    }

    override suspend fun prepareAndAwait(expectedMediaId: String) = withContext(Dispatchers.Main.immediate) {
        requireCurrent(expectedMediaId)
        if (prepared && !preparing && inFlightSeek == null && !ended && failure == null) return@withContext
        prepare()
        val pending = prepareFuture ?: throw sourceError("PREPARATION_FAILED", PlaybackException.ERROR_CODE_INVALID_STATE)
        awaitWithin(pending, PREPARE_TIMEOUT_MS, "PREPARATION_TIMED_OUT")
        requireCurrent(expectedMediaId)
        failure?.let { throw it }
        if (!prepared || inFlightSeek != null) throw sourceError("PREPARATION_FAILED", PlaybackException.ERROR_CODE_INVALID_STATE)
    }

    override suspend fun seekAndAwait(expectedMediaId: String, positionMs: Long): Unit = withContext(Dispatchers.Main.immediate) {
        requireCurrent(expectedMediaId)
        require(positionMs >= 0L)
        if (!prepared || failure != null) throw sourceError("SEEK_UNAVAILABLE", PlaybackException.ERROR_CODE_INVALID_STATE)
        validatePhysicalSeek(positionMs)
        lastSeekFuture = null
        seekTo(positionMs)
        val pending = lastSeekFuture ?: throw sourceError("SEEK_UNAVAILABLE", PlaybackException.ERROR_CODE_INVALID_STATE)
        awaitWithin(pending, SEEK_TIMEOUT_MS, "SEEK_TIMED_OUT")
        requireCurrent(expectedMediaId)
        failure?.let { throw it }
    }

    fun requestSpeed(tenths: Int) {
        checkMain()
        require(tenths in PlaybackPreferenceLimits.MIN_SPEED_TENTHS..PlaybackPreferenceLimits.MAX_SPEED_TENTHS)
        if (closed) return
        if (_speedState.value.requestedTenths != tenths || _speedState.value.issue != null) attemptedSpeedTenths = null
        _speedState.value = _speedState.value.copy(requestedTenths = tenths,
            pending = !sameSpeed(_speedState.value.actualSpeed, tenths / 10f) || !sameSpeed(_speedState.value.actualPitch, 1f), issue = null)
        // On API26, a nonzero setPlaybackParams in Prepared/Paused can start audio. A paused
        // request stays pending; Initialized setup or an authorized explicit play applies it.
        if (prepared && playRequested && inFlightSeek == null && !buffering) applySpeed()
        invalidateState()
    }

    override fun getState(): State {
        val speed = _speedState.value
        val generation = sourceGeneration
        val frozenPosition = currentPositionSafely()
        val position = livePosition ?: LivePositionSupplier(PositionSupplier {
            if (isCurrent(generation)) currentPositionSafely() else frozenPosition
        }).also { livePosition = it }
        val commands = if (closed) Player.Commands.EMPTY else availableBackendCommands()
        val playlist = items.mapIndexed { index, item ->
            MediaItemData.Builder(item.uid)
                .setMediaItem(item.mediaItem)
                .setDurationUs(if (item.durationMs == C.TIME_UNSET) C.TIME_UNSET else item.durationMs * 1000L)
                .setIsSeekable(index == currentIndex && seekable && item.durationMs > 0L)
                .build()
        }
        val state = when {
            closed || failure != null || items.isEmpty() -> Player.STATE_IDLE
            ended -> Player.STATE_ENDED
            preparing || inFlightSeek != null || buffering -> Player.STATE_BUFFERING
            prepared -> Player.STATE_READY
            else -> Player.STATE_IDLE
        }
        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(playlist)
            .setCurrentMediaItemIndex(currentIndex)
            .setPlaybackState(state)
            .setPlayWhenReady(playRequested, playReason)
            .setIsLoading(preparing || buffering)
            .setPlayerError(failure)
            .setAudioAttributes(audioAttributesValue)
            .setVolume(volumeValue)
            .setPlaybackParameters(PlaybackParameters(speed.actualSpeed, speed.actualPitch))
            .setContentPositionMs(position)
            .setContentBufferedPositionMs(position)
            .setTotalBufferedDurationMs(PositionSupplier.ZERO)
            .build()
    }

    override fun getPlaceholderState(suggestedPlaceholderState: State): State = getState()

    override fun handlePrepare(): ListenableFuture<*> {
        checkMain()
        if (closed || items.isEmpty()) return immediate()
        failure = null
        if (prepared && !ended && (inFlightSeek == null || prepareFuture?.isCancelled != true)) {
            return prepareFuture?.takeUnless { it.isCancelled } ?: immediate()
        }
        return startPreparation()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        checkMain()
        if (closed) return immediate()
        playReason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
        playRequested = playWhenReady
        if (!playWhenReady) pausePlatform() else if (prepared && !preparing && inFlightSeek == null && !ended && failure == null) startPlatform()
        return immediate()
    }

    override fun handleStop(): ListenableFuture<*> {
        checkMain()
        if (!closed) {
            playRequested = false
            resetSource()
            pendingPositionMs = capturedPositionMs
        }
        return immediate()
    }

    override fun handleRelease(): ListenableFuture<*> {
        checkMain()
        route.close()
        routingGeneration = null
        if (closed) {
            releasePlatformIfNeeded()
            return immediate()
        }
        var failure: Exception? = null
        fun attempt(operation: () -> Unit) {
            try { operation() }
            catch (error: Exception) {
                val first = failure
                if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
            }
        }
        attempt {
            capturedPositionMs = currentPositionSafely()
            pausePlatform()
        }
        disconnectPosition(capturedPositionMs)
        playRequested = false
        closed = true
        sourceGeneration += 1L
        stopBoundaryCheck()
        val loading = loadJob
        loadJob = null
        val provider = providerCancellation
        providerCancellation = null
        attempt { loading?.cancel() }
        attempt { provider?.cancel() }
        preparing = false
        prepared = false
        prepareFuture?.cancel(false)
        prepareFuture = null
        cancelSeeks()
        attempt {
            platform.setOnPreparedListener(null)
            platform.setOnSeekCompleteListener(null)
            platform.setOnCompletionListener(null)
            platform.setOnInfoListener(null)
            platform.setOnErrorListener(null)
        }
        attempt(::releasePlatformIfNeeded)
        failure?.let { throw it }
        return immediate()
    }

    /** SimpleBasePlayer's released facade cannot retry a native release which threw. */
    fun releaseOwnedPlayer() {
        checkMain()
        route.close()
        routingGeneration = null
        try { release() } finally { releasePlatformIfNeeded() }
    }

    private fun releasePlatformIfNeeded() {
        if (!platformReleased) {
            platform.release()
            platformReleased = true
        }
    }

    override fun handleSetVolume(volume: Float): ListenableFuture<*> {
        checkMain()
        volumeValue = volume
        if (!closed && failure == null) platform.setVolume(volume, volume)
        return immediate()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        val tenths = kotlin.math.round(playbackParameters.speed * 10f).toInt()
        if (playbackParameters.pitch != 1f || !sameSpeed(playbackParameters.speed, tenths / 10f) ||
            tenths !in PlaybackPreferenceLimits.MIN_SPEED_TENTHS..PlaybackPreferenceLimits.MAX_SPEED_TENTHS) {
            _speedState.value = _speedState.value.copy(issue = PlaybackBackendIssue.SPEED_UNSUPPORTED)
            return immediate()
        }
        requestSpeed(tenths)
        return immediate()
    }

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        checkMain()
        require(mediaItems.size <= MAX_WINDOW_ITEMS)
        resetSource()
        items.clear()
        items.addAll(mediaItems.map { WindowItem(UUID.randomUUID().toString(), it) })
        currentIndex = if (items.isEmpty()) C.INDEX_UNSET else if (startIndex == C.INDEX_UNSET) 0 else startIndex
        pendingPositionMs = if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs.coerceAtLeast(0L)
        capturedPositionMs = pendingPositionMs
        return immediate()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        checkMain()
        require(mediaItems.size <= MAX_WINDOW_ITEMS - items.size)
        items.addAll(index, mediaItems.map { WindowItem(UUID.randomUUID().toString(), it) })
        if (currentIndex == C.INDEX_UNSET && items.isNotEmpty()) currentIndex = 0
        else if (index <= currentIndex) currentIndex += mediaItems.size
        return immediate()
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        checkMain()
        val currentUid = items.getOrNull(currentIndex)?.uid
        val moved = items.subList(fromIndex, toIndex).toList()
        items.subList(fromIndex, toIndex).clear()
        items.addAll(newIndex, moved)
        currentIndex = currentUid?.let { uid -> items.indexOfFirst { it.uid == uid } } ?: C.INDEX_UNSET
        return immediate()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        checkMain()
        val removedCurrent = currentIndex in fromIndex until toIndex
        if (removedCurrent) resetSource()
        items.subList(fromIndex, toIndex).clear()
        currentIndex = when {
            items.isEmpty() -> C.INDEX_UNSET
            removedCurrent -> fromIndex.coerceAtMost(items.lastIndex)
            currentIndex >= toIndex -> currentIndex - (toIndex - fromIndex)
            else -> currentIndex
        }
        if (removedCurrent) {
            pendingPositionMs = 0L
            capturedPositionMs = 0L
        }
        return immediate()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        checkMain()
        if (closed || mediaItemIndex !in items.indices) return immediate()
        val target = if (positionMs == C.TIME_UNSET) 0L else positionMs.coerceAtLeast(0L)
        if (mediaItemIndex != currentIndex) {
            val wasPrepared = prepared || preparing
            resetSource()
            currentIndex = mediaItemIndex
            pendingPositionMs = target
            capturedPositionMs = target
            val result = if (wasPrepared) startPreparation() else immediate()
            lastSeekFuture = result
            return result
        }
        pendingPositionMs = target
        if (!prepared) {
            lastSeekFuture = prepareFuture ?: immediate()
            return lastSeekFuture ?: immediate()
        }
        val future = SettableFuture.create<Unit>()
        lastSeekFuture = future
        val request = SeekRequest(target, future)
        if (inFlightSeek != null) {
            queuedSeek?.future?.cancel(false)
            queuedSeek = request
        } else submitSeek(request)
        return future
    }

    private fun startPreparation(): ListenableFuture<Unit> {
        if (preparing) return checkNotNull(prepareFuture)
        val requestedPlay = playRequested
        resetSource()
        playRequested = requestedPlay
        val item = items.getOrNull(currentIndex)?.mediaItem ?: return immediate()
        val uri = item.localConfiguration?.uri
        val future = SettableFuture.create<Unit>()
        prepareFuture = future
        if (uri == null || uri.scheme != "content") {
            fail(sourceError("LOCAL_URI_REQUIRED", PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED))
            return future
        }
        val generation = sourceGeneration
        val signal = CancellationSignal()
        providerCancellation = signal
        preparing = true
        failure = null
        installCallbacks(generation)
        loadJob = scope.launch(Dispatchers.Main.immediate) {
            try {
                val asset = openValidatedAsset(uri, signal)
                asset.descriptor.use { descriptor ->
                    currentCoroutineContext().ensureActive()
                    if (!isCurrent(generation)) throw CancellationException("Platform source changed")
                    platform.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, asset.length)
                    seekable = true
                    applySpeed()
                    platform.prepareAsync()
                }
            } catch (error: CancellationException) {
                if (isCurrent(generation)) {
                    preparing = false
                    playRequested = false
                    future.cancel(false)
                    invalidateState()
                }
                throw error
            } catch (error: Exception) {
                if (isCurrent(generation)) fail(when (error) {
                    is PlaybackException -> error
                    is SourceReadException -> when (error.issue) {
                        SourceReadIssue.PERMISSION_DENIED, SourceReadIssue.PERMISSION_REVOKED ->
                            sourceError("PERMISSION_DENIED", PlaybackException.ERROR_CODE_IO_NO_PERMISSION)
                        SourceReadIssue.FILE_MISSING -> sourceError("FILE_MISSING", PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
                        SourceReadIssue.BUSY -> sourceError("SOURCE_BUSY", PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
                        else -> sourceError("SOURCE_UNAVAILABLE", PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
                    }
                    is SecurityException -> sourceError("PERMISSION_DENIED", PlaybackException.ERROR_CODE_IO_NO_PERMISSION)
                    is FileNotFoundException -> sourceError("FILE_MISSING", PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
                    else -> sourceError("SOURCE_UNAVAILABLE", PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
                })
            } finally {
                if (providerCancellation === signal) providerCancellation = null
            }
        }
        return future
    }

    private suspend fun openValidatedAsset(uri: Uri, signal: CancellationSignal): ValidatedAsset {
        repeat(3) { attempt ->
            currentCoroutineContext().ensureActive()
            try {
                return io.read(signal, abandoned = { it.descriptor.close() }) { cancellation ->
                    val descriptor = applicationContext.contentResolver.openAssetFileDescriptor(uri, "r", cancellation)
                        ?: throw FileNotFoundException()
                    try { ValidatedAsset(descriptor, validateAssetRange(descriptor)) }
                    catch (error: Exception) {
                        descriptor.close()
                        throw error
                    }
                }
            } catch (error: SourceReadException) {
                if (error.issue != SourceReadIssue.BUSY || attempt == 2) throw error
                io.awaitIdle()
            }
        }
        throw SourceReadException(SourceReadIssue.BUSY)
    }

    private fun installCallbacks(generation: Long) {
        platform.setOnPreparedListener { mediaPlayer ->
            if (!isCurrent(generation) || mediaPlayer !== platform) return@setOnPreparedListener
            try {
                physicalDurationMs = mediaPlayer.duration.toLong().takeIf { it > 0L } ?: C.TIME_UNSET
                val clip = currentItem().clippingConfiguration
                if (clip.startPositionUs / 1000L > Int.MAX_VALUE.toLong() ||
                    physicalDurationMs != C.TIME_UNSET && (clip.startPositionUs >= physicalDurationMs * 1000L ||
                        clip.endPositionUs != C.TIME_END_OF_SOURCE && clip.endPositionUs > physicalDurationMs * 1000L)) {
                    throw sourceError("SEGMENT_OUT_OF_RANGE", PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
                }
                items[currentIndex].durationMs = segmentDurationMs()
                prepared = true
                preparing = false
                ended = false
                readActualSpeed()
                if (pendingPositionMs > 0L || clip.startPositionUs > 0L) {
                    submitSeek(SeekRequest(pendingPositionMs, SettableFuture.create()))
                } else finishPreparation()
                invalidateState()
            } catch (error: Exception) {
                fail(if (error is PlaybackException) error else sourceError("PREPARATION_FAILED", PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
            }
        }
        platform.setOnSeekCompleteListener { mediaPlayer ->
            if (!isCurrent(generation) || mediaPlayer !== platform) return@setOnSeekCompleteListener
            val completed = inFlightSeek ?: return@setOnSeekCompleteListener
            disconnectPosition(capturedPositionMs)
            capturedPositionMs = positionFromPlatform()
            inFlightSeek = null
            val next = queuedSeek
            queuedSeek = null
            if (next != null) submitSeek(next) else {
                finishPreparation()
            }
            completed.future.set(Unit)
            invalidateState()
        }
        platform.setOnCompletionListener { mediaPlayer ->
            if (isCurrent(generation) && mediaPlayer === platform && !ended) completeSegment()
        }
        platform.setOnInfoListener { mediaPlayer, what, _ ->
            if (isCurrent(generation) && mediaPlayer === platform) {
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START || what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                    capturedPositionMs = currentPositionSafely()
                    buffering = what == MediaPlayer.MEDIA_INFO_BUFFERING_START
                    route.setRenderingActive(!buffering && playRequested && prepared && inFlightSeek == null && !ended)
                    if (!buffering && playRequested && prepared && inFlightSeek == null) {
                        applySpeed()
                        scheduleBoundaryCheck()
                    }
                    invalidateState()
                }
            }
            false
        }
        platform.setOnErrorListener { mediaPlayer, what, extra ->
            if (isCurrent(generation) && mediaPlayer === platform) fail(sourceError(
                when (extra) {
                    MediaPlayer.MEDIA_ERROR_UNSUPPORTED -> "FORMAT_UNSUPPORTED"
                    MediaPlayer.MEDIA_ERROR_MALFORMED -> "SOURCE_DAMAGED"
                    MediaPlayer.MEDIA_ERROR_TIMED_OUT -> "SOURCE_TIMED_OUT"
                    else -> if (what == MediaPlayer.MEDIA_ERROR_SERVER_DIED) "OUTPUT_RECLAIMED" else "OUTPUT_ERROR"
                },
                when (extra) {
                    MediaPlayer.MEDIA_ERROR_UNSUPPORTED -> PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                    MediaPlayer.MEDIA_ERROR_MALFORMED -> PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                    MediaPlayer.MEDIA_ERROR_TIMED_OUT -> PlaybackException.ERROR_CODE_TIMEOUT
                    else -> PlaybackException.ERROR_CODE_DECODING_FAILED
                },
            ))
            true
        }
    }

    private fun finishPreparation() {
        if (!prepared || inFlightSeek != null || failure != null || closed) return
        if (playRequested) startPlatform()
        prepareFuture?.set(Unit)
    }

    private fun submitSeek(request: SeekRequest) {
        try {
            validatePhysicalSeek(request.positionMs)
            capturedPositionMs = currentPositionSafely()
            pausePlatform()
            disconnectPosition(capturedPositionMs)
            ended = false
            inFlightSeek = request
            platform.seekTo(physicalStartMs() + request.positionMs.coerceAtMostPositive(segmentDurationMs()), MediaPlayer.SEEK_CLOSEST)
        } catch (error: Exception) {
            request.future.setException(error)
            fail(if (error is PlaybackException) error else sourceError("SEEK_FAILED", PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        }
    }

    private fun validatePhysicalSeek(positionMs: Long) {
        val start = physicalStartMs()
        if (positionMs > Int.MAX_VALUE.toLong() - start) throw sourceError(
            "PLATFORM_SEEK_LIMIT", PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        )
        if (prepared && !seekable) throw sourceError("SOURCE_NOT_SEEKABLE", PlaybackException.ERROR_CODE_NOT_SUPPORTED)
    }

    private fun startPlatform() {
        if (!prepared || closed || failure != null || inFlightSeek != null || !playRequested) return
        try {
            applySpeed()
            observeRoutingForCurrentSource()
            platform.start()
            route.setRenderingActive(true)
            scheduleBoundaryCheck()
        } catch (_: Exception) {
            fail(sourceError("OUTPUT_ERROR", PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED))
        }
    }

    private fun pausePlatform() {
        route.setPlaybackActive(false)
        route.setRenderingActive(false)
        stopBoundaryCheck()
        if (!prepared || closed || failure != null) return
        capturedPositionMs = currentPositionSafely()
        try { if (platform.isPlaying) platform.pause() }
        catch (_: IllegalStateException) { /* An error callback already owns the state transition. */ }
    }

    private fun applySpeed() {
        val requested = _speedState.value.requestedTenths / 10f
        if (_speedState.value.issue == null && !_speedState.value.pending &&
            sameSpeed(_speedState.value.actualSpeed, requested) && sameSpeed(_speedState.value.actualPitch, 1f)) return
        try {
            attemptedSpeedTenths = _speedState.value.requestedTenths
            platform.playbackParams = PlaybackParams().allowDefaults()
                .setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_FAIL).setPitch(1f).setSpeed(requested)
            readActualSpeed()
        } catch (_: RuntimeException) {
            _speedState.value = _speedState.value.copy(pending = false, issue = PlaybackBackendIssue.SPEED_UNSUPPORTED)
        }
    }

    private fun readActualSpeed() {
        try {
            val parameters = platform.playbackParams
            val actualSpeed = parameters.speed
            val actualPitch = parameters.pitch
            if (actualSpeed.isFinite() && actualSpeed > 0f && actualPitch.isFinite() && actualPitch > 0f) {
                val requested = _speedState.value.requestedTenths / 10f
                val matches = sameSpeed(actualSpeed, requested) && sameSpeed(actualPitch, 1f)
                _speedState.value = _speedState.value.copy(actualSpeed = actualSpeed, actualPitch = actualPitch,
                    pending = !matches && attemptedSpeedTenths != _speedState.value.requestedTenths,
                    issue = if (matches) null else if (attemptedSpeedTenths == _speedState.value.requestedTenths ||
                        !sameSpeed(actualPitch, 1f)) PlaybackBackendIssue.SPEED_UNSUPPORTED else _speedState.value.issue)
            } else {
                _speedState.value = _speedState.value.copy(pending = false, issue = PlaybackBackendIssue.SPEED_UNSUPPORTED)
            }
        } catch (_: RuntimeException) {
            _speedState.value = _speedState.value.copy(pending = false, issue = PlaybackBackendIssue.SPEED_UNSUPPORTED)
        }
    }

    private fun resetSource() {
        route.setPlaybackActive(false)
        route.setRenderingActive(false)
        route.sourceChanged()
        routingGeneration = null
        capturedPositionMs = currentPositionSafely()
        disconnectPosition(capturedPositionMs)
        sourceGeneration += 1L
        loadJob?.cancel()
        loadJob = null
        providerCancellation?.cancel()
        providerCancellation = null
        stopBoundaryCheck()
        playRequested = false
        preparing = false
        prepared = false
        buffering = false
        ended = false
        seekable = false
        physicalDurationMs = C.TIME_UNSET
        failure = null
        attemptedSpeedTenths = null
        _speedState.value = _speedState.value.copy(actualSpeed = 1f, actualPitch = 1f,
            pending = _speedState.value.requestedTenths != PlaybackPreferenceLimits.DEFAULT_SPEED_TENTHS, issue = null)
        prepareFuture?.cancel(false)
        prepareFuture = null
        cancelSeeks()
        if (!closed) {
            platform.reset()
            platform.setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
            platform.setVolume(volumeValue, volumeValue)
        }
    }

    private fun cancelSeeks() {
        val active = inFlightSeek
        val queued = queuedSeek
        inFlightSeek = null
        queuedSeek = null
        active?.future?.cancel(false)
        queued?.future?.cancel(false)
        lastSeekFuture = null
    }

    private fun fail(error: PlaybackException) {
        route.setPlaybackActive(false)
        route.setRenderingActive(false)
        route.sourceChanged()
        routingGeneration = null
        capturedPositionMs = currentPositionSafely()
        playRequested = false
        pausePlatform()
        disconnectPosition(capturedPositionMs)
        sourceGeneration += 1L
        loadJob?.cancel()
        loadJob = null
        providerCancellation?.cancel()
        providerCancellation = null
        failure = error
        preparing = false
        prepared = false
        buffering = false
        stopBoundaryCheck()
        inFlightSeek?.future?.setException(error)
        queuedSeek?.future?.setException(error)
        inFlightSeek = null
        queuedSeek = null
        prepareFuture?.setException(error)
        invalidateState()
    }

    private fun completeSegment() {
        if (ended || closed || !prepared) return
        capturedPositionMs = segmentDurationMs().takeIf { it > 0L } ?: currentPositionSafely()
        pausePlatform()
        disconnectPosition(capturedPositionMs)
        playRequested = false
        playReason = Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
        ended = true
        buffering = false
        invalidateState()
    }

    private fun scheduleBoundaryCheck() {
        stopBoundaryCheck()
        if (currentItem().clippingConfiguration.endPositionUs == C.TIME_END_OF_SOURCE) return
        val generation = sourceGeneration
        val check = object : Runnable {
            override fun run() {
                if (!isCurrent(generation) || !prepared || !playRequested || ended) return
                val duration = segmentDurationMs()
                val position = currentPositionSafely()
                if (duration > 0L && position >= duration) completeSegment() else {
                    val remaining = (duration - position).coerceAtLeast(1L)
                    val delay = (remaining / _speedState.value.actualSpeed).toLong().coerceIn(1L, BOUNDARY_CHECK_INTERVAL_MS)
                    handler.postDelayed(this, delay)
                }
            }
        }
        boundaryCheck = check
        handler.postDelayed(check, 1L)
    }

    private fun stopBoundaryCheck() {
        boundaryCheck?.let(handler::removeCallbacks)
        boundaryCheck = null
    }

    private fun disconnectPosition(finalPositionMs: Long) {
        livePosition?.disconnect(finalPositionMs.coerceAtLeast(0L))
        livePosition = null
    }

    private fun currentPositionSafely(): Long {
        if (!closed && prepared && inFlightSeek == null) capturedPositionMs = positionFromPlatform()
        return capturedPositionMs.coerceAtLeast(0L)
    }

    private fun positionFromPlatform(): Long = try {
        (platform.currentPosition.toLong() - physicalStartMs()).coerceAtLeast(0L).coerceAtMostPositive(segmentDurationMs())
    } catch (_: IllegalStateException) { capturedPositionMs.coerceAtLeast(0L) }

    private fun physicalStartMs(): Long = items.getOrNull(currentIndex)?.mediaItem?.clippingConfiguration?.startPositionUs?.div(1000L) ?: 0L

    private fun segmentDurationMs(): Long {
        if (physicalDurationMs == C.TIME_UNSET) return C.TIME_UNSET
        val clip = currentItem().clippingConfiguration
        val end = if (clip.endPositionUs == C.TIME_END_OF_SOURCE) physicalDurationMs else clip.endPositionUs / 1000L
        return (end - physicalStartMs()).coerceAtLeast(0L)
    }

    private fun currentItem(): MediaItem = checkNotNull(items.getOrNull(currentIndex)).mediaItem

    private fun requireCurrent(expectedMediaId: String) {
        checkMain()
        if (closed) throw sourceError("BACKEND_CLOSED", PlaybackException.ERROR_CODE_INVALID_STATE)
        if (items.getOrNull(currentIndex)?.mediaItem?.mediaId != expectedMediaId) throw sourceError(
            "SOURCE_CHANGED", PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        )
    }

    private fun availableBackendCommands(): Player.Commands {
        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP, Player.COMMAND_RELEASE,
            Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA, Player.COMMAND_GET_VOLUME, Player.COMMAND_SET_VOLUME,
            Player.COMMAND_GET_AUDIO_ATTRIBUTES, Player.COMMAND_SET_SPEED_AND_PITCH,
        )
        if (prepared && seekable && segmentDurationMs() > 0L) commands.addAll(
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
        )
        return commands.build()
    }

    private fun validateAssetRange(asset: AssetFileDescriptor): Long {
        val stat = Os.fstat(asset.fileDescriptor)
        if (!OsConstants.S_ISREG(stat.st_mode)) throw sourceError("SOURCE_NOT_SEEKABLE", PlaybackException.ERROR_CODE_NOT_SUPPORTED)
        Os.lseek(asset.fileDescriptor, 0L, OsConstants.SEEK_CUR)
        val start = asset.startOffset
        if (start < 0L || start > stat.st_size) throw sourceError("ASSET_RANGE_INVALID", PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        val available = stat.st_size - start
        val declared = asset.declaredLength
        val length = if (declared == AssetFileDescriptor.UNKNOWN_LENGTH) available else declared
        if (length <= 0L || length > available) throw sourceError("ASSET_RANGE_INVALID", PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        return length
    }

    private suspend fun await(future: ListenableFuture<Unit>) = suspendCancellableCoroutine<Unit> { continuation ->
        val generation = sourceGeneration
        future.addListener({
            if (!continuation.isActive) return@addListener
            try { continuation.resume(future.get()) }
            catch (error: Exception) { continuation.resumeWithException(error.cause ?: error) }
        }, Executor { if (Looper.myLooper() == Looper.getMainLooper()) it.run() else handler.post(it) })
        continuation.invokeOnCancellation {
            val cancel = {
                future.cancel(false)
                if (isCurrent(generation)) {
                    if (future === prepareFuture && preparing) {
                        val position = pendingPositionMs
                        resetSource()
                        pendingPositionMs = position
                        capturedPositionMs = position
                    } else {
                        playRequested = false
                        pausePlatform()
                    }
                    invalidateState()
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) cancel() else handler.post(cancel)
        }
    }

    private suspend fun awaitWithin(future: ListenableFuture<Unit>, timeoutMs: Long, message: String) {
        if (withTimeoutOrNull(timeoutMs) { await(future) } == null) {
            val error = sourceError(message, PlaybackException.ERROR_CODE_TIMEOUT)
            if (!closed) fail(error)
            throw error
        }
    }

    private fun isCurrent(generation: Long): Boolean = !closed && generation == sourceGeneration

    private fun observeRoutingForCurrentSource() {
        if (routingGeneration == sourceGeneration) return
        val generation = sourceGeneration
        val ownedPlayer = platform
        routingGeneration = generation
        val ticket = route.beginTrack() ?: return
        // API 26/27 走工厂的 null 分支，不加载或调用 MediaPlayer 的 AudioRouting 接口。
        val source = AndroidRoutingSources.mediaPlayer(ownedPlayer, handler) {
            platform === ownedPlayer && !platformReleased && isCurrent(generation) && prepared &&
                !preparing && !buffering && !ended && playRequested && inFlightSeek == null && failure == null
        } ?: return
        route.observe(ticket, source)
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())
    private fun immediate(): ListenableFuture<Unit> = Futures.immediateFuture(Unit)
    private fun sourceError(message: String, @PlaybackException.ErrorCode code: Int) = PlaybackException(message, null, code)
    private fun sameSpeed(first: Float, second: Float): Boolean = kotlin.math.abs(first - second) < 0.001f
    private fun Long.coerceAtMostPositive(maximum: Long): Long = if (maximum > 0L) coerceAtMost(maximum) else this

    companion object {
        private const val MAX_WINDOW_ITEMS = 3
        private const val PREPARE_TIMEOUT_MS = 15_000L
        private const val SEEK_TIMEOUT_MS = 10_000L
        private const val BOUNDARY_CHECK_INTERVAL_MS = 250L
    }
}
