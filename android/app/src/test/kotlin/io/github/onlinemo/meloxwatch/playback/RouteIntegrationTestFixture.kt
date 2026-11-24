package io.github.onlinemo.meloxwatch.playback

import io.github.onlinemo.meloxwatch.playback.route.MusicVolumePort
import io.github.onlinemo.meloxwatch.playback.route.MusicVolumeValues

/** 云端回归使用的受控媒体流值，不能作为设备音量或实际音频输出证据。 */
internal class RecordingMusicVolume : MusicVolumePort {
    var values = MusicVolumeValues(4, 0, 15, fixed = false, muted = false, remote = false)
    var reads = 0
        private set
    val writes = mutableListOf<Int>()
    var onRead: (() -> Unit)? = null
    var onWrite: (() -> Unit)? = null

    override fun read(): MusicVolumeValues {
        reads++
        val callback = onRead
        onRead = null
        callback?.invoke()
        return values
    }

    override fun set(index: Int) {
        writes += index
        values = values.copy(current = index, muted = false)
        val callback = onWrite
        onWrite = null
        callback?.invoke()
    }
}
