package io.github.onlinemo.meloxwatch.playback.route

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.AudioDeviceInfoBuilder

/** 仅供云端单元测试的合成 public API 返回值，不能作为设备路由或支持证据。 */
@Implements(AudioDeviceInfo::class)
class SyntheticRoutingDeviceShadow {
    var localId = 17
    var output = true

    @Implementation
    protected fun getId(): Int = localId

    @Implementation
    protected fun isSink(): Boolean = output
}

internal fun syntheticRoutingDevice(id: Int = 17): AudioDeviceInfo = AudioDeviceInfoBuilder.newBuilder().build().also {
    Shadow.extract<SyntheticRoutingDeviceShadow>(it).localId = id
}

internal class ControlledRoutingAudioTrack : AudioTrack(
    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48_000)
        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
    4096, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE,
) {
    var platformState = AudioTrack.STATE_INITIALIZED
    var platformPlayState = AudioTrack.PLAYSTATE_PLAYING
    var outputDevice: AudioDeviceInfo? = null
    var routedReads = 0
    var onRoutedRead: (() -> Unit)? = null
    var releaseCalls = 0
    val listeners = mutableListOf<Pair<AudioRouting.OnRoutingChangedListener, Handler?>>()
    val removed = mutableListOf<AudioRouting.OnRoutingChangedListener>()

    override fun getState(): Int = platformState
    override fun getPlayState(): Int = platformPlayState

    override fun getRoutedDevice(): AudioDeviceInfo? {
        routedReads++
        onRoutedRead?.also { onRoutedRead = null }?.invoke()
        return outputDevice
    }

    override fun addOnRoutingChangedListener(listener: AudioRouting.OnRoutingChangedListener, handler: Handler?) {
        listeners.add(listener to handler)
    }

    override fun removeOnRoutingChangedListener(listener: AudioRouting.OnRoutingChangedListener) { removed.add(listener) }
    override fun release() { releaseCalls++ }
    fun closePlatform() = super.release()
}
