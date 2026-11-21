package io.github.onlinemo.meloxwatch.playback.route

import android.media.AudioRouting
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler

internal object AndroidRoutingSources {
    fun mediaPlayer(player: MediaPlayer, handler: Handler, stillCurrent: () -> Boolean): ActualRoutingSource? =
        if (Build.VERSION.SDK_INT >= 28) Api28MediaPlayerRoutingSource(player, handler, stillCurrent) else null

    fun audioTrack(track: AudioTrack, handler: Handler): ActualRoutingSource = AudioTrackRoutingSource(track, handler)
}

private class AudioTrackRoutingSource(
    private val track: AudioTrack,
    private val handler: Handler,
) : ActualRoutingSource {
    override val objectIdentity: Any get() = track

    override fun playingDeviceId(): Int? {
        if (!playing()) return null
        val device = track.routedDevice ?: return null
        if (!device.isSink || !playing()) return null
        return device.id.takeIf { it > 0 }
    }

    private fun playing(): Boolean = track.state == AudioTrack.STATE_INITIALIZED &&
        track.playState == AudioTrack.PLAYSTATE_PLAYING

    override fun addRoutingListener(changed: () -> Unit): RoutingListenerRemoval {
        val listener = AudioRouting.OnRoutingChangedListener { router -> if (router === track) changed() }
        try { track.addOnRoutingChangedListener(listener, handler) }
        catch (failure: RuntimeException) {
            try { track.removeOnRoutingChangedListener(listener) }
            catch (_: RuntimeException) { }
            throw failure
        }
        return RoutingListenerRemoval { track.removeOnRoutingChangedListener(listener) }
    }
}
