package io.github.onlinemo.meloxwatch.playback.route

import android.content.Context
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

@androidx.annotation.OptIn(UnstableApi::class)
internal class RoutingAudioTrackProvider(
    private val route: PlaybackAudioRoute,
    private val delegate: DefaultAudioSink.AudioTrackProvider = DefaultAudioSink.AudioTrackProvider.DEFAULT,
) : DefaultAudioSink.AudioTrackProvider {
    override fun getAudioTrack(
        audioTrackConfig: AudioSink.AudioTrackConfig,
        audioAttributes: AudioAttributes,
        audioSessionId: Int,
        context: Context?,
    ): AudioTrack {
        val ticket = route.beginTrack()
        val track = delegate.getAudioTrack(audioTrackConfig, audioAttributes, audioSessionId, context)
        // 分配和释放仍归 sink；只有播放线程与初始化成功的真轨道可建立观察。
        val looper = Looper.myLooper()
        if (ticket != null && looper != null && track.state == AudioTrack.STATE_INITIALIZED) {
            route.observe(ticket, AndroidRoutingSources.audioTrack(track, Handler(looper)))
        }
        return track
    }

    override fun getAudioTrackChannelConfig(channelCount: Int): Int = delegate.getAudioTrackChannelConfig(channelCount)
}
