package io.github.onlinemo.meloxwatch.data.cue

import io.github.onlinemo.meloxwatch.model.CueTiming
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class CuePcmWaveParserTest {
    @Test fun actualStereoSamplesAtTheBoundaryHaveNoLeakGapOrDuplication() {
        val fixture = CueByteFixture()
        val facts = CuePcmWaveParser.inspect(ByteArrayInputStream(fixture.wave), fixture.wave.size.toLong())
        assertEquals(fixture.facts, facts)
        val first = facts.window(fixture.reference(1).identity)
        val second = facts.window(fixture.reference(2).identity)
        assertEquals(44_688L, first.endSampleExclusive)
        assertEquals(44_688L, second.firstSample)
        assertEquals(88_200L, second.endSampleExclusive)
        val a = fixture.wave.copyOfRange(first.physicalByteOffset.toInt(), (first.physicalByteOffset + first.payloadBytes).toInt())
        val b = fixture.wave.copyOfRange(second.physicalByteOffset.toInt(), (second.physicalByteOffset + second.payloadBytes).toInt())
        assertArrayEquals(fixture.wave.copyOfRange(44, fixture.wave.size), a + b)
        val leftA = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN).getShort(a.size - 4)
        val leftB = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getShort(0)
        assertEquals(CueByteFixture.sampleValue(44_687), leftA)
        assertEquals(CueByteFixture.sampleValue(44_688), leftB)
    }
    @Test fun aRateNotDivisibleBy75RoundsSamplesUpWhileKeepingOriginalFramesAndMicroseconds() {
        val fixture = CueByteFixture(32_003)
        val reference = fixture.reference(1, start = 1L, end = 76L)
        val window = fixture.facts.window(reference.identity)
        assertEquals(13_333L, reference.identity.startUs)
        assertEquals(427L, window.firstSample)
        assertEquals(32_430L, window.endSampleExclusive)
        assertEquals(128_012L, window.payloadBytes)
        assertEquals(1_000_000L, window.durationUs)
        assertThrows(IllegalArgumentException::class.java) { CueTiming.microseconds(CueTiming.MAX_FRAMES + 1L) }
    }
    @Test fun truncatedExtentCompressedFormatAndUnboundedHeaderAreRejected() {
        val fixture = CueByteFixture()
        assertEquals(CueIssue.INVALID_AUDIO, assertThrows(CueException::class.java) {
            CuePcmWaveParser.inspect(ByteArrayInputStream(fixture.wave), fixture.wave.size.toLong() - 4L)
        }.issue)
        val compressed = fixture.wave.copyOf().apply { this[20] = 3 }
        assertEquals(CueIssue.INVALID_AUDIO, assertThrows(CueException::class.java) {
            CuePcmWaveParser.inspect(ByteArrayInputStream(compressed), compressed.size.toLong())
        }.issue)
        val prefix = ByteArray(20).also { bytes ->
            val out = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            out.put("RIFF".toByteArray());
            out.putInt(2_000_020 - 8);
            out.put("WAVEJUNK".toByteArray());
            out.putInt(2_000_000)
        }
        assertEquals(CueIssue.LIMIT_EXCEEDED, assertThrows(CueException::class.java) {
            CuePcmWaveParser.inspect(ByteArrayInputStream(prefix), 2_000_020)
        }.issue)
    }
    @Test fun callerCancellationIsPreservedBeforeAnotherHeaderRead() {
        val fixture = CueByteFixture()
        val stop = CancellationException("Controlled header cancellation")
        assertSame(stop, assertThrows(CancellationException::class.java) {
            CuePcmWaveParser.inspect(ByteArrayInputStream(fixture.wave), fixture.wave.size.toLong()) { throw stop }
        })
    }
}
