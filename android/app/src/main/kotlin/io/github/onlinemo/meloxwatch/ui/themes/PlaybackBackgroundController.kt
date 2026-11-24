package io.github.onlinemo.meloxwatch.ui.themes

import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.themes.PlaybackBackgroundIssue
import io.github.onlinemo.meloxwatch.data.themes.ThemePalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The request supplies current colors/mask; the frame only owns its static image. */
data class PlaybackBackgroundDisplay(
    val request: PlaybackBackgroundRequest? = null,
    val frame: BackgroundFrameLease? = null,
    val visible: Boolean = false,
    val loading: Boolean = false,
) {
    val palette: ThemePalette? get() = request?.snapshot?.effectiveManifest?.palette
    val issue: PlaybackBackgroundIssue? get() = frame?.issue
    val darkMaskPercent: Int get() = request?.options?.darkMaskPercent ?: 0
}

/** One active render and one latest intent. Hidden screens own neither a frame nor a timer. */
class PlaybackBackgroundController internal constructor(
    private val scope: CoroutineScope,
    private val io: LocalProviderIo,
    private val render: suspend (PlaybackBackgroundRequest) -> BackgroundFrameLease,
    private val busyWaitMillis: Long,
) : AutoCloseable {
    constructor(scope: CoroutineScope, io: LocalProviderIo, renderer: PlaybackBackgroundRenderer) :
        this(scope, io, renderer::render, 3_000)

    init { require(busyWaitMillis in 1..5_000) }

    private val lock = Any()
    private var closed = false
    private var visible = false
    private var latest: PlaybackBackgroundRequest? = null
    private var generation = 0L
    private var completedGeneration: Long? = null
    private var active: RenderTask? = null
    private val mutableState = MutableStateFlow(PlaybackBackgroundDisplay())
    val state: StateFlow<PlaybackBackgroundDisplay> = mutableState.asStateFlow()

    fun update(request: PlaybackBackgroundRequest) {
        var previous: BackgroundFrameLease? = null
        var cancel: Job? = null
        synchronized(lock) {
            if (closed) return
            val samePixels = latest?.renderKey() == request.renderKey()
            val accessChanged = latest?.image?.stableKey() != request.image?.stableKey()
            latest = request
            if (samePixels) {
                if (accessChanged && completedGeneration == generation && mutableState.value.issue != null) {
                    // 新的真实访问可以恢复已结束的旧失败；相同访问不会形成自动重试。
                    completedGeneration = null
                    previous = mutableState.value.frame
                    mutableState.value = placeholder(request)
                } else {
                    // 已有有效像素和正在计算的像素不因遮罩或访问修订号变化而重算。
                    mutableState.value = mutableState.value.copy(request = request)
                }
            } else {
                generation++
                previous = mutableState.value.frame
                cancel = active?.job
                mutableState.value = placeholder(request)
            }
        }
        previous?.close()
        cancel?.cancel()
        schedule()
    }

    fun setVisible(value: Boolean) {
        var previous: BackgroundFrameLease? = null
        var cancel: Job? = null
        synchronized(lock) {
            if (closed || visible == value) return
            visible = value
            generation++
            previous = mutableState.value.frame
            cancel = active?.job
            mutableState.value = latest?.let(::placeholder)
                ?: PlaybackBackgroundDisplay(visible = value)
        }
        previous?.close()
        cancel?.cancel()
        schedule()
    }

    /** A failed resource/budget gets an explicit retry, never an idle polling loop. */
    fun retry() {
        var previous: BackgroundFrameLease? = null
        var cancel: Job? = null
        synchronized(lock) {
            if (closed || !visible || latest == null) return
            generation++
            previous = mutableState.value.frame
            cancel = active?.job
            mutableState.value = placeholder(checkNotNull(latest))
        }
        previous?.close()
        cancel?.cancel()
        schedule()
    }

    override fun close() {
        val retired = synchronized(lock) {
            if (closed) return
            closed = true
            visible = false
            generation++
            latest = null
            val result = mutableState.value.frame to active?.job
            mutableState.value = PlaybackBackgroundDisplay()
            result
        }
        retired.first?.close()
        retired.second?.cancel()
    }

    private fun placeholder(request: PlaybackBackgroundRequest): PlaybackBackgroundDisplay = PlaybackBackgroundDisplay(
        request, if (visible) PlaybackBackgroundRenderer.fallback(request) else null,
        visible, loading = visible,
    )

    private fun schedule() {
        val job = synchronized(lock) {
            if (closed || !visible || !scope.isActive || active != null || completedGeneration == generation) return
            val request = latest ?: return
            val ticket = RenderTask(generation, request)
            val pending = scope.launch(start = CoroutineStart.LAZY) { execute(ticket) }
            ticket.job = pending
            active = ticket
            pending.invokeOnCompletion { failure ->
                var retired: BackgroundFrameLease? = null
                synchronized(lock) {
                    if (active === ticket) {
                        active = null
                        // A source may cancel itself while this screen's intent is unchanged.
                        // It completes this attempt; only a newer intent or retry may restart it.
                        if (failure != null && !closed && visible && generation == ticket.generation) {
                            val current = latest ?: ticket.request
                            // 旧访问自行取消也不能结算新访问；旧 job 结束后才调度 latest。
                            if (current.image?.stableKey() == ticket.request.image?.stableKey()) {
                                retired = mutableState.value.frame
                                mutableState.value = PlaybackBackgroundDisplay(current,
                                    PlaybackBackgroundRenderer.fallback(current, backgroundRenderIssue(failure)), visible = true)
                                completedGeneration = ticket.generation
                            }
                        }
                    }
                }
                retired?.close()
                schedule()
            }
            pending
        }
        job.start()
    }

    private suspend fun execute(ticket: RenderTask) {
        val request = synchronized(lock) {
            if (closed || !visible || active !== ticket || generation != ticket.generation) return
            val current = latest ?: return
            if (current.renderKey() != ticket.request.renderKey()) return
            // 排队期间尚未获取源；开跑时选择最新实际访问，避免已退休的 revision/token。
            ticket.request = current
            current
        }
        var result: BackgroundFrameLease? = null
        try {
            val frame = renderBounded(request)
            result = frame
            currentCoroutineContext().ensureActive()
            var previous: BackgroundFrameLease? = null
            synchronized(lock) {
                val current = latest
                if (!closed && visible && active === ticket && generation == ticket.generation &&
                    current != null && current.renderKey() == request.renderKey() &&
                    (frame.issue == null || current.image?.stableKey() == request.image?.stableKey())) {
                    // 成功的同像素结果可连续保留；旧访问失败不得阻止当前访问加载。
                    previous = mutableState.value.frame
                    mutableState.value = PlaybackBackgroundDisplay(current, frame, visible = true)
                    completedGeneration = ticket.generation
                    result = null
                }
            }
            previous?.close()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            result?.close()
        }
    }

    private suspend fun renderBounded(request: PlaybackBackgroundRequest): BackgroundFrameLease {
        var retried = false
        while (true) {
            try { return render(request) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val issue = backgroundRenderIssue(error)
                if (issue == PlaybackBackgroundIssue.BUSY && !retried) {
                    retried = true
                    val idle = withTimeoutOrNull(busyWaitMillis) {
                        io.awaitIdle()
                        true
                    } ?: false
                    currentCoroutineContext().ensureActive()
                    if (idle) continue
                }
                return PlaybackBackgroundRenderer.fallback(request, issue)
            } catch (error: OutOfMemoryError) {
                return PlaybackBackgroundRenderer.fallback(request, PlaybackBackgroundIssue.LOW_MEMORY)
            }
        }
    }

    private class RenderTask(val generation: Long, var request: PlaybackBackgroundRequest) {
        var job: Job? = null
    }
}
