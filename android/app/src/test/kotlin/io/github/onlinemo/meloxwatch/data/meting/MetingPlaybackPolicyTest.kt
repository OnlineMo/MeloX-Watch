package io.github.onlinemo.meloxwatch.data.meting

import io.github.onlinemo.meloxwatch.data.auth.ControlledSessionStore
import io.github.onlinemo.meloxwatch.data.auth.SessionRepository
import io.github.onlinemo.meloxwatch.data.auth.fixtureSession
import io.github.onlinemo.meloxwatch.data.netease.NeteaseAuthApi
import io.github.onlinemo.meloxwatch.data.netease.NeteaseEapi
import io.github.onlinemo.meloxwatch.data.netease.NeteasePlaybackApi
import io.github.onlinemo.meloxwatch.data.netease.NeteasePlaybackResolver
import io.github.onlinemo.meloxwatch.data.network.BoundedHttpGetClient
import io.github.onlinemo.meloxwatch.data.network.HttpExchange
import io.github.onlinemo.meloxwatch.data.network.HttpResponse
import io.github.onlinemo.meloxwatch.data.network.NetworkException
import io.github.onlinemo.meloxwatch.data.network.NetworkIssue
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackException
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue
import io.github.onlinemo.meloxwatch.playback.resources.FixturePlaybackClock
import io.github.onlinemo.meloxwatch.playback.resources.PlaybackResolutionBudget
import io.github.onlinemo.meloxwatch.playback.resources.RuntimePlaybackResources
import io.github.onlinemo.meloxwatch.playback.resources.StreamingQuality
import io.github.onlinemo.meloxwatch.playback.resources.onlineEntry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
class MetingPlaybackPolicyTest {
    @Before fun initializeControlledUrlParsing() { OkHttp.initialize(RuntimeEnvironment.getApplication()) }

    @Test fun nativeSuccessNeverLoadsServiceConfigurationOrAcquiresTheSharedHttpClient() = runBlocking {
        fixture(this, enabled = true, native = HttpExchange { nativeResponse(nativeSource()) }).use { fixture ->
            fixture.resolver.resolve(onlineEntry(), PlaybackResolutionBudget(FixturePlaybackClock()), StreamingQuality.STANDARD).use {
                assertEquals("netease-native", it.status?.resolverId)
                assertEquals(0, fixture.storeCreations.get())
                assertEquals(0, fixture.httpCreations.get())
                assertEquals(0, fixture.serviceRequests.get())
            }
        }
    }

    @Test fun disabledOrAbsentServiceHasNoSupplementalRequestForAnAllowedNativeMiss() = runBlocking {
        for (configuration in listOf(null, metingConfiguration(enabled = false))) {
            fixture(this, native = HttpExchange { nativeResponse(nativeMissing()) }, configuration = configuration).use { fixture ->
                val error = assertThrows(QueuePlaybackException::class.java) { runBlocking {
                    fixture.resolver.resolve(onlineEntry(), PlaybackResolutionBudget(FixturePlaybackClock()), StreamingQuality.STANDARD)
                } }
                assertEquals(QueuePlaybackIssue.RESOURCE_UNAVAILABLE, error.failure.issue)
                assertEquals(0, fixture.httpCreations.get())
                assertEquals(0, fixture.serviceRequests.get())
                assertEquals("101", fixture.sessionStore.persistent?.account?.accountId)
                assertEquals(0, fixture.sessionStore.clears)
            }
        }
    }

    @Test fun oneAllowedMissUsesTheOriginalIdentityAndBudgetWithoutForwardingTheNeteaseCookie() = runBlocking {
        val clock = FixturePlaybackClock()
        val budget = PlaybackResolutionBudget(clock)
        fixture(this, enabled = true, native = HttpExchange {
            clock.now += 2_000L
            nativeResponse(nativeMissing())
        }).use { fixture ->
            fixture.resolver.resolve(onlineEntry(), budget, StreamingQuality.STANDARD).use { resource ->
                assertEquals("meting-custom", resource.status?.resolverId)
                assertEquals(onlineEntry().trackKey, resource.entry.trackKey)
                assertEquals("101", resource.entry.accountId)
                assertEquals(budget.operationId, resource.budget?.operationId)
                assertEquals(6_000L, budget.remainingMs())
                assertEquals(1, fixture.serviceRequests.get())
                assertFalse(resource.mediaUri.toString().contains("signature"))
                assertNull(resource.entry.location)
            }
        }
    }

    @Test fun offlineCertificateAndRateLimitFailuresNeverLoadOrCallTheService() = runBlocking {
        for (failure in listOf(NetworkException(NetworkIssue.UNAVAILABLE), NetworkException(NetworkIssue.CERTIFICATE), NetworkException(NetworkIssue.HTTP, 429))) {
            fixture(this, enabled = true, native = HttpExchange { throw failure }).use { fixture ->
                assertThrows(QueuePlaybackException::class.java) { runBlocking {
                    fixture.resolver.resolve(onlineEntry(), PlaybackResolutionBudget(FixturePlaybackClock()), StreamingQuality.STANDARD)
                } }
                assertEquals(0, fixture.storeCreations.get())
                assertEquals(0, fixture.httpCreations.get())
                assertEquals(0, fixture.serviceRequests.get())
            }
        }
    }

    @Test(timeout = 5_000) fun logoutRejectsALateSupplementalBodyAndClearsOnlyTheSessionRecord() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        fixture(this, enabled = true, native = HttpExchange { nativeResponse(nativeMissing()) }, service = { chain ->
            entered.complete(Unit)
            check(release.await(3, TimeUnit.SECONDS))
            serviceResponse(chain)
        }).use { fixture ->
            val opening = async(Dispatchers.IO) {
                fixture.resolver.resolve(onlineEntry(), PlaybackResolutionBudget(FixturePlaybackClock()), StreamingQuality.STANDARD)
            }
            try {
                entered.await()
                val logout = fixture.sessions.beginLogout()
                fixture.sessions.finishLogout(logout)
                assertThrows(CancellationException::class.java) { runBlocking { opening.await() } }
                assertTrue(fixture.http.hasPhysicalRequest())
                release.countDown()
                withTimeout(2_000L) { while (fixture.http.hasPhysicalRequest()) delay(1L) }
                assertNull(fixture.resources.openedResource.value)
                assertNull(fixture.sessionStore.persistent)
                assertEquals(1, fixture.sessionStore.clears)
                assertEquals(1, fixture.serviceRequests.get())
            } finally { release.countDown(); opening.join() }
        }
    }

    private class Fixture(val resources: RuntimePlaybackResources, val sessionStore: ControlledSessionStore,
        val sessions: SessionRepository, val owner: MetingRuntimeOwner, val http: BoundedHttpGetClient,
        val resolver: NeteasePlaybackResolver, val storeCreations: AtomicInteger, val httpCreations: AtomicInteger,
        val serviceRequests: AtomicInteger) : AutoCloseable {
        override fun close() { sessions.close(); owner.close(); http.close() }
    }
    private suspend fun fixture(scope: CoroutineScope, enabled: Boolean = false, native: HttpExchange,
        configuration: MetingStoredConfiguration? = metingConfiguration(enabled = enabled),
        service: Interceptor = Interceptor(::serviceResponse)): Fixture {
        val resources = RuntimePlaybackResources()
        val sessionStore = ControlledSessionStore()
        val sessions = SessionRepository(sessionStore) { resources.invalidateStaleAccounts() }
        sessions.commit(fixtureSession(), sessions.loginEpoch()) { it() }
        val storeCreations = AtomicInteger()
        val httpCreations = AtomicInteger()
        val requests = AtomicInteger()
        val client = BoundedHttpGetClient(RuntimeEnvironment.getApplication(), OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            assertNull(chain.request().header("Cookie"))
            assertEquals("Bearer service-fixture", chain.request().header("Authorization"))
            service.intercept(chain)
        }.build())
        val owner = MetingRuntimeOwner(RuntimeEnvironment.getApplication(), resources,
            { httpCreations.incrementAndGet(); client }, { storeCreations.incrementAndGet(); ControlledMetingStore(configuration) }, scope)
        val eapi = NeteaseEapi(native)
        val resolver = NeteasePlaybackResolver(sessions, NeteaseAuthApi(eapi), NeteasePlaybackApi(eapi), resources,
            supplemental = { owner.playbackResolver(sessions) })
        return Fixture(resources, sessionStore, sessions, owner, client, resolver, storeCreations, httpCreations, requests)
    }
    private fun nativeResponse(body: String) = HttpResponse(200, emptyList(), body.toByteArray())
    private fun nativeSource() = "{\"code\":200,\"data\":[{\"id\":201,\"code\":200,\"url\":\"https://native.invalid/audio\",\"level\":\"standard\",\"br\":128000,\"expi\":600,\"freeTrialInfo\":null}]}"
    private fun nativeMissing() = "{\"code\":200,\"data\":[{\"id\":201,\"code\":200,\"url\":null}]}"
    private fun serviceResponse(chain: Interceptor.Chain): Response = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
        .code(200).message("Fixture").body("[{\"url\":\"https://cdn.invalid/audio?signature=fixture\"}]".toResponseBody()).build()
}
