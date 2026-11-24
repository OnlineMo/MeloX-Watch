package io.github.onlinemo.meloxwatch.data.codec

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class PcmWaveLargeFileTest {
    @Test fun rf64UsesLongDataSizeAndARealFiniteDsdSeekBeyondFourGiB() {
        val frames = (1L shl 30) + 1024
        val header = PcmWaveHeader(PcmFormat(88_200, 2), frames)
        assertEquals("RF64", header.bytes.fourCc())
        assertEquals(80, header.bytes.size)
        assertEquals(frames * 4, ByteBuffer.wrap(header.bytes).order(ByteOrder.LITTLE_ENDIAN).getLong(28))
        val input = SparseDsfInput(frames)
        val decoder = LocalPcmCodecs.open(input)!!
        PcmWaveStream(decoder).use { stream ->
            assertEquals(header.streamBytes, stream.length)
            stream.seek(stream.length - 4)
            assertEquals(4, stream.read(ByteArray(4), 0, 4))
            assertEquals(-1, stream.read(ByteArray(4), 0, 4))
        }
        assertTrue(input.maximumPosition > 0xffff_ffffL)
        assertTrue(input.maximumRead <= 16 * 1024)
        assertTrue(input.reads < 128)
    }

    @Test fun dsfZeroPaddingAndDffWordPaddingAreValidated() {
        val dsf = CodecFixtures.dsf(CodecFixtures.dsdTone(1000, 1024))
        dsf[dsf.lastIndex] = 1
        try { LocalPcmCodecs.open(MemoryCodecInput(dsf)); fail("Unused block bytes must be zero") }
        catch (error: CodecReadException) { assertEquals(CodecReadIssue.INVALID_CONTAINER, error.issue) }
        val dff = CodecFixtures.dff(CodecFixtures.dsdTone(1000))
        // CMPR has 5 bytes and one zero pad, immediately before the DSD sound-data chunk.
        val cmpr = dff.indices.first { index -> index + 4 <= dff.size && dff.copyOfRange(index, index + 4).fourCc() == "CMPR" }
        dff[cmpr + 17] = 1
        try { LocalPcmCodecs.open(MemoryCodecInput(dff)); fail("Word pad must be zero") }
        catch (error: CodecReadException) { assertEquals(CodecReadIssue.INVALID_CONTAINER, error.issue) }
    }

    /** Synthetic known-length sparse source; it does not allocate or pretend to test a real huge file. */
    private class SparseDsfInput(frames: Long) : CodecInput {
        private val samples = frames * 32
        private val channelBytes = samples / 8
        private val groups = (channelBytes + 4095) / 4096
        private val header = CodecFixtures.dsfHeader(samples, 2, groups * 8192)
        override val length = header.size + groups * 8192
        override var position = 0L
            private set
        var reads = 0
        var maximumRead = 0
        var maximumPosition = 0L
        override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
            reads++; maximumRead = maxOf(maximumRead, count)
            if (position == length) return -1
            val read = minOf(count.toLong(), length - position).toInt()
            repeat(read) { index ->
                val at = position + index
                bytes[offset + index] = if (at < header.size) header[at.toInt()] else {
                    val body = at - header.size
                    val within = (body % 8192).toInt()
                    val channelIndex = body / 8192 * 4096 + within % 4096
                    (if (channelIndex >= channelBytes) 0 else if (within < 4096) 0x69 else 0x96).toByte()
                }
            }
            position += read; maximumPosition = maxOf(maximumPosition, position)
            return read
        }
        override fun seek(position: Long) { require(position in 0..length); this.position = position; maximumPosition = maxOf(maximumPosition, position) }
        override fun checkCancellation() = Unit
        override fun close() = Unit
    }
}
