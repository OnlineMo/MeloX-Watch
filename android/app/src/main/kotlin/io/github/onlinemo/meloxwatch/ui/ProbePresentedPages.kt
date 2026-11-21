package io.github.onlinemo.meloxwatch.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

/** Admission follows the actual selector and producer epochs, including a drag that returns. */
@Composable
internal fun rememberProbePresentedPage(
    runtime: ProbePageRuntime,
    kind: ProbePresentedPageKind,
    selection: ProbePageRuntime.SelectionBinding?,
    presented: Boolean,
): ProbePageRuntime.PageBinding? {
    val sourceEpoch by runtime.sourceEpoch.collectAsState()
    val windowEpoch by runtime.windowEpoch.collectAsState()
    val selectionEpoch = selection?.epoch?.collectAsState()?.value
    var bound by remember(runtime, kind) { mutableStateOf<ProbePageRuntime.PageBinding?>(null) }
    DisposableEffect(runtime, kind, selection, selectionEpoch, sourceEpoch, windowEpoch, presented) {
        val exact = if (presented && selection != null) runtime.bindPage(kind, selection) else null
        bound = exact
        onDispose {
            exact?.close()
            if (bound === exact) bound = null
        }
    }
    return bound?.takeIf { it.visibleNow }
}

/** One selector belongs to the actual non-pager route container; neighboring pages never bind. */
@Composable
internal fun rememberProbeSingleSelection(
    runtime: ProbePageRuntime,
    route: ProbePage,
    presented: Boolean,
): ProbePageRuntime.SelectionBinding? {
    val actualRoute by rememberUpdatedState(route)
    val actualPresented by rememberUpdatedState(presented)
    var bound by remember(runtime) { mutableStateOf<ProbePageRuntime.SelectionBinding?>(null) }
    DisposableEffect(runtime, route, presented) {
        val exact = if (presented) runtime.bindSelection {
            ProbePageSelectionSnapshot(actualRoute, actualPresented, settled = true, scrolling = false)
        } else null
        bound = exact
        onDispose {
            exact?.close()
            if (bound === exact) bound = null
        }
    }
    return bound?.takeIf { it.isCurrent }
}

internal fun ProbePage.presentationKind(): ProbePresentedPageKind = when (this) {
    ProbePage.PLAYBACK -> ProbePresentedPageKind.PLAYBACK
    ProbePage.LYRICS -> ProbePresentedPageKind.LYRICS
    ProbePage.APPEARANCE -> ProbePresentedPageKind.APPEARANCE
    ProbePage.LYRICS_FONTS -> ProbePresentedPageKind.LYRICS_FONT
    else -> ProbePresentedPageKind.THEME_UI
}
