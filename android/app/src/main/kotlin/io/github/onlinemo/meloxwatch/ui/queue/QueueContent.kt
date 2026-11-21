package io.github.onlinemo.meloxwatch.ui.queue

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.model.ArtworkReadPath
import io.github.onlinemo.meloxwatch.model.LocalAccess
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueMutationRequest
import io.github.onlinemo.meloxwatch.model.QueueRepeatMode
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.ui.watch.WatchActionRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchChoiceRow
import io.github.onlinemo.meloxwatch.ui.themes.LocalWatchThemeColors
import io.github.onlinemo.meloxwatch.ui.themes.LocalWatchThemeFonts
import io.github.onlinemo.meloxwatch.ui.watch.WatchSwitchRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchText
import io.github.onlinemo.meloxwatch.ui.watch.watchRowMinimum
import io.github.onlinemo.meloxwatch.ui.watch.watchSpacing
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
    artworkPath: ArtworkReadPath = ArtworkReadPath.SYSTEM_THUMBNAIL,
) {
    // Saver 保存索引/偏移，状态有无不能改变歌曲行前的固定 item 数量。
    item(key = "queue:status") {
        if (state.loading || !state.hasLoaded || state.failure != null || state.showingPreviousData ||
            state.editMessage != null || playbackCommandPending || state.artistPhase == QueueArtistPhase.FAILED
        ) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.showingPreviousData) {
                QueueText(stringResource(R.string.queue_previous_data), primary = true)
            }
            if (state.loading || (!state.hasLoaded && state.failure == null)) {
                QueueText(stringResource(R.string.queue_loading))
            }
            state.failure?.let { failure ->
                QueueText(stringResource(failureText(failure)), error = true)
            }
            if (state.artistPhase == QueueArtistPhase.FAILED && state.failure == null) {
                QueueText(stringResource(R.string.queue_artist_read_failed))
                QueueButton(stringResource(R.string.queue_reload), !state.loading && !playbackCommandPending, model::retry)
            }
            if (state.failure != null || state.showingPreviousData && !state.loading) {
                QueueButton(stringResource(R.string.queue_reload), !state.loading, model::retry)
            }
            if (playbackCommandPending) QueueText(stringResource(R.string.queue_command_pending))
            state.editMessage?.let { QueueText(stringResource(editMessageText(it)), primary = true) }
        }
    }
    val snapshot = state.snapshot
    val choosingEntry = state.editor?.action == QueueEditAction.CHOOSE_ENTRY
    if (state.editor != null && !choosingEntry) item(key = "queue:editor") {
        QueueEditorControls(state, model, playbackCommandPending, onMutation)
    }
    if (state.editor != null && !choosingEntry) return
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
                QueueModesSummary(snapshot)
                if (choosingEntry) QueueManagementPrompt(state, model, playbackCommandPending)
                else QueueButton(stringResource(R.string.queue_controls),
                    state.canSelect && !playbackCommandPending, model::beginQueueControls)
            }
        }
    }
    if (state.hasLoaded && !state.loading && state.failure == null && state.entries.isEmpty()) {
        item(key = "queue:empty") { QueueText(stringResource(R.string.queue_empty)) }
    }
    if (snapshot != null && state.entries.isNotEmpty()) {
        items(state.entries, key = { entry -> "queue:entry:${entry.queueEntryId}" }) { entry ->
            val isCurrent = !state.showingPreviousData && entry.queueEntryId == currentEntryId
            QueueEntryButton(
                entry = entry,
                artist = QueueArtistLabel(state, entry),
                rowsIdentity = state.rowsIdentity,
                generationId = snapshot.generationId,
                artworkVisible = state.canSelect && entry.queueEntryId in state.visibleEntryIds,
                artworkPath = artworkPath,
                isCurrent = isCurrent,
                isSavedCurrent = entry.queueEntryId == snapshot.currentEntryId && !isCurrent,
                isPlaying = isPlaying,
                managing = choosingEntry,
                enabled = state.canSelect && !playbackCommandPending && !state.editorConflict &&
                    (choosingEntry || entry.trackKey.providerId == TrackKey.LOCAL_PROVIDER && entry.location != null),
                onClick = {
                    if (choosingEntry) model.beginEntryEdit(entry) else onSelect(snapshot, entry.queueEntryId)
                },
            )
        }
        item(key = "queue:range") {
            QueueText(stringResource(R.string.queue_page_range, state.pageStart + 1L,
                state.pageStart + state.entries.size.toLong(), snapshot.entryCount))
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
                it.displayName?.takeIf { name -> name.isNotBlank() } ?: stringResource(R.string.queue_unnamed_entry)), primary = true)
            QueueText(stringResource(R.string.queue_artist_detail, QueueArtistLabel(state, it)))
            it.durationMs?.let { duration -> QueueText(stringResource(R.string.queue_duration, elapsedTime(duration))) }
            QueueText(stringResource(entryStatusText(it)))
        }
        if (state.editorConflict) QueueText(stringResource(R.string.queue_edit_conflict), error = true)
        when (draft.action) {
            QueueEditAction.MENU -> {
                QueueButton(stringResource(R.string.queue_manage_items), enabled && draft.base.entryCount > 0L,
                    model::beginEntryManagement)
                QueueButton(stringResource(R.string.queue_edit_mode), enabled, model::beginModeEdit)
                QueueButton(stringResource(R.string.queue_locate_current), enabled && draft.base.currentEntryId != null) {
                    model.cancelEdit()
                    model.locateCurrent()
                }
                QueueButton(stringResource(R.string.queue_reload), !state.loading && !pending, model::retry)
                SavedModes(draft.base)
                draft.base.currentPlaybackOrder?.let { order ->
                    QueueText(stringResource(R.string.queue_saved_position, order + 1L, draft.base.entryCount))
                }
                QueueText(stringResource(R.string.queue_selection_hint))
            }
            QueueEditAction.CHOOSE_ENTRY -> Unit // 此状态由 summary item 与歌曲行呈现。
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
        QueueButton(stringResource(if (draft.action == QueueEditAction.MENU)
            R.string.queue_back_to_list else R.string.prefs_cancel_edit), !pending, model::cancelEdit)
    }
}

@Composable
private fun QueueManagementPrompt(state: QueueViewerState, model: QueueViewModel, pending: Boolean) {
    // 保持同一个 summary item，管理选择不会给已保存的歌曲索引插入一个新行。
    QueueText(stringResource(R.string.queue_choose_manage), primary = true)
    QueueText(stringResource(R.string.queue_management_hint))
    if (state.editorConflict) {
        QueueText(stringResource(R.string.queue_edit_conflict), error = true)
        QueueButton(stringResource(R.string.queue_reload), !state.loading && !pending, model::retry)
    }
    QueueButton(stringResource(R.string.queue_back_to_list), !pending, model::cancelEdit)
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
private fun QueueModesSummary(snapshot: QueueSnapshot) {
    val repeat = when (snapshot.mode.repeat) {
        QueueRepeatMode.OFF -> R.string.queue_repeat_off
        QueueRepeatMode.LIST -> R.string.queue_repeat_list
        QueueRepeatMode.SINGLE -> R.string.queue_repeat_single
    }
    QueueText(stringResource(R.string.queue_mode_summary,
        stringResource(if (snapshot.mode.shuffle) R.string.queue_order_shuffle else R.string.queue_order_sequence),
        stringResource(R.string.queue_repeat, stringResource(repeat))))
    if (snapshot.mode.repeat == QueueRepeatMode.SINGLE) {
        val limit = snapshot.mode.cycleLimit
        QueueText(if (limit == null) stringResource(R.string.queue_repeat_until_changed)
            else stringResource(R.string.queue_repeat_progress, snapshot.checkpoint.completedCycles, limit))
    }
}

@Composable
private fun QueueEntryButton(
    entry: QueueEntry,
    artist: String,
    rowsIdentity: Any?,
    generationId: String,
    artworkVisible: Boolean,
    artworkPath: ArtworkReadPath,
    isCurrent: Boolean,
    isSavedCurrent: Boolean,
    isPlaying: Boolean,
    managing: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalWatchThemeColors.current
    val fonts = LocalWatchThemeFonts.current
    val largeText = LocalDensity.current.fontScale >= 1.5f
    val artworkSize = if (largeText) 32.dp else 36.dp
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(watchSpacing(8.dp))
    val currentLabel = when {
        isCurrent -> stringResource(R.string.queue_current_entry)
        isSavedCurrent -> stringResource(R.string.queue_saved_current_entry)
        else -> null
    }
    val orderDescription = stringResource(R.string.queue_instance_order, entry.playbackOrder + 1L)
    val orderAndDuration = entry.durationMs?.let { duration ->
        "$orderDescription, ${stringResource(R.string.queue_duration, elapsedTime(duration))}"
    } ?: orderDescription
    val subtitleDescription = stringResource(R.string.queue_row_artist,
        stringResource(R.string.queue_artist_detail, artist), orderAndDuration)
    val ordinal = stringResource(R.string.queue_row_number, entry.playbackOrder + 1L)
    Column(
        Modifier.fillMaxWidth().heightIn(min = watchRowMinimum()).clip(shape)
            .background(if (pressed) colors.pressed else Color.Transparent)
            .then(if (focused) Modifier.border(2.dp, colors.focus, shape) else Modifier)
            .semantics(mergeDescendants = true) {
                selected = isCurrent
                if (currentLabel != null) stateDescription = currentLabel
            }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button,
                onClickLabel = stringResource(if (managing) R.string.queue_open_management else R.string.queue_open_item),
                onClick = onClick)
            .padding(horizontal = watchSpacing(8.dp), vertical = watchSpacing(8.dp)),
        verticalArrangement = Arrangement.spacedBy(watchSpacing(2.dp)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(watchSpacing(8.dp))) {
            Box(Modifier.size(artworkSize)) {
                QueueRowArtwork(entry, rowsIdentity, generationId, artworkVisible, artworkPath, artworkSize)
                if (isCurrent) Box(Modifier.align(Alignment.BottomEnd)
                    .background(colors.background, RoundedCornerShape(3.dp))) { CurrentEntrySymbol(isPlaying) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(watchSpacing(2.dp))) {
                BasicText(
                    entry.displayName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.queue_unnamed_entry),
                    modifier = Modifier.fillMaxWidth(),
                    style = TextStyle(color = if (enabled) colors.text else colors.disabled,
                        fontFamily = fonts.originalFamily, fontWeight = fonts.originalWeight, fontSynthesis = FontSynthesis.None,
                        fontSize = 16.sp, lineHeight = 22.sp),
                    maxLines = if (largeText) 3 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(watchSpacing(4.dp))) {
                    // Instance identity remains visible even when a valid long artist is shortened.
                    if (currentLabel == null) WatchText(ordinal,
                        Modifier.clearAndSetSemantics { }, enabled = enabled)
                    BasicText(
                        artist,
                        modifier = Modifier.weight(1f).semantics { contentDescription = subtitleDescription },
                        style = TextStyle(color = if (enabled) colors.secondary else colors.disabled,
                            fontFamily = fonts.originalFamily, fontWeight = fonts.originalWeight, fontSynthesis = FontSynthesis.None,
                            fontSize = 14.sp, lineHeight = 20.sp),
                        maxLines = if (largeText) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (currentLabel != null) WatchText(stringResource(R.string.queue_row_current, ordinal, currentLabel),
                    Modifier.fillMaxWidth().clearAndSetSemantics { }, enabled = enabled)
            }
        }
        if (entry.trackKey.providerId != TrackKey.LOCAL_PROVIDER || entry.location == null) {
            QueueText(stringResource(entryStatusText(entry)), error = true)
        }
        Canvas(Modifier.fillMaxWidth().height(1.dp)) { drawLine(colors.outline, Offset.Zero, Offset(size.width, 0f)) }
    }
}

@Composable
private fun QueueArtistLabel(state: QueueViewerState, entry: QueueEntry): String = when {
    entry.trackKey.providerId != TrackKey.LOCAL_PROVIDER -> stringResource(R.string.queue_artist_unavailable)
    state.artists.containsKey(entry.trackKey) -> state.artists[entry.trackKey]?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.library_unknown_artist)
    state.artistPhase == QueueArtistPhase.LOADING -> stringResource(R.string.queue_artist_loading)
    state.artistPhase == QueueArtistPhase.FAILED -> stringResource(R.string.queue_artist_read_failed)
    else -> stringResource(R.string.queue_artist_unavailable)
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
    val accent = LocalWatchThemeColors.current.accent
    Canvas(Modifier.size(18.dp)) {
        val bars = if (playing) listOf(0.25f to 0.55f, 0.5f to 0.9f, 0.75f to 0.7f)
            else listOf(0.35f to 0.85f, 0.65f to 0.85f)
        for ((x, height) in bars) drawLine(accent,
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
