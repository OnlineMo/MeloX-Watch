package io.github.onlinemo.meloxwatch.data.codec

import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class ApePcmDecoderTest {
    @Test fun realEntropyAndPredictorDecodeNonzeroMonoAndStereoForFastAndNormal() {
        for (compression in listOf(1000, 2000)) {
            for (value in listOf<Short>(-1, 1)) {
                val actual = decode(CodecFixtures.apeOne(value, compression = compression))
                assertArrayEquals(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array(), actual)
            }
            val stereo = decode(CodecFixtures.apeOne(1, -1, compression))
            assertArrayEquals(byteArrayOf(1, 0, -1, -1), stereo)
        }
    }

    @Test fun frameSeekHasExactCountAndReachesTheDeclaredEof() {
        val raw = CodecFixtures.apeSilence()
        LocalPcmCodecs.open(MemoryCodecInput(raw))!!.use { decoder ->
            assertEquals(1281L, decoder.format.pcmFrames)
            for (position in listOf(0L, 1023L, 1024L, 1280L, 1281L)) {
                decoder.seekFrame(position)
                val bytes = ByteArray(512 * 4) { 85 }
                val count = decoder.readFrames(bytes, 0, 512)
                assertEquals(minOf(512L, 1281L - position).toInt(), count)
                assertTrue(bytes.take(count * 4).all { it.toInt() == 0 })
                assertEquals(position + count, decoder.nextFrame)
            }
        }
    }

    @Test fun largerDeclaredNormalFrameUsesTheSameBoundedDecoder() {
        val raw = CodecFixtures.apeOne()
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putInt(56, 294912)
        assertArrayEquals(byteArrayOf(1, 0), decode(raw))
    }

    @Test fun badCrcNeverPublishesAnUnverifiedFrame() {
        val raw = CodecFixtures.apeOne()
        raw[80] = (raw[80].toInt() xor 1).toByte()
        LocalPcmCodecs.open(MemoryCodecInput(raw))!!.use { decoder ->
            val output = ByteArray(2) { 85 }
            try { decoder.readFrames(output, 0, 1); fail("CRC must fail") }
            catch (error: CodecReadException) { assertEquals(CodecReadIssue.INVALID_CONTAINER, error.issue) }
            assertArrayEquals(byteArrayOf(85, 85), output)
        }
    }

    @Test fun highCompressionAndUnknownVersionsFailBeforeDecode() {
        val high = CodecFixtures.apeOne()
        ByteBuffer.wrap(high).order(ByteOrder.LITTLE_ENDIAN).putShort(52, 5000)
        rejects(high, CodecReadIssue.UNSUPPORTED_ENCODING)
        val future = CodecFixtures.apeOne()
        ByteBuffer.wrap(future).order(ByteOrder.LITTLE_ENDIAN).putShort(4, 4000)
        rejects(future, CodecReadIssue.UNSUPPORTED_VERSION)
    }

    @Test fun malformedFrameOrSeekDimensionsCannotAllocateLargeBuffers() {
        val huge = CodecFixtures.apeOne()
        ByteBuffer.wrap(huge).order(ByteOrder.LITTLE_ENDIAN).putInt(56, Int.MAX_VALUE)
        rejects(huge, CodecReadIssue.RESOURCE_LIMIT)
        val badSeek = CodecFixtures.apeOne()
        ByteBuffer.wrap(badSeek).order(ByteOrder.LITTLE_ENDIAN).putInt(76, 0)
        rejects(badSeek, CodecReadIssue.INVALID_CONTAINER)
        rejects(CodecFixtures.apeOne().dropLast(1).toByteArray(), CodecReadIssue.INVALID_CONTAINER)
    }

    @Test fun localApeAfterBoundedId3DoesNotBecomeAnMp3ByItsExtension() {
        val tag = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0, 0, 0, 0, 0)
        assertArrayEquals(byteArrayOf(1, 0), decode(tag + CodecFixtures.apeOne()))
    }

    @Test fun cancelledReadsStayCancelledAcrossAFrameDecode() {
        val input = MemoryCodecInput(CodecFixtures.apeSilence())
        LocalPcmCodecs.open(input)!!.use { decoder ->
            input.cancelled = true
            try { decoder.readFrames(ByteArray(4), 0, 1); fail("Must cancel") }
            catch (_: InterruptedIOException) {}
        }
    }

    private fun decode(raw: ByteArray): ByteArray = LocalPcmCodecs.open(MemoryCodecInput(raw))!!.use { decoder ->
        val bytes = ByteArray(decoder.format.pcmFrames.toInt() * decoder.format.pcm.frameBytes)
        var frame = 0
        while (frame < decoder.format.pcmFrames) frame += decoder.readFrames(bytes, frame * decoder.format.pcm.frameBytes,
            minOf(512, decoder.format.pcmFrames.toInt() - frame))
        bytes
    }
    private fun rejects(raw: ByteArray, issue: CodecReadIssue) {
        try { LocalPcmCodecs.open(MemoryCodecInput(raw))?.close(); fail("Must reject") }
        catch (error: CodecReadException) { assertEquals(issue, error.issue) }
    }
}
