package io.github.onlinemo.meloxwatch.playback.dsp

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Metadata
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi

/** 速度/音调先处理，再交给唯一增益、均衡器和压限链。 */
@androidx.annotation.OptIn(UnstableApi::class)
class DspAudioProcessorChain(private val runtime: DspRuntime) : AudioProcessorChain {
    private val sonic = SonicAudioProcessor()
    private val dsp = DspAudioProcessor(runtime)
    private val processors = arrayOf<AudioProcessor>(sonic, dsp)

    internal fun configureStreamMetadata(metadata: Metadata?) = dsp.configureStreamTags(ReplayGainTagReader.read(metadata))
    internal fun hardFlush() = dsp.markHardFlush()
    internal fun outputUnsupported() = runtime.report(dsp.ticket,
        DspActualState(requested = runtime.currentSettings(), issue = DspRuntimeIssue.OUTPUT_UNSUPPORTED))
    internal fun release() = runtime.releaseSink(dsp.ticket)

    override fun getAudioProcessors(): Array<AudioProcessor> = processors

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        sonic.setSpeed(playbackParameters.speed)
        sonic.setPitch(playbackParameters.pitch)
        return playbackParameters
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean = false

    override fun getMediaDuration(playoutDuration: Long): Long =
        if (sonic.isActive) sonic.getMediaDuration(playoutDuration) else playoutDuration

    override fun getSkippedOutputFrameCount(): Long = 0L
}
