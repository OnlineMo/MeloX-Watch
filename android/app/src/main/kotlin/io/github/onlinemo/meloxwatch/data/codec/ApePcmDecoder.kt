package io.github.onlinemo.meloxwatch.data.codec

import io.github.onlinemo.meloxwatch.data.codec.jmac.decoder.JmacDecoder
import io.github.onlinemo.meloxwatch.data.codec.jmac.tools.File
import java.io.IOException

/** Pure Java entropy/predictor/CRC decode; no native library, network reader or automatic transcoding file. */
internal class ApePcmDecoder(private val input: CodecInput, layout: ApeLayout) : PcmFrameDecoder {
    override val format = layout.format
    override var nextFrame = 0L
        private set
    private var closed = false
    private val file = object : File() {
        override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
            input.checkCancellation()
            val remaining = layout.audioEnd - input.position
            return if (remaining <= 0) -1 else input.read(bytes, offset, minOf(count.toLong(), remaining).toInt())
        }
        override fun seek(position: Long) {
            codecRequire(position in 0..layout.audioEnd)
            input.moveTo(position)
        }
        override fun checkCancellation() = input.checkCancellation()
    }
    private val decoder = JmacDecoder(file, layout.version, layout.compression, layout.channels,
        layout.blocksPerFrame, layout.lastFrameBlocks, layout.sourceFrames, layout.seekBytes)

    override fun seekFrame(frame: Long) {
        input.checkCancellation()
        codecRequire(!closed && frame in 0..format.pcmFrames)
        translate { decoder.seek(frame.toInt()) }
        nextFrame = frame
    }

    override fun readFrames(bytes: ByteArray, offset: Int, frames: Int): Int {
        codecRequire(!closed && frames in 0..512 && offset >= 0 && offset <= bytes.size - frames * format.pcm.frameBytes)
        val count = minOf(frames.toLong(), format.pcmFrames - nextFrame).toInt()
        if (count == 0) return 0
        val read = translate { decoder.readFrames(bytes, offset, count) }
        codecRequire(read in 1..count)
        nextFrame += read
        input.checkCancellation()
        return read
    }

    override fun close() { closed = true; decoder.close() }

    private fun <T> translate(action: () -> T): T = try { action() }
    catch (_: JmacDecoder.BudgetExceededException) { throw CodecReadException(CodecReadIssue.RESOURCE_LIMIT) }
    catch (error: IOException) { throw error }
    catch (_: ArithmeticException) { throw CodecReadException(CodecReadIssue.INVALID_CONTAINER) }
    catch (_: IndexOutOfBoundsException) { throw CodecReadException(CodecReadIssue.INVALID_CONTAINER) }
    catch (_: io.github.onlinemo.meloxwatch.data.codec.jmac.tools.JMACException) {
        throw CodecReadException(CodecReadIssue.INVALID_CONTAINER)
    }
}
