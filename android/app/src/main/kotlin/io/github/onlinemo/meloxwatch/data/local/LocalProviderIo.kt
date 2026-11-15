package io.github.onlinemo.meloxwatch.data.local

import android.os.CancellationSignal
import android.os.OperationCanceledException
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * One physical provider operation, including a provider that ignores cancellation.
 * There is no waiting request queue. A cancelled caller cannot free the channel early.
 */
class LocalProviderIo(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) {
    private val lock = Any()
    private var occupied = false
    private var deferredClose: (() -> Unit)? = null
    private val busy = MutableStateFlow(false)

    suspend fun awaitIdle() { busy.first { !it } }

    suspend fun <T> read(
        signal: CancellationSignal = CancellationSignal(),
        abandoned: (T) -> Unit = {},
        failed: (Exception) -> Unit = {},
        action: (CancellationSignal) -> T,
    ): T = suspendCancellableCoroutine { continuation ->
        val accepted = synchronized(lock) {
            if (occupied) false else { occupied = true; busy.value = true; true }
        }
        if (!accepted) {
            val failure = SourceReadException(SourceReadIssue.BUSY)
            failed(failure)
            continuation.resumeWithException(failure)
            return@suspendCancellableCoroutine
        }
        continuation.invokeOnCancellation { signal.cancel() }
        val worker = Runnable {
            var outcome: Result<T>? = null
            try {
                signal.throwIfCanceled()
                val value = action(signal)
                if (continuation.isActive) outcome = Result.success(value) else abandoned(value)
            } catch (error: Exception) {
                failed(error)
                outcome = Result.failure(map(error))
            } catch (error: OutOfMemoryError) {
                val failure = SourceReadException(SourceReadIssue.METADATA_UNREADABLE, error)
                failed(failure)
                outcome = Result.failure(failure)
            } finally {
                drainAndRelease()
            }
            // A resumed sequential caller must observe the channel as available.
            outcome?.fold(
                onSuccess = { continuation.resume(it) { _, result, _ -> abandoned(result) } },
                onFailure = { if (continuation.isActive) continuation.resumeWithException(it) },
            )
        }
        try {
            dispatcher.dispatch(continuation.context, worker)
        } catch (error: RuntimeException) {
            failed(error)
            drainAndRelease()
            if (continuation.isActive) continuation.resumeWithException(map(error))
        }
    }

    /** Only the single live enumeration cursor may use this bounded retirement slot. */
    internal fun retireCursor(close: () -> Unit) {
        val dispatch = synchronized(lock) {
            if (occupied) {
                check(deferredClose == null) { "Only one enumeration cursor may be live" }
                deferredClose = close
                false
            } else {
                occupied = true
                busy.value = true
                true
            }
        }
        if (dispatch) {
            val worker = Runnable { try { close() } finally { drainAndRelease() } }
            try {
                dispatcher.dispatch(kotlin.coroutines.EmptyCoroutineContext, worker)
            } catch (_: RuntimeException) {
                // The rejected dispatcher has no worker, so the fallback still owns one channel.
                Dispatchers.IO.dispatch(kotlin.coroutines.EmptyCoroutineContext, worker)
            }
        }
    }

    private fun drainAndRelease() {
        while (true) {
            val close = synchronized(lock) {
                val pending = deferredClose
                deferredClose = null
                if (pending == null) { occupied = false; busy.value = false }
                pending
            } ?: return
            try { close() }
            catch (_: Exception) { /* Closing cannot turn cancelled work into success. */ }
            catch (_: OutOfMemoryError) { /* Release ownership even when native cleanup ran out of memory. */ }
        }
    }

    private fun map(error: Exception): Exception = when (error) {
        is SourceReadException, is CancellationException -> error
        is OperationCanceledException -> CancellationException("Provider operation cancelled", error)
        is SecurityException -> SourceReadException(SourceReadIssue.PERMISSION_DENIED, error)
        // A missing descriptor can also mean an unavailable document provider.
        is FileNotFoundException, is IOException -> SourceReadException(SourceReadIssue.SOURCE_UNAVAILABLE, error)
        is IllegalArgumentException -> SourceReadException(SourceReadIssue.INVALID_SOURCE, error)
        // Domain failures (for example import quota) already have stable caller-owned meanings.
        else -> error
    }
}
