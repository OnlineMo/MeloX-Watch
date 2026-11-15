package io.github.onlinemo.meloxwatch.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Shapes
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.data.ProbeAccess
import io.github.onlinemo.meloxwatch.data.ProbeDocument
import io.github.onlinemo.meloxwatch.data.ProbeFileIssue
import io.github.onlinemo.meloxwatch.data.ProbeFiles
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var probeViewModel: ProbeViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        probeViewModel = ViewModelProvider(this)[ProbeViewModel::class.java]
        if (savedInstanceState == null) acceptExternalAudio(intent)
        setContent {
            ProbeTheme {
                ProbeApp(probeViewModel, this)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptExternalAudio(intent)
    }

    override fun onStart() {
        super.onStart()
        probeViewModel.onForeground()
    }

    override fun onStop() {
        probeViewModel.onBackground()
        super.onStop()
    }

    private fun acceptExternalAudio(source: Intent?) {
        val sourceIntent = source ?: return
        val uri = sourceIntent.data ?: return
        if (sourceIntent.action == Intent.ACTION_VIEW) probeViewModel.selectDocument(uri, sourceIntent.flags)
    }
}

private val Background = Color(0xff000000)
private val Surface = Color(0xff151a17)
private val PrimaryText = Color(0xfff2f7f4)
private val SecondaryText = Color(0xffb7c6bd)
private val Accent = Color(0xff8de6c0)
private val OnAccent = Color(0xff082419)

@Composable
private fun ProbeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = OnAccent,
            background = Background,
            onBackground = PrimaryText,
            surface = Surface,
            onSurface = PrimaryText,
            onSurfaceVariant = SecondaryText,
            error = Color(0xffffb4ab),
        ),
        typography = Typography(
            titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
            labelLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
        ),
        shapes = Shapes(medium = RoundedCornerShape(12.dp)),
        content = content,
    )
}

@Composable
private fun ProbeApp(viewModel: ProbeViewModel, activity: MainActivity) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val savedPages = rememberSaveableStateHolder()
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val uri = data?.data
        if (result.resultCode == Activity.RESULT_OK && data != null && uri != null) {
            viewModel.selectDocument(uri, data.flags)
        } else {
            viewModel.pickerCancelled()
        }
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val uri = data?.data
        if (result.resultCode == Activity.RESULT_OK && data != null && uri != null) {
            viewModel.readTreeSample(uri, data.flags)
        } else {
            viewModel.pickerCancelled()
        }
    }
    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.readMediaSample()
        } else {
            viewModel.permissionDenied(permanently = !activity.shouldShowRequestPermissionRationale(viewModel.audioPermission))
        }
    }
    val openDocument = {
        if (state.hasDocumentPicker) {
            val request = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("audio/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            try {
                documentPicker.launch(request)
            } catch (_: ActivityNotFoundException) {
                viewModel.noPicker(isTree = false)
            }
        } else {
            viewModel.noPicker(isTree = false)
        }
    }
    val openTree = {
        if (state.hasTreePicker) {
            val request = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            try {
                treePicker.launch(request)
            } catch (_: ActivityNotFoundException) {
                viewModel.noPicker(isTree = true)
            }
        } else {
            viewModel.noPicker(isTree = true)
        }
    }
    val openMedia = {
        if (viewModel.hasAudioPermission()) viewModel.readMediaSample() else audioPermission.launch(viewModel.audioPermission)
    }
    val openSettings = {
        val request = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${activity.packageName}".toUri())
        try {
            activity.startActivity(request)
        } catch (_: ActivityNotFoundException) {
            viewModel.permissionDenied(permanently = true)
        }
    }
    BackHandler(enabled = state.page != ProbePage.HOME) {
        viewModel.showPage(ProbePage.HOME)
    }
    savedPages.SaveableStateProvider(state.page.name) {
        ProbePageContent(state.page) {
            item { PageTitle(stringResource(pageTitle(state.page))) }
            if (state.page != ProbePage.HOME) {
                item { ProbeButton(stringResource(R.string.probe_back), onClick = { viewModel.showPage(ProbePage.HOME) }) }
            }
            item { OperationStatus(state, onCancel = viewModel::cancelFileRead) }
            when (state.page) {
                ProbePage.HOME -> homeContent(state, viewModel, openDocument, openTree, openMedia, openSettings)
                ProbePage.MEDIA -> mediaContent(state, viewModel, openMedia, openSettings)
                ProbePage.TREE -> treeContent(state, viewModel, openTree)
                ProbePage.INPUT -> item { InputCheck() }
                ProbePage.PLAYBACK -> playbackContent(state, viewModel, openDocument)
                ProbePage.DEVICE -> item { DeviceDetails() }
            }
        }
    }
}

@Composable
private fun ProbePageContent(page: ProbePage, content: LazyListScope.() -> Unit) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val hasKeyboard = WindowInsets.ime.getBottom(density) > 0
    val listState = rememberLazyListState()
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(Background).safeDrawingPadding().imePadding(),
    ) {
        val isRound = configuration.isScreenRound
        val contentWidth = if (isRound) maxWidth * 0.78f else maxWidth - 24.dp
        val roundInset = maxHeight * 0.22f
        val verticalInset = if (isRound && !(page == ProbePage.INPUT && hasKeyboard)) roundInset else 12.dp
        LazyColumn(
            modifier = Modifier.width(contentWidth.coerceAtLeast(48.dp)).align(Alignment.Center),
            state = listState,
            contentPadding = PaddingValues(top = verticalInset, bottom = verticalInset + 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

private fun LazyListScope.homeContent(
    state: ProbeUiState,
    viewModel: ProbeViewModel,
    openDocument: () -> Unit,
    openTree: () -> Unit,
    openMedia: () -> Unit,
    openSettings: () -> Unit,
) {
    item { BodyText(stringResource(R.string.probe_intro)) }
    item { ProbeButton(stringResource(R.string.probe_open_audio), enabled = !state.isReading, primary = true, onClick = openDocument) }
    if (!state.hasDocumentPicker) item { BodyText(stringResource(R.string.probe_no_document_picker)) }
    item { ProbeButton(stringResource(R.string.probe_open_media), enabled = !state.isReading, onClick = openMedia) }
    item { BodyText(stringResource(R.string.probe_media_permission_explanation)) }
    if (state.needsPermissionSettings) item { ProbeButton(stringResource(R.string.probe_permission_settings), onClick = openSettings) }
    item { ProbeButton(stringResource(R.string.probe_open_tree), enabled = !state.isReading, onClick = openTree) }
    if (!state.hasTreePicker) item { BodyText(stringResource(R.string.probe_no_tree_picker)) }
    item { ProbeButton(stringResource(R.string.probe_playback), onClick = { viewModel.showPage(ProbePage.PLAYBACK) }) }
    item { ProbeButton(stringResource(R.string.probe_input), onClick = { viewModel.showPage(ProbePage.INPUT) }) }
    item { ProbeButton(stringResource(R.string.probe_device), onClick = { viewModel.showPage(ProbePage.DEVICE) }) }
}

private fun LazyListScope.mediaContent(
    state: ProbeUiState,
    viewModel: ProbeViewModel,
    openMedia: () -> Unit,
    openSettings: () -> Unit,
) {
    item {
        BodyText(pluralStringResource(R.plurals.probe_media_scope, ProbeFiles.MEDIA_SAMPLE_LIMIT, ProbeFiles.MEDIA_SAMPLE_LIMIT))
    }
    item { ProbeButton(stringResource(R.string.probe_refresh), enabled = !state.isReading, onClick = openMedia) }
    if (state.needsPermissionSettings) item { ProbeButton(stringResource(R.string.probe_permission_settings), onClick = openSettings) }
    if (state.mediaSample.isEmpty() && !state.isReading && state.fileIssue == null) {
        item { BodyText(stringResource(R.string.probe_empty_media)) }
    }
    items(state.mediaSample, key = { document -> document.uri.toString() }) { document ->
        DocumentButton(document, !state.isReading) { viewModel.selectMedia(document) }
    }
}

private fun LazyListScope.treeContent(state: ProbeUiState, viewModel: ProbeViewModel, openTree: () -> Unit) {
    item {
        BodyText(pluralStringResource(R.plurals.probe_tree_scope, ProbeFiles.TREE_CHILD_LIMIT, ProbeFiles.TREE_CHILD_LIMIT))
    }
    item { ProbeButton(stringResource(R.string.probe_choose_tree), enabled = !state.isReading, onClick = openTree) }
    val sample = state.treeSample
    if (sample != null) {
        item {
            BodyText(pluralStringResource(R.plurals.probe_tree_checked, sample.inspectedChildren, sample.inspectedChildren))
        }
        item { BodyText(pluralStringResource(R.plurals.probe_tree_candidates, sample.audio.size, sample.audio.size)) }
        item { BodyText(stringResource(accessText(sample.access))) }
        if (sample.reachedLimit) item { BodyText(stringResource(R.string.probe_tree_more)) }
        if (sample.access != ProbeAccess.PERSISTED) {
            item {
                ProbeButton(
                    stringResource(R.string.probe_keep_tree_access),
                    enabled = !state.isReading,
                    onClick = viewModel::persistTreeAccess,
                )
            }
        }
        if (sample.audio.isEmpty()) item { BodyText(stringResource(R.string.probe_empty_tree)) }
        items(sample.audio, key = { document -> document.uri.toString() }) { document ->
            DocumentButton(document, !state.isReading) { viewModel.selectTreeAudio(document) }
        }
    }
}

private fun LazyListScope.playbackContent(state: ProbeUiState, viewModel: ProbeViewModel, openDocument: () -> Unit) {
    val selected = state.selectedAudio
    val playback = state.playback
    if (selected != null) {
        item { BodyText(stringResource(R.string.probe_selected_audio)) }
        item { BodyText(selected.displayName.ifBlank { stringResource(R.string.probe_unnamed_audio) }, isPrimary = true) }
        item { BodyText(stringResource(accessText(selected.access))) }
        if (selected.access != ProbeAccess.MEDIA_PERMISSION && selected.access != ProbeAccess.PERSISTED) {
            item { ProbeButton(stringResource(R.string.probe_keep_access), enabled = !state.isReading, onClick = viewModel::persistSelectedAccess) }
        }
    }
    if (playback.hasMedia) {
        item { BodyText(stringResource(R.string.probe_current_audio)) }
        item { BodyText(playback.title.orEmpty().ifBlank { stringResource(R.string.probe_unnamed_audio) }, isPrimary = true) }
    }
    item { BodyText(stringResource(playbackText(playback))) }
    if (!playback.isConnected) {
        item { ProbeButton(stringResource(R.string.probe_reconnect), enabled = !state.isReading, onClick = viewModel::reconnectPlayback) }
    }
    if (playback.hasError) item { BodyText(stringResource(R.string.probe_playback_error), isError = true) }
    item {
        val label = if (selected != null && selected.mediaId != playback.mediaId) R.string.probe_play_selected else R.string.probe_play
        ProbeButton(
            stringResource(label),
            enabled = playback.isConnected && (selected != null || playback.hasMedia) && !state.isReading,
            primary = true,
            onClick = viewModel::playSelection,
        )
    }
    item { ProbeButton(stringResource(R.string.probe_pause), enabled = playback.isConnected && playback.hasMedia, onClick = viewModel::pause) }
    item { SeekControls(playback, viewModel) }
    item { ProbeButton(stringResource(R.string.probe_choose_another), enabled = !state.isReading, onClick = openDocument) }
}

@Composable
private fun SeekControls(playback: ProbePlaybackState, viewModel: ProbeViewModel) {
    var previewMs by remember(playback.mediaId) { mutableStateOf<Long?>(null) }
    val durationMs = playback.durationMs
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(
                R.string.probe_position,
                elapsedTime(playback.positionMs),
                durationMs?.let(::elapsedTime) ?: stringResource(R.string.probe_unknown_duration),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = SecondaryText,
        )
        if (!playback.canSeek || durationMs == null) {
            BodyText(stringResource(R.string.probe_seek_unavailable))
            return@Column
        }
        val targetMs = previewMs ?: playback.positionMs
        val seekLabel = stringResource(R.string.probe_seek_label)
        Slider(
            value = (targetMs.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f),
            onValueChange = { fraction -> previewMs = (fraction.toDouble() * durationMs).toLong().coerceIn(0L, durationMs) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = seekLabel },
        )
        if (previewMs != null) {
            BodyText(stringResource(R.string.probe_seek_preview, elapsedTime(targetMs)))
            ProbeButton(stringResource(R.string.probe_seek_commit), primary = true) {
                viewModel.seekTo(playback.mediaId, targetMs)
                previewMs = null
            }
            ProbeButton(stringResource(R.string.probe_cancel)) { previewMs = null }
        }
        ProbeButton(stringResource(R.string.probe_seek_back)) {
            previewMs = null
            viewModel.seekTo(playback.mediaId, (playback.positionMs - 10000L).coerceAtLeast(0L))
        }
        ProbeButton(stringResource(R.string.probe_seek_forward)) {
            previewMs = null
            viewModel.seekTo(playback.mediaId, (playback.positionMs + 10000L).coerceAtMost(durationMs))
        }
    }
}

@Composable
private fun InputCheck() {
    var titleDraft by rememberSaveable { mutableStateOf("") }
    var addressDraft by rememberSaveable { mutableStateOf("") }
    var hasSubmitted by rememberSaveable { mutableStateOf(false) }
    var isAddressValid by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        if (titleDraft.isNotBlank()) {
            hasSubmitted = true
            isAddressValid = validAddressSyntax(addressDraft)
            keyboard?.hide()
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BodyText(stringResource(R.string.probe_input_explanation))
        OutlinedTextField(
            value = titleDraft,
            onValueChange = { value ->
                if (value.length <= 256) {
                    titleDraft = value
                    hasSubmitted = false
                }
            },
            label = { Text(stringResource(R.string.probe_input_title)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            maxLines = 3,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            value = addressDraft,
            onValueChange = { value ->
                if (value.length <= 1024) {
                    addressDraft = value
                    hasSubmitted = false
                }
            },
            label = { Text(stringResource(R.string.probe_input_address)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            maxLines = 4,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        ProbeButton(stringResource(R.string.probe_input_submit), enabled = titleDraft.isNotBlank(), primary = true, onClick = submit)
        if (hasSubmitted) {
            BodyText(stringResource(R.string.probe_input_echo, titleDraft))
            BodyText(stringResource(if (isAddressValid) R.string.probe_input_address_valid else R.string.probe_input_address_invalid))
        }
        ProbeButton(stringResource(R.string.probe_input_clear)) {
            titleDraft = ""
            addressDraft = ""
            hasSubmitted = false
            keyboard?.hide()
        }
    }
}

@Composable
private fun DeviceDetails() {
    val configuration = LocalConfiguration.current
    val windowSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val widthDp = with(density) { windowSize.width.toDp().value.roundToInt() }
    val heightDp = with(density) { windowSize.height.toDp().value.roundToInt() }
    val shape = stringResource(if (configuration.isScreenRound) R.string.probe_shape_round else R.string.probe_shape_rectangle)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BodyText(stringResource(R.string.probe_device_api, android.os.Build.VERSION.SDK_INT))
        BodyText(stringResource(R.string.probe_device_abi, android.os.Build.SUPPORTED_ABIS.joinToString(", ")))
        BodyText(stringResource(R.string.probe_device_shape, shape))
        BodyText(stringResource(R.string.probe_device_window, widthDp, heightDp))
        BodyText(stringResource(R.string.probe_device_font, configuration.fontScale.toString()))
        BodyText(stringResource(R.string.probe_device_scope))
    }
}

@Composable
private fun OperationStatus(state: ProbeUiState, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val notice = state.notice
        if (notice != null) BodyText(stringResource(noticeText(notice)), isError = notice == ProbeNotice.TIMEOUT || notice == ProbeNotice.CONNECTION_FAILED)
        val issue = state.fileIssue
        if (issue != null) BodyText(stringResource(fileIssueText(issue)), isError = true)
        if (state.isReading) ProbeButton(stringResource(R.string.probe_cancel), onClick = onCancel)
    }
}

@Composable
private fun PageTitle(title: String) {
    Text(
        title,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.titleLarge,
        color = PrimaryText,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun BodyText(text: String, isPrimary: Boolean = false, isError: Boolean = false) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = if (isPrimary) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
        color = if (isError) MaterialTheme.colorScheme.error else if (isPrimary) PrimaryText else SecondaryText,
    )
}

@Composable
private fun DocumentButton(document: ProbeDocument, enabled: Boolean, onClick: () -> Unit) {
    ProbeButton(document.displayName.ifBlank { stringResource(R.string.probe_unnamed_audio) }, enabled = enabled, onClick = onClick)
}

@Composable
private fun ProbeButton(
    label: String,
    enabled: Boolean = true,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) Accent else Surface,
            contentColor = if (primary) OnAccent else PrimaryText,
            disabledContainerColor = Surface,
            disabledContentColor = Color(0xff95a29a),
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, textAlign = TextAlign.Center)
    }
}

private fun pageTitle(page: ProbePage): Int = when (page) {
    ProbePage.HOME -> R.string.app_name
    ProbePage.MEDIA -> R.string.probe_media_title
    ProbePage.TREE -> R.string.probe_tree_title
    ProbePage.INPUT -> R.string.probe_input
    ProbePage.PLAYBACK -> R.string.probe_playback
    ProbePage.DEVICE -> R.string.probe_device
}

private fun accessText(access: ProbeAccess): Int = when (access) {
    ProbeAccess.TEMPORARY -> R.string.probe_access_temporary
    ProbeAccess.PERSISTED -> R.string.probe_access_persisted
    ProbeAccess.MEDIA_PERMISSION -> R.string.probe_access_media
    ProbeAccess.PERSISTENCE_REJECTED -> R.string.probe_access_rejected
}

private fun playbackText(playback: ProbePlaybackState): Int = when {
    !playback.isConnected -> R.string.probe_connecting
    !playback.hasMedia -> R.string.probe_no_audio
    playback.hasError -> R.string.probe_playback_failed
    playback.playbackState == Player.STATE_BUFFERING -> R.string.probe_buffering
    playback.playbackState == Player.STATE_ENDED -> R.string.probe_ended
    playback.isPlaying -> R.string.probe_playing
    else -> R.string.probe_paused
}

private fun noticeText(notice: ProbeNotice): Int = when (notice) {
    ProbeNotice.READING -> R.string.probe_reading
    ProbeNotice.READY -> R.string.probe_read_ready
    ProbeNotice.CANCELLED -> R.string.probe_cancelled
    ProbeNotice.TIMEOUT -> R.string.probe_timeout
    ProbeNotice.NO_DOCUMENT_PICKER -> R.string.probe_no_document_picker
    ProbeNotice.NO_TREE_PICKER -> R.string.probe_no_tree_picker
    ProbeNotice.PERMISSION_DENIED -> R.string.probe_permission_denied
    ProbeNotice.CONNECTION_FAILED -> R.string.probe_connection_failed
    ProbeNotice.COMMAND_REJECTED -> R.string.probe_command_rejected
    ProbeNotice.BACKGROUND_START_DENIED -> R.string.probe_background_start_denied
}

private fun fileIssueText(issue: ProbeFileIssue): Int = when (issue) {
    ProbeFileIssue.PERMISSION_DENIED -> R.string.probe_permission_denied
    ProbeFileIssue.INVALID_URI -> R.string.probe_invalid_uri
    ProbeFileIssue.UNREADABLE_FILE -> R.string.probe_unreadable_file
    ProbeFileIssue.NOT_AUDIO -> R.string.probe_not_audio
    ProbeFileIssue.SOURCE_UNAVAILABLE -> R.string.probe_source_unavailable
    ProbeFileIssue.SOURCE_BUSY -> R.string.probe_source_busy
}

private fun elapsedTime(positionMs: Long): String {
    val totalSeconds = positionMs.coerceAtLeast(0L) / 1000L
    return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60L, totalSeconds % 60L)
}

private fun validAddressSyntax(address: String): Boolean {
    return try {
        val uri = URI(address.trim())
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null
    } catch (_: URISyntaxException) {
        false
    }
}
