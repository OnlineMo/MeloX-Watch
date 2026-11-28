package io.github.onlinemo.meloxwatch.data.online

import android.content.Context
import io.github.onlinemo.meloxwatch.data.artwork.LocalArtworkRepository
import io.github.onlinemo.meloxwatch.data.network.BoundedHttpGetClient
import io.github.onlinemo.meloxwatch.data.auth.SessionRepository
import io.github.onlinemo.meloxwatch.data.netease.OnlineMusicRuntime
import io.github.onlinemo.meloxwatch.data.online.cache.OnlineCatalogDatabase
import io.github.onlinemo.meloxwatch.data.online.cache.RoomOnlineCatalogCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Only an explicit online page creates the account client and this separate no-backup cache. */
internal class OnlineCatalogFeatureOwner(
    private val context: Context,
    private val online: suspend () -> OnlineMusicRuntime,
    private val images: () -> LocalArtworkRepository,
    private val http: () -> BoundedHttpGetClient,
    private val clearLyricsAccount: suspend (String) -> io.github.onlinemo.meloxwatch.data.lyrics.online.OnlineLyricsIssue? = { null },
) {
    private val creation = Mutex()
    @Volatile private var feature: OnlineCatalogFeature? = null
    fun current(): OnlineCatalogFeature? = feature
    suspend fun get(): OnlineCatalogFeature = creation.withLock {
        feature?.let { return@withLock it }
        val runtime = online()
        val database = OnlineCatalogDatabase.open(context)
        try {
            val artworks = OnlineArtworkRepository(runtime.sessions, images(), http())
            val history = OnlineSearchHistory.create(context.applicationContext.noBackupFilesDir, runtime.sessions)
            OnlineCatalogFeature(runtime.sessions,
                OnlineCatalogRepository(runtime.sessions, runtime.loginApi, runtime.catalogApi(),
                    RoomOnlineCatalogCache(database, context.applicationContext.noBackupFilesDir), registerArtwork = artworks::register),
                artworks, searchHistory = history,
                login = { scope -> OnlineLoginPresenter(runtime.loginApi, runtime.sessions, scope,
                    searchHistory = history, clearLyricsAccount = clearLyricsAccount) }, database = database)
                .also { feature = it }
        } catch (failure: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { database.close() }
            throw failure
        }
    }
}

internal class OnlineCatalogFeature(
    val sessions: SessionRepository,
    val catalog: OnlineCatalogRepository,
    val artworks: OnlineArtworkRepository,
    val searchHistory: OnlineSearchHistory,
    private val login: (CoroutineScope) -> OnlineLoginPresenter,
    private val database: OnlineCatalogDatabase,
) : AutoCloseable {
    fun login(scope: CoroutineScope): OnlineLoginPresenter = login.invoke(scope)
    override fun close() = database.close()
}
