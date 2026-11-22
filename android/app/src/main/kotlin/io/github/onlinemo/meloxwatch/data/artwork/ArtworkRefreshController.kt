package io.github.onlinemo.meloxwatch.data.artwork

import io.github.onlinemo.meloxwatch.data.library.LocalReadDescriptor
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.model.ArtworkReadPath
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

data class ArtworkRefreshRequest(
    val refreshId: String,
    val targets: List<LocalReadDescriptor>,
    val path: ArtworkReadPath,
    val targetPx: Int,
)
enum class ArtworkRefreshPhase { RUNNING, STOPPING, COMPLETED, CANCELLED, PAUSED_BACKGROUND, FAILED }
data class ArtworkRefreshState(
    val refreshId: String,
    val phase: ArtworkRefreshPhase,
    val total: Int,
    val processed: Int = 0,
    val updated: Int = 0,
    val missing: Int = 0,
    val failed: Int = 0,
    val issue: ArtworkIssue? = null,
)

/** The visible/current scope is frozen before confirmation; a later page cannot enlarge the task. */
class ArtworkRefreshController(
    private val artwork: LocalArtworkRepository,
    private val io: LocalProviderIo,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val lock = Any()
    private val startLock = Mutex()
    private var preview: ArtworkRefreshRequest? = null
    private var active: Active? = null
    @Volatile private var backgrounded = false
    private val mutableState = MutableStateFlow<ArtworkRefreshState?>(null)
    val state: StateFlow<ArtworkRefreshState?> = mutableState.asStateFlow()

    fun prepareVisible(targets: List<LocalReadDescriptor>, path: ArtworkReadPath, targetPx: Int = 96): ArtworkRefreshRequest {
        if (targets.isEmpty() || targets.size > ArtworkLimits.VISIBLE_REFRESH_ITEMS || targetPx !in 1..ArtworkLimits.OUTPUT_EDGE) {
            throw ArtworkException(ArtworkIssue.INVALID_IMAGE)
        }
        val frozen = targets.distinctBy { artworkKey(it, path, targetPx) }.toList()
        val request = ArtworkRefreshRequest(UUID.randomUUID().toString(), frozen, path, targetPx)
        synchronized(lock) {
            if (active != null) throw ArtworkException(ArtworkIssue.BUSY)
            preview = request
        }
        return request
    }

    suspend fun start(confirmed: ArtworkRefreshRequest): String {
        if (!startLock.tryLock()) throw ArtworkException(ArtworkIssue.BUSY)
        try {
            val task = synchronized(lock) {
                active?.let { if (it.request.refreshId == confirmed.refreshId) return it.request.refreshId else throw ArtworkException(ArtworkIssue.BUSY) }
                if (preview != confirmed || backgrounded) throw ArtworkException(ArtworkIssue.BUSY)
                preview = null
                Active(confirmed).also { task ->
                    task.job = scope.launch(start = CoroutineStart.LAZY) { task.entered.set(true); run(task) }
                    active = task
                }
            }
            task.job.invokeOnCompletion {
                if (!task.entered.get()) {
                    publish(task, ArtworkRefreshState(confirmed.refreshId,
                        if (backgrounded) ArtworkRefreshPhase.PAUSED_BACKGROUND else task.terminal, confirmed.targets.size))
                    synchronized(lock) { if (active === task) active = null }
                }
            }
            publish(task, ArtworkRefreshState(confirmed.refreshId, ArtworkRefreshPhase.RUNNING, confirmed.targets.size))
            task.job.start()
            return confirmed.refreshId
        } finally { startLock.unlock() }
    }

    suspend fun cancel(refreshId: String) {
        val task = synchronized(lock) { active?.takeIf { it.request.refreshId == refreshId } } ?: return
        if (!task.stopping.compareAndSet(false, true)) return
        stopAccepted(task, ArtworkRefreshPhase.CANCELLED)
    }

    fun onForeground() { backgrounded = false }
    fun onBackground() {
        backgrounded = true
        val task = synchronized(lock) { active } ?: return
        if (!task.stopping.compareAndSet(false, true)) return
        scope.launch { stopAccepted(task, ArtworkRefreshPhase.PAUSED_BACKGROUND) }
    }

    private fun stopAccepted(task: Active, terminal: ArtworkRefreshPhase) {
        task.terminal = terminal
        publish(task, (mutableState.value ?: ArtworkRefreshState(task.request.refreshId, ArtworkRefreshPhase.RUNNING,
            task.request.targets.size)).copy(phase = ArtworkRefreshPhase.STOPPING))
        task.job.cancel(CancellationException("Artwork refresh stopped"))
    }

    private suspend fun run(task: Active) {
        val request = task.request
        var state = ArtworkRefreshState(request.refreshId, ArtworkRefreshPhase.RUNNING, request.targets.size)
        try {
            for (target in request.targets) {
                currentCoroutineContext().ensureActive()
                if (backgrounded || task.stopping.get()) throw CancellationException("Artwork refresh hidden")
                try {
                    val lease = artwork.acquire(target, request.path, request.targetPx, ArtworkPriority.REFRESH, refresh = true)
                    lease?.close()
                    state = if (lease == null) state.copy(processed = state.processed + 1, missing = state.missing + 1)
                        else state.copy(processed = state.processed + 1, updated = state.updated + 1)
                } catch (error: ArtworkException) {
                    state = state.copy(processed = state.processed + 1, failed = state.failed + 1, issue = error.issue)
                }
                publish(task, state)
            }
            publish(task, state.copy(phase = ArtworkRefreshPhase.COMPLETED))
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                io.awaitIdle()
                publish(task, state.copy(phase = if (backgrounded) ArtworkRefreshPhase.PAUSED_BACKGROUND else task.terminal))
            }
        } catch (_: Exception) {
            publish(task, state.copy(phase = ArtworkRefreshPhase.FAILED, issue = ArtworkIssue.SOURCE_UNAVAILABLE))
        } finally { synchronized(lock) { if (active === task) active = null } }
    }

    private fun publish(task: Active, state: ArtworkRefreshState) {
        synchronized(lock) {
            if (active === task && (!task.stopping.get() || state.phase != ArtworkRefreshPhase.RUNNING)) mutableState.value = state
        }
    }

    private class Active(val request: ArtworkRefreshRequest) {
        lateinit var job: Job
        val stopping = AtomicBoolean(false)
        val entered = AtomicBoolean(false)
        @Volatile var terminal = ArtworkRefreshPhase.CANCELLED
    }
}
