package io.github.onlinemo.meloxwatch.playback.resources

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.BaseDataSource
import io.github.onlinemo.meloxwatch.data.cue.CueException
import io.github.onlinemo.meloxwatch.data.cue.CueIssue
import io.github.onlinemo.meloxwatch.data.cue.CuePcmWaveParser
import io.github.onlinemo.meloxwatch.data.cue.CueCompleteContentProof
import io.github.onlinemo.meloxwatch.data.cue.CueReadDeadline
import io.github.onlinemo.meloxwatch.data.cue.CueReadLimits
import io.github.onlinemo.meloxwatch.data.cue.CueReadWatchdog
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue
import java.io.InputStream
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** 共用服务资源owner，最多三个PCM输入；关闭失败的输入仍保留引用。 */
@UnstableApi
internal class CuePcmDataSources(
    private val inputs: CuePcmInputFactory,
    private val resources: RuntimePlaybackResources,
    private val proofs: CueBlockProofs = CueBlockProofs.temporary(),
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) : DataSource.Factory, AutoCloseable {
    private val lock = Any()
    private var closed = false
    private val active = LinkedHashSet<CuePcmDataSource>()
    private val failedClosers = LinkedHashSet<CuePcmDataSource>()
    private val retiring = LinkedHashMap<CuePcmDataSource, RuntimePlaybackResources.CueRetirementLease?>()
    private data class FailedRetirement(val cause: Throwable, val hold: RuntimePlaybackResources.CueRetirementLease?)
    private val failedRetirements = LinkedHashMap<CuePcmDataSource, FailedRetirement>()
    private var platform: CueRuntimeMediaDataSource? = null
    private var platformFailed = false
    private data class Retirement(val active: Int = 0, val platform: Boolean = false, val workers: Int = 0, val failed: Boolean = false)
    private val retirement = MutableStateFlow(Retirement())

    override fun createDataSource(): CuePcmDataSource = CuePcmDataSource(this, inputs, resources, proofs, nowMs)
    internal fun acquire(source: CuePcmDataSource) = synchronized(lock) {
        if (closed || failedClosers.isNotEmpty() || retiring.isNotEmpty() || failedRetirements.isNotEmpty() || active.size >= 3 || source in active) {
            throw MediaTransportException(QueuePlaybackIssue.BUSY)
        }
        active.add(source)
        publish()
    }
    internal fun released(source: CuePcmDataSource) = synchronized(lock) {
        active.remove(source)
        failedClosers.remove(source)
        publish()
    }
    internal fun closeFailed(source: CuePcmDataSource) = synchronized(lock) {
        failedClosers.add(source)
        publish()
    }
    private fun retire(source: CuePcmDataSource) {
        var admissionFailure: Throwable? = null
        val admitted = synchronized(lock) {
            if (source !in active || source in retiring || source in failedRetirements) false else {
                retiring[source] = try {
                    source.retirementHold()
                } catch (failure: Throwable) {
                    admissionFailure = failure;
                    null
                }
                publish()
                true
            }
        }
        if (!admitted) return
        val work = Runnable {
            var failure: Throwable? = admissionFailure
            try { source.retireInputsOnWorker() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            }
            finishRetirement(source, failure)
        }
        try { Dispatchers.IO.dispatch(EmptyCoroutineContext, work) }
        catch (failure: RuntimeException) { finishRetirement(source, failure) }
    }
    internal fun proofTimedOut(source: CuePcmDataSource) {
        source.requestCancellation()
        val wrapper = synchronized(lock) { platform?.takeIf { it.owns(source) } }
        if (wrapper != null) wrapper.close() else retire(source)
    }
    private fun finishRetirement(source: CuePcmDataSource, failure: Throwable?) = synchronized(lock) {
        val hold = retiring.remove(source)
        if (failure != null) {
            failedRetirements[source] = FailedRetirement(failure, hold)
            failedClosers.add(source)
            hold?.state?.let { resources.cueCloseFailed(it, source) }
        } else {
            hold?.close()
        }
        publish()
    }
    fun platform(uri: Uri, onFailure: (QueuePlaybackIssue) -> Unit): CueRuntimeMediaDataSource = synchronized(lock) {
        if (closed || platform != null || failedClosers.isNotEmpty() || retiring.isNotEmpty() || failedRetirements.isNotEmpty()) throw MediaTransportException(QueuePlaybackIssue.BUSY)
        val state = resources.acquireCue(uri)
        CueRuntimeMediaDataSource(uri, state, this, onFailure).also {
            platform = it;
            publish()
        }
    }
    internal fun platformRetired(source: CueRuntimeMediaDataSource, state: CueRuntimeResourceState) = synchronized(lock) {
        check(platform === source)
        resources.cueCloseRetired(state, source)
        resources.releaseCue(state)
        platform = null
        platformFailed = false
        publish()
    }
    internal fun platformCloseFailed(source: CueRuntimeMediaDataSource, state: CueRuntimeResourceState) = synchronized(lock) {
        check(platform === source)
        resources.cueCloseFailed(state, source)
        platformFailed = true
        publish()
    }

    /** CUE集合为空时立即返回；该查询不创建读取或周期任务。 */
    suspend fun awaitRetirement() {
        val result = retirement.first { it.failed || it.active == 0 && !it.platform && it.workers == 0 }
        if (result.failed) throw MediaTransportException(QueuePlaybackIssue.SOURCE_CHANGED)
    }

    override fun close() {
        val snapshot = synchronized(lock) {
            closed = true
            // 系统封装拥有唯一worker及其底层DataSource。
            active.filterNot { platform?.owns(it) == true } to platform
        }
        var failure: Exception? = null
        for (source in snapshot.first) try {
            source.requestCancellation();
            retire(source)
        }
        catch (error: Exception) {
            closeFailed(source)
            if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
        }
        try { snapshot.second?.close() }
        catch (error: Exception) { if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error) }
        failure?.let { throw it }
        if (synchronized(lock) { failedClosers.isNotEmpty() || retirement.value.failed }) {
            throw MediaTransportException(QueuePlaybackIssue.SOURCE_CHANGED)
        }
    }
    private fun publish() {
        retirement.value = Retirement(active.size, platform != null, retiring.size,
            platformFailed || failedClosers.isNotEmpty() || failedRetirements.isNotEmpty())
    }
}

/** 暴露的音频字节仅为精确样本窗口和小型虚拟WAV头。 */
@UnstableApi
internal class CuePcmDataSource(
    private val owner: CuePcmDataSources,
    private val inputs: CuePcmInputFactory,
    private val resources: RuntimePlaybackResources,
    private val proofs: CueBlockProofs,
    private val nowMs: () -> Long,
) : BaseDataSource(false), AutoCloseable {
    @Volatile private var state: CueRuntimeResourceState? = null
    @Volatile private var cueFile: CueSeekableInput? = null
    @Volatile private var audioFile: CueSeekableInput? = null
    @Volatile private var proofIndex: CueBlockProofIndex? = null
    @Volatile private var deadline: CueReadDeadline? = null
    private val deadlineExpired = AtomicBoolean()
    private var watchdog: CueReadWatchdog? = null
    private val permanentlyCancelled = AtomicBoolean()
    private var cancellation = AtomicBoolean()
    private var transferActive = false
    private var registered = false
    private var uri: Uri? = null
    private var header = ByteArray(0)
    private var position = 0L
    private var remaining = 0L
    private var payloadStart = 0L
    private var rangeStart = 0L
    private var rangeEnd = 0L
    private var scratch: ByteArray? = null
    private var cachedBlock = -1
    private var cachedBytes = 0
    internal var completedAudioProofs = 0
        private set
    @Volatile var verified = false
        private set

    override fun open(dataSpec: DataSpec): Long {
        check(!registered && state == null && cueFile == null && audioFile == null && proofIndex == null)
        if (dataSpec.position < 0L || dataSpec.length < 0L && dataSpec.length != C.LENGTH_UNSET.toLong()) {
            throw MediaTransportException(QueuePlaybackIssue.RESOURCE_INVALID)
        }
        if (permanentlyCancelled.get()) throw InterruptedIOException("CUE media source retired")
        cancellation = AtomicBoolean()
        try {
            checkCancellation()
            transferInitializing(dataSpec)
            owner.acquire(this)
            registered = true
            val current = resources.acquireCue(dataSpec.uri)
            state = current
            uri = dataSpec.uri
            val binding = requireNotNull(current.reference.binding)
            beginProof(Math.addExact(CueReadLimits.waveRuntimeWorkBytes(binding.pcm), Math.multiplyExact(binding.cue.sizeBytes, 2L)))
            verifyCue(current)
            val audio = inputs.create(binding.audio).also { audioFile = it }
            checkCancellation()
            audio.initialize()
            val baseline = binding.audio.verifiedContentToken ?: throw MediaTransportException(QueuePlaybackIssue.CONTENT_UNKNOWN)
            val index = proofs.allocate(binding.audio.sizeBytes).also { proofIndex = it }
            index.initialize()
            scratch = ByteArray(CueReadLimits.STREAM_BYTES)
            verifyAudio(audio, baseline, index)
            val proved = VerifiedInput()
            val facts = CuePcmWaveParser.inspectVerified(proved, audio.length, proved::seek, ::checkCancellation)
            if (facts != binding.pcm) throw MediaTransportException(QueuePlaybackIssue.SOURCE_CHANGED)
            val window = facts.window(current.reference.identity)
            header = CuePcmWaveParser.transportHeader(facts, window.payloadBytes)
            payloadStart = window.physicalByteOffset
            val total = Math.addExact(header.size.toLong(), window.payloadBytes)
            if (dataSpec.position > total) throw MediaTransportException(QueuePlaybackIssue.RESOURCE_INVALID)
            position = dataSpec.position
            rangeStart = position
            val available = total - position
            remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else minOf(dataSpec.length, available)
            rangeEnd = Math.addExact(position, remaining)
            cachedBlock = -1
            audio.checkCurrent(probe = true)
            // 长WAV校验期间文档发生变化时，不能继续沿用较早的CUE边界证明。
            verifyCue(current)
            resources.checkCue(current)
            checkCancellation()
            completeProof()
            verified = true
            transferActive = true
            transferStarted(dataSpec)
            return remaining
        } catch (failure: OutOfMemoryError) {
            val mapped = MediaTransportException(QueuePlaybackIssue.SOURCE_UNAVAILABLE).also { it.initCause(failure) }
            try { close() } catch (cleanup: Exception) { mapped.addSuppressed(cleanup) }
            throw mapped
        } catch (failure: Exception) {
            try { close() } catch (cleanup: Exception) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            throw cueMediaFailure(if (deadlineExpired.get()) CueException(CueIssue.READ_TIMEOUT, cause = failure) else failure)
        } finally {
            endProof()
        }
    }

    /** 原生随机读取保留已核验的物理epoch及原DataSpec范围。 */
    internal fun seekVirtual(next: Long) {
        if (!verified || next !in rangeStart..rangeEnd) throw MediaTransportException(QueuePlaybackIssue.RESOURCE_INVALID)
        try {
            val current = checkNotNull(state)
            val binding = requireNotNull(current.reference.binding)
            beginProof(Math.addExact(binding.cue.sizeBytes, CueReadLimits.STREAM_BYTES.toLong()))
            resources.checkCue(current)
            val audio = checkNotNull(audioFile)
            audio.checkCurrent(probe = true)
            verifyCue(current)
            audio.checkCurrent()
            resources.checkCue(current)
            checkCancellation()
            completeProof()
            cachedBlock = -1
            cachedBytes = 0
            position = next
            remaining = rangeEnd - next
        } catch (failure: Exception) { throw cueMediaFailure(if (deadlineExpired.get()) CueException(CueIssue.READ_TIMEOUT, cause = failure) else failure) }
        finally { endProof() }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > buffer.size - length) throw MediaTransportException(QueuePlaybackIssue.RESOURCE_INVALID)
        checkCancellation()
        val current = state ?: throw MediaTransportException(QueuePlaybackIssue.RESOURCE_EXPIRED)
        resources.checkCue(current)
        if (!verified) throw MediaTransportException(QueuePlaybackIssue.SOURCE_CHANGED)
        checkNotNull(audioFile).checkCurrent()
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val wanted = minOf(length.toLong(), remaining, CuePcmWaveParser.READ_BYTES.toLong()).toInt()
        val read = if (position < header.size) {
            val count = minOf(wanted.toLong(), header.size - position).toInt()
            header.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            count
        } else try { readVerifiedAt(payloadStart + position - header.size, buffer, offset, wanted) }
        catch (failure: Exception) { throw cueMediaFailure(failure) }
        if (read <= 0 || read > wanted) throw MediaTransportException(QueuePlaybackIssue.SOURCE_CHANGED)
        position += read
        remaining -= read
        bytesTransferred(read)
        checkCancellation()
        resources.checkCue(current)
        return read
    }

    override fun getUri(): Uri? = uri
    override fun getResponseHeaders(): Map<String, List<String>> = mapOf("Content-Type" to listOf("audio/wav"))
    internal fun requestCancellation() {
        permanentlyCancelled.set(true)
        cancellation.set(true)
        cueFile?.requestCancellation()
        audioFile?.requestCancellation()
    }
    internal fun retirementHold(): RuntimePlaybackResources.CueRetirementLease? {
        val snapshot = state ?: return null
        return try { resources.retainCueRetirement(snapshot) }
        catch (failure: MediaTransportException) {
            if (cueFile != null || audioFile != null || proofIndex != null) throw failure
            null
        }
    }
    /** 外部取消和FD清理只在已注册的物理退休worker中执行。 */
    internal fun retireInputsOnWorker() {
        requestCancellation()
        var failure: Exception? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (error: Exception) {
                if (failure == null) failure = error else if (failure !== error) failure?.addSuppressed(error)
            }
        }
        attempt(::cancelActiveInputs)
        attempt { cueFile?.close() }
        attempt { audioFile?.close() }
        failure?.let { throw it }
    }
    private fun cancelActiveInputs() {
        cancellation.set(true)
        var failure: Exception? = null
        try { cueFile?.cancel() } catch (error: Exception) { failure = error }
        try { audioFile?.cancel() }
        catch (error: Exception) { if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error) }
        failure?.let { throw it }
    }
    override fun close() {
        verified = false
        endProof()
        var failure: Exception? = null
        fun close(action: () -> Unit) {
            try { action() } catch (error: Exception) {
                if (failure == null) failure = error else if (failure !== error) failure?.addSuppressed(error)
            }
        }
        close(::cancelActiveInputs)
        close {
            cueFile?.close();
            cueFile = null
        }
        close {
            audioFile?.close();
            audioFile = null
        }
        close {
            proofIndex?.close();
            proofIndex = null
        }
        val current = state
        if (failure != null) {
            owner.closeFailed(this)
            if (current != null) resources.cueCloseFailed(current, this)
            throw checkNotNull(failure)
        }
        if (current != null) {
            resources.cueCloseRetired(current, this)
            resources.releaseCue(current)
            state = null
        }
        verified = false
        uri = null
        header = ByteArray(0)
        scratch = null
        cachedBlock = -1
        cachedBytes = 0
        remaining = 0L
        if (registered) {
            registered = false;
            owner.released(this)
        }
        if (transferActive) {
            transferActive = false;
            transferEnded()
        }
    }

    private fun checkCancellation() {
        if (deadlineExpired.get()) throw CueException(CueIssue.READ_TIMEOUT)
        deadline?.check()
        if (permanentlyCancelled.get() || cancellation.get() || Thread.currentThread().isInterrupted) throw InterruptedIOException("CUE media read cancelled")
    }
    private fun beginProof(workBytes: Long) {
        check(deadline == null && watchdog == null)
        checkCancellation()
        val budget = CueReadDeadline(workBytes, nowMs)
        deadline = budget
        watchdog = CueReadWatchdog(budget) {
            deadlineExpired.set(true);
            owner.proofTimedOut(this)
        }
    }
    private fun endProof() {
        watchdog?.close();
        watchdog = null;
        deadline = null
    }
    private fun completeProof() {
        if (watchdog?.complete() == false || deadlineExpired.get()) throw CueException(CueIssue.READ_TIMEOUT)
    }
    private fun verifyCue(current: CueRuntimeResourceState) {
        check(cueFile == null)
        val binding = requireNotNull(current.reference.binding)
        val cue = inputs.create(binding.cue).also { cueFile = it }
        checkCancellation()
        cue.initialize()
        verifyComplete(cue, current.reference.identity.cueContentToken, binding.cue.sizeBytes, CueReadLimits.CUE_MAX_BYTES)
        cue.close()
        cueFile = null
    }
    private fun verifyComplete(input: CueSeekableInput, token: String, expectedBytes: Long, limit: Long) {
        if (!token.matches(Regex("sha256:[0-9a-f]{64}")) || expectedBytes <= 0L || expectedBytes > limit) {
            throw MediaTransportException(QueuePlaybackIssue.CONTENT_UNKNOWN)
        }
        if (input.length != expectedBytes) throw MediaTransportException(QueuePlaybackIssue.SOURCE_CHANGED)
        input.checkCurrent()
        input.seek(0L)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(CuePcmWaveParser.READ_BYTES)
        var count = 0L
        while (count < expectedBytes) {
            checkCancellation()
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), expectedBytes - count).toInt())
            if (read <= 0 || read > expectedBytes - count) throw MediaTransportException(QueuePlaybackIssue.SOURCE_CHANGED)
            count += read
            digest.update(buffer, 0, read)
        }
        checkCancellation()
        val alphabet = "0123456789abcdef"
        val actual = buildString(71) {
            append("sha256:")
            for (byte in digest.digest()) {
                val value = byte.toInt() and 255;
                append(alphabet[value ushr 4]);
                append(alphabet[value and 15])
            }
        }
        if (actual != token) throw MediaTransportException(QueuePlaybackIssue.SOURCE_CHANGED)
        input.checkCurrent(probe = true)
    }

    private fun verifyAudio(audio: CueSeekableInput, token: String, index: CueBlockProofIndex) {
        if (!token.matches(Regex("sha256:[0-9a-f]{64}")) || audio.length != index.fileBytes) {
            throw MediaTransportException(QueuePlaybackIssue.CONTENT_UNKNOWN)
        }
        audio.checkCurrent()
        audio.seek(0L)
        val bytes = checkNotNull(scratch)
        val whole = MessageDigest.getInstance("SHA-256")
        val blockDigest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        while (total < index.fileBytes) {
            val wanted = minOf(bytes.size.toLong(), index.fileBytes - total).toInt()
            fill(audio, bytes, wanted)
            whole.update(bytes, 0, wanted)
            blockDigest.update(bytes, 0, wanted)
            index.append(blockDigest.digest())
            total = Math.addExact(total, wanted.toLong())
        }
        if (audio.read(bytes, 0, 1) != -1 || CueCompleteContentProof.token(whole.digest()) != token) {
            throw MediaTransportException(QueuePlaybackIssue.SOURCE_CHANGED)
        }
        audio.checkCurrent(probe = true)
        checkCancellation()
        index.seal()
        completedAudioProofs++
        cachedBlock = -1
    }
    private fun fill(input: CueSeekableInput, bytes: ByteArray, wanted: Int) {
        var count = 0
        while (count < wanted) {
            checkCancellation()
            val read = input.read(bytes, count, wanted - count)
            if (read <= 0 || read > wanted - count) throw MediaTransportException(QueuePlaybackIssue.SOURCE_CHANGED)
            count += read
        }
        checkCancellation()
    }
    private fun readVerifiedAt(physical: Long, buffer: ByteArray, offset: Int, wanted: Int): Int {
        val index = checkNotNull(proofIndex)
        if (physical == index.fileBytes) return -1
        if (physical !in 0L until index.fileBytes) throw MediaTransportException(QueuePlaybackIssue.RESOURCE_INVALID)
        val block = (physical / CueReadLimits.STREAM_BYTES).toInt()
        val within = (physical % CueReadLimits.STREAM_BYTES).toInt()
        val bytes = checkNotNull(scratch)
        if (cachedBlock != block) {
            val audio = checkNotNull(audioFile)
            audio.checkCurrent()
            val start = block.toLong() * bytes.size
            audio.seek(start)
            val count = minOf(bytes.size.toLong(), index.fileBytes - start).toInt()
            fill(audio, bytes, count)
            val digest = MessageDigest.getInstance("SHA-256").apply { update(bytes, 0, count) }.digest()
            if (!index.matches(block, digest)) throw MediaTransportException(QueuePlaybackIssue.SOURCE_CHANGED)
            audio.checkCurrent()
            checkCancellation()
            cachedBlock = block
            cachedBytes = count
        }
        val count = minOf(wanted, cachedBytes - within)
        if (count <= 0) throw MediaTransportException(QueuePlaybackIssue.SOURCE_CHANGED)
        checkCancellation()
        bytes.copyInto(buffer, offset, within, within + count)
        return count
    }
    private inner class VerifiedInput : InputStream() {
        private var physical = 0L
        private val single = ByteArray(1)
        fun seek(next: Long) {
            require(next in 0L..checkNotNull(proofIndex).fileBytes);
            physical = next;
            cachedBlock = -1
        }
        override fun read(): Int = if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 255
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val count = readVerifiedAt(physical, buffer, offset, length)
            if (count > 0) physical += count
            return count
        }
    }
}

internal fun cueMediaFailure(failure: Exception): Exception = when (failure) {
    is MediaTransportException, is InterruptedIOException -> failure
    is SecurityException -> MediaTransportException(QueuePlaybackIssue.PERMISSION_DENIED).also { it.initCause(failure) }
    is java.io.FileNotFoundException -> MediaTransportException(QueuePlaybackIssue.FILE_MISSING).also { it.initCause(failure) }
    is SourceReadException -> MediaTransportException(when (failure.issue) {
        SourceReadIssue.PERMISSION_DENIED, SourceReadIssue.PERMISSION_REVOKED -> QueuePlaybackIssue.PERMISSION_DENIED
        SourceReadIssue.FILE_MISSING -> QueuePlaybackIssue.FILE_MISSING
        SourceReadIssue.SOURCE_CHANGED -> QueuePlaybackIssue.SOURCE_CHANGED
        SourceReadIssue.UNSUPPORTED_FORMAT -> QueuePlaybackIssue.SOURCE_UNSUPPORTED
        else -> QueuePlaybackIssue.SOURCE_UNAVAILABLE
    }).also { it.initCause(failure) }
    is CueException -> MediaTransportException(when (failure.issue) {
        CueIssue.READ_TIMEOUT -> QueuePlaybackIssue.SOURCE_READ_TIMEOUT
        CueIssue.INVALID_AUDIO -> QueuePlaybackIssue.SOURCE_DAMAGED
        CueIssue.UNVERIFIED_CONTENT -> QueuePlaybackIssue.CONTENT_UNKNOWN
        else -> QueuePlaybackIssue.SOURCE_CHANGED
    }).also { it.initCause(failure) }
    else -> MediaTransportException(QueuePlaybackIssue.SOURCE_UNAVAILABLE).also { it.initCause(failure) }
}
