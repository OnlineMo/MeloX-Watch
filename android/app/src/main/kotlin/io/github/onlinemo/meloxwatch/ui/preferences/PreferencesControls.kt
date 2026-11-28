package io.github.onlinemo.meloxwatch.ui.preferences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesFailure
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesField
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesRecoveryReason
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesState
import io.github.onlinemo.meloxwatch.model.ArtworkReadPath
import io.github.onlinemo.meloxwatch.model.LibraryLayout
import io.github.onlinemo.meloxwatch.model.PlaybackBackendKind
import io.github.onlinemo.meloxwatch.model.PlaybackPreferenceLimits
import io.github.onlinemo.meloxwatch.model.ProgressGestureMode
import io.github.onlinemo.meloxwatch.model.RotaryAction
import io.github.onlinemo.meloxwatch.model.RotaryOrientation
import io.github.onlinemo.meloxwatch.model.ScreenShape
import io.github.onlinemo.meloxwatch.model.SongTapPolicy
import io.github.onlinemo.meloxwatch.model.UserPreferencesDraft
import io.github.onlinemo.meloxwatch.ui.watch.WatchActionRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchChoiceRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchSwitchRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchText
import io.github.onlinemo.meloxwatch.ui.storage.StorageControls

private enum class PreferencesSection { OVERVIEW, GENERAL, PLAYBACK, CONTROLS, STORAGE }

/** 放入宿主的可滚动 item；宿主负责标题、真实窗口避让与导航 enterEditor/clearDraft。 */
@Composable
fun PreferencesControls(viewModel: PreferencesViewModel, onCancel: () -> Unit, onBluetoothLyrics: () -> Unit = {}) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var sectionName by rememberSaveable { mutableStateOf(PreferencesSection.OVERVIEW.name) }
    var showSavedValues by rememberSaveable { mutableStateOf(false) }
    val section = PreferencesSection.entries.firstOrNull { it.name == sectionName } ?: PreferencesSection.OVERVIEW
    val requiresSavedValues = state.failure != null || state.hasConflict || state.storage !is PreferencesState.Ready ||
        (state.storage as? PreferencesState.Ready)?.recovery != null
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StorageStatus(state, onRetryRead = viewModel::retryRead)
        EditorStatus(state, onReload = viewModel::reloadCurrentValues)
        if (section != PreferencesSection.OVERVIEW) {
            PreferenceButton(stringResource(R.string.prefs_back_to_sections)) { sectionName = PreferencesSection.OVERVIEW.name }
        }
        if (section == PreferencesSection.OVERVIEW) {
            PreferenceButton(stringResource(R.string.storage_title)) { sectionName = PreferencesSection.STORAGE.name }
        }
        if (section == PreferencesSection.STORAGE) StorageControls()
        if (!requiresSavedValues) {
            PreferenceButton(stringResource(if (showSavedValues) R.string.prefs_hide_saved_values else R.string.prefs_show_saved_values)) {
                showSavedValues = !showSavedValues
            }
        }
        if (requiresSavedValues || showSavedValues) CurrentPreferences(state)
        val draft = state.draft
        if (state.isEditing && draft != null && section != PreferencesSection.STORAGE) {
            Heading(stringResource(R.string.prefs_edit_values))
            when (section) {
                PreferencesSection.OVERVIEW -> {
                    PreferenceButton(stringResource(R.string.prefs_section_general)) { sectionName = PreferencesSection.GENERAL.name }
                    PreferenceButton(stringResource(R.string.prefs_section_playback)) { sectionName = PreferencesSection.PLAYBACK.name }
                    PreferenceButton(stringResource(R.string.prefs_section_controls)) { sectionName = PreferencesSection.CONTROLS.name }
                }
                PreferencesSection.GENERAL -> GeneralSettingsEditor(draft, state.canEdit, viewModel)
                PreferencesSection.PLAYBACK -> {
                    PlaybackSettingsEditor(draft, state.canEdit, viewModel)
                    PreferenceButton(stringResource(R.string.bluetooth_lyrics_title), onClick = onBluetoothLyrics)
                }
                PreferencesSection.CONTROLS -> ControlsSettingsEditor(draft, state.canEdit, viewModel)
                PreferencesSection.STORAGE -> Unit
            }
            Explanation(stringResource(R.string.prefs_save_scope))
            if (state.confirmReset) {
                WatchText(stringResource(R.string.prefs_reset_question), primary = true)
                PreferenceButton(
                    stringResource(R.string.prefs_reset_confirm),
                    enabled = state.canConfirmReset,
                    onClick = viewModel::confirmReset,
                )
                PreferenceButton(stringResource(R.string.prefs_reset_cancel), onClick = viewModel::cancelReset)
            } else {
                val retrySave = state.failure == PreferencesFailure.IO &&
                    state.failedOperation == PreferencesEditOperation.SAVE
                PreferenceButton(
                    stringResource(if (retrySave) R.string.prefs_retry_save else R.string.prefs_save),
                    enabled = state.canEdit,
                    primary = true,
                    onClick = viewModel::saveDraft,
                )
                val retryReset = state.failure == PreferencesFailure.IO &&
                    state.failedOperation == PreferencesEditOperation.RESET
                PreferenceButton(
                    stringResource(if (retryReset) R.string.prefs_retry_reset else R.string.prefs_reset),
                    enabled = state.canEdit,
                    onClick = viewModel::requestReset,
                )
            }
        } else if (section != PreferencesSection.STORAGE) {
            Explanation(stringResource(R.string.prefs_wait_for_settings))
        }
        PreferenceButton(stringResource(R.string.prefs_cancel_edit), enabled = !state.isSaving) {
            viewModel.clearDraft()
            onCancel()
        }
    }
}

@Composable
private fun GeneralSettingsEditor(draft: UserPreferencesDraft, enabled: Boolean, viewModel: PreferencesViewModel) {
    PreferenceToggle(stringResource(R.string.prefs_restore_position), draft.restorePosition, enabled, viewModel::setRestorePosition)
    Explanation(stringResource(R.string.prefs_restore_position_scope))
    Heading(stringResource(R.string.prefs_screen_shape))
    Explanation(stringResource(R.string.prefs_screen_shape_scope))
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ScreenShape.entries.forEach { shape ->
            PreferenceChoice(shapeLabel(shape), draft.screenShape == shape, enabled) { viewModel.setScreenShape(shape) }
        }
    }
    Heading(stringResource(R.string.library_layout))
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LibraryLayout.entries.forEach { layout ->
            PreferenceChoice(stringResource(if (layout == LibraryLayout.LIST) R.string.library_list else R.string.library_grid),
                draft.libraryLayout == layout, enabled) { viewModel.setLibraryLayout(layout) }
        }
    }
    Explanation(stringResource(R.string.library_grid_scope))
    Heading(stringResource(R.string.library_artwork_path))
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ArtworkReadPath.entries.forEach { path ->
            PreferenceChoice(stringResource(if (path == ArtworkReadPath.SYSTEM_THUMBNAIL) R.string.library_artwork_system
                else R.string.library_artwork_local), draft.artworkReadPath == path, enabled) { viewModel.setArtworkReadPath(path) }
        }
    }
    Explanation(stringResource(R.string.library_artwork_scope))
}

@Composable
private fun CurrentPreferences(state: PreferencesEditorState) {
    val heading = when (val storage = state.storage) {
        is PreferencesState.Ready -> R.string.prefs_current_values
        is PreferencesState.Unavailable -> if (storage.lastKnown == null) {
            R.string.prefs_temporary_values
        } else {
            R.string.prefs_last_known_values
        }
        PreferencesState.Loading -> R.string.prefs_temporary_values
    }
    Heading(stringResource(heading))
    Explanation(
        stringResource(
            R.string.prefs_restore_position_value,
            stringResource(if (state.effective.restorePosition) R.string.prefs_on else R.string.prefs_off),
        ),
    )
    Explanation(stringResource(R.string.prefs_screen_shape_value, shapeLabel(state.effective.screenShape)))
    Explanation(stringResource(R.string.library_layout_value, stringResource(
        if (state.effective.libraryLayout == LibraryLayout.LIST) R.string.library_list else R.string.library_grid,
    )))
    Explanation(stringResource(R.string.library_artwork_value, stringResource(
        if (state.effective.artworkReadPath == ArtworkReadPath.SYSTEM_THUMBNAIL) {
            R.string.library_artwork_system
        } else R.string.library_artwork_local,
    )))
    Explanation(stringResource(R.string.prefs_song_tap_value, tapPolicyLabel(state.effective.songTapPolicy)))
    Explanation(stringResource(R.string.prefs_playback_backend_value, backendLabel(state.effective.playbackBackend)))
    Explanation(stringResource(R.string.prefs_playback_speed_value, state.effective.playbackSpeedTenths / 10f))
    Explanation(stringResource(R.string.prefs_media_buttons_value, onOffLabel(state.effective.mediaButtonsEnabled)))
    Explanation(stringResource(R.string.prefs_audio_focus_value, onOffLabel(state.effective.audioFocusManagement)))
    Explanation(stringResource(R.string.prefs_gapless_value, onOffLabel(state.effective.gaplessPlayback)))
    val controls = state.effective.controls
    Explanation(stringResource(R.string.prefs_rotary_value, onOffLabel(controls.rotaryEnabled),
        rotaryActionLabel(controls.rotaryAction), rotaryOrientationLabel(controls.rotaryOrientation)))
    Explanation(stringResource(R.string.prefs_rotary_haptics_value, onOffLabel(controls.rotaryHaptics)))
    Explanation(stringResource(R.string.prefs_main_progress_value, onOffLabel(controls.showMainProgress),
        progressGestureLabel(controls.progressGesture)))
    Explanation(stringResource(R.string.prefs_sleep_summary_value, onOffLabel(controls.sleepTimerSummary)))
}

@Composable
private fun PlaybackSettingsEditor(draft: UserPreferencesDraft, enabled: Boolean, viewModel: PreferencesViewModel) {
    Heading(stringResource(R.string.prefs_song_tap))
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        SongTapPolicy.entries.forEach { policy ->
            PreferenceChoice(tapPolicyLabel(policy), draft.songTapPolicy == policy, enabled) {
                viewModel.setSongTapPolicy(policy)
            }
        }
    }
    Explanation(stringResource(R.string.prefs_song_tap_scope))
    Heading(stringResource(R.string.prefs_playback_backend))
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        PlaybackBackendKind.entries.forEach { backend ->
            PreferenceChoice(backendLabel(backend), draft.playbackBackend == backend, enabled) {
                viewModel.setPlaybackBackend(backend)
            }
        }
    }
    Explanation(stringResource(R.string.prefs_backend_scope))
    Heading(stringResource(R.string.prefs_playback_speed_value, draft.playbackSpeedTenths / 10f))
    PreferenceButton(stringResource(R.string.prefs_speed_slower),
        enabled = enabled && draft.playbackSpeedTenths > PlaybackPreferenceLimits.MIN_SPEED_TENTHS) {
        viewModel.setPlaybackSpeedTenths(draft.playbackSpeedTenths - 1)
    }
    PreferenceButton(stringResource(R.string.prefs_speed_faster),
        enabled = enabled && draft.playbackSpeedTenths < PlaybackPreferenceLimits.MAX_SPEED_TENTHS) {
        viewModel.setPlaybackSpeedTenths(draft.playbackSpeedTenths + 1)
    }
    PreferenceButton(stringResource(R.string.prefs_speed_normal),
        enabled = enabled && draft.playbackSpeedTenths != PlaybackPreferenceLimits.DEFAULT_SPEED_TENTHS) {
        viewModel.setPlaybackSpeedTenths(PlaybackPreferenceLimits.DEFAULT_SPEED_TENTHS)
    }
    Explanation(stringResource(R.string.prefs_speed_scope))
    PreferenceToggle(stringResource(R.string.prefs_gapless), draft.gaplessPlayback, enabled, viewModel::setGaplessPlayback)
    Explanation(stringResource(R.string.prefs_gapless_scope))
    PreferenceToggle(stringResource(R.string.prefs_media_buttons), draft.mediaButtonsEnabled, enabled, viewModel::setMediaButtonsEnabled)
    Explanation(stringResource(R.string.prefs_media_buttons_scope))
    PreferenceToggle(stringResource(R.string.prefs_audio_focus), draft.audioFocusManagement, enabled, viewModel::setAudioFocusManagement)
    Explanation(stringResource(R.string.prefs_audio_focus_scope))
}

@Composable
private fun ControlsSettingsEditor(draft: UserPreferencesDraft, enabled: Boolean, viewModel: PreferencesViewModel) {
    val controls = draft.controls
    Heading(stringResource(R.string.prefs_main_progress))
    PreferenceToggle(stringResource(R.string.prefs_main_progress), controls.showMainProgress, enabled, viewModel::setMainProgressVisible)
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ProgressGestureMode.entries.forEach { mode ->
            PreferenceChoice(progressGestureLabel(mode), controls.progressGesture == mode, enabled) { viewModel.setProgressGesture(mode) }
        }
    }
    Explanation(stringResource(R.string.prefs_progress_scope))
    Heading(stringResource(R.string.prefs_rotary))
    PreferenceToggle(stringResource(R.string.prefs_rotary), controls.rotaryEnabled, enabled, viewModel::setRotaryEnabled)
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RotaryAction.entries.forEach { action ->
            PreferenceChoice(rotaryActionLabel(action), controls.rotaryAction == action, enabled) { viewModel.setRotaryAction(action) }
        }
    }
    Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RotaryOrientation.entries.forEach { orientation ->
            PreferenceChoice(rotaryOrientationLabel(orientation), controls.rotaryOrientation == orientation, enabled) {
                viewModel.setRotaryOrientation(orientation)
            }
        }
    }
    PreferenceToggle(stringResource(R.string.prefs_rotary_haptics), controls.rotaryHaptics, enabled, viewModel::setRotaryHaptics)
    Explanation(stringResource(R.string.prefs_rotary_scope))
    Heading(stringResource(R.string.prefs_sleep_summary))
    PreferenceToggle(stringResource(R.string.prefs_sleep_summary), controls.sleepTimerSummary, enabled, viewModel::setSleepTimerSummary)
    Explanation(stringResource(R.string.prefs_sleep_summary_scope))
}

@Composable
private fun PreferenceChoice(label: String, selected: Boolean, enabled: Boolean, onSelected: () -> Unit) {
    WatchChoiceRow(label, selected, enabled, onSelected)
}

@Composable
private fun PreferenceToggle(label: String, checked: Boolean, enabled: Boolean, onChanged: (Boolean) -> Unit) {
    WatchSwitchRow(label, checked, enabled, onChanged)
}

@Composable
private fun tapPolicyLabel(policy: SongTapPolicy): String = stringResource(when (policy) {
    SongTapPolicy.ASK -> R.string.prefs_song_tap_ask
    SongTapPolicy.INSERT_AND_PLAY -> R.string.prefs_song_tap_insert
    SongTapPolicy.REPLACE_COMPLETE_LIST -> R.string.prefs_song_tap_replace
})

@Composable
private fun backendLabel(backend: PlaybackBackendKind): String = stringResource(when (backend) {
    PlaybackBackendKind.MEDIA3 -> R.string.prefs_backend_media3
    PlaybackBackendKind.SYSTEM_MEDIA_PLAYER -> R.string.prefs_backend_system
})

@Composable
private fun onOffLabel(enabled: Boolean): String = stringResource(if (enabled) R.string.prefs_on else R.string.prefs_off)

@Composable
private fun rotaryActionLabel(action: RotaryAction): String = stringResource(when (action) {
    RotaryAction.MEDIA_VOLUME -> R.string.prefs_rotary_volume
    RotaryAction.SEEK -> R.string.prefs_rotary_seek
})

@Composable
private fun rotaryOrientationLabel(orientation: RotaryOrientation): String = stringResource(when (orientation) {
    RotaryOrientation.NORMAL -> R.string.prefs_rotary_normal
    RotaryOrientation.REVERSED -> R.string.prefs_rotary_reversed
})

@Composable
private fun progressGestureLabel(mode: ProgressGestureMode): String = stringResource(when (mode) {
    ProgressGestureMode.DRAG_TO_COMMIT -> R.string.prefs_progress_drag
    ProgressGestureMode.TAP_TO_SEEK -> R.string.prefs_progress_tap
})

@Composable
private fun StorageStatus(state: PreferencesEditorState, onRetryRead: () -> Unit) {
    when (val storage = state.storage) {
        PreferencesState.Loading -> Explanation(stringResource(R.string.prefs_loading))
        is PreferencesState.Unavailable -> {
            ErrorText(stringResource(R.string.prefs_read_failure))
            PreferenceButton(
                stringResource(R.string.prefs_retry_read),
                enabled = !state.isSaving,
                onClick = onRetryRead,
            )
        }
        is PreferencesState.Ready -> {
            val recovery = storage.recovery
            if (recovery != null) {
                val fields = recoveryFields(recovery.fields)
                val message = when (recovery.reason) {
                    PreferencesRecoveryReason.CORRUPT_FILE -> stringResource(R.string.prefs_recovery_corrupt, fields)
                    PreferencesRecoveryReason.UNRECOGNIZED_SCHEMA -> stringResource(R.string.prefs_recovery_old, fields)
                    PreferencesRecoveryReason.INVALID_FIELDS -> stringResource(R.string.prefs_recovery_fields, fields)
                    PreferencesRecoveryReason.NEWER_SCHEMA -> stringResource(R.string.prefs_recovery_newer, fields)
                }
                if (storage.canWrite) Explanation(message) else ErrorText(message)
            }
        }
    }
}

@Composable
private fun EditorStatus(state: PreferencesEditorState, onReload: () -> Unit) {
    if (state.isSaving) Explanation(stringResource(R.string.prefs_saving))
    if (state.hasConflict) {
        ErrorText(stringResource(R.string.prefs_conflict))
        Explanation(stringResource(R.string.prefs_reload_scope))
    }
    val failure = state.failure
    if (failure != null) {
        val label = when (failure) {
            PreferencesFailure.IO -> if (state.failedOperation == PreferencesEditOperation.RESET) {
                R.string.prefs_reset_failure
            } else {
                R.string.prefs_save_failure
            }
            PreferencesFailure.BUSY -> R.string.backup_storage_busy
            PreferencesFailure.UNSUPPORTED_SCHEMA -> R.string.prefs_write_unsupported
            PreferencesFailure.INVALID_REVISION -> R.string.prefs_invalid_edit
            PreferencesFailure.REVISION_EXHAUSTED -> R.string.prefs_revision_exhausted
        }
        ErrorText(stringResource(label))
    }
    if (state.hasConflict || failure == PreferencesFailure.INVALID_REVISION) {
        PreferenceButton(
            stringResource(R.string.prefs_reload_current),
            enabled = state.canReload,
            onClick = onReload,
        )
    }
    val feedback = state.feedback
    if (feedback != null) {
        val label = when (feedback) {
            PreferencesEditFeedback.SAVED -> R.string.prefs_saved
            PreferencesEditFeedback.UNCHANGED -> R.string.prefs_unchanged
            PreferencesEditFeedback.DEFAULTS_RESTORED -> R.string.prefs_defaults_restored
            PreferencesEditFeedback.DEFAULTS_ALREADY_SET -> R.string.prefs_defaults_already_set
            PreferencesEditFeedback.RESET_CANCELLED -> R.string.prefs_defaults_cancelled
        }
        Explanation(stringResource(label))
    }
}

@Composable
private fun recoveryFields(fields: Set<PreferencesField>): String {
    val format = stringResource(R.string.prefs_field_format)
    val history = stringResource(R.string.prefs_field_history)
    val position = stringResource(R.string.prefs_restore_position)
    val shape = stringResource(R.string.prefs_screen_shape)
    val layout = stringResource(R.string.library_layout)
    val artwork = stringResource(R.string.library_artwork_path)
    val sorts = stringResource(R.string.library_sort)
    val tapPolicy = stringResource(R.string.prefs_song_tap)
    val mediaButtons = stringResource(R.string.prefs_media_buttons)
    val audioFocus = stringResource(R.string.prefs_audio_focus)
    val backend = stringResource(R.string.prefs_playback_backend)
    val speed = stringResource(R.string.prefs_playback_speed)
    val gapless = stringResource(R.string.prefs_gapless)
    val rotary = stringResource(R.string.prefs_rotary)
    val rotaryTarget = stringResource(R.string.prefs_rotary_target)
    val rotaryDirection = stringResource(R.string.prefs_rotary_direction)
    val rotaryHaptics = stringResource(R.string.prefs_rotary_haptics)
    val mainProgress = stringResource(R.string.prefs_main_progress)
    val seekGesture = stringResource(R.string.prefs_progress_gesture)
    val sleepSummary = stringResource(R.string.prefs_sleep_summary)
    val help = stringResource(R.string.help_title)
    val bluetoothLyrics = stringResource(R.string.bluetooth_lyrics_title)
    val metadataLookup = stringResource(R.string.metadata_lookup_enabled)
    val separator = stringResource(R.string.prefs_field_separator)
    return PreferencesField.entries.filter { field -> field in fields }.joinToString(separator) { field ->
        when (field) {
            PreferencesField.SCHEMA_VERSION -> format
            PreferencesField.REVISION -> history
            PreferencesField.RESTORE_POSITION -> position
            PreferencesField.SCREEN_SHAPE -> shape
            PreferencesField.LIBRARY_LAYOUT -> layout
            PreferencesField.ARTWORK_READ_PATH -> artwork
            PreferencesField.LIBRARY_SORTS -> sorts
            PreferencesField.SONG_TAP_POLICY -> tapPolicy
            PreferencesField.MEDIA_BUTTONS -> mediaButtons
            PreferencesField.AUDIO_FOCUS -> audioFocus
            PreferencesField.PLAYBACK_BACKEND -> backend
            PreferencesField.PLAYBACK_SPEED -> speed
            PreferencesField.GAPLESS -> gapless
            PreferencesField.ROTARY_CONTROL -> rotary
            PreferencesField.ROTARY_TARGET -> rotaryTarget
            PreferencesField.ROTARY_DIRECTION -> rotaryDirection
            PreferencesField.ROTARY_HAPTICS -> rotaryHaptics
            PreferencesField.MAIN_PROGRESS -> mainProgress
            PreferencesField.SEEK_GESTURE -> seekGesture
            PreferencesField.SLEEP_SUMMARY -> sleepSummary
            PreferencesField.HELP -> help
            PreferencesField.BLUETOOTH_LYRICS -> bluetoothLyrics
            PreferencesField.METADATA_LOOKUP -> metadataLookup
        }
    }
}

@Composable
private fun shapeLabel(shape: ScreenShape): String = stringResource(
    when (shape) {
        ScreenShape.AUTO -> R.string.prefs_shape_auto
        ScreenShape.ROUND -> R.string.prefs_shape_round
        ScreenShape.RECTANGULAR -> R.string.prefs_shape_rectangle
    },
)

@Composable
private fun Explanation(text: String) {
    WatchText(text)
}

@Composable
private fun ErrorText(text: String) {
    WatchText(text, error = true)
}

@Composable
private fun Heading(text: String) {
    WatchText(text, primary = true)
}

@Composable
private fun PreferenceButton(
    label: String,
    enabled: Boolean = true,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    WatchActionRow(label, enabled = enabled, primary = primary, onClick = onClick)
}
