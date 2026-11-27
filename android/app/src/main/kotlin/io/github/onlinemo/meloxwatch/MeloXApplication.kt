package io.github.onlinemo.meloxwatch

import android.app.Application
import io.github.onlinemo.meloxwatch.data.cue.CueReferenceCodec
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistLimits
import io.github.onlinemo.meloxwatch.data.bluetoothlyrics.WatchBluetoothLyricsController
import io.github.onlinemo.meloxwatch.ui.lyrics.entry.ApplicationLyricsEntryOwner
import io.github.onlinemo.meloxwatch.data.about.AppLicenseRepository
import io.github.onlinemo.meloxwatch.data.about.DiagnosticArea
import io.github.onlinemo.meloxwatch.data.about.DiagnosticCode
import io.github.onlinemo.meloxwatch.data.about.ProjectUpdateSource
import io.github.onlinemo.meloxwatch.data.about.SafeDiagnosticsStore
import io.github.onlinemo.meloxwatch.data.backup.BackupCoordinator
import io.github.onlinemo.meloxwatch.data.backup.BackupDocuments
import io.github.onlinemo.meloxwatch.data.backup.BackupException
import io.github.onlinemo.meloxwatch.data.backup.BackupIssue
import io.github.onlinemo.meloxwatch.data.backup.BackupMutationBarrier
import io.github.onlinemo.meloxwatch.data.backup.BackupNativeConfigBridge
import io.github.onlinemo.meloxwatch.data.backup.BackupNativeResourceBridge
import io.github.onlinemo.meloxwatch.data.backup.BackupPermissionVerifier
import io.github.onlinemo.meloxwatch.data.backup.BackupStartupState
import io.github.onlinemo.meloxwatch.data.backup.RoomBackupRelinker
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationException
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationIssue
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationPlaybackHost
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationsRuntime
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontBackupAccess
import io.github.onlinemo.meloxwatch.data.fonts.BackupFontOwner
import io.github.onlinemo.meloxwatch.data.storage.FileStorageRepository
import io.github.onlinemo.meloxwatch.data.storage.FileStoragePhysical
import io.github.onlinemo.meloxwatch.data.storage.StorageException
import io.github.onlinemo.meloxwatch.data.storage.StorageIssue
import io.github.onlinemo.meloxwatch.data.storage.StoragePrivateInventory
import io.github.onlinemo.meloxwatch.data.storage.StoragePrivateReport
import io.github.onlinemo.meloxwatch.playback.dsp.DspRuntime
import io.github.onlinemo.meloxwatch.playback.dsp.DspSettingsStore
import io.github.onlinemo.meloxwatch.playback.dsp.DspSettingsStoreFactory
import io.github.onlinemo.meloxwatch.data.local.LocalArtifactDocuments
import io.github.onlinemo.meloxwatch.data.online.OnlineCatalogFeatureOwner
import io.github.onlinemo.meloxwatch.data.online.OnlineQueueDirectory
import io.github.onlinemo.meloxwatch.data.network.BoundedHttpGetClient
import io.github.onlinemo.meloxwatch.data.metadata.LocalMetadataArtworkFiles
import io.github.onlinemo.meloxwatch.data.metadata.LocalMetadataRepository
import io.github.onlinemo.meloxwatch.data.metadata.NeteaseMetadataClientOwner
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
import io.github.onlinemo.meloxwatch.data.cue.AndroidCueLocalDocuments
import io.github.onlinemo.meloxwatch.data.cue.CuePathMatcher
import io.github.onlinemo.meloxwatch.data.cue.CueScanReader
import io.github.onlinemo.meloxwatch.data.local.LocalMetadataReader
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.LocalProviderFailureStage
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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private val applicationScopeOwner = lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    internal val backupMutationBarrier by lazy { BackupMutationBarrier() }
    val dspRuntime by lazy { DspRuntime() }
    private val dspStoreScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    private val dspStoreFactory by lazy { DspSettingsStoreFactory() }
    val dspSettings: DspSettingsStore
        get() = dspStoreFactory.get(this, dspStoreScope, backupMutationBarrier)
    private val diagnosticQueue = Channel<Pair<DiagnosticArea, DiagnosticCode>>(8)
    private val diagnosticLock = Any()
    private var diagnosticRecordingStarted = false
    internal val diagnostics by lazy { SafeDiagnosticsStore(this) }
    internal val appLicenses by lazy { AppLicenseRepository(assets) }
    internal val localArtifactDocuments by lazy { LocalArtifactDocuments(this, localProviderIo) }
    internal val projectUpdates by lazy { ProjectUpdateSource(boundedHttpGets) }

    internal fun recordDiagnostic(area: DiagnosticArea, code: DiagnosticCode) {
        diagnosticQueue.trySend(area to code)
        synchronized(diagnosticLock) {
            if (diagnosticRecordingStarted) return
            diagnosticRecordingStarted = true
            applicationScopeOwner.value.launch(Dispatchers.IO) {
                for ((recordArea, recordCode) in diagnosticQueue) {
                    try { diagnostics.record(recordArea, recordCode) }
                    catch (failure: CancellationException) { throw failure }
                    catch (_: Exception) { /* 诊断失效不能中断播放或擅自清空已有记录。 */ }
                }
            }
        }
    }

    val runtimePlaybackResources by lazy { RuntimePlaybackResources() }
    val onlineAccountChanges by lazy { OnlineAccountChanges() }
    private val boundedHttpGetOwner = lazy { BoundedHttpGetClient(this) }
    internal val boundedHttpGets get() = boundedHttpGetOwner.value
    private val metadataClientOwner = lazy { NeteaseMetadataClientOwner(this, { boundedHttpGets }) }
    internal suspend fun metadataLookupClient() = metadataClientOwner.value.get()
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
    private val lyricsEntryRuntime = lazy { ApplicationLyricsEntryOwner(this, ::stopBluetoothLyricsIfStarted) }
    internal val lyricsEntryOwner get() = lyricsEntryRuntime.value
    private val bluetoothLyricsOwner = lazy { WatchBluetoothLyricsController(this) }
    internal val bluetoothLyrics get() = bluetoothLyricsOwner.value

    internal fun stopBluetoothLyricsIfStarted() {
        if (bluetoothLyricsOwner.isInitialized()) bluetoothLyricsOwner.value.stop()
    }

    internal suspend fun quiesceBluetoothLyricsIfStarted() {
        if (bluetoothLyricsOwner.isInitialized()) bluetoothLyricsOwner.value.quiesce()
    }
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
                    finally {
                        try { if (metadataClientOwner.isInitialized()) metadataClientOwner.value.close() }
                        finally { if (boundedHttpGetOwner.isInitialized()) boundedHttpGetOwner.value.close() }
                    }
                }
            }
        }
    }

    override fun onTerminate() {
        if (bluetoothLyricsOwner.isInitialized()) bluetoothLyricsOwner.value.close()
        if (lyricsEntryRuntime.isInitialized()) lyricsEntryRuntime.value.close()
        if (localProviderIoOwner.isInitialized()) localProviderIoOwner.value.closeFailureReceipts()
        diagnosticQueue.close()
        if (applicationScopeOwner.isInitialized()) applicationScopeOwner.value.cancel()
        try { closeOnlineResources() } finally { super.onTerminate() }
    }

    private val database by lazy { MeloXDatabase.open(this, backupMutationBarrier) }
    internal val roomQueues by lazy { RoomQueueStore(database) }
    val queues: QueueStore get() = roomQueues
    val library by lazy { RoomLocalLibraryRepository(database) }
    val listening by lazy { RoomListeningRepository(library) }
    private val localProviderIoOwner = lazy {
        LocalProviderIo(reportRetiredFailure = { stage ->
            recordDiagnostic(DiagnosticArea.LIBRARY, when (stage) {
                LocalProviderFailureStage.READ -> DiagnosticCode.INTERNAL
                LocalProviderFailureStage.RETIREMENT -> DiagnosticCode.RECOVERY_REQUIRED
            })
        })
    }
    val localProviderIo get() = localProviderIoOwner.value
    internal val filePlaybackHost by lazy { FileOperationPlaybackHost() }
    val fileOperations: FileOperationsRuntime by lazy {
        FileOperationsRuntime(this, library, roomQueues, localProviderIo, filePlaybackHost,
            cancelScan = { scanId -> if (scansOwner.isInitialized()) scans.cancelAndAwait(scanId) },
            validateRead = localSources::validateRead,
            requireFileMutationReady = {
                try { backupMutationBarrier.requireReadyForFileMutation() }
                catch (failure: BackupException) {
                    throw FileOperationException(if (failure.issue == BackupIssue.BUSY) FileOperationIssue.BUSY
                        else FileOperationIssue.RECOVERY_REQUIRED, failure)
                }
            })
    }
    val fileStorage by lazy {
        FileStorageRepository(this, localProviderIo, fileOperations,
            artworkMeasure = { artworks.measureDiskCache() }, artworkClear = { artworks.clearDiskCache() },
            lyricsMeasure = { onlineLyrics.measureDiskCache() }, lyricsClear = { onlineLyrics.clearDiskCache() },
            privateMeasure = ::measurePrivateStorage)
    }

    private val storageInventory by lazy { StoragePrivateInventory(this, localProviderIo) }

    private suspend fun measurePrivateStorage(): StoragePrivateReport {
        requireBackupStorageReady()
        return backupMutationBarrier.mutation({ StorageException(StorageIssue.BUSY) }) {
            backups.withStorageInventory { backupSpecification ->
                storageInventory.measure {
                    FileStoragePhysical.privateAudioSpecifications(this) + listOf(lyricsCopies.storageDirectorySpecOnWorker(),
                        metadataArtworkFiles.storageDirectorySpecOnWorker(), lyricsFontFiles.storageDirectorySpecOnWorker(),
                        themes.storageDirectorySpecOnWorker(), playbackBackgrounds.storageDirectorySpecOnWorker(),
                        backupSpecification())
                }
            }
        }
    }
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
    val themes by lazy { LocalThemeRepository(this, localProviderIo, fontThemeStorageBudget, themeBitmaps,
        backupBarrier = backupMutationBarrier) }
    val appliedThemeResources by lazy { AppliedThemeResources(this, localProviderIo, themeBitmaps) }
    val playbackBackgrounds by lazy { LocalBackgroundImages(this, localProviderIo, themeBitmaps,
        backupBarrier = backupMutationBarrier) }
    val lyricsFontFiles by lazy { AndroidLyricsFontFiles(this, localProviderIo, fontThemeStorageBudget, themeBitmaps,
        backupBarrier = backupMutationBarrier) }
    val lyricsFontSettings: LyricsFontSettingsStore get() = LyricsFontSettingsStoreFactory.get(this)
    val lyricsFontPresentationOwner by lazy { LyricsFontPresentationOwner() }
    val lyricsFontEditor by lazy { LyricsFontEditor(lyricsFontSettings, lyricsFontFiles, lyricsFontPresentationOwner,
        backupBarrier = backupMutationBarrier) }
    val lyricsFontRuntime by lazy {
        LyricsFontRuntime(lyricsFontSettings, lyricsFontFiles, themes, appliedThemeResources,
            localProviderIo, lyricsFontPresentationOwner)
    }
    private val playlistFences by lazy { RoomPlaylistFences(database) }
    private val roomPlaylists by lazy {
        RoomPlaylistRepository(database, database.playlistDao(), folderFenceVerifier = playlistFences,
            libraryFenceVerifier = playlistFences, cueInputRegistrar = { generation ->
                var first = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val page = database.playlistDao().membersPage(generation, first, PlaylistLimits.PAGE_SIZE)
                    if (page.isEmpty()) break
                    for (member in page) {
                        currentCoroutineContext().ensureActive()
                        val cue = member.cueReference?.let { CueReferenceCodec.decode(it, portableOnly = true) } ?: continue
                        val metadata = LocalAudioMetadata(title = member.titleHint, artist = member.artistHint,
                            album = member.albumHint, durationMs = member.durationMs ?: cue.identity.durationUs / 1000L,
                            trackNumber = cue.identity.trackNumber)
                        library.registerPortableCueHintUnlocked(cue, metadata, "CUE ${cue.identity.trackNumber}")
                    }
                    first = Math.addExact(page.last().ordinal, 1L)
                }
            })
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
    val lyricsCopies by lazy { LyricsDocumentCopies(this, localProviderIo, backupBarrier = backupMutationBarrier) }
    val lyricsContexts by lazy { RoomLyricsContextResolver(database, library, cueReadValidator = localSources::validateCueRead) }
    private val rawLyricsTracks by lazy { RoomLyricsTrackStore(database, database.lyricsDao()) }
    val lyricsTracks: LyricsTrackStore by lazy { ReferenceGatedLyricsTrackStore(rawLyricsTracks, lyricsCopies) }
    val lyricsDocuments: LyricsDocuments by lazy {
        SavedLyricsDocumentResolver(this, localSources, lyricsCopies, localSources::verifyObservedCandidate)
    }
    val lyricsLoader by lazy {
        LocalLyricsLoader.android(this, localProviderIo, library, localSources, lyricsAuthority::isCurrent)
    }
    internal val metadataArtworkFiles by lazy {
        LocalMetadataArtworkFiles(this, localProviderIo, backupMutationBarrier, bitmaps = themeBitmaps)
    }
    internal val metadataAssociations by lazy {
        LocalMetadataRepository(this, database, library, lyricsContexts, localSources, queues,
            lyricsCopies, metadataArtworkFiles, backupMutationBarrier)
    }
    val lyricsDisplay: LyricsDisplayStore by lazy { LyricsDisplayStoreFactory.get(this) }

    /** Registration and physical read proofs are separate checks, outside a Room writer. */
    suspend fun validateLyricsContext(context: LocalLyricsContext): Boolean {
        if (!lyricsContexts.isCurrent(context)) return false
        val verified = if (context.cueReference != null) localSources.validateCueRead(context.audio) else localSources.validateRead(context.audio)
        return verified.contentValidation == LocalContentValidation.VERIFIED &&
            verified.copy(contentValidation = context.audio.contentValidation) == context.audio &&
            lyricsContexts.isCurrent(context)
    }
    val localMetadata by lazy { LocalMetadataReader(this, localProviderIo) }
    private val cueScanReader by lazy {
        CueScanReader(fileOperations.cueMutations, CuePathMatcher(localSources),
            AndroidCueLocalDocuments(localSources))
    }
    private val scansOwner = lazy {
        LocalScanEngine(localSources, localMetadata::read, fileOperations.mutations, localProviderIo,
            readContentToken = localMetadata::contentToken,
            verifyMetadataCandidate = localSources::verifyMetadataCandidate,
            scanCue = { token, candidate, encoding -> cueScanReader.read(token, candidate, encoding) })
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
        requireBackupStorageReady()
        fileOperations.initialize()
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
        LocalArtworkRepository(this, localProviderIo, validateRead = localSources::validateRead, bitmaps = themeBitmaps,
            confirmedArtwork = { descriptor, edge -> metadataAssociations.acquireArtwork(descriptor, edge, artworks) })
    }
    val artworks: LocalArtworkRepository get() = artworkOwner.value
    private val recoveryLock = Mutex()
    private var localRecovered = false
    private val preferencesOwner = lazy { UserPreferencesRepositoryFactory.create(this, applicationScopeOwner.value) }
    val preferences: UserPreferencesRepository
        get() = preferencesOwner.value

    private val backupFontOwner = object : BackupFontOwner {
        override suspend fun <T> withSettings(action: suspend (LyricsFontBackupAccess) -> T): T =
            lyricsFontEditor.withBackupSettings(action)
    }
    private val backupConfigurations by lazy {
        BackupNativeConfigBridge(backupMutationBarrier, preferences, lyricsDisplay,
            appearancePreferences, playbackBackgrounds, themes, dspSettings)
    }
    private val backupResources by lazy {
        BackupNativeResourceBridge(this, backupMutationBarrier, database, localProviderIo,
            localSources, lyricsCopies, playbackBackgrounds, themes, metadataArtwork = metadataArtworkFiles)
    }
    internal val backupDocuments by lazy { BackupDocuments(localArtifactDocuments) }
    val backups by lazy {
        BackupCoordinator(this, backupMutationBarrier, database, library, roomQueues,
            roomPlaylists, listening, fileOperations, backupConfigurations, backupResources,
            backupFontOwner, themes, localProviderIo, backupDocuments,
            BackupPermissionVerifier(database, library, localSources, fileOperations::retainedRead),
            RoomBackupRelinker(backupMutationBarrier, database, library, localSources, localProviderIo))
    }
    private val backupStartupOwner = lazy {
        applicationScopeOwner.value.async(Dispatchers.IO) { backups.initializeStorage() }
    }

    override fun onCreate() {
        super.onCreate()
        backupStartupOwner.value
    }

    internal suspend fun requireBackupStorageReady() {
        // Join only the first startup operation. A later explicit retry changes the authoritative
        // coordinator state, so the first Unavailable result must never remain a permanent latch.
        backupStartupOwner.value.await()
        backups.awaitReady()
        when (val current = backups.startup.value) {
            is BackupStartupState.Ready -> backups.requireReady()
            is BackupStartupState.Unavailable -> throw BackupException(current.issue)
            BackupStartupState.Loading -> throw BackupException(BackupIssue.BUSY)
        }
    }

    /** Recovery inspects private copies only; opening the app never enumerates external music. */
    suspend fun recoverLocalState(): ImportIssue? = recoveryLock.withLock {
        try {
            requireBackupStorageReady()
            fileOperations.initialize()
            library.initialize()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            return@withLock when (failure) {
                is BackupException -> if (failure.issue == BackupIssue.BUSY) ImportIssue.BUSY else ImportIssue.RECOVERY_REQUIRED
                is FileOperationException -> if (failure.issue == FileOperationIssue.BUSY) ImportIssue.BUSY else ImportIssue.RECOVERY_REQUIRED
                is LibraryException -> if (failure.issue == LibraryIssue.BUSY) ImportIssue.BUSY else ImportIssue.RECOVERY_REQUIRED
                else -> ImportIssue.RECOVERY_REQUIRED
            }
        }
        if (!localRecovered) {
            try {
                val recovered = imports.recover()
                playlistExchange.recoverAbandoned()
                metadataAssociations.recoverLyrics()
                metadataAssociations.recoverArtwork()
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
                    requireBackupStorageReady()
                    fileOperations.initialize()
                    library.initialize()
                    playlists.initialize()
                    mutableFolderMaintenanceIssue.value = folderPlaylistMaintainer.resume().issue
                }
                scans.state.collectLatest { state ->
                    if (state?.phase == ScanPhase.COMPLETED &&
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
