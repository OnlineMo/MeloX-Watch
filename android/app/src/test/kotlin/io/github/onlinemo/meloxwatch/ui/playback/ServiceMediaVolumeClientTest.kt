package io.github.onlinemo.meloxwatch.ui.playback

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.SettableFuture
import io.github.onlinemo.meloxwatch.playback.RecordingMusicVolume
import io.github.onlinemo.meloxwatch.playback.ServiceMediaVolumeCommandHandler
import io.github.onlinemo.meloxwatch.playback.input.RotaryStep
import io.github.onlinemo.meloxwatch.playback.input.RotaryTarget
import io.github.onlinemo.meloxwatch.playback.route.MediaVolumeCommands
import io.github.onlinemo.meloxwatch.playback.route.MediaVolumeResult
import io.github.onlinemo.meloxwatch.playback.route.MediaVolumeSetRequest
import io.github.onlinemo.meloxwatch.playback.route.PlaybackRouteSessionState
import io.github.onlinemo.meloxwatch.playback.route.RouteTestFixture
import io.github.onlinemo.meloxwatch.playback.route.ServiceMediaVolume
import io.github.onlinemo.meloxwatch.playback.route.ServiceMediaVolumeIssue
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** 真实服务协议及 opaque 路由边界的受控回归；未代表控制器 IPC、设备或性能验收。 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ServiceMediaVolumeClientTest {
    @Test
    fun aVisibleReadDisplaysOnlyTheServiceSnapshotWithoutWriting() = Fixture().use { fixture ->
        fixture.port.values = fixture.port.values.copy(current = 8)
        fixture.show()
        val snapshot = checkNotNull(fixture.client.state.value.snapshot)
        assertEquals(8, snapshot.values.current)
        assertEquals(fixture.requests.single().context.route, snapshot.route)
        assertTrue(snapshot.route.known)
        assertTrue(fixture.client.state.value.absoluteAdjustable)
        assertEquals(listOf(MediaVolumeCommands.read), fixture.commands)
        assertTrue(fixture.port.writes.isEmpty())
        assertFalse(fixture.client.busy)
    }

    @Test
    fun draggingAndCancellingNeverSendAnAbsoluteWrite() = Fixture().use { fixture ->
        fixture.show()
        val id = checkNotNull(fixture.client.begin())
        assertTrue(fixture.client.drag(id, 0.8))
        assertEquals(12, fixture.client.state.value.preview)
        assertEquals(4, fixture.client.state.value.current)
        repeat(4) { fixture.client.sample() }
        assertEquals(1, fixture.readCount)
        assertFalse(fixture.client.change(1))
        fixture.client.cancel(id)
        fixture.idle()
        assertFalse(fixture.client.release(id))
        assertNull(fixture.client.state.value.preview)
        assertTrue(fixture.commands.all { it == MediaVolumeCommands.read })
        assertTrue(fixture.port.writes.isEmpty())
    }

    @Test
    fun aCompletedGestureSendsItsExactSnapshotOnceAndWaitsForReadback() = Fixture().use { fixture ->
        fixture.show()
        val observed = checkNotNull(fixture.client.state.value.snapshot)
        fixture.holdReplies = true
        val id = checkNotNull(fixture.client.begin())
        assertTrue(fixture.client.drag(id, 0.4))
        assertTrue(fixture.client.release(id))
        val request = fixture.requests.last()
        assertEquals(MediaVolumeCommands.set, request.command)
        assertEquals(MediaVolumeSetRequest(observed, 6), MediaVolumeCommands.readSet(request.arguments))
        assertTrue(fixture.client.busy)
        assertEquals(MediaVolumeClientPhase.COMMITTING, fixture.client.state.value.phase)
        assertEquals(4, fixture.client.state.value.current)
        assertEquals(listOf(6), fixture.port.writes)
        assertFalse(fixture.client.release(id))
        assertNull(fixture.client.begin())
        assertFalse(fixture.client.change(-1))
        fixture.acknowledge(request)
        fixture.idle()
        assertEquals(6, fixture.client.state.value.current)
        assertFalse(fixture.client.busy)
        assertEquals(1, fixture.commands.count { it == MediaVolumeCommands.set })
        assertTrue(fixture.result(request).accepted && fixture.result(request).changed)
    }

    @Test
    fun releaseWithoutMovementStaysAServiceVerifiedNoop() = Fixture().use { fixture ->
        fixture.show()
        val id = checkNotNull(fixture.client.begin())
        assertTrue(fixture.client.release(id))
        fixture.idle()
        val result = fixture.result(fixture.requests.last())
        assertTrue(result.accepted)
        assertFalse(result.changed || result.writeAttempted)
        assertTrue(fixture.port.writes.isEmpty())
    }

    @Test
    fun invalidFractionsRetireTheGestureWithoutWriting() {
        for (fraction in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.01, 1.01)) {
            Fixture().use { fixture ->
                fixture.show()
                val id = checkNotNull(fixture.client.begin())
                assertFalse(fixture.client.drag(id, fraction))
                fixture.idle()
                assertFalse(fixture.client.release(id))
                assertFalse(fixture.client.setProgress(fraction))
                assertNull(fixture.client.state.value.preview)
                assertTrue(fixture.port.writes.isEmpty())
            }
        }
    }

    @Test
    fun aMutedZeroBelowMinimumIsDisplayedAndOnlyExplicitMovementUnmutes() = Fixture().use { fixture ->
        fixture.port.values = fixture.port.values.copy(current = 0, minimum = 1, muted = true)
        fixture.show()
        assertEquals(0, fixture.client.state.value.current)
        assertTrue(fixture.client.state.value.muted)
        val id = checkNotNull(fixture.client.begin())
        assertEquals(0, fixture.client.state.value.preview)
        assertFalse(fixture.client.release(id))
        fixture.idle()
        assertTrue(fixture.port.writes.isEmpty())
        assertTrue(fixture.client.setProgress(0.0))
        fixture.idle()
        assertEquals(listOf(1), fixture.port.writes)
        assertEquals(1, fixture.client.state.value.current)
        assertFalse(fixture.client.state.value.muted)
    }

    @Test
    fun aPausedRelativeStepUsesThisCommandsFreshStreamValue() = runBlocking {
        Fixture(active = false).use { fixture ->
            fixture.show()
            assertFalse(checkNotNull(fixture.client.state.value.snapshot).route.known)
            assertFalse(fixture.client.state.value.absoluteAdjustable)
            assertTrue(fixture.client.state.value.relativeAdjustable)
            fixture.port.values = fixture.port.values.copy(current = 10)
            val changed = fixture.scope.async(start = CoroutineStart.UNDISPATCHED) {
                fixture.client.rotary(step(-1))
            }
            assertFalse(changed.isCompleted)
            fixture.idle()
            assertTrue(completed(changed))
            assertEquals(listOf(9), fixture.port.writes)
            assertEquals(9, fixture.client.state.value.current)
            assertEquals(MediaVolumeCommands.adjust, fixture.requests.last().command)
        }
    }

    @Test
    fun fixedRemoteAndZeroRangeKeepRealValuesButNeverProduceASuccess() = runBlocking {
        val blocked = listOf(
            ServiceMediaVolumeIssue.FIXED to { fixture: Fixture -> fixture.port.values.copy(fixed = true) },
            ServiceMediaVolumeIssue.REMOTE_OUTPUT to { fixture: Fixture -> fixture.port.values.copy(remote = true) },
            ServiceMediaVolumeIssue.FIXED to { fixture: Fixture -> fixture.port.values.copy(current = 0, maximum = 0) },
        )
        for ((issue, values) in blocked) Fixture().use { fixture ->
            fixture.port.values = values(fixture)
            fixture.show()
            assertEquals(issue, fixture.client.state.value.issue)
            assertEquals(fixture.port.values.current, fixture.client.state.value.current)
            assertFalse(fixture.client.state.value.relativeAdjustable)
            assertNull(fixture.client.begin())
            val changed = fixture.scope.async(start = CoroutineStart.UNDISPATCHED) { fixture.client.rotary(step(1)) }
            fixture.idle()
            assertFalse(completed(changed))
            assertEquals(issue, fixture.client.state.value.issue)
            assertTrue(fixture.port.writes.isEmpty())
        }
    }

    @Test
    fun relativeLimitsReportNoChangeAndAnOppositeStepStillWorks() = runBlocking {
        for ((index, delta) in listOf(0 to -1, 15 to 1)) Fixture().use { fixture ->
            fixture.port.values = fixture.port.values.copy(current = index)
            fixture.show()
            val blocked = fixture.scope.async(start = CoroutineStart.UNDISPATCHED) { fixture.client.rotary(step(delta)) }
            fixture.idle()
            assertFalse(completed(blocked))
            assertEquals(ServiceMediaVolumeIssue.AT_LIMIT, fixture.client.state.value.issue)
            assertTrue(fixture.port.writes.isEmpty())
            val changed = fixture.scope.async(start = CoroutineStart.UNDISPATCHED) { fixture.client.rotary(step(-delta)) }
            fixture.idle()
            assertTrue(completed(changed))
            assertEquals(listOf(index - delta), fixture.port.writes)
        }
    }

    @Test
    fun invalidRelativeIntentAndASeekStepDoNotEnterTheProtocol() = runBlocking {
        Fixture().use { fixture ->
            fixture.show()
            for (delta in listOf(Int.MIN_VALUE, -2, 0, 2, Int.MAX_VALUE)) assertFalse(fixture.client.change(delta))
            val seek = fixture.scope.async(start = CoroutineStart.UNDISPATCHED) {
                fixture.client.rotary(step(1).copy(target = RotaryTarget.SEEK))
            }
            fixture.idle()
            assertFalse(completed(seek))
            assertEquals(1, fixture.requests.size)
            assertTrue(fixture.port.writes.isEmpty())
        }
    }

    @Test
    fun actualOutputChangeWithoutConnectivityEventsDiscardsThePreview() = Fixture().use { fixture ->
        fixture.show()
        val before = checkNotNull(fixture.client.state.value.snapshot)
        val id = checkNotNull(fixture.client.begin())
        assertTrue(fixture.client.drag(id, 0.4))
        fixture.source.deviceId = 8
        fixture.publishActualRoute()
        fixture.idle()
        assertEquals(1, fixture.source.callbacks.size)
        assertNotEquals(before.route, fixture.client.state.value.snapshot?.route)
        assertFalse(fixture.client.release(id))
        assertNull(fixture.client.state.value.preview)
        assertTrue(fixture.port.writes.isEmpty())
    }

    @Test
    fun serviceRequeryRejectsAnUndiscoveredOutputChangeBeforeTheAbsoluteWrite() = Fixture().use { fixture ->
        fixture.show()
        val before = checkNotNull(fixture.client.state.value.snapshot)
        fixture.source.deviceId = 8
        assertTrue(fixture.client.setProgress(0.4))
        val request = fixture.requests.last()
        fixture.idle()
        assertEquals(before, MediaVolumeCommands.readSet(request.arguments).expected)
        val result = fixture.result(request)
        assertEquals(ServiceMediaVolumeIssue.OUTPUT_CHANGED, result.issue)
        assertFalse(result.writeAttempted || result.accepted || result.changed)
        assertTrue(fixture.port.writes.isEmpty())
        assertNotEquals(before.route, fixture.client.state.value.snapshot?.route)
        assertEquals(4, fixture.client.state.value.current)
    }

    @Test
    fun aChangedStreamValueRejectsTheHeldAbsoluteSnapshot() = Fixture().use { fixture ->
        fixture.show()
        fixture.holdExecution = true
        assertTrue(fixture.client.setProgress(0.4))
        val request = fixture.requests.last()
        fixture.port.values = fixture.port.values.copy(current = 5)
        fixture.execute(request)
        fixture.idle()
        assertEquals(ServiceMediaVolumeIssue.OUTPUT_CHANGED, fixture.result(request).issue)
        assertFalse(fixture.result(request).writeAttempted)
        assertEquals(5, fixture.client.state.value.current)
        assertTrue(fixture.port.writes.isEmpty())
    }

    @Test
    fun aRouteRaceInsideTheSetterRetainsTheWriteAttemptAndRejectsSuccess() = runBlocking {
        Fixture().use { fixture ->
            fixture.show()
            fixture.port.onWrite = { fixture.source.deviceId = 8 }
            val changed = fixture.scope.async(start = CoroutineStart.UNDISPATCHED) { fixture.client.rotary(step(1)) }
            val request = fixture.requests.last()
            fixture.idle()
            assertFalse(completed(changed))
            val result = fixture.result(request)
            assertEquals(ServiceMediaVolumeIssue.OUTPUT_CHANGED, result.issue)
            assertTrue(result.writeAttempted)
            assertFalse(result.accepted || result.changed)
            assertEquals(listOf(5), fixture.port.writes)
            assertEquals(5, fixture.client.state.value.current)
        }
    }

    @Test
    fun aPlatformRejectionAfterApplyingKeepsReadbackWithoutSuccess() = runBlocking {
        Fixture().use { fixture ->
            fixture.show()
            fixture.port.onWrite = { throw SecurityException("Controlled late platform rejection") }
            val changed = fixture.scope.async(start = CoroutineStart.UNDISPATCHED) { fixture.client.rotary(step(1)) }
            val request = fixture.requests.last()
            fixture.idle()
            assertFalse(completed(changed))
            assertTrue(fixture.result(request).writeAttempted)
            assertEquals(ServiceMediaVolumeIssue.SET_REJECTED, fixture.client.state.value.issue)
            assertEquals(5, fixture.client.state.value.current)
            assertEquals(listOf(5), fixture.port.writes)
        }
    }

    @Test
    fun changedConnectionBackendAndUiOwnersCannotAcceptTheOldWriteAck() = runBlocking {
        val changes: List<(MediaVolumeClientContext) -> MediaVolumeClientContext> = listOf(
            { it.copy(connectionGeneration = it.connectionGeneration + 1L) },
            { it.copy(backendGeneration = 2L) },
            { it.copy(backendGeneration = null) },
            { it.copy(ownerId = "replacement-page:focus:settings") },
            { it.copy(ownerId = null, eligible = false) },
            { it.copy(eligible = false) },
        )
        for (change in changes) Fixture().use { fixture ->
            fixture.show()
            fixture.holdReplies = true
            val changed = fixture.scope.async(start = CoroutineStart.UNDISPATCHED) { fixture.client.rotary(step(1)) }
            val request = fixture.requests.last()
            fixture.context = change(fixture.context)
            fixture.client.contextChanged()
            assertTrue(fixture.client.busy)
            assertFalse(request.future.isCancelled)
            assertFalse(fixture.client.change(1))
            fixture.acknowledge(request)
            fixture.idle()
            assertFalse(completed(changed))
            assertEquals(listOf(5), fixture.port.writes)
            assertNull(fixture.client.state.value.snapshot)
            if (fixture.context.eligible) {
                assertEquals(MediaVolumeCommands.read, fixture.requests.last().command)
                assertTrue(fixture.client.busy)
                fixture.acknowledge(fixture.requests.last())
                fixture.idle()
                assertEquals(5, fixture.client.state.value.current)
            } else assertFalse(fixture.client.busy)
        }
    }

    @Test
    fun aPausedRouteRejectsTheOldAbsoluteDraftAndFreshReadDisablesOnlyAbsoluteInput() = Fixture().use { fixture ->
        fixture.show()
        fixture.holdExecution = true
        assertTrue(fixture.client.setProgress(0.4))
        val request = fixture.requests.last()
        fixture.routing.route.setPlaybackActive(false)
        fixture.execute(request)
        fixture.holdExecution = false
        fixture.idle()
        assertEquals(ServiceMediaVolumeIssue.OUTPUT_CHANGED, fixture.result(request).issue)
        assertFalse(fixture.result(request).writeAttempted)
        assertFalse(fixture.client.state.value.absoluteAdjustable)
        assertTrue(fixture.client.state.value.relativeAdjustable)
        assertTrue(fixture.port.writes.isEmpty())
    }

    @Test
    fun theOnlyPendingReadBlocksOtherReadsAndWritesUntilItsReply() = Fixture().use { fixture ->
        fixture.holdReplies = true
        fixture.client.onVisible()
        repeat(6) {
            fixture.client.sample()
            fixture.client.refresh()
            assertFalse(fixture.client.change(1))
            assertNull(fixture.client.begin())
        }
        assertEquals(1, fixture.readCount)
        assertTrue(fixture.client.busy)
        fixture.acknowledge(fixture.requests.single())
        fixture.idle()
        assertFalse(fixture.client.busy)
        assertTrue(fixture.port.writes.isEmpty())
    }

    @Test
    fun aReadCanFollowFreshRouteExtrasWithoutReusingAnAbsoluteDraft() = Fixture().use { fixture ->
        fixture.holdExecution = true
        fixture.client.onVisible()
        val request = fixture.requests.single()
        fixture.source.deviceId = 8
        fixture.publishActualRoute()
        fixture.execute(request)
        fixture.idle()
        assertEquals(fixture.context.route, fixture.client.state.value.snapshot?.route)
        assertNotEquals(request.context.route, fixture.client.state.value.snapshot?.route)
        assertEquals(1, fixture.readCount)
        assertFalse(fixture.client.busy)
    }

    @Test
    fun anUnnotifiedUiOwnerCannotReuseAnAlreadyIdleSnapshot() = Fixture().use { fixture ->
        fixture.show()
        fixture.context = fixture.context.copy(ownerId = "replacement-page:focus:settings")
        assertNull(fixture.client.begin())
        assertNull(fixture.client.state.value.snapshot)
        assertTrue(fixture.port.writes.isEmpty())
        fixture.client.sample()
        fixture.idle()
        assertTrue(fixture.client.state.value.absoluteAdjustable)
    }

    @Test
    fun callerCancellationDoesNotCancelTheSentFutureOrReleaseItsSlotEarly() = runBlocking {
        Fixture().use { fixture ->
            fixture.show()
            fixture.holdReplies = true
            val changed = fixture.scope.async(start = CoroutineStart.UNDISPATCHED) { fixture.client.rotary(step(1)) }
            val request = fixture.requests.last()
            changed.cancel()
            assertFalse(changed.isCompleted)
            assertTrue(fixture.client.busy)
            assertFalse(request.future.isCancelled)
            assertFalse(fixture.client.change(-1))
            fixture.acknowledge(request)
            fixture.idle()
            assertTrue(changed.isCompleted)
            changed.join()
            assertTrue(changed.isCancelled)
            assertEquals(listOf(5), fixture.port.writes)
            assertEquals(MediaVolumeCommands.read, fixture.requests.last().command)
        }
    }

    @Test
    fun scopeCancellationKeepsOnlyTheOriginalReplyUntilItFinishes() = runBlocking {
        Fixture().use { fixture ->
            fixture.show()
            fixture.holdReplies = true
            val changed = fixture.scope.async(start = CoroutineStart.UNDISPATCHED) { fixture.client.rotary(step(1)) }
            val request = fixture.requests.last()
            fixture.ownerJob.cancel()
            assertFalse(changed.isCompleted)
            assertTrue(fixture.client.busy)
            assertFalse(request.future.isCancelled)
            fixture.acknowledge(request)
            fixture.idle()
            assertTrue(changed.isCompleted)
            changed.join()
            assertTrue(changed.isCancelled)
            assertFalse(fixture.client.busy)
            assertNull(fixture.client.state.value.snapshot)
            assertEquals(2, fixture.requests.size)
        }
    }

    @Test
    fun aReplyDeadlineRequestsOneConnectionReleaseWithoutRetryingOrClaimingSuccess() = runBlocking {
        Fixture().use { fixture ->
            fixture.show()
            fixture.holdReplies = true
            val changed = fixture.scope.async(start = CoroutineStart.UNDISPATCHED) { fixture.client.rotary(step(1)) }
            val request = fixture.requests.last()
            fixture.advance(ServiceMediaVolumeClient.REPLY_DEADLINE_MS - 1L)
            assertTrue(fixture.releaseRequests.isEmpty())
            fixture.advance(1L)
            assertEquals(listOf(request.context.connectionGeneration), fixture.releaseRequests)
            assertEquals(ServiceMediaVolumeIssue.UNAVAILABLE, fixture.client.state.value.issue)
            assertFalse(changed.isCompleted)
            assertTrue(fixture.client.busy)
            assertFalse(request.future.isCancelled)
            repeat(4) { fixture.client.sample() }
            fixture.advance(30_000L)
            assertEquals(1, fixture.releaseRequests.size)
            assertEquals(2, fixture.requests.size)
            fixture.acknowledge(request)
            fixture.idle()
            assertFalse(completed(changed))
            assertFalse(fixture.client.busy)
            assertEquals(ServiceMediaVolumeIssue.UNAVAILABLE, fixture.client.state.value.issue)
            fixture.client.sample()
            assertEquals(2, fixture.requests.size)
            fixture.holdReplies = false
            fixture.client.refresh()
            fixture.idle()
            assertEquals(3, fixture.requests.size)
            assertEquals(5, fixture.client.state.value.current)
        }
    }

    @Test
    fun releaseCallbackFailureDoesNotCrashOrFalselyFreeTheUnsettledRequest() = Fixture().use { fixture ->
        fixture.show()
        fixture.holdReplies = true
        fixture.onRelease = { throw IllegalStateException("Controlled host release failure") }
        assertTrue(fixture.client.change(1))
        val request = fixture.requests.last()
        fixture.advance(ServiceMediaVolumeClient.REPLY_DEADLINE_MS)
        assertEquals(1, fixture.releaseRequests.size)
        assertTrue(fixture.client.busy)
        assertEquals(ServiceMediaVolumeIssue.UNAVAILABLE, fixture.client.state.value.issue)
        assertFalse(request.future.isCancelled)
        fixture.acknowledge(request)
        fixture.idle()
        assertFalse(fixture.client.busy)
        assertEquals(ServiceMediaVolumeIssue.UNAVAILABLE, fixture.client.state.value.issue)
    }

    @Test
    fun hiddenAndClosedClientsStopNewReadsAndRejectTheLateReply() = runBlocking {
        for (close in listOf(false, true)) Fixture().use { fixture ->
            fixture.show()
            fixture.holdReplies = true
            val changed = fixture.scope.async(start = CoroutineStart.UNDISPATCHED) { fixture.client.rotary(step(1)) }
            val request = fixture.requests.last()
            if (close) fixture.client.close() else fixture.client.onHidden()
            repeat(4) { fixture.client.refresh(); fixture.client.sample(); fixture.client.contextChanged() }
            assertEquals(2, fixture.requests.size)
            assertTrue(fixture.client.busy)
            assertFalse(request.future.isCancelled)
            fixture.acknowledge(request)
            fixture.idle()
            assertFalse(completed(changed))
            assertFalse(fixture.client.busy)
            assertNull(fixture.client.state.value.snapshot)
            assertEquals(ServiceMediaVolumeIssue.UNAVAILABLE, fixture.client.state.value.issue)
            assertTrue(fixture.releaseRequests.isEmpty())
        }
    }

    @Test
    fun retirementAfterDispatchCannotPretendToCancelTheRemoteWrite() {
        for (close in listOf(false, true)) Fixture().use { fixture ->
            fixture.show()
            fixture.holdExecution = true
            assertTrue(fixture.client.setProgress(0.4))
            val request = fixture.requests.last()
            if (close) fixture.client.close() else fixture.client.onHidden()
            assertTrue(fixture.client.busy)
            assertFalse(request.future.isCancelled)
            assertTrue(fixture.port.writes.isEmpty())
            fixture.execute(request)
            fixture.idle()
            assertEquals(listOf(6), fixture.port.writes)
            assertTrue(fixture.result(request).accepted && fixture.result(request).writeAttempted)
            assertFalse(fixture.client.busy)
            assertNull(fixture.client.state.value.snapshot)
            assertEquals(ServiceMediaVolumeIssue.UNAVAILABLE, fixture.client.state.value.issue)
            assertEquals(1, fixture.commands.count { it == MediaVolumeCommands.set })
        }
    }

    @Test
    fun closingDoesNotDropTheDeadlineForAnUnfinishedSessionRequest() = Fixture().use { fixture ->
        fixture.show()
        fixture.holdReplies = true
        assertTrue(fixture.client.change(1))
        fixture.client.close()
        fixture.advance(ServiceMediaVolumeClient.REPLY_DEADLINE_MS)
        assertEquals(listOf(1L), fixture.releaseRequests)
        assertTrue(fixture.client.busy)
        assertEquals(2, fixture.requests.size)
    }

    @Test
    fun malformedDeniedAndExceptionalRepliesNeverTurnIntoChangedSuccess() = runBlocking {
        val failures: List<(SettableFuture<SessionResult>) -> Unit> = listOf(
            { it.set(SessionResult(SessionResult.RESULT_SUCCESS, Bundle.EMPTY)) },
            { it.set(SessionResult(SessionError.ERROR_PERMISSION_DENIED)) },
            { it.set(SessionResult(SessionError.ERROR_BAD_VALUE)) },
            { it.setException(IllegalStateException("Controlled transport failure")) },
        )
        for (complete in failures) Fixture().use { fixture ->
            fixture.show()
            fixture.holdExecution = true
            val changed = fixture.scope.async(start = CoroutineStart.UNDISPATCHED) { fixture.client.rotary(step(1)) }
            complete(fixture.requests.last().future)
            fixture.idle()
            assertFalse(completed(changed))
            assertEquals(ServiceMediaVolumeIssue.UNAVAILABLE, fixture.client.state.value.issue)
            assertFalse(fixture.client.state.value.absoluteAdjustable)
            assertFalse(fixture.client.busy)
            assertTrue(fixture.port.writes.isEmpty())
            fixture.client.sample()
            assertEquals(2, fixture.requests.size)
        }
    }

    @Test
    fun anUnavailableOrThrowingDispatcherHasNoPlatformOperation() = Fixture().use { fixture ->
        fixture.show()
        fixture.dispatchAvailable = false
        assertTrue(fixture.client.change(1))
        assertFalse(fixture.client.busy)
        assertEquals(ServiceMediaVolumeIssue.UNAVAILABLE, fixture.client.state.value.issue)
        fixture.dispatchAvailable = true
        fixture.dispatchFailure = IllegalStateException("Controlled disconnected controller")
        assertTrue(fixture.client.change(-1))
        assertFalse(fixture.client.busy)
        assertEquals(ServiceMediaVolumeIssue.UNAVAILABLE, fixture.client.state.value.issue)
        assertEquals(1, fixture.requests.size)
        assertTrue(fixture.port.writes.isEmpty())
    }

    @Test
    fun aSynchronousPendingObserverCanRetireBeforeTheDispatcherIsEntered() = runBlocking {
        Fixture().use { fixture ->
            fixture.show()
            val observer = launch(context = Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                fixture.client.state.first { state ->
                    if (state.phase != MediaVolumeClientPhase.COMMITTING) false else {
                        fixture.client.invalidate()
                        true
                    }
                }
            }
            try {
                val changed = fixture.scope.async(start = CoroutineStart.UNDISPATCHED) { fixture.client.rotary(step(1)) }
                fixture.idle()
                assertTrue(observer.isCompleted)
                observer.join()
                assertFalse(completed(changed))
                assertEquals(1, fixture.requests.size)
                assertTrue(fixture.port.writes.isEmpty())
                assertFalse(fixture.client.busy)
            } finally { observer.cancelAndJoin() }
        }
    }

    @Test
    fun synchronousInvalidationAtIdleCannotPublishTheCompletedRequestsSuccess() = runBlocking {
        Fixture().use { fixture ->
            fixture.show()
            fixture.holdReplies = true
            val changed = fixture.scope.async(start = CoroutineStart.UNDISPATCHED) { fixture.client.rotary(step(1)) }
            val request = fixture.requests.last()
            val observer = launch(context = Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                fixture.client.state.first { state ->
                    if (state.phase != MediaVolumeClientPhase.IDLE) false else {
                        fixture.client.invalidate()
                        true
                    }
                }
            }
            try {
                fixture.acknowledge(request)
                fixture.idle()
                assertTrue(observer.isCompleted)
                observer.join()
                assertFalse(completed(changed))
                assertNull(fixture.client.state.value.snapshot)
                assertEquals(listOf(5), fixture.port.writes)
            } finally { observer.cancelAndJoin() }
        }
    }

    @Test
    fun aSynchronousIdleObserverCannotOccupyTheSlotBeforeOriginalSettlement() = runBlocking {
        Fixture().use { fixture ->
            fixture.show()
            fixture.holdReplies = true
            val changed = fixture.scope.async(start = CoroutineStart.UNDISPATCHED) { fixture.client.rotary(step(1)) }
            val request = fixture.requests.last()
            val observer = launch(context = Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                fixture.client.state.first { state ->
                    if (state.phase != MediaVolumeClientPhase.IDLE) false else {
                        assertTrue(fixture.client.busy)
                        assertFalse(fixture.client.change(1))
                        assertNull(fixture.client.begin())
                        true
                    }
                }
            }
            try {
                fixture.acknowledge(request)
                fixture.idle()
                assertTrue(observer.isCompleted)
                observer.join()
                assertTrue(completed(changed))
                assertFalse(fixture.client.busy)
                assertEquals(2, fixture.requests.size)
                assertEquals(listOf(5), fixture.port.writes)
            } finally { observer.cancelAndJoin() }
        }
    }

    @Test
    fun aSuccessfulLookingReplyForAnotherRouteCannotProduceSuccess() = runBlocking {
        Fixture().use { fixture ->
            fixture.show()
            fixture.publishExtras = false
            fixture.holdReplies = true
            val observedRoute = fixture.context.route
            val changed = fixture.scope.async(start = CoroutineStart.UNDISPATCHED) { fixture.client.rotary(step(1)) }
            val request = fixture.requests.last()
            val realResult = fixture.result(request)
            val stale = realResult.copy(snapshot = checkNotNull(realResult.snapshot).copy(
                route = PlaybackRouteSessionState(UUID.randomUUID().toString(), true),
            ))
            fixture.acknowledge(request, SessionResult(SessionResult.RESULT_SUCCESS, MediaVolumeCommands.resultArguments(stale)))
            fixture.idle()
            assertFalse(completed(changed))
            assertEquals(listOf(5), fixture.port.writes)
            assertNull(fixture.client.state.value.snapshot)
            assertEquals(ServiceMediaVolumeIssue.OUTPUT_CHANGED, fixture.client.state.value.issue)
            assertEquals(MediaVolumeCommands.read, fixture.requests.last().command)
            fixture.acknowledge(fixture.requests.last())
            fixture.idle()
            assertEquals(observedRoute, fixture.client.state.value.snapshot?.route)
            assertEquals(5, fixture.client.state.value.current)
        }
    }

    @Test
    fun persistentReadAndExtrasMismatchNeedsExplicitRetryAfterOneCorrection() = Fixture().use { fixture ->
        fixture.publishExtras = false
        fixture.holdReplies = true
        fixture.client.onVisible()
        repeat(2) {
            val request = fixture.requests.last()
            val result = fixture.result(request)
            val mismatch = result.copy(snapshot = checkNotNull(result.snapshot).copy(
                route = PlaybackRouteSessionState(UUID.randomUUID().toString(), true),
            ))
            fixture.acknowledge(request, SessionResult(SessionResult.RESULT_SUCCESS, MediaVolumeCommands.resultArguments(mismatch)))
            fixture.idle()
        }
        assertEquals(2, fixture.readCount)
        assertFalse(fixture.client.busy)
        assertNull(fixture.client.state.value.snapshot)
        assertEquals(ServiceMediaVolumeIssue.OUTPUT_CHANGED, fixture.client.state.value.issue)
        repeat(5) { fixture.client.sample() }
        assertEquals(2, fixture.readCount)
        fixture.holdReplies = false
        fixture.client.refresh()
        fixture.idle()
        assertEquals(3, fixture.readCount)
        assertEquals(fixture.context.route, fixture.client.state.value.snapshot?.route)
    }

    @Test
    fun unchangedContextAndSamplesDoNotRetireAReadyDraft() = Fixture().use { fixture ->
        fixture.show()
        val id = checkNotNull(fixture.client.begin())
        val owner = fixture.client.state.value.ownerId
        repeat(5) { fixture.client.contextChanged(); fixture.client.sample() }
        assertEquals(id, fixture.client.state.value.gestureId)
        assertEquals(owner, fixture.client.state.value.ownerId)
        assertEquals(1, fixture.readCount)
        assertTrue(fixture.client.drag(id, 0.4))
        assertTrue(fixture.client.release(id))
        fixture.idle()
        assertEquals(listOf(6), fixture.port.writes)
    }

    @Test
    fun everyCommandAndStateMutationRequiresTheClientLooper() = runBlocking {
        Fixture().use { fixture ->
            fixture.show()
            val count = fixture.requests.size
            val operations: List<() -> Unit> = listOf(
                { fixture.client.onVisible() }, { fixture.client.onHidden() },
                { fixture.client.contextChanged() }, { fixture.client.refresh() }, { fixture.client.sample() },
                { fixture.client.begin() }, { fixture.client.drag("obsolete", 0.5) },
                { fixture.client.release("obsolete") }, { fixture.client.cancel("obsolete") },
                { fixture.client.change(1) }, { fixture.client.setProgress(0.5) },
                { fixture.client.invalidate() }, { fixture.client.close() },
            )
            for (operation in operations) withContext(Dispatchers.Default) {
                try { operation() }
                catch (_: IllegalStateException) { return@withContext }
                throw AssertionError("The volume client must use its owner Looper")
            }
            withContext(Dispatchers.Default) {
                try { fixture.client.rotary(step(1)) }
                catch (_: IllegalStateException) { return@withContext }
                throw AssertionError("Rotary IPC must use the volume client Looper")
            }
            assertEquals(count, fixture.requests.size)
            assertTrue(fixture.port.writes.isEmpty())
        }
    }

    private suspend fun completed(request: Deferred<Boolean>): Boolean {
        assertTrue("The controlled reply must settle before awaiting its outcome", request.isCompleted)
        return request.await()
    }

    private class Fixture(active: Boolean = true) : AutoCloseable {
        val ownerJob = SupervisorJob()
        val scope = CoroutineScope(ownerJob + Dispatchers.Unconfined)
        val routing = RouteTestFixture()
        val source = routing.attach()
        val port = RecordingMusicVolume()
        private val volume = ServiceMediaVolume(port, routing.route::read)
        var context = MediaVolumeClientContext(1L, "page:focus:settings", 1L, PlaybackRouteSessionState(), eligible = true)
        var holdExecution = false
        var holdReplies = false
        var publishExtras = true
        var dispatchAvailable = true
        var dispatchFailure: RuntimeException? = null
        var onRelease: ((Long) -> Unit)? = null
        val releaseRequests = mutableListOf<Long>()
        val requests = mutableListOf<Request>()
        val commands: List<SessionCommand> get() = requests.map { it.command }
        val readCount: Int get() = commands.count { it == MediaVolumeCommands.read }
        private val handler = ServiceMediaVolumeCommandHandler(OWNER_UID, volume::read, volume::setAbsolute,
            volume::adjust, onResult = {
                if (publishExtras) {
                    context = context.copy(route = routing.route.cachedState())
                    client.contextChanged()
                }
            })
        val client = ServiceMediaVolumeClient(scope, { context },
            dispatch = { captured, command, arguments ->
                dispatchFailure?.let { throw it }
                if (!dispatchAvailable) null else {
                    val request = Request(captured, command, Bundle(arguments))
                    requests.add(request)
                    if (!holdExecution) execute(request)
                    request.future
                }
            },
            onUnsettledReply = { generation ->
                releaseRequests.add(generation)
                onRelease?.invoke(generation)
            })

        init {
            routing.route.setPlaybackActive(active)
            context = context.copy(route = routing.route.cachedState())
        }

        fun show() { client.onVisible(); idle() }
        fun idle() = shadowOf(Looper.getMainLooper()).idle()
        fun advance(milliseconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))
        fun publishActualRoute() {
            context = context.copy(route = routing.route.read())
            client.contextChanged()
        }
        fun execute(request: Request) {
            check(request.reply == null)
            request.reply = checkNotNull(handler.handle(OWNER_UID, request.command.customAction, request.arguments))
            if (!holdReplies) acknowledge(request)
        }
        fun acknowledge(request: Request, reply: SessionResult = checkNotNull(request.reply)) {
            assertTrue(request.future.set(reply))
        }
        fun result(request: Request): MediaVolumeResult = MediaVolumeCommands.readResult(checkNotNull(request.reply).extras)
        override fun close() {
            client.close()
            requests.toList().filter { !it.future.isDone }.forEach {
                it.future.setException(IllegalStateException("Controlled fixture teardown"))
            }
            idle()
            ownerJob.cancel()
            routing.route.close()
        }
    }

    private class Request(val context: MediaVolumeClientContext, val command: SessionCommand, val arguments: Bundle) {
        val future: SettableFuture<SessionResult> = SettableFuture.create()
        var reply: SessionResult? = null
    }

    private companion object {
        const val OWNER_UID = 101
        fun step(delta: Int) = RotaryStep("page-focus", 1L, RotaryTarget.MEDIA_VOLUME, delta, 1_000L)
    }
}
