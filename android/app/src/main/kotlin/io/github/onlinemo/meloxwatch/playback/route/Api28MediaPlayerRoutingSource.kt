package io.github.onlinemo.meloxwatch.playback.route

import android.media.AudioRouting
import android.media.MediaPlayer
import android.os.Handler
import androidx.annotation.RequiresApi

/** API 26/27 的调用路径不加载 MediaPlayer AudioRouting 操作。 */
@RequiresApi(28)
internal class Api28MediaPlayerRoutingSource(
    private val player: MediaPlayer,
    private val handler: Handler,
    private val stillCurrent: () -> Boolean,
) : ActualRoutingSource {
    override val objectIdentity: Any get() = player

    override fun playingDeviceId(): Int? {
        if (!stillCurrent() || !player.isPlaying) return null
        val device = player.routedDevice ?: return null
        // 暂停结果可能是上次设备，查询之后再次核拥有关系与实际播放状态。
        if (!device.isSink || !stillCurrent() || !player.isPlaying) return null
        return device.id.takeIf { it > 0 }
    }

    override fun addRoutingListener(changed: () -> Unit): RoutingListenerRemoval {
        val listener = AudioRouting.OnRoutingChangedListener { router -> if (router === player) changed() }
        try { player.addOnRoutingChangedListener(listener, handler) }
        catch (failure: RuntimeException) {
            try { player.removeOnRoutingChangedListener(listener) }
            catch (_: RuntimeException) { }
            throw failure
        }
        return RoutingListenerRemoval { player.removeOnRoutingChangedListener(listener) }
    }
}
