package io.github.onlinemo.meloxwatch.data.transfer

import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 地址只供本机界面选择；不接受主机名，不解析 DNS，也不进入保存状态或诊断。 */
class LocalTransferAddress internal constructor(internal val address: InetAddress) {
    fun forLocalDisplay(): String = checkNotNull(address.hostAddress)
    override fun toString(): String = "<local-receiver-address>"
}

/** 只枚举操作系统已有的接口，不探测远端、广播或创建连接。 */
suspend fun localTransferAddresses(): List<LocalTransferAddress> = withContext(Dispatchers.IO) {
    val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@withContext emptyList()
    val selected = ArrayList<LocalTransferAddress>()
    while (interfaces.hasMoreElements()) {
        currentCoroutineContext().ensureActive()
        val network = interfaces.nextElement()
        if (!network.isUp || network.isLoopback) continue
        val addresses = network.inetAddresses
        while (addresses.hasMoreElements()) {
            val address = addresses.nextElement()
            if (receiverLocalAddress(address) && !address.isLoopbackAddress &&
                selected.none { it.address == address }) selected.add(LocalTransferAddress(address))
            if (selected.size == MAX_LOCAL_ADDRESSES) return@withContext selected.toList()
        }
    }
    selected.toList()
}

private const val MAX_LOCAL_ADDRESSES = 16

private fun receiverLocalAddress(address: InetAddress): Boolean =
    !address.isAnyLocalAddress && !address.isMulticastAddress &&
        (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
            address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc)

enum class ReceiverControlPhase { READY, PREPARING, RECEIVING, CLOSING, RECOVERY_REQUIRED }

data class ReceiverControlState(
    val phase: ReceiverControlPhase = ReceiverControlPhase.READY,
    val address: LocalTransferAddress? = null,
    val handle: LocalTransferHandle? = null,
    val issue: TransferIssue? = null,
)

/**
 * 应用拥有启动和物理退役票据，界面只等待票据。取消一次五秒等待不取消资源所有者。
 * 关闭先撤销启动资格和服务器授权，再派发释放；已知关闭错误不被另一个 provider 等待遮住。
 * 构造时不启动任务、监听或身份读写，返回前台也不自动开启会话。
 */
class LocalTransferReceiver(
    val server: LocalTransferServer,
    private val io: LocalProviderIo,
    private val prepare: suspend () -> Unit,
    private val retireUploads: suspend () -> Unit,
    private val identity: suspend () -> TransferTlsIdentity = { AndroidTransferIdentity.loadOrCreate(io) },
    scope: CoroutineScope? = null,
) {
    private val owner = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var opening: Opening? = null
    private var cleanup: Deferred<Unit>? = null
    private var closing: Deferred<Unit>? = null
    private val mutableState = MutableStateFlow(ReceiverControlState())
    val state = mutableState.asStateFlow()

    private class Opening {
        val revoked = AtomicBoolean(false)
        lateinit var receipt: Deferred<LocalTransferHandle>
        var phaseWatch: Job? = null
    }

    fun open(address: LocalTransferAddress): Deferred<LocalTransferHandle> {
        if (!receiverLocalAddress(address.address)) throw TransferException(TransferIssue.INVALID_INPUT)
        val task = Opening()
        val receipt = synchronized(lock) {
            if (opening != null || closing != null || cleanup != null ||
                server.phase.value != TransferServerPhase.OFF) throw TransferException(TransferIssue.BUSY)
            owner.async(start = CoroutineStart.LAZY) {
                prepare()
                currentCoroutineContext().ensureActive()
                if (task.revoked.get()) throw TransferException(TransferIssue.CLOSED)
                val preparedIdentity = identity()
                currentCoroutineContext().ensureActive()
                if (task.revoked.get()) throw TransferException(TransferIssue.CLOSED)
                val handle = server.start(preparedIdentity, address.address, 0) { !task.revoked.get() }
                synchronized(lock) {
                    if (opening === task && !task.revoked.get() && closing == null) {
                        mutableState.value = ReceiverControlState(ReceiverControlPhase.RECEIVING, address, handle)
                    }
                }
                val watch = owner.launch(start = CoroutineStart.LAZY) {
                    server.phase.first { it != TransferServerPhase.LISTENING }
                    if (!task.revoked.get()) close()
                }
                val retained = synchronized(lock) {
                    if (opening !== task || task.revoked.get() || closing != null) false else {
                        task.phaseWatch = watch
                        true
                    }
                }
                if (retained) watch.start() else watch.cancel()
                handle
            }.also { admitted ->
                task.receipt = admitted
                opening = task
                mutableState.value = ReceiverControlState(ReceiverControlPhase.PREPARING, address)
            }
        }
        receipt.invokeOnCompletion { failure ->
            if (failure == null || task.revoked.get()) return@invokeOnCompletion
            val issue = (failure as? TransferException)?.issue ?: TransferIssue.STORAGE_UNAVAILABLE
            val needsClose = synchronized(lock) {
                if (opening !== task || closing != null) false else {
                    mutableState.value = mutableState.value.copy(issue = issue)
                    true
                }
            }
            if (needsClose) close()
        }
        receipt.start()
        return receipt
    }

    /** 可从隐藏/锁屏回调调用：此处只撤销内存资格并派发既有关闭所有者。 */
    fun close(): Deferred<Unit> = beginClose(retry = false)

    /** 明确恢复才重试已失败票据；仍在工作的 provider/关闭尝试保持同一所有者。 */
    fun retryClose(): Deferred<Unit> = beginClose(retry = true)

    private fun beginClose(retry: Boolean): Deferred<Unit> {
        val task = synchronized(lock) {
            val previous = closing
            if (previous != null && (!retry || !previous.isCompleted || !previous.isCancelled)) return previous
            if (opening == null && cleanup == null && server.phase.value == TransferServerPhase.OFF) {
                return CompletableDeferred(Unit)
            }
            val currentOpening = opening
            currentOpening?.revoked?.set(true)
            mutableState.value = mutableState.value.copy(phase = ReceiverControlPhase.CLOSING,
                address = null, handle = null)
            owner.async(start = CoroutineStart.LAZY) {
                val serverReceipt = if (retry && server.phase.value == TransferServerPhase.RECOVERY_REQUIRED) {
                    owner.async(start = CoroutineStart.LAZY) { server.retryRetirement() }.also { it.start() }
                } else server.stop()
                val cleanupReceipt = cleanupReceipt(currentOpening, retry)
                try {
                    // 独立应用票据不因 awaitAll 的首个错误取消；阻塞方继续持有自己的资源。
                    listOf(serverReceipt, cleanupReceipt).awaitAll()
                    server.stop().await()
                    synchronized(lock) {
                        opening = null
                        cleanup = null
                        closing = null
                        mutableState.value = ReceiverControlState(issue = if (retry) null else mutableState.value.issue)
                    }
                } catch (cancelled: CancellationException) {
                    synchronized(lock) { mutableState.value = mutableState.value.copy(
                        phase = ReceiverControlPhase.RECOVERY_REQUIRED, issue = TransferIssue.RECOVERY_REQUIRED) }
                    throw cancelled
                } catch (_: Exception) {
                    synchronized(lock) { mutableState.value = mutableState.value.copy(
                        phase = ReceiverControlPhase.RECOVERY_REQUIRED, issue = TransferIssue.RECOVERY_REQUIRED) }
                    throw TransferException(TransferIssue.RECOVERY_REQUIRED)
                }
            }.also { closing = it }
        }
        // 资格已经失效。stop 的同步部分撤销 permit，物理 socket close 由 server 自己派发。
        server.stop()
        task.start()
        return task
    }

    private fun cleanupReceipt(currentOpening: Opening?, retry: Boolean): Deferred<Unit> {
        val receipt = synchronized(lock) {
            val previous = cleanup
            if (previous != null && (!retry || !previous.isCompleted || !previous.isCancelled)) return previous
            owner.async(start = CoroutineStart.LAZY) {
                // Job 取消可能同步触发 provider 的 CancellationSignal，故不在界面线程执行。
                currentOpening?.receipt?.cancel()
                currentOpening?.receipt?.join()
                currentOpening?.phaseWatch?.cancel()
                currentOpening?.phaseWatch?.join()
                io.awaitIdle()
                retireUploads()
                io.awaitIdle()
            }.also { cleanup = it }
        }
        receipt.start()
        return receipt
    }
}
