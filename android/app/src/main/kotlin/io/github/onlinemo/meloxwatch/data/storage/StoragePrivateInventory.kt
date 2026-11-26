package io.github.onlinemo.meloxwatch.data.storage

import android.content.Context
import android.os.CancellationSignal
import android.os.OperationCanceledException
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

internal enum class StorageArea { AUDIO, LYRICS, ARTWORK, FONTS, THEMES, BACKGROUNDS, RECOVERY }

/** Owners supply their private namespace contract. This visitor receives no mutation authority. */
internal data class StorageDirectorySpec(
    val directory: File,
    val area: StorageArea,
    val maxDepth: Int,
    val maxNodes: Long?,
    val classify: (parts: List<String>, directory: Boolean) -> StorageArea?,
    val lockFile: String? = null,
    val mutableFile: (parts: List<String>) -> Boolean = { false },
    val requireNonEmpty: (parts: List<String>) -> Boolean = { false },
    val sentinels: List<String> = emptyList(),
)

/**
 * A single retained cursor advances at most 100 nodes per physical read, yielding to playback.
 * Root identities and modification times fence cross-directory moves such as transfer adoption.
 * Changed roots lose their byte subtotal to prevent cross-directory duplicate counts.
 * Measurements never become allocation or delete proofs.
 */
internal class StoragePrivateInventory(private val context: Context, private val io: LocalProviderIo,
    private val openDirectory: (Path) -> DirectoryStream<Path> = { Files.newDirectoryStream(it) }) {
    private val command = Mutex()
    private var retained: Cursor? = null

    suspend fun measure(specifications: () -> List<StorageDirectorySpec>): StoragePrivateReport {
        if (!command.tryLock()) throw StorageException(StorageIssue.BUSY)
        var failure: Throwable? = null
        try {
            val retirement = io.trackReads()
            return withContext(retirement) {
                try {
                    closeRetained()
                    val cursor = Cursor(openDirectory)
                    retained = cursor
                    io.read { signal -> cursor.open(specifications(), signal) }
                    while (!io.read { signal -> cursor.advance(signal) }) {
                        currentCoroutineContext().ensureActive()
                        yield()
                    }
                    io.read { signal -> cursor.finish(context, signal) }
                } catch (error: Throwable) {
                    failure = error
                    throw error
                } finally {
                    try {
                        withContext(NonCancellable) {
                            retirement.awaitRetired()
                            closeRetained()
                        }
                    } catch (closing: Throwable) {
                        val original = failure
                        if (original == null) throw closing
                        if (original !== closing) original.addSuppressed(closing)
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (retained?.isClosed == false) throw StorageException(StorageIssue.CLEANUP_REQUIRED, error)
            throw error
        } finally { command.unlock() }
    }

    /** A failed native close retains this one cursor; a retry closes it before opening new files. */
    private suspend fun closeRetained() {
        val cursor = retained ?: return
        while (true) {
            try {
                io.read { cursor.close() }
                if (cursor.isClosed) retained = null
                return
            } catch (failure: SourceReadException) {
                if (!io.isAdmissionBusy(failure)) {
                    if (cursor.isClosed) retained = null
                    throw StorageException(StorageIssue.CLEANUP_REQUIRED, failure)
                }
                io.awaitIdle()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (cursor.isClosed) retained = null
                throw StorageException(StorageIssue.CLEANUP_REQUIRED, failure)
            }
        }
    }

    private class Cursor(private val openDirectory: (Path) -> DirectoryStream<Path>) : AutoCloseable {
        private data class Fence(val spec: StorageDirectorySpec, val facts: BasicFileAttributes?,
            val sentinels: Map<String, BasicFileAttributes?>)
        private class Frame(val path: Path, val stream: DirectoryStream<Path>) {
            val iterator = stream.iterator()
        }
        private val totals = EnumMap<StorageArea, StorageCategory>(StorageArea::class.java).apply {
            StorageArea.entries.forEach { put(it, StorageCategory(0L, 0L)) }
        }
        private val fences = ArrayList<Fence>()
        private val channels = ArrayList<FileChannel>(16)
        private var openingChannel: FileChannel? = null
        private var openingStream: DirectoryStream<Path>? = null
        private val frames = ArrayDeque<Frame>()
        private var index = 0
        private var visited = 0L
        private var closed = false
        val isClosed: Boolean get() = closed

        fun open(specifications: List<StorageDirectorySpec>, signal: CancellationSignal) {
            require(specifications.size in 1..16)
            require(specifications.map { it.directory.absoluteFile }.distinct().size == specifications.size)
            for (spec in specifications) {
                signal.throwIfCanceled()
                require(spec.maxDepth in 1..16 && (spec.maxNodes == null || spec.maxNodes > 0L))
                require(spec.sentinels.size <= 16 && spec.sentinels.distinct().size == spec.sentinels.size &&
                    spec.sentinels.all { it.isNotEmpty() && it != "." && it != ".." && '/' !in it && '\\' !in it })
                try {
                    val facts = rootFacts(spec.directory)
                    if (facts != null && spec.lockFile != null) {
                        holdLock(spec, signal)
                        closeReadLocks()
                    }
                    val sentinels = if (facts == null) emptyMap() else spec.sentinels.associateWith { name ->
                        optionalFacts(spec.directory.resolve(name).toPath())
                    }
                    fences += Fence(spec, facts, sentinels)
                } catch (cancelled: OperationCanceledException) { throw cancelled }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    markUnknown(spec.area, storageIssue(failure))
                    markIncompleteRecovery(spec.area, storageIssue(failure))
                    closeOpeningChannel()
                }
            }
        }

        fun advance(signal: CancellationSignal): Boolean = withReadLocks(signal) { advanceUnlocked(signal) }

        private fun advanceUnlocked(signal: CancellationSignal): Boolean {
            var nodes = 0
            while (index < fences.size && nodes < 100) {
                signal.throwIfCanceled()
                val fence = fences[index]
                val spec = fence.spec
                if (fence.facts == null) { index++; continue }
                try {
                    if (frames.isEmpty()) {
                        visited = 0L
                        pushFrame(spec.directory.toPath())
                    }
                    val frame = frames.last()
                    if (!frame.iterator.hasNext()) {
                        frame.stream.close()
                        frames.removeLast()
                        if (frames.isEmpty()) index++
                        continue
                    }
                    val path = frame.iterator.next()
                    nodes++
                    visited = Math.addExact(visited, 1L)
                    if (spec.maxNodes != null && visited > spec.maxNodes) throw StorageException(StorageIssue.INCOMPLETE)
                    val parts = spec.directory.toPath().relativize(path).map { it.toString() }
                    if (parts.isEmpty() || parts.any { it == "." || it == ".." } || parts.size > spec.maxDepth ||
                        path.parent != frame.path || path.toFile().canonicalFile.parentFile != frame.path.toFile().canonicalFile) {
                        throw StorageException(StorageIssue.SOURCE_CHANGED)
                    }
                    val facts = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    val area = spec.classify(parts, facts.isDirectory)
                    if (facts.isSymbolicLink || area == null || (!facts.isDirectory && !facts.isRegularFile) || facts.size() < 0L) {
                        markUnknown(spec.area, StorageIssue.INCOMPLETE)
                        markIncompleteRecovery(spec.area, StorageIssue.INCOMPLETE)
                        continue
                    }
                    if (facts.isDirectory) {
                        if (parts.size == spec.maxDepth) throw StorageException(StorageIssue.INCOMPLETE)
                        pushFrame(path)
                    } else {
                        if (spec.requireNonEmpty(parts) && facts.size() == 0L) {
                            markUnknown(area, StorageIssue.INCOMPLETE)
                            continue
                        }
                        val after = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                        if (facts.fileKey() != after.fileKey() || facts.size() != after.size() ||
                            facts.lastModifiedTime() != after.lastModifiedTime() || !after.isRegularFile || after.isSymbolicLink) {
                            markUnknown(area, StorageIssue.SOURCE_CHANGED)
                        } else {
                            totals[area] = combineStorage(checkNotNull(totals[area]), StorageCategory(facts.size(), 1L))
                            // A stable directory and one stat pair do not seal an appendable stage.
                            if (spec.mutableFile(parts)) totals[area] = value(area).copy(issue = value(area).issue ?: StorageIssue.INCOMPLETE)
                        }
                    }
                } catch (cancelled: OperationCanceledException) { throw cancelled }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    markUnknown(spec.area, storageIssue(failure))
                    markIncompleteRecovery(spec.area, storageIssue(failure))
                    try { closeFrames() }
                    catch (closing: Throwable) {
                        if (failure !== closing) failure.addSuppressed(closing)
                        throw failure
                    }
                    index++
                }
            }
            return index == fences.size
        }

        fun finish(context: Context, signal: CancellationSignal): StoragePrivateReport = withReadLocks(signal) { finishUnlocked(context, signal) }

        private fun finishUnlocked(context: Context, signal: CancellationSignal): StoragePrivateReport {
            check(index == fences.size && frames.isEmpty())
            for (fence in fences) {
                signal.throwIfCanceled()
                val same = try {
                    val after = rootFacts(fence.spec.directory)
                    val before = fence.facts
                    val sameRoot = if (before == null) after == null else after != null && before.fileKey() == after.fileKey() &&
                        before.lastModifiedTime() == after.lastModifiedTime()
                    sameRoot && fence.sentinels.all { (name, original) ->
                        sameFacts(original, optionalFacts(fence.spec.directory.resolve(name).toPath()))
                    }
                } catch (_: Exception) { false }
                if (!same) {
                    val area = fence.spec.area
                    totals[area] = value(area).copy(bytes = null, unknownFiles = Math.addExact(value(area).unknownFiles, 1L),
                        issue = value(area).issue ?: StorageIssue.SOURCE_CHANGED)
                    totals[StorageArea.RECOVERY] = value(StorageArea.RECOVERY).copy(bytes = null,
                        issue = value(StorageArea.RECOVERY).issue ?: StorageIssue.SOURCE_CHANGED)
                }
            }
            return StoragePrivateReport(value(StorageArea.AUDIO), value(StorageArea.LYRICS), value(StorageArea.ARTWORK),
                value(StorageArea.FONTS), value(StorageArea.THEMES), value(StorageArea.BACKGROUNDS), value(StorageArea.RECOVERY),
                FileStoragePhysical.available(context))
        }

        private fun rootFacts(directory: File): BasicFileAttributes? {
            val root = directory.absoluteFile
            val parent = root.parentFile ?: throw StorageException(StorageIssue.UNAVAILABLE)
            val parentFacts = Files.readAttributes(parent.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (!parentFacts.isDirectory || parentFacts.isSymbolicLink || root.canonicalFile.parentFile != parent.canonicalFile) {
                throw StorageException(StorageIssue.UNAVAILABLE)
            }
            return try {
                Files.readAttributes(root.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).also {
                    if (!it.isDirectory || it.isSymbolicLink) throw StorageException(StorageIssue.INCOMPLETE)
                }
            } catch (_: NoSuchFileException) {
                val after = Files.readAttributes(parent.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                if (!after.isDirectory || after.isSymbolicLink || parentFacts.fileKey() != after.fileKey()) {
                    throw StorageException(StorageIssue.SOURCE_CHANGED)
                }
                null
            }
        }

        private fun optionalFacts(path: Path): BasicFileAttributes? = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) { null }

        private fun sameFacts(before: BasicFileAttributes?, after: BasicFileAttributes?): Boolean =
            if (before == null) after == null else after != null && before.fileKey() == after.fileKey() &&
                before.size() == after.size() && before.lastModifiedTime() == after.lastModifiedTime() &&
                before.isDirectory == after.isDirectory && before.isRegularFile == after.isRegularFile && before.isSymbolicLink == after.isSymbolicLink

        private fun holdLock(spec: StorageDirectorySpec, signal: CancellationSignal) {
            signal.throwIfCanceled()
            val path = spec.directory.resolve(checkNotNull(spec.lockFile)).toPath()
            val facts = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (!facts.isRegularFile || facts.isSymbolicLink) throw StorageException(StorageIssue.INCOMPLETE)
            check(openingChannel == null)
            val channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
            openingChannel = channel
            try { channel.tryLock(0L, Long.MAX_VALUE, true) }
                catch (_: OverlappingFileLockException) { throw StorageException(StorageIssue.BUSY) }
                ?: throw StorageException(StorageIssue.BUSY)
            channels += channel
            openingChannel = null
        }

        private fun value(area: StorageArea) = checkNotNull(totals[area])

        private fun markUnknown(area: StorageArea, issue: StorageIssue) {
            totals[area] = combineStorage(value(area), StorageCategory(null, 0L, 1L, issue))
        }

        private fun markIncompleteRecovery(area: StorageArea, issue: StorageIssue) {
            if (area != StorageArea.RECOVERY) {
                val old = value(StorageArea.RECOVERY)
                totals[StorageArea.RECOVERY] = old.copy(bytes = old.bytes.takeIf { old.files > 0L }, issue = old.issue ?: issue)
            }
        }

        private fun closeFrames() {
            var failure: Throwable? = null
            openingStream?.let { stream ->
                try { stream.close(); openingStream = null }
                catch (closing: Throwable) { failure = closing }
            }
            for (index in frames.lastIndex downTo 0) {
                try { frames[index].stream.close(); frames.removeAt(index) }
                catch (closing: Throwable) { if (failure == null) failure = closing else if (failure !== closing) failure.addSuppressed(closing) }
            }
            failure?.let { throw it }
        }

        private fun pushFrame(path: Path) {
            check(openingStream == null)
            val stream = openDirectory(path)
            openingStream = stream
            frames.addLast(Frame(path, stream))
            openingStream = null
        }

        private fun closeOpeningChannel() {
            openingChannel?.let { channel -> channel.close(); openingChannel = null }
        }

        /** Read locks end with this batch so unrelated theme/font reads can run between batches. */
        private fun <T> withReadLocks(signal: CancellationSignal, action: () -> T): T {
            var failure: Throwable? = null
            try {
                for (fence in fences) if (fence.facts != null && fence.spec.lockFile != null) holdLock(fence.spec, signal)
                return action()
            } catch (error: Throwable) { failure = error; throw error }
            finally {
                try { closeReadLocks() }
                catch (closing: Throwable) {
                    val original = failure
                    if (original == null) throw closing
                    if (original !== closing) original.addSuppressed(closing)
                }
            }
        }

        private fun closeReadLocks() {
            var failure: Throwable? = null
            try { closeOpeningChannel() } catch (closing: Throwable) { failure = closing }
            for (index in channels.lastIndex downTo 0) {
                try { channels[index].close(); channels.removeAt(index) }
                catch (closing: Throwable) {
                    if (failure == null) failure = closing else if (failure !== closing) failure.addSuppressed(closing)
                }
            }
            failure?.let { throw it }
        }

        override fun close() {
            if (closed) return
            var failure: Throwable? = null
            try { closeFrames() } catch (closing: Throwable) { failure = closing }
            try { closeReadLocks() } catch (closing: Throwable) {
                if (failure == null) failure = closing else if (failure !== closing) failure.addSuppressed(closing)
            }
            closed = openingStream == null && openingChannel == null && frames.isEmpty() && channels.isEmpty()
            failure?.let { throw it }
        }
    }
}

private fun storageIssue(failure: Exception): StorageIssue = when (failure) {
    is StorageException -> failure.issue
    is AccessDeniedException, is SecurityException -> StorageIssue.PERMISSION_REVOKED
    else -> StorageIssue.UNAVAILABLE
}
