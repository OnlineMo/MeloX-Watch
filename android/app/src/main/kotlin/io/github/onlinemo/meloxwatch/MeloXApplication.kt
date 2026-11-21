package io.github.onlinemo.meloxwatch

import android.app.Application
import io.github.onlinemo.meloxwatch.data.online.OnlineCatalogFeatureOwner
import io.github.onlinemo.meloxwatch.data.online.OnlineQueueDirectory
import io.github.onlinemo.meloxwatch.data.network.BoundedHttpGetClient
import io.github.onlinemo.meloxwatch.data.meting.MetingRuntimeOwner
import io.github.onlinemo.meloxwatch.data.netease.OnlineAccountChanges
import io.github.onlinemo.meloxwatch.data.netease.OnlineMusicRuntime
import io.github.onlinemo.meloxwatch.data.netease.OnlineMusicRuntimeOwner
import io.github.onlinemo.meloxwatch.playback.resources.RuntimePlaybackResources
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.artwork.LocalArtworkRepository
import io.github.onlinemo.meloxwatch.data.fonts.AndroidLyricsFontFiles
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontSettingsStore
import io.github.onlinemo.meloxwatch.data.fonts.store.LyricsFontSettingsStoreFactory
import io.github.onlinemo.meloxwatch.data.imports.LocalImportManager
import io.github.onlinemo.meloxwatch.data.imports.AudioCopyQuota
import io.github.onlinemo.meloxwatch.data.transfer.ImportedAudioTransferBackend
import io.github.onlinemo.meloxwatch.data.transfer.LocalTransferContentValidator
import io.github.onlinemo.meloxwatch.data.transfer.LocalTransferQuotaAccounting
import io.github.onlinemo.meloxwatch.data.transfer.LocalTransferReceiver
import io.github.onlinemo.meloxwatch.data.transfer.LocalTransferServer
import io.github.onlinemo.meloxwatch.data.transfer.PlatformAudioTransferValidator
import io.github.onlinemo.meloxwatch.data.transfer.RoomCompanionTransferBackend
import io.github.onlinemo.meloxwatch.data.transfer.ThemeReceivingTransferBackend
import io.github.onlinemo.meloxwatch.data.transfer.TransferUploadStore
import io.github.onlinemo.meloxwatch.data.imports.ImportException
import io.github.onlinemo.meloxwatch.data.imports.ImportIssue
import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.library.LocalContentValidation
import io.github.onlinemo.meloxwatch.data.library.RoomImportLedger
import io.github.onlinemo.meloxwatch.data.library.RoomLocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.library.RoomFolderPlaylistController
import io.github.onlinemo.meloxwatch.data.library.RoomFolderPlaylistMaintainer
import io.github.onlinemo.meloxwatch.data.library.RoomKeywordPlaylistController
import io.github.onlinemo.meloxwatch.data.library.RoomPlaylistFences
import io.github.onlinemo.meloxwatch.data.library.RoomPlaylistQueueMapper
import io.github.onlinemo.meloxwatch.data.library.RoomPlaylistRecoveryController
import io.github.onlinemo.meloxwatch.data.listening.RoomListeningRepository
import io.github.onlinemo.meloxwatch.data.local.AndroidLocalSources
import io.github.onlinemo.meloxwatch.data.local.LocalMetadataReader
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsMemoryOwner
import io.github.onlinemo.meloxwatch.data.lyrics.online.OnlineLyricsFeatureOwner
import io.github.onlinemo.meloxwatch.data.lyrics.active.LocalLyricsContext
import io.github.onlinemo.meloxwatch.data.lyrics.active.LyricsDocumentCopies
import io.github.onlinemo.meloxwatch.data.lyrics.active.LyricsDocuments
import io.github.onlinemo.meloxwatch.data.lyrics.active.LyricsLoadAuthority
import io.github.onlinemo.meloxwatch.data.lyrics.active.RoomLyricsContextResolver
import io.github.onlinemo.meloxwatch.data.lyrics.active.SavedLyricsDocumentResolver
import io.github.onlinemo.meloxwatch.data.lyrics.load.LocalLyricsLoader
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayStore
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsDisplayStoreFactory
import io.github.onlinemo.meloxwatch.data.lyrics.store.LyricsTrackStore
import io.github.onlinemo.meloxwatch.data.lyrics.store.ReferenceGatedLyricsTrackStore
import io.github.onlinemo.meloxwatch.data.lyrics.store.RoomLyricsTrackStore
import io.github.onlinemo.meloxwatch.data.preferences.UserPreferencesRepository
import io.github.onlinemo.meloxwatch.data.preferences.UserPreferencesRepositoryFactory
import io.github.onlinemo.meloxwatch.data.playlists.LocalPlaylistRepository
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistException
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import io.github.onlinemo.meloxwatch.data.playlists.RoomPlaylistRepository
import io.github.onlinemo.meloxwatch.data.playlistsexchange.PlaylistDocumentRegistry
import io.github.onlinemo.meloxwatch.data.playlistsexchange.RoomPlaylistExchangeController
import io.github.onlinemo.meloxwatch.data.queue.QueueStore
import io.github.onlinemo.meloxwatch.data.queue.RoomQueueStore
import io.github.onlinemo.meloxwatch.data.scan.LocalScanEngine
import io.github.onlinemo.meloxwatch.data.scan.ScanMode
import io.github.onlinemo.meloxwatch.data.scan.ScanPhase
import io.github.onlinemo.meloxwatch.data.themes.AppearancePreferencesStore
import io.github.onlinemo.meloxwatch.data.themes.AppearancePreferencesStoreFactory
import io.github.onlinemo.meloxwatch.data.themes.AppliedThemeResources
import io.github.onlinemo.meloxwatch.data.themes.LocalBackgroundImages
import io.github.onlinemo.meloxwatch.data.themes.LocalThemeRepository
import io.github.onlinemo.meloxwatch.data.themes.SharedFontThemeStorageBudget
import io.github.onlinemo.meloxwatch.data.themes.ThemeBitmapOwner
import io.github.onlinemo.meloxwatch.data.themes.ThemeTransferQuota
import io.github.onlinemo.meloxwatch.data.themes.TransferredThemeCoordinator
import io.github.onlinemo.meloxwatch.ui.fonts.LyricsFontEditor
import io.github.onlinemo.meloxwatch.ui.fonts.LyricsFontPresentationOwner
import io.github.onlinemo.meloxwatch.ui.fonts.LyricsFontRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext

/** UI 和服务共享存储身份；播放内核只在唯一服务中建立。 */
class MeloXApplication : Application() {
    val runtimePlaybackResources by lazy { RuntimePlaybackResources() }
    val onlineAccountChanges by lazy { OnlineAccountChanges() }
    private val boundedHttpGetOwner = lazy { BoundedHttpGetClient(this) }
    internal val boundedHttpGets get() = boundedHttpGetOwner.value
    private val metingOwner = lazy { MetingRuntimeOwner(this, runtimePlaybackResources, { boundedHttpGets }) }
    internal val metingRuntime get() = metingOwner.value
    internal fun existingMetingRuntime() = if (metingOwner.isInitialized()) metingOwner.value else null
    private val onlineRuntimeOwner = lazy { OnlineMusicRuntimeOwner(this, runtimePlaybackResources, onlineAccountChanges, metingRuntime) }
    internal suspend fun onlineMusic(): OnlineMusicRuntime = onlineRuntimeOwner.value.get()
    internal val onlineQueues by lazy { OnlineQueueDirectory() }
    private val onlineCatalogOwner = lazy {
        OnlineCatalogFeatureOwner(this, ::onlineMusic, { artworks }, { boundedHttpGets }, ::clearOnlineLyricsAccount)
    }
    val lyricsMemory by lazy { LyricsMemoryOwner() }
    private val onlineLyricsOwner = lazy {
        OnlineLyricsFeatureOwner(this, ::onlineMusic, runtimePlaybackResources, onlineAccountChanges, metingRuntime)
    }
    internal val onlineLyrics get() = onlineLyricsOwner.value
    internal suspend fun clearOnlineLyricsAccount(accountId: String) = onlineLyrics.clearAccount(accountId)
    internal suspend fun onlineCatalog() = onlineCatalogOwner.value.get()
    internal fun existingOnlineCatalog() = if (onlineCatalogOwner.isInitialized()) onlineCatalogOwner.value.current() else null

    /** Feature cleanup retains the single application-owned client until every online owner closes. */
    internal fun closeOnlineResources() {
        // Closing an application is not logout. Retire the lyrics observer before sessions so
        // process shutdown preserves the same account's no-backup cache for an offline restart.
        try { if (onlineLyricsOwner.isInitialized()) onlineLyricsOwner.value.close() }
        finally {
            try { if (onlineCatalogOwner.isInitialized()) onlineCatalogOwner.value.current()?.close() }
            finally {
                try { if (onlineRuntimeOwner.isInitialized()) onlineRuntimeOwner.value.close() }
                finally {
                    try { if (metingOwner.isInitialized()) metingOwner.value.close() }
                    finally { if (boundedHttpGetOwner.isInitialized()) boundedHttpGetOwner.value.close() }
                }
            }
        }
    }

    override fun onTerminate() {
        try { closeOnlineResources() } finally { super.onTerminate() }
    }

    private val database by lazy { MeloXDatabase.open(this) }
    val queues: QueueStore by lazy { RoomQueueStore(database) }
    val library by lazy { RoomLocalLibraryRepository(database) }
    val listening by lazy { RoomListeningRepository(library) }
    val localProviderIo by lazy { LocalProviderIo() }
    private val themeBitmapOwner = lazy { ThemeBitmapOwner() }
    val themeBitmaps get() = themeBitmapOwner.value
    private val audioCopyQuota: AudioCopyQuota by lazy {
        AudioCopyQuota(this, localProviderIo,
            otherPendingBytes = { incomingThemeQuota.pendingBytesOnPhysicalWorker() },
            otherTransferTickets = { incomingThemeQuota.ticketsOnPhysicalWorker() })
    }
    private val incomingThemeQuota: ThemeTransferQuota by lazy {
        ThemeTransferQuota(this, localProviderIo,
            audioPendingBytes = { audioCopyQuota.pendingBytesOnPhysicalWorker() },
            audioTickets = { audioCopyQuota.transferTicketsOnPhysicalWorker() })
    }
    private val fontThemeStorageBudget by lazy {
        SharedFontThemeStorageBudget(this, localProviderIo, incomingThemeQuota) { audioCopyQuota.pendingBytesOnPhysicalWorker() }
    }
    private val appearanceStoreScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    private val appearanceStoreFactory by lazy { AppearancePreferencesStoreFactory() }
    val appearancePreferences: AppearancePreferencesStore
        get() = appearanceStoreFactory.get(this, appearanceStoreScope)
    val themes by lazy { LocalThemeRepository(this, localProviderIo, fontThemeStorageBudget, themeBitmaps) }
    val appliedThemeResources by lazy { AppliedThemeResources(this, localProviderIo, themeBitmaps) }
    val playbackBackgrounds by lazy { LocalBackgroundImages(this, localProviderIo, themeBitmaps) }
    val lyricsFontFiles by lazy { AndroidLyricsFontFiles(this, localProviderIo, fontThemeStorageBudget, themeBitmaps) }
    val lyricsFontSettings: LyricsFontSettingsStore get() = LyricsFontSettingsStoreFactory.get(this)
    val lyricsFontPresentationOwner by lazy { LyricsFontPresentationOwner() }
    val lyricsFontEditor by lazy { LyricsFontEditor(lyricsFontSettings, lyricsFontFiles, lyricsFontPresentationOwner) }
    val lyricsFontRuntime by lazy {
        LyricsFontRuntime(lyricsFontSettings, lyricsFontFiles, themes, appliedThemeResources,
            localProviderIo, lyricsFontPresentationOwner)
    }
    private val playlistFences by lazy { RoomPlaylistFences(database) }
    private val roomPlaylists by lazy {
        RoomPlaylistRepository(database, database.playlistDao(), folderFenceVerifier = playlistFences,
            libraryFenceVerifier = playlistFences)
    }
    val playlists: LocalPlaylistRepository get() = roomPlaylists
    val playlistFolders by lazy { RoomFolderPlaylistController(database, playlists, library) }
    private val folderPlaylistMaintainer by lazy { RoomFolderPlaylistMaintainer(database, playlists, library) }
    private class FolderMaintenanceVisibility(val foreground: Boolean)
    private val folderMaintenanceForeground = MutableStateFlow(FolderMaintenanceVisibility(false))
    private val mutableFolderMaintenanceIssue = MutableStateFlow<PlaylistIssue?>(null)
    val folderPlaylistMaintenanceIssue = mutableFolderMaintenanceIssue.asStateFlow()
    private val folderMaintenanceOwner = lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { observeFolderPlaylistMaintenance() }
    }
    val playlistKeywords by lazy { RoomKeywordPlaylistController(database, playlists, library) }
    val playlistQueueMapper by lazy { RoomPlaylistQueueMapper(library) }
    val playlistDocuments by lazy { PlaylistDocumentRegistry(this, localProviderIo) }
    val playlistExchange by lazy {
        RoomPlaylistExchangeController(this, database, playlists, library, playlistDocuments, localProviderIo)
    }
    val playlistRecovery by lazy {
        RoomPlaylistRecoveryController(database, playlists, library, closeLibraryView = ::closePlaylistView)
    }

    suspend fun closePlaylistView(viewId: String) {
        try { library.closePlaylistView(viewId, 2_500L) }
        catch (error: TimeoutCancellationException) { throw PlaylistException(PlaylistIssue.BUSY, error) }
    }
    val localSources by lazy { AndroidLocalSources(this, localProviderIo) }
    val lyricsAuthority by lazy { LyricsLoadAuthority() }
    val lyricsCopies by lazy { LyricsDocumentCopies(this, localProviderIo) }
    val lyricsContexts by lazy { RoomLyricsContextResolver(database, library) }
    private val rawLyricsTracks by lazy { RoomLyricsTrackStore(database, database.lyricsDao()) }
    val lyricsTracks: LyricsTrackStore by lazy { ReferenceGatedLyricsTrackStore(rawLyricsTracks, lyricsCopies) }
    val lyricsDocuments: LyricsDocuments by lazy {
        SavedLyricsDocumentResolver(this, localSources, lyricsCopies, localSources::verifyObservedCandidate)
    }
    val lyricsLoader by lazy {
        LocalLyricsLoader.android(this, localProviderIo, library, localSources, lyricsAuthority::isCurrent)
    }
    val lyricsDisplay: LyricsDisplayStore by lazy { LyricsDisplayStoreFactory.get(this) }

    /** Registration and physical read proofs are separate checks, outside a Room writer. */
    suspend fun validateLyricsContext(context: LocalLyricsContext): Boolean {
        if (!lyricsContexts.isCurrent(context)) return false
        val verified = localSources.validateRead(context.audio)
        return verified.contentValidation == LocalContentValidation.VERIFIED &&
            verified.copy(contentValidation = context.audio.contentValidation) == context.audio &&
            lyricsContexts.isCurrent(context)
    }
    val localMetadata by lazy { LocalMetadataReader(this, localProviderIo) }
    private val scansOwner = lazy {
        LocalScanEngine(localSources, localMetadata::read, library, localProviderIo,
            readContentToken = localMetadata::contentToken,
            verifyMetadataCandidate = localSources::verifyMetadataCandidate)
    }
    val scans get() = scansOwner.value
    val imports by lazy {
        LocalImportManager(this, localSources, localMetadata, RoomImportLedger(library), localProviderIo, copyQuota = audioCopyQuota)
    }
    val transferredThemes by lazy { TransferredThemeCoordinator(database, themes, incomingThemeQuota) }
    private val transferUploads by lazy {
        TransferUploadStore(audioCopyQuota.transferDirectory, localProviderIo,
            LocalTransferQuotaAccounting(audioCopyQuota, incomingThemeQuota),
            LocalTransferContentValidator(PlatformAudioTransferValidator(), incomingThemeQuota))
    }
    private val transferBackend by lazy {
        // 主题路由在最外层，历史状态查询也由真实主题文件/收据所有者核验。
        ThemeReceivingTransferBackend(
            RoomCompanionTransferBackend(database, library, roomPlaylists, ImportedAudioTransferBackend(imports)),
            transferredThemes)
    }
    private val transferReceiverOwner = lazy {
        LocalTransferReceiver(LocalTransferServer(transferUploads, transferBackend), localProviderIo,
            prepare = ::prepareTransferReception, retireUploads = ::retireTransferUploads)
    }
    val transferReceiver get() = transferReceiverOwner.value

    internal suspend fun prepareTransferReception() {
        library.initialize()
        playlists.initialize()
        // 完整的已安装副本保留到原操作获新授权；pending 不能封死认证恢复入口。
        // recoverTransferred 对未知、缺失或损坏的已安装对象仍拒绝，不自动发布。
        imports.recoverTransferred()
        transferUploads.retireAbandoned()
        transferredThemes.load()
    }

    internal suspend fun retireTransferUploads() {
        transferUploads.retireAbandoned()
        transferUploads.awaitIdle()
    }
    private val artworkOwner = lazy {
        LocalArtworkRepository(this, localProviderIo, validateRead = localSources::validateRead, bitmaps = themeBitmaps)
    }
    val artworks get() = artworkOwner.value
    private val recoveryLock = Mutex()
    private var localRecovered = false
    val preferences: UserPreferencesRepository
        get() = UserPreferencesRepositoryFactory.get(this)

    /** Recovery inspects private copies only; opening the app never enumerates external music. */
    suspend fun recoverLocalState(): ImportIssue? = recoveryLock.withLock {
        library.initialize()
        if (!localRecovered) {
            try {
                val recovered = imports.recover()
                playlistExchange.recoverAbandoned()
                localRecovered = recovered.pending == 0L
                if (localRecovered) null else ImportIssue.RECOVERY_REQUIRED
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                // A private-copy failure cannot make a successfully opened old library unusable.
                when (error) {
                    is ImportException -> error.issue
                    is LibraryException -> when (error.issue) {
                        LibraryIssue.BUSY -> ImportIssue.BUSY
                        LibraryIssue.STORAGE_FULL -> ImportIssue.STORAGE_FULL
                        else -> ImportIssue.RECOVERY_REQUIRED
                    }
                    is PlaylistException -> when (error.issue) {
                        PlaylistIssue.BUSY -> ImportIssue.BUSY
                        PlaylistIssue.STORAGE_FULL -> ImportIssue.STORAGE_FULL
                        else -> ImportIssue.RECOVERY_REQUIRED
                    }
                    else -> ImportIssue.RECOVERY_REQUIRED
                }
            }
        } else null
    }

    fun onUiForeground() {
        if (scansOwner.isInitialized()) scans.onForeground()
        folderMaintenanceForeground.value = FolderMaintenanceVisibility(true)
        folderMaintenanceOwner.value
    }

    fun onUiBackground() {
        if (transferReceiverOwner.isInitialized()) transferReceiver.close()
        folderMaintenanceForeground.value = FolderMaintenanceVisibility(false)
        if (scansOwner.isInitialized()) scans.onBackground()
    }

    private suspend fun observeFolderPlaylistMaintenance() {
        suspend fun attempt(block: suspend () -> Unit) {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: PlaylistException) { mutableFolderMaintenanceIssue.value = error.issue }
            catch (error: LibraryException) {
                mutableFolderMaintenanceIssue.value = when (error.issue) {
                    LibraryIssue.BUSY -> PlaylistIssue.BUSY
                    LibraryIssue.STORAGE_FULL -> PlaylistIssue.STORAGE_FULL
                    else -> PlaylistIssue.STORAGE_UNAVAILABLE
                }
            }
            catch (_: Exception) { mutableFolderMaintenanceIssue.value = PlaylistIssue.STORAGE_UNAVAILABLE }
        }
        // A fresh visibility signal restarts the owner even if StateFlow coalesces a rapid STOP/START.
        folderMaintenanceForeground.collectLatest { visibility ->
            if (!visibility.foreground) return@collectLatest
            try {
                attempt {
                    library.initialize()
                    playlists.initialize()
                    mutableFolderMaintenanceIssue.value = folderPlaylistMaintainer.resume().issue
                }
                scans.state.collectLatest { state ->
                    if (state?.phase == ScanPhase.COMPLETED && state.token.mode == ScanMode.FULL &&
                        state.coverage.permitsAbsence && state.counters.failed == 0L && state.issue == null) attempt {
                        mutableFolderMaintenanceIssue.value = folderPlaylistMaintainer.afterScan(state).issue
                    }
                }
            } finally {
                // collectLatest joins this cleanup before a new foreground sweep can acquire a lease.
                withContext(NonCancellable) { attempt { folderPlaylistMaintainer.close() } }
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (themeBitmapOwner.isInitialized()) themeBitmaps.trimUnused()
    }
}
