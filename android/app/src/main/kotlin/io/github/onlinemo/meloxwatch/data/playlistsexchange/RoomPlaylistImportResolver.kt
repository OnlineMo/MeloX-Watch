package io.github.onlinemo.meloxwatch.data.playlistsexchange

import io.github.onlinemo.meloxwatch.data.library.LibraryAvailability
import io.github.onlinemo.meloxwatch.data.library.LibraryTrack
import io.github.onlinemo.meloxwatch.data.library.LibraryViewToken
import io.github.onlinemo.meloxwatch.data.library.RoomLocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistFolderBinding
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistLimits
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistQueueAvailability
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistQueueCatalogToken
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistReferenceIssue
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistImportMemberIssue
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Only indexed identities or an exact path under the explicitly selected root may resolve a member. */
internal class RoomPlaylistImportResolver(
    private val library: RoomLocalLibraryRepository,
    private val view: LibraryViewToken,
    private val root: PlaylistFolderBinding?,
) {
    suspend fun resolve(previewId: String, members: List<ExchangeMember>): List<PlaylistExchangeRowEntity> {
        require(members.size <= PlaylistExchangeLimits.PAGE_SIZE)
        val requested = members.mapNotNull { it.trackKey?.takeIf { key -> key.trackId.length <= PlaylistLimits.MAX_IDENTIFIER_CHARACTERS } }
        val context = PlaylistQueueCatalogToken(view.viewId, view.revision, view.rulesRevision)
        val tracks = library.playlistTracks(context, requested)
        val byKey = requested.zip(tracks).toMap()
        return members.map { member ->
            currentCoroutineContext().ensureActive()
            val knownTrack = member.trackKey?.let { byKey[it] }
            val resolution = when {
                member.referenceIssue == ExchangeReferenceIssue.EXTERNAL_REFERENCE ||
                    member.referenceIssue == ExchangeReferenceIssue.UNSAFE_RELATIVE_PATH ->
                    Resolution(null, PlaylistImportMemberIssue.BLOCKED_REFERENCE)
                // The index stores a bounded content proof, not the full-file SHA-256 advertised by an exchange document.
                member.contentSha256 != null || member.mediaKind == ExchangeMediaKind.CUE_TRACK ||
                    member.unresolved && member.referenceIssue == null ->
                    Resolution(null, PlaylistImportMemberIssue.REQUIRES_REASSOCIATION)
                knownTrack != null -> Resolution(knownTrack, PlaylistImportMemberIssue.NONE)
                member.relativePath != null && root != null -> {
                    val matches = library.playlistRelativeMatches(view, root.nodeId, member.relativePath.components)
                    when (matches.size) {
                        0 -> Resolution(null, PlaylistImportMemberIssue.UNMATCHED)
                        1 -> {
                            val track = library.playlistTracks(context, matches).single()
                            if (track == null) Resolution(null, PlaylistImportMemberIssue.UNMATCHED)
                            else Resolution(track, PlaylistImportMemberIssue.NONE)
                        }
                        else -> Resolution(null, PlaylistImportMemberIssue.AMBIGUOUS)
                    }
                }
                else -> Resolution(null, PlaylistImportMemberIssue.UNMATCHED)
            }
            val track = resolution.track
            val issue = if (track != null) null else when {
                member.blockedReference?.startsWith("http:", ignoreCase = true) == true ||
                    member.blockedReference?.startsWith("https:", ignoreCase = true) == true -> PlaylistReferenceIssue.NETWORK_REFERENCE
                resolution.issue == PlaylistImportMemberIssue.REQUIRES_REASSOCIATION ||
                    resolution.issue == PlaylistImportMemberIssue.BLOCKED_REFERENCE ||
                    resolution.issue == PlaylistImportMemberIssue.AMBIGUOUS -> PlaylistReferenceIssue.REQUIRES_REASSOCIATION
                else -> PlaylistReferenceIssue.UNMATCHED
            }
            PlaylistExchangeRowEntity(previewId, member.ordinal, member.memberId,
                track?.trackKey?.trackId, member.memberId.takeIf { track == null }, issue?.name,
                member.title ?: track?.metadata?.title, member.artist ?: track?.metadata?.artist,
                member.album ?: track?.metadata?.album, member.relativePath?.toString(),
                // Raw absolute paths and URLs are not retained; the typed issue explains reassociation.
                null, member.durationMs ?: track?.metadata?.durationMs, resolution.issue.name,
                playlistImportAvailability(track).name)
        }
    }

    private data class Resolution(val track: LibraryTrack?, val issue: PlaylistImportMemberIssue)
}

internal fun playlistImportAvailability(track: LibraryTrack?): PlaylistQueueAvailability = when (track?.availability) {
    LibraryAvailability.AVAILABLE -> PlaylistQueueAvailability.AVAILABLE
    LibraryAvailability.MISSING -> PlaylistQueueAvailability.MISSING
    LibraryAvailability.NEEDS_AUTHORIZATION -> PlaylistQueueAvailability.NEEDS_AUTHORIZATION
    null -> PlaylistQueueAvailability.UNRESOLVED
    else -> PlaylistQueueAvailability.UNAVAILABLE
}
