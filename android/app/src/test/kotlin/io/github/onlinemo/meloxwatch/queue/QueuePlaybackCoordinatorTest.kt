package io.github.onlinemo.meloxwatch.queue

import android.os.Looper
import androidx.media3.common.MediaItem
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
import io.github.onlinemo.meloxwatch.model.QueueRepeatMode
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import io.github.onlinemo.meloxwatch.model.QueueWindow
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.model.UserPreferences
import io.github.onlinemo.meloxwatch.model.UserPreferencesDraft
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackCoordinator
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackException
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/**
 * 真正的主 Looper ExoPlayer，DB gate 只控制 IO 时序；不把这些断言当音频/设备验收。
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
        coordinator = QueuePlaybackCoordinator(backend, queues, FixedPreferences(), scope)
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
        coordinator.initialize()
        val gate = Gate(ignoreCancellation = false)
        queues.checkpointGate = gate
        val before = queues.completedWrites
        coordinator.requestCheckpoint()
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
        coordinator = QueuePlaybackCoordinator(backend, queues, FixedPreferences(), scope) { failures.add(it.issue) }
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
        val committed = coordinator.replace(request, source(request, unavailable), explicitPlay = true)
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
        private val published = mutableMapOf<String, Pair<QueueReplacementRequest, String>>()

        override suspend fun initialize(): QueueSnapshot = snapshot()
        override suspend fun snapshot(): QueueSnapshot = active.value ?: error("Initialized queue required")
        override fun snapshots(): Flow<QueueSnapshot?> = active

        override suspend fun replace(request: QueueReplacementRequest, source: QueuePageSource): QueueCommit {
            published[request.operationId]?.let { (original, generation) ->
                if (request != original) throw QueueStoreException(QueueStoreIssue.INVALID_INPUT)
                return QueueCommit(snapshot(), generation, true)
            }
            val staged = source.read(0L, request.view.totalCount.toInt()).entries
            return withContext(NonCancellable) {
                commitGate.also { commitGate = null }?.waitForRelease()
                val old = snapshot()
                if (old.generationId != request.expectedGenerationId || old.queueRevision != request.expectedQueueRevision) {
                    throw QueueStoreException(QueueStoreIssue.CONFLICT)
                }
                entries = staged
                val current = entries.single { it.originalOrder == request.startOriginalOrder }
                val next = QueueSnapshot("generation-${request.operationId}", old.queueRevision + 1L, 0L,
                    request.view, request.mode, QueueCheckpoint(current.queueEntryId, request.initialPositionMs),
                    current.originalOrder, current.playbackOrder)
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
        var released = false
            private set
        var lateCalls = 0
            private set
        val addedIds = mutableListOf<String>()
        val removedIds = mutableListOf<String>()

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
        override fun getPlaybackState(): Int = live { delegate.playbackState }
        override fun getPlayerError(): PlaybackException? = live { delegate.playerError }
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
        override fun addListener(listener: Player.Listener) = live { delegate.addListener(listener) }
        override fun removeListener(listener: Player.Listener) = live { delegate.removeListener(listener) }
        override fun release() {
            check(!released)
            released = true
            delegate.release()
        }
    }
}
