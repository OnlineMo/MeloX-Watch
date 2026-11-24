package io.github.onlinemo.meloxwatch.data.network

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class MetadataCallBudgetTest {
    @Before fun initializeControlledFixtures() { OkHttp.initialize(RuntimeEnvironment.getApplication()) }

    @Test fun theNarrowResponseLimitCountsActualBytesAndClosesBodiesWithMissingOrFalseLength() {
        for (declared in listOf(-1L, 1L)) {
            val closed = AtomicBoolean()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val source = object : ForwardingSource(Buffer().write(ByteArray(256 * 1024 + 1))) {
                    override fun close() {
                        closed.set(true)
                        super.close()
                    }
                }.buffer()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body(object : ResponseBody() {
                        override fun contentType() = null
                        override fun contentLength() = declared
                        override fun source() = source
                    }).build()
            }.build()
            val exchange = OkHttpExchange.controlled(client, "https://fixture.invalid")
            try {
                val request = HttpRequest("https://fixture.invalid/api", emptyMap(), byteArrayOf(1),
                    MetadataCallBudget(8_000L, 3_000L, 256 * 1024))
                val failure = assertThrows(NetworkException::class.java) { runBlocking { exchange.execute(request) } }
                assertEquals(NetworkIssue.RESPONSE_TOO_LARGE, failure.issue)
                assertTrue(closed.get())
            } finally { exchange.close() }
        }
    }
}
