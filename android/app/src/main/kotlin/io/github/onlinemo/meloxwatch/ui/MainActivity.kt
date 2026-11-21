package io.github.onlinemo.meloxwatch.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
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
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsEncodingChoice
import io.github.onlinemo.meloxwatch.data.themes.BackgroundRoundClip
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
import io.github.onlinemo.meloxwatch.playback.input.SeekGestureMode
import io.github.onlinemo.meloxwatch.playback.input.SeekGestureSurface
import io.github.onlinemo.meloxwatch.playback.route.ServiceMediaVolumeIssue
import io.github.onlinemo.meloxwatch.ui.playback.MediaVolumeClientPhase
import io.github.onlinemo.meloxwatch.ui.playback.mediaVolumeSliderFraction
import io.github.onlinemo.meloxwatch.ui.playback.PlaybackProgressInputIssue
import io.github.onlinemo.meloxwatch.ui.playback.PlaybackProgressInputPhase
import io.github.onlinemo.meloxwatch.ui.playback.SleepTimerControls
import io.github.onlinemo.meloxwatch.ui.playback.SleepTimerSummaryPreferences
import io.github.onlinemo.meloxwatch.ui.preferences.PreferencesControls
import io.github.onlinemo.meloxwatch.ui.preferences.PreferencesViewModel
import io.github.onlinemo.meloxwatch.ui.queue.QueueViewModel
import io.github.onlinemo.meloxwatch.ui.queue.QueueEditAction
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
import io.github.onlinemo.meloxwatch.ui.watch.WatchProgressBar
import io.github.onlinemo.meloxwatch.ui.watch.WatchWindowRuntime
import io.github.onlinemo.meloxwatch.ui.themes.AppearanceViewModel
import io.github.onlinemo.meloxwatch.ui.themes.LocalWatchAppearance
import io.github.onlinemo.meloxwatch.ui.themes.LocalWatchThemeColors
import io.github.onlinemo.meloxwatch.ui.themes.LocalWatchThemeFonts
import io.github.onlinemo.meloxwatch.ui.themes.PlaybackBackgroundViewModel
import io.github.onlinemo.meloxwatch.ui.themes.PlaybackBackgroundSurface
import io.github.onlinemo.meloxwatch.ui.themes.WatchAppearanceSettings
import io.github.onlinemo.meloxwatch.ui.themes.WatchThemeScope
import io.github.onlinemo.meloxwatch.ui.fonts.ProbeFontPickerViewModel
import io.github.onlinemo.meloxwatch.ui.fonts.WatchSystemFontControls
import io.github.onlinemo.meloxwatch.ui.fonts.rememberProbeFontPickerLauncher
import io.github.onlinemo.meloxwatch.ui.lyrics.entry.LyricsEntryViewModel
import io.github.onlinemo.meloxwatch.ui.lyrics.entry.lyricsEntryViewModelFactory
import io.github.onlinemo.meloxwatch.ui.lyrics.options.LyricsDisplayEditor
import io.github.onlinemo.meloxwatch.ui.watch.WatchLyricsAssociationConfirmation
import io.github.onlinemo.meloxwatch.ui.watch.WatchLyricsDisplaySettings
import io.github.onlinemo.meloxwatch.ui.watch.WatchLyricsOffsetSettings
import io.github.onlinemo.meloxwatch.ui.watch.WatchLyricsOptions
import io.github.onlinemo.meloxwatch.ui.watch.compactPlaybackTitle
import io.github.onlinemo.meloxwatch.ui.watch.lyricsDisplayOptions
import io.github.onlinemo.meloxwatch.ui.watch.watchSpacing
import io.github.onlinemo.meloxwatch.ui.watch.rememberRotaryAxisSteps
import io.github.onlinemo.meloxwatch.ui.watch.rememberWatchRotaryScroll
import io.github.onlinemo.meloxwatch.ui.watch.watchRotaryInput
import java.net.URI
import java.net.URISyntaxException
import io.github.onlinemo.meloxwatch.ui.transfer.WatchTransferViewModel
import io.github.onlinemo.meloxwatch.ui.transfer.watchTransferContent
import io.github.onlinemo.meloxwatch.data.online.OnlineNetworkMonitor
import io.github.onlinemo.meloxwatch.playback.OnlineQueueAction
import io.github.onlinemo.meloxwatch.ui.online.OnlineViewModel
import io.github.onlinemo.meloxwatch.ui.meting.MetingSettingsViewModel
import io.github.onlinemo.meloxwatch.ui.watch.WatchMetingSettings
import io.github.onlinemo.meloxwatch.ui.online.onlineContent
import io.github.onlinemo.meloxwatch.ui.online.OnlineNowPlayingArtwork
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var probeViewModel: ProbeViewModel
    private lateinit var preferencesViewModel: PreferencesViewModel
    private lateinit var queueViewModel: QueueViewModel
    private lateinit var libraryViewModel: LibraryViewModel
    private lateinit var sourcesViewModel: SourcesViewModel
    private lateinit var listeningViewModel: ListeningViewModel
    private lateinit var playlistsViewModel: PlaylistsViewModel
    private lateinit var playlistEntryViewModel: PlaylistEntryViewModel
    private lateinit var appearanceViewModel: AppearanceViewModel
    private lateinit var transferViewModel: WatchTransferViewModel
    private lateinit var onlineViewModel: OnlineViewModel
    private lateinit var metingSettingsViewModel: MetingSettingsViewModel
    private lateinit var playbackBackgroundViewModel: PlaybackBackgroundViewModel
    private lateinit var lyricsEntryViewModel: LyricsEntryViewModel
    private lateinit var fontPickerViewModel: ProbeFontPickerViewModel
    private lateinit var lyricsPickerViewModel: ProbeLyricsPickerViewModel
    private lateinit var appearancePickerViewModel: ProbeAppearancePickerViewModel
    private lateinit var lyricsDisplayViewModel: ProbeLyricsDisplayViewModel
    private var watchWindowRuntime: WatchWindowRuntime? = null
    private var pageRuntime: ProbePageRuntime? = null
    private var uiPresentationBinding: ProbeUiPresentation.Binding? = null
    private var transferPresentationBinding: AutoCloseable? = null
    private var transferForeground = false
    private var onlineForeground = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        probeViewModel = ViewModelProvider(this)[ProbeViewModel::class.java]
        preferencesViewModel = ViewModelProvider(this)[PreferencesViewModel::class.java]
        queueViewModel = ViewModelProvider(this)[QueueViewModel::class.java]
        libraryViewModel = ViewModelProvider(this)[LibraryViewModel::class.java]
        sourcesViewModel = ViewModelProvider(this)[SourcesViewModel::class.java]
        listeningViewModel = ViewModelProvider(this)[ListeningViewModel::class.java]
        val app = application as MeloXApplication
        val playbackModel = probeViewModel
        onlineViewModel = ViewModelProvider(this, viewModelFactory {
            initializer {
                OnlineViewModel(app::onlineCatalog, app.queues::snapshot, app.onlineQueues, playbackModel::submitOnlineQueue,
                    onAccepted = { action -> if (action != OnlineQueueAction.INSERT_NEXT) playbackModel.showPage(ProbePage.PLAYBACK) },
                    networkFactory = { OnlineNetworkMonitor(app) })
            }
        })[OnlineViewModel::class.java]
        metingSettingsViewModel = ViewModelProvider(this, viewModelFactory {
            initializer { createMetingSettingsModel(app, playbackModel) }
        })[MetingSettingsViewModel::class.java]
        fontPickerViewModel = ViewModelProvider(this)[ProbeFontPickerViewModel::class.java]
        lyricsPickerViewModel = ViewModelProvider(this)[ProbeLyricsPickerViewModel::class.java]
        appearancePickerViewModel = ViewModelProvider(this)[ProbeAppearancePickerViewModel::class.java]
        lyricsDisplayViewModel = ViewModelProvider(this)[ProbeLyricsDisplayViewModel::class.java]
        appearanceViewModel = ViewModelProvider(this, viewModelFactory {
            initializer {
                AppearanceViewModel(app.appearancePreferences, app.transferredThemes, app.playbackBackgrounds,
                    app.localProviderIo, createSavedStateHandle(), receivedThemes = app.transferredThemes)
            }
        })[AppearanceViewModel::class.java]
        transferViewModel = ViewModelProvider(this, viewModelFactory {
            initializer { WatchTransferViewModel(app.transferReceiver, app.transferredThemes) }
        })[WatchTransferViewModel::class.java]
        playbackBackgroundViewModel = ViewModelProvider(this, viewModelFactory {
            initializer {
                PlaybackBackgroundViewModel(app.library::resolveLocalRead, app.artworks,
                    app.appliedThemeResources, app.playbackBackgrounds, app.localProviderIo, app.themeBitmaps)
            }
        })[PlaybackBackgroundViewModel::class.java]
        lyricsEntryViewModel = ViewModelProvider(this, lyricsEntryViewModelFactory { savedState ->
            LyricsEntryViewModel(app.queues, app.lyricsContexts::resolve, app::validateLyricsContext,
                app.lyricsTracks, app.lyricsDocuments, app.lyricsAuthority, app.lyricsLoader::load,
                app.lyricsLoader::validate, probeViewModel.lyricsPlayback,
                libraryChanges = app.library.observeOverview().map { it.revision }
                    .distinctUntilChanged().drop(1).map { Unit }, savedStateHandle = savedState,
                memoryOwner = app.lyricsMemory, onlineLyrics = app.onlineLyrics)
        })[LyricsEntryViewModel::class.java]
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
        var exactCallbacks: ProbeActivityUiCallbacks? = null
        val exactWindow = WatchWindowRuntime(this) {
            exactCallbacks?.onWindowVisibilityChanged()
            transferViewModel.visibilityChanged()
            updateOnlineVisibility()
        }
        watchWindowRuntime = exactWindow
        val exactRuntime = ProbePageRuntime(
            uiState = { probeViewModel.uiState.value },
            window = exactWindow,
            activityPresentation = { uiPresentationBinding },
            appearance = appearanceViewModel,
            fonts = app.lyricsFontRuntime,
            background = playbackBackgroundViewModel,
        )
        pageRuntime = exactRuntime
        val callbacks = ProbeActivityUiCallbacks(exactRuntime, { uiPresentationBinding },
            lyricsEntryViewModel, fontPickerViewModel, lyricsDisplayViewModel.events)
        exactCallbacks = callbacks
        // bind invokes its initial callback before returning; attach only after filling the cell.
        val exactActivity = probeViewModel.bindUiPresentation(
            update = { state ->
                try { callbacks.onUiStateChanged(state) }
                finally {
                    transferViewModel.visibilityChanged()
                    updateOnlineVisibility()
                }
            },
            retire = {
                try { callbacks.close() }
                finally { transferPresentationBinding?.close() }
            },
        )
        uiPresentationBinding = exactActivity
        transferPresentationBinding = transferViewModel.bindPresentation {
            transferForeground && exactActivity.isCurrent && exactWindow.visibility.value.visible &&
                probeViewModel.uiState.value.page == ProbePage.TRANSFER
        }
        // The initial bind callback ran before this cell existed; record its real retained route now.
        callbacks.noteActualRoute(probeViewModel.uiState.value)
        updateOnlineVisibility()
        exactRuntime.attachActivity(exactActivity)
        if (savedInstanceState == null) acceptLaunchIntent(intent)
        setContent {
            val appearanceState by appearanceViewModel.state.collectAsStateWithLifecycle()
            val displayEditor = lyricsDisplayViewModel.editor
            LaunchedEffect(exactRuntime, appearanceState.appearance) { exactRuntime.onAppearanceChanged() }
            WatchThemeScope(appearanceState.theme.effectiveManifest.palette, appearanceState.appearance.effective) {
                ProbeTheme {
                    ProbeApp(probeViewModel, preferencesViewModel, queueViewModel,
                        libraryViewModel, sourcesViewModel, listeningViewModel, playlistsViewModel,
                        playlistEntryViewModel, appearanceViewModel, transferViewModel, onlineViewModel, metingSettingsViewModel, playbackBackgroundViewModel,
                        lyricsEntryViewModel, displayEditor, lyricsDisplayViewModel, fontPickerViewModel, lyricsPickerViewModel,
                        appearancePickerViewModel, exactRuntime, exactWindow, exactActivity, this)
                }
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
        if (uiPresentationBinding?.isCurrent == true) {
            transferForeground = true
            onlineForeground = true
            transferViewModel.visibilityChanged()
            updateOnlineVisibility()
            (application as MeloXApplication).onUiForeground()
            probeViewModel.onForeground()
        }
    }

    override fun onStop() {
        if (uiPresentationBinding?.isCurrent == true) {
            transferForeground = false
            onlineForeground = false
            transferViewModel.visibilityChanged()
            updateOnlineVisibility()
            probeViewModel.onBackground()
            sourcesViewModel.onBackground()
            (application as MeloXApplication).onUiBackground()
        }
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        watchWindowRuntime?.windowFocusChanged(hasFocus)
        if (::probeViewModel.isInitialized && uiPresentationBinding?.isCurrent == true) {
            probeViewModel.setWindowFocused(hasFocus)
            updateOnlineVisibility()
        }
    }

    override fun onDestroy() {
        if (uiPresentationBinding?.isCurrent == true && ::onlineViewModel.isInitialized) onlineViewModel.setVisible(false)
        if (uiPresentationBinding?.isCurrent == true && ::metingSettingsViewModel.isInitialized) metingSettingsViewModel.setVisible(false)
        try {
            transferPresentationBinding?.close()
            transferPresentationBinding = null
            watchWindowRuntime?.close()
        } finally {
            try {
                uiPresentationBinding?.close()
            } finally {
                pageRuntime?.close()
                super.onDestroy()
            }
        }
    }

    private fun updateOnlineVisibility() {
        if (!::onlineViewModel.isInitialized || uiPresentationBinding?.isCurrent != true) return
        val visible = onlineForeground && watchWindowRuntime?.visibility?.value?.visible == true
        val page = probeViewModel.uiState.value.page
        onlineViewModel.setVisible(visible && page == ProbePage.ONLINE)
        if (::metingSettingsViewModel.isInitialized) metingSettingsViewModel.setVisible(visible && page == ProbePage.METING)
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

private val Background: Color
    @Composable get() = LocalWatchThemeColors.current.background
private val Surface: Color
    @Composable get() = LocalWatchThemeColors.current.surface
private val PrimaryText: Color
    @Composable get() = LocalWatchThemeColors.current.text
private val SecondaryText: Color
    @Composable get() = LocalWatchThemeColors.current.secondary
private val Accent: Color
    @Composable get() = LocalWatchThemeColors.current.accent
private val OnAccent: Color
    @Composable get() = LocalWatchThemeColors.current.background

@Composable
internal fun ProbeTheme(content: @Composable () -> Unit) {
    val colors = LocalWatchThemeColors.current
    val fontFamily = LocalWatchThemeFonts.current.originalFamily
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = OnAccent,
            background = Background,
            onBackground = PrimaryText,
            surface = Surface,
            surfaceVariant = colors.raised,
            onSurface = PrimaryText,
            onSurfaceVariant = SecondaryText,
            error = colors.error,
        ),
        typography = Typography(
            titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = fontFamily, fontSynthesis = FontSynthesis.None),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontFamily = fontFamily, fontSynthesis = FontSynthesis.None),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontFamily = fontFamily, fontSynthesis = FontSynthesis.None),
            labelLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium,
                fontFamily = fontFamily, fontSynthesis = FontSynthesis.None),
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
    appearanceViewModel: AppearanceViewModel,
    transferViewModel: WatchTransferViewModel,
    onlineViewModel: OnlineViewModel,
    metingSettingsViewModel: MetingSettingsViewModel,
    playbackBackgroundViewModel: PlaybackBackgroundViewModel,
    lyricsEntryViewModel: LyricsEntryViewModel,
    lyricsDisplayEditor: LyricsDisplayEditor,
    lyricsDisplayViewModel: ProbeLyricsDisplayViewModel,
    fontPickerViewModel: ProbeFontPickerViewModel,
    lyricsPickerViewModel: ProbeLyricsPickerViewModel,
    appearancePickerViewModel: ProbeAppearancePickerViewModel,
    pageRuntime: ProbePageRuntime,
    watchWindowRuntime: WatchWindowRuntime,
    activityBinding: ProbeUiPresentation.Binding,
    activity: MainActivity,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val app = activity.application as MeloXApplication
    val windowVisibility by watchWindowRuntime.visibility.collectAsStateWithLifecycle()
    val appearance by appearanceViewModel.state.collectAsStateWithLifecycle()
    val transfer by transferViewModel.state.collectAsStateWithLifecycle()
    val online by onlineViewModel.state.collectAsStateWithLifecycle()
    val meting by metingSettingsViewModel.state.collectAsStateWithLifecycle()
    val displayState by lyricsDisplayEditor.state.collectAsStateWithLifecycle()
    val displaySettings by lyricsDisplayEditor.settings.collectAsStateWithLifecycle()
    val entryState by lyricsEntryViewModel.uiState.collectAsStateWithLifecycle()
    // System result registrations remain mounted while the Window/page resource owners retire.
    val launchFontPicker = rememberProbeFontPickerLauncher(fontPickerViewModel)
    val launchLyricsPicker = rememberProbeLyricsPickerLauncher(lyricsPickerViewModel, lyricsEntryViewModel)
    val launchAppearancePicker = rememberProbeAppearancePickerLauncher(appearancePickerViewModel, appearanceViewModel)
    val physicalCircle = physicalRoundClipInWindow(activity)
    val presented = activityBinding.isCurrent && windowVisibility.visible
    val mainProgress by viewModel.playbackInput.mainState.collectAsStateWithLifecycle()
    val independentProgress by viewModel.playbackInput.independentState.collectAsStateWithLifecycle()
    val rotaryPending by viewModel.playbackInput.rotaryPending.collectAsStateWithLifecycle()
    val inputBusy = rotaryPending || mainProgress.phase != PlaybackProgressInputPhase.IDLE ||
        independentProgress.phase != PlaybackProgressInputPhase.IDLE
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
        if (!activityBinding.isCurrent) return@LaunchedEffect
        if (state.page in setOf(ProbePage.SETTINGS, ProbePage.SLEEP_TIMER_NOTIFICATIONS)) preferencesViewModel.enterEditor()
        else if (preferences.isEditing) preferencesViewModel.clearDraft()
    }
    LifecycleStartEffect(state.page) {
        val queueVisible = state.page == ProbePage.QUEUE
        val libraryVisible = state.page == ProbePage.LIBRARY
        if (activityBinding.isCurrent && queueVisible) queueViewModel.onVisible()
        if (activityBinding.isCurrent && libraryVisible) libraryViewModel.onVisible()
        onStopOrDispose {
            if (activityBinding.isCurrent && queueVisible) queueViewModel.onHidden()
            if (activityBinding.isCurrent && libraryVisible) libraryViewModel.onHidden()
        }
    }
    LifecycleStartEffect(playlistsViewModel, state.page) {
        val visible = state.page == ProbePage.PLAYLISTS
        if (activityBinding.isCurrent && visible) playlistsViewModel.onVisible()
        onStopOrDispose {
            if (activityBinding.isCurrent && visible) playlistsViewModel.onHidden()
        }
    }
    LifecycleStartEffect(state.page) {
        if (activityBinding.isCurrent) playlistEntryViewModel.onVisible(when (state.page) {
            ProbePage.PLAYLISTS -> PlaylistEntrySurface.PLAYLISTS
            ProbePage.LIBRARY -> PlaylistEntrySurface.LIBRARY
            ProbePage.PLAYBACK -> PlaylistEntrySurface.PLAYBACK
            else -> PlaylistEntrySurface.OTHER
        })
        onStopOrDispose {
            if (activityBinding.isCurrent) playlistEntryViewModel.onHidden()
        }
    }
    LaunchedEffect(state.page, library.screen, library.detail) {
        if (!activityBinding.isCurrent) return@LaunchedEffect
        playlistEntryViewModel.setLibraryFavorite(library.detail?.takeIf {
            state.page == ProbePage.LIBRARY && library.screen == LibraryScreen.DETAIL
        }?.playlistReference())
    }
    LaunchedEffect(state.playback.mediaId) {
        if (activityBinding.isCurrent) playlistEntryViewModel.setPlayingEntry(state.playback.mediaId)
    }
    LifecycleStartEffect(state.page, library.screen) {
        val sourcesVisible = state.page == ProbePage.LIBRARY && library.screen == LibraryScreen.SOURCES
        val listeningVisible = state.page == ProbePage.LIBRARY &&
            library.screen in setOf(LibraryScreen.HISTORY, LibraryScreen.STATISTICS)
        if (activityBinding.isCurrent && sourcesVisible) sourcesViewModel.onVisible()
        if (activityBinding.isCurrent && listeningVisible) listeningViewModel.onVisible(library.screen)
        onStopOrDispose {
            if (activityBinding.isCurrent && sourcesVisible) sourcesViewModel.onHidden()
            if (activityBinding.isCurrent && listeningVisible) listeningViewModel.onHidden()
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
        if (state.page in setOf(ProbePage.SETTINGS, ProbePage.SLEEP_TIMER_NOTIFICATIONS)) preferencesViewModel.clearDraft()
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
            state.page in ProbeLyricsEntryRoutes && entryState.saving -> Unit
            state.page == ProbePage.LYRICS_OFFSET -> {
                lyricsEntryViewModel.cancelOffset()
                viewModel.showPage(ProbePage.LYRICS_OPTIONS)
            }
            state.page == ProbePage.ONLINE && onlineViewModel.back() -> Unit
            state.page == ProbePage.LYRICS_ASSOCIATION -> {
                entryState.associationPreview?.let { lyricsEntryViewModel.cancelAssociation(it.previewId) }
                viewModel.showPage(ProbePage.LYRICS_OPTIONS)
            }
            state.page == ProbePage.LYRICS_OPTIONS -> viewModel.showPage(ProbePage.LYRICS)
            state.page == ProbePage.LYRICS -> viewModel.showPage(ProbePage.PLAYBACK)
            state.page == ProbePage.LYRICS_SETTINGS && displayState.working -> Unit
            state.page == ProbePage.LYRICS_SETTINGS -> {
                lyricsDisplayEditor.cancel()
                viewModel.showPage(ProbePage.LYRICS_OPTIONS)
            }
            state.page == ProbePage.LYRICS_FONTS -> viewModel.showPage(ProbePage.LYRICS_OPTIONS)
            state.page == ProbePage.APPEARANCE && appearance.busy -> Unit
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
            state.page == ProbePage.SLEEP_TIMER_NOTIFICATIONS -> {
                preferencesViewModel.clearDraft()
                viewModel.showPage(ProbePage.SLEEP_TIMER)
            }
            state.page in setOf(ProbePage.SEEK, ProbePage.VOLUME, ProbePage.SLEEP_TIMER) -> viewModel.showPage(ProbePage.PLAYBACK)
            state.page == ProbePage.LIBRARY && libraryViewModel.back() -> Unit
            else -> returnHome()
        }
    }
    BackHandler(enabled = state.page != ProbePage.HOME, onBack = back)
    val renderPage: @Composable (ProbePage, Boolean, ProbePageRuntime.SelectionBinding?) -> Unit = { page, active, selection ->
        val exactPage = rememberProbePresentedPage(pageRuntime, page.presentationKind(), selection, active && presented)
        val visiblePage = exactPage?.visibleNow == true
        val entryPresentation = rememberProbeLyricsEntry(exactPage, page, lyricsEntryViewModel, viewModel)
        savedPages.SaveableStateProvider(page.name) {
            val queuePanels = rememberSaveableStateHolder()
            var visibleKeys by remember(page) { mutableStateOf<List<Any>>(emptyList()) }
            val queueRowsIdentity = queue.rowsIdentity
            val recordVisibleKeys = remember(exactPage, page, libraryViewModel, queueViewModel, queueRowsIdentity) {
                { keys: List<Any> ->
                    if (exactPage?.visibleNow == true) {
                        visibleKeys = keys
                        if (page == ProbePage.LIBRARY) libraryViewModel.setVisibleItemKeys(keys)
                        if (page == ProbePage.QUEUE) queueViewModel.setVisibleItemKeys(queueRowsIdentity, keys)
                    }
                }
            }
            LaunchedEffect(exactPage, page, entryState.associationPreview?.previewId) {
                if (page == ProbePage.LYRICS_OPTIONS && exactPage?.visibleNow == true &&
                    entryState.associationPreview != null) viewModel.showPage(ProbePage.LYRICS_ASSOCIATION)
            }
            val resetScroll = when (page) {
                ProbePage.QUEUE -> {
                    val draft = queue.editor
                    val generation = queue.snapshot?.generationId
                    val lane = if (draft == null || draft.action == QueueEditAction.CHOOSE_ENTRY) generation
                        else "$generation:${draft.action}:${draft.operationId}"
                    lane to queue.pageStart
                }
                ProbePage.LIBRARY -> "${library.screen}:${library.page?.view?.viewId}:${library.artworkRefreshPreview?.refreshId}" to library.pageStart
                ProbePage.ONLINE -> "${online.screen}:${online.snapshot?.id}" to online.pageStart
                ProbePage.PLAYLISTS -> "${playlists.screen}:${playlists.view?.viewId}:${playlists.importDraft?.preview?.previewId}:" +
                    "${playlistEntry.screen}:${playlistEntry.folder?.nodeId}:${playlistEntry.replacements?.nextCursor}" to playlists.pageStart
                else -> null
            }
            val renderContent: @Composable () -> Unit = {
            if (page == ProbePage.LYRICS) {
                ProbeLyricsScreen(app, exactPage, entryPresentation, lyricsEntryViewModel, state, screenShape,
                    presented = active && presented,
                    onBack = { viewModel.showPage(ProbePage.PLAYBACK) },
                    onOptions = { viewModel.showPage(ProbePage.LYRICS_OPTIONS) },
                    confirmation = { play, cancel ->
                        ProbePageContent(screenShape, null, visible = visiblePage, ownerKey = exactPage?.identity,
                            physicalCircle = physicalCircle) {
                            item { ProbeLyricsPlayControls(play, lyricsEntryViewModel, cancel) }
                        }
                    })
            } else if (page == ProbePage.PLAYBACK) {
                PlaybackBackgroundSurface(playbackBackgroundViewModel, appearance.theme, appearance.background.effective,
                    appearance.customImageKey(useDraft = false), playlistEntry.playingTrack,
                    preferences.effective.artworkReadPath, library.artworkRevision, exactPage, physicalCircle) {
                NowPlayingContent(screenShape, state, viewModel, playlistEntry, playlistEntryViewModel,
                    preferences.effective.artworkReadPath, library.artworkRevision, visiblePage,
                    onMenu = returnHome, openDocument = openDocument,
                    onAddPlaying = { playlistEntryViewModel.addPlaying(showAddTargets) }, onPlaylists = openPlaylists)
                }
            } else {
                val listContent: @Composable () -> Unit = {
                    ProbePageContent(screenShape, resetScroll, visible = visiblePage, ownerKey = page,
                        physicalCircle = physicalCircle,
                        onInputFocus = { within, direct ->
                            if (page in setOf(ProbePage.SEEK, ProbePage.VOLUME)) viewModel.setPlaybackInputFocused(page, within, direct)
                        },
                        onVisibleKeys = recordVisibleKeys) {
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
                            ProbePage.ONLINE -> onlineContent(online, onlineViewModel, visiblePage, visibleKeys = visibleKeys,
                                onMetingSettings = { viewModel.showPage(ProbePage.METING) })
                            ProbePage.METING -> item(key = "meting:settings") {
                                WatchMetingSettings(meting, visiblePage, metingSettingsViewModel, onBack = back)
                            }
                            ProbePage.MEDIA -> mediaContent(state, viewModel, openMedia, openSettings)
                            ProbePage.TREE -> treeContent(state, viewModel, openTree)
                            ProbePage.INPUT -> item { InputCheck() }
                            ProbePage.PLAYBACK -> Unit // Dedicated viewport above; business actions keep the same owners.
                            ProbePage.LYRICS -> Unit
                            ProbePage.LYRICS_OPTIONS -> item {
                                var encodingName by rememberSaveable { mutableStateOf(LyricsEncodingChoice.AUTO_BOM_OR_UTF8.name) }
                                val encoding = LyricsEncodingChoice.entries.firstOrNull { it.name == encodingName }
                                    ?: LyricsEncodingChoice.AUTO_BOM_OR_UTF8
                                WatchSystemFontControls {
                                    WatchLyricsOptions(entryState, encoding, visiblePage,
                                        onEncoding = { encodingName = it.name },
                                        onChoose = { purpose, choice ->
                                            if (exactPage?.visibleNow == true) {
                                                lyricsEntryViewModel.requestDocument(purpose, choice)?.let { id ->
                                                    lyricsEntryViewModel.uiState.value.documentRequest?.takeIf {
                                                        it.requestId == id
                                                    }?.let(launchLyricsPicker)
                                                }
                                            }
                                        },
                                        onClear = { if (exactPage?.visibleNow == true) lyricsEntryViewModel.clearAssociation(it) },
                                        onOffset = {
                                            if (exactPage?.visibleNow == true && lyricsEntryViewModel.beginOffset()) {
                                                viewModel.showPage(ProbePage.LYRICS_OFFSET)
                                            }
                                        },
                                        onDisplay = { viewModel.showPage(ProbePage.LYRICS_SETTINGS) },
                                        onFonts = { viewModel.showPage(ProbePage.LYRICS_FONTS) },
                                        onRetry = { if (exactPage?.visibleNow == true) lyricsEntryViewModel.retry() },
                                        onBack = back)
                                }
                            }
                            ProbePage.LYRICS_ASSOCIATION -> item {
                                WatchSystemFontControls {
                                    WatchLyricsAssociationConfirmation(entryState, visiblePage,
                                        onConfirm = { if (exactPage?.visibleNow == true) lyricsEntryViewModel.confirmAssociation(it) },
                                        onCancel = { id ->
                                            if (exactPage?.visibleNow == true) {
                                                lyricsEntryViewModel.cancelAssociation(id)
                                                viewModel.showPage(ProbePage.LYRICS_OPTIONS)
                                            }
                                        }, preview = { ProbeLyricsExcerpt(entryState) })
                                    if (entryState.associationPreview == null) {
                                        BodyText(stringResource(if (entryState.loading || entryState.saving) R.string.lyrics_settings_working
                                            else if (entryState.notice == io.github.onlinemo.meloxwatch.ui.lyrics.entry.LyricsEntryNotice.SAVED)
                                                R.string.lyrics_settings_saved else R.string.lyrics_entry_changed))
                                    }
                                }
                            }
                            ProbePage.LYRICS_OFFSET -> item {
                                WatchSystemFontControls {
                                    WatchLyricsOffsetSettings(entryState, visiblePage,
                                        onAdjust = { if (exactPage?.visibleNow == true) lyricsEntryViewModel.adjustOffset(it) },
                                        onZero = { if (exactPage?.visibleNow == true) lyricsEntryViewModel.resetOffset() },
                                        onSave = { if (exactPage?.visibleNow == true) lyricsEntryViewModel.saveOffset() },
                                        onReview = { if (exactPage?.visibleNow == true) lyricsEntryViewModel.reviewOffsetDraft() },
                                        onRetry = { if (exactPage?.visibleNow == true) lyricsEntryViewModel.retry() },
                                        onCancel = back, preview = { ProbeLyricsExcerpt(entryState) })
                                }
                            }
                            ProbePage.LYRICS_SETTINGS -> item {
                                val events = rememberCoroutineScope()
                                WatchSystemFontControls {
                                    WatchLyricsDisplaySettings(displayState, displaySettings, visiblePage,
                                        onBegin = { if (exactPage?.visibleNow == true) lyricsDisplayEditor.begin() },
                                        onPreview = { if (exactPage?.visibleNow == true) lyricsDisplayEditor.preview(it) },
                                        onDefaults = { if (exactPage?.visibleNow == true) lyricsDisplayEditor.previewDefaults() },
                                        onSave = { if (exactPage != null) events.launch { lyricsDisplayViewModel.save(exactPage) } },
                                        onReviewRetry = { if (exactPage?.visibleNow == true) lyricsDisplayEditor.reviewRetry() },
                                        onRetryRead = { if (exactPage?.visibleNow == true) lyricsDisplayEditor.retryRead() },
                                        onCancel = back, preview = { preview -> ProbeLyricsDisplayPreview(lyricsDisplayOptions(preview)) })
                                }
                            }
                            ProbePage.LYRICS_FONTS -> item {
                                if (exactPage != null) ProbeLyricsFontPage(app, exactPage, fontPickerViewModel, launchFontPicker,
                                    onBack = { viewModel.showPage(ProbePage.LYRICS_OPTIONS) })
                            }
                            ProbePage.APPEARANCE -> {
                                appearance.receivedTheme?.let { received ->
                                    item { BodyText(stringResource(R.string.transfer_received_theme, received.displayName)) }
                                    if (appearance.discardReceivedTheme?.operationId == received.operationId) {
                                        item { BodyText(stringResource(R.string.transfer_received_discard_confirm)) }
                                        item {
                                            ProbeButton(stringResource(R.string.transfer_received_discard_yes), enabled = visiblePage && appearance.editable) {
                                                if (exactPage?.visibleNow == true) appearanceViewModel.confirmDiscardReceivedTheme(received.operationId)
                                            }
                                        }
                                        item {
                                            ProbeButton(stringResource(R.string.transfer_received_discard_no), enabled = visiblePage && appearance.editable,
                                                onClick = { appearanceViewModel.cancelDiscardReceivedTheme() })
                                        }
                                    } else {
                                        item {
                                            ProbeButton(stringResource(R.string.transfer_received_preview), enabled = visiblePage && appearance.editable) {
                                                if (exactPage?.visibleNow == true) appearanceViewModel.previewReceivedTheme(received.operationId)
                                            }
                                        }
                                        item {
                                            ProbeButton(stringResource(R.string.transfer_received_discard), enabled = visiblePage && appearance.editable) {
                                                if (exactPage?.visibleNow == true) appearanceViewModel.requestDiscardReceivedTheme(received.operationId)
                                            }
                                        }
                                    }
                                }
                                item {
                                    WatchSystemFontControls {
                                        WatchAppearanceSettings(appearance, appearanceViewModel, visiblePage, launchAppearancePicker,
                                            onOpenScreenShape = { viewModel.showPage(ProbePage.SETTINGS) }, onBack = back,
                                            preview = {
                                                if (exactPage != null) ProbeCandidateThemeFontPreview(app, exactPage, appearance, appearanceViewModel)
                                            })
                                    }
                                }
                            }
                            ProbePage.TRANSFER -> watchTransferContent(transfer, transferViewModel, visiblePage,
                                onAppearance = { viewModel.showPage(ProbePage.APPEARANCE) })
                            ProbePage.DEVICE -> item { DeviceDetails() }
                            ProbePage.SEEK -> item { SeekControls(state, viewModel) }
                            ProbePage.VOLUME -> item { MediaVolumeControls(viewModel) }
                            ProbePage.SLEEP_TIMER -> item {
                                SleepTimerControls(state, viewModel) { viewModel.showPage(ProbePage.SLEEP_TIMER_NOTIFICATIONS) }
                            }
                            ProbePage.SLEEP_TIMER_NOTIFICATIONS -> item {
                                SleepTimerSummaryPreferences(preferencesViewModel) { viewModel.showPage(ProbePage.SLEEP_TIMER) }
                            }
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
                                artworkPath = preferences.effective.artworkReadPath,
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
                if (page == ProbePage.QUEUE) {
                    // 显式编辑页单独保存位置，返回浏览时保留原歌曲行锚点。
                    val draft = queue.editor
                    val lane = if (draft == null || draft.action == QueueEditAction.CHOOSE_ENTRY) "rows" else "editor"
                    queuePanels.SaveableStateProvider(lane, listContent)
                } else listContent()
            }
            }
            if (page in setOf(ProbePage.LYRICS, ProbePage.LYRICS_FONTS, ProbePage.APPEARANCE)) renderContent()
            else ProbeThemeUiFonts(app, exactPage, appearance.theme,
                probePageVisibleText(page, state, library, queue, playlists, playlistEntry, visibleKeys,
                    online = online, meting = meting), renderContent)
        }
    }
    if (state.page in setOf(ProbePage.QUEUE, ProbePage.PLAYBACK, ProbePage.LYRICS)) {
        var exactSelection by remember(pageRuntime) { mutableStateOf<ProbePageRuntime.SelectionBinding?>(null) }
        WatchPlaybackPages(state.page, !state.playbackCommandPending && !inputBusy && queue.editor == null,
            onPage = { page -> if (page != state.page) viewModel.showPage(page) }, presented = presented,
            presentationOwner = pageRuntime, bindPresentation = pageRuntime::bindSelection,
            onPresentationBound = { exactSelection = it },
            onPresentationRetired = { retired -> if (exactSelection === retired) exactSelection = null }) { page, active ->
            renderPage(page, active, exactSelection)
        }
    } else {
        val exactSelection = rememberProbeSingleSelection(pageRuntime, state.page, presented)
        renderPage(state.page, presented, exactSelection)
    }
}

@Composable
private fun ProbePageContent(
    screenShape: ScreenShape,
    resetScroll: Pair<String?, Long>?,
    visible: Boolean,
    ownerKey: Any?,
    physicalCircle: BackgroundRoundClip?,
    onInputFocus: (Boolean, Boolean) -> Unit = { _, _ -> },
    onVisibleKeys: ((List<Any>) -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val listState = rememberLazyListState()
    var appliedScrollSource by rememberSaveable { mutableStateOf(resetScroll?.first) }
    var appliedScrollPage by rememberSaveable { mutableStateOf(resetScroll?.second) }
    if (!visible) {
        Box(Modifier.fillMaxSize().clearAndSetSemantics { })
        return
    }
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val appearance = LocalWatchAppearance.current
    val fullWindowSize = LocalWindowInfo.current.containerSize
    val fullWindowHeight = with(density) { (physicalCircle?.let { it.radius * 2f }
        ?: fullWindowSize.height.toFloat()).toDp() }
    val scrollRotary = rememberWatchRotaryScroll(listState, visible)
    LaunchedEffect(onVisibleKeys, resetScroll) {
        if (onVisibleKeys != null) snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.key } }
            .distinctUntilChanged().collect(onVisibleKeys)
    }
    LaunchedEffect(resetScroll) {
        if (resetScroll != null &&
            (appliedScrollSource != resetScroll.first || appliedScrollPage != resetScroll.second)
        ) {
            listState.scrollToItem(0)
            appliedScrollSource = resetScroll.first
            appliedScrollPage = resetScroll.second
        }
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
        val baseWidth = if (protectsRoundBounds) maxWidth * 0.78f else maxWidth - watchSpacing(24.dp)
        val requestedWidth = baseWidth - appearance.horizontalMarginDp.dp * 2
        val minimumWidth = minOf(48.dp, maxWidth)
        val contentWidth = requestedWidth.coerceIn(minimumWidth, maxWidth)
        // 键盘只收缩可见滚动区域；顶部圆边避让仍以完整窗口计算。
        val roundInset = maxOf(fullWindowHeight, maxHeight) * 0.22f
        val verticalInset = (if (protectsRoundBounds) roundInset else watchSpacing(12.dp)) + appearance.verticalMarginDp.dp
        LazyColumn(
            modifier = Modifier.width(contentWidth).align(Alignment.Center)
                .watchRotaryInput(visible, ownerKey, onInputFocus, scrollRotary),
            state = listState,
            contentPadding = PaddingValues(top = verticalInset, bottom = verticalInset + watchSpacing(12.dp)),
            verticalArrangement = Arrangement.spacedBy(watchSpacing(10.dp)),
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
    item { ProbeButton(stringResource(R.string.online_title)) { viewModel.showPage(ProbePage.ONLINE) } }
    item { ProbeButton(stringResource(R.string.online_meting)) { viewModel.showPage(ProbePage.METING) } }
    item { ProbeButton(stringResource(R.string.playlist_heading)) { viewModel.showPage(ProbePage.PLAYLISTS) } }
    item { ProbeButton(stringResource(R.string.playback_queue), onClick = { viewModel.showPage(ProbePage.QUEUE) }) }
    item { ProbeButton(stringResource(R.string.playback_settings), onClick = { viewModel.showPage(ProbePage.SETTINGS) }) }
    item { ProbeButton(stringResource(R.string.appearance_title), onClick = { viewModel.showPage(ProbePage.APPEARANCE) }) }
    item { ProbeButton(stringResource(R.string.transfer_title), onClick = { viewModel.showPage(ProbePage.TRANSFER) }) }
    item { ProbeButton(stringResource(R.string.lyrics_settings), onClick = { viewModel.showPage(ProbePage.LYRICS_OPTIONS) }) }
    item { ProbeButton(stringResource(R.string.sleep_timer_title), onClick = { viewModel.showPage(ProbePage.SLEEP_TIMER) }) }
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
    val rotaryAxis = rememberRotaryAxisSteps()
    val track = entry.playingTrack
    val title = playback.title?.takeIf(String::isNotBlank) ?: track?.title ?: stringResource(R.string.probe_unnamed_audio)
    val artist = track?.metadata?.artist?.takeIf(String::isNotBlank) ?: playback.artist?.takeIf(String::isNotBlank)
        ?: stringResource(R.string.library_unknown_artist)
    val heroTitle = compactPlaybackTitle(title, LocalWatchAppearance.current.compactPlaybackTitle)
    WatchNowPlayingPage(screenShape, heroTitle, artist.takeIf { playback.hasMedia },
        stringResource(R.string.watch_open_menu), onMenu,
        artwork = { size ->
            if (track == null && playback.mediaId != null) OnlineNowPlayingArtwork(playback.mediaId, visible, size)
            else NowPlayingArtwork(track, artworkPath, artworkRevision, visible, size)
        },
        transport = { centerSize, horizontal -> WatchTransport(state, viewModel, centerSize, horizontal) },
        visible = visible,
        onInputFocus = { within, direct -> viewModel.setPlaybackInputFocused(ProbePage.PLAYBACK, within, direct) },
        onListPriority = { viewModel.setPlaybackListPriority(ProbePage.PLAYBACK, it) },
        onRotaryPlayback = { event -> rotaryAxis(event)?.let {
            viewModel.playbackInput.rotary(SystemClock.elapsedRealtime(), it)
        } ?: false }) {
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
        if (state.inputControls.showMainProgress) PlaybackProgressControl(state, viewModel, SeekGestureSurface.MAIN_PROGRESS)
        ProbeButton(stringResource(R.string.playback_seek_open)) { viewModel.showPage(ProbePage.SEEK) }
        ProbeButton(stringResource(R.string.input_media_volume_title)) { viewModel.showPage(ProbePage.VOLUME) }
        ProbeButton(stringResource(R.string.sleep_timer_title)) { viewModel.showPage(ProbePage.SLEEP_TIMER) }
        ProbeButton(stringResource(R.string.lyrics_settings)) { viewModel.showPage(ProbePage.LYRICS_OPTIONS) }
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
    } else TrackArtwork(track, path, revision = artworkRevision, size = size, visible = visible)
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
            enabled = playback.isConnected && playback.hasMedia && (!state.playbackCommandPending || playback.isPlaying),
            size = centerSize, emphasized = true) { if (playback.isPlaying) viewModel.pause() else viewModel.resumeCurrent() }
    }
    val previous: @Composable () -> Unit = {
        WatchCircleButton(previousLabel, WatchGlyph.PREVIOUS,
            enabled = playback.isConnected && playback.hasPrevious && !state.playbackCommandPending, onClick = viewModel::previous)
    }
    val next: @Composable () -> Unit = {
        WatchCircleButton(nextLabel, WatchGlyph.NEXT,
            enabled = playback.isConnected && playback.hasNext && !state.playbackCommandPending, onClick = viewModel::next)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = watchSpacing(8.dp)
        val sideControlsFitRow = maxWidth >= 96.dp + gap
        if (horizontal) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically) {
                previous()
                center()
                next()
            }
        } else {
            // Narrow windows keep full independent targets by placing the center above the two side controls.
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(gap)) {
                center()
                if (sideControlsFitRow) Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    previous()
                    next()
                } else Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    previous()
                    next()
                }
            }
        }
    }
}

@Composable
private fun SeekControls(state: ProbeUiState, viewModel: ProbeViewModel) {
    val main by viewModel.playbackInput.mainState.collectAsStateWithLifecycle()
    val independent by viewModel.playbackInput.independentState.collectAsStateWithLifecycle()
    val rotary by viewModel.playbackInput.rotaryPending.collectAsStateWithLifecycle()
    val surface = SeekGestureSurface.INDEPENDENT_CONTROLS
    val context = viewModel.playbackInput.progressContext(surface)
    val duration = state.playback.durationMs
    val enabled = context.foreground && context.ownerId != null && context.canSeek && !rotary &&
        main.phase == PlaybackProgressInputPhase.IDLE && independent.phase == PlaybackProgressInputPhase.IDLE &&
        duration != null && duration > 0L
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BodyText(stringResource(R.string.input_seek_scope))
        PlaybackProgressControl(state, viewModel, surface)
        ProbeButton(stringResource(R.string.probe_seek_back), enabled && state.playback.positionMs > 0L) {
            viewModel.playbackInput.seekBy(surface, -10_000L)
        }
        ProbeButton(stringResource(R.string.probe_seek_forward), enabled && state.playback.positionMs < (duration ?: 0L)) {
            viewModel.playbackInput.seekBy(surface, 10_000L)
        }
        ProbeButton(stringResource(R.string.playback_seek_start), enabled && state.playback.positionMs > 0L) {
            viewModel.playbackInput.setProgress(surface, 0.0)
        }
    }
}

@Composable
private fun PlaybackProgressControl(state: ProbeUiState, model: ProbeViewModel, surface: SeekGestureSurface) {
    val main by model.playbackInput.mainState.collectAsStateWithLifecycle()
    val independent by model.playbackInput.independentState.collectAsStateWithLifecycle()
    val rotary by model.playbackInput.rotaryPending.collectAsStateWithLifecycle()
    val progress = if (surface == SeekGestureSurface.MAIN_PROGRESS) main else independent
    val other = if (surface == SeekGestureSurface.MAIN_PROGRESS) independent else main
    val context = model.playbackInput.progressContext(surface)
    val duration = state.playback.durationMs?.takeIf { it > 0L }
    val submitting = progress.phase in setOf(PlaybackProgressInputPhase.COMMITTING, PlaybackProgressInputPhase.CANCELLING)
    val otherBusy = rotary || other.phase != PlaybackProgressInputPhase.IDLE
    val enabled = context.foreground && context.ownerId != null && context.canSeek && !submitting && !otherBusy && duration != null
    val preview = progress.previewPositionMs ?: duration?.let { total ->
        progress.previewFraction?.let { (it * total.toDouble()).toLong().coerceIn(0L, total) }
    }
    val fraction = duration?.let { total ->
        progress.previewFraction ?: ((preview ?: state.playback.positionMs).coerceIn(0L, total).toDouble() / total.toDouble())
    }
    val reason = stringResource(when {
        duration == null -> R.string.input_seek_unknown_duration
        submitting || otherBusy || state.playbackCommandPending -> R.string.input_seek_busy
        !context.foreground || context.ownerId == null && state.seekAuthority?.ownerId != null -> R.string.input_seek_unfocused
        else -> R.string.probe_seek_unavailable
    })
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BodyText(stringResource(R.string.probe_position, elapsedTime(state.playback.positionMs),
            duration?.let(::elapsedTime) ?: stringResource(R.string.probe_unknown_duration)))
        WatchProgressBar(fraction, context.settings.mode, enabled,
            context.ownerId to context.controlsStateKey, stringResource(R.string.input_seek_progress), reason,
            onBegin = { model.playbackInput.begin(surface) },
            onDrag = { id, value -> model.playbackInput.drag(surface, id, value) },
            onRelease = { model.playbackInput.release(surface, it) },
            onTap = { id, value -> model.playbackInput.tap(surface, id, value) },
            onCancel = { model.playbackInput.cancel(surface, it) },
            onSetProgress = { model.playbackInput.setProgress(surface, it) })
        if (preview != null) BodyText(stringResource(R.string.probe_seek_preview, elapsedTime(preview)))
        if (progress.phase == PlaybackProgressInputPhase.ACQUIRING) BodyText(stringResource(R.string.input_seek_acquiring))
        if (submitting || rotary) BodyText(stringResource(R.string.input_seek_busy))
        progress.issue?.takeIf { progress.issueOwnerId == context.ownerId }?.let {
            BodyText(stringResource(progressIssueText(it)), isError = true)
        }
        progress.gestureId?.takeIf { progress.phase in setOf(PlaybackProgressInputPhase.ACQUIRING, PlaybackProgressInputPhase.PREVIEW) }?.let { id ->
            ProbeButton(stringResource(R.string.probe_cancel)) { model.playbackInput.cancel(surface, id) }
        }
    }
}

@Composable
private fun MediaVolumeControls(model: ProbeViewModel) {
    val volume by model.mediaVolume.state.collectAsStateWithLifecycle()
    val maximum = volume.maximum
    val current = volume.current
    val fraction = mediaVolumeSliderFraction(volume)
    val absoluteEnabled = volume.absoluteAdjustable
    val relativeEnabled = volume.relativeAdjustable && !model.mediaVolume.busy
    val reason = stringResource(when {
        volume.issue == ServiceMediaVolumeIssue.UNAVAILABLE -> R.string.input_volume_unavailable
        volume.phase !in setOf(MediaVolumeClientPhase.IDLE, MediaVolumeClientPhase.PREVIEW) -> R.string.input_volume_busy
        volume.issue != null -> volumeIssueText(volume.issue)
        volume.snapshot?.values?.adjustable == true && volume.snapshot?.route?.known == false -> R.string.input_volume_route_unknown
        else -> R.string.input_volume_unavailable
    })
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BodyText(stringResource(R.string.input_volume_scope))
        if (current != null && maximum != null) BodyText(stringResource(R.string.input_volume_value, current, maximum), isPrimary = true)
        if (volume.muted) BodyText(stringResource(R.string.input_volume_muted))
        WatchProgressBar(fraction, SeekGestureMode.DRAG_TO_COMMIT, absoluteEnabled, volume.ownerId,
            stringResource(R.string.input_media_volume_title), reason,
            onBegin = model.mediaVolume::begin, onDrag = model.mediaVolume::drag,
            onRelease = model.mediaVolume::release, onTap = model.mediaVolume::tap,
            onCancel = model.mediaVolume::cancel, onSetProgress = model.mediaVolume::setProgress)
        volume.preview?.let { BodyText(stringResource(R.string.input_volume_preview, it)) }
        ProbeButton(stringResource(R.string.input_volume_less), relativeEnabled && current != null && current > volume.minimum) {
            model.mediaVolume.change(-1)
        }
        ProbeButton(stringResource(R.string.input_volume_more), relativeEnabled && current != null && maximum != null && current < maximum) {
            model.mediaVolume.change(1)
        }
        if (absoluteEnabled && volume.issue != null) BodyText(reason, isError = volume.issue != ServiceMediaVolumeIssue.AT_LIMIT)
        ProbeButton(stringResource(R.string.input_volume_refresh), onClick = model::refreshMediaVolume)
        volume.gestureId?.let { id -> ProbeButton(stringResource(R.string.probe_cancel)) { model.mediaVolume.cancel(id) } }
    }
}

private fun progressIssueText(issue: PlaybackProgressInputIssue): Int = when (issue) {
    PlaybackProgressInputIssue.BINDING_UNAVAILABLE -> R.string.input_seek_binding_changed
    PlaybackProgressInputIssue.INVALID_FRACTION -> R.string.input_seek_invalid_position
    PlaybackProgressInputIssue.SEEK_REJECTED, PlaybackProgressInputIssue.REQUEST_FAILED -> R.string.input_seek_unconfirmed
}

private fun volumeIssueText(issue: ServiceMediaVolumeIssue?): Int = when (issue) {
    ServiceMediaVolumeIssue.FIXED -> R.string.input_volume_fixed
    ServiceMediaVolumeIssue.REMOTE_OUTPUT -> R.string.input_volume_remote
    ServiceMediaVolumeIssue.OUTPUT_CHANGED -> R.string.input_volume_output_changed
    ServiceMediaVolumeIssue.SET_REJECTED -> R.string.input_volume_unconfirmed
    ServiceMediaVolumeIssue.AT_LIMIT -> R.string.input_volume_at_limit
    ServiceMediaVolumeIssue.UNAVAILABLE, null -> R.string.input_volume_unavailable
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
    ProbePage.ONLINE -> R.string.online_title
    ProbePage.METING -> R.string.meting_title
    ProbePage.MEDIA -> R.string.probe_media_title
    ProbePage.TREE -> R.string.probe_tree_title
    ProbePage.INPUT -> R.string.probe_input
    ProbePage.PLAYBACK -> R.string.probe_playback
    ProbePage.DEVICE -> R.string.probe_device
    ProbePage.SETTINGS -> R.string.playback_settings
    ProbePage.QUEUE -> R.string.playback_queue
    ProbePage.LIBRARY -> R.string.library_title
    ProbePage.PLAYLISTS -> R.string.playlist_heading
    ProbePage.SEEK -> R.string.playback_seek_open
    ProbePage.VOLUME -> R.string.input_media_volume_title
    ProbePage.SLEEP_TIMER -> R.string.sleep_timer_title
    ProbePage.SLEEP_TIMER_NOTIFICATIONS -> R.string.prefs_sleep_summary
    ProbePage.LYRICS -> R.string.lyrics_current_line
    ProbePage.LYRICS_OPTIONS -> R.string.lyrics_settings
    ProbePage.LYRICS_ASSOCIATION -> R.string.lyrics_association_preview
    ProbePage.LYRICS_OFFSET -> R.string.lyrics_offset_title
    ProbePage.LYRICS_SETTINGS -> R.string.lyrics_display_title
    ProbePage.LYRICS_FONTS -> R.string.lyrics_fonts_title
    ProbePage.APPEARANCE -> R.string.appearance_title
    ProbePage.TRANSFER -> R.string.transfer_title
}

/** Window metrics keep the physical circle fixed while IME/insets reduce the scrolling viewport. */
@Composable
private fun physicalRoundClipInWindow(activity: MainActivity): BackgroundRoundClip? {
    val configuration = LocalConfiguration.current
    val composedWindow = LocalWindowInfo.current.containerSize
    return remember(activity, configuration, composedWindow) {
        if (!configuration.isScreenRound) return@remember null
        try {
            val dimensions = if (Build.VERSION.SDK_INT >= 30) {
                val bounds = activity.windowManager.currentWindowMetrics.bounds
                Point(bounds.width(), bounds.height())
            } else {
                if (activity.isInMultiWindowMode) return@remember null
                @Suppress("DEPRECATION")
                Point().also { activity.windowManager.defaultDisplay.getSize(it) }
            }
            if (dimensions.x <= 0 || dimensions.y <= 0) null else BackgroundRoundClip(
                dimensions.x / 2f, dimensions.y / 2f, minOf(dimensions.x, dimensions.y) / 2f)
        } catch (_: RuntimeException) { null }
    }
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
    QueuePlaybackIssue.NETWORK_UNAVAILABLE -> R.string.online_network_unavailable
    QueuePlaybackIssue.NETWORK_TIMEOUT -> R.string.online_network_timeout
    QueuePlaybackIssue.NETWORK_CERTIFICATE -> R.string.online_certificate_error
    QueuePlaybackIssue.NETWORK_RATE_LIMITED -> R.string.online_rate_limited
    QueuePlaybackIssue.LOGIN_REQUIRED -> R.string.online_login_required
    QueuePlaybackIssue.SESSION_UNAVAILABLE -> R.string.online_session_unavailable
    QueuePlaybackIssue.LOCAL_ONLY_ONLINE_SKIPPED -> R.string.playback_local_only_skipped_online
    QueuePlaybackIssue.RESOURCE_UNAVAILABLE -> R.string.online_resource_unavailable
    QueuePlaybackIssue.RESOURCE_AUTH_REQUIRED -> R.string.online_resource_auth_required
    QueuePlaybackIssue.RESOURCE_INVALID -> R.string.online_resource_invalid
    QueuePlaybackIssue.RESOURCE_EXPIRED -> R.string.online_resource_expired
    QueuePlaybackIssue.RESOURCE_REDIRECT -> R.string.online_redirect_limit
    QueuePlaybackIssue.RESOURCE_RETRY_EXHAUSTED -> R.string.online_retry_limit
    QueuePlaybackIssue.STREAM_STALLED -> R.string.online_stream_stalled
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

internal fun elapsedTime(positionMs: Long): String {
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
