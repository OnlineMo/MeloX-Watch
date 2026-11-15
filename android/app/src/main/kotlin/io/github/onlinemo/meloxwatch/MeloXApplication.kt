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
import io.github.onlinemo.meloxwatch.data.listening.RoomListeningRepository
import io.github.onlinemo.meloxwatch.data.local.AndroidLocalSources
import io.github.onlinemo.meloxwatch.data.local.LocalMetadataReader
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.preferences.UserPreferencesRepository
import io.github.onlinemo.meloxwatch.data.preferences.UserPreferencesRepositoryFactory
import io.github.onlinemo.meloxwatch.data.queue.QueueStore
import io.github.onlinemo.meloxwatch.data.queue.RoomQueueStore
import io.github.onlinemo.meloxwatch.data.scan.LocalScanEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException

/** UI 和服务共享存储身份；播放内核只在唯一服务中建立。 */
class MeloXApplication : Application() {
    private val database by lazy { MeloXDatabase.open(this) }
    val queues: QueueStore by lazy { RoomQueueStore(database) }
    val library by lazy { RoomLocalLibraryRepository(database) }
    val listening by lazy { RoomListeningRepository(library) }
    val localProviderIo by lazy { LocalProviderIo() }
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
                    else -> ImportIssue.RECOVERY_REQUIRED
                }
            }
        } else null
    }

    fun onUiForeground() { if (scansOwner.isInitialized()) scans.onForeground() }
    fun onUiBackground() { if (scansOwner.isInitialized()) scans.onBackground() }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (artworkOwner.isInitialized()) artworks.trimUnused()
    }
}
