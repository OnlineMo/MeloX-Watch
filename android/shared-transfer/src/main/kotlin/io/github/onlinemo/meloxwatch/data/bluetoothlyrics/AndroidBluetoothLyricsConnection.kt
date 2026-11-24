package io.github.onlinemo.meloxwatch.data.bluetoothlyrics

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** A live OS handle and label remain local; no address/string credential is persisted or exported. */
class BluetoothLyricsTarget internal constructor(
    internal val device: BluetoothDevice,
    val label: String,
) {
    override fun toString(): String = "<selected-local-bluetooth-device>"
}

class BluetoothLyricsTargets(val entries: List<BluetoothLyricsTarget>, val nextOffset: Int?) {
    override fun toString(): String = "<local-bonded-selection-page>"
}

object BluetoothLyricsPlatform {
    fun needsPermission(): Boolean = Build.VERSION.SDK_INT >= 31
    fun hasPermission(context: Context): Boolean = try {
        ContextCompat.checkSelfPermission(context,
            if (needsPermission()) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH) ==
            PackageManager.PERMISSION_GRANTED
    } catch (_: RuntimeException) { false }

    /** The public SDK returns a bonded set; only one 32-row selection page is retained here. */
    @SuppressLint("MissingPermission")
    suspend fun targets(context: Context, offset: Int = 0): BluetoothLyricsTargets = withContext(Dispatchers.IO) {
        require(offset >= 0)
        val adapter = adapter(context)
        val selected = adapter.bondedDevices.asSequence().drop(offset).take(33).toList()
        currentCoroutineContext().ensureActive()
        val rows = selected.take(32).map { device ->
            BluetoothLyricsTarget(device, BluetoothLyricsText.clip(device.name.orEmpty(), 256).value)
        }
        BluetoothLyricsTargets(rows, if (selected.size > 32) Math.addExact(offset, 32) else null)
    }

    @SuppressLint("MissingPermission")
    internal fun adapter(context: Context): BluetoothAdapter {
        requirePermission(context)
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) {
            throw BluetoothLyricsException(BluetoothLyricsIssue.UNAVAILABLE)
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw BluetoothLyricsException(BluetoothLyricsIssue.UNAVAILABLE)
        if (!adapter.isEnabled) throw BluetoothLyricsException(BluetoothLyricsIssue.BLUETOOTH_OFF)
        return adapter
    }

    internal fun requirePermission(context: Context) {
        if (!hasPermission(context)) throw BluetoothLyricsException(BluetoothLyricsIssue.PERMISSION_DENIED)
    }
}

/**
 * One secure RFCOMM connection owns at most two physical jobs and a separate native closer.
 * Cancellation of a waiter never releases a still-blocked worker or admits a replacement socket.
 */
@SuppressLint("MissingPermission") // Every query/connect/physical operation rechecks the actual grant.
internal class AndroidBluetoothLyricsConnection(
    context: Context,
    val role: BluetoothLyricsRole,
    private val authorized: () -> Boolean = { true },
) : BluetoothLyricsTransport {
    private val context = context.applicationContext
    private val lock = Any()
    private val closing = AtomicBoolean(false)
    private val retired = AtomicBoolean(false)
    private val workers = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2))
    private val closer = ScheduledThreadPoolExecutor(1).apply {
        removeOnCancelPolicy = true
        executeExistingDelayedTasksAfterShutdownPolicy = false
        continueExistingPeriodicTasksAfterShutdownPolicy = false
    }
    private val closeScope = CoroutineScope(SupervisorJob() + closer.asCoroutineDispatcher())
    private val active = linkedSetOf<Job>()
    private var socket: BluetoothSocket? = null
    private var server: BluetoothServerSocket? = null
    private var wire: BluetoothLyricsWire? = null
    private var opening = false
    private var receiverRegistered = false
    private var retirementAttempt: Deferred<Unit>? = null
    private val firstRetirement = CompletableDeferred<Unit>()
    private val closeStarted = CompletableDeferred<BluetoothLyricsIssue>()
    private var sessionDeadline = 0L
    private var lifetimeTask: ScheduledFuture<*>? = null
    private var receivedWindowAt = 0L
    private var receivedInWindow = 0
    @Volatile override var peerLabel: String = ""
        private set
    override val physicallyRetired: Boolean get() = retired.get()
    override val closed: Boolean get() = closing.get()
    override val authenticated: Boolean get() = !closed && wire?.authenticated == true
    override val comparison: BluetoothLyricsComparison? get() = wire?.comparison
    @Volatile override var closureIssue: BluetoothLyricsIssue? = null
        private set

    private val disconnects = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (closing.get()) return
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED &&
                intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) != BluetoothAdapter.STATE_ON) {
                close(BluetoothLyricsIssue.BLUETOOTH_OFF)
            } else if (intent.action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
                val device = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                }
                val current = synchronized(lock) { socket?.remoteDevice }
                if (device != null && current == device) close(BluetoothLyricsIssue.CONNECTION_LOST)
            }
        }
    }

    override suspend fun openWatch(target: BluetoothLyricsTarget) {
        if (role != BluetoothLyricsRole.WATCH) fail(BluetoothLyricsIssue.WRONG_PEER)
        open(BluetoothLyricsLimits.CONNECT_MS) {
            BluetoothLyricsPlatform.adapter(context)
            if (target.device.bondState != BluetoothDevice.BOND_BONDED) fail(BluetoothLyricsIssue.NOT_BONDED)
            val created = target.device.createRfcommSocketToServiceRecord(BluetoothLyricsLimits.SERVICE_UUID)
            synchronized(lock) { socket = created }
            checkActive()
            created.connect()
            prepareWire(created)
        }
    }

    override suspend fun acceptPhone() {
        if (role != BluetoothLyricsRole.PHONE) fail(BluetoothLyricsIssue.WRONG_PEER)
        open(BluetoothLyricsLimits.COMPARE_MS) {
            val adapter = BluetoothLyricsPlatform.adapter(context)
            val listening = adapter.listenUsingRfcommWithServiceRecord("MeloX lyrics", BluetoothLyricsLimits.SERVICE_UUID)
            synchronized(lock) { server = listening }
            checkActive()
            val accepted = listening.accept(BluetoothLyricsLimits.COMPARE_MS.toInt())
            synchronized(lock) { socket = accepted }
            checkActive()
            // Admission is one peer/attempt. Stop advertising before authenticating or exposing data.
            listening.close()
            synchronized(lock) { if (server === listening) server = null }
            if (accepted.remoteDevice.bondState != BluetoothDevice.BOND_BONDED) fail(BluetoothLyricsIssue.NOT_BONDED)
            prepareWire(accepted)
        }
    }

    private suspend fun open(durationMs: Long, action: () -> Unit) {
        synchronized(lock) {
            if (opening || wire != null || closing.get()) fail(BluetoothLyricsIssue.BUSY)
            opening = true
        }
        try {
            physical(durationMs) {
                val filter = IntentFilter().apply {
                    addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                    addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                }
                ContextCompat.registerReceiver(context, disconnects, filter, ContextCompat.RECEIVER_EXPORTED)
                synchronized(lock) { receiverRegistered = true }
                checkActive()
                action()
            }
        } finally { synchronized(lock) { opening = false } }
    }

    private fun prepareWire(connected: BluetoothSocket) {
        checkActive()
        val label = BluetoothLyricsText.clip(connected.remoteDevice.name.orEmpty(), 256).value
        synchronized(lock) { if (!closing.get()) peerLabel = label }
        val deadline = closer.schedule({ close(BluetoothLyricsIssue.EXPIRED) }, BluetoothLyricsLimits.CONNECT_MS, TimeUnit.MILLISECONDS)
        try {
            val handshake = BluetoothLyricsHandshake.create(role)
            try {
                val input = BufferedInputStream(connected.inputStream, BluetoothLyricsLimits.FRAME_BYTES)
                val output = BufferedOutputStream(connected.outputStream, BluetoothLyricsLimits.FRAME_BYTES)
                val established = BluetoothLyricsWire.establish(input, output, handshake)
                synchronized(lock) {
                    wire = established
                    if (closing.get()) established.close()
                }
                checkActive()
            } finally { handshake.close() }
        } finally { deadline.cancel(false) }
    }

    /** Caller checks the same local comparison ticket and passes its remaining absolute deadline. */
    override suspend fun confirm(remainingMs: Long) {
        require(remainingMs in 1..BluetoothLyricsLimits.COMPARE_MS)
        physical(remainingMs) {
            val established = checkNotNull(wire)
            established.confirmLocal()
            established.readPeerConfirmation()
            if (!established.authenticated) fail(BluetoothLyricsIssue.NOT_CONFIRMED)
            established.comparison.close()
            synchronized(lock) {
                sessionDeadline = Math.addExact(now(), BluetoothLyricsLimits.SESSION_MS)
                lifetimeTask = closer.schedule({ close(BluetoothLyricsIssue.EXPIRED) }, BluetoothLyricsLimits.SESSION_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    override suspend fun write(value: BluetoothLyricsPayload) = physical(BluetoothLyricsLimits.WRITE_MS) {
        if (!authenticated) fail(BluetoothLyricsIssue.NOT_CONFIRMED)
        checkNotNull(wire).writeSnapshot(value)
    }

    override suspend fun heartbeat() = physical(BluetoothLyricsLimits.WRITE_MS) {
        if (!authenticated) fail(BluetoothLyricsIssue.NOT_CONFIRMED)
        checkNotNull(wire).writeHeartbeat()
    }

    override suspend fun read(): BluetoothLyricsMessage = physical(BluetoothLyricsLimits.SILENCE_MS) {
        if (!authenticated) fail(BluetoothLyricsIssue.NOT_CONFIRMED)
        val message = checkNotNull(wire).readMessage()
        val observed = now()
        if (observed - receivedWindowAt >= 1000L) {
            receivedWindowAt = observed
            receivedInWindow = 0
        }
        if (++receivedInWindow > BluetoothLyricsLimits.RECEIVED_FRAMES_PER_SECOND) fail(BluetoothLyricsIssue.RATE_LIMITED)
        message
    }

    private suspend fun <T> physical(durationMs: Long, action: () -> T): T {
        currentCoroutineContext().ensureActive()
        val work: Deferred<T> = synchronized(lock) {
            if (closing.get() || active.size >= 2) fail(BluetoothLyricsIssue.BUSY)
            workers.async(start = CoroutineStart.LAZY) {
                val deadline = closer.schedule({ close(BluetoothLyricsIssue.EXPIRED) }, durationMs, TimeUnit.MILLISECONDS)
                try {
                    checkActive()
                    action().also { checkActive() }
                } finally { deadline.cancel(false) }
            }.also { owned ->
                active += owned
                owned.invokeOnCompletion { synchronized(lock) { active.remove(owned) } }
            }
        }
        work.start()
        try { return work.await() }
        catch (error: CancellationException) { close(); throw error }
        catch (error: BluetoothLyricsException) { close(error.issue); throw error }
        catch (_: SecurityException) { close(BluetoothLyricsIssue.PERMISSION_DENIED); fail(BluetoothLyricsIssue.PERMISSION_DENIED) }
        catch (_: Exception) { close(BluetoothLyricsIssue.CONNECTION_LOST); fail(BluetoothLyricsIssue.CONNECTION_LOST) }
        finally { if (!work.isCompleted) close() }
    }

    private fun checkActive() {
        if (closing.get()) fail(BluetoothLyricsIssue.CLOSED)
        if (!authorized()) fail(BluetoothLyricsIssue.CLOSED)
        BluetoothLyricsPlatform.requirePermission(context)
        val deadline = synchronized(lock) { sessionDeadline }
        if (deadline != 0L && now() >= deadline) fail(BluetoothLyricsIssue.EXPIRED)
    }

    override suspend fun awaitClosing(): BluetoothLyricsIssue = closeStarted.await()

    override fun close(issue: BluetoothLyricsIssue): Deferred<Unit> {
        val attempt = synchronized(lock) {
            if (!closing.compareAndSet(false, true)) return firstRetirement
            closureIssue = issue
            closeStarted.complete(issue)
            lifetimeTask?.cancel(false)
            lifetimeTask = null
            wire?.close()
            peerLabel = ""
            newRetirementAttempt().also { retirementAttempt = it }
        }
        // The original outcome is immutable. A later explicit retry gets its own real attempt.
        attempt.invokeOnCompletion { error ->
            if (error == null) firstRetirement.complete(Unit)
            else firstRetirement.completeExceptionally(BluetoothLyricsException(BluetoothLyricsIssue.RETIREMENT_REQUIRED))
        }
        attempt.start()
        return firstRetirement
    }

    override suspend fun awaitClosed(waitMs: Long) {
        try { withTimeout(waitMs) { close().await() } }
        catch (_: TimeoutCancellationException) { fail(BluetoothLyricsIssue.RETIREMENT_REQUIRED) }
    }

    override suspend fun retryRetirement(waitMs: Long) {
        val attempt = synchronized(lock) {
            if (retired.get()) return
            if (!closing.get()) fail(BluetoothLyricsIssue.BUSY)
            retirementAttempt?.takeIf { !it.isCompleted } ?: newRetirementAttempt().also { retirementAttempt = it }
        }
        attempt.start()
        try { withTimeout(waitMs) { attempt.await() } }
        catch (_: TimeoutCancellationException) { fail(BluetoothLyricsIssue.RETIREMENT_REQUIRED) }
    }

    private fun newRetirementAttempt(): Deferred<Unit> =
        closeScope.async(NonCancellable, start = CoroutineStart.LAZY) { retire() }

    private suspend fun retire() {
        val initial = synchronized(lock) { socket to server }
        closeHandles(initial.first, initial.second)
        val pending = synchronized(lock) { active.toList() }
        pending.forEach { it.join() }
        // A public socket factory may return after closure began; retain and close its late handle.
        val final = synchronized(lock) { socket to server }
        if (final.first !== initial.first || final.second !== initial.second) closeHandles(final.first, final.second)
        val registered = synchronized(lock) { receiverRegistered }
        if (registered) {
            try { context.unregisterReceiver(disconnects) }
            catch (_: Exception) { fail(BluetoothLyricsIssue.RETIREMENT_REQUIRED) }
        }
        synchronized(lock) {
            if (active.isNotEmpty()) fail(BluetoothLyricsIssue.RETIREMENT_REQUIRED)
            wire?.close()
            wire = null
            peerLabel = ""
            socket = null
            server = null
            receiverRegistered = false
            retired.set(true)
        }
        workers.cancel()
        closer.shutdown()
    }

    private fun closeHandles(current: BluetoothSocket?, listening: BluetoothServerSocket?) {
        var failed = false
        try { listening?.close() } catch (_: Exception) { failed = true }
        try { current?.close() } catch (_: Exception) { failed = true }
        if (failed) fail(BluetoothLyricsIssue.RETIREMENT_REQUIRED)
    }

    override fun toString(): String = "<owned-secure-rfcomm-connection>"
    private fun now(): Long = System.nanoTime() / 1_000_000L
    private fun fail(issue: BluetoothLyricsIssue): Nothing = throw BluetoothLyricsException(issue)
}
