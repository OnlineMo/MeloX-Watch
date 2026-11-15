package io.github.onlinemo.meloxwatch.ui.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.onlinemo.meloxwatch.MeloXApplication
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkLease
import io.github.onlinemo.meloxwatch.data.artwork.ArtworkRefreshPhase
import io.github.onlinemo.meloxwatch.data.library.LibraryAvailability
import io.github.onlinemo.meloxwatch.data.library.LibraryGroupKind
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.library.LibraryTrack
import io.github.onlinemo.meloxwatch.model.ArtworkReadPath
import io.github.onlinemo.meloxwatch.model.LibraryLayout
import io.github.onlinemo.meloxwatch.model.LibrarySortField
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation

@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.libraryContent(
    state: LibraryUiState,
    model: LibraryViewModel,
    sources: SourcesUiState,
    sourceModel: SourcesViewModel,
    commandPending: Boolean,
    onPlay: (String, TrackKey?) -> Unit,
    onPlayPreview: (QueueEntry) -> Unit,
    onChooseFile: () -> Unit,
    onChooseTree: () -> Unit,
    onMediaStore: () -> Unit,
    listening: ListeningUiState,
    listeningModel: ListeningViewModel,
    onPermissionSettings: () -> Unit,
) {
    item(key = "library:navigation") { LibraryNavigation(state, model) }
    if (state.screen == LibraryScreen.SOURCES) {
        sourcesContent(sources, sourceModel, commandPending, onPlayPreview, onChooseFile, onChooseTree, onMediaStore, onPermissionSettings)
        return
    }
    if (state.screen in setOf(LibraryScreen.HISTORY, LibraryScreen.STATISTICS)) {
        listeningContent(state.screen, listening, listeningModel, model::openTrackFromHistory)
        return
    }
    item(key = "library:state") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.loading) LibraryText(stringResource(R.string.library_loading))
            if (state.showingPreviousData) LibraryText(stringResource(R.string.library_previous_data))
            state.issue?.let { LibraryText(stringResource(libraryIssueText(it)), error = true) }
            if (state.importRecoveryIssue != null) {
                LibraryText(stringResource(R.string.library_recover_import_required), error = true)
                LibraryButton(stringResource(R.string.library_sources)) { model.show(LibraryScreen.SOURCES) }
            }
            state.overview?.let { overview ->
                LibraryText(stringResource(R.string.library_overview, overview.visibleTracks, overview.totalTracks))
            }
            if (state.hasNewData) LibraryText(stringResource(R.string.library_new_data))
            LibraryButton(stringResource(R.string.library_reload), !state.loading, model::reload)
        }
    }
    if (state.screen !in setOf(LibraryScreen.ALBUMS, LibraryScreen.ARTISTS)) {
        stickyHeader(key = "library:refresh-action") {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                LibraryButton(stringResource(R.string.library_refresh_visible_artwork, state.visibleArtworkCount),
                    !state.artworkPreparing && state.visibleArtworkCount in 1..32 && !state.loading, model::prepareArtworkRefresh)
            }
        }
        item(key = "library:refresh-artwork") { ArtworkRefreshControls(state, model) }
    }
    if (state.screen == LibraryScreen.DETAIL) {
        detailContent(state, sourceModel, commandPending, onPlay)
        return
    }
    if (state.screen == LibraryScreen.ALBUMS || state.screen == LibraryScreen.ARTISTS) {
        val kind = if (state.screen == LibraryScreen.ALBUMS) LibraryGroupKind.ALBUM else LibraryGroupKind.ARTIST
        items(state.groupPage?.groups.orEmpty(), key = { "group:${it.key}" }) { group ->
            LibraryButton(
                stringResource(R.string.library_group_count, group.title ?: stringResource(R.string.library_unknown), group.trackCount),
                !state.loading && !state.showingPreviousData,
            ) { model.openGroup(group, kind) }
            group.subtitle?.let { LibraryText(it) }
        }
        item { LibraryButton(stringResource(R.string.library_next_page), !state.loading && state.groupPage?.nextKey != null, model::nextGroups) }
        return
    }
    if (state.screen == LibraryScreen.FOLDERS) {
        state.folder?.let { folder ->
            item {
                LibraryText(folder.name ?: stringResource(R.string.library_unnamed_folder), primary = true)
                LibraryButton(stringResource(R.string.library_parent_folder), !state.loading, model::upFolder)
                LibraryButton(stringResource(R.string.library_exclude_folder), !sources.working) { sourceModel.excludeFolder(folder) }
            }
        }
        items(state.folderPage?.folders.orEmpty(), key = { "folder:${it.nodeId}" }) { folder ->
            LibraryButton(folder.name ?: stringResource(R.string.library_unnamed_folder), !state.loading) { model.openFolder(folder) }
        }
        item { LibraryButton(stringResource(R.string.library_more_folders), !state.loading && state.folderPage?.nextNodeId != null, model::nextFolders) }
    }
    val page = state.page
    if (page != null) {
        item(key = "library:play-all") {
            LibraryButton(stringResource(R.string.library_play_all, page.view.totalCount), state.canPlay && !commandPending) {
                onPlay(page.view.viewId, null)
            }
            LibraryButton(stringResource(R.string.library_sort), state.preferencesWritable && !state.loading, model::beginSort)
        }
        state.sortEdit?.let { item(key = "library:sort-editor") { SortEditor(state, model) } }
        if (state.sections.isNotEmpty()) item(key = "library:index") { Sections(state, model) }
        if (page.tracks.isEmpty() && !state.loading && state.issue == null) item { LibraryText(stringResource(R.string.library_empty)) }
        if (state.layout == LibraryLayout.GRID) {
            items(page.tracks.chunked(2), key = { "pair:${it.first().trackKey.trackId}" }) { pair ->
                TrackPair(pair, state, model, pair.first() == page.tracks.firstOrNull())
            }
        } else {
            items(page.tracks, key = { "track:${it.trackKey.providerId}:${it.trackKey.trackId}" }) { track ->
                TrackCard(track, state.preferences.artworkReadPath, state.canPlay, artworkRevision = state.artworkRevision) { model.openDetail(track) }
            }
        }
        item(key = "library:page-controls") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (page.tracks.isNotEmpty()) LibraryText(stringResource(
                    R.string.library_page_range, state.pageStart + 1L, state.pageStart + page.tracks.size, page.view.totalCount,
                ))
                LibraryButton(stringResource(R.string.library_previous_page), state.hasPrevious, model::previousPage)
                LibraryButton(stringResource(R.string.library_next_page), state.hasNext, model::nextPage)
            }
        }
    }
}

@Composable
internal fun LibraryNavigation(state: LibraryUiState, model: LibraryViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LibraryText(stringResource(libraryScreenTitle(state.screen)), primary = true)
        LibraryButton(stringResource(R.string.library_browse), true) { expanded = !expanded }
        if (expanded) {
            for (screen in LibraryScreen.entries.filter { it != LibraryScreen.DETAIL }) {
                LibraryButton(stringResource(libraryScreenTitle(screen)), !state.loading) { expanded = false; model.show(screen) }
            }
        }
        if (state.screen == LibraryScreen.SEARCH) {
            OutlinedTextField(
                value = state.searchDraft, onValueChange = model::updateSearch,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                label = { Text(stringResource(R.string.library_search_hint)) }, maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { model.submitSearch(); keyboard?.hide() }),
            )
            LibraryButton(stringResource(R.string.library_search_submit), !state.loading) { model.submitSearch(); keyboard?.hide() }
        }
        if (state.screen == LibraryScreen.RECENT) {
            LibraryText(stringResource(R.string.library_recent_scope))
            LibraryButton(stringResource(R.string.library_all), !state.loading) { model.show(LibraryScreen.ALL) }
        }
    }
}

@Composable
private fun SortEditor(state: LibraryUiState, model: LibraryViewModel) {
    val draft = state.sortEdit ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LibraryText(stringResource(R.string.library_sort_scope))
        LibraryText(stringResource(R.string.library_sort_preview, stringResource(sortFieldText(draft.sort.field)),
            stringResource(if (draft.sort.descending) R.string.library_descending else R.string.library_ascending)))
        for (field in LibrarySortField.entries) LibraryButton(stringResource(sortFieldText(field)), !state.sortSaving) { model.chooseSort(field) }
        LibraryButton(stringResource(R.string.library_reverse_sort), !state.sortSaving, model::toggleSortDirection)
        if (state.sortConflict) LibraryText(stringResource(R.string.prefs_conflict), error = true)
        LibraryButton(stringResource(R.string.prefs_save), !state.sortSaving && !state.sortConflict, model::saveSort)
        LibraryButton(stringResource(R.string.prefs_cancel_edit), !state.sortSaving, model::cancelSort)
    }
}

@Composable
private fun Sections(state: LibraryUiState, model: LibraryViewModel) {
    var expanded by rememberSaveable(state.page?.view?.viewId) { mutableStateOf(false) }
    LibraryButton(stringResource(R.string.library_index), state.canPlay) { expanded = !expanded }
    if (expanded) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (section in state.sections) LibraryButton(stringResource(R.string.library_group_count, section.key, section.count), state.canPlay) {
            expanded = false
            model.jumpSection(section)
        }
    }
}

@Composable
private fun TrackPair(tracks: List<LibraryTrack>, state: LibraryUiState, model: LibraryViewModel, first: Boolean) {
    val measurer = rememberTextMeasurer()
    val label = stringResource(R.string.library_grid_measure)
    val style = MaterialTheme.typography.bodyLarge
    val textWidth = with(LocalDensity.current) { measurer.measure(AnnotatedString(label), style = style, maxLines = 1).size.width.toDp() }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cellWidth = (maxWidth - 8.dp) / 2
        val allowsGrid = cellWidth >= maxOf(48.dp, textWidth + 16.dp)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (first && !allowsGrid) LibraryText(stringResource(R.string.library_grid_fallback))
            if (allowsGrid) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (track in tracks) Box(Modifier.weight(1f)) {
                    TrackCard(track, state.preferences.artworkReadPath, state.canPlay, grid = true, artworkRevision = state.artworkRevision) { model.openDetail(track) }
                }
                if (tracks.size == 1) Box(Modifier.weight(1f))
            } else for (track in tracks) TrackCard(track, state.preferences.artworkReadPath, state.canPlay, artworkRevision = state.artworkRevision) { model.openDetail(track) }
        }
    }
}

@Composable
private fun TrackCard(track: LibraryTrack, path: ArtworkReadPath, enabled: Boolean, grid: Boolean = false,
    artworkRevision: Long = 0L, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (grid) {
                TrackArtwork(track, path, artworkRevision)
                TrackLabel(track)
            } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TrackArtwork(track, path, artworkRevision)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { TrackLabel(track) }
            }
            if (track.availability != LibraryAvailability.AVAILABLE) LibraryText(stringResource(availabilityText(track.availability)))
            if (track.metadataIssue != null) LibraryText(stringResource(R.string.library_metadata_unreadable))
        }
    }
}

@Composable
private fun TrackLabel(track: LibraryTrack) {
    Text(track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
    Text(track.metadata.artist ?: stringResource(R.string.library_unknown_artist), style = MaterialTheme.typography.bodyMedium,
        maxLines = 2, overflow = TextOverflow.Ellipsis)
    LibraryText(track.metadata.durationMs?.let(::formatDuration) ?: stringResource(R.string.probe_unknown_duration))
}

@Composable
internal fun TrackArtwork(track: LibraryTrack, path: ArtworkReadPath, revision: Long = 0L) {
    val app = LocalContext.current.applicationContext as MeloXApplication
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var lease by remember(track.trackKey, track.location, path, revision) { mutableStateOf<ArtworkLease?>(null) }
    LaunchedEffect(track.trackKey, track.location, path, revision, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var acquired: ArtworkLease? = null
            try {
                val location = track.location ?: return@repeatOnLifecycle
                val descriptor = app.library.resolveLocalRead(track.trackKey, location.locationId, location.contentRevision)
                acquired = app.artworks.acquire(descriptor, path)
                lease = acquired
                awaitCancellation()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* A stable local placeholder remains available; audio never waits for artwork. */ }
            finally { lease = null; acquired?.close() }
        }
    }
    val description = stringResource(R.string.library_artwork, track.title)
    Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        val image = lease?.bitmap
        if (image == null) Text("♪", style = MaterialTheme.typography.titleLarge)
        else Image(image.asImageBitmap(), contentDescription = null, modifier = Modifier.size(56.dp))
    }
}

private fun LazyListScope.detailContent(state: LibraryUiState, sources: SourcesViewModel, pending: Boolean, onPlay: (String, TrackKey?) -> Unit) {
    val track = state.detail
    if (track == null) { item { LibraryText(stringResource(R.string.library_detail_missing)) }; return }
    item(key = "library:detail-art") { TrackArtwork(track, state.preferences.artworkReadPath, state.artworkRevision) }
    item { LibraryText(track.title, primary = true) }
    item { LibraryText(stringResource(R.string.library_file_value, track.filename)) }
    item { LibraryText(stringResource(R.string.library_artist_value, track.metadata.artist ?: stringResource(R.string.library_unknown_artist))) }
    items(track.metadata.artists.withIndex().toList(), key = { "artist-value:${it.index}" }) { artist -> LibraryText(artist.value) }
    item { LibraryText(stringResource(R.string.library_album_value, track.metadata.album ?: stringResource(R.string.library_unknown_album))) }
    item { LibraryText(stringResource(R.string.library_album_artist_value, track.metadata.albumArtist ?: stringResource(R.string.library_unknown_artist))) }
    item { LibraryText(stringResource(R.string.library_duration_value, track.metadata.durationMs?.let(::formatDuration) ?: stringResource(R.string.probe_unknown_duration))) }
    item { LibraryText(stringResource(R.string.library_format_value, track.metadata.mimeType ?: stringResource(R.string.library_unknown))) }
    item { LibraryText(stringResource(R.string.library_disc_track, track.metadata.discNumber?.toString() ?: "—", track.metadata.trackNumber?.toString() ?: "—")) }
    item { LibraryText(stringResource(R.string.library_added_value, formatDate(track.firstAddedEpochMs))) }
    item { LibraryText(stringResource(availabilityText(track.availability))) }
    item { LibraryText(stringResource(R.string.library_listening_value, track.playCount, formatDuration(track.listeningMs))) }
    if (track.metadataIssue != null) item { LibraryText(stringResource(R.string.library_metadata_unreadable), error = true) }
    item {
        LibraryButton(stringResource(R.string.library_play_selected), state.canPlay && !pending) {
            onPlay(requireNotNull(state.page).view.viewId, track.trackKey)
        }
    }
    if (track.sourceId != null && track.nodeId != null) item {
        LibraryButton(stringResource(R.string.library_exclude_file), true) { sources.excludeTrack(track.sourceId, track.nodeId) }
    }
}

@Composable
private fun ArtworkRefreshControls(state: LibraryUiState, model: LibraryViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.artworkPreparing) LibraryText(stringResource(R.string.library_reading))
        state.artworkSourceIssue?.let { LibraryText(stringResource(sourceIssueText(it)), error = true) }
        state.artworkRefreshPreview?.let { preview ->
            LibraryText(stringResource(R.string.library_artwork_refresh_confirmation, preview.targets.size))
            LibraryButton(stringResource(R.string.library_artwork_refresh_confirm), true, model::confirmArtworkRefresh)
            LibraryButton(stringResource(R.string.probe_cancel), true, model::cancelArtworkPreview)
        }
        state.artworkRefresh?.let { refresh ->
            val label = when (refresh.phase) {
                ArtworkRefreshPhase.RUNNING -> R.string.library_artwork_refresh_running
                ArtworkRefreshPhase.STOPPING -> R.string.library_stopping
                ArtworkRefreshPhase.COMPLETED -> R.string.library_artwork_refresh_completed
                ArtworkRefreshPhase.CANCELLED -> R.string.library_cancelled
                ArtworkRefreshPhase.PAUSED_BACKGROUND -> R.string.library_artwork_refresh_background
                ArtworkRefreshPhase.FAILED -> R.string.library_artwork_refresh_failed
            }
            LibraryText(stringResource(label))
            LibraryText(stringResource(R.string.library_artwork_refresh_counts,
                refresh.processed, refresh.total, refresh.updated, refresh.missing, refresh.failed))
            if (refresh.phase == ArtworkRefreshPhase.RUNNING) LibraryButton(stringResource(R.string.probe_cancel), true, model::cancelArtworkRefresh)
        }
    }
}

@Composable
internal fun LibraryText(text: String, primary: Boolean = false, error: Boolean = false) {
    Text(text, modifier = Modifier.fillMaxWidth(),
        style = if (primary) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else if (primary) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun LibraryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(label, textAlign = TextAlign.Center)
    }
}

internal fun libraryScreenTitle(screen: LibraryScreen): Int = when (screen) {
    LibraryScreen.ALL -> R.string.library_all
    LibraryScreen.SEARCH -> R.string.library_search
    LibraryScreen.ALBUMS -> R.string.library_albums
    LibraryScreen.ARTISTS -> R.string.library_artists
    LibraryScreen.FOLDERS -> R.string.library_folders
    LibraryScreen.RECENT -> R.string.library_recent
    LibraryScreen.DETAIL -> R.string.library_detail
    LibraryScreen.SOURCES -> R.string.library_sources
    LibraryScreen.HISTORY -> R.string.library_history
    LibraryScreen.STATISTICS -> R.string.library_statistics
}

internal fun libraryIssueText(issue: LibraryIssue): Int = when (issue) {
    LibraryIssue.BUSY -> R.string.library_busy
    LibraryIssue.CONFLICT -> R.string.library_conflict
    LibraryIssue.INVALID_INPUT -> R.string.library_invalid_input
    LibraryIssue.VIEW_EXPIRED -> R.string.library_view_expired
    LibraryIssue.SOURCE_CHANGED -> R.string.library_source_changed
    LibraryIssue.SOURCE_UNAVAILABLE -> R.string.library_source_unavailable
    LibraryIssue.PERMISSION_REVOKED -> R.string.library_permission_revoked
    LibraryIssue.FILE_MISSING -> R.string.library_file_missing
    LibraryIssue.STORAGE_FULL -> R.string.library_storage_full
    LibraryIssue.STORAGE_UNAVAILABLE -> R.string.library_storage_unavailable
    LibraryIssue.RECOVERY_REQUIRED -> R.string.library_recovery_required
    LibraryIssue.INCOMPLETE -> R.string.library_partial
}

private fun availabilityText(value: LibraryAvailability): Int = when (value) {
    LibraryAvailability.AVAILABLE -> R.string.library_available
    LibraryAvailability.UNKNOWN -> R.string.library_access_unknown
    LibraryAvailability.NEEDS_AUTHORIZATION -> R.string.library_permission_revoked
    LibraryAvailability.UNAVAILABLE -> R.string.library_source_unavailable
    LibraryAvailability.MISSING -> R.string.library_file_missing
    LibraryAvailability.REMOVED -> R.string.library_source_removed
}

private fun sortFieldText(field: LibrarySortField): Int = when (field) {
    LibrarySortField.TITLE -> R.string.library_sort_title
    LibrarySortField.ARTIST -> R.string.library_sort_artist
    LibrarySortField.ALBUM -> R.string.library_sort_album
    LibrarySortField.DURATION -> R.string.library_sort_duration
    LibrarySortField.ADDED -> R.string.library_sort_added
    LibrarySortField.FILENAME_NATURAL -> R.string.library_sort_filename
    LibrarySortField.ALBUM_TRACK -> R.string.library_sort_album_track
    LibrarySortField.PLAY_COUNT -> R.string.library_sort_play_count
    LibrarySortField.LISTENING_TIME -> R.string.library_sort_listening
}

internal fun formatDuration(milliseconds: Long): String = String.format(Locale.ROOT, "%d:%02d", milliseconds / 60_000L, milliseconds / 1000L % 60)
internal fun formatDate(milliseconds: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(milliseconds))
