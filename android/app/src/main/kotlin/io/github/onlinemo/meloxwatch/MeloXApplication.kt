package io.github.onlinemo.meloxwatch

import android.app.Application
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.artwork.LocalArtworkRepository
import io.github.onlinemo.meloxwatch.data.imports.LocalImportManager
import io.github.onlinemo.meloxwatch.data.imports.ImportException
import io.github.onlinemo.meloxwatch.data.imports.ImportIssue
import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
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
    private val database by lazy { MeloXDatabase.open(this) }
    val queues: QueueStore by lazy { RoomQueueStore(database) }
    val library by lazy { RoomLocalLibraryRepository(database) }
    val listening by lazy { RoomListeningRepository(library) }
    val localProviderIo by lazy { LocalProviderIo() }
    private val playlistFences by lazy { RoomPlaylistFences(database) }
    val playlists: LocalPlaylistRepository by lazy {
        RoomPlaylistRepository(database, database.playlistDao(), folderFenceVerifier = playlistFences,
            libraryFenceVerifier = playlistFences)
    }
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
    val localMetadata by lazy { LocalMetadataReader(this, localProviderIo) }
    private val scansOwner = lazy {
        LocalScanEngine(localSources, localMetadata::read, library, localProviderIo,
            readContentToken = localMetadata::contentToken,
            verifyMetadataCandidate = localSources::verifyMetadataCandidate)
    }
    val scans get() = scansOwner.value
    val imports by lazy { LocalImportManager(this, localSources, localMetadata, RoomImportLedger(library), localProviderIo) }
    private val artworkOwner = lazy {
        LocalArtworkRepository(this, localProviderIo, validateRead = localSources::validateRead)
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
        if (artworkOwner.isInitialized()) artworks.trimUnused()
    }
}
