package io.github.onlinemo.meloxwatch.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.FlagSet
import androidx.media3.common.util.UnstableApi
import io.github.onlinemo.meloxwatch.model.QueueRepeatMode
import java.util.IdentityHashMap

/** 系统只看到有界时间线；标准切歌命令转交完整数据库队列，不能按窗口尾部停止。 */
@UnstableApi
internal class QueueSessionPlayer(
    delegate: Player,
    private val coordinator: QueuePlaybackCoordinator,
) : ForwardingPlayer(delegate) {
    private val adapters = IdentityHashMap<Player.Listener, QueueListener>()

    override fun getAvailableCommands(): Player.Commands {
        if (coordinator.isDetached) return Player.Commands.EMPTY
        val commands = super.getAvailableCommands().buildUpon()
            .remove(Player.COMMAND_SET_MEDIA_ITEM)
            .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
            .remove(Player.COMMAND_SET_REPEAT_MODE)
            .remove(Player.COMMAND_SET_SHUFFLE_MODE)
        for (command in listOf(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)) {
            if (coordinator.canNavigate(-1)) commands.add(command) else commands.remove(command)
        }
        for (command in listOf(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)) {
            if (coordinator.canNavigate(1)) commands.add(command) else commands.remove(command)
        }
        return commands.build()
    }

    override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)

    override fun getRepeatMode(): Int = when (coordinator.state.value.snapshot?.mode?.repeat) {
        QueueRepeatMode.LIST -> Player.REPEAT_MODE_ALL
        QueueRepeatMode.SINGLE -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
    }

    override fun getShuffleModeEnabled(): Boolean = coordinator.state.value.snapshot?.mode?.shuffle == true

    // 原始 playlist/mode mutation 会绕过持久队列；禁用命令的直接调用也不能透传。
    override fun setMediaItems(mediaItems: List<MediaItem>) = Unit
    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) = Unit
    override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) = Unit
    override fun setMediaItem(mediaItem: MediaItem) = Unit
    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) = Unit
    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) = Unit
    override fun addMediaItem(mediaItem: MediaItem) = Unit
    override fun addMediaItem(index: Int, mediaItem: MediaItem) = Unit
    override fun addMediaItems(mediaItems: List<MediaItem>) = Unit
    override fun addMediaItems(index: Int, mediaItems: List<MediaItem>) = Unit
    override fun moveMediaItem(currentIndex: Int, newIndex: Int) = Unit
    override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) = Unit
    override fun replaceMediaItem(index: Int, mediaItem: MediaItem) = Unit
    override fun replaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>) = Unit
    override fun removeMediaItem(index: Int) = Unit
    override fun removeMediaItems(fromIndex: Int, toIndex: Int) = Unit
    override fun clearMediaItems() = Unit
    override fun setRepeatMode(repeatMode: Int) = Unit
    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) = Unit

    override fun prepare() {
        if (!coordinator.isDetached) super.prepare()
    }

    // backend 生命周期属于 Service；Session/controller 不能先释放其唯一播放器。
    override fun release() = Unit

    override fun play() = coordinator.playFromSession()
    override fun pause() = coordinator.pauseFromSession()
    override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) play() else pause()
    override fun stop() = coordinator.pauseFromSession()
    override fun seekToNext() = coordinator.navigateFromSession(1)
    override fun seekToNextMediaItem() = coordinator.navigateFromSession(1)
    override fun seekToPrevious() = coordinator.navigateFromSession(-1)
    override fun seekToPreviousMediaItem() = coordinator.navigateFromSession(-1)
    override fun seekTo(positionMs: Long) = coordinator.seekFromSession(positionMs)
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) = coordinator.seekFromSession(mediaItemIndex, positionMs)
    override fun seekToDefaultPosition() = coordinator.seekFromSession(0L)
    override fun seekToDefaultPosition(mediaItemIndex: Int) = coordinator.seekFromSession(mediaItemIndex, 0L)
    override fun seekBack() = coordinator.seekByFromSession(forward = false)
    override fun seekForward() = coordinator.seekByFromSession(forward = true)

    override fun addListener(listener: Player.Listener) {
        if (coordinator.isDetached || adapters.containsKey(listener)) return
        val adapter = QueueListener(listener)
        adapters[listener] = adapter
        super.addListener(adapter)
    }

    override fun removeListener(listener: Player.Listener) {
        adapters.remove(listener)?.let { super.removeListener(it) }
    }

    fun logicalStateChanged() {
        if (coordinator.isDetached) return
        adapters.values.toList().forEach { it.logicalStateChanged() }
    }

    fun detachListeners() {
        adapters.values.toList().forEach { super.removeListener(it) }
        adapters.clear()
    }

    /** Kotlin 委托保留全部 Media3 回调，只改队列拥有的模式及命令事件。 */
    private inner class QueueListener(private val listener: Player.Listener) : Player.Listener by listener {
        private var commands = this@QueueSessionPlayer.availableCommands
        private var repeat = this@QueueSessionPlayer.repeatMode
        private var shuffle = this@QueueSessionPlayer.shuffleModeEnabled
        private val changed = mutableSetOf<Int>()

        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
            val actual = this@QueueSessionPlayer.availableCommands
            if (commands != actual) {
                commands = actual
                changed.add(Player.EVENT_AVAILABLE_COMMANDS_CHANGED)
                listener.onAvailableCommandsChanged(actual)
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            val actual = this@QueueSessionPlayer.repeatMode
            if (repeat != actual) {
                repeat = actual
                changed.add(Player.EVENT_REPEAT_MODE_CHANGED)
                listener.onRepeatModeChanged(actual)
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            val actual = this@QueueSessionPlayer.shuffleModeEnabled
            if (shuffle != actual) {
                shuffle = actual
                changed.add(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)
                listener.onShuffleModeEnabledChanged(actual)
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            val flags = FlagSet.Builder()
            for (index in 0 until events.size()) {
                val event = events.get(index)
                if (event !in QUEUE_EVENTS || event in changed) flags.add(event)
            }
            changed.clear()
            val accepted = flags.build()
            if (accepted.size() > 0) listener.onEvents(this@QueueSessionPlayer, Player.Events(accepted))
        }

        fun logicalStateChanged() {
            onAvailableCommandsChanged(this@QueueSessionPlayer.availableCommands)
            onRepeatModeChanged(this@QueueSessionPlayer.repeatMode)
            onShuffleModeEnabledChanged(this@QueueSessionPlayer.shuffleModeEnabled)
            if (changed.isEmpty()) return
            val flags = FlagSet.Builder()
            changed.forEach { flags.add(it) }
            changed.clear()
            listener.onEvents(this@QueueSessionPlayer, Player.Events(flags.build()))
        }
    }

    companion object {
        private val QUEUE_EVENTS = setOf(Player.EVENT_AVAILABLE_COMMANDS_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)
    }
}
