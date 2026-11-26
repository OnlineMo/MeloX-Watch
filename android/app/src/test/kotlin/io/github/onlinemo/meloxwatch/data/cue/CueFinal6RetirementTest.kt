package io.github.onlinemo.meloxwatch.data.cue

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** 退休顺序可控；实际TREE Cursor/FD用例沿用生产Android路由。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CueFinal6RetirementTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test(timeout = 20_000L)
    fun concurrentPhysicalClosersPublishFailureAndSuccessInActualCloseOrder() = runBlocking {
        val io = LocalProviderIo()
        val input = RecordingInput().apply { releaseCancel.countDown() }
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val actionClosing = CountDownLatch(1)
        val original = IOException("Controlled first physical close failure")
        input.beforeClose = { attempt ->
            if (attempt == 1) {
                firstEntered.countDown()
                check(releaseFirst.await(10L, TimeUnit.SECONDS))
                throw original
            } else secondEntered.countDown()
        }
        val owner = CuePhysicalReadOwner(input, CancellationSignal())
        val operation = async(Dispatchers.Default) {
            runCatching { io.read(physicalOwner = owner) {
                owner.begin(1L)
                try {
                    input.initialize()
                    owner.requestCancellation()
                    check(firstEntered.await(2L, TimeUnit.SECONDS))
                } finally {
                    actionClosing.countDown();
                    owner.finish()
                }
            } }
        }
        try {
            await(actionClosing)
            assertFalse(secondEntered.await(300L, TimeUnit.MILLISECONDS))
            assertBusy(io)
            releaseFirst.countDown()
            val failure = checkNotNull(operation.await().exceptionOrNull())
            assertTrue(contains(failure, original))
            assertEquals(1, input.maximumCloseConcurrency.get())
            withTimeout(5000L) { io.awaitIdle() }
            assertFalse(owner.retainedAfterFailure)
            assertEquals(42, io.read { 42 })
        } finally {
            releaseFirst.countDown()
            operation.await()
            runCatching { withContext(Dispatchers.IO) { owner.finish() } }
        }
    }

    @Test(timeout = 30_000L)
    fun aLateRetirementDispatchRejectionDoesNotTurnClosedResourcesBackIntoOccupiedResources() = runBlocking {
        for (failPhysicalClose in listOf(false, true)) {
            val io = LocalProviderIo()
            val input = RecordingInput().apply { failClose.set(failPhysicalClose) }
            val actionEntered = CountDownLatch(1)
            val finishAction = CountDownLatch(1)
            val dispatchEntered = CountDownLatch(1)
            val releaseDispatch = CountDownLatch(1)
            val original = RejectedExecutionException("Controlled late retirement rejection")
            val dispatcher = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) {
                    dispatchEntered.countDown()
                    check(releaseDispatch.await(10L, TimeUnit.SECONDS))
                    throw original
                }
            }
            val owner = CuePhysicalReadOwner(input, CancellationSignal(), dispatcher)
            val operation = async(Dispatchers.Default) {
                runCatching { io.read(physicalOwner = owner) {
                    owner.begin(1L)
                    try {
                        input.initialize();
                        actionEntered.countDown();
                        check(finishAction.await(10L, TimeUnit.SECONDS))
                    }
                    finally { owner.finish() }
                } }
            }
            await(actionEntered)
            val cancellation = async(Dispatchers.Default) { owner.requestCancellation() }
            try {
                await(dispatchEntered)
                finishAction.countDown()
                await(input.closeReturned)
                assertEquals(!failPhysicalClose, input.closedSuccessfully.get())
                assertBusy(io)
                releaseDispatch.countDown()
                cancellation.await()
                val failure = checkNotNull(operation.await().exceptionOrNull())
                assertTrue(contains(failure, original))
                if (failPhysicalClose) {
                    assertTrue(owner.retainedAfterFailure)
                    assertBusy(io)
                    input.failClose.set(false)
                    runCatching { withContext(Dispatchers.IO) { owner.finish() } }
                }
                withTimeout(5000L) { io.awaitIdle() }
                assertFalse(owner.retainedAfterFailure)
                assertEquals(43, io.read { 43 })
            } finally {
                finishAction.countDown();
                releaseDispatch.countDown();
                input.failClose.set(false)
                cancellation.await();
                operation.await()
                runCatching { withContext(Dispatchers.IO) { owner.finish() } }
            }
        }
    }

    @Test(timeout = 90_000L)
    fun aLateQueryCursorStillClosesAfterAnEarlierEmptyPhysicalRetirement() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val fixture = CueLargeTreeFixture(context, temporary.newFolder("late-cursor"))
        fixture.open()
        try {
            val track = fixture.scan("cursor")[0]
            val reference = checkNotNull(track.cueReference)
            val binding = checkNotNull(reference.binding).audio
            val signal = CancellationSignal()
            val actual = AndroidCueOwnedInput(context, binding, signal)
            val inputClosed = CountDownLatch(1)
            val input = object : CueOwnedInput by actual {
                override fun close() {
                    actual.close();
                    inputClosed.countDown()
                }
            }
            val owner = CuePhysicalReadOwner(input, signal)
            val queryEntered = CountDownLatch(1)
            val releaseQuery = CountDownLatch(1)
            val failCursor = AtomicBoolean(true)
            val original = IOException("Controlled late cursor close failure")
            fixture.provider.beforeQueryDocument = { id ->
                if (id == "cursor-wave") {
                    queryEntered.countDown();
                    check(releaseQuery.await(10L, TimeUnit.SECONDS))
                }
            }
            fixture.provider.beforeCursorClose = { ids ->
                if (ids == listOf("cursor-wave") && failCursor.get()) throw original
            }
            val operation = async(Dispatchers.Default) {
                runCatching { fixture.io.read(signal, physicalOwner = owner) {
                    owner.begin(1L)
                    try {
                        val cursor = checkNotNull(context.contentResolver.query(Uri.parse(binding.location.uri),
                            null, null, null, null, signal))
                        owner.withCursor(cursor) { Unit }
                    } finally { owner.finish() }
                } }
            }
            try {
                await(queryEntered)
                owner.requestCancellation()
                await(inputClosed)
                assertBusy(fixture.io)
                releaseQuery.countDown()
                val failure = checkNotNull(operation.await().exceptionOrNull())
                assertTrue(contains(failure, original))
                assertTrue(owner.retainedAfterFailure)
                assertTrue(fixture.provider.activeCursors.get() > 0)
                assertBusy(fixture.io)
                failCursor.set(false)
                withContext(Dispatchers.IO) { owner.finish() }
                withTimeout(5000L) { fixture.io.awaitIdle() }
                assertEquals(0, fixture.provider.activeCursors.get())
                assertFalse(owner.retainedAfterFailure)
                assertEquals(44, fixture.io.read { 44 })
            } finally {
                failCursor.set(false);
                releaseQuery.countDown()
                operation.await()
                fixture.provider.beforeQueryDocument = null;
                fixture.provider.beforeCursorClose = null
                runCatching { withContext(Dispatchers.IO) { owner.finish() } }
            }
        } finally { fixture.close() }
    }

    @Test(timeout = 90_000L)
    fun aLateFileDescriptorCannotOutliveItsAcceptedSlot() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val fixture = CueLargeTreeFixture(context, temporary.newFolder("late-fd"))
        fixture.open()
        try {
            val track = fixture.scan("fd")[0]
            val reference = checkNotNull(track.cueReference)
            val originalToken = CueLargeTreeFixture.fileToken(fixture.file("fd"))
            val signal = CancellationSignal()
            val actual = AndroidCueOwnedInput(context, checkNotNull(reference.binding).audio, signal)
            val openEntered = CountDownLatch(1)
            val releaseOpen = CountDownLatch(1)
            val closeEntered = CountDownLatch(1)
            val releaseClose = CountDownLatch(1)
            val initializationExited = CountDownLatch(1)
            fixture.provider.beforeOpenDocument = { id ->
                if (id == "fd-wave") {
                    openEntered.countDown();
                    check(releaseOpen.await(10L, TimeUnit.SECONDS))
                }
            }
            val input = object : CueOwnedInput by actual {
                override fun close() {
                    closeEntered.countDown()
                    check(releaseClose.await(10L, TimeUnit.SECONDS))
                    actual.close()
                }
            }
            val owner = CuePhysicalReadOwner(input, signal)
            val operation = async(Dispatchers.Default) {
                runCatching { fixture.io.read(signal, physicalOwner = owner) {
                    owner.begin(CueReadLimits.waveProofWorkBytes(input.length))
                    try { input.initialize() }
                    finally {
                        initializationExited.countDown();
                        owner.finish()
                    }
                } }
            }
            try {
                await(openEntered)
                owner.requestCancellation()
                await(closeEntered)
                assertBusy(fixture.io)
                releaseOpen.countDown()
                await(initializationExited)
                val physical = fixture.provider.descriptors.last().fileDescriptor
                assertTrue(physical.valid())
                assertBusy(fixture.io)
                releaseClose.countDown()
                assertTrue(operation.await().isFailure)
                withTimeout(5000L) { fixture.io.awaitIdle() }
                assertFalse(physical.valid())
                assertFalse(owner.retainedAfterFailure)
                assertEquals(originalToken, CueLargeTreeFixture.fileToken(fixture.file("fd")))
                assertEquals(45, fixture.io.read { 45 })
            } finally {
                releaseOpen.countDown();
                releaseClose.countDown()
                operation.await();
                fixture.provider.beforeOpenDocument = null
                runCatching { withContext(Dispatchers.IO) { owner.finish() } }
            }
        } finally { fixture.close() }
    }

    @Test(timeout = 35_000L)
    fun anOriginalCancellationErrorArrivingAfterTheFiveSecondFailureStillReachesItsExactReceipt() = runBlocking {
        for (reportThrows in listOf(false, true)) {
            val io = LocalProviderIo()
            val original = IOException("Controlled cancellation error after five seconds")
            val reporting = IOException("Controlled failure reporting error")
            val input = RecordingInput().apply { cancelFailure = original }
            val dispatcher = TrackingRetirementDispatcher()
            val workerCloses = AtomicInteger()
            input.beforeClose = { if (dispatcher.isCurrentWorker()) workerCloses.incrementAndGet() }
            val owner = CuePhysicalReadOwner(input, CancellationSignal(), dispatcher)
            owner.requestCancellation()
            await(input.cancelEntered)
            val operation = async(Dispatchers.Default) {
                runCatching { io.read(physicalOwner = owner, failed = { if (reportThrows) throw reporting }) {
                    owner.begin(1L)
                    try { input.initialize() } finally { owner.finish() }
                } }
            }
            try {
                val failure = checkNotNull(withTimeout(8000L) { operation.await() }.exceptionOrNull())
                assertEquals(CueIssue.READ_CLOSE_FAILED, (failure as CueException).issue)
                assertFalse(contains(failure, original))
                if (reportThrows) assertTrue(contains(failure, reporting))
                assertTrue(owner.retainedAfterFailure)
                assertBusy(io)
                input.releaseCancel.countDown()
                await(dispatcher.completed)
                withTimeout(5000L) { io.awaitIdle() }
                assertTrue(contains(failure, original))
                assertTrue(input.closeThreads.contains(input.cancelThread.get()))
                assertEquals(1, workerCloses.get())
                assertFalse(owner.retainedAfterFailure)
                val replacementEntered = CountDownLatch(1)
                val releaseReplacement = CountDownLatch(1)
                val replacement = async(Dispatchers.Default) { io.read {
                    replacementEntered.countDown();
                    check(releaseReplacement.await(5L, TimeUnit.SECONDS));
                    46
                } }
                try {
                    await(replacementEntered)
                    repeat(2) {
                        owner.requestCancellation();
                        runCatching {
                            withContext(Dispatchers.IO) {
                                owner.finish()
                            }
                        }
                    }
                    assertBusy(io)
                    assertFalse(replacement.isCompleted)
                    assertTrue(contains(failure, original))
                } finally { releaseReplacement.countDown() }
                assertEquals(46, replacement.await())
                assertEquals(47, io.read { 47 })
            } finally {
                input.releaseCancel.countDown();
                operation.await()
            }
        }
    }

    @Test(timeout = 20_000L)
    fun throwingCancelStillAttemptsPhysicalCloseOnTheRetirementWorker() = runBlocking {
        val io = LocalProviderIo()
        val original = IOException("Controlled original worker cancellation error")
        val input = RecordingInput().apply {
            cancelFailure = original;
            failClose.set(true);
            releaseCancel.countDown()
        }
        val dispatcher = TrackingRetirementDispatcher()
        val workerCloses = AtomicInteger()
        input.beforeClose = { if (dispatcher.isCurrentWorker()) workerCloses.incrementAndGet() }
        val owner = CuePhysicalReadOwner(input, CancellationSignal(), dispatcher)
        val result = runCatching { io.read(physicalOwner = owner) {
            owner.begin(1L)
            try {
                input.initialize();
                owner.requestCancellation();
                await(input.cancelEntered)
            }
            finally { owner.finish() }
        } }
        await(dispatcher.completed)
        val failure = checkNotNull(result.exceptionOrNull())
        assertTrue(contains(failure, original))
        assertTrue(input.closeThreads.contains(input.cancelThread.get()))
        assertEquals(1, workerCloses.get())
        assertTrue(owner.retainedAfterFailure)
        assertFalse(input.closedSuccessfully.get())
        assertBusy(io)
        input.failClose.set(false)
        runCatching { withContext(Dispatchers.IO) { owner.finish() } }
        withTimeout(5000L) { io.awaitIdle() }
        assertFalse(owner.retainedAfterFailure)
        assertTrue(input.closedSuccessfully.get())
        assertEquals(48, io.read { 48 })
    }

    private fun await(latch: CountDownLatch) { assertTrue(latch.await(2L, TimeUnit.SECONDS)) }
    private fun assertBusy(io: LocalProviderIo) {
        val result = runCatching { runBlocking { io.read { throw AssertionError("A retained ticket must reject replacement") } } }
        assertEquals(SourceReadIssue.BUSY, (result.exceptionOrNull() as SourceReadException).issue)
    }
    private fun contains(root: Throwable, expected: Throwable): Boolean {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val pending = ArrayDeque<Throwable>().apply { add(root) }
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (!seen.add(next)) continue
            if (next === expected) return true
            next.cause?.let(pending::add)
            next.suppressed.forEach(pending::add)
        }
        return false
    }
    private class TrackingRetirementDispatcher : CoroutineDispatcher() {
        val completed = CountDownLatch(1)
        private val worker = ThreadLocal<Boolean>()
        fun isCurrentWorker(): Boolean = worker.get() == true
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            Dispatchers.IO.dispatch(context, Runnable {
                worker.set(true)
                try {
                    block.run()
                } finally {
                    worker.remove();
                    completed.countDown()
                }
            })
        }
    }
    private class RecordingInput : CueOwnedInput {
        val cancelEntered = CountDownLatch(1)
        val releaseCancel = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val cancelThread = AtomicReference<Thread?>()
        val closeThreads = CopyOnWriteArrayList<Thread>()
        val closedSuccessfully = AtomicBoolean()
        val failClose = AtomicBoolean()
        val maximumCloseConcurrency = AtomicInteger()
        private val activeCloses = AtomicInteger()
        private val closeAttempts = AtomicInteger()
        @Volatile var cancelFailure: IOException? = null
        @Volatile var beforeClose: ((Int) -> Unit)? = null
        override val length = 44L
        override fun initialize() = Unit
        override fun seek(position: Long) = Unit
        override fun read(buffer: ByteArray, offset: Int, count: Int) = -1
        override fun cancel() {
            cancelThread.set(Thread.currentThread());
            cancelEntered.countDown()
            check(releaseCancel.await(15L, TimeUnit.SECONDS))
            cancelFailure?.let { throw it }
        }
        override fun close() {
            val active = activeCloses.incrementAndGet()
            maximumCloseConcurrency.accumulateAndGet(active) { left, right -> maxOf(left, right) }
            closeThreads += Thread.currentThread()
            try {
                beforeClose?.invoke(closeAttempts.incrementAndGet())
                if (failClose.get()) throw IOException("Controlled retained physical close failure")
                closedSuccessfully.set(true)
            } finally {
                activeCloses.decrementAndGet();
                closeReturned.countDown()
            }
        }
    }
}
