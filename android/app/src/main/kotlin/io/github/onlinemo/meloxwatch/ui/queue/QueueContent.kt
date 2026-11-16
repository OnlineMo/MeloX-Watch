package io.github.onlinemo.meloxwatch.ui.queue

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.model.LocalAccess
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueMutationRequest
import io.github.onlinemo.meloxwatch.model.QueueRepeatMode
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.ui.watch.WatchActionRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchChoiceRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchContentRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchPalette
import io.github.onlinemo.meloxwatch.ui.watch.WatchSwitchRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchText
import java.util.Locale

/** 宿主负责可见生命周期和唯一控制器；每个实例是独立 lazy item，不创建播放器。 */
fun LazyListScope.queueContent(
    state: QueueViewerState,
    currentEntryId: String?,
    playbackCommandPending: Boolean,
    onSelect: (QueueSnapshot, String) -> Unit,
    model: QueueViewModel,
    onMutation: (QueueMutationRequest) -> Unit,
    isPlaying: Boolean = false,
) {
    item(key = "queue:status") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.showingPreviousData) {
                QueueText(stringResource(R.string.queue_previous_data), primary = true)
            }
            if (state.loading || (!state.hasLoaded && state.failure == null)) {
                QueueText(stringResource(R.string.queue_loading))
            }
            state.failure?.let { failure ->
                QueueText(stringResource(failureText(failure)), error = true)
            }
            QueueButton(
                label = stringResource(R.string.queue_reload),
                enabled = !state.loading,
                onClick = model::retry,
            )
            state.editMessage?.let { QueueText(stringResource(editMessageText(it)), primary = true) }
        }
    }
    val snapshot = state.snapshot
    if (snapshot != null) {
        item(key = "queue:summary") {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 目前 en/zh 的 one 只取精确 1；传给格式化器的仍是完整 Long。
                QueueText(
                    pluralStringResource(
                        R.plurals.queue_entry_count,
                        if (snapshot.entryCount == 1L) 1 else 2,
                        snapshot.entryCount,
                    ),
                    primary = true,
                )
                if (state.entries.isNotEmpty()) {
                    QueueText(
                        stringResource(
                            R.string.queue_page_range,
                            state.pageStart + 1L,
                            state.pageStart + state.entries.size.toLong(),
                            snapshot.entryCount,
                        ),
                    )
                }
                val currentOrder = snapshot.currentPlaybackOrder
                if (currentOrder != null && currentOrder >= 0L && currentOrder < snapshot.entryCount) {
                    QueueText(
                        stringResource(R.string.queue_saved_position, currentOrder + 1L, snapshot.entryCount),
                    )
                }
                SavedModes(snapshot)
                QueueButton(stringResource(R.string.queue_edit_mode),
                    state.canSelect && !playbackCommandPending && state.editor == null, model::beginModeEdit)
                QueueButton(stringResource(R.string.queue_locate_current),
                    state.canSelect && snapshot.currentEntryId != null && state.editor == null, model::locateCurrent)
            }
        }
    }
    if (state.editor != null) item(key = "queue:editor") {
        QueueEditorControls(state, model, playbackCommandPending, onMutation)
    }
    if (state.hasLoaded && !state.loading && state.failure == null && state.entries.isEmpty()) {
        item(key = "queue:empty") { QueueText(stringResource(R.string.queue_empty)) }
    }
    if (snapshot != null && state.entries.isNotEmpty()) {
        item(key = "queue:selection") {
            QueueText(
                stringResource(
                    if (playbackCommandPending) R.string.queue_command_pending else R.string.queue_selection_hint,
                ),
            )
        }
        items(state.entries, key = { entry -> "queue:entry:${entry.queueEntryId}" }) { entry ->
            val isCurrent = !state.showingPreviousData && entry.queueEntryId == currentEntryId
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                QueueEntryButton(
                    entry = entry,
                    isCurrent = isCurrent,
                    isSavedCurrent = entry.queueEntryId == snapshot.currentEntryId && !isCurrent,
                    isPlaying = isPlaying,
                    enabled = state.canSelect && !playbackCommandPending && state.editor == null &&
                        entry.trackKey.providerId == TrackKey.LOCAL_PROVIDER && entry.location != null,
                    onClick = { onSelect(snapshot, entry.queueEntryId) },
                )
                QueueButton(stringResource(R.string.queue_manage_entry, entry.playbackOrder + 1L),
                    state.canSelect && !playbackCommandPending && state.editor == null) { model.beginEntryEdit(entry) }
            }
        }
        item(key = "queue:previous") {
            QueueButton(
                stringResource(R.string.queue_previous_page),
                enabled = state.hasPreviousPage,
                onClick = model::previousPage,
            )
        }
        item(key = "queue:next") {
            QueueButton(
                stringResource(R.string.queue_next_page),
                enabled = state.hasNextPage,
                onClick = model::nextPage,
            )
        }
    }
}

@Composable
private fun QueueEditorControls(
    state: QueueViewerState, model: QueueViewModel, pending: Boolean, onMutation: (QueueMutationRequest) -> Unit,
) {
    val draft = state.editor ?: return
    val enabled = state.canSelect && !state.editorConflict && !pending
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        draft.entry?.let {
            QueueText(stringResource(R.string.queue_edit_target, it.playbackOrder + 1L,
                it.displayName ?: stringResource(R.string.queue_unnamed_entry)), primary = true)
        }
        if (state.editorConflict) QueueText(stringResource(R.string.queue_edit_conflict), error = true)
        when (draft.action) {
            QueueEditAction.CHOOSE -> {
                QueueButton(stringResource(R.string.queue_move_entry), enabled) { model.chooseEntryAction(QueueEditAction.MOVE) }
                QueueButton(stringResource(R.string.queue_remove_entry), enabled) { model.chooseEntryAction(QueueEditAction.REMOVE) }
            }
            QueueEditAction.REMOVE -> {
                QueueText(stringResource(R.string.queue_remove_scope))
                val current = draft.entry?.queueEntryId == draft.base.currentEntryId
                if (current) QueueText(stringResource(when {
                    draft.base.entryCount == 1L -> R.string.queue_remove_current_only
                    draft.entry?.playbackOrder == draft.base.entryCount - 1L -> R.string.queue_remove_current_last
                    else -> R.string.queue_remove_current_next
                }))
                QueueButton(stringResource(R.string.queue_confirm_remove), enabled && draft.canSubmit) {
                    model.submitEdit(onMutation)
                }
            }
            QueueEditAction.MOVE -> {
                QueueText(stringResource(if (draft.base.mode.shuffle) R.string.queue_move_shuffle_scope else R.string.queue_move_scope))
                OutlinedTextField(
                    value = draft.destinationText, onValueChange = model::setDestination, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    label = { Text(stringResource(R.string.queue_destination, draft.base.entryCount)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, isError = draft.destinationOrder == null,
                )
                QueueButton(stringResource(R.string.queue_move_up), enabled && draft.destinationOrder?.let { it > 0L } == true) {
                    model.stepDestination(-1)
                }
                QueueButton(stringResource(R.string.queue_move_down),
                    enabled && draft.destinationOrder?.let { it < draft.base.entryCount - 1L } == true) { model.stepDestination(1) }
                if (draft.destinationOrder == null) QueueText(stringResource(R.string.queue_destination_invalid), error = true)
                QueueButton(stringResource(R.string.prefs_save), enabled && draft.canSubmit) { model.submitEdit(onMutation) }
            }
            QueueEditAction.MODE -> {
                QueueToggle(stringResource(R.string.queue_order_shuffle), draft.shuffle, enabled, model::setShuffle)
                QueueText(stringResource(R.string.queue_shuffle_scope))
                Column(Modifier.fillMaxWidth().selectableGroup()) {
                    QueueRepeatMode.entries.forEach { repeat ->
                        QueueChoice(stringResource(when (repeat) {
                            QueueRepeatMode.OFF -> R.string.queue_repeat_off
                            QueueRepeatMode.LIST -> R.string.queue_repeat_list
                            QueueRepeatMode.SINGLE -> R.string.queue_repeat_single
                        }), draft.repeat == repeat, enabled) { model.setRepeat(repeat) }
                    }
                }
                QueueToggle(stringResource(R.string.queue_cycle_limit_toggle), draft.limitEnabled, enabled, model::setCycleLimitEnabled)
                QueueText(stringResource(R.string.queue_cycle_limit_scope))
                if (draft.limitEnabled) {
                    OutlinedTextField(
                        value = draft.limitText, onValueChange = model::setCycleLimitText, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        label = { Text(stringResource(R.string.queue_cycle_limit_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, isError = draft.mode == null,
                    )
                }
                QueueButton(stringResource(R.string.prefs_save), enabled && draft.canSubmit) { model.submitEdit(onMutation) }
            }
        }
        QueueButton(stringResource(R.string.prefs_cancel_edit), !pending, model::cancelEdit)
    }
}

@Composable
private fun QueueChoice(label: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    WatchChoiceRow(label, selected, enabled, onSelect)
}

@Composable
private fun QueueToggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    WatchSwitchRow(label, checked, enabled, onChange)
}

private fun editMessageText(message: QueueEditMessage): Int = when (message) {
    QueueEditMessage.REMOVED -> R.string.queue_item_removed
    QueueEditMessage.CURRENT_REMOVED_PAUSED -> R.string.queue_current_removed_paused
    QueueEditMessage.MOVED -> R.string.queue_item_moved
    QueueEditMessage.MODE_SAVED -> R.string.queue_mode_saved
}

@Composable
private fun SavedModes(snapshot: QueueSnapshot) {
    QueueText(stringResource(R.string.queue_saved_modes), primary = true)
    QueueText(
        stringResource(
            R.string.queue_order,
            stringResource(if (snapshot.mode.shuffle) R.string.queue_order_shuffle else R.string.queue_order_sequence),
        ),
    )
    val repeat = when (snapshot.mode.repeat) {
        QueueRepeatMode.OFF -> R.string.queue_repeat_off
        QueueRepeatMode.LIST -> R.string.queue_repeat_list
        QueueRepeatMode.SINGLE -> R.string.queue_repeat_single
    }
    QueueText(stringResource(R.string.queue_repeat, stringResource(repeat)))
    val limit = snapshot.mode.cycleLimit
    QueueText(
        if (limit == null) {
            stringResource(R.string.queue_repeat_limit_off)
        } else {
            pluralStringResource(R.plurals.queue_repeat_limit, limit, limit)
        },
    )
    val completed = snapshot.checkpoint.completedCycles
    QueueText(pluralStringResource(R.plurals.queue_completed_cycles, completed, completed))
}

@Composable
private fun QueueEntryButton(
    entry: QueueEntry,
    isCurrent: Boolean,
    isSavedCurrent: Boolean,
    isPlaying: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    WatchContentRow(onClick = onClick, enabled = enabled, selected = isCurrent) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isCurrent) CurrentEntrySymbol(isPlaying)
                Text(
                    entry.displayName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.queue_unnamed_entry),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) WatchPalette.Text else WatchPalette.Disabled,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            QueueText(stringResource(R.string.queue_instance_order, entry.playbackOrder + 1L), enabled = enabled)
            if (isCurrent || isSavedCurrent) {
                QueueText(
                    stringResource(if (isCurrent) R.string.queue_current_entry else R.string.queue_saved_current_entry),
                    primary = true,
                    enabled = enabled,
                )
            }
            entry.durationMs?.let { duration ->
                QueueText(stringResource(R.string.queue_duration, elapsedTime(duration)), enabled = enabled)
            }
            QueueText(stringResource(entryStatusText(entry)), enabled = enabled)
        }
    }
}

@Composable
private fun QueueButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    WatchActionRow(label, enabled, onClick = onClick)
}

@Composable
private fun QueueText(text: String, primary: Boolean = false, error: Boolean = false, enabled: Boolean = true) {
    WatchText(text, Modifier.fillMaxWidth(), primary, enabled, error)
}

@Composable
private fun CurrentEntrySymbol(playing: Boolean) {
    Canvas(Modifier.size(18.dp)) {
        val bars = if (playing) listOf(0.25f to 0.55f, 0.5f to 0.9f, 0.75f to 0.7f)
            else listOf(0.35f to 0.85f, 0.65f to 0.85f)
        for ((x, height) in bars) drawLine(WatchPalette.Accent,
            Offset(size.width * x, size.height * (1f - height)), Offset(size.width * x, size.height * height),
            strokeWidth = 3.dp.toPx())
    }
}

private fun entryStatusText(entry: QueueEntry): Int {
    if (entry.trackKey.providerId != TrackKey.LOCAL_PROVIDER) return R.string.queue_source_placeholder
    return when (entry.location?.access) {
        null -> R.string.queue_missing_location
        LocalAccess.TEMPORARY -> R.string.queue_access_temporary
        LocalAccess.PERSISTED -> R.string.queue_access_persisted
        LocalAccess.MEDIA_PERMISSION -> R.string.queue_access_media_permission
        LocalAccess.PERSISTENCE_REJECTED -> R.string.queue_access_not_persisted
        LocalAccess.APP_OWNED -> R.string.library_access_owned
    }
}

private fun failureText(failure: QueueViewerFailure): Int = when (failure) {
    QueueViewerFailure.CONFLICT -> R.string.queue_failure_conflict
    QueueViewerFailure.STORAGE_FULL -> R.string.queue_failure_storage_full
    QueueViewerFailure.STORAGE_UNAVAILABLE -> R.string.queue_failure_storage_unavailable
    QueueViewerFailure.RECOVERY_REQUIRED -> R.string.queue_failure_recovery
    QueueViewerFailure.READ_FAILED -> R.string.queue_failure_read
}

private fun elapsedTime(durationMs: Long): String {
    val seconds = durationMs / 1000L
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60L, seconds % 60L)
}
