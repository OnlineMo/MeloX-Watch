package io.github.onlinemo.meloxwatch.playback

import android.os.Bundle
import io.github.onlinemo.meloxwatch.model.PlaybackBackendKind
import io.github.onlinemo.meloxwatch.model.PlaybackPreferenceLimits

enum class PlaybackBackendIssue {
    FOCUS_DENIED,
    FOREGROUND_DENIED,
    SOURCE_UNAVAILABLE,
    SOURCE_NOT_SEEKABLE,
    PREPARATION_FAILED,
    SEEK_FAILED,
    SPEED_UNSUPPORTED,
    SWITCH_FAILED,
    CLOSED,
}

/** A saved request and an observed speed are different, especially while platform playback is paused. */
data class PlaybackSpeedState(
    val requestedTenths: Int = PlaybackPreferenceLimits.DEFAULT_SPEED_TENTHS,
    val actualSpeed: Float = 1f,
    val actualPitch: Float = 1f,
    val pending: Boolean = false,
    val issue: PlaybackBackendIssue? = null,
) {
    init {
        require(requestedTenths in PlaybackPreferenceLimits.MIN_SPEED_TENTHS..PlaybackPreferenceLimits.MAX_SPEED_TENTHS)
        require(actualSpeed.isFinite() && actualSpeed > 0f)
        require(actualPitch.isFinite() && actualPitch > 0f)
    }
}

/** Only observed configuration is exported. No URI, title, provider or device identifier is included. */
data class PlaybackBackendState(
    val requestedBackend: PlaybackBackendKind = PlaybackBackendKind.MEDIA3,
    val actualBackend: PlaybackBackendKind? = null,
    val generation: Long = 0L,
    val switching: Boolean = false,
    val speed: PlaybackSpeedState = PlaybackSpeedState(),
    val gaplessRequested: Boolean = true,
    val gaplessAvailable: Boolean = false,
    val issue: PlaybackBackendIssue? = null,
    val gaplessIssue: GaplessPlaybackIssue? = GaplessPlaybackIssue.PREPARING,
) {
    fun toSessionExtras(): Bundle = Bundle().apply {
        putString(REQUESTED_BACKEND, requestedBackend.name)
        actualBackend?.let { putString(ACTUAL_BACKEND, it.name) }
        putLong(GENERATION, generation)
        putBoolean(SWITCHING, switching)
        putInt(REQUESTED_SPEED, speed.requestedTenths)
        putFloat(ACTUAL_SPEED, speed.actualSpeed)
        putFloat(ACTUAL_PITCH, speed.actualPitch)
        putBoolean(SPEED_PENDING, speed.pending)
        speed.issue?.let { putString(SPEED_ISSUE, it.name) }
        putBoolean(GAPLESS_REQUESTED, gaplessRequested)
        putBoolean(GAPLESS_AVAILABLE, gaplessAvailable)
        gaplessIssue?.let { putString(GAPLESS_ISSUE, it.name) }
        issue?.let { putString(ISSUE, it.name) }
    }

    companion object {
        private const val PREFIX = "io.github.onlinemo.meloxwatch.backend."
        const val REQUESTED_BACKEND = PREFIX + "requested"
        const val ACTUAL_BACKEND = PREFIX + "actual"
        const val GENERATION = PREFIX + "generation"
        const val SWITCHING = PREFIX + "switching"
        const val REQUESTED_SPEED = PREFIX + "speedRequested"
        const val ACTUAL_SPEED = PREFIX + "speedActual"
        const val ACTUAL_PITCH = PREFIX + "pitchActual"
        const val SPEED_PENDING = PREFIX + "speedPending"
        const val SPEED_ISSUE = PREFIX + "speedIssue"
        const val GAPLESS_REQUESTED = PREFIX + "gaplessRequested"
        const val GAPLESS_AVAILABLE = PREFIX + "gaplessAvailable"
        const val GAPLESS_ISSUE = PREFIX + "gaplessIssue"
        const val ISSUE = PREFIX + "issue"

        fun fromSessionExtras(extras: Bundle): PlaybackBackendState? {
            return try {
                PlaybackBackendState(
                    requestedBackend = PlaybackBackendKind.valueOf(extras.value(REQUESTED_BACKEND)),
                    actualBackend = extras.optionalText(ACTUAL_BACKEND)?.let(PlaybackBackendKind::valueOf),
                    generation = extras.value<Long>(GENERATION).also { require(it >= 0L) },
                    switching = extras.value(SWITCHING),
                    speed = PlaybackSpeedState(
                        requestedTenths = extras.value(REQUESTED_SPEED),
                        actualSpeed = extras.value(ACTUAL_SPEED),
                        actualPitch = extras.value(ACTUAL_PITCH),
                        pending = extras.value(SPEED_PENDING),
                        issue = extras.optionalText(SPEED_ISSUE)?.let(PlaybackBackendIssue::valueOf),
                    ),
                    gaplessRequested = extras.value(GAPLESS_REQUESTED),
                    gaplessAvailable = extras.value(GAPLESS_AVAILABLE),
                    gaplessIssue = extras.optionalText(GAPLESS_ISSUE)?.let(GaplessPlaybackIssue::valueOf),
                    issue = extras.optionalText(ISSUE)?.let(PlaybackBackendIssue::valueOf),
                )
            } catch (_: RuntimeException) {
                null
            }
        }

        private inline fun <reified T> Bundle.value(key: String): T =
            rawValue(key) as? T ?: throw IllegalArgumentException("Invalid playback backend state")

        private fun Bundle.optionalText(key: String): String? {
            val item = rawValue(key) ?: return null
            require(item is String)
            return item
        }

        // Default-valued Bundle getters would turn missing or malformed observations into 1x.
        @Suppress("DEPRECATION")
        private fun Bundle.rawValue(key: String): Any? = get(key)
    }
}
