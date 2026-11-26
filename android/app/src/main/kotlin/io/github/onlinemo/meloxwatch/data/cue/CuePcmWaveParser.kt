package io.github.onlinemo.meloxwatch.data.cue

import io.github.onlinemo.meloxwatch.model.CuePcmWaveFacts
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 此流只借用资源；同一provider FD由调用方在正常或物理取消路径关闭。 */
object CuePcmWaveParser {
    const val HEADER_BYTES = 1024 * 1024L
    const val CHUNKS = 256
    const val READ_BYTES = 16 * 1024

    fun inspect(input: InputStream, fileBytes: Long, checkCancelled: () -> Unit = {}): CuePcmWaveFacts =
        inspectInternal(input, fileBytes, checkCancelled, null)

    /** 只有已核验物理字节范围的调用方可通过定位跳过PCM载荷。 */
    fun inspectVerified(input: InputStream, fileBytes: Long, seekVerifiedExtent: (Long) -> Unit,
        checkCancelled: () -> Unit = {}): CuePcmWaveFacts = inspectInternal(input, fileBytes, checkCancelled, seekVerifiedExtent)

    private fun inspectInternal(input: InputStream, fileBytes: Long, checkCancelled: () -> Unit,
        seekVerifiedExtent: ((Long) -> Unit)?): CuePcmWaveFacts {
        try {
            requireAudio(fileBytes in 44L..0x1_0000_0007L)
            val reader = HeaderReader(input, checkCancelled, seekVerifiedExtent)
            val riff = reader.bytes(12)
            requireAudio(riff.id(0) == "RIFF" && riff.id(8) == "WAVE")
            val riffBytes = riff.u32(4) + 8L
            requireAudio(riffBytes == fileBytes)
            var sampleRate = 0
            var channels = 0
            var blockBytes = 0
            var formatSeen = false
            var facts: CuePcmWaveFacts? = null
            var chunks = 0
            while (reader.position < riffBytes) {
                checkCancelled()
                if (++chunks > CHUNKS) throw CueException(CueIssue.LIMIT_EXCEEDED)
                requireAudio(riffBytes - reader.position >= 8L)
                val chunk = reader.bytes(8)
                val size = chunk.u32(4)
                val offset = reader.position
                requireAudio(size <= riffBytes - offset)
                when (chunk.id(0)) {
                    "fmt " -> {
                        requireAudio(!formatSeen)
                        if (size !in setOf(16L, 18L)) throw CueException(CueIssue.INVALID_AUDIO)
                        val format = reader.bytes(size.toInt())
                        requireAudio(format.u16(0) == 1 && format.u16(14) == 16)
                        channels = format.u16(2)
                        val rate = format.u32(4)
                        requireAudio(channels in 1..2 && rate in 1L..192_000L)
                        sampleRate = rate.toInt()
                        blockBytes = format.u16(12)
                        requireAudio(blockBytes == channels * 2 && format.u32(8) == sampleRate.toLong() * blockBytes)
                        requireAudio(size != 18L || format.u16(16) == 0)
                        formatSeen = true
                    }
                    "data" -> {
                        requireAudio(formatSeen && facts == null && size > 0L && size % blockBytes == 0L)
                        facts = CuePcmWaveFacts(fileBytes, offset, size, sampleRate, channels)
                        reader.skipPayload(size)
                        checkCancelled()
                    }
                    else -> reader.skip(size)
                }
                if (size and 1L != 0L) {
                    requireAudio(reader.position < riffBytes)
                    reader.bytes(1)
                }
            }
            checkCancelled()
            return facts ?: throw CueException(CueIssue.INVALID_AUDIO)
        } catch (failure: EOFException) { throw CueException(CueIssue.INVALID_AUDIO, cause = failure) }
        catch (failure: ArithmeticException) { throw CueException(CueIssue.INVALID_AUDIO, cause = failure) }
        catch (failure: IllegalArgumentException) { throw CueException(CueIssue.INVALID_AUDIO, cause = failure) }
    }

    /** 此传输头仅描述已校验的分轨样本，不能作为输出文件。 */
    fun transportHeader(facts: CuePcmWaveFacts, payloadBytes: Long): ByteArray {
        requireAudio(payloadBytes > 0L && payloadBytes <= facts.dataBytes && payloadBytes % facts.frameBytes == 0L)
        requireAudio(payloadBytes <= 0xffff_ffffL - 36L)
        return ByteArray(44).also { bytes ->
            val output = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            fun id(value: String) { output.put(value.toByteArray(Charsets.US_ASCII)) }
            id("RIFF");
            output.putInt((payloadBytes + 36L).toInt());
            id("WAVE")
            id("fmt ");
            output.putInt(16);
            output.putShort(1);
            output.putShort(facts.channels.toShort())
            output.putInt(facts.sampleRateHz);
            output.putInt(Math.multiplyExact(facts.sampleRateHz, facts.frameBytes))
            output.putShort(facts.frameBytes.toShort());
            output.putShort(16)
            id("data");
            output.putInt(payloadBytes.toInt())
        }
    }

    private class HeaderReader(private val input: InputStream, private val checkCancelled: () -> Unit,
        private val seekVerifiedExtent: ((Long) -> Unit)?) {
        var position = 0L
            private set
        private val scratch = ByteArray(READ_BYTES)
        private var headerWorkBytes = 0L
        fun bytes(count: Int): ByteArray {
            admit(count.toLong())
            val result = ByteArray(count)
            read(result, count)
            return result
        }
        fun skip(count: Long) {
            admit(count)
            var remaining = count
            while (remaining > 0L) {
                val next = minOf(remaining, scratch.size.toLong()).toInt()
                read(scratch, next)
                remaining -= next
            }
        }
        fun skipPayload(count: Long) {
            checkCancelled()
            if (count < 0L) throw CueException(CueIssue.INVALID_AUDIO)
            val seek = seekVerifiedExtent
            if (seek != null) {
                val next = Math.addExact(position, count)
                seek(next)
                position = next
            } else {
                var remaining = count
                while (remaining > 0L) {
                    val next = minOf(remaining, scratch.size.toLong()).toInt()
                    read(scratch, next)
                    remaining -= next
                }
            }
            checkCancelled()
        }
        private fun admit(count: Long) {
            checkCancelled()
            if (count < 0L || count > HEADER_BYTES - headerWorkBytes) throw CueException(CueIssue.LIMIT_EXCEEDED)
            headerWorkBytes += count
        }
        private fun read(buffer: ByteArray, count: Int) {
            var offset = 0
            while (offset < count) {
                checkCancelled()
                val read = input.read(buffer, offset, minOf(count - offset, READ_BYTES))
                if (read <= 0) throw EOFException()
                offset += read
                position += read
            }
            checkCancelled()
        }
    }

    private fun ByteArray.id(offset: Int) = String(this, offset, 4, Charsets.US_ASCII)
    private fun ByteArray.u16(offset: Int) = (this[offset].toInt() and 255) or ((this[offset + 1].toInt() and 255) shl 8)
    private fun ByteArray.u32(offset: Int) = u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)
    private fun requireAudio(valid: Boolean) { if (!valid) throw CueException(CueIssue.INVALID_AUDIO) }
}
