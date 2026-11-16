package io.github.onlinemo.meloxwatch.ui.playback

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesFailure
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesState
import io.github.onlinemo.meloxwatch.data.sleep.SleepTimerStoreIssue
import io.github.onlinemo.meloxwatch.playback.sleep.SleepTimerEndMode
import io.github.onlinemo.meloxwatch.playback.sleep.SleepTimerPersistence
import io.github.onlinemo.meloxwatch.playback.sleep.SleepTimerStopReason
import io.github.onlinemo.meloxwatch.ui.ProbeUiState
import io.github.onlinemo.meloxwatch.ui.ProbeViewModel
import io.github.onlinemo.meloxwatch.ui.SleepTimerCommandIssue
import io.github.onlinemo.meloxwatch.ui.preferences.PreferencesEditFeedback
import io.github.onlinemo.meloxwatch.ui.preferences.PreferencesViewModel
import io.github.onlinemo.meloxwatch.ui.watch.WatchActionRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchChoiceRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchSwitchRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchText

/** The host supplies the scrollable, round-safe viewport. Drafts never start a timer on recreation. */
@Composable
fun SleepTimerControls(state: ProbeUiState, model: ProbeViewModel, onNotifications: () -> Unit) {
    val timer = state.sleepTimer
    var editing by rememberSaveable { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    var minutes by rememberSaveable { mutableStateOf(30) }
    var modeName by rememberSaveable { mutableStateOf(SleepTimerEndMode.PAUSE_IMMEDIATELY.name) }
    var expectedGeneration by rememberSaveable { mutableStateOf<String?>(null) }
    val mode = SleepTimerEndMode.entries.firstOrNull { it.name == modeName } ?: SleepTimerEndMode.PAUSE_IMMEDIATELY
    val connected = state.playback.isConnected && timer?.initialized == true
    val editable = connected && !state.sleepTimerCommandPending && timer?.issue == null && timer?.conflict == false
    val draftCurrent = timer != null && expectedGeneration == timer.generationId
    BackHandler(enabled = editing) {
        if (!state.sleepTimerCommandPending) {
            if (confirming) confirming = false else editing = false
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!connected) {
            WatchText(stringResource(R.string.sleep_timer_loading))
            WatchActionRow(stringResource(R.string.probe_reconnect), onClick = model::reconnectPlayback)
        } else if (timer != null) {
            if (timer.active) {
                WatchText(if (timer.waitingForTrackEnd) stringResource(R.string.sleep_timer_waiting_for_end)
                    else pluralStringResource(R.plurals.sleep_timer_minutes_remaining,
                        timer.remainingMinutes(state.sleepTimerElapsedMs), timer.remainingMinutes(state.sleepTimerElapsedMs)), primary = true)
                WatchText(stringResource(sleepTimerModeText(requireNotNull(timer.endMode))))
                // A failed/pending disk save cannot delay cancellation of the runtime timer.
                WatchActionRow(stringResource(R.string.sleep_timer_cancel), onClick = {
                    model.cancelSleepTimer(timer.generationId)
                })
            } else {
                WatchText(stringResource(timer.stopReason?.let(::sleepTimerStopText) ?: R.string.sleep_timer_off), primary = true)
            }
            when (timer.persistence) {
                SleepTimerPersistence.LOADING -> WatchText(stringResource(R.string.sleep_timer_loading))
                SleepTimerPersistence.SAVING -> WatchText(stringResource(R.string.sleep_timer_saving))
                SleepTimerPersistence.SAVED -> Unit
                SleepTimerPersistence.FAILED -> {
                    WatchText(stringResource(sleepTimerSaveIssueText(timer.issue)), error = true)
                    if (timer.issue != SleepTimerStoreIssue.RETIRED_OWNER && timer.issue != SleepTimerStoreIssue.UNSUPPORTED_SCHEMA) {
                        WatchActionRow(stringResource(R.string.sleep_timer_retry_save), !state.sleepTimerCommandPending) {
                            model.retrySleepTimerPersistence(timer.generationId)
                        }
                    }
                }
            }
            if (timer.conflict) WatchText(stringResource(R.string.sleep_timer_disk_conflict), error = true)
            if (timer.recovery != null) WatchText(stringResource(R.string.sleep_timer_recovered_settings))
            if (timer.summaryEnabled && !timer.notificationAllowed) WatchText(stringResource(R.string.sleep_timer_notification_unavailable))
        }
        state.sleepTimerCommandIssue?.let { WatchText(stringResource(sleepTimerCommandIssueText(it)), error = true) }
        if (state.sleepTimerCommandPending) WatchText(stringResource(R.string.sleep_timer_command_pending))
        if (!editing) {
            WatchActionRow(stringResource(if (timer?.active == true) R.string.sleep_timer_replace else R.string.sleep_timer_set), editable) {
                minutes = timer?.minutes ?: 30
                modeName = (timer?.endMode ?: SleepTimerEndMode.PAUSE_IMMEDIATELY).name
                expectedGeneration = timer?.generationId
                confirming = false
                editing = true
            }
        } else {
            WatchText(stringResource(R.string.sleep_timer_draft), primary = true)
            if (!draftCurrent) {
                WatchText(stringResource(R.string.sleep_timer_changed_while_editing), error = true)
                WatchActionRow(stringResource(R.string.sleep_timer_reload_draft), editable) {
                    expectedGeneration = timer?.generationId
                    confirming = false
                }
            }
            WatchText(pluralStringResource(R.plurals.sleep_timer_duration, minutes.coerceIn(1, 180), minutes.coerceIn(1, 180)), primary = true)
            val canChange = editable && !confirming
            WatchActionRow(stringResource(R.string.sleep_timer_one_minute_less), canChange && minutes > 1) { minutes -= 1 }
            WatchActionRow(stringResource(R.string.sleep_timer_one_minute_more), canChange && minutes < 180) { minutes += 1 }
            Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SleepTimerEndMode.entries.forEach { choice ->
                    WatchChoiceRow(stringResource(sleepTimerModeText(choice)), choice == mode, canChange) { modeName = choice.name }
                }
            }
            WatchText(stringResource(R.string.sleep_timer_waiting_scope))
            if (confirming) {
                WatchText(stringResource(if (timer?.active == true) R.string.sleep_timer_replace_confirm else R.string.sleep_timer_enable_confirm), primary = true)
                WatchActionRow(stringResource(R.string.sleep_timer_enable), editable && draftCurrent && minutes in 1..180, primary = true) {
                    model.armSleepTimer(minutes, mode, expectedGeneration) {
                        editing = false
                        confirming = false
                    }
                }
                WatchActionRow(stringResource(R.string.probe_cancel), !state.sleepTimerCommandPending) { confirming = false }
            } else {
                WatchActionRow(stringResource(R.string.sleep_timer_continue), editable && draftCurrent && minutes in 1..180) { confirming = true }
                WatchActionRow(stringResource(R.string.sleep_timer_discard_draft), !state.sleepTimerCommandPending) { editing = false }
            }
        }
        WatchActionRow(stringResource(R.string.prefs_sleep_summary), onClick = onNotifications)
        WatchText(stringResource(R.string.sleep_timer_restore_scope))
    }
}

/** A separate preference editor changes only the summary draft, through the shared revision/CAS store. */
@Composable
fun SleepTimerSummaryPreferences(model: PreferencesViewModel, onCancel: () -> Unit) {
    val state by model.uiState.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val valueHeading = when (val storage = state.storage) {
            is PreferencesState.Ready -> R.string.prefs_current_values
            is PreferencesState.Unavailable -> if (storage.lastKnown == null) R.string.prefs_temporary_values else R.string.prefs_last_known_values
            PreferencesState.Loading -> R.string.prefs_temporary_values
        }
        WatchText(stringResource(valueHeading), primary = true)
        WatchText(stringResource(R.string.prefs_sleep_summary_value,
            stringResource(if (state.effective.controls.sleepTimerSummary) R.string.prefs_on else R.string.prefs_off)))
        when (val storage = state.storage) {
            PreferencesState.Loading -> WatchText(stringResource(R.string.prefs_loading))
            is PreferencesState.Unavailable -> {
                WatchText(stringResource(R.string.sleep_summary_read_failed), error = true)
                WatchActionRow(stringResource(R.string.prefs_retry_read), !state.isSaving, onClick = model::retryRead)
            }
            is PreferencesState.Ready -> if (storage.recovery != null) WatchText(stringResource(R.string.sleep_summary_recovered))
        }
        if (state.hasConflict) {
            WatchText(stringResource(R.string.sleep_summary_conflict), error = true)
            WatchText(stringResource(R.string.prefs_reload_scope))
            WatchActionRow(stringResource(R.string.prefs_reload_current), state.canReload, onClick = model::reloadCurrentValues)
        }
        state.failure?.let { failure ->
            WatchText(stringResource(when (failure) {
                PreferencesFailure.IO -> R.string.sleep_summary_save_failed
                PreferencesFailure.UNSUPPORTED_SCHEMA -> R.string.prefs_write_unsupported
                PreferencesFailure.INVALID_REVISION -> R.string.prefs_invalid_edit
                PreferencesFailure.REVISION_EXHAUSTED -> R.string.prefs_revision_exhausted
            }), error = true)
            if (failure == PreferencesFailure.INVALID_REVISION && !state.hasConflict) {
                WatchActionRow(stringResource(R.string.prefs_reload_current), state.canReload, onClick = model::reloadCurrentValues)
            }
        }
        if (state.isSaving) WatchText(stringResource(R.string.prefs_saving))
        if (state.feedback in setOf(PreferencesEditFeedback.SAVED, PreferencesEditFeedback.UNCHANGED)) {
            WatchText(stringResource(R.string.sleep_summary_saved))
        }
        state.draft?.let { draft ->
            WatchText(stringResource(R.string.prefs_edit_values), primary = true)
            WatchSwitchRow(stringResource(R.string.prefs_sleep_summary), draft.controls.sleepTimerSummary, state.canEdit,
                model::setSleepTimerSummary)
            WatchActionRow(stringResource(R.string.sleep_summary_default), state.canEdit) { model.setSleepTimerSummary(false) }
            WatchText(stringResource(R.string.prefs_sleep_summary_scope))
            WatchActionRow(stringResource(if (state.failure == PreferencesFailure.IO) R.string.prefs_retry_save else R.string.prefs_save),
                state.canEdit, primary = true, onClick = model::saveDraft)
        }
        WatchText(stringResource(R.string.sleep_summary_save_scope))
        WatchActionRow(stringResource(R.string.prefs_cancel_edit), !state.isSaving) {
            model.clearDraft()
            onCancel()
        }
    }
}

private fun sleepTimerModeText(mode: SleepTimerEndMode): Int = when (mode) {
    SleepTimerEndMode.PAUSE_IMMEDIATELY -> R.string.sleep_timer_pause_immediately
    SleepTimerEndMode.AFTER_CURRENT_TRACK -> R.string.sleep_timer_after_current
}

private fun sleepTimerStopText(reason: SleepTimerStopReason): Int = when (reason) {
    SleepTimerStopReason.USER_CANCELLED -> R.string.sleep_timer_cancelled
    SleepTimerStopReason.USER_PAUSED -> R.string.sleep_timer_cancelled_by_pause
    SleepTimerStopReason.USER_CHANGED_TRACK -> R.string.sleep_timer_cancelled_by_track
    SleepTimerStopReason.DEADLINE_REACHED, SleepTimerStopReason.CURRENT_TRACK_ENDED -> R.string.sleep_timer_completed
    SleepTimerStopReason.WAITING_TRACK_LOST -> R.string.sleep_timer_bound_track_lost
    SleepTimerStopReason.DEVICE_RESTARTED, SleepTimerStopReason.BOOT_UNVERIFIED,
    SleepTimerStopReason.INVALID_MONOTONIC_CLOCK, SleepTimerStopReason.RECOVERED_EXPIRED -> R.string.sleep_timer_interrupted
}

private fun sleepTimerSaveIssueText(issue: SleepTimerStoreIssue?): Int = when (issue) {
    SleepTimerStoreIssue.RETIRED_OWNER -> R.string.sleep_timer_retired_owner
    SleepTimerStoreIssue.UNSUPPORTED_SCHEMA -> R.string.sleep_timer_newer_schema
    SleepTimerStoreIssue.REVISION_EXHAUSTED -> R.string.sleep_timer_revision_exhausted
    SleepTimerStoreIssue.INPUT_BOUND -> R.string.sleep_timer_binding_too_large
    SleepTimerStoreIssue.IO, null -> R.string.sleep_timer_save_failed
}

private fun sleepTimerCommandIssueText(issue: SleepTimerCommandIssue): Int = when (issue) {
    SleepTimerCommandIssue.CONNECTION -> R.string.sleep_timer_connection_failed
    SleepTimerCommandIssue.BUSY -> R.string.sleep_timer_busy
    SleepTimerCommandIssue.CONFLICT -> R.string.sleep_timer_changed_while_editing
    SleepTimerCommandIssue.INVALID_INPUT -> R.string.sleep_timer_invalid_input
    SleepTimerCommandIssue.FAILED -> R.string.sleep_timer_command_failed
}
