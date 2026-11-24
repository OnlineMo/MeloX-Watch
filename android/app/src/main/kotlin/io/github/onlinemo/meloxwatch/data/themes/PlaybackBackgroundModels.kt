package io.github.onlinemo.meloxwatch.data.themes

import io.github.onlinemo.meloxwatch.data.artwork.ArtworkLimits
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

object PlaybackBackgroundLimits {
    const val DERIVED_EDGE = 512
    const val DERIVED_PIXELS = 262_144
    const val DECODE_BYTES = 2L * 1024 * 1024
    const val SETTINGS_BYTES = 8 * 1024
    const val STORED_SOURCE_BYTES = 2L * ArtworkLimits.INPUT_BYTES
    const val DIRECTORY_ENTRIES = 16
    const val BLUR_MAX = 20
}

data class BackgroundCrop(val zoom: Float = 1f, val horizontal: Float = 0.5f, val vertical: Float = 0.5f) {
    init {
        require(zoom.isFinite() && zoom in 1f..4f)
        require(horizontal.isFinite() && horizontal in 0f..1f)
        require(vertical.isFinite() && vertical in 0f..1f)
    }
}

/** A managed private copy; neither a provider URI nor a user filesystem path is persisted. */
data class BackgroundImageRef(
    val id: String,
    val sha256: String,
    val byteCount: Long,
    val mediaType: String,
    val width: Int,
    val height: Int,
    val orientation: Int = 1,
) {
    init {
        require(isThemeOperationId(id) && isThemeDigest(sha256))
        require(byteCount in 1..ArtworkLimits.INPUT_BYTES.toLong())
        require(mediaType in setOf("image/png", "image/jpeg", "image/webp"))
        require(width > 0 && height > 0 && width.toLong() * height <= ArtworkLimits.INPUT_PIXELS)
        require(orientation in 1..8)
    }
}

data class PlaybackBackgroundOptions(
    val mode: ThemeBackgroundMode = ThemeBackgroundMode.DEFAULT,
    val blurLevel: Int = 0,
    val darkMaskPercent: Int = 50,
    val crop: BackgroundCrop = BackgroundCrop(),
    val customImage: BackgroundImageRef? = null,
) {
    init {
        require(blurLevel in 0..PlaybackBackgroundLimits.BLUR_MAX && darkMaskPercent in 0..100)
    }
    val supportsBlur: Boolean get() = mode == ThemeBackgroundMode.COVER_BLUR || mode == ThemeBackgroundMode.CUSTOM_IMAGE
    val effectiveBlur: Int get() = if (supportsBlur) blurLevel else 0
}

data class PlaybackBackgroundSettings(val revision: Long = 0, val options: PlaybackBackgroundOptions = PlaybackBackgroundOptions()) {
    init { require(revision >= 0) }
}

enum class PlaybackBackgroundIssue {
    BUSY, INVALID_INPUT, NO_IMAGE, PERMISSION_DENIED, SOURCE_UNAVAILABLE, IMAGE_TOO_LARGE, INVALID_IMAGE,
    LOW_MEMORY, INPUT_CLOSE_FAILED, STORAGE_FULL, STORAGE_UNAVAILABLE, INTEGRITY_FAILED, PREVIEW_NOT_FOUND,
    SETTINGS_CONFLICT, OPERATION_CONFLICT, SAVE_FAILED, RECOVERY_REQUIRED, CLEANUP_REQUIRED,
}

class PlaybackBackgroundException(val issue: PlaybackBackgroundIssue, cause: Throwable? = null) : Exception(issue.name, cause)

/** null means no trusted saved record was read, rather than a successful default save. */
data class PlaybackBackgroundSnapshot(val settings: PlaybackBackgroundSettings?, val issue: PlaybackBackgroundIssue? = null) {
    val effective: PlaybackBackgroundOptions get() = settings?.options ?: PlaybackBackgroundOptions()
}

data class CustomBackgroundImportRequest(val operationId: String, val uri: String, val displayName: String)
data class CustomBackgroundPreview(val token: String, val image: BackgroundImageRef, val displayName: String, val expectedRevision: Long)

sealed interface PlaybackBackgroundSaveResult {
    data class Saved(val snapshot: PlaybackBackgroundSnapshot) : PlaybackBackgroundSaveResult
    data class Conflict(val snapshot: PlaybackBackgroundSnapshot) : PlaybackBackgroundSaveResult
}

interface PlaybackBackgroundStore {
    suspend fun read(): PlaybackBackgroundSnapshot
    suspend fun prepareImage(request: CustomBackgroundImportRequest): CustomBackgroundPreview
    /** Memory-only retirement; the next physical operation removes the unaccepted owned copy. */
    fun retirePreview(token: String)
    suspend fun discardPreview(token: String)
    suspend fun compareAndSet(expectedRevision: Long, replacement: PlaybackBackgroundOptions, previewToken: String? = null): PlaybackBackgroundSaveResult
}

sealed interface PlaybackBackgroundImageKey {
    fun stableKey(): String

    /** 访问修订号和预览令牌仍供存储校验；它们不改变同一图像的派生像素。 */
    fun pixelKey(): String = stableKey()

    data class Cover(val fingerprint: String) : PlaybackBackgroundImageKey {
        init { require(isThemeDigest(fingerprint)) }
        override fun stableKey(): String = backgroundKey("cover", fingerprint)
    }

    data class Theme(val snapshot: ThemeSnapshot, val resourceId: String) : PlaybackBackgroundImageKey {
        init { require(snapshot.effective is ThemeChoice.Installed && isThemeId(resourceId)) }
        override fun stableKey(): String {
            val ref = (snapshot.effective as ThemeChoice.Installed).ref
            return backgroundKey("theme", snapshot.revision.toString(), ref.installationId, ref.themeId, ref.manifestSha256, resourceId)
        }
        override fun pixelKey(): String {
            val ref = (snapshot.effective as ThemeChoice.Installed).ref
            return backgroundKey("theme", ref.installationId, ref.themeId, ref.manifestSha256, resourceId)
        }
    }

    data class Custom(val image: BackgroundImageRef, val settingsRevision: Long? = null, val previewToken: String? = null) : PlaybackBackgroundImageKey {
        init {
            require((settingsRevision != null) != (previewToken != null))
            require(settingsRevision == null || settingsRevision >= 0)
            require(previewToken == null || isThemeOperationId(previewToken))
        }
        override fun stableKey(): String = backgroundKey("custom", image.id, image.sha256, image.byteCount.toString(),
            image.mediaType, image.width.toString(), image.height.toString(), image.orientation.toString(),
            settingsRevision.toString(), previewToken.orEmpty())
        override fun pixelKey(): String = backgroundKey("custom", image.id, image.sha256, image.byteCount.toString(),
            image.mediaType, image.width.toString(), image.height.toString(), image.orientation.toString())
    }
}

data class PlaybackBackgroundImageRequest(val key: PlaybackBackgroundImageKey, val targetPx: Int) {
    init { require(targetPx in 1..PlaybackBackgroundLimits.DERIVED_EDGE) }
}

/** Implementations use the same injected LocalProviderIo; they do not fetch implicit network data. */
fun interface PlaybackBackgroundImageSource {
    suspend fun load(request: PlaybackBackgroundImageRequest): ThemeBitmapLease?
}

/** Physical round geometry is independent of an IME-reduced viewport. */
data class BackgroundRoundClip(val centerX: Float, val centerY: Float, val radius: Float) {
    init { require(centerX.isFinite() && centerY.isFinite() && radius.isFinite() && radius > 0f) }
}

data class BackgroundViewport(val widthPx: Int, val heightPx: Int, val roundClip: BackgroundRoundClip? = null) {
    init { require(widthPx > 0 && heightPx > 0) }
}

internal fun backgroundFail(issue: PlaybackBackgroundIssue): Nothing = throw PlaybackBackgroundException(issue)

internal fun backgroundKey(vararg parts: String): String {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { output -> for (part in parts) output.writeUTF(part) }
    return ThemeStorageRecords.digest(bytes.toByteArray())
}
