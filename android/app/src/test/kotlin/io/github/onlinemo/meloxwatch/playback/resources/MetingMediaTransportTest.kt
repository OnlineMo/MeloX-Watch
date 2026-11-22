package io.github.onlinemo.meloxwatch.playback.resources

import io.github.onlinemo.meloxwatch.data.meting.ControlledMetingStore
import io.github.onlinemo.meloxwatch.data.meting.MetingConfigurationRepository
import io.github.onlinemo.meloxwatch.data.meting.MetingConfigurationUse
import io.github.onlinemo.meloxwatch.data.meting.metingConfiguration
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackException
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
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
class MetingMediaTransportTest {
    @Before fun initializeControlledUrlParsing() { OkHttp.initialize(RuntimeEnvironment.getApplication()) }

    @Test fun explicitHttpOpeningRetiresConfigurationCancellationAndKeepsHealthyAudioAfterDisable() = runBlocking(Dispatchers.IO) {
        val repository = repository()
        val use = checkNotNull(repository.capture(true))
        val resources = RuntimePlaybackResources()
        val account = AtomicBoolean(true)
        val resource = resource(resources, use, account::get)
        val transport = transport(resources) { chain ->
            assertEquals("http", chain.request().url.scheme)
            assertEquals("service.invalid", chain.request().url.host)
            assertEquals("Bearer service-fixture", chain.request().header("Authorization"))
            assertNull(chain.request().header("Cookie"))
            response(chain, 200, "audio".toResponseBody())
        }
        try {
            val stream = transport.open(resource.mediaUri, 0L)
            try {
                assertEquals("meting-custom", resources.openedResource.value?.resolverId)
                assertEquals(use.generation, resources.openedResource.value?.configurationGeneration)
                repository.setEnabled(use.generation, false)
                resource.checkCurrent()
                assertEquals(5, stream.read(ByteArray(5), 0, 5))
                assertEquals(QueuePlaybackIssue.RESOURCE_EXPIRED,
                    assertThrows(MediaTransportException::class.java) { transport.open(resource.mediaUri, 0L) }.issue)
                account.set(false)
                resources.invalidateStaleAccounts()
                assertEquals(QueuePlaybackIssue.LOGIN_REQUIRED,
                    assertThrows(MediaTransportException::class.java) { stream.read(ByteArray(5), 0, 5) }.issue)
            } finally { stream.requestClose() }
            assertNull(resources.openedResource.value)
            assertFalse(checkNotNull(resource.online).opened.get())
        } finally { resource.close(); transport.close(); repository.close() }
    }

    @Test fun serviceRedirectKeepsRangeButDropsOriginCredentialAndPrivateQueryBeforeCdnConnection() = runBlocking(Dispatchers.IO) {
        val repository = repository()
        val resources = RuntimePlaybackResources()
        val resource = resource(resources, checkNotNull(repository.capture(true)))
        val calls = AtomicInteger()
        val transport = transport(resources) { chain ->
            assertEquals("bytes=0-", chain.request().header("Range"))
            if (calls.incrementAndGet() == 1) {
                assertEquals("Bearer service-fixture", chain.request().header("Authorization"))
                response(chain, 302, "".toResponseBody()).newBuilder()
                    .header("Location", "https://cdn.invalid/audio?token=fixture&sig=a%2fb+c").build()
            } else {
                assertNull(chain.request().header("Authorization"))
                assertNull(chain.request().header("Cookie"))
                assertEquals("sig=a%2fb+c", chain.request().url.encodedQuery)
                response(chain, 200, "audio".toResponseBody())
            }
        }
        try {
            val stream = transport.open(resource.mediaUri, 0L)
            try { assertEquals(5, stream.read(ByteArray(8), 0, 8)) } finally { stream.requestClose() }
            assertEquals(2, calls.get())
        } finally { resource.close(); transport.close(); repository.close() }
    }

    @Test(timeout = 5_000) fun configurationChangeCancelsPendingOpeningButHoldsPhysicalLeaseUntilLateBodyCleanup() = runBlocking {
        val repository = repository()
        val use = checkNotNull(repository.capture(true))
        val resources = RuntimePlaybackResources()
        val old = resource(resources, use)
        val newer = onlineResource(resources, onlineEntry("202"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bodyClosed = CountDownLatch(1)
        val transport = transport(resources) { chain ->
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            val source = object : ForwardingSource(Buffer().writeUtf8("audio")) {
                override fun close() { super.close(); bodyClosed.countDown() }
            }.buffer()
            response(chain, 200, object : ResponseBody() {
                override fun contentType() = null
                override fun contentLength() = 5L
                override fun source() = source
            })
        }
        val replacementCalls = AtomicInteger()
        val replacement = transport(resources) { chain -> replacementCalls.incrementAndGet(); response(chain, 200, "audio".toResponseBody()) }
        val opening = async(Dispatchers.IO) {
            assertThrows(MediaTransportException::class.java) { transport.open(old.mediaUri, 0L) }.issue
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repository.setEnabled(use.generation, false)
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                assertEquals(QueuePlaybackIssue.BUSY,
                    assertThrows(MediaTransportException::class.java) { replacement.open(newer.mediaUri, 0L) }.issue)
            }
            assertEquals(0, replacementCalls.get())
            release.countDown()
            assertEquals(QueuePlaybackIssue.RESOURCE_EXPIRED, opening.await())
            assertTrue(bodyClosed.await(2, TimeUnit.SECONDS))
            assertNull(resources.openedResource.value)
            assertEquals(QueuePlaybackIssue.RESOURCE_EXPIRED,
                assertThrows(QueuePlaybackException::class.java) { old.checkCurrent() }.failure.issue)
            kotlinx.coroutines.withContext(Dispatchers.IO) { replacement.open(newer.mediaUri, 0L).requestClose() }
            assertEquals(1, replacementCalls.get())
        } finally {
            release.countDown()
            opening.join()
            old.close()
            newer.close()
            transport.close()
            replacement.close()
            repository.close()
        }
    }

    @Test fun httpCrossOriginAndHttpsDowngradeResourcesAreRejectedBeforeTheyReachTransport(): Unit = runBlocking {
        val repository = repository()
        try {
            val use = checkNotNull(repository.capture(true))
            assertThrows(MediaTransportException::class.java) {
                MediaHttpTarget.serviceReference("http://other.invalid/audio", use.configuration.endpoint.url, use.context)
            }
            assertThrows(MediaTransportException::class.java) {
                MediaHttpTarget.serviceReference("http://service.invalid:8080/audio",
                    io.github.onlinemo.meloxwatch.data.network.HttpTransportGate.parseHttps("https://cdn.invalid/api"), use.context)
            }
        } finally { repository.close() }
    }

    @Test fun typedJsonErrorsAndAmbiguousRedirectsCannotBecomeOpenedAudio() = runBlocking(Dispatchers.IO) {
        for (ambiguousRedirect in listOf(false, true)) {
            val repository = repository()
            val resources = RuntimePlaybackResources()
            val resource = resource(resources, checkNotNull(repository.capture(true)))
            val calls = AtomicInteger()
            val transport = transport(resources) { chain ->
                calls.incrementAndGet()
                if (ambiguousRedirect) response(chain, 302, "".toResponseBody()).newBuilder()
                    .addHeader("Location", "/first").addHeader("Location", "/second").build()
                else response(chain, 200, "{\"error\":\"unavailable\"}".toResponseBody()).newBuilder()
                    .header("Content-Type", "application/problem+json").build()
            }
            try {
                assertThrows(MediaTransportException::class.java) { transport.open(resource.mediaUri, 0L) }
                assertEquals(1, calls.get())
                assertNull(resources.openedResource.value)
            } finally { resource.close(); transport.close(); repository.close() }
        }
    }

    private fun repository() = MetingConfigurationRepository { ControlledMetingStore(metingConfiguration(
        "http://service.invalid:8080/api?token=fixture", enabled = true, allowCleartext = true)) }
    private fun resource(resources: RuntimePlaybackResources, use: MetingConfigurationUse, account: () -> Boolean = { true }) = resources.register(
        onlineEntry(), MediaHttpTarget.serviceReference("/audio", use.configuration.endpoint.url, use.contextFor("201")),
        PlaybackResolutionBudget(FixturePlaybackClock()), null,
        PlaybackResourceStatus("meting-custom", ResourceMatch.SERVICE_UNVERIFIED, ResourceCompleteness.UNKNOWN,
            StreamingQuality.STANDARD, null, null, use.generation), account, use::isCurrent)
    private fun transport(resources: RuntimePlaybackResources, interceptor: Interceptor) = HttpsMediaStreams(RuntimeEnvironment.getApplication(),
        resources, OkHttpClient.Builder().addInterceptor(interceptor).build())
    private fun response(chain: Interceptor.Chain, code: Int, body: ResponseBody) = Response.Builder()
        .request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Fixture").body(body).build()
}
