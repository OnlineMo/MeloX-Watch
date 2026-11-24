package io.github.onlinemo.meloxwatch.data.cue

import android.content.Context
import android.database.Cursor
import android.os.CancellationSignal
import android.os.SystemClock
import io.github.onlinemo.meloxwatch.data.library.LocalContentValidation
import io.github.onlinemo.meloxwatch.data.library.LocalReadDescriptor
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.LocalProviderReadOwner
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.model.CueFileBinding
import io.github.onlinemo.meloxwatch.model.CuePcmWaveFacts
import java.io.InputStream
import java.io.InterruptedIOException
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.EmptyCoroutineContext

data class CueWaveProof(val descriptor: LocalReadDescriptor, val pcm: CuePcmWaveFacts)

/** 完整读取共用原物理通道；普通元数据仍遵守4 MiB预算。 */
class AndroidCueCompleteReads internal constructor(private val io: LocalProviderIo,
    private val fence: (LocalReadDescriptor, CancellationSignal, CuePhysicalReadOwner) -> ObservedCandidate,
    private val inputs: (CueFileBinding, CancellationSignal) -> CueOwnedInput,
    private val nowMs: () -> Long,
) {
    constructor(context: Context, io: LocalProviderIo,
        fence: (LocalReadDescriptor, CancellationSignal, CuePhysicalReadOwner) -> ObservedCandidate) :
        this(io, fence, { binding, signal -> AndroidCueOwnedInput(context, binding, signal) }, SystemClock::elapsedRealtime)

    suspend fun validate(descriptor: LocalReadDescriptor): LocalReadDescriptor {
        val expected = descriptor.verifiedContentToken ?: throw CueException(CueIssue.UNVERIFIED_CONTENT)
        val size = descriptor.sizeBytes ?: throw CueException(CueIssue.UNVERIFIED_CONTENT)
        val token = read(descriptor, size) { owner ->
            val actual = CueCompleteContentProof.hash(owner.stream(), size, owner::checkpoint)
            if (actual != expected) throw CueException(CueIssue.SOURCE_CHANGED)
            actual
        }
        return descriptor.copy(verifiedContentToken = token, contentValidation = LocalContentValidation.VERIFIED)
    }

    suspend fun proveWave(descriptor: LocalReadDescriptor): CueWaveProof {
        val size = descriptor.sizeBytes ?: throw CueException(CueIssue.UNVERIFIED_CONTENT)
        CueReadLimits.requireWaveExtent(size)
        val proof = read(descriptor, CueReadLimits.waveProofWorkBytes(size)) { owner ->
            // PCM事实与第一次完整SHA共用同一字节流，防止分开散列和解析期间
            // 临时变化的头部被接纳为PCM布局。
            val digest = MessageDigest.getInstance("SHA-256")
            val proved = DigestInputStream(owner.stream(), digest)
            val facts = CuePcmWaveParser.inspect(proved, size, owner::checkpoint)
            owner.checkpoint()
            if (proved.read() != -1) throw CueException(CueIssue.SOURCE_CHANGED)
            val token = CueCompleteContentProof.token(digest.digest())
            owner.input.seek(0L)
            if (CueCompleteContentProof.hash(owner.stream(), size, owner::checkpoint) != token) {
                throw CueException(CueIssue.SOURCE_CHANGED)
            }
            token to facts
        }
        return CueWaveProof(descriptor.copy(verifiedContentToken = proof.first,
            contentValidation = LocalContentValidation.VERIFIED), proof.second)
    }

    suspend fun parse(descriptor: LocalReadDescriptor, encoding: CueEncoding, expected: ObservedCandidate? = null): ParsedCue {
        val size = descriptor.sizeBytes ?: throw CueException(CueIssue.UNVERIFIED_CONTENT)
        if (size !in 1L..CueReadLimits.CUE_MAX_BYTES) throw CueException(CueIssue.LIMIT_EXCEEDED)
        return read(descriptor, size, expected) { owner -> CueParser.read(owner.stream(), size, encoding, owner::checkpoint) }
    }

    private suspend fun <T> read(descriptor: LocalReadDescriptor, workBytes: Long, expected: ObservedCandidate? = null,
        action: (CuePhysicalReadOwner) -> T): T {
        val size = descriptor.sizeBytes ?: throw CueException(CueIssue.UNVERIFIED_CONTENT)
        if (size !in 1L..CueReadLimits.RIFF_MAX_BYTES) throw CueException(CueIssue.INVALID_AUDIO)
        val signal = CancellationSignal()
        val binding = CueFileBinding(descriptor.source.spec.sourceId, descriptor.source.revision,
            descriptor.source.authEpoch, descriptor.source.grantId, descriptor.aliasId, descriptor.objectId,
            descriptor.location, descriptor.providerContentToken, descriptor.verifiedContentToken, size)
        val owner = CuePhysicalReadOwner(inputs(binding, signal), signal, nowMs = nowMs)
        return io.read(signal, physicalOwner = owner) {
            var failure: Throwable? = null
            try {
                owner.begin(workBytes)
                owner.checkpoint()
                checkCandidate(fence(descriptor, signal, owner), expected)
                owner.input.initialize()
                owner.input.checkCurrent()
                val value = action(owner)
                owner.input.checkCurrent(probe = true)
                checkCandidate(fence(descriptor, signal, owner), expected)
                owner.checkpoint()
                value
            } catch (error: Throwable) {
                val mapped = owner.mapFailure(error);
                failure = mapped;
                throw mapped
            }
            finally {
                try { owner.finish() } catch (cleanup: Throwable) {
                    if (failure == null) throw cleanup else if (failure !== cleanup) failure.addSuppressed(cleanup)
                }
            }
        }
    }
    private fun checkCandidate(observed: ObservedCandidate, expected: ObservedCandidate?) {
        if (expected == null) return
        if (observed.sourceId != expected.sourceId || observed.objectId != expected.objectId || observed.uri != expected.uri ||
            observed.kind != expected.kind || observed.displayName != expected.displayName || observed.mimeType != expected.mimeType ||
            observed.sizeBytes != expected.sizeBytes || expected.providerContentToken?.takeUnless { it.startsWith("sha256:") }?.let {
                it != observed.providerContentToken
            } == true) throw CueException(CueIssue.SOURCE_CHANGED)
    }
}

/** 取消与期限到达只调度本操作唯一的物理退休worker。 */
class CuePhysicalReadOwner(val input: CueOwnedInput, private val signal: CancellationSignal,
    private val retirementDispatcher: CoroutineDispatcher = defaultRetirementDispatcher,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) : LocalProviderReadOwner {
    private val requested = AtomicBoolean()
    private val dispatched = AtomicBoolean()
    private val expired = AtomicBoolean()
    private val retired = CountDownLatch(1)
    private val cursorLock = Any()
    private val closeLock = Any()
    private val stateLock = Any()
    private var operationExited = false
    private var actualRetired: (() -> Unit)? = null
    private var cursor: Cursor? = null
    @Volatile private var closedExactly = false
    @Volatile private var closeFailure: Throwable? = null
    @Volatile private var retirementFailure: Throwable? = null
    private var failedFinishReceipt: Throwable? = null
    private var retirementAttached = false
    private var retirementReport: ((Throwable) -> Unit)? = null
    private var pendingRetirementFailure: Throwable? = null
    private var additionalPendingRetirementFailure: Throwable? = null
    private var deadline: CueReadDeadline? = null
    private var watchdog: CueReadWatchdog? = null
    override val retainedAfterFailure: Boolean get() = !closedExactly || closeFailure != null ||
        dispatched.get() && retired.count != 0L

    override fun bindRetirementFailure(report: (Throwable) -> Unit) {
        val pending = synchronized(stateLock) {
            check(retirementReport == null)
            retirementReport = report
            arrayOf(pendingRetirementFailure, additionalPendingRetirementFailure).also {
                pendingRetirementFailure = null
                additionalPendingRetirementFailure = null
            }
        }
        // 登记IO回执前先释放stateLock，绑定前的取消原错也仍可回核。
        pending.forEach { it?.let(report) }
    }

    fun begin(workBytes: Long) {
        check(deadline == null && watchdog == null)
        val budget = CueReadDeadline(workBytes, nowMs)
        deadline = budget
        watchdog = CueReadWatchdog(budget) {
            expired.set(true);
            requestCancellation()
        }
    }
    fun checkpoint() {
        if (expired.get()) throw CueException(CueIssue.READ_TIMEOUT)
        deadline?.check()
        if (requested.get() || Thread.currentThread().isInterrupted) throw InterruptedIOException("CUE verification cancelled")
        signal.throwIfCanceled()
    }
    fun mapFailure(error: Throwable): Throwable = if (expired.get()) CueException(CueIssue.READ_TIMEOUT, cause = error) else error
    fun stream(): InputStream = object : InputStream() {
        private val single = ByteArray(1)
        override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 255
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            checkpoint()
            val count = input.read(buffer, offset, minOf(length, CueReadLimits.STREAM_BYTES))
            checkpoint()
            return count
        }
    }
    fun <T> withCursor(value: Cursor, action: (Cursor) -> T): T = synchronized(cursorLock) {
        check(cursor == null)
        cursor = value
        try {
            checkpoint();
            action(value)
        }
        finally {
            value.close();
            cursor = null
        }
    }
    override fun requestCancellation() {
        val dispatch = synchronized(stateLock) {
            if (operationExited) false else {
                requested.set(true)
                input.requestCancellation()
                dispatched.compareAndSet(false, true)
            }
        }
        if (!dispatch) return
        val worker = Runnable {
            var failure: Throwable? = null
            try {
                try { input.cancel() } catch (error: Throwable) { failure = error }
                try { closePhysical() } catch (error: Throwable) {
                    if (failure == null) failure = error else if (failure !== error) failure?.addSuppressed(error)
                }
                // 取消失败仍须清理FD，并保留原始失败证据。
                failure?.let(::recordRetirementFailure)
            }
            finally {
                retired.countDown();
                notifyActualRetirement()
            }
        }
        try { retirementDispatcher.dispatch(EmptyCoroutineContext, worker) }
        catch (failure: Throwable) {
            recordRetirementFailure(failure);
            retired.countDown();
            notifyActualRetirement()
        }
    }
    override fun operationExited(actualRetired: () -> Unit): Boolean = synchronized(stateLock) {
        check(!operationExited)
        operationExited = true
        if (!retainedAfterFailure) true else {
            this.actualRetired = actualRetired;
            false
        }
    }
    private fun notifyActualRetirement() {
        val notify = synchronized(stateLock) {
            if (!operationExited || retainedAfterFailure) null else actualRetired.also { actualRetired = null }
        }
        notify?.invoke()
    }
    private fun recordRetirementFailure(failure: Throwable) {
        val report = synchronized(stateLock) {
            retirementFailure = failure
            attachRetirementFailure()
            if (retirementReport == null) {
                if (pendingRetirementFailure == null) pendingRetirementFailure = failure
                else if (pendingRetirementFailure !== failure && additionalPendingRetirementFailure == null) {
                    additionalPendingRetirementFailure = failure
                }
            }
            retirementReport
        }
        // 原错误先登记到应用持有的精确回执，随后latch/ACK才能退休owner。
        report?.invoke(failure)
    }
    private fun rememberFinishFailure(failure: Throwable) = synchronized(stateLock) {
        // owner/ticket物理退休后，第一次返回的失败仍可读取。
        if (failedFinishReceipt == null) failedFinishReceipt = failure
        attachRetirementFailure()
    }
    private fun attachRetirementFailure() {
        // 两个调用方均持有stateLock；同一返回回执只接收一次结果。
        val receipt = failedFinishReceipt ?: return
        val failure = retirementFailure ?: return
        if (retirementAttached) return
        if (receipt !== failure && receipt.cause !== failure) receipt.addSuppressed(failure)
        retirementAttached = true
    }
    fun finish() {
        try {
            closePhysical()
            if (dispatched.get() && !retired.await(5L, TimeUnit.SECONDS)) throw CueException(CueIssue.READ_CLOSE_FAILED)
            // 实际退休只释放本槽位；历史错误仍写入回执。
            notifyActualRetirement()
            closeFailure?.let { throw CueException(CueIssue.READ_CLOSE_FAILED, cause = it) }
            retirementFailure?.let { throw CueException(CueIssue.READ_CLOSE_FAILED, cause = it) }
            if (watchdog?.complete() == false) throw CueException(CueIssue.READ_TIMEOUT)
        } catch (failure: Throwable) {
            rememberFinishFailure(failure)
            throw failure
        } finally {
            watchdog?.close();
            watchdog = null;
            deadline = null
        }
    }
    private fun closePhysical() = synchronized(closeLock) {
        var failure: Throwable? = null
        synchronized(cursorLock) {
            try {
                cursor?.close();
                cursor = null
            } catch (error: Throwable) {
                failure = error
            }
        }
        try { input.close() } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure?.addSuppressed(error)
        }
        if (failure != null) {
            closeFailure = failure;
            throw CueException(CueIssue.READ_CLOSE_FAILED, cause = failure)
        }
        closeFailure = null
        closedExactly = true
    }
    companion object { private val defaultRetirementDispatcher = Dispatchers.IO.limitedParallelism(1) }
}
