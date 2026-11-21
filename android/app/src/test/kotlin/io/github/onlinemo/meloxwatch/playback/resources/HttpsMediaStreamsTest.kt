package io.github.onlinemo.meloxwatch.playback.resources

import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
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

/** Controlled interceptors exercise transport ownership without DNS, real accounts or audio output. */
@RunWith(RobolectricTestRunner::class)
@UnstableApi
@Config(sdk = [26], manifest = Config.NONE)
class HttpsMediaStreamsTest {
    @Before fun initializeOnlyTheControlledOnlineClient() { OkHttp.initialize(RuntimeEnvironment.getApplication()) }

    @Test fun rangesKeepTheOffsetAndBoundTheReadWithoutSendingAccountOrServiceCredentials() = runBlocking(Dispatchers.IO) {
        val resources = RuntimePlaybackResources()
        val resource = onlineResource(resources)
        val transport = transport(resources) { chain ->
            assertEquals("bytes=4-7", chain.request().header("Range"))
            assertEquals("identity", chain.request().header("Accept-Encoding"))
            assertNull(chain.request().header("Cookie"))
            assertNull(chain.request().header("Authorization"))
            response(chain, 206, "efgh".toResponseBody()).newBuilder()
                .header("Content-Range", "bytes 4-7/8").header("Content-Type", "audio/mpeg").build()
        }
        try {
            val stream = transport.open(resource.mediaUri, 4L, 4L)
            try {
                val buffer = ByteArray(8)
                assertEquals(4L, stream.availableLength)
                assertEquals(8L, stream.totalSize)
                assertTrue(stream.seekable)
                assertEquals(4, stream.read(buffer, 0, buffer.size))
                assertEquals("efgh", buffer.copyOf(4).toString(Charsets.UTF_8))
                assertEquals(-1, stream.read(buffer, 0, buffer.size))
            } finally { stream.requestClose() }
        } finally {
            resource.close()
            transport.close()
        }
    }

    @Test fun missingOrFalseRangesCannotTurnASeekIntoDifferentBytes() = runBlocking(Dispatchers.IO) {
        for ((code, contentRange) in listOf(200 to null, 206 to "bytes 0-3/8", 206 to "bytes 4-8/8",
            206 to "bytes 4-7/9223372036854775808")) {
            val resources = RuntimePlaybackResources()
            val resource = onlineResource(resources)
            val transport = transport(resources) { chain -> response(chain, code, "abcd".toResponseBody()).newBuilder().apply {
                contentRange?.let { header("Content-Range", it) }
            }.build() }
            try {
                assertEquals(QueuePlaybackIssue.RESOURCE_INVALID,
                    assertThrows(MediaTransportException::class.java) { transport.open(resource.mediaUri, 4L) }.issue)
            } finally {
                resource.close()
                transport.close()
            }
        }
    }

    @Test fun eachControlledRedirectChecksHttpsAndNeverInheritsACookieOrAuthorizationHeader() = runBlocking(Dispatchers.IO) {
        val resources = RuntimePlaybackResources()
        val resource = onlineResource(resources)
        val requests = AtomicInteger()
        val transport = transport(resources) { chain ->
            val count = requests.incrementAndGet()
            assertNull(chain.request().header("Cookie"))
            assertNull(chain.request().header("Authorization"))
            if (count <= 3) response(chain, 302, "".toResponseBody()).newBuilder()
                .header("Location", "https://cdn.invalid/hop-$count?signature=fixture")
                .header("Set-Cookie", "service-fixture=fixture; Path=/").build()
            else response(chain, 200, "audio".toResponseBody())
        }
        try {
            transport.open(resource.mediaUri, 0L).requestClose()
            assertEquals(4, requests.get())
        } finally {
            resource.close()
            transport.close()
        }
    }

    @Test fun aFourthRedirectOrHttpsDowngradeStopsBeforeAnotherConnection() = runBlocking(Dispatchers.IO) {
        for ((location, expectedCalls) in listOf("https://cdn.invalid/again" to 4, "http://cdn.invalid/audio" to 1)) {
            val resources = RuntimePlaybackResources()
            val resource = onlineResource(resources)
            val requests = AtomicInteger()
            val transport = transport(resources) { chain ->
                requests.incrementAndGet()
                response(chain, 302, "".toResponseBody()).newBuilder().header("Location", location).build()
            }
            try {
                assertThrows(MediaTransportException::class.java) { transport.open(resource.mediaUri, 0L) }
                assertEquals(expectedCalls, requests.get())
            } finally {
                resource.close()
                transport.close()
            }
        }
    }

    @Test fun authorizationAndRateLimitsAreDistinctStopsWithNoRawUrlCauseOrRetry() = runBlocking(Dispatchers.IO) {
        for ((code, issue) in listOf(403 to QueuePlaybackIssue.RESOURCE_AUTH_REQUIRED, 429 to QueuePlaybackIssue.NETWORK_RATE_LIMITED)) {
            val resources = RuntimePlaybackResources()
            val resource = onlineResource(resources)
            val requests = AtomicInteger()
            val transport = transport(resources) { chain ->
                requests.incrementAndGet()
                response(chain, code, "fixture".toResponseBody())
            }
            try {
                val failure = assertThrows(MediaTransportException::class.java) { transport.open(resource.mediaUri, 0L) }
                assertEquals(issue, failure.issue)
                assertNull(failure.cause)
                assertFalse(failure.toString().contains("signature"))
                assertEquals(1, requests.get())
            } finally {
                resource.close()
                transport.close()
            }
        }
    }

    @Test fun urlExpiryAfterHeadersDoesNotEndAHealthyAlreadyOpenedResponse() = runBlocking(Dispatchers.IO) {
        val clock = FixturePlaybackClock()
        val resources = RuntimePlaybackResources()
        val resource = onlineResource(resources, budget = PlaybackResolutionBudget(clock), expiry = 100L)
        val transport = transport(resources) { chain ->
            clock.now = 200L
            response(chain, 200, "audio".toResponseBody())
        }
        try {
            val stream = transport.open(resource.mediaUri, 0L)
            try { assertEquals(5, stream.read(ByteArray(5), 0, 5)) }
            finally { stream.requestClose() }
        } finally {
            resource.close()
            transport.close()
        }
    }

    @Test fun aShortNonTail206CannotBeMistakenForObjectEofForEitherRequestShape() = runBlocking(Dispatchers.IO) {
        for (length in listOf(-1L, 100L)) {
            val resources = RuntimePlaybackResources()
            val resource = onlineResource(resources)
            var requests = 0
            val transport = transport(resources) { chain ->
                requests++
                response(chain, 206, "0123456789".toResponseBody()).newBuilder().header("Content-Range", "bytes 0-9/1000").build()
            }
            try {
                assertEquals(QueuePlaybackIssue.RESOURCE_INVALID,
                    assertThrows(MediaTransportException::class.java) { transport.open(resource.mediaUri, 0L, length) }.issue)
                assertEquals(1, requests)
            } finally {
                resource.close()
                transport.close()
            }
        }
    }

    @Test fun onlyAFullyCoveredFixedUnknownTotalRangeOrAKnownTrueTailIsAccepted() = runBlocking(Dispatchers.IO) {
        for ((total, requested, accepted) in listOf(Triple("8", 20L, true), Triple("*", 4L, true),
            Triple("*", 20L, false), Triple("*", -1L, false))) {
            val resources = RuntimePlaybackResources()
            val resource = onlineResource(resources)
            val transport = transport(resources) { chain -> response(chain, 206, "efgh".toResponseBody()).newBuilder()
                .header("Content-Range", "bytes 4-7/$total").build() }
            try {
                if (accepted) {
                    val stream = transport.open(resource.mediaUri, 4L, requested)
                    try {
                        assertEquals(4L, stream.availableLength)
                        assertEquals(4, stream.read(ByteArray(8), 0, 8))
                        assertEquals(-1, stream.read(ByteArray(8), 0, 8))
                        assertEquals(total != "*", stream.seekable)
                    } finally { stream.requestClose() }
                } else assertEquals(QueuePlaybackIssue.RESOURCE_INVALID,
                    assertThrows(MediaTransportException::class.java) { transport.open(resource.mediaUri, 4L, requested) }.issue)
            } finally {
                resource.close()
                transport.close()
            }
        }
    }

    @Test fun aKnownComplete200BodyReportsItsActualTailWhenTheFixedRequestExtendsPastIt() = runBlocking(Dispatchers.IO) {
        val resources = RuntimePlaybackResources()
        val resource = onlineResource(resources)
        val transport = transport(resources) { chain -> response(chain, 200, "audio".toResponseBody()) }
        try {
            val stream = transport.open(resource.mediaUri, 0L, 100L)
            try {
                assertEquals(5L, stream.availableLength)
                assertEquals(5, stream.read(ByteArray(8), 0, 8))
                assertEquals(-1, stream.read(ByteArray(8), 0, 8))
            } finally { stream.requestClose() }
        } finally {
            resource.close()
            transport.close()
        }
    }

    @Test(timeout = 5_000) fun cancellationRetainsThePhysicalSlotAcrossFactoriesUntilTheLateResponseIsClosed() = runBlocking {
        val resources = RuntimePlaybackResources()
        val current = AtomicBoolean(true)
        val old = onlineResource(resources, current = current::get)
        val newer = onlineResource(resources, onlineEntry("202"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bodyClosed = CountDownLatch(1)
        val requests = AtomicInteger()
        val transport = transport(resources) { chain ->
            if (requests.incrementAndGet() == 1) {
                entered.countDown()
                check(release.await(4, TimeUnit.SECONDS))
                val source = object : ForwardingSource(Buffer().writeUtf8("audio")) {
                    override fun close() {
                        bodyClosed.countDown()
                        super.close()
                    }
                }.buffer()
                response(chain, 200, object : ResponseBody() {
                    override fun contentType() = null
                    override fun contentLength() = 5L
                    override fun source() = source
                })
            } else response(chain, 200, "audio".toResponseBody())
        }
        val replacementRequests = AtomicInteger()
        val replacement = PlaybackDataSources(RuntimeEnvironment.getApplication(), resources, this@HttpsMediaStreamsTest.transport(resources) { chain ->
            replacementRequests.incrementAndGet()
            response(chain, 200, "audio".toResponseBody())
        })
        val opening = async(Dispatchers.IO) {
            assertThrows(MediaTransportException::class.java) { transport.open(old.mediaUri, 0L).requestClose() }.issue
        }
        try {
            assertTrue(entered.await(4, TimeUnit.SECONDS))
            current.set(false)
            resources.invalidateStaleAccounts()
            withContextIo {
                assertEquals(QueuePlaybackIssue.BUSY,
                    assertThrows(MediaTransportException::class.java) { transport.open(newer.mediaUri, 0L) }.issue)
                val source = replacement.createDataSource()
                try {
                    assertEquals(QueuePlaybackIssue.BUSY, assertThrows(MediaTransportException::class.java) {
                        source.open(DataSpec.Builder().setUri(newer.mediaUri).build())
                    }.issue)
                } finally { source.close() }
            }
            assertEquals(1, requests.get())
            assertEquals(0, replacementRequests.get())
            release.countDown()
            assertEquals(QueuePlaybackIssue.LOGIN_REQUIRED, opening.await())
            assertTrue(bodyClosed.await(4, TimeUnit.SECONDS))
            withContextIo {
                val source = replacement.createDataSource()
                try { assertEquals(5L, source.open(DataSpec.Builder().setUri(newer.mediaUri).build())) }
                finally { source.close() }
            }
            assertEquals(1, requests.get())
            assertEquals(1, replacementRequests.get())
        } finally {
            release.countDown()
            opening.join()
            old.close()
            newer.close()
            transport.close()
            replacement.close()
        }
    }

    private suspend fun <T> withContextIo(action: () -> T): T = kotlinx.coroutines.withContext(Dispatchers.IO) { action() }
    private fun transport(resources: RuntimePlaybackResources, interceptor: Interceptor) = HttpsMediaStreams(
        RuntimeEnvironment.getApplication(), resources, OkHttpClient.Builder().addInterceptor(interceptor).build(),
    )
    private fun response(chain: Interceptor.Chain, code: Int, body: ResponseBody) = Response.Builder()
        .request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Fixture").body(body).build()
}
