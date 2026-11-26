package io.github.onlinemo.meloxwatch.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import io.github.onlinemo.meloxwatch.model.LocalCueReference

/** PCM字节已按样本精确裁剪；Media3维护余下的相对微秒时间线。 */
@UnstableApi
internal fun cuePlaybackClipping(reference: LocalCueReference?, systemBackend: Boolean): MediaItem.ClippingConfiguration {
    if (reference == null) return MediaItem.ClippingConfiguration.UNSET
    val window = reference.pcmWindow ?: throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.SOURCE_CHANGED))
    return MediaItem.ClippingConfiguration.Builder()
        .setStartPositionUs(0L)
        .setEndPositionUs(if (systemBackend) C.TIME_END_OF_SOURCE else window.durationUs)
        .setStartsAtKeyFrame(true)
        .build()
}
