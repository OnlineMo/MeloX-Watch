package io.github.onlinemo.meloxwatch.playback

import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import io.github.onlinemo.meloxwatch.data.listening.ListeningException
import io.github.onlinemo.meloxwatch.data.listening.ListeningIssue
import io.github.onlinemo.meloxwatch.data.listening.ListeningRepository
import io.github.onlinemo.meloxwatch.model.QueueEntry
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The service owns one sampler and one coalescing writer. Closing consumes captures, never a released player. */
@UnstableApi
internal class ServiceListeningObserver(
    private val player: Player,
    private val repository: ListeningRepository,
    private val scope: CoroutineScope,
    private val onFailure: (ListeningIssue) -> Unit,
) {
    private data class ActiveRound(val entryId: String, val progress: ListeningProgressRound)
    private val pending = ArrayDeque<ListeningProgressRound>()
    private var current: ActiveRound? = null
    private var deferred: ActiveRound? = null
    private var sampling: Job? = null
    private var detached = false
    private var lastWrittenElapsedMs = 0L
    private val writes = Mutex()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (detached) return
            capture()
            if (isPlaying) startSampling() else { sampling?.cancel(); sampling = null; requests.trySend(Unit) }
        }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (detached) return
            val round = current
            if (round != null && oldPosition.mediaItem?.mediaId == round.entryId) {
                capture(ListeningSample(System.currentTimeMillis(), SystemClock.elapsedRealtime(), ZoneId.systemDefault().id,
                    oldPosition.positionMs.coerceAtLeast(0L), false, player.playbackParameters.speed))
            }
            current?.progress?.reset(sampleForCurrent())
        }
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            if (detached) return
            val sample = sampleForCurrent()
            val oldSpeed = current?.progress?.baselineSpeed
            if (sample != null && oldSpeed != null) capture(sample.copy(speed = oldSpeed))
            current?.progress?.reset(sample)
            requests.trySend(Unit)
        }
        override fun onPlayerError(error: PlaybackException) {
            if (!detached) { capture(); requests.trySend(Unit) }
        }
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (!detached) { capture(); if (current?.progress?.hasUnacknowledgedProgress == true) requests.trySend(Unit) }
        }
    }
    private val writer: Job

    init {
        check(Looper.myLooper() == Looper.getMainLooper() && player.applicationLooper == Looper.getMainLooper())
        player.addListener(listener)
        writer = scope.launch(Dispatchers.Main.immediate) {
            while (isActive) {
                requests.receive()
                flush()
            }
        }
    }

    suspend fun beginRound(entry: QueueEntry) {
        if (detached) return
        current?.let { active ->
            if (active.progress.hasUnacknowledgedProgress) pending.addLast(active.progress)
        }
        current = null
        deferred = null
        requests.trySend(Unit)
        try {
            val epochs = repository.state()
            if (detached) return
            val round = ActiveRound(entry.queueEntryId, ListeningProgressRound(UUID.randomUUID().toString(),
                entry.trackKey, entry.displayName, epochs, entry.durationMs))
            // A failed writer cannot grow an unbounded history in memory or block local audio.
            if (pending.size >= MAX_PENDING_ROUNDS) {
                deferred = round
                onFailure(ListeningIssue.STORAGE_UNAVAILABLE)
            } else {
                current = round
                round.progress.reset(sampleForCurrent())
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { onFailure((error as? ListeningException)?.issue ?: ListeningIssue.STORAGE_UNAVAILABLE) }
    }

    fun detach() {
        if (detached) return
        capture()
        detached = true
        player.removeListener(listener)
        sampling?.cancel(); sampling = null
        deferred = null
    }

    suspend fun close() {
        check(detached)
        writer.cancelAndJoin()
        requests.close()
        flush()
    }

    private fun startSampling() {
        if (sampling?.isActive == true || detached) return
        sampling = scope.launch(Dispatchers.Main.immediate) {
            while (isActive && !detached && player.isPlaying) {
                delay(SAMPLE_INTERVAL_MS)
                if (!detached) capture()
            }
        }
    }

    private fun capture() {
        if (detached) return
        val round = current ?: return
        val sample = sampleForCurrent() ?: run { round.progress.reset(null); return }
        capture(sample)
    }

    private fun capture(sample: ListeningSample) {
        if (detached) return
        val round = current ?: return
        try {
            val hadProgress = round.progress.hasProgress
            round.progress.sample(sample)
            if (round.progress.hasUnacknowledgedProgress && (!hadProgress || sample.elapsedMs - lastWrittenElapsedMs >= WRITE_INTERVAL_MS)) {
                requests.trySend(Unit)
                lastWrittenElapsedMs = sample.elapsedMs
            }
        } catch (error: ListeningException) { onFailure(error.issue); requests.trySend(Unit) }
    }

    private fun sampleForCurrent(): ListeningSample? {
        if (detached || player.currentMediaItem?.mediaId != current?.entryId) return null
        return ListeningSample(System.currentTimeMillis(), SystemClock.elapsedRealtime(), ZoneId.systemDefault().id,
            player.currentPosition.coerceAtLeast(0L), player.isPlaying, player.playbackParameters.speed,
            player.duration.takeIf { it != C.TIME_UNSET && it > 0L })
    }

    private suspend fun flush() = writes.withLock {
        try {
            while (pending.isNotEmpty()) {
                val round = pending.first()
                write(round)
                if (!round.hasUnacknowledgedProgress) pending.removeFirst() else break
            }
            current?.progress?.let { write(it) }
            if (!detached && pending.size < MAX_PENDING_ROUNDS) deferred?.let { waiting ->
                current = waiting
                deferred = null
                waiting.progress.reset(sampleForCurrent())
                if (player.isPlaying) startSampling()
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { onFailure((error as? ListeningException)?.issue ?: ListeningIssue.STORAGE_UNAVAILABLE) }
    }

    private suspend fun write(round: ListeningProgressRound) {
        val update = round.update() ?: return
        val commit = repository.submit(update)
        round.acknowledge(update, commit.acceptedListeningMs)
    }

    companion object {
        private const val SAMPLE_INTERVAL_MS = 1000L
        private const val WRITE_INTERVAL_MS = 5000L
        private const val MAX_PENDING_ROUNDS = 8
    }
}
