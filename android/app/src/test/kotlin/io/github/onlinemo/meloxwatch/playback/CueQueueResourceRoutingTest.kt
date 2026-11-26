package io.github.onlinemo.meloxwatch.playback

import android.app.Application
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.github.onlinemo.meloxwatch.data.cue.RoomCueLibraryFixture
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesState
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesWriteResult
import io.github.onlinemo.meloxwatch.data.preferences.ResetConfirmation
import io.github.onlinemo.meloxwatch.data.preferences.UserPreferencesRepository
import io.github.onlinemo.meloxwatch.data.queue.QueueInputPage
import io.github.onlinemo.meloxwatch.data.queue.QueuePageSource
import io.github.onlinemo.meloxwatch.data.queue.QueueReplacementRequest
import io.github.onlinemo.meloxwatch.data.queue.RoomQueueStore
import io.github.onlinemo.meloxwatch.model.CueFileBinding
import io.github.onlinemo.meloxwatch.model.FrozenQueueView
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueMode
import io.github.onlinemo.meloxwatch.model.QueueMutation
import io.github.onlinemo.meloxwatch.model.QueueMutationRequest
import io.github.onlinemo.meloxwatch.model.QueueRepeatMode
import io.github.onlinemo.meloxwatch.model.UserPreferences
import io.github.onlinemo.meloxwatch.model.UserPreferencesDraft
import io.github.onlinemo.meloxwatch.playback.resources.CuePcmInputFactory
import io.github.onlinemo.meloxwatch.playback.resources.CueSeekableInput
import io.github.onlinemo.meloxwatch.playback.resources.PlaybackDataSources
import io.github.onlinemo.meloxwatch.playback.resources.RuntimePlaybackResources
import io.github.onlinemo.meloxwatch.playback.resources.SourcePlaybackResolver
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.SQLiteMode

/** 真实Room队列和共享PCM工厂；原生prepare/seek仅核控制意图，不授解码、出声或设备信用。 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CueQueueResourceRoutingTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var room: RoomCueLibraryFixture
    private lateinit var queues: RoomQueueStore
    private lateinit var scope: CoroutineScope
    private lateinit var sources: PlaybackDataSources
    private lateinit var backend: ExoPlayer
    private lateinit var coordinator: QueuePlaybackCoordinator
    private lateinit var second: QueueEntry
    private lateinit var ordinary: QueueEntry
    private val registry = RuntimePlaybackResources()
    private var directReads = 0
    private var cueResolverReads = 0
    private var onlineCreations = 0

    @Before fun open() = runBlocking {
        val application = RuntimeEnvironment.getApplication() as Application
        room = RoomCueLibraryFixture(application, File(temporary.root, "queue-cue-routing.db"))
        room.open()
        val staged = room.stage()
        room.accept(staged)
        room.finish(staged)
        val segment = room.segments()[1]
        val reference = checkNotNull(segment.cueReference)
        second = QueueEntry("queue-cue-second", segment.trackKey, 0L, 0L, segment.title,
            checkNotNull(reference.pcmWindow).durationUs / 1000L,
            checkNotNull(reference.binding).audio.location, cueReference = reference)
        val whole = room.tracks().single { it.cueReference == null }
        ordinary = QueueEntry("queue-cue-whole", whole.trackKey, 0L, 0L, whole.title,
            whole.metadata.durationMs, checkNotNull(whole.location))
        queues = RoomQueueStore(room.database)
        queues.initialize()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        sources = PlaybackDataSources(application, registry, controlledCueInputs = FixtureInputs(room.wave))
        backend = ExoPlayer.Builder(application).setLooper(Looper.getMainLooper())
            .setUsePlatformDiagnostics(false).setHandleAudioBecomingNoisy(false)
            .setMediaSourceFactory(DefaultMediaSourceFactory(sources)).build()
        coordinator = createCoordinator(withResolver = true)
    }

    @After fun close() {
        var cleanupFailure: Throwable? = null
        fun recordCleanup(error: Throwable) {
            val first = cleanupFailure
            if (first == null) cleanupFailure = error
            else if (first !== error) first.addSuppressed(error)
        }
        // 初始化可能在Room阶段失败；只清理已建立的owner，各清理失败保留在首个错误上。
        runCatching {
            if (::scope.isInitialized) onMain {
                runCatching { if (::coordinator.isInitialized) coordinator.detach() }
                    .exceptionOrNull()?.let(::recordCleanup)
                runCatching { if (::backend.isInitialized) backend.release() }
                    .exceptionOrNull()?.let(::recordCleanup)
                runCatching { if (::coordinator.isInitialized) coordinator.close() }
                    .exceptionOrNull()?.let(::recordCleanup)
                runCatching { if (::sources.isInitialized) sources.close() }
                    .exceptionOrNull()?.let(::recordCleanup)
                runCatching {
                    if (::sources.isInitialized) withTimeout(5000L) { sources.awaitCueRetirement() }
                }.exceptionOrNull()?.let(::recordCleanup)
            }
        }.exceptionOrNull()?.let(::recordCleanup)
        runCatching { if (::scope.isInitialized) scope.cancel() }
            .exceptionOrNull()?.let(::recordCleanup)
        runCatching { if (::room.isInitialized) room.close() }
            .exceptionOrNull()?.let(::recordCleanup)
        cleanupFailure?.let { throw it }
    }

    @Test fun realQueuePreparationRegistersTheSecondSegmentAndKeptEditsRetainItsExactRuntimeUri() = onMain {
        withTimeout(20_000L) {
            val first = replace(second)
            val media = checkNotNull(backend.currentMediaItem)
            val uri = checkNotNull(media.localConfiguration).uri
            assertEquals(RuntimePlaybackResources.CUE_SCHEME, uri.scheme)
            assertEquals(0, directReads)
            assertEquals(1, cueResolverReads)
            assertEquals(0, onlineCreations)
            assertSame(backend, coordinator.sessionBackend())
            assertFalse(backend.playWhenReady)
            withContext(Dispatchers.IO) { assertSecondSegment(media, sources) }

            val reference = registry.acquireCue(uri)
            try {
                assertEquals(second.cueReference, reference.reference)
                assertEquals(2, reference.references)
            } finally { registry.releaseCue(reference) }
            val edited = coordinator.mutate(QueueMutationRequest("keep-cue-runtime", first.generationId,
                first.queueRevision, first.currentEntryId, QueueMutation.SetMode(QueueMode(repeat = QueueRepeatMode.LIST))))
            assertEquals(second.queueEntryId, edited.snapshot.currentEntryId)
            assertEquals(uri, checkNotNull(backend.currentMediaItem?.localConfiguration).uri)
            assertEquals(1, cueResolverReads)
            assertEquals(1, directReads)
            assertEquals(0, onlineCreations)
            assertSame(backend, coordinator.sessionBackend())
            assertFalse(backend.playWhenReady)
            // 首次原生读取已close，编辑后重开旧URI仍须归同一实际token和第二段输入。
            val retained = registry.acquireCue(uri)
            try {
                assertSame(reference, retained)
                assertEquals(2, retained.references)
            } finally { registry.releaseCue(retained) }
            val keptMedia = checkNotNull(backend.currentMediaItem)
            withContext(Dispatchers.IO) { assertSecondSegment(keptMedia, sources) }
        }
    }

    @Test fun ordinaryLocalQueuePreparationKeepsItsDirectReadAndCreatesNoCueOrOnlineResolver() = onMain {
        withTimeout(20_000L) {
            replace(ordinary)
            assertEquals(ordinary.location?.uri, backend.currentMediaItem?.localConfiguration?.uri.toString())
            assertEquals("content", backend.currentMediaItem?.localConfiguration?.uri?.scheme)
            assertEquals(1, directReads)
            assertEquals(0, cueResolverReads)
            assertEquals(0, onlineCreations)
            assertSame(backend, coordinator.sessionBackend())
            assertFalse(backend.playWhenReady)
            assertNull(coordinator.state.value.failure)
        }
    }

    @Test fun aCueWithoutTheRuntimeResolverCannotPublishABareContentWindow() = onMain {
        withTimeout(20_000L) {
            coordinator.detach()
            coordinator.close()
            coordinator = createCoordinator(withResolver = false)
            val request = request(second)
            val failure = try {
                coordinator.replace(request, source(request, second), explicitPlay = false)
                throw AssertionError("CUE published without a runtime resolver")
            } catch (failure: QueuePlaybackException) { failure }
            assertEquals(QueuePlaybackIssue.SOURCE_UNSUPPORTED, failure.failure.issue)
            assertNull(queues.snapshot())
            assertEquals(0, backend.mediaItemCount)
            assertEquals(0, directReads)
            assertEquals(0, cueResolverReads)
            assertEquals(0, onlineCreations)
            assertSame(backend, coordinator.sessionBackend())
        }
    }

    /** Room恢复须返回真实Main；只驱动当前Looper任务，不推进合成时间。 */
    private fun onMain(block: suspend () -> Unit) = runBlocking {
        val pending = scope.async { block() }
        try {
            withTimeout(20_000L) {
                while (!pending.isCompleted) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    delay(1L)
                }
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                pending.await()
            }
        } finally { if (!pending.isCompleted) pending.cancel() }
    }

    private fun createCoordinator(withResolver: Boolean): QueuePlaybackCoordinator {
        val resolver = if (!withResolver) null else SourcePlaybackResolver(local = { entry ->
            cueResolverReads++
            validated(entry)
        }, resources = registry, online = {
            onlineCreations++
            error("Local queue attempted online creation")
        })
        return QueuePlaybackCoordinator(backend, queues, FixedPreferences(), scope,
            resolveEntry = { entry ->
                directReads++
                validated(entry)
            },
            sourceResolver = resolver, checkCueBinding = { entry ->
                room.library.resolveCueRead(entry.trackKey, entry.cueReference)
                Unit
            }, operations = IntentOnlyOperations(backend))
    }

    private suspend fun validated(entry: QueueEntry): QueueEntry {
        if (entry.cueReference != null) {
            val read = room.library.resolveCueRead(entry.trackKey, entry.cueReference)
            assertEquals(entry.cueReference, read.reference)
            assertEquals(RoomCueLibraryFixture.sha(room.wave), read.audio.verifiedContentToken)
            return entry.copy(location = read.audio.location, cueReference = read.reference)
        }
        val location = checkNotNull(entry.location)
        val read = room.library.resolveLocalRead(entry.trackKey, location.locationId, location.contentRevision)
        assertEquals(RoomCueLibraryFixture.sha(room.wave), read.verifiedContentToken)
        return entry.copy(location = read.location)
    }

    private fun request(entry: QueueEntry): QueueReplacementRequest = QueueReplacementRequest(
        UUID.randomUUID().toString(), null, 0L, FrozenQueueView("routing-${entry.queueEntryId}", 0L, 1L))
    private fun source(request: QueueReplacementRequest, entry: QueueEntry) = QueuePageSource { offset, limit ->
        check(offset == 0L && limit in 1..100)
        QueueInputPage(request.view, offset, listOf(entry))
    }
    private suspend fun replace(entry: QueueEntry) = request(entry).let { request ->
        coordinator.replace(request, source(request, entry), explicitPlay = false).snapshot
    }

    private fun assertSecondSegment(media: androidx.media3.common.MediaItem, factory: DataSource.Factory) {
        val source = factory.createDataSource()
        try {
            val payload = room.wave.copyOfRange(44 + 44_688 * 4, room.wave.size)
            assertEquals(44L + payload.size, source.open(DataSpec.Builder()
                .setUri(checkNotNull(media.localConfiguration).uri).build()))
            val output = ByteArrayOutputStream()
            val bytes = ByteArray(901)
            while (true) {
                val count = source.read(bytes, 0, bytes.size)
                if (count < 0) break
                check(count > 0)
                output.write(bytes, 0, count)
            }
            val actual = output.toByteArray()
            assertArrayEquals(payload, actual.copyOfRange(44, actual.size))
            val samples = ByteBuffer.wrap(actual, 44, 4).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals((-11000).toShort(), samples.short)
            assertEquals(11000.toShort(), samples.short)
            assertEquals(0L, media.clippingConfiguration.startPositionUs)
            assertEquals(checkNotNull(second.cueReference?.pcmWindow).durationUs, media.clippingConfiguration.endPositionUs)
        } finally { source.close() }
    }

    /** 输入仍走生产全哈希、WAV解析、block proof与虚拟段输出；只替换Android FD接入。 */
    private class FixtureInputs(private val wave: ByteArray) : CuePcmInputFactory {
        private val cue = RoomCueLibraryFixture.CUE.toByteArray(Charsets.UTF_8)
        override fun create(binding: CueFileBinding): CueSeekableInput {
            val bytes = when (binding.objectId) {
                "sheet" -> cue
                "wave" -> wave
                else -> error("Unexpected CUE input")
            }
            return object : CueSeekableInput {
                private var opened = false
                private var position = 0
                override val length = bytes.size.toLong()
                override fun initialize() {
                    check(!opened)
                    opened = true
                }
                override fun seek(position: Long) {
                    check(opened)
                    require(position in 0L..length)
                    this.position = position.toInt()
                }
                override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
                    check(opened)
                    if (position == bytes.size) return -1
                    val read = minOf(count, bytes.size - position)
                    bytes.copyInto(buffer, offset, position, position + read)
                    position += read
                    return read
                }
                override fun cancel() = Unit
                override fun close() { opened = false }
            }
        }
    }

    /** 只隔离原生准备/seek完成边界；一个真实ExoPlayer及其媒体项仍由Queue所有。 */
    private class IntentOnlyOperations(override val player: ExoPlayer) : PlaybackBackendOperations {
        override fun setPauseAtBoundary(pause: Boolean) = player.setPauseAtEndOfMediaItems(pause)
        override suspend fun prepareAndAwait(expectedMediaId: String) { assertEquals(expectedMediaId, player.currentMediaItem?.mediaId) }
        override suspend fun seekAndAwait(expectedMediaId: String, positionMs: Long) {
            assertEquals(expectedMediaId, player.currentMediaItem?.mediaId)
            player.seekTo(positionMs)
        }
    }
    private class FixedPreferences : UserPreferencesRepository {
        override val state = MutableStateFlow<PreferencesState>(PreferencesState.Ready(UserPreferences()))
        override suspend fun save(expectedRevision: Long, draft: UserPreferencesDraft): PreferencesWriteResult = error("No preferences writes")
        override suspend fun reset(expectedRevision: Long, confirmation: ResetConfirmation): PreferencesWriteResult = error("No preferences reset")
        override fun retryRead() = Unit
    }
}
