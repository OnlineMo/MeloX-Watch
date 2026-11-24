package io.github.onlinemo.meloxwatch.data.library

import io.github.onlinemo.meloxwatch.data.playlists.PlaylistLimits
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMember
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistQueueAvailability
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistQueueCatalogToken
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistQueueMapper
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistQueueResolution
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.playback.PlaylistQueueKeys
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A placeholder has no access route. Its descriptive text is never passed to a player. */
class RoomPlaylistQueueMapper(private val library: RoomLocalLibraryRepository) : PlaylistQueueMapper {
    override suspend fun map(context: PlaylistQueueCatalogToken, members: List<PlaylistMember>): List<PlaylistQueueResolution> {
        require(members.size <= PlaylistLimits.PAGE_SIZE)
        val keys = members.mapNotNull { it.reference.trackKey }
        val tracks = library.playlistTracks(context, keys)
        var keyIndex = 0
        return members.map { member ->
            currentCoroutineContext().ensureActive()
            val reference = member.reference
            val key = reference.trackKey
            val track = if (key == null) null else tracks[keyIndex++]
            val availability = if (key == null) PlaylistQueueAvailability.UNRESOLVED else when (track?.availability) {
                LibraryAvailability.AVAILABLE -> PlaylistQueueAvailability.AVAILABLE
                LibraryAvailability.MISSING -> PlaylistQueueAvailability.MISSING
                LibraryAvailability.NEEDS_AUTHORIZATION -> PlaylistQueueAvailability.NEEDS_AUTHORIZATION
                else -> PlaylistQueueAvailability.UNAVAILABLE
            }
            PlaylistQueueResolution(member.memberId, QueueEntry(
                queueEntryId = UUID.randomUUID().toString(),
                trackKey = key ?: PlaylistQueueKeys.unresolved(checkNotNull(reference.placeholderId)),
                originalOrder = member.ordinal,
                playbackOrder = member.ordinal,
                displayName = track?.title ?: reference.titleHint,
                durationMs = track?.metadata?.durationMs ?: reference.durationMs,
                location = track?.location,
            ), availability)
        }
    }
}
