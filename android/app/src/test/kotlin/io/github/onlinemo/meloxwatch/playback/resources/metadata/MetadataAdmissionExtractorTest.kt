package io.github.onlinemo.meloxwatch.playback.resources.metadata

import android.util.Base64
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.mp3.Mp3Extractor
import io.github.onlinemo.meloxwatch.playback.QueuePlaybackIssue
import io.github.onlinemo.meloxwatch.playback.resources.MediaTransportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], manifest = Config.NONE)
class MetadataAdmissionExtractorTest {
    @Test fun id3DeclaredWholeTagIsRejectedBeforeId3PeekerPayloadAllocationEvenWithMetadataDisabled() {
        val header = AudioMetadataFixtures.id3Header(MetadataLimits.RAW_BLOCK + 1)
        for (flags in listOf(0, Mp3Extractor.FLAG_DISABLE_ID3_METADATA)) {
            val extractor = BoundedExtractor(Mp3Extractor(flags), GuardedContainer.MP3)
            ExtractorFixtureHarness(GuardedContainer.MP3, header, unknownLength = true, extractor = extractor).use { harness ->
                rejected(MetadataAdmissionIssue.RESOURCE_LIMIT) { harness.sniff() }
                assertEquals(10L, harness.transport.bytesRead)
            }
        }
    }

    @Test fun nestedChapterDepthAndTextValueCountAreBoundedBeforeId3DecoderRecursionOrLists() {
        for (tag in listOf(AudioMetadataFixtures.nestedId3(9), AudioMetadataFixtures.manyId3Values(130))) {
            ExtractorFixtureHarness(GuardedContainer.MP3, AudioMetadataFixtures.mp3(tag)).use { harness ->
                rejected(MetadataAdmissionIssue.RESOURCE_LIMIT) { harness.sniff() }
            }
        }
    }

    @Test fun mlltReferenceCountsAreCheckedBeforeItsTwoDeviationArrays() {
        val references = ByteArray((MetadataLimits.SEEK_POINTS + 4) / 4)
        val payload = AudioMetadataFixtures.be16(1) + AudioMetadataFixtures.be24(1) + AudioMetadataFixtures.be24(1) +
            byteArrayOf(1, 1) + references
        val frame = AudioMetadataFixtures.id3Frame("MLLT", payload)
        val tag = AudioMetadataFixtures.id3Header(frame.size) + frame
        ExtractorFixtureHarness(GuardedContainer.MP3, AudioMetadataFixtures.mp3(tag)).use { harness ->
            rejected(MetadataAdmissionIssue.RESOURCE_LIMIT) { harness.sniff() }
        }
    }

    @Test fun malformedSmallId3FrameCannotBorrowBytesBeyondItsDeclaredTag() {
        val frame = "TIT2".toByteArray() + AudioMetadataFixtures.syncSafe(100) + byteArrayOf(0, 0, 0)
        val tag = AudioMetadataFixtures.id3Header(frame.size) + frame
        ExtractorFixtureHarness(GuardedContainer.MP3, AudioMetadataFixtures.mp3(tag)).use { harness ->
            rejected(MetadataAdmissionIssue.MALFORMED) { harness.sniff() }
        }
    }

    @Test fun nativeFlacWholeBlockLengthIsCheckedAtTheReturnedHeader() {
        val header = byteArrayOf(0x84.toByte()) + AudioMetadataFixtures.be24(MetadataLimits.RAW_BLOCK + 1)
        val raw = "fLaC".toByteArray() + AudioMetadataFixtures.block(0, AudioMetadataFixtures.flacInfo(48), last = false) + header
        ExtractorFixtureHarness(GuardedContainer.FLAC, raw, unknownLength = true).use { harness ->
            rejected(MetadataAdmissionIssue.RESOURCE_LIMIT) { harness.drain() }
            assertEquals(raw.size.toLong(), harness.transport.bytesRead)
            assertTrue(harness.output.audio().formats.isEmpty())
        }
    }

    @Test fun tinyFlacCommentPayloadCannotCreateItsClaimedCommentArray() {
        val comments = AudioMetadataFixtures.le32(0) + AudioMetadataFixtures.le32(65_537)
        val raw = AudioMetadataFixtures.flac(AudioMetadataFixtures.block(4, comments))
        expect(GuardedContainer.FLAC, raw, MetadataAdmissionIssue.RESOURCE_LIMIT)
    }

    @Test fun tinyPicturePayloadAndBase64PictureCannotAllocateAClaimedImageBeyondTheirExtent() {
        val picture = AudioMetadataFixtures.picture(byteArrayOf(1), declaredSize = 65_536)
        expect(GuardedContainer.FLAC, AudioMetadataFixtures.flac(AudioMetadataFixtures.block(6, picture)), MetadataAdmissionIssue.MALFORMED)
        val encoded = Base64.encodeToString(picture, Base64.NO_WRAP)
        val comments = AudioMetadataFixtures.comments(listOf("METADATA_BLOCK_PICTURE=$encoded"))
        expect(GuardedContainer.OGG, AudioMetadataFixtures.opusPackets(comments), MetadataAdmissionIssue.MALFORMED)
    }

    @Test fun continuedOggPacketsAreLimitedBeforeTheSeventeenthFullPageCanExpandPayload() {
        val raw = AudioMetadataFixtures.oversizedContinuedOpusTag()
        ExtractorFixtureHarness(GuardedContainer.OGG, raw).use { harness ->
            rejected(MetadataAdmissionIssue.RESOURCE_LIMIT) { harness.drain() }
            assertTrue(harness.transport.bytesRead < raw.size)
        }
    }

    @Test fun repeatedEmptyOggPagesHaveABoundedNoPacketWindow() {
        expect(GuardedContainer.OGG, AudioMetadataFixtures.emptyOpusPages(129), MetadataAdmissionIssue.RESOURCE_LIMIT)
    }

    @Test fun tinyVorbisCommentsAndSetupUseActualPacketExtentBeforeCountsOrEntryLoops() {
        val comments = AudioMetadataFixtures.le32(0) + AudioMetadataFixtures.le32(65_537)
        expect(GuardedContainer.OGG, AudioMetadataFixtures.vorbisPackets(commentBody = comments), MetadataAdmissionIssue.RESOURCE_LIMIT)
        expect(GuardedContainer.OGG, AudioMetadataFixtures.vorbisPackets(setup = AudioMetadataFixtures.vorbisSetup(65_537)),
            MetadataAdmissionIssue.RESOURCE_LIMIT)
    }

    @Test fun wavFmtLengthIsCheckedBeforeTheExtraDataArray() {
        val raw = "RIFF".toByteArray() + AudioMetadataFixtures.le32(12) + "WAVEfmt ".toByteArray() +
            AudioMetadataFixtures.le32(65_537)
        expect(GuardedContainer.WAVE, raw, MetadataAdmissionIssue.RESOURCE_LIMIT, unknownLength = true)
    }

    @Test fun mp4ParsedLeafLengthAndExpandedSniffFtypAreCheckedBeforeScratchAllocation() {
        val leaf = AudioMetadataFixtures.be32(MetadataLimits.RAW_BLOCK + 1) + "keys".toByteArray()
        expect(GuardedContainer.MP4, leaf, MetadataAdmissionIssue.RESOURCE_LIMIT, unknownLength = true)
        val sniff = AudioMetadataFixtures.be32(65_536) + "moov".toByteArray() + AudioMetadataFixtures.be32(8192) + "ftyp".toByteArray()
        for (kind in listOf(GuardedContainer.MP4, GuardedContainer.FRAGMENTED_MP4)) {
            ExtractorFixtureHarness(kind, sniff, unknownLength = true).use { harness ->
                rejected(MetadataAdmissionIssue.RESOURCE_LIMIT) { harness.sniff() }
                assertEquals(16L, harness.transport.bytesRead)
            }
        }
    }

    @Test fun mp4KeysAndSampleSizeCountsCannotReachBoxParserArrays() {
        expect(GuardedContainer.MP4, M4aMetadataFixtures.movie(metadata = M4aMetadataFixtures.metadata(keysCount = 65_537)),
            MetadataAdmissionIssue.RESOURCE_LIMIT)
        expect(GuardedContainer.MP4, M4aMetadataFixtures.movie(sampleSizeBox = M4aMetadataFixtures.stsz(MetadataLimits.SAMPLES + 1)),
            MetadataAdmissionIssue.RESOURCE_LIMIT)
        val compact = AudioMetadataFixtures.box("stz2", ByteArray(7) + byteArrayOf(16) + AudioMetadataFixtures.be32(MetadataLimits.SAMPLES + 1))
        expect(GuardedContainer.MP4, M4aMetadataFixtures.movie(sampleSizeBox = compact), MetadataAdmissionIssue.RESOURCE_LIMIT)
    }

    @Test fun editListCountExpansionAndMovieAggregateStayWithinOneRetainedTableBudget() {
        expect(GuardedContainer.MP4, M4aMetadataFixtures.movie(editList = M4aMetadataFixtures.edits(257)), MetadataAdmissionIssue.RESOURCE_LIMIT)
        expect(GuardedContainer.MP4, M4aMetadataFixtures.movie(sampleCount = 100_000, editList = M4aMetadataFixtures.edits(3)),
            MetadataAdmissionIssue.RESOURCE_LIMIT)
        expect(GuardedContainer.MP4, M4aMetadataFixtures.movieWithTwoClaimedTracks(200_000), MetadataAdmissionIssue.RESOURCE_LIMIT)
    }

    @Test fun duplicateSampleSizeTablesCannotReplaceTheBudgetOfTheFirstParsedTable() {
        val duplicate = M4aMetadataFixtures.stsz(1)
        expect(GuardedContainer.MP4, M4aMetadataFixtures.movie(duplicateTable = duplicate), MetadataAdmissionIssue.MALFORMED)
    }

    @Test fun trunCountAndCrossTrackFragmentTotalAreRejectedBeforeFragmentInitTables() {
        expect(GuardedContainer.FRAGMENTED_MP4, M4aMetadataFixtures.fragmented(frames = 1, trunCount = MetadataLimits.SAMPLES + 1),
            MetadataAdmissionIssue.RESOURCE_LIMIT)
        val twoTracks = M4aMetadataFixtures.init(trackCount = 2) + M4aMetadataFixtures.twoTrackFragment(200_000)
        expect(GuardedContainer.FRAGMENTED_MP4, twoTracks, MetadataAdmissionIssue.RESOURCE_LIMIT)
    }

    @Test fun seekAcrossFragmentsStillCountsRetainedPerTrackTablePeaks() {
        val initial = M4aMetadataFixtures.init(trackCount = 2)
        val first = M4aMetadataFixtures.fragment(0, count = 200_000, track = 1, materializeSamples = true)
        val second = M4aMetadataFixtures.fragment(1, count = 100_000, track = 2)
        ExtractorFixtureHarness(GuardedContainer.FRAGMENTED_MP4, initial + first + second).use { harness ->
            harness.start()
            assertEquals(Extractor.RESULT_CONTINUE, harness.readStep())
            assertEquals(1, harness.output.tracks.values.sumOf { it.sampleSizes.size })
            rejected(MetadataAdmissionIssue.RESOURCE_LIMIT) { harness.seekAndDrain((initial.size + first.size).toLong(), 1024) }
        }
    }

    @Test fun esdsVariableLengthAndNestedCoverDataAreValidatedInsideTheirSmallOuterLeaf() {
        expect(GuardedContainer.MP4, M4aMetadataFixtures.movie(esds = M4aMetadataFixtures.esds(hugeSpecific = true)),
            MetadataAdmissionIssue.RESOURCE_LIMIT)
        expect(GuardedContainer.MP4, M4aMetadataFixtures.movie(metadata = M4aMetadataFixtures.coverWithBadChildLength()),
            MetadataAdmissionIssue.MALFORMED)
    }

    @Test fun opaqueUuidStaysAvailableAndKnownPiffEncryptionHasAnExplicitUnsupportedSurface() {
        val opaque = AudioMetadataFixtures.box("uuid", ByteArray(16))
        ExtractorFixtureHarness(GuardedContainer.FRAGMENTED_MP4, M4aMetadataFixtures.init() + M4aMetadataFixtures.fragment(0, uuid = opaque)).use { harness ->
            assertEquals(listOf(6), harness.drain().audio().sampleSizes)
        }
        val piff = byteArrayOf(-94, 57, 79, 82, 90, -101, 79, 20, -94, 68, 108, 66, 124, 100, -115, -12)
        val raw = M4aMetadataFixtures.init() + M4aMetadataFixtures.fragment(0, uuid = AudioMetadataFixtures.box("uuid", piff))
        expect(GuardedContainer.FRAGMENTED_MP4, raw, MetadataAdmissionIssue.UNSUPPORTED_SURFACE)
    }

    @Test fun inbandId3IsRejectedBeforeAnAdtsMetadataSampleIsCommitted() {
        val raw = AudioMetadataFixtures.adts(inband = AudioMetadataFixtures.manyId3Values(130))
        ExtractorFixtureHarness(GuardedContainer.ADTS, raw, maximumRead = 3).use { harness ->
            rejected(MetadataAdmissionIssue.RESOURCE_LIMIT) { harness.drain() }
            assertTrue(harness.output.tracks.values.filter { it.type == C.TRACK_TYPE_METADATA }.all { it.sampleSizes.isEmpty() })
        }
    }

    private fun expect(kind: GuardedContainer, raw: ByteArray, issue: MetadataAdmissionIssue, unknownLength: Boolean = false) {
        ExtractorFixtureHarness(kind, raw, unknownLength).use { harness -> rejected(issue) { harness.drain() } }
    }

    private fun rejected(issue: MetadataAdmissionIssue, action: () -> Any?) {
        try {
            action()
            fail("Expected admission before delegated allocation")
        } catch (error: MediaTransportException) {
            assertEquals(if (issue == MetadataAdmissionIssue.MALFORMED) QueuePlaybackIssue.SOURCE_DAMAGED else QueuePlaybackIssue.SOURCE_UNSUPPORTED,
                error.issue)
            assertEquals(issue, (error.cause as MetadataAdmissionException).issue)
        }
    }
}
