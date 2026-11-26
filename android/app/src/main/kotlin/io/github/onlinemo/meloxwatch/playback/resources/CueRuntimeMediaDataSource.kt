package io.github.onlinemo.meloxwatch.playback.resources

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.EmptyCoroutineContext

/** 原生回调在自身worker读取；Main close只取消并调度同一资源的物理退休。 */
@UnstableApi
internal class CueRuntimeMediaDataSource(
    private val uri: Uri,
    private val state: CueRuntimeResourceState,
    private val sources: CuePcmDataSources,
    private val onFailure: (QueuePlaybackIssue) -> Unit,
) : SeekableRuntimeMediaDataSource() {
    private val reads = Any()
    private val closed = AtomicBoolean()
    @Volatile private var source: CuePcmDataSource? = null
    private var nextPosition = -1L
    private val total = Math.addExact(44L, requireNotNull(state.reference.pcmWindow).payloadBytes)
    val retirement = CompletableDeferred<Unit>()
    override val supportsSeek: Boolean get() = !closed.get() && source?.verified == true

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = synchronized(reads) {
        try {
            if (closed.get()) throw MediaTransportException(QueuePlaybackIssue.SOURCE_CHANGED)
            if (position < 0L || offset < 0 || size < 0 || offset > buffer.size - size) {
                throw MediaTransportException(QueuePlaybackIssue.RESOURCE_INVALID)
            }
            if (size == 0) return@synchronized 0
            if (position >= total) return@synchronized -1
            state.owner.checkCue(state)
            var current = source
            if (current == null) {
                current = sources.createDataSource()
                source = current
                if (closed.get()) {
                    current.requestCancellation();
                    throw MediaTransportException(QueuePlaybackIssue.SOURCE_CHANGED)
                }
                current.open(DataSpec.Builder().setUri(uri).build())
                nextPosition = 0L
            }
            if (position != nextPosition) {
                current.seekVirtual(position)
                nextPosition = position
            }
            if (closed.get()) throw MediaTransportException(QueuePlaybackIssue.SOURCE_CHANGED)
            val count = current.read(buffer, offset, minOf(size.toLong(), total - position).toInt())
            if (count > 0) nextPosition += count
            return@synchronized count
        } catch (failure: Exception) {
            close()
            val mapped = cueMediaFailure(failure)
            val issue = (mapped as? MediaTransportException)?.issue ?: QueuePlaybackIssue.SOURCE_UNAVAILABLE
            try { onFailure(issue) } catch (reporting: Exception) { if (reporting !== mapped) mapped.addSuppressed(reporting) }
            throw mapped
        }
    }

    /** 预期字节范围只是快照，不证明read/seek/格式验收。 */
    override fun getSize(): Long = total
    internal fun owns(candidate: CuePcmDataSource): Boolean = source === candidate

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        source?.requestCancellation()
        val work = Runnable {
            try {
                source?.retireInputsOnWorker()
                synchronized(reads) {
                    source?.close();
                    source = null
                    sources.platformRetired(this, state)
                }
                retirement.complete(Unit)
            } catch (failure: Throwable) {
                sources.platformCloseFailed(this, state)
                retirement.completeExceptionally(failure)
            }
        }
        try { Dispatchers.IO.dispatch(EmptyCoroutineContext, work) }
        catch (failure: RuntimeException) {
            sources.platformCloseFailed(this, state)
            retirement.completeExceptionally(failure)
        }
    }
}
