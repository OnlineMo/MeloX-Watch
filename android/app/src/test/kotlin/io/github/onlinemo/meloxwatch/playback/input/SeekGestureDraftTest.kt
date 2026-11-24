package io.github.onlinemo.meloxwatch.playback.input

import io.github.onlinemo.meloxwatch.model.QueueSeekBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SeekGestureDraftTest {
    private val binding = QueueSeekBinding("preview-1", "session-1", 2L, "queue-1", 3L, "entry-1", "location-1", 4L, 60_000L)
    private val context = SeekGestureContext(binding, "playback-page", foreground = true, canSeek = true)
    private fun begin(owner: SeekGestureContext = context, surface: SeekGestureSurface = SeekGestureSurface.MAIN_PROGRESS,
        position: Long = 10_000L) = requireNotNull(SeekGestures.begin(owner, surface, position))
    private fun moved(draft: SeekGestureDraft = begin(), owner: SeekGestureContext = context) =
        requireNotNull(SeekGestures.drag(draft, draft.gestureId, owner, 0.5).draft)

    @Test fun dragIsOnlyAPreviewUntilAValidReleaseAndPreservesPlaybackIntent() {
        val draft = begin()
        val preview = SeekGestures.drag(draft, draft.gestureId, context, 0.5)
        assertNull(preview.request)
        assertEquals(30_000L, preview.draft?.previewPositionMs)
        val released = SeekGestures.release(preview.draft, draft.gestureId, context)
        assertNull(released.draft)
        val request = requireNotNull(released.request)
        assertSame(binding, request.binding)
        assertEquals(30_000L, request.positionMs)
        assertFalse(request.explicitPlay)
    }

    @Test fun tapModeCommitsTheCompletedTapToItsIntendedPosition() {
        val owner = context.copy(settings = SeekProgressSettings(mode = SeekGestureMode.TAP_TO_SEEK))
        val draft = begin(owner)
        val tapped = SeekGestures.tap(draft, draft.gestureId, owner, 0.75)
        assertNull(tapped.draft)
        assertEquals(45_000L, tapped.request?.positionMs)
        assertSame(binding, tapped.request?.binding)
        assertFalse(requireNotNull(tapped.request).explicitPlay)
    }

    @Test fun tapInDragModeAndReleaseInTapModeCannotCommitTheWrongGesture() {
        val drag = begin()
        assertNull(SeekGestures.tap(drag, drag.gestureId, context, 0.5).request)
        val tapOwner = context.copy(settings = SeekProgressSettings(mode = SeekGestureMode.TAP_TO_SEEK))
        val tap = begin(tapOwner)
        assertNull(SeekGestures.drag(tap, tap.gestureId, tapOwner, 0.5).request)
        assertNull(SeekGestures.release(tap, tap.gestureId, tapOwner).request)
    }

    @Test fun dragWithoutMovementOrTapAtTheCurrentPositionIsANoop() {
        val draft = begin(position = 30_000L)
        assertNull(SeekGestures.release(draft, draft.gestureId, context).request)
        val unchanged = moved(draft)
        assertNull(SeekGestures.release(unchanged, unchanged.gestureId, context).request)
        val tapOwner = context.copy(settings = SeekProgressSettings(mode = SeekGestureMode.TAP_TO_SEEK))
        val tap = begin(tapOwner, position = 30_000L)
        assertNull(SeekGestures.tap(tap, tap.gestureId, tapOwner, 0.5).request)
    }

    @Test fun cancellationMakesALateReleaseHarmless() {
        val draft = moved()
        val cancelled = SeekGestures.cancel(draft, draft.gestureId)
        assertNull(cancelled.draft)
        assertNull(cancelled.request)
        assertNull(SeekGestures.release(cancelled.draft, draft.gestureId, context).request)
    }

    @Test fun releaseConsumesTheDraftBeforeAnAcknowledgementOrDuplicateEvent() {
        val draft = moved()
        val first = SeekGestures.release(draft, draft.gestureId, context)
        assertNotNull(first.request)
        val duplicate = SeekGestures.release(first.draft, draft.gestureId, context)
        assertNull(duplicate.request)
        assertNull(duplicate.draft)
    }

    @Test fun oldGestureTokensCannotChangeCancelOrSubmitTheCurrentDraft() {
        val previous = begin()
        val current = moved(begin())
        val drag = SeekGestures.drag(current, previous.gestureId, context, 0.75)
        val release = SeekGestures.release(current, previous.gestureId, context)
        val tap = SeekGestures.tap(current, previous.gestureId, context, 0.75)
        val cancel = SeekGestures.cancel(current, previous.gestureId)
        for (decision in listOf(drag, release, tap, cancel)) {
            assertSame(current, decision.draft)
            assertNull(decision.request)
        }
    }

    @Test fun everyServiceBindingFieldParticipatesInStaleGestureRejection() {
        val draft = moved()
        val changedBindings = listOf(binding.copy(previewId = "preview-2"), binding.copy(sessionId = "session-2"),
            binding.copy(backendGeneration = 3L), binding.copy(generationId = "queue-2"), binding.copy(queueRevision = 4L),
            binding.copy(queueEntryId = "entry-2"), binding.copy(locationId = "location-2"),
            binding.copy(contentRevision = 5L), binding.copy(durationMs = 61_000L), binding.copy(playbackCycle = 1L))
        for (changed in changedBindings) {
            val result = SeekGestures.release(draft, draft.gestureId, context.copy(binding = changed))
            assertNull(result.draft)
            assertNull(result.request)
        }
    }

    @Test fun focusLossBackgroundAndDisabledSeekCannotSubmitAnOldPreview() {
        val draft = moved()
        for (owner in listOf(context.copy(ownerId = null), context.copy(foreground = false), context.copy(canSeek = false), context.copy(binding = null))) {
            assertNull(SeekGestures.release(draft, draft.gestureId, owner).request)
            assertNull(SeekGestures.begin(owner, SeekGestureSurface.MAIN_PROGRESS, 10_000L))
        }
        val anotherOwner = context.copy(ownerId = "queue-page")
        assertNull(SeekGestures.release(draft, draft.gestureId, anotherOwner).request)
        assertNotNull(SeekGestures.begin(anotherOwner, SeekGestureSurface.MAIN_PROGRESS, 10_000L))
    }

    @Test fun hidingMainProgressRetainsTheIndependentSeekPath() {
        val hidden = context.copy(settings = SeekProgressSettings(showMainProgress = false))
        assertNull(SeekGestures.begin(hidden, SeekGestureSurface.MAIN_PROGRESS, 10_000L))
        val independent = begin(hidden, SeekGestureSurface.INDEPENDENT_CONTROLS)
        val preview = SeekGestures.drag(independent, independent.gestureId, hidden, 0.5)
        assertEquals(30_000L, SeekGestures.release(preview.draft, independent.gestureId, hidden).request?.positionMs)
        val visible = moved()
        assertNull(SeekGestures.release(visible, visible.gestureId, hidden).request)
    }

    @Test fun changingGestureModeCancelsAnInFlightDraft() {
        val draft = moved()
        val changed = context.copy(settings = SeekProgressSettings(mode = SeekGestureMode.TAP_TO_SEEK))
        val result = SeekGestures.release(draft, draft.gestureId, changed)
        assertNull(result.draft)
        assertNull(result.request)
    }

    @Test fun invalidFractionsCancelWithoutProducingASeekRequest() {
        for (fraction in listOf(-0.1, 1.1, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            val draft = moved()
            val invalid = SeekGestures.drag(draft, draft.gestureId, context, fraction)
            assertNull(invalid.draft)
            assertNull(invalid.request)
            assertNull(SeekGestures.release(invalid.draft, draft.gestureId, context).request)
        }
    }

    @Test fun longDurationEndpointsRemainExactAndBounded() {
        val large = binding.copy(durationMs = Long.MAX_VALUE)
        val owner = context.copy(binding = large)
        val draft = begin(owner, position = -1L)
        assertEquals(0L, draft.initialPositionMs)
        val end = SeekGestures.drag(draft, draft.gestureId, owner, 1.0)
        val request = requireNotNull(SeekGestures.release(end.draft, draft.gestureId, owner).request)
        assertEquals(Long.MAX_VALUE, request.positionMs)
        assertSame(large, request.binding)
        val back = begin(owner, position = Long.MAX_VALUE)
        val start = SeekGestures.drag(back, back.gestureId, owner, 0.0)
        assertEquals(0L, SeekGestures.release(start.draft, back.gestureId, owner).request?.positionMs)
    }
}
