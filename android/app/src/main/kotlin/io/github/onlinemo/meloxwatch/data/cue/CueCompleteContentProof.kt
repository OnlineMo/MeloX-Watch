package io.github.onlinemo.meloxwatch.data.cue

import android.os.SystemClock
import io.github.onlinemo.meloxwatch.model.CuePcmWaveFacts
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** CUE校验对内存和任务所有者设界；经典RIFF提供有限的字节范围。 */
object CueReadLimits {
    const val STREAM_BYTES = 16 * 1024
    const val RIFF_MAX_BYTES = 0x1_0000_0007L
    const val CUE_MAX_BYTES = 1024 * 1024L
    const val HEADER_BYTES = 1024 * 1024L
    private const val BASE_TIMEOUT_MS = 10_000L
    private const val WORK_QUANTUM_BYTES = 256 * 1024L

    fun timeoutMs(workBytes: Long): Long {
        require(workBytes > 0L)
        val quanta = workBytes / WORK_QUANTUM_BYTES + if (workBytes % WORK_QUANTUM_BYTES == 0L) 0L else 1L
        return Math.addExact(BASE_TIMEOUT_MS, Math.multiplyExact(quanta, 1000L))
    }

    fun waveProofWorkBytes(fileBytes: Long): Long {
        requireWaveExtent(fileBytes)
        return Math.addExact(Math.multiplyExact(fileBytes, 2L), minOf(fileBytes, HEADER_BYTES))
    }

    /** 一次完整散列先于两段结构范围读取；重新定位从新的块缓存开始。 */
    fun waveRuntimeWorkBytes(pcm: CuePcmWaveFacts): Long {
        requireWaveExtent(pcm.fileBytes)
        if (pcm.fileBytes - pcm.dataBytes > HEADER_BYTES) throw CueException(CueIssue.LIMIT_EXCEEDED)
        val blockBytes = STREAM_BYTES.toLong()
        val dataEnd = Math.addExact(pcm.dataOffset, pcm.dataBytes)
        require(dataEnd <= pcm.fileBytes)
        val prefixBlocks = Math.addExact(pcm.dataOffset, blockBytes - 1L) / blockBytes
        val prefix = minOf(pcm.fileBytes, Math.multiplyExact(prefixBlocks, blockBytes))
        val suffix = if (dataEnd == pcm.fileBytes) 0L else
            pcm.fileBytes - Math.multiplyExact(dataEnd / blockBytes, blockBytes)
        return Math.addExact(pcm.fileBytes, Math.addExact(prefix, suffix))
    }

    fun requireWaveExtent(fileBytes: Long) {
        if (fileBytes !in 12L..RIFF_MAX_BYTES) throw CueException(CueIssue.INVALID_AUDIO)
    }
}

/** 一份单调时钟预算覆盖完整内容校验，包括定位和前后fence核验。 */
class CueReadDeadline(
    workBytes: Long,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) {
    val timeoutMs = CueReadLimits.timeoutMs(workBytes)
    private val started = nowMs()

    fun check() {
        val current = nowMs()
        if (current < started || current - started >= timeoutMs) throw CueException(CueIssue.READ_TIMEOUT)
    }
}

/** 每个活动校验只持有一个可移除ticket；回调仅请求已注册的物理退休。 */
class CueReadWatchdog(private val deadline: CueReadDeadline, requestRetirement: () -> Unit) : AutoCloseable {
    private val lock = Any()
    private enum class Phase { ACTIVE, COMPLETED, EXPIRED }
    private var phase = Phase.ACTIVE
    private val ticket: ScheduledFuture<*> = scheduler.schedule({
        synchronized(lock) {
            if (phase == Phase.ACTIVE) {
                phase = Phase.EXPIRED;
                requestRetirement()
            }
        }
    }, deadline.timeoutMs, TimeUnit.MILLISECONDS)
    fun complete(): Boolean = synchronized(lock) {
        if (phase == Phase.ACTIVE) {
            phase = try {
                deadline.check();
                Phase.COMPLETED
            } catch (_: CueException) {
                Phase.EXPIRED
            }
        }
        ticket.cancel(false)
        phase == Phase.COMPLETED
    }
    override fun close() { complete() }
    companion object {
        private val scheduler by lazy {
            ScheduledThreadPoolExecutor(1) { action -> Thread(action, "melox-cue-deadline").apply { isDaemon = true } }
                .apply { removeOnCancelPolicy = true }
        }
    }
}

/** SHA仅来自范围已核验且完整读至EOF的字节，不接受前缀或provider token替代。 */
object CueCompleteContentProof {
    fun hash(input: InputStream, expectedBytes: Long, checkpoint: () -> Unit): String {
        if (expectedBytes <= 0L || expectedBytes > CueReadLimits.RIFF_MAX_BYTES) throw CueException(CueIssue.INVALID_AUDIO)
        val buffer = ByteArray(CueReadLimits.STREAM_BYTES)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        while (total < expectedBytes) {
            checkpoint()
            val wanted = minOf(buffer.size.toLong(), expectedBytes - total).toInt()
            val count = input.read(buffer, 0, wanted)
            if (count <= 0 || count > wanted) throw CueException(CueIssue.SOURCE_CHANGED)
            total = Math.addExact(total, count.toLong())
            digest.update(buffer, 0, count)
            checkpoint()
        }
        checkpoint()
        if (input.read() != -1) throw CueException(CueIssue.SOURCE_CHANGED)
        checkpoint()
        return token(digest.digest())
    }

    fun token(digest: ByteArray): String {
        require(digest.size == 32)
        val alphabet = "0123456789abcdef"
        return buildString(71) {
            append("sha256:")
            for (byte in digest) {
                val value = byte.toInt() and 255
                append(alphabet[value ushr 4])
                append(alphabet[value and 15])
            }
        }
    }
}
