package io.github.onlinemo.meloxwatch.data.artwork

import android.graphics.Bitmap
import io.github.onlinemo.meloxwatch.data.library.LocalReadDescriptor
import io.github.onlinemo.meloxwatch.model.ArtworkReadPath
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

object ArtworkLimits {
    const val MEMORY_BYTES = 8L * 1024 * 1024
    const val DISK_BYTES = 32L * 1024 * 1024
    const val INPUT_BYTES = 16 * 1024 * 1024
    const val INPUT_PIXELS = 16_000_000L
    const val OUTPUT_EDGE = 2048
    const val BITMAP_BYTES = 2L * 1024 * 1024
    const val DISK_ENTRY_BYTES = 4L * 1024 * 1024
    const val MEMORY_ENTRIES = 128
    const val DISK_ENTRIES = 256
    const val LIVE_LEASES = 32
    const val WAITING_REQUESTS = 8
    const val VISIBLE_REFRESH_ITEMS = 32
}

enum class ArtworkPriority { CURRENT, VISIBLE, REFRESH }
enum class ArtworkIssue { BUSY, NO_ARTWORK, PERMISSION_DENIED, SOURCE_UNAVAILABLE, INPUT_TOO_LARGE, INVALID_IMAGE, LOW_MEMORY, CACHE_UNAVAILABLE }
class ArtworkException(val issue: ArtworkIssue, cause: Throwable? = null) : Exception(issue.name, cause)

/** The caller keeps the lease for the entire time the immutable bitmap is displayed. */
class ArtworkLease internal constructor(val bitmap: Bitmap, val key: String, private val release: () -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    override fun close() { if (closed.compareAndSet(false, true)) release() }
}

internal fun artworkKey(descriptor: LocalReadDescriptor, path: ArtworkReadPath, targetPx: Int): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val parts = listOf("artwork-v1", descriptor.trackKey.providerId, descriptor.trackKey.trackId,
        descriptor.location.locationId, descriptor.location.contentRevision.toString(), descriptor.aliasId,
        descriptor.source.authEpoch.toString(), descriptor.source.revision.toString(), descriptor.objectId,
        descriptor.providerContentToken.orEmpty(), descriptor.verifiedContentToken.orEmpty(), path.name, targetPx.toString())
    for (part in parts) {
        val bytes = part.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}
