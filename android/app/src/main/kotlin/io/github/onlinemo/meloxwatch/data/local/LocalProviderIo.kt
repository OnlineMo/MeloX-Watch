package io.github.onlinemo.meloxwatch.data.local

import android.os.CancellationSignal
import android.os.OperationCanceledException
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** CUE取消只调度物理退休；关闭失败时保留同一物理owner。 */
interface LocalProviderReadOwner {
    fun requestCancellation()
    val retainedAfterFailure: Boolean
    /** 准入时绑定精确操作，保证调用者取消或dispatcher拒绝前已有退休回执。 */
    fun bindRetirementFailure(report: (Throwable) -> Unit) = Unit
    fun operationExited(actualRetired: () -> Unit): Boolean = !retainedAfterFailure
}

/**
 * One physical provider operation, including a provider that ignores cancellation.
 * There is no waiting request queue. A cancelled caller cannot free the channel early.
 */
class LocalProviderIo(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    reportRetiredFailure: (LocalProviderFailureStage) -> Unit = {},
) {
    private val lock = Any()
    private var occupied = false
    private var deferredClose: CursorRetirement? = null
    private var retainedReadOwner: LocalProviderReadOwner? = null
    private var readTicket: Any? = null
    private var trackedReadRetirement: CompletableDeferred<Unit>? = null
    private var draining = false
    private val busy = MutableStateFlow(false)
    private var activeReadReceipt: LocalProviderFailureReceipt? = null
    private var latestReadFailure: LocalProviderFailureReceipt? = null
    private var receiptSequence = 0L
    private var latestFailureSequence = 0L
    private var failureReceiptsClosed = false
    private var retiredFailureReporter: ((LocalProviderFailureStage) -> Unit)? = reportRetiredFailure

    suspend fun awaitIdle() { busy.first { !it } }

    /** 只标识本通道尚未准入的占用；已准入动作传播的同类原错不能重试。 */
    internal fun isAdmissionBusy(error: SourceReadException): Boolean {
        val admission = error.cause as? ReadAdmissionBusy ?: return false
        return error.issue == SourceReadIssue.BUSY && admission.io === this && admission.retryable
    }

    private class ReadAdmissionBusy(val io: LocalProviderIo) : Exception() {
        @Volatile var retryable = true
    }

    /** 只跟踪同一顺序调用的实际读；不等待或关闭后来占槽的其它操作。 */
    internal fun trackReads(): ReadRetirement = ReadRetirement(this)

    internal class ReadRetirement(val io: LocalProviderIo) : AbstractCoroutineContextElement(Key) {
        @Volatile private var latest: CompletableDeferred<Unit>? = null

        internal fun admitted(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { latest = it }

        suspend fun awaitRetired() { latest?.await() }

        companion object Key : CoroutineContext.Key<ReadRetirement>
    }

    /** 扫描可能已消费返回异常；迟到的退休原错仍须登记到这份回执。 */
    internal fun peekReadFailure(): LocalProviderFailureSnapshot? = synchronized(lock) {
        latestReadFailure?.snapshot()
    }

    /** 确认消费须等物理退休和迟到报告完成，避免提前丢掉原错。 */
    internal fun consumeReadFailure(expected: LocalProviderFailureReceipt): LocalProviderFailureSnapshot? = synchronized(lock) {
        if (latestReadFailure !== expected || !expected.canConsume()) null
        else expected.snapshot().also {
            expected.discard()
            latestReadFailure = null
        }
    }

    /** 应用退出只清原错内存，物理owner仍遵守原退出握手。 */
    internal fun closeFailureReceipts() = synchronized(lock) {
        failureReceiptsClosed = true
        retiredFailureReporter = null
        activeReadReceipt?.discard()
        latestReadFailure?.takeUnless { it === activeReadReceipt }?.discard()
        activeReadReceipt = null
        latestReadFailure = null
    }

    suspend fun <T> read(
        signal: CancellationSignal = CancellationSignal(),
        abandoned: (T) -> Unit = {},
        failed: (Exception) -> Unit = {},
        admitted: () -> Unit = {},
        physicalOwner: LocalProviderReadOwner? = null,
        action: (CancellationSignal) -> T,
    ): T = suspendCancellableCoroutine<Result<T>> { continuation ->
        fun resumeFailure(failure: Throwable) {
            if (continuation.isActive) continuation.resumeWith(Result.success(Result.failure(failure)))
        }
        val ticket = Any()
        val tracking = continuation.context[ReadRetirement]?.takeIf { it.io === this }
        var receipt: LocalProviderFailureReceipt? = null
        val accepted = try { synchronized(lock) {
            if (occupied) false else {
                occupied = true
                busy.value = true
                readTicket = ticket
                // Acceptance and the owner's fence are one synchronous admission decision.
                try {
                    admitted()
                    if (physicalOwner != null && !failureReceiptsClosed) {
                        receiptSequence = Math.addExact(receiptSequence, 1L)
                        receipt = LocalProviderFailureReceipt(ticket, receiptSequence)
                        activeReadReceipt = receipt
                    }
                    // 失败的准入回调尚无实际worker；成功准入才绑定本次退休回执。
                    trackedReadRetirement = tracking?.admitted()
                }
                catch (failure: Throwable) {
                    occupied = false;
                    readTicket = null;
                    busy.value = false;
                    throw failure
                }
                true
            }
        } } catch (failure: Throwable) {
            resumeFailure(if (failure is Exception) map(failure) else failure)
            return@suspendCancellableCoroutine
        }
        if (!accepted) {
            val failure = SourceReadException(SourceReadIssue.BUSY, ReadAdmissionBusy(this))
            notifyFailure(failure, failed)
            resumeFailure(failure)
            return@suspendCancellableCoroutine
        }
        val worker = Runnable {
            var outcome: Result<T>? = null
            try {
                // 即使取消在首次IO前胜出，CUE action的finally仍须执行。
                if (physicalOwner == null) signal.throwIfCanceled()
                val value = action(signal)
                if (continuation.isActive) outcome = Result.success(value) else abandoned(value)
            } catch (error: Exception) {
                recordFailure(receipt, LocalProviderFailureStage.READ, error)
                notifyFailure(error, failed)
                outcome = Result.failure(map(error))
            } catch (error: OutOfMemoryError) {
                recordFailure(receipt, LocalProviderFailureStage.READ, error)
                val failure = SourceReadException(SourceReadIssue.METADATA_UNREADABLE, error)
                notifyFailure(failure, failed)
                outcome = Result.failure(failure)
            } finally {
                if (physicalOwner != null) {
                    synchronized(lock) { if (readTicket === ticket) retainedReadOwner = physicalOwner }
                    if (physicalOwner.operationExited { actualReadRetired(physicalOwner, ticket) }) actualReadRetired(physicalOwner, ticket)
                }
                drainAndRelease(expectedTicket = ticket)
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
            physicalOwner?.bindRetirementFailure { error -> recordFailure(receipt, LocalProviderFailureStage.RETIREMENT, error) }
            continuation.invokeOnCancellation {
                if (physicalOwner == null) signal.cancel() else physicalOwner.requestCancellation()
            }
            dispatcher.dispatch(continuation.context, worker)
        } catch (error: RuntimeException) {
            recordFailure(receipt, LocalProviderFailureStage.READ, error)
            notifyFailure(error, failed)
            if (physicalOwner != null) {
                synchronized(lock) { if (readTicket === ticket) retainedReadOwner = physicalOwner }
                physicalOwner.requestCancellation()
                if (physicalOwner.operationExited { actualReadRetired(physicalOwner, ticket) }) actualReadRetired(physicalOwner, ticket)
            }
            drainAndRelease(expectedTicket = ticket)
            resumeFailure(map(error))
        }
    }.getOrThrow()

    private fun recordFailure(receipt: LocalProviderFailureReceipt?, stage: LocalProviderFailureStage, failure: Throwable) {
        if (receipt == null) return
        val report = synchronized(lock) {
            if (failureReceiptsClosed || !receipt.record(stage, failure)) false
            else {
                retainFailure(receipt)
                receipt.isRetired()
            }
        }
        // 正常worker在真实放槽后才报告，不能占住五秒退休门。
        if (report) reportFailure(receipt)
    }

    private fun retainFailure(receipt: LocalProviderFailureReceipt) {
        // 最近失败槽由lock保护；旧ticket的迟到报告不能挤掉较新失败。
        if (receipt.sequence >= latestFailureSequence) {
            latestFailureSequence = receipt.sequence
            latestReadFailure = receipt
        }
    }

    private fun reportFailure(receipt: LocalProviderFailureReceipt) {
        val report = synchronized(lock) { retiredFailureReporter } ?: return
        for (stage in receipt.pendingReports()) {
            try { report(stage) }
            catch (reporting: Throwable) {
                // 捕获的回执可能已早于新失败；报告原错只附回此回执。
                receipt.recordReportingFailure(stage, reporting)
            }
            finally { receipt.reportCompleted(stage) }
        }
    }

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

    private fun actualReadRetired(owner: LocalProviderReadOwner, ticket: Any) {
        val accepted = synchronized(lock) {
            if (readTicket !== ticket || retainedReadOwner !== owner) false
            else {
                retainedReadOwner = null;
                true
            }
        }
        if (!accepted) return
        val worker = Runnable { drainAndRelease(expectedTicket = ticket) }
        try { dispatcher.dispatch(kotlin.coroutines.EmptyCoroutineContext, worker) }
        catch (_: RuntimeException) { Dispatchers.IO.dispatch(kotlin.coroutines.EmptyCoroutineContext, worker) }
    }

    private fun drainAndRelease(initial: CursorRetirement? = null, expectedTicket: Any? = null) {
        val admitted = synchronized(lock) {
            if (draining || expectedTicket != null && readTicket !== expectedTicket) false
            else {
                draining = true;
                true
            }
        }
        if (!admitted) return
        var retirement = initial
        var completed: CompletableDeferred<Unit>? = null
        var outcome: Result<Unit>? = null
        var settledReadReceipt: LocalProviderFailureReceipt? = null
        var settledTrackedRead: CompletableDeferred<Unit>? = null
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
                if (pending == null) {
                    draining = false
                    if (retainedReadOwner == null) {
                        occupied = false
                        readTicket = null
                        settledTrackedRead = trackedReadRetirement
                        trackedReadRetirement = null
                        settledReadReceipt = activeReadReceipt
                        activeReadReceipt?.retire()
                        activeReadReceipt = null
                        busy.value = false
                    }
                }
                pending
            }
            if (retirement == null) break
        }
        // CompletableDeferred may resume an unconfined sequential reader inline. Release first.
        settledTrackedRead?.complete(Unit)
        completed?.let { receipt -> checkNotNull(outcome).fold(receipt::complete, receipt::completeExceptionally) }
        settledReadReceipt?.let(::reportFailure)
    }

    private fun map(error: Exception): Exception = when (error) {
        // 嵌套拒绝可能穿透已准入action或admitted回调；保留原异常/cause，撤销重试资格。
        is SourceReadException -> error.also { (it.cause as? ReadAdmissionBusy)?.retryable = false }
        is CancellationException -> error
        is OperationCanceledException -> CancellationException("Provider operation cancelled", error)
        is SecurityException -> SourceReadException(SourceReadIssue.PERMISSION_DENIED, error)
        // A missing descriptor can also mean an unavailable document provider.
        is FileNotFoundException, is IOException -> SourceReadException(SourceReadIssue.SOURCE_UNAVAILABLE, error)
        is IllegalArgumentException -> SourceReadException(SourceReadIssue.INVALID_SOURCE, error)
        // Domain failures (for example import quota) already have stable caller-owned meanings.
        else -> error
    }
}
