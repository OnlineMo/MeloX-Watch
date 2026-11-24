package io.github.onlinemo.meloxwatch.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import io.github.onlinemo.meloxwatch.playback.route.PlaybackAudioRoute

/**
 * 命令抵达现有 ExoPlayer 前退役旧输出身份，由服务观察实际状态后再打开播放门。
 *
 * 与被包装播放器共用 application Looper；不创建内核，也不直接转发监听器绕过 facade 身份。
 */
@UnstableApi
internal class RoutingPlaybackPlayer(
    player: Player,
    private val route: PlaybackAudioRoute,
) : ForwardingPlayer(player) {
    override fun pause() {
        route.setPlaybackActive(false)
        super.pause()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (!playWhenReady) route.setPlaybackActive(false)
        super.setPlayWhenReady(playWhenReady)
    }

    override fun prepare() = changeSource { super.prepare() }

    override fun stop() = changeSource(keepSource = false) { super.stop() }

    override fun release() {
        route.close()
        super.release()
    }

    // ForwardingPlayer 的重载分别直接委派，必须逐一拦截来源命令，不能依赖重载间转调。
    override fun setMediaItem(mediaItem: MediaItem) = changeSource { super.setMediaItem(mediaItem) }

    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) = changeSource {
        super.setMediaItem(mediaItem, startPositionMs)
    }

    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) = changeSource {
        super.setMediaItem(mediaItem, resetPosition)
    }

    override fun setMediaItems(mediaItems: List<MediaItem>) = changeSource { super.setMediaItems(mediaItems) }

    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) = changeSource {
        super.setMediaItems(mediaItems, resetPosition)
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) = changeSource {
        super.setMediaItems(mediaItems, startIndex, startPositionMs)
    }

    override fun clearMediaItems() = changeSource(keepSource = false) { super.clearMediaItems() }

    override fun addMediaItem(mediaItem: MediaItem) = changeEmptySource { super.addMediaItem(mediaItem) }

    override fun addMediaItem(index: Int, mediaItem: MediaItem) = changeEmptySource { super.addMediaItem(index, mediaItem) }

    override fun addMediaItems(mediaItems: List<MediaItem>) = changeEmptySource { super.addMediaItems(mediaItems) }

    override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) = changeEmptySource {
        super.addMediaItems(index, mediaItems)
    }

    override fun replaceMediaItem(index: Int, mediaItem: MediaItem) = changeCurrentSource(index, index + 1) {
        super.replaceMediaItem(index, mediaItem)
    }

    override fun replaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>) =
        changeCurrentSource(fromIndex, toIndex) {
            super.replaceMediaItems(fromIndex, toIndex, mediaItems)
        }

    override fun removeMediaItem(index: Int) = changeCurrentSource(index, index + 1) { super.removeMediaItem(index) }

    override fun removeMediaItems(fromIndex: Int, toIndex: Int) = changeCurrentSource(fromIndex, toIndex) {
        super.removeMediaItems(fromIndex, toIndex)
    }

    override fun seekTo(positionMs: Long) = changeSource { super.seekTo(positionMs) }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) = changeSource { super.seekTo(mediaItemIndex, positionMs) }

    override fun seekToDefaultPosition() = changeSource { super.seekToDefaultPosition() }

    override fun seekToDefaultPosition(mediaItemIndex: Int) = changeSource { super.seekToDefaultPosition(mediaItemIndex) }

    override fun seekBack() = changeSource { super.seekBack() }

    override fun seekForward() = changeSource { super.seekForward() }

    override fun seekToPreviousMediaItem() = changeSource { super.seekToPreviousMediaItem() }

    override fun seekToPrevious() = changeSource { super.seekToPrevious() }

    override fun seekToNextMediaItem() = changeSource { super.seekToNextMediaItem() }

    override fun seekToNext() = changeSource { super.seekToNext() }

    override fun setAudioAttributes(audioAttributes: AudioAttributes, handleAudioFocus: Boolean) = changeSource {
        super.setAudioAttributes(audioAttributes, handleAudioFocus)
    }

    private fun changeEmptySource(change: () -> Unit) {
        if (mediaItemCount == 0) changeSource(change = change) else change()
    }

    private fun changeCurrentSource(fromIndex: Int, toIndex: Int, change: () -> Unit) {
        // 仅编辑相邻的持久队列窗口项时，保留当前实际来源；移动当前项也由原播放器保持来源。
        if (currentMediaItemIndex in fromIndex until toIndex) changeSource(change = change) else change()
    }

    private fun changeSource(keepSource: Boolean = true, change: () -> Unit) {
        route.setPlaybackActive(false)
        route.sourceChanged(keepSource)
        change()
        // 仅所有者观察到实际 Player 状态后才重新打开此门，晚到的 sink.play 不能替代服务授权。
    }
}
