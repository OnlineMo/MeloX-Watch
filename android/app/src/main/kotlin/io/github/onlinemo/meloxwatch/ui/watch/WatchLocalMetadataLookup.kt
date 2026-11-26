package io.github.onlinemo.meloxwatch.ui.watch

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.data.themes.ThemeBitmapLease
import io.github.onlinemo.meloxwatch.data.themes.ThemeBitmapOwner
import io.github.onlinemo.meloxwatch.ui.metadata.LocalMetadataCandidate
import io.github.onlinemo.meloxwatch.ui.metadata.LocalMetadataIssue
import io.github.onlinemo.meloxwatch.ui.metadata.LocalMetadataKind
import io.github.onlinemo.meloxwatch.ui.metadata.LocalMetadataLookupUiState
import io.github.onlinemo.meloxwatch.ui.metadata.LocalMetadataNotice
import io.github.onlinemo.meloxwatch.ui.metadata.LocalMetadataPreview
import io.github.onlinemo.meloxwatch.ui.themes.LocalWatchThemeColors
import io.github.onlinemo.meloxwatch.ui.themes.LocalWatchThemeFonts

/** The host supplies round-safe scrolling; callbacks own requests and association tickets. */
@Composable
internal fun WatchLocalMetadataLookup(
    state: LocalMetadataLookupUiState,
    bitmaps: ThemeBitmapOwner,
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    onTitle: (String) -> Unit,
    onArtist: (String) -> Unit,
    onSendTitle: (Boolean) -> Unit,
    onSendArtist: (Boolean) -> Unit,
    onSearch: () -> Unit,
    onMore: () -> Unit,
    onPreview: (String) -> Unit,
    onPreviewPage: (Int) -> Unit,
    onConfirm: (String) -> Unit,
    onCancelPreview: () -> Unit,
    onClear: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val idle = enabled && !state.initializing && !state.working
    val targetReady = state.lookupTargetReady
    val queryEditable = idle && targetReady && state.enabled && state.preview == null
    val hasQuery = (state.sendTitle && state.title.isNotBlank()) || (state.sendArtist && state.artist.isNotBlank())
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(watchSpacing(8.dp))) {
        WatchSettingTitle(stringResource(if (state.kind == LocalMetadataKind.LYRICS)
            R.string.metadata_lookup_lyrics_title else R.string.metadata_lookup_artwork_title))
        state.targetTitle?.let { WatchText(it, primary = true) }
        state.targetArtist?.let { WatchText(it) }
        if (state.initializing) WatchText(stringResource(R.string.metadata_lookup_initializing))
        if (state.working) WatchText(stringResource(R.string.metadata_lookup_working))
        state.notice?.let { WatchText(stringResource(when (it) {
            LocalMetadataNotice.SAVED -> R.string.metadata_lookup_saved
            LocalMetadataNotice.CLEARED -> R.string.metadata_lookup_cleared
        })) }
        state.issue?.let { issue ->
            WatchText(stringResource(metadataIssueText(issue)), error = true)
            if (issue !in setOf(LocalMetadataIssue.DISABLED, LocalMetadataIssue.NO_TRACK,
                    LocalMetadataIssue.NOT_LOCAL, LocalMetadataIssue.INVALID_QUERY, LocalMetadataIssue.CLOSED)) {
                WatchActionRow(stringResource(R.string.metadata_lookup_retry), idle, onClick = onRetry)
            }
        }
        WatchSwitchRow(stringResource(R.string.metadata_lookup_enabled), state.enabled,
            enabled = enabled && !state.initializing && (!state.working || state.enabled), onChange = onEnabled)
        if (!state.initializing && !state.enabled) WatchText(stringResource(R.string.metadata_lookup_disabled))
        state.associated?.let { associated ->
            WatchText(stringResource(if (state.associationPending) R.string.metadata_lookup_pending_resource
                else R.string.metadata_lookup_associated), primary = true)
            MetadataCandidateDetails(associated)
            WatchText(stringResource(R.string.metadata_lookup_rights))
            WatchActionRow(stringResource(if (state.kind == LocalMetadataKind.LYRICS)
                R.string.metadata_lookup_clear_lyrics else R.string.metadata_lookup_clear_artwork),
                idle && state.canClear, onClick = onClear)
        }
        if (state.enabled) {
            WatchText(stringResource(R.string.metadata_lookup_disclosure), primary = true)
            WatchText(stringResource(R.string.metadata_lookup_privacy))
            WatchCheckRow(stringResource(R.string.metadata_lookup_send_title), state.sendTitle,
                queryEditable, onSendTitle)
            MetadataQueryField(stringResource(R.string.metadata_lookup_title_field), state.title,
                queryEditable && state.sendTitle, onTitle)
            WatchCheckRow(stringResource(R.string.metadata_lookup_send_artist), state.sendArtist,
                queryEditable, onSendArtist)
            MetadataQueryField(stringResource(R.string.metadata_lookup_artist_field), state.artist,
                queryEditable && state.sendArtist, onArtist)
            if (state.preview == null) {
                WatchActionRow(stringResource(R.string.metadata_lookup_search), queryEditable && hasQuery,
                    primary = true, onClick = { keyboard?.hide(); focus.clearFocus(); onSearch() })
                if (!hasQuery) WatchText(stringResource(R.string.metadata_lookup_query_required))
                if (state.candidates.isNotEmpty()) {
                    WatchText(stringResource(R.string.metadata_lookup_candidates), primary = true)
                    WatchText(stringResource(R.string.metadata_lookup_rights))
                    state.candidates.forEach { candidate ->
                        key(candidate.id) {
                            WatchContentRow(enabled = queryEditable, onClick = { onPreview(candidate.id) }) {
                                MetadataCandidateDetails(candidate)
                                WatchText(stringResource(R.string.metadata_lookup_open_preview))
                            }
                        }
                    }
                    if (state.more) WatchActionRow(stringResource(R.string.metadata_lookup_more),
                        queryEditable && hasQuery, onClick = onMore)
                } else if (!state.working && state.issue == null) {
                    WatchText(stringResource(R.string.metadata_lookup_search_hint))
                }
            } else {
                val preview = state.preview
                WatchText(stringResource(R.string.metadata_lookup_preview), primary = true)
                MetadataCandidateDetails(preview.candidate)
                WatchText(stringResource(R.string.metadata_lookup_rights))
                val previewReady = when (state.kind) {
                    LocalMetadataKind.LYRICS -> {
                        preview.lyricsText?.let { WatchText(it, primary = true) }
                        if (preview.textPages > 1) {
                            WatchText(stringResource(R.string.metadata_lookup_text_page,
                                preview.textPage + 1, preview.textPages))
                            WatchActionRow(stringResource(R.string.metadata_lookup_previous_page),
                                idle && targetReady && preview.textPage > 0, onClick = { onPreviewPage(preview.textPage - 1) })
                            WatchActionRow(stringResource(R.string.metadata_lookup_next_page),
                                idle && targetReady && preview.textPage < preview.textPages - 1,
                                onClick = { onPreviewPage(preview.textPage + 1) })
                        }
                        !preview.lyricsText.isNullOrBlank()
                    }
                    LocalMetadataKind.ARTWORK -> metadataArtworkPreview(preview, bitmaps,
                        enabled && targetReady && !state.initializing, idle && targetReady, onRetry)
                }
                WatchActionRow(stringResource(R.string.metadata_lookup_confirm), idle && targetReady && previewReady,
                    primary = true, onClick = { onConfirm(preview.id) })
                WatchActionRow(stringResource(R.string.metadata_lookup_cancel_preview), enabled,
                    onClick = onCancelPreview)
            }
        }
        WatchActionRow(stringResource(R.string.metadata_lookup_back), enabled, onClick = onBack)
    }
}

@Composable
private fun MetadataCandidateDetails(candidate: LocalMetadataCandidate) {
    WatchText(candidate.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.metadata_lookup_unknown_title), primary = true)
    WatchText(candidate.artist?.takeIf { it.isNotBlank() } ?: stringResource(R.string.metadata_lookup_unknown_artist))
    candidate.album?.takeIf { it.isNotBlank() }?.let { WatchText(it) }
    WatchText(stringResource(R.string.metadata_lookup_source))
}

@Composable
private fun MetadataQueryField(label: String, value: String, enabled: Boolean, onValue: (String) -> Unit) {
    val colors = LocalWatchThemeColors.current
    val fonts = LocalWatchThemeFonts.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(watchSpacing(10.dp))
    Column(verticalArrangement = Arrangement.spacedBy(watchSpacing(4.dp))) {
        WatchText(label, primary = true, enabled = enabled)
        BasicTextField(value, onValueChange = onValue, enabled = enabled, singleLine = true,
            interactionSource = interaction,
            textStyle = TextStyle(color = if (enabled) colors.text else colors.disabled,
                fontFamily = fonts.originalFamily, fontSynthesis = FontSynthesis.None,
                fontSize = 16.sp, lineHeight = 22.sp), cursorBrush = SolidColor(colors.text),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth().heightIn(min = watchRowMinimum()).clip(shape)
                .background(colors.surface).border(if (focused) 2.dp else 1.dp,
                    if (focused) colors.focus else colors.outline, shape)
                .semantics { contentDescription = label }.padding(watchSpacing(12.dp)))
    }
}

/** A display acquires its own bounded lease and retires it when its ticket or visibility changes. */
@Composable
private fun metadataArtworkPreview(preview: LocalMetadataPreview, bitmaps: ThemeBitmapOwner,
    enabled: Boolean, canRetry: Boolean, onRetry: () -> Unit): Boolean {
    var lease by remember(bitmaps, preview.id, preview.artworkKey) { mutableStateOf<ThemeBitmapLease?>(null) }
    var unavailable by remember(bitmaps, preview.id, preview.artworkKey) { mutableStateOf(false) }
    DisposableEffect(bitmaps, preview.id, preview.artworkKey, enabled) {
        val acquired = if (enabled && preview.artworkKey != null) {
            try { bitmaps.acquire(preview.artworkKey) } catch (_: Exception) { null }
        } else null
        lease = acquired
        unavailable = enabled && preview.artworkKey != null && acquired == null
        onDispose { acquired?.close(); lease = null }
    }
    val image = remember(lease) { lease?.bitmap?.takeUnless { it.isRecycled }?.asImageBitmap() }
    val colors = LocalWatchThemeColors.current
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val edge = minOf(maxWidth, watchSpacing(144.dp))
        if (enabled && image != null) Image(image,
            stringResource(R.string.metadata_lookup_artwork_description,
                preview.candidate.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.metadata_lookup_unknown_title)),
            Modifier.size(edge).clip(RoundedCornerShape(watchSpacing(8.dp))).background(colors.surface),
            contentScale = ContentScale.Fit)
        else Box(Modifier.size(edge).clip(RoundedCornerShape(watchSpacing(8.dp))).background(colors.surface))
    }
    if (enabled && image == null && !unavailable) WatchText(stringResource(R.string.metadata_lookup_preview_pending))
    if (enabled && unavailable) {
        WatchText(stringResource(R.string.metadata_lookup_preview_unavailable), error = true)
        WatchActionRow(stringResource(R.string.metadata_lookup_retry), canRetry, onClick = onRetry)
    }
    return enabled && image != null
}

private fun metadataIssueText(issue: LocalMetadataIssue): Int = when (issue) {
    LocalMetadataIssue.DISABLED -> R.string.metadata_lookup_disabled
    LocalMetadataIssue.NO_TRACK -> R.string.metadata_lookup_no_track
    LocalMetadataIssue.NOT_LOCAL -> R.string.metadata_lookup_not_local
    LocalMetadataIssue.INVALID_QUERY -> R.string.metadata_lookup_invalid_query
    LocalMetadataIssue.OFFLINE -> R.string.metadata_lookup_offline
    LocalMetadataIssue.SERVICE_UNAVAILABLE -> R.string.metadata_lookup_service_unavailable
    LocalMetadataIssue.TIMEOUT -> R.string.metadata_lookup_timeout
    LocalMetadataIssue.CERTIFICATE -> R.string.metadata_lookup_certificate
    LocalMetadataIssue.RATE_LIMITED -> R.string.metadata_lookup_rate_limited
    LocalMetadataIssue.BUSY -> R.string.metadata_lookup_busy
    LocalMetadataIssue.NO_RESULTS -> R.string.metadata_lookup_no_results
    LocalMetadataIssue.NO_LYRICS -> R.string.metadata_lookup_no_lyrics
    LocalMetadataIssue.NO_ARTWORK -> R.string.metadata_lookup_no_artwork
    LocalMetadataIssue.INVALID_RESPONSE -> R.string.metadata_lookup_invalid_response
    LocalMetadataIssue.INPUT_LIMIT -> R.string.metadata_lookup_input_limit
    LocalMetadataIssue.INVALID_IMAGE -> R.string.metadata_lookup_invalid_image
    LocalMetadataIssue.LOW_MEMORY -> R.string.metadata_lookup_low_memory
    LocalMetadataIssue.LOW_SPACE -> R.string.metadata_lookup_low_space
    LocalMetadataIssue.STORAGE -> R.string.metadata_lookup_storage
    LocalMetadataIssue.SOURCE_CHANGED -> R.string.metadata_lookup_source_changed
    LocalMetadataIssue.CLOSED -> R.string.metadata_lookup_closed
}
