package io.github.onlinemo.meloxwatch.playback.route

import android.media.AudioDeviceInfo
import android.media.AudioRouting
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE, shadows = [SyntheticRoutingDeviceShadow::class])
class Api28MediaPlayerRoutingSourceTest {
    @Test
    fun pausedLastDeviceIsIgnoredAndResumeQueriesTheCurrentDevice() {
        val player = ControlledPlayer()
        try {
            player.outputDevice = syntheticRoutingDevice()
            val source = requireNotNull(AndroidRoutingSources.mediaPlayer(player, Handler(Looper.getMainLooper())) { true })
            assertSame(player, source.objectIdentity)
            assertNull(source.playingDeviceId())
            assertEquals(0, player.routedReads)
            player.active = true
            assertEquals(17, source.playingDeviceId())
            player.active = false
            assertNull(source.playingDeviceId())
            assertEquals(1, player.routedReads)
        } finally { player.closePlatform() }
    }

    @Test
    fun sourceGenerationLossDuringQueryRejectsTheLastDevice() {
        val player = ControlledPlayer()
        try {
            var current = true
            player.active = true
            player.outputDevice = syntheticRoutingDevice()
            player.onRoutedRead = { current = false }
            val source = requireNotNull(AndroidRoutingSources.mediaPlayer(player, Handler(Looper.getMainLooper())) { current })
            assertNull(source.playingDeviceId())
            assertEquals(1, player.routedReads)
            assertNull(source.playingDeviceId())
            assertEquals(1, player.routedReads)
        } finally { player.closePlatform() }
    }

    @Test
    fun mediaPlayerListenerKeepsObjectIdentityAndDoesNotOwnRelease() {
        val player = ControlledPlayer()
        val other = ControlledPlayer()
        try {
            val handler = Handler(Looper.getMainLooper())
            val source = requireNotNull(AndroidRoutingSources.mediaPlayer(player, handler) { true })
            var changed = 0
            val removal = source.addRoutingListener { changed++ }
            val (listener, registeredHandler) = player.listeners.single()
            assertSame(handler, registeredHandler)
            listener.onRoutingChanged(other)
            assertEquals(0, changed)
            listener.onRoutingChanged(player)
            assertEquals(1, changed)
            removal.remove()
            assertEquals(listOf(listener), player.removed)
            assertEquals(0, player.releaseCalls)
        } finally {
            player.closePlatform()
            other.closePlatform()
        }
    }

    private class ControlledPlayer : MediaPlayer() {
        var active = false
        var outputDevice: AudioDeviceInfo? = null
        var routedReads = 0
        var onRoutedRead: (() -> Unit)? = null
        var releaseCalls = 0
        val listeners = mutableListOf<Pair<AudioRouting.OnRoutingChangedListener, Handler?>>()
        val removed = mutableListOf<AudioRouting.OnRoutingChangedListener>()

        override fun isPlaying(): Boolean = active
        override fun getRoutedDevice(): AudioDeviceInfo? {
            routedReads++
            onRoutedRead?.also { onRoutedRead = null }?.invoke()
            return outputDevice
        }
        override fun addOnRoutingChangedListener(listener: AudioRouting.OnRoutingChangedListener, handler: Handler?) {
            listeners.add(listener to handler)
        }
        override fun removeOnRoutingChangedListener(listener: AudioRouting.OnRoutingChangedListener) { removed.add(listener) }
        override fun release() { releaseCalls++ }
        fun closePlatform() = super.release()
    }
}
