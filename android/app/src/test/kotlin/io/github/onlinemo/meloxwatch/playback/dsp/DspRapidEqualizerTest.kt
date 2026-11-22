package io.github.onlinemo.meloxwatch.playback.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 非零连续 PCM 验证实际处理器输出；不以配置回读或测试定义证明设备声音通过。 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class DspRapidEqualizerTest {
    @Test(timeout = 15_000L)
    fun aSecondBandRequestKeepsTheFirstCrossfadeIntactThenReachesTheLatestRequestedCurve() {
        Processor().use { actual -> Processor().use { reference ->
            assertArrayEquals(reference.process(-512 until 0), actual.process(-512 until 0))
            reference.settings(curve(120))
            actual.settings(curve(120))
            val firstReference = reference.process(0 until 24)
            val firstActual = actual.process(0 until 24)
            actual.settings(curve(-120))
            val restReference = reference.process(24 until RAMP)
            val restActual = actual.process(24 until RAMP)
            assertArrayEquals(firstReference + restReference, firstActual + restActual)
            val settled = actual.process(RAMP until RAMP + 1_600)
            val unchanged = reference.process(RAMP until RAMP + 1_600)
            val ratio = rms(settled.takeLast(1_600).toByteArray()) / rms(unchanged.takeLast(1_600).toByteArray())
            assertTrue("Latest cut curve did not settle: $ratio", ratio in 0.04..0.09)
            assertEquals(curve(-120), actual.runtime.state.value.requested)
        } }
    }

    @Test(timeout = 15_000L)
    fun cancellingRapidPreviewKeepsTheInFlightTransitionThenReturnsToExactUnprocessedBytes() {
        Processor().use { actual -> Processor().use { reference ->
            actual.process(-512 until 0)
            reference.process(-512 until 0)
            actual.settings(curve(120))
            reference.settings(curve(120))
            val first = actual.process(0 until 24)
            actual.settings(curve(-120))
            val middle = actual.process(24 until 48)
            actual.settings(DspSettings())
            val last = actual.process(48 until RAMP)
            assertArrayEquals(reference.process(0 until RAMP), first + middle + last)
            actual.process(RAMP until RAMP * 2)
            val unchanged = actual.process(RAMP * 2 until 1_024)
            assertArrayEquals(signal(RAMP * 2 until 1_024), unchanged)
            assertTrue(actual.runtime.state.value.effectiveBandIndices.isEmpty())
            assertFalse(actual.runtime.state.value.requested.equalizerEnabled)
        } }
    }

    @Test(timeout = 15_000L)
    fun aCompletedSameFormatStreamBoundaryDoesNotResetAnIncompleteEqualizerTransition() {
        Processor().use { actual -> Processor().use { reference ->
            actual.process(-512 until 0)
            reference.process(-512 until 0)
            actual.settings(curve(120))
            reference.settings(curve(120))
            assertArrayEquals(reference.process(0 until 24), actual.process(0 until 24))
            actual.settings(curve(-120))
            actual.processor.queueEndOfStream()
            assertFalse(actual.processor.getOutput().hasRemaining())
            assertTrue(actual.processor.isEnded)
            actual.processor.flush()
            assertArrayEquals(reference.process(24 until RAMP), actual.process(24 until RAMP))
            val settled = actual.process(RAMP until RAMP + 1_600)
            val unchanged = reference.process(RAMP until RAMP + 1_600)
            assertTrue(rms(settled.takeLast(1_600).toByteArray()) < rms(unchanged.takeLast(1_600).toByteArray()) * 0.1)
        } }
    }

    @Test(timeout = 15_000L)
    fun aBorrowedOutputBufferCannotBeOverwrittenByAnEarlyQueueInput() {
        Processor().use { actual ->
            val original = signal(0 until 16)
            val first = input(original)
            actual.processor.queueInput(first)
            val borrowed = actual.processor.getOutput()
            val expected = ByteArray(borrowed.remaining()).also { borrowed.duplicate().get(it) }
            val next = input(signal(16 until 32))
            try {
                actual.processor.queueInput(next)
                throw AssertionError("Unconsumed output was reused")
            } catch (_: IllegalStateException) { }
            assertEquals(0, next.position())
            assertArrayEquals(expected, ByteArray(borrowed.remaining()).also(borrowed::get))
            actual.processor.queueInput(next)
            val output = actual.processor.getOutput()
            assertArrayEquals(signal(16 until 32), ByteArray(output.remaining()).also(output::get))
            assertFalse(next.hasRemaining())
        }
    }

    private class Processor : AutoCloseable {
        val runtime = DspRuntime()
        val processor = DspAudioProcessor(runtime)
        private var revision = 0L

        init {
            processor.configure(AudioProcessor.AudioFormat(RATE, 1, C.ENCODING_PCM_16BIT))
            processor.flush()
        }

        fun settings(settings: DspSettings) { runtime.setSaved(DspStored(revision = ++revision, settings = settings)) }

        fun process(range: IntRange): ByteArray {
            val input = input(signal(range))
            processor.queueInput(input)
            assertFalse(input.hasRemaining())
            val output = processor.getOutput()
            return ByteArray(output.remaining()).also(output::get)
        }

        override fun close() {
            processor.reset()
            runtime.releaseSink(processor.ticket)
        }
    }

    private fun rms(bytes: ByteArray): Double {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        var total = 0.0
        val count = buffer.remaining() / 2
        while (buffer.hasRemaining()) {
            val sample = buffer.short.toDouble()
            total += sample * sample
        }
        return sqrt(total / count)
    }

    companion object {
        private const val RATE = 8_000
        private const val RAMP = RATE * DspLimits.CONFIG_RAMP_MILLIS / 1_000
        private fun curve(gain: Int): DspSettings = DspSettings(equalizerEnabled = true,
            bandGainTenthsDb = DspLimits.FREQUENCIES_HZ.map { if (it == 1_000) gain else 0 })
        private fun input(bytes: ByteArray): ByteBuffer = ByteBuffer.allocateDirect(bytes.size)
            .order(ByteOrder.nativeOrder()).apply { put(bytes); flip() }
        private fun signal(range: IntRange): ByteArray {
            val output = ByteBuffer.allocate(range.count() * 2).order(ByteOrder.nativeOrder())
            range.forEach { frame -> output.putShort((sin(2.0 * Math.PI * 1_000 * frame / RATE) * 1_500).roundToInt().toShort()) }
            return output.array()
        }
    }
}
