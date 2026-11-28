package io.github.onlinemo.meloxwatch.ui.library

import android.app.Activity
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CancellationException

internal class SourcesEntryLaunchers(
    val chooseFile: () -> Unit,
    val chooseTree: () -> Unit,
    val addMedia: (Boolean) -> Unit,
)

/** Registrations stay mounted while a stopped page keeps its single platform drain obligation. */
@Composable
internal fun rememberSourcesEntryLaunchers(
    model: SourcesViewModel,
    activity: Activity,
    synchronizeRoute: () -> Unit,
    isCurrent: () -> Boolean,
): SourcesEntryLaunchers {
    val synchronize by rememberUpdatedState(synchronizeRoute)
    val current by rememberUpdatedState(isCurrent)
    val file = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (current()) {
            synchronize()
            model.awaitingPicker(SourcesPickerKind.FILE)?.let { ticket ->
                model.pickerResult(ticket, result.resultCode, result.data?.data, result.data?.flags ?: 0)
            }
        }
    }
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (current()) {
            synchronize()
            model.awaitingPicker(SourcesPickerKind.TREE)?.let { ticket ->
                model.pickerResult(ticket, result.resultCode, result.data?.data, result.data?.flags ?: 0)
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (current()) {
            synchronize()
            model.awaitingMediaPicker()?.let { ticket ->
                val permanentlyDenied = if (granted) false else try {
                    !activity.shouldShowRequestPermissionRationale(model.audioReadPermission)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: RuntimeException) { false }
                catch (_: OutOfMemoryError) { false }
                model.mediaPermissionResult(ticket, granted, permanentlyDenied)
            }
        }
    }
    return remember(model, activity, file, tree, permission) {
        fun launch(ticket: SourcesPickerTicket, block: () -> Unit) {
            try { block() }
            catch (_: ActivityNotFoundException) { model.pickerLaunchFailed(ticket, definitelyNotLaunched = true) }
            catch (_: SecurityException) { model.pickerLaunchFailed(ticket, definitelyNotLaunched = true, denied = true) }
            catch (cancelled: CancellationException) {
                model.pickerLaunchFailed(ticket, definitelyNotLaunched = false)
                throw cancelled
            }
            catch (_: RuntimeException) { model.pickerLaunchFailed(ticket, definitelyNotLaunched = false) }
            catch (_: OutOfMemoryError) { model.pickerLaunchFailed(ticket, definitelyNotLaunched = false) }
        }
        fun choose(kind: SourcesPickerKind) {
            if (!current()) return
            synchronize()
            val ticket = model.beginPicker(kind) ?: return
            launch(ticket) {
                if (kind == SourcesPickerKind.FILE) file.launch(sourcesPickerIntent(kind))
                else tree.launch(sourcesPickerIntent(kind))
            }
        }
        SourcesEntryLaunchers(
            chooseFile = { choose(SourcesPickerKind.FILE) },
            chooseTree = { choose(SourcesPickerKind.TREE) },
            addMedia = media@{ primary ->
                if (!current()) return@media
                synchronize()
                if (!model.prepareMediaStore(primary)) return@media
                val ticket = model.awaitingMediaPicker() ?: return@media
                if (model.uiState.value.mediaPermission == SourcesMediaPermission.GRANTED) {
                    model.addMediaStore()
                } else launch(ticket) { permission.launch(model.audioReadPermission) }
            },
        )
    }
}
