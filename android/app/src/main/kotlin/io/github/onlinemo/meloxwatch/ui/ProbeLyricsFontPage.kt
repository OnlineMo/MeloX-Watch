package io.github.onlinemo.meloxwatch.ui

import android.app.Application
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.onlinemo.meloxwatch.MeloXApplication
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontRole
import io.github.onlinemo.meloxwatch.ui.fonts.ProbeFontPickerReturnHost
import io.github.onlinemo.meloxwatch.ui.fonts.ProbeFontPickerTicket
import io.github.onlinemo.meloxwatch.ui.fonts.ProbeFontPickerViewModel
import io.github.onlinemo.meloxwatch.ui.fonts.WatchSystemFontControls
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsFonts
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsDisplayOptions
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsTextBlock
import io.github.onlinemo.meloxwatch.ui.lyrics.options.LyricsDisplayEditor
import io.github.onlinemo.meloxwatch.ui.watch.WatchLyricsFontSettings
import io.github.onlinemo.meloxwatch.ui.watch.WatchText
import kotlinx.coroutines.launch

/** Configuration recreation retains only the display proposal; its event coroutine belongs to UI. */
internal class ProbeLyricsDisplayViewModel internal constructor(
    application: Application,
    val editor: LyricsDisplayEditor,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application,
        LyricsDisplayEditor((application as MeloXApplication).lyricsDisplay))

    val events = ProbeLyricsDisplayEvents(editor)

    suspend fun save(page: ProbePageRuntime.PageBinding) = events.save(page)

    fun retireInvisibleEvent() = events.retireInvisibleEvent()

    override fun onCleared() {
        events.close()
        super.onCleared()
    }
}

@Composable
internal fun ProbeLyricsFontPage(
    app: MeloXApplication,
    page: ProbePageRuntime.PageBinding,
    picker: ProbeFontPickerViewModel,
    launchPicker: (ProbeFontPickerTicket, Intent) -> Unit,
    onBack: () -> Unit,
) {
    var retry by remember { mutableIntStateOf(0) }
    ProbeLyricsFontEditorHost(app, page, retry) { host ->
        val pickerState by picker.state.collectAsStateWithLifecycle()
        val route = picker.currentFontsRouteToken(ProbePage.LYRICS_FONTS)
        val visibleNow = remember(page) { { page.visibleNow } }
        val events = rememberCoroutineScope()
        val metadata = host.editorState
        val sample = host.previewText
        if (route != null) {
            ProbeFontPickerReturnHost(picker, route, page.epoch, host, page.visibleNow, visibleNow)
        }
        val controls = metadata?.copy(
            working = metadata.working || pickerState.awaitingPlatform || pickerState.pendingReturn != null,
            issue = pickerState.issue ?: metadata.issue,
        )
        val cancel = {
            if (page.visibleNow) {
                if (controls == null || sample == null) onBack()
                else if (!controls.working) {
                    if (metadata?.draft == null && metadata?.retry == null) onBack()
                    else events.launch { host.edit { it.cancel(sample) } }
                }
            }
            Unit
        }
        BackHandler(enabled = page.visibleNow, onBack = cancel)
        WatchSystemFontControls {
            if (controls == null || sample == null) {
                WatchText(stringResource(R.string.lyrics_settings_working))
            } else {
                WatchLyricsFontSettings(
                    state = controls,
                    licenseAcknowledged = picker.licenseAcknowledgedFor(metadata?.draft),
                    visible = page.visibleNow,
                    canAdjustFontWeight = host.canAdjustFontWeight,
                    onBegin = { role -> events.launch { host.edit { it.begin(role, sample) } } },
                    onLicenseAcknowledgment = { checked ->
                        if (route != null) events.launch { picker.acknowledgeLicense(route, host, checked) }
                    },
                    onChooseFont = {
                        if (route != null) events.launch { picker.launchFontPicker(route, host, launchPicker) }
                    },
                    onWeight = { weight -> events.launch { host.edit { it.previewWeight(weight, sample) } } },
                    onSystemFont = { events.launch { host.edit { it.previewSystemFont() } } },
                    onConfirm = { events.launch { host.edit { it.confirm(sample) } } },
                    onRetrySave = { events.launch { host.edit { it.retrySave(sample) } } },
                    onReadAgain = { if (page.visibleNow) retry++ },
                    onCancel = cancel,
                    preview = { role ->
                        val fonts = host.fonts
                        val explicit = if (role == LyricsFontRole.ORIGINAL) fonts else LyricsFonts(
                            originalFamily = fonts.translationFamily,
                            originalWeight = fonts.translationWeight,
                        )
                        LyricsTextBlock(sample.forRole(role), translation = null, active = true, wordRange = null,
                            options = LyricsDisplayOptions(), fonts = explicit,
                            currentLabel = stringResource(R.string.lyrics_current_line),
                            actionLabel = null, onPreview = null, visible = page.visibleNow)
                    },
                )
            }
        }
    }
}
