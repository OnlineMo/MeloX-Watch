package io.github.onlinemo.meloxwatch.data.playlists

import io.github.onlinemo.meloxwatch.model.TrackKey
import kotlinx.coroutines.flow.Flow

/** 离线关系存储；不读取音频、扫描、取得URI授权、联网或修改活动队列。 */
interface LocalPlaylistRepository {
    suspend fun initialize(): PlaylistOverview
    suspend fun overview(): PlaylistOverview
    fun observeOverview(): Flow<PlaylistOverview?>
    suspend fun snapshot(playlistId: String): PlaylistSnapshot?
    fun observePlaylist(playlistId: String): Flow<PlaylistSnapshot?>
    suspend fun isFavorite(trackKey: TrackKey): Boolean
    fun observeFavorite(trackKey: TrackKey): Flow<Boolean>

    /**
     * 外部source在事务外完整逐页读取；适用库/扫描围栏在最终同Room事务重核。
     * 文件夹同步保留交集成员身份/人工序；换绑定明确重建。提交点之后取消不撤销receipt。
     */
    suspend fun apply(request: PlaylistMutationRequest, source: PlaylistPageSource? = null): PlaylistCommit
    suspend fun openCatalogView(): PlaylistCatalogView
    suspend fun catalogPage(
        view: PlaylistCatalogView,
        after: PlaylistCatalogCursor? = null,
        limit: Int = PlaylistLimits.PAGE_SIZE,
    ): PlaylistCatalogPage
    suspend fun openView(
        playlistId: String,
        owner: PlaylistViewOwner = PlaylistViewOwner.UI,
        fromCatalog: PlaylistCatalogView? = null,
    ): PlaylistViewToken
    suspend fun adoptView(viewId: String, owner: PlaylistViewOwner = PlaylistViewOwner.QUEUE): PlaylistViewToken
    suspend fun page(
        view: PlaylistViewToken,
        firstOrdinal: Long = 0L,
        limit: Int = PlaylistLimits.PAGE_SIZE,
    ): PlaylistPage
    suspend fun closeView(viewId: String)

    /** 每次至多回收100个成员或一个元数据代次；调用者决定后台维护生命周期。 */
    suspend fun maintain(): Boolean
}
