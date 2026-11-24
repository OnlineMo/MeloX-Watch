package io.github.onlinemo.meloxwatch.ui.watch

import android.view.ViewConfiguration
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.rotary.RotaryScrollEvent
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Input focus is distinct from accessibility focus. Text fields/child targets keep their focus. */
@Composable
fun Modifier.watchRotaryInput(
    visible: Boolean,
    ownerKey: Any?,
    onFocus: (Boolean, Boolean) -> Unit = { _, _ -> },
    onScroll: (RotaryScrollEvent) -> Boolean,
): Modifier {
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val requester = remember(ownerKey) { FocusRequester() }
    var directlyFocused by remember(ownerKey) { mutableStateOf(false) }
    var containsFocus by remember(ownerKey) { mutableStateOf(false) }
    val focusCallback by rememberUpdatedState(onFocus)
    val scrollCallback by rememberUpdatedState(onScroll)
    LaunchedEffect(visible, windowFocused, ownerKey) {
        if (visible && windowFocused) {
            if (!containsFocus) requester.requestFocus()
            focusCallback(containsFocus, directlyFocused)
        }
        else focusCallback(false, false)
    }
    DisposableEffect(ownerKey) {
        onDispose { focusCallback(false, false) }
    }
    return this.onRotaryScrollEvent { event ->
        visible && windowFocused && directlyFocused && scrollCallback(event)
    }.focusRequester(requester).onFocusChanged {
        directlyFocused = it.isFocused
        containsFocus = it.hasFocus
        focusCallback(visible && windowFocused && it.hasFocus, visible && windowFocused && it.isFocused)
    }.focusable(visible)
}

/** Undo Compose's pixel scaling with the current system factor, rather than treating pixels as steps. */
@Composable
fun rememberRotaryAxisSteps(): (RotaryScrollEvent) -> Double? {
    val context = LocalContext.current
    val factor = ViewConfiguration.get(context).scaledVerticalScrollFactor.toDouble()
    return remember(factor) { { event ->
        event.verticalScrollPixels.toDouble().takeIf { it.isFinite() && factor.isFinite() && factor > 0.0 }?.div(factor)
    } }
}

/** At most one list scroll is in flight. A hidden page cancels it and never queues a burst. */
@Composable
fun rememberWatchRotaryScroll(listState: LazyListState, visible: Boolean): (RotaryScrollEvent) -> Boolean {
    val scope = rememberCoroutineScope()
    val maximum = with(LocalDensity.current) { 80.dp.toPx() }
    var pending by remember(listState) { mutableStateOf<Job?>(null) }
    DisposableEffect(listState, visible) {
        onDispose { pending?.cancel() }
    }
    return remember(listState, visible, scope, maximum) { { event ->
        val pixels = event.verticalScrollPixels
        if (!visible || !pixels.isFinite() || pixels == 0f) false else {
            if (pending == null) {
                val job = scope.launch(start = CoroutineStart.LAZY) { listState.scrollBy(pixels.coerceIn(-maximum, maximum)) }
                pending = job
                job.invokeOnCompletion { if (pending === job) pending = null }
                job.start()
            }
            true
        }
    } }
}
