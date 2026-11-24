package io.github.onlinemo.meloxwatch.playback.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 已知 PCM 信号验证输出、尾帧和所有权；测试定义不能代替设备测量。 */
@androidx.annotation.OptIn(UnstableApi::class)
class DspSignalTest {
    @Test fun allEffectsOffPreservesEveryPcmBitAcrossSmallAndLargeChunks() {
        val bytes = pcm(7_013, 2) { index, channel -> ((index * 31 + channel * 997) % 60_000) - 30_000 }
        assertArrayEquals(bytes, process(bytes, DspSettings(), channels = 2, chunkFrames = 37).bytes)
        assertArrayEquals(bytes, process(bytes, DspSettings(), channels = 2, chunkFrames = 4_096).bytes)
    }

    @Test fun stereoLeftMuteLeavesTheRightSignalUnchanged() {
        val bytes = pcm(3_001, 2) { _, channel -> if (channel == 0) 12_000 else -6_000 }
        val result = shorts(process(bytes, DspSettings(leftPercent = 0), channels = 2).bytes)
        for (index in result.indices step 2) {
            assertEquals(0, result[index].toInt())
            assertEquals(-6_000, result[index + 1].toInt())
        }
    }

    @Test fun trackAndAlbumModesApplyTheirOwnMetadataOnce() {
        val source = pcm(512, 1) { _, _ -> 8_192 }
        val tags = ReplayGainTags(trackGainDb = -6.020599913, albumGainDb = -12.041199827)
        val track = shorts(process(source, DspSettings(replayGainMode = ReplayGainMode.TRACK), tags).bytes)
        val album = shorts(process(source, DspSettings(replayGainMode = ReplayGainMode.ALBUM), tags).bytes)
        assertTrue(track.all { it.toInt() == 4_096 })
        assertTrue(album.all { it.toInt() == 2_048 })
        assertEquals(-6.020599913, tags.trackGainDb ?: 0.0, 0.0)
    }

    @Test fun aMissingAlbumTagDoesNotBorrowTheTrackGain() {
        val source = pcm(901, 1) { _, _ -> 4_000 }
        val result = process(source, DspSettings(replayGainMode = ReplayGainMode.ALBUM),
            ReplayGainTags(trackGainDb = -6.0))
        assertArrayEquals(source, result.bytes)
        assertTrue(result.actual.replayGainTagMissing)
    }

    @Test fun metadataPeakProtectionPrecedesEqAndDoesNotApplyGainTwice() {
        val source = pcm(600, 1) { _, _ -> 8_192 }
        val result = process(source, DspSettings(replayGainMode = ReplayGainMode.TRACK, preampTenthsDb = 30),
            ReplayGainTags(trackGainDb = 6.020599913, trackPeak = 2.0))
        assertTrue(shorts(result.bytes).all { it.toInt() == 4_096 })
        assertTrue(!result.actual.replayGainPeakMissing)
    }

    @Test fun oneKilohertzToneMeasuresTheSelectedSixDecibelBand() {
        val source = pcm(48_000, 1) { index, _ -> (sin(2 * Math.PI * 1_000 * index / 48_000) * 1_000).roundToInt() }
        val bands = List(10) { if (it == 5) 60 else 0 }
        val result = process(source, DspSettings(equalizerEnabled = true, bandGainTenthsDb = bands))
        val ratio = rms(shorts(result.bytes), 8_000) / rms(shorts(source), 8_000)
        assertTrue("Measured tone ratio $ratio", ratio in 1.85..2.15)
        assertEquals((0..9).toList(), result.actual.effectiveBandIndices)
    }

    @Test fun limitingContainsExtremeBoostAndPreservesTheEndOfStreamFrames() {
        val source = pcm(4_003, 2) { index, channel ->
            if (index in 1_000..1_040) if (channel == 0) 32_000 else -32_000 else 2_000
        }
        val settings = DspSettings(replayGainMode = ReplayGainMode.TRACK, preampTenthsDb = 120,
            preventClipping = false, limiterEnabled = true)
        val result = process(source, settings, ReplayGainTags(trackGainDb = 12.0), channels = 2, chunkFrames = 19)
        assertEquals(source.size, result.bytes.size)
        val ceiling = (10.0.pow(-1.0 / 20.0) * 32_768).roundToInt() + 1
        assertTrue(shorts(result.bytes).all { abs(it.toInt()) <= ceiling })
        assertTrue(result.actual.limiterEnabled)
    }

    @Test fun processorChunkBoundariesDoNotAlterTheSignalOrDropLimiterTail() {
        val source = pcm(5_017, 2) { index, channel ->
            (sin(2 * Math.PI * (if (channel == 0) 440 else 1_000) * index / 48_000) * 10_000).roundToInt()
        }
        val settings = DspSettings(equalizerEnabled = true,
            bandGainTenthsDb = List(10) { if (it == 5) 90 else 0 }, rightPercent = 50, limiterEnabled = true)
        val small = process(source, settings, channels = 2, chunkFrames = 31)
        val large = process(source, settings, channels = 2, chunkFrames = 4_096)
        assertEquals(source.size, small.bytes.size)
        assertArrayEquals(small.bytes, large.bytes)
    }

    @Test fun seekFlushCannotEmitThePreviousDelayedSignal() {
        val runtime = DspRuntime().also { it.setSaved(DspStored(settings = DspSettings(limiterEnabled = true))) }
        val processor = DspAudioProcessor(runtime)
        processor.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
        processor.flush()
        processor.queueInput(buffer(pcm(100, 1) { _, _ -> 30_000 }))
        assertEquals(0, processor.output.remaining())
        processor.flush()
        val source = pcm(900, 1) { _, _ -> 3_000 }
        val output = ByteArrayOutputStream()
        val input = buffer(source)
        while (input.hasRemaining()) {
            processor.queueInput(input)
            take(processor, output)
        }
        processor.queueEndOfStream()
        take(processor, output)
        assertArrayEquals(source, output.toByteArray())
        assertTrue(processor.isEnded)
        processor.reset()
        assertTrue(!runtime.state.value.processing)
    }

    @Test fun positiveGainWaitsWhenProtectionHasNoPeakEvidence() {
        val source = pcm(1_701, 1) { _, _ -> 8_192 }
        val result = process(source, DspSettings(replayGainMode = ReplayGainMode.TRACK),
            ReplayGainTags(trackGainDb = 12.0))
        assertArrayEquals(source, result.bytes)
        assertTrue(result.actual.replayGainPositiveGainHeld)
        assertEquals(12.0, result.actual.replayGainTags.trackGainDb ?: 0.0, 0.0)
    }

    @Test fun lowOutputSampleRateReportsOnlyUsableEqualizerBands() {
        val runtime = DspRuntime()
        runtime.setSaved(DspStored(settings = DspSettings(equalizerEnabled = true)))
        val processor = DspAudioProcessor(runtime)
        processor.configure(AudioProcessor.AudioFormat(8_000, 1, C.ENCODING_PCM_16BIT))
        processor.flush()
        assertEquals((0..6).toList(), runtime.state.value.effectiveBandIndices)
        assertTrue(!runtime.state.value.stereoControlAvailable)
        processor.reset()
    }

    @Test fun aRetiredSinkResetCannotEraseTheNewSinkFormat() {
        val runtime = DspRuntime()
        runtime.setSaved(DspStored(settings = DspSettings(leftPercent = 75)))
        val retired = DspAudioProcessor(runtime)
        retired.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
        retired.flush()
        val preview = Any()
        assertTrue(runtime.preview(preview, 0, DspSettings(leftPercent = 25)))
        val current = DspAudioProcessor(runtime)
        current.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
        current.flush()
        assertTrue(!runtime.hasPreview(preview))
        assertEquals(75, runtime.state.value.requested.leftPercent)
        retired.reset()
        assertEquals(44_100, runtime.state.value.sampleRate)
        assertEquals(2, runtime.state.value.channels)
        assertTrue(runtime.state.value.processing)
        current.reset()
    }

    @Test fun pendingNextTrackTagsCannotChangeTheCurrentDelayedTail() {
        val settings = DspSettings(replayGainMode = ReplayGainMode.TRACK, limiterEnabled = true)
        val runtime = DspRuntime().also { it.setSaved(DspStored(settings = settings)) }
        val processor = DspAudioProcessor(runtime)
        val oldTags = ReplayGainTagResult(ReplayGainTags(trackGainDb = -6.020599913))
        processor.configureStreamTags(oldTags)
        processor.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
        processor.flush()
        val oldOutput = ByteArrayOutputStream()
        feed(processor, pcm(100, 1) { _, _ -> 8_192 }, oldOutput)
        assertEquals(0, oldOutput.size())
        processor.configureStreamTags(ReplayGainTagResult(ReplayGainTags(trackGainDb = -12.041199827)))
        feed(processor, pcm(701, 1) { _, _ -> 8_192 }, oldOutput)
        processor.queueEndOfStream()
        take(processor, oldOutput)
        assertEquals(801 * 2, oldOutput.size())
        assertTrue(shorts(oldOutput.toByteArray()).all { it.toInt() == 4_096 })

        processor.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT))
        processor.flush()
        val nextOutput = ByteArrayOutputStream()
        feed(processor, pcm(2_401, 1) { _, _ -> 8_192 }, nextOutput)
        processor.queueEndOfStream()
        take(processor, nextOutput)
        val next = shorts(nextOutput.toByteArray())
        assertEquals(2_401, next.size)
        assertTrue(next.take(960).all { it.toInt() in 2_048..4_096 })
        assertTrue(next.drop(960).all { it.toInt() == 2_048 })
        processor.reset()
    }

    @Test fun aCompletedSameFormatBoundaryPreservesEqualizerContinuity() {
        val source = pcm(7_007, 1) { index, _ ->
            (sin(2 * Math.PI * 1_000 * index / 48_000) * 1_000).roundToInt()
        }
        val settings = DspSettings(equalizerEnabled = true, bandGainTenthsDb = List(10) { if (it == 5) 60 else 0 })
        val complete = process(source, settings).bytes
        val runtime = DspRuntime().also { it.setSaved(DspStored(settings = settings)) }
        val processor = DspAudioProcessor(runtime)
        val format = AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.flush()
        val output = ByteArrayOutputStream()
        val boundary = 2_757 * 2
        feed(processor, source.copyOfRange(0, boundary), output)
        processor.queueEndOfStream()
        take(processor, output)
        assertTrue(processor.isEnded)
        processor.configure(format)
        processor.flush()
        feed(processor, source.copyOfRange(boundary, source.size), output)
        processor.queueEndOfStream()
        take(processor, output)
        assertArrayEquals(complete, output.toByteArray())
        processor.reset()
    }

    private data class Result(val bytes: ByteArray, val actual: DspActualState)

    private fun process(
        source: ByteArray,
        settings: DspSettings,
        tags: ReplayGainTags = ReplayGainTags(),
        channels: Int = 1,
        chunkFrames: Int = 127,
    ): Result {
        val runtime = DspRuntime().also { it.setSaved(DspStored(settings = settings)) }
        val processor = DspAudioProcessor(runtime)
        processor.configureStreamTags(ReplayGainTagResult(tags))
        processor.configure(AudioProcessor.AudioFormat(48_000, channels, C.ENCODING_PCM_16BIT))
        processor.flush()
        val output = ByteArrayOutputStream()
        var offset = 0
        while (offset < source.size) {
            val count = minOf(source.size - offset, chunkFrames * channels * 2)
            val input = buffer(source.copyOfRange(offset, offset + count))
            while (input.hasRemaining()) {
                processor.queueInput(input)
                take(processor, output)
            }
            offset += count
        }
        processor.queueEndOfStream()
        take(processor, output)
        assertTrue(processor.isEnded)
        val actual = runtime.state.value
        processor.reset()
        return Result(output.toByteArray(), actual)
    }

    private fun take(processor: DspAudioProcessor, output: ByteArrayOutputStream) {
        val bytes = processor.output
        val copy = ByteArray(bytes.remaining())
        bytes.get(copy)
        output.write(copy)
    }

    private fun feed(processor: DspAudioProcessor, source: ByteArray, output: ByteArrayOutputStream) {
        val input = buffer(source)
        while (input.hasRemaining()) {
            processor.queueInput(input)
            take(processor, output)
        }
    }

    private fun buffer(bytes: ByteArray): ByteBuffer = ByteBuffer.allocateDirect(bytes.size)
        .order(ByteOrder.nativeOrder()).put(bytes).also { it.flip() }

    private fun pcm(frames: Int, channels: Int, sample: (Int, Int) -> Int): ByteArray =
        ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.nativeOrder()).apply {
            for (index in 0 until frames) for (channel in 0 until channels) putShort(sample(index, channel).toShort())
        }.array()

    private fun shorts(bytes: ByteArray): ShortArray = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        .asShortBuffer().let { values -> ShortArray(values.remaining()).also { values.get(it) } }

    private fun rms(samples: ShortArray, from: Int): Double {
        var sum = 0.0
        for (index in from until samples.size) sum += samples[index].toDouble() * samples[index]
        return sqrt(sum / (samples.size - from))
    }
}
