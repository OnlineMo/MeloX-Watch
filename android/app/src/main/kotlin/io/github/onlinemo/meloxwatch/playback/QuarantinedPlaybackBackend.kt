package io.github.onlinemo.meloxwatch.playback

import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** No decoder, MediaPlayer, ExoPlayer or AudioTrack exists behind this inspectable paused state. */
@UnstableApi
internal class QuarantinedPlaybackBackend(private val guard: PlaybackEngineReleaseGuard) :
    SimpleBasePlayer(Looper.getMainLooper()), PlaybackBackendOperations {
    override val player: Player get() = this

    override fun getState(): State = State.Builder()
        .setAvailableCommands(Player.Commands.Builder().addAll(
            Player.COMMAND_RELEASE, Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA,
        ).build())
        .setPlaybackState(Player.STATE_IDLE)
        .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .setPlayerError(unavailable())
        .build()

    override fun setPauseAtBoundary(pause: Boolean) = Unit

    override suspend fun prepareAndAwait(expectedMediaId: String): Unit = throw unavailable()

    override suspend fun seekAndAwait(expectedMediaId: String, positionMs: Long): Unit = throw unavailable()

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateFuture(Unit)

    private fun unavailable() = PlaybackException(
        if (guard.hasUnconfirmedRelease()) "RELEASE_UNCONFIRMED" else "BACKEND_UNAVAILABLE",
        null, PlaybackException.ERROR_CODE_INVALID_STATE,
    )
}
