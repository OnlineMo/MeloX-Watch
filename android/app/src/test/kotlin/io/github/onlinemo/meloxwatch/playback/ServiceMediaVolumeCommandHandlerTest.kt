package io.github.onlinemo.meloxwatch.playback

import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import io.github.onlinemo.meloxwatch.playback.route.MediaVolumeCommands
import io.github.onlinemo.meloxwatch.playback.route.MediaVolumeSetRequest
import io.github.onlinemo.meloxwatch.playback.route.MediaVolumeSnapshot
import io.github.onlinemo.meloxwatch.playback.route.PlaybackRouteSessionState
import io.github.onlinemo.meloxwatch.playback.route.RouteTestFixture
import io.github.onlinemo.meloxwatch.playback.route.ServiceMediaVolume
import io.github.onlinemo.meloxwatch.playback.route.ServiceMediaVolumeIssue
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 命令、实际路由保护与媒体流边界整合；路由及音量响应受控，未代表设备验收。 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class ServiceMediaVolumeCommandHandlerTest {
    @Test
    fun anotherUidIsDeniedBeforeMalformedArgumentsOrAnyPlatformAccess() = Fixture().use { fixture ->
        for (action in listOf(MediaVolumeCommands.read, MediaVolumeCommands.set, MediaVolumeCommands.adjust)) {
            val malformed = Bundle().apply { putLong("schema", 1L) }
            val result = checkNotNull(fixture.handler.handle(OWNER_UID + 1, action.customAction, malformed))
            assertEquals(SessionError.ERROR_PERMISSION_DENIED, result.resultCode)
        }
        assertEquals(0, fixture.port.reads)
        assertTrue(fixture.port.writes.isEmpty())
        assertEquals(0, fixture.publications)
    }

    @Test
    fun invalidBundleFieldsNeverReadOrWriteTheStream() = Fixture().use { fixture ->
        val forged = MediaVolumeSnapshot(
            fixture.port.values,
            PlaybackRouteSessionState(UUID.randomUUID().toString(), true),
        )
        val inputs = listOf(
            MediaVolumeCommands.read.customAction to Bundle().apply { putLong("schema", 1L) },
            MediaVolumeCommands.read.customAction to MediaVolumeCommands.readArguments().apply { putString("extra", "unexpected") },
            MediaVolumeCommands.adjust.customAction to MediaVolumeCommands.readArguments().apply { putInt("delta", 2) },
            MediaVolumeCommands.set.customAction to
                MediaVolumeCommands.setArguments(MediaVolumeSetRequest(forged, 6)).apply { putLong("target", 6L) },
        )
        for ((action, args) in inputs) {
            assertEquals(
                SessionError.ERROR_BAD_VALUE,
                checkNotNull(fixture.handler.handle(OWNER_UID, action, args)).resultCode,
            )
        }
        assertEquals(0, fixture.port.reads)
        assertTrue(fixture.port.writes.isEmpty())
        assertEquals(0, fixture.publications)
    }

    @Test
    fun aReadReturnsTheCurrentStreamSnapshotAndPublishesBeforeTheReply() = Fixture(active = true).use { fixture ->
        fixture.port.values = fixture.port.values.copy(current = 8)
        fixture.onPublication = {
            assertEquals(8, fixture.port.values.current)
            assertTrue(fixture.routing.route.cachedState().known)
        }
        val reply = fixture.command(MediaVolumeCommands.read.customAction, MediaVolumeCommands.readArguments())
        val result = MediaVolumeCommands.readResult(reply.extras)
        assertEquals(SessionResult.RESULT_SUCCESS, reply.resultCode)
        assertEquals(8, checkNotNull(result.snapshot).values.current)
        assertTrue(checkNotNull(result.snapshot).route.known)
        assertFalse(result.accepted || result.changed || result.writeAttempted)
        assertEquals(1, fixture.publications)
    }

    @Test
    fun aValidAbsoluteSetReturnsReadbackAndKeepsItsOwner() = Fixture(active = true).use { fixture ->
        val expected = checkNotNull(fixture.volume.read().snapshot)
        fixture.onPublication = { assertEquals(6, fixture.port.values.current) }
        val reply = fixture.command(MediaVolumeCommands.set.customAction,
            MediaVolumeCommands.setArguments(MediaVolumeSetRequest(expected, 6)))
        val result = MediaVolumeCommands.readResult(reply.extras)
        assertEquals(SessionResult.RESULT_SUCCESS, reply.resultCode)
        assertTrue(result.accepted && result.changed && result.writeAttempted)
        assertEquals(6, checkNotNull(result.snapshot).values.current)
        assertEquals(expected.route, checkNotNull(result.snapshot).route)
        assertEquals(listOf(6), fixture.port.writes)
        assertEquals(1, fixture.publications)
    }

    @Test
    fun anAdmittedSameDeviceRouteEventRejectsTheHeldRequestBeforeWriting() = Fixture(active = true).use { fixture ->
        val expected = checkNotNull(fixture.volume.read().snapshot)
        fixture.source.callbacks.single().invoke()
        val reply = fixture.command(MediaVolumeCommands.set.customAction,
            MediaVolumeCommands.setArguments(MediaVolumeSetRequest(expected, 6)))
        val result = MediaVolumeCommands.readResult(reply.extras)
        assertEquals(ServiceMediaVolumeIssue.OUTPUT_CHANGED, result.issue)
        assertFalse(result.accepted || result.changed || result.writeAttempted)
        assertNotEquals(expected.route.ownerId, checkNotNull(result.snapshot).route.ownerId)
        assertTrue(fixture.port.writes.isEmpty())
        assertEquals(1, fixture.publications)
    }

    @Test
    fun aRouteChangeDuringThePlatformWriteKeepsTheAttemptAndRejectsItsAck() = Fixture(active = true).use { fixture ->
        val expected = checkNotNull(fixture.volume.read().snapshot)
        fixture.port.onWrite = { fixture.source.callbacks.single().invoke() }
        fixture.onPublication = { assertEquals(7, fixture.port.values.current) }
        val reply = fixture.command(MediaVolumeCommands.set.customAction,
            MediaVolumeCommands.setArguments(MediaVolumeSetRequest(expected, 7)))
        val result = MediaVolumeCommands.readResult(reply.extras)
        assertEquals(SessionResult.RESULT_SUCCESS, reply.resultCode)
        assertEquals(ServiceMediaVolumeIssue.OUTPUT_CHANGED, result.issue)
        assertTrue(result.writeAttempted)
        assertFalse(result.accepted || result.changed)
        assertEquals(7, checkNotNull(result.snapshot).values.current)
        assertNotEquals(expected.route.ownerId, checkNotNull(result.snapshot).route.ownerId)
        assertEquals(listOf(7), fixture.port.writes)
    }

    @Test
    fun aPausedRelativeCommandDerivesItsStepFromThisRequestFreshRead() = Fixture().use { fixture ->
        val old = checkNotNull(fixture.volume.read().snapshot)
        assertEquals(4, old.values.current)
        fixture.port.values = fixture.port.values.copy(current = 10)
        val reply = fixture.command(MediaVolumeCommands.adjust.customAction, MediaVolumeCommands.adjustArguments(-1))
        val result = MediaVolumeCommands.readResult(reply.extras)
        assertTrue(result.accepted && result.changed && result.writeAttempted)
        assertEquals(9, checkNotNull(result.snapshot).values.current)
        assertFalse(checkNotNull(result.snapshot).route.known)
        assertEquals(listOf(9), fixture.port.writes)
    }

    @Test
    fun validFixedVolumeFailureTravelsInTheStructuredReply() = Fixture().use { fixture ->
        fixture.port.values = fixture.port.values.copy(fixed = true)
        val reply = fixture.command(MediaVolumeCommands.adjust.customAction, MediaVolumeCommands.adjustArguments(1))
        val result = MediaVolumeCommands.readResult(reply.extras)
        assertEquals(SessionResult.RESULT_SUCCESS, reply.resultCode)
        assertEquals(ServiceMediaVolumeIssue.FIXED, result.issue)
        assertFalse(result.accepted || result.changed || result.writeAttempted)
        assertTrue(fixture.port.writes.isEmpty())
    }

    @Test
    fun otherNamespacesRemainAvailableToTheExistingSessionDispatcher() = Fixture().use { fixture ->
        assertNull(fixture.handler.handle(OWNER_UID, "io.github.onlinemo.meloxwatch.sleep.REFRESH", Bundle.EMPTY))
        assertEquals(0, fixture.port.reads)
        assertEquals(0, fixture.publications)
    }

    @Test
    fun publicationFailureDoesNotHideACompletedPlatformWrite() = Fixture(active = true).use { fixture ->
        val expected = checkNotNull(fixture.volume.read().snapshot)
        fixture.onPublication = { throw IllegalStateException("Controlled session teardown") }
        val reply = fixture.command(MediaVolumeCommands.set.customAction,
            MediaVolumeCommands.setArguments(MediaVolumeSetRequest(expected, 5)))
        val result = MediaVolumeCommands.readResult(reply.extras)
        assertTrue(result.accepted && result.changed && result.writeAttempted)
        assertEquals(5, checkNotNull(result.snapshot).values.current)
        assertEquals(listOf(5), fixture.port.writes)
    }

    @Test
    fun commandDispatchRequiresTheServiceThreadBeforeAnyStreamAccess() = Fixture().use { fixture ->
        try {
            runBlocking {
                withContext(Dispatchers.Default) {
                    fixture.handler.handle(
                        OWNER_UID,
                        MediaVolumeCommands.read.customAction,
                        MediaVolumeCommands.readArguments(),
                    )
                }
            }
            throw AssertionError("The volume dispatcher must use the Service owner Looper")
        } catch (_: IllegalStateException) {
            assertEquals(0, fixture.port.reads)
            assertTrue(fixture.port.writes.isEmpty())
        }
    }

    private class Fixture(active: Boolean = false) : AutoCloseable {
        val routing = RouteTestFixture()
        val source = routing.attach()
        val port = RecordingMusicVolume()
        val volume = ServiceMediaVolume(port, routing.route::read)
        var publications = 0
            private set
        var onPublication: (() -> Unit)? = null
        val handler = ServiceMediaVolumeCommandHandler(
            OWNER_UID,
            volume::read,
            volume::setAbsolute,
            volume::adjust,
            onResult = {
                publications++
                onPublication?.invoke()
            },
        )
        init { routing.route.setPlaybackActive(active) }
        fun command(action: String, args: Bundle): SessionResult = checkNotNull(handler.handle(OWNER_UID, action, args))
        override fun close() = routing.route.close()
    }

    companion object {
        private const val OWNER_UID = 101
    }
}
