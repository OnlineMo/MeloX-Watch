package io.github.onlinemo.meloxwatch.playback.route

import android.os.Bundle
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class MediaVolumeCommandsTest {
    @Test
    fun exactSetProtocolRoundTripsWithoutDeviceOrSourceMetadata() {
        val request = MediaVolumeSetRequest(snapshot(), 6)
        val arguments = MediaVolumeCommands.setArguments(request)
        assertEquals(request, MediaVolumeCommands.readSet(arguments))
        assertEquals(setOf("schema", "owner", "known", "current", "minimum", "maximum", "fixed", "muted", "remote", "target"),
            arguments.keySet())
        arguments.putInt("current", 1)
        assertEquals(4, request.expected.values.current)
    }

    @Test
    fun relativeProtocolCarriesOnlyDirectionAndSchema() {
        for (delta in listOf(-1, 1)) {
            val arguments = MediaVolumeCommands.adjustArguments(delta)
            assertEquals(setOf("schema", "delta"), arguments.keySet())
            assertEquals(delta, MediaVolumeCommands.readAdjust(arguments))
        }
        MediaVolumeCommands.readRead(MediaVolumeCommands.readArguments())
        rejects { MediaVolumeCommands.adjustArguments(0) }
        rejects { MediaVolumeCommands.adjustArguments(2) }
    }

    @Test
    fun missingWrongPrimitiveOrUnknownFieldsCannotBecomeDefaultSetValues() {
        val mutations: List<Bundle.() -> Unit> = listOf(
            { remove("schema") }, { putString("schema", "1") }, { putInt("schema", 0) }, { putInt("schema", 2) },
            { remove("owner") }, { putInt("owner", 7) }, { putString("owner", "device-7") },
            { putString("owner", null) }, { putString("owner", "00000000-0000-0000-0000-00000000000A") },
            { remove("known") }, { putInt("known", 1) }, { putBoolean("known", false) },
            { remove("current") }, { putLong("current", 4L) }, { putInt("current", -1) },
            { remove("minimum") }, { putInt("minimum", -1) }, { putInt("minimum", 5) },
            { remove("maximum") }, { putString("maximum", "10") }, { putInt("maximum", 3) },
            { remove("fixed") }, { putInt("fixed", 0) }, { remove("muted") }, { putString("muted", "false") },
            { remove("remote") }, { putInt("remote", 0) }, { remove("target") }, { putLong("target", 6L) },
            { putInt("target", -1) }, { putInt("target", 11) }, { putString("device_id", "7") },
            { putString("device_name", "synthetic output") }, { putString("address", "synthetic address") },
        )
        for (mutate in mutations) {
            rejects { MediaVolumeCommands.readSet(MediaVolumeCommands.setArguments(MediaVolumeSetRequest(snapshot(), 6)).apply(mutate)) }
        }
    }

    @Test
    fun malformedReadAndRelativeCommandsRejectBeforeSubmission() {
        for (arguments in listOf(
            Bundle(), Bundle().apply { putString("schema", "1") },
            MediaVolumeCommands.readArguments().apply { putInt("delta", 1) },
        )) rejects { MediaVolumeCommands.readRead(arguments) }
        val mutations: List<Bundle.() -> Unit> = listOf(
            { remove("delta") }, { putString("delta", "1") }, { putInt("delta", 0) }, { putInt("delta", 2) },
            { putInt("delta", Int.MIN_VALUE) }, { putString("owner", UUID.randomUUID().toString()) },
        )
        for (mutate in mutations) rejects { MediaVolumeCommands.readAdjust(MediaVolumeCommands.adjustArguments(1).apply(mutate)) }
    }

    @Test
    fun realReadbackAndWriteAttemptRoundTripSeparatelyFromSuccess() {
        val results = listOf(
            MediaVolumeResult(snapshot()),
            MediaVolumeResult(snapshot().copy(route = PlaybackRouteSessionState())),
            MediaVolumeResult(snapshot(), accepted = true, changed = true, writeAttempted = true),
            MediaVolumeResult(snapshot(), ServiceMediaVolumeIssue.OUTPUT_CHANGED, writeAttempted = true),
            MediaVolumeResult(null, ServiceMediaVolumeIssue.UNAVAILABLE, writeAttempted = true),
        )
        for (result in results) {
            assertEquals(result, MediaVolumeCommands.readResult(MediaVolumeCommands.resultArguments(result)))
        }
    }

    @Test
    fun fabricatedSuccessAndMalformedResultFieldsAreRejected() {
        val unavailable = MediaVolumeCommands.resultArguments(MediaVolumeResult(null, ServiceMediaVolumeIssue.UNAVAILABLE))
        rejects { MediaVolumeCommands.readResult(Bundle(unavailable).apply { putBoolean("accepted", true) }) }
        rejects { MediaVolumeCommands.readResult(Bundle(unavailable).apply { putBoolean("changed", true) }) }
        val mutations: List<Bundle.() -> Unit> = listOf(
            { putInt("available", 1) }, { remove("available") }, { remove("issue") }, { putInt("issue", 0) },
            { putString("issue", "SUCCESS") }, { putInt("accepted", 1) }, { remove("write_attempted") },
            { putBoolean("changed", true) }, { putBoolean("known", false) }, { putString("device_id", "7") },
        )
        for (mutate in mutations) {
            rejects { MediaVolumeCommands.readResult(MediaVolumeCommands.resultArguments(MediaVolumeResult(snapshot())).apply(mutate)) }
        }
    }

    @Test
    fun routeSessionContainsOnlyOpaqueOwnerAndKnownStateAlongsideOtherNamespaces() {
        val route = snapshot().route
        val extras = route.toSessionExtras()
        assertEquals(setOf(ROUTE_PREFIX + "schema", ROUTE_PREFIX + "owner", ROUTE_PREFIX + "known"), extras.keySet())
        extras.putString("unrelated.session.field", "retained")
        assertEquals(route, PlaybackRouteSessionState.fromSessionExtras(extras))
        val unknown = PlaybackRouteSessionState().toSessionExtras()
        assertNotNull(PlaybackRouteSessionState.fromSessionExtras(unknown))
        assertFalse(requireNotNull(PlaybackRouteSessionState.fromSessionExtras(unknown)).known)
        assertNull(PlaybackRouteSessionState.fromSessionExtras(Bundle()))
    }

    @Test
    fun malformedRouteNamespaceNeverBecomesKnownByDefaultReads() {
        val mutations: List<Bundle.() -> Unit> = listOf(
            { remove(ROUTE_PREFIX + "schema") }, { putString(ROUTE_PREFIX + "schema", "1") },
            { remove(ROUTE_PREFIX + "owner") }, { putInt(ROUTE_PREFIX + "owner", 7) },
            { putString(ROUTE_PREFIX + "owner", "device-7") }, { putString(ROUTE_PREFIX + "owner", null) },
            { remove(ROUTE_PREFIX + "known") }, { putString(ROUTE_PREFIX + "known", "true") },
            { putBoolean(ROUTE_PREFIX + "known", false) }, { putInt(ROUTE_PREFIX + "device", 7) },
        )
        for (mutate in mutations) {
            assertNull(snapshot().route.toSessionExtras().apply(mutate).let(PlaybackRouteSessionState::fromSessionExtras))
        }
        assertTrue(PlaybackRouteSessionState.fromSessionExtras(snapshot().route.toSessionExtras())?.known == true)
    }

    private fun snapshot() = MediaVolumeSnapshot(MusicVolumeValues(4, 0, 10, false, false, false),
        PlaybackRouteSessionState(UUID.randomUUID().toString(), true))

    private fun rejects(action: () -> Unit) {
        try { action() }
        catch (_: IllegalArgumentException) { return }
        throw AssertionError("Malformed volume protocol was accepted")
    }

    companion object {
        private const val ROUTE_PREFIX = "io.github.onlinemo.meloxwatch.route."
    }
}
