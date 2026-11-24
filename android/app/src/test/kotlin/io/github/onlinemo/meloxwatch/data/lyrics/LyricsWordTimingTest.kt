package io.github.onlinemo.meloxwatch.data.lyrics

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsWordTimingTest {
    @Test fun genuineFragmentBoundariesProduceExactRangesAndARealAnimationFraction() {
        runBlocking {
            val document = parseLyrics("[00:01]<00:01>one<00:02>two<00:03>")
            val words = document.sourceLines.single().wordTimings
            assertEquals("onetwo", document.sourceLines.single().text)
            assertTrue(document.hasWordTimings)
            assertEquals(listOf(LyricWordTiming(1_000L, 2_000L, 0, 3), LyricWordTiming(2_000L, 3_000L, 3, 6)), words)
            val first = requireNotNull(document.timeline.word(0, requireNotNull(document.timeline.position(1_500L))))
            assertEquals(0, first.wordIndex)
            assertEquals(0.5, requireNotNull(first.progress), 0.000001)
            val second = requireNotNull(document.timeline.word(0, requireNotNull(document.timeline.position(2_000L))))
            assertEquals(1, second.wordIndex)
            assertEquals(0.0, requireNotNull(second.progress), 0.000001)
            assertNull(document.timeline.word(0, requireNotNull(document.timeline.position(3_000L))))
        }
    }

    @Test fun anOpenLastFragmentOffersStaticEmphasisWithoutInventingAnEndTime() {
        runBlocking {
            val document = parseLyrics("[00:01]<00:01>whole")
            val word = requireNotNull(document.timeline.word(0, requireNotNull(document.timeline.position(8_000L))))
            assertEquals("whole", document.sourceLines.single().text)
            assertNull(word.timing.endsAtMs)
            assertNull(word.progress)
            assertEquals(1, document.sourceLines.single().wordTimings.size)
            assertNull(document.timeline.word(0, requireNotNull(document.timeline.position(8_000L)), enabled = false))
        }
    }

    @Test fun ordinaryLineTimeDoesNotCreateSyntheticPerLetterTiming() {
        runBlocking {
            val document = parseLyrics("[00:01]A whole line")
            assertFalse(document.hasWordTimings)
            assertNull(document.timeline.word(0, requireNotNull(document.timeline.position(2_000L))))
            assertEquals("A whole line", document.timeline.line(0).text)
        }
    }

    @Test fun untimedLeadingTextRemainsReadableAndIsNotIncludedInALaterTimedFragment() {
        runBlocking {
            val document = parseLyrics("[00:00]prefix <00:01>real")
            assertEquals("prefix real", document.sourceLines.single().text)
            assertNull(document.timeline.word(0, requireNotNull(document.timeline.position(500L))))
            val word = requireNotNull(document.timeline.word(0, requireNotNull(document.timeline.position(1_500L))))
            assertEquals(7, word.timing.startChar)
            assertEquals(11, word.timing.endChar)
        }
    }

    @Test fun repeatedReversedNegativeAndInvalidWordTimesDowngradeTheWholeRowButKeepText() {
        runBlocking {
            val samples = listOf(
                "[00:01]<00:01>one<00:01>two",
                "[00:01]<00:02>one<00:01>two",
                "[00:01]<-00:01>one<00:02>two",
                "[00:01]<00:60>one<00:02>two",
                "[00:01]<00:00>one<00:02>two",
            )
            for (sample in samples) {
                val document = parseLyrics(sample)
                assertEquals("onetwo", document.timeline.line(0).text)
                assertFalse(document.hasWordTimings)
                assertEquals(1, document.diagnostics.invalidWordTimingLineCount)
            }
        }
    }

    @Test fun angleTimesWithoutALineTimestampRemainPlainTextAndReportTheTimingFailure() {
        runBlocking {
            val document = parseLyrics("<00:01>one<00:02>two")
            assertEquals(LyricsKind.PLAIN_TEXT, document.kind)
            assertEquals("onetwo", document.sourceLines.single().text)
            assertFalse(document.hasWordTimings)
            assertEquals(1, document.diagnostics.invalidWordTimingLineCount)
        }
    }

    @Test fun enhancedMultiTagRowsStayLineTimedWithoutShiftingOrReusingWordTimes() {
        runBlocking {
            val document = parseLyrics("[00:01][00:10]<00:01>one<00:02>two")
            assertEquals(2, document.timeline.size)
            assertEquals("onetwo", document.timeline.line(0).text)
            assertEquals("onetwo", document.timeline.line(1).text)
            assertFalse(document.hasWordTimings)
            assertEquals(1, document.diagnostics.ambiguousWordTimingLineCount)
            assertEquals(0, document.diagnostics.invalidWordTimingLineCount)
        }
    }

    @Test fun wordEventsCrossingTheNextRealLineBoundaryDowngradeWithoutDroppingLyrics() {
        runBlocking {
            for (sample in listOf("[00:01]<00:01>one<00:04>two\n[00:03]Next",
                "[00:01]<00:01>one<00:03>two\n[00:03]Next")) {
                val document = parseLyrics(sample)
                assertEquals("onetwo", document.timeline.line(0).text)
                assertEquals("Next", document.timeline.line(1).text)
                assertFalse(document.hasWordTimings)
                assertEquals(1, document.diagnostics.invalidWordTimingLineCount)
            }
        }
    }

    @Test fun aRealClosingTagAtTheNextLineBoundaryIsValidAndCannotRemainActiveAfterSeek() {
        runBlocking {
            val document = parseLyrics("[00:01]<00:01>one<00:03>\n[00:03]Next")
            assertTrue(document.hasWordTimings)
            assertEquals(3_000L, document.timeline.line(0).wordTimings.single().endsAtMs)
            assertTrue(document.timeline.word(0, requireNotNull(document.timeline.position(2_999L))) != null)
            val next = requireNotNull(document.timeline.position(3_000L))
            assertEquals(1, next.anchorIndex)
            assertNull(document.timeline.word(0, next))
        }
    }

    @Test fun supplementaryTextRangesNeverSplitASurrogatePair() {
        runBlocking {
            val document = parseLyrics("[00:01]<00:01>\uD83D\uDE03<00:02>a<00:03>")
            val line = document.sourceLines.single()
            assertEquals("\uD83D\uDE03a", line.text)
            assertEquals(LyricWordTiming(1_000L, 2_000L, 0, 2), line.wordTimings[0])
            assertEquals(LyricWordTiming(2_000L, 3_000L, 2, 3), line.wordTimings[1])
            assertEquals("\uD83D\uDE03", line.text.substring(line.wordTimings[0].startChar, line.wordTimings[0].endChar))
        }
    }

    @Test fun aTruncatedWordTagPreservesItsVisibleTextAndDisablesWordTiming() {
        runBlocking {
            val document = parseLyrics("[00:01]<00:01>one<00:02unfinished")
            assertEquals("one<00:02unfinished", document.timeline.line(0).text)
            assertFalse(document.hasWordTimings)
            assertEquals(1, document.diagnostics.invalidWordTimingLineCount)
        }
    }
}
