package io.github.onlinemo.meloxwatch.ui.lyrics.entry

import androidx.annotation.MainThread
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsClockMapping
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsDocument
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsSourceFence
import io.github.onlinemo.meloxwatch.model.QueueSeekBinding
import io.github.onlinemo.meloxwatch.model.QueueSeekRequest
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsPageIdentity
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsPlayPreview
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

data class LyricsPlayContext(
    val identity: LyricsPageIdentity,
    val queueRevision: Long,
    val audio: LyricsSourceFence,
    val document: LyricsDocument,
    val mapping: LyricsClockMapping,
    val durationMs: Long,
    val visible: Boolean,
    val canSeek: Boolean,
) {
    init {
        require(queueRevision >= 0L && durationMs > 0L)
        require(identity.trackKey == audio.trackKey && identity.sourceGeneration == audio.sourceGeneration)
    }
}

enum class LyricsPlayPhase { IDLE, ACQUIRING, CONFIRMATION, VERIFYING, COMMITTING, CANCELLING }
enum class LyricsPlayIssue { BINDING_UNAVAILABLE, STALE, SOURCE_UNAVAILABLE, SEEK_REJECTED, REQUEST_FAILED }

data class LyricsPlayState(
    val phase: LyricsPlayPhase = LyricsPlayPhase.IDLE,
    val confirmationId: String? = null,
    val preview: LyricsPlayPreview? = null,
    val issue: LyricsPlayIssue? = null,
)

/**
 * A line press first obtains a real service ticket and then waits for an explicit sound-producing
 * confirmation. Source verification is cancellable and occurs outside storage transactions. The
 * original ticket is rechecked after every suspension; requesting another ticket is not validation.
 * Call contextChanged immediately on page/lifecycle, document, offset, queue or backend changes.
 */
@MainThread
class LyricsPlayConfirmation(
    private val scope: CoroutineScope,
    private val currentContext: () -> LyricsPlayContext?,
    private val playback: LyricsPlaybackPort,
    private val verifyCurrentSource: suspend (LyricsPlayContext) -> Boolean,
    private val retainContext: (LyricsPlayContext) -> AutoCloseable?,
) {
    constructor(scope: CoroutineScope, currentContext: () -> LyricsPlayContext?, playback: LyricsPlaybackPort,
        verifyCurrentSource: suspend (LyricsPlayContext) -> Boolean) :
        this(scope, currentContext, playback, verifyCurrentSource, { EmptyLyricsContextReference })

    private val mutableState = MutableStateFlow(LyricsPlayState())
    val state = mutableState.asStateFlow()
    private var active: Action? = null
    private var inFlight: Action? = null
    private var closed = false
    val busy: Boolean get() = inFlight != null

    /** Cancelling a waiter cannot release the original action's retained verification input. */
    internal suspend fun awaitCleanup() {
        inFlight?.job?.join()
    }

    fun begin(preview: LyricsPlayPreview): String? {
        if (closed || !scope.isActive || busy || playback.busy) return null
        val context = currentContext() ?: return null
        if (!matchesPreview(context, preview) || !eligible(context)) return null
        val reference = retainContext(context) ?: return null
        val action = Action(UUID.randomUUID().toString(), context, preview, reference)
        active = action
        inFlight = action
        mutableState.value = LyricsPlayState(LyricsPlayPhase.ACQUIRING, action.id, preview)
        action.job = scope.launch(start = CoroutineStart.UNDISPATCHED) { run(action) }
        return action.id.takeIf { active === action }
    }

    fun confirm(confirmationId: String): Boolean {
        val action = active?.takeIf { it.id == confirmationId } ?: return false
        if (mutableState.value.phase != LyricsPlayPhase.CONFIRMATION) return false
        if (!stillCurrent(action, action.binding)) {
            retire(action)
            return false
        }
        return action.confirmed.complete(Unit)
    }

    fun cancel(confirmationId: String) {
        inFlight?.takeIf { it.id == confirmationId }?.let(::retire)
    }

    fun contextChanged() {
        val action = active ?: return
        if (!stillCurrent(action, action.binding)) retire(action)
    }

    fun close() {
        closed = true
        inFlight?.let(::retire)
    }

    private suspend fun run(action: Action) {
        try {
            val binding = playback.acquireSeekBinding()
            currentCoroutineContext().ensureActive()
            if (!stillCurrent(action, null)) return
            if (binding == null || !bindingMatches(action.context, binding) || !playback.isSeekBindingCurrent(binding)) {
                fail(action, LyricsPlayIssue.BINDING_UNAVAILABLE)
                return
            }
            action.binding = binding
            mutableState.value = LyricsPlayState(LyricsPlayPhase.CONFIRMATION, action.id, action.preview)
            action.confirmed.await()
            currentCoroutineContext().ensureActive()
            if (!stillCurrent(action, binding)) return
            mutableState.value = LyricsPlayState(LyricsPlayPhase.VERIFYING, action.id, action.preview)
            val readable = verifyCurrentSource(action.context)
            currentCoroutineContext().ensureActive()
            if (!stillCurrent(action, binding)) return
            if (!readable) {
                fail(action, LyricsPlayIssue.SOURCE_UNAVAILABLE)
                return
            }
            mutableState.value = LyricsPlayState(LyricsPlayPhase.COMMITTING, action.id, action.preview)
            val accepted = playback.dispatchSeek(QueueSeekRequest(binding, action.preview.target.mediaPositionMs, explicitPlay = true))
            currentCoroutineContext().ensureActive()
            if (active === action) {
                mutableState.value = LyricsPlayState(issue = if (accepted) null else LyricsPlayIssue.SEEK_REJECTED)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (active === action && stillCurrent(action, action.binding)) fail(action, LyricsPlayIssue.REQUEST_FAILED)
        } finally {
            try { action.reference.close() }
            finally {
                if (active === action) active = null
                if (inFlight === action) {
                    inFlight = null
                    // Idle becomes visible after the input release; waiters join the whole child.
                    if (mutableState.value.confirmationId == action.id) mutableState.value = LyricsPlayState()
                }
            }
        }
    }

    private fun stillCurrent(action: Action, binding: QueueSeekBinding?): Boolean {
        if (closed || active !== action || action.retired || !scope.isActive || playback.busy) return false
        val current = currentContext() ?: return false
        if (!eligible(current) || !sameIntent(action.context, current) || !matchesPreview(current, action.preview)) return false
        return binding == null || bindingMatches(current, binding) && playback.isSeekBindingCurrent(binding)
    }

    private fun retire(action: Action) {
        if (inFlight !== action || action.retired) return
        action.retired = true
        if (active === action) active = null
        mutableState.value = LyricsPlayState(LyricsPlayPhase.CANCELLING, action.id)
        action.job?.cancel()
    }

    private fun fail(action: Action, issue: LyricsPlayIssue) {
        if (active === action) mutableState.value = LyricsPlayState(issue = issue)
    }

    private fun eligible(context: LyricsPlayContext): Boolean {
        if (!context.visible || !context.canSeek) return false
        val clock = playback.clock() ?: return false
        return clock.canSeek && clock.queueEntryId == context.identity.queueEntryId && clock.durationMs == context.durationMs
    }

    private fun bindingMatches(context: LyricsPlayContext, binding: QueueSeekBinding): Boolean =
        binding.generationId == context.identity.queueGenerationId && binding.queueRevision == context.queueRevision &&
            binding.queueEntryId == context.identity.queueEntryId && context.audio.matchesSeekBinding(binding) &&
            binding.durationMs == context.durationMs

    private fun sameIntent(left: LyricsPlayContext, right: LyricsPlayContext): Boolean =
        left.identity == right.identity && left.queueRevision == right.queueRevision && left.audio == right.audio &&
            left.document === right.document && left.mapping == right.mapping && left.durationMs == right.durationMs

    private fun matchesPreview(context: LyricsPlayContext, preview: LyricsPlayPreview): Boolean {
        if (preview.identity != context.identity || preview.clockMapping != context.mapping || preview.durationMs != context.durationMs ||
            preview.target.documentId != context.document.documentId) return false
        val timeline = context.document.timeline
        // This bounded search runs only for an explicit line action, never for a clock sample.
        val index = (0 until timeline.size).firstOrNull { timeline.line(it).id == preview.target.lineId } ?: return false
        return timeline.seekTarget(index, context.durationMs, context.mapping) == preview.target
    }

    private class Action(val id: String, val context: LyricsPlayContext, val preview: LyricsPlayPreview,
        val reference: AutoCloseable) {
        val confirmed = CompletableDeferred<Unit>()
        var binding: QueueSeekBinding? = null
        var job: Job? = null
        var retired = false
    }
}

private object EmptyLyricsContextReference : AutoCloseable {
    override fun close() = Unit
}
