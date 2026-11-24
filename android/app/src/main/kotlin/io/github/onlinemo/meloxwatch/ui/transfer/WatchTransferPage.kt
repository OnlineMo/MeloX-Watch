package io.github.onlinemo.meloxwatch.ui.transfer

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.data.transfer.ReceiverControlPhase
import io.github.onlinemo.meloxwatch.data.transfer.TransferCapability
import io.github.onlinemo.meloxwatch.data.transfer.TransferFingerprint
import io.github.onlinemo.meloxwatch.data.transfer.TransferIssue
import io.github.onlinemo.meloxwatch.data.transfer.TransferLimits
import io.github.onlinemo.meloxwatch.data.transfer.TransferServerPhase
import io.github.onlinemo.meloxwatch.data.transfer.TransferUploadPhase
import io.github.onlinemo.meloxwatch.ui.themes.LocalWatchThemeColors
import io.github.onlinemo.meloxwatch.ui.fonts.WatchSystemFontControls
import io.github.onlinemo.meloxwatch.ui.watch.WatchActionRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchCheckRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchChoiceRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchText

/** 独立行沿用圆屏安全列表；长指纹可换行，操作不藏在整页大矩形里。 */
internal fun LazyListScope.watchTransferContent(
    state: WatchTransferState,
    model: WatchTransferViewModel,
    visible: Boolean,
    onAppearance: () -> Unit,
) {
    val enabled = visible && state.visible
    val closed = state.serverPhase == TransferServerPhase.OFF && state.control.phase == ReceiverControlPhase.READY
    item(key = "transfer:introduction") { WatchText(stringResource(R.string.transfer_introduction)) }
    item(key = "transfer:state") {
        WatchText(stringResource(receiverStatus(state)), primary = true)
    }
    val issue = (state.issue ?: state.control.issue ?: state.session.issue ?: state.upload?.issue)
        ?.takeIf { it != TransferIssue.CLOSED }
    if (issue != null) item(key = "transfer:issue") {
        WatchText(stringResource(transferIssueText(issue)), error = true)
    }
    state.notice?.let { notice -> item(key = "transfer:notice") {
        WatchText(stringResource(when (notice) {
            TransferUiNotice.NO_ADDRESS -> R.string.transfer_no_address
            TransferUiNotice.ADDRESS_FAILED -> R.string.transfer_address_failed
            TransferUiNotice.WAITING_FOR_OWNER -> R.string.transfer_waiting_owner
        }))
    } }
    if (closed) {
        item(key = "transfer:addresses") {
            WatchActionRow(stringResource(if (state.readingAddresses) R.string.transfer_reading_addresses
                else R.string.transfer_find_addresses), enabled = enabled && !state.readingAddresses,
                onClick = model::refreshAddresses)
        }
        state.addresses.forEachIndexed { index, address -> item(key = "transfer:address:$index") {
            WatchSystemFontControls {
                WatchChoiceRow(address.forLocalDisplay(), selected = state.selectedAddress === address,
                    enabled = enabled && !state.controlPending, onSelect = { model.selectAddress(address) })
            }
        } }
        item(key = "transfer:open") {
            WatchActionRow(stringResource(R.string.transfer_open),
                enabled = enabled && state.selectedAddress != null && !state.controlPending,
                primary = true, onClick = model::open)
        }
    }
    val handle = state.control.handle
    val address = state.control.address
    if (handle != null && address != null && state.session.active) {
        item(key = "transfer:endpoint") {
            WatchSystemFontControls {
                WatchText(stringResource(R.string.transfer_local_endpoint, address.forLocalDisplay(), handle.port))
            }
        }
        item(key = "transfer:target-label") { WatchText(stringResource(R.string.transfer_target_fingerprint), primary = true) }
        item(key = "transfer:target") { FingerprintText(handle.opening.target) }
        item(key = "transfer:target-explanation") { WatchText(stringResource(R.string.transfer_target_check)) }
        state.pairingCode?.let { code -> item(key = "transfer:code") {
            WatchSystemFontControls { WatchText(stringResource(R.string.transfer_pairing_code, code), primary = true) }
        } }
        if (state.pairingCode == null && state.session.pending == null && state.session.approvedPeer == null) {
            item(key = "transfer:code-expired") { WatchText(stringResource(R.string.transfer_pairing_expired)) }
        }
        item(key = "transfer:remaining") {
            val seconds = ((state.remainingMs.coerceIn(0L, TransferLimits.SESSION_MS) + 999L) / 1000L).toInt()
            WatchText(pluralStringResource(R.plurals.transfer_remaining_seconds, seconds, seconds))
        }
        item(key = "transfer:extend") {
            WatchActionRow(stringResource(R.string.transfer_extend), enabled = enabled, onClick = model::extend)
        }
    }
    state.session.pending?.let { pending ->
        item(key = "transfer:peer-label") { WatchText(stringResource(R.string.transfer_peer_fingerprint), primary = true) }
        item(key = "transfer:peer") { FingerprintText(pending.fingerprint) }
        item(key = "transfer:permissions-label") { WatchText(stringResource(R.string.transfer_permissions_explanation)) }
        TransferCapability.entries.filter { it in pending.requested }.forEach { capability ->
            item(key = "transfer:permission:${capability.name}") {
                WatchCheckRow(stringResource(capabilityText(capability)), capability in state.selectedCapabilities,
                    enabled = enabled, onChange = { model.chooseCapability(pending.fingerprint, capability, it) })
            }
        }
        item(key = "transfer:approve") {
            WatchActionRow(stringResource(R.string.transfer_approve), primary = true,
                enabled = enabled && state.selectedCapabilities.isNotEmpty(), onClick = { model.approve(pending.fingerprint) })
        }
        item(key = "transfer:reject") {
            WatchActionRow(stringResource(R.string.transfer_reject), enabled = enabled,
                onClick = { model.revoke(pending.fingerprint) })
        }
    }
    state.session.approvedPeer?.let { peer ->
        item(key = "transfer:approved-label") { WatchText(stringResource(R.string.transfer_approved_peer), primary = true) }
        item(key = "transfer:approved-peer") { FingerprintText(peer) }
        TransferCapability.entries.filter { it in state.session.approvedCapabilities }.forEach { capability ->
            item(key = "transfer:approved:${capability.name}") { WatchText(stringResource(capabilityText(capability))) }
        }
        item(key = "transfer:revoke") {
            WatchActionRow(stringResource(R.string.transfer_revoke), enabled = enabled, onClick = { model.revoke(peer) })
        }
    }
    state.upload?.let { upload ->
        item(key = "transfer:upload-label") { WatchText(upload.declaration.displayName, primary = true) }
        item(key = "transfer:upload-phase") { WatchText(stringResource(uploadPhaseText(upload.phase))) }
        upload.receivedBytes?.let { bytes -> item(key = "transfer:bytes") {
            WatchText(stringResource(R.string.transfer_written_kib, (bytes / 1024L).toString()))
        } }
        if (upload.phase == TransferUploadPhase.RECOVERY_REQUIRED && upload.receipt != null) {
            item(key = "transfer:retained-receipt") { WatchText(stringResource(R.string.transfer_committed_cleanup_pending)) }
        }
    }
    if (!closed) {
        item(key = "transfer:close") {
            WatchActionRow(stringResource(R.string.transfer_close), enabled = enabled, onClick = model::closeReceiver)
        }
    }
    if (state.control.phase in setOf(ReceiverControlPhase.CLOSING, ReceiverControlPhase.RECOVERY_REQUIRED) ||
        state.serverPhase in setOf(TransferServerPhase.CLOSING, TransferServerPhase.RECOVERY_REQUIRED)) {
        item(key = "transfer:retirement") {
            WatchActionRow(stringResource(if (state.control.phase == ReceiverControlPhase.CLOSING &&
                state.serverPhase != TransferServerPhase.RECOVERY_REQUIRED) R.string.transfer_wait_again else R.string.transfer_retry_retirement),
                enabled = enabled && !state.controlPending, onClick = model::retryRetirement)
        }
    }
    if (state.received != null) {
        item(key = "transfer:received-label") { WatchText(stringResource(R.string.transfer_received_theme, state.received.displayName)) }
        item(key = "transfer:received-preview") {
            WatchActionRow(stringResource(R.string.transfer_review_theme), enabled = enabled, onClick = onAppearance)
        }
    }
    if (state.themeIssue != null) {
        item(key = "transfer:theme-issue") { WatchText(stringResource(R.string.transfer_theme_read_failed), error = true) }
        item(key = "transfer:theme-retry") {
            WatchActionRow(stringResource(R.string.transfer_refresh_theme), enabled = enabled, onClick = model::refreshReceivedTheme)
        }
    }
}

@Composable
private fun FingerprintText(fingerprint: TransferFingerprint) {
    BasicText(fingerprint.forLocalDisplay().chunked(4).joinToString(" "), Modifier.fillMaxWidth(),
        style = TextStyle(color = LocalWatchThemeColors.current.text, fontFamily = FontFamily.Monospace,
            fontSize = 14.sp, lineHeight = 20.sp))
}

private fun receiverStatus(state: WatchTransferState): Int = when {
    state.control.phase == ReceiverControlPhase.RECOVERY_REQUIRED || state.serverPhase == TransferServerPhase.RECOVERY_REQUIRED -> R.string.transfer_recovery_required
    state.control.phase == ReceiverControlPhase.CLOSING || state.serverPhase == TransferServerPhase.CLOSING -> R.string.transfer_closing
    state.control.phase == ReceiverControlPhase.PREPARING || state.serverPhase == TransferServerPhase.OPENING -> R.string.transfer_preparing
    state.serverPhase == TransferServerPhase.LISTENING && state.session.active -> R.string.transfer_listening
    else -> R.string.transfer_off
}

private fun capabilityText(capability: TransferCapability): Int = when (capability) {
    TransferCapability.UPLOAD_AUDIO -> R.string.transfer_permission_audio
    TransferCapability.READ_LIBRARY -> R.string.transfer_permission_library
    TransferCapability.WRITE_PLAYLISTS -> R.string.transfer_permission_playlists
    TransferCapability.WRITE_DISPLAY_METADATA -> R.string.transfer_permission_metadata
    TransferCapability.UPLOAD_THEME -> R.string.transfer_permission_theme
}

private fun uploadPhaseText(phase: TransferUploadPhase): Int = when (phase) {
    TransferUploadPhase.PREPARING -> R.string.transfer_upload_preparing
    TransferUploadPhase.RECEIVING -> R.string.transfer_upload_receiving
    TransferUploadPhase.VERIFYING -> R.string.transfer_upload_verifying
    TransferUploadPhase.COMMITTING -> R.string.transfer_upload_committing
    TransferUploadPhase.RETIRING -> R.string.transfer_upload_retiring
    TransferUploadPhase.COMPLETED -> R.string.transfer_upload_completed
    TransferUploadPhase.CANCELLED -> R.string.transfer_upload_cancelled
    TransferUploadPhase.FAILED -> R.string.transfer_upload_failed
    TransferUploadPhase.RECOVERY_REQUIRED -> R.string.transfer_recovery_required
}

private fun transferIssueText(issue: TransferIssue): Int = when (issue) {
    TransferIssue.BUSY -> R.string.transfer_error_busy
    TransferIssue.CLOSED, TransferIssue.EXPIRED -> R.string.transfer_error_expired
    TransferIssue.UNAUTHENTICATED, TransferIssue.NOT_APPROVED, TransferIssue.PERMISSION_DENIED,
    TransferIssue.PAIRING_FAILED, TransferIssue.PAIRING_LOCKED -> R.string.transfer_error_permission
    TransferIssue.WRONG_TARGET -> R.string.transfer_error_target
    TransferIssue.INCOMPATIBLE_SCHEMA -> R.string.transfer_error_schema
    TransferIssue.CONFLICT -> R.string.transfer_error_conflict
    TransferIssue.QUOTA_EXCEEDED -> R.string.transfer_error_quota
    TransferIssue.STORAGE_FULL -> R.string.transfer_error_space
    TransferIssue.STORAGE_UNAVAILABLE, TransferIssue.RECOVERY_REQUIRED -> R.string.transfer_recovery_required
    TransferIssue.IDENTITY_UNAVAILABLE -> R.string.transfer_error_identity
    TransferIssue.INTEGRITY_FAILED, TransferIssue.INVALID_OFFSET, TransferIssue.INVALID_INPUT -> R.string.transfer_error_integrity
    TransferIssue.UNSUPPORTED_TYPE, TransferIssue.NOT_AUDIO -> R.string.transfer_error_type
    TransferIssue.RATE_LIMITED -> R.string.transfer_error_rate
    TransferIssue.NOT_FOUND, TransferIssue.INTERRUPTED, TransferIssue.TRANSPORT_FAILED -> R.string.transfer_error_transport
}
