package io.github.onlinemo.meloxwatch.queue

import android.content.Context
import android.os.Looper
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
import io.github.onlinemo.meloxwatch.data.library.LibraryLocation
import io.github.onlinemo.meloxwatch.data.library.LibraryViewToken
import io.github.onlinemo.meloxwatch.data.library.LocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.library.RoomLocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.local.LocalGrantObservation
import io.github.onlinemo.meloxwatch.data.local.LocalNodeKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesState
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesWriteResult
import io.github.onlinemo.meloxwatch.data.preferences.ResetConfirmation
import io.github.onlinemo.meloxwatch.data.preferences.UserPreferencesRepository
import io.github.onlinemo.meloxwatch.data.queue.QueueCommit
import io.github.onlinemo.meloxwatch.data.queue.QueueInputPage
import io.github.onlinemo.meloxwatch.data.queue.QueuePageSource
import io.github.onlinemo.meloxwatch.data.queue.QueueReplacementRequest
import io.github.onlinemo.meloxwatch.data.queue.QueueStoreException
import io.github.onlinemo.meloxwatch.data.queue.QueueStoreIssue
import io.github.onlinemo.meloxwatch.data.queue.RoomQueueStore
import io.github.onlinemo.meloxwatch.data.scan.ScanCounters
import io.github.onlinemo.meloxwatch.data.scan.ScanCoverage
import io.github.onlinemo.meloxwatch.data.scan.ScanMode
import io.github.onlinemo.meloxwatch.model.FrozenQueueView
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import io.github.onlinemo.meloxwatch.model.QueueCheckpoint
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueMode
import io.github.onlinemo.meloxwatch.model.QueueMutation
import io.github.onlinemo.meloxwatch.model.QueueMutationRequest
import io.github.onlinemo.meloxwatch.model.QueueRepeatMode
import io.github.onlinemo.meloxwatch.model.QueueSeekRequest
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.model.UserPreferences
import io.github.onlinemo.meloxwatch.model.UserPreferencesDraft
import io.github.onlinemo.meloxwatch.playback.LibraryQueueAction
import io.github.onlinemo.meloxwatch.playback.LibraryQueueCommand
import io.github.onlinemo.meloxwatch.playback.LibraryQueueProducer
import io.github.onlinemo.meloxwatch.playback.PlaybackBackendOperations
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackCoordinator
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackException
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackFailure
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** Real Room transactions and a controlled media clock; no decoder, sound, performance or device claims. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class QueueCoordinatorIntegrationTest {
    private lateinit var database: MeloXDatabase
    private lateinit var store: RoomQueueStore
    private lateinit var scope: CoroutineScope
    private lateinit var coordinator: QueuePlaybackCoordinator
    private lateinit var backend: CheckedPlayer
    private lateinit var operations: ControlledOperations
    private val backends = mutableListOf<CheckedPlayer>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    private val testCommands = mutableListOf<Job>()
    private val reads = mutableListOf<String>()
    private val rounds = mutableListOf<String>()
    private var unavailable = emptySet<String>()
    private var resolveHook: suspend (QueueEntry) -> QueueEntry = { it }
    private var userPauses = 0
    private var userIntentChanges = 0
    private var focusAllowed = true

    @Before
    fun open() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication() as Context, MeloXDatabase::class.java).build()
        store = RoomQueueStore(database, shuffleIndex = { it })
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val pair = createBackend()
        backend = pair.first
        operations = pair.second
        coordinator = QueuePlaybackCoordinator(backend, store, FixedPreferences(), scope,
            resolveEntry = { entry ->
                reads.add(entry.queueEntryId)
                if (entry.queueEntryId in unavailable) throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.FILE_MISSING, entry.queueEntryId))
                resolveHook(entry)
            },
            onRoundChanged = { rounds.add(it.queueEntryId) },
            operations = operations,
            requestPlay = { if (focusAllowed) { backend.play(); backend.playWhenReady } else false },
            onUserPause = { userPauses += 1 },
            onUserIntentChanged = { userIntentChanges += 1 },
        )
    }

    @After
    fun close() {
        for (gate in gates) gate.complete(Unit)
        try {
            coordinator.detach()
        } finally {
            try {
                onMain {
                    for (command in testCommands) command.cancelAndJoin()
                    coordinator.close()
                }
            } finally {
                try { for (player in backends) if (!player.released) player.release() }
                finally { scope.cancel(); database.close() }
            }
        }
        for (player in backends) assertEquals("No released Player API may be read", 0, player.lateCalls)
    }

    @Test
    fun completeLibraryCommandReplaysItsReceiptAfterTheOriginalViewClosesAndTheQueueModeChanges() = onMain {
        seed()
        val library = libraryWithTracks()
        val visible = library.openView()
        val visibleTracks = library.page(visible).tracks
        val selected = visibleTracks[2]
        val before = store.snapshot()!!
        val command = LibraryQueueCommand(visible.viewId, "replace-visible", before.generationId, before.queueRevision,
            selected.trackKey, visible.revision, visible.totalCount, 2L, before.currentEntryId,
            QueueMode(true, QueueRepeatMode.SINGLE, 2), LibraryQueueAction.REPLACE_COMPLETE_LIST)
        val first = commitLibraryQueue(library, command)
        assertEquals(4L, first.snapshot.entryCount)
        assertEquals(selected.trackKey, store.window(first.snapshot.generationId, first.snapshot.currentEntryId!!).current.trackKey)
        library.closeView(visible.viewId)
        assertNull(database.libraryDao().view(visible.viewId))
        coordinator.mutate(edit("later-mode", store.snapshot()!!, QueueMutation.SetMode(QueueMode(false, QueueRepeatMode.LIST))))
        coordinator.seek(QueueSeekRequest(coordinator.seekBinding()!!, 4321L))
        coordinator.flush()
        val later = store.snapshot()!!
        val ids = store.page(later.generationId, 0L).map { it.queueEntryId }
        val counts = library.counts()
        val pauses = backend.pauseCalls
        val seeks = backend.seekCalls
        val prepares = operations.prepares
        val previousRounds = rounds.toList()
        val intents = userIntentChanges
        reads.clear()

        val replay = commitLibraryQueue(library, command)

        assertTrue(replay.wasReplay)
        assertEquals(first.appliedGenerationId, replay.appliedGenerationId)
        assertEquals(later, replay.snapshot)
        expectStoreFailure(QueueStoreIssue.INVALID_INPUT) {
            commitLibraryQueue(library, command.copy(selected = visibleTracks[0].trackKey))
        }
        assertEquals(QueuePlaybackIssue.CONFLICT, coordinator.state.value.failure?.issue)
        assertEquals(QueueStoreIssue.INVALID_INPUT, coordinator.state.value.failure?.queueStoreIssue)
        assertEquals(later, store.snapshot())
        assertEquals(ids, store.page(later.generationId, 0L).map { it.queueEntryId })
        assertEquals(counts, library.counts())
        assertTrue(reads.isEmpty())
        assertEquals(pauses, backend.pauseCalls)
        assertEquals(seeks, backend.seekCalls)
        assertEquals(prepares, operations.prepares)
        assertEquals(previousRounds, rounds)
        assertEquals(intents, userIntentChanges)
        assertEquals(4321L, backend.currentPosition)
        assertTrue(backend.playWhenReady)
    }

    @Test
    fun insertAndPlayReplaysWithoutAnotherInstanceOrLeaseAndRejectsReusedIntentIdentityWithoutIO() = onMain {
        seed()
        val library = libraryWithTracks()
        val visible = library.openView()
        val selected = library.page(visible).tracks
        val before = store.snapshot()!!
        val command = LibraryQueueCommand(visible.viewId, "insert-visible", before.generationId, before.queueRevision,
            selected[0].trackKey, visible.revision, visible.totalCount, 0L, before.currentEntryId,
            before.mode, LibraryQueueAction.INSERT_AND_PLAY)
        val first = commitLibraryQueue(library, command)
        assertEquals(6L, first.snapshot.entryCount)
        assertNotEquals(before.currentEntryId, first.snapshot.currentEntryId)
        assertEquals(selected[0].trackKey, store.window(first.snapshot.generationId, first.snapshot.currentEntryId!!).current.trackKey)
        assertTrue(backend.playWhenReady)
        library.closeView(visible.viewId)
        assertNull(database.libraryDao().view(visible.viewId))
        coordinator.mutate(edit("later-insert-mode", store.snapshot()!!, QueueMutation.SetMode(QueueMode(true, QueueRepeatMode.LIST))))
        coordinator.flush()
        val later = store.snapshot()!!
        val ids = store.page(later.generationId, 0L).map { it.queueEntryId }
        val counts = library.counts()
        val pauses = backend.pauseCalls
        val seeks = backend.seekCalls
        val prepares = operations.prepares
        val previousRounds = rounds.toList()
        val intents = userIntentChanges
        reads.clear()

        val replay = commitLibraryQueue(library, command)
        assertTrue(replay.wasReplay)
        assertEquals(first.appliedGenerationId, replay.appliedGenerationId)
        assertEquals(later, replay.snapshot)
        expectStoreFailure(QueueStoreIssue.INVALID_INPUT) {
            commitLibraryQueue(library, command.copy(selected = selected[1].trackKey, selectedOriginalOrder = 1L))
        }
        expectStoreFailure(QueueStoreIssue.INVALID_INPUT) {
            commitLibraryQueue(library, command.copy(viewId = "another-visible-view"))
        }
        assertEquals(QueuePlaybackIssue.CONFLICT, coordinator.state.value.failure?.issue)
        assertEquals(QueueStoreIssue.INVALID_INPUT, coordinator.state.value.failure?.queueStoreIssue)

        assertEquals(later, store.snapshot())
        assertEquals(6, ids.distinct().size)
        assertEquals(ids, store.page(later.generationId, 0L).map { it.queueEntryId })
        assertEquals(counts, library.counts())
        assertTrue(reads.isEmpty())
        assertEquals(pauses, backend.pauseCalls)
        assertEquals(seeks, backend.seekCalls)
        assertEquals(prepares, operations.prepares)
        assertEquals(previousRounds, rounds)
        assertEquals(intents, userIntentChanges)
        assertTrue(backend.playWhenReady)
    }

    @Test
    fun failedLibraryInsertionCanReadAFreshProducerOnRetryAndOnlyInsertOnceWhenAccepted() = onMain {
        seed()
        coordinator.sessionPlayer.play()
        coordinator.flush()
        val library = libraryWithTracks()
        val visible = library.openView()
        val selected = library.page(visible).tracks.first()
        val before = store.snapshot()!!
        val command = LibraryQueueCommand(visible.viewId, "retry-visible", before.generationId, before.queueRevision,
            selected.trackKey, visible.revision, visible.totalCount, 0L, before.currentEntryId,
            before.mode, LibraryQueueAction.INSERT_AND_PLAY)
        val pauses = backend.pauseCalls
        val seeks = backend.seekCalls
        resolveHook = { entry ->
            if (entry.trackKey == selected.trackKey) throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.FILE_MISSING, entry.queueEntryId))
            entry
        }
        expectFailure(QueuePlaybackIssue.FILE_MISSING) { commitLibraryQueue(library, command) }
        assertEquals(before, store.snapshot())
        assertEquals(before.currentEntryId, backend.currentMediaItem?.mediaId)
        assertEquals(pauses, backend.pauseCalls)
        assertEquals(seeks, backend.seekCalls)
        assertTrue(backend.playWhenReady)
        assertEquals(1, library.counts().first())

        resolveHook = { it }
        val accepted = commitLibraryQueue(library, command)
        assertFalse(accepted.wasReplay)
        assertEquals(6L, accepted.snapshot.entryCount)
        assertEquals(1, store.page(accepted.snapshot.generationId, 0L).count { it.trackKey == selected.trackKey })
        assertEquals(2, library.counts().first())
        library.closeView(visible.viewId)
        coordinator.flush()
        val after = store.snapshot()!!
        val counts = library.counts()
        reads.clear()
        val replay = commitLibraryQueue(library, command)
        assertTrue(replay.wasReplay)
        assertEquals(accepted.appliedGenerationId, replay.appliedGenerationId)
        assertEquals(after, replay.snapshot)
        assertEquals(after, store.snapshot())
        assertEquals(counts, library.counts())
        assertTrue(reads.isEmpty())
        assertEquals(6L, replay.snapshot.entryCount)
        assertTrue(backend.playWhenReady)
    }

    @Test
    fun insertingDuplicateTracksKeepsCurrentAudioPositionAndRoundAndReplayDoesNoIO() = onMain {
        seed()
        coordinator.sessionPlayer.play()
        val old = store.snapshot()!!
        val pauseCalls = backend.pauseCalls
        val seekCalls = backend.seekCalls
        val oldRounds = rounds.toList()
        val oldIntents = userIntentChanges
        val view = FrozenQueueView("selected-two", 1L, 2L)
        val request = edit("insert", old, QueueMutation.InsertNext(view, old.currentEntryId))
        var producerReads = 0
        val commit = coordinator.mutate(request, QueuePageSource { offset, limit ->
            producerReads += 1
            assertEquals(0L, offset)
            assertEquals(2, limit)
            QueueInputPage(view, offset, List(limit) { ordinal -> entry(ordinal.toLong()).copy(
                queueEntryId = "preview-$ordinal", trackKey = entry(1L).trackKey,
            ) })
        })
        assertEquals(7L, commit.snapshot.entryCount)
        assertEquals(old.currentEntryId, commit.snapshot.currentEntryId)
        assertEquals(1234L, backend.currentPosition)
        assertTrue(backend.playWhenReady)
        assertEquals(pauseCalls, backend.pauseCalls)
        assertEquals(seekCalls, backend.seekCalls)
        assertEquals(oldRounds, rounds)
        assertEquals(oldIntents, userIntentChanges)
        assertEquals(1, producerReads)
        val all = store.page(commit.snapshot.generationId, 0L)
        assertEquals(7, all.size)
        assertEquals(7, all.map { it.queueEntryId }.distinct().size)
        assertTrue(all.none { it.queueEntryId.startsWith("preview-") })
        reads.clear()
        val replay = coordinator.mutate(request, QueuePageSource { _, _ -> throw AssertionError("Replay must not read a producer") }, explicitPlay = true)
        assertTrue(replay.wasReplay)
        assertTrue(reads.isEmpty())
        assertEquals(pauseCalls, backend.pauseCalls)
        assertEquals(seekCalls, backend.seekCalls)
        assertTrue(backend.peakWindow <= 3)
    }

    @Test
    fun moveAndModeChangesPreserveCurrentWithoutPauseSeekOrNewRound() = onMain {
        seed()
        coordinator.sessionPlayer.play()
        val pauses = backend.pauseCalls
        val seeks = backend.seekCalls
        val initialRounds = rounds.toList()
        val before = store.snapshot()!!
        val moved = coordinator.mutate(edit("move", before, QueueMutation.Move("entry-1", 4L))).snapshot
        assertEquals("entry-1", moved.currentEntryId)
        assertEquals(4L, moved.currentPlaybackOrder)
        val shuffled = coordinator.mutate(edit("shuffle", moved, QueueMutation.SetMode(QueueMode(true, QueueRepeatMode.SINGLE, 2)))).snapshot
        assertEquals(0L, shuffled.currentPlaybackOrder)
        assertEquals(1234L, shuffled.positionMs)
        assertEquals(pauses, backend.pauseCalls)
        assertEquals(seeks, backend.seekCalls)
        assertEquals(initialRounds, rounds)
        assertTrue(backend.playWhenReady)
        assertEquals(Player.REPEAT_MODE_ONE, coordinator.sessionPlayer.repeatMode)
        assertTrue(coordinator.sessionPlayer.shuffleModeEnabled)
    }

    @Test
    fun currentRemovalUsesTheFourthBoundedCandidateAndAlwaysPauses() = onMain {
        seed(count = 7)
        coordinator.sessionPlayer.play()
        unavailable = setOf("entry-2", "entry-3", "entry-4")
        reads.clear()
        val commit = coordinator.mutate(edit("remove-current", store.snapshot()!!, QueueMutation.Remove("entry-1", confirmedCurrent = true)), explicitPlay = true)
        assertEquals("entry-5", commit.snapshot.currentEntryId)
        assertEquals(listOf("entry-2", "entry-3", "entry-4", "entry-5"), reads.take(4))
        assertFalse(backend.playWhenReady)
        assertEquals(0L, backend.currentPosition)
        assertEquals(6L, commit.snapshot.entryCount)
        assertTrue(store.page(commit.snapshot.generationId, 0L).none { it.queueEntryId == "entry-1" })
        assertNull(coordinator.state.value.failure)
    }

    @Test
    fun allFailedRemovalCandidatesStillCommitManagementWithoutScanningTheRemainder() = onMain {
        seed(count = 8)
        coordinator.sessionPlayer.play()
        unavailable = setOf("entry-2", "entry-3", "entry-4", "entry-5")
        reads.clear()
        val commit = coordinator.mutate(edit("remove-unavailable", store.snapshot()!!, QueueMutation.Remove("entry-1", true)))
        assertEquals(7L, commit.snapshot.entryCount)
        assertEquals("entry-2", commit.snapshot.currentEntryId)
        assertEquals(listOf("entry-2", "entry-3", "entry-4", "entry-5"), reads)
        assertEquals(0, backend.mediaItemCount)
        assertFalse(backend.playWhenReady)
        assertEquals(QueuePlaybackIssue.FILE_MISSING, coordinator.state.value.failure?.issue)
    }

    @Test
    fun removingTheLastInstanceCommitsAnEmptyStoppedQueue() = onMain {
        seed(count = 1, current = 0L)
        coordinator.sessionPlayer.play()
        val commit = coordinator.mutate(edit("empty", store.snapshot()!!, QueueMutation.Remove("entry-0", true)))
        assertEquals(0L, commit.snapshot.entryCount)
        assertNull(commit.snapshot.currentEntryId)
        assertEquals(0, backend.mediaItemCount)
        assertFalse(backend.playWhenReady)
    }

    @Test
    fun seekPreviewIsReadOnlyAndASecondPreviewInvalidatesTheFirst() = onMain {
        seed()
        val before = store.snapshot()!!
        val seeks = backend.seekCalls
        val first = coordinator.seekBinding()!!
        val second = coordinator.seekBinding()!!
        assertNotEquals(first.previewId, second.previewId)
        assertEquals(before, store.snapshot())
        assertEquals(seeks, backend.seekCalls)
        expectFailure(QueuePlaybackIssue.CONFLICT) { coordinator.seek(QueueSeekRequest(first, 5000L)) }
        assertEquals(before, store.snapshot())
        assertEquals(seeks, backend.seekCalls)
    }

    @Test
    fun seekCommitsOnlyAcknowledgedActualPositionPreservesCyclesAndReplaysOnce() = onMain {
        seedWithCompletedCycle()
        coordinator.sessionPlayer.play()
        val discontinuities = mutableListOf<Triple<Long, Long, Int>>()
        coordinator.sessionPlayer.addListener(object : Player.Listener {
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                discontinuities.add(Triple(oldPosition.contentPositionMs, newPosition.contentPositionMs, reason))
            }
        })
        val binding = coordinator.seekBinding()!!
        val seekOwner = coordinator.seekState.value.ownerId
        assertEquals(binding.previewId, coordinator.seekState.value.previewId)
        val gate = testGate()
        operations.seekGate = gate
        operations.seekEntered = CompletableDeferred()
        operations.seekAdjustmentMs = -100L
        val request = QueueSeekRequest(binding, 9000L)
        val before = store.snapshot()!!
        val seek = asyncCommand { coordinator.seek(request) }
        operations.seekEntered.await()
        assertEquals(1234L, store.snapshot()!!.positionMs)
        assertEquals(1234L, backend.currentPosition)
        assertTrue(coordinator.state.value.preparing)
        assertEquals(seekOwner, coordinator.seekState.value.ownerId)
        assertEquals(binding.previewId, coordinator.seekState.value.previewId)
        assertEquals(1234L, coordinator.sessionPlayer.currentPosition)
        assertTrue(discontinuities.isEmpty())
        gate.complete(Unit)
        val saved = seek.await()
        assertEquals(8900L, saved.positionMs)
        assertEquals(seekOwner, coordinator.seekState.value.ownerId)
        assertNull(coordinator.seekState.value.previewId)
        assertEquals(1, saved.checkpoint.completedCycles)
        assertTrue(saved.checkpointRevision > before.checkpointRevision)
        assertTrue(backend.playWhenReady)
        assertEquals(listOf(Triple(1234L, 8900L, Player.DISCONTINUITY_REASON_SEEK)), discontinuities)
        val calls = backend.seekCalls
        coordinator.seek(request)
        assertEquals(calls, backend.seekCalls)
        assertEquals(8900L, backend.currentPosition)
        assertEquals(1, discontinuities.size)
        // Even a short seek below SimpleBasePlayer's inference threshold is one real event.
        coordinator.seek(QueueSeekRequest(coordinator.seekBinding()!!, 9150L))
        assertEquals(9050L, backend.currentPosition)
        assertEquals(Triple(8900L, 9050L, Player.DISCONTINUITY_REASON_SEEK), discontinuities.last())
        assertEquals(2, discontinuities.size)
    }

    @Test
    fun pauseCancelsAnUnacknowledgedSeekAndCannotPublishOrPlayItsLateCompletion() = onMain {
        seedWithCompletedCycle()
        coordinator.sessionPlayer.play()
        val request = QueueSeekRequest(coordinator.seekBinding()!!, 9000L, explicitPlay = true)
        val gate = testGate()
        operations.seekGate = gate
        operations.seekEntered = CompletableDeferred()
        val command = asyncCommand { coordinator.seek(request) }
        operations.seekEntered.await()
        coordinator.sessionPlayer.pause()
        command.join()
        gate.complete(Unit)
        awaitIdleCommand()
        assertTrue(command.isCancelled)
        assertEquals(1234L, backend.currentPosition)
        assertEquals(1234L, store.snapshot()!!.positionMs)
        assertEquals(1, store.snapshot()!!.checkpoint.completedCycles)
        assertFalse(backend.playWhenReady)
    }

    @Test
    fun pauseCancelsPrepareAfterAcceptedSelectionAndNeverPlaysWhenTheGateCompletes() = onMain {
        seed()
        coordinator.sessionPlayer.play()
        val before = store.snapshot()!!
        val gate = testGate()
        operations.prepareGate = gate
        operations.prepareEntered = CompletableDeferred()
        val command = asyncCommand { coordinator.select(before.generationId, before.queueRevision, "entry-2", explicitPlay = true) }
        operations.prepareEntered.await()
        assertEquals("entry-2", store.snapshot()!!.currentEntryId)
        assertFalse(backend.playWhenReady)
        coordinator.sessionPlayer.pause()
        command.join()
        gate.complete(Unit)
        awaitIdleCommand()
        assertTrue(command.isCancelled)
        assertEquals("entry-2", store.snapshot()!!.currentEntryId)
        assertEquals(0L, backend.currentPosition)
        assertFalse(backend.playWhenReady)
    }

    @Test
    fun manualSeekToEndDoesNotCountNaturalCompletionOrAdvanceTheInstance() = onMain {
        seedWithCompletedCycle()
        coordinator.sessionPlayer.play()
        val before = store.snapshot()!!
        val saved = coordinator.seek(QueueSeekRequest(coordinator.seekBinding()!!, Long.MAX_VALUE))
        backend.fake.completeNaturally()
        assertEquals(before.currentEntryId, saved.currentEntryId)
        assertEquals(10_000L, saved.positionMs)
        assertEquals(1, store.snapshot()!!.checkpoint.completedCycles)
        assertEquals(before.currentEntryId, store.snapshot()!!.currentEntryId)
        assertFalse(backend.playWhenReady)
    }

    @Test
    fun durationChangesRejectStaleSeekWithoutAudioMutationAndDisableItsOwner() = onMain {
        seed()
        val durationBinding = coordinator.seekBinding()!!
        val oldOwner = coordinator.seekState.value.ownerId
        backend.fake.durationMs = 12_000L
        backend.fake.refresh()
        assertNotEquals(oldOwner, coordinator.seekSessionState().ownerId)
        assertNull(coordinator.seekState.value.previewId)
        val calls = backend.seekCalls
        expectFailure(QueuePlaybackIssue.SOURCE_CHANGED) { coordinator.seek(QueueSeekRequest(durationBinding, 8000L)) }
        assertEquals(calls, backend.seekCalls)
        assertNull(coordinator.seekState.value.ownerId)
    }

    @Test
    fun resolvedContentChangesRejectStaleSeekWithoutAudioMutationAndDisableItsOwner() = onMain {
        seed()
        val contentBinding = coordinator.seekBinding()!!
        val calls = backend.seekCalls
        resolveHook = { it.copy(location = it.location?.copy(contentRevision = 1L)) }
        expectFailure(QueuePlaybackIssue.SOURCE_CHANGED) { coordinator.seek(QueueSeekRequest(contentBinding, 8000L)) }
        assertEquals(calls, backend.seekCalls)
        assertNull(coordinator.seekState.value.ownerId)
    }

    @Test
    fun queueChangesRejectStaleSeekWithoutAudioMutationAndReplaceTheOwner() = onMain {
        seed()
        val queueBinding = coordinator.seekBinding()!!
        val oldOwner = coordinator.seekState.value.ownerId
        val calls = backend.seekCalls
        coordinator.mutate(edit("new-generation", store.snapshot()!!, QueueMutation.Move("entry-0", 4L)))
        assertNotEquals(oldOwner, coordinator.seekState.value.ownerId)
        assertNull(coordinator.seekState.value.previewId)
        expectFailure(QueuePlaybackIssue.CONFLICT) { coordinator.seek(QueueSeekRequest(queueBinding, 8000L)) }
        assertEquals(calls, backend.seekCalls)
    }

    @Test
    fun seekOwnerAndPreviewSurviveProgressAndRealCheckpointWritesWithoutProviderReads() = onMain {
        seed()
        val binding = coordinator.seekBinding()!!
        val owner = coordinator.seekState.value.ownerId
        assertTrue(owner != null)
        assertTrue(binding.playbackCycle > 0L)
        val readsBefore = reads.toList()
        val checkpoint = store.snapshot()!!
        val calls = backend.seekCalls
        repeat(100) {
            assertEquals(owner, coordinator.seekSessionState().ownerId)
            assertEquals(binding.previewId, coordinator.seekState.value.previewId)
        }
        assertEquals(checkpoint.checkpointRevision, store.snapshot()!!.checkpointRevision)
        backend.fake.advancePosition(2345L)
        coordinator.flush()
        assertEquals(2345L, store.snapshot()!!.positionMs)
        assertTrue(store.snapshot()!!.checkpointRevision > checkpoint.checkpointRevision)
        assertEquals(owner, coordinator.seekState.value.ownerId)
        assertEquals(binding.previewId, coordinator.seekState.value.previewId)
        assertEquals(readsBefore, reads)
        assertEquals(calls, backend.seekCalls)
    }

    @Test
    fun replacingAPreviewPublishesItsNewIdWhileRetainingTheSameOwner() = onMain {
        seed()
        val first = coordinator.seekBinding()!!
        val owner = coordinator.seekState.value.ownerId
        val second = coordinator.seekBinding()!!
        assertNotEquals(first.previewId, second.previewId)
        assertEquals(first.playbackCycle, second.playbackCycle)
        assertEquals(owner, coordinator.seekState.value.ownerId)
        assertEquals(second.previewId, coordinator.seekState.value.previewId)
        val calls = backend.seekCalls
        expectFailure(QueuePlaybackIssue.CONFLICT) { coordinator.seek(QueueSeekRequest(first, 5000L)) }
        assertEquals(calls, backend.seekCalls)
    }

    @Test
    fun seekKeepsItsOriginalOwnerThroughItsOwnResolverPreparationAndBuffering() = onMain {
        seed()
        coordinator.sessionPlayer.play()
        val binding = coordinator.seekBinding()!!
        val owner = coordinator.seekState.value.ownerId
        val sourceEntered = CompletableDeferred<Unit>()
        val sourceGate = testGate()
        val seekGate = testGate()
        resolveHook = {
            sourceEntered.complete(Unit)
            sourceGate.await()
            it
        }
        operations.seekGate = seekGate
        operations.seekEntered = CompletableDeferred()
        val command = asyncCommand { coordinator.seek(QueueSeekRequest(binding, 5000L)) }
        sourceEntered.await()
        assertTrue(coordinator.state.value.preparing)
        assertEquals(owner, coordinator.seekState.value.ownerId)
        assertEquals(binding.previewId, coordinator.seekState.value.previewId)
        coordinator.flush()
        assertEquals(owner, coordinator.seekSessionState().ownerId)
        sourceGate.complete(Unit)
        operations.seekEntered.await()
        assertEquals(Player.STATE_BUFFERING, backend.playbackState)
        assertEquals(owner, coordinator.seekState.value.ownerId)
        assertEquals(binding.previewId, coordinator.seekState.value.previewId)
        backend.fake.durationMs = C.TIME_UNSET
        backend.fake.refresh()
        backend.seekCommandAvailable = false
        assertEquals(owner, coordinator.seekSessionState().ownerId)
        assertEquals(binding.previewId, coordinator.seekState.value.previewId)
        backend.fake.durationMs = binding.durationMs
        backend.seekCommandAvailable = true
        backend.fake.refresh()
        seekGate.complete(Unit)
        assertEquals(5000L, command.await().positionMs)
        assertEquals(owner, coordinator.seekState.value.ownerId)
        assertNull(coordinator.seekState.value.previewId)
        assertTrue(backend.playWhenReady)
    }

    @Test
    fun aQueuedNonSeekActionRevokesTheOwnerBeforeALateSeekAcknowledgement() = onMain {
        seed()
        val binding = coordinator.seekBinding()!!
        val gate = testGate()
        operations.seekGate = gate
        operations.seekEntered = CompletableDeferred()
        val command = asyncCommand { coordinator.seek(QueueSeekRequest(binding, 5000L)) }
        operations.seekEntered.await()
        coordinator.sessionPlayer.seekToNextMediaItem()
        assertNull(coordinator.seekState.value.ownerId)
        assertNull(coordinator.seekState.value.previewId)
        assertFalse(command.isCompleted)
        gate.complete(Unit)
        expectFailure(QueuePlaybackIssue.CONFLICT) { command.await() }
        awaitIdleCommand()
        assertEquals("entry-2", store.snapshot()!!.currentEntryId)
        assertEquals(0L, store.snapshot()!!.positionMs)
        assertFalse(backend.playWhenReady)
    }

    @Test
    fun naturalRepeatOfTheSameInstanceRevokesItsOldPlaybackCycleAndPreview() = onMain {
        seed()
        coordinator.sessionPlayer.play()
        val binding = coordinator.seekBinding()!!
        val owner = coordinator.seekState.value.ownerId
        val current = store.snapshot()!!.currentEntryId
        backend.fake.completeNaturally()
        awaitIdleCommand()
        assertEquals(current, store.snapshot()!!.currentEntryId)
        assertEquals(1, store.snapshot()!!.checkpoint.completedCycles)
        assertNotEquals(owner, coordinator.seekState.value.ownerId)
        assertNull(coordinator.seekState.value.previewId)
        val next = coordinator.seekBinding()!!
        assertTrue(next.playbackCycle > binding.playbackCycle)
        val calls = backend.seekCalls
        expectFailure(QueuePlaybackIssue.CONFLICT) { coordinator.seek(QueueSeekRequest(binding, 5000L)) }
        assertEquals(calls, backend.seekCalls)
    }

    @Test
    fun explicitPauseRevokesAPreviewEvenWhenTheBackendIsAlreadyPaused() = onMain {
        seed()
        val binding = coordinator.seekBinding()!!
        val owner = coordinator.seekState.value.ownerId
        val calls = backend.seekCalls
        coordinator.sessionPlayer.pause()
        assertNotEquals(owner, coordinator.seekState.value.ownerId)
        assertNull(coordinator.seekState.value.previewId)
        expectFailure(QueuePlaybackIssue.CONFLICT) { coordinator.seek(QueueSeekRequest(binding, 5000L)) }
        assertEquals(calls, backend.seekCalls)
        assertEquals(1234L, backend.currentPosition)
        assertFalse(backend.playWhenReady)
    }

    @Test
    fun aCompletedSeekReceiptCannotBeReplayedAfterANewNonSeekAction() = onMain {
        seed()
        val request = QueueSeekRequest(coordinator.seekBinding()!!, 5000L)
        coordinator.seek(request)
        val owner = coordinator.seekState.value.ownerId
        val calls = backend.seekCalls
        val readsBefore = reads.toList()
        coordinator.sessionPlayer.pause()
        assertNotEquals(owner, coordinator.seekState.value.ownerId)
        expectFailure(QueuePlaybackIssue.CONFLICT) { coordinator.seek(request) }
        assertEquals(calls, backend.seekCalls)
        assertEquals(readsBefore, reads)
        assertEquals(5000L, backend.currentPosition)
    }

    @Test
    fun unknownDurationUnavailableSeekAndRealBackendErrorsPublishNoOwner() = onMain {
        seed()
        val owner = coordinator.seekState.value.ownerId
        backend.fake.durationMs = C.TIME_UNSET
        backend.fake.refresh()
        assertNull(coordinator.seekSessionState().ownerId)
        assertNull(coordinator.seekBinding())
        backend.fake.durationMs = 10_000L
        backend.fake.refresh()
        assertNotEquals(owner, coordinator.seekSessionState().ownerId)
        assertTrue(coordinator.seekState.value.ownerId != null)
        backend.seekCommandAvailable = false
        assertNull(coordinator.seekSessionState().ownerId)
        assertNull(coordinator.seekBinding())
        backend.seekCommandAvailable = true
        assertTrue(coordinator.seekSessionState().ownerId != null)
        backend.fake.fail(PlaybackException("controlled seek failure", null, PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED))
        assertNull(coordinator.seekState.value.ownerId)
        assertNull(coordinator.seekState.value.previewId)
        assertNull(coordinator.seekBinding())
    }

    @Test
    fun explicitPauseWhileAlreadyPausedAlwaysRevokesTheDeferredFocusIntent() = onMain {
        seed()
        focusAllowed = false
        coordinator.sessionPlayer.play()
        assertFalse(backend.playWhenReady)
        val calls = userPauses
        coordinator.sessionPlayer.pause()
        coordinator.sessionPlayer.setPlayWhenReady(false)
        assertEquals(calls + 2, userPauses)
        assertFalse(backend.playWhenReady)
    }

    @Test
    fun stopReleasesBackendLoadingAndPrepareRestoresTheSameQueueAndCheckpointPaused() = onMain {
        seedWithCompletedCycle()
        coordinator.sessionPlayer.play()
        val before = store.snapshot()!!
        val session = coordinator.sessionPlayer
        session.stop()
        assertEquals(Player.STATE_IDLE, backend.playbackState)
        assertFalse(backend.playWhenReady)
        coordinator.flush()
        val saved = store.snapshot()!!
        assertEquals(before.generationId, saved.generationId)
        assertEquals(before.currentEntryId, saved.currentEntryId)
        assertEquals(1234L, saved.positionMs)
        assertEquals(1, saved.checkpoint.completedCycles)
        session.prepare()
        awaitIdleCommand()
        assertSame(session, coordinator.sessionPlayer)
        assertEquals(Player.STATE_READY, backend.playbackState)
        assertEquals(1234L, backend.currentPosition)
        assertEquals(1, store.snapshot()!!.checkpoint.completedCycles)
        assertFalse(backend.playWhenReady)
    }

    @Test
    fun aSingleFacadePlayAfterStopSurvivesSuspendedSourcePrepareAndSeekAndRestoresTheSavedPosition() = onMain {
        seedWithCompletedCycle()
        val session = coordinator.sessionPlayer
        session.play()
        session.stop()
        awaitIdleCommand()
        val saved = store.snapshot()!!
        val ids = store.page(saved.generationId, 0L).map { it.queueEntryId }
        val previousRounds = rounds.toList()
        val prepares = operations.prepares
        assertEquals(Player.STATE_IDLE, session.playbackState)
        assertEquals(1234L, saved.positionMs)
        assertEquals(1, saved.checkpoint.completedCycles)

        val sourceGate = testGate()
        val sourceEntered = CompletableDeferred<Unit>()
        resolveHook = { entry ->
            if (entry.queueEntryId == saved.currentEntryId) {
                sourceEntered.complete(Unit)
                sourceGate.await()
            }
            entry
        }
        val prepareGate = testGate()
        operations.prepareGate = prepareGate
        operations.prepareEntered = CompletableDeferred()
        val seekGate = testGate()
        operations.seekGate = seekGate
        operations.seekEntered = CompletableDeferred()

        // One Play owns the complete restore; separate Prepare/Seek commands would lose its intent.
        session.play()
        sourceEntered.await()
        assertTrue(coordinator.state.value.preparing)
        assertFalse(backend.playWhenReady)
        assertEquals(1234L, backend.currentPosition)
        assertEquals(prepares, operations.prepares)
        sourceGate.complete(Unit)
        operations.prepareEntered.await()
        assertTrue(coordinator.state.value.preparing)
        assertFalse(backend.playWhenReady)
        assertEquals(prepares + 1, operations.prepares)
        assertEquals(1234L, store.snapshot()!!.positionMs)
        prepareGate.complete(Unit)
        operations.seekEntered.await()
        assertFalse(backend.playWhenReady)
        assertEquals(1234L, store.snapshot()!!.positionMs)
        seekGate.complete(Unit)
        awaitIdleCommand()

        val restored = store.snapshot()!!
        assertSame(session, coordinator.sessionPlayer)
        assertEquals(saved.generationId, restored.generationId)
        assertEquals(saved.queueRevision, restored.queueRevision)
        assertEquals(saved.mode, restored.mode)
        assertEquals(saved.currentEntryId, restored.currentEntryId)
        assertEquals(ids, store.page(restored.generationId, 0L).map { it.queueEntryId })
        assertEquals(1234L, restored.positionMs)
        assertEquals(1, restored.checkpoint.completedCycles)
        assertEquals(previousRounds, rounds)
        assertEquals(Player.STATE_READY, session.playbackState)
        assertEquals(saved.currentEntryId, session.currentMediaItem?.mediaId)
        assertEquals(1234L, session.currentPosition)
        assertTrue(backend.playWhenReady)
        assertTrue(session.isPlaying)
        assertNull(coordinator.state.value.failure)
    }

    @Test
    fun aSingleFacadePlayAfterNaturalEndSurvivesSuspendedRestoreAndRestartsTheSameInstanceAtZero() = onMain {
        seed(current = 4L)
        coordinator.mutate(edit("last-item-one-completion", store.snapshot()!!,
            QueueMutation.SetMode(QueueMode(false, QueueRepeatMode.SINGLE, 1))))
        val session = coordinator.sessionPlayer
        session.play()
        backend.fake.completeNaturally()
        awaitIdleCommand()
        val ended = store.snapshot()!!
        val ids = store.page(ended.generationId, 0L).map { it.queueEntryId }
        val previousRounds = rounds.toList()
        val prepares = operations.prepares
        assertEquals(Player.STATE_ENDED, session.playbackState)
        assertEquals("entry-4", ended.currentEntryId)
        assertEquals(10_000L, ended.positionMs)
        assertEquals(1, ended.checkpoint.completedCycles)
        assertFalse(backend.playWhenReady)

        val sourceGate = testGate()
        val sourceEntered = CompletableDeferred<Unit>()
        resolveHook = { entry ->
            if (entry.queueEntryId == ended.currentEntryId) {
                sourceEntered.complete(Unit)
                sourceGate.await()
            }
            entry
        }
        val prepareGate = testGate()
        operations.prepareGate = prepareGate
        operations.prepareEntered = CompletableDeferred()
        val seekGate = testGate()
        operations.seekGate = seekGate
        operations.seekEntered = CompletableDeferred()

        session.play()
        sourceEntered.await()
        assertTrue(coordinator.state.value.preparing)
        assertFalse(backend.playWhenReady)
        assertEquals(10_000L, backend.currentPosition)
        assertEquals(prepares, operations.prepares)
        assertEquals(0L, store.snapshot()!!.positionMs)
        sourceGate.complete(Unit)
        operations.prepareEntered.await()
        assertFalse(backend.playWhenReady)
        assertEquals(prepares + 1, operations.prepares)
        prepareGate.complete(Unit)
        operations.seekEntered.await()
        assertFalse(backend.playWhenReady)
        seekGate.complete(Unit)
        awaitIdleCommand()

        val restarted = store.snapshot()!!
        assertSame(session, coordinator.sessionPlayer)
        assertEquals(ended.generationId, restarted.generationId)
        assertEquals(ended.queueRevision, restarted.queueRevision)
        assertEquals(ended.mode, restarted.mode)
        assertEquals(ended.currentEntryId, restarted.currentEntryId)
        assertEquals(ids, store.page(restarted.generationId, 0L).map { it.queueEntryId })
        assertEquals(0L, restarted.positionMs)
        assertEquals(0, restarted.checkpoint.completedCycles)
        assertEquals(previousRounds + listOf("entry-4"), rounds)
        assertEquals(Player.STATE_READY, session.playbackState)
        assertEquals(ended.currentEntryId, session.currentMediaItem?.mediaId)
        assertEquals(0L, session.currentPosition)
        assertTrue(backend.playWhenReady)
        assertTrue(session.isPlaying)
        assertNull(coordinator.state.value.failure)
    }

    @Test
    fun naturalCompletionDuringAnEditIsCountedOnceAfterTheEdit() = onMain {
        seed()
        coordinator.sessionPlayer.play()
        val gate = testGate()
        val entered = CompletableDeferred<Unit>()
        resolveHook = { entry -> if (entry.queueEntryId == "entry-1") { entered.complete(Unit); gate.await() }; entry }
        val operation = asyncCommand { coordinator.mutate(edit("editing-at-end", store.snapshot()!!, QueueMutation.Move("entry-0", 4L))) }
        entered.await()
        backend.fake.completeNaturally()
        assertEquals(0, store.snapshot()!!.checkpoint.completedCycles)
        gate.complete(Unit)
        operation.await()
        // The same real round finished once despite both END callbacks and a new queue generation.
        awaitIdleCommand()
        assertEquals("entry-1", store.snapshot()!!.currentEntryId)
        assertEquals(1, store.snapshot()!!.checkpoint.completedCycles)
        assertEquals(0L, backend.currentPosition)
        assertTrue(backend.playWhenReady)
    }

    @Test
    fun aResolvedDifferentInstanceRejectsTheStagedReplacementAndKeepsTheOldAudio() = onMain {
        seed()
        coordinator.sessionPlayer.play()
        coordinator.flush()
        val before = store.snapshot()!!
        val pauses = backend.pauseCalls
        val seeks = backend.seekCalls
        val view = FrozenQueueView("bad-resolver", 1L, 1L)
        resolveHook = { it.copy(queueEntryId = "another-instance") }
        expectFailure(QueuePlaybackIssue.SOURCE_CHANGED) {
            coordinator.replace(QueueReplacementRequest("bad-identity", before.generationId, before.queueRevision, view),
                QueuePageSource { offset, _ -> QueueInputPage(view, offset, listOf(entry(0L))) }, explicitPlay = true)
        }
        assertEquals(before, store.snapshot())
        assertEquals(before.currentEntryId, backend.currentMediaItem?.mediaId)
        assertEquals(pauses, backend.pauseCalls)
        assertEquals(seeks, backend.seekCalls)
        assertTrue(backend.playWhenReady)
    }

    @Test
    fun backendSwitchKeepsSessionInstancePositionAndCycleAndRejectsOldCallbacks() = onMain {
        seedWithCompletedCycle()
        coordinator.sessionPlayer.play()
        val binding = coordinator.seekBinding()!!
        val seekOwner = coordinator.seekState.value.ownerId
        val session = coordinator.sessionPlayer
        val beforeRounds = rounds.toList()
        val old = backend
        val oldListeners = old.listeners.toList()
        val checkpoint = coordinator.freezeForBackendSwitch()
        assertNull(coordinator.seekState.value.ownerId)
        assertNull(coordinator.seekState.value.previewId)
        coordinator.unbindBackendForSwitch(checkpoint)
        assertNull(coordinator.seekSessionState().ownerId)
        old.release()
        val pair = createBackend()
        backend = pair.first
        operations = pair.second
        coordinator.restoreAfterBackendSwitch(backend, operations, checkpoint)
        assertNotEquals(seekOwner, coordinator.seekState.value.ownerId)
        assertNull(coordinator.seekState.value.previewId)
        assertEquals(binding.playbackCycle, coordinator.seekBinding()!!.playbackCycle)
        assertSame(session, coordinator.sessionPlayer)
        assertEquals(beforeRounds, rounds)
        assertEquals(1234L, session.currentPosition)
        assertEquals(1, store.snapshot()!!.checkpoint.completedCycles)
        assertFalse(session.playWhenReady)
        assertTrue(session.availableCommands.contains(Player.COMMAND_PLAY_PAUSE))
        for (listener in oldListeners) {
            listener.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
            listener.onPlayerError(PlaybackException("stale", null, PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED))
        }
        assertFalse(backend.playWhenReady)
        assertNull(coordinator.state.value.failure)
        expectFailure(QueuePlaybackIssue.CONFLICT) { coordinator.restoreAfterBackendSwitch(backend, operations, checkpoint) }
        expectFailure(QueuePlaybackIssue.CONFLICT) { coordinator.seek(QueueSeekRequest(binding, 5000L)) }
        assertEquals(0, old.lateCalls)
    }

    @Test
    fun failedOldListenerRemovalStillFencesTheBackendAndLeavesTheSwitchTokenRestorable() = onMain {
        seed()
        val old = backend
        val checkpoint = coordinator.freezeForBackendSwitch()
        old.removeListenerFailure = IllegalStateException("Listener removal failed")
        try {
            coordinator.unbindBackendForSwitch(checkpoint)
            throw AssertionError("The listener failure must be surfaced")
        } catch (_: IllegalStateException) { }
        assertTrue(coordinator.sessionPlayer.availableCommands.size() == 0)
        old.release()
        val replacement = createBackend()
        backend = replacement.first
        operations = replacement.second
        coordinator.restoreAfterBackendSwitch(backend, operations, checkpoint)
        assertEquals(1234L, backend.currentPosition)
        assertFalse(backend.playWhenReady)
        assertEquals(0, old.lateCalls)
    }

    @Test
    fun failingInitialSwitchRefreshIsSurfacedAndDoesNotLeaveTheCoordinatorBusy() = onMain {
        seed()
        coordinator.sessionPlayer.play()
        val failure = IllegalStateException("Backend command read failed")
        backend.availableCommandsFailure = failure
        try {
            coordinator.freezeForBackendSwitch()
            throw AssertionError("The state read failure must be surfaced")
        } catch (error: IllegalStateException) { assertSame(failure, error) }
        backend.availableCommandsFailure = null
        assertFalse(backend.playWhenReady)
        val checkpoint = coordinator.freezeForBackendSwitch()
        coordinator.cancelBackendSwitch(checkpoint)
        assertTrue(coordinator.sessionPlayer.availableCommands.contains(Player.COMMAND_PLAY_PAUSE))
    }

    @Test
    fun failingInitialActionRefreshReleasesItsMutexAndTheNextCommandCanRestorePaused() = onMain {
        seed()
        val before = store.snapshot()!!
        backend.availableCommandsFailure = IllegalStateException("One failed state read")
        backend.availableCommandsFailureRemaining = 1
        expectFailure(QueuePlaybackIssue.OUTPUT_ERROR) { coordinator.initialize() }
        assertFalse(coordinator.state.value.preparing)
        assertEquals(before, store.snapshot())
        backend.availableCommandsFailure = null
        coordinator.initialize()
        assertEquals(before.currentEntryId, backend.currentMediaItem?.mediaId)
        assertEquals(1234L, backend.currentPosition)
        assertFalse(backend.playWhenReady)
    }

    @Test
    fun failingFinalPositionCaptureStillDetachesAllCallbacksBeforeTheBackendIsReleased() = onMain {
        seed()
        val oldListeners = backend.listeners.toList()
        val failure = IllegalStateException("Position read failed during owner detach")
        backend.currentPositionFailure = failure
        backend.removeListenerFailure = failure
        try {
            coordinator.detach()
            throw AssertionError("The capture failure must be surfaced")
        } catch (error: IllegalStateException) { assertSame(failure, error) }
        backend.release()
        for (listener in oldListeners) {
            listener.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
            listener.onPlayerError(PlaybackException("stale after detach", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
        }
        assertFalse(coordinator.sessionPlayer.availableCommands.contains(Player.COMMAND_PLAY_PAUSE))
        assertEquals(0L, coordinator.sessionPlayer.currentPosition)
        coordinator.close()
        assertEquals(0, backend.lateCalls)
    }

    @Test
    fun failedTargetCanBeReleasedAndOldKindRestoredPausedWithTheSameUnconsumedToken() = onMain {
        seedWithCompletedCycle()
        val checkpoint = coordinator.freezeForBackendSwitch()
        coordinator.unbindBackendForSwitch(checkpoint)
        backend.release()
        val target = createBackend()
        backend = target.first
        operations = target.second
        operations.prepareFailure = PlaybackException("target failed", null, PlaybackException.ERROR_CODE_DECODING_FAILED)
        try {
            coordinator.restoreAfterBackendSwitch(backend, operations, checkpoint)
            throw AssertionError("Failed target must not consume the switch token")
        } catch (_: PlaybackException) { }
        coordinator.unbindBackendForSwitch(checkpoint)
        backend.release()
        val rollback = createBackend()
        backend = rollback.first
        operations = rollback.second
        coordinator.restoreAfterBackendSwitch(backend, operations, checkpoint)
        assertEquals(1234L, backend.currentPosition)
        assertEquals(1, store.snapshot()!!.checkpoint.completedCycles)
        assertFalse(backend.playWhenReady)
        expectFailure(QueuePlaybackIssue.CONFLICT) { coordinator.restoreAfterBackendSwitch(backend, operations, checkpoint) }
    }

    @Test
    fun startupBackendSwitchWorksBeforeTheFirstQueueInitialization() = onMain {
        val checkpoint = coordinator.freezeForBackendSwitch()
        assertNull(checkpoint.snapshot)
        coordinator.unbindBackendForSwitch(checkpoint)
        backend.release()
        val pair = createBackend()
        backend = pair.first
        operations = pair.second
        coordinator.restoreAfterBackendSwitch(backend, operations, checkpoint)
        assertNull(coordinator.initialize())
        assertEquals(0, operations.prepares)
        assertEquals(0, backend.mediaItemCount)
        assertFalse(backend.playWhenReady)
    }

    @Test
    fun freezeAwaitsAnUncancellableOldReadBeforeTheOldBackendCanBeReleased() = onMain {
        seed()
        coordinator.sessionPlayer.play()
        val gate = testGate()
        val entered = CompletableDeferred<Unit>()
        resolveHook = { entry ->
            if (entry.queueEntryId == "entry-1") { entered.complete(Unit); withContext(NonCancellable) { gate.await() } }
            entry
        }
        val old = store.snapshot()!!
        val edit = asyncCommand { coordinator.mutate(edit("cancel-for-switch", old, QueueMutation.Move("entry-0", 4L))) }
        entered.await()
        val switch = asyncCommand { coordinator.freezeForBackendSwitch() }
        assertFalse(switch.isCompleted)
        assertFalse(backend.released)
        gate.complete(Unit)
        val checkpoint = switch.await()
        assertTrue(edit.isCancelled)
        assertEquals(old.generationId, checkpoint.snapshot?.generationId)
        coordinator.cancelBackendSwitch(checkpoint)
        assertFalse(backend.playWhenReady)
        assertEquals(old.generationId, store.snapshot()!!.generationId)
    }

    @Test
    fun closeAwaitsACanceledProviderReadAfterDetachAndNeverReadsTheReleasedBackend() = onMain {
        seed()
        val before = store.snapshot()!!
        val gate = testGate()
        val entered = CompletableDeferred<Unit>()
        resolveHook = { entry ->
            if (entry.queueEntryId == "entry-1") {
                entered.complete(Unit)
                withContext(NonCancellable) { gate.await() }
            }
            entry
        }
        val edit = asyncCommand { coordinator.mutate(edit("cancel-for-close", before, QueueMutation.Move("entry-0", 4L))) }
        entered.await()
        coordinator.detach()
        backend.release()
        val shutdown = asyncCommand { coordinator.close() }
        kotlinx.coroutines.yield()
        assertFalse(shutdown.isCompleted)
        gate.complete(Unit)
        shutdown.await()
        assertTrue(edit.isCancelled)
        assertEquals(before.generationId, store.snapshot()!!.generationId)
        assertEquals(1234L, store.snapshot()!!.positionMs)
        assertEquals(0, backend.lateCalls)
    }

    @Test
    fun runtimeResourceRecoveryRefreshesOnceSkipsAtMostThreeAndDoesNotWrap() = onMain {
        seed(count = 8)
        coordinator.sessionPlayer.play()
        unavailable = setOf("entry-1", "entry-2", "entry-3", "entry-4", "entry-5")
        reads.clear()
        backend.fake.fail(PlaybackException("missing", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
        awaitIdleCommand()
        assertEquals(listOf("entry-1", "entry-2", "entry-3", "entry-4"), reads)
        assertEquals(8L, store.snapshot()!!.entryCount)
        assertFalse(backend.playWhenReady)
        assertEquals(0, backend.mediaItemCount)
        assertEquals(QueuePlaybackIssue.FILE_MISSING, coordinator.state.value.failure?.issue)
    }

    @Test
    fun refreshThatCannotConfirmTheSourceIdentityStopsBeforeAnyAutomaticSkip() = onMain {
        seed()
        coordinator.sessionPlayer.play()
        val before = store.snapshot()!!
        reads.clear()
        resolveHook = { entry -> throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.CONTENT_UNKNOWN, entry.queueEntryId)) }
        backend.fake.fail(PlaybackException("missing", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
        awaitIdleCommand()
        assertEquals(listOf("entry-1"), reads)
        assertEquals(before.generationId, store.snapshot()!!.generationId)
        assertEquals(before.currentEntryId, store.snapshot()!!.currentEntryId)
        assertEquals(before.entryCount, store.snapshot()!!.entryCount)
        assertEquals(QueuePlaybackIssue.CONTENT_UNKNOWN, coordinator.state.value.failure?.issue)
        assertFalse(backend.playWhenReady)
    }

    private suspend fun seed(count: Int = 5, current: Long = 1L) {
        coordinator.initialize()
        val view = FrozenQueueView("initial", 1L, count.toLong())
        coordinator.replace(QueueReplacementRequest("seed", null, 0L, view, QueueMode(false, QueueRepeatMode.SINGLE, 2),
            startOriginalOrder = current, initialPositionMs = 1234L), QueuePageSource { offset, limit ->
            QueueInputPage(view, offset, List(limit) { entry(offset + it) })
        }, explicitPlay = false)
    }

    private suspend fun seedWithCompletedCycle() {
        seed()
        val saved = store.snapshot()!!
        store.saveCheckpoint(saved.generationId, saved.checkpointRevision, QueueCheckpoint(saved.currentEntryId, 1234L, 1))
        coordinator.initialize()
    }

    private fun edit(id: String, saved: QueueSnapshot, mutation: QueueMutation) = QueueMutationRequest(
        id, saved.generationId, saved.queueRevision, saved.currentEntryId, mutation,
    )

    private fun entry(order: Long) = QueueEntry("entry-$order", TrackKey(TrackKey.LOCAL_PROVIDER, "track-$order"), order, order,
        durationMs = 10_000L, displayName = "Track $order", location = LocalLocationReference("location-$order", "content://test-audio/$order"))

    private suspend fun expectFailure(issue: QueuePlaybackIssue, block: suspend () -> Unit) {
        try { block(); throw AssertionError("Expected $issue") }
        catch (error: QueuePlaybackException) { assertEquals(issue, error.failure.issue) }
    }

    private suspend fun expectStoreFailure(issue: QueueStoreIssue, block: suspend () -> Unit) {
        try { block(); throw AssertionError("Expected $issue") }
        catch (error: QueueStoreException) { assertEquals(issue, error.issue) }
    }

    private fun testGate(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { gates.add(it) }

    private fun <T> asyncCommand(block: suspend () -> T): Deferred<T> =
        scope.async { block() }.also { testCommands.add(it) }

    private suspend fun awaitIdleCommand() {
        // A cleared preparing flag can precede the worker's finally block in the same Main turn.
        kotlinx.coroutines.yield()
        while (coordinator.state.value.preparing) {
            kotlinx.coroutines.yield()
        }
        coordinator.flush()
    }

    private suspend fun libraryWithTracks(): CountingLibrary {
        val library = RoomLocalLibraryRepository(database, { 1000L }, { 1_790_000_000_000L })
        library.initialize()
        val source = library.registerSource(LocalSourceSpec("producer", LocalSourceKind.TREE,
            "content://producer-fixture/tree/root", authority = "producer-fixture", rootObjectId = "root",
            providerIncarnation = "fixture-instance"), LocalGrantObservation(
                read = ObservedCapability.AVAILABLE, enumerate = ObservedCapability.AVAILABLE))
        val token = library.beginScan(source.spec.sourceId, source.authEpoch, library.rules().revision, ScanMode.INCREMENTAL)
        val root = ObservedCandidate(source.spec.sourceId, "root", source.spec.uri, null, "Root",
            "vnd.android.document/directory", LocalNodeKind.DIRECTORY)
        val audio = List(4) { ordinal -> ObservedCandidate(source.spec.sourceId, "audio-$ordinal",
            "content://producer-fixture/document/audio-$ordinal", "root", "Visible $ordinal.mp3", "audio/mpeg",
            LocalNodeKind.AUDIO, sizeBytes = 128L, providerContentToken = "native:audio-$ordinal:1") }
        library.commitSeen(token, listOf(root) + audio)
        while (true) {
            val directory = library.nextDirectory(token) ?: break
            library.completeDirectory(token, directory.objectId, true)
        }
        val coverage = ScanCoverage(true, true, true, true, false)
        library.setCoverage(token, coverage)
        library.finishScan(token, ScanCounters(), coverage)
        return CountingLibrary(library)
    }

    private suspend fun commitLibraryQueue(library: LocalLibraryRepository, command: LibraryQueueCommand): QueueCommit {
        val producer = LibraryQueueProducer(library, command)
        try { return producer.commit(coordinator) }
        finally { producer.close() }
    }

    /** Counts the real library lease and source calls; a closed view really fails if replay tries to reopen it. */
    private class CountingLibrary(private val library: LocalLibraryRepository) : LocalLibraryRepository by library {
        private var queueViews = 0
        private var sources = 0
        private var sourceReads = 0
        private var locations = 0

        override suspend fun openQueueView(fromViewId: String): LibraryViewToken {
            queueViews += 1
            return library.openQueueView(fromViewId)
        }

        override suspend fun locate(view: LibraryViewToken, trackKey: TrackKey): LibraryLocation? {
            locations += 1
            return library.locate(view, trackKey)
        }

        override fun queueSource(view: LibraryViewToken): QueuePageSource {
            sources += 1
            val source = library.queueSource(view)
            return QueuePageSource { offset, limit -> sourceReads += 1; source.read(offset, limit) }
        }

        fun counts(): List<Int> = listOf(queueViews, sources, sourceReads, locations)
    }

    /** Pump the paused Android Looper while Room works on its normal executors, without a test thread bypass. */
    private fun <T> onMain(block: suspend () -> T): T {
        val task = scope.async(Dispatchers.Main.immediate) { block() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30L)
        while (!task.isCompleted && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!task.isCompleted) Thread.sleep(1L)
        }
        if (!task.isCompleted) { task.cancel(); throw AssertionError("Main/Room operation did not complete within the test deadline") }
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
        override suspend fun save(expectedRevision: Long, draft: UserPreferencesDraft): PreferencesWriteResult = error("No preference writes in queue integration tests")
        override suspend fun reset(expectedRevision: Long, confirmation: ResetConfirmation): PreferencesWriteResult = error("No reset in queue integration tests")
        override fun retryRead() = Unit
    }

    private class ControlledOperations(
        override val player: CheckedPlayer,
        private val raw: ControlledPlayer,
    ) : PlaybackBackendOperations {
        var prepareGate: CompletableDeferred<Unit>? = null
        var prepareEntered = CompletableDeferred<Unit>()
        var seekGate: CompletableDeferred<Unit>? = null
        var seekEntered = CompletableDeferred<Unit>()
        var prepareFailure: PlaybackException? = null
        var seekAdjustmentMs = 0L
        var prepares = 0

        override fun setPauseAtBoundary(pause: Boolean) { assertTrue(pause) }

        override suspend fun prepareAndAwait(expectedMediaId: String) {
            prepares += 1
            prepareEntered.complete(Unit)
            prepareGate?.await()
            currentCoroutineContext().ensureActive()
            assertEquals(expectedMediaId, player.currentMediaItem?.mediaId)
            prepareFailure?.let { throw it }
            player.prepare()
            raw.prepared()
        }

        override suspend fun seekAndAwait(expectedMediaId: String, positionMs: Long) {
            assertEquals(expectedMediaId, player.currentMediaItem?.mediaId)
            raw.deferSeek = true
            try { player.seekTo(positionMs) } finally { raw.deferSeek = false }
            seekEntered.complete(Unit)
            seekGate?.await()
            currentCoroutineContext().ensureActive()
            assertEquals(expectedMediaId, player.currentMediaItem?.mediaId)
            raw.acknowledgeSeek((positionMs + seekAdjustmentMs).coerceIn(0L, raw.durationMs))
        }
    }

    /** Actual position changes only at a test-controlled acknowledgment; there is no audio output. */
    private class ControlledPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        private data class Item(val uid: String, val media: MediaItem)
        private val items = mutableListOf<Item>()
        private var uid = 0L
        private var index = C.INDEX_UNSET
        private var position = 0L
        private var ready = false
        private var ended = false
        private var buffering = false
        private var requestedPlay = false
        private var reason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
        private var failure: PlaybackException? = null
        private var closed = false
        var durationMs = 10_000L
        var deferSeek = false
        var peakWindow = 0
            private set

        override fun getState(): State {
            val playlist = items.map { item -> MediaItemData.Builder(item.uid).setMediaItem(item.media)
                .setDurationUs(if (durationMs == C.TIME_UNSET) C.TIME_UNSET else durationMs * 1000L).setIsSeekable(true).build() }
            val state = when {
                closed || items.isEmpty() || failure != null -> Player.STATE_IDLE
                ended -> Player.STATE_ENDED
                buffering -> Player.STATE_BUFFERING
                ready -> Player.STATE_READY
                else -> Player.STATE_IDLE
            }
            return State.Builder().setAvailableCommands(if (closed) Player.Commands.EMPTY else COMMANDS)
                .setPlaylist(playlist).setCurrentMediaItemIndex(index).setPlaybackState(state)
                .setPlayerError(failure).setPlayWhenReady(requestedPlay, reason)
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

        override fun handlePrepare(): ListenableFuture<*> { ready = true; ended = false; failure = null; return immediate() }
        override fun handleStop(): ListenableFuture<*> { requestedPlay = false; ready = false; return immediate() }
        override fun handleRelease(): ListenableFuture<*> {
            closed = true
            requestedPlay = false
            ready = false
            return immediate()
        }

        override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
            items.clear()
            items.addAll(mediaItems.map { Item("source-${uid++}", it) })
            index = if (items.isEmpty()) C.INDEX_UNSET else if (startIndex == C.INDEX_UNSET) 0 else startIndex
            position = if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs
            ready = false
            ended = false
            buffering = false
            failure = null
            recordWindow()
            return immediate()
        }

        override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
            val current = items.getOrNull(this.index)?.uid
            items.addAll(index, mediaItems.map { Item("source-${uid++}", it) })
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
            val target = if (positionMs == C.TIME_UNSET) 0L else positionMs.coerceIn(0L, durationMs)
            if (mediaItemIndex != index) { index = mediaItemIndex; ready = false; ended = false }
            if (deferSeek) buffering = true else {
                position = target
                ended = target >= durationMs
                buffering = false
            }
            reason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
            return immediate()
        }

        fun prepared() { ready = true; ended = false; buffering = false; failure = null; invalidateState() }
        fun advancePosition(positionMs: Long) {
            position = positionMs
            invalidateState()
        }
        fun acknowledgeSeek(positionMs: Long) {
            position = positionMs
            buffering = false
            ready = true
            ended = position >= durationMs
            reason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
            invalidateState()
        }

        fun completeNaturally() {
            position = durationMs
            ended = true
            buffering = false
            requestedPlay = false
            reason = Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
            invalidateState()
        }

        fun fail(error: PlaybackException) { failure = error; ready = false; buffering = false; invalidateState() }
        fun refresh() = invalidateState()
        private fun recordWindow() { peakWindow = maxOf(peakWindow, items.size); assertTrue(items.size <= 3) }
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

    /** Every API used by coordinator/facade fails after release, including all cached clock suppliers. */
    private class CheckedPlayer(val fake: ControlledPlayer) : Player by fake {
        var released = false
            private set
        var lateCalls = 0
            private set
        var pauseCalls = 0
            private set
        var seekCalls = 0
            private set
        var removeListenerFailure: IllegalStateException? = null
        var availableCommandsFailure: IllegalStateException? = null
        var availableCommandsFailureRemaining = Int.MAX_VALUE
        var currentPositionFailure: IllegalStateException? = null
        var seekCommandAvailable = true
        val listeners = mutableListOf<Player.Listener>()
        val peakWindow get() = fake.peakWindow

        private fun <T> live(block: () -> T): T {
            if (released) { lateCalls += 1; throw AssertionError("Released backend API used") }
            return block()
        }
        override fun getApplicationLooper(): Looper = live { fake.applicationLooper }
        override fun getAvailableCommands(): Player.Commands = live {
            availableCommandsFailure?.takeIf { availableCommandsFailureRemaining > 0 }?.let {
                availableCommandsFailureRemaining -= 1
                throw it
            }
            fake.availableCommands
        }
        override fun isCommandAvailable(command: Int): Boolean = live {
            (command != Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM || seekCommandAvailable) && fake.isCommandAvailable(command)
        }
        override fun getRepeatMode(): Int = live { fake.repeatMode }
        override fun getShuffleModeEnabled(): Boolean = live { fake.shuffleModeEnabled }
        override fun getPlaybackState(): Int = live { fake.playbackState }
        override fun getPlayWhenReady(): Boolean = live { fake.playWhenReady }
        override fun getPlayerError(): PlaybackException? = live { fake.playerError }
        override fun getCurrentMediaItem(): MediaItem? = live { fake.currentMediaItem }
        override fun getCurrentMediaItemIndex(): Int = live { fake.currentMediaItemIndex }
        override fun getMediaItemCount(): Int = live { fake.mediaItemCount }
        override fun getMediaItemAt(index: Int): MediaItem = live { fake.getMediaItemAt(index) }
        override fun getCurrentPosition(): Long = live { currentPositionFailure?.let { throw it }; fake.currentPosition }
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
        override fun play() = live { fake.play() }
        override fun pause() = live { pauseCalls += 1; fake.pause() }
        override fun stop() = live { fake.stop() }
        override fun prepare() = live { fake.prepare() }
        override fun seekTo(positionMs: Long) = live { seekCalls += 1; fake.seekTo(positionMs) }
        override fun seekTo(mediaItemIndex: Int, positionMs: Long) = live { seekCalls += 1; fake.seekTo(mediaItemIndex, positionMs) }
        override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) = live { fake.setMediaItems(mediaItems, startIndex, startPositionMs) }
        override fun clearMediaItems() = live { fake.clearMediaItems() }
        override fun addMediaItem(index: Int, mediaItem: MediaItem) = live { fake.addMediaItem(index, mediaItem) }
        override fun moveMediaItem(currentIndex: Int, newIndex: Int) = live { fake.moveMediaItem(currentIndex, newIndex) }
        override fun removeMediaItem(index: Int) = live { fake.removeMediaItem(index) }
        override fun setRepeatMode(repeatMode: Int) = live { fake.repeatMode = repeatMode }
        override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) = live { fake.shuffleModeEnabled = shuffleModeEnabled }
        override fun addListener(listener: Player.Listener) = live { listeners.add(listener); fake.addListener(listener) }
        override fun removeListener(listener: Player.Listener) = live {
            removeListenerFailure?.let { throw it }
            listeners.remove(listener)
            fake.removeListener(listener)
        }
        override fun release() = live { fake.release(); released = true }
    }
}
