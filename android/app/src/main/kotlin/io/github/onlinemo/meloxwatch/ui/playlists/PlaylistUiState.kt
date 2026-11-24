package io.github.onlinemo.meloxwatch.ui.playlists

import io.github.onlinemo.meloxwatch.data.library.LibraryViewToken
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCatalogPage
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCommit
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistKind
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMember
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistOverview
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistPage
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistQueueAvailability
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistSnapshot
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistViewToken
import io.github.onlinemo.meloxwatch.playback.PlaylistQueueCommand

enum class PlaylistScreen {
    CATALOG, DETAILS, MEMBER, NAME, DELETE, REMOVE, MOVE, FOLDER, KEYWORD, ADD_TARGETS, ADD_CONFIRM, IMPORT, EXPORT,
}

enum class PlaylistNamePurpose { CREATE, RENAME, FOLDER, KEYWORD }
enum class FolderPlaylistPurpose { CREATE, SYNCHRONIZE, REBIND }

enum class PlaylistUiIssue {
    BUSY, CONFLICT, INVALID_NAME, INVALID_POSITION, INVALID_KEYWORD, INVALID_INPUT, NOT_FOUND, AUTOMATIC_MEMBERS,
    VIEW_EXPIRED, SOURCE_CHANGED, SOURCE_UNAVAILABLE, INCOMPLETE_SCAN, STORAGE_FULL, STORAGE_UNAVAILABLE,
    RECOVERY_REQUIRED, INVALID_DOCUMENT, UNSUPPORTED_DOCUMENT, DOCUMENT_TOO_LARGE, DOCUMENT_READ, DOCUMENT_WRITE,
}

data class PlaylistMemberRow(
    val member: PlaylistMember,
    val title: String?,
    val availability: PlaylistQueueAvailability,
)

/** 写入草稿保存意图身份，恢复只显示输入，永不自动提交。 */
data class PlaylistNameDraft(
    val operationId: String,
    val playlistId: String,
    val expectedRevision: Long,
    val name: String,
    val purpose: PlaylistNamePurpose,
)

data class PlaylistMoveDraft(
    val operationId: String,
    val memberId: String,
    val fromOrdinal: Long,
    val destination: String,
    val expectedRevision: Long,
    val playlistId: String,
    val titleHint: String? = null,
)

data class PlaylistDeleteDraft(val operationId: String, val playlistId: String, val expectedRevision: Long)
data class PlaylistRemoveDraft(val operationId: String, val memberId: String, val expectedRevision: Long)

data class PlaylistFolderDraft(
    val operationId: String,
    val purpose: FolderPlaylistPurpose,
    val playlistId: String,
    val expectedRevision: Long,
    val choice: PlaylistFolderChoice?,
    val preview: FolderPlaylistPreview? = null,
)

data class PlaylistKeywordDraft(
    val keyword: String = "",
    val preview: KeywordPlaylistPreview? = null,
    val broadMatchConfirmed: Boolean = false,
    val emptyMatchConfirmed: Boolean = false,
)

data class PlaylistAddDraft(val operationId: String, val target: PlaylistSnapshot)

data class PlaylistImportDraft(
    val operationId: String,
    val newPlaylistId: String,
    val format: PlaylistTransferFormat,
    val encoding: PlaylistTransferEncoding,
    val target: PlaylistImportTarget?,
    val document: AuthorizedPlaylistDocument? = null,
    val preview: PlaylistImportPreview? = null,
    val name: String = "",
    val unresolvedConfirmed: Boolean = false,
)

data class PlaylistExportDraft(
    val operationId: String,
    val originalView: PlaylistViewToken,
    val revision: PlaylistPreviewRevision,
    val format: PlaylistTransferFormat,
    val encoding: PlaylistTransferEncoding,
    val destination: AuthorizedPlaylistDocument? = null,
)

data class PlaylistPlayDraft(
    val command: PlaylistQueueCommand,
    val previousCount: Long,
    val title: String?,
    val submitted: Boolean = false,
)

data class PlaylistQueueBinding(
    val generationId: String?,
    val revision: Long,
    val currentEntryId: String?,
    val entryCount: Long,
)

data class PlaylistUiState(
    val screen: PlaylistScreen = PlaylistScreen.CATALOG,
    val playlistId: String? = null,
    val overview: PlaylistOverview? = null,
    val catalogPage: PlaylistCatalogPage? = null,
    val view: PlaylistViewToken? = null,
    val catalog: LibraryViewToken? = null,
    val memberPage: PlaylistPage? = null,
    val pageStart: Long = 0L,
    val rows: List<PlaylistMemberRow> = emptyList(),
    val selected: PlaylistMemberRow? = null,
    val favorite: Boolean? = null,
    val favoritePending: Boolean = false,
    val nameDraft: PlaylistNameDraft? = null,
    val deleteDraft: PlaylistDeleteDraft? = null,
    val removeDraft: PlaylistRemoveDraft? = null,
    val moveDraft: PlaylistMoveDraft? = null,
    val folderDraft: PlaylistFolderDraft? = null,
    val keywordDraft: PlaylistKeywordDraft? = null,
    val addCount: Long? = null,
    val addLabel: String? = null,
    val addDraft: PlaylistAddDraft? = null,
    val importDraft: PlaylistImportDraft? = null,
    val importPage: PlaylistImportPage? = null,
    val exportDraft: PlaylistExportDraft? = null,
    val exportReceipt: PlaylistExportReceipt? = null,
    val playDraft: PlaylistPlayDraft? = null,
    val playPending: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val showingPreviousData: Boolean = false,
    val hasNewData: Boolean = false,
    val previewStale: Boolean = false,
    val issue: PlaylistUiIssue? = null,
    val lastCommit: PlaylistCommit? = null,
) {
    val canUseView: Boolean get() = !loading && !saving && !showingPreviousData && view != null && catalog != null
    val canPlay: Boolean get() = canUseView && !playPending && view?.snapshot?.memberCount?.let { it > 0L } == true
    val canEditMembers: Boolean get() = canUseView && view?.snapshot?.kind == PlaylistKind.ORDINARY
    val canMoveMembers: Boolean get() = canUseView && view?.snapshot?.kind != PlaylistKind.FAVORITES
    val hasPrevious: Boolean get() = !loading && !saving && !showingPreviousData && pageStart > 0L
    val hasNext: Boolean get() = !loading && !saving && !showingPreviousData && memberPage?.nextOrdinal != null
}
