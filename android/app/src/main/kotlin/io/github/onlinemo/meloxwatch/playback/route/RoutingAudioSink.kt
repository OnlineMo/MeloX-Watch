package io.github.onlinemo.meloxwatch.playback.route

import android.media.AudioDeviceInfo
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

@androidx.annotation.OptIn(UnstableApi::class)
internal class RoutingAudioSink(sink: AudioSink, private val route: PlaybackAudioRoute) : ForwardingAudioSink(sink) {
    private var playing = false

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) = changeSource {
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleDiscontinuity() = changeSource { super.handleDiscontinuity() }

    override fun setAudioAttributes(audioAttributes: AudioAttributes) = changeSource {
        super.setAudioAttributes(audioAttributes)
    }

    override fun setAudioSessionId(audioSessionId: Int) = changeSource { super.setAudioSessionId(audioSessionId) }

    override fun enableTunnelingV21() = changeSource { super.enableTunnelingV21() }

    override fun disableTunneling() = changeSource { super.disableTunneling() }

    override fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo?) = changeSource {
        super.setPreferredDevice(audioDeviceInfo)
    }

    override fun play() {
        try {
            super.play()
        } catch (failure: RuntimeException) {
            renderingFailed()
            throw failure
        }
        playing = true
        route.setRenderingActive(true)
    }

    override fun pause() {
        playing = false
        route.setRenderingActive(false)
        super.pause()
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val handled = try {
            super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        } catch (failure: Exception) {
            renderingFailed()
            throw failure
        }
        // DefaultAudioSink 的 self-flush 绕过包装器；true 也可能是 stalled-track flush。
        // 同步查真轨道的 initialized/playState/routedDevice，不能用返回值恢复旧身份。
        route.read()
        return handled
    }

    override fun playToEndOfStream() {
        try {
            super.playToEndOfStream()
        } catch (failure: Exception) {
            renderingFailed()
            throw failure
        }
        route.read()
    }

    override fun flush() = changeSource(keepSource = false) { super.flush() }

    override fun reset() {
        playing = false
        changeSource(keepSource = false) { super.reset() }
    }

    override fun release() {
        playing = false
        changeSource(keepSource = false) { super.release() }
    }

    private fun changeSource(keepSource: Boolean = true, change: () -> Unit) {
        route.setRenderingActive(false)
        route.sourceChanged(keepSource)
        try {
            change()
        } catch (failure: Exception) {
            renderingFailed()
            throw failure
        }
        route.setRenderingActive(playing)
    }

    private fun renderingFailed() {
        playing = false
        route.setRenderingActive(false)
        route.sourceChanged()
    }
}
