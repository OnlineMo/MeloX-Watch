package io.github.onlinemo.meloxwatch.ui

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.AndroidViewModel
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.ui.themes.AppearancePicker
import io.github.onlinemo.meloxwatch.ui.themes.AppearancePickerTicket
import io.github.onlinemo.meloxwatch.ui.themes.AppearanceViewModel

/** Android does not echo a request nonce; one retained transport must drain before another launch. */
@MainThread
internal class ProbeAppearancePickerViewModel(application: Application) : AndroidViewModel(application) {
    private var awaiting: AppearancePickerTicket? = null

    fun launch(ticket: AppearancePickerTicket, model: AppearanceViewModel, launch: (Intent) -> Unit) {
        if (model.state.value.picker != ticket) return
        if (awaiting != null) {
            model.pickerCancelled(ticket)
            return
        }
        awaiting = ticket
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(if (ticket.picker == AppearancePicker.IMAGE) "image/*" else "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            launch(intent)
        } catch (_: ActivityNotFoundException) {
            if (awaiting === ticket) awaiting = null
            model.pickerUnavailable(ticket)
        } catch (_: SecurityException) {
            if (awaiting === ticket) awaiting = null
            model.pickerUnavailable(ticket)
        }
    }

    fun returned(model: AppearanceViewModel, resultCode: Int, data: Intent?) {
        val ticket = awaiting ?: return
        awaiting = null
        val uri = data?.data.takeIf { resultCode == Activity.RESULT_OK &&
            data != null && data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 }
        if (uri == null) {
            model.pickerCancelled(ticket)
            return
        }
        // A generic local name avoids a separate provider read during the hidden return interval.
        when (ticket.picker) {
            AppearancePicker.THEME -> model.importPickedTheme(ticket, uri.toString())
            AppearancePicker.IMAGE -> model.importPickedImage(ticket, uri.toString(),
                getApplication<Application>().getString(R.string.appearance_background))
        }
    }
}

/** The registration stays mounted across route and Window changes; only its captured ticket can return. */
@Composable
internal fun rememberProbeAppearancePickerLauncher(
    picker: ProbeAppearancePickerViewModel,
    model: AppearanceViewModel,
): (AppearancePickerTicket) -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        picker.returned(model, it.resultCode, it.data)
    }
    return remember(picker, model, launcher) {
        { ticket -> picker.launch(ticket, model) { intent -> launcher.launch(intent) } }
    }
}
