package io.github.onlinemo.meloxwatch.data.lyrics

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLyricsBoundsTest {
    @Test fun frozenLimitsCannotBeWidenedByAConstructorArgument() {
        expectLyricsArgumentError { LyricsLimits(maxInputBytes = LyricsLimits.INPUT_BYTES + 1) }
        expectLyricsArgumentError { LyricsLimits(maxPhysicalLines = LyricsLimits.PHYSICAL_LINES + 1) }
        expectLyricsArgumentError { LyricsLimits(maxTimelineEntries = LyricsLimits.TIMELINE_ENTRIES + 1) }
        expectLyricsArgumentError { LyricsLimits(maxWordEvents = LyricsLimits.WORD_EVENTS + 1) }
        expectLyricsArgumentError { LyricsLimits(maxLineCodePoints = LyricsLimits.LINE_CODE_POINTS + 1) }
        expectLyricsArgumentError { LyricsLimits(maxAccountedBytes = LyricsLimits.ACCOUNTED_BYTES + 1L) }
        expectLyricsArgumentError { LyricsLimits(maxInputBytes = 0) }
    }

    @Test fun theByteLimitRejectsExcessBeforeInterpretingEvenACompleteLrcPrefix() {
        runBlocking {
            val text = "[00:01]One"
            val limits = LyricsLimits(maxInputBytes = text.toByteArray(Charsets.UTF_8).size)
            assertEquals("One", parseLyrics(text, limits).timeline.line(0).text)
            expectLyricsIssue(LyricsParseIssue.INPUT_TOO_LARGE) { parseLyrics("$text\n", limits) }
            expectLyricsIssue(LyricsParseIssue.INPUT_TOO_LARGE) {
                LocalLyricsParser().parse(ByteArray(LyricsLimits.INPUT_BYTES + 1))
            }
        }
    }

    @Test fun crLfCrAndLfCountPhysicalRowsWithoutAddingATerminalPhantomRow() {
        runBlocking {
            val limits = LyricsLimits(maxPhysicalLines = 3)
            val document = parseLyrics("One\r\nTwo\rThree\n", limits)
            assertEquals(3, document.physicalLineCount)
            assertEquals(listOf("One", "Two", "Three"), document.sourceLines.map { it.text })
            expectLyricsIssue(LyricsParseIssue.TOO_MANY_LINES, 4) { parseLyrics("One\nTwo\nThree\nFour", limits) }
        }
    }

    @Test fun theTimelineLimitCountsExpandedTagsRatherThanOnlyPhysicalRows() {
        runBlocking {
            val limits = LyricsLimits(maxTimelineEntries = 2)
            assertEquals(2, parseLyrics("[00:01][00:02]One", limits).timeline.size)
            expectLyricsIssue(LyricsParseIssue.TOO_MANY_TIMED_LINES, 1) {
                parseLyrics("[00:01][00:02][00:03]One", limits)
            }
        }
    }

    @Test fun theDefaultExpandedTimelineCapHasAPassingFullBoundaryAndARejectedExtraTag() {
        runBlocking {
            val rows = List(5_000) { "[00:01][00:02]x" }.joinToString("\n")
            val exact = parseLyrics(rows)
            assertEquals(LyricsLimits.TIMELINE_ENTRIES, exact.timeline.size)
            assertEquals(5_000, exact.physicalLineCount)
            expectLyricsIssue(LyricsParseIssue.TOO_MANY_TIMED_LINES, 5_000) {
                parseLyrics("[00:03]$rows")
            }
        }
    }

    @Test fun lineLengthUsesUnicodeCodePointsRatherThanSplittingASupplementaryCharacter() {
        runBlocking {
            val limits = LyricsLimits(maxLineCodePoints = 2)
            assertEquals("\uD83D\uDE03a", parseLyrics("\uD83D\uDE03a", limits).sourceLines.single().text)
            expectLyricsIssue(LyricsParseIssue.LINE_TOO_LONG, 1) { parseLyrics("\uD83D\uDE03ab", limits) }
            assertEquals(LyricsLimits.LINE_CODE_POINTS,
                parseLyrics("a".repeat(LyricsLimits.LINE_CODE_POINTS)).sourceLines.single().text.length)
            expectLyricsIssue(LyricsParseIssue.LINE_TOO_LONG, 1) {
                parseLyrics("a".repeat(LyricsLimits.LINE_CODE_POINTS + 1))
            }
        }
    }

    @Test fun onlyNonemptyRealFragmentsConsumeTheWordEventLimit() {
        runBlocking {
            val limits = LyricsLimits(maxWordEvents = 2)
            val exact = parseLyrics("[00:01]<00:01><00:02>a<00:03>b<00:04>", limits)
            assertEquals(2, exact.sourceLines.single().wordTimings.size)
            expectLyricsIssue(LyricsParseIssue.TOO_MANY_WORD_EVENTS, 1) {
                parseLyrics("[00:01]<00:01>a<00:02>b<00:03>c", limits)
            }
        }
    }

    @Test fun insufficientDecoderWorkspaceIsAMemoryFailureWithoutAPartialDocument() {
        runBlocking {
            expectLyricsIssue(LyricsParseIssue.MEMORY_LIMIT) {
                parseLyrics("a".repeat(128), LyricsLimits(maxAccountedBytes = 2_000L))
            }
        }
    }

    @Test fun residentRowsAndCurrentLineScratchShareOneBudgetThroughoutParsing() {
        runBlocking {
            val limits = LyricsLimits(maxAccountedBytes = 3_000L)
            val accepted = parseLyrics(List(8) { "a" }.joinToString("\n"), limits)
            assertEquals(8, accepted.sourceLines.size)
            assertTrue(accepted.accountedResidentBytes < limits.maxAccountedBytes)
            expectLyricsIssue(LyricsParseIssue.MEMORY_LIMIT, 9) {
                parseLyrics(List(9) { "a" }.joinToString("\n"), limits)
            }
        }
    }

    @Test fun nonTextControlCharactersAreRejectedWithABoundedLineNumber() {
        runBlocking {
            expectLyricsIssue(LyricsParseIssue.INVALID_TEXT, 2) { parseLyrics("First\nBad\u0000text") }
            expectLyricsIssue(LyricsParseIssue.INVALID_TEXT, 1) { parseLyrics("Bad\u0001text") }
        }
    }
}
