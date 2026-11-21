package io.github.onlinemo.meloxwatch.playback.resources

import android.content.Context
import android.net.Uri
import android.os.Looper
import io.github.onlinemo.meloxwatch.data.network.PendingHttpUse
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** One physical media acquisition; cancellation keeps its slot until execute/read/body cleanup has ended. */
internal class HttpsMediaStreams(
    private val context: Context,
    private val resources: RuntimePlaybackResources,
    private val controlledClient: OkHttpClient? = null,
    cleanupExecutor: Executor? = null,
) : AutoCloseable {
    private val ownsCleanupExecutor = cleanupExecutor == null
    private val cleanupExecutor = cleanupExecutor ?: Executors.newSingleThreadExecutor { task ->
        Thread(task, "MeloX-media-cleanup").apply { isDaemon = true }
    }
    private val active = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val clientClosed = AtomicBoolean()
    private val current = AtomicReference<MediaStream?>()
    private val clientOwner = lazy {
        check(Looper.myLooper() != Looper.getMainLooper())
        OkHttp.initialize(context.applicationContext)
        (controlledClient?.newBuilder() ?: OkHttpClient.Builder())
            .cookieJar(CookieJar.NO_COOKIES).cache(null)
            .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectionPool(ConnectionPool(1, 60L, TimeUnit.SECONDS))
            .connectTimeout(5_000L, TimeUnit.MILLISECONDS).readTimeout(10_000L, TimeUnit.MILLISECONDS)
            .writeTimeout(5_000L, TimeUnit.MILLISECONDS).callTimeout(0L, TimeUnit.MILLISECONDS).build()
    }

    fun open(uri: Uri, position: Long, length: Long = -1L): MediaStream {
        if (Looper.myLooper() == Looper.getMainLooper()) throw MediaTransportException(QueuePlaybackIssue.BUSY)
        if (closed.get()) throw MediaTransportException(QueuePlaybackIssue.CLOSED)
        if (position < 0L || length < -1L || length == 0L || length > 0L && position > Long.MAX_VALUE - length) {
            throw MediaTransportException(QueuePlaybackIssue.RESOURCE_INVALID)
        }
        val state = resources.acquire(uri)
        val physical = try { resources.acquirePhysicalMedia() } catch (failure: MediaTransportException) {
            resources.release(state)
            throw failure
        }
        if (!active.compareAndSet(false, true)) {
            resources.release(state)
            physical.close()
            throw MediaTransportException(QueuePlaybackIssue.BUSY)
        }
        var stream: MediaStream? = null
        try {
            val network = clientOwner.value
            if (closed.get()) throw MediaTransportException(QueuePlaybackIssue.CLOSED)
            val created = MediaStream(state, position, length, network, cleanupExecutor) {
                try { resources.release(state) }
                finally {
                    current.compareAndSet(stream, null)
                    active.set(false)
                    physical.close()
                }
            }
            stream = created
            current.set(created)
            if (closed.get()) throw MediaTransportException(QueuePlaybackIssue.CLOSED)
            created.open()
            return created
        } catch (failure: Exception) {
            val created = stream
            if (created == null) {
                try { resources.release(state) } finally {
                    active.set(false)
                    physical.close()
                }
            } else created.requestClose()
            if (closed.get()) closeOwnedClient()
            throw failure
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        current.get()?.requestClose()
        if (!active.get() && !clientOwner.isInitialized()) {
            if (ownsCleanupExecutor) (cleanupExecutor as ExecutorService).shutdown()
            return
        }
        cleanupExecutor.execute {
            try { closeOwnedClient() }
            finally { if (ownsCleanupExecutor) (cleanupExecutor as ExecutorService).shutdown() }
        }
    }

    private fun closeOwnedClient() {
        if (!clientOwner.isInitialized() || !clientClosed.compareAndSet(false, true)) return
        val network = clientOwner.value
        network.connectionPool.evictAll()
        network.dispatcher.executorService.shutdown()
    }
}

internal class MediaStream(
    private val resource: OnlineResourceState,
    val position: Long,
    private val requestedLength: Long,
    private val client: OkHttpClient,
    private val cleanup: Executor,
    private val onFinished: () -> Unit,
) {
    private val memory = Any()
    private val redirects = MediaRedirectBudget()
    private var call: Call? = null
    private var response: Response? = null
    private var pendingConfiguration: PendingHttpUse? = null
    private var closing = false
    private var operations = 0
    private var cleanupClaimed = false
    private var remaining = -1L
    @Volatile var totalSize = -1L
        private set
    @Volatile var seekable = false
        private set
    @Volatile var availableLength = -1L
        private set
    var responseHeaders: Map<String, List<String>> = emptyMap()
        private set
    private val cancelCall: () -> Unit = { synchronized(memory) { call }?.cancel() }

    fun open(): Long = physical {
        resource.owner.attachCancellation(resource, cancelCall)
        // 待打开登记在接受响应前保留；代次改动取消真实 call，槽位仍由 physical 清理释放。
        val pending = resource.target.ownPending(::requestClose)
        synchronized(memory) { pendingConfiguration = pending }
        var target = resource.target
        while (true) {
            requireCurrent(newAcquisition = true)
            val range = if (requestedLength < 0L) "bytes=$position-" else "bytes=$position-${position + requestedLength - 1L}"
            val builder = Request.Builder().url(target.checked().url).get()
                .header("Accept-Encoding", "identity").header("Range", range)
            target.headers().forEach { (name, value) -> builder.header(name, value) }
            val next = client.newCall(builder.build())
            synchronized(memory) {
                if (closing) throw MediaTransportException(QueuePlaybackIssue.SOURCE_CHANGED)
                call = next
            }
            // Invalidation can occur between the first check and storing the call; it must see a cancellable call.
            requireCurrent(newAcquisition = true)
            val receivedResponse = try { next.execute() } catch (error: IOException) { throw transportFailure(error) }
            var accepted = false
            try {
                requireCurrent(newAcquisition = false)
                checkHeaders(receivedResponse)
                if (receivedResponse.code in REDIRECT_CODES) {
                    redirects.claim()
                    val locations = receivedResponse.headers.values("Location")
                    if (locations.size != 1 || locations.single().length !in 1..2048) {
                        throw MediaTransportException(QueuePlaybackIssue.RESOURCE_REDIRECT)
                    }
                    val location = locations.single()
                    target = target.redirect(location)
                    continue
                }
                if (receivedResponse.code !in 200..299) throw MediaTransportException(when (receivedResponse.code) {
                    401, 403 -> QueuePlaybackIssue.RESOURCE_AUTH_REQUIRED
                    429 -> QueuePlaybackIssue.NETWORK_RATE_LIMITED
                    404, 410 -> QueuePlaybackIssue.RESOURCE_UNAVAILABLE
                    else -> QueuePlaybackIssue.RESOURCE_INVALID
                })
                val encoding = receivedResponse.header("Content-Encoding")
                if (encoding != null && !encoding.equals("identity", ignoreCase = true)) {
                    throw MediaTransportException(QueuePlaybackIssue.RESOURCE_INVALID)
                }
                val contentType = receivedResponse.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT)
                if (contentType?.startsWith("text/") == true || contentType?.endsWith("+json") == true ||
                    contentType in setOf("application/json", "application/xhtml+xml")) {
                    throw MediaTransportException(QueuePlaybackIssue.RESOURCE_INVALID)
                }
                val bodyLength = receivedResponse.body.contentLength()
                if (receivedResponse.code == 206) {
                    val rangeFields = RANGE.matchEntire(receivedResponse.header("Content-Range").orEmpty())
                        ?: throw MediaTransportException(QueuePlaybackIssue.RESOURCE_INVALID)
                    val start = rangeFields.groupValues[1].toLongOrNull() ?: invalidRange()
                    val end = rangeFields.groupValues[2].toLongOrNull() ?: invalidRange()
                    val totalField = rangeFields.groupValues[3]
                    val total = if (totalField == "*") null else (totalField.toLongOrNull() ?: invalidRange())
                    if (start != position || end < start || end == Long.MAX_VALUE || total != null && (total <= end || total <= 0L)) invalidRange()
                    val rangeLength = end - start + 1L
                    if (bodyLength >= 0L && bodyLength != rangeLength) invalidRange()
                    if (requestedLength >= 0L && rangeLength > requestedLength) invalidRange()
                    val objectTail = total != null && end == total - 1L
                    if ((requestedLength < 0L || rangeLength < requestedLength) && !objectTail) invalidRange()
                    remaining = rangeLength
                    totalSize = total ?: -1L
                    seekable = total != null
                } else {
                    if (receivedResponse.code != 200 || position != 0L) invalidRange()
                    totalSize = bodyLength
                    remaining = if (requestedLength >= 0L && bodyLength >= 0L) minOf(requestedLength, bodyLength)
                        else if (requestedLength >= 0L) requestedLength else bodyLength
                }
                responseHeaders = buildMap {
                    contentType?.takeIf { it.length <= 128 && MIME.matches(it) }?.let { put("Content-Type", listOf(it)) }
                    if (bodyLength >= 0L) put("Content-Length", listOf(bodyLength.toString()))
                    if (receivedResponse.code == 206) put("Content-Range", listOf(receivedResponse.header("Content-Range").orEmpty()))
                    if (seekable) put("Accept-Ranges", listOf("bytes"))
                }
                availableLength = remaining
                // URL 在连接开始时有效即可；响应到达后的过期不撤销此获取，配置则由原子交接核验。
                requireCurrent(newAcquisition = false)
                // 配置锁内只交接响应内存；健康流随后读 body 只核验账户，不重绑新配置。
                target.commitOpened(pending) {
                    synchronized(memory) {
                        if (closing) throw MediaTransportException(QueuePlaybackIssue.SOURCE_CHANGED)
                        response = receivedResponse
                        resource.opened.set(true)
                        accepted = true
                    }
                }
                requireCurrent(newAcquisition = false)
                resource.owner.notifyOpened(resource)
                return@physical remaining
            } finally {
                if (!accepted) receivedResponse.close()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        -1L
    }

    fun read(buffer: ByteArray, offset: Int, length: Int): Int = physical {
        if (offset < 0 || length < 0 || offset > buffer.size - length) throw MediaTransportException(QueuePlaybackIssue.RESOURCE_INVALID)
        if (length == 0) return@physical 0
        requireCurrent(newAcquisition = false)
        if (remaining == 0L) return@physical -1
        val owned = synchronized(memory) { response } ?: throw MediaTransportException(QueuePlaybackIssue.SOURCE_CHANGED)
        val limit = minOf(length.toLong(), 64 * 1024L, if (remaining < 0L) Long.MAX_VALUE else remaining).toInt()
        val count = try { owned.body.byteStream().read(buffer, offset, limit) }
        catch (error: IOException) { throw transportFailure(error) }
        requireCurrent(newAcquisition = false)
        if (count == 0) throw MediaTransportException(QueuePlaybackIssue.RESOURCE_INVALID)
        if (count < 0) {
            if (remaining > 0L) throw MediaTransportException(QueuePlaybackIssue.RESOURCE_UNAVAILABLE)
            return@physical -1
        }
        if (remaining >= 0L) remaining -= count
        count
    }

    fun requestClose() {
        val shouldCleanup = synchronized(memory) {
            closing = true
            if (operations == 0 && !cleanupClaimed) {
                cleanupClaimed = true
                true
            } else false
        }
        cancelCall()
        if (shouldCleanup) {
            if (Looper.myLooper() == Looper.getMainLooper()) cleanup.execute(::finish) else finish()
        }
    }

    private inline fun <T> physical(action: () -> T): T {
        synchronized(memory) {
            if (closing) throw MediaTransportException(QueuePlaybackIssue.SOURCE_CHANGED)
            operations++
        }
        try { return action() }
        catch (failure: MediaTransportException) { throw failure }
        catch (failure: IOException) { throw transportFailure(failure) }
        catch (_: RuntimeException) { throw MediaTransportException(QueuePlaybackIssue.RESOURCE_INVALID) }
        finally {
            val finishHere = synchronized(memory) {
                operations--
                if (closing && operations == 0 && !cleanupClaimed) {
                    cleanupClaimed = true
                    true
                } else false
            }
            if (finishHere) finish()
        }
    }

    private fun finish() {
        val (owned, pending) = synchronized(memory) {
            (response to pendingConfiguration).also {
                response = null
                call = null
                pendingConfiguration = null
            }
        }
        try { owned?.close() }
        finally {
            try { pending?.close() }
            finally {
                resource.opened.set(false)
                try {
                    resource.owner.notifyClosed(resource)
                    resource.owner.detachCancellation(resource, cancelCall)
                } finally { onFinished() }
            }
        }
    }
    private fun requireCurrent(newAcquisition: Boolean) {
        synchronized(memory) { if (closing) throw MediaTransportException(QueuePlaybackIssue.SOURCE_CHANGED) }
        resource.owner.check(resource, newAcquisition)
    }
    private fun checkHeaders(value: Response) {
        if (value.headers.size > 128) throw MediaTransportException(QueuePlaybackIssue.RESOURCE_INVALID)
        var bytes = 0L
        for (index in 0 until value.headers.size) {
            bytes += value.headers.name(index).length.toLong() + value.headers.value(index).length + 4L
            if (bytes > 32 * 1024L) throw MediaTransportException(QueuePlaybackIssue.RESOURCE_INVALID)
        }
    }
    private fun transportFailure(error: IOException): MediaTransportException {
        if (error is MediaTransportException) return error
        try { resource.owner.checkPrepared(resource) }
        catch (failure: MediaTransportException) { return failure }
        return MediaTransportException(when (error) {
            is SSLException -> QueuePlaybackIssue.NETWORK_CERTIFICATE
            is InterruptedIOException -> QueuePlaybackIssue.NETWORK_TIMEOUT
            else -> QueuePlaybackIssue.NETWORK_UNAVAILABLE
        })
    }
    private fun invalidRange(): Nothing = throw MediaTransportException(QueuePlaybackIssue.RESOURCE_INVALID)

    companion object {
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+|\\*)")
        private val MIME = Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")
    }
}
