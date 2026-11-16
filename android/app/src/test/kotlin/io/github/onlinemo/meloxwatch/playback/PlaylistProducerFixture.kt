package io.github.onlinemo.meloxwatch.playback

import io.github.onlinemo.meloxwatch.data.library.LibraryCursor
import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryExclusionPage
import io.github.onlinemo.meloxwatch.data.library.LibraryFolder
import io.github.onlinemo.meloxwatch.data.library.LibraryFolderPage
import io.github.onlinemo.meloxwatch.data.library.LibraryGroupKind
import io.github.onlinemo.meloxwatch.data.library.LibraryGroupPage
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.library.LibraryLocation
import io.github.onlinemo.meloxwatch.data.library.LibraryOverview
import io.github.onlinemo.meloxwatch.data.library.LibraryPage
import io.github.onlinemo.meloxwatch.data.library.LibraryQuery
import io.github.onlinemo.meloxwatch.data.library.LibraryRules
import io.github.onlinemo.meloxwatch.data.library.LibrarySection
import io.github.onlinemo.meloxwatch.data.library.LibrarySort
import io.github.onlinemo.meloxwatch.data.library.LibrarySource
import io.github.onlinemo.meloxwatch.data.library.LibrarySourcePage
import io.github.onlinemo.meloxwatch.data.library.LibraryViewOwner
import io.github.onlinemo.meloxwatch.data.library.LibraryViewToken
import io.github.onlinemo.meloxwatch.data.library.LocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.library.LocalReadDescriptor
import io.github.onlinemo.meloxwatch.data.playlists.LocalPlaylistRepository
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCatalogCursor
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCatalogPage
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCatalogView
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCommit
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistException
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistKind
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMember
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutationRequest
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistOverview
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistPage
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistPageSource
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistQueueAvailability
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistQueueCatalogToken
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistQueueMapper
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistQueueResolution
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistReference
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistSnapshot
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistViewOwner
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistViewToken
import io.github.onlinemo.meloxwatch.data.queue.QueuePageSource
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueMode
import io.github.onlinemo.meloxwatch.model.TrackKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

/** 只控制端口与时序；不会冒充Room、授权provider、解码或设备验收。 */
internal class PlaylistProducerFixture(count: Long = 205L) {
    val snapshot = PlaylistSnapshot("list", "members-v1", 7L, "Playlist", PlaylistKind.ORDINARY, count, 1L, 1L)
    val view = PlaylistViewToken("playlist-ui", snapshot, PlaylistViewOwner.UI)
    val playlists = PlaylistTestStore(view)
    val library = PlaylistTestLibrary()
    val mappedContexts = mutableListOf<PlaylistQueueCatalogToken>()
    val mappedSizes = mutableListOf<Int>()
    var mapRows: suspend (List<PlaylistMember>) -> List<PlaylistQueueResolution> = { members ->
        members.map { member ->
            val key = member.reference.trackKey ?: PlaylistQueueKeys.unresolved(checkNotNull(member.reference.placeholderId))
            val available = member.reference.trackKey != null
            PlaylistQueueResolution(
                member.memberId,
                QueueEntry(
                    "mapper-id", key, 999L, 999L, "Track ${member.ordinal}", 120_000L,
                    if (available) LocalLocationReference("location-${member.ordinal}", "content://test/${member.ordinal}") else null,
                ),
                if (available) PlaylistQueueAvailability.AVAILABLE else PlaylistQueueAvailability.UNRESOLVED,
            )
        }
    }
    val mapper = PlaylistQueueMapper { context, members ->
        mappedContexts.add(context)
        mappedSizes.add(members.size)
        mapRows(members)
    }

    fun command(): PlaylistQueueCommand = PlaylistQueueCommand(
        view, PlaylistQueueCatalogToken(library.view.viewId, library.view.revision, library.view.rulesRevision),
        "operation", null, 0L, null, null, QueueMode(), PlaylistQueueAction.REPLACE_COMPLETE_LIST,
    )

    fun producer(command: PlaylistQueueCommand = command()): PlaylistQueueProducer =
        PlaylistQueueProducer(playlists, library, mapper, command)
}

internal class PlaylistTestStore(initialView: PlaylistViewToken) : LocalPlaylistRepository {
    val detail = MutableStateFlow<PlaylistSnapshot?>(initialView.snapshot)
    val overviewState = MutableStateFlow<PlaylistOverview?>(PlaylistOverview(4L, 1L, 0L))
    val favoriteState = MutableStateFlow(false)
    val favoriteObserverCount = MutableStateFlow(0)
    var favoriteSnapshot = PlaylistSnapshot("system:favorites", "favorites", 0L, "Favorites", PlaylistKind.FAVORITES, 0L, 1L, 1L)
    val requests = mutableListOf<PlaylistMutationRequest>()
    val opened = mutableListOf<String>()
    val closed = mutableListOf<String>()
    val pages = mutableListOf<Long>()
    var adoptionCount = 0
    var closeFailures = 0
    var closeError: Exception? = null
    var expired = false
    var nextLease = 0
    var beforeOpenView: suspend () -> Unit = {}
    var pageTransform: (PlaylistPage) -> PlaylistPage = { it }
    var memberAt: (Long) -> PlaylistMember = { ordinal ->
        PlaylistMember("member-$ordinal", ordinal, PlaylistReference(trackKey = TrackKey(TrackKey.LOCAL_PROVIDER, "track-$ordinal")))
    }
    var adoptionTransform: (PlaylistViewToken) -> PlaylistViewToken = { it }
    var mutation: suspend (PlaylistMutationRequest, PlaylistPageSource?) -> PlaylistCommit = { _, _ ->
        error("Unexpected playlist write")
    }
    private val views = mutableMapOf(initialView.viewId to initialView)

    override suspend fun initialize(): PlaylistOverview = checkNotNull(overviewState.value)
    override suspend fun overview(): PlaylistOverview = checkNotNull(overviewState.value)
    override fun observeOverview(): Flow<PlaylistOverview?> = overviewState
    override suspend fun snapshot(playlistId: String): PlaylistSnapshot? = if (playlistId == favoriteSnapshot.playlistId) favoriteSnapshot
        else detail.value?.takeIf { it.playlistId == playlistId }
    override fun observePlaylist(playlistId: String): Flow<PlaylistSnapshot?> = detail
    override suspend fun isFavorite(trackKey: TrackKey): Boolean = favoriteState.value
    override fun observeFavorite(trackKey: TrackKey): Flow<Boolean> = flow {
        favoriteObserverCount.value += 1
        try {
            favoriteState.collect { emit(it) }
        } finally {
            favoriteObserverCount.value -= 1
        }
    }
    override suspend fun apply(request: PlaylistMutationRequest, source: PlaylistPageSource?): PlaylistCommit {
        requests.add(request)
        return mutation(request, source)
    }

    override suspend fun openCatalogView(): PlaylistCatalogView {
        val catalog = PlaylistCatalogView("catalog-${++nextLease}", overview().revision, 1L)
        opened.add(catalog.viewId)
        return catalog
    }

    override suspend fun catalogPage(view: PlaylistCatalogView, after: PlaylistCatalogCursor?, limit: Int): PlaylistCatalogPage =
        PlaylistCatalogPage(view, listOfNotNull(detail.value), null)

    override suspend fun openView(playlistId: String, owner: PlaylistViewOwner, fromCatalog: PlaylistCatalogView?): PlaylistViewToken {
        beforeOpenView()
        val snapshot = snapshot(playlistId) ?: throw PlaylistException(PlaylistIssue.NOT_FOUND)
        val lease = PlaylistViewToken("playlist-open-${++nextLease}", snapshot, owner)
        views[lease.viewId] = lease
        opened.add(lease.viewId)
        return lease
    }

    override suspend fun adoptView(viewId: String, owner: PlaylistViewOwner): PlaylistViewToken {
        adoptionCount += 1
        val original = views[viewId] ?: throw PlaylistException(PlaylistIssue.VIEW_EXPIRED)
        if (expired) throw PlaylistException(PlaylistIssue.VIEW_EXPIRED)
        val adopted = adoptionTransform(original.copy(viewId = "playlist-adopt-${++nextLease}", owner = owner))
        views[adopted.viewId] = adopted
        opened.add(adopted.viewId)
        return adopted
    }

    override suspend fun page(view: PlaylistViewToken, firstOrdinal: Long, limit: Int): PlaylistPage {
        if (expired || views[view.viewId] != view) throw PlaylistException(PlaylistIssue.VIEW_EXPIRED)
        pages.add(firstOrdinal)
        val end = minOf(view.snapshot.memberCount, firstOrdinal + limit)
        val members = (firstOrdinal until end).map(memberAt)
        return pageTransform(PlaylistPage(view, members, if (end == view.snapshot.memberCount) null else end))
    }

    override suspend fun closeView(viewId: String) {
        if (closeFailures > 0) {
            closeFailures -= 1
            throw closeError ?: PlaylistException(PlaylistIssue.STORAGE_UNAVAILABLE)
        }
        views.remove(viewId)
        closed.add(viewId)
    }

    override suspend fun maintain(): Boolean = false
}

internal class PlaylistTestLibrary : LocalLibraryRepository {
    val view = LibraryViewToken("library-ui", 9L, 3L, 20_005L, LibraryQuery(includeHidden = true), LibrarySort(), true, LibraryViewOwner.UI)
    val overviewState = MutableStateFlow(LibraryOverview(view.revision, view.rulesRevision, view.totalCount, view.totalCount, view.totalCount, 1L, true))
    val opened = mutableListOf<String>()
    val closed = mutableListOf<String>()
    var queueOpens = 0
    var closeFailures = 0
    var closeError: Exception? = null
    var expired = false
    var nextLease = 0
    var adoptionTransform: (LibraryViewToken) -> LibraryViewToken = { it }
    var initialization: suspend () -> LibraryOverview = { overviewState.value }
    private val views = mutableMapOf(view.viewId to view)

    override suspend fun initialize(): LibraryOverview = initialization()
    override suspend fun overview(): LibraryOverview = overviewState.value
    override fun observeOverview(): Flow<LibraryOverview> = overviewState
    override suspend fun openView(query: LibraryQuery, sort: LibrarySort, owner: LibraryViewOwner): LibraryViewToken {
        val lease = view.copy(viewId = "library-open-${++nextLease}", query = query, sort = sort, owner = owner)
        views[lease.viewId] = lease
        opened.add(lease.viewId)
        return lease
    }

    override suspend fun openQueueView(fromViewId: String): LibraryViewToken {
        queueOpens += 1
        val original = views[fromViewId] ?: throw LibraryException(LibraryIssue.VIEW_EXPIRED)
        if (expired) throw LibraryException(LibraryIssue.VIEW_EXPIRED)
        val lease = adoptionTransform(original.copy(viewId = "library-adopt-${++nextLease}", owner = LibraryViewOwner.QUEUE))
        views[lease.viewId] = lease
        opened.add(lease.viewId)
        return lease
    }

    override suspend fun closeView(viewId: String) {
        if (closeFailures > 0) {
            closeFailures -= 1
            throw closeError ?: LibraryException(LibraryIssue.STORAGE_UNAVAILABLE)
        }
        views.remove(viewId)
        closed.add(viewId)
    }

    override suspend fun source(sourceId: String): LibrarySource? = error("Unexpected source lookup")
    override suspend fun sourcesPage(afterSourceId: String?, limit: Int): LibrarySourcePage = error("Unexpected sources query")
    override suspend fun page(view: LibraryViewToken, after: LibraryCursor?, limit: Int): LibraryPage = error("Unexpected track page")
    override suspend fun previousPage(view: LibraryViewToken, before: LibraryCursor, limit: Int): LibraryPage = error("Unexpected previous page")
    override suspend fun groups(view: LibraryViewToken, kind: LibraryGroupKind, afterKey: String?, limit: Int): LibraryGroupPage = error("Unexpected groups query")
    override suspend fun folders(view: LibraryViewToken, parentNodeId: String?, afterNodeId: String?, limit: Int): LibraryFolderPage = error("Unexpected folders query")
    override suspend fun folder(view: LibraryViewToken, nodeId: String): LibraryFolder? = error("Unexpected folder lookup")
    override suspend fun sections(view: LibraryViewToken): List<LibrarySection> = error("Unexpected sections query")
    override suspend fun locate(view: LibraryViewToken, trackKey: TrackKey): LibraryLocation? = error("Unexpected selected track lookup")
    override fun queueSource(view: LibraryViewToken): QueuePageSource = error("Unexpected library queue source")
    override suspend fun resolveLocalRead(trackKey: TrackKey, expectedLocationId: String?, expectedContentRevision: Long?): LocalReadDescriptor = error("Unexpected audio read")
    override suspend fun rules(): LibraryRules = LibraryRules(view.rulesRevision, true)
    override suspend fun exclusionPage(afterRuleId: String?, limit: Int): LibraryExclusionPage = error("Unexpected exclusion query")
    override suspend fun setShortAudioFilter(expectedRulesRevision: Long, enabled: Boolean): LibraryRules = error("Unexpected filter write")
    override suspend fun addExclusion(expectedRulesRevision: Long, sourceId: String, nodeId: String, subtree: Boolean): LibraryRules = error("Unexpected exclusion write")
    override suspend fun removeExclusion(expectedRulesRevision: Long, ruleId: String): LibraryRules = error("Unexpected exclusion removal")
}
