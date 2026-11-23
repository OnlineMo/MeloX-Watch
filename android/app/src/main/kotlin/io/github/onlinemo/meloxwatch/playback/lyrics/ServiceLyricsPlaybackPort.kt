package io.github.onlinemo.meloxwatch.playback.lyrics

import android.os.Looper
import androidx.annotation.MainThread
import androidx.media3.common.Player
import io.github.onlinemo.meloxwatch.model.QueueSeekBinding
import io.github.onlinemo.meloxwatch.model.QueueSeekRequest
import io.github.onlinemo.meloxwatch.ui.lyrics.entry.LyricsMediaClock
import io.github.onlinemo.meloxwatch.ui.lyrics.entry.LyricsPlaybackPort
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Snapshot of the same service player; no metadata mutation, player creation or UI ticker. */
class ServiceLyricsPlaybackSnapshot(
    val clock: LyricsMediaClock?,
    val title: String,
    val artist: String,
    val state: Int,
    val discontinuity: Long,
) {
    override fun toString(): String = "<current-service-lyrics-clock>"
}

/** UI command authority is separate from the service's read-only clock and native lifetime. */
@MainThread
class ServiceLyricsPlaybackPort(
    private val onNativeClosed: () -> Unit = {},
) : LyricsPlaybackPort, AutoCloseable {
    private val mutableChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val changes = mutableChanges.asSharedFlow()
    private val mutableServiceChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val serviceChanges = mutableServiceChanges.asSharedFlow()
    private var service: ServiceBinding? = null
    private var ui: UiBinding? = null
    private var discontinuity = 0L
    override val busy: Boolean get() = ui?.takeIf { it.isCurrent }?.port?.busy ?: false

    fun bindService(player: Player): ServiceBinding {
        require(Looper.myLooper() == player.applicationLooper)
        service?.close()
        check(discontinuity < Long.MAX_VALUE)
        discontinuity++
        val binding = ServiceBinding(player)
        service = binding
        try {
            player.addListener(binding.listener)
            mutableChanges.tryEmit(Unit)
            mutableServiceChanges.tryEmit(Unit)
            return binding
        } catch (error: Throwable) {
            try { binding.close() }
            catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
            throw error
        }
    }

    fun bindUi(port: LyricsPlaybackPort, scope: CoroutineScope): UiBinding {
        ui?.close()
        return UiBinding(port).also { binding ->
            ui = binding
            binding.relay = scope.launch {
                port.changes.collect { if (binding.isCurrent) mutableChanges.tryEmit(Unit) }
            }
            mutableChanges.tryEmit(Unit)
        }
    }

    inner class UiBinding internal constructor(internal val port: LyricsPlaybackPort) : AutoCloseable {
        private var open = true
        internal var relay: Job? = null
        val isCurrent: Boolean get() = open && ui === this
        override fun close() {
            if (!open) return
            open = false
            relay?.cancel()
            relay = null
            if (ui === this) { ui = null; mutableChanges.tryEmit(Unit) }
        }
    }

    inner class ServiceBinding internal constructor(private val player: Player) : AutoCloseable {
        private var open = true
        val isCurrent: Boolean get() = open && service === this
        internal val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (!isCurrent) return
                if (events.contains(Player.EVENT_POSITION_DISCONTINUITY) ||
                    events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                    events.contains(Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)) {
                    if (discontinuity == Long.MAX_VALUE) {
                        close()
                        return
                    }
                    discontinuity++
                }
                mutableChanges.tryEmit(Unit)
                mutableServiceChanges.tryEmit(Unit)
            }
        }

        internal fun read(): ServiceLyricsPlaybackSnapshot? {
            if (!isCurrent || Looper.myLooper() != player.applicationLooper) return null
            return try {
                val id = player.currentMediaItem?.mediaId?.takeIf(String::isNotBlank)
                val speed = player.playbackParameters.speed
                val clock = if (id == null || !speed.isFinite() || speed <= 0f) null else LyricsMediaClock(
                    id, player.currentPosition.coerceAtLeast(0L), player.duration.takeIf { it >= 0L },
                    player.isPlaying, speed, player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
                ServiceLyricsPlaybackSnapshot(clock, player.mediaMetadata.title?.toString().orEmpty(),
                    player.mediaMetadata.artist?.toString().orEmpty(), player.playbackState, discontinuity)
            } catch (_: RuntimeException) { null }
        }

        override fun close() {
            if (!open) return
            open = false
            try { player.removeListener(listener) }
            finally {
                if (service === this) {
                    service = null
                    mutableChanges.tryEmit(Unit)
                    mutableServiceChanges.tryEmit(Unit)
                    onNativeClosed()
                }
            }
        }
    }

    fun readService(): ServiceLyricsPlaybackSnapshot? = service?.read()
    override fun clock(): LyricsMediaClock? = readService()?.clock ?: ui?.takeIf { it.isCurrent }?.port?.clock()
    override suspend fun acquireSeekBinding(): QueueSeekBinding? =
        ui?.takeIf { it.isCurrent }?.port?.acquireSeekBinding()
    override fun isSeekBindingCurrent(binding: QueueSeekBinding): Boolean =
        ui?.takeIf { it.isCurrent }?.port?.isSeekBindingCurrent(binding) == true
    override suspend fun dispatchSeek(request: QueueSeekRequest): Boolean =
        ui?.takeIf { it.isCurrent }?.port?.dispatchSeek(request) == true
    override fun close() {
        ui?.close()
        service?.close()
    }
}
