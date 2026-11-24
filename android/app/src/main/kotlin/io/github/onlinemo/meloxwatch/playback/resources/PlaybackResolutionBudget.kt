package io.github.onlinemo.meloxwatch.playback.resources

import android.os.SystemClock
import io.github.onlinemo.meloxwatch.data.network.MetadataCallBudget
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackException
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackFailure
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

fun interface PlaybackElapsedClock { fun nowMs(): Long }

enum class PlaybackSourceIntent { LOCAL_ONLY, ONLINE_ALLOWED }
enum class StreamingQuality { STANDARD, HIGH }

/** One user operation owns the deadline, native refresh allowance and optional service attempt. */
class PlaybackResolutionBudget(
    private val clock: PlaybackElapsedClock = PlaybackElapsedClock(SystemClock::elapsedRealtime),
) {
    val operationId: String = UUID.randomUUID().toString()
    // 仅进程内的新操作顺序；设置测试不能把先前正在解析或已打开的资源算成新测试。
    internal val operationSequence: Long = nextSequence()
    private val started = clock.nowMs()
    private val deadline = if (started > Long.MAX_VALUE - TOTAL_MS) Long.MAX_VALUE else started + TOTAL_MS
    private var nativeRequests = 0
    private var supplementalUsed = false
    private var metadataRedirects = 0

    fun remainingMs(): Long {
        val now = clock.nowMs()
        if (now < started || now >= deadline) throw timeout()
        return deadline - now
    }

    internal fun metadataCall(): MetadataCallBudget {
        val remaining = remainingMs()
        return MetadataCallBudget(remaining, minOf(3_000L, remaining), JSON_BYTES)
    }

    @Synchronized fun claimNativeRequest() {
        remainingMs()
        if (nativeRequests >= 2) throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.RESOURCE_RETRY_EXHAUSTED))
        nativeRequests++
    }

    @Synchronized fun mayRefreshNative(): Boolean {
        if (nativeRequests >= 2) return false
        return try { remainingMs() > 0L } catch (_: QueuePlaybackException) { false }
    }

    @Synchronized fun claimSupplementalRequest() {
        remainingMs()
        if (supplementalUsed) throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.RESOURCE_RETRY_EXHAUSTED))
        supplementalUsed = true
    }

    @Synchronized fun claimMetadataRedirect() {
        remainingMs()
        if (metadataRedirects >= 3) throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.RESOURCE_REDIRECT))
        metadataRedirects++
    }

    suspend fun <T : Any> within(onAbandoned: (T) -> Unit = {}, action: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        var owned: T? = null
        try {
            val result = withTimeoutOrNull(remainingMs()) { action().also { owned = it } } ?: throw timeout()
            currentCoroutineContext().ensureActive()
            remainingMs()
            owned = null
            return result
        } finally { owned?.let(onAbandoned) }
    }

    fun elapsedNowMs(): Long = clock.nowMs()
    override fun toString(): String = "PlaybackResolutionBudget(redacted)"

    companion object {
        const val TOTAL_MS = 8_000L
        const val JSON_BYTES = 256 * 1024
        private val sequences = AtomicLong()
        internal fun currentSequence(): Long = sequences.get()
        private fun nextSequence(): Long {
            while (true) {
                val previous = sequences.get()
                if (previous == Long.MAX_VALUE) throw QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.CLOSED))
                if (sequences.compareAndSet(previous, previous + 1L)) return previous + 1L
            }
        }
        private fun timeout() = QueuePlaybackException(QueuePlaybackFailure(QueuePlaybackIssue.NETWORK_TIMEOUT))
    }
}

/** Media redirects are bounded per acquisition, independently of the metadata round's three hops. */
internal class MediaRedirectBudget {
    private var used = 0
    @Synchronized fun claim() {
        if (used >= 3) throw MediaTransportException(QueuePlaybackIssue.RESOURCE_REDIRECT)
        used++
    }
}
