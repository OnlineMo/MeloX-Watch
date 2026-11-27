package io.github.onlinemo.meloxwatch.data.metadata

import android.content.Context
import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkLimits
import io.github.onlinemo.meloxwatch.data.artwork.BoundedArtworkDecoder
import io.github.onlinemo.meloxwatch.data.backup.BackupException
import io.github.onlinemo.meloxwatch.data.backup.BackupIssue
import io.github.onlinemo.meloxwatch.data.backup.BackupMutationBarrier
import io.github.onlinemo.meloxwatch.data.backup.BackupResourceWriter
import io.github.onlinemo.meloxwatch.data.backup.withBackupMutation
import io.github.onlinemo.meloxwatch.data.local.AndroidLocalSources
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.themes.ThemeBitmapOwner
import io.github.onlinemo.meloxwatch.data.storage.StorageArea
import io.github.onlinemo.meloxwatch.data.storage.StorageDirectorySpec
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.DirectoryStream
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** Confirmed associations are owned assets outside the rebuildable artwork cache. */
internal class LocalMetadataArtworkFiles(
    context: Context,
    private val io: LocalProviderIo,
    private val backupBarrier: BackupMutationBarrier? = null,
    private val freeBytes: (File) -> Long = { StatFs(it.absolutePath).availableBytes },
    private val syncDirectory: (File) -> Unit = ::syncMetadataDirectory,
    private val bitmaps: ThemeBitmapOwner = ThemeBitmapOwner(),
) {
    private val application = context.applicationContext
    private val operation = Mutex()
    private val pinLock = Any()
    private val pins = HashSet<String>()
    private val backupPins = HashSet<String>()

    internal fun storageDirectorySpecOnWorker(): StorageDirectorySpec {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        return StorageDirectorySpec(application.noBackupFilesDir.canonicalFile.resolve(DIRECTORY), StorageArea.ARTWORK, 1,
            MAX_ENTRIES.toLong(), classify = { parts, directory ->
                val name = parts.singleOrNull()
                if (directory || name == null || !validMetadataAssetId(name.substringBeforeLast('.'))) null else when (name.substringAfterLast('.')) {
                    "image" -> StorageArea.ARTWORK
                    "part" -> StorageArea.RECOVERY
                    else -> null
                }
            }, mutableFile = { it.last().endsWith(".part") }, requireNonEmpty = { it.last().endsWith(".image") })
    }

    internal class Asset internal constructor(
        val assetId: String,
        val sha256: String,
        val byteCount: Long,
        private val unpin: () -> Unit,
    ) {
        private val released = AtomicBoolean(false)
        fun release() { if (released.compareAndSet(false, true)) unpin() }
        override fun toString(): String = "MetadataArtworkAsset(redacted)"
    }

    suspend fun installConfirmed(bytes: ByteArray, sha256: String): Asset =
        backupBarrier.withBackupMutation({ MetadataStoreException(MetadataStoreIssue.BUSY) }) {
            if (bytes.size !in 1..ArtworkLimits.INPUT_BYTES) fail(MetadataStoreIssue.INPUT_LIMIT)
            physical { signal -> validateImage(bytes, signal) }
            install(bytes.size.toLong(), sha256) { ByteArrayInputStream(bytes) }
        }

    suspend fun <T> withReferenceWrite(action: suspend () -> T): T =
        backupBarrier.withBackupMutation({ MetadataStoreException(MetadataStoreIssue.BUSY) }) {
            if (!operation.tryLock()) fail(MetadataStoreIssue.BUSY)
            try { action() } finally { operation.unlock() }
        }

    suspend fun readVerified(assetId: String, byteCount: Long, sha256: String): ByteArray {
        checkAsset(assetId, byteCount, sha256)
        return physical { signal ->
            readCheckedBytes(file(assetId, "image"), byteCount, sha256, signal)
        }
    }

    internal suspend fun beginBackupRetention() { requireBackupOwner(); synchronized(pinLock) { backupPins.clear() } }

    internal suspend fun retainForBackup(assetId: String) {
        requireBackupOwner()
        if (!validMetadataAssetId(assetId)) throw BackupException(BackupIssue.INVALID_RESOURCE)
        synchronized(pinLock) {
            if (assetId !in backupPins && backupPins.size >= MAX_ENTRIES) throw BackupException(BackupIssue.TOO_LARGE)
            backupPins.add(assetId)
        }
    }

    internal suspend fun releaseBackupRetention() { requireBackupOwner(); synchronized(pinLock) { backupPins.clear() } }

    internal suspend fun verifyForBackup(assetId: String, byteCount: Long, sha256: String) {
        requireBackupOwner()
        checkAsset(assetId, byteCount, sha256)
        physical { signal -> copyChecked(file(assetId, "image"), byteCount, sha256, signal) { _, _ -> } }
    }

    /** A sealed archive image is decoded under the same bitmap budget before any asset or association is installed. */
    internal suspend fun validateForBackup(file: File, byteCount: Long, sha256: String) {
        requireBackupOwner()
        checkContent(byteCount, sha256)
        physical { signal ->
            val encoded = readCheckedBytes(file, byteCount, sha256, signal)
            validateImage(encoded, signal)
        }
    }

    internal suspend fun exportForBackup(assetId: String, byteCount: Long, sha256: String, output: BackupResourceWriter) {
        requireBackupOwner()
        checkAsset(assetId, byteCount, sha256)
        physical { signal -> output.copyVerified(file(assetId, "image"), byteCount, sha256, signal::throwIfCanceled) }
    }

    internal suspend fun createForBackup(uri: String, byteCount: Long, sha256: String): Asset {
        requireBackupOwner()
        return install(byteCount, sha256) { signal ->
            application.contentResolver.openAssetFileDescriptor(AndroidLocalSources.contentUri(uri), "r", signal)
                ?.createInputStream() ?: throw BackupException(BackupIssue.INVALID_RESOURCE)
        }
    }

    /** Recovery removes only proven unreferenced assets under the same reference-write gate. */
    suspend fun recover(isReferenced: suspend (String) -> Boolean) =
        withReferenceWrite {
            val retained = AtomicReference<DirectoryStream<Path>?>()
            try {
                physical { signal ->
                    val directory = root()
                    inventory(directory, signal)
                    retained.set(Files.newDirectoryStream(directory.toPath()))
                }
                val entries = checkNotNull(retained.get())
                val iterator = physical { entries.iterator() }
                var visited = 0
                while (true) {
                    val names = physical { signal ->
                        buildList {
                            while (size < 32 && iterator.hasNext()) {
                                signal.throwIfCanceled()
                                if (++visited > MAX_ENTRIES) fail(MetadataStoreIssue.LOW_SPACE)
                                add(iterator.next().fileName.toString())
                            }
                        }
                    }
                    if (names.isEmpty()) break
                    for (name in names) {
                        currentCoroutineContext().ensureActive()
                        val assetId = name.substringBeforeLast('.')
                        val pinned = synchronized(pinLock) { assetId in pins || assetId in backupPins }
                        if (pinned || name.endsWith(".image") && isReferenced(assetId)) continue
                        physical { signal ->
                            signal.throwIfCanceled()
                            if (synchronized(pinLock) { assetId !in pins && assetId !in backupPins }) {
                                Files.deleteIfExists(file(assetId, name.substringAfterLast('.')).toPath())
                                syncDirectory(root())
                            }
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    val cursor = retained.get()
                    if (cursor != null) while (true) {
                        try {
                            physical { cursor.close() }
                            retained.compareAndSet(cursor, null)
                            break
                        } catch (failure: SourceReadException) {
                            if (!io.isAdmissionBusy(failure)) throw failure
                            io.awaitIdle()
                        }
                    }
                }
            }
        }

    private suspend fun install(byteCount: Long, sha256: String, reader: (CancellationSignal) -> InputStream): Asset {
        val assetId = UUID.randomUUID().toString()
        checkAsset(assetId, byteCount, sha256)
        if (!operation.tryLock()) fail(MetadataStoreIssue.BUSY)
        synchronized(pinLock) { pins.add(assetId) }
        var delivered = false
        try {
            val caller = currentCoroutineContext()
            val installed = physical<Asset>(abandoned = Asset::release) { signal ->
                val directory = root()
                val occupied = inventory(directory, signal)
                requireSpace(directory, occupied, byteCount)
                val staging = file(assetId, "part")
                val completed = file(assetId, "image")
                var published = false
                var originalFailure: Throwable? = null
                try {
                    val hash = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(CHUNK_BYTES)
                    var copied = 0L
                    reader(signal).use { input ->
                        FileChannel.open(staging.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS).use { output ->
                            while (true) {
                                signal.throwIfCanceled(); caller.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count == 0 || count > buffer.size || count.toLong() > byteCount - copied) fail(MetadataStoreIssue.INPUT_LIMIT)
                                requireSpace(directory, occupied, copied + count, count.toLong())
                                hash.update(buffer, 0, count)
                                val bytes = ByteBuffer.wrap(buffer, 0, count)
                                while (bytes.hasRemaining()) {
                                    signal.throwIfCanceled(); caller.ensureActive()
                                    if (output.write(bytes) <= 0) fail(MetadataStoreIssue.IO)
                                }
                                copied += count
                            }
                            if (copied != byteCount || hex(hash.digest()) != sha256) fail(MetadataStoreIssue.SOURCE_CHANGED)
                            output.force(true)
                        }
                    }
                    signal.throwIfCanceled(); caller.ensureActive()
                    if (Files.exists(completed.toPath(), LinkOption.NOFOLLOW_LINKS)) fail(MetadataStoreIssue.INVALID_RECORD)
                    Files.move(staging.toPath(), completed.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    published = true
                    syncDirectory(directory)
                    Asset(assetId, sha256, byteCount) { synchronized(pinLock) { pins.remove(assetId) } }
                } catch (failure: Throwable) {
                    originalFailure = failure
                    if (published) try { Files.deleteIfExists(completed.toPath()) }
                        catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
                    throw failure
                } finally {
                    try { Files.deleteIfExists(staging.toPath()) }
                    catch (cleanup: Throwable) {
                        val original = originalFailure
                        if (original == null) throw cleanup
                        if (cleanup !== original) original.addSuppressed(cleanup)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            delivered = true
            return installed
        } finally {
            if (!delivered) synchronized(pinLock) { pins.remove(assetId) }
            operation.unlock()
        }
    }

    private fun copyChecked(file: File, byteCount: Long, sha256: String, signal: CancellationSignal,
        consume: (ByteArray, Int) -> Unit) {
        val facts = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!facts.isRegularFile || facts.isSymbolicLink || facts.size() != byteCount) fail(MetadataStoreIssue.SOURCE_CHANGED)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(CHUNK_BYTES)
        var copied = 0L
        Files.newInputStream(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            while (true) {
                signal.throwIfCanceled()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0 || count > buffer.size || count.toLong() > byteCount - copied) fail(MetadataStoreIssue.INPUT_LIMIT)
                digest.update(buffer, 0, count); copied += count
                consume(buffer, count)
            }
        }
        if (copied != byteCount || hex(digest.digest()) != sha256) fail(MetadataStoreIssue.SOURCE_CHANGED)
        signal.throwIfCanceled()
    }

    private fun readCheckedBytes(file: File, byteCount: Long, sha256: String, signal: CancellationSignal): ByteArray {
        val output = ByteArray(byteCount.toInt())
        var offset = 0
        copyChecked(file, byteCount, sha256, signal) { bytes, count ->
            System.arraycopy(bytes, 0, output, offset, count)
            offset += count
        }
        return output
    }

    private fun validateImage(encoded: ByteArray, signal: CancellationSignal) {
        val reservation = bitmaps.reserve(2L * ArtworkLimits.BITMAP_BYTES)
        var decoded: Bitmap? = null
        try {
            decoded = BoundedArtworkDecoder.decode(encoded, 96, signal)
            signal.throwIfCanceled()
        } finally {
            decoded?.recycle()
            reservation.close()
        }
    }

    private fun root(): File {
        val directory = application.noBackupFilesDir.resolve(DIRECTORY)
        Files.createDirectories(directory.toPath())
        val facts = Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!facts.isDirectory || facts.isSymbolicLink) fail(MetadataStoreIssue.INVALID_RECORD)
        return directory
    }

    private fun file(assetId: String, suffix: String): File {
        if (!validMetadataAssetId(assetId) || suffix !in setOf("part", "image")) fail(MetadataStoreIssue.INVALID_INPUT)
        return root().resolve("$assetId.$suffix")
    }

    private fun inventory(directory: File, signal: CancellationSignal): Long {
        var occupied = 0L
        var count = 0
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            for (path in entries) {
                signal.throwIfCanceled()
                val name = path.fileName.toString()
                if (++count > MAX_ENTRIES || !validMetadataAssetId(name.substringBeforeLast('.')) ||
                    name.substringAfterLast('.') !in setOf("part", "image")) fail(MetadataStoreIssue.INVALID_RECORD)
                val facts = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                if (!facts.isRegularFile || facts.isSymbolicLink || facts.size() < 0L) fail(MetadataStoreIssue.INVALID_RECORD)
                if (facts.size() > DISK_BYTES - occupied - ENTRY_BYTES) fail(MetadataStoreIssue.LOW_SPACE)
                occupied += facts.size() + ENTRY_BYTES
            }
        }
        return occupied
    }

    private fun requireSpace(directory: File, occupied: Long, additional: Long, physicalAdditional: Long = additional) {
        if (additional !in 0..ArtworkLimits.INPUT_BYTES.toLong() || additional > DISK_BYTES - occupied - ENTRY_BYTES) fail(MetadataStoreIssue.LOW_SPACE)
        val available = freeBytes(directory)
        if (available < FREE_RESERVE_BYTES || physicalAdditional > available - FREE_RESERVE_BYTES) fail(MetadataStoreIssue.LOW_SPACE)
    }

    private suspend fun <T> physical(abandoned: (T) -> Unit = {}, action: (CancellationSignal) -> T): T {
        val retirement = io.trackReads()
        return withContext(retirement) {
            try { io.read(abandoned = abandoned, action = action) }
            finally { withContext(NonCancellable) { retirement.awaitRetired() } }
        }
    }

    private suspend fun requireBackupOwner() {
        val barrier = backupBarrier ?: throw BackupException(BackupIssue.INVALID_INPUT)
        barrier.requireExclusiveOwner()
    }

    private fun checkAsset(assetId: String, byteCount: Long, sha256: String) {
        if (!validMetadataAssetId(assetId)) fail(MetadataStoreIssue.INVALID_INPUT)
        checkContent(byteCount, sha256)
    }

    private fun checkContent(byteCount: Long, sha256: String) {
        if (byteCount !in 1..ArtworkLimits.INPUT_BYTES.toLong() || !sha256.matches(Regex("[0-9a-f]{64}"))) fail(MetadataStoreIssue.INVALID_INPUT)
    }

    private companion object {
        const val DIRECTORY = "local-metadata-artwork-v1"
        const val DISK_BYTES = ArtworkLimits.DISK_BYTES
        const val FREE_RESERVE_BYTES = 536_870_912L
        const val ENTRY_BYTES = 256L
        const val MAX_ENTRIES = 131_072
        const val CHUNK_BYTES = 16_384
        fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        fun fail(issue: MetadataStoreIssue): Nothing = throw MetadataStoreException(issue)
    }
}

private fun syncMetadataDirectory(directory: File) {
    val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
    try {
        if (!OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) throw MetadataStoreException(MetadataStoreIssue.INVALID_RECORD)
        Os.fsync(descriptor)
    } finally { Os.close(descriptor) }
}
