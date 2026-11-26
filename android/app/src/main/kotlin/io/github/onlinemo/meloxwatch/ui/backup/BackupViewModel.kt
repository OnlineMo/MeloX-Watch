package io.github.onlinemo.meloxwatch.ui.backup

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.onlinemo.meloxwatch.data.backup.BackupCoordinator
import io.github.onlinemo.meloxwatch.data.backup.BackupDocumentDestination
import io.github.onlinemo.meloxwatch.data.backup.BackupDocumentInput
import io.github.onlinemo.meloxwatch.data.backup.BackupDocuments
import io.github.onlinemo.meloxwatch.data.backup.BackupException
import io.github.onlinemo.meloxwatch.data.backup.BackupIssue
import io.github.onlinemo.meloxwatch.data.backup.BackupMergeMode
import io.github.onlinemo.meloxwatch.data.backup.BackupMissingFile
import io.github.onlinemo.meloxwatch.data.backup.BackupRelinkMatch
import io.github.onlinemo.meloxwatch.data.backup.BackupScope
import io.github.onlinemo.meloxwatch.data.backup.BackupSelection
import io.github.onlinemo.meloxwatch.data.backup.BackupStartupState
import io.github.onlinemo.meloxwatch.model.TrackKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 页面/窗口 owner 驱动可见性与取消；显式存储重试共用同一个业务任务租约。 */
@MainThread
class BackupViewModel(
    private val coordinator: BackupCoordinator,
    private val documents: BackupDocuments,
) : ViewModel() {
    private val mutableState = MutableStateFlow(BackupUiState(startup = coordinator.startup.value))
    val state = mutableState.asStateFlow()
    private var pageSelected = false
    private var visible = false
    private var epoch = 0L
    private var draftRevision = 0L
    private var observer: Job? = null
    private var operation: Job? = null
    private var retirement: PreviewRetirement? = null
    private var restoreInput: BackupDocumentInput? = null
    private var picker: Picker? = null
    private var queuedPick: QueuedPick? = null
    private var queuedMissingPage: MissingPageRequest? = null
    private var targetRequest: BackupRelinkTargetRequest? = null
    private var queuedTarget: RelinkTarget? = null

    /** 目标选择页可遮住备份窗口，同一流程仍保留尚未退休的 opaque 票据。 */
    val pendingRelinkTargetRequest: BackupRelinkTargetRequest?
        get() = targetRequest?.takeUnless { it.retired }

    /** 路由离开会退休当前意图；外部系统选择器暂时遮住窗口则只 setVisible(false)。 */
    fun setPageSelected(selected: Boolean) {
        if (pageSelected == selected) return
        pageSelected = selected
        if (!selected) {
            draftRevision++
            picker?.retired = true
            queuedPick = null
            queuedMissingPage = null
            targetRequest?.retired = true
            queuedTarget = null
            setVisible(false)
        }
    }

    fun setVisible(value: Boolean) {
        val actual = value && pageSelected
        if (visible == actual) return
        visible = actual
        epoch++
        observer?.cancel()
        observer = null
        if (!actual) {
            operation?.cancel()
            retirePreviews()
            mutableState.update { it.copy(progress = null, effectsAcknowledged = false,
                fontLicenseAcknowledged = false, allowDifferentContent = false, allowCueDifferences = false, cancellable = false,
                cancelling = operation != null) }
            return
        }
        mutableState.update { it.copy(startup = coordinator.startup.value,
            progress = if (operation != null) coordinator.progress.value else null) }
        val expected = epoch
        observer = viewModelScope.launch {
            combine(coordinator.startup, coordinator.progress) { startup, progress -> startup to progress }
                .collect { (startup, progress) ->
                    if (isCurrent(expected)) mutableState.update { it.copy(startup = startup,
                        progress = if (operation != null) progress else null) }
                }
        }
        resumeQueuedPick()
    }

    fun beginExport() = open(BackupUiPage.EXPORT)
    fun beginRestore() = open(BackupUiPage.RESTORE)

    fun home() {
        if (!canAct()) return
        retirePreviews()
        restoreInput = null
        queuedMissingPage = null
        draftRevision++
        mutableState.update { it.copy(page = BackupUiPage.HOME, hasRestoreInput = false,
            privacyAcknowledged = false, effectsAcknowledged = false, fontLicenseAcknowledged = false,
            issue = null, cancelled = false, selectedMissing = null, cueRelinkTrackId = null, allowDifferentContent = false, allowCueDifferences = false) }
    }

    private fun open(page: BackupUiPage) {
        if (!canAct()) return
        retirePreviews()
        restoreInput = null
        queuedMissingPage = null
        draftRevision++
        mutableState.update { it.copy(page = page, hasRestoreInput = false, issue = null,
            pickerUnavailable = false, cancelled = false, exportReceipt = null, restoreReceipt = null,
            privacyAcknowledged = false, effectsAcknowledged = false, fontLicenseAcknowledged = false,
            selectedMissing = null, cueRelinkTrackId = null, allowDifferentContent = false, allowCueDifferences = false) }
    }

    fun setScope(scope: BackupScope, selected: Boolean) {
        if (!canAct()) return
        val scopes = mutableState.value.scopes.toMutableSet()
        if (selected) scopes.add(scope) else scopes.remove(scope)
        if (BackupScope.LIBRARY !in scopes && BackupScope.PREFERENCES !in scopes && BackupScope.LYRICS !in scopes) scopes.remove(BackupScope.RESOURCES)
        if (scopes == mutableState.value.scopes) return
        draftRevision++
        mutableState.update { it.copy(scopes = scopes.toSet(), privacyAcknowledged = false,
            effectsAcknowledged = false, fontLicenseAcknowledged = false, issue = null) }
    }

    fun setMode(mode: BackupMergeMode) {
        if (!canAct() || mode == mutableState.value.mode) return
        draftRevision++
        mutableState.update { it.copy(mode = mode, effectsAcknowledged = false, fontLicenseAcknowledged = false, issue = null) }
    }

    fun acknowledgePrivacy(value: Boolean) {
        if (canAct()) mutableState.update { it.copy(privacyAcknowledged = value) }
    }
    fun acknowledgeEffects(value: Boolean) {
        if (canAct()) mutableState.update { it.copy(effectsAcknowledged = value) }
    }
    fun acknowledgeFontLicense(value: Boolean) {
        if (canAct()) mutableState.update { it.copy(fontLicenseAcknowledged = value) }
    }
    fun acknowledgeDifferentContent(value: Boolean) {
        if (canAct()) mutableState.update { it.copy(allowDifferentContent = value) }
    }
    fun acknowledgeCueDifferences(value: Boolean) {
        if (canAct()) mutableState.update { it.copy(allowCueDifferences = value) }
    }


    /** 只容许一个外部选择器；退休结果仍占回执槽，不能套用给后来的一次选择。 */
    fun beginDestinationPicker(): Boolean {
        val current = mutableState.value
        if (!canAct() || !current.canChooseExport || current.page != BackupUiPage.EXPORT || picker != null) return false
        picker = Picker(PickerKind.DESTINATION, draftRevision, current.scopes.toSet(), current.mode)
        mutableState.update { it.copy(pickerPending = true, pickerUnavailable = false, exportReceipt = null) }
        return true
    }

    fun beginInputPicker(): Boolean {
        val current = mutableState.value
        if (!canAct() || current.page != BackupUiPage.RESTORE || picker != null) return false
        picker = Picker(PickerKind.INPUT, draftRevision, current.scopes.toSet(), current.mode)
        mutableState.update { it.copy(pickerPending = true, pickerUnavailable = false) }
        return true
    }

    fun destinationPicked(destination: BackupDocumentDestination?) {
        val captured = takePicker(PickerKind.DESTINATION) ?: return
        if (destination == null || captured.retired || captured.revision != draftRevision || !pageSelected) return
        queuedPick = QueuedPick.Destination(captured, destination)
        resumeQueuedPick()
    }

    fun inputPicked(input: BackupDocumentInput?) {
        val captured = takePicker(PickerKind.INPUT) ?: return
        if (input == null || captured.retired || captured.revision != draftRevision || !pageSelected) return
        queuedPick = QueuedPick.Input(captured, input)
        resumeQueuedPick()
    }

    fun pickerUnavailable() {
        picker = null
        queuedPick = null
        mutableState.update { it.copy(pickerPending = false, pickerUnavailable = true) }
    }

    private fun takePicker(kind: PickerKind): Picker? {
        val captured = picker ?: return null
        if (captured.kind != kind) return null
        picker = null
        mutableState.update { it.copy(pickerPending = false) }
        return captured
    }

    private fun resumeQueuedPick() {
        if (!canAct()) return
        val target = queuedTarget
        if (target != null) {
            queuedTarget = null
            if (!target.request.retired && target.request.draftRevision == draftRevision) {
                previewRelinkTarget(target.request.missingTrackId, target.track, target.preferRegisteredNative)
            }
            return
        }
        val queued = queuedPick
        if (queued == null) {
            val request = queuedMissingPage
            if (request != null) {
                queuedMissingPage = null
                loadMissing(request.after)
            }
            return
        }
        queuedPick = null
        if (queued.picker.retired || queued.picker.revision != draftRevision) return
        when (queued) {
            is QueuedPick.Destination -> launchOperation { expected ->
                val receipt = coordinator.export(BackupSelection(queued.picker.scopes), queued.destination)
                if (isCurrent(expected)) mutableState.update { it.copy(exportReceipt = receipt, privacyAcknowledged = false) }
            }
            is QueuedPick.Input -> {
                restoreInput = queued.input
                mutableState.update { it.copy(hasRestoreInput = true) }
                preview(queued.input, queued.picker.mode, null)
            }
        }
    }

    fun recheckRestore() {
        val input = restoreInput ?: return
        val current = mutableState.value
        if (!canAct() || current.scopes.isEmpty()) return
        preview(input, current.mode, current.scopes)
    }

    private fun preview(input: BackupDocumentInput, mode: BackupMergeMode, scopes: Set<BackupScope>?) {
        launchOperation { expected ->
            val old = mutableState.value.restorePreview?.token
            mutableState.update { it.copy(restorePreview = null, effectsAcknowledged = false, fontLicenseAcknowledged = false) }
            if (old != null) coordinator.discardRestorePreview(old)
            val preview = coordinator.previewRestore(input, mode, scopes)
            if (!isCurrent(expected)) {
                withContext(NonCancellable) { coordinator.discardRestorePreview(preview.token) }
                return@launchOperation
            }
            mutableState.update { it.copy(restorePreview = preview, scopes = preview.scopes.toSet(), mode = preview.mode,
                effectsAcknowledged = false, fontLicenseAcknowledged = false, restoreReceipt = null) }
        }
    }

    fun confirmRestore() {
        val current = mutableState.value
        val preview = current.restorePreview ?: return
        if (!canAct() || !current.canRestore) return
        val acknowledged = current.fontLicenseAcknowledged
        mutableState.update { it.copy(effectsAcknowledged = false, fontLicenseAcknowledged = false) }
        launchOperation { expected ->
            val receipt = coordinator.confirmRestore(preview.token, acknowledged)
            if (isCurrent(expected)) mutableState.update { it.copy(restoreReceipt = receipt, restorePreview = null,
                effectsAcknowledged = false, fontLicenseAcknowledged = false) }
        }
    }

    fun showMissingFiles() {
        if (!canAct()) return
        mutableState.update { it.copy(page = BackupUiPage.MISSING_FILES, selectedMissing = null, cueRelinkTrackId = null,
            missingFiles = emptyList(), missingPageLoaded = false, missingPageAfter = null, nextMissingPage = null, issue = null) }
        queuedMissingPage = MissingPageRequest(null)
        retirePreviews()
        resumeQueuedPick()
    }

    fun firstMissingPage() = loadMissing(null)
    fun nextMissingPage() { mutableState.value.nextMissingPage?.let(::loadMissing) }

    private fun loadMissing(after: String?) {
        launchOperation { expected ->
            val page = coordinator.missingRelinks(after = after, limit = 100)
            if (isCurrent(expected)) mutableState.update { it.copy(page = BackupUiPage.MISSING_FILES,
                missingFiles = page.items.toList(), missingPageLoaded = true, missingPageAfter = after, nextMissingPage = page.next,
                selectedMissing = null, cueRelinkTrackId = null, relinkPreview = null, allowDifferentContent = false, allowCueDifferences = false) }
        }
    }

    fun selectMissing(file: BackupMissingFile) {
        if (!canAct() || mutableState.value.missingFiles.none { it.hintId == file.hintId && it.trackId == file.trackId }) return
        draftRevision++
        mutableState.update { it.copy(page = BackupUiPage.RELINK, selectedMissing = file,
            relinkPreview = null, relinkReceipt = null, allowDifferentContent = false, allowCueDifferences = false,
            cueRelinkTrackId = file.trackId.takeIf { file.isCue }, issue = null) }
    }

    /** 目标仅来自已授权曲库；Activity 保持同一流程 owner，窗口隐藏时不做 I/O。 */
    fun beginRelinkTargetRequest(): BackupRelinkTargetRequest? {
        val missing = mutableState.value.selectedMissing ?: return null
        if (!canAct() || targetRequest != null) return null
        val request = BackupRelinkTargetRequest(missing.trackId, missing.hintId, draftRevision, missing.isCue)
        targetRequest = request
        mutableState.update { it.copy(targetPickerPending = true) }
        return request
    }

    fun relinkTargetPicked(request: BackupRelinkTargetRequest, target: TrackKey?, preferRegisteredNative: Boolean = false) {
        if (targetRequest !== request) return
        targetRequest = null
        mutableState.update { it.copy(targetPickerPending = false) }
        val missing = mutableState.value.selectedMissing ?: return
        if (target == null || request.retired || request.draftRevision != draftRevision || !pageSelected ||
            missing.trackId != request.missingTrackId || missing.hintId != request.hintId) return
        queuedTarget = RelinkTarget(request, target, preferRegisteredNative && request.cueTarget)
        resumeQueuedPick()
    }

    private fun previewRelinkTarget(missingTrackId: String, target: TrackKey, preferRegisteredNative: Boolean) {
        launchOperation { expected ->
            mutableState.value.relinkPreview?.token?.let { coordinator.discardRelinkPreview(it) }
            mutableState.update { it.copy(relinkPreview = null, allowDifferentContent = false, allowCueDifferences = false) }
            val preview = if (mutableState.value.cueRelinkTrackId == missingTrackId)
                coordinator.previewCueRelink(missingTrackId, target, preferRegisteredNative) else coordinator.previewRelink(missingTrackId, target)
            if (!isCurrent(expected)) {
                withContext(NonCancellable) { coordinator.discardRelinkPreview(preview.token) }
                return@launchOperation
            }
            mutableState.update { it.copy(relinkPreview = preview, allowDifferentContent = false, allowCueDifferences = false) }
        }
    }

    fun confirmRelink() {
        val current = mutableState.value
        val preview = current.relinkPreview ?: return
        if (!canAct() || (preview.match != BackupRelinkMatch.VERIFIED_MATCH && !current.allowDifferentContent) ||
            preview.cueDifferences?.requiresExplicitConfirmation == true && !current.allowCueDifferences) return
        mutableState.update { it.copy(allowDifferentContent = false, allowCueDifferences = false) }
        launchOperation { expected ->
            val receipt = coordinator.confirmRelink(preview.token, current.allowDifferentContent, current.allowCueDifferences)
            if (isCurrent(expected)) {
                mutableState.update { it.copy(page = BackupUiPage.MISSING_FILES, selectedMissing = null, cueRelinkTrackId = null,
                    relinkPreview = null, relinkReceipt = receipt, allowDifferentContent = false, allowCueDifferences = false) }
                val page = coordinator.missingRelinks(mutableState.value.missingPageAfter, 100)
                if (isCurrent(expected)) mutableState.update { it.copy(missingFiles = page.items.toList(), missingPageLoaded = true, nextMissingPage = page.next) }
            }
        }
    }

    fun retryOutputCleanup() = launchOperation { documents.recoverPendingOutput() }

    fun retryStorage() {
        if (!visible || !pageSelected || operation != null || retirement != null || picker != null || targetRequest != null ||
            queuedPick != null || queuedMissingPage != null || queuedTarget != null ||
            coordinator.startup.value !is BackupStartupState.Unavailable) return
        launchOwnedOperation { expected ->
            val startup = coordinator.initializeStorage()
            if (isCurrent(expected)) mutableState.update { it.copy(startup = startup,
                issue = (startup as? BackupStartupState.Unavailable)?.issue) }
        }
    }

    fun cancel() {
        if (!visible || !pageSelected || operation == null) return
        mutableState.update { it.copy(cancelling = true, cancellable = false) }
        operation?.cancel()
    }

    private fun launchOperation(action: suspend (Long) -> Unit) {
        if (!canAct()) return
        launchOwnedOperation(action)
    }

    private fun launchOwnedOperation(action: suspend (Long) -> Unit) {
        val expected = epoch
        val exact = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try { action(expected) }
            catch (cancelled: CancellationException) {
                if (isCurrent(expected)) mutableState.update { it.copy(cancelled = true) }
                throw cancelled
            } catch (failure: Exception) {
                if (isCurrent(expected)) mutableState.update { it.copy(issue = (failure as? BackupException)?.issue ?: BackupIssue.IO) }
            } finally {
                if (operation === currentCoroutineContext()[Job]) {
                    operation = null
                    mutableState.update { it.copy(working = retirement != null, cancellable = false, cancelling = false, progress = null) }
                    if (visible && pageSelected) resumeQueuedPick()
                }
            }
        }
        operation = exact
        mutableState.update { it.copy(working = true, cancellable = true, cancelling = false, cancelled = false, issue = null) }
        exact.start()
    }

    private fun retirePreviews() {
        val current = mutableState.value
        val restoreToken = current.restorePreview?.token
        val relinkToken = current.relinkPreview?.token
        mutableState.update { it.copy(restorePreview = null, relinkPreview = null,
            effectsAcknowledged = false, fontLicenseAcknowledged = false, allowDifferentContent = false, allowCueDifferences = false) }
        if (restoreToken == null && relinkToken == null) return
        check(retirement == null)
        val retiring = PreviewRetirement(restoreToken, relinkToken, operation)
        retirement = retiring
        mutableState.update { it.copy(working = true, cancellable = operation != null && !it.cancelling) }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable) {
                    retiring.previous?.join()
                    discardTokens(retiring)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.update { it.copy(issue = (failure as? BackupException)?.issue ?: BackupIssue.IO) }
            } finally {
                if (retirement === retiring) {
                    retirement = null
                    mutableState.update { it.copy(working = operation != null) }
                    if (visible && pageSelected) resumeQueuedPick()
                }
            }
        }
    }

    private suspend fun discardTokens(retiring: PreviewRetirement) {
        var failure: Exception? = null
        try {
            if (retiring.restoreToken != null) coordinator.discardRestorePreview(retiring.restoreToken)
        } catch (error: Exception) { failure = error }
        try {
            if (retiring.relinkToken != null) coordinator.discardRelinkPreview(retiring.relinkToken)
        } catch (error: Exception) {
            val previous = failure
            if (previous == null) failure = error else if (previous !== error) previous.addSuppressed(error)
        }
        failure?.let { throw it }
    }

    private fun isCurrent(expected: Long): Boolean = visible && pageSelected && epoch == expected
    private fun canAct(): Boolean = visible && pageSelected && mutableState.value.canEdit && operation == null && retirement == null

    override fun onCleared() {
        pageSelected = false
        visible = false
        epoch++
        observer?.cancel()
        operation?.cancel()
        picker?.retired = true
        queuedPick = null
        queuedMissingPage = null
        targetRequest?.retired = true
        queuedTarget = null
        retirePreviews()
        super.onCleared()
    }

    private enum class PickerKind { INPUT, DESTINATION }
    private class PreviewRetirement(val restoreToken: String?, val relinkToken: String?, val previous: Job?)
    private class RelinkTarget(val request: BackupRelinkTargetRequest, val track: TrackKey, val preferRegisteredNative: Boolean)
    private class MissingPageRequest(val after: String?)
    private class Picker(val kind: PickerKind, val revision: Long, val scopes: Set<BackupScope>, val mode: BackupMergeMode) {
        var retired = false
        override fun toString(): String = "BackupPicker"
    }
    private sealed interface QueuedPick {
        val picker: Picker
        class Input(override val picker: Picker, val input: BackupDocumentInput) : QueuedPick
        class Destination(override val picker: Picker, val destination: BackupDocumentDestination) : QueuedPick
    }
}
