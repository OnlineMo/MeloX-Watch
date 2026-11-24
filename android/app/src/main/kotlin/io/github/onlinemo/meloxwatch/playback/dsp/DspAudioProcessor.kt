package io.github.onlinemo.meloxwatch.playback.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/** 唯一输出 PCM 通路依次处理 ReplayGain、EQ、声道增益和前瞻压限。 */
@androidx.annotation.OptIn(UnstableApi::class)
class DspAudioProcessor(private val runtime: DspRuntime) : BaseAudioProcessor() {
    internal val ticket = runtime.attachSink()
    private var pendingTags = ReplayGainTagResult()
    private var activeTags = ReplayGainTagResult()
    private var current: DspInput? = null
    private var equalizer: PeakingEqualizer? = null
    private var previousEqualizer: PeakingEqualizer? = null
    private var installedEqualizerSettings: DspSettings? = null
    private var pendingEqualizerSettings: DspSettings? = null
    private var limiter: LookaheadLimiter? = null
    private var frame = DoubleArray(0)
    private var gain = 1.0
    private var left = 1.0
    private var right = 1.0
    private var gainTarget = 1.0
    private var leftTarget = 1.0
    private var rightTarget = 1.0
    private var rampFrames = 1
    private var gainRampRemaining = 0
    private var equalizerRampRemaining = 0
    private var previousFormat = AudioProcessor.AudioFormat.NOT_SET
    private var completedStream = false
    private var hardFlush = false
    private var retainedOutput = AudioProcessor.EMPTY_BUFFER

    internal fun configureStreamTags(tags: ReplayGainTagResult) {
        pendingTags = tags
    }

    internal fun markHardFlush() {
        hardFlush = true
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount !in 1..8 ||
            inputAudioFormat.sampleRate !in 8_000..192_000) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        // 关闭音效时仍保留零处理通路，实时偏好变化不会绕过当前处理链。
        return inputAudioFormat
    }

    override fun onFlush() {
        if (inputAudioFormat == AudioProcessor.AudioFormat.NOT_SET) return
        val drained = completedStream && !retainedOutput.hasRemaining() && (limiter?.pendingFrames ?: 0) == 0
        val keepHistory = drained && !hardFlush && inputAudioFormat == previousFormat && current != null
        retainedOutput = AudioProcessor.EMPTY_BUFFER
        activeTags = pendingTags
        frame = DoubleArray(inputAudioFormat.channelCount)
        rampFrames = max(1, inputAudioFormat.sampleRate * DspLimits.CONFIG_RAMP_MILLIS / 1_000)
        if (!keepHistory) {
            current = null
            previousEqualizer = null
            installedEqualizerSettings = null
            pendingEqualizerSettings = null
            limiter = null
        }
        // 完整尾帧已排出且格式相同才延续滤波历史；seek/销毁必须清掉旧信号。
        install(DspInput(runtime.currentSettings(), activeTags.tags), immediate = !keepHistory)
        previousFormat = inputAudioFormat
        completedStream = false
        hardFlush = false
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        check(!hasPendingOutput() && !retainedOutput.hasRemaining())
        val channels = inputAudioFormat.channelCount
        val frameBytes = channels * 2
        require(frameBytes > 0 && inputBuffer.remaining() % frameBytes == 0)
        val frames = min(DspLimits.MAX_BLOCK_FRAMES, inputBuffer.remaining() / frameBytes)
        if (frames == 0) return
        val next = DspInput(runtime.currentSettings(), activeTags.tags)
        val changed = next != current
        val pending = if (changed) limiter?.pendingFrames ?: 0 else 0
        val output = replaceOutputBuffer((frames + pending) * frameBytes)
        retainedOutput = output
        if (changed) {
            limiter?.drain(output)
            install(next, immediate = false)
        }
        val settings = checkNotNull(current).settings
        if (!settings.equalizerEnabled && settings.replayGainMode == ReplayGainMode.OFF &&
            settings.leftPercent == 100 && settings.rightPercent == 100 && !settings.limiterEnabled &&
            previousEqualizer == null && pendingEqualizerSettings == null &&
            installedEqualizerSettings?.equalizerEnabled != true && gainRampRemaining == 0) {
            val oldLimit = inputBuffer.limit()
            try {
                inputBuffer.limit(inputBuffer.position() + frames * frameBytes)
                output.put(inputBuffer)
            } finally {
                inputBuffer.limit(oldLimit)
            }
            output.flip()
            return
        }
        inputBuffer.order(ByteOrder.nativeOrder())
        repeat(frames) {
            if (equalizerRampRemaining == 0) {
                pendingEqualizerSettings?.let {
                    installEqualizer(it, immediate = false)
                    publishActual(checkNotNull(current).settings)
                }
            }
            if (gainRampRemaining > 0) {
                gain += (gainTarget - gain) / gainRampRemaining
                left += (leftTarget - left) / gainRampRemaining
                right += (rightTarget - right) / gainRampRemaining
                gainRampRemaining--
            }
            val blend = 1.0 - equalizerRampRemaining.toDouble() / rampFrames
            for (channel in 0 until channels) {
                val value = inputBuffer.short / 32768.0 * gain
                val filtered = checkNotNull(equalizer).apply(channel, value)
                val previous = previousEqualizer
                val selected = if (previous != null) previous.apply(channel, value) * (1.0 - blend) + filtered * blend else filtered
                val channelGain = if (channels == 2) if (channel == 0) left else right else 1.0
                frame[channel] = selected * channelGain
            }
            if (equalizerRampRemaining > 0 && --equalizerRampRemaining == 0) previousEqualizer = null
            val activeLimiter = limiter
            if (activeLimiter == null) for (channel in 0 until channels) putPcm16(output, frame[channel])
            else activeLimiter.queue(frame, output)
        }
        output.flip()
    }

    override fun onQueueEndOfStream() {
        // Pipeline 可在 AudioTrack 仍持有返回缓冲时发 EOS；此处不能清空或复用该对象。
        completedStream = true
    }

    override fun getOutput(): ByteBuffer {
        val pending = super.getOutput()
        if (pending.hasRemaining()) {
            retainedOutput = pending
            return pending
        }
        if (retainedOutput.hasRemaining()) return retainedOutput
        val active = limiter
        if (completedStream && active != null && active.pendingFrames > 0) {
            // 唯一输出缓冲已实际消费完毕，才可生成延迟尾帧；重复 EOS 不会重排它们。
            val output = replaceOutputBuffer(active.pendingFrames * inputAudioFormat.channelCount * 2)
            retainedOutput = output
            active.drain(output)
            output.flip()
            return super.getOutput()
        }
        return pending
    }

    override fun isEnded(): Boolean =
        super.isEnded() && !retainedOutput.hasRemaining() && (limiter?.pendingFrames ?: 0) == 0

    override fun onReset() {
        current = null
        equalizer = null
        previousEqualizer = null
        installedEqualizerSettings = null
        pendingEqualizerSettings = null
        limiter = null
        frame = DoubleArray(0)
        activeTags = ReplayGainTagResult()
        pendingTags = ReplayGainTagResult()
        previousFormat = AudioProcessor.AudioFormat.NOT_SET
        completedStream = false
        hardFlush = false
        retainedOutput = AudioProcessor.EMPTY_BUFFER
        runtime.report(ticket, DspActualState(requested = runtime.currentSettings()))
    }

    private fun install(next: DspInput, immediate: Boolean) {
        val previous = current
        val settings = next.settings
        val resolved = next.tags.resolve(settings)
        gainTarget = resolved.linearGain
        leftTarget = settings.leftPercent / 100.0
        rightTarget = settings.rightPercent / 100.0
        if (immediate) {
            gain = gainTarget
            left = leftTarget
            right = rightTarget
            gainRampRemaining = 0
        } else gainRampRemaining = rampFrames
        if (sameEqualizer(installedEqualizerSettings, settings)) {
            pendingEqualizerSettings = null
        } else if (immediate || equalizerRampRemaining == 0) {
            installEqualizer(settings, immediate)
        } else {
            // 打断混合信号会跳回一整套滤波器。保留正在进行的过渡，只留下最新待用请求。
            pendingEqualizerSettings = settings
        }
        val sameLimiter = previous != null && previous.settings.limiterEnabled == settings.limiterEnabled &&
            previous.settings.limiterThresholdTenthsDb == settings.limiterThresholdTenthsDb &&
            previous.settings.limiterAttackMicros == settings.limiterAttackMicros &&
            previous.settings.limiterReleaseMillis == settings.limiterReleaseMillis &&
            previous.settings.limiterLookaheadMillis == settings.limiterLookaheadMillis
        limiter = if (!settings.limiterEnabled) null else if (!immediate && sameLimiter && limiter != null) limiter
            else LookaheadLimiter(settings, inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        current = next
        publishActual(settings, resolved)
    }

    private fun publishActual(settings: DspSettings, resolved: ResolvedReplayGain = activeTags.tags.resolve(settings)) {
        runtime.report(ticket, DspActualState(
            processing = true,
            sampleRate = inputAudioFormat.sampleRate,
            channels = inputAudioFormat.channelCount,
            effectiveBandIndices = checkNotNull(equalizer).effectiveBandIndices,
            stereoControlAvailable = inputAudioFormat.channelCount == 2,
            replayGainTagMissing = resolved.gainTagMissing,
            replayGainPeakMissing = resolved.peakTagMissing,
            replayGainTags = activeTags.tags,
            replayGainSource = activeTags.source,
            replayGainTagIssue = activeTags.issue,
            replayGainLinearGain = resolved.linearGain,
            replayGainClamped = resolved.gainClamped,
            replayGainPositiveGainHeld = resolved.positiveGainHeld,
            limiterEnabled = settings.limiterEnabled,
            requested = settings,
            issue = null,
        ))
    }

    private fun installEqualizer(settings: DspSettings, immediate: Boolean) {
        previousEqualizer = if (immediate) null else equalizer
        equalizerRampRemaining = if (previousEqualizer == null) 0 else rampFrames
        equalizer = PeakingEqualizer(settings, inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        installedEqualizerSettings = settings
        pendingEqualizerSettings = null
    }

    private fun sameEqualizer(first: DspSettings?, second: DspSettings): Boolean =
        first != null && first.equalizerEnabled == second.equalizerEnabled &&
            (!second.equalizerEnabled || first.bandGainTenthsDb == second.bandGainTenthsDb)
}
