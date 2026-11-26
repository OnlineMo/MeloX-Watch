package io.github.onlinemo.meloxwatch.data.cue

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackInputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.charset.UnsupportedCharsetException
import java.security.MessageDigest
import java.util.Locale

/**
 * Original narrow CUE parser: FILE, AUDIO TRACK, TITLE, PERFORMER, INDEX 00/01 and opaque REM.
 * Unknown directives/data tracks fail. INDEX 00 belongs to the preceding segment; first-file
 * leading audio remains reachable through the preserved physical track. No silence is synthesized.
 * TRACK uses two digits, unique increasing 01..99; timestamps use at least two minute digits,
 * exactly two second/frame digits, seconds 00..59 and frames 00..74. Gaps fail even at zero length.
 */
object CueParser {
    fun parse(
        bytes: ByteArray,
        encoding: CueEncoding = CueEncoding.UTF8,
        checkCancelled: () -> Unit = {},
    ): ParsedCue = read(ByteArrayInputStream(bytes), bytes.size.toLong(), encoding, checkCancelled)

    /**
     * Borrowed stream: caller owns closing and the existing LocalProviderIo physical channel.
     * Call off Main; this function creates no dispatcher/executor and never retries another encoding.
     * 只有显式选择编码并重试时才使用 GB18030。
     */
    fun read(
        input: InputStream,
        declaredBytes: Long? = null,
        encoding: CueEncoding = CueEncoding.UTF8,
        checkCancelled: () -> Unit = {},
    ): ParsedCue {
        checkCancelled()
        if (declaredBytes != null && declaredBytes < 0L) throw CueException(CueIssue.INVALID_INPUT)
        if (declaredBytes != null && declaredBytes > CueLimits.RAW_BYTES) throw CueException(CueIssue.LIMIT_EXCEEDED)
        val bounded = BoundedCueInput(input, declaredBytes, checkCancelled)
        val prefixed = PushbackInputStream(bounded, 3)
        val prefix = ByteArray(3)
        var prefixSize = 0
        while (prefixSize < prefix.size) {
            val value = prefixed.read()
            if (value < 0) break
            prefix[prefixSize++] = value.toByte()
        }
        val utf8Bom = prefixSize == 3 && prefix[0] == 0xef.toByte() &&
            prefix[1] == 0xbb.toByte() && prefix[2] == 0xbf.toByte()
        val utf16Bom = prefixSize >= 2 && ((prefix[0] == 0xff.toByte() && prefix[1] == 0xfe.toByte()) ||
            (prefix[0] == 0xfe.toByte() && prefix[1] == 0xff.toByte()))
        if (utf16Bom || (utf8Bom && encoding != CueEncoding.UTF8)) throw CueException(CueIssue.INVALID_ENCODING)
        if (!utf8Bom && prefixSize > 0) prefixed.unread(prefix, 0, prefixSize)
        val charset = try {
            if (encoding == CueEncoding.UTF8) StandardCharsets.UTF_8 else Charset.forName("GB18030")
        } catch (error: UnsupportedCharsetException) {
            throw CueException(CueIssue.UNSUPPORTED_ENCODING, cause = error)
        }
        val decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val state = ParseState()
        try {
            readLines(InputStreamReader(prefixed, decoder), state, checkCancelled)
        } catch (error: CharacterCodingException) {
            throw CueException(CueIssue.INVALID_ENCODING, cause = error)
        }
        checkCancelled()
        return state.finish(encoding, bounded.completeToken())
    }

    private fun readLines(reader: InputStreamReader, state: ParseState, checkCancelled: () -> Unit) {
        val buffer = CharArray(4096)
        val line = StringBuilder()
        var lineNumber = 0
        var previousCarriageReturn = false
        fun emit() {
            checkCancelled()
            lineNumber++
            if (lineNumber > CueLimits.LINES) throw CueException(CueIssue.LIMIT_EXCEEDED, lineNumber)
            state.consume(line.toString(), lineNumber)
            line.setLength(0)
        }
        while (true) {
            checkCancelled()
            val size = reader.read(buffer)
            if (size < 0) break
            if (size == 0) throw CueException(CueIssue.INVALID_INPUT)
            for (index in 0 until size) {
                if ((index and 255) == 0) checkCancelled()
                val character = buffer[index]
                if (character == '\n' && previousCarriageReturn) {
                    previousCarriageReturn = false
                    continue
                }
                previousCarriageReturn = character == '\r'
                if (character == '\r' || character == '\n') {
                    emit()
                } else {
                    if (Character.isISOControl(character) && character != '\t') {
                        throw CueException(CueIssue.INVALID_SYNTAX, lineNumber + 1)
                    }
                    if (line.length >= CueLimits.LINE_CHARACTERS) throw CueException(CueIssue.LIMIT_EXCEEDED, lineNumber + 1)
                    line.append(character)
                }
            }
        }
        if (line.isNotEmpty()) emit()
    }

    private class MetadataBuilder {
        var title: String? = null
        var performer: String? = null

        fun set(directive: String, value: String, line: Int) {
            if (value.length > CueLimits.FIELD_CHARACTERS) throw CueException(CueIssue.LIMIT_EXCEEDED, line)
            if (value.any { Character.isISOControl(it) }) throw CueException(CueIssue.INVALID_SYNTAX, line)
            if (directive == "TITLE") {
                if (title != null) throw CueException(CueIssue.INVALID_SYNTAX, line)
                title = value
            } else {
                if (performer != null) throw CueException(CueIssue.INVALID_SYNTAX, line)
                performer = value
            }
        }

        fun freeze(): CueMetadata = CueMetadata(title, performer)
    }

    private class FileBuilder(val index: Int, val path: CueRelativePath, val type: CueFileType) {
        val metadata = MetadataBuilder()
        var trackCount = 0
    }

    private class TrackBuilder(val number: Int, val fileIndex: Int) {
        val metadata = MetadataBuilder()
        var index00: CueFrameTime? = null
        var index01: CueFrameTime? = null
    }

    private class ParseState {
        private val metadata = MetadataBuilder()
        private val files = ArrayList<FileBuilder>()
        private val tracks = ArrayList<TrackBuilder>()
        private val paths = HashSet<CueRelativePath>()
        private var currentFile: FileBuilder? = null
        private var currentTrack: TrackBuilder? = null
        private var lastLine = 1

        fun consume(line: String, lineNumber: Int) {
            lastLine = lineNumber
            val cursor = LineCursor(line, lineNumber)
            val directive = cursor.atomOrNull()?.uppercase(Locale.ROOT) ?: return
            when (directive) {
                "REM" -> Unit
                "FILE" -> {
                    finishTrack(lineNumber)
                    if (currentFile?.trackCount == 0) throw CueException(CueIssue.INVALID_TRACK, lineNumber)
                    if (files.size >= CueLimits.FILES) throw CueException(CueIssue.LIMIT_EXCEEDED, lineNumber)
                    val rawPath = cursor.valueToken()
                    val type = when (cursor.atom().uppercase(Locale.ROOT)) {
                        "WAVE" -> CueFileType.WAVE
                        "MP3" -> CueFileType.MP3
                        "AIFF" -> CueFileType.AIFF
                        else -> throw CueException(CueIssue.UNSUPPORTED_FILE_TYPE, lineNumber)
                    }
                    cursor.requireEnd()
                    val path = try { CueRelativePath.parse(rawPath) }
                    catch (error: CueException) { throw CueException(error.issue, lineNumber, error) }
                    if (!paths.add(path)) throw CueException(CueIssue.DUPLICATE_REFERENCE, lineNumber)
                    val file = FileBuilder(files.size, path, type)
                    files += file
                    currentFile = file
                    currentTrack = null
                }
                "TRACK" -> {
                    val file = currentFile ?: throw CueException(CueIssue.INVALID_TRACK, lineNumber)
                    finishTrack(lineNumber)
                    if (tracks.size >= CueLimits.TRACKS) throw CueException(CueIssue.LIMIT_EXCEEDED, lineNumber)
                    val token = cursor.atom()
                    val number = if (token.length == 2 && token.all { it in '0'..'9' }) token.toInt() else -1
                    if (number !in 1..CueLimits.TRACKS || number <= (tracks.lastOrNull()?.number ?: 0)) {
                        throw CueException(CueIssue.INVALID_TRACK, lineNumber)
                    }
                    if (cursor.atom().uppercase(Locale.ROOT) != "AUDIO") throw CueException(CueIssue.INVALID_TRACK, lineNumber)
                    cursor.requireEnd()
                    val track = TrackBuilder(number, file.index)
                    tracks += track
                    currentTrack = track
                    file.trackCount++
                }
                "TITLE", "PERFORMER" -> {
                    val target = currentTrack?.metadata ?: currentFile?.metadata ?: metadata
                    target.set(directive, cursor.remainderValue(), lineNumber)
                }
                "INDEX" -> {
                    val track = currentTrack ?: throw CueException(CueIssue.INVALID_INDEX, lineNumber)
                    val index = cursor.atom()
                    val time = timestamp(cursor.atom(), lineNumber)
                    cursor.requireEnd()
                    val previous = tracks.getOrNull(tracks.size - 2)?.takeIf { it.fileIndex == track.fileIndex }?.index01
                    when (index) {
                        "00" -> {
                            if (track.index00 != null || track.index01 != null) throw CueException(CueIssue.INVALID_INDEX, lineNumber)
                            if (previous != null && time.frames < previous.frames) throw CueException(CueIssue.INVALID_BOUNDARIES, lineNumber)
                            track.index00 = time
                        }
                        "01" -> {
                            if (track.index01 != null) throw CueException(CueIssue.INVALID_INDEX, lineNumber)
                            if ((track.index00?.frames ?: 0L) > time.frames ||
                                (previous != null && time.frames <= previous.frames)) {
                                throw CueException(CueIssue.INVALID_BOUNDARIES, lineNumber)
                            }
                            track.index01 = time
                        }
                        else -> throw CueException(CueIssue.UNSUPPORTED_DIRECTIVE, lineNumber)
                    }
                }
                "PREGAP", "POSTGAP" -> {
                    if (currentTrack == null) throw CueException(CueIssue.INVALID_TRACK, lineNumber)
                    timestamp(cursor.atom(), lineNumber)
                    cursor.requireEnd()
                    throw CueException(CueIssue.UNSUPPORTED_GAP, lineNumber)
                }
                else -> throw CueException(CueIssue.UNSUPPORTED_DIRECTIVE, lineNumber)
            }
        }

        fun finish(encoding: CueEncoding, token: String): ParsedCue {
            finishTrack(lastLine)
            if (files.isEmpty() || tracks.isEmpty() || currentFile?.trackCount == 0) {
                throw CueException(CueIssue.INVALID_TRACK, lastLine)
            }
            return ParsedCue(encoding, token, metadata.freeze(),
                files.map { CueFileDeclaration(it.index, it.path, it.type, it.metadata.freeze()) },
                tracks.map { CueTrackDeclaration(it.number, it.fileIndex, it.metadata.freeze(), it.index00,
                    it.index01 ?: throw CueException(CueIssue.INVALID_INDEX, lastLine)) })
        }

        private fun finishTrack(line: Int) {
            val track = currentTrack
            if (track != null && track.index01 == null) throw CueException(CueIssue.INVALID_INDEX, line)
        }
    }

    private fun timestamp(value: String, line: Int): CueFrameTime {
        val parts = value.split(':')
        if (parts.size != 3 || parts[0].length < 2 || parts[1].length != 2 || parts[2].length != 2 ||
            parts.any { part -> part.isEmpty() || part.any { it !in '0'..'9' } }) {
            throw CueException(CueIssue.INVALID_TIME, line)
        }
        val minutes = parts[0].toLongOrNull() ?: throw CueException(CueIssue.INVALID_TIME, line)
        val seconds = parts[1].toInt()
        val subframes = parts[2].toInt()
        if (seconds !in 0..59 || subframes !in 0..74) throw CueException(CueIssue.INVALID_TIME, line)
        val frames = try { Math.addExact(Math.multiplyExact(minutes, 4500L), seconds * 75L + subframes) }
        catch (error: ArithmeticException) { throw CueException(CueIssue.INVALID_TIME, line, error) }
        if (frames > CueLimits.MAX_FRAME_COUNT) throw CueException(CueIssue.INVALID_TIME, line)
        return CueFrameTime(frames)
    }

    private class LineCursor(private val line: String, private val lineNumber: Int) {
        private var offset = 0

        fun atomOrNull(): String? {
            whitespace()
            if (offset == line.length) return null
            if (line[offset] == '"') throw CueException(CueIssue.INVALID_SYNTAX, lineNumber)
            val start = offset
            while (offset < line.length && line[offset] != ' ' && line[offset] != '\t') offset++
            return line.substring(start, offset)
        }

        fun atom(): String = atomOrNull() ?: throw CueException(CueIssue.INVALID_SYNTAX, lineNumber)

        fun valueToken(): String {
            whitespace()
            if (offset == line.length) throw CueException(CueIssue.INVALID_SYNTAX, lineNumber)
            if (line[offset] != '"') return atom()
            offset++
            val start = offset
            while (offset < line.length && line[offset] != '"') offset++
            if (offset == line.length) throw CueException(CueIssue.INVALID_SYNTAX, lineNumber)
            val result = line.substring(start, offset++)
            if (offset < line.length && line[offset] != ' ' && line[offset] != '\t') throw CueException(CueIssue.INVALID_SYNTAX, lineNumber)
            return result
        }

        fun remainderValue(): String {
            whitespace()
            if (offset == line.length) throw CueException(CueIssue.INVALID_SYNTAX, lineNumber)
            if (line[offset] == '"') return valueToken().also { requireEnd() }
            val result = line.substring(offset).trimEnd(' ', '\t')
            if (result.contains('"')) throw CueException(CueIssue.INVALID_SYNTAX, lineNumber)
            offset = line.length
            return result
        }

        fun requireEnd() {
            whitespace()
            if (offset != line.length) throw CueException(CueIssue.INVALID_SYNTAX, lineNumber)
        }

        private fun whitespace() {
            while (offset < line.length && (line[offset] == ' ' || line[offset] == '\t')) offset++
        }
    }

    private class BoundedCueInput(
        private val input: InputStream,
        private val declaredBytes: Long?,
        private val checkCancelled: () -> Unit,
    ) : InputStream() {
        private val digest = MessageDigest.getInstance("SHA-256")
        private var total = 0L
        private var exhausted = false

        override fun read(): Int {
            checkCancelled()
            val value = input.read()
            checkCancelled()
            if (value < 0) { end(); return -1 }
            if (value > 255) throw CueException(CueIssue.INVALID_INPUT)
            account(1)
            digest.update(value.toByte())
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            checkCancelled()
            if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
            if (length == 0) return 0
            val requested = minOf(length.toLong(), CueLimits.RAW_BYTES.toLong() - total + 1L).toInt()
            val size = input.read(buffer, offset, requested)
            checkCancelled()
            if (size < 0) { end(); return -1 }
            if (size > requested) throw CueException(CueIssue.INVALID_INPUT)
            if (size == 0) {
                val value = read()
                if (value >= 0) buffer[offset] = value.toByte()
                return if (value < 0) -1 else 1
            }
            account(size)
            digest.update(buffer, offset, size)
            return size
        }

        fun completeToken(): String {
            if (!exhausted) throw CueException(CueIssue.INVALID_INPUT)
            return "sha256:${cueHex(digest.digest())}"
        }

        private fun account(size: Int) {
            total += size
            if (total > CueLimits.RAW_BYTES) throw CueException(CueIssue.LIMIT_EXCEEDED)
            if (declaredBytes != null && total > declaredBytes) throw CueException(CueIssue.SOURCE_CHANGED)
        }

        private fun end() {
            if (declaredBytes != null && total != declaredBytes) throw CueException(CueIssue.SOURCE_CHANGED)
            exhausted = true
        }
    }
}
