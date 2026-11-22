package io.github.onlinemo.meloxwatch.data.storage

import io.github.onlinemo.meloxwatch.data.library.LocalReadDescriptor

enum class StorageIssue { BUSY, PERMISSION_REVOKED, SOURCE_CHANGED, INCOMPLETE, STORAGE_FULL, CANCELLED, UNAVAILABLE }
class StorageException(val issue: StorageIssue, cause: Throwable? = null) : Exception(issue.name, cause)

/** 字节值来自实际观察的文件或引用；未知观察不能填成零。 */
data class StorageCategory(val bytes: Long?, val files: Long, val unknownFiles: Long = 0L, val issue: StorageIssue? = null) {
    init { require(bytes == null || bytes >= 0L)
        require(files >= 0L && unknownFiles >= 0L) }
}
data class StorageReferencePage(val references: List<LocalReadDescriptor?>, val nextLocationId: String?)
data class StorageReport(
    val originalReferences: StorageCategory,
    val audioCopies: StorageCategory,
    val artworkCache: StorageCategory,
    val lyricsCache: StorageCategory,
    val recoveryStaging: StorageCategory,
    val availableBytes: Long?,
)
data class StorageClearReport(
    val artworkBefore: StorageCategory,
    val artworkAfter: StorageCategory,
    val lyricsBefore: StorageCategory,
    val lyricsAfter: StorageCategory,
    val availableBefore: Long?,
    val availableAfter: Long?,
    val issue: StorageIssue? = null,
)

object StorageLimits { const val FREE_RESERVE_BYTES = 512L * 1024L * 1024L }

internal fun combineStorage(left: StorageCategory, right: StorageCategory) = StorageCategory(
    if (left.bytes == null && right.bytes == null) null else Math.addExact(left.bytes ?: 0L, right.bytes ?: 0L),
    Math.addExact(left.files, right.files), Math.addExact(left.unknownFiles, right.unknownFiles), left.issue ?: right.issue)
