package io.github.onlinemo.meloxwatch.playback.lyrics

import android.content.Intent
import android.os.Looper
import androidx.media3.common.FlagSet
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import io.github.onlinemo.meloxwatch.data.bluetoothlyrics.BluetoothLyricsHandshake
import io.github.onlinemo.meloxwatch.data.bluetoothlyrics.BluetoothLyricsIssue
import io.github.onlinemo.meloxwatch.data.bluetoothlyrics.BluetoothLyricsLimits
import io.github.onlinemo.meloxwatch.data.bluetoothlyrics.BluetoothLyricsMessage
import io.github.onlinemo.meloxwatch.data.bluetoothlyrics.BluetoothLyricsPayload
import io.github.onlinemo.meloxwatch.data.bluetoothlyrics.BluetoothLyricsPlayback
import io.github.onlinemo.meloxwatch.data.bluetoothlyrics.BluetoothLyricsRole
import io.github.onlinemo.meloxwatch.data.bluetoothlyrics.BluetoothLyricsWire
import io.github.onlinemo.meloxwatch.data.library.LocalReadDescriptor
import io.github.onlinemo.meloxwatch.data.local.LocalNodeKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.lyrics.LocalLyricsParser
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsEncodingChoice
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsLimits
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsTranslationMatcher
import io.github.onlinemo.meloxwatch.data.lyrics.active.LocalLyricsContext
import io.github.onlinemo.meloxwatch.data.lyrics.active.LyricsDocuments
import io.github.onlinemo.meloxwatch.data.lyrics.active.LyricsLoadAuthority
import io.github.onlinemo.meloxwatch.data.lyrics.active.PreparedLyricsAssociation
import io.github.onlinemo.meloxwatch.data.lyrics.load.LocalLyricsLoadFixture
import io.github.onlinemo.meloxwatch.data.lyrics.load.LocalLyricsLoadResult
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsDocumentInput
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsLoadRequest
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsLoadedSource
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsLoadSelection
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsSiblingStatus
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsSourceOrigin
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsAssociation
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackCommitResult
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackPreferences
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackReadResult
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackStore
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackWriteFence
import io.github.onlinemo.meloxwatch.data.queue.QueueCommit
import io.github.onlinemo.meloxwatch.data.queue.QueuePageSource
import io.github.onlinemo.meloxwatch.data.queue.QueueReplacementRequest
import io.github.onlinemo.meloxwatch.data.queue.QueueStore
import io.github.onlinemo.meloxwatch.model.FrozenQueueView
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import io.github.onlinemo.meloxwatch.model.QueueCheckpoint
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueMode
import io.github.onlinemo.meloxwatch.model.QueueMutationCandidate
import io.github.onlinemo.meloxwatch.model.QueueMutationRequest
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import io.github.onlinemo.meloxwatch.model.QueueWindow
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsPageContent
import io.github.onlinemo.meloxwatch.ui.lyrics.entry.LyricsEntrySurface
import io.github.onlinemo.meloxwatch.ui.lyrics.entry.LyricsEntryViewModel
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 使用真实解析器、Entry owner、服务时钟和双端加密 Wire 验证组合行为。
 * 受控 Player 与内存字节通路只证明云端契约，不能作为物理 RFCOMM 或原 AC 的通过证据。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class BluetoothLyricsSenderTest {
    @Test fun realParsedLinesAndTranslationsReachTheReceiverAcrossSeekAndTrackChange(): Unit = runTest {
        val fixture = fixture()
        fixture.saved[fixture.firstAudio.trackKey] = fixture.saved.getValue(fixture.firstAudio.trackKey)
            .copy(userDelayMs = 500)
        fixture.player.seek(1500L)
        fixture.player.setPlaying(true)
        val wire = SenderWireFixture()
        val sender = fixture.sender(wire)
        try {
            runCurrent()
            val first = wire.received.last()
            assertEquals("first line", first.original)
            assertEquals("第一译行", first.translation)
            assertEquals(1500L, first.lineMediaTimeMs)
            assertEquals(BluetoothLyricsPlayback.PLAYING, first.playback)
            assertEquals("Fixture title one", first.title)
            assertEquals("Fixture title one", fixture.player.player.mediaMetadata.title)

            fixture.player.seek(2600L)
            runCurrent()
            val sought = wire.received.last()
            assertEquals("second line\nharmony", sought.original)
            assertEquals("第二译行\n和声译行", sought.translation)
            assertEquals(2500L, sought.lineMediaTimeMs)
            assertTrue(sought.trackEpoch > first.trackEpoch)

            fixture.selectSecond(1500L)
            runCurrent()
            val second = wire.received.last()
            assertEquals("other track", second.original)
            assertEquals("另一曲译行", second.translation)
            assertEquals("Fixture title two", second.title)
            assertTrue(second.trackEpoch > sought.trackEpoch)
            assertEquals("Fixture title two", fixture.player.player.mediaMetadata.title)
            assertEquals(0, fixture.player.releaseCalls)
            assertTrue(fixture.issues.isEmpty())
        } finally {
            sender.close()
            sender.awaitClosed()
            fixture.close()
            wire.close()
            runCurrent()
        }
    }

    @Test fun aHiddenPageUsesOnlyTheNextServiceLineBoundaryAndPauseCancelsIt(): Unit = runTest {
        val fixture = fixture()
        fixture.player.setPlaying(true)
        val wire = SenderWireFixture()
        val sender = fixture.sender(wire)
        try {
            runCurrent()
            assertNull(fixture.model.mediaClock.value)
            assertEquals("", wire.received.last().original)
            advanceTimeBy(999L)
            runCurrent()
            assertEquals("", wire.received.last().original)
            advanceTimeBy(1L)
            runCurrent()
            assertEquals("first line", wire.received.last().original)
            val reads = fixture.player.clockReads
            advanceTimeBy(500L)
            runCurrent()
            assertEquals(reads, fixture.player.clockReads)
            fixture.player.setPlaying(false)
            runCurrent()
            assertEquals(BluetoothLyricsPlayback.PAUSED, wire.received.last().playback)
            val frames = wire.received.size
            val pausedReads = fixture.player.clockReads
            advanceTimeBy(10_000L)
            runCurrent()
            assertEquals(frames, wire.received.size)
            assertEquals(pausedReads, fixture.player.clockReads)
            assertNull(fixture.model.mediaClock.value)
        } finally {
            sender.close()
            sender.awaitClosed()
            fixture.close()
            wire.close()
            runCurrent()
        }
    }

    @Test fun sourceRetirementWaitsForItsOwnTasksWhileTheSharedVisiblePageRemainsAlive(): Unit = runTest {
        val fixture = fixture()
        fixture.model.onVisible(LyricsEntrySurface.PAGE)
        val wire = SenderWireFixture()
        val sender = fixture.sender(wire)
        try {
            runCurrent()
            val retirement = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                sender.close()
                sender.awaitClosed()
            }
            // StandardTestDispatcher 尚未处理输出观察器的取消；共享页面不应替它证明退出。
            assertFalse(retirement.isCompleted)
            runCurrent()
            assertTrue(retirement.isCompleted)
            assertTrue(fixture.model.uiState.value.content is LyricsPageContent.Ready)
            assertTrue(backgroundScope.coroutineContext[Job]?.isActive == true)
            val frames = wire.received.size
            fixture.player.seek(3000L)
            runCurrent()
            assertEquals(frames, wire.received.size)
            assertEquals(0, fixture.player.releaseCalls)
        } finally {
            sender.close()
            sender.awaitClosed()
            fixture.close()
            wire.close()
            runCurrent()
        }
    }

    @Test fun aLateCancelledLoadCannotSendThePreviousTracksTextAfterTheServiceChangesTrack(): Unit = runTest {
        val fixture = fixture()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.beforeLoad = { request ->
            if (request.binding.trackKey == fixture.firstAudio.trackKey) {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
            }
        }
        val wire = SenderWireFixture()
        val sender = fixture.sender(wire)
        try {
            runCurrent()
            assertTrue(entered.isCompleted)
            fixture.selectSecond(1500L)
            runCurrent()
            val transition = wire.received.indexOfFirst { it.title == "Fixture title two" }
            assertTrue(transition >= 0)
            release.complete(Unit)
            runCurrent()
            assertEquals("other track", wire.received.last().original)
            assertTrue(wire.received.drop(transition).none { "first line" in it.original })
            assertTrue(fixture.issues.isEmpty())
        } finally {
            release.complete(Unit)
            sender.close()
            sender.awaitClosed()
            fixture.close()
            wire.close()
            runCurrent()
        }
    }

    @Test fun aLongUnicodeCurrentLineReachesTheReceiverAsOneBoundedMarkedSnapshot(): Unit = runTest {
        val fixture = fixture()
        fixture.primaryTexts[fixture.firstAudio.trackKey] = "[00:01]" + "🎵".repeat(3000)
        fixture.player.seek(1500L)
        val wire = SenderWireFixture()
        val sender = fixture.sender(wire)
        try {
            runCurrent()
            val payload = wire.received.last()
            assertTrue(payload.shortened)
            assertTrue(payload.original.isNotEmpty())
            assertTrue(payload.original.toByteArray(Charsets.UTF_8).size <= BluetoothLyricsLimits.ORIGINAL_BYTES)
            assertEquals("第一译行", payload.translation)
            assertEquals(1000L, payload.lineMediaTimeMs)
            assertTrue(fixture.issues.isEmpty())
        } finally {
            sender.close()
            sender.awaitClosed()
            fixture.close()
            wire.close()
            runCurrent()
        }
    }

    private fun TestScope.fixture() = SenderEntryFixture(backgroundScope,
        StandardTestDispatcher(testScheduler), testScheduler)
}

private class SenderEntryFixture(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    scheduler: TestCoroutineScheduler,
) : AutoCloseable {
    val firstAudio = LocalLyricsLoadFixture().audio
    private val secondKey = TrackKey("local", "second-track")
    private val secondAudio = LocalReadDescriptor(secondKey, firstAudio.source, "second-alias",
        LocalLocationReference("second-location", "content://bluetooth.lyrics.fixture/audio/two",
            firstAudio.location.grantUri, contentRevision = 7L), "second-object", "native-audio-v2", null)
    private val audio = mapOf(firstAudio.trackKey to firstAudio, secondKey to secondAudio)
    val primaryTexts = mutableMapOf(firstAudio.trackKey to
        "[00:01]first line\n[00:02]second line\n[00:02]harmony\n[00:04]last line",
        secondKey to "[00:01]other track\n[00:03]other ending")
    private val documents = SenderDocuments(mapOf(firstAudio.trackKey to
        "[00:01]第一译行\n[00:02]第二译行\n[00:02]和声译行\n[00:04]末译行",
        secondKey to "[00:01]另一曲译行\n[00:03]另一曲末行"))
    val saved = audio.keys.associateWith { LyricsTrackPreferences(it, translation = documents.association(it)) }
        .toMutableMap()
    private val queues = SenderQueues(audio.values.mapIndexed { index, descriptor ->
        QueueEntry(if (index == 0) "entry-one" else "entry-two", descriptor.trackKey, index.toLong(),
            index.toLong(), location = descriptor.location)
    })
    val player = SenderPlayer(scheduler)
    private val playback = ServiceLyricsPlaybackPort()
    private val service = playback.bindService(player.player)
    private val authority = LyricsLoadAuthority()
    val issues = mutableListOf<BluetoothLyricsIssue>()
    var beforeLoad: (suspend (LyricsLoadRequest) -> Unit)? = null
    val model = LyricsEntryViewModel(
        queues = queues,
        resolveContext = { LocalLyricsContext(audio.getValue(it.trackKey), null, null) },
        validateContext = { audio[it.audio.trackKey] == it.audio },
        tracks = object : LyricsTrackStore {
            override suspend fun read(trackKey: TrackKey): LyricsTrackReadResult =
                LyricsTrackReadResult.Ready(saved.getValue(trackKey))
            override suspend fun commit(expectedRevision: Long, next: LyricsTrackPreferences,
                fence: LyricsTrackWriteFence, stillCurrent: suspend () -> Boolean): LyricsTrackCommitResult =
                error("Read-only sender must not write associations or offsets")
        },
        documents = documents,
        authority = authority,
        load = { request ->
            beforeLoad?.invoke(request)
            val descriptor = audio.getValue(request.binding.trackKey)
            val input = (request.selection as? LyricsLoadSelection.TextDocument)?.input
            val text = if (input == null) primaryTexts.getValue(request.binding.trackKey)
                else documents.text(input.documentId)
            val bytes = text.toByteArray(Charsets.UTF_8)
            val parsed = LocalLyricsParser(LyricsLimits(), dispatcher).parse(bytes)
            val source = LyricsLoadedSource(
                if (input == null) LyricsSourceOrigin.EMBEDDED_SYNCHRONIZED else LyricsSourceOrigin.EXPLICIT_DOCUMENT,
                input?.source?.sourceId ?: descriptor.source.spec.sourceId,
                input?.candidate?.objectId ?: descriptor.objectId,
                input?.documentId ?: "embedded-" + request.binding.trackKey.trackId,
                request.binding.sourceGeneration, senderHash(bytes),
                if (input == null) "embedded-fixture" else null)
            LocalLyricsLoadResult.Loaded(request.binding, descriptor, source, parsed, emptyList(), emptyList(),
                LyricsSiblingStatus.NOT_REQUESTED, parsed.accountedResidentBytes)
        },
        validate = { request, loaded -> authority.isCurrent(request) && loaded.binding == request.binding },
        playback = playback,
        translationMatcher = LyricsTranslationMatcher(LyricsLimits(), dispatcher),
        scopeOverride = scope,
        awaitDisplayFrame = {},
    )

    fun sender(wire: SenderWireFixture) = BluetoothLyricsSender(model, playback, scope,
        offer = wire::receive, onFailure = { issues += it })

    fun selectSecond(positionMs: Long) {
        player.id = "entry-two"
        player.seek(positionMs)
        player.event(Player.EVENT_MEDIA_ITEM_TRANSITION)
        queues.selectSecond()
    }

    override fun close() {
        model.close()
        service.close()
        playback.close()
    }
}

private class SenderDocuments(private val texts: Map<TrackKey, String>) : LyricsDocuments {
    private val byId = texts.entries.associate { "translation-" + it.key.trackId to it.value }
    fun text(id: String) = byId.getValue(id)
    fun association(key: TrackKey): LyricsAssociation {
        val id = "translation-" + key.trackId
        return LyricsAssociation(id, id, "content://bluetooth.lyrics.fixture/lyrics/$id",
            Intent.FLAG_GRANT_READ_URI_PERMISSION, LyricsEncodingChoice.AUTO_BOM_OR_UTF8,
            senderHash(byId.getValue(id).toByteArray(Charsets.UTF_8)))
    }
    override suspend fun resolve(association: LyricsAssociation, generation: Long): LyricsDocumentInput {
        val source = LocalSourceSpec(association.sourceId, LocalSourceKind.DOCUMENT, association.uri)
        val bytes = text(association.documentId).toByteArray(Charsets.UTF_8)
        val candidate = ObservedCandidate(source.sourceId, association.documentId, association.uri,
            null, "Fixture.lrc", "text/plain", LocalNodeKind.OTHER, bytes.size.toLong())
        return LyricsDocumentInput(association.documentId, generation, source, candidate,
            expectedContentSha256 = association.confirmedContentSha256)
    }
    override suspend fun preview(uri: String, returnedFlags: Int, generation: Long,
        encoding: LyricsEncodingChoice): LyricsDocumentInput = error("No picker belongs to external output")
    override suspend fun prepareAssociation(input: LyricsDocumentInput,
        confirmedSha256: String): PreparedLyricsAssociation = error("No write belongs to external output")
}

private class SenderQueues(private val entries: List<QueueEntry>) : QueueStore {
    private val state = MutableStateFlow(QueueSnapshot("bluetooth-queue", 3L, 0L,
        FrozenQueueView("bluetooth-view", 1L, 2L), QueueMode(), QueueCheckpoint(entries.first().queueEntryId, 0L), 0L, 0L))
    fun selectSecond() {
        state.value = state.value.copy(checkpointRevision = state.value.checkpointRevision + 1L,
            checkpoint = QueueCheckpoint(entries[1].queueEntryId, 0L), currentOriginalOrder = 1L,
            currentPlaybackOrder = 1L)
    }
    override suspend fun initialize(): QueueSnapshot = state.value
    override suspend fun snapshot(): QueueSnapshot = state.value
    override fun snapshots(): Flow<QueueSnapshot?> = state
    override suspend fun window(generationId: String, currentEntryId: String): QueueWindow =
        QueueWindow(generationId, state.value.queueRevision, 2L, entries, entries.indexOfFirst { it.queueEntryId == currentEntryId })
    override suspend fun page(generationId: String, firstPlaybackOrder: Long, limit: Int): List<QueueEntry> =
        error("External lyrics must not read the complete queue")
    override suspend fun replace(request: QueueReplacementRequest, source: QueuePageSource,
        beforePublish: suspend (QueueWindow?) -> Unit): QueueCommit = error("No replacement belongs to external output")
    override suspend fun mutate(request: QueueMutationRequest, source: QueuePageSource?,
        beforePublish: suspend (QueueMutationCandidate) -> QueueCheckpoint?): QueueCommit = error("No mutation belongs to external output")
    override suspend fun saveCheckpoint(generationId: String, expectedCheckpointRevision: Long,
        checkpoint: QueueCheckpoint): QueueSnapshot = error("No checkpoint owner belongs to external output")
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
@OptIn(ExperimentalCoroutinesApi::class)
private class SenderPlayer(private val scheduler: TestCoroutineScheduler) {
    private val listeners = linkedSetOf<Player.Listener>()
    var id = "entry-one"
    private var anchorPositionMs = 0L
    private var anchorAtMs = scheduler.currentTime
    private var playing = false
    var clockReads = 0
    var releaseCalls = 0
    private fun position(): Long = anchorPositionMs + if (playing) scheduler.currentTime - anchorAtMs else 0L
    fun seek(positionMs: Long) {
        anchorPositionMs = positionMs
        anchorAtMs = scheduler.currentTime
        event(Player.EVENT_POSITION_DISCONTINUITY)
    }
    fun setPlaying(value: Boolean) {
        anchorPositionMs = position()
        anchorAtMs = scheduler.currentTime
        playing = value
        event(Player.EVENT_IS_PLAYING_CHANGED)
    }
    val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { proxy, method, args ->
        when (method.name) {
            "getApplicationLooper" -> Looper.getMainLooper()
            "addListener" -> {
                listeners += args!![0] as Player.Listener
                null
            }
            "removeListener" -> {
                listeners -= args!![0] as Player.Listener
                null
            }
            "getCurrentMediaItem" -> MediaItem.Builder().setMediaId(id).build()
            "getMediaMetadata" -> MediaMetadata.Builder()
                .setTitle(if (id == "entry-one") "Fixture title one" else "Fixture title two")
                .setArtist("Fixture artist").build()
            "getCurrentPosition" -> {
                clockReads++
                position()
            }
            "getDuration" -> 180_000L
            "getPlaybackState" -> Player.STATE_READY
            "isPlaying" -> playing
            "getPlaybackParameters" -> PlaybackParameters.DEFAULT
            "isCommandAvailable" -> true
            "release" -> {
                releaseCalls++
                null
            }
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.get(0)
            "toString" -> "<controlled-service-player>"
            else -> throw AssertionError("Unexpected operation on the read-only player boundary")
        }
    } as Player
    fun event(flag: Int) {
        val events = Player.Events(FlagSet.Builder().add(flag).build())
        listeners.toList().forEach { it.onEvents(player, events) }
    }
}

/** 固定内存通路实际完成双方 commit/reveal、双向确认、AES-GCM 编码和接收解码。 */
private class SenderWireFixture : AutoCloseable {
    private val watchInput = PipedInputStream(BluetoothLyricsLimits.FRAME_BYTES * 2)
    private val phoneInput = PipedInputStream(BluetoothLyricsLimits.FRAME_BYTES * 2)
    private val watchOutput = PipedOutputStream(phoneInput)
    private val phoneOutput = PipedOutputStream(watchInput)
    private val executor = Executors.newFixedThreadPool(2)
    private val watchHello = BluetoothLyricsHandshake.create(BluetoothLyricsRole.WATCH)
    private val phoneHello = BluetoothLyricsHandshake.create(BluetoothLyricsRole.PHONE)
    private var watch: BluetoothLyricsWire? = null
    private var phone: BluetoothLyricsWire? = null
    val received = mutableListOf<BluetoothLyricsPayload>()
    init {
        try {
            val pendingPhone = executor.submit<BluetoothLyricsWire> {
                BluetoothLyricsWire.establish(phoneInput, phoneOutput, phoneHello)
            }
            val pendingWatch = executor.submit<BluetoothLyricsWire> {
                BluetoothLyricsWire.establish(watchInput, watchOutput, watchHello)
            }
            watch = pendingWatch.get(5L, TimeUnit.SECONDS)
            phone = pendingPhone.get(5L, TimeUnit.SECONDS)
            check(checkNotNull(watch).comparison.forLocalUi() == checkNotNull(phone).comparison.forLocalUi())
            checkNotNull(watch).confirmLocal()
            checkNotNull(phone).confirmLocal()
            checkNotNull(watch).readPeerConfirmation()
            checkNotNull(phone).readPeerConfirmation()
        } catch (error: Throwable) {
            close()
            throw error
        }
    }
    fun receive(payload: BluetoothLyricsPayload) {
        checkNotNull(watch).writeSnapshot(payload)
        received += (checkNotNull(phone).readMessage() as BluetoothLyricsMessage.Snapshot).payload
    }
    override fun close() {
        watch?.close()
        phone?.close()
        watchHello.close()
        phoneHello.close()
        watchOutput.close()
        phoneOutput.close()
        watchInput.close()
        phoneInput.close()
        executor.shutdownNow()
    }
}

private fun senderHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
