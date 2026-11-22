package io.github.onlinemo.meloxwatch.data.lyrics.online

import io.github.onlinemo.meloxwatch.data.lyrics.LyricsLimits
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsParseException
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsParseIssue
import android.os.StatFs
import io.github.onlinemo.meloxwatch.data.storage.StorageCategory
import io.github.onlinemo.meloxwatch.data.storage.StorageLimits
import io.github.onlinemo.meloxwatch.model.onlineOpaqueHash
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class OnlineLyricsText(val primary: ByteArray, val translation: ByteArray?, val translationIssue: OnlineLyricsIssue? = null) {
    override fun toString(): String = "OnlineLyricsText(redacted)"
}

internal data class OnlineLyricsCacheKey(val ownerHash: String, val contentHash: String) {
    init { require(listOf(ownerHash, contentHash).all { it.matches(Regex("[0-9a-f]{64}")) }) }
    val fileName: String get() = "$ownerHash-$contentHash.lrc"
    override fun toString(): String = "OnlineLyricsCacheKey(redacted)"

    companion object {
        fun of(ownerHash: String, trackId: String, origin: OnlineLyricsOrigin, configuration: Long?,
            serviceFingerprint: String? = null): OnlineLyricsCacheKey {
            require(if (origin == OnlineLyricsOrigin.OPENED_METING_LRC)
                configuration != null && serviceFingerprint?.matches(Regex("[0-9a-f]{64}")) == true
                else configuration == null && serviceFingerprint == null)
            val source = "lyrics-v1:$trackId:${origin.name}:${configuration ?: -1L}" +
                serviceFingerprint?.let { ":$it" }.orEmpty()
            return OnlineLyricsCacheKey(ownerHash, onlineOpaqueHash(source))
        }
    }
}

/**
 * Only this no-backup cache directory is owned here; user LRCs and library files never enter it.
 * Cache originals remain on disk, not in a second parsed-memory LRU. Two bounded texts are read
 * directly into their arrays and checksumed without an extra whole encoded-file allocation.
 */
internal class DiskOnlineLyricsCache(private val directoryProvider: () -> File,
    private val maxBytes: Long = 8L * 1024L * 1024L, private val maxEntries: Int = 32) {
    constructor(directory: File, maxBytes: Long = 8L * 1024L * 1024L, maxEntries: Int = 32) :
        this({ directory }, maxBytes, maxEntries)
    init { require(maxBytes in 1L..8L * 1024L * 1024L && maxEntries in 1..32) }
    private val directory: File by lazy(directoryProvider)
    private val operations = Mutex()

    suspend fun read(key: OnlineLyricsCacheKey, maxTextBytes: Long = LyricsLimits.ACCOUNTED_BYTES): OnlineLyricsText? = operations.withLock {
        require(maxTextBytes in 1L..LyricsLimits.ACCOUNTED_BYTES)
        withContext(Dispatchers.IO) {
            val file = path(key.fileName)
            if (!file.exists()) return@withContext null
            requireFile(file)
            if (file.length() !in HEADER_BYTES.toLong()..MAX_FILE_BYTES) throw IOException("Lyrics cache is unavailable")
            val result = DataInputStream(file.inputStream().buffered(CHUNK_BYTES)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) throw IOException("Lyrics cache is unavailable")
                val primarySize = input.readInt()
                val translationSize = input.readInt()
                if (primarySize !in 1..LyricsLimits.INPUT_BYTES || translationSize !in -1..LyricsLimits.INPUT_BYTES ||
                    file.length() != HEADER_BYTES + primarySize.toLong() + translationSize.coerceAtLeast(0)) {
                    throw IOException("Lyrics cache is unavailable")
                }
                if (primarySize.toLong() + translationSize.coerceAtLeast(0) > maxTextBytes) {
                    throw LyricsParseException(LyricsParseIssue.MEMORY_LIMIT)
                }
                val expected = ByteArray(32).also(input::readFully)
                val digest = contentDigest(primarySize, translationSize)
                val primary = ByteArray(primarySize)
                readText(input, primary, digest)
                val translation = if (translationSize < 0) null else ByteArray(translationSize).also { readText(input, it, digest) }
                if (input.read() != -1 || !MessageDigest.isEqual(expected, digest.digest())) throw IOException("Lyrics cache is unavailable")
                OnlineLyricsText(primary, translation)
            }
            currentCoroutineContext().ensureActive()
            file.setLastModified(System.currentTimeMillis())
            result
        }
    }

    /** A cache failure leaves the fetched display usable; callers report their source separately. */
    suspend fun write(key: OnlineLyricsCacheKey, text: OnlineLyricsText, isCurrent: () -> Boolean): Boolean = operations.withLock {
        withContext(Dispatchers.IO) {
            if (text.primary.size !in 1..LyricsLimits.INPUT_BYTES ||
                (text.translation?.size ?: 0) > LyricsLimits.INPUT_BYTES) return@withContext false
            val size = HEADER_BYTES + text.primary.size.toLong() + (text.translation?.size ?: 0)
            if (size > maxBytes) return@withContext false
            checkCurrent(isCurrent)
            ensureDirectory()
            val available = StatFs(directory.absolutePath).availableBytes
            if (available < StorageLimits.FREE_RESERVE_BYTES || size > available - StorageLimits.FREE_RESERVE_BYTES) return@withContext false
            val target = path(key.fileName)
            val entries = inventory()
            // The old target stays intact until atomic replacement; pending owns another directory entry.
            val pendingEntries = 1
            var used = entries.sumOf { it.length() }
            var count = entries.size
            for (old in entries.filter { it != target }.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })) {
                if (used + size <= maxBytes && count + pendingEntries <= maxEntries) break
                checkCurrent(isCurrent)
                val bytes = old.length()
                if (!old.delete()) throw IOException("Lyrics cache is unavailable")
                used -= bytes
                count--
            }
            if (used + size > maxBytes || count + pendingEntries > maxEntries) return@withContext false
            val pending = path(key.fileName + "." + UUID.randomUUID().toString() + ".pending")
            try {
                val digest = contentDigest(text.primary.size, text.translation?.size ?: -1)
                hashText(digest, text.primary, isCurrent)
                text.translation?.let { hashText(digest, it, isCurrent) }
                val hash = digest.digest()
                DataOutputStream(pending.outputStream().buffered(CHUNK_BYTES)).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(VERSION)
                    output.writeInt(text.primary.size)
                    output.writeInt(text.translation?.size ?: -1)
                    output.write(hash)
                    writeText(output, text.primary, isCurrent)
                    text.translation?.let { writeText(output, it, isCurrent) }
                }
                checkCurrent(isCurrent)
                requireFile(pending)
                if (pending.length() != size) throw IOException("Lyrics cache is unavailable")
                Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                checkCurrent(isCurrent)
                true
            } finally {
                if (pending.exists()) pending.delete()
            }
        }
    }

    /** Logout/expiry removes only the exact account's owned derived files, including abandoned writes. */
    suspend fun clearOwner(ownerHash: String) = operations.withLock {
        require(ownerHash.matches(Regex("[0-9a-f]{64}")))
        withContext(Dispatchers.IO) {
            for (file in inventory()) {
                currentCoroutineContext().ensureActive()
                if (file.name.startsWith("$ownerHash-") && !file.delete()) throw IOException("Lyrics cache is unavailable")
            }
        }
    }

    suspend fun measureStorage(): StorageCategory = operations.withLock { withContext(Dispatchers.IO) { measuredStorage() } }

    /** 明确清理缓存不构造账户会话，也不访问已保存的歌词文档。 */
    suspend fun clearStorage(): StorageCategory = operations.withLock {
        withContext(Dispatchers.IO) {
            measuredStorage()
            for (file in inventory()) {
                currentCoroutineContext().ensureActive()
                Files.delete(file.toPath())
            }
            measuredStorage()
        }
    }

    private fun measuredStorage(): StorageCategory {
        try {
            val facts = Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (!facts.isDirectory || facts.isSymbolicLink) throw IOException("Lyrics cache is unavailable")
        } catch (_: NoSuchFileException) {
            val parent = directory.parentFile ?: throw IOException("Lyrics cache is unavailable")
            val facts = Files.readAttributes(parent.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (!facts.isDirectory || facts.isSymbolicLink) throw IOException("Lyrics cache is unavailable")
            return StorageCategory(0L, 0L)
        }
        val files = inventory()
        var bytes = 0L
        for (file in files) bytes = Math.addExact(bytes, Files.size(file.toPath()))
        return StorageCategory(bytes, files.size.toLong())
    }

    private suspend fun readText(input: DataInputStream, bytes: ByteArray, digest: MessageDigest) {
        var offset = 0
        while (offset < bytes.size) {
            currentCoroutineContext().ensureActive()
            val count = input.read(bytes, offset, minOf(CHUNK_BYTES, bytes.size - offset))
            if (count <= 0) throw IOException("Lyrics cache is unavailable")
            digest.update(bytes, offset, count)
            offset += count
        }
    }

    private suspend fun writeText(output: DataOutputStream, bytes: ByteArray, isCurrent: () -> Boolean) {
        var offset = 0
        while (offset < bytes.size) {
            checkCurrent(isCurrent)
            val count = minOf(CHUNK_BYTES, bytes.size - offset)
            output.write(bytes, offset, count)
            offset += count
        }
    }

    private suspend fun hashText(digest: MessageDigest, bytes: ByteArray, isCurrent: () -> Boolean) {
        var offset = 0
        while (offset < bytes.size) {
            checkCurrent(isCurrent)
            val count = minOf(CHUNK_BYTES, bytes.size - offset)
            digest.update(bytes, offset, count)
            offset += count
        }
    }

    private suspend fun checkCurrent(isCurrent: () -> Boolean) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) throw CancellationException("Online lyrics intent retired")
    }

    private fun ensureDirectory() {
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Lyrics cache is unavailable")
        if (!directory.isDirectory || Files.isSymbolicLink(directory.toPath())) throw IOException("Lyrics cache is unavailable")
    }

    private fun path(name: String): File {
        if (!FILE_NAME.matches(name)) throw IOException("Lyrics cache is unavailable")
        val file = directory.resolve(name)
        if (Files.isSymbolicLink(directory.toPath()) || Files.isSymbolicLink(file.toPath()) ||
            file.canonicalFile.parentFile != directory.canonicalFile) throw IOException("Lyrics cache is unavailable")
        return file
    }

    private fun requireFile(file: File) {
        if (!file.isFile || Files.isSymbolicLink(file.toPath())) throw IOException("Lyrics cache is unavailable")
    }

    private fun inventory(): List<File> {
        if (!directory.exists()) return emptyList()
        ensureDirectory()
        val result = ArrayList<File>()
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            for (entry in entries) {
                if (result.size == MAX_INVENTORY) throw IOException("Lyrics cache is unavailable")
                val file = path(entry.fileName.toString())
                requireFile(file)
                result.add(file)
            }
        }
        return result
    }

    private fun contentDigest(primarySize: Int, translationSize: Int): MessageDigest =
        MessageDigest.getInstance("SHA-256").apply {
            update(ByteBuffer.allocate(16).putInt(MAGIC).putInt(VERSION).putInt(primarySize).putInt(translationSize).array())
        }

    private companion object {
        const val MAGIC = 0x4D4C5243
        const val VERSION = 1
        const val HEADER_BYTES = 48
        const val CHUNK_BYTES = 8192
        const val MAX_INVENTORY = 64
        const val MAX_FILE_BYTES = 48L + 2L * LyricsLimits.INPUT_BYTES
        val FILE_NAME = Regex("[0-9a-f]{64}-[0-9a-f]{64}\\.lrc(?:\\.[0-9a-f-]{36}\\.pending)?")
    }
}
