package io.github.onlinemo.meloxwatch.playback

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.onlinemo.meloxwatch.model.QueueRepeatMode

/** 会话身份固定；真实后端只由 Service 绑定/释放，导航始终使用完整持久队列。 */
@UnstableApi
internal class QueueSessionPlayer private constructor(
    private val facade: SessionFacade,
    private val coordinator: QueuePlaybackCoordinator,
) : ForwardingPlayer(facade) {
    constructor(coordinator: QueuePlaybackCoordinator) : this(SessionFacade(coordinator), coordinator)

    // SimpleBasePlayer 合并 false→false；明确 pause 仍必须撤销尚未到来的 focus gain。
    override fun play() = coordinator.playFromSession()
    override fun pause() = coordinator.pauseFromSession()
    override fun setPlayWhenReady(playWhenReady: Boolean) = if (playWhenReady) play() else pause()
    override fun stop() = coordinator.stopFromSession()
    override fun prepare() = coordinator.prepareFromSession()

    // BasePlayer 根据三项窗口推断的尾部不能代表完整队列的尾部。
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

    // Session/controller 的 release 不拥有真实后端或 facade 的生命周期。
    override fun release() = Unit

    fun logicalStateChanged() = facade.refresh()
    fun positionSourceChanging() = facade.freezePositionClocks()
    fun positionSourceChanged(reason: Int? = null, positionMs: Long = 0L) = facade.resumePositionClocks(reason, positionMs)
    fun backendPositionDiscontinuity(oldPositionMs: Long, newPositionMs: Long, reason: Int) =
        facade.backendPositionDiscontinuity(oldPositionMs, newPositionMs, reason)
    fun detachListeners() = facade.detach()

    /** SimpleBasePlayer 负责命令/状态事件；不会把未确认的异步操作显示为成功。 */
    @UnstableApi
    private class SessionFacade(private val coordinator: QueuePlaybackCoordinator) : SimpleBasePlayer(Looper.getMainLooper()) {
        private var detached = false
        private var positionClocks: PositionClocks? = null
        private var pendingPositionDiscontinuity: Pair<Int, Long>? = null

        override fun getState(): State {
            val saved = coordinator.state.value.snapshot
            val builder = State.Builder()
                .setRepeatMode(when (saved?.mode?.repeat) {
                    QueueRepeatMode.LIST -> Player.REPEAT_MODE_ALL
                    QueueRepeatMode.SINGLE -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                })
                .setShuffleModeEnabled(saved?.mode?.shuffle == true)
            if (detached) {
                // SimpleBasePlayer.release is command-gated. This command exists only during
                // owner detach; the public session wrapper never forwards controller release.
                return builder.setAvailableCommands(Player.Commands.Builder().add(Player.COMMAND_RELEASE).build()).build()
            }
            val backend = coordinator.sessionBackend() ?: run {
                disconnectPositionClocks()
                return builder.build()
            }
            val generation = coordinator.sessionBackendGeneration()
            val available = backend.availableCommands
            val commands = Player.Commands.Builder()
            for (command in READ_COMMANDS) if (available.contains(command)) commands.add(command)
            if (coordinator.acceptsSessionCommands()) {
                commands.addAll(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_PREPARE)
                if (coordinator.canSeekFromSession()) commands.addAll(
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                    Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
                )
                if (coordinator.canNavigate(-1)) commands.addAll(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                if (coordinator.canNavigate(1)) commands.addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            }
            val reference = coordinator.sessionPausedOnlineReference()
            if (reference != null) {
                disconnectPositionClocks()
                commands.addAll(Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA)
                val durationUs = reference.durationMs?.takeIf { it in 1..Long.MAX_VALUE / 1000L }?.times(1000L) ?: C.TIME_UNSET
                return builder.setAvailableCommands(commands.build())
                    .setPlaylist(listOf(MediaItemData.Builder(reference.mediaItem.mediaId).setMediaItem(reference.mediaItem)
                        .setDurationUs(durationUs).setIsSeekable(false).build()))
                    .setCurrentMediaItemIndex(0).setPlaybackState(Player.STATE_IDLE)
                    .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                    .setContentPositionMs(PositionSupplier { reference.positionMs })
                    .setContentBufferedPositionMs(PositionSupplier { reference.positionMs })
                    .setTotalBufferedDurationMs(PositionSupplier.ZERO).build()
            }
            val timeline = backend.currentTimeline
            val error = backend.playerError
            val playbackState = when {
                error != null -> Player.STATE_IDLE
                timeline.isEmpty && backend.playbackState !in listOf(Player.STATE_IDLE, Player.STATE_ENDED) -> Player.STATE_IDLE
                else -> backend.playbackState
            }
            val position = backend.contentPosition
            val buffered = backend.contentBufferedPosition
            val totalBuffered = backend.totalBufferedDuration
            val mediaId = backend.currentMediaItem?.mediaId
            val previousClocks = positionClocks
            val clocks = if (previousClocks != null && previousClocks.backend === backend &&
                previousClocks.generation == generation && previousClocks.mediaId == mediaId) {
                previousClocks.update(position, buffered, totalBuffered)
                previousClocks
            } else {
                disconnectPositionClocks()
                PositionClocks(backend, generation, mediaId, position, buffered, totalBuffered) {
                    coordinator.isCurrentBackend(backend, generation) && backend.currentMediaItem?.mediaId == mediaId
                }.also { positionClocks = it }
            }
            builder.setAvailableCommands(commands.build())
                .setPlaylist(timeline, if (available.contains(Player.COMMAND_GET_TRACKS)) backend.currentTracks else Tracks.EMPTY,
                    backend.mediaMetadata)
                .setPlaylistMetadata(backend.playlistMetadata)
                .setCurrentMediaItemIndex(if (timeline.isEmpty) C.INDEX_UNSET else backend.currentMediaItemIndex)
                .setPlaybackState(playbackState)
                .setPlayWhenReady(backend.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaybackSuppressionReason(backend.playbackSuppressionReason)
                .setPlayerError(error)
                .setIsLoading(playbackState in listOf(Player.STATE_BUFFERING, Player.STATE_READY) && backend.isLoading)
                .setPlaybackParameters(backend.playbackParameters)
                .setSeekBackIncrementMs(backend.seekBackIncrement)
                .setSeekForwardIncrementMs(backend.seekForwardIncrement)
                .setContentPositionMs(clocks.contentPosition)
                .setContentBufferedPositionMs(clocks.contentBufferedPosition)
                .setTotalBufferedDurationMs(clocks.totalBufferedDuration)
            if (available.contains(Player.COMMAND_GET_VOLUME)) builder.setVolume(backend.volume)
            if (available.contains(Player.COMMAND_GET_AUDIO_ATTRIBUTES)) builder.setAudioAttributes(backend.audioAttributes)
            pendingPositionDiscontinuity?.let { (reason, actualPosition) -> builder.setPositionDiscontinuity(reason, actualPosition) }
            return builder.build().also { pendingPositionDiscontinuity = null }
        }

        override fun getPlaceholderState(suggestedPlaceholderState: State): State = getState()

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            if (playWhenReady) coordinator.playFromSession() else coordinator.pauseFromSession()
            return immediate()
        }

        override fun handlePrepare(): ListenableFuture<*> {
            coordinator.prepareFromSession()
            return immediate()
        }

        override fun handleStop(): ListenableFuture<*> {
            coordinator.stopFromSession()
            return immediate()
        }

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            when (seekCommand) {
                Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> coordinator.navigateFromSession(1)
                Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> coordinator.navigateFromSession(-1)
                else -> coordinator.seekFromSession(mediaItemIndex, if (positionMs == C.TIME_UNSET) 0L else positionMs)
            }
            return immediate()
        }

        override fun handleRelease(): ListenableFuture<*> = immediate()

        fun refresh() { if (!detached) invalidateState() }

        fun freezePositionClocks() { if (!detached) positionClocks?.freeze() }

        fun resumePositionClocks(reason: Int?, positionMs: Long) {
            if (detached) return
            if (reason != null || positionClocks?.frozen == true) disconnectPositionClocks()
            if (reason != null) pendingPositionDiscontinuity = reason to positionMs.coerceAtLeast(0L)
            refresh()
        }

        fun backendPositionDiscontinuity(oldPositionMs: Long, newPositionMs: Long, reason: Int) {
            if (detached) return
            positionClocks?.disconnect(oldPositionMs)
            positionClocks = null
            pendingPositionDiscontinuity = reason to newPositionMs.coerceAtLeast(0L)
            refresh()
        }

        private fun disconnectPositionClocks() {
            positionClocks?.disconnect()
            positionClocks = null
        }

        fun detach() {
            if (detached) return
            detached = true
            disconnectPositionClocks()
            pendingPositionDiscontinuity = null
            try { invalidateState() }
            finally { release() }
        }

        private fun immediate(): ListenableFuture<Unit> = Futures.immediateFuture(Unit)

        /** Old states must retain their final clocks, including a seek within the same media ID. */
        private class PositionClocks(
            val backend: Player,
            val generation: Long,
            val mediaId: String?,
            positionMs: Long,
            bufferedMs: Long,
            totalBufferedMs: Long,
            private val valid: () -> Boolean,
        ) {
            private var lastPosition = positionMs
            private var lastBuffered = bufferedMs
            private var lastTotalBuffered = totalBufferedMs
            var frozen = false
                private set
            // The generation/identity guard runs before any getter on a released backend.
            val contentPosition = SimpleBasePlayer.LivePositionSupplier(SimpleBasePlayer.PositionSupplier {
                if (valid()) backend.contentPosition.also { lastPosition = it } else lastPosition
            })
            val contentBufferedPosition = SimpleBasePlayer.LivePositionSupplier(SimpleBasePlayer.PositionSupplier {
                if (valid()) backend.contentBufferedPosition.also { lastBuffered = it } else lastBuffered
            })
            val totalBufferedDuration = SimpleBasePlayer.LivePositionSupplier(SimpleBasePlayer.PositionSupplier {
                if (valid()) backend.totalBufferedDuration.also { lastTotalBuffered = it } else lastTotalBuffered
            })

            fun update(positionMs: Long, bufferedMs: Long, totalBufferedMs: Long) {
                if (frozen) return
                lastPosition = positionMs
                lastBuffered = bufferedMs
                lastTotalBuffered = totalBufferedMs
            }

            fun freeze() {
                if (frozen) return
                try {
                    contentPosition.get()
                    contentBufferedPosition.get()
                    totalBufferedDuration.get()
                } finally {
                    // Even a failing live getter must disconnect every old supplier.
                    disconnect()
                }
            }

            fun disconnect(finalPositionMs: Long? = null) {
                if (finalPositionMs != null) lastPosition = finalPositionMs.coerceAtLeast(0L)
                contentPosition.disconnect(lastPosition.coerceAtLeast(0L))
                contentBufferedPosition.disconnect(lastBuffered.coerceAtLeast(0L))
                totalBufferedDuration.disconnect(lastTotalBuffered.coerceAtLeast(0L))
                frozen = true
            }
        }

        companion object {
            private val READ_COMMANDS = intArrayOf(
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_TRACKS, Player.COMMAND_GET_VOLUME, Player.COMMAND_GET_AUDIO_ATTRIBUTES,
            )
        }
    }
}
