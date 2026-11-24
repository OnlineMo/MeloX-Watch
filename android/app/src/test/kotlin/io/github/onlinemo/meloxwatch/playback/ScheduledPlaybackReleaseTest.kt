package io.github.onlinemo.meloxwatch.playback

import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.Clock
import androidx.media3.common.util.ListenerSet
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoTimeoutException
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** The real pinned ListenerSet, rather than a copied event loop, exercises recursive release loss. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ScheduledPlaybackReleaseTest {
    @Test fun releaseRequestedInsideAListenerRunsLaterAndCapturesTheNormallyReturnedTimeout() {
        val guard = PlaybackEngineReleaseGuard()
        val lease = guard.acquire()
        val fixture = ListenerSetReleaseFixture().apply {
            releaseError = PlaybackException("Release timed out",
                ExoTimeoutException(ExoTimeoutException.TIMEOUT_OPERATION_RELEASE), PlaybackException.ERROR_CODE_TIMEOUT)
        }
        val release = ScheduledPlaybackRelease(guard, lease, fixture.player)
        lateinit var completion: Deferred<Unit>
        fixture.listeners.add(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                completion = release.start()
                assertFalse(completion.isCompleted)
                assertEquals(0, fixture.releaseCalls)
                assertTrue(guard.blocksCreation())
            }
        })

        fixture.listeners.sendEvent(Player.EVENT_IS_PLAYING_CHANGED) { it.onIsPlayingChanged(false) }
        assertEquals(0, fixture.releaseCalls)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, fixture.releaseCalls)
        assertThrows(PlaybackException::class.java) { runBlocking { completion.await() } }
        assertTrue(guard.hasUnconfirmedRelease())
        assertTrue(guard.isRetained(lease))
        assertEquals(0, fixture.listeners.size())
    }

    @Test fun aPendingReleaseKeepsItsLeaseAndRepeatedStartsShareOneActualRelease() {
        val guard = PlaybackEngineReleaseGuard()
        val lease = guard.acquire()
        val fixture = ListenerSetReleaseFixture()
        val release = ScheduledPlaybackRelease(guard, lease, fixture.player)
        val completion = release.start()

        assertSame(completion, release.start())
        assertEquals(0, fixture.releaseCalls)
        assertTrue(guard.blocksCreation())
        assertThrows(PlaybackReleaseUnconfirmedException::class.java) { guard.acquire() }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        runBlocking { completion.await() }
        assertEquals(1, fixture.releaseCalls)
        assertFalse(guard.blocksCreation())
        assertSame(completion, release.start())
        assertEquals(1, fixture.releaseCalls)
    }

    @Test fun cancellingAnAwaiterCannotCancelTheQueuedNativeReleaseOrItsLease() {
        val guard = PlaybackEngineReleaseGuard()
        val lease = guard.acquire()
        val fixture = ListenerSetReleaseFixture()
        val release = ScheduledPlaybackRelease(guard, lease, fixture.player)
        val completion = release.start()
        val waiter = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val job = waiter.launch { completion.await() }
        job.cancel()

        assertFalse(completion.isCancelled)
        assertTrue(guard.blocksCreation())
        assertEquals(0, fixture.releaseCalls)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        runBlocking { completion.await() }
        assertEquals(1, fixture.releaseCalls)
        assertFalse(guard.blocksCreation())
        waiter.cancel()
    }

    private class ListenerSetReleaseFixture {
        val listeners = ListenerSet<Player.Listener>(Looper.getMainLooper(), Clock.DEFAULT) { _, _ -> }
        var releaseError: PlaybackException? = null
        var releaseCalls = 0
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { proxy, method, arguments ->
            when (method.name) {
                "addListener" -> {
                    listeners.add(arguments!![0] as Player.Listener)
                    null
                }
                "removeListener" -> {
                    listeners.remove(arguments!![0] as Player.Listener)
                    null
                }
                "pause" -> null
                "release" -> {
                    releaseCalls += 1
                    releaseError?.let { error -> listeners.sendEvent(Player.EVENT_PLAYER_ERROR) { it.onPlayerError(error) } }
                    listeners.release()
                    null
                }
                "equals" -> proxy === arguments!![0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "ListenerSetReleaseFixturePlayer"
                else -> throw AssertionError("Unexpected Player call: ${method.name}")
            }
        } as Player
    }
}
