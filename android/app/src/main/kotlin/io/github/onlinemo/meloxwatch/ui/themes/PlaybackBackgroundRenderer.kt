package io.github.onlinemo.meloxwatch.ui.themes

import android.graphics.Bitmap
import android.os.CancellationSignal
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkException
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkIssue
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.themes.AppliedThemeResources
import io.github.onlinemo.meloxwatch.data.themes.BackgroundCrop
import io.github.onlinemo.meloxwatch.data.themes.BackgroundViewport
import io.github.onlinemo.meloxwatch.data.themes.LocalBackgroundImages
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundException
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundImageKey
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundImageRequest
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundImageSource
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundIssue
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundLimits
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundOptions
import io.github.onlinemo.meloxwatch.data.themes.ThemeBackgroundMode
import io.github.onlinemo.meloxwatch.data.themes.ThemeBitmapLease
import io.github.onlinemo.meloxwatch.data.themes.ThemeBitmapOwner
import io.github.onlinemo.meloxwatch.data.themes.ThemeException
import io.github.onlinemo.meloxwatch.data.themes.ThemeIssue
import io.github.onlinemo.meloxwatch.data.themes.ThemePalette
import io.github.onlinemo.meloxwatch.data.themes.ThemeSnapshot
import io.github.onlinemo.meloxwatch.data.themes.backgroundFail
import io.github.onlinemo.meloxwatch.data.themes.backgroundKey
import io.github.onlinemo.meloxwatch.data.themes.validateThemePalette
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class PlaybackBackgroundRequest(
    val snapshot: ThemeSnapshot,
    val options: PlaybackBackgroundOptions,
    val image: PlaybackBackgroundImageKey?,
    val viewport: BackgroundViewport,
) {
    fun renderKey(): String {
        val size = renderSize(viewport)
        val crop = if (options.mode == ThemeBackgroundMode.CUSTOM_IMAGE) options.crop else BackgroundCrop()
        val clip = viewport.roundClip
        return backgroundKey("background-render-v1", options.mode.name, image?.pixelKey().orEmpty(),
            snapshot.effectiveManifest.palette.background.toString(), size.width.toString(), size.height.toString(),
            viewport.widthPx.toString(), viewport.heightPx.toString(), clip?.centerX.toString(), clip?.centerY.toString(), clip?.radius.toString(),
            options.effectiveBlur.toString(), crop.zoom.toString(), crop.horizontal.toString(), crop.vertical.toString())
    }
}

/** The controller owns this lease for the whole time composition may draw it. */
class BackgroundFrameLease internal constructor(
    val key: String,
    val mode: ThemeBackgroundMode,
    val palette: ThemePalette,
    val baseColor: Int,
    val image: ThemeBitmapLease? = null,
    val issue: PlaybackBackgroundIssue? = null,
) : AutoCloseable {
    override fun close() { image?.close() }
}

/** The cover adapter receives a current fingerprint, never an implicit lookup/network command. */
class ThemeAndCustomBackgroundSource(
    private val themes: AppliedThemeResources,
    private val custom: LocalBackgroundImages,
    private val cover: suspend (PlaybackBackgroundImageKey.Cover, Int) -> ThemeBitmapLease?,
) : PlaybackBackgroundImageSource {
    override suspend fun load(request: PlaybackBackgroundImageRequest): ThemeBitmapLease? = when (val key = request.key) {
        is PlaybackBackgroundImageKey.Theme -> themes.loadImage(key.snapshot, key.resourceId, request.targetPx)
        is PlaybackBackgroundImageKey.Custom -> custom.loadImage(key, request.targetPx)
        is PlaybackBackgroundImageKey.Cover -> cover(key, request.targetPx)
    }
}

/** One static derivative per intent, with cancellation checkpoints and no UI/frame timer. */
class PlaybackBackgroundRenderer(
    private val io: LocalProviderIo,
    private val source: PlaybackBackgroundImageSource,
    private val bitmaps: ThemeBitmapOwner,
) {
    suspend fun render(request: PlaybackBackgroundRequest): BackgroundFrameLease {
        currentCoroutineContext().ensureActive()
        validateThemePalette(request.snapshot.effectiveManifest.palette)
        val mode = request.options.mode
        if (mode == ThemeBackgroundMode.DEFAULT || mode == ThemeBackgroundMode.BLACK) return fallback(request)
        val imageKey = request.image ?: return fallback(request, PlaybackBackgroundIssue.NO_IMAGE)
        val key = request.renderKey()
        // Cover identity is the caller's current content fingerprint. Private managed sources
        // must still validate their saved revision/token and bytes before reusing a derivative.
        if (imageKey is PlaybackBackgroundImageKey.Cover) bitmaps.acquire(key)?.let { return frame(request, it) }
        return try {
            val size = renderSize(request.viewport)
            val input = source.load(PlaybackBackgroundImageRequest(imageKey, max(size.width, size.height)))
                ?: return fallback(request, PlaybackBackgroundIssue.NO_IMAGE)
            if (!bitmaps.owns(input)) {
                input.close()
                backgroundFail(PlaybackBackgroundIssue.INVALID_INPUT)
            }
            // The worker, including pre-action failure, owns input retirement after submission.
            // A cancelled caller cannot release its source while native/CPU work still uses it.
            io.read(abandoned = BackgroundFrameLease::close, failed = { input.close() }) { signal ->
                try {
                    bitmaps.acquire(key)?.let { return@read frame(request, it) }
                    renderPixels(request, input, size, signal)
                } finally { input.close() }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val issue = backgroundRenderIssue(error)
            if (issue == PlaybackBackgroundIssue.BUSY) throw PlaybackBackgroundException(issue, error)
            fallback(request, issue)
        } catch (error: OutOfMemoryError) { fallback(request, PlaybackBackgroundIssue.LOW_MEMORY) }
    }

    private fun renderPixels(
        request: PlaybackBackgroundRequest,
        input: ThemeBitmapLease,
        size: RenderSize,
        signal: CancellationSignal,
    ): BackgroundFrameLease {
        val bitmap = input.bitmap
        if (bitmap.isRecycled || max(bitmap.width, bitmap.height) > PlaybackBackgroundLimits.DERIVED_EDGE ||
            bitmap.width.toLong() * bitmap.height > PlaybackBackgroundLimits.DERIVED_PIXELS) {
            backgroundFail(PlaybackBackgroundIssue.IMAGE_TOO_LARGE)
        }
        val sourcePixels = bitmap.width * bitmap.height
        val outputPixels = size.width * size.height
        val blur = request.options.effectiveBlur
        val workBytes = sourcePixels * 4L + outputPixels * (if (blur == 0) 4L else 8L)
        val work = bitmaps.reserve(workBytes)
        var result: Bitmap? = null
        try {
            signal.throwIfCanceled()
            val original = IntArray(sourcePixels)
            bitmap.getPixels(original, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val base = request.snapshot.effectiveManifest.palette.background
            for (index in original.indices) {
                if (index and 255 == 0) signal.throwIfCanceled()
                original[index] = flatten(original[index], base)
            }
            val output = IntArray(outputPixels)
            if (request.options.mode == ThemeBackgroundMode.FLUID) {
                fluid(original, bitmap.width, bitmap.height, output, size, request.viewport, base, signal)
            } else {
                val cropChoice = if (request.options.mode == ThemeBackgroundMode.CUSTOM_IMAGE) request.options.crop else BackgroundCrop()
                crop(original, bitmap.width, bitmap.height, output, size, request.viewport, cropChoice, signal)
                if (blur != 0) {
                    val scratch = IntArray(outputPixels)
                    repeat(3) {
                        boxBlur(output, scratch, size.width, size.height, blur, horizontal = true, signal = signal)
                        boxBlur(scratch, output, size.width, size.height, blur, horizontal = false, signal = signal)
                    }
                }
            }
            signal.throwIfCanceled()
            val reservation = bitmaps.reserve(outputPixels * 4L)
            try {
                // The colors overload creates an immutable bitmap; no publication copy is needed.
                result = Bitmap.createBitmap(output, size.width, size.height, Bitmap.Config.ARGB_8888)
                signal.throwIfCanceled()
                val lease = bitmaps.publish(request.renderKey(), checkNotNull(result), reservation)
                result = null
                return frame(request, lease)
            } finally { reservation.close() }
        } finally {
            result?.recycle()
            work.close()
        }
    }

    private fun crop(
        source: IntArray,
        width: Int,
        height: Int,
        output: IntArray,
        size: RenderSize,
        viewport: BackgroundViewport,
        crop: BackgroundCrop,
        signal: CancellationSignal,
    ) {
        val clip = viewport.roundClip
        val physicalWidth = clip?.let { it.radius * 2.0 } ?: viewport.widthPx.toDouble()
        val physicalHeight = clip?.let { it.radius * 2.0 } ?: viewport.heightPx.toDouble()
        val scale = max(physicalWidth / width, physicalHeight / height) * crop.zoom
        val visibleWidth = physicalWidth / scale
        val visibleHeight = physicalHeight / scale
        val left = (width - visibleWidth) * crop.horizontal
        val top = (height - visibleHeight) * crop.vertical
        val physicalLeft = clip?.let { it.centerX - it.radius } ?: 0f
        val physicalTop = clip?.let { it.centerY - it.radius } ?: 0f
        for (y in 0 until size.height) {
            signal.throwIfCanceled()
            val sy = ((y + 0.5) * viewport.heightPx / size.height - physicalTop) / scale + top - 0.5
            for (x in 0 until size.width) {
                val sx = ((x + 0.5) * viewport.widthPx / size.width - physicalLeft) / scale + left - 0.5
                output[y * size.width + x] = sample(source, width, height, sx, sy)
            }
        }
    }

    private fun fluid(
        source: IntArray,
        width: Int,
        height: Int,
        output: IntArray,
        size: RenderSize,
        viewport: BackgroundViewport,
        base: Int,
        signal: CancellationSignal,
    ) {
        val first = average(source, width, height, left = true, bottom = false)
        val second = average(source, width, height, left = false, bottom = false)
        val third = average(source, width, height, left = true, bottom = true)
        val clip = viewport.roundClip
        val physicalWidth = clip?.let { it.radius * 2f } ?: viewport.widthPx.toFloat()
        val physicalHeight = clip?.let { it.radius * 2f } ?: viewport.heightPx.toFloat()
        val left = clip?.let { it.centerX - it.radius } ?: 0f
        val top = clip?.let { it.centerY - it.radius } ?: 0f
        for (y in 0 until size.height) {
            signal.throwIfCanceled()
            val fy = (((y + 0.5f) * viewport.heightPx / size.height - top) / physicalHeight).coerceIn(0f, 1f)
            val smoothY = fy * fy * (3 - 2 * fy)
            for (x in 0 until size.width) {
                val fx = (((x + 0.5f) * viewport.widthPx / size.width - left) / physicalWidth).coerceIn(0f, 1f)
                val smoothX = fx * fx * (3 - 2 * fx)
                val upper = mix(first, second, smoothX)
                val lower = mix(third, base, smoothX)
                output[y * size.width + x] = mix(mix(upper, lower, smoothY), base, 0.25f)
            }
        }
    }

    private fun average(source: IntArray, width: Int, height: Int, left: Boolean, bottom: Boolean): Int {
        var red = 0
        var green = 0
        var blue = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            val sx = (((x + 0.5) / 8 + if (left) 0.0 else 1.0) * width / 2).toInt().coerceIn(0, width - 1)
            val sy = (((y + 0.5) / 8 + if (bottom) 1.0 else 0.0) * height / 2).toInt().coerceIn(0, height - 1)
            val color = source[sy * width + sx]
            red += color ushr 16 and 255
            green += color ushr 8 and 255
            blue += color and 255
        }
        return rgb(red / 64, green / 64, blue / 64)
    }

    private fun boxBlur(input: IntArray, output: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean, signal: CancellationSignal) {
        val rows = if (horizontal) height else width
        val columns = if (horizontal) width else height
        val count = radius * 2 + 1
        fun pixel(row: Int, column: Int): Int = if (horizontal) input[row * width + column] else input[column * width + row]
        for (row in 0 until rows) {
            signal.throwIfCanceled()
            var red = 0
            var green = 0
            var blue = 0
            for (offset in -radius..radius) {
                val color = pixel(row, offset.coerceIn(0, columns - 1))
                red += color ushr 16 and 255
                green += color ushr 8 and 255
                blue += color and 255
            }
            for (column in 0 until columns) {
                val index = if (horizontal) row * width + column else column * width + row
                output[index] = rgb(red / count, green / count, blue / count)
                val before = pixel(row, (column - radius).coerceIn(0, columns - 1))
                val after = pixel(row, (column + radius + 1).coerceIn(0, columns - 1))
                red += (after ushr 16 and 255) - (before ushr 16 and 255)
                green += (after ushr 8 and 255) - (before ushr 8 and 255)
                blue += (after and 255) - (before and 255)
            }
        }
    }

    private fun sample(source: IntArray, width: Int, height: Int, x: Double, y: Double): Int {
        val boundedX = x.coerceIn(0.0, (width - 1).toDouble())
        val boundedY = y.coerceIn(0.0, (height - 1).toDouble())
        val left = floor(boundedX).toInt()
        val top = floor(boundedY).toInt()
        val right = min(left + 1, width - 1)
        val bottom = min(top + 1, height - 1)
        return mix(mix(source[top * width + left], source[top * width + right], (boundedX - left).toFloat()),
            mix(source[bottom * width + left], source[bottom * width + right], (boundedX - left).toFloat()), (boundedY - top).toFloat())
    }

    private fun flatten(color: Int, base: Int): Int = mix(base, color, (color ushr 24) / 255f)

    private fun mix(first: Int, second: Int, fraction: Float): Int {
        fun channel(shift: Int): Int {
            val from = first ushr shift and 255
            return (from + ((second ushr shift and 255) - from) * fraction).roundToInt().coerceIn(0, 255)
        }
        return rgb(channel(16), channel(8), channel(0))
    }

    private fun rgb(red: Int, green: Int, blue: Int): Int = 0xff000000.toInt() or (red shl 16) or (green shl 8) or blue

    private fun frame(request: PlaybackBackgroundRequest, image: ThemeBitmapLease): BackgroundFrameLease = BackgroundFrameLease(
        request.renderKey(), request.options.mode, request.snapshot.effectiveManifest.palette,
        request.snapshot.effectiveManifest.palette.background, image,
    )

    companion object {
        fun fallback(request: PlaybackBackgroundRequest, issue: PlaybackBackgroundIssue? = null): BackgroundFrameLease = BackgroundFrameLease(
            request.renderKey(), request.options.mode, request.snapshot.effectiveManifest.palette,
            if (request.options.mode == ThemeBackgroundMode.BLACK) 0xff000000.toInt() else request.snapshot.effectiveManifest.palette.background,
            issue = issue,
        )
    }
}

internal data class RenderSize(val width: Int, val height: Int)

internal fun renderSize(viewport: BackgroundViewport): RenderSize {
    val edge = max(viewport.widthPx, viewport.heightPx).toDouble()
    val scale = min(1.0, PlaybackBackgroundLimits.DERIVED_EDGE / edge)
    return RenderSize(max(1, (viewport.widthPx * scale).toInt()), max(1, (viewport.heightPx * scale).toInt()))
}

internal fun backgroundRenderIssue(error: Throwable): PlaybackBackgroundIssue = when (error) {
    is PlaybackBackgroundException -> error.issue
    is ThemeException -> when (error.issue) {
        ThemeIssue.BUSY -> PlaybackBackgroundIssue.BUSY
        ThemeIssue.LOW_MEMORY -> PlaybackBackgroundIssue.LOW_MEMORY
        ThemeIssue.IMAGE_TOO_LARGE -> PlaybackBackgroundIssue.IMAGE_TOO_LARGE
        ThemeIssue.PERMISSION_DENIED -> PlaybackBackgroundIssue.PERMISSION_DENIED
        ThemeIssue.SOURCE_UNAVAILABLE -> PlaybackBackgroundIssue.SOURCE_UNAVAILABLE
        ThemeIssue.REVISION_CONFLICT -> PlaybackBackgroundIssue.SETTINGS_CONFLICT
        ThemeIssue.RECOVERY_REQUIRED, ThemeIssue.CORRUPT_STORAGE -> PlaybackBackgroundIssue.RECOVERY_REQUIRED
        else -> PlaybackBackgroundIssue.INVALID_IMAGE
    }
    is ArtworkException -> when (error.issue) {
        ArtworkIssue.BUSY -> PlaybackBackgroundIssue.BUSY
        ArtworkIssue.LOW_MEMORY -> PlaybackBackgroundIssue.LOW_MEMORY
        ArtworkIssue.INPUT_TOO_LARGE -> PlaybackBackgroundIssue.IMAGE_TOO_LARGE
        else -> PlaybackBackgroundIssue.INVALID_IMAGE
    }
    is SourceReadException -> when (error.issue) {
        SourceReadIssue.BUSY -> PlaybackBackgroundIssue.BUSY
        SourceReadIssue.PERMISSION_DENIED, SourceReadIssue.PERMISSION_REVOKED -> PlaybackBackgroundIssue.PERMISSION_DENIED
        else -> if (error.cause is OutOfMemoryError) PlaybackBackgroundIssue.LOW_MEMORY else PlaybackBackgroundIssue.SOURCE_UNAVAILABLE
    }
    is OutOfMemoryError -> PlaybackBackgroundIssue.LOW_MEMORY
    else -> PlaybackBackgroundIssue.SOURCE_UNAVAILABLE
}
