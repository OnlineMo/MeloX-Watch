package io.github.onlinemo.meloxwatch.data.transfer

import android.os.Looper
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RunnableScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
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
class LocalTransferWriteIdleTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test(timeout = 30_000L)
    fun writeIdleExpiryReportsCloseFailuresBeforeBlockedProviderRetirement() = runBlocking(Dispatchers.Default) {
        val cancellationEntered = CountDownLatch(1)
        val cancellationGate = CountDownLatch(1)
        val cancellationThreads = ConcurrentLinkedQueue<Thread>()
        val writeGate = BlockingWrite()
        val checks = AtomicInteger()
        val outbound = TransferResponse.Failure(TransferTestFixtures.id(), TransferIssue.BUSY)
        lateinit var server: LocalTransferServer
        lateinit var resources: Any
        lateinit var writingConnection: Any
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder(),
            validator = TransferContentValidator { file, declaration, signal ->
                PcmWavTransferValidator.validate(file, declaration, signal)
                signal.setOnCancelListener {
                    cancellationThreads.add(Thread.currentThread())
                    cancellationEntered.countDown()
                    check(cancellationGate.await(10L, TimeUnit.SECONDS))
                }
                // The real serve job is waiting on this sole provider worker. Its controlled output
                // blocks schema 2's first frame flush after the actual write-idle callback is armed.
                val writer = server.javaClass.declaredMethods.single {
                    it.name == "writeWithIdleDeadline" && it.parameterCount == 5
                }.apply { isAccessible = true }
                val checkActive: () -> Unit = { signal.throwIfCanceled(); checks.incrementAndGet(); Unit }
                try {
                    writer.invoke(server, resources, writingConnection,
                        BufferedOutputStream(writeGate, TransferLimits.FRAME_BYTES), outbound, checkActive)
                } catch (error: InvocationTargetException) {
                    throw error.targetException
                }
                signal.throwIfCanceled()
            })
        val phone = TransferTestFixtures.identity()
        val views = ConcurrentHashMap.newKeySet<String>()
        val closedViews = ConcurrentLinkedQueue<Pair<TransferFingerprint, String>>()
        val backend = object : TransferBackend by fixture.backend {
            override suspend fun readPage(
                request: TransferRequest.ReadPage,
                permit: TransferPermit,
            ): TransferCatalogPage =
                fixture.backend.readPage(request, permit).also { views.add(it.viewId) }

            override suspend fun closePeerViews(peer: TransferFingerprint, sessionId: String) {
                closedViews.add(peer to sessionId)
                views.clear()
                fixture.backend.closePeerViews(peer, sessionId)
            }
        }
        val listener = ControlledListener()
        server = LocalTransferServer(fixture.store, backend) { listener }
        val receiver = LocalTransferReceiver(server, fixture.providerIo, prepare = {},
            retireUploads = { fixture.store.retireAbandoned(); fixture.store.awaitIdle() },
            identity = { TransferTestFixtures.identity() })
        var client: TransferTestFixtures.Client? = null
        var secondClient: TransferTestFixtures.Client? = null
        try {
            val handle = withTimeout(5000L) {
                receiver.open(LocalTransferAddress(InetAddress.getLoopbackAddress())).await()
            }
            val selected = withContext(Dispatchers.IO) { TransferTestFixtures.client(phone, handle) }
            client = selected
            secondClient = withContext(Dispatchers.IO) { TransferTestFixtures.client(phone, handle) }
            withTimeout(5000L) { server.connectionCount.first { it == 2 } }
            val operationId = TransferTestFixtures.id()
            val bytes = TransferTestFixtures.wav()
            val ticket = withContext(Dispatchers.IO) {
                TransferTestFixtures.pair(selected, phone, server,
                    setOf(TransferCapability.UPLOAD_AUDIO, TransferCapability.READ_LIBRARY))
                val page = selected.readPage(TransferRequest.ReadPage(TransferTestFixtures.id(), selected.scope(),
                    TransferCatalogKind.TRACKS, null, null, null, null, null, "", 1))
                assertEquals(0, page.rowCount)
                assertTrue(views.contains(page.begin.viewId))
                val begin = selected.request(TransferRequest.BeginUpload(operationId, selected.scope(),
                    TransferTestFixtures.declaration(bytes)))
                    as TransferResponse.UploadReady
                selected.request(TransferRequest.UploadChunk(TransferTestFixtures.id(), selected.scope(),
                    begin.ticket, 0L, bytes))
                begin.ticket
            }
            resources = checkNotNull(field(server, "resources"))
            val firstRaw = listener.sockets[0]
            val failingRaw = listener.sockets[1]
            writingConnection = connections(resources).single { field(it, "raw") === firstRaw }
            val secondConnection = connections(resources).single { field(it, "raw") === failingRaw }
            val serve = checkNotNull(field(writingConnection, "job")) as Job
            val processor = checkNotNull(field(writingConnection, "processor"))
            val tls = checkNotNull(field(writingConnection, "tls"))
            val rawOwner = checkNotNull(field(writingConnection, "rawClose"))
            val secondRawOwner = checkNotNull(field(secondConnection, "rawClose"))
            val listenerOwner = checkNotNull(field(resources, "listenerClose"))
            val closer = checkNotNull(field(resources, "closer")) as ScheduledThreadPoolExecutor
            val priorDeadlines = closer.queue.toSet()
            val finish = async(Dispatchers.IO) {
                try {
                    selected.request(TransferRequest.FinishUpload(TransferTestFixtures.id(), selected.scope(), ticket))
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            }
            withContext(Dispatchers.IO) { check(writeGate.entered.await(5L, TimeUnit.SECONDS)) }
            assertEquals(1, checks.get())
            val encoded = TransferProtocolCodec.readResponse(ByteArrayInputStream(writeGate.frame()))
                as TransferResponse.Failure
            assertEquals(outbound.requestId, encoded.requestId)
            assertEquals(TransferIssue.BUSY, encoded.issue)
            assertTrue(serve.isActive)
            assertEquals(1, fixture.accounting.reservationCount)

            // Advance only the already queued real write-idle Runnable. Product 10 s / 60 s
            // constants remain unchanged; this exercises the callback, not clock accuracy.
            val idle = closer.queue.filterIsInstance<RunnableScheduledFuture<*>>().single {
                it !in priorDeadlines && it.getDelay(TimeUnit.MILLISECONDS) in 1L..TransferLimits.IDLE_MS
            }
            check(closer.remove(idle))
            closer.execute(idle)
            withContext(Dispatchers.IO) { check(cancellationEntered.await(5L, TimeUnit.SECONDS)) }
            val closeThread = AtomicReference<Thread>()
            val sentinel = CountDownLatch(1)
            closer.execute { closeThread.set(Thread.currentThread()); sentinel.countDown() }
            withContext(Dispatchers.IO) { check(sentinel.await(5L, TimeUnit.SECONDS)) }
            assertTrue(idle.isDone)
            assertTrue(firstRaw.isClosed)
            assertTrue(serve.isCancelled)
            val rawReceipt = receipt(rawOwner)
            withTimeout(5000L) { rawReceipt.await() }
            val pendingRetirement = checkNotNull(field(writingConnection, "retirement")) as Deferred<*>
            assertFalse(pendingRetirement.isCompleted)

            // An independent accepted raw close and listener close fail while the first
            // connection's cancel listener and actual physical write remain blocked.
            val original = receiver.close()
            withTimeout(5000L) { expectRecovery(original) }
            withContext(Dispatchers.IO) {
                check(failingRaw.failedClose.await(5L, TimeUnit.SECONDS))
                check(listener.failedClose.await(5L, TimeUnit.SECONDS))
            }
            val oldRawFailure = receipt(secondRawOwner)
            val oldListenerFailure = receipt(listenerOwner)
            withTimeout(5000L) { expectRecovery(oldRawFailure); expectRecovery(oldListenerFailure) }
            assertSame(original, receiver.close())
            assertSame(resources, field(server, "resources"))
            assertSame(processor, field(writingConnection, "processor"))
            assertTrue(field(writingConnection, "tls") === tls)
            assertSame(rawOwner, field(writingConnection, "rawClose"))
            assertSame(pendingRetirement, field(writingConnection, "retirement"))
            assertEquals(2, server.activeConnectionCount())
            assertEquals(TransferServerPhase.RECOVERY_REQUIRED, server.phase.value)
            assertEquals(ReceiverControlPhase.RECOVERY_REQUIRED, receiver.state.value.phase)
            assertFalse(server.sessionState.value.active)
            assertEquals(1L, cancellationGate.count)
            assertEquals(1L, writeGate.release.count)
            assertEquals(1, fixture.accounting.reservationCount)
            assertEquals(1, views.size)
            assertTrue(closedViews.isEmpty())
            assertEquals(0, fixture.backend.closeCalls.get())
            assertEquals(0, fixture.backend.commitCalls.get())
            assertFalse(fixture.backend.receiptExists(operationId))
            assertFalse(fixture.backend.installedMarker(operationId))
            assertNull(withTimeoutOrNull(100L) { fixture.providerIo.awaitIdle(); true })
            assertTrue(cancellationThreads.isNotEmpty())
            assertTrue(cancellationThreads.all {
                it !== closeThread.get() && it !== Looper.getMainLooper().thread
            })
            assertTrue(listener.closeThreads.all { it === closeThread.get() })
            assertTrue(listener.rawCloseThreads.all { it === closeThread.get() })

            val retry = receiver.retryClose()
            assertSame(retry, receiver.retryClose())
            withContext(Dispatchers.IO) {
                check(failingRaw.successfulClose.await(5L, TimeUnit.SECONDS))
                check(listener.successfulClose.await(5L, TimeUnit.SECONDS))
            }
            val retriedRaw = receipt(secondRawOwner)
            val retriedListener = receipt(listenerOwner)
            assertNotSame(oldRawFailure, retriedRaw)
            assertNotSame(oldListenerFailure, retriedListener)
            withTimeout(5000L) { retriedRaw.await(); retriedListener.await() }
            assertSame(rawReceipt, receipt(rawOwner))
            assertSame(secondRawOwner, field(secondConnection, "rawClose"))
            assertSame(listenerOwner, field(resources, "listenerClose"))
            assertSame(pendingRetirement, field(writingConnection, "retirement"))
            assertTrue(connections(resources).contains(writingConnection))
            assertEquals(1, views.size)
            assertTrue(closedViews.isEmpty())
            assertNull(withTimeoutOrNull(100L) { retry.await(); true })
            assertNull(withTimeoutOrNull(100L) { fixture.providerIo.awaitIdle(); true })
            withTimeout(5000L) {
                expectRecovery(oldRawFailure); expectRecovery(oldListenerFailure); expectRecovery(original)
            }

            cancellationGate.countDown()
            writeGate.release.countDown()
            withTimeout(5000L) { retry.await(); finish.await(); fixture.providerIo.awaitIdle() }
            assertEquals(TransferServerPhase.OFF, server.phase.value)
            assertEquals(ReceiverControlPhase.READY, receiver.state.value.phase)
            assertNull(field(server, "resources"))
            assertTrue(connections(resources).isEmpty())
            assertTrue(serve.isCompleted)
            assertTrue((field(secondConnection, "job") as Job).isCompleted)
            assertNull(field(writingConnection, "processor"))
            assertNull(field(writingConnection, "tls"))
            assertNull(field(secondConnection, "processor"))
            assertNull(field(secondConnection, "tls"))
            assertTrue((field(resources, "scope") as CoroutineScope).coroutineContext[Job]!!.isCompleted)
            assertTrue(closer.isTerminated)
            assertTrue(firstRaw.isClosed && failingRaw.isClosed && listener.isClosed)
            assertEquals(2, listener.closeCalls.get())
            assertEquals(0, fixture.accounting.reservationCount)
            assertTrue(views.isEmpty())
            assertEquals(listOf(phone.peerFingerprint to handle.opening.sessionId), closedViews.toList())
            assertEquals(1, fixture.backend.closeCalls.get())
            assertEquals(0, fixture.backend.commitCalls.get())
            assertFalse(fixture.backend.receiptExists(operationId))
            assertFalse(fixture.backend.installedMarker(operationId))
            assertTrue(fixture.owned.listFiles().orEmpty().isEmpty())
            withTimeout(5000L) {
                expectRecovery(original); expectRecovery(oldRawFailure); expectRecovery(oldListenerFailure)
            }
        } finally {
            listener.failures.set(0)
            listener.sockets.forEach { it.failures.set(0) }
            cancellationGate.countDown()
            writeGate.release.countDown()
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    try { secondClient?.close() } finally { client?.close() }
                }
            } finally {
                withContext(NonCancellable) {
                    withTimeout(5000L) {
                        try { receiver.close().await() }
                        catch (error: TransferException) { assertEquals(TransferIssue.RECOVERY_REQUIRED, error.issue) }
                        receiver.retryClose().await()
                    }
                }
            }
        }
    }

    private class BlockingWrite : OutputStream() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val bytes = ByteArrayOutputStream()
        override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
        override fun write(value: ByteArray, offset: Int, length: Int) {
            synchronized(bytes) { bytes.write(value, offset, length) }
            entered.countDown()
            check(release.await(10L, TimeUnit.SECONDS))
            throw IOException("Controlled local write failure")
        }
        fun frame(): ByteArray = synchronized(bytes) { bytes.toByteArray() }
    }

    private class ControlledListener : ServerSocket() {
        val failures = AtomicInteger(1)
        val closeCalls = AtomicInteger()
        val failedClose = CountDownLatch(1)
        val successfulClose = CountDownLatch(1)
        val closeThreads = ConcurrentLinkedQueue<Thread>()
        val rawCloseThreads = ConcurrentLinkedQueue<Thread>()
        val sockets = CopyOnWriteArrayList<ControlledSocket>()
        private val accepted = AtomicInteger()
        override fun accept(): Socket {
            // implAccept installs the real accepted transport into this Socket subclass.
            val socket = ControlledSocket(if (accepted.getAndIncrement() == 1) 1 else 0, rawCloseThreads)
            implAccept(socket)
            sockets.add(socket)
            return socket
        }
        override fun close() {
            closeCalls.incrementAndGet()
            closeThreads.add(Thread.currentThread())
            if (failures.getAndUpdate { maxOf(0, it - 1) } > 0) {
                failedClose.countDown()
                throw IOException("Controlled local listener close failure")
            }
            super.close()
            successfulClose.countDown()
        }
    }

    private class ControlledSocket(
        initialFailures: Int,
        private val closeThreads: ConcurrentLinkedQueue<Thread>,
    ) : Socket() {
        val failures = AtomicInteger(initialFailures)
        val failedClose = CountDownLatch(1)
        val successfulClose = CountDownLatch(1)
        override fun close() {
            closeThreads.add(Thread.currentThread())
            if (failures.getAndUpdate { maxOf(0, it - 1) } > 0) {
                failedClose.countDown()
                throw IOException("Controlled local raw close failure")
            }
            super.close()
            successfulClose.countDown()
        }
    }

    private fun field(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    @Suppress("UNCHECKED_CAST")
    private fun connections(resources: Any): Set<Any> = checkNotNull(field(resources, "connections")) as Set<Any>

    @Suppress("UNCHECKED_CAST")
    private fun receipt(owner: Any): Deferred<Unit> = checkNotNull(field(owner, "receipt")) as Deferred<Unit>

    private suspend fun expectRecovery(receipt: Deferred<Unit>) {
        try { receipt.await(); fail("Expected preserved close failure") }
        catch (error: TransferException) { assertEquals(TransferIssue.RECOVERY_REQUIRED, error.issue) }
    }
}
