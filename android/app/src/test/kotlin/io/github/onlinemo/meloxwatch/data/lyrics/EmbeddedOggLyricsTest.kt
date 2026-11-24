package io.github.onlinemo.meloxwatch.data.lyrics

import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedOggLyricsTest {
    private val binary = EmbeddedLyricsBinaryFixture
    private val ogg = OggLyricsBinaryFixture

    @Test fun vorbisPagesReadTheCheckedInCommentPayloadWithRealWordTiming() = runBlocking {
        val bytes = ogg.metadata(ogg.vorbisId(), binary.resource("vorbis-lyrics-comment.bin"))
        val result = readEmbedded(bytes)
        val candidate = result.defaultCandidate!!
        assertTrue(result.metadataComplete)
        assertTrue(result.diagnostics.isEmpty())
        assertEquals(EmbeddedLyricsContainer.OGG_VORBIS, candidate.source.container)
        assertEquals(EmbeddedLyricsField.VORBIS_LYRICS, candidate.source.field)
        assertEquals("hi", candidate.document.timeline.line(0).text)
        assertEquals(LyricWordTiming(1_000L, 2_000L, 0, 2), candidate.document.timeline.line(0).wordTimings.single())
    }

    @Test fun opusTagsHasANormalLyricsPathWithoutAVorbisFramingBit() = runBlocking {
        val result = readEmbedded(ogg.opus(listOf(binary.lyrics("[00:01]星空"))))
        assertTrue(result.metadataComplete)
        assertTrue(result.diagnostics.isEmpty())
        assertEquals(EmbeddedLyricsContainer.OGG_OPUS, result.defaultCandidate!!.source.container)
        assertEquals(EmbeddedLyricsWireEncoding.UTF8, result.defaultCandidate!!.source.wireEncoding)
        assertEquals("星空", result.defaultCandidate!!.document.timeline.line(0).text)
        assertFalse(result.defaultCandidate!!.document.hasWordTimings)
    }

    @Test fun commentsMaySpanManyVerifiedPagesWithoutBufferingTheirVendor() = runBlocking {
        val comments = binary.vorbisPacket(listOf(binary.lyrics("visible")))
        val vendor = "vendor".repeat(600).toByteArray()
        val withVendor = binary.join(byteArrayOf(3), "vorbis".toByteArray(), binary.comments(listOf(binary.lyrics("visible")), vendor), byteArrayOf(1))
        assertTrue(withVendor.size > comments.size)
        val result = readEmbedded(ogg.metadata(ogg.vorbisId(), withVendor, segmentsPerPage = 1))
        assertTrue(result.metadataComplete)
        assertEquals("visible", result.defaultCandidate!!.document.sourceLines.single().text)
    }

    @Test fun vorbisCommentEndCanShareItsVerifiedPageWithAFollowingSetupPacket() = runBlocking {
        val comments = binary.vorbisPacket(listOf(binary.lyrics("text")))
        val setupPrefix = binary.join(byteArrayOf(5), "vorbis".toByteArray(), byteArrayOf(0))
        val bytes = ogg.metadata(ogg.vorbisId(), comments, finalPacket = setupPrefix)
        val stream = EmbeddedCountingStream(binary.join(bytes, byteArrayOf(0x7f)))
        val result = EmbeddedLyricsReader().read(stream)
        assertTrue(result.metadataComplete)
        assertEquals("text", result.defaultCandidate!!.document.sourceLines.single().text)
        assertEquals(bytes.size, stream.bytesRead)
        assertEquals(0x7f, stream.read())
    }

    @Test fun vorbisExact255BytePacketWaitsForItsZeroLaceOnTheNextPage() = runBlocking {
        val comments = binary.vorbisPacket(listOf(binary.lyrics("x".repeat(221))))
        assertEquals(255, comments.size)
        val bytes = ogg.metadata(ogg.vorbisId(), comments, segmentsPerPage = 1)
        val stream = EmbeddedCountingStream(bytes)
        val result = EmbeddedLyricsReader().read(stream)
        assertTrue(result.metadataComplete)
        assertEquals(221, result.defaultCandidate!!.document.sourceLines.single().text.length)
        assertEquals(bytes.size, stream.bytesRead)
    }

    @Test fun opusExact255BytePacketAlsoNeedsAContinuedZeroLace() = runBlocking {
        val tags = ogg.opusTags(listOf(binary.lyrics("x".repeat(221))))
        assertEquals(255, tags.size)
        val bytes = ogg.metadata(ogg.opusId(), tags, opus = true, segmentsPerPage = 1)
        val result = readEmbedded(bytes)
        assertTrue(result.metadataComplete)
        assertEquals(221, result.defaultCandidate!!.document.sourceLines.single().text.length)
    }

    @Test fun anEmptyIntermediatePageKeepsThePendingOpusPacketAndSequence() = runBlocking {
        val tags = ogg.opusTags(listOf(binary.lyrics("x".repeat(600))))
        val bytes = binary.join(
            ogg.identification(ogg.opusId()),
            ogg.page(tags.copyOfRange(0, 255), intArrayOf(255), granule = -1L, sequence = 1L),
            ogg.page(ByteArray(0), intArrayOf(), flags = 0, granule = -1L, sequence = 2L),
            ogg.page(tags.copyOfRange(255, tags.size), flags = 1, sequence = 3L),
        )
        val result = readEmbedded(bytes)
        assertTrue(result.metadataComplete)
        assertEquals(600, result.defaultCandidate!!.document.sourceLines.single().text.length)
    }

    @Test fun firstSequenceIsNotForcedToZeroAndFollowingSequencesWrapUnsigned32() = runBlocking {
        val tags = ogg.opusTags(listOf(binary.lyrics("text")), tail = ByteArray(600))
        val bytes = ogg.metadata(ogg.opusId(), tags, opus = true, segmentsPerPage = 1, firstSequence = 0xffff_fffeL)
        assertTrue(readEmbedded(bytes).metadataComplete)
    }

    @Test fun aCommentCountCanCrossAPageBoundary() = runBlocking {
        val vendor = ByteArray(242) { 'v'.code.toByte() }
        val comments = binary.join(byteArrayOf(3), "vorbis".toByteArray(), binary.comments(listOf(binary.lyrics("text")), vendor), byteArrayOf(1))
        val result = readEmbedded(ogg.metadata(ogg.vorbisId(), comments, segmentsPerPage = 1))
        assertTrue(result.metadataComplete)
        assertEquals("text", result.defaultCandidate!!.document.sourceLines.single().text)
    }

    @Test fun aFieldLengthCanCrossAPageBoundary() = runBlocking {
        val vendor = ByteArray(238) { 'v'.code.toByte() }
        val comments = binary.join(byteArrayOf(3), "vorbis".toByteArray(), binary.comments(listOf(binary.lyrics("text")), vendor), byteArrayOf(1))
        val result = readEmbedded(ogg.metadata(ogg.vorbisId(), comments, segmentsPerPage = 1))
        assertTrue(result.metadataComplete)
        assertEquals("text", result.defaultCandidate!!.document.sourceLines.single().text)
    }

    @Test fun theExactAsciiLyricsKeyCanCrossAPageBoundary() = runBlocking {
        val vendor = ByteArray(234) { 'v'.code.toByte() }
        val tags = ogg.opusTags(listOf(binary.lyrics("text", "lYrIcS")), vendor = vendor)
        val result = readEmbedded(ogg.metadata(ogg.opusId(), tags, opus = true, segmentsPerPage = 1))
        assertTrue(result.metadataComplete)
        assertEquals("text", result.defaultCandidate!!.document.sourceLines.single().text)
    }

    @Test fun multibyteUtf8PayloadCanCrossBothPageAndIoChunkBoundaries() = runBlocking {
        val vendor = ByteArray(226) { 'v'.code.toByte() }
        val value = "🌙" + "星".repeat(3_000)
        val comments = binary.join(byteArrayOf(3), "vorbis".toByteArray(), binary.comments(listOf(binary.lyrics(value)), vendor), byteArrayOf(1))
        val limits = EmbeddedLyricsLimits(ioChunkBytes = 7)
        val stream = EmbeddedCountingStream(ogg.metadata(ogg.vorbisId(), comments, segmentsPerPage = 1), shortRead = 3)
        val result = EmbeddedLyricsReader(limits).read(stream)
        assertTrue(result.metadataComplete)
        assertEquals(value, result.defaultCandidate!!.document.sourceLines.single().text)
        assertTrue(stream.largestRequest <= 7)
    }

    @Test fun sourceOffsetAndContentIdentityRemainTheSameAcrossTheFourVorbisMappings() = runBlocking {
        val field = binary.lyrics("[offset:100]\n[00:01]same")
        val demuxed = binary.vorbisPacket(listOf(field))
        val candidates = listOf(
            readEmbedded(binary.flacComments(listOf(field))).candidates.single(),
            EmbeddedLyricsReader().readVorbisCommentPacket(ByteArrayInputStream(demuxed), demuxed.size.toLong()).candidates.single(),
            readEmbedded(ogg.vorbis(listOf(field))).candidates.single(),
            readEmbedded(ogg.opus(listOf(field))).candidates.single(),
        )
        assertEquals(1, candidates.map { it.source.contentSha256 }.distinct().size)
        assertEquals(1, candidates.map { it.document.documentId }.distinct().size)
        assertEquals(4, candidates.map { it.source.candidateId }.distinct().size)
        for (candidate in candidates) {
            assertEquals(100L, candidate.document.sourceOffsetMs)
            assertNull(candidate.document.timeline.position(899L))
            assertEquals(0, candidate.document.timeline.position(900L)!!.anchorIndex)
        }
    }

    @Test fun duplicateLyricsStaySeparateAndDefaultSelectionPrefersActualSynchronizedText() = runBlocking {
        val fields = listOf(binary.lyrics("plain"), binary.lyrics("[00:01]timed"), binary.lyrics("other"))
        for (bytes in listOf(ogg.vorbis(fields), ogg.opus(fields))) {
            val result = readEmbedded(bytes)
            assertTrue(result.metadataComplete)
            assertEquals(listOf(1, 2, 3), result.candidates.map { it.source.ordinal })
            assertEquals(2, result.defaultCandidate!!.source.ordinal)
            assertNotEquals(result.candidates[0].source.candidateId, result.candidates[2].source.candidateId)
        }
    }

    @Test fun uninterestedFieldsAndBinaryValuesDoNotCreateAliasLyricsSources() = runBlocking {
        val fields = listOf(
            binary.lyrics("ignored", "LRC"), binary.lyrics("ignored", "UNSYNCEDLYRICS"),
            binary.join("TITLE=".toByteArray(), byteArrayOf(0xff.toByte(), 0)), binary.lyrics("visible"),
        )
        val result = readEmbedded(ogg.opus(fields))
        assertTrue(result.metadataComplete)
        assertEquals(1, result.candidates.size)
        assertEquals(1, result.defaultCandidate!!.source.ordinal)
        assertEquals("visible", result.defaultCandidate!!.document.sourceLines.single().text)
    }

    @Test fun opusAllowsBoundedBinaryTailIncludingZeroAndNonzeroPadding() = runBlocking {
        val tail = ByteArray(70_000) { if (it % 2 == 0) 0.toByte() else 0xff.toByte() }
        val result = readEmbedded(ogg.opus(listOf(binary.lyrics("text")), tail = tail))
        assertTrue(result.metadataComplete)
        assertEquals(4, result.cumulativePayloadBytes)
        assertEquals("text", result.defaultCandidate!!.document.sourceLines.single().text)
    }

    @Test fun supportedOpusChannelFamiliesPermitDuplicateMappingsAndSilentOutput() = runBlocking {
        val ids = listOf(
            ogg.opusId(channels = 1),
            ogg.opusId(family = 1, channels = 3, streams = 2, coupled = 0, mapping = byteArrayOf(0, 1, 255.toByte())),
            ogg.opusId(family = 255, channels = 5, streams = 1, coupled = 1, mapping = byteArrayOf(0, 1, 0, 255.toByte(), 1)),
        )
        for (id in ids) {
            val result = readEmbedded(ogg.metadata(id, ogg.opusTags(listOf(binary.lyrics("text"))), opus = true))
            assertTrue(result.metadataComplete)
            assertEquals(EmbeddedLyricsContainer.OGG_OPUS, result.defaultCandidate!!.source.container)
        }
    }

    @Test fun compatibleMinorIdExtensionsAndInformationalAudioFieldsNeverShiftLyricTimes() = runBlocking {
        val id = ogg.opusId(version = 2, preSkip = 65_535, inputRate = 0L, gain = -32_768, extra = byteArrayOf(8, 9))
        val result = readEmbedded(ogg.metadata(id, ogg.opusTags(listOf(binary.lyrics("[00:01]text"))), opus = true))
        assertTrue(result.metadataComplete)
        assertNull(result.defaultCandidate!!.document.timeline.position(999L))
        assertEquals(0, result.defaultCandidate!!.document.timeline.position(1_000L)!!.anchorIndex)
    }

    @Test fun emptyLyricsAndAbsentLyricsAreCompleteMetadataWithoutAChosenSource() = runBlocking {
        for (fields in listOf(emptyList(), listOf(binary.lyrics("")))) {
            val result = readEmbedded(ogg.vorbis(fields))
            assertTrue(result.metadataComplete)
            assertNull(result.defaultCandidate)
            if (fields.isNotEmpty()) assertEquals(LyricsKind.EMPTY, result.candidates.single().document.kind)
        }
    }

    @Test fun metadataStopsAtItsLastVerifiedPageAndLeavesCallerAndAudioTailUntouched() = runBlocking {
        val metadata = ogg.opus(listOf(binary.lyrics("text")))
        val nextPage = ogg.page(byteArrayOf(8, 9), granule = 960L, sequence = 2L)
        val stream = EmbeddedCountingStream(binary.join(metadata, nextPage), shortRead = 2)
        val result = EmbeddedLyricsReader().read(stream)
        assertTrue(result.metadataComplete)
        assertEquals(metadata.size, stream.bytesRead)
        assertFalse(stream.closed)
        assertEquals('O'.code, stream.read())
    }

    @Test fun resultsExposeNoMutableCandidateDiagnosticOrDocumentCollectionAlias() = runBlocking {
        val result = readEmbedded(ogg.opus(listOf(binary.lyrics("[00:01]text"))))
        assertTrue(result.metadataComplete)
        expectEmbeddedImmutable { (result.candidates as MutableList).clear() }
        expectEmbeddedImmutable { (result.diagnostics as MutableList).add(EmbeddedLyricsDiagnostic(EmbeddedLyricsIssue.INVALID_CONTAINER)) }
        expectEmbeddedImmutable { (result.defaultCandidate!!.document.sourceLines as MutableList).clear() }
        assertEquals(1, result.candidates.size)
    }
}
