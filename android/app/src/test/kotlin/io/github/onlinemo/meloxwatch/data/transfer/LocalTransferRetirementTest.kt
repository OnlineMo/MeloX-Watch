package io.github.onlinemo.meloxwatch.data.transfer

import android.os.CancellationSignal
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
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
class LocalTransferRetirementTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun listenerFailuresRemainFailedAndRecoveryJoinsTheRealAcceptJobEvenWhenItsCallerCancels() = runBlocking(Dispatchers.Default) {
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder())
        val listener = PhysicalListener(closeFailures = 2, acceptExitGate = CountDownLatch(1))
        val creations = AtomicInteger()
        val server = LocalTransferServer(fixture.store, fixture.backend) { if (creations.getAndIncrement() == 0) listener else ServerSocket() }
        val identity = TransferTestFixtures.identity()
        val handle = server.start(identity, InetAddress.getLoopbackAddress())
        var recovery: Deferred<Unit>? = null
        try {
            await(listener.acceptEntered)
            val first = server.stop()
            expectIssue(TransferIssue.RECOVERY_REQUIRED) { first.await() }
            assertTrue(server.phase.value == TransferServerPhase.RECOVERY_REQUIRED && !listener.isClosed)
            expectIssue(TransferIssue.BUSY) { server.start(identity, InetAddress.getLoopbackAddress()) }
            expectIssue(TransferIssue.RECOVERY_REQUIRED) { server.retryRetirement() }
            val second = server.stop()
            assertTrue(first !== second && listener.closeCalls.get() == 2)
            expectIssue(TransferIssue.RECOVERY_REQUIRED) { second.await() }

            recovery = async { server.retryRetirement() }
            await(listener.acceptExitEntered)
            val physicalReceipt = server.stop()
            assertTrue(listener.isClosed && server.phase.value == TransferServerPhase.CLOSING)
            assertFalse(checkNotNull(recovery).isCompleted || physicalReceipt.isCompleted)
            expectWaitTimeout { server.retryRetirement() }
            assertTrue(server.stop() === physicalReceipt && listener.closeCalls.get() == 3)
            checkNotNull(recovery).cancel()
            withTimeout(1000L) { checkNotNull(recovery).join() }
            assertFalse(physicalReceipt.isCompleted)
            listener.acceptExitGate.countDown()
            withTimeout(5000L) { physicalReceipt.await(); checkNotNull(recovery).join() }
            assertTrue(server.phase.value == TransferServerPhase.OFF && server.activeConnectionCount() == 0)
            expectIssue(TransferIssue.RECOVERY_REQUIRED) { first.await() }
            expectIssue(TransferIssue.RECOVERY_REQUIRED) { second.await() }
            assertOffMain(listener.closeThreads)

            val restarted = server.start(identity, InetAddress.getLoopbackAddress(), handle.port)
            val phone = TransferTestFixtures.identity()
            val bytes = TransferTestFixtures.wav()
            TransferTestFixtures.client(phone, restarted).use { client ->
                TransferTestFixtures.pair(client, phone, server)
                assertTrue(TransferTestFixtures.upload(client, bytes).receipt.result == TransferReceiptKind.AUDIO_COMMITTED)
            }
            withTimeout(5000L) { server.stop().await() }
            assertTrue(fixture.owned.listFiles().orEmpty().single().readBytes().contentEquals(bytes))
        } finally {
            listener.closeFailures.set(0); listener.acceptExitGate.countDown()
            finish(server); recovery?.join()
        }
    }

    @Test fun rawCloseAndQuotaReleaseFailuresRetainTheUploadOwnerUntilRealDeletionAndReleaseSucceed() = runBlocking(Dispatchers.Default) {
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder())
        val releaseFailures = AtomicInteger(1)
        val releaseEntered = CountDownLatch(1)
        val releaseGate = CountDownLatch(1)
        val accounting = object : TransferQuotaAccounting by fixture.accounting {
            override suspend fun release(ticket: String) {
                if (releaseFailures.getAndUpdate { maxOf(0, it - 1) } > 0) throw TransferException(TransferIssue.RECOVERY_REQUIRED)
                releaseEntered.countDown()
                withContext(Dispatchers.IO) { check(releaseGate.await(10, TimeUnit.SECONDS)) }
                fixture.accounting.release(ticket)
            }
        }
        val store = TransferUploadStore(fixture.partials, fixture.providerIo, accounting, PcmWavTransferValidator,
            TransferDiskSpace { TransferLimits.FREE_RESERVE_BYTES + TransferLimits.AUDIO_BYTES * 2 })
        val listener = PhysicalListener(connectionFailures = { 2 })
        val server = LocalTransferServer(store, fixture.backend) { listener }
        val originalBytes = TransferTestFixtures.wav(frequency = 220)
        val original = File(fixture.owned, "original.wav").also { it.writeBytes(originalBytes) }
        val handle = server.start(TransferTestFixtures.identity(), InetAddress.getLoopbackAddress())
        val phone = TransferTestFixtures.identity()
        val client = TransferTestFixtures.client(phone, handle)
        var recovery: Deferred<Unit>? = null
        try {
            TransferTestFixtures.pair(client, phone, server)
            val bytes = TransferTestFixtures.wav()
            val ready = client.request(TransferRequest.BeginUpload(TransferTestFixtures.id(), client.scope(), TransferTestFixtures.declaration(bytes))) as TransferResponse.UploadReady
            client.request(TransferRequest.UploadChunk(TransferTestFixtures.id(), client.scope(), ready.ticket, 0L, bytes.copyOfRange(0, 1024)))
            val raw = listener.accepted.single()
            val first = server.stop()
            expectIssue(TransferIssue.RECOVERY_REQUIRED) { first.await() }
            assertTrue(!raw.isClosed && raw.closeCalls.get() == 1 && server.activeConnectionCount() == 1)
            assertTrue(fixture.partials.listFiles().orEmpty().single().length() == 1024L && fixture.accounting.reservationCount == 1)
            assertFalse(server.sessionState.value.active)
            expectIssue(TransferIssue.RECOVERY_REQUIRED) { server.retryRetirement() }
            assertTrue(!raw.isClosed && raw.closeCalls.get() == 2 && fixture.accounting.reservationCount == 1)

            expectIssue(TransferIssue.RECOVERY_REQUIRED) { server.retryRetirement() }
            val failedStoreReceipt = server.stop()
            assertTrue(raw.isClosed && server.phase.value == TransferServerPhase.RECOVERY_REQUIRED && server.activeConnectionCount() == 1)
            assertTrue(fixture.partials.listFiles().orEmpty().isEmpty() && fixture.accounting.reservationCount == 1)
            assertTrue(server.uploadProgress.value?.phase == TransferUploadPhase.RECOVERY_REQUIRED)
            assertTrue(original.readBytes().contentEquals(originalBytes) && fixture.backend.commitCalls.get() == 0)

            recovery = async { server.retryRetirement() }
            await(releaseEntered)
            val physicalReceipt = server.stop()
            assertFalse(checkNotNull(recovery).isCompleted || physicalReceipt.isCompleted)
            assertTrue(server.activeConnectionCount() == 1 && fixture.accounting.reservationCount == 1)
            releaseGate.countDown()
            withTimeout(5000L) { checkNotNull(recovery).await(); physicalReceipt.await(); store.awaitIdle() }
            assertTrue(server.phase.value == TransferServerPhase.OFF && server.connectionCount.value == 0)
            assertTrue(fixture.accounting.reservationCount == 0 && fixture.partials.listFiles().orEmpty().isEmpty())
            assertTrue(fixture.owned.listFiles().orEmpty().single() == original && original.readBytes().contentEquals(originalBytes))
            assertTrue(fixture.backend.closeCalls.get() == 1)
            expectIssue(TransferIssue.RECOVERY_REQUIRED) { first.await() }
            expectIssue(TransferIssue.RECOVERY_REQUIRED) { failedStoreReceipt.await() }
            assertOffMain(raw.closeThreads)
        } finally {
            listener.accepted.forEach { it.closeFailures.set(0) }; releaseFailures.set(0); releaseGate.countDown()
            client.close(); finish(server); recovery?.join()
        }
    }

    @Test fun peerViewFailureIsRetriedForTheLastConnectionAndWaitsForRealLeaseRemoval() = runBlocking(Dispatchers.Default) {
        val root = temporary.newFolder()
        val fixture = TransferTestFixtures.StoreFixture(root)
        val backend = LeasedBackend(fixture.backend, File(root, "selected-view"))
        val foreignLease = File(root, "other-session-view").also { it.writeBytes(byteArrayOf(7)) }
        val originalBytes = TransferTestFixtures.wav(frequency = 220)
        val original = File(fixture.owned, "original.wav").also { it.writeBytes(originalBytes) }
        val server = LocalTransferServer(fixture.store, backend)
        val handle = server.start(TransferTestFixtures.identity(), InetAddress.getLoopbackAddress())
        val phone = TransferTestFixtures.identity()
        val firstClient = TransferTestFixtures.client(phone, handle)
        val secondClient = TransferTestFixtures.client(phone, handle)
        var recovery: Deferred<Unit>? = null
        try {
            TransferTestFixtures.pair(firstClient, phone, server, setOf(TransferCapability.READ_LIBRARY))
            firstClient.readPage(TransferRequest.ReadPage(TransferTestFixtures.id(), firstClient.scope(), TransferCatalogKind.TRACKS, null, null, null, null, null, "", 100))
            val authenticated = secondClient.request(TransferRequest.Authenticate(TransferTestFixtures.id(), handle.opening.target, handle.opening.sessionId)) as TransferResponse.Authenticated
            secondClient.scope = TransferScope(handle.opening.target, handle.opening.sessionId, authenticated.bearer)
            secondClient.readPage(TransferRequest.ReadPage(TransferTestFixtures.id(), secondClient.scope(), TransferCatalogKind.TRACKS, null, null, null, null, null, "", 100))
            firstClient.close()
            withTimeout(5000L) { server.connectionCount.first { it == 1 } }
            assertTrue(backend.lease.isFile && backend.closeAttempts.get() == 0)
            val first = server.stop()
            expectIssue(TransferIssue.RECOVERY_REQUIRED) { first.await() }
            assertTrue(server.phase.value == TransferServerPhase.RECOVERY_REQUIRED && server.activeConnectionCount() == 1)
            assertTrue(backend.lease.isFile && backend.closeAttempts.get() == 1 && fixture.backend.closeCalls.get() == 0)

            recovery = async { server.retryRetirement() }
            await(backend.closeEntered)
            val physicalReceipt = server.stop()
            assertFalse(checkNotNull(recovery).isCompleted || physicalReceipt.isCompleted)
            assertTrue(backend.lease.isFile && server.phase.value == TransferServerPhase.CLOSING)
            expectWaitTimeout { server.retryRetirement() }
            assertTrue(server.stop() === physicalReceipt && backend.closeAttempts.get() == 2)
            backend.closeGate.countDown()
            withTimeout(5000L) { checkNotNull(recovery).await(); physicalReceipt.await() }
            assertTrue(server.phase.value == TransferServerPhase.OFF && !backend.lease.exists())
            assertTrue(backend.closeAttempts.get() == 2 && fixture.backend.closeCalls.get() == 1)
            assertTrue(backend.closedBindings.all { it.first == phone.peerFingerprint && it.second == handle.opening.sessionId })
            assertTrue(foreignLease.readBytes().contentEquals(byteArrayOf(7)) && original.readBytes().contentEquals(originalBytes))
            expectIssue(TransferIssue.RECOVERY_REQUIRED) { first.await() }
        } finally {
            backend.closeFailures.set(0); backend.closeGate.countDown()
            firstClient.close(); secondClient.close(); finish(server); recovery?.join()
        }
    }

    @Test fun failedOpeningKeepsTheBoundListenerAndAnIndependentRecoveryReceiptThroughCancellation() = runBlocking(Dispatchers.Default) {
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder())
        val listener = PhysicalListener(closeFailures = 1, failBinding = true, closeGate = CountDownLatch(1))
        val creations = AtomicInteger()
        val server = LocalTransferServer(fixture.store, fixture.backend) { if (creations.getAndIncrement() == 0) listener else ServerSocket() }
        val identity = TransferTestFixtures.identity()
        var recovery: Deferred<Unit>? = null
        try {
            expectIssue(TransferIssue.TRANSPORT_FAILED) { server.start(identity, InetAddress.getLoopbackAddress()) }
            val port = listener.localPort
            assertTrue(listener.isBound && !listener.isClosed && server.phase.value == TransferServerPhase.RECOVERY_REQUIRED)
            val first = server.stop()
            expectIssue(TransferIssue.RECOVERY_REQUIRED) { first.await() }
            recovery = async { server.retryRetirement() }
            await(listener.closeEntered)
            val physicalReceipt = server.stop()
            assertTrue(first !== physicalReceipt && !listener.isClosed)
            assertFalse(physicalReceipt.isCompleted)
            expectWaitTimeout { server.retryRetirement() }
            assertTrue(server.stop() === physicalReceipt && listener.closeCalls.get() == 2)
            checkNotNull(recovery).cancel()
            withTimeout(1000L) { checkNotNull(recovery).join() }
            assertTrue(!listener.isClosed && server.phase.value == TransferServerPhase.CLOSING)
            assertFalse(physicalReceipt.isCompleted)
            listener.closeGate.countDown()
            withTimeout(5000L) { physicalReceipt.await(); checkNotNull(recovery).join() }
            assertTrue(listener.isClosed && server.phase.value == TransferServerPhase.OFF)
            expectIssue(TransferIssue.RECOVERY_REQUIRED) { first.await() }
            assertOffMain(listener.closeThreads)
            server.start(identity, InetAddress.getLoopbackAddress(), port)
            withTimeout(5000L) { server.stop().await() }
        } finally {
            listener.closeFailures.set(0); listener.closeGate.countDown()
            finish(server); recovery?.join()
        }
    }

    @Test fun aRejectedRawSocketRetainsItsSingleOwnerAfterCloseFailureAndActuallyClosesOnRecovery() = runBlocking(Dispatchers.Default) {
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder())
        val listener = PhysicalListener(connectionFailures = { index -> if (index == TransferLimits.CONNECTIONS) 1 else 0 })
        val server = LocalTransferServer(fixture.store, fixture.backend) { listener }
        val handle = server.start(TransferTestFixtures.identity(), InetAddress.getLoopbackAddress())
        val firstClient = TransferTestFixtures.client(TransferTestFixtures.identity(), handle)
        val secondClient = TransferTestFixtures.client(TransferTestFixtures.identity(), handle)
        val rejectedClient = Socket()
        try {
            assertTrue(server.activeConnectionCount() == TransferLimits.CONNECTIONS)
            rejectedClient.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), handle.port), 5000)
            withTimeout(5000L) { server.phase.first { it == TransferServerPhase.RECOVERY_REQUIRED } }
            val rejected = listener.accepted.last()
            val first = server.stop()
            expectIssue(TransferIssue.RECOVERY_REQUIRED) { first.await() }
            assertTrue(listener.accepted.size == TransferLimits.CONNECTIONS + 1 && !rejected.isClosed)
            withTimeout(5000L) { server.connectionCount.first { it == 0 } }
            assertTrue(server.activeConnectionCount() == 0 && fixture.backend.readCalls.get() == 0 && fixture.backend.lookupCalls.get() == 0)
            withTimeout(5000L) { server.retryRetirement() }
            assertTrue(rejected.isClosed && rejected.closeCalls.get() == 2 && server.phase.value == TransferServerPhase.OFF)
            assertTrue(fixture.partials.listFiles().orEmpty().isEmpty() && fixture.owned.listFiles().orEmpty().isEmpty())
            expectIssue(TransferIssue.RECOVERY_REQUIRED) { first.await() }
            assertOffMain(rejected.closeThreads)
        } finally {
            listener.accepted.forEach { it.closeFailures.set(0) }
            rejectedClient.close(); firstClient.close(); secondClient.close(); finish(server)
        }
    }

    @Test fun listenerAllocationFailureLeavesNoOpeningDebtAndTheNextStartUsesARealListener() = runBlocking(Dispatchers.Default) {
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder())
        val allocations = AtomicInteger()
        val server = LocalTransferServer(fixture.store, fixture.backend) {
            if (allocations.getAndIncrement() == 0) throw IOException("Injected allocation failure")
            ServerSocket()
        }
        val identity = TransferTestFixtures.identity()
        try {
            expectIssue(TransferIssue.TRANSPORT_FAILED) { server.start(identity, InetAddress.getLoopbackAddress()) }
            assertTrue(server.phase.value == TransferServerPhase.OFF && !server.sessionState.value.active)
            val handle = server.start(identity, InetAddress.getLoopbackAddress())
            TransferTestFixtures.client(TransferTestFixtures.identity(), handle).use { assertTrue(it.greeting.target == handle.opening.target) }
            withTimeout(5000L) { server.stop().await() }
            assertTrue(server.phase.value == TransferServerPhase.OFF && allocations.get() == 2)
        } finally { finish(server) }
    }

    @Test fun listenerFailureIsVisibleWhileAnActualTlsUploadProviderIgnoresCancellation() = runBlocking(Dispatchers.Default) {
        val validator = BlockedValidation()
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder(), validator)
        val listener = PhysicalListener(closeFailures = 2)
        val server = LocalTransferServer(fixture.store, fixture.backend) { listener }
        val originalBytes = TransferTestFixtures.wav(frequency = 220)
        val original = File(fixture.owned, "original.wav").also { it.writeBytes(originalBytes) }
        val handle = server.start(TransferTestFixtures.identity(), InetAddress.getLoopbackAddress())
        val phone = TransferTestFixtures.identity()
        val client = TransferTestFixtures.client(phone, handle)
        var recovery: Deferred<Unit>? = null
        try {
            TransferTestFixtures.pair(client, phone, server)
            val bytes = TransferTestFixtures.wav()
            val ready = client.request(TransferRequest.BeginUpload(TransferTestFixtures.id(), client.scope(), TransferTestFixtures.declaration(bytes))) as TransferResponse.UploadReady
            client.request(TransferRequest.UploadChunk(TransferTestFixtures.id(), client.scope(), ready.ticket, 0L, bytes))
            client.send(TransferRequest.FinishUpload(TransferTestFixtures.id(), client.scope(), ready.ticket))
            await(validator.entered)

            val first = server.stop()
            withTimeout(1000L) { expectIssue(TransferIssue.RECOVERY_REQUIRED) { first.await() } }
            await(validator.cancelled)
            assertTrue(server.phase.value == TransferServerPhase.RECOVERY_REQUIRED && !listener.isClosed)
            assertTrue(server.activeConnectionCount() == 1 && validator.calls.get() == 1)
            assertTrue(validator.exited.count == 1L && fixture.accounting.reservationCount == 1)
            assertTrue(fixture.partials.listFiles().orEmpty().single().readBytes().contentEquals(bytes))
            assertTrue(original.readBytes().contentEquals(originalBytes) && fixture.backend.commitCalls.get() == 0)

            withTimeout(1000L) { expectIssue(TransferIssue.RECOVERY_REQUIRED) { server.retryRetirement() } }
            val second = server.stop()
            assertTrue(second !== first && listener.closeCalls.get() == 2 && validator.exited.count == 1L)
            recovery = async { server.retryRetirement() }
            await(listener.closeEntered)
            val physicalReceipt = server.stop()
            expectWaitTimeout { server.retryRetirement() }
            assertTrue(server.stop() === physicalReceipt && listener.closeCalls.get() == 3)
            checkNotNull(recovery).cancel()
            withTimeout(1000L) { checkNotNull(recovery).join() }
            assertFalse(physicalReceipt.isCompleted)
            assertTrue(server.phase.value == TransferServerPhase.CLOSING && server.activeConnectionCount() == 1)
            assertTrue(fixture.accounting.reservationCount == 1 && validator.calls.get() == 1 && validator.exited.count == 1L)
            assertTrue(fixture.partials.listFiles().orEmpty().single().readBytes().contentEquals(bytes))

            validator.gate.countDown()
            withTimeout(5000L) { physicalReceipt.await(); fixture.store.awaitIdle() }
            assertTrue(server.phase.value == TransferServerPhase.OFF && server.activeConnectionCount() == 0)
            assertTrue(validator.exited.count == 0L && fixture.accounting.reservationCount == 0)
            assertTrue(fixture.partials.listFiles().orEmpty().isEmpty() && original.readBytes().contentEquals(originalBytes))
            assertTrue(fixture.backend.commitCalls.get() == 0 && fixture.backend.closeCalls.get() == 1)
            expectIssue(TransferIssue.RECOVERY_REQUIRED) { first.await() }
            expectIssue(TransferIssue.RECOVERY_REQUIRED) { second.await() }
            assertOffMain(listener.closeThreads)
        } finally {
            listener.closeFailures.set(0)
            validator.gate.countDown()
            client.close()
            finish(server)
            recovery?.join()
        }
    }

    @Test fun oneRealRawCloseFailureDoesNotWaitForAnotherTlsConnectionsBlockedProvider() = runBlocking(Dispatchers.Default) {
        val validator = BlockedValidation()
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder(), validator)
        val listener = PhysicalListener(connectionFailures = { index -> if (index == 1) 2 else 0 })
        val server = LocalTransferServer(fixture.store, fixture.backend) { listener }
        val originalBytes = TransferTestFixtures.wav(frequency = 220)
        val original = File(fixture.owned, "original.wav").also { it.writeBytes(originalBytes) }
        val handle = server.start(TransferTestFixtures.identity(), InetAddress.getLoopbackAddress())
        val phone = TransferTestFixtures.identity()
        val uploadClient = TransferTestFixtures.client(phone, handle)
        val idleClient = TransferTestFixtures.client(phone, handle)
        var recovery: Deferred<Unit>? = null
        try {
            TransferTestFixtures.pair(uploadClient, phone, server)
            val bytes = TransferTestFixtures.wav()
            val ready = uploadClient.request(TransferRequest.BeginUpload(TransferTestFixtures.id(), uploadClient.scope(), TransferTestFixtures.declaration(bytes))) as TransferResponse.UploadReady
            uploadClient.request(TransferRequest.UploadChunk(TransferTestFixtures.id(), uploadClient.scope(), ready.ticket, 0L, bytes))
            uploadClient.send(TransferRequest.FinishUpload(TransferTestFixtures.id(), uploadClient.scope(), ready.ticket))
            await(validator.entered)
            val failedRaw = listener.accepted.last()

            val first = server.stop()
            withTimeout(1000L) { expectIssue(TransferIssue.RECOVERY_REQUIRED) { first.await() } }
            await(validator.cancelled)
            assertTrue(server.phase.value == TransferServerPhase.RECOVERY_REQUIRED && !failedRaw.isClosed)
            assertTrue(failedRaw.closeCalls.get() == 1 && server.activeConnectionCount() == 2)
            assertTrue(validator.calls.get() == 1 && validator.exited.count == 1L && fixture.accounting.reservationCount == 1)
            assertTrue(fixture.partials.listFiles().orEmpty().single().readBytes().contentEquals(bytes))
            assertTrue(original.readBytes().contentEquals(originalBytes) && fixture.backend.commitCalls.get() == 0)

            withTimeout(1000L) { expectIssue(TransferIssue.RECOVERY_REQUIRED) { server.retryRetirement() } }
            val second = server.stop()
            assertTrue(first !== second && !failedRaw.isClosed && failedRaw.closeCalls.get() == 2)
            recovery = async { server.retryRetirement() }
            withTimeout(5000L) { server.connectionCount.first { it == 1 } }
            val physicalReceipt = server.stop()
            assertTrue(failedRaw.isClosed && failedRaw.closeCalls.get() == 3)
            expectWaitTimeout { server.retryRetirement() }
            assertTrue(server.stop() === physicalReceipt && failedRaw.closeCalls.get() == 3)
            checkNotNull(recovery).cancel()
            withTimeout(1000L) { checkNotNull(recovery).join() }
            assertFalse(physicalReceipt.isCompleted)
            assertTrue(server.phase.value == TransferServerPhase.CLOSING && server.activeConnectionCount() == 1)
            assertTrue(validator.exited.count == 1L && fixture.accounting.reservationCount == 1)
            assertTrue(fixture.partials.listFiles().orEmpty().single().readBytes().contentEquals(bytes))

            validator.gate.countDown()
            withTimeout(5000L) { physicalReceipt.await(); fixture.store.awaitIdle() }
            assertTrue(server.phase.value == TransferServerPhase.OFF && server.activeConnectionCount() == 0)
            assertTrue(validator.exited.count == 0L && fixture.accounting.reservationCount == 0)
            assertTrue(fixture.partials.listFiles().orEmpty().isEmpty() && original.readBytes().contentEquals(originalBytes))
            assertTrue(fixture.backend.commitCalls.get() == 0 && fixture.backend.closeCalls.get() == 1)
            expectIssue(TransferIssue.RECOVERY_REQUIRED) { first.await() }
            expectIssue(TransferIssue.RECOVERY_REQUIRED) { second.await() }
            assertOffMain(failedRaw.closeThreads)
        } finally {
            listener.accepted.forEach { it.closeFailures.set(0) }
            validator.gate.countDown()
            uploadClient.close()
            idleClient.close()
            finish(server)
            recovery?.join()
        }
    }

    private suspend fun expectIssue(issue: TransferIssue, action: suspend () -> Unit) {
        try { withTimeout(5000L) { action() }; fail("Expected ${issue.name}") }
        catch (error: TransferException) { assertTrue(error.issue == issue) }
    }

    private suspend fun expectWaitTimeout(action: suspend () -> Unit) {
        try {
            withTimeout(150L) { action() }
            fail("Expected the control wait to time out")
        } catch (_: TimeoutCancellationException) {
            // The application still owns the original physical retirement receipt.
        }
    }

    private suspend fun await(latch: CountDownLatch) {
        assertTrue(withContext(Dispatchers.IO) { latch.await(5, TimeUnit.SECONDS) })
    }

    private suspend fun finish(server: LocalTransferServer) = withTimeout(5000L) {
        try { server.stop().await() }
        catch (error: TransferException) {
            if (error.issue != TransferIssue.RECOVERY_REQUIRED) throw error
            server.retryRetirement()
        }
    }

    private fun assertOffMain(threads: Collection<Thread>) {
        assertTrue(threads.isNotEmpty())
        assertFalse(threads.any { it === android.os.Looper.getMainLooper().thread })
    }

    /** A real physical provider callback retains its actual file and quota while its gate waits. */
    private class BlockedValidation : TransferContentValidator {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val calls = AtomicInteger()

        override fun validate(file: File, declaration: TransferUploadDeclaration, signal: CancellationSignal) {
            calls.incrementAndGet()
            signal.setOnCancelListener { cancelled.countDown() }
            entered.countDown()
            try {
                // This provider deliberately ignores CancellationSignal until the physical gate opens.
                check(gate.await(30, TimeUnit.SECONDS))
                PcmWavTransferValidator.validate(file, declaration, signal)
            } finally {
                signal.setOnCancelListener(null)
                exited.countDown()
            }
        }
    }

    /** Real loopback sockets; failure is injected before the physical superclass close. */
    private class PhysicalListener(
        closeFailures: Int = 0,
        private val connectionFailures: (Int) -> Int = { 0 },
        private val failBinding: Boolean = false,
        val closeGate: CountDownLatch = CountDownLatch(0),
        val acceptExitGate: CountDownLatch = CountDownLatch(0),
    ) : ServerSocket() {
        val closeFailures = AtomicInteger(closeFailures)
        val closeCalls = AtomicInteger()
        val closeThreads = ConcurrentLinkedQueue<Thread>()
        val acceptEntered = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        val acceptExitEntered = CountDownLatch(1)
        val accepted = ConcurrentLinkedQueue<PhysicalSocket>()
        private val nextConnection = AtomicInteger()

        override fun bind(endpoint: SocketAddress, backlog: Int) {
            super.bind(endpoint, backlog)
            if (failBinding) throw IOException("Injected failure after physical bind")
        }

        override fun accept(): Socket {
            acceptEntered.countDown()
            val socket = PhysicalSocket()
            try {
                implAccept(socket)
                socket.closeFailures.set(connectionFailures(nextConnection.getAndIncrement()))
                accepted.add(socket)
                return socket
            } catch (error: IOException) { socket.close(); throw error }
            finally {
                if (isClosed) { acceptExitEntered.countDown(); check(acceptExitGate.await(10, TimeUnit.SECONDS)) }
            }
        }

        override fun close() {
            closeThreads.add(Thread.currentThread()); closeCalls.incrementAndGet()
            if (closeFailures.getAndUpdate { maxOf(0, it - 1) } > 0) throw IOException("Injected listener close failure")
            closeEntered.countDown()
            check(closeGate.await(10, TimeUnit.SECONDS))
            super.close()
        }
    }

    private class PhysicalSocket : Socket() {
        val closeFailures = AtomicInteger()
        val closeCalls = AtomicInteger()
        val closeThreads = ConcurrentLinkedQueue<Thread>()
        override fun close() {
            closeThreads.add(Thread.currentThread()); closeCalls.incrementAndGet()
            if (closeFailures.getAndUpdate { maxOf(0, it - 1) } > 0) throw IOException("Injected raw close failure")
            super.close()
        }
    }

    /** Lease deletion is real and scoped; the durable upload backend remains the existing fixture. */
    private class LeasedBackend(private val delegate: TransferBackend, val lease: File) : TransferBackend by delegate {
        val closeFailures = AtomicInteger(1)
        val closeAttempts = AtomicInteger()
        val closeEntered = CountDownLatch(1)
        val closeGate = CountDownLatch(1)
        val closedBindings = ConcurrentLinkedQueue<Pair<TransferFingerprint, String>>()
        @Volatile private var leaseBinding: Pair<TransferFingerprint, String>? = null

        override suspend fun readPage(request: TransferRequest.ReadPage, permit: TransferPermit): TransferCatalogPage {
            val page = delegate.readPage(request, permit)
            withContext(Dispatchers.IO) {
                permit.checkActive()
                lease.writeBytes(byteArrayOf(1))
                leaseBinding = permit.peer to permit.sessionId
            }
            return page
        }

        override suspend fun closePeerViews(peer: TransferFingerprint, sessionId: String) {
            val binding = peer to sessionId
            if (leaseBinding != binding) throw TransferException(TransferIssue.WRONG_TARGET)
            closedBindings.add(binding); closeAttempts.incrementAndGet()
            if (closeFailures.getAndUpdate { maxOf(0, it - 1) } > 0) throw TransferException(TransferIssue.RECOVERY_REQUIRED)
            closeEntered.countDown()
            withContext(Dispatchers.IO) { check(closeGate.await(10, TimeUnit.SECONDS)); Files.deleteIfExists(lease.toPath()) }
            delegate.closePeerViews(peer, sessionId)
        }
    }
}
