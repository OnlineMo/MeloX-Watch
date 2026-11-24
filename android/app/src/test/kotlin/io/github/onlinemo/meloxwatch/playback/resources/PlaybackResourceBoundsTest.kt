package io.github.onlinemo.meloxwatch.playback.resources

import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackException
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class PlaybackResourceBoundsTest {
    @Before fun initializeOnlyControlledOnlineFixtures() { OkHttp.initialize(RuntimeEnvironment.getApplication()) }

    @Test fun nativeAndSupplementalWorkKeepTheSameEightSecondDeadlineAndNarrowEachCall() = runBlocking {
        val clock = FixturePlaybackClock(100L)
        val budget = PlaybackResolutionBudget(clock)
        val operation = budget.operationId
        val failure = assertThrows(QueuePlaybackException::class.java) { runBlocking { budget.within {
            budget.claimNativeRequest()
            clock.now += 3_100L
            assertEquals(4_900L, budget.metadataCall().callTimeoutMs)
            assertEquals(3_000L, budget.metadataCall().connectTimeoutMs)
            budget.claimSupplementalRequest()
            clock.now += 4_200L
            assertEquals(700L, budget.metadataCall().callTimeoutMs)
            assertEquals(700L, budget.metadataCall().connectTimeoutMs)
            assertEquals(operation, budget.operationId)
            clock.now += 700L
            Unit
        } } }
        assertEquals(QueuePlaybackIssue.NETWORK_TIMEOUT, failure.failure.issue)
    }

    @Test(timeout = 5_000) fun aLateResourceReturnedAfterCancellationIsClosedBeforeAnotherWindowCanUseIt() = runBlocking {
        val resources = RuntimePlaybackResources()
        val budget = PlaybackResolutionBudget(FixturePlaybackClock())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var late: RuntimePlaybackResource? = null
        val work = async(Dispatchers.IO) {
            budget.within(onAbandoned = RuntimePlaybackResource::close) {
                entered.countDown()
                check(release.await(4, TimeUnit.SECONDS))
                onlineResource(resources, budget = budget).also { late = it }
            }
        }
        try {
            assertTrue(entered.await(4, TimeUnit.SECONDS))
            work.cancel()
        } finally { release.countDown() }
        work.join()
        assertTrue(work.isCancelled)
        assertThrows(QueuePlaybackException::class.java) { requireNotNull(late).checkCurrent() }
        val replacements = List(6) { onlineResource(resources, onlineEntry((301 + it).toString())) }
        replacements.forEach(RuntimePlaybackResource::close)
    }

    @Test fun invalidatingOldTokensRetainsANewTokenForTheSameAccountAndPreservesLocalResources() {
        val resources = RuntimePlaybackResources()
        var oldCurrent = true
        val old = onlineResource(resources, current = { oldCurrent })
        val local = RuntimePlaybackResource(QueueEntry("local-entry", TrackKey("local", "local-track"), 0L, 0L,
            location = LocalLocationReference("location", "content://fixture/local")))
        oldCurrent = false
        val newer = onlineResource(resources, onlineEntry("202"), current = { true })
        resources.invalidateStaleAccounts()
        assertEquals(QueuePlaybackIssue.LOGIN_REQUIRED, assertThrows(QueuePlaybackException::class.java) { old.checkCurrent() }.failure.issue)
        newer.checkCurrent()
        local.checkCurrent()
        assertEquals("content", local.mediaUri.scheme)
        old.close()
        newer.close()
        local.close()
    }

    @Test fun onlyOpaqueRuntimeUrisAreExposedAndUnknownExpiryCannotBecomeADurableLocation() {
        val resources = RuntimePlaybackResources()
        val resource = onlineResource(resources)
        try {
            assertEquals("meloxstream", resource.mediaUri.scheme)
            assertFalse(resource.mediaUri.toString().contains("signature"))
            assertFalse(resource.toString().contains("fixture.invalid"))
            assertEquals(null, resource.entry.location)
            assertFalse(resource.status.toString().contains("fixture.invalid"))
        } finally { resource.close() }
    }

    @Test fun expiryBlocksNewAcquisitionWithoutEndingAnAlreadyOpenedHealthyStream() {
        val resources = RuntimePlaybackResources()
        val clock = FixturePlaybackClock()
        val resource = onlineResource(resources, budget = PlaybackResolutionBudget(clock), expiry = 100L)
        val stream = resources.acquire(resource.mediaUri)
        try {
            clock.now = 100L
            resources.check(stream, newAcquisition = false)
            assertEquals(QueuePlaybackIssue.RESOURCE_EXPIRED,
                assertThrows(MediaTransportException::class.java) { resources.acquire(resource.mediaUri) }.issue)
        } finally {
            resources.release(stream)
            resource.close()
        }
    }

    @Test fun localAndLocalOnlyIntentsNeverCreateTheOnlineProvider() = runBlocking {
        var creations = 0
        val resolver = SourcePlaybackResolver(local = { it }, online = {
            creations++
            throw AssertionError("Online provider must not be created")
        })
        val local = QueueEntry("local-entry", TrackKey("local", "local-track"), 0L, 0L,
            location = LocalLocationReference("location", "content://fixture/local"))
        resolver.resolve(local, PlaybackSourceIntent.LOCAL_ONLY, PlaybackResolutionBudget(FixturePlaybackClock()), StreamingQuality.STANDARD).close()
        val failure = assertThrows(QueuePlaybackException::class.java) { runBlocking {
            resolver.resolve(onlineEntry(), PlaybackSourceIntent.LOCAL_ONLY, PlaybackResolutionBudget(FixturePlaybackClock()), StreamingQuality.STANDARD)
        } }
        assertEquals(QueuePlaybackIssue.LOCAL_ONLY_ONLINE_SKIPPED, failure.failure.issue)
        assertEquals(0, creations)
    }
}
