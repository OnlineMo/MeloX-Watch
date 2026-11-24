package io.github.onlinemo.meloxwatch.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoTimeoutException

/**
 * Media3 1.8.0 reports RELEASE timeout to a listener and returns normally. Keep this dedicated
 * listener through release, independently of old generation-fenced observers, before clearing lease.
 */
@UnstableApi
internal fun releasePlaybackEngine(
    guard: PlaybackEngineReleaseGuard,
    lease: PlaybackEngineReleaseGuard.Lease,
    player: Player,
    closeOperations: () -> Unit = {},
    releasePlayer: () -> Unit = player::release,
) {
    guard.requireRetained(lease)
    var failure: Throwable? = null
    var releasing = false
    var listenerInstalled = false
    fun record(error: Throwable) {
        val first = failure
        if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
    }
    fun attempt(operation: () -> Unit) {
        try { operation() } catch (error: Throwable) { record(error) }
    }
    val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val cause = error.cause as? ExoTimeoutException
            if (releasing && error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT &&
                cause?.timeoutOperation == ExoTimeoutException.TIMEOUT_OPERATION_RELEASE) record(error)
        }
    }
    attempt {
        listenerInstalled = true
        player.addListener(listener)
    }
    try {
        attempt(closeOperations)
        attempt(player::pause)
        releasing = true
        try { attempt(releasePlayer) } finally { releasing = false }
    } finally {
        if (listenerInstalled) attempt { player.removeListener(listener) }
    }
    val error = failure
    if (error != null) {
        guard.quarantine(lease)
        throw error
    }
    // A prior failure is sticky even if another release call has returned normally.
    guard.confirmReleased(lease)
}
