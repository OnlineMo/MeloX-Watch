package io.github.onlinemo.meloxwatch.data.backup

import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** At most the operation's two stages and the application journal can need another close. */
internal class BackupCloseRetentions(private val io: LocalProviderIo, private val requireRecovery: () -> Unit) {
    private val lock = Any()
    private val owners = IdentityHashMap<AutoCloseable, Unit>()
    val pendingCount: Int get() = synchronized(lock) { owners.size }
    val hasPending: Boolean get() = synchronized(lock) { owners.isNotEmpty() }
    fun contains(owner: AutoCloseable): Boolean = synchronized(lock) { owners.containsKey(owner) }

    fun retain(owner: AutoCloseable) {
        synchronized(lock) {
            check(owners.containsKey(owner) || owners.size < MAX_OWNERS) { "Too many backup close owners" }
            owners[owner] = Unit
        }
        requireRecovery()
    }

    /** Cancellation cannot turn a successful physical close into an unknown delivery outcome. */
    suspend fun close(owner: AutoCloseable) = withContext(NonCancellable + Dispatchers.IO) {
        val admitted = AtomicBoolean(false)
        try {
            io.awaitIdle()
            val outcome = try { io.read(admitted = { admitted.set(true) }) { runCatching { owner.close() } } }
            finally { if (admitted.get()) io.awaitIdle() }
            outcome.getOrThrow()
        } catch (failure: Throwable) {
            val original = (failure as? SourceReadException)?.cause ?: failure
            try { retain(owner) }
            catch (reporting: Throwable) { if (reporting !== original) original.addSuppressed(reporting) }
            throw original
        }
        synchronized(lock) { owners.remove(owner) }
        Unit
    }

    /** Each real owner gets a retry; one failure never drops or skips the other retained object. */
    suspend fun retryAll() = withContext(NonCancellable + Dispatchers.IO) {
        val pending = synchronized(lock) { owners.keys.toList() }
        var failure: Throwable? = null
        for (owner in pending) {
            try { close(owner) }
            catch (closing: Throwable) {
                val original = failure
                if (original == null) failure = closing
                else if (original !== closing) original.addSuppressed(closing)
            }
        }
        failure?.let { throw BackupException(BackupIssue.RECOVERY_REQUIRED, it) }
        Unit
    }

    companion object { private const val MAX_OWNERS = 3 }
}
