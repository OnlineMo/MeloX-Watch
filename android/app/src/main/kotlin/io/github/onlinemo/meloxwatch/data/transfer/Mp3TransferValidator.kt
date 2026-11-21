package io.github.onlinemo.meloxwatch.data.transfer

import android.os.CancellationSignal
import java.io.File
import java.io.RandomAccessFile

/** Full MPEG Layer III frame boundaries, with bounded ID3 headers and no suffix-based trust. */
object Mp3TransferValidator : TransferContentValidator {
    override fun validate(file: File, declaration: TransferUploadDeclaration, signal: CancellationSignal) {
        if (declaration.kind != TransferUploadKind.AUDIO || declaration.mediaType != "audio/mpeg") {
            throw TransferException(TransferIssue.UNSUPPORTED_TYPE)
        }
        RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            if (length < 4L || length > TransferLimits.AUDIO_BYTES) invalid()
            var start = 0L
            if (length >= 10L) {
                val header = ByteArray(10)
                input.readFully(header)
                if (header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) {
                    val version = header[3].toInt() and 255
                    val flags = header[5].toInt() and 255
                    if (version !in 2..4 || (header[4].toInt() and 255) == 255 ||
                        (flags and (if (version == 2) 0x3f else if (version == 3) 0x1f else 0x0f)) != 0) invalid()
                    var tagBytes = 0L
                    for (index in 6..9) {
                        val part = header[index].toInt() and 255
                        if (part >= 128) invalid()
                        tagBytes = (tagBytes shl 7) or part.toLong()
                    }
                    if (tagBytes > MAX_ID3_BYTES || tagBytes > length - 10L) invalid()
                    start = 10L + tagBytes
                    if (version == 4 && (flags and 0x10) != 0) {
                        if (length - start < 10L) invalid()
                        input.seek(start)
                        val footer = ByteArray(10)
                        input.readFully(footer)
                        if (footer[0] != '3'.code.toByte() || footer[1] != 'D'.code.toByte() || footer[2] != 'I'.code.toByte() ||
                            !footer.copyOfRange(3, 10).contentEquals(header.copyOfRange(3, 10))) invalid()
                        start += 10L
                    }
                }
            }
            var end = length
            if (length - start >= 128L) {
                input.seek(length - 128L)
                if (input.readUnsignedByte() == 'T'.code && input.readUnsignedByte() == 'A'.code && input.readUnsignedByte() == 'G'.code) end -= 128L
            }
            var position = start
            var first: Format? = null
            var frames = 0L
            while (position < end) {
                signal.throwIfCanceled()
                if (end - position < 4L) invalid()
                input.seek(position)
                val header = input.readInt()
                if ((header and 0xffe00000.toInt()) != 0xffe00000.toInt()) invalid()
                val version = (header ushr 19) and 3
                if (version == 1 || ((header ushr 17) and 3) != 1 || (header and 3) == 2) invalid()
                val bitrateIndex = (header ushr 12) and 15
                val rateIndex = (header ushr 10) and 3
                if (bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) invalid()
                val rate = RATES[rateIndex] / if (version == 3) 1 else if (version == 2) 2 else 4
                val channels = if (((header ushr 6) and 3) == 3) 1 else 2
                val format = Format(version, rate, channels)
                if (first == null) first = format else if (first != format) invalid()
                val bitrate = (if (version == 3) MPEG_ONE_BITRATES else MPEG_TWO_BITRATES)[bitrateIndex] * 1000
                val frameBytes = (if (version == 3) 144L else 72L) * bitrate / rate + ((header ushr 9) and 1)
                val crcBytes = if (((header ushr 16) and 1) == 0) 2 else 0
                val sideBytes = if (version == 3) (if (channels == 1) 17 else 32) else (if (channels == 1) 9 else 17)
                if (frameBytes <= 4L + crcBytes + sideBytes || frameBytes > end - position) invalid()
                position += frameBytes
                frames = Math.addExact(frames, 1L)
            }
            if (frames == 0L || position != end) invalid()
        }
    }

    private fun invalid(): Nothing = throw TransferException(TransferIssue.NOT_AUDIO)
    private data class Format(val version: Int, val sampleRate: Int, val channels: Int)
    private const val MAX_ID3_BYTES = 16L * 1024 * 1024
    private val RATES = intArrayOf(44100, 48000, 32000)
    private val MPEG_ONE_BITRATES = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val MPEG_TWO_BITRATES = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
}
