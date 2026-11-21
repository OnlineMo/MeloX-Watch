package io.github.onlinemo.meloxwatch.data.lyrics

/**
 * One application owner reserves the shared local/online 8 MiB, including parsing and validation.
 * The visible page and its worker retain the same reservation. Hiding drops the page reference;
 * cancelled physical work keeps its reference until cleanup ends, so another owner cannot overlap.
 * Online cache text stays on bounded disk and is parsed only inside this reservation.
 */
class LyricsMemoryOwner(private val limitBytes: Long = LyricsLimits.ACCOUNTED_BYTES) {
    init { require(limitBytes in 1L..LyricsLimits.ACCOUNTED_BYTES) }
    private val memory = Any()
    private var active: Reservation? = null

    internal class Reservation(val identity: Any, var references: Int = 0)

    fun acquire(identity: Any): Lease? = synchronized(memory) {
        val selected = active
        if (selected != null && selected.identity !== identity) return@synchronized null
        val reservation = selected ?: Reservation(identity).also { active = it }
        retain(reservation)
    }

    private fun retain(reservation: Reservation): Lease {
        check(active === reservation && reservation.references < MAX_REFERENCES)
        reservation.references++
        return Lease(this, reservation)
    }

    class Lease internal constructor(private val owner: LyricsMemoryOwner, private val reservation: Reservation) : AutoCloseable {
        private var open = true
        val maxAccountedBytes: Long get() = owner.limitBytes

        fun retain(): Lease = synchronized(owner.memory) {
            check(open)
            owner.retain(reservation)
        }

        override fun close() = synchronized(owner.memory) {
            if (!open) return@synchronized
            open = false
            check(owner.active === reservation && reservation.references > 0)
            if (--reservation.references == 0) owner.active = null
        }
    }

    private companion object { const val MAX_REFERENCES = 32 }
}
