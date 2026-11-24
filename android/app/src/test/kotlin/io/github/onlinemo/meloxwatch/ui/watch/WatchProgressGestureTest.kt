package io.github.onlinemo.meloxwatch.ui.watch

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.IntSize
import io.github.onlinemo.meloxwatch.playback.input.SeekGestureMode
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class WatchProgressGestureTest {
    private fun gesture(mode: SeekGestureMode = SeekGestureMode.DRAG_TO_COMMIT) =
        ProgressPointerGesture(mode, 100.0, 48.0, 8.0, 50.0, 24.0)

    @Test fun horizontalDragOwnsInputOnlyBeyondSlopAndFinishesOnRelease() {
        val pointer = gesture()
        assertEquals(ProgressPointerAction.WAIT, pointer.update(58.0, 24.0, pressed = true).action)
        val start = pointer.update(59.0, 24.0, pressed = true)
        assertEquals(ProgressPointerAction.START_DRAG, start.action)
        assertEquals(0.59, requireNotNull(start.fraction), 0.000001)
        assertEquals(ProgressPointerAction.DRAG, pointer.update(70.0, 26.0, pressed = true).action)
        val release = pointer.update(80.0, 26.0, pressed = false)
        assertEquals(ProgressPointerAction.RELEASE, release.action)
        assertEquals(0.8, requireNotNull(release.fraction), 0.000001)
    }

    @Test fun aTapOrAnUpOnlyJumpCannotBecomeADragCommit() {
        val tap = gesture().update(50.0, 24.0, pressed = false)
        val upJump = gesture().update(80.0, 24.0, pressed = false)
        for (decision in listOf(tap, upJump)) {
            assertEquals(ProgressPointerAction.CANCEL, decision.action)
            assertNull(decision.fraction)
        }
    }

    @Test fun verticalAndDiagonalSlopYieldBeforeAnyHorizontalClaim() {
        for ((x, y) in listOf(54.0 to 35.0, 61.0 to 35.0)) {
            val pointer = gesture()
            assertEquals(ProgressPointerAction.CANCEL, pointer.update(x, y, pressed = true).action)
            assertEquals(ProgressPointerAction.WAIT, pointer.update(80.0, 24.0, pressed = false).action)
        }
    }

    @Test fun tapModeNeedsACompleteTapWithinBothSlopAxes() {
        val pointer = gesture(SeekGestureMode.TAP_TO_SEEK)
        assertEquals(ProgressPointerAction.WAIT, pointer.update(57.0, 30.0, pressed = true).action)
        val tap = pointer.update(57.0, 30.0, pressed = false)
        assertEquals(ProgressPointerAction.TAP, tap.action)
        assertEquals(0.57, requireNotNull(tap.fraction), 0.000001)
        for ((x, y) in listOf(59.0 to 24.0, 50.0 to 33.0)) {
            val moved = gesture(SeekGestureMode.TAP_TO_SEEK)
            assertEquals(ProgressPointerAction.CANCEL, moved.update(x, y, pressed = true).action)
            assertEquals(ProgressPointerAction.WAIT, moved.update(50.0, 24.0, pressed = false).action)
        }
    }

    @Test fun leavingAnyComponentEdgeCancelsEvenIfThePointerReturns() {
        for ((x, y) in listOf(-0.01 to 24.0, 100.01 to 24.0, 80.0 to -0.01, 80.0 to 48.01)) {
            val pointer = gesture()
            assertEquals(ProgressPointerAction.START_DRAG, pointer.update(60.0, 24.0, pressed = true).action)
            assertEquals(ProgressPointerAction.CANCEL, pointer.update(x, y, pressed = true).action)
            assertEquals(ProgressPointerAction.WAIT, pointer.update(80.0, 24.0, pressed = false).action)
        }
    }

    @Test fun anotherConsumerOrPointerCannotFinishTheOwnedDrag() {
        for (anotherPointer in listOf(false, true)) {
            val pointer = gesture()
            pointer.update(60.0, 24.0, pressed = true)
            val cancelled = pointer.update(70.0, 24.0, pressed = true,
                consumed = !anotherPointer, anotherPointer = anotherPointer)
            assertEquals(ProgressPointerAction.CANCEL, cancelled.action)
            assertEquals(ProgressPointerAction.WAIT, pointer.update(80.0, 24.0, pressed = false).action)
        }
    }

    @Test fun enlargedTouchAreaMapsTrackInsetsToExactEndpoints() {
        for ((x, expected) in listOf(0.0 to 0.0, 4.0 to 0.0, 96.0 to 1.0, 100.0 to 1.0)) {
            val pointer = ProgressPointerGesture(SeekGestureMode.DRAG_TO_COMMIT,
                100.0, 48.0, 8.0, 50.0, 24.0, trackInset = 4.0)
            pointer.update(60.0, 24.0, pressed = true)
            val release = pointer.update(x, 24.0, pressed = false)
            assertEquals(ProgressPointerAction.RELEASE, release.action)
            assertEquals(expected, requireNotNull(release.fraction), 0.0)
        }
    }

    @Test fun invalidGeometryAndCoordinatesCannotProduceASelection() {
        val invalid = listOf(
            ProgressPointerGesture(SeekGestureMode.TAP_TO_SEEK, 0.0, 48.0, 8.0, 0.0, 24.0),
            ProgressPointerGesture(SeekGestureMode.TAP_TO_SEEK, 100.0, 0.0, 8.0, 50.0, 0.0),
            ProgressPointerGesture(SeekGestureMode.TAP_TO_SEEK, 100.0, 48.0, -1.0, 50.0, 24.0),
            ProgressPointerGesture(SeekGestureMode.TAP_TO_SEEK, 8.0, 48.0, 8.0, 4.0, 24.0, trackInset = 4.0),
            ProgressPointerGesture(SeekGestureMode.TAP_TO_SEEK, Double.NaN, 48.0, 8.0, 50.0, 24.0),
            ProgressPointerGesture(SeekGestureMode.TAP_TO_SEEK, 100.0, 48.0, 8.0, -1.0, 24.0),
        )
        for (pointer in invalid) {
            val decision = pointer.update(50.0, 24.0, pressed = false)
            assertEquals(ProgressPointerAction.CANCEL, decision.action)
            assertNull(decision.fraction)
        }
        for (bad in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            assertEquals(ProgressPointerAction.CANCEL, gesture().update(bad, 24.0, pressed = true).action)
        }
    }

    @Test fun completedPointerSequencesNeverReplayTheirSelection() {
        val pointer = gesture(SeekGestureMode.TAP_TO_SEEK)
        assertEquals(ProgressPointerAction.TAP, pointer.update(50.0, 24.0, pressed = false).action)
        val duplicate = pointer.update(55.0, 24.0, pressed = false)
        assertEquals(ProgressPointerAction.WAIT, duplicate.action)
        assertNull(duplicate.fraction)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class WatchProgressPointerAdapterTest {
    @Test fun mainConsumptionOfDownPreventsTapAndLaterHorizontalOwnership() {
        for (mode in SeekGestureMode.entries) {
            val recorder = RecordingCallbacks()
            val scope = PointerScriptScope(listOf(frame(50f, consumeMain = true),
                frame(if (mode == SeekGestureMode.TAP_TO_SEEK) 55f else 75f),
                frame(if (mode == SeekGestureMode.TAP_TO_SEEK) 55f else 80f, pressed = false)))
            scope.run(mode) { recorder.callbacks }.getOrThrow()
            assertEquals(0, recorder.begins)
            assertTrue(recorder.drags.isEmpty())
            assertTrue(recorder.taps.isEmpty())
            assertTrue(recorder.releases.isEmpty())
            assertTrue(scope.events[0].changes.single().isConsumed)
            assertFalse(scope.events[1].changes.single().isConsumed)
            assertEquals(0, scope.lastEventIndex)
        }
    }

    @Test fun mainConsumptionBelowSlopPreventsTapAndLaterHorizontalOwnership() {
        for (mode in SeekGestureMode.entries) {
            val recorder = RecordingCallbacks()
            val scope = PointerScriptScope(listOf(frame(50f), frame(55f, consumeMain = true),
                frame(if (mode == SeekGestureMode.TAP_TO_SEEK) 57f else 75f),
                frame(if (mode == SeekGestureMode.TAP_TO_SEEK) 57f else 80f, pressed = false)))
            scope.run(mode) { recorder.callbacks }.getOrThrow()
            assertEquals(0, recorder.begins)
            assertTrue(recorder.drags.isEmpty())
            assertTrue(recorder.taps.isEmpty())
            assertTrue(recorder.releases.isEmpty())
            assertTrue(scope.events[1].changes.single().isConsumed)
            assertFalse(scope.events[2].changes.single().isConsumed)
            assertEquals(1, scope.lastEventIndex)
        }
    }

    @Test fun ancestorFinalConsumptionOfDownOrSubSlopInputAlsoPreventsCompletion() {
        for (mode in SeekGestureMode.entries) {
            for (consumedIndex in listOf(0, 1)) {
                val recorder = RecordingCallbacks()
                val scope = PointerScriptScope(listOf(frame(50f, consumeFinal = consumedIndex == 0),
                    frame(55f, consumeFinal = consumedIndex == 1),
                    frame(if (mode == SeekGestureMode.TAP_TO_SEEK) 57f else 75f),
                    frame(if (mode == SeekGestureMode.TAP_TO_SEEK) 57f else 80f, pressed = false)))
                scope.run(mode) { recorder.callbacks }.getOrThrow()
                assertEquals(0, recorder.begins)
                assertTrue(recorder.taps.isEmpty())
                assertTrue(recorder.releases.isEmpty())
                assertTrue(scope.events[consumedIndex].changes.single().isConsumed)
                assertEquals(consumedIndex, scope.lastEventIndex)
            }
        }
    }

    @Test fun aMainOrAncestorFinalConsumerCanOwnTheUnclaimedTapUp() {
        for (main in listOf(false, true)) {
            val recorder = RecordingCallbacks()
            val scope = PointerScriptScope(listOf(frame(50f), frame(55f),
                frame(55f, pressed = false, consumeMain = main, consumeFinal = !main)))
            scope.run(SeekGestureMode.TAP_TO_SEEK) { recorder.callbacks }.getOrThrow()
            assertEquals(0, recorder.begins)
            assertTrue(recorder.taps.isEmpty())
            assertTrue(recorder.cancels.isEmpty())
            assertTrue(scope.events[2].changes.single().isConsumed)
        }
    }

    @Test fun anUnconsumedTapCompletesOnceAfterFinalWithTheInsetTrackFraction() {
        val recorder = RecordingCallbacks()
        val scope = PointerScriptScope(listOf(frame(50f), frame(55f), frame(57f, pressed = false)))
        scope.run(SeekGestureMode.TAP_TO_SEEK) { recorder.callbacks }.getOrThrow()
        assertEquals(1, recorder.begins)
        assertEquals(listOf(RecordingCallbacks.ID), recorder.taps.map { it.first })
        assertEquals((57.0 - 4.0) / 92.0, recorder.taps.single().second, 0.0)
        assertTrue(recorder.drags.isEmpty())
        assertTrue(recorder.releases.isEmpty())
        assertTrue(recorder.cancels.isEmpty())
        assertEquals(listOf(false, false, false), scope.mainSawConsumed)
        assertEquals(listOf(0 to PointerEventPass.Initial, 0 to PointerEventPass.Final,
            1 to PointerEventPass.Initial, 1 to PointerEventPass.Final,
            2 to PointerEventPass.Initial, 2 to PointerEventPass.Final), scope.requestedPasses)
        assertTrue(scope.events[2].changes.single().isConsumed)
    }

    @Test fun horizontalOwnershipConsumesInitialBeforeThePagerThroughTheRelease() {
        val recorder = RecordingCallbacks()
        val scope = PointerScriptScope(listOf(frame(50f), frame(58f), frame(59f, consumeMain = true),
            frame(70f, consumeMain = true), frame(80f, pressed = false, consumeMain = true)))
        scope.run(SeekGestureMode.DRAG_TO_COMMIT) { recorder.callbacks }.getOrThrow()
        assertEquals(1, recorder.begins)
        assertEquals(listOf(RecordingCallbacks.ID, RecordingCallbacks.ID, RecordingCallbacks.ID), recorder.drags.map { it.first })
        for ((index, x) in listOf(59.0, 70.0, 80.0).withIndex()) {
            assertEquals((x - 4.0) / 92.0, recorder.drags[index].second, 0.0)
        }
        assertEquals(listOf(RecordingCallbacks.ID), recorder.releases)
        assertTrue(recorder.taps.isEmpty())
        assertTrue(recorder.cancels.isEmpty())
        assertEquals(listOf(false, false, true, true, true), scope.mainSawConsumed)
        assertEquals(listOf(0, 1), scope.requestedPasses.filter { it.second == PointerEventPass.Final }.map { it.first })
    }

    @Test fun verticalAndDiagonalMotionYieldWithoutASeekOrChildConsumption() {
        for ((x, y) in listOf(54f to 35f, 61f to 35f)) {
            val recorder = RecordingCallbacks()
            val scope = PointerScriptScope(listOf(frame(50f), frame(x, y), frame(80f, pressed = false)))
            scope.run(SeekGestureMode.DRAG_TO_COMMIT) { recorder.callbacks }.getOrThrow()
            assertEquals(0, recorder.begins)
            assertTrue(recorder.drags.isEmpty())
            assertTrue(recorder.releases.isEmpty())
            assertTrue(scope.events.take(2).all { event -> event.changes.none { it.isConsumed } })
            assertEquals(listOf(false, false), scope.mainSawConsumed)
            assertEquals(1, scope.lastEventIndex)
        }
    }

    @Test fun anInitialAllDownEventCannotSelectOneOfSeveralPointers() {
        val recorder = RecordingCallbacks()
        val scope = PointerScriptScope(listOf(
            PointerFrame(listOf(PointerSample(1L, Offset(50f, 24f)), PointerSample(2L, Offset(30f, 24f)))),
            PointerFrame(listOf(PointerSample(1L, Offset(55f, 24f)), PointerSample(2L, Offset(30f, 24f), pressed = false))),
            frame(55f, pressed = false)))
        scope.run(SeekGestureMode.TAP_TO_SEEK) { recorder.callbacks }.getOrThrow()
        assertEquals(0, recorder.begins)
        assertTrue(recorder.taps.isEmpty())
        assertTrue(scope.events[0].changes.none { it.isConsumed })
        assertEquals(0, scope.lastEventIndex)
    }

    @Test fun aSecondPointerCancelsTheCapturedCallbacksEvenAfterTheProviderChanges() {
        val captured = RecordingCallbacks()
        val replacement = RecordingCallbacks()
        var current = captured.callbacks
        val scope = PointerScriptScope(listOf(frame(50f, onMain = { current = replacement.callbacks }), frame(60f),
            PointerFrame(listOf(PointerSample(1L, Offset(70f, 24f)), PointerSample(2L, Offset(30f, 24f)))),
            frame(80f, pressed = false)))
        scope.run(SeekGestureMode.DRAG_TO_COMMIT) { current }.getOrThrow()
        assertEquals(1, captured.begins)
        assertEquals(1, captured.drags.size)
        assertEquals(listOf(RecordingCallbacks.ID), captured.cancels)
        assertTrue(captured.releases.isEmpty())
        assertEquals(0, replacement.begins)
        assertTrue(replacement.cancels.isEmpty())
        assertEquals(2, scope.lastEventIndex)
    }

    @Test fun resizingDuringAnUnownedEventCancelsBeforeAnyCompletion() {
        for (resizedIndex in listOf(0, 1, 2)) {
            val recorder = RecordingCallbacks()
            val replacementSize = IntSize(120, 48)
            val scope = PointerScriptScope(listOf(frame(50f, sizeOnMain = replacementSize.takeIf { resizedIndex == 0 }),
                frame(55f, sizeOnMain = replacementSize.takeIf { resizedIndex == 1 }),
                frame(57f, pressed = false, sizeOnMain = replacementSize.takeIf { resizedIndex == 2 })))
            scope.run(SeekGestureMode.TAP_TO_SEEK) { recorder.callbacks }.getOrThrow()
            assertEquals(0, recorder.begins)
            assertTrue(recorder.taps.isEmpty())
            assertTrue(scope.events.take(resizedIndex + 1).all { event -> event.changes.none { it.isConsumed } })
            assertEquals(resizedIndex, scope.lastEventIndex)
        }
    }

    @Test fun rejectedBeginAndDragOnlyCancelAnActuallyAcceptedGesture() {
        for (rejectBegin in listOf(false, true)) {
            val recorder = RecordingCallbacks(beginAccepted = !rejectBegin, dragAccepted = false)
            val scope = PointerScriptScope(listOf(frame(50f), frame(60f), frame(80f, pressed = false)))
            scope.run(SeekGestureMode.DRAG_TO_COMMIT) { recorder.callbacks }.getOrThrow()
            assertEquals(1, recorder.begins)
            assertEquals(if (rejectBegin) 0 else 1, recorder.drags.size)
            assertEquals(if (rejectBegin) emptyList<String>() else listOf(RecordingCallbacks.ID), recorder.cancels)
            assertTrue(recorder.releases.isEmpty())
            assertEquals(1, scope.lastEventIndex)
        }
    }

    @Test fun pointerScopeCancellationRetiresTheCapturedGestureWithoutARelease() {
        val recorder = RecordingCallbacks()
        val cancellation = CancellationException("pointer owner retired")
        val scope = PointerScriptScope(listOf(frame(50f), frame(60f), frame(70f, initialFailure = cancellation)))
        val result = scope.run(SeekGestureMode.DRAG_TO_COMMIT) { recorder.callbacks }
        assertSame(cancellation, result.exceptionOrNull())
        assertEquals(1, recorder.begins)
        assertEquals(listOf(RecordingCallbacks.ID), recorder.cancels)
        assertTrue(recorder.releases.isEmpty())
        assertTrue(recorder.taps.isEmpty())
    }

    private class RecordingCallbacks(private val beginAccepted: Boolean = true, private val dragAccepted: Boolean = true) {
        var begins = 0
        val drags = mutableListOf<Pair<String, Double>>()
        val taps = mutableListOf<Pair<String, Double>>()
        val releases = mutableListOf<String>()
        val cancels = mutableListOf<String>()
        val callbacks = ProgressCallbacks(
            {
                begins++
                ID.takeIf { beginAccepted }
            },
            {
                id, fraction -> drags.add(id to fraction)
                dragAccepted
            },
            {
                id -> releases.add(id)
                true
            },
            {
                id, fraction -> taps.add(id to fraction)
                true
            },
            { id -> cancels.add(id) },
            { false },
        )
        companion object { const val ID = "captured-progress-gesture" }
    }

    private data class PointerSample(val id: Long, val position: Offset, val pressed: Boolean = true)
    private data class PointerFrame(
        val pointers: List<PointerSample>,
        val consumeMain: Boolean = false,
        val consumeFinal: Boolean = false,
        val sizeOnMain: IntSize? = null,
        val onMain: () -> Unit = {},
        val initialFailure: Throwable? = null,
    )

    private fun frame(
        x: Float,
        y: Float = 24f,
        pressed: Boolean = true,
        consumeMain: Boolean = false,
        consumeFinal: Boolean = false,
        sizeOnMain: IntSize? = null,
        onMain: () -> Unit = {},
        initialFailure: Throwable? = null,
    ) = PointerFrame(listOf(PointerSample(1L, Offset(x, y), pressed)),
        consumeMain, consumeFinal, sizeOnMain, onMain, initialFailure)

    /** Real PointerInputChanges are shared within a pass sequence and freshly created next event. */
    private class PointerScriptScope(private val frames: List<PointerFrame>) : AwaitPointerEventScope {
        override val density = 1f
        override val fontScale = 1f
        private var measuredSize = IntSize(100, 48)
        override val size get() = measuredSize
        override val viewConfiguration = object : ViewConfiguration {
            override val longPressTimeoutMillis = 500L
            override val doubleTapTimeoutMillis = 300L
            override val doubleTapMinTimeMillis = 40L
            override val touchSlop = 8f
        }
        val events: List<PointerEvent> = buildEvents(frames)
        var lastEventIndex = -1
            private set
        private val emptyEvent = PointerEvent(emptyList())
        override val currentEvent get() = if (lastEventIndex < 0) emptyEvent else events[lastEventIndex]
        private var mainDelivered = false
        private var finalDelivered = false
        val requestedPasses = mutableListOf<Pair<Int, PointerEventPass>>()
        val mainSawConsumed = mutableListOf<Boolean>()

        override suspend fun awaitPointerEvent(pass: PointerEventPass): PointerEvent {
            when (pass) {
                PointerEventPass.Initial -> {
                    // Even an adapter which skips Final still allows the old event's consumers to
                    // run before it receives fresh unconsumed changes at the next Initial.
                    finishCurrentEvent()
                    val next = lastEventIndex + 1
                    check(next < frames.size) { "Pointer script exhausted before the adapter completed" }
                    frames[next].initialFailure?.let { throw it }
                    lastEventIndex = next
                    mainDelivered = false
                    finalDelivered = false
                }
                PointerEventPass.Main -> deliverMain()
                PointerEventPass.Final -> finishCurrentEvent()
            }
            requestedPasses.add(lastEventIndex to pass)
            return currentEvent
        }

        fun run(mode: SeekGestureMode, currentCallbacks: () -> ProgressCallbacks): Result<Unit> {
            var completion: Result<Unit>? = null
            val block: suspend AwaitPointerEventScope.() -> Unit = { awaitProgressBarGesture(mode, currentCallbacks) }
            block.startCoroutine(this, object : Continuation<Unit> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<Unit>) { completion = result }
            })
            finishCurrentEvent()
            return requireNotNull(completion) { "Restricted pointer fixture did not complete synchronously" }
        }

        private fun deliverMain() {
            if (lastEventIndex < 0 || mainDelivered) return
            mainDelivered = true
            mainSawConsumed.add(currentEvent.changes.any { it.isConsumed })
            val frame = frames[lastEventIndex]
            if (frame.consumeMain) currentEvent.changes.forEach { it.consume() }
            frame.sizeOnMain?.let { measuredSize = it }
            frame.onMain()
        }

        private fun finishCurrentEvent() {
            deliverMain()
            if (lastEventIndex < 0 || finalDelivered) return
            finalDelivered = true
            if (frames[lastEventIndex].consumeFinal) currentEvent.changes.forEach { it.consume() }
        }

        private companion object {
            fun buildEvents(frames: List<PointerFrame>): List<PointerEvent> {
                val previous = mutableMapOf<Long, Pair<Long, PointerSample>>()
                return frames.mapIndexed { index, frame ->
                    val time = (index + 1L) * 16L
                    val changes = frame.pointers.map { pointer ->
                        val last = previous[pointer.id]
                        PointerInputChange(id = PointerId(pointer.id), uptimeMillis = time,
                            position = pointer.position, pressed = pointer.pressed,
                            previousUptimeMillis = last?.first ?: time,
                            previousPosition = last?.second?.position ?: pointer.position,
                            previousPressed = last?.second?.pressed ?: false, isInitiallyConsumed = false)
                    }
                    frame.pointers.forEach { pointer ->
                        if (pointer.pressed) previous[pointer.id] = time to pointer else previous.remove(pointer.id)
                    }
                    PointerEvent(changes)
                }
            }
        }
    }
}
