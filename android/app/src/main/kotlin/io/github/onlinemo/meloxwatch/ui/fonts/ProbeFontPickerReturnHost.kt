package io.github.onlinemo.meloxwatch.ui.fonts

import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Register once at the Activity composition root, outside any Window/route visibility guard. */
@Composable
fun rememberProbeFontPickerLauncher(
    picker: ProbeFontPickerViewModel,
): (ProbeFontPickerTicket, Intent) -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        picker.awaitingPlatformTicket()?.let { ticket ->
            picker.onPickerResult(ticket, result.resultCode, result.data?.data, result.data?.flags ?: 0)
        }
    }
    return remember(picker, launcher) {
        { ticket, intent ->
            check(picker.awaitingPlatformTicket() === ticket) { "Font picker launch retired" }
            launcher.launch(intent)
        }
    }
}

/**
 * Host updates and editor working changes do not restart this effect. It borrows the latest
 * host only for admission; the single imported return identity stays stable while importing.
 * A hidden Window preserves an unconsumed return for its fresh presentation, never for save.
 */
@Composable
fun ProbeFontPickerReturnHost(
    picker: ProbeFontPickerViewModel,
    routeIdentity: ProbeFontPickerRoute,
    pageEpoch: Any,
    host: WatchFontHostValue?,
    visible: Boolean,
    visibleNow: () -> Boolean,
) {
    val pickerState by picker.state.collectAsState()
    val currentHost by rememberUpdatedState(host)
    val currentVisibleNow by rememberUpdatedState(visibleNow)
    val ticket = pickerState.pendingReturn
    LaunchedEffect(picker, routeIdentity, pageEpoch, ticket, visible) {
        if (!visible || ticket == null || !currentVisibleNow()) return@LaunchedEffect
        val deadline = SystemClock.uptimeMillis() + HOST_READY_TIMEOUT_MILLIS
        while (picker.isPendingReturn(routeIdentity, ticket)) {
            if (!currentVisibleNow()) return@LaunchedEffect
            val remaining = deadline - SystemClock.uptimeMillis()
            val ready = if (remaining > 0L) withTimeoutOrNull(remaining) {
                snapshotFlow {
                    currentHost?.let { value ->
                        !value.loading && value.editorState?.let { state ->
                            state.presentationActive && !state.working
                        } == true
                    } == true
                }.first { it }
            } else null
            if (ready == null) {
                // The synchronous Window gate may already have retired memory before its
                // Compose visibility emission arrives. Preserve that hidden, unconsumed ticket.
                if (currentVisibleNow()) picker.expireReturn(routeIdentity, ticket)
                return@LaunchedEffect
            }
            if (!currentVisibleNow()) return@LaunchedEffect
            val admitted = currentHost ?: continue
            when (picker.consumeReturn(routeIdentity, ticket, admitted)) {
                ProbeFontPickerConsumption.CONSUMED, ProbeFontPickerConsumption.REJECTED -> return@LaunchedEffect
                ProbeFontPickerConsumption.DEFERRED -> delay(ADMISSION_RETRY_MILLIS)
            }
        }
    }
}

private const val HOST_READY_TIMEOUT_MILLIS = 2_500L
private const val ADMISSION_RETRY_MILLIS = 16L
