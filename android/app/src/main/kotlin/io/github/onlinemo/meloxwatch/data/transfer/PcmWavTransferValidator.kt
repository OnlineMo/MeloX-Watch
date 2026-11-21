package io.github.onlinemo.meloxwatch.data.transfer

import android.os.CancellationSignal
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

/** First concrete audio path: bounded RIFF/WAVE PCM16 validation, not MIME/extension acceptance. */
object PcmWavTransferValidator : TransferContentValidator {
    override fun validate(file: File, declaration: TransferUploadDeclaration, signal: CancellationSignal) {
        if (declaration.kind != TransferUploadKind.AUDIO || declaration.mediaType !in setOf("audio/wav", "audio/x-wav")) throw TransferException(TransferIssue.UNSUPPORTED_TYPE)
        FileChannel.open(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            val size = input.size()
            if (size !in 44..TransferLimits.AUDIO_BYTES) throw TransferException(TransferIssue.NOT_AUDIO)
            val header = read(input, 12, signal)
            if (tag(header) != "RIFF") throw TransferException(TransferIssue.NOT_AUDIO)
            val riffBytes = unsignedInt(header)
            if (tag(header) != "WAVE" || riffBytes + 8L != size) throw TransferException(TransferIssue.NOT_AUDIO)
            var blockAlign = 0
            var formatSeen = false
            var dataSeen = false
            var chunks = 0
            while (input.position() < size) {
                signal.throwIfCanceled()
                if (++chunks > 256 || size - input.position() < 8L) throw TransferException(TransferIssue.NOT_AUDIO)
                val chunk = read(input, 8, signal)
                val kind = tag(chunk)
                val bytes = unsignedInt(chunk)
                val start = input.position()
                val padded = bytes + (bytes and 1L)
                if (padded > size - start) throw TransferException(TransferIssue.NOT_AUDIO)
                when (kind) {
                    "fmt " -> {
                        if (formatSeen || dataSeen || bytes !in 16..64) throw TransferException(TransferIssue.NOT_AUDIO)
                        val format = read(input, 16, signal)
                        val encoding = format.short.toInt() and 65535
                        val channels = format.short.toInt() and 65535
                        val rate = unsignedInt(format)
                        val byteRate = unsignedInt(format)
                        blockAlign = format.short.toInt() and 65535
                        val bits = format.short.toInt() and 65535
                        if (encoding != 1 || channels !in 1..2 || rate !in 8000..192000 || bits != 16 || blockAlign != channels * 2 || byteRate != rate * blockAlign) throw TransferException(TransferIssue.UNSUPPORTED_TYPE)
                        formatSeen = true
                    }
                    "data" -> {
                        if (!formatSeen || dataSeen || bytes == 0L || bytes % blockAlign != 0L) throw TransferException(TransferIssue.NOT_AUDIO)
                        dataSeen = true
                    }
                }
                signal.throwIfCanceled()
                input.position(start + padded)
            }
            if (!formatSeen || !dataSeen) throw TransferException(TransferIssue.NOT_AUDIO)
            signal.throwIfCanceled()
        }
    }

    private fun read(input: FileChannel, count: Int, signal: CancellationSignal): ByteBuffer {
        val bytes = ByteBuffer.allocate(count).order(ByteOrder.LITTLE_ENDIAN)
        while (bytes.hasRemaining()) {
            signal.throwIfCanceled()
            if (input.read(bytes) <= 0) throw TransferException(TransferIssue.NOT_AUDIO)
        }
        val view: java.nio.Buffer = bytes
        view.flip()
        return bytes
    }
    private fun tag(bytes: ByteBuffer): String = String(ByteArray(4).also(bytes::get), Charsets.US_ASCII)
    private fun unsignedInt(bytes: ByteBuffer): Long = bytes.int.toLong() and 0xffffffffL
}
