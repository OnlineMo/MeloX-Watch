package io.github.onlinemo.meloxwatch.data.lyrics

import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedFlacLyricsTest {
    private val binary = EmbeddedLyricsBinaryFixture

    @Test fun checkedInNativeFlacReadsRealLyricsCommentWithEnhancedTiming() = runBlocking {
        val result = readEmbedded(binary.resource("flac-lyrics-metadata.bin"))
        val candidate = result.defaultCandidate!!
        assertTrue(result.metadataComplete)
        assertTrue(result.diagnostics.isEmpty())
        assertEquals(EmbeddedLyricsContainer.FLAC, candidate.source.container)
        assertEquals(EmbeddedLyricsField.VORBIS_LYRICS, candidate.source.field)
        assertEquals("hi", candidate.document.timeline.line(0).text)
        assertEquals(LyricWordTiming(1_000L, 2_000L, 0, 2), candidate.document.timeline.line(0).wordTimings.single())
    }

    @Test fun checkedInVorbisCommentPacketValidatesItsActualHeaderAndFramingBit() = runBlocking {
        val bytes = binary.resource("vorbis-lyrics-comment.bin")
        val result = EmbeddedLyricsReader().readVorbisCommentPacket(ByteArrayInputStream(bytes), bytes.size.toLong())
        assertTrue(result.metadataComplete)
        assertEquals(EmbeddedLyricsContainer.VORBIS_COMMENT_PACKET, result.defaultCandidate!!.source.container)
        assertEquals("hi", result.defaultCandidate!!.document.timeline.line(0).text)
    }

    @Test fun plainUtf8LyricsHasANormalPathIndependentOfAPlaybackDecoder() = runBlocking {
        val result = readEmbedded(binary.flacComments(listOf(binary.lyrics("星空\n音乐🌙"))))
        assertEquals(LyricsKind.PLAIN_TEXT, result.defaultCandidate!!.document.kind)
        assertEquals(listOf("星空", "音乐🌙"), result.defaultCandidate!!.document.sourceLines.map { it.text })
        assertFalse(result.defaultCandidate!!.document.hasWordTimings)
        assertEquals(EmbeddedLyricsWireEncoding.UTF8, result.defaultCandidate!!.source.wireEncoding)
    }

    @Test fun exactCaseInsensitiveLyricsKeyMapsWithoutAliasGuessing() = runBlocking {
        val result = readEmbedded(binary.flacComments(listOf(
            binary.lyrics("ignored", "LYRIC"), binary.lyrics("ignored", "LRC"),
            binary.lyrics("ignored", "UNSYNCEDLYRICS"), binary.lyrics("visible", "lYrIcS"),
        )))
        assertEquals(1, result.candidates.size)
        assertEquals("visible", result.defaultCandidate!!.document.sourceLines.single().text)
        assertEquals(1, result.defaultCandidate!!.source.ordinal)
    }

    @Test fun duplicateVorbisLyricsFieldsRemainSeparateLegitimateSources() = runBlocking {
        val result = readEmbedded(binary.flacComments(listOf(binary.lyrics("first"), binary.lyrics("second"))))
        assertEquals(listOf(1, 2), result.candidates.map { it.source.ordinal })
        assertEquals(listOf("first", "second"), result.candidates.map { it.document.sourceLines.single().text })
        assertTrue(result.diagnostics.isEmpty())
        assertNotEquals(result.candidates[0].source.candidateId, result.candidates[1].source.candidateId)
    }

    @Test fun synchronizedCommentWinsBeforePlainTextAndRetainsSourceOffset() = runBlocking {
        val result = readEmbedded(binary.flacComments(listOf(binary.lyrics("plain"), binary.lyrics("[offset:100]\n[00:01]timed"))))
        val candidate = result.defaultCandidate!!
        assertEquals(2, candidate.source.ordinal)
        assertEquals(100L, candidate.document.sourceOffsetMs)
        assertEquals(0, candidate.document.timeline.position(900L)!!.anchorIndex)
        assertNull(candidate.document.timeline.position(899L))
    }

    @Test fun identicalPayloadSharesContentVersionAcrossFlacAndVorbisContainers() = runBlocking {
        val field = binary.lyrics("[00:01]same")
        val native = readEmbedded(binary.flacComments(listOf(field))).candidates.single()
        val bytes = binary.vorbisPacket(listOf(field))
        val packet = EmbeddedLyricsReader().readVorbisCommentPacket(ByteArrayInputStream(bytes), bytes.size.toLong()).candidates.single()
        assertEquals(native.source.contentSha256, packet.source.contentSha256)
        assertEquals(native.document.documentId, packet.document.documentId)
        assertNotEquals(native.source.candidateId, packet.source.candidateId)
    }

    @Test fun uninterestedCommentValuesAreSkippedWithoutTryingToDecodeThem() = runBlocking {
        val invalidTitle = binary.join("TITLE=".toByteArray(), byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0))
        val result = readEmbedded(binary.flacComments(listOf(invalidTitle, binary.lyrics("good"))))
        assertTrue(result.metadataComplete)
        assertEquals("good", result.defaultCandidate!!.document.sourceLines.single().text)
    }

    @Test fun emptyLyricsCommentIsReadableEmptyContentWithNoDefaultSelection() = runBlocking {
        val result = readEmbedded(binary.flacComments(listOf(binary.lyrics(""))))
        assertTrue(result.metadataComplete)
        assertEquals(LyricsKind.EMPTY, result.candidates.single().document.kind)
        assertNull(result.defaultCandidate)
    }

    @Test fun invalidUtf8AndUtf16BytesCannotBeRelabeledAsVorbisUtf8Lyrics() = runBlocking {
        for (value in listOf(byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x61, 0))) {
            val field = binary.join("LYRICS=".toByteArray(), value)
            assertEmbeddedFatal(readEmbedded(binary.flacComments(listOf(field))), EmbeddedLyricsIssue.INVALID_ENCODING)
        }
    }

    @Test fun malformedCommentTextInvalidatesThePrivateAcquisitionWithoutAPartialSource() = runBlocking {
        val result = readEmbedded(binary.flacComments(listOf(binary.lyrics("first"), binary.lyrics("second\u0001bad"))))
        assertEmbeddedFatal(result, EmbeddedLyricsIssue.INVALID_TEXT)
        assertNull(result.accountedBytes)
    }

    @Test fun streamInfoMustBeFirstHave34BytesAndNeverRepeat() = runBlocking {
        val wrongFirst = binary.join("fLaC".toByteArray(), binary.block(4, binary.comments(emptyList()), last = true))
        val wrongLength = binary.join("fLaC".toByteArray(), binary.block(0, ByteArray(33), last = true))
        val repeated = binary.flac(binary.streamInfo(last = true))
        for (bytes in listOf(wrongFirst, wrongLength, repeated)) assertEmbeddedFatal(readEmbedded(bytes), EmbeddedLyricsIssue.INVALID_CONTAINER)
    }

    @Test fun reservedMetadataTypesAndRepeatedCommentBlocksAreRejected() = runBlocking {
        val reserved = binary.flac(binary.block(7, ByteArray(0), last = true))
        val repeated = binary.flac(binary.block(4, binary.comments(emptyList()), last = false), binary.block(4, binary.comments(emptyList()), last = true))
        assertEmbeddedFatal(readEmbedded(reserved), EmbeddedLyricsIssue.INVALID_CONTAINER)
        assertEmbeddedFatal(readEmbedded(repeated), EmbeddedLyricsIssue.INVALID_CONTAINER)
    }

    @Test fun vorbisInvalidHeaderMissingFramingAndExtraPacketBytesAreRejected() = runBlocking {
        val valid = binary.vorbisPacket(listOf(binary.lyrics("text")))
        val wrongHeader = valid.copyOf().also { it[0] = 1 }
        val noBit = binary.vorbisPacket(listOf(binary.lyrics("text")), framing = 0)
        val extra = binary.vorbisPacket(listOf(binary.lyrics("text")), trailing = byteArrayOf(0))
        for (bytes in listOf(wrongHeader, noBit, extra)) {
            val result = EmbeddedLyricsReader().readVorbisCommentPacket(ByteArrayInputStream(bytes), bytes.size.toLong())
            assertEmbeddedFatal(result, EmbeddedLyricsIssue.INVALID_CONTAINER)
        }
    }

    @Test fun vorbisPacketStopsAtItsExactDemuxLengthAndKeepsTheCallerStreamOpen() = runBlocking {
        val packet = binary.vorbisPacket(listOf(binary.lyrics("text")))
        val tail = byteArrayOf(0x7f, 0x7e, 0x7d)
        val stream = EmbeddedCountingStream(binary.join(packet, tail))
        val result = EmbeddedLyricsReader().readVorbisCommentPacket(stream, packet.size.toLong())
        assertTrue(result.metadataComplete)
        assertEquals(packet.size, stream.bytesRead)
        assertFalse(stream.closed)
        assertEquals(0x7f, stream.read())
    }

    @Test fun flacStopsAfterLastMetadataBlockWithoutReadingAudioTail() = runBlocking {
        val metadata = binary.flacComments(listOf(binary.lyrics("text")))
        val stream = EmbeddedCountingStream(binary.join(metadata, byteArrayOf(0x7f, 0x7e)))
        assertTrue(EmbeddedLyricsReader().read(stream).metadataComplete)
        assertEquals(metadata.size, stream.bytesRead)
        assertFalse(stream.closed)
        assertEquals(0x7f, stream.read())
    }

    @Test fun oggPagesAreNotMistakenForADemuxedVorbisCommentPacket() = runBlocking {
        val bytes = "OggScontrolled fixture".toByteArray()
        assertEmbeddedFatal(readEmbedded(bytes), EmbeddedLyricsIssue.UNSUPPORTED_CONTAINER)
        assertEmbeddedFatal(EmbeddedLyricsReader().readVorbisCommentPacket(ByteArrayInputStream(bytes), bytes.size.toLong()), EmbeddedLyricsIssue.INVALID_CONTAINER)
    }

    @Test fun invalidKeyBytesMissingEqualsAndAnEmptyKeyAreRejectedBeforeValueDecoding() = runBlocking {
        for (field in listOf("LYRICS".toByteArray(), "=text".toByteArray(), binary.join(byteArrayOf(0x1f), "=text".toByteArray()))) {
            assertEmbeddedFatal(readEmbedded(binary.flacComments(listOf(field))), EmbeddedLyricsIssue.INVALID_CONTAINER)
        }
    }

    @Test fun missingVendorCountOrCommentBytesCannotEscapeTheirDeclaredBlock() = runBlocking {
        val badVendor = binary.join(binary.uint32(100, true), byteArrayOf(1))
        val missingCount = binary.join(binary.uint32(0, true), byteArrayOf(1))
        val missingField = binary.join(binary.uint32(0, true), binary.uint32(1, true), binary.uint32(100, true), byteArrayOf(1))
        for (payload in listOf(badVendor, missingCount, missingField)) {
            assertEmbeddedFatal(readEmbedded(binary.flac(binary.block(4, payload, last = true))), EmbeddedLyricsIssue.TRUNCATED_METADATA)
        }
    }
}
