package io.github.onlinemo.meloxwatch.playback

import io.github.onlinemo.meloxwatch.data.listening.ListeningDayAccumulation
import io.github.onlinemo.meloxwatch.data.listening.ListeningEpochs
import io.github.onlinemo.meloxwatch.data.listening.ListeningException
import io.github.onlinemo.meloxwatch.data.listening.ListeningIssue
import io.github.onlinemo.meloxwatch.data.listening.ListeningLimits
import io.github.onlinemo.meloxwatch.data.listening.ListeningRoundUpdate
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.min

internal data class ListeningSample(
    val epochMs: Long,
    val elapsedMs: Long,
    val zoneId: String,
    val positionMs: Long,
    val advancing: Boolean,
    val speed: Float = 1f,
    val durationMs: Long? = null,
)

/** Only observed advancing intervals count. Unobserved gaps and discontinuities establish a new baseline. */
internal class ListeningProgressRound(
    private val roundId: String,
    private val trackKey: TrackKey,
    private val displayName: String?,
    private val epochs: ListeningEpochs,
    initialDurationMs: Long?,
) {
    private var baseline: ListeningSample? = null
    private var firstProgress: ListeningSample? = null
    private var latest: ListeningSample? = null
    private var knownDuration = initialDurationMs?.takeIf { it > 0L }
    private var acknowledgedDuration: Long? = null
    private var acceptedMs = 0L
    private var totalMs = 0L
    private var nextOrdinal = 0L
    private var openSegment: Segment? = null
    private val segments = linkedMapOf<Long, Segment>()
    val hasProgress: Boolean get() = totalMs > 0L
    val baselineSpeed: Float? get() = baseline?.speed

    val hasUnacknowledgedProgress: Boolean get() = totalMs > 0L &&
        (totalMs > acceptedMs || knownDuration != acknowledgedDuration)

    fun reset(sample: ListeningSample?) {
        baseline = sample
        if (sample != null) observe(sample)
    }

    fun sample(current: ListeningSample): Boolean {
        val previous = baseline
        baseline = current
        observe(current)
        if (previous == null || !previous.advancing || previous.speed <= 0f || !previous.speed.isFinite()) return false
        val elapsed = current.elapsedMs - previous.elapsedMs
        val advance = current.positionMs - previous.positionMs
        if (elapsed !in 1L..MAX_OBSERVED_GAP_MS || advance <= 0L || current.speed != previous.speed) return false
        // A missing seek callback must still never turn a position jump into listening time.
        if (advance.toDouble() > elapsed.toDouble() * previous.speed + POSITION_TOLERANCE_MS) return false
        val effective = min(elapsed, (advance.toDouble() / previous.speed).toLong())
        if (effective <= 0L) return false
        val parts = dateParts(previous, current, effective)
        var simulatedDate = openSegment?.date
        var simulatedZone = openSegment?.zone
        var required = 0
        for (part in parts) {
            if (simulatedDate != part.date || simulatedZone != part.zone) required += 1
            simulatedDate = part.date
            simulatedZone = part.zone
        }
        if (segments.size + required > ListeningLimits.PAGE_SIZE) throw ListeningException(ListeningIssue.STORAGE_UNAVAILABLE)
        if (firstProgress == null) firstProgress = previous
        for (part in parts) {
            val open = openSegment
            val segment = if (open != null && open.date == part.date && open.zone == part.zone) open else {
                Segment(nextOrdinal++, part.date, part.zone).also { segments[it.ordinal] = it; openSegment = it }
            }
            segment.total = Math.addExact(segment.total, part.milliseconds)
            segment.dirty = true
        }
        totalMs = Math.addExact(totalMs, effective)
        return true
    }

    fun update(): ListeningRoundUpdate? {
        if (!hasUnacknowledgedProgress) return null
        val first = firstProgress ?: return null
        val last = latest ?: return null
        return ListeningRoundUpdate(roundId, trackKey, displayName, epochs,
            first.epochMs, first.elapsedMs, first.zoneId,
            last.epochMs, last.elapsedMs, last.zoneId, totalMs, knownDuration,
            segments.values.filter { it.dirty }.map { ListeningDayAccumulation(it.ordinal, it.date, it.zone, it.total) })
    }

    /** ACK the submitted snapshot, retaining any additional progress collected while the write suspended. */
    fun acknowledge(sent: ListeningRoundUpdate, acceptedListeningMs: Long) {
        require(sent.roundId == roundId && acceptedListeningMs >= sent.totalListeningMs && acceptedListeningMs <= totalMs)
        acceptedMs = maxOf(acceptedMs, acceptedListeningMs)
        acknowledgedDuration = sent.knownDurationMs
        for (part in sent.daySegments) {
            val current = segments[part.ordinal] ?: continue
            if (current.total == part.listeningMs) {
                if (current === openSegment) current.dirty = false else segments.remove(part.ordinal)
            }
        }
    }

    private fun observe(sample: ListeningSample) {
        latest = sample
        sample.durationMs?.takeIf { it > 0L }?.let { knownDuration = it }
    }

    private fun dateParts(before: ListeningSample, after: ListeningSample, milliseconds: Long): List<DatePart> {
        val wallDelta = after.epochMs - before.epochMs
        val elapsed = after.elapsedMs - before.elapsedMs
        if (before.zoneId != after.zoneId || wallDelta <= 0L || abs(wallDelta - elapsed) > CLOCK_TOLERANCE_MS) {
            // Manual clock/zone changes create an observed segment; they do not invent intervening days.
            return listOf(DatePart(localDate(after.epochMs, after.zoneId), after.zoneId, milliseconds))
        }
        val zone = ZoneId.of(before.zoneId)
        val firstDate = Instant.ofEpochMilli(before.epochMs).atZone(zone).toLocalDate()
        val boundary = firstDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        if (boundary >= after.epochMs || boundary <= before.epochMs) return listOf(DatePart(firstDate.toString(), before.zoneId, milliseconds))
        val firstMs = milliseconds * (boundary - before.epochMs) / wallDelta
        return buildList {
            if (firstMs > 0L) add(DatePart(firstDate.toString(), before.zoneId, firstMs))
            if (milliseconds > firstMs) add(DatePart(localDate(after.epochMs, after.zoneId), after.zoneId, milliseconds - firstMs))
        }
    }

    private fun localDate(epochMs: Long, zoneId: String): String = Instant.ofEpochMilli(epochMs).atZone(ZoneId.of(zoneId)).toLocalDate().toString()
    private class Segment(val ordinal: Long, val date: String, val zone: String, var total: Long = 0L, var dirty: Boolean = true)
    private data class DatePart(val date: String, val zone: String, val milliseconds: Long)

    companion object {
        const val MAX_OBSERVED_GAP_MS = 5000L
        private const val POSITION_TOLERANCE_MS = 100.0
        private const val CLOCK_TOLERANCE_MS = 2000L
    }
}
