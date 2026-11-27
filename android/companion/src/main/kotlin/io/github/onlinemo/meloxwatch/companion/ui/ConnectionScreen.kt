package io.github.onlinemo.meloxwatch.companion.ui

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import io.github.onlinemo.meloxwatch.companion.R
import io.github.onlinemo.meloxwatch.companion.data.CompanionConnectionPhase
import io.github.onlinemo.meloxwatch.companion.data.CompanionUploadPhase
import io.github.onlinemo.meloxwatch.data.transfer.TransferCapability
import io.github.onlinemo.meloxwatch.data.transfer.TransferUploadKind

internal fun LazyListScope.connectionItems(state: CompanionUiState, model: CompanionViewModel, selectAudio: () -> Unit) {
    item(key = "connection-inputs") {
        MeloXCard {
            MeloXText(stringResource(R.string.pairing_steps))
            MeloXField(stringResource(R.string.local_address), state.form.address, { value -> model.editConnection { it.edited(address = value) } }, keyboard = KeyboardType.Uri, maximum = 80)
            MeloXField(stringResource(R.string.local_port), state.form.port, { value -> model.editConnection { it.edited(port = value) } }, keyboard = KeyboardType.Number, maximum = 5)
            MeloXField(stringResource(R.string.watch_label), state.form.label, { value -> model.editConnection { it.edited(label = value) } }, maximum = 80)
            MeloXField(stringResource(R.string.watch_certificate), state.form.fingerprint, { value -> model.editConnection { it.edited(fingerprint = value) } }, keyboard = KeyboardType.Ascii, maximum = 128)
            MeloXField(stringResource(R.string.pairing_code), state.form.code, { value -> model.editConnection { it.edited(code = value) } }, keyboard = KeyboardType.NumberPassword, secret = true, maximum = 8)
            MeloXText(stringResource(R.string.permission_request), heading = true)
            TransferCapability.entries.forEach { capability ->
                MeloXToggle(capabilityName(capability), capability in state.form.requested, { selected -> model.editConnection {
                        it.edited(requested = if (selected) it.requested + capability else it.requested - capability)
                } }, enabled = state.available)
            }
            MeloXAction(stringResource(R.string.request_pairing), model::pair,
                enabled = state.available && state.form.requested.isNotEmpty() && state.form.code.length == 8, prominent = true)
        }
    }
    // OFF retains an in-memory pairing after a picker stop; a saved target alone cannot authenticate.
    val retainedPairing = state.connection.phase == CompanionConnectionPhase.OFF && state.connection.target != null &&
        state.connection.sessionId != null && state.connection.phoneFingerprint != null
    if (retainedPairing || state.connection.phase == CompanionConnectionPhase.WAITING_FOR_WATCH ||
        state.connection.phase == CompanionConnectionPhase.READY) item(key = "pairing-confirmation") {
        MeloXCard {
            MeloXText(stringResource(R.string.confirm_phone_on_watch))
            state.connection.phoneFingerprint?.let { MeloXText(it.forLocalDisplay().chunked(8).joinToString(" "), mono = true) }
            MeloXAction(stringResource(R.string.continue_after_watch), model::authenticate, enabled = state.available)
            if (state.connection.capabilities.isNotEmpty()) {
                MeloXText(stringResource(R.string.permission_granted), heading = true)
                state.connection.capabilities.forEach { MeloXText(capabilityName(it), secondary = true) }
            }
        }
    }
    item(key = "connection-stop") {
        var forget by remember { mutableStateOf(false) }
        MeloXCard {
            MeloXText(stringResource(R.string.connection_privacy), secondary = true)
            MeloXAction(stringResource(R.string.disconnect), { model.stop() }, enabled = !state.stopping)
            MeloXAction(stringResource(R.string.forget_pairing), { forget = true }, enabled = !state.stopping)
        }
        if (forget) MeloXConfirmation(stringResource(R.string.forget_pairing), stringResource(R.string.forget_message), stringResource(R.string.forget_pairing),
        onConfirm = {
                forget = false
                model.stop(forget = true)
            }, onDismiss = {
                forget = false
            })
    }
    uploadItems(state, model, selectAudio)
}

internal fun LazyListScope.uploadItems(state: CompanionUiState, model: CompanionViewModel, selectAudio: (() -> Unit)? = null) {
    item(key = "selected-upload") {
        var discard by remember { mutableStateOf(false) }
        val draft = state.local?.upload
        val target = state.connection.target?.fingerprint
        var send by remember(draft?.operationId, draft?.phase, target) { mutableStateOf(false) }
        MeloXCard {
            MeloXText(stringResource(R.string.selected_upload), heading = true)
            if (draft == null) {
                MeloXText(stringResource(R.string.no_selected_file), secondary = true)
                if (selectAudio != null) MeloXAction(stringResource(R.string.select_audio), selectAudio, enabled = state.available)
            } else {
                MeloXText(draft.declaration.displayName)
                MeloXText(companionPlural(R.plurals.selected_bytes, checkNotNull(draft.declaration.declaredBytes)), secondary = true)
                MeloXText(uploadPhaseName(draft.phase))
                if (draft.target != null) MeloXText(stringResource(R.string.upload_bound_target, draft.target.forLocalDisplay().chunked(8).joinToString(" ")), secondary = true, mono = true)
                state.uploadProgress?.let { progress -> MeloXText(companionPlural(R.plurals.upload_progress, progress.totalBytes, progress.sentBytes, progress.totalBytes), secondary = true) }
                draft.receipt?.let { ReceiptView(it) }
                if (draft.declaration.kind == TransferUploadKind.THEME) MeloXText(stringResource(R.string.theme_watch_confirmation), secondary = true)
                val sendable = draft.phase in setOf(CompanionUploadPhase.READY, CompanionUploadPhase.UNCERTAIN, CompanionUploadPhase.FAILED, CompanionUploadPhase.CANCELLED)
                MeloXAction(stringResource(if (draft.phase == CompanionUploadPhase.UNCERTAIN) R.string.retry_same_operation else R.string.send_selected), { send = true },
                    enabled = state.available && state.allowed(draft.declaration.kind.capability) && sendable && target != null && (draft.target == null || draft.target == target), prominent = true)
                MeloXAction(stringResource(R.string.remove_private_copy), { discard = true }, enabled = state.available)
            }
        }
        if (send && draft != null && target != null) MeloXConfirmation(stringResource(R.string.send_upload_title),
            companionPlural(R.plurals.send_upload_message, checkNotNull(draft.declaration.declaredBytes), draft.declaration.displayName, checkNotNull(draft.declaration.declaredBytes), target.forLocalDisplay().chunked(8).joinToString(" ")),
            stringResource(if (draft.phase == CompanionUploadPhase.UNCERTAIN) R.string.retry_same_operation else R.string.send_selected),
        onConfirm = {
                send = false
                model.sendUpload()
            }, onDismiss = {
                send = false
            })
        if (discard) MeloXConfirmation(stringResource(R.string.remove_private_copy), stringResource(R.string.remove_copy_message), stringResource(R.string.remove_private_copy),
        onConfirm = {
                discard = false
                model.discardUpload()
            }, onDismiss = {
                discard = false
            })
    }
}

@Composable
internal fun capabilityName(capability: TransferCapability): String = stringResource(when (capability) {
    TransferCapability.UPLOAD_AUDIO -> R.string.permission_audio
    TransferCapability.READ_LIBRARY -> R.string.permission_read
    TransferCapability.WRITE_PLAYLISTS -> R.string.permission_playlist
    TransferCapability.WRITE_DISPLAY_METADATA -> R.string.permission_display
    TransferCapability.UPLOAD_THEME -> R.string.permission_theme
})

@Composable
internal fun uploadPhaseName(phase: CompanionUploadPhase): String = stringResource(when (phase) {
    CompanionUploadPhase.PREPARING -> R.string.preparing_copy
    CompanionUploadPhase.READY -> R.string.copy_ready
    CompanionUploadPhase.SENDING, CompanionUploadPhase.VERIFYING -> R.string.working
    CompanionUploadPhase.COMPLETED -> R.string.result_committed
    CompanionUploadPhase.CANCELLED -> R.string.error_cancelled
    CompanionUploadPhase.UNCERTAIN -> R.string.result_uncertain
    CompanionUploadPhase.FAILED -> R.string.upload_failed
    CompanionUploadPhase.STOPPING, CompanionUploadPhase.RETIRING_COPY -> R.string.retiring
})
