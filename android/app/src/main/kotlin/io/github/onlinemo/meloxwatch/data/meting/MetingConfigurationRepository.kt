package io.github.onlinemo.meloxwatch.data.meting

import io.github.onlinemo.meloxwatch.data.network.PendingHttpUse
import io.github.onlinemo.meloxwatch.data.network.ServiceHttpContext
import io.github.onlinemo.meloxwatch.data.network.NetworkException
import io.github.onlinemo.meloxwatch.data.network.NetworkIssue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 一个应用所有者；配置锁只包短内存操作，磁盘和取消 callback 都在锁外。 */
internal class MetingConfigurationRepository(
    private val storeFactory: suspend () -> MetingConfigurationStore,
) : AutoCloseable {
    private val memory = Any()
    private val mutations = Mutex()
    private var store: MetingConfigurationStore? = null
    private var active: MetingStoredConfiguration? = null
    private var loaded = false
    private var changing = false
    private var closed = false
    private var generation = 0L
    private val pending = mutableSetOf<PendingUse>()
    private val mutableSnapshot = MutableStateFlow(MetingConfigurationSnapshot())
    val snapshot = mutableSnapshot.asStateFlow()

    suspend fun restore(): MetingConfigurationSnapshot {
        if (synchronized(memory) { loaded }) return snapshot.value
        return mutations.withLock {
            if (synchronized(memory) { loaded }) return@withLock snapshot.value
            val restored = try { storage().load() }
            catch (error: MetingException) {
                currentCoroutineContext().ensureActive()
                synchronized(memory) {
                    requireOpen()
                    loaded = true
                    active = null
                    publish(error.issue)
                    return@withLock snapshot.value
                }
            }
            currentCoroutineContext().ensureActive()
            synchronized(memory) {
                requireOpen()
                active = restored
                loaded = true
                generation = maxOf(generation, restored?.generation ?: 0L)
                publish()
                snapshot.value
            }
        }
    }

    suspend fun edit(expectedGeneration: Long): MetingSettingsDraft {
        restore()
        return synchronized(memory) {
            requireGeneration(expectedGeneration)
            val saved = active
            // 编辑需重新输入完整地址；遮蔽字段不能把旧查询秘密悄悄带入用户修改后的新 origin。
            MetingSettingsDraft("", saved?.endpoint?.mode ?: MetingEndpointMode.ENDPOINT,
                saved?.enabled ?: false, saved?.allowCleartext ?: false,
                if (saved?.credential == null) MetingCredentialEdit.CLEAR else MetingCredentialEdit.KEEP,
                saved?.credential?.name ?: "Authorization")
        }
    }

    suspend fun save(expectedGeneration: Long, draft: MetingSettingsDraft): MetingConfigurationSnapshot {
        restore()
        val endpoint = MetingEndpoint.checked(draft.endpoint, draft.mode, draft.allowCleartext)
        return mutations.withLock {
            val previous = synchronized(memory) { requireGeneration(expectedGeneration); active }
            val credential = when (draft.credentialEdit) {
                MetingCredentialEdit.CLEAR -> null
                MetingCredentialEdit.REPLACE -> MetingServiceCredential.checked(draft.headerName, draft.credentialInput)
                MetingCredentialEdit.KEEP -> {
                    if (previous?.credential != null && previous.origin != endpoint.origin) {
                        throw MetingException(MetingIssue.CREDENTIAL_ORIGIN_CHANGED)
                    }
                    previous?.credential
                }
            }
            // 本次完整 URL 输入是唯一地址来源；不从旧配置补入新 origin 的参数或 headers。
            currentCoroutineContext().ensureActive()
            val next = beginChange(expectedGeneration)
            commit(next, MetingStoredConfiguration(next, endpoint, draft.enabled, draft.allowCleartext, credential))
        }
    }

    suspend fun setEnabled(expectedGeneration: Long, enabled: Boolean): MetingConfigurationSnapshot {
        restore()
        return mutations.withLock {
            val saved = synchronized(memory) {
                requireGeneration(expectedGeneration)
                active ?: throw MetingException(MetingIssue.NOT_CONFIGURED)
            }
            currentCoroutineContext().ensureActive()
            val next = beginChange(expectedGeneration)
            commit(next, MetingStoredConfiguration(next, saved.endpoint, enabled, saved.allowCleartext, saved.credential))
        }
    }

    /** 用户确认删除仅清本服务文件；健康已开媒体和本地库都不在这次清理范围。 */
    suspend fun delete(expectedGeneration: Long): MetingConfigurationSnapshot {
        restore()
        return mutations.withLock {
            currentCoroutineContext().ensureActive()
            val next = beginChange(expectedGeneration)
            try {
                withContext(NonCancellable) { storage().clear() }
                synchronized(memory) {
                    if (!closed && generation == next) { changing = false; active = null; publish() }
                    snapshot.value
                }
            } catch (error: MetingException) { failedChange(next, error.issue); throw error }
        }
    }

    suspend fun capture(requireEnabled: Boolean): MetingConfigurationUse? {
        restore()
        return synchronized(memory) {
            requireOpen()
            if (changing) throw MetingException(MetingIssue.BUSY)
            snapshot.value.issue?.let { throw MetingException(it) }
            val configuration = active ?: return@synchronized null
            if (requireEnabled && !configuration.enabled) return@synchronized null
            MetingConfigurationUse(this, configuration, generation, requireEnabled)
        }
    }

    internal fun current(use: MetingConfigurationUse): Boolean = synchronized(memory) { currentLocked(use) }

    internal fun context(use: MetingConfigurationUse, privateParameters: List<Pair<String, String?>> = use.configuration.endpoint.privateParameters()): ServiceHttpContext = ServiceHttpContext(
        use.configuration.origin, use.configuration.allowCleartext,
        use.configuration.credential?.headers().orEmpty(), privateParameters,
        current = { current(use) }, registerPending = { cancel -> register(use, cancel) },
    )

    private fun register(use: MetingConfigurationUse, cancel: () -> Unit): PendingHttpUse = synchronized(memory) {
        if (!currentLocked(use)) throw CancellationException("Service configuration changed")
        if (pending.size >= MetingLimits.PENDING_REQUESTS) throw NetworkException(NetworkIssue.BUSY)
        PendingUse(use, cancel).also { pending.add(it) }
    }

    private fun currentLocked(use: MetingConfigurationUse): Boolean = !closed && !changing &&
        use.owner === this && use.generation == generation && active === use.configuration &&
        (!use.requireEnabled || use.configuration.enabled)

    private suspend fun storage(): MetingConfigurationStore {
        store?.let { return it }
        val created = storeFactory()
        currentCoroutineContext().ensureActive()
        return created.also { store = it }
    }

    private fun beginChange(expectedGeneration: Long): Long {
        val cancelled = synchronized(memory) {
            requireGeneration(expectedGeneration)
            if (generation == Long.MAX_VALUE) throw MetingException(MetingIssue.CLOSED)
            generation += 1L
            changing = true
            active = null
            pending.toList().also { pending.clear(); publish() }
        }
        cancelled.forEach(PendingUse::cancelNow)
        return synchronized(memory) { generation }
    }

    private suspend fun commit(next: Long, configuration: MetingStoredConfiguration): MetingConfigurationSnapshot {
        try {
            // 已接受的有界密文提交不能因离页半写；Keystore 系统调用的墙钟时长仍需设备实测。
            withContext(NonCancellable) { storage().save(configuration) }
            return synchronized(memory) {
                if (!closed && generation == next) { active = configuration; changing = false; publish() }
                snapshot.value
            }
        } catch (error: MetingException) { failedChange(next, error.issue); throw error }
    }

    private fun failedChange(next: Long, issue: MetingIssue) = synchronized(memory) {
        if (!closed && generation == next) {
            // 未确认存储结果时停止未来服务请求，保留旧密文供明确重读/修复，不启用旧配置重试。
            active = null
            changing = false
            publish(issue)
        }
    }

    suspend fun reload(): MetingConfigurationSnapshot = mutations.withLock {
        val expected = synchronized(memory) {
            requireOpen()
            if (changing) throw MetingException(MetingIssue.BUSY)
            generation
        }
        val next = beginChange(expected)
        val restored = try { storage().load() }
        catch (error: MetingException) {
            failedChange(next, error.issue)
            throw error
        } catch (cancelled: CancellationException) {
            failedChange(next, MetingIssue.CONFIGURATION_CHANGED)
            throw cancelled
        }
        try { currentCoroutineContext().ensureActive() }
        catch (cancelled: CancellationException) { failedChange(next, MetingIssue.CONFIGURATION_CHANGED); throw cancelled }
        synchronized(memory) {
            requireOpen()
            generation = maxOf(generation, restored?.generation ?: 0L)
            active = restored
            loaded = true
            changing = false
            publish()
            snapshot.value
        }
    }

    private fun requireGeneration(expected: Long) {
        requireOpen()
        if (changing) throw MetingException(MetingIssue.BUSY)
        if (expected != generation) throw MetingException(MetingIssue.CONFIGURATION_CHANGED)
    }
    private fun requireOpen() { if (closed) throw MetingException(MetingIssue.CLOSED) }
    private fun publish(issue: MetingIssue? = null) {
        val value = active
        mutableSnapshot.value = MetingConfigurationSnapshot(generation, loaded, value != null, value?.enabled ?: false,
            value?.endpoint?.hostLabel, value?.endpoint?.mode, value?.allowCleartext ?: false,
            value?.credential != null, changing, issue)
    }

    override fun close() {
        val cancelled = synchronized(memory) {
            if (closed) return
            closed = true
            active = null
            changing = false
            pending.toList().also { pending.clear(); publish(MetingIssue.CLOSED) }
        }
        cancelled.forEach(PendingUse::cancelNow)
    }

    private inner class PendingUse(val use: MetingConfigurationUse, private val cancel: () -> Unit) : PendingHttpUse {
        private val open = AtomicBoolean(true)
        fun cancelNow() { cancel() }
        override fun commitOpened(accept: () -> Unit) = synchronized(memory) {
            if (!open.get() || this !in pending || !currentLocked(use)) throw CancellationException("Service configuration changed")
            // accept 只发布已取得响应的内存所有权；与代次变更有确定先后，不能在这里读 body。
            accept()
            pending.remove(this)
            open.set(false)
        }
        override fun close() {
            if (open.compareAndSet(true, false)) synchronized(memory) { pending.remove(this) }
        }
    }
}

/** 原始配置留在适配层；当前流 read 不调用此配置谓词，新获取和元数据提交必须调用。 */
internal class MetingConfigurationUse(
    val owner: MetingConfigurationRepository,
    val configuration: MetingStoredConfiguration,
    val generation: Long,
    val requireEnabled: Boolean,
) {
    val context: ServiceHttpContext by lazy { owner.context(this) }
    fun contextFor(trackId: String): ServiceHttpContext = owner.context(this, configuration.endpoint.privateParameters(trackId))
    fun isCurrent(): Boolean = owner.current(this)
    fun checkCurrent() { if (!isCurrent()) throw CancellationException("Service configuration changed") }
    override fun toString(): String = "MetingConfigurationUse(redacted)"
}
