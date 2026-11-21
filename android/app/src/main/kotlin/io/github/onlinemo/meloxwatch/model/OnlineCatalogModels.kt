package io.github.onlinemo.meloxwatch.model

import java.security.MessageDigest

enum class OnlineCatalogKind { PLAYLISTS, LIKED, DAILY, PLAYLIST, SEARCH }
enum class OnlineCatalogEntryKind { TRACK, PLAYLIST }
enum class OnlineCatalogIssue {
    OFFLINE, TIMEOUT, CERTIFICATE, RATE_LIMITED, LOGIN_REQUIRED, SESSION_UNAVAILABLE,
    INVALID_RESPONSE, INCOMPLETE, STALE_VIEW, BUSY, STORAGE_FULL, STORAGE_UNAVAILABLE, CLOSED,
}

class OnlineCatalogException(val issue: OnlineCatalogIssue) : Exception(issue.name)

/** The private query stays local except for the explicit provider request; logs never print it. */
data class OnlineCatalogQuery(val kind: OnlineCatalogKind, val argument: String = "") {
    init {
        require(argument.length <= 256 && argument.none { it.code < 32 })
        when (kind) {
            OnlineCatalogKind.SEARCH -> require(argument.isNotBlank() && argument == argument.trim())
            OnlineCatalogKind.PLAYLIST -> require(onlineCanonicalId(argument))
            else -> require(argument.isEmpty())
        }
    }
    val cacheKey: String get() = onlineOpaqueHash(kind.name + ":" + argument)
    override fun toString(): String = "OnlineCatalogQuery(kind=$kind, redacted)"
}

data class OnlineCatalogSnapshot(
    val id: String,
    val revision: Long,
    val kind: OnlineCatalogKind,
    val count: Long,
    val reportedTotal: Long?,
    val complete: Boolean,
    val metadataCount: Long,
    val missingMetadataCount: Long,
    val cachedAtEpochMs: Long,
) {
    override fun toString(): String = "OnlineCatalogSnapshot(kind=$kind, count=$count, complete=$complete, redacted)"
}

/** Stable metadata only. An artwork key is an opaque, bounded-memory lookup, never a remote URL. */
data class OnlineCatalogEntry(
    val order: Long,
    val kind: OnlineCatalogEntryKind,
    val id: String,
    val title: String?,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val trackCount: Long? = null,
    val artworkKey: String? = null,
    val metadataMissing: Boolean = false,
) {
    init {
        require(order >= 0L && onlineCanonicalId(id))
        require(durationMs == null || durationMs > 0L)
        require(trackCount == null || trackCount >= 0L)
    }
    override fun toString(): String = "OnlineCatalogEntry(kind=$kind, metadataMissing=$metadataMissing, redacted)"
}

data class OnlineCatalogProgress(val received: Long, val reportedTotal: Long?, val finishingMetadata: Boolean)

internal fun onlineCanonicalId(value: String): Boolean =
    value.length in 1..32 && value.first() in '1'..'9' && value.all { it in '0'..'9' }

internal fun onlineOpaqueHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
