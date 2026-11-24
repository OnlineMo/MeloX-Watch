package io.github.onlinemo.meloxwatch.queue

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesState
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesWriteResult
import io.github.onlinemo.meloxwatch.data.preferences.ResetConfirmation
import io.github.onlinemo.meloxwatch.data.preferences.UserPreferencesRepository
import io.github.onlinemo.meloxwatch.data.queue.QueueCommit
import io.github.onlinemo.meloxwatch.data.queue.QueueInputPage
import io.github.onlinemo.meloxwatch.data.queue.QueuePageSource
import io.github.onlinemo.meloxwatch.data.queue.QueueReplacementRequest
import io.github.onlinemo.meloxwatch.data.queue.QueueStore
import io.github.onlinemo.meloxwatch.data.queue.QueueStoreException
import io.github.onlinemo.meloxwatch.data.queue.QueueStoreIssue
import io.github.onlinemo.meloxwatch.model.FrozenQueueView
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import io.github.onlinemo.meloxwatch.model.QueueCheckpoint
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueMode
import io.github.onlinemo.meloxwatch.model.QueueMutationCandidate
import io.github.onlinemo.meloxwatch.model.QueueMutationRequest
import io.github.onlinemo.meloxwatch.model.QueueRepeatMode
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import io.github.onlinemo.meloxwatch.model.QueueWindow
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.model.UserPreferences
import io.github.onlinemo.meloxwatch.model.UserPreferencesDraft
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackCoordinator
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackException
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackFailure
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue
import io.github.onlinemo.meloxwatch.playback.PlaybackBackendOperations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

/**
 * 主 Looper ExoPlayer 的窗口/命令，DB gate 控制 IO 时序；准备/seek 用明确的意图 stub。
 * content://audio 假 URI 不证明实际 READY、解码、出声或设备验收。
 * 不用 JUnit timeout 的额外线程，保持与 Service 相同的主线程调用约束。
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class QueuePlaybackCoordinatorTest {
    private lateinit var backend: ReleaseGuard
    private lateinit var queues: ControlledQueueStore
    private lateinit var scope: CoroutineScope
    private lateinit var coordinator: QueuePlaybackCoordinator

    @Before
    fun createCoordinator() {
        backend = ReleaseGuard(
            ExoPlayer.Builder(RuntimeEnvironment.getApplication())
                .setLooper(Looper.getMainLooper())
                .setUsePlatformDiagnostics(false)
                .setHandleAudioBecomingNoisy(false)
                .build(),
        )
        queues = ControlledQueueStore(List(3) { entry(it.toLong()) })
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        coordinator = QueuePlaybackCoordinator(backend, queues, FixedPreferences(), scope, operations = IntentOnlyOperations(backend))
    }

    @After
    fun releaseCoordinator() = runBlocking {
        coordinator.detach()
        if (!backend.released) backend.release()
        coordinator.close()
        scope.cancel()
        assertEquals(0, backend.lateCalls)
    }

    @Test
    fun restoreAndRepeatedInitializationStayPausedWithoutSelfCancellation() = runBlocking {
        coordinator.initialize()
        assertFalse(backend.playWhenReady)
        assertEquals(1234L, backend.currentPosition)
        coordinator.sessionPlayer.play()
        assertTrue(backend.playWhenReady)
        coordinator.initialize()
        assertFalse(backend.playWhenReady)
        assertEquals(1234L, backend.currentPosition)
        assertFalse(coordinator.state.value.preparing)
    }

    @Test
    fun pauseWhileAlreadyPausedRevokesPlayAfterAnAtomicQueueCommit() = runBlocking {
        coordinator.initialize()
        val old = queues.snapshot()
        val gate = Gate(ignoreCancellation = true)
        queues.commitGate = gate
        val request = replacement("next", old)
        val operation = scope.async { coordinator.replace(request, source(request), explicitPlay = true) }
        assertTrue(gate.entered.isCompleted)

        // 没有 backend pause event 也必须失效旧显式播放 token。
        coordinator.sessionPlayer.pause()
        gate.release.complete(Unit)
        operation.join()

        assertNotEquals(old.generationId, queues.snapshot().generationId)
        assertEquals(queues.snapshot().generationId, coordinator.state.value.snapshot?.generationId)
        assertFalse(backend.playWhenReady)
        assertFalse(coordinator.state.value.preparing)
    }

    @Test
    fun pauseRejectsALateSelectionReadAndKeepsThePreviousInstance() = runBlocking {
        coordinator.initialize()
        val old = queues.snapshot()
        val gate = Gate(ignoreCancellation = true)
        queues.windowGate = gate
        val operation = scope.async { coordinator.select(old.generationId, old.queueRevision, "entry-2", explicitPlay = true) }
        assertTrue(gate.entered.isCompleted)
        coordinator.sessionPlayer.pause()
        gate.release.complete(Unit)
        operation.join()

        assertTrue(operation.isCancelled)
        assertEquals(old.currentEntryId, queues.snapshot().currentEntryId)
        assertEquals(old.currentEntryId, backend.currentMediaItem?.mediaId)
        assertFalse(backend.playWhenReady)
    }

    @Test
    fun detachLetsAnAtomicCommitFinishWithoutTouchingTheReleasedBackend() = runBlocking {
        coordinator.initialize()
        val gate = Gate(ignoreCancellation = true)
        queues.commitGate = gate
        val request = replacement("closing", queues.snapshot())
        val operation = scope.async { coordinator.replace(request, source(request), explicitPlay = true) }
        assertTrue(gate.entered.isCompleted)
        coordinator.detach()
        backend.release()
        gate.release.complete(Unit)
        operation.join()
        coordinator.flush()
        coordinator.close()

        assertEquals("generation-closing", queues.snapshot().generationId)
        assertEquals(0, backend.lateCalls)
        assertFalse(coordinator.state.value.preparing)
    }

    @Test
    fun closeRetriesTheCapturedFinalCheckpointWhenItsWriterIsCancelled() = runBlocking {
        // An already durable position is deduplicated; exercise cancellation with a dirty clock.
        val restored = queues.snapshot()
        queues.saveCheckpoint(restored.generationId, restored.checkpointRevision, restored.checkpoint.copy(positionMs = 0L))
        coordinator.initialize()
        val gate = Gate(ignoreCancellation = false)
        queues.checkpointGate = gate
        val before = queues.completedWrites
        backend.seekTo(1234L)
        assertEquals(0L, queues.snapshot().positionMs)
        assertEquals(1234L, backend.currentPosition)
        coordinator.requestCheckpoint()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(gate.entered.isCompleted)
        coordinator.detach()
        backend.release()
        coordinator.close()

        assertTrue(gate.cancelled)
        assertEquals(before + 1, queues.completedWrites)
        assertEquals(1234L, queues.snapshot().positionMs)
        assertEquals(0, backend.lateCalls)
    }

    @Test
    fun neighborUriAndContentRevisionChangesReplaceTheirSources() = runBlocking {
        coordinator.initialize()
        for ((operation, changedEntry) in listOf(
            "uri" to entry(2L).copy(location = LocalLocationReference("location-2", "content://audio/new-2")),
            "revision" to entry(2L).copy(location = LocalLocationReference("location-2", "content://audio/new-2", contentRevision = 1L)),
        )) {
            val request = replacement(operation, queues.snapshot())
            backend.addedIds.clear()
            backend.removedIds.clear()
            coordinator.replace(request, source(request, changedEntry), explicitPlay = false)
            assertTrue("changed neighbor must receive a fresh source", "entry-2" in backend.addedIds)
            assertTrue("old source must leave the bounded window", "entry-2" in backend.removedIds)
            assertEquals("entry-1", backend.currentMediaItem?.mediaId)
            assertEquals(changedEntry.location?.uri, backend.getMediaItemAt(2).localConfiguration?.uri.toString())
            assertTrue(backend.mediaItemCount <= 3)
        }
    }

    @Test
    fun sessionCommandsAndListenersCannotMutateTheRawWindowOrModes() = runBlocking {
        val session = coordinator.sessionPlayer
        var commandEvents = 0
        var logicalRepeatEvents = 0
        session.addListener(object : Player.Listener {
            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                assertEquals(session.availableCommands, availableCommands)
                commandEvents += 1
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                assertEquals(Player.REPEAT_MODE_ALL, repeatMode)
                logicalRepeatEvents += 1
            }

            override fun onEvents(player: Player, events: Player.Events) {
                assertSame(session, player)
            }
        })
        coordinator.initialize()
        val before = backend.currentMediaItem
        session.setMediaItem(MediaItem.fromUri("content://audio/foreign"))
        session.clearMediaItems()
        session.repeatMode = Player.REPEAT_MODE_ONE
        session.shuffleModeEnabled = false
        assertEquals(before, backend.currentMediaItem)
        assertEquals(3, backend.mediaItemCount)
        assertEquals(Player.REPEAT_MODE_OFF, backend.repeatMode)
        assertFalse(backend.shuffleModeEnabled)
        assertEquals(Player.REPEAT_MODE_ALL, session.repeatMode)
        assertTrue(session.shuffleModeEnabled)
        assertFalse(session.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS))
        assertFalse(session.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE))
        assertTrue(session.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
        assertTrue(commandEvents > 0)
        assertEquals(1, logicalRepeatEvents)
    }

    @Test
    fun concurrentPublicActionsReportBusyExactlyOnce() = runBlocking {
        val failures = mutableListOf<QueuePlaybackIssue>()
        coordinator.detach()
        coordinator.close()
        coordinator = QueuePlaybackCoordinator(backend, queues, FixedPreferences(), scope,
            onFailure = { failures.add(it.issue) }, operations = IntentOnlyOperations(backend))
        coordinator.initialize()
        val gate = Gate(ignoreCancellation = true)
        queues.windowGate = gate
        val current = queues.snapshot()
        val first = scope.async { coordinator.select(current.generationId, current.queueRevision, "entry-2", false) }
        assertTrue(gate.entered.isCompleted)
        try {
            coordinator.select(current.generationId, current.queueRevision, "entry-0", true)
            throw AssertionError("concurrent selection must report BUSY")
        } catch (error: QueuePlaybackException) {
            assertEquals(QueuePlaybackIssue.BUSY, error.failure.issue)
        }
        gate.release.complete(Unit)
        first.join()
        assertEquals(listOf(QueuePlaybackIssue.BUSY), failures)
    }

    @Test
    fun aSavedQueueWithAnUnavailableCurrentEntryNeedsAnExplicitWindowRetry() = runBlocking {
        coordinator.initialize()
        val request = replacement("missing-current", queues.snapshot())
        val unavailable = entry(1L).copy(location = null)
        // Restoration can encounter a once-valid resource that has disappeared. Seed the durable
        // snapshot directly; a new coordinator replacement must reject it before publication.
        val committed = queues.replace(request, source(request, unavailable))
        coordinator.initialize()
        assertEquals(3L, committed.snapshot.entryCount)
        assertEquals(committed.snapshot.generationId, queues.snapshot().generationId)
        assertEquals(QueuePlaybackIssue.LOCATION_MISSING, coordinator.state.value.failure?.issue)
        assertEquals(0, backend.mediaItemCount)

        val replay = coordinator.replace(request, QueuePageSource { _, _ -> throw AssertionError("A replay cannot reconstruct its old source") }, true)
        assertTrue(replay.wasReplay)
        assertEquals(QueuePlaybackIssue.LOCATION_MISSING, coordinator.state.value.failure?.issue)
        assertFalse(backend.playWhenReady)
        assertEquals(0, backend.mediaItemCount)

        val current = queues.snapshot()
        coordinator.select(current.generationId, current.queueRevision, "entry-0", explicitPlay = true)
        assertEquals(3L, queues.snapshot().entryCount)
        assertEquals("entry-0", backend.currentMediaItem?.mediaId)
        assertTrue(backend.playWhenReady)
        assertEquals(null, coordinator.state.value.failure)
    }

    @Test
    fun resolvedCurrentIsFirstAndAFailedNeighborLeavesPlayableAudioInABoundedWindow() = runBlocking {
        val resolvedIds = mutableListOf<String>()
        val rounds = mutableListOf<String>()
        var revision = 1L
        coordinator.detach()
        coordinator.close()
        coordinator = QueuePlaybackCoordinator(backend, queues, FixedPreferences(), scope,
            resolveEntry = { original ->
                resolvedIds.add(original.queueEntryId)
                if (original.queueEntryId == "entry-2") throw QueuePlaybackException(
                    QueuePlaybackFailure(QueuePlaybackIssue.PERMISSION_DENIED, original.queueEntryId),
                )
                original.copy(location = requireNotNull(original.location).copy(
                    uri = "content://verified/" + original.queueEntryId + "/" + revision,
                    contentRevision = revision,
                ))
            },
            onRoundChanged = { current ->
                assertFalse("round identity must be ready before playback", backend.playWhenReady)
                rounds.add(current.queueEntryId)
            },
            operations = IntentOnlyOperations(backend),
        )
        coordinator.initialize()
        assertEquals(listOf("entry-1", "entry-0", "entry-2"), resolvedIds)
        assertEquals(2, backend.mediaItemCount)
        assertEquals("entry-1", backend.currentMediaItem?.mediaId)
        assertEquals("content://verified/entry-1/1", backend.currentMediaItem?.localConfiguration?.uri.toString())
        coordinator.sessionPlayer.play()
        coordinator.sessionPlayer.pause()
        coordinator.sessionPlayer.play()
        assertEquals(listOf("entry-1"), rounds)
        assertTrue(backend.playWhenReady)
        assertEquals(null, coordinator.state.value.failure)

        revision = 2L
        coordinator.initialize()
        assertEquals("content://verified/entry-1/2", backend.currentMediaItem?.localConfiguration?.uri.toString())
        assertEquals(2, backend.mediaItemCount)
        assertFalse(backend.playWhenReady)
    }

    @Test
    fun changedCurrentContentStopsItsOldSourceAndRequiresAnExplicitVerifiedRetry() = runBlocking {
        val resolvedIds = mutableListOf<String>()
        val rounds = mutableListOf<String>()
        var rejectCurrent = false
        coordinator.detach()
        coordinator.close()
        coordinator = QueuePlaybackCoordinator(backend, queues, FixedPreferences(), scope,
            resolveEntry = { current ->
                resolvedIds.add(current.queueEntryId)
                if (rejectCurrent) throw QueuePlaybackException(
                    QueuePlaybackFailure(QueuePlaybackIssue.CONTENT_UNKNOWN, current.queueEntryId),
                )
                current
            },
            onRoundChanged = { rounds.add(it.queueEntryId) },
            operations = IntentOnlyOperations(backend),
        )
        coordinator.initialize()
        resolvedIds.clear()
        rejectCurrent = true
        val active = queues.snapshot()
        coordinator.select(active.generationId, active.queueRevision, "entry-2", explicitPlay = true)
        assertEquals(listOf("entry-2"), resolvedIds)
        assertEquals(QueuePlaybackIssue.CONTENT_UNKNOWN, coordinator.state.value.failure?.issue)
        assertEquals(0, backend.mediaItemCount)
        assertFalse(backend.playWhenReady)
        assertEquals(3L, queues.snapshot().entryCount)
        assertEquals("entry-2", queues.snapshot().currentEntryId)
        assertEquals(listOf("entry-1"), rounds)

        rejectCurrent = false
        coordinator.sessionPlayer.play()
        assertEquals("entry-2", backend.currentMediaItem?.mediaId)
        assertTrue(backend.playWhenReady)
        assertEquals(listOf("entry-1", "entry-2"), rounds)
        assertEquals(null, coordinator.state.value.failure)
    }

    @Test
    fun pauseDuringAnUncancellableResolverStartsNoNeighborReadsOrLatePlayback() = runBlocking {
        val resolvedIds = mutableListOf<String>()
        var readGate: Gate? = null
        coordinator.detach()
        coordinator.close()
        coordinator = QueuePlaybackCoordinator(backend, queues, FixedPreferences(), scope,
            resolveEntry = { current ->
                resolvedIds.add(current.queueEntryId)
                readGate?.waitForRelease()
                current
            },
            operations = IntentOnlyOperations(backend),
        )
        coordinator.initialize()
        resolvedIds.clear()
        val gate = Gate(ignoreCancellation = true)
        readGate = gate
        val old = queues.snapshot()
        val operation = scope.async { coordinator.select(old.generationId, old.queueRevision, "entry-2", true) }
        assertTrue(gate.entered.isCompleted)
        coordinator.sessionPlayer.pause()
        gate.release.complete(Unit)
        operation.join()
        assertTrue(operation.isCancelled)
        assertEquals(listOf("entry-2"), resolvedIds)
        assertEquals("entry-1", backend.currentMediaItem?.mediaId)
        assertFalse(backend.playWhenReady)
        assertEquals(3L, queues.snapshot().entryCount)
        assertEquals("entry-2", queues.snapshot().currentEntryId)
    }

    @Test
    fun detachDuringALateRoundIdentityReadNeverPreparesTheReleasedBackend() = runBlocking {
        var roundGate: Gate? = null
        coordinator.detach()
        coordinator.close()
        coordinator = QueuePlaybackCoordinator(backend, queues, FixedPreferences(), scope,
            onRoundChanged = { roundGate?.waitForRelease() },
            operations = IntentOnlyOperations(backend),
        )
        coordinator.initialize()
        val gate = Gate(ignoreCancellation = true)
        roundGate = gate
        val old = queues.snapshot()
        val operation = scope.async { coordinator.select(old.generationId, old.queueRevision, "entry-2", true) }
        assertTrue(gate.entered.isCompleted)
        coordinator.detach()
        backend.release()
        gate.release.complete(Unit)
        operation.join()
        assertTrue(operation.isCancelled)
        assertEquals(0, backend.lateCalls)
        assertEquals("entry-2", queues.snapshot().currentEntryId)
    }

    @Test
    fun rejectedCurrentPreflightKeepsTheOldCompleteQueueWindowPositionAndPlayIntent() = runBlocking {
        var rejection: QueuePlaybackIssue? = null
        val reads = mutableListOf<String>()
        recreateCoordinator(resolveEntry = { current ->
            reads.add(current.queueEntryId)
            rejection?.let { throw QueuePlaybackException(QueuePlaybackFailure(it, current.queueEntryId)) }
            current
        })
        coordinator.initialize()
        coordinator.sessionPlayer.play()
        val old = queues.snapshot()
        val oldRows = queues.page(old.generationId, 0L, 100)
        val oldItems = (0 until backend.mediaItemCount).map { backend.getMediaItemAt(it) }
        val oldPosition = backend.currentPosition

        for (issue in listOf(QueuePlaybackIssue.PERMISSION_DENIED, QueuePlaybackIssue.CONTENT_UNKNOWN)) {
            reads.clear()
            rejection = issue
            val next = replacement("rejected-${issue.name}", old)
            try {
                coordinator.replace(next, source(next), explicitPlay = true)
                throw AssertionError("A rejected current resource cannot be published")
            } catch (error: QueuePlaybackException) {
                assertEquals(issue, error.failure.issue)
            }
            assertEquals(listOf("entry-1"), reads)
            assertEquals(old, queues.snapshot())
            assertEquals(old, coordinator.state.value.snapshot)
            assertEquals(oldRows, queues.page(old.generationId, 0L, 100))
            assertEquals(oldItems, (0 until backend.mediaItemCount).map { backend.getMediaItemAt(it) })
            assertEquals(oldPosition, backend.currentPosition)
            assertTrue(backend.playWhenReady)
            assertEquals(issue, coordinator.state.value.failure?.issue)
            assertFalse(coordinator.state.value.preparing)
        }
    }

    @Test
    fun pauseDuringUncancellableReplacementPreflightKeepsTheOldQueueAndStartsNoNeighbors() = runBlocking {
        val reads = mutableListOf<String>()
        var readGate: Gate? = null
        recreateCoordinator(resolveEntry = { current ->
            reads.add(current.queueEntryId)
            readGate?.waitForRelease()
            current
        })
        coordinator.initialize()
        coordinator.sessionPlayer.play()
        val old = queues.snapshot()
        val oldItems = (0 until backend.mediaItemCount).map { backend.getMediaItemAt(it) }
        reads.clear()
        val gate = Gate(ignoreCancellation = true)
        readGate = gate
        val next = replacement("cancelled-preflight", old)
        val operation = scope.async { coordinator.replace(next, source(next), explicitPlay = true) }
        assertTrue(gate.entered.isCompleted)
        assertEquals(old.generationId, queues.snapshot().generationId)
        assertTrue(backend.playWhenReady)
        coordinator.sessionPlayer.pause()
        gate.release.complete(Unit)
        operation.join()

        assertTrue(operation.isCancelled)
        assertEquals(listOf("entry-1"), reads)
        assertEquals(old.generationId, queues.snapshot().generationId)
        assertEquals(old.queueRevision, queues.snapshot().queueRevision)
        assertEquals(old.currentEntryId, queues.snapshot().currentEntryId)
        assertEquals(old.entryCount, queues.snapshot().entryCount)
        assertEquals(oldItems, (0 until backend.mediaItemCount).map { backend.getMediaItemAt(it) })
        assertEquals(1234L, backend.currentPosition)
        assertFalse(backend.playWhenReady)
        assertFalse(coordinator.state.value.preparing)
    }

    @Test
    fun acceptedReplacementReusesPreflightResourcesAndKeepsAFailedNeighborAsADurableRow() = runBlocking {
        var expectedGeneration: String? = null
        val reads = mutableListOf<String>()
        recreateCoordinator(resolveEntry = { current ->
            expectedGeneration?.let {
                assertEquals("The resource must be read before publication", it, queues.snapshot().generationId)
                reads.add(current.queueEntryId)
                if (current.queueEntryId == "entry-2") throw QueuePlaybackException(
                    QueuePlaybackFailure(QueuePlaybackIssue.PERMISSION_DENIED, current.queueEntryId),
                )
            }
            current.copy(location = requireNotNull(current.location).copy(uri = "content://verified/${current.queueEntryId}"))
        })
        coordinator.initialize()
        val old = queues.snapshot()
        expectedGeneration = old.generationId
        val previousWindowReads = queues.windowReads
        val next = replacement("preflight-once", old)
        val commit = coordinator.replace(next, source(next), explicitPlay = true)

        assertFalse(commit.wasReplay)
        assertEquals(listOf("entry-1", "entry-0", "entry-2"), reads)
        assertEquals(previousWindowReads, queues.windowReads)
        assertEquals(3L, commit.snapshot.entryCount)
        assertEquals(3, queues.page(commit.snapshot.generationId, 0L, 100).size)
        assertEquals(2, backend.mediaItemCount)
        assertEquals("entry-1", backend.currentMediaItem?.mediaId)
        assertEquals("content://verified/entry-1", backend.currentMediaItem?.localConfiguration?.uri.toString())
        assertTrue(backend.playWhenReady)
        assertEquals(null, coordinator.state.value.failure)
    }

    @Test
    fun historicalReplayAfterALaterQueueDoesNotResolveOrInstallTheHistoricalWindow() = runBlocking {
        var rejectReads = false
        recreateCoordinator(resolveEntry = { current ->
            if (rejectReads) throw AssertionError("A replay cannot read current or historical resources")
            current
        })
        coordinator.initialize()
        val firstRequest = replacement("historical", queues.snapshot())
        val first = coordinator.replace(firstRequest, source(firstRequest), explicitPlay = true)
        val laterRequest = replacement("later", queues.snapshot()).copy(startOriginalOrder = 0L)
        coordinator.replace(laterRequest, source(laterRequest), explicitPlay = false)
        val active = queues.snapshot()
        val items = (0 until backend.mediaItemCount).map { backend.getMediaItemAt(it) }
        val previousWindowReads = queues.windowReads
        rejectReads = true

        val replay = coordinator.replace(firstRequest,
            QueuePageSource { _, _ -> throw AssertionError("A replay cannot reconstruct its producer") },
            explicitPlay = true,
        )
        assertTrue(replay.wasReplay)
        assertEquals(first.appliedGenerationId, replay.appliedGenerationId)
        assertEquals(active, replay.snapshot)
        assertEquals(active, queues.snapshot())
        assertEquals(active, coordinator.state.value.snapshot)
        assertEquals(previousWindowReads, queues.windowReads)
        assertEquals(items, (0 until backend.mediaItemCount).map { backend.getMediaItemAt(it) })
        assertEquals("entry-0", backend.currentMediaItem?.mediaId)
        assertFalse(backend.playWhenReady)
    }

    @Test
    fun pauseAfterPublicationDuringRoundReadKeepsTheCompleteNewQueuePaused() = runBlocking {
        var roundGate: Gate? = null
        recreateCoordinator(onRoundChanged = { roundGate?.waitForRelease() })
        coordinator.initialize()
        coordinator.sessionPlayer.play()
        val old = queues.snapshot()
        val gate = Gate(ignoreCancellation = true)
        roundGate = gate
        val next = replacement("accepted-before-pause", old)
        val operation = scope.async { coordinator.replace(next, source(next), explicitPlay = true) }
        assertTrue(gate.entered.isCompleted)
        assertEquals("generation-accepted-before-pause", queues.snapshot().generationId)
        assertFalse(backend.playWhenReady)
        coordinator.sessionPlayer.pause()
        gate.release.complete(Unit)
        operation.join()

        assertEquals(3L, queues.snapshot().entryCount)
        assertEquals(old.queueRevision + 1L, queues.snapshot().queueRevision)
        assertEquals(queues.snapshot().generationId, coordinator.state.value.snapshot?.generationId)
        assertEquals(3, queues.page(queues.snapshot().generationId, 0L, 100).size)
        assertFalse(backend.playWhenReady)
        assertFalse(coordinator.state.value.preparing)
    }

    @Test
    fun asynchronousOutputFailureAfterAcceptanceRetainsTheSameCompleteSnapshotAndReceipt() = runBlocking {
        coordinator.initialize()
        val output = CompletableDeferred<Unit>()
        val outputEvent = scope.async {
            output.await()
            backend.emitError(PlaybackException("Controlled output failure", null, PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED))
        }
        val next = replacement("accepted-output-error", queues.snapshot())
        val commit = coordinator.replace(next, source(next), explicitPlay = true)
        assertTrue(backend.playWhenReady)
        output.complete(Unit)
        outputEvent.await()

        assertEquals(commit.appliedGenerationId, queues.snapshot().generationId)
        assertEquals(commit.snapshot.queueRevision, queues.snapshot().queueRevision)
        assertEquals(commit.snapshot.currentEntryId, queues.snapshot().currentEntryId)
        assertEquals(3L, queues.snapshot().entryCount)
        assertEquals(3, queues.page(commit.appliedGenerationId, 0L, 100).size)
        assertEquals(QueuePlaybackIssue.OUTPUT_ERROR, coordinator.state.value.failure?.issue)
        assertFalse(backend.playWhenReady)
        val replay = coordinator.replace(next,
            QueuePageSource { _, _ -> throw AssertionError("Runtime failure must not rebuild an accepted queue") }, false)
        assertTrue(replay.wasReplay)
        assertEquals(commit.appliedGenerationId, replay.appliedGenerationId)
        assertEquals(QueuePlaybackIssue.OUTPUT_ERROR, coordinator.state.value.failure?.issue)
    }

    @Test
    fun failedBackendWindowInstallationKeepsTheAcceptedQueueAndExplicitRetryCannotReplayTheOldUri() = runBlocking {
        coordinator.initialize()
        coordinator.sessionPlayer.play()
        val oldUri = backend.currentMediaItem?.localConfiguration?.uri.toString()
        val changed = entry(1L).copy(location = LocalLocationReference("location-1", "content://audio/replaced-1"))
        val next = replacement("accepted-window-error", queues.snapshot())
        backend.failNextWindowInstallation = true
        val commit = coordinator.replace(next, source(next, changed), explicitPlay = true)

        assertFalse(commit.wasReplay)
        assertEquals(commit.appliedGenerationId, queues.snapshot().generationId)
        assertEquals(3L, queues.snapshot().entryCount)
        assertEquals(QueuePlaybackIssue.OUTPUT_ERROR, coordinator.state.value.failure?.issue)
        assertEquals(0, backend.mediaItemCount)
        assertFalse(backend.playWhenReady)
        val replay = coordinator.replace(next,
            QueuePageSource { _, _ -> throw AssertionError("Accepted output failure cannot reconstruct its producer") }, true)
        assertTrue(replay.wasReplay)
        assertEquals(0, backend.mediaItemCount)
        assertEquals(QueuePlaybackIssue.OUTPUT_ERROR, coordinator.state.value.failure?.issue)

        coordinator.sessionPlayer.play()
        assertEquals(commit.appliedGenerationId, queues.snapshot().generationId)
        assertEquals(commit.snapshot.queueRevision, queues.snapshot().queueRevision)
        assertEquals("entry-1", backend.currentMediaItem?.mediaId)
        assertEquals("content://audio/replaced-1", backend.currentMediaItem?.localConfiguration?.uri.toString())
        assertNotEquals(oldUri, backend.currentMediaItem?.localConfiguration?.uri.toString())
        assertTrue(backend.playWhenReady)
        assertEquals(null, coordinator.state.value.failure)
    }

    private suspend fun recreateCoordinator(
        resolveEntry: suspend (QueueEntry) -> QueueEntry = { it },
        onRoundChanged: suspend (QueueEntry) -> Unit = {},
    ) {
        coordinator.detach()
        coordinator.close()
        coordinator = QueuePlaybackCoordinator(backend, queues, FixedPreferences(), scope,
            resolveEntry = resolveEntry, onRoundChanged = onRoundChanged, operations = IntentOnlyOperations(backend))
    }

    private fun replacement(operation: String, base: QueueSnapshot) = QueueReplacementRequest(
        operation,
        base.generationId,
        base.queueRevision,
        FrozenQueueView("view-$operation", 1L, 3L),
        mode = base.mode,
        startOriginalOrder = 1L,
    )

    private fun source(request: QueueReplacementRequest, changed: QueueEntry? = null) = QueuePageSource { offset, limit ->
        QueueInputPage(request.view, offset, List(limit) { index ->
            val order = offset + index
            if (changed != null && changed.originalOrder == order) changed else entry(order)
        })
    }

    private fun entry(order: Long) = QueueEntry(
        "entry-$order",
        TrackKey(TrackKey.LOCAL_PROVIDER, "track-$order"),
        order,
        order,
        displayName = "Audio $order",
        location = LocalLocationReference("location-$order", "content://audio/$order"),
    )

    private class FixedPreferences : UserPreferencesRepository {
        override val state = MutableStateFlow<PreferencesState>(PreferencesState.Ready(UserPreferences()))
        override suspend fun save(expectedRevision: Long, draft: UserPreferencesDraft): PreferencesWriteResult = error("No settings mutation in coordinator tests")
        override suspend fun reset(expectedRevision: Long, confirmation: ResetConfirmation): PreferencesWriteResult = error("No settings reset in coordinator tests")
        override fun retryRead() = Unit
    }

    private class Gate(private val ignoreCancellation: Boolean) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var cancelled = false

        suspend fun waitForRelease() {
            entered.complete(Unit)
            try {
                if (ignoreCancellation) withContext(NonCancellable) { release.await() } else release.await()
            } catch (error: CancellationException) {
                cancelled = true
                throw error
            }
        }
    }

    private class ControlledQueueStore(private var entries: List<QueueEntry>) : QueueStore {
        private val active = MutableStateFlow<QueueSnapshot?>(
            QueueSnapshot("generation-old", 1L, 0L, FrozenQueueView("view-old", 1L, entries.size.toLong()),
                QueueMode(shuffle = true, repeat = QueueRepeatMode.LIST), QueueCheckpoint("entry-1", 1234L), 1L, 1L),
        )
        var commitGate: Gate? = null
        var windowGate: Gate? = null
        var checkpointGate: Gate? = null
        var completedWrites = 0
        var windowReads = 0
        private val published = mutableMapOf<String, Pair<QueueReplacementRequest, String>>()

        override suspend fun initialize(): QueueSnapshot = snapshot()
        override suspend fun snapshot(): QueueSnapshot = active.value ?: error("Initialized queue required")
        override fun snapshots(): Flow<QueueSnapshot?> = active

        override suspend fun mutate(
            request: QueueMutationRequest,
            source: QueuePageSource?,
            beforePublish: suspend (QueueMutationCandidate) -> QueueCheckpoint?,
        ): QueueCommit = throw AssertionError("This intent-only store covers replacement/checkpoints; mutation integration uses real Room in QueueCoordinatorIntegrationTest")

        override suspend fun replace(
            request: QueueReplacementRequest,
            source: QueuePageSource,
            beforePublish: suspend (QueueWindow?) -> Unit,
        ): QueueCommit {
            published[request.operationId]?.let { (original, generation) ->
                if (request != original) throw QueueStoreException(QueueStoreIssue.INVALID_INPUT)
                return QueueCommit(snapshot(), generation, true)
            }
            val staged = if (request.view.totalCount == 0L) emptyList() else source.read(0L, request.view.totalCount.toInt()).entries
            val stagedCurrent = staged.singleOrNull { it.originalOrder == request.startOriginalOrder }
            val selected = stagedCurrent?.let { current ->
                staged.filter { it.playbackOrder in current.playbackOrder - 1L..current.playbackOrder + 1L }
                    .sortedBy { it.playbackOrder }
            }
            val candidate = if (stagedCurrent == null) null else QueueWindow(
                "generation-${request.operationId}", request.expectedQueueRevision + 1L,
                request.view.totalCount, requireNotNull(selected), requireNotNull(selected).indexOf(stagedCurrent),
            )
            beforePublish(candidate)
            currentCoroutineContext().ensureActive()
            return withContext(NonCancellable) {
                commitGate.also { commitGate = null }?.waitForRelease()
                val old = snapshot()
                if (old.generationId != request.expectedGenerationId || old.queueRevision != request.expectedQueueRevision) {
                    throw QueueStoreException(QueueStoreIssue.CONFLICT)
                }
                entries = staged
                val current = stagedCurrent
                val next = QueueSnapshot("generation-${request.operationId}", old.queueRevision + 1L, 0L,
                    request.view, request.mode, QueueCheckpoint(current?.queueEntryId, request.initialPositionMs),
                    current?.originalOrder, current?.playbackOrder)
                active.value = next
                published[request.operationId] = request to next.generationId
                QueueCommit(next, next.generationId, false)
            }
        }

        override suspend fun page(generationId: String, firstPlaybackOrder: Long, limit: Int): List<QueueEntry> {
            if (snapshot().generationId != generationId) throw QueueStoreException(QueueStoreIssue.CONFLICT)
            return entries.filter { it.playbackOrder >= firstPlaybackOrder }.sortedBy { it.playbackOrder }.take(limit)
        }

        override suspend fun window(generationId: String, currentEntryId: String): QueueWindow {
            windowReads += 1
            val current = entries.single { it.queueEntryId == currentEntryId }
            val selected = entries.filter { it.playbackOrder in current.playbackOrder - 1L..current.playbackOrder + 1L }
            val before = snapshot()
            if (before.generationId != generationId) throw QueueStoreException(QueueStoreIssue.CONFLICT)
            windowGate.also { windowGate = null }?.waitForRelease()
            return QueueWindow(generationId, before.queueRevision, before.entryCount, selected, selected.indexOf(current))
        }

        override suspend fun saveCheckpoint(generationId: String, expectedCheckpointRevision: Long, checkpoint: QueueCheckpoint): QueueSnapshot {
            checkpointGate.also { checkpointGate = null }?.waitForRelease()
            val before = snapshot()
            if (before.generationId != generationId || before.checkpointRevision != expectedCheckpointRevision) {
                throw QueueStoreException(QueueStoreIssue.CONFLICT)
            }
            val current = entries.single { it.queueEntryId == checkpoint.queueEntryId }
            val next = before.copy(checkpointRevision = before.checkpointRevision + 1L, checkpoint = checkpoint,
                currentOriginalOrder = current.originalOrder, currentPlaybackOrder = current.playbackOrder)
            active.value = next
            completedWrites += 1
            return next
        }
    }

    /** 委托真实后端；销毁后的任何协调器 getter/mutation 都立即计数并失败。 */
    private class ReleaseGuard(private val delegate: ExoPlayer) : ExoPlayer by delegate {
        var preparedForIntentTest = false
        var released = false
            private set
        var lateCalls = 0
            private set
        val addedIds = mutableListOf<String>()
        val removedIds = mutableListOf<String>()
        var failNextWindowInstallation = false
        private val listeners = mutableListOf<Player.Listener>()

        fun emitError(error: PlaybackException) = live {
            check(Looper.myLooper() == Looper.getMainLooper())
            listeners.toList().forEach { it.onPlayerError(error) }
        }

        private fun <T> live(block: () -> T): T {
            if (released) {
                lateCalls += 1
                throw AssertionError("Backend accessed after release")
            }
            return block()
        }

        override fun getApplicationLooper(): Looper = live { delegate.applicationLooper }
        override fun getAvailableCommands(): Player.Commands = live { delegate.availableCommands }
        override fun getRepeatMode(): Int = live { delegate.repeatMode }
        override fun getShuffleModeEnabled(): Boolean = live { delegate.shuffleModeEnabled }
        override fun getPlayWhenReady(): Boolean = live { delegate.playWhenReady }
        override fun getPlaybackState(): Int = live { if (preparedForIntentTest && delegate.currentMediaItem != null) Player.STATE_READY else delegate.playbackState }
        override fun getPlayerError(): ExoPlaybackException? = live { delegate.playerError }
        override fun getCurrentMediaItem(): MediaItem? = live { delegate.currentMediaItem }
        override fun getCurrentMediaItemIndex(): Int = live { delegate.currentMediaItemIndex }
        override fun getMediaItemCount(): Int = live { delegate.mediaItemCount }
        override fun getMediaItemAt(index: Int): MediaItem = live { delegate.getMediaItemAt(index) }
        override fun getCurrentPosition(): Long = live { delegate.currentPosition }
        override fun getDuration(): Long = live { delegate.duration }
        override fun getSeekBackIncrement(): Long = live { delegate.seekBackIncrement }
        override fun getSeekForwardIncrement(): Long = live { delegate.seekForwardIncrement }
        override fun isPlaying(): Boolean = live { delegate.isPlaying }
        override fun isCommandAvailable(command: Int): Boolean = live { delegate.isCommandAvailable(command) }
        override fun play() = live { delegate.play() }
        override fun pause() = live { delegate.pause() }
        override fun prepare() = live { delegate.prepare() }
        override fun clearMediaItems() = live { delegate.clearMediaItems() }
        override fun seekTo(positionMs: Long) = live { delegate.seekTo(positionMs) }
        override fun seekTo(mediaItemIndex: Int, positionMs: Long) = live { delegate.seekTo(mediaItemIndex, positionMs) }
        override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) = live {
            if (failNextWindowInstallation) {
                failNextWindowInstallation = false
                throw IllegalStateException("Controlled backend window failure before mutation")
            }
            delegate.setMediaItems(mediaItems, startIndex, startPositionMs)
        }
        override fun addMediaItem(index: Int, mediaItem: MediaItem) = live {
            addedIds.add(mediaItem.mediaId)
            delegate.addMediaItem(index, mediaItem)
        }
        override fun removeMediaItem(index: Int) = live {
            removedIds.add(delegate.getMediaItemAt(index).mediaId)
            delegate.removeMediaItem(index)
        }
        override fun moveMediaItem(currentIndex: Int, newIndex: Int) = live { delegate.moveMediaItem(currentIndex, newIndex) }
        override fun addListener(listener: Player.Listener) = live { listeners.add(listener); delegate.addListener(listener) }
        override fun removeListener(listener: Player.Listener) = live { listeners.remove(listener); delegate.removeListener(listener) }
        override fun release() {
            check(!released)
            released = true
            delegate.release()
        }
    }

    /** 此 stub 只使旧意图/释放测试不等待不存在的音频；实际后端 await 由独立测试验证。 */
    private class IntentOnlyOperations(override val player: ReleaseGuard) : PlaybackBackendOperations {
        override fun setPauseAtBoundary(pause: Boolean) = player.setPauseAtEndOfMediaItems(pause)
        override suspend fun prepareAndAwait(expectedMediaId: String) {
            assertEquals(expectedMediaId, player.currentMediaItem?.mediaId)
            player.prepare()
            player.preparedForIntentTest = true
        }
        override suspend fun seekAndAwait(expectedMediaId: String, positionMs: Long) {
            assertEquals(expectedMediaId, player.currentMediaItem?.mediaId)
            player.seekTo(positionMs)
        }
    }
}
