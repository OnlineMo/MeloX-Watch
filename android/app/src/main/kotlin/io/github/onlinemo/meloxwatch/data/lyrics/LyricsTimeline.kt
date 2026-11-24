package io.github.onlinemo.meloxwatch.data.lyrics

@ConsistentCopyVisibility
data class LyricsPosition internal constructor(
    val documentId: String,
    val lyricTimeMs: Long,
    val firstActiveIndex: Int,
    val activeEndExclusive: Int,
) {
    val anchorIndex: Int get() = firstActiveIndex
    fun isActive(index: Int): Boolean = index in firstActiveIndex until activeEndExclusive
}

@ConsistentCopyVisibility
data class LyricsWordPosition internal constructor(
    val lineId: LyricsLineId,
    val wordIndex: Int,
    val timing: LyricWordTiming,
    /** No explicit end boundary means no synthetic animation fraction. Static emphasis still works. */
    val progress: Double?,
)

/** This is preview data only; the caller must bind the current session/source and confirm playback. */
@ConsistentCopyVisibility
data class LyricsSeekTarget internal constructor(
    val documentId: String,
    val lineId: LyricsLineId,
    val mediaPositionMs: Long,
)

/** Immutable, source-order-stable time groups; a query returns indices instead of copying a group. */
class LyricsTimeline internal constructor(
    val documentId: String,
    val sourceOffsetMs: Long,
    private val lines: List<LyricsTimedLine>,
) {
    val size: Int get() = lines.size

    fun line(index: Int): LyricsTimedLine = lines[index]

    fun position(mediaPositionMs: Long, mapping: LyricsClockMapping = LyricsClockMapping()): LyricsPosition? {
        val time = mapping.lyricTime(mediaPositionMs, sourceOffsetMs) ?: return null
        val end = upperBound(time)
        if (end == 0) return null
        val start = lowerBound(lines[end - 1].startsAtMs)
        return LyricsPosition(documentId, time, start, end)
    }

    fun word(index: Int, position: LyricsPosition, enabled: Boolean = true): LyricsWordPosition? {
        if (!enabled || position.documentId != documentId || !position.isActive(index) || index !in lines.indices) return null
        val line = lines[index]
        val words = line.wordTimings
        var low = 0
        var high = words.size
        while (low < high) {
            val middle = low + (high - low) / 2
            if (words[middle].startsAtMs <= position.lyricTimeMs) low = middle + 1 else high = middle
        }
        if (low == 0) return null
        val wordIndex = low - 1
        val timing = words[wordIndex]
        val end = timing.endsAtMs
        if (end != null && position.lyricTimeMs >= end) return null
        val progress = end?.let {
            ((position.lyricTimeMs - timing.startsAtMs).toDouble() / (it - timing.startsAtMs).toDouble()).coerceIn(0.0, 1.0)
        }
        return LyricsWordPosition(line.id, wordIndex, timing, progress)
    }

    fun seekTarget(index: Int, mediaDurationMs: Long, mapping: LyricsClockMapping = LyricsClockMapping()): LyricsSeekTarget? {
        if (index !in lines.indices || mediaDurationMs <= 0L) return null
        val line = lines[index]
        val target = mapping.mediaTime(line.startsAtMs, sourceOffsetMs) ?: return null
        // Out-of-range lines are disabled; silently clamping a different point would misstate intent.
        if (target < 0L || target >= mediaDurationMs) return null
        return LyricsSeekTarget(documentId, line.id, target)
    }

    internal fun lowerBound(time: Long): Int {
        var low = 0
        var high = lines.size
        while (low < high) {
            val middle = low + (high - low) / 2
            if (lines[middle].startsAtMs < time) low = middle + 1 else high = middle
        }
        return low
    }

    internal fun upperBound(time: Long): Int {
        var low = 0
        var high = lines.size
        while (low < high) {
            val middle = low + (high - low) / 2
            if (lines[middle].startsAtMs <= time) low = middle + 1 else high = middle
        }
        return low
    }
}
