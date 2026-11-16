package io.github.onlinemo.meloxwatch.ui.watch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.onlinemo.meloxwatch.playback.input.SeekGestureMode
import kotlin.math.abs

/**
 * A static, thin watch progress bar with its own 48dp touch area. Null value is indeterminate.
 * gestureKey changes with the UI owner/controls; hiding or replacing the component cancels its
 * exact captured callbacks. The caller provides a precise, localized disabledReason and an
 * independent accessible control path. setProgress is an explicit intent, never a clock tick.
 */
@Composable
fun WatchProgressBar(
    value: Double?,
    mode: SeekGestureMode,
    enabled: Boolean,
    gestureKey: Any?,
    description: String,
    disabledReason: String,
    onBegin: () -> String?,
    onDrag: (String, Double) -> Boolean,
    onRelease: (String) -> Boolean,
    onTap: (String, Double) -> Boolean,
    onCancel: (String) -> Unit,
    onSetProgress: (Double) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val displayed = value?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
    val interactive = enabled && displayed != null
    val reason = disabledReason.ifBlank { "暂时无法调整进度" }
    val callbacks by rememberUpdatedState(ProgressCallbacks(onBegin, onDrag, onRelease, onTap, onCancel, onSetProgress))
    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .semantics {
                    contentDescription = description
                    progressBarRangeInfo = displayed?.let { ProgressBarRangeInfo(it.toFloat(), 0f..1f) }
                        ?: ProgressBarRangeInfo.Indeterminate
                    if (interactive) {
                        setProgress { fraction ->
                            fraction.isFinite() && fraction in 0f..1f && callbacks.setProgress(fraction.toDouble())
                        }
                    } else {
                        disabled()
                        stateDescription = reason
                    }
                }
                .pointerInput(interactive, mode, gestureKey) {
                    if (!interactive) return@pointerInput
                    awaitEachGesture {
                        awaitProgressBarGesture(mode) { callbacks }
                    }
                },
        ) {
            val inset = 4.dp.toPx()
            if (size.width > inset * 2f) {
                val start = Offset(inset, size.height / 2f)
                val end = Offset(size.width - inset, start.y)
                drawLine(WatchPalette.Focus, start, end, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                displayed?.takeIf { it > 0.0 }?.let { fraction ->
                    drawLine(if (interactive) WatchPalette.Text else WatchPalette.Disabled, start,
                        Offset(start.x + (end.x - start.x) * fraction.toFloat(), start.y),
                        strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                }
            }
        }
        if (!interactive) WatchText(reason, Modifier.fillMaxWidth(), enabled = false)
    }
}

internal class ProgressCallbacks(
    val begin: () -> String?,
    val drag: (String, Double) -> Boolean,
    val release: (String) -> Boolean,
    val tap: (String, Double) -> Boolean,
    val cancel: (String) -> Unit,
    val setProgress: (Double) -> Boolean,
)

/** Each invocation captures the callbacks on down and owns at most that one pointer sequence. */
internal suspend fun AwaitPointerEventScope.awaitProgressBarGesture(
    mode: SeekGestureMode,
    currentCallbacks: () -> ProgressCallbacks,
) {
    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    if (down.isConsumed || currentEvent.changes.size != 1) return
    val gestureSize = size
    val pointer = ProgressPointerGesture(mode, gestureSize.width.toDouble(), gestureSize.height.toDouble(),
        viewConfiguration.touchSlop.toDouble(), down.position.x.toDouble(), down.position.y.toDouble(),
        trackInset = 4.dp.toPx().toDouble())
    val handlers = currentCallbacks()
    var gestureId: String? = null
    var completed = false
    try {
        // New events do not retain the previous event's consumption. Check this down after Main
        // before waiting for another Initial; the same rule applies while below horizontal slop.
        if (!awaitUnconsumedProgressEvent(down.id, gestureSize)) return
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (size != gestureSize) break
            val decision = pointer.update(change.position.x.toDouble(), change.position.y.toDouble(),
                change.pressed, change.isConsumed, event.changes.any { it.id != down.id })
            when (decision.action) {
                ProgressPointerAction.WAIT -> if (!awaitUnconsumedProgressEvent(down.id, gestureSize)) break
                ProgressPointerAction.START_DRAG -> {
                    // Horizontal ownership is acquired before a Main-pass pager; vertical input
                    // has yielded without consumption and remains available to the list.
                    change.consume()
                    gestureId = handlers.begin()
                    val id = gestureId ?: break
                    if (!handlers.drag(id, requireNotNull(decision.fraction))) break
                }
                ProgressPointerAction.DRAG -> {
                    change.consume()
                    val id = gestureId ?: break
                    if (!handlers.drag(id, requireNotNull(decision.fraction))) break
                }
                ProgressPointerAction.RELEASE -> {
                    change.consume()
                    val id = gestureId ?: break
                    if (!handlers.drag(id, requireNotNull(decision.fraction))) break
                    completed = handlers.release(id)
                    break
                }
                ProgressPointerAction.TAP -> {
                    if (!awaitUnconsumedProgressEvent(down.id, gestureSize)) break
                    change.consume()
                    gestureId = handlers.begin()
                    val id = gestureId ?: break
                    completed = handlers.tap(id, requireNotNull(decision.fraction))
                    break
                }
                ProgressPointerAction.CANCEL -> break
            }
        }
    } finally {
        if (!completed) gestureId?.let { handlers.cancel(it) }
    }
}

private suspend fun AwaitPointerEventScope.awaitUnconsumedProgressEvent(pointerId: PointerId, gestureSize: IntSize): Boolean {
    val event = awaitPointerEvent(PointerEventPass.Final)
    val change = event.changes.singleOrNull() ?: return false
    return size == gestureSize && change.id == pointerId && !change.isConsumed
}

internal enum class ProgressPointerAction { WAIT, START_DRAG, DRAG, RELEASE, TAP, CANCEL }
internal data class ProgressPointerDecision(val action: ProgressPointerAction, val fraction: Double? = null)

/** Pixel geometry only; the Compose adapter supplies ownership, consumption and pointer identity. */
internal class ProgressPointerGesture(
    private val mode: SeekGestureMode,
    private val width: Double,
    private val height: Double,
    private val slop: Double,
    private val downX: Double,
    private val downY: Double,
    private val trackInset: Double = 0.0,
) {
    private var dragging = false
    private var finished = false
    private val valid = listOf(width, height, slop, downX, downY, trackInset).all { it.isFinite() } &&
        width > trackInset * 2.0 && height > 0.0 && slop >= 0.0 && trackInset >= 0.0 && inside(downX, downY)

    fun update(x: Double, y: Double, pressed: Boolean, consumed: Boolean = false, anotherPointer: Boolean = false): ProgressPointerDecision {
        if (finished) return ProgressPointerDecision(ProgressPointerAction.WAIT)
        if (!valid || !inside(x, y) || consumed || anotherPointer) return finish(ProgressPointerAction.CANCEL)
        val horizontal = abs(x - downX)
        val vertical = abs(y - downY)
        return when (mode) {
            SeekGestureMode.TAP_TO_SEEK -> when {
                horizontal > slop || vertical > slop -> finish(ProgressPointerAction.CANCEL)
                !pressed -> finish(ProgressPointerAction.TAP, fraction(x))
                else -> ProgressPointerDecision(ProgressPointerAction.WAIT)
            }
            SeekGestureMode.DRAG_TO_COMMIT -> when {
                dragging && !pressed -> finish(ProgressPointerAction.RELEASE, fraction(x))
                dragging -> ProgressPointerDecision(ProgressPointerAction.DRAG, fraction(x))
                !pressed -> finish(ProgressPointerAction.CANCEL)
                vertical > slop && vertical >= horizontal -> finish(ProgressPointerAction.CANCEL)
                horizontal > slop && horizontal > vertical -> {
                    dragging = true
                    ProgressPointerDecision(ProgressPointerAction.START_DRAG, fraction(x))
                }
                else -> ProgressPointerDecision(ProgressPointerAction.WAIT)
            }
        }
    }

    private fun inside(x: Double, y: Double) = x.isFinite() && y.isFinite() && x in 0.0..width && y in 0.0..height
    private fun fraction(x: Double) = ((x - trackInset) / (width - trackInset * 2.0)).coerceIn(0.0, 1.0)
    private fun finish(action: ProgressPointerAction, fraction: Double? = null): ProgressPointerDecision {
        finished = true
        return ProgressPointerDecision(action, fraction)
    }
}
