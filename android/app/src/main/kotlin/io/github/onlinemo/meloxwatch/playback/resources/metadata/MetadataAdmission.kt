package io.github.onlinemo.meloxwatch.playback.resources.metadata

import java.io.IOException
import java.io.InterruptedIOException

internal enum class MetadataAdmissionIssue { MALFORMED, RESOURCE_LIMIT, UNSUPPORTED_SURFACE }

internal class MetadataAdmissionException(val issue: MetadataAdmissionIssue) : IOException("Metadata admission: ${issue.name}")

internal object MetadataLimits {
    // 字节与结构数量是源码准入上限；不代表堆/PSS、功耗或设备性能已通过验收。
    const val RAW_BLOCK = 1024 * 1024
    const val RETAINED_BYTES = 4 * 1024 * 1024
    const val PEEK_WINDOW = 2 * 1024 * 1024
    const val TEXT_BYTES = 8192
    const val ENTRIES = 256
    const val DEPTH = 8
    const val BLOCKS = 128
    const val ATOMS = 8192
    const val TRACKS = 8
    const val SAMPLES = 262_144
    const val SEEK_POINTS = 16_384
    const val CODEC_CONFIG = 256 * 1024
    const val AUDIO_FRAME = 1024 * 1024
    const val CODEBOOK_ENTRIES = 65_536
    const val TOTAL_CODEBOOK_ENTRIES = 262_144
}

internal fun admit(condition: Boolean, issue: MetadataAdmissionIssue = MetadataAdmissionIssue.MALFORMED) {
    if (!condition) throw MetadataAdmissionException(issue)
}

internal fun boundedAdd(left: Long, right: Long): Long = try { Math.addExact(left, right) }
catch (_: ArithmeticException) { throw MetadataAdmissionException(MetadataAdmissionIssue.MALFORMED) }

internal class MetadataWork {
    private val deadline = System.nanoTime() + 2_000_000_000L
    private var operations = 0
    fun check() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Metadata read cancelled")
        if (++operations and 255 == 0) admit(System.nanoTime() - deadline < 0, MetadataAdmissionIssue.RESOURCE_LIMIT)
    }
}

internal class MetadataBudget {
    private var retained = 0L
    private var entries = 0
    fun retain(bytes: Int) {
        admit(bytes >= 0)
        retained = boundedAdd(retained, bytes.toLong())
        admit(retained <= MetadataLimits.RETAINED_BYTES, MetadataAdmissionIssue.RESOURCE_LIMIT)
    }
    fun entry(count: Int = 1) {
        admit(count >= 0 && count <= MetadataLimits.ENTRIES - entries, MetadataAdmissionIssue.RESOURCE_LIMIT)
        entries += count
    }
}

/** 仅访问当前 payload 的实际范围，不能借 ParsableByteArray 未使用的容量补齐坏字段。 */
internal class MetadataCursor(
    val bytes: ByteArray,
    var position: Int,
    val end: Int,
    val work: MetadataWork = MetadataWork(),
) {
    init { admit(position >= 0 && position <= end && end <= bytes.size) }
    val left: Int get() = end - position
    fun need(count: Int) {
        work.check()
        admit(count >= 0 && count <= left)
    }
    fun skip(count: Int) {
        need(count)
        position += count
    }
    fun u8(): Int {
        need(1)
        return bytes[position++].toInt() and 255
    }
    fun u16(little: Boolean = false): Int {
        val first = u8()
        val second = u8()
        return if (little) first or (second shl 8) else (first shl 8) or second
    }
    fun u24(little: Boolean = false): Int {
        val first = u8()
        val second = u8()
        val third = u8()
        return if (little) first or (second shl 8) or (third shl 16) else (first shl 16) or (second shl 8) or third
    }
    fun u32(little: Boolean = false): Long {
        var result = 0L
        if (little) repeat(4) { result = result or (u8().toLong() shl (it * 8)) }
        else repeat(4) { result = (result shl 8) or u8().toLong() }
        return result
    }
    fun count(maximum: Int, little: Boolean = false): Int {
        val value = u32(little)
        admit(value <= maximum.toLong(), MetadataAdmissionIssue.RESOURCE_LIMIT)
        return value.toInt()
    }
    fun syncSafe(): Int {
        var result = 0
        repeat(4) {
            val value = u8()
            admit(value and 128 == 0)
            result = (result shl 7) or value
        }
        return result
    }
    fun textBytes(count: Int, maximum: Int = MetadataLimits.TEXT_BYTES) {
        admit(count <= maximum, MetadataAdmissionIssue.RESOURCE_LIMIT)
        skip(count)
    }
    fun zeroTerminated(maximum: Int = MetadataLimits.TEXT_BYTES) {
        var count = 0
        while (true) {
            val value = u8()
            if (value == 0) return
            admit(++count <= maximum, MetadataAdmissionIssue.RESOURCE_LIMIT)
        }
    }
    fun fourCc(): String {
        need(4)
        val value = String(bytes, position, 4, Charsets.US_ASCII)
        position += 4
        return value
    }
}

internal fun ByteArray.matches(at: Int, value: String): Boolean = at >= 0 && at <= size - value.length &&
    value.indices.all { this[at + it].toInt() and 255 == value[it].code }

internal interface ContainerAdmission {
    fun returned(position: Long, bytes: ByteArray, offset: Int, count: Int, peek: Boolean, length: Long) = Unit
    fun skipped(position: Long, count: Int) = Unit
    fun seek(position: Long) = Unit
    fun release() = Unit
}
