package io.github.onlinemo.meloxwatch.companion.transfer

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.github.onlinemo.meloxwatch.companion.data.CompanionClock
import io.github.onlinemo.meloxwatch.companion.data.CompanionEndpoint
import io.github.onlinemo.meloxwatch.companion.data.CompanionLimits
import io.github.onlinemo.meloxwatch.data.transfer.TransferCatalogPage
import io.github.onlinemo.meloxwatch.data.transfer.TransferCatalogRow
import io.github.onlinemo.meloxwatch.data.transfer.TransferException
import io.github.onlinemo.meloxwatch.data.transfer.TransferFingerprint
import io.github.onlinemo.meloxwatch.data.transfer.TransferIssue
import io.github.onlinemo.meloxwatch.data.transfer.TransferLimits
import io.github.onlinemo.meloxwatch.data.transfer.TransferProtocolCodec
import io.github.onlinemo.meloxwatch.data.transfer.TransferRequest
import io.github.onlinemo.meloxwatch.data.transfer.TransferResponse
import io.github.onlinemo.meloxwatch.data.transfer.TransferTls
import io.github.onlinemo.meloxwatch.data.transfer.TransferTlsIdentity
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal interface CompanionWireConnection {
    val greeting: TransferResponse.Greeting
    /** 连接前计时，缓冲中的问候不能延长设备会话。 */
    val greetingObservedAtMs: Long
    val peer: TransferFingerprint
    val retired: Boolean
    val accepting: Boolean
    suspend fun exchange(request: TransferRequest): TransferResponse
    suspend fun page(request: TransferRequest.ReadPage): TransferCatalogPage
    fun close(): Deferred<Unit>
    suspend fun retryRetirement()
}

internal interface CompanionWireFactory {
    val retirementRequired: Boolean
    suspend fun open(endpoint: CompanionEndpoint, target: TransferFingerprint, expectedSession: String?): CompanionWireConnection
    fun stop(): Deferred<Unit>
    suspend fun retryRetirement()
}

/** 明确绑定用户选择的 Wi-Fi 网络，数值地址不经 DNS 或 VPN 回退。 */
internal class TlsCompanionWireFactory(
    private val connectivity: ConnectivityManager,
    private val identity: suspend () -> TransferTlsIdentity,
    private val clock: CompanionClock = CompanionClock { System.nanoTime() / 1_000_000L },
    private val retirementWaitMs: Long = CompanionLimits.RETIREMENT_WAIT_MS,
) : CompanionWireFactory {
    init { require(retirementWaitMs > 0L) }
    private val lock = Any()
    private var current: TlsCompanionConnection? = null
    private var preparing = false
    override val retirementRequired: Boolean get() = synchronized(lock) { current?.retirementFailed == true }

    override suspend fun open(endpoint: CompanionEndpoint, target: TransferFingerprint, expectedSession: String?): CompanionWireConnection {
        synchronized(lock) {
            if (preparing || current?.retired == false) throw TransferException(TransferIssue.BUSY)
            preparing = true
        }
        val connection = try {
            val localIdentity = identity()
            coroutineContext.ensureActive()
            synchronized(lock) {
                if (!preparing) throw TransferException(TransferIssue.CLOSED)
                TlsCompanionConnection(localIdentity, clock, retirementWaitMs = retirementWaitMs).also {
                    current = it
                    preparing = false
                }
            }
        } catch (error: Exception) {
            synchronized(lock) { preparing = false }
            throw error
        }
        try {
            connection.prepare {
                selectedWifi().bindSocket(connection.raw)
                connection.raw.connect(InetSocketAddress(endpoint.address, endpoint.port), CompanionLimits.CONNECT_MS.toInt())
                connection.prepareTls(target, expectedSession)
            }
            return connection
        } catch (error: Exception) {
            var cleanupFailed = false
            withContext(NonCancellable) {
                try { awaitTlsRetirement(connection.close(), retirementWaitMs) }
                catch (_: Exception) { cleanupFailed = true }
            }
            if (cleanupFailed) error.addSuppressed(TransferException(TransferIssue.RECOVERY_REQUIRED))
            if (error is TransferException || error is CancellationException) throw error
            throw TransferException(TransferIssue.TRANSPORT_FAILED).also {
                if (cleanupFailed) it.addSuppressed(TransferException(TransferIssue.RECOVERY_REQUIRED))
            }
        }
    }

    override fun stop(): Deferred<Unit> = synchronized(lock) {
        preparing = false
        current?.takeIf { !it.retired }?.close() ?: CompletableDeferred(Unit)
    }

    // 用户请求时读取快照；两个候选已足以拒绝歧义，不注册后台网络回调。
    @Suppress("DEPRECATION")
    private fun selectedWifi(): Network {
        val candidates = connectivity.allNetworks.asSequence().filter { network ->
            val capabilities = connectivity.getNetworkCapabilities(network)
            capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }.take(2).toList()
        if (candidates.size != 1) throw TransferException(TransferIssue.TRANSPORT_FAILED)
        return candidates.single()
    }

    override suspend fun retryRetirement() {
        val retained = synchronized(lock) { current } ?: return
        retained.retryRetirement()
        synchronized(lock) { if (current === retained && retained.retired) current = null }
    }
}

/** 读写任务和关闭任务各保留唯一物理所有者，控制取消只结束有界等待。 */
internal class TlsCompanionConnection(
    private val identity: TransferTlsIdentity,
    clock: CompanionClock,
    val raw: Socket = Socket(),
    private val retirementWaitMs: Long = CompanionLimits.RETIREMENT_WAIT_MS,
) : CompanionWireConnection {
    init { require(retirementWaitMs > 0L) }
    override val greetingObservedAtMs: Long = clock.monotonicMs()
    private val lock = Any()
    private val workers = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val closer = ScheduledThreadPoolExecutor(1) { work -> Thread(work, "melox-phone-tls-close").apply { isDaemon = true } }.apply { removeOnCancelPolicy = true }
    private val closing = AtomicBoolean(false)
    private val physicallyRetired = AtomicBoolean(false)
    private var active: Deferred<*>? = null
    private var retirementAttempt: Deferred<Unit>? = null
    private val closed = CompletableDeferred<Unit>()
    private var idle: ScheduledFuture<*>? = null
    private var socket: SSLSocket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null
    private var opening: TransferResponse.Greeting? = null
    override val greeting: TransferResponse.Greeting get() = checkNotNull(opening)
    override val peer: TransferFingerprint get() = identity.peerFingerprint
    override val retired: Boolean get() = physicallyRetired.get()
    override val accepting: Boolean get() = !closing.get()
    val retirementFailed: Boolean get() = closing.get() && closed.isCompleted && !retired

    suspend fun prepare(action: () -> Unit) { physical(CompanionLimits.CONNECT_MS + TransferLimits.HANDSHAKE_MS + TransferLimits.IDLE_MS, action) }

    fun prepareTls(target: TransferFingerprint, expectedSession: String?) {
        val tls = TransferTls.clientSocket(TransferTls.clientContext(identity, target), raw)
        socket = tls
        val handshake = closer.schedule({ close() }, TransferLimits.HANDSHAKE_MS, TimeUnit.MILLISECONDS)
        try { tls.startHandshake() } finally { handshake.cancel(false) }
        input = BufferedInputStream(tls.inputStream, TransferLimits.FRAME_BYTES)
        output = BufferedOutputStream(tls.outputStream, TransferLimits.FRAME_BYTES)
        opening = CompanionResponseValidator.greeting(TransferProtocolCodec.readResponse(checkNotNull(input)), target, expectedSession)
    }

    override suspend fun exchange(request: TransferRequest): TransferResponse = physical(TransferLimits.OPERATION_MS) {
        TransferProtocolCodec.writeRequest(checkNotNull(output), request)
        TransferProtocolCodec.readResponse(checkNotNull(input))
    }

    override suspend fun page(request: TransferRequest.ReadPage): TransferCatalogPage = physical(TransferLimits.OPERATION_MS) {
        TransferProtocolCodec.writeRequest(checkNotNull(output), request)
        val stream = checkNotNull(input)
        // 先检验一个有界首帧，避免把合法协议错误误读为分页数据。
        stream.mark(TransferLimits.FRAME_BYTES)
        val first = CompanionResponseValidator.response(request.requestId, TransferProtocolCodec.readResponse(stream))
        if (first !is TransferResponse.PageBegin) throw TransferException(TransferIssue.INVALID_INPUT)
        stream.reset()
        val rows = ArrayList<TransferCatalogRow>(TransferLimits.PAGE_SIZE)
        val completion = TransferProtocolCodec.readPage(stream, request.requestId) { row ->
            if (closing.get()) throw TransferException(TransferIssue.CLOSED)
            rows += row.row
        }
        val begin = completion.begin
        TransferCatalogPage(begin.kind, begin.viewId, begin.revision, begin.libraryRevision, begin.totalCount, begin.firstOrdinal, rows, begin.nextCursor)
    }

    private suspend fun <T> physical(durationMs: Long, action: () -> T): T {
        coroutineContext.ensureActive()
        val work = synchronized(lock) {
            if (closing.get() || active != null) throw TransferException(TransferIssue.BUSY)
            idle?.cancel(false)
            idle = null
            workers.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                val deadline = closer.schedule({ close() }, durationMs, TimeUnit.MILLISECONDS)
                try {
                    coroutineContext.ensureActive()
                    action().also { if (closing.get()) throw TransferException(TransferIssue.CLOSED) }
                }
                finally { deadline.cancel(false) }
            }.also { owned ->
                active = owned
                // 只有真实任务完成才能释放占用；取消等待者不能代替物理退出。
                owned.invokeOnCompletion {
                    synchronized(lock) {
                        if (active === owned) active = null
                        if (!closing.get()) idle = closer.schedule({ close() }, CompanionLimits.IDLE_MS, TimeUnit.MILLISECONDS)
                    }
                }
            }
        }
        work.start()
        try { return work.await() }
        catch (error: Exception) {
            close()
            if (error is TransferException || error is CancellationException) throw error
            throw TransferException(TransferIssue.TRANSPORT_FAILED)
        } finally {
            if (!work.isCompleted) close()
        }
    }

    override fun close(): Deferred<Unit> {
        val attempt = synchronized(lock) {
            if (!closing.compareAndSet(false, true)) return closed
            idle?.cancel(false)
            idle = null
            newRetirementAttempt().also { retirementAttempt = it }
        }
        // 首次失败回执永远保留失败；后续实际关闭成功不改写旧证据。
        attempt.invokeOnCompletion { error ->
            if (error == null) closed.complete(Unit)
            else closed.completeExceptionally(TransferException(TransferIssue.RECOVERY_REQUIRED))
        }
        attempt.start()
        return closed
    }

    private suspend fun retire() {
        // 必要关闭已失败时立即给出失败回执，保留阻塞读写和全部句柄供真实重试。
        try { raw.close() }
        catch (_: Exception) { throw TransferException(TransferIssue.RECOVERY_REQUIRED) }
        if (!raw.isClosed) throw TransferException(TransferIssue.RECOVERY_REQUIRED)
        val worker = synchronized(lock) { active }
        worker?.join()
        val retainedTls = synchronized(lock) { socket }
        try { retainedTls?.close() }
        catch (_: Exception) { throw TransferException(TransferIssue.RECOVERY_REQUIRED) }
        if (retainedTls != null && !retainedTls.isClosed) throw TransferException(TransferIssue.RECOVERY_REQUIRED)
        synchronized(lock) {
            input = null
            output = null
            socket = null
            if (active === worker) active = null
            physicallyRetired.set(true)
        }
        workers.cancel()
        closer.shutdown()
    }

    private fun newRetirementAttempt(): Deferred<Unit> = closeScope.async(
        NonCancellable, start = kotlinx.coroutines.CoroutineStart.LAZY,
    ) { retire() }

    override suspend fun retryRetirement() {
        val retry = synchronized(lock) {
            if (retired) return
            if (!closing.get()) throw TransferException(TransferIssue.BUSY)
            // 未完成时复用同一物理关闭；失败已完成后才重新调用真实 close。
            retirementAttempt?.takeIf { !it.isCompleted }
                ?: newRetirementAttempt().also { retirementAttempt = it }
        }
        retry.start()
        awaitTlsRetirement(retry, retirementWaitMs)
    }
    override fun toString(): String = "<local-mtls-connection>"
}

private suspend fun awaitTlsRetirement(receipt: Deferred<Unit>, waitMs: Long) {
    try { withTimeout(waitMs) { receipt.await() } }
    catch (_: TimeoutCancellationException) { throw TransferException(TransferIssue.RECOVERY_REQUIRED) }
}
