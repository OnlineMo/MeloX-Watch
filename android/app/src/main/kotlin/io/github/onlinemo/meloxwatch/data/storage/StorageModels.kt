package io.github.onlinemo.meloxwatch.data.storage

import io.github.onlinemo.meloxwatch.data.library.LocalReadDescriptor

enum class StorageIssue { BUSY, PERMISSION_REVOKED, SOURCE_CHANGED, INCOMPLETE, STORAGE_FULL, CANCELLED, UNAVAILABLE, CLEANUP_REQUIRED }
class StorageException(val issue: StorageIssue, cause: Throwable? = null) : Exception(issue.name, cause)

/** Bytes are the observed subtotal; unknownFiles and issue distinguish an incomplete measurement. */
data class StorageCategory(val bytes: Long?, val files: Long, val unknownFiles: Long = 0L, val issue: StorageIssue? = null) {
    init { require(bytes == null || bytes >= 0L)
        require(files >= 0L && unknownFiles >= 0L) }
    val complete: Boolean get() = bytes != null && unknownFiles == 0L && issue == null
    val observedBytes: Long? get() = bytes?.takeIf { complete || files > 0L }
}
data class StorageReferencePage(val references: List<LocalReadDescriptor?>, val nextLocationId: String?)
data class StorageReport(
    val originalReferences: StorageCategory,
    val audioCopies: StorageCategory,
    val lyricsCopies: StorageCategory,
    val savedArtwork: StorageCategory,
    val fonts: StorageCategory,
    val themes: StorageCategory,
    val backgrounds: StorageCategory,
    val artworkCache: StorageCategory,
    val lyricsCache: StorageCategory,
    val recoveryStaging: StorageCategory,
    val availableBytes: Long?,
)

/** This private file observation carries no directory, asset identity or material contents. */
data class StoragePrivateReport(
    val audioCopies: StorageCategory,
    val lyricsCopies: StorageCategory,
    val savedArtwork: StorageCategory,
    val fonts: StorageCategory,
    val themes: StorageCategory,
    val backgrounds: StorageCategory,
    val staging: StorageCategory,
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

internal fun combineStorage(left: StorageCategory, right: StorageCategory): StorageCategory {
    val files = Math.addExact(left.files, right.files)
    val unknown = Math.addExact(left.unknownFiles, right.unknownFiles)
    val issue = left.issue ?: right.issue
    val bytes = when {
        left.bytes == null && right.bytes == null -> null
        files == 0L && (unknown > 0L || issue != null) -> null
        else -> Math.addExact(left.bytes ?: 0L, right.bytes ?: 0L)
    }
    return StorageCategory(bytes, files, unknown, issue)
}
