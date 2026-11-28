package io.github.onlinemo.meloxwatch.data.artwork

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import android.provider.DocumentsContract
import io.github.onlinemo.meloxwatch.data.library.LocalContentValidation
import io.github.onlinemo.meloxwatch.data.library.LocalReadDescriptor
import io.github.onlinemo.meloxwatch.data.local.AndroidLocalSources
import io.github.onlinemo.meloxwatch.data.local.AudioTagReader
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.model.ArtworkReadPath
import io.github.onlinemo.meloxwatch.data.themes.ThemeBitmapLease
import io.github.onlinemo.meloxwatch.data.themes.ThemeBitmapOwner
import io.github.onlinemo.meloxwatch.data.themes.ThemeBitmapReservation
import io.github.onlinemo.meloxwatch.data.themes.ThemeException
import io.github.onlinemo.meloxwatch.data.themes.ThemeIssue
import io.github.onlinemo.meloxwatch.data.storage.StorageCategory
import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class EncodedArtwork(val bytes: ByteArray, val orientation: Int = 0)

/** Bounded thumbnail and embedded-cover access to an already resolved, explicitly granted source. */
class LocalArtworkRepository(
    context: Context,
    private val io: LocalProviderIo,
    private val validateRead: suspend (LocalReadDescriptor) -> LocalReadDescriptor,
    private val bitmaps: ThemeBitmapOwner = ThemeBitmapOwner(),
    private val confirmedArtwork: (suspend (LocalReadDescriptor, Int) -> ThemeBitmapLease?)? = null,
) {
    private val sourceReader = AndroidArtworkReader(context)
    private val disk = ArtworkDiskCache(File(context.cacheDir, "artwork-v1"))
    private val gate = ArtworkRequestGate()
    private val session = UUID.randomUUID().toString()
    private val noArtwork = LinkedHashMap<String, Long>()
    private val missingLock = Any()

    suspend fun acquire(
        descriptor: LocalReadDescriptor,
        path: ArtworkReadPath,
        targetPx: Int = 96,
        priority: ArtworkPriority = ArtworkPriority.VISIBLE,
        refresh: Boolean = false,
    ): ArtworkLease? {
        val owned = acquireOwned(descriptor, path, targetPx, priority, refresh) ?: return null
        return ArtworkLease(owned.bitmap, owned.key, owned::close)
    }

    /** Background covers and foreground artwork retain leases from the same injected owner. */
    suspend fun acquireOwned(
        descriptor: LocalReadDescriptor,
        path: ArtworkReadPath,
        targetPx: Int = 96,
        priority: ArtworkPriority = ArtworkPriority.CURRENT,
        refresh: Boolean = false,
    ): ThemeBitmapLease? {
        require(targetPx in 1..ArtworkLimits.OUTPUT_EDGE)
        currentCoroutineContext().ensureActive()
        // Explicit local associations precede rebuildable covers; this callback accesses no HTTP.
        confirmedArtwork?.invoke(descriptor, targetPx)?.let { return it }
        val baseKey = artworkKey(descriptor, path, targetPx)
        // Unknown content facts expire by request time; hidden UI does not run a refresh timer.
        val key = if (descriptor.providerContentToken != null || descriptor.verifiedContentToken != null) baseKey else
            hashKey("$baseKey:$session:${SystemClock.elapsedRealtime() / UNKNOWN_CONTENT_MS}")
        if (!refresh) {
            bitmaps.acquire(key)?.let { return it }
            if (isMissing(key)) return null
        }
        val ticket = gate.enter(priority)
        var decoded: PendingArtwork? = null
        try {
            currentCoroutineContext().ensureActive()
            if (!refresh) {
                bitmaps.acquire(key)?.let { return it }
                if (isMissing(key)) return null
            }
            if (!refresh) decoded = provider {
                io.read(abandoned = { it?.close() }) { signal ->
                    val cached = try { disk.load(key, signal) } catch (error: android.os.OperationCanceledException) { throw error }
                        catch (_: Exception) { null }
                    if (cached == null) return@read null
                    try { decodeOwned(cached, targetPx, signal) }
                    catch (error: ArtworkException) {
                        if (error.issue == ArtworkIssue.LOW_MEMORY) throw error
                        try { disk.discard(key, signal) } catch (_: Exception) { }
                        null
                    }
                }
            }
            if (decoded == null) {
                val verified = validate(descriptor)
                decoded = provider {
                    io.read(abandoned = { it?.close() }) { signal ->
                        val encoded = when (path) {
                            ArtworkReadPath.SYSTEM_THUMBNAIL -> sourceReader.thumbnail(verified, targetPx, signal)
                                ?: sourceReader.embedded(verified, signal)
                            ArtworkReadPath.LOCAL_EXTRACT -> sourceReader.embedded(verified, signal)
                        } ?: return@read null
                        decodeOwned(encoded.bytes, targetPx, signal, encoded.orientation)
                    }
                }
                // Validation acquires the shared channel after extraction, never recursively inside io.read.
                // Changed bytes must not publish an image or a missing-image entry under the old content key.
                validate(verified)
                currentCoroutineContext().ensureActive()
                val fresh = decoded
                if (fresh == null) { rememberMissing(key); return null }
                persist(key, fresh.bitmap)
            }
            currentCoroutineContext().ensureActive()
            val ready = requireNotNull(decoded)
            val lease = ready.publish(bitmaps, key)
            decoded = null
            synchronized(missingLock) { noArtwork.remove(key) }
            return lease
        } catch (error: ThemeException) {
            throw ArtworkException(if (error.issue == ThemeIssue.BUSY) ArtworkIssue.BUSY else ArtworkIssue.INVALID_IMAGE, error)
        } catch (error: SourceReadException) {
            throw ArtworkException(when (error.issue) {
                SourceReadIssue.BUSY -> ArtworkIssue.BUSY
                SourceReadIssue.PERMISSION_DENIED, SourceReadIssue.PERMISSION_REVOKED -> ArtworkIssue.PERMISSION_DENIED
                SourceReadIssue.METADATA_UNREADABLE -> if (error.cause is OutOfMemoryError) ArtworkIssue.LOW_MEMORY else ArtworkIssue.INVALID_IMAGE
                else -> ArtworkIssue.SOURCE_UNAVAILABLE
            }, error)
        } finally {
            decoded?.close()
            try { withContext(NonCancellable) { io.awaitIdle() } }
            finally { gate.exit(ticket) }
        }
    }

    fun trimUnused() { bitmaps.trimUnused() }

    suspend fun measureDiskCache(): StorageCategory = diskCommand { disk.measure(it) }
    suspend fun clearDiskCache(): StorageCategory = diskCommand { signal ->
        disk.clear(signal).also { synchronized(missingLock) { noArtwork.clear() } }
    }

    private suspend fun diskCommand(action: (CancellationSignal) -> StorageCategory): StorageCategory {
        val ticket = gate.enter(ArtworkPriority.VISIBLE)
        try { return provider { io.read(action = action) } }
        finally { try { withContext(NonCancellable) { io.awaitIdle() } } finally { gate.exit(ticket) } }
    }

    /** Online thumbnails share the existing gate, bitmap owner and 32 MiB disk LRU; this method never accesses HTTP. */
    internal suspend fun derived(key: String, targetPx: Int, encoded: ByteArray? = null): ThemeBitmapLease? {
        require(key.matches(Regex("[0-9a-f]{64}")) && targetPx in 1..ArtworkLimits.OUTPUT_EDGE)
        val ticket = gate.enter(ArtworkPriority.VISIBLE)
        var decoded: PendingArtwork? = null
        try {
            currentCoroutineContext().ensureActive()
            bitmaps.acquire(key)?.let { return it }
            decoded = provider {
                io.read(abandoned = { it?.close() }) { signal ->
                    val bytes = encoded ?: try { disk.load(key, signal) }
                        catch (cancelled: android.os.OperationCanceledException) { throw cancelled }
                        catch (_: Exception) { null }
                    bytes?.let {
                        try { decodeOwned(it, targetPx, signal) }
                        catch (error: ArtworkException) {
                            if (encoded != null || error.issue == ArtworkIssue.LOW_MEMORY) throw error
                            try { disk.discard(key, signal) }
                            catch (cancelled: android.os.OperationCanceledException) { throw cancelled }
                            catch (_: Exception) { }
                            null
                        }
                    }
                }
            }
            val ready = decoded ?: return null
            if (encoded != null) persist(key, ready.bitmap)
            currentCoroutineContext().ensureActive()
            return ready.publish(bitmaps, key).also { decoded = null }
        } finally {
            decoded?.close()
            try { withContext(NonCancellable) { io.awaitIdle() } }
            finally { gate.exit(ticket) }
        }
    }

    private fun decodeOwned(bytes: ByteArray, targetPx: Int, signal: CancellationSignal, orientation: Int = 0): PendingArtwork {
        // Rotation can briefly own both source and output. Reserve before BitmapFactory/Matrix.
        val reservation = bitmaps.reserve(2L * ArtworkLimits.BITMAP_BYTES)
        var decoded: Bitmap? = null
        try {
            val bitmap = BoundedArtworkDecoder.decode(bytes, targetPx, signal, orientation)
            decoded = bitmap
            signal.throwIfCanceled()
            reservation.resize(bitmap.allocationByteCount.toLong())
            val result = PendingArtwork(bitmap, reservation)
            decoded = null
            return result
        } catch (error: Throwable) {
            decoded?.recycle()
            reservation.close()
            throw error
        }
    }

    private class PendingArtwork(
        bitmap: Bitmap,
        private val reservation: ThemeBitmapReservation,
    ) : AutoCloseable {
        private var held: Bitmap? = bitmap
        val bitmap: Bitmap get() = checkNotNull(held)
        fun publish(owner: ThemeBitmapOwner, key: String): ThemeBitmapLease {
            val result = owner.publish(key, bitmap, reservation)
            held = null
            return result
        }
        override fun close() {
            // A late resume cancellation may close on Main; retire memory without native work.
            held = null
            reservation.close()
        }
    }

    private suspend fun validate(descriptor: LocalReadDescriptor): LocalReadDescriptor {
        val verified = provider { validateRead(descriptor) }
        if (verified.contentValidation != LocalContentValidation.VERIFIED ||
            verified.copy(contentValidation = descriptor.contentValidation) != descriptor) {
            throw ArtworkException(ArtworkIssue.SOURCE_UNAVAILABLE)
        }
        return verified
    }

    private suspend fun persist(key: String, bitmap: Bitmap) {
        try {
            provider {
                io.read { signal ->
                    disk.put(key, bitmap, signal)
                    signal.throwIfCanceled()
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* A disk failure keeps the validated in-memory image and any old display lease. */ }
    }

    private suspend fun <T> provider(action: suspend () -> T): T {
        repeat(3) {
            currentCoroutineContext().ensureActive()
            try { return action() }
            catch (error: SourceReadException) {
                if (error.issue != SourceReadIssue.BUSY || it == 2) throw error
                io.awaitIdle()
            }
        }
        throw ArtworkException(ArtworkIssue.BUSY)
    }

    private fun isMissing(key: String): Boolean = synchronized(missingLock) {
        val expiry = noArtwork[key] ?: return false
        if (SystemClock.elapsedRealtime() < expiry) true else { noArtwork.remove(key); false }
    }

    private fun rememberMissing(key: String) = synchronized(missingLock) {
        noArtwork.remove(key)
        while (noArtwork.size >= ArtworkLimits.MEMORY_ENTRIES) noArtwork.remove(noArtwork.keys.first())
        noArtwork[key] = SystemClock.elapsedRealtime() + UNKNOWN_CONTENT_MS
    }

    companion object { private const val UNKNOWN_CONTENT_MS = 60_000L }
}

/** Uses the same public typed-thumbnail request as loadThumbnail, with bounds checked before decoding. */
internal class AndroidArtworkReader(private val context: Context) {
    fun thumbnail(descriptor: LocalReadDescriptor, targetPx: Int, signal: CancellationSignal): EncodedArtwork? {
        val uri = AndroidLocalSources.contentUri(descriptor.location.uri)
        val options = Bundle().apply { putParcelable(ContentResolver.EXTRA_SIZE, Point(targetPx, targetPx)) }
        return try {
            context.contentResolver.openTypedAssetFileDescriptor(uri, "image/*", options, signal)?.use { file ->
                if (file.declaredLength > ArtworkLimits.INPUT_BYTES) throw ArtworkException(ArtworkIssue.INPUT_TOO_LARGE)
                val orientation = file.extras?.getInt(DocumentsContract.EXTRA_ORIENTATION, 0) ?: 0
                EncodedArtwork(file.createInputStream().use { readArtworkBytes(it, signal) }, orientation)
            }
        } catch (_: FileNotFoundException) { null }
    }

    fun embedded(descriptor: LocalReadDescriptor, signal: CancellationSignal): EncodedArtwork? {
        val uri = AndroidLocalSources.contentUri(descriptor.location.uri)
        return context.contentResolver.openAssetFileDescriptor(uri, "r", signal)?.use { file ->
            file.createInputStream().use stream@{ input ->
                val extracted = try { AudioTagReader.readPicture(input, signal) }
                catch (error: EOFException) { throw ArtworkException(ArtworkIssue.INVALID_IMAGE, error) }
                if (extracted != null) return@stream EncodedArtwork(extracted)
                // Keep the descriptor open for native fallback; closing this stream closes its FD.
                if (file.declaredLength !in 1..ArtworkLimits.INPUT_BYTES.toLong()) return@stream null
                val retriever = MediaMetadataRetriever()
                try {
                    signal.throwIfCanceled()
                    retriever.setDataSource(file.fileDescriptor, file.startOffset, file.declaredLength)
                    val bytes = retriever.embeddedPicture ?: return@stream null
                    signal.throwIfCanceled()
                    if (bytes.size > ArtworkLimits.INPUT_BYTES) throw ArtworkException(ArtworkIssue.INPUT_TOO_LARGE)
                    EncodedArtwork(bytes)
                } catch (_: IllegalArgumentException) { null }
                catch (error: OutOfMemoryError) { throw ArtworkException(ArtworkIssue.LOW_MEMORY, error) }
                finally { retriever.release() }
            }
        }
    }
}

private fun hashKey(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
