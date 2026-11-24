package io.github.onlinemo.meloxwatch.ui.lyrics.entry

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.onlinemo.meloxwatch.MeloXApplication
import io.github.onlinemo.meloxwatch.playback.lyrics.ServiceLyricsPlaybackPort
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map

/** One business model owns the existing acquisition/reservation across UI and external leases. */
@MainThread
class ApplicationLyricsEntryOwner(
    private val application: MeloXApplication,
    onNativeClosed: () -> Unit,
) : ViewModelStoreOwner, AutoCloseable {
    override val viewModelStore = ViewModelStore()
    val playback = ServiceLyricsPlaybackPort(onNativeClosed)
    val model: LyricsEntryViewModel by lazy {
        ViewModelProvider(this, viewModelFactory {
            initializer {
                LyricsEntryViewModel(application.queues, application.lyricsContexts::resolve,
                    application::validateLyricsContext, application.lyricsTracks, application.lyricsDocuments,
                    application.lyricsAuthority, application.lyricsLoader::load, application.lyricsLoader::validate,
                    playback, libraryChanges = application.library.observeOverview().map { it.revision }
                        .distinctUntilChanged().drop(1).map { Unit },
                    memoryOwner = application.lyricsMemory, onlineLyrics = application.onlineLyrics,
                    awaitReadReady = {
                        application.requireBackupStorageReady()
                        application.fileOperations.assertPlaybackReadable()
                    })
            }
        })[LyricsEntryViewModel::class.java]
    }

    override fun close() { try { playback.close() } finally { viewModelStore.clear() } }
}
