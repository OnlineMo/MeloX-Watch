package io.github.onlinemo.meloxwatch.data.lyrics

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTranslationTest {
    @Test fun onlyExactlyEqualNormalizedTimesPairAndAdjacentTimesAreNotGuessed() {
        runBlocking {
            val primary = parseLyrics("[00:01]One\n[00:02]Two\n[00:03]Three")
            val translated = parseLyrics("[00:01]一\n[00:02.001]Wrong time\n[00:03]三")
            val result = LyricsTranslationMatcher().match(primary, translated)
            assertEquals("一", requireNotNull(result.line(0, primary.documentId)).text)
            assertNull(result.line(1, primary.documentId))
            assertEquals("三", requireNotNull(result.line(2, primary.documentId)).text)
            assertEquals(2, result.diagnostics.pairedLineCount)
            assertEquals(1, result.diagnostics.unmatchedPrimaryLineCount)
            assertEquals(1, result.diagnostics.unmatchedTranslationLineCount)
            assertEquals(translated.documentId, result.translationDocumentId)
        }
    }

    @Test fun independentPositiveAndNegativeSourceOffsetsUseRawTimeMinusSourceOffset() {
        runBlocking {
            val pairs = listOf(
                "[offset:1000]\n[00:05]Original" to "[offset:2000]\n[00:06]Translated",
                "[offset:-1000]\n[00:03]Original" to "[00:04]Translated",
            )
            for ((primaryText, translatedText) in pairs) {
                val primary = parseLyrics(primaryText)
                val translated = parseLyrics(translatedText)
                val result = LyricsTranslationMatcher().match(primary, translated)
                assertEquals("Translated", requireNotNull(result.line(0, primary.documentId)).text)
                assertEquals(1, result.diagnostics.pairedLineCount)
                assertEquals(0, result.diagnostics.ambiguousDuplicateGroupCount)
            }
        }
    }

    @Test fun equalDuplicateGroupsPairByStableSourceOrdinalEvenWhenInputRowsAreOutOfTimeOrder() {
        runBlocking {
            val primary = parseLyrics("[00:01]First\n[00:01]Second\n[00:02]Third")
            val translated = parseLyrics("[00:02]三\n[00:01]一\n[00:01]二")
            val result = LyricsTranslationMatcher().match(primary, translated)
            assertEquals(listOf("一", "二", "三"), (0 until result.size).map { requireNotNull(result.line(it, primary.documentId)).text })
            assertEquals(listOf(LyricsLineId(2, 0), LyricsLineId(3, 0), LyricsLineId(1, 0)),
                (0 until result.size).map { requireNotNull(result.line(it, primary.documentId)).id })
            assertEquals(3, result.diagnostics.pairedLineCount)
            assertEquals(0, result.diagnostics.ambiguousDuplicateGroupCount)
        }
    }

    @Test fun unequalDuplicateGroupsRemainWhollyUnmatchedAndCannotShiftTheNextGroup() {
        runBlocking {
            val one = "[00:01]One\n[00:02]Next"
            val two = "[00:01]First\n[00:01]Second\n[00:02]Next translation"
            for ((primaryText, translatedText) in listOf(one to two, two to one)) {
                val primary = parseLyrics(primaryText)
                val translated = parseLyrics(translatedText)
                val result = LyricsTranslationMatcher().match(primary, translated)
                val nextIndex = primary.timeline.size - 1
                for (index in 0 until nextIndex) assertNull(result.line(index, primary.documentId))
                assertTrue(result.line(nextIndex, primary.documentId) != null)
                assertEquals(1, result.diagnostics.pairedLineCount)
                assertEquals(primary.timeline.size - 1, result.diagnostics.unmatchedPrimaryLineCount)
                assertEquals(translated.timeline.size - 1, result.diagnostics.unmatchedTranslationLineCount)
                assertEquals(1, result.diagnostics.ambiguousDuplicateGroupCount)
            }
        }
    }

    @Test fun absentTimedTranslationLeavesOriginalTextIntactAndAllTimedRowsUnmatched() {
        runBlocking {
            val primary = parseLyrics("[00:01]One\n[00:02]Two")
            val translated = parseLyrics("Translation without timestamps")
            val result = LyricsTranslationMatcher().match(primary, translated)
            assertEquals(2, result.size)
            assertNull(result.line(0, primary.documentId))
            assertNull(result.line(1, primary.documentId))
            assertEquals(2, result.diagnostics.unmatchedPrimaryLineCount)
            assertEquals(0, result.diagnostics.unmatchedTranslationLineCount)
            assertEquals(listOf("One", "Two"), primary.sourceLines.map { it.text })
        }
    }

    @Test fun unrepresentableNormalizedTimesAreUnmatchedAndDiagnosedOnEitherSide() {
        runBlocking {
            val invalid = parseLyrics("[offset:-9223372036854775808]\n[00:00]Zero\n[00:01]One")
            val normal = parseLyrics("[00:00]Zero\n[00:01]One")
            val first = LyricsTranslationMatcher().match(invalid, normal)
            assertEquals(0, first.diagnostics.pairedLineCount)
            assertEquals(2, first.diagnostics.unrepresentablePrimaryTimeCount)
            assertEquals(0, first.diagnostics.unrepresentableTranslationTimeCount)
            val second = LyricsTranslationMatcher().match(normal, invalid)
            assertEquals(0, second.diagnostics.pairedLineCount)
            assertEquals(0, second.diagnostics.unrepresentablePrimaryTimeCount)
            assertEquals(2, second.diagnostics.unrepresentableTranslationTimeCount)
        }
    }

    @Test fun aLateOverflowAfterTheOtherTimelineEndsCannotHideItsDiagnosticOrProduceAPair() {
        runBlocking {
            val primary = parseLyrics("[offset:-1]\n[00:00]First\n[153722867280912:55.807]Overflow")
            val translated = parseLyrics("[00:00.001]一")
            val result = LyricsTranslationMatcher().match(primary, translated)
            assertEquals("一", requireNotNull(result.line(0, primary.documentId)).text)
            assertNull(result.line(1, primary.documentId))
            assertEquals(1, result.diagnostics.pairedLineCount)
            assertEquals(1, result.diagnostics.unrepresentablePrimaryTimeCount)
            assertEquals(1, result.diagnostics.unmatchedPrimaryLineCount)
        }
    }

    @Test fun bothResidentDocumentsAndTheirPairingArrayMustFitTheSameMemoryBudget() {
        runBlocking {
            val primary = parseLyrics("[00:01]p")
            val translated = parseLyrics("[00:01]t")
            assertTrue(primary.accountedResidentBytes < 3_000L)
            assertTrue(translated.accountedResidentBytes < 3_000L)
            expectLyricsIssue(LyricsParseIssue.MEMORY_LIMIT) {
                LyricsTranslationMatcher(LyricsLimits(maxAccountedBytes = 3_000L)).match(primary, translated)
            }
            val result = LyricsTranslationMatcher(LyricsLimits(maxAccountedBytes = 4_096L)).match(primary, translated)
            assertEquals(1, result.diagnostics.pairedLineCount)
            assertTrue(result.accountedResidentBytes <= 4_096L)
        }
    }

    @Test fun aTightenedTimelineBudgetRejectsRatherThanTruncatingAPairingInput() {
        runBlocking {
            val primary = parseLyrics("[00:01]One\n[00:02]Two")
            val translated = parseLyrics("[00:01]一")
            expectLyricsIssue(LyricsParseIssue.TOO_MANY_TIMED_LINES) {
                LyricsTranslationMatcher(LyricsLimits(maxTimelineEntries = 1)).match(primary, translated)
            }
        }
    }

    @Test fun hiddenTranslationInvalidIndicesAndReplacementContentCannotReuseAnOldMapping() {
        runBlocking {
            val primary = parseLyrics("[00:01]Original")
            val translated = parseLyrics("[00:01]Translated")
            val replacement = parseLyrics("[00:01]Replacement")
            val result = LyricsTranslationMatcher().match(primary, translated)
            assertNull(result.line(0, primary.documentId, enabled = false))
            assertNull(result.line(-1, primary.documentId))
            assertNull(result.line(1, primary.documentId))
            assertNull(result.line(0, replacement.documentId))
            assertEquals("Translated", requireNotNull(result.line(0, primary.documentId)).text)
        }
    }
}
