package io.github.onlinemo.meloxwatch.data.lyrics

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedOggBoundsTest {
    private val binary = EmbeddedLyricsBinaryFixture
    private val ogg = OggLyricsBinaryFixture

    @Test fun physicalMetadataLimitCountsPageHeadersLacesAndBodies() = runBlocking {
        val bytes = ogg.opus(listOf(binary.lyrics("text")))
        val exact = EmbeddedLyricsLimits(maxMetadataBytes = bytes.size)
        assertTrue(readEmbedded(bytes, exact).metadataComplete)
        assertEmbeddedFatal(readEmbedded(bytes, exact.copy(maxMetadataBytes = bytes.size - 1)), EmbeddedLyricsIssue.METADATA_TOO_LARGE)
    }

    @Test fun pagesAndCommentFieldsShareOneStructureBudget() = runBlocking {
        val one = ogg.opus(listOf(binary.lyrics("text")))
        assertTrue(readEmbedded(one, EmbeddedLyricsLimits(maxStructures = 3)).metadataComplete)
        assertEmbeddedFatal(readEmbedded(one, EmbeddedLyricsLimits(maxStructures = 2)), EmbeddedLyricsIssue.TOO_MANY_STRUCTURES)
        val two = ogg.opus(listOf(binary.lyrics("first"), binary.lyrics("second")))
        val result = readEmbedded(two, EmbeddedLyricsLimits(maxStructures = 3))
        assertEmbeddedFatal(result, EmbeddedLyricsIssue.TOO_MANY_STRUCTURES)
        assertEquals(5, result.cumulativePayloadBytes)
    }

    @Test fun emptyContinuationPagesCannotBypassTheStructureBudget() = runBlocking {
        val tags = ogg.opusTags(listOf(binary.lyrics("x".repeat(600))))
        val bytes = binary.join(
            ogg.identification(ogg.opusId()),
            ogg.page(tags.copyOfRange(0, 255), intArrayOf(255), granule = -1L, sequence = 1L),
            ogg.page(ByteArray(0), intArrayOf(), granule = -1L, sequence = 2L),
            ogg.page(ByteArray(0), intArrayOf(), granule = -1L, sequence = 3L),
            ogg.page(tags.copyOfRange(255, tags.size), flags = 1, sequence = 4L),
        )
        assertEmbeddedFatal(readEmbedded(bytes, EmbeddedLyricsLimits(maxStructures = 4)), EmbeddedLyricsIssue.TOO_MANY_STRUCTURES)
    }

    @Test fun unsignedCommentCountIsRejectedWithoutAnArrayOrIntegerOverflow() = runBlocking {
        val tags = binary.join("OpusTags".toByteArray(), binary.uint32(0, true), binary.uint32(0xffff_ffffL, true))
        val result = readEmbedded(ogg.metadata(ogg.opusId(), tags, opus = true))
        assertEmbeddedFatal(result, EmbeddedLyricsIssue.TOO_MANY_STRUCTURES)
        assertNotNull(result.accountedBytes)
        assertEquals(0, result.cumulativePayloadBytes)
    }

    @Test fun impossibleUnsignedVendorLengthsFailBeforeScanningOrAllocatingTheirDeclaration() = runBlocking {
        for (length in listOf(0x8000_0000L, 0xffff_ffffL)) {
            val start = binary.join("OpusTags".toByteArray(), binary.uint32(length, true), ByteArray(243))
            val bytes = binary.join(ogg.identification(ogg.opusId()), ogg.page(start, intArrayOf(255), granule = -1L, sequence = 1L))
            val stream = EmbeddedCountingStream(bytes)
            val result = EmbeddedLyricsReader().read(stream)
            assertEmbeddedFatal(result, EmbeddedLyricsIssue.METADATA_TOO_LARGE)
            assertEquals(bytes.size, stream.bytesRead)
            assertEquals(0, result.cumulativePayloadBytes)
        }
    }

    @Test fun impossibleUnsignedFieldLengthsFailBeforeAKeyOrPayloadAllocation() = runBlocking {
        for (length in listOf(0x8000_0000L, 0xffff_ffffL)) {
            val prefix = binary.join("OpusTags".toByteArray(), binary.uint32(0, true), binary.uint32(1, true), binary.uint32(length, true))
            val data = binary.join(prefix, ByteArray(255 - prefix.size))
            val bytes = binary.join(ogg.identification(ogg.opusId()), ogg.page(data, intArrayOf(255), granule = -1L, sequence = 1L))
            val result = readEmbedded(bytes)
            assertEmbeddedFatal(result, EmbeddedLyricsIssue.METADATA_TOO_LARGE)
            assertEquals(0, result.cumulativePayloadBytes)
        }
    }

    @Test fun aPacketEndProvesMissingFieldBytesBeforeAnyDeclaredPayloadIsCharged() = runBlocking {
        val tags = binary.join(
            "OpusTags".toByteArray(), binary.uint32(0, true), binary.uint32(1, true),
            binary.uint32(512_007L, true), "LYRICS=x".toByteArray(),
        )
        val limits = EmbeddedLyricsLimits(lyrics = LyricsLimits(maxAccountedBytes = 120_000L))
        val result = readEmbedded(ogg.metadata(ogg.opusId(), tags, opus = true), limits)
        assertEmbeddedFatal(result, EmbeddedLyricsIssue.TRUNCATED_METADATA)
        assertEquals(0, result.cumulativePayloadBytes)
        assertTrue(result.accountedBytes!! < 120_000L)
    }

    @Test fun anUnfinishedLargeFieldAllocatesOnlyReceivedVerifiedChunksBeforeTruncation() = runBlocking {
        val prefix = binary.join(
            "OpusTags".toByteArray(), binary.uint32(0, true), binary.uint32(1, true),
            binary.uint32(512_007L, true), "LYRICS=".toByteArray(),
        )
        val data = binary.join(prefix, ByteArray(255 - prefix.size) { 'x'.code.toByte() })
        val bytes = binary.join(ogg.identification(ogg.opusId()), ogg.page(data, intArrayOf(255), granule = -1L, sequence = 1L))
        val limits = EmbeddedLyricsLimits(lyrics = LyricsLimits(maxAccountedBytes = 120_000L))
        val result = readEmbedded(bytes, limits)
        assertEmbeddedFatal(result, EmbeddedLyricsIssue.TRUNCATED_METADATA)
        assertEquals(512_000, result.cumulativePayloadBytes)
        assertNotNull(result.accountedBytes)
        assertTrue(result.accountedBytes!! < 120_000L)
    }

    @Test fun pageBuffersAndChunkAssemblyShareTheSameRepresentationAllowance() = runBlocking {
        val value = List(14) { "x".repeat(5_000) }.joinToString("\n")
        val limits = EmbeddedLyricsLimits(lyrics = LyricsLimits(maxAccountedBytes = 120_000L))
        val result = readEmbedded(ogg.opus(listOf(binary.lyrics(value))), limits)
        assertEmbeddedFatal(result, EmbeddedLyricsIssue.MEMORY_LIMIT)
        assertTrue(result.accountedBytes!! <= 120_000L)
    }

    @Test fun aTightRepresentationBudgetRejectsPageStorageBeforeItIsAllocated() = runBlocking {
        val limits = EmbeddedLyricsLimits(lyrics = LyricsLimits(maxAccountedBytes = 30_000L))
        val stream = EmbeddedCountingStream(ogg.opus(listOf(binary.lyrics("text"))))
        val result = EmbeddedLyricsReader(limits).read(stream)
        assertEmbeddedFatal(result, EmbeddedLyricsIssue.MEMORY_LIMIT)
        assertEquals(4, stream.bytesRead)
        assertTrue(result.accountedBytes!! <= 30_000L)
    }

    @Test fun cumulativeLyricsBytesCannotRestartForTheNextField() = runBlocking {
        val limits = EmbeddedLyricsLimits(lyrics = LyricsLimits(maxInputBytes = 16))
        val fields = listOf(binary.lyrics("12345678"), binary.lyrics("123456789"))
        val result = readEmbedded(ogg.opus(fields), limits)
        assertEmbeddedFatal(result, EmbeddedLyricsIssue.LYRICS_INPUT_TOO_LARGE)
        assertEquals(8, result.cumulativePayloadBytes)
    }

    @Test fun cumulativeCandidateLimitClearsTheWholePrivateResult() = runBlocking {
        val result = readEmbedded(ogg.opus(listOf(binary.lyrics("first"), binary.lyrics("second"))), EmbeddedLyricsLimits(maxCandidates = 1))
        assertEmbeddedFatal(result, EmbeddedLyricsIssue.TOO_MANY_CANDIDATES)
        assertEquals(5, result.cumulativePayloadBytes)
    }

    @Test fun decodedLineTimelineAndWordLimitsStaySharedAcrossOggFields() = runBlocking {
        val cases = listOf(
            Triple(LyricsLimits(maxPhysicalLines = 2), listOf("first", "second\nthird"), EmbeddedLyricsIssue.TOO_MANY_LINES),
            Triple(LyricsLimits(maxTimelineEntries = 1), listOf("[00:01]first", "[00:02]second"), EmbeddedLyricsIssue.TOO_MANY_TIMED_LINES),
            Triple(LyricsLimits(maxWordEvents = 1), listOf("[00:01]<00:01>a<00:02>", "[00:02]<00:02>b<00:03>"), EmbeddedLyricsIssue.TOO_MANY_WORD_EVENTS),
        )
        for ((lyrics, values, issue) in cases) {
            val fields = values.map { binary.lyrics(it) }
            assertEmbeddedFatal(readEmbedded(ogg.opus(fields), EmbeddedLyricsLimits(lyrics = lyrics)), issue)
        }
    }

    @Test fun overlongLinesUseTheFrozenParserLimitInsteadOfASeparateOggFallback() = runBlocking {
        val result = readEmbedded(ogg.opus(listOf(binary.lyrics("abcd"))), EmbeddedLyricsLimits(lyrics = LyricsLimits(maxLineCodePoints = 3)))
        assertEmbeddedFatal(result, EmbeddedLyricsIssue.LINE_TOO_LONG)
    }

    @Test fun readOperationLimitBoundsShortProviderReadsIncludingTheCapture() = runBlocking {
        val stream = EmbeddedCountingStream(ogg.opus(listOf(binary.lyrics("text"))), shortRead = 1)
        val result = EmbeddedLyricsReader(EmbeddedLyricsLimits(maxReadOperations = 8)).read(stream)
        assertEmbeddedFatal(result, EmbeddedLyricsIssue.READ_LIMIT)
        assertEquals(8, stream.readCalls)
    }
}
