package io.github.onlinemo.meloxwatch.model

import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** 帧与采样的边界保持精确，直到需要转换为对外毫秒时钟。 */
object CueTiming {
    const val FRAMES_PER_SECOND = 75L
    const val MAX_FRAMES = Long.MAX_VALUE / 1_000_000L * FRAMES_PER_SECOND +
        Long.MAX_VALUE % 1_000_000L * FRAMES_PER_SECOND / 1_000_000L

    fun microseconds(frames: Long): Long {
        require(frames in 0L..MAX_FRAMES)
        return frames / FRAMES_PER_SECOND * 1_000_000L +
            frames % FRAMES_PER_SECOND * 1_000_000L / FRAMES_PER_SECOND
    }

    fun sampleAtOrAfter(frames: Long, sampleRateHz: Int): Long {
        require(frames in 0L..MAX_FRAMES && sampleRateHz in 1..192_000)
        val whole = Math.multiplyExact(frames / FRAMES_PER_SECOND, sampleRateHz.toLong())
        val remainder = frames % FRAMES_PER_SECOND * sampleRateHz
        val fraction = remainder / FRAMES_PER_SECOND + if (remainder % FRAMES_PER_SECOND == 0L) 0L else 1L
        return Math.addExact(whole, fraction)
    }

    fun sampleDurationUs(samples: Long, sampleRateHz: Int): Long {
        require(samples >= 0L && sampleRateHz in 1..192_000)
        return Math.addExact(Math.multiplyExact(samples / sampleRateHz, 1_000_000L),
            samples % sampleRateHz * 1_000_000L / sampleRateHz)
    }
}

/** 可移植的标识与边界不授予文件访问能力；标题或音频路径不能替代 cueId。 */
data class CueTrackReference(
    val sourceId: String,
    val cueId: String,
    val trackNumber: Int,
    val groupRevision: Long,
    val startFrame: Long,
    val endFrameExclusive: Long?,
    val endUsExclusive: Long,
    val index00Frame: Long?,
    val cueContentToken: String,
    val boundaryVersion: Int = 1,
    val audioContentTokenHint: String? = null,
) {
    init {
        cueIdentifier(sourceId, 128)
        cueIdentifier(cueId, 128)
        require(trackNumber in 1..99 && groupRevision > 0L && boundaryVersion == 1)
        require(startFrame in 0L..CueTiming.MAX_FRAMES)
        require(endFrameExclusive == null || endFrameExclusive in (startFrame + 1L)..CueTiming.MAX_FRAMES)
        require(index00Frame == null || index00Frame in 0L..startFrame)
        require(endUsExclusive > CueTiming.microseconds(startFrame))
        require(endFrameExclusive == null || CueTiming.microseconds(endFrameExclusive) == endUsExclusive)
        require(cueContentToken.matches(CUE_CONTENT_TOKEN))
        require(audioContentTokenHint == null || audioContentTokenHint.matches(CUE_CONTENT_TOKEN))
    }

    val startUs: Long get() = CueTiming.microseconds(startFrame)
    val durationUs: Long get() = endUsExclusive - startUs
    val timelineOriginMs: Long get() = startUs / 1000L
    // Stable identity depends only on this immutable triple; duplicate queue rows reuse its hash.
    val trackKey: TrackKey by lazy(LazyThreadSafetyMode.PUBLICATION) { cueTrackKey(sourceId, cueId, trackNumber) }
    override fun toString(): String = "CueTrackReference(track=$trackNumber, revision=$groupRevision)"
}

/** 每份文件携带各自已登记的来源、授权与内容围栏。 */
data class CueFileBinding(
    val sourceId: String,
    val sourceRevision: Long,
    val authEpoch: Long,
    val grantId: String,
    val aliasId: String,
    val objectId: String,
    val location: LocalLocationReference,
    val providerContentToken: String?,
    val verifiedContentToken: String?,
    val sizeBytes: Long,
) {
    init {
        cueIdentifier(sourceId, 128)
        cueIdentifier(grantId, 128)
        cueIdentifier(aliasId, 128)
        cueIdentifier(objectId, 8192)
        require(sourceRevision > 0L && authEpoch > 0L && location.contentRevision > 0L && sizeBytes > 0L)
        require(providerContentToken == null || providerContentToken.length in 1..4096)
        require(verifiedContentToken == null || verifiedContentToken.matches(CUE_CONTENT_TOKEN))
        val uri = URI(location.uri)
        val grant = URI(location.grantUri)
        require(uri.scheme == "content" && !uri.authority.isNullOrBlank() && grant.scheme == "content" && uri.authority == grant.authority)
    }
    override fun toString(): String = "CueFileBinding(redacted)"
}

/** 真实 RIFF PCM16 范围；后缀、CUE FILE 类型和元数据读取器的毫秒值都不足以证明。 */
data class CuePcmWaveFacts(
    val fileBytes: Long,
    val dataOffset: Long,
    val dataBytes: Long,
    val sampleRateHz: Int,
    val channels: Int,
    val bitsPerSample: Int = 16,
) {
    init {
        require(fileBytes in 44L..0x1_0000_0007L && dataOffset >= 44L && dataBytes > 0L)
        require(sampleRateHz in 1..192_000 && channels in 1..2 && bitsPerSample == 16)
        require(dataOffset <= fileBytes && dataBytes <= fileBytes - dataOffset && dataBytes % frameBytes == 0L)
        require(sampleFrames > 0L)
    }
    val frameBytes: Int get() = channels * (bitsPerSample / 8)
    val sampleFrames: Long get() = dataBytes / frameBytes
    val durationUs: Long get() = CueTiming.sampleDurationUs(sampleFrames, sampleRateHz)

    fun window(reference: CueTrackReference): CuePcmWindow {
        require(reference.endUsExclusive <= durationUs)
        val first = CueTiming.sampleAtOrAfter(reference.startFrame, sampleRateHz)
        val end = reference.endFrameExclusive?.let { CueTiming.sampleAtOrAfter(it, sampleRateHz) } ?: sampleFrames
        require(first in 0L until end && end <= sampleFrames)
        return CuePcmWindow(first, end, Math.addExact(dataOffset, Math.multiplyExact(first, frameBytes.toLong())),
            Math.multiplyExact(end - first, frameBytes.toLong()), CueTiming.sampleDurationUs(end - first, sampleRateHz))
    }
}

data class CuePcmWindow(
    val firstSample: Long,
    val endSampleExclusive: Long,
    val physicalByteOffset: Long,
    val payloadBytes: Long,
    val durationUs: Long,
)

/** 实际登记的文档与显式保留的旧逻辑标识分开存放。 */
data class CueBoundDocumentReference(val sourceId: String, val cueId: String, val groupRevision: Long, val trackNumber: Int) {
    init {
        cueIdentifier(sourceId, 128)
        cueIdentifier(cueId, 128)
        require(groupRevision > 0L && trackNumber in 1..99)
    }
}

data class CueSegmentBinding(
    val cue: CueFileBinding,
    val audio: CueFileBinding,
    val pcm: CuePcmWaveFacts,
    val actualDocument: CueBoundDocumentReference? = null,
    val adoptionAliasId: String? = null,
) {
    init { require(audio.sizeBytes == pcm.fileBytes && cue.location.locationId != audio.location.locationId) }
    override fun toString(): String = "CueSegmentBinding(redacted)"
}

/** 待确认的恢复与交换提示保留分段语义，绝不能据此播放完整文件。 */
data class LocalCueReference(val identity: CueTrackReference, val binding: CueSegmentBinding? = null) {
    init {
        if (binding != null) {
            val actual = actualDocument
            require(actual.sourceId == binding.cue.sourceId && actual.trackNumber == identity.trackNumber)
            require(binding.cue.verifiedContentToken == identity.cueContentToken)
            require(identity.audioContentTokenHint == null || identity.audioContentTokenHint == binding.audio.verifiedContentToken)
            if (actual.sourceId != identity.sourceId || actual.cueId != identity.cueId) {
                require(!binding.adoptionAliasId.isNullOrBlank())
            }
            if (binding.adoptionAliasId == null) require(actual.groupRevision == identity.groupRevision)
            else cueIdentifier(binding.adoptionAliasId, 128)
            binding.pcm.window(identity)
        }
    }
    val isBound: Boolean get() = binding != null
    val trackKey: TrackKey get() = identity.trackKey
    val actualDocument: CueBoundDocumentReference get() = binding?.actualDocument ?:
        CueBoundDocumentReference(identity.sourceId, identity.cueId, identity.groupRevision, identity.trackNumber)
    val pcmWindow: CuePcmWindow? get() = binding?.pcm?.window(identity)
    fun portable(): LocalCueReference = LocalCueReference(identity.copy(
        audioContentTokenHint = identity.audioContentTokenHint ?: binding?.audio?.verifiedContentToken), binding = null)
    fun requireEntry(key: TrackKey, location: LocalLocationReference?) {
        require(key == trackKey)
        if (binding != null) require(location == binding.audio.location)
    }
    override fun toString(): String = "LocalCueReference(bound=$isBound)"
}

fun cueTrackKey(sourceId: String, cueId: String, trackNumber: Int): TrackKey {
    cueIdentifier(sourceId, 128)
    cueIdentifier(cueId, 128)
    require(trackNumber in 1..99)
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("melox-cue-track:v1:".toByteArray(StandardCharsets.US_ASCII))
    for (component in listOf(sourceId, cueId)) {
        val bytes = component.toByteArray(StandardCharsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
        digest.update(':'.code.toByte())
        digest.update(bytes)
    }
    digest.update(trackNumber.toByte())
    val alphabet = "0123456789abcdef"
    val hex = buildString(64) {
        for (byte in digest.digest()) {
            val value = byte.toInt() and 255
            append(alphabet[value ushr 4]);
            append(alphabet[value and 15])
        }
    }
    return TrackKey(TrackKey.LOCAL_PROVIDER, "cue-v1-$hex")
}

private fun cueIdentifier(value: String, maximum: Int) {
    require(value.isNotBlank() && value.length <= maximum && value.none { Character.isISOControl(it) })
    require(StandardCharsets.UTF_8.newEncoder().canEncode(value))
}

private val CUE_CONTENT_TOKEN = Regex("sha256:[0-9a-f]{64}")
