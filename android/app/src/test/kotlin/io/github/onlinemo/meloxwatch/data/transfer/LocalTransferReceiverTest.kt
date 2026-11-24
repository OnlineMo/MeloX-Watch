package io.github.onlinemo.meloxwatch.data.transfer

import android.os.Looper
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.RunnableScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class LocalTransferReceiverTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test(timeout = 30_000L)
    fun closingDuringNonCooperativePreparationKeepsItsTicketAndNeverOpensALateListener() = runBlocking(Dispatchers.Default) {
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder())
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val creations = AtomicInteger()
        val server = LocalTransferServer(fixture.store, fixture.backend) {
            creations.incrementAndGet()
            ServerSocket()
        }
        val receiver = LocalTransferReceiver(server, fixture.providerIo,
            prepare = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    gate.await()
                }
            },
            retireUploads = {
                fixture.store.retireAbandoned()
                fixture.store.awaitIdle()
            },
            identity = { TransferTestFixtures.identity() })
        try {
            val opening = receiver.open(loopback())
            withTimeout(5000L) { entered.await() }
            assertNull(withTimeoutOrNull(100L) { opening.await() })
            assertFalse(opening.isCompleted)
            val closing = receiver.close()
            assertNull(withTimeoutOrNull(100L) { closing.await()
                true })
            assertSame(closing, receiver.close())
            assertEquals(TransferServerPhase.OFF, server.phase.value)
            expectIssue(TransferIssue.BUSY) { receiver.open(loopback()) }
            gate.complete(Unit)
            withTimeout(5000L) { closing.await() }
            assertEquals(0, creations.get())
            assertEquals(ReceiverControlPhase.READY, receiver.state.value.phase)
            assertTrue(opening.isCancelled)
        } finally {
            gate.complete(Unit)
            withTimeout(5000L) { receiver.retryClose().await() }
        }
    }

    @Test(timeout = 30_000L)
    fun cancellingOnlyTheControlWaitKeepsTheRealOpeningAndClosingReceipts() = runBlocking(Dispatchers.Default) {
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder())
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val receiver = LocalTransferReceiver(fixture.server, fixture.providerIo,
            prepare = {
                entered.complete(Unit)
                gate.await()
            },
            retireUploads = {
                fixture.store.retireAbandoned()
                fixture.store.awaitIdle()
            },
            identity = { TransferTestFixtures.identity() })
        try {
            val opening = receiver.open(loopback())
            withTimeout(5000L) { entered.await() }
            assertNull(withTimeoutOrNull(100L) { opening.await() })
            assertTrue(opening.isActive)
            gate.complete(Unit)
            val handle = withTimeout(5000L) { opening.await() }
            assertTrue(handle.port > 0)
            assertEquals(TransferServerPhase.LISTENING, fixture.server.phase.value)
            val phone = TransferTestFixtures.identity()
            TransferTestFixtures.client(phone, handle).use { client ->
                assertEquals(handle.opening.target, client.greeting.target)
                assertEquals(handle.opening.sessionId, client.greeting.sessionId)
            }
            withTimeout(5000L) { receiver.close().await() }
            assertEquals(TransferServerPhase.OFF, fixture.server.phase.value)
            assertEquals(ReceiverControlPhase.READY, receiver.state.value.phase)
        } finally {
            gate.complete(Unit)
            withTimeout(5000L) { receiver.retryClose().await() }
        }
    }

    @Test(timeout = 30_000L)
    fun knownListenerFailureIsReportedBeforeBlockedCleanupAndRetryReusesThatCleanup() = runBlocking(Dispatchers.Default) {
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder())
        val listener = FailOnceListener()
        val server = LocalTransferServer(fixture.store, fixture.backend) { listener }
        val cleanupEntered = CompletableDeferred<Unit>()
        val cleanupGate = CompletableDeferred<Unit>()
        val cleanupCalls = AtomicInteger()
        val receiver = LocalTransferReceiver(server, fixture.providerIo, prepare = {},
            retireUploads = {
                cleanupCalls.incrementAndGet()
                cleanupEntered.complete(Unit)
                withContext(NonCancellable) { cleanupGate.await() }
                fixture.store.retireAbandoned()
                fixture.store.awaitIdle()
            }, identity = { TransferTestFixtures.identity() })
        try {
            withTimeout(5000L) { receiver.open(loopback()).await() }
            val original = receiver.close()
            withTimeout(5000L) { cleanupEntered.await() }
            withTimeout(5000L) { expectIssue(TransferIssue.RECOVERY_REQUIRED) { original.await() } }
            assertEquals(ReceiverControlPhase.RECOVERY_REQUIRED, receiver.state.value.phase)
            assertFalse(cleanupGate.isCompleted)
            assertEquals(1, cleanupCalls.get())
            val retry = receiver.retryClose()
            assertSame(retry, receiver.retryClose())
            assertNull(withTimeoutOrNull(100L) { retry.await()
                true })
            assertEquals(1, cleanupCalls.get())
            cleanupGate.complete(Unit)
            withTimeout(5000L) { retry.await() }
            assertEquals(TransferServerPhase.OFF, server.phase.value)
            assertEquals(2, listener.closeCalls.get())
            assertEquals(ReceiverControlPhase.READY, receiver.state.value.phase)
            expectIssue(TransferIssue.RECOVERY_REQUIRED) { original.await() }
        } finally {
            listener.failures.set(0)
            cleanupGate.complete(Unit)
            withTimeout(5000L) { receiver.retryClose().await() }
        }
    }

    @Test(timeout = 30_000L)
    fun revokedAdmissionIsCheckedBeforeAListenerIsCreated() = runBlocking(Dispatchers.Default) {
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder())
        val creations = AtomicInteger()
        val server = LocalTransferServer(fixture.store, fixture.backend) {
            creations.incrementAndGet()
            ServerSocket()
        }
        expectIssue(TransferIssue.CLOSED) {
            server.start(TransferTestFixtures.identity(), InetAddress.getLoopbackAddress(), 0) { false }
        }
        assertEquals(0, creations.get())
        assertEquals(TransferServerPhase.OFF, server.phase.value)
        withTimeout(5000L) { server.stop().await() }
    }

    private fun loopback(): LocalTransferAddress = LocalTransferAddress(InetAddress.getLoopbackAddress())

    @Test(timeout = 30_000L)
    fun mainCloseDispatchesBlockingCancellationWhileKnownListenerFailureStillReturns() = runBlocking {
        assertSame(Looper.getMainLooper(), Looper.myLooper())
        val validationEntered = CountDownLatch(1)
        val validationGate = CountDownLatch(1)
        val cancellationEntered = CountDownLatch(1)
        val cancellationGate = CountDownLatch(1)
        val cancellationThreads = ConcurrentLinkedQueue<Thread>()
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder(), validator = TransferContentValidator { file, declaration, signal ->
            PcmWavTransferValidator.validate(file, declaration, signal)
            signal.setOnCancelListener {
                cancellationThreads.add(Thread.currentThread())
                cancellationEntered.countDown()
                check(cancellationGate.await(10L, TimeUnit.SECONDS))
            }
            validationEntered.countDown()
            check(validationGate.await(10L, TimeUnit.SECONDS))
            signal.throwIfCanceled()
        })
        val listener = FailOnceListener()
        val server = LocalTransferServer(fixture.store, fixture.backend) { listener }
        val receiver = LocalTransferReceiver(server, fixture.providerIo, prepare = {},
            retireUploads = {
                fixture.store.retireAbandoned()
                fixture.store.awaitIdle()
            }, identity = { TransferTestFixtures.identity() })
        var client: TransferTestFixtures.Client? = null
        try {
            val handle = withTimeout(5000L) { receiver.open(loopback()).await() }
            val phone = TransferTestFixtures.identity()
            val selected = withContext(Dispatchers.IO) { TransferTestFixtures.client(phone, handle) }
            client = selected
            val bytes = TransferTestFixtures.wav()
            val ticket = withContext(Dispatchers.IO) {
                TransferTestFixtures.pair(selected, phone, server)
                val begin = selected.request(TransferRequest.BeginUpload(TransferTestFixtures.id(), selected.scope(), TransferTestFixtures.declaration(bytes)))
                    as TransferResponse.UploadReady
                selected.request(TransferRequest.UploadChunk(TransferTestFixtures.id(), selected.scope(), begin.ticket, 0L, bytes))
                begin.ticket
            }
            val finish = async(Dispatchers.IO) {
                try { selected.request(TransferRequest.FinishUpload(TransferTestFixtures.id(), selected.scope(), ticket)) }
                catch (_: Exception) { null }
            }
            withContext(Dispatchers.IO) { check(validationEntered.await(5L, TimeUnit.SECONDS)) }
            val closing = receiver.close()
            withContext(Dispatchers.IO) { check(cancellationEntered.await(5L, TimeUnit.SECONDS)) }
            assertTrue(cancellationThreads.all { it !== Looper.getMainLooper().thread })
            withTimeout(5000L) { expectIssue(TransferIssue.RECOVERY_REQUIRED) { closing.await() } }
            assertEquals(1L, cancellationGate.count)
            assertFalse(server.sessionState.value.active)
            assertNull(withTimeoutOrNull(100L) { fixture.providerIo.awaitIdle()
                true })
            val retry = receiver.retryClose()
            assertNull(withTimeoutOrNull(100L) { retry.await()
                true })
            cancellationGate.countDown()
            validationGate.countDown()
            withTimeout(5000L) { retry.await()
                finish.await() }
            assertEquals(ReceiverControlPhase.READY, receiver.state.value.phase)
            assertEquals(TransferServerPhase.OFF, server.phase.value)
        } finally {
            listener.failures.set(0)
            cancellationGate.countDown()
            validationGate.countDown()
            withContext(NonCancellable + Dispatchers.IO) { client?.close() }
            withContext(NonCancellable) { withTimeout(5000L) { receiver.retryClose().await() } }
        }
    }

    @Test(timeout = 30_000L)
    fun operationDeadlineDispatchesBlockingCancellationWithoutOccupyingThePhysicalCloseThread() = runBlocking(Dispatchers.Default) {
        val validationEntered = CountDownLatch(1)
        val validationGate = CountDownLatch(1)
        val cancellationEntered = CountDownLatch(1)
        val cancellationGate = CountDownLatch(1)
        val cancellationThreads = ConcurrentLinkedQueue<Thread>()
        val closeThreads = ConcurrentLinkedQueue<Thread>()
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder(), validator = TransferContentValidator { file, declaration, signal ->
            PcmWavTransferValidator.validate(file, declaration, signal)
            signal.setOnCancelListener {
                cancellationThreads.add(Thread.currentThread())
                cancellationEntered.countDown()
                check(cancellationGate.await(10L, TimeUnit.SECONDS))
            }
            validationEntered.countDown()
            check(validationGate.await(10L, TimeUnit.SECONDS))
            signal.throwIfCanceled()
        })
        val failures = AtomicInteger(1)
        val listener = object : ServerSocket() {
            override fun close() {
                closeThreads.add(Thread.currentThread())
                if (failures.getAndUpdate { maxOf(0, it - 1) } > 0) throw IOException("Controlled local close failure")
                super.close()
            }
        }
        val server = LocalTransferServer(fixture.store, fixture.backend) { listener }
        val receiver = LocalTransferReceiver(server, fixture.providerIo, prepare = {},
            retireUploads = {
                fixture.store.retireAbandoned()
                fixture.store.awaitIdle()
            }, identity = { TransferTestFixtures.identity() })
        var client: TransferTestFixtures.Client? = null
        try {
            val handle = withTimeout(5000L) { receiver.open(loopback()).await() }
            val phone = TransferTestFixtures.identity()
            val selected = withContext(Dispatchers.IO) { TransferTestFixtures.client(phone, handle) }
            client = selected
            val bytes = TransferTestFixtures.wav()
            val ticket = withContext(Dispatchers.IO) {
                TransferTestFixtures.pair(selected, phone, server)
                val begin = selected.request(TransferRequest.BeginUpload(TransferTestFixtures.id(), selected.scope(), TransferTestFixtures.declaration(bytes)))
                    as TransferResponse.UploadReady
                selected.request(TransferRequest.UploadChunk(TransferTestFixtures.id(), selected.scope(), begin.ticket, 0L, bytes))
                begin.ticket
            }
            val finish = async(Dispatchers.IO) {
                try { selected.request(TransferRequest.FinishUpload(TransferTestFixtures.id(), selected.scope(), ticket)) }
                catch (_: Exception) { null }
            }
            withContext(Dispatchers.IO) { check(validationEntered.await(5L, TimeUnit.SECONDS)) }
            // 提前触发已排队的真实 operation 回调；不改变产品 60 秒期限，也不把此测试当计时实测。
            val resources = server.javaClass.getDeclaredField("resources").apply { isAccessible = true }.get(server)!!
            val closer = resources.javaClass.getDeclaredField("closer").apply { isAccessible = true }.get(resources)
                as ScheduledThreadPoolExecutor
            val deadline = closer.queue.filterIsInstance<RunnableScheduledFuture<*>>().single {
                it.getDelay(TimeUnit.MILLISECONDS) in 1L..TransferLimits.OPERATION_MS
            }
            check(closer.remove(deadline))
            closer.execute(deadline)
            withContext(Dispatchers.IO) { check(cancellationEntered.await(5L, TimeUnit.SECONDS)) }
            val closing = receiver.close()
            withTimeout(5000L) { expectIssue(TransferIssue.RECOVERY_REQUIRED) { closing.await() } }
            assertEquals(1L, cancellationGate.count)
            assertTrue(cancellationThreads.isNotEmpty())
            assertTrue(closeThreads.isNotEmpty())
            assertTrue(cancellationThreads.none { it in closeThreads })
            assertTrue(cancellationThreads.all { it !== Looper.getMainLooper().thread })
            assertFalse(server.sessionState.value.active)
            assertNull(withTimeoutOrNull(100L) { fixture.providerIo.awaitIdle()
                true })
            val retry = receiver.retryClose()
            assertNull(withTimeoutOrNull(100L) { retry.await()
                true })
            cancellationGate.countDown()
            validationGate.countDown()
            withTimeout(5000L) { retry.await()
                finish.await() }
            assertEquals(TransferServerPhase.OFF, server.phase.value)
            assertEquals(ReceiverControlPhase.READY, receiver.state.value.phase)
        } finally {
            failures.set(0)
            cancellationGate.countDown()
            validationGate.countDown()
            withContext(NonCancellable + Dispatchers.IO) { client?.close() }
            withContext(NonCancellable) { withTimeout(5000L) { receiver.retryClose().await() } }
        }
    }

    private suspend fun expectIssue(issue: TransferIssue, action: suspend () -> Any?) {
        try {
            action()
            fail("Expected transfer issue")
        } catch (error: TransferException) { assertEquals(issue, error.issue) }
    }

    private class FailOnceListener : ServerSocket() {
        val failures = AtomicInteger(1)
        val closeCalls = AtomicInteger()
        override fun close() {
            closeCalls.incrementAndGet()
            if (failures.getAndUpdate { maxOf(0, it - 1) } > 0) throw IOException("Controlled local close failure")
            super.close()
        }
    }
}
