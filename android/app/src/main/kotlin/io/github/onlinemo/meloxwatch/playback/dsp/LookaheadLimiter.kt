package io.github.onlinemo.meloxwatch.playback.dsp

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** 有界延迟和单调峰值队列保留全部输入帧，EOS 也必须排出完整尾帧。 */
internal class LookaheadLimiter(settings: DspSettings, sampleRate: Int, private val channels: Int) {
    private val capacity = max(1, sampleRate * settings.limiterLookaheadMillis / 1_000)
    private val samples = DoubleArray(capacity * channels)
    private val peaks = DoubleArray(capacity)
    private val peakOrdinals = LongArray(capacity)
    private val threshold = 10.0.pow(settings.limiterThresholdTenthsDb / 200.0)
    private val attack = exp(-1.0 / (sampleRate * settings.limiterAttackMicros / 1_000_000.0))
    private val release = exp(-1.0 / (sampleRate * settings.limiterReleaseMillis / 1_000.0))
    private var sampleHead = 0
    private var sampleCount = 0
    private var peakHead = 0
    private var peakCount = 0
    private var nextOrdinal = 0L
    private var outputOrdinal = 0L
    private var envelope = 1.0
    val pendingFrames: Int get() = sampleCount

    fun queue(frame: DoubleArray, output: ByteBuffer) {
        if (sampleCount == capacity) emit(output)
        val tail = (sampleHead + sampleCount) % capacity
        var peak = 0.0
        for (channel in 0 until channels) {
            samples[tail * channels + channel] = frame[channel]
            peak = max(peak, abs(frame[channel]))
        }
        while (peakCount > 0 && peaks[(peakHead + peakCount - 1) % capacity] <= peak) peakCount--
        val peakTail = (peakHead + peakCount) % capacity
        peaks[peakTail] = peak
        peakOrdinals[peakTail] = nextOrdinal++
        peakCount++
        sampleCount++
    }

    fun drain(output: ByteBuffer) {
        while (sampleCount > 0) emit(output)
    }

    private fun emit(output: ByteBuffer) {
        val windowPeak = if (peakCount == 0) 0.0 else peaks[peakHead]
        val required = if (windowPeak > threshold) threshold / windowPeak else 1.0
        val coefficient = if (required < envelope) attack else release
        envelope = required + coefficient * (envelope - required)
        var currentPeak = 0.0
        for (channel in 0 until channels) currentPeak = max(currentPeak, abs(samples[sampleHead * channels + channel]))
        // attack 尚未收敛时仍钳住当前峰值，避免启动阶段越过阈值。
        val gain = min(envelope, if (currentPeak > threshold) threshold / currentPeak else 1.0)
        for (channel in 0 until channels) putPcm16(output, samples[sampleHead * channels + channel] * gain)
        sampleHead = (sampleHead + 1) % capacity
        sampleCount--
        outputOrdinal++
        while (peakCount > 0 && peakOrdinals[peakHead] < outputOrdinal) {
            peakHead = (peakHead + 1) % capacity
            peakCount--
        }
    }
}

internal fun putPcm16(output: ByteBuffer, sample: Double) {
    val bounded = if (sample.isFinite()) sample.coerceIn(-1.0, 32767.0 / 32768.0) else 0.0
    output.putShort((bounded * 32768.0).roundToInt().coerceIn(-32768, 32767).toShort())
}
