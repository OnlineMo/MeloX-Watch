package io.github.onlinemo.meloxwatch.data.library

import io.github.onlinemo.meloxwatch.data.playlists.FrozenPlaylistInput
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistException
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistInputPage
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistLimits
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistPageSource
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistReference

/** A retry restarts the same retained library view; it never opens a newer library or keeps the full list. */
internal class LibraryPlaylistInput(
    private val library: LocalLibraryRepository,
    private val view: LibraryViewToken,
) : PlaylistPageSource {
    val input = FrozenPlaylistInput(view.viewId, view.revision, view.totalCount)
    private var cursor: LibraryCursor? = null
    private var nextOffset = 0L
    private var previous: PlaylistInputPage? = null

    override suspend fun read(offset: Long, limit: Int): PlaylistInputPage {
        if (offset < 0L || limit !in 1..PlaylistLimits.PAGE_SIZE) fail(PlaylistIssue.INVALID_INPUT)
        if (offset == 0L) {
            cursor = null
            nextOffset = 0L
            previous = null
        } else previous?.takeIf { it.offset == offset && it.references.size <= limit }?.let { return it }
        if (offset != nextOffset) fail(PlaylistIssue.INCOMPLETE)
        val page = library.page(view, cursor, limit)
        if (page.view != view || page.tracks.size > limit) fail(PlaylistIssue.SOURCE_CHANGED)
        val references = page.tracks.map { track ->
            if (track.availability != LibraryAvailability.AVAILABLE &&
                !(view.query.allowUnknownTreeReadValidation && track.availability == LibraryAvailability.UNKNOWN)) {
                fail(PlaylistIssue.SOURCE_CHANGED)
            }
            PlaylistReference(trackKey = track.trackKey, titleHint = track.metadata.title,
                artistHint = track.metadata.artist, albumHint = track.metadata.album, durationMs = track.metadata.durationMs)
        }
        val end = try { Math.addExact(offset, references.size.toLong()) }
        catch (_: ArithmeticException) { fail(PlaylistIssue.INCOMPLETE) }
        val endReached = page.nextCursor == null
        if (end > input.totalCount || endReached != (end == input.totalCount) || (!endReached && references.isEmpty())) {
            fail(PlaylistIssue.INCOMPLETE)
        }
        cursor = page.nextCursor
        nextOffset = end
        return PlaylistInputPage(input, offset, references, endReached).also { previous = it }
    }

    private fun fail(issue: PlaylistIssue): Nothing = throw PlaylistException(issue)
}
