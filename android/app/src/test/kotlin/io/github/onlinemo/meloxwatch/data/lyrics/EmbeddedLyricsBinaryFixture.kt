package io.github.onlinemo.meloxwatch.data.lyrics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** Real binary tag layouts, independent from the production decoder; these are metadata-only fixtures. */
internal object EmbeddedLyricsBinaryFixture {
    fun resource(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/embedded-lyrics/$name")).use { it.readBytes() }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun join(vararg parts: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        parts.forEach(output::write)
        output.toByteArray()
    }

    fun uint32(value: Long, littleEndian: Boolean = false): ByteArray {
        require(value in 0L..0xffff_ffffL)
        return ByteArray(4) { index ->
            val shift = if (littleEndian) index * 8 else (3 - index) * 8
            (value ushr shift).toByte()
        }
    }

    fun synchsafe(value: Int): ByteArray {
        require(value in 0..0x0fff_ffff)
        return ByteArray(4) { index -> ((value ushr ((3 - index) * 7)) and 0x7f).toByte() }
    }

    fun frame(
        id: String,
        payload: ByteArray,
        version: Int = 3,
        status: Int = 0,
        format: Int = 0,
        declaredSize: Long = payload.size.toLong(),
    ): ByteArray = join(
        id.toByteArray(Charsets.US_ASCII),
        if (version == 4) synchsafe(declaredSize.toInt()) else uint32(declaredSize),
        byteArrayOf(status.toByte(), format.toByte()), payload,
    )

    fun id3(
        vararg frames: ByteArray,
        version: Int = 3,
        revision: Int = 0,
        flags: Int = 0,
        padding: ByteArray = ByteArray(0),
        declaredTagSize: Int? = null,
        audioTail: ByteArray = ByteArray(0),
    ): ByteArray {
        val body = join(*frames, padding)
        return join(
            byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), version.toByte(), revision.toByte(), flags.toByte()),
            synchsafe(declaredTagSize ?: body.size), body, audioTail,
        )
    }

    private fun encoded(text: String, encoding: Int, littleEndian: Boolean): ByteArray = when (encoding) {
        0 -> text.toByteArray(Charsets.ISO_8859_1)
        1 -> join(
            if (littleEndian) byteArrayOf(0xff.toByte(), 0xfe.toByte()) else byteArrayOf(0xfe.toByte(), 0xff.toByte()),
            text.toByteArray(if (littleEndian) Charsets.UTF_16LE else Charsets.UTF_16BE),
        )
        2 -> text.toByteArray(Charsets.UTF_16BE)
        3 -> text.toByteArray(Charsets.UTF_8)
        else -> text.toByteArray(Charsets.UTF_8)
    }

    fun terminated(text: String, encoding: Int, littleEndian: Boolean = true): ByteArray = join(
        if (text.isEmpty()) ByteArray(0) else encoded(text, encoding, littleEndian),
        if (encoding == 1 || encoding == 2) byteArrayOf(0, 0) else byteArrayOf(0),
    )

    fun uslt(
        text: String,
        encoding: Int = 0,
        language: String = "eng",
        descriptor: String = "",
        littleEndian: Boolean = true,
        terminateBody: Boolean = false,
    ): ByteArray = join(
        byteArrayOf(encoding.toByte()), language.toByteArray(Charsets.US_ASCII),
        terminated(descriptor, encoding, littleEndian), encoded(text, encoding, littleEndian),
        if (!terminateBody) ByteArray(0) else if (encoding == 1 || encoding == 2) byteArrayOf(0, 0) else byteArrayOf(0),
    )

    fun sylt(
        events: List<Pair<String, Long>>,
        encoding: Int = 3,
        language: String = "eng",
        descriptor: String = "",
        littleEndian: Boolean = true,
        timestampFormat: Int = 2,
        contentType: Int = 1,
    ): ByteArray {
        val pieces = ArrayList<ByteArray>()
        pieces += byteArrayOf(encoding.toByte())
        pieces += language.toByteArray(Charsets.US_ASCII)
        pieces += byteArrayOf(timestampFormat.toByte(), contentType.toByte())
        pieces += terminated(descriptor, encoding, littleEndian)
        for ((text, time) in events) {
            pieces += terminated(text, encoding, littleEndian)
            pieces += uint32(time)
        }
        return join(*pieces.toTypedArray())
    }

    fun comments(fields: List<ByteArray>, vendor: ByteArray = "fixture".toByteArray(Charsets.UTF_8), count: Long? = null): ByteArray {
        val pieces = ArrayList<ByteArray>()
        pieces += uint32(vendor.size.toLong(), littleEndian = true)
        pieces += vendor
        pieces += uint32(count ?: fields.size.toLong(), littleEndian = true)
        for (field in fields) {
            pieces += uint32(field.size.toLong(), littleEndian = true)
            pieces += field
        }
        return join(*pieces.toTypedArray())
    }

    fun block(type: Int, payload: ByteArray, last: Boolean): ByteArray = join(
        byteArrayOf((type or if (last) 0x80 else 0).toByte(), (payload.size ushr 16).toByte(),
            (payload.size ushr 8).toByte(), payload.size.toByte()), payload,
    )

    fun streamInfo(last: Boolean = false): ByteArray {
        val rateAndFormat = (44_100L shl 44) or (1L shl 41) or (15L shl 36) or 44_100L
        val parameters = ByteArray(8) { index -> (rateAndFormat ushr ((7 - index) * 8)).toByte() }
        val body = join(byteArrayOf(0x10, 0, 0x10, 0), ByteArray(6), parameters, ByteArray(16))
        return block(0, body, last)
    }

    fun flac(vararg blocks: ByteArray, audioTail: ByteArray = ByteArray(0)): ByteArray = join(
        "fLaC".toByteArray(Charsets.US_ASCII), streamInfo(last = blocks.isEmpty()), *blocks, audioTail,
    )

    fun flacComments(fields: List<ByteArray>): ByteArray = flac(block(4, comments(fields), last = true))

    fun vorbisPacket(fields: List<ByteArray>, framing: Int = 1, trailing: ByteArray = ByteArray(0)): ByteArray = join(
        byteArrayOf(3), "vorbis".toByteArray(Charsets.US_ASCII), comments(fields), byteArrayOf(framing.toByte()), trailing,
    )

    fun lyrics(text: String, key: String = "LYRICS"): ByteArray = "$key=$text".toByteArray(Charsets.UTF_8)
}

internal suspend fun readEmbedded(bytes: ByteArray, limits: EmbeddedLyricsLimits = EmbeddedLyricsLimits()): EmbeddedLyricsReadResult =
    EmbeddedLyricsReader(limits).read(ByteArrayInputStream(bytes))

internal fun assertEmbeddedFatal(result: EmbeddedLyricsReadResult, issue: EmbeddedLyricsIssue) {
    assertFalse(result.metadataComplete)
    assertTrue(result.candidates.isEmpty())
    assertEquals(EmbeddedLyricsDiagnostic(issue), result.diagnostics.last())
}

internal fun assertEmbeddedCandidateFailure(result: EmbeddedLyricsReadResult, issue: EmbeddedLyricsIssue, ordinal: Int = 1) {
    assertTrue(result.metadataComplete)
    assertTrue(result.candidates.isEmpty())
    assertEquals(listOf(EmbeddedLyricsDiagnostic(issue, ordinal)), result.diagnostics)
}

internal fun expectEmbeddedImmutable(block: () -> Unit) {
    try {
        block()
    } catch (_: UnsupportedOperationException) {
        return
    }
    throw AssertionError("An acquisition result must not expose a mutable collection alias")
}

internal class EmbeddedCountingStream(bytes: ByteArray, private val shortRead: Int = Int.MAX_VALUE) : InputStream() {
    private val delegate = ByteArrayInputStream(bytes)
    var bytesRead = 0
        private set
    var readCalls = 0
        private set
    var largestRequest = 0
        private set
    var closed = false
        private set

    override fun read(): Int {
        readCalls++
        return delegate.read().also { if (it >= 0) bytesRead++ }
    }

    override fun read(destination: ByteArray, offset: Int, length: Int): Int {
        readCalls++
        largestRequest = maxOf(largestRequest, length)
        return delegate.read(destination, offset, minOf(length, shortRead)).also { if (it > 0) bytesRead += it }
    }

    override fun close() {
        closed = true
        delegate.close()
    }
}
