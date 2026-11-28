package io.github.onlinemo.meloxwatch.data.metadata

import io.github.onlinemo.meloxwatch.data.lyrics.LyricsDocument
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsKind
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/** Stores small offsets into the parsed source lines; only the selected page becomes a String. */
internal class MetadataLyricsPages private constructor(private val document: LyricsDocument, private val starts: List<Cursor>) {
    private data class Cursor(val line: Int, val offset: Int)
    val count: Int get() = starts.size
    val accountedBytes: Long get() = starts.size.toLong() * 64L + PAGE_UNITS * 4L + 1_024L

    fun text(page: Int): String {
        require(page in starts.indices)
        val builder = StringBuilder(PAGE_UNITS)
        var line = starts[page].line
        var offset = starts[page].offset
        val end = starts.getOrNull(page + 1) ?: Cursor(document.sourceLines.size, 0)
        while (line < document.sourceLines.size && (line < end.line || line == end.line && offset < end.offset)) {
            val source = document.sourceLines[line].text
            val span = source.length + if (line + 1 < document.sourceLines.size) 1 else 0
            val stop = if (line == end.line) end.offset else span
            if (offset < minOf(stop, source.length)) builder.append(source, offset, minOf(stop, source.length))
            if (offset <= source.length && stop > source.length) builder.append('\n')
            offset = stop
            if (offset == span && line < end.line) {
                line++
                offset = 0
            }
        }
        check(builder.length <= PAGE_UNITS)
        return builder.toString()
    }

    companion object {
        const val PAGE_UNITS = 2_048

        suspend fun create(document: LyricsDocument, maxAccountedBytes: Long): MetadataLyricsPages {
            if (document.kind == LyricsKind.EMPTY) throw MetadataLookupException(MetadataLookupIssue.NO_LYRICS)
            val starts = ArrayList<Cursor>()
            starts += Cursor(0, 0)
            var remaining = PAGE_UNITS
            for ((line, source) in document.sourceLines.withIndex()) {
                currentCoroutineContext().ensureActive()
                var offset = 0
                while (offset < source.text.length) {
                    if (remaining == 0) {
                        starts += Cursor(line, offset)
                        remaining = PAGE_UNITS
                    }
                    var stop = minOf(source.text.length, offset + remaining)
                    if (stop < source.text.length && stop > offset && Character.isHighSurrogate(source.text[stop - 1]) &&
                        Character.isLowSurrogate(source.text[stop])) stop--
                    if (stop == offset) {
                        starts += Cursor(line, offset)
                        remaining = PAGE_UNITS
                        continue
                    }
                    remaining -= stop - offset
                    offset = stop
                    if (starts.size.toLong() * 64L + PAGE_UNITS * 4L + 1_024L > maxAccountedBytes) {
                        throw MetadataStoreException(MetadataStoreIssue.LOW_MEMORY)
                    }
                }
                if (line + 1 < document.sourceLines.size) {
                    if (remaining == 0) {
                        starts += Cursor(line, source.text.length)
                        remaining = PAGE_UNITS
                    }
                    remaining--
                }
                if (line % 100 == 0) yield()
            }
            val result = MetadataLyricsPages(document, starts)
            if (result.accountedBytes > maxAccountedBytes) throw MetadataStoreException(MetadataStoreIssue.LOW_MEMORY)
            return result
        }
    }
}
