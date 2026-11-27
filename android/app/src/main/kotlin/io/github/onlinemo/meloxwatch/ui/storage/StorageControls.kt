package io.github.onlinemo.meloxwatch.ui.storage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.data.storage.StorageCategory
import io.github.onlinemo.meloxwatch.data.storage.StorageIssue
import io.github.onlinemo.meloxwatch.ui.watch.WatchActionRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchText
import io.github.onlinemo.meloxwatch.ui.watch.rememberWatchViewModel
import java.util.Locale

@Composable
fun StorageControls(model: StorageViewModel = rememberWatchViewModel(StorageViewModel::class.java)) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(model, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.onHidden() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer)
            model.onHidden() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WatchText(stringResource(R.string.storage_title), primary = true)
        WatchText(stringResource(R.string.storage_reference_scope))
        WatchActionRow(stringResource(R.string.storage_measure), !state.working, onClick = model::refresh)
        if (state.working) {
            WatchText(stringResource(if (state.stopping) R.string.library_stopping else R.string.library_reading))
            WatchActionRow(stringResource(R.string.probe_cancel), !state.stopping, onClick = model::cancel)
        }
        state.issue?.let { WatchText(stringResource(storageIssueText(it)), error = true) }
        val report = state.report
        if (report == null) WatchText(stringResource(R.string.storage_unmeasured)) else {
            Category(stringResource(R.string.storage_originals), report.originalReferences)
            Category(stringResource(R.string.storage_copies), report.audioCopies)
            Category(stringResource(R.string.storage_lyrics_copies), report.lyricsCopies)
            Category(stringResource(R.string.storage_saved_artwork), report.savedArtwork)
            Category(stringResource(R.string.storage_fonts), report.fonts)
            Category(stringResource(R.string.storage_themes), report.themes)
            Category(stringResource(R.string.storage_backgrounds), report.backgrounds)
            Category(stringResource(R.string.storage_artwork), report.artworkCache)
            Category(stringResource(R.string.storage_lyrics), report.lyricsCache)
            Category(stringResource(R.string.storage_recovery), report.recoveryStaging)
            WatchText(stringResource(R.string.storage_available, bytes(report.availableBytes)))
        }
        state.clear?.let { cleared ->
            WatchText(stringResource(R.string.storage_clear_observed), primary = true)
            WatchText(stringResource(R.string.storage_clear_category, stringResource(R.string.storage_artwork),
                observedBytes(cleared.artworkBefore), observedBytes(cleared.artworkAfter)))
            WatchText(stringResource(R.string.storage_clear_category, stringResource(R.string.storage_lyrics),
                observedBytes(cleared.lyricsBefore), observedBytes(cleared.lyricsAfter)))
            WatchText(stringResource(R.string.storage_available_change, bytes(cleared.availableBefore), bytes(cleared.availableAfter)))
            cleared.issue?.let { WatchText(stringResource(storageIssueText(it)), error = true) }
        }
        WatchActionRow(stringResource(R.string.storage_clear), !state.working, onClick = model::requestClear)
        if (state.confirmClear) {
            WatchText(stringResource(R.string.storage_clear_scope))
            WatchActionRow(stringResource(R.string.storage_clear_confirm), !state.working, onClick = model::confirmClear)
            WatchActionRow(stringResource(R.string.probe_cancel), !state.working, onClick = model::cancelClear)
        }
        WatchText(stringResource(R.string.storage_copy_delete_scope))
    }
}

@Composable
private fun Category(name: String, category: StorageCategory) {
    WatchText(stringResource(R.string.storage_category, name, observedBytes(category), category.files))
    if (category.unknownFiles > 0L) {
        // The plural selector uses Int; the displayed count retains the full Long subtotal.
        val quantity = category.unknownFiles.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        WatchText(pluralStringResource(R.plurals.storage_unknown_files, quantity, category.unknownFiles))
    }
    category.issue?.let { WatchText(stringResource(storageIssueText(it)), error = true) }
}

@Composable
private fun observedBytes(category: StorageCategory): String = if (category.complete) bytes(category.observedBytes)
    else category.observedBytes?.let { stringResource(R.string.storage_known_subtotal, bytes(it)) }
        ?: stringResource(R.string.library_unknown)

@Composable
private fun bytes(value: Long?): String = value?.let {
    if (it < 1024L) "$it B" else String.format(Locale.ROOT, "%.1f MiB", it.toDouble() / (1024.0 * 1024.0))
} ?: stringResource(R.string.library_unknown)

private fun storageIssueText(issue: StorageIssue): Int = when (issue) {
    StorageIssue.BUSY -> R.string.library_busy
    StorageIssue.PERMISSION_REVOKED -> R.string.library_permission_revoked
    StorageIssue.SOURCE_CHANGED -> R.string.library_source_changed
    StorageIssue.INCOMPLETE -> R.string.storage_incomplete
    StorageIssue.STORAGE_FULL -> R.string.library_storage_full
    StorageIssue.CANCELLED -> R.string.library_cancelled
    StorageIssue.UNAVAILABLE -> R.string.library_storage_unavailable
    StorageIssue.CLEANUP_REQUIRED -> R.string.storage_cleanup_required
}
