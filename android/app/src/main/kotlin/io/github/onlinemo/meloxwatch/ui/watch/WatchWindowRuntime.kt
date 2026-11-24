package io.github.onlinemo.meloxwatch.ui.watch

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.Player
import io.github.onlinemo.meloxwatch.ui.ProbePlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class WatchWindowVisibility(
    val resumed: Boolean = false,
    val focused: Boolean = false,
    val interactive: Boolean = false,
    val unlocked: Boolean = false,
) {
    val interactiveAndUnlocked: Boolean get() = interactive && unlocked
    val visible: Boolean get() = resumed && focused && interactiveAndUnlocked
}

/** A paused, valid item can still show lyrics; an ended or disconnected item cannot hold the screen. */
internal fun lyricsKeepBrightRequested(
    savedPreference: Boolean,
    selectedLyricsPage: Boolean,
    playback: ProbePlaybackState,
): Boolean = savedPreference && selectedLyricsPage && playback.isConnected && playback.hasMedia &&
    !playback.mediaId.isNullOrBlank() && !playback.hasError &&
    (playback.playbackState == Player.STATE_READY || playback.playbackState == Player.STATE_BUFFERING)

/**
 * One Activity owns this Window flag and its current lyrics lease. System/lifecycle callbacks clear
 * it synchronously, so a hidden composition or a delayed media/UI tick cannot keep the screen awake.
 */
@MainThread
internal class WatchWindowRuntime(
    private val activity: ComponentActivity,
    private val onVisibilityChanged: (WatchWindowVisibility) -> Unit = {},
) : AutoCloseable {
    private val power = activity.getSystemService(PowerManager::class.java)
    private val keyguard = activity.getSystemService(KeyguardManager::class.java)
    private val mutableVisibility = MutableStateFlow(WatchWindowVisibility())
    val visibility = mutableVisibility.asStateFlow()
    private var resumed = false
    private var focused = false
    private var interactive = false
    private var unlocked = false
    private var receiverRegistered = false
    private var closed = false
    private var activeLyricsLease: Any? = null
    private var lyricsRequested = false
    private var holdingFlag = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (closed || !receiverRegistered) return
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF, Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> refreshDeviceState()
            }
        }
    }
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        if (!closed) when (event) {
            Lifecycle.Event.ON_START -> registerScreenReceiver()
            Lifecycle.Event.ON_RESUME -> {
                resumed = true
                focused = activity.window.decorView.hasWindowFocus()
                refreshDeviceState()
            }
            Lifecycle.Event.ON_PAUSE -> {
                resumed = false
                publish()
            }
            Lifecycle.Event.ON_STOP -> {
                resumed = false
                focused = false
                publish()
                unregisterScreenReceiver()
            }
            Lifecycle.Event.ON_DESTROY -> close()
            else -> Unit
        }
    }

    init {
        activity.lifecycle.addObserver(lifecycleObserver)
    }

    /** MainActivity forwards the actual callback; no polling or guessed focus is used. */
    fun windowFocusChanged(hasFocus: Boolean) {
        if (closed) return
        focused = hasFocus
        if (!hasFocus) publish()
        refreshDeviceState()
    }

    fun acquireLyricsLease(): LyricsLease {
        check(!closed) { "The Window owner has been destroyed" }
        val token = Any()
        activeLyricsLease = token
        lyricsRequested = false
        publish()
        return LyricsLease(this, token)
    }

    private fun updateLyricsRequest(token: Any, requested: Boolean) {
        if (closed || activeLyricsLease !== token) return
        lyricsRequested = requested
        if (!requested) publish()
        refreshDeviceState()
    }

    private fun releaseLyricsLease(token: Any) {
        if (closed || activeLyricsLease !== token) return
        activeLyricsLease = null
        lyricsRequested = false
        publish()
    }

    private fun registerScreenReceiver() {
        if (receiverRegistered || closed) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // All three actions are protected system broadcasts. USER_PRESENT can come from SystemUI,
        // whose UID differs from system_server; the callback only re-reads actual device state.
        ContextCompat.registerReceiver(activity, screenReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
        refreshDeviceState()
    }

    private fun unregisterScreenReceiver() {
        if (!receiverRegistered) return
        receiverRegistered = false
        activity.unregisterReceiver(screenReceiver)
    }

    private fun refreshDeviceState() {
        try {
            val actualInteractive = power?.isInteractive == true
            val actualUnlocked = keyguard?.isKeyguardLocked == false && keyguard?.isDeviceLocked == false
            interactive = actualInteractive
            unlocked = actualUnlocked
        } catch (_: RuntimeException) {
            // Binder/service failure cannot leave a previously held flag or claim device visibility.
            interactive = false
            unlocked = false
        }
        publish()
    }

    private fun publish() {
        val snapshot = WatchWindowVisibility(
            resumed = resumed && !closed,
            focused = focused && !closed,
            interactive = interactive && !closed,
            unlocked = unlocked && !closed,
        )
        val requested = snapshot.visible && activeLyricsLease != null && lyricsRequested
        if (requested != holdingFlag) {
            if (requested) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            holdingFlag = requested
        }
        if (mutableVisibility.value != snapshot) {
            mutableVisibility.value = snapshot
            // The sole Window source retires memory before returning from focus/system callbacks.
            onVisibilityChanged(snapshot)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        activeLyricsLease = null
        lyricsRequested = false
        publish()
        try {
            unregisterScreenReceiver()
        } finally {
            activity.lifecycle.removeObserver(lifecycleObserver)
        }
    }

    /** A retired composition cannot clear or re-enable its successor's request. */
    class LyricsLease internal constructor(private val owner: WatchWindowRuntime, private val token: Any) : AutoCloseable {
        fun update(requested: Boolean) = owner.updateLyricsRequest(token, requested)
        override fun close() = owner.releaseLyricsLease(token)
    }
}
