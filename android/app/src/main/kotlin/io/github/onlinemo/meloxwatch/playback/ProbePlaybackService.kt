package io.github.onlinemo.meloxwatch.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Process
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionError
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.onlinemo.meloxwatch.ui.MainActivity

/**
 * DEV-004-A 的本地音频所有者；Activity 只持有连接，不创建第二个播放器。
 *
 * 当前探针将焦点/noisy 交给唯一 ExoPlayer。LP-05 接入另一后端前，DEV-103
 * 必须把这些职责移交统一协调器，不能再叠加第二套监听。
 */
@UnstableApi
class ProbePlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        setListener(object : Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                val session = mediaSession ?: return
                // 前台提升失败必须停声，不让系统拒绝变成无通知后台播放。
                session.player.pause()
                session.sendError(SessionError(SessionError.ERROR_INVALID_STATE, "BACKGROUND_START_DENIED"))
            }
        })
        // 不提供 HTTP 数据源；错误 URI 不能使离线探针隐式发起网络请求。
        val localDataSource = DataSource.Factory { ContentDataSource(this) }
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(localDataSource))
            .build()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        player.setAudioAttributes(audioAttributes, true)
        player.setHandleAudioBecomingNoisy(true)
        player.setWakeMode(C.WAKE_MODE_LOCAL)

        val activityIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setCallback(LocalProbeCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        val session = mediaSession
        val player = session?.player
        mediaSession = null
        session?.release()
        player?.release()
        super.onDestroy()
    }

    private class LocalProbeCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            if (controller.uid != Process.myUid() && !controller.isTrusted) {
                return MediaSession.ConnectionResult.reject()
            }
            val result = MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            if (controller.uid != Process.myUid()) {
                // 系统/耳机可控制当前音频，但不能把探针变成任意 URI 读取入口。
                val commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .remove(Player.COMMAND_SET_MEDIA_ITEM)
                    .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                    .build()
                result.setAvailablePlayerCommands(commands)
            }
            return result.build()
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> {
            if (controller.uid != Process.myUid()) {
                return Futures.immediateFailedFuture(SecurityException("LOCAL_PROBE_CONTROLLER_DENIED"))
            }
            val hasInvalidUri = mediaItems.any { mediaItem ->
                val uri = mediaItem.localConfiguration?.uri
                uri == null || uri.scheme != "content" || uri.authority.isNullOrBlank()
            }
            if (hasInvalidUri) {
                return Futures.immediateFailedFuture(IllegalArgumentException("LOCAL_PROBE_URI_REQUIRED"))
            }
            return Futures.immediateFuture(mediaItems.toList())
        }
    }
}
