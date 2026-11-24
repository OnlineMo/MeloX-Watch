package io.github.onlinemo.meloxwatch.data.lyrics

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.UnsupportedCharsetException
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * Local text only: standard multi-tag LRC and the explicit [M:SS]<M:SS>fragment enhanced dialect.
 * Each valid angle tag supplies a real fragment start; the next real tag may supply its end.
 * A multi-line-tag enhanced row is ambiguous and stays line-timed without shifting word tags.
 * The caller owns source authorization, media/content identity, acquisition and cache lifecycle.
 */
class LocalLyricsParser internal constructor(
    private val limits: LyricsLimits,
    private val workDispatcher: CoroutineDispatcher,
) {
    constructor(limits: LyricsLimits = LyricsLimits()) : this(limits, Dispatchers.Default)

    suspend fun parse(
        input: ByteArray,
        encodingChoice: LyricsEncodingChoice = LyricsEncodingChoice.AUTO_BOM_OR_UTF8,
    ): LyricsDocument = withContext(workDispatcher) {
        currentCoroutineContext().ensureActive()
        if (input.size > limits.maxInputBytes) throw LyricsParseException(LyricsParseIssue.INPUT_TOO_LARGE)
        // These encodings need at most one UTF-16 unit per byte. Reserve the caller/owned bytes,
        // a decoder CharBuffer and its resulting String before allocating any parser copy.
        LyricsMemoryBudget(limits, input.size.toLong() * 6L + 256L).check()
        val owned = input.copyOf()
        val resolved = resolveEncoding(owned, encodingChoice)
        val text = decode(owned, resolved)
        val budget = LyricsMemoryBudget(limits, owned.size.toLong() * 4L + text.length.toLong() * 2L + 256L)
        budget.check()
        val counters = Counters()
        val sources = ArrayList<SourceDraft>()
        val times = ArrayList<TimeDraft>()
        var offset: Long? = null
        var start = 0
        while (start < text.length) {
            currentCoroutineContext().ensureActive()
            val lineNumber = ++counters.physicalLines
            if (lineNumber > limits.maxPhysicalLines) throw LyricsParseException(LyricsParseIssue.TOO_MANY_LINES, lineNumber)
            var end = start
            var codePoints = 0
            while (end < text.length && text[end] != '\n' && text[end] != '\r') {
                val character = text[end]
                if (character < ' ' && character != '\t') throw LyricsParseException(LyricsParseIssue.INVALID_TEXT, lineNumber)
                end += if (Character.isHighSurrogate(character) && end + 1 < text.length &&
                    Character.isLowSurrogate(text[end + 1])) 2 else 1
                if (++codePoints > limits.maxLineCodePoints) throw LyricsParseException(LyricsParseIssue.LINE_TOO_LONG, lineNumber)
            }
            budget.checkScratch(end - start, lineNumber)
            val raw = text.substring(start, end)
            val metadata = metadataValues(raw)
            if (metadata != null) {
                counters.ignoredMetadataLines++
                for (value in metadata) {
                    val parsed = value.trim().toLongOrNull()
                    if (parsed == null) counters.invalidOffsets++
                    else if (offset == null) offset = parsed
                    else if (offset != parsed) counters.conflictingOffsets++
                }
            } else {
                val prefix = lineTimes(raw, counters)
                val body = wordText(prefix.text, prefix.times, counters, lineNumber)
                val sourceIndex = sources.size
                budget.retainText(body.text.length, body.words.size, lineNumber)
                sources += SourceDraft(lineNumber, body.text, body.words)
                for ((occurrence, time) in prefix.times.withIndex()) {
                    if (times.size == limits.maxTimelineEntries) {
                        throw LyricsParseException(LyricsParseIssue.TOO_MANY_TIMED_LINES, lineNumber)
                    }
                    budget.retainTimedEntry(lineNumber)
                    times += TimeDraft(time, sourceIndex, occurrence, times.size)
                }
            }
            budget.releaseScratch()
            start = if (end == text.length) end else if (text[end] == '\r' && end + 1 < text.length && text[end + 1] == '\n') {
                end + 2
            } else end + 1
            if (lineNumber % BATCH_SIZE == 0) yield()
        }
        val context = currentCoroutineContext()
        var comparisons = 0
        times.sortWith(Comparator { left, right ->
            if (comparisons++ % 256 == 0) context.ensureActive()
            val byTime = left.timeMs.compareTo(right.timeMs)
            if (byTime != 0) byTime else left.sourceOrder.compareTo(right.sourceOrder)
        })
        validateWordRanges(sources, times, counters)
        val sourceLines = ArrayList<LyricsTextLine>(sources.size)
        for ((index, source) in sources.withIndex()) {
            context.ensureActive()
            sourceLines += LyricsTextLine(source.lineNumber, source.text, immutableLyricsList(source.words))
            if (index % BATCH_SIZE == 0) yield()
        }
        val timedLines = ArrayList<LyricsTimedLine>(times.size)
        for ((index, time) in times.withIndex()) {
            context.ensureActive()
            val source = sourceLines[time.sourceIndex]
            timedLines += LyricsTimedLine(LyricsLineId(source.sourceLineNumber, time.occurrence), time.timeMs, source)
            if (index % BATCH_SIZE == 0) yield()
        }
        val documentId = digest(owned, resolved.encoding)
        context.ensureActive()
        val sourceOffset = offset ?: 0L
        val timeline = LyricsTimeline(documentId, sourceOffset, immutableLyricsList(timedLines))
        LyricsDocument(
            documentId = documentId,
            encoding = resolved.encoding,
            sourceOffsetMs = sourceOffset,
            sourceLines = immutableLyricsList(sourceLines),
            timeline = timeline,
            diagnostics = counters.diagnostics(),
            physicalLineCount = counters.physicalLines,
            accountedResidentBytes = budget.residentBytes,
        )
    }

    private data class ResolvedEncoding(val encoding: LyricsEncoding, val charset: Charset, val skip: Int)
    private data class SourceDraft(val lineNumber: Int, val text: String, var words: MutableList<LyricWordTiming>)
    private data class TimeDraft(val timeMs: Long, val sourceIndex: Int, val occurrence: Int, val sourceOrder: Int)
    private data class LinePrefix(val text: String, val times: List<Long>)
    private data class WordBoundary(val timeMs: Long, val textOffset: Int)
    private data class WordText(val text: String, val words: MutableList<LyricWordTiming>)

    private class Counters {
        var physicalLines = 0
        var invalidLineTimes = 0
        var invalidWordLines = 0
        var ambiguousWordLines = 0
        var invalidOffsets = 0
        var conflictingOffsets = 0
        var ignoredMetadataLines = 0
        var wordEvents = 0

        fun diagnostics() = LyricsDiagnostics(
            invalidLineTimestampCount = invalidLineTimes,
            invalidWordTimingLineCount = invalidWordLines,
            ambiguousWordTimingLineCount = ambiguousWordLines,
            invalidOffsetCount = invalidOffsets,
            conflictingOffsetCount = conflictingOffsets,
            ignoredMetadataLineCount = ignoredMetadataLines,
        )
    }

    private fun resolveEncoding(bytes: ByteArray, choice: LyricsEncodingChoice): ResolvedEncoding {
        fun begins(vararg values: Int): Boolean = bytes.size >= values.size && values.indices.all {
            (bytes[it].toInt() and 0xff) == values[it]
        }
        if (begins(0xff, 0xfe, 0, 0) || begins(0, 0, 0xfe, 0xff)) {
            throw LyricsParseException(LyricsParseIssue.UNSUPPORTED_ENCODING)
        }
        val bom = when {
            begins(0xef, 0xbb, 0xbf) -> LyricsEncoding.UTF8 to 3
            begins(0xff, 0xfe) -> LyricsEncoding.UTF16_LE to 2
            begins(0xfe, 0xff) -> LyricsEncoding.UTF16_BE to 2
            else -> null
        }
        val encoding = when (choice) {
            LyricsEncodingChoice.AUTO_BOM_OR_UTF8 -> bom?.first ?: LyricsEncoding.UTF8
            LyricsEncodingChoice.UTF8 -> LyricsEncoding.UTF8
            LyricsEncodingChoice.GB18030 -> LyricsEncoding.GB18030
        }
        if (bom != null && bom.first != encoding) throw LyricsParseException(LyricsParseIssue.ENCODING_MISMATCH)
        val charset = when (encoding) {
            LyricsEncoding.UTF8 -> Charsets.UTF_8
            LyricsEncoding.UTF16_LE -> Charsets.UTF_16LE
            LyricsEncoding.UTF16_BE -> Charsets.UTF_16BE
            LyricsEncoding.GB18030 -> try {
                Charset.forName("GB18030")
            } catch (error: UnsupportedCharsetException) {
                throw LyricsParseException(LyricsParseIssue.UNSUPPORTED_ENCODING, cause = error)
            }
        }
        return ResolvedEncoding(encoding, charset, bom?.second ?: 0)
    }

    private fun decode(bytes: ByteArray, encoding: ResolvedEncoding): String = try {
        encoding.charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, encoding.skip, bytes.size - encoding.skip)).toString()
    } catch (error: CharacterCodingException) {
        throw LyricsParseException(LyricsParseIssue.INVALID_ENCODING, cause = error)
    }

    /** Only whole metadata rows are directives; a lyric fragment cannot silently replace the offset. */
    private fun metadataValues(raw: String): List<String>? {
        val line = raw.trim()
        if (line.isEmpty()) return null
        var index = 0
        val offsets = ArrayList<String>()
        while (index < line.length) {
            if (line[index] != '[') return null
            val end = line.indexOf(']', index + 1)
            if (end < 0) return null
            val colon = line.indexOf(':', index + 1)
            if (colon < 0 || colon >= end) return null
            val key = line.substring(index + 1, colon).lowercase(Locale.ROOT)
            if (key !in METADATA_KEYS) return null
            if (key == "offset") offsets += line.substring(colon + 1, end)
            index = end + 1
            while (index < line.length && (line[index] == ' ' || line[index] == '\t')) index++
        }
        return offsets
    }

    private fun lineTimes(raw: String, counters: Counters): LinePrefix {
        var index = 0
        while (index < raw.length && (raw[index] == ' ' || raw[index] == '\t')) index++
        val times = ArrayList<Long>()
        var consumed = false
        while (index < raw.length && raw[index] == '[') {
            val end = raw.indexOf(']', index + 1)
            if (end < 0) {
                if (looksTimed(raw, index + 1, raw.length)) counters.invalidLineTimes++
                break
            }
            if (!looksTimed(raw, index + 1, end)) break
            val time = timestamp(raw, index + 1, end)
            if (time == null) counters.invalidLineTimes++ else times += time
            consumed = true
            index = end + 1
        }
        return LinePrefix(if (consumed) raw.substring(index) else raw, times)
    }

    private fun wordText(raw: String, lineTimes: List<Long>, counters: Counters, lineNumber: Int): WordText {
        val text = StringBuilder(raw.length)
        val boundaries = ArrayList<WordBoundary>()
        var sawTiming = false
        var invalid = false
        var index = 0
        while (index < raw.length) {
            if (raw[index] != '<') {
                text.append(raw[index++])
                continue
            }
            val end = raw.indexOf('>', index + 1)
            if (end < 0) {
                if (looksTimed(raw, index + 1, raw.length)) {
                    sawTiming = true
                    invalid = true
                }
                text.append(raw, index, raw.length)
                break
            }
            if (!looksTimed(raw, index + 1, end)) {
                text.append(raw, index, end + 1)
                index = end + 1
                continue
            }
            sawTiming = true
            val time = timestamp(raw, index + 1, end)
            if (time == null) invalid = true
            else {
                if (boundaries.lastOrNull()?.timeMs?.let { time <= it } == true) invalid = true
                boundaries += WordBoundary(time, text.length)
            }
            index = end + 1
        }
        val completeText = text.toString()
        val words = ArrayList<LyricWordTiming>()
        if (!sawTiming) return WordText(completeText, words)
        val lineStart = lineTimes.singleOrNull()
        if (lineStart != null && boundaries.any { it.timeMs < lineStart }) invalid = true
        if (invalid || lineTimes.isEmpty()) {
            counters.invalidWordLines++
            return WordText(completeText, words)
        }
        if (lineTimes.size != 1) {
            counters.ambiguousWordLines++
            return WordText(completeText, words)
        }
        for ((ordinal, boundary) in boundaries.withIndex()) {
            val next = boundaries.getOrNull(ordinal + 1)
            val endChar = next?.textOffset ?: completeText.length
            if (endChar == boundary.textOffset) continue
            if (counters.wordEvents == limits.maxWordEvents) {
                throw LyricsParseException(LyricsParseIssue.TOO_MANY_WORD_EVENTS, lineNumber)
            }
            counters.wordEvents++
            words += LyricWordTiming(boundary.timeMs, next?.timeMs, boundary.textOffset, endChar)
        }
        return WordText(completeText, words)
    }

    private suspend fun validateWordRanges(sources: List<SourceDraft>, times: List<TimeDraft>, counters: Counters) {
        var groupStart = 0
        var inspected = 0
        while (groupStart < times.size) {
            currentCoroutineContext().ensureActive()
            var groupEnd = groupStart + 1
            while (groupEnd < times.size && times[groupEnd].timeMs == times[groupStart].timeMs) groupEnd++
            val nextTime = times.getOrNull(groupEnd)?.timeMs
            for (index in groupStart until groupEnd) {
                currentCoroutineContext().ensureActive()
                val source = sources[times[index].sourceIndex]
                if (nextTime != null && source.words.any { it.startsAtMs >= nextTime || it.endsAtMs?.let { end -> end > nextTime } == true }) {
                    source.words = ArrayList()
                    counters.invalidWordLines++
                }
                if (++inspected % BATCH_SIZE == 0) yield()
            }
            groupStart = groupEnd
        }
    }

    private suspend fun digest(bytes: ByteArray, encoding: LyricsEncoding): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(("lyrics-v1\u0000" + encoding.name + "\u0000").toByteArray(Charsets.UTF_8))
        var start = 0
        while (start < bytes.size) {
            currentCoroutineContext().ensureActive()
            val count = minOf(65_536, bytes.size - start)
            digest.update(bytes, start, count)
            start += count
        }
        return "lyrics-v1-" + digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Numeric/sign/colon-led tags are attempted times; other bracketed text stays literal. */
    private fun looksTimed(text: String, start: Int, end: Int): Boolean = start < end &&
        (text[start] in '0'..'9' || text[start] == '-' || text[start] == '+' || text[start] == ':')

    /** M:S or M:SS, optionally with one to three decimal digits; multiplication is checked. */
    private fun timestamp(text: String, start: Int, end: Int): Long? {
        val colon = text.indexOf(':', start)
        if (colon <= start || colon >= end) return null
        var minutes = 0L
        for (index in start until colon) {
            val character = text[index]
            if (character !in '0'..'9') return null
            val digit = character - '0'
            if (minutes > (Long.MAX_VALUE - digit) / 10L) return null
            minutes = minutes * 10L + digit
        }
        val dot = text.indexOf('.', colon + 1).takeIf { it in (colon + 1) until end }
        val secondsEnd = dot ?: end
        if (secondsEnd - colon - 1 !in 1..2) return null
        var seconds = 0
        for (index in colon + 1 until secondsEnd) {
            if (text[index] !in '0'..'9') return null
            seconds = seconds * 10 + (text[index] - '0')
        }
        if (seconds >= 60) return null
        var fraction = 0
        if (dot != null) {
            val length = end - dot - 1
            if (length !in 1..3) return null
            for (index in dot + 1 until end) {
                if (text[index] !in '0'..'9') return null
                fraction = fraction * 10 + (text[index] - '0')
            }
            fraction *= when (length) {
                1 -> 100
                2 -> 10
                else -> 1
            }
        }
        val tail = seconds * 1_000L + fraction
        if (minutes > (Long.MAX_VALUE - tail) / 60_000L) return null
        return minutes * 60_000L + tail
    }

    private companion object {
        const val BATCH_SIZE = 100
        val METADATA_KEYS = setOf("ar", "ti", "al", "au", "by", "re", "ve", "length", "offset")
    }
}
