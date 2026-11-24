package io.github.onlinemo.meloxwatch.ui.themes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkPriority
import io.github.onlinemo.meloxwatch.data.artwork.LocalArtworkRepository
import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.library.LibraryTrack
import io.github.onlinemo.meloxwatch.data.library.LocalReadDescriptor
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.themes.AppliedThemeResources
import io.github.onlinemo.meloxwatch.data.themes.BackgroundViewport
import io.github.onlinemo.meloxwatch.data.themes.LocalBackgroundImages
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundException
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundImageKey
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundIssue
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundOptions
import io.github.onlinemo.meloxwatch.data.themes.ThemeBackgroundMode
import io.github.onlinemo.meloxwatch.data.themes.ThemeBitmapLease
import io.github.onlinemo.meloxwatch.data.themes.ThemeBitmapOwner
import io.github.onlinemo.meloxwatch.data.themes.ThemeChoice
import io.github.onlinemo.meloxwatch.data.themes.ThemeSnapshot
import io.github.onlinemo.meloxwatch.data.themes.backgroundKey
import io.github.onlinemo.meloxwatch.model.ArtworkReadPath
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One descriptor/resolve task and one latest scene; no time polling or hidden artwork access. */
class PlaybackBackgroundViewModel(
    private val resolveRead: suspend (TrackKey, String?, Long?) -> LocalReadDescriptor,
    private val artworks: LocalArtworkRepository,
    appliedThemes: AppliedThemeResources,
    customImages: LocalBackgroundImages,
    private val io: LocalProviderIo,
    bitmaps: ThemeBitmapOwner,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val workScope = scope ?: viewModelScope
    private val lock = Any()
    private var closed = false
    private var visible = false
    private var visibilityOwner: Any? = null
    private val mutablePresentationOwner = MutableStateFlow<Any?>(null)
    val presentationOwner = mutablePresentationOwner.asStateFlow()
    private var presented = false
    private var latest: Scene? = null
    private var generation = 0L
    private var active: ResolveTask? = null
    private var completedCoverIntent: String? = null
    private var heldCover: HeldCover? = null
    private val mutableResolutionIssue = MutableStateFlow<PlaybackBackgroundIssue?>(null)
    val resolutionIssue = mutableResolutionIssue.asStateFlow()
    private val source = ThemeAndCustomBackgroundSource(appliedThemes, customImages, ::loadCover)
    private val controller = PlaybackBackgroundController(workScope, io, PlaybackBackgroundRenderer(io, source, bitmaps))
    val display = controller.state

    fun updateScene(
        owner: Any,
        snapshot: ThemeSnapshot,
        options: PlaybackBackgroundOptions,
        customKey: PlaybackBackgroundImageKey.Custom?,
        playingTrack: LibraryTrack?,
        artworkPath: ArtworkReadPath,
        artworkRevision: Long,
        viewport: BackgroundViewport,
    ): Boolean {
        val scene = Scene(snapshot, options, customKey, playingTrack, artworkPath, artworkRevision, viewport)
        var cancel: Job? = null
        synchronized(lock) {
            if (closed || !workScope.isActive || !visible || visibilityOwner !== owner) return false
            if (latest?.coverIntent() != scene.coverIntent()) {
                generation++
                cancel = active?.job
                heldCover = null
                completedCoverIntent = null
                mutableResolutionIssue.value = null
            }
            latest = scene
            presented = true
            // Controller transitions are memory-only and share this ordering with hide/owner changes.
            controller.update(request(scene))
            controller.setVisible(true)
        }
        cancel?.cancel()
        scheduleResolution()
        return true
    }

    /** Only the admitted surface can update or retire its scene. Hidden neighbors cannot claim it. */
    fun setVisible(owner: Any, value: Boolean): Boolean {
        val cancelled = synchronized(lock) {
            if (closed || !workScope.isActive) return false
            if (value) {
                if (visibilityOwner != null && visibilityOwner !== owner) return false
                visibilityOwner = owner
            } else {
                if (visibilityOwner !== owner) return false
                visibilityOwner = null
            }
            if (visible == value) return true
            visible = value
            presented = false
            generation++
            heldCover = null
            completedCoverIntent = null
            mutableResolutionIssue.value = null
            controller.setVisible(false)
            mutablePresentationOwner.value = visibilityOwner
            active?.job
        }
        cancelled?.cancel()
        // A resumed owner must supply the current scene before a retained request can run.
        // This avoids resolving an old hidden track between ownership and scene publication.
        return true
    }

    /** A collected old frame cannot be drawn by a new owner, even between StateFlow deliveries. */
    fun isPresented(owner: Any, candidate: PlaybackBackgroundDisplay, viewport: BackgroundViewport?): Boolean = synchronized(lock) {
        !closed && workScope.isActive && visible && presented && visibilityOwner === owner && viewport != null &&
            latest?.viewport == viewport && controller.state.value === candidate && candidate.visible &&
            candidate.request?.viewport == viewport
    }

    fun retry() {
        val cancelled = synchronized(lock) {
            if (closed || !workScope.isActive || !visible || !presented) return
            generation++
            completedCoverIntent = null
            heldCover = null
            mutableResolutionIssue.value = null
            latest?.let { controller.update(request(it)) }
            controller.retry()
            active?.job
        }
        cancelled?.cancel()
        scheduleResolution()
    }

    private fun scheduleResolution() {
        val job = synchronized(lock) {
            val scene = latest ?: return
            val intent = scene.coverIntent() ?: return
            if (closed || !workScope.isActive || !visible || !presented || active != null || completedCoverIntent == intent) return
            val ticket = ResolveTask(generation, intent, scene)
            val pending = workScope.launch(start = CoroutineStart.LAZY) { resolve(ticket) }
            ticket.job = pending
            active = ticket
            pending.invokeOnCompletion { failure ->
                synchronized(lock) {
                    if (active === ticket) {
                        active = null
                        if (failure != null && accepts(ticket)) {
                            completedCoverIntent = ticket.intent
                            mutableResolutionIssue.value = PlaybackBackgroundIssue.SOURCE_UNAVAILABLE
                        }
                    }
                }
                scheduleResolution()
            }
            pending
        }
        job.start()
    }

    private suspend fun resolve(ticket: ResolveTask) {
        try {
            val track = ticket.scene.track ?: return
            val location = track.location
            val descriptor = resolveRead(track.trackKey, location?.locationId, location?.contentRevision)
            currentCoroutineContext().ensureActive()
            val fingerprint = coverFingerprint(descriptor, ticket.scene.path, ticket.scene.artworkRevision)
            synchronized(lock) {
                if (!accepts(ticket) || ticket.job?.isActive != true) return
                heldCover = HeldCover(ticket.intent, fingerprint, descriptor, ticket.scene.path)
                completedCoverIntent = ticket.intent
                mutableResolutionIssue.value = null
                controller.update(request(checkNotNull(latest)))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            synchronized(lock) {
                if (accepts(ticket)) {
                    completedCoverIntent = ticket.intent
                    mutableResolutionIssue.value = when (error) {
                        is LibraryException -> when (error.issue) {
                            LibraryIssue.BUSY -> PlaybackBackgroundIssue.BUSY
                            LibraryIssue.PERMISSION_REVOKED -> PlaybackBackgroundIssue.PERMISSION_DENIED
                            else -> PlaybackBackgroundIssue.SOURCE_UNAVAILABLE
                        }
                        else -> backgroundRenderIssue(error)
                    }
                }
            }
        } catch (_: OutOfMemoryError) {
            synchronized(lock) {
                if (accepts(ticket)) {
                    completedCoverIntent = ticket.intent
                    mutableResolutionIssue.value = PlaybackBackgroundIssue.LOW_MEMORY
                }
            }
        }
    }

    private fun accepts(ticket: ResolveTask): Boolean = !closed && workScope.isActive && visible && presented &&
        generation == ticket.generation && latest?.coverIntent() == ticket.intent

    private suspend fun loadCover(key: PlaybackBackgroundImageKey.Cover, targetPx: Int): ThemeBitmapLease? {
        val cover = synchronized(lock) {
            heldCover?.takeIf { !closed && visible && it.fingerprint == key.fingerprint && latest?.coverIntent() == it.intent }
        } ?: throw PlaybackBackgroundException(PlaybackBackgroundIssue.SOURCE_UNAVAILABLE)
        var lease: ThemeBitmapLease? = null
        try {
            lease = artworks.acquireOwned(cover.descriptor, cover.path, targetPx, ArtworkPriority.CURRENT)
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (closed || !visible || heldCover !== cover) throw CancellationException("Current cover changed")
            }
            val result = lease
            lease = null
            return result
        } finally { lease?.close() }
    }

    private fun request(scene: Scene): PlaybackBackgroundRequest {
        val effective = scene.effectiveOptions()
        val image = when (effective.mode) {
            ThemeBackgroundMode.COVER_BLUR, ThemeBackgroundMode.FLUID -> heldCover?.takeIf { it.intent == scene.coverIntent() }
                ?.let { PlaybackBackgroundImageKey.Cover(it.fingerprint) }
            ThemeBackgroundMode.CUSTOM_IMAGE -> if (scene.options.mode == ThemeBackgroundMode.DEFAULT) {
                scene.snapshot.effectiveManifest.background.imageResourceId?.takeIf { scene.snapshot.effective is ThemeChoice.Installed }
                    ?.let { PlaybackBackgroundImageKey.Theme(scene.snapshot, it) }
            } else scene.customKey
            else -> null
        }
        return PlaybackBackgroundRequest(scene.snapshot, effective, image, scene.viewport)
    }

    override fun onCleared() {
        val cancelled = synchronized(lock) {
            closed = true
            visible = false
            visibilityOwner = null
            mutablePresentationOwner.value = null
            presented = false
            generation++
            heldCover = null
            latest = null
            controller.close()
            active?.job
        }
        cancelled?.cancel()
    }

    private data class HeldCover(val intent: String, val fingerprint: String, val descriptor: LocalReadDescriptor, val path: ArtworkReadPath)
    private class ResolveTask(val generation: Long, val intent: String, val scene: Scene) { var job: Job? = null }
    private data class Scene(
        val snapshot: ThemeSnapshot,
        val options: PlaybackBackgroundOptions,
        val customKey: PlaybackBackgroundImageKey.Custom?,
        val track: LibraryTrack?,
        val path: ArtworkReadPath,
        val artworkRevision: Long,
        val viewport: BackgroundViewport,
    ) {
        fun effectiveOptions(): PlaybackBackgroundOptions {
            if (options.mode != ThemeBackgroundMode.DEFAULT) return options
            val background = snapshot.effectiveManifest.background
            return options.copy(mode = background.mode, blurLevel = background.blurLevel, darkMaskPercent = background.darkMaskPercent)
        }
        fun coverIntent(): String? {
            if (effectiveOptions().mode !in setOf(ThemeBackgroundMode.COVER_BLUR, ThemeBackgroundMode.FLUID)) return null
            val current = track ?: return null
            return backgroundKey("current-cover-intent-v1", current.trackKey.providerId, current.trackKey.trackId,
                current.location?.locationId.orEmpty(), current.location?.contentRevision.toString(), path.name, artworkRevision.toString())
        }
    }
}

internal fun coverFingerprint(descriptor: LocalReadDescriptor, path: ArtworkReadPath, refreshRevision: Long): String = backgroundKey(
    "current-cover-v1", descriptor.trackKey.providerId, descriptor.trackKey.trackId, descriptor.location.locationId,
    descriptor.location.contentRevision.toString(), descriptor.aliasId, descriptor.source.spec.sourceId,
    descriptor.source.authEpoch.toString(), descriptor.source.revision.toString(), descriptor.objectId,
    descriptor.providerContentToken.orEmpty(), descriptor.verifiedContentToken.orEmpty(), path.name, refreshRevision.toString(),
    // Unknown content gets a new access identity on foreground resolution, without an idle timer.
    if (descriptor.providerContentToken == null && descriptor.verifiedContentToken == null) UUID.randomUUID().toString() else "known",
)
