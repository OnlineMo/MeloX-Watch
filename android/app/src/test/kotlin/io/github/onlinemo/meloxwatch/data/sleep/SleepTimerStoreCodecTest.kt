package io.github.onlinemo.meloxwatch.data.sleep

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.onlinemo.meloxwatch.playback.sleep.SleepTimerClockSample
import io.github.onlinemo.meloxwatch.playback.sleep.SleepTimerEndMode
import io.github.onlinemo.meloxwatch.playback.sleep.SleepTimerReducer
import io.github.onlinemo.meloxwatch.playback.sleep.SleepTimerState
import io.github.onlinemo.meloxwatch.playback.sleep.SleepTimerTrackBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerStoreCodecTest {
    private fun timer() = SleepTimerReducer.arm(1, SleepTimerEndMode.AFTER_CURRENT_TRACK,
        SleepTimerClockSample("fixture-boot", 1_000L)).state

    @Test fun waitingTraversalAndCancelledReasonRetainTheirExactAuthority() {
        val armed = timer()
        val token = requireNotNull(armed.active).token
        val track = SleepTimerTrackBinding("session", "queue", "entry", 4L)
        val waiting = SleepTimerReducer.onDeadline(armed, token, SleepTimerClockSample("fixture-boot", 61_000L), track).state
        val decoded = SleepTimerStoreCodec.decode(SleepTimerStoreCodec.encode(8L, waiting))
        assertEquals(waiting, decoded.state)
        assertEquals(8L, decoded.revision)
        assertTrue(decoded.canonical)
        val cancelled = SleepTimerReducer.cancel(waiting).state
        assertEquals(cancelled, SleepTimerStoreCodec.decode(SleepTimerStoreCodec.encode(9L, cancelled)).state)
    }

    @Test fun absentBootStaysAbsentRatherThanBecomingVerifiedDuringPersistence() {
        val state = SleepTimerReducer.arm(1, SleepTimerEndMode.PAUSE_IMMEDIATELY, SleepTimerClockSample(null, 0L)).state
        val decoded = SleepTimerStoreCodec.decode(SleepTimerStoreCodec.encode(1L, state))
        assertNull(decoded.state.active?.token?.bootId)
        assertEquals(state, decoded.state)
        assertTrue(SleepTimerReducer.restore(decoded.state, SleepTimerClockSample(null, 1L)).pauseRequired)
        assertNull(SleepTimerReducer.restore(decoded.state, SleepTimerClockSample(null, 1L)).state.active)
    }

    @Test fun damagedTokenDurationModeAndTypesCannotRestoreAPartialTimer() {
        val encoded = SleepTimerStoreCodec.encode(3L, timer())
        val badFields = listOf(
            encoded.toMutablePreferences().apply { this[stringPreferencesKey("timer_generation")] = "1-1-1-1-1" },
            encoded.toMutablePreferences().apply { this[longPreferencesKey("deadline_elapsed_ms")] = 60_999L },
            encoded.toMutablePreferences().apply { this[stringPreferencesKey("end_mode")] = "UNKNOWN" },
            encoded.toMutablePreferences().apply { this[stringPreferencesKey("minutes")] = "1" },
            encoded.toMutablePreferences().apply { this[stringPreferencesKey("revision")] = "3" },
            encoded.toMutablePreferences().apply { this[stringPreferencesKey("boot_id")] = "x".repeat(129) },
        )
        for (raw in badFields) {
            val result = SleepTimerStoreCodec.decode(raw)
            assertEquals(SleepTimerState(), result.state)
            assertEquals(SleepTimerStoreRecovery.INVALID_FIELDS, result.recovery)
        }
    }

    @Test fun aNewerSchemaIsReadableAsASafeBlockedStateAndNeverWritable() {
        val raw = mutablePreferencesOf(intPreferencesKey("schema_version") to 2,
            longPreferencesKey("revision") to 17L, stringPreferencesKey("phase") to "ACTIVE")
        val result = SleepTimerStoreCodec.decode(raw)
        assertEquals(17L, result.revision)
        assertEquals(SleepTimerState(), result.state)
        assertFalse(result.canWrite)
        assertEquals(SleepTimerStoreRecovery.NEWER_SCHEMA, result.recovery)
    }

    @Test fun unrecognizedSchemaAndCorruptionRequireAnExplainedExplicitRepair() {
        val unknown = SleepTimerStoreCodec.decode(mutablePreferencesOf(
            stringPreferencesKey("schema_version") to "1", longPreferencesKey("revision") to 7L))
        assertTrue(unknown.canWrite)
        assertEquals(SleepTimerStoreRecovery.UNRECOGNIZED_SCHEMA, unknown.recovery)
        val corrupt = SleepTimerStoreCodec.decode(SleepTimerStoreCodec.corruptionDefaults())
        assertEquals(SleepTimerState(), corrupt.state)
        assertEquals(SleepTimerStoreRecovery.CORRUPT_FILE, corrupt.recovery)
        assertFalse(corrupt.canonical)
    }
}
