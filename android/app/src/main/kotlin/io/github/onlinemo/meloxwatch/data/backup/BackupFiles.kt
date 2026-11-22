package io.github.onlinemo.meloxwatch.data.backup

import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import io.github.onlinemo.meloxwatch.data.themes.ThemeLimits

/** File identities are private cleanup proofs, never archive fields, progress or diagnostic output. */
internal class BackupFileIdentity private constructor(private val key: Any, private val directory: Boolean) {
    fun requireSame(file: File) {
        val actual = capture(file, directory)
        if (actual.key != key) throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
    }

    override fun toString(): String = "BackupFileIdentity"

    companion object {
        fun capture(file: File, directory: Boolean = false): BackupFileIdentity {
            val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (attributes.isSymbolicLink || (if (directory) !attributes.isDirectory else !attributes.isRegularFile)) {
                throw BackupException(BackupIssue.PATH_ESCAPE)
            }
            val key = attributes.fileKey() ?: throw BackupException(BackupIssue.IO)
            return BackupFileIdentity(key, directory)
        }
    }
}

/** A private generation owns newly created files. Deleting a path requires its original identity. */
internal class BackupCreatedFile private constructor(
    val file: File,
    val channel: FileChannel,
    private val identity: BackupFileIdentity,
    private val parentIdentity: BackupFileIdentity,
) : AutoCloseable {
    fun requireSame() {
        parentIdentity.requireSame(file.parentFile)
        identity.requireSame(file)
    }

    fun deleteCreated() {
        requireSame()
        Files.delete(file.toPath())
        syncBackupDirectory(file.parentFile)
    }

    override fun close() = channel.close()

    companion object {
        fun create(file: File): BackupCreatedFile {
            val parent = BackupFileIdentity.capture(file.parentFile, directory = true)
            if (file.parentFile.absoluteFile != file.parentFile.canonicalFile) throw BackupException(BackupIssue.PATH_ESCAPE)
            val channel = FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)
            try {
                val identity = BackupFileIdentity.capture(file)
                parent.requireSame(file.parentFile)
                return BackupCreatedFile(file, channel, identity, parent)
            } catch (failure: Throwable) {
                channel.close()
                // No cleanup without the captured identity; an unexpected path is never guessed to be ours.
                throw failure
            }
        }
    }
}

internal class BackupPrivateDirectory private constructor(val root: File, private val identity: BackupFileIdentity) {
    fun ownership(): BackupGenerationOwner {
        identity.requireSame(root)
        val facts = Os.lstat(root.absolutePath)
        if (!OsConstants.S_ISDIR(facts.st_mode)) throw BackupException(BackupIssue.PATH_ESCAPE)
        return BackupGenerationOwner(root.name, facts.st_dev, facts.st_ino)
    }

    /** Recovery reuses the recorded directory identity; a replacement UUID directory is not ours. */
    fun openOwnedGeneration(owner: BackupGenerationOwner): BackupPrivateDirectory? {
        val file = child(owner.id)
        val facts = try { Os.lstat(file.absolutePath) }
        catch (failure: ErrnoException) { if (failure.errno == OsConstants.ENOENT) return null else throw failure }
        if (!OsConstants.S_ISDIR(facts.st_mode) || facts.st_dev != owner.device || facts.st_ino != owner.inode) {
            throw BackupException(BackupIssue.INTEGRITY_MISMATCH)
        }
        return open(file)
    }
    fun child(name: String): File {
        if (!name.matches(Regex("[a-z0-9][a-z0-9._-]{0,79}")) || name.endsWith('.') || name == "." || name == "..") {
            throw BackupException(BackupIssue.PATH_ESCAPE)
        }
        identity.requireSame(root)
        val child = File(root, name)
        if (child.canonicalFile.parentFile != root || Files.isSymbolicLink(child.toPath())) {
            throw BackupException(BackupIssue.PATH_ESCAPE)
        }
        return child
    }

    fun newGeneration(onCreated: (BackupPrivateDirectory) -> Unit = {}): BackupPrivateDirectory {
        val file = child(UUID.randomUUID().toString())
        Files.createDirectory(file.toPath())
        val created = open(file)
        // The caller owns this measured directory before a later fsync acknowledgement can fail.
        onCreated(created)
        syncBackupDirectory(root)
        return created
    }

    /** The caller checks this UUID generation against its journal before retiring it. */
    fun retireGeneration(generation: BackupPrivateDirectory) {
        identity.requireSame(root)
        generation.identity.requireSame(generation.root)
        val target = generation.root.toPath().toAbsolutePath().normalize()
        val allowed = root.toPath().toAbsolutePath().normalize()
        if (target.parent != allowed || !target.fileName.toString().matches(UUID_NAME)) {
            throw BackupException(BackupIssue.PATH_ESCAPE)
        }
        var nodes = 0
        fun remove(path: Path, depth: Int) {
            if (++nodes > BackupLimits.ZIP_ENTRIES + ThemeLimits.OWNED_NODES + 128 ||
                depth > ThemeLimits.DIRECTORY_DEPTH + 4 || !path.normalize().startsWith(target)) {
                throw BackupException(BackupIssue.PATH_ESCAPE)
            }
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (attributes.isSymbolicLink) throw BackupException(BackupIssue.PATH_ESCAPE)
            if (attributes.isDirectory) Files.newDirectoryStream(path).use { children ->
                for (child in children) remove(child, depth + 1)
            } else if (!attributes.isRegularFile) throw BackupException(BackupIssue.PATH_ESCAPE)
            Files.delete(path)
        }
        remove(target, 0)
        syncBackupDirectory(root)
    }

    companion object {
        fun open(root: File): BackupPrivateDirectory {
            val file = root.absoluteFile
            if (file != file.canonicalFile) throw BackupException(BackupIssue.PATH_ESCAPE)
            return BackupPrivateDirectory(file, BackupFileIdentity.capture(file, directory = true))
        }

        fun createBelow(trustedPrivateRoot: File, name: String): BackupPrivateDirectory {
            val parent = open(trustedPrivateRoot.canonicalFile)
            val child = parent.child(name)
            if (!Files.exists(child.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(child.toPath())
                syncBackupDirectory(parent.root)
            }
            return open(child)
        }

        private val UUID_NAME = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

/** Filesystem facts stay in the private journal and never in a portable archive or receipt. */
internal class BackupGenerationOwner(val id: String, val device: Long, val inode: Long) {
    init {
        require(UUID.fromString(id).toString() == id && device >= 0L && inode >= 0L)
    }
    override fun toString(): String = "BackupGenerationOwner"
}

internal fun syncBackupDirectory(directory: File) {
    val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
    try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
}

/** New private output stops before consuming the same reserve used by other local imports. */
internal fun requireBackupSpace(directory: File, incomingBytes: Long) {
    if (incomingBytes < 0L) throw BackupException(BackupIssue.INVALID_INPUT)
    val available = directory.usableSpace
    if (available <= 0L) throw BackupException(BackupIssue.IO)
    if (available < BackupLimits.FREE_RESERVE_BYTES || incomingBytes > available - BackupLimits.FREE_RESERVE_BYTES) {
        throw BackupException(BackupIssue.STORAGE_FULL)
    }
}
