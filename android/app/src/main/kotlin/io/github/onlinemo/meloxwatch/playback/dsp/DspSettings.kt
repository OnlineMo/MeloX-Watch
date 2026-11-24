package io.github.onlinemo.meloxwatch.playback.dsp

import kotlin.math.min
import kotlin.math.pow

enum class ReplayGainMode { OFF, TRACK, ALBUM }

object DspLimits {
    val FREQUENCIES_HZ: List<Int> = listOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)
    const val MAX_PRESETS = 32
    const val MAX_NAME_CHARACTERS = 64
    const val CONFIG_RAMP_MILLIS = 20
    const val MAX_BLOCK_FRAMES = 2_048
}

/** 音效偏好独立于文件授权和播放意图，保存参数不会自动发声。 */
data class DspSettings(
    val equalizerEnabled: Boolean = false,
    val bandGainTenthsDb: List<Int> = List(DspLimits.FREQUENCIES_HZ.size) { 0 },
    val selectedPresetId: String? = null,
    val replayGainMode: ReplayGainMode = ReplayGainMode.OFF,
    val preampTenthsDb: Int = 0,
    val preventClipping: Boolean = true,
    val leftPercent: Int = 100,
    val rightPercent: Int = 100,
    val limiterEnabled: Boolean = false,
    val limiterThresholdTenthsDb: Int = -10,
    val limiterAttackMicros: Int = 500,
    val limiterReleaseMillis: Int = 100,
    val limiterLookaheadMillis: Int = 5,
) {
    init {
        require(bandGainTenthsDb.size == DspLimits.FREQUENCIES_HZ.size)
        require(bandGainTenthsDb.all { it in -120..120 })
        require(selectedPresetId == null || selectedPresetId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(preampTenthsDb in -120..120)
        require(leftPercent in 0..100 && leftPercent % 5 == 0)
        require(rightPercent in 0..100 && rightPercent % 5 == 0)
        require(limiterThresholdTenthsDb in -120..0)
        require(limiterAttackMicros in 100..5_000)
        require(limiterReleaseMillis in 20..1_000)
        require(limiterLookaheadMillis in 1..10)
    }

    fun immutableCopy(): DspSettings = copy(bandGainTenthsDb = bandGainTenthsDb.toList())
}

data class DspPreset(val id: String, val name: String, val bandGainTenthsDb: List<Int>) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(name.isNotBlank() && name.length <= DspLimits.MAX_NAME_CHARACTERS && name.none(Char::isISOControl))
        require(name.codePoints().noneMatch { it in 0xd800..0xdfff })
        require(bandGainTenthsDb.size == DspLimits.FREQUENCIES_HZ.size && bandGainTenthsDb.all { it in -120..120 })
    }
}

/** 增益和峰值保留原标签数值，独立于所选的补偿模式。 */
data class ReplayGainTags(
    val trackGainDb: Double? = null,
    val trackPeak: Double? = null,
    val albumGainDb: Double? = null,
    val albumPeak: Double? = null,
) {
    init {
        require(listOfNotNull(trackGainDb, albumGainDb).all { it.isFinite() && it in -60.0..60.0 })
        require(listOfNotNull(trackPeak, albumPeak).all { it.isFinite() && it > 0.0 && it <= 64.0 })
    }

    fun resolve(settings: DspSettings): ResolvedReplayGain {
        val mode = settings.replayGainMode
        if (mode == ReplayGainMode.OFF) return ResolvedReplayGain(1.0, false, false)
        val gain = if (mode == ReplayGainMode.TRACK) trackGainDb else albumGainDb
        val peak = if (mode == ReplayGainMode.TRACK) trackPeak else albumPeak
        val combined = (gain ?: 0.0) + settings.preampTenthsDb / 10.0
        val bounded = combined.coerceIn(-60.0, 24.0)
        val requested = 10.0.pow(bounded / 20.0)
        // 开启峰值保护时，缺少原峰值不能授权正增益。
        val safe = if (settings.preventClipping) min(requested, if (peak != null) 1.0 / peak else 1.0) else requested
        return ResolvedReplayGain(safe, gain == null, settings.preventClipping && peak == null,
            combined != bounded, settings.preventClipping && peak == null && requested > 1.0)
    }
}

data class ResolvedReplayGain(
    val linearGain: Double,
    val gainTagMissing: Boolean,
    val peakTagMissing: Boolean,
    val gainClamped: Boolean = false,
    val positiveGainHeld: Boolean = false,
)

data class DspInput(val settings: DspSettings = DspSettings(), val tags: ReplayGainTags = ReplayGainTags())

data class DspActualState(
    val processing: Boolean = false,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val effectiveBandIndices: List<Int> = emptyList(),
    val stereoControlAvailable: Boolean = false,
    val replayGainTagMissing: Boolean = false,
    val replayGainPeakMissing: Boolean = false,
    val replayGainTags: ReplayGainTags = ReplayGainTags(),
    val replayGainSource: ReplayGainTagSource = ReplayGainTagSource.NONE,
    val replayGainTagIssue: ReplayGainTagIssue? = null,
    val replayGainLinearGain: Double = 1.0,
    val replayGainClamped: Boolean = false,
    val replayGainPositiveGainHeld: Boolean = false,
    val limiterEnabled: Boolean = false,
    val requested: DspSettings = DspSettings(),
    val issue: DspRuntimeIssue? = DspRuntimeIssue.WAITING_PCM,
)

enum class DspRuntimeIssue { WAITING_PCM, BACKEND_UNSUPPORTED, OUTPUT_UNSUPPORTED, CLOSED }
