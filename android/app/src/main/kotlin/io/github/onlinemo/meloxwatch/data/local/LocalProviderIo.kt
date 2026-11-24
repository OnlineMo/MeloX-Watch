package io.github.onlinemo.meloxwatch.data.local

import android.os.CancellationSignal
import android.os.OperationCanceledException
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
    private var deferredClose: CursorRetirement? = null
    private val busy = MutableStateFlow(false)

    suspend fun awaitIdle() { busy.first { !it } }

    suspend fun <T> read(
        signal: CancellationSignal = CancellationSignal(),
        abandoned: (T) -> Unit = {},
        failed: (Exception) -> Unit = {},
        admitted: () -> Unit = {},
        action: (CancellationSignal) -> T,
    ): T = suspendCancellableCoroutine<Result<T>> { continuation ->
        fun resumeFailure(failure: Throwable) {
            if (continuation.isActive) continuation.resumeWith(Result.success(Result.failure(failure)))
        }
        val accepted = try { synchronized(lock) {
            if (occupied) false else {
                occupied = true
                busy.value = true
                // Acceptance and the owner's fence are one synchronous admission decision.
                try { admitted() }
                catch (failure: Throwable) {
                    occupied = false
                    busy.value = false
                    throw failure
                }
                true
            }
        } } catch (failure: Throwable) {
            resumeFailure(if (failure is Exception) map(failure) else failure)
            return@suspendCancellableCoroutine
        }
        if (!accepted) {
            val failure = SourceReadException(SourceReadIssue.BUSY)
            notifyFailure(failure, failed)
            resumeFailure(failure)
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
                notifyFailure(error, failed)
                outcome = Result.failure(map(error))
            } catch (error: OutOfMemoryError) {
                val failure = SourceReadException(SourceReadIssue.METADATA_UNREADABLE, error)
                notifyFailure(failure, failed)
                outcome = Result.failure(failure)
            } finally {
                drainAndRelease()
            }
            // A resumed sequential caller must observe the channel as available.
            outcome?.fold(
                // 失败以值跨过continuation，保留原领域异常身份。
                onSuccess = { continuation.resume(Result.success(it)) { _, result, _ ->
                    if (result.isSuccess) abandoned(result.getOrThrow())
                } },
                onFailure = { resumeFailure(it) },
            )
        }
        try {
            dispatcher.dispatch(continuation.context, worker)
        } catch (error: RuntimeException) {
            notifyFailure(error, failed)
            drainAndRelease()
            resumeFailure(map(error))
        }
    }.getOrThrow()

    /** Reporting a failure cannot strand the caller or the accepted physical slot. */
    private fun notifyFailure(error: Exception, report: (Exception) -> Unit) {
        try { report(error) }
        catch (reporting: Throwable) { if (reporting !== error) error.addSuppressed(reporting) }
    }

    /** Only the single live enumeration cursor may use this bounded retirement slot. */
    internal fun retireCursor(completed: CompletableDeferred<Unit>, close: () -> Unit) {
        val retirement = CursorRetirement(completed, close)
        val dispatch = synchronized(lock) {
            if (occupied) {
                check(deferredClose == null) { "Only one enumeration cursor may be live" }
                deferredClose = retirement
                false
            } else {
                occupied = true
                busy.value = true
                true
            }
        }
        if (dispatch) {
            val worker = Runnable { drainAndRelease(retirement) }
            try {
                dispatcher.dispatch(kotlin.coroutines.EmptyCoroutineContext, worker)
            } catch (_: RuntimeException) {
                // The rejected dispatcher has no worker, so the fallback still owns one channel.
                Dispatchers.IO.dispatch(kotlin.coroutines.EmptyCoroutineContext, worker)
            }
        }
    }

    private data class CursorRetirement(val completed: CompletableDeferred<Unit>, val close: () -> Unit)

    private fun drainAndRelease(initial: CursorRetirement? = null) {
        var retirement = initial
        var completed: CompletableDeferred<Unit>? = null
        var outcome: Result<Unit>? = null
        while (true) {
            retirement?.let { pending ->
                // The single enumeration cursor owns the sole bounded retirement/ack slot.
                check(completed == null) { "Only one enumeration cursor may be retired" }
                completed = pending.completed
                outcome = try { pending.close(); Result.success(Unit) }
                catch (error: Exception) { Result.failure(error) }
                catch (error: OutOfMemoryError) { Result.failure(error) }
            }
            retirement = synchronized(lock) {
                val pending = deferredClose
                deferredClose = null
                if (pending == null) { occupied = false; busy.value = false }
                pending
            }
            if (retirement == null) break
        }
        // CompletableDeferred may resume an unconfined sequential reader inline. Release first.
        completed?.let { receipt -> checkNotNull(outcome).fold(receipt::complete, receipt::completeExceptionally) }
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
