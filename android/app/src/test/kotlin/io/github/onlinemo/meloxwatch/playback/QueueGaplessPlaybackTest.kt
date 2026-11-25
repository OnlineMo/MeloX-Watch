package io.github.onlinemo.meloxwatch.playback

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.Timeline
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
import io.github.onlinemo.meloxwatch.model.QueueSeekRequest
import io.github.onlinemo.meloxwatch.playback.input.SeekGestureContext
import io.github.onlinemo.meloxwatch.playback.input.SeekGestureSurface
import io.github.onlinemo.meloxwatch.playback.input.SeekGestures
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.model.UserPreferences
import io.github.onlinemo.meloxwatch.model.UserPreferencesDraft
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
    private val naturalEndings = mutableListOf<String>()
    private val failures = mutableListOf<QueuePlaybackFailure>()
    private var timerActive = false
    private var timerBlocksEnd = false
    private var epochReady = true

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
            mayContinuousPlayback = { !timerActive }, mayBeginContinuousRound = { epochReady },
            beforeNaturalAdvance = { binding ->
                naturalEndings += binding.queueEntryId
                timerBlocksEnd
            },
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

    @Test fun anAutomaticEventFlushedWhileOpeningTheBoundaryKeepsThePendingReceiptVisible() = onMain {
        preferences.state.value = PreferencesState.Ready(UserPreferences(gaplessPlayback = false))
        seed(3)
        val gate = gated.blockNext()
        operations.onNextResume = { raw.completeNaturally(pause = false) }
        preferences.state.value = PreferencesState.Ready(UserPreferences())
        coordinator.refreshContinuousBoundary()
        gate.entered.await()
        assertEquals("entry-0", store.snapshot()?.currentEntryId)
        assertEquals("entry-1", checked.player.currentMediaItem?.mediaId)
        assertFalse(coordinator.state.value.gapless.available)
        assertEquals(GaplessPlaybackIssue.HANDOVER_PENDING, coordinator.state.value.gapless.issue)
        assertTrue(operations.boundaryPauseEnabled)
        gate.release.complete(Unit)
        idle()
        assertEquals("entry-1", store.snapshot()?.currentEntryId)
        assertTrue(coordinator.state.value.gapless.available)
        assertTrue(failures.isEmpty())
    }

    @Test fun aPauseFlushedWhileOpeningTheBoundaryCannotResurrectItsPermission() = onMain {
        preferences.state.value = PreferencesState.Ready(UserPreferences(gaplessPlayback = false))
        seed(2)
        val before = checked.calls.toMap()
        operations.onNextResume = { coordinator.sessionPlayer.pause() }
        preferences.state.value = PreferencesState.Ready(UserPreferences())
        coordinator.refreshContinuousBoundary()
        idle()
        assertFalse(checked.player.playWhenReady)
        assertTrue(operations.boundaryPauseEnabled)
        assertFalse(coordinator.state.value.gapless.available)
        assertEquals(GaplessPlaybackIssue.PREPARING, coordinator.state.value.gapless.issue)
        assertEquals(before["prepare"], checked.calls["prepare"])
        assertEquals(before["seekTo"], checked.calls["seekTo"])
        assertEquals("entry-0", store.snapshot()?.currentEntryId)
    }

    @Test fun disablingGaplessInsideTheOpeningSetterKeepsTheCurrentTrackPlayingWithTheGateClosed() = onMain {
        preferences.state.value = PreferencesState.Ready(UserPreferences(gaplessPlayback = false))
        seed(2)
        operations.onNextResume = {
            preferences.state.value = PreferencesState.Ready(UserPreferences(gaplessPlayback = false))
            coordinator.refreshContinuousBoundary()
        }
        preferences.state.value = PreferencesState.Ready(UserPreferences())
        coordinator.refreshContinuousBoundary()
        assertTrue(checked.player.playWhenReady)
        assertTrue(operations.boundaryPauseEnabled)
        assertFalse(coordinator.state.value.gapless.available)
        assertEquals(GaplessPlaybackIssue.PREFERENCE_DISABLED, coordinator.state.value.gapless.issue)
        assertEquals("entry-0", store.snapshot()?.currentEntryId)
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
        assertEquals(listOf("entry-1"), naturalEndings)
        assertEquals(0, saved.checkpoint.completedCycles)
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
        assertEquals(listOf("entry-1"), naturalEndings)
        assertEquals(0, store.snapshot()?.checkpoint?.completedCycles)
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

    @Test fun aBoundarySetterFailureBeforeAdmissionKeepsTheQueueAndAllowsAnExplicitRetry() = onMain {
        seed(4)
        val failure = ControlledAdmissionFailure("BOUNDARY_SETTER_FIXTURE_FAILURE")
        assertAdmissionFailureKeepsQueueAndAllowsRetry(failure) {
            operations.onNextPause = { throw failure }
        }
    }

    @Test fun aRollingTailUpdateFailureBeforeAdmissionKeepsTheCurrentSourceAndAllowsRetry() = onMain {
        operations.rolling = true
        seed(4)
        assertRollingTail("entry-1", pending = true)
        val failure = ControlledTailUpdateFailure("ROLLING_QUEUE_TAIL_UPDATE_UNSUPPORTED")
        assertAdmissionFailureKeepsQueueAndAllowsRetry(failure) {
            checked.onNextReplace = { throw failure }
        }
    }

    @Test fun cancellationInsideTheAdmissionGateReleasesTheLockBeforeTheNextCommand() = onMain {
        seed(4)
        val base = requireNotNull(store.snapshot())
        val view = FrozenQueueView("cancelled-admission", 2L, 2L)
        val request = QueueReplacementRequest("cancelled-admission", base.generationId, base.queueRevision, view)
        var producerReads = 0
        val delegate = newEntries(view)
        val producer = QueuePageSource { offset, limit ->
            producerReads += 1
            delegate.read(offset, limit)
        }
        val failure = ControlledAdmissionCancellation("ADMISSION_CANCELLED_FIXTURE")
        var observed: Throwable? = null
        val command = scope.async(Dispatchers.Main.immediate, start = CoroutineStart.LAZY) {
            val context = currentCoroutineContext()
            operations.onNextPause = {
                context.cancel(failure)
                throw failure
            }
            try { coordinator.replace(request, producer, explicitPlay = false) }
            catch (error: Throwable) { observed = error; throw error }
        }
        command.start()
        command.join()
        assertTrue(command.isCompleted)
        assertTrue(command.isCancelled)
        assertSame(failure, observed)
        assertEquals(0, producerReads)
        assertEquals(base, store.snapshot())
        assertFalse(coordinator.state.value.preparing)
        assertEquals("entry-0", checked.player.currentMediaItem?.mediaId)
        val committed = coordinator.replace(request, producer, explicitPlay = false)
        idle()
        assertFalse(committed.wasReplay)
        assertTrue(producerReads > 0)
        assertEquals("new-0", store.snapshot()?.currentEntryId)
        assertFalse(checked.player.playWhenReady)
        assertTrue(failures.isEmpty())
    }

    @Test fun anAutomaticHandoverBeforeAGateFailureStillSettlesItsOriginalReceipt() = onMain {
        seed(4)
        val base = requireNotNull(store.snapshot())
        val view = FrozenQueueView("failed-gate-auto", 2L, 2L)
        val failure = ControlledAdmissionFailure("GATE_FAILURE_AFTER_AUTO")
        val gate = gated.blockNext()
        val before = checked.calls.toMap()
        operations.onNextPause = {
            raw.completeNaturally(pause = false)
            throw failure
        }
        var observed: Throwable? = null
        try {
            coordinator.replace(QueueReplacementRequest("failed-gate-auto", base.generationId, base.queueRevision, view),
                QueuePageSource { _, _ -> throw AssertionError("Failed admission consumed its producer") }, false)
        } catch (error: Throwable) { observed = error }
        assertSame(failure, observed)
        awaitReceipt(gate)
        assertEquals(base, store.snapshot())
        assertEquals("entry-1", checked.player.currentMediaItem?.mediaId)
        gate.release.complete(Unit)
        idle()
        assertEquals(base.generationId, store.snapshot()?.generationId)
        assertEquals("entry-1", store.snapshot()?.currentEntryId)
        assertEquals(listOf("entry-0", "entry-1"), rounds)
        assertEquals(before["prepare"], checked.calls["prepare"])
        assertEquals(before["seekTo"], checked.calls["seekTo"])
        assertTrue(checked.player.playWhenReady)
        assertTrue(failures.isEmpty())
    }

    @Test fun aPersistentGateFailureAfterAutoPausesOnceAndRequiresAnExplicitReceiptRetry() = onMain {
        seed(4)
        val base = requireNotNull(store.snapshot())
        val view = FrozenQueueView("persistent-gate-auto", 2L, 2L)
        val failure = ControlledAdmissionFailure("PERSISTENT_GATE_FIXTURE_FAILURE")
        val before = checked.calls.toMap()
        val writerStart = gated.checkpointAttempts.size
        var setterFailures = 0
        lateinit var rejectSetter: () -> Unit
        rejectSetter = {
            setterFailures += 1
            operations.onNextPause = rejectSetter
            throw failure
        }
        operations.onNextPause = {
            raw.completeNaturally(pause = false)
            operations.onNextPause = rejectSetter
            throw failure
        }
        var observed: Throwable? = null
        try {
            coordinator.replace(QueueReplacementRequest("persistent-gate-auto", base.generationId, base.queueRevision, view),
                QueuePageSource { _, _ -> throw AssertionError("Failed admission consumed its producer") }, false)
        } catch (error: Throwable) { observed = error }
        assertSame(failure, observed)
        idle()
        assertTrue(setterFailures in 1..4)
        assertEquals(base, store.snapshot())
        assertEquals("entry-1", checked.player.currentMediaItem?.mediaId)
        assertFalse(checked.player.playWhenReady)
        assertEquals(requireNotNull(before["pause"]) + 1, checked.calls["pause"])
        assertFalse(coordinator.state.value.preparing)
        assertEquals(QueuePlaybackIssue.OUTPUT_ERROR, coordinator.state.value.failure?.issue)
        assertTrue(gated.checkpointAttempts.drop(writerStart).isEmpty())
        val settledFailures = setterFailures
        repeat(4) { yield() }
        assertEquals(settledFailures, setterFailures)
        operations.onNextPause = null
        coordinator.sessionPlayer.play()
        idle()
        assertEquals(base.generationId, store.snapshot()?.generationId)
        assertEquals("entry-1", store.snapshot()?.currentEntryId)
        assertEquals(listOf("entry-0", "entry-1"), rounds)
        assertEquals(before["prepare"], checked.calls["prepare"])
        assertEquals(before["seekTo"], checked.calls["seekTo"])
        assertTrue(checked.player.playWhenReady)
        assertNull(coordinator.state.value.failure)
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

    @Test fun aRollingTailKeepsTheIncomingWindowLiveUntilItsRoomReceiptAllowsTheNextAppend() = onMain {
        operations.rolling = true
        seed(4)
        val before = checked.calls.toMap()
        assertEquals(listOf("entry-0", "entry-1"), physicalIds())
        assertRollingTail("entry-1", pending = true)
        val gate = gated.blockNext()
        raw.completeNaturally(operations.boundaryPauseEnabled)
        awaitReceipt(gate)
        val incomingUid = currentUid()
        assertEquals("entry-0", store.snapshot()?.currentEntryId)
        assertEquals("entry-1", checked.player.currentMediaItem?.mediaId)
        assertEquals(checked.player.mediaItemCount - 1, checked.player.currentMediaItemIndex)
        assertEquals(listOf("entry-0", "entry-1"), physicalIds())
        assertEquals(GaplessPlaybackIssue.HANDOVER_PENDING, coordinator.state.value.gapless.issue)
        assertFalse(coordinator.state.value.gapless.available)
        assertFalse(operations.boundaryPauseEnabled)
        assertTrue(checked.player.isPlaying)
        assertRollingTail("entry-1", pending = true)
        assertEquals(before["pause"], checked.calls["pause"])
        assertEquals(before["prepare"], checked.calls["prepare"])
        assertEquals(before["seekTo"], checked.calls["seekTo"])
        gate.release.complete(Unit)
        idle()
        assertEquals("entry-1", store.snapshot()?.currentEntryId)
        assertEquals("entry-1", checked.player.currentMediaItem?.mediaId)
        assertEquals(incomingUid, currentUid())
        assertEquals(listOf("entry-0", "entry-1"), rounds)
        assertTrue(physicalIds().contains("entry-2"))
        assertRollingTail("entry-2", pending = true)
        assertFalse(operations.boundaryPauseEnabled)
        assertTrue(coordinator.state.value.gapless.available)
        assertTrue(checked.player.isPlaying)
        assertEquals(before["pause"], checked.calls["pause"])
        assertEquals(before["prepare"], checked.calls["prepare"])
        assertEquals(before["seekTo"], checked.calls["seekTo"])
        assertTrue(raw.peakWindow <= 3)
        assertTrue(failures.isEmpty())
    }

    @Test fun aManualPauseHardClosesTheRollingHoldWithoutAppendingOrLosingTheIncomingClock() = onMain {
        operations.rolling = true
        seed(4)
        val before = checked.calls.toMap()
        val seeksStart = operations.seekRequests.size
        val receiptAndSeekOrder = mutableListOf<String>()
        val gate = gated.blockNext()
        raw.completeNaturally(operations.boundaryPauseEnabled)
        awaitReceipt(gate)
        val incomingUid = currentUid()
        val incomingUri = checked.player.currentMediaItem?.localConfiguration?.uri
        assertFalse(operations.boundaryPauseEnabled)
        assertRollingTail("entry-1", pending = true)
        raw.position(200L)
        coordinator.sessionPlayer.pause()
        assertTrue(operations.boundaryPauseEnabled)
        assertRollingTail("entry-1", pending = false)
        assertEquals(incomingUid, currentUid())
        assertEquals(Player.STATE_READY, checked.player.playbackState)
        assertEquals(200L, checked.player.currentPosition)
        assertFalse(checked.player.playWhenReady)
        assertEquals(listOf("entry-0", "entry-1"), physicalIds())
        gated.onCheckpointAccepted = { saved ->
            if (saved.currentEntryId == "entry-1" && saved.positionMs == 200L) receiptAndSeekOrder += "cas"
        }
        gate.release.complete(Unit)
        idle()
        assertEquals("entry-1", store.snapshot()?.currentEntryId)
        assertEquals(200L, store.snapshot()?.positionMs)
        assertEquals("entry-1", checked.player.currentMediaItem?.mediaId)
        assertEquals(200L, checked.player.currentPosition)
        assertEquals(incomingUid, currentUid())
        assertEquals(listOf("entry-0", "entry-1"), rounds)
        assertEquals(listOf("entry-0", "entry-1"), physicalIds())
        assertTrue(operations.boundaryPauseEnabled)
        assertRollingTail("entry-1", pending = false)
        assertFalse(checked.player.playWhenReady)
        assertFalse(coordinator.state.value.gapless.available)
        assertEquals(before["prepare"], checked.calls["prepare"])
        assertEquals(before["seekTo"], checked.calls["seekTo"])
        assertTrue(raw.peakWindow <= 3)
        assertTrue(failures.isEmpty())
        assertEquals(listOf("cas"), receiptAndSeekOrder)
        gated.onCheckpointAccepted = null
        operations.onNextSeek = { expectedMediaId, positionMs ->
            assertEquals("entry-1", expectedMediaId)
            assertEquals(200L, positionMs)
            assertEquals(incomingUid, currentUid())
            assertEquals(incomingUri, checked.player.currentMediaItem?.localConfiguration?.uri)
            assertFalse(operations.boundaryPauseEnabled)
            assertRollingTail(physicalIds().last(), pending = true)
            assertEquals(listOf("cas"), receiptAndSeekOrder)
            receiptAndSeekOrder += "seek"
        }
        coordinator.sessionPlayer.play()
        idle()
        assertEquals(listOf("cas", "seek"), receiptAndSeekOrder)
        assertEquals(listOf("entry-1" to 200L), operations.seekRequests.drop(seeksStart))
        assertEquals((before["seekTo"] ?: 0) + 1, checked.calls["seekTo"])
        assertEquals(before["prepare"], checked.calls["prepare"])
        assertEquals("entry-1", store.snapshot()?.currentEntryId)
        assertEquals(200L, store.snapshot()?.positionMs)
        assertEquals("entry-1", checked.player.currentMediaItem?.mediaId)
        assertEquals(200L, checked.player.currentPosition)
        assertEquals(incomingUid, currentUid())
        assertEquals(incomingUri, checked.player.currentMediaItem?.localConfiguration?.uri)
        assertEquals(listOf("entry-0", "entry-1"), rounds)
        assertTrue(checked.player.playWhenReady)
        assertTrue(coordinator.state.value.gapless.available)
        assertFalse(operations.boundaryPauseEnabled)
        assertRollingTail("entry-2", pending = true)
        assertTrue(raw.peakWindow <= 3)
        assertTrue(failures.isEmpty())
    }

    @Test fun timerInvalidationHardClosesTheRollingHoldAndFencesItsDeferredAppend() = onMain {
        operations.rolling = true
        seed(4)
        val before = checked.calls.toMap()
        val gate = gated.blockNext()
        raw.completeNaturally(operations.boundaryPauseEnabled)
        awaitReceipt(gate)
        val incomingUid = currentUid()
        assertRollingTail("entry-1", pending = true)
        assertFalse(operations.boundaryPauseEnabled)
        raw.position(240L)
        timerActive = true
        coordinator.refreshContinuousBoundary()
        assertTrue(operations.boundaryPauseEnabled)
        assertRollingTail("entry-1", pending = false)
        assertEquals(incomingUid, currentUid())
        assertEquals("entry-1", checked.player.currentMediaItem?.mediaId)
        assertEquals(240L, checked.player.currentPosition)
        assertEquals(Player.STATE_READY, checked.player.playbackState)
        assertFalse(coordinator.state.value.gapless.available)
        assertEquals(listOf("entry-0", "entry-1"), physicalIds())
        gate.release.complete(Unit)
        idle()
        assertEquals("entry-1", store.snapshot()?.currentEntryId)
        assertEquals(240L, store.snapshot()?.positionMs)
        assertEquals("entry-1", checked.player.currentMediaItem?.mediaId)
        assertEquals(240L, checked.player.currentPosition)
        assertEquals(incomingUid, currentUid())
        assertEquals(listOf("entry-0", "entry-1"), rounds)
        assertEquals(listOf("entry-0", "entry-1"), physicalIds())
        assertRollingTail("entry-1", pending = false)
        assertTrue(operations.boundaryPauseEnabled)
        assertFalse(coordinator.state.value.gapless.available)
        assertEquals(before["prepare"], checked.calls["prepare"])
        assertEquals(before["seekTo"], checked.calls["seekTo"])
        assertTrue(raw.peakWindow <= 3)
        assertTrue(failures.isEmpty())
    }

    @Test fun epochInvalidationHardClosesTheRollingHoldWithoutRebindingItsActualIncomingSource() = onMain {
        operations.rolling = true
        seed(4)
        val before = checked.calls.toMap()
        val gate = gated.blockNext()
        raw.completeNaturally(operations.boundaryPauseEnabled)
        awaitReceipt(gate)
        val incomingUid = currentUid()
        assertRollingTail("entry-1", pending = true)
        assertFalse(operations.boundaryPauseEnabled)
        raw.position(280L)
        epochReady = false
        coordinator.refreshContinuousBoundary()
        assertTrue(operations.boundaryPauseEnabled)
        assertRollingTail("entry-1", pending = false)
        assertEquals(incomingUid, currentUid())
        assertEquals("entry-1", checked.player.currentMediaItem?.mediaId)
        assertEquals(280L, checked.player.currentPosition)
        assertEquals(Player.STATE_READY, checked.player.playbackState)
        assertFalse(coordinator.state.value.gapless.available)
        assertEquals(listOf("entry-0", "entry-1"), physicalIds())
        gate.release.complete(Unit)
        idle()
        assertEquals("entry-1", store.snapshot()?.currentEntryId)
        assertEquals(280L, store.snapshot()?.positionMs)
        assertEquals("entry-1", checked.player.currentMediaItem?.mediaId)
        assertEquals(280L, checked.player.currentPosition)
        assertEquals(incomingUid, currentUid())
        assertEquals(listOf("entry-0", "entry-1"), rounds)
        assertEquals(listOf("entry-0", "entry-1"), physicalIds())
        assertRollingTail("entry-1", pending = false)
        assertTrue(operations.boundaryPauseEnabled)
        assertFalse(coordinator.state.value.gapless.available)
        assertEquals(before["prepare"], checked.calls["prepare"])
        assertEquals(before["seekTo"], checked.calls["seekTo"])
        assertTrue(raw.peakWindow <= 3)
        assertTrue(failures.isEmpty())
    }

    @Test fun aTerminalRollingSuccessorCanReallyEndBeforeTheFirstRoomReceiptReturns() = onMain {
        operations.rolling = true
        seed(2)
        val before = checked.calls.toMap()
        assertRollingTail("entry-1", pending = false)
        val gate = gated.blockNext()
        raw.completeNaturally(operations.boundaryPauseEnabled)
        awaitReceipt(gate)
        assertEquals("entry-0", store.snapshot()?.currentEntryId)
        assertEquals("entry-1", checked.player.currentMediaItem?.mediaId)
        assertEquals(Player.STATE_READY, checked.player.playbackState)
        raw.completeNaturally(operations.boundaryPauseEnabled)
        assertEquals(Player.STATE_ENDED, checked.player.playbackState)
        assertEquals("entry-1", checked.player.currentMediaItem?.mediaId)
        assertEquals(DURATION_MS, checked.player.currentPosition)
        assertFalse(checked.player.playWhenReady)
        gate.release.complete(Unit)
        idle()
        val saved = requireNotNull(store.snapshot())
        assertEquals("entry-1", saved.currentEntryId)
        assertEquals(DURATION_MS, saved.positionMs)
        assertEquals(0, saved.checkpoint.completedCycles)
        assertEquals(listOf("entry-1"), naturalEndings)
        assertEquals(listOf("entry-0", "entry-1"), rounds)
        assertEquals(Player.STATE_ENDED, checked.player.playbackState)
        assertFalse(checked.player.playWhenReady)
        assertEquals(before["prepare"], checked.calls["prepare"])
        assertEquals(before["seekTo"], checked.calls["seekTo"])
        assertTrue(raw.peakWindow <= 3)
        val reopened = openDatabase()
        try { assertEquals(saved, RoomQueueStore(reopened).snapshot()) }
        finally { reopened.close() }
        assertTrue(failures.isEmpty())
    }

    @Test fun aDynamicRollingTailClockOnlyFinalizesUntilTheRealEndedEventArrives() = onMain {
        operations.rolling = true
        seed(4)
        val before = checked.calls.toMap()
        val accepted = mutableListOf<QueueCheckpoint>()
        gated.onCheckpointAccepted = { accepted += it.checkpoint }
        val gate = gated.blockNext()
        raw.completeNaturally(operations.boundaryPauseEnabled)
        awaitReceipt(gate)
        assertRollingTail("entry-1", pending = true)
        assertFalse(operations.boundaryPauseEnabled)
        raw.position(DURATION_MS)
        assertEquals(Player.STATE_READY, checked.player.playbackState)
        coordinator.refreshContinuousBoundary()
        assertEquals(Player.STATE_READY, checked.player.playbackState)
        assertEquals("entry-1", checked.player.currentMediaItem?.mediaId)
        assertEquals(DURATION_MS, checked.player.currentPosition)
        assertTrue(operations.boundaryPauseEnabled)
        assertRollingTail("entry-1", pending = false)
        assertFalse(coordinator.state.value.gapless.available)
        assertEquals(listOf("entry-0", "entry-1"), physicalIds())
        assertEquals(listOf("entry-0", "entry-1"), rounds)
        assertEquals("entry-0", store.snapshot()?.currentEntryId)
        assertTrue(naturalEndings.isEmpty())
        assertTrue(accepted.isEmpty())
        assertEquals(before["prepare"], checked.calls["prepare"])
        assertEquals(before["seekTo"], checked.calls["seekTo"])
        raw.completeNaturally(pause = true)
        assertEquals(Player.STATE_ENDED, checked.player.playbackState)
        assertEquals("entry-1", checked.player.currentMediaItem?.mediaId)
        assertEquals(DURATION_MS, checked.player.currentPosition)
        assertTrue(naturalEndings.isEmpty())
        gate.release.complete(Unit)
        idle()
        assertTrue(accepted.any { it.queueEntryId == "entry-1" && it.positionMs == DURATION_MS })
        assertTrue(naturalEndings.isNotEmpty())
        assertTrue(naturalEndings.all { it == "entry-1" })
        assertEquals("entry-2", store.snapshot()?.currentEntryId)
        assertEquals("entry-2", checked.player.currentMediaItem?.mediaId)
        assertEquals(listOf("entry-0", "entry-1", "entry-2"), rounds)
        assertTrue(raw.peakWindow <= 3)
        assertTrue(failures.isEmpty())
    }

    @Test fun aFailedRollingReceiptHardClosesItsTailAndExplicitlyRetriesTheSameCAS() = onMain {
        operations.rolling = true
        seed(4)
        val base = requireNotNull(store.snapshot())
        val before = checked.calls.toMap()
        val seeksStart = operations.seekRequests.size
        val receiptAndSeekOrder = mutableListOf<String>()
        val attemptsStart = gated.checkpointAttempts.size
        gated.onCheckpointAccepted = { saved ->
            if (saved.currentEntryId == "entry-1" && saved.positionMs == 205L) receiptAndSeekOrder += "cas"
        }
        gated.failNext = true
        raw.completeNaturally(operations.boundaryPauseEnabled, incomingPositionMs = 205L)
        assertEquals(205L, checked.player.currentPosition)
        val incomingUid = currentUid()
        val incomingUri = checked.player.currentMediaItem?.localConfiguration?.uri
        idle()
        val failed = gated.checkpointAttempts.drop(attemptsStart)
            .filter { it.checkpoint.queueEntryId == "entry-1" }.single()
        assertEquals(base.generationId, failed.generationId)
        assertEquals(base.checkpointRevision, failed.expectedCheckpointRevision)
        assertEquals(QueueCheckpoint("entry-1", 205L, 0), failed.checkpoint)
        assertEquals("entry-0", store.snapshot()?.currentEntryId)
        assertEquals("entry-1", checked.player.currentMediaItem?.mediaId)
        assertEquals(205L, checked.player.currentPosition)
        assertFalse(checked.player.playWhenReady)
        assertTrue(operations.boundaryPauseEnabled)
        assertRollingTail("entry-1", pending = false)
        assertEquals(listOf("entry-0", "entry-1"), physicalIds())
        assertFalse(coordinator.state.value.gapless.available)
        assertTrue(receiptAndSeekOrder.isEmpty())
        operations.onNextSeek = { expectedMediaId, positionMs ->
            assertEquals("entry-1", expectedMediaId)
            assertEquals(205L, positionMs)
            assertEquals(incomingUid, currentUid())
            assertEquals(incomingUri, checked.player.currentMediaItem?.localConfiguration?.uri)
            assertEquals("entry-1", coordinator.state.value.snapshot?.currentEntryId)
            assertFalse(operations.boundaryPauseEnabled)
            assertRollingTail(physicalIds().last(), pending = true)
            assertEquals(listOf("cas"), receiptAndSeekOrder)
            receiptAndSeekOrder += "seek"
        }
        coordinator.sessionPlayer.play()
        idle()
        val attempts = gated.checkpointAttempts.drop(attemptsStart)
            .filter { it.checkpoint.queueEntryId == "entry-1" }
        assertEquals(2, attempts.size)
        assertEquals(failed, attempts[1])
        assertEquals(listOf("cas", "seek"), receiptAndSeekOrder)
        assertEquals(listOf("entry-1" to 205L), operations.seekRequests.drop(seeksStart))
        assertEquals("entry-1", store.snapshot()?.currentEntryId)
        assertEquals(205L, store.snapshot()?.positionMs)
        assertEquals("entry-1", checked.player.currentMediaItem?.mediaId)
        assertEquals(205L, checked.player.currentPosition)
        assertEquals(incomingUid, currentUid())
        assertEquals(incomingUri, checked.player.currentMediaItem?.localConfiguration?.uri)
        assertEquals(listOf("entry-0", "entry-1"), rounds)
        assertTrue(physicalIds().contains("entry-2"))
        assertRollingTail("entry-2", pending = true)
        assertTrue(checked.player.playWhenReady)
        assertTrue(coordinator.state.value.gapless.available)
        assertFalse(operations.boundaryPauseEnabled)
        assertEquals(before["prepare"], checked.calls["prepare"])
        assertEquals((before["seekTo"] ?: 0) + 1, checked.calls["seekTo"])
        assertNull(coordinator.state.value.failure)
        assertTrue(raw.peakWindow <= 3)
    }

    @Test fun anAutomaticEventInsideTheRollingAppendUsesTheNewPermitWithoutAnOldStackOverwrite() = onMain {
        operations.rolling = true
        seed(4)
        val before = checked.calls.toMap()
        val firstGate = gated.blockNext()
        raw.completeNaturally(operations.boundaryPauseEnabled)
        awaitReceipt(firstGate)
        var secondGate: Gate? = null
        checked.onNextAdd = {
            assertFalse(operations.boundaryPauseEnabled)
            assertEquals("entry-1", checked.player.currentMediaItem?.mediaId)
            assertRollingTail("entry-2", pending = true)
            secondGate = gated.blockNext()
            raw.completeNaturally(pause = false)
            assertEquals("entry-2", checked.player.currentMediaItem?.mediaId)
            assertEquals(GaplessPlaybackIssue.HANDOVER_PENDING, coordinator.state.value.gapless.issue)
            assertFalse(coordinator.state.value.gapless.available)
            assertFalse(operations.boundaryPauseEnabled)
            assertRollingTail("entry-2", pending = true)
        }
        firstGate.release.complete(Unit)
        withTimeout(10_000L) {
            while (secondGate == null) yield()
            requireNotNull(secondGate).entered.await()
        }
        assertEquals("entry-1", store.snapshot()?.currentEntryId)
        assertEquals("entry-2", checked.player.currentMediaItem?.mediaId)
        assertEquals(GaplessPlaybackIssue.HANDOVER_PENDING, coordinator.state.value.gapless.issue)
        assertFalse(coordinator.state.value.gapless.available)
        assertFalse(operations.boundaryPauseEnabled)
        assertRollingTail("entry-2", pending = true)
        assertFalse(physicalIds().contains("entry-3"))
        assertEquals(listOf("entry-0", "entry-1", "entry-2"), rounds)
        assertEquals(before["pause"], checked.calls["pause"])
        assertEquals(before["prepare"], checked.calls["prepare"])
        assertEquals(before["seekTo"], checked.calls["seekTo"])
        assertTrue(raw.peakWindow <= 3)
        requireNotNull(secondGate).release.complete(Unit)
        idle()
        assertEquals("entry-2", store.snapshot()?.currentEntryId)
        assertEquals("entry-2", checked.player.currentMediaItem?.mediaId)
        assertEquals(listOf("entry-0", "entry-1", "entry-2"), rounds)
        assertTrue(physicalIds().contains("entry-3"))
        assertRollingTail("entry-3", pending = false)
        assertTrue(coordinator.state.value.gapless.available)
        assertFalse(operations.boundaryPauseEnabled)
        assertTrue(checked.player.isPlaying)
        assertEquals(before["pause"], checked.calls["pause"])
        assertEquals(before["prepare"], checked.calls["prepare"])
        assertEquals(before["seekTo"], checked.calls["seekTo"])
        assertTrue(raw.peakWindow <= 3)
        assertTrue(failures.isEmpty())
    }

    @Test fun theInitialRollingPrepareAlreadyHasItsGateAndMarkerAndCannotUndoAManualPause() = onMain {
        operations.rolling = true
        val before = checked.calls.toMap()
        var prepareObserved = false
        operations.onNextPrepare = {
            prepareObserved = true
            assertFalse(operations.boundaryPauseEnabled)
            assertEquals("entry-0", checked.player.currentMediaItem?.mediaId)
            assertEquals(listOf("entry-0", "entry-1"), physicalIds())
            assertRollingTail("entry-1", pending = true)
            coordinator.sessionPlayer.pause()
        }
        val preparing = scope.async { seed(4) }
        preparing.join()
        idle()
        assertTrue(prepareObserved)
        assertEquals("entry-0", store.snapshot()?.currentEntryId)
        assertEquals("entry-0", checked.player.currentMediaItem?.mediaId)
        assertEquals(0L, checked.player.currentPosition)
        assertEquals(listOf("entry-0"), rounds)
        assertFalse(checked.player.playWhenReady)
        assertFalse(checked.player.isPlaying)
        assertTrue(operations.boundaryPauseEnabled)
        assertRollingTail("entry-1", pending = false)
        assertFalse(coordinator.state.value.gapless.available)
        assertEquals(before["seekTo"], checked.calls["seekTo"])
        coordinator.refreshContinuousBoundary()
        assertTrue(operations.boundaryPauseEnabled)
        assertFalse(checked.player.playWhenReady)
        assertFalse(coordinator.state.value.gapless.available)
        assertTrue(raw.peakWindow <= 3)
        assertTrue(failures.isEmpty())
    }

    @Test fun reconfirmingEpochBeforeTheReceiptReturnsCannotReopenAnAlreadyFinalizedRollingTail() = onMain {
        operations.rolling = true
        seed(4)
        val before = checked.calls.toMap()
        val gate = gated.blockNext()
        raw.completeNaturally(operations.boundaryPauseEnabled)
        awaitReceipt(gate)
        raw.position(280L)
        epochReady = false
        coordinator.refreshContinuousBoundary()
        assertTrue(operations.boundaryPauseEnabled)
        assertRollingTail("entry-1", pending = false)
        epochReady = true
        coordinator.refreshContinuousBoundary()
        assertTrue(operations.boundaryPauseEnabled)
        assertRollingTail("entry-1", pending = false)
        assertEquals(listOf("entry-0", "entry-1"), physicalIds())
        gate.release.complete(Unit)
        idle()
        assertEquals("entry-1", store.snapshot()?.currentEntryId)
        assertEquals(280L, store.snapshot()?.positionMs)
        assertEquals(listOf("entry-0", "entry-1"), physicalIds())
        assertTrue(operations.boundaryPauseEnabled)
        assertFalse(coordinator.state.value.gapless.available)
        assertRollingTail("entry-1", pending = false)
        assertEquals(before["prepare"], checked.calls["prepare"])
        assertEquals(before["seekTo"], checked.calls["seekTo"])
        assertEquals(listOf("entry-0", "entry-1"), rounds)
        assertTrue(raw.peakWindow <= 3)
        assertTrue(failures.isEmpty())
    }

    @Test fun aConfirmedPausedRollingSeekAndCancelledPreviewResumeTheAcceptedPosition() = onMain {
        operations.rolling = true
        seed(4)
        val gate = gated.blockNext()
        raw.completeNaturally(operations.boundaryPauseEnabled)
        awaitReceipt(gate)
        raw.position(200L)
        coordinator.sessionPlayer.pause()
        gate.release.complete(Unit)
        idle()
        val incomingUid = currentUid()
        val incomingUri = checked.player.currentMediaItem?.localConfiguration?.uri
        val before = checked.calls.toMap()
        val seeksStart = operations.seekRequests.size
        val binding = requireNotNull(coordinator.seekBinding())
        val saved = coordinator.seek(QueueSeekRequest(binding, 420L))
        idle()
        assertEquals(420L, saved.positionMs)
        assertEquals(420L, store.snapshot()?.positionMs)
        assertEquals(420L, checked.player.currentPosition)
        assertFalse(checked.player.playWhenReady)
        assertEquals(incomingUid, currentUid())
        assertEquals(incomingUri, checked.player.currentMediaItem?.localConfiguration?.uri)
        val previewBinding = requireNotNull(coordinator.seekBinding())
        val context = SeekGestureContext(previewBinding, coordinator.seekState.value.ownerId, foreground = true, canSeek = coordinator.canSeekFromSession())
        val draft = requireNotNull(SeekGestures.begin(context, SeekGestureSurface.MAIN_PROGRESS, 420L))
        val moved = SeekGestures.drag(draft, draft.gestureId, context, 0.7)
        assertEquals(700L, requireNotNull(moved.draft).previewPositionMs)
        assertNull(moved.request)
        val cancelled = SeekGestures.cancel(moved.draft, draft.gestureId)
        assertNull(cancelled.draft)
        assertNull(cancelled.request)
        assertNull(SeekGestures.release(cancelled.draft, draft.gestureId, context).request)
        assertEquals(420L, checked.player.currentPosition)
        assertEquals(listOf("entry-1" to 420L), operations.seekRequests.drop(seeksStart))
        operations.onNextSeek = { mediaId, position ->
            assertEquals("entry-1", mediaId)
            assertEquals(420L, position)
            assertEquals(incomingUid, currentUid())
            assertEquals(incomingUri, checked.player.currentMediaItem?.localConfiguration?.uri)
        }
        coordinator.sessionPlayer.play()
        idle()
        assertEquals(listOf("entry-1" to 420L, "entry-1" to 420L), operations.seekRequests.drop(seeksStart))
        assertEquals((before["seekTo"] ?: 0) + 2, checked.calls["seekTo"])
        assertEquals(before["prepare"], checked.calls["prepare"])
        assertEquals("entry-1", store.snapshot()?.currentEntryId)
        assertEquals(420L, store.snapshot()?.positionMs)
        assertEquals(420L, checked.player.currentPosition)
        assertEquals(incomingUid, currentUid())
        assertEquals(incomingUri, checked.player.currentMediaItem?.localConfiguration?.uri)
        assertEquals(listOf("entry-0", "entry-1"), rounds)
        assertTrue(checked.player.playWhenReady)
        assertTrue(coordinator.state.value.gapless.available)
        assertTrue(raw.peakWindow <= 3)
        assertTrue(failures.isEmpty())
    }

    @Test fun aReadyEndOnAnOrdinaryRollingBoundarySettlesItsEndClockInsteadOfThePausedCapture() = onMain {
        operations.rolling = true
        preferences.state.value = PreferencesState.Ready(UserPreferences(gaplessPlayback = false))
        seed(4)
        raw.position(200L)
        coordinator.sessionPlayer.pause()
        idle()
        assertEquals(200L, store.snapshot()?.positionMs)
        coordinator.sessionPlayer.play()
        idle()
        assertEquals("entry-0", checked.player.currentMediaItem?.mediaId)
        assertEquals(200L, checked.player.currentPosition)
        assertTrue(checked.player.playWhenReady)
        assertTrue(operations.boundaryPauseEnabled)
        assertTrue(checked.player.currentMediaItemIndex + 1 < checked.player.mediaItemCount)
        val before = checked.calls.toMap()
        val current = requireNotNull(store.snapshot())
        val uid = currentUid()
        val uri = checked.player.currentMediaItem?.localConfiguration?.uri
        val endEvents = mutableListOf<Triple<Boolean, Int, Long>>()
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                    endEvents += Triple(playWhenReady, checked.player.playbackState, checked.player.currentPosition)
                }
            }
        }
        checked.player.addListener(listener)
        try {
            timerActive = true
            timerBlocksEnd = true
            coordinator.refreshContinuousBoundary()
            val gate = gated.blockNext()
            raw.completeReadyBoundary()
            awaitReceipt(gate)
            assertTrue(endEvents.contains(Triple(false, Player.STATE_READY, DURATION_MS)))
            assertEquals(Player.STATE_READY, checked.player.playbackState)
            assertEquals("entry-0", checked.player.currentMediaItem?.mediaId)
            assertEquals(DURATION_MS, checked.player.currentPosition)
            assertFalse(checked.player.playWhenReady)
            gate.release.complete(Unit)
            idle()
            val saved = requireNotNull(store.snapshot())
            assertEquals(current.generationId, saved.generationId)
            assertEquals(current.queueRevision, saved.queueRevision)
            assertEquals("entry-0", saved.currentEntryId)
            assertEquals(DURATION_MS, saved.positionMs)
            assertEquals(1, saved.checkpoint.completedCycles)
            assertEquals(Player.STATE_READY, checked.player.playbackState)
            assertEquals(DURATION_MS, checked.player.currentPosition)
            assertFalse(checked.player.playWhenReady)
            assertTrue(operations.boundaryPauseEnabled)
            assertEquals(uid, currentUid())
            assertEquals(uri, checked.player.currentMediaItem?.localConfiguration?.uri)
            assertEquals(listOf("entry-0"), rounds)
            assertEquals(listOf("entry-0"), naturalEndings)
            assertEquals(before["prepare"], checked.calls["prepare"])
            assertEquals(before["seekTo"], checked.calls["seekTo"])
            coordinator.requestCheckpoint()
            coordinator.flush()
            idle()
            assertEquals(saved, store.snapshot())
            assertEquals(DURATION_MS, store.snapshot()?.positionMs)
            assertEquals(1, store.snapshot()?.checkpoint?.completedCycles)
            val reopened = openDatabase()
            try { assertEquals(saved, RoomQueueStore(reopened).snapshot()) }
            finally { reopened.close() }
            assertTrue(raw.peakWindow <= 3)
            assertTrue(failures.isEmpty())
        } finally {
            checked.player.removeListener(listener)
        }
    }

    @Test fun aReadyEndFlushedByTheOrdinaryGateSettlesTheIncomingTailBeforeItsPendingReceipt() = onMain {
        operations.rolling = true
        seed(4)
        val gate = gated.blockNext()
        raw.completeNaturally(operations.boundaryPauseEnabled)
        awaitReceipt(gate)
        raw.position(200L)
        assertEquals("entry-0", store.snapshot()?.currentEntryId)
        assertEquals("entry-1", checked.player.currentMediaItem?.mediaId)
        assertFalse(operations.boundaryPauseEnabled)
        assertRollingTail("entry-1", pending = true)
        val before = checked.calls.toMap()
        val uid = currentUid()
        val uri = checked.player.currentMediaItem?.localConfiguration?.uri
        val endEvents = mutableListOf<Triple<Boolean, Int, Long>>()
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                    endEvents += Triple(playWhenReady, checked.player.playbackState, checked.player.currentPosition)
                }
            }
        }
        checked.player.addListener(listener)
        try {
            var ordinarySetterEndCount = 0
            operations.onNextPause = {
                ordinarySetterEndCount += 1
                assertTrue(operations.boundaryPauseEnabled)
                assertEquals("entry-1", checked.player.currentMediaItem?.mediaId)
                assertEquals(200L, checked.player.currentPosition)
                assertRollingTail("entry-1", pending = true)
                raw.completeReadyBoundary()
            }
            timerActive = true
            timerBlocksEnd = true
            coordinator.refreshContinuousBoundary()
            assertEquals(1, ordinarySetterEndCount)
            assertTrue(endEvents.contains(Triple(false, Player.STATE_READY, DURATION_MS)))
            assertEquals(Player.STATE_READY, checked.player.playbackState)
            assertEquals("entry-1", checked.player.currentMediaItem?.mediaId)
            assertEquals(DURATION_MS, checked.player.currentPosition)
            assertFalse(checked.player.playWhenReady)
            assertTrue(operations.boundaryPauseEnabled)
            assertRollingTail("entry-1", pending = false)
            assertEquals("entry-0", store.snapshot()?.currentEntryId)
            assertFalse(physicalIds().contains("entry-2"))
            gate.release.complete(Unit)
            idle()
            val saved = requireNotNull(store.snapshot())
            assertEquals("entry-1", saved.currentEntryId)
            assertEquals(DURATION_MS, saved.positionMs)
            assertEquals(1, saved.checkpoint.completedCycles)
            assertEquals(Player.STATE_READY, checked.player.playbackState)
            assertEquals(DURATION_MS, checked.player.currentPosition)
            assertFalse(checked.player.playWhenReady)
            assertEquals(uid, currentUid())
            assertEquals(uri, checked.player.currentMediaItem?.localConfiguration?.uri)
            assertEquals(listOf("entry-0", "entry-1"), rounds)
            assertEquals(listOf("entry-1"), naturalEndings)
            assertEquals(listOf("entry-0", "entry-1"), physicalIds())
            assertEquals(before["prepare"], checked.calls["prepare"])
            assertEquals(before["seekTo"], checked.calls["seekTo"])
            val writerStart = gated.checkpointAttempts.size
            coordinator.requestCheckpoint()
            coordinator.flush()
            idle()
            assertEquals(saved, store.snapshot())
            assertEquals(DURATION_MS, store.snapshot()?.positionMs)
            assertEquals(1, store.snapshot()?.checkpoint?.completedCycles)
            assertFalse(gated.checkpointAttempts.drop(writerStart).any {
                it.checkpoint.queueEntryId == "entry-1" && it.checkpoint.positionMs == 200L
            })
            val reopened = openDatabase()
            try { assertEquals(saved, RoomQueueStore(reopened).snapshot()) }
            finally { reopened.close() }
            assertTrue(raw.peakWindow <= 3)
            assertTrue(failures.isEmpty())
        } finally {
            checked.player.removeListener(listener)
        }
    }

    @Test fun aReadyEndDuringTheRetainedAppendSettlesAfterItsOwnedWindowFinishesApplying() = onMain {
        operations.rolling = true
        seed(4)
        val before = checked.calls.toMap()
        val accepted = mutableListOf<QueueCheckpoint>()
        gated.onCheckpointAccepted = { accepted += it.checkpoint }
        val gate = gated.blockNext()
        raw.completeNaturally(operations.boundaryPauseEnabled)
        awaitReceipt(gate)
        raw.position(200L)
        coordinator.requestCheckpoint()
        val uid = currentUid()
        val uri = checked.player.currentMediaItem?.localConfiguration?.uri
        val endEntries = mutableListOf<String?>()
        val endStates = mutableListOf<Triple<Boolean, Int, Long>>()
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                    endEntries += checked.player.currentMediaItem?.mediaId
                    endStates += Triple(playWhenReady, checked.player.playbackState, checked.player.currentPosition)
                }
            }
        }
        checked.player.addListener(listener)
        try {
            var appendEndCount = 0
            checked.onNextAdd = {
                appendEndCount += 1
                assertEquals("entry-1", checked.player.currentMediaItem?.mediaId)
                assertEquals(200L, checked.player.currentPosition)
                assertEquals("entry-1", coordinator.state.value.snapshot?.currentEntryId)
                assertEquals(200L, coordinator.state.value.snapshot?.positionMs)
                assertTrue(accepted.any { it.queueEntryId == "entry-1" && it.positionMs == 200L })
                assertTrue(checked.player.currentMediaItemIndex + 1 < checked.player.mediaItemCount)
                assertTrue(physicalIds().contains("entry-2"))
                assertFalse(operations.boundaryPauseEnabled)
                timerActive = true
                timerBlocksEnd = true
                raw.completeReadyBoundary()
            }
            gate.release.complete(Unit)
            idle()
            assertEquals(1, appendEndCount)
            assertTrue(endEntries.isNotEmpty())
            assertTrue(endEntries.all { it == "entry-1" })
            assertTrue(endStates.contains(Triple(false, Player.STATE_READY, DURATION_MS)))
            val saved = requireNotNull(store.snapshot())
            assertEquals("entry-1", saved.currentEntryId)
            assertEquals(DURATION_MS, saved.positionMs)
            assertEquals(1, saved.checkpoint.completedCycles)
            assertEquals("entry-1", checked.player.currentMediaItem?.mediaId)
            assertEquals(Player.STATE_READY, checked.player.playbackState)
            assertEquals(DURATION_MS, checked.player.currentPosition)
            assertFalse(checked.player.playWhenReady)
            assertTrue(operations.boundaryPauseEnabled)
            assertEquals(uid, currentUid())
            assertEquals(uri, checked.player.currentMediaItem?.localConfiguration?.uri)
            assertEquals(listOf("entry-0", "entry-1"), rounds)
            assertEquals(listOf("entry-1"), naturalEndings)
            assertEquals(before["prepare"], checked.calls["prepare"])
            assertEquals(before["seekTo"], checked.calls["seekTo"])
            val writerStart = gated.checkpointAttempts.size
            coordinator.requestCheckpoint()
            coordinator.flush()
            idle()
            assertEquals(saved, store.snapshot())
            assertFalse(gated.checkpointAttempts.drop(writerStart).any {
                it.checkpoint.queueEntryId == "entry-1" && it.checkpoint.positionMs in listOf(0L, 200L)
            })
            val reopened = openDatabase()
            try { assertEquals(saved, RoomQueueStore(reopened).snapshot()) }
            finally { reopened.close() }
            assertEquals(before["prepare"], checked.calls["prepare"])
            assertEquals(before["seekTo"], checked.calls["seekTo"])
            assertEquals(listOf("entry-0", "entry-1"), rounds)
            assertTrue(raw.peakWindow <= 3)
            assertTrue(failures.isEmpty())
        } finally {
            checked.player.removeListener(listener)
        }
    }

    // 受控错误保留原对象；身份断言不能被协程调试栈的异常复制替代。
    @OptIn(ExperimentalCoroutinesApi::class)
    private class ControlledAdmissionFailure(message: String) : IllegalStateException(message),
        CopyableThrowable<ControlledAdmissionFailure> {
        override fun createCopy(): ControlledAdmissionFailure? = null
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private class ControlledTailUpdateFailure(message: String) : UnsupportedOperationException(message),
        CopyableThrowable<ControlledTailUpdateFailure> {
        override fun createCopy(): ControlledTailUpdateFailure? = null
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private class ControlledAdmissionCancellation(message: String) : CancellationException(message),
        CopyableThrowable<ControlledAdmissionCancellation> {
        override fun createCopy(): ControlledAdmissionCancellation? = null
    }

    private suspend fun assertAdmissionFailureKeepsQueueAndAllowsRetry(failure: Exception, arm: () -> Unit) {
        val base = requireNotNull(store.snapshot())
        val view = FrozenQueueView("admission-retry", 2L, 2L)
        val request = QueueReplacementRequest("admission-retry", base.generationId, base.queueRevision, view)
        val before = checked.calls.toMap()
        val uid = currentUid()
        val uri = checked.player.currentMediaItem?.localConfiguration?.uri
        var producerReads = 0
        val delegate = newEntries(view)
        val producer = QueuePageSource { offset, limit ->
            producerReads += 1
            delegate.read(offset, limit)
        }
        arm()
        var observed: Throwable? = null
        try { coordinator.replace(request, producer, explicitPlay = false) }
        catch (error: Throwable) { observed = error }
        assertSame(failure, observed)
        assertEquals(0, producerReads)
        assertEquals(base, store.snapshot())
        assertEquals("entry-0", checked.player.currentMediaItem?.mediaId)
        assertEquals(uid, currentUid())
        assertEquals(uri, checked.player.currentMediaItem?.localConfiguration?.uri)
        assertEquals(before["pause"], checked.calls["pause"])
        assertEquals(before["prepare"], checked.calls["prepare"])
        assertEquals(before["seekTo"], checked.calls["seekTo"])
        assertTrue(checked.player.playWhenReady)
        assertFalse(coordinator.state.value.preparing)
        val committed = coordinator.replace(request, producer, explicitPlay = false)
        idle()
        assertFalse(committed.wasReplay)
        assertTrue(producerReads > 0)
        assertEquals("new-0", store.snapshot()?.currentEntryId)
        assertFalse(checked.player.playWhenReady)
        assertTrue(failures.isEmpty())
    }

    private suspend fun seed(count: Int, mode: QueueMode = QueueMode()) {
        val view = FrozenQueueView("fixture-view", 1L, count.toLong())
        coordinator.replace(QueueReplacementRequest("seed", null, 0L, view, mode = mode),
            QueuePageSource { offset, limit -> QueueInputPage(view, offset,
                List(minOf(limit.toLong(), view.totalCount - offset).toInt()) { entry(offset + it) }) }, explicitPlay = true)
    }

    private suspend fun awaitReceipt(gate: Gate) = withTimeout(10_000L) { gate.entered.await() }

    private fun physicalIds(): List<String> =
        (0 until checked.player.mediaItemCount).map { checked.player.getMediaItemAt(it).mediaId }

    private fun currentUid(): Any =
        checked.player.currentTimeline.getUidOfPeriod(checked.player.currentPeriodIndex)

    private fun assertRollingTail(expectedEntryId: String, pending: Boolean) {
        val index = checked.player.mediaItemCount - 1
        val item = checked.player.getMediaItemAt(index)
        assertEquals(expectedEntryId, item.mediaId)
        assertEquals(pending, (item.localConfiguration?.tag as? QueueTailMarker)?.pendingContinuation)
        assertEquals(pending, checked.player.currentTimeline.getWindow(index, Timeline.Window()).isDynamic)
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

    private data class CheckpointAttempt(
        val generationId: String,
        val expectedCheckpointRevision: Long,
        val checkpoint: QueueCheckpoint,
    )

    private class GatedStore(private val actual: RoomQueueStore) : QueueStore by actual {
        val checkpointAttempts = mutableListOf<CheckpointAttempt>()
        var onCheckpointAccepted: ((QueueSnapshot) -> Unit)? = null
        var gate: Gate? = null
        var failNext = false
        var onMutationCommitted: (() -> Unit)? = null
        fun blockNext() = Gate().also { gate = it }
        override suspend fun saveCheckpoint(generationId: String, expectedCheckpointRevision: Long, checkpoint: QueueCheckpoint): QueueSnapshot {
            checkpointAttempts += CheckpointAttempt(generationId, expectedCheckpointRevision, checkpoint)
            if (failNext) {
                failNext = false
                throw QueueStoreException(QueueStoreIssue.STORAGE_UNAVAILABLE)
            }
            gate?.let {
                it.entered.complete(Unit)
                it.release.await()
                gate = null
            }
            return actual.saveCheckpoint(generationId, expectedCheckpointRevision, checkpoint).also {
                onCheckpointAccepted?.invoke(it)
            }
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
        var rolling = false
        override val supportsContinuousPlayback: Boolean get() = supports
        override val supportsRollingQueueTail: Boolean get() = rolling
        var boundaryPauseEnabled = true
        var onNextPause: (() -> Unit)? = null
        var onNextResume: (() -> Unit)? = null
        var onNextPrepare: (() -> Unit)? = null
        var onNextSeek: ((String, Long) -> Unit)? = null
        val seekRequests = mutableListOf<Pair<String, Long>>()
        override fun setPauseAtBoundary(pause: Boolean) {
            boundaryPauseEnabled = pause
            if (pause) onNextPause?.also { onNextPause = null }?.invoke()
            else onNextResume?.also { onNextResume = null }?.invoke()
        }
        override suspend fun prepareAndAwait(expectedMediaId: String) {
            assertEquals(expectedMediaId, player.currentMediaItem?.mediaId)
            onNextPrepare?.also { onNextPrepare = null }?.invoke()
            player.prepare()
        }
        override suspend fun seekAndAwait(expectedMediaId: String, positionMs: Long) {
            assertEquals(expectedMediaId, player.currentMediaItem?.mediaId)
            seekRequests += expectedMediaId to positionMs
            onNextSeek?.also { onNextSeek = null }?.invoke(expectedMediaId, positionMs)
            player.seekTo(positionMs)
        }
    }

    private class CheckedPlayer(raw: Player) {
        var released = false
        var lateCalls = 0
        var onNextPause: (() -> Unit)? = null
        var onNextAdd: (() -> Unit)? = null
        var onNextReplace: (() -> Unit)? = null
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
                    if (method.name == "replaceMediaItem" || method.name == "replaceMediaItems") {
                        onNextReplace?.also { onNextReplace = null }?.invoke()
                    }
                    try {
                        method.invoke(raw, *(args ?: emptyArray())).also {
                            if (method.name == "release") released = true
                            if (method.name == "addMediaItem" || method.name == "addMediaItems") {
                                onNextAdd?.also { onNextAdd = null }?.invoke()
                            }
                        }
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
                    .setIsDynamic((it.media.localConfiguration?.tag as? QueueTailMarker)?.pendingContinuation == true)
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
        override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
            val current = items.getOrNull(index)?.uid
            val previous = items.subList(fromIndex, toIndex).toList()
            val replacement = mediaItems.mapIndexed { offset, media ->
                val old = previous.getOrNull(offset)
                if (old != null && old.media.mediaId == media.mediaId &&
                    old.media.localConfiguration?.uri == media.localConfiguration?.uri &&
                    old.media.clippingConfiguration == media.clippingConfiguration) old.copy(media = media)
                else Item(nextUid++, media)
            }
            items.subList(fromIndex, toIndex).clear()
            items.addAll(fromIndex, replacement)
            index = items.indexOfFirst { it.uid == current }
            if (current != null && index == C.INDEX_UNSET) {
                index = minOf(fromIndex, items.lastIndex)
                positionMs = 0L
                ready = false
                ended = false
            }
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
        fun completeReadyBoundary() {
            val current = requireNotNull(items.getOrNull(index))
            val pending = (current.media.localConfiguration?.tag as? QueueTailMarker)?.pendingContinuation == true
            check(index + 1 < items.size || pending) { "READY_END_REQUIRES_NONFINAL_FRONTIER" }
            ready = true
            ended = false
            playing = false
            positionMs = DURATION_MS
            reason = Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
            invalidateState()
        }
        fun completeNaturally(pause: Boolean, incomingPositionMs: Long = 0L) {
            if (!pause && index + 1 < items.size) {
                index += 1
                positionMs = incomingPositionMs
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
