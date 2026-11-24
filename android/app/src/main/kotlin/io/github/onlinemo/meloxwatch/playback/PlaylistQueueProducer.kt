package io.github.onlinemo.meloxwatch.playback

import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.library.LibraryQuery
import io.github.onlinemo.meloxwatch.data.library.LibraryViewOwner
import io.github.onlinemo.meloxwatch.data.library.LibraryViewToken
import io.github.onlinemo.meloxwatch.data.library.LocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.playlists.LocalPlaylistRepository
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistException
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistLimits
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMember
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistPage
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistQueueAvailability
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistQueueCatalogToken
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistQueueMapper
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistQueueResolution
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistViewOwner
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistViewToken
import io.github.onlinemo.meloxwatch.data.queue.QueueCommit
import io.github.onlinemo.meloxwatch.data.queue.QueueInputPage
import io.github.onlinemo.meloxwatch.data.queue.QueuePageSource
import io.github.onlinemo.meloxwatch.data.queue.QueueReplacementRequest
import io.github.onlinemo.meloxwatch.model.FrozenQueueView
import io.github.onlinemo.meloxwatch.model.QueueLimits
import io.github.onlinemo.meloxwatch.model.QueueMode
import io.github.onlinemo.meloxwatch.model.QueueMutation
import io.github.onlinemo.meloxwatch.model.QueueMutationRequest
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class PlaylistQueueAction { REPLACE_COMPLETE_LIST, INSERT_NEXT, INSERT_AND_PLAY }

data class PlaylistQueueSelection(val memberId: String, val ordinal: Long) {
    init {
        require(memberId.isNotBlank() && memberId.length <= PlaylistLimits.MAX_IDENTIFIER_CHARACTERS)
        require(ordinal >= 0L)
    }
}

/** 界面传入它实际展示的两个冻结视图；不能在服务端改为查询最新歌单或曲库。 */
data class PlaylistQueueCommand(
    val view: PlaylistViewToken,
    val catalog: PlaylistQueueCatalogToken,
    val operationId: String,
    val expectedGenerationId: String?,
    val expectedQueueRevision: Long,
    val expectedCurrentEntryId: String?,
    val selection: PlaylistQueueSelection?,
    val mode: QueueMode,
    val action: PlaylistQueueAction,
) {
    init {
        require(view.owner == PlaylistViewOwner.UI)
        require(view.snapshot.revision >= 0L && view.snapshot.memberCount > 0L)
        require(listOf(view.viewId, view.snapshot.playlistId, view.snapshot.generationId, catalog.id, operationId).all {
            it.isNotBlank() && it.length <= PlaylistLimits.MAX_IDENTIFIER_CHARACTERS
        })
        require(catalog.revision >= 0L && catalog.rulesRevision >= 0L && expectedQueueRevision >= 0L)
        require(expectedGenerationId == null ||
            (expectedGenerationId.isNotBlank() && expectedGenerationId.length <= PlaylistLimits.MAX_IDENTIFIER_CHARACTERS))
        require(expectedCurrentEntryId == null ||
            (expectedCurrentEntryId.isNotBlank() && expectedCurrentEntryId.length <= PlaylistLimits.MAX_IDENTIFIER_CHARACTERS))
        require(expectedGenerationId != null || (expectedQueueRevision == 0L && expectedCurrentEntryId == null))
        require(selection == null || selection.ordinal < view.snapshot.memberCount)
    }
}

/** 此命名空间不登记为曲库曲目；队列恢复时也不能按描述、同名或导入URI找资源。 */
object PlaylistQueueKeys {
    fun unresolved(placeholderId: String): TrackKey {
        require(placeholderId.isNotBlank() && placeholderId.length <= PlaylistLimits.MAX_IDENTIFIER_CHARACTERS)
        val digest = MessageDigest.getInstance("SHA-256").digest(placeholderId.toByteArray(Charsets.UTF_8))
        return TrackKey(TrackKey.LOCAL_PROVIDER, "playlist-unresolved:${digest.hexString()}")
    }
}

/**
 * 完整列表逐页构建独立队列；页面大小是内存预算，不是歌单或队列数量上限。
 * 首次真正读页才取得两个独立QUEUE lease，因此receipt重放无需旧界面或原文件。
 * 调用者在finally中关闭producer；取消后的收尾由服务拥有者在短NonCancellable边界执行。
 */
internal class PlaylistQueueProducer(
    private val playlists: LocalPlaylistRepository,
    private val library: LocalLibraryRepository,
    private val mapper: PlaylistQueueMapper,
    private val command: PlaylistQueueCommand,
) {
    private val singleInsertion = command.action != PlaylistQueueAction.REPLACE_COMPLETE_LIST && command.selection != null
    val frozenView = FrozenQueueView(
        playlistQueueViewId(command),
        command.view.snapshot.revision,
        if (singleInsertion) 1L else command.view.snapshot.memberCount,
    )
    private val reads = Mutex()
    private var playlistLease: PlaylistViewToken? = null
    private var catalogLease: LibraryViewToken? = null
    private var selectedMember: PlaylistMember? = null
    private var ready = false
    private var nextOffset = 0L
    private var closed = false

    val source = QueuePageSource { offset, limit ->
        reads.withLock {
            check(!closed)
            require(limit in 1..QueueLimits.PAGE_SIZE && offset == nextOffset && offset < frozenView.totalCount)
            if (!ready) adopt()
            val expectedSize = minOf(limit.toLong(), frozenView.totalCount - offset).toInt()
            val members = if (singleInsertion) {
                listOf(checkNotNull(selectedMember))
            } else {
                val lease = checkNotNull(playlistLease)
                val page = playlists.page(lease, offset, expectedSize)
                verifyPage(page, lease, offset, expectedSize)
                page.members
            }
            val catalog = checkNotNull(catalogLease)
            val context = PlaylistQueueCatalogToken(catalog.viewId, catalog.revision, catalog.rulesRevision)
            val resolutions = mapper.map(context, members)
            currentCoroutineContext().ensureActive()
            verifyPlaylistQueueResolutions(members, resolutions)
            val entries = resolutions.mapIndexed { index, resolution ->
                val ordinal = offset + index
                resolution.entry.copy(
                    queueEntryId = UUID.randomUUID().toString(),
                    originalOrder = ordinal,
                    playbackOrder = ordinal,
                )
            }
            nextOffset += entries.size
            QueueInputPage(frozenView, offset, entries)
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    suspend fun commit(coordinator: QueuePlaybackCoordinator): QueueCommit {
        if (command.action == PlaylistQueueAction.REPLACE_COMPLETE_LIST) {
            return coordinator.replace(
                QueueReplacementRequest(
                    operationId = command.operationId,
                    expectedGenerationId = command.expectedGenerationId,
                    expectedQueueRevision = command.expectedQueueRevision,
                    view = frozenView,
                    mode = command.mode,
                    startOriginalOrder = command.selection?.ordinal ?: 0L,
                ),
                source,
                explicitPlay = true,
            )
        }
        val startsInserted = command.action == PlaylistQueueAction.INSERT_AND_PLAY
        return coordinator.mutate(
            QueueMutationRequest(
                operationId = command.operationId,
                expectedGenerationId = command.expectedGenerationId,
                expectedQueueRevision = command.expectedQueueRevision,
                expectedCurrentEntryId = command.expectedCurrentEntryId,
                mutation = QueueMutation.InsertNext(frozenView, command.expectedCurrentEntryId, startsInserted),
            ),
            source,
            explicitPlay = startsInserted,
        )
    }

    private suspend fun adopt() {
        val adoptedPlaylist = playlistLease ?: playlists.adoptView(command.view.viewId, PlaylistViewOwner.QUEUE)
        playlistLease = adoptedPlaylist
        if (adoptedPlaylist.owner != PlaylistViewOwner.QUEUE || adoptedPlaylist.snapshot != command.view.snapshot) {
            throw PlaylistException(PlaylistIssue.SOURCE_CHANGED)
        }
        val adoptedCatalog = catalogLease ?: library.openQueueView(command.catalog.id)
        catalogLease = adoptedCatalog
        if (adoptedCatalog.owner != LibraryViewOwner.QUEUE || adoptedCatalog.revision != command.catalog.revision ||
            adoptedCatalog.rulesRevision != command.catalog.rulesRevision ||
            adoptedCatalog.query != LibraryQuery(includeHidden = true)) {
            throw LibraryException(LibraryIssue.SOURCE_CHANGED)
        }
        val selection = command.selection
        if (selection != null) {
            val page = playlists.page(adoptedPlaylist, selection.ordinal, 1)
            verifyPage(page, adoptedPlaylist, selection.ordinal, 1)
            val member = page.members.single()
            if (member.memberId != selection.memberId) throw PlaylistException(PlaylistIssue.SOURCE_CHANGED)
            selectedMember = member
        }
        ready = true
    }

    private fun verifyPage(page: PlaylistPage, lease: PlaylistViewToken, offset: Long, expectedSize: Int) {
        if (page.view != lease) throw PlaylistException(PlaylistIssue.SOURCE_CHANGED)
        if (page.members.size != expectedSize) throw PlaylistException(PlaylistIssue.INCOMPLETE)
        for ((index, member) in page.members.withIndex()) {
            if (member.ordinal != offset + index) throw PlaylistException(PlaylistIssue.SOURCE_CHANGED)
        }
        val following = offset + expectedSize
        val expectedNext = if (following == lease.snapshot.memberCount) null else following
        if (page.nextOrdinal != expectedNext) throw PlaylistException(PlaylistIssue.INCOMPLETE)
    }

    suspend fun close() {
        reads.withLock {
            closed = true
            ready = false
            selectedMember = null
            var failure: Exception? = null
            val relationLease = playlistLease
            if (relationLease != null) {
                try {
                    playlists.closeView(relationLease.viewId)
                    playlistLease = null
                } catch (error: Exception) {
                    failure = error
                }
            }
            val resourceLease = catalogLease
            if (resourceLease != null) {
                try {
                    library.closeView(resourceLease.viewId)
                    catalogLease = null
                } catch (error: Exception) {
                    val previous = failure
                    if (previous == null) failure = error else if (previous !== error) previous.addSuppressed(error)
                }
            }
            // 一个数据库清理失败也释放另一lease；失败lease保留到调用者重试close。
            failure?.let { throw it }
        }
    }
}

/** 页面展示和入队共享同一匹配边界，避免一个入口把未匹配描述当成可播资源。 */
internal fun verifyPlaylistQueueResolutions(members: List<PlaylistMember>, resolutions: List<PlaylistQueueResolution>) {
    if (resolutions.size != members.size) throw PlaylistException(PlaylistIssue.INCOMPLETE)
    for (index in members.indices) {
        val member = members[index]
        val resolution = resolutions[index]
        val entry = resolution.entry
        if (resolution.memberId != member.memberId || entry.trackKey.providerId != TrackKey.LOCAL_PROVIDER ||
            entry.accountId != null || entry.displayName?.length?.let { it > PlaylistLimits.MAX_FIELD_CHARACTERS } == true) {
            throw PlaylistException(PlaylistIssue.SOURCE_CHANGED)
        }
        val key = member.reference.trackKey
        if (key != null) {
            if (entry.trackKey != key || resolution.availability == PlaylistQueueAvailability.UNRESOLVED) {
                throw PlaylistException(PlaylistIssue.SOURCE_CHANGED)
            }
        } else {
            val expectedKey = PlaylistQueueKeys.unresolved(checkNotNull(member.reference.placeholderId))
            if (resolution.availability != PlaylistQueueAvailability.UNRESOLVED || entry.location != null ||
                entry.trackKey != expectedKey) {
                throw PlaylistException(PlaylistIssue.SOURCE_CHANGED)
            }
        }
        if (resolution.availability == PlaylistQueueAvailability.AVAILABLE && entry.location == null) {
            throw PlaylistException(PlaylistIssue.SOURCE_UNAVAILABLE)
        }
    }
}

private fun playlistQueueViewId(command: PlaylistQueueCommand): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun field(value: String?) {
        val bytes = value?.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes?.size ?: -1).array())
        if (bytes != null) digest.update(bytes)
    }
    field(command.view.viewId)
    field(command.view.snapshot.playlistId)
    field(command.view.snapshot.generationId)
    field(command.view.snapshot.revision.toString())
    field(command.view.snapshot.memberCount.toString())
    field(command.view.snapshot.kind.name)
    field(command.catalog.id)
    field(command.catalog.revision.toString())
    field(command.catalog.rulesRevision.toString())
    field(command.selection?.memberId)
    field(command.selection?.ordinal?.toString())
    field(command.mode.shuffle.toString())
    field(command.mode.repeat.name)
    field(command.mode.cycleLimit?.toString())
    field(command.action.name)
    return "playlist-queue:${digest.digest().hexString()}"
}

private fun ByteArray.hexString(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
