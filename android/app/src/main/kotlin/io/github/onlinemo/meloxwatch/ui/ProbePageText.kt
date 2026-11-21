package io.github.onlinemo.meloxwatch.ui

import io.github.onlinemo.meloxwatch.ui.library.LibraryUiState
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistEntryUiState
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistUiState
import io.github.onlinemo.meloxwatch.ui.queue.QueueViewerState

/** Local font coverage uses the bounded current page and visible keys, never the full library. */
internal fun probePageVisibleText(
    page: ProbePage,
    state: ProbeUiState,
    library: LibraryUiState,
    queue: QueueViewerState,
    playlists: PlaylistUiState,
    entry: PlaylistEntryUiState,
    visibleKeys: List<Any>,
): List<String> = buildList {
    val keys = visibleKeys.toSet()
    if (page == ProbePage.PLAYBACK) {
        state.playback.title?.let(::add)
        state.playback.artist?.let(::add)
        entry.playingTrack?.let { track ->
            add(track.title)
            track.metadata.artist?.let(::add)
        }
        state.selectedAudio?.displayName?.let(::add)
    }
    if (page == ProbePage.QUEUE) queue.entries.forEach { row ->
        if ("queue:entry:${row.queueEntryId}" in keys) row.displayName?.let(::add)
    }
    if (page == ProbePage.LIBRARY) {
        val tracks = library.page?.tracks.orEmpty()
        for ((index, track) in tracks.withIndex()) {
            val gridKey = "pair:${tracks[index - index % 2].trackKey.trackId}"
            if ("track:${track.trackKey.providerId}:${track.trackKey.trackId}" in keys || gridKey in keys) {
                add(track.title)
                track.metadata.artist?.let(::add)
                track.metadata.album?.let(::add)
            }
        }
        library.groupPage?.groups.orEmpty().forEach { group ->
            if ("group:${group.key}" in keys) {
                group.title?.let(::add)
                group.subtitle?.let(::add)
            }
        }
        library.folderPage?.folders.orEmpty().forEach { folder ->
            if ("folder:${folder.nodeId}" in keys) folder.name?.let(::add)
        }
        library.detail?.let { track ->
            add(track.title)
            add(track.filename)
            track.metadata.artist?.let(::add)
            addAll(track.metadata.artists)
            track.metadata.album?.let(::add)
            track.metadata.albumArtist?.let(::add)
            track.metadata.mimeType?.let(::add)
        }
        if ("library:navigation" in keys) add(library.searchDraft)
    }
    if (page == ProbePage.PLAYLISTS) {
        playlists.catalogPage?.playlists.orEmpty().forEach { row ->
            if ("playlist:${row.playlistId}" in keys) add(row.name)
        }
        playlists.rows.forEach { row ->
            if ("member:${row.member.memberId}" in keys) row.title?.let(::add)
        }
        playlists.selected?.title?.let(::add)
        playlists.view?.snapshot?.name?.let(::add)
        playlists.nameDraft?.name?.let(::add)
        playlists.playDraft?.title?.let(::add)
        playlists.addLabel?.let(::add)
    }
}
