package io.github.onlinemo.meloxwatch.data.meting

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import io.github.onlinemo.meloxwatch.playback.resources.PlaybackResolutionBudget

/** 同一当前操作、曲目、账户及配置只解析一次；新明确操作不复用未知有效期 URL。 */
internal class MetingRequestFlights(private val scope: CoroutineScope) : AutoCloseable {
    private val memory = Any()
    private var active: Flight? = null
    private var closed = false

    suspend fun resolve(key: MetingRequestKey, budget: PlaybackResolutionBudget, action: suspend () -> MetingResolvedSong): MetingResolvedSong {
        if (key.operationId != budget.operationId) throw MetingException(MetingIssue.PROTOCOL_INVALID)
        val flight = synchronized(memory) {
            if (closed) throw MetingException(MetingIssue.CLOSED)
            val existing = active
            val selected = if (existing == null) {
                Flight(key).also { created ->
                    created.job = scope.async(start = CoroutineStart.LAZY) { budget.within(action = action) }
                    active = created
                }
            } else {
                if (existing.key != key) throw MetingException(MetingIssue.BUSY)
                existing
            }
            if (selected.waiters >= MetingLimits.JOINED_REQUESTS) throw MetingException(MetingIssue.BUSY)
            selected.waiters += 1
            selected
        }
        flight.job.start()
        try { return budget.within { flight.job.await() } }
        finally {
            val stop = synchronized(memory) {
                flight.waiters -= 1
                if (flight.waiters == 0) {
                    if (active === flight) active = null
                    flight.job
                } else null
            }
            // GET 的物理租约由终止 callback 清理，不因最后一个等待者离开而提前释放。
            stop?.cancel(CancellationException("No current service requester"))
        }
    }

    override fun close() {
        val stopped = synchronized(memory) {
            if (closed) return
            closed = true
            active?.job.also { active = null }
        }
        stopped?.cancel(CancellationException("Service owner closed"))
    }

    private class Flight(val key: MetingRequestKey) {
        lateinit var job: Deferred<MetingResolvedSong>
        var waiters = 0
    }
}

internal data class MetingRequestKey(
    val trackId: String,
    val accountId: String?,
    val accountGeneration: Long?,
    val configurationGeneration: Long,
    val operationId: String,
) {
    override fun toString(): String = "MetingRequestKey(redacted)"
}
