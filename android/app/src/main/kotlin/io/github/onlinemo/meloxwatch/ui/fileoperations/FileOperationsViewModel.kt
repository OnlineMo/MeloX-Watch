package io.github.onlinemo.meloxwatch.ui.fileoperations

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.onlinemo.meloxwatch.MeloXApplication
import io.github.onlinemo.meloxwatch.data.fileoperations.AudioTagField
import io.github.onlinemo.meloxwatch.data.fileoperations.AudioTagPatch
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationCommand
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationConfirmation
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationDestination
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationException
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationIssue
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationItem
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationKind
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationPreview
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationProgress
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationRecord
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationsState
import io.github.onlinemo.meloxwatch.data.fileoperations.FileRecoveryAction
import io.github.onlinemo.meloxwatch.data.fileoperations.FileRecoveryConfirmation
import io.github.onlinemo.meloxwatch.data.fileoperations.FileSelectedTrack
import io.github.onlinemo.meloxwatch.data.fileoperations.FileSelectionDraft
import io.github.onlinemo.meloxwatch.data.fileoperations.FileSelectionPage
import io.github.onlinemo.meloxwatch.data.fileoperations.FileSelectionPageSource
import io.github.onlinemo.meloxwatch.data.fileoperations.FileSelectionToken
import io.github.onlinemo.meloxwatch.data.fileoperations.FileTagDifference
import io.github.onlinemo.meloxwatch.data.fileoperations.FileIgnoredPage
import io.github.onlinemo.meloxwatch.data.library.LibrarySource
import io.github.onlinemo.meloxwatch.data.library.LibrarySourcePage
import io.github.onlinemo.meloxwatch.data.library.LibraryTrack
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class FileEditorKind { TAGS, RENAME, MOVE }
data class FileEditor(
    val kind: FileEditorKind,
    val track: FileSelectedTrack?,
    val viewRevision: Long,
    val beforeTags: Map<AudioTagField, String> = emptyMap(),
    val tags: Map<AudioTagField, String> = emptyMap(),
    val oldName: String = "",
    val name: String = "",
    val destination: LibrarySource? = null,
)
data class FileOperationsUiState(
    val runtime: FileOperationsState = FileOperationsState.Loading,
    val draft: FileSelectionDraft? = null,
    val visibleSelected: Set<TrackKey> = emptySet(),
    val editor: FileEditor? = null,
    val destinations: LibrarySourcePage? = null,
    val preview: FileOperationPreview? = null,
    val kind: FileOperationKind? = null,
    val retrying: Boolean = false,
    val tagDifferences: List<FileTagDifference> = emptyList(),
    val progress: FileOperationProgress? = null,
    val items: List<FileOperationItem> = emptyList(),
    val pending: List<FileOperationRecord> = emptyList(),
    val ignored: FileIgnoredPage? = null,
    val recovery: FileRecoveryConfirmation? = null,
    val working: Boolean = false,
    val stopping: Boolean = false,
    val issue: FileOperationIssue? = null,
)

/** ViewModel 仅持显式草稿与命令；重建读取私有日志，不重放操作。 */
class FileOperationsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MeloXApplication
    private val mutableState = MutableStateFlow(FileOperationsUiState())
    val state = mutableState.asStateFlow()
    private var command: Job? = null
    private var observers: Job? = null
    private var visible = false
    private var visibleKeys: List<TrackKey> = emptyList()

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        observers?.cancel()
        observers = null
        if (value) {
            observers = viewModelScope.launch {
                launch { app.fileOperations.state.collect { status -> mutableState.update { it.copy(runtime = status) } } }
                launch { app.fileOperations.progress.collect { progress ->
                    if (progress?.operationId == mutableState.value.progress?.operationId) mutableState.update { it.copy(progress = progress) }
                } }
                try { app.fileOperations.initialize() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { publish(failure) }
            }
        } else {
            cancel()
            val stoppingCommand = command
            val abandonedDraft = mutableState.value.draft
            mutableState.update { it.copy(editor = null, recovery = null, visibleSelected = emptySet()) }
            viewModelScope.launch(NonCancellable) {
                stoppingCommand?.join()
                if (abandonedDraft != null) try {
                    app.fileOperations.closeSelectionDraft(abandonedDraft)
                    mutableState.update { if (it.draft?.draftId == abandonedDraft.draftId) it.copy(draft = null) else it }
                }
                    catch (failure: Exception) { publish(failure) }
            }
        }
    }

    fun beginSelection(viewId: String) = run {
        mutableState.value.draft?.let { app.fileOperations.closeSelectionDraft(it) }
        val draft = app.fileOperations.openSelectionDraft(viewId)
        mutableState.update { it.copy(draft = draft, editor = null, visibleSelected = emptySet()) }
        refreshMembership()
    }
    fun toggle(key: TrackKey) = run {
        val draft = mutableState.value.draft ?: return@run
        val changed = app.fileOperations.toggleSelectionDraft(draft, key)
        mutableState.update { it.copy(draft = changed) }
        refreshMembership()
    }
    fun selectAll(value: Boolean) = run {
        val draft = mutableState.value.draft ?: return@run
        val changed = app.fileOperations.selectAllDraft(draft, value)
        mutableState.update { it.copy(draft = changed) }
        refreshMembership()
    }
    fun closeSelection() = run {
        val draft = mutableState.value.draft ?: return@run
        app.fileOperations.closeSelectionDraft(draft)
        mutableState.update { it.copy(draft = null, visibleSelected = emptySet()) }
    }
    fun visibleTracks(keys: List<TrackKey>) {
        visibleKeys = keys.take(100)
        if (visible && command?.isCompleted != false && mutableState.value.draft != null) run { refreshMembership() }
    }

    fun beginTags(track: LibraryTrack, revision: Long) {
        if (command?.isCompleted == false) return
        val before = mapOf(AudioTagField.TITLE to track.metadata.title.orEmpty(), AudioTagField.ARTISTS to
            track.metadata.artists.ifEmpty { listOfNotNull(track.metadata.artist) }.joinToString("\n"),
            AudioTagField.ALBUM to track.metadata.album.orEmpty(), AudioTagField.ALBUM_ARTIST to track.metadata.albumArtist.orEmpty(),
            AudioTagField.TRACK_NUMBER to track.metadata.trackNumber?.toString().orEmpty(), AudioTagField.DISC_NUMBER to track.metadata.discNumber?.toString().orEmpty())
        mutableState.update { it.copy(editor = FileEditor(FileEditorKind.TAGS, selected(track), revision, before, before), preview = null) }
    }
    fun beginRename(track: LibraryTrack, revision: Long) {
        if (command?.isCompleted != false) mutableState.update { it.copy(editor = FileEditor(FileEditorKind.RENAME, selected(track), revision,
            oldName = track.filename, name = track.filename), preview = null) }
    }
    fun beginMove(track: LibraryTrack? = null, revision: Long = 0L) = run {
        if (track == null && (mutableState.value.draft?.selectedCount ?: 0L) == 0L) throw FileOperationException(FileOperationIssue.EMPTY_SELECTION)
        val destinations = app.library.sourcesPage()
        mutableState.update { it.copy(editor = FileEditor(FileEditorKind.MOVE, track?.let(::selected), revision), destinations = destinations, preview = null) }
    }
    fun moreDestinations() = run {
        val after = mutableState.value.destinations?.nextSourceId ?: return@run
        val page = app.library.sourcesPage(after)
        mutableState.update { it.copy(destinations = page) }
    }
    fun destination(source: LibrarySource) { mutableState.update { it.copy(editor = it.editor?.copy(destination = source)) } }
    fun editName(value: String) { if (value.length <= 255) mutableState.update { it.copy(editor = it.editor?.copy(name = value)) } }
    fun editTag(field: AudioTagField, value: String) {
        val limit = if (field == AudioTagField.ARTISTS) 128 * 4096 else 4096
        if (value.length <= limit) mutableState.update { it.copy(editor = it.editor?.let { draft -> draft.copy(tags = draft.tags + (field to value)) }) }
    }
    fun cancelEditor() { mutableState.update { it.copy(editor = null) } }
    fun prepareEditor() {
        val editor = mutableState.value.editor ?: return
        run {
            val command = when (editor.kind) {
                FileEditorKind.TAGS -> {
                    val changed = editor.tags.filter { (field, value) -> value != editor.beforeTags[field] }
                    if (changed.isEmpty()) throw FileOperationException(FileOperationIssue.INVALID_INPUT)
                    FileOperationCommand.writeTags(AudioTagPatch(changed.mapValues { (field, value) ->
                        if (value.isBlank()) null else if (field == AudioTagField.ARTISTS) value.lines().map(String::trim).filter(String::isNotEmpty)
                        else listOf(value)
                    }))
                }
                FileEditorKind.RENAME -> FileOperationCommand.rename(editor.name)
                FileEditorKind.MOVE -> {
                    val source = editor.destination ?: throw FileOperationException(FileOperationIssue.INVALID_INPUT)
                    if (source.spec.kind != LocalSourceKind.TREE) throw FileOperationException(FileOperationIssue.INVALID_INPUT)
                    FileOperationCommand.move(FileOperationDestination(source.spec.sourceId, source.spec.uri,
                        source.spec.rootObjectId ?: throw FileOperationException(FileOperationIssue.INVALID_INPUT), source.authEpoch))
                }
            }
            prepare(command, editor.track, editor.viewRevision)
            mutableState.update { it.copy(editor = null) }
        }
    }

    fun prepareSelection(kind: FileOperationKind) = run { prepare(command(kind), null, 0L) }
    fun prepareTrack(track: LibraryTrack, revision: Long, kind: FileOperationKind) = run { prepare(command(kind), selected(track), revision) }

    private suspend fun prepare(command: FileOperationCommand, single: FileSelectedTrack?, revision: Long) {
        val operationId = UUID.randomUUID().toString()
        val draft = if (single == null) mutableState.value.draft ?: throw FileOperationException(FileOperationIssue.EMPTY_SELECTION) else null
        val selection = draft?.selection ?: FileSelectionToken("single:$operationId", revision, 1L)
        val producer = draft?.let { app.fileOperations.selectionProducer(it) } ?: FileSelectionPageSource { offset, limit ->
            if (offset != 0L || limit < 1) throw FileOperationException(FileOperationIssue.INVALID_INPUT)
            FileSelectionPage(selection, 0L, listOf(checkNotNull(single)), true)
        }
        val preview = app.fileOperations.prepare(operationId, selection, command, producer)
        val items = app.fileOperations.items(operationId, limit = RESULT_PAGE_SIZE)
        val differences = if (command.kind == FileOperationKind.WRITE_TAGS && items.size == 1)
            app.fileOperations.tagDifferences(operationId, items.single().target.unitKey) else emptyList()
        val progress = app.fileOperations.readProgress(operationId)
        mutableState.update { it.copy(preview = preview, kind = preview.kind, retrying = false, progress = progress, items = items, tagDifferences = differences) }
        if (draft != null) { app.fileOperations.closeSelectionDraft(draft)
            mutableState.update { it.copy(draft = null, visibleSelected = emptySet()) } }
    }
    fun confirm(wholeFileAccepted: Boolean, playbackClosedAccepted: Boolean) = run {
        val preview = mutableState.value.preview ?: return@run
        val confirmation = FileOperationConfirmation(preview.operationId, preview.binding, preview.kind, true, wholeFileAccepted, playbackClosedAccepted)
        val progress = if (mutableState.value.retrying) app.fileOperations.retry(confirmation) else app.fileOperations.confirm(confirmation)
        mutableState.update { it.copy(preview = null, progress = progress, tagDifferences = emptyList()) }
        loadItems(progress.operationId)
    }
    fun cancelPreview() = run {
        val preview = mutableState.value.preview ?: return@run
        val progress = app.fileOperations.confirm(FileOperationConfirmation(preview.operationId, preview.binding, preview.kind, false, false, false))
        mutableState.update { it.copy(preview = null, progress = progress, tagDifferences = emptyList()) }
    }
    fun sourceDeletionPreview() = run {
        val id = mutableState.value.progress?.operationId ?: return@run
        val preview = app.fileOperations.sourceDeletionPreview(id)
        mutableState.update { it.copy(preview = preview, retrying = false) }
    }
    fun keepCompletedCopies() = run {
        val id = mutableState.value.progress?.operationId ?: return@run
        val preview = app.fileOperations.sourceDeletionPreview(id)
        val progress = app.fileOperations.confirm(FileOperationConfirmation(id, preview.binding, preview.kind, false, false, false))
        mutableState.update { it.copy(progress = progress, preview = null) }
    }
    fun pending(afterOperationId: String? = null) = run {
        val page = app.fileOperations.pending(afterOperationId, RESULT_PAGE_SIZE)
        mutableState.update { it.copy(pending = page) }
    }
    fun showOperation(record: FileOperationRecord) = run {
        val progress = app.fileOperations.readProgress(record.operationId)
        mutableState.update { it.copy(progress = progress, preview = null, kind = record.command.kind) }
        loadItems(record.operationId)
    }
    fun retryPreview() = run {
        val id = mutableState.value.progress?.operationId ?: return@run
        val preview = app.fileOperations.operationPreview(id)
        mutableState.update { it.copy(preview = preview, retrying = true) }
    }
    fun retryInitialize() = run { app.fileOperations.initialize() }
    fun ignored(afterTrackId: String? = null) = run {
        val page = app.fileOperations.ignoredTracks(afterTrackId, RESULT_PAGE_SIZE)
        mutableState.update { it.copy(ignored = page) }
    }
    fun restoreIgnored(key: TrackKey) = run {
        app.fileOperations.restoreIgnoredTrack(key)
        val page = app.fileOperations.ignoredTracks(limit = RESULT_PAGE_SIZE)
        mutableState.update { it.copy(ignored = page) }
    }
    fun recover() = run {
        val id = mutableState.value.progress?.operationId ?: return@run
        val progress = app.fileOperations.recover(id)
        mutableState.update { it.copy(progress = progress) }
        loadItems(id)
    }
    fun nextItems() = run {
        val id = mutableState.value.progress?.operationId ?: return@run
        val after = mutableState.value.items.lastOrNull()?.ordinal ?: return@run
        loadItems(id, after)
    }
    fun firstItems() = run { mutableState.value.progress?.operationId?.let { loadItems(it) } }
    fun restoreIndex() = run {
        val id = mutableState.value.progress?.operationId ?: return@run
        val progress = app.fileOperations.restoreIndex(id)
        mutableState.update { it.copy(progress = progress) }
    }
    fun recovery(item: FileOperationItem, action: FileRecoveryAction) = run {
        val binding = app.fileOperations.recoveryBinding(item.operationId, item.target.unitKey, action)
        mutableState.update { it.copy(recovery = FileRecoveryConfirmation(item.operationId, item.target.unitKey, binding, action, false, false)) }
    }
    fun cancelRecovery() { mutableState.update { it.copy(recovery = null) } }
    fun confirmRecovery() = run {
        val draft = mutableState.value.recovery ?: return@run
        val confirmation = draft.copy(accepted = true, playbackPauseAndCloseAccepted = true)
        val progress = when (draft.action) {
            FileRecoveryAction.RESTORE_RETAINED_ORIGINAL -> app.fileOperations.restoreOriginal(confirmation)
            FileRecoveryAction.DISCARD_STAGING -> app.fileOperations.discardStaging(confirmation)
        }
        mutableState.update { it.copy(recovery = null, progress = progress) }
        loadItems(draft.operationId)
    }
    fun cancel() { if (command?.isCompleted == false) { mutableState.update { it.copy(stopping = true) }
            command?.cancel() } }

    private suspend fun loadItems(id: String, after: Long? = null) {
        val page = app.fileOperations.items(id, after, RESULT_PAGE_SIZE)
        mutableState.update { it.copy(items = page) }
    }
    private suspend fun refreshMembership() {
        val draft = mutableState.value.draft ?: return
        val selected = app.fileOperations.selectionMembership(draft, visibleKeys)
        mutableState.update { it.copy(visibleSelected = selected) }
    }
    private fun run(action: suspend () -> Unit) {
        if (command?.isCompleted == false) { mutableState.update { it.copy(issue = FileOperationIssue.BUSY) }
            return }
        mutableState.update { it.copy(working = true, stopping = false, issue = null) }
        command = viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { mutableState.update { it.copy(issue = FileOperationIssue.CANCELLED) }
                throw cancelled }
            catch (failure: Exception) { publish(failure) }
            finally { withContext(NonCancellable) { app.localProviderIo.awaitIdle() }
                mutableState.update { it.copy(working = false, stopping = false) } }
        }
    }
    private fun publish(failure: Exception) { mutableState.update { it.copy(issue = (failure as? FileOperationException)?.issue ?: FileOperationIssue.RECOVERY_REQUIRED) } }
    private fun selected(track: LibraryTrack) = FileSelectedTrack(track.trackKey, track.location?.locationId, track.location?.contentRevision)
    private fun command(kind: FileOperationKind): FileOperationCommand = when (kind) {
        FileOperationKind.REMOVE_FROM_INDEX -> FileOperationCommand.removeFromIndex()
        FileOperationKind.DELETE_ORIGINAL -> FileOperationCommand.deleteOriginal()
        FileOperationKind.DELETE_APP_COPY -> FileOperationCommand.deleteAppCopy()
        else -> throw FileOperationException(FileOperationIssue.INVALID_INPUT)
    }
    private companion object { const val RESULT_PAGE_SIZE = 10 }
}
