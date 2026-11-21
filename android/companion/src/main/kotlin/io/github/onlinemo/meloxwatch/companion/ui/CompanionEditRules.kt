package io.github.onlinemo.meloxwatch.companion.ui

import io.github.onlinemo.meloxwatch.companion.data.CompanionCachedPage
import io.github.onlinemo.meloxwatch.companion.data.CompanionDisplayDraft
import io.github.onlinemo.meloxwatch.companion.data.CompanionPlaylistDraft
import io.github.onlinemo.meloxwatch.data.transfer.TransferCapability
import io.github.onlinemo.meloxwatch.data.transfer.TransferCatalogKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferCatalogRow
import io.github.onlinemo.meloxwatch.data.transfer.TransferDisplayPatch
import io.github.onlinemo.meloxwatch.data.transfer.TransferException
import io.github.onlinemo.meloxwatch.data.transfer.TransferFingerprint
import io.github.onlinemo.meloxwatch.data.transfer.TransferIssue
import io.github.onlinemo.meloxwatch.data.transfer.TransferLimits
import io.github.onlinemo.meloxwatch.data.transfer.TransferLibraryRevision
import io.github.onlinemo.meloxwatch.data.transfer.TransferPlaylistEdit
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** 界面和草稿共用身份、授权及修订号检查，播放可用性不授予或收回关系编辑权。 */
internal fun toggleCompanionTrack(state: CompanionUiState, row: TransferCatalogRow.Track): CompanionUiState {
    val capability = when (state.trackSelection) {
        CompanionTrackSelection.PLAYLIST_ADD -> TransferCapability.WRITE_PLAYLISTS
        CompanionTrackSelection.DISPLAY_ADD, CompanionTrackSelection.DISPLAY_REMOVE -> TransferCapability.WRITE_DISPLAY_METADATA
        CompanionTrackSelection.NONE -> throw TransferException(TransferIssue.INVALID_INPUT)
    }
    val target = readyCompanionTarget(state, capability)
    val kind = when (state.trackSelection) {
        CompanionTrackSelection.DISPLAY_REMOVE -> membersKind(state.group?.state?.kind ?: throw TransferException(TransferIssue.NOT_FOUND))
        else -> TransferCatalogKind.TRACKS
    }
    val cached = currentCompanionPage(state, kind)
    if (cached.page.rows.none { it === row }) throw TransferException(TransferIssue.NOT_FOUND)
    if (state.selectedTrackRevision != null && !state.selectedTrackRevision.sameAs(cached.page.libraryRevision)) {
        throw TransferException(TransferIssue.CONFLICT)
    }
    if (state.trackSelection == CompanionTrackSelection.PLAYLIST_ADD) {
        val playlist = state.playlist ?: throw TransferException(TransferIssue.NOT_FOUND)
        if (playlist.target != target) throw TransferException(TransferIssue.WRONG_TARGET)
        if (playlist.sessionId != cached.sessionId) throw TransferException(TransferIssue.CONFLICT)
        if (playlist.row.automatic) throw TransferException(TransferIssue.PERMISSION_DENIED)
    } else {
        val group = state.group ?: throw TransferException(TransferIssue.NOT_FOUND)
        if (group.target != target) throw TransferException(TransferIssue.WRONG_TARGET)
        if (group.sessionId != cached.sessionId || !group.state.libraryRevision.sameAs(cached.page.libraryRevision)) {
            throw TransferException(TransferIssue.CONFLICT)
        }
        if (state.trackSelection == CompanionTrackSelection.DISPLAY_REMOVE && state.pageWindow.parentId != group.state.entityId) {
            throw TransferException(TransferIssue.CONFLICT)
        }
    }
    val selected = state.selectedTracks.toMutableSet()
    if (!selected.remove(row.trackId)) {
        if (selected.size >= TransferLimits.PAGE_SIZE) throw TransferException(TransferIssue.QUOTA_EXCEEDED)
        selected += row.trackId
    }
    return state.copy(
        selectedTracks = selected.toSet(),
        selectedTrackRevision = if (selected.isEmpty()) null else cached.page.libraryRevision,
    )
}

internal fun prepareCompanionPlaylistDraft(
    state: CompanionUiState,
    edit: TransferPlaylistEdit,
    operationId: String = UUID.randomUUID().toString(),
    createdPlaylistId: String = UUID.randomUUID().toString(),
): CompanionPlaylistDraft {
    val target = readyCompanionTarget(state, TransferCapability.WRITE_PLAYLISTS)
    val selected = state.playlist
    if (edit !is TransferPlaylistEdit.Create) {
        if (selected == null) throw TransferException(TransferIssue.PERMISSION_DENIED)
        if (selected.target != target) throw TransferException(TransferIssue.WRONG_TARGET)
        if (selected.sessionId != state.connection.sessionId) throw TransferException(TransferIssue.CONFLICT)
        // 自动成员只禁手工增删；系统保护和其它类型限制由真实设备最终裁决。
        if (selected.row.automatic && (edit is TransferPlaylistEdit.Add || edit is TransferPlaylistEdit.Remove)) {
            throw TransferException(TransferIssue.PERMISSION_DENIED)
        }
    }
    val fence = if (edit is TransferPlaylistEdit.Add || edit is TransferPlaylistEdit.Remove || edit is TransferPlaylistEdit.Move) {
        checkNotNull(selected).libraryRevision
    } else null
    if (edit is TransferPlaylistEdit.Add) {
        val cached = currentCompanionPage(state, TransferCatalogKind.TRACKS)
        if (fence == null || state.selectedTrackRevision?.sameAs(fence) != true ||
            state.trackSelection != CompanionTrackSelection.PLAYLIST_ADD || state.selectedTracks.toList() != edit.trackIds) {
            throw TransferException(TransferIssue.CONFLICT)
        }
        if (!fence.sameAs(cached.page.libraryRevision)) throw TransferException(TransferIssue.CONFLICT)
    }
    if (edit is TransferPlaylistEdit.Remove || edit is TransferPlaylistEdit.Move) {
        val playlist = checkNotNull(selected)
        val cached = currentCompanionPage(state, TransferCatalogKind.PLAYLIST_MEMBERS)
        val memberId = if (edit is TransferPlaylistEdit.Remove) edit.memberId else (edit as TransferPlaylistEdit.Move).memberId
        if (state.pageWindow.parentId != playlist.row.playlistId || cached.page.revision != playlist.row.revision ||
            !cached.page.libraryRevision.sameAs(playlist.libraryRevision) ||
            cached.page.rows.none { it is TransferCatalogRow.Member && it.memberId == memberId }) {
            throw TransferException(TransferIssue.CONFLICT)
        }
        if (edit is TransferPlaylistEdit.Move && (edit.destinationOrdinal < 0L || edit.destinationOrdinal >= playlist.row.memberCount)) {
            throw TransferException(TransferIssue.INVALID_INPUT)
        }
    }
    val playlistId = if (edit is TransferPlaylistEdit.Create) createdPlaylistId else checkNotNull(selected).row.playlistId
    val name = when (edit) {
        is TransferPlaylistEdit.Create -> edit.name
        is TransferPlaylistEdit.Rename -> edit.name
        else -> checkNotNull(selected).row.name
    }
    val revision = if (edit is TransferPlaylistEdit.Create) 0L else checkNotNull(selected).row.revision
    return CompanionPlaylistDraft(operationId, target, playlistId, name, revision, fence, edit)
}

internal fun prepareCompanionDisplayDraft(
    state: CompanionUiState,
    patch: TransferDisplayPatch,
    selectedFence: TransferLibraryRevision? = null,
    operationId: String = UUID.randomUUID().toString(),
): CompanionDisplayDraft {
    val selected = state.group ?: throw TransferException(TransferIssue.NOT_FOUND)
    val target = readyCompanionTarget(state, TransferCapability.WRITE_DISPLAY_METADATA)
    if (target != selected.target) throw TransferException(TransferIssue.WRONG_TARGET)
    if (selected.sessionId != state.connection.sessionId) throw TransferException(TransferIssue.CONFLICT)
    if (patch !is TransferDisplayPatch.Rename) {
        val remove = patch is TransferDisplayPatch.Remove
        val expectedSelection = if (remove) CompanionTrackSelection.DISPLAY_REMOVE else CompanionTrackSelection.DISPLAY_ADD
        val kind = if (remove) membersKind(selected.state.kind) else TransferCatalogKind.TRACKS
        val cached = currentCompanionPage(state, kind)
        val ids = if (patch is TransferDisplayPatch.Remove) patch.trackIds else (patch as TransferDisplayPatch.Add).trackIds
        if (state.trackSelection != expectedSelection || state.selectedTracks.toList() != ids ||
            selectedFence == null || state.selectedTrackRevision?.sameAs(selectedFence) != true ||
            !selectedFence.sameAs(selected.state.libraryRevision) || !selectedFence.sameAs(cached.page.libraryRevision) ||
            remove && state.pageWindow.parentId != selected.state.entityId) {
            throw TransferException(TransferIssue.CONFLICT)
        }
    }
    val context = selected.state
    return CompanionDisplayDraft(operationId, target, context.kind, context.entityId, context.revision,
        context.libraryRevision, context.currentName, context.trackCount, patch)
}

internal fun currentCompanionPage(state: CompanionUiState, kind: TransferCatalogKind): CompanionCachedPage {
    val cached = state.pageWindow.current ?: throw TransferException(TransferIssue.NOT_FOUND)
    val target = readyCompanionTarget(state, TransferCapability.READ_LIBRARY)
    if (cached.page.kind != kind || cached.target != target || cached.sessionId != state.connection.sessionId) {
        throw TransferException(TransferIssue.CONFLICT)
    }
    return cached
}

internal fun readyCompanionTarget(state: CompanionUiState, capability: TransferCapability): TransferFingerprint {
    if (!state.allowed(capability)) throw TransferException(TransferIssue.PERMISSION_DENIED)
    return state.connection.target?.fingerprint ?: throw TransferException(TransferIssue.WRONG_TARGET)
}

internal fun companionVisibleIssue(error: Exception): TransferIssue {
    // 父任务取消是控制流；必须将原异常交回调用者，不能显示成普通业务失败。
    if (error is CancellationException) throw error
    return if (error is TransferException) error.issue else TransferIssue.INVALID_INPUT
}
