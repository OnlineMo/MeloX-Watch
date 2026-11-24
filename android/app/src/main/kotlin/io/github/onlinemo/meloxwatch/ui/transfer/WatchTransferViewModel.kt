package io.github.onlinemo.meloxwatch.ui.transfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.onlinemo.meloxwatch.data.themes.ReceivedThemeSummary
import io.github.onlinemo.meloxwatch.data.themes.ThemeException
import io.github.onlinemo.meloxwatch.data.themes.ThemeIssue
import io.github.onlinemo.meloxwatch.data.themes.TransferredThemeCoordinator
import io.github.onlinemo.meloxwatch.data.transfer.LocalTransferAddress
import io.github.onlinemo.meloxwatch.data.transfer.LocalTransferReceiver
import io.github.onlinemo.meloxwatch.data.transfer.ReceiverControlState
import io.github.onlinemo.meloxwatch.data.transfer.TransferCapability
import io.github.onlinemo.meloxwatch.data.transfer.TransferException
import io.github.onlinemo.meloxwatch.data.transfer.TransferFingerprint
import io.github.onlinemo.meloxwatch.data.transfer.TransferIssue
import io.github.onlinemo.meloxwatch.data.transfer.TransferLocalSessionState
import io.github.onlinemo.meloxwatch.data.transfer.TransferServerPhase
import io.github.onlinemo.meloxwatch.data.transfer.TransferUploadStatus
import io.github.onlinemo.meloxwatch.data.transfer.localTransferAddresses
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class TransferUiNotice { NO_ADDRESS, ADDRESS_FAILED, WAITING_FOR_OWNER }

data class WatchTransferState(
    val visible: Boolean = false,
    val addresses: List<LocalTransferAddress> = emptyList(),
    val selectedAddress: LocalTransferAddress? = null,
    val readingAddresses: Boolean = false,
    val controlPending: Boolean = false,
    val control: ReceiverControlState = ReceiverControlState(),
    val serverPhase: TransferServerPhase = TransferServerPhase.OFF,
    val session: TransferLocalSessionState,
    val selectedCapabilities: Set<TransferCapability> = emptySet(),
    val connections: Int = 0,
    val upload: TransferUploadStatus? = null,
    val pairingCode: String? = null,
    val remainingMs: Long = 0L,
    val received: ReceivedThemeSummary? = null,
    val themeIssue: ThemeIssue? = null,
    val issue: TransferIssue? = null,
    val notice: TransferUiNotice? = null,
) {
    override fun toString(): String = "<local-transfer-page-state>"
}

/**
 * 真实 Activity/window/page 绑定控制可见性，旧界面不能再次开启监听。
 * 页面不保存地址、验证码或指纹。隐藏取消 UI 等待和观察，应用所有者继续完成物理关闭。
 */
class WatchTransferViewModel(
    private val receiver: LocalTransferReceiver,
    private val themes: TransferredThemeCoordinator,
) : ViewModel() {
    private val mutableState = MutableStateFlow(WatchTransferState(session = receiver.server.sessionState.value))
    val state = mutableState.asStateFlow()
    private var binding: Presentation? = null
    private var observation: Job? = null
    private var addressRead: Job? = null
    private var controlWait: Job? = null
    private var themeRead: Job? = null
    private var closed = false

    /** 绑定只读取窗口/路由内存，不执行 I/O；销毁释放其闭包。 */
    fun bindPresentation(readVisible: () -> Boolean): AutoCloseable {
        binding?.close()
        return Presentation(readVisible).also {
            binding = it
            visibilityChanged()
        }
    }

    private inner class Presentation(private var readVisible: (() -> Boolean)?) : AutoCloseable {
        val visible: Boolean get() = !closed && binding === this && readVisible?.invoke() == true
        override fun close() {
            if (readVisible == null) return
            readVisible = null
            if (binding !== this) return
            binding = null
            hide()
        }
    }

    fun visibilityChanged() {
        val visible = binding?.visible == true
        if (mutableState.value.visible == visible) return
        if (!visible) hide() else {
            mutableState.value = mutableState.value.copy(visible = true)
            observe()
            refreshReceivedTheme()
        }
    }

    private fun visible(): Boolean = !closed && binding?.visible == true && mutableState.value.visible

    private fun hide() {
        observation?.cancel()
        observation = null
        addressRead?.cancel()
        addressRead = null
        controlWait?.cancel()
        controlWait = null
        themeRead?.cancel()
        themeRead = null
        receiver.close()
        mutableState.value = mutableState.value.copy(visible = false, addresses = emptyList(),
            selectedAddress = null, selectedCapabilities = emptySet(), readingAddresses = false,
            control = receiver.state.value, session = receiver.server.sessionState.value,
            controlPending = false, pairingCode = null, remainingMs = 0L)
    }

    private fun observe() {
        observation?.cancel()
        observation = viewModelScope.launch {
            coroutineScope {
                launch { receiver.state.collect { control ->
                    if (visible()) {
                        val current = mutableState.value
                        val notice = if (control.phase !in setOf(
                            io.github.onlinemo.meloxwatch.data.transfer.ReceiverControlPhase.PREPARING,
                            io.github.onlinemo.meloxwatch.data.transfer.ReceiverControlPhase.CLOSING) &&
                            current.notice == TransferUiNotice.WAITING_FOR_OWNER) null else current.notice
                        mutableState.value = current.copy(control = control, notice = notice)
                    }
                } }
                launch { receiver.server.phase.collect { phase ->
                    if (visible()) mutableState.value = mutableState.value.copy(serverPhase = phase)
                } }
                launch { receiver.server.sessionState.collect { session ->
                    if (!visible()) return@collect
                    val old = mutableState.value
                    val selected = if (old.session.pending !== session.pending) emptySet()
                        else old.selectedCapabilities.intersect(session.pending?.requested.orEmpty())
                    mutableState.value = old.copy(session = session, selectedCapabilities = selected,
                        pairingCode = receiver.server.pairingCodeForLocalUi(), remainingMs = receiver.server.remainingMs())
                } }
                launch { receiver.server.connectionCount.collect { count ->
                    if (visible()) mutableState.value = mutableState.value.copy(connections = count)
                } }
                launch { receiver.server.uploadProgress.collect { upload ->
                    if (visible()) mutableState.value = mutableState.value.copy(upload = upload)
                } }
                launch { themes.pending.collect { received ->
                    if (visible()) mutableState.value = mutableState.value.copy(received = received)
                } }
                launch {
                    receiver.server.sessionState.collectLatest { session ->
                        if (!session.active) return@collectLatest
                        while (isActive) {
                            if (!visible()) return@collectLatest
                            mutableState.value = mutableState.value.copy(pairingCode = receiver.server.pairingCodeForLocalUi(),
                                remainingMs = receiver.server.remainingMs())
                            delay(1000L)
                        }
                    }
                }
            }
        }
    }

    fun refreshAddresses() {
        if (!visible() || addressRead != null) return
        mutableState.value = mutableState.value.copy(readingAddresses = true, notice = null)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val addresses = localTransferAddresses()
                if (visible()) mutableState.value = mutableState.value.copy(addresses = addresses,
                    selectedAddress = null, notice = if (addresses.isEmpty()) TransferUiNotice.NO_ADDRESS else null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (visible()) mutableState.value = mutableState.value.copy(notice = TransferUiNotice.ADDRESS_FAILED)
            }
        }
        addressRead = job
        job.invokeOnCompletion {
            viewModelScope.launch {
                if (addressRead === job) {
                    addressRead = null
                    mutableState.value = mutableState.value.copy(readingAddresses = false)
                }
            }
        }
        job.start()
    }

    fun selectAddress(address: LocalTransferAddress) {
        if (!visible() || address !in mutableState.value.addresses || controlWait != null ||
            receiver.server.phase.value != TransferServerPhase.OFF) return
        mutableState.value = mutableState.value.copy(selectedAddress = address, notice = null)
    }

    fun open() {
        if (!visible()) return
        val selected = mutableState.value.selectedAddress ?: return
        try { waitForOwner(receiver.open(selected)) }
        catch (error: TransferException) { mutableState.value = mutableState.value.copy(issue = error.issue) }
    }

    fun closeReceiver() {
        if (!visible()) return
        waitForOwner(receiver.close(), replace = true)
    }

    fun retryRetirement() {
        if (!visible()) return
        waitForOwner(receiver.retryClose(), replace = true)
    }

    private fun waitForOwner(receipt: Deferred<*>, replace: Boolean = false) {
        if (controlWait != null && !replace) return
        if (replace) {
            controlWait?.cancel()
            controlWait = null
        }
        mutableState.value = mutableState.value.copy(controlPending = true, issue = null, notice = null)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val completed = withTimeoutOrNull(CONTROL_WAIT_MS) { receipt.await()
                    true } ?: false
                if (visible() && !completed) mutableState.value = mutableState.value.copy(notice = TransferUiNotice.WAITING_FOR_OWNER)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: TransferException) {
                if (visible()) mutableState.value = mutableState.value.copy(issue = error.issue)
            } catch (_: Exception) {
                if (visible()) mutableState.value = mutableState.value.copy(issue = TransferIssue.RECOVERY_REQUIRED)
            }
        }
        controlWait = job
        job.invokeOnCompletion {
            viewModelScope.launch {
                if (controlWait === job) {
                    controlWait = null
                    mutableState.value = mutableState.value.copy(controlPending = false)
                }
            }
        }
        job.start()
    }

    fun chooseCapability(peer: TransferFingerprint, capability: TransferCapability, enabled: Boolean) {
        if (!visible()) return
        val pending = receiver.server.sessionState.value.pending ?: return
        if (pending.fingerprint != peer || capability !in pending.requested) return
        val old = mutableState.value
        val selected = if (enabled) old.selectedCapabilities + capability else old.selectedCapabilities - capability
        mutableState.value = old.copy(selectedCapabilities = selected.toSet())
    }

    fun approve(peer: TransferFingerprint) = localControl {
        val pending = receiver.server.sessionState.value.pending ?: throw TransferException(TransferIssue.NOT_FOUND)
        if (pending.fingerprint != peer) throw TransferException(TransferIssue.WRONG_TARGET)
        receiver.server.approveForLocalUi(peer, mutableState.value.selectedCapabilities.toSet())
    }

    fun revoke(peer: TransferFingerprint) = localControl { receiver.server.revokeForLocalUi(peer) }
    fun extend() = localControl { receiver.server.extendForLocalUi() }

    private fun localControl(action: () -> Unit) {
        if (!visible()) return
        try {
            action()
            mutableState.value = mutableState.value.copy(issue = null)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: TransferException) { mutableState.value = mutableState.value.copy(issue = error.issue) }
        catch (_: Exception) { mutableState.value = mutableState.value.copy(issue = TransferIssue.TRANSPORT_FAILED) }
    }

    fun refreshReceivedTheme() {
        if (!visible() || themeRead != null) return
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val received = themes.load()
                if (visible()) mutableState.value = mutableState.value.copy(received = received, themeIssue = null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: ThemeException) {
                if (visible()) mutableState.value = mutableState.value.copy(themeIssue = error.issue)
            } catch (_: Exception) {
                if (visible()) mutableState.value = mutableState.value.copy(themeIssue = ThemeIssue.RECOVERY_REQUIRED)
            }
        }
        themeRead = job
        job.invokeOnCompletion { viewModelScope.launch { if (themeRead === job) themeRead = null } }
        job.start()
    }

    override fun onCleared() {
        closed = true
        binding?.close()
        binding = null
        hide()
        super.onCleared()
    }

    private companion object { const val CONTROL_WAIT_MS = 5000L }
}
