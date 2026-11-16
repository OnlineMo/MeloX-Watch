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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Shapes
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.Player
import io.github.onlinemo.meloxwatch.R
import io.github.onlinemo.meloxwatch.MeloXApplication
import io.github.onlinemo.meloxwatch.data.ProbeAccess
import io.github.onlinemo.meloxwatch.data.ProbeDocument
import io.github.onlinemo.meloxwatch.data.ProbeFileIssue
import io.github.onlinemo.meloxwatch.data.ProbeFiles
import io.github.onlinemo.meloxwatch.data.library.LibraryTrack
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.ui.library.LibraryScreen
import io.github.onlinemo.meloxwatch.ui.library.LibraryViewModel
import io.github.onlinemo.meloxwatch.ui.library.ListeningViewModel
import io.github.onlinemo.meloxwatch.ui.library.SourcesViewModel
import io.github.onlinemo.meloxwatch.ui.library.TrackArtwork
import io.github.onlinemo.meloxwatch.ui.library.libraryContent
import io.github.onlinemo.meloxwatch.ui.library.listeningIssueText
import io.github.onlinemo.meloxwatch.model.ScreenShape
import io.github.onlinemo.meloxwatch.model.ArtworkReadPath
import io.github.onlinemo.meloxwatch.model.PlaybackBackendKind
import io.github.onlinemo.meloxwatch.playback.PlaybackCommands
import io.github.onlinemo.meloxwatch.playback.PlaybackBackendIssue
import io.github.onlinemo.meloxwatch.playback.PlaybackBackendState
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue
import io.github.onlinemo.meloxwatch.ui.preferences.PreferencesControls
import io.github.onlinemo.meloxwatch.ui.preferences.PreferencesViewModel
import io.github.onlinemo.meloxwatch.ui.queue.QueueViewModel
import io.github.onlinemo.meloxwatch.ui.queue.queueContent
import io.github.onlinemo.meloxwatch.ui.playlists.AuthorizedPlaylistDocument
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistDocumentPort
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistDocumentPurpose
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistEntryStatus
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistEntrySurface
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistEntryUiState
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistEntryViewModel
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistPageLifecycle
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistPickerScreen
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistScreen
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistTrackControls
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistTransferFormat
import io.github.onlinemo.meloxwatch.ui.playlists.PlaylistsViewModel
import io.github.onlinemo.meloxwatch.ui.playlists.playlistPickerContent
import io.github.onlinemo.meloxwatch.ui.playlists.playlistReference
import io.github.onlinemo.meloxwatch.ui.playlists.playlistsContent
import io.github.onlinemo.meloxwatch.ui.watch.WatchActionRow
import io.github.onlinemo.meloxwatch.ui.watch.WatchCircleButton
import io.github.onlinemo.meloxwatch.ui.watch.WatchGlyph
import io.github.onlinemo.meloxwatch.ui.watch.WatchPalette
import io.github.onlinemo.meloxwatch.ui.watch.WatchNowPlayingPage
import io.github.onlinemo.meloxwatch.ui.watch.WatchPlaybackPages
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged

class MainActivity : ComponentActivity() {
    private lateinit var probeViewModel: ProbeViewModel
    private lateinit var preferencesViewModel: PreferencesViewModel
    private lateinit var queueViewModel: QueueViewModel
    private lateinit var libraryViewModel: LibraryViewModel
    private lateinit var sourcesViewModel: SourcesViewModel
    private lateinit var listeningViewModel: ListeningViewModel
    private lateinit var playlistsViewModel: PlaylistsViewModel
    private lateinit var playlistEntryViewModel: PlaylistEntryViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        probeViewModel = ViewModelProvider(this)[ProbeViewModel::class.java]
        preferencesViewModel = ViewModelProvider(this)[PreferencesViewModel::class.java]
        queueViewModel = ViewModelProvider(this)[QueueViewModel::class.java]
        libraryViewModel = ViewModelProvider(this)[LibraryViewModel::class.java]
        sourcesViewModel = ViewModelProvider(this)[SourcesViewModel::class.java]
        listeningViewModel = ViewModelProvider(this)[ListeningViewModel::class.java]
        val app = application as MeloXApplication
        playlistsViewModel = ViewModelProvider(this, viewModelFactory {
            initializer {
                PlaylistsViewModel(app.playlists, app.library, app.playlistQueueMapper, app.playlistFolders,
                    app.playlistKeywords, app.playlistExchange, createSavedStateHandle(),
                    closeLibraryView = app::closePlaylistView)
            }
        })[PlaylistsViewModel::class.java]
        playlistEntryViewModel = ViewModelProvider(this, viewModelFactory {
            initializer {
                PlaylistEntryViewModel(playlistsViewModel, app.playlists, app.library, app.queues,
                    object : PlaylistDocumentPort {
                        override suspend fun registerImport(uri: android.net.Uri, returnedFlags: Int,
                            rootBinding: io.github.onlinemo.meloxwatch.data.playlists.PlaylistFolderBinding?): AuthorizedPlaylistDocument =
                            app.playlistDocuments.registerImport(uri, returnedFlags, rootBinding)
                        override suspend fun registerExport(uri: android.net.Uri, returnedFlags: Int,
                            operationId: String): AuthorizedPlaylistDocument = app.playlistDocuments.registerExport(uri, returnedFlags, operationId)
                        override suspend fun discard(selectionId: String) = app.playlistDocuments.discard(selectionId)
                    }, app.playlistRecovery, closeLibraryView = app::closePlaylistView)
            }
        })[PlaylistEntryViewModel::class.java]
        if (savedInstanceState == null) acceptLaunchIntent(intent)
        setContent {
            ProbeTheme {
                ProbeApp(probeViewModel, preferencesViewModel, queueViewModel,
                    libraryViewModel, sourcesViewModel, listeningViewModel, playlistsViewModel, playlistEntryViewModel, this)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptLaunchIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        (application as MeloXApplication).onUiForeground()
        probeViewModel.onForeground()
    }

    override fun onStop() {
        probeViewModel.onBackground()
        sourcesViewModel.onBackground()
        (application as MeloXApplication).onUiBackground()
        super.onStop()
    }

    private fun acceptLaunchIntent(source: Intent?) {
        if (PlaybackCommands.opensPlayback(source)) {
            probeViewModel.showPage(ProbePage.PLAYBACK)
            return
        }
        acceptExternalAudio(source)
    }

    @Suppress("DEPRECATION")
    private fun acceptExternalAudio(source: Intent?) {
        val sourceIntent = source ?: return
        if (sourceIntent.action !in setOf(Intent.ACTION_VIEW, Intent.ACTION_SEND)) return
        val uri = try {
            if (sourceIntent.action == Intent.ACTION_VIEW) sourceIntent.data
            else if (android.os.Build.VERSION.SDK_INT >= 33) sourceIntent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
            else sourceIntent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        } catch (_: RuntimeException) { null }
        probeViewModel.showPage(ProbePage.LIBRARY)
        libraryViewModel.show(LibraryScreen.SOURCES)
        sourcesViewModel.cancelReauthorization()
        if (uri == null || uri.scheme != "content" || uri.authority.isNullOrBlank()) sourcesViewModel.invalidSelection()
        else sourcesViewModel.selected(LocalSourceKind.DOCUMENT, uri, sourceIntent.flags)
    }
}

private val Background = WatchPalette.Background
private val Surface = WatchPalette.Row
private val PrimaryText = WatchPalette.Text
private val SecondaryText = WatchPalette.Secondary
private val Accent = WatchPalette.Text
private val OnAccent = WatchPalette.Background

@Composable
private fun ProbeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = OnAccent,
            background = Background,
            onBackground = PrimaryText,
            surface = Surface,
            surfaceVariant = WatchPalette.Raised,
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
        content = {
            // Foundation watch surfaces do not inherit Material button/Surface content colors.
            CompositionLocalProvider(LocalContentColor provides PrimaryText, content = content)
        },
    )
}

@Composable
private fun ProbeApp(
    viewModel: ProbeViewModel,
    preferencesViewModel: PreferencesViewModel,
    queueViewModel: QueueViewModel,
    libraryViewModel: LibraryViewModel,
    sourcesViewModel: SourcesViewModel,
    listeningViewModel: ListeningViewModel,
    playlistsViewModel: PlaylistsViewModel,
    playlistEntryViewModel: PlaylistEntryViewModel,
    activity: MainActivity,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val preferences by preferencesViewModel.uiState.collectAsStateWithLifecycle()
    val queue by queueViewModel.uiState.collectAsStateWithLifecycle()
    val library by libraryViewModel.uiState.collectAsStateWithLifecycle()
    val sources by sourcesViewModel.uiState.collectAsStateWithLifecycle()
    val listening by listeningViewModel.uiState.collectAsStateWithLifecycle()
    val playlists by playlistsViewModel.uiState.collectAsStateWithLifecycle()
    val playlistEntry by playlistEntryViewModel.uiState.collectAsStateWithLifecycle()
    val folderMaintenanceIssue by (activity.application as MeloXApplication)
        .folderPlaylistMaintenanceIssue.collectAsStateWithLifecycle()
    val screenShape = if (state.page == ProbePage.SETTINGS) {
        preferences.previewScreenShape ?: preferences.effective.screenShape
    } else {
        preferences.effective.screenShape
    }
    LaunchedEffect(state.page) {
        if (state.page == ProbePage.SETTINGS) preferencesViewModel.enterEditor()
        else if (preferences.isEditing) preferencesViewModel.clearDraft()
    }
    LifecycleStartEffect(state.page) {
        val queueVisible = state.page == ProbePage.QUEUE
        val libraryVisible = state.page == ProbePage.LIBRARY
        if (queueVisible) queueViewModel.onVisible()
        if (libraryVisible) libraryViewModel.onVisible()
        onStopOrDispose {
            if (queueVisible) queueViewModel.onHidden()
            if (libraryVisible) libraryViewModel.onHidden()
        }
    }
    PlaylistPageLifecycle(playlistsViewModel, state.page == ProbePage.PLAYLISTS)
    LifecycleStartEffect(state.page) {
        playlistEntryViewModel.onVisible(when (state.page) {
            ProbePage.PLAYLISTS -> PlaylistEntrySurface.PLAYLISTS
            ProbePage.LIBRARY -> PlaylistEntrySurface.LIBRARY
            ProbePage.PLAYBACK -> PlaylistEntrySurface.PLAYBACK
            else -> PlaylistEntrySurface.OTHER
        })
        onStopOrDispose { playlistEntryViewModel.onHidden() }
    }
    LaunchedEffect(state.page, library.screen, library.detail) {
        playlistEntryViewModel.setLibraryFavorite(library.detail?.takeIf {
            state.page == ProbePage.LIBRARY && library.screen == LibraryScreen.DETAIL
        }?.playlistReference())
    }
    LaunchedEffect(state.playback.mediaId) { playlistEntryViewModel.setPlayingEntry(state.playback.mediaId) }
    LifecycleStartEffect(state.page, library.screen) {
        val sourcesVisible = state.page == ProbePage.LIBRARY && library.screen == LibraryScreen.SOURCES
        val listeningVisible = state.page == ProbePage.LIBRARY &&
            library.screen in setOf(LibraryScreen.HISTORY, LibraryScreen.STATISTICS)
        if (sourcesVisible) sourcesViewModel.onVisible()
        if (listeningVisible) listeningViewModel.onVisible(library.screen)
        onStopOrDispose {
            if (sourcesVisible) sourcesViewModel.onHidden()
            if (listeningVisible) listeningViewModel.onHidden()
        }
    }
    val savedPages = rememberSaveableStateHolder()
    val playlistImportPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        playlistEntryViewModel.documentResult(PlaylistDocumentPurpose.IMPORT,
            data?.data.takeIf { result.resultCode == Activity.RESULT_OK }, data?.flags ?: 0)
    }
    val playlistExportPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        playlistEntryViewModel.documentResult(PlaylistDocumentPurpose.EXPORT,
            data?.data.takeIf { result.resultCode == Activity.RESULT_OK }, data?.flags ?: 0)
    }
    LifecycleStartEffect(playlistEntry.documentRequest?.requestId) {
        playlistEntry.documentRequest?.let { ready ->
            playlistEntryViewModel.takeDocumentRequest(ready.requestId)?.let { request ->
                val mime = if (request.format == PlaylistTransferFormat.JSON) "application/json" else "audio/x-mpegurl"
                val intent = if (request.purpose == PlaylistDocumentPurpose.IMPORT) {
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                } else {
                    Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime)
                        .putExtra(Intent.EXTRA_TITLE, request.suggestedName)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                try {
                    if (request.purpose == PlaylistDocumentPurpose.IMPORT) playlistImportPicker.launch(intent)
                    else playlistExportPicker.launch(intent)
                } catch (_: ActivityNotFoundException) { playlistEntryViewModel.pickerUnavailable(request.requestId) }
            }
        }
        onStopOrDispose { }
    }
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
            viewModel.permissionDenied(
                permanently = !activity.shouldShowRequestPermissionRationale(viewModel.audioPermission),
            )
        }
    }
    val libraryFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val uri = data?.data
        if (result.resultCode == Activity.RESULT_OK && data != null && uri != null)
            sourcesViewModel.selected(LocalSourceKind.DOCUMENT, uri, data.flags)
        else sourcesViewModel.pickerCancelled()
    }
    val libraryTreePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val uri = data?.data
        if (result.resultCode == Activity.RESULT_OK && data != null && uri != null)
            sourcesViewModel.selected(LocalSourceKind.TREE, uri, data.flags)
        else sourcesViewModel.pickerCancelled()
    }
    val libraryPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) sourcesViewModel.addMediaStore() else sourcesViewModel.permissionDenied()
    }
    val chooseLibraryFile = {
        try {
            libraryFilePicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("audio/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
        } catch (_: ActivityNotFoundException) { sourcesViewModel.noPicker(tree = false) }
    }
    val chooseLibraryTree = {
        try {
            libraryTreePicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
        } catch (_: ActivityNotFoundException) { sourcesViewModel.noPicker(tree = true) }
    }
    val addLibraryMedia = {
        if (viewModel.hasAudioPermission()) sourcesViewModel.addMediaStore()
        else libraryPermission.launch(viewModel.audioPermission)
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
        if (viewModel.hasAudioPermission()) viewModel.readMediaSample()
        else audioPermission.launch(viewModel.audioPermission)
    }
    val openSettings = {
        val request = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${activity.packageName}".toUri())
        try {
            activity.startActivity(request)
        } catch (_: ActivityNotFoundException) {
            viewModel.permissionDenied(permanently = true)
        }
    }
    val returnHome = {
        if (state.page == ProbePage.SETTINGS) preferencesViewModel.clearDraft()
        viewModel.showPage(ProbePage.HOME)
    }
    val openPlaylists = {
        playlistsViewModel.showCatalog()
        viewModel.showPage(ProbePage.PLAYLISTS)
    }
    val showAddTargets = { viewModel.showPage(ProbePage.PLAYLISTS) }
    val openLibrarySources = {
        playlistEntryViewModel.closePicker()
        viewModel.showPage(ProbePage.LIBRARY)
        libraryViewModel.show(LibraryScreen.SOURCES)
    }
    val back = {
        when {
            state.page == ProbePage.PLAYLISTS && playlistEntryViewModel.back() -> Unit
            state.page == ProbePage.PLAYLISTS && playlists.playDraft != null -> {
                if (playlists.playPending) viewModel.pause() else playlistsViewModel.cancelPlay()
            }
            state.page == ProbePage.PLAYLISTS && playlists.saving -> Unit
            state.page == ProbePage.PLAYLISTS && playlists.screen != PlaylistScreen.CATALOG -> when (playlists.screen) {
                PlaylistScreen.DETAILS, PlaylistScreen.ADD_TARGETS -> playlistsViewModel.showCatalog()
                PlaylistScreen.ADD_CONFIRM -> playlistsViewModel.returnToAddTargets()
                else -> if (playlists.playlistId == null) playlistsViewModel.showCatalog() else playlistsViewModel.returnToDetails()
            }
            state.page == ProbePage.QUEUE && queue.editor != null -> queueViewModel.cancelEdit()
            state.page == ProbePage.PLAYBACK && state.seekPreview != null && !state.playbackCommandPending -> viewModel.cancelSeek()
            state.page == ProbePage.LIBRARY && libraryViewModel.back() -> Unit
            else -> returnHome()
        }
    }
    BackHandler(enabled = state.page != ProbePage.HOME, onBack = back)
    val renderPage: @Composable (ProbePage, Boolean) -> Unit = { page, visiblePage ->
        savedPages.SaveableStateProvider(page.name) {
            val resetScroll = when (page) {
                ProbePage.QUEUE -> queue.snapshot?.generationId to queue.pageStart
                ProbePage.LIBRARY -> "${library.screen}:${library.page?.view?.viewId}:${library.artworkRefreshPreview?.refreshId}" to library.pageStart
                ProbePage.PLAYLISTS -> "${playlists.screen}:${playlists.view?.viewId}:${playlists.importDraft?.preview?.previewId}:" +
                    "${playlistEntry.screen}:${playlistEntry.folder?.nodeId}:${playlistEntry.replacements?.nextCursor}" to playlists.pageStart
                else -> null
            }
            if (page == ProbePage.PLAYBACK) {
                NowPlayingContent(screenShape, state, viewModel, playlistEntry, playlistEntryViewModel,
                    preferences.effective.artworkReadPath, library.artworkRevision, visiblePage,
                    onMenu = returnHome, openDocument = openDocument,
                    onAddPlaying = { playlistEntryViewModel.addPlaying(showAddTargets) }, onPlaylists = openPlaylists)
            } else ProbePageContent(screenShape, resetScroll,
                onVisibleKeys = if (page == ProbePage.LIBRARY && visiblePage) libraryViewModel::setVisibleItemKeys else null) {
                if (page == ProbePage.QUEUE) {
                    item(key = "player:menu") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                            WatchCircleButton(stringResource(R.string.watch_open_menu), WatchGlyph.MENU, onClick = returnHome)
                        }
                    }
                    item { PageTitle(stringResource(R.string.playback_queue)) }
                } else item { PageTitle(stringResource(pageTitle(page))) }
                if (page != ProbePage.HOME && page !in setOf(ProbePage.QUEUE, ProbePage.PLAYBACK)) {
                    item { ProbeButton(stringResource(R.string.probe_back), onClick = back) }
                }
                item {
                    OperationStatus(
                        state,
                        onCancel = viewModel::cancelFileRead,
                        onRetryQueue = viewModel::retryQueueRestore,
                    )
                }
                when (page) {
                    ProbePage.HOME -> homeContent(state, viewModel, openDocument, openTree, openMedia, openSettings)
                    ProbePage.MEDIA -> mediaContent(state, viewModel, openMedia, openSettings)
                    ProbePage.TREE -> treeContent(state, viewModel, openTree)
                    ProbePage.INPUT -> item { InputCheck() }
                    ProbePage.PLAYBACK -> Unit // Dedicated viewport above; business actions keep the same owners.
                    ProbePage.DEVICE -> item { DeviceDetails() }
                    ProbePage.SETTINGS -> item {
                        PreferencesControls(preferencesViewModel, onCancel = { viewModel.showPage(ProbePage.HOME) })
                    }
                    ProbePage.QUEUE -> queueContent(
                        state = queue,
                        currentEntryId = state.playback.mediaId,
                        playbackCommandPending = state.playbackCommandPending || !state.playback.isConnected,
                        onSelect = viewModel::selectQueueEntry,
                        model = queueViewModel,
                        onMutation = { request ->
                            viewModel.editQueue(request) { queueViewModel.finishEdit(request.operationId) }
                        },
                        isPlaying = state.playback.isPlaying,
                    )
                    ProbePage.LIBRARY -> libraryContent(library, libraryViewModel, sources, sourcesViewModel,
                        state.playbackCommandPending || !state.playback.isConnected,
                        viewModel::playLibraryView, viewModel::playTemporaryEntry,
                        chooseLibraryFile, chooseLibraryTree, addLibraryMedia,
                        listening, listeningViewModel, openSettings, playlistEntry, playlistEntryViewModel, openPlaylists,
                        onAddToPlaylist = { view, track, label -> playlistEntryViewModel.addFromLibrary(view, track, label, showAddTargets) })
                    ProbePage.PLAYLISTS -> {
                        if (playlistEntry.screen != PlaylistPickerScreen.NONE) {
                            playlistPickerContent(playlistEntry, playlistEntryViewModel, openLibrarySources,
                                onReauthorize = { source ->
                                    sourcesViewModel.beginReauthorization(source)
                                    viewModel.showPage(ProbePage.LIBRARY)
                                    libraryViewModel.show(LibraryScreen.SOURCES)
                                })
                        } else {
                            if (folderMaintenanceIssue != null) {
                                item(key = "playlists:folder-maintenance") {
                                    BodyText(stringResource(R.string.playlist_folder_maintenance_pending))
                                }
                            }
                            item(key = "playlists:entry-status") { PlaylistEntryStatus(playlistEntry, playlistEntryViewModel) }
                            val binding = playlistEntry.queue
                            val mode = playlistEntry.mode
                            if (binding == null || mode == null) {
                                item { BodyText(stringResource(R.string.playlist_entry_queue_waiting)) }
                            }
                            playlistsContent(playlists, playlistsViewModel, binding, mode,
                                state.playbackCommandPending || !state.playback.isConnected,
                                onPlay = { command -> viewModel.playPlaylistView(command) { issue ->
                                    playlistsViewModel.acknowledgePlay(command.operationId, issue)
                                } }, onChooseFolder = playlistEntryViewModel::chooseFolder,
                                onChooseImport = playlistEntryViewModel::chooseImport,
                                onChooseExport = playlistEntryViewModel::chooseExport,
                                onRecover = playlistEntryViewModel::recover,
                                onAddToPlaylist = playlistEntryViewModel::addFromMember)
                        }
                    }
                }
            }
        }
    }
    if (state.page in setOf(ProbePage.QUEUE, ProbePage.PLAYBACK)) {
        WatchPlaybackPages(state.page, !state.playbackCommandPending && state.seekPreview == null && queue.editor == null,
            onPage = { page -> if (page != state.page) viewModel.showPage(page) }, content = renderPage)
    } else {
        renderPage(state.page, true)
    }
}

@Composable
private fun ProbePageContent(
    screenShape: ScreenShape,
    resetScroll: Pair<String?, Long>?,
    onVisibleKeys: ((List<Any>) -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val fullWindowSize = LocalWindowInfo.current.containerSize
    val fullWindowHeight = with(density) { fullWindowSize.height.toDp() }
    val listState = rememberLazyListState()
    LaunchedEffect(onVisibleKeys, resetScroll) {
        if (onVisibleKeys != null) snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.key } }
            .distinctUntilChanged().collect(onVisibleKeys)
    }
    LaunchedEffect(resetScroll) {
        if (resetScroll != null) listState.scrollToItem(0)
    }
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(Background).safeDrawingPadding().imePadding(),
    ) {
        val isRound = when (screenShape) {
            ScreenShape.AUTO -> configuration.isScreenRound
            ScreenShape.ROUND -> true
            ScreenShape.RECTANGULAR -> false
        }
        // 手动矩形布局不能关闭真实圆屏的边界避让。
        val protectsRoundBounds = isRound || configuration.isScreenRound
        val requestedWidth = if (protectsRoundBounds) maxWidth * 0.78f else maxWidth - 24.dp
        val minimumWidth = minOf(48.dp, maxWidth)
        val contentWidth = requestedWidth.coerceIn(minimumWidth, maxWidth)
        // 键盘只收缩可见滚动区域；顶部圆边避让仍以完整窗口计算。
        val roundInset = maxOf(fullWindowHeight, maxHeight) * 0.22f
        val verticalInset = if (protectsRoundBounds) roundInset else 12.dp
        LazyColumn(
            modifier = Modifier.width(contentWidth).align(Alignment.Center),
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
    item { ProbeButton(stringResource(R.string.probe_playback), primary = true) { viewModel.showPage(ProbePage.PLAYBACK) } }
    item { ProbeButton(stringResource(R.string.library_title), primary = true) { viewModel.showPage(ProbePage.LIBRARY) } }
    item { ProbeButton(stringResource(R.string.playlist_heading)) { viewModel.showPage(ProbePage.PLAYLISTS) } }
    item { ProbeButton(stringResource(R.string.playback_queue), onClick = { viewModel.showPage(ProbePage.QUEUE) }) }
    item { ProbeButton(stringResource(R.string.playback_settings), onClick = { viewModel.showPage(ProbePage.SETTINGS) }) }
    item(key = "menu:device-checks") {
        var expanded by rememberSaveable { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ProbeButton(stringResource(R.string.watch_device_checks)) { expanded = !expanded }
            if (expanded) {
                BodyText(stringResource(R.string.probe_intro))
                ProbeButton(stringResource(R.string.probe_open_audio), !state.isReading, onClick = openDocument)
                if (!state.hasDocumentPicker) BodyText(stringResource(R.string.probe_no_document_picker))
                ProbeButton(stringResource(R.string.probe_open_media), !state.isReading, onClick = openMedia)
                BodyText(stringResource(R.string.probe_media_permission_explanation))
                if (state.needsPermissionSettings) ProbeButton(stringResource(R.string.probe_permission_settings), onClick = openSettings)
                ProbeButton(stringResource(R.string.probe_open_tree), !state.isReading, onClick = openTree)
                if (!state.hasTreePicker) BodyText(stringResource(R.string.probe_no_tree_picker))
                ProbeButton(stringResource(R.string.probe_input)) { viewModel.showPage(ProbePage.INPUT) }
                ProbeButton(stringResource(R.string.probe_device)) { viewModel.showPage(ProbePage.DEVICE) }
            }
        }
    }
}

private fun LazyListScope.mediaContent(
    state: ProbeUiState,
    viewModel: ProbeViewModel,
    openMedia: () -> Unit,
    openSettings: () -> Unit,
) {
    item {
        BodyText(
            pluralStringResource(R.plurals.probe_media_scope, ProbeFiles.MEDIA_SAMPLE_LIMIT, ProbeFiles.MEDIA_SAMPLE_LIMIT),
        )
    }
    item { ProbeButton(stringResource(R.string.probe_refresh), enabled = !state.isReading, onClick = openMedia) }
    if (state.needsPermissionSettings) {
        item { ProbeButton(stringResource(R.string.probe_permission_settings), onClick = openSettings) }
    }
    if (state.mediaSample.isEmpty() && !state.isReading && state.fileIssue == null) {
        item { BodyText(stringResource(R.string.probe_empty_media)) }
    }
    items(state.mediaSample, key = { document -> document.uri.toString() }) { document ->
        DocumentButton(document, !state.isReading) { viewModel.selectMedia(document) }
    }
}

private fun LazyListScope.treeContent(state: ProbeUiState, viewModel: ProbeViewModel, openTree: () -> Unit) {
    item {
        BodyText(
            pluralStringResource(R.plurals.probe_tree_scope, ProbeFiles.TREE_CHILD_LIMIT, ProbeFiles.TREE_CHILD_LIMIT),
        )
    }
    item { ProbeButton(stringResource(R.string.probe_choose_tree), enabled = !state.isReading, onClick = openTree) }
    val sample = state.treeSample
    if (sample != null) {
        item {
            BodyText(
                pluralStringResource(R.plurals.probe_tree_checked, sample.inspectedChildren, sample.inspectedChildren),
            )
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

@Composable
private fun NowPlayingContent(
    screenShape: ScreenShape,
    state: ProbeUiState,
    viewModel: ProbeViewModel,
    entry: PlaylistEntryUiState,
    entryModel: PlaylistEntryViewModel,
    artworkPath: ArtworkReadPath,
    artworkRevision: Long,
    visible: Boolean,
    onMenu: () -> Unit,
    openDocument: () -> Unit,
    onAddPlaying: () -> Unit,
    onPlaylists: () -> Unit,
) {
    val playback = state.playback
    val track = entry.playingTrack
    val title = playback.title?.takeIf(String::isNotBlank) ?: track?.title ?: stringResource(R.string.probe_unnamed_audio)
    val artist = track?.metadata?.artist?.takeIf(String::isNotBlank) ?: playback.artist?.takeIf(String::isNotBlank)
        ?: stringResource(R.string.library_unknown_artist)
    WatchNowPlayingPage(screenShape, title, artist.takeIf { playback.hasMedia },
        stringResource(R.string.watch_open_menu), onMenu,
        artwork = { size -> NowPlayingArtwork(track, artworkPath, artworkRevision, visible, size) },
        transport = { centerSize, horizontal -> WatchTransport(state, viewModel, centerSize, horizontal) }) {
        OperationStatus(state, onCancel = viewModel::cancelFileRead, onRetryQueue = viewModel::retryQueueRestore)
        PlaybackDetails(state, viewModel, openDocument, title, artist)
        if (playback.hasMedia) {
            PlaylistEntryStatus(entry, entryModel)
            PlaylistTrackControls(entry, entryModel, onAdd = onAddPlaying, onPlaylists = onPlaylists)
        }
    }
}

@Composable
private fun PlaybackDetails(state: ProbeUiState, viewModel: ProbeViewModel, openDocument: () -> Unit,
    title: String, artist: String) {
    val selected = state.selectedAudio
    val playback = state.playback
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BodyText(stringResource(playbackText(playback)))
        if (!playback.isConnected) {
            ProbeButton(
                stringResource(R.string.probe_reconnect),
                enabled = !state.isReading,
                onClick = viewModel::reconnectPlayback,
            )
        }
        if (playback.hasError) BodyText(stringResource(R.string.probe_playback_error), isError = true)
        if (state.playbackCommandPending) {
            BodyText(stringResource(R.string.playback_preparing_queue))
            ProbeButton(stringResource(R.string.playback_cancel_prepare), onClick = viewModel::pause)
        }
        SeekControls(state, viewModel)
        BodyText(stringResource(R.string.watch_swipe_queue))
        ProbeButton(stringResource(R.string.playback_queue), onClick = { viewModel.showPage(ProbePage.QUEUE) })
        if (selected != null && state.selectedQueueEntry?.queueEntryId != playback.mediaId) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BodyText(stringResource(R.string.probe_selected_audio))
                BodyText(selected.displayName.ifBlank { stringResource(R.string.probe_unnamed_audio) }, isPrimary = true)
                ProbeButton(stringResource(R.string.playback_replace_with_selection), playback.isConnected &&
                    !state.isReading && !state.playbackCommandPending, onClick = viewModel::playSelection)
            }
        }
        if (selected != null && selected.access != ProbeAccess.MEDIA_PERMISSION && selected.access != ProbeAccess.PERSISTED) {
            BodyText(stringResource(accessText(selected.access)))
            ProbeButton(stringResource(R.string.probe_keep_access), !state.isReading, onClick = viewModel::persistSelectedAccess)
        }
        ProbeButton(stringResource(R.string.probe_choose_another), enabled = !state.isReading, onClick = openDocument)
        var expanded by rememberSaveable { mutableStateOf(false) }
        ProbeButton(stringResource(R.string.watch_playback_details)) { expanded = !expanded }
        if (expanded) {
            BodyText(stringResource(R.string.probe_current_audio))
            // The selected document may differ from the active session; use the hero's exact resolved metadata.
            BodyText(title, isPrimary = true)
            if (playback.hasMedia) BodyText(artist)
            BackendStatus(state.backend)
        }
    }
}

@Composable
private fun NowPlayingArtwork(track: LibraryTrack?, path: ArtworkReadPath, artworkRevision: Long,
    visible: Boolean, size: androidx.compose.ui.unit.Dp) {
    if (track == null) {
        Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(WatchPalette.Raised),
            contentAlignment = Alignment.Center) { Text("♪", color = PrimaryText, fontSize = 32.sp) }
    } else TrackArtwork(track, path, artworkRevision, size, visible = visible)
}

@Composable
private fun WatchTransport(state: ProbeUiState, viewModel: ProbeViewModel,
    centerSize: androidx.compose.ui.unit.Dp, horizontal: Boolean) {
    val playback = state.playback
    val previousLabel = stringResource(R.string.playback_previous)
    val nextLabel = stringResource(R.string.playback_next)
    val centerLabel = stringResource(if (playback.isPlaying) R.string.probe_pause else R.string.probe_play)
    val center: @Composable () -> Unit = {
        WatchCircleButton(centerLabel, if (playback.isPlaying) WatchGlyph.PAUSE else WatchGlyph.PLAY,
            playback.isConnected && playback.hasMedia && (!state.playbackCommandPending || playback.isPlaying),
            centerSize, emphasized = true) { if (playback.isPlaying) viewModel.pause() else viewModel.resumeCurrent() }
    }
    val previous: @Composable () -> Unit = {
        WatchCircleButton(previousLabel, WatchGlyph.PREVIOUS,
            playback.isConnected && playback.hasPrevious && !state.playbackCommandPending, onClick = viewModel::previous)
    }
    val next: @Composable () -> Unit = {
        WatchCircleButton(nextLabel, WatchGlyph.NEXT,
            playback.isConnected && playback.hasNext && !state.playbackCommandPending, onClick = viewModel::next)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (horizontal) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically) {
                previous()
                center()
                next()
            }
        } else {
            // Narrow windows keep full independent targets by placing the center above the two side controls.
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                center()
                if (maxWidth >= 104.dp) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { previous(); next() }
                else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { previous(); next() }
            }
        }
    }
}

@Composable
private fun SeekControls(state: ProbeUiState, viewModel: ProbeViewModel) {
    val playback = state.playback
    val preview = state.seekPreview
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
        if (!playback.canSeek || durationMs == null || durationMs <= 0L) {
            BodyText(stringResource(R.string.probe_seek_unavailable))
            return@Column
        }
        if (preview == null) {
            ProbeButton(
                stringResource(R.string.playback_seek_open),
                enabled = !state.playbackCommandPending,
                onClick = viewModel::beginSeek,
            )
            return@Column
        }
        val targetMs = preview.positionMs
        val boundDuration = preview.binding.durationMs
        val enabled = !state.playbackCommandPending
        val seekLabel = stringResource(R.string.probe_seek_label)
        BodyText(stringResource(R.string.playback_seek_scope))
        Slider(
            value = (targetMs.toDouble() / boundDuration.toDouble()).toFloat().coerceIn(0f, 1f),
            onValueChange = { fraction ->
                viewModel.previewSeek((fraction.toDouble() * boundDuration).toLong())
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = seekLabel },
            enabled = enabled,
        )
        BodyText(stringResource(R.string.probe_seek_preview, elapsedTime(targetMs)))
        ProbeButton(stringResource(R.string.probe_seek_back), enabled && targetMs > 0L) {
            viewModel.previewSeek((targetMs - 10000L).coerceAtLeast(0L))
        }
        ProbeButton(stringResource(R.string.probe_seek_forward), enabled && targetMs < boundDuration) {
            val remaining = boundDuration - targetMs
            viewModel.previewSeek(targetMs + minOf(10000L, remaining))
        }
        ProbeButton(stringResource(R.string.playback_seek_start), enabled && targetMs != 0L) {
            viewModel.previewSeek(0L)
        }
        ProbeButton(stringResource(R.string.probe_seek_commit), enabled, primary = true, onClick = viewModel::confirmSeek)
        ProbeButton(stringResource(R.string.probe_cancel), enabled, onClick = viewModel::cancelSeek)
    }
}

@Composable
private fun BackendStatus(backend: PlaybackBackendState?) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (backend == null) {
            BodyText(stringResource(R.string.playback_backend_waiting))
            return@Column
        }
        BodyText(stringResource(R.string.playback_backend_requested, stringResource(backendLabel(backend.requestedBackend))))
        if (backend.switching) BodyText(stringResource(R.string.playback_backend_switching))
        val actual = backend.actualBackend
        if (actual == null) BodyText(stringResource(R.string.playback_backend_none))
        else {
            BodyText(stringResource(R.string.playback_backend_actual, stringResource(backendLabel(actual))))
            BodyText(stringResource(R.string.playback_speed_actual,
                backend.speed.requestedTenths / 10f, backend.speed.actualSpeed, backend.speed.actualPitch))
            if (backend.speed.pending) BodyText(stringResource(R.string.playback_speed_pending))
            if (backend.gaplessRequested) BodyText(stringResource(
                if (backend.gaplessAvailable) R.string.playback_gapless_active else R.string.playback_gapless_fallback,
            ))
        }
        backend.speed.issue?.let { BodyText(stringResource(backendIssueText(it)), isError = true) }
        backend.issue?.takeIf { it != backend.speed.issue }?.let {
            BodyText(stringResource(backendIssueText(it)), isError = true)
        }
    }
}

private fun backendLabel(kind: PlaybackBackendKind): Int = when (kind) {
    PlaybackBackendKind.MEDIA3 -> R.string.prefs_backend_media3
    PlaybackBackendKind.SYSTEM_MEDIA_PLAYER -> R.string.prefs_backend_system
}

private fun backendIssueText(issue: PlaybackBackendIssue): Int = when (issue) {
    PlaybackBackendIssue.FOCUS_DENIED -> R.string.playback_focus_denied
    PlaybackBackendIssue.FOREGROUND_DENIED -> R.string.probe_background_start_denied
    PlaybackBackendIssue.SOURCE_UNAVAILABLE -> R.string.playback_source_unavailable
    PlaybackBackendIssue.SOURCE_NOT_SEEKABLE -> R.string.probe_seek_unavailable
    PlaybackBackendIssue.PREPARATION_FAILED -> R.string.playback_engine_prepare_failed
    PlaybackBackendIssue.SEEK_FAILED -> R.string.playback_seek_failed
    PlaybackBackendIssue.SPEED_UNSUPPORTED -> R.string.playback_speed_unsupported
    PlaybackBackendIssue.SWITCH_FAILED -> R.string.playback_engine_switch_failed
    PlaybackBackendIssue.CLOSED -> R.string.playback_queue_closed
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
        ProbeButton(
            stringResource(R.string.probe_input_submit),
            enabled = titleDraft.isNotBlank(),
            primary = true,
            onClick = submit,
        )
        if (hasSubmitted) {
            BodyText(stringResource(R.string.probe_input_echo, titleDraft))
            val addressStatus = if (isAddressValid) R.string.probe_input_address_valid
            else R.string.probe_input_address_invalid
            BodyText(stringResource(addressStatus))
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
    val shapeLabel = if (configuration.isScreenRound) R.string.probe_shape_round else R.string.probe_shape_rectangle
    val shape = stringResource(shapeLabel)
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
private fun OperationStatus(state: ProbeUiState, onCancel: () -> Unit, onRetryQueue: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val notice = state.notice
        if (notice != null) {
            BodyText(
                stringResource(noticeText(notice)),
                isError = notice == ProbeNotice.TIMEOUT || notice == ProbeNotice.CONNECTION_FAILED,
            )
        }
        val issue = state.fileIssue
        if (issue != null) BodyText(stringResource(fileIssueText(issue)), isError = true)
        state.queueIssue?.let { queueIssue ->
            BodyText(stringResource(queueIssueText(queueIssue)), isError = true)
            ProbeButton(
                stringResource(R.string.playback_reload_queue),
                enabled = state.playback.isConnected && !state.playbackCommandPending,
                onClick = onRetryQueue,
            )
        }
        state.listeningIssue?.let { BodyText(stringResource(listeningIssueText(it)), isError = true) }
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
    ProbeButton(
        document.displayName.ifBlank { stringResource(R.string.probe_unnamed_audio) },
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
private fun ProbeButton(
    label: String,
    enabled: Boolean = true,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    WatchActionRow(label, enabled, primary, onClick = onClick)
}

private fun pageTitle(page: ProbePage): Int = when (page) {
    ProbePage.HOME -> R.string.app_name
    ProbePage.MEDIA -> R.string.probe_media_title
    ProbePage.TREE -> R.string.probe_tree_title
    ProbePage.INPUT -> R.string.probe_input
    ProbePage.PLAYBACK -> R.string.probe_playback
    ProbePage.DEVICE -> R.string.probe_device
    ProbePage.SETTINGS -> R.string.playback_settings
    ProbePage.QUEUE -> R.string.playback_queue
    ProbePage.LIBRARY -> R.string.library_title
    ProbePage.PLAYLISTS -> R.string.playlist_heading
}

private fun queueIssueText(issue: QueuePlaybackIssue): Int = when (issue) {
    QueuePlaybackIssue.BUSY -> R.string.playback_queue_busy
    QueuePlaybackIssue.CONFLICT -> R.string.playback_queue_conflict
    QueuePlaybackIssue.VIEW_EXPIRED -> R.string.library_view_expired
    QueuePlaybackIssue.SOURCE_CHANGED -> R.string.library_source_changed
    QueuePlaybackIssue.CONTENT_UNKNOWN -> R.string.library_content_unknown
    QueuePlaybackIssue.NOT_READY -> R.string.playback_queue_not_ready
    QueuePlaybackIssue.CLOSED -> R.string.playback_queue_closed
    QueuePlaybackIssue.STORAGE_FULL -> R.string.playback_storage_full
    QueuePlaybackIssue.STORAGE_UNAVAILABLE -> R.string.playback_storage_unavailable
    QueuePlaybackIssue.RECOVERY_REQUIRED -> R.string.playback_recovery_required
    QueuePlaybackIssue.SOURCE_UNSUPPORTED -> R.string.playback_source_unsupported
    QueuePlaybackIssue.LOCATION_MISSING -> R.string.playback_location_missing
    QueuePlaybackIssue.PERMISSION_DENIED -> R.string.playback_permission_denied
    QueuePlaybackIssue.FILE_MISSING -> R.string.playback_file_missing
    QueuePlaybackIssue.SOURCE_DAMAGED -> R.string.playback_source_damaged
    QueuePlaybackIssue.SOURCE_UNAVAILABLE -> R.string.playback_source_unavailable
    QueuePlaybackIssue.OUTPUT_ERROR -> R.string.playback_output_error
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
