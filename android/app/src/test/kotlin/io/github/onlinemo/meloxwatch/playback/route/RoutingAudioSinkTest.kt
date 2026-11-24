package io.github.onlinemo.meloxwatch.playback.route

import android.media.AudioFormat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** self-flush 测试是固定 1.8.0 生命周期模型，不声称已运行真实 AudioTrack 或设备路径。 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class RoutingAudioSinkTest {
    @Test
    fun flushRetiresBeforeNativeDelegationEvenWhenTheDelegateThrows() {
        val fixture = Fixture()
        fixture.delegate.onCall = { name ->
            if (name == "flush") {
                assertFalse(fixture.owner.route.cachedState().known)
                throw IllegalStateException("native flush unavailable")
            }
        }
        rejects { fixture.sink.flush() }
        fixture.source.callbacks.first().invoke()
        assertFalse(fixture.owner.route.read().known)
        assertEquals(1, fixture.source.removals)
    }

    @Test
    fun resetAndReleaseRetireBeforeDelegationWithoutReleasingObservedObjects() {
        val operations: List<(RoutingAudioSink) -> Unit> = listOf({ it.reset() }, { it.release() })
        for (operation in operations) {
            val fixture = Fixture()
            fixture.delegate.onCall = { name ->
                if (name == "reset" || name == "release") assertFalse(fixture.owner.route.cachedState().known)
            }
            operation(fixture.sink)
            assertFalse(fixture.owner.route.read().known)
            assertEquals(1, fixture.source.removals)
            assertTrue(fixture.owner.guard.isRetained(fixture.owner.lease))
        }
    }

    @Test
    fun reusableConfigurationRebindsTheSameTrackUnderANewSourceTicket() {
        val fixture = Fixture()
        val oldOwner = fixture.owner.route.read().ownerId
        val oldCallback = fixture.source.callbacks.last()
        fixture.delegate.onCall = { if (it == "configure") assertFalse(fixture.owner.route.cachedState().known) }
        fixture.sink.configure(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).build(), 4096, null)
        val replacement = fixture.owner.route.read().ownerId
        assertNotEquals(oldOwner, replacement)
        oldCallback()
        assertEquals(replacement, fixture.owner.route.read().ownerId)
        assertEquals(2, fixture.source.callbacks.size)
    }

    @Test
    fun attributesSessionTunnelingAndDiscontinuityInvalidateBeforeTheirInternalWork() {
        val operations: List<(RoutingAudioSink) -> Unit> = listOf(
            { it.setAudioAttributes(AudioAttributes.DEFAULT) },
            { it.setAudioSessionId(23) },
            { it.enableTunnelingV21() },
            { it.disableTunneling() },
            { it.handleDiscontinuity() },
        )
        for (operation in operations) {
            val fixture = Fixture()
            val old = fixture.source.callbacks.last()
            val previous = fixture.owner.route.read().ownerId
            fixture.delegate.onCall = { assertFalse(fixture.owner.route.cachedState().known) }
            operation(fixture.sink)
            val current = fixture.owner.route.read().ownerId
            assertNotEquals(previous, current)
            old()
            assertEquals(current, fixture.owner.route.read().ownerId)
        }
    }

    @Test
    fun nonReusablePendingConfigurationSelfFlushModelRejectsThePausedOldTrack() {
        val fixture = Fixture()
        val prior = fixture.owner.route.read().ownerId
        fixture.delegate.bufferResult = false
        fixture.delegate.onCall = { if (it == "handleBuffer") fixture.source.playing = false }
        assertFalse(fixture.buffer())
        assertFalse(fixture.owner.route.read().known)
        fixture.source.callbacks.last().invoke()
        assertFalse(fixture.owner.route.read().known)
        val replacementSource = fixture.owner.attach()
        val replacement = fixture.owner.route.read().ownerId
        assertNotEquals(prior, replacement)
        fixture.source.callbacks.first().invoke()
        assertEquals(replacement, fixture.owner.route.read().ownerId)
        assertTrue(replacementSource.callbacks.isNotEmpty())
    }

    @Test
    fun stalledTrackSelfFlushModelReturnsTrueWhileActualRouteBecomesUnknown() {
        val fixture = Fixture()
        fixture.delegate.bufferResult = true
        fixture.delegate.onCall = { if (it == "handleBuffer") fixture.source.playing = false }
        assertTrue(fixture.buffer())
        assertFalse(fixture.owner.route.cachedState().known)
        fixture.source.callbacks.last().invoke()
        assertFalse(fixture.owner.route.read().known)
    }

    @Test
    fun providerReplacementInsideBufferHandlingCannotBeClearedByTheOldTrackCallback() {
        val fixture = Fixture()
        var replacementOwner: String? = null
        fixture.delegate.onCall = { name ->
            if (name == "handleBuffer") {
                fixture.source.playing = false
                fixture.owner.attach(FakeRoutingSource().apply { deviceId = 9 })
                replacementOwner = fixture.owner.route.read().ownerId
                fixture.source.callbacks.first().invoke()
            }
        }
        fixture.buffer()
        assertEquals(replacementOwner, fixture.owner.route.read().ownerId)
        assertTrue(fixture.owner.route.read().known)
    }

    @Test
    fun asynchronousReleaseWithMatchingConfigurationDoesNotIdentifyTheCurrentTrack() {
        val fixture = Fixture()
        var releases = 0
        fixture.sink.setListener(object : AudioSink.Listener {
            override fun onPositionDiscontinuity() = Unit
            override fun onUnderrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) = Unit
            override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = Unit
            override fun onAudioTrackReleased(audioTrackConfig: AudioSink.AudioTrackConfig) { releases++ }
        })
        val configuration = AudioSink.AudioTrackConfig(C.ENCODING_PCM_16BIT, 48_000, AudioFormat.CHANNEL_OUT_STEREO,
            false, false, 4096)
        requireNotNull(fixture.delegate.listener).onAudioTrackInitialized(configuration)
        fixture.owner.attach()
        val current = fixture.owner.route.read().ownerId
        requireNotNull(fixture.delegate.listener).onAudioTrackReleased(configuration)
        assertEquals(1, releases)
        assertEquals(current, fixture.owner.route.read().ownerId)
    }

    @Test
    fun pauseAndLatePlayBufferCallsCannotReopenTheServicePauseGate() {
        val fixture = Fixture()
        fixture.owner.route.setPlaybackActive(false)
        fixture.delegate.onCall = { if (it == "pause") assertFalse(fixture.owner.route.cachedState().known) }
        fixture.sink.pause()
        fixture.sink.play()
        fixture.buffer()
        assertFalse(fixture.owner.route.read().known)
        fixture.owner.route.setPlaybackActive(true)
        assertTrue(fixture.owner.route.read().known)
    }

    @Test
    fun bufferFailureRetiresTheSourceAndKeepsLateCallbacksUnknown() {
        val fixture = Fixture()
        fixture.delegate.onCall = { if (it == "handleBuffer") throw IllegalStateException("native write rejected") }
        rejects { fixture.buffer() }
        fixture.source.callbacks.first().invoke()
        assertFalse(fixture.owner.route.read().known)
    }

    private class Fixture {
        val owner = RouteTestFixture()
        val delegate = SinkDelegate()
        val sink = RoutingAudioSink(delegate.sink, owner.route)
        val source = owner.attach()

        init {
            owner.route.setPlaybackActive(true)
            sink.play()
        }

        fun buffer(): Boolean = sink.handleBuffer(ByteBuffer.allocate(8), 0L, 1)
    }

    /** 只代理公开的测试接口；不反射平台/Media3 私有状态。 */
    private class SinkDelegate {
        var bufferResult = true
        var onCall: (String) -> Unit = {}
        var listener: AudioSink.Listener? = null
        val sink: AudioSink = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) {
            proxy, method, arguments ->
            when (method.name) {
                "equals" -> proxy === arguments?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "public AudioSink fixture"
                "setListener" -> {
                    listener = arguments?.firstOrNull() as AudioSink.Listener
                    null
                }
                else -> {
                    onCall(method.name)
                    when (method.returnType) {
                        java.lang.Boolean.TYPE -> if (method.name == "handleBuffer") bufferResult else false
                        java.lang.Integer.TYPE -> 0
                        java.lang.Long.TYPE -> 0L
                        else -> null
                    }
                }
            }
        } as AudioSink
    }

    private fun rejects(action: () -> Unit) {
        try { action() }
        catch (_: IllegalStateException) { return }
        throw AssertionError("Expected native delegate failure")
    }
}
