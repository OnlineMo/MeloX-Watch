package io.github.onlinemo.meloxwatch.data.artwork

import android.graphics.Bitmap
import android.os.CancellationSignal
import android.os.StatFs
import io.github.onlinemo.meloxwatch.data.storage.StorageCategory
import io.github.onlinemo.meloxwatch.data.storage.StorageLimits
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** The caller serializes disk operations with the same gate as image decoding. */
internal class ArtworkDiskCache(private val directory: File) {
    private val entries = LinkedHashMap<String, Long>()
    private var bytes = 0L
    private var initialized = false
    private val keyPattern = Regex("[0-9a-f]{64}")

    fun load(key: String, signal: CancellationSignal): ByteArray? {
        initialize(signal)
        val expected = entries[key] ?: return null
        val file = child("$key.png")
        if (!file.isFile || file.length() != expected || expected > ArtworkLimits.DISK_ENTRY_BYTES) {
            discard(key, signal)
            return null
        }
        val result = Files.newInputStream(file.toPath()).use { readArtworkBytes(it, signal) }
        entries.remove(key)
        entries[key] = expected
        writeIndex(signal)
        return result
    }

    fun discard(key: String, signal: CancellationSignal) {
        initialize(signal)
        entries.remove(key)?.let { bytes -= it }
        Files.deleteIfExists(child("$key.png").toPath())
        writeIndex(signal)
    }

    fun put(key: String, bitmap: Bitmap, signal: CancellationSignal) {
        if (!keyPattern.matches(key)) throw ArtworkException(ArtworkIssue.CACHE_UNAVAILABLE)
        initialize(signal)
        requireRoom(ArtworkLimits.DISK_ENTRY_BYTES)
        trim(ArtworkLimits.DISK_ENTRY_BYTES, key, signal)
        val temporary = child("$key-${UUID.randomUUID()}.part")
        try {
            FileOutputStream(temporary).use { file ->
                val bounded = BoundedOutput(file, signal, ::requireRoom)
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, bounded)) throw ArtworkException(ArtworkIssue.CACHE_UNAVAILABLE)
                bounded.flush()
                file.fd.sync()
            }
            signal.throwIfCanceled()
            val actual = temporary.length()
            if (actual <= 0 || actual > ArtworkLimits.DISK_ENTRY_BYTES) throw ArtworkException(ArtworkIssue.CACHE_UNAVAILABLE)
            Files.move(temporary.toPath(), child("$key.png").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            entries.remove(key)?.let { bytes -= it }
            entries[key] = actual
            bytes += actual
            writeIndex(signal)
        } finally { Files.deleteIfExists(temporary.toPath()) }
    }

    internal fun occupiedBytes(): Long = bytes

    fun measure(signal: CancellationSignal): StorageCategory {
        initialize(signal)
        var actual = 0L
        var count = 0L
        Files.newDirectoryStream(directory.toPath()).use { files ->
            for (path in files) {
                signal.throwIfCanceled()
                val file = child(path.fileName.toString())
                if (!file.isFile || Files.isSymbolicLink(path)) throw ArtworkException(ArtworkIssue.CACHE_UNAVAILABLE)
                actual = Math.addExact(actual, Files.size(path))
                count = Math.addExact(count, 1L)
            }
        }
        return StorageCategory(actual, count)
    }

    /** 磁盘缓存所有者清理并重建自己的索引，调用方不能绕过 LRU 删除目录。 */
    fun clear(signal: CancellationSignal): StorageCategory {
        initialize(signal)
        try {
            Files.newDirectoryStream(directory.toPath()).use { files ->
                for (path in files) {
                    signal.throwIfCanceled()
                    val name = path.fileName.toString()
                    if (name == "index.json") continue
                    val file = child(name)
                    if (!file.isFile) throw ArtworkException(ArtworkIssue.CACHE_UNAVAILABLE)
                    Files.delete(file.toPath())
                    if (name.endsWith(".png")) entries.remove(name.removeSuffix(".png"))?.let { bytes -= it }
                }
            }
            entries.clear()
            bytes = 0L
            writeIndex(signal)
        } finally {
            // 部分清理取消或失败后，重试须依据实际保留的文件重建缓存状态。
            initialized = false
            entries.clear()
            bytes = 0L
        }
        return measure(signal)
    }

    private fun requireRoom(next: Long) {
        val available = try { StatFs(directory.absolutePath).availableBytes }
            catch (failure: Exception) { throw ArtworkException(ArtworkIssue.CACHE_UNAVAILABLE, failure) }
        if (next < 0L || available < StorageLimits.FREE_RESERVE_BYTES || next > available - StorageLimits.FREE_RESERVE_BYTES) {
            throw ArtworkException(ArtworkIssue.CACHE_UNAVAILABLE)
        }
    }

    private fun initialize(signal: CancellationSignal) {
        if (initialized) return
        signal.throwIfCanceled()
        val parent = directory.parentFile?.canonicalFile ?: throw ArtworkException(ArtworkIssue.CACHE_UNAVAILABLE)
        if (Files.isSymbolicLink(directory.toPath()) || directory.canonicalFile.parentFile != parent ||
            (!directory.isDirectory && !directory.mkdirs())) throw ArtworkException(ArtworkIssue.CACHE_UNAVAILABLE)
        val index = child("index.json")
        val restored = LinkedHashMap<String, Long>()
        var restoredBytes = 0L
        if (index.isFile && index.length() <= INDEX_BYTES) {
            try {
                val array = JSONArray(index.readText(Charsets.UTF_8))
                if (array.length() > ArtworkLimits.DISK_ENTRIES) throw ArtworkException(ArtworkIssue.CACHE_UNAVAILABLE)
                for (position in 0 until array.length()) {
                    signal.throwIfCanceled()
                    val row = array.getJSONObject(position)
                    val key = row.getString("key")
                    val length = row.getLong("bytes")
                    if (!keyPattern.matches(key) || key in restored || length !in 1..ArtworkLimits.DISK_ENTRY_BYTES) {
                        throw ArtworkException(ArtworkIssue.CACHE_UNAVAILABLE)
                    }
                    val file = child("$key.png")
                    if (file.isFile && file.length() == length) { restored[key] = length; restoredBytes += length }
                    if (restoredBytes > DATA_BYTES) throw ArtworkException(ArtworkIssue.CACHE_UNAVAILABLE)
                }
            } catch (error: android.os.OperationCanceledException) { throw error }
            catch (_: Exception) { restored.clear(); restoredBytes = 0 }
        }
        Files.newDirectoryStream(directory.toPath()).use { files ->
            for (file in files) {
                signal.throwIfCanceled()
                if (Files.isSymbolicLink(file) || !Files.isRegularFile(file)) throw ArtworkException(ArtworkIssue.CACHE_UNAVAILABLE)
                val name = file.fileName.toString()
                when {
                    name == "index.json" -> Unit
                    name.endsWith(".png") && keyPattern.matches(name.removeSuffix(".png")) -> {
                        if (name.removeSuffix(".png") !in restored) Files.deleteIfExists(file)
                    }
                    name.matches(Regex("[0-9a-f]{64}-[0-9a-f-]{36}\\.part")) ||
                        name.matches(Regex("index-[0-9a-f-]{36}\\.part")) -> Files.deleteIfExists(file)
                    else -> throw ArtworkException(ArtworkIssue.CACHE_UNAVAILABLE)
                }
            }
        }
        signal.throwIfCanceled()
        entries.clear()
        entries.putAll(restored)
        bytes = restoredBytes
        initialized = true
        writeIndex(signal)
    }

    private fun trim(required: Long, preserve: String, signal: CancellationSignal) {
        val iterator = entries.entries.iterator()
        while ((bytes + required > DATA_BYTES || entries.size >= ArtworkLimits.DISK_ENTRIES) && iterator.hasNext()) {
            signal.throwIfCanceled()
            val row = iterator.next()
            if (row.key == preserve) continue
            Files.deleteIfExists(child("${row.key}.png").toPath())
            bytes -= row.value
            iterator.remove()
        }
        if (bytes + required > DATA_BYTES || (preserve !in entries && entries.size >= ArtworkLimits.DISK_ENTRIES)) {
            throw ArtworkException(ArtworkIssue.CACHE_UNAVAILABLE)
        }
    }

    private fun writeIndex(signal: CancellationSignal) {
        signal.throwIfCanceled()
        val rows = JSONArray()
        for ((key, size) in entries) rows.put(JSONObject().put("key", key).put("bytes", size))
        val encoded = rows.toString().toByteArray(Charsets.UTF_8)
        if (encoded.size > INDEX_BYTES) throw ArtworkException(ArtworkIssue.CACHE_UNAVAILABLE)
        val temporary = child("index-${UUID.randomUUID()}.part")
        try {
            FileOutputStream(temporary).use { it.write(encoded); it.fd.sync() }
            signal.throwIfCanceled()
            Files.move(temporary.toPath(), child("index.json").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary.toPath()) }
    }

    private fun child(name: String): File {
        if (name.contains('/') || name.contains('\\')) throw ArtworkException(ArtworkIssue.CACHE_UNAVAILABLE)
        val raw = File(directory, name)
        if (Files.isSymbolicLink(raw.toPath())) throw ArtworkException(ArtworkIssue.CACHE_UNAVAILABLE)
        val result = raw.canonicalFile
        if (result.parentFile != directory.canonicalFile) throw ArtworkException(ArtworkIssue.CACHE_UNAVAILABLE)
        return result
    }

    private class BoundedOutput(private val output: OutputStream, private val signal: CancellationSignal, private val requireRoom: (Long) -> Unit) : OutputStream() {
        private var bytes = 0L
        override fun write(value: Int) { check(1); output.write(value) }
        override fun write(buffer: ByteArray, offset: Int, length: Int) { check(length); output.write(buffer, offset, length) }
        override fun flush() = output.flush()
        private fun check(length: Int) {
            signal.throwIfCanceled()
            if (length < 0 || bytes + length > ArtworkLimits.DISK_ENTRY_BYTES) throw ArtworkException(ArtworkIssue.CACHE_UNAVAILABLE)
            requireRoom(length.toLong())
            bytes += length
        }
    }

    companion object {
        private const val INDEX_BYTES = 128L * 1024
        private const val DATA_BYTES = ArtworkLimits.DISK_BYTES - 2 * INDEX_BYTES
    }
}
