package io.github.onlinemo.meloxwatch.ui.preferences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesFailure
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesField
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesRecoveryReason
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesState
import io.github.onlinemo.meloxwatch.model.ScreenShape

/** 放入宿主的可滚动 item；宿主负责标题、真实窗口避让与导航 enterEditor/clearDraft。 */
@Composable
fun PreferencesControls(viewModel: PreferencesViewModel, onCancel: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CurrentPreferences(state)
        StorageStatus(state, onRetryRead = viewModel::retryRead)
        EditorStatus(state, onReload = viewModel::reloadCurrentValues)
        val draft = state.draft
        if (state.isEditing && draft != null) {
            Text(stringResource(R.string.prefs_edit_values), style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .semantics(mergeDescendants = true) {}
                    .toggleable(
                        value = draft.restorePosition,
                        enabled = state.canEdit,
                        role = Role.Switch,
                        onValueChange = viewModel::setRestorePosition,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.prefs_restore_position),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Switch(checked = draft.restorePosition, onCheckedChange = null, enabled = state.canEdit)
            }
            Explanation(stringResource(R.string.prefs_restore_position_scope))
            Text(stringResource(R.string.prefs_screen_shape), style = MaterialTheme.typography.titleMedium)
            Explanation(stringResource(R.string.prefs_screen_shape_scope))
            Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ScreenShape.entries.forEach { shape ->
                    val selected = draft.screenShape == shape
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .semantics(mergeDescendants = true) {}
                            .selectable(
                                selected = selected,
                                enabled = state.canEdit,
                                role = Role.RadioButton,
                                onClick = { viewModel.setScreenShape(shape) },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = selected, onClick = null, enabled = state.canEdit)
                        Text(
                            shapeLabel(shape),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
            Explanation(stringResource(R.string.prefs_save_scope))
            if (state.confirmReset) {
                Text(stringResource(R.string.prefs_reset_question), style = MaterialTheme.typography.bodyLarge)
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
        } else {
            Explanation(stringResource(R.string.prefs_wait_for_settings))
        }
        PreferenceButton(stringResource(R.string.prefs_cancel_edit), enabled = !state.isSaving) {
            viewModel.clearDraft()
            onCancel()
        }
    }
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
    Text(stringResource(heading), style = MaterialTheme.typography.titleMedium)
    Explanation(
        stringResource(
            R.string.prefs_restore_position_value,
            stringResource(if (state.effective.restorePosition) R.string.prefs_on else R.string.prefs_off),
        ),
    )
    Explanation(stringResource(R.string.prefs_screen_shape_value, shapeLabel(state.effective.screenShape)))
}

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
    val separator = stringResource(R.string.prefs_field_separator)
    return PreferencesField.entries.filter { field -> field in fields }.joinToString(separator) { field ->
        when (field) {
            PreferencesField.SCHEMA_VERSION -> format
            PreferencesField.REVISION -> history
            PreferencesField.RESTORE_POSITION -> position
            PreferencesField.SCREEN_SHAPE -> shape
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
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ErrorText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun PreferenceButton(
    label: String,
    enabled: Boolean = true,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
    if (primary) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label, textAlign = TextAlign.Center) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) {
            Text(label, textAlign = TextAlign.Center)
        }
    }
}
