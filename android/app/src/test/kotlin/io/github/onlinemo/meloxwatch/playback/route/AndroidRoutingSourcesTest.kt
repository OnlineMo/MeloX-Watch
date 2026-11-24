package io.github.onlinemo.meloxwatch.playback.route

import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], manifest = Config.NONE, shadows = [SyntheticRoutingDeviceShadow::class])
class AndroidRoutingSourcesTest {
    @Test
    fun actualTrackQueryRequiresInitializedAndPlayingStateAroundThePublicRead() {
        val track = ControlledRoutingAudioTrack()
        try {
            track.outputDevice = syntheticRoutingDevice()
            val source = AndroidRoutingSources.audioTrack(track, Handler(Looper.getMainLooper()))
            assertSame(track, source.objectIdentity)
            assertEquals(17, source.playingDeviceId())
            track.platformPlayState = AudioTrack.PLAYSTATE_PAUSED
            assertNull(source.playingDeviceId())
            assertEquals(1, track.routedReads)
            track.platformPlayState = AudioTrack.PLAYSTATE_PLAYING
            track.onRoutedRead = { track.platformPlayState = AudioTrack.PLAYSTATE_PAUSED }
            assertNull(source.playingDeviceId())
            track.platformState = AudioTrack.STATE_UNINITIALIZED
            assertNull(source.playingDeviceId())
            assertEquals(2, track.routedReads)
        } finally { track.closePlatform() }
    }

    @Test
    fun invalidOrNonOutputDeviceIsUnknown() {
        val track = ControlledRoutingAudioTrack()
        try {
            val source = AndroidRoutingSources.audioTrack(track, Handler(Looper.getMainLooper()))
            track.outputDevice = syntheticRoutingDevice(0)
            assertNull(source.playingDeviceId())
            track.outputDevice = syntheticRoutingDevice()
            Shadow.extract<SyntheticRoutingDeviceShadow>(requireNotNull(track.outputDevice)).output = false
            assertNull(source.playingDeviceId())
        } finally { track.closePlatform() }
    }

    @Test
    fun routingCallbackUsesExactTrackIdentityAndTheSuppliedPlaybackHandler() {
        val track = ControlledRoutingAudioTrack()
        val other = ControlledRoutingAudioTrack()
        try {
            val handler = Handler(Looper.getMainLooper())
            var calls = 0
            val removal = AndroidRoutingSources.audioTrack(track, handler).addRoutingListener { calls++ }
            val (listener, registeredHandler) = track.listeners.single()
            assertSame(handler, registeredHandler)
            listener.onRoutingChanged(other)
            assertEquals(0, calls)
            listener.onRoutingChanged(track)
            assertEquals(1, calls)
            removal.remove()
            assertEquals(listOf(listener), track.removed)
            assertEquals(0, track.releaseCalls)
            assertTrue(other.listeners.isEmpty())
        } finally {
            track.closePlatform()
            other.closePlatform()
        }
    }

    @Test
    @Config(sdk = [26])
    fun api26MediaPlayerPathDoesNotRegisterOrCastAudioRouting() {
        val player = MediaPlayer()
        try {
            var ownershipReads = 0
            assertNull(AndroidRoutingSources.mediaPlayer(player, Handler(Looper.getMainLooper())) {
                ownershipReads++
                true
            })
            assertEquals(0, ownershipReads)
        } finally { player.release() }
    }
}
