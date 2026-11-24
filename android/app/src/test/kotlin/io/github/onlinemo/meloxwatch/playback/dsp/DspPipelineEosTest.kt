package io.github.onlinemo.meloxwatch.playback.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.google.common.collect.ImmutableList
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 固定 Media3 真实 Pipeline 的回压和 EOS，不能以直接处理器全量 take 替代该调用顺序。 */
@androidx.annotation.OptIn(UnstableApi::class)
class DspPipelineEosTest {
    @Test fun soleProcessorEosPreservesAlreadyReturnedBytesAndAllDelayedFrames() {
        val source = pcm(500, 2) { index, channel -> index * 11 - 3_000 + channel * 900 }
        val harness = harness(DspSettings(limiterEnabled = true), channels = 2)
        val input = buffer(source)
        harness.pipeline.queueInput(input)
        assertFalse(input.hasRemaining())
        val retained = harness.pipeline.getOutput()
        assertEquals((500 - 240) * 4, retained.remaining())
        val output = ByteArrayOutputStream()
        append(retained, output, 17 * 4)
        val position = retained.position()
        val limit = retained.limit()
        val remaining = copyRemaining(retained)

        harness.pipeline.queueEndOfStream()
        assertEquals(position, retained.position())
        assertEquals(limit, retained.limit())
        assertArrayEquals(remaining, copyRemaining(retained))
        assertSame(retained, harness.pipeline.getOutput())
        assertFalse(harness.pipeline.isEnded())
        assertFalse(harness.processor.isEnded())
        drain(harness.pipeline, output)

        assertArrayEquals(source, output.toByteArray())
        assertTrue(harness.processor.isEnded())
        harness.pipeline.reset()
    }

    @Test fun repeatedUpstreamEosCannotReplaceAPartlyConsumedLimiterTail() {
        val source = pcm(701, 1) { index, _ -> index * 13 - 4_000 }
        val harness = harness(DspSettings(limiterEnabled = true), upstream = true)
        val output = ByteArrayOutputStream()
        feed(harness.pipeline, source, output)
        harness.pipeline.queueEndOfStream()
        val tail = harness.pipeline.getOutput()
        assertEquals(240 * 2, tail.remaining())
        append(tail, output, 9 * 2)
        val remaining = copyRemaining(tail)
        val position = tail.position()
        val limit = tail.limit()

        // processData 会在已结束上游再被拉取时重复向下游传播 EOS。
        harness.processor.queueEndOfStream()
        harness.processor.queueEndOfStream()
        assertEquals(position, tail.position())
        assertEquals(limit, tail.limit())
        assertArrayEquals(remaining, copyRemaining(tail))
        assertFalse(harness.pipeline.isEnded())
        drain(harness.pipeline, output)
        harness.pipeline.queueEndOfStream()
        assertTrue(harness.pipeline.isEnded())
        assertEquals(0, harness.pipeline.getOutput().remaining())
        assertArrayEquals(source, output.toByteArray())
        harness.pipeline.reset()
    }

    @Test fun aShortStreamEndsOnlyAfterItsDelayedOutputHasBeenConsumed() {
        val source = pcm(23, 2) { index, channel -> 7_000 - index * 71 - channel * 2_000 }
        val harness = harness(DspSettings(limiterEnabled = true), channels = 2)
        val input = buffer(source)
        harness.pipeline.queueInput(input)
        assertFalse(input.hasRemaining())
        assertEquals(0, harness.pipeline.getOutput().remaining())
        harness.pipeline.queueEndOfStream()
        assertFalse(harness.pipeline.isEnded())
        val tail = harness.pipeline.getOutput()
        assertEquals(source.size, tail.remaining())
        assertFalse(harness.processor.isEnded())
        val output = ByteArrayOutputStream()
        append(tail, output, 3 * 4)
        assertFalse(harness.pipeline.isEnded())
        assertFalse(harness.processor.isEnded())
        drain(harness.pipeline, output)
        assertArrayEquals(source, output.toByteArray())
        assertTrue(harness.processor.isEnded())
        harness.pipeline.reset()
    }

    @Test fun effectsOffRetainedOutputIsNotDuplicatedAtEos() {
        val source = pcm(333, 2) { index, channel -> index * 19 - 6_000 + channel * 2_000 }
        val harness = harness(DspSettings(), channels = 2)
        val output = ByteArrayOutputStream()
        val input = buffer(source)
        harness.pipeline.queueInput(input)
        val retained = harness.pipeline.getOutput()
        append(retained, output, 5 * 4)
        val before = copyRemaining(retained)
        harness.pipeline.queueEndOfStream()
        assertArrayEquals(before, copyRemaining(retained))
        assertFalse(harness.processor.isEnded())
        drain(harness.pipeline, output)
        assertArrayEquals(source, output.toByteArray())
        harness.pipeline.reset()
    }

    @Test fun drainedSameFormatBoundaryKeepsEqualizerSignalContinuousWithLimiterEnabled() {
        val source = pcm(7_007, 2) { index, channel ->
            val frequency = if (channel == 0) 1_000 else 440
            (sin(2.0 * Math.PI * frequency * index / 48_000) * 1_500).roundToInt()
        }
        val settings = DspSettings(equalizerEnabled = true,
            bandGainTenthsDb = List(10) { if (it == 5) 60 else 0 }, limiterEnabled = true)
        val expected = complete(source, settings, channels = 2)
        val harness = harness(settings, channels = 2)
        val output = ByteArrayOutputStream()
        val boundary = 2_757 * 4
        val retained = feedRetainingFinalOutput(harness.pipeline, source.copyOfRange(0, boundary), output)
        append(retained, output, minOf(7 * 4, retained.remaining()))
        harness.pipeline.configure(harness.format)
        harness.pipeline.queueEndOfStream()
        drain(harness.pipeline, output)
        assertTrue(harness.processor.isEnded())
        harness.pipeline.flush()
        feed(harness.pipeline, source.copyOfRange(boundary, source.size), output)
        harness.pipeline.queueEndOfStream()
        drain(harness.pipeline, output)
        assertArrayEquals(expected, output.toByteArray())
        harness.pipeline.reset()
    }

    @Test fun seekHardFlushDropsUnconsumedOutputAndDelayedSignalAfterEos() {
        val settings = DspSettings(equalizerEnabled = true,
            bandGainTenthsDb = List(10) { if (it == 5) 60 else 0 }, limiterEnabled = true)
        val harness = harness(settings)
        harness.pipeline.queueInput(buffer(pcm(1_200, 1) { index, _ ->
            (sin(2.0 * Math.PI * 1_000 * index / 48_000) * 20_000).roundToInt()
        }))
        val oldOutput = harness.pipeline.getOutput()
        assertTrue(oldOutput.hasRemaining())
        oldOutput.position(oldOutput.position() + 3 * 2)
        harness.pipeline.queueEndOfStream()
        assertFalse(harness.pipeline.isEnded())
        harness.processor.markHardFlush()
        harness.pipeline.flush()

        val source = pcm(901, 1) { index, _ ->
            (sin(2.0 * Math.PI * 440 * index / 48_000) * 2_000).roundToInt()
        }
        val output = ByteArrayOutputStream()
        feed(harness.pipeline, source, output)
        harness.pipeline.queueEndOfStream()
        drain(harness.pipeline, output)
        assertArrayEquals(complete(source, settings), output.toByteArray())
        harness.pipeline.reset()
    }

    @Test fun flushAfterUndrainedEosCannotTreatTheOldSignalAsACompletedBoundary() {
        val settings = DspSettings(equalizerEnabled = true,
            bandGainTenthsDb = List(10) { if (it == 5) 60 else 0 }, limiterEnabled = true)
        val harness = harness(settings)
        harness.pipeline.queueInput(buffer(pcm(1_200, 1) { index, _ ->
            (sin(2.0 * Math.PI * 1_000 * index / 48_000) * 12_000).roundToInt()
        }))
        val retained = harness.pipeline.getOutput()
        assertTrue(retained.hasRemaining())
        harness.pipeline.queueEndOfStream()
        assertFalse(harness.pipeline.isEnded())
        harness.pipeline.flush()
        val source = pcm(901, 1) { _, _ -> 0 }
        val output = ByteArrayOutputStream()
        feed(harness.pipeline, source, output)
        harness.pipeline.queueEndOfStream()
        drain(harness.pipeline, output)
        assertArrayEquals(source, output.toByteArray())
        harness.pipeline.reset()
    }

    private data class Harness(
        val processor: DspAudioProcessor,
        val pipeline: AudioProcessingPipeline,
        val format: AudioProcessor.AudioFormat,
    )

    private fun harness(settings: DspSettings, channels: Int = 1, upstream: Boolean = false): Harness {
        val runtime = DspRuntime().also { it.setSaved(DspStored(settings = settings)) }
        val processor = DspAudioProcessor(runtime)
        val processors = if (upstream) ImmutableList.of<AudioProcessor>(IdentityProcessor(), processor)
            else ImmutableList.of<AudioProcessor>(processor)
        val pipeline = AudioProcessingPipeline(processors)
        val format = AudioProcessor.AudioFormat(48_000, channels, C.ENCODING_PCM_16BIT)
        pipeline.configure(format)
        pipeline.flush()
        return Harness(processor, pipeline, format)
    }

    private fun complete(source: ByteArray, settings: DspSettings, channels: Int = 1): ByteArray {
        val harness = harness(settings, channels)
        val output = ByteArrayOutputStream()
        feed(harness.pipeline, source, output)
        harness.pipeline.queueEndOfStream()
        drain(harness.pipeline, output)
        harness.pipeline.reset()
        return output.toByteArray()
    }

    private fun feed(pipeline: AudioProcessingPipeline, source: ByteArray, output: ByteArrayOutputStream) {
        val input = buffer(source)
        while (input.hasRemaining()) {
            val position = input.position()
            pipeline.queueInput(input)
            append(pipeline.getOutput(), output)
            assertTrue(input.position() > position)
        }
    }

    private fun feedRetainingFinalOutput(
        pipeline: AudioProcessingPipeline,
        source: ByteArray,
        output: ByteArrayOutputStream,
    ): ByteBuffer {
        val input = buffer(source)
        while (input.hasRemaining()) {
            val position = input.position()
            pipeline.queueInput(input)
            val pending = pipeline.getOutput()
            assertTrue(input.position() > position)
            if (!input.hasRemaining()) return pending
            append(pending, output)
        }
        throw AssertionError("Nonempty source required")
    }

    private fun drain(pipeline: AudioProcessingPipeline, output: ByteArrayOutputStream) {
        var pulls = 0
        while (!pipeline.isEnded()) {
            assertTrue("EOS output must progress", ++pulls <= 16)
            val pending = pipeline.getOutput()
            assertTrue("The sole DSP pipeline has pending audio", pending.hasRemaining())
            append(pending, output)
        }
        assertEquals(0, pipeline.getOutput().remaining())
    }

    private fun append(buffer: ByteBuffer, output: ByteArrayOutputStream, count: Int = buffer.remaining()) {
        val bytes = ByteArray(count)
        buffer.get(bytes)
        output.write(bytes)
    }

    private fun copyRemaining(buffer: ByteBuffer): ByteArray =
        ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }

    private fun buffer(bytes: ByteArray): ByteBuffer = ByteBuffer.allocateDirect(bytes.size)
        .order(ByteOrder.nativeOrder()).put(bytes).also { it.flip() }

    private fun pcm(frames: Int, channels: Int, sample: (Int, Int) -> Int): ByteArray =
        ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.nativeOrder()).apply {
            for (index in 0 until frames) for (channel in 0 until channels) putShort(sample(index, channel).toShort())
        }.array()

    private class IdentityProcessor : BaseAudioProcessor() {
        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat = inputAudioFormat

        override fun queueInput(inputBuffer: ByteBuffer) {
            if (!inputBuffer.hasRemaining()) return
            val output = replaceOutputBuffer(inputBuffer.remaining())
            output.put(inputBuffer)
            output.flip()
        }
    }
}
