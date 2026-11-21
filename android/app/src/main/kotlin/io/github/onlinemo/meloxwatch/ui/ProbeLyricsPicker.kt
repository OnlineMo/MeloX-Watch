package io.github.onlinemo.meloxwatch.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import io.github.onlinemo.meloxwatch.ui.lyrics.entry.LyricsDocumentRequest
import io.github.onlinemo.meloxwatch.ui.lyrics.entry.LyricsEntryViewModel

/** One retained platform launch drains even after a route departure; Android returns no nonce. */
internal class ProbeLyricsPickerViewModel : ViewModel() {
    private var awaiting: String? = null

    fun launch(request: LyricsDocumentRequest, entry: LyricsEntryViewModel, launch: (Intent) -> Unit) {
        if (entry.uiState.value.documentRequest != request) return
        if (awaiting != null) {
            entry.pickerReturned(request.requestId, null, 0)
            return
        }
        if (!entry.pickerLaunched(request.requestId)) return
        awaiting = request.requestId
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            launch(intent)
        } catch (_: ActivityNotFoundException) {
            if (awaiting == request.requestId) awaiting = null
            entry.noPicker(request.requestId)
        } catch (_: SecurityException) {
            if (awaiting == request.requestId) awaiting = null
            entry.noPicker(request.requestId)
        }
    }

    fun returned(entry: LyricsEntryViewModel, resultCode: Int, data: Intent?) {
        val id = awaiting ?: return
        awaiting = null
        val uri = data?.data.takeIf { resultCode == Activity.RESULT_OK }
        entry.pickerReturned(id, uri?.toString(), data?.flags ?: 0)
    }
}

/** Always mounted at the Activity composition root, including hidden and non-lyrics routes. */
@Composable
internal fun rememberProbeLyricsPickerLauncher(
    picker: ProbeLyricsPickerViewModel,
    entry: LyricsEntryViewModel,
): (LyricsDocumentRequest) -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        picker.returned(entry, it.resultCode, it.data)
    }
    return remember(picker, entry, launcher) {
        { request -> picker.launch(request, entry) { intent -> launcher.launch(intent) } }
    }
}
