package io.github.onlinemo.meloxwatch.data.network

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** One explicitly enabled origin; no redirects, credential jar, automatic retry, cache or logging. */
internal class OkHttpExchange private constructor(
    private val client: OkHttpClient,
    private val origin: HttpUrl,
    private val admission: RequestAdmission = RequestAdmission(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onCallFinished: (() -> Unit)? = null,
) : HttpExchange, AutoCloseable {
    private val lifecycle = Any()
    private val calls = mutableSetOf<Call>()
    private var closed = false

    override suspend fun execute(request: HttpRequest): HttpResponse = withContext(ioDispatcher) {
        val started = System.nanoTime()
        withTimeoutOrNull(request.budget.callTimeoutMs) {
            val prepared = prepare(request)
            val lease = admission.enter()
            val call = try {
                synchronized(lifecycle) {
                    if (closed) throw NetworkException(NetworkIssue.CLOSED)
                    val spent = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
                    val remaining = request.budget.callTimeoutMs - spent
                    if (remaining <= 0L) throw NetworkException(NetworkIssue.TIMEOUT)
                    // Derived clients retain the one dispatcher/pool and isolated origin policy.
                    client.newBuilder()
                        .callTimeout(remaining, TimeUnit.MILLISECONDS)
                        .connectTimeout(minOf(request.budget.connectTimeoutMs, remaining), TimeUnit.MILLISECONDS)
                        .build().newCall(prepared).also { calls.add(it) }
                }
            } catch (error: Throwable) {
                lease.close()
                throw error
            }
            awaitResponse(call, lease, request.budget.responseBytes)
        } ?: throw NetworkException(NetworkIssue.TIMEOUT)
    }

    private fun prepare(request: HttpRequest): Request {
        if (request.url.length > NetworkLimits.URL_CHARS || request.body.size > NetworkLimits.REQUEST_BYTES) {
            throw NetworkException(NetworkIssue.INVALID_REQUEST)
        }
        val url = HttpTransportGate.parseHttps(request.url)
        if (url.scheme != "https" || url.host != origin.host || url.port != origin.port ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null
        ) throw NetworkException(NetworkIssue.INVALID_REQUEST)
        var headerBytes = 0L
        val names = mutableSetOf<String>()
        if (request.headers.size > NetworkLimits.HEADER_COUNT) throw NetworkException(NetworkIssue.INVALID_REQUEST)
        val builder = Request.Builder().url(url).post(request.body.toRequestBody())
        for ((name, value) in request.headers) {
            val lower = name.lowercase(java.util.Locale.ROOT)
            if (name.isEmpty() || name.length > 64 || name.any { !isHeaderToken(it) } ||
                value.any { it.code !in 32..126 } || !names.add(lower) || lower in FORBIDDEN_HEADERS
            ) throw NetworkException(NetworkIssue.INVALID_REQUEST)
            headerBytes += name.length.toLong() + value.length + 4
            if (headerBytes > NetworkLimits.HEADER_BYTES) throw NetworkException(NetworkIssue.INVALID_REQUEST)
            builder.header(name, value)
        }
        return builder.build()
    }

    private suspend fun awaitResponse(call: Call, lease: RequestAdmission.Lease, responseLimit: Int): HttpResponse {
        val done = CompletableDeferred<Unit>()
        val finished = AtomicBoolean()
        fun finishCall() {
            if (!finished.compareAndSet(false, true)) return
            try { finish(call, lease) }
            finally { done.complete(Unit) }
        }
        val result = try {
            suspendCancellableCoroutine<HttpResponse> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                if (!continuation.isActive) {
                    finishCall()
                    return@suspendCancellableCoroutine
                }
                try {
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            finishCall()
                            if (continuation.isActive) continuation.resumeWithException(transportFailure(e))
                        }

                        override fun onResponse(call: Call, response: Response) {
                            val result = try {
                                response.use {
                                    if (!continuation.isActive) return
                                    readBounded(it, responseLimit) { continuation.isActive && !call.isCanceled() }
                                }
                            } catch (error: NetworkException) {
                                if (continuation.isActive) continuation.resumeWithException(error)
                                return
                            } catch (error: IOException) {
                                if (continuation.isActive) continuation.resumeWithException(transportFailure(error))
                                return
                            } catch (_: RuntimeException) {
                                if (continuation.isActive) continuation.resumeWithException(NetworkException(NetworkIssue.INVALID_RESPONSE))
                                return
                            } finally {
                                finishCall()
                            }
                            if (continuation.isActive) continuation.resume(result)
                        }
                    })
                } catch (_: RuntimeException) {
                    finishCall()
                    if (continuation.isActive) continuation.resumeWithException(NetworkException(NetworkIssue.CLOSED))
                }
            }
        } finally {
            // Logical cancellation cannot release a caller's input budget while this callback still owns raw bytes.
            withContext(NonCancellable) { done.await() }
        }
        currentCoroutineContext().ensureActive()
        return result
    }

    private fun finish(call: Call, lease: RequestAdmission.Lease) {
        synchronized(lifecycle) { calls.remove(call) }
        lease.close()
        onCallFinished?.invoke()
    }

    override fun close() {
        val active = synchronized(lifecycle) {
            if (closed) return
            closed = true
            calls.toList()
        }
        active.forEach(Call::cancel)
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    companion object {
        private val FORBIDDEN_HEADERS = setOf("host", "content-length", "transfer-encoding", "connection", "proxy-authorization")

        /** Call only from an explicit online entry; application startup and local pages stay independent. */
        suspend fun create(context: Context, origin: String): OkHttpExchange {
            var created: OkHttpExchange? = null
            try {
                return withContext(Dispatchers.IO) {
                    val parsed = parseOrigin(origin)
                    OkHttp.initialize(context.applicationContext)
                    OkHttpExchange(configure(OkHttpClient.Builder()).build(), parsed).also { created = it }
                }
            } catch (failure: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { created?.close() }
                throw failure
            }
        }

        /**
         * 受控测试可固定 admission 前的 dispatcher，并观察真实 lease 释放后的终止点。
         * [onCallFinished] 只能做短小、不阻塞且不抛异常的测试通知；生产 create 使用 IO、
         * 独立 admission 和空通知，不改变其并发、超时或资源释放契约。
         */
        internal fun controlled(
            client: OkHttpClient,
            origin: String,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            admission: RequestAdmission = RequestAdmission(),
            onCallFinished: (() -> Unit)? = null,
        ): OkHttpExchange = OkHttpExchange(
            configure(client.newBuilder()).build(),
            parseOrigin(origin),
            admission,
            ioDispatcher,
            onCallFinished,
        )

        private fun configure(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder
            .dispatcher(Dispatcher().apply {
                maxRequests = NetworkLimits.ACTIVE_REQUESTS
                maxRequestsPerHost = NetworkLimits.ACTIVE_REQUESTS
            })
            .connectionPool(ConnectionPool(2, 60, TimeUnit.SECONDS))
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(null)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .callTimeout(NetworkLimits.CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .connectTimeout(NetworkLimits.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(NetworkLimits.IDLE_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(NetworkLimits.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        private fun parseOrigin(value: String): HttpUrl {
            val parsed = HttpTransportGate.parseHttps(value)
            if (parsed.scheme != "https" || parsed.username.isNotEmpty() || parsed.password.isNotEmpty() ||
                parsed.encodedPath != "/" || parsed.query != null || parsed.fragment != null
            ) throw NetworkException(NetworkIssue.INVALID_REQUEST)
            return parsed
        }

        private fun readBounded(response: Response, responseLimit: Int, current: () -> Boolean): HttpResponse {
            if (response.code !in 200..299) throw NetworkException(
                if (response.code in 300..399) NetworkIssue.REDIRECT else NetworkIssue.HTTP,
                response.code,
                response.header("Retry-After")?.toLongOrNull()?.takeIf { it in 0..3600 },
            )
            if (response.headers.size > NetworkLimits.HEADER_COUNT) throw NetworkException(NetworkIssue.RESPONSE_TOO_LARGE)
            var headerBytes = 0L
            val headers = buildList {
                for (index in 0 until response.headers.size) {
                    val name = response.headers.name(index)
                    val value = response.headers.value(index)
                    headerBytes += name.length.toLong() + value.length + 4
                    if (headerBytes > NetworkLimits.HEADER_BYTES) throw NetworkException(NetworkIssue.RESPONSE_TOO_LARGE)
                    add(name to value)
                }
            }
            val body = response.body
            if (body.contentLength() > responseLimit) throw NetworkException(NetworkIssue.RESPONSE_TOO_LARGE)
            val output = ByteArrayOutputStream(8192)
            val buffer = ByteArray(8192)
            val stream = body.byteStream()
            while (true) {
                if (!current()) throw NetworkException(NetworkIssue.CLOSED)
                val read = stream.read(buffer, 0, minOf(buffer.size, responseLimit - output.size() + 1))
                if (read < 0) break
                if (read == 0) throw NetworkException(NetworkIssue.INVALID_RESPONSE)
                if (output.size() + read > responseLimit) throw NetworkException(NetworkIssue.RESPONSE_TOO_LARGE)
                output.write(buffer, 0, read)
            }
            if (output.size() == 0) throw NetworkException(NetworkIssue.INVALID_RESPONSE)
            return HttpResponse(response.code, headers, output.toByteArray())
        }

        private fun transportFailure(error: IOException): NetworkException = NetworkException(when (error) {
            is SSLException -> NetworkIssue.CERTIFICATE
            is InterruptedIOException -> NetworkIssue.TIMEOUT
            else -> NetworkIssue.UNAVAILABLE
        })

        private fun isHeaderToken(value: Char): Boolean = value in 'a'..'z' || value in 'A'..'Z' || value in '0'..'9' ||
            value in "!#$%&'*+-.^_`|~"
    }
}
