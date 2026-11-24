package io.github.onlinemo.meloxwatch.playback.route

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Public platform objects under Robolectric; synthetic route values are not device acceptance. */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], manifest = Config.NONE, shadows = [SyntheticRoutingDeviceShadow::class])
class RoutingAudioTrackProviderTest {
    @Test
    fun providerReturnsTheExactAllocatedObjectAndPreservesEveryDelegateArgument() {
        val fixture = RouteTestFixture()
        val track = ControlledRoutingAudioTrack()
        try {
            track.outputDevice = syntheticRoutingDevice()
            fixture.route.setPlaybackActive(true)
            val context = RuntimeEnvironment.getApplication() as Context
            val attributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).build()
            val delegate = RecordingProvider { track }
            val provider = RoutingAudioTrackProvider(fixture.route, delegate)
            assertSame(track, provider.getAudioTrack(CONFIGURATION, attributes, 42, context))
            val forwarded = delegate.arguments.single()
            assertSame(CONFIGURATION, forwarded.configuration)
            assertSame(attributes, forwarded.attributes)
            assertEquals(42, forwarded.sessionId)
            assertSame(context, forwarded.context)
            assertEquals(1, track.listeners.size)
            assertSame(Looper.myLooper(), requireNotNull(track.listeners.single().second).looper)
            assertTrue(fixture.route.read().known)
            assertEquals(0, track.releaseCalls)
        } finally {
            fixture.route.close()
            track.closePlatform()
        }
    }

    @Test
    fun defaultProviderStillOwnsActualTrackConstructionAndChannelMaskSelection() {
        val fixture = RouteTestFixture()
        var allocated: AudioTrack? = null
        val delegate = object : DefaultAudioSink.AudioTrackProvider {
            override fun getAudioTrack(
                audioTrackConfig: AudioSink.AudioTrackConfig,
                audioAttributes: AudioAttributes,
                audioSessionId: Int,
                context: Context?,
            ): AudioTrack = DefaultAudioSink.AudioTrackProvider.DEFAULT.getAudioTrack(
                audioTrackConfig, audioAttributes, audioSessionId, context,
            ).also { allocated = it }

            override fun getAudioTrackChannelConfig(channelCount: Int): Int =
                DefaultAudioSink.AudioTrackProvider.DEFAULT.getAudioTrackChannelConfig(channelCount)
        }
        try {
            val provider = RoutingAudioTrackProvider(fixture.route, delegate)
            val track = provider.getAudioTrack(CONFIGURATION, AudioAttributes.DEFAULT, 0, null)
            assertSame(allocated, track)
            assertEquals(AudioTrack.STATE_INITIALIZED, track.state)
            assertEquals(AudioFormat.CHANNEL_OUT_STEREO, provider.getAudioTrackChannelConfig(2))
            // A successfully allocated paused track supplies no actual routed output yet.
            assertFalse(fixture.route.read().known)
        } finally {
            fixture.route.close()
            allocated?.release()
        }
    }

    @Test
    fun sourceChangeInsideAllocationRejectsTheOldTicketWithoutReplacingOrReleasingTheReturnedTrack() {
        val fixture = RouteTestFixture()
        val old = fixture.attach()
        fixture.route.setPlaybackActive(true)
        val track = ControlledRoutingAudioTrack()
        try {
            track.outputDevice = syntheticRoutingDevice()
            var changeDuringAllocation = true
            val delegate = RecordingProvider {
                assertFalse(fixture.route.cachedState().known)
                if (changeDuringAllocation) fixture.route.sourceChanged()
                track
            }
            val provider = RoutingAudioTrackProvider(fixture.route, delegate)
            assertSame(track, provider.getAudioTrack(CONFIGURATION, AudioAttributes.DEFAULT, 0, null))
            assertTrue(track.listeners.isEmpty())
            assertFalse(fixture.route.read().known)
            old.callbacks.single().invoke()
            assertFalse(fixture.route.read().known)
            assertEquals(1, old.removals)
            assertEquals(0, track.releaseCalls)
            changeDuringAllocation = false
            provider.getAudioTrack(CONFIGURATION, AudioAttributes.DEFAULT, 0, null)
            assertEquals(1, track.listeners.size)
            assertTrue(fixture.route.read().known)
        } finally {
            fixture.route.close()
            track.closePlatform()
        }
    }

    @Test
    fun uninitializedReturnedTrackIsLeftToTheSinkWithoutRegisteringAnObserver() {
        val fixture = RouteTestFixture()
        val track = ControlledRoutingAudioTrack()
        try {
            track.platformState = AudioTrack.STATE_UNINITIALIZED
            fixture.route.setPlaybackActive(true)
            val provider = RoutingAudioTrackProvider(fixture.route, RecordingProvider { track })
            assertSame(track, provider.getAudioTrack(CONFIGURATION, AudioAttributes.DEFAULT, 0, null))
            assertTrue(track.listeners.isEmpty())
            assertFalse(fixture.route.read().known)
            assertEquals(0, track.releaseCalls)
        } finally {
            fixture.route.close()
            track.closePlatform()
        }
    }

    @Test
    fun creationFailureRetiresThePriorObservationBeforeTheDelegateThrows() {
        val fixture = RouteTestFixture()
        val old = fixture.attach()
        fixture.route.setPlaybackActive(true)
        val failure = IllegalStateException("platform allocation rejected")
        val provider = RoutingAudioTrackProvider(fixture.route, RecordingProvider { throw failure })
        try {
            provider.getAudioTrack(CONFIGURATION, AudioAttributes.DEFAULT, 0, null)
            throw AssertionError("The original allocation failure must propagate")
        } catch (caught: IllegalStateException) {
            assertSame(failure, caught)
        } finally {
            old.callbacks.single().invoke()
            assertFalse(fixture.route.read().known)
            assertEquals(1, old.removals)
            fixture.route.close()
        }
    }

    @Test(timeout = 10000L)
    fun allocationWithoutAThreadLooperDoesNotCreateAReplacementTrackOrObserveFromAnotherThread() {
        val fixture = RouteTestFixture()
        val track = ControlledRoutingAudioTrack()
        val returned = AtomicReference<AudioTrack>()
        val failure = AtomicReference<Throwable>()
        val completed = CountDownLatch(1)
        try {
            track.outputDevice = syntheticRoutingDevice()
            fixture.route.setPlaybackActive(true)
            val provider = RoutingAudioTrackProvider(fixture.route, RecordingProvider { track })
            val worker = Thread {
                try {
                    assertNull(Looper.myLooper())
                    returned.set(provider.getAudioTrack(CONFIGURATION, AudioAttributes.DEFAULT, 0, null))
                } catch (caught: Throwable) {
                    failure.set(caught)
                } finally {
                    completed.countDown()
                }
            }
            worker.start()
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
            assertSame(track, returned.get())
            assertTrue(track.listeners.isEmpty())
            assertFalse(fixture.route.read().known)
            assertEquals(0, track.releaseCalls)
        } finally {
            fixture.route.close()
            track.closePlatform()
        }
    }

    @Test
    fun providerForwardsChannelCountWithoutAssumingAFixedOutputMask() {
        val fixture = RouteTestFixture()
        val delegate = RecordingProvider { throw AssertionError("Channel selection must not allocate a track") }
        val provider = RoutingAudioTrackProvider(fixture.route, delegate)
        assertEquals(12345, provider.getAudioTrackChannelConfig(7))
        assertEquals(listOf(7), delegate.channelCounts)
        assertTrue(delegate.arguments.isEmpty())
        assertFalse(fixture.route.read().known)
    }

    private class RecordingProvider(private val allocate: () -> AudioTrack) : DefaultAudioSink.AudioTrackProvider {
        val arguments = mutableListOf<Arguments>()
        val channelCounts = mutableListOf<Int>()

        override fun getAudioTrack(
            audioTrackConfig: AudioSink.AudioTrackConfig,
            audioAttributes: AudioAttributes,
            audioSessionId: Int,
            context: Context?,
        ): AudioTrack {
            arguments.add(Arguments(audioTrackConfig, audioAttributes, audioSessionId, context))
            return allocate()
        }

        override fun getAudioTrackChannelConfig(channelCount: Int): Int {
            channelCounts.add(channelCount)
            return 12345
        }
    }

    private data class Arguments(
        val configuration: AudioSink.AudioTrackConfig,
        val attributes: AudioAttributes,
        val sessionId: Int,
        val context: Context?,
    )

    companion object {
        private val CONFIGURATION = AudioSink.AudioTrackConfig(
            C.ENCODING_PCM_16BIT, 48_000, AudioFormat.CHANNEL_OUT_STEREO, false, false, 4096,
        )
    }
}
