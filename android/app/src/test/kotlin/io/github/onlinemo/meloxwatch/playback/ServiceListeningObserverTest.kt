package io.github.onlinemo.meloxwatch.playback

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import io.github.onlinemo.meloxwatch.data.listening.ListeningCommit
import io.github.onlinemo.meloxwatch.data.listening.ListeningDayCursor
import io.github.onlinemo.meloxwatch.data.listening.ListeningDayPage
import io.github.onlinemo.meloxwatch.data.listening.ListeningEpochs
import io.github.onlinemo.meloxwatch.data.listening.ListeningException
import io.github.onlinemo.meloxwatch.data.listening.ListeningHistoryCursor
import io.github.onlinemo.meloxwatch.data.listening.ListeningHistoryPage
import io.github.onlinemo.meloxwatch.data.listening.ListeningIssue
import io.github.onlinemo.meloxwatch.data.listening.ListeningRepository
import io.github.onlinemo.meloxwatch.data.listening.ListeningRoundUpdate
import io.github.onlinemo.meloxwatch.data.listening.ListeningSummary
import io.github.onlinemo.meloxwatch.data.listening.ListeningTodaySummary
import io.github.onlinemo.meloxwatch.data.listening.ListeningTrackCursor
import io.github.onlinemo.meloxwatch.data.listening.ListeningTrackPage
import io.github.onlinemo.meloxwatch.data.listening.ListeningTrackSummary
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.lang.reflect.Proxy
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Main-Looper observer lifecycle and delayed repository ACKs; audio output requires separate device evidence. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ServiceListeningObserverTest {
    private lateinit var backend: ControlledPlayer
    private lateinit var repository: ControlledListeningRepository
    private lateinit var scope: CoroutineScope
    private lateinit var observer: ServiceListeningObserver
    private val failures = mutableListOf<ListeningIssue>()

    @Before
    fun createObserver() {
        backend = ControlledPlayer()
        repository = ControlledListeningRepository()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        observer = ServiceListeningObserver(backend.player, repository, scope, failures::add)
    }

    @After
    fun releaseObserver() = runBlocking {
        repository.releaseGates()
        observer.detach()
        if (!backend.released) backend.player.release()
        try { observer.close() } finally { scope.cancel() }
        assertEquals("no getter, callback or final flush may touch a released player", 0, backend.lateCalls)
    }

    @Test
    fun preparingPausedAndRepeatedCallbacksCannotCreateSuccessfulHistory() = runBlocking {
        val entry = entry(0)
        backend.choose(entry)
        observer.beginRound(entry)
        repeat(3) {
            advance(1000L, (it + 1) * 1000L)
            backend.notifyPlaying(false)
            backend.notifyTimeline()
        }
        observer.detach()
        backend.player.release()
        observer.close()

        assertTrue(repository.attempts.isEmpty())
        assertTrue(repository.accepted.isEmpty())
        assertTrue(failures.isEmpty())
    }

    @Test
    fun pauseSeekAndDuplicateCallbacksCountOnlyObservedAdvanceInOneRound() = runBlocking {
        beginPlaying(entry(0))
        advance(1000L, 1000L)
        advance(200L, 1200L)
        backend.notifyPlaying(false)
        backend.notifyPlaying(false)
        advance(2000L, 1200L)
        backend.notifyPlaying(true)
        advance(1000L, 2200L)
        advance(200L, 2400L)
        val old = backend.positionInfo()
        backend.positionMs = 100_000L
        val new = backend.positionInfo()
        backend.notifyDiscontinuity(old, new)
        backend.notifyDiscontinuity(old, new)
        advance(800L, 100_800L)
        advance(200L, 101_000L)
        backend.notifyPlaying(false)
        backend.notifyPlaying(false)
        backend.notifyTimeline()

        assertEquals(1, repository.accepted.size)
        assertEquals(3400L, repository.totalListeningMs)
        val writes = repository.attempts.size
        backend.notifyTimeline()
        backend.notifyPlaying(false)
        assertEquals("ACKed progress must not be submitted again", writes, repository.attempts.size)
        assertEquals(3400L, repository.accepted.values.single().daySegments.sumOf { it.listeningMs })
        assertTrue(failures.isEmpty())
    }

    @Test
    fun elapsedTimeAtDoubleSpeedIsNotDoubleListeningTime() = runBlocking {
        backend.speed = 2f
        beginPlaying(entry(0))
        repeat(3) { advance(1000L, (it + 1) * 2000L) }
        backend.notifyPlaying(false)

        assertEquals(3000L, repository.totalListeningMs)
        assertEquals(1, repository.accepted.size)
    }

    @Test
    fun automaticTransitionCapturesTheOldItemsTailBeforeBeginningTheNewRound() = runBlocking {
        val first = entry(0)
        val second = entry(1)
        beginPlaying(first)
        advance(1000L, 1000L)
        advance(200L, 1200L)
        val old = backend.positionInfo()
        backend.choose(second)
        val new = backend.positionInfo()
        backend.notifyDiscontinuity(old, new, Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        backend.notifyDiscontinuity(old, new, Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        observer.beginRound(second)
        advance(800L, 800L)
        advance(1000L, 1800L)
        backend.notifyPlaying(false)

        assertEquals(2, repository.accepted.size)
        val tracks = repository.accepted.values.associateBy { it.trackKey }
        assertEquals(1200L, requireNotNull(tracks[first.trackKey]).totalListeningMs)
        assertEquals(1800L, requireNotNull(tracks[second.trackKey]).totalListeningMs)
        assertEquals(3000L, repository.totalListeningMs)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun aContinuousShortRoundRetainsRealSamplesWhileItsNewEpochReadIsPending() = runBlocking {
        val first = entry(0)
        val second = entry(1)
        beginPlaying(first)
        advance(1000L, 1000L)
        advance(200L, 1200L)
        val old = backend.positionInfo()
        backend.choose(second)
        val next = backend.positionInfo()
        backend.notifyDiscontinuity(old, next, Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        val gate = repository.blockNextState()
        observer.beginContinuousRound(second, 0L)
        assertTrue(gate.entered.isCompleted)
        assertFalse(observer.canBeginContinuousRound())
        advance(200L, 200L)
        backend.notifyPlaying(false)
        gate.release.complete(Unit)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        observer.detach()
        backend.player.release()
        observer.close()
        val tracks = repository.accepted.values.associateBy { it.trackKey }
        assertEquals(2, tracks.size)
        assertEquals(1200L, requireNotNull(tracks[first.trackKey]).totalListeningMs)
        assertEquals(200L, requireNotNull(tracks[second.trackKey]).totalListeningMs)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun aLateEpochReadCannotRebindAStartedContinuousRoundsCapturedSamples() = runBlocking {
        val first = entry(0)
        val second = entry(1)
        beginPlaying(first)
        advance(1000L, 1000L)
        val old = backend.positionInfo()
        backend.choose(second)
        val next = backend.positionInfo()
        backend.notifyDiscontinuity(old, next, Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        val original = repository.state()
        val gate = repository.blockNextState()
        observer.beginContinuousRound(second, 0L)
        advance(200L, 200L)
        repository.changeEpochs(ListeningEpochs(historyEpoch = 3L, statisticsEpoch = 4L))
        backend.notifyPlaying(false)
        gate.release.complete(Unit)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        observer.detach()
        backend.player.release()
        observer.close()
        val submitted = repository.attempts.single { it.trackKey == second.trackKey }
        assertEquals(original, submitted.epochs)
        assertEquals(200L, submitted.totalListeningMs)
        assertEquals(0, backend.lateCalls)
    }

    @Test
    fun aDuplicateOldAutomaticEventCannotEraseTheIncomingRoundsFirstObservedInterval() = runBlocking {
        val first = entry(0)
        val second = entry(1)
        beginPlaying(first)
        advance(1000L, 1000L)
        val old = backend.positionInfo()
        backend.choose(second)
        val next = backend.positionInfo()
        backend.notifyDiscontinuity(old, next, Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        observer.beginContinuousRound(second, 0L)
        advance(200L, 200L)
        backend.notifyDiscontinuity(old, next, Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        advance(200L, 400L)
        backend.notifyPlaying(false)
        assertEquals(400L, repository.accepted.values.single { it.trackKey == second.trackKey }.totalListeningMs)
        assertEquals(2, repository.accepted.size)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun aCapturedContinuousRoundFinishesItsEpochReadAfterDetachWithoutTouchingTheReleasedPlayer() = runBlocking {
        val first = entry(0)
        val second = entry(1)
        beginPlaying(first)
        advance(1000L, 1000L)
        val old = backend.positionInfo()
        backend.choose(second)
        val next = backend.positionInfo()
        backend.notifyDiscontinuity(old, next, Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        val gate = repository.blockNextState()
        observer.beginContinuousRound(second, 0L)
        advance(300L, 300L)
        observer.detach()
        backend.player.release()
        gate.release.complete(Unit)
        observer.close()
        assertEquals(300L, repository.accepted.values.single { it.trackKey == second.trackKey }.totalListeningMs)
        assertEquals(0, backend.lateCalls)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun aDelayedAckRetainsNewProgressAndCoalescesWriteRequests() = runBlocking {
        val gate = repository.blockNextSubmit()
        beginPlaying(entry(0))
        advance(1000L, 1000L)
        assertTrue(gate.entered.isCompleted)
        repeat(5) { advance(1000L, (it + 2) * 1000L) }
        assertEquals(1, repository.attempts.size)
        assertEquals(0L, repository.totalListeningMs)
        gate.release.complete(Unit)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        backend.notifyPlaying(false)

        assertEquals(listOf(1000L, 6000L), repository.attempts.map { it.totalListeningMs })
        assertEquals(6000L, repository.totalListeningMs)
        assertEquals(1, repository.accepted.size)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun closeRetriesCapturedProgressAfterCancellingTheWriterWithoutPlayerAccess() = runBlocking {
        val gate = repository.blockNextSubmit()
        beginPlaying(entry(0))
        advance(1000L, 1000L)
        assertTrue(gate.entered.isCompleted)
        advance(1000L, 2000L)
        advance(200L, 2200L)
        val staleListener = backend.listeners.single()
        observer.detach()
        assertTrue(backend.listeners.isEmpty())
        backend.player.release()
        staleListener.onIsPlayingChanged(true)
        staleListener.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
        observer.close()
        advance(5000L, 2200L)

        assertTrue(gate.cancelled)
        assertEquals(listOf(1000L, 2200L), repository.attempts.map { it.totalListeningMs })
        assertEquals(2200L, repository.totalListeningMs)
        assertEquals(1, repository.accepted.size)
        assertEquals(0, backend.lateCalls)
    }

    @Test
    fun detachWhileReadingEpochsCannotAttachALateRoundToAReleasedPlayer() = runBlocking {
        val gate = repository.blockNextState()
        val entry = entry(0)
        backend.choose(entry)
        val beginning = scope.async { observer.beginRound(entry) }
        assertTrue(gate.entered.isCompleted)
        observer.detach()
        backend.player.release()
        gate.release.complete(Unit)
        beginning.await()
        observer.close()

        assertTrue(repository.attempts.isEmpty())
        assertTrue(backend.listeners.isEmpty())
        assertEquals(0, backend.lateCalls)
    }

    @Test
    fun repeatedStorageFailureKeepsOnlyABoundedBacklogAndDoesNotPauseAudio() = runBlocking {
        repository.failWrites = true
        beginPlaying(entry(0))
        advance(1000L, 1000L)
        for (index in 1 until 24) {
            val next = entry(index)
            backend.choose(next)
            observer.beginRound(next)
            advance(1000L, 1000L)
        }
        assertTrue(backend.playing)
        assertTrue(ListeningIssue.STORAGE_UNAVAILABLE in failures)
        repository.failWrites = false
        observer.detach()
        backend.player.release()
        observer.close()

        assertEquals("failed storage cannot retain arbitrarily many rounds in RAM", 8, repository.accepted.size)
        assertEquals(8000L, repository.totalListeningMs)
        assertEquals(0, backend.lateCalls)
    }

    @Test
    fun serviceTimeoutBoundsCloseWhenARepositoryIgnoresWriterCancellation() = runBlocking {
        val gate = repository.blockNextSubmit(ignoreCancellation = true)
        beginPlaying(entry(0))
        advance(1000L, 1000L)
        assertTrue(gate.entered.isCompleted)
        observer.detach()
        backend.player.release()
        val closing = scope.async { withTimeoutOrNull(1500L) { observer.close(); true } }
        advance(1500L, 1000L)

        assertTrue("the service close timeout must complete while storage is still blocked", closing.isCompleted)
        assertNull(closing.await())
        assertFalse(gate.release.isCompleted)
        gate.release.complete(Unit)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        observer.close()
        assertEquals(1000L, repository.totalListeningMs)
        assertEquals(0, backend.lateCalls)
    }

    @Test
    fun aListenerRemovalFailureStillFencesTheReleasedBackendAndPreservesTheRoundOnRebind() = runBlocking {
        val entry = entry(0)
        beginPlaying(entry)
        advance(1000L, 1000L)
        val previous = backend
        val stale = previous.listeners.single()
        previous.failNextRemoval = true
        try { observer.unbindBackend(); throw AssertionError("Expected listener removal failure") }
        catch (_: IllegalStateException) { }
        previous.player.release()
        stale.onIsPlayingChanged(true)
        stale.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE)

        backend = ControlledPlayer().also { it.choose(entry) }
        observer.bindBackend(backend.player)
        backend.notifyPlaying(true)
        advance(1000L, 1000L)
        backend.notifyPlaying(false)
        observer.detach()
        backend.player.release()
        observer.close()

        assertEquals(0, previous.lateCalls)
        assertEquals(0, backend.lateCalls)
        assertEquals(1, repository.accepted.size)
        assertEquals(2000L, repository.totalListeningMs)
    }

    private suspend fun beginPlaying(entry: QueueEntry) {
        backend.choose(entry)
        observer.beginRound(entry)
        backend.notifyPlaying(true)
    }

    private fun advance(milliseconds: Long, positionMs: Long) {
        backend.positionMs = positionMs
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))
    }

    private fun entry(index: Int) = QueueEntry("entry-$index", TrackKey(TrackKey.LOCAL_PROVIDER, "track-$index"),
        index.toLong(), index.toLong(), "Title $index", 60_000L)

    /** Unexpected API use is an assertion failure, including every access after release. */
    private class ControlledPlayer {
        val listeners = linkedSetOf<Player.Listener>()
        var mediaItem: MediaItem? = null
        var positionMs = 0L
        var playing = false
        var speed = 1f
        var failNextRemoval = false
        var released = false
            private set
        var lateCalls = 0
            private set
        val player: Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.singleOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "ControlledPlayer"
                else -> {
                    check(Looper.myLooper() == Looper.getMainLooper())
                    if (released) { lateCalls += 1; throw AssertionError("player.${method.name} called after release") }
                    when (method.name) {
                        "getApplicationLooper" -> Looper.getMainLooper()
                        "getCurrentMediaItem" -> mediaItem
                        "getCurrentPosition" -> positionMs
                        "getDuration" -> 60_000L
                        "isPlaying" -> playing
                        "getPlaybackParameters" -> PlaybackParameters(speed)
                        "addListener" -> { listeners.add(requireNotNull(args)[0] as Player.Listener); null }
                        "removeListener" -> {
                            if (failNextRemoval) { failNextRemoval = false; throw IllegalStateException("Controlled listener removal failure") }
                            listeners.remove(requireNotNull(args)[0] as Player.Listener); null
                        }
                        "release" -> { released = true; null }
                        else -> throw AssertionError("unexpected player method ${method.name}")
                    }
                }
            }
        } as Player

        fun choose(entry: QueueEntry) {
            mediaItem = MediaItem.Builder().setMediaId(entry.queueEntryId).setUri("content://fixture/audio/${entry.queueEntryId}").build()
            positionMs = 0L
        }

        fun notifyPlaying(value: Boolean) {
            playing = value
            listeners.toList().forEach { it.onIsPlayingChanged(value) }
        }

        fun notifyTimeline() = listeners.toList().forEach {
            it.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
        }

        fun positionInfo() = Player.PositionInfo(null, 0, mediaItem, null, 0,
            positionMs, positionMs, C.INDEX_UNSET, C.INDEX_UNSET)

        fun notifyDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int = Player.DISCONTINUITY_REASON_SEEK) = listeners.toList().forEach {
            it.onPositionDiscontinuity(old, new, reason)
        }
    }

    private class Gate(private val ignoreCancellation: Boolean = false) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var cancelled = false
            private set

        suspend fun await() {
            entered.complete(Unit)
            if (ignoreCancellation) withContext(NonCancellable) { release.await() }
            else try { release.await() }
            catch (error: CancellationException) { cancelled = true; throw error }
        }
    }

    private class ControlledListeningRepository : ListeningRepository {
        private var epochs = ListeningEpochs(historyEpoch = 2L, statisticsEpoch = 3L)
        override val observedEpochs = kotlinx.coroutines.flow.MutableStateFlow<ListeningEpochs?>(epochs)
        private val gates = mutableListOf<Gate>()
        private var stateGate: Gate? = null
        private var submitGate: Gate? = null
        var failWrites = false
        val attempts = mutableListOf<ListeningRoundUpdate>()
        val accepted = linkedMapOf<String, ListeningRoundUpdate>()
        val totalListeningMs: Long get() = accepted.values.sumOf { it.totalListeningMs }

        fun blockNextState() = Gate().also { stateGate = it; gates += it }
        fun blockNextSubmit(ignoreCancellation: Boolean = false) = Gate(ignoreCancellation).also { submitGate = it; gates += it }
        fun releaseGates() = gates.forEach { it.release.complete(Unit) }
        fun changeEpochs(next: ListeningEpochs) {
            epochs = next
            observedEpochs.value = next
        }

        override suspend fun state(): ListeningEpochs {
            stateGate?.also { stateGate = null }?.await()
            return epochs
        }

        override suspend fun submit(update: ListeningRoundUpdate): ListeningCommit {
            attempts += update
            submitGate?.also { submitGate = null }?.await()
            if (failWrites) throw ListeningException(ListeningIssue.STORAGE_UNAVAILABLE)
            val previous = accepted.put(update.roundId, update)
            check(previous == null || previous.totalListeningMs <= update.totalListeningMs)
            return ListeningCommit(epochs, update.totalListeningMs, historyRecorded = true,
                playCounted = false, wasReplay = previous?.totalListeningMs == update.totalListeningMs)
        }

        override suspend fun summary(): ListeningSummary = throw AssertionError("observer must not read UI summaries")
        override suspend fun today(localDate: String, currentZoneId: String): ListeningTodaySummary = throw AssertionError("observer must not read today's UI summary")
        override fun observeSummary(): Flow<ListeningSummary> = throw AssertionError("observer must not observe UI summaries")
        override suspend fun historyPage(after: ListeningHistoryCursor?, limit: Int): ListeningHistoryPage = throw AssertionError("unexpected history query")
        override suspend fun trackSummary(trackKey: TrackKey): ListeningTrackSummary = throw AssertionError("unexpected track query")
        override suspend fun trackPage(after: ListeningTrackCursor?, limit: Int): ListeningTrackPage = throw AssertionError("unexpected track page")
        override suspend fun dayPage(after: ListeningDayCursor?, limit: Int): ListeningDayPage = throw AssertionError("unexpected day page")
        override suspend fun clearHistory(expectedHistoryEpoch: Long): ListeningEpochs = throw AssertionError("playback cannot clear history")
        override suspend fun clearStatistics(expectedStatisticsEpoch: Long): ListeningEpochs = throw AssertionError("playback cannot clear statistics")
    }
}
