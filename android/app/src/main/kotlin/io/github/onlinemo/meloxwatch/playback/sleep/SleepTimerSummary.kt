package io.github.onlinemo.meloxwatch.playback.sleep

data class SleepTimerSummary(
    val token: SleepTimerToken,
    val remainingMinutes: Int,
    val endMode: SleepTimerEndMode,
    val nextUpdateElapsedMs: Long,
)

/** Null means remove the summary and its pending update; it never cancels the timer itself. */
fun sleepTimerSummary(
    state: SleepTimerState,
    now: SleepTimerClockSample,
    enabled: Boolean = false,
    notificationAllowed: Boolean = true,
): SleepTimerSummary? {
    if (!enabled || !notificationAllowed) return null
    val active = state.active ?: return null
    val token = active.token
    if (token.bootId != null && now.bootId != token.bootId) return null
    if (active.waitingFor != null || now.elapsedMs < active.startedElapsedMs || now.elapsedMs >= token.deadlineElapsedMs) return null
    val remainingMs = token.deadlineElapsedMs - now.elapsedMs
    val wholeMinutes = remainingMs / 60_000L
    val remainder = remainingMs % 60_000L
    val displayedMinutes = (wholeMinutes + if (remainder == 0L) 0L else 1L).toInt()
    // Bucket boundaries are tied to the same deadline; a hidden UI need not tick every second.
    val untilNextChangeMs = if (remainder == 0L) 60_000L else remainder
    return SleepTimerSummary(token, displayedMinutes, active.endMode, now.elapsedMs + untilNextChangeMs)
}
