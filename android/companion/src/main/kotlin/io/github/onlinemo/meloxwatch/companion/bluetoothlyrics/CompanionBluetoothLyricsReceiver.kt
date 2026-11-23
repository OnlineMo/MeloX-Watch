package io.github.onlinemo.meloxwatch.companion.bluetoothlyrics

import android.content.Context
import androidx.annotation.MainThread
import io.github.onlinemo.meloxwatch.data.bluetoothlyrics.BluetoothLyricsIssue
import io.github.onlinemo.meloxwatch.data.bluetoothlyrics.BluetoothLyricsComparison
import io.github.onlinemo.meloxwatch.data.bluetoothlyrics.BluetoothLyricsPhase
import io.github.onlinemo.meloxwatch.data.bluetoothlyrics.BluetoothLyricsRole
import io.github.onlinemo.meloxwatch.data.bluetoothlyrics.BluetoothLyricsSessionCoordinator
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A finite connected-device service owns reception; neither Activity nor receiver owns a player. */
@MainThread
class CompanionBluetoothLyricsReceiver(context: Context) {
    private val foreground = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val sessions = BluetoothLyricsSessionCoordinator(context.applicationContext, BluetoothLyricsRole.PHONE,
        scope, foreground::get)
    private var serviceOwner: Any? = null
    private var presentation: Presentation? = null
    private val mutableIssue = MutableStateFlow<BluetoothLyricsIssue?>(null)
    val issue = mutableIssue.asStateFlow()

    fun bindPresentation(): Presentation {
        presentation?.close()
        return Presentation().also { presentation = it }
    }

    inner class Presentation internal constructor() : AutoCloseable {
        private var open = true
        val isCurrent: Boolean get() = open && presentation === this
        fun visible(value: Boolean) { if (isCurrent) sessions.uiVisible(value) }
        fun approve(comparison: BluetoothLyricsComparison): Boolean =
            isCurrent && sessions.approve(comparison)
        fun reject() { if (isCurrent) sessions.reject() }
        override fun close() {
            if (!open) return
            open = false
            if (presentation === this) {
                presentation = null
                sessions.uiVisible(false)
            }
        }
    }

    fun attachService(owner: Any): Boolean {
        if (serviceOwner === owner && foreground.get()) return true
        if (serviceOwner != null && serviceOwner !== owner) return false
        serviceOwner = owner
        foreground.set(true)
        mutableIssue.value = null
        if (sessions.listen()) return true
        foreground.set(false)
        serviceOwner = null
        mutableIssue.value = when {
            sessions.state.value.retirementRequired -> BluetoothLyricsIssue.RETIREMENT_REQUIRED
            sessions.state.value.phase != BluetoothLyricsPhase.OFF -> BluetoothLyricsIssue.BUSY
            else -> BluetoothLyricsIssue.CLOSED
        }
        return false
    }

    fun detachService(owner: Any) {
        if (serviceOwner !== owner) return
        foreground.set(false)
        sessions.stop()
        serviceOwner = null
    }

    fun startDenied(issue: BluetoothLyricsIssue) {
        mutableIssue.value = issue
        foreground.set(false)
        sessions.stop()
        serviceOwner = null
    }
}
