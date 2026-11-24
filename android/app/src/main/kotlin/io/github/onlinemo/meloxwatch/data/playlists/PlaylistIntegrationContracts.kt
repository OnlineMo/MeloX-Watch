package io.github.onlinemo.meloxwatch.data.playlists

import io.github.onlinemo.meloxwatch.model.QueueEntry

/** 预览、完整库查询和交换匹配共用的版本；确认后库或规则变化必须重新预览。 */
data class PlaylistLibraryRevision(val catalogRevision: Long, val rulesRevision: Long) {
    init {
        require(catalogRevision >= 0L && rulesRevision >= 0L)
    }
}

/** 仅在最终同一Room事务读真实库/规则版本；无provider/文件/网络IO，不缓存预检查结果。 */
fun interface PlaylistLibraryTransactionFenceVerifier {
    suspend fun verify(revision: PlaylistLibraryRevision)
}

/** LP-09目录同步必须在同Room短提交点逐字段重核，不能信任调用方的成功boolean。 */
data class PlaylistCatalogFence(
    val catalogRevision: Long,
    val rulesRevision: Long,
    val sourceId: String,
    val sourceRevision: Long,
    val authEpoch: Long,
    val scanId: String,
) {
    init {
        require(catalogRevision >= 0L && rulesRevision >= 0L && sourceRevision >= 0L && authEpoch >= 0L)
        requirePlaylistIdentifier(sourceId)
        requirePlaylistIdentifier(scanId)
    }
}

/**
 * 仅在持有同一Room事务时逐字段读真实库、来源、授权及完整扫描记录。
 * 失败/取消/半范围扫描必须拒绝；不做provider/文件/网络IO或跨数据库检查。
 */
fun interface PlaylistTransactionFenceVerifier {
    suspend fun verify(fence: PlaylistCatalogFence)
}

/** 完整、稳定排序的已索引输入；半扫、撤权或任何围栏变化不能发布成员移除。 */
data class PlaylistFolderSynchronization(
    val binding: PlaylistFolderBinding,
    val fence: PlaylistCatalogFence,
    val input: FrozenPlaylistInput,
) {
    init {
        require(binding.sourceId == fence.sourceId)
    }
}

enum class PlaylistQueueAvailability { AVAILABLE, MISSING, NEEDS_AUTHORIZATION, UNAVAILABLE, UNRESOLVED }
data class PlaylistQueueCatalogToken(val id: String, val revision: Long, val rulesRevision: Long)
data class PlaylistQueueResolution(
    val memberId: String,
    val entry: QueueEntry,
    val availability: PlaylistQueueAvailability,
)

/**
 * LP-09集成层提供真实曲库快照；每批输入和结果一一对应且保序。
 * placeholder必须返回显式UNRESOLVED结果，不能过滤、截断、猜同名文件或发网络请求。
 */
fun interface PlaylistQueueMapper {
    suspend fun map(context: PlaylistQueueCatalogToken, members: List<PlaylistMember>): List<PlaylistQueueResolution>
}
