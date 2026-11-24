package io.github.onlinemo.meloxwatch.data.online

import io.github.onlinemo.meloxwatch.data.artwork.LocalArtworkRepository
import io.github.onlinemo.meloxwatch.data.meting.MetingRuntimeOwner
import io.github.onlinemo.meloxwatch.data.themes.ThemeBitmapLease
import io.github.onlinemo.meloxwatch.model.onlineOpaqueHash
import io.github.onlinemo.meloxwatch.playback.resources.OpenedPlaybackResourceMetadata
import io.github.onlinemo.meloxwatch.playback.resources.PlaybackAssociatedKind
import io.github.onlinemo.meloxwatch.playback.resources.RuntimePlaybackResources
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A view receives an opaque identity; the actual associated URL remains inside its resource owner. */
internal fun openedMetingArtworkKey(resources: RuntimePlaybackResources, opened: OpenedPlaybackResourceMetadata): String? {
    if (opened.resolverId != "meting-custom" || opened.providerId != "netease" || !opened.isCurrentAccount()) return null
    val retained = try { resources.retainOpened(opened.queueEntryId) } catch (_: Exception) { null } ?: return null
    return retained.use { resource ->
        val state = resource.online ?: return@use null
        if (!opened.belongsTo(state) || resources.openedResource.value?.belongsTo(state) != true ||
            state.associated?.target(PlaybackAssociatedKind.ARTWORK)?.serviceContext == null) return@use null
        onlineOpaqueHash(state.key)
    }
}

/** Cache and decode use the existing global image budget; a changed resource cannot paint an old result. */
internal suspend fun loadOpenedMetingArtwork(
    resources: RuntimePlaybackResources,
    owner: MetingRuntimeOwner,
    images: LocalArtworkRepository,
    opened: OpenedPlaybackResourceMetadata,
    targetPx: Int,
): ThemeBitmapLease? {
    require(targetPx in 1..512)
    val key = openedMetingArtworkKey(resources, opened) ?: return null
    val derived = onlineOpaqueHash("meting-artwork:$key:$targetPx")
    var pending: ThemeBitmapLease? = null
    try {
        pending = images.derived(derived, targetPx)
        if (pending == null) {
            val bytes = owner.readOpenedAssociated(opened.queueEntryId, PlaybackAssociatedKind.ARTWORK) ?: return null
            currentCoroutineContext().ensureActive()
            if (openedMetingArtworkKey(resources, opened) != key) return null
            pending = images.derived(derived, targetPx, bytes)
        }
        currentCoroutineContext().ensureActive()
        if (openedMetingArtworkKey(resources, opened) != key) return null
        val ready = pending
        pending = null
        return ready
    } finally { pending?.close() }
}
