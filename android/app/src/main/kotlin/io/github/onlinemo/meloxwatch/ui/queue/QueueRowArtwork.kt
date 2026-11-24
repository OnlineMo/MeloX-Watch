package io.github.onlinemo.meloxwatch.ui.queue

import androidx.annotation.MainThread
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.onlinemo.meloxwatch.MeloXApplication
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkLease
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkLimits
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkPriority
import io.github.onlinemo.meloxwatch.model.ArtworkReadPath
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.ui.themes.LocalWatchThemeColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One cell per exact display request. Retiring a row also rejects a provider's late result. */
internal class QueueArtworkPresentation {
    private var retired = false
    var lease by mutableStateOf<ArtworkLease?>(null)
        private set

    @MainThread
    fun publish(candidate: ArtworkLease): Boolean {
        if (retired) {
            candidate.close()
            return false
        }
        val previous = lease
        lease = candidate
        if (previous !== candidate) previous?.close()
        return true
    }

    @MainThread
    fun release(candidate: ArtworkLease) {
        if (lease === candidate) lease = null
        candidate.close()
    }

    @MainThread
    fun retire() {
        retired = true
        val previous = lease
        lease = null
        previous?.close()
    }
}

@Composable
internal fun QueueRowArtwork(
    entry: QueueEntry,
    rowsIdentity: Any?,
    generationId: String,
    visible: Boolean,
    path: ArtworkReadPath,
    size: Dp,
) {
    val app = LocalContext.current.applicationContext as MeloXApplication
    val owner = LocalLifecycleOwner.current
    val density = LocalDensity.current
    val targetPx = with(density) { size.roundToPx() }.coerceIn(1, ArtworkLimits.OUTPUT_EDGE)
    val location = entry.location
    val canRead = visible && rowsIdentity != null && entry.trackKey.providerId == TrackKey.LOCAL_PROVIDER && location != null
    val presentation = remember(app, owner, rowsIdentity, generationId, entry.queueEntryId, entry.trackKey,
        location, path, targetPx, canRead) { QueueArtworkPresentation() }
    DisposableEffect(presentation) {
        onDispose { presentation.retire() }
    }
    LaunchedEffect(presentation, canRead) {
        if (!canRead) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var held: ArtworkLease? = null
            try {
                // Cached artist text is not authorization: resolve the exact location/content again for images.
                val selected = checkNotNull(location)
                val descriptor = app.library.resolveLocalRead(entry.trackKey, selected.locationId, selected.contentRevision)
                currentCoroutineContext().ensureActive()
                held = app.artworks.acquire(descriptor, path, targetPx, ArtworkPriority.VISIBLE)
                currentCoroutineContext().ensureActive()
                val acquired = held
                if (acquired != null && presentation.publish(acquired)) awaitCancellation()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Missing artwork, denied access and bounded-reader failures keep the neutral placeholder.
            } finally {
                held?.let(presentation::release)
            }
        }
    }
    val colors = LocalWatchThemeColors.current
    val modifier = Modifier.size(size).clip(RoundedCornerShape(4.dp)).background(colors.raised)
    val lease = presentation.lease
    if (lease != null) {
        Image(lease.bitmap.asImageBitmap(), contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    } else {
        Canvas(modifier) {
            val x = this.size.width * 0.58f
            val top = this.size.height * 0.26f
            val bottom = this.size.height * 0.65f
            val stroke = 1.5.dp.toPx()
            drawLine(colors.secondary, Offset(x, top), Offset(x, bottom), strokeWidth = stroke)
            drawLine(colors.secondary, Offset(x, top), Offset(this.size.width * 0.76f, this.size.height * 0.22f), strokeWidth = stroke)
            drawCircle(colors.secondary, this.size.minDimension * 0.09f, Offset(x - this.size.width * 0.08f, bottom))
        }
    }
}
