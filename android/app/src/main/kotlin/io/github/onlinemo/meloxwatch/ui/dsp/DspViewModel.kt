package io.github.onlinemo.meloxwatch.ui.dsp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.onlinemo.meloxwatch.data.backup.BackupAccessState
import io.github.onlinemo.meloxwatch.data.backup.BackupMutationBarrier
import io.github.onlinemo.meloxwatch.playback.dsp.BuiltinDspPresets
import io.github.onlinemo.meloxwatch.playback.dsp.DspLimits
import io.github.onlinemo.meloxwatch.playback.dsp.DspPreset
import io.github.onlinemo.meloxwatch.playback.dsp.DspRuntime
import io.github.onlinemo.meloxwatch.playback.dsp.DspSaveResult
import io.github.onlinemo.meloxwatch.playback.dsp.DspSettings
import io.github.onlinemo.meloxwatch.playback.dsp.DspSettingsIssue
import io.github.onlinemo.meloxwatch.playback.dsp.DspSettingsStore
import io.github.onlinemo.meloxwatch.playback.dsp.DspSnapshot
import io.github.onlinemo.meloxwatch.playback.dsp.DspStorageException
import io.github.onlinemo.meloxwatch.playback.dsp.normalizedPresetName
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DspEditorIssue { PRESET_NAME_INVALID, PRESET_NAME_CONFLICT, PRESET_LIMIT, PREVIEW_CONFLICT, READ_REQUIRED }

data class DspEditorState(
    val saved: DspSnapshot = DspSnapshot(null),
    val draft: DspSettings? = null,
    val presets: List<DspPreset> = emptyList(),
    val baseRevision: Long? = null,
    val visible: Boolean = false,
    val working: Boolean = false,
    val backupBusy: Boolean = false,
    val requiresRead: Boolean = true,
    val previewEnabled: Boolean = false,
    val savedFeedback: Boolean = false,
    val storageIssue: DspSettingsIssue? = null,
    val editorIssue: DspEditorIssue? = null,
) {
    val editable: Boolean get() = visible && !working && !backupBusy && !requiresRead && draft != null && baseRevision != null
    val dirty: Boolean get() = draft != saved.saved?.settings || presets != saved.saved?.presets
}

/** 草稿和预览不落盘；CAS 失败或中途离页后必须回读，不重放保存命令。 */
class DspViewModel(
    private val store: DspSettingsStore,
    private val runtime: DspRuntime,
    private val backupBarrier: BackupMutationBarrier,
) : ViewModel() {
    private val previewOwner = Any()
    private val mutableState = MutableStateFlow(DspEditorState())
    val state = mutableState.asStateFlow()
    private var observation: Job? = null
    private var backupObservation: Job? = null
    private var runtimeObservation: Job? = null
    private var operation: Job? = null
    private var operationTicket: Any? = null

    fun setVisible(visible: Boolean) {
        if (visible == state.value.visible) return
        mutableState.value = state.value.copy(visible = visible)
        if (!visible) {
            observation?.cancel()
            observation = null
            backupObservation?.cancel()
            backupObservation = null
            runtimeObservation?.cancel()
            runtimeObservation = null
            runtime.endPreview(previewOwner)
            operation?.cancel()
            mutableState.value = state.value.copy(previewEnabled = false,
                requiresRead = state.value.requiresRead || operation != null)
            return
        }
        backupObservation = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            backupBarrier.state.collect { access ->
                val wasBusy = state.value.backupBusy
                val busy = access != BackupAccessState.READY
                if (busy) runtime.endPreview(previewOwner)
                mutableState.value = state.value.copy(backupBusy = busy,
                    previewEnabled = if (busy) false else state.value.previewEnabled)
                if (wasBusy && !busy && state.value.requiresRead && operation == null) retryRead()
            }
        }
        observation = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            store.state.collect { actual ->
                if (actual.saved != null || actual.issue != null) accept(actual, readCompleted = false)
            }
        }
        runtimeObservation = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            runtime.state.collect {
                // sink 重建会撤销临时预览；即使 StateFlow 合并关闭事件，界面仍显示真实预览所有权。
                if (state.value.previewEnabled && !runtime.hasPreview(previewOwner)) {
                    mutableState.value = state.value.copy(previewEnabled = false)
                }
            }
        }
        if (operation == null) retryRead()
    }

    fun retryRead() {
        if (!state.value.visible || operation != null || state.value.backupBusy) return
        begin {
            val actual = withContext(Dispatchers.IO) { store.read() }
            accept(actual, readCompleted = true)
        }
    }

    fun change(settings: DspSettings) {
        if (!state.value.editable) return
        mutableState.value = state.value.copy(draft = settings.immutableCopy(), savedFeedback = false,
            editorIssue = null, storageIssue = null)
        if (state.value.previewEnabled) applyPreview()
    }

    fun enablePreview(enabled: Boolean) {
        if (!state.value.editable) return
        if (enabled && (!runtime.state.value.processing || runtime.state.value.issue != null)) return
        if (!enabled) {
            runtime.endPreview(previewOwner)
            mutableState.value = state.value.copy(previewEnabled = false)
        } else {
            mutableState.value = state.value.copy(previewEnabled = true, savedFeedback = false)
            applyPreview()
        }
    }

    private fun applyPreview() {
        val draft = state.value.draft ?: return
        val revision = state.value.baseRevision ?: return
        // 此操作不挂起或排队；与恢复代次同一 admission 下发布原子参数。
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                backupBarrier.mutation({ DspStorageException(DspSettingsIssue.BUSY) }) {
                    if (!runtime.preview(previewOwner, revision, draft)) {
                        runtime.endPreview(previewOwner)
                        mutableState.value = state.value.copy(previewEnabled = false,
                            editorIssue = DspEditorIssue.PREVIEW_CONFLICT, requiresRead = true)
                    }
                }
            } catch (error: DspStorageException) {
                runtime.endPreview(previewOwner)
                mutableState.value = state.value.copy(previewEnabled = false, storageIssue = error.issue)
            }
        }
    }

    fun selectPreset(id: String) {
        if (!state.value.editable) return
        val preset = (BuiltinDspPresets.values + state.value.presets).singleOrNull { it.id == id } ?: return
        val draft = state.value.draft ?: return
        change(draft.copy(selectedPresetId = preset.id, bandGainTenthsDb = preset.bandGainTenthsDb.toList()))
    }

    fun saveNamedPreset(name: String, replaceId: String? = null) {
        if (!state.value.editable) return
        val actual = state.value
        val draft = actual.draft ?: return
        val selected = replaceId?.let { id -> actual.presets.singleOrNull { it.id == id } }
        if (replaceId != null && selected == null) return
        val clean = name.trim()
        val preset = try { DspPreset(selected?.id ?: "custom_" + UUID.randomUUID().toString(), clean,
            draft.bandGainTenthsDb.toList()) }
        catch (_: IllegalArgumentException) {
            mutableState.value = actual.copy(editorIssue = DspEditorIssue.PRESET_NAME_INVALID)
            return
        }
        if (actual.presets.any { it.id != preset.id && normalizedPresetName(it.name) == normalizedPresetName(clean) }) {
            mutableState.value = actual.copy(editorIssue = DspEditorIssue.PRESET_NAME_CONFLICT)
            return
        }
        if (selected == null && actual.presets.size == DspLimits.MAX_PRESETS) {
            mutableState.value = actual.copy(editorIssue = DspEditorIssue.PRESET_LIMIT)
            return
        }
        val presets = if (selected == null) actual.presets + preset else actual.presets.map { if (it.id == preset.id) preset else it }
        mutableState.value = actual.copy(presets = presets.toList(), savedFeedback = false, editorIssue = null)
        change(draft.copy(selectedPresetId = preset.id))
    }

    fun renamePreset(id: String, name: String) {
        if (!state.value.editable) return
        val actual = state.value
        val preset = actual.presets.singleOrNull { it.id == id } ?: return
        val clean = name.trim()
        val replacement = try { preset.copy(name = clean) }
        catch (_: IllegalArgumentException) {
            mutableState.value = actual.copy(editorIssue = DspEditorIssue.PRESET_NAME_INVALID)
            return
        }
        if (actual.presets.any { it.id != id && normalizedPresetName(it.name) == normalizedPresetName(clean) }) {
            mutableState.value = actual.copy(editorIssue = DspEditorIssue.PRESET_NAME_CONFLICT)
            return
        }
        mutableState.value = actual.copy(presets = actual.presets.map { if (it.id == id) replacement else it },
            savedFeedback = false, editorIssue = null)
    }

    /** 调用者先展示具体配置名并独立确认；当前曲线保留，只移除命名引用。 */
    fun deletePreset(id: String) {
        if (!state.value.editable || state.value.presets.none { it.id == id }) return
        val actual = state.value
        val draft = actual.draft ?: return
        mutableState.value = actual.copy(presets = actual.presets.filter { it.id != id }, savedFeedback = false)
        if (draft.selectedPresetId == id) change(draft.copy(selectedPresetId = null))
    }

    fun defaults() = change(DspSettings())

    fun cancel() {
        runtime.endPreview(previewOwner)
        val actual = state.value
        val saved = actual.saved.saved
        mutableState.value = actual.copy(draft = saved?.settings, presets = saved?.presets.orEmpty(),
            baseRevision = saved?.revision, previewEnabled = false, savedFeedback = false, editorIssue = null)
    }

    fun save() {
        val actual = state.value
        if (!actual.editable || operation != null) return
        val revision = actual.baseRevision ?: return
        val settings = actual.draft?.immutableCopy() ?: return
        val presets = actual.presets.map { it.copy(bandGainTenthsDb = it.bandGainTenthsDb.toList()) }
        begin {
            val result = withContext(Dispatchers.IO) { store.compareAndSet(revision, settings, presets) }
            runtime.endPreview(previewOwner)
            when (result) {
                is DspSaveResult.Saved -> {
                    accept(result.snapshot, readCompleted = true, preserveDraft = false)
                    val current = state.value
                    val same = current.saved == result.snapshot
                    mutableState.value = current.copy(savedFeedback = same && current.saved.issue == null,
                        storageIssue = current.storageIssue ?: if (same) null else DspSettingsIssue.CONFLICT,
                        previewEnabled = false)
                }
                is DspSaveResult.Conflict -> {
                    accept(result.actual, readCompleted = true, preserveDraft = false)
                    mutableState.value = state.value.copy(storageIssue = DspSettingsIssue.CONFLICT, previewEnabled = false)
                }
                is DspSaveResult.Failed -> {
                    mutableState.value = state.value.copy(storageIssue = result.issue, previewEnabled = false,
                        requiresRead = true)
                }
            }
        }
    }

    fun recoverDefaults(token: String) {
        if (!state.value.visible || state.value.backupBusy || operation != null || state.value.saved.recoveryToken != token) return
        begin {
            when (val result = withContext(Dispatchers.IO) { store.recoverDefaults(token) }) {
                is DspSaveResult.Saved -> accept(result.snapshot, readCompleted = true, preserveDraft = false)
                is DspSaveResult.Conflict -> accept(result.actual, readCompleted = true, preserveDraft = false)
                is DspSaveResult.Failed -> mutableState.value = state.value.copy(storageIssue = result.issue, requiresRead = true)
            }
        }
    }

    private fun accept(snapshot: DspSnapshot, readCompleted: Boolean, preserveDraft: Boolean = true) {
        val published = store.state.value
        // 已返回的读/CAS 应答可以晚于另一次真实提交；恢复期间只读可见代次，不提前展示原生值。
        val actual = if (snapshot == published) snapshot
            else if (snapshot.issue == DspSettingsIssue.BUSY) published.copy(issue = snapshot.issue)
            else published
        val previous = state.value
        val saved = actual.saved
        val valid = saved != null && actual.issue == null
        val same = saved?.revision == previous.baseRevision
        val preserve = preserveDraft && valid && same && previous.draft != null
        if (saved != null) runtime.setSaved(saved)
        if (!same || !valid) runtime.endPreview(previewOwner)
        mutableState.value = previous.copy(saved = actual,
            draft = if (preserve) previous.draft else saved?.settings,
            presets = if (preserve) previous.presets else saved?.presets.orEmpty(),
            baseRevision = saved?.revision, storageIssue = actual.issue,
            previewEnabled = previous.previewEnabled && same && valid,
            savedFeedback = previous.savedFeedback && actual == previous.saved,
            requiresRead = if (readCompleted) !valid else previous.requiresRead || !valid)
    }

    private fun begin(action: suspend () -> Unit) {
        val ticket = Any()
        operationTicket = ticket
        mutableState.value = state.value.copy(working = true, savedFeedback = false, editorIssue = null)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try { action() }
            catch (cancelled: CancellationException) {
                runtime.endPreview(previewOwner)
                if (operationTicket === ticket) mutableState.value = state.value.copy(requiresRead = true, previewEnabled = false)
                throw cancelled
            } catch (_: Exception) {
                runtime.endPreview(previewOwner)
                if (operationTicket === ticket) mutableState.value = state.value.copy(requiresRead = true,
                    previewEnabled = false, storageIssue = DspSettingsIssue.STORAGE_UNAVAILABLE)
            } finally {
                if (operationTicket === ticket) {
                    operation = null
                    operationTicket = null
                    mutableState.value = state.value.copy(working = false)
                }
            }
        }
        operation = job
        job.start()
    }

    override fun onCleared() {
        runtime.endPreview(previewOwner)
        observation?.cancel()
        backupObservation?.cancel()
        runtimeObservation?.cancel()
        operation?.cancel()
        super.onCleared()
    }
}
