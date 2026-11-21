package io.github.onlinemo.meloxwatch.data.fonts

import android.graphics.Typeface
import android.os.CancellationSignal
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Original OFL fixtures are never rewritten; mutations exist only in individual test byte arrays. */
internal object LyricsFontTestFixtures {
    const val VARIABLE = "RobotoMono-wght.ttf"
    const val STATIC = "SourceCodePro-Regular.otf"
    const val ROOM = LyricsFontLimits.FREE_RESERVE_BYTES + 64L * 1024 * 1024

    // Robolectric reserves fonts/ for system configuration; fixtures must not shadow it.
    fun bytes(name: String = VARIABLE): ByteArray = checkNotNull(javaClass.getResourceAsStream("/fixtures/lyrics-fonts/$name")).use { it.readBytes() }
    fun file(directory: File, name: String = VARIABLE): File = File(directory, name).apply { writeBytes(bytes(name)) }
    fun request(name: String = VARIABLE): LyricsFontImportRequest = LyricsFontImportRequest(
        "content://font-fixture/selected", name, LyricsFontLimits.LICENSE_ACKNOWLEDGMENT_VERSION,
    )

    fun asset(bytes: ByteArray = bytes(), name: String = VARIABLE): LyricsFontAsset = LyricsFontAsset(
        UUID.randomUUID().toString(), sha256(bytes), bytes.size.toLong(), name, LyricsFontLimits.LICENSE_ACKNOWLEDGMENT_VERSION,
    )

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    fun tableEntry(bytes: ByteArray, tag: String): Int {
        val count = u16(bytes, 4)
        for (index in 0 until count) {
            val offset = 12 + index * 16
            if (String(bytes, offset, 4, Charsets.US_ASCII) == tag) return offset
        }
        throw AssertionError("Missing fixture table $tag")
    }

    fun tableOffset(bytes: ByteArray, tag: String): Int = i32(bytes, tableEntry(bytes, tag) + 8)
    fun u16(bytes: ByteArray, offset: Int): Int = ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
    fun i32(bytes: ByteArray, offset: Int): Int = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int
    fun put16(bytes: ByteArray, offset: Int, value: Int) { ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.BIG_ENDIAN).putShort(value.toShort()) }
    fun put32(bytes: ByteArray, offset: Int, value: Int) { ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).putInt(value) }

    fun expect(issue: LyricsFontIssue, action: () -> Unit) {
        try { action(); throw AssertionError("Expected $issue") }
        catch (error: LyricsFontException) { org.junit.Assert.assertEquals(issue, error.issue) }
    }
}

/** File/transaction tests isolate native work; only the native suites prove Android glyph decoding. */
internal class FilesOnlyFontDecoder : LyricsFontDecoder {
    val validations = AtomicInteger()
    var failure: (() -> Throwable)? = null

    override fun validate(file: File, info: SfntFontInfo, checkCancellation: () -> Unit): FontWeightCapability {
        validations.incrementAndGet()
        checkCancellation()
        failure?.let { throw it() }
        return if (info.weightAxis == null) FontWeightCapability(FontWeightSupport.NO_AXIS, null)
        else FontWeightCapability(FontWeightSupport.AVAILABLE, info.weightAxis, FontWeightObservation(1, 1, 2, 1))
    }

    override fun decode(file: File, info: SfntFontInfo, capability: FontWeightCapability, requested: Float?): DecodedLyricsFont {
        failure?.let { throw it() }
        val configured = if (capability.support == FontWeightSupport.AVAILABLE) info.weightAxis?.clamp(requested) else null
        return DecodedLyricsFont(Typeface.DEFAULT, configured?.toInt() ?: 400, configured)
    }
}

internal class BytesFontStream(private val bytes: ByteArray, private val declared: Long? = bytes.size.toLong()) : LyricsFontStreamOpener {
    val opens = AtomicInteger()
    val closes = AtomicInteger()
    var denied = false
    override fun open(uri: String, signal: CancellationSignal): OpenedLyricsFontStream {
        opens.incrementAndGet()
        signal.throwIfCanceled()
        if (denied) throw SecurityException("Controlled selected-document denial")
        return OpenedLyricsFontStream(ByteArrayInputStream(bytes), declared) { closes.incrementAndGet() }
    }
}
