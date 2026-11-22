package io.github.onlinemo.meloxwatch.ui.about

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable

/** 只请求一次临时tree授权；返回URI留在本机内存，不持久化或附到错误记录。 */
@Composable
internal fun rememberHelpExportPickerLauncher(model: AboutViewModel): (String) -> Unit {
    var launchToken by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val uri = data?.data?.takeIf { result.resultCode == Activity.RESULT_OK && (data.clipData?.itemCount ?: 0) <= 1 }
        val token = launchToken
        launchToken = null
        model.destinationReturned(token, uri, data?.flags ?: 0)
    }
    LaunchedEffect(model, launchToken) { model.retainUnboundLaunchSlot(launchToken) }
    return remember(model, launcher) {
        { token: String ->
            if (launchToken == null && model.pickerLaunched(token)) {
                launchToken = token
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                try { launcher.launch(intent) }
                catch (_: ActivityNotFoundException) {
                    launchToken = null
                    model.pickerUnavailable(token)
                }
                catch (_: SecurityException) {
                    launchToken = null
                    model.pickerUnavailable(token)
                }
            }
        }
    }
}
