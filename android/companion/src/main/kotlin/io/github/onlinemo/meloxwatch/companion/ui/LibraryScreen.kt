package io.github.onlinemo.meloxwatch.companion.ui

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.github.onlinemo.meloxwatch.companion.R
import io.github.onlinemo.meloxwatch.companion.data.CompanionConnectionPhase
import io.github.onlinemo.meloxwatch.companion.data.companionRowIdentity
import io.github.onlinemo.meloxwatch.data.transfer.TransferCapability
import io.github.onlinemo.meloxwatch.data.transfer.TransferCatalogKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferCatalogRow
import java.text.DateFormat
import java.util.Date

internal fun LazyListScope.libraryItems(state: CompanionUiState, model: CompanionViewModel) {
    item(key = "library-query") {
        var search by remember(state.pageWindow.kind, state.pageWindow.search) { mutableStateOf(state.pageWindow.search) }
        MeloXCard {
            MeloXText(stringResource(R.string.library_read_only), secondary = true)
            if (!state.allowed(TransferCapability.READ_LIBRARY)) MeloXText(stringResource(R.string.read_permission_needed), secondary = true)
            if (state.trackSelection == CompanionTrackSelection.NONE) {
                listOf(TransferCatalogKind.TRACKS, TransferCatalogKind.ALBUMS, TransferCatalogKind.ARTISTS).forEach { kind ->
                    MeloXChoice(catalogName(kind), state.pageWindow.kind == kind, { model.refresh(kind, "", null) }, enabled = state.available && state.allowed(TransferCapability.READ_LIBRARY))
                }
            } else {
                MeloXText(stringResource(R.string.selection_batch_limit), secondary = true)
                MeloXAction(stringResource(R.string.return_without_selection), model::cancelTrackSelection)
            }
            if (state.pageWindow.kind != TransferCatalogKind.PLAYLIST_MEMBERS) {
                MeloXField(stringResource(R.string.search), search, { search = it })
                MeloXAction(stringResource(R.string.search_on_watch), { model.refresh(state.pageWindow.kind, search, state.pageWindow.parentId) },
                    enabled = state.available && state.allowed(TransferCapability.READ_LIBRARY))
            }
            if (state.pageWindow.kind in setOf(TransferCatalogKind.ALBUM_TRACKS, TransferCatalogKind.ARTIST_TRACKS)) {
                val group = state.group
                if (group != null) {
                    MeloXText(group.state.currentName.ifBlank { stringResource(R.string.unnamed_group) })
                    MeloXText(stringResource(R.string.display_group_id, group.state.entityId), secondary = true, mono = true)
                    if (state.trackSelection == CompanionTrackSelection.NONE) MeloXAction(stringResource(R.string.back_to_groups), { model.showGroups(group.state.kind) })
                }
            }
        }
    }
    pageItems(state, model)
    val page = state.pageWindow.current?.page
    if (page != null) items(page.rows, key = ::companionRowIdentity) { row ->
        when (row) {
            is TransferCatalogRow.Track -> trackView(row, state, model)
            is TransferCatalogRow.Group -> groupView(row, state, model)
            is TransferCatalogRow.Playlist -> playlistRowView(row, state, model)
            is TransferCatalogRow.Member -> MeloXCard {
                MeloXText(row.titleHint)
                MeloXText(row.artistHint, secondary = true)
            }
        }
    }
    if (state.trackSelection != CompanionTrackSelection.NONE) item(key = "library-track-selection") {
        MeloXCard {
            MeloXText(when (state.trackSelection) {
                CompanionTrackSelection.PLAYLIST_ADD -> stringResource(R.string.tracks_for_playlist, state.playlist?.row?.name.orEmpty(), state.selectedTracks.size)
                CompanionTrackSelection.DISPLAY_ADD -> stringResource(R.string.tracks_for_display_add, state.group?.state?.currentName.orEmpty(), state.selectedTracks.size)
                CompanionTrackSelection.DISPLAY_REMOVE -> stringResource(R.string.tracks_for_display_remove, state.group?.state?.currentName.orEmpty(), state.selectedTracks.size)
                CompanionTrackSelection.NONE -> ""
            })
            MeloXText(stringResource(R.string.selection_batch_limit), secondary = true)
            val capability = if (state.trackSelection == CompanionTrackSelection.PLAYLIST_ADD) TransferCapability.WRITE_PLAYLISTS else TransferCapability.WRITE_DISPLAY_METADATA
            MeloXAction(stringResource(R.string.save_selected_tracks), model::prepareSelectedTracks,
                enabled = state.available && state.allowed(capability) && state.selectedTracks.isNotEmpty(), prominent = true)
            MeloXAction(stringResource(R.string.clear_selection), model::clearTrackSelection, enabled = state.available)
        }
    }
}

internal fun LazyListScope.pageItems(state: CompanionUiState, model: CompanionViewModel) {
    item(key = "bounded-page-controls") {
        MeloXCard {
            val cached = state.pageWindow.current
            if (cached == null) MeloXText(stringResource(R.string.request_first_page), secondary = true)
            else {
                MeloXText(stringResource(R.string.cached_target, cached.target.forLocalDisplay().chunked(8).joinToString(" ")), secondary = true, mono = true)
                MeloXText(stringResource(R.string.cache_time, DateFormat.getDateTimeInstance().format(Date(cached.capturedAtEpochMs))), secondary = true)
                if (state.connection.phase != CompanionConnectionPhase.READY || cached.target != state.connection.target?.fingerprint || cached.sessionId != state.connection.sessionId) {
                    MeloXText(stringResource(R.string.cache_is_stale), secondary = true)
                }
                MeloXText(stringResource(R.string.device_revision, cached.page.revision), secondary = true)
                MeloXText(stringResource(R.string.library_fence, cached.page.libraryRevision.catalogRevision, cached.page.libraryRevision.rulesRevision), secondary = true)
                val first = if (cached.page.rows.isEmpty()) 0L else cached.page.firstOrdinal + 1L
                val last = cached.page.firstOrdinal + cached.page.rows.size
                MeloXText(stringResource(R.string.page_range, first, last, cached.page.totalCount))
                if (cached.page.totalCount == 0L) MeloXText(stringResource(R.string.empty_result), secondary = true)
            }
            MeloXAction(stringResource(R.string.refresh_from_start), { model.refresh() }, enabled = state.available && state.allowed(TransferCapability.READ_LIBRARY))
            MeloXAction(stringResource(R.string.previous_page), model::previousPage, enabled = state.available && state.pageWindow.index > 0)
            val canNext = state.pageWindow.cachedNext() != null || cached?.page?.nextCursor != null && state.allowed(TransferCapability.READ_LIBRARY)
            MeloXAction(stringResource(R.string.next_page), model::nextPage, enabled = state.available && canNext)
        }
    }
}

@Composable
internal fun trackView(row: TransferCatalogRow.Track, state: CompanionUiState, model: CompanionViewModel) {
    MeloXCard {
        val writeCapability = if (state.trackSelection == CompanionTrackSelection.PLAYLIST_ADD) TransferCapability.WRITE_PLAYLISTS else TransferCapability.WRITE_DISPLAY_METADATA
        if (state.trackSelection != CompanionTrackSelection.NONE) MeloXToggle(row.title.ifBlank { stringResource(R.string.untitled_track) }, row.trackId in state.selectedTracks, { model.toggleTrack(row) }, enabled = state.available && state.allowed(TransferCapability.READ_LIBRARY) && state.allowed(writeCapability))
        else MeloXText(row.title)
        MeloXText(row.artist, secondary = true)
        MeloXText(row.album, secondary = true)
        MeloXText(if (row.durationMs == null) stringResource(R.string.duration_unknown) else stringResource(R.string.duration_seconds, row.durationMs / 1000L), secondary = true)
        if (!row.available) MeloXText(stringResource(R.string.track_unavailable), secondary = true)
    }
}

@Composable
internal fun groupView(row: TransferCatalogRow.Group, state: CompanionUiState, model: CompanionViewModel) {
    MeloXCard {
        MeloXText(row.title)
        MeloXText(row.subtitle, secondary = true)
        MeloXText(stringResource(R.string.track_count, row.trackCount), secondary = true)
        MeloXAction(stringResource(R.string.open_group_members), { model.browseGroup(row) }, enabled = state.available && state.allowed(TransferCapability.READ_LIBRARY))
        if (state.allowed(TransferCapability.WRITE_DISPLAY_METADATA)) MeloXAction(stringResource(R.string.edit_display_group), { model.selectGroup(row) }, enabled = state.available)
    }
}

@Composable
internal fun playlistRowView(row: TransferCatalogRow.Playlist, state: CompanionUiState, model: CompanionViewModel) {
    MeloXCard {
        MeloXText(row.name)
        MeloXText(stringResource(R.string.track_count, row.memberCount), secondary = true)
        if (row.automatic) MeloXText(stringResource(R.string.automatic_playlist), secondary = true)
        MeloXAction(stringResource(R.string.open_playlist), { model.selectPlaylist(row) }, enabled = state.available && state.allowed(TransferCapability.READ_LIBRARY))
    }
}

@Composable
internal fun catalogName(kind: TransferCatalogKind): String = stringResource(when (kind) {
    TransferCatalogKind.TRACKS -> R.string.tracks
    TransferCatalogKind.ALBUMS -> R.string.albums
    TransferCatalogKind.ARTISTS -> R.string.artists
    TransferCatalogKind.PLAYLISTS -> R.string.playlists
    TransferCatalogKind.PLAYLIST_MEMBERS -> R.string.playlist_members
    TransferCatalogKind.ALBUM_TRACKS -> R.string.album_tracks
    TransferCatalogKind.ARTIST_TRACKS -> R.string.artist_tracks
})
