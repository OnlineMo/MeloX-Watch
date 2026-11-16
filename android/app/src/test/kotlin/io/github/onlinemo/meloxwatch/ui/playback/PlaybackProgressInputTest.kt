package io.github.onlinemo.meloxwatch.ui.playback

import io.github.onlinemo.meloxwatch.model.QueueSeekBinding
import io.github.onlinemo.meloxwatch.model.QueueSeekRequest
import io.github.onlinemo.meloxwatch.playback.input.SeekGestureMode
import io.github.onlinemo.meloxwatch.playback.input.SeekGestureSurface
import io.github.onlinemo.meloxwatch.playback.input.SeekProgressSettings
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackProgressInputTest {
    @Test(timeout = 30000L) fun delayedBindingKeepsOnlyTheLatestFractionAndOneCompletedRelease() = runBlocking {
        val fixture = Fixture(this, holdBinding = true)
        try {
            val id = requireNotNull(fixture.input.begin())
            assertEquals(1, fixture.client.acquisitions)
            assertTrue(fixture.input.drag(id, 0.3))
            assertTrue(fixture.input.drag(id, 0.8))
            assertEquals(0.8, requireNotNull(fixture.input.state.value.previewFraction), 0.0)
            assertNull(fixture.input.state.value.previewPositionMs)
            assertTrue(fixture.client.requests.isEmpty())
            fixture.context = fixture.context.copy(positionMs = 11_000L)
            assertTrue(fixture.input.release(id))
            assertFalse(fixture.input.release(id))
            assertFalse(fixture.input.drag(id, 0.9))
            fixture.client.binding.complete(BINDING)
            val request = fixture.request()
            assertSame(BINDING, request.binding)
            assertEquals(48_000L, request.positionMs)
            assertFalse(request.explicitPlay)
            fixture.idle()
            assertEquals(1, fixture.client.acquisitions)
            assertEquals(1, fixture.client.requests.size)
        } finally { fixture.close() }
    }

    @Test(timeout = 30000L) fun cancelledUncooperativeBindingBlocksNewRequestsThenDropsTheLateResult() = runBlocking {
        val fixture = Fixture(this, holdBinding = true, ignoreBindingCancellation = true)
        try {
            val previous = requireNotNull(fixture.input.begin())
            fixture.input.drag(previous, 0.4)
            fixture.input.cancel(previous)
            assertEquals(PlaybackProgressInputPhase.CANCELLING, fixture.input.state.value.phase)
            assertNull(fixture.input.state.value.previewFraction)
            assertNull(fixture.input.begin())
            assertFalse(fixture.input.release(previous))
            assertEquals(1, fixture.client.acquisitions)
            fixture.client.binding.complete(BINDING)
            fixture.idle()
            assertTrue(fixture.client.requests.isEmpty())

            fixture.context = fixture.context.copy(ownerId = "new-playback-owner")
            val current = requireNotNull(fixture.input.begin())
            assertNotEquals(previous, current)
            fixture.input.cancel(previous)
            assertTrue(fixture.input.drag(current, 0.7))
            assertTrue(fixture.input.release(current))
            assertEquals(42_000L, fixture.request().positionMs)
            fixture.idle()
            assertEquals(2, fixture.client.acquisitions)
            assertEquals(1, fixture.client.requests.size)
        } finally { fixture.close() }
    }

    @Test(timeout = 30000L) fun ownerDriftBeforeALateBindingCannotDispatchTheOldCompletedGesture() = runBlocking {
        val fixture = Fixture(this, holdBinding = true)
        try {
            val id = requireNotNull(fixture.input.begin())
            fixture.input.drag(id, 0.6)
            fixture.input.release(id)
            fixture.context = fixture.context.copy(ownerId = "replacement-owner")
            fixture.client.binding.complete(BINDING)
            fixture.idle()
            assertTrue(fixture.client.requests.isEmpty())
            assertNull(fixture.input.state.value.previewPositionMs)
            assertFalse(fixture.input.release(id))
        } finally { fixture.close() }
    }

    @Test(timeout = 30000L) fun hiddenUnfocusedBackgroundAndChangedSavedControlsDiscardPendingCompletion() = runBlocking {
        val changed = listOf(CONTEXT.copy(ownerId = null), CONTEXT.copy(foreground = false), CONTEXT.copy(canSeek = false),
            CONTEXT.copy(settings = SeekProgressSettings(showMainProgress = false)),
            CONTEXT.copy(settings = SeekProgressSettings(mode = SeekGestureMode.TAP_TO_SEEK)),
            CONTEXT.copy(controlsStateKey = "saving:normal:drag"),
            CONTEXT.copy(controlsStateKey = "saved:reversed:drag"))
        for (context in changed) {
            val fixture = Fixture(this, holdBinding = true)
            try {
                val id = requireNotNull(fixture.input.begin())
                fixture.input.drag(id, 0.6)
                fixture.input.release(id)
                fixture.context = context
                fixture.client.binding.complete(BINDING)
                fixture.idle()
                assertTrue(fixture.client.requests.isEmpty())
                assertEquals(1, fixture.client.acquisitions)
            } finally { fixture.close() }
        }
    }

    @Test(timeout = 30000L) fun contextChangeInvalidatesAReadyPreviewWithoutPrefetchingAnotherBinding() = runBlocking {
        val fixture = Fixture(this)
        try {
            val id = requireNotNull(fixture.input.begin())
            fixture.input.drag(id, 0.5)
            assertEquals(30_000L, fixture.input.state.value.previewPositionMs)
            fixture.context = fixture.context.copy(controlsStateKey = "failed-save:normal:drag")
            fixture.input.contextChanged()
            assertNull(fixture.input.state.value.previewFraction)
            assertFalse(fixture.input.release(id))
            fixture.idle()
            assertEquals(1, fixture.client.acquisitions)
            assertTrue(fixture.client.requests.isEmpty())
        } finally { fixture.close() }
    }

    @Test(timeout = 30000L) fun synchronousPreviewObserverRetiresUndispatchedOwnerBeforeJobAssignment() = runBlocking {
        val fixture = Fixture(this)
        var retiredId: String? = null
        val observer = launch(context = Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            fixture.input.state.first { state ->
                if (state.phase != PlaybackProgressInputPhase.PREVIEW) false else {
                    retiredId = state.gestureId
                    fixture.input.invalidate()
                    true
                }
            }
        }
        try {
            assertNull(fixture.input.begin())
            observer.join()
            fixture.settleChildren()
            assertFalse(fixture.input.release(requireNotNull(retiredId)))
            assertEquals(PlaybackProgressInputPhase.IDLE, fixture.input.state.value.phase)
            assertNull(fixture.input.state.value.previewFraction)
            assertTrue(fixture.client.requests.isEmpty())
            assertEquals(1, fixture.client.acquisitions)

            assertTrue(fixture.input.setProgress(0.5))
            assertEquals(30_000L, fixture.request().positionMs)
            fixture.idle()
            assertEquals(2, fixture.client.acquisitions)
            assertEquals(1, fixture.client.requests.size)
        } finally {
            observer.cancelAndJoin()
            fixture.close()
        }
    }

    @Test(timeout = 30000L) fun everyBindingIdentityFieldIsCheckedAgainBeforeCommit() = runBlocking {
        val changed = listOf(BINDING.copy(previewId = "other-preview"), BINDING.copy(sessionId = "other-session"),
            BINDING.copy(backendGeneration = 3L), BINDING.copy(generationId = "other-queue"),
            BINDING.copy(queueRevision = 4L), BINDING.copy(queueEntryId = "other-entry"),
            BINDING.copy(locationId = "other-location"), BINDING.copy(contentRevision = 5L),
            BINDING.copy(durationMs = 61_000L))
        for (binding in changed) {
            val fixture = Fixture(this)
            try {
                val id = requireNotNull(fixture.input.begin())
                fixture.input.drag(id, 0.5)
                fixture.expectedBinding = binding
                assertFalse(fixture.input.release(id))
                fixture.idle()
                assertTrue(fixture.client.requests.isEmpty())
                assertEquals(1, fixture.client.acquisitions)
            } finally { fixture.close() }
        }
    }

    @Test(timeout = 30000L) fun invalidFractionsRetireThePreviewAndCannotBeReplayedByLateRelease() = runBlocking {
        for (fraction in listOf(-0.1, 1.1, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            val fixture = Fixture(this, holdBinding = true)
            try {
                val id = requireNotNull(fixture.input.begin())
                fixture.input.drag(id, 0.4)
                assertFalse(fixture.input.drag(id, fraction))
                fixture.input.cancel(id)
                assertFalse(fixture.input.release(id))
                fixture.idle()
                assertEquals(PlaybackProgressInputIssue.INVALID_FRACTION, fixture.input.state.value.issue)
                assertEquals(CONTEXT.ownerId, fixture.input.state.value.issueOwnerId)
                assertNull(fixture.input.state.value.previewFraction)
                assertTrue(fixture.client.requests.isEmpty())
            } finally { fixture.close() }
        }
    }

    @Test(timeout = 30000L) fun completingTheWrongGestureModeCancelsWithoutSeek() = runBlocking {
        val drag = Fixture(this)
        try {
            val id = requireNotNull(drag.input.begin())
            assertFalse(drag.input.tap(id, 0.6))
            drag.idle()
            assertTrue(drag.client.requests.isEmpty())
        } finally { drag.close() }
        val tap = Fixture(this, initialContext = CONTEXT.copy(settings = SeekProgressSettings(mode = SeekGestureMode.TAP_TO_SEEK)))
        try {
            val id = requireNotNull(tap.input.begin())
            assertFalse(tap.input.drag(id, 0.6))
            assertFalse(tap.input.release(id))
            tap.idle()
            assertTrue(tap.client.requests.isEmpty())
        } finally { tap.close() }
    }

    @Test(timeout = 30000L) fun unavailableCapabilityAndMissingBindingNeverInventASeekableDuration() = runBlocking {
        val disabled = Fixture(this, initialContext = CONTEXT.copy(canSeek = false))
        try {
            assertNull(disabled.input.begin())
            assertFalse(disabled.input.setProgress(0.5))
            assertEquals(0, disabled.client.acquisitions)
        } finally { disabled.close() }
        val missing = Fixture(this, returnedBinding = null)
        try {
            assertNull(missing.input.begin())
            assertEquals(PlaybackProgressInputIssue.BINDING_UNAVAILABLE, missing.input.state.value.issue)
            assertEquals(CONTEXT.ownerId, missing.input.state.value.issueOwnerId)
            assertNull(missing.input.state.value.previewPositionMs)
            assertEquals(1, missing.client.acquisitions)
            assertTrue(missing.client.requests.isEmpty())
        } finally { missing.close() }
    }

    @Test(timeout = 30000L) fun accessibilityIntentUsesTheRealBindingInBothSavedModes() = runBlocking {
        val actual = BINDING.copy(durationMs = 40_000L)
        for (mode in SeekGestureMode.entries) {
            val fixture = Fixture(this, initialContext = CONTEXT.copy(settings = SeekProgressSettings(mode = mode)), actualBinding = actual)
            try {
                assertTrue(fixture.input.setProgress(0.75))
                val request = fixture.request()
                assertSame(actual, request.binding)
                assertEquals(30_000L, request.positionMs)
                assertFalse(request.explicitPlay)
                fixture.idle()
                assertEquals(1, fixture.client.requests.size)
            } finally { fixture.close() }
        }
    }

    @Test(timeout = 30000L) fun hidingTheMainProgressPreservesAnIndependentInputOwner() = runBlocking {
        val hidden = CONTEXT.copy(settings = SeekProgressSettings(showMainProgress = false), controlsStateKey = "saved:normal:drag:main-hidden")
        val main = Fixture(this, initialContext = hidden)
        try {
            assertNull(main.input.begin())
            assertEquals(0, main.client.acquisitions)
        } finally { main.close() }
        val independent = Fixture(this, initialContext = hidden.copy(ownerId = "independent-controls"),
            surface = SeekGestureSurface.INDEPENDENT_CONTROLS)
        try {
            assertTrue(independent.input.setProgress(0.5))
            assertEquals(30_000L, independent.request().positionMs)
            independent.idle()
            assertEquals(1, independent.client.acquisitions)
            assertEquals(1, independent.client.requests.size)
        } finally { independent.close() }
    }

    @Test(timeout = 30000L) fun aTapNeedsItsCompletedActionAndDuplicateTapsCannotChangeTheTarget() = runBlocking {
        val fixture = Fixture(this, initialContext = CONTEXT.copy(settings = SeekProgressSettings(mode = SeekGestureMode.TAP_TO_SEEK)))
        try {
            val id = requireNotNull(fixture.input.begin())
            assertTrue(fixture.client.requests.isEmpty())
            assertTrue(fixture.input.tap(id, 0.4))
            assertFalse(fixture.input.tap(id, 0.8))
            assertEquals(24_000L, fixture.request().positionMs)
            fixture.idle()
            assertEquals(1, fixture.client.requests.size)
        } finally { fixture.close() }
    }

    @Test(timeout = 30000L) fun uiAndCoreDraftsAreConsumedBeforeTheOneDispatch() = runBlocking {
        val fixture = Fixture(this, holdSeek = true)
        try {
            val id = requireNotNull(fixture.input.begin())
            fixture.beforeDispatch = {
                assertEquals(PlaybackProgressInputPhase.COMMITTING, fixture.input.state.value.phase)
                assertNull(fixture.input.state.value.previewFraction)
                assertNull(fixture.input.state.value.previewPositionMs)
                assertFalse(fixture.input.release(id))
                assertFalse(fixture.input.drag(id, 0.9))
                assertFalse(fixture.input.tap(id, 0.9))
                assertNull(fixture.input.begin())
            }
            fixture.input.drag(id, 0.5)
            fixture.input.release(id)
            fixture.request()
            assertEquals(1, fixture.client.requests.size)
            fixture.client.seekResult.complete(true)
            fixture.idle()
            assertFalse(fixture.input.release(id))
            assertEquals(1, fixture.client.requests.size)
        } finally { fixture.close() }
    }

    @Test(timeout = 30000L) fun cancellationDuringDispatchDropsLateAcknowledgementWithoutReplayingTheRequest() = runBlocking {
        val fixture = Fixture(this, holdSeek = true, ignoreSeekCancellation = true)
        try {
            fixture.input.setProgress(0.5)
            fixture.request()
            fixture.input.invalidate()
            fixture.context = fixture.context.copy(ownerId = "new-owner")
            assertNull(fixture.input.begin())
            assertEquals(PlaybackProgressInputPhase.CANCELLING, fixture.input.state.value.phase)
            fixture.client.seekResult.complete(false)
            fixture.idle()
            assertNull(fixture.input.state.value.issue)
            assertEquals(1, fixture.client.requests.size)
            assertEquals(1, fixture.client.acquisitions)
            assertTrue(fixture.input.begin() != null)
            assertEquals(2, fixture.client.acquisitions)
        } finally { fixture.close() }
    }

    @Test(timeout = 30000L) fun retirementAfterDraftConsumptionPreventsTheExternalDispatch() = runBlocking {
        val fixture = Fixture(this)
        try {
            val id = requireNotNull(fixture.input.begin())
            fixture.input.drag(id, 0.5)
            // Models an owner/lifecycle observer retiring the input at the final context check.
            fixture.onContextRead = {
                if (fixture.input.state.value.phase == PlaybackProgressInputPhase.COMMITTING) fixture.input.invalidate()
            }
            fixture.input.release(id)
            fixture.idle()
            assertTrue(fixture.client.requests.isEmpty())
            assertFalse(fixture.input.release(id))
            assertEquals(1, fixture.client.acquisitions)
        } finally { fixture.close() }
    }

    @Test(timeout = 30000L) fun releaseWithoutMovementAndTheActualCurrentTargetRemainNoops() = runBlocking {
        val fixture = Fixture(this, initialContext = CONTEXT.copy(positionMs = 30_000L))
        try {
            val id = requireNotNull(fixture.input.begin())
            assertFalse(fixture.input.release(id))
            fixture.idle()
            assertTrue(fixture.input.setProgress(0.5))
            fixture.idle()
            assertTrue(fixture.client.requests.isEmpty())
            assertEquals(2, fixture.client.acquisitions)
        } finally { fixture.close() }
    }

    @Test(timeout = 30000L) fun requestExceptionsAndExplicitRejectionsStayTruthfulAndAllowExplicitRetry() = runBlocking {
        val bindingFailure = Fixture(this)
        try {
            bindingFailure.client.bindingError = IOException("owned binding failure")
            assertNull(bindingFailure.input.begin())
            assertEquals(PlaybackProgressInputIssue.REQUEST_FAILED, bindingFailure.input.state.value.issue)
            assertEquals(CONTEXT.ownerId, bindingFailure.input.state.value.issueOwnerId)
            assertTrue(bindingFailure.client.requests.isEmpty())
        } finally { bindingFailure.close() }
        val seekFailure = Fixture(this, holdSeek = true)
        try {
            seekFailure.client.seekError = IOException("owned seek failure")
            assertTrue(seekFailure.input.setProgress(0.4))
            seekFailure.idle()
            assertEquals(PlaybackProgressInputIssue.REQUEST_FAILED, seekFailure.input.state.value.issue)
            assertEquals(CONTEXT.ownerId, seekFailure.input.state.value.issueOwnerId)
            seekFailure.client.seekError = null
            seekFailure.client.seekResult.complete(false)
            assertTrue(seekFailure.input.setProgress(0.6))
            seekFailure.idle()
            assertEquals(PlaybackProgressInputIssue.SEEK_REJECTED, seekFailure.input.state.value.issue)
            assertEquals(CONTEXT.ownerId, seekFailure.input.state.value.issueOwnerId)
            assertEquals(2, seekFailure.client.requests.size)
            assertEquals(2, seekFailure.client.acquisitions)
        } finally { seekFailure.close() }
    }

    @Test(timeout = 30000L) fun closingTheUiOwnerMakesEvenAnUncooperativeLateBindingHarmless() = runBlocking {
        val fixture = Fixture(this, holdBinding = true, ignoreBindingCancellation = true)
        try {
            val id = requireNotNull(fixture.input.begin())
            fixture.input.drag(id, 0.7)
            fixture.input.release(id)
            fixture.input.close()
            assertNull(fixture.input.begin())
            assertEquals(PlaybackProgressInputPhase.CANCELLING, fixture.input.state.value.phase)
            fixture.client.binding.complete(BINDING)
            fixture.settleChildren()
            assertEquals(PlaybackProgressInputState(), fixture.input.state.value)
            assertTrue(fixture.client.requests.isEmpty())
        } finally { fixture.close() }
    }

    @Test(timeout = 30000L) fun lateNullBindingDropsItsIssueWhenTheCapturedOwnerHasChanged() = runBlocking {
        for (replacement in RETIRED_CONTEXTS) {
            val fixture = Fixture(this, holdBinding = true)
            try {
                val id = requireNotNull(fixture.input.begin())
                fixture.input.drag(id, 0.6)
                fixture.input.release(id)
                // The normal host notifies immediately. Late outcomes also defend against drift
                // before that notification, including the null-result branch.
                fixture.context = replacement
                fixture.client.binding.complete(null)
                fixture.idle()
                assertEquals(PlaybackProgressInputState(), fixture.input.state.value)
                assertTrue(fixture.client.requests.isEmpty())
                assertEquals(1, fixture.client.acquisitions)
            } finally { fixture.close() }
        }
    }

    @Test(timeout = 30000L) fun lateBindingExceptionCannotBecomeTheReplacementOwnersError() = runBlocking {
        val fixture = Fixture(this, holdBinding = true)
        try {
            val id = requireNotNull(fixture.input.begin())
            fixture.input.drag(id, 0.4)
            fixture.input.release(id)
            fixture.context = CONTEXT.copy(ownerId = "replacement-owner")
            fixture.client.lateBindingError = IOException("late binding failure")
            fixture.client.binding.complete(BINDING)
            fixture.idle()
            assertEquals(PlaybackProgressInputState(), fixture.input.state.value)
            assertTrue(fixture.client.requests.isEmpty())
            assertEquals(1, fixture.client.acquisitions)
        } finally { fixture.close() }
    }

    @Test(timeout = 30000L) fun lateSeekExceptionAndRejectionCannotBecomeTheReplacementOwnersError() = runBlocking {
        for (fails in listOf(false, true)) {
            val fixture = Fixture(this, holdSeek = true)
            try {
                assertTrue(fixture.input.setProgress(0.4))
                fixture.request()
                assertEquals(PlaybackProgressInputPhase.COMMITTING, fixture.input.state.value.phase)
                fixture.context = CONTEXT.copy(ownerId = "replacement-owner")
                if (fails) fixture.client.lateSeekError = IOException("late seek failure")
                fixture.client.seekResult.complete(false)
                fixture.idle()
                assertEquals(PlaybackProgressInputState(), fixture.input.state.value)
                assertEquals(1, fixture.client.requests.size)
                assertEquals(1, fixture.client.acquisitions)
            } finally { fixture.close() }
        }
    }

    @Test(timeout = 30000L) fun immediateOwnerControlAndLifecycleNotificationsClearAlreadyIdleErrors() = runBlocking {
        for (replacement in RETIRED_CONTEXTS) {
            val fixture = Fixture(this, returnedBinding = null)
            try {
                assertNull(fixture.input.begin())
                assertEquals(PlaybackProgressInputPhase.IDLE, fixture.input.state.value.phase)
                assertEquals(PlaybackProgressInputIssue.BINDING_UNAVAILABLE, fixture.input.state.value.issue)
                assertEquals(CONTEXT.ownerId, fixture.input.state.value.issueOwnerId)
                fixture.context = replacement
                fixture.input.contextChanged()
                assertEquals(PlaybackProgressInputState(), fixture.input.state.value)
                assertEquals(1, fixture.client.acquisitions)
                assertTrue(fixture.client.requests.isEmpty())
            } finally { fixture.close() }
        }
    }

    @Test(timeout = 30000L) fun positionTicksPreserveTheCurrentOwnersIdleErrorWithoutRetrying() = runBlocking {
        val fixture = Fixture(this, returnedBinding = null)
        try {
            assertNull(fixture.input.begin())
            fixture.context = CONTEXT.copy(positionMs = 11_000L)
            fixture.input.contextChanged()
            assertEquals(PlaybackProgressInputPhase.IDLE, fixture.input.state.value.phase)
            assertEquals(PlaybackProgressInputIssue.BINDING_UNAVAILABLE, fixture.input.state.value.issue)
            assertEquals(CONTEXT.ownerId, fixture.input.state.value.issueOwnerId)
            assertEquals(1, fixture.client.acquisitions)
            assertTrue(fixture.client.requests.isEmpty())
        } finally { fixture.close() }
    }

    @Test(timeout = 30000L) fun explicitInvalidationClearsAnIdleErrorWithoutAcquiringABinding() = runBlocking {
        val fixture = Fixture(this, returnedBinding = null)
        try {
            assertNull(fixture.input.begin())
            assertEquals(PlaybackProgressInputIssue.BINDING_UNAVAILABLE, fixture.input.state.value.issue)
            fixture.input.invalidate()
            assertEquals(PlaybackProgressInputState(), fixture.input.state.value)
            assertEquals(1, fixture.client.acquisitions)
            assertTrue(fixture.client.requests.isEmpty())
        } finally { fixture.close() }
    }

    @Test(timeout = 30000L) fun invalidationClearsAPendingIssueButKeepsTheUncooperativeRequestSlot() = runBlocking {
        val fixture = Fixture(this, holdBinding = true, ignoreBindingCancellation = true)
        try {
            val id = requireNotNull(fixture.input.begin())
            assertFalse(fixture.input.drag(id, Double.NaN))
            assertEquals(PlaybackProgressInputIssue.INVALID_FRACTION, fixture.input.state.value.issue)
            assertEquals(CONTEXT.ownerId, fixture.input.state.value.issueOwnerId)
            fixture.input.invalidate()
            assertEquals(PlaybackProgressInputPhase.CANCELLING, fixture.input.state.value.phase)
            assertEquals(id, fixture.input.state.value.gestureId)
            assertNull(fixture.input.state.value.issue)
            assertNull(fixture.input.state.value.issueOwnerId)
            assertNull(fixture.input.begin())
            assertEquals(1, fixture.client.acquisitions)
            fixture.client.binding.complete(BINDING)
            fixture.idle()
            assertEquals(PlaybackProgressInputState(), fixture.input.state.value)
            assertTrue(fixture.client.requests.isEmpty())
            assertTrue(fixture.input.setProgress(0.5))
            assertEquals(30_000L, fixture.request().positionMs)
            fixture.idle()
            assertEquals(2, fixture.client.acquisitions)
        } finally { fixture.close() }
    }

    @Test(timeout = 30000L) fun ownerNotificationClearsAPendingIssueWithoutFreeingTheCancelledCallback() = runBlocking {
        val fixture = Fixture(this, holdBinding = true, ignoreBindingCancellation = true)
        try {
            val id = requireNotNull(fixture.input.begin())
            assertFalse(fixture.input.drag(id, Double.NaN))
            fixture.context = CONTEXT.copy(ownerId = "replacement-owner")
            fixture.input.contextChanged()
            assertEquals(PlaybackProgressInputPhase.CANCELLING, fixture.input.state.value.phase)
            assertEquals(id, fixture.input.state.value.gestureId)
            assertNull(fixture.input.state.value.issue)
            assertNull(fixture.input.state.value.issueOwnerId)
            assertNull(fixture.input.begin())
            assertEquals(1, fixture.client.acquisitions)
            fixture.client.binding.complete(BINDING)
            fixture.idle()
            assertEquals(PlaybackProgressInputState(), fixture.input.state.value)
            assertTrue(fixture.client.requests.isEmpty())
        } finally { fixture.close() }
    }

    @Test(timeout = 30000L) fun closingDuringUncooperativeDispatchKeepsTheSlotUntilTheCallbackReturns() = runBlocking {
        val fixture = Fixture(this, holdSeek = true, ignoreSeekCancellation = true)
        try {
            assertTrue(fixture.input.setProgress(0.5))
            fixture.request()
            val id = fixture.input.state.value.gestureId
            fixture.input.close()
            fixture.context = CONTEXT.copy(ownerId = "replacement-owner")
            fixture.input.contextChanged()
            assertEquals(PlaybackProgressInputPhase.CANCELLING, fixture.input.state.value.phase)
            assertEquals(id, fixture.input.state.value.gestureId)
            assertNull(fixture.input.begin())
            assertNull(fixture.input.state.value.issue)
            assertEquals(1, fixture.client.acquisitions)
            assertEquals(1, fixture.client.requests.size)
            fixture.client.seekResult.complete(false)
            fixture.settleChildren()
            assertEquals(PlaybackProgressInputState(), fixture.input.state.value)
            assertNull(fixture.input.begin())
            assertEquals(1, fixture.client.requests.size)
        } finally { fixture.close() }
    }

    @Test(timeout = 30000L) fun finalIssueOwnershipCheckKeepsTheSlotDuringSynchronousInvalidation() = runBlocking {
        val fixture = Fixture(this, returnedBinding = null)
        var checked = false
        try {
            fixture.onContextRead = {
                if (fixture.input.state.value.phase == PlaybackProgressInputPhase.CANCELLING) {
                    checked = true
                    assertNull(fixture.input.begin())
                    fixture.input.invalidate()
                }
            }
            assertNull(fixture.input.begin())
            assertTrue(checked)
            assertEquals(PlaybackProgressInputState(), fixture.input.state.value)
            assertEquals(1, fixture.client.acquisitions)
            assertTrue(fixture.client.requests.isEmpty())
        } finally { fixture.close() }
    }

    @Test(timeout = 30000L) fun idleOwnerCheckCannotEraseANewerFailureStartedByItsContextCallback() = runBlocking {
        val fixture = Fixture(this, returnedBinding = null)
        var startedReplacement = false
        try {
            assertNull(fixture.input.begin())
            fixture.context = CONTEXT.copy(ownerId = "replacement-owner")
            fixture.onContextRead = {
                if (!startedReplacement) {
                    startedReplacement = true
                    assertNull(fixture.input.begin())
                }
            }
            fixture.input.contextChanged()
            assertTrue(startedReplacement)
            assertEquals(PlaybackProgressInputPhase.IDLE, fixture.input.state.value.phase)
            assertEquals(PlaybackProgressInputIssue.BINDING_UNAVAILABLE, fixture.input.state.value.issue)
            assertEquals("replacement-owner", fixture.input.state.value.issueOwnerId)
            assertEquals(2, fixture.client.acquisitions)
            assertTrue(fixture.client.requests.isEmpty())
        } finally { fixture.close() }
    }

    @Test(timeout = 30000L) fun contextCallbackCannotCloseOrReplaceTheOwnerBeforeAnOuterBeginClaimsTheSlot() = runBlocking {
        val closed = Fixture(this)
        try {
            closed.onContextRead = { closed.input.close() }
            assertNull(closed.input.begin())
            assertEquals(0, closed.client.acquisitions)
            assertEquals(PlaybackProgressInputState(), closed.input.state.value)
        } finally { closed.close() }
        val replacement = Fixture(this, holdBinding = true)
        var entered = false
        var replacementId: String? = null
        try {
            replacement.onContextRead = {
                if (!entered) {
                    entered = true
                    replacementId = replacement.input.begin()
                }
            }
            assertNull(replacement.input.begin())
            assertEquals(requireNotNull(replacementId), replacement.input.state.value.gestureId)
            assertEquals(PlaybackProgressInputPhase.ACQUIRING, replacement.input.state.value.phase)
            assertEquals(1, replacement.client.acquisitions)
            replacement.input.cancel(requireNotNull(replacementId))
            replacement.idle()
            assertTrue(replacement.client.requests.isEmpty())
        } finally { replacement.close() }
    }

    private class Fixture(
        owner: CoroutineScope,
        initialContext: PlaybackProgressInputContext = CONTEXT,
        actualBinding: QueueSeekBinding = BINDING,
        returnedBinding: QueueSeekBinding? = actualBinding,
        holdBinding: Boolean = false,
        holdSeek: Boolean = false,
        ignoreBindingCancellation: Boolean = false,
        ignoreSeekCancellation: Boolean = false,
        surface: SeekGestureSurface = SeekGestureSurface.MAIN_PROGRESS,
    ) {
        private val job = SupervisorJob(owner.coroutineContext[Job])
        private val scope = CoroutineScope(owner.coroutineContext + job)
        var context = initialContext
        var expectedBinding = actualBinding
        var beforeDispatch: (QueueSeekRequest) -> Unit = {}
        var onContextRead: () -> Unit = {}
        val client = ControlledClient(ignoreBindingCancellation, ignoreSeekCancellation)
        val input = PlaybackProgressInput(scope, surface, {
            onContextRead()
            context
        }, client::acquire,
            { captured, binding -> captured.ownerId == context.ownerId && binding == expectedBinding },
            {
                request -> beforeDispatch(request)
                client.seek(request)
            })

        init {
            if (!holdBinding) client.binding.complete(returnedBinding)
            if (!holdSeek) client.seekResult.complete(true)
        }

        suspend fun request() = withTimeout(10_000L) { client.seekEntered.await() }
        suspend fun idle() = withTimeout(10_000L) { input.state.first { it.phase == PlaybackProgressInputPhase.IDLE } }
        suspend fun settleChildren() = withTimeout(10_000L) { job.children.toList().forEach { it.join() } }
        suspend fun close() {
            input.close()
            client.binding.complete(null)
            client.seekResult.complete(false)
            job.cancelAndJoin()
        }
    }

    private class ControlledClient(private val ignoreBindingCancellation: Boolean, private val ignoreSeekCancellation: Boolean) {
        val binding = CompletableDeferred<QueueSeekBinding?>()
        val seekResult = CompletableDeferred<Boolean>()
        val seekEntered = CompletableDeferred<QueueSeekRequest>()
        val requests = mutableListOf<QueueSeekRequest>()
        var acquisitions = 0
        var bindingError: Exception? = null
        var seekError: Exception? = null
        var lateBindingError: Exception? = null
        var lateSeekError: Exception? = null

        suspend fun acquire(): QueueSeekBinding? {
            acquisitions++
            bindingError?.let { throw it }
            val result = if (ignoreBindingCancellation) withContext(NonCancellable) { binding.await() } else binding.await()
            lateBindingError?.let { throw it }
            return result
        }

        suspend fun seek(request: QueueSeekRequest): Boolean {
            requests.add(request)
            seekEntered.complete(request)
            seekError?.let { throw it }
            val result = if (ignoreSeekCancellation) withContext(NonCancellable) { seekResult.await() } else seekResult.await()
            lateSeekError?.let { throw it }
            return result
        }
    }

    private companion object {
        val BINDING = QueueSeekBinding("preview", "session", 2L, "queue", 3L, "entry", "location", 4L, 60_000L)
        val CONTEXT = PlaybackProgressInputContext("playback-owner", foreground = true, canSeek = true,
            positionMs = 10_000L, controlsStateKey = "saved:normal:drag")
        val RETIRED_CONTEXTS = listOf(
            CONTEXT.copy(ownerId = "replacement-owner"), CONTEXT.copy(ownerId = null),
            CONTEXT.copy(foreground = false), CONTEXT.copy(canSeek = false),
            CONTEXT.copy(settings = SeekProgressSettings(showMainProgress = false)),
            CONTEXT.copy(settings = SeekProgressSettings(mode = SeekGestureMode.TAP_TO_SEEK)),
            CONTEXT.copy(controlsStateKey = "saving:normal:drag"),
            CONTEXT.copy(controlsStateKey = "saved:reversed:drag"),
        )
    }
}
