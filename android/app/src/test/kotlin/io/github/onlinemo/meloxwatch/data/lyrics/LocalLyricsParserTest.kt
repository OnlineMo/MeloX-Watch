package io.github.onlinemo.meloxwatch.data.lyrics

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLyricsParserTest {
    @Test fun standardMetadataAndFractionalLrcKeepSourceLinesSeparateFromTheTimeline() {
        runBlocking {
            val document = parseLyrics("[ti:Song]\n[ar:Artist]\n[00:01.25]One\n[00:02]Two")
            assertEquals(LyricsKind.SYNCHRONIZED, document.kind)
            assertEquals(1, document.schemaVersion)
            assertEquals(4, document.physicalLineCount)
            assertEquals(listOf("One", "Two"), document.sourceLines.map { it.text })
            assertEquals(listOf(3, 4), document.sourceLines.map { it.sourceLineNumber })
            assertEquals(1_250L, document.timeline.line(0).startsAtMs)
            assertEquals(2_000L, document.timeline.line(1).startsAtMs)
            assertEquals(2, document.diagnostics.ignoredMetadataLineCount)
            assertFalse(document.diagnostics.hasWarnings)
            assertFalse(document.hasWordTimings)
        }
    }

    @Test fun multiTagsExpandAndEqualTimesKeepExplicitSourceOccurrenceIds() {
        runBlocking {
            val document = parseLyrics("[00:03][00:01][00:01]Alpha\n[00:01]Beta")
            assertEquals(4, document.timeline.size)
            assertEquals(listOf(LyricsLineId(1, 1), LyricsLineId(1, 2), LyricsLineId(2, 0), LyricsLineId(1, 0)),
                (0 until document.timeline.size).map { document.timeline.line(it).id })
            assertEquals(listOf("Alpha", "Alpha", "Beta", "Alpha"),
                (0 until document.timeline.size).map { document.timeline.line(it).text })
            assertEquals(listOf("Alpha", "Beta"), document.sourceLines.map { it.text })
        }
    }

    @Test fun invalidNumericTimesAreExplainedAndTheirLyricTextSurvives() {
        runBlocking {
            val document = parseLyrics("[00:60]Too late\n[-00:01]Negative\n[00:00.1234]Precision\n" +
                "[999999999999999999:00]Overflow\n[00:02]Valid")
            assertEquals(4, document.diagnostics.invalidLineTimestampCount)
            assertTrue(document.diagnostics.hasWarnings)
            assertEquals(listOf("Too late", "Negative", "Precision", "Overflow", "Valid"),
                document.sourceLines.map { it.text })
            assertEquals(1, document.timeline.size)
            assertEquals("Valid", document.timeline.line(0).text)
        }
    }

    @Test fun truncatedTimeTagsRemainReadableWithAWarning() {
        runBlocking {
            val document = parseLyrics("[00:01")
            assertEquals(LyricsKind.PLAIN_TEXT, document.kind)
            assertEquals("[00:01", document.sourceLines.single().text)
            assertEquals(1, document.diagnostics.invalidLineTimestampCount)
        }
    }

    @Test fun nonnumericBracketedTextAndUnsupportedAngleNotationStayLiteral() {
        runBlocking {
            val document = parseLyrics("[abc:02]literal <abc:02>\n[00:01]<verse>words</verse>")
            assertEquals(listOf("[abc:02]literal <abc:02>", "<verse>words</verse>"), document.sourceLines.map { it.text })
            assertFalse(document.hasWordTimings)
            assertFalse(document.diagnostics.hasWarnings)
        }
    }

    @Test fun offsetUsesTheFirstValidWholeMetadataDirectiveWithoutReadingLyricFragments() {
        runBlocking {
            val document = parseLyrics("[offset:broken]\n[OFFSET:1000]\n[offset:1000][offset:1500]\n" +
                "[00:01]One[offset:2000]")
            assertEquals(1_000L, document.sourceOffsetMs)
            assertEquals(1, document.diagnostics.invalidOffsetCount)
            assertEquals(1, document.diagnostics.conflictingOffsetCount)
            assertEquals(3, document.diagnostics.ignoredMetadataLineCount)
            assertEquals("One[offset:2000]", document.timeline.line(0).text)
        }
    }

    @Test fun emptyAndBlankInputsHaveNoSynchronization() {
        runBlocking {
            val empty = parseLyrics("")
            val blank = parseLyrics(" \t\n")
            assertEquals(LyricsKind.EMPTY, empty.kind)
            assertEquals(0, empty.physicalLineCount)
            assertTrue(empty.sourceLines.isEmpty())
            assertEquals(LyricsKind.EMPTY, blank.kind)
            assertEquals(1, blank.physicalLineCount)
            assertEquals(" \t", blank.sourceLines.single().text)
            assertEquals(0, blank.timeline.size)
        }
    }

    @Test fun plainTextKeepsBlankLinesAndReadingOrderWithoutInventingTimes() {
        runBlocking {
            val document = parseLyrics("First\n\n中文 / English / العربية")
            assertEquals(LyricsKind.PLAIN_TEXT, document.kind)
            assertEquals(listOf("First", "", "中文 / English / العربية"), document.sourceLines.map { it.text })
            assertEquals(0, document.timeline.size)
            assertFalse(document.hasWordTimings)
        }
    }

    @Test fun oneOrTwoSecondDigitsAndOneToThreeFractionDigitsHaveExactMillisecondValues() {
        runBlocking {
            val document = parseLyrics("[1:2]a\n[1:02.3]b\n[1:02.34]c\n[1:02.345]d")
            assertEquals(listOf(62_000L, 62_300L, 62_340L, 62_345L),
                (0 until document.timeline.size).map { document.timeline.line(it).startsAtMs })
        }
    }

    @Test fun theLargestRepresentableTimestampDoesNotWrapAndTheNextMillisecondIsRejected() {
        runBlocking {
            val document = parseLyrics("[153722867280912:55.807]Last\n[153722867280912:55.808]Overflow")
            assertEquals(1, document.timeline.size)
            assertEquals(Long.MAX_VALUE, document.timeline.line(0).startsAtMs)
            assertEquals(1, document.diagnostics.invalidLineTimestampCount)
            assertEquals("Overflow", document.sourceLines[1].text)
        }
    }

    @Test fun aTimedBlankLineIsARealStableTimelineBoundary() {
        runBlocking {
            val document = parseLyrics("[00:01]One\n[00:02]\n[00:03]Three")
            assertEquals(3, document.timeline.size)
            assertEquals("", document.timeline.line(1).text)
            assertEquals(LyricsLineId(2, 0), document.timeline.line(1).id)
            assertEquals(1, requireNotNull(document.timeline.position(2_000L)).anchorIndex)
        }
    }

    @Test fun parsedContentAndWordListsKeepNoMutableInputAlias() {
        runBlocking {
            val input = "[00:01]<00:01>one<00:02>two".toByteArray(Charsets.UTF_8)
            val document = LocalLyricsParser().parse(input)
            val repeated = LocalLyricsParser().parse(input)
            val changed = parseLyrics("[00:01]<00:01>other")
            input.fill(0)
            assertEquals("onetwo", document.sourceLines.single().text)
            assertEquals(document.documentId, repeated.documentId)
            assertNotEquals(document.documentId, changed.documentId)
            try {
                (document.sourceLines as MutableList<LyricsTextLine>).clear()
                throw AssertionError("Source list must be immutable")
            } catch (_: UnsupportedOperationException) { /* Expected for the transferred private list. */ }
            try {
                (document.sourceLines.single().wordTimings as MutableList<LyricWordTiming>).clear()
                throw AssertionError("Word list must be immutable")
            } catch (_: UnsupportedOperationException) { /* Expected for the transferred private list. */ }
        }
    }
}
