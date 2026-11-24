package io.github.onlinemo.meloxwatch.playback

import android.os.Bundle
import io.github.onlinemo.meloxwatch.model.PlaybackBackendKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class PlaybackBackendStateTest {
    @Test fun aFallbackAndPendingSpeedRoundTripWithoutReplacingTheSavedRequest() {
        val state = PlaybackBackendState(
            requestedBackend = PlaybackBackendKind.SYSTEM_MEDIA_PLAYER,
            actualBackend = PlaybackBackendKind.MEDIA3,
            generation = 7L,
            speed = PlaybackSpeedState(15, 1f, 1f, pending = true),
            gaplessRequested = true,
            gaplessAvailable = false,
            issue = PlaybackBackendIssue.SWITCH_FAILED,
        )
        assertEquals(state, PlaybackBackendState.fromSessionExtras(state.toSessionExtras()))
        assertEquals(PlaybackBackendState(), PlaybackBackendState.fromSessionExtras(PlaybackBackendState().toSessionExtras()))
    }

    @Test fun everyMissingObservationIsRejectedInsteadOfBecomingAFalseOrOneSpeedDefault() {
        val fields = listOf(
            PlaybackBackendState.REQUESTED_BACKEND, PlaybackBackendState.GENERATION,
            PlaybackBackendState.SWITCHING, PlaybackBackendState.REQUESTED_SPEED,
            PlaybackBackendState.ACTUAL_SPEED, PlaybackBackendState.ACTUAL_PITCH,
            PlaybackBackendState.SPEED_PENDING, PlaybackBackendState.GAPLESS_REQUESTED,
            PlaybackBackendState.GAPLESS_AVAILABLE,
        )
        fields.forEach { missing ->
            val extras = valid().apply { remove(missing) }
            assertNull(missing, PlaybackBackendState.fromSessionExtras(extras))
        }
    }

    @Test fun primitiveTypesAreNotCoercedAcrossIntLongFloatAndBoolean() {
        val malformed = listOf<Bundle.() -> Unit>(
            { putInt(PlaybackBackendState.GENERATION, 3) },
            { putLong(PlaybackBackendState.REQUESTED_SPEED, 10L) },
            { putDouble(PlaybackBackendState.ACTUAL_SPEED, 1.0) },
            { putInt(PlaybackBackendState.ACTUAL_PITCH, 1) },
            { putString(PlaybackBackendState.SWITCHING, "false") },
            { putInt(PlaybackBackendState.SPEED_PENDING, 0) },
            { putInt(PlaybackBackendState.GAPLESS_REQUESTED, 1) },
            { putString(PlaybackBackendState.GAPLESS_AVAILABLE, "false") },
        )
        malformed.forEach { change -> assertNull(PlaybackBackendState.fromSessionExtras(valid().apply(change))) }
    }

    @Test fun invalidOrNonFiniteObservationsCannotAdvertiseAUsableBackend() {
        val malformed = listOf<Bundle.() -> Unit>(
            { putString(PlaybackBackendState.REQUESTED_BACKEND, "UNKNOWN") },
            { putString(PlaybackBackendState.ACTUAL_BACKEND, "UNKNOWN") },
            { putInt(PlaybackBackendState.ACTUAL_BACKEND, 1) },
            { putString(PlaybackBackendState.SPEED_ISSUE, "UNKNOWN") },
            { putBoolean(PlaybackBackendState.ISSUE, true) },
            { putLong(PlaybackBackendState.GENERATION, -1L) },
            { putInt(PlaybackBackendState.REQUESTED_SPEED, 21) },
            { putFloat(PlaybackBackendState.ACTUAL_SPEED, Float.NaN) },
            { putFloat(PlaybackBackendState.ACTUAL_SPEED, Float.POSITIVE_INFINITY) },
            { putFloat(PlaybackBackendState.ACTUAL_PITCH, 0f) },
        )
        malformed.forEach { change -> assertNull(PlaybackBackendState.fromSessionExtras(valid().apply(change))) }
    }

    @Test fun optionalAbsentBackendAndIssuesRemainAbsent() {
        val extras = valid().apply {
            putString(PlaybackBackendState.ACTUAL_BACKEND, null)
            putString(PlaybackBackendState.SPEED_ISSUE, null)
            putString(PlaybackBackendState.ISSUE, null)
        }
        assertEquals(PlaybackBackendState(), PlaybackBackendState.fromSessionExtras(extras))
    }

    private fun valid() = PlaybackBackendState().toSessionExtras()
}
