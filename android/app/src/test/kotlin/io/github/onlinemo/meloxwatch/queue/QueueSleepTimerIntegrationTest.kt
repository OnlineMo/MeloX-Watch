package io.github.onlinemo.meloxwatch.queue

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
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
import io.github.onlinemo.meloxwatch.data.queue.QueuePageSource
import io.github.onlinemo.meloxwatch.data.queue.QueueReplacementRequest
import io.github.onlinemo.meloxwatch.data.queue.QueueStore
import io.github.onlinemo.meloxwatch.data.queue.QueueStoreException
import io.github.onlinemo.meloxwatch.data.queue.QueueStoreIssue
import io.github.onlinemo.meloxwatch.data.queue.RoomQueueStore
import io.github.onlinemo.meloxwatch.data.sleep.SleepTimerStore
import io.github.onlinemo.meloxwatch.data.sleep.SleepTimerStoreCodec
import io.github.onlinemo.meloxwatch.data.sleep.createSleepTimerDataStore
import io.github.onlinemo.meloxwatch.model.FrozenQueueView
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import io.github.onlinemo.meloxwatch.model.QueueCheckpoint
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueMode
import io.github.onlinemo.meloxwatch.model.QueueMutation
import io.github.onlinemo.meloxwatch.model.QueueMutationRequest
import io.github.onlinemo.meloxwatch.model.QueueRepeatMode
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.model.UserPreferences
import io.github.onlinemo.meloxwatch.model.UserPreferencesDraft
import io.github.onlinemo.meloxwatch.playback.PlaybackAudioFocusOwner
import io.github.onlinemo.meloxwatch.playback.PlaybackBackendIssue
import io.github.onlinemo.meloxwatch.playback.PlaybackBackendOperations
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackCoordinator
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackFailure
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue
import io.github.onlinemo.meloxwatch.playback.sleep.ServiceSleepTimer
import io.github.onlinemo.meloxwatch.playback.sleep.SleepTimerClockSample
import io.github.onlinemo.meloxwatch.playback.sleep.SleepTimerEndMode
import io.github.onlinemo.meloxwatch.playback.sleep.SleepTimerNotification
import io.github.onlinemo.meloxwatch.playback.sleep.SleepTimerPersistence
import io.github.onlinemo.meloxwatch.playback.sleep.SleepTimerScheduler
import io.github.onlinemo.meloxwatch.playback.sleep.SleepTimerStopReason
import io.github.onlinemo.meloxwatch.playback.sleep.SleepTimerSummary
import io.github.onlinemo.meloxwatch.playback.sleep.SleepTimerToken
import io.github.onlinemo.meloxwatch.playback.sleep.SleepTimerTrackBinding
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import org.robolectric.shadows.ShadowAudioManager

/** File Room/DataStore and production timer/queue/focus logic; the controlled player emits no audio. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class QueueSleepTimerIntegrationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var databaseFile: File
    private lateinit var database: MeloXDatabase
    private lateinit var store: RoomQueueStore
    private lateinit var gatedStore: CheckpointStore
    private lateinit var timerData: DataStore<Preferences>
    private lateinit var storageJob: Job
    private lateinit var scope: CoroutineScope
    private lateinit var coordinator: QueuePlaybackCoordinator
    private lateinit var timer: ServiceSleepTimer
    private lateinit var focus: PlaybackAudioFocusOwner
    private lateinit var audio: ShadowAudioManager
    private lateinit var backend: CheckedPlayer
    private lateinit var operations: ControlledOperations
    private val scheduler = ControlledScheduler()
    private val notification = ControlledNotification()
    private val backends = mutableListOf<CheckedPlayer>()
    private val phaseGates = mutableListOf<PhaseGate>()
    private val checkpointGates = mutableListOf<CheckpointGate>()
    private val testCommands = mutableListOf<Job>()
    private val rounds = mutableListOf<String>()
    private val retainedBindings = mutableListOf<Pair<SleepTimerTrackBinding, SleepTimerTrackBinding>>()
    private val failures = mutableListOf<QueuePlaybackFailure>()
    private val focusIssues = mutableListOf<PlaybackBackendIssue>()
    private var resolveHook: suspend (QueueEntry) -> QueueEntry = { it }
    private var roundHook: suspend (QueueEntry) -> Unit = {}
    private var elapsedMs = 1_000L
    private var userPauses = 0
    private var resumeRequests = 0
    private var operationOrdinal = 0

    @Before fun open() {
        val application: Application = RuntimeEnvironment.getApplication()
        shadowOf(application).grantPermissions(application.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        audio = shadowOf(checkNotNull(application.getSystemService(AudioManager::class.java)))
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        databaseFile = File(temporaryFolder.root, "queue.db")
        database = openDatabase(databaseFile)
        store = RoomQueueStore(database, shuffleIndex = { it })
        gatedStore = CheckpointStore(store)
        storageJob = SupervisorJob()
        timerData = createSleepTimerDataStore(CoroutineScope(storageJob + Dispatchers.IO)) {
            File(temporaryFolder.root, "timer.preferences_pb")
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val pair = createBackend()
        backend = pair.first
        operations = pair.second
        focus = PlaybackAudioFocusOwner(application, { backend }, { coordinator.sessionBackendGeneration() },
            { true }, { coordinator.pauseForInterruption() }, { coordinator.requestCheckpoint() },
            {
                resumeRequests += 1
                coordinator.playFromSession()
            }, focusIssues::add)
        coordinator = QueuePlaybackCoordinator(backend, gatedStore, FixedPreferences(), scope,
            resolveEntry = { resolveHook(it) },
            onRoundChanged = {
                rounds.add(it.queueEntryId)
                roundHook(it)
            },
            onFailure = failures::add,
            operations = operations,
            requestPlay = { timer.beforePlay() && focus.requestPlay() },
            onUserPause = {
                userPauses += 1
                focus.userPause()
                timer.onUserPause()
            },
            onUserIntentChanged = focus::cancelResumeIntent,
            onManualTrackChange = { timer.onManualTrackChange() },
            onRetainedTrackBinding = { previous, current ->
                retainedBindings.add(previous to current)
                timer.onRetainedTrackBinding(previous, current)
            },
            beforeNaturalAdvance = { timer.beforeNaturalAdvance(it) },
        )
        timer = ServiceSleepTimer(SleepTimerStore(timerData), scope,
            { SleepTimerClockSample("fixture-boot", elapsedMs) }, coordinator::sleepTimerTrackBinding,
            scheduler, notification,
            {
                focus.userPause()
                coordinator.pauseForSleepTimer()
            }, focus::userPause)
        onMain {
            timer.initialize()
            coordinator.initialize()
        }
    }

    @After fun close() {
        phaseGates.forEach { it.release() }
        checkpointGates.forEach { it.release() }
        try {
            timer.detach()
            focus.close()
            coordinator.detach()
        } finally {
            try {
                onMain {
                    testCommands.forEach { it.cancelAndJoin() }
                    try { coordinator.close() } finally { timer.close() }
                }
            } finally {
                try { backends.forEach { if (!it.released) it.release() } }
                finally {
                    scope.cancel()
                    runBlocking { storageJob.cancelAndJoin() }
                    database.close()
                }
            }
        }
        backends.forEach { assertEquals("Released backend API used", 0, it.lateCalls) }
    }

    @Test fun naturalEndBeforeTheCutoffStillRepeatsAndKeepsTheSameTimer() = onMain {
        seed(QueueMode(repeat = QueueRepeatMode.SINGLE, cycleLimit = 2))
        val token = arm(SleepTimerEndMode.AFTER_CURRENT_TRACK)
        val previous = binding()
        elapsedMs = token.deadlineElapsedMs - 1L
        backend.fake.completeNaturally()
        awaitQueueIdle()
        val saved = requireNotNull(store.snapshot())
        assertEquals(previous.queueEntryId, saved.currentEntryId)
        assertEquals(1, saved.checkpoint.completedCycles)
        assertEquals(0L, saved.positionMs)
        assertEquals(previous.playbackCycle + 1L, binding().playbackCycle)
        assertTrue(backend.playWhenReady)
        assertEquals(token, timer.state.value.timer.active?.token)
        assertNull(timer.state.value.timer.active?.waitingFor)
        awaitTimerSaved()
        assertEquals(timer.state.value.timer, SleepTimerStoreCodec.decode(timerData.data.first()).state)
    }

    @Test fun naturalEndAtTheExactCutoffBeatsALateDeadlineTaskAndDuplicateEndEvents() = onMain {
        seed(QueueMode(repeat = QueueRepeatMode.SINGLE, cycleLimit = 2))
        val token = arm(SleepTimerEndMode.AFTER_CURRENT_TRACK)
        val lateTask = requireNotNull(scheduler.deadline)
        val ending = binding()
        val initialRounds = rounds.toList()
        val initialPlays = backend.playCalls
        elapsedMs = token.deadlineElapsedMs
        backend.fake.completeNaturally()
        assertStopped(ending, 1, SleepTimerStopReason.CURRENT_TRACK_ENDED)
        assertEquals(initialRounds, rounds)
        assertEquals(initialPlays, backend.playCalls)

        lateTask.action()
        backend.repeatNaturalCallbacks()
        assertStopped(ending, 1, SleepTimerStopReason.CURRENT_TRACK_ENDED)
        assertEquals(initialPlays, backend.playCalls)
        assertEquals(initialRounds, rounds)
    }

    @Test fun aWaitingDeadlineWinsBeforeFiniteSingleRepeat() = onMain {
        waitingDeadlineStopsRepeat(QueueMode(repeat = QueueRepeatMode.SINGLE, cycleLimit = 2))
    }

    @Test fun aWaitingDeadlineWinsBeforeUnlimitedSingleRepeat() = onMain {
        waitingDeadlineStopsRepeat(QueueMode(repeat = QueueRepeatMode.SINGLE))
    }

    @Test fun cancellingTheRealTimerMakesItsOldDeadlineUnableToPauseTheQueue() = onMain {
        seed(QueueMode(repeat = QueueRepeatMode.LIST))
        val token = arm(SleepTimerEndMode.AFTER_CURRENT_TRACK)
        val lateTask = requireNotNull(scheduler.deadline)
        val previous = requireNotNull(store.snapshot())
        val initialPlays = backend.playCalls
        assertTrue(timer.cancel(token.generation))
        awaitTimerSaved()
        elapsedMs = token.deadlineElapsedMs
        lateTask.action()
        coordinator.flush()
        assertTrue(backend.playWhenReady)
        assertEquals(initialPlays, backend.playCalls)
        assertEquals(previous.checkpoint, store.snapshot()?.checkpoint)
        assertEquals(SleepTimerStopReason.USER_CANCELLED, timer.state.value.timer.lastStop?.reason)
        assertEquals(timer.state.value.timer, SleepTimerStoreCodec.decode(timerData.data.first()).state)
    }

    @Test fun waitingCutoffDuringTheRealNonCancellableCheckpointSettlesTheOldEndingOnce() = onMain {
        cutoffDuringCheckpoint(SleepTimerEndMode.AFTER_CURRENT_TRACK)
    }

    @Test fun immediateCutoffDuringTheRealNonCancellableCheckpointSettlesWithoutCancellingItsReceipt() = onMain {
        cutoffDuringCheckpoint(SleepTimerEndMode.PAUSE_IMMEDIATELY)
    }

    @Test fun backendSwitchDuringAWaitingCutoffReceiptKeepsTheCompletedOldTraversal() = onMain {
        backendSwitchDuringCheckpoint(SleepTimerEndMode.AFTER_CURRENT_TRACK)
    }

    @Test fun backendSwitchDuringAnImmediateCutoffReceiptCannotCancelTheEndingCompensation() = onMain {
        backendSwitchDuringCheckpoint(SleepTimerEndMode.PAUSE_IMMEDIATELY)
    }

    @Test fun aRealEndingCheckpointConflictBlocksBackendSwitchAndExplicitRetryCountsTheEndingOnce() = onMain {
        seed(QueueMode(repeat = QueueRepeatMode.SINGLE, cycleLimit = 2), completedCycles = 1)
        val token = arm(SleepTimerEndMode.AFTER_CURRENT_TRACK)
        val ending = binding()
        val initialRounds = rounds.toList()
        val old = backend
        val plays = old.playCalls
        val gate = checkpointGate {
            it.queueEntryId == "entry-2" && it.positionMs == 0L && it.completedCycles == 0
        }
        elapsedMs = token.deadlineElapsedMs - 1L
        old.fake.completeNaturally()
        assertEquals("entry-2", gate.committed.await().currentEntryId)
        val compensationReceiptStart = gatedStore.receipts.size
        elapsedMs = token.deadlineElapsedMs
        scheduler.fireDeadline()
        assertEquals(ending, timer.state.value.timer.active?.waitingFor)
        val endingCheckpoint = QueueCheckpoint(ending.queueEntryId, DURATION_MS, 2)
        gatedStore.conflictNextCheckpoint = { it == endingCheckpoint }

        val freezing = asyncCommand(start = CoroutineStart.UNDISPATCHED) {
            coordinator.freezeForBackendSwitch()
        }
        assertFalse(freezing.isCompleted)
        gate.release()
        val failure = runCatching { freezing.await() }.exceptionOrNull()
        assertTrue("Actual Room CAS must reject the stale revision", failure is QueueStoreException)
        assertEquals(QueueStoreIssue.CONFLICT, (failure as QueueStoreException).issue)
        assertEquals(endingCheckpoint, gatedStore.rejectedCheckpoint)
        assertEquals("entry-2", gatedStore.competingReceipt?.currentEntryId)
        assertEquals(0, gatedStore.competingReceipt?.checkpoint?.completedCycles)
        assertTrue(gate.exited.isCompleted)
        assertSame(old, coordinator.sessionBackend())
        assertFalse(old.released)
        assertFalse(old.playWhenReady)
        assertEquals(1, backends.size)
        assertTrue(coordinator.acceptsSessionCommands())
        assertEquals("entry-2", store.snapshot()?.currentEntryId)
        assertEquals(0, store.snapshot()?.checkpoint?.completedCycles)
        assertNull(timer.state.value.timer.active)
        assertEquals(SleepTimerStopReason.CURRENT_TRACK_ENDED, timer.state.value.timer.lastStop?.reason)
        assertEquals(initialRounds, rounds)
        assertEquals(plays, old.playCalls)

        val checkpoint = coordinator.freezeForBackendSwitch()
        assertEquals(endingCheckpoint, checkpoint.snapshot?.checkpoint)
        assertEquals(2, checkpoint.completedCurrentCycles)
        assertStopped(ending, 2, SleepTimerStopReason.CURRENT_TRACK_ENDED)
        focus.unbindBackend()
        coordinator.unbindBackendForSwitch(checkpoint)
        old.release()
        val replacement = createBackend()
        backend = replacement.first
        operations = replacement.second
        coordinator.restoreAfterBackendSwitch(backend, operations, checkpoint)
        assertEquals(ending, binding())
        backend.repeatNaturalCallbacks()
        assertStopped(ending, 2, SleepTimerStopReason.CURRENT_TRACK_ENDED)
        val endingCounts = gatedStore.receipts.drop(compensationReceiptStart).filter {
            it.currentEntryId == ending.queueEntryId && it.positionMs == DURATION_MS
        }.map { it.checkpoint.completedCycles }.toSet()
        assertEquals(setOf(2), endingCounts)
        assertEquals(0, backend.playCalls)
        assertEquals(initialRounds, rounds)
    }

    @Test fun aBackendGetterFailureDuringEndingCaptureLeavesBackendSwitchExplicitlyRetryable() = onMain {
        seed(QueueMode(repeat = QueueRepeatMode.SINGLE, cycleLimit = 2), completedCycles = 1)
        val token = arm(SleepTimerEndMode.AFTER_CURRENT_TRACK)
        val ending = binding()
        val initialRounds = rounds.toList()
        val old = backend
        val plays = old.playCalls
        val gate = checkpointGate {
            it.queueEntryId == "entry-2" && it.positionMs == 0L && it.completedCycles == 0
        }
        elapsedMs = token.deadlineElapsedMs - 1L
        old.fake.completeNaturally()
        assertEquals("entry-2", gate.committed.await().currentEntryId)
        elapsedMs = token.deadlineElapsedMs
        scheduler.fireDeadline()
        old.failNextCurrentMediaItemRead = true

        val failure = runCatching { coordinator.freezeForBackendSwitch() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("Controlled backend item read failed", failure?.message)
        assertEquals(1, old.failedCurrentMediaItemReads)
        assertSame(old, coordinator.sessionBackend())
        assertFalse(old.released)
        assertFalse(old.playWhenReady)
        assertEquals(1, backends.size)
        assertTrue(coordinator.acceptsSessionCommands())
        assertFalse(gate.exited.isCompleted)
        assertEquals(ending, timer.state.value.timer.active?.waitingFor)
        assertEquals(initialRounds, rounds)
        assertEquals(plays, old.playCalls)

        val freezing = asyncCommand(start = CoroutineStart.UNDISPATCHED) {
            coordinator.freezeForBackendSwitch()
        }
        assertFalse(freezing.isCompleted)
        gate.release()
        val checkpoint = freezing.await()
        assertEquals(QueueCheckpoint(ending.queueEntryId, DURATION_MS, 2), checkpoint.snapshot?.checkpoint)
        assertStopped(ending, 2, SleepTimerStopReason.CURRENT_TRACK_ENDED)
        focus.unbindBackend()
        coordinator.unbindBackendForSwitch(checkpoint)
        old.release()
        val replacement = createBackend()
        backend = replacement.first
        operations = replacement.second
        coordinator.restoreAfterBackendSwitch(backend, operations, checkpoint)
        assertEquals(ending, binding())
        assertStopped(ending, 2, SleepTimerStopReason.CURRENT_TRACK_ENDED)
        assertEquals(1, old.failedCurrentMediaItemReads)
        assertEquals(0, backend.playCalls)
        assertEquals(initialRounds, rounds)
    }

    @Test fun aCutoffDuringCheckpointKeepsTheSaturatedCycleCounterAndCannotRepeatAgain() = onMain {
        cutoffDuringCheckpoint(SleepTimerEndMode.PAUSE_IMMEDIATELY, saturated = true)
    }

    @Test fun waitingCutoffDuringAnUncooperativeResolverStopsBeforeTheLoadedTraversalChanges() = onMain {
        cutoffDuringResolver(SleepTimerEndMode.AFTER_CURRENT_TRACK)
    }

    @Test fun immediateCutoffDuringAnUncooperativeResolverSettlesTheEndingAndNeverStartsItsSuccessor() = onMain {
        cutoffDuringResolver(SleepTimerEndMode.PAUSE_IMMEDIATELY)
    }

    @Test fun waitingCutoffInsideTheFinalBackendPauseCannotHandOverTheOldEnding() = onMain {
        cutoffDuringHandoverPause(SleepTimerEndMode.AFTER_CURRENT_TRACK)
    }

    @Test fun immediateCutoffInsideTheFinalBackendPauseStillSettlesTheOldEnding() = onMain {
        cutoffDuringHandoverPause(SleepTimerEndMode.PAUSE_IMMEDIATELY)
    }

    @Test fun waitingCutoffAfterBackendHandoverBindsTheNewTraversalDuringPrepare() = onMain {
        waitingCutoffAfterHandover(atRoundObserver = false)
    }

    @Test fun waitingCutoffAfterBackendHandoverBindsTheNewTraversalDuringTheRoundObserver() = onMain {
        waitingCutoffAfterHandover(atRoundObserver = true)
    }

    @Test fun immediateCutoffDuringUncooperativeBackendPrepareRejectsItsLateCompletion() = onMain {
        immediateCutoffAfterHandover(duringSeek = false)
    }

    @Test fun immediateCutoffDuringUncooperativeBackendSeekRejectsItsLateAcknowledgement() = onMain {
        immediateCutoffAfterHandover(duringSeek = true)
    }

    @Test fun backendSwitchKeepsTheWaitingTraversalAndRestoresTheSingleSessionPaused() = onMain {
        seed(QueueMode(repeat = QueueRepeatMode.SINGLE, cycleLimit = 2), completedCycles = 1)
        val token = arm(SleepTimerEndMode.AFTER_CURRENT_TRACK)
        elapsedMs = token.deadlineElapsedMs
        scheduler.fireDeadline()
        val ending = binding()
        val initialRounds = rounds.toList()
        val session = coordinator.sessionPlayer
        val old = backend
        val oldListeners = old.listeners.toList()
        val oldFocusRequest = audio.lastAudioFocusRequest
        val checkpoint = coordinator.freezeForBackendSwitch()
        focus.unbindBackend()
        coordinator.unbindBackendForSwitch(checkpoint)
        old.release()
        val replacement = createBackend()
        backend = replacement.first
        operations = replacement.second
        coordinator.restoreAfterBackendSwitch(backend, operations, checkpoint)
        assertSame(session, coordinator.sessionPlayer)
        assertEquals(ending, binding())
        assertEquals(ending, timer.state.value.timer.active?.waitingFor)
        assertEquals(token, timer.state.value.timer.active?.token)
        assertEquals(1_234L, backend.currentPosition)
        assertEquals(1, store.snapshot()?.checkpoint?.completedCycles)
        assertFalse(backend.playWhenReady)
        assertEquals(0, backend.playCalls)
        assertEquals(initialRounds, rounds)

        oldFocusRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        oldListeners.forEach { listener ->
            listener.onPlaybackStateChanged(Player.STATE_ENDED)
            listener.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
        }
        assertFalse(backend.playWhenReady)
        assertEquals(0, old.lateCalls)
        assertEquals(0, backend.playCalls)
        coordinator.sessionPlayer.play()
        assertTrue(backend.playWhenReady)
        backend.fake.completeNaturally()
        assertStopped(ending, 2, SleepTimerStopReason.CURRENT_TRACK_ENDED)
        assertEquals(1, backend.playCalls)
        assertEquals(initialRounds, rounds)
    }

    @Test fun foregroundRefreshDuringBackendReplacementKeepsTheWaitingTraversal() = onMain {
        seed(QueueMode(repeat = QueueRepeatMode.SINGLE, cycleLimit = 2), completedCycles = 1)
        val token = arm(SleepTimerEndMode.AFTER_CURRENT_TRACK)
        elapsedMs = token.deadlineElapsedMs
        scheduler.fireDeadline()
        val waiting = binding()
        val initialRounds = rounds.toList()
        val session = coordinator.sessionPlayer
        val checkpoint = coordinator.freezeForBackendSwitch()
        focus.unbindBackend()
        coordinator.unbindBackendForSwitch(checkpoint)
        backend.release()

        // Entering the timer page can request refresh while the old backend is releasing.
        timer.refresh()
        awaitTimerSaved()
        assertEquals(token, timer.state.value.timer.active?.token)
        assertEquals(waiting, timer.state.value.timer.active?.waitingFor)
        assertNull(timer.state.value.timer.lastStop)

        val gate = phaseGate()
        resolveHook = {
            entry -> if (entry.queueEntryId == waiting.queueEntryId) gate.hold()
            entry
        }
        val replacement = createBackend()
        backend = replacement.first
        operations = replacement.second
        val restoring = asyncCommand { coordinator.restoreAfterBackendSwitch(backend, operations, checkpoint) }
        gate.entered.await()
        // The replacement is bound, but source resolution has not loaded its window yet.
        timer.refresh()
        awaitTimerSaved()
        assertEquals(token, timer.state.value.timer.active?.token)
        assertEquals(waiting, timer.state.value.timer.active?.waitingFor)
        assertNull(timer.state.value.timer.lastStop)
        assertFalse(backend.playWhenReady)
        gate.release()
        restoring.await()
        assertSame(session, coordinator.sessionPlayer)
        assertEquals(waiting, binding())
        assertEquals(1_234L, backend.currentPosition)
        assertEquals(1, store.snapshot()?.checkpoint?.completedCycles)
        assertFalse(backend.playWhenReady)
        assertEquals(0, backend.playCalls)
        assertEquals(initialRounds, rounds)
        assertEquals(timer.state.value.timer, SleepTimerStoreCodec.decode(timerData.data.first()).state)

        coordinator.sessionPlayer.play()
        backend.fake.completeNaturally()
        assertStopped(waiting, 2, SleepTimerStopReason.CURRENT_TRACK_ENDED)
        assertEquals(1, backend.playCalls)
        assertEquals(initialRounds, rounds)
    }

    @Test fun foregroundRefreshAfterBackendReplacementFailureSettlesTheUnavailableWaitingTraversal() = onMain {
        seed(QueueMode(repeat = QueueRepeatMode.SINGLE, cycleLimit = 2), completedCycles = 1)
        val token = arm(SleepTimerEndMode.AFTER_CURRENT_TRACK)
        elapsedMs = token.deadlineElapsedMs
        scheduler.fireDeadline()
        val waiting = binding()
        val initialRounds = rounds.toList()
        val saved = requireNotNull(store.snapshot())
        val checkpoint = coordinator.freezeForBackendSwitch()
        focus.unbindBackend()
        coordinator.unbindBackendForSwitch(checkpoint)
        backend.release()
        val failure = QueuePlaybackFailure(QueuePlaybackIssue.OUTPUT_ERROR, waiting.queueEntryId)
        coordinator.failBackendSwitch(checkpoint, failure)

        timer.refresh()
        awaitQueueIdle()
        awaitTimerSaved()
        assertNull(coordinator.sleepTimerTrackBinding())
        assertNull(timer.state.value.timer.active)
        assertEquals(token, timer.state.value.timer.lastStop?.token)
        assertEquals(SleepTimerStopReason.DEADLINE_REACHED, timer.state.value.timer.lastStop?.reason)
        assertEquals(saved.checkpoint, store.snapshot()?.checkpoint)
        assertEquals(timer.state.value.timer, SleepTimerStoreCodec.decode(timerData.data.first()).state)
        assertEquals(listOf(failure), failures)
        assertTrue(focusIssues.isEmpty())
        assertEquals(initialRounds, rounds)
        assertFalse(coordinator.acceptsSessionCommands())
    }

    @Test fun cutoffWhileTheBackendIsTemporarilyUnboundKeepsTheTimerWaitingForThePausedTraversal() = onMain {
        seed(QueueMode(repeat = QueueRepeatMode.SINGLE, cycleLimit = 2), completedCycles = 1)
        val token = arm(SleepTimerEndMode.AFTER_CURRENT_TRACK)
        val deadline = requireNotNull(scheduler.deadline)
        val waiting = binding()
        val initialRounds = rounds.toList()
        val session = coordinator.sessionPlayer
        val oldFocusRequest = audio.lastAudioFocusRequest
        val checkpoint = coordinator.freezeForBackendSwitch()
        focus.unbindBackend()
        coordinator.unbindBackendForSwitch(checkpoint)
        backend.release()
        assertEquals(waiting, coordinator.sleepTimerTrackBinding())
        elapsedMs = token.deadlineElapsedMs
        deadline.action()
        val replacement = createBackend()
        backend = replacement.first
        operations = replacement.second
        coordinator.restoreAfterBackendSwitch(backend, operations, checkpoint)
        awaitQueueIdle()
        awaitTimerSaved()
        assertSame(session, coordinator.sessionPlayer)
        assertFalse(backend.playWhenReady)
        assertEquals(0, backend.playCalls)
        assertEquals(1_234L, backend.currentPosition)
        assertEquals(1, store.snapshot()?.checkpoint?.completedCycles)
        assertEquals(token, timer.state.value.timer.active?.token)
        assertEquals(waiting, timer.state.value.timer.active?.waitingFor)
        assertNull(timer.state.value.timer.lastStop)
        assertEquals(timer.state.value.timer, SleepTimerStoreCodec.decode(timerData.data.first()).state)
        oldFocusRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(backend.playWhenReady)
        assertEquals(0, backend.playCalls)
        assertTrue(failures.isEmpty())
        assertTrue(focusIssues.isEmpty())
        assertEquals(initialRounds, rounds)
        coordinator.sessionPlayer.play()
        backend.fake.completeNaturally()
        assertStopped(waiting, 2, SleepTimerStopReason.CURRENT_TRACK_ENDED)
        assertEquals(1, backend.playCalls)
        assertEquals(initialRounds, rounds)
    }

    @Test fun focusAndNoisyPausesKeepWaitingButAnExplicitPauseCancelsItEvenWhenAlreadyPaused() = onMain {
        seed(QueueMode(repeat = QueueRepeatMode.SINGLE, cycleLimit = 2))
        val token = arm(SleepTimerEndMode.AFTER_CURRENT_TRACK)
        elapsedMs = token.deadlineElapsedMs
        scheduler.fireDeadline()
        val waiting = binding()
        val initialRounds = rounds.toList()
        val request = audio.lastAudioFocusRequest
        request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse(backend.playWhenReady)
        assertEquals(waiting, timer.state.value.timer.active?.waitingFor)
        assertEquals(0, userPauses)
        request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(backend.playWhenReady)
        assertEquals(1, resumeRequests)
        assertEquals(initialRounds, rounds)
        request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(backend.playWhenReady)
        assertEquals(waiting, timer.state.value.timer.active?.waitingFor)
        assertEquals(0, userPauses)

        coordinator.sessionPlayer.play()
        val noisyRequest = audio.lastAudioFocusRequest
        val application: Application = RuntimeEnvironment.getApplication()
        application.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        withTimeout(10_000L) { while (backend.playWhenReady) yield() }
        noisyRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        application.sendBroadcast(Intent(AudioManager.ACTION_HEADSET_PLUG).putExtra("state", 1))
        yield()
        assertFalse(backend.playWhenReady)
        assertEquals(waiting, timer.state.value.timer.active?.waitingFor)
        assertEquals(0, userPauses)
        coordinator.sessionPlayer.pause()
        awaitTimerSaved()
        assertEquals(1, userPauses)
        assertNull(timer.state.value.timer.active)
        assertEquals(SleepTimerStopReason.USER_PAUSED, timer.state.value.timer.lastStop?.reason)
        assertEquals(timer.state.value.timer, SleepTimerStoreCodec.decode(timerData.data.first()).state)
        assertFalse(backend.playWhenReady)
        assertEquals(initialRounds, rounds)
    }

    @Test fun retainedMoveAndShuffleEditTransferWaitingIdentityWithoutPauseSeekOrANewRound() = onMain {
        seed(QueueMode(repeat = QueueRepeatMode.SINGLE, cycleLimit = 2), completedCycles = 1)
        val token = arm(SleepTimerEndMode.AFTER_CURRENT_TRACK)
        elapsedMs = token.deadlineElapsedMs
        scheduler.fireDeadline()
        val original = binding()
        val initialRounds = rounds.toList()
        val pauses = backend.pauseCalls
        val seeks = backend.seekCalls
        val prepares = operations.prepares
        val plays = backend.playCalls
        val before = requireNotNull(store.snapshot())
        val moved = coordinator.mutate(edit("retained-move", before, QueueMutation.Move("entry-0", 2L))).snapshot
        val shuffled = coordinator.mutate(edit("retained-shuffle", moved,
            QueueMutation.SetMode(QueueMode(shuffle = true, repeat = QueueRepeatMode.SINGLE, cycleLimit = 2)))).snapshot
        val current = binding()
        assertNotEquals(original.queueGenerationId, current.queueGenerationId)
        assertEquals(original.sessionId, current.sessionId)
        assertEquals(original.queueEntryId, current.queueEntryId)
        assertEquals(original.playbackCycle, current.playbackCycle)
        assertEquals(shuffled.generationId, current.queueGenerationId)
        assertEquals(current, timer.state.value.timer.active?.waitingFor)
        assertEquals(token, timer.state.value.timer.active?.token)
        assertEquals(1, shuffled.checkpoint.completedCycles)
        assertEquals(2, retainedBindings.size)
        assertEquals(pauses, backend.pauseCalls)
        assertEquals(seeks, backend.seekCalls)
        assertEquals(prepares, operations.prepares)
        assertEquals(plays, backend.playCalls)
        assertEquals(initialRounds, rounds)
        assertTrue(backend.playWhenReady)
        awaitTimerSaved()
        assertEquals(current, SleepTimerStoreCodec.decode(timerData.data.first()).state.active?.waitingFor)
        backend.fake.completeNaturally()
        assertStopped(current, 2, SleepTimerStopReason.CURRENT_TRACK_ENDED)
        assertEquals(initialRounds, rounds)
        assertEquals(plays, backend.playCalls)
    }

    @Test fun cutoffAndNaturalEndDuringARetainedEditFollowItsNewGenerationAndCountOnce() = onMain {
        seed(QueueMode(repeat = QueueRepeatMode.SINGLE, cycleLimit = 2), completedCycles = 1)
        val token = arm(SleepTimerEndMode.AFTER_CURRENT_TRACK)
        val original = binding()
        val initialRounds = rounds.toList()
        val plays = backend.playCalls
        val gate = phaseGate(nonCooperative = true)
        var intercepted = false
        resolveHook = { entry ->
            if (!intercepted && entry.queueEntryId == original.queueEntryId) {
                intercepted = true
                gate.hold()
            }
            entry
        }
        val before = requireNotNull(store.snapshot())
        val editing = asyncCommand { coordinator.mutate(edit("editing-at-cutoff", before, QueueMutation.Move("entry-0", 2L))) }
        gate.entered.await()
        elapsedMs = token.deadlineElapsedMs
        scheduler.fireDeadline()
        assertEquals(original, timer.state.value.timer.active?.waitingFor)
        backend.fake.completeNaturally()
        assertEquals(1, store.snapshot()?.checkpoint?.completedCycles)
        gate.release()
        val edited = editing.await().snapshot
        awaitQueueIdle()
        val current = binding()
        assertNotEquals(original.queueGenerationId, current.queueGenerationId)
        assertEquals(edited.generationId, current.queueGenerationId)
        assertEquals(original.playbackCycle, current.playbackCycle)
        assertEquals(original.queueEntryId, current.queueEntryId)
        assertStopped(current, 2, SleepTimerStopReason.CURRENT_TRACK_ENDED)
        assertEquals(listOf(original to current), retainedBindings)
        assertEquals(initialRounds, rounds)
        assertEquals(plays, backend.playCalls)
        backend.repeatNaturalCallbacks()
        assertStopped(current, 2, SleepTimerStopReason.CURRENT_TRACK_ENDED)
    }

    @Test fun anAcceptedManualSelectionCancelsTheWaitingTimerAndItsLateTaskCannotPauseTheNewTrack() = onMain {
        seed(QueueMode(repeat = QueueRepeatMode.SINGLE, cycleLimit = 2))
        val token = arm(SleepTimerEndMode.AFTER_CURRENT_TRACK)
        val oldTask = requireNotNull(scheduler.deadline)
        val waiting = binding()
        elapsedMs = token.deadlineElapsedMs
        scheduler.fireDeadline()
        assertEquals(waiting, timer.state.value.timer.active?.waitingFor)
        val previous = requireNotNull(store.snapshot())
        coordinator.select(previous.generationId, previous.queueRevision, "entry-2", explicitPlay = true)
        awaitQueueIdle()
        awaitTimerSaved()
        assertEquals(SleepTimerStopReason.USER_CHANGED_TRACK, timer.state.value.timer.lastStop?.reason)
        assertEquals("entry-2", backend.currentMediaItem?.mediaId)
        assertTrue(backend.playWhenReady)
        val plays = backend.playCalls
        elapsedMs = token.deadlineElapsedMs
        oldTask.action()
        assertTrue(backend.playWhenReady)
        assertEquals(plays, backend.playCalls)
        assertEquals("entry-2", store.snapshot()?.currentEntryId)
        assertEquals(0, store.snapshot()?.checkpoint?.completedCycles)
        assertEquals(timer.state.value.timer, SleepTimerStoreCodec.decode(timerData.data.first()).state)
    }

    private suspend fun waitingDeadlineStopsRepeat(mode: QueueMode) {
        seed(mode)
        val token = arm(SleepTimerEndMode.AFTER_CURRENT_TRACK)
        val ending = binding()
        val initialRounds = rounds.toList()
        val plays = backend.playCalls
        elapsedMs = token.deadlineElapsedMs
        scheduler.fireDeadline()
        assertEquals(ending, timer.state.value.timer.active?.waitingFor)
        assertTrue(backend.playWhenReady)
        backend.fake.completeNaturally()
        assertStopped(ending, 1, SleepTimerStopReason.CURRENT_TRACK_ENDED)
        assertEquals(initialRounds, rounds)
        assertEquals(plays, backend.playCalls)
    }

    private suspend fun cutoffDuringCheckpoint(mode: SleepTimerEndMode, saturated: Boolean = false) {
        seed(if (saturated) QueueMode(repeat = QueueRepeatMode.SINGLE) else
            QueueMode(repeat = QueueRepeatMode.SINGLE, cycleLimit = 2),
            completedCycles = if (saturated) Int.MAX_VALUE else 1)
        val token = arm(mode)
        val ending = binding()
        val initialRounds = rounds.toList()
        val plays = backend.playCalls
        val target = if (saturated) ending.queueEntryId else "entry-2"
        val gate = checkpointGate { it.queueEntryId == target && it.positionMs == 0L &&
            it.completedCycles == (if (saturated) Int.MAX_VALUE else 0) }
        elapsedMs = token.deadlineElapsedMs - 1L
        backend.fake.completeNaturally()
        val accepted = gate.committed.await()
        assertEquals(target, accepted.currentEntryId)
        assertEquals(accepted, store.snapshot())
        assertEquals(ending, binding())
        assertFalse(gate.exited.isCompleted)
        assertTrue(coordinator.state.value.preparing)
        elapsedMs = token.deadlineElapsedMs
        scheduler.fireDeadline()
        if (mode == SleepTimerEndMode.AFTER_CURRENT_TRACK) assertEquals(ending, timer.state.value.timer.active?.waitingFor)
        else assertEquals(SleepTimerStopReason.DEADLINE_REACHED, timer.state.value.timer.lastStop?.reason)
        assertFalse(gate.exited.isCompleted)
        assertFalse(backend.playWhenReady)
        gate.release()
        assertStopped(ending, if (saturated) Int.MAX_VALUE else 2,
            if (mode == SleepTimerEndMode.AFTER_CURRENT_TRACK) SleepTimerStopReason.CURRENT_TRACK_ENDED else SleepTimerStopReason.DEADLINE_REACHED)
        assertTrue(gate.exited.isCompleted)
        assertEquals(initialRounds, rounds)
        assertEquals(plays, backend.playCalls)
    }

    private suspend fun cutoffDuringResolver(mode: SleepTimerEndMode) {
        seed(QueueMode(repeat = QueueRepeatMode.SINGLE, cycleLimit = 2), completedCycles = 1)
        val token = arm(mode)
        val ending = binding()
        val initialRounds = rounds.toList()
        val plays = backend.playCalls
        val gate = phaseGate(nonCooperative = true)
        var intercepted = false
        resolveHook = { entry ->
            if (!intercepted && entry.queueEntryId == "entry-2") {
                intercepted = true
                gate.hold()
            }
            entry
        }
        elapsedMs = token.deadlineElapsedMs - 1L
        backend.fake.completeNaturally()
        gate.entered.await()
        assertEquals(ending, binding())
        assertEquals("entry-2", store.snapshot()?.currentEntryId)
        elapsedMs = token.deadlineElapsedMs
        scheduler.fireDeadline()
        assertFalse(gate.exited.isCompleted)
        gate.release()
        assertStopped(ending, 2,
            if (mode == SleepTimerEndMode.AFTER_CURRENT_TRACK) SleepTimerStopReason.CURRENT_TRACK_ENDED else SleepTimerStopReason.DEADLINE_REACHED)
        assertEquals(initialRounds, rounds)
        assertEquals(plays, backend.playCalls)
    }

    private suspend fun backendSwitchDuringCheckpoint(mode: SleepTimerEndMode) {
        seed(QueueMode(repeat = QueueRepeatMode.SINGLE, cycleLimit = 2), completedCycles = 1)
        val token = arm(mode)
        val ending = binding()
        val initialRounds = rounds.toList()
        val old = backend
        val plays = old.playCalls
        val session = coordinator.sessionPlayer
        val gate = checkpointGate {
            it.queueEntryId == "entry-2" && it.positionMs == 0L && it.completedCycles == 0
        }
        elapsedMs = token.deadlineElapsedMs - 1L
        old.fake.completeNaturally()
        assertEquals("entry-2", gate.committed.await().currentEntryId)
        assertEquals(ending, binding())
        elapsedMs = token.deadlineElapsedMs
        scheduler.fireDeadline()
        if (mode == SleepTimerEndMode.AFTER_CURRENT_TRACK) {
            assertEquals(ending, timer.state.value.timer.active?.waitingFor)
        }

        val freezing = asyncCommand(start = CoroutineStart.UNDISPATCHED) {
            coordinator.freezeForBackendSwitch()
        }
        assertFalse(freezing.isCompleted)
        assertFalse(coordinator.acceptsSessionCommands())
        assertFalse(gate.exited.isCompleted)
        gate.release()
        val checkpoint = freezing.await()
        assertTrue(gate.exited.isCompleted)
        assertEquals(ending.queueEntryId, checkpoint.snapshot?.currentEntryId)
        assertEquals(DURATION_MS, checkpoint.positionMs)
        assertEquals(2, checkpoint.completedCurrentCycles)
        val reason = if (mode == SleepTimerEndMode.AFTER_CURRENT_TRACK) {
            SleepTimerStopReason.CURRENT_TRACK_ENDED
        } else SleepTimerStopReason.DEADLINE_REACHED
        assertStopped(ending, 2, reason)

        focus.unbindBackend()
        coordinator.unbindBackendForSwitch(checkpoint)
        old.release()
        val replacement = createBackend()
        backend = replacement.first
        operations = replacement.second
        coordinator.restoreAfterBackendSwitch(backend, operations, checkpoint)
        assertSame(session, coordinator.sessionPlayer)
        assertEquals(ending, binding())
        assertStopped(ending, 2, reason)
        assertEquals(plays, old.playCalls)
        assertEquals(0, backend.playCalls)
        assertEquals(initialRounds, rounds)
    }

    private suspend fun waitingCutoffAfterHandover(atRoundObserver: Boolean) {
        seed(QueueMode(repeat = QueueRepeatMode.SINGLE, cycleLimit = 2))
        val token = arm(SleepTimerEndMode.AFTER_CURRENT_TRACK)
        val previous = binding()
        val initialRounds = rounds.toList()
        val plays = backend.playCalls
        val gate = phaseGate()
        if (atRoundObserver) roundHook = { gate.hold() } else operations.prepareGate = gate
        elapsedMs = token.deadlineElapsedMs - 1L
        backend.fake.completeNaturally()
        gate.entered.await()
        val current = binding()
        assertEquals(previous.queueEntryId, current.queueEntryId)
        assertEquals(previous.playbackCycle + 1L, current.playbackCycle)
        assertFalse(backend.playWhenReady)
        elapsedMs = token.deadlineElapsedMs
        scheduler.fireDeadline()
        assertEquals(current, timer.state.value.timer.active?.waitingFor)
        gate.release()
        awaitQueueIdle()
        assertTrue(backend.playWhenReady)
        assertEquals(plays + 1, backend.playCalls)
        assertEquals(current, timer.state.value.timer.active?.waitingFor)
        assertEquals(1, store.snapshot()?.checkpoint?.completedCycles)
        val completedRounds = initialRounds + current.queueEntryId
        assertEquals(completedRounds, rounds)
        backend.fake.completeNaturally()
        assertStopped(current, 2, SleepTimerStopReason.CURRENT_TRACK_ENDED)
        assertEquals(completedRounds, rounds)
        assertEquals(plays + 1, backend.playCalls)
    }

    private suspend fun cutoffDuringHandoverPause(mode: SleepTimerEndMode) {
        seed(QueueMode(repeat = QueueRepeatMode.SINGLE, cycleLimit = 2), completedCycles = 1)
        val token = arm(mode)
        val ending = binding()
        val initialRounds = rounds.toList()
        val plays = backend.playCalls
        var installed = false
        var reached = false
        resolveHook = { entry ->
            if (!installed && entry.queueEntryId == "entry-2") {
                installed = true
                // The current resource has resolved; only the final backend pause remains
                // before its loaded binding changes. Advance time inside that synchronous call.
                backend.afterNextPause = {
                    reached = true
                    assertEquals(ending, binding())
                    elapsedMs = token.deadlineElapsedMs
                    scheduler.fireDeadline()
                    if (mode == SleepTimerEndMode.AFTER_CURRENT_TRACK) {
                        assertEquals(ending, timer.state.value.timer.active?.waitingFor)
                    }
                }
            }
            entry
        }
        elapsedMs = token.deadlineElapsedMs - 1L
        backend.fake.completeNaturally()
        assertStopped(ending, 2,
            if (mode == SleepTimerEndMode.AFTER_CURRENT_TRACK) SleepTimerStopReason.CURRENT_TRACK_ENDED else SleepTimerStopReason.DEADLINE_REACHED)
        assertTrue(installed)
        assertTrue(reached)
        assertEquals(initialRounds, rounds)
        assertEquals(plays, backend.playCalls)
    }

    private suspend fun immediateCutoffAfterHandover(duringSeek: Boolean) {
        seed(QueueMode(repeat = QueueRepeatMode.SINGLE, cycleLimit = 2))
        val token = arm(SleepTimerEndMode.PAUSE_IMMEDIATELY)
        val previous = binding()
        val plays = backend.playCalls
        val gate = phaseGate(nonCooperative = true)
        if (duringSeek) operations.seekGate = gate else operations.prepareGate = gate
        elapsedMs = token.deadlineElapsedMs - 1L
        backend.fake.completeNaturally()
        gate.entered.await()
        val current = binding()
        assertEquals(previous.playbackCycle + 1L, current.playbackCycle)
        elapsedMs = token.deadlineElapsedMs
        scheduler.fireDeadline()
        assertEquals(SleepTimerStopReason.DEADLINE_REACHED, timer.state.value.timer.lastStop?.reason)
        assertFalse(gate.exited.isCompleted)
        assertFalse(backend.playWhenReady)
        val existingRounds = rounds.toList()
        gate.release()
        awaitQueueIdle()
        awaitTimerSaved()
        assertTrue(gate.exited.isCompleted)
        assertFalse(backend.playWhenReady)
        assertEquals(plays, backend.playCalls)
        assertEquals(existingRounds, rounds)
        assertEquals(current.queueEntryId, store.snapshot()?.currentEntryId)
        assertEquals(1, store.snapshot()?.checkpoint?.completedCycles)
        assertNull(timer.state.value.timer.active)
        assertEquals(timer.state.value.timer, SleepTimerStoreCodec.decode(timerData.data.first()).state)
        assertTrue(failures.isEmpty())
        assertTrue(focusIssues.isEmpty())
    }

    private suspend fun seed(mode: QueueMode, completedCycles: Int = 0) {
        val previous = store.snapshot()
        val operation = "seed-${operationOrdinal++}"
        val view = FrozenQueueView(operation, 1L, 3L)
        coordinator.replace(QueueReplacementRequest(operation, previous?.generationId, previous?.queueRevision ?: 0L,
            view, mode, startOriginalOrder = 1L, initialPositionMs = 1_234L), QueuePageSource { offset, limit ->
            QueueInputPage(view, offset, List(limit) { entry(offset + it) })
        }, explicitPlay = true)
        if (completedCycles != 0) {
            val saved = requireNotNull(store.snapshot())
            store.saveCheckpoint(saved.generationId, saved.checkpointRevision,
                QueueCheckpoint(saved.currentEntryId, 1_234L, completedCycles))
            coordinator.initialize()
            coordinator.sessionPlayer.play()
        }
        coordinator.flush()
        assertTrue(backend.playWhenReady)
        assertEquals("entry-1", backend.currentMediaItem?.mediaId)
        assertTrue(backend.peakWindow <= 3)
        assertTrue(failures.isEmpty())
        assertTrue(focusIssues.isEmpty())
    }

    private suspend fun arm(mode: SleepTimerEndMode): SleepTimerToken {
        assertTrue(timer.arm(1, mode, timer.generationId))
        awaitTimerSaved()
        return requireNotNull(timer.state.value.timer.active).token
    }

    private fun binding() = requireNotNull(coordinator.sleepTimerTrackBinding())

    private suspend fun awaitTimerSaved() = withTimeout(10_000L) {
        val saved = timer.state.first { it.persistence == SleepTimerPersistence.SAVED || it.persistence == SleepTimerPersistence.FAILED }
        assertEquals(SleepTimerPersistence.SAVED, saved.persistence)
        assertNull(saved.issue)
        assertFalse(saved.conflict)
    }

    private suspend fun awaitQueueIdle() = withTimeout(10_000L) {
        // The action clears preparing before the worker finally may schedule the next pending event.
        yield()
        while (coordinator.state.value.preparing) yield()
        coordinator.flush()
    }

    private suspend fun assertStopped(ending: SleepTimerTrackBinding, completedCycles: Int, reason: SleepTimerStopReason) {
        awaitQueueIdle()
        awaitTimerSaved()
        val saved = requireNotNull(store.snapshot())
        assertEquals(ending.queueGenerationId, saved.generationId)
        assertEquals(ending.queueEntryId, saved.currentEntryId)
        assertEquals(DURATION_MS, saved.positionMs)
        assertEquals(completedCycles, saved.checkpoint.completedCycles)
        assertFalse(backend.playWhenReady)
        assertFalse(backend.isPlaying)
        assertEquals(reason, timer.state.value.timer.lastStop?.reason)
        assertNull(timer.state.value.timer.active)
        assertEquals(timer.state.value.timer, SleepTimerStoreCodec.decode(timerData.data.first()).state)
        assertTrue(failures.isEmpty())
        assertTrue(focusIssues.isEmpty())
        // Another Room instance reads the actual file without involving a player or invented receipt.
        val reopened = openDatabase(databaseFile)
        try { assertEquals(saved, RoomQueueStore(reopened).snapshot()) }
        finally { reopened.close() }
    }

    private fun phaseGate(nonCooperative: Boolean = false) = PhaseGate(nonCooperative).also { phaseGates.add(it) }
    private fun checkpointGate(matches: (QueueCheckpoint) -> Boolean) = CheckpointGate(matches).also {
        checkpointGates.add(it)
        gatedStore.nextGate = it
    }
    // Freeze tests inspect the reservation before releasing the Room receipt. Main.immediate
    // can queue a nested DEFAULT start while that receipt resumes the test.
    private fun <T> asyncCommand(
        start: CoroutineStart = CoroutineStart.DEFAULT,
        block: suspend () -> T,
    ): Deferred<T> = scope.async(start = start) { block() }.also { testCommands.add(it) }
    private fun edit(operation: String, saved: QueueSnapshot, mutation: QueueMutation) =
        QueueMutationRequest(operation, saved.generationId, saved.queueRevision, saved.currentEntryId, mutation)

    /** Room uses its normal executors; the paused main Looper is pumped with a bounded wall-clock wait. */
    private fun <T> onMain(block: suspend () -> T): T {
        val task = scope.async(Dispatchers.Main.immediate) { block() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30L)
        while (!task.isCompleted && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!task.isCompleted) Thread.sleep(1L)
        }
        if (!task.isCompleted) {
            task.cancel()
            throw AssertionError("Main/Room/DataStore operation exceeded the test deadline")
        }
        return runBlocking { task.await() }
    }

    private fun createBackend(): Pair<CheckedPlayer, ControlledOperations> {
        val raw = ControlledPlayer()
        val checked = CheckedPlayer(raw)
        backends.add(checked)
        return checked to ControlledOperations(checked, raw)
    }

    private class FixedPreferences : UserPreferencesRepository {
        override val state = MutableStateFlow<PreferencesState>(PreferencesState.Ready(UserPreferences()))
        override suspend fun save(expectedRevision: Long, draft: UserPreferencesDraft): PreferencesWriteResult = error("Unexpected preference write")
        override suspend fun reset(expectedRevision: Long, confirmation: ResetConfirmation): PreferencesWriteResult = error("Unexpected preference reset")
        override fun retryRead() = Unit
    }

    private class PhaseGate(private val nonCooperative: Boolean) {
        val entered = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        private val released = CompletableDeferred<Unit>()
        suspend fun hold() {
            entered.complete(Unit)
            try {
                if (nonCooperative) withContext(NonCancellable) { released.await() } else released.await()
            } finally { exited.complete(Unit) }
        }
        fun release() { released.complete(Unit) }
    }

    private class CheckpointGate(val matches: (QueueCheckpoint) -> Boolean) {
        val committed = CompletableDeferred<QueueSnapshot>()
        val exited = CompletableDeferred<Unit>()
        private val released = CompletableDeferred<Unit>()
        suspend fun afterCommit(snapshot: QueueSnapshot) {
            committed.complete(snapshot)
            try { released.await() } finally { exited.complete(Unit) }
        }
        fun release() { released.complete(Unit) }
    }

    /** The production coordinator supplies NonCancellable; this wrapper returns only real Room receipts. */
    private class CheckpointStore(private val actual: RoomQueueStore) : QueueStore by actual {
        var nextGate: CheckpointGate? = null
        var conflictNextCheckpoint: ((QueueCheckpoint) -> Boolean)? = null
        var competingReceipt: QueueSnapshot? = null
            private set
        var rejectedCheckpoint: QueueCheckpoint? = null
            private set
        val receipts = mutableListOf<QueueSnapshot>()
        override suspend fun saveCheckpoint(generationId: String, expectedCheckpointRevision: Long,
            checkpoint: QueueCheckpoint): QueueSnapshot {
            val gate = nextGate?.takeIf { it.matches(checkpoint) }
            if (gate != null) nextGate = null
            val competingWrite = conflictNextCheckpoint?.invoke(checkpoint) == true
            if (competingWrite) {
                conflictNextCheckpoint = null
                val live = requireNotNull(actual.snapshot())
                // A separate real write advances Room's revision. The attempted production
                // checkpoint then uses its captured stale revision and must fail the actual CAS.
                competingReceipt = actual.saveCheckpoint(live.generationId, live.checkpointRevision, live.checkpoint)
            }
            val written = try {
                actual.saveCheckpoint(generationId, expectedCheckpointRevision, checkpoint)
            } catch (error: QueueStoreException) {
                if (competingWrite) rejectedCheckpoint = checkpoint
                throw error
            }
            receipts.add(written)
            gate?.afterCommit(written)
            return written
        }
    }

    private data class ScheduledTask(val token: SleepTimerToken, val atElapsedMs: Long, val action: () -> Unit)
    private class ControlledScheduler : SleepTimerScheduler {
        var deadline: ScheduledTask? = null
            private set
        var summary: ScheduledTask? = null
            private set
        override fun deadline(token: SleepTimerToken, atElapsedMs: Long, task: () -> Unit) {
            deadline = ScheduledTask(token, atElapsedMs, task)
        }
        override fun summary(token: SleepTimerToken, atElapsedMs: Long, task: () -> Unit) {
            summary = ScheduledTask(token, atElapsedMs, task)
        }
        override fun clearDeadline() { deadline = null }
        override fun clearSummary() { summary = null }
        override fun close() {
            deadline = null
            summary = null
        }
        fun fireDeadline() { requireNotNull(deadline).action() }
    }

    private class ControlledNotification : SleepTimerNotification {
        override fun isAllowed() = true
        override fun show(summary: SleepTimerSummary?) = true
    }

    private class ControlledOperations(override val player: CheckedPlayer, private val raw: ControlledPlayer) : PlaybackBackendOperations {
        var prepareGate: PhaseGate? = null
        var seekGate: PhaseGate? = null
        var prepares = 0
            private set
        override fun setPauseAtBoundary(pause: Boolean) { assertTrue(pause) }
        override suspend fun prepareAndAwait(expectedMediaId: String) {
            prepares += 1
            prepareGate?.hold()
            currentCoroutineContext().ensureActive()
            assertEquals(expectedMediaId, player.currentMediaItem?.mediaId)
            player.prepare()
            raw.prepared()
        }
        override suspend fun seekAndAwait(expectedMediaId: String, positionMs: Long) {
            assertEquals(expectedMediaId, player.currentMediaItem?.mediaId)
            raw.deferSeek = true
            try { player.seekTo(positionMs) } finally { raw.deferSeek = false }
            seekGate?.hold()
            currentCoroutineContext().ensureActive()
            assertEquals(expectedMediaId, player.currentMediaItem?.mediaId)
            raw.acknowledgeSeek(positionMs.coerceIn(0L, DURATION_MS))
        }
    }

    /** END callbacks are genuine SimpleBasePlayer events; time changes only on explicit acknowledgement. */
    private class ControlledPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        private data class Item(val uid: String, val media: MediaItem)
        private val items = mutableListOf<Item>()
        private var nextUid = 0L
        private var index = C.INDEX_UNSET
        private var position = 0L
        private var ready = false
        private var ended = false
        private var buffering = false
        private var requestedPlay = false
        private var reason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
        private var closed = false
        var deferSeek = false
        var peakWindow = 0
            private set

        override fun getState(): State {
            val playlist = items.map { MediaItemData.Builder(it.uid).setMediaItem(it.media)
                .setDurationUs(DURATION_MS * 1000L).setIsSeekable(true).build() }
            val state = when {
                closed || items.isEmpty() -> Player.STATE_IDLE
                ended -> Player.STATE_ENDED
                buffering -> Player.STATE_BUFFERING
                ready -> Player.STATE_READY
                else -> Player.STATE_IDLE
            }
            return State.Builder().setAvailableCommands(if (closed) Player.Commands.EMPTY else COMMANDS)
                .setPlaylist(playlist).setCurrentMediaItemIndex(index).setPlaybackState(state)
                .setPlayWhenReady(requestedPlay, reason)
                .setContentPositionMs(PositionSupplier { position })
                .setContentBufferedPositionMs(PositionSupplier { position })
                .setTotalBufferedDurationMs(PositionSupplier.ZERO).build()
        }

        override fun getPlaceholderState(suggestedPlaceholderState: State): State = getState()
        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            requestedPlay = playWhenReady
            reason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
            return immediate()
        }
        override fun handlePrepare(): ListenableFuture<*> {
            ready = true
            ended = false
            return immediate()
        }
        override fun handleStop(): ListenableFuture<*> {
            requestedPlay = false
            ready = false
            return immediate()
        }
        override fun handleRelease(): ListenableFuture<*> {
            closed = true
            requestedPlay = false
            ready = false
            return immediate()
        }
        override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
            items.clear()
            items.addAll(mediaItems.map { Item("source-${nextUid++}", it) })
            index = if (items.isEmpty()) C.INDEX_UNSET else if (startIndex == C.INDEX_UNSET) 0 else startIndex
            position = if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs
            ready = false
            ended = false
            buffering = false
            recordWindow()
            return immediate()
        }
        override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
            val current = items.getOrNull(this.index)?.uid
            items.addAll(index, mediaItems.map { Item("source-${nextUid++}", it) })
            this.index = current?.let { key -> items.indexOfFirst { it.uid == key } } ?: if (items.isEmpty()) C.INDEX_UNSET else 0
            recordWindow()
            return immediate()
        }
        override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
            val current = items.getOrNull(index)?.uid
            val moved = items.subList(fromIndex, toIndex).toList()
            items.subList(fromIndex, toIndex).clear()
            items.addAll(newIndex, moved)
            index = current?.let { key -> items.indexOfFirst { it.uid == key } } ?: C.INDEX_UNSET
            return immediate()
        }
        override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
            val current = items.getOrNull(index)?.uid
            items.subList(fromIndex, toIndex).clear()
            index = when {
                items.isEmpty() -> C.INDEX_UNSET
                items.any { it.uid == current } -> items.indexOfFirst { it.uid == current }
                else -> fromIndex.coerceAtMost(items.lastIndex)
            }
            if (current == null || items.none { it.uid == current }) {
                ready = false
                ended = false
                position = 0L
            }
            return immediate()
        }
        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            if (mediaItemIndex !in items.indices) return immediate()
            val target = if (positionMs == C.TIME_UNSET) 0L else positionMs.coerceIn(0L, DURATION_MS)
            if (mediaItemIndex != index) {
                index = mediaItemIndex
                ready = false
                ended = false
            }
            if (deferSeek) buffering = true else {
                position = target
                ended = target >= DURATION_MS
                buffering = false
            }
            reason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
            return immediate()
        }
        fun prepared() {
            ready = true
            ended = false
            buffering = false
            invalidateState()
        }
        fun acknowledgeSeek(positionMs: Long) {
            position = positionMs
            buffering = false
            ready = true
            ended = position >= DURATION_MS
            reason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
            invalidateState()
        }
        fun completeNaturally() {
            position = DURATION_MS
            ended = true
            buffering = false
            requestedPlay = false
            reason = Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
            invalidateState()
        }
        private fun recordWindow() {
            peakWindow = maxOf(peakWindow, items.size)
            assertTrue(items.size <= 3)
        }
        private fun immediate(): ListenableFuture<Unit> = Futures.immediateFuture(Unit)

        companion object {
            private val COMMANDS = Player.Commands.Builder().addAll(
                Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP, Player.COMMAND_RELEASE,
                Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA, Player.COMMAND_GET_TRACKS,
                Player.COMMAND_GET_AUDIO_ATTRIBUTES, Player.COMMAND_GET_VOLUME,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
            ).build()
        }
    }

    private class CheckedPlayer(val fake: ControlledPlayer) : Player by fake {
        var released = false
            private set
        var lateCalls = 0
            private set
        var pauseCalls = 0
            private set
        var playCalls = 0
            private set
        var seekCalls = 0
            private set
        var afterNextPause: (() -> Unit)? = null
        var failNextCurrentMediaItemRead = false
        var failedCurrentMediaItemReads = 0
            private set
        val listeners = mutableListOf<Player.Listener>()
        val peakWindow get() = fake.peakWindow
        // Deliver duplicates even when SimpleBasePlayer suppresses an unchanged END state.
        fun repeatNaturalCallbacks() {
            listeners.toList().forEach { listener ->
                listener.onPlaybackStateChanged(Player.STATE_ENDED)
                listener.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
            }
        }
        private fun <T> live(block: () -> T): T {
            if (released) {
                lateCalls += 1
                throw AssertionError("Released backend API used")
            }
            return block()
        }
        override fun getApplicationLooper(): Looper = live { fake.applicationLooper }
        override fun getAvailableCommands(): Player.Commands = live { fake.availableCommands }
        override fun isCommandAvailable(command: Int): Boolean = live { fake.isCommandAvailable(command) }
        override fun getRepeatMode(): Int = live { fake.repeatMode }
        override fun getShuffleModeEnabled(): Boolean = live { fake.shuffleModeEnabled }
        override fun getPlaybackState(): Int = live { fake.playbackState }
        override fun getPlayWhenReady(): Boolean = live { fake.playWhenReady }
        override fun getPlayerError(): PlaybackException? = live { fake.playerError }
        override fun getCurrentMediaItem(): MediaItem? = live {
            if (failNextCurrentMediaItemRead) {
                failNextCurrentMediaItemRead = false
                failedCurrentMediaItemReads += 1
                throw IllegalStateException("Controlled backend item read failed")
            }
            fake.currentMediaItem
        }
        override fun getCurrentMediaItemIndex(): Int = live { fake.currentMediaItemIndex }
        override fun getMediaItemCount(): Int = live { fake.mediaItemCount }
        override fun getMediaItemAt(index: Int): MediaItem = live { fake.getMediaItemAt(index) }
        override fun getCurrentPosition(): Long = live { fake.currentPosition }
        override fun getDuration(): Long = live { fake.duration }
        override fun getContentPosition(): Long = live { fake.contentPosition }
        override fun getContentBufferedPosition(): Long = live { fake.contentBufferedPosition }
        override fun getTotalBufferedDuration(): Long = live { fake.totalBufferedDuration }
        override fun getCurrentTimeline(): Timeline = live { fake.currentTimeline }
        override fun getCurrentTracks(): Tracks = live { fake.currentTracks }
        override fun getMediaMetadata(): MediaMetadata = live { fake.mediaMetadata }
        override fun getPlaylistMetadata(): MediaMetadata = live { fake.playlistMetadata }
        override fun getPlaybackParameters(): PlaybackParameters = live { fake.playbackParameters }
        override fun getAudioAttributes(): AudioAttributes = live { fake.audioAttributes }
        override fun getVolume(): Float = live { fake.volume }
        override fun getPlaybackSuppressionReason(): Int = live { fake.playbackSuppressionReason }
        override fun isLoading(): Boolean = live { fake.isLoading }
        override fun isPlaying(): Boolean = live { fake.isPlaying }
        override fun getSeekBackIncrement(): Long = live { fake.seekBackIncrement }
        override fun getSeekForwardIncrement(): Long = live { fake.seekForwardIncrement }
        override fun play() = live {
            playCalls += 1
            fake.play()
        }
        override fun pause() = live {
            pauseCalls += 1
            val callback = afterNextPause
            afterNextPause = null
            fake.pause()
            callback?.invoke()
            Unit
        }
        override fun stop() = live { fake.stop() }
        override fun prepare() = live { fake.prepare() }
        override fun seekTo(positionMs: Long) = live {
            seekCalls += 1
            fake.seekTo(positionMs)
        }
        override fun seekTo(mediaItemIndex: Int, positionMs: Long) = live {
            seekCalls += 1
            fake.seekTo(mediaItemIndex, positionMs)
        }
        override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) = live { fake.setMediaItems(mediaItems, startIndex, startPositionMs) }
        override fun clearMediaItems() = live { fake.clearMediaItems() }
        override fun addMediaItem(index: Int, mediaItem: MediaItem) = live { fake.addMediaItem(index, mediaItem) }
        override fun moveMediaItem(currentIndex: Int, newIndex: Int) = live { fake.moveMediaItem(currentIndex, newIndex) }
        override fun removeMediaItem(index: Int) = live { fake.removeMediaItem(index) }
        override fun setRepeatMode(repeatMode: Int) = live { fake.repeatMode = repeatMode }
        override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) = live { fake.shuffleModeEnabled = shuffleModeEnabled }
        override fun addListener(listener: Player.Listener) = live {
            listeners.add(listener)
            fake.addListener(listener)
        }
        override fun removeListener(listener: Player.Listener) = live {
            listeners.remove(listener)
            fake.removeListener(listener)
        }
        override fun release() = live {
            fake.release()
            released = true
        }
    }

    private companion object {
        const val DURATION_MS = 10_000L
        fun entry(order: Long) = QueueEntry("entry-$order", TrackKey(TrackKey.LOCAL_PROVIDER, "track-$order"), order, order,
            durationMs = DURATION_MS, displayName = "Track $order",
            location = LocalLocationReference("location-$order", "content://timer-fixture/$order"))
        fun openDatabase(file: File): MeloXDatabase = Room.databaseBuilder(
            RuntimeEnvironment.getApplication() as Context, MeloXDatabase::class.java, file.absolutePath).build()
    }
}
