package io.github.onlinemo.meloxwatch.playback.resources

import android.net.Uri
import androidx.core.net.toUri
import io.github.onlinemo.meloxwatch.data.network.HttpTransportGate
import io.github.onlinemo.meloxwatch.data.network.NetworkException
import io.github.onlinemo.meloxwatch.data.network.NetworkIssue
import io.github.onlinemo.meloxwatch.data.network.PendingHttpUse
import io.github.onlinemo.meloxwatch.data.network.ServiceHttpContext
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueWindow
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackException
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackFailure
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl

/** Causes contain only a stable issue; HTTP URLs, addresses and account IDs never enter errors. */
internal class MediaTransportException(val issue: QueuePlaybackIssue) : IOException(issue.name)

enum class ResourceMatch { PROVIDER_REPORTED, SERVICE_UNVERIFIED }
enum class ResourceCompleteness { FULL_REPORTED, PREVIEW_REPORTED, UNKNOWN }

data class PlaybackResourceStatus(
    val resolverId: String,
    val match: ResourceMatch,
    val completeness: ResourceCompleteness,
    val requestedQuality: StreamingQuality,
    val reportedQuality: StreamingQuality?,
    val reportedBitrate: Long?,
    val configurationGeneration: Long? = null,
) {
    init {
        require(resolverId.matches(Regex("[a-z][a-z0-9-]{0,63}")))
        require(reportedBitrate == null || reportedBitrate in 1..100_000_000L)
        require(configurationGeneration == null || configurationGeneration >= 0L)
    }
    override fun toString(): String = "PlaybackResourceStatus(resolver=$resolverId, match=$match, completeness=$completeness)"
}

/** 原生只能 HTTPS；显式服务上下文检查每次请求和跳转，鉴权不会成为全局默认值。 */
internal class MediaHttpTarget private constructor(val url: HttpUrl, internal val serviceContext: ServiceHttpContext? = null) {
    fun checked(): MediaHttpTarget = transport { HttpTransportGate.check(url, serviceContext); this }
    fun headers(): Map<String, String> = transport { serviceContext?.headersFor(url).orEmpty() }
    fun redirect(location: String): MediaHttpTarget = transport {
        MediaHttpTarget(HttpTransportGate.redirect(url, location, serviceContext), serviceContext)
    }
    fun ownPending(cancel: () -> Unit): PendingHttpUse? = transport { serviceContext?.ownPending(cancel) }
    fun commitOpened(pending: PendingHttpUse?, accept: () -> Unit) = transport {
        if (pending == null) accept() else pending.commitOpened(accept)
    }
    override fun toString(): String = "MediaHttpTarget(redacted)"
    companion object {
        fun https(value: String): MediaHttpTarget = transport { MediaHttpTarget(HttpTransportGate.parseHttps(value)) }
        fun serviceReference(value: String, responseUrl: HttpUrl, context: ServiceHttpContext): MediaHttpTarget = transport {
            MediaHttpTarget(HttpTransportGate.redirect(responseUrl, value, context), context)
        }
        private inline fun <T> transport(action: () -> T): T = try { action() }
        catch (_: CancellationException) { throw MediaTransportException(QueuePlaybackIssue.RESOURCE_EXPIRED) }
        catch (error: NetworkException) { throw MediaTransportException(
            if (error.issue == NetworkIssue.BUSY) QueuePlaybackIssue.BUSY else QueuePlaybackIssue.RESOURCE_INVALID) }
    }
}

internal enum class PlaybackAssociatedKind { ARTWORK, LYRICS }
internal class PlaybackAssociatedResources(val artwork: MediaHttpTarget?, val lyrics: MediaHttpTarget?) {
    fun target(kind: PlaybackAssociatedKind): MediaHttpTarget? = when (kind) {
        PlaybackAssociatedKind.ARTWORK -> artwork
        PlaybackAssociatedKind.LYRICS -> lyrics
    }
    override fun toString(): String = "PlaybackAssociatedResources(redacted)"
}

/** 只证明唯一媒体 transport 已接受响应；解码/推进/首声仍以会话及实际输出观察为准。 */
internal class OpenedPlaybackResourceMetadata(
    private val resourceKey: String,
    val queueEntryId: String,
    val providerId: String,
    val trackId: String,
    val accountId: String?,
    val resolverId: String,
    val configurationGeneration: Long?,
    val operationId: String,
    val operationSequence: Long,
    private val accountCurrent: () -> Boolean,
) {
    fun belongsTo(state: OnlineResourceState): Boolean = resourceKey == state.key
    fun isCurrentAccount(): Boolean = accountCurrent()
    override fun toString(): String = "OpenedPlaybackResourceMetadata(redacted)"
}

internal class OnlineResourceState(
    val owner: RuntimePlaybackResources,
    val key: String,
    val entry: QueueEntry,
    val target: MediaHttpTarget,
    val budget: PlaybackResolutionBudget,
    val expiresElapsedMs: Long?,
    val status: PlaybackResourceStatus,
    val accountCurrent: () -> Boolean,
    val configurationCurrent: () -> Boolean,
    val associated: PlaybackAssociatedResources?,
) {
    var references = 1
    var revoked = false
    val cancellations = mutableSetOf<() -> Unit>()
    val opened = AtomicBoolean()
    fun checkAccount() {
        if (revoked || !accountCurrent()) throw MediaTransportException(QueuePlaybackIssue.LOGIN_REQUIRED)
    }
    fun checkNewAcquisition() {
        checkAccount()
        if (!configurationCurrent()) throw MediaTransportException(QueuePlaybackIssue.RESOURCE_EXPIRED)
        if (expiresElapsedMs != null && budget.elapsedNowMs() >= expiresElapsedMs) {
            throw MediaTransportException(QueuePlaybackIssue.RESOURCE_EXPIRED)
        }
    }
    fun checkPreparation() {
        checkAccount()
        // 已开健康流保留配置；尚未交接的资源必须在队列提交/播放器所有者检查时拒旧代次。
        if (!opened.get() && !configurationCurrent()) throw MediaTransportException(QueuePlaybackIssue.RESOURCE_EXPIRED)
    }
    override fun toString(): String = "OnlineResourceState(redacted)"
}

/** One reference is owned by a prepared/loaded window or an actual media read, never by the database. */
class RuntimePlaybackResource internal constructor(
    val entry: QueueEntry,
    internal val online: OnlineResourceState? = null,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    val isOnline: Boolean get() = online != null
    val status: PlaybackResourceStatus? get() = online?.status
    val budget: PlaybackResolutionBudget? get() = online?.budget
    val mediaUri: Uri get() = if (online == null) (requireNotNull(entry.location).uri).toUri()
        else Uri.Builder().scheme(RuntimePlaybackResources.SCHEME).authority("resource").appendPath(online.key).build()

    fun retain(forEntry: QueueEntry = entry): RuntimePlaybackResource {
        if (closed.get() || forEntry.queueEntryId != entry.queueEntryId || forEntry.trackKey != entry.trackKey ||
            forEntry.accountId != entry.accountId || forEntry.location != entry.location) {
            throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.SOURCE_CHANGED, entry.queueEntryId))
        }
        try { if (online != null) online.owner.retain(online, newAcquisition = false) }
        catch (error: MediaTransportException) { throw QueuePlaybackException(QueuePlaybackFailure(error.issue, entry.queueEntryId)) }
        return RuntimePlaybackResource(forEntry, online)
    }

    fun checkCurrent() {
        if (closed.get()) throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.SOURCE_CHANGED, entry.queueEntryId))
        try { if (online != null) online.owner.checkPrepared(online) }
        catch (error: MediaTransportException) { throw QueuePlaybackException(QueuePlaybackFailure(error.issue, entry.queueEntryId)) }
    }

    override fun close() { if (closed.compareAndSet(false, true) && online != null) online.owner.release(online) }
    override fun toString(): String = "RuntimePlaybackResource(online=$isOnline, redacted)"
}

class PreparedPlaybackWindow(
    val window: QueueWindow,
    val resources: Map<String, RuntimePlaybackResource>,
) : AutoCloseable {
    val generationId get() = window.generationId
    val queueRevision get() = window.queueRevision
    val totalCount get() = window.totalCount
    val current get() = window.current
    init { require(resources.keys == window.entries.map { it.queueEntryId }.toSet()) }
    override fun close() { resources.values.forEach(RuntimePlaybackResource::close) }
    override fun toString(): String = "PreparedPlaybackWindow(redacted)"
}

/**
 * At most two three-item windows coexist during preflight. URL strings are never Room values or MediaItems.
 * Invalidation runs only short memory actions; bodies close on their owning media worker.
 */
class RuntimePlaybackResources {
    private val memory = Any()
    private val states = mutableMapOf<String, OnlineResourceState>()
    private val physicalMedia = AtomicBoolean()
    private val mutableOpenedResource = MutableStateFlow<OpenedPlaybackResourceMetadata?>(null)
    internal val openedResource = mutableOpenedResource.asStateFlow()

    /** Application-owned admission survives service/backend recreation and a noncooperative old reader. */
    internal fun acquirePhysicalMedia(): PhysicalMediaLease {
        if (!physicalMedia.compareAndSet(false, true)) throw MediaTransportException(QueuePlaybackIssue.BUSY)
        return PhysicalMediaLease { physicalMedia.set(false) }
    }

    internal class PhysicalMediaLease(private val release: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean()
        override fun close() { if (closed.compareAndSet(false, true)) release() }
    }

    internal fun register(
        entry: QueueEntry,
        target: MediaHttpTarget,
        budget: PlaybackResolutionBudget,
        expiresElapsedMs: Long?,
        status: PlaybackResourceStatus,
        accountCurrent: () -> Boolean,
        configurationCurrent: () -> Boolean = { true },
        associated: PlaybackAssociatedResources? = null,
    ): RuntimePlaybackResource = synchronized(memory) {
        if (states.size >= MAX_RESOURCES) throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.BUSY, entry.queueEntryId))
        if (entry.accountId == null) throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.LOGIN_REQUIRED, entry.queueEntryId))
        val key = UUID.randomUUID().toString()
        val state = OnlineResourceState(this, key, entry, target, budget, expiresElapsedMs, status, accountCurrent, configurationCurrent, associated)
        state.checkNewAcquisition()
        states[key] = state
        RuntimePlaybackResource(entry, state)
    }

    internal fun retain(state: OnlineResourceState, newAcquisition: Boolean) = synchronized(memory) {
        checkLocked(state, newAcquisition)
        if (state.references == Int.MAX_VALUE) throw MediaTransportException(QueuePlaybackIssue.BUSY)
        state.references++
    }

    internal fun acquire(uri: Uri): OnlineResourceState = synchronized(memory) {
        if (uri.scheme != SCHEME || uri.authority != "resource" || uri.pathSegments.size != 1 ||
            uri.query != null || uri.fragment != null) throw MediaTransportException(QueuePlaybackIssue.RESOURCE_INVALID)
        val key = uri.lastPathSegment ?: throw MediaTransportException(QueuePlaybackIssue.RESOURCE_INVALID)
        val state = states[key] ?: throw MediaTransportException(QueuePlaybackIssue.RESOURCE_EXPIRED)
        retain(state, newAcquisition = true)
        state
    }

    internal fun check(state: OnlineResourceState, newAcquisition: Boolean) = synchronized(memory) { checkLocked(state, newAcquisition) }
    internal fun checkPrepared(state: OnlineResourceState) = synchronized(memory) {
        checkLocked(state, newAcquisition = false)
        state.checkPreparation()
    }
    /** 当前可见附属资源入口保留唯一已开项；未开/旧配置/不同实例不能触发请求。 */
    internal fun retainOpened(entryId: String): RuntimePlaybackResource? = synchronized(memory) {
        val opened = mutableOpenedResource.value ?: return@synchronized null
        val state = states.values.firstOrNull { opened.belongsTo(it) && it.entry.queueEntryId == entryId }
            ?: return@synchronized null
        if (state.associated == null) return@synchronized null
        retain(state, newAcquisition = true)
        RuntimePlaybackResource(state.entry, state)
    }
    private fun checkLocked(state: OnlineResourceState, newAcquisition: Boolean) {
        if (state.owner !== this || states[state.key] !== state || state.references <= 0) {
            throw MediaTransportException(QueuePlaybackIssue.RESOURCE_EXPIRED)
        }
        if (newAcquisition) state.checkNewAcquisition() else state.checkAccount()
    }

    internal fun attachCancellation(state: OnlineResourceState, cancel: () -> Unit) = synchronized(memory) {
        checkLocked(state, newAcquisition = false)
        state.cancellations.add(cancel)
    }
    internal fun detachCancellation(state: OnlineResourceState, cancel: () -> Unit) = synchronized(memory) { state.cancellations.remove(cancel) }

    internal fun release(state: OnlineResourceState) {
        val cancel = synchronized(memory) {
            if (state.owner !== this || states[state.key] !== state || state.references <= 0) return
            state.references--
            if (state.references != 0) return
            state.revoked = true
            states.remove(state.key)
            state.cancellations.toList().also { state.cancellations.clear() }
        }
        cancel.forEach { it() }
    }

    fun invalidateStaleAccounts() {
        val cancel = synchronized(memory) {
            states.values.filter { !it.accountCurrent() }.flatMap { state ->
                state.revoked = true
                state.cancellations.toList().also { state.cancellations.clear() }
            }
        }
        cancel.forEach { it() }
    }

    // 调用点在配置锁之外；唯一物理媒体租约保证不会与第二个已开流竞争投影。
    internal fun notifyOpened(state: OnlineResourceState) {
        mutableOpenedResource.value = OpenedPlaybackResourceMetadata(state.key, state.entry.queueEntryId,
            state.entry.trackKey.providerId, state.entry.trackKey.trackId, state.entry.accountId, state.status.resolverId,
            state.status.configurationGeneration, state.budget.operationId, state.budget.operationSequence, state.accountCurrent)
    }
    internal fun notifyClosed(state: OnlineResourceState) {
        if (mutableOpenedResource.value?.belongsTo(state) == true) mutableOpenedResource.value = null
    }

    companion object {
        const val SCHEME = "meloxstream"
        private const val MAX_RESOURCES = 6
    }
}
