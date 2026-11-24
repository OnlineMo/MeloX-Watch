package io.github.onlinemo.meloxwatch.data.lyrics

import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

internal data class EmbeddedId3Lyrics(
    val language: String,
    val descriptor: String,
    val wireEncoding: EmbeddedLyricsWireEncoding,
    val contentSha256: String,
    val document: LyricsDocument,
)

/** USLT and SYLT text stays literal; only the explicit Vorbis LYRICS mapping invokes the LRC dialect. */
internal class EmbeddedLyricsDocumentBuilder(
    private val budget: EmbeddedLyricsBudget,
    private val context: CoroutineContext,
    private val parserDispatcher: CoroutineDispatcher,
) {
    private data class WordDraft(val time: Long, var endTime: Long?, val start: Int, var end: Int)
    private data class LineDraft(val number: Int, val time: Long, val text: String, val words: MutableList<WordDraft>)
    private class OpenLine(val number: Int, val time: Long) {
        val text = StringBuilder(0)
        val words = ArrayList<WordDraft>()
        var codePoints = 0
    }

    suspend fun uslt(bytes: ByteArray, version: Int): EmbeddedId3Lyrics {
        context.ensureActive()
        val cursor = EmbeddedLyricsCursor(bytes)
        val decoder = EmbeddedId3TextDecoder(cursor.byte(), version, budget) { context.ensureActive() }
        val language = decoder.language(cursor)
        val descriptor = decoder.terminated(cursor, descriptor = true)
        val text = decoder.body(cursor)
        val hash = contentHash(EmbeddedLyricsField.ID3_USLT, bytes)
        return EmbeddedId3Lyrics(language, descriptor, decoder.wireEncoding, hash, plainText(text, "embedded-lyrics-v1-$hash"))
    }

    suspend fun sylt(bytes: ByteArray, version: Int): EmbeddedId3Lyrics {
        context.ensureActive()
        val cursor = EmbeddedLyricsCursor(bytes)
        val decoder = EmbeddedId3TextDecoder(cursor.byte(), version, budget) { context.ensureActive() }
        val language = decoder.language(cursor)
        if (cursor.byte() != 2) embeddedLyricsFailure(EmbeddedLyricsIssue.UNSUPPORTED_TIMESTAMP_FORMAT)
        if (cursor.byte() != 1) embeddedLyricsFailure(EmbeddedLyricsIssue.UNSUPPORTED_SYLT_CONTENT)
        val descriptor = decoder.terminated(cursor, descriptor = true)
        val lines = SyltLines()
        var events = 0
        while (cursor.remaining != 0) {
            context.ensureActive()
            budget.syltEvent()
            val fragment = decoder.terminated(cursor)
            val timestamp = cursor.uint32()
            lines.event(fragment, timestamp)
            if (++events % 64 == 0) yield()
        }
        val hash = contentHash(EmbeddedLyricsField.ID3_SYLT, bytes)
        return EmbeddedId3Lyrics(language, descriptor, decoder.wireEncoding, hash, lines.document("embedded-lyrics-v1-$hash"))
    }

    suspend fun vorbis(bytes: ByteArray): Pair<String, LyricsDocument> {
        context.ensureActive()
        val parserLimits = budget.textParserLimits(bytes.size)
        val document = try {
            LocalLyricsParser(parserLimits, parserDispatcher).parse(bytes, LyricsEncodingChoice.UTF8)
        } catch (error: LyricsParseException) {
            context.ensureActive()
            // A failed frozen parser does not expose its private partial resident count. End this
            // metadata acquisition rather than giving another candidate a fresh allowance.
            budget.textParserFailed()
            throw EmbeddedLyricsReadException(error.issue.toEmbeddedIssue())
        }
        context.ensureActive()
        budget.parsedTextDocument(document, bytes.size) { context.ensureActive() }
        return contentHash(EmbeddedLyricsField.VORBIS_LYRICS, bytes) to document
    }

    private suspend fun plainText(text: String, documentId: String): LyricsDocument {
        budget.charge(1_024L)
        val sourceLines = ArrayList<LyricsTextLine>()
        var resident = 1_024L
        var start = 0
        while (start < text.length) {
            context.ensureActive()
            var end = start
            var points = 0
            while (end < text.length && text[end] != '\r' && text[end] != '\n') {
                if ((end - start) % 256 == 0) context.ensureActive()
                end += if (Character.isHighSurrogate(text[end]) && end + 1 < text.length &&
                    Character.isLowSurrogate(text[end + 1])) 2 else 1
                if (++points > budget.limits.lyrics.maxLineCodePoints) embeddedLyricsFailure(EmbeddedLyricsIssue.LINE_TOO_LONG)
            }
            budget.line(synchronized = false)
            budget.scratch(end - start)
            budget.charge((end - start).toLong() * 2L)
            sourceLines += LyricsTextLine(sourceLines.size + 1, text.substring(start, end), emptyList())
            resident += 192L + (end - start).toLong() * 2L
            start = if (end == text.length) end else if (text[end] == '\r' &&
                end + 1 < text.length && text[end + 1] == '\n') end + 2 else end + 1
            if (sourceLines.size % 64 == 0) yield()
        }
        context.ensureActive()
        return LyricsDocument(
            documentId, LyricsEncoding.UTF8, 0L, immutableLyricsList(sourceLines),
            LyricsTimeline(documentId, 0L, emptyList()), LyricsDiagnostics(), sourceLines.size, resident,
        )
    }

    /**
     * Each timestamp belongs to a real fragment. A leading separator marks a new line, so the
     * first such marker creates no artificial empty first row. Consecutive markers keep empty
     * rows; a final marker alone creates no extra row. CRLF is one separator, including when
     * split across fragments. A marker's real time can precede the next fragment in that row.
     */
    private inner class SyltLines {
        private val sources = ArrayList<LineDraft>()
        private val pendingWords = ArrayList<WordDraft>()
        private var open: OpenLine? = null
        private var pendingLineTime: Long? = null
        private var previousEventTime: Long? = null
        private var previousWasCr = false
        private var resident = 1_024L

        fun event(fragment: String, time: Long) {
            val previous = previousEventTime
            if (previous != null && time < previous) embeddedLyricsFailure(EmbeddedLyricsIssue.INVALID_TIMESTAMP)
            if (previous != null && time > previous) {
                for ((index, word) in pendingWords.withIndex()) {
                    if (index % 64 == 0) context.ensureActive()
                    word.endTime = time
                }
                pendingWords.clear()
            }
            previousEventTime = time
            var index = 0
            var runStart = 0
            while (index < fragment.length) {
                if (index % 256 == 0) context.ensureActive()
                val character = fragment[index]
                if (previousWasCr && character == '\n') {
                    previousWasCr = false
                    index++
                    runStart = index
                    continue
                }
                previousWasCr = false
                if (character == '\r' || character == '\n') {
                    append(fragment, runStart, index, time)
                    separator(time)
                    previousWasCr = character == '\r'
                    index++
                    runStart = index
                } else {
                    index += if (Character.isHighSurrogate(character) && index + 1 < fragment.length &&
                        Character.isLowSurrogate(fragment[index + 1])) 2 else 1
                }
            }
            append(fragment, runStart, fragment.length, time)
        }

        private fun append(fragment: String, start: Int, end: Int, time: Long) {
            if (start == end) return
            val line = open ?: run {
                budget.line(synchronized = true)
                OpenLine(sources.size + 1, pendingLineTime ?: time).also {
                    open = it
                    pendingLineTime = null
                }
            }
            var index = start
            var points = line.codePoints
            while (index < end) {
                if ((index - start) % 256 == 0) context.ensureActive()
                index += if (Character.isHighSurrogate(fragment[index]) && index + 1 < end &&
                    Character.isLowSurrogate(fragment[index + 1])) 2 else 1
                if (++points > budget.limits.lyrics.maxLineCodePoints) embeddedLyricsFailure(EmbeddedLyricsIssue.LINE_TOO_LONG)
            }
            val textStart = line.text.length
            budget.scratch(textStart + end - start)
            line.text.append(fragment, start, end)
            line.codePoints = points
            val prior = line.words.lastOrNull()
            if (prior != null && prior.time == time && prior.end == textStart) {
                // Equal-time contiguous events identify one real fragment group, not zero-length durations.
                prior.end = line.text.length
            } else {
                budget.word()
                val word = WordDraft(time, null, textStart, line.text.length)
                line.words += word
                pendingWords += word
            }
        }

        private fun separator(time: Long) {
            val markerTime = pendingLineTime
            if (open != null) closeLine()
            else if (markerTime != null) {
                budget.line(synchronized = true)
                sources += LineDraft(sources.size + 1, markerTime, "", ArrayList())
                resident += 352L
            }
            pendingLineTime = time
        }

        private fun closeLine() {
            val line = open ?: return
            budget.charge(line.text.length.toLong() * 2L)
            sources += LineDraft(line.number, line.time, line.text.toString(), line.words)
            resident += 352L + line.text.length.toLong() * 2L + line.words.size.toLong() * 80L
            open = null
        }

        suspend fun document(documentId: String): LyricsDocument {
            closeLine()
            budget.charge(1_024L)
            val textLines = ArrayList<LyricsTextLine>(sources.size)
            val timedLines = ArrayList<LyricsTimedLine>(sources.size)
            for ((index, source) in sources.withIndex()) {
                context.ensureActive()
                val words = ArrayList<LyricWordTiming>(source.words.size)
                for ((wordIndex, word) in source.words.withIndex()) {
                    if (wordIndex % 64 == 0) context.ensureActive()
                    words += LyricWordTiming(word.time, word.endTime, word.start, word.end)
                }
                val line = LyricsTextLine(source.number, source.text, immutableLyricsList(words))
                textLines += line
                timedLines += LyricsTimedLine(LyricsLineId(source.number, 0), source.time, line)
                if (index % 64 == 0) yield()
            }
            context.ensureActive()
            return LyricsDocument(
                documentId, LyricsEncoding.UTF8, 0L, immutableLyricsList(textLines),
                LyricsTimeline(documentId, 0L, immutableLyricsList(timedLines)), LyricsDiagnostics(), sources.size, resident,
            )
        }
    }

    /** Wire encoding lives on the source; direct ID3 documents contain normalized Unicode text. */
    private suspend fun contentHash(field: EmbeddedLyricsField, bytes: ByteArray): String {
        budget.charge(1_024L)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(("embedded-lyrics-v1\u0000${field.name}\u0000").toByteArray(Charsets.UTF_8))
        var offset = 0
        while (offset < bytes.size) {
            context.ensureActive()
            val count = minOf(65_536, bytes.size - offset)
            digest.update(bytes, offset, count)
            offset += count
        }
        context.ensureActive()
        val hash = digest.digest()
        val alphabet = "0123456789abcdef"
        val hex = CharArray(hash.size * 2)
        for (index in hash.indices) {
            val value = hash[index].toInt() and 0xff
            hex[index * 2] = alphabet[value ushr 4]
            hex[index * 2 + 1] = alphabet[value and 0x0f]
        }
        return String(hex)
    }
}
