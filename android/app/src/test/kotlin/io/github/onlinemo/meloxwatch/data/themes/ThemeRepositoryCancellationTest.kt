package io.github.onlinemo.meloxwatch.data.themes

import android.os.CancellationSignal
import io.github.onlinemo.meloxwatch.data.fonts.DecodedLyricsFont
import io.github.onlinemo.meloxwatch.data.fonts.FilesOnlyFontDecoder
import io.github.onlinemo.meloxwatch.data.fonts.FontWeightCapability
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontDecoder
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontRole
import io.github.onlinemo.meloxwatch.data.fonts.SfntFontInfo
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.themes.ThemeTestFixtures as Fixture
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CopyableThrowable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** latch / 受控结果调度证明物理通道寿命；不靠 sleep，也不把 fake 字体验证当正常原生验收。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class ThemeRepositoryCancellationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test(timeout = 30000L) fun ignoringReadCancellationKeepsBothOwnersBusyUntilTheRealWorkerEnds() = runBlocking {
        withTimeout(15000) {
            val io = LocalProviderIo()
            val source = BlockingThemeSource(Fixture.packageOf().zip(), blockClose = false)
            val directory = temporary.newFolder()
            val repository = Fixture.repository(directory, source, io)
            val caller = async(Dispatchers.Default) { repository.prepareImport(Fixture.request()) }
            try {
                awaitThemeLatch(source.entered)
                caller.cancel()
                assertTrue(caller.isCancelled)
                assertFalse(caller.isCompleted)
                assertEquals(0, source.descriptorCloses.get())
                assertTrue(checkNotNull(File(directory, "staging").list()).isNotEmpty())
                Fixture.expectSuspending(ThemeIssue.BUSY) { repository.prepareImport(Fixture.request()) }
                try {
                    io.read { throw AssertionError("A second physical source must not enter") }
                    throw AssertionError("Expected occupied physical channel")
                } catch (error: SourceReadException) {
                    assertEquals(SourceReadIssue.BUSY, error.issue)
                }
                assertEquals(1, source.opens.get())
                source.release.countDown()
                io.awaitIdle()
                caller.join()
                assertTrue(source.inputCloses.get() >= 1)
                assertEquals(1, source.descriptorCloses.get())
                assertNull(repository.preview.value)
                assertTrue(checkNotNull(File(directory, "staging").list()).isEmpty())
                assertEquals(0L, repository.read().revision)
            } finally {
                source.release.countDown()
                caller.cancelAndJoin()
                io.awaitIdle()
            }
        }
    }

    @Test(timeout = 30000L) fun aBlockedFinalCloseKeepsItsCompleteInputUntilCloseAndDescriptorRetirementFinish() = runBlocking {
        withTimeout(15000) {
            val io = LocalProviderIo()
            val original = Fixture.packageOf().zip()
            val source = BlockingThemeSource(original, blockClose = true)
            val directory = temporary.newFolder()
            val repository = Fixture.repository(directory, source, io)
            val caller = async(Dispatchers.Default) { repository.prepareImport(Fixture.request()) }
            try {
                awaitThemeLatch(source.entered)
                val stage = checkNotNull(File(directory, "staging").listFiles()).single()
                assertArrayEquals(original, File(stage, "input.zip").readBytes())
                caller.cancel()
                assertTrue(caller.isCancelled)
                assertFalse(caller.isCompleted)
                assertEquals(0, source.descriptorCloses.get())
                assertEquals(0, source.inputCloses.get())
                assertTrue(stage.isDirectory)
                Fixture.expectSuspending(ThemeIssue.BUSY) { repository.read() }
                source.release.countDown()
                io.awaitIdle()
                caller.join()
                assertEquals(1, source.descriptorCloses.get())
                assertEquals(1, source.inputCloses.get())
                assertFalse(stage.exists())
                assertNull(repository.preview.value)
            } finally {
                source.release.countDown()
                caller.cancelAndJoin()
                io.awaitIdle()
            }
        }
    }

    @Test(timeout = 30000L) fun anIgnoredNativeCancellationCannotDeliverOrOverlapItsCandidate() = runBlocking {
        withTimeout(15000) {
            val font = Fixture.font()
            val value = Fixture.packageOf(assets = listOf(font), fonts = mapOf("original" to font.id))
            val source = BytesThemeSource(value.zip())
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val calls = AtomicInteger()
            val delegate = FilesOnlyFontDecoder()
            val decoder = object : LyricsFontDecoder {
                override fun validate(file: File, info: SfntFontInfo, checkCancellation: () -> Unit): FontWeightCapability {
                    calls.incrementAndGet()
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    return delegate.validate(file, info, {})
                }
                override fun decode(file: File, info: SfntFontInfo, capability: FontWeightCapability, requested: Float?): DecodedLyricsFont =
                    delegate.decode(file, info, capability, requested)
            }
            val directory = temporary.newFolder()
            val io = LocalProviderIo()
            val repository = Fixture.repository(directory, source, io, validator = ThemeAssetValidator(decoder))
            val caller = async(Dispatchers.Default) { repository.prepareImport(Fixture.request()) }
            try {
                awaitThemeLatch(entered)
                assertEquals(1, source.descriptorCloses.get())
                caller.cancel()
                assertTrue(caller.isCancelled)
                assertFalse(caller.isCompleted)
                Fixture.expectSuspending(ThemeIssue.BUSY) { repository.prepareImport(Fixture.request()) }
                assertEquals(1, calls.get())
                assertNull(repository.preview.value)
                release.countDown()
                io.awaitIdle()
                caller.join()
                assertTrue(checkNotNull(File(directory, "staging").list()).isEmpty())
                assertEquals(ThemeBuiltins.defaultChoice, repository.read().selected)
            } finally {
                release.countDown()
                caller.cancelAndJoin()
                io.awaitIdle()
            }
        }
    }

    @Test(timeout = 30000L) fun cancellationBeforePreviewDeliveryRetiresOnlyItsExactTokenAndNextReadCleansItsStage() = runBlocking {
        withTimeout(15000) {
            val dispatcher = ThemeDeliveryIoDispatcher()
            val io = LocalProviderIo(dispatcher)
            val source = BytesThemeSource(Fixture.packageOf().zip())
            val directory = temporary.newFolder()
            val repository = Fixture.repository(directory, source, io)
            val scheduler = TestCoroutineScheduler()
            val scope = CoroutineScope(Job() + StandardTestDispatcher(scheduler))
            val workerDone = CountDownLatch(1)
            dispatcher.arm(workerDone)
            val caller = scope.async { repository.prepareImport(Fixture.request()) }
            try {
                scheduler.runCurrent()
                awaitThemeLatch(workerDone)
                assertFalse(caller.isCompleted)
                val notDelivered = checkNotNull(repository.preview.value)
                assertTrue(File(directory, "staging/${notDelivered.token}").isDirectory)
                caller.cancel()
                scheduler.runCurrent()
                caller.join()
                assertNull(repository.preview.value)
                repository.read()
                assertFalse(File(directory, "staging/${notDelivered.token}").exists())
                val fresh = repository.prepareImport(Fixture.request())
                assertNotEquals(notDelivered.token, fresh.token)
                assertSame(fresh, repository.preview.value)
                repository.discardPreview(fresh.token)
                assertEquals(0L, repository.read().revision)
            } finally {
                scope.cancel()
                scheduler.runCurrent()
                io.awaitIdle()
            }
        }
    }

    @Test(timeout = 30000L) fun fontConsumerReceivesTheSameSignalAndLateCancellationCannotReturnItsOldResult() = runBlocking {
        withTimeout(15000) {
            val font = Fixture.font()
            val value = Fixture.packageOf(assets = listOf(font), fonts = mapOf("original" to font.id))
            val io = LocalProviderIo()
            val repository = Fixture.repository(temporary.newFolder(), BytesThemeSource(value.zip()), io,
                validator = ThemeAssetValidator(FilesOnlyFontDecoder()))
            val preview = repository.prepareImport(Fixture.request())
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            var delivered = false
            val caller = async(Dispatchers.Default) {
                repository.consumePreviewFont(preview.token, LyricsFontRole.ORIGINAL) { _, _, _, signal ->
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    assertTrue(signal.isCanceled)
                    "obsolete consumer result"
                }
                delivered = true
            }
            try {
                awaitThemeLatch(entered)
                caller.cancel()
                assertTrue(caller.isCancelled)
                assertFalse(caller.isCompleted)
                Fixture.expectSuspending(ThemeIssue.BUSY) { repository.read() }
                release.countDown()
                io.awaitIdle()
                caller.join()
                assertFalse(delivered)
                assertSame(preview, repository.preview.value)
                assertEquals(ThemeBuiltins.defaultChoice, repository.state.value.selected)
                repository.discardPreview(preview.token)
            } finally {
                release.countDown()
                caller.cancelAndJoin()
                io.awaitIdle()
            }
        }
    }

    @Test(timeout = 30000L) fun unknownLengthInputStopsAtThePackageBoundaryAndClosesItsOnlyDescriptor() = runBlocking {
        val bytesRead = AtomicReference(0L)
        val closes = AtomicInteger()
        val descriptorCloses = AtomicInteger()
        val maximum = ThemeLimits.PACKAGE_BYTES + 1
        val source = object : ThemePackageOpener {
            override fun open(uri: String, signal: CancellationSignal): OpenedThemePackage {
                val input = object : InputStream() {
                    override fun read(): Int = if (bytesRead.get() == maximum) -1 else {
                        bytesRead.set(bytesRead.get() + 1)
                        1
                    }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        val count = minOf(length.toLong(), maximum - bytesRead.get()).toInt()
                        if (count == 0) return -1
                        java.util.Arrays.fill(buffer, offset, offset + count, 1.toByte())
                        bytesRead.set(bytesRead.get() + count)
                        return count
                    }
                    override fun close() { closes.incrementAndGet() }
                }
                return OpenedThemePackage(input, null) { descriptorCloses.incrementAndGet() }
            }
        }
        val directory = temporary.newFolder()
        val repository = Fixture.repository(directory, source)
        Fixture.expectSuspending(ThemeIssue.PACKAGE_TOO_LARGE) { repository.prepareImport(Fixture.request()) }
        assertEquals(maximum, bytesRead.get())
        assertEquals(1, closes.get())
        assertEquals(1, descriptorCloses.get())
        assertNull(repository.preview.value)
        assertTrue(checkNotNull(File(directory, "staging").list()).isEmpty())
        assertEquals(0L, repository.read().revision)
    }

    @Test(timeout = 30000L) fun mainCancellationKeepsReadInputCloseAndDescriptorCloseOnTheSamePhysicalWorker() = runBlocking {
        withTimeout(15000) { exercisePhysicalCancellation(blockOpen = false) }
    }

    @Test(timeout = 30000L) fun cancellingBeforeOpenerReturnsSkipsReadAndClosesInputAndDescriptorOnceOnItsWorker() = runBlocking {
        withTimeout(15000) { exercisePhysicalCancellation(blockOpen = true) }
    }

    /** 两个真实 executor，不用虚拟 dispatcher 冒充 Main 或 I/O；观察方独立放行每个物理门。 */
    private suspend fun exercisePhysicalCancellation(blockOpen: Boolean) {
        val dispatcher = PhysicalThemeIoDispatcher()
        val io = LocalProviderIo(dispatcher)
        val mainDispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "theme-ir-01-caller").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        val scope = CoroutineScope(Job() + mainDispatcher)
        val source = PhysicalBlockingThemeSource(Fixture.packageOf().zip(), blockOpen)
        val directory = temporary.newFolder()
        val repository = Fixture.repository(directory, source, io)
        val cancellation = ControlledThemeCancellation()
        val delivered = AtomicBoolean(false)
        val completionCause = AtomicReference<Throwable?>(null)
        val completionObserved = CompletableDeferred<Unit>()
        var caller: Deferred<ThemePreview>? = null
        try {
            val mainThread = scope.async { Thread.currentThread() }.await()
            val active = scope.async {
                repository.prepareImport(Fixture.request()).also { delivered.set(true) }
            }
            active.invokeOnCompletion {
                completionCause.set(it)
                completionObserved.complete(Unit)
            }
            caller = active
            awaitThemeLatch(if (blockOpen) source.openEntered else source.readEntered)
            val physicalThread = checkNotNull(dispatcher.workerThread.get())
            assertNotEquals(mainThread, physicalThread)
            assertSame(physicalThread, source.openThread.get())
            assertEquals(1, dispatcher.entered.get())
            assertEquals(0, dispatcher.returned.get())
            val before = File(directory, "catalog.bin").readBytes()
            val stage = checkNotNull(File(directory, "staging").listFiles()).single()

            assertCallerQueueProgress(scope, mainThread) { active.cancel(cancellation) }
            assertTrue(active.isCancelled)
            assertTrue(checkNotNull(source.signal.get()).isCanceled)
            assertEquals(0, source.inputCloses.get())
            assertEquals(0, source.descriptorCloses.get())
            assertEquals(1L, source.releaseRead.count)
            assertEquals(1L, source.releaseInputClose.count)
            assertEquals(1L, source.releaseDescriptorClose.count)
            assertTrue(stage.isDirectory)
            assertOnlyOriginalWorkerBusy(repository, io, dispatcher)
            if (blockOpen) {
                assertEquals(1L, source.releaseOpen.count)
                assertEquals(0, source.readCalls.get())
                source.releaseOpen.countDown()
            } else {
                assertSame(physicalThread, source.readThread.get())
                assertEquals(1, source.readCalls.get())
                assertEquals(1L, source.readReturned.count)
                source.releaseRead.countDown()
            }

            awaitThemeLatch(source.inputCloseEntered)
            assertSame(physicalThread, source.inputCloseThread.get())
            assertEquals(1, source.inputCloses.get())
            assertEquals(0, source.descriptorCloses.get())
            assertEquals(if (blockOpen) 0 else 1, source.readCalls.get())
            if (!blockOpen) assertEquals(0L, source.readReturned.count)
            // 取消命令已执行；各关闭门仍未放行时，caller 队列必须继续推进。
            assertCallerQueueProgress(scope, mainThread) {}
            assertEquals(1L, source.releaseInputClose.count)
            assertEquals(1L, source.inputCloseReturned.count)
            assertTrue(stage.isDirectory)
            assertOnlyOriginalWorkerBusy(repository, io, dispatcher)

            source.releaseInputClose.countDown()
            awaitThemeLatch(source.descriptorCloseEntered)
            assertEquals(0L, source.inputCloseReturned.count)
            assertSame(physicalThread, source.descriptorCloseThread.get())
            assertEquals(1, source.inputCloses.get())
            assertEquals(1, source.descriptorCloses.get())
            assertCallerQueueProgress(scope, mainThread) {}
            assertEquals(1L, source.releaseDescriptorClose.count)
            assertEquals(1L, source.descriptorCloseReturned.count)
            assertTrue(stage.isDirectory)
            assertOnlyOriginalWorkerBusy(repository, io, dispatcher)

            source.releaseDescriptorClose.countDown()
            // busy=false 先于 Runnable 返回和结果 resume；必须等真实 block.run 返回屏障。
            awaitThemeLatch(dispatcher.firstWorkerReturned)
            assertNull("Physical provider gates must finish by observer release", source.firstGateFailure.get())
            io.awaitIdle()
            active.join()
            // 以真实 completion callback 回执确认原取消原因；join 唤醒不作为 handler 已执行的证明。
            completionObserved.await()
            assertSame(cancellation, completionCause.get())
            try {
                active.await()
                throw AssertionError("Expected the original caller cancellation")
            } catch (actual: CancellationException) {
                // await 的协程栈恢复可能复制异常；completion cause 上方已核原始取消对象。
                assertEquals(cancellation.message, actual.message)
            }
            assertCallerQueueProgress(scope, mainThread) {}
            assertEquals(1, dispatcher.submitted.get())
            assertEquals(1, dispatcher.returned.get())
            assertEquals(1, source.opens.get())
            assertEquals(1, source.inputCloses.get())
            assertEquals(1, source.descriptorCloses.get())
            assertEquals(0L, source.descriptorCloseReturned.count)
            assertFalse(delivered.get())
            assertNull(repository.preview.value)
            assertFalse(stage.exists())
            assertArrayEquals(before, File(directory, "catalog.bin").readBytes())
            assertEquals(0L, repository.read().revision)
        } finally {
            // 先放行全部 provider 门，再取消/join；错误线程 close 不能使故障收尾本身挂住。
            source.releaseAll()
            caller?.cancel()
            scope.cancel()
            try {
                withContext(NonCancellable) {
                    withTimeout(5000) {
                        caller?.join()
                        if (dispatcher.submitted.get() != 0) awaitThemeLatch(dispatcher.firstWorkerReturned)
                        io.awaitIdle()
                    }
                }
            } finally {
                mainDispatcher.close()
                dispatcher.close()
            }
        }
    }

    /** 保留受控取消的原对象，现有身份断言继续验证真实完成原因。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private class ControlledThemeCancellation : CancellationException("Controlled Main-equivalent theme cancellation"),
        CopyableThrowable<ControlledThemeCancellation> {
        override fun createCopy(): ControlledThemeCancellation? = null
    }

    private suspend fun assertCallerQueueProgress(scope: CoroutineScope, thread: Thread, command: () -> Unit) {
        val cancellationCommand = scope.async {
            command()
            Thread.currentThread()
        }
        val queuedAfterCommand = scope.async { Thread.currentThread() }
        assertSame(thread, queuedAfterCommand.await())
        assertSame(thread, cancellationCommand.await())
    }

    private suspend fun assertOnlyOriginalWorkerBusy(
        repository: LocalThemeRepository,
        io: LocalProviderIo,
        dispatcher: PhysicalThemeIoDispatcher,
    ) {
        Fixture.expectSuspending(ThemeIssue.BUSY) { repository.read() }
        try {
            io.read { throw AssertionError("A second physical source must not enter or queue") }
            throw AssertionError("Expected occupied physical channel")
        } catch (actual: SourceReadException) {
            assertEquals(SourceReadIssue.BUSY, actual.issue)
        }
        assertEquals(1, dispatcher.submitted.get())
        assertEquals(1, dispatcher.entered.get())
        assertEquals(0, dispatcher.returned.get())
    }
}

internal suspend fun awaitThemeLatch(latch: CountDownLatch) = withContext(Dispatchers.IO) {
    check(latch.await(5, TimeUnit.SECONDS)) { "Theme test checkpoint was not reached" }
}

/** 可在首次 scheduler drain 后启动 worker，完成通知仍等待结果 resume 完整返回。 */
internal class ThemeDeliveryIoDispatcher : CoroutineDispatcher() {
    private data class Checkpoint(val completed: CountDownLatch, val beforeStart: CountDownLatch?,
        val startedAfterRelease: AtomicBoolean = AtomicBoolean(false))
    private val nextCompletion = AtomicReference<Checkpoint?>(null)
    fun arm(latch: CountDownLatch, beforeStart: CountDownLatch? = null): AtomicBoolean {
        val checkpoint = Checkpoint(latch, beforeStart)
        check(nextCompletion.compareAndSet(null, checkpoint))
        return checkpoint.startedAfterRelease
    }
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val checkpoint = nextCompletion.getAndSet(null)
        Dispatchers.IO.dispatch(context, Runnable {
            try {
                try {
                    checkpoint?.let { current ->
                        current.startedAfterRelease.set(current.beforeStart?.await(5, TimeUnit.SECONDS) ?: true)
                    }
                } finally {
                    // Even an interrupted or timed-out test gate must retire the admitted real worker.
                    block.run()
                }
            } finally {
                checkpoint?.completed?.countDown()
            }
        })
    }
}

private class BlockingThemeSource(private val bytes: ByteArray, private val blockClose: Boolean) : ThemePackageOpener {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val opens = AtomicInteger()
    val inputCloses = AtomicInteger()
    val descriptorCloses = AtomicInteger()
    private val blocked = AtomicBoolean(false)

    override fun open(uri: String, signal: CancellationSignal): OpenedThemePackage {
        opens.incrementAndGet()
        val input = object : FilterInputStream(ByteArrayInputStream(bytes)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!blockClose) blockOnce()
                return super.read(buffer, offset, length)
            }
            override fun close() {
                if (blockClose) blockOnce()
                super.close()
                inputCloses.incrementAndGet()
            }
            private fun blockOnce() {
                if (blocked.compareAndSet(false, true)) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
        }
        return OpenedThemePackage(input, bytes.size.toLong()) { descriptorCloses.incrementAndGet() }
    }
}

/** 记录真实 dispatch/entry/return；被 BUSY 拒绝的请求必须没有第二次 executor submission。 */
private class PhysicalThemeIoDispatcher : CoroutineDispatcher(), AutoCloseable {
    val submitted = AtomicInteger()
    val entered = AtomicInteger()
    val returned = AtomicInteger()
    val workerThread = AtomicReference<Thread?>(null)
    val firstWorkerReturned = CountDownLatch(1)
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "theme-ir-01-physical-io").apply { isDaemon = true }
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        submitted.incrementAndGet()
        executor.execute {
            workerThread.compareAndSet(null, Thread.currentThread())
            entered.incrementAndGet()
            try {
                block.run()
            } finally {
                returned.incrementAndGet()
                firstWorkerReturned.countDown()
            }
        }
    }

    override fun close() { executor.shutdownNow() }
}

/** close 不解除 read；没有取消 listener，只有观察方分别放行 open/read/input/descriptor 门。 */
private class PhysicalBlockingThemeSource(private val bytes: ByteArray, private val blockOpen: Boolean) : ThemePackageOpener {
    val opens = AtomicInteger()
    val readCalls = AtomicInteger()
    val inputCloses = AtomicInteger()
    val descriptorCloses = AtomicInteger()
    val firstGateFailure = AtomicReference<Throwable?>(null)
    val signal = AtomicReference<CancellationSignal?>(null)
    val openThread = AtomicReference<Thread?>(null)
    val readThread = AtomicReference<Thread?>(null)
    val inputCloseThread = AtomicReference<Thread?>(null)
    val descriptorCloseThread = AtomicReference<Thread?>(null)
    val openEntered = CountDownLatch(1)
    val releaseOpen = CountDownLatch(if (blockOpen) 1 else 0)
    val readEntered = CountDownLatch(1)
    val releaseRead = CountDownLatch(1)
    val readReturned = CountDownLatch(1)
    val inputCloseEntered = CountDownLatch(1)
    val releaseInputClose = CountDownLatch(1)
    val inputCloseReturned = CountDownLatch(1)
    val descriptorCloseEntered = CountDownLatch(1)
    val releaseDescriptorClose = CountDownLatch(1)
    val descriptorCloseReturned = CountDownLatch(1)

    override fun open(uri: String, signal: CancellationSignal): OpenedThemePackage {
        opens.incrementAndGet()
        this.signal.set(signal)
        openThread.set(Thread.currentThread())
        openEntered.countDown()
        awaitGate(releaseOpen, "opener")
        val delegate = ByteArrayInputStream(bytes)
        val input = object : InputStream() {
            override fun read(): Int = delegate.read()

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                readCalls.incrementAndGet()
                readThread.compareAndSet(null, Thread.currentThread())
                readEntered.countDown()
                try {
                    awaitGate(releaseRead, "read")
                    return delegate.read(buffer, offset, length)
                } finally {
                    readReturned.countDown()
                }
            }

            override fun close() {
                inputCloses.incrementAndGet()
                inputCloseThread.compareAndSet(null, Thread.currentThread())
                inputCloseEntered.countDown()
                try {
                    awaitGate(releaseInputClose, "input-close")
                    delegate.close()
                } finally {
                    inputCloseReturned.countDown()
                }
            }
        }
        return OpenedThemePackage(input, bytes.size.toLong()) {
            descriptorCloses.incrementAndGet()
            descriptorCloseThread.compareAndSet(null, Thread.currentThread())
            descriptorCloseEntered.countDown()
            try {
                awaitGate(releaseDescriptorClose, "descriptor-close")
            } finally {
                descriptorCloseReturned.countDown()
            }
        }
    }

    private fun awaitGate(gate: CountDownLatch, name: String) {
        try {
            check(gate.await(5, TimeUnit.SECONDS)) { "Theme $name gate was not released" }
        } catch (failure: Throwable) {
            // 已取消 continuation 不会交付 worker 失败；观察方必须独立核对物理门的真实结果。
            firstGateFailure.compareAndSet(null, failure)
            throw failure
        }
    }

    fun releaseAll() {
        releaseOpen.countDown()
        releaseRead.countDown()
        releaseInputClose.countDown()
        releaseDescriptorClose.countDown()
    }
}
