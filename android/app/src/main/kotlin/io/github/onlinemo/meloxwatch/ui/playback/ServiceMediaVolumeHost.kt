package io.github.onlinemo.meloxwatch.ui.playback

import android.os.Bundle
import android.os.Looper
import androidx.annotation.MainThread
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture
import io.github.onlinemo.meloxwatch.playback.route.MediaVolumeCommands

/**
 * Keeps one controller connection bound to its generation and application Looper. A failed release
 * retains that exact connection for an explicit cleanup retry; a new connection cannot replace it.
 * The existing service owns playback. This host never creates a player or cancels a command future.
 */
@MainThread
@androidx.annotation.OptIn(UnstableApi::class)
internal class ServiceMediaVolumeHost(private val currentContext: () -> MediaVolumeClientContext) {
    interface Connection {
        val generation: Long
        val applicationLooper: Looper
        val connected: Boolean
        fun send(command: SessionCommand, arguments: Bundle): ListenableFuture<SessionResult>
        fun release()
    }

    private var connection: Connection? = null
    private var retiring: Connection? = null
    private var releasing = false
    val retiringGeneration: Long? get() = retiring?.generation
    val canConnect: Boolean get() = connection == null && retiring == null

    fun attach(candidate: Connection) {
        checkMain()
        require(candidate.generation >= 0L)
        check(candidate.applicationLooper == Looper.getMainLooper())
        check(canConnect) { "Release the previous controller connection before replacing it" }
        connection = candidate
    }

    fun dispatch(
        expected: MediaVolumeClientContext,
        command: SessionCommand,
        arguments: Bundle,
    ): ListenableFuture<SessionResult>? {
        checkMain()
        if (command != MediaVolumeCommands.read && command != MediaVolumeCommands.set && command != MediaVolumeCommands.adjust) return null
        val active = connection ?: return null
        check(Looper.myLooper() == active.applicationLooper)
        if (active.generation != expected.connectionGeneration || !active.connected) return null
        val actual = currentContext()
        if (!expected.eligible || !actual.eligible || expected.ownerId == null ||
            expected.connectionGeneration != actual.connectionGeneration || expected.ownerId != actual.ownerId ||
            expected.backendGeneration != actual.backendGeneration || expected.route != actual.route ||
            connection !== active || !active.connected) return null
        return active.send(command, arguments)
    }

    /** Detach before release callbacks run. A stale timeout can never release a newer connection. */
    fun release(generation: Long): Boolean {
        checkMain()
        val active = retiring ?: connection ?: return false
        if (active.generation != generation || releasing) return false
        check(Looper.myLooper() == active.applicationLooper)
        connection = null
        retiring = active
        releasing = true
        try {
            active.release()
            if (retiring === active) retiring = null
            return true
        } finally {
            releasing = false
        }
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())
}

/** Only this in-flight rotary volume action may ignore its own service-volume pending slot. */
internal fun playbackPendingForMediaVolume(
    playbackPending: Boolean,
    volumeBusy: Boolean,
    ownsRotaryVolume: Boolean,
): Boolean = playbackPending || volumeBusy && !ownsRotaryVolume

internal fun mediaVolumeInputAvailable(
    foreground: Boolean,
    windowFocused: Boolean,
    inputFocused: Boolean,
    pageVisible: Boolean,
    connected: Boolean,
    backendSwitching: Boolean,
    playbackPending: Boolean,
    inputBusy: Boolean,
    ownsRotaryVolume: Boolean,
): Boolean = foreground && windowFocused && inputFocused && pageVisible && connected && !backendSwitching &&
    !playbackPending && (!inputBusy || ownsRotaryVolume)

/** A muted observed zero stays zero in the label even when the system minimum is positive. */
internal fun mediaVolumeSliderFraction(state: ServiceMediaVolumeUiState): Double? {
    val maximum = state.maximum ?: return null
    val range = (maximum - state.minimum).takeIf { it > 0 } ?: return null
    val current = state.preview ?: state.current ?: return null
    return ((current - state.minimum).toDouble() / range.toDouble()).coerceIn(0.0, 1.0)
}
