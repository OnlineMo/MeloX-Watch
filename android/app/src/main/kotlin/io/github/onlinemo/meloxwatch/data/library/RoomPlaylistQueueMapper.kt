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
        val keys = members.mapNotNull { it.reference.trackKey ?: it.reference.cueReference?.trackKey }
        val tracks = library.playlistTracks(context, keys)
        var keyIndex = 0
        return members.map { member ->
            currentCoroutineContext().ensureActive()
            val reference = member.reference
            val key = reference.trackKey ?: reference.cueReference?.trackKey
            val track = if (key == null) null else tracks[keyIndex++]
            val hint = reference.cueReference
            val selectedCue = if (hint == null) track?.cueReference else track?.cueReference?.takeIf {
                currentCueMatchesPortableHint(it, hint) &&
                    (track?.availability == LibraryAvailability.AVAILABLE || track?.availability == LibraryAvailability.UNKNOWN)
            } ?: hint
            // UNKNOWN绑定须重核当前完整组围栏；实际文件验证保留在显式播放中。
            val cue = if (track?.availability == LibraryAvailability.UNKNOWN && selectedCue?.isBound == true) {
                library.resolveCueRead(selectedCue.trackKey, selectedCue).reference
            } else selectedCue
            val pendingCue = cue != null && !cue.isBound
            val availability = if (key == null) PlaylistQueueAvailability.UNRESOLVED else if (pendingCue) PlaylistQueueAvailability.UNAVAILABLE else when (track?.availability) {
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
                durationMs = if (pendingCue) cue?.identity?.durationUs?.div(1000L)?.takeIf { it > 0L }
                    else track?.metadata?.durationMs ?: reference.durationMs,
                location = if (cue != null) cue.binding?.audio?.location else track?.location,
                cueReference = cue,
            ), availability)
        }
    }
}
