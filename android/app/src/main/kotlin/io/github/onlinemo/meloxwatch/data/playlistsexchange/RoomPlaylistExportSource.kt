package io.github.onlinemo.meloxwatch.data.playlistsexchange

import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.database.withPreservedTransaction
import io.github.onlinemo.meloxwatch.data.library.LibraryAvailability
import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.library.LibraryTrack
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.local.allowsFileReadValidation
import io.github.onlinemo.meloxwatch.data.library.LibrarySql
import io.github.onlinemo.meloxwatch.data.library.LibraryViewToken
import io.github.onlinemo.meloxwatch.data.library.RoomLocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.library.currentCueMatchesPortableHint
import io.github.onlinemo.meloxwatch.data.playlists.LocalPlaylistRepository
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistException
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistKind
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistQueueCatalogToken
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistViewToken
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Both leases cover the whole serialization. The small root set never grows with the playlist. */
internal class RoomPlaylistExportSource(
    private val database: MeloXDatabase,
    private val playlists: LocalPlaylistRepository,
    private val library: RoomLocalLibraryRepository,
    private val playlistView: PlaylistViewToken,
    private val libraryView: LibraryViewToken,
    private val exportId: String,
    private val singleLineTitles: Boolean,
) : ExchangeExportSource {
    override val memberCount = playlistView.snapshot.memberCount
    override val header = ExchangePlaylistHeader(playlistView.snapshot.name, when (playlistView.snapshot.kind) {
        PlaylistKind.ORDINARY -> ExchangePlaylistKind.MANUAL
        PlaylistKind.FOLDER -> ExchangePlaylistKind.FOLDER
        PlaylistKind.FAVORITES -> ExchangePlaylistKind.FAVORITES
    }, exportId)
    private val catalog = PlaylistQueueCatalogToken(libraryView.viewId, libraryView.revision, libraryView.rulesRevision)
    private var relativeSourceId: String? = null
    private var rootPrefix: List<String> = emptyList()

    suspend fun prepareRelativeRoot() {
        validateSnapshot()
        var candidate: String? = null
        var offset = 0L
        while (offset < memberCount) {
            currentCoroutineContext().ensureActive()
            val wanted = minOf(PlaylistExchangeLimits.PAGE_SIZE.toLong(), memberCount - offset).toInt()
            val page = playlists.page(playlistView, offset, wanted)
            if (page.view != playlistView || page.members.size != wanted) fail(PlaylistIssue.INCOMPLETE)
            val keys = page.members.mapNotNull { it.reference.trackKey }
            val tracks = library.playlistTracks(catalog, keys)
            for (track in tracks) {
                if (track == null || track.sourceId == null || !canUseRelativeTrack(track)) return
                if (candidate != null && candidate != track.sourceId) return
                candidate = track.sourceId
            }
            offset += page.members.size
        }
        val sourceId = candidate ?: return
        val binding = playlistView.snapshot.folderBinding
        if (binding != null) {
            if (binding.sourceId != sourceId) return
            val path = database.withPreservedTransaction {
                database.libraryDao().text(LibrarySql.relativePath(libraryView.revision, sourceId, binding.nodeId))
            } ?: return
            rootPrefix = if (path.isEmpty()) emptyList() else ExchangeRelativePath.parse(path).components
        }
        relativeSourceId = sourceId
        validateSnapshot()
    }

    override suspend fun page(offset: Long, limit: Int): List<ExchangeMember> {
        require(offset >= 0L && limit in 1..PlaylistExchangeLimits.PAGE_SIZE)
        val page = playlists.page(playlistView, offset, limit)
        if (page.view != playlistView) fail(PlaylistIssue.SOURCE_CHANGED)
        val keys = page.members.mapNotNull { it.reference.trackKey }
        val tracks = library.playlistTracks(catalog, keys)
        var keyIndex = 0
        return page.members.map { member ->
            currentCoroutineContext().ensureActive()
            val reference = member.reference
            val track = if (reference.trackKey == null) null else tracks[keyIndex++]
            val storedCue = reference.cueReference
            val currentCue = track?.cueReference
            // 保存的证明决定待确认状态，不能用同一逻辑标识的当前内容替换它。
            val cue = storedCue ?: currentCue
            val cueAvailable = currentCue != null && currentCue.isBound &&
                (storedCue == null || currentCueMatchesPortableHint(currentCue, storedCue)) &&
                track != null && canUseRelativeTrack(track)
            val path = if ((cue == null || cueAvailable) &&
                track?.sourceId != null && track.sourceId == relativeSourceId) {
                (if (cue != null) library.cueRelativePath(libraryView, track)
                    else library.playlistRelativePath(libraryView, track))?.let { full ->
                    val components = ExchangeRelativePath.parse(full).components
                    if (components.size <= rootPrefix.size || components.take(rootPrefix.size) != rootPrefix) null
                    else ExchangeRelativePath.parse(components.drop(rootPrefix.size).joinToString("/"))
                }
            } else null
            val title = reference.titleHint ?: track?.metadata?.title ?: track?.title
            val exportMemberId = UUID.nameUUIDFromBytes("$exportId:${member.memberId}".toByteArray(Charsets.UTF_8)).toString()
            ExchangeMember(member.ordinal, exportMemberId, trackKey = reference.trackKey, relativePath = path,
                mediaKind = if (cue == null) ExchangeMediaKind.AUDIO else ExchangeMediaKind.CUE_TRACK,
                cue = cue?.let { ExchangeCueHint(path, it.identity.trackNumber, it.portable()) },
                title = if (singleLineTitles) title?.replace('\r', ' ')?.replace('\n', ' ') else title,
                artist = reference.artistHint ?: track?.metadata?.artist,
                album = reference.albumHint ?: track?.metadata?.album,
                durationMs = reference.durationMs ?: track?.metadata?.durationMs,
                unresolved = reference.trackKey == null || cue != null && (!cueAvailable || path == null))
        }
    }

    private suspend fun canUseRelativeTrack(track: LibraryTrack): Boolean {
        if (track.availability == LibraryAvailability.AVAILABLE) return true
        if (track.availability != LibraryAvailability.UNKNOWN) return false
        val location = track.location ?: return false
        // 只重核当前索引绑定和完整CUE组；导出不读取引用音频或补写证明。
        val read = try { library.resolveLocalRead(track.trackKey, location.locationId, location.contentRevision) }
        catch (failure: LibraryException) {
            if (failure.issue in setOf(LibraryIssue.PERMISSION_REVOKED, LibraryIssue.SOURCE_UNAVAILABLE,
                    LibraryIssue.FILE_MISSING, LibraryIssue.SOURCE_CHANGED)) return false
            throw failure
        }
        if (read.source.spec.sourceId != track.sourceId || read.location != location || read.cueReference != track.cueReference) return false
        return read.source.spec.kind == LocalSourceKind.TREE && read.source.grant.read == ObservedCapability.UNKNOWN &&
            read.source.grant.allowsFileReadValidation(read.source.spec.kind)
    }

    override suspend fun validateSnapshot() {
        val page = playlists.page(playlistView, 0L, 1)
        if (page.view != playlistView) fail(PlaylistIssue.SOURCE_CHANGED)
        library.validatePlaylistView(libraryView)
    }

    private fun fail(issue: PlaylistIssue): Nothing = throw PlaylistException(issue)
}
