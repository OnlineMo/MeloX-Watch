package io.github.onlinemo.meloxwatch.ui

import io.github.onlinemo.meloxwatch.MeloXApplication
import io.github.onlinemo.meloxwatch.data.meting.MetingCredentialEdit
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.ui.meting.MetingPlaybackObservation
import io.github.onlinemo.meloxwatch.ui.meting.MetingSettingsEnvironment
import io.github.onlinemo.meloxwatch.ui.meting.MetingSettingsState
import io.github.onlinemo.meloxwatch.ui.meting.MetingSettingsViewModel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Tests use the existing controller and durable current item; browsing selections never become playback. */
internal fun createMetingSettingsModel(app: MeloXApplication, playback: ProbeViewModel): MetingSettingsViewModel =
    MetingSettingsViewModel(app.metingRuntime, app.runtimePlaybackResources,
        MetingSettingsEnvironment(
            playback = playback.uiState.map { state ->
                val current = state.playback
                MetingPlaybackObservation(current.isConnected, current.hasMedia, current.mediaId,
                    current.isPlaying, current.playbackState, current.hasError)
            }.distinctUntilChanged(),
            currentSelection = { currentMetingSelection(app) },
            requestCurrentPlayback = playback::requestCurrentPlayback,
        ))

private suspend fun currentMetingSelection(app: MeloXApplication): QueueEntry? {
    val snapshot = app.queues.snapshot() ?: return null
    val entryId = snapshot.currentEntryId ?: return null
    val entry = app.queues.window(snapshot.generationId, entryId).current
    currentCoroutineContext().ensureActive()
    val latest = app.queues.snapshot()
    return entry.takeIf { latest?.generationId == snapshot.generationId &&
        latest.queueRevision == snapshot.queueRevision && latest.currentEntryId == entryId }
}

/** Masked fields contribute their actual bullet glyph, never endpoint or credential plaintext. */
internal fun metingVisibleFontText(state: MetingSettingsState): List<String> = buildList {
    state.saved.hostLabel?.let(::add)
    val draft = state.draft
    if (draft == null) add(state.testTrackId) else {
        if (draft.endpoint.isNotEmpty()) add("•")
        if (draft.credentialEdit == MetingCredentialEdit.REPLACE) {
            add(draft.headerName)
            if (draft.credentialInput.isNotEmpty()) add("•")
        }
    }
}
