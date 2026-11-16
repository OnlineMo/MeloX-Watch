package io.github.onlinemo.meloxwatch.playback

/**
 * One lease precedes engine allocation and survives every Service/owner instance in this process.
 * An unconfirmed release is permanent here: a facade flag or a second release is not native proof.
 */
internal class PlaybackEngineReleaseGuard {
    internal class Lease internal constructor() {
        internal var unconfirmed = false
        internal var releaseStarted = false
    }

    private val lock = Any()
    private var retained: Lease? = null

    fun acquire(): Lease = synchronized(lock) {
        if (retained != null) throw PlaybackReleaseUnconfirmedException()
        Lease().also { retained = it }
    }

    fun blocksCreation(): Boolean = synchronized(lock) { retained != null }

    fun hasUnconfirmedRelease(): Boolean = synchronized(lock) { retained?.unconfirmed == true }

    fun isRetained(lease: Lease): Boolean = synchronized(lock) { retained === lease }

    fun releaseStarted(lease: Lease): Boolean = synchronized(lock) { retained === lease && lease.releaseStarted }

    fun markReleaseStarted(lease: Lease) = synchronized(lock) {
        requireRetained(lease)
        lease.releaseStarted = true
    }

    fun requireRetained(lease: Lease) = synchronized(lock) {
        check(retained === lease) { "Playback release lease is no longer owned" }
    }

    fun quarantine(lease: Lease) = synchronized(lock) {
        requireRetained(lease)
        lease.unconfirmed = true
    }

    fun confirmReleased(lease: Lease) = synchronized(lock) {
        requireRetained(lease)
        if (lease.unconfirmed) throw PlaybackReleaseUnconfirmedException()
        retained = null
    }

    companion object {
        val process = PlaybackEngineReleaseGuard()
    }
}

internal class PlaybackReleaseUnconfirmedException :
    IllegalStateException("Playback engine release is unconfirmed")
