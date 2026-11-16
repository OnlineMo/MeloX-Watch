package io.github.onlinemo.meloxwatch.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoTimeoutException
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Release callback/failure definitions use a non-audio Player double; no decoder is exercised. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class GuardedPlaybackReleaseTest {
    @Test fun aReleaseTimeoutThatReturnsNormallyKeepsTheProcessLeaseAndRemovesItsListener() {
        val guard = PlaybackEngineReleaseGuard()
        val lease = guard.acquire()
        val error = timeout(ExoTimeoutException.TIMEOUT_OPERATION_RELEASE)
        val fixture = ReleaseFixture().apply { releaseError = error }

        assertSame(error, assertThrows(PlaybackException::class.java) {
            releasePlaybackEngine(guard, lease, fixture.player)
        })
        assertEquals(1, fixture.releaseCalls)
        assertTrue(fixture.listenerPresentThroughRelease)
        assertTrue(fixture.listeners.isEmpty())
        assertTrue(guard.hasUnconfirmedRelease())
        assertTrue(guard.isRetained(lease))
        assertThrows(PlaybackReleaseUnconfirmedException::class.java) { guard.acquire() }
    }

    @Test fun aLaterNormalReleaseCannotClearAnEarlierUnconfirmedRelease() {
        val guard = PlaybackEngineReleaseGuard()
        val lease = guard.acquire()
        val fixture = ReleaseFixture().apply { releaseError = timeout(ExoTimeoutException.TIMEOUT_OPERATION_RELEASE) }
        assertThrows(PlaybackException::class.java) { releasePlaybackEngine(guard, lease, fixture.player) }

        fixture.releaseError = null
        assertThrows(PlaybackReleaseUnconfirmedException::class.java) {
            releasePlaybackEngine(guard, lease, fixture.player)
        }
        assertEquals(2, fixture.releaseCalls)
        assertTrue(fixture.listeners.isEmpty())
        assertTrue(guard.isRetained(lease))
        assertThrows(PlaybackReleaseUnconfirmedException::class.java) { guard.acquire() }
    }

    @Test fun aConfirmedNormalReleaseAllowsOneSubsequentEngineLease() {
        val guard = PlaybackEngineReleaseGuard()
        val lease = guard.acquire()
        val fixture = ReleaseFixture()

        releasePlaybackEngine(guard, lease, fixture.player)
        assertTrue(fixture.listenerPresentThroughRelease)
        assertTrue(fixture.listeners.isEmpty())
        assertFalse(guard.blocksCreation())
        assertFalse(guard.hasUnconfirmedRelease())
        val replacement = guard.acquire()
        assertNotSame(lease, replacement)
        assertTrue(guard.isRetained(replacement))
        assertThrows(PlaybackReleaseUnconfirmedException::class.java) { guard.acquire() }
    }

    @Test fun timeoutClassificationRequiresBothTheTimeoutCodeAndReleaseOperation() {
        val errors = listOf(
            timeout(ExoTimeoutException.TIMEOUT_OPERATION_SET_FOREGROUND_MODE),
            timeout(ExoTimeoutException.TIMEOUT_OPERATION_DETACH_SURFACE),
            timeout(ExoTimeoutException.TIMEOUT_OPERATION_UNDEFINED),
            PlaybackException("Other timeout", IllegalStateException(), PlaybackException.ERROR_CODE_TIMEOUT),
            PlaybackException("Other code", ExoTimeoutException(ExoTimeoutException.TIMEOUT_OPERATION_RELEASE),
                PlaybackException.ERROR_CODE_INVALID_STATE),
        )
        for (error in errors) {
            val guard = PlaybackEngineReleaseGuard()
            val lease = guard.acquire()
            val fixture = ReleaseFixture().apply { releaseError = error }
            releasePlaybackEngine(guard, lease, fixture.player)
            assertFalse(guard.blocksCreation())
            assertTrue(fixture.listeners.isEmpty())
        }
    }

    @Test fun aThrownNativeReleaseStillRemovesTheDedicatedListenerAndBlocksCreation() {
        val guard = PlaybackEngineReleaseGuard()
        val lease = guard.acquire()
        val error = IllegalStateException("Release failed")
        val fixture = ReleaseFixture().apply { releaseFailure = error }

        assertSame(error, assertThrows(IllegalStateException::class.java) {
            releasePlaybackEngine(guard, lease, fixture.player)
        })
        assertTrue(fixture.listeners.isEmpty())
        assertTrue(guard.hasUnconfirmedRelease())
        assertThrows(PlaybackReleaseUnconfirmedException::class.java) { guard.acquire() }
    }

    @Test fun operationCleanupFailureStillAttemptsNativeReleaseAndRetainsUnknownOwnership() {
        val guard = PlaybackEngineReleaseGuard()
        val lease = guard.acquire()
        val fixture = ReleaseFixture()
        val error = IllegalStateException("Operation cleanup failed")

        assertSame(error, assertThrows(IllegalStateException::class.java) {
            releasePlaybackEngine(guard, lease, fixture.player, closeOperations = { throw error })
        })
        assertEquals(1, fixture.pauseCalls)
        assertEquals(1, fixture.releaseCalls)
        assertTrue(fixture.listenerPresentThroughRelease)
        assertTrue(fixture.listeners.isEmpty())
        assertTrue(guard.hasUnconfirmedRelease())
    }

    @Test fun aPartlyRegisteredListenerIsRemovedEvenWhenRegistrationThrows() {
        val guard = PlaybackEngineReleaseGuard()
        val lease = guard.acquire()
        val fixture = ReleaseFixture().apply { registrationFailure = IllegalStateException("Registration failed") }

        assertThrows(IllegalStateException::class.java) { releasePlaybackEngine(guard, lease, fixture.player) }
        assertEquals(1, fixture.releaseCalls)
        assertEquals(1, fixture.removeCalls)
        assertTrue(fixture.listeners.isEmpty())
        assertTrue(guard.hasUnconfirmedRelease())
    }

    @Test fun aForeignOrAlreadyRetiredLeaseCannotReleaseAnotherOwnersEngine() {
        val guard = PlaybackEngineReleaseGuard()
        val other = PlaybackEngineReleaseGuard()
        val current = guard.acquire()
        val foreign = other.acquire()
        val fixture = ReleaseFixture()

        assertThrows(IllegalStateException::class.java) { releasePlaybackEngine(guard, foreign, fixture.player) }
        assertEquals(0, fixture.releaseCalls)
        assertTrue(guard.isRetained(current))
        assertTrue(other.isRetained(foreign))
        releasePlaybackEngine(guard, current, fixture.player)
        val replacement = guard.acquire()
        assertThrows(IllegalStateException::class.java) { releasePlaybackEngine(guard, current, fixture.player) }
        assertTrue(guard.isRetained(replacement))
        assertNull(fixture.releaseError)
    }

    private fun timeout(@ExoTimeoutException.TimeoutOperation operation: Int) = PlaybackException(
        "Playback operation timed out", ExoTimeoutException(operation), PlaybackException.ERROR_CODE_TIMEOUT,
    )

    private class ReleaseFixture {
        val listeners = mutableListOf<Player.Listener>()
        var releaseError: PlaybackException? = null
        var releaseFailure: RuntimeException? = null
        var registrationFailure: RuntimeException? = null
        var pauseCalls = 0
        var releaseCalls = 0
        var removeCalls = 0
        var listenerPresentThroughRelease = false
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { proxy, method, arguments ->
            when (method.name) {
                "addListener" -> {
                    listeners += arguments!![0] as Player.Listener
                    registrationFailure?.let { throw it }
                    null
                }
                "removeListener" -> {
                    removeCalls += 1
                    listeners.remove(arguments!![0] as Player.Listener)
                    null
                }
                "pause" -> {
                    pauseCalls += 1
                    null
                }
                "release" -> {
                    releaseCalls += 1
                    listenerPresentThroughRelease = listeners.size == 1
                    releaseError?.let { error -> listeners.toList().forEach { it.onPlayerError(error) } }
                    listenerPresentThroughRelease = listenerPresentThroughRelease && listeners.size == 1
                    releaseFailure?.let { throw it }
                    null
                }
                "equals" -> proxy === arguments!![0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "ReleaseFixturePlayer"
                else -> throw AssertionError("Unexpected Player call: ${method.name}")
            }
        } as Player
    }
}
