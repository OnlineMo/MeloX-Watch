package io.github.onlinemo.meloxwatch.playback.dsp

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** RBJ peaking 滤波器分别持有各声道历史，系数只在参数改变时计算。 */
internal class PeakingEqualizer(settings: DspSettings, sampleRate: Int, channels: Int) {
    val effectiveBandIndices = if (!settings.equalizerEnabled) emptyList() else
        DspLimits.FREQUENCIES_HZ.indices.filter { DspLimits.FREQUENCIES_HZ[it] < sampleRate * 0.45 }
    private val filters = Array(channels) {
        effectiveBandIndices.filter { settings.bandGainTenthsDb[it] != 0 }.map { index ->
            Biquad(DspLimits.FREQUENCIES_HZ[index], settings.bandGainTenthsDb[index] / 10.0, sampleRate)
        }.toTypedArray()
    }

    fun apply(channel: Int, sample: Double): Double {
        var value = sample
        for (filter in filters[channel]) value = filter.apply(value)
        return value
    }

    private class Biquad(frequency: Int, gainDb: Double, sampleRate: Int) {
        private val amplitude = 10.0.pow(gainDb / 40.0)
        private val omega = 2.0 * Math.PI * frequency / sampleRate
        private val alpha = sin(omega) / (2.0 * sqrt(0.5))
        private val denominator = 1.0 + alpha / amplitude
        private val b0 = (1.0 + alpha * amplitude) / denominator
        private val b1 = -2.0 * cos(omega) / denominator
        private val b2 = (1.0 - alpha * amplitude) / denominator
        private val a1 = b1
        private val a2 = (1.0 - alpha / amplitude) / denominator
        private var z1 = 0.0
        private var z2 = 0.0

        fun apply(input: Double): Double {
            val output = b0 * input + z1
            z1 = b1 * input - a1 * output + z2
            z2 = b2 * input - a2 * output
            if (abs(z1) < 1e-30) z1 = 0.0
            if (abs(z2) < 1e-30) z2 = 0.0
            return if (output.isFinite()) output else 0.0
        }
    }
}
