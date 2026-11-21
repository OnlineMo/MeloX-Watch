package io.github.onlinemo.meloxwatch.data.network

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
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
class OkHttpExchangeTest {
    @Before fun initializeExplicitlyForControlledOnlineTests() { OkHttp.initialize(RuntimeEnvironment.getApplication()) }

    @Test fun acceptsTheFixedHttpsOriginAndReturnsOnlyTheBoundedResponse() = runBlocking {
        val exchange = exchange { chain -> response(chain, 200, "{\"code\":200}".toResponseBody()).newBuilder().header("X-Fixture", "yes").build() }
        try {
            val result = exchange.execute(request())
            assertEquals(200, result.statusCode)
            assertEquals(listOf("yes"), result.headerValues("x-fixture"))
            assertEquals("{\"code\":200}", result.body.toString(Charsets.UTF_8))
        } finally { exchange.close() }
    }

    @Test fun rejectsCredentialsFragmentsOtherOriginsAndHeaderInjectionBeforeDispatch() {
        val dispatched = AtomicInteger()
        val exchange = exchange { chain ->
            dispatched.incrementAndGet()
            response(chain, 200, "{}".toResponseBody())
        }
        try {
            val urls = listOf("http://fixture.invalid/api", "https://other.invalid/api", "https://user:fixture@fixture.invalid/api",
                "https://fixture.invalid:444/api", "https://fixture.invalid/api#fixture")
            urls.forEach { expect(NetworkIssue.INVALID_REQUEST) { runBlocking { exchange.execute(request(it)) } } }
            listOf(mapOf("Host" to "other.invalid"), mapOf("X-Test" to "a\r\nCookie: fixture"),
                mapOf("Cookie" to "one", "cookie" to "two"), mapOf("Content-Length" to "1")).forEach {
                expect(NetworkIssue.INVALID_REQUEST) { runBlocking { exchange.execute(request(headers = it)) } }
            }
            assertEquals(0, dispatched.get())
        } finally { exchange.close() }
    }

    @Test fun rejectsOversizedRequestsBeforeTheByteArrayCopyAndOversizedHeadersBeforeDispatch() {
        expect(NetworkIssue.INVALID_REQUEST) { HttpRequest(ORIGIN, emptyMap(), ByteArray(NetworkLimits.REQUEST_BYTES + 1)) }
        val exchange = exchange { chain -> response(chain, 200, "{}".toResponseBody()) }
        try {
            expect(NetworkIssue.INVALID_REQUEST) { runBlocking { exchange.execute(request(headers = mapOf("X-Test" to "x".repeat(NetworkLimits.HEADER_BYTES)))) } }
        } finally { exchange.close() }
    }

    @Test fun redirectsAreReportedWithoutFollowingTheLocation() {
        val dispatched = AtomicInteger()
        val exchange = exchange { chain ->
            dispatched.incrementAndGet()
            response(chain, 302, "redirect".toResponseBody()).newBuilder()
                .header("Location", "https://other.invalid/fixture-secret").build()
        }
        try {
            val error = expect(NetworkIssue.REDIRECT) { runBlocking { exchange.execute(request()) } }
            assertEquals(302, error.statusCode)
            assertEquals(1, dispatched.get())
            assertFalse(error.toString().contains("fixture-secret"))
        } finally { exchange.close() }
    }

    @Test fun rateLimitingRetainsOnlyAValidatedRetryInterval() {
        for ((header, expected) in listOf("12" to 12L, "-1" to null, "3601" to null, "fixture-address" to null)) {
            val exchange = exchange { chain -> response(chain, 429, "fixture".toResponseBody()).newBuilder().header("Retry-After", header).build() }
            try {
                val error = expect(NetworkIssue.HTTP) { runBlocking { exchange.execute(request()) } }
                assertEquals(429, error.statusCode)
                assertEquals(expected, error.retryAfterSeconds)
            } finally { exchange.close() }
        }
    }

    @Test fun certificateTimeoutAndConnectionErrorsStayDistinctAndDoNotRetainTheirCause() {
        for ((failure, issue) in listOf(SSLHandshakeException("fixture-private-address") to NetworkIssue.CERTIFICATE,
            SocketTimeoutException("fixture-private-address") to NetworkIssue.TIMEOUT, IOException("fixture-private-address") to NetworkIssue.UNAVAILABLE)) {
            val exchange = exchange { throw failure }
            try {
                val error = expect(issue) { runBlocking { exchange.execute(request()) } }
                assertEquals(null, error.cause)
                assertFalse(error.toString().contains("fixture-private-address"))
            } finally { exchange.close() }
        }
    }

    @Test fun measuresActualResponseBytesEvenWhenContentLengthIsMissingOrFalse() {
        for (declared in listOf(-1L, 1L, (NetworkLimits.RESPONSE_BYTES + 1).toLong())) {
            val closed = AtomicBoolean()
            val exchange = exchange { chain -> response(chain, 200, trackedBody(ByteArray(NetworkLimits.RESPONSE_BYTES + 1), declared, closed)) }
            try {
                expect(NetworkIssue.RESPONSE_TOO_LARGE) { runBlocking { exchange.execute(request()) } }
                assertTrue(closed.get())
            } finally { exchange.close() }
        }
    }

    @Test fun emptyOrOversizedHeaderResponsesAreClosedAndRejected() {
        val exchange = exchange { chain -> response(chain, 200, "".toResponseBody()) }
        try { expect(NetworkIssue.INVALID_RESPONSE) { runBlocking { exchange.execute(request()) } } }
        finally { exchange.close() }
        val headers = exchange { chain -> response(chain, 200, "{}".toResponseBody()).newBuilder().apply {
            repeat(NetworkLimits.HEADER_COUNT + 1) { addHeader("X-Fixture-$it", "value") }
        }.build() }
        try { expect(NetworkIssue.RESPONSE_TOO_LARGE) { runBlocking { headers.execute(request()) } } }
        finally { headers.close() }
    }

    @Test fun cancellationKeepsAnActiveLeaseUntilTheLateBodyHasBeenClosed() = runBlocking {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val dispatched = AtomicInteger()
        val bodiesClosed = AtomicInteger()
        val lateBodiesClosed = CountDownLatch(2)
        val callsFinished = CountDownLatch(2)
        val reopenedEntered = CountDownLatch(2)
        val reopenedRelease = CountDownLatch(1)
        val admission = RequestAdmission()
        val exchange = exchange(
            ioDispatcher = Dispatchers.Unconfined,
            admission = admission,
            onCallFinished = { callsFinished.countDown() },
        ) { chain ->
            val index = dispatched.incrementAndGet()
            if (index <= 2) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            } else {
                reopenedEntered.countDown()
                check(reopenedRelease.await(5, TimeUnit.SECONDS))
            }
            val source = object : ForwardingSource(Buffer().writeUtf8("{}")) {
                override fun close() {
                    bodiesClosed.incrementAndGet()
                    lateBodiesClosed.countDown()
                    super.close()
                }
            }.buffer()
            response(chain, 200, object : ResponseBody() {
                override fun contentType(): MediaType? = null
                override fun contentLength(): Long = 2
                override fun source(): BufferedSource = source
            })
        }
        val active = List(2) { async(start = CoroutineStart.UNDISPATCHED) { exchange.execute(request()) } }
        val queued = mutableListOf<kotlinx.coroutines.Deferred<HttpResponse>>()
        val reopened = mutableListOf<kotlinx.coroutines.Deferred<HttpResponse>>()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            active.forEach { it.cancel() }
            assertFalse(active.any { it.isCompleted })
            assertEquals(2, admission.admittedRequestCount())
            // Unconfined 与 UNDISPATCHED 保证四个 caller 先到真实 admission，再停在活动 permit。
            repeat(4) { queued.add(async(start = CoroutineStart.UNDISPATCHED) { exchange.execute(request()) }) }
            assertEquals(6, admission.admittedRequestCount())
            assertFalse(queued.any { it.isCompleted })
            assertEquals(2, dispatched.get())
            assertEquals(0, bodiesClosed.get())
            supervisorScope {
                val seventh = async(start = CoroutineStart.UNDISPATCHED) { exchange.execute(request()) }
                try {
                    // 必须在首次挂起前立即 BUSY，不能让 OkHttp ready queue 或超时遮住 admission 错误。
                    assertTrue(seventh.isCompleted)
                    val failure = assertThrows(NetworkException::class.java) { runBlocking { seventh.await() } }
                    assertEquals(NetworkIssue.BUSY, failure.issue)
                } finally {
                    seventh.cancelAndJoin()
                }
            }
            assertEquals(0, bodiesClosed.get())
            assertEquals(2L, callsFinished.count)
            queued.forEach { it.cancelAndJoin() }
            assertEquals(2, admission.admittedRequestCount())
            release.countDown()
            assertTrue(lateBodiesClosed.await(5, TimeUnit.SECONDS))
            // body 的 close marker 早于应用 callback 结束；单独等待真实 finish 后才证明容量恢复。
            assertTrue(callsFinished.await(5, TimeUnit.SECONDS))
            active.forEach { it.join() }
            assertEquals(0, admission.admittedRequestCount())
            repeat(2) { reopened.add(async(start = CoroutineStart.UNDISPATCHED) { exchange.execute(request()) }) }
            assertTrue(reopenedEntered.await(5, TimeUnit.SECONDS))
            assertEquals(2, admission.admittedRequestCount())
            reopenedRelease.countDown()
            reopened.forEach { assertEquals(200, it.await().statusCode) }
            assertEquals(0, admission.admittedRequestCount())
        } finally {
            release.countDown()
            reopenedRelease.countDown()
            active.forEach { it.cancel() }
            queued.forEach { it.cancelAndJoin() }
            reopened.forEach { it.cancelAndJoin() }
            active.forEach { it.join() }
            exchange.close()
            assertTrue(lateBodiesClosed.await(5, TimeUnit.SECONDS))
        }
    }

    @Test fun closedExchangesRejectFurtherRequestsWithoutDispatching() {
        val count = AtomicInteger()
        val exchange = exchange { chain ->
            count.incrementAndGet()
            response(chain, 200, "{}".toResponseBody())
        }
        exchange.close()
        exchange.close()
        expect(NetworkIssue.CLOSED) { runBlocking { exchange.execute(request()) } }
        assertEquals(0, count.get())
    }

    private fun exchange(
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        admission: RequestAdmission = RequestAdmission(),
        onCallFinished: (() -> Unit)? = null,
        interceptor: Interceptor,
    ): OkHttpExchange = OkHttpExchange.controlled(
        OkHttpClient.Builder().addInterceptor(interceptor).build(),
        ORIGIN,
        ioDispatcher,
        admission,
        onCallFinished,
    )
    private fun response(chain: Interceptor.Chain, code: Int, body: ResponseBody): Response = Response.Builder()
        .request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Fixture").body(body).build()
    private fun request(url: String = "$ORIGIN/api", headers: Map<String, String> = emptyMap()): HttpRequest = HttpRequest(url, headers, "fixture".toByteArray())
    private fun expect(issue: NetworkIssue, action: () -> Unit): NetworkException = assertThrows(NetworkException::class.java) { action() }.also { assertEquals(issue, it.issue) }
    private fun trackedBody(bytes: ByteArray, declaredLength: Long, closed: AtomicBoolean): ResponseBody {
        val source = object : ForwardingSource(Buffer().write(bytes)) {
            override fun close() {
                closed.set(true)
                super.close()
            }
        }.buffer()
        return object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = declaredLength
            override fun source(): BufferedSource = source
        }
    }
    companion object { private const val ORIGIN = "https://fixture.invalid" }
}
