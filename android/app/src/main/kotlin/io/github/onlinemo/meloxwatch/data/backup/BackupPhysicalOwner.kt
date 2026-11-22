package io.github.onlinemo.meloxwatch.data.backup

import android.os.CancellationSignal
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * A physical worker can finish before its cancellable result reaches the caller. Register the
 * actual owner in that worker; abandoned callbacks never take it away from this cleanup fence.
 */
internal suspend fun <T : AutoCloseable> openBackupPhysicalOwner(
    io: LocalProviderIo,
    onCloseFailure: (T, Throwable) -> Unit = { _, _ -> },
    action: (CancellationSignal, (T) -> Unit) -> T,
): T {
    val admitted = AtomicBoolean(false)
    val retained = AtomicReference<T?>(null)
    var outgoing: Throwable? = null
    try {
        val owner = try {
            io.read(admitted = { admitted.set(true) }) { signal ->
                fun register(owner: T) {
                    val previous = retained.get()
                    check(previous === owner || previous == null && retained.compareAndSet(null, owner))
                }
                // Factories register before fallible initialization/verification, not just on return.
                action(signal, ::register).also(::register)
            }
        } finally {
            if (admitted.get()) withContext(NonCancellable) { io.awaitIdle() }
        }
        currentCoroutineContext().ensureActive()
        check(retained.compareAndSet(owner, null))
        // No suspension after transfer: the caller assigns this owner in the same coroutine.
        return owner
    } catch (failure: Throwable) {
        val original = (failure as? SourceReadException)?.cause as? BackupException ?: failure
        outgoing = original
        throw original
    } finally {
        if (retained.get() != null) withContext(NonCancellable + Dispatchers.IO) {
            io.awaitIdle()
            val owner = checkNotNull(retained.get())
            try {
                owner.close()
                check(retained.compareAndSet(owner, null))
            } catch (closing: Throwable) {
                try { onCloseFailure(owner, closing) }
                catch (reporting: Throwable) { if (reporting !== closing) closing.addSuppressed(reporting) }
                val original = outgoing
                if (original == null) throw closing
                if (closing !== original) original.addSuppressed(closing)
            }
        }
    }
}
