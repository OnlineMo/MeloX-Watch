package io.github.onlinemo.meloxwatch.ui.online

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.onlinemo.meloxwatch.MeloXApplication
import io.github.onlinemo.meloxwatch.data.auth.SessionValidity
import io.github.onlinemo.meloxwatch.data.online.loadOpenedMetingArtwork
import io.github.onlinemo.meloxwatch.data.online.openedMetingArtworkKey
import io.github.onlinemo.meloxwatch.model.OnlineCatalogEntry
import kotlinx.coroutines.CancellationException

/** One visible current-item lookup. Backend position ticks and hidden pager neighbors do not fetch artwork. */
@Composable
internal fun OnlineNowPlayingArtwork(mediaId: String, visible: Boolean, edge: Dp) {
    if (!visible) {
        WatchOnlineArtwork(null, edge, false) { null }
        return
    }
    val app = LocalContext.current.applicationContext as MeloXApplication
    val accountRevision by app.onlineAccountChanges.revision.collectAsStateWithLifecycle()
    val opened by app.runtimePlaybackResources.openedResource.collectAsStateWithLifecycle()
    val metingOwner = app.existingMetingRuntime()
    val metingConfiguration = metingOwner?.configuration?.snapshot?.collectAsStateWithLifecycle()?.value
    val custom = opened?.takeIf { it.queueEntryId == mediaId && it.resolverId == "meting-custom" && it.isCurrentAccount() }
    val customKey = remember(custom, metingConfiguration?.generation, metingConfiguration?.changing,
        metingConfiguration?.enabled, accountRevision) {
        custom?.let { openedMetingArtworkKey(app.runtimePlaybackResources, it) }
    }
    if (custom != null && customKey != null && metingOwner != null) {
        WatchBoundedArtwork(customKey, edge, true) { pixels ->
            loadOpenedMetingArtwork(app.runtimePlaybackResources, metingOwner, app.artworks, custom, pixels)
        }
        return
    }
    val feature = app.existingOnlineCatalog()
    val account = feature?.sessions?.snapshot?.collectAsStateWithLifecycle()?.value
    var binding by remember(mediaId) { mutableStateOf<NativeArtworkBinding?>(null) }
    LaunchedEffect(mediaId, accountRevision, feature, account?.generation) {
        binding = null
        try {
            val snapshot = app.queues.snapshot() ?: return@LaunchedEffect
            if (snapshot.currentEntryId != mediaId) return@LaunchedEffect
            val current = app.queues.window(snapshot.generationId, mediaId).current
            val accountId = current.accountId ?: return@LaunchedEffect
            if (current.trackKey.providerId != "netease") return@LaunchedEffect
            val catalog = feature ?: return@LaunchedEffect
            val before = catalog.sessions.snapshot.value
            if (before.validity != SessionValidity.VERIFIED || before.account?.accountId != accountId) return@LaunchedEffect
            val metadata = catalog.catalog.presentation(accountId, current.trackKey.trackId) ?: return@LaunchedEffect
            val stillCurrent = app.queues.snapshot()
            val after = catalog.sessions.snapshot.value
            if (stillCurrent?.generationId == snapshot.generationId && stillCurrent.currentEntryId == mediaId &&
                after.generation == before.generation && after.account == before.account && after.validity == SessionValidity.VERIFIED) {
                binding = NativeArtworkBinding(metadata, accountId, before.generation)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { }
    }
    val selected = binding?.takeIf { account?.validity == SessionValidity.VERIFIED &&
        account.generation == it.accountGeneration && account.account?.accountId == it.accountId }?.row
    // A paused restore may display an existing image without starting a fresh provider request.
    val mayFetch = opened?.queueEntryId == mediaId && opened?.isCurrentAccount() == true
    WatchBoundedArtwork(selected?.let { Triple(it.id, it.artworkKey, mayFetch) }, edge, true) { pixels ->
        val feature = app.existingOnlineCatalog()
        if (selected == null || feature == null) null else {
            val key = selected.artworkKey
            val cached = key?.let { feature.artworks.load(it, pixels, allowNetwork = mayFetch) }
            cached ?: if (!mayFetch || key != null && feature.artworks.hasTarget(key)) null else {
                feature.catalog.artworkKey(selected.id)?.let { feature.artworks.load(it, pixels, allowNetwork = true) }
            }
        }
    }
}

private class NativeArtworkBinding(val row: OnlineCatalogEntry, val accountId: String, val accountGeneration: Long)
