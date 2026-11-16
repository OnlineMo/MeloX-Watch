package io.github.onlinemo.meloxwatch.ui.playlists

import io.github.onlinemo.meloxwatch.data.playlists.FrozenPlaylistInput
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCatalogFence
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCommit
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistFolderBinding
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistLimits
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMember
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistPageSource
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistQueueAvailability
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistSnapshot
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistViewToken

/** 应用接线层实现这些端口；仓库不依赖UI，页面也不直接解析文档或查询provider。 */
data class PlaylistFolderChoice(val binding: PlaylistFolderBinding, val label: String?)

data class FolderPlaylistPreview(
    val previewId: String,
    val choice: PlaylistFolderChoice,
    val fence: PlaylistCatalogFence,
    val memberCount: Long,
    val addedCount: Long,
    val removedCount: Long,
) {
    init {
        requirePreviewId(previewId)
        require(memberCount >= 0L && addedCount >= 0L && removedCount >= 0L)
        require(choice.binding.sourceId == fence.sourceId)
    }
}

data class FolderPlaylistCreateRequest(
    val operationId: String,
    val playlistId: String,
    val name: String,
    val previewId: String,
)

data class FolderPlaylistUpdateRequest(
    val operationId: String,
    val playlistId: String,
    val expectedRevision: Long,
    val previewId: String,
)

interface FolderPlaylistController {
    /** 只读既有索引；base用于预览同步差异，不能以半扫或失败扫描作删除依据。 */
    suspend fun preview(choice: PlaylistFolderChoice, base: PlaylistSnapshot? = null): FolderPlaylistPreview
    suspend fun create(request: FolderPlaylistCreateRequest): PlaylistCommit
    /** 保留仍在目录内的旧成员顺序，新项按已声明的稳定规则追加。 */
    suspend fun synchronize(request: FolderPlaylistUpdateRequest): PlaylistCommit
    /** 只在用户另行确认换绑定后重建；与旧活动播放队列独立。 */
    suspend fun rebind(request: FolderPlaylistUpdateRequest): PlaylistCommit
    suspend fun closePreview(previewId: String)
}

data class PlaylistPreviewRevision(val catalogRevision: Long, val rulesRevision: Long) {
    init { require(catalogRevision >= 0L && rulesRevision >= 0L) }
}

data class KeywordPlaylistPreview(
    val previewId: String,
    val keyword: String,
    val revision: PlaylistPreviewRevision,
    val memberCount: Long,
    val requiresBroadConfirmation: Boolean,
) {
    init {
        requirePreviewId(previewId)
        require(keyword.isNotBlank() && keyword.length <= PlaylistLimits.MAX_FIELD_CHARACTERS)
        require(memberCount >= 0L)
    }
}

data class KeywordPlaylistCreateRequest(
    val operationId: String,
    val playlistId: String,
    val name: String,
    val previewId: String,
    val broadMatchConfirmed: Boolean,
    val emptyMatchConfirmed: Boolean,
)

interface KeywordPlaylistController {
    /** 字面连续字符串只匹配已授权相对路径；不以普通曲目search或遍历provider替代。 */
    suspend fun preview(keyword: String): KeywordPlaylistPreview
    /** 同库短事务再次核验revision；变化时拒绝旧预览且保留名称/关键词草稿。 */
    suspend fun create(request: KeywordPlaylistCreateRequest): PlaylistCommit
    suspend fun closePreview(previewId: String)
}

enum class PlaylistTransferFormat { M3U8, M3U, JSON }
enum class PlaylistTransferEncoding { UTF8, GB18030 }

/** 仅系统选择器/应用接线层签发；控制器持有真实URI及授权，页面不能从文字构造访问权。 */
data class AuthorizedPlaylistDocument(val selectionId: String, val label: String?) {
    init { requirePreviewId(selectionId) }
}

data class PlaylistImportTarget(val playlistId: String, val expectedRevision: Long) {
    init {
        requirePreviewId(playlistId)
        require(expectedRevision >= 0L)
    }
}

enum class PlaylistImportMemberIssue { NONE, UNMATCHED, AMBIGUOUS, BLOCKED_REFERENCE, REQUIRES_REASSOCIATION }

data class PlaylistImportRow(
    val ordinal: Long,
    val title: String?,
    val artist: String?,
    val availability: PlaylistQueueAvailability,
    val issue: PlaylistImportMemberIssue,
) {
    init {
        require(ordinal >= 0L)
        require(listOf(title, artist).all { it == null || it.length <= PlaylistLimits.MAX_FIELD_CHARACTERS })
    }
}

data class PlaylistImportPreview(
    val previewId: String,
    val format: PlaylistTransferFormat,
    val encoding: PlaylistTransferEncoding,
    val nameHint: String?,
    val schemaVersion: Int?,
    val revision: PlaylistPreviewRevision,
    val target: PlaylistImportTarget?,
    val memberCount: Long,
    val matchedCount: Long,
    val unresolvedCount: Long,
    val ambiguousCount: Long,
    val blockedReferenceCount: Long,
    val duplicateCount: Long,
) {
    init {
        requirePreviewId(previewId)
        require(nameHint == null || nameHint.length <= PlaylistLimits.MAX_FIELD_CHARACTERS)
        require(memberCount >= 0L && matchedCount >= 0L && unresolvedCount >= 0L)
        require(matchedCount <= memberCount && unresolvedCount == memberCount - matchedCount)
        require(ambiguousCount in 0L..unresolvedCount && blockedReferenceCount in 0L..unresolvedCount)
        require(duplicateCount in 0L..memberCount)
        require(format == PlaylistTransferFormat.M3U || encoding == PlaylistTransferEncoding.UTF8)
    }
}

data class PlaylistImportPage(
    val previewId: String,
    val firstOrdinal: Long,
    val rows: List<PlaylistImportRow>,
    val nextOrdinal: Long?,
)

sealed interface PlaylistImportDestination {
    data class New(val playlistId: String, val name: String) : PlaylistImportDestination
    data class Merge(val target: PlaylistImportTarget) : PlaylistImportDestination
}

data class PlaylistImportConfirmation(
    val operationId: String,
    val previewId: String,
    val destination: PlaylistImportDestination,
    val unresolvedConfirmed: Boolean,
)

/** 这是已完成、复读核验过的目标文档提交；writer的序列化返回值不能构造成功提示。 */
data class PlaylistExportReceipt(
    val operationId: String,
    val memberCount: Long,
    val byteCount: Long,
    val sha256: String,
    val destinationLabel: String?,
) {
    init {
        requirePreviewId(operationId)
        require(memberCount >= 0L && byteCount >= 0L)
        require(sha256.matches(Regex("[0-9a-f]{64}")))
    }
}

interface PlaylistExchangeController {
    /** 校验、分批staging与真实库匹配都可取消；原文中的URI/绝对路径永远不扩大授权。 */
    suspend fun inspectImport(
        format: PlaylistTransferFormat,
        encoding: PlaylistTransferEncoding,
        authorizedDocument: AuthorizedPlaylistDocument,
        target: PlaylistImportTarget? = null,
    ): PlaylistImportPreview
    suspend fun importPage(previewId: String, firstOrdinal: Long, limit: Int = PlaylistLimits.PAGE_SIZE): PlaylistImportPage
    /** 默认新建；合并只对指定普通歌单按exact target revision保旧序后追加，不替换整库。 */
    suspend fun confirmImport(request: PlaylistImportConfirmation): PlaylistCommit
    /** 对空列表同样保持不可变lease；目标授权、暂存、核验、提交和失败清理由接线层负责。 */
    suspend fun export(
        operationId: String,
        view: PlaylistViewToken,
        authorizedDestination: AuthorizedPlaylistDocument,
        format: PlaylistTransferFormat,
        encoding: PlaylistTransferEncoding,
    ): PlaylistExportReceipt
    suspend fun closePreview(previewId: String)
}

enum class PlaylistRecoveryAction { REAUTHORIZE, REASSOCIATE }

data class PlaylistRecoveryTarget(
    val view: PlaylistViewToken,
    val member: PlaylistMember,
    val availability: PlaylistQueueAvailability,
    val action: PlaylistRecoveryAction,
)

/** 已冻结的单项/多选输入；逐页source可重试，取消/离页只释放输入，不改变曲库或文件。 */
interface PlaylistAddSelection {
    val input: FrozenPlaylistInput
    val label: String?
    val source: PlaylistPageSource
    suspend fun close()
}

private fun requirePreviewId(value: String) {
    require(value.isNotBlank() && value.length <= PlaylistLimits.MAX_IDENTIFIER_CHARACTERS)
}
