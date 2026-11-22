package io.github.onlinemo.meloxwatch.data.backup

import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

/** A native owner writes only this newly created private file while retaining its real IO lease. */
internal class BackupResourceWriter private constructor(
    private val created: BackupCreatedFile,
    private val maximumBytes: Long,
    private val checkCancelled: () -> Unit,
) : AutoCloseable {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var written = 0L
    private var sealed = false
    private var closed = false

    // A ZIP wrapper may close this borrowed stream; only this owner closes the physical channel.
    val output: OutputStream = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            checkCancelled()
            if (closed || sealed) throw BackupException(BackupIssue.IO)
            if (length.toLong() > maximumBytes - written) throw BackupException(BackupIssue.TOO_LARGE)
            requireBackupSpace(created.directory, length.toLong())
            val buffer = ByteBuffer.wrap(bytes, offset, length)
            while (buffer.hasRemaining()) {
                checkCancelled()
                if (created.channel.write(buffer) <= 0) throw BackupException(BackupIssue.IO)
            }
            digest.update(bytes, offset, length)
            written += length
        }
        override fun close() = Unit
    }

    fun copyVerified(file: File, bytes: Long, sha256: String, checkSource: () -> Unit) {
        if (bytes !in 1..maximumBytes || !sha256.matches(Regex("[0-9a-f]{64}"))) {
            throw BackupException(BackupIssue.INVALID_RESOURCE)
        }
        val identity = BackupFileIdentity.capture(file)
        val sourceDigest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            if (channel.size() != bytes) throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
            val buffer = ByteBuffer.allocate(64 * 1024)
            while (true) {
                checkCancelled(); checkSource()
                buffer.clear()
                val count = channel.read(buffer)
                if (count < 0) break
                if (count == 0 || count.toLong() > bytes - copied) throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
                sourceDigest.update(buffer.array(), 0, count)
                output.write(buffer.array(), 0, count)
                copied += count
            }
            if (channel.size() != bytes) throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
        }
        identity.requireSame(file)
        if (copied != bytes || backupHex(sourceDigest.digest()) != sha256) throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
    }

    fun seal(kind: BackupResourceKind, fontLicenseRequired: Boolean): BackupCapturedResource {
        checkCancelled()
        if (closed || sealed || written !in 1..kind.limitBytes) throw BackupException(BackupIssue.INVALID_RESOURCE)
        created.channel.force(true)
        created.requireSame()
        created.close()
        closed = true
        syncBackupDirectory(created.directory)
        val sha256 = backupHex(digest.digest())
        val descriptor = BackupResource(BackupLogicalSchema.resourceId(kind, sha256), kind, written, sha256, fontLicenseRequired)
        sealed = true
        return BackupCapturedResource(descriptor, created)
    }

    fun <T> verifyWrittenFile(action: (File) -> T): T {
        checkCancelled()
        if (closed || sealed || written == 0L) throw BackupException(BackupIssue.IO)
        created.channel.force(true)
        created.requireSame()
        return action(created.file).also { created.requireSame(); checkCancelled() }
    }

    override fun close() {
        if (sealed) return
        var failure: Throwable? = null
        try { if (!closed) created.close() }
        catch (error: Throwable) { failure = error }
        closed = true
        try { created.deleteCreated() }
        catch (error: Throwable) { if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error) }
        failure?.let { throw it }
    }

    companion object {
        fun create(directory: BackupPrivateDirectory, kind: BackupResourceKind, checkCancelled: () -> Unit): BackupResourceWriter =
            BackupResourceWriter(BackupCreatedFile.create(directory.child(UUID.randomUUID().toString() + ".resource")), kind.limitBytes, checkCancelled)
    }
}

internal class BackupCapturedResource(val descriptor: BackupResource, private val created: BackupCreatedFile) {
    fun install(directory: BackupPrivateDirectory) {
        created.requireSame()
        if (created.file.parentFile != directory.root) throw BackupException(BackupIssue.PATH_ESCAPE)
        val destination = directory.child(descriptor.id + ".bin")
        if (Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) throw BackupException(BackupIssue.DUPLICATE_RECORD)
        val identity = BackupFileIdentity.capture(created.file)
        Files.move(created.file.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        identity.requireSame(destination)
        syncBackupDirectory(directory.root)
    }
    fun discardDuplicate() = created.deleteCreated()
    override fun toString(): String = "BackupCapturedResource"
}
