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
import io.github.onlinemo.meloxwatch.data.listening.ListeningEpochs
import io.github.onlinemo.meloxwatch.model.QueueEntry
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The service owns one sampler and one coalescing writer. Closing consumes captures, never a released player. */
@UnstableApi
internal class ServiceListeningObserver(
    player: Player,
    private val repository: ListeningRepository,
    private val scope: CoroutineScope,
    private val onFailure: (ListeningIssue) -> Unit,
) {
    var onContinuousRoundReady: () -> Unit = {}
    private data class ActiveRound(val entryId: String, val progress: ListeningProgressRound)
    private data class CapturedSample(val sample: ListeningSample?, val reset: Boolean = false)
    private class StartingRound(val entry: QueueEntry, val epochs: ListeningEpochs) {
        val samples = mutableListOf<CapturedSample>()
        var saturated = false
    }
    private val pending = ArrayDeque<ListeningProgressRound>()
    private var current: ActiveRound? = null
    private var deferred: ActiveRound? = null
    private var starting: StartingRound? = null
    private var automaticStart: Job? = null
    private var lastKnownEpochs: ListeningEpochs? = null
    private var sampling: Job? = null
    private var detached = false
    private var attachedPlayer: Player? = player
    private var backendGeneration = 0L
    private var listener: Player.Listener? = null
    private var lastWrittenElapsedMs = 0L
    private val writes = Mutex()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private fun backendListener(bound: Player, generation: Long) = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isCurrentBinding(bound, generation)) return
            capture()
            if (isPlaying) startSampling() else { sampling?.cancel(); sampling = null; requests.trySend(Unit) }
        }
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (!isCurrentBinding(bound, generation)) return
            val entryId = current?.entryId ?: starting?.entry?.queueEntryId
            if (entryId != null && oldPosition.mediaItem?.mediaId == entryId) {
                capture(ListeningSample(System.currentTimeMillis(), SystemClock.elapsedRealtime(), ZoneId.systemDefault().id,
                    oldPosition.positionMs.coerceAtLeast(0L), false, bound.playbackParameters.speed))
                resetCurrent(sampleForCurrent())
            }
        }
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            if (!isCurrentBinding(bound, generation)) return
            val sample = sampleForCurrent()
            val oldSpeed = current?.progress?.baselineSpeed ?: starting?.samples?.lastOrNull()?.sample?.speed
            if (sample != null && oldSpeed != null) capture(sample.copy(speed = oldSpeed))
            resetCurrent(sample)
            requests.trySend(Unit)
        }
        override fun onPlayerError(error: PlaybackException) {
            if (isCurrentBinding(bound, generation)) { capture(); requests.trySend(Unit) }
        }
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (isCurrentBinding(bound, generation)) { capture(); if (current?.progress?.hasUnacknowledgedProgress == true) requests.trySend(Unit) }
        }
    }
    private val writer: Job
    private val epochObserver: Job?

    init {
        check(Looper.myLooper() == Looper.getMainLooper() && player.applicationLooper == Looper.getMainLooper())
        listener = backendListener(player, backendGeneration).also(player::addListener)
        epochObserver = repository.observedEpochs?.let { epochs ->
            scope.launch(Dispatchers.Main.immediate) {
                epochs.collect { observed ->
                    if (observed != null) lastKnownEpochs = observed
                    if (!detached) onContinuousRoundReady()
                }
            }
        }
        writer = scope.launch(Dispatchers.Main.immediate) {
            while (isActive) {
                requests.receive()
                flush()
            }
        }
    }

    suspend fun beginRound(entry: QueueEntry) {
        if (detached) return
        automaticStart?.join()
        if (detached) return
        current?.let { active ->
            if (active.progress.hasUnacknowledgedProgress) pending.addLast(active.progress)
        }
        current = null
        deferred = null
        requests.trySend(Unit)
        try {
            val epochs = repository.state()
            lastKnownEpochs = epochs
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

    /** 一个未完成 epoch 读取与最多100个真实样本；它未释放前不能再授权自动边界。 */
    fun canBeginContinuousRound(): Boolean = !detached && starting == null && automaticStart == null &&
        deferred == null && pending.size < MAX_PENDING_ROUNDS &&
        (repository.observedEpochs?.value != null || repository.observedEpochs == null && lastKnownEpochs != null)

    /** 已捕获当前轮次可在无future尾部等待；清除失效和失败不保留该例外。 */
    fun canKeepContinuousTail(): Boolean = !detached && attachedPlayer != null &&
        (current != null || starting != null) && deferred == null && pending.size < MAX_PENDING_ROUNDS &&
        (repository.observedEpochs?.value != null || repository.observedEpochs == null && lastKnownEpochs != null)

    /**
     * 真实 AUTO 回调冻结已确认 epoch 并捕获新轮次；异步新读只供后续轮次使用。
     * 短曲在 IO 期间结束也保留其真实采样；
     * 不重启轮次、不复用旧 epoch，不用媒体位移猜未观测聆听。只由队列过轨 witness 调用一次。
     */
    fun beginContinuousRound(entry: QueueEntry, positionMs: Long) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (detached || starting != null || automaticStart != null || deferred != null || pending.size >= MAX_PENDING_ROUNDS) {
            onFailure(ListeningIssue.STORAGE_UNAVAILABLE)
            return
        }
        current?.let { if (it.progress.hasUnacknowledgedProgress) pending.addLast(it.progress) }
        current = null
        deferred = null
        // 清除可在已授权的播放线程过轨后先失效缓存；此时仍冻结旧已确认 epoch，不把旧采样带回新代次。
        val epochs = repository.observedEpochs?.value ?: lastKnownEpochs
        if (epochs == null) {
            onFailure(ListeningIssue.STORAGE_UNAVAILABLE)
            return
        }
        val captured = StartingRound(entry, epochs)
        starting = captured
        sampleForCurrent()?.let { captured.samples += CapturedSample(it.copy(positionMs = positionMs.coerceAtLeast(0L)), reset = true) }
        requests.trySend(Unit)
        val job = scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.LAZY) {
            try {
                lastKnownEpochs = repository.state()
                if (!detached && starting === captured) sampleForCurrent()?.let { recordStartingSample(CapturedSample(it)) }
                val round = ActiveRound(entry.queueEntryId, ListeningProgressRound(UUID.randomUUID().toString(),
                    entry.trackKey, entry.displayName, captured.epochs, entry.durationMs))
                for (observed in captured.samples) {
                    if (observed.reset) round.progress.reset(observed.sample)
                    else observed.sample?.let { round.progress.sample(it) }
                }
                if (detached) {
                    if (round.progress.hasUnacknowledgedProgress && pending.size < MAX_PENDING_ROUNDS) pending.addLast(round.progress)
                } else if (pending.size >= MAX_PENDING_ROUNDS) {
                    deferred = round
                    onFailure(ListeningIssue.STORAGE_UNAVAILABLE)
                } else {
                    current = round
                }
                requests.trySend(Unit)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { onFailure((error as? ListeningException)?.issue ?: ListeningIssue.STORAGE_UNAVAILABLE) }
            finally {
                if (starting === captured) starting = null
                automaticStart = null
                if (!detached) {
                    if (attachedPlayer?.isPlaying == true) startSampling()
                    onContinuousRoundReady()
                }
            }
        }
        automaticStart = job
        job.start()
        if (!detached && attachedPlayer?.isPlaying == true) startSampling()
    }

    /** Capture and stop the sampler before release; the durable round remains the same. */
    fun unbindBackend() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (detached || attachedPlayer == null) return
        val bound = attachedPlayer
        val oldListener = listener
        try { capture() }
        finally {
            // No failure from a player getter or listener removal may leave a sampler attached
            // to the instance the service is about to release.
            backendGeneration += 1L
            attachedPlayer = null
            listener = null
            sampling?.cancel(); sampling = null
            resetCurrent(null)
            requests.trySend(Unit)
            if (bound != null && oldListener != null) bound.removeListener(oldListener)
        }
    }

    fun bindBackend(backend: Player) {
        check(Looper.myLooper() == Looper.getMainLooper() && backend.applicationLooper == Looper.getMainLooper())
        check(!detached && attachedPlayer == null)
        backendGeneration += 1L
        attachedPlayer = backend
        listener = backendListener(backend, backendGeneration).also(backend::addListener)
        resetCurrent(sampleForCurrent())
        if (backend.isPlaying) startSampling()
    }

    fun detach() {
        if (detached) return
        try { unbindBackend() }
        finally {
            detached = true
            deferred = null
            epochObserver?.cancel()
        }
    }

    suspend fun close() {
        check(detached)
        automaticStart?.join()
        epochObserver?.join()
        writer.cancelAndJoin()
        requests.close()
        flush()
    }

    private fun startSampling() {
        if (sampling?.isActive == true || detached) return
        sampling = scope.launch(Dispatchers.Main.immediate) {
            while (isActive && !detached && attachedPlayer?.isPlaying == true) {
                delay(SAMPLE_INTERVAL_MS)
                if (!detached) capture()
            }
        }
    }

    private fun capture() {
        if (detached) return
        if (current == null && starting == null) return
        val sample = sampleForCurrent() ?: run { resetCurrent(null); return }
        capture(sample)
    }

    private fun capture(sample: ListeningSample) {
        if (detached) return
        val round = current ?: run { recordStartingSample(CapturedSample(sample)); return }
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
        val bound = attachedPlayer ?: return null
        val entryId = current?.entryId ?: starting?.entry?.queueEntryId ?: return null
        if (detached || bound.currentMediaItem?.mediaId != entryId) return null
        return ListeningSample(System.currentTimeMillis(), SystemClock.elapsedRealtime(), ZoneId.systemDefault().id,
            bound.currentPosition.coerceAtLeast(0L), bound.isPlaying, bound.playbackParameters.speed,
            bound.duration.takeIf { it != C.TIME_UNSET && it > 0L })
    }

    private fun resetCurrent(sample: ListeningSample?) {
        current?.progress?.reset(sample)
        if (current == null) recordStartingSample(CapturedSample(sample, reset = true))
    }

    private fun recordStartingSample(sample: CapturedSample) {
        val captured = starting ?: return
        if (captured.samples.size < MAX_STARTING_SAMPLES) captured.samples += sample
        else {
            // 达上限仍保留最后真实观察，但不把丢失的中间观察猜成连续聆听。
            captured.samples[captured.samples.lastIndex] = sample.copy(reset = true)
            if (!captured.saturated) {
                captured.saturated = true
                onFailure(ListeningIssue.STORAGE_UNAVAILABLE)
            }
        }
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
                if (attachedPlayer?.isPlaying == true) startSampling()
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { onFailure((error as? ListeningException)?.issue ?: ListeningIssue.STORAGE_UNAVAILABLE) }
    }

    private suspend fun write(round: ListeningProgressRound) {
        val update = round.update() ?: return
        val commit = repository.submit(update)
        round.acknowledge(update, commit.acceptedListeningMs)
    }

    private fun isCurrentBinding(bound: Player, generation: Long): Boolean =
        !detached && attachedPlayer === bound && backendGeneration == generation

    companion object {
        private const val SAMPLE_INTERVAL_MS = 1000L
        private const val WRITE_INTERVAL_MS = 5000L
        private const val MAX_PENDING_ROUNDS = 8
        private const val MAX_STARTING_SAMPLES = 100
    }
}
