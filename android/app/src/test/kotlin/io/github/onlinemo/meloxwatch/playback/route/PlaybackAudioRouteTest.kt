package io.github.onlinemo.meloxwatch.playback.route

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackAudioRouteTest {
    @Test
    fun noTrackAndNonPlayingTrackRemainUnknown() {
        val fixture = RouteTestFixture()
        fixture.route.setPlaybackActive(true)
        assertFalse(fixture.route.read().known)
        val source = fixture.attach(FakeRoutingSource().apply { playing = false })
        assertFalse(fixture.route.read().known)
        source.playing = true
        assertTrue(fixture.route.read().known)
        source.initialized = false
        assertFalse(fixture.route.read().known)
    }

    @Test
    fun ordinaryFreshQueriesPreserveTheOpaqueOwner() {
        val fixture = playingFixture()
        val owner = fixture.route.read().ownerId
        repeat(30) { assertEquals(owner, fixture.route.read().ownerId) }
        assertTrue(requireNotNull(owner).length == 36)
    }

    @Test
    fun sameDeviceNotificationRetiresThePriorOwner() {
        val fixture = RouteTestFixture()
        val source = fixture.attach()
        fixture.route.setPlaybackActive(true)
        val first = fixture.route.read().ownerId
        source.callbacks.single().invoke()
        assertTrue(fixture.route.read().known)
        assertNotEquals(first, fixture.route.read().ownerId)
    }

    @Test
    fun observedAToBToAReturnsWithANewOwner() {
        val fixture = RouteTestFixture()
        val source = fixture.attach()
        fixture.route.setPlaybackActive(true)
        val first = fixture.route.read().ownerId
        source.deviceId = 8
        source.callbacks.single().invoke()
        val second = fixture.route.read().ownerId
        source.deviceId = 7
        source.callbacks.single().invoke()
        assertNotEquals(first, second)
        assertNotEquals(first, fixture.route.read().ownerId)
        assertNotEquals(second, fixture.route.read().ownerId)
    }

    @Test
    fun synchronousQueryDetectsADeviceChangeBeforeItsCallback() {
        val fixture = RouteTestFixture()
        val source = fixture.attach()
        fixture.route.setPlaybackActive(true)
        val first = fixture.route.read().ownerId
        source.deviceId = 8
        assertNotEquals(first, fixture.route.read().ownerId)
    }

    @Test
    fun pauseInvalidatesAndLateSinkPlayCannotReopenTheMainThreadGate() {
        val fixture = playingFixture()
        val first = fixture.route.read().ownerId
        fixture.route.setPlaybackActive(false)
        fixture.route.setRenderingActive(true)
        assertNull(fixture.route.read().ownerId)
        fixture.route.setPlaybackActive(true)
        assertNotEquals(first, fixture.route.read().ownerId)
    }

    @Test
    fun rendererPauseAlsoRejectsAnOtherwisePlayingSource() {
        val fixture = playingFixture()
        fixture.route.setRenderingActive(false)
        assertFalse(fixture.route.read().known)
        fixture.route.setPlaybackActive(true)
        assertFalse(fixture.route.read().known)
        fixture.route.setRenderingActive(true)
        assertTrue(fixture.route.read().known)
    }

    @Test
    fun lateSourceCallbackCannotRetireItsReplacementOnTheSameObject() {
        val fixture = RouteTestFixture()
        val source = fixture.attach()
        fixture.route.setPlaybackActive(true)
        val oldCallback = source.callbacks.single()
        val first = fixture.route.read().ownerId
        fixture.route.sourceChanged(keepSource = true)
        val replacement = fixture.route.read().ownerId
        assertNotEquals(first, replacement)
        assertEquals(1, source.removals)
        oldCallback()
        assertEquals(replacement, fixture.route.read().ownerId)
        source.callbacks.last().invoke()
        assertNotEquals(replacement, fixture.route.read().ownerId)
    }

    @Test
    fun lateTrackCallbackCannotRetireTheNewTrack() {
        val fixture = RouteTestFixture()
        val old = fixture.attach()
        fixture.route.setPlaybackActive(true)
        val oldCallback = old.callbacks.single()
        fixture.attach(FakeRoutingSource().apply { deviceId = 9 })
        val replacement = fixture.route.read().ownerId
        oldCallback()
        assertEquals(replacement, fixture.route.read().ownerId)
        assertEquals(1, old.removals)
    }

    @Test
    fun allocationTicketCannotSurviveASourceChangeDuringAllocation() {
        val fixture = playingFixture()
        val ticket = requireNotNull(fixture.route.beginTrack())
        assertFalse(fixture.route.cachedState().known)
        fixture.route.sourceChanged()
        val lateAllocatedSource = FakeRoutingSource()
        fixture.route.observe(ticket, lateAllocatedSource)
        assertTrue(lateAllocatedSource.callbacks.isEmpty())
        assertFalse(fixture.route.read().known)
        fixture.attach()
        assertTrue(fixture.route.read().known)
    }

    @Test
    fun foreignBackendTicketIsNeverRegistered() {
        val first = RouteTestFixture()
        val second = RouteTestFixture()
        val foreign = requireNotNull(first.route.beginTrack())
        val source = FakeRoutingSource()
        second.route.observe(foreign, source)
        assertTrue(source.callbacks.isEmpty())
        assertFalse(second.route.read().known)
    }

    @Test
    fun closeRetiresBeforeUnconfirmedNativeReleaseAndCannotBeResumed() {
        val fixture = RouteTestFixture()
        val source = fixture.attach()
        fixture.route.setPlaybackActive(true)
        fixture.route.close()
        fixture.guard.markReleaseStarted(fixture.lease)
        fixture.guard.quarantine(fixture.lease)
        source.callbacks.single().invoke()
        fixture.route.setPlaybackActive(true)
        assertFalse(fixture.route.read().known)
        assertNull(fixture.route.beginTrack())
        assertTrue(fixture.guard.isRetained(fixture.lease))
        assertEquals(1, source.removals)
    }

    @Test
    fun aReleasedBackendCallbackCannotChangeTheNextLease() {
        val fixture = RouteTestFixture()
        val old = fixture.attach()
        fixture.route.setPlaybackActive(true)
        fixture.route.close()
        fixture.guard.markReleaseStarted(fixture.lease)
        fixture.guard.confirmReleased(fixture.lease)
        val next = PlaybackAudioRoute(fixture.guard.acquire(), fixture.guard, Executor { it.run() })
        next.setPlaybackActive(true)
        next.observe(requireNotNull(next.beginTrack()), FakeRoutingSource())
        val owner = next.read().ownerId
        old.callbacks.single().invoke()
        assertEquals(owner, next.read().ownerId)
    }

    @Test
    fun releaseStartedGuardRejectsAStillPlayingNativeObject() {
        val fixture = playingFixture()
        fixture.guard.markReleaseStarted(fixture.lease)
        assertFalse(fixture.route.read().known)
        assertNull(fixture.route.beginTrack())
    }

    @Test
    fun unavailablePublicQueryRetiresButALaterFreshQueryCanRecover() {
        val fixture = RouteTestFixture()
        val source = fixture.attach()
        fixture.route.setPlaybackActive(true)
        val owner = fixture.route.read().ownerId
        source.readFailure = true
        assertFalse(fixture.route.read().known)
        source.readFailure = false
        assertNotEquals(owner, fixture.route.read().ownerId)
    }

    @Test
    fun listenerRegistrationFailureRemainsUnknownWithoutAccumulatingListeners() {
        val fixture = RouteTestFixture()
        fixture.route.setPlaybackActive(true)
        val source = fixture.attach(FakeRoutingSource().apply { registerFailure = true })
        assertTrue(source.callbacks.isEmpty())
        assertFalse(fixture.route.read().known)
        assertNull(fixture.route.beginTrack())
    }

    @Test
    fun listenerRemovalFailureRetiresAndDoesNotAccumulateOnTheSameObject() {
        val fixture = RouteTestFixture()
        val source = fixture.attach()
        fixture.route.setPlaybackActive(true)
        source.removalFailure = true
        fixture.route.sourceChanged(keepSource = true)
        repeat(5) { fixture.route.sourceChanged(keepSource = true) }
        source.callbacks.single().invoke()
        assertEquals(1, source.callbacks.size)
        assertFalse(fixture.route.read().known)
        assertNull(fixture.route.beginTrack())
    }

    @Test(timeout = 30000L)
    fun registrationInFlightKeepsOnlyOneNativeListenerAndTheLatestSourceTicket() {
        val fixture = RouteTestFixture()
        fixture.route.setPlaybackActive(true)
        val entered = CountDownLatch(1)
        val allowedToReturn = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val source = FakeRoutingSource().apply {
            onRegister = {
                entered.countDown()
                assertTrue(allowedToReturn.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            val worker = Thread {
                try {
                    fixture.attach(source)
                } catch (caught: Throwable) {
                    failure.set(caught)
                } finally {
                    completed.countDown()
                }
            }
            worker.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(100) { fixture.route.sourceChanged(keepSource = true) }
            assertEquals(1, source.callbacks.size)
            assertFalse(fixture.route.cachedState().known)
            allowedToReturn.countDown()
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
            assertEquals(2, source.callbacks.size)
            assertEquals(1, source.removals)
            val current = fixture.route.read().ownerId
            assertTrue(fixture.route.read().known)
            source.callbacks.first().invoke()
            assertEquals(current, fixture.route.read().ownerId)
        } finally {
            allowedToReturn.countDown()
            fixture.route.close()
        }
    }

    @Test
    fun removalInFlightDoesNotRegisterSupersededReplacementObjects() {
        val fixture = RouteTestFixture()
        val old = fixture.attach()
        fixture.route.setPlaybackActive(true)
        val superseded = FakeRoutingSource().apply { deviceId = 8 }
        val latest = FakeRoutingSource().apply { deviceId = 9 }
        old.onRemove = {
            fixture.attach(superseded)
            fixture.attach(latest)
            assertTrue(superseded.callbacks.isEmpty())
            assertTrue(latest.callbacks.isEmpty())
        }
        fixture.route.sourceChanged(keepSource = true)
        val current = fixture.route.read().ownerId
        assertTrue(fixture.route.read().known)
        assertTrue(superseded.callbacks.isEmpty())
        assertEquals(1, latest.callbacks.size)
        assertEquals(1, old.removals)
        assertEquals(1, old.callbacks.size)
        old.callbacks.single().invoke()
        assertEquals(current, fixture.route.read().ownerId)
    }

    @Test
    fun failedRemovalCannotRegisterAReplacementQueuedDuringThatRemoval() {
        val fixture = RouteTestFixture()
        val old = fixture.attach()
        fixture.route.setPlaybackActive(true)
        val next = FakeRoutingSource()
        old.onRemove = { fixture.attach(next) }
        old.removalFailure = true
        fixture.route.sourceChanged(keepSource = true)
        assertTrue(next.callbacks.isEmpty())
        assertEquals(1, old.removals)
        assertFalse(fixture.route.read().known)
        assertNull(fixture.route.beginTrack())
    }

    @Test
    fun queryStartedBeforeReplacementCannotOverwriteItsOwner() {
        val fixture = RouteTestFixture()
        val old = fixture.attach()
        fixture.route.setPlaybackActive(true)
        var replacementOwner: String? = null
        old.onRead = {
            fixture.route.sourceChanged()
            fixture.attach(FakeRoutingSource().apply { deviceId = 9 })
            replacementOwner = fixture.route.read().ownerId
        }
        assertFalse(fixture.route.read().known)
        assertEquals(replacementOwner, fixture.route.cachedState().ownerId)
        assertTrue(fixture.route.cachedState().known)
    }

    @Test
    fun queuedStatePublicationReadsTheLatestStateAndIsBounded() {
        val executor = QueuedExecutor()
        lateinit var fixture: RouteTestFixture
        val notifications = mutableListOf<PlaybackRouteSessionState>()
        fixture = RouteTestFixture(executor) { notifications.add(fixture.route.cachedState()) }
        val source = fixture.attach()
        fixture.route.setPlaybackActive(true)
        repeat(8) { source.callbacks.single().invoke() }
        val latest = fixture.route.cachedState()
        assertEquals(1, executor.work.size)
        executor.work.removeAt(0).run()
        assertEquals(listOf(latest), notifications)
        repeat(8) { fixture.route.read() }
        assertTrue(executor.work.isEmpty())
    }

    @Test
    fun asynchronousObserverFailureCannotBreakPlaybackOrPreventLaterPublication() {
        val executor = QueuedExecutor()
        var delivered = 0
        val fixture = RouteTestFixture(executor) {
            delivered++
            if (delivered == 1) throw IllegalStateException("observer unavailable")
        }
        val source = fixture.attach()
        fixture.route.setPlaybackActive(true)
        executor.work.removeAt(0).run()
        assertTrue(fixture.route.read().known)
        source.callbacks.single().invoke()
        assertEquals(1, executor.work.size)
        executor.work.removeAt(0).run()
        assertEquals(2, delivered)
        assertTrue(executor.work.isEmpty())
    }

    @Test
    fun observerRunsAfterReleasingTheSharedRouteLock() {
        lateinit var fixture: RouteTestFixture
        fixture = RouteTestFixture(onChanged = {
            val delivered = CountDownLatch(1)
            val value = AtomicReference<PlaybackRouteSessionState>()
            val reader = Thread {
                value.set(fixture.route.cachedState())
                delivered.countDown()
            }
            reader.start()
            assertTrue(delivered.await(1, TimeUnit.SECONDS))
            assertEquals(fixture.route.cachedState(), value.get())
        })
        fixture.attach()
        fixture.route.setPlaybackActive(true)
    }

    private fun playingFixture(): RouteTestFixture = RouteTestFixture().also {
        it.attach()
        it.route.setPlaybackActive(true)
    }

    private class QueuedExecutor : Executor {
        val work = mutableListOf<Runnable>()
        override fun execute(command: Runnable) { work.add(command) }
    }
}
