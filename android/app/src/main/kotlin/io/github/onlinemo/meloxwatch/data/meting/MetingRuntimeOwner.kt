package io.github.onlinemo.meloxwatch.data.meting

import android.content.Context
import io.github.onlinemo.meloxwatch.data.auth.AuthException
import io.github.onlinemo.meloxwatch.data.auth.AuthIssue
import io.github.onlinemo.meloxwatch.data.auth.SessionRepository
import io.github.onlinemo.meloxwatch.data.network.BoundedHttpGetClient
import io.github.onlinemo.meloxwatch.data.network.HttpGetKind
import io.github.onlinemo.meloxwatch.data.network.HttpGetLimits
import io.github.onlinemo.meloxwatch.data.network.HttpTransportGate
import io.github.onlinemo.meloxwatch.data.network.NetworkException
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackException
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackFailure
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue
import io.github.onlinemo.meloxwatch.playback.resources.OnlinePlaybackResolver
import io.github.onlinemo.meloxwatch.playback.resources.PlaybackAssociatedKind
import io.github.onlinemo.meloxwatch.playback.resources.PlaybackAssociatedResources
import io.github.onlinemo.meloxwatch.playback.resources.PlaybackResolutionBudget
import io.github.onlinemo.meloxwatch.playback.resources.PlaybackResourceStatus
import io.github.onlinemo.meloxwatch.playback.resources.ResourceMatch
import io.github.onlinemo.meloxwatch.playback.resources.RuntimePlaybackResource
import io.github.onlinemo.meloxwatch.playback.resources.RuntimePlaybackResources
import io.github.onlinemo.meloxwatch.playback.resources.StreamingQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 构造只分配内存；配置读取由明确设置/测试或允许的原生失败触发。
 * GET 客户端属于应用，服务关闭不关闭共享原生封面通道，也不释放播放后端。
 */
internal class MetingRuntimeOwner(
    context: Context,
    private val resources: RuntimePlaybackResources,
    private val httpProvider: () -> BoundedHttpGetClient,
    storeFactory: (suspend () -> MetingConfigurationStore)? = null,
    scope: CoroutineScope? = null,
) : AutoCloseable {
    private val application = context.applicationContext
    private val ownsScope = scope == null
    private val workScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val configuration = MetingConfigurationRepository(storeFactory ?: { AtomicMetingConfigurationStore.create(application) })
    private val flights = MetingRequestFlights(workScope)
    private val api by lazy { MetingProtocolApi(httpProvider()) }

    /** 正常原生成功不调用此 supplier；未配置/关闭时返回 null，保留原生的真实失败或试听。 */
    suspend fun playbackResolver(sessions: SessionRepository): OnlinePlaybackResolver? {
        val use = try { configuration.capture(requireEnabled = true) ?: return null }
        catch (error: MetingException) { throw QueuePlaybackException(QueuePlaybackFailure(metingPlaybackIssue(error.issue))) }
        return MetingPlaybackResolver(sessions, resources, use, flights, { api })
    }

    /** 显式协议测试不要求网易云登录，不发其 Cookie；解析结果不冒充已解码或已听到。 */
    suspend fun test(trackId: String): MetingTestReport = withContext(Dispatchers.IO) {
        requireMetingTrackId(trackId)
        val use = configuration.capture(requireEnabled = false) ?: throw MetingException(MetingIssue.NOT_CONFIGURED)
        val budget = PlaybackResolutionBudget()
        budget.claimSupplementalRequest()
        try {
            val song = flights.resolve(MetingRequestKey(trackId, null, null, use.generation, budget.operationId), budget) {
                api.resolve(trackId, use, budget)
            }
            currentCoroutineContext().ensureActive()
            use.checkCurrent()
            MetingTestReport(addressValid = true, protocolCompatible = true, resourceResolved = true,
                match = song.match, completeness = song.completeness, artworkIssue = song.artworkIssue, lyricsIssue = song.lyricsIssue)
        } catch (error: MetingException) {
            MetingTestReport(addressValid = true, protocolCompatible = error.protocolCompatible, issue = error.issue)
        }
        catch (error: QueuePlaybackException) {
            MetingTestReport(addressValid = true, issue = if (error.failure.issue == QueuePlaybackIssue.NETWORK_TIMEOUT)
                MetingIssue.NETWORK_TIMEOUT else MetingIssue.PROTOCOL_INVALID)
        }
    }

    /** 附属资源独立按需读取，不阻首声；本地或无关联 target 的资源在创建 GET client 前返回。 */
    suspend fun readOpenedAssociated(entryId: String, kind: PlaybackAssociatedKind, maxResponseBytes: Int? = null): ByteArray? {
        val retained = try { resources.retainOpened(entryId) ?: return null }
        catch (error: io.github.onlinemo.meloxwatch.playback.resources.MediaTransportException) {
            throw QueuePlaybackException(QueuePlaybackFailure(error.issue, entryId))
        }
        return retained.use {
            val bytes = readAssociated(it, kind, maxResponseBytes)
            val state = checkNotNull(it.online)
            if (resources.openedResource.value?.belongsTo(state) != true) throw CancellationException("Current associated resource changed")
            bytes
        }
    }

    suspend fun readAssociated(resource: RuntimePlaybackResource, kind: PlaybackAssociatedKind,
        maxResponseBytes: Int? = null): ByteArray? = withContext(Dispatchers.IO) {
        val state = resource.online ?: return@withContext null
        val original = state.associated?.target(kind) ?: return@withContext null
        val context = original.serviceContext ?: return@withContext null
        val maximum = if (kind == PlaybackAssociatedKind.ARTWORK) 16 * 1024 * 1024 else 1024 * 1024
        require(maxResponseBytes == null || maxResponseBytes in 1..maximum)
        val responseLimit = maxResponseBytes ?: maximum
        val caller = currentCoroutineContext()[Job] ?: throw MetingException(MetingIssue.CLOSED)
        val retained = resource.retain()
        val cancelForAccount: () -> Unit = { caller.cancel(CancellationException("Online resource changed")) }
        var attached = false
        try {
            state.owner.attachCancellation(state, cancelForAccount)
            attached = true
            retained.checkCurrent()
            context.requireCurrent()
            val budget = PlaybackResolutionBudget()
            var target = original.url
            while (true) {
                currentCoroutineContext().ensureActive()
                val remaining = budget.remainingMs()
                val response = httpProvider().get(target, HttpGetLimits(remaining, minOf(3_000L, remaining),
                    responseLimit,
                    if (kind == PlaybackAssociatedKind.ARTWORK) HttpGetKind.IMAGE else HttpGetKind.TEXT), context)
                if (response.location != null) {
                    budget.claimMetadataRedirect()
                    target = HttpTransportGate.redirect(response.url, response.location, context)
                    continue
                }
                retained.checkCurrent()
                context.requireCurrent()
                currentCoroutineContext().ensureActive()
                return@withContext response.bytes
            }
            @Suppress("UNREACHABLE_CODE")
            null
        } catch (error: NetworkException) { throw MetingException(metingNetworkIssue(error)) }
        finally {
            if (attached) state.owner.detachCancellation(state, cancelForAccount)
            retained.close()
        }
    }

    override fun close() {
        try { configuration.close() } finally {
            flights.close()
            if (ownsScope) workScope.cancel()
        }
    }
}

/** 账户 token 只用于权限谓词/取消；服务协议 API 仅接收曲目 ID、独立配置及原预算。 */
private class MetingPlaybackResolver(
    private val sessions: SessionRepository,
    private val resources: RuntimePlaybackResources,
    private val use: MetingConfigurationUse,
    private val flights: MetingRequestFlights,
    private val api: () -> MetingProtocolApi,
) : OnlinePlaybackResolver {
    override suspend fun resolve(entry: QueueEntry, budget: PlaybackResolutionBudget, quality: StreamingQuality): RuntimePlaybackResource {
        if (entry.trackKey.providerId != "netease") throw playbackFailure(QueuePlaybackIssue.SOURCE_UNSUPPORTED, entry)
        var pending: RuntimePlaybackResource? = null
        try {
            return budget.within(onAbandoned = RuntimePlaybackResource::close) {
                sessions.authenticated { _, token ->
                    if (token.account.providerId != entry.trackKey.providerId || token.account.accountId != entry.accountId) {
                        throw playbackFailure(QueuePlaybackIssue.LOGIN_REQUIRED, entry)
                    }
                    use.checkCurrent()
                    val song = flights.resolve(MetingRequestKey(entry.trackKey.trackId, token.account.accountId,
                        token.generation, use.generation, budget.operationId), budget) {
                        api().resolve(entry.trackKey.trackId, use, budget)
                    }
                    currentCoroutineContext().ensureActive()
                    use.checkCurrent()
                    if (!sessions.isCurrent(token)) throw CancellationException("Account session changed")
                    resources.register(entry, song.target, budget, expiresElapsedMs = null,
                        status = PlaybackResourceStatus("meting-custom", ResourceMatch.SERVICE_UNVERIFIED, song.completeness,
                            quality, reportedQuality = null, reportedBitrate = null, configurationGeneration = use.generation),
                        accountCurrent = { sessions.isCurrent(token) }, configurationCurrent = use::isCurrent,
                        associated = PlaybackAssociatedResources(song.artwork, song.lyrics)).also { pending = it }
                }
            }.also { pending = null }
        } catch (error: MetingException) { throw playbackFailure(metingPlaybackIssue(error.issue), entry) }
        catch (error: AuthException) { throw playbackFailure(when (error.issue) {
            AuthIssue.LOGIN_REQUIRED -> QueuePlaybackIssue.LOGIN_REQUIRED
            AuthIssue.BUSY -> QueuePlaybackIssue.BUSY
            AuthIssue.CLOSED -> QueuePlaybackIssue.CLOSED
            else -> QueuePlaybackIssue.SESSION_UNAVAILABLE
        }, entry) }
        finally { pending?.close() }
    }

    private fun playbackFailure(issue: QueuePlaybackIssue, entry: QueueEntry) = QueuePlaybackException(QueuePlaybackFailure(issue, entry.queueEntryId))
}

internal fun metingPlaybackIssue(issue: MetingIssue): QueuePlaybackIssue = when (issue) {
        MetingIssue.NETWORK_UNAVAILABLE -> QueuePlaybackIssue.NETWORK_UNAVAILABLE
        MetingIssue.NETWORK_TIMEOUT -> QueuePlaybackIssue.NETWORK_TIMEOUT
        MetingIssue.CERTIFICATE -> QueuePlaybackIssue.NETWORK_CERTIFICATE
        MetingIssue.RATE_LIMITED -> QueuePlaybackIssue.NETWORK_RATE_LIMITED
        MetingIssue.AUTH_REQUIRED -> QueuePlaybackIssue.RESOURCE_AUTH_REQUIRED
        MetingIssue.EMPTY_RESOURCE, MetingIssue.NOT_CONFIGURED, MetingIssue.DISABLED -> QueuePlaybackIssue.RESOURCE_UNAVAILABLE
        MetingIssue.CONFIGURATION_CHANGED, MetingIssue.CREDENTIAL_ORIGIN_CHANGED -> QueuePlaybackIssue.SOURCE_CHANGED
        MetingIssue.BUSY -> QueuePlaybackIssue.BUSY
        MetingIssue.CLOSED -> QueuePlaybackIssue.CLOSED
        MetingIssue.KEY_UNAVAILABLE, MetingIssue.CONFIGURATION_CORRUPT, MetingIssue.STORAGE_UNAVAILABLE -> QueuePlaybackIssue.SOURCE_UNAVAILABLE
        MetingIssue.REDIRECT_LIMIT -> QueuePlaybackIssue.RESOURCE_REDIRECT
        else -> QueuePlaybackIssue.RESOURCE_INVALID
}
