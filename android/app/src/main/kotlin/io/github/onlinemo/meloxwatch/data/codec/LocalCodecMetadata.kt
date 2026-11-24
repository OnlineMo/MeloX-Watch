package io.github.onlinemo.meloxwatch.data.codec

import android.os.CancellationSignal
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.library.LocalCodecParameters
import io.github.onlinemo.meloxwatch.data.local.AudioTagReader
import io.github.onlinemo.meloxwatch.data.local.AudioTextTags
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import java.io.EOFException
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer

/** A real small PCM decode qualifies custom metadata; ordinary sources retain their native reader. */
internal object LocalCodecMetadata {
    fun read(
        stream: FileInputStream,
        startOffset: Long,
        declaredLength: Long,
        signal: CancellationSignal,
        nativeRead: (InputStream) -> LocalAudioMetadata,
    ): LocalAudioMetadata {
        val prefixStream = PushbackInputStream(stream, 4)
        val prefix = ByteArray(4)
        var count = 0
        while (count < 4) {
            signal.throwIfCanceled()
            val read = prefixStream.read(prefix, count, 4 - count)
            if (read < 0) break
            if (read == 0) throw EOFException("Metadata input made no progress")
            count += read
        }
        prefixStream.unread(prefix, 0, count)
        val definite = count == 4 && prefix.fourCc() in setOf("DSD ", "FRM8", "MAC ")
        val possibleId3 = count == 4 && prefix[0] == 'I'.code.toByte() && prefix[1] == 'D'.code.toByte() && prefix[2] == '3'.code.toByte()
        if (!definite && !possibleId3) return nativeRead(prefixStream)
        val input = try { FileCodecInput(stream, startOffset, declaredLength, count.toLong(), signal) }
        catch (failure: IOException) {
            if (definite) throw SourceReadException(SourceReadIssue.UNSUPPORTED_FORMAT, failure)
            return nativeRead(prefixStream)
        }
        if (input.length <= 0) {
            if (definite) throw SourceReadException(SourceReadIssue.UNSUPPORTED_FORMAT)
            return nativeRead(prefixStream)
        }
        val decoder = try { LocalPcmCodecs.open(input) }
        catch (failure: CodecReadException) { throw metadataFailure(failure) }
        catch (failure: EOFException) { throw SourceReadException(SourceReadIssue.METADATA_UNREADABLE, failure) }
        catch (failure: IOException) {
            if (!definite && input.position == count.toLong()) return nativeRead(prefixStream)
            throw failure
        }
        if (decoder == null) {
            input.seek(count.toLong())
            return nativeRead(prefixStream)
        }
        return decoder.use {
            try {
                val decoded = ByteArray(64 * decoder.format.pcm.frameBytes)
                codecRequire(decoder.readFrames(decoded, 0, 64) > 0)
                signal.throwIfCanceled()
                val format = decoder.format
                val tags = auxiliaryTags(input, format, signal)
                LocalAudioMetadata(title = tags.title, artist = tags.artist, artists = tags.artists,
                    album = tags.album, albumArtist = tags.albumArtist, durationMs = format.durationMs.takeIf { it > 0 },
                    discNumber = number(tags.discNumber), trackNumber = number(tags.trackNumber), compilation = tags.compilation ?: false,
                    mimeType = when (format.container) { "DSF" -> "audio/x-dsf"; "DFF" -> "audio/x-dff"; else -> "audio/x-ape" },
                    parserVersion = AudioTagReader.PARSER_VERSION,
                    codecParameters = LocalCodecParameters(format.container, format.encoding, format.inputSampleRate,
                        format.inputBitsPerSample, format.channels, format.pcm.sampleRate, format.pcm.bitsPerSample,
                        format.version, format.compressionLevel))
            } catch (failure: CodecReadException) { throw metadataFailure(failure) }
            catch (failure: EOFException) { throw SourceReadException(SourceReadIssue.METADATA_UNREADABLE, failure) }
        }
    }

    private fun auxiliaryTags(input: CodecInput, format: LocalCodecFormat, signal: CancellationSignal): AudioTextTags = try {
        val offset = format.tagOffset
        if (offset != null) {
            input.moveTo(offset)
            AudioTagReader.readText(object : InputStream() {
                private val one = ByteArray(1)
                override fun read(): Int = if (input.read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
                override fun read(bytes: ByteArray, offset: Int, count: Int): Int = input.read(bytes, offset, count)
            }, signal)
        } else if (format.container == "APE") {
            val fields = ApeTagFields.read(input)
            val artist = fields["ARTIST"]
            val artists = artist?.split('\u0000', limit = 129).orEmpty()
            codecRequire(artists.size <= 128, CodecReadIssue.RESOURCE_LIMIT)
            AudioTextTags(fields["TITLE"], artist, artists.filter(String::isNotBlank).distinct(), fields["ALBUM"],
                fields["ALBUMARTIST"] ?: fields["ALBUM ARTIST"], fields["TRACK"], fields["DISC"],
                fields["COMPILATION"]?.let { it == "1" || it.equals("true", true) })
        } else AudioTextTags()
    } catch (failure: CodecReadException) {
        if (failure.issue == CodecReadIssue.SOURCE_CHANGED) throw failure
        AudioTextTags()
    } catch (_: EOFException) { AudioTextTags() }
    catch (_: java.nio.charset.CharacterCodingException) { AudioTextTags() }
    catch (failure: SourceReadException) {
        if (failure.issue != SourceReadIssue.METADATA_UNREADABLE) throw failure
        AudioTextTags()
    }

    private fun number(value: String?): Int? = value?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 }
    private fun metadataFailure(failure: CodecReadException) = SourceReadException(when (failure.issue) {
        CodecReadIssue.INVALID_CONTAINER -> SourceReadIssue.METADATA_UNREADABLE
        CodecReadIssue.SOURCE_CHANGED -> SourceReadIssue.SOURCE_CHANGED
        else -> SourceReadIssue.UNSUPPORTED_FORMAT
    }, failure)
}

/** The surrounding descriptor/stream use owns close; positions stay within that asset's byte extent. */
private class FileCodecInput(
    private val stream: FileInputStream,
    private val startOffset: Long,
    declaredLength: Long,
    initialPosition: Long,
    private val signal: CancellationSignal,
) : CodecInput {
    override val length: Long = if (declaredLength >= 0) declaredLength else {
        val size = stream.channel.size()
        if (size <= startOffset) -1 else size - startOffset
    }
    override var position = initialPosition
        private set
    override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
        signal.throwIfCanceled()
        if (count == 0) return 0
        if (length >= 0 && position == length) return -1
        val limit = if (length < 0) count else minOf(count.toLong(), length - position).toInt()
        codecRequire(limit > 0)
        // AFD 流的剩余计数不会随 channel seek 重置。解码走同一 channel，保留原流的
        // prefix 计数；普通格式回退到 count 位置后，Pushback/native 仍读完整且不越资产边界。
        val read = stream.channel.read(ByteBuffer.wrap(bytes, offset, minOf(limit, 16 * 1024)))
        if (read > 0) position += read
        signal.throwIfCanceled()
        return read
    }
    override fun seek(position: Long) {
        signal.throwIfCanceled()
        codecRequire(position >= 0 && (length < 0 || position <= length))
        stream.channel.position(codecAdd(startOffset, position))
        this.position = position
    }
    override fun checkCancellation() = signal.throwIfCanceled()
    override fun close() = Unit
}
