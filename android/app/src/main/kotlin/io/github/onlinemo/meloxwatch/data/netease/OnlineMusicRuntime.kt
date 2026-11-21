package io.github.onlinemo.meloxwatch.data.netease

import android.content.Context
import io.github.onlinemo.meloxwatch.data.auth.AuthException
import io.github.onlinemo.meloxwatch.data.auth.AuthIssue
import io.github.onlinemo.meloxwatch.data.auth.AtomicEncryptedSessionStore
import io.github.onlinemo.meloxwatch.data.auth.SessionRepository
import io.github.onlinemo.meloxwatch.data.meting.MetingRuntimeOwner
import io.github.onlinemo.meloxwatch.data.network.OkHttpExchange
import io.github.onlinemo.meloxwatch.playback.resources.OnlinePlaybackResolver
import io.github.onlinemo.meloxwatch.playback.resources.RuntimePlaybackResources
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A revision carries no account identity. A service rechecks its own runtime token on notification. */
class OnlineAccountChanges {
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()
    @Synchronized internal fun changed() {
        mutableRevision.value = if (mutableRevision.value == Long.MAX_VALUE) 0L else mutableRevision.value + 1L
    }
}

internal class OnlineMusicRuntime private constructor(
    val sessions: SessionRepository,
    val loginApi: NeteaseAuthApi,
    val playbackResolver: OnlinePlaybackResolver,
    private val exchange: OkHttpExchange,
) : AutoCloseable {
    internal fun catalogApi(): NeteaseCatalogApi = NeteaseCatalogApi(NeteaseEapi(exchange))
    internal fun lyricsApi(): NeteaseLyricsApi = NeteaseLyricsApi(NeteaseEapi(exchange))
    override fun close() {
        try { sessions.close() } finally { exchange.close() }
    }

    companion object {
        suspend fun create(context: Context, resources: RuntimePlaybackResources, changes: OnlineAccountChanges,
            meting: MetingRuntimeOwner? = null): OnlineMusicRuntime {
            val store = AtomicEncryptedSessionStore.create(context)
            val exchange = OkHttpExchange.create(context, NeteaseEapi.ORIGIN)
            try {
                val sessions = SessionRepository(store) {
                    // A same-account revalidation may already have a new token. Revoke only stale tokens.
                    resources.invalidateStaleAccounts()
                    changes.changed()
                }
                val eapi = NeteaseEapi(exchange)
                val login = NeteaseAuthApi(eapi)
                return OnlineMusicRuntime(sessions, login,
                    NeteasePlaybackResolver(sessions, login, NeteasePlaybackApi(eapi), resources,
                        supplemental = { meting?.playbackResolver(sessions) }), exchange)
            } catch (failure: Exception) {
                exchange.close()
                throw failure
            }
        }
    }
}

/** The constructor is memory-only. The store and client are created only by an explicit online caller. */
internal class OnlineMusicRuntimeOwner(
    private val context: Context,
    private val resources: RuntimePlaybackResources,
    private val changes: OnlineAccountChanges,
    private val meting: MetingRuntimeOwner? = null,
) : AutoCloseable {
    private val creation = Mutex()
    private val memory = Any()
    private var runtime: OnlineMusicRuntime? = null
    private var closed = false
    suspend fun get(): OnlineMusicRuntime = creation.withLock {
        synchronized(memory) { requireOpen(); runtime }?.let { return@withLock it }
        val created = OnlineMusicRuntime.create(context, resources, changes, meting)
        try {
            currentCoroutineContext().ensureActive()
            synchronized(memory) { requireOpen(); runtime = created; created }
        } catch (failure: Exception) { created.close(); throw failure }
    }
    override fun close() {
        val previous = synchronized(memory) {
            if (closed) return
            closed = true
            runtime.also { runtime = null }
        }
        previous?.close()
    }
    private fun requireOpen() { if (closed) throw AuthException(AuthIssue.CLOSED) }
}
