package io.github.onlinemo.meloxwatch.playback

import io.github.onlinemo.meloxwatch.data.listening.ListeningEpochs
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningProgressRoundTest {
    private val roundId = "3aeaf752-46f2-46dc-aaca-b1a5f5e8e41"
    private val origin = Instant.parse("2026-11-15T12:00:00Z").toEpochMilli()
    private fun round() = ListeningProgressRound(roundId, TrackKey(TrackKey.LOCAL_PROVIDER, "track"),
        "Title", ListeningEpochs(historyEpoch = 2L, statisticsEpoch = 3L), null)
    private fun sample(elapsed: Long, position: Long = elapsed, playing: Boolean = true, speed: Float = 1f,
        epoch: Long = origin + elapsed, duration: Long? = null, zone: String = "UTC") =
        ListeningSample(epoch, elapsed, zone, position, playing, speed, duration)

    @Test fun preparingPausedAndBufferingPlayersNeverCreateHistory() {
        val round = round()
        round.sample(sample(0L, 0L, playing = false))
        round.sample(sample(1000L, 0L, playing = false))
        round.sample(sample(2000L, 1000L, playing = false))
        assertNull(round.update())
    }

    @Test fun firstActualAdvanceUsesTheBeginningOfTheObservedInterval() {
        val round = round()
        round.sample(sample(1000L, 7000L))
        assertFalse(round.hasProgress)
        round.sample(sample(2000L, 8000L))
        val update = requireNotNull(round.update())
        assertEquals(1000L, update.totalListeningMs)
        assertEquals(origin + 1000L, update.firstProgressEpochMs)
        assertEquals(1000L, update.firstProgressElapsedMs)
        assertEquals(ListeningEpochs(2L, 3L), update.epochs)
    }

    @Test fun pauseResumeExcludesPausedTimeAndKeepsTheSameRound() {
        val round = round()
        round.sample(sample(0L))
        round.sample(sample(1000L))
        round.sample(sample(1500L, playing = false))
        round.sample(sample(4500L, 1500L, playing = false))
        round.sample(sample(5000L, 1500L))
        round.sample(sample(6000L, 2500L))
        val update = requireNotNull(round.update())
        assertEquals(roundId, update.roundId)
        assertEquals(2500L, update.totalListeningMs)
    }

    @Test fun explicitSeekAndAnUnexpectedPositionJumpNeverCountSeekDistance() {
        val round = round()
        round.sample(sample(0L))
        round.sample(sample(1000L))
        round.reset(sample(1000L, 180000L))
        round.sample(sample(2000L, 181000L))
        round.sample(sample(3000L, 600000L))
        round.sample(sample(4000L, 601000L))
        assertEquals(3000L, requireNotNull(round.update()).totalListeningMs)
    }

    @Test fun playbackSpeedCountsElapsedListeningTime() {
        val round = round()
        round.sample(sample(0L, speed = 2f))
        round.sample(sample(1000L, 2000L, speed = 2f))
        round.sample(sample(2000L, 4000L, speed = 2f))
        assertEquals(2000L, requireNotNull(round.update()).totalListeningMs)
    }

    @Test fun aLongUnobservedGapCannotInventListeningTime() {
        val round = round()
        round.sample(sample(0L))
        round.sample(sample(120000L))
        assertNull(round.update())
        round.sample(sample(121000L))
        assertEquals(1000L, requireNotNull(round.update()).totalListeningMs)
    }

    @Test fun aContinuousIntervalAcrossMidnightSplitsWithoutLosingMilliseconds() {
        val round = round()
        val start = Instant.parse("2026-11-15T23:59:59.500Z").toEpochMilli()
        round.sample(sample(0L, epoch = start))
        round.sample(sample(1000L, epoch = start + 1000L))
        val update = requireNotNull(round.update())
        assertEquals(listOf("2026-11-15", "2026-11-16"), update.daySegments.map { it.localDate })
        assertEquals(listOf(500L, 500L), update.daySegments.map { it.listeningMs })
        assertEquals(update.totalListeningMs, update.daySegments.sumOf { it.listeningMs })
    }

    @Test fun aClockJumpUsesTheObservedDateWithoutInventingIntermediateDays() {
        val round = round()
        round.sample(sample(0L))
        round.sample(sample(1000L, epoch = origin + 30L * 86400000L))
        val update = requireNotNull(round.update())
        assertEquals(1000L, update.totalListeningMs)
        assertEquals(listOf("2026-12-15"), update.daySegments.map { it.localDate })
    }

    @Test fun aZoneChangeProducesARealSegmentWhileMonotonicTimeRemainsContinuous() {
        val round = round()
        round.sample(sample(0L))
        round.sample(sample(1000L))
        round.sample(sample(2000L, zone = "Asia/Shanghai"))
        val update = requireNotNull(round.update())
        assertEquals(listOf("UTC", "Asia/Shanghai"), update.daySegments.map { it.zoneId })
        assertEquals(2000L, update.daySegments.sumOf { it.listeningMs })
    }

    @Test fun acknowledgingAnOlderSnapshotRetainsAdvanceCollectedDuringTheWrite() {
        val round = round()
        round.sample(sample(0L))
        round.sample(sample(1000L))
        val first = requireNotNull(round.update())
        round.sample(sample(2000L))
        round.acknowledge(first, 1000L)
        val second = requireNotNull(round.update())
        assertEquals(2000L, second.totalListeningMs)
        assertEquals(2000L, second.daySegments.single().listeningMs)
        round.acknowledge(second, 2000L)
        assertNull(round.update())
    }

    @Test fun lateKnownDurationCanBeAcknowledgedWithoutDuplicatingTime() {
        val round = round()
        round.sample(sample(0L))
        round.sample(sample(1000L))
        val first = requireNotNull(round.update())
        round.acknowledge(first, 1000L)
        round.sample(sample(2000L, 1000L, playing = false, duration = 1500L))
        val durationUpdate = requireNotNull(round.update())
        assertEquals(1000L, durationUpdate.totalListeningMs)
        assertEquals(1500L, durationUpdate.knownDurationMs)
        assertTrue(durationUpdate.daySegments.isEmpty())
        round.acknowledge(durationUpdate, 1000L)
        assertNull(round.update())
    }
}
