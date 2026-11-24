package io.github.onlinemo.meloxwatch.data.network

import io.github.onlinemo.meloxwatch.data.lyrics.LyricsMemoryOwner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Owned input reservations stay held across real OkHttp callback and body retirement. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class HttpTerminalCleanupTest {
    @Before fun initializeControlledCalls() { OkHttp.initialize(RuntimeEnvironment.getApplication()) }

    @Test(timeout = 5_000) fun nativeCancellationKeepsTheCallerReservationThroughActualReadAndClose(): Unit = runBlocking {
        cancellationScenario(useGet = false)
    }

    @Test(timeout = 5_000) fun getIntentCancellationKeepsItsReservationAndPendingUseThroughActualReadAndClose(): Unit = runBlocking {
        cancellationScenario(useGet = true)
    }

    @Test(timeout = 5_000) fun serviceRetirementPreservesItsCancellationAndDetachesOnlyAfterActualBodyClose(): Unit = runBlocking {
        cancellationScenario(useGet = true, serviceRetirement = true)
    }

    @Test(timeout = 5_000) fun nativeAndGetByteFailuresFinishBodyCloseBeforeReturningTheirOriginalIssue(): Unit = runBlocking {
        for (useGet in listOf(false, true)) failureScenario(useGet)
    }

    private suspend fun cancellationScenario(useGet: Boolean, serviceRetirement: Boolean = false) = coroutineScope {
        val body = GatedBody(ByteArray(NetworkLimits.RESPONSE_BYTES) { 97 }, holdReadAtEnd = true)
        val originalCall = AtomicReference<Call>()
        val dispatched = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val first = dispatched.incrementAndGet() == 1
            if (first) originalCall.set(chain.call())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(if (first) body else "[00:01]reopened".toResponseBody()).build()
        }.build()
        val admission = RequestAdmission()
        val native = if (useGet) null else OkHttpExchange.controlled(client, ORIGIN, admission = admission)
        val get = if (useGet) BoundedHttpGetClient(RuntimeEnvironment.getApplication(), client) else null
        val pendingCount = AtomicInteger()
        val pendingCancel = AtomicReference<() -> Unit>()
        val current = AtomicBoolean(true)
        val service = ServiceHttpContext(HttpOrigin.of(HttpTransportGate.parseHttps(ORIGIN)), false,
            emptyMap(), emptyList(), { current.get() }) { cancel ->
            pendingCount.incrementAndGet()
            pendingCancel.set(cancel)
            object : PendingHttpUse {
                override fun commitOpened(accept: () -> Unit) = error("No media opening in a metadata callback")
                override fun close() {
                    assertTrue(body.closed.get())
                    pendingCount.decrementAndGet()
                }
            }
        }
        val memory = LyricsMemoryOwner()
        val identity = Any()
        val observed = AtomicReference<CancellationException>()
        val opening = async(Dispatchers.IO) {
            val reservation = checkNotNull(memory.acquire(identity))
            try {
                if (useGet) checkNotNull(get).get(HttpTransportGate.parseHttps("$ORIGIN/lyrics"),
                    HttpGetLimits(responseBytes = NetworkLimits.RESPONSE_BYTES, kind = HttpGetKind.TEXT), service)
                else checkNotNull(native).execute(HttpRequest("$ORIGIN/lyrics", emptyMap(), byteArrayOf(1)))
            } catch (cancelled: CancellationException) {
                observed.set(cancelled)
                throw cancelled
            } finally { reservation.close() }
        }
        fun assertCallerStillOwnsItsInput() {
            assertFalse(opening.isCompleted)
            assertNull(memory.acquire(Any()))
            assertFalse(body.closed.get())
            if (useGet) {
                assertTrue(checkNotNull(get).hasPhysicalRequest())
                assertEquals(1, pendingCount.get())
            } else assertEquals(1, admission.admittedRequestCount())
        }
        try {
            // The real collector has consumed the full bounded text and is blocked in its final read.
            assertTrue(body.readEntered.await(2, TimeUnit.SECONDS))
            val reason = "fixture lyrics intent retired"
            if (serviceRetirement) {
                current.set(false)
                checkNotNull(pendingCancel.get()).invoke()
            } else opening.cancel(CancellationException(reason))
            withTimeout(2_000L) { while (!checkNotNull(originalCall.get()).isCanceled()) delay(1L) }
            assertCallerStillOwnsItsInput()
            body.allowRead.countDown()
            assertTrue(body.closeEntered.await(2, TimeUnit.SECONDS))
            assertCallerStillOwnsItsInput()
            body.allowClose.countDown()
            opening.join()
            assertTrue(body.closed.get())
            checkNotNull(memory.acquire(Any())).close()
            val expectedReason = if (serviceRetirement) "Service configuration changed" else reason
            assertTrue(generateSequence<Throwable>(checkNotNull(observed.get())) { it.cause }.any { it.message == expectedReason })
            if (useGet) {
                assertFalse(checkNotNull(get).hasPhysicalRequest())
                assertEquals(0, pendingCount.get())
                assertEquals("[00:01]reopened", checkNotNull(get).get(HttpTransportGate.parseHttps("$ORIGIN/new-lyrics"),
                    HttpGetLimits(kind = HttpGetKind.TEXT)).bytes.toString(Charsets.UTF_8))
            } else {
                assertEquals(0, admission.admittedRequestCount())
                assertEquals("[00:01]reopened", checkNotNull(native).execute(
                    HttpRequest("$ORIGIN/new-lyrics", emptyMap(), byteArrayOf(1))).body.toString(Charsets.UTF_8))
            }
            assertEquals(2, dispatched.get())
        } finally {
            body.allowRead.countDown()
            body.allowClose.countDown()
            opening.cancel()
            opening.join()
            native?.close()
            get?.close()
        }
    }

    private suspend fun failureScenario(useGet: Boolean) = coroutineScope {
        val body = GatedBody(ByteArray(257) { 97 }, holdReadAtEnd = false)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(body).build()
        }.build()
        val admission = RequestAdmission()
        val native = if (useGet) null else OkHttpExchange.controlled(client, ORIGIN, admission = admission)
        val get = if (useGet) BoundedHttpGetClient(RuntimeEnvironment.getApplication(), client) else null
        val memory = LyricsMemoryOwner()
        val opening = async(Dispatchers.IO) {
            val reservation = checkNotNull(memory.acquire(Any()))
            try {
                if (useGet) checkNotNull(get).get(HttpTransportGate.parseHttps("$ORIGIN/lyrics"), HttpGetLimits(responseBytes = 256))
                else checkNotNull(native).execute(HttpRequest("$ORIGIN/lyrics", emptyMap(), byteArrayOf(1),
                    MetadataCallBudget(responseBytes = 256)))
                error("An oversized body was returned")
            } catch (failure: NetworkException) { failure }
            finally { reservation.close() }
        }
        try {
            assertTrue(body.closeEntered.await(2, TimeUnit.SECONDS))
            assertFalse(opening.isCompleted)
            assertNull(memory.acquire(Any()))
            if (useGet) assertTrue(checkNotNull(get).hasPhysicalRequest())
            else assertEquals(1, admission.admittedRequestCount())
            body.allowClose.countDown()
            val failure = opening.await()
            assertEquals(NetworkIssue.RESPONSE_TOO_LARGE, failure.issue)
            assertNull(failure.cause)
            assertTrue(body.closed.get())
            checkNotNull(memory.acquire(Any())).close()
            if (useGet) assertFalse(checkNotNull(get).hasPhysicalRequest())
            else assertEquals(0, admission.admittedRequestCount())
        } finally {
            body.allowRead.countDown()
            body.allowClose.countDown()
            opening.cancel()
            opening.join()
            native?.close()
            get?.close()
        }
    }

    private class GatedBody(bytes: ByteArray, holdReadAtEnd: Boolean) : ResponseBody() {
        val readEntered = CountDownLatch(1)
        val allowRead = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        val closed = AtomicBoolean()
        private val payload = Buffer().write(bytes)
        private val content = object : ForwardingSource(payload) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (holdReadAtEnd && payload.exhausted()) {
                    readEntered.countDown()
                    check(allowRead.await(2, TimeUnit.SECONDS))
                }
                return super.read(sink, byteCount)
            }
            override fun close() {
                closeEntered.countDown()
                check(allowClose.await(2, TimeUnit.SECONDS))
                super.close()
                closed.set(true)
            }
        }.buffer()
        override fun contentType() = null
        override fun contentLength() = -1L
        override fun source() = content
    }

    companion object { private const val ORIGIN = "https://fixture.invalid" }
}
