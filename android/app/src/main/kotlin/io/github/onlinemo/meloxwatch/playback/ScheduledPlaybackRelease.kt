package io.github.onlinemo.meloxwatch.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/**
 * ListenerSet defers recursive sendEvent and release then drops those listeners. Always perform
 * native release in a fresh main turn, holding the process lease until that turn confirms release.
 */
@UnstableApi
internal class ScheduledPlaybackRelease(
    private val guard: PlaybackEngineReleaseGuard,
    private val lease: PlaybackEngineReleaseGuard.Lease,
    private val player: Player,
    private val closeOperations: () -> Unit = {},
    private val releasePlayer: () -> Unit = player::release,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var completion: CompletableDeferred<Unit>? = null

    fun start(): Deferred<Unit> {
        check(Looper.myLooper() == Looper.getMainLooper())
        completion?.let { return it }
        guard.markReleaseStarted(lease)
        // This deferred has no parent Job. Cancellation or Service destruction cannot cancel
        // the only native release or free the lease before its dedicated listener has observed it.
        val result = CompletableDeferred<Unit>().also { completion = it }
        val accepted = handler.post {
            try {
                releasePlaybackEngine(guard, lease, player, closeOperations, releasePlayer)
                result.complete(Unit)
            } catch (error: Throwable) {
                result.completeExceptionally(error)
            }
        }
        if (!accepted) {
            guard.quarantine(lease)
            result.completeExceptionally(PlaybackReleaseUnconfirmedException())
        }
        return result
    }
}
