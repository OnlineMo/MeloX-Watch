package io.github.onlinemo.meloxwatch.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch

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
    private val afterNativeRelease: (suspend () -> Unit)? = null,
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
                val retirement = afterNativeRelease
                if (retirement == null) {
                    releasePlaybackEngine(guard, lease, player, closeOperations, releasePlayer)
                    result.complete(Unit)
                } else {
                    observePlaybackEngineRelease(guard, lease, player, closeOperations, releasePlayer)
                    // 一次无父任务且有界的物理退休归同一进程engine lease所有。
                    CoroutineScope(NonCancellable + Dispatchers.Main.immediate).launch {
                        try {
                            retirement()
                            guard.confirmReleased(lease)
                            result.complete(Unit)
                        } catch (failure: Throwable) {
                            guard.quarantine(lease)
                            result.completeExceptionally(failure)
                        }
                    }
                }
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
