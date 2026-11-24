package io.github.onlinemo.meloxwatch.companion.bluetoothlyrics

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.onlinemo.meloxwatch.companion.MeloXCompanionApplication
import io.github.onlinemo.meloxwatch.companion.R
import io.github.onlinemo.meloxwatch.data.bluetoothlyrics.BluetoothLyricsIssue
import io.github.onlinemo.meloxwatch.data.bluetoothlyrics.BluetoothLyricsPhase
import io.github.onlinemo.meloxwatch.data.bluetoothlyrics.BluetoothLyricsPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Only a visible start admits one finite listener. The service never restores or owns a player. */
class BluetoothLyricsReceiverService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var receiver: CompanionBluetoothLyricsReceiver
    private var observer: Job? = null
    private var foreground = false

    override fun onCreate() {
        super.onCreate()
        receiver = (application as MeloXCompanionApplication).bluetoothLyrics
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            receiver.detachService(this)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!BluetoothLyricsPlatform.hasPermission(this)) {
            receiver.startDenied(BluetoothLyricsIssue.PERMISSION_DENIED)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (!foreground) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(),
                    if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
                foreground = true
            }
        } catch (_: SecurityException) {
            receiver.startDenied(BluetoothLyricsIssue.PERMISSION_DENIED)
            stopSelf()
            return START_NOT_STICKY
        } catch (_: RuntimeException) {
            receiver.startDenied(BluetoothLyricsIssue.UNAVAILABLE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!receiver.attachService(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (observer == null) observer = scope.launch {
            receiver.sessions.state.collect {
                if (it.phase in setOf(BluetoothLyricsPhase.OFF, BluetoothLyricsPhase.CLOSING, BluetoothLyricsPhase.FAILED)) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            receiver.detachService(this)
            observer?.cancel()
            scope.cancel()
            if (foreground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } finally {
            foreground = false
            super.onDestroy()
        }
    }

    private fun notification(): Notification {
        val manager = checkNotNull(getSystemService(NotificationManager::class.java))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,
            getString(R.string.bluetooth_lyrics_receiver_title), NotificationManager.IMPORTANCE_LOW).apply {
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            setShowBadge(false)
        })
        val open = PendingIntent.getActivity(this, 0, Intent(this, BluetoothLyricsReceiverActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, BluetoothLyricsReceiverService::class.java)
            .setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Content, device labels and comparisons never enter a notification or mirrored surface.
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bluetooth_lyrics_notification)
            .setContentTitle(getString(R.string.bluetooth_lyrics_notification_title))
            .setContentText(getString(R.string.bluetooth_lyrics_notification_text))
            .setContentIntent(open).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).setLocalOnly(true)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.bluetooth_lyrics_stop), stop)
            .build()
    }

    companion object {
        const val ACTION_START = "io.github.onlinemo.meloxwatch.companion.bluetoothlyrics.START"
        const val ACTION_STOP = "io.github.onlinemo.meloxwatch.companion.bluetoothlyrics.STOP"
        private const val CHANNEL_ID = "bluetooth-lyrics-v1"
        private const val NOTIFICATION_ID = 4104
    }
}
