package io.github.onlinemo.meloxwatch.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.data.listening.ListeningIssue
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun LazyListScope.listeningContent(
    screen: LibraryScreen,
    state: ListeningUiState,
    model: ListeningViewModel,
    openTrack: (TrackKey) -> Unit,
) {
    item(key = "listening:status") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LibraryText(stringResource(R.string.library_listening_rules))
            if (state.loading) LibraryText(stringResource(R.string.library_loading))
            if (state.previousData) LibraryText(stringResource(R.string.library_previous_data))
            state.issue?.let { LibraryText(stringResource(listeningIssueText(it)), error = true) }
            state.summary?.let { summary ->
                LibraryText(stringResource(R.string.library_listening_totals, summary.totalPlayCount, formatDuration(summary.totalListeningMs)))
                LibraryText(stringResource(R.string.library_history_scope, summary.historyCount))
            }
            LibraryButton(stringResource(R.string.library_reload), !state.loading, model::reload)
            LibraryButton(stringResource(if (screen == LibraryScreen.HISTORY) R.string.library_clear_history else R.string.library_clear_statistics),
                !state.loading && state.summary != null) {
                model.askClear(if (screen == LibraryScreen.HISTORY) ListeningClear.HISTORY else ListeningClear.STATISTICS)
            }
        }
    }
    state.clear?.let { kind -> item(key = "listening:clear-confirmation") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LibraryText(stringResource(if (kind == ListeningClear.HISTORY) R.string.library_clear_history_confirmation
                else R.string.library_clear_statistics_confirmation), primary = true)
            LibraryButton(stringResource(R.string.library_confirm_clear), !state.loading, model::confirmClear)
            LibraryButton(stringResource(R.string.probe_cancel), !state.loading, model::cancelClear)
        }
    } }
    if (screen == LibraryScreen.HISTORY) {
        val history = state.history
        if (history?.items?.isEmpty() == true && !state.loading) item { LibraryText(stringResource(R.string.library_history_empty)) }
        items(history?.items.orEmpty(), key = { "history:${it.historyId}" }) { entry ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LibraryText(entry.displayName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.probe_unnamed_audio), primary = true)
                LibraryText(historyDate(entry.firstProgressEpochMs, entry.firstProgressZoneId))
                LibraryText(stringResource(R.string.library_history_listening, formatDuration(entry.listeningMs)))
                LibraryButton(stringResource(R.string.library_open_history_track), !state.loading) { openTrack(entry.trackKey) }
            }
        }
        item { LibraryButton(stringResource(R.string.library_next_page), !state.loading && history?.nextCursor != null, model::nextHistory) }
    } else {
        state.today?.let { today -> item(key = "listening:today") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LibraryText(pluralStringResource(R.plurals.library_today_listening, libraryPluralQuantity(today.playCount),
                    today.playCount, formatDuration(today.listeningMs)), primary = true)
                LibraryText(stringResource(R.string.library_today_scope, today.localDate, today.currentZoneId))
            }
        } }
        item { LibraryText(stringResource(R.string.library_track_statistics), primary = true) }
        if (state.tracks?.items?.isEmpty() == true && !state.loading) item { LibraryText(stringResource(R.string.library_statistics_empty)) }
        items(state.tracks?.items.orEmpty(), key = { "stat-track:${it.trackKey.providerId}:${it.trackKey.trackId}" }) { entry ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LibraryText(entry.displayName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.probe_unnamed_audio), primary = true)
                LibraryText(stringResource(R.string.library_listening_value, entry.playCount, formatDuration(entry.listeningMs)))
                LibraryButton(stringResource(R.string.library_open_history_track), !state.loading) { openTrack(entry.trackKey) }
            }
        }
        item { LibraryButton(stringResource(R.string.library_next_track_statistics), !state.loading && state.tracks?.nextCursor != null, model::nextTracks) }
        item { LibraryText(stringResource(R.string.library_day_statistics), primary = true) }
        items(state.days?.items.orEmpty(), key = { "stat-day:${it.localDate}:${it.zoneId}" }) { entry ->
            LibraryText(stringResource(R.string.library_day_statistics_value, entry.localDate, entry.zoneId,
                entry.playCount, formatDuration(entry.listeningMs)))
        }
        item { LibraryButton(stringResource(R.string.library_next_day_statistics), !state.loading && state.days?.nextCursor != null, model::nextDays) }
    }
}

internal fun listeningIssueText(issue: ListeningIssue): Int = when (issue) {
    ListeningIssue.INVALID_INPUT -> R.string.library_invalid_input
    ListeningIssue.CONFLICT -> R.string.library_listening_clear_conflict
    ListeningIssue.SOURCE_CHANGED -> R.string.library_source_changed
    ListeningIssue.STORAGE_FULL -> R.string.library_listening_storage_full
    ListeningIssue.STORAGE_UNAVAILABLE -> R.string.library_listening_storage_unavailable
    ListeningIssue.RECOVERY_REQUIRED -> R.string.library_recovery_required
}

private fun historyDate(epochMs: Long, zone: String): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
    .withZone(ZoneId.of(zone)).format(Instant.ofEpochMilli(epochMs))
