package io.github.onlinemo.meloxwatch.ui.playback

import io.github.onlinemo.meloxwatch.model.PlaybackControlsPreferences
import io.github.onlinemo.meloxwatch.model.QueueSeekBinding
import io.github.onlinemo.meloxwatch.model.QueueSeekRequest
import io.github.onlinemo.meloxwatch.model.RotaryAction
import io.github.onlinemo.meloxwatch.model.RotaryOrientation
import io.github.onlinemo.meloxwatch.playback.input.RotaryAppliedStep
import io.github.onlinemo.meloxwatch.playback.input.RotaryHapticGate
import io.github.onlinemo.meloxwatch.playback.input.RotaryHapticState
import io.github.onlinemo.meloxwatch.playback.input.RotaryInputContext
import io.github.onlinemo.meloxwatch.playback.input.RotaryStep
import io.github.onlinemo.meloxwatch.playback.input.SeekGestureSurface
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

class PlaybackPageInputTest {
    @Test(timeout = 30000L) fun bothPermanentSurfacesShareAcquisitionPreviewAndCommitOccupancy() = runBlocking {
        for (surface in SeekGestureSurface.entries) {
            val fixture = Fixture(this, holdBinding = true, holdSeek = true)
            try {
                val other = otherSurface(surface)
                val id = requireNotNull(fixture.input.begin(surface))
                assertTrue(fixture.input.busy)
                assertEquals(PlaybackProgressInputPhase.ACQUIRING, fixture.state(surface).value.phase)
                assertNull(fixture.input.begin(other))
                assertFalse(fixture.input.setProgress(other, 0.9))
                assertFalse(fixture.input.rotary(1_000L, 1.0))
                assertTrue(fixture.input.drag(surface, id, 0.7))
                fixture.client.binding.complete(BINDING)
                withTimeout(10_000L) { fixture.state(surface).first { it.phase == PlaybackProgressInputPhase.PREVIEW } }
                assertEquals(42_000L, fixture.state(surface).value.previewPositionMs)
                assertNull(fixture.input.begin(other))
                assertTrue(fixture.input.release(surface, id))
                assertSame(BINDING, fixture.request().binding)
                assertEquals(PlaybackProgressInputPhase.COMMITTING, fixture.state(surface).value.phase)
                assertNull(fixture.input.begin(other))
                assertFalse(fixture.input.rotary(1_100L, 1.0))
                fixture.client.seekResult.complete(true)
                fixture.settle()
                assertFalse(fixture.input.busy)
                assertEquals(1, fixture.client.acquisitions)
                assertEquals(1, fixture.client.requests.size)
                assertTrue(fixture.volumeSteps.isEmpty())
            } finally { fixture.close() }
        }
    }

    @Test(timeout = 30000L) fun switchingPagesCannotBypassAnUncooperativeCancelledProgressAcquisition() = runBlocking {
        for (surface in SeekGestureSurface.entries) {
            val fixture = Fixture(this, holdBinding = true, ignoreBindingCancellation = true)
            try {
                val mainState = fixture.input.mainState
                val independentState = fixture.input.independentState
                val id = requireNotNull(fixture.input.begin(surface))
                assertTrue(fixture.input.drag(surface, id, 0.6))
                val replacement = otherSurface(surface)
                fixture.context = PAGE.copy(focusToken = "replacement-focus",
                    mainVisible = replacement == SeekGestureSurface.MAIN_PROGRESS,
                    independentVisible = replacement == SeekGestureSurface.INDEPENDENT_CONTROLS)
                fixture.input.contextChanged()
                assertEquals(PlaybackProgressInputPhase.CANCELLING, fixture.state(surface).value.phase)
                assertTrue(fixture.input.busy)
                assertNull(fixture.input.begin(replacement))
                assertFalse(fixture.input.release(surface, id))
                assertSame(mainState, fixture.input.mainState)
                assertSame(independentState, fixture.input.independentState)
                assertEquals(1, fixture.client.acquisitions)
                fixture.client.binding.complete(BINDING)
                fixture.settle()
                assertTrue(fixture.client.requests.isEmpty())

                val current = requireNotNull(fixture.input.begin(replacement))
                assertNotEquals(id, current)
                fixture.input.cancel(surface, id)
                assertTrue(fixture.input.drag(replacement, current, 0.5))
                assertTrue(fixture.input.release(replacement, current))
                assertEquals(30_000L, fixture.request().positionMs)
                fixture.settle()
                assertEquals(2, fixture.client.acquisitions)
                assertEquals(1, fixture.client.requests.size)
            } finally { fixture.close() }
        }
    }

    @Test(timeout = 30000L) fun cancelledProgressDispatchStillBlocksEveryReplacementUntilTheCallbackReturns() = runBlocking {
        for (surface in SeekGestureSurface.entries) {
            val fixture = Fixture(this, holdSeek = true, ignoreSeekCancellation = true)
            try {
                assertTrue(fixture.input.setProgress(surface, 0.5))
                fixture.request()
                fixture.input.invalidate()
                fixture.context = PAGE.copy(serviceOwnerId = "replacement-service", focusToken = "replacement-focus")
                fixture.input.contextChanged()
                assertEquals(PlaybackProgressInputPhase.CANCELLING, fixture.state(surface).value.phase)
                assertTrue(fixture.input.busy)
                for (candidate in SeekGestureSurface.entries) assertNull(fixture.input.begin(candidate))
                assertFalse(fixture.input.rotary(1_000L, 1.0))
                fixture.client.lateSeekError = IOException("retired dispatch")
                fixture.client.seekResult.complete(false)
                fixture.settle()
                assertEquals(PlaybackProgressInputState(), fixture.state(surface).value)
                assertFalse(fixture.input.busy)
                assertEquals(1, fixture.client.acquisitions)
                assertEquals(1, fixture.client.requests.size)
                assertTrue(fixture.applied.isEmpty())
            } finally { fixture.close() }
        }
    }

    @Test(timeout = 30000L) fun cancelledRotaryAcquisitionHoldsTheGlobalSlotAndDropsItsLateBinding() = runBlocking {
        val fixture = Fixture(this, holdBinding = true, ignoreBindingCancellation = true)
        try {
            assertTrue(fixture.input.rotary(1_000L, 1.0))
            assertTrue(fixture.input.rotaryPending.value)
            assertTrue(fixture.input.busy)
            fixture.acquisition()
            fixture.context = PAGE.copy(focusToken = "new-focus")
            fixture.input.contextChanged()
            for (surface in SeekGestureSurface.entries) assertNull(fixture.input.begin(surface))
            assertFalse(fixture.input.rotary(1_100L, 1.0))
            assertTrue(fixture.input.rotaryPending.value)
            fixture.client.binding.complete(BINDING)
            fixture.settle()
            assertFalse(fixture.input.rotaryPending.value)
            assertFalse(fixture.input.busy)
            assertTrue(fixture.client.requests.isEmpty())
            assertTrue(fixture.applied.isEmpty())
            assertTrue(fixture.input.setProgress(SeekGestureSurface.INDEPENDENT_CONTROLS, 0.5))
            assertEquals(30_000L, fixture.request().positionMs)
            fixture.settle()
            assertEquals(2, fixture.client.acquisitions)
            assertEquals(1, fixture.client.requests.size)
        } finally { fixture.close() }
    }

    @Test(timeout = 30000L) fun lateRotaryAcknowledgementsCannotReportSuccessAfterOwnerControlsOrEligibilityChange() = runBlocking {
        for (replacement in RETIRED_ROTARY_CONTEXTS) {
            val fixture = Fixture(this, holdSeek = true, ignoreSeekCancellation = true)
            try {
                assertTrue(fixture.input.rotary(1_000L, 1.0))
                fixture.request()
                fixture.context = replacement
                fixture.input.contextChanged()
                assertTrue(fixture.input.rotaryPending.value)
                assertTrue(fixture.input.busy)
                for (surface in SeekGestureSurface.entries) assertNull(fixture.input.begin(surface))
                fixture.client.seekResult.complete(true)
                fixture.settle()
                assertFalse(fixture.input.rotaryPending.value)
                assertFalse(fixture.input.busy)
                assertTrue(fixture.applied.isEmpty())
                assertEquals(1, fixture.client.requests.size)
            } finally { fixture.close() }
        }
    }

    @Test(timeout = 30000L) fun bindingDriftIsRejectedEvenBeforeTheHostDeliversItsContextNotification() = runBlocking {
        for (replacement in RETIRED_ROTARY_CONTEXTS) {
            val fixture = Fixture(this, holdBinding = true)
            try {
                assertTrue(fixture.input.rotary(1_000L, 1.0))
                fixture.acquisition()
                fixture.context = replacement
                fixture.client.binding.complete(BINDING)
                fixture.settle()
                assertTrue(fixture.client.requests.isEmpty())
                assertTrue(fixture.applied.isEmpty())
                assertEquals(1, fixture.client.acquisitions)
                assertFalse(fixture.input.rotaryPending.value)
            } finally { fixture.close() }
        }
    }

    @Test(timeout = 30000L) fun synchronousBindingValidationRetirementPreventsEnteringSeekDispatch() = runBlocking {
        val fixture = Fixture(this)
        try {
            fixture.beforeValidation = { fixture.input.invalidate() }
            assertTrue(fixture.input.rotary(1_000L, 1.0))
            fixture.settle()
            assertEquals(1, fixture.client.acquisitions)
            assertTrue(fixture.client.requests.isEmpty())
            assertTrue(fixture.applied.isEmpty())
            assertFalse(fixture.input.busy)
        } finally { fixture.close() }
    }

    @Test(timeout = 30000L) fun synchronousPositionReadRetirementPreventsEnteringSeekDispatch() = runBlocking {
        val fixture = Fixture(this, holdBinding = true)
        var retired = false
        try {
            assertTrue(fixture.input.rotary(1_000L, 1.0))
            fixture.acquisition()
            fixture.onContextRead = {
                if (!retired) {
                    retired = true
                    fixture.context = PAGE.copy(focusToken = "replacement-focus")
                    fixture.input.contextChanged()
                }
            }
            fixture.client.binding.complete(BINDING)
            fixture.settle()
            assertTrue(retired)
            assertTrue(fixture.client.requests.isEmpty())
            assertTrue(fixture.applied.isEmpty())
            assertFalse(fixture.input.busy)
        } finally { fixture.close() }
    }

    @Test(timeout = 30000L) fun reentrantContextReadCannotAdmitBothProgressSurfaces() = runBlocking {
        for (explicitProgress in listOf(false, true)) {
            val fixture = Fixture(this, holdBinding = true)
            var entered = false
            var nestedId: String? = null
            try {
                fixture.onContextRead = {
                    if (!entered) {
                        entered = true
                        nestedId = fixture.input.begin(SeekGestureSurface.INDEPENDENT_CONTROLS)
                    }
                }
                val outerAccepted = if (explicitProgress) fixture.input.setProgress(SeekGestureSurface.MAIN_PROGRESS, 0.5)
                    else fixture.input.begin(SeekGestureSurface.MAIN_PROGRESS) != null
                assertTrue(entered)
                assertTrue(outerAccepted.xor(nestedId != null))
                assertEquals(1, fixture.client.acquisitions)
                assertTrue(fixture.input.busy)
                assertTrue(fixture.client.requests.isEmpty())
            } finally { fixture.close() }
        }
    }

    @Test(timeout = 30000L) fun aSynchronousPendingObserverCanCancelRotaryBeforeItsLazyCallbackStarts() = runBlocking {
        val fixture = Fixture(this)
        val observer = launch(context = Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            fixture.input.rotaryPending.first { pending ->
                if (!pending) false else {
                    fixture.input.invalidate()
                    for (surface in SeekGestureSurface.entries) assertNull(fixture.input.begin(surface))
                    true
                }
            }
        }
        try {
            assertTrue(fixture.input.rotary(1_000L, 1.0))
            observer.join()
            fixture.settle()
            assertEquals(0, fixture.client.acquisitions)
            assertTrue(fixture.client.requests.isEmpty())
            assertFalse(fixture.input.rotaryPending.value)
            assertFalse(fixture.input.busy)
        } finally {
            observer.cancelAndJoin()
            fixture.close()
        }
    }

    @Test(timeout = 30000L) fun positionTicksPreserveOwnersAndRotarySeeksFromTheFreshPosition() = runBlocking {
        val fixture = Fixture(this, holdBinding = true, holdSeek = true)
        try {
            val mainOwner = fixture.input.progressContext(SeekGestureSurface.MAIN_PROGRESS).ownerId
            val independentOwner = fixture.input.progressContext(SeekGestureSurface.INDEPENDENT_CONTROLS).ownerId
            assertTrue(fixture.input.rotary(1_000L, 1.0))
            fixture.acquisition()
            fixture.context = PAGE.copy(positionMs = 12_000L)
            fixture.input.contextChanged()
            assertEquals(mainOwner, fixture.input.progressContext(SeekGestureSurface.MAIN_PROGRESS).ownerId)
            assertEquals(independentOwner, fixture.input.progressContext(SeekGestureSurface.INDEPENDENT_CONTROLS).ownerId)
            assertTrue(fixture.input.rotaryPending.value)
            fixture.client.binding.complete(BINDING)
            val request = fixture.request()
            assertSame(BINDING, request.binding)
            assertEquals(17_000L, request.positionMs)
            assertFalse(request.explicitPlay)
            fixture.client.seekResult.complete(true)
            fixture.settle()
            assertEquals(1, fixture.applied.size)
            assertTrue(fixture.applied.single().first.changed)
            assertEquals(fixture.input.rotaryContext().focusToken, fixture.applied.single().second.focusToken)
            assertEquals(1, fixture.client.acquisitions)
        } finally { fixture.close() }
    }

    @Test(timeout = 30000L) fun listPriorityAndChildFocusDiscardRotaryFractionsWithoutADeferredJump() = runBlocking {
        for (childFocus in listOf(false, true)) {
            val volumeContext = PAGE.copy(controls = CONTROLS.copy(rotaryAction = RotaryAction.MEDIA_VOLUME))
            val fixture = Fixture(this, initialContext = volumeContext)
            try {
                assertTrue(fixture.input.rotary(1_000L, 0.6))
                fixture.context = if (childFocus) volumeContext.copy(rotaryFocused = false)
                    else volumeContext.copy(listHasPriority = true)
                fixture.input.contextChanged()
                assertFalse(fixture.input.rotary(1_100L, 0.6))
                fixture.context = volumeContext
                fixture.input.contextChanged()
                assertTrue(fixture.input.rotary(1_200L, 0.6))
                assertTrue(fixture.volumeSteps.isEmpty())
                assertTrue(fixture.input.rotary(1_300L, 0.4))
                assertEquals(1, fixture.volumeSteps.size)
                assertEquals(1, fixture.volumeSteps.single().delta)
                assertEquals(0, fixture.client.acquisitions)
            } finally { fixture.close() }
        }
    }

    @Test(timeout = 30000L) fun focusInsideThePageAllowsAccessibleProgressWithoutStealingChildRotaryInput() = runBlocking {
        for (listPriority in listOf(false, true)) {
            val fixture = Fixture(this, initialContext = PAGE.copy(rotaryFocused = false, listHasPriority = listPriority))
            try {
                assertFalse(fixture.input.rotary(1_000L, 1.0))
                assertTrue(fixture.input.setProgress(SeekGestureSurface.MAIN_PROGRESS, 0.5))
                assertEquals(30_000L, fixture.request().positionMs)
                fixture.settle()
                assertEquals(1, fixture.client.acquisitions)
                assertTrue(fixture.applied.isEmpty())
            } finally { fixture.close() }
        }
    }

    @Test(timeout = 30000L) fun listPriorityDoesNotRetireAnAlreadyOwnedTouchProgressPreview() = runBlocking {
        val fixture = Fixture(this)
        try {
            val id = requireNotNull(fixture.input.begin(SeekGestureSurface.MAIN_PROGRESS))
            assertTrue(fixture.input.drag(SeekGestureSurface.MAIN_PROGRESS, id, 0.5))
            val owner = fixture.input.progressContext(SeekGestureSurface.MAIN_PROGRESS).ownerId
            fixture.context = PAGE.copy(listHasPriority = true, rotaryFocused = false)
            fixture.input.contextChanged()
            assertEquals(owner, fixture.input.progressContext(SeekGestureSurface.MAIN_PROGRESS).ownerId)
            assertEquals(PlaybackProgressInputPhase.PREVIEW, fixture.input.mainState.value.phase)
            assertTrue(fixture.input.release(SeekGestureSurface.MAIN_PROGRESS, id))
            assertEquals(30_000L, fixture.request().positionMs)
            fixture.settle()
            assertEquals(1, fixture.client.requests.size)
        } finally { fixture.close() }
    }

    @Test(timeout = 30000L) fun disabledUnfocusedBackgroundAndListContextsDoNotConsumeRotaryControls() = runBlocking {
        val ineligible = listOf(PAGE.copy(focusToken = null), PAGE.copy(foreground = false),
            PAGE.copy(mainVisible = false), PAGE.copy(listHasPriority = true), PAGE.copy(rotaryFocused = false),
            PAGE.copy(commandPending = true), PAGE.copy(controls = CONTROLS.copy(rotaryEnabled = false)))
        for (context in ineligible) {
            val fixture = Fixture(this, initialContext = context)
            try {
                assertFalse(fixture.input.rotary(1_000L, 1.0))
                assertFalse(fixture.input.rotary(1_100L, 0.6))
                assertEquals(0, fixture.client.acquisitions)
                assertTrue(fixture.volumeSteps.isEmpty())
                assertTrue(fixture.applied.isEmpty())
                assertFalse(fixture.input.busy)
            } finally { fixture.close() }
        }
    }

    @Test(timeout = 30000L) fun volumeFeedbackCarriesTheRealOutcomeAndTheFreshFocusContext() = runBlocking {
        val volumeContext = PAGE.copy(controls = CONTROLS.copy(rotaryAction = RotaryAction.MEDIA_VOLUME, rotaryHaptics = true))
        for (changed in listOf(false, true)) {
            val fixture = Fixture(this, initialContext = volumeContext)
            try {
                fixture.onVolume = { changed }
                assertTrue(fixture.input.rotary(1_000L, 1.0))
                val (outcome, context) = fixture.applied.single()
                assertEquals(changed, outcome.changed)
                assertEquals(changed, RotaryHapticGate.evaluate(RotaryHapticState(), outcome, context,
                    nowElapsedMs = 1_000L, enabled = true).requestPulse)
                assertEquals(0, fixture.client.acquisitions)
            } finally { fixture.close() }
        }
        val movedFocus = Fixture(this, initialContext = volumeContext)
        try {
            movedFocus.onVolume = {
                movedFocus.context = volumeContext.copy(focusToken = "child-focus", rotaryFocused = false)
                movedFocus.input.contextChanged()
                true
            }
            assertTrue(movedFocus.input.rotary(1_000L, 1.0))
            val (outcome, context) = movedFocus.applied.single()
            assertNotEquals(outcome.step.focusToken, context.focusToken)
            assertTrue(outcome.changed)
            assertFalse(RotaryHapticGate.evaluate(RotaryHapticState(), outcome, context,
                nowElapsedMs = 1_000L, enabled = true).requestPulse)
        } finally { movedFocus.close() }
    }

    @Test(timeout = 30000L) fun closingWithAnUncooperativeRotaryCallbackKeepsOccupancyUntilItReturns() = runBlocking {
        for (duringBinding in listOf(false, true)) {
            val fixture = Fixture(this, holdBinding = duringBinding, holdSeek = !duringBinding,
                ignoreBindingCancellation = duringBinding, ignoreSeekCancellation = !duringBinding)
            try {
                assertTrue(fixture.input.rotary(1_000L, 1.0))
                if (duringBinding) fixture.acquisition() else fixture.request()
                fixture.input.close()
                assertTrue(fixture.input.busy)
                assertTrue(fixture.input.rotaryPending.value)
                for (surface in SeekGestureSurface.entries) assertNull(fixture.input.begin(surface))
                assertFalse(fixture.input.rotary(1_100L, 1.0))
                fixture.client.binding.complete(BINDING)
                fixture.client.seekResult.complete(true)
                fixture.settle()
                assertFalse(fixture.input.busy)
                assertFalse(fixture.input.rotaryPending.value)
                assertTrue(fixture.applied.isEmpty())
                assertEquals(if (duringBinding) 0 else 1, fixture.client.requests.size)
                for (surface in SeekGestureSurface.entries) assertNull(fixture.input.begin(surface))
            } finally { fixture.close() }
        }
    }

    @Test(timeout = 30000L) fun missingRejectedFailedAndBoundaryRotaryResultsNeverBecomeSuccess() = runBlocking {
        for (result in RotaryResult.entries) {
            val fixture = Fixture(this, returnedBinding = if (result == RotaryResult.MISSING) null else BINDING,
                holdSeek = true, initialContext = if (result == RotaryResult.END) PAGE.copy(positionMs = BINDING.durationMs) else PAGE)
            try {
                if (result == RotaryResult.BINDING_FAILURE) fixture.client.bindingError = IOException("binding failure")
                if (result == RotaryResult.SEEK_FAILURE) fixture.client.seekError = IOException("seek failure")
                if (result == RotaryResult.OBSOLETE_BINDING) fixture.expectedBinding = BINDING.copy(contentRevision = 5L)
                fixture.client.seekResult.complete(false)
                assertTrue(fixture.input.rotary(1_000L, 1.0))
                fixture.settle()
                assertFalse(fixture.input.rotaryPending.value)
                assertFalse(fixture.input.busy)
                assertTrue(fixture.applied.none { it.first.changed })
                assertEquals(if (result in listOf(RotaryResult.REJECTED, RotaryResult.SEEK_FAILURE)) 1 else 0,
                    fixture.client.requests.size)
                assertEquals(1, fixture.client.acquisitions)
            } finally { fixture.close() }
        }
        val unknownDuration = Fixture(this, initialContext = PAGE.copy(durationMs = null))
        try {
            assertTrue(unknownDuration.input.rotary(1_000L, 1.0))
            assertEquals(0, unknownDuration.client.acquisitions)
            assertFalse(unknownDuration.input.busy)
        } finally { unknownDuration.close() }
    }

    @Test(timeout = 30000L) fun explicitSeekButtonsReachBothBoundsThroughHiddenMainIndependentControls() = runBlocking {
        val hidden = PAGE.copy(mainVisible = false, controls = CONTROLS.copy(showMainProgress = false))
        val fixture = Fixture(this, initialContext = hidden)
        try {
            assertFalse(fixture.input.seekBy(SeekGestureSurface.MAIN_PROGRESS, 5_000L))
            assertEquals(0, fixture.client.acquisitions)
            assertTrue(fixture.input.seekBy(SeekGestureSurface.INDEPENDENT_CONTROLS, Long.MAX_VALUE))
            assertEquals(BINDING.durationMs, fixture.request().positionMs)
            fixture.settle()
            fixture.context = hidden.copy(positionMs = BINDING.durationMs)
            assertTrue(fixture.input.seekBy(SeekGestureSurface.INDEPENDENT_CONTROLS, Long.MIN_VALUE))
            fixture.settle()
            assertEquals(0L, fixture.client.requests.last().positionMs)
            assertFalse(fixture.client.requests.last().explicitPlay)
            assertEquals(2, fixture.client.acquisitions)
            assertEquals(2, fixture.client.requests.size)
        } finally { fixture.close() }
    }

    @Test(timeout = 30000L) fun aSavedMappingChangeDuringContextReadsCannotApplyTheOldVolumeMapping() = runBlocking {
        val volumeContext = PAGE.copy(controls = CONTROLS.copy(rotaryAction = RotaryAction.MEDIA_VOLUME))
        val fixture = Fixture(this, initialContext = volumeContext)
        var reads = 0
        var changed = false
        try {
            fixture.onContextRead = {
                reads++
                if (reads == 2) {
                    changed = true
                    fixture.context = PAGE.copy(settingsRevision = 2L, controlsStateKey = "saved:seek:revision-2")
                    fixture.input.contextChanged()
                }
            }
            fixture.input.rotary(1_000L, 1.0)
            fixture.settle()
            assertTrue(changed)
            assertTrue(fixture.volumeSteps.isEmpty())
            assertFalse(fixture.input.busy)
        } finally { fixture.close() }
    }

    @Test(timeout = 30000L) fun snapshotCallbackRetirementOrProgressOccupancyPreventsEitherRotaryOutput() = runBlocking {
        for (target in RotaryAction.entries) {
            for (retirement in SnapshotRetirement.entries) {
                val fixture = Fixture(this, initialContext = PAGE.copy(controls = CONTROLS.copy(rotaryAction = target)))
                var reads = 0
                var retired = false
                var progressId: String? = null
                try {
                    fixture.onContextRead = {
                        reads++
                        if (reads == 2) {
                            retired = true
                            when (retirement) {
                                SnapshotRetirement.CLOSE -> fixture.input.close()
                                SnapshotRetirement.INVALIDATE -> fixture.input.invalidate()
                                SnapshotRetirement.CANCEL_SCOPE -> fixture.cancelScope()
                                SnapshotRetirement.OCCUPY -> progressId = fixture.input.begin(SeekGestureSurface.INDEPENDENT_CONTROLS)
                            }
                        }
                    }
                    fixture.input.rotary(1_000L, 1.0)
                    assertTrue(retired)
                    assertTrue(fixture.volumeSteps.isEmpty())
                    assertTrue(fixture.client.requests.isEmpty())
                    assertTrue(fixture.applied.isEmpty())
                    if (retirement == SnapshotRetirement.OCCUPY) {
                        assertTrue(fixture.input.busy)
                        assertEquals(1, fixture.client.acquisitions)
                        fixture.input.cancel(SeekGestureSurface.INDEPENDENT_CONTROLS, requireNotNull(progressId))
                    } else assertEquals(0, fixture.client.acquisitions)
                    fixture.settle()
                    assertFalse(fixture.input.busy)
                    assertFalse(fixture.input.rotaryPending.value)
                } finally { fixture.close() }
            }
        }
    }

    private enum class RotaryResult { MISSING, BINDING_FAILURE, REJECTED, SEEK_FAILURE, END, OBSOLETE_BINDING }
    private enum class SnapshotRetirement { CLOSE, INVALIDATE, CANCEL_SCOPE, OCCUPY }

    private class Fixture(
        owner: CoroutineScope,
        initialContext: PlaybackPageInputContext = PAGE,
        returnedBinding: QueueSeekBinding? = BINDING,
        holdBinding: Boolean = false,
        holdSeek: Boolean = false,
        ignoreBindingCancellation: Boolean = false,
        ignoreSeekCancellation: Boolean = false,
    ) {
        private val job = SupervisorJob(owner.coroutineContext[Job])
        private val scope = CoroutineScope(owner.coroutineContext + job)
        var context = initialContext
        var expectedBinding = BINDING
        var beforeValidation: () -> Unit = {}
        var onContextRead: () -> Unit = {}
        var onVolume: (RotaryStep) -> Boolean = { true }
        val client = ControlledClient(ignoreBindingCancellation, ignoreSeekCancellation)
        val volumeSteps = mutableListOf<RotaryStep>()
        val applied = mutableListOf<Pair<RotaryAppliedStep, RotaryInputContext>>()
        val input = PlaybackPageInput(scope, {
            onContextRead()
            context
        }, client::acquire,
            {
                binding -> beforeValidation()
                binding == expectedBinding
            }, client::seek,
            {
                step -> volumeSteps.add(step)
                onVolume(step)
            },
            { outcome, current -> applied.add(outcome to current) })

        init {
            if (!holdBinding) client.binding.complete(returnedBinding)
            if (!holdSeek) client.seekResult.complete(true)
        }

        fun state(surface: SeekGestureSurface) = when (surface) {
            SeekGestureSurface.MAIN_PROGRESS -> input.mainState
            SeekGestureSurface.INDEPENDENT_CONTROLS -> input.independentState
        }

        suspend fun acquisition() = withTimeout(10_000L) { client.acquireEntered.await() }
        suspend fun request() = withTimeout(10_000L) { client.seekEntered.await() }
        suspend fun settle() = withTimeout(10_000L) { job.children.toList().forEach { it.join() } }
        fun cancelScope() = job.cancel()
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
        val acquireEntered = CompletableDeferred<Int>()
        val seekEntered = CompletableDeferred<QueueSeekRequest>()
        val requests = mutableListOf<QueueSeekRequest>()
        var acquisitions = 0
        var bindingError: Exception? = null
        var seekError: Exception? = null
        var lateSeekError: Exception? = null

        suspend fun acquire(): QueueSeekBinding? {
            acquisitions++
            acquireEntered.complete(acquisitions)
            bindingError?.let { throw it }
            return if (ignoreBindingCancellation) withContext(NonCancellable) { binding.await() } else binding.await()
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
        val CONTROLS = PlaybackControlsPreferences(rotaryEnabled = true, rotaryAction = RotaryAction.SEEK)
        val PAGE = PlaybackPageInputContext("service-owner", "page-focus", foreground = true,
            mainVisible = true, independentVisible = true, commandPending = false, positionMs = 10_000L,
            durationMs = 60_000L, controls = CONTROLS, controlsStateKey = "saved:normal:drag", settingsRevision = 1L)
        val RETIRED_ROTARY_CONTEXTS = listOf(
            PAGE.copy(serviceOwnerId = "replacement-service"), PAGE.copy(serviceOwnerId = null),
            PAGE.copy(focusToken = "replacement-focus"), PAGE.copy(focusToken = null),
            PAGE.copy(foreground = false), PAGE.copy(mainVisible = false), PAGE.copy(commandPending = true),
            PAGE.copy(listHasPriority = true), PAGE.copy(rotaryFocused = false),
            PAGE.copy(controlsStateKey = "saving:normal:drag"), PAGE.copy(settingsRevision = 2L),
            PAGE.copy(controls = CONTROLS.copy(rotaryOrientation = RotaryOrientation.REVERSED)),
            PAGE.copy(controls = CONTROLS.copy(rotaryEnabled = false)),
        )

        fun otherSurface(surface: SeekGestureSurface) = when (surface) {
            SeekGestureSurface.MAIN_PROGRESS -> SeekGestureSurface.INDEPENDENT_CONTROLS
            SeekGestureSurface.INDEPENDENT_CONTROLS -> SeekGestureSurface.MAIN_PROGRESS
        }
    }
}
