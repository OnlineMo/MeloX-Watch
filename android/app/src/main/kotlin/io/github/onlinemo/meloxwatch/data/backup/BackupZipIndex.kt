package io.github.onlinemo.meloxwatch.data.backup

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

/**
 * Inspect both ZIP directories before ZipFile allocates its index. Only ordinary, bounded,
 * contiguous entries are accepted; a central name cannot hide a second local path or a link.
 * Budgets include each entry's local header, payload, descriptor and central record.
 */
internal object BackupZipIndex {
    fun verify(file: File, checkCancelled: () -> Unit): Int {
        val identity = BackupFileIdentity.capture(file)
        var verifiedCount = 0
        FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val length = channel.size()
            if (length !in 22L..(BackupLimits.LOGICAL_ARCHIVE_BYTES + BackupLimits.RESOURCE_ARCHIVE_BYTES)) {
                throw BackupException(BackupIssue.TOO_LARGE)
            }
            val end = read(channel, length - 22L, 22, checkCancelled)
            if (end.int != END || end.shortValue() != 0 || end.shortValue() != 0) corrupt()
            val localCount = end.shortValue()
            val count = end.shortValue()
            verifiedCount = count
            val directoryBytes = end.unsignedInt()
            val directoryOffset = end.unsignedInt()
            if (end.shortValue() != 0 || count != localCount || count !in 2..BackupLimits.ZIP_ENTRIES ||
                directoryOffset + directoryBytes != length - 22L) corrupt()
            val budget = BackupZipBudget()
            var central = directoryOffset
            var localEnd = 0L
            repeat(count) {
                checkCancelled()
                val header = read(channel, central, 46, checkCancelled)
                if (header.int != CENTRAL) corrupt()
                header.shortValue() // creator version supplies no authorization or identity
                val needed = header.shortValue()
                val flags = header.shortValue()
                val method = header.shortValue()
                val time = header.shortValue()
                val date = header.shortValue()
                val crc = header.unsignedInt()
                val packed = header.unsignedInt()
                val expanded = header.unsignedInt()
                val nameBytes = header.shortValue()
                val extraBytes = header.shortValue()
                val commentBytes = header.shortValue()
                val disk = header.shortValue()
                header.shortValue()
                val attributes = header.unsignedInt()
                val localOffset = header.unsignedInt()
                val unixType = (attributes ushr 16).toInt() and 0xf000
                if (needed !in 10..20 || flags !in setOf(0, 8, 0x800, 0x808) || method !in setOf(0, 8) ||
                    nameBytes !in 1..128 || extraBytes != 0 || commentBytes != 0 || disk != 0 ||
                    attributes and 0x10L != 0L || unixType !in setOf(0, 0x8000) ||
                    localOffset != localEnd || packed == 0xffffffffL || expanded == 0xffffffffL ||
                    method == 0 && packed != expanded) corrupt()
                val rawName = read(channel, central + 46L, nameBytes, checkCancelled).array()
                if (rawName.any { (it.toInt() and 255) !in 0x20..0x7e }) corrupt()
                val name = rawName.toString(Charsets.US_ASCII)
                if (!backupCanonicalEntryName(name)) throw BackupException(BackupIssue.PATH_ESCAPE)
                val local = read(channel, localOffset, 30, checkCancelled)
                if (local.int != LOCAL || local.shortValue() != needed || local.shortValue() != flags ||
                    local.shortValue() != method || local.shortValue() != time || local.shortValue() != date) corrupt()
                val localCrc = local.unsignedInt()
                val localPacked = local.unsignedInt()
                val localExpanded = local.unsignedInt()
                if (local.shortValue() != nameBytes || local.shortValue() != 0 ||
                    !read(channel, localOffset + 30L, nameBytes, checkCancelled).array().contentEquals(rawName)) corrupt()
                val descriptor = flags and 8 != 0
                if ((!descriptor && (localCrc != crc || localPacked != packed || localExpanded != expanded)) ||
                    (descriptor && (localCrc !in setOf(0L, crc) || localPacked !in setOf(0L, packed) ||
                        localExpanded !in setOf(0L, expanded)))) corrupt()
                val dataEnd = localOffset + 30L + nameBytes + packed
                localEnd = dataEnd + if (descriptor) 16L else 0L
                if (localEnd > directoryOffset) corrupt()
                if (descriptor) {
                    val trailer = read(channel, dataEnd, 16, checkCancelled)
                    if (trailer.int != DESCRIPTOR || trailer.unsignedInt() != crc ||
                        trailer.unsignedInt() != packed || trailer.unsignedInt() != expanded) corrupt()
                }
                budget.add(name, localEnd - localOffset, 46L + nameBytes)
                central += 46L + nameBytes
            }
            budget.finish()
            if (central != directoryOffset + directoryBytes || localEnd != directoryOffset ||
                budget.bytes != length) corrupt()
        }
        identity.requireSame(file)
        return verifiedCount
    }

    private fun read(channel: FileChannel, offset: Long, bytes: Int, checkCancelled: () -> Unit): ByteBuffer {
        if (offset < 0L || offset > channel.size() - bytes) corrupt()
        val target = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var position = offset
        while (target.hasRemaining()) {
            checkCancelled()
            val count = channel.read(target, position)
            if (count <= 0) corrupt()
            position += count
        }
        target.flip()
        return target
    }

    private fun ByteBuffer.shortValue(): Int = short.toInt() and 0xffff
    private fun ByteBuffer.unsignedInt(): Long = int.toLong() and 0xffffffffL
    private fun corrupt(): Nothing = throw BackupException(BackupIssue.CORRUPT_ARCHIVE)
    private const val END = 0x06054b50
    private const val CENTRAL = 0x02014b50
    private const val LOCAL = 0x04034b50
    private const val DESCRIPTOR = 0x08074b50
}

internal class BackupZipBudget {
    private var logical = 0L
    private var resources = 0L
    private var entries = 0
    val bytes: Long get() = logical + resources

    fun add(name: String, localBytes: Long, centralBytes: Long) {
        if (++entries > BackupLimits.ZIP_ENTRIES) throw BackupException(BackupIssue.TOO_LARGE)
        val bytes = backupAdd(localBytes, centralBytes, Long.MAX_VALUE)
        if (name.startsWith("resources/")) resources = backupAdd(resources, bytes, BackupLimits.RESOURCE_ARCHIVE_BYTES)
        else logical = backupAdd(logical, bytes, BackupLimits.LOGICAL_ARCHIVE_BYTES)
    }

    fun finish() { logical = backupAdd(logical, 22L, BackupLimits.LOGICAL_ARCHIVE_BYTES) }
}

internal fun backupCanonicalEntryName(name: String): Boolean =
    name == BACKUP_HEADER_NAME || name == BACKUP_COMPLETE_NAME ||
        BACKUP_ROW_NAME.matches(name) || BACKUP_RESOURCE_NAME.matches(name)

internal const val BACKUP_HEADER_NAME = "manifest.json"
internal const val BACKUP_COMPLETE_NAME = "complete.json"
internal val BACKUP_ROW_NAME = Regex("rows/([a-z_]+)/([0-9]{12})\\.json")
internal val BACKUP_RESOURCE_NAME = Regex("resources/([0-9a-f]{64})\\.bin")
