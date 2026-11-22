package io.github.onlinemo.meloxwatch.data.backup

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The captured source owns complete disk-backed rows and supplies bounded keyset pages. */
internal interface BackupSnapshot : AutoCloseable {
    val header: BackupHeader
    fun page(kind: BackupRecordKind, afterKey: String?): List<BackupRecord>
    fun resource(id: String): BackupResource
    fun openResource(id: String): InputStream
}

/** Callbacks target an independent staging generation, never active Room rows or preferences. */
internal interface BackupArchiveStaging {
    fun begin(header: BackupHeader)
    fun record(record: BackupRecord)
    fun resource(id: String): BackupResource
    fun writeResource(id: String): OutputStream
    fun complete(header: BackupHeader, completion: BackupCompletion)
    fun abort()
}

internal class BackupArchiveWriter {
    suspend fun write(snapshot: BackupSnapshot, destination: File): BackupExportReceipt {
        val context = currentCoroutineContext()
        return write(snapshot, destination) { context.ensureActive() }
    }

    /** A sealed disk-backed snapshot is consumed on the existing physical IO channel. */
    fun write(snapshot: BackupSnapshot, destination: File, checkCancelled: () -> Unit): BackupExportReceipt {
        val header = snapshot.header
        val counts = BackupRecordKind.entries.associateWith { 0L }.toMutableMap()
        val chunks = counts.toMutableMap()
        val chain = BackupEntryChain()
        val packed = BackupZipBudget()
        var logicalBytes = 0L
        var resourceBytes = 0L
        var resourceCount = 0L
        val created = BackupCreatedFile.create(destination)
        try {
            created.use {
                val output = BackupCountingOutput(Channels.newOutputStream(created.channel), created.directory)
                ZipOutputStream(output).use { zip ->
                    fun entry(name: String, write: () -> Unit) {
                        checkCancelled()
                        val value = ZipEntry(name).apply { time = 1577836800000L }
                        val start = output.bytes
                        zip.putNextEntry(value)
                        write()
                        zip.closeEntry()
                        if (value.extra?.isNotEmpty() == true || !value.comment.isNullOrEmpty()) {
                            throw BackupException(BackupIssue.INVALID_INPUT)
                        }
                        packed.add(name, output.bytes - start, 46L + name.toByteArray(Charsets.US_ASCII).size)
                    }
                    fun logicalEntry(name: String, bytes: ByteArray) {
                        logicalBytes = backupAdd(logicalBytes, bytes.size.toLong(), BackupLimits.EXPANDED_LOGICAL_BYTES)
                        entry(name) { zip.write(bytes) }
                        chain.add(name, bytes.size.toLong(), backupSha256(bytes))
                    }
                    logicalEntry(BACKUP_HEADER_NAME, BackupJsonCodec.header(header, checkCancelled))
                    for (kind in BackupRecordKind.entries) {
                        if (kind.scope !in header.scopes) continue
                        var after: String? = null
                        val chunk = ByteArrayOutputStream(8192)
                        var chunkRows = 0
                        chunk.write('['.code)
                        fun flushChunk() {
                            if (chunkRows == 0) return
                            chunk.write(']'.code)
                            val index = checkNotNull(chunks[kind])
                            logicalEntry(backupRowName(kind, index), chunk.toByteArray())
                            chunks[kind] = index + 1L
                            chunk.reset()
                            chunk.write('['.code)
                            chunkRows = 0
                        }
                        while (true) {
                            checkCancelled()
                            val page = snapshot.page(kind, after)
                            if (page.isEmpty()) break
                            if (page.size > BackupLimits.ROWS_PER_CHUNK) throw BackupException(BackupIssue.INVALID_INPUT)
                            for (record in page) {
                                if (record.kind != kind || after?.let { compareBackupKeys(it, record.key) >= 0 } == true) {
                                    throw BackupException(BackupIssue.DUPLICATE_RECORD)
                                }
                                val encoded = BackupJsonCodec.record(record, checkCancelled)
                                val separator = if (chunkRows == 0) 0 else 1
                                if (chunkRows == BackupLimits.ROWS_PER_CHUNK ||
                                    encoded.size > BackupLimits.CHUNK_BYTES - chunk.size() - separator - 1) flushChunk()
                                if (chunkRows != 0) chunk.write(','.code)
                                chunk.write(encoded)
                                chunkRows++
                                counts[kind] = checkNotNull(counts[kind]) + 1L
                                after = record.key
                            }
                        }
                        flushChunk()
                    }
                    if (BackupScope.RESOURCES in header.scopes) {
                        var after: String? = null
                        while (true) {
                            checkCancelled()
                            val page = snapshot.page(BackupRecordKind.RESOURCE, after)
                            if (page.isEmpty()) break
                            if (page.size > BackupLimits.ROWS_PER_CHUNK) throw BackupException(BackupIssue.INVALID_INPUT)
                            for (record in page) {
                                if (record.kind != BackupRecordKind.RESOURCE ||
                                    after?.let { compareBackupKeys(it, record.key) >= 0 } == true) {
                                    throw BackupException(BackupIssue.DUPLICATE_RECORD)
                                }
                                val descriptor = BackupLogicalSchema.resourceDescriptor(record)
                                val resource = snapshot.resource(record.key)
                                if (!sameBackupResource(descriptor, resource)) throw BackupException(BackupIssue.INVALID_REFERENCE)
                                val hash = MessageDigest.getInstance("SHA-256")
                                var actualBytes = 0L
                                val name = backupResourceName(resource.id)
                                entry(name) {
                                    snapshot.openResource(resource.id).use { input ->
                                        val buffer = ByteArray(8192)
                                        while (true) {
                                            checkCancelled()
                                            val read = input.read(buffer)
                                            if (read < 0) break
                                            if (read == 0) throw BackupException(BackupIssue.IO)
                                            actualBytes = backupAdd(actualBytes, read.toLong(), resource.bytes)
                                            resourceBytes = backupAdd(resourceBytes, read.toLong(), BackupLimits.RESOURCE_ARCHIVE_BYTES)
                                            hash.update(buffer, 0, read)
                                            zip.write(buffer, 0, read)
                                        }
                                    }
                                }
                                val sha256 = backupHex(hash.digest())
                                if (actualBytes != resource.bytes || sha256 != resource.sha256) {
                                    throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
                                }
                                chain.add(name, actualBytes, sha256)
                                resourceCount++
                                after = record.key
                            }
                        }
                    }
                    if (resourceCount != counts[BackupRecordKind.RESOURCE]) throw BackupException(BackupIssue.INVALID_REFERENCE)
                    val completion = BackupCompletion(header.exportId, counts, chunks, resourceCount, logicalBytes,
                        resourceBytes, chain.finish())
                    val completedBytes = BackupJsonCodec.completion(completion, checkCancelled)
                    backupAdd(logicalBytes, completedBytes.size.toLong(), BackupLimits.EXPANDED_LOGICAL_BYTES)
                    entry(BACKUP_COMPLETE_NAME) { zip.write(completedBytes) }
                    packed.finish()
                    zip.finish()
                    zip.flush()
                    if (output.bytes != packed.bytes) throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
                    checkCancelled()
                    created.channel.force(true)
                }
            }
            created.requireSame()
            syncBackupDirectory(created.directory)
            // Re-read the actual central directory, every row and its matching resource descriptor.
            BackupArchiveReader().verify(destination, checkCancelled)
            return BackupExportReceipt(header.exportId, backupFileSha256(destination, checkCancelled), destination.length())
        } catch (failure: Throwable) {
            try { created.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            try { created.deleteCreated() } catch (cleanup: Throwable) {
                failure.addSuppressed(BackupException(BackupIssue.OUTPUT_CLEANUP_REQUIRED, cleanup))
            }
            throw failure
        }
    }
}

internal class BackupArchiveReader {
    fun verify(file: File, checkCancelled: () -> Unit): BackupHeader = read(file, null, checkCancelled)
    fun stage(file: File, staging: BackupArchiveStaging, checkCancelled: () -> Unit): BackupHeader = read(file, staging, checkCancelled)

    private fun read(file: File, staging: BackupArchiveStaging?, checkCancelled: () -> Unit): BackupHeader {
        checkCancelled()
        val identity = BackupFileIdentity.capture(file)
        val expectedEntries = BackupZipIndex.verify(file, checkCancelled)
        var begun = false
        try {
            ZipFile(file).use { zip ->
                if (!zip.comment.isNullOrEmpty()) throw BackupException(BackupIssue.CORRUPT_ARCHIVE)
                val entries = zip.entries()
                if (!entries.hasMoreElements()) throw BackupException(BackupIssue.INCOMPLETE_ARCHIVE)
                val headerEntry = entries.nextElement()
                if (headerEntry.name != BACKUP_HEADER_NAME) throw BackupException(BackupIssue.CORRUPT_ARCHIVE)
                checkEntry(headerEntry)
                val headerBytes = smallEntry(zip, headerEntry, BackupLimits.MANIFEST_BYTES, checkCancelled)
                val header = BackupJsonCodec.readHeader(headerBytes, checkCancelled)
                val chain = BackupEntryChain()
                chain.add(BACKUP_HEADER_NAME, headerBytes.size.toLong(), backupSha256(headerBytes))
                val counts = BackupRecordKind.entries.associateWith { 0L }.toMutableMap()
                val chunks = counts.toMutableMap()
                val lastKeys = mutableMapOf<BackupRecordKind, String>()
                var logicalBytes = headerBytes.size.toLong()
                var resourceBytes = 0L
                var resourceCount = 0L
                var entryCount = 1
                var lastKind = -1
                var lastResource: String? = null
                var resourceCursor: ResourceCatalogCursor? = null
                staging?.begin(header)
                begun = true
                while (entries.hasMoreElements()) {
                    checkCancelled()
                    val entry = entries.nextElement()
                    if (++entryCount > expectedEntries) throw BackupException(BackupIssue.CORRUPT_ARCHIVE)
                    checkEntry(entry)
                    if (entry.name == BACKUP_COMPLETE_NAME) {
                        val bytes = smallEntry(zip, entry, BackupLimits.MANIFEST_BYTES, checkCancelled)
                        backupAdd(logicalBytes, bytes.size.toLong(), BackupLimits.EXPANDED_LOGICAL_BYTES)
                        val completion = BackupJsonCodec.readCompletion(bytes, checkCancelled)
                        if (entries.hasMoreElements() || entryCount != expectedEntries || completion.exportId != header.exportId ||
                            completion.records != counts || completion.chunks != chunks || completion.resources != resourceCount ||
                            completion.logicalBytes != logicalBytes || completion.resourceBytes != resourceBytes ||
                            completion.chainSha256 != chain.finish() || resourceCount != counts[BackupRecordKind.RESOURCE] ||
                            resourceCursor?.hasNext() == true) throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
                        identity.requireSame(file)
                        staging?.complete(header, completion)
                        return header
                    }
                    val row = BACKUP_ROW_NAME.matchEntire(entry.name)
                    if (row != null) {
                        if (resourceCursor != null) throw BackupException(BackupIssue.CORRUPT_ARCHIVE)
                        val kind = BackupRecordKind.entries.firstOrNull { it.name.lowercase(Locale.ROOT) == row.groupValues[1] }
                            ?: throw BackupException(BackupIssue.CORRUPT_ARCHIVE)
                        val index = row.groupValues[2].toLongOrNull() ?: throw BackupException(BackupIssue.CORRUPT_ARCHIVE)
                        if (kind.scope !in header.scopes || kind.ordinal < lastKind || index != chunks[kind]) {
                            throw BackupException(BackupIssue.CORRUPT_ARCHIVE)
                        }
                        lastKind = kind.ordinal
                        val bytes = smallEntry(zip, entry, BackupLimits.CHUNK_BYTES, checkCancelled)
                        logicalBytes = backupAdd(logicalBytes, bytes.size.toLong(), BackupLimits.EXPANDED_LOGICAL_BYTES)
                        val records = BackupJsonCodec.readRecords(bytes, checkCancelled)
                        for (record in records) {
                            if (record.kind != kind || lastKeys[kind]?.let { compareBackupKeys(it, record.key) >= 0 } == true) {
                                throw BackupException(BackupIssue.DUPLICATE_RECORD)
                            }
                            lastKeys[kind] = record.key
                            staging?.record(record)
                        }
                        counts[kind] = checkNotNull(counts[kind]) + records.size
                        chunks[kind] = checkNotNull(chunks[kind]) + 1L
                        chain.add(entry.name, bytes.size.toLong(), backupSha256(bytes))
                        continue
                    }
                    val match = BACKUP_RESOURCE_NAME.matchEntire(entry.name) ?: throw BackupException(BackupIssue.PATH_ESCAPE)
                    if (BackupScope.RESOURCES !in header.scopes) throw BackupException(BackupIssue.CORRUPT_ARCHIVE)
                    val id = match.groupValues[1]
                    if (lastResource?.let { compareBackupKeys(it, id) >= 0 } == true) throw BackupException(BackupIssue.DUPLICATE_RECORD)
                    lastResource = id
                    val catalog = resourceCursor ?: ResourceCatalogCursor(zip, checkNotNull(chunks[BackupRecordKind.RESOURCE]), checkCancelled)
                        .also { resourceCursor = it }
                    val descriptor = catalog.next()
                    if (descriptor.id != id || entry.size != descriptor.bytes ||
                        staging?.resource(id)?.let { !sameBackupResource(descriptor, it) } == true) {
                        throw BackupException(BackupIssue.INVALID_REFERENCE)
                    }
                    val hash = MessageDigest.getInstance("SHA-256")
                    val crc = CRC32()
                    var bytes = 0L
                    fun copy(output: OutputStream?) {
                        zip.getInputStream(entry).use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                checkCancelled()
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) throw BackupException(BackupIssue.IO)
                                bytes = backupAdd(bytes, read.toLong(), descriptor.bytes)
                                resourceBytes = backupAdd(resourceBytes, read.toLong(), BackupLimits.RESOURCE_ARCHIVE_BYTES)
                                hash.update(buffer, 0, read)
                                crc.update(buffer, 0, read)
                                output?.write(buffer, 0, read)
                            }
                        }
                    }
                    val output = staging?.writeResource(id)
                    if (output == null) copy(null) else output.use(::copy)
                    val actualHash = backupHex(hash.digest())
                    if (bytes != entry.size || crc.value != entry.crc || bytes != descriptor.bytes || actualHash != descriptor.sha256) {
                        throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
                    }
                    chain.add(entry.name, bytes, actualHash)
                    resourceCount++
                }
            }
            throw BackupException(BackupIssue.INCOMPLETE_ARCHIVE)
        } catch (failure: Throwable) {
            if (begun) try { staging?.abort() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private inner class ResourceCatalogCursor(
        private val zip: ZipFile,
        private val chunks: Long,
        private val checkCancelled: () -> Unit,
    ) {
        private var nextChunk = 0L
        private var rows: List<BackupRecord> = emptyList()
        private var index = 0
        fun hasNext(): Boolean = index < rows.size || nextChunk < chunks
        fun next(): BackupResource {
            if (index == rows.size) {
                if (nextChunk >= chunks) throw BackupException(BackupIssue.INVALID_REFERENCE)
                val entry = zip.getEntry(backupRowName(BackupRecordKind.RESOURCE, nextChunk++))
                    ?: throw BackupException(BackupIssue.INVALID_REFERENCE)
                checkEntry(entry)
                rows = BackupJsonCodec.readRecords(smallEntry(zip, entry, BackupLimits.CHUNK_BYTES, checkCancelled), checkCancelled)
                index = 0
            }
            val row = rows[index++]
            if (row.kind != BackupRecordKind.RESOURCE) throw BackupException(BackupIssue.INVALID_REFERENCE)
            return BackupLogicalSchema.resourceDescriptor(row)
        }
    }

    private fun checkEntry(entry: ZipEntry) {
        if (entry.isDirectory || !backupCanonicalEntryName(entry.name) || entry.method !in setOf(ZipEntry.DEFLATED, ZipEntry.STORED) ||
            entry.size < 0L || entry.compressedSize < 0L || entry.crc < 0L || entry.extra?.isNotEmpty() == true ||
            !entry.comment.isNullOrEmpty()) throw BackupException(BackupIssue.CORRUPT_ARCHIVE)
    }

    private fun smallEntry(zip: ZipFile, entry: ZipEntry, limit: Int, checkCancelled: () -> Unit): ByteArray {
        if (entry.size > limit) throw BackupException(BackupIssue.TOO_LARGE)
        val output = ByteArrayOutputStream(minOf(limit, 8192))
        val crc = CRC32()
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                checkCancelled()
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) throw BackupException(BackupIssue.IO)
                if (read > limit - output.size()) throw BackupException(BackupIssue.TOO_LARGE)
                output.write(buffer, 0, read)
                crc.update(buffer, 0, read)
            }
        }
        if (output.size().toLong() != entry.size || crc.value != entry.crc) throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
        return output.toByteArray()
    }
}

private class BackupCountingOutput(output: OutputStream, private val directory: File) : FilterOutputStream(output) {
    var bytes = 0L
        private set
    override fun write(value: Int) { account(1); out.write(value) }
    override fun write(buffer: ByteArray, offset: Int, length: Int) { account(length); out.write(buffer, offset, length) }
    private fun account(count: Int) {
        requireBackupSpace(directory, count.toLong())
        bytes = backupAdd(bytes, count.toLong(), BackupLimits.LOGICAL_ARCHIVE_BYTES + BackupLimits.RESOURCE_ARCHIVE_BYTES)
    }
}

private class BackupEntryChain {
    private val digest = MessageDigest.getInstance("SHA-256")
    fun add(name: String, bytes: Long, sha256: String) {
        val encoded = name.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(encoded.size).array())
        digest.update(encoded)
        digest.update(ByteBuffer.allocate(8).putLong(bytes).array())
        digest.update(sha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
    }
    fun finish(): String = backupHex(digest.digest())
}

internal fun sameBackupResource(left: BackupResource, right: BackupResource): Boolean =
    left.id == right.id && left.kind == right.kind && left.bytes == right.bytes && left.sha256 == right.sha256 &&
        left.requiresFontLicenseAcknowledgment == right.requiresFontLicenseAcknowledgment
internal fun backupAdd(current: Long, increment: Long, limit: Long): Long {
    if (increment < 0L || current < 0L || current > limit || increment > limit - current) throw BackupException(BackupIssue.TOO_LARGE)
    return current + increment
}
internal fun backupSha256(bytes: ByteArray): String = backupHex(MessageDigest.getInstance("SHA-256").digest(bytes))
internal fun backupHex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
internal fun backupFileSha256(file: File, checkCancelled: () -> Unit): String {
    val identity = BackupFileIdentity.capture(file)
    val digest = MessageDigest.getInstance("SHA-256")
    FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
        val length = channel.size()
        var total = 0L
        val buffer = ByteArray(8192)
        while (total < length) {
            checkCancelled()
            val read = channel.read(ByteBuffer.wrap(buffer, 0, minOf(buffer.size.toLong(), length - total).toInt()))
            if (read <= 0) throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
            digest.update(buffer, 0, read)
            total += read
        }
        if (channel.read(ByteBuffer.allocate(1)) != -1) throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
    }
    identity.requireSame(file)
    return backupHex(digest.digest())
}
internal fun compareBackupKeys(left: String, right: String): Int {
    val a = left.toByteArray(Charsets.UTF_8)
    val b = right.toByteArray(Charsets.UTF_8)
    for (index in 0 until minOf(a.size, b.size)) {
        val order = (a[index].toInt() and 255).compareTo(b[index].toInt() and 255)
        if (order != 0) return order
    }
    return a.size.compareTo(b.size)
}
internal fun backupRowName(kind: BackupRecordKind, index: Long): String =
    "rows/" + kind.name.lowercase(Locale.ROOT) + "/" + index.toString().padStart(12, '0') + ".json"
internal fun backupResourceName(id: String): String = "resources/$id.bin"
