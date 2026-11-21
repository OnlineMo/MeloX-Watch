package io.github.onlinemo.meloxwatch.data.lyrics.active

import android.content.Context
import android.os.CancellationSignal
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import io.github.onlinemo.meloxwatch.data.local.AndroidLocalSources
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsLimits
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * 用户确认后才复制原始文本；解析、预览与文件写入分开。所有 IO 共用应用的物理通道。
 * 副本是关联资产，配额不足时拒绝，不淘汰正在使用的关联或用户原件。
 */
class LyricsDocumentCopies(
    context: Context,
    private val io: LocalProviderIo,
    private val openInput: (ObservedCandidate, CancellationSignal) -> InputStream = { candidate, signal ->
        context.applicationContext.contentResolver.openAssetFileDescriptor(
            AndroidLocalSources.contentUri(candidate.uri), "r", signal,
        )?.createInputStream() ?: throw SourceReadException(SourceReadIssue.SOURCE_UNAVAILABLE)
    },
    private val freeBytes: (File) -> Long = { StatFs(it.absolutePath).availableBytes },
    private val syncDirectory: (File) -> Unit = ::syncLyricsDirectory,
    private val openRecoveryDirectory: (File) -> DirectoryStream<Path> = { Files.newDirectoryStream(it.toPath()) },
) {
    private val context = context.applicationContext
    private val operation = Mutex()
    private val pinLock = Any()
    private val pins = HashSet<String>()

    /** release 只结束本次借用；已提交与取消边界不明的副本由数据库引用回收。 */
    class Copy internal constructor(val uri: String, val sha256: String, private val releasePin: () -> Unit) {
        private val released = AtomicBoolean(false)
        fun release() { if (released.compareAndSet(false, true)) releasePin() }
    }

    suspend fun create(candidate: ObservedCandidate, expectedSha256: String): Copy {
        require(expectedSha256.matches(Regex("[0-9a-f]{64}")))
        if (!operation.tryLock()) throw SourceReadException(SourceReadIssue.BUSY)
        val token = UUID.randomUUID().toString()
        synchronized(pinLock) { pins.add(token) }
        var delivered = false
        var cancelled = false
        try {
            val caller = currentCoroutineContext()
            val result = io.read(
                // Delivery cancellation can run on Main after the IO worker has returned.
                // Leave the bounded orphan for recover instead of deleting/fsyncing here.
                abandoned = { value: Copy -> value.release() },
                action = { signal ->
                    caller.ensureActive()
                    val root = OwnedLyricsProvider.root(context)
                    val occupiedBytes = inventoryBytes(root, signal)
                    requireSpace(root, occupiedBytes, 0L)
                    val staging = OwnedLyricsProvider.stagingFile(context, token)
                    val completed = OwnedLyricsProvider.finalFile(context, token)
                    var installed = false
                    try {
                        val digest = MessageDigest.getInstance("SHA-256")
                        var copied = 0L
                        var reads = 0
                        val buffer = ByteArray(COPY_CHUNK_BYTES)
                        openInput(candidate, signal).use { input ->
                            FileChannel.open(staging.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                                LinkOption.NOFOLLOW_LINKS).use { output ->
                                while (true) {
                                    signal.throwIfCanceled()
                                    caller.ensureActive()
                                    if (++reads > MAX_READ_OPERATIONS) throw SourceReadException(SourceReadIssue.INVALID_SOURCE)
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (count == 0 || count > buffer.size) throw SourceReadException(SourceReadIssue.INVALID_SOURCE)
                                    val next = copied + count
                                    requireSpace(root, occupiedBytes, next)
                                    if (candidate.sizeBytes != null && next > candidate.sizeBytes) {
                                        throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
                                    }
                                    digest.update(buffer, 0, count)
                                    val bytes = ByteBuffer.wrap(buffer, 0, count)
                                    while (bytes.hasRemaining()) {
                                        signal.throwIfCanceled()
                                        caller.ensureActive()
                                        if (output.write(bytes) <= 0) throw SourceReadException(SourceReadIssue.SOURCE_UNAVAILABLE)
                                    }
                                    copied = next
                                }
                                if (candidate.sizeBytes != null && copied != candidate.sizeBytes) {
                                    throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
                                }
                                if (copied == 0L) throw SourceReadException(SourceReadIssue.INVALID_SOURCE)
                                val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                                if (hash != expectedSha256) throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
                                output.force(true)
                            }
                        }
                        signal.throwIfCanceled()
                        caller.ensureActive()
                        Files.move(staging.toPath(), completed.toPath(), StandardCopyOption.ATOMIC_MOVE)
                        installed = true
                        syncDirectory(root)
                        Copy(OwnedLyricsProvider.uri(context, token).toString(), expectedSha256) {
                            synchronized(pinLock) { pins.remove(token) }
                        }
                    } catch (error: Exception) {
                        if (installed) Files.deleteIfExists(completed.toPath())
                        throw error
                    } finally {
                        Files.deleteIfExists(staging.toPath())
                    }
                },
            )
            currentCoroutineContext().ensureActive()
            delivered = true
            return result
        } catch (error: CancellationException) {
            cancelled = true
            throw error
        } finally {
            try {
                if (cancelled) withContext(NonCancellable) { io.awaitIdle() }
                if (!delivered) synchronized(pinLock) { pins.remove(token) }
            } finally {
                operation.unlock()
            }
        }
    }

    /**
     * 由真实数据库逐个判引用；查询不包在物理 IO 内，不物化所有关联。
     * 当前借用副本保留。失败或取消的数据库提交先保留文件，下次只回收确无引用的对象。
     * 引用的创建、替换和备份恢复必须走 withReferenceWrite，与本次扫描共用同一个门。
     */
    suspend fun recover(isReferenced: suspend (String) -> Boolean) {
        if (!operation.tryLock()) throw SourceReadException(SourceReadIssue.BUSY)
        val retainedCursor = AtomicReference<RecoveryCursor?>()
        try {
            // The holder is covered by outer cleanup before the worker creates the cursor.
            // Returning Unit avoids transferring a native close into an abandon callback.
            io.read {
                val stream = openRecoveryDirectory(OwnedLyricsProvider.root(context))
                try { retainedCursor.set(RecoveryCursor(stream)) }
                catch (error: Exception) {
                    stream.close()
                    throw error
                }
                catch (error: OutOfMemoryError) {
                    stream.close()
                    throw error
                }
            }
            val cursor = checkNotNull(retainedCursor.get())
            while (true) {
                currentCoroutineContext().ensureActive()
                val page = io.read { signal ->
                    buildList {
                        while (size < RECOVERY_PAGE_SIZE && cursor.iterator.hasNext()) {
                            signal.throwIfCanceled()
                            if (++cursor.visited > MAX_STORED_ENTRIES) throw SourceReadException(SourceReadIssue.STORAGE_FULL)
                            val path = cursor.iterator.next()
                            val name = path.fileName.toString()
                            requireStoredName(name)
                            storedBytes(path)
                            add(name)
                        }
                    }
                }
                if (page.isEmpty()) break
                for (name in page) {
                    currentCoroutineContext().ensureActive()
                    val token = name.substringBeforeLast('.')
                    if (synchronized(pinLock) { token in pins }) continue
                    val referenced = name.endsWith(".lrc") && isReferenced(OwnedLyricsProvider.uri(context, token).toString())
                    if (!referenced) io.read { signal ->
                        signal.throwIfCanceled()
                        if (!synchronized(pinLock) { token in pins }) {
                            val file = if (name.endsWith(".part")) OwnedLyricsProvider.stagingFile(context, token)
                                else OwnedLyricsProvider.finalFile(context, token)
                            Files.deleteIfExists(file.toPath())
                            syncDirectory(OwnedLyricsProvider.root(context))
                        }
                    }
                }
            }
        } finally {
            try {
                withContext(NonCancellable) {
                    io.awaitIdle()
                    val cursor = retainedCursor.get() ?: return@withContext
                    while (true) {
                        try {
                            io.read { cursor.close() }
                            retainedCursor.compareAndSet(cursor, null)
                            break
                        } catch (error: SourceReadException) {
                            if (error.issue != SourceReadIssue.BUSY) throw error
                            // An unrelated accepted read can win after awaitIdle; this sole cleanup
                            // keeps its cursor and operation gate until close is actually admitted.
                            yield()
                            io.awaitIdle()
                        }
                    }
                }
            } finally { operation.unlock() }
        }
    }

    /** Create any copy first, then fence the database write against reference-check/file-delete races. */
    suspend fun <T> withReferenceWrite(action: suspend () -> T): T {
        if (!operation.tryLock()) throw SourceReadException(SourceReadIssue.BUSY)
        try { return action() }
        finally { operation.unlock() }
    }

    private fun inventoryBytes(root: File, signal: CancellationSignal): Long {
        var bytes = 0L
        var entries = 0
        Files.newDirectoryStream(root.toPath()).use { stream ->
            for (path in stream) {
                signal.throwIfCanceled()
                if (++entries > MAX_STORED_ENTRIES) throw SourceReadException(SourceReadIssue.STORAGE_FULL)
                requireStoredName(path.fileName.toString())
                val occupied = storedBytes(path)
                if (occupied > DISK_BYTES - bytes) throw SourceReadException(SourceReadIssue.STORAGE_FULL)
                bytes += occupied
            }
        }
        return bytes
    }

    private fun storedBytes(path: Path): Long {
        val facts = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!facts.isRegularFile || facts.isSymbolicLink || facts.size() < 0L) {
            throw SourceReadException(SourceReadIssue.INVALID_SOURCE)
        }
        if (facts.size() > Long.MAX_VALUE - DIRECTORY_ENTRY_BYTES) throw SourceReadException(SourceReadIssue.STORAGE_FULL)
        return facts.size() + DIRECTORY_ENTRY_BYTES
    }

    private fun requireStoredName(name: String) {
        val suffix = name.substringAfterLast('.', "")
        if (suffix != "lrc" && suffix != "part") throw SourceReadException(SourceReadIssue.INVALID_SOURCE)
        val token = name.substringBeforeLast('.')
        if (token.length != 36 || try { UUID.fromString(token).toString() != token } catch (_: IllegalArgumentException) { true }) {
            throw SourceReadException(SourceReadIssue.INVALID_SOURCE)
        }
    }

    private fun requireSpace(root: File, occupiedBytes: Long, nextBytes: Long) {
        if (nextBytes > LyricsLimits.INPUT_BYTES) throw SourceReadException(SourceReadIssue.INVALID_SOURCE)
        if (nextBytes > DISK_BYTES - occupiedBytes - DIRECTORY_ENTRY_BYTES) throw SourceReadException(SourceReadIssue.STORAGE_FULL)
        val available = freeBytes(root)
        if (available < FREE_RESERVE_BYTES || nextBytes > available - FREE_RESERVE_BYTES) {
            throw SourceReadException(SourceReadIssue.STORAGE_FULL)
        }
    }

    private class RecoveryCursor(private val stream: DirectoryStream<Path>) : AutoCloseable {
        val iterator: Iterator<Path> = stream.iterator()
        var visited = 0
        private val closed = AtomicBoolean(false)
        override fun close() { if (closed.compareAndSet(false, true)) stream.close() }
    }

    companion object {
        const val DISK_BYTES = 8_388_608L
        const val FREE_RESERVE_BYTES = 536_870_912L
        // Even empty crash stages consume directory metadata; the byte quota bounds traversal too.
        internal const val DIRECTORY_ENTRY_BYTES = 256L
        private const val MAX_STORED_ENTRIES = 32_768
        private const val COPY_CHUNK_BYTES = 8_192
        private const val MAX_READ_OPERATIONS = 65_536
        private const val RECOVERY_PAGE_SIZE = 32
    }
}

private fun syncLyricsDirectory(directory: File) {
    val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
    try {
        if (!OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) throw SourceReadException(SourceReadIssue.INVALID_SOURCE)
        Os.fsync(descriptor)
    } finally {
        Os.close(descriptor)
    }
}
