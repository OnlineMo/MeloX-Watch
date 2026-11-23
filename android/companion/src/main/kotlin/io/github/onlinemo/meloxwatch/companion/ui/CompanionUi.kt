package io.github.onlinemo.meloxwatch.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.onlinemo.meloxwatch.companion.R
import io.github.onlinemo.meloxwatch.companion.data.CompanionConnectionPhase
import io.github.onlinemo.meloxwatch.companion.data.CompanionEditPhase
import io.github.onlinemo.meloxwatch.companion.data.CompanionIconRole
import io.github.onlinemo.meloxwatch.companion.ui.about.CompanionLicensesState
import io.github.onlinemo.meloxwatch.companion.ui.about.CompanionLicensesViewModel
import io.github.onlinemo.meloxwatch.companion.ui.about.companionLicenseItems
import io.github.onlinemo.meloxwatch.data.transfer.TransferIssue
import io.github.onlinemo.meloxwatch.data.transfer.TransferReceipt
import io.github.onlinemo.meloxwatch.data.transfer.TransferReceiptKind

@Composable
internal fun CompanionUi(
    state: CompanionUiState, model: CompanionViewModel,
    licenseState: CompanionLicensesState, licenses: CompanionLicensesViewModel,
    onExit: () -> Unit, selectAudio: () -> Unit,
    selectIcon: (CompanionIconRole, String, String) -> Unit,
    onBluetoothLyrics: () -> Unit = {},
) {
    DisposableEffect(state.screen, licenses) {
        if (state.screen == CompanionScreen.LICENSES) licenses.show() else licenses.hide()
        onDispose { licenses.hide() }
    }
    val back: () -> Unit = {
        if (state.screen != CompanionScreen.LICENSES || !licenses.backToList()) {
            if (state.screen == CompanionScreen.LICENSES) licenses.hide()
            if (state.screen == CompanionScreen.CONNECTION) onExit() else model.back()
        }
    }
    BackHandler(enabled = state.screen != CompanionScreen.CONNECTION, onBack = back)
    val scroll = rememberLazyListState()
    LaunchedEffect(state.screen, licenseState.selected?.id) {
        if (state.screen == CompanionScreen.LICENSES) scroll.scrollToItem(0)
    }
    Column(Modifier.fillMaxSize().background(CompanionInk.background).windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally) {
        MeloXAction(stringResource(R.string.back), back,
            modifier = Modifier.widthIn(max = 720.dp).padding(horizontal = 16.dp, vertical = 8.dp))
        LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxWidth().weight(1f), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp), state = scroll) {
            item(key = "screen-heading") { MeloXText(screenName(state.screen), heading = true) }
            item(key = "screen-navigation") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(CompanionScreen.entries, key = { it.name }) { screen ->
                        MeloXAction(screenName(screen), {
                            if (state.screen == CompanionScreen.LICENSES && screen != CompanionScreen.LICENSES) licenses.hide()
                            model.navigate(screen)
                        }, prominent = screen == state.screen,
                            modifier = Modifier.width(144.dp).semantics { selected = screen == state.screen })
                    }
                }
            }
            if (state.screen != CompanionScreen.LICENSES) {
                item(key = "session-status") {
                    MeloXCard {
                        val target = state.connection.target ?: state.local?.target
                        MeloXText(connectionPhaseName(state.connection.phase))
                        if (target != null) MeloXText(stringResource(R.string.target_label, target.label), secondary = true)
                        if (state.busy || state.stopping || state.recovering) {
                            MeloXText(stringResource(if (state.stopping || state.recovering) R.string.retiring else R.string.working), secondary = true)
                            if (state.busy && !state.stopping) MeloXAction(stringResource(R.string.cancel_work), { model.stop() })
                        }
                        if (state.local == null) {
                            MeloXText(stringResource(R.string.loading_local), secondary = true)
                            MeloXAction(stringResource(R.string.reload_local), model::reloadLocal, enabled = !state.busy && !state.stopping && !state.recovering && state.documentIssue != TransferIssue.RECOVERY_REQUIRED)
                        }
                    }
                }
                val issue = state.issue ?: state.documentIssue ?: state.connection.issue
                if (issue != null || state.pickerUnavailable) item(key = "fixed-error") {
                    MeloXCard {
                        MeloXText(if (state.pickerUnavailable) stringResource(R.string.error_picker) else transferIssueText(checkNotNull(issue)))
                        if (issue == TransferIssue.RECOVERY_REQUIRED || state.documentIssue == TransferIssue.RECOVERY_REQUIRED || state.connection.phase == CompanionConnectionPhase.RECOVERY_REQUIRED) {
                            MeloXAction(stringResource(R.string.retry_retirement), model::retryRecovery, enabled = !state.stopping && !state.recovering)
                        }
                        if (issue != TransferIssue.RECOVERY_REQUIRED) MeloXAction(stringResource(R.string.dismiss), model::dismissIssue)
                    }
                }
            }
            when (state.screen) {
                CompanionScreen.CONNECTION -> {
                    item(key = "bluetooth-lyrics-receiver") {
                        MeloXAction(stringResource(R.string.bluetooth_lyrics_receiver_title), onBluetoothLyrics)
                    }
                    connectionItems(state, model, selectAudio)
                }
                CompanionScreen.LIBRARY -> libraryItems(state, model)
                CompanionScreen.PLAYLIST -> playlistItems(state, model)
                CompanionScreen.DISPLAY -> displayItems(state, model)
                CompanionScreen.THEME -> themeItems(state, model, selectIcon)
                CompanionScreen.LICENSES -> companionLicenseItems(licenseState, licenses)
            }
        }
    }
}

@Composable
internal fun screenName(screen: CompanionScreen): String = stringResource(when (screen) {
    CompanionScreen.CONNECTION -> R.string.connection
    CompanionScreen.LIBRARY -> R.string.library
    CompanionScreen.PLAYLIST -> R.string.playlists
    CompanionScreen.DISPLAY -> R.string.display_info
    CompanionScreen.THEME -> R.string.theme
    CompanionScreen.LICENSES -> R.string.licenses
})

@Composable
internal fun connectionPhaseName(phase: CompanionConnectionPhase): String = stringResource(when (phase) {
    CompanionConnectionPhase.OFF -> R.string.connection_off
    CompanionConnectionPhase.CONNECTING, CompanionConnectionPhase.PAIRING -> R.string.connection_pairing
    CompanionConnectionPhase.WAITING_FOR_WATCH -> R.string.connection_waiting
    CompanionConnectionPhase.AUTHENTICATING -> R.string.connection_authenticating
    CompanionConnectionPhase.READY -> R.string.connection_ready
    CompanionConnectionPhase.STOPPING -> R.string.retiring
    CompanionConnectionPhase.RECOVERY_REQUIRED -> R.string.error_recovery
})

@Composable
internal fun editPhaseName(phase: CompanionEditPhase): String = stringResource(when (phase) {
    CompanionEditPhase.DRAFT -> R.string.draft_saved
    CompanionEditPhase.PREVIEWED -> R.string.preview_ready
    CompanionEditPhase.SUBMITTING -> R.string.working
    CompanionEditPhase.UNCERTAIN -> R.string.result_uncertain
    CompanionEditPhase.COMPLETED -> R.string.result_committed
    CompanionEditPhase.CONFLICT -> R.string.conflict_keep_draft
})

@Composable
internal fun ReceiptView(receipt: TransferReceipt) {
    MeloXText(stringResource(when (receipt.result) {
        TransferReceiptKind.AUDIO_COMMITTED -> R.string.receipt_audio
        TransferReceiptKind.THEME_PENDING_CONFIRMATION -> R.string.receipt_theme
        TransferReceiptKind.PLAYLIST_COMMITTED -> R.string.receipt_playlist
        TransferReceiptKind.DISPLAY_COMMITTED -> R.string.receipt_display
    }))
    MeloXText(stringResource(R.string.device_revision, receipt.revision), secondary = true)
}
