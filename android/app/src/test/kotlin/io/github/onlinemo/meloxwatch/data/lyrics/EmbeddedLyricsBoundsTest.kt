package io.github.onlinemo.meloxwatch.data.lyrics

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedLyricsBoundsTest {
    private val binary = EmbeddedLyricsBinaryFixture

    @Test fun independentBinaryMetadataFixturesHavePinnedBytes() {
        val expected = mapOf(
            "id3-v23-uslt-latin1.bin" to "20b8b2ac6f40772f68317e222e8ba27d0a902f80078b4ab7fc71925609271fd4",
            "id3-v24-sylt-utf8.bin" to "111081019120ca71ddb72deee08b293e2cc257ef62c73f9dd314745b2697bfce",
            "flac-lyrics-metadata.bin" to "fb8a1733079cbad3ee78f6e683ea448a98eaa5ffc1c5242c711909ad13629c92",
            "vorbis-lyrics-comment.bin" to "99828d3486cc3750d9b496122d202ef9c43da3ccc137b5db98c299307d541d21",
        )
        for ((name, hash) in expected) assertEquals(hash, binary.sha256(binary.resource(name)))
    }

    @Test fun allConfiguredLimitsCanOnlyTightenTheirFrozenCeilings() {
        for (build in listOf<() -> Unit>(
            { EmbeddedLyricsLimits(maxMetadataBytes = EmbeddedLyricsLimits.METADATA_BYTES + 1) },
            { EmbeddedLyricsLimits(maxStructures = EmbeddedLyricsLimits.STRUCTURES + 1) },
            { EmbeddedLyricsLimits(maxCandidates = EmbeddedLyricsLimits.CANDIDATES + 1) },
            { EmbeddedLyricsLimits(maxReadOperations = EmbeddedLyricsLimits.READ_OPERATIONS + 1) },
            { EmbeddedLyricsLimits(ioChunkBytes = EmbeddedLyricsLimits.IO_CHUNK_BYTES + 1) },
            { EmbeddedLyricsLimits(maxDescriptorCodePoints = EmbeddedLyricsLimits.DESCRIPTOR_CODE_POINTS + 1) },
            { EmbeddedLyricsLimits(maxVorbisKeyBytes = EmbeddedLyricsLimits.VORBIS_KEY_BYTES + 1) },
            { EmbeddedLyricsLimits(maxCandidates = 0) },
        )) expectLyricsArgumentError(build)
    }

    @Test fun wholeId3MetadataSpanIsRejectedBeforePayloadAllocation() = runBlocking {
        val bytes = binary.id3(binary.frame("USLT", binary.uslt("text")))
        val stream = EmbeddedCountingStream(bytes)
        val result = EmbeddedLyricsReader(EmbeddedLyricsLimits(maxMetadataBytes = bytes.size - 1)).read(stream)
        assertEmbeddedFatal(result, EmbeddedLyricsIssue.METADATA_TOO_LARGE)
        assertEquals(10, stream.bytesRead)
        assertEquals(0, result.cumulativePayloadBytes)
    }

    @Test fun maximumUnsignedV23FrameSizeCannotAllocateOrConsumeBeyondTheTag() = runBlocking {
        val bytes = binary.id3(binary.frame("USLT", byteArrayOf(0), declaredSize = 0xffff_ffffL))
        val stream = EmbeddedCountingStream(bytes)
        val result = EmbeddedLyricsReader().read(stream)
        assertEmbeddedFatal(result, EmbeddedLyricsIssue.TRUNCATED_METADATA)
        assertEquals(20, stream.bytesRead)
        assertEquals(0, result.cumulativePayloadBytes)
    }

    @Test fun malformedSynchsafeHeaderAndV24FrameSizesAreRejected() = runBlocking {
        val original = binary.id3(binary.frame("USLT", binary.uslt("text"), 4), version = 4)
        val tag = original.copyOf().also { it[6] = 0x80.toByte() }
        val frame = original.copyOf().also { it[14] = 0x80.toByte() }
        assertEmbeddedFatal(readEmbedded(tag), EmbeddedLyricsIssue.INVALID_CONTAINER)
        assertEmbeddedFatal(readEmbedded(frame), EmbeddedLyricsIssue.INVALID_CONTAINER)
    }

    @Test fun id3VersionRevisionAndEveryTagTransformationRemainExplicitlyUnsupported() = runBlocking {
        assertEmbeddedFatal(readEmbedded(binary.id3(version = 2)), EmbeddedLyricsIssue.UNSUPPORTED_ID3_VERSION)
        assertEmbeddedFatal(readEmbedded(binary.id3(revision = 1)), EmbeddedLyricsIssue.UNSUPPORTED_ID3_VERSION)
        for (flag in listOf(0x80, 0x40, 0x20, 0x10, 0x01)) {
            assertEmbeddedFatal(readEmbedded(binary.id3(flags = flag)), EmbeddedLyricsIssue.UNSUPPORTED_ID3_TAG_FLAGS)
        }
    }

    @Test fun unknownStatusBitsAreNotTreatedLikeValidPreservationFlags() = runBlocking {
        for (version in listOf(3, 4)) {
            val result = readEmbedded(binary.id3(binary.frame("USLT", binary.uslt("text"), version, status = 1), version = version))
            assertEmbeddedCandidateFailure(result, EmbeddedLyricsIssue.UNSUPPORTED_ID3_FRAME_FLAGS)
        }
    }

    @Test fun zeroFrameLengthAndNonAsciiFrameIdentifiersInvalidateTheContainer() = runBlocking {
        assertEmbeddedFatal(readEmbedded(binary.id3(binary.frame("USLT", ByteArray(0)))), EmbeddedLyricsIssue.INVALID_ID3_FRAME)
        assertEmbeddedFatal(readEmbedded(binary.id3(binary.frame("uslt", binary.uslt("text")))), EmbeddedLyricsIssue.INVALID_ID3_FRAME)
    }

    @Test fun zeroPaddingIsAcceptedButMalformedTrailingPaddingDiscardsAnEarlierCandidate() = runBlocking {
        val frame = binary.frame("USLT", binary.uslt("text"))
        assertTrue(readEmbedded(binary.id3(frame, padding = ByteArray(13))).metadataComplete)
        assertEmbeddedFatal(readEmbedded(binary.id3(frame, padding = byteArrayOf(0, 1, 0))), EmbeddedLyricsIssue.INVALID_ID3_FRAME)
    }

    @Test fun eofInsideADeclaredTagCannotPublishAnEarlierCompleteCandidate() = runBlocking {
        val bytes = binary.id3(binary.frame("USLT", binary.uslt("first", descriptor = "one")),
            binary.frame("USLT", binary.uslt("second", descriptor = "two")))
        assertEmbeddedFatal(readEmbedded(bytes.copyOf(bytes.size - 1)), EmbeddedLyricsIssue.TRUNCATED_METADATA)
        assertEmbeddedFatal(readEmbedded("ID3".toByteArray()), EmbeddedLyricsIssue.TRUNCATED_METADATA)
        assertEmbeddedFatal(readEmbedded("fLa".toByteArray()), EmbeddedLyricsIssue.TRUNCATED_METADATA)
    }

    @Test fun rawLyricsAllowanceIsCumulativeIncludingARejectedCandidate() = runBlocking {
        val first = binary.uslt("a", language = "e1g")
        val second = binary.uslt("b")
        val bytes = binary.id3(binary.frame("USLT", first), binary.frame("USLT", second))
        val exact = EmbeddedLyricsLimits(lyrics = LyricsLimits(maxInputBytes = first.size + second.size))
        val result = readEmbedded(bytes, exact)
        assertTrue(result.metadataComplete)
        assertEquals(first.size + second.size, result.cumulativePayloadBytes)
        assertEquals("b", result.defaultCandidate!!.document.sourceLines.single().text)
        assertEquals(2, result.defaultCandidate!!.source.ordinal)
        assertEmbeddedFatal(readEmbedded(bytes, exact.copy(lyrics = exact.lyrics.copy(maxInputBytes = first.size + second.size - 1))),
            EmbeddedLyricsIssue.LYRICS_INPUT_TOO_LARGE)
    }

    @Test fun unsupportedTransformedLyricsStillConsumesTheRawLyricsAllowance() = runBlocking {
        val payload = binary.uslt("text")
        val bytes = binary.id3(binary.frame("USLT", payload, format = 0x80), binary.frame("USLT", payload))
        val result = readEmbedded(bytes, EmbeddedLyricsLimits(lyrics = LyricsLimits(maxInputBytes = payload.size * 2 - 1)))
        assertEmbeddedFatal(result, EmbeddedLyricsIssue.LYRICS_INPUT_TOO_LARGE)
        assertEquals(EmbeddedLyricsDiagnostic(EmbeddedLyricsIssue.UNSUPPORTED_ID3_FRAME_FLAGS, 1), result.diagnostics.first())
    }

    @Test fun candidateOverflowRejectsTheAcquisitionInsteadOfSilentlyReturningTheFirstSource() = runBlocking {
        val bytes = binary.id3(binary.frame("USLT", binary.uslt("one", descriptor = "one")),
            binary.frame("USLT", binary.uslt("two", descriptor = "two")))
        assertEmbeddedFatal(readEmbedded(bytes, EmbeddedLyricsLimits(maxCandidates = 1)), EmbeddedLyricsIssue.TOO_MANY_CANDIDATES)
    }

    @Test fun metadataStructureBudgetIncludesIgnoredFramesAndFlacCommentFields() = runBlocking {
        val id3 = binary.id3(binary.frame("APIC", byteArrayOf(1)), binary.frame("USLT", binary.uslt("text")))
        assertEmbeddedFatal(readEmbedded(id3, EmbeddedLyricsLimits(maxStructures = 1)), EmbeddedLyricsIssue.TOO_MANY_STRUCTURES)
        val flac = binary.flacComments(listOf(binary.lyrics("text")))
        assertTrue(readEmbedded(flac, EmbeddedLyricsLimits(maxStructures = 3)).metadataComplete)
        assertEmbeddedFatal(readEmbedded(flac, EmbeddedLyricsLimits(maxStructures = 2)), EmbeddedLyricsIssue.TOO_MANY_STRUCTURES)
    }

    @Test fun physicalLineCountIsSharedAcrossOtherwiseValidId3Candidates() = runBlocking {
        val bytes = binary.id3(binary.frame("USLT", binary.uslt("a\nb", descriptor = "one")),
            binary.frame("USLT", binary.uslt("c\nd", descriptor = "two")))
        assertTrue(readEmbedded(bytes, EmbeddedLyricsLimits(lyrics = LyricsLimits(maxPhysicalLines = 4))).metadataComplete)
        assertEmbeddedFatal(readEmbedded(bytes, EmbeddedLyricsLimits(lyrics = LyricsLimits(maxPhysicalLines = 3))), EmbeddedLyricsIssue.TOO_MANY_LINES)
    }

    @Test fun timelineEntryCountIsSharedAcrossSynchronizedCandidates() = runBlocking {
        val frames = (1..3).map { index -> binary.frame("SYLT", binary.sylt(listOf("line" to 1_000L), descriptor = "source$index"), 4) }
        val bytes = binary.id3(*frames.toTypedArray(), version = 4)
        assertEmbeddedFatal(readEmbedded(bytes, EmbeddedLyricsLimits(lyrics = LyricsLimits(maxTimelineEntries = 2))), EmbeddedLyricsIssue.TOO_MANY_TIMED_LINES)
    }

    @Test fun rawSyltEventsIncludeEmptyAndEqualTimeEventsInTheirBound() = runBlocking {
        val events = listOf("a" to 1_000L, "" to 1_000L, "b" to 1_000L)
        val bytes = binary.id3(binary.frame("SYLT", binary.sylt(events), 4), version = 4)
        assertEmbeddedFatal(readEmbedded(bytes, EmbeddedLyricsLimits(lyrics = LyricsLimits(maxWordEvents = 2))), EmbeddedLyricsIssue.TOO_MANY_WORD_EVENTS)
    }

    @Test fun wordOutputAlsoBoundsMultipleRowsInsideOneTimestampedFragment() = runBlocking {
        val bytes = binary.id3(binary.frame("SYLT", binary.sylt(listOf("a\nb\nc" to 1_000L)), 4), version = 4)
        assertEmbeddedFatal(readEmbedded(bytes, EmbeddedLyricsLimits(lyrics = LyricsLimits(maxWordEvents = 2))), EmbeddedLyricsIssue.TOO_MANY_WORD_EVENTS)
    }

    @Test fun lineLengthUsesUnicodeCodePointsRatherThanSplittingSurrogatePairs() = runBlocking {
        val limits = EmbeddedLyricsLimits(lyrics = LyricsLimits(maxLineCodePoints = 2))
        val exact = binary.id3(binary.frame("USLT", binary.uslt("🌙🌙", encoding = 3), 4), version = 4)
        val tooLong = binary.id3(binary.frame("USLT", binary.uslt("🌙🌙🌙", encoding = 3), 4), version = 4)
        assertEquals("🌙🌙", readEmbedded(exact, limits).defaultCandidate!!.document.sourceLines.single().text)
        assertEmbeddedFatal(readEmbedded(tooLong, limits), EmbeddedLyricsIssue.LINE_TOO_LONG)
    }

    @Test fun descriptorBoundIsCheckedForRealUnicodeText() = runBlocking {
        val limits = EmbeddedLyricsLimits(maxDescriptorCodePoints = 2)
        val exact = binary.id3(binary.frame("USLT", binary.uslt("text", encoding = 3, descriptor = "🌙星"), 4), version = 4)
        val tooLong = binary.id3(binary.frame("USLT", binary.uslt("text", encoding = 3, descriptor = "🌙星空"), 4), version = 4)
        assertEquals("🌙星", readEmbedded(exact, limits).defaultCandidate!!.source.descriptor)
        assertEmbeddedCandidateFailure(readEmbedded(tooLong, limits), EmbeddedLyricsIssue.DESCRIPTOR_TOO_LONG)
    }

    @Test fun cumulativeResidentAndDecoderBudgetDoesNotResetForEachValidCandidate() = runBlocking {
        val text = "0123456789\n".repeat(20)
        val one = binary.frame("USLT", binary.uslt(text, descriptor = "one"))
        val two = binary.frame("USLT", binary.uslt(text, descriptor = "two"))
        val limits = EmbeddedLyricsLimits(lyrics = LyricsLimits(maxAccountedBytes = 14_000L), ioChunkBytes = 32)
        assertTrue(readEmbedded(binary.id3(one), limits).metadataComplete)
        assertTrue(readEmbedded(binary.id3(two), limits).metadataComplete)
        assertEmbeddedFatal(readEmbedded(binary.id3(one, two), limits), EmbeddedLyricsIssue.MEMORY_LIMIT)
    }

    @Test fun frozenVorbisParserReceivesTheRemainingSharedMemoryAndLineBudget() = runBlocking {
        val fields = listOf(binary.lyrics("a\nb"), binary.lyrics("c\nd"))
        val bytes = binary.flacComments(fields)
        assertTrue(readEmbedded(bytes, EmbeddedLyricsLimits(lyrics = LyricsLimits(maxPhysicalLines = 4))).metadataComplete)
        assertEmbeddedFatal(readEmbedded(bytes, EmbeddedLyricsLimits(lyrics = LyricsLimits(maxPhysicalLines = 3))), EmbeddedLyricsIssue.TOO_MANY_LINES)
        val small = EmbeddedLyricsLimits(lyrics = LyricsLimits(maxAccountedBytes = 1), ioChunkBytes = 1)
        assertEmbeddedFatal(readEmbedded(bytes, small), EmbeddedLyricsIssue.MEMORY_LIMIT)
    }

    @Test fun largeUnrelatedFramesAreSkippedInFixedChunksAndNeverChargedAsLyrics() = runBlocking {
        val payload = binary.uslt("good")
        val metadata = binary.id3(binary.frame("APIC", ByteArray(50_000) { 1 }), binary.frame("USLT", payload))
        val stream = EmbeddedCountingStream(binary.join(metadata, byteArrayOf(0x7f)), shortRead = 17)
        val result = EmbeddedLyricsReader(EmbeddedLyricsLimits(ioChunkBytes = 64)).read(stream)
        assertEquals("good", result.defaultCandidate!!.document.sourceLines.single().text)
        assertEquals(payload.size, result.cumulativePayloadBytes)
        assertTrue(stream.largestRequest <= 64)
        assertEquals(metadata.size, stream.bytesRead)
        assertFalse(stream.closed)
        assertEquals(0x7f, stream.read())
    }

    @Test fun zeroProgressInputIsBoundedByReadOperationsWithoutAnInfiniteLoop() = runBlocking {
        var calls = 0
        val stream = object : InputStream() {
            override fun read(): Int = 0
            override fun read(destination: ByteArray, offset: Int, length: Int): Int {
                calls++
                return 0
            }
        }
        val result = EmbeddedLyricsReader(EmbeddedLyricsLimits(maxReadOperations = 3)).read(stream)
        assertEmbeddedFatal(result, EmbeddedLyricsIssue.READ_LIMIT)
        assertEquals(3, calls)
    }

    @Test fun oneByteShortReadsStillParseRealBinaryFramesWithinTheOperationBound() = runBlocking {
        val bytes = binary.id3(binary.frame("USLT", binary.uslt("text")))
        val stream = EmbeddedCountingStream(bytes, shortRead = 1)
        assertEquals("text", EmbeddedLyricsReader().read(stream).defaultCandidate!!.document.sourceLines.single().text)
        assertEquals(bytes.size, stream.bytesRead)
    }

    @Test fun vorbisUnsignedVendorLengthAndCommentCountAreCheckedBeforeAllocation() = runBlocking {
        val vendor = binary.join(binary.uint32(0xffff_ffffL, true), binary.uint32(0, true))
        assertEmbeddedFatal(readEmbedded(binary.flac(binary.block(4, vendor, last = true))), EmbeddedLyricsIssue.TRUNCATED_METADATA)
        val count = binary.comments(emptyList(), count = 0xffff_ffffL)
        assertEmbeddedFatal(readEmbedded(binary.flac(binary.block(4, count, last = true))), EmbeddedLyricsIssue.TOO_MANY_STRUCTURES)
    }

    @Test fun vorbisKeyLimitAllowsItsBoundaryAndRejectsExtraBytes() = runBlocking {
        val bytes = binary.flacComments(listOf(binary.lyrics("text")))
        assertEquals("text", readEmbedded(bytes, EmbeddedLyricsLimits(maxVorbisKeyBytes = 6)).defaultCandidate!!.document.sourceLines.single().text)
        assertEmbeddedFatal(readEmbedded(bytes, EmbeddedLyricsLimits(maxVorbisKeyBytes = 5)), EmbeddedLyricsIssue.INVALID_CONTAINER)
    }

    @Test fun standalonePacketDeclaredSpanCannotReadPastTheAcquisitionBound() = runBlocking {
        val packet = binary.vorbisPacket(listOf(binary.lyrics("text")))
        val stream = EmbeddedCountingStream(packet)
        val result = EmbeddedLyricsReader(EmbeddedLyricsLimits(maxMetadataBytes = packet.size - 1))
            .readVorbisCommentPacket(stream, packet.size.toLong())
        assertEmbeddedFatal(result, EmbeddedLyricsIssue.METADATA_TOO_LARGE)
        assertEquals(0, stream.bytesRead)
        assertEmbeddedFatal(EmbeddedLyricsReader().readVorbisCommentPacket(ByteArrayInputStream(packet), -1), EmbeddedLyricsIssue.INVALID_CONTAINER)
    }
}
