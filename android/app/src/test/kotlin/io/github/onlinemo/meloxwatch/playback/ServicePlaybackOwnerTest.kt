package io.github.onlinemo.meloxwatch.playback

import android.app.Application
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import io.github.onlinemo.meloxwatch.data.listening.ListeningRepository
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesState
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesWriteResult
import io.github.onlinemo.meloxwatch.data.preferences.ResetConfirmation
import io.github.onlinemo.meloxwatch.data.preferences.UserPreferencesRepository
import io.github.onlinemo.meloxwatch.data.queue.QueueCommit
import io.github.onlinemo.meloxwatch.data.queue.QueuePageSource
import io.github.onlinemo.meloxwatch.data.queue.QueueReplacementRequest
import io.github.onlinemo.meloxwatch.data.queue.QueueStore
import io.github.onlinemo.meloxwatch.data.queue.QueueStoreException
import io.github.onlinemo.meloxwatch.data.queue.QueueStoreIssue
import io.github.onlinemo.meloxwatch.model.PlaybackBackendKind
import io.github.onlinemo.meloxwatch.model.QueueCheckpoint
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueMutationCandidate
import io.github.onlinemo.meloxwatch.model.QueueMutationRequest
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import io.github.onlinemo.meloxwatch.model.QueueWindow
import io.github.onlinemo.meloxwatch.model.UserPreferences
import io.github.onlinemo.meloxwatch.model.UserPreferencesDraft
import io.github.onlinemo.meloxwatch.playback.route.MediaVolumeSetRequest
import io.github.onlinemo.meloxwatch.playback.route.MediaVolumeSnapshot
import io.github.onlinemo.meloxwatch.playback.route.PlaybackRouteSessionState
import io.github.onlinemo.meloxwatch.playback.route.ServiceMediaVolumeIssue
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Real owner/queue coroutines with gated empty-queue I/O; these tests do not prove decoded audio. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ServicePlaybackOwnerTest {
    private lateinit var preferences: MutablePreferences
    private lateinit var store: EmptyQueueStore
    private lateinit var scope: CoroutineScope
    private lateinit var owner: ServicePlaybackOwner
    private lateinit var statistics: ServiceListeningObserver
    private lateinit var queues: QueuePlaybackCoordinator
    private lateinit var musicVolume: RecordingMusicVolume
    private val issues = mutableListOf<PlaybackBackendIssue>()
    private val queueFailures = mutableListOf<QueuePlaybackFailure>()

    @Before fun createOwner() {
        val application: Application = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(application).grantPermissions(application.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        preferences = MutablePreferences()
        store = EmptyQueueStore()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        musicVolume = RecordingMusicVolume()
        owner = ServicePlaybackOwner(application, scope, preferences, LocalProviderIo(),
            ensureForeground = { throw AssertionError("An empty-queue switch must remain paused") },
            onFailure = issues::add,
            musicVolumePort = musicVolume,
        )
        val noListeningCalls = Proxy.newProxyInstance(ListeningRepository::class.java.classLoader,
            arrayOf(ListeningRepository::class.java)) { _, method, _ ->
            throw AssertionError("An empty queue must not access listening storage: ${method.name}")
        } as ListeningRepository
        statistics = ServiceListeningObserver(owner.initialPlayer, noListeningCalls, scope) {
            throw AssertionError("An empty queue must not fail listening storage: $it")
        }
        queues = QueuePlaybackCoordinator(owner.initialPlayer, store, preferences, scope,
            operations = owner.initialOperations,
            onRoundChanged = statistics::beginRound,
            requestPlay = owner::requestPlay,
            onUserPause = owner::userPause,
            onUserIntentChanged = owner::userIntentChanged,
            onFailure = queueFailures::add,
        )
        owner.attach(queues, statistics)
        runBlocking {
            owner.initializePreferences()
            queues.initialize()
        }
    }

    @After fun releaseOwner() = runBlocking {
        store.releaseGates()
        try { statistics.detach() }
        finally {
            try { queues.detach() }
            finally {
                try {
                    owner.close()
                    idleMain()
                }
                finally {
                    scope.cancel()
                    try { queues.close() } finally { statistics.close() }
                }
            }
        }
    }

    @Test fun aNewSavedBackendDuringExplicitRetryWaitsForRestoreAndIsThenApplied() = runBlocking {
        requestSystemWithAFailedFreeze()
        val session = queues.sessionPlayer
        owner.observePreferences()
        val before = store.snapshotCalls
        val gate = store.blockSnapshot(skip = 1)
        val retry = scope.async { owner.retryBackendIfNeeded() }
        idleMain()
        assertTrue(gate.entered.isCompleted)
        assertEquals(PlaybackBackendKind.SYSTEM_MEDIA_PLAYER, owner.state.value.actualBackend)
        assertTrue(owner.state.value.switching)

        preferences.publish(PlaybackBackendKind.MEDIA3)
        idleMain()
        assertEquals(PlaybackBackendKind.MEDIA3, preferences.current.playbackBackend)
        assertEquals(PlaybackBackendKind.SYSTEM_MEDIA_PLAYER, owner.currentPreferences.playbackBackend)
        assertEquals(PlaybackBackendKind.SYSTEM_MEDIA_PLAYER, owner.state.value.requestedBackend)
        assertTrue("a second caller cannot clear the first switch's busy state", owner.state.value.switching)
        assertTrue(issues.isEmpty())
        assertTrue(queueFailures.isEmpty())
        assertFalse(retry.isCompleted)

        gate.release.complete(Unit)
        idleMain()
        assertTrue(retry.await())
        idleMain()
        assertPausedBackend(PlaybackBackendKind.MEDIA3, session)
        assertEquals("one retry and one latest preference application each freeze and restore once", before + 4, store.snapshotCalls)
        assertTrue(issues.isEmpty())
        assertTrue(queueFailures.isEmpty())
    }

    @Test fun aRetryWaitingForThePreferenceSwitchRechecksTheAlreadyHealthyBackend() = runBlocking {
        val session = queues.sessionPlayer
        owner.observePreferences()
        val gate = store.blockSnapshot()
        preferences.publish(PlaybackBackendKind.SYSTEM_MEDIA_PLAYER)
        idleMain()
        assertTrue(gate.entered.isCompleted)
        assertEquals(PlaybackBackendKind.MEDIA3, owner.state.value.actualBackend)
        assertTrue(owner.state.value.switching)
        val callsWhileFrozen = store.snapshotCalls

        val retry = scope.async { owner.retryBackendIfNeeded() }
        assertFalse("retry must wait for the owning switch before inspecting backend health", retry.isCompleted)
        assertEquals(callsWhileFrozen, store.snapshotCalls)
        gate.release.complete(Unit)
        idleMain()

        assertFalse("the completed preference switch needs no second rebuild", retry.await())
        assertEquals(callsWhileFrozen + 1, store.snapshotCalls)
        assertPausedBackend(PlaybackBackendKind.SYSTEM_MEDIA_PLAYER, session)
        assertTrue(issues.isEmpty())
        assertTrue(queueFailures.isEmpty())
    }

    @Test fun cancellingAnInFlightRetryReleasesTheTransactionForTheLatestPreference() = runBlocking {
        requestSystemWithAFailedFreeze()
        val session = queues.sessionPlayer
        owner.observePreferences()
        val gate = store.blockSnapshot(skip = 1)
        val retry = scope.async { owner.retryBackendIfNeeded() }
        idleMain()
        assertTrue(gate.entered.isCompleted)
        assertEquals(PlaybackBackendKind.SYSTEM_MEDIA_PLAYER, owner.state.value.actualBackend)
        preferences.publish(PlaybackBackendKind.MEDIA3)
        idleMain()
        assertTrue(owner.state.value.switching)

        retry.cancel()
        idleMain()
        retry.join()
        idleMain()
        assertTrue(retry.isCancelled)
        assertTrue("cancellation reaches the suspended restore I/O", gate.cancelled)
        assertFalse("the obsolete restore was not unblocked by a successful I/O result", gate.release.isCompleted)
        assertPausedBackend(PlaybackBackendKind.MEDIA3, session)
        val generation = owner.state.value.generation
        assertFalse(owner.retryBackendIfNeeded())
        assertEquals(generation, owner.state.value.generation)
    }

    @Test fun sessionObservationsUseTheQueueBindingEpochInitiallyAndAfterTwoBackendSwitches() = runBlocking {
        val session = queues.sessionPlayer
        val ownerGeneration = owner.state.value.generation
        var previousBinding = queues.sessionBackendGeneration()
        assertTrue(ownerGeneration != previousBinding)
        assertSessionBinding(PlaybackBackendKind.MEDIA3, previousBinding)

        for (kind in listOf(PlaybackBackendKind.SYSTEM_MEDIA_PLAYER, PlaybackBackendKind.MEDIA3)) {
            preferences.publish(kind)
            val applying = scope.async { owner.initializePreferences() }
            idleMain()
            applying.await()
            val binding = queues.sessionBackendGeneration()
            assertTrue(binding > previousBinding)
            assertSessionBinding(kind, binding)
            assertPausedBackend(kind, session)
            previousBinding = binding
        }
        assertTrue("the focus owner retains its independent epoch", owner.state.value.generation != previousBinding)
        assertTrue(issues.isEmpty())
        assertTrue(queueFailures.isEmpty())
    }

    @Test
    fun realFactoriesKeepTheOperationsPlayerAndPausedRouteNamespaceConsistent() = runBlocking {
        val kinds = listOf(
            PlaybackBackendKind.MEDIA3,
            PlaybackBackendKind.SYSTEM_MEDIA_PLAYER,
            PlaybackBackendKind.MEDIA3,
        )
        for (kind in kinds) {
            preferences.publish(kind)
            val applying = scope.async { owner.initializePreferences() }
            idleMain()
            applying.await()
            assertSame(owner.initialPlayer, owner.initialOperations.player)
            if (kind == PlaybackBackendKind.MEDIA3) {
                assertTrue(owner.initialPlayer is RoutingPlaybackPlayer)
            } else {
                assertTrue(owner.initialPlayer is SystemMediaPlayerAdapter)
            }
            val extras = owner.sessionExtras()
            assertEquals(kind, checkNotNull(PlaybackBackendState.fromSessionExtras(extras)).actualBackend)
            assertEquals(PlaybackRouteSessionState(), PlaybackRouteSessionState.fromSessionExtras(extras))
            assertEquals(3, extras.keySet().count { it.startsWith("io.github.onlinemo.meloxwatch.route.") })
            assertEquals(PlaybackRouteSessionState(), owner.routeState.value)
            assertFalse(queues.sessionPlayer.playWhenReady)
        }
    }

    @Test
    fun pausedRelativeVolumeReadsTheCurrentStreamAndNeverRequestsPlayback() {
        val before = checkNotNull(owner.readMediaVolume().snapshot)
        assertFalse(before.route.known)
        musicVolume.values = musicVolume.values.copy(current = 9)
        val result = owner.adjustMediaVolume(1)
        assertTrue(result.accepted && result.changed && result.writeAttempted)
        assertEquals(10, checkNotNull(result.snapshot).values.current)
        assertEquals(listOf(10), musicVolume.writes)
        assertFalse(checkNotNull(result.snapshot).route.known)
        assertFalse(owner.initialPlayer.playWhenReady)
        assertFalse(queues.sessionPlayer.playWhenReady)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun aForgedKnownRouteCannotCommitAnAbsoluteValueOnThePausedBackend() {
        val expected = MediaVolumeSnapshot(
            musicVolume.values,
            PlaybackRouteSessionState(UUID.randomUUID().toString(), true),
        )
        val result = owner.setMediaVolume(MediaVolumeSetRequest(expected, 7))
        assertEquals(ServiceMediaVolumeIssue.OUTPUT_CHANGED, result.issue)
        assertFalse(result.accepted || result.writeAttempted)
        assertFalse(checkNotNull(result.snapshot).route.known)
        assertTrue(musicVolume.writes.isEmpty())
        assertFalse(queues.sessionPlayer.playWhenReady)
    }

    @Test
    fun relativeVolumeCompletesWhileTheBackendSwitchIsWaitingForQueueIO() = runBlocking {
        val gate = store.blockSnapshot()
        preferences.publish(PlaybackBackendKind.SYSTEM_MEDIA_PLAYER)
        val applying = scope.async { owner.initializePreferences() }
        idleMain()
        assertTrue(gate.entered.isCompleted)
        assertTrue(owner.state.value.switching)
        val result = owner.adjustMediaVolume(-1)
        assertTrue(result.accepted && result.changed)
        assertEquals(3, checkNotNull(result.snapshot).values.current)
        assertFalse(checkNotNull(result.snapshot).route.known)
        assertFalse(applying.isCompleted)
        assertFalse(queues.sessionPlayer.playWhenReady)
        gate.release.complete(Unit)
        idleMain()
        applying.await()
        assertPausedBackend(PlaybackBackendKind.SYSTEM_MEDIA_PLAYER, queues.sessionPlayer)
    }

    @Test
    fun aClosedOwnerLeavesAllVolumeCommandsUnavailableWithoutPlatformAccess() {
        val expected = MediaVolumeSnapshot(
            musicVolume.values,
            PlaybackRouteSessionState(UUID.randomUUID().toString(), true),
        )
        val reads = musicVolume.reads
        owner.close()
        val results = listOf(
            owner.readMediaVolume(),
            owner.adjustMediaVolume(1),
            owner.setMediaVolume(MediaVolumeSetRequest(expected, 5)),
        )
        for (result in results) {
            assertEquals(ServiceMediaVolumeIssue.UNAVAILABLE, result.issue)
            assertNull(result.snapshot)
            assertFalse(result.accepted || result.changed || result.writeAttempted)
        }
        assertEquals(reads, musicVolume.reads)
        assertTrue(musicVolume.writes.isEmpty())
        assertEquals(PlaybackRouteSessionState(), owner.routeState.value)
    }

    @Test
    fun volumeEntryPointsRejectTheWrongThreadBeforeAnyStreamQuery() = runBlocking {
        val reads = musicVolume.reads
        try {
            withContext(Dispatchers.Default) { owner.readMediaVolume() }
            throw AssertionError("A Service volume command requires its owner Looper")
        } catch (_: IllegalStateException) {
            assertEquals(reads, musicVolume.reads)
            assertTrue(musicVolume.writes.isEmpty())
        }
    }

    private fun assertSessionBinding(kind: PlaybackBackendKind, expectedGeneration: Long) {
        val observation = checkNotNull(PlaybackBackendState.fromSessionExtras(owner.sessionExtras()))
        assertEquals(kind, observation.actualBackend)
        assertEquals(expectedGeneration, observation.generation)
    }

    private suspend fun requestSystemWithAFailedFreeze() {
        preferences.publish(PlaybackBackendKind.SYSTEM_MEDIA_PLAYER)
        store.failNextSnapshot = true
        owner.initializePreferences()
        assertEquals(PlaybackBackendKind.SYSTEM_MEDIA_PLAYER, owner.state.value.requestedBackend)
        assertEquals(PlaybackBackendKind.MEDIA3, owner.state.value.actualBackend)
        assertEquals(PlaybackBackendIssue.SWITCH_FAILED, owner.state.value.issue)
        assertFalse(owner.state.value.switching)
        issues.clear()
        queueFailures.clear()
    }

    private fun assertPausedBackend(kind: PlaybackBackendKind, originalSession: Player) {
        assertEquals(kind, owner.currentPreferences.playbackBackend)
        assertEquals(kind, owner.state.value.requestedBackend)
        assertEquals(kind, owner.state.value.actualBackend)
        assertFalse(owner.state.value.switching)
        assertNull(owner.state.value.issue)
        assertNull(queues.state.value.failure)
        assertSame(originalSession, queues.sessionPlayer)
        assertFalse(queues.sessionPlayer.playWhenReady)
        assertEquals(1, store.maxConcurrentSnapshots)
    }

    private fun idleMain() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    private class MutablePreferences : UserPreferencesRepository {
        private val values = MutableStateFlow<PreferencesState>(PreferencesState.Ready(UserPreferences()))
        override val state = values.asStateFlow()
        val current: UserPreferences get() = (values.value as PreferencesState.Ready).preferences
        fun publish(kind: PlaybackBackendKind) {
            values.value = PreferencesState.Ready(current.copy(revision = current.revision + 1L, playbackBackend = kind))
        }
        override suspend fun save(expectedRevision: Long, draft: UserPreferencesDraft): PreferencesWriteResult =
            throw AssertionError("The playback owner cannot write preferences")
        override suspend fun reset(expectedRevision: Long, confirmation: ResetConfirmation): PreferencesWriteResult =
            throw AssertionError("The playback owner cannot reset preferences")
        override fun retryRead() { throw AssertionError("The playback owner cannot retry preference I/O implicitly") }
    }

    private class Gate(var skip: Int) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var cancelled = false
            private set
        suspend fun await() {
            entered.complete(Unit)
            try { release.await() }
            catch (error: CancellationException) {
                cancelled = true
                throw error
            }
        }
    }

    private class EmptyQueueStore : QueueStore {
        private val changes = MutableStateFlow<QueueSnapshot?>(null)
        private val gates = mutableListOf<Gate>()
        private var nextGate: Gate? = null
        private var activeSnapshots = 0
        var failNextSnapshot = false
        var snapshotCalls = 0
            private set
        var maxConcurrentSnapshots = 0
            private set
        fun blockSnapshot(skip: Int = 0): Gate {
            check(nextGate == null && skip >= 0)
            val gate = Gate(skip)
            gates += gate
            nextGate = gate
            return gate
        }
        fun releaseGates() = gates.forEach { it.release.complete(Unit) }
        override suspend fun initialize(): QueueSnapshot? = null
        override fun snapshots(): Flow<QueueSnapshot?> = changes
        override suspend fun snapshot(): QueueSnapshot? {
            snapshotCalls += 1
            activeSnapshots += 1
            maxConcurrentSnapshots = maxOf(maxConcurrentSnapshots, activeSnapshots)
            try {
                if (failNextSnapshot) {
                    failNextSnapshot = false
                    throw QueueStoreException(QueueStoreIssue.STORAGE_UNAVAILABLE)
                }
                val gate = nextGate
                if (gate != null) {
                    if (gate.skip > 0) {
                        gate.skip -= 1
                    } else {
                        nextGate = null
                        gate.await()
                    }
                }
                return null
            } finally { activeSnapshots -= 1 }
        }
        override suspend fun replace(request: QueueReplacementRequest, source: QueuePageSource,
            beforePublish: suspend (QueueWindow?) -> Unit): QueueCommit = throw AssertionError("Unexpected queue replacement")
        override suspend fun mutate(request: QueueMutationRequest, source: QueuePageSource?,
            beforePublish: suspend (QueueMutationCandidate) -> QueueCheckpoint?): QueueCommit = throw AssertionError("Unexpected queue mutation")
        override suspend fun page(generationId: String, firstPlaybackOrder: Long, limit: Int): List<QueueEntry> =
            throw AssertionError("An empty queue has no page")
        override suspend fun window(generationId: String, currentEntryId: String): QueueWindow =
            throw AssertionError("An empty queue has no window")
        override suspend fun saveCheckpoint(generationId: String, expectedCheckpointRevision: Long,
            checkpoint: QueueCheckpoint): QueueSnapshot = throw AssertionError("An empty queue has no checkpoint")
    }
}
