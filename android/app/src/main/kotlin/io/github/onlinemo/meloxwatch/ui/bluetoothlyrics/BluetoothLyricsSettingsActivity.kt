package io.github.onlinemo.meloxwatch.ui.bluetoothlyrics

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.onlinemo.meloxwatch.MeloXApplication
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.data.bluetoothlyrics.BluetoothLyricsIssue
import io.github.onlinemo.meloxwatch.data.bluetoothlyrics.BluetoothLyricsPhase
import io.github.onlinemo.meloxwatch.data.bluetoothlyrics.BluetoothLyricsPlatform
import io.github.onlinemo.meloxwatch.data.bluetoothlyrics.BluetoothLyricsSelectionState
import io.github.onlinemo.meloxwatch.data.bluetoothlyrics.BluetoothLyricsSessionState
import io.github.onlinemo.meloxwatch.data.bluetoothlyrics.WatchBluetoothLyricsController
import io.github.onlinemo.meloxwatch.data.preferences.PreferencesState
import io.github.onlinemo.meloxwatch.model.ScreenShape
import io.github.onlinemo.meloxwatch.ui.themes.LocalWatchThemeColors
import io.github.onlinemo.meloxwatch.ui.themes.WatchThemeScope
import io.github.onlinemo.meloxwatch.ui.watch.WatchActionRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchSwitchRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchText
import io.github.onlinemo.meloxwatch.ui.watch.WatchWindowRuntime
import io.github.onlinemo.meloxwatch.ui.watch.protectsPhysicalRoundBounds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** The actual Window gates comparison; authenticated service output outlives this presentation. */
class BluetoothLyricsSettingsActivity : ComponentActivity() {
    private lateinit var controller: WatchBluetoothLyricsController
    private lateinit var model: BluetoothLyricsSettingsModel
    private var windowRuntime: WatchWindowRuntime? = null
    private var presentation: WatchBluetoothLyricsController.Presentation? = null
    private val permission = MutableStateFlow(false)
    private val platformIssue = MutableStateFlow<BluetoothLyricsIssue?>(null)
    private var retryingRetirement = false
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshPermission()
        if (!it) platformIssue.value = BluetoothLyricsIssue.PERMISSION_DENIED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as MeloXApplication
        controller = app.bluetoothLyrics
        presentation = controller.bindPresentation()
        model = ViewModelProvider(this, viewModelFactory {
            initializer { BluetoothLyricsSettingsModel(app.preferences, controller::stop) }
        })[BluetoothLyricsSettingsModel::class.java]
        enableEdgeToEdge()
        refreshPermission()
        windowRuntime = WatchWindowRuntime(this) {
            if (it.visible) { refreshPermission(); presentation?.show() } else presentation?.hide()
        }
        setContent {
            val editor by model.state.collectAsStateWithLifecycle()
            val session by controller.sessions.state.collectAsStateWithLifecycle()
            val selection by controller.selection.collectAsStateWithLifecycle()
            val granted by permission.collectAsStateWithLifecycle()
            val issue by platformIssue.collectAsStateWithLifecycle()
            val appearance by app.appearancePreferences.state.collectAsStateWithLifecycle()
            val theme by app.themes.state.collectAsStateWithLifecycle()
            WatchThemeScope(theme.effectiveManifest.palette, appearance.effective) {
                SettingsContent(editor, session, selection, granted, issue)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        windowRuntime?.windowFocusChanged(hasFocus)
    }

    override fun onDestroy() {
        try { windowRuntime?.close(); presentation?.close() } finally { super.onDestroy() }
    }

    private fun onCurrentUi(action: () -> Unit) {
        if (presentation?.isCurrent == true && windowRuntime?.visibility?.value?.visible == true) action()
    }

    private fun refreshPermission() {
        if (presentation?.isCurrent != true) return
        permission.value = try { BluetoothLyricsPlatform.hasPermission(this) } catch (_: RuntimeException) { false }
        if (!permission.value && ::controller.isInitialized) controller.stop()
        if (permission.value) platformIssue.value = null
    }

    private fun requestPermission() {
        if (presentation?.isCurrent != true || windowRuntime?.visibility?.value?.visible != true) return
        if (BluetoothLyricsPlatform.needsPermission()) {
            try { permissionRequest.launch(Manifest.permission.BLUETOOTH_CONNECT) }
            catch (_: RuntimeException) { platformIssue.value = BluetoothLyricsIssue.PERMISSION_DENIED }
        } else refreshPermission()
    }

    private fun openSettings(applicationPermissions: Boolean) {
        if (presentation?.isCurrent != true || windowRuntime?.visibility?.value?.visible != true) return
        val request = if (applicationPermissions) Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            ("package:" + packageName).toUri()) else Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        try { startActivity(request) }
        catch (_: ActivityNotFoundException) { platformIssue.value = BluetoothLyricsIssue.UNAVAILABLE }
        catch (_: SecurityException) { platformIssue.value = BluetoothLyricsIssue.PERMISSION_DENIED }
    }

    private fun retryRetirement() {
        if (presentation?.isCurrent != true || windowRuntime?.visibility?.value?.visible != true || retryingRetirement) return
        retryingRetirement = true
        lifecycleScope.launch {
            try { controller.sessions.retryRetirement() }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { platformIssue.value = BluetoothLyricsIssue.RETIREMENT_REQUIRED }
            finally { retryingRetirement = false }
        }
    }

    @Composable
    private fun SettingsContent(
        editor: BluetoothLyricsSettingsState,
        session: BluetoothLyricsSessionState,
        selection: BluetoothLyricsSelectionState,
        granted: Boolean,
        issue: BluetoothLyricsIssue?,
    ) {
        val ready = editor.storage as? PreferencesState.Ready
        val shape = ready?.preferences?.screenShape ?: ScreenShape.AUTO
        val round = protectsPhysicalRoundBounds(shape, LocalConfiguration.current.isScreenRound)
        val idle = session.phase == BluetoothLyricsPhase.OFF || session.phase == BluetoothLyricsPhase.FAILED
        val canSelect = editor.canConnect && granted && idle && !session.retirementRequired && !selection.working
        BoxWithConstraints(Modifier.fillMaxSize().background(LocalWatchThemeColors.current.background)) {
            // An inscribed square protects every row, including large-font wrapped controls.
            val side = minOf(maxWidth, maxHeight) * 0.70710678f
            val viewport = if (round) Modifier.size(side) else Modifier.fillMaxSize()
            Box(viewport.align(Alignment.Center).safeDrawingPadding()) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { WatchActionRow(stringResource(R.string.bluetooth_lyrics_back), onClick = ::finish) }
                    item { WatchText(stringResource(R.string.bluetooth_lyrics_title), primary = true, textAlign = TextAlign.Center) }
                    item { WatchText(stringResource(R.string.bluetooth_lyrics_disclosure)) }
                    item { WatchText(stringResource(R.string.bluetooth_lyrics_compatibility)) }
                    item { WatchSwitchRow(stringResource(R.string.bluetooth_lyrics_enable), editor.draft,
                        enabled = editor.canEdit, onChange = { value -> onCurrentUi { model.change(value) } }) }
                    if (ready != null) item { WatchText(stringResource(if (ready.preferences.bluetoothLyricsEnabled)
                        R.string.bluetooth_lyrics_saved_on else R.string.bluetooth_lyrics_saved_off)) }
                    item { WatchText(stringResource(R.string.bluetooth_lyrics_draft_note)) }
                    item { WatchActionRow(stringResource(R.string.bluetooth_lyrics_save), editor.canEdit, primary = true, onClick = { onCurrentUi(model::save) }) }
                    item { WatchActionRow(stringResource(R.string.bluetooth_lyrics_cancel), !editor.saving && ready != null, onClick = { onCurrentUi(model::reloadSaved) }) }
                    item { WatchActionRow(stringResource(R.string.bluetooth_lyrics_default), editor.canEdit, onClick = { onCurrentUi(model::defaultDraft) }) }
                    if (editor.saving) item { WatchText(stringResource(R.string.bluetooth_lyrics_saving)) }
                    editor.feedback?.let { feedback -> item {
                        WatchText(stringResource(if (feedback == BluetoothLyricsSaveFeedback.SAVED)
                            R.string.bluetooth_lyrics_saved else R.string.bluetooth_lyrics_unchanged))
                    } }
                    if (editor.failure != null) item { WatchText(stringResource(R.string.bluetooth_lyrics_save_failed), error = true) }
                    if (editor.hasConflict) item {
                        WatchText(stringResource(R.string.bluetooth_lyrics_conflict), error = true)
                        WatchActionRow(stringResource(R.string.bluetooth_lyrics_reload), !editor.saving, onClick = { onCurrentUi(model::reloadSaved) })
                    }
                    if (editor.storage is PreferencesState.Unavailable || ready?.canWrite == false) item {
                        WatchText(stringResource(R.string.bluetooth_lyrics_storage_failed), error = true)
                        WatchActionRow(stringResource(R.string.bluetooth_lyrics_read_retry), !editor.saving, onClick = { onCurrentUi(model::retryRead) })
                    }
                    if (editor.storage == PreferencesState.Loading) item { WatchText(stringResource(R.string.bluetooth_lyrics_loading)) }
                    item { WatchText(stringResource(phaseText(session.phase)), primary = true) }
                    if (session.peerLabel.isNotEmpty()) item { WatchText(stringResource(R.string.bluetooth_lyrics_peer, session.peerLabel)) }
                    val comparison = session.comparison
                    val digits = comparison?.forLocalUi()
                    if (session.phase == BluetoothLyricsPhase.COMPARING && comparison != null && digits != null) {
                        item { WatchText(stringResource(R.string.bluetooth_lyrics_compare)) }
                        item { WatchText(digits, Modifier.semantics {
                            contentDescription = digits.toList().joinToString(" ")
                        }, primary = true, textAlign = TextAlign.Center) }
                        item { WatchActionRow(stringResource(R.string.bluetooth_lyrics_approve), primary = true,
                            onClick = { onCurrentUi { presentation?.approve(comparison) } }) }
                        item { WatchActionRow(stringResource(R.string.bluetooth_lyrics_reject), onClick = { onCurrentUi { presentation?.reject() } }) }
                    }
                    if (!idle) item { WatchActionRow(stringResource(R.string.bluetooth_lyrics_stop), onClick = { onCurrentUi(controller::stop) }) }
                    val currentIssue = session.issue ?: selection.issue ?: issue
                    if (currentIssue != null) item { WatchText(stringResource(issueText(currentIssue)), error = true) }
                    if (session.retirementRequired) item {
                        WatchActionRow(stringResource(R.string.bluetooth_lyrics_retry_retirement), onClick = ::retryRetirement)
                    }
                    if (!granted) {
                        item { WatchText(stringResource(R.string.bluetooth_lyrics_permission_note)) }
                        item { WatchActionRow(stringResource(R.string.bluetooth_lyrics_permission), onClick = ::requestPermission) }
                        item { WatchActionRow(stringResource(R.string.bluetooth_lyrics_app_settings), onClick = { openSettings(true) }) }
                    }
                    item { WatchActionRow(stringResource(R.string.bluetooth_lyrics_system_settings), onClick = { openSettings(false) }) }
                    item { WatchText(stringResource(R.string.bluetooth_lyrics_paired), primary = true) }
                    item { WatchActionRow(stringResource(R.string.bluetooth_lyrics_refresh), canSelect, onClick = { onCurrentUi { controller.listPaired() } }) }
                    if (selection.working) item { WatchText(stringResource(R.string.bluetooth_lyrics_working)) }
                    val page = selection.page
                    if (page != null) {
                        if (page.entries.isEmpty()) item { WatchText(stringResource(R.string.bluetooth_lyrics_no_devices)) }
                        items(page.entries) { target ->
                            val label = if (target.label.isBlank()) stringResource(R.string.bluetooth_lyrics_unnamed) else target.label
                            WatchActionRow(label,
                                canSelect, onClick = { onCurrentUi { controller.connect(target) } })
                        }
                        page.nextOffset?.let { next ->
                            item { WatchActionRow(stringResource(R.string.bluetooth_lyrics_next), canSelect, onClick = { onCurrentUi { controller.listPaired(next) } }) }
                        }
                        item { WatchActionRow(stringResource(R.string.bluetooth_lyrics_previous), canSelect, onClick = { onCurrentUi { controller.listPaired() } }) }
                    }
                }
            }
        }
    }
}

private fun phaseText(phase: BluetoothLyricsPhase): Int = when (phase) {
    BluetoothLyricsPhase.OFF -> R.string.bluetooth_lyrics_phase_off
    BluetoothLyricsPhase.CONNECTING -> R.string.bluetooth_lyrics_phase_connecting
    BluetoothLyricsPhase.COMPARING -> R.string.bluetooth_lyrics_phase_comparing
    BluetoothLyricsPhase.CONNECTED -> R.string.bluetooth_lyrics_phase_connected
    BluetoothLyricsPhase.CLOSING -> R.string.bluetooth_lyrics_phase_closing
    BluetoothLyricsPhase.FAILED -> R.string.bluetooth_lyrics_phase_failed
}

private fun issueText(issue: BluetoothLyricsIssue): Int = when (issue) {
    BluetoothLyricsIssue.CLOSED -> R.string.bluetooth_lyrics_error_closed
    BluetoothLyricsIssue.BUSY -> R.string.bluetooth_lyrics_error_busy
    BluetoothLyricsIssue.UNAVAILABLE -> R.string.bluetooth_lyrics_error_unavailable
    BluetoothLyricsIssue.BLUETOOTH_OFF -> R.string.bluetooth_lyrics_error_off
    BluetoothLyricsIssue.NOT_BONDED -> R.string.bluetooth_lyrics_error_bond
    BluetoothLyricsIssue.PERMISSION_DENIED -> R.string.bluetooth_lyrics_error_permission
    BluetoothLyricsIssue.WRONG_PEER -> R.string.bluetooth_lyrics_error_peer
    BluetoothLyricsIssue.NOT_CONFIRMED -> R.string.bluetooth_lyrics_error_confirm
    BluetoothLyricsIssue.EXPIRED -> R.string.bluetooth_lyrics_error_expired
    BluetoothLyricsIssue.CONNECTION_LOST, BluetoothLyricsIssue.TRANSPORT_FAILED -> R.string.bluetooth_lyrics_error_connection
    BluetoothLyricsIssue.INVALID_FRAME -> R.string.bluetooth_lyrics_error_frame
    BluetoothLyricsIssue.INCOMPATIBLE_SCHEMA -> R.string.bluetooth_lyrics_error_schema
    BluetoothLyricsIssue.AUTHENTICATION_FAILED -> R.string.bluetooth_lyrics_error_auth
    BluetoothLyricsIssue.CRYPTO_UNAVAILABLE -> R.string.bluetooth_lyrics_error_crypto
    BluetoothLyricsIssue.RATE_LIMITED -> R.string.bluetooth_lyrics_error_rate
    BluetoothLyricsIssue.RETIREMENT_REQUIRED -> R.string.bluetooth_lyrics_error_retirement
    BluetoothLyricsIssue.STORAGE_UNAVAILABLE -> R.string.bluetooth_lyrics_error_storage
    BluetoothLyricsIssue.SOURCE_UNAVAILABLE -> R.string.bluetooth_lyrics_error_source
}
