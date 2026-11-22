package io.github.onlinemo.meloxwatch.data.fileoperations.fixture

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import io.github.onlinemo.meloxwatch.data.fileoperations.AudioTagField
import io.github.onlinemo.meloxwatch.data.fileoperations.AudioTagPatch
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationCommand
import io.github.onlinemo.meloxwatch.data.fileoperations.FileOperationItem
import io.github.onlinemo.meloxwatch.data.fileoperations.SafOriginalFileAdapter
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** 仅由明确 am instrument 调用；结果需在外部报告绑定实际 cloud APK，不能当作 UI/整套 AC 验收。 */
class FileOperationNativeInstrumentation : Instrumentation() {
    private var selected = "all"

    override fun onCreate(arguments: Bundle?) {
        selected = arguments?.getString("case") ?: "all"
        require(selected in setOf("all", "flac", "mp3"))
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            if (selected in setOf("all", "flac")) verifyNative("flac", AudioWriteFixtures.flac(), "audio/flac")
            if (selected in setOf("all", "mp3")) verifyNative("mp3", AudioWriteFixtures.mp3(), "audio/mpeg")
            result.putString("lp10_case", selected)
            result.putBoolean("physical_tag_bytes_verified", true)
            result.putBoolean("native_metadata_verified", true)
            result.putBoolean("native_pcm_equal", true)
            finish(Activity.RESULT_OK, result)
        } catch (failure: Exception) {
            result.putString("lp10_case", selected)
            result.putString("failure_class", failure.javaClass.simpleName.take(96))
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun verifyNative(extension: String, originalBytes: ByteArray, mime: String) {
        val context = targetContext
        val authority = "${context.packageName}.lp10fixture"
        val tree = FixtureDocumentTree(File(context.cacheDir, "lp10-native-fixture/${UUID.randomUUID()}"))
        FileOperationFixtureRegistry.install(authority, tree)
        var grantUri: Uri? = null
        try {
            val documentId = tree.putAudio("fixture.$extension", originalBytes, mime)
            val base = fixtureFileTarget(context, authority, tree, documentId)
            grantUri = Uri.parse(checkNotNull(base.descriptor).source.spec.uri)
            val adapter = SafOriginalFileAdapter(context)
            val signal = CancellationSignal()
            val command = FileOperationCommand.writeTags(AudioTagPatch(mapOf(
                AudioTagField.TITLE to listOf("After"), AudioTagField.ARTISTS to listOf("Artist A", "Artist B"),
                AudioTagField.TRACK_NUMBER to listOf("8/16"), AudioTagField.DISC_NUMBER to listOf("2"),
            )))
            val target = base.copy(proof = adapter.inspect(base, command.kind, signal))
            val beforeDecode = decode(context, Uri.parse(checkNotNull(target.proof).uri))
            val before = IndependentAudioTagReader.read(originalBytes)
            val saved = ArrayList<FileOperationItem>()
            val applied = adapter.apply(FileOperationItem(UUID.randomUUID().toString(), 0L, target), command, false, signal) { saved += it }
            val published = checkNotNull(applied.artifacts.published)
            val after = IndependentAudioTagReader.read(tree.bytes(published.proof.objectId))
            IndependentAudioTagReader.requirePreserved(before, after)
            check(after.fields["TITLE"] == listOf("After"))
            check(after.fields["ARTIST"] == listOf("Artist A", "Artist B"))
            check(after.fields["ALBUM"] == listOf("Keep album"))
            check(after.fields["TRACKNUMBER"] == listOf("8/16"))
            val metadata = MediaMetadataRetriever()
            try {
                metadata.setDataSource(context, Uri.parse(published.proof.uri))
                check(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) == "After")
                check(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { it > 0L } == true)
            } finally {
                metadata.release()
            }
            val afterDecode = decode(context, Uri.parse(published.proof.uri))
            check(beforeDecode == afterDecode && afterDecode.pcmBytes > 0L)
            check(saved.isNotEmpty() && saved.last() == applied)
            adapter.finishArtifacts(applied.copy(phase = io.github.onlinemo.meloxwatch.data.fileoperations.FileItemPhase.INDEX_RECONCILED), signal)
            tree.assertClosed()
        } finally {
            grantUri?.let { context.revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            FileOperationFixtureRegistry.remove(authority, tree)
            tree.close()
        }
    }

    private data class DecodedAudio(val pcmBytes: Long, val sha256: List<Byte>)

    /** 用真实 extractor/decoder 读取 PCM，不创建音频输出；循环有实际十秒截止。 */
    private fun decode(context: Context, uri: Uri): DecodedAudio {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var started = false
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No native audio track")
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val decoder = MediaCodec.createDecoderByType(checkNotNull(format.getString(MediaFormat.KEY_MIME)))
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            started = true
            val deadline = SystemClock.elapsedRealtime() + 10_000L
            val info = MediaCodec.BufferInfo()
            val digest = MessageDigest.getInstance("SHA-256")
            var inputEnded = false
            var outputEnded = false
            var pcmBytes = 0L
            var turns = 0
            while (!outputEnded) {
                check(SystemClock.elapsedRealtime() < deadline && turns++ < 4096)
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val input = checkNotNull(decoder.getInputBuffer(inputIndex))
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = decoder.dequeueOutputBuffer(info, 10_000L)
                if (outputIndex >= 0) {
                    try {
                        if (info.size > 0) {
                            val output = checkNotNull(decoder.getOutputBuffer(outputIndex))
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            digest.update(output)
                            pcmBytes = Math.addExact(pcmBytes, info.size.toLong())
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally {
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            check(pcmBytes > 0L)
            return DecodedAudio(pcmBytes, digest.digest().toList())
        } finally {
            try {
                if (started) codec?.stop()
            } finally {
                try { codec?.release() } finally { extractor.release() }
            }
        }
    }
}
