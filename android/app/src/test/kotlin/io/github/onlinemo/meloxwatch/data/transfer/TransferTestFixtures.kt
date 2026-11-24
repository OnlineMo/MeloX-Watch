package io.github.onlinemo.meloxwatch.data.transfer

import android.os.CancellationSignal
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import javax.security.auth.x500.X500Principal
import kotlin.math.sin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Only ephemeral runtime identities. No certificate, private key, address or token fixture is stored. */
internal object TransferTestFixtures {
    fun id(): String = UUID.randomUUID().toString()
    fun fingerprint(): TransferFingerprint = TransferFingerprint.digest(ByteArray(32).also(SecureRandom()::nextBytes))
    fun hash(bytes: ByteArray): TransferHash = TransferHash.digest(bytes)

    fun certificate(bits: Int = 2048, validUntil: Long = System.currentTimeMillis() + 86_400_000L): Pair<java.security.PrivateKey, X509Certificate> {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()
        val algorithm = der(0x30, byteArrayOf(0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b, 0x05, 0x00))
        val principal = X500Principal("CN=Ephemeral Transfer Test").encoded
        val formatter = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val from = minOf(System.currentTimeMillis() - 60_000L, validUntil - 60_000L)
        val validity = sequence(der(0x17, formatter.format(Date(from)).toByteArray(Charsets.US_ASCII)), der(0x17, formatter.format(Date(validUntil)).toByteArray(Charsets.US_ASCII)))
        val serial = BigInteger(127, SecureRandom()).add(BigInteger.ONE).toByteArray()
        val tbs = sequence(der(0xa0, der(0x02, byteArrayOf(2))), der(0x02, serial), algorithm, principal, validity, principal, pair.public.encoded)
        val signer = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private)
            update(tbs) }
        val encoded = sequence(tbs, algorithm, der(0x03, byteArrayOf(0) + signer.sign()))
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(encoded)) as X509Certificate
        return pair.private to certificate
    }
    fun identity(): TransferTlsIdentity { val (key, certificate) = certificate()
        return TransferTlsIdentity(key, certificate) }
    private fun sequence(vararg bytes: ByteArray): ByteArray = der(0x30, bytes.fold(byteArrayOf()) { result, value -> result + value })
    private fun der(tag: Int, value: ByteArray): ByteArray {
        val length = if (value.size < 128) byteArrayOf(value.size.toByte()) else if (value.size < 256) byteArrayOf(0x81.toByte(), value.size.toByte()) else byteArrayOf(0x82.toByte(), (value.size ushr 8).toByte(), value.size.toByte())
        return byteArrayOf(tag.toByte()) + length + value
    }

    fun wav(frames: Int = 800, frequency: Int = 440): ByteArray {
        val buffer = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(buffer.capacity() - 8).put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
        buffer.put("data".toByteArray(Charsets.US_ASCII)).putInt(frames * 2)
        repeat(frames) { frame -> buffer.putShort((sin(2.0 * Math.PI * frequency * frame / 8000) * 8192).toInt().toShort()) }
        return buffer.array()
    }
    fun declaration(bytes: ByteArray, name: String = "Selected.wav", declared: Long? = bytes.size.toLong()): TransferUploadDeclaration =
        TransferUploadDeclaration(TransferUploadKind.AUDIO, name, "audio/wav", declared, hash(bytes), 0L)

    class Clock(var now: Long = 0L) : TransferClock { override fun monotonicMs(): Long = now }
    class Authorized(val authority: TransferSessionAuthority, val peer: TransferFingerprint, val opening: TransferOpening, val scope: TransferScope) {
        fun permit(capability: TransferCapability = TransferCapability.UPLOAD_AUDIO): TransferPermit = authority.authorize(peer, scope, capability)
    }
    fun authorized(capabilities: Set<TransferCapability> = setOf(TransferCapability.UPLOAD_AUDIO), clock: TransferClock = Clock()): Authorized {
        val authority = TransferSessionAuthority(clock)
        val peer = fingerprint()
        val opening = authority.begin(fingerprint())
        authority.pair(peer, TransferRequest.Pair(id(), opening.target, opening.sessionId, checkNotNull(authority.pairingCodeForLocalUi()), capabilities))
        authority.approveForLocalUi(peer, capabilities)
        val authenticated = authority.authenticate(peer, opening.target, opening.sessionId)
        return Authorized(authority, peer, opening, TransferScope(opening.target, opening.sessionId, authenticated.bearer))
    }
    fun binding(auth: Authorized, declaration: TransferUploadDeclaration, operationId: String = id()): TransferOperationBinding =
        TransferProtocolCodec.operationBinding(auth.peer, TransferRequest.BeginUpload(operationId, auth.scope, declaration))

    class Accounting(private val owned: File) : TransferQuotaAccounting {
        private val lock = Any()
        private val reservations = linkedMapOf<String, Pair<TransferUploadKind, Long>>()
        var externalAudioReservation: Long = 0L
        var externalThemeReservation: Long = 0L
        val reservationCount: Int get() = synchronized(lock) { reservations.size }
        override suspend fun reserve(ticket: String, kind: TransferUploadKind, totalBytes: Long): TransferQuotaUsage = synchronized(lock) {
            require(totalBytes >= 0L)
            reservations[ticket] = kind to totalBytes
            val audio = if (owned.exists()) owned.listFiles().orEmpty().filter { it.extension == "wav" }.sumOf { it.length() } else 0L
            val otherAudio = reservations.filterKeys { it != ticket }.values.filter { it.first == TransferUploadKind.AUDIO }.sumOf { it.second } + externalAudioReservation
            val otherTheme = reservations.filterKeys { it != ticket }.values.filter { it.first == TransferUploadKind.THEME }.sumOf { it.second } + externalThemeReservation
            TransferQuotaUsage(audio, otherAudio, 0L, otherTheme)
        }
        override suspend fun release(ticket: String) { synchronized(lock) { reservations.remove(ticket) } }
    }

    class StoreFixture(
        root: File,
        validator: TransferContentValidator = PcmWavTransferValidator,
        space: TransferDiskSpace = TransferDiskSpace { TransferLimits.FREE_RESERVE_BYTES + TransferLimits.AUDIO_BYTES * 2 },
        clock: TransferClock = TransferClock { System.nanoTime() / 1_000_000L },
    ) {
        val providerIo = LocalProviderIo()
        val owned = File(root, "owned").also { it.mkdirs() }
        val partials = File(root, "partials")
        val accounting = Accounting(owned)
        val store = TransferUploadStore(partials, providerIo, accounting, validator, space, clock)
        val backend = FileBackend(File(root, "receipts"), owned)
        val server = LocalTransferServer(store, backend)
    }

    /** Actual private file copy + durable receipt, independent of app Room acceptance. */
    class FileBackend(private val receipts: File, private val owned: File) : TransferBackend {
        val readCalls = AtomicInteger()
        val commitCalls = AtomicInteger()
        val lookupCalls = AtomicInteger()
        val closeCalls = AtomicInteger()
        var onInstalled: (File, CancellationSignal) -> Unit = { _, _ -> }
        var onCommitted: (TransferReceipt) -> Unit = {}
        override suspend fun committed(binding: TransferOperationBinding, permit: TransferPermit): TransferReceipt? {
            permit.checkActive()
            lookupCalls.incrementAndGet()
            val file = File(receipts, binding.operationId + ".receipt")
            if (!file.isFile) return null
            val previous = file.inputStream().use { TransferProtocolCodec.readResponse(it) } as TransferResponse.Completed
            if (!previous.receipt.binding.sameOperation(binding)) throw TransferException(TransferIssue.CONFLICT)
            permit.checkActive()
            return previous.receipt
        }
        override suspend fun commitUpload(binding: TransferOperationBinding, upload: TransferVerifiedUpload, permit: TransferPermit): TransferReceipt {
            commitCalls.incrementAndGet()
            return upload.readOnPhysicalWorker { source, signal ->
                owned.mkdirs()
                receipts.mkdirs()
                val resultId = id()
                val installed = File(owned, resultId + ".wav")
                var count = 0L
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                Files.newInputStream(source.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                    Files.newOutputStream(installed.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { output ->
                        val bytes = ByteArray(TransferLimits.CHUNK_BYTES)
                        while (true) {
                            signal.throwIfCanceled()
                            permit.checkActive()
                            val read = input.read(bytes)
                            if (read < 0) break
                            check(read > 0)
                            output.write(bytes, 0, read)
                            count += read
                            digest.update(bytes, 0, read)
                        }
                    }
                }
                check(count == upload.actualBytes && TransferHash.fromWire(digest.digest()) == upload.sha256)
                FileChannel.open(installed.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
                // This installed marker models retained recovery state; it is never treated as committed.
                File(receipts, binding.operationId + ".installed").writeBytes(byteArrayOf(1))
                onInstalled(installed, signal)
                permit.claimCommit(binding)
                val receipt = TransferReceipt(binding, TransferReceiptKind.AUDIO_COMMITTED, 1L, resultId, count)
                val staged = File(receipts, id() + ".tmp")
                staged.outputStream().use { TransferProtocolCodec.writeResponse(it, TransferResponse.Completed(binding.operationId, receipt, false)) }
                FileChannel.open(staged.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
                Files.move(staged.toPath(), File(receipts, binding.operationId + ".receipt").toPath(), StandardCopyOption.ATOMIC_MOVE)
                upload.recordCommitted(receipt)
                onCommitted(receipt)
                receipt
            }
        }
        override suspend fun readPage(request: TransferRequest.ReadPage, permit: TransferPermit): TransferCatalogPage {
            permit.checkActive()
            readCalls.incrementAndGet()
            return TransferCatalogPage(request.kind, request.viewId ?: id(), request.expectedRevision ?: 1L, request.expectedLibraryRevision ?: TransferLibraryRevision(1L, 0L), 0L, 0L, emptyList(), null)
        }
        override suspend fun readDisplay(request: TransferRequest.ReadDisplay, permit: TransferPermit): TransferDisplayState {
            permit.checkActive()
            throw TransferException(TransferIssue.UNSUPPORTED_TYPE)
        }
        override suspend fun editPlaylist(binding: TransferOperationBinding, request: TransferRequest.EditPlaylist, permit: TransferPermit): TransferReceipt = throw TransferException(TransferIssue.UNSUPPORTED_TYPE)
        override suspend fun previewDisplay(request: TransferRequest.PreviewDisplay, permit: TransferPermit): TransferDisplayPreview = throw TransferException(TransferIssue.UNSUPPORTED_TYPE)
        override suspend fun applyDisplay(binding: TransferOperationBinding, request: TransferRequest.ApplyDisplay, permit: TransferPermit): TransferReceipt = throw TransferException(TransferIssue.UNSUPPORTED_TYPE)
        override suspend fun closePeerViews(peer: TransferFingerprint, sessionId: String) { closeCalls.incrementAndGet() }
        fun receiptExists(operationId: String): Boolean = File(receipts, operationId + ".receipt").isFile
        fun installedMarker(operationId: String): Boolean = File(receipts, operationId + ".installed").isFile
    }

    class Client(private val raw: Socket, val tls: SSLSocket, val greeting: TransferResponse.Greeting) : AutoCloseable {
        private val input = BufferedInputStream(tls.inputStream, TransferLimits.FRAME_BYTES)
        private val output = BufferedOutputStream(tls.outputStream, TransferLimits.FRAME_BYTES)
        var scope: TransferScope? = null
        fun request(request: TransferRequest): TransferResponse { TransferProtocolCodec.writeRequest(output, request)
            return TransferProtocolCodec.readResponse(input) }
        fun send(request: TransferRequest) { TransferProtocolCodec.writeRequest(output, request) }
        fun sendWire(bytes: ByteArray) { output.write(bytes)
            output.flush() }
        fun readPage(request: TransferRequest.ReadPage): TransferProtocolCodec.PageCompletion {
            TransferProtocolCodec.writeRequest(output, request)
            return TransferProtocolCodec.readPage(input, request.requestId) {}
        }
        fun receive(): TransferResponse = TransferProtocolCodec.readResponse(input)
        fun scope(): TransferScope = checkNotNull(scope)
        override fun close() { raw.close()
            tls.close() }
    }
    fun client(identity: TransferTlsIdentity, handle: LocalTransferHandle, pin: TransferFingerprint = handle.opening.target): Client {
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), handle.port), TransferLimits.HANDSHAKE_MS.toInt())
            val tls = TransferTls.clientSocket(TransferTls.clientContext(identity, pin), raw)
            tls.startHandshake()
            val greeting = TransferProtocolCodec.readResponse(tls.inputStream) as TransferResponse.Greeting
            check(greeting.target == pin && greeting.sessionId == handle.opening.sessionId)
            return Client(raw, tls, greeting)
        } catch (error: Exception) { raw.close()
            throw error }
    }
    fun pair(client: Client, identity: TransferTlsIdentity, server: LocalTransferServer, capabilities: Set<TransferCapability> = setOf(TransferCapability.UPLOAD_AUDIO)) {
        val greeting = client.greeting
        val response = client.request(TransferRequest.Pair(id(), greeting.target, greeting.sessionId, checkNotNull(server.pairingCodeForLocalUi()), capabilities))
        check(response is TransferResponse.PairPending)
        runBlocking { withTimeout(5000L) { server.sessionState.first { it.pending?.fingerprint == identity.peerFingerprint } } }
        server.approveForLocalUi(identity.peerFingerprint, capabilities)
        val authentication = client.request(TransferRequest.Authenticate(id(), greeting.target, greeting.sessionId)) as TransferResponse.Authenticated
        client.scope = TransferScope(greeting.target, greeting.sessionId, authentication.bearer)
    }
    fun upload(client: Client, bytes: ByteArray, declaration: TransferUploadDeclaration = declaration(bytes), operationId: String = id()): TransferResponse.Completed {
        val begin = client.request(TransferRequest.BeginUpload(operationId, client.scope(), declaration))
        if (begin is TransferResponse.Completed) return begin
        val ticket = (begin as TransferResponse.UploadReady).ticket
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + TransferLimits.CHUNK_BYTES, bytes.size)
            val response = client.request(TransferRequest.UploadChunk(id(), client.scope(), ticket, offset.toLong(), bytes.copyOfRange(offset, end)))
            check(response is TransferResponse.ChunkAccepted && response.nextOffset == end.toLong())
            offset = end
        }
        return client.request(TransferRequest.FinishUpload(id(), client.scope(), ticket)) as TransferResponse.Completed
    }
}
