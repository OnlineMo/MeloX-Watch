package io.github.onlinemo.meloxwatch.data.network

import android.content.Context
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import okhttp3.Response

internal enum class HttpGetKind { JSON, IMAGE, TEXT }

/** 每次获取可以缩小读取量及时间；音频流使用自己的流式 transport，不进入这个收集器。 */
internal data class HttpGetLimits(
    val callTimeoutMs: Long = 8_000L,
    val connectTimeoutMs: Long = 3_000L,
    val responseBytes: Int = 256 * 1024,
    val kind: HttpGetKind = HttpGetKind.JSON,
) {
    init {
        if (callTimeoutMs !in 1..8_000L || connectTimeoutMs !in 1..minOf(5_000L, callTimeoutMs) ||
            responseBytes !in 1..16 * 1024 * 1024
        ) throw NetworkException(NetworkIssue.INVALID_REQUEST)
    }
}

internal class HttpGetResponse(val code: Int, val url: HttpUrl, val location: String?, val bytes: ByteArray) {
    override fun toString(): String = "HttpGetResponse(code=$code, redacted)"
}

/**
 * 应用拥有的一个有界 GET 通道，供原生封面及 Meting 元数据/附属资源共用。
 * 默认只准 HTTPS、无 Cookie/鉴权；取消保留物理占用至终止 callback 关闭 body。
 * 此层不跟随跳转、不重试、不建立缓存；调用者按各自完整操作预算核验每一跳及旧结果。
 */
internal class BoundedHttpGetClient(context: Context, private val controlledClient: OkHttpClient? = null) : AutoCloseable {
    private val application = context.applicationContext
    private val lifecycle = Any()
    private val physical = AtomicBoolean()
    private val cleanupScheduled = AtomicBoolean()
    private val calls = mutableSetOf<Call>()
    private var activeKind: HttpGetKind? = null
    private var physicalReleased = CompletableDeferred<Unit>()
    private var waitingJson: PriorityWaiter? = null
    private var closed = false
    private val cleanup = Executors.newSingleThreadExecutor { task -> Thread(task, "MeloX-http-get-cleanup").apply { isDaemon = true } }
    private val clientOwner = lazy {
        check(Looper.myLooper() != Looper.getMainLooper())
        OkHttp.initialize(application)
        (controlledClient?.newBuilder() ?: OkHttpClient.Builder())
            .dispatcher(Dispatcher().apply { maxRequests = 1; maxRequestsPerHost = 1 })
            .connectionPool(ConnectionPool(1, 60L, TimeUnit.SECONDS))
            .cookieJar(CookieJar.NO_COOKIES).cache(null)
            .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(3_000L, TimeUnit.MILLISECONDS).readTimeout(10_000L, TimeUnit.MILLISECONDS)
            .writeTimeout(3_000L, TimeUnit.MILLISECONDS).callTimeout(8_000L, TimeUnit.MILLISECONDS).build()
    }

    suspend fun get(target: HttpUrl, limits: HttpGetLimits, context: ServiceHttpContext? = null): HttpGetResponse =
        withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            withTimeoutOrNull(limits.callTimeoutMs) {
                synchronized(lifecycle) { if (closed) throw NetworkException(NetworkIssue.CLOSED) }
                HttpTransportGate.check(target, context)
                val caller = currentCoroutineContext()[Job] ?: throw NetworkException(NetworkIssue.CLOSED)
                var lease: PhysicalLease? = null
                var call: Call? = null
                var pending: PendingHttpUse? = null
                var transferred = false
                try {
                    // 排队也属待解析请求；配置变化必须立即取消，不能等旧封面回调释放后才发现旧代次。
                    pending = context?.ownPending { caller.cancel(CancellationException("Service configuration changed")) }
                    val acquired = acquirePhysical(limits.kind, caller)
                    lease = acquired
                    currentCoroutineContext().ensureActive()
                    val client = clientOwner.value
                    val spent = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
                    val remaining = limits.callTimeoutMs - spent
                    if (remaining <= 0L) throw NetworkException(NetworkIssue.TIMEOUT)
                    val builder = Request.Builder().url(HttpTransportGate.check(target, context)).get()
                        .header("Accept", when (limits.kind) {
                            HttpGetKind.JSON -> "application/json"
                            HttpGetKind.IMAGE -> "image/*"
                            HttpGetKind.TEXT -> "text/plain, application/octet-stream"
                        })
                    context?.headersFor(target)?.forEach { (name, value) -> builder.header(name, value) }
                    val created = client.newBuilder().callTimeout(remaining, TimeUnit.MILLISECONDS)
                        .connectTimeout(minOf(limits.connectTimeoutMs, remaining), TimeUnit.MILLISECONDS).build().newCall(builder.build())
                    call = created
                    val interruptedByMetadata = synchronized(lifecycle) {
                        if (closed) throw NetworkException(NetworkIssue.CLOSED)
                        calls.add(created)
                        waitingJson != null && limits.kind != HttpGetKind.JSON
                    }
                    if (interruptedByMetadata) created.cancel()
                    transferred = true
                    await(created, acquired, pending, context, limits)
                } finally {
                    if (!transferred) {
                        try { call?.cancel(); pending?.close() }
                        finally { synchronized(lifecycle) { call?.let(calls::remove) }; lease?.close() }
                    }
                }
            } ?: throw NetworkException(NetworkIssue.TIMEOUT)
        }

    /** 只容纳一个解析等待者；JSON 让位于真实清理后的槽位，图片/歌词不能抢在它前面重开。 */
    private suspend fun acquirePhysical(kind: HttpGetKind, caller: Job): PhysicalLease {
        val waiter = PriorityWaiter(caller)
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                var cancelled: List<Call> = emptyList()
                var signal: CompletableDeferred<Unit>? = null
                val lease = synchronized(lifecycle) {
                    if (closed) throw NetworkException(NetworkIssue.CLOSED)
                    if (!physical.get()) {
                        if (waitingJson != null && waitingJson !== waiter) throw NetworkException(NetworkIssue.BUSY)
                        physical.set(true)
                        activeKind = kind
                        val released = CompletableDeferred<Unit>()
                        physicalReleased = released
                        if (waitingJson === waiter) waitingJson = null
                        PhysicalLease { releasePhysical(released) }
                    } else {
                        if (kind != HttpGetKind.JSON || activeKind == HttpGetKind.JSON ||
                            waitingJson != null && waitingJson !== waiter) throw NetworkException(NetworkIssue.BUSY)
                        waitingJson = waiter
                        cancelled = calls.toList()
                        signal = physicalReleased
                        null
                    }
                }
                if (lease != null) return lease
                cancelled.forEach(Call::cancel)
                checkNotNull(signal).await()
            }
        } finally { synchronized(lifecycle) { if (waitingJson === waiter) waitingJson = null } }
    }

    private fun releasePhysical(signal: CompletableDeferred<Unit>) {
        synchronized(lifecycle) { activeKind = null; physical.set(false) }
        signal.complete(Unit)
        cleanupIfClosed()
    }

    private suspend fun await(
        call: Call,
        lease: PhysicalLease,
        pending: PendingHttpUse?,
        context: ServiceHttpContext?,
        limits: HttpGetLimits,
    ): HttpGetResponse {
        val done = CompletableDeferred<Unit>()
        val finished = AtomicBoolean()
        fun finish() {
            if (!finished.compareAndSet(false, true)) return
            try {
                try { pending?.close() }
                finally { synchronized(lifecycle) { calls.remove(call) }; lease.close() }
            } finally { done.complete(Unit) }
        }
        val result = try {
            suspendCancellableCoroutine<HttpGetResponse> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                if (!continuation.isActive) { finish(); return@suspendCancellableCoroutine }
                try {
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            finish()
                            if (!continuation.isActive) return
                            try { context?.requireCurrent() }
                            catch (cancelled: CancellationException) { continuation.cancel(cancelled); return }
                            continuation.resumeWithException(transportFailure(e))
                        }

                        override fun onResponse(call: Call, response: Response) {
                            var failure: NetworkException? = null
                            val result = try {
                                response.use { value ->
                                    if (!continuation.isActive) return
                                    context?.requireCurrent()
                                    readBounded(value, context, limits) {
                                        if (!continuation.isActive || call.isCanceled()) throw CancellationException("HTTP request cancelled")
                                        context?.requireCurrent()
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                continuation.cancel(cancelled)
                                null
                            } catch (error: NetworkException) {
                                failure = error
                                null
                            } catch (error: IOException) {
                                failure = transportFailure(error)
                                null
                            } catch (_: RuntimeException) {
                                failure = NetworkException(NetworkIssue.INVALID_RESPONSE)
                                null
                            } finally { finish() }
                            if (!continuation.isActive) return
                            val error = failure
                            if (error != null) continuation.resumeWithException(error)
                            else if (result != null) continuation.resume(result)
                        }
                    })
                } catch (_: RuntimeException) {
                    finish()
                    if (continuation.isActive) continuation.resumeWithException(NetworkException(NetworkIssue.CLOSED))
                }
            }
        } finally {
            // The app keeps its shared client; this caller waits only for its own body and pending-use release.
            withContext(NonCancellable) { done.await() }
        }
        currentCoroutineContext().ensureActive()
        return result
    }

    private fun readBounded(response: Response, context: ServiceHttpContext?, limits: HttpGetLimits, checkCancelled: () -> Unit): HttpGetResponse {
        HttpTransportGate.check(response.request.url, context)
        if (response.headers.size > NetworkLimits.HEADER_COUNT) throw NetworkException(NetworkIssue.RESPONSE_TOO_LARGE)
        var headerBytes = 0L
        for (index in 0 until response.headers.size) {
            headerBytes += response.headers.name(index).length.toLong() + response.headers.value(index).length + 4L
            if (headerBytes > NetworkLimits.HEADER_BYTES) throw NetworkException(NetworkIssue.RESPONSE_TOO_LARGE)
        }
        if (response.code in REDIRECT_CODES) {
            val locations = response.headers.values("Location")
            if (locations.size != 1 || locations.single().length !in 1..NetworkLimits.URL_CHARS) throw NetworkException(NetworkIssue.REDIRECT)
            return HttpGetResponse(response.code, response.request.url, locations.single(), byteArrayOf())
        }
        if (response.code !in 200..299) throw NetworkException(NetworkIssue.HTTP, response.code,
            response.header("Retry-After")?.toLongOrNull()?.takeIf { it in 0..3600 })
        if (response.code == 206) throw NetworkException(NetworkIssue.INVALID_RESPONSE)
        val type = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT)
        if (type in setOf("text/html", "application/xhtml+xml") ||
            limits.kind != HttpGetKind.JSON && (type in setOf("application/json", "application/problem+json") || type?.endsWith("+json") == true) ||
            limits.kind == HttpGetKind.IMAGE && type?.startsWith("text/") == true
        ) throw NetworkException(NetworkIssue.INVALID_RESPONSE)
        if (response.body.contentLength() > limits.responseBytes) throw NetworkException(NetworkIssue.RESPONSE_TOO_LARGE)
        val output = ByteArrayOutputStream(8192)
        val buffer = ByteArray(8192)
        val input = response.body.byteStream()
        while (true) {
            checkCancelled()
            val read = input.read(buffer, 0, minOf(buffer.size, limits.responseBytes - output.size() + 1))
            if (read < 0) break
            if (read == 0) throw NetworkException(NetworkIssue.INVALID_RESPONSE)
            if (output.size() + read > limits.responseBytes) throw NetworkException(NetworkIssue.RESPONSE_TOO_LARGE)
            output.write(buffer, 0, read)
        }
        if (output.size() == 0) throw NetworkException(NetworkIssue.INVALID_RESPONSE)
        return HttpGetResponse(response.code, response.request.url, null, output.toByteArray())
    }

    internal fun hasPhysicalRequest(): Boolean = physical.get()

    override fun close() {
        val (admitted, waiter) = synchronized(lifecycle) {
            if (closed) return
            closed = true
            calls.toList() to waitingJson?.job
        }
        waiter?.cancel(CancellationException("HTTP owner closed"))
        admitted.forEach(Call::cancel)
        cleanupIfClosed()
    }

    private fun cleanupIfClosed() {
        if (!synchronized(lifecycle) { closed && !physical.get() } || !cleanupScheduled.compareAndSet(false, true)) return
        // 初始化前的 close 与获取准入有同一内存次序；有真实 callback 时由其最终租约清理接续。
        if (!clientOwner.isInitialized()) { cleanup.shutdown(); return }
        cleanup.execute {
            try {
                val client = clientOwner.value
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            } finally { cleanup.shutdown() }
        }
    }

    private class PhysicalLease(private val release: () -> Unit) : AutoCloseable {
        private val open = AtomicBoolean(true)
        override fun close() { if (open.compareAndSet(true, false)) release() }
    }
    private class PriorityWaiter(val job: Job)
    companion object {
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private fun transportFailure(error: IOException): NetworkException = NetworkException(when (error) {
            is SSLException -> NetworkIssue.CERTIFICATE
            is InterruptedIOException -> NetworkIssue.TIMEOUT
            else -> NetworkIssue.UNAVAILABLE
        })
    }
}
