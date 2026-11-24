package io.github.onlinemo.meloxwatch.ui.themes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.onlinemo.meloxwatch.data.library.LibraryTrack
import io.github.onlinemo.meloxwatch.data.themes.BackgroundRoundClip
import io.github.onlinemo.meloxwatch.data.themes.BackgroundViewport
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundImageKey
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundOptions
import io.github.onlinemo.meloxwatch.data.themes.ThemeSnapshot
import io.github.onlinemo.meloxwatch.model.ArtworkReadPath
import io.github.onlinemo.meloxwatch.ui.ProbePageRuntime

/** The actual Page/Window identity owns work; this surface has no independent lifecycle owner. */
@Composable
internal fun PlaybackBackgroundSurface(
    model: PlaybackBackgroundViewModel,
    snapshot: ThemeSnapshot,
    options: PlaybackBackgroundOptions,
    customKey: PlaybackBackgroundImageKey.Custom?,
    playingTrack: LibraryTrack?,
    artworkPath: ArtworkReadPath,
    artworkRevision: Long,
    pageBinding: ProbePageRuntime.PageBinding?,
    physicalRoundClipInWindow: BackgroundRoundClip?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val actualRound = LocalConfiguration.current.isScreenRound
    var position by remember { mutableStateOf<PlaybackSurfacePosition?>(null) }
    val composedPosition = position
    val viewport = composedPosition?.let { playbackBackgroundViewport(it.width, it.height, it.originX, it.originY,
        actualRound, physicalRoundClipInWindow) }
    var surfaceBinding by remember(model, pageBinding) {
        mutableStateOf<ProbePageRuntime.BackgroundBinding?>(null)
    }
    DisposableEffect(model, pageBinding, viewport) {
        // Model identity is checked before retiring any existing slot or claiming resource work.
        val exact = if (viewport != null) pageBinding?.bindBackground(model) else null
        surfaceBinding = exact
        onDispose {
            exact?.close()
            if (surfaceBinding === exact) surfaceBinding = null
        }
    }
    val exact = surfaceBinding
    LaunchedEffect(model, exact, snapshot, options, customKey, playingTrack, artworkPath, artworkRevision, viewport) {
        val current = viewport
        // Queued work reads the real Page/Window/media/selection gates again at admission.
        if (exact?.isCurrent != true || current == null || position != composedPosition) return@LaunchedEffect
        exact.updateScene(ProbePageRuntime.BackgroundScene(snapshot, options, customKey,
            playingTrack, artworkPath, artworkRevision, current))
    }
    val base = remember(snapshot, options, viewport) {
        PlaybackBackgroundDisplay(request = PlaybackBackgroundRequest(snapshot, options, null,
            viewport ?: BackgroundViewport(1, 1)))
    }
    val active = exact?.isCurrent == true && position == composedPosition && viewport != null
    // Hidden or rejected pages do not keep a background StateFlow observer alive.
    val shown = if (active) {
        val display by model.display.collectAsStateWithLifecycle()
        if (exact?.isPresented(display, viewport) == true) display else base
    } else base
    Box(modifier.fillMaxSize().onGloballyPositioned { coordinates ->
        val size = coordinates.size
        val origin = coordinates.positionInWindow()
        val measured = PlaybackSurfacePosition(size.width, size.height, origin.x, origin.y)
        if (measured != position) position = measured
    }) {
        WatchPlaybackBackground(shown, Modifier.matchParentSize().drawWithContent {
            // A synchronous producer can revoke this exact binding after composition and before
            // draw. Decoration drops to the static base while text and controls remain visible.
            if (shown === base || position == composedPosition && exact?.isPresented(shown, viewport) == true) drawContent()
            else drawWatchPlaybackBackground(base)
        }, content = {})
        content()
    }
}

private data class PlaybackSurfacePosition(val width: Int, val height: Int, val originX: Float, val originY: Float)

/** The parent supplies the unshrunk physical circle in window coordinates, independent of IME. */
internal fun playbackBackgroundViewport(
    widthPx: Int,
    heightPx: Int,
    originX: Float,
    originY: Float,
    actualRound: Boolean,
    physicalRoundClipInWindow: BackgroundRoundClip?,
): BackgroundViewport? {
    if (widthPx <= 0 || heightPx <= 0 || !originX.isFinite() || !originY.isFinite()) return null
    if (actualRound && physicalRoundClipInWindow == null) return null
    val local = physicalRoundClipInWindow?.takeIf { actualRound }?.let {
        val centerX = it.centerX - originX
        val centerY = it.centerY - originY
        if (!centerX.isFinite() || !centerY.isFinite()) return null
        BackgroundRoundClip(centerX, centerY, it.radius)
    }
    return BackgroundViewport(widthPx, heightPx, local)
}
