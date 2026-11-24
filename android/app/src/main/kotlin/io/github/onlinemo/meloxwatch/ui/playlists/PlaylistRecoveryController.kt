package io.github.onlinemo.meloxwatch.ui.playlists

import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCommit
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistLimits
import io.github.onlinemo.meloxwatch.model.TrackKey

/** 页面展示实际选定曲目；来源围栏与完整确认请求仅由控制器持有。 */
data class PlaylistReassociationPreview(
    val previewId: String,
    val target: PlaylistRecoveryTarget,
    val replacement: TrackKey,
    val replacementTitle: String,
    val revision: PlaylistPreviewRevision,
) {
    init {
        require(previewId.isNotBlank() && previewId.length <= PlaylistLimits.MAX_IDENTIFIER_CHARACTERS)
        require(replacement.providerId == TrackKey.LOCAL_PROVIDER)
        require(replacement.trackId.isNotBlank() && replacement.trackId.length <= PlaylistLimits.MAX_IDENTIFIER_CHARACTERS)
        require(replacementTitle.length <= PlaylistLimits.MAX_FIELD_CHARACTERS)
    }
}

interface PlaylistRecoveryController {
    /** 新开至多一个自有曲库lease；只定位明确选择的local key，不用同名或描述URI猜测。 */
    suspend fun preview(target: PlaylistRecoveryTarget, replacement: TrackKey): PlaylistReassociationPreview
    /** 首次确认冻结operation与完整mutation；同进程未知结果重试回读原持久receipt。 */
    suspend fun confirm(operationId: String, previewId: String, confirmed: Boolean): PlaylistCommit
    /** 释放预览lease；已提交的最后一次意图可同进程显式重试，不自动恢复确认或写入。 */
    suspend fun closePreview(previewId: String)
}
