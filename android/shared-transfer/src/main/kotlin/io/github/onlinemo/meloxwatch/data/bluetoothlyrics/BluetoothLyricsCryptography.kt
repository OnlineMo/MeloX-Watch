package io.github.onlinemo.meloxwatch.data.bluetoothlyrics

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A local comparison value is not a password, export value or diagnostic identifier. */
class BluetoothLyricsComparison internal constructor(value: CharArray) : AutoCloseable {
    private val lock = Any()
    private var characters: CharArray? = value

    fun forLocalUi(): String? = synchronized(lock) { characters?.let(::String) }

    override fun close() = synchronized(lock) {
        characters?.fill('\u0000')
        characters = null
    }

    override fun toString(): String = "<local-comparison>"
}

internal class BluetoothLyricsHello(
    val role: BluetoothLyricsRole,
    private val nonce: ByteArray,
    private val encodedKey: ByteArray,
) : AutoCloseable {
    init { require(nonce.size == 16 && encodedKey.size in 1..128) }

    fun body(): ByteArray = ByteArrayOutputStream(160).also { bytes ->
        DataOutputStream(bytes).apply {
            writeByte(role.wire)
            write(nonce)
            writeShort(encodedKey.size)
            write(encodedKey)
        }
    }.toByteArray()

    fun keyBytes(): ByteArray = encodedKey.copyOf()

    fun commitment(): ByteArray = MessageDigest.getInstance("SHA-256").apply {
        update(PROTOCOL_CONTEXT)
        update(body())
    }.digest()

    override fun close() { nonce.fill(0); encodedKey.fill(0) }
    override fun toString(): String = "<ephemeral-hello>"
}

/** Commit/reveal precedes comparison, so peer-controlled nonces cannot adapt to revealed hellos. */
class BluetoothLyricsHandshake private constructor(
    val role: BluetoothLyricsRole,
    private var keyPair: KeyPair?,
    internal val hello: BluetoothLyricsHello,
) : AutoCloseable {
    internal fun derive(peer: BluetoothLyricsHello): BluetoothLyricsSessionKeys {
        val owned = keyPair ?: throw BluetoothLyricsException(BluetoothLyricsIssue.CLOSED)
        if (peer.role == role) throw BluetoothLyricsException(BluetoothLyricsIssue.WRONG_PEER)
        var secret: ByteArray? = null
        var extracted: ByteArray? = null
        var codeBytes: ByteArray? = null
        try {
            val public = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peer.keyBytes())) as? ECPublicKey
                ?: throw BluetoothLyricsException(BluetoothLyricsIssue.AUTHENTICATION_FAILED)
            val expected = owned.public as ECPublicKey
            if (public.params.order != expected.params.order || public.params.cofactor != expected.params.cofactor ||
                public.params.generator != expected.params.generator || public.params.curve.field != expected.params.curve.field ||
                public.params.curve.a != expected.params.curve.a || public.params.curve.b != expected.params.curve.b) {
                throw BluetoothLyricsException(BluetoothLyricsIssue.AUTHENTICATION_FAILED)
            }
            secret = KeyAgreement.getInstance("ECDH").apply {
                init(owned.private)
                doPhase(public, true)
            }.generateSecret()
            if (secret.size != 32) throw BluetoothLyricsException(BluetoothLyricsIssue.AUTHENTICATION_FAILED)
            val watch = if (role == BluetoothLyricsRole.WATCH) hello else peer
            val phone = if (role == BluetoothLyricsRole.PHONE) hello else peer
            val transcript = MessageDigest.getInstance("SHA-256").apply {
                update(PROTOCOL_CONTEXT)
                update(watch.commitment())
                update(phone.commitment())
                update(watch.body())
                update(phone.body())
            }.digest()
            extracted = lyricsHmac(transcript, secret)
            val watchToPhone = lyricsHmac(extracted, "watch-to-phone".toByteArray(Charsets.US_ASCII), transcript, byteArrayOf(1))
            val phoneToWatch = lyricsHmac(extracted, "phone-to-watch".toByteArray(Charsets.US_ASCII), transcript, byteArrayOf(1))
            codeBytes = lyricsHmac(extracted, "comparison".toByteArray(Charsets.US_ASCII), transcript)
            val numeric = (ByteBuffer.wrap(codeBytes).long and Long.MAX_VALUE) % 100_000_000L
            val comparison = BluetoothLyricsComparison(String.format(Locale.ROOT, "%08d", numeric).toCharArray())
            keyPair = null
            return if (role == BluetoothLyricsRole.WATCH) {
                BluetoothLyricsSessionKeys(role, watchToPhone, phoneToWatch, transcript, comparison)
            } else {
                BluetoothLyricsSessionKeys(role, phoneToWatch, watchToPhone, transcript, comparison)
            }
        } catch (error: BluetoothLyricsException) {
            throw error
        } catch (_: Exception) {
            throw BluetoothLyricsException(BluetoothLyricsIssue.CRYPTO_UNAVAILABLE)
        } finally {
            secret?.fill(0)
            extracted?.fill(0)
            codeBytes?.fill(0)
        }
    }

    override fun close() {
        keyPair = null
        hello.close()
    }

    override fun toString(): String = "<ephemeral-lyrics-handshake>"

    companion object {
        fun create(role: BluetoothLyricsRole): BluetoothLyricsHandshake = try {
            val random = SecureRandom()
            val pair = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"), random)
            }.generateKeyPair()
            val nonce = ByteArray(16).also(random::nextBytes)
            BluetoothLyricsHandshake(role, pair, BluetoothLyricsHello(role, nonce, pair.public.encoded))
        } catch (_: Exception) {
            throw BluetoothLyricsException(BluetoothLyricsIssue.CRYPTO_UNAVAILABLE)
        }
    }
}

/** Separate keys and direction/sequence nonces prevent reflection and nonce reuse in one session. */
class BluetoothLyricsSessionKeys internal constructor(
    val role: BluetoothLyricsRole,
    private val outgoing: ByteArray,
    private val incoming: ByteArray,
    private val transcript: ByteArray,
    val comparison: BluetoothLyricsComparison,
) : AutoCloseable {
    private val lock = Any()
    private var open = true

    internal fun encrypt(sequence: Long, header: ByteArray, plaintext: ByteArray): ByteArray =
        transform(true, sequence, header, plaintext)

    internal fun decrypt(sequence: Long, header: ByteArray, ciphertext: ByteArray): ByteArray =
        transform(false, sequence, header, ciphertext)

    private fun transform(encrypt: Boolean, sequence: Long, header: ByteArray, body: ByteArray): ByteArray =
        synchronized(lock) {
            if (!open || sequence <= 0L) throw BluetoothLyricsException(BluetoothLyricsIssue.CLOSED)
            val direction = if (encrypt) role else if (role == BluetoothLyricsRole.WATCH) {
                BluetoothLyricsRole.PHONE
            } else BluetoothLyricsRole.WATCH
            val nonce = ByteBuffer.allocate(12).putInt(direction.wire).putLong(sequence).array()
            try {
                Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
                        SecretKeySpec(if (encrypt) outgoing else incoming, "AES"), GCMParameterSpec(128, nonce))
                    updateAAD(header)
                    updateAAD(transcript)
                }.doFinal(body)
            } catch (_: Exception) {
                throw BluetoothLyricsException(BluetoothLyricsIssue.AUTHENTICATION_FAILED)
            } finally { nonce.fill(0) }
        }

    override fun close() = synchronized(lock) {
        if (!open) return@synchronized
        open = false
        outgoing.fill(0)
        incoming.fill(0)
        transcript.fill(0)
        comparison.close()
    }

    override fun toString(): String = "<ephemeral-lyrics-keys>"
}

private fun lyricsHmac(key: ByteArray, vararg fragments: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(key, "HmacSHA256"))
        fragments.forEach(::update)
    }.doFinal()

private val PROTOCOL_CONTEXT =
    ("MeloX Bluetooth Lyrics|1|" + BluetoothLyricsLimits.SERVICE_UUID.toString()).toByteArray(Charsets.US_ASCII)
