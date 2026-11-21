package io.github.onlinemo.meloxwatch.ui.online

import androidx.media3.common.util.UnstableApi
import androidx.media3.session.SessionResult
import io.github.onlinemo.meloxwatch.model.OnlineCatalogIssue
import io.github.onlinemo.meloxwatch.playback.PlaybackCommands
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue

internal data class OnlineQueueSubmission(
    val accepted: Boolean = false,
    val catalogIssue: OnlineCatalogIssue? = null,
    val playbackIssue: QueuePlaybackIssue? = null,
)

/** Only the matching operation receipt acknowledges publication; decoder failures remain service state. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun onlineQueueSubmission(result: SessionResult, operationId: String): OnlineQueueSubmission {
    try {
        if (PlaybackCommands.readReceiptOperation(result.extras) != operationId) {
            return OnlineQueueSubmission(catalogIssue = OnlineCatalogIssue.STALE_VIEW)
        }
    } catch (_: RuntimeException) { return OnlineQueueSubmission(catalogIssue = OnlineCatalogIssue.INVALID_RESPONSE) }
    if (result.resultCode == SessionResult.RESULT_SUCCESS) return OnlineQueueSubmission(accepted = true)
    val code = PlaybackCommands.readFailureCode(result.extras)
    val catalog = OnlineCatalogIssue.entries.firstOrNull { it.name == code }
    if (catalog != null) return OnlineQueueSubmission(catalogIssue = catalog)
    val playback = QueuePlaybackIssue.entries.firstOrNull { it.name == code }
    return OnlineQueueSubmission(playbackIssue = playback ?: QueuePlaybackIssue.SOURCE_UNAVAILABLE)
}
