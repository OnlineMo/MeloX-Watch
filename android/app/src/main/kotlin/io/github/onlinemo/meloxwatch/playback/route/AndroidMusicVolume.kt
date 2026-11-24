package io.github.onlinemo.meloxwatch.playback.route

import android.content.Context
import android.media.AudioManager
import android.media.MediaRouter
import android.os.Build

internal class AndroidMusicVolume(context: Context) : MusicVolumePort {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val router = context.getSystemService(MediaRouter::class.java)

    override fun read(): MusicVolumeValues {
        val manager = checkNotNull(audio)
        val current = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val minimum = if (Build.VERSION.SDK_INT >= 28) manager.getStreamMinVolume(AudioManager.STREAM_MUSIC) else 0
        val maximum = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        // 这里只把公开的 remote 提示当拒绝条件；它从不建立实际播放路由身份。
        val remote = router?.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_AUDIO)?.playbackType ==
            MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE
        return MusicVolumeValues(current, minimum, maximum, manager.isVolumeFixed,
            manager.isStreamMute(AudioManager.STREAM_MUSIC), remote)
    }

    override fun set(index: Int) {
        // 不使用 FLAG_PLAY_SOUND，不改铃声/通知音量，也不启动播放器。
        checkNotNull(audio).setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
    }
}
