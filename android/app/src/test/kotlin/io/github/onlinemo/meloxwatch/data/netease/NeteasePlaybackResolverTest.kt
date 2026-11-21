package io.github.onlinemo.meloxwatch.data.netease

import io.github.onlinemo.meloxwatch.data.auth.AuthException
import io.github.onlinemo.meloxwatch.data.auth.AuthIssue
import io.github.onlinemo.meloxwatch.data.auth.ControlledSessionStore
import io.github.onlinemo.meloxwatch.data.auth.SessionRepository
import io.github.onlinemo.meloxwatch.data.auth.fixtureSession
import io.github.onlinemo.meloxwatch.data.network.HttpExchange
import io.github.onlinemo.meloxwatch.data.network.HttpRequest
import io.github.onlinemo.meloxwatch.data.network.HttpResponse
import io.github.onlinemo.meloxwatch.data.network.NetworkException
import io.github.onlinemo.meloxwatch.data.network.NetworkIssue
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackException
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue
import io.github.onlinemo.meloxwatch.playback.resources.FixturePlaybackClock
import io.github.onlinemo.meloxwatch.playback.resources.OnlinePlaybackResolver
import io.github.onlinemo.meloxwatch.playback.resources.PlaybackResolutionBudget
import io.github.onlinemo.meloxwatch.playback.resources.ResourceCompleteness
import io.github.onlinemo.meloxwatch.playback.resources.RuntimePlaybackResource
import io.github.onlinemo.meloxwatch.playback.resources.RuntimePlaybackResources
import io.github.onlinemo.meloxwatch.playback.resources.StreamingQuality
import io.github.onlinemo.meloxwatch.playback.resources.onlineEntry
import io.github.onlinemo.meloxwatch.playback.resources.onlineResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
class NeteasePlaybackResolverTest {
    @Before fun initializeControlledUrlParsing() { OkHttp.initialize(RuntimeEnvironment.getApplication()) }

    @Test fun theOneRequestedStableIdProducesAnOpaqueResourceWithServerReportedQualityAndNoQueueUrl() = runBlocking {
        val requests = mutableListOf<HttpRequest>()
        val fixture = fixture(HttpExchange {
            requests.add(it)
            response(source("201"))
        })
        try {
            val resource = fixture.resolver.resolve(onlineEntry(), PlaybackResolutionBudget(FixturePlaybackClock()), StreamingQuality.STANDARD)
            resource.use {
                assertEquals(1, requests.size)
                assertTrue(requests.single().url.endsWith("/eapi/song/enhance/player/url/v1"))
                assertTrue(requests.single().budget.connectTimeoutMs <= 3_000L)
                assertEquals(256 * 1024, requests.single().budget.responseBytes)
                assertEquals(StreamingQuality.STANDARD, it.status?.reportedQuality)
                assertEquals(128_000L, it.status?.reportedBitrate)
                assertEquals(ResourceCompleteness.FULL_REPORTED, it.status?.completeness)
                assertNull(it.entry.location)
                assertFalse(it.mediaUri.toString().contains("signature"))
            }
        } finally { fixture.close() }
    }

    @Test fun highQualityDowngradeSharesTheOriginalBudgetAndReportsTheActualReturnedLevel() = runBlocking {
        val clock = FixturePlaybackClock()
        val requests = mutableListOf<HttpRequest>()
        val fixture = fixture(HttpExchange {
            requests.add(it)
            if (requests.size == 1) {
                clock.now += 3_000L
                response("{\"code\":200,\"data\":[{\"id\":201,\"code\":200,\"url\":null}]}")
            } else response(source("201", level = "standard"))
        })
        val budget = PlaybackResolutionBudget(clock)
        try {
            fixture.resolver.resolve(onlineEntry(), budget, StreamingQuality.HIGH).use {
                assertEquals(2, requests.size)
                assertTrue(requests[1].budget.callTimeoutMs in 1..5_000L)
                assertEquals(StreamingQuality.HIGH, it.status?.requestedQuality)
                assertEquals(StreamingQuality.STANDARD, it.status?.reportedQuality)
                assertFalse(budget.mayRefreshNative())
            }
        } finally { fixture.close() }
    }

    @Test fun aStaleQueueAccountCannotSendTheCurrentAccountsCredentialOrLogOutTheCurrentAccount() = runBlocking {
        var dispatched = 0
        val fixture = fixture(HttpExchange {
            dispatched++
            throw AssertionError("No old-account request")
        })
        try {
            val failure = assertThrows(QueuePlaybackException::class.java) { runBlocking {
                fixture.resolver.resolve(onlineEntry(accountId = "999"), PlaybackResolutionBudget(FixturePlaybackClock()), StreamingQuality.STANDARD)
            } }
            assertEquals(QueuePlaybackIssue.LOGIN_REQUIRED, failure.failure.issue)
            assertEquals(0, dispatched)
            assertEquals("101", fixture.store.persistent?.account?.accountId)
            assertEquals(0, fixture.store.clears)
        } finally { fixture.close() }
    }

    @Test fun conflictingIdsInvalidResponsesAndTransportStopsNeverInvokeTheSupplementalService() = runBlocking {
        for (error in listOf<Any>(
            source("202"), "{\"code\":200,\"data\":[]}",
            NetworkException(NetworkIssue.CERTIFICATE), NetworkException(NetworkIssue.HTTP, 429),
            NetworkException(NetworkIssue.UNAVAILABLE), AuthException(AuthIssue.LOGIN_REQUIRED),
        )) {
            var supplements = 0
            val fixture = fixture(HttpExchange {
                if (error is String) response(error) else throw (error as Exception)
            }, supplemental = {
                supplements++
                throw AssertionError("No supplemental service for this failure")
            })
            try {
                assertThrows(QueuePlaybackException::class.java) { runBlocking {
                    fixture.resolver.resolve(onlineEntry(), PlaybackResolutionBudget(FixturePlaybackClock()), StreamingQuality.STANDARD)
                } }
                assertEquals(0, supplements)
            } finally { fixture.close() }
        }
    }

    @Test fun anExplicitPreviewMayUseOneConfiguredSupplementWithTheSameIdAndRemainingBudget() = runBlocking {
        val clock = FixturePlaybackClock()
        var requests = 0
        var supplementalCalls = 0
        lateinit var prepared: Fixture
        val supplement = OnlinePlaybackResolver { entry, budget, _ ->
            supplementalCalls++
            assertEquals("201", entry.trackKey.trackId)
            assertEquals("netease", entry.trackKey.providerId)
            assertEquals(6_000L, budget.remainingMs())
            onlineResource(prepared.resources, entry, budget)
        }
        prepared = fixture(HttpExchange {
            requests++
            clock.now += 2_000L
            response(source("201", trial = "{\"start\":0,\"end\":30}"))
        }, supplemental = { supplement })
        try {
            prepared.resolver.resolve(onlineEntry(), PlaybackResolutionBudget(clock), StreamingQuality.STANDARD).close()
            assertEquals(1, requests)
            assertEquals(1, supplementalCalls)
        } finally { prepared.close() }
    }

    @Test(timeout = 5_000) fun logoutCancelsAnInFlightNativeRequestAndALateResponseCannotCreateAPlayableResource() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = fixture(HttpExchange {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            response(source("201"))
        })
        val work = async(start = CoroutineStart.UNDISPATCHED) {
            fixture.resolver.resolve(onlineEntry(), PlaybackResolutionBudget(FixturePlaybackClock()), StreamingQuality.STANDARD)
        }
        try {
            entered.await()
            val operation = fixture.sessions.beginLogout()
            release.complete(Unit)
            fixture.sessions.finishLogout(operation)
            assertThrows(CancellationException::class.java) { runBlocking { work.await() } }
            assertNull(fixture.store.persistent)
            val replacements = List(6) { onlineResource(fixture.resources, onlineEntry((301 + it).toString())) }
            replacements.forEach(RuntimePlaybackResource::close)
        } finally {
            release.complete(Unit)
            work.join()
            fixture.close()
        }
    }

    private class Fixture(val store: ControlledSessionStore, val sessions: SessionRepository,
        val resources: RuntimePlaybackResources, val resolver: NeteasePlaybackResolver) {
        fun close() = sessions.close()
    }
    private suspend fun fixture(exchange: HttpExchange, supplemental: () -> OnlinePlaybackResolver? = { null }): Fixture {
        val store = ControlledSessionStore()
        val sessions = SessionRepository(store)
        sessions.commit(fixtureSession(), sessions.loginEpoch()) { it() }
        val resources = RuntimePlaybackResources()
        val eapi = NeteaseEapi(exchange)
        return Fixture(store, sessions, resources, NeteasePlaybackResolver(sessions, NeteaseAuthApi(eapi), NeteasePlaybackApi(eapi), resources, { supplemental() }))
    }
    private fun response(body: String) = HttpResponse(200, emptyList(), body.toByteArray())
    private fun source(id: String, level: String = "standard", trial: String = "null") =
        "{\"code\":200,\"data\":[{\"id\":$id,\"code\":200,\"url\":\"https://fixture.invalid/audio?signature=fixture\",\"level\":\"$level\",\"br\":128000,\"expi\":600,\"freeTrialInfo\":$trial}]}"
}
