package io.github.onlinemo.meloxwatch.data.themes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.CancellationSignal
import android.os.OperationCanceledException
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkException
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkIssue
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkLimits
import io.github.onlinemo.meloxwatch.data.artwork.BoundedArtworkDecoder
import io.github.onlinemo.meloxwatch.data.fonts.AndroidLyricsFontDecoder
import io.github.onlinemo.meloxwatch.data.fonts.FontWeightCapability
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontDecoder
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontException
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontIssue
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontRole
import io.github.onlinemo.meloxwatch.data.fonts.SfntFontParser
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.EnumMap
import kotlinx.coroutines.CancellationException

/** 原生验证使用同一物理 worker；临时 bitmap 在每项结束释放，不建立第二个字体 face/cache 所有者。 */
internal class ThemeAssetValidator(private val fontDecoder: LyricsFontDecoder = AndroidLyricsFontDecoder()) {
    private val fontParser = SfntFontParser()

    fun validate(payload: File, manifest: ThemeManifest, signal: CancellationSignal): Map<LyricsFontRole, FontWeightCapability> {
        verifyThemePayloadTree(payload, manifest, signal::throwIfCanceled)
        val fonts = HashMap<String, FontWeightCapability>()
        val iconIds = manifest.icons.values.toSet()
        for (resource in manifest.resources) {
            signal.throwIfCanceled()
            val file = themeContainedFile(payload, resource.path)
            val bytes = verifiedBytes(file, resource, signal)
            when (resource.kind) {
                ThemeResourceKind.IMAGE -> validateImage(bytes, resource.mediaType, resource.id in iconIds, signal)
                ThemeResourceKind.FONT -> {
                    val info = try {
                        fontParser.parse(bytes, signal::throwIfCanceled)
                    } catch (error: LyricsFontException) {
                        throw fontFailure(error)
                    }
                    fonts[resource.id] = nativeFont { fontDecoder.validate(file, info, signal::throwIfCanceled) }
                }
            }
        }
        signal.throwIfCanceled()
        val result = EnumMap<LyricsFontRole, FontWeightCapability>(LyricsFontRole::class.java)
        for ((role, id) in manifest.fonts) result[role] = fonts.getValue(id)
        return result
    }

    fun verifiedBytes(file: File, resource: ThemeResource, signal: CancellationSignal): ByteArray {
        if (resource.byteCount !in 1..themeResourceLimit(resource.kind)) {
            themeFail(ThemeIssue.INTEGRITY_FAILED)
        }
        val bytes = readBoundedThemeFile(
            file, resource.byteCount.toInt(), ThemeIssue.INTEGRITY_FAILED, signal::throwIfCanceled,
        )
        if (bytes.size.toLong() != resource.byteCount) themeFail(ThemeIssue.INTEGRITY_FAILED)
        val digest = MessageDigest.getInstance("SHA-256")
        var offset = 0
        while (offset < bytes.size) {
            signal.throwIfCanceled()
            val count = minOf(ThemeLimits.IO_BYTES, bytes.size - offset)
            digest.update(bytes, offset, count)
            offset += count
        }
        if (themeHex(digest.digest()) != resource.sha256) themeFail(ThemeIssue.INTEGRITY_FAILED)
        return bytes
    }

    fun decodeImage(bytes: ByteArray, mediaType: String, targetPx: Int, icon: Boolean, signal: CancellationSignal): Bitmap {
        ThemeImageHeaders.verify(bytes, mediaType, signal::throwIfCanceled)
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        bounds.inScaled = false
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        signal.throwIfCanceled()
        if (bounds.outMimeType != mediaType || bounds.outWidth <= 0 || bounds.outHeight <= 0) themeFail(ThemeIssue.INVALID_IMAGE)
        if (bounds.outWidth.toLong() * bounds.outHeight > ArtworkLimits.INPUT_PIXELS) themeFail(ThemeIssue.IMAGE_TOO_LARGE)
        if (icon && (mediaType != "image/png" || maxOf(bounds.outWidth, bounds.outHeight) > ThemeLimits.ICON_EDGE)) {
            themeFail(ThemeIssue.INVALID_ICON)
        }
        return try {
            BoundedArtworkDecoder.decode(bytes, targetPx, signal)
        } catch (error: ArtworkException) {
            val issue = when (error.issue) {
                ArtworkIssue.INPUT_TOO_LARGE -> ThemeIssue.IMAGE_TOO_LARGE
                ArtworkIssue.LOW_MEMORY -> ThemeIssue.LOW_MEMORY
                else -> ThemeIssue.INVALID_IMAGE
            }
            throw ThemeException(issue, error)
        }
    }

    private fun validateImage(bytes: ByteArray, mediaType: String, icon: Boolean, signal: CancellationSignal) {
        val target = if (icon) ThemeLimits.ICON_EDGE else ThemeLimits.VALIDATION_IMAGE_EDGE
        val bitmap = decodeImage(bytes, mediaType, target, icon, signal)
        try {
            if (icon) validateIcon(bitmap, signal)
        } finally {
            bitmap.recycle()
        }
    }

    private fun validateIcon(bitmap: Bitmap, signal: CancellationSignal) {
        if (!bitmap.hasAlpha()) themeFail(ThemeIssue.INVALID_ICON)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var opaque = false
        var transparent = false
        for (index in pixels.indices) {
            if ((index and 255) == 0) signal.throwIfCanceled()
            val alpha = Color.alpha(pixels[index])
            if (alpha == 255) opaque = true
            if (alpha == 0) transparent = true
        }
        if (!opaque || !transparent) themeFail(ThemeIssue.INVALID_ICON)
    }

    private fun <T> nativeFont(action: () -> T): T = try {
        action()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (cancelled: OperationCanceledException) {
        throw cancelled
    } catch (error: LyricsFontException) {
        throw fontFailure(error)
    } catch (error: IOException) {
        throw ThemeException(ThemeIssue.INVALID_FONT, error)
    } catch (error: RuntimeException) {
        throw ThemeException(ThemeIssue.INVALID_FONT, error)
    }

    private fun fontFailure(error: LyricsFontException): ThemeException = ThemeException(
        if (error.issue == LyricsFontIssue.LOW_MEMORY) ThemeIssue.LOW_MEMORY else ThemeIssue.INVALID_FONT, error,
    )
}

internal fun themeHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
