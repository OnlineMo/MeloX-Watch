package io.github.onlinemo.meloxwatch.ui.fonts

import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontException
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontIssue
import io.github.onlinemo.meloxwatch.data.fonts.LyricsFontLimits
import io.github.onlinemo.meloxwatch.data.lyrics.LyricsLimits
import io.github.onlinemo.meloxwatch.ui.lyrics.LyricsPageContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * All source text plus exactly matched translation text, once per acquired document identity.
 * Repeated timestamps reuse source lines rather than multiplying the coverage budget. Media
 * position, word ticks, offsets and the currently scrolled page are deliberately not inputs.
 */
suspend fun lyricsFontCoverage(
    ready: LyricsPageContent.Ready,
    visibleNow: () -> Boolean,
): LyricsFontPreviewText = lyricsFontCoverage(ready, visibleNow, LyricsFontLimits.TEXT_CHARACTERS)

internal suspend fun lyricsFontCoverage(
    ready: LyricsPageContent.Ready,
    visibleNow: () -> Boolean,
    limit: Int,
): LyricsFontPreviewText = withContext(Dispatchers.Default) {
    fontCoverageCheckpoint(visibleNow)
    val document = ready.document
    val translated = ready.translation
    if (document.sourceLines.size > LyricsLimits.PHYSICAL_LINES ||
        document.timeline.size > LyricsLimits.TIMELINE_ENTRIES ||
        translated != null && (translated.primaryDocumentId != document.documentId ||
            translated.size != document.timeline.size)) invalidCoverage()
    val original = FontCoverageBuilder(limit)
    val translation = FontCoverageBuilder(limit)
    val originalSeen = BooleanArray(LyricsLimits.PHYSICAL_LINES + 1)
    for ((index, line) in document.sourceLines.withIndex()) {
        if (index % CHECK_BATCH == 0) fontCoverageCheckpoint(visibleNow)
        val number = line.sourceLineNumber
        if (number !in 1..LyricsLimits.PHYSICAL_LINES || originalSeen[number]) invalidCoverage()
        originalSeen[number] = true
        original.append(line.text)
    }
    if (translated != null) {
        val seen = BooleanArray(LyricsLimits.PHYSICAL_LINES + 1)
        for (index in 0 until document.timeline.size) {
            if (index % CHECK_BATCH == 0) fontCoverageCheckpoint(visibleNow)
            val line = translated.line(index, document.documentId, enabled = true)?.source ?: continue
            val number = line.sourceLineNumber
            if (number !in 1..LyricsLimits.PHYSICAL_LINES) invalidCoverage()
            if (!seen[number]) {
                seen[number] = true
                translation.append(line.text)
            }
        }
    }
    fontCoverageCheckpoint(visibleNow)
    LyricsFontPreviewText(original.finish(), translation.finish())
}

private fun invalidCoverage(): Nothing = throw LyricsFontException(LyricsFontIssue.INVALID_INPUT)
private const val CHECK_BATCH = 128
