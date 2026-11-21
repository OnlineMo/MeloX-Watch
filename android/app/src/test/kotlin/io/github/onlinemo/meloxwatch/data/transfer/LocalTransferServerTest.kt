package io.github.onlinemo.meloxwatch.data.transfer

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
class LocalTransferServerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun defaultOffAndWildcardRejectionDoNotCreateAListener() = runBlocking(Dispatchers.Default) {
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder())
        assertTrue(fixture.server.phase.value == TransferServerPhase.OFF && fixture.server.activeConnectionCount() == 0)
        assertTrue(fixture.server.pairingCodeForLocalUi() == null)
        try { fixture.server.start(TransferTestFixtures.identity(), InetAddress.getByAddress(ByteArray(4)))
            fail("Expected wildcard rejection") }
        catch (error: TransferException) { assertTrue(error.issue == TransferIssue.INVALID_INPUT) }
        fixture.server.stop().await()
        assertTrue(fixture.server.phase.value == TransferServerPhase.OFF)
    }

    @Test fun actualTlsAudioCommitAndAFreshBackendReplayTheSameDurableResult() = runBlocking(Dispatchers.Default) {
        val root = temporary.newFolder()
        val first = TransferTestFixtures.StoreFixture(root)
        val serverIdentity = TransferTestFixtures.identity()
        val phoneIdentity = TransferTestFixtures.identity()
        val bytes = TransferTestFixtures.wav(frames = 40_000)
        val operationId = TransferTestFixtures.id()
        val handle = first.server.start(serverIdentity, InetAddress.getLoopbackAddress())
        lateinit var prior: TransferScope
        var resultId: String? = null
        try {
            TransferTestFixtures.client(phoneIdentity, handle).use { client ->
                TransferTestFixtures.pair(client, phoneIdentity, first.server)
                prior = client.scope()
                val receipt = TransferTestFixtures.upload(client, bytes, operationId = operationId)
                assertFalse(receipt.replay)
                assertTrue(receipt.receipt.result == TransferReceiptKind.AUDIO_COMMITTED)
                resultId = receipt.receipt.resultId
                assertTrue(first.server.uploadProgress.value?.phase == TransferUploadPhase.COMPLETED && first.server.uploadProgress.value?.receivedBytes == bytes.size.toLong())
            }
        } finally { withTimeout(5000L) { first.server.stop().await() } }
        assertTrue(first.owned.listFiles().orEmpty().single().readBytes().contentEquals(bytes))
        val second = TransferTestFixtures.StoreFixture(root)
        val nextHandle = second.server.start(serverIdentity, InetAddress.getLoopbackAddress())
        try {
            TransferTestFixtures.client(phoneIdentity, nextHandle).use { client ->
                val stale = client.request(TransferRequest.BeginUpload(operationId, prior, TransferTestFixtures.declaration(bytes))) as TransferResponse.Failure
                assertTrue(stale.issue == TransferIssue.WRONG_TARGET)
                TransferTestFixtures.pair(client, phoneIdentity, second.server)
                val replay = TransferTestFixtures.upload(client, bytes, operationId = operationId)
                assertTrue(replay.replay && replay.receipt.resultId == resultId && second.backend.commitCalls.get() == 0)
                assertTrue(second.server.uploadProgress.value?.replay == true && second.server.uploadProgress.value?.receivedBytes == bytes.size.toLong())
                val conflicting = client.request(TransferRequest.BeginUpload(operationId, client.scope(), TransferTestFixtures.declaration(bytes, name = "Changed.wav"))) as TransferResponse.Failure
                assertTrue(conflicting.issue == TransferIssue.CONFLICT)
            }
        } finally { withTimeout(5000L) { second.server.stop().await() } }
        assertTrue(second.owned.listFiles().orEmpty().size == 1 && second.partials.listFiles().orEmpty().isEmpty())
    }

    @Test fun wrongPinnedTargetFailsTlsBeforePairingOrAnyBackendRequest() = runBlocking(Dispatchers.Default) {
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder())
        val handle = fixture.server.start(TransferTestFixtures.identity(), InetAddress.getLoopbackAddress())
        try {
            try { TransferTestFixtures.client(TransferTestFixtures.identity(), handle, TransferTestFixtures.fingerprint()).close()
                fail("Expected a TLS pin rejection") }
            catch (_: IOException) { }
            assertTrue(fixture.backend.lookupCalls.get() == 0 && fixture.backend.readCalls.get() == 0)
        } finally { withTimeout(5000L) { fixture.server.stop().await() } }
        assertTrue(fixture.partials.listFiles().orEmpty().isEmpty())
    }

    @Test fun tlsRequiresClientCertificateAndPrivateKeyProof() = runBlocking(Dispatchers.Default) {
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder())
        val handle = fixture.server.start(TransferTestFixtures.identity(), InetAddress.getLoopbackAddress())
        val raw = Socket()
        try {
            val trust = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { throw CertificateException("No client trust path") }
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    if (chain.size != 1 || TransferFingerprint.digest(chain[0].encoded) != handle.opening.target) throw CertificateException("Wrong ephemeral target")
                    TransferTls.validateCertificate(chain, true)
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val context = SSLContext.getInstance("TLSv1.2").apply { init(emptyArray(), arrayOf(trust), SecureRandom()) }
            raw.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), handle.port), 5000)
            val tls = TransferTls.clientSocket(context, raw)
            try { tls.startHandshake()
                fail("Expected missing client certificate rejection") }
            catch (_: IOException) { }
            assertTrue(fixture.backend.lookupCalls.get() == 0)
        } finally { raw.close()
            withTimeout(5000L) { fixture.server.stop().await() } }
    }

    @Test fun aValidTlsCandidateCannotReadOrUploadBeforeLocalPeerApproval() = runBlocking(Dispatchers.Default) {
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder())
        val handle = fixture.server.start(TransferTestFixtures.identity(), InetAddress.getLoopbackAddress())
        try {
            TransferTestFixtures.client(TransferTestFixtures.identity(), handle).use { client ->
                val forged = TransferScope(client.greeting.target, client.greeting.sessionId, TransferBearer.generate(SecureRandom()))
                val read = client.request(TransferRequest.ReadPage(TransferTestFixtures.id(), forged, TransferCatalogKind.TRACKS, null, null, null, null, null, "", 100)) as TransferResponse.Failure
                val upload = client.request(TransferRequest.BeginUpload(TransferTestFixtures.id(), forged, TransferTestFixtures.declaration(TransferTestFixtures.wav()))) as TransferResponse.Failure
                assertTrue(read.issue == TransferIssue.NOT_APPROVED && upload.issue == TransferIssue.NOT_APPROVED)
                assertTrue(fixture.backend.readCalls.get() == 0 && fixture.backend.lookupCalls.get() == 0)
            }
        } finally { withTimeout(5000L) { fixture.server.stop().await() } }
        assertTrue(fixture.partials.listFiles().orEmpty().isEmpty())
    }

    @Test fun readOnlyGrantStreamsAPageAndCannotUploadOrEditAPlaylist() = runBlocking(Dispatchers.Default) {
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder())
        val handle = fixture.server.start(TransferTestFixtures.identity(), InetAddress.getLoopbackAddress())
        val identity = TransferTestFixtures.identity()
        try {
            TransferTestFixtures.client(identity, handle).use { client ->
                TransferTestFixtures.pair(client, identity, fixture.server, setOf(TransferCapability.READ_LIBRARY))
                val page = client.readPage(TransferRequest.ReadPage(TransferTestFixtures.id(), client.scope(), TransferCatalogKind.TRACKS, null, null, null, null, null, "", 100))
                assertTrue(page.rowCount == 0 && fixture.backend.readCalls.get() == 1)
                val upload = client.request(TransferRequest.BeginUpload(TransferTestFixtures.id(), client.scope(), TransferTestFixtures.declaration(TransferTestFixtures.wav()))) as TransferResponse.Failure
                val edit = client.request(TransferRequest.EditPlaylist(TransferTestFixtures.id(), client.scope(), TransferTestFixtures.id(), 0L, page.begin.libraryRevision, TransferPlaylistEdit.Create("Draft"))) as TransferResponse.Failure
                assertTrue(upload.issue == TransferIssue.PERMISSION_DENIED && edit.issue == TransferIssue.PERMISSION_DENIED)
                assertTrue(fixture.backend.lookupCalls.get() == 0)
            }
        } finally { withTimeout(5000L) { fixture.server.stop().await() } }
    }

    @Test fun stopWaitsForPhysicalValidationAndKeepsOriginalMusicIntact() = runBlocking(Dispatchers.Default) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder(), TransferContentValidator { file, declaration, signal ->
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            PcmWavTransferValidator.validate(file, declaration, signal)
        })
        val originalBytes = TransferTestFixtures.wav(frequency = 220)
        val original = File(fixture.owned, "original.wav").also { it.writeBytes(originalBytes) }
        val handle = fixture.server.start(TransferTestFixtures.identity(), InetAddress.getLoopbackAddress())
        val identity = TransferTestFixtures.identity()
        val client = TransferTestFixtures.client(identity, handle)
        var receiver: kotlinx.coroutines.Deferred<Boolean>? = null
        try {
            TransferTestFixtures.pair(client, identity, fixture.server)
            val bytes = TransferTestFixtures.wav()
            val ready = client.request(TransferRequest.BeginUpload(TransferTestFixtures.id(), client.scope(), TransferTestFixtures.declaration(bytes))) as TransferResponse.UploadReady
            client.request(TransferRequest.UploadChunk(TransferTestFixtures.id(), client.scope(), ready.ticket, 0L, bytes))
            client.send(TransferRequest.FinishUpload(TransferTestFixtures.id(), client.scope(), ready.ticket))
            receiver = async(Dispatchers.IO) { try { client.receive()
                    false } catch (_: IOException) { true } }
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            val stopped = fixture.server.stop()
            delay(100L)
            assertFalse(stopped.isCompleted)
            assertTrue(fixture.server.phase.value == TransferServerPhase.CLOSING && fixture.server.activeConnectionCount() == 1)
            assertTrue(fixture.server.connectionCount.value == 1 && fixture.server.uploadProgress.value?.phase == TransferUploadPhase.RETIRING)
            assertTrue(fixture.backend.commitCalls.get() == 0)
            release.countDown()
            withTimeout(5000L) { stopped.await()
                assertTrue(checkNotNull(receiver).await()) }
            assertTrue(fixture.server.phase.value == TransferServerPhase.OFF && fixture.partials.listFiles().orEmpty().isEmpty())
            assertTrue(fixture.server.connectionCount.value == 0 && fixture.server.uploadProgress.value?.phase == TransferUploadPhase.CANCELLED)
            assertTrue(original.readBytes().contentEquals(originalBytes))
        } finally { release.countDown()
            client.close()
            receiver?.cancel()
            withTimeout(5000L) { fixture.server.stop().await() } }
    }

    @Test fun cancellationAfterPhysicalInstallationRetainsRecoveryBytesWithoutACommitReceipt() = runBlocking(Dispatchers.Default) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder())
        fixture.backend.onInstalled = { _, _ -> entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) }
        val bytes = TransferTestFixtures.wav()
        val operationId = TransferTestFixtures.id()
        val handle = fixture.server.start(TransferTestFixtures.identity(), InetAddress.getLoopbackAddress())
        val identity = TransferTestFixtures.identity()
        val client = TransferTestFixtures.client(identity, handle)
        try {
            TransferTestFixtures.pair(client, identity, fixture.server)
            val ready = client.request(TransferRequest.BeginUpload(operationId, client.scope(), TransferTestFixtures.declaration(bytes))) as TransferResponse.UploadReady
            client.request(TransferRequest.UploadChunk(TransferTestFixtures.id(), client.scope(), ready.ticket, 0L, bytes))
            client.send(TransferRequest.FinishUpload(TransferTestFixtures.id(), client.scope(), ready.ticket))
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            assertTrue(fixture.backend.installedMarker(operationId) && !fixture.backend.receiptExists(operationId))
            val stopped = fixture.server.stop()
            delay(100L)
            assertFalse(stopped.isCompleted)
            release.countDown()
            withTimeout(5000L) { stopped.await() }
            assertTrue(fixture.owned.listFiles().orEmpty().single().readBytes().contentEquals(bytes))
            assertTrue(fixture.backend.installedMarker(operationId) && !fixture.backend.receiptExists(operationId))
            assertTrue(fixture.partials.listFiles().orEmpty().isEmpty())
            assertTrue(fixture.server.uploadProgress.value?.phase == TransferUploadPhase.RECOVERY_REQUIRED && fixture.server.uploadProgress.value?.receipt == null)
        } finally { release.countDown()
            client.close()
            withTimeout(5000L) { fixture.server.stop().await() } }
    }

    @Test fun cancellationAfterTheDurableCommitKeepsItsRealReceiptUntilPhysicalCleanupFinishes() = runBlocking(Dispatchers.Default) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder())
        fixture.backend.onCommitted = {
            entered.countDown()
            fixture.server.stop()
            check(release.await(10, TimeUnit.SECONDS))
        }
        val bytes = TransferTestFixtures.wav()
        val operationId = TransferTestFixtures.id()
        val handle = fixture.server.start(TransferTestFixtures.identity(), InetAddress.getLoopbackAddress())
        val identity = TransferTestFixtures.identity()
        val client = TransferTestFixtures.client(identity, handle)
        try {
            TransferTestFixtures.pair(client, identity, fixture.server)
            val ready = client.request(TransferRequest.BeginUpload(operationId, client.scope(), TransferTestFixtures.declaration(bytes))) as TransferResponse.UploadReady
            client.request(TransferRequest.UploadChunk(TransferTestFixtures.id(), client.scope(), ready.ticket, 0L, bytes))
            client.send(TransferRequest.FinishUpload(TransferTestFixtures.id(), client.scope(), ready.ticket))
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            val stopped = fixture.server.stop()
            delay(100L)
            assertFalse(stopped.isCompleted)
            assertTrue(fixture.backend.receiptExists(operationId) && fixture.partials.listFiles().orEmpty().size == 1)
            assertTrue(fixture.server.uploadProgress.value?.receipt?.actualBytes == bytes.size.toLong())
            assertTrue(fixture.server.uploadProgress.value?.phase == TransferUploadPhase.RETIRING)
            release.countDown()
            withTimeout(5000L) { stopped.await() }
            assertTrue(fixture.owned.listFiles().orEmpty().single().readBytes().contentEquals(bytes))
            assertTrue(fixture.server.uploadProgress.value?.phase == TransferUploadPhase.COMPLETED && fixture.server.uploadProgress.value?.receipt != null)
            assertTrue(fixture.partials.listFiles().orEmpty().isEmpty() && fixture.accounting.reservationCount == 0)
        } finally { release.countDown()
            client.close()
            withTimeout(5000L) { fixture.server.stop().await() } }
    }

    @Test fun aMaliciousNameInAnAuthenticatedWireFrameNeverCreatesAFile() = runBlocking(Dispatchers.Default) {
        val fixture = TransferTestFixtures.StoreFixture(temporary.newFolder())
        val handle = fixture.server.start(TransferTestFixtures.identity(), InetAddress.getLoopbackAddress())
        val identity = TransferTestFixtures.identity()
        try {
            TransferTestFixtures.client(identity, handle).use { client ->
                TransferTestFixtures.pair(client, identity, fixture.server)
                val request = TransferRequest.BeginUpload(TransferTestFixtures.id(), client.scope(), TransferTestFixtures.declaration(TransferTestFixtures.wav(), name = "safe_path.wav"))
                val encoded = ByteArrayOutputStream().also { TransferProtocolCodec.writeRequest(it, request) }.toByteArray()
                "../escape.wav".toByteArray(Charsets.UTF_8).copyInto(encoded, 28 + 88)
                client.sendWire(encoded)
                try { client.receive()
                    fail("Expected malicious-name disconnect") } catch (_: IOException) { }
                assertTrue(fixture.backend.lookupCalls.get() == 0 && fixture.partials.listFiles().orEmpty().isEmpty())
            }
        } finally { withTimeout(5000L) { fixture.server.stop().await() } }
    }

    @Test fun weakAndExpiredEphemeralCertificatesAreRejectedBeforeTheyBecomeIdentities() {
        val (weakKey, weakCertificate) = TransferTestFixtures.certificate(bits = 1024)
        try { TransferTlsIdentity(weakKey, weakCertificate)
            fail("Expected weak certificate rejection") } catch (_: CertificateException) { }
        val (expiredKey, expiredCertificate) = TransferTestFixtures.certificate(validUntil = System.currentTimeMillis() - 1000L)
        try { TransferTlsIdentity(expiredKey, expiredCertificate)
            fail("Expected expired certificate rejection") } catch (_: CertificateException) { }
        assertTrue(TransferTestFixtures.identity().toString() == "<local-tls-identity>")
    }
}
