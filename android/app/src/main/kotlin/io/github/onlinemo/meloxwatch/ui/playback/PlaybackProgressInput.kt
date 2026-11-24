package io.github.onlinemo.meloxwatch.ui.playback

import androidx.annotation.MainThread
import io.github.onlinemo.meloxwatch.model.QueueSeekBinding
import io.github.onlinemo.meloxwatch.model.QueueSeekRequest
import io.github.onlinemo.meloxwatch.playback.input.SeekGestureContext
import io.github.onlinemo.meloxwatch.playback.input.SeekGestureDraft
import io.github.onlinemo.meloxwatch.playback.input.SeekGestureMode
import io.github.onlinemo.meloxwatch.playback.input.SeekGestureSurface
import io.github.onlinemo.meloxwatch.playback.input.SeekGestures
import io.github.onlinemo.meloxwatch.playback.input.SeekProgressSettings
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ownerId changes with focus, surface or any observable playback identity; null means unfocused.
 * controlsStateKey includes the actual saved settings, save status and input direction. A change
 * invalidates the gesture even if the resulting progress mode happens to be the same.
 */
data class PlaybackProgressInputContext(
    val ownerId: String?,
    val foreground: Boolean,
    val canSeek: Boolean,
    val positionMs: Long,
    val controlsStateKey: String,
    val settings: SeekProgressSettings = SeekProgressSettings(),
) {
    init {
        require(ownerId == null || ownerId.isNotBlank())
        require(positionMs >= 0L && controlsStateKey.isNotBlank())
    }
}

enum class PlaybackProgressInputPhase { IDLE, ACQUIRING, PREVIEW, COMMITTING, CANCELLING }
enum class PlaybackProgressInputIssue { BINDING_UNAVAILABLE, INVALID_FRACTION, SEEK_REJECTED, REQUEST_FAILED }

/** An issue retains its captured owner; contextChanged also checks its saved controls and eligibility. */
data class PlaybackProgressInputState(
    val gestureId: String? = null,
    val phase: PlaybackProgressInputPhase = PlaybackProgressInputPhase.IDLE,
    val previewFraction: Double? = null,
    val previewPositionMs: Long? = null,
    val issue: PlaybackProgressInputIssue? = null,
    val issueOwnerId: String? = null,
)

/**
 * One instance owns one progress surface. All methods, callbacks and scope continuations run on
 * the same UI owner thread (normally Main.immediate). Binding/seek callbacks must be main-safe,
 * bounded and cancellable; this helper never creates a controller, player or periodic prefetch.
 *
 * isBindingCurrent is a required, synchronous validation of the captured owner and currently
 * observable service/queue/content/backend identity. It must not request a fresh binding. The
 * service still validates every field of the original binding when accepting the seek.
 *
 * The caller invokes contextChanged/invalidate immediately on lifecycle, focus, surface, saved
 * controls or playback identity changes. Progress clock updates alone do not change the owner.
 * It coordinates MAIN_PROGRESS and INDEPENDENT_CONTROLS before beginning either gesture. The
 * helper's own pending phase is not a change to the backend's canSeek capability or UI owner.
 * Every non-IDLE helper reserves that shared input slot, including a closed, cancelling helper.
 */
@MainThread
class PlaybackProgressInput(
    private val scope: CoroutineScope,
    private val surface: SeekGestureSurface,
    private val currentContext: () -> PlaybackProgressInputContext,
    private val acquireBinding: suspend () -> QueueSeekBinding?,
    private val isBindingCurrent: (PlaybackProgressInputContext, QueueSeekBinding) -> Boolean,
    private val dispatchSeek: suspend (QueueSeekRequest) -> Boolean,
) {
    private val mutableState = MutableStateFlow(PlaybackProgressInputState())
    val state = mutableState.asStateFlow()
    private var active: Gesture? = null
    private var inFlight: Gesture? = null
    private var ownedIssue: OwnedIssue? = null
    private var closed = false

    /** Call only after a horizontal drag has crossed slop, or for a completed explicit action. */
    fun begin(): String? {
        if (closed || !scope.isActive || active != null || inFlight != null) return null
        val context = currentContext()
        if (closed || !scope.isActive || active != null || inFlight != null || !eligible(context)) return null
        val gesture = Gesture(UUID.randomUUID().toString(), context)
        active = gesture
        inFlight = gesture
        ownedIssue = null
        mutableState.value = PlaybackProgressInputState(gesture.id, PlaybackProgressInputPhase.ACQUIRING)
        // Enter the try/finally before any suspension, including cancellation of the caller scope.
        gesture.job = scope.launch(start = CoroutineStart.UNDISPATCHED) { runGesture(gesture) }
        return gesture.id.takeIf { active === gesture }
    }

    fun drag(gestureId: String, fraction: Double): Boolean {
        val gesture = editable(gestureId) ?: return false
        if (!validFraction(fraction)) return rejectFraction(gesture)
        if (gesture.context.settings.mode != SeekGestureMode.DRAG_TO_COMMIT) {
            retire(gesture)
            return false
        }
        val context = validatedContext(gesture, gesture.binding) ?: return discard(gesture)
        gesture.fraction = fraction
        gesture.draft?.let { draft ->
            gesture.draft = SeekGestures.drag(draft, draft.gestureId, coreContext(context, draft.binding), fraction).draft
            if (gesture.draft == null) return discard(gesture)
        }
        publishPreview(gesture)
        return true
    }

    fun release(gestureId: String): Boolean {
        val gesture = editable(gestureId) ?: return false
        if (gesture.context.settings.mode != SeekGestureMode.DRAG_TO_COMMIT || gesture.fraction == null) {
            retire(gesture)
            return false
        }
        if (validatedContext(gesture, gesture.binding) == null) return discard(gesture)
        return gesture.completion.complete(Completion.RELEASE)
    }

    /** A cancelled pointer or a drag must never call this completed-tap entry point. */
    fun tap(gestureId: String, fraction: Double): Boolean {
        val gesture = editable(gestureId) ?: return false
        if (!validFraction(fraction)) return rejectFraction(gesture)
        if (gesture.context.settings.mode != SeekGestureMode.TAP_TO_SEEK) {
            retire(gesture)
            return false
        }
        if (validatedContext(gesture, gesture.binding) == null) return discard(gesture)
        gesture.fraction = fraction
        publishPreview(gesture)
        return gesture.completion.complete(Completion.TAP)
    }

    /** True means this accessibility intent was accepted, not that audio has already moved. */
    fun setProgress(fraction: Double): Boolean {
        if (!validFraction(fraction)) return false
        val gestureId = begin() ?: return false
        return when (active?.context?.settings?.mode) {
            SeekGestureMode.DRAG_TO_COMMIT -> drag(gestureId, fraction) && release(gestureId)
            SeekGestureMode.TAP_TO_SEEK -> tap(gestureId, fraction)
            null -> false
        }
    }

    fun cancel(gestureId: String) {
        inFlight?.takeIf { it.id == gestureId }?.let { retire(it) }
    }

    fun contextChanged() {
        clearObsoleteIssue()
        val gesture = inFlight ?: return
        if (gesture.retired) return
        // A consumed preview ID can cease to be current after an accepted seek. Its response only
        // renders while the captured UI owner/controls remain current; it does not reuse a binding.
        val binding = gesture.binding.takeIf { active === gesture }
        if (validatedContext(gesture, binding) == null) retire(gesture)
    }

    fun invalidate() {
        clearIssue()
        inFlight?.let { retire(it) }
    }

    fun close() {
        closed = true
        invalidate()
    }

    private suspend fun runGesture(gesture: Gesture) {
        try {
            currentCoroutineContext().ensureActive()
            if (gesture.retired || active !== gesture) return
            val binding = acquireBinding()
            currentCoroutineContext().ensureActive()
            val context = validatedContext(gesture, binding) ?: return retire(gesture)
            if (binding == null) {
                retire(gesture, PlaybackProgressInputIssue.BINDING_UNAVAILABLE)
                return
            }
            val draft = SeekGestures.begin(coreContext(context, binding), surface, gesture.context.positionMs)
                ?: return retire(gesture)
            gesture.binding = binding
            gesture.draft = draft
            gesture.fraction?.takeIf { draft.mode == SeekGestureMode.DRAG_TO_COMMIT }?.let { fraction ->
                gesture.draft = SeekGestures.drag(draft, draft.gestureId, coreContext(context, binding), fraction).draft
            }
            if (gesture.draft == null) return retire(gesture)
            publishPreview(gesture)
            currentCoroutineContext().ensureActive()
            // An immediate state observer can retire an undispatched start before job assignment.
            if (gesture.retired || active !== gesture) return
            val completion = gesture.completion.await()
            currentCoroutineContext().ensureActive()
            val finalContext = validatedContext(gesture, binding) ?: return retire(gesture)
            val currentDraft = gesture.draft ?: return retire(gesture)
            val decision = when (completion) {
                Completion.RELEASE -> SeekGestures.release(currentDraft, currentDraft.gestureId, coreContext(finalContext, binding))
                Completion.TAP -> SeekGestures.tap(currentDraft, currentDraft.gestureId, coreContext(finalContext, binding),
                    gesture.fraction ?: return retire(gesture))
            }
            // Consume both UI and core authority before dispatch, so repeated completion is inert.
            active = null
            gesture.draft = null
            gesture.fraction = null
            val request = decision.request ?: return
            mutableState.value = PlaybackProgressInputState(gesture.id, PlaybackProgressInputPhase.COMMITTING)
            if (validatedContext(gesture, binding) == null) return retire(gesture)
            currentCoroutineContext().ensureActive()
            val accepted = dispatchSeek(request)
            currentCoroutineContext().ensureActive()
            if (validatedContext(gesture, null) == null) return retire(gesture)
            if (!accepted) recordIssue(gesture, PlaybackProgressInputIssue.SEEK_REJECTED)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            val issue = PlaybackProgressInputIssue.REQUEST_FAILED.takeIf { validatedContext(gesture, null) != null }
            retire(gesture, issue)
        } finally {
            finish(gesture)
        }
    }

    private fun finish(gesture: Gesture) {
        var retainedIssue: OwnedIssue? = null
        try {
            val issue = gesture.issue
            if (issue != null && ownedIssue === issue && !closed && scope.isActive) {
                // Validate while the original slot is occupied. The context callback may retire
                // this owner; releasing first would let it begin a replacement during cleanup.
                val context = currentContext()
                if (gesture.issue === issue && ownedIssue === issue && inFlight === gesture &&
                    !closed && scope.isActive && contextMatches(issue.context, context)) retainedIssue = issue
            }
        } finally {
            gesture.draft = null
            gesture.binding = null
            gesture.fraction = null
            gesture.issue = null
            if (active === gesture) active = null
            if (inFlight === gesture) {
                inFlight = null
                ownedIssue = retainedIssue
                mutableState.value = PlaybackProgressInputState(issue = retainedIssue?.issue,
                    issueOwnerId = retainedIssue?.context?.ownerId)
            }
        }
    }

    private fun clearObsoleteIssue() {
        val issue = ownedIssue ?: return
        val context = if (!closed && scope.isActive) currentContext() else null
        // A synchronous observer can start another idle request during currentContext. Its issue
        // has a different token even if it has the same owner and error kind.
        if (ownedIssue === issue && (closed || !scope.isActive || context == null || !contextMatches(issue.context, context))) clearIssue()
    }

    private fun clearIssue() {
        ownedIssue = null
        inFlight?.issue = null
        if (mutableState.value.issue != null) {
            mutableState.value = mutableState.value.copy(issue = null, issueOwnerId = null)
        }
    }

    private fun recordIssue(gesture: Gesture, issue: PlaybackProgressInputIssue) {
        val owned = OwnedIssue(gesture.context, issue)
        gesture.issue = owned
        ownedIssue = owned
    }

    private fun editable(gestureId: String): Gesture? = active?.takeIf {
        it.id == gestureId && !it.retired && !it.completion.isCompleted
    }

    private fun eligible(context: PlaybackProgressInputContext): Boolean =
        context.ownerId != null && context.foreground && context.canSeek &&
            (surface != SeekGestureSurface.MAIN_PROGRESS || context.settings.showMainProgress)

    private fun contextMatches(expected: PlaybackProgressInputContext, actual: PlaybackProgressInputContext): Boolean =
        eligible(actual) && actual.ownerId == expected.ownerId && actual.controlsStateKey == expected.controlsStateKey &&
            actual.settings == expected.settings

    private fun validatedContext(gesture: Gesture, binding: QueueSeekBinding?): PlaybackProgressInputContext? {
        if (closed || !scope.isActive || gesture.retired || inFlight !== gesture) return null
        if (!contextMatches(gesture.context, currentContext())) return null
        if (binding != null && !isBindingCurrent(gesture.context, binding)) return null
        return currentContext().takeIf {
            !closed && scope.isActive && !gesture.retired && inFlight === gesture && contextMatches(gesture.context, it)
        }
    }

    private fun coreContext(context: PlaybackProgressInputContext, binding: QueueSeekBinding) =
        SeekGestureContext(binding, context.ownerId, context.foreground, context.canSeek, context.settings)

    private fun publishPreview(gesture: Gesture) {
        if (active !== gesture || gesture.retired) return
        mutableState.value = PlaybackProgressInputState(gesture.id,
            if (gesture.binding == null) PlaybackProgressInputPhase.ACQUIRING else PlaybackProgressInputPhase.PREVIEW,
            gesture.fraction, gesture.draft?.takeIf { it.hasDragUpdate }?.previewPositionMs)
    }

    private fun rejectFraction(gesture: Gesture): Boolean {
        val issue = PlaybackProgressInputIssue.INVALID_FRACTION.takeIf { validatedContext(gesture, null) != null }
        retire(gesture, issue)
        return false
    }

    private fun discard(gesture: Gesture): Boolean {
        retire(gesture)
        return false
    }

    private fun retire(gesture: Gesture, issue: PlaybackProgressInputIssue? = null) {
        if (inFlight !== gesture || gesture.retired) return
        gesture.retired = true
        gesture.draft = null
        gesture.fraction = null
        if (active === gesture) active = null
        if (issue != null) recordIssue(gesture, issue)
        mutableState.value = PlaybackProgressInputState(gesture.id, PlaybackProgressInputPhase.CANCELLING,
            issue = issue, issueOwnerId = gesture.issue?.context?.ownerId)
        gesture.job?.cancel()
    }

    private fun validFraction(fraction: Double) = fraction.isFinite() && fraction in 0.0..1.0
    private enum class Completion { RELEASE, TAP }
    private class OwnedIssue(val context: PlaybackProgressInputContext, val issue: PlaybackProgressInputIssue)
    private class Gesture(val id: String, val context: PlaybackProgressInputContext) {
        val completion = CompletableDeferred<Completion>()
        var fraction: Double? = null
        var binding: QueueSeekBinding? = null
        var draft: SeekGestureDraft? = null
        var issue: OwnedIssue? = null
        var retired = false
        var job: Job? = null
    }
}
