package io.github.onlinemo.meloxwatch.ui.lyrics.entry

import io.github.onlinemo.meloxwatch.data.lyrics.LocalLyricsParser
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsEncodingChoice
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsLimits
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsMemoryOwner
import io.github.onlinemo.meloxwatch.data.lyrics.active.LyricsDocuments
import io.github.onlinemo.meloxwatch.data.lyrics.active.LyricsLoadAuthority
import io.github.onlinemo.meloxwatch.data.lyrics.active.PreparedLyricsAssociation
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsDocumentInput
import io.github.onlinemo.meloxwatch.data.lyrics.load.LyricsLoadIssue
import io.github.onlinemo.meloxwatch.data.lyrics.online.OnlineLyricsFence
import io.github.onlinemo.meloxwatch.data.lyrics.online.OnlineLyricsOrigin
import io.github.onlinemo.meloxwatch.data.lyrics.online.OnlineLyricsIssue
import io.github.onlinemo.meloxwatch.data.lyrics.online.OnlineLyricsPort
import io.github.onlinemo.meloxwatch.data.lyrics.online.OnlineLyricsRepository
import io.github.onlinemo.meloxwatch.data.lyrics.online.OnlineLyricsRepositoryFixture
import io.github.onlinemo.meloxwatch.data.lyrics.online.OnlineLyricsRequest
import io.github.onlinemo.meloxwatch.data.lyrics.online.OnlineLyricsResult
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
import io.github.onlinemo.meloxwatch.model.QueueCheckpoint
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueMode
import io.github.onlinemo.meloxwatch.model.QueueMutationCandidate
import io.github.onlinemo.meloxwatch.model.QueueMutationRequest
import io.github.onlinemo.meloxwatch.model.QueueSeekBinding
import io.github.onlinemo.meloxwatch.model.QueueSeekRequest
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import io.github.onlinemo.meloxwatch.model.QueueWindow
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.model.onlineOpaqueHash
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsPageContent
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsPlayPreview
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import org.robolectric.annotation.Config

/** The existing Entry and confirmation controller are exercised; no additional media player or clock exists. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class OnlineLyricsEntryViewModelTest {
    @get:Rule val temporary = TemporaryFolder()
    @Before fun initializeSyntheticHttp() { OkHttp.initialize(RuntimeEnvironment.getApplication()) }

    @Test fun externalReadingAuthorizesOnlyOneFirstReadyCurrentTrackAttemptAfterCacheFailure(): Unit = runTest {
        val requests = ArrayList<OnlineLyricsRequest>()
        val signals = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val port = object : OnlineLyricsPort {
            override val changes: Flow<Unit> = signals
            override suspend fun load(request: OnlineLyricsRequest, isCurrent: () -> Boolean): OnlineLyricsResult {
                requests += request
                return OnlineLyricsResult.Unavailable(OnlineLyricsIssue.OFFLINE)
            }
            override suspend fun validate(loaded: OnlineLyricsResult.Loaded) = false
        }
        val fixture = fixture(port = port)
        var currentTrackReady = false
        val external = fixture.vm.bindExternalReading {
            currentTrackReady && it == fixture.queues.current.currentEntryId
        }
        try {
            runCurrent()
            assertEquals(1, requests.size)
            assertFalse(requests.single().allowNetwork)
            currentTrackReady = true
            fixture.playback.signals.emit(Unit)
            runCurrent()
            assertEquals(2, requests.size)
            assertTrue(requests.last().allowNetwork)
            repeat(3) { fixture.playback.signals.emit(Unit); runCurrent() }
            assertEquals(2, requests.size)
            assertNull(fixture.vm.mediaClock.value)
            assertEquals(0, fixture.localCalls)
        } finally { external.close(); fixture.vm.close(); runCurrent() }
    }

    @Test fun missingOnlineAccountCannotCreateAnAutomaticExternalReadLoop(): Unit = runTest {
        val fixture = fixture(first = QueueEntry("lyrics-entry-one", TrackKey("netease", "201"), 0L, 0L))
        val external = fixture.vm.bindExternalReading { true }
        try {
            runCurrent()
            assertEquals(OnlineLyricsIssue.LOGIN_REQUIRED, fixture.vm.uiState.value.onlineIssue)
            repeat(3) { fixture.playback.signals.emit(Unit); runCurrent() }
            assertEquals(0, fixture.online.requests.size)
            assertNull(fixture.vm.readExternal())
            assertEquals(0, fixture.localCalls)
        } finally { external.close(); fixture.vm.close(); runCurrent() }
    }

    @Test fun authenticatedOnlineReadingReusesTheDocumentWhenItsUiSamplerStops(): Unit = runTest {
        val fixture = fixture()
        val external = fixture.vm.bindExternalReading { it == fixture.queues.current.currentEntryId }
        try {
            runCurrent()
            assertTrue(fixture.online.requests.single().allowNetwork)
            val document = checkNotNull(fixture.vm.readExternal()).document
            fixture.playback.playing = true
            fixture.vm.onVisible(LyricsEntrySurface.PAGE)
            runCurrent()
            fixture.vm.onHidden()
            runCurrent()
            val reads = fixture.playback.clockReads
            advanceTimeBy(5000L)
            runCurrent()
            assertEquals(reads, fixture.playback.clockReads)
            assertNull(fixture.vm.mediaClock.value)
            assertSame(document, fixture.vm.readExternal()?.document)
            assertEquals(1, fixture.online.requests.size)
            assertEquals(0, fixture.localCalls)
            assertTrue(fixture.playback.dispatched.isEmpty())
        } finally { external.close(); fixture.vm.close(); runCurrent() }
    }

    @Test fun theProductionNativeRepositoryFeedsTheOriginalEntryAndTimelineWithNoLocalAuthority() = runTest {
        OnlineLyricsRepositoryFixture(temporary.newFolder(), this).use { repository ->
            repository.signIn()
            repository.openNative()
            val port = object : OnlineLyricsPort {
                override val changes = repository.repository.changes.drop(1)
                // 真实磁盘/解析边界使用真实IO时钟；runTest只推进Entry的合成时钟。
                override suspend fun load(request: OnlineLyricsRequest, isCurrent: () -> Boolean) =
                    withContext(Dispatchers.IO) { repository.repository.load(request, isCurrent) }
                override suspend fun validate(loaded: OnlineLyricsResult.Loaded) =
                    withContext(Dispatchers.IO) { repository.repository.validate(loaded) }
            }
            val fixture = fixture(port = port, first = repository.entry)
            try {
                runCurrent()
                assertEquals(0, repository.requests.size)
                fixture.vm.onVisible(LyricsEntrySurface.PAGE)
                val ready = fixture.vm.uiState.first { it.content is LyricsPageContent.Ready }
                assertEquals(OnlineLyricsOrigin.NETEASE_LRC, ready.onlineOrigin)
                val document = (ready.content as LyricsPageContent.Ready).document
                assertEquals(1_230L, document.timeline.line(0).startsAtMs)
                assertEquals("original", document.timeline.line(0).text)
                assertEquals(1, repository.requests.size)
                assertEquals(0, fixture.localCalls)
                assertEquals(0, repository.httpAcquisitions.get())
                assertNull(ready.preferences)
                assertFalse(ready.canChoose)
                assertTrue(fixture.playback.dispatched.isEmpty())
            } finally { fixture.vm.close(); runCurrent() }
        }
    }

    @Test fun constructionHiddenAndOptionsDoNotAuthorizeNewLyricsAndHiddenSamplingStops() = runTest {
        val fixture = fixture()
        try {
            runCurrent()
            assertEquals(0, fixture.online.requests.size)
            fixture.vm.onVisible(LyricsEntrySurface.OPTIONS)
            runCurrent()
            assertFalse(fixture.online.requests.single().allowNetwork)
            assertNull(fixture.vm.mediaClock.value)
            fixture.vm.onHidden()
            fixture.playback.playing = true
            fixture.vm.onVisible(LyricsEntrySurface.PAGE)
            runCurrent()
            assertTrue(fixture.online.requests.last().allowNetwork)
            assertNotNull(fixture.vm.mediaClock.value)
            fixture.vm.onHidden()
            runCurrent()
            val calls = fixture.online.requests.size
            val reads = fixture.playback.clockReads
            advanceTimeBy(5_000L)
            runCurrent()
            assertEquals(calls, fixture.online.requests.size)
            assertEquals(reads, fixture.playback.clockReads)
            assertNull(fixture.vm.mediaClock.value)
            assertTrue(fixture.playback.dispatched.isEmpty())
        } finally { fixture.vm.close(); runCurrent() }
    }

    @Test fun aLocalEntryCannotCreateOrCallTheOnlineLyricsPort() = runTest {
        val fixture = fixture(first = QueueEntry("local-placeholder", TrackKey(TrackKey.LOCAL_PROVIDER, "local-placeholder"), 0L, 0L))
        try {
            fixture.vm.onVisible(LyricsEntrySurface.PAGE)
            runCurrent()
            assertEquals(0, fixture.online.requests.size)
            assertEquals(0, fixture.online.validations)
            assertTrue(fixture.localCalls > 0)
            assertTrue(fixture.playback.dispatched.isEmpty())
        } finally { fixture.vm.close(); runCurrent() }
    }

    @Test fun cachedDisplayDoesNotRequirePlaybackValidationOrStartASoundProducingAction() = runTest {
        val fixture = fixture()
        fixture.online.cached = true
        fixture.online.valid = false
        fixture.playback.canSeek = false
        try {
            fixture.vm.onVisible(LyricsEntrySurface.PAGE)
            runCurrent()
            assertTrue(fixture.vm.uiState.value.content is LyricsPageContent.Ready)
            assertTrue(fixture.vm.uiState.value.onlineFromCache)
            assertEquals(0, fixture.online.validations)
            assertNull(fixture.vm.previewPlay(fixture.preview()))
            assertEquals(0, fixture.playback.acquisitions)
            assertTrue(fixture.playback.dispatched.isEmpty())
        } finally { fixture.vm.close(); runCurrent() }
    }

    @Test fun lineConfirmationObtainsOneOriginalServiceTicketAndDispatchesThatSameObjectOnlyAfterConfirmation() = runTest {
        val fixture = fixture()
        try {
            fixture.vm.onVisible(LyricsEntrySurface.PAGE)
            runCurrent()
            val confirmation = checkNotNull(fixture.vm.previewPlay(fixture.preview()))
            runCurrent()
            assertEquals(LyricsPlayPhase.CONFIRMATION, fixture.vm.playConfirmation.state.value.phase)
            assertEquals(1, fixture.playback.acquisitions)
            assertTrue(fixture.playback.dispatched.isEmpty())
            assertNull(fixture.vm.mediaClock.value)
            assertTrue(fixture.vm.playConfirmation.confirm(confirmation))
            runCurrent()
            assertEquals(1, fixture.online.validations)
            assertEquals(1, fixture.playback.acquisitions)
            val request = fixture.playback.dispatched.single()
            assertSame(fixture.playback.originalTicket, request.binding)
            assertEquals(1_000L, request.positionMs)
            assertTrue(request.explicitPlay)
            assertEquals(1, fixture.online.requests.size)
        } finally { fixture.vm.close(); runCurrent() }
    }

    @Test fun sourceChangeRetiresTheOldConfirmationAndDefersLyricsWorkUntilTheBodyIsPresentedAgain() = runTest {
        val fixture = fixture()
        try {
            fixture.vm.onVisible(LyricsEntrySurface.PAGE)
            runCurrent()
            val old = checkNotNull(fixture.vm.previewPlay(fixture.preview()))
            runCurrent()
            fixture.online.signals.emit(Unit)
            runCurrent()
            assertEquals(1, fixture.online.requests.size)
            assertFalse(fixture.vm.playConfirmation.confirm(old))
            assertTrue(fixture.playback.dispatched.isEmpty())
            fixture.vm.setTimelinePresented(true)
            runCurrent()
            assertEquals(2, fixture.online.requests.size)
            assertTrue(fixture.online.requests.last().sourceGeneration > fixture.online.requests.first().sourceGeneration)
            assertTrue(fixture.vm.uiState.value.content is LyricsPageContent.Ready)
        } finally { fixture.vm.close(); runCurrent() }
    }

    @Test fun anOldBackendTicketCannotBeRenewedDuringSourceVerification() = runTest {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val fixture = fixture()
        fixture.online.beforeValidate = { entered.complete(Unit); withContext(NonCancellable) { gate.await() } }
        try {
            fixture.vm.onVisible(LyricsEntrySurface.PAGE)
            runCurrent()
            val confirmation = checkNotNull(fixture.vm.previewPlay(fixture.preview()))
            runCurrent()
            assertTrue(fixture.vm.playConfirmation.confirm(confirmation))
            runCurrent()
            assertTrue(entered.isCompleted)
            fixture.playback.backendEpoch++
            fixture.playback.signals.emit(Unit)
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            assertEquals(1, fixture.playback.acquisitions)
            assertTrue(fixture.playback.dispatched.isEmpty())
            assertFalse(fixture.vm.playConfirmation.confirm(confirmation))
        } finally { gate.complete(Unit); fixture.vm.close(); runCurrent() }
    }

    @Test fun cancelledLoadRetainsTheSharedMemoryReservationThroughPhysicalCleanup() = runTest {
        val memory = LyricsMemoryOwner()
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val first = fixture(memory = memory)
        val second = fixture(memory = memory)
        first.online.beforeLoad = { entered.complete(Unit); withContext(NonCancellable) { gate.await() } }
        try {
            first.vm.onVisible(LyricsEntrySurface.PAGE)
            runCurrent()
            assertTrue(entered.isCompleted)
            first.vm.onHidden()
            second.vm.onVisible(LyricsEntrySurface.PAGE)
            runCurrent()
            assertEquals(0, second.online.requests.size)
            assertEquals(LyricsLoadIssue.BUSY, second.vm.uiState.value.failure?.issue)
            assertNull(memory.acquire(Any()))
            gate.complete(Unit)
            runCurrent()
            second.vm.retry()
            runCurrent()
            assertTrue(second.vm.uiState.value.content is LyricsPageContent.Ready)
            assertEquals(LyricsLimits.ACCOUNTED_BYTES - 262_144L, second.online.requests.single().maxAccountedBytes)
            assertTrue(first.playback.dispatched.isEmpty())
        } finally { gate.complete(Unit); first.vm.close(); second.vm.close(); runCurrent() }
    }

    @Test fun hiddenConfirmationKeepsItsDocumentReservationUntilCancelledSourceVerificationCleansUp() = runTest {
        val memory = LyricsMemoryOwner()
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val fixture = fixture(memory = memory)
        fixture.online.beforeValidate = { entered.complete(Unit); withContext(NonCancellable) { gate.await() } }
        try {
            fixture.vm.onVisible(LyricsEntrySurface.PAGE)
            runCurrent()
            val confirmation = checkNotNull(fixture.vm.previewPlay(fixture.preview()))
            runCurrent()
            assertTrue(fixture.vm.playConfirmation.confirm(confirmation))
            runCurrent()
            assertTrue(entered.isCompleted)
            fixture.vm.onHidden()
            runCurrent()
            assertNull(memory.acquire(Any()))
            assertTrue(fixture.vm.playConfirmation.busy)
            gate.complete(Unit)
            runCurrent()
            checkNotNull(memory.acquire(Any())).close()
            assertFalse(fixture.vm.playConfirmation.busy)
            assertTrue(fixture.playback.dispatched.isEmpty())
        } finally { gate.complete(Unit); fixture.vm.close(); runCurrent() }
    }

    @Test(timeout = 5_000) fun reopeningOptionsWaitsForTheOldConfirmationBeforeParsingOnlyTheLatestCachedIntent(): Unit = runTest {
        val directory = temporary.newFolder("f128-cache")
        val memory = LyricsMemoryOwner()
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        OnlineLyricsRepositoryFixture(directory, this).use { real ->
            real.signIn()
            real.openNative()
            val scheduledParser = StandardTestDispatcher(testScheduler)
            val parserDispatches = AtomicInteger()
            val parser = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    parserDispatches.incrementAndGet()
                    scheduledParser.dispatch(context, block)
                }
            }
            val repository = OnlineLyricsRepository(real.sessions, real.native, real.resources, real.meting,
                real.cache, { real.online.get() }, workDispatcher = parser)
            val loads = ArrayList<OnlineLyricsRequest>()
            var verifyingInput: OnlineLyricsResult.Loaded? = null
            val port = object : OnlineLyricsPort {
                override val changes = repository.changes.drop(1)
                override suspend fun load(request: OnlineLyricsRequest, isCurrent: () -> Boolean): OnlineLyricsResult {
                    loads += request
                    // Real file IO and the repository deadline use wall time; parsing stays observable on the test scheduler.
                    return withContext(Dispatchers.IO) { repository.load(request, isCurrent) }
                }
                override suspend fun validate(loaded: OnlineLyricsResult.Loaded): Boolean {
                    verifyingInput = loaded
                    try {
                        entered.complete(Unit)
                        withContext(NonCancellable) { gate.await() }
                        return repository.validate(loaded)
                    } finally { verifyingInput = null }
                }
            }
            val fixture = fixture(port = port, memory = memory, first = real.entry)
            try {
                fixture.vm.onVisible(LyricsEntrySurface.PAGE)
                val initial = fixture.vm.uiState.first { it.content is LyricsPageContent.Ready }
                runCurrent()
                val originalDocument = (initial.content as LyricsPageContent.Ready).document
                val originalRequest = loads.single()
                val cacheFile = directory.listFiles().orEmpty().single()
                val originalCacheBytes = cacheFile.readBytes()
                val parsedBefore = parserDispatches.get()
                assertTrue(parsedBefore > 0)
                assertFalse(initial.onlineFromCache)
                real.online.set(false)
                val confirmation = checkNotNull(fixture.vm.previewPlay(fixture.preview()))
                runCurrent()
                val originalTicket = checkNotNull(fixture.playback.originalTicket)
                assertTrue(fixture.vm.playConfirmation.confirm(confirmation))
                runCurrent()
                assertTrue(entered.isCompleted)

                fun assertOldCleanupStillOwnsTheSlot() {
                    assertFalse(gate.isCompleted)
                    assertTrue(fixture.vm.playConfirmation.busy)
                    assertEquals(LyricsPlayPhase.CANCELLING, fixture.vm.playConfirmation.state.value.phase)
                    assertNull(memory.acquire(Any()))
                    assertSame(originalDocument, verifyingInput?.document)
                    assertEquals(1, loads.size)
                    assertEquals(parsedBefore, parserDispatches.get())
                    assertArrayEquals(originalCacheBytes, cacheFile.readBytes())
                    assertEquals(1, fixture.playback.acquisitions)
                    assertTrue(fixture.playback.dispatched.isEmpty())
                }

                fixture.vm.onHidden()
                runCurrent()
                assertOldCleanupStillOwnsTheSlot()
                fixture.vm.onVisible(LyricsEntrySurface.OPTIONS)
                runCurrent()
                assertOldCleanupStillOwnsTheSlot()
                repeat(2) {
                    fixture.vm.retry(sourceChanged = true)
                    runCurrent()
                    assertOldCleanupStillOwnsTheSlot()
                }
                gate.complete(Unit)
                val restored = fixture.vm.uiState.first { it.content is LyricsPageContent.Ready }
                runCurrent()
                assertEquals(2, loads.size)
                assertEquals(originalRequest.sourceGeneration + 2L, loads.last().sourceGeneration)
                assertFalse(loads.last().allowNetwork)
                assertEquals(LyricsLimits.ACCOUNTED_BYTES - 262_144L, loads.last().maxAccountedBytes)
                assertTrue(restored.onlineFromCache)
                assertTrue((restored.content as LyricsPageContent.Ready).document !== originalDocument)
                assertTrue(parserDispatches.get() > parsedBefore)
                assertArrayEquals(originalCacheBytes, cacheFile.readBytes())
                assertEquals(1, real.requests.size)
                assertNull(verifyingInput)
                assertFalse(fixture.vm.playConfirmation.busy)
                assertFalse(fixture.vm.playConfirmation.confirm(confirmation))
                assertSame(originalTicket, fixture.playback.originalTicket)
                assertEquals(1, fixture.playback.acquisitions)
                assertTrue(fixture.playback.dispatched.isEmpty())
            } finally { gate.complete(Unit); fixture.vm.close(); repository.close(); runCurrent() }
        }
    }

    private fun TestScope.fixture(port: OnlineLyricsPort? = null, memory: LyricsMemoryOwner = LyricsMemoryOwner(),
        first: QueueEntry = QueueEntry("lyrics-entry-one", TrackKey("netease", "201"), 0L, 0L, accountId = "101")):
        OnlineEntryFixture = OnlineEntryFixture(this, StandardTestDispatcher(testScheduler), port, memory, first)
}

private class OnlineEntryFixture(scope: CoroutineScope, dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    port: OnlineLyricsPort?, memory: LyricsMemoryOwner, first: QueueEntry) {
    val queues = OnlineEntryQueues(first)
    val online = ControlledOnlineLyricsPort(dispatcher)
    val playback = OnlineEntryPlayback(queues)
    var localCalls = 0
    private fun localCall(): Nothing { localCalls++; error("Online lyrics attempted local file authority") }
    val vm = LyricsEntryViewModel(queues, { localCall() }, { localCall() },
        object : LyricsTrackStore {
            override suspend fun read(trackKey: TrackKey): LyricsTrackReadResult = localCall()
            override suspend fun commit(expectedRevision: Long, next: LyricsTrackPreferences, fence: LyricsTrackWriteFence,
                stillCurrent: suspend () -> Boolean): LyricsTrackCommitResult = localCall()
        }, object : LyricsDocuments {
            override suspend fun preview(uri: String, returnedFlags: Int, generation: Long,
                encoding: LyricsEncodingChoice): LyricsDocumentInput = localCall()
            override suspend fun resolve(association: LyricsAssociation, generation: Long): LyricsDocumentInput = localCall()
            override suspend fun prepareAssociation(input: LyricsDocumentInput, confirmedSha256: String): PreparedLyricsAssociation = localCall()
        }, LyricsLoadAuthority(), { localCall() }, { _, _ -> localCall() }, playback,
        scopeOverride = scope, awaitDisplayFrame = {}, memoryOwner = memory, onlineLyrics = port ?: online)
    fun preview(): LyricsPlayPreview {
        val state = vm.uiState.value
        val document = (state.content as LyricsPageContent.Ready).document
        return LyricsPlayPreview(checkNotNull(state.identity), checkNotNull(document.timeline.seekTarget(0, OnlineEntryPlayback.DURATION_MS)),
            state.mapping, OnlineEntryPlayback.DURATION_MS)
    }
}

private class ControlledOnlineLyricsPort(private val dispatcher: kotlinx.coroutines.CoroutineDispatcher) : OnlineLyricsPort {
    val signals = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val changes: Flow<Unit> = signals
    val requests = ArrayList<OnlineLyricsRequest>()
    var validations = 0
    var cached = false
    var valid = true
    var beforeLoad: (suspend () -> Unit)? = null
    var beforeValidate: (suspend () -> Unit)? = null
    override suspend fun load(request: OnlineLyricsRequest, isCurrent: () -> Boolean): OnlineLyricsResult {
        requests += request
        beforeLoad?.invoke()
        val document = LocalLyricsParser(LyricsLimits(), dispatcher).parse("[00:01]first\n[00:02]second".toByteArray())
        val fence = OnlineLyricsFence(request.trackKey, request.queueGenerationId, request.queueRevision, request.queueEntryId,
            request.sourceGeneration, onlineOpaqueHash("netease:${request.accountId}"), 1L, OnlineLyricsOrigin.NETEASE_LRC)
        return OnlineLyricsResult.Loaded(fence, document, null, document.accountedResidentBytes + 1_024L, cached)
    }
    override suspend fun validate(loaded: OnlineLyricsResult.Loaded): Boolean {
        validations++
        beforeValidate?.invoke()
        return valid
    }
}

private class OnlineEntryQueues(val first: QueueEntry) : QueueStore {
    private val duplicate = first.copy(queueEntryId = "lyrics-entry-duplicate", originalOrder = 1L, playbackOrder = 1L)
    private val state = MutableStateFlow(QueueSnapshot("lyrics-queue-one", 3L, 0L, FrozenQueueView("lyrics-view-one", 1L, 2L),
        QueueMode(), QueueCheckpoint(first.queueEntryId, 0L), 0L, 0L))
    val current get() = state.value
    override suspend fun initialize() = current
    override suspend fun snapshot() = current
    override fun snapshots(): Flow<QueueSnapshot?> = state
    override suspend fun window(generationId: String, currentEntryId: String) = QueueWindow(generationId, current.queueRevision,
        2L, listOf(first, duplicate), if (currentEntryId == first.queueEntryId) 0 else 1)
    override suspend fun page(generationId: String, firstPlaybackOrder: Long, limit: Int): List<QueueEntry> = error("No full queue load")
    override suspend fun replace(request: QueueReplacementRequest, source: QueuePageSource,
        beforePublish: suspend (QueueWindow?) -> Unit): QueueCommit = error("No second queue owner")
    override suspend fun mutate(request: QueueMutationRequest, source: QueuePageSource?,
        beforePublish: suspend (QueueMutationCandidate) -> QueueCheckpoint?): QueueCommit = error("No second queue owner")
    override suspend fun saveCheckpoint(generationId: String, expectedCheckpointRevision: Long,
        checkpoint: QueueCheckpoint): QueueSnapshot = error("No second checkpoint owner")
}

private class OnlineEntryPlayback(private val queues: OnlineEntryQueues) : LyricsPlaybackPort {
    val signals = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val changes: Flow<Unit> = signals
    override val busy = false
    var playing = false
    var canSeek = true
    var backendEpoch = 0L
    var clockReads = 0
    var acquisitions = 0
    var originalTicket: QueueSeekBinding? = null
        private set
    val dispatched = ArrayList<QueueSeekRequest>()
    override fun clock(): LyricsMediaClock {
        clockReads++
        return LyricsMediaClock(checkNotNull(queues.current.currentEntryId), queues.current.positionMs, DURATION_MS, playing, 1f, canSeek)
    }
    override suspend fun acquireSeekBinding(): QueueSeekBinding {
        acquisitions++
        return QueueSeekBinding("original-ticket-$acquisitions", "original-session", backendEpoch, queues.current.generationId,
            queues.current.queueRevision, checkNotNull(queues.current.currentEntryId), null, 0L, DURATION_MS).also { originalTicket = it }
    }
    override fun isSeekBindingCurrent(binding: QueueSeekBinding): Boolean = binding === originalTicket &&
        binding.backendGeneration == backendEpoch && binding.queueEntryId == queues.current.currentEntryId &&
        binding.queueRevision == queues.current.queueRevision
    override suspend fun dispatchSeek(request: QueueSeekRequest): Boolean { dispatched += request; return true }
    companion object { const val DURATION_MS = 180_000L }
}
