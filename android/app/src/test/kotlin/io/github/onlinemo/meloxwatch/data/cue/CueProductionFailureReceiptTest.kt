package io.github.onlinemo.meloxwatch.data.cue

import android.os.CancellationSignal
import io.github.onlinemo.meloxwatch.MeloXApplication
import io.github.onlinemo.meloxwatch.data.about.AppVersion
import io.github.onlinemo.meloxwatch.data.about.DiagnosticArea
import io.github.onlinemo.meloxwatch.data.about.DiagnosticCode
import io.github.onlinemo.meloxwatch.data.about.SafeDiagnosticsStore
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationPlaybackHost
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationsRuntime
import io.github.onlinemo.meloxwatch.data.local.LocalProviderFailureStage
import io.github.onlinemo.meloxwatch.data.local.LocalProviderFailureReceipt
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.LocalProviderReadOwner
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.network.JsonArray
import io.github.onlinemo.meloxwatch.data.network.JsonBounds
import io.github.onlinemo.meloxwatch.data.network.JsonObject
import io.github.onlinemo.meloxwatch.data.network.StrictJson
import io.github.onlinemo.meloxwatch.data.queue.QueueInputPage
import io.github.onlinemo.meloxwatch.data.queue.QueueReplacementRequest
import io.github.onlinemo.meloxwatch.data.queue.RoomQueueStore
import io.github.onlinemo.meloxwatch.data.scan.LocalScanEngine
import io.github.onlinemo.meloxwatch.data.scan.ScanPhase
import io.github.onlinemo.meloxwatch.data.scan.ScanState
import io.github.onlinemo.meloxwatch.data.scan.ScanToken
import io.github.onlinemo.meloxwatch.model.FrozenQueueView
import io.github.onlinemo.meloxwatch.model.LocalCueReference
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** 真实TREE、扫描器与Room调用者在异常路径结束后读取应用持有的回执。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE, application = MeloXApplication::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CueProductionFailureReceiptTest {
    @get:Rule val temporary = TemporaryFolder()
    private var scannerFileOperations: FileOperationsRuntime? = null

    @Test(timeout = 60_000L)
    fun scanConsumptionOfTheFiveSecondFailureKeepsLateOriginalEvidenceInTheApplicationReceipt() = runBlocking {
        val app = application()
        val enabled = AtomicBoolean()
        val controlled = AtomicReference<ControlledTreeInput?>()
        val original = IOException("Controlled original cancellation after scan consumption")
        val fixture = CueLargeTreeFixture(app, temporary.newFolder("scan-consumed"), cueInputs = { binding, signal ->
            val actual = AndroidCueOwnedInput(app, binding, signal)
            if (enabled.get() && binding.objectId == "consumed-cue") {
                ControlledTreeInput(actual, original, waitForDeadline = true).also(controlled::set)
            } else actual
        }, providerIo = app.localProviderIo)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        fixture.open()
        try {
            fixture.scan("consumed")
            val preserved = preserve(fixture, "consumed")
            enabled.set(true)
            val engine = scanner(fixture, scope)
            val source = checkNotNull(fixture.room.library.source("source-consumed"))
            val token = engine.start(source, fixture.room.library.rules().revision)
            val input = withTimeout(5000L) {
                while (controlled.get() == null) delay(10L)
                checkNotNull(controlled.get())
            }
            await(input.readEntered)
            // 取消由真实watchdog触发；物理取消worker的门独立保留。
            await(input.cancelEntered, 15L)
            await(input.closeReturned)
            val gateStarted = input.firstCloseNanos.get()
            withTimeout(8000L) { engine.state.filterNotNull().first { it.token == token && it.counters.failed == 1L } }
            assertTrue(System.nanoTime() - gateStarted >= TimeUnit.SECONDS.toNanos(5L))
            assertEquals(CueIssue.READ_TIMEOUT.name, checkNotNull(fixture.room.database.cueDao()
                .document(token.sourceId, "consumed-cue")).lastIssue)
            // 保存不透明ticket以核对身份；返回异常的持有者仍是应用回执。
            val exactTicket = checkNotNull(app.localProviderIo.peekReadFailure()).let { receipt ->
                assertFalse(receipt.physicallyRetired)
                assertTrue(contains(receipt.readFailure) { it is CueException && it.issue == CueIssue.READ_CLOSE_FAILED })
                assertNull(receipt.retirementFailure)
                assertNull(app.localProviderIo.consumeReadFailure(receipt.receipt))
                receipt.receipt.ticket
            }
            assertBusy(app.localProviderIo)
            input.releaseCancel.countDown()
            withTimeout(5000L) { app.localProviderIo.awaitIdle() }
            val terminal = terminal(engine, token)
            assertEquals(1L, terminal.counters.failed)
            assertFalse(terminal.coverage.permitsAbsence)
            awaitRetirementDiagnostic(app)
            val receipt = checkNotNull(app.localProviderIo.peekReadFailure())
            assertSame(exactTicket, receipt.receipt.ticket)
            assertSame(original, receipt.retirementFailure)
            assertTrue(receipt.physicallyRetired)
            assertEquals(51, app.localProviderIo.read { 51 })
            assertSame(receipt.receipt, checkNotNull(app.localProviderIo.peekReadFailure()).receipt)
            assertPreserved(fixture, "consumed", preserved)
            awaitConsumable(receipt.receipt)
            assertSame(original, checkNotNull(app.localProviderIo.consumeReadFailure(receipt.receipt)).retirementFailure)
            assertNull(app.localProviderIo.peekReadFailure())
        } finally {
            controlled.get()?.let {
                it.releaseRead.countDown()
                it.releaseCancel.countDown()
            }
            scope.cancel()
            withTimeout(10_000L) { app.localProviderIo.awaitIdle() }
            withTimeout(10_000L) { scannerFileOperations?.closeForOwner() }
            scannerFileOperations = null
            fixture.close()
        }
    }

    @Test(timeout = 45_000L)
    fun cancellingTheRealScanCallerDoesNotLoseTheInactiveContinuationsRetirementFailure() = runBlocking {
        val app = application()
        val enabled = AtomicBoolean()
        val controlled = AtomicReference<ControlledTreeInput?>()
        val original = IOException("Controlled original after inactive continuation")
        val fixture = CueLargeTreeFixture(app, temporary.newFolder("scan-cancelled"), cueInputs = { binding, signal ->
            val actual = AndroidCueOwnedInput(app, binding, signal)
            if (enabled.get() && binding.objectId == "cancelled-cue") {
                ControlledTreeInput(actual, original).also(controlled::set)
            } else actual
        }, providerIo = app.localProviderIo)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        fixture.open()
        try {
            fixture.scan("cancelled")
            val preserved = preserve(fixture, "cancelled")
            enabled.set(true)
            val engine = scanner(fixture, scope)
            val source = checkNotNull(fixture.room.library.source("source-cancelled"))
            val token = engine.start(source, fixture.room.library.rules().revision)
            val input = withTimeout(5000L) {
                while (controlled.get() == null) delay(10L)
                checkNotNull(controlled.get())
            }
            await(input.readEntered)
            engine.cancel(token.scanId)
            await(input.cancelEntered)
            input.releaseRead.countDown()
            await(input.closeReturned)
            withTimeout(8000L) { while (app.localProviderIo.peekReadFailure()?.readFailure == null) delay(10L) }
            assertEquals(ScanPhase.STOPPING, checkNotNull(engine.state.value).phase)
            assertBusy(app.localProviderIo)
            input.releaseCancel.countDown()
            withTimeout(5000L) { app.localProviderIo.awaitIdle() }
            assertEquals(ScanPhase.CANCELLED, terminal(engine, token).phase)
            awaitRetirementDiagnostic(app)
            val receipt = checkNotNull(app.localProviderIo.peekReadFailure())
            assertSame(original, receipt.retirementFailure)
            assertTrue(receipt.physicallyRetired)
            assertTrue(contains(receipt.readFailure) { it is CueException && it.issue == CueIssue.READ_CLOSE_FAILED })
            assertNull(checkNotNull(fixture.room.database.cueDao().document(token.sourceId, "cancelled-cue")).lastIssue)
            assertPreserved(fixture, "cancelled", preserved)
            assertEquals(52, app.localProviderIo.read { 52 })
            assertSame(receipt.receipt, checkNotNull(app.localProviderIo.peekReadFailure()).receipt)
        } finally {
            controlled.get()?.let {
                it.releaseRead.countDown()
                it.releaseCancel.countDown()
            }
            scope.cancel()
            withTimeout(10_000L) { app.localProviderIo.awaitIdle() }
            withTimeout(10_000L) { scannerFileOperations?.closeForOwner() }
            scannerFileOperations = null
            fixture.close()
        }
    }

    @Test(timeout = 45_000L)
    fun aSuccessfulNaturalFinishStillRecordsTheOriginalCancellationBeforeItsExactExitAck() = runBlocking {
        val app = application()
        val fixture = CueLargeTreeFixture(app, temporary.newFolder("natural-finish"), providerIo = app.localProviderIo)
        fixture.open()
        try {
            val track = fixture.scan("natural")[0]
            val preserved = preserve(fixture, "natural")
            val descriptor = fixture.room.library.resolveCueRead(track.trackKey, track.cueReference).audio
            val signal = CancellationSignal()
            val original = IOException("Controlled cancellation after successful natural finish")
            val input = ControlledTreeInput(AndroidCueOwnedInput(app, checkNotNull(track.cueReference?.binding).audio, signal),
                original, blockRead = false)
            val owner = CuePhysicalReadOwner(input, signal)
            val exposed = ForwardingOwner(owner, owner::requestCancellation)
            try {
                assertEquals(53, app.localProviderIo.read(signal, physicalOwner = exposed) {
                    owner.begin(checkNotNull(descriptor.sizeBytes))
                    try {
                        input.initialize()
                        assertEquals(descriptor.verifiedContentToken, CueCompleteContentProof.hash(owner.stream(),
                            checkNotNull(descriptor.sizeBytes), owner::checkpoint))
                        53
                    } finally { owner.finish() }
                })
                await(input.cancelEntered)
                assertTrue(input.closedSuccessfully.get())
                assertNull(app.localProviderIo.peekReadFailure())
                assertBusy(app.localProviderIo)
                input.releaseCancel.countDown()
                withTimeout(5000L) { app.localProviderIo.awaitIdle() }
                awaitRetirementDiagnostic(app)
                val receipt = checkNotNull(app.localProviderIo.peekReadFailure())
                assertNull(receipt.readFailure)
                assertSame(original, receipt.retirementFailure)
                assertFalse(owner.retainedAfterFailure)
                val replacementEntered = CountDownLatch(1)
                val releaseReplacement = CountDownLatch(1)
                val replacement = async(Dispatchers.Default) { app.localProviderIo.read {
                    replacementEntered.countDown()
                    check(releaseReplacement.await(5L, TimeUnit.SECONDS))
                    54
                } }
                try {
                    await(replacementEntered)
                    checkNotNull(exposed.exitAck.get()).invoke()
                    assertBusy(app.localProviderIo)
                    assertFalse(replacement.isCompleted)
                    assertSame(receipt.receipt, checkNotNull(app.localProviderIo.peekReadFailure()).receipt)
                } finally { releaseReplacement.countDown() }
                assertEquals(54, replacement.await())
                assertPreserved(fixture, "natural", preserved)
                awaitConsumable(receipt.receipt)
                assertSame(original, checkNotNull(app.localProviderIo.consumeReadFailure(receipt.receipt)).retirementFailure)
                assertNull(app.localProviderIo.peekReadFailure())
            } finally { input.releaseCancel.countDown() }
        } finally {
            withTimeout(10_000L) { app.localProviderIo.awaitIdle() }
            fixture.close()
        }
    }

    @Test(timeout = 45_000L)
    fun acceptedFacadeDispatchRejectionRetainsBothReadAndRetirementOriginalsWithoutRunningTheAction() = runBlocking {
        val app = application()
        val rejectNext = AtomicBoolean()
        val rejected = RejectedExecutionException("Controlled original read dispatcher rejection")
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (rejectNext.compareAndSet(true, false)) throw rejected
                Dispatchers.IO.dispatch(context, block)
            }
        }
        val io = LocalProviderIo(dispatcher) { stage -> recordStage(app, stage) }
        val enabled = AtomicBoolean()
        val controlled = AtomicReference<ControlledTreeInput?>()
        val original = IOException("Controlled original retirement following rejected read")
        val fixture = CueLargeTreeFixture(app, temporary.newFolder("rejected-read"), cueInputs = { binding, signal ->
            val actual = AndroidCueOwnedInput(app, binding, signal)
            if (enabled.get()) ControlledTreeInput(actual, original, blockRead = false).also(controlled::set) else actual
        }, providerIo = io)
        fixture.open()
        try {
            val track = fixture.scan("rejected")[0]
            val preserved = preserve(fixture, "rejected")
            val descriptor = fixture.room.library.resolveCueRead(track.trackKey, track.cueReference).audio
            enabled.set(true)
            rejectNext.set(true)
            assertSame(rejected, runCatching { fixture.sources.validateCueRead(descriptor) }.exceptionOrNull())
            val input = checkNotNull(controlled.get())
            await(input.cancelEntered)
            assertEquals(0, input.initializations.get())
            assertEquals(0, input.readCalls.get())
            assertFalse(input.closedSuccessfully.get())
            assertBusy(io)
            val ticket = checkNotNull(io.peekReadFailure()).let { receipt ->
                assertSame(rejected, receipt.readFailure)
                assertNull(receipt.retirementFailure)
                assertFalse(receipt.physicallyRetired)
                receipt.receipt.ticket
            }
            input.releaseCancel.countDown()
            withTimeout(5000L) { io.awaitIdle() }
            awaitRetirementDiagnostic(app)
            val receipt = checkNotNull(io.peekReadFailure())
            assertSame(ticket, receipt.receipt.ticket)
            assertSame(rejected, receipt.readFailure)
            assertSame(original, receipt.retirementFailure)
            assertTrue(input.closedSuccessfully.get())
            assertEquals(0, input.initializations.get())
            assertEquals(0, input.readCalls.get())
            assertEquals(55, io.read { 55 })
            val ordinary = IOException("Controlled ordinary null-owner failure")
            val ordinaryResult = runCatching { io.read<Unit> { throw ordinary } }.exceptionOrNull() as SourceReadException
            assertEquals(SourceReadIssue.SOURCE_UNAVAILABLE, ordinaryResult.issue)
            assertSame(ordinary, ordinaryResult.cause)
            assertSame(receipt.receipt, checkNotNull(io.peekReadFailure()).receipt)
            assertPreserved(fixture, "rejected", preserved)
        } finally {
            controlled.get()?.releaseCancel?.countDown()
            withTimeout(10_000L) { io.awaitIdle() }
            io.closeFailureReceipts()
            fixture.close()
        }
    }

    @Test(timeout = 60_000L)
    fun notificationFailureCannotConsumeAnUnfinishedReceiptOrOverwriteTheNextTicketAndExportsStayRedacted() = runBlocking {
        val app = application()
        val notificationEntered = CountDownLatch(1)
        val releaseNotification = CountDownLatch(1)
        val notifications = AtomicInteger()
        val reporting = IOException("Controlled reporting failure")
        val io = LocalProviderIo(Dispatchers.IO) { stage ->
            recordStage(app, stage)
            if (stage == LocalProviderFailureStage.READ && notifications.incrementAndGet() == 1) {
                notificationEntered.countDown()
                check(releaseNotification.await(15L, TimeUnit.SECONDS))
                throw reporting
            }
        }
        val fixture = CueLargeTreeFixture(app, temporary.newFolder("notify-and-redact"), providerIo = io)
        fixture.open()
        try {
            val track = fixture.scan("notify")[0]
            val preserved = preserve(fixture, "notify")
            val binding = checkNotNull(track.cueReference?.binding).audio
            val firstSignal = CancellationSignal()
            val firstOwner = CuePhysicalReadOwner(AndroidCueOwnedInput(app, binding, firstSignal), firstSignal)
            val exposed = ForwardingOwner(firstOwner)
            val secretMarkers = listOf("private-track-marker", "private-grant-marker", "content://receipt-fixture/private-marker")
            val original = IOException(secretMarkers.joinToString(" "))
            val first = async(Dispatchers.Default) { runCatching { io.read(firstSignal, physicalOwner = exposed) {
                firstOwner.begin(1L)
                try {
                    firstOwner.input.initialize()
                    throw original
                } finally { firstOwner.finish() }
            } } }
            await(notificationEntered)
            val old = checkNotNull(io.peekReadFailure())
            assertTrue(old.physicallyRetired)
            assertSame(original, old.readFailure)
            assertNull(io.consumeReadFailure(old.receipt))
            assertEquals(56, io.read { 56 })
            assertSame(old.receipt, checkNotNull(io.peekReadFailure()).receipt)
            val nextSignal = CancellationSignal()
            val nextOwner = CuePhysicalReadOwner(AndroidCueOwnedInput(app, binding, nextSignal), nextSignal)
            val nextEntered = CountDownLatch(1)
            val releaseNext = CountDownLatch(1)
            val nextOriginal = IOException("Controlled original of the next operation")
            val next = async(Dispatchers.Default) { runCatching { io.read(nextSignal, physicalOwner = nextOwner) {
                nextOwner.begin(1L)
                try {
                    nextOwner.input.initialize()
                    nextEntered.countDown()
                    check(releaseNext.await(10L, TimeUnit.SECONDS))
                    throw nextOriginal
                } finally { nextOwner.finish() }
            } } }
            try {
                await(nextEntered)
                checkNotNull(exposed.exitAck.get()).invoke()
                checkNotNull(exposed.retirementReport.get()).invoke(IOException("Controlled old-ticket late outcome"))
                assertBusy(io)
                assertSame(old.receipt, checkNotNull(io.peekReadFailure()).receipt)
            } finally { releaseNext.countDown() }
            next.await()
            val latest = checkNotNull(io.peekReadFailure())
            assertSame(nextOriginal, latest.readFailure)
            assertNull(io.consumeReadFailure(old.receipt))
            releaseNotification.countDown()
            first.await()
            awaitConsumable(old.receipt)
            assertTrue(contains(original) { it === reporting })
            assertFalse(contains(nextOriginal) { it === reporting })
            assertSame(latest.receipt, checkNotNull(io.peekReadFailure()).receipt)
            repeat(8) { index ->
                val signal = CancellationSignal()
                val owner = CuePhysicalReadOwner(AndroidCueOwnedInput(app, binding, signal), signal)
                val failure = IOException("Controlled bounded receipt replacement $index")
                runCatching { io.read<Unit>(signal, physicalOwner = owner) {
                    owner.begin(1L)
                    try {
                        owner.input.initialize()
                        throw failure
                    } finally { owner.finish() }
                } }
                assertSame(failure, checkNotNull(io.peekReadFailure()).readFailure)
                assertNull(io.consumeReadFailure(latest.receipt))
            }
            val final = checkNotNull(io.peekReadFailure())
            assertTrue(final.physicallyRetired)
            awaitConsumable(final.receipt)
            assertSame(final.readFailure, checkNotNull(io.consumeReadFailure(final.receipt)).readFailure)
            checkNotNull(exposed.retirementReport.get()).invoke(IOException("Controlled stale after explicit consumption"))
            assertNull(io.peekReadFailure())
            awaitRetirementDiagnostic(app)
            val export = app.diagnostics.prepareExport(AppVersion("0.1.0", 7))
            try {
                val bytes = export.file.readBytes()
                val root = StrictJson.decode(bytes, JsonBounds(SafeDiagnosticsStore.MAX_BYTES)) as JsonObject
                assertEquals(setOf("schema_version", "data", "data_sha256", "complete"), root.fields.keys)
                val data = root["data"] as JsonObject
                assertEquals(setOf("app_version", "app_version_code", "events"), data.fields.keys)
                (data["events"] as JsonArray).values.forEach { event ->
                    assertEquals(setOf("area", "code", "epoch_minute"), (event as JsonObject).fields.keys)
                }
                secretMarkers.forEach { assertFalse(bytes.toString(Charsets.UTF_8).contains(it)) }
                assertFalse(bytes.toString(Charsets.UTF_8).contains("disc.wav"))
                assertFalse(old.toString().contains(original.message!!))
                assertFalse(old.receipt.toString().contains(original.message!!))
            } finally { app.diagnostics.discard(export) }
            io.closeFailureReceipts()
            checkNotNull(exposed.retirementReport.get()).invoke(IOException("Controlled disposed late outcome"))
            assertNull(io.peekReadFailure())
            assertEquals(57, io.read { 57 })
            assertPreserved(fixture, "notify", preserved)
        } finally {
            releaseNotification.countDown()
            withTimeout(10_000L) { io.awaitIdle() }
            io.closeFailureReceipts()
            fixture.close()
        }
    }

    @Test(timeout = 60_000L)
    fun consumedAndDisposedReceiptsStayTombstonedWhenARealRetirementDispatcherRejectsAfterItsWorkerExit() = runBlocking {
        val app = application()
        val fixture = CueLargeTreeFixture(app, temporary.newFolder("late-dispatch-tombstone"))
        fixture.open()
        try {
            val track = fixture.scan("tombstone")[0]
            val preserved = preserve(fixture, "tombstone")
            val binding = checkNotNull(track.cueReference?.binding).audio
            for (dispose in listOf(false, true)) {
                val io = LocalProviderIo()
                val workerExited = CountDownLatch(1)
                val dispatcherWaiting = CountDownLatch(1)
                val releaseDispatch = CountDownLatch(1)
                val lateOriginal = RejectedExecutionException("Controlled original after real retirement worker exit")
                val retirementDispatcher = object : CoroutineDispatcher() {
                    override fun dispatch(context: CoroutineContext, block: Runnable) {
                        Dispatchers.IO.dispatch(context, Runnable { try { block.run() } finally { workerExited.countDown() } })
                        check(workerExited.await(5L, TimeUnit.SECONDS))
                        dispatcherWaiting.countDown()
                        check(releaseDispatch.await(10L, TimeUnit.SECONDS))
                        throw lateOriginal
                    }
                }
                val signal = CancellationSignal()
                val owner = CuePhysicalReadOwner(AndroidCueOwnedInput(app, binding, signal), signal, retirementDispatcher)
                val actionEntered = CountDownLatch(1)
                val releaseAction = CountDownLatch(1)
                val readOriginal = IOException("Controlled original before explicit receipt completion")
                val operation = async(Dispatchers.Default) { runCatching { io.read<Unit>(signal, physicalOwner = owner) {
                    owner.begin(1L)
                    try {
                        owner.input.initialize()
                        actionEntered.countDown()
                        check(releaseAction.await(10L, TimeUnit.SECONDS))
                        throw readOriginal
                    } finally { owner.finish() }
                } } }
                await(actionEntered)
                val cancellation = async(Dispatchers.Default) { owner.requestCancellation() }
                try {
                    await(dispatcherWaiting)
                    releaseAction.countDown()
                    operation.await()
                    withTimeout(5000L) { io.awaitIdle() }
                    val receipt = checkNotNull(io.peekReadFailure())
                    assertSame(readOriginal, receipt.readFailure)
                    assertNull(receipt.retirementFailure)
                    if (dispose) io.closeFailureReceipts() else {
                        awaitConsumable(receipt.receipt)
                        assertSame(readOriginal, checkNotNull(io.consumeReadFailure(receipt.receipt)).readFailure)
                    }
                    assertNull(io.peekReadFailure())
                    releaseDispatch.countDown()
                    cancellation.await()
                    assertNull(io.peekReadFailure())
                    assertFalse(owner.retainedAfterFailure)
                    assertEquals(58, io.read { 58 })
                } finally {
                    releaseAction.countDown()
                    releaseDispatch.countDown()
                    cancellation.await()
                    operation.await()
                    io.closeFailureReceipts()
                }
            }
            assertPreserved(fixture, "tombstone", preserved)
        } finally { fixture.close() }
    }

    private suspend fun application(): MeloXApplication {
        val app = RuntimeEnvironment.getApplication() as MeloXApplication
        app.requireBackupStorageReady()
        withTimeout(5000L) { app.localProviderIo.awaitIdle() }
        return app
    }

    /** 使用真实文件运行时的同一 mutation 门面，覆盖物理 IO 未退休时的失败结清。 */
    private suspend fun scanner(fixture: CueLargeTreeFixture, scope: CoroutineScope): LocalScanEngine {
        check(scannerFileOperations == null)
        val app = fixture.context.applicationContext as MeloXApplication
        lateinit var engine: LocalScanEngine
        val files = FileOperationsRuntime(
            fixture.context,
            fixture.room.library,
            RoomQueueStore(fixture.room.database),
            fixture.io,
            FileOperationPlaybackHost(),
            cancelScan = { scanId -> engine.cancelAndAwait(scanId) },
            validateRead = fixture.sources::validateRead,
            requireFileMutationReady = app.backupMutationBarrier::requireReadyForFileMutation,
            progressScope = scope,
        )
        scannerFileOperations = files
        files.initialize()
        val reader = CueScanReader(files.cueMutations, CuePathMatcher(fixture.sources), AndroidCueLocalDocuments(fixture.sources))
        engine = LocalScanEngine(fixture.sources, { LocalAudioMetadata(title = "Whole file",
            durationMs = fixture.facts.durationUs / 1000L, mimeType = "audio/wav") }, files.mutations, fixture.io, scope,
            fixture.metadata::contentToken, fixture.sources::verifyObservedCandidate, reader::read)
        return engine
    }

    private suspend fun terminal(engine: LocalScanEngine, token: ScanToken): ScanState = withTimeout(10_000L) {
        engine.state.filterNotNull().first { it.token == token && it.phase in setOf(ScanPhase.COMPLETED, ScanPhase.FAILED, ScanPhase.CANCELLED) }
    }

    private suspend fun preserve(fixture: CueLargeTreeFixture, root: String): Preserved {
        val segments = fixture.room.tracks().filter { it.cueReference != null }
        val queues = RoomQueueStore(fixture.room.database)
        queues.initialize()
        val entries = segments.mapIndexed { index, track -> fixture.entry(checkNotNull(track.cueReference))
            .copy(originalOrder = index.toLong(), playbackOrder = index.toLong()) }
        val view = FrozenQueueView("receipt-$root", 0L, entries.size.toLong())
        val queue = queues.replace(QueueReplacementRequest("receipt-queue-$root", null, 0L, view), { offset, limit ->
            QueueInputPage(view, offset, entries.drop(offset.toInt()).take(limit))
        }).snapshot
        val document = checkNotNull(fixture.room.database.cueDao().document("source-$root", "$root-cue"))
        return Preserved(segments.map { it.trackKey to it.cueReference }, fixture.room.database.cueDao().currentGroups(document.cueId),
            queue, queues.page(queue.generationId, 0L), CueLargeTreeFixture.fileToken(checkNotNull(fixture.provider.documents["$root-cue"])),
            CueLargeTreeFixture.fileToken(fixture.file(root)))
    }

    private suspend fun assertPreserved(fixture: CueLargeTreeFixture, root: String, before: Preserved) {
        assertEquals(before.segments, fixture.room.tracks().filter { it.cueReference != null }.map { it.trackKey to it.cueReference })
        val document = checkNotNull(fixture.room.database.cueDao().document("source-$root", "$root-cue"))
        assertEquals(before.groups, fixture.room.database.cueDao().currentGroups(document.cueId))
        val queues = RoomQueueStore(fixture.room.database)
        assertEquals(before.queue, queues.snapshot())
        assertEquals(before.entries, queues.page(before.queue.generationId, 0L))
        assertEquals(before.cueToken, CueLargeTreeFixture.fileToken(checkNotNull(fixture.provider.documents["$root-cue"])))
        assertEquals(before.waveToken, CueLargeTreeFixture.fileToken(fixture.file(root)))
        assertEquals(0, fixture.provider.activeCursors.get())
    }

    private class Preserved(val segments: List<Pair<TrackKey, LocalCueReference?>>, val groups: List<CueGroupVersionEntity>,
        val queue: QueueSnapshot, val entries: List<QueueEntry>, val cueToken: String, val waveToken: String)

    private fun recordStage(app: MeloXApplication, stage: LocalProviderFailureStage) {
        app.recordDiagnostic(DiagnosticArea.LIBRARY, when (stage) {
            LocalProviderFailureStage.READ -> DiagnosticCode.INTERNAL
            LocalProviderFailureStage.RETIREMENT -> DiagnosticCode.RECOVERY_REQUIRED
        })
    }

    private suspend fun awaitRetirementDiagnostic(app: MeloXApplication) = withTimeout(5000L) {
        while (app.diagnostics.events().none { it.area == DiagnosticArea.LIBRARY && it.code == DiagnosticCode.RECOVERY_REQUIRED }) delay(25L)
    }

    private suspend fun awaitConsumable(receipt: LocalProviderFailureReceipt) = withTimeout(5000L) {
        while (!receipt.canConsume()) delay(10L)
    }

    private fun await(latch: CountDownLatch, seconds: Long = 2L) { assertTrue(latch.await(seconds, TimeUnit.SECONDS)) }
    private fun assertBusy(io: LocalProviderIo) {
        val failure = runCatching { runBlocking { io.read { throw AssertionError("Occupied physical slot admitted a replacement") } } }
            .exceptionOrNull() as SourceReadException
        assertEquals(SourceReadIssue.BUSY, failure.issue)
    }

    private fun contains(root: Throwable?, matches: (Throwable) -> Boolean): Boolean {
        if (root == null) return false
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val pending = ArrayDeque<Throwable>().apply { add(root) }
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (!seen.add(next)) continue
            if (matches(next)) return true
            next.cause?.let(pending::add)
            next.suppressed.forEach(pending::add)
        }
        return false
    }

    private class ForwardingOwner(private val owner: CuePhysicalReadOwner, private val beforeExit: () -> Unit = {}) : LocalProviderReadOwner {
        val exitAck = AtomicReference<(() -> Unit)?>()
        val retirementReport = AtomicReference<((Throwable) -> Unit)?>()
        override val retainedAfterFailure get() = owner.retainedAfterFailure
        override fun requestCancellation() = owner.requestCancellation()
        override fun bindRetirementFailure(report: (Throwable) -> Unit) {
            retirementReport.set(report)
            owner.bindRetirementFailure(report)
        }
        override fun operationExited(actualRetired: () -> Unit): Boolean {
            exitAck.set(actualRetired)
            beforeExit()
            return owner.operationExited(actualRetired)
        }
    }

    private class ControlledTreeInput(private val actual: AndroidCueOwnedInput, private val cancellationFailure: Throwable,
        private val waitForDeadline: Boolean = false, private val blockRead: Boolean = true) : CueOwnedInput by actual {
        val readEntered = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val cancelEntered = CountDownLatch(1)
        val releaseCancel = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val firstCloseNanos = AtomicLong()
        val closedSuccessfully = AtomicBoolean()
        val initializations = AtomicInteger()
        val readCalls = AtomicInteger()
        override fun initialize() {
            initializations.incrementAndGet()
            actual.initialize()
        }
        override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
            val result = actual.read(buffer, offset, count)
            if (readCalls.incrementAndGet() == 1 && blockRead) {
                readEntered.countDown()
                check((if (waitForDeadline) cancelEntered else releaseRead).await(20L, TimeUnit.SECONDS))
            }
            return result
        }
        override fun cancel() {
            cancelEntered.countDown()
            check(releaseCancel.await(20L, TimeUnit.SECONDS))
            throw cancellationFailure
        }
        override fun close() {
            actual.close()
            closedSuccessfully.set(true)
            firstCloseNanos.compareAndSet(0L, System.nanoTime())
            closeReturned.countDown()
        }
    }
}
