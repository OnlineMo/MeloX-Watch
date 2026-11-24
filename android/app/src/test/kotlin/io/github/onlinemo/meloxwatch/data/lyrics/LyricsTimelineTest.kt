package io.github.onlinemo.meloxwatch.data.lyrics

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTimelineTest {
    @Test fun equalTimeGroupsHaveOneStableAnchorAndHalfOpenActiveIndices() {
        runBlocking {
            val document = parseLyrics("[00:01]One\n[00:01]Two\n[00:02]Next")
            assertNull(document.timeline.position(999L))
            val first = requireNotNull(document.timeline.position(1_000L))
            assertEquals(0, first.firstActiveIndex)
            assertEquals(2, first.activeEndExclusive)
            assertEquals(0, first.anchorIndex)
            assertTrue(first.isActive(0))
            assertTrue(first.isActive(1))
            assertFalse(first.isActive(2))
            assertEquals(0, requireNotNull(document.timeline.position(1_999L)).anchorIndex)
            assertEquals(2, requireNotNull(document.timeline.position(2_000L)).anchorIndex)
        }
    }

    @Test fun reverseAndForwardSeeksQueryTheMediaClockWithoutRetainingAnOldActiveRow() {
        runBlocking {
            val document = parseLyrics("[00:01]One\n[00:02]Two\n[00:03]Three")
            assertEquals(2, requireNotNull(document.timeline.position(5_000L)).anchorIndex)
            assertEquals(0, requireNotNull(document.timeline.position(1_100L)).anchorIndex)
            assertEquals(1, requireNotNull(document.timeline.position(2_000L)).anchorIndex)
            assertNull(document.timeline.position(-1L))
        }
    }

    @Test fun positiveUserOffsetDelaysAndNegativeUserOffsetAdvancesDisplayAndSeekTogether() {
        runBlocking {
            val document = parseLyrics("[00:01]One")
            val delayed = LyricsClockMapping(userOffset = LyricsUserOffset(500))
            val advanced = LyricsClockMapping(userOffset = LyricsUserOffset(-500))
            assertNull(document.timeline.position(1_499L, delayed))
            assertEquals(1_000L, requireNotNull(document.timeline.position(1_500L, delayed)).lyricTimeMs)
            assertEquals(1_500L, requireNotNull(document.timeline.seekTarget(0, 5_000L, delayed)).mediaPositionMs)
            assertEquals(1_000L, requireNotNull(document.timeline.position(500L, advanced)).lyricTimeMs)
            assertEquals(500L, requireNotNull(document.timeline.seekTarget(0, 5_000L, advanced)).mediaPositionMs)
            assertEquals(0L, document.sourceOffsetMs)
        }
    }

    @Test fun sourceOffsetAdvancesIndependentlyAndDoesNotRewriteTheUserDelay() {
        runBlocking {
            val document = parseLyrics("[offset:500]\n[00:01]One")
            assertEquals(1_000L, requireNotNull(document.timeline.position(500L)).lyricTimeMs)
            assertEquals(500L, requireNotNull(document.timeline.seekTarget(0, 5_000L)).mediaPositionMs)
            val mapping = LyricsClockMapping(userOffset = LyricsUserOffset(500))
            assertEquals(1_000L, requireNotNull(document.timeline.position(1_000L, mapping)).lyricTimeMs)
            assertEquals(1_000L, requireNotNull(document.timeline.seekTarget(0, 5_000L, mapping)).mediaPositionMs)
            assertEquals(500, mapping.userOffset.delayMs)
            assertEquals(500L, document.sourceOffsetMs)
        }
    }

    @Test fun explicitWholeFileCueOriginMapsSegmentMediaTimeAndUsesTheExactInverseForSeek() {
        runBlocking {
            val wholeFile = parseLyrics("[offset:1000]\n[01:31]Whole file")
            val mapping = LyricsClockMapping(90_000L, LyricsUserOffset(500))
            assertEquals(91_000L, requireNotNull(wholeFile.timeline.position(500L, mapping)).lyricTimeMs)
            assertEquals(500L, requireNotNull(wholeFile.timeline.seekTarget(0, 20_000L, mapping)).mediaPositionMs)
            assertNull(wholeFile.timeline.position(500L))
            val segment = parseLyrics("[00:01]Segment")
            assertEquals(0, requireNotNull(segment.timeline.position(1_000L)).anchorIndex)
            assertEquals(1_000L, requireNotNull(segment.timeline.seekTarget(0, 20_000L)).mediaPositionMs)
        }
    }

    @Test fun theMediaPositionIsAlreadySpeedAdjustedAndIsNotMultipliedAgain() {
        runBlocking {
            val document = parseLyrics("[00:01]One\n[00:02]Two")
            // A backend at 2x has reported 2000ms of media time; the visual layer uses that value.
            assertEquals(1, requireNotNull(document.timeline.position(2_000L)).anchorIndex)
            assertEquals(2_000L, LyricsClockMapping().lyricTime(2_000L, 0L))
        }
    }

    @Test fun userOffsetStepsStayWithinTheFrozenRangeAndInvalidValuesCannotEnterTheModel() {
        assertEquals(100, LyricsUserOffset().plusSteps(1).delayMs)
        assertEquals(-100, LyricsUserOffset().plusSteps(-1).delayMs)
        assertEquals(10_000, LyricsUserOffset(9_900).plusSteps(Int.MAX_VALUE).delayMs)
        assertEquals(-10_000, LyricsUserOffset(-9_900).plusSteps(Int.MIN_VALUE).delayMs)
        assertEquals(0, LyricsUserOffset().delayMs)
        expectLyricsArgumentError { LyricsUserOffset(50) }
        expectLyricsArgumentError { LyricsUserOffset(10_100) }
        expectLyricsArgumentError { LyricsClockMapping(timelineOriginMs = -1L) }
    }

    @Test fun seekPreviewDisablesUnknownDurationOutOfRangeAndInvalidIndicesWithoutClamping() {
        runBlocking {
            val document = parseLyrics("[00:00]Zero\n[00:01]One\n[00:02]End")
            assertEquals(0L, requireNotNull(document.timeline.seekTarget(0, 2_000L)).mediaPositionMs)
            assertEquals(1_000L, requireNotNull(document.timeline.seekTarget(1, 2_000L)).mediaPositionMs)
            assertNull(document.timeline.seekTarget(2, 2_000L))
            assertNull(document.timeline.seekTarget(1, 999L))
            assertNull(document.timeline.seekTarget(0, 0L))
            assertNull(document.timeline.seekTarget(0, -1L))
            assertNull(document.timeline.seekTarget(-1, 2_000L))
            assertNull(document.timeline.seekTarget(3, 2_000L))
        }
    }

    @Test fun negativeNormalizedAndOverflowedSeekTimesAreDisabled() {
        runBlocking {
            val negative = parseLyrics("[offset:2000]\n[00:01]Early")
            assertNull(negative.timeline.seekTarget(0, 5_000L))
            val overflow = parseLyrics("[offset:-9223372036854775808]\n[00:00]Overflow")
            assertNull(overflow.timeline.seekTarget(0, Long.MAX_VALUE))
            assertNull(LyricsClockMapping().lyricTime(Long.MAX_VALUE, 1L))
            assertNull(LyricsClockMapping(1L).lyricTime(Long.MAX_VALUE, 0L))
            assertNull(LyricsClockMapping().mediaTime(0L, Long.MIN_VALUE))
        }
    }

    @Test fun oldContentCursorsCannotSelectWordsInAReplacementDocument() {
        runBlocking {
            val old = parseLyrics("[00:01]<00:01>old")
            val current = parseLyrics("[00:01]<00:01>current")
            val oldPosition = requireNotNull(old.timeline.position(1_000L))
            assertNull(current.timeline.word(0, oldPosition))
            val preview = requireNotNull(current.timeline.seekTarget(0, 5_000L))
            assertEquals(current.documentId, preview.documentId)
            assertEquals(current.timeline.line(0).id, preview.lineId)
        }
    }
}
