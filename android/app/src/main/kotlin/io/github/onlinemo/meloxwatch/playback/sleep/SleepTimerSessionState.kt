package io.github.onlinemo.meloxwatch.playback.sleep

import android.os.Bundle
import io.github.onlinemo.meloxwatch.data.sleep.SleepTimerStoreIssue
import io.github.onlinemo.meloxwatch.data.sleep.SleepTimerStoreRecovery
import java.util.UUID

/** 会话只传管理定时所需状态；启动标识及等待曲目的完整身份留在服务内部。 */
data class SleepTimerSessionState(
    val initialized: Boolean = false,
    val generationId: String? = null,
    val active: Boolean = false,
    val minutes: Int? = null,
    val deadlineElapsedMs: Long? = null,
    val endMode: SleepTimerEndMode? = null,
    val waitingForTrackEnd: Boolean = false,
    val stopReason: SleepTimerStopReason? = null,
    val persistence: SleepTimerPersistence = SleepTimerPersistence.LOADING,
    val issue: SleepTimerStoreIssue? = null,
    val conflict: Boolean = false,
    val recovery: SleepTimerStoreRecovery? = null,
    val summaryEnabled: Boolean = false,
    val notificationAllowed: Boolean = true,
) {
    init {
        require(generationId == null || UUID.fromString(generationId).toString() == generationId)
        require(!active || generationId != null)
        require(if (active) minutes != null && minutes in 1..180 && deadlineElapsedMs != null && deadlineElapsedMs >= 0L && endMode != null
            else minutes == null && deadlineElapsedMs == null && endMode == null && !waitingForTrackEnd)
        require(!active || stopReason == null)
        require(!waitingForTrackEnd || endMode == SleepTimerEndMode.AFTER_CURRENT_TRACK)
    }

    fun remainingMinutes(elapsedMs: Long): Int {
        val deadline = deadlineElapsedMs ?: return 0
        if (elapsedMs >= deadline) return 0
        val remaining = deadline - elapsedMs.coerceAtLeast(0L)
        return (remaining / 60_000L + if (remaining % 60_000L == 0L) 0L else 1L)
            .coerceIn(0L, (minutes ?: 180).toLong()).toInt()
    }

    internal fun toSessionExtras(): Bundle = Bundle().apply {
        putInt(PREFIX + "schema", 1)
        putBoolean(PREFIX + "initialized", initialized)
        putString(PREFIX + "generation", generationId)
        putBoolean(PREFIX + "active", active)
        minutes?.let { putInt(PREFIX + "minutes", it) }
        deadlineElapsedMs?.let { putLong(PREFIX + "deadline", it) }
        endMode?.let { putString(PREFIX + "mode", it.name) }
        putBoolean(PREFIX + "waiting", waitingForTrackEnd)
        stopReason?.let { putString(PREFIX + "stop", it.name) }
        putString(PREFIX + "persistence", persistence.name)
        issue?.let { putString(PREFIX + "issue", it.name) }
        putBoolean(PREFIX + "conflict", conflict)
        recovery?.let { putString(PREFIX + "recovery", it.name) }
        putBoolean(PREFIX + "summary", summaryEnabled)
        putBoolean(PREFIX + "notificationAllowed", notificationAllowed)
    }

    companion object {
        private const val PREFIX = "io.github.onlinemo.meloxwatch.sleep."

        internal fun fromService(state: ServiceSleepTimerState): SleepTimerSessionState {
            val active = state.timer.active
            val stopped = state.timer.lastStop
            return SleepTimerSessionState(state.initialized,
                active?.token?.generation ?: stopped?.token?.generation, active != null, active?.minutes,
                active?.token?.deadlineElapsedMs, active?.endMode, active?.waitingFor != null, stopped?.reason,
                state.persistence, state.issue, state.conflict, state.recovery, state.summaryEnabled, state.notificationAllowed)
        }

        fun fromSessionExtras(extras: Bundle): SleepTimerSessionState? = try {
            require(extras.value<Int>("schema") == 1)
            SleepTimerSessionState(
                initialized = extras.value("initialized"),
                generationId = extras.optional<String>("generation"),
                active = extras.value("active"),
                minutes = extras.optional("minutes"),
                deadlineElapsedMs = extras.optional("deadline"),
                endMode = extras.optional<String>("mode")?.let(SleepTimerEndMode::valueOf),
                waitingForTrackEnd = extras.value("waiting"),
                stopReason = extras.optional<String>("stop")?.let(SleepTimerStopReason::valueOf),
                persistence = SleepTimerPersistence.valueOf(extras.value("persistence")),
                issue = extras.optional<String>("issue")?.let(SleepTimerStoreIssue::valueOf),
                conflict = extras.value("conflict"),
                recovery = extras.optional<String>("recovery")?.let(SleepTimerStoreRecovery::valueOf),
                summaryEnabled = extras.value("summary"),
                notificationAllowed = extras.value("notificationAllowed"),
            )
        } catch (_: RuntimeException) {
            null
        }

        private inline fun <reified T> Bundle.value(key: String): T = rawValue(PREFIX + key) as? T
            ?: throw IllegalArgumentException("Invalid sleep timer observation")

        private inline fun <reified T> Bundle.optional(key: String): T? {
            val item = rawValue(PREFIX + key) ?: return null
            require(item is T)
            return item
        }

        // 类型核验不能把缺失/错误字段当作合法零值；抑制仅限兼容的raw读取。
        @Suppress("DEPRECATION")
        private fun Bundle.rawValue(key: String): Any? = get(key)
    }
}
