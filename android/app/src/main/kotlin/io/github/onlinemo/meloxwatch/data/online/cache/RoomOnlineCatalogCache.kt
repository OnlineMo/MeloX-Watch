package io.github.onlinemo.meloxwatch.data.online.cache

import android.os.StatFs
import androidx.room.withTransaction
import io.github.onlinemo.meloxwatch.model.OnlineCatalogEntry
import io.github.onlinemo.meloxwatch.model.OnlineCatalogEntryKind
import io.github.onlinemo.meloxwatch.model.OnlineCatalogException
import io.github.onlinemo.meloxwatch.model.OnlineCatalogIssue
import io.github.onlinemo.meloxwatch.model.OnlineCatalogKind
import io.github.onlinemo.meloxwatch.model.OnlineCatalogSnapshot
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Short transactions contain only cache rows and memory authorization checks, never provider calls. */
internal class RoomOnlineCatalogCache(private val database: OnlineCatalogDatabase, private val diskDirectory: File? = null) {
    val dao = database.catalog()

    suspend fun begin(owner: String, key: String, kind: OnlineCatalogKind, now: Long,
        preserve: Set<String>): OnlineCatalogViewEntity = storage {
        prune(0L, preserve, needView = true)
        checkDisk()
        OnlineCatalogViewEntity(UUID.randomUUID().toString(), owner, key, kind.name,
            cachedAtEpochMs = now.coerceAtLeast(0L)).also { dao.insertView(it) }
    }

    suspend fun apply(expected: OnlineCatalogViewEntity, next: OnlineCatalogViewEntity,
        rows: List<OnlineCatalogRowEntity>, preserve: Set<String>, authorized: () -> Boolean): OnlineCatalogViewEntity = storage {
        require(rows.size <= 100 && rows.all { it.viewId == expected.id })
        require(next.id == expected.id && next.ownerHash == expected.ownerHash && next.queryKey == expected.queryKey)
        require(next.revision == expected.revision + 1L && next.usedBytes >= 0L)
        prune((next.usedBytes - expected.usedBytes).coerceAtLeast(0L), preserve + expected.id, needView = false)
        checkDisk()
        database.withTransaction {
            currentCoroutineContext().ensureActive()
            if (!authorized()) throw CancellationException("Online account changed")
            if (dao.view(expected.id) != expected) throw OnlineCatalogException(OnlineCatalogIssue.STALE_VIEW)
            if (rows.isNotEmpty()) dao.putRows(rows)
            if (dao.putView(next) != 1) throw OnlineCatalogException(OnlineCatalogIssue.STALE_VIEW)
            currentCoroutineContext().ensureActive()
            if (!authorized()) throw CancellationException("Online account changed")
        }
        next
    }

    suspend fun remove(id: String) = storage { dao.delete(id) }

    private suspend fun prune(additionalBytes: Long, preserve: Set<String>, needView: Boolean) {
        for (candidate in dao.oldest()) {
            val bytesFit = dao.usedBytes() <= MAX_BYTES - additionalBytes
            val countFits = !needView || dao.viewCount() < MAX_VIEWS
            if (bytesFit && countFits) return
            if (candidate.id !in preserve) dao.delete(candidate.id)
        }
        if (dao.usedBytes() > MAX_BYTES - additionalBytes || needView && dao.viewCount() >= MAX_VIEWS) {
            throw OnlineCatalogException(OnlineCatalogIssue.STORAGE_FULL)
        }
    }

    private fun checkDisk() {
        val directory = diskDirectory ?: return
        // The physical reserve remains unavailable until any cache reclamation actually finishes.
        val free = StatFs(directory.absolutePath).availableBytes
        if (free <= 0L) throw OnlineCatalogException(OnlineCatalogIssue.STORAGE_UNAVAILABLE)
        if (free < RESERVE_BYTES) throw OnlineCatalogException(OnlineCatalogIssue.STORAGE_FULL)
        val files = listOf("online-catalog.db", "online-catalog.db-journal", "online-catalog.db-wal", "online-catalog.db-shm")
        if (files.sumOf { directory.resolve(it).length() } > MAX_DISK_BYTES) {
            throw OnlineCatalogException(OnlineCatalogIssue.STORAGE_FULL)
        }
    }

    private suspend fun <T> storage(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { action() }
        catch (error: CancellationException) { throw error }
        catch (error: OnlineCatalogException) { throw error }
        catch (_: android.database.sqlite.SQLiteFullException) { throw OnlineCatalogException(OnlineCatalogIssue.STORAGE_FULL) }
        catch (_: Exception) { throw OnlineCatalogException(OnlineCatalogIssue.STORAGE_UNAVAILABLE) }
    }

    companion object {
        const val MAX_BYTES = 16 * 1024 * 1024L
        const val MAX_DISK_BYTES = 32 * 1024 * 1024L
        const val MAX_VIEWS = 12L
        private const val RESERVE_BYTES = 512 * 1024 * 1024L
    }
}

internal fun OnlineCatalogViewEntity.snapshot() = OnlineCatalogSnapshot(id, revision, OnlineCatalogKind.valueOf(kind),
    count, reportedTotal, complete, metadataCursor, missingMetadataCount, cachedAtEpochMs)

internal fun OnlineCatalogRowEntity.entry() = OnlineCatalogEntry(position, OnlineCatalogEntryKind.valueOf(kind), itemId,
    title, artist, album, durationMs, trackCount, artworkKey, metadataMissing)
