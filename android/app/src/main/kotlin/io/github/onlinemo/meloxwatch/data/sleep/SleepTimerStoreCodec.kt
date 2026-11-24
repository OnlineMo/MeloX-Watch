package io.github.onlinemo.meloxwatch.data.sleep

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.onlinemo.meloxwatch.playback.sleep.ActiveSleepTimer
import io.github.onlinemo.meloxwatch.playback.sleep.SleepTimerEndMode
import io.github.onlinemo.meloxwatch.playback.sleep.SleepTimerState
import io.github.onlinemo.meloxwatch.playback.sleep.SleepTimerStop
import io.github.onlinemo.meloxwatch.playback.sleep.SleepTimerStopReason
import io.github.onlinemo.meloxwatch.playback.sleep.SleepTimerToken
import io.github.onlinemo.meloxwatch.playback.sleep.SleepTimerTrackBinding

/** 定时恢复是本机启动周期的私有状态，不能进入可移植备份或手机同步。 */
internal object SleepTimerStoreCodec {
    const val SCHEMA_VERSION = 1
    private const val MAX_IDENTITY_CHARS = 128
    private val schema = intPreferencesKey("schema_version")
    private val revision = longPreferencesKey("revision")
    private val phase = stringPreferencesKey("phase")
    private val generation = stringPreferencesKey("timer_generation")
    private val bootId = stringPreferencesKey("boot_id")
    private val deadline = longPreferencesKey("deadline_elapsed_ms")
    private val started = longPreferencesKey("started_elapsed_ms")
    private val minutes = intPreferencesKey("minutes")
    private val mode = stringPreferencesKey("end_mode")
    private val waiting = booleanPreferencesKey("waiting")
    private val session = stringPreferencesKey("waiting_session")
    private val queue = stringPreferencesKey("waiting_queue")
    private val entry = stringPreferencesKey("waiting_entry")
    private val cycle = longPreferencesKey("waiting_cycle")
    private val stopReason = stringPreferencesKey("stop_reason")
    private val corrupt = booleanPreferencesKey("recovered_corrupt_file")

    fun decode(stored: Preferences): StoredSleepTimer {
        val raw = namedValues(stored)
        if (raw.isEmpty()) return StoredSleepTimer()
        val actualSchema = raw[schema.name] as? Int
        val rawRevision = raw[revision.name] as? Long
        val safeRevision = rawRevision?.takeIf { it >= 0L } ?: 0L
        if (actualSchema != SCHEMA_VERSION) {
            val newer = actualSchema != null && actualSchema > SCHEMA_VERSION
            return StoredSleepTimer(safeRevision, recovery = if (newer) SleepTimerStoreRecovery.NEWER_SCHEMA
                else SleepTimerStoreRecovery.UNRECOGNIZED_SCHEMA, canWrite = !newer)
        }
        if (rawRevision == null || rawRevision < 0L) {
            return StoredSleepTimer(safeRevision, recovery = SleepTimerStoreRecovery.INVALID_FIELDS)
        }
        val state = try {
            when (raw[phase.name]) {
                "DISABLED" -> SleepTimerState()
                "ACTIVE" -> {
                    val token = readToken(raw)
                    val endMode = requireNotNull(SleepTimerEndMode.entries.firstOrNull { it.name == raw[mode.name] })
                    val isWaiting = requireNotNull(raw[waiting.name] as? Boolean)
                    val binding = if (isWaiting) SleepTimerTrackBinding(
                        readIdentity(raw, session), readIdentity(raw, queue), readIdentity(raw, entry),
                        requireNotNull(raw[cycle.name] as? Long),
                    ) else null
                    SleepTimerState(active = ActiveSleepTimer(token, requireNotNull(raw[started.name] as? Long),
                        requireNotNull(raw[minutes.name] as? Int), endMode, binding))
                }
                "STOPPED" -> SleepTimerState(lastStop = SleepTimerStop(readToken(raw), requireNotNull(
                    SleepTimerStopReason.entries.firstOrNull { it.name == raw[stopReason.name] },
                )))
                else -> null
            }
        } catch (_: IllegalArgumentException) {
            null
        }
        if (state == null) return StoredSleepTimer(safeRevision, recovery = SleepTimerStoreRecovery.INVALID_FIELDS)
        val recovery = if (raw[corrupt.name] == true) SleepTimerStoreRecovery.CORRUPT_FILE else null
        return StoredSleepTimer(rawRevision, state, recovery,
            canonical = recovery == null && raw == namedValues(encode(rawRevision, state)))
    }

    fun canEncode(state: SleepTimerState): Boolean {
        val binding = state.active?.waitingFor ?: return true
        return binding.sessionId.length <= MAX_IDENTITY_CHARS &&
            binding.queueGenerationId.length <= MAX_IDENTITY_CHARS &&
            binding.queueEntryId.length <= MAX_IDENTITY_CHARS
    }

    fun encode(storedRevision: Long, state: SleepTimerState): Preferences {
        require(storedRevision >= 0L)
        require(canEncode(state))
        return mutablePreferencesOf(schema to SCHEMA_VERSION, revision to storedRevision).apply {
            val active = state.active
            val stopped = state.lastStop
            this[phase] = when {
                active != null -> "ACTIVE"
                stopped != null -> "STOPPED"
                else -> "DISABLED"
            }
            val token = active?.token ?: stopped?.token
            if (token != null) {
                this[generation] = token.generation
                token.bootId?.let { this[bootId] = it }
                this[deadline] = token.deadlineElapsedMs
            }
            if (active != null) {
                this[started] = active.startedElapsedMs
                this[minutes] = active.minutes
                this[mode] = active.endMode.name
                val binding = active.waitingFor
                this[waiting] = binding != null
                if (binding != null) {
                    this[session] = binding.sessionId
                    this[queue] = binding.queueGenerationId
                    this[entry] = binding.queueEntryId
                    this[cycle] = binding.playbackCycle
                }
            }
            if (stopped != null) this[stopReason] = stopped.reason.name
        }
    }

    fun corruptionDefaults(): Preferences = encode(0L, SleepTimerState()).toMutablePreferences().apply {
        this[corrupt] = true
    }

    private fun readToken(raw: Map<String, Any>): SleepTimerToken {
        val boot = if (bootId.name in raw) readIdentity(raw, bootId) else null
        return SleepTimerToken(readIdentity(raw, generation), boot, requireNotNull(raw[deadline.name] as? Long))
    }

    private fun readIdentity(raw: Map<String, Any>, key: Preferences.Key<String>): String =
        requireNotNull(raw[key.name] as? String).also { require(it.isNotBlank() && it.length <= MAX_IDENTITY_CHARS) }

    private fun namedValues(stored: Preferences): Map<String, Any> =
        stored.asMap().entries.associate { it.key.name to it.value }
}
