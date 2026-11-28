package io.github.onlinemo.meloxwatch.ui.library

import android.app.Application
import android.Manifest
import android.content.pm.PackageManager
import io.github.onlinemo.meloxwatch.data.backup.BackupException
import io.github.onlinemo.meloxwatch.data.backup.BackupIssue
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
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
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

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
    val entryCapabilities: SourcesEntryCapabilities = SourcesEntryCapabilities(),
    val mediaPermission: SourcesMediaPermission = SourcesMediaPermission.UNKNOWN,
    val mediaReadIssue: SourceReadIssue? = null,
    val awaitingPicker: Boolean = false,
    val awaitingPickerForeground: Boolean = false,
    val drainingPicker: Boolean = false,
    val rules: LibraryRules? = null,
    val exclusions: LibraryExclusionPage? = null,
    val filterDraft: Boolean? = null,
    val filterBaseRevision: Long? = null,
    val filterRequiresReview: Boolean = false,
    val filterDraftRejected: Boolean = false,
    val filterForeground: Boolean = false,
    val working: Boolean = false,
    val stopping: Boolean = false,
    val waitingForFileOperation: Boolean = false,
    val sourceIssue: SourceReadIssue? = null,
    val libraryIssue: LibraryIssue? = null,
    val importIssue: ImportIssue? = null,
    val fileIssue: FileOperationIssue? = null,
    val notice: SourcesNotice? = null,
) {
    val canChooseSource: Boolean get() = filterForeground && !working && !stopping && !awaitingPicker && !awaitingPickerForeground
    val filterEditable: Boolean get() = filterForeground && !working && !stopping && rules != null
    val canReviewFilter: Boolean get() = filterEditable && filterDraft != null && filterRequiresReview
    val canSaveFilter: Boolean get() = filterEditable && filterDraft != null && !filterRequiresReview &&
        filterBaseRevision != null && filterBaseRevision >= 0L && filterBaseRevision < Long.MAX_VALUE &&
        filterBaseRevision == rules?.revision
}

/** Selecting a document only previews it. Grant retention, indexing and copying are separate actions. */
class SourcesViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val app = application as MeloXApplication
    private val restoredFilter = restoreFilterProposal()
    private val fallbackNavigation = LibraryNavigationOwner()
    private val pickerRequests = SourcesPickerRequests(savedState)
    @Volatile private var filterIntent = Any()
    private val mutableState = MutableStateFlow(SourcesUiState(
        filterDraft = restoredFilter.proposal?.second,
        filterBaseRevision = restoredFilter.proposal?.first,
        filterRequiresReview = restoredFilter.proposal != null,
        filterDraftRejected = restoredFilter.rejected,
        awaitingPicker = pickerRequests.awaitingPlatform,
        drainingPicker = pickerRequests.restoredDrain,
    ))
    val uiState = mutableState.asStateFlow()
    private var queuedExternalInput: SourcesReturnedInput? = null
    private var selectionFence: () -> Boolean = { false }
    private var visible = false
    private var observer: Job? = null
    private var action: Job? = null
    private var indexReloadAction: Job? = null
    private var filterReviewRequest: Job? = null
    private var copyJob: Job? = null
    private var selectionRequest: Job? = null
    private var selectionRevision = 0L

    fun onVisible(navigation: LibraryNavigationOwner = fallbackNavigation) {
        notePageSelected(true, navigation)
        mutableState.update { it.copy(filterForeground = true) }
        refreshEntryCapabilities()
        if (visible) { consumeIncoming(); return }
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
        // Cached foreground returns refresh the index independently of journal recovery.
        reloadSources(recoverLocal = mutableState.value.page == null)
        consumeIncoming()
    }

    /** Logical route changes retire picker intent; a platform STOP only retires provider work. */
    internal fun notePageSelected(selected: Boolean, navigation: LibraryNavigationOwner) {
        if (pickerRequests.selectPage(navigation.takeIf { selected && it.isCurrent })) {
            queuedExternalInput = null
            selectionFence = { false }
            cancelRead()
            discardPreview()
            retireFilterReadiness()
            mutableState.update { it.copy(selected = null, reauthorization = null) }
        }
        // A synchronous leave/return can coalesce before LifecycleStartEffect sees either route.
        if (selected && navigation.isCurrent && visible) mutableState.update { it.copy(filterForeground = true) }
        publishPickerState()
    }

    fun refreshEntryCapabilities() {
        val capabilities = sourcesEntryCapabilities(app)
        val permission = try {
            if (hasMediaPermission()) SourcesMediaPermission.GRANTED
            else mutableState.value.mediaPermission.takeIf { it == SourcesMediaPermission.SETTINGS_ONLY }
                ?: SourcesMediaPermission.REQUESTABLE
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: RuntimeException) { SourcesMediaPermission.UNKNOWN }
        catch (_: OutOfMemoryError) { SourcesMediaPermission.UNKNOWN }
        mutableState.update { it.copy(entryCapabilities = capabilities, mediaPermission = permission,
            mediaReadIssue = if (permission == SourcesMediaPermission.GRANTED &&
                it.mediaReadIssue in setOf(SourceReadIssue.PERMISSION_DENIED, SourceReadIssue.PERMISSION_REVOKED)) null else it.mediaReadIssue) }
    }

    internal fun beginPicker(kind: SourcesPickerKind): SourcesPickerTicket? {
        if (!visible || !mutableState.value.canChooseSource || !pickerRequests.currentFence()()) return null
        if (kind == SourcesPickerKind.MEDIA_PRIMARY && Build.VERSION.SDK_INT < 30) return null
        refreshEntryCapabilities()
        if (!visible || !pickerRequests.currentFence()()) return null
        val available = when (kind) {
            SourcesPickerKind.FILE -> mutableState.value.entryCapabilities.file
            SourcesPickerKind.TREE -> mutableState.value.entryCapabilities.tree
            SourcesPickerKind.MEDIA_ALL, SourcesPickerKind.MEDIA_PRIMARY -> mutableState.value.entryCapabilities.media
        }
        if (available != SourcesEntryAvailability.AVAILABLE) {
            if (available == SourcesEntryAvailability.UNAVAILABLE && (kind == SourcesPickerKind.FILE || kind == SourcesPickerKind.TREE)) {
                noPicker(kind == SourcesPickerKind.TREE)
            }
            else mutableState.update { it.copy(sourceIssue = SourceReadIssue.SOURCE_UNAVAILABLE) }
            return null
        }
        if (kind.isMedia && mutableState.value.mediaPermission in setOf(SourcesMediaPermission.UNKNOWN, SourcesMediaPermission.SETTINGS_ONLY)) {
            mutableState.update { it.copy(sourceIssue = if (it.mediaPermission == SourcesMediaPermission.SETTINGS_ONLY)
                SourceReadIssue.PERMISSION_DENIED else SourceReadIssue.SOURCE_UNAVAILABLE) }
            return null
        }
        val ticket = pickerRequests.begin(kind)
        if (ticket == null) mutableState.update { it.copy(sourceIssue = SourceReadIssue.BUSY) }
        else mutableState.update { it.copy(sourceIssue = null, notice = null) }
        publishPickerState()
        return ticket
    }

    internal fun awaitingPicker(kind: SourcesPickerKind): SourcesPickerTicket? = pickerRequests.awaiting(kind)
    internal fun awaitingMediaPicker(): SourcesPickerTicket? = pickerRequests.awaitingMedia()

    internal fun pickerLaunchFailed(ticket: SourcesPickerTicket, definitelyNotLaunched: Boolean, denied: Boolean = false) {
        if (pickerRequests.launchFailed(ticket, definitelyNotLaunched)) {
            if (denied) mutableState.update { it.copy(sourceIssue = SourceReadIssue.PERMISSION_DENIED) }
            else if (definitelyNotLaunched && (ticket.kind == SourcesPickerKind.FILE || ticket.kind == SourcesPickerKind.TREE)) {
                noPicker(ticket.kind == SourcesPickerKind.TREE)
            }
            else mutableState.update { it.copy(sourceIssue = SourceReadIssue.SOURCE_UNAVAILABLE) }
            refreshEntryCapabilities()
        }
        publishPickerState()
    }

    internal fun pickerResult(ticket: SourcesPickerTicket, resultCode: Int, uri: Uri?, flags: Int) {
        receivedPickerOutcome(pickerRequests.documentResult(ticket, resultCode, uri, flags))
    }

    internal fun mediaPermissionResult(ticket: SourcesPickerTicket, granted: Boolean, permanentlyDenied: Boolean = false) {
        val outcome = pickerRequests.mediaResult(ticket, granted)
        if (outcome == SourcesPickerOutcome.PERMISSION_DENIED) {
            mutableState.update { it.copy(mediaPermission = if (permanentlyDenied) SourcesMediaPermission.SETTINGS_ONLY
                else SourcesMediaPermission.REQUESTABLE, mediaReadIssue = SourceReadIssue.PERMISSION_DENIED,
                sourceIssue = SourceReadIssue.PERMISSION_DENIED) }
        }
        receivedPickerOutcome(outcome)
    }

    private fun receivedPickerOutcome(outcome: SourcesPickerOutcome) {
        when (outcome) {
            SourcesPickerOutcome.CANCELLED -> pickerCancelled()
            SourcesPickerOutcome.INVALID -> invalidSelection()
            SourcesPickerOutcome.DEFERRED -> consumeIncoming()
            // Retired platform callbacks may drain their slot but cannot overwrite a new intent's UI.
            SourcesPickerOutcome.IGNORED, SourcesPickerOutcome.EXPIRED, SourcesPickerOutcome.PERMISSION_DENIED -> Unit
        }
        publishPickerState()
    }

    private fun publishPickerState() {
        mutableState.update { it.copy(awaitingPicker = pickerRequests.awaitingPlatform,
            awaitingPickerForeground = pickerRequests.awaitingForeground || queuedExternalInput != null,
            drainingPicker = pickerRequests.restoredDrain) }
    }

    private fun consumeIncoming() {
        if (!visible) return
        val input = queuedExternalInput?.also { queuedExternalInput = null } ?: pickerRequests.takeReturn() ?: return
        publishPickerState()
        if (!input.isCurrent()) return
        if (input.kind.isMedia) addPreparedMedia(input)
        else selectRead(if (input.kind == SourcesPickerKind.TREE) LocalSourceKind.TREE else LocalSourceKind.DOCUMENT,
            requireNotNull(input.uri), input.flags, input.isCurrent)
    }

    fun onHidden() {
        retireFilterReadiness()
        visible = false
        observer?.cancel(); observer = null
        cancelRead()
        discardPreview()
    }

    fun onBackground() {
        onHidden()
        copyJob?.cancel()
    }

    fun reload() = reloadSources(recoverLocal = true)

    private fun reloadSources(recoverLocal: Boolean) {
        if (action?.isCompleted == false) return
        runTrackedReadAction(quietIfBusy = true) {
            app.requireBackupStorageReady()
            app.library.initialize()
            val page = app.library.sourcesPage()
            val rules = app.library.rules()
            val exclusions = app.library.exclusionPage()
            currentCoroutineContext().ensureActive()
            mutableState.update { state -> state.copy(page = page, rules = rules, exclusions = exclusions,
                filterRequiresReview = state.filterDraft != null &&
                    (state.filterRequiresReview || state.filterBaseRevision != rules.revision)) }
            if (recoverLocal) {
                val issue = app.recoverLocalState()
                val recoveredPage = app.library.sourcesPage()
                mutableState.update { it.copy(page = recoveredPage, importIssue = issue) }
            }
        }
        indexReloadAction = action.takeIf { !recoverLocal }
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
        pickerRequests.newExternalIntent()
        queuedExternalInput = null
        selectionFence = { false }
        cancelRead()
        mutableState.value.selected?.let { discardPreview() }
        mutableState.update { it.copy(reauthorization = source, selected = null, notice = null) }
        publishPickerState()
    }

    fun cancelReauthorization() {
        pickerRequests.newExternalIntent()
        queuedExternalInput = null
        selectionFence = { false }
        cancelRead()
        discardPreview()
        mutableState.update { it.copy(reauthorization = null, selected = null, notice = SourcesNotice.CANCELLED) }
        publishPickerState()
    }

    fun selected(kind: LocalSourceKind, uri: Uri?, flags: Int) {
        pickerRequests.newExternalIntent()
        queuedExternalInput = null
        cancelRead()
        discardPreview()
        selectionFence = { false }
        mutableState.update { it.copy(selected = null, sourceIssue = null, notice = null) }
        if (kind !in setOf(LocalSourceKind.DOCUMENT, LocalSourceKind.TREE) || uri == null || uri.scheme != "content" ||
            uri.authority.isNullOrBlank() || uri.toString().length > io.github.onlinemo.meloxwatch.data.local.LocalInputLimits.URI_CHARACTERS) {
            invalidSelection()
        } else {
            queuedExternalInput = SourcesReturnedInput(if (kind == LocalSourceKind.TREE) SourcesPickerKind.TREE else SourcesPickerKind.FILE,
                uri, flags, pickerRequests.currentFence())
            consumeIncoming()
        }
        publishPickerState()
    }

    private fun selectRead(kind: LocalSourceKind, uri: Uri, flags: Int, isCurrent: () -> Boolean) {
        cancelRead()
        val requestRevision = selectionRevision
        discardPreview()
        val original = mutableState.value.reauthorization
        mutableState.update { it.copy(selected = null) }
        // Keep one latest picker/intent request while the cancelled physical reader releases its slot.
        selectionRequest = viewModelScope.launch {
            while (action?.isCompleted == false) action?.join()
            currentCoroutineContext().ensureActive()
            if (requestRevision != selectionRevision || !isCurrent()) return@launch
            val waitBudget = SelectionReadBudget(app.localProviderIo.trackReads(), isCurrent)
            runAction(awaitReadRetirement = { waitBudget.retirement.awaitRetired() }, isCurrent = isCurrent) {
                val spec = LocalSourceSpec(UUID.randomUUID().toString(), kind, uri.toString(), flags)
                val observed = readSelectionProvider(waitBudget) {
                    if (original == null) app.localSources.observe(spec)
                    else app.localSources.verifyReauthorization(original.spec, spec)
                }
                val selection = readSelection(observed, original, waitBudget = waitBudget)
                currentCoroutineContext().ensureActive()
                if (requestRevision == selectionRevision && mutableState.value.reauthorization == original && isCurrent()) {
                    selectionFence = isCurrent
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

    fun prepareMediaStore(primary: Boolean): Boolean {
        return beginPicker(if (primary) SourcesPickerKind.MEDIA_PRIMARY else SourcesPickerKind.MEDIA_ALL) != null
    }

    fun addMediaStore() {
        val ticket = awaitingMediaPicker() ?: return
        val granted = try { hasMediaPermission() }
        catch (cancelled: CancellationException) {
            pickerLaunchFailed(ticket, definitelyNotLaunched = true)
            throw cancelled
        }
        catch (_: RuntimeException) { pickerLaunchFailed(ticket, definitelyNotLaunched = true); return }
        catch (_: OutOfMemoryError) { pickerLaunchFailed(ticket, definitelyNotLaunched = true); return }
        mediaPermissionResult(ticket, granted)
    }

    private fun addPreparedMedia(input: SourcesReturnedInput) {
        cancelRead()
        val revision = selectionRevision
        selectionRequest = viewModelScope.launch {
            while (action?.isCompleted == false) action?.join()
            currentCoroutineContext().ensureActive()
            if (revision != selectionRevision || !input.isCurrent()) return@launch
            val retirement = app.localProviderIo.trackReads()
            runAction(awaitReadRetirement = { retirement.awaitRetired() }, isCurrent = input.isCurrent) {
                withContext(retirement) { registerPreparedMedia(input) }
            }
        }
    }

    private suspend fun registerPreparedMedia(input: SourcesReturnedInput) {
        if (!input.isCurrent()) return
        val volume = if (input.kind == SourcesPickerKind.MEDIA_PRIMARY) MediaStore.VOLUME_EXTERNAL_PRIMARY else "external"
        val source = LocalSourceSpec(UUID.randomUUID().toString(), LocalSourceKind.MEDIA_STORE,
            MediaStore.Audio.Media.getContentUri(volume).toString(), mediaVolume = volume)
        val observed = try { app.localSources.observe(source) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: SourceReadException) {
            if (input.isCurrent()) mutableState.update { it.copy(mediaReadIssue = failure.issue) }
            throw failure
        }
        currentCoroutineContext().ensureActive()
        if (!input.isCurrent()) return
        val registered = app.fileOperations.mutations.registerSource(observed.source, observed.access)
        val rules = app.library.rules()
        app.scans.start(registered, rules.revision)
        val page = app.library.sourcesPage()
        if (input.isCurrent()) mutableState.update { it.copy(page = page, rules = rules, notice = SourcesNotice.ADDED,
            mediaReadIssue = null, mediaPermission = SourcesMediaPermission.GRANTED) }
    }

    internal val audioReadPermission: String get() = if (Build.VERSION.SDK_INT >= 33)
        Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasMediaPermission(): Boolean = app.checkSelfPermission(audioReadPermission) == PackageManager.PERMISSION_GRANTED

    fun scan(source: LibrarySource) = runAction {
        val latest = app.library.source(source.spec.sourceId) ?: throw LibraryException(LibraryIssue.SOURCE_UNAVAILABLE)
        val rules = app.library.rules()
        app.scans.start(latest, rules.revision)
    }

    fun update(source: LibrarySource) = runAction {
        val latest = app.library.source(source.spec.sourceId) ?: throw LibraryException(LibraryIssue.SOURCE_UNAVAILABLE)
        app.scans.update(latest, app.library.rules().revision)
    }

    fun retryCueGb18030(source: LibrarySource) = runAction {
        if (source.spec.kind != LocalSourceKind.TREE) throw LibraryException(LibraryIssue.INVALID_INPUT)
        val latest = app.library.source(source.spec.sourceId) ?: throw LibraryException(LibraryIssue.SOURCE_UNAVAILABLE)
        val rules = app.library.rules()
        app.scans.start(latest, rules.revision, cueEncoding = io.github.onlinemo.meloxwatch.data.cue.CueEncoding.GB18030)
    }

    fun cancelScan() {
        val id = mutableState.value.scan?.token?.scanId ?: return
        viewModelScope.launch {
            try { app.scans.cancel(id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (mutableState.value.scan?.token?.scanId == id) publishFailure(failure) }
        }
    }

    fun retryScanStop() {
        val scan = mutableState.value.scan ?: return
        if (scan.phase != io.github.onlinemo.meloxwatch.data.scan.ScanPhase.STOPPING || scan.issue == null) return
        viewModelScope.launch {
            try { app.scans.retryStop(scan.token.scanId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (mutableState.value.scan?.token?.scanId == scan.token.scanId) publishFailure(failure) }
        }
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
        val state = mutableState.value
        if (!state.filterEditable) return
        val rules = requireNotNull(state.rules)
        filterIntent = Any()
        mutableState.update { it.copy(filterDraft = !rules.shortAudioFilter,
            filterBaseRevision = state.filterBaseRevision ?: rules.revision, filterDraftRejected = false) }
        recordFilterProposal()
    }

    fun cancelFilter() {
        filterIntent = Any()
        mutableState.update { it.copy(filterDraft = null, filterBaseRevision = null,
            filterRequiresReview = false, filterDraftRejected = false) }
        recordFilterProposal()
    }

    fun restoreFilterDefaults() {
        val state = mutableState.value
        if (!state.filterEditable) return
        filterIntent = Any()
        mutableState.update { it.copy(filterDraft = false,
            filterBaseRevision = state.filterBaseRevision ?: requireNotNull(state.rules).revision,
            filterDraftRejected = false) }
        recordFilterProposal()
    }

    /** A restored proposal becomes ready only after an explicit, successful read of current rules. */
    fun reviewFilter() {
        val state = mutableState.value
        val indexReload = indexReloadAction?.takeIf { it === action && !it.isCompleted }
        if (!state.canReviewFilter && (indexReload == null || !state.copy(working = false).canReviewFilter)) return
        val draft = requireNotNull(state.filterDraft)
        val base = requireNotNull(state.filterBaseRevision)
        val intent = filterIntent
        filterReviewRequest?.cancel()
        if (indexReload != null) {
            mutableState.update { it.copy(stopping = true) }
            indexReload.cancel()
        }
        // The exact index refresh yields to explicit review; recovery keeps its original owner.
        filterReviewRequest = viewModelScope.launch {
            indexReload?.join()
            currentCoroutineContext().ensureActive()
            if (!currentFilterIntent(intent, draft, base) || !mutableState.value.canReviewFilter) return@launch
            runTrackedReadAction {
                app.requireBackupStorageReady()
                val rules = app.library.rules()
                currentCoroutineContext().ensureActive()
                if (!currentFilterIntent(intent, draft, base)) return@runTrackedReadAction
                if (rules.revision == Long.MAX_VALUE) throw LibraryException(LibraryIssue.RECOVERY_REQUIRED)
                mutableState.update { it.copy(rules = rules, filterBaseRevision = rules.revision,
                    filterRequiresReview = false) }
                recordFilterProposal()
            }
        }
    }

    fun saveFilter() {
        val state = mutableState.value
        if (!state.canSaveFilter) return
        val draft = requireNotNull(state.filterDraft)
        val base = requireNotNull(state.filterBaseRevision)
        val intent = filterIntent
        runTrackedReadAction {
            app.requireBackupStorageReady()
            val rules = app.library.rules()
            currentCoroutineContext().ensureActive()
            if (!currentFilterIntent(intent, draft, base) || mutableState.value.filterRequiresReview) return@runTrackedReadAction
            if (rules.revision != base) {
                mutableState.update { it.copy(rules = rules, filterRequiresReview = true) }
                throw LibraryException(LibraryIssue.CONFLICT)
            }
            val saved = app.fileOperations.withExclusiveLibraryMutation {
                currentCoroutineContext().ensureActive()
                if (!currentFilterIntent(intent, draft, base) || mutableState.value.filterRequiresReview) {
                    throw CancellationException("Filter proposal retired")
                }
                app.library.setShortAudioFilter(base, draft)
            }
            currentCoroutineContext().ensureActive()
            if (!currentFilterIntent(intent, draft, base)) return@runTrackedReadAction
            mutableState.update { it.copy(rules = saved, filterDraft = null, filterBaseRevision = null,
                filterRequiresReview = false, notice = SourcesNotice.RULES_SAVED) }
            recordFilterProposal()
        }
    }

    /** Foreground tasks retire only provider reads they actually acquire, including journal checks. */
    private fun runTrackedReadAction(quietIfBusy: Boolean = false, block: suspend () -> Unit) {
        val retirement = app.localProviderIo.trackReads()
        runAction(quietIfBusy = quietIfBusy, awaitReadRetirement = { retirement.awaitRetired() }) {
            withContext(retirement) { block() }
        }
    }

    private fun currentFilterIntent(intent: Any, draft: Boolean, base: Long): Boolean {
        val state = mutableState.value
        return filterIntent === intent && state.filterForeground && state.filterDraft == draft &&
            state.filterBaseRevision == base
    }

    private fun retireFilterReadiness() {
        filterIntent = Any()
        mutableState.update { it.copy(filterForeground = false, filterRequiresReview = it.filterDraft != null) }
    }

    /** Only a format number, original revision and Boolean survive; no task or access ticket does. */
    private fun recordFilterProposal() {
        val state = mutableState.value
        val base = state.filterBaseRevision
        val draft = state.filterDraft
        if (base == null || draft == null) savedState.remove<Any?>(KEY_FILTER_PROPOSAL)
        else savedState[KEY_FILTER_PROPOSAL] = arrayListOf<Any>(FILTER_PROPOSAL_VERSION, base, draft)
    }

    private fun restoreFilterProposal(): RestoredFilter {
        if (!savedState.contains(KEY_FILTER_PROPOSAL)) return RestoredFilter(null, false)
        val fields = savedState.get<Any?>(KEY_FILTER_PROPOSAL) as? List<*>
        val revision = if (fields?.size == 3) fields[1] as? Long else null
        val enabled = if (fields?.size == 3) fields[2] as? Boolean else null
        if (fields?.size == 3 && fields[0] as? Int == FILTER_PROPOSAL_VERSION &&
            revision != null && revision >= 0L && enabled != null) {
            return RestoredFilter(revision to enabled, false)
        }
        savedState.remove<Any?>(KEY_FILTER_PROPOSAL)
        return RestoredFilter(null, true)
    }

    private data class RestoredFilter(val proposal: Pair<Long, Boolean>?, val rejected: Boolean)

    override fun onCleared() {
        pickerRequests.selectPage(null)
        queuedExternalInput = null
        fallbackNavigation.retire()
        retireFilterReadiness()
        super.onCleared()
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
    fun permissionDenied() {
        awaitingMediaPicker()?.let { mediaPermissionResult(it, granted = false) }
            ?: mutableState.update { it.copy(sourceIssue = SourceReadIssue.PERMISSION_DENIED) }
    }
    fun invalidSelection() { mutableState.update { it.copy(sourceIssue = SourceReadIssue.INVALID_SOURCE) } }

    fun cancelRead() {
        selectionRevision++
        selectionRequest?.cancel(); selectionRequest = null
        filterReviewRequest?.cancel(); filterReviewRequest = null
        if (action?.isActive != true) return
        mutableState.update { it.copy(stopping = true) }
        action?.cancel()
    }

    private fun runAction(
        quietIfBusy: Boolean = false,
        awaitReadRetirement: suspend () -> Unit = { app.localProviderIo.awaitIdle() },
        isCurrent: () -> Boolean = { true },
        block: suspend () -> Unit,
    ) {
        if (action?.isCompleted == false) {
            if (!quietIfBusy) mutableState.update { it.copy(sourceIssue = SourceReadIssue.BUSY) }
            return
        }
        mutableState.update { it.copy(working = true, stopping = false, waitingForFileOperation = false, sourceIssue = null,
            libraryIssue = null, importIssue = null, fileIssue = null, notice = null) }
        action = viewModelScope.launch {
            try { block() }
            catch (error: CancellationException) {
                if (isCurrent()) mutableState.update { it.copy(notice = SourcesNotice.CANCELLED, stopping = true) }
                throw error
            } catch (error: Exception) { if (isCurrent()) publishFailure(error) }
            finally {
                withContext(NonCancellable) {
                    // 最新选择仅等待自己的未退休读；其它动作保持原全局退出契约。
                    awaitReadRetirement()
                }
                mutableState.update { it.copy(working = false, stopping = false, waitingForFileOperation = false) }
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
                filterRequiresReview = state.filterRequiresReview ||
                    error.issue == LibraryIssue.CONFLICT && state.filterDraft != null,
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
        if (!selectionFence()) throw CancellationException("Source selection route retired")
        app.localSources.verifyObservedCandidate(selection.observation.source, selection.observation.root)
        selection.verifiedContentToken?.let { expected ->
            if (app.localMetadata.contentToken(selection.observation.root) != expected) {
                throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
            }
        }
        currentCoroutineContext().ensureActive()
        if (!selectionFence()) throw CancellationException("Source selection route retired")
    }

    private suspend fun readSelection(
        observed: LocalSourceObservation,
        original: LibrarySource?,
        previous: SourceSelection? = null,
        waitBudget: SelectionReadBudget? = null,
    ): SourceSelection {
        if (previous != null) {
            if (observed.identity != previous.observation.identity) throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
            readSelectionProvider(waitBudget) { app.localSources.verifyObservedCandidate(observed.source, previous.observation.root) }
        }
        val candidate = observed.root
        readSelectionProvider(waitBudget) { app.localSources.verifyObservedCandidate(observed.source, candidate) }
        val nativeProof = candidate.providerContentToken?.let { !it.startsWith("sha256:") } == true
        val before = if (!candidate.directory && !nativeProof) {
            readSelectionProvider(waitBudget) { app.localMetadata.contentToken(candidate) }
        } else null
        if (previous?.verifiedContentToken != null && before != previous.verifiedContentToken) {
            throw SourceReadException(SourceReadIssue.SOURCE_CHANGED)
        }
        val metadata = if (candidate.directory) null else readSelectionProvider(waitBudget) { app.localMetadata.read(candidate) }
        readSelectionProvider(waitBudget) { app.localSources.verifyObservedCandidate(observed.source, candidate) }
        if (before != null && readSelectionProvider(waitBudget) { app.localMetadata.contentToken(candidate) } != before) {
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

    /** 最新选择沿单个等待预算重试准入；每个已返回的普通读都已退出物理槽。 */
    private suspend fun <T> readSelectionProvider(budget: SelectionReadBudget?, block: suspend () -> T): T {
        if (budget == null) return block()
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!budget.isCurrent()) throw CancellationException("Source selection route retired")
            try {
                return withContext(budget.retirement) { block() }
            } catch (cancelled: CancellationException) {
                // 取消可能先于底层实际关闭，finally按精确回执等待而非全局空闲。
                throw cancelled
            } catch (failure: Exception) {
                if (failure !is SourceReadException || !app.localProviderIo.isAdmissionBusy(failure)) throw failure
                budget.admissionCollisions++
                if (budget.admissionCollisions >= SELECTION_ADMISSION_COLLISIONS || budget.remainingWaitMs <= 0L) throw failure
                mutableState.update { it.copy(waitingForFileOperation = true) }
                val waitStarted = SystemClock.elapsedRealtime()
                val idle = try {
                    withTimeoutOrNull(budget.remainingWaitMs) {
                        app.localProviderIo.awaitIdle()
                        true
                    }
                } finally {
                    val waitedMs = (SystemClock.elapsedRealtime() - waitStarted).coerceAtLeast(0L)
                    budget.remainingWaitMs = (budget.remainingWaitMs - waitedMs).coerceAtLeast(0L)
                    mutableState.update { it.copy(waitingForFileOperation = false) }
                }
                if (idle != true) throw failure
                // 八次准入上限和被动状态等待同时保留，不在主线程忙等。
                yield()
            }
        }
    }

    private class SelectionReadBudget(
        val retirement: LocalProviderIo.ReadRetirement,
        val isCurrent: () -> Boolean,
        var remainingWaitMs: Long = SELECTION_WAIT_MS,
        var admissionCollisions: Int = 0,
    )

    private companion object {
        const val KEY_FILTER_PROPOSAL = "sources:filter-proposal-v1"
        const val FILTER_PROPOSAL_VERSION = 1
        // 与 DEV-005 单文件检查同用八秒初值，仅计算等待而非重设每个步骤的期限。
        const val SELECTION_WAIT_MS = 8_000L
        const val SELECTION_ADMISSION_COLLISIONS = 8
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
