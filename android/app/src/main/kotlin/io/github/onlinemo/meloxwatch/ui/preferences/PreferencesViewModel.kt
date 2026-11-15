package io.github.onlinemo.meloxwatch.ui.preferences

import android.app.Application
import androidx.annotation.MainThread
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesFailure
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesState
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesWriteResult
import io.github.onlinemo.meloxwatch.data.preferences.ResetConfirmation
import io.github.onlinemo.meloxwatch.data.preferences.UserPreferencesRepositoryFactory
import io.github.onlinemo.meloxwatch.model.ScreenShape
import io.github.onlinemo.meloxwatch.model.UserPreferences
import io.github.onlinemo.meloxwatch.model.UserPreferencesDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PreferencesEditOperation {
    SAVE,
    RESET,
}

enum class PreferencesEditFeedback {
    SAVED,
    UNCHANGED,
    DEFAULTS_RESTORED,
    DEFAULTS_ALREADY_SET,
    RESET_CANCELLED,
}

data class PreferencesEditorState(
    val storage: PreferencesState = PreferencesState.Loading,
    val isEditing: Boolean = false,
    val baseRevision: Long? = null,
    val draft: UserPreferencesDraft? = null,
    val isSaving: Boolean = false,
    val confirmReset: Boolean = false,
    val failure: PreferencesFailure? = null,
    val failedOperation: PreferencesEditOperation? = null,
    val conflict: UserPreferences? = null,
    val feedback: PreferencesEditFeedback? = null,
    val previewEnabled: Boolean = true,
) {
    /** Ready 是本次确认的值；Unavailable 只能提供最后确认值或临时安全默认。 */
    val effective: UserPreferences
        get() = when (val current = storage) {
            is PreferencesState.Ready -> current.preferences
            is PreferencesState.Unavailable -> current.lastKnown ?: UserPreferences()
            PreferencesState.Loading -> UserPreferences()
        }

    val hasConflict: Boolean
        get() = conflict != null || (
            isEditing && !isSaving && draft != null && baseRevision != null &&
                storage is PreferencesState.Ready && baseRevision != storage.preferences.revision
            )

    val canEdit: Boolean
        get() = isEditing && draft != null && baseRevision != null && !isSaving && !confirmReset &&
            !hasConflict && storage is PreferencesState.Ready && storage.canWrite

    val canConfirmReset: Boolean
        get() = isEditing && draft != null && baseRevision != null && !isSaving && confirmReset &&
            !hasConflict && storage is PreferencesState.Ready && storage.canWrite

    val canReload: Boolean
        get() = isEditing && !isSaving && storage is PreferencesState.Ready

    /** 仅供设置页预览；主屏幕采用 effective.screenShape，真实窗口避让仍由布局所有者处理。 */
    val previewScreenShape: ScreenShape?
        get() {
            val ready = storage as? PreferencesState.Ready ?: return null
            return if (isEditing && previewEnabled && !hasConflict && ready.canWrite) draft?.screenShape else null
        }
}

/** 编辑在主线程串行；DataStore 的 IO 和 revision 冲突核验仍由共享仓库负责。 */
class PreferencesViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val repository = UserPreferencesRepositoryFactory.get(application)
    val preferencesState = repository.state
    private val _uiState = MutableStateFlow(restoredEditor())
    val uiState = _uiState.asStateFlow()
    private var mutationJob: Job? = null
    private var editorGeneration = Any()

    init {
        viewModelScope.launch {
            repository.state.collect { storage ->
                updateEditor { current -> initializeDraft(current.copy(storage = storage)) }
            }
        }
    }

    /** 导航进入时调用；已有草稿不因再进入或仓库 emission 被覆盖。 */
    @MainThread
    fun enterEditor() {
        if (_uiState.value.isEditing) return
        editorGeneration = Any()
        updateEditor { current ->
            initializeDraft(
                current.copy(
                    storage = repository.state.value,
                    isEditing = true,
                    isSaving = mutationJob?.isActive == true,
                    previewEnabled = true,
                ),
            )
        }
    }

    /**
     * 导航返回或取消编辑时调用，解除未保存预览。
     * 已经明确发起的保存继续由仓库确认；离页不能承诺原子提交回滚。
     */
    @MainThread
    fun clearDraft() {
        editorGeneration = Any()
        updateEditor { current ->
            PreferencesEditorState(
                storage = repository.state.value,
                isSaving = current.isSaving,
                previewEnabled = false,
            )
        }
    }

    @MainThread
    fun setRestorePosition(enabled: Boolean) {
        editDraft { draft -> draft.copy(restorePosition = enabled) }
    }

    @MainThread
    fun setScreenShape(shape: ScreenShape) {
        editDraft { draft -> draft.copy(screenShape = shape) }
    }

    /** 明确丢弃当前草稿，按最新确认值建立新基线；冲突不自动更新 expectedRevision。 */
    @MainThread
    fun reloadCurrentValues() {
        val current = _uiState.value
        val storage = repository.state.value as? PreferencesState.Ready ?: return
        if (!current.isEditing || current.isSaving) return
        updateEditor {
            current.copy(
                storage = storage,
                baseRevision = storage.preferences.revision,
                draft = storage.preferences.asDraft(),
                confirmReset = false,
                failure = null,
                failedOperation = null,
                conflict = null,
                feedback = null,
                previewEnabled = false,
            )
        }
    }

    /** 用户点击保存即确认草稿；失败后未修改草稿时，此动作重试相同值和基线。 */
    @MainThread
    fun saveDraft() {
        val current = _uiState.value
        if (!current.canEdit) return
        val revision = current.baseRevision ?: return
        val draft = current.draft ?: return
        beginMutation(PreferencesEditOperation.SAVE, revision, draft)
    }

    @MainThread
    fun requestReset() {
        if (!_uiState.value.canEdit) return
        updateEditor { current ->
            current.copy(confirmReset = true, failure = null, failedOperation = null, feedback = null)
        }
    }

    @MainThread
    fun cancelReset() {
        if (!_uiState.value.confirmReset) return
        updateEditor { current ->
            current.copy(confirmReset = false, feedback = PreferencesEditFeedback.RESET_CANCELLED)
        }
    }

    @MainThread
    fun confirmReset() {
        val current = _uiState.value
        if (!current.canConfirmReset) return
        val revision = current.baseRevision ?: return
        val draft = current.draft ?: return
        beginMutation(PreferencesEditOperation.RESET, revision, draft)
    }

    @MainThread
    fun retryRead() {
        repository.retryRead()
    }

    private fun editDraft(transform: (UserPreferencesDraft) -> UserPreferencesDraft) {
        val current = _uiState.value
        if (!current.canEdit) return
        val draft = current.draft ?: return
        updateEditor {
            current.copy(
                draft = transform(draft),
                failure = null,
                failedOperation = null,
                feedback = null,
                previewEnabled = true,
            )
        }
    }

    private fun beginMutation(operation: PreferencesEditOperation, revision: Long, draft: UserPreferencesDraft) {
        if (mutationJob?.isActive == true) return
        val latest = repository.state.value
        if (latest is PreferencesState.Ready && latest.preferences.revision != revision) {
            updateEditor { current ->
                current.copy(
                    storage = latest,
                    conflict = latest.preferences,
                    confirmReset = false,
                    previewEnabled = false,
                )
            }
            return
        }
        updateEditor { current ->
            current.copy(
                storage = latest,
                isSaving = true,
                confirmReset = false,
                failure = null,
                failedOperation = null,
                feedback = null,
                previewEnabled = false,
            )
        }
        val generation = editorGeneration
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = when (operation) {
                    PreferencesEditOperation.SAVE -> repository.save(revision, draft)
                    PreferencesEditOperation.RESET -> repository.reset(revision, ResetConfirmation.CONFIRMED)
                }
                if (generation === editorGeneration && _uiState.value.isEditing) applyResult(result, operation)
            } catch (error: CancellationException) {
                throw error
            } finally {
                mutationJob = null
                updateEditor { current -> current.copy(isSaving = false) }
            }
        }
        mutationJob = job
        job.start()
    }

    private fun applyResult(result: PreferencesWriteResult, operation: PreferencesEditOperation) {
        updateEditor { current ->
            val updated = current.copy(storage = repository.state.value, isSaving = false, confirmReset = false)
            when (result) {
                is PreferencesWriteResult.Saved -> committed(
                    updated,
                    result.preferences,
                    if (operation == PreferencesEditOperation.RESET) {
                        PreferencesEditFeedback.DEFAULTS_RESTORED
                    } else {
                        PreferencesEditFeedback.SAVED
                    },
                )
                is PreferencesWriteResult.Unchanged -> committed(
                    updated,
                    result.preferences,
                    if (operation == PreferencesEditOperation.RESET) {
                        PreferencesEditFeedback.DEFAULTS_ALREADY_SET
                    } else {
                        PreferencesEditFeedback.UNCHANGED
                    },
                )
                is PreferencesWriteResult.Conflict -> updated.copy(
                    conflict = result.current,
                    feedback = null,
                    previewEnabled = false,
                )
                is PreferencesWriteResult.Failed -> updated.copy(
                    failure = result.failure,
                    failedOperation = operation,
                    feedback = null,
                    previewEnabled = false,
                )
                is PreferencesWriteResult.Cancelled -> updated.copy(
                    feedback = PreferencesEditFeedback.RESET_CANCELLED,
                    previewEnabled = false,
                )
            }
        }
    }

    private fun committed(
        current: PreferencesEditorState,
        preferences: UserPreferences,
        feedback: PreferencesEditFeedback,
    ) = current.copy(
        baseRevision = preferences.revision,
        draft = preferences.asDraft(),
        failure = null,
        failedOperation = null,
        conflict = null,
        feedback = feedback,
        previewEnabled = false,
    )

    private fun initializeDraft(current: PreferencesEditorState): PreferencesEditorState {
        val storage = current.storage as? PreferencesState.Ready ?: return current
        if (!current.isEditing || current.draft != null) return current
        return current.copy(baseRevision = storage.preferences.revision, draft = storage.preferences.asDraft())
    }

    private fun restoredEditor(): PreferencesEditorState {
        val active = savedStateHandle.get<Any>(KEY_ACTIVE) as? Boolean == true
        val revision = (savedStateHandle.get<Any>(KEY_REVISION) as? Long)?.takeIf { value -> value >= 0L }
        val restore = savedStateHandle.get<Any>(KEY_RESTORE_POSITION) as? Boolean
        val shapeName = savedStateHandle.get<Any>(KEY_SCREEN_SHAPE) as? String
        val shape = ScreenShape.entries.firstOrNull { value -> value.name == shapeName }
        val draft = if (active && revision != null && restore != null && shape != null) {
            UserPreferencesDraft(restore, shape)
        } else {
            null
        }
        // 保存/确认/错误提示不进 SavedStateHandle，重建只恢复编辑数据，不重放操作。
        return PreferencesEditorState(
            storage = repository.state.value,
            isEditing = active,
            baseRevision = revision.takeIf { draft != null },
            draft = draft,
            previewEnabled = savedStateHandle.get<Any>(KEY_PREVIEW) as? Boolean ?: true,
        )
    }

    private fun updateEditor(transform: (PreferencesEditorState) -> PreferencesEditorState) {
        _uiState.update(transform)
        val current = _uiState.value
        savedStateHandle[KEY_ACTIVE] = current.isEditing
        val draft = current.draft
        if (current.isEditing && draft != null && current.baseRevision != null) {
            savedStateHandle[KEY_REVISION] = current.baseRevision
            savedStateHandle[KEY_RESTORE_POSITION] = draft.restorePosition
            savedStateHandle[KEY_SCREEN_SHAPE] = draft.screenShape.name
            savedStateHandle[KEY_PREVIEW] = current.previewEnabled
        } else {
            savedStateHandle.remove<Long>(KEY_REVISION)
            savedStateHandle.remove<Boolean>(KEY_RESTORE_POSITION)
            savedStateHandle.remove<String>(KEY_SCREEN_SHAPE)
            savedStateHandle.remove<Boolean>(KEY_PREVIEW)
        }
    }

    private companion object {
        const val KEY_ACTIVE = "preferences.editor.active"
        const val KEY_REVISION = "preferences.editor.revision"
        const val KEY_RESTORE_POSITION = "preferences.editor.restore_position"
        const val KEY_SCREEN_SHAPE = "preferences.editor.screen_shape"
        const val KEY_PREVIEW = "preferences.editor.preview"
    }
}
