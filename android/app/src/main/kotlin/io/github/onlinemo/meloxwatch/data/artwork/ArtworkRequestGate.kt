package io.github.onlinemo.meloxwatch.data.artwork

import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

/** A bounded priority wait list; one ticket retains ownership until native I/O has stopped. */
internal class ArtworkRequestGate {
    private val lock = Any()
    private var sequence = 0L
    private var active: Ticket? = null
    private val waiting = ArrayList<Waiter>(ArtworkLimits.WAITING_REQUESTS)

    suspend fun enter(priority: ArtworkPriority): Ticket = suspendCancellableCoroutine { continuation ->
        val ticket: Ticket
        val start: Boolean
        synchronized(lock) {
            if (active != null && waiting.size >= ArtworkLimits.WAITING_REQUESTS) {
                continuation.resumeWithException(ArtworkException(ArtworkIssue.BUSY))
                return@suspendCancellableCoroutine
            }
            ticket = Ticket(++sequence)
            start = active == null
            if (start) active = ticket else waiting += Waiter(ticket, priority, continuation)
        }
        continuation.invokeOnCancellation { synchronized(lock) { waiting.removeAll { it.ticket == ticket } } }
        if (start) continuation.resume(ticket) { _, value, _ -> exit(value) }
    }

    fun exit(ticket: Ticket) {
        val next = synchronized(lock) {
            if (active != ticket) return
            active = null
            waiting.removeAll { !it.continuation.isActive }
            val chosen = waiting.minWithOrNull(compareBy<Waiter> { it.priority.ordinal }.thenBy { it.ticket.sequence })
            if (chosen != null) { waiting.remove(chosen); active = chosen.ticket }
            chosen
        }
        if (next != null) next.continuation.resume(next.ticket) { _, value, _ -> exit(value) }
    }

    data class Ticket(val sequence: Long)
    private data class Waiter(val ticket: Ticket, val priority: ArtworkPriority, val continuation: CancellableContinuation<Ticket>)
}
