package io.github.onlinemo.meloxwatch.data.transfer

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.CancellationSignal
import android.os.OperationCanceledException
import java.io.File
import java.io.IOException

/** Supported concrete inputs: PCM WAV and MPEG Layer III. Other MIME paths remain to be built. */
class PlatformAudioTransferValidator(
    private val decodeMpeg: (File, CancellationSignal) -> Unit = ::decodeMpegSample,
) : TransferContentValidator {
    override fun validate(file: File, declaration: TransferUploadDeclaration, signal: CancellationSignal) {
        if (declaration.kind != TransferUploadKind.AUDIO) throw TransferException(TransferIssue.UNSUPPORTED_TYPE)
        when (declaration.mediaType) {
            "audio/wav", "audio/x-wav" -> PcmWavTransferValidator.validate(file, declaration, signal)
            "audio/mpeg" -> {
                Mp3TransferValidator.validate(file, declaration, signal)
                decodeMpeg(file, signal)
            }
            else -> throw TransferException(TransferIssue.UNSUPPORTED_TYPE)
        }
    }
}

/** A bounded actual PCM decode complements the complete structural walk; metadata is read at import. */
private fun decodeMpegSample(file: File, signal: CancellationSignal) {
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    var started = false
    try {
        signal.throwIfCanceled()
        extractor.setDataSource(file.absolutePath)
        if (extractor.trackCount != 1) throw TransferException(TransferIssue.NOT_AUDIO)
        val format = extractor.getTrackFormat(0)
        if (format.getString(MediaFormat.KEY_MIME) != "audio/mpeg") throw TransferException(TransferIssue.NOT_AUDIO)
        extractor.selectTrack(0)
        codec = MediaCodec.createDecoderByType("audio/mpeg")
        codec.configure(format, null, null, 0)
        codec.start()
        started = true
        val deadline = System.nanoTime() + 8_000_000_000L
        val info = MediaCodec.BufferInfo()
        var sent = 0
        var inputEnded = false
        while (System.nanoTime() < deadline) {
            signal.throwIfCanceled()
            if (!inputEnded) {
                val index = codec.dequeueInputBuffer(10_000L)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index) ?: throw TransferException(TransferIssue.UNSUPPORTED_TYPE)
                    buffer.clear()
                    if (buffer.capacity() > MAX_SAMPLE_BYTES) buffer.limit(MAX_SAMPLE_BYTES)
                    val count = if (sent >= MAX_SAMPLES) -1 else extractor.readSampleData(buffer, 0)
                    if (count < 0) {
                        codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        if (count == 0 || count > MAX_SAMPLE_BYTES || count > buffer.capacity() ||
                            (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) throw TransferException(TransferIssue.NOT_AUDIO)
                        codec.queueInputBuffer(index, 0, count, maxOf(0L, extractor.sampleTime), 0)
                        sent++
                        extractor.advance()
                    }
                }
            }
            val output = codec.dequeueOutputBuffer(info, 10_000L)
            if (output >= 0) {
                val decoded = info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                codec.releaseOutputBuffer(output, false)
                if (decoded) return
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) throw TransferException(TransferIssue.NOT_AUDIO)
            }
        }
        throw TransferException(TransferIssue.INTERRUPTED)
    } catch (error: OperationCanceledException) { throw error }
    catch (error: TransferException) { throw error }
    catch (_: IOException) { throw TransferException(TransferIssue.UNSUPPORTED_TYPE) }
    catch (_: IllegalArgumentException) { throw TransferException(TransferIssue.NOT_AUDIO) }
    catch (_: IllegalStateException) { throw TransferException(TransferIssue.UNSUPPORTED_TYPE) }
    finally {
        try { if (started) codec?.stop() }
        finally { try { codec?.release() } finally { extractor.release() } }
    }
}

private const val MAX_SAMPLE_BYTES = 64 * 1024
private const val MAX_SAMPLES = 16
