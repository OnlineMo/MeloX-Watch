package io.github.onlinemo.meloxwatch.playback.resources

import android.os.Bundle
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue

/** A Media3 wrapper can add causes; only our stable transport issue is projected to service/UI state. */
internal fun mediaTransportIssue(error: Throwable): QueuePlaybackIssue? {
    var current: Throwable? = error
    repeat(8) {
        val inspected = current ?: return null
        if (inspected is MediaTransportException) return inspected.issue
        if (inspected.message?.startsWith("RESOURCE_") == true) {
            val name = inspected.message?.removePrefix("RESOURCE_")
            QueuePlaybackIssue.entries.firstOrNull { it.name == name && it in MEDIA_ISSUES }?.let { return it }
        }
        current = inspected.cause?.takeUnless { it === inspected }
    }
    return null
}

fun PlaybackResourceStatus.toSessionExtras(): Bundle = Bundle().apply {
    putString("melox.resource.resolver", resolverId)
    putString("melox.resource.match", match.name)
    putString("melox.resource.completeness", completeness.name)
    putString("melox.resource.quality.requested", requestedQuality.name)
    reportedQuality?.let { putString("melox.resource.quality.reported", it.name) }
    reportedBitrate?.let { putLong("melox.resource.bitrate.reported", it) }
}

private val MEDIA_ISSUES = setOf(
    QueuePlaybackIssue.BUSY, QueuePlaybackIssue.CLOSED, QueuePlaybackIssue.SOURCE_CHANGED,
    QueuePlaybackIssue.LOGIN_REQUIRED, QueuePlaybackIssue.RESOURCE_AUTH_REQUIRED,
    QueuePlaybackIssue.RESOURCE_INVALID, QueuePlaybackIssue.RESOURCE_EXPIRED,
    QueuePlaybackIssue.RESOURCE_UNAVAILABLE, QueuePlaybackIssue.RESOURCE_REDIRECT,
    QueuePlaybackIssue.NETWORK_UNAVAILABLE, QueuePlaybackIssue.NETWORK_CERTIFICATE,
    QueuePlaybackIssue.NETWORK_TIMEOUT, QueuePlaybackIssue.NETWORK_RATE_LIMITED,
)
