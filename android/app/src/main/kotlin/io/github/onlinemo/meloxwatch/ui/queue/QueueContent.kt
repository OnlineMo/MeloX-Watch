package io.github.onlinemo.meloxwatch.ui.queue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.model.LocalAccess
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueRepeatMode
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.util.Locale

/** 宿主负责可见生命周期和唯一控制器；每个实例是独立 lazy item，不创建播放器。 */
fun LazyListScope.queueContent(
    state: QueueViewerState,
    currentEntryId: String?,
    playbackCommandPending: Boolean,
    onSelect: (QueueSnapshot, String) -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onRetry: () -> Unit,
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
                onClick = onRetry,
            )
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
            }
        }
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
            QueueEntryButton(
                entry = entry,
                isCurrent = isCurrent,
                isSavedCurrent = entry.queueEntryId == snapshot.currentEntryId && !isCurrent,
                enabled = state.canSelect && !playbackCommandPending &&
                    entry.trackKey.providerId == TrackKey.LOCAL_PROVIDER && entry.location != null,
                onClick = { onSelect(snapshot, entry.queueEntryId) },
            )
        }
        item(key = "queue:previous") {
            QueueButton(
                stringResource(R.string.queue_previous_page),
                enabled = state.hasPreviousPage,
                onClick = onPreviousPage,
            )
        }
        item(key = "queue:next") {
            QueueButton(
                stringResource(R.string.queue_next_page),
                enabled = state.hasNextPage,
                onClick = onNextPage,
            )
        }
    }
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
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { selected = isCurrent },
        colors = ButtonDefaults.outlinedButtonColors(
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                entry.displayName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.queue_unnamed_entry),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            QueueText(stringResource(R.string.queue_instance_order, entry.playbackOrder + 1L))
            if (isCurrent || isSavedCurrent) {
                QueueText(
                    stringResource(if (isCurrent) R.string.queue_current_entry else R.string.queue_saved_current_entry),
                    primary = true,
                )
            }
            entry.durationMs?.let { duration ->
                QueueText(stringResource(R.string.queue_duration, elapsedTime(duration)))
            }
            QueueText(stringResource(entryStatusText(entry)))
        }
    }
}

@Composable
private fun QueueButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text(label, textAlign = TextAlign.Center)
    }
}

@Composable
private fun QueueText(text: String, primary: Boolean = false, error: Boolean = false) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth(),
        style = if (primary) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
        color = when {
            error -> MaterialTheme.colorScheme.error
            primary -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
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
