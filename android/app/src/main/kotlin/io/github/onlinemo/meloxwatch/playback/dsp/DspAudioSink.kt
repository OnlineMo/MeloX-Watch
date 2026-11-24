package io.github.onlinemo.meloxwatch.playback.dsp

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/** 拒绝编码直通、offload 和 tunneling 绕过有序 PCM 处理链。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class DspAudioSink(sink: AudioSink, private val chain: DspAudioProcessorChain) : ForwardingAudioSink(sink) {
    override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int =
        if (format.sampleMimeType == MimeTypes.AUDIO_RAW) super.getFormatSupport(format)
        else AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        // 标签绑定待用格式，旧格式尾帧排完后才在 flush 边界交给处理器。
        chain.configureStreamMetadata(inputFormat.metadata)
        try { super.configure(inputFormat, specifiedBufferSize, outputChannels) }
        catch (error: Exception) {
            chain.outputUnsupported()
            throw error
        }
    }

    override fun setOffloadMode(offloadMode: Int) = super.setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED)

    override fun flush() {
        chain.hardFlush()
        super.flush()
    }

    override fun reset() {
        chain.hardFlush()
        super.reset()
    }

    override fun enableTunnelingV21() {
        chain.outputUnsupported()
        throw UnsupportedOperationException("Tunneled output bypasses the PCM processing chain")
    }

    override fun release() {
        try { super.release() } finally { chain.release() }
    }
}
