/* Visual reference: MeloX/MeloXWatch Library and Account views, GPL-3.0,
 * 09713edb847a0f0a3515a34f779cc630d4fe5985. Foundation rendering and lifecycle are original Android code. */
package io.github.onlinemo.meloxwatch.ui.online

import androidx.compose.foundation.Canvas
import androidx.annotation.PluralsRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.data.auth.QrLoginPhase
import io.github.onlinemo.meloxwatch.data.auth.SessionValidity
import io.github.onlinemo.meloxwatch.data.online.OnlineQrGrid
import io.github.onlinemo.meloxwatch.data.themes.ThemeBitmapLease
import io.github.onlinemo.meloxwatch.model.OnlineCatalogEntry
import io.github.onlinemo.meloxwatch.model.OnlineCatalogEntryKind
import io.github.onlinemo.meloxwatch.model.OnlineCatalogIssue
import io.github.onlinemo.meloxwatch.model.OnlineCatalogKind
import io.github.onlinemo.meloxwatch.playback.OnlineQueueAction
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue
import io.github.onlinemo.meloxwatch.playback.resources.StreamingQuality
import io.github.onlinemo.meloxwatch.ui.themes.LocalWatchThemeColors
import io.github.onlinemo.meloxwatch.ui.themes.LocalWatchThemeFonts
import io.github.onlinemo.meloxwatch.ui.watch.WatchActionRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchChoiceRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchContentRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchText
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException

internal fun LazyListScope.onlineContent(state: OnlineUiState, model: OnlineViewModel, visible: Boolean,
    visibleKeys: List<Any> = emptyList(),
    onMetingSettings: (() -> Unit)? = null) {
    if (state.busy) item(key = "online:busy") {
        WatchText(state.progress?.let { progress ->
            if (progress.finishingMetadata && progress.reportedTotal != null) {
                stringResource(R.string.online_progress_metadata, progress.received, progress.reportedTotal)
            } else onlineCount(R.plurals.online_progress, progress.received)
        }
            ?: stringResource(R.string.online_loading))
        WatchActionRow(stringResource(R.string.online_cancel), onClick = model::cancel)
    }
    state.issue?.let { issue -> item(key = "online:issue") { WatchText(stringResource(onlineIssueText(issue)), error = true) } }
    state.submission?.playbackIssue?.let { issue -> item(key = "online:playback-issue") {
        WatchText(stringResource(onlinePlaybackIssueText(issue)), error = true)
    } }
    when (state.screen) {
        OnlineScreen.HOME -> {
            item(key = "online:account") {
                WatchText(state.account.account?.displayName ?: stringResource(R.string.online_account_signed_out), primary = true)
                if (state.account.validity == SessionValidity.RESTORED_UNVERIFIED) WatchText(stringResource(R.string.online_account_unverified))
                if (!state.online) WatchText(stringResource(R.string.online_offline_cached))
            }
            if (state.account.account == null) item {
                WatchActionRow(stringResource(R.string.online_sign_in), primary = true, enabled = !state.busy, onClick = model::beginLogin)
            }
            item { WatchActionRow(stringResource(R.string.online_playlists), enabled = !state.busy) { model.open(OnlineCatalogKind.PLAYLISTS) } }
            item { WatchActionRow(stringResource(R.string.online_liked), enabled = !state.busy) { model.open(OnlineCatalogKind.LIKED) } }
            item { WatchActionRow(stringResource(R.string.online_daily), enabled = !state.busy) { model.open(OnlineCatalogKind.DAILY) } }
            item { WatchActionRow(stringResource(R.string.online_search), enabled = !state.busy, onClick = model::showSearch) }
            if (state.account.account != null) item {
                WatchActionRow(stringResource(R.string.online_sign_out), enabled = !state.busy, onClick = model::showLogoutConfirmation)
            }
            onMetingSettings?.let { open -> item { WatchActionRow(stringResource(R.string.online_meting), onClick = open) } }
        }
        OnlineScreen.LOGIN -> {
            state.login.grid?.let { grid -> item(key = "online:qr:${state.login.generation}") { WatchQrCode(grid) } }
            item { WatchText(stringResource(loginPhaseText(state.login.phase)), primary = true) }
            item { WatchText(stringResource(R.string.online_qr_instructions)) }
            state.login.issue?.let { issue -> item { WatchText(stringResource(onlineIssueText(issue)), error = true) } }
            item { WatchActionRow(stringResource(R.string.online_qr_refresh), enabled = !state.busy, onClick = model::refreshLogin) }
        }
        OnlineScreen.SEARCH -> {
            item(key = "online:search") { WatchSearchField(state.searchDraft, model::editSearch, model::search, !state.busy) }
            item { WatchActionRow(stringResource(R.string.online_search), primary = true,
                enabled = !state.busy && state.searchDraft.isNotBlank(), onClick = model::search) }
            item { WatchText(stringResource(R.string.online_search_hint)) }
        }
        OnlineScreen.CATALOG -> {
            state.title?.let { title -> item(key = "online:title") { WatchText(title, primary = true) } }
            val snapshot = state.snapshot
            if (snapshot != null) {
                val indexed = snapshot.kind in setOf(OnlineCatalogKind.LIKED, OnlineCatalogKind.PLAYLIST)
                item {
                    WatchText(onlineCount(R.plurals.online_loaded_count, snapshot.count))
                    val time = remember(snapshot.cachedAtEpochMs) {
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(snapshot.cachedAtEpochMs))
                    }
                    WatchText(stringResource(R.string.online_cache_time, time))
                    if (!snapshot.complete && !indexed) WatchText(stringResource(R.string.online_more_pages))
                    if (snapshot.missingMetadataCount > 0L) WatchText(stringResource(R.string.online_missing_metadata))
                }
                if (state.kind != OnlineCatalogKind.PLAYLISTS && snapshot.count > 0L) item {
                    WatchActionRow(stringResource(R.string.online_play_all), primary = true, enabled = !state.busy, onClick = model::playAll)
                    WatchText(stringResource(R.string.online_complete_list_note))
                }
                if (snapshot.count == 0L) item { WatchText(stringResource(R.string.online_empty)) }
                items(state.rows, key = { "online:item:${snapshot.id}:${it.order}" }) { row ->
                    val rowVisible = visible && "online:item:${snapshot.id}:${row.order}" in visibleKeys
                    WatchOnlineRow(row, !state.busy, model, rowVisible)
                }
                if (state.pageStart > 0L) item { WatchActionRow(stringResource(R.string.online_previous_page), enabled = !state.busy) { model.page(-1) } }
                if (state.pageStart + state.rows.size < snapshot.count || !snapshot.complete && !indexed && state.online) item {
                    WatchActionRow(stringResource(R.string.online_next_page), enabled = !state.busy) { model.page(1) }
                }
            }
            item { WatchActionRow(stringResource(R.string.online_refresh), enabled = !state.busy, onClick = model::refresh) }
            if (state.issue == OnlineCatalogIssue.LOGIN_REQUIRED || state.issue == OnlineCatalogIssue.SESSION_UNAVAILABLE) item {
                WatchActionRow(stringResource(R.string.online_sign_in), enabled = !state.busy, onClick = model::beginLogin)
            }
        }
        OnlineScreen.TRACK_ACTION -> {
            state.selected?.let { selected -> item(key = "online:selection") {
                WatchText(selected.title ?: stringResource(R.string.online_unknown_title), primary = true)
                WatchText(selected.artist ?: stringResource(R.string.library_unknown_artist))
            } }
            item { WatchActionRow(stringResource(R.string.online_play_this_list), primary = true, enabled = !state.busy) {
                model.playSelected(OnlineQueueAction.REPLACE_COMPLETE_LIST)
            } }
            item { WatchActionRow(stringResource(R.string.online_insert_and_play), enabled = !state.busy) { model.playSelected(OnlineQueueAction.INSERT_AND_PLAY) } }
            item { WatchActionRow(stringResource(R.string.online_play_next), enabled = !state.busy) { model.playSelected(OnlineQueueAction.INSERT_NEXT) } }
            item { WatchChoiceRow(stringResource(R.string.online_quality_standard), state.quality == StreamingQuality.STANDARD, !state.busy) { model.setQuality(StreamingQuality.STANDARD) } }
            item { WatchChoiceRow(stringResource(R.string.online_quality_high), state.quality == StreamingQuality.HIGH, !state.busy) { model.setQuality(StreamingQuality.HIGH) } }
        }
        OnlineScreen.LOGOUT_CONFIRM -> {
            item { WatchText(stringResource(R.string.online_sign_out_confirmation), primary = true) }
            item { WatchActionRow(stringResource(R.string.online_sign_out), enabled = !state.busy, onClick = model::confirmLogout) }
            item { WatchActionRow(stringResource(R.string.online_cancel), enabled = !state.busy) { model.back() } }
        }
    }
}

@Composable
private fun WatchOnlineRow(row: OnlineCatalogEntry, enabled: Boolean, model: OnlineViewModel, visible: Boolean) {
    WatchContentRow(enabled = enabled, onClick = { model.choose(row) }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WatchOnlineArtwork(row, 36.dp, visible) { pixels -> model.artwork(row, pixels) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                OnlineLabel(row.title ?: stringResource(R.string.online_unknown_title), primary = true)
                OnlineLabel(if (row.kind == OnlineCatalogEntryKind.PLAYLIST) row.trackCount?.let { onlineCount(R.plurals.online_track_count, it) }
                        ?: stringResource(R.string.online_unknown_count)
                    else row.artist ?: stringResource(R.string.library_unknown_artist), primary = false)
            }
        }
    }
}

@Composable
internal fun WatchOnlineArtwork(row: OnlineCatalogEntry?, edge: Dp, visible: Boolean,
    acquire: suspend (Int) -> ThemeBitmapLease?) {
    WatchBoundedArtwork(row?.let { it.id to it.artworkKey }, edge, visible, acquire)
}

@Composable
internal fun WatchBoundedArtwork(identity: Any?, edge: Dp, visible: Boolean,
    acquire: suspend (Int) -> ThemeBitmapLease?) {
    val targetPx = with(LocalDensity.current) { edge.roundToPx().coerceIn(1, 512) }
    val holder = remember(identity, targetPx) { ArtworkHolder() }
    var bitmapRevision by remember(holder) { mutableStateOf(0L) }
    LaunchedEffect(holder, visible) {
        holder.close()
        bitmapRevision += 1L
        if (visible && identity != null) try {
            holder.lease = acquire(targetPx)
            bitmapRevision += 1L
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { }
    }
    DisposableEffect(holder) { onDispose { holder.close() } }
    val colors = LocalWatchThemeColors.current
    Box(Modifier.size(edge).clip(RoundedCornerShape(8.dp)).background(colors.surface), contentAlignment = Alignment.Center) {
        val image = remember(holder, bitmapRevision) { holder.lease?.bitmap }
        if (image != null && !image.isRecycled) Image(image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Canvas(Modifier.size(minOf(edge * .4f, 36.dp))) {
            val line = size.minDimension * .09f
            drawCircle(colors.secondary, radius = size.minDimension * .15f,
                center = Offset(size.width * .32f, size.height * .75f))
            drawLine(colors.secondary, Offset(size.width * .47f, size.height * .21f),
                Offset(size.width * .47f, size.height * .75f), line, StrokeCap.Round)
            drawLine(colors.secondary, Offset(size.width * .47f, size.height * .23f),
                Offset(size.width * .76f, size.height * .34f), line, StrokeCap.Round)
        }
    }
}

private class ArtworkHolder : AutoCloseable {
    var lease: ThemeBitmapLease? = null
    override fun close() {
        lease?.close()
        lease = null
    }
}

@Composable
private fun OnlineLabel(value: String, primary: Boolean) {
    val colors = LocalWatchThemeColors.current
    BasicText(value, maxLines = if (primary) 2 else 1, overflow = TextOverflow.Ellipsis,
        style = TextStyle(color = if (primary) colors.text else colors.secondary,
            fontFamily = LocalWatchThemeFonts.current.originalFamily, fontSynthesis = FontSynthesis.None,
            fontSize = if (primary) 16.sp else 13.sp, fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal))
}

@Composable
private fun WatchSearchField(value: String, change: (String) -> Unit, submit: () -> Unit, enabled: Boolean) {
    val colors = LocalWatchThemeColors.current
    val label = stringResource(R.string.online_search)
    BasicTextField(value, change, enabled = enabled, singleLine = true,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
            .background(colors.surface).padding(12.dp).semantics { contentDescription = label },
        textStyle = TextStyle(color = colors.text, fontSize = 16.sp, fontFamily = LocalWatchThemeFonts.current.originalFamily,
            fontSynthesis = FontSynthesis.None),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) WatchText(stringResource(R.string.online_search_placeholder))
                inner()
            }
        })
}

@Composable
private fun WatchQrCode(grid: OnlineQrGrid) {
    val label = stringResource(R.string.online_qr_accessibility)
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val edge = minOf(maxWidth, 176.dp)
        Box(Modifier.size(edge).clip(RoundedCornerShape(14.dp)).background(Color.White).padding(8.dp)) {
            Canvas(Modifier.fillMaxSize().semantics { contentDescription = label }) {
                val cell = (size.minDimension / grid.edge).toInt().coerceAtLeast(1).toFloat()
                val origin = Offset((size.width - cell * grid.edge) / 2f, (size.height - cell * grid.edge) / 2f)
                for (y in 0 until grid.edge) for (x in 0 until grid.edge) if (grid[x, y]) {
                    drawRect(Color.Black, origin + Offset(x * cell, y * cell), Size(cell, cell))
                }
            }
        }
    }
}

@Composable
private fun onlineCount(@PluralsRes id: Int, count: Long): String {
    // Both supported UI languages use one only for 1; the displayed count retains its Long range.
    val quantity = when (count) {
        0L -> 0
        1L -> 1
        else -> 2
    }
    return pluralStringResource(id, quantity, count)
}

private fun loginPhaseText(phase: QrLoginPhase): Int = when (phase) {
    QrLoginPhase.WAITING_SCAN -> R.string.online_qr_waiting_scan
    QrLoginPhase.WAITING_CONFIRMATION -> R.string.online_qr_waiting_confirm
    QrLoginPhase.VERIFYING, QrLoginPhase.SAVING -> R.string.online_qr_verifying
    QrLoginPhase.SIGNED_IN -> R.string.online_qr_success
    QrLoginPhase.EXPIRED -> R.string.online_qr_expired
    QrLoginPhase.OFFLINE -> R.string.online_issue_offline
    QrLoginPhase.ERROR -> R.string.online_qr_failed
    else -> R.string.online_qr_generating
}

private fun onlineIssueText(issue: OnlineCatalogIssue): Int = when (issue) {
    OnlineCatalogIssue.OFFLINE -> R.string.online_issue_offline
    OnlineCatalogIssue.TIMEOUT -> R.string.online_issue_timeout
    OnlineCatalogIssue.CERTIFICATE -> R.string.online_issue_certificate
    OnlineCatalogIssue.RATE_LIMITED -> R.string.online_issue_rate_limited
    OnlineCatalogIssue.LOGIN_REQUIRED -> R.string.online_issue_login
    OnlineCatalogIssue.SESSION_UNAVAILABLE -> R.string.online_issue_session
    OnlineCatalogIssue.STALE_VIEW -> R.string.online_issue_stale
    OnlineCatalogIssue.INCOMPLETE -> R.string.online_issue_incomplete
    OnlineCatalogIssue.BUSY -> R.string.online_issue_busy
    OnlineCatalogIssue.STORAGE_FULL -> R.string.online_issue_storage_full
    OnlineCatalogIssue.STORAGE_UNAVAILABLE -> R.string.online_issue_storage
    OnlineCatalogIssue.CLOSED -> R.string.online_issue_closed
    OnlineCatalogIssue.INVALID_RESPONSE -> R.string.online_issue_response
}

private fun onlinePlaybackIssueText(issue: QueuePlaybackIssue): Int = when (issue) {
    QueuePlaybackIssue.LOGIN_REQUIRED -> R.string.online_issue_login
    QueuePlaybackIssue.NETWORK_UNAVAILABLE -> R.string.online_issue_offline
    QueuePlaybackIssue.NETWORK_TIMEOUT -> R.string.online_issue_timeout
    QueuePlaybackIssue.NETWORK_CERTIFICATE -> R.string.online_issue_certificate
    QueuePlaybackIssue.NETWORK_RATE_LIMITED -> R.string.online_issue_rate_limited
    QueuePlaybackIssue.STORAGE_FULL -> R.string.online_issue_storage_full
    QueuePlaybackIssue.CONFLICT, QueuePlaybackIssue.SOURCE_CHANGED -> R.string.online_issue_stale
    else -> R.string.online_issue_playback
}
