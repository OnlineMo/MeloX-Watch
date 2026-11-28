package io.github.onlinemo.meloxwatch.ui.online

import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import io.github.onlinemo.meloxwatch.data.artwork.LocalArtworkRepository
import io.github.onlinemo.meloxwatch.data.auth.ControlledSessionStore
import io.github.onlinemo.meloxwatch.data.auth.SessionRepository
import io.github.onlinemo.meloxwatch.data.auth.fixtureSession
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.netease.NeteaseAuthApi
import io.github.onlinemo.meloxwatch.data.netease.NeteaseCatalogApi
import io.github.onlinemo.meloxwatch.data.netease.NeteaseEapi
import io.github.onlinemo.meloxwatch.data.network.BoundedHttpGetClient
import io.github.onlinemo.meloxwatch.data.network.HttpExchange
import io.github.onlinemo.meloxwatch.data.network.jsonString
import io.github.onlinemo.meloxwatch.data.online.OnlineArtworkRepository
import io.github.onlinemo.meloxwatch.data.online.OnlineCatalogFeature
import io.github.onlinemo.meloxwatch.data.online.OnlineCatalogRepository
import io.github.onlinemo.meloxwatch.data.online.OnlineLoginPresenter
import io.github.onlinemo.meloxwatch.data.online.OnlineNetworkMonitor
import io.github.onlinemo.meloxwatch.data.online.OnlineQueueDirectory
import io.github.onlinemo.meloxwatch.data.online.OnlineSearchHistory
import io.github.onlinemo.meloxwatch.data.online.cache.OnlineCatalogDatabase
import io.github.onlinemo.meloxwatch.data.online.cache.RoomOnlineCatalogCache
import io.github.onlinemo.meloxwatch.data.online.catalogFields
import io.github.onlinemo.meloxwatch.data.online.catalogSearch
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttp
import okhttp3.OkHttpClient
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

/** Actual VM, native Room and AtomicFile; every provider/cover transport is synthetic or blocked. */
internal class OnlineSearchFixture(root: File) {
    val context: Context = RuntimeEnvironment.getApplication()
    val file = File(root, "history")
    val sessions = SessionRepository(ControlledSessionStore())
    val database: OnlineCatalogDatabase = Room.inMemoryDatabaseBuilder(context, OnlineCatalogDatabase::class.java).build()
    val calls = CopyOnWriteArrayList<String>()
    val writeStarts = AtomicInteger()
    val failNextWrite = AtomicBoolean()
    private val nextWrite = AtomicReference<PhysicalWriteGate?>()
    private val nextReply = AtomicReference<ReplyGate?>()
    private val gates = ArrayList<PhysicalWriteGate>()
    private val replies = ArrayList<ReplyGate>()
    val history = OnlineSearchHistory.controlled(file, sessions, afterStartWrite = {
        writeStarts.incrementAndGet()
        if (failNextWrite.compareAndSet(true, false)) throw IOException("synthetic AtomicFile write failure")
        nextWrite.getAndSet(null)?.hold()
    })
    private val eapi = NeteaseEapi(HttpExchange { request ->
        check(request.url.endsWith("/eapi/search/get")) { "Unexpected synthetic catalog endpoint" }
        calls += catalogFields(request)["s"].jsonString()
        nextReply.getAndSet(null)?.let { gate ->
            withContext(NonCancellable) {
                gate.entered.complete(Unit)
                try { gate.release.await() } finally { gate.exited.complete(Unit) }
            }
        }
        catalogSearch(listOf(201L), 1L)
    })
    private val localIo = LocalProviderIo()
    private val http: BoundedHttpGetClient
    private val feature: OnlineCatalogFeature
    private val models = ViewModelStore()
    var model: OnlineViewModel? = null
        private set

    init {
        OkHttp.initialize(context)
        val blocked = OkHttpClient.Builder().addInterceptor { throw IOException("Cover network is outside this fixture") }.build()
        http = BoundedHttpGetClient(context, blocked)
        val images = LocalArtworkRepository(context, localIo, { error("Search must not access a local audio source") })
        val artwork = OnlineArtworkRepository(sessions, images, http)
        feature = OnlineCatalogFeature(sessions,
            OnlineCatalogRepository(sessions, NeteaseAuthApi(eapi), NeteaseCatalogApi(eapi), RoomOnlineCatalogCache(database)),
            artwork, searchHistory = history,
            login = { scope -> OnlineLoginPresenter(NeteaseAuthApi(eapi), sessions, scope, searchHistory = history) }, database = database)
    }

    suspend fun signIn(id: String = "101") {
        sessions.commit(fixtureSession(id), sessions.loginEpoch()) { accept -> accept() }
    }
    suspend fun visibleModel(): OnlineViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == OnlineViewModel::class.java)
                return OnlineViewModel({ feature }, { null }, OnlineQueueDirectory(),
                    { error("Search must not submit a queue") }, { error("Search must not accept playback") },
                    { OnlineNetworkMonitor(context) }) as T
            }
        }
        return ViewModelProvider(models, factory)[OnlineViewModel::class.java].also {
            model = it
            it.setVisible(true)
            awaitState(it) { state -> !state.busy && state.account.generation == sessions.snapshot.value.generation }
        }
    }
    fun blockNextWrite(): PhysicalWriteGate = PhysicalWriteGate().also { gates += it; check(nextWrite.compareAndSet(null, it)) }
    fun blockNextReply(): ReplyGate = ReplyGate().also { replies += it; check(nextReply.compareAndSet(null, it)) }
    suspend fun close() {
        gates.forEach { it.release.countDown() }
        replies.forEach { it.release.complete(Unit) }
        try {
            model?.let {
                it.setVisible(true)
                it.showSearch()
                awaitState(it) { state -> state.screen == OnlineScreen.SEARCH && !state.busy }
                it.setVisible(false)
            }
            models.clear()
        } finally {
            sessions.close()
            http.close()
            feature.close()
        }
    }
    private suspend fun awaitState(model: OnlineViewModel, predicate: (OnlineUiState) -> Boolean): OnlineUiState =
        awaitOnlineState(model, predicate)
}

internal class PhysicalWriteGate {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    fun hold() { entered.countDown(); check(release.await(5L, TimeUnit.SECONDS)) }
}
internal class ReplyGate {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val exited = CompletableDeferred<Unit>()
}
internal suspend fun awaitOnlineState(model: OnlineViewModel, predicate: (OnlineUiState) -> Boolean): OnlineUiState {
    awaitOnlineCondition { predicate(model.state.value) }
    return model.state.value
}
internal suspend fun awaitOnlineCondition(predicate: () -> Boolean): Unit = withTimeout(10_000L) {
    while (!predicate()) { Shadows.shadowOf(Looper.getMainLooper()).idle(); delay(1L) }
}
