package io.github.onlinemo.meloxwatch.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TimelineWithUpdatedMediaItem
import androidx.media3.exoplayer.source.WrappingMediaSource

/** 未完成的内核队列尾部；真实文件时长、位置与live配置仍由原source提供。 */
internal data class QueueTailMarker(val pendingContinuation: Boolean)

/** 既有factory先完成配置，真实读取/解码和原错误处理继续由它负责。 */
@UnstableApi
internal class RollingQueueMediaSourceFactory(private val delegate: MediaSource.Factory) : MediaSource.Factory by delegate {
    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val source = delegate.createMediaSource(mediaItem)
        if (mediaItem.localConfiguration?.tag !is QueueTailMarker) return source
        // 标记false仍须可原位finalize；拒绝无法更新的child，不能宣称可保持当前stream。
        if (!source.isSingleWindow || !source.canUpdateMediaItem(mediaItem)) throw UnsupportedOperationException(UPDATE_UNSUPPORTED)
        return QueueTailMediaSource(source)
    }
}

@UnstableApi
private class QueueTailMediaSource(source: MediaSource) : WrappingMediaSource(source) {
    private var childTimeline: Timeline? = null

    override fun getInitialTimeline(): Timeline? = super.getInitialTimeline()?.let(::project)

    override fun canUpdateMediaItem(mediaItem: MediaItem): Boolean {
        if (mediaItem.localConfiguration?.tag !is QueueTailMarker) return false
        val accepted = super.canUpdateMediaItem(mediaItem)
        if (!accepted && mediaItem.buildUpon().setTag(this.mediaItem.localConfiguration?.tag).build() == this.mediaItem) {
            // 仅尾标记变化却不可更新时显式失败，不让内核静默替换已播放的source。
            throw UnsupportedOperationException(UPDATE_UNSUPPORTED)
        }
        return accepted
    }

    override fun updateMediaItem(mediaItem: MediaItem) {
        if (!canUpdateMediaItem(mediaItem)) throw UnsupportedOperationException(UPDATE_UNSUPPORTED)
        super.updateMediaItem(mediaItem)
        childTimeline?.let { refreshSourceInfo(project(it)) }
    }

    override fun onChildSourceInfoRefreshed(newTimeline: Timeline) {
        childTimeline = newTimeline
        refreshSourceInfo(project(newTimeline))
    }

    override fun releaseSourceInternal() {
        try { super.releaseSourceInternal() } finally { childTimeline = null }
    }

    private fun project(timeline: Timeline): Timeline {
        val currentItem = mediaItem
        val pending = (currentItem.localConfiguration?.tag as? QueueTailMarker)?.pendingContinuation == true
        return object : ForwardingTimeline(TimelineWithUpdatedMediaItem(timeline, currentItem)) {
            override fun getWindow(windowIndex: Int, window: Timeline.Window, defaultPositionProjectionUs: Long): Timeline.Window {
                super.getWindow(windowIndex, window, defaultPositionProjectionUs)
                // 官方投影同步mediaItem及兼容tag，原period、时间与seek仍由child提供。
                if (pending && windowIndex == windowCount - 1) window.isDynamic = true
                return window
            }
        }
    }
}

private const val UPDATE_UNSUPPORTED = "ROLLING_QUEUE_TAIL_UPDATE_UNSUPPORTED"
