package io.github.onlinemo.meloxwatch.companion.transfer

import io.github.onlinemo.meloxwatch.companion.data.CompanionClock
import io.github.onlinemo.meloxwatch.companion.data.CompanionConnectionPhase
import io.github.onlinemo.meloxwatch.companion.data.CompanionEndpoint
import io.github.onlinemo.meloxwatch.companion.data.CompanionTarget
import io.github.onlinemo.meloxwatch.data.transfer.TransferBearer
import io.github.onlinemo.meloxwatch.data.transfer.TransferCapability
import io.github.onlinemo.meloxwatch.data.transfer.TransferCatalogKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferCatalogPage
import io.github.onlinemo.meloxwatch.data.transfer.TransferException
import io.github.onlinemo.meloxwatch.data.transfer.TransferFingerprint
import io.github.onlinemo.meloxwatch.data.transfer.TransferIssue
import io.github.onlinemo.meloxwatch.data.transfer.TransferLibraryRevision
import io.github.onlinemo.meloxwatch.data.transfer.TransferLimits
import io.github.onlinemo.meloxwatch.data.transfer.TransferProtocolCodec
import io.github.onlinemo.meloxwatch.data.transfer.TransferRequest
import io.github.onlinemo.meloxwatch.data.transfer.TransferResponse
import io.github.onlinemo.meloxwatch.data.transfer.TransferTls
import io.github.onlinemo.meloxwatch.data.transfer.TransferTlsIdentity
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 使用真实 JDK mTLS 与生产连接所有者，只有已连接 raw socket 的物理故障由夹具注入。
 * 不替代 Android Wi-Fi 绑定、真实设备和双 APK 验收。每个 gate 均在 finally 释放。
 * 本封存包仅提供测试定义；云端执行前全部为 NOT_RUN。
 */
class CompanionTlsConnectionTest {
    private val clock = CompanionClock { 0L }
    private fun id(number: Long): String = UUID(0L, number).toString()

    @Test(timeout = 20000L)
    fun realMutualTlsPinsTheWatchAndProvesThePhoneKey() = runBlocking {
        withTimeout(15000L) {
            val server = LoopbackServer(identity())
            val factory = FixtureFactory(server, identity())
            val client = CompanionTransferClient(factory, clock, retirementWaitMs = 200L)
            try {
                ready(client, server)
                val connection = checkNotNull(factory.current)
                assertEquals(server.identity.targetFingerprint, connection.greeting.target)
                assertEquals(factory.identity.peerFingerprint, connection.peer)
                assertEquals(2, server.authenticatedPeers.size)
                assertTrue(server.authenticatedPeers.all { it == factory.identity.peerFingerprint })
                assertEquals(0L, client.readPage(TransferCatalogKind.TRACKS, "", null).totalCount)
            } finally {
                factory.releaseAll()
                recover(client)
                server.finish()
            }
        }
    }

    @Test(timeout = 20000L)
    fun aDifferentWatchCertificateCannotCompleteRealTlsPreparation() = runBlocking {
        withTimeout(15000L) {
            val server = LoopbackServer(identity())
            val factory = FixtureFactory(server, identity())
            try {
                issue(TransferIssue.TRANSPORT_FAILED) {
                    factory.open(server.endpoint, TransferFingerprint.digest(byteArrayOf(91)), null)
                }
                val connection = checkNotNull(factory.current)
                withTimeout(2000L) { connection.close().await() }
                assertTrue(connection.retired)
                assertFalse(connection.accepting)
            } finally {
                factory.releaseAll()
                factory.current?.retryRetirement()
                server.finish()
            }
        }
    }

    @Test(timeout = 20000L)
    fun knownRawCloseFailureDoesNotWaitForABlockedRealTlsRead() = runBlocking {
        withTimeout(15000L) { blockedBusiness(RawGate.READ) }
    }

    @Test(timeout = 20000L)
    fun knownRawCloseFailureDoesNotWaitForABlockedRealTlsWrite() = runBlocking {
        withTimeout(15000L) { blockedBusiness(RawGate.WRITE) }
    }

    private suspend fun blockedBusiness(direction: RawGate) {
        val server = LoopbackServer(identity())
        val factory = FixtureFactory(server, identity())
        val client = CompanionTransferClient(factory, clock, retirementWaitMs = 100L)
        val callers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var body: Deferred<TransferCatalogPage>? = null
        try {
            ready(client, server)
            val connection = checkNotNull(factory.current)
            val raw = connection.raw as FaultingSocket
            val gate = if (direction == RawGate.READ) raw.readGate else raw.writeGate
            gate.arm()
            raw.failNextClose.set(true)
            body = callers.async { client.readPage(TransferCatalogKind.TRACKS, "", null) }
            gate.awaitEntered()
            val firstControl = client.disconnect()
            // 在 gate 未释放时，失败回执和已取消的调用者必须都能结束。
            withTimeout(1000L) { issue(TransferIssue.RECOVERY_REQUIRED) { firstControl.await() } }
            withTimeout(1000L) {
                try {
                    checkNotNull(body).await()
                    fail("Expected the cancelled caller")
                } catch (_: CancellationException) { }
            }
            val originalClose = connection.close()
            assertSame(firstControl, client.disconnect())
            assertFalse(connection.retired)
            assertFalse(raw.isClosed)
            assertSame(connection, factory.current)
            assertEquals(1, raw.closeCalls.get())
            assertEquals(CompanionConnectionPhase.RECOVERY_REQUIRED, client.state.value.phase)
            issue(TransferIssue.BUSY) { client.authenticate(server.endpoint) }

            // 真正重试必须触达 raw.close；成功关闭 raw 不等于阻塞 TLS worker 已退出。
            issue(TransferIssue.RECOVERY_REQUIRED) { client.retryRetirement() }
            assertTrue(raw.isClosed)
            assertFalse(connection.retired)
            val physicalCloseCount = raw.closeCalls.get()
            assertEquals(2, physicalCloseCount)
            issue(TransferIssue.RECOVERY_REQUIRED) { client.retryRetirement() }
            assertEquals(physicalCloseCount, raw.closeCalls.get())
            assertSame(connection, factory.current)
            assertEquals(CompanionConnectionPhase.RECOVERY_REQUIRED, client.state.value.phase)

            gate.release()
            awaitRetired(connection)
            assertEquals(CompanionConnectionPhase.RECOVERY_REQUIRED, client.state.value.phase)
            client.retryRetirement()
            assertEquals(CompanionConnectionPhase.OFF, client.state.value.phase)
            assertEquals(null, factory.current)
            // 后继成功不得将首个失败回执改写为成功。
            issue(TransferIssue.RECOVERY_REQUIRED) { originalClose.await() }
            issue(TransferIssue.RECOVERY_REQUIRED) { firstControl.await() }
        } finally {
            factory.releaseAll()
            callers.cancel()
            body?.join()
            recover(client)
            server.finish()
        }
    }

    @Test(timeout = 20000L)
    fun cancelledRecoveryWaiterKeepsTheRealCloserAndRequiresExplicitOffConfirmation() = runBlocking {
        withTimeout(15000L) {
            val server = LoopbackServer(identity())
            val factory = FixtureFactory(server, identity())
            val client = CompanionTransferClient(factory, clock, retirementWaitMs = 100L)
            val callers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            var waiter: Deferred<Unit>? = null
            try {
                ready(client, server)
                val connection = checkNotNull(factory.current)
                val raw = connection.raw as FaultingSocket
                raw.failNextClose.set(true)
                raw.closeGate.arm()
                val first = client.disconnect()
                issue(TransferIssue.RECOVERY_REQUIRED) { first.await() }
                waiter = callers.async { client.retryRetirement() }
                raw.closeGate.awaitEntered()
                checkNotNull(waiter).cancel(CancellationException("Fixture control cancelled"))
                try {
                    checkNotNull(waiter).await()
                    fail("Expected cancelled recovery waiter")
                } catch (_: CancellationException) { }
                assertFalse(connection.retired)
                assertFalse(raw.isClosed)
                assertEquals(2, raw.closeCalls.get())
                assertEquals(CompanionConnectionPhase.RECOVERY_REQUIRED, client.state.value.phase)
                issue(TransferIssue.RECOVERY_REQUIRED) { client.retryRetirement() }
                assertEquals(2, raw.closeCalls.get())
                raw.closeGate.release()
                awaitRetired(connection)
                assertEquals(CompanionConnectionPhase.RECOVERY_REQUIRED, client.state.value.phase)
                client.retryRetirement()
                assertEquals(CompanionConnectionPhase.OFF, client.state.value.phase)
                issue(TransferIssue.RECOVERY_REQUIRED) { first.await() }
            } finally {
                factory.releaseAll()
                callers.cancel()
                waiter?.join()
                recover(client)
                server.finish()
            }
        }
    }

    @Test(timeout = 20000L)
    fun pendingInitialPhysicalCloseIsReusedByBoundedAndCancelledRetryWaiters() = runBlocking {
        withTimeout(15000L) {
            val server = LoopbackServer(identity())
            val factory = FixtureFactory(server, identity())
            val callers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            var waiter: Deferred<Unit>? = null
            try {
                val connection = factory.open(server.endpoint, server.identity.targetFingerprint, null) as TlsCompanionConnection
                val raw = connection.raw as FaultingSocket
                raw.closeGate.arm()
                val first = connection.close()
                raw.closeGate.awaitEntered()
                issue(TransferIssue.RECOVERY_REQUIRED) { connection.retryRetirement() }
                waiter = callers.async { connection.retryRetirement() }
                checkNotNull(waiter).cancel(CancellationException("Fixture retry cancelled"))
                try { checkNotNull(waiter).await() } catch (_: CancellationException) { }
                assertFalse(first.isCompleted)
                assertFalse(connection.retired)
                assertEquals(1, raw.closeCalls.get())
                raw.closeGate.release()
                withTimeout(2000L) { first.await() }
                assertTrue(connection.retired)
                assertSame(first, connection.close())
            } finally {
                factory.releaseAll()
                callers.cancel()
                waiter?.join()
                factory.current?.retryRetirement()
                server.finish()
            }
        }
    }

    private suspend fun ready(client: CompanionTransferClient, server: LoopbackServer) {
        client.pair(server.endpoint, CompanionTarget(server.identity.targetFingerprint, "Fixture watch"), "12345678", setOf(TransferCapability.READ_LIBRARY))
        client.authenticate(server.endpoint)
    }

    private suspend fun recover(client: CompanionTransferClient) {
        try { withTimeout(2000L) { client.disconnect().await() } }
        catch (_: TransferException) { withTimeout(2000L) { client.retryRetirement() } }
    }

    private suspend fun awaitRetired(connection: TlsCompanionConnection) {
        withTimeout(2000L) { while (!connection.retired) delay(5L) }
    }

    private suspend fun issue(expected: TransferIssue, action: suspend () -> Unit) {
        try {
            action()
            fail("Expected a transfer issue")
        } catch (error: TransferException) { assertEquals(expected, error.issue) }
    }

    private enum class RawGate { READ, WRITE }

    private class Gate {
        private val armed = AtomicBoolean(false)
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        fun arm() { armed.set(true) }
        fun release() { released.countDown() }
        fun blockIfArmed() {
            if (!armed.get()) return
            entered.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L)
            var interrupted = false
            try {
                while (released.count > 0L) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0L) throw IOException("Fixture gate deadline")
                    try {
                        if (released.await(remaining, TimeUnit.NANOSECONDS)) return
                    } catch (_: InterruptedException) { interrupted = true }
                }
            } finally { if (interrupted) Thread.currentThread().interrupt() }
        }
        suspend fun awaitEntered() = withContext(Dispatchers.IO) {
            assertTrue("Expected physical gate entry", entered.await(3000L, TimeUnit.MILLISECONDS))
        }
    }

    /** gate 位于真实 TLS 流下方；cancel 不能将这个阻塞物理步骤标记成完成。 */
    private class FaultingSocket : Socket() {
        private val transport = Socket()
        val readGate = Gate()
        val writeGate = Gate()
        val closeGate = Gate()
        val failNextClose = AtomicBoolean(false)
        val closeCalls = AtomicInteger()
        private val gatedInput: InputStream by lazy {
            object : FilterInputStream(transport.getInputStream()) {
                override fun read(): Int {
                    readGate.blockIfArmed()
                    return super.read()
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    readGate.blockIfArmed()
                    return super.read(buffer, offset, length)
                }
            }
        }
        private val gatedOutput: OutputStream by lazy {
            object : FilterOutputStream(transport.getOutputStream()) {
                override fun write(value: Int) {
                    writeGate.blockIfArmed()
                    out.write(value)
                }
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    writeGate.blockIfArmed()
                    out.write(buffer, offset, length)
                }
            }
        }
        override fun connect(endpoint: SocketAddress, timeout: Int) { transport.connect(endpoint, timeout) }
        override fun connect(endpoint: SocketAddress) { transport.connect(endpoint) }
        override fun isConnected(): Boolean = transport.isConnected
        override fun isBound(): Boolean = transport.isBound
        override fun isClosed(): Boolean = transport.isClosed
        override fun isInputShutdown(): Boolean = transport.isInputShutdown
        override fun isOutputShutdown(): Boolean = transport.isOutputShutdown
        override fun getInetAddress(): InetAddress = transport.inetAddress
        override fun getLocalAddress(): InetAddress = transport.localAddress
        override fun getPort(): Int = transport.port
        override fun getLocalPort(): Int = transport.localPort
        override fun getRemoteSocketAddress(): SocketAddress = transport.remoteSocketAddress
        override fun getLocalSocketAddress(): SocketAddress = transport.localSocketAddress
        override fun getInputStream(): InputStream = gatedInput
        override fun getOutputStream(): OutputStream = gatedOutput
        override fun setSoTimeout(timeout: Int) { transport.soTimeout = timeout }
        override fun getSoTimeout(): Int = transport.soTimeout
        override fun setSoLinger(enabled: Boolean, seconds: Int) { transport.setSoLinger(enabled, seconds) }
        override fun getSoLinger(): Int = transport.soLinger
        override fun setTcpNoDelay(enabled: Boolean) { transport.tcpNoDelay = enabled }
        override fun getTcpNoDelay(): Boolean = transport.tcpNoDelay
        override fun shutdownInput() { transport.shutdownInput() }
        override fun shutdownOutput() { transport.shutdownOutput() }
        override fun close() {
            closeCalls.incrementAndGet()
            if (failNextClose.compareAndSet(true, false)) throw IOException("Fixture raw close failure")
            closeGate.blockIfArmed()
            transport.close()
        }
        fun releaseAll() {
            readGate.release()
            writeGate.release()
            closeGate.release()
        }
        override fun toString(): String = "<synthetic-loopback-socket>"
    }

    /** 仅替代 Android 网络选择；返回值、TLS、worker 和关闭均使用生产实现。 */
    private inner class FixtureFactory(val server: LoopbackServer, val identity: TransferTlsIdentity) : CompanionWireFactory {
        @Volatile var current: TlsCompanionConnection? = null
            private set
        private val created: MutableList<FaultingSocket> = Collections.synchronizedList(ArrayList())
        override val retirementRequired: Boolean get() = current?.retirementFailed == true
        override suspend fun open(endpoint: CompanionEndpoint, target: TransferFingerprint, expectedSession: String?): CompanionWireConnection {
            if (current?.retired == false) throw TransferException(TransferIssue.BUSY)
            val raw = FaultingSocket()
            created += raw
            val connection = TlsCompanionConnection(identity, clock, raw, retirementWaitMs = 100L)
            current = connection
            connection.prepare {
                raw.connect(InetSocketAddress(endpoint.address, endpoint.port), 1000)
                connection.prepareTls(target, expectedSession)
            }
            return connection
        }
        override fun stop(): Deferred<Unit> = current?.takeIf { !it.retired }?.close() ?: CompletableDeferred(Unit)
        override suspend fun retryRetirement() {
            val retained = current ?: return
            retained.retryRetirement()
            if (current === retained && retained.retired) current = null
        }
        fun releaseAll() { synchronized(created) { created.forEach(FaultingSocket::releaseAll) } }
    }

    private inner class LoopbackServer(val identity: TransferTlsIdentity) {
        private val rawServer = ServerSocket(0, 2, InetAddress.getLoopbackAddress())
        val endpoint = CompanionEndpoint.parse(checkNotNull(rawServer.inetAddress.hostAddress), rawServer.localPort.toString())
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val accepted: MutableList<Socket> = Collections.synchronizedList(ArrayList())
        val authenticatedPeers: MutableList<TransferFingerprint> = Collections.synchronizedList(ArrayList())
        private val jobs: MutableList<kotlinx.coroutines.Job> = Collections.synchronizedList(ArrayList())
        private val listener = scope.launch {
            try {
                while (!rawServer.isClosed) {
                    val raw = rawServer.accept()
                    accepted += raw
                    jobs += scope.launch { serve(raw) }
                }
            } catch (_: IOException) { }
        }
        private fun serve(raw: Socket) {
            var tls: SSLSocket? = null
            try {
                val selected = TransferTls.serverSocket(TransferTls.serverContext(identity), raw)
                tls = selected
                selected.startHandshake()
                authenticatedPeers += TransferTls.authenticatedPeer(selected)
                val output = selected.outputStream
                TransferProtocolCodec.writeResponse(output, TransferResponse.Greeting(id(0), identity.targetFingerprint, id(1), TransferLimits.SESSION_MS))
                while (!selected.isClosed) {
                    val request = TransferProtocolCodec.readRequest(selected.inputStream)
                    val response = when (request) {
                        is TransferRequest.Pair -> TransferResponse.PairPending(request.requestId)
                        is TransferRequest.Authenticate -> TransferResponse.Authenticated(request.requestId, TransferBearer.fromWire(ByteArray(32) { 9 }), setOf(TransferCapability.READ_LIBRARY), TransferLimits.TOKEN_MS)
                        is TransferRequest.ReadPage -> TransferResponse.Page(request.requestId,
                            TransferCatalogPage(request.kind, id(4), 5L, TransferLibraryRevision(7L, 3L), 0L, 0L, emptyList(), null))
                        else -> TransferResponse.Failure(request.requestId, TransferIssue.INVALID_INPUT)
                    }
                    TransferProtocolCodec.writeResponse(output, response)
                }
            } catch (_: IOException) { }
            catch (_: TransferException) { }
            finally {
                try { tls?.close() } finally { raw.close() }
            }
        }
        suspend fun finish() {
            rawServer.close()
            listener.join()
            synchronized(accepted) { accepted.forEach(Socket::close) }
            val owned = synchronized(jobs) { jobs.toList() }
            withTimeout(3000L) { owned.forEach { it.join() } }
            scope.cancel()
        }
    }

    /** 每次仅在测试进程内生成临时密钥；不使用固定私钥、真实凭据或外部端点。 */
    private fun identity(): TransferTlsIdentity {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val algorithm = der(0x30, der(0x06, byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b)) + der(0x05, byteArrayOf()))
        val name = der(0x30, der(0x31, der(0x30, der(0x06, byteArrayOf(0x55, 0x04, 0x03)) + der(0x0c, "MeloX fixture".toByteArray(Charsets.UTF_8)))))
        val time = DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'").withZone(ZoneOffset.UTC)
        val now = Instant.now()
        val validity = der(0x30, der(0x18, time.format(now.minusSeconds(86400L)).toByteArray(Charsets.US_ASCII)) +
            der(0x18, time.format(now.plusSeconds(86400L)).toByteArray(Charsets.US_ASCII)))
        val tbs = der(0x30, der(0xa0, der(0x02, byteArrayOf(2))) + der(0x02, byteArrayOf(1)) + algorithm + name + validity + name + key.public.encoded)
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(key.private)
        signer.update(tbs)
        val encoded = der(0x30, tbs + algorithm + der(0x03, byteArrayOf(0) + signer.sign()))
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(encoded)) as X509Certificate
        return TransferTlsIdentity(key.private, certificate)
    }

    private fun der(tag: Int, content: ByteArray): ByteArray {
        var size = content.size
        val length = if (size < 128) byteArrayOf(size.toByte()) else {
            val bytes = ArrayList<Byte>()
            while (size > 0) {
                bytes.add(0, size.toByte())
                size = size ushr 8
            }
            byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
        }
        return byteArrayOf(tag.toByte()) + length + content
    }
}
