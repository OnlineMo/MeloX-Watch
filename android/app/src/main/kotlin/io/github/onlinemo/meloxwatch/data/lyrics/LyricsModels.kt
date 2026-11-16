package io.github.onlinemo.meloxwatch.data.lyrics

import java.util.Collections

/** Frozen LP-07/14 input limits; test instances may tighten them, never silently widen them. */
data class LyricsLimits(
    val maxInputBytes: Int = INPUT_BYTES,
    val maxPhysicalLines: Int = PHYSICAL_LINES,
    val maxTimelineEntries: Int = TIMELINE_ENTRIES,
    val maxWordEvents: Int = WORD_EVENTS,
    val maxLineCodePoints: Int = LINE_CODE_POINTS,
    val maxAccountedBytes: Long = ACCOUNTED_BYTES,
) {
    init {
        require(maxInputBytes in 1..INPUT_BYTES && maxPhysicalLines in 1..PHYSICAL_LINES)
        require(maxTimelineEntries in 1..TIMELINE_ENTRIES && maxWordEvents in 1..WORD_EVENTS)
        require(maxLineCodePoints in 1..LINE_CODE_POINTS && maxAccountedBytes in 1L..ACCOUNTED_BYTES)
    }

    companion object {
        const val INPUT_BYTES = 1_048_576
        const val PHYSICAL_LINES = 10_000
        const val TIMELINE_ENTRIES = 10_000
        const val WORD_EVENTS = 100_000
        const val LINE_CODE_POINTS = 8_192
        const val ACCOUNTED_BYTES = 8_388_608L
    }
}

enum class LyricsKind { EMPTY, PLAIN_TEXT, SYNCHRONIZED }
enum class LyricsEncoding { UTF8, UTF16_LE, UTF16_BE, GB18030 }
enum class LyricsEncodingChoice { AUTO_BOM_OR_UTF8, UTF8, GB18030 }

enum class LyricsParseIssue {
    INPUT_TOO_LARGE, TOO_MANY_LINES, TOO_MANY_TIMED_LINES, TOO_MANY_WORD_EVENTS,
    LINE_TOO_LONG, MEMORY_LIMIT, INVALID_ENCODING, ENCODING_MISMATCH, UNSUPPORTED_ENCODING, INVALID_TEXT,
}

/** Contains a bounded issue and optional line number, never input text, paths or credentials. */
class LyricsParseException(
    val issue: LyricsParseIssue,
    val sourceLineNumber: Int? = null,
    cause: Throwable? = null,
) : Exception(issue.name, cause)

data class LyricsDiagnostics(
    val invalidLineTimestampCount: Int = 0,
    val invalidWordTimingLineCount: Int = 0,
    val ambiguousWordTimingLineCount: Int = 0,
    val invalidOffsetCount: Int = 0,
    val conflictingOffsetCount: Int = 0,
    val ignoredMetadataLineCount: Int = 0,
) {
    val hasWarnings: Boolean get() = invalidLineTimestampCount != 0 || invalidWordTimingLineCount != 0 ||
        ambiguousWordTimingLineCount != 0 || invalidOffsetCount != 0 || conflictingOffsetCount != 0
}

/** IDs stay in source order within a document; its digest supplies the separate content identity. */
data class LyricsLineId(val sourceLineNumber: Int, val timestampOccurrence: Int) {
    init {
        require(sourceLineNumber in 1..LyricsLimits.PHYSICAL_LINES)
        require(timestampOccurrence in 0 until LyricsLimits.TIMELINE_ENTRIES)
    }
}

/** Character ranges are UTF-16 boundaries of complete text fragments, never invented per-letter times. */
data class LyricWordTiming internal constructor(
    val startsAtMs: Long,
    val endsAtMs: Long?,
    val startChar: Int,
    val endChar: Int,
) {
    init {
        require(startsAtMs >= 0L && (endsAtMs == null || endsAtMs > startsAtMs))
        require(startChar >= 0 && endChar > startChar)
    }
}

class LyricsTextLine internal constructor(
    val sourceLineNumber: Int,
    val text: String,
    val wordTimings: List<LyricWordTiming>,
)

class LyricsTimedLine internal constructor(
    val id: LyricsLineId,
    val startsAtMs: Long,
    val source: LyricsTextLine,
) {
    val text: String get() = source.text
    val wordTimings: List<LyricWordTiming> get() = source.wordTimings
}

class LyricsDocument internal constructor(
    val documentId: String,
    val encoding: LyricsEncoding,
    val sourceOffsetMs: Long,
    val sourceLines: List<LyricsTextLine>,
    val timeline: LyricsTimeline,
    val diagnostics: LyricsDiagnostics,
    val physicalLineCount: Int,
    val accountedResidentBytes: Long,
) {
    val schemaVersion: Int = 1
    val kind: LyricsKind = when {
        timeline.size != 0 -> LyricsKind.SYNCHRONIZED
        sourceLines.any { it.text.isNotBlank() } -> LyricsKind.PLAIN_TEXT
        else -> LyricsKind.EMPTY
    }
    val hasWordTimings: Boolean = sourceLines.any { it.wordTimings.isNotEmpty() }
}

/** A positive user delay displays later. Source [offset] has its own independent convention. */
data class LyricsUserOffset(val delayMs: Int = 0) {
    init { require(delayMs in -MAX_DELAY_MS..MAX_DELAY_MS && delayMs % STEP_MS == 0) }

    fun plusSteps(steps: Int): LyricsUserOffset = LyricsUserOffset(
        (delayMs.toLong() + steps.toLong() * STEP_MS).coerceIn(-MAX_DELAY_MS.toLong(), MAX_DELAY_MS.toLong()).toInt(),
    )

    companion object {
        const val STEP_MS = 100
        const val MAX_DELAY_MS = 10_000
    }
}

/**
 * The supplied media position already includes playback speed. timelineOriginMs maps a segment's
 * relative media clock to whole-file lyric times when that is the explicitly selected CUE source.
 * This LRC dialect uses positive source offset to advance, and positive user delay to delay.
 */
data class LyricsClockMapping(
    val timelineOriginMs: Long = 0L,
    val userOffset: LyricsUserOffset = LyricsUserOffset(),
) {
    init { require(timelineOriginMs >= 0L) }

    fun lyricTime(mediaPositionMs: Long, sourceOffsetMs: Long): Long? {
        if (mediaPositionMs < 0L) return null
        val absolute = lyricsAdd(mediaPositionMs, timelineOriginMs) ?: return null
        val adjusted = lyricsAdd(absolute, sourceOffsetMs) ?: return null
        return lyricsSubtract(adjusted, userOffset.delayMs.toLong())
    }

    fun mediaTime(lyricTimeMs: Long, sourceOffsetMs: Long): Long? {
        val normalized = lyricsSubtract(lyricTimeMs, sourceOffsetMs) ?: return null
        val delayed = lyricsAdd(normalized, userOffset.delayMs.toLong()) ?: return null
        return lyricsSubtract(delayed, timelineOriginMs)
    }
}

internal fun lyricsAdd(left: Long, right: Long): Long? {
    if ((right > 0L && left > Long.MAX_VALUE - right) || (right < 0L && left < Long.MIN_VALUE - right)) return null
    return left + right
}

internal fun lyricsSubtract(left: Long, right: Long): Long? {
    if ((right > 0L && left < Long.MIN_VALUE + right) || (right < 0L && left > Long.MAX_VALUE + right)) return null
    return left - right
}

/** The caller transfers a finished, privately owned list; the wrapper exposes no mutable alias. */
internal fun <T> immutableLyricsList(owned: MutableList<T>): List<T> = Collections.unmodifiableList(owned)

/**
 * Conservative representation accounting, including parser input/decoder work and current-line
 * scratch. Constants allow object/list overhead rather than counting only UTF-8 payload bytes.
 * Actual heap/PSS and physical allocation timing still require cloud/device measurement.
 */
internal class LyricsMemoryBudget(private val limits: LyricsLimits, private val parserWorkingBytes: Long) {
    var residentBytes = 1_024L
        private set
    private var currentLineScratchBytes = 0L

    fun checkScratch(utf16Units: Int, line: Int) {
        currentLineScratchBytes = utf16Units.toLong() * 64L
        check(line)
    }

    fun releaseScratch() { currentLineScratchBytes = 0L }

    fun retainText(utf16Units: Int, wordEvents: Int, line: Int) {
        residentBytes += 192L + utf16Units.toLong() * 2L + wordEvents.toLong() * 80L
        check(line)
    }

    fun retainTimedEntry(line: Int) {
        residentBytes += 160L
        check(line)
    }

    fun check(line: Int? = null) {
        if (residentBytes + parserWorkingBytes + currentLineScratchBytes > limits.maxAccountedBytes) {
            throw LyricsParseException(LyricsParseIssue.MEMORY_LIMIT, line)
        }
    }
}
