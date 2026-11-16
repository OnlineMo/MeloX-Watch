package io.github.onlinemo.meloxwatch.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import io.github.onlinemo.meloxwatch.model.PlaybackPreferenceLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Media3 has a masking position; a playback-thread fence prevents treating that as a completed seek. */
@UnstableApi
class Media3PlaybackOperations(override val player: Player) : PlaybackBackendOperations {
    private val exoPlayer = requireNotNull(player as? ExoPlayer)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val waiters = LinkedHashMap<Player.Listener, () -> Unit>()
    private var closed = false

    override fun setPauseAtBoundary(pause: Boolean) {
        checkMain()
        if (!closed) exoPlayer.setPauseAtEndOfMediaItems(pause)
    }

    override suspend fun prepareAndAwait(expectedMediaId: String) = withContext(Dispatchers.Main.immediate) {
        awaitReady(expectedMediaId, PREPARE_TIMEOUT_MS, allowEnded = false) { acknowledge ->
            if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) player.prepare()
            acknowledge()
            null
        }
    }

    override suspend fun seekAndAwait(expectedMediaId: String, positionMs: Long) = withContext(Dispatchers.Main.immediate) {
        require(positionMs >= 0L)
        awaitReady(expectedMediaId, SEEK_TIMEOUT_MS, allowEnded = true) { acknowledge ->
            player.seekTo(positionMs)
            // This message is processed after the seek on ExoPlayer's playback thread. The state
            // may still be buffering afterwards, so the listener also waits for actual READY.
            exoPlayer.createMessage { _, _ -> mainHandler.post(acknowledge) }.send()
        }
    }

    internal suspend fun applyPlaybackParameters(tenths: Int): PlaybackSpeedState = withContext(Dispatchers.Main.immediate) {
        checkMain()
        require(tenths in PlaybackPreferenceLimits.MIN_SPEED_TENTHS..PlaybackPreferenceLimits.MAX_SPEED_TENTHS)
        if (closed) throw sourceError("BACKEND_CLOSED", PlaybackException.ERROR_CODE_INVALID_STATE)
        player.playbackParameters = PlaybackParameters(tenths / 10f, 1f)
        withTimeoutOrNull(PARAMETERS_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val message = exoPlayer.createMessage { _, _ ->
                    mainHandler.post {
                        if (continuation.isActive) {
                            if (closed) continuation.resumeWithException(sourceError("BACKEND_CLOSED", PlaybackException.ERROR_CODE_INVALID_STATE))
                            else continuation.resume(Unit)
                        }
                    }
                }.send()
                continuation.invokeOnCancellation { message.cancel() }
            }
        } ?: throw sourceError("PARAMETERS_TIMED_OUT", PlaybackException.ERROR_CODE_TIMEOUT)
        if (closed) throw sourceError("BACKEND_CLOSED", PlaybackException.ERROR_CODE_INVALID_STATE)
        val actual = player.playbackParameters
        PlaybackSpeedState(tenths, actual.speed, actual.pitch, pending = false,
            issue = if (kotlin.math.abs(actual.speed - tenths / 10f) < 0.001f && actual.pitch == 1f) null else PlaybackBackendIssue.SPEED_UNSUPPORTED)
    }

    internal fun close() {
        checkMain()
        closed = true
        waiters.values.toList().forEach { it() }
    }

    private suspend fun awaitReady(
        expectedMediaId: String,
        timeoutMs: Long,
        allowEnded: Boolean,
        start: (acknowledge: () -> Unit) -> PlayerMessage?,
    ) {
        withTimeoutOrNull(timeoutMs) {
            checkMain()
            if (closed) throw sourceError("BACKEND_CLOSED", PlaybackException.ERROR_CODE_INVALID_STATE)
            if (player.currentMediaItem?.mediaId != expectedMediaId) throw sourceError(
                "SOURCE_CHANGED", PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            )
            player.playerError?.let { throw it }
            suspendCancellableCoroutine<Unit> { continuation ->
                var acknowledged = false
                var message: PlayerMessage? = null
                lateinit var listener: Player.Listener
                fun removeListener() {
                    waiters.remove(listener)
                    player.removeListener(listener)
                }
                fun inspect() {
                    if (!continuation.isActive) return
                    val error = when {
                        closed -> sourceError("BACKEND_CLOSED", PlaybackException.ERROR_CODE_INVALID_STATE)
                        player.currentMediaItem?.mediaId != expectedMediaId -> sourceError(
                            "SOURCE_CHANGED", PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                        )
                        else -> player.playerError
                    }
                    if (error != null) {
                        removeListener()
                        continuation.resumeWithException(error)
                    } else if (acknowledged && (player.playbackState == Player.STATE_READY ||
                        allowEnded && player.playbackState == Player.STATE_ENDED && player.duration != C.TIME_UNSET &&
                        player.duration > 0L && player.currentPosition >= player.duration)) {
                        removeListener()
                        continuation.resume(Unit)
                    }
                }
                listener = object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) = inspect()
                }
                player.addListener(listener)
                waiters[listener] = {
                    removeListener()
                    if (continuation.isActive) continuation.resumeWithException(
                        sourceError("BACKEND_CLOSED", PlaybackException.ERROR_CODE_INVALID_STATE),
                    )
                }
                continuation.invokeOnCancellation {
                    val cancel = {
                        if (waiters.containsKey(listener)) removeListener()
                        message?.cancel()
                        if (!closed && player.currentMediaItem?.mediaId == expectedMediaId) player.pause()
                    }
                    if (Looper.myLooper() == Looper.getMainLooper()) cancel() else mainHandler.post(cancel)
                }
                try {
                    message = start { acknowledged = true; inspect() }
                    inspect()
                } catch (error: Exception) {
                    removeListener()
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        } ?: throw sourceError("SOURCE_TIMED_OUT", PlaybackException.ERROR_CODE_TIMEOUT)
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())

    private fun sourceError(message: String, @PlaybackException.ErrorCode code: Int) = PlaybackException(message, null, code)

    companion object {
        private const val PREPARE_TIMEOUT_MS = 15_000L
        private const val SEEK_TIMEOUT_MS = 10_000L
        private const val PARAMETERS_TIMEOUT_MS = 5000L
    }
}
