package io.github.onlinemo.meloxwatch.data.cue

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CueModelsTest {
    @Test fun seventyFiveFpsConversionsKeepFramesAndFloorEndpointsWithoutDrift() {
        val expected = mapOf(0L to 0L, 1L to 13_333L, 74L to 986_666L, 75L to 1_000_000L,
            76L to 1_013_333L, 4501L to 60_013_333L)
        for ((frames, microseconds) in expected) {
            val time = CueFrameTime(frames)
            assertEquals(frames, time.frames)
            assertEquals(microseconds, time.toMicroseconds())
            assertEquals(microseconds / 1000L, time.toMilliseconds())
        }
        assertEquals(1_000_000L, CueFrameTime(76).toMicroseconds() - CueFrameTime(1).toMicroseconds())
    }

    @Test fun largestAdmittedFramesAgreeWithAnIndependentBigIntegerOracle() {
        for (frames in listOf(CueLimits.MAX_FRAME_COUNT, CueLimits.MAX_FRAME_COUNT - 74L, CueLimits.MAX_FRAME_COUNT - 75L)) {
            val expected = BigInteger.valueOf(frames).multiply(BigInteger.valueOf(1_000_000L))
                .divide(BigInteger.valueOf(75L)).longValueExact()
            assertEquals(expected, CueFrameTime(frames).toMicroseconds())
            assertTrue(expected >= 0L)
        }
        for (frames in listOf(-1L, CueLimits.MAX_FRAME_COUNT + 1L, Long.MAX_VALUE)) {
            try { CueFrameTime(frames); throw AssertionError("Expected out-of-range frames") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun durableIdentityIncludesSourceCueAndTrackWithoutConcatenationCollisions() {
        val first = CueTrackIdentity(CueDocumentIdentity("a", "bc"), 1)
        assertEquals(first.trackKey, CueTrackIdentity(CueDocumentIdentity("a", "bc"), 1).trackKey)
        assertNotEquals(first.trackKey, CueTrackIdentity(CueDocumentIdentity("ab", "c"), 1).trackKey)
        assertNotEquals(first.trackKey, CueTrackIdentity(CueDocumentIdentity("a", "other"), 1).trackKey)
        assertNotEquals(first.trackKey, CueTrackIdentity(CueDocumentIdentity("a", "bc"), 2).trackKey)
        assertEquals("local", first.trackKey.providerId)
    }

    @Test fun invalidIdentityCannotCollapseThroughUtf8ReplacementEncoding() {
        for (identity in listOf("", "x\u0000", "\ud800", "x".repeat(129))) {
            try { CueDocumentIdentity("source", identity); throw AssertionError("Expected invalid cue identity") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
