package io.github.onlinemo.meloxwatch.data.codec

import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class DsdPcmDecoderTest {
    @Test fun dsfLsbMsbAndDffProduceTheSameRealNonzeroPcm() {
        val channels = CodecFixtures.dsdTone(1000)
        val inputs = listOf(CodecFixtures.dsf(channels), CodecFixtures.dsf(channels, msb = true), CodecFixtures.dff(channels))
        val pcm = inputs.map(::decode)
        assertTrue(pcm[0].any { it.toInt() != 0 })
        assertArrayEquals(pcm[0], pcm[1]); assertArrayEquals(pcm[0], pcm[2])
    }

    @Test fun centeredPrerollSeekMatchesSequentialPcmAndKeepsTheTail() {
        val raw = CodecFixtures.dsf(CodecFixtures.dsdTone(1000))
        val expected = decode(raw)
        LocalPcmCodecs.open(MemoryCodecInput(raw))!!.use { decoder ->
            for (frame in listOf(0L, 1L, 63L, 512L, 1023L, 1024L)) {
                decoder.seekFrame(frame)
                val bytes = ByteArray(512 * 4)
                val count = decoder.readFrames(bytes, 0, 512)
                assertArrayEquals(expected.copyOfRange((frame * 4).toInt(), (frame * 4 + count * 4).toInt()), bytes.copyOf(count * 4))
            }
        }
    }

    @Test fun independentChannelSignalsAreNotSwappedOrMerged() {
        val pcm = ByteBuffer.wrap(decode(CodecFixtures.dsf(CodecFixtures.dsdTone(1000)))).order(ByteOrder.LITTLE_ENDIAN)
        while (pcm.remaining() >= 4) {
            val left = pcm.short.toInt(); val right = pcm.short.toInt()
            assertTrue(left + right in -1..1)
        }
    }

    @Test fun filterPassBandAndAliasRejectionUseARealLowPass() {
        fun magnitude(frequency: Double): Double {
            var real = 0.0; var imaginary = 0.0
            DsdFilterBank.coefficients.forEachIndexed { index, tap ->
                val phase = 2 * PI * frequency * index / 2_822_400
                real += tap * cos(phase); imaginary -= tap * sin(phase)
            }
            return sqrt(real * real + imaginary * imaginary)
        }
        assertTrue(magnitude(1000.0) in 0.999..1.001)
        assertTrue(magnitude(20_000.0) > 0.99)
        assertTrue(magnitude(90_000.0) < 0.0001)
        assertTrue(magnitude(1_000_000.0) < 0.0001)
    }

    @Test fun actualHighFrequencyDsdDoesNotFoldIntoAudiblePcm() {
        fun rms(bytes: ByteArray): Double {
            val values = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var sum = 0.0; var count = 0
            repeat(bytes.size / 4) { frame ->
                val left = values.short / 32768.0; values.short
                if (frame in 64 until bytes.size / 4 - 64) { sum += left * left; count++ }
            }
            return sqrt(sum / count)
        }
        assertTrue(rms(decode(CodecFixtures.dsf(CodecFixtures.dsdTone(1000)))) in 0.23..0.26)
        assertTrue(rms(decode(CodecFixtures.dsf(CodecFixtures.dsdTone(90_000)))) < 0.003)
    }

    @Test fun dstAndHighRateAreRejectedBeforePcm() {
        rejects(CodecFixtures.dff(CodecFixtures.dsdTone(1000), "DST "), CodecReadIssue.UNSUPPORTED_ENCODING)
        val raw = CodecFixtures.dsf(CodecFixtures.dsdTone(1000))
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putInt(56, 5_644_800)
        rejects(raw, CodecReadIssue.UNSUPPORTED_RATE)
    }

    @Test fun malformedBlockAndTruncatedContainerCannotQualify() {
        val raw = CodecFixtures.dsf(CodecFixtures.dsdTone(1000))
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putInt(72, 1)
        rejects(raw, CodecReadIssue.INVALID_CONTAINER)
        rejects(CodecFixtures.dsf(CodecFixtures.dsdTone(1000)).dropLast(1).toByteArray(), CodecReadIssue.INVALID_CONTAINER)
    }

    @Test fun partialDsfByteUsesDeclaredSamplesAndNotBlockPadding() {
        val raw = CodecFixtures.dsf(CodecFixtures.dsdTone(1000, 1027), samples = 1027)
        LocalPcmCodecs.open(MemoryCodecInput(raw))!!.use { decoder ->
            assertEquals(33L, decoder.format.pcmFrames)
            assertEquals(33, decoder.readFrames(ByteArray(512 * 4), 0, 512))
            assertEquals(0, decoder.readFrames(ByteArray(4), 0, 1))
        }
    }

    @Test fun cancellationPropagatesInsteadOfReturningEofOrPcm() {
        val input = MemoryCodecInput(CodecFixtures.dsf(CodecFixtures.dsdTone(1000)))
        LocalPcmCodecs.open(input)!!.use { decoder ->
            input.cancelled = true
            try { decoder.readFrames(ByteArray(4), 0, 1); fail("Cancellation must propagate") }
            catch (_: InterruptedIOException) {}
        }
    }

    private fun decode(raw: ByteArray): ByteArray = LocalPcmCodecs.open(MemoryCodecInput(raw))!!.use { decoder ->
        val result = ByteArray(decoder.format.pcmFrames.toInt() * decoder.format.pcm.frameBytes)
        var frame = 0
        while (frame < decoder.format.pcmFrames) {
            frame += decoder.readFrames(result, frame * decoder.format.pcm.frameBytes, minOf(512, decoder.format.pcmFrames.toInt() - frame))
        }
        result
    }
    private fun rejects(raw: ByteArray, issue: CodecReadIssue) {
        try { LocalPcmCodecs.open(MemoryCodecInput(raw))?.close(); fail("Must reject") }
        catch (error: CodecReadException) { assertEquals(issue, error.issue) }
    }
}
