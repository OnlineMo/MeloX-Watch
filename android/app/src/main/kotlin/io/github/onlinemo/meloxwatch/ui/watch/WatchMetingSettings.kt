package io.github.onlinemo.meloxwatch.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.data.meting.MetingCredentialEdit
import io.github.onlinemo.meloxwatch.data.meting.MetingEndpointMode
import io.github.onlinemo.meloxwatch.data.meting.MetingIssue
import io.github.onlinemo.meloxwatch.data.meting.MetingPlaybackTestState
import io.github.onlinemo.meloxwatch.data.meting.MetingTrackMatch
import io.github.onlinemo.meloxwatch.playback.resources.ResourceCompleteness
import io.github.onlinemo.meloxwatch.ui.meting.MetingSettingsState
import io.github.onlinemo.meloxwatch.ui.meting.MetingSettingsViewModel
import io.github.onlinemo.meloxwatch.ui.themes.LocalWatchThemeColors
import io.github.onlinemo.meloxwatch.ui.themes.LocalWatchThemeFonts

/** host 提供既有圆/矩形滚动避让；输入真实交给 IME，秘密不进入 rememberSaveable。 */
@Composable
internal fun WatchMetingSettings(
    state: MetingSettingsState,
    visible: Boolean,
    viewModel: MetingSettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(watchSpacing(8.dp))) {
        WatchText(stringResource(R.string.meting_title), primary = true)
        WatchText(stringResource(R.string.meting_privacy))
        if (state.working) WatchText(stringResource(R.string.meting_working))
        (state.issue ?: state.saved.issue)?.let { WatchText(stringResource(metingIssueText(it)), error = true) }
        val draft = state.draft
        if (draft == null) {
            WatchText(state.saved.hostLabel ?: stringResource(R.string.meting_unconfigured))
            WatchSwitchRow(stringResource(R.string.meting_enabled), state.saved.enabled,
                enabled = !state.working && state.saved.configured && state.saved.issue == null,
                onChange = viewModel::setEnabled)
            WatchActionRow(stringResource(R.string.meting_edit), !state.working, onClick = viewModel::beginEdit)
            WatchActionRow(stringResource(R.string.meting_read_again), !state.working, onClick = viewModel::readAgain)
            WatchActionRow(stringResource(R.string.meting_delete), !state.working && state.saved.loaded,
                onClick = { viewModel.confirmDeletion(true) })
            if (state.deleteConfirmation) {
                WatchText(stringResource(R.string.meting_delete_confirmation))
                WatchActionRow(stringResource(R.string.meting_delete_confirm), !state.working,
                    primary = true, onClick = viewModel::delete)
                WatchActionRow(stringResource(R.string.meting_cancel), !state.working,
                    onClick = { viewModel.confirmDeletion(false) })
            }
            WatchText(stringResource(R.string.meting_test_title), primary = true)
            MetingInput(stringResource(R.string.meting_test_id), state.testTrackId, !state.working,
                KeyboardType.Number, onValue = viewModel::setTrackId)
            WatchActionRow(stringResource(R.string.meting_use_current), !state.working, onClick = viewModel::useCurrentTrack)
            WatchActionRow(stringResource(R.string.meting_test_metadata), state.canTest,
                primary = true, onClick = viewModel::test)
            WatchActionRow(stringResource(R.string.meting_play_current), state.canTest && state.saved.enabled,
                onClick = viewModel::playCurrentTrack)
            WatchText(stringResource(R.string.meting_playback_policy))
            val report = state.report
            WatchText(stringResource(R.string.meting_address_stage,
                stringResource(if (report?.addressValid == true || state.saved.configured) R.string.meting_valid else R.string.meting_not_tested)))
            WatchText(stringResource(R.string.meting_protocol_stage,
                stringResource(if (report?.protocolCompatible == true) R.string.meting_compatible else R.string.meting_not_confirmed)))
            WatchText(stringResource(R.string.meting_resource_stage,
                stringResource(if (report?.resourceResolved == true) R.string.meting_resolved else R.string.meting_not_confirmed)))
            WatchText(stringResource(R.string.meting_playback_stage,
                stringResource(metingPlaybackText(report?.playback ?: MetingPlaybackTestState.NOT_STARTED))))
            report?.issue?.let { WatchText(stringResource(metingIssueText(it)), error = true) }
            report?.match?.let { WatchText(stringResource(if (it == MetingTrackMatch.EXACT_SERVICE_DECLARATION)
                R.string.meting_match_declared else R.string.meting_match_unverified)) }
            report?.completeness?.let { WatchText(stringResource(if (it == ResourceCompleteness.PREVIEW_REPORTED)
                R.string.meting_preview else R.string.meting_completeness_unknown)) }
            if (report?.artworkIssue != null) WatchText(stringResource(R.string.meting_artwork_unavailable))
            if (report?.lyricsIssue != null) WatchText(stringResource(R.string.meting_lyrics_unavailable))
        } else {
            state.saved.hostLabel?.let { WatchText(it) }
            WatchText(stringResource(R.string.meting_edit_explanation))
            MetingInput(stringResource(R.string.meting_address), draft.endpoint, !state.working,
                KeyboardType.Uri, secret = true, onValue = { viewModel.changeDraft(draft.changed(endpoint = it)) })
            WatchText(stringResource(R.string.meting_address_masked))
            WatchChoiceRow(stringResource(R.string.meting_endpoint_mode), draft.mode == MetingEndpointMode.ENDPOINT,
                !state.working, onSelect = { viewModel.changeDraft(draft.changed(mode = MetingEndpointMode.ENDPOINT)) })
            WatchChoiceRow(stringResource(R.string.meting_template_mode), draft.mode == MetingEndpointMode.TEMPLATE,
                !state.working, onSelect = { viewModel.changeDraft(draft.changed(mode = MetingEndpointMode.TEMPLATE)) })
            WatchText(stringResource(R.string.meting_template_help))
            WatchCheckRow(stringResource(R.string.meting_allow_http), draft.allowCleartext, !state.working,
                onChange = { viewModel.changeDraft(draft.changed(allowCleartext = it)) })
            WatchText(stringResource(R.string.meting_http_explanation))
            WatchChoiceRow(stringResource(R.string.meting_credential_keep), draft.credentialEdit == MetingCredentialEdit.KEEP,
                !state.working && state.saved.hasCredential,
                onSelect = { viewModel.changeDraft(draft.changed(credentialEdit = MetingCredentialEdit.KEEP, credentialInput = "")) })
            WatchChoiceRow(stringResource(R.string.meting_credential_replace), draft.credentialEdit == MetingCredentialEdit.REPLACE,
                !state.working, onSelect = { viewModel.changeDraft(draft.changed(credentialEdit = MetingCredentialEdit.REPLACE)) })
            WatchChoiceRow(stringResource(R.string.meting_credential_clear), draft.credentialEdit == MetingCredentialEdit.CLEAR,
                !state.working,
                onSelect = { viewModel.changeDraft(draft.changed(credentialEdit = MetingCredentialEdit.CLEAR, credentialInput = "")) })
            if (draft.credentialEdit == MetingCredentialEdit.REPLACE) {
                MetingInput(stringResource(R.string.meting_header_name), draft.headerName, !state.working,
                    KeyboardType.Ascii, onValue = { viewModel.changeDraft(draft.changed(headerName = it)) })
                MetingInput(stringResource(R.string.meting_credential_value), draft.credentialInput, !state.working,
                    KeyboardType.Password, secret = true,
                    onValue = { viewModel.changeDraft(draft.changed(credentialInput = it)) })
            }
            WatchSwitchRow(stringResource(R.string.meting_enabled), draft.enabled, !state.working,
                onChange = { viewModel.changeDraft(draft.changed(enabled = it)) })
            WatchActionRow(stringResource(R.string.meting_save), !state.working && draft.endpoint.isNotBlank(),
                primary = true, onClick = viewModel::save)
            WatchActionRow(stringResource(R.string.meting_cancel), !state.working, onClick = viewModel::cancelEdit)
        }
        // 离页取消测试和清掉编辑；已经接受的密文提交由所有者清理，返回按钮无需锁住用户。
        WatchActionRow(stringResource(R.string.meting_back), onClick = onBack)
    }
}

@Composable
private fun MetingInput(label: String, value: String, enabled: Boolean, keyboardType: KeyboardType,
    secret: Boolean = false, onValue: (String) -> Unit) {
    val colors = LocalWatchThemeColors.current
    val fonts = LocalWatchThemeFonts.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(watchSpacing(4.dp))) {
        WatchText(label, primary = true, enabled = enabled)
        BasicTextField(value, onValueChange = onValue, enabled = enabled, singleLine = true,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = TextStyle(color = if (enabled) colors.text else colors.disabled, fontFamily = fonts.originalFamily,
                fontSynthesis = FontSynthesis.None, fontSize = 14.sp, lineHeight = 20.sp),
            cursorBrush = SolidColor(colors.text),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false,
                keyboardType = if (secret) KeyboardType.Password else keyboardType, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth().heightIn(min = watchRowMinimum()).semantics { contentDescription = label }
                .background(colors.surface, RoundedCornerShape(watchSpacing(10.dp)))
                .border(1.dp, colors.outline, RoundedCornerShape(watchSpacing(10.dp)))
                .padding(watchSpacing(12.dp)))
    }
}

private fun metingPlaybackText(state: MetingPlaybackTestState): Int = when (state) {
    MetingPlaybackTestState.NOT_STARTED -> R.string.meting_not_tested
    MetingPlaybackTestState.WAITING_NEW_RESOURCE -> R.string.meting_playback_waiting
    MetingPlaybackTestState.EXISTING_STREAM -> R.string.meting_playback_existing
    MetingPlaybackTestState.BUFFERING -> R.string.meting_playback_buffering
    MetingPlaybackTestState.READY -> R.string.meting_playback_ready
    MetingPlaybackTestState.PLAYING -> R.string.meting_playback_playing
    MetingPlaybackTestState.PAUSED -> R.string.meting_playback_paused
    MetingPlaybackTestState.ENDED -> R.string.meting_playback_ended
    MetingPlaybackTestState.FAILED -> R.string.meting_playback_failed
    MetingPlaybackTestState.OTHER_SOURCE -> R.string.meting_playback_other
}

private fun metingIssueText(issue: MetingIssue): Int = when (issue) {
    MetingIssue.NOT_CONFIGURED -> R.string.meting_unconfigured
    MetingIssue.DISABLED -> R.string.meting_disabled
    MetingIssue.INVALID_ADDRESS -> R.string.meting_invalid_address
    MetingIssue.INVALID_TEMPLATE -> R.string.meting_invalid_template
    MetingIssue.CONFLICTING_PARAMETERS -> R.string.meting_conflicting_parameters
    MetingIssue.CREDENTIAL_INVALID -> R.string.meting_credential_invalid
    MetingIssue.CREDENTIAL_ORIGIN_CHANGED -> R.string.meting_credential_origin_changed
    MetingIssue.ID_REQUIRED -> R.string.meting_id_required
    MetingIssue.WRONG_SOURCE -> R.string.meting_wrong_source
    MetingIssue.KEY_UNAVAILABLE -> R.string.meting_key_unavailable
    MetingIssue.CONFIGURATION_CORRUPT -> R.string.meting_configuration_corrupt
    MetingIssue.STORAGE_UNAVAILABLE -> R.string.meting_storage_unavailable
    MetingIssue.CONFIGURATION_CHANGED -> R.string.meting_configuration_changed
    MetingIssue.BUSY -> R.string.meting_working
    MetingIssue.CLOSED -> R.string.meting_closed
    MetingIssue.NETWORK_UNAVAILABLE -> R.string.meting_network_unavailable
    MetingIssue.NETWORK_TIMEOUT -> R.string.meting_network_timeout
    MetingIssue.CERTIFICATE -> R.string.meting_certificate
    MetingIssue.RATE_LIMITED -> R.string.meting_rate_limited
    MetingIssue.AUTH_REQUIRED -> R.string.meting_auth_required
    MetingIssue.PROTOCOL_INVALID -> R.string.meting_protocol_invalid
    MetingIssue.RESPONSE_TOO_LARGE -> R.string.meting_response_too_large
    MetingIssue.EMPTY_RESOURCE -> R.string.meting_empty_resource
    MetingIssue.CONFLICTING_TRACK -> R.string.meting_conflicting_track
    MetingIssue.MULTIPLE_RESULTS -> R.string.meting_multiple_results
    MetingIssue.RESOURCE_INVALID -> R.string.meting_resource_invalid
    MetingIssue.REDIRECT_LIMIT -> R.string.meting_redirect_limit
}
