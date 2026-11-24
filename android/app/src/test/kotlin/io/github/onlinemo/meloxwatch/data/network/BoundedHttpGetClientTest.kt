package io.github.onlinemo.meloxwatch.data.network

import io.github.onlinemo.meloxwatch.data.meting.ControlledMetingStore
import io.github.onlinemo.meloxwatch.data.meting.MetingConfigurationRepository
import io.github.onlinemo.meloxwatch.data.meting.metingConfiguration
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 回归定义只使用受控 interceptor 或回环服务，不向音乐服务、真实地址或用户账户发请求。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class BoundedHttpGetClientTest {
    @Before fun initializeControlledUrlParsing() { OkHttp.initialize(RuntimeEnvironment.getApplication()) }

    @Test fun defaultConsumersRejectHttpBeforeOpeningTheClientOrSendingHeaders() = runBlocking {
        val calls = AtomicInteger()
        val client = client { chain -> calls.incrementAndGet(); response(chain, 200, "[]".toResponseBody()) }
        try {
            val error = assertThrows(NetworkException::class.java) { runBlocking {
                client.get(HttpTransportGate.parse("http://service.invalid/api"), HttpGetLimits())
            } }
            assertEquals(NetworkIssue.INVALID_REQUEST, error.issue)
            assertEquals(0, calls.get())
            assertFalse(client.hasPhysicalRequest())
        } finally { client.close() }
    }

    @Test fun streamedUnknownLengthExcessClosesTheBodyAtTheCallerLimit() = runBlocking {
        val closed = CountDownLatch(1)
        val source = object : ForwardingSource(Buffer().writeUtf8("a".repeat(257))) {
            override fun close() { super.close(); closed.countDown() }
        }.buffer()
        val client = client { chain -> response(chain, 200, object : ResponseBody() {
            override fun contentType() = null
            override fun contentLength() = -1L
            override fun source() = source
        }) }
        try {
            val error = assertThrows(NetworkException::class.java) { runBlocking {
                client.get(HttpTransportGate.parseHttps("https://fixture.invalid/metadata"), HttpGetLimits(responseBytes = 256))
            } }
            assertEquals(NetworkIssue.RESPONSE_TOO_LARGE, error.issue)
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            assertFalse(client.hasPhysicalRequest())
        } finally { client.close() }
    }

    @Test(timeout = 8_000) fun transparentGzipIsLimitedByDecompressedBytesRatherThanWireContentLength() = runBlocking {
        for ((bytes, tooLarge) in listOf("[]".toByteArray() to false, ByteArray(256 * 1024 + 1) { 97 } to true)) {
            val compressed = ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(bytes) } }.toByteArray()
            val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 2_000 }
            val executor = Executors.newSingleThreadExecutor()
            val serving = executor.submit<Unit> {
                server.accept().use { socket ->
                    socket.soTimeout = 2_000
                    val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    for (index in 0 until 32) {
                        val line = reader.readLine() ?: error("Incomplete controlled request")
                        check(line.length <= 8192)
                        if (line.isEmpty()) break
                        check(index < 31)
                    }
                    val header = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Encoding: gzip\r\nContent-Length: ${compressed.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().write(header.toByteArray(Charsets.US_ASCII))
                    socket.getOutputStream().write(compressed)
                    socket.getOutputStream().flush()
                }
            }
            val address = "http://127.0.0.1:${server.localPort}/api"
            val repository = MetingConfigurationRepository { ControlledMetingStore(metingConfiguration(address, allowCleartext = true)) }
            val client = BoundedHttpGetClient(RuntimeEnvironment.getApplication())
            try {
                val context = checkNotNull(repository.capture(false)).context
                if (tooLarge) {
                    val error = assertThrows(NetworkException::class.java) { runBlocking { client.get(HttpTransportGate.parse(address), HttpGetLimits(), context) } }
                    assertEquals(NetworkIssue.RESPONSE_TOO_LARGE, error.issue)
                } else {
                    assertEquals("[]", client.get(HttpTransportGate.parse(address), HttpGetLimits(), context).bytes.toString(Charsets.UTF_8))
                }
                serving.get(2, TimeUnit.SECONDS)
            } finally {
                client.close()
                repository.close()
                server.close()
                executor.shutdownNow()
            }
        }
    }

    @Test(timeout = 5_000) fun timeoutRetainsThePhysicalSlotUntilANoncooperativeCallbackActuallyCloses() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bodyClosed = CountDownLatch(1)
        val calls = AtomicInteger()
        val originalCall = CompletableDeferred<Call>()
        val client = client { chain ->
            calls.incrementAndGet()
            originalCall.complete(chain.call())
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            val source = object : ForwardingSource(Buffer().writeUtf8("[]")) {
                override fun close() { super.close(); bodyClosed.countDown() }
            }.buffer()
            response(chain, 200, object : ResponseBody() {
                override fun contentType() = null
                override fun contentLength() = 2L
                override fun source() = source
            })
        }
        val opening = async(Dispatchers.IO) { assertThrows(NetworkException::class.java) { runBlocking {
            client.get(HttpTransportGate.parseHttps("https://fixture.invalid/metadata"), HttpGetLimits(callTimeoutMs = 100L, connectTimeoutMs = 100L))
        } }.issue }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val timedOut = originalCall.await()
            withTimeout(2_000L) { while (!timedOut.isCanceled()) delay(1L) }
            assertFalse(opening.isCompleted)
            assertTrue(client.hasPhysicalRequest())
            assertEquals(NetworkIssue.BUSY, assertThrows(NetworkException::class.java) { runBlocking {
                client.get(HttpTransportGate.parseHttps("https://fixture.invalid/new-operation"), HttpGetLimits())
            } }.issue)
            assertEquals(1, calls.get())
            release.countDown()
            assertTrue(bodyClosed.await(2, TimeUnit.SECONDS))
            withTimeout(2_000L) { while (client.hasPhysicalRequest()) delay(1L) }
            assertEquals(NetworkIssue.TIMEOUT, opening.await())
        } finally { release.countDown(); opening.join(); client.close() }
    }

    @Test fun redirectsAreReturnedForPerHopAdmissionAndHtmlOrImageJsonCannotBecomeAcceptedBytes() = runBlocking {
        val redirect = client { chain -> response(chain, 302, "".toResponseBody()).newBuilder().header("Location", "http://other.invalid/audio").build() }
        try {
            val result = redirect.get(HttpTransportGate.parseHttps("https://fixture.invalid/api"), HttpGetLimits())
            assertEquals(302, result.code)
            assertEquals("http://other.invalid/audio", result.location)
            assertTrue(result.bytes.isEmpty())
        } finally { redirect.close() }
        for ((type, kind) in listOf("text/html" to HttpGetKind.JSON, "application/json" to HttpGetKind.IMAGE)) {
            val client = client { chain -> response(chain, 200, "error".toResponseBody()).newBuilder().header("Content-Type", type).build() }
            try {
                assertEquals(NetworkIssue.INVALID_RESPONSE, assertThrows(NetworkException::class.java) { runBlocking {
                    client.get(HttpTransportGate.parseHttps("https://fixture.invalid/resource"), HttpGetLimits(kind = kind))
                } }.issue)
            } finally { client.close() }
        }
    }

    @Test(timeout = 5_000) fun metadataCancelsAVisibleCoverAndWaitsForRealCleanupWithinItsOriginalBudget() = runBlocking {
        val imageCall = CompletableDeferred<Call>()
        val release = CountDownLatch(1)
        val bodyClosed = CountDownLatch(1)
        val calls = AtomicInteger()
        val client = client { chain ->
            calls.incrementAndGet()
            if (chain.request().header("Accept") == "image/*") {
                imageCall.complete(chain.call())
                check(release.await(3, TimeUnit.SECONDS))
                val source = object : ForwardingSource(Buffer().writeUtf8("cover")) {
                    override fun close() { super.close(); bodyClosed.countDown() }
                }.buffer()
                response(chain, 200, object : ResponseBody() {
                    override fun contentType() = null
                    override fun contentLength() = 5L
                    override fun source() = source
                })
            } else {
                assertEquals(0L, bodyClosed.count)
                assertTrue(chain.call().timeout().timeoutNanos() in 1 until TimeUnit.MILLISECONDS.toNanos(3_000L))
                response(chain, 200, "[]".toResponseBody())
            }
        }
        val image = async(Dispatchers.IO) { runCatching { client.get(HttpTransportGate.parseHttps("https://fixture.invalid/cover"),
            HttpGetLimits(kind = HttpGetKind.IMAGE)) }.exceptionOrNull() }
        var metadata: kotlinx.coroutines.Deferred<HttpGetResponse>? = null
        try {
            val oldCall = imageCall.await()
            metadata = async(Dispatchers.IO) { client.get(HttpTransportGate.parseHttps("https://fixture.invalid/metadata"),
                HttpGetLimits(callTimeoutMs = 3_000L)) }
            withTimeout(2_000L) { while (!oldCall.isCanceled()) delay(1L) }
            assertTrue(client.hasPhysicalRequest())
            assertFalse(metadata.isCompleted)
            assertEquals(1, calls.get())
            assertEquals(1L, bodyClosed.count)
            assertEquals(NetworkIssue.BUSY, assertThrows(NetworkException::class.java) { runBlocking {
                client.get(HttpTransportGate.parseHttps("https://fixture.invalid/another-cover"), HttpGetLimits(kind = HttpGetKind.IMAGE))
            } }.issue)
            delay(20L)
            release.countDown()
            assertEquals("[]", metadata.await().bytes.toString(Charsets.UTF_8))
            assertTrue(image.await() != null)
            assertEquals(2, calls.get())
        } finally {
            release.countDown()
            image.cancel()
            metadata?.cancel()
            image.join()
            metadata?.join()
            client.close()
        }
    }

    private fun client(interceptor: Interceptor) = BoundedHttpGetClient(RuntimeEnvironment.getApplication(),
        OkHttpClient.Builder().addInterceptor(interceptor).build())
    private fun response(chain: Interceptor.Chain, code: Int, body: ResponseBody) = Response.Builder()
        .request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Fixture").body(body).build()
}
