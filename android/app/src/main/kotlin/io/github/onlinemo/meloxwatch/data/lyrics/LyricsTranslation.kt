package io.github.onlinemo.meloxwatch.data.lyrics

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

data class LyricsTranslationDiagnostics(
    val pairedLineCount: Int,
    val unmatchedPrimaryLineCount: Int,
    val unmatchedTranslationLineCount: Int,
    val ambiguousDuplicateGroupCount: Int,
    val unrepresentablePrimaryTimeCount: Int,
    val unrepresentableTranslationTimeCount: Int,
)

/** A private bounded index array references the two immutable documents without copying their text. */
class LyricsTranslation internal constructor(
    private val primary: LyricsDocument,
    private val translation: LyricsDocument,
    private val pairedIndices: IntArray,
    val diagnostics: LyricsTranslationDiagnostics,
    val accountedResidentBytes: Long,
) {
    val primaryDocumentId: String get() = primary.documentId
    val translationDocumentId: String get() = translation.documentId
    val size: Int get() = pairedIndices.size

    fun line(primaryIndex: Int, primaryDocumentId: String, enabled: Boolean = true): LyricsTimedLine? {
        if (!enabled || primaryDocumentId != this.primaryDocumentId || primaryIndex !in pairedIndices.indices) return null
        val translationIndex = pairedIndices[primaryIndex]
        return if (translationIndex < 0) null else translation.timeline.line(translationIndex)
    }
}

/**
 * Translation LRC is explicitly selected by the caller. Matching uses raw time minus each source
 * offset（同时减去各文档声明的时间线起点）。 Equal-time groups of equal size pair by
 * stable source ordinal; unequal groups remain wholly unmatched so duplicates cannot drift.
 * Track identity, source permission and association persistence remain with the acquisition owner.
 */
class LyricsTranslationMatcher internal constructor(
    private val limits: LyricsLimits,
    private val workDispatcher: CoroutineDispatcher,
) {
    constructor(limits: LyricsLimits = LyricsLimits()) : this(limits, Dispatchers.Default)

    suspend fun match(primary: LyricsDocument, translation: LyricsDocument, primaryTimelineOriginMs: Long = 0L,
        translationTimelineOriginMs: Long = 0L): LyricsTranslation = withContext(workDispatcher) {
        currentCoroutineContext().ensureActive()
        require(primaryTimelineOriginMs >= 0L && translationTimelineOriginMs >= 0L)
        if (primary.timeline.size > limits.maxTimelineEntries || translation.timeline.size > limits.maxTimelineEntries) {
            throw LyricsParseException(LyricsParseIssue.TOO_MANY_TIMED_LINES)
        }
        // Count both documents, even when the caller reuses one object. The fixed allowance covers
        // the array header, result/diagnostics objects and the few scalar coroutine working values.
        val accounted = primary.accountedResidentBytes + translation.accountedResidentBytes +
            RESULT_ALLOWANCE_BYTES + primary.timeline.size.toLong() * Int.SIZE_BYTES
        if (accounted > limits.maxAccountedBytes) throw LyricsParseException(LyricsParseIssue.MEMORY_LIMIT)
        val paired = IntArray(primary.timeline.size) { -1 }
        var primaryIndex = 0
        var translationIndex = 0
        var pairedCount = 0
        var ambiguousGroups = 0
        var invalidPrimary = 0
        var invalidTranslation = 0
        var visited = 0
        while (primaryIndex < primary.timeline.size || translationIndex < translation.timeline.size) {
            currentCoroutineContext().ensureActive()
            if (primaryIndex == primary.timeline.size) {
                if (normalizedTime(translation, translationIndex, translationTimelineOriginMs) == null) invalidTranslation++
                translationIndex++
            } else if (translationIndex == translation.timeline.size) {
                if (normalizedTime(primary, primaryIndex, primaryTimelineOriginMs) == null) invalidPrimary++
                primaryIndex++
            } else {
                val primaryTime = normalizedTime(primary, primaryIndex, primaryTimelineOriginMs)
                val translationTime = normalizedTime(translation, translationIndex, translationTimelineOriginMs)
                when {
                    primaryTime == null -> {
                        invalidPrimary++
                        primaryIndex++
                    }
                    translationTime == null -> {
                        invalidTranslation++
                        translationIndex++
                    }
                    primaryTime < translationTime -> primaryIndex++
                    primaryTime > translationTime -> translationIndex++
                    else -> {
                        val primaryEnd = groupEnd(primary.timeline, primaryIndex)
                        val translationEnd = groupEnd(translation.timeline, translationIndex)
                        val count = primaryEnd - primaryIndex
                        if (count == translationEnd - translationIndex) {
                            for (ordinal in 0 until count) {
                                currentCoroutineContext().ensureActive()
                                paired[primaryIndex + ordinal] = translationIndex + ordinal
                                if (++visited % BATCH_SIZE == 0) yield()
                            }
                            pairedCount += count
                        } else ambiguousGroups++
                        primaryIndex = primaryEnd
                        translationIndex = translationEnd
                    }
                }
            }
            if (++visited % BATCH_SIZE == 0) yield()
        }
        currentCoroutineContext().ensureActive()
        LyricsTranslation(
            primary = primary,
            translation = translation,
            pairedIndices = paired,
            diagnostics = LyricsTranslationDiagnostics(
                pairedLineCount = pairedCount,
                unmatchedPrimaryLineCount = primary.timeline.size - pairedCount,
                unmatchedTranslationLineCount = translation.timeline.size - pairedCount,
                ambiguousDuplicateGroupCount = ambiguousGroups,
                unrepresentablePrimaryTimeCount = invalidPrimary,
                unrepresentableTranslationTimeCount = invalidTranslation,
            ),
            accountedResidentBytes = accounted,
        )
    }

    private fun normalizedTime(document: LyricsDocument, index: Int, timelineOriginMs: Long): Long? =
        lyricsSubtract(document.timeline.line(index).startsAtMs, document.sourceOffsetMs)?.let { lyricsSubtract(it, timelineOriginMs) }

    private suspend fun groupEnd(timeline: LyricsTimeline, start: Int): Int {
        val time = timeline.line(start).startsAtMs
        var end = start + 1
        while (end < timeline.size && timeline.line(end).startsAtMs == time) {
            currentCoroutineContext().ensureActive()
            end++
            if ((end - start) % BATCH_SIZE == 0) yield()
        }
        return end
    }

    private companion object {
        const val BATCH_SIZE = 100
        const val RESULT_ALLOWANCE_BYTES = 512L
    }
}
