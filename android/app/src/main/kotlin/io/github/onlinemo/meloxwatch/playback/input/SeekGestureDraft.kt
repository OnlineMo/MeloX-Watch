package io.github.onlinemo.meloxwatch.playback.input

import io.github.onlinemo.meloxwatch.model.QueueSeekBinding
import io.github.onlinemo.meloxwatch.model.QueueSeekRequest
import java.util.UUID

enum class SeekGestureMode { DRAG_TO_COMMIT, TAP_TO_SEEK }
enum class SeekGestureSurface { MAIN_PROGRESS, INDEPENDENT_CONTROLS }

data class SeekProgressSettings(val showMainProgress: Boolean = true, val mode: SeekGestureMode = SeekGestureMode.DRAG_TO_COMMIT)

data class SeekGestureContext(
    val binding: QueueSeekBinding?,
    val ownerId: String?,
    val foreground: Boolean,
    val canSeek: Boolean,
    val settings: SeekProgressSettings = SeekProgressSettings(),
) {
    init { require(ownerId == null || ownerId.isNotBlank()) }
}

data class SeekGestureDraft(
    val gestureId: String,
    val binding: QueueSeekBinding,
    val ownerId: String,
    val surface: SeekGestureSurface,
    val mode: SeekGestureMode,
    val initialPositionMs: Long,
    val previewPositionMs: Long,
    val hasDragUpdate: Boolean = false,
) {
    init {
        require(UUID.fromString(gestureId).toString() == gestureId && ownerId.isNotBlank())
        require(initialPositionMs in 0L..binding.durationMs && previewPositionMs in 0L..binding.durationMs)
    }
}

data class SeekGestureDecision(val draft: SeekGestureDraft?, val request: QueueSeekRequest? = null)

/** Drafts own no player. All submitted requests still undergo the service's full binding checks. */
object SeekGestures {
    fun begin(context: SeekGestureContext, surface: SeekGestureSurface, positionMs: Long): SeekGestureDraft? {
        val binding = context.binding ?: return null
        val owner = context.ownerId ?: return null
        if (!context.foreground || !context.canSeek || (surface == SeekGestureSurface.MAIN_PROGRESS && !context.settings.showMainProgress)) return null
        val initial = positionMs.coerceIn(0L, binding.durationMs)
        return SeekGestureDraft(UUID.randomUUID().toString(), binding, owner, surface, context.settings.mode, initial, initial)
    }

    fun drag(draft: SeekGestureDraft?, gestureId: String, context: SeekGestureContext, fraction: Double): SeekGestureDecision {
        if (draft == null || gestureId != draft.gestureId) return SeekGestureDecision(draft)
        if (!valid(draft, context)) return SeekGestureDecision(null)
        if (draft.mode != SeekGestureMode.DRAG_TO_COMMIT) return SeekGestureDecision(draft)
        val position = position(fraction, draft.binding.durationMs) ?: return SeekGestureDecision(null)
        return SeekGestureDecision(draft.copy(previewPositionMs = position, hasDragUpdate = true))
    }

    fun release(draft: SeekGestureDraft?, gestureId: String, context: SeekGestureContext): SeekGestureDecision {
        if (draft == null || gestureId != draft.gestureId) return SeekGestureDecision(draft)
        if (!valid(draft, context) || draft.mode != SeekGestureMode.DRAG_TO_COMMIT || !draft.hasDragUpdate || draft.previewPositionMs == draft.initialPositionMs) {
            return SeekGestureDecision(null)
        }
        return SeekGestureDecision(null, QueueSeekRequest(draft.binding, draft.previewPositionMs, explicitPlay = false))
    }

    /** Call only for a completed tap; pointer cancellation or a drag must call cancel instead. */
    fun tap(draft: SeekGestureDraft?, gestureId: String, context: SeekGestureContext, fraction: Double): SeekGestureDecision {
        if (draft == null || gestureId != draft.gestureId) return SeekGestureDecision(draft)
        if (!valid(draft, context) || draft.mode != SeekGestureMode.TAP_TO_SEEK) return SeekGestureDecision(null)
        val target = position(fraction, draft.binding.durationMs) ?: return SeekGestureDecision(null)
        return if (target == draft.initialPositionMs) SeekGestureDecision(null)
            else SeekGestureDecision(null, QueueSeekRequest(draft.binding, target, explicitPlay = false))
    }

    fun cancel(draft: SeekGestureDraft?, gestureId: String): SeekGestureDecision =
        SeekGestureDecision(if (draft?.gestureId == gestureId) null else draft)

    private fun valid(draft: SeekGestureDraft, context: SeekGestureContext): Boolean =
        context.foreground && context.canSeek && context.binding == draft.binding && context.ownerId == draft.ownerId &&
            context.settings.mode == draft.mode && (draft.surface != SeekGestureSurface.MAIN_PROGRESS || context.settings.showMainProgress)

    private fun position(fraction: Double, durationMs: Long): Long? {
        if (!fraction.isFinite() || fraction !in 0.0..1.0) return null
        if (fraction == 0.0) return 0L
        if (fraction == 1.0) return durationMs
        return (fraction * durationMs.toDouble()).toLong().coerceIn(0L, durationMs)
    }
}
