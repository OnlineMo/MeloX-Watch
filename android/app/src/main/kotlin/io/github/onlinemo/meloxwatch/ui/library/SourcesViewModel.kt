package io.github.onlinemo.meloxwatch.ui.library

import android.app.Application
import io.github.onlinemo.meloxwatch.data.backup.BackupException
import io.github.onlinemo.meloxwatch.data.backup.BackupIssue
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.onlinemo.meloxwatch.MeloXApplication
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationConfirmation
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationException
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationIssue
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationPhase
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationPreview
import io.github.onlinemo.meloxwatch.data.imports.ImportException
import io.github.onlinemo.meloxwatch.data.imports.ImportIssue
import io.github.onlinemo.meloxwatch.data.imports.ImportPreview
import io.github.onlinemo.meloxwatch.data.imports.ImportProgress
import io.github.onlinemo.meloxwatch.data.imports.ImportRecord
import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryExclusionPage
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.library.LibraryRules
import io.github.onlinemo.meloxwatch.data.library.LibrarySource
import io.github.onlinemo.meloxwatch.data.library.LibrarySourcePage
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceObservation
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.scan.ScanState
import io.github.onlinemo.meloxwatch.model.LocalAccess
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SourcesNotice { CANCELLED, NO_FILE_PICKER, NO_TREE_PICKER, ADDED, REAUTHORIZED, REMOVED, RULES_SAVED, RULES_CONFLICT }

data class SourceSelection(
    val observation: LocalSourceObservation,
    val metadata: LocalAudioMetadata?,
    val entry: QueueEntry?,
    val original: LibrarySource? = null,
    val verifiedContentToken: String? = null,
)

data class SourcesUiState(
    val page: LibrarySourcePage? = null,
    val selected: SourceSelection? = null,
    val reauthorization: LibrarySource? = null,
    val removeConfirmation: LibrarySource? = null,
    val removePreview: FileOperationPreview? = null,
    val importPreview: ImportPreview? = null,
    val importRecord: ImportRecord? = null,
    val importProgress: ImportProgress? = null,
    val scan: ScanState? = null,
    val rules: LibraryRules? = null,
    val exclusions: LibraryExclusionPage? = null,
    val filterDraft: Boolean? = null,
    val working: Boolean = false,
    val stopping: Boolean = false,
    val sourceIssue: SourceReadIssue? = null,
    val libraryIssue: LibraryIssue? = null,
    val importIssue: ImportIssue? = null,
    val fileIssue: FileOperationIssue? = null,
    val notice: SourcesNotice? = null,
)

/** Selecting a document only previews it. Grant retention, indexing and copying are separate actions. */
class SourcesViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MeloXApplication
    private val mutableState = MutableStateFlow(SourcesUiState())
    val uiState = mutableState.asStateFlow()
    private var visible = false
    private var observer: Job? = null
    private var action: Job? = null
    private var copyJob: Job? = null
    private var selectionRequest: Job? = null
    private var selectionRevision = 0L

    fun onVisible() {
        if (visible) return
        visible = true
        observer = viewModelScope.launch {
            try { app.requireBackupStorageReady() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val busy = (failure as? BackupException)?.issue == BackupIssue.BUSY
                mutableState.update { it.copy(libraryIssue = if (busy) LibraryIssue.BUSY else LibraryIssue.STORAGE_UNAVAILABLE,
                    importIssue = if (busy) ImportIssue.BUSY else ImportIssue.RECOVERY_REQUIRED) }
                return@launch
            }
            launch { app.scans.state.collect { scan -> mutableState.update { it.copy(scan = scan) } } }
            launch { app.imports.state.collect { record -> mutableState.update { it.copy(importRecord = record) } } }
            launch { app.imports.progress.collect { progress -> mutableState.update { it.copy(importProgress = progress) } } }
            launch { app.library.observeOverview().collect { if (mutableState.value.page == null) reload() } }
        }
        reload()
    }

    fun onHidden() {
        visible = false
        observer?.cancel(); observer = null
        cancelRead()
        discardPreview()
    }

    fun onBackground() {
        cancelRead()
        copyJob?.cancel()
    }

    fun reload() = runAction(quietIfBusy = true) {
        app.requireBackupStorageReady()
        app.library.initialize()
        val page = app.library.sourcesPage()
        val rules = app.library.rules()
        val exclusions = app.library.exclusionPage()
        mutableState.update { it.copy(page = page, rules = rules, exclusions = exclusions, filterDraft = null) }
        val issue = app.recoverLocalState()
        val recoveredPage = app.library.sourcesPage()
        mutableState.update { it.copy(page = recoveredPage, importIssue = issue) }
    }

    fun nextSources() {
        val after = mutableState.value.page?.nextSourceId ?: return
        runAction { val page = app.library.sourcesPage(after); mutableState.update { it.copy(page = page) } }
    }

    fun nextExclusions() {
        val after = mutableState.value.exclusions?.nextRuleId ?: return
        runAction { val page = app.library.exclusionPage(after); mutableState.update { it.copy(exclusions = page) } }
    }

    fun beginReauthorization(source: LibrarySource) {
        cancelRead()
        mutableState.value.selected?.let { discardPreview() }
        mutableState.update { it.copy(reauthorization = source, selected = null, notice = null) }
    }

    fun cancelReauthorization() {
        cancelRead()
        discardPreview()
        mutableState.update { it.copy(reauthorization = null, selected = null, notice = SourcesNotice.CANCELLED) }
    }

    fun selected(kind: LocalSourceKind, uri: Uri, flags: Int) {
        cancelRead()
        val requestRevision = selectionRevision
        discardPreview()
        val original = mutableState.value.reauthorization
        mutableState.update { it.copy(selected = null) }
        // Keep one latest picker/intent request while the cancelled physical reader releases its slot.
        selectionRequest = viewModelScope.launch {
            while (action?.isCompleted == false) action?.join()
            currentCoroutineContext().ensureActive()
            if (requestRevision != selectionRevision) return@launch
            runAction {
                val spec = LocalSourceSpec(UUID.randomUUID().toString(), kind, uri.toString(), flags)
                val observed = if (original == null) app.localSources.observe(spec)
                    else app.localSources.verifyReauthorization(original.spec, spec)
                val selection = readSelection(observed, original)
                currentCoroutineContext().ensureActive()
                if (requestRevision == selectionRevision && mutableState.value.reauthorization == original) {
                    mutableState.update { it.copy(selected = selection) }
                }
            }
        }
    }

    fun persistSelectedAccess() = runAction {
        val selected = mutableState.value.selected ?: return@runAction
        verifySelection(selected)
        val observed = app.localSources.persistReadAccess(selected.observation.source)
        val refreshed = readSelection(observed, selected.original, selected)
        currentCoroutineContext().ensureActive()
        mutableState.update { it.copy(selected = refreshed) }
    }

    fun addSelected() = runAction {
        val selection = mutableState.value.selected ?: return@runAction
        if (selection.original != null) throw LibraryException(LibraryIssue.INVALID_INPUT)
        val refreshed = refreshSelection(selection)
        val source = app.fileOperations.mutations.registerSource(refreshed.observation.source, refreshed.observation.access)
        val rules = app.library.rules()
        app.scans.start(source, rules.revision)
        discardPreview()
        mutableState.update { it.copy(selected = null, notice = SourcesNotice.ADDED,
            page = null, rules = rules) }
        val page = app.library.sourcesPage()
        mutableState.update { it.copy(page = page) }
    }

    fun commitReauthorization() = runAction {
        val selection = mutableState.value.selected ?: return@runAction
        val original = selection.original ?: throw LibraryException(LibraryIssue.INVALID_INPUT)
        verifySelection(selection)
        val verified = app.localSources.verifyReauthorization(original.spec, selection.observation.source)
        readSelection(verified, original, selection)
        val updated = app.fileOperations.mutations.reauthorizeSource(original.spec.sourceId, original.authEpoch, verified.source, verified.access)
        val rules = app.library.rules()
        if (!app.fileOperations.hasPendingSource(updated.spec.sourceId)) app.scans.start(updated, rules.revision)
        mutableState.update { it.copy(selected = null, reauthorization = null, notice = SourcesNotice.REAUTHORIZED) }
        val page = app.library.sourcesPage()
        mutableState.update { it.copy(page = page) }
    }

    fun addMediaStore() = runAction {
        val source = LocalSourceSpec(UUID.randomUUID().toString(), LocalSourceKind.MEDIA_STORE,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.toString())
        val observed = app.localSources.observe(source)
        val registered = app.fileOperations.mutations.registerSource(observed.source, observed.access)
        val rules = app.library.rules()
        app.scans.start(registered, rules.revision)
        val page = app.library.sourcesPage()
        mutableState.update { it.copy(page = page, rules = rules, notice = SourcesNotice.ADDED) }
    }

    fun scan(source: LibrarySource) = runAction {
        val latest = app.library.source(source.spec.sourceId) ?: throw LibraryException(LibraryIssue.SOURCE_UNAVAILABLE)
        val rules = app.library.rules()
        app.scans.start(latest, rules.revision)
    }

    fun retryCueGb18030(source: LibrarySource) = runAction {
        if (source.spec.kind != LocalSourceKind.TREE) throw LibraryException(LibraryIssue.INVALID_INPUT)
        val latest = app.library.source(source.spec.sourceId) ?: throw LibraryException(LibraryIssue.SOURCE_UNAVAILABLE)
        val rules = app.library.rules()
        app.scans.start(latest, rules.revision, cueEncoding = io.github.onlinemo.meloxwatch.data.cue.CueEncoding.GB18030)
    }

    fun cancelScan() {
        val id = mutableState.value.scan?.token?.scanId ?: return
        viewModelScope.launch { app.scans.cancel(id) }
    }

    fun previewCopy() = runAction {
        val selection = mutableState.value.selected ?: return@runAction
        verifySelection(selection)
        val preview = app.imports.prepare(selection.observation.source)
        mutableState.update { it.copy(importPreview = preview) }
    }

    fun playSelected(play: (QueueEntry) -> Unit) = runAction {
        val selection = mutableState.value.selected ?: return@runAction
        val refreshed = refreshSelection(selection)
        val entry = refreshed.entry ?: return@runAction
        currentCoroutineContext().ensureActive()
        mutableState.update { it.copy(selected = refreshed) }
        play(entry)
    }

    fun discardPreview() {
        mutableState.value.importPreview?.let { app.imports.discardPreview(it.intent.operationId) }
        mutableState.update { it.copy(importPreview = null) }
    }

    fun confirmCopy() {
        val preview = mutableState.value.importPreview ?: return
        if (copyJob?.isActive == true || action?.isActive == true) return
        mutableState.update { it.copy(importPreview = null, importIssue = null, notice = null) }
        copyJob = viewModelScope.launch {
            try {
                val record = app.imports.confirmCopy(preview)
                mutableState.update { it.copy(importRecord = record, selected = null) }
                if (visible) reload()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { publishFailure(error) }
        }
    }

    fun cancelCopy() {
        val id = mutableState.value.importProgress?.operationId ?: return
        viewModelScope.launch { app.imports.cancel(id) }
    }

    fun retryImportRecovery() = runAction { app.imports.recover() }

    fun askRemoveSource(source: LibrarySource) = runAction {
        val preview = app.fileOperations.prepareSourceRemoval(UUID.randomUUID().toString(), source.spec.sourceId, source.authEpoch)
        mutableState.update { it.copy(removeConfirmation = source, removePreview = preview) }
    }

    fun cancelRemoveSource() = runAction {
        val preview = mutableState.value.removePreview
        if (preview != null) app.fileOperations.confirm(FileOperationConfirmation(preview.operationId, preview.binding, preview.kind,
            accepted = false, wholePhysicalFileImpactAccepted = false, playbackPauseAndCloseAccepted = false))
        mutableState.update { it.copy(removeConfirmation = null, removePreview = null) }
    }

    fun confirmRemoveSource() = runAction {
        val source = mutableState.value.removeConfirmation ?: return@runAction
        val preview = mutableState.value.removePreview ?: return@runAction
        val result = app.fileOperations.confirm(FileOperationConfirmation(preview.operationId, preview.binding, preview.kind,
            accepted = true, wholePhysicalFileImpactAccepted = false, playbackPauseAndCloseAccepted = false))
        if (result.phase != FileOperationPhase.COMPLETED) throw FileOperationException(FileOperationIssue.PARTIAL_RESULT)
        val page = app.library.sourcesPage()
        mutableState.update { it.copy(page = page, removeConfirmation = null, removePreview = null, notice = SourcesNotice.REMOVED) }
    }

    fun editFilter() {
        val current = mutableState.value.rules ?: return
        mutableState.update { it.copy(filterDraft = !current.shortAudioFilter) }
    }

    fun cancelFilter() { mutableState.update { it.copy(filterDraft = null) } }

    fun saveFilter() = runAction {
        val state = mutableState.value
        val rules = state.rules ?: return@runAction
        val draft = state.filterDraft ?: return@runAction
        val saved = app.fileOperations.withExclusiveLibraryMutation { app.library.setShortAudioFilter(rules.revision, draft) }
        mutableState.update { it.copy(rules = saved, filterDraft = null, notice = SourcesNotice.RULES_SAVED) }
    }

    fun excludeTrack(sourceId: String, nodeId: String) = runAction {
        val rules = app.library.rules()
        val updated = app.fileOperations.withExclusiveLibraryMutation { app.library.addExclusion(rules.revision, sourceId, nodeId, subtree = false) }
        mutableState.update { it.copy(rules = updated, notice = SourcesNotice.RULES_SAVED) }
    }

    fun excludeFolder(folder: io.github.onlinemo.meloxwatch.data.library.LibraryFolder) = runAction {
        val rules = app.library.rules()
        val updated = app.fileOperations.withExclusiveLibraryMutation { app.library.addExclusion(rules.revision, folder.sourceId, folder.nodeId, subtree = true) }
        mutableState.update { it.copy(rules = updated, notice = SourcesNotice.RULES_SAVED) }
    }

    fun removeExclusion(ruleId: String) = runAction {
        val rules = mutableState.value.rules ?: return@runAction
        val updated = app.fileOperations.withExclusiveLibraryMutation { app.library.removeExclusion(rules.revision, ruleId) }
        val exclusions = app.library.exclusionPage()
        mutableState.update { it.copy(rules = updated, exclusions = exclusions, notice = SourcesNotice.RULES_SAVED) }
    }

    fun pickerCancelled() { mutableState.update { it.copy(notice = SourcesNotice.CANCELLED) } }
    fun noPicker(tree: Boolean) { mutableState.update { it.copy(notice = if (tree) SourcesNotice.NO_TREE_PICKER else SourcesNotice.NO_FILE_PICKER) } }
    fun permissionDenied() { mutableState.update { it.copy(sourceIssue = SourceReadIssue.PERMISSION_DENIED) } }
    fun invalidSelection() { mutableState.update { it.copy(sourceIssue = SourceReadIssue.INVALID_SOURCE) } }

    fun cancelRead() {
        selectionRevision++
        selectionRequest?.cancel(); selectionRequest = null
        if (action?.isActive != true) return
        mutableState.update { it.copy(stopping = true) }
        action?.cancel()
    }

    private fun runAction(quietIfBusy: Boolean = false, block: suspend () -> Unit) {
        if (action?.isCompleted == false) {
            if (!quietIfBusy) mutableState.update { it.copy(sourceIssue = SourceReadIssue.BUSY) }
            return
        }
        mutableState.update { it.copy(working = true, stopping = false, sourceIssue = null,
            libraryIssue = null, importIssue = null, fileIssue = null, notice = null) }
        action = viewModelScope.launch {
            try { block() }
            catch (error: CancellationException) {
                mutableState.update { it.copy(notice = SourcesNotice.CANCELLED, stopping = true) }
                throw error
            } catch (error: Exception) { publishFailure(error) }
            finally {
                withContext(NonCancellable) { app.localProviderIo.awaitIdle() }
                mutableState.update { it.copy(working = false, stopping = false) }
            }
        }
    }

    private fun publishFailure(error: Exception) {
        if (error is SourceReadException && error.issue == SourceReadIssue.SOURCE_CHANGED) {
            discardPreview()
            mutableState.update { it.copy(selected = null) }
        }
        mutableState.update { state -> when (error) {
            is SourceReadException -> state.copy(sourceIssue = error.issue)
            is LibraryException -> state.copy(libraryIssue = error.issue,
                notice = if (error.issue == LibraryIssue.CONFLICT && state.filterDraft != null) SourcesNotice.RULES_CONFLICT else null)
            is ImportException -> state.copy(importIssue = error.issue)
            is FileOperationException -> state.copy(fileIssue = error.issue)
            else -> state.copy(sourceIssue = SourceReadIssue.SOURCE_UNAVAILABLE)
        } }
    }

    private suspend fun refreshSelection(selection: SourceSelection): SourceSelection {
        verifySelection(selection)
        val observed = app.localSources.observe(selection.observation.source)
        return readSelection(observed, selection.original, selection)
    }

    private suspend fun verifySelection(selection: SourceSelection) {
        app.localSources.verifyObservedCandidate(selection.observation.source, selection.observation.root)
        selection.verifiedContentToken?.let { expected ->
            if (app.localMetadata.contentToken(selection.observation.root) != expected) {
                throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
            }
        }
        currentCoroutineContext().ensureActive()
    }

    private suspend fun readSelection(
        observed: LocalSourceObservation,
        original: LibrarySource?,
        previous: SourceSelection? = null,
    ): SourceSelection {
        if (previous != null) {
            if (observed.identity != previous.observation.identity) throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
            app.localSources.verifyObservedCandidate(observed.source, previous.observation.root)
        }
        val candidate = observed.root
        app.localSources.verifyObservedCandidate(observed.source, candidate)
        val nativeProof = candidate.providerContentToken?.let { !it.startsWith("sha256:") } == true
        val before = if (!candidate.directory && !nativeProof) app.localMetadata.contentToken(candidate) else null
        if (previous?.verifiedContentToken != null && before != previous.verifiedContentToken) {
            throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
        }
        val metadata = if (candidate.directory) null else app.localMetadata.read(candidate)
        app.localSources.verifyObservedCandidate(observed.source, candidate)
        if (before != null && app.localMetadata.contentToken(candidate) != before) {
            throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
        }
        currentCoroutineContext().ensureActive()
        val entry = metadata?.let { values ->
            previous?.entry?.copy(displayName = values.title ?: candidate.displayName,
                durationMs = values.durationMs, location = location(observed))
                ?: QueueEntry(UUID.randomUUID().toString(), TrackKey.newLocal(), 0L, 0L,
                    values.title ?: candidate.displayName, values.durationMs, location(observed))
        }
        return SourceSelection(observed, metadata, entry, original, before)
    }

    private fun location(observed: LocalSourceObservation): LocalLocationReference = LocalLocationReference(
        locationId = UUID.randomUUID().toString(), uri = observed.root.uri,
        grantUri = observed.source.uri, grantFlags = observed.source.grantFlags,
        access = when {
            observed.access.appOwned -> LocalAccess.APP_OWNED
            observed.source.kind == LocalSourceKind.MEDIA_STORE -> LocalAccess.MEDIA_PERMISSION
            observed.access.persistedRead == ObservedCapability.AVAILABLE -> LocalAccess.PERSISTED
            observed.access.persistenceRejected -> LocalAccess.PERSISTENCE_REJECTED
            else -> LocalAccess.TEMPORARY
        },
    )
}
