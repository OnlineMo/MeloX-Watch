package io.github.onlinemo.meloxwatch.data.bluetoothlyrics

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each fixture generates fresh keys/nonces in memory. Assertions never print key or code values. */
class BluetoothLyricsWireTest {
    @Test fun aRealCommitRevealPairHasTheSameComparisonWithoutExportingIt() {
        WirePair().use { pair ->
            val left = pair.watch.comparison.forLocalUi()
            val right = pair.phone.comparison.forLocalUi()
            assertTrue(left != null && left.length == 8 && left.all(Char::isDigit))
            assertTrue(left == right)
            assertFalse(pair.watch.comparison.toString().contains(checkNotNull(left)))
            assertFalse(pair.watch.authenticated)
            assertFalse(pair.phone.authenticated)
        }
    }

    @Test fun titleAndLyricsCannotBeWrittenOrReadBeforeBothConfirmations() {
        WirePair().use { pair ->
            expectIssue(BluetoothLyricsIssue.NOT_CONFIRMED) { pair.watch.writeSnapshot(payload()) }
            expectIssue(BluetoothLyricsIssue.NOT_CONFIRMED) { pair.phone.readMessage() }
            pair.watch.confirmLocal()
            expectIssue(BluetoothLyricsIssue.NOT_CONFIRMED) { pair.watch.writeSnapshot(payload()) }
            assertFalse(pair.watch.authenticated)
            assertFalse(pair.phone.authenticated)
        }
    }

    @Test fun authenticatedPairRoundTripsOneBoundedSnapshotAndActualPlaybackState() {
        WirePair().use { pair ->
            pair.authenticate()
            val sent = BluetoothLyricsPayload(2L, 2400L, 2000L, BluetoothLyricsPlayback.PLAYING,
                "Fixture title", "Fixture artist", "当前行\nsame-time line", "translation", true, true)
            pair.watch.writeSnapshot(sent)
            val frame = pair.watchOutput.take()
            assertTrue(frame.size <= BluetoothLyricsLimits.FRAME_BYTES)
            pair.phoneInput.install(frame)
            val received = (pair.phone.readMessage() as BluetoothLyricsMessage.Snapshot).payload
            assertEquals(sent.trackEpoch, received.trackEpoch)
            assertEquals(sent.mediaPositionMs, received.mediaPositionMs)
            assertEquals(sent.lineMediaTimeMs, received.lineMediaTimeMs)
            assertEquals(sent.playback, received.playback)
            assertEquals(sent.original, received.original)
            assertEquals(sent.translation, received.translation)
            assertEquals(sent.title, received.title)
            assertEquals(sent.artist, received.artist)
            assertTrue(received.shortened)
            assertTrue(received.isPlaying)
            assertFalse(received.toString().contains(sent.original))
        }
    }

    @Test fun authenticatedHeartbeatContainsNoPayload() {
        WirePair().use { pair ->
            pair.authenticate()
            pair.watch.writeHeartbeat()
            val frame = pair.watchOutput.take()
            assertEquals(BluetoothLyricsLimits.HEADER_BYTES + BluetoothLyricsLimits.TAG_BYTES, frame.size)
            pair.phoneInput.install(frame)
            assertEquals(BluetoothLyricsMessage.Heartbeat, pair.phone.readMessage())
        }
    }

    @Test fun modifiedHelloCannotMatchAnAlreadyCommittedPeer() {
        val local = BluetoothLyricsHandshake.create(BluetoothLyricsRole.PHONE)
        val peer = BluetoothLyricsHandshake.create(BluetoothLyricsRole.WATCH)
        val input = MutableBytesInput()
        val output = TransientBytesOutput()
        try {
            val commitment = peer.hello.commitment()
            commitment[0] = (commitment[0].toInt() xor 1).toByte()
            input.install(handshakeFrame(1, commitment) + handshakeFrame(2, peer.hello.body()))
            expectIssue(BluetoothLyricsIssue.AUTHENTICATION_FAILED) {
                BluetoothLyricsWire.establish(input, output, local).close()
            }
        } finally { local.close(); peer.close(); input.close(); output.close() }
    }

    @Test fun theSameRoleIsRejectedBeforeComparisonCanBeApproved() {
        val local = BluetoothLyricsHandshake.create(BluetoothLyricsRole.WATCH)
        val peer = BluetoothLyricsHandshake.create(BluetoothLyricsRole.WATCH)
        val input = MutableBytesInput()
        val output = TransientBytesOutput()
        try {
            input.install(handshakeFrames(peer))
            expectIssue(BluetoothLyricsIssue.WRONG_PEER) {
                BluetoothLyricsWire.establish(input, output, local).close()
            }
        } finally { local.close(); peer.close(); input.close(); output.close() }
    }

    @Test fun aRecordedCiphertextCannotBeAcceptedTwice() {
        RawWatchPeer().use { fixture ->
            fixture.authenticate()
            val frame = fixture.frame(4, rawPayload(), 2L)
            fixture.input.install(frame.copyOf())
            assertTrue(fixture.phone.readMessage() is BluetoothLyricsMessage.Snapshot)
            fixture.input.install(frame)
            expectIssue(BluetoothLyricsIssue.INVALID_FRAME) { fixture.phone.readMessage() }
        }
    }

    @Test fun aSequenceGapCannotSkipAheadWithinTheSameKey() {
        RawWatchPeer().use { fixture ->
            fixture.authenticate()
            fixture.input.install(fixture.frame(4, rawPayload(), 3L))
            expectIssue(BluetoothLyricsIssue.INVALID_FRAME) { fixture.phone.readMessage() }
        }
    }

    @Test fun olderTrackEpochCannotReplaceANewerSnapshot() {
        RawWatchPeer().use { fixture ->
            fixture.authenticate()
            fixture.input.install(fixture.frame(4, rawPayload(epoch = 2L), 2L))
            assertTrue(fixture.phone.readMessage() is BluetoothLyricsMessage.Snapshot)
            fixture.input.install(fixture.frame(4, rawPayload(epoch = 1L), 3L))
            expectIssue(BluetoothLyricsIssue.INVALID_FRAME) { fixture.phone.readMessage() }
        }
    }

    @Test fun ciphertextAndHeaderTamperingFailAuthentication() {
        for (headerChanged in listOf(false, true)) RawWatchPeer().use { fixture ->
            fixture.authenticate()
            val frame = fixture.frame(4, rawPayload(), 2L)
            val index = if (headerChanged) 5 else frame.lastIndex
            frame[index] = (frame[index].toInt() xor 1).toByte()
            fixture.input.install(frame)
            expectIssue(BluetoothLyricsIssue.AUTHENTICATION_FAILED) { fixture.phone.readMessage() }
        }
    }

    @Test fun oversizedFrameIsRejectedBeforeItsBodyIsRead() {
        RawWatchPeer().use { fixture ->
            fixture.authenticate()
            fixture.input.install(frameHeader(4, BluetoothLyricsLimits.PLAINTEXT_BYTES +
                BluetoothLyricsLimits.TAG_BYTES + 1, 2L))
            expectIssue(BluetoothLyricsIssue.INVALID_FRAME) { fixture.phone.readMessage() }
        }
    }

    @Test fun malformedUtf8NulOversizedFieldAndTrailingBytesAreRejectedAfterAuthentication() {
        val bodies = listOf(
            rawPayload(original = byteArrayOf(0xc3.toByte(), 0x28)),
            rawPayload(original = byteArrayOf(0)),
            rawPayload(original = ByteArray(BluetoothLyricsLimits.ORIGINAL_BYTES + 1) { 65 }),
            rawPayload() + byteArrayOf(1),
        )
        for (body in bodies) RawWatchPeer().use { fixture ->
            fixture.authenticate()
            fixture.input.install(fixture.frame(4, body, 2L))
            expectIssue(BluetoothLyricsIssue.INVALID_FRAME) { fixture.phone.readMessage() }
            body.fill(0)
        }
    }

    @Test fun unknownSchemaAndUnknownMessageTypeCloseTheReadPath() {
        for (newSchema in listOf(false, true)) RawWatchPeer().use { fixture ->
            fixture.authenticate()
            val frame = if (newSchema) fixture.frame(4, rawPayload(), 2L).also { it[4] = 2 }
                else fixture.frame(99, rawPayload(), 2L)
            fixture.input.install(frame)
            expectIssue(if (newSchema) BluetoothLyricsIssue.INCOMPATIBLE_SCHEMA
                else BluetoothLyricsIssue.INVALID_FRAME) { fixture.phone.readMessage() }
        }
    }

    @Test fun aPausedFrameCannotClaimThatThePlayerIsStillPlaying() {
        RawWatchPeer().use { fixture ->
            fixture.authenticate()
            val body = rawPayload().also { it[26] = 1 }
            fixture.input.install(fixture.frame(4, body, 2L))
            expectIssue(BluetoothLyricsIssue.INVALID_FRAME) { fixture.phone.readMessage() }
            body.fill(0)
        }
    }

    @Test fun closeRevokesEncryptionAndClearsTheComparison() {
        WirePair().use { pair ->
            pair.authenticate()
            pair.watch.close()
            assertFalse(pair.watch.authenticated)
            assertTrue(pair.watch.comparison.forLocalUi() == null)
            expectIssue(BluetoothLyricsIssue.CLOSED) { pair.watch.writeSnapshot(payload()) }
        }
    }

    @Test fun clippingKeepsValidUnicodeAndMarksTheDisplayedShortening() {
        val clipped = BluetoothLyricsText.clip("A\u0301B", 2)
        assertTrue(clipped.shortened)
        assertEquals("", clipped.value)
        val complete = BluetoothLyricsText.clip("A\u0301B", 4)
        assertEquals("A\u0301B", complete.value)
        assertFalse(complete.shortened)
        val pair = BluetoothLyricsText.clip("\uD83D\uDE00Z", 4)
        assertEquals("\uD83D\uDE00", pair.value)
        assertTrue(pair.shortened)
        expectIssue(BluetoothLyricsIssue.INVALID_FRAME) { BluetoothLyricsText.clip("\uD800", 8) }
    }

    private fun payload() = BluetoothLyricsPayload(1L, 0L, -1L, BluetoothLyricsPlayback.PAUSED,
        "Fixture title", "", "Fixture line", "", isPlaying = false)
}

private class WirePair : AutoCloseable {
    private val watchHello = BluetoothLyricsHandshake.create(BluetoothLyricsRole.WATCH)
    private val phoneHello = BluetoothLyricsHandshake.create(BluetoothLyricsRole.PHONE)
    val watchInput = MutableBytesInput()
    val phoneInput = MutableBytesInput()
    val watchOutput = TransientBytesOutput()
    private val phoneOutput = TransientBytesOutput()
    private var watchOwner: BluetoothLyricsWire? = null
    private var phoneOwner: BluetoothLyricsWire? = null
    val watch: BluetoothLyricsWire get() = checkNotNull(watchOwner)
    val phone: BluetoothLyricsWire get() = checkNotNull(phoneOwner)
    init {
        try {
            watchInput.install(handshakeFrames(phoneHello))
            phoneInput.install(handshakeFrames(watchHello))
            watchOwner = BluetoothLyricsWire.establish(watchInput, watchOutput, watchHello)
            phoneOwner = BluetoothLyricsWire.establish(phoneInput, phoneOutput, phoneHello)
            watchOutput.reset()
            phoneOutput.reset()
        } catch (error: Throwable) { close(); throw error }
    }
    fun authenticate() {
        watch.confirmLocal()
        phone.confirmLocal()
        watchInput.install(phoneOutput.take())
        phoneInput.install(watchOutput.take())
        watch.readPeerConfirmation()
        phone.readPeerConfirmation()
        assertTrue(watch.authenticated && phone.authenticated)
    }
    override fun close() {
        watchOwner?.close(); phoneOwner?.close()
        watchHello.close(); phoneHello.close()
        watchInput.close(); phoneInput.close()
        watchOutput.close(); phoneOutput.close()
    }
}

private class RawWatchPeer : AutoCloseable {
    private val watchHello = BluetoothLyricsHandshake.create(BluetoothLyricsRole.WATCH)
    private val phoneHello = BluetoothLyricsHandshake.create(BluetoothLyricsRole.PHONE)
    val input = MutableBytesInput()
    private val output = TransientBytesOutput()
    private var phoneOwner: BluetoothLyricsWire? = null
    private var rawKeys: BluetoothLyricsSessionKeys? = null
    val phone: BluetoothLyricsWire get() = checkNotNull(phoneOwner)
    init {
        try {
            input.install(handshakeFrames(watchHello))
            phoneOwner = BluetoothLyricsWire.establish(input, output, phoneHello)
            rawKeys = watchHello.derive(phoneHello.hello)
            output.reset()
        } catch (error: Throwable) { close(); throw error }
    }
    fun frame(type: Int, body: ByteArray, sequence: Long): ByteArray {
        val header = frameHeader(type, body.size + BluetoothLyricsLimits.TAG_BYTES, sequence)
        return header + checkNotNull(rawKeys).encrypt(sequence, header, body)
    }
    fun authenticate() {
        phone.confirmLocal()
        input.install(frame(3, byteArrayOf(1), 1L))
        phone.readPeerConfirmation()
        output.reset()
    }
    override fun close() {
        phoneOwner?.close(); rawKeys?.close()
        watchHello.close(); phoneHello.close()
        input.close(); output.close()
    }
}

private class MutableBytesInput : InputStream() {
    private var value = ByteArray(0)
    private var offset = 0
    fun install(next: ByteArray) {
        check(offset == value.size)
        value.fill(0)
        value = next
        offset = 0
    }
    override fun read(): Int = if (offset < value.size) value[offset++].toInt() and 255 else -1
    override fun read(destination: ByteArray, start: Int, length: Int): Int {
        if (length == 0) return 0
        if (offset == value.size) return -1
        val count = minOf(length, value.size - offset)
        value.copyInto(destination, start, offset, offset + count)
        offset += count
        return count
    }
    override fun close() { value.fill(0); value = ByteArray(0); offset = 0 }
    override fun toString(): String = "<transient-wire-input>"
}

private class TransientBytesOutput : ByteArrayOutputStream() {
    fun take(): ByteArray = toByteArray().also { reset() }
    override fun reset() { buf.fill(0); super.reset() }
    override fun close() { reset() }
    override fun toString(): String = "<transient-wire-output>"
}

private fun handshakeFrames(handshake: BluetoothLyricsHandshake): ByteArray =
    handshakeFrame(1, handshake.hello.commitment()) + handshakeFrame(2, handshake.hello.body())

private fun handshakeFrame(type: Int, body: ByteArray): ByteArray = frameHeader(type, body.size, 0L) + body

private fun frameHeader(type: Int, length: Int, sequence: Long): ByteArray =
    ByteBuffer.allocate(16).putInt(0x4d584c59).put(1).put(type.toByte()).putShort(length.toShort()).putLong(sequence).array()

private fun rawPayload(epoch: Long = 1L, original: ByteArray = "Fixture line".toByteArray()): ByteArray =
    ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).apply {
            writeLong(epoch); writeLong(0L); writeLong(-1L)
            writeByte(BluetoothLyricsPlayback.PAUSED.wire); writeByte(0); writeByte(0)
            writeShort(0); writeShort(0)
            writeShort(original.size); write(original)
            writeShort(0)
        }
    }.toByteArray()

private fun expectIssue(issue: BluetoothLyricsIssue, operation: () -> Unit) {
    var caught = false
    try { operation() }
    catch (error: BluetoothLyricsException) { assertEquals(issue, error.issue); caught = true }
    assertTrue(caught)
}
