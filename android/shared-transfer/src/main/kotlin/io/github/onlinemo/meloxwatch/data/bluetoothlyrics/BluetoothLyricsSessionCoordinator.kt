package io.github.onlinemo.meloxwatch.data.bluetoothlyrics

import android.content.Context
import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

enum class BluetoothLyricsPhase { OFF, CONNECTING, COMPARING, CONNECTED, CLOSING, FAILED }

/** Peer labels and comparisons are local UI state; serializers/diagnostics must not receive it. */
class BluetoothLyricsSessionState(
    val phase: BluetoothLyricsPhase = BluetoothLyricsPhase.OFF,
    val comparison: BluetoothLyricsComparison? = null,
    val peerLabel: String = "",
    val issue: BluetoothLyricsIssue? = null,
    val retirementRequired: Boolean = false,
) {
    override fun toString(): String = "<local-bluetooth-lyrics-session>"
}

/** One duplex session for the two concrete Android roles, with no automatic listen/reconnect. */
@MainThread
class BluetoothLyricsSessionCoordinator internal constructor(
    val role: BluetoothLyricsRole,
    private val scope: CoroutineScope,
    private val authorized: () -> Boolean,
    private val createConnection: () -> BluetoothLyricsTransport,
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    constructor(
        context: Context,
        role: BluetoothLyricsRole,
        scope: CoroutineScope,
        authorized: () -> Boolean = { true },
    ) : this(role, scope, authorized, { AndroidBluetoothLyricsConnection(context, role, authorized) })

    private class Session(val connection: BluetoothLyricsTransport) {
        val outgoing = Channel<BluetoothLyricsPayload>(Channel.CONFLATED)
        val decision = CompletableDeferred<Boolean>()
        var comparison: BluetoothLyricsComparison? = null
        var comparisonDeadline = 0L
        var job: Job? = null
        var source: BluetoothLyricsSource? = null
        var sourceClosing = false
        var sourceRetirementFailed = false
    }

    private val mutableState = MutableStateFlow(BluetoothLyricsSessionState())
    val state = mutableState.asStateFlow()
    private val mutablePayload = MutableStateFlow<BluetoothLyricsPayload?>(null)
    val payload = mutablePayload.asStateFlow()
    private var current: Session? = null
    private var visible = false

    fun uiVisible(value: Boolean) {
        visible = value
        if (!value && state.value.phase in setOf(BluetoothLyricsPhase.CONNECTING, BluetoothLyricsPhase.COMPARING)) stop()
    }

    fun connect(target: BluetoothLyricsTarget, onReady: () -> BluetoothLyricsSource): Boolean {
        if (role != BluetoothLyricsRole.WATCH) return false
        return start(target, onReady)
    }

    fun listen(): Boolean {
        if (role != BluetoothLyricsRole.PHONE) return false
        return start(null, null)
    }

    private fun start(target: BluetoothLyricsTarget?, onReady: (() -> BluetoothLyricsSource)?): Boolean {
        if (!visible || !authorized()) return false
        if (current != null) return false
        val session = Session(createConnection())
        current = session
        mutablePayload.value = null
        mutableState.value = BluetoothLyricsSessionState(BluetoothLyricsPhase.CONNECTING)
        session.job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var issue: BluetoothLyricsIssue? = null
            val ownerJob = checkNotNull(currentCoroutineContext()[Job])
            val closureObserver = launch {
                session.connection.awaitClosing()
                if (current === session) {
                    mutablePayload.value = null
                    ownerJob.cancel()
                }
            }
            try {
                if (role == BluetoothLyricsRole.WATCH) session.connection.openWatch(checkNotNull(target))
                else session.connection.acceptPhone()
                if (!isCurrent(session) || !visible) throw BluetoothLyricsException(BluetoothLyricsIssue.CLOSED)
                val comparison = session.connection.comparison
                    ?: throw BluetoothLyricsException(BluetoothLyricsIssue.AUTHENTICATION_FAILED)
                session.comparison = comparison
                session.comparisonDeadline = Math.addExact(now(), BluetoothLyricsLimits.COMPARE_MS)
                mutableState.value = BluetoothLyricsSessionState(BluetoothLyricsPhase.COMPARING, comparison,
                    session.connection.peerLabel)
                val approved = withTimeoutOrNull(BluetoothLyricsLimits.COMPARE_MS) { session.decision.await() }
                    ?: throw BluetoothLyricsException(BluetoothLyricsIssue.EXPIRED)
                if (!approved || !visible || !isCurrent(session)) throw BluetoothLyricsException(BluetoothLyricsIssue.NOT_CONFIRMED)
                val remaining = (session.comparisonDeadline - now()).coerceAtLeast(0L)
                if (remaining == 0L) throw BluetoothLyricsException(BluetoothLyricsIssue.EXPIRED)
                session.connection.confirm(remaining)
                if (!isCurrent(session) || !authorized()) throw BluetoothLyricsException(BluetoothLyricsIssue.CLOSED)
                session.source = onReady?.invoke()
                if (!isCurrent(session)) throw BluetoothLyricsException(BluetoothLyricsIssue.CLOSED)
                mutableState.value = BluetoothLyricsSessionState(BluetoothLyricsPhase.CONNECTED,
                    peerLabel = session.connection.peerLabel)
                coroutineScope {
                    launch {
                        while (isCurrent(session)) {
                            when (val message = session.connection.read()) {
                                is BluetoothLyricsMessage.Snapshot -> {
                                    if (role != BluetoothLyricsRole.PHONE) throw BluetoothLyricsException(BluetoothLyricsIssue.WRONG_PEER)
                                    if (isCurrent(session)) mutablePayload.value = message.payload
                                }
                                BluetoothLyricsMessage.Heartbeat -> Unit
                                BluetoothLyricsMessage.Closed -> throw BluetoothLyricsException(BluetoothLyricsIssue.CLOSED)
                            }
                        }
                    }
                    launch {
                        var lastWrite = 0L
                        while (isCurrent(session)) {
                            var pending = withTimeoutOrNull(BluetoothLyricsLimits.HEARTBEAT_MS) { session.outgoing.receive() }
                            val wait = (lastWrite + BluetoothLyricsLimits.MIN_WRITE_INTERVAL_MS - now()).coerceAtLeast(0L)
                            if (wait != 0L) delay(wait)
                            // One latest body replaces older queued text, including seek/track transitions.
                            session.outgoing.tryReceive().getOrNull()?.let { pending = it }
                            if (!isCurrent(session)) break
                            val body = pending
                            if (body == null) session.connection.heartbeat()
                            else session.connection.write(body)
                            lastWrite = now()
                        }
                    }
                }
            } catch (error: CancellationException) {
                issue = session.connection.closureIssue ?: BluetoothLyricsIssue.CLOSED
                throw error
            } catch (error: BluetoothLyricsException) {
                issue = session.connection.closureIssue?.takeUnless { it == BluetoothLyricsIssue.CLOSED } ?: error.issue
            } catch (_: Exception) {
                issue = BluetoothLyricsIssue.TRANSPORT_FAILED
            } finally {
                closureObserver.cancel()
                session.decision.cancel()
                session.outgoing.cancel()
                if (!session.sourceRetirementFailed) closeSource(session)
                if (session.sourceRetirementFailed) issue = BluetoothLyricsIssue.RETIREMENT_REQUIRED
                session.connection.close(issue ?: BluetoothLyricsIssue.CLOSED)
                if (current === session) {
                    mutablePayload.value = null
                    mutableState.value = BluetoothLyricsSessionState(BluetoothLyricsPhase.CLOSING)
                }
                var retirementFailed = false
                withContext(NonCancellable) {
                    try { finishSourceRetirement(session) }
                    catch (_: Exception) { retirementFailed = true }
                    try { session.connection.awaitClosed() }
                    catch (_: Exception) { retirementFailed = true }
                }
                if (current === session) {
                    if (session.sourceRetirementFailed || retirementFailed || !session.connection.physicallyRetired) {
                        mutableState.value = BluetoothLyricsSessionState(BluetoothLyricsPhase.FAILED,
                            issue = BluetoothLyricsIssue.RETIREMENT_REQUIRED, retirementRequired = true)
                    } else {
                        current = null
                        mutableState.value = BluetoothLyricsSessionState(
                            if (issue == null || issue == BluetoothLyricsIssue.CLOSED) BluetoothLyricsPhase.OFF else BluetoothLyricsPhase.FAILED,
                            issue = issue?.takeUnless { it == BluetoothLyricsIssue.CLOSED })
                    }
                }
            }
        }
        return true
    }

    fun approve(comparison: BluetoothLyricsComparison): Boolean {
        val session = current ?: return false
        if (!visible || !isCurrent(session) || state.value.phase != BluetoothLyricsPhase.COMPARING ||
            session.comparison !== comparison || now() >= session.comparisonDeadline) return false
        return session.decision.complete(true)
    }

    fun reject() { current?.decision?.complete(false); stop() }

    fun offer(value: BluetoothLyricsPayload) {
        val session = current ?: return
        if (role == BluetoothLyricsRole.WATCH && isCurrent(session) && session.connection.authenticated && authorized()) {
            session.outgoing.trySend(value)
        }
    }

    fun stop(issue: BluetoothLyricsIssue = BluetoothLyricsIssue.CLOSED) {
        val session = current ?: return
        mutablePayload.value = null
        mutableState.value = BluetoothLyricsSessionState(BluetoothLyricsPhase.CLOSING)
        session.decision.cancel()
        session.outgoing.cancel()
        if (!session.sourceRetirementFailed) closeSource(session)
        session.connection.close(issue)
        session.job?.cancel()
    }

    suspend fun awaitStopped() {
        val session = current ?: return
        stop()
        session.job?.join()
        if (session.source != null || session.sourceRetirementFailed || !session.connection.physicallyRetired) {
            throw BluetoothLyricsException(BluetoothLyricsIssue.RETIREMENT_REQUIRED)
        }
    }

    suspend fun retryRetirement() {
        val session = current ?: return
        if (session.sourceRetirementFailed) closeSource(session)
        finishSourceRetirement(session)
        session.connection.retryRetirement()
        session.job?.join()
        if (current === session && session.connection.physicallyRetired) {
            current = null
            mutablePayload.value = null
            mutableState.value = BluetoothLyricsSessionState()
        }
    }

    private fun closeSource(session: Session) {
        val source = session.source ?: return
        if (session.sourceClosing) return
        try {
            source.close()
            session.sourceClosing = true
            session.sourceRetirementFailed = false
        } catch (_: Exception) {
            session.sourceRetirementFailed = true
        }
    }

    private suspend fun finishSourceRetirement(session: Session) {
        val source = session.source ?: return
        if (!session.sourceClosing) throw BluetoothLyricsException(BluetoothLyricsIssue.RETIREMENT_REQUIRED)
        try {
            withTimeout(2500L) { source.awaitClosed() }
            session.source = null
            session.sourceRetirementFailed = false
        } catch (error: Exception) {
            session.sourceRetirementFailed = true
            throw error
        }
    }

    private fun isCurrent(session: Session): Boolean =
        current === session && !session.connection.closed && authorized()
    private fun now(): Long = monotonicMs()
}
