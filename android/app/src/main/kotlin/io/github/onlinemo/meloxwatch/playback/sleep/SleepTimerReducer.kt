package io.github.onlinemo.meloxwatch.playback.sleep

import java.util.UUID

/** Decisions only; scheduling, durable storage and the one playback session belong to the service. */
object SleepTimerReducer {
    fun arm(minutes: Int, endMode: SleepTimerEndMode, now: SleepTimerClockSample): SleepTimerTransition {
        require(minutes in 1..180)
        val durationMs = minutes * 60_000L
        require(now.elapsedMs <= Long.MAX_VALUE - durationMs)
        // Replacing state invalidates every old token, including one with an identical deadline.
        val token = SleepTimerToken(UUID.randomUUID().toString(), now.bootId, now.elapsedMs + durationMs)
        return SleepTimerTransition(SleepTimerState(active = ActiveSleepTimer(token, now.elapsedMs, minutes, endMode)))
    }

    fun cancel(state: SleepTimerState): SleepTimerTransition =
        cancelWithReason(state, SleepTimerStopReason.USER_CANCELLED)

    fun onUserPause(state: SleepTimerState): SleepTimerTransition =
        cancelWaitingMode(state, SleepTimerStopReason.USER_PAUSED)

    fun onManualTrackChange(state: SleepTimerState): SleepTimerTransition =
        cancelWaitingMode(state, SleepTimerStopReason.USER_CHANGED_TRACK)

    fun onDeadline(
        state: SleepTimerState,
        token: SleepTimerToken,
        now: SleepTimerClockSample,
        currentTrack: SleepTimerTrackBinding?,
    ): SleepTimerTransition {
        val active = state.active ?: return SleepTimerTransition(state)
        if (token != active.token) return SleepTimerTransition(state)
        invalidClock(active, now)?.let { return stop(active, it, pause = true) }
        if (now.elapsedMs < token.deadlineElapsedMs) return SleepTimerTransition(state)
        if (active.endMode == SleepTimerEndMode.PAUSE_IMMEDIATELY || currentTrack == null) {
            return stop(active, SleepTimerStopReason.DEADLINE_REACHED, pause = true)
        }
        if (active.waitingFor != null) {
            // Losing the bound traversal must not make an old timer pause a newly selected track.
            return if (active.waitingFor == currentTrack) SleepTimerTransition(state)
            else stop(active, SleepTimerStopReason.WAITING_TRACK_LOST)
        }
        return SleepTimerTransition(SleepTimerState(active = active.copy(waitingFor = currentTrack)))
    }

    /** Use a fresh owner-thread clock sample, and call this before repeat or natural advancement. */
    fun onNaturalEnd(
        state: SleepTimerState,
        now: SleepTimerClockSample,
        endingTrack: SleepTimerTrackBinding,
        currentTrack: SleepTimerTrackBinding?,
    ): SleepTimerTransition {
        val active = state.active ?: return SleepTimerTransition(state)
        if (endingTrack != currentTrack) return SleepTimerTransition(state)
        invalidClock(active, now)?.let { return stop(active, it, pause = true, blockAdvance = true) }
        if (active.waitingFor != null && active.waitingFor != endingTrack) {
            return stop(active, SleepTimerStopReason.WAITING_TRACK_LOST)
        }
        if (now.elapsedMs < active.token.deadlineElapsedMs) return SleepTimerTransition(state)
        val reason = if (active.endMode == SleepTimerEndMode.AFTER_CURRENT_TRACK)
            SleepTimerStopReason.CURRENT_TRACK_ENDED else SleepTimerStopReason.DEADLINE_REACHED
        // This covers natural-end arriving before a delayed deadline task, at the exact cutoff too.
        return stop(active, reason, pause = true, blockAdvance = true)
    }

    /** Recovery never issues play, even when the verified deadline is still in the future. */
    fun restore(state: SleepTimerState, now: SleepTimerClockSample): SleepTimerTransition {
        val active = state.active ?: return SleepTimerTransition(state, pauseRequired = true)
        if (active.token.bootId == null || now.bootId == null) {
            return stop(active, SleepTimerStopReason.BOOT_UNVERIFIED, pause = true)
        }
        invalidClock(active, now)?.let { return stop(active, it, pause = true) }
        if (now.elapsedMs >= active.token.deadlineElapsedMs || active.waitingFor != null) {
            return stop(active, SleepTimerStopReason.RECOVERED_EXPIRED, pause = true)
        }
        return SleepTimerTransition(state, pauseRequired = true)
    }

    private fun invalidClock(active: ActiveSleepTimer, now: SleepTimerClockSample): SleepTimerStopReason? {
        if (active.token.bootId != null && active.token.bootId != now.bootId) {
            return if (now.bootId == null) SleepTimerStopReason.BOOT_UNVERIFIED else SleepTimerStopReason.DEVICE_RESTARTED
        }
        return if (now.elapsedMs < active.startedElapsedMs || (active.waitingFor != null && now.elapsedMs < active.token.deadlineElapsedMs))
            SleepTimerStopReason.INVALID_MONOTONIC_CLOCK else null
    }

    private fun cancelWaitingMode(state: SleepTimerState, reason: SleepTimerStopReason): SleepTimerTransition =
        if (state.active?.endMode == SleepTimerEndMode.AFTER_CURRENT_TRACK) cancelWithReason(state, reason)
        else SleepTimerTransition(state)

    private fun cancelWithReason(state: SleepTimerState, reason: SleepTimerStopReason): SleepTimerTransition =
        state.active?.let { stop(it, reason) } ?: SleepTimerTransition(state)

    private fun stop(
        active: ActiveSleepTimer,
        reason: SleepTimerStopReason,
        pause: Boolean = false,
        blockAdvance: Boolean = false,
    ) = SleepTimerTransition(SleepTimerState(lastStop = SleepTimerStop(active.token, reason)), pause, blockAdvance)
}
