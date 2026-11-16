package io.github.onlinemo.meloxwatch.data.lyrics

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedSyltLyricsTest {
    private val binary = EmbeddedLyricsBinaryFixture

    private suspend fun readEvents(events: List<Pair<String, Long>>, encoding: Int = 3): EmbeddedLyricsReadResult =
        readEmbedded(binary.id3(binary.frame("SYLT", binary.sylt(events, encoding), version = 4), version = 4))

    @Test fun checkedInV24SyltKeepsRealFragmentTimesAndUnknownFinalEnd() = runBlocking {
        val result = readEmbedded(binary.resource("id3-v24-sylt-utf8.bin"))
        val document = result.defaultCandidate!!.document
        assertTrue(result.diagnostics.isEmpty())
        assertEquals(LyricsKind.SYNCHRONIZED, document.kind)
        assertEquals("Hello world", document.timeline.line(0).text)
        assertEquals(1_000L, document.timeline.line(0).startsAtMs)
        assertEquals(listOf(1_000L, 1_500L), document.timeline.line(0).wordTimings.map { it.startsAtMs })
        assertEquals(listOf(1_500L, null), document.timeline.line(0).wordTimings.map { it.endsAtMs })
        assertEquals(listOf(0 to 6, 6 to 11), document.timeline.line(0).wordTimings.map { it.startChar to it.endChar })
    }

    @Test fun finalFragmentHasStaticEmphasisWithoutSyntheticProgress() = runBlocking {
        val document = readEvents(listOf("one " to 1_000L, "two" to 2_000L)).defaultCandidate!!.document
        val position = document.timeline.position(2_500L)!!
        val word = document.timeline.word(0, position)!!
        assertEquals(1, word.wordIndex)
        assertNull(word.timing.endsAtMs)
        assertNull(word.progress)
    }

    @Test fun realEmptyFragmentProvidesAnExplicitEndBoundary() = runBlocking {
        val document = readEvents(listOf("one " to 1_000L, "two" to 2_000L, "" to 3_000L)).defaultCandidate!!.document
        assertEquals(listOf(2_000L, 3_000L), document.timeline.line(0).wordTimings.map { it.endsAtMs })
        assertNull(document.timeline.word(0, document.timeline.position(3_000L)!!))
        assertEquals(0.5, document.timeline.word(0, document.timeline.position(1_500L)!!)!!.progress!!, 0.0001)
    }

    @Test fun unsigned32MillisecondsRemainMillisecondsIncludingTheMaximumValue() = runBlocking {
        val document = readEvents(listOf("begin " to 0L, "late" to 0xffff_ffffL)).defaultCandidate!!.document
        assertEquals(0L, document.timeline.line(0).startsAtMs)
        assertEquals(4_294_967_295L, document.timeline.line(0).wordTimings.last().startsAtMs)
        assertEquals(4_294_967_295L, document.timeline.line(0).wordTimings.first().endsAtMs)
        assertNull(document.timeline.line(0).wordTimings.last().endsAtMs)
    }

    @Test fun equalTimeAdjacentFragmentsBecomeOneRealRangeWithANextDistinctEnd() = runBlocking {
        val document = readEvents(listOf("Hello " to 1_000L, "world" to 1_000L, "" to 1_000L, "!" to 2_000L)).defaultCandidate!!.document
        val words = document.timeline.line(0).wordTimings
        assertEquals("Hello world!", document.timeline.line(0).text)
        assertEquals(2, words.size)
        assertEquals(LyricWordTiming(1_000L, 2_000L, 0, 11), words[0])
        assertEquals(LyricWordTiming(2_000L, null, 11, 12), words[1])
    }

    @Test fun leadingLineMarkerDoesNotCreateAnArtificialEmptyFirstRow() = runBlocking {
        val document = readEvents(listOf("\nfirst" to 1_000L, "\nsecond" to 2_000L)).defaultCandidate!!.document
        assertEquals(listOf("first", "second"), document.sourceLines.map { it.text })
        assertEquals(listOf(1_000L, 2_000L), (0 until document.timeline.size).map { document.timeline.line(it).startsAtMs })
        assertEquals(listOf(1, 2), document.sourceLines.map { it.sourceLineNumber })
    }

    @Test fun consecutiveMarkersPreserveBlankRowsAndOneFinalMarkerAddsNoRow() = runBlocking {
        val document = readEvents(listOf("\n\nfirst\n\nsecond\n" to 1_000L)).defaultCandidate!!.document
        assertEquals(listOf("", "first", "", "second"), document.sourceLines.map { it.text })
        assertEquals(4, document.timeline.size)
        assertTrue((0 until document.timeline.size).all { document.timeline.line(it).startsAtMs == 1_000L })
    }

    @Test fun crlfIsOneSeparatorWithinAFragment() = runBlocking {
        val document = readEvents(listOf("first\r\nsecond\rthird\nfourth" to 500L)).defaultCandidate!!.document
        assertEquals(listOf("first", "second", "third", "fourth"), document.sourceLines.map { it.text })
    }

    @Test fun splitCrLfStillKeepsOneRealLineMarkerTime() = runBlocking {
        val document = readEvents(listOf("first\r" to 1_000L, "\nsecond" to 2_000L)).defaultCandidate!!.document
        assertEquals(listOf("first", "second"), document.sourceLines.map { it.text })
        assertEquals(1_000L, document.timeline.line(1).startsAtMs)
        assertEquals(2_000L, document.timeline.line(1).wordTimings.single().startsAtMs)
        assertEquals(2_000L, document.timeline.line(0).wordTimings.single().endsAtMs)
    }

    @Test fun separateMarkerEventSuppliesItsOwnTimeWithoutInventingTheNextWordTime() = runBlocking {
        val document = readEvents(listOf("first" to 500L, "\n" to 1_000L, "second" to 1_500L)).defaultCandidate!!.document
        assertEquals(1_000L, document.timeline.line(1).startsAtMs)
        assertEquals(LyricWordTiming(1_500L, null, 0, 6), document.timeline.line(1).wordTimings.single())
        assertNull(document.timeline.word(1, document.timeline.position(1_200L)!!))
    }

    @Test fun literalBracketAngleAndOffsetTextCannotCreateFalseTimeTags() = runBlocking {
        val literal = "[00:55]<00:56>[offset:999]"
        val document = readEvents(listOf(literal to 1_000L)).defaultCandidate!!.document
        assertEquals(literal, document.sourceLines.single().text)
        assertEquals(1_000L, document.timeline.line(0).startsAtMs)
        assertEquals(0L, document.sourceOffsetMs)
        assertEquals(1, document.timeline.line(0).wordTimings.size)
    }

    @Test fun aMultiLineFragmentDoesNotDivideItsTimestampIntoInventedTimes() = runBlocking {
        val document = readEvents(listOf("first\nsecond" to 1_234L, "" to 2_345L)).defaultCandidate!!.document
        assertEquals(listOf("first", "second"), document.sourceLines.map { it.text })
        for (index in 0 until document.timeline.size) {
            assertEquals(1_234L, document.timeline.line(index).startsAtMs)
            assertEquals(1_234L, document.timeline.line(index).wordTimings.single().startsAtMs)
            assertEquals(2_345L, document.timeline.line(index).wordTimings.single().endsAtMs)
        }
        val active = document.timeline.position(1_500L)!!
        assertEquals(0, active.firstActiveIndex)
        assertEquals(2, active.activeEndExclusive)
    }

    @Test fun unicodeFragmentRangesUseCompleteUtf16Boundaries() = runBlocking {
        val document = readEvents(listOf("星空🌙" to 1_000L, "音乐" to 2_000L)).defaultCandidate!!.document
        assertEquals("星空🌙音乐", document.sourceLines.single().text)
        assertEquals(listOf(0 to 4, 4 to 6), document.sourceLines.single().wordTimings.map { it.startChar to it.endChar })
        assertEquals(2, document.sourceLines.single().wordTimings.size)
    }

    @Test fun utf16BomFragmentsHaveARealNormalPathIncludingEmptyDescriptors() = runBlocking {
        for (littleEndian in listOf(true, false)) {
            val payload = binary.sylt(listOf("\n星空" to 1_000L, "音乐" to 2_000L), encoding = 1, littleEndian = littleEndian)
            val result = readEmbedded(binary.id3(binary.frame("SYLT", payload), version = 3))
            assertEquals("星空音乐", result.defaultCandidate!!.document.sourceLines.single().text)
            assertEquals(2, result.defaultCandidate!!.document.sourceLines.single().wordTimings.size)
        }
    }

    @Test fun mpegFrameTimestampFormatIsNeverConvertedByAnAssumedFrameRate() = runBlocking {
        val payload = binary.sylt(listOf("text" to 100L), timestampFormat = 1)
        val result = readEmbedded(binary.id3(binary.frame("SYLT", payload, 4), version = 4))
        assertEmbeddedCandidateFailure(result, EmbeddedLyricsIssue.UNSUPPORTED_TIMESTAMP_FORMAT)
    }

    @Test fun nonLyricContentTypesAreNotRelabeledAsLyrics() = runBlocking {
        for (content in listOf(0, 2, 3, 8)) {
            val payload = binary.sylt(listOf("text" to 1_000L), contentType = content)
            val result = readEmbedded(binary.id3(binary.frame("SYLT", payload, 4), version = 4))
            assertEmbeddedCandidateFailure(result, EmbeddedLyricsIssue.UNSUPPORTED_SYLT_CONTENT)
        }
    }

    @Test fun decreasingTimestampRejectsTheEntireCandidateWithoutPublishingItsFirstRow() = runBlocking {
        val result = readEvents(listOf("\nfirst" to 2_000L, "\nsecond" to 1_000L))
        assertEmbeddedCandidateFailure(result, EmbeddedLyricsIssue.INVALID_TIMESTAMP)
    }

    @Test fun truncatedTimestampAndUnterminatedFragmentAreDifferentBoundedFailures() = runBlocking {
        val payload = binary.sylt(listOf("text" to 1_000L))
        val truncated = payload.copyOf(payload.size - 1)
        assertEmbeddedCandidateFailure(readEmbedded(binary.id3(binary.frame("SYLT", truncated, 4), version = 4)), EmbeddedLyricsIssue.INVALID_TIMESTAMP)
        val unterminated = binary.join(byteArrayOf(3), "eng".toByteArray(), byteArrayOf(2, 1, 0), "text".toByteArray())
        assertEmbeddedCandidateFailure(readEmbedded(binary.id3(binary.frame("SYLT", unterminated, 4), version = 4)), EmbeddedLyricsIssue.INVALID_TEXT)
    }

    @Test fun onlyEmptyEventsReturnNoTimedOrTextCapability() = runBlocking {
        val result = readEvents(listOf("" to 0L, "" to 1_000L))
        assertEquals(LyricsKind.EMPTY, result.candidates.single().document.kind)
        assertEquals(0, result.candidates.single().document.timeline.size)
        assertNull(result.defaultCandidate)
    }

    @Test fun synchronizedSeekUsesTheSharedClockAndDisabledOutOfRangeRule() = runBlocking {
        val document = readEvents(listOf("line" to 1_000L)).defaultCandidate!!.document
        val mapping = LyricsClockMapping(userOffset = LyricsUserOffset(200))
        assertEquals(1_200L, document.timeline.seekTarget(0, 10_000L, mapping)!!.mediaPositionMs)
        assertNull(document.timeline.seekTarget(0, 1_000L, mapping))
        assertNull(document.timeline.position(1_199L, mapping))
        assertEquals(0, document.timeline.position(1_200L, mapping)!!.anchorIndex)
    }
}
