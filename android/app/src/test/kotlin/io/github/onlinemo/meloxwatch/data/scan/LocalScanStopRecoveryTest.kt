package io.github.onlinemo.meloxwatch.data.scan

import android.content.Context
import android.database.sqlite.SQLiteFullException
import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.library.LibraryMutationStore
import io.github.onlinemo.meloxwatch.data.library.LibrarySource
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.library.RoomLocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.local.AndroidLocalSources
import io.github.onlinemo.meloxwatch.data.local.LocalCandidateCursor
import io.github.onlinemo.meloxwatch.data.local.LocalGrantObservation
import io.github.onlinemo.meloxwatch.data.local.LocalNodeKind
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.LocalSourceAccess
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceObservation
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Real Room rows and statement-stage faults; no device disk-full or provider support claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LocalScanStopRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var database: MeloXDatabase
    private lateinit var library: RoomLocalLibraryRepository
    private lateinit var driver: PhaseFaultDriver
    private lateinit var scope: CoroutineScope
    private lateinit var io: LocalProviderIo
    private lateinit var file: File
    private val escaped = ConcurrentLinkedQueue<Throwable>()
    private var pausedDispatcher: PausedDispatcher? = null
    private val gates = mutableListOf<ReadGate>()
    private val spec = LocalSourceSpec("scan-stop", LocalSourceKind.TREE, "content://scan-stop/tree/root",
        authority = "scan-stop", rootObjectId = "root")

    @Before fun create(): Unit = runBlocking {
        file = File(temporary.root, "scan-stop.db")
        driver = PhaseFaultDriver()
        database = Room.databaseBuilder(RuntimeEnvironment.getApplication() as Context, MeloXDatabase::class.java,
            file.absolutePath).setDriver(driver).build()
        library = RoomLocalLibraryRepository(database)
        library.initialize()
        io = LocalProviderIo()
        scope = newScope(Dispatchers.Default)
    }

    @After fun retire(): Unit = runBlocking {
        gates.forEach { it.release.countDown() }
        driver.releaseWrite.countDown()
        driver.failPhases = emptySet()
        scope.cancel()
        withTimeout(10_000L) {
            while (scope.coroutineContext[Job]!!.children.any { !it.isCompleted }) {
                pausedDispatcher?.drain()
                delay(1L)
            }
        }
        pausedDispatcher?.drain()
        database.close()
    }

    @Test(timeout = 30_000L)
    fun stoppingAndTerminalWriteFailuresRetainTheOriginalTokenUntilExplicitRecovery(): Unit = runBlocking {
        val fixture = fixture()
        seed(fixture)
        val gate = blockNextBatch(fixture.access)
        driver.failPhases = setOf(ScanPhase.STOPPING, ScanPhase.CANCELLED)
        val token = fixture.engine.start(currentSource(), library.rules().revision)
        try {
            assertTrue(gate.entered.await(5L, TimeUnit.SECONDS))
            expect(SourceReadIssue.STORAGE_FULL) { fixture.engine.cancel(token.scanId) }
            expect(SourceReadIssue.BUSY) { fixture.engine.retryStop(token.scanId) }
            expect(SourceReadIssue.BUSY) { io.read { Unit } }
            expect(SourceReadIssue.BUSY) { fixture.engine.start(currentSource(), library.rules().revision) }
            assertEquals(ScanPhase.STOPPING, fixture.engine.state.value?.phase)
            gate.release.countDown()
            joinScanJobs()
            assertEquals(ScanPhase.RUNNING, library.latestScan(spec.sourceId)?.phase)
            assertEquals(token, fixture.engine.state.value?.token)
            assertEquals(SourceReadIssue.STORAGE_FULL, fixture.engine.state.value?.issue)
            assertEquals(1L, library.overview().totalTracks)

            driver.failPhases = emptySet()
            fixture.engine.retryStop(token.scanId)
            assertEquals(library.latestScan(spec.sourceId), fixture.engine.state.value)
            assertEquals(ScanPhase.CANCELLED, fixture.engine.state.value?.phase)
            fixture.access.beforeBatch = null
            val next = fixture.engine.start(currentSource(), library.rules().revision)
            assertNotEquals(token.scanId, next.scanId)
            completed(fixture.engine, next)
        } finally { gate.release.countDown() }
    }

    @Test(timeout = 30_000L)
    fun aFailedTerminalCommitDoesNotPublishCancelledOrAbandonTheStoppingPointer(): Unit = runBlocking {
        val fixture = fixture()
        seed(fixture)
        val gate = blockNextBatch(fixture.access)
        driver.failPhases = setOf(ScanPhase.CANCELLED)
        val token = fixture.engine.start(currentSource(), library.rules().revision)
        try {
            assertTrue(gate.entered.await(5L, TimeUnit.SECONDS))
            fixture.engine.cancel(token.scanId)
            assertEquals(ScanPhase.STOPPING, library.latestScan(spec.sourceId)?.phase)
            gate.release.countDown()
            joinScanJobs()
            assertEquals(ScanPhase.STOPPING, fixture.engine.state.value?.phase)
            assertEquals(SourceReadIssue.STORAGE_FULL, fixture.engine.state.value?.issue)
            assertEquals(ScanPhase.STOPPING, library.latestScan(spec.sourceId)?.phase)
            expect(SourceReadIssue.BUSY) { fixture.engine.start(currentSource(), library.rules().revision) }
            driver.failPhases = emptySet()
            fixture.engine.retryStop(token.scanId)
            assertEquals(ScanPhase.CANCELLED, library.latestScan(spec.sourceId)?.phase)
            assertEquals(library.latestScan(spec.sourceId), fixture.engine.state.value)
            assertEquals(1L, library.overview().totalTracks)
        } finally { gate.release.countDown() }
    }

    @Test(timeout = 30_000L)
    fun backgroundErrorsStayObservableAndRecoveryKeepsTheOriginalPauseIntent(): Unit = runBlocking {
        val fixture = fixture()
        seed(fixture)
        val gate = blockNextBatch(fixture.access)
        driver.failPhases = setOf(ScanPhase.STOPPING, ScanPhase.PAUSED_BACKGROUND)
        val token = fixture.engine.start(currentSource(), library.rules().revision)
        try {
            assertTrue(gate.entered.await(5L, TimeUnit.SECONDS))
            fixture.engine.onBackground()
            gate.release.countDown()
            joinScanJobs()
            assertTrue(escaped.isEmpty())
            assertEquals(ScanPhase.STOPPING, fixture.engine.state.value?.phase)
            assertEquals(SourceReadIssue.STORAGE_FULL, fixture.engine.state.value?.issue)
            fixture.engine.onForeground()
            assertEquals(token, fixture.engine.state.value?.token)
            driver.failPhases = emptySet()
            fixture.engine.retryStop(token.scanId)
            assertEquals(ScanPhase.PAUSED_BACKGROUND, fixture.engine.state.value?.phase)
            assertEquals(library.latestScan(spec.sourceId), fixture.engine.state.value)
            assertEquals(1L, library.overview().totalTracks)
        } finally { gate.release.countDown() }
    }

    @Test(timeout = 30_000L)
    fun aNeverEnteredLazyScanRetainsItsAcceptedRoomTokenAfterTheTerminalFault(): Unit = runBlocking {
        scope.cancel()
        val dispatcher = PausedDispatcher().also { pausedDispatcher = it }
        scope = newScope(dispatcher)
        val fixture = fixture()
        driver.failPhases = setOf(ScanPhase.CANCELLED)
        val token = fixture.engine.start(currentSource(), library.rules().revision)
        fixture.engine.cancel(token.scanId)
        pumpUntil { driver.attempts(ScanPhase.CANCELLED) != 0 && fixture.engine.state.value?.issue == SourceReadIssue.STORAGE_FULL }
        dispatcher.drain()
        assertEquals(0, fixture.access.observations.get())
        assertEquals(0, fixture.access.opens.get())
        assertEquals(ScanPhase.STOPPING, fixture.engine.state.value?.phase)
        assertEquals(ScanPhase.STOPPING, library.latestScan(spec.sourceId)?.phase)
        expect(SourceReadIssue.BUSY) { fixture.engine.start(currentSource(), library.rules().revision) }
        driver.failPhases = emptySet()
        val recovered = async(Dispatchers.Default) { fixture.engine.retryStop(token.scanId) }
        pumpUntil { recovered.isCompleted }
        recovered.await()
        assertEquals(ScanPhase.CANCELLED, fixture.engine.state.value?.phase)
        val next = fixture.engine.start(currentSource(), library.rules().revision)
        assertNotEquals(token.scanId, next.scanId)
        pumpUntil { fixture.engine.state.value?.token == next && fixture.engine.state.value?.phase == ScanPhase.COMPLETED }
        assertTrue(escaped.isEmpty())
    }

    @Test(timeout = 30_000L)
    fun aCommittedTerminalWithALostReplyIsConfirmedFromTheRealRoomRow(): Unit = runBlocking {
        val lostReply = AtomicBoolean(true)
        val store = object : LibraryMutationStore by library {
            override suspend fun setScanState(token: ScanToken, phase: ScanPhase, issue: SourceReadIssue?): ScanState {
                val actual = library.setScanState(token, phase, issue)
                if (phase == ScanPhase.CANCELLED && lostReply.compareAndSet(true, false)) {
                    throw SourceReadException(SourceReadIssue.SOURCE_UNAVAILABLE)
                }
                return actual
            }
        }
        val fixture = fixture(store)
        val gate = blockNextBatch(fixture.access)
        val token = fixture.engine.start(currentSource(), library.rules().revision)
        try {
            assertTrue(gate.entered.await(5L, TimeUnit.SECONDS))
            fixture.engine.cancel(token.scanId)
            gate.release.countDown()
            joinScanJobs()
            assertFalse(lostReply.get())
            assertEquals(ScanPhase.CANCELLED, fixture.engine.state.value?.phase)
            assertEquals(library.latestScan(spec.sourceId), fixture.engine.state.value)
            fixture.access.beforeBatch = null
            val next = fixture.engine.start(currentSource(), library.rules().revision)
            assertNotEquals(token.scanId, next.scanId)
            completed(fixture.engine, next)
        } finally { gate.release.countDown() }
    }

    @Test(timeout = 30_000L)
    fun aRulesRevisionChangeAloneDoesNotAcknowledgeTheStillOccupiedActivePointer(): Unit = runBlocking {
        val fixture = fixture()
        val gate = blockNextBatch(fixture.access)
        driver.failPhases = setOf(ScanPhase.CANCELLED)
        val token = fixture.engine.start(currentSource(), library.rules().revision)
        try {
            assertTrue(gate.entered.await(5L, TimeUnit.SECONDS))
            fixture.engine.cancel(token.scanId)
            gate.release.countDown()
            joinScanJobs()
            val changed = library.setShortAudioFilter(library.rules().revision, true)
            assertNull(library.acknowledgeScanStop(token))
            expect(SourceReadIssue.BUSY) { fixture.engine.start(currentSource(), changed.revision) }
            expect(SourceReadIssue.STORAGE_FULL) { fixture.engine.retryStop(token.scanId) }
            assertEquals(token, fixture.engine.state.value?.token)
            driver.failPhases = emptySet()
            fixture.engine.retryStop(token.scanId)
            assertEquals(ScanPhase.CANCELLED, fixture.engine.state.value?.phase)
        } finally { gate.release.countDown() }
    }

    @Test(timeout = 30_000L)
    fun anActuallyRepairedDurablePointerCanRetireTheOldFenceAfterPhysicalExit(): Unit = runBlocking {
        val fixture = fixture()
        val gate = blockNextBatch(fixture.access)
        driver.failPhases = setOf(ScanPhase.CANCELLED)
        val token = fixture.engine.start(currentSource(), library.rules().revision)
        try {
            assertTrue(gate.entered.await(5L, TimeUnit.SECONDS))
            fixture.engine.cancel(token.scanId)
            gate.release.countDown()
            joinScanJobs()
            // A real external repair/restoration fault changes the pointer, never an in-memory mock.
            driver.open(file.absolutePath).use { connection ->
                connection.prepare("UPDATE local_sources SET activeScanId = NULL WHERE sourceId = ?").use { statement ->
                    statement.bindText(1, spec.sourceId)
                    statement.step()
                }
            }
            assertTrue(library.acknowledgeScanStop(token) is ScanStopAcknowledgement.ReplacedFence)
            fixture.engine.retryStop(token.scanId)
            assertEquals(ScanPhase.INTERRUPTED, fixture.engine.state.value?.phase)
            assertEquals(SourceReadIssue.SOURCE_CHANGED, fixture.engine.state.value?.issue)
            driver.failPhases = emptySet()
            fixture.access.beforeBatch = null
            val next = fixture.engine.start(currentSource(), library.rules().revision)
            assertNotEquals(token.scanId, next.scanId)
            completed(fixture.engine, next)
        } finally { gate.release.countDown() }
    }

    @Test(timeout = 30_000L)
    fun aRetryWaitTimeoutKeepsTheSingleAcceptedNativeWriterAndRejectsASecondAttempt(): Unit = runBlocking {
        val fixture = fixture()
        val gate = blockNextBatch(fixture.access)
        driver.failPhases = setOf(ScanPhase.CANCELLED)
        val token = fixture.engine.start(currentSource(), library.rules().revision)
        try {
            assertTrue(gate.entered.await(5L, TimeUnit.SECONDS))
            fixture.engine.cancel(token.scanId)
            gate.release.countDown()
            joinScanJobs()
            driver.failPhases = emptySet()
            driver.blockNextCancelled.set(true)
            val countBefore = driver.attempts(ScanPhase.CANCELLED)
            val retry = async(Dispatchers.Default) {
                expect(SourceReadIssue.BUSY) { fixture.engine.retryStop(token.scanId) }
            }
            assertTrue(driver.writeEntered.await(5L, TimeUnit.SECONDS))
            withTimeout(8_000L) { retry.await() }
            assertEquals(1L, driver.releaseWrite.count)
            assertEquals(countBefore + 1, driver.attempts(ScanPhase.CANCELLED))
            expect(SourceReadIssue.BUSY) { fixture.engine.retryStop(token.scanId) }
            expect(SourceReadIssue.BUSY) { fixture.engine.start(currentSource(), library.rules().revision) }
            assertEquals(countBefore + 1, driver.attempts(ScanPhase.CANCELLED))
            driver.releaseWrite.countDown()
            withTimeout(5_000L) { fixture.engine.state.filterNotNull().first { it.token == token && it.phase == ScanPhase.CANCELLED } }
            assertEquals(library.latestScan(spec.sourceId), fixture.engine.state.value)
        } finally { gate.release.countDown(); driver.releaseWrite.countDown() }
    }

    @Test(timeout = 30_000L)
    fun cancellingTheRetryCallerDoesNotCancelItsAlreadyAcceptedNativeWriter(): Unit = runBlocking {
        val fixture = fixture()
        val gate = blockNextBatch(fixture.access)
        driver.failPhases = setOf(ScanPhase.CANCELLED)
        val token = fixture.engine.start(currentSource(), library.rules().revision)
        try {
            assertTrue(gate.entered.await(5L, TimeUnit.SECONDS))
            fixture.engine.cancel(token.scanId)
            gate.release.countDown()
            joinScanJobs()
            driver.failPhases = emptySet()
            driver.blockNextCancelled.set(true)
            val before = driver.attempts(ScanPhase.CANCELLED)
            val caller = async(Dispatchers.Default) { fixture.engine.retryStop(token.scanId) }
            assertTrue(driver.writeEntered.await(5L, TimeUnit.SECONDS))
            caller.cancelAndJoin()
            assertTrue(caller.isCancelled)
            assertEquals(1L, driver.releaseWrite.count)
            expect(SourceReadIssue.BUSY) { fixture.engine.retryStop(token.scanId) }
            assertEquals(before + 1, driver.attempts(ScanPhase.CANCELLED))
            driver.releaseWrite.countDown()
            withTimeout(5_000L) { fixture.engine.state.filterNotNull().first { it.token == token && it.phase == ScanPhase.CANCELLED } }
            assertEquals(library.latestScan(spec.sourceId), fixture.engine.state.value)
            fixture.access.beforeBatch = null
            val next = fixture.engine.start(currentSource(), library.rules().revision)
            assertNotEquals(token.scanId, next.scanId)
            completed(fixture.engine, next)
        } finally { gate.release.countDown(); driver.releaseWrite.countDown() }
    }

    private fun newScope(dispatcher: CoroutineDispatcher) = CoroutineScope(SupervisorJob() + dispatcher +
        CoroutineExceptionHandler { _, failure -> escaped.add(failure) })

    private suspend fun currentSource(): LibrarySource = checkNotNull(library.source(spec.sourceId))

    private suspend fun fixture(store: LibraryMutationStore = library): Fixture {
        val access = Graph(spec)
        library.registerSource(spec, access.grant)
        val engine = LocalScanEngine(access, { LocalAudioMetadata(title = it.displayName, mimeType = "audio/mpeg") },
            store, io, scope, readContentToken = { "sha256:" + "1".repeat(64) })
        return Fixture(access, engine)
    }

    private suspend fun seed(fixture: Fixture) {
        completed(fixture.engine, fixture.engine.start(currentSource(), library.rules().revision))
        assertEquals(1L, library.overview().totalTracks)
    }

    private fun blockNextBatch(access: Graph): ReadGate = ReadGate().also { gate ->
        gates += gate
        access.beforeBatch = { io.read { gate.entered.countDown(); check(gate.release.await(12L, TimeUnit.SECONDS)) } }
    }

    private suspend fun joinScanJobs() {
        withTimeout(10_000L) { scope.coroutineContext[Job]!!.children.toList().joinAll() }
    }

    private suspend fun completed(engine: LocalScanEngine, token: ScanToken) {
        withTimeout(10_000L) { engine.state.filterNotNull().first { it.token == token && it.phase == ScanPhase.COMPLETED } }
        joinScanJobs()
    }

    private suspend fun pumpUntil(done: () -> Boolean) {
        withTimeout(10_000L) {
            while (!done()) { pausedDispatcher?.drain(); delay(1L) }
            pausedDispatcher?.drain()
        }
    }

    private suspend fun expect(issue: SourceReadIssue, action: suspend () -> Any?) {
        try { action(); throw AssertionError("Expected $issue") }
        catch (failure: SourceReadException) { assertEquals(issue, failure.issue) }
    }

    private data class Fixture(val access: Graph, val engine: LocalScanEngine)
    private class ReadGate(val entered: CountDownLatch = CountDownLatch(1), val release: CountDownLatch = CountDownLatch(1))

    private class PausedDispatcher : CoroutineDispatcher() {
        private val queued = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queued.add(block) }
        fun drain() { while (true) (queued.poll() ?: return).run() }
    }

    private class Graph(private val spec: LocalSourceSpec) : LocalSourceAccess {
        val grant = LocalGrantObservation(read = ObservedCapability.AVAILABLE, enumerate = ObservedCapability.AVAILABLE)
        val observations = AtomicInteger(0)
        val opens = AtomicInteger(0)
        var beforeBatch: (suspend () -> Unit)? = null
        private val root = node("root", null, LocalNodeKind.DIRECTORY)
        private fun node(id: String, parent: String?, kind: LocalNodeKind) = ObservedCandidate(spec.sourceId, id,
            "content://scan-stop/tree/root/document/$id", parent, if (kind == LocalNodeKind.AUDIO) "$id.mp3" else "Root",
            if (kind == LocalNodeKind.AUDIO) "audio/mpeg" else null, kind,
            sizeBytes = if (kind == LocalNodeKind.AUDIO) 123L else null, entityProof = "document:scan-stop:$id")
        override suspend fun observe(source: LocalSourceSpec): LocalSourceObservation {
            observations.incrementAndGet()
            return LocalSourceObservation(spec, AndroidLocalSources.sourceIdentity(spec), grant, root)
        }
        override suspend fun open(source: LocalSourceSpec, parentObjectId: String?, sinceMediaGeneration: Long?): LocalCandidateCursor {
            opens.incrementAndGet()
            return object : LocalCandidateCursor {
                override var exhausted = false
                override suspend fun nextBatch(limit: Int): List<ObservedCandidate> {
                    beforeBatch?.invoke()
                    exhausted = true
                    return listOf(node("one", "root", LocalNodeKind.AUDIO))
                }
                override fun close() { exhausted = true }
            }
        }
    }

    private class PhaseFaultDriver(private val delegate: SQLiteDriver = BundledSQLiteDriver()) : SQLiteDriver by delegate {
        @Volatile var failPhases: Set<ScanPhase> = emptySet()
        val blockNextCancelled = AtomicBoolean(false)
        val writeEntered = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        private val writes = ConcurrentHashMap<ScanPhase, AtomicInteger>()
        fun attempts(phase: ScanPhase): Int = writes[phase]?.get() ?: 0
        override fun open(fileName: String): SQLiteConnection {
            val connection = delegate.open(fileName)
            return object : SQLiteConnection by connection {
                override fun prepare(sql: String): SQLiteStatement {
                    val statement = connection.prepare(sql)
                    val text = sql.lowercase(Locale.ROOT).replace("`", "")
                    val scanUpdate = text.startsWith("update") && "scan_generations" in text
                    return object : SQLiteStatement by statement {
                        private var phase: ScanPhase? = null
                        override fun bindText(index: Int, value: String) {
                            if (scanUpdate) ScanPhase.entries.firstOrNull { it.name == value }?.let { phase = it }
                            statement.bindText(index, value)
                        }
                        override fun clearBindings() { phase = null; statement.clearBindings() }
                        override fun step(): Boolean {
                            phase?.let { current ->
                                writes.computeIfAbsent(current) { AtomicInteger(0) }.incrementAndGet()
                                if (current == ScanPhase.CANCELLED && blockNextCancelled.compareAndSet(true, false)) {
                                    writeEntered.countDown()
                                    check(releaseWrite.await(12L, TimeUnit.SECONDS))
                                }
                                if (current in failPhases) throw SQLiteFullException("Injected scan-state statement failure")
                            }
                            return statement.step()
                        }
                    }
                }
            }
        }
    }
}
