package io.github.onlinemo.meloxwatch.data.lyrics.online

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.onlinemo.meloxwatch.data.auth.SessionValidity
import io.github.onlinemo.meloxwatch.data.auth.AuthException
import io.github.onlinemo.meloxwatch.data.auth.AuthIssue
import io.github.onlinemo.meloxwatch.data.storage.StorageCategory
import io.github.onlinemo.meloxwatch.data.meting.MetingRuntimeOwner
import io.github.onlinemo.meloxwatch.data.netease.OnlineAccountChanges
import io.github.onlinemo.meloxwatch.data.netease.OnlineMusicRuntime
import io.github.onlinemo.meloxwatch.model.onlineOpaqueHash
import io.github.onlinemo.meloxwatch.playback.resources.RuntimePlaybackResources
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The local Entry may construct this port: account/client/directory work starts only on online load or explicit cleanup. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class OnlineLyricsFeatureOwner(
    context: Context,
    private val online: suspend () -> OnlineMusicRuntime,
    private val resources: RuntimePlaybackResources,
    private val accounts: OnlineAccountChanges,
    private val meting: MetingRuntimeOwner,
) : OnlineLyricsPort, AutoCloseable {
    private val application = context.applicationContext
    private val creation = Mutex()
    private val memory = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository = MutableStateFlow<OnlineLyricsRepository?>(null)
    private val cache = DiskOnlineLyricsCache(directoryProvider = { application.noBackupFilesDir.resolve("online-lyrics-cache") })
    @Volatile private var closed = false
    @Volatile var cleanupIssue: OnlineLyricsIssue? = null
        private set

    override val changes: Flow<Unit> = merge(
        combine(accounts.revision, resources.openedResource, meting.configuration.snapshot) { revision, opened, configured ->
            val service = if (opened?.resolverId == "meting-custom") configured else null
            SourceEpoch(revision, opened?.operationId, service?.generation, service?.enabled, service?.changing, service?.issue)
        }.distinctUntilChanged().drop(1).map { Unit },
        repository.flatMapLatest { it?.changes?.drop(1) ?: emptyFlow() },
    )

    override suspend fun load(request: OnlineLyricsRequest, isCurrent: () -> Boolean): OnlineLyricsResult =
        withTimeoutOrNull(8_000L) { loadCurrent(request, isCurrent) }
            ?: OnlineLyricsResult.Unavailable(OnlineLyricsIssue.TIMEOUT)

    private suspend fun loadCurrent(request: OnlineLyricsRequest, isCurrent: () -> Boolean): OnlineLyricsResult {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) throw CancellationException("Online lyrics intent retired")
        if (closed) return OnlineLyricsResult.Unavailable(OnlineLyricsIssue.CLOSED)
        val current = try { get() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: AuthException) {
            return OnlineLyricsResult.Unavailable(when (error.issue) {
                AuthIssue.LOGIN_REQUIRED -> OnlineLyricsIssue.LOGIN_REQUIRED
                AuthIssue.CLOSED -> OnlineLyricsIssue.CLOSED
                AuthIssue.BUSY -> OnlineLyricsIssue.BUSY
                else -> OnlineLyricsIssue.SESSION_UNAVAILABLE
            })
        }
        catch (_: IOException) { return OnlineLyricsResult.Unavailable(OnlineLyricsIssue.READ_FAILED) }
        catch (_: OutOfMemoryError) { return OnlineLyricsResult.Unavailable(OnlineLyricsIssue.MEMORY_LIMIT) }
        catch (_: Exception) { return OnlineLyricsResult.Unavailable(OnlineLyricsIssue.SESSION_UNAVAILABLE) }
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) throw CancellationException("Online lyrics intent retired")
        return current.load(request, isCurrent)
    }

    override suspend fun validate(loaded: OnlineLyricsResult.Loaded): Boolean =
        !closed && repository.value?.validate(loaded) == true

    private suspend fun get(): OnlineLyricsRepository = creation.withLock {
        repository.value?.let { return@withLock it }
        if (closed) throw CancellationException("Online lyrics owner closed")
        val runtime = online()
        currentCoroutineContext().ensureActive()
        val created = OnlineLyricsRepository(runtime.sessions, runtime.lyricsApi(), resources, meting, cache, ::networkAvailable)
        synchronized(memory) {
            if (closed) { created.close(); throw CancellationException("Online lyrics owner closed") }
            repository.value = created
        }
        // This is account cleanup, independent of hidden UI and without any polling or lyric fetch.
        // Register before get returns; a queued dispatcher must not skip the initial owner.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var previous = runtime.sessions.snapshot.value.account?.let { onlineOpaqueHash("${it.providerId}:${it.accountId}") }
            runtime.sessions.snapshot.collect { account ->
                val next = account.account?.let { onlineOpaqueHash("${it.providerId}:${it.accountId}") }
                val old = previous
                previous = next
                if (old != null && old != next && account.validity != SessionValidity.RESTORED_UNVERIFIED && account.issue != AuthIssue.CLOSED) {
                    withContext(NonCancellable) { clearHash(old) }
                }
            }
        }
        created
    }

    /** The logout UI waits for this exact account's cache cleanup; it does not create an account client. */
    suspend fun clearAccount(accountId: String): OnlineLyricsIssue? {
        require(accountId.isNotBlank())
        return withContext(NonCancellable) { clearHash(onlineOpaqueHash("netease:$accountId")) }
    }

    suspend fun measureDiskCache(): StorageCategory = cache.measureStorage()
    suspend fun clearDiskCache(): StorageCategory = cache.clearStorage()

    private suspend fun clearHash(hash: String): OnlineLyricsIssue? {
        val issue = try {
            cache.clearOwner(hash)
            null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) { OnlineLyricsIssue.READ_FAILED }
        catch (_: Exception) { OnlineLyricsIssue.READ_FAILED }
        catch (_: OutOfMemoryError) { OnlineLyricsIssue.MEMORY_LIMIT }
        cleanupIssue = issue
        return issue
    }

    private suspend fun networkAvailable(): Boolean = withContext(Dispatchers.IO) {
        val manager = application.getSystemService(ConnectivityManager::class.java) ?: return@withContext false
        val network = manager.activeNetwork ?: return@withContext false
        manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    override fun close() {
        val retained = synchronized(memory) {
            if (closed) return
            closed = true
            repository.value.also { repository.value = null }
        }
        try { retained?.close() } finally { scope.cancel() }
    }

    private data class SourceEpoch(val accountRevision: Long, val opened: String?, val configurationGeneration: Long?,
        val enabled: Boolean?, val changing: Boolean?, val issue: io.github.onlinemo.meloxwatch.data.meting.MetingIssue?)
}
