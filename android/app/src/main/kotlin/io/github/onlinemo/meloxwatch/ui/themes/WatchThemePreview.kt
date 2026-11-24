package io.github.onlinemo.meloxwatch.ui.themes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.themes.LocalThemeRepository
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundLimits
import io.github.onlinemo.meloxwatch.data.themes.ThemeBackgroundMode
import io.github.onlinemo.meloxwatch.data.themes.ThemeBitmapLease
import io.github.onlinemo.meloxwatch.data.themes.ThemeBitmapOwner
import io.github.onlinemo.meloxwatch.data.themes.ThemeException
import io.github.onlinemo.meloxwatch.data.themes.ThemeIssue
import io.github.onlinemo.meloxwatch.data.themes.ThemePreview
import io.github.onlinemo.meloxwatch.ui.fonts.LyricsFontPreviewText
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsDisplayOptions
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsFonts
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsTextBlock
import io.github.onlinemo.meloxwatch.ui.watch.WatchActionRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchText
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** These exact samples also go to Root's sole two-role coverage/face loader. */
@Composable
fun watchThemePreviewText(): LyricsFontPreviewText = LyricsFontPreviewText(
    original = stringResource(R.string.appearance_preview_original),
    translation = stringResource(R.string.appearance_preview_translation),
)

/**
 * Preview data remains a ThemePreview. The only image lease comes from the injected shared
 * repository/owner; fonts are Root's current LyricsFonts, with no file/cache work in composition.
 * onRetireFonts is a stable, idempotent, memory-only callback for this preview font binding.
 * onCancelRetiredFonts drains that same runtime's captured retired work outside the editor lock;
 * it must never cancel a current replacement binding or retire the still-applicable theme stage.
 * Root places this sample in its real round-safe scrolling host and passes sole Window visibility.
 */
@Composable
fun WatchThemePreview(
    state: AppearanceEditorState,
    model: AppearanceViewModel,
    themes: LocalThemeRepository,
    io: LocalProviderIo,
    bitmaps: ThemeBitmapOwner,
    fonts: LyricsFonts,
    text: LyricsFontPreviewText,
    visible: Boolean,
    windowVisibleNow: () -> Boolean,
    onRetireFonts: () -> Unit,
    onCancelRetiredFonts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val preview = state.themePreview
    if (preview == null) {
        WatchText(stringResource(R.string.appearance_preview_choose_theme))
        return
    }
    val enabled = visible && state.editable && state.previewing
    val currentWindowGate by rememberUpdatedState(windowVisibleNow)
    val imageState = remember(preview.token) { mutableStateOf<ThemeBitmapLease?>(null) }
    val imageJob = remember(preview.token) { AtomicReference<Job?>(null) }
    var binding by remember(preview.token) { mutableStateOf<AutoCloseable?>(null) }
    var loading by remember(preview.token) { mutableStateOf(false) }
    var issue by remember(preview.token) { mutableStateOf<ThemeIssue?>(null) }
    var retry by remember(preview.token) { mutableIntStateOf(0) }
    var measured by remember(preview.token) { mutableStateOf(IntSize.Zero) }

    DisposableEffect(model, preview.token, enabled, onRetireFonts, onCancelRetiredFonts) {
        val retirement = ThemePreviewRetirement(imageJob,
            dropMemory = {
                val image = imageState.value
                imageState.value = null
                image?.close()
                loading = false
                onRetireFonts()
            }, cancelRetiredFonts = onCancelRetiredFonts)
        val admitted = if (enabled && currentWindowGate())
            model.bindThemePreviewPresentation(preview.token,
                releaseMemory = retirement::releaseMemory,
                cancelRetiredWork = retirement::cancelRetiredWork) else null
        binding = admitted
        if (admitted == null) {
            retirement.releaseMemory()
            retirement.cancelRetiredWork()
        }
        onDispose {
            if (binding === admitted) binding = null
            // An admitted binding owns both phases; a stale disposer cannot retake a new image Job.
            if (admitted != null) admitted.close()
        }
    }

    val targetPx = max(measured.width, measured.height).coerceAtMost(PlaybackBackgroundLimits.DERIVED_EDGE)
    val imageId = preview.manifest.background.imageResourceId
    LaunchedEffect(model, themes, io, bitmaps, binding, imageId, targetPx, retry) {
        val admittedBinding = binding ?: return@LaunchedEffect
        if (imageId == null || targetPx <= 0) return@LaunchedEffect
        val job = currentCoroutineContext()[Job] ?: return@LaunchedEffect
        var admitted = false
        var previous: ThemeBitmapLease? = null
        model.updateThemePreviewPresentation(admittedBinding) {
            if (currentWindowGate()) {
                previous = imageState.value
                imageState.value = null
                imageJob.set(job)
                loading = true
                issue = null
                admitted = true
            }
        }
        previous?.close()
        if (!admitted) return@LaunchedEffect
        var delivered: ThemeBitmapLease? = null
        try {
            val loaded = loadThemePreviewImage(themes, io, preview, imageId, targetPx)
            delivered = loaded
            currentCoroutineContext().ensureActive()
            if (!bitmaps.owns(loaded)) throw ThemeException(ThemeIssue.INVALID_INPUT)
            model.updateThemePreviewPresentation(admittedBinding) {
                if (currentWindowGate() && imageJob.get() === job) {
                    imageState.value = loaded
                    loading = false
                    delivered = null
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ThemeException) {
            model.updateThemePreviewPresentation(admittedBinding) {
                if (imageJob.get() === job) issue = error.issue
            }
        } catch (_: OutOfMemoryError) {
            model.updateThemePreviewPresentation(admittedBinding) {
                if (imageJob.get() === job) issue = ThemeIssue.LOW_MEMORY
            }
        } catch (_: Exception) {
            model.updateThemePreviewPresentation(admittedBinding) {
                if (imageJob.get() === job) issue = ThemeIssue.SOURCE_UNAVAILABLE
            }
        } finally {
            delivered?.close()
            if (imageJob.compareAndSet(job, null)) model.updateThemePreviewPresentation(admittedBinding) { loading = false }
        }
    }

    // Only the sample's two lyric roles use custom faces. Labels/recovery keep system glyphs.
    WatchThemeScope(preview.manifest.palette, state.draftAppearance) {
        val colors = LocalWatchThemeColors.current
        Column(modifier.fillMaxWidth().background(colors.background).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WatchText(stringResource(R.string.appearance_preview_heading, preview.manifest.displayName), primary = true)
            Canvas(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(12.dp))
                .onSizeChanged { measured = it }.clearAndSetSemantics {}) {
                val base = if (preview.manifest.background.mode == ThemeBackgroundMode.BLACK) Color.Black else colors.background
                drawRect(base)
                if (!currentWindowGate() || !model.isThemePreviewPresented(binding) || size.width <= 0f || size.height <= 0f) return@Canvas
                val bitmap = imageState.value?.bitmap ?: return@Canvas
                if (bitmap.isRecycled) return@Canvas
                // Crop the bounded source, rather than drawing a huge destination for a thin image.
                val scale = max(size.width / bitmap.width, size.height / bitmap.height)
                val sourceWidth = (size.width / scale).roundToInt().coerceIn(1, bitmap.width)
                val sourceHeight = (size.height / scale).roundToInt().coerceIn(1, bitmap.height)
                drawImage(bitmap.asImageBitmap(),
                    srcOffset = IntOffset((bitmap.width - sourceWidth) / 2, (bitmap.height - sourceHeight) / 2),
                    srcSize = IntSize(sourceWidth, sourceHeight),
                    dstSize = IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1)),
                    filterQuality = FilterQuality.Low)
                val mask = preview.manifest.background.darkMaskPercent
                if (mask != 0) drawRect(Color.Black.copy(alpha = mask / 100f))
            }
            if (imageId == null) WatchText(stringResource(R.string.appearance_preview_no_picture))
            if (loading) WatchText(stringResource(R.string.appearance_preview_picture_loading))
            issue?.let {
                WatchText(stringResource(themeIssueMessage(it)), error = true)
                WatchActionRow(stringResource(R.string.appearance_preview_retry_picture), enabled && !loading) { retry++ }
            }
            WatchText(stringResource(R.string.appearance_preview_sample), primary = true)
            val sampleFonts = if (enabled && currentWindowGate() && model.isThemePreviewPresented(binding)) fonts else LyricsFonts()
            LyricsTextBlock(text.original, text.translation, active = true, wordRange = null,
                options = LyricsDisplayOptions(), fonts = sampleFonts,
                currentLabel = stringResource(R.string.appearance_preview_current),
                actionLabel = null, onPreview = null, visible = enabled,
                modifier = Modifier.fillMaxWidth())
            WatchText(stringResource(R.string.appearance_preview_scope))
        }
    }
}

/**
 * One binding captures its precise old Job during memory retirement, before a same-token
 * successor can install another Job in the shared image cell. No provider work runs while
 * either this memory monitor or the outer editor monitor is held. Fonts remain Root-owned.
 */
internal class ThemePreviewRetirement(
    private val imageJob: AtomicReference<Job?>,
    private val dropMemory: () -> Unit,
    private val cancelRetiredFonts: () -> Unit,
) {
    private val lock = Any()
    private var retired = false
    private var cancelled = false
    private var retiredImageJob: Job? = null

    fun releaseMemory() = synchronized(lock) {
        if (retired) return
        retired = true
        retiredImageJob = imageJob.getAndSet(null)
        dropMemory()
    }

    fun cancelRetiredWork() {
        val captured = synchronized(lock) {
            if (!retired || cancelled) return
            cancelled = true
            val previous = retiredImageJob
            retiredImageJob = null
            previous
        }
        try { captured?.cancel() }
        finally { cancelRetiredFonts() }
    }
}

/** One shared channel wait/one retry; a failed image does not start an idle retry loop. */
private suspend fun loadThemePreviewImage(
    themes: LocalThemeRepository,
    io: LocalProviderIo,
    preview: ThemePreview,
    resourceId: String,
    targetPx: Int,
): ThemeBitmapLease {
    try { return themes.loadOwnedPreviewImage(preview.token, resourceId, targetPx) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (error: ThemeException) {
        if (error.issue != ThemeIssue.BUSY) throw error
        val idle = withTimeoutOrNull(3_000L) { io.awaitIdle(); true } ?: false
        currentCoroutineContext().ensureActive()
        if (!idle) throw error
    }
    return themes.loadOwnedPreviewImage(preview.token, resourceId, targetPx)
}
