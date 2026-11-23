package io.github.onlinemo.meloxwatch.companion.bluetoothlyrics

import android.Manifest
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.onlinemo.meloxwatch.companion.MeloXCompanionApplication
import io.github.onlinemo.meloxwatch.companion.R
import io.github.onlinemo.meloxwatch.companion.ui.CompanionInk
import io.github.onlinemo.meloxwatch.companion.ui.MeloXAction
import io.github.onlinemo.meloxwatch.companion.ui.MeloXCard
import io.github.onlinemo.meloxwatch.companion.ui.MeloXText
import io.github.onlinemo.meloxwatch.data.bluetoothlyrics.BluetoothLyricsIssue
import io.github.onlinemo.meloxwatch.data.bluetoothlyrics.BluetoothLyricsPayload
import io.github.onlinemo.meloxwatch.data.bluetoothlyrics.BluetoothLyricsPhase
import io.github.onlinemo.meloxwatch.data.bluetoothlyrics.BluetoothLyricsPlatform
import io.github.onlinemo.meloxwatch.data.bluetoothlyrics.BluetoothLyricsPlayback
import io.github.onlinemo.meloxwatch.data.bluetoothlyrics.BluetoothLyricsSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Received text and comparison tickets remain in memory and are collected only while resumed. */
class BluetoothLyricsReceiverActivity : ComponentActivity() {
    private lateinit var receiver: CompanionBluetoothLyricsReceiver
    private var presentation: CompanionBluetoothLyricsReceiver.Presentation? = null
    private val permission = MutableStateFlow(false)
    private val notificationPermission = MutableStateFlow(true)
    private val platformIssue = MutableStateFlow<BluetoothLyricsIssue?>(null)
    private var resumed = false
    private var focused = false
    private var visible = false
    private var screenReceiverRegistered = false
    private var retryingRetirement = false
    private val connectPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshPermissions()
        if (!it) platformIssue.value = BluetoothLyricsIssue.PERMISSION_DENIED
    }
    private val notificationsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshPermissions()
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { updateVisibility() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receiver = (application as MeloXCompanionApplication).bluetoothLyrics
        presentation = receiver.bindPresentation()
        enableEdgeToEdge()
        refreshPermissions()
        setContent {
            val session by receiver.sessions.state.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
            val payload by receiver.sessions.payload.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
            val receiverIssue by receiver.issue.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
            val granted by permission.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
            val notifications by notificationPermission.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
            val issue by platformIssue.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
            ReceiverContent(session, payload, session.issue ?: receiverIssue ?: issue, granted, notifications)
        }
    }

    override fun onStart() {
        super.onStart()
        if (!screenReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            try {
                ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
                screenReceiverRegistered = true
            } catch (_: RuntimeException) { platformIssue.value = BluetoothLyricsIssue.UNAVAILABLE }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        focused = window.decorView.hasWindowFocus()
        refreshPermissions()
        updateVisibility()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        focused = hasFocus
        if (::receiver.isInitialized) updateVisibility()
    }

    override fun onPause() {
        resumed = false
        updateVisibility()
        super.onPause()
    }

    override fun onStop() {
        if (screenReceiverRegistered) {
            try { unregisterReceiver(screenReceiver) }
            catch (_: RuntimeException) { platformIssue.value = BluetoothLyricsIssue.UNAVAILABLE }
            finally { screenReceiverRegistered = false }
        }
        super.onStop()
    }

    override fun onDestroy() {
        try { presentation?.close() } finally { super.onDestroy() }
    }

    private fun onCurrentUi(action: () -> Unit) {
        if (presentation?.isCurrent == true && visible) action()
    }

    private fun updateVisibility() {
        val interactive = try {
            val power = getSystemService(PowerManager::class.java)
            val keyguard = getSystemService(KeyguardManager::class.java)
            power?.isInteractive == true && keyguard?.isKeyguardLocked == false && keyguard?.isDeviceLocked == false
        } catch (_: RuntimeException) { false }
        visible = resumed && focused && interactive && screenReceiverRegistered
        presentation?.visible(visible)
    }

    private fun refreshPermissions() {
        if (presentation?.isCurrent != true) return
        permission.value = try { BluetoothLyricsPlatform.hasPermission(this) } catch (_: RuntimeException) { false }
        notificationPermission.value = try {
            NotificationManagerCompat.from(this).areNotificationsEnabled() &&
                (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        } catch (_: RuntimeException) { false }
        if (!permission.value) receiver.sessions.stop(BluetoothLyricsIssue.PERMISSION_DENIED)
        if (permission.value) platformIssue.value = null
    }

    private fun requestConnectionPermission() {
        if (presentation?.isCurrent != true || !visible) return
        if (BluetoothLyricsPlatform.needsPermission()) {
            try { connectPermission.launch(Manifest.permission.BLUETOOTH_CONNECT) }
            catch (_: RuntimeException) { platformIssue.value = BluetoothLyricsIssue.PERMISSION_DENIED }
        } else refreshPermissions()
    }

    private fun requestNotificationPermission() {
        if (presentation?.isCurrent != true || !visible) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            try { notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
            catch (_: RuntimeException) { platformIssue.value = BluetoothLyricsIssue.UNAVAILABLE }
        } else try { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
        catch (_: ActivityNotFoundException) { platformIssue.value = BluetoothLyricsIssue.UNAVAILABLE }
        catch (_: SecurityException) { platformIssue.value = BluetoothLyricsIssue.PERMISSION_DENIED }
    }

    private fun openSettings(appPermissions: Boolean) {
        if (presentation?.isCurrent != true || !visible) return
        val request = if (appPermissions) Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            ("package:" + packageName).toUri()) else Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        try { startActivity(request) }
        catch (_: ActivityNotFoundException) { platformIssue.value = BluetoothLyricsIssue.UNAVAILABLE }
        catch (_: SecurityException) { platformIssue.value = BluetoothLyricsIssue.PERMISSION_DENIED }
    }

    private fun startReceiving() {
        updateVisibility()
        if (presentation?.isCurrent != true || !visible || !permission.value) return
        platformIssue.value = null
        try {
            ContextCompat.startForegroundService(this, Intent(this, BluetoothLyricsReceiverService::class.java)
                .setAction(BluetoothLyricsReceiverService.ACTION_START))
        } catch (_: SecurityException) { receiver.startDenied(BluetoothLyricsIssue.PERMISSION_DENIED) }
        catch (_: RuntimeException) { receiver.startDenied(BluetoothLyricsIssue.UNAVAILABLE) }
    }

    private fun stopReceiving() {
        if (presentation?.isCurrent != true || !visible) return
        receiver.sessions.stop()
        stopService(Intent(this, BluetoothLyricsReceiverService::class.java))
    }

    private fun retryRetirement() {
        if (presentation?.isCurrent != true || !visible || retryingRetirement) return
        retryingRetirement = true
        lifecycleScope.launch {
            try { receiver.sessions.retryRetirement() }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { platformIssue.value = BluetoothLyricsIssue.RETIREMENT_REQUIRED }
            finally { retryingRetirement = false }
        }
    }

    @Composable
    private fun ReceiverContent(
        session: BluetoothLyricsSessionState,
        payload: BluetoothLyricsPayload?,
        issue: BluetoothLyricsIssue?,
        granted: Boolean,
        notifications: Boolean,
    ) {
        val idle = session.phase == BluetoothLyricsPhase.OFF || session.phase == BluetoothLyricsPhase.FAILED
        Column(Modifier.fillMaxSize().background(CompanionInk.background)
            .windowInsetsPadding(WindowInsets.safeDrawing), horizontalAlignment = Alignment.CenterHorizontally) {
            MeloXAction(stringResource(R.string.bluetooth_lyrics_back), ::finish,
                Modifier.widthIn(max = 720.dp).padding(16.dp))
            LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { MeloXText(stringResource(R.string.bluetooth_lyrics_receiver_title), heading = true) }
                item { MeloXText(stringResource(R.string.bluetooth_lyrics_compatibility), secondary = true) }
                item { MeloXText(stringResource(R.string.bluetooth_lyrics_disclosure)) }
                item { MeloXText(stringResource(R.string.bluetooth_lyrics_receiver_note), secondary = true) }
                item { MeloXCard {
                    MeloXText(stringResource(phonePhaseText(session.phase)))
                    if (session.peerLabel.isNotEmpty()) MeloXText(stringResource(R.string.bluetooth_lyrics_peer, session.peerLabel), secondary = true)
                    if (session.phase == BluetoothLyricsPhase.CONNECTING) MeloXText(stringResource(R.string.bluetooth_lyrics_waiting_peer), secondary = true)
                    val comparison = session.comparison
                    val digits = comparison?.forLocalUi()
                    if (session.phase == BluetoothLyricsPhase.COMPARING && comparison != null && digits != null) {
                        MeloXText(stringResource(R.string.bluetooth_lyrics_compare))
                        MeloXText(digits, Modifier.semantics { contentDescription = digits.toList().joinToString(" ") }, heading = true, mono = true)
                        MeloXAction(stringResource(R.string.bluetooth_lyrics_approve), { onCurrentUi { presentation?.approve(comparison) } }, prominent = true)
                        MeloXAction(stringResource(R.string.bluetooth_lyrics_reject), { onCurrentUi { presentation?.reject() } })
                    }
                    if (!idle) MeloXAction(stringResource(R.string.bluetooth_lyrics_stop), ::stopReceiving)
                } }
                if (issue != null) item { MeloXCard { MeloXText(stringResource(phoneIssueText(issue))) } }
                if (session.retirementRequired) item {
                    MeloXAction(stringResource(R.string.bluetooth_lyrics_retry_retirement), ::retryRetirement)
                }
                if (!granted) {
                    item { MeloXText(stringResource(R.string.bluetooth_lyrics_permission_note), secondary = true) }
                    item { MeloXAction(stringResource(R.string.bluetooth_lyrics_permission), ::requestConnectionPermission) }
                    item { MeloXAction(stringResource(R.string.bluetooth_lyrics_app_settings), { openSettings(true) }) }
                }
                item { MeloXAction(stringResource(R.string.bluetooth_lyrics_system_settings), { openSettings(false) }) }
                if (!notifications) {
                    item { MeloXText(stringResource(R.string.bluetooth_lyrics_notifications_note), secondary = true) }
                    item { MeloXAction(stringResource(R.string.bluetooth_lyrics_notifications), ::requestNotificationPermission) }
                }
                item { MeloXAction(stringResource(R.string.bluetooth_lyrics_start_receiver), ::startReceiving,
                    enabled = idle && granted && !session.retirementRequired, prominent = true) }
                if (session.phase == BluetoothLyricsPhase.CONNECTED) item {
                    if (payload == null) MeloXText(stringResource(R.string.bluetooth_lyrics_no_payload))
                    else CurrentPayload(payload)
                }
            }
        }
    }
}

@Composable
private fun CurrentPayload(payload: BluetoothLyricsPayload) {
    MeloXCard {
        if (payload.title.isNotEmpty()) MeloXText(payload.title, heading = true)
        if (payload.artist.isNotEmpty()) MeloXText(payload.artist, secondary = true)
        MeloXText(stringResource(when (payload.playback) {
            BluetoothLyricsPlayback.NO_TRACK -> R.string.bluetooth_lyrics_no_track
            BluetoothLyricsPlayback.LOADING -> R.string.bluetooth_lyrics_loading_track
            BluetoothLyricsPlayback.ENDED -> R.string.bluetooth_lyrics_ended
            BluetoothLyricsPlayback.NO_LYRICS -> R.string.bluetooth_lyrics_no_lyrics
            BluetoothLyricsPlayback.SOURCE_UNAVAILABLE -> R.string.bluetooth_lyrics_error_source
            BluetoothLyricsPlayback.PLAIN_TEXT -> R.string.bluetooth_lyrics_plain
            BluetoothLyricsPlayback.PLAYING -> R.string.bluetooth_lyrics_playing
            BluetoothLyricsPlayback.PAUSED -> R.string.bluetooth_lyrics_paused
        }))
        if (payload.playback !in setOf(BluetoothLyricsPlayback.PLAYING, BluetoothLyricsPlayback.PAUSED,
                BluetoothLyricsPlayback.NO_TRACK, BluetoothLyricsPlayback.ENDED)) {
            MeloXText(stringResource(if (payload.isPlaying) R.string.bluetooth_lyrics_playing else R.string.bluetooth_lyrics_paused), secondary = true)
        }
        if (payload.original.isNotEmpty()) {
            MeloXText(stringResource(R.string.bluetooth_lyrics_current_line), secondary = true)
            MeloXText(payload.original)
        }
        if (payload.translation.isNotEmpty()) {
            MeloXText(stringResource(R.string.bluetooth_lyrics_translation), secondary = true)
            MeloXText(payload.translation)
        }
        if (payload.shortened) MeloXText(stringResource(R.string.bluetooth_lyrics_shortened), secondary = true)
        MeloXText(stringResource(R.string.bluetooth_lyrics_position, payload.mediaPositionMs), secondary = true)
    }
}

private fun phonePhaseText(phase: BluetoothLyricsPhase): Int = when (phase) {
    BluetoothLyricsPhase.OFF -> R.string.bluetooth_lyrics_phase_off
    BluetoothLyricsPhase.CONNECTING -> R.string.bluetooth_lyrics_phase_connecting
    BluetoothLyricsPhase.COMPARING -> R.string.bluetooth_lyrics_phase_comparing
    BluetoothLyricsPhase.CONNECTED -> R.string.bluetooth_lyrics_phase_connected
    BluetoothLyricsPhase.CLOSING -> R.string.bluetooth_lyrics_phase_closing
    BluetoothLyricsPhase.FAILED -> R.string.bluetooth_lyrics_phase_failed
}

private fun phoneIssueText(issue: BluetoothLyricsIssue): Int = when (issue) {
    BluetoothLyricsIssue.CLOSED -> R.string.bluetooth_lyrics_error_closed
    BluetoothLyricsIssue.BUSY -> R.string.bluetooth_lyrics_error_busy
    BluetoothLyricsIssue.UNAVAILABLE -> R.string.bluetooth_lyrics_error_unavailable
    BluetoothLyricsIssue.BLUETOOTH_OFF -> R.string.bluetooth_lyrics_error_off
    BluetoothLyricsIssue.NOT_BONDED -> R.string.bluetooth_lyrics_error_bond
    BluetoothLyricsIssue.PERMISSION_DENIED -> R.string.bluetooth_lyrics_error_permission
    BluetoothLyricsIssue.WRONG_PEER -> R.string.bluetooth_lyrics_error_peer
    BluetoothLyricsIssue.NOT_CONFIRMED -> R.string.bluetooth_lyrics_error_confirm
    BluetoothLyricsIssue.EXPIRED -> R.string.bluetooth_lyrics_error_expired
    BluetoothLyricsIssue.CONNECTION_LOST, BluetoothLyricsIssue.TRANSPORT_FAILED -> R.string.bluetooth_lyrics_error_connection
    BluetoothLyricsIssue.INVALID_FRAME -> R.string.bluetooth_lyrics_error_frame
    BluetoothLyricsIssue.INCOMPATIBLE_SCHEMA -> R.string.bluetooth_lyrics_error_schema
    BluetoothLyricsIssue.AUTHENTICATION_FAILED -> R.string.bluetooth_lyrics_error_auth
    BluetoothLyricsIssue.CRYPTO_UNAVAILABLE -> R.string.bluetooth_lyrics_error_crypto
    BluetoothLyricsIssue.RATE_LIMITED -> R.string.bluetooth_lyrics_error_rate
    BluetoothLyricsIssue.RETIREMENT_REQUIRED -> R.string.bluetooth_lyrics_error_retirement
    BluetoothLyricsIssue.STORAGE_UNAVAILABLE -> R.string.bluetooth_lyrics_error_storage
    BluetoothLyricsIssue.SOURCE_UNAVAILABLE -> R.string.bluetooth_lyrics_error_source
}
