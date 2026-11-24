package io.github.onlinemo.meloxwatch.playback.route

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import io.github.onlinemo.meloxwatch.playback.dsp.DspAudioProcessorChain
import io.github.onlinemo.meloxwatch.playback.dsp.DspAudioSink
import io.github.onlinemo.meloxwatch.playback.dsp.DspRuntime

@androidx.annotation.OptIn(UnstableApi::class)
internal class RoutingRenderersFactory(
    context: Context,
    private val route: PlaybackAudioRoute,
    private val dspRuntime: DspRuntime,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        val chain = DspAudioProcessorChain(dspRuntime)
        try {
            val sink = DefaultAudioSink.Builder(context)
                .setAudioProcessorChain(chain)
                .setEnableFloatOutput(false)
                .setEnableAudioTrackPlaybackParams(false)
                .setAudioTrackProvider(RoutingAudioTrackProvider(route))
                .build()
            return RoutingAudioSink(DspAudioSink(sink, chain), route)
        } catch (error: Exception) {
            chain.release()
            throw error
        }
    }
}
