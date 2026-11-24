package io.github.onlinemo.meloxwatch.data.transfer

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class TransferServerPhase { OFF, OPENING, LISTENING, CLOSING, RECOVERY_REQUIRED }

class LocalTransferHandle(val port: Int, val opening: TransferOpening) {
    override fun toString(): String = "<local-listening-handle>"
}

/**
 * Default zero sockets/tasks. Explicit start binds only a caller-selected local address.
 * Background/exit calls stop; foreground restoration never starts a new session implicitly.
 * Stop first invalidates permits, dispatches raw-socket closes, then acknowledges physical
 * connection/provider retirement. No cancellation callback closes a socket or file on Main.
 */
class LocalTransferServer internal constructor(
    private val store: TransferUploadStore,
    private val backend: TransferBackend,
    private val listenerFactory: () -> ServerSocket,
) {
    constructor(store: TransferUploadStore, backend: TransferBackend) : this(store, backend, ::ServerSocket)

    private val lock = Any()
    private val acceptDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val networkDispatcher = Dispatchers.IO.limitedParallelism(TransferLimits.CONNECTIONS)
    private val cleanupDispatcher = Dispatchers.IO.limitedParallelism(1)
    // 物理退役由应用拥有，不归可取消的控制调用方。
    private val retirementScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var resources: Resources? = null
    private var openingEpoch: String? = null
    private var openingStopped: CompletableDeferred<Unit>? = null
    private var failedOpeningListener: ServerSocket? = null
    private var openingRetirementJob: Job? = null
    private val mutablePhase = MutableStateFlow(TransferServerPhase.OFF)
    val phase: StateFlow<TransferServerPhase> = mutablePhase.asStateFlow()
    private val mutableConnectionCount = MutableStateFlow(0)
    val connectionCount: StateFlow<Int> = mutableConnectionCount.asStateFlow()
    private val authority = TransferSessionAuthority(onRevoked = ::closeConnections)
    val sessionState: StateFlow<TransferLocalSessionState> get() = authority.state
    val uploadProgress: StateFlow<TransferUploadStatus?> get() = store.progress

    private class Resources(
        val listener: ServerSocket,
        val tls: SSLContext,
        val opening: TransferOpening,
        val scope: CoroutineScope,
        val closer: ScheduledThreadPoolExecutor,
    ) {
        val connections = linkedSetOf<Connection>()
        // 串行 accept 除已接纳连接外，最多保留一条待退役的拒绝连接。
        var rejectedConnection: Connection? = null
        val stopping = AtomicBoolean(false)
        val retirementMutex = Mutex()
        var acceptRetirement: Deferred<Unit>? = null
        var retirementJob: Job? = null
        val listenerClose = PhysicalClose {
            listener.close()
            if (!listener.isClosed) throw TransferException(TransferIssue.RECOVERY_REQUIRED)
        }
        var stopped = CompletableDeferred<Unit>()
        var acceptJob: Job? = null
        var sessionDeadline: ScheduledFuture<*>? = null
        var acceptWindow = System.nanoTime()
        var accepted = 0
    }

    private class Connection(val raw: Socket) {
        @Volatile var job: Job? = null
        @Volatile var peer: TransferFingerprint? = null
        @Volatile var tls: SSLSocket? = null
        @Volatile var processor: TransferRequestProcessor? = null
        // 此字段在服务器锁内读写；待清理任务保留同一物理所有者。
        var retirement: Deferred<Unit>? = null
        private val rawClose = PhysicalClose {
            raw.close()
            if (!raw.isClosed) throw TransferException(TransferIssue.RECOVERY_REQUIRED)
        }
        fun requestClose(closer: ScheduledThreadPoolExecutor, retry: Boolean = false): Deferred<Unit> = rawClose.request(closer, retry)
    }

    /** 失败收据保持失败；明确恢复才在非主线程创建新关闭尝试。 */
    private class PhysicalClose(private val close: () -> Unit) {
        private val lock = Any()
        private var receipt: CompletableDeferred<Unit>? = null

        fun request(closer: ScheduledThreadPoolExecutor, retry: Boolean = false): Deferred<Unit> = synchronized(lock) {
            val previous = receipt
            if (previous != null && (!retry || !previous.isCompleted || !previous.isCancelled)) return@synchronized previous
            val next = CompletableDeferred<Unit>()
            receipt = next
            try {
                closer.execute {
                    try { close()
                        next.complete(Unit) }
                    catch (_: Exception) { next.completeExceptionally(TransferException(TransferIssue.RECOVERY_REQUIRED)) }
                }
            } catch (_: Exception) {
                next.completeExceptionally(TransferException(TransferIssue.RECOVERY_REQUIRED))
            }
            next
        }
    }

    suspend fun start(identity: TransferTlsIdentity, address: InetAddress, port: Int = 0): LocalTransferHandle =
        start(identity, address, port) { true }

    /** admission 只读取内存资格；在服务器锁内核验，关闭不能漏掉迟到启动。 */
    internal suspend fun start(identity: TransferTlsIdentity, address: InetAddress, port: Int,
        admission: () -> Boolean): LocalTransferHandle {
        coroutineContext.ensureActive()
        if (!isLocalAddress(address) || address.isAnyLocalAddress || address.isMulticastAddress || port != 0 && port !in 1024..65535) throw TransferException(TransferIssue.INVALID_INPUT)
        val epoch = UUID.randomUUID().toString()
        val stopped = CompletableDeferred<Unit>()
        synchronized(lock) {
            if (!admission()) throw TransferException(TransferIssue.CLOSED)
            if (resources != null || openingEpoch != null || mutablePhase.value != TransferServerPhase.OFF) throw TransferException(TransferIssue.BUSY)
            openingEpoch = epoch
            openingStopped = stopped
            mutablePhase.value = TransferServerPhase.OPENING
        }
        try {
            // Once OPENING is visible, cancellation still owes a physical prepare/close receipt.
            return withContext(NonCancellable + cleanupDispatcher) {
                val listener = try { listenerFactory() } catch (failure: Exception) {
                    synchronized(lock) {
                        if (openingEpoch == epoch) { openingEpoch = null
                            openingStopped = null
                            mutablePhase.value = TransferServerPhase.OFF }
                    }
                    stopped.complete(Unit)
                    throw failure
                }
                var installed = false
                var beganAuthority = false
                var prepared: Resources? = null
                try {
                    listener.reuseAddress = false
                    listener.bind(InetSocketAddress(address, port), TransferLimits.CONNECTIONS)
                    val tls = TransferTls.serverContext(identity)
                    synchronized(lock) {
                        if (openingEpoch != epoch || mutablePhase.value != TransferServerPhase.OPENING || !admission()) throw TransferException(TransferIssue.CLOSED)
                        val opening = authority.begin(identity.targetFingerprint)
                        beganAuthority = true
                        val closer = ScheduledThreadPoolExecutor(1) { command -> Thread(command, "melox-local-transfer-close").apply { isDaemon = true } }.apply { removeOnCancelPolicy = true }
                        val current = Resources(listener, tls, opening, CoroutineScope(SupervisorJob() + Dispatchers.Default), closer)
                        prepared = current
                        resources = current
                        openingEpoch = null
                        openingStopped = null
                        installed = true
                        mutablePhase.value = TransferServerPhase.LISTENING
                        current.acceptJob = current.scope.launch(acceptDispatcher) { accept(current) }
                        current.sessionDeadline = closer.schedule({ requestStop(current, TransferIssue.EXPIRED) }, opening.remainingMs, TimeUnit.MILLISECONDS)
                        LocalTransferHandle(listener.localPort, opening)
                    }
                } finally {
                    if (!installed) {
                        var closeFailed = false
                        try { listener.close()
                            closeFailed = !listener.isClosed } catch (_: Exception) { closeFailed = true }
                        if (beganAuthority) authority.stop(TransferIssue.TRANSPORT_FAILED)
                        prepared?.scope?.cancel()
                        prepared?.closer?.shutdown()
                        synchronized(lock) {
                            if (openingEpoch == epoch) {
                                openingEpoch = null
                                if (closeFailed) { failedOpeningListener = listener
                                    mutablePhase.value = TransferServerPhase.RECOVERY_REQUIRED }
                                else { openingStopped = null
                                    mutablePhase.value = TransferServerPhase.OFF }
                            }
                        }
                        if (closeFailed) stopped.completeExceptionally(TransferException(TransferIssue.RECOVERY_REQUIRED)) else stopped.complete(Unit)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { stop().await() }
            throw cancelled
        } catch (error: TransferException) { throw error }
          catch (_: Exception) { throw TransferException(TransferIssue.TRANSPORT_FAILED) }
    }

    fun pairingCodeForLocalUi(): String? = authority.pairingCodeForLocalUi()
    fun approveForLocalUi(peer: TransferFingerprint, capabilities: Set<TransferCapability>) { authority.approveForLocalUi(peer, capabilities) }
    fun revokeForLocalUi(peer: TransferFingerprint) { authority.revokeForLocalUi(peer) }
    fun remainingMs(): Long = authority.remainingMs()
    fun activeConnectionCount(): Int = synchronized(lock) { resources?.connections?.size ?: 0 }

    fun extendForLocalUi() = synchronized(lock) {
        val current = resources ?: throw TransferException(TransferIssue.CLOSED)
        if (current.stopping.get()) throw TransferException(TransferIssue.CLOSED)
        val remaining = authority.extendForLocalUi()
        current.sessionDeadline?.cancel(false)
        current.sessionDeadline = current.closer.schedule({ requestStop(current, TransferIssue.EXPIRED) }, remaining, TimeUnit.MILLISECONDS)
    }

    /** The returned Deferred is a physical retirement receipt; CLOSING is not a completed stop. */
    fun stop(issue: TransferIssue = TransferIssue.CLOSED): Deferred<Unit> {
        val current = synchronized(lock) {
            val active = resources
            if (active == null && openingEpoch != null) {
                mutablePhase.value = TransferServerPhase.CLOSING
                return checkNotNull(openingStopped)
            }
            if (active == null && failedOpeningListener != null) return checkNotNull(openingStopped)
            active
        }
        if (current == null) return CompletableDeferred(Unit)
        requestStop(current, issue)
        return synchronized(lock) { current.stopped }
    }

    /** 取消仅结束本次等待；再次等待复用保留的物理尝试。 */
    suspend fun retryRetirement() {
        coroutineContext.ensureActive()
        var worker: Job? = null
        val receipt = synchronized(lock) {
            val current = resources
            val listener = failedOpeningListener
            if (mutablePhase.value == TransferServerPhase.CLOSING) {
                if (current != null) return@synchronized current.stopped
                if (listener != null) return@synchronized checkNotNull(openingStopped)
                throw TransferException(TransferIssue.BUSY)
            }
            if (mutablePhase.value != TransferServerPhase.RECOVERY_REQUIRED) throw TransferException(TransferIssue.INVALID_INPUT)
            check(current != null || listener != null)
            val next = CompletableDeferred<Unit>()
            mutablePhase.value = TransferServerPhase.CLOSING
            if (current != null) {
                current.stopped = next
                worker = retirementScope.launch(start = CoroutineStart.LAZY) {
                    completeRetirement(current, next, retry = true)
                }.also { current.retirementJob = it }
            } else {
                openingStopped = next
                worker = retirementScope.launch(start = CoroutineStart.LAZY) {
                    completeOpeningRetirement(checkNotNull(listener), next)
                }.also { openingRetirementJob = it }
            }
            next
        }
        worker?.start()
        receipt.await()
    }

    private suspend fun completeOpeningRetirement(listener: ServerSocket, receipt: CompletableDeferred<Unit>) {
        try {
            withContext(cleanupDispatcher) {
                listener.close()
                if (!listener.isClosed) throw TransferException(TransferIssue.RECOVERY_REQUIRED)
            }
            synchronized(lock) {
                if (failedOpeningListener === listener) {
                    failedOpeningListener = null
                    openingStopped = null
                    openingRetirementJob = null
                    mutablePhase.value = TransferServerPhase.OFF
                }
            }
            receipt.complete(Unit)
        } catch (cancelled: CancellationException) {
            synchronized(lock) { if (failedOpeningListener === listener) mutablePhase.value = TransferServerPhase.RECOVERY_REQUIRED }
            receipt.completeExceptionally(cancelled)
            throw cancelled
        } catch (_: Exception) {
            synchronized(lock) { if (failedOpeningListener === listener) mutablePhase.value = TransferServerPhase.RECOVERY_REQUIRED }
            receipt.completeExceptionally(TransferException(TransferIssue.RECOVERY_REQUIRED))
        }
    }

    private suspend fun accept(current: Resources) {
        try {
            while (coroutineContext.isActive && !current.stopping.get()) {
                val raw = current.listener.accept()
                val connection = Connection(raw)
                val accepted = synchronized(lock) {
                    val now = System.nanoTime()
                    if (now - current.acceptWindow >= TimeUnit.MINUTES.toNanos(1)) { current.acceptWindow = now
                        current.accepted = 0 }
                    if (resources !== current || current.stopping.get() || current.connections.size >= TransferLimits.CONNECTIONS || !isLocalAddress(raw.inetAddress) || ++current.accepted > TransferLimits.ACCEPTS_PER_MINUTE) {
                        current.rejectedConnection = connection
                        false
                    }
                    else {
                        current.connections.add(connection)
                        mutableConnectionCount.value = current.connections.size
                        // 接纳时先绑定 serve 所有者，stop 才能退役其占用槽。
                        val job = current.scope.launch(networkDispatcher, start = CoroutineStart.LAZY) {
                            serve(current, connection)
                        }
                        connection.job = job
                        job.invokeOnCompletion { requestConnectionRetirement(current, connection) }
                        true
                    }
                }
                if (!accepted) {
                    try {
                        requestConnectionRetirement(current, connection).await()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        requestStop(current, TransferIssue.RECOVERY_REQUIRED)
                        break
                    }
                    continue
                }
                val job = checkNotNull(connection.job)
                if (current.stopping.get()) {
                    job.cancel()
                    connection.requestClose(current.closer)
                } else job.start()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (!current.stopping.get()) requestStop(current, TransferIssue.TRANSPORT_FAILED)
        }
    }

    private suspend fun serve(current: Resources, connection: Connection) {
        try {
            val connected = TransferTls.serverSocket(current.tls, connection.raw)
            connection.tls = connected
            timed(current, connection, TransferLimits.HANDSHAKE_MS) { connected.startHandshake() }
            val peer = TransferTls.authenticatedPeer(connected)
            connection.peer = peer
            val handler = TransferRequestProcessor(peer, authority, store, backend)
            connection.processor = handler
            val input = BufferedInputStream(connected.inputStream, TransferLimits.FRAME_BYTES)
            val output = BufferedOutputStream(connected.outputStream, TransferLimits.FRAME_BYTES)
            val opening = authority.currentOpening()
            writeWithIdleDeadline(current, connection, output, TransferResponse.Greeting(UUID(0L, 0L).toString(), opening.target, opening.sessionId, opening.remainingMs)) { authority.currentOpening() }
            while (coroutineContext.isActive && !current.stopping.get()) {
                val request = timed(current, connection, TransferLimits.IDLE_MS) { TransferProtocolCodec.readRequest(input) }
                try {
                    timed(current, connection, TransferLimits.OPERATION_MS) {
                        val result = handler.process(request)
                        writeWithIdleDeadline(current, connection, output, result.response, result.checkActive)
                    }
                } catch (failure: TransferException) {
                    writeWithIdleDeadline(current, connection, output, TransferResponse.Failure(request.requestId, failure.issue)) {}
                    if (failure.issue in setOf(TransferIssue.RATE_LIMITED, TransferIssue.CLOSED, TransferIssue.RECOVERY_REQUIRED)) break
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Public state/errors contain no peer certificate, address, selected name or exception text.
        } finally {
            // 完成清理等待此 job，关闭成功前保留 TLS 与处理器所有者。
            connection.requestClose(current.closer)
        }
    }

    private suspend fun <T> timed(current: Resources, connection: Connection, duration: Long, action: suspend () -> T): T {
        coroutineContext.ensureActive()
        // 计时线程只派发退役。provider 的同步取消监听不能占住唯一物理关闭线程。
        val deadline = current.closer.schedule({ requestConnectionRetirement(current, connection) }, duration, TimeUnit.MILLISECONDS)
        try { return action().also { coroutineContext.ensureActive() } }
        finally { deadline.cancel(false) }
    }

    private fun writeWithIdleDeadline(current: Resources, connection: Connection, output: BufferedOutputStream, response: TransferResponse, checkActive: () -> Unit) {
        var idleDeadline: ScheduledFuture<*>? = null
        try {
            TransferProtocolCodec.writeResponse(output, response) {
                checkActive()
                idleDeadline?.cancel(false)
                idleDeadline = current.closer.schedule({ requestConnectionRetirement(current, connection) },
                    TransferLimits.IDLE_MS, TimeUnit.MILLISECONDS)
            }
        } finally { idleDeadline?.cancel(false) }
    }

    /** 失败和待定收据仍绑定实际 raw/TLS/provider/lease 所有者。 */
    private fun requestConnectionRetirement(current: Resources, connection: Connection, retry: Boolean = false): Deferred<Unit> {
        val worker = synchronized(lock) {
            val previous = connection.retirement
            if (previous != null && (!retry || !previous.isCompleted || !previous.isCancelled)) return@synchronized previous
            if (connection !in current.connections && current.rejectedConnection !== connection) return@synchronized CompletableDeferred(Unit)
            retirementScope.async(start = CoroutineStart.LAZY) {
                try { retireConnection(current, connection, retry) }
                catch (cancelled: CancellationException) {
                    requestStop(current, TransferIssue.RECOVERY_REQUIRED)
                    throw cancelled
                } catch (_: Exception) {
                    requestStop(current, TransferIssue.RECOVERY_REQUIRED)
                    throw TransferException(TransferIssue.RECOVERY_REQUIRED)
                }
            }.also { connection.retirement = it }
        }
        worker.start()
        return worker
    }

    private suspend fun retireConnection(current: Resources, connection: Connection, retry: Boolean) {
        // raw 关闭错误必须先回执，不能被同步 provider 取消监听遮住。
        connection.requestClose(current.closer, retry).await()
        connection.job?.cancel()
        connection.job?.join()
        connection.tls?.let { socket ->
            withContext(cleanupDispatcher) {
                socket.close()
                if (!socket.isClosed) throw TransferException(TransferIssue.RECOVERY_REQUIRED)
            }
            connection.tls = null
        }
        connection.processor?.let { processor ->
            processor.close()
            connection.processor = null
        }
        // 仅同一 peer 最后连接的 lease/移除事务串行化。其他 provider 等待
        // 不能遮住已知 listener、raw 或 TLS 关闭错误的汇总回执。
        current.retirementMutex.withLock {
            val lastPeer = synchronized(lock) {
                connection.peer?.takeIf { peer -> current.connections.none { it !== connection && it.peer == peer } }
            }
            lastPeer?.let { backend.closePeerViews(it, current.opening.sessionId) }
            synchronized(lock) {
                current.connections.remove(connection)
                if (current.rejectedConnection === connection) current.rejectedConnection = null
                mutableConnectionCount.value = current.connections.size
            }
        }
    }

    private fun requestAcceptRetirement(current: Resources, retry: Boolean): Deferred<Unit> {
        val worker = synchronized(lock) {
            val previous = current.acceptRetirement
            if (previous != null && (!retry || !previous.isCompleted || !previous.isCancelled)) return@synchronized previous
            retirementScope.async(start = CoroutineStart.LAZY) {
                current.listenerClose.request(current.closer, retry).await()
                current.acceptJob?.join()
                Unit
            }.also { current.acceptRetirement = it }
        }
        worker.start()
        return worker
    }

    private fun closeConnections(peer: TransferFingerprint?) {
        val snapshot = synchronized(lock) { resources?.let { current -> current to current.connections.filter { peer == null || it.peer == peer } } } ?: return
        // 撤权可在 Main 调用；这里只派发已有连接所有者，取消监听在其后台任务执行。
        snapshot.second.forEach { connection -> requestConnectionRetirement(snapshot.first, connection) }
    }

    private fun requestStop(current: Resources, issue: TransferIssue) {
        if (!current.stopping.compareAndSet(false, true)) return
        val worker = synchronized(lock) {
            if (resources === current) mutablePhase.value = TransferServerPhase.CLOSING
            val receipt = current.stopped
            retirementScope.launch(start = CoroutineStart.LAZY) {
                completeRetirement(current, receipt, retry = false)
            }.also { current.retirementJob = it }
        }
        current.sessionDeadline?.cancel(false)
        authority.stop(issue)
        current.listenerClose.request(current.closer)
        worker.start()
    }

    private suspend fun completeRetirement(current: Resources, receipt: CompletableDeferred<Unit>, retry: Boolean) {
        try {
            retireResources(current, retry)
            withContext(cleanupDispatcher) {
                current.closer.shutdown()
                if (!current.closer.awaitTermination(1000L, TimeUnit.MILLISECONDS)) throw TransferException(TransferIssue.RECOVERY_REQUIRED)
            }
            current.scope.cancel()
            current.scope.coroutineContext[Job]?.join()
            finishRetirement(current, receipt, null)
        } catch (cancelled: CancellationException) {
            finishRetirement(current, receipt, cancelled)
            throw cancelled
        } catch (_: Exception) {
            finishRetirement(current, receipt, TransferException(TransferIssue.RECOVERY_REQUIRED))
        }
    }

    private fun finishRetirement(current: Resources, receipt: CompletableDeferred<Unit>, failure: Exception?) {
        synchronized(lock) {
            if (resources === current) {
                if (failure == null) {
                    resources = null
                    mutableConnectionCount.value = 0
                    mutablePhase.value = TransferServerPhase.OFF
                }
                else mutablePhase.value = TransferServerPhase.RECOVERY_REQUIRED
            }
        }
        if (failure == null) receipt.complete(Unit) else receipt.completeExceptionally(failure)
    }

    private suspend fun retireResources(current: Resources, retry: Boolean) {
        fun ownedConnections(): List<Connection> = synchronized(lock) { current.connections.toList() + listOfNotNull(current.rejectedConnection) }
        val acceptRetirement = requestAcceptRetirement(current, retry)
        val connectionRetirements = ownedConnections().map { requestConnectionRetirement(current, it, retry) }
        // 各 Deferred 有独立应用所有者；awaitAll 及时报告已知错误，
        // 被阻塞的其他任务仍保留各自物理票据。
        (connectionRetirements + acceptRetirement).awaitAll()
        // 实际 accept job 已退出；把其最后一条拒绝连接纳入退役后才能置 OFF。
        ownedConnections().map { requestConnectionRetirement(current, it, retry) }.awaitAll()
        val allRetired = synchronized(lock) { current.connections.isEmpty() && current.rejectedConnection == null }
        if (!allRetired) throw TransferException(TransferIssue.RECOVERY_REQUIRED)
        store.retireAbandoned()
        store.awaitIdle()
    }

    private fun isLocalAddress(address: InetAddress): Boolean = address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
        (address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc)
}
