package io.github.onlinemo.meloxwatch.playback.resources

import android.content.Context
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
import io.github.onlinemo.meloxwatch.data.online.OnlineCatalogQueueLease
import io.github.onlinemo.meloxwatch.data.online.OnlineQueueDirectory
import io.github.onlinemo.meloxwatch.data.online.cache.OnlineCatalogRowEntity
import io.github.onlinemo.meloxwatch.data.queue.QueuePageSource
import io.github.onlinemo.meloxwatch.data.queue.QueueReplacementRequest
import io.github.onlinemo.meloxwatch.data.queue.RoomQueueStore
import io.github.onlinemo.meloxwatch.model.FrozenQueueView
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueMode
import io.github.onlinemo.meloxwatch.model.QueueMutation
import io.github.onlinemo.meloxwatch.model.QueueMutationRequest
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.model.UserPreferences
import io.github.onlinemo.meloxwatch.model.UserPreferencesDraft
import io.github.onlinemo.meloxwatch.playback.PlaybackBackendOperations
import io.github.onlinemo.meloxwatch.playback.OnlineQueueAction
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackCoordinator
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackException
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackFailure
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

/** Real durable Room rows and a controlled Player; no decoder, sound or device acceptance is inferred. */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class OnlineQueueResourceIntegrationTest {
    private lateinit var database: MeloXDatabase
    private lateinit var store: RoomQueueStore
    private lateinit var scope: CoroutineScope
    private lateinit var coordinator: QueuePlaybackCoordinator
    private lateinit var backend: WindowPlayer
    private val resources = RuntimePlaybackResources()
    private val resolvedIds = mutableListOf<String>()
    private val resolvedQualities = mutableListOf<StreamingQuality>()
    private var metadataReads = 0
    private var accountCurrent = true
    private var failOnline = false
    private var onlineCreations = 0
    private val preferences = FixedPreferences()

    @Before fun open() {
        OkHttp.initialize(RuntimeEnvironment.getApplication())
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication() as Context, MeloXDatabase::class.java).build()
        store = RoomQueueStore(database)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        backend = WindowPlayer()
        val online = OnlinePlaybackResolver { entry, budget, quality ->
            resolvedIds.add(entry.trackKey.trackId)
            resolvedQualities.add(quality)
            if (failOnline) throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.NETWORK_UNAVAILABLE, entry.queueEntryId))
            onlineResource(resources, entry, budget, current = { accountCurrent })
        }
        coordinator = QueuePlaybackCoordinator(backend, store, preferences, scope,
            resolveEntry = { it }, operations = WindowOperations(backend),
            sourceResolver = SourcePlaybackResolver(local = { it }, online = {
                onlineCreations++
                online
            }), resolveMetadata = { entry ->
                if (entry.trackKey.providerId == "netease") {
                    metadataReads++
                    MediaMetadata.Builder().setTitle("Fixture catalog title").setArtist("Fixture cache artist").build()
                } else null
            })
    }

    @After fun close() {
        try { onMain { coordinator.close() } }
        finally {
            scope.cancel()
            backend.release()
            database.close()
        }
    }

    @Test fun theCompleteOnlineQueueIsDurableButOnlyItsSelectedCurrentItemIsResolved() = onMain {
        val entries = List(6) { onlineEntry((201 + it).toString(), it.toLong()) }
        val view = FrozenQueueView("online-view", 1L, entries.size.toLong())
        val request = QueueReplacementRequest("online-replace", null, 0L, view, startOriginalOrder = 2L)
        val commit = coordinator.replace(request, source(view, entries), true, PlaybackSourceIntent.ONLINE_ALLOWED)
        assertEquals(listOf("203"), resolvedIds)
        assertEquals(1, backend.mediaItemCount)
        assertEquals("meloxstream", backend.currentMediaItem?.localConfiguration?.uri?.scheme)
        val rows = store.page(commit.snapshot.generationId, 0L)
        assertEquals(6, rows.size)
        assertTrue(rows.all { it.location == null })
        assertEquals(6L, store.snapshot()?.entryCount)
    }

    @Test fun coldOnlineRestoreOnlyShowsAPausedReferenceAndNeverCreatesAProviderOrRequestsAUrl() = onMain {
        val entries = List(4) { onlineEntry((201 + it).toString(), it.toLong()) }
        val view = FrozenQueueView("restored-online-view", 1L, 4L)
        val saved = store.replace(QueueReplacementRequest("seed-online", null, 0L, view), source(view, entries)).snapshot
        coordinator.initialize()
        assertEquals(saved.generationId, store.snapshot()?.generationId)
        assertEquals(4L, store.snapshot()?.entryCount)
        assertEquals(0, onlineCreations)
        assertTrue(resolvedIds.isEmpty())
        assertFalse(backend.isPlaying)
        assertNull(backend.currentMediaItem?.localConfiguration)
    }

    @Test fun anAccountInvalidationStopsTheMatchingOnlineTraversalAndPreservesItsFullQueue() = onMain {
        val entries = List(4) { onlineEntry((201 + it).toString(), it.toLong()) }
        val view = FrozenQueueView("online-view", 1L, 4L)
        val before = coordinator.replace(QueueReplacementRequest("start-online", null, 0L, view),
            source(view, entries), true, PlaybackSourceIntent.ONLINE_ALLOWED).snapshot
        assertTrue(backend.isPlaying)
        accountCurrent = false
        resources.invalidateStaleAccounts()
        coordinator.onAccountStateChanged()
        assertFalse(backend.isPlaying)
        assertEquals(0, backend.mediaItemCount)
        assertEquals(before.generationId, store.snapshot()?.generationId)
        assertEquals(4L, store.snapshot()?.entryCount)
        assertEquals(QueuePlaybackIssue.LOGIN_REQUIRED, coordinator.state.value.failure?.issue)
    }

    @Test fun accountInvalidationLeavesLocalPlaybackAndItsQueueUntouched() = onMain {
        val entries = listOf(localEntry(0L), localEntry(1L))
        val view = FrozenQueueView("local-view", 1L, 2L)
        val before = coordinator.replace(QueueReplacementRequest("start-local", null, 0L, view), source(view, entries), true).snapshot
        assertTrue(backend.isPlaying)
        accountCurrent = false
        resources.invalidateStaleAccounts()
        coordinator.onAccountStateChanged()
        assertTrue(backend.isPlaying)
        assertEquals(before.generationId, store.snapshot()?.generationId)
        assertEquals(before.currentEntryId, backend.currentMediaItem?.mediaId)
        assertEquals(0, onlineCreations)
    }

    @Test fun aFailedOnlinePreflightPreservesThePreviouslyPlayingLocalQueue() = onMain {
        val local = listOf(localEntry(0L))
        val oldView = FrozenQueueView("local-view", 1L, 1L)
        val before = coordinator.replace(QueueReplacementRequest("start-local", null, 0L, oldView), source(oldView, local), true).snapshot
        failOnline = true
        val entries = List(4) { onlineEntry((201 + it).toString(), it.toLong()) }
        val view = FrozenQueueView("failed-online-view", 1L, 4L)
        var error: QueuePlaybackException? = null
        try {
            coordinator.replace(QueueReplacementRequest("failed-online", before.generationId, before.queueRevision, view),
                source(view, entries), true, PlaybackSourceIntent.ONLINE_ALLOWED)
        } catch (failure: QueuePlaybackException) { error = failure }
        assertEquals(QueuePlaybackIssue.NETWORK_UNAVAILABLE, requireNotNull(error).failure.issue)
        assertEquals(before.generationId, store.snapshot()?.generationId)
        assertEquals(before.currentEntryId, backend.currentMediaItem?.mediaId)
        assertTrue(backend.isPlaying)
        assertEquals("content", backend.currentMediaItem?.localConfiguration?.uri?.scheme)
    }

    @Test fun anOnlineNeighborOfALocalCurrentItemRemainsDurableWithoutAnyNetworkPreflight() = onMain {
        val entries = listOf(localEntry(0L), onlineEntry("201", 1L), localEntry(2L))
        val view = FrozenQueueView("mixed-view", 1L, 3L)
        val saved = coordinator.replace(QueueReplacementRequest("start-local-mixed", null, 0L, view), source(view, entries), true).snapshot
        assertEquals(3L, saved.entryCount)
        assertEquals(1, backend.mediaItemCount)
        assertTrue(resolvedIds.isEmpty())
        assertEquals(0, onlineCreations)
        assertTrue(backend.isPlaying)
    }

    @Test fun aNonzeroPausedRestoreKeepsItsSessionClockAndCheckpointThroughPreparePauseFlushAndClose() = onMain {
        val saved = seedPausedOnline()
        coordinator.initialize()
        assertEquals(2_700L, coordinator.sessionPlayer.currentPosition)
        assertEquals(saved.currentEntryId, coordinator.sessionPlayer.currentMediaItem?.mediaId)
        assertEquals(0, backend.mediaItemCount)
        coordinator.sessionPlayer.prepare()
        coordinator.sessionPlayer.pause()
        coordinator.flush()
        assertEquals(2_700L, store.snapshot()?.positionMs)
        assertEquals(0, onlineCreations)
        assertTrue(resolvedIds.isEmpty())
        coordinator.close()
        assertEquals(2_700L, store.snapshot()?.positionMs)
    }

    @Test fun disabledHistoryRestorationSelectsZeroBeforeTheFirstExplicitOnlinePlay() = onMain {
        preferences.state.value = PreferencesState.Ready(UserPreferences(restorePosition = false))
        seedPausedOnline()
        coordinator.initialize()
        assertEquals(0L, coordinator.sessionPlayer.currentPosition)
        assertEquals(0, onlineCreations)
        coordinator.sessionPlayer.play()
        backend.played.await()
        coordinator.state.first { !it.preparing }
        coordinator.flush()
        assertEquals(0L, backend.currentPosition)
        assertEquals(0L, store.snapshot()?.positionMs)
        assertEquals(listOf("201"), resolvedIds)
    }

    @Test fun explicitPlayResolvesOnlyTheCurrentItemAndSeeksToTheSelectedNonzeroRestoredPosition() = onMain {
        seedPausedOnline()
        coordinator.initialize()
        coordinator.sessionPlayer.play()
        backend.played.await()
        coordinator.flush()
        assertEquals(2_700L, backend.currentPosition)
        assertEquals(2_700L, store.snapshot()?.positionMs)
        assertEquals(listOf("201"), resolvedIds)
        assertEquals("meloxstream", backend.currentMediaItem?.localConfiguration?.uri?.scheme)
    }

    @Test fun switchingAnOnlinePausedReferenceRestoresTheLogicalPositionWithoutCreatingAProvider() = onMain {
        seedPausedOnline()
        coordinator.initialize()
        val checkpoint = coordinator.freezeForBackendSwitch()
        coordinator.unbindBackendForSwitch(checkpoint)
        backend.release()
        val replacement = WindowPlayer()
        backend = replacement
        coordinator.restoreAfterBackendSwitch(replacement, WindowOperations(replacement), checkpoint)
        coordinator.flush()
        assertEquals(2_700L, checkpoint.positionMs)
        assertEquals(2_700L, coordinator.sessionPlayer.currentPosition)
        assertEquals(2_700L, store.snapshot()?.positionMs)
        assertEquals(0, replacement.mediaItemCount)
        assertFalse(replacement.isPlaying)
        assertEquals(0, onlineCreations)
    }

    @Test fun failedExplicitResolutionRetainsThePausedReferenceAndItsDurablePosition() = onMain {
        seedPausedOnline()
        coordinator.initialize()
        failOnline = true
        coordinator.sessionPlayer.play()
        coordinator.state.first { it.failure?.issue == QueuePlaybackIssue.NETWORK_UNAVAILABLE }
        coordinator.flush()
        assertEquals(2_700L, coordinator.sessionPlayer.currentPosition)
        assertEquals(2_700L, store.snapshot()?.positionMs)
        assertEquals(0, backend.mediaItemCount)
        assertEquals(listOf("201"), resolvedIds)
    }

    @Test fun revokingAPlayingAccountPreservesItsLatestPositionInThePausedReference() = onMain {
        seedPausedOnline()
        coordinator.initialize()
        coordinator.sessionPlayer.play()
        backend.played.await()
        backend.seekTo(5_800L)
        backend.positionAdvanceOnPause = 123L
        accountCurrent = false
        resources.invalidateStaleAccounts()
        coordinator.onAccountStateChanged()
        coordinator.flush()
        assertEquals(5_923L, coordinator.sessionPlayer.currentPosition)
        assertEquals(5_923L, store.snapshot()?.positionMs)
        assertFalse(backend.isPlaying)
        assertEquals(QueuePlaybackIssue.LOGIN_REQUIRED, coordinator.state.value.failure?.issue)
        assertEquals(listOf("201"), resolvedIds)
    }

    @Test fun aPureQueueModeEditKeepsThePausedClockAndPerformsNoOnlinePreparation() = onMain {
        seedPausedOnline()
        coordinator.initialize()
        val before = requireNotNull(store.snapshot())
        coordinator.mutate(QueueMutationRequest("paused-mode-edit", before.generationId, before.queueRevision,
            before.currentEntryId, QueueMutation.SetMode(QueueMode(shuffle = true))))
        coordinator.flush()
        assertEquals(2_700L, coordinator.sessionPlayer.currentPosition)
        assertEquals(2_700L, store.snapshot()?.positionMs)
        assertEquals(0, onlineCreations)
    }

    @Test fun anExplicitOnlineInsertionFromALocalIntentUsesTheChosenQualityAndChangesTheCurrentItem() = onMain {
        val local = listOf(localEntry(0L))
        val localView = FrozenQueueView("local-insertion-start", 1L, 1L)
        val before = coordinator.replace(QueueReplacementRequest("local-insertion-seed", null, 0L, localView), source(localView, local), true).snapshot
        val inserted = listOf(onlineEntry("201", 0L))
        val insertedView = FrozenQueueView("online-insertion-source", 1L, 1L)
        val commit = coordinator.mutate(QueueMutationRequest("online-insertion", before.generationId, before.queueRevision,
            before.currentEntryId, QueueMutation.InsertNext(insertedView, before.currentEntryId, startInserted = true)),
            source(insertedView, inserted), explicitPlay = true, sourceIntent = PlaybackSourceIntent.ONLINE_ALLOWED,
            streamingQuality = StreamingQuality.HIGH)
        assertEquals(2L, commit.snapshot.entryCount)
        assertTrue(before.currentEntryId != commit.snapshot.currentEntryId)
        assertEquals(listOf("201"), resolvedIds)
        assertEquals(listOf(StreamingQuality.HIGH), resolvedQualities)
        assertTrue(backend.isPlaying)
    }

    @Test fun cachedMetadataIsSharedByTheBackendAndFacadeButIsNotReadDuringColdRestore() = onMain {
        seedPausedOnline()
        coordinator.initialize()
        assertEquals(0, metadataReads)
        coordinator.sessionPlayer.play()
        backend.played.await()
        assertEquals(1, metadataReads)
        assertEquals("Fixture cache artist", backend.mediaMetadata.artist)
        assertEquals("Fixture cache artist", coordinator.sessionPlayer.mediaMetadata.artist)
    }

    @Test fun anOnlineBackendSwitchRetainsTheSharedMetadataWithoutAnotherCatalogRead() = onMain {
        seedPausedOnline()
        coordinator.initialize()
        coordinator.sessionPlayer.play()
        backend.played.await()
        val checkpoint = coordinator.freezeForBackendSwitch()
        coordinator.unbindBackendForSwitch(checkpoint)
        backend.release()
        val replacement = WindowPlayer()
        backend = replacement
        coordinator.restoreAfterBackendSwitch(replacement, WindowOperations(replacement), checkpoint)
        assertEquals("Fixture cache artist", coordinator.sessionPlayer.mediaMetadata.artist)
        assertEquals(1, metadataReads)
        assertEquals(listOf("201"), resolvedIds)
    }

    @Test fun replayingAnAcceptedOnlineTicketAcknowledgesItWithoutRestoringAnOlderQueue() = onMain {
        val directory = OnlineQueueDirectory()
        val view = FrozenQueueView("directory-online-view", 1L, 4L)
        val lease = OnlineCatalogQueueLease(view, "101", { true }, { first, limit ->
            (first until minOf(4L, first + limit)).map { order -> OnlineCatalogRowEntity(view.id, order, "TRACK", (201L + order).toString(), "Fixture song") }
        }, {})
        val ticket = directory.register(lease, UUID.randomUUID().toString(), 2L, OnlineQueueAction.REPLACE_COMPLETE_LIST, null, StreamingQuality.STANDARD)
        try {
            directory.claim(ticket.command) {}.use { it.commit(coordinator) }
            val local = listOf(localEntry(0L))
            val localView = FrozenQueueView("after-online-receipt", 1L, 1L)
            val current = requireNotNull(store.snapshot())
            val newer = coordinator.replace(QueueReplacementRequest("newer-local-after-receipt", current.generationId, current.queueRevision, localView),
                source(localView, local), true).snapshot
            val resolutions = resolvedIds.toList()
            assertTrue(directory.wasAccepted(ticket.command))
            assertEquals(newer.generationId, store.snapshot()?.generationId)
            assertEquals(newer.currentEntryId, backend.currentMediaItem?.mediaId)
            assertEquals(resolutions, resolvedIds)
        } finally { ticket.close() }
    }

    private suspend fun seedPausedOnline(): io.github.onlinemo.meloxwatch.model.QueueSnapshot {
        val entries = List(4) { onlineEntry((201 + it).toString(), it.toLong()) }
        val view = FrozenQueueView("nonzero-online", 1L, 4L)
        return store.replace(QueueReplacementRequest("seed-nonzero-online", null, 0L, view, initialPositionMs = 2_700L),
            source(view, entries)).snapshot
    }

    private fun source(view: FrozenQueueView, entries: List<QueueEntry>) = QueuePageSource { offset, limit ->
        QueueInputPage(view, offset, entries.drop(offset.toInt()).take(limit))
    }
    private fun localEntry(order: Long) = QueueEntry("local-$order", TrackKey("local", "local-track-$order"), order, order,
        "Fixture local", 10_000L, LocalLocationReference("location-$order", "content://fixture/local-$order"))

    private fun <T> onMain(action: suspend () -> T): T {
        val task = scope.async(Dispatchers.Main.immediate) { action() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4L)
        while (!task.isCompleted && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!task.isCompleted) Thread.sleep(1L)
        }
        if (!task.isCompleted) {
            task.cancel()
            throw AssertionError("Queue operation did not complete within the controlled test deadline")
        }
        return runBlocking { task.await() }
    }

    private class FixedPreferences : UserPreferencesRepository {
        override val state = MutableStateFlow<PreferencesState>(PreferencesState.Ready(UserPreferences()))
        override suspend fun save(expectedRevision: Long, draft: UserPreferencesDraft): PreferencesWriteResult = error("No preference write")
        override suspend fun reset(expectedRevision: Long, confirmation: ResetConfirmation): PreferencesWriteResult = error("No reset")
        override fun retryRead() = Unit
    }

    private class WindowOperations(override val player: WindowPlayer) : PlaybackBackendOperations {
        override fun setPauseAtBoundary(pause: Boolean) { assertTrue(pause) }
        override suspend fun prepareAndAwait(expectedMediaId: String) {
            assertEquals(expectedMediaId, player.currentMediaItem?.mediaId)
            player.prepare()
        }
        override suspend fun seekAndAwait(expectedMediaId: String, positionMs: Long) {
            assertEquals(expectedMediaId, player.currentMediaItem?.mediaId)
            player.seekTo(positionMs)
        }
    }

    /** A bounded timeline and acknowledged commands supply only the state needed by these ownership tests. */
    private class WindowPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        val played = CompletableDeferred<Unit>()
        var positionAdvanceOnPause = 0L
        private data class Item(val uid: String, val media: MediaItem)
        private val items = mutableListOf<Item>()
        private var uid = 0L
        private var index = C.INDEX_UNSET
        private var position = 0L
        private var ready = false
        private var play = false
        private var closed = false
        override fun getState(): State = State.Builder().setAvailableCommands(if (closed) Player.Commands.EMPTY else COMMANDS)
            .setPlaylist(items.map { MediaItemData.Builder(it.uid).setMediaItem(it.media).setDurationUs(10_000_000L).setIsSeekable(true).build() })
            .setCurrentMediaItemIndex(index).setPlaybackState(if (ready && items.isNotEmpty()) Player.STATE_READY else Player.STATE_IDLE)
            .setPlayWhenReady(play, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(PositionSupplier { position }).setContentBufferedPositionMs(PositionSupplier { position })
            .setTotalBufferedDurationMs(PositionSupplier.ZERO).build()
        override fun getPlaceholderState(suggestedPlaceholderState: State): State = getState()
        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            if (play && !playWhenReady) position += positionAdvanceOnPause
            play = playWhenReady
            if (playWhenReady) played.complete(Unit)
            return immediate()
        }
        override fun handlePrepare(): ListenableFuture<*> {
            ready = true
            return immediate()
        }
        override fun handleStop(): ListenableFuture<*> {
            play = false
            ready = false
            return immediate()
        }
        override fun handleRelease(): ListenableFuture<*> {
            closed = true
            play = false
            return immediate()
        }
        override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
            check(mediaItems.size <= 3)
            items.clear()
            items.addAll(mediaItems.map { Item("fixture-${uid++}", it) })
            index = if (items.isEmpty()) C.INDEX_UNSET else if (startIndex == C.INDEX_UNSET) 0 else startIndex
            position = if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs
            ready = false
            return immediate()
        }
        override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
            check(items.size + mediaItems.size <= 3)
            val current = items.getOrNull(this.index)?.uid
            items.addAll(index, mediaItems.map { Item("fixture-${uid++}", it) })
            this.index = current?.let { key -> items.indexOfFirst { it.uid == key } } ?: 0
            return immediate()
        }
        override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
            val current = items.getOrNull(index)?.uid
            val selected = items.subList(fromIndex, toIndex).toList()
            items.subList(fromIndex, toIndex).clear()
            items.addAll(newIndex, selected)
            index = current?.let { key -> items.indexOfFirst { it.uid == key } } ?: C.INDEX_UNSET
            return immediate()
        }
        override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
            val current = items.getOrNull(index)?.uid
            items.subList(fromIndex, toIndex).clear()
            index = current?.let { key -> items.indexOfFirst { it.uid == key } } ?: C.INDEX_UNSET
            if (index < 0) {
                index = if (items.isEmpty()) C.INDEX_UNSET else 0
                ready = false
            }
            return immediate()
        }
        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            index = mediaItemIndex
            position = if (positionMs == C.TIME_UNSET) 0L else positionMs.coerceAtLeast(0L)
            return immediate()
        }
        private fun immediate(): ListenableFuture<Unit> = Futures.immediateFuture(Unit)
        companion object {
            private val COMMANDS = Player.Commands.Builder().addAll(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE,
                Player.COMMAND_STOP, Player.COMMAND_RELEASE, Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS,
                Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD).build()
        }
    }
}
