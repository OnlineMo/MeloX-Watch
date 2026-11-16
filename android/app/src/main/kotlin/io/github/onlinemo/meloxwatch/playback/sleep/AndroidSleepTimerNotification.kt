package io.github.onlinemo.meloxwatch.playback.sleep

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.playback.PlaybackCommands

/** 一般摘要使用独立ID，不能替换MediaSession的前台通知，也不显示曲目或启动标识。 */
internal class AndroidSleepTimerNotification(context: Context) : SleepTimerNotification {
    private val context = context.applicationContext
    private val manager = context.getSystemService(NotificationManager::class.java)

    override fun isAllowed(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            val notifications = manager ?: return false
            notifications.areNotificationsEnabled() &&
                notifications.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        } catch (_: RuntimeException) {
            false
        }
    }

    override fun show(summary: SleepTimerSummary?): Boolean = try {
        val notifications = manager
        if (summary == null) {
            notifications?.cancel(ID)
            true
        } else if (notifications == null || !isAllowed()) {
            false
        } else {
            notifications.createNotificationChannel(NotificationChannel(CHANNEL,
                context.getString(R.string.sleep_timer_channel), NotificationManager.IMPORTANCE_LOW))
            val title = context.resources.getQuantityString(R.plurals.sleep_timer_minutes_remaining,
                summary.remainingMinutes, summary.remainingMinutes)
            val detail = context.getString(if (summary.endMode == SleepTimerEndMode.PAUSE_IMMEDIATELY)
                R.string.sleep_timer_pause_immediately else R.string.sleep_timer_after_current)
            val intent = PendingIntent.getActivity(context, ID, PlaybackCommands.playbackActivityIntent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(detail)
                .setContentIntent(intent)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false)
                .build()
            notifications.notify(ID, notification)
            true
        }
    } catch (_: RuntimeException) {
        false
    }

    private companion object {
        const val ID = 1002
        const val CHANNEL = "melox_sleep_timer"
    }
}
