package io.github.onlinemo.meloxwatch.playback

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.room.Room
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesState
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesWriteResult
import io.github.onlinemo.meloxwatch.data.preferences.ResetConfirmation
import io.github.onlinemo.meloxwatch.data.preferences.UserPreferencesRepository
import io.github.onlinemo.meloxwatch.data.queue.QueueInputPage
import io.github.onlinemo.meloxwatch.data.queue.QueueCommit
import io.github.onlinemo.meloxwatch.data.queue.QueuePageSource
import io.github.onlinemo.meloxwatch.data.queue.QueueReplacementRequest
import io.github.onlinemo.meloxwatch.data.queue.QueueStore
import io.github.onlinemo.meloxwatch.data.queue.QueueStoreException
import io.github.onlinemo.meloxwatch.data.queue.QueueStoreIssue
import io.github.onlinemo.meloxwatch.data.queue.RoomQueueStore
import io.github.onlinemo.meloxwatch.model.FrozenQueueView
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import io.github.onlinemo.meloxwatch.model.QueueCheckpoint
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueMode
import io.github.onlinemo.meloxwatch.model.QueueMutation
import io.github.onlinemo.meloxwatch.model.QueueMutationCandidate
import io.github.onlinemo.meloxwatch.model.QueueMutationRequest
import io.github.onlinemo.meloxwatch.model.QueueRepeatMode
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.model.UserPreferences
import io.github.onlinemo.meloxwatch.model.UserPreferencesDraft
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** 真实文件 Room CAS 与自动 Player 事件；受控后端不产出 PCM，不能证明波形接缝。 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class QueueGaplessPlaybackTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var database: MeloXDatabase
    private lateinit var databaseFile: File
    private lateinit var store: RoomQueueStore
    private lateinit var gated: GatedStore
    private lateinit var scope: CoroutineScope
    private lateinit var raw: WindowPlayer
    private lateinit var checked: CheckedPlayer
    private lateinit var operations: WindowOperations
    private lateinit var coordinator: QueuePlaybackCoordinator
    private val preferences = FixedPreferences()
    private val rounds = mutableListOf<String>()
    private val failures = mutableListOf<QueuePlaybackFailure>()
    private var timerActive = false
    private var timerBlocksEnd = false

    @Before fun open() {
        databaseFile = File(temporaryFolder.root, "gapless.db")
        database = openDatabase()
        store = RoomQueueStore(database, shuffleIndex = { it })
        gated = GatedStore(store)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        raw = WindowPlayer()
        checked = CheckedPlayer(raw)
        operations = WindowOperations(checked.player)
        coordinator = QueuePlaybackCoordinator(checked.player, gated, preferences, scope,
            operations = operations, onRoundChanged = { rounds += it.queueEntryId },
            onContinuousRoundChanged = { entry, _ -> rounds += entry.queueEntryId },
            mayContinuousPlayback = { !timerActive }, beforeNaturalAdvance = { timerBlocksEnd },
            onFailure = failures::add)
        onMain { coordinator.initialize() }
    }

    @After fun close() {
        gated.gate?.release?.complete(Unit)
        coordinator.detach()
        if (!checked.released) checked.player.release()
        try { onMain { coordinator.close() } }
        finally {
            scope.cancel()
            database.close()
        }
        assertEquals(0, checked.lateCalls)
    }

    @Test fun anAutomaticBoundaryPersistsTheActualSuccessorWithoutPausePrepareOrSeek() = onMain {
        seed(4)
        val before = checked.calls.toMap()
        assertTrue(coordinator.state.value.gapless.available)
        raw.completeNaturally(operations.boundaryPauseEnabled)
        idle()
        assertEquals("entry-1", store.snapshot()?.currentEntryId)
        assertEquals(listOf("entry-0", "entry-1"), rounds)
        assertEquals(before["pause"], checked.calls["pause"])
        assertEquals(before["prepare"], checked.calls["prepare"])
        assertEquals(before["seekTo"], checked.calls["seekTo"])
        assertTrue(checked.player.isPlaying)
        assertTrue(raw.peakWindow <= 3)
        assertTrue(coordinator.state.value.gapless.available)
        raw.completeNaturally(operations.boundaryPauseEnabled)
        idle()
        assertEquals("entry-2", store.snapshot()?.currentEntryId)
        assertEquals(listOf("entry-0", "entry-1", "entry-2"), rounds)
        assertTrue(failures.isEmpty())
    }

    @Test fun aShortSuccessorCanEndBeforeTheFirstNonCancellableReceiptReturns() = onMain {
        seed(2)
        val before = checked.calls.toMap()
        val gate = gated.blockNext()
        raw.completeNaturally(operations.boundaryPauseEnabled)
        gate.entered.await()
        assertEquals("entry-0", store.snapshot()?.currentEntryId)
        assertEquals("entry-1", checked.player.currentMediaItem?.mediaId)
        assertTrue(operations.boundaryPauseEnabled)
        raw.completeNaturally(operations.boundaryPauseEnabled)
        gate.release.complete(Unit)
        idle()
        val saved = requireNotNull(store.snapshot())
        assertEquals("entry-1", saved.currentEntryId)
        assertEquals(DURATION_MS, saved.positionMs)
        assertEquals(1, saved.checkpoint.completedCycles)
        assertFalse(checked.player.playWhenReady)
        assertEquals(before["prepare"], checked.calls["prepare"])
        assertEquals(before["seekTo"], checked.calls["seekTo"])
        assertEquals(listOf("entry-0", "entry-1"), rounds)
        val reopened = openDatabase()
        try { assertEquals(saved, RoomQueueStore(reopened).snapshot()) }
        finally { reopened.close() }
        assertTrue(failures.isEmpty())
    }

    @Test fun terminalEventsFlushedByTheBoundaryGateKeepTheIncomingIdentity() = onMain {
        seed(2)
        operations.onNextPause = { raw.completeNaturally(pause = true) }
        raw.completeNaturally(operations.boundaryPauseEnabled)
        idle()
        assertEquals("entry-1", store.snapshot()?.currentEntryId)
        assertEquals(1, store.snapshot()?.checkpoint?.completedCycles)
        assertEquals(listOf("entry-0", "entry-1"), rounds)
        assertFalse(checked.player.playWhenReady)
    }

    @Test fun aUserPauseDuringTheReceiptPersistsTheIncomingClockWithoutReplayingItsStart() = onMain {
        seed(3)
        val before = checked.calls.toMap()
        val gate = gated.blockNext()
        raw.completeNaturally(operations.boundaryPauseEnabled)
        gate.entered.await()
        raw.position(200L)
        coordinator.sessionPlayer.pause()
        gate.release.complete(Unit)
        idle()
        assertEquals("entry-1", store.snapshot()?.currentEntryId)
        assertEquals(200L, store.snapshot()?.positionMs)
        assertFalse(checked.player.playWhenReady)
        assertEquals(before["prepare"], checked.calls["prepare"])
        assertEquals(before["seekTo"], checked.calls["seekTo"])
    }

    @Test fun teardownDuringTheReceiptRetainsTheActualIncomingClockWithoutReleasedPlayerAccess() = onMain {
        seed(3)
        val gate = gated.blockNext()
        raw.completeNaturally(operations.boundaryPauseEnabled)
        gate.entered.await()
        raw.position(300L)
        assertEquals(300L, checked.player.currentPosition)
        coordinator.detach()
        checked.player.release()
        gate.release.complete(Unit)
        coordinator.close()
        assertEquals("entry-1", store.snapshot()?.currentEntryId)
        assertEquals(300L, store.snapshot()?.positionMs)
        assertEquals(0, checked.lateCalls)
    }

    @Test fun backendSwitchPausesAndCapturesTheIncomingClockBeforeWaitingForItsReceipt() = onMain {
        seed(3)
        val gate = gated.blockNext()
        raw.completeNaturally(operations.boundaryPauseEnabled)
        gate.entered.await()
        raw.position(250L)
        val freezing = scope.async { coordinator.freezeForBackendSwitch() }
        yield()
        assertFalse(checked.player.playWhenReady)
        assertFalse(freezing.isCompleted)
        gate.release.complete(Unit)
        val frozen = freezing.await()
        assertEquals("entry-1", frozen.snapshot?.currentEntryId)
        assertEquals(250L, frozen.positionMs)
        assertEquals(250L, store.snapshot()?.positionMs)
        assertEquals(listOf("entry-0", "entry-1"), rounds)
        coordinator.cancelBackendSwitch(frozen)
        assertFalse(checked.player.playWhenReady)
    }

    @Test fun anActiveSleepTimerKeepsTheOrdinaryEndGateAheadOfRepeatOrAdvance() = onMain {
        timerActive = true
        seed(2)
        assertFalse(coordinator.state.value.gapless.available)
        assertEquals(GaplessPlaybackIssue.TIMER_ACTIVE, coordinator.state.value.gapless.issue)
        timerBlocksEnd = true
        raw.completeNaturally(operations.boundaryPauseEnabled)
        idle()
        assertEquals("entry-0", store.snapshot()?.currentEntryId)
        assertEquals(1, store.snapshot()?.checkpoint?.completedCycles)
        assertFalse(checked.player.playWhenReady)
        assertEquals(listOf("entry-0"), rounds)
    }

    @Test fun finiteSingleRepeatSettlesItsCountBeforeArmingTheDistinctNextInstance() = onMain {
        seed(2, QueueMode(repeat = QueueRepeatMode.SINGLE, cycleLimit = 2))
        assertEquals(GaplessPlaybackIssue.MODE_REQUIRES_SETTLEMENT, coordinator.state.value.gapless.issue)
        raw.completeNaturally(operations.boundaryPauseEnabled)
        idle()
        assertEquals("entry-0", store.snapshot()?.currentEntryId)
        assertEquals(1, store.snapshot()?.checkpoint?.completedCycles)
        assertTrue(coordinator.state.value.gapless.available)
        raw.completeNaturally(operations.boundaryPauseEnabled)
        idle()
        assertEquals("entry-1", store.snapshot()?.currentEntryId)
        assertEquals(0, store.snapshot()?.checkpoint?.completedCycles)
        assertEquals(listOf("entry-0", "entry-0", "entry-1"), rounds)
    }

    @Test fun aFailedHandoverRetriesItsCASInsteadOfPreparingTheOldOrIncomingTrackAgain() = onMain {
        seed(2)
        val before = checked.calls.toMap()
        gated.failNext = true
        raw.completeNaturally(operations.boundaryPauseEnabled)
        idle()
        assertEquals("entry-0", store.snapshot()?.currentEntryId)
        assertEquals("entry-1", checked.player.currentMediaItem?.mediaId)
        assertFalse(checked.player.playWhenReady)
        coordinator.sessionPlayer.play()
        idle()
        assertEquals("entry-1", store.snapshot()?.currentEntryId)
        assertEquals(listOf("entry-0", "entry-1"), rounds)
        assertEquals(before["prepare"], checked.calls["prepare"])
        assertEquals(before["seekTo"], checked.calls["seekTo"])
        assertTrue(checked.player.playWhenReady)
        assertNull(coordinator.state.value.failure)
    }

    @Test fun anUnsupportedBackendKeepsOrdinaryTransitions() = onMain {
        operations.supports = false
        seed(2)
        assertEquals(GaplessPlaybackIssue.BACKEND_UNSUPPORTED, coordinator.state.value.gapless.issue)
        val preparations = checked.calls["prepare"]
        raw.completeNaturally(operations.boundaryPauseEnabled)
        idle()
        assertEquals("entry-1", store.snapshot()?.currentEntryId)
        assertEquals(requireNotNull(preparations) + 1, checked.calls["prepare"])
        assertTrue(checked.player.playWhenReady)
    }

    @Test fun turningThePreferenceOffRevokesThePreparedBoundaryWithoutPausingTheCurrentTrack() = onMain {
        seed(2)
        val before = checked.calls["pause"]
        preferences.state.value = PreferencesState.Ready(UserPreferences(gaplessPlayback = false))
        coordinator.refreshContinuousBoundary()
        assertEquals(GaplessPlaybackIssue.PREFERENCE_DISABLED, coordinator.state.value.gapless.issue)
        assertTrue(operations.boundaryPauseEnabled)
        assertEquals(before, checked.calls["pause"])
        raw.completeNaturally(operations.boundaryPauseEnabled)
        idle()
        assertEquals("entry-1", store.snapshot()?.currentEntryId)
        assertTrue(checked.player.playWhenReady)
    }

    @Test fun anAutomaticEventFlushedByTheActionGateRejectsReplacementBeforeReadingItsProducer() = onMain {
        seed(4)
        val base = requireNotNull(store.snapshot())
        val view = FrozenQueueView("gate-replacement", 2L, 2L)
        operations.onNextPause = { raw.completeNaturally(pause = false) }
        try {
            coordinator.replace(QueueReplacementRequest("gate-replace", base.generationId, base.queueRevision, view),
                QueuePageSource { _, _ -> throw AssertionError("Replacement producer read after the gate flushed AUTO") }, false)
            throw AssertionError("Expected BUSY after the actual automatic handover")
        } catch (failure: QueuePlaybackException) {
            assertEquals(QueuePlaybackIssue.BUSY, failure.failure.issue)
        }
        idle()
        assertEquals(base.generationId, store.snapshot()?.generationId)
        assertEquals("entry-1", store.snapshot()?.currentEntryId)
        assertEquals(listOf("entry-0", "entry-1"), rounds)
        assertTrue(checked.player.playWhenReady)
    }

    @Test fun aLateOldAutomaticEventAfterReplacementCommitCannotBlockTheNewPointerOrLaterCommands() = onMain {
        seed(3)
        val base = requireNotNull(store.snapshot())
        val view = FrozenQueueView("accepted-replacement", 2L, 2L)
        checked.onNextPause = { raw.completeNaturally(pause = false) }
        val commit = coordinator.replace(QueueReplacementRequest("accepted-replace", base.generationId, base.queueRevision, view),
            newEntries(view), explicitPlay = false)
        idle()
        assertEquals("new-0", commit.snapshot.currentEntryId)
        assertEquals("new-0", store.snapshot()?.currentEntryId)
        assertEquals("new-0", checked.player.currentMediaItem?.mediaId)
        coordinator.sessionPlayer.play()
        idle()
        assertEquals("new-0", store.snapshot()?.currentEntryId)
        assertTrue(checked.player.playWhenReady)
        assertTrue(failures.isEmpty())
    }

    @Test fun aRetainedModeCommitWithALateAutomaticEventPreservesTheActualSourceAndItsNewGeneration() = onMain {
        seed(3, QueueMode(repeat = QueueRepeatMode.SINGLE, cycleLimit = 2))
        raw.completeNaturally(operations.boundaryPauseEnabled)
        idle()
        val base = requireNotNull(store.snapshot())
        assertEquals(1, base.checkpoint.completedCycles)
        assertTrue(coordinator.state.value.gapless.available)
        val before = checked.calls.toMap()
        gated.onMutationCommitted = { raw.completeNaturally(pause = false) }
        val editing = scope.async {
            coordinator.mutate(QueueMutationRequest("keep-mode", base.generationId, base.queueRevision, base.currentEntryId,
                QueueMutation.SetMode(QueueMode())))
        }
        editing.join()
        idle()
        val saved = requireNotNull(store.snapshot())
        assertTrue(saved.generationId != base.generationId)
        assertEquals("entry-1", saved.currentEntryId)
        assertEquals(0, saved.checkpoint.completedCycles)
        assertEquals(saved.generationId, coordinator.sleepTimerTrackBinding()?.queueGenerationId)
        assertEquals("entry-1", checked.player.currentMediaItem?.mediaId)
        assertEquals(before["pause"], checked.calls["pause"])
        assertEquals(before["prepare"], checked.calls["prepare"])
        assertEquals(before["seekTo"], checked.calls["seekTo"])
        assertEquals(listOf("entry-0", "entry-0", "entry-1"), rounds)
        assertTrue(failures.isEmpty())
    }

    @Test fun listWrapUsesTheDurableOrderWithoutMakingThePhysicalWindowRepeat() = onMain {
        seed(2, QueueMode(repeat = QueueRepeatMode.LIST))
        raw.completeNaturally(operations.boundaryPauseEnabled)
        idle()
        assertEquals("entry-1", store.snapshot()?.currentEntryId)
        assertTrue(operations.boundaryPauseEnabled)
        assertEquals(Player.REPEAT_MODE_OFF, checked.player.repeatMode)
        raw.completeNaturally(operations.boundaryPauseEnabled)
        idle()
        assertEquals("entry-0", store.snapshot()?.currentEntryId)
        assertEquals(listOf("entry-0", "entry-1", "entry-0"), rounds)
        assertTrue(checked.player.playWhenReady)
    }

    private suspend fun seed(count: Int, mode: QueueMode = QueueMode()) {
        val view = FrozenQueueView("fixture-view", 1L, count.toLong())
        coordinator.replace(QueueReplacementRequest("seed", null, 0L, view, mode = mode),
            QueuePageSource { offset, limit -> QueueInputPage(view, offset,
                List(minOf(limit.toLong(), view.totalCount - offset).toInt()) { entry(offset + it) }) }, explicitPlay = true)
    }

    private suspend fun idle() = withTimeout(10_000L) {
        repeat(4) {
            yield()
            while (coordinator.state.value.preparing) yield()
        }
        coordinator.flush()
    }

    private fun entry(order: Long) = QueueEntry("entry-$order", TrackKey(TrackKey.LOCAL_PROVIDER, "track-$order"),
        order, order, durationMs = DURATION_MS, location = LocalLocationReference("location-$order", "content://fixture/audio/$order"))

    private fun newEntries(view: FrozenQueueView) = QueuePageSource { offset, limit ->
        QueueInputPage(view, offset, List(minOf(limit.toLong(), view.totalCount - offset).toInt()) {
            val order = offset + it
            entry(order + 4L).copy(queueEntryId = "new-$order", originalOrder = order, playbackOrder = order)
        })
    }

    private fun openDatabase(): MeloXDatabase = Room.databaseBuilder(RuntimeEnvironment.getApplication(),
        MeloXDatabase::class.java, databaseFile.absolutePath).build()

    private fun <T> onMain(block: suspend () -> T): T {
        val task = scope.async(Dispatchers.Main.immediate) { block() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30L)
        while (!task.isCompleted && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!task.isCompleted) Thread.sleep(1L)
        }
        if (!task.isCompleted) {
            task.cancel()
            throw AssertionError("Main/Room operation exceeded its test deadline")
        }
        return runBlocking { task.await() }
    }

    private class FixedPreferences : UserPreferencesRepository {
        override val state = MutableStateFlow<PreferencesState>(PreferencesState.Ready(UserPreferences()))
        override suspend fun save(expectedRevision: Long, draft: UserPreferencesDraft): PreferencesWriteResult = error("Unexpected settings write")
        override suspend fun reset(expectedRevision: Long, confirmation: ResetConfirmation): PreferencesWriteResult = error("Unexpected settings reset")
        override fun retryRead() = Unit
    }

    private class Gate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    private class GatedStore(private val actual: RoomQueueStore) : QueueStore by actual {
        var gate: Gate? = null
        var failNext = false
        var onMutationCommitted: (() -> Unit)? = null
        fun blockNext() = Gate().also { gate = it }
        override suspend fun saveCheckpoint(generationId: String, expectedCheckpointRevision: Long, checkpoint: QueueCheckpoint): QueueSnapshot {
            if (failNext) {
                failNext = false
                throw QueueStoreException(QueueStoreIssue.STORAGE_UNAVAILABLE)
            }
            gate?.let {
                it.entered.complete(Unit)
                it.release.await()
                gate = null
            }
            return actual.saveCheckpoint(generationId, expectedCheckpointRevision, checkpoint)
        }
        override suspend fun mutate(request: QueueMutationRequest, source: QueuePageSource?,
            beforePublish: suspend (QueueMutationCandidate) -> QueueCheckpoint?): QueueCommit = withContext(NonCancellable) {
            val commit = actual.mutate(request, source, beforePublish)
            onMutationCommitted?.also { onMutationCommitted = null }?.invoke()
            commit
        }
    }

    private class WindowOperations(override val player: Player) : PlaybackBackendOperations {
        var supports = true
        override val supportsContinuousPlayback: Boolean get() = supports
        var boundaryPauseEnabled = true
        var onNextPause: (() -> Unit)? = null
        override fun setPauseAtBoundary(pause: Boolean) {
            boundaryPauseEnabled = pause
            if (pause) onNextPause?.also { onNextPause = null }?.invoke()
        }
        override suspend fun prepareAndAwait(expectedMediaId: String) {
            assertEquals(expectedMediaId, player.currentMediaItem?.mediaId)
            player.prepare()
        }
        override suspend fun seekAndAwait(expectedMediaId: String, positionMs: Long) {
            assertEquals(expectedMediaId, player.currentMediaItem?.mediaId)
            player.seekTo(positionMs)
        }
    }

    private class CheckedPlayer(raw: Player) {
        var released = false
        var lateCalls = 0
        var onNextPause: (() -> Unit)? = null
        val calls = mutableMapOf<String, Int>()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.singleOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "CheckedGaplessPlayer"
                else -> {
                    if (released) {
                        lateCalls += 1
                        throw AssertionError("Released player accessed")
                    }
                    if (method.name in listOf("pause", "prepare", "seekTo")) calls[method.name] = (calls[method.name] ?: 0) + 1
                    if (method.name == "pause") onNextPause?.also { onNextPause = null }?.invoke()
                    try {
                        method.invoke(raw, *(args ?: emptyArray())).also { if (method.name == "release") released = true }
                    } catch (error: InvocationTargetException) { throw error.targetException }
                }
            }
        } as Player
    }

    private class WindowPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        private data class Item(val uid: Long, val media: MediaItem)
        private val items = mutableListOf<Item>()
        private var nextUid = 0L
        private var index = C.INDEX_UNSET
        private var positionMs = 0L
        private var ready = false
        private var ended = false
        private var playing = false
        private var reason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
        private var discontinuity: Int? = null
        var peakWindow = 0
            private set

        override fun getState(): State {
            val value = positionMs
            val builder = State.Builder().setAvailableCommands(COMMANDS)
                .setPlaylist(items.map { MediaItemData.Builder(it.uid).setMediaItem(it.media)
                    .setDurationUs(DURATION_MS * 1000L).setIsSeekable(true).build() })
                .setCurrentMediaItemIndex(index).setPlaybackState(when {
                    items.isEmpty() -> Player.STATE_IDLE
                    ended -> Player.STATE_ENDED
                    ready -> Player.STATE_READY
                    else -> Player.STATE_IDLE
                }).setPlayWhenReady(playing, reason).setContentPositionMs(PositionSupplier { value })
                .setContentBufferedPositionMs(PositionSupplier { value }).setTotalBufferedDurationMs(PositionSupplier.ZERO)
            discontinuity?.let { builder.setPositionDiscontinuity(it, value) }
            discontinuity = null
            return builder.build()
        }
        override fun getPlaceholderState(suggestedPlaceholderState: State): State = getState()
        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            playing = playWhenReady
            reason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
            return done()
        }
        override fun handlePrepare(): ListenableFuture<*> {
            ready = true
            ended = false
            return done()
        }
        override fun handleRelease(): ListenableFuture<*> {
            playing = false
            ready = false
            return done()
        }
        override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
            items.clear()
            items += mediaItems.map { Item(nextUid++, it) }
            index = if (items.isEmpty()) C.INDEX_UNSET else startIndex
            positionMs = startPositionMs
            ready = false
            ended = false
            recordWindow()
            return done()
        }
        override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
            val current = items.getOrNull(this.index)?.uid
            items.addAll(index, mediaItems.map { Item(nextUid++, it) })
            this.index = items.indexOfFirst { it.uid == current }
            recordWindow()
            return done()
        }
        override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
            val current = items.getOrNull(index)?.uid
            items.subList(fromIndex, toIndex).clear()
            index = items.indexOfFirst { it.uid == current }
            return done()
        }
        override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
            val current = items.getOrNull(index)?.uid
            val selected = items.subList(fromIndex, toIndex).toList()
            items.subList(fromIndex, toIndex).clear()
            items.addAll(newIndex, selected)
            index = items.indexOfFirst { it.uid == current }
            return done()
        }
        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            index = mediaItemIndex
            this.positionMs = positionMs
            ended = positionMs >= DURATION_MS
            return done()
        }
        fun position(value: Long) {
            positionMs = value
            invalidateState()
        }
        fun completeNaturally(pause: Boolean) {
            if (!pause && index + 1 < items.size) {
                index += 1
                positionMs = 0L
                discontinuity = Player.DISCONTINUITY_REASON_AUTO_TRANSITION
            } else {
                positionMs = DURATION_MS
                ended = true
                playing = false
                reason = Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
            }
            invalidateState()
        }
        private fun recordWindow() {
            peakWindow = maxOf(peakWindow, items.size)
            assertTrue(items.size <= 3)
        }
        private fun done(): ListenableFuture<Unit> = Futures.immediateFuture(Unit)
        companion object {
            private val COMMANDS = Player.Commands.Builder().addAll(
                Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP, Player.COMMAND_RELEASE,
                Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA, Player.COMMAND_GET_TRACKS,
                Player.COMMAND_GET_AUDIO_ATTRIBUTES, Player.COMMAND_GET_VOLUME, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
            ).build()
        }
    }

    companion object { private const val DURATION_MS = 1000L }
}
