package io.github.onlinemo.meloxwatch.data.playlists

import io.github.onlinemo.meloxwatch.model.TrackKey

object PlaylistLimits {
    const val PAGE_SIZE = 100
    const val MAX_NAME_CODE_POINTS = 80
    const val MAX_FIELD_CHARACTERS = 4096
    const val MAX_IDENTIFIER_CHARACTERS = 128
    const val UI_VIEWS = 3
    const val QUEUE_VIEWS = 1
    const val TRANSFER_VIEWS = 4
    const val VIEW_LEASE_MS = 10 * 60 * 1000L
    const val FAVORITES_ID = "system:favorites"
}

enum class PlaylistKind { ORDINARY, FOLDER, FAVORITES }
enum class PlaylistViewOwner { UI, QUEUE, TRANSFER }
enum class PlaylistReferenceIssue { UNMATCHED, REQUIRES_REASSOCIATION, NETWORK_REFERENCE }

/** 提示文字不是授权或播放器资源；未匹配项不能伪造成已经存在的曲目。 */
data class PlaylistReference(
    val trackKey: TrackKey? = null,
    val placeholderId: String? = null,
    val issue: PlaylistReferenceIssue? = null,
    val titleHint: String? = null,
    val artistHint: String? = null,
    val albumHint: String? = null,
    val relativePathHint: String? = null,
    val descriptionHint: String? = null,
    val durationMs: Long? = null,
    val cueReference: io.github.onlinemo.meloxwatch.model.LocalCueReference? = null,
) {
    init {
        require((trackKey == null) != (placeholderId == null))
        require((trackKey == null) == (issue != null))
        trackKey?.let {
            require(it.providerId == TrackKey.LOCAL_PROVIDER)
            requirePlaylistIdentifier(it.trackId)
        }
        placeholderId?.let(::requirePlaylistIdentifier)
        require(listOf(titleHint, artistHint, albumHint, relativePathHint, descriptionHint).all {
            it == null || it.length <= PlaylistLimits.MAX_FIELD_CHARACTERS
        })
        require(durationMs == null || durationMs > 0L)
        require(cueReference == null || !cueReference.isBound && (trackKey == null || trackKey == cueReference.trackKey))
    }
}

data class PlaylistFolderBinding(val sourceId: String, val nodeId: String, val subtree: Boolean = true) {
    init {
        requirePlaylistIdentifier(sourceId)
        requirePlaylistIdentifier(nodeId)
    }
}

data class PlaylistSnapshot(
    val playlistId: String,
    val generationId: String,
    val revision: Long,
    val name: String,
    val kind: PlaylistKind,
    val memberCount: Long,
    val createdEpochMs: Long,
    val updatedEpochMs: Long,
    val folderBinding: PlaylistFolderBinding? = null,
)

data class PlaylistMember(val memberId: String, val ordinal: Long, val reference: PlaylistReference)
data class PlaylistOverview(val revision: Long, val playlistCount: Long, val favoriteCount: Long)

/** 关系及顺序固定；当前文件可用性由集成层的同版本曲库快照补充。 */
data class PlaylistViewToken(val viewId: String, val snapshot: PlaylistSnapshot, val owner: PlaylistViewOwner)
data class PlaylistPage(val view: PlaylistViewToken, val members: List<PlaylistMember>, val nextOrdinal: Long?)
data class PlaylistCatalogView(val viewId: String, val revision: Long, val totalCount: Long)
data class PlaylistCatalogCursor(val viewId: String, val revision: Long, val playlistId: String)
data class PlaylistCatalogPage(
    val view: PlaylistCatalogView,
    val playlists: List<PlaylistSnapshot>,
    val nextCursor: PlaylistCatalogCursor?,
)

/** id 必须标识完整有序输入；内容或选择意图改变时生产者必须换 id。 */
data class FrozenPlaylistInput(val id: String, val revision: Long, val totalCount: Long) {
    init {
        requirePlaylistIdentifier(id)
        require(revision >= 0L && totalCount >= 0L)
    }
}

data class PlaylistInputPage(
    val view: FrozenPlaylistInput,
    val offset: Long,
    val references: List<PlaylistReference>,
    val endReached: Boolean,
)

/** 调用者拥有文件/库快照，单次 read 不超过100项，末页必须明确完整结束。 */
fun interface PlaylistPageSource {
    suspend fun read(offset: Long, limit: Int): PlaylistInputPage
}

sealed interface PlaylistMutation {
    data class Create(val name: String, val input: FrozenPlaylistInput? = null) : PlaylistMutation
    data class CreateFolder(val name: String, val synchronization: PlaylistFolderSynchronization) : PlaylistMutation
    data class Rename(val name: String) : PlaylistMutation
    data class Delete(val confirmed: Boolean) : PlaylistMutation
    data class Add(val input: FrozenPlaylistInput) : PlaylistMutation
    data class Remove(val memberId: String, val confirmed: Boolean) : PlaylistMutation
    data class Move(val memberId: String, val destinationOrdinal: Long) : PlaylistMutation
    data class SetFavorite(val reference: PlaylistReference, val enabled: Boolean) : PlaylistMutation
    data class SynchronizeFolder(val synchronization: PlaylistFolderSynchronization) : PlaylistMutation
    data class RebindFolder(val synchronization: PlaylistFolderSynchronization, val confirmed: Boolean) : PlaylistMutation
    data class ReplaceReference(val memberId: String, val reference: PlaylistReference, val confirmed: Boolean) : PlaylistMutation
}

data class PlaylistMutationRequest(
    val operationId: String,
    val playlistId: String,
    val expectedRevision: Long,
    val mutation: PlaylistMutation,
    val expectedLibraryRevision: PlaylistLibraryRevision? = null,
    val expectedSourceFence: PlaylistCatalogFence? = null,
) {
    init {
        requirePlaylistIdentifier(operationId)
        requirePlaylistIdentifier(playlistId)
        require(expectedRevision >= 0L)
        when (mutation) {
            is PlaylistMutation.Remove -> requirePlaylistIdentifier(mutation.memberId)
            is PlaylistMutation.Move -> {
                requirePlaylistIdentifier(mutation.memberId)
                require(mutation.destinationOrdinal >= 0L)
            }
            is PlaylistMutation.SetFavorite -> require(mutation.reference.trackKey != null)
            is PlaylistMutation.ReplaceReference -> {
                requirePlaylistIdentifier(mutation.memberId)
                require(mutation.reference.trackKey != null)
            }
            else -> Unit
        }
    }
}

enum class PlaylistOperationResult {
    CREATED, RENAMED, DELETED, ADDED, REMOVED, MOVED, FAVORITE_SET, UNCHANGED,
    SYNCHRONIZED, REBOUND, REFERENCE_REPLACED,
}

/** receipt保留原操作结果，snapshot反映当前列表；重放不会恢复被删除/后来编辑的列表。 */
data class PlaylistCommit(
    val playlistId: String,
    val appliedRevision: Long,
    val result: PlaylistOperationResult,
    val snapshot: PlaylistSnapshot?,
    val wasReplay: Boolean,
    val addedCount: Long = 0L,
    val removedCount: Long = 0L,
    val duplicateCount: Long = 0L,
    val sameNameCount: Long = 0L,
)

enum class PlaylistIssue {
    BUSY, CONFLICT, INVALID_INPUT, NOT_FOUND, AUTOMATIC_MEMBERS, DUPLICATE_REFERENCE, VIEW_EXPIRED,
    SOURCE_CHANGED, SOURCE_UNAVAILABLE, INCOMPLETE, STORAGE_FULL, STORAGE_UNAVAILABLE, RECOVERY_REQUIRED, REVISION_EXHAUSTED,
}

class PlaylistException(val issue: PlaylistIssue, cause: Throwable? = null) : Exception(issue.name, cause)

internal fun requirePlaylistIdentifier(value: String) {
    require(value.isNotBlank() && value.length <= PlaylistLimits.MAX_IDENTIFIER_CHARACTERS)
}
