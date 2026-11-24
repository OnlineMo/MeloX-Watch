package io.github.onlinemo.meloxwatch.data.database

import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Room 2.8.5 在公开连接及生成的 DAO 调用中替换 caller Job；取消原调用者不会结束真实数据库任务。
 * 保留 Room 的 dispatcher、连接限域和失效通知，仅将调用者取消转发给执行中的子任务。
 * 桥接持续到 Room 回收连接后，不能在挂起时移除，也不能取消数据库的 SupervisorJob。
 */
internal suspend fun <T> withRoomCallerCancellation(block: suspend () -> T): T {
    val context = currentCoroutineContext()
    context.ensureActive()
    val caller = context[Job]
    val inherited = context[RoomWriterCancellation]
    if (inherited != null && inherited.canReuse(caller)) {
        inherited.ensureReady()
        return block()
    }

    val bridge = RoomWriterCancellation(caller)
    var blockFailure: Throwable? = null
    var failure: Throwable? = null
    try {
        val result = withContext(bridge) {
            bridge.enter(currentCoroutineContext()[Job])
            try {
                bridge.ensureReady()
                block()
            } catch (error: Throwable) {
                // withContext 的栈恢复可能复制异常；在此保留原业务错误及 cleanup 的身份。
                blockFailure = error
                throw error
            }
        }
        bridge.ensureReady()
        return result
    } catch (error: Throwable) {
        // 连接池只收到普通 CE；跨出此边界时恢复调用者的原始超时类型及身份。
        val observed = blockFailure ?: error
        val original = if (observed is CancellationException) {
            bridge.callerCancellation() ?: bridge.failure() ?: observed
        } else observed
        // withContext 可选择更早的入口取消；block 的 cleanup 可能只保留在 observed 上。
        for (source in listOf(observed, error)) {
            if (source === original) continue
            source.suppressed.forEach { cleanup ->
                if (cleanup !== original && original.suppressed.none { it === cleanup }) {
                    original.addSuppressed(cleanup)
                }
            }
        }
        failure = original
        throw original
    } finally {
        bridge.close()
        bridge.failure()?.let { cleanup ->
            val original = failure
            if (original == null) throw cleanup
            if (original !== cleanup && original.suppressed.none { it === cleanup }) {
                original.addSuppressed(cleanup)
            }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class RoomWriterCancellation(private val caller: Job?) :
    AbstractCoroutineContextElement(Key), ThreadContextElement<Unit> {
    companion object Key : CoroutineContext.Key<RoomWriterCancellation> {
        private const val MAX_ACTIVE_EXECUTIONS = 64
        private const val MAX_PARENT_HOPS = 64
    }

    private class Execution {
        var completion: DisposableHandle? = null
    }

    private val lock = Any()
    private val executions = IdentityHashMap<Job, Execution>()
    private val bridgeFailure = AtomicReference<Throwable?>()
    private var stopped = false
    private var closed = false
    @Volatile private var entry: Job? = null

    // 无任务体或子任务的 observer 随父取消立即完成。直接监听 caller 完成会等待卡住的 Room 子任务。
    private val cancellationObserver = Job(caller)
    private val callerCompletion = cancellationObserver.invokeOnCompletion { cause ->
        if (cause != null) cancelExecutions()
    }

    fun enter(execution: Job?) {
        entry = execution
    }

    // 嵌套超时有新的 caller Job，必须另建 observer；仅复用同一入口的桥接。
    fun canReuse(execution: Job?): Boolean = execution != null && execution === entry

    fun failure(): Throwable? = bridgeFailure.get()

    fun ensureReady() {
        caller?.ensureActive()
        failure()?.let { throw it }
    }

    fun callerCancellation(): CancellationException? {
        if (caller?.isCancelled != true) return null
        return try {
            caller.ensureActive()
            null
        } catch (error: CancellationException) {
            error
        }
    }

    override fun updateThreadContext(context: CoroutineContext) {
        val execution = context[Job] ?: return
        // withContext 在 update 前安装执行协程的 Job。原 caller 和无执行体的 Job 不是取消目标。
        if (execution === caller || execution !is Continuation<*>) return
        try {
            if (belongsToBoundary(execution)) observe(execution)
        } catch (error: Throwable) {
            bridgeFailure.compareAndSet(null, error)
            cancelExecutions()
            cancelExecution(execution)
        }
    }

    // 连接等待挂起会恢复线程上下文，取消监听此时仍须有效。
    override fun restoreThreadContext(context: CoroutineContext, oldState: Unit) = Unit

    private fun belongsToBoundary(execution: Job): Boolean {
        var ancestor = execution
        repeat(MAX_PARENT_HOPS) {
            if (ancestor === caller) return true
            // NonCancellable 不挂接父 Job，保留其保护作用及 dispatcher 子任务。
            // 后续显式 DAO/事务入口以仍活跃的新 caller 安装桥接。
            val parent = ancestor.parent ?: return false
            if (parent !is Continuation<*>) return true
            ancestor = parent
        }
        throw IllegalStateException("Room execution parent chain exceeds the bounded traversal")
    }

    private fun observe(job: Job) {
        var cancel = false
        var exceeded = false
        val execution = synchronized(lock) {
            when {
                closed || stopped -> {
                    cancel = true
                    null
                }
                executions.containsKey(job) -> return
                executions.size >= MAX_ACTIVE_EXECUTIONS -> {
                    exceeded = true
                    null
                }
                else -> {
                    val active = Execution()
                    executions[job] = active
                    active
                }
            }
        }
        if (exceeded) {
            bridgeFailure.compareAndSet(null, IllegalStateException("Too many active Room execution contexts"))
            cancelExecutions()
            cancel = true
        }
        if (cancel) cancelExecution(job)
        if (execution == null) return

        // 注册可能同步调用完成回调，先公布占位记录；已完成/关闭后返回的 handle 必须立即释放。
        val completion = job.invokeOnCompletion {
            try {
                synchronized(lock) {
                    if (executions[job] === execution) executions.remove(job)
                }
            } catch (error: Throwable) {
                bridgeFailure.compareAndSet(null, error)
            }
        }
        val retained = synchronized(lock) {
            if (executions[job] === execution && !closed) {
                execution.completion = completion
                true
            } else false
        }
        if (!retained) dispose(completion)
    }

    private fun cancelExecutions() {
        try {
            val pending = synchronized(lock) {
                stopped = true
                executions.keys.toList()
            }
            pending.forEach(::cancelExecution)
        } catch (error: Throwable) {
            bridgeFailure.compareAndSet(null, error)
        }
    }

    private fun cancelExecution(job: Job) {
        try {
            // acquireWithTimeout 会重试所有 TCE。普通 CE 保留原取消为 cause，避免触发该重试。
            job.cancel(RoomWriterCancellationSignal(callerCancellation() ?: failure()))
        } catch (error: Throwable) {
            bridgeFailure.compareAndSet(null, error)
        }
    }

    private fun dispose(handle: DisposableHandle) {
        try {
            handle.dispose()
        } catch (error: Throwable) {
            bridgeFailure.compareAndSet(null, error)
        }
    }

    fun close() {
        val handles = synchronized(lock) {
            closed = true
            entry = null
            val pending = executions.values.mapNotNull { it.completion }
            executions.clear()
            pending
        }
        dispose(callerCompletion)
        handles.forEach(::dispose)
        cancellationObserver.complete()
    }
}

private class RoomWriterCancellationSignal(cause: Throwable?) :
    CancellationException("Room operation caller cancelled") {
    init {
        if (cause != null) initCause(cause)
    }
}
