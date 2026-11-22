package io.github.onlinemo.meloxwatch.data.backup

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationPlaybackHost
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationsRuntime
import io.github.onlinemo.meloxwatch.data.fonts.AndroidLyricsFontFiles
import io.github.onlinemo.meloxwatch.data.fonts.BackupFontOwner
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontBackupAccess
import io.github.onlinemo.meloxwatch.data.fonts.store.DataStoreLyricsFontSettingsStore
import io.github.onlinemo.meloxwatch.data.library.RoomLocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.listening.RoomListeningRepository
import io.github.onlinemo.meloxwatch.data.local.AndroidLocalSources
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.lyrics.active.LyricsDocumentCopies
import io.github.onlinemo.meloxwatch.data.lyrics.store.DataStoreLyricsDisplayStore
import io.github.onlinemo.meloxwatch.data.playlists.RoomPlaylistRepository
import io.github.onlinemo.meloxwatch.data.preferences.DataStoreUserPreferencesRepository
import io.github.onlinemo.meloxwatch.data.queue.RoomQueueStore
import io.github.onlinemo.meloxwatch.data.themes.DataStoreAppearancePreferencesStore
import io.github.onlinemo.meloxwatch.data.themes.LocalBackgroundImages
import io.github.onlinemo.meloxwatch.data.themes.LocalThemeRepository
import io.github.onlinemo.meloxwatch.data.themes.SharedFontThemeStorageBudget
import io.github.onlinemo.meloxwatch.data.themes.ThemeBitmapOwner
import io.github.onlinemo.meloxwatch.playback.dsp.DataStoreDspSettingsStore
import io.github.onlinemo.meloxwatch.ui.fonts.LyricsFontEditor
import io.github.onlinemo.meloxwatch.ui.fonts.LyricsFontPresentationOwner
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Real disk, Room, seven DataStore/file owners and LP-10 gates; transport is a local ZIP fixture. */
internal class NativeBackupFixture(base: Context, private val root: File) {
    private class PrivateContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getDatabasePath(name: String): File = File(root, "databases/$name").apply { parentFile.mkdirs() }
    }
    val context: Context = PrivateContext(base, root)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val progressScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val barrier = BackupMutationBarrier()
    val io = LocalProviderIo()
    val database = MeloXDatabase.open(context, barrier)
    val library = RoomLocalLibraryRepository(database, { 1000L }, { BackupTestFixtures.EPOCH_MS })
    val queues = RoomQueueStore(database)
    val playlists = RoomPlaylistRepository(database, database.playlistDao(), epochMs = { BackupTestFixtures.EPOCH_MS })
    val listening = RoomListeningRepository(library)
    val sources = AndroidLocalSources(context, io)
    val host = FileOperationPlaybackHost()
    var closedPlaybackCalls = 0
        private set
    private val hostIdentity = Any()
    val fileOperations = FileOperationsRuntime(context, library, queues, io, host,
        cancelScan = { throw AssertionError("Backup startup must not enumerate or cancel a synthetic source") },
        validateRead = sources::validateRead, requireFileMutationReady = barrier::requireReadyForFileMutation,
        progressScope = progressScope)
    private fun store(name: String) = PreferenceDataStoreFactory.create(scope = scope) {
        File(root, "preferences/$name.preferences_pb").apply { parentFile.mkdirs() }
    }
    val preferences = DataStoreUserPreferencesRepository(store("player"), scope, barrier)
    val appearance = DataStoreAppearancePreferencesStore(store("appearance"), barrier)
    val display = DataStoreLyricsDisplayStore(store("lyrics-display"), scope, barrier)
    val fontSettings = DataStoreLyricsFontSettingsStore(store("lyrics-fonts"), barrier)
    val dsp = DataStoreDspSettingsStore(store("dsp"), barrier)
    val bitmaps = ThemeBitmapOwner()
    val sharedBudget = SharedFontThemeStorageBudget(context, io)
    val fontFiles = AndroidLyricsFontFiles(context, io, sharedBudget, bitmaps, barrier)
    val themes = LocalThemeRepository(context, io, sharedBudget, bitmaps, barrier)
    val background = LocalBackgroundImages(context, io, bitmaps, barrier)
    val copies = LyricsDocumentCopies(context, io, backupBarrier = barrier)
    private val fontEditor = LyricsFontEditor(fontSettings, fontFiles, LyricsFontPresentationOwner(), barrier)
    val fonts: BackupFontOwner = object : BackupFontOwner {
        override suspend fun <T> withSettings(action: suspend (LyricsFontBackupAccess) -> T): T = fontEditor.withBackupSettings(action)
    }
    val configurations = BackupNativeConfigBridge(barrier, preferences, display, appearance, background, themes, dsp)
    val resources = BackupNativeResourceBridge(context, barrier, database, io, sources, copies, background, themes)
    val permissions = BackupPermissionVerifier(database, library, sources, fileOperations::retainedRead)
    val relinker = RoomBackupRelinker(barrier, database, library, sources, io, { 1000L })
    var archiveInput: File? = null
    val coordinator = BackupCoordinator(context, barrier, database, library, queues, playlists, listening, fileOperations,
        configurations, resources, fonts, themes, io, BackupDocuments(context, io), permissions, relinker,
        epochMs = { BackupTestFixtures.EPOCH_MS }, elapsedMs = { 1000L }, copyInput = { _, directory ->
            val source = archiveInput ?: throw AssertionError("No synthetic local ZIP was supplied")
            val caller = currentCoroutineContext()
            withContext(Dispatchers.IO) {
                val target = directory.child("input.zip")
                BackupCreatedFile.create(target).use { created ->
                    source.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            caller.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            requireBackupSpace(directory.root, count.toLong())
                            val bytes = ByteBuffer.wrap(buffer, 0, count)
                            while (bytes.hasRemaining()) { caller.ensureActive(); created.channel.write(bytes) }
                        }
                    }
                    created.channel.force(true); created.requireSame()
                }
                syncBackupDirectory(directory.root)
                target
            }
        })

    suspend fun attachClosedPlayback() = withContext(Dispatchers.Main.immediate) {
        check(host.attach(hostIdentity) { closedPlaybackCalls++; host.detach(hostIdentity) })
    }
    suspend fun initializeDatabaseOwners() {
        library.initialize(); queues.initialize(); playlists.initialize(); listening.state()
    }
    suspend fun close() {
        coordinator.closeForOwner()
        fileOperations.closeForOwner()
        scope.coroutineContext[Job]?.cancelAndJoin()
        progressScope.coroutineContext[Job]?.cancelAndJoin()
        io.awaitIdle()
        database.close()
    }
}
