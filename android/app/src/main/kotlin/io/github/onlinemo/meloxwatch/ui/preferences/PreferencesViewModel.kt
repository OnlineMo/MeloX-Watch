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
import io.github.onlinemo.meloxwatch.model.ArtworkReadPath
import io.github.onlinemo.meloxwatch.model.HelpPreferenceLimits
import io.github.onlinemo.meloxwatch.model.HelpPreferences
import io.github.onlinemo.meloxwatch.model.PrivacyNoticeChoice
import io.github.onlinemo.meloxwatch.model.LibraryLayout
import io.github.onlinemo.meloxwatch.model.LibraryListContext
import io.github.onlinemo.meloxwatch.model.LibrarySort
import io.github.onlinemo.meloxwatch.model.LibrarySortField
import io.github.onlinemo.meloxwatch.model.PlaybackBackendKind
import io.github.onlinemo.meloxwatch.model.PlaybackPreferenceLimits
import io.github.onlinemo.meloxwatch.model.PlaybackControlsPreferences
import io.github.onlinemo.meloxwatch.model.ProgressGestureMode
import io.github.onlinemo.meloxwatch.model.RotaryAction
import io.github.onlinemo.meloxwatch.model.RotaryOrientation
import io.github.onlinemo.meloxwatch.model.ScreenShape
import io.github.onlinemo.meloxwatch.model.SongTapPolicy
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

    @MainThread
    fun setLibraryLayout(layout: LibraryLayout) {
        editDraft { draft -> draft.copy(libraryLayout = layout) }
    }

    @MainThread
    fun setArtworkReadPath(path: ArtworkReadPath) {
        editDraft { draft -> draft.copy(artworkReadPath = path) }
    }

    @MainThread
    fun setSongTapPolicy(policy: SongTapPolicy) {
        editDraft { it.copy(songTapPolicy = policy) }
    }

    @MainThread
    fun setMediaButtonsEnabled(enabled: Boolean) {
        editDraft { it.copy(mediaButtonsEnabled = enabled) }
    }

    @MainThread
    fun setAudioFocusManagement(enabled: Boolean) {
        editDraft { it.copy(audioFocusManagement = enabled) }
    }

    @MainThread
    fun setPlaybackBackend(backend: PlaybackBackendKind) {
        editDraft { it.copy(playbackBackend = backend) }
    }

    @MainThread
    fun setPlaybackSpeedTenths(speed: Int) {
        if (speed !in PlaybackPreferenceLimits.MIN_SPEED_TENTHS..PlaybackPreferenceLimits.MAX_SPEED_TENTHS) return
        editDraft { it.copy(playbackSpeedTenths = speed) }
    }

    @MainThread
    fun setGaplessPlayback(enabled: Boolean) {
        editDraft { it.copy(gaplessPlayback = enabled) }
    }

    @MainThread
    fun setRotaryEnabled(enabled: Boolean) {
        editDraft { it.copy(controls = it.controls.copy(rotaryEnabled = enabled)) }
    }

    @MainThread
    fun setRotaryAction(action: RotaryAction) {
        editDraft { it.copy(controls = it.controls.copy(rotaryAction = action)) }
    }

    @MainThread
    fun setRotaryOrientation(orientation: RotaryOrientation) {
        editDraft { it.copy(controls = it.controls.copy(rotaryOrientation = orientation)) }
    }

    @MainThread
    fun setRotaryHaptics(enabled: Boolean) {
        editDraft { it.copy(controls = it.controls.copy(rotaryHaptics = enabled)) }
    }

    @MainThread
    fun setMainProgressVisible(visible: Boolean) {
        editDraft { it.copy(controls = it.controls.copy(showMainProgress = visible)) }
    }

    @MainThread
    fun setProgressGesture(mode: ProgressGestureMode) {
        editDraft { it.copy(controls = it.controls.copy(progressGesture = mode)) }
    }

    @MainThread
    fun setSleepTimerSummary(enabled: Boolean) {
        editDraft { it.copy(controls = it.controls.copy(sleepTimerSummary = enabled)) }
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
        val layout = LibraryLayout.entries.firstOrNull { it.name == (savedStateHandle.get<Any>(KEY_LAYOUT) as? String) }
        val artworkPath = ArtworkReadPath.entries.firstOrNull { it.name == (savedStateHandle.get<Any>(KEY_ARTWORK) as? String) }
        val oldDraft = !savedStateHandle.contains(KEY_EDITOR_SCHEMA)
        val editorSchema = savedStateHandle.get<Any>(KEY_EDITOR_SCHEMA) as? Int
        val legacyControls = oldDraft || editorSchema == 3
        val legacyHelp = legacyControls || editorSchema == 4
        val help = restoredHelp(legacyHelp)
        val tapPolicy = if (oldDraft) SongTapPolicy.ASK else SongTapPolicy.entries.firstOrNull {
            it.name == (savedStateHandle.get<Any>(KEY_TAP_POLICY) as? String)
        }
        val backend = if (oldDraft) PlaybackBackendKind.MEDIA3 else PlaybackBackendKind.entries.firstOrNull {
            it.name == (savedStateHandle.get<Any>(KEY_BACKEND) as? String)
        }
        val speed = if (oldDraft) PlaybackPreferenceLimits.DEFAULT_SPEED_TENTHS else {
            (savedStateHandle.get<Any>(KEY_SPEED) as? Int)?.takeIf {
                it in PlaybackPreferenceLimits.MIN_SPEED_TENTHS..PlaybackPreferenceLimits.MAX_SPEED_TENTHS
            }
        }
        val mediaButtons = if (oldDraft) true else savedStateHandle.get<Any>(KEY_MEDIA_BUTTONS) as? Boolean
        val audioFocus = if (oldDraft) true else savedStateHandle.get<Any>(KEY_AUDIO_FOCUS) as? Boolean
        val gapless = if (oldDraft) true else savedStateHandle.get<Any>(KEY_GAPLESS) as? Boolean
        val rotaryEnabled = if (legacyControls) false else savedStateHandle.get<Any>(KEY_ROTARY_ENABLED) as? Boolean
        val rotaryAction = if (legacyControls) RotaryAction.MEDIA_VOLUME else RotaryAction.entries.firstOrNull {
            it.name == (savedStateHandle.get<Any>(KEY_ROTARY_ACTION) as? String)
        }
        val rotaryOrientation = if (legacyControls) RotaryOrientation.NORMAL else RotaryOrientation.entries.firstOrNull {
            it.name == (savedStateHandle.get<Any>(KEY_ROTARY_ORIENTATION) as? String)
        }
        val rotaryHaptics = if (legacyControls) false else savedStateHandle.get<Any>(KEY_ROTARY_HAPTICS) as? Boolean
        val mainProgress = if (legacyControls) true else savedStateHandle.get<Any>(KEY_MAIN_PROGRESS) as? Boolean
        val progressGesture = if (legacyControls) ProgressGestureMode.DRAG_TO_COMMIT else ProgressGestureMode.entries.firstOrNull {
            it.name == (savedStateHandle.get<Any>(KEY_PROGRESS_GESTURE) as? String)
        }
        val sleepSummary = if (legacyControls) false else savedStateHandle.get<Any>(KEY_SLEEP_SUMMARY) as? Boolean
        val sorts = LibraryListContext.entries.associateWith { context ->
            val field = LibrarySortField.entries.firstOrNull {
                it.name == (savedStateHandle.get<Any>(sortKey(context)) as? String)
            }
            val direction = savedStateHandle.get<Any>(sortDirectionKey(context)) as? Boolean
            if (field == null || direction == null) null else LibrarySort(field, direction)
        }
        val draft = if (active && revision != null && restore != null && shape != null &&
            layout != null && artworkPath != null && sorts.values.all { it != null } &&
            tapPolicy != null && backend != null && speed != null &&
            mediaButtons != null && audioFocus != null && gapless != null &&
            rotaryEnabled != null && rotaryAction != null && rotaryOrientation != null && rotaryHaptics != null &&
            mainProgress != null && progressGesture != null && sleepSummary != null &&
            help != null && (legacyHelp || editorSchema == UserPreferences.CURRENT_SCHEMA_VERSION)
        ) {
            UserPreferencesDraft(
                restorePosition = restore,
                screenShape = shape,
                libraryLayout = layout,
                artworkReadPath = artworkPath,
                librarySorts = sorts.mapValues { checkNotNull(it.value) },
                songTapPolicy = tapPolicy,
                mediaButtonsEnabled = mediaButtons,
                audioFocusManagement = audioFocus,
                playbackBackend = backend,
                playbackSpeedTenths = speed,
                gaplessPlayback = gapless,
                controls = PlaybackControlsPreferences(rotaryEnabled, rotaryAction, rotaryOrientation, rotaryHaptics,
                    mainProgress, progressGesture, sleepSummary),
                help = help,
            )
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

    private fun restoredHelp(legacy: Boolean): HelpPreferences? {
        if (legacy) return HelpPreferenceLimits.Legacy
        fun version(key: String): Int? = (savedStateHandle.get<Any>(key) as? Int)
            ?.takeIf { it in 0..HelpPreferenceLimits.MAX_DOCUMENT_VERSION }
        val completed = version(KEY_GUIDE_COMPLETED) ?: return null
        val dismissed = version(KEY_GUIDE_DISMISSED) ?: return null
        val step = (savedStateHandle.get<Any>(KEY_GUIDE_STEP) as? Int)
            ?.takeIf { it in 0 until HelpPreferenceLimits.GUIDE_STEP_COUNT } ?: return null
        val showTips = savedStateHandle.get<Any>(KEY_HELP_TIPS) as? Boolean ?: return null
        val noticeVersion = version(KEY_NOTICE_VERSION) ?: return null
        val choice = PrivacyNoticeChoice.entries.firstOrNull {
            it.name == (savedStateHandle.get<Any>(KEY_NOTICE_CHOICE) as? String)
        } ?: return null
        if ((noticeVersion == 0) != (choice == PrivacyNoticeChoice.UNANSWERED)) return null
        return HelpPreferences(
            onboardingCompletedVersion = completed,
            onboardingDismissedVersion = dismissed,
            onboardingStep = step,
            showTips = showTips,
            privacyNoticeVersion = noticeVersion,
            privacyNoticeChoice = choice,
        )
    }

    private fun updateEditor(transform: (PreferencesEditorState) -> PreferencesEditorState) {
        _uiState.update(transform)
        val current = _uiState.value
        savedStateHandle[KEY_ACTIVE] = current.isEditing
        val draft = current.draft
        if (current.isEditing && draft != null && current.baseRevision != null) {
            savedStateHandle[KEY_EDITOR_SCHEMA] = UserPreferences.CURRENT_SCHEMA_VERSION
            savedStateHandle[KEY_REVISION] = current.baseRevision
            savedStateHandle[KEY_RESTORE_POSITION] = draft.restorePosition
            savedStateHandle[KEY_SCREEN_SHAPE] = draft.screenShape.name
            savedStateHandle[KEY_LAYOUT] = draft.libraryLayout.name
            savedStateHandle[KEY_ARTWORK] = draft.artworkReadPath.name
            savedStateHandle[KEY_TAP_POLICY] = draft.songTapPolicy.name
            savedStateHandle[KEY_MEDIA_BUTTONS] = draft.mediaButtonsEnabled
            savedStateHandle[KEY_AUDIO_FOCUS] = draft.audioFocusManagement
            savedStateHandle[KEY_BACKEND] = draft.playbackBackend.name
            savedStateHandle[KEY_SPEED] = draft.playbackSpeedTenths
            savedStateHandle[KEY_GAPLESS] = draft.gaplessPlayback
            savedStateHandle[KEY_ROTARY_ENABLED] = draft.controls.rotaryEnabled
            savedStateHandle[KEY_ROTARY_ACTION] = draft.controls.rotaryAction.name
            savedStateHandle[KEY_ROTARY_ORIENTATION] = draft.controls.rotaryOrientation.name
            savedStateHandle[KEY_ROTARY_HAPTICS] = draft.controls.rotaryHaptics
            savedStateHandle[KEY_MAIN_PROGRESS] = draft.controls.showMainProgress
            savedStateHandle[KEY_PROGRESS_GESTURE] = draft.controls.progressGesture.name
            savedStateHandle[KEY_SLEEP_SUMMARY] = draft.controls.sleepTimerSummary
            savedStateHandle[KEY_GUIDE_COMPLETED] = draft.help.onboardingCompletedVersion
            savedStateHandle[KEY_GUIDE_DISMISSED] = draft.help.onboardingDismissedVersion
            savedStateHandle[KEY_GUIDE_STEP] = draft.help.onboardingStep
            savedStateHandle[KEY_HELP_TIPS] = draft.help.showTips
            savedStateHandle[KEY_NOTICE_VERSION] = draft.help.privacyNoticeVersion
            savedStateHandle[KEY_NOTICE_CHOICE] = draft.help.privacyNoticeChoice.name
            LibraryListContext.entries.forEach { context ->
                savedStateHandle[sortKey(context)] = draft.librarySorts.getValue(context).field.name
                savedStateHandle[sortDirectionKey(context)] = draft.librarySorts.getValue(context).descending
            }
            savedStateHandle[KEY_PREVIEW] = current.previewEnabled
        } else {
            savedStateHandle.remove<Int>(KEY_EDITOR_SCHEMA)
            savedStateHandle.remove<Long>(KEY_REVISION)
            savedStateHandle.remove<Boolean>(KEY_RESTORE_POSITION)
            savedStateHandle.remove<String>(KEY_SCREEN_SHAPE)
            savedStateHandle.remove<String>(KEY_LAYOUT)
            savedStateHandle.remove<String>(KEY_ARTWORK)
            savedStateHandle.remove<String>(KEY_TAP_POLICY)
            savedStateHandle.remove<Boolean>(KEY_MEDIA_BUTTONS)
            savedStateHandle.remove<Boolean>(KEY_AUDIO_FOCUS)
            savedStateHandle.remove<String>(KEY_BACKEND)
            savedStateHandle.remove<Int>(KEY_SPEED)
            savedStateHandle.remove<Boolean>(KEY_GAPLESS)
            savedStateHandle.remove<Boolean>(KEY_ROTARY_ENABLED)
            savedStateHandle.remove<String>(KEY_ROTARY_ACTION)
            savedStateHandle.remove<String>(KEY_ROTARY_ORIENTATION)
            savedStateHandle.remove<Boolean>(KEY_ROTARY_HAPTICS)
            savedStateHandle.remove<Boolean>(KEY_MAIN_PROGRESS)
            savedStateHandle.remove<String>(KEY_PROGRESS_GESTURE)
            savedStateHandle.remove<Boolean>(KEY_SLEEP_SUMMARY)
            savedStateHandle.remove<Int>(KEY_GUIDE_COMPLETED)
            savedStateHandle.remove<Int>(KEY_GUIDE_DISMISSED)
            savedStateHandle.remove<Int>(KEY_GUIDE_STEP)
            savedStateHandle.remove<Boolean>(KEY_HELP_TIPS)
            savedStateHandle.remove<Int>(KEY_NOTICE_VERSION)
            savedStateHandle.remove<String>(KEY_NOTICE_CHOICE)
            LibraryListContext.entries.forEach { context ->
                savedStateHandle.remove<String>(sortKey(context))
                savedStateHandle.remove<Boolean>(sortDirectionKey(context))
            }
            savedStateHandle.remove<Boolean>(KEY_PREVIEW)
        }
    }

    private companion object {
        const val KEY_ACTIVE = "preferences.editor.active"
        const val KEY_EDITOR_SCHEMA = "preferences.editor.schema"
        const val KEY_REVISION = "preferences.editor.revision"
        const val KEY_RESTORE_POSITION = "preferences.editor.restore_position"
        const val KEY_SCREEN_SHAPE = "preferences.editor.screen_shape"
        const val KEY_LAYOUT = "preferences.editor.library_layout"
        const val KEY_ARTWORK = "preferences.editor.artwork_path"
        const val KEY_TAP_POLICY = "preferences.editor.song_tap_policy"
        const val KEY_MEDIA_BUTTONS = "preferences.editor.media_buttons"
        const val KEY_AUDIO_FOCUS = "preferences.editor.audio_focus"
        const val KEY_BACKEND = "preferences.editor.backend"
        const val KEY_SPEED = "preferences.editor.speed_tenths"
        const val KEY_GAPLESS = "preferences.editor.gapless"
        const val KEY_ROTARY_ENABLED = "preferences.editor.rotary_enabled"
        const val KEY_ROTARY_ACTION = "preferences.editor.rotary_action"
        const val KEY_ROTARY_ORIENTATION = "preferences.editor.rotary_orientation"
        const val KEY_ROTARY_HAPTICS = "preferences.editor.rotary_haptics"
        const val KEY_MAIN_PROGRESS = "preferences.editor.main_progress"
        const val KEY_PROGRESS_GESTURE = "preferences.editor.progress_gesture"
        const val KEY_SLEEP_SUMMARY = "preferences.editor.sleep_summary"
        const val KEY_GUIDE_COMPLETED = "preferences.editor.guide_completed"
        const val KEY_GUIDE_DISMISSED = "preferences.editor.guide_dismissed"
        const val KEY_GUIDE_STEP = "preferences.editor.guide_step"
        const val KEY_HELP_TIPS = "preferences.editor.help_tips"
        const val KEY_NOTICE_VERSION = "preferences.editor.notice_version"
        const val KEY_NOTICE_CHOICE = "preferences.editor.notice_choice"
        const val KEY_PREVIEW = "preferences.editor.preview"
        fun sortKey(context: LibraryListContext) = "preferences.editor.sort.${context.name}"
        fun sortDirectionKey(context: LibraryListContext) = "preferences.editor.descending.${context.name}"
    }
}
