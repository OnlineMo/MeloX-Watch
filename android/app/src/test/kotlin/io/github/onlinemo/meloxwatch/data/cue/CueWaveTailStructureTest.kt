package io.github.onlinemo.meloxwatch.data.cue

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CueWaveTailStructureTest {
    @Test fun aSecondDataOrFormatChunkAfterThePayloadIsRejectedInsteadOfBeingHiddenByTheFirstDataHeader() {
        val fixture = CueByteFixture()
        for (chunk in listOf("data", "fmt ")) {
            val extra = if (chunk == "data") ByteArray(12) else ByteArray(24)
            extra[0] = chunk[0].code.toByte();
            extra[1] = chunk[1].code.toByte()
            extra[2] = chunk[2].code.toByte();
            extra[3] = chunk[3].code.toByte()
            ByteBuffer.wrap(extra).order(ByteOrder.LITTLE_ENDIAN).putInt(4, extra.size - 8)
            val actual = fixture.wave + extra
            ByteBuffer.wrap(actual).order(ByteOrder.LITTLE_ENDIAN).putInt(4, actual.size - 8)
            assertEquals(CueIssue.INVALID_AUDIO, assertThrows(CueException::class.java) {
                CuePcmWaveParser.inspect(ByteArrayInputStream(actual), actual.size.toLong())
            }.issue)
        }
    }
    @Test fun validPaddedTailChunksKeepTheProvenDataExtentAndTruncatedPaddingIsRejected() {
        val fixture = CueByteFixture()
        val tail = byteArrayOf('J'.code.toByte(), 'U'.code.toByte(), 'N'.code.toByte(), 'K'.code.toByte(), 1, 0, 0, 0, 7, 0)
        val actual = fixture.wave + tail
        ByteBuffer.wrap(actual).order(ByteOrder.LITTLE_ENDIAN).putInt(4, actual.size - 8)
        val facts = CuePcmWaveParser.inspect(ByteArrayInputStream(actual), actual.size.toLong())
        assertEquals(fixture.facts.copy(fileBytes = actual.size.toLong()), facts)
        val truncated = actual.copyOf(actual.size - 1)
        ByteBuffer.wrap(truncated).order(ByteOrder.LITTLE_ENDIAN).putInt(4, truncated.size - 8)
        assertEquals(CueIssue.INVALID_AUDIO, assertThrows(CueException::class.java) {
            CuePcmWaveParser.inspect(ByteArrayInputStream(truncated), truncated.size.toLong())
        }.issue)
    }
}
