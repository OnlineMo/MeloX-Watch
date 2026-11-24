package io.github.onlinemo.meloxwatch.data.bluetoothlyrics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

sealed interface BluetoothLyricsMessage {
    class Snapshot(val payload: BluetoothLyricsPayload) : BluetoothLyricsMessage {
        override fun toString(): String = "<received-local-lyrics>"
    }
    data object Heartbeat : BluetoothLyricsMessage
    data object Closed : BluetoothLyricsMessage
}

/** Blocking bounded framing; the Android socket owner supplies physical deadlines and cancellation. */
class BluetoothLyricsWire private constructor(
    private val input: InputStream,
    private val output: OutputStream,
    private val keys: BluetoothLyricsSessionKeys,
) : AutoCloseable {
    val comparison: BluetoothLyricsComparison get() = keys.comparison
    val role: BluetoothLyricsRole get() = keys.role
    private val readLock = Any()
    private val writeLock = Any()
    private var outgoingSequence = 0L
    private var incomingSequence = 0L
    private var highestTrackEpoch = 0L
    @Volatile private var closed = false
    @Volatile private var localConfirmed = false
    @Volatile private var peerConfirmed = false
    val authenticated: Boolean get() = !closed && localConfirmed && peerConfirmed

    /** Only an explicit local comparison action calls this; no title or lyrics is in the message. */
    fun confirmLocal() = synchronized(writeLock) {
        if (localConfirmed || closed) fail(BluetoothLyricsIssue.CLOSED)
        writePacket(CONFIRM, byteArrayOf(1), requireAuthentication = false)
        localConfirmed = true
    }

    fun readPeerConfirmation() = synchronized(readLock) {
        if (peerConfirmed || closed) fail(BluetoothLyricsIssue.CLOSED)
        val packet = readPacket(requireAuthentication = false)
        try {
            if (packet.type != CONFIRM || packet.body.size != 1 || packet.body[0] != 1.toByte()) {
                fail(BluetoothLyricsIssue.NOT_CONFIRMED)
            }
            peerConfirmed = true
        } finally { packet.body.fill(0) }
    }

    fun writeSnapshot(value: BluetoothLyricsPayload) = synchronized(writeLock) {
        if (role != BluetoothLyricsRole.WATCH) fail(BluetoothLyricsIssue.WRONG_PEER)
        val bytes = ByteArrayOutputStream(128)
        DataOutputStream(bytes).apply {
            writeLong(value.trackEpoch)
            writeLong(value.mediaPositionMs)
            writeLong(value.lineMediaTimeMs)
            writeByte(value.playback.wire)
            writeByte(if (value.shortened) 1 else 0)
            writeByte(if (value.isPlaying) 1 else 0)
            writeText(this, value.title, BluetoothLyricsLimits.TITLE_BYTES)
            writeText(this, value.artist, BluetoothLyricsLimits.ARTIST_BYTES)
            writeText(this, value.original, BluetoothLyricsLimits.ORIGINAL_BYTES)
            writeText(this, value.translation, BluetoothLyricsLimits.TRANSLATION_BYTES)
        }
        val body = bytes.toByteArray()
        try { writePacket(SNAPSHOT, body, requireAuthentication = true) }
        finally { body.fill(0) }
    }

    fun writeHeartbeat() = synchronized(writeLock) {
        writePacket(HEARTBEAT, ByteArray(0), requireAuthentication = true)
    }

    fun readMessage(): BluetoothLyricsMessage = synchronized(readLock) {
        val packet = readPacket(requireAuthentication = true)
        try {
            when (packet.type) {
                SNAPSHOT -> {
                    if (role != BluetoothLyricsRole.PHONE) fail(BluetoothLyricsIssue.WRONG_PEER)
                    val payload = parsePayload(packet.body)
                    if (payload.trackEpoch < highestTrackEpoch) fail(BluetoothLyricsIssue.INVALID_FRAME)
                    highestTrackEpoch = payload.trackEpoch
                    BluetoothLyricsMessage.Snapshot(payload)
                }
                HEARTBEAT -> {
                    if (packet.body.isNotEmpty()) fail(BluetoothLyricsIssue.INVALID_FRAME)
                    BluetoothLyricsMessage.Heartbeat
                }
                CLOSE -> {
                    if (packet.body.isNotEmpty()) fail(BluetoothLyricsIssue.INVALID_FRAME)
                    BluetoothLyricsMessage.Closed
                }
                else -> fail(BluetoothLyricsIssue.INVALID_FRAME)
            }
        } finally { packet.body.fill(0) }
    }

    private fun writePacket(type: Int, plaintext: ByteArray, requireAuthentication: Boolean) {
        requireOpen(requireAuthentication)
        if (plaintext.size > BluetoothLyricsLimits.PLAINTEXT_BYTES ||
            outgoingSequence == Long.MAX_VALUE) fail(BluetoothLyricsIssue.INVALID_FRAME)
        // Consume the nonce before physical writing. A failed write retires this entire session.
        val sequence = ++outgoingSequence
        val header = header(type, plaintext.size + BluetoothLyricsLimits.TAG_BYTES, sequence)
        val ciphertext = keys.encrypt(sequence, header, plaintext)
        try {
            requireOpen(requireAuthentication)
            output.write(header)
            output.write(ciphertext)
            output.flush()
            requireOpen(requireAuthentication)
        } finally { ciphertext.fill(0) }
    }

    private fun readPacket(requireAuthentication: Boolean): Packet {
        requireOpen(requireAuthentication)
        val header = ByteArray(BluetoothLyricsLimits.HEADER_BYTES)
        DataInputStream(input).readFully(header)
        val parsed = parseHeader(header)
        if (parsed.length !in BluetoothLyricsLimits.TAG_BYTES..BluetoothLyricsLimits.PLAINTEXT_BYTES + BluetoothLyricsLimits.TAG_BYTES ||
            incomingSequence == Long.MAX_VALUE || parsed.sequence != incomingSequence + 1L ||
            parsed.type !in CONFIRM..CLOSE) fail(BluetoothLyricsIssue.INVALID_FRAME)
        val ciphertext = ByteArray(parsed.length)
        try {
            DataInputStream(input).readFully(ciphertext)
            requireOpen(requireAuthentication)
            val plaintext = keys.decrypt(parsed.sequence, header, ciphertext)
            incomingSequence = parsed.sequence
            return Packet(parsed.type, plaintext)
        } finally { ciphertext.fill(0) }
    }

    private fun requireOpen(requireAuthentication: Boolean) {
        if (closed) fail(BluetoothLyricsIssue.CLOSED)
        if (requireAuthentication && !authenticated) fail(BluetoothLyricsIssue.NOT_CONFIRMED)
    }

    /** Does not wait on a blocked stream lock. The physical owner closes the socket separately. */
    override fun close() {
        closed = true
        localConfirmed = false
        peerConfirmed = false
        keys.close()
    }

    override fun toString(): String = "<authenticated-lyrics-wire>"

    companion object {
        private const val MAGIC = 0x4d584c59
        private const val COMMIT = 1
        private const val HELLO = 2
        private const val CONFIRM = 3
        private const val SNAPSHOT = 4
        private const val HEARTBEAT = 5
        private const val CLOSE = 6

        /** Both commitments are received before either local hello is revealed. */
        fun establish(
            input: InputStream,
            output: OutputStream,
            handshake: BluetoothLyricsHandshake,
        ): BluetoothLyricsWire {
            val commitment = handshake.hello.commitment()
            val peerCommitment: ByteArray
            try {
                writeHandshake(output, COMMIT, commitment)
                peerCommitment = readHandshake(input, COMMIT, 32)
            } finally { commitment.fill(0) }
            val ownBody = handshake.hello.body()
            val peer: BluetoothLyricsHello
            try {
                writeHandshake(output, HELLO, ownBody)
                peer = parseHello(readHandshake(input, HELLO, BluetoothLyricsLimits.HELLO_BYTES))
            } finally { ownBody.fill(0) }
            try {
                if (!MessageDigest.isEqual(peerCommitment, peer.commitment())) {
                    fail(BluetoothLyricsIssue.AUTHENTICATION_FAILED)
                }
                return BluetoothLyricsWire(input, output, handshake.derive(peer))
            } finally {
                peerCommitment.fill(0)
                peer.close()
            }
        }

        private class Packet(val type: Int, val body: ByteArray)
        private class Header(val type: Int, val length: Int, val sequence: Long)

        private fun header(type: Int, length: Int, sequence: Long): ByteArray =
            ByteBuffer.allocate(BluetoothLyricsLimits.HEADER_BYTES)
                .putInt(MAGIC).put(BluetoothLyricsLimits.SCHEMA.toByte()).put(type.toByte())
                .putShort(length.toShort()).putLong(sequence).array()

        private fun parseHeader(bytes: ByteArray): Header {
            val buffer = ByteBuffer.wrap(bytes)
            if (buffer.int != MAGIC) fail(BluetoothLyricsIssue.INVALID_FRAME)
            if (buffer.get().toInt() and 255 != BluetoothLyricsLimits.SCHEMA) {
                fail(BluetoothLyricsIssue.INCOMPATIBLE_SCHEMA)
            }
            return Header(buffer.get().toInt() and 255, buffer.short.toInt() and 65535, buffer.long)
        }

        private fun writeHandshake(output: OutputStream, type: Int, body: ByteArray) {
            if (body.size !in 1..BluetoothLyricsLimits.HELLO_BYTES) fail(BluetoothLyricsIssue.INVALID_FRAME)
            output.write(header(type, body.size, 0L))
            output.write(body)
            output.flush()
        }

        private fun readHandshake(input: InputStream, expectedType: Int, maximum: Int): ByteArray {
            val bytes = ByteArray(BluetoothLyricsLimits.HEADER_BYTES)
            val reader = DataInputStream(input)
            reader.readFully(bytes)
            val frame = parseHeader(bytes)
            if (frame.type != expectedType || frame.sequence != 0L || frame.length !in 1..maximum ||
                expectedType == COMMIT && frame.length != 32) fail(BluetoothLyricsIssue.INVALID_FRAME)
            return ByteArray(frame.length).also(reader::readFully)
        }

        private fun parseHello(body: ByteArray): BluetoothLyricsHello = try {
            val reader = DataInputStream(ByteArrayInputStream(body))
            val roleCode = reader.readUnsignedByte()
            val role = BluetoothLyricsRole.entries.firstOrNull { it.wire == roleCode }
                ?: fail(BluetoothLyricsIssue.WRONG_PEER)
            val nonce = ByteArray(16).also(reader::readFully)
            val keyLength = reader.readUnsignedShort()
            if (keyLength !in 1..128 || keyLength != reader.available()) fail(BluetoothLyricsIssue.INVALID_FRAME)
            val key = ByteArray(keyLength).also(reader::readFully)
            BluetoothLyricsHello(role, nonce, key)
        } catch (error: BluetoothLyricsException) {
            throw error
        } catch (_: Exception) {
            fail(BluetoothLyricsIssue.INVALID_FRAME)
        } finally { body.fill(0) }

        private fun writeText(writer: DataOutputStream, value: String, maximum: Int) {
            val encoded = boundedLyricsUtf8(value, maximum)
            try { writer.writeShort(encoded.size); writer.write(encoded) }
            finally { encoded.fill(0) }
        }

        private fun parsePayload(body: ByteArray): BluetoothLyricsPayload = try {
            val reader = DataInputStream(ByteArrayInputStream(body))
            val epoch = reader.readLong()
            val position = reader.readLong()
            val time = reader.readLong()
            val stateCode = reader.readUnsignedByte()
            val state = BluetoothLyricsPlayback.entries.firstOrNull { it.wire == stateCode }
                ?: fail(BluetoothLyricsIssue.INVALID_FRAME)
            val shortened = when (reader.readUnsignedByte()) {
                0 -> false
                1 -> true
                else -> fail(BluetoothLyricsIssue.INVALID_FRAME)
            }
            val playing = when (reader.readUnsignedByte()) {
                0 -> false
                1 -> true
                else -> fail(BluetoothLyricsIssue.INVALID_FRAME)
            }
            val title = readText(reader, BluetoothLyricsLimits.TITLE_BYTES)
            val artist = readText(reader, BluetoothLyricsLimits.ARTIST_BYTES)
            val original = readText(reader, BluetoothLyricsLimits.ORIGINAL_BYTES)
            val translation = readText(reader, BluetoothLyricsLimits.TRANSLATION_BYTES)
            if (reader.available() != 0) fail(BluetoothLyricsIssue.INVALID_FRAME)
            BluetoothLyricsPayload(epoch, position, time, state, title, artist, original, translation, shortened, playing)
        } catch (error: BluetoothLyricsException) {
            throw error
        } catch (_: Exception) {
            fail(BluetoothLyricsIssue.INVALID_FRAME)
        }

        private fun readText(reader: DataInputStream, maximum: Int): String {
            val length = reader.readUnsignedShort()
            if (length > maximum || length > reader.available()) fail(BluetoothLyricsIssue.INVALID_FRAME)
            val encoded = ByteArray(length).also(reader::readFully)
            return try {
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(encoded)).toString()
                    .also { if (it.any { character -> character == '\u0000' }) fail(BluetoothLyricsIssue.INVALID_FRAME) }
            } finally { encoded.fill(0) }
        }

        private fun fail(issue: BluetoothLyricsIssue): Nothing = throw BluetoothLyricsException(issue)
    }
}
