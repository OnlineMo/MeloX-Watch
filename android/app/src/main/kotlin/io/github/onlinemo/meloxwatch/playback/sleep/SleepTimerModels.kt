package io.github.onlinemo.meloxwatch.playback.sleep

import java.util.UUID

/** A missing boot ID is usable only within this process, never as proof for recovery. */
data class SleepTimerClockSample(val bootId: String?, val elapsedMs: Long) {
    init {
        require(bootId == null || (bootId.isNotBlank() && bootId.length <= 128))
        require(elapsedMs >= 0L)
    }
}

enum class SleepTimerEndMode { PAUSE_IMMEDIATELY, AFTER_CURRENT_TRACK }

data class SleepTimerToken(val generation: String, val bootId: String?, val deadlineElapsedMs: Long) {
    init {
        require(UUID.fromString(generation).toString() == generation)
        require(bootId == null || (bootId.isNotBlank() && bootId.length <= 128))
        require(deadlineElapsedMs >= 0L)
    }
}

/** Backend callbacks must already be checked by the service against its active backend generation. */
data class SleepTimerTrackBinding(
    val sessionId: String,
    val queueGenerationId: String,
    val queueEntryId: String,
    val playbackCycle: Long,
) {
    init {
        require(sessionId.isNotBlank() && queueGenerationId.isNotBlank() && queueEntryId.isNotBlank())
        require(playbackCycle >= 0L)
    }
}

data class ActiveSleepTimer(
    val token: SleepTimerToken,
    val startedElapsedMs: Long,
    val minutes: Int,
    val endMode: SleepTimerEndMode,
    val waitingFor: SleepTimerTrackBinding? = null,
) {
    init {
        require(minutes in 1..180 && startedElapsedMs >= 0L)
        require(token.deadlineElapsedMs >= startedElapsedMs)
        require(token.deadlineElapsedMs - startedElapsedMs == minutes * 60_000L)
        require(waitingFor == null || endMode == SleepTimerEndMode.AFTER_CURRENT_TRACK)
    }
}

enum class SleepTimerStopReason {
    USER_CANCELLED, USER_PAUSED, USER_CHANGED_TRACK, DEADLINE_REACHED, CURRENT_TRACK_ENDED,
    WAITING_TRACK_LOST, DEVICE_RESTARTED, BOOT_UNVERIFIED, INVALID_MONOTONIC_CLOCK, RECOVERED_EXPIRED,
}

data class SleepTimerStop(val token: SleepTimerToken, val reason: SleepTimerStopReason)

data class SleepTimerState(val active: ActiveSleepTimer? = null, val lastStop: SleepTimerStop? = null) {
    init { require(active == null || lastStop == null) }
}

/** The sole service applies pause before processing another command or any natural queue advance. */
data class SleepTimerTransition(
    val state: SleepTimerState,
    val pauseRequired: Boolean = false,
    val blockNaturalAdvance: Boolean = false,
) {
    init { require(!blockNaturalAdvance || pauseRequired) }
}
