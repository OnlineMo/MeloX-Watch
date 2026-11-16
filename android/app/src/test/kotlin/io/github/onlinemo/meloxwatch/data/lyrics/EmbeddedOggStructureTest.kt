package io.github.onlinemo.meloxwatch.data.lyrics

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedOggStructureTest {
    private val binary = EmbeddedLyricsBinaryFixture
    private val ogg = OggLyricsBinaryFixture

    @Test fun partialOggCaptureAndVersionZeroHeaderAreTruncatedMetadata() = runBlocking {
        val prefix = binary.join("OggS".toByteArray(), byteArrayOf(0))
        for (length in 1..prefix.size) {
            assertEmbeddedFatal(readEmbedded(prefix.copyOf(length)), EmbeddedLyricsIssue.TRUNCATED_METADATA)
        }
    }

    @Test fun unsupportedOggVersionAndUnknownFirstCodecDoNotGuessAPacketFormat() = runBlocking {
        val unknownVersion = ogg.page(ogg.vorbisId(), flags = 2, version = 7)
        val unknownCodec = ogg.identification("Speex header".toByteArray())
        for (bytes in listOf(unknownVersion, unknownCodec)) {
            assertEmbeddedFatal(readEmbedded(bytes), EmbeddedLyricsIssue.UNSUPPORTED_CONTAINER)
        }
    }

    @Test fun crcProtectsHeaderLacingAndBodyBeforeIdentificationIsUsed() = runBlocking {
        val id = ogg.identification(ogg.vorbisId())
        val changes = listOf(
            id.copyOf().also { it[14] = (it[14].toInt() xor 1).toByte() },
            id.copyOf().also { it[18] = (it[18].toInt() xor 1).toByte() },
            id.copyOf().also { it[27] = 29 },
            id.copyOf().also { it[it.lastIndex] = 0 },
        )
        for (bytes in changes) assertEmbeddedFatal(readEmbedded(bytes), EmbeddedLyricsIssue.INVALID_OGG_CHECKSUM)
    }

    @Test fun aDamagedCommentPageCannotExposeLyricsEvenIfItsTextWasIntact() = runBlocking {
        val id = ogg.identification(ogg.opusId())
        val comments = ogg.page(ogg.opusTags(listOf(binary.lyrics("private text"))), sequence = 1L)
            .also { it[22] = (it[22].toInt() xor 1).toByte() }
        val result = readEmbedded(binary.join(id, comments))
        assertEmbeddedFatal(result, EmbeddedLyricsIssue.INVALID_OGG_CHECKSUM)
        assertEquals(0, result.cumulativePayloadBytes)
    }

    @Test fun laterContinuationCrcFailureClearsAnEarlierPrivateCandidate() = runBlocking {
        val tags = ogg.opusTags(listOf(binary.lyrics("good"), binary.lyrics("x".repeat(700))))
        val first = ogg.page(tags.copyOfRange(0, 255), intArrayOf(255), granule = -1L, sequence = 1L)
        val later = ogg.page(tags.copyOfRange(255, tags.size), flags = 1, sequence = 2L)
            .also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 1).toByte() }
        val result = readEmbedded(binary.join(ogg.identification(ogg.opusId()), first, later))
        assertEmbeddedFatal(result, EmbeddedLyricsIssue.INVALID_OGG_CHECKSUM)
        assertTrue(result.cumulativePayloadBytes >= 4)
    }

    @Test fun crcAlsoCoversAnUninterpretedSetupPacketSharingTheVorbisPage() = runBlocking {
        val comments = binary.vorbisPacket(listOf(binary.lyrics("text")))
        val bytes = ogg.metadata(ogg.vorbisId(), comments, finalPacket = byteArrayOf(5, 1, 2))
            .also { it[it.lastIndex] = 3 }
        assertEmbeddedFatal(readEmbedded(bytes), EmbeddedLyricsIssue.INVALID_OGG_CHECKSUM)
    }

    @Test fun identificationRequiresBosWithoutContinuationOrEosAndOnlyKnownFlags() = runBlocking {
        for (flags in listOf(0, 1, 3, 6, 0x82)) {
            assertEmbeddedFatal(readEmbedded(ogg.page(ogg.vorbisId(), flags = flags)), EmbeddedLyricsIssue.INVALID_CONTAINER)
        }
    }

    @Test fun bosPageContainsOneCompleteIdentificationPacketAndGranuleZero() = runBlocking {
        val id = ogg.vorbisId()
        val continued = binary.join(id, ByteArray(255 - id.size))
        val bytes = listOf(
            ogg.page(id, intArrayOf(30, 0), flags = 2),
            ogg.page(continued, intArrayOf(255), flags = 2),
            ogg.page(ByteArray(0), intArrayOf(), flags = 2),
            ogg.page(id, flags = 2, granule = -1L),
            ogg.page(id, flags = 2, granule = 1L),
        )
        for (value in bytes) assertEmbeddedFatal(readEmbedded(value), EmbeddedLyricsIssue.INVALID_CONTAINER)
    }

    @Test fun vorbisIdentificationChecksLengthTypeVersionChannelsRateBlocksAndFraming() = runBlocking {
        val invalidIds = listOf(
            ogg.vorbisId().copyOf(29), ogg.vorbisId().copyOf(31),
            ogg.vorbisId().also { it[0] = 3 }, ogg.vorbisId(channels = 0),
            ogg.vorbisId(sampleRate = 0L), ogg.vorbisId(blocks = 0xb5),
            ogg.vorbisId(blocks = 0x67), ogg.vorbisId(framing = 0),
        )
        for (id in invalidIds) assertEmbeddedFatal(readEmbedded(ogg.identification(id)), EmbeddedLyricsIssue.INVALID_CONTAINER)
        assertEmbeddedFatal(readEmbedded(ogg.identification(ogg.vorbisId(version = 1L))), EmbeddedLyricsIssue.UNSUPPORTED_CONTAINER)
    }

    @Test fun opusIdentificationChecksMandatoryFieldsAndCompatibleVersionLength() = runBlocking {
        for (id in listOf(ogg.opusId().copyOf(18), ogg.opusId(channels = 0), ogg.opusId(extra = byteArrayOf(0)))) {
            assertEmbeddedFatal(readEmbedded(ogg.identification(id)), EmbeddedLyricsIssue.INVALID_CONTAINER)
        }
        assertEmbeddedFatal(readEmbedded(ogg.identification(ogg.opusId(version = 16))), EmbeddedLyricsIssue.UNSUPPORTED_CONTAINER)
    }

    @Test fun opusMappingsCheckFamiliesCountsAndEveryEncodedChannelIndex() = runBlocking {
        val ids = listOf(
            ogg.opusId(channels = 3),
            ogg.opusId(family = 1, channels = 9),
            ogg.opusId(family = 1, streams = 0),
            ogg.opusId(family = 1, streams = 1, coupled = 2),
            ogg.opusId(family = 255, streams = 200, coupled = 100),
            ogg.opusId(family = 1, streams = 1, coupled = 0, mapping = byteArrayOf(0, 1)),
            ogg.opusId(family = 1, mapping = byteArrayOf(0)),
        )
        for (id in ids) assertEmbeddedFatal(readEmbedded(ogg.identification(id)), EmbeddedLyricsIssue.INVALID_CONTAINER)
        assertEmbeddedFatal(readEmbedded(ogg.identification(ogg.opusId(family = 2))), EmbeddedLyricsIssue.UNSUPPORTED_CONTAINER)
    }

    @Test fun aSecondBosPageCannotBeUsedAsTheCommentPacket() = runBlocking {
        val bytes = binary.join(
            ogg.identification(ogg.vorbisId()),
            ogg.page(binary.vorbisPacket(emptyList()), flags = 2, sequence = 1L),
        )
        assertEmbeddedFatal(readEmbedded(bytes), EmbeddedLyricsIssue.INVALID_CONTAINER)
    }

    @Test fun initialCommentsCannotBeAContinuedPacketOrAnEmptyPage() = runBlocking {
        val id = ogg.identification(ogg.opusId())
        for (page in listOf(
            ogg.page(ogg.opusTags(emptyList()), flags = 1, sequence = 1L),
            ogg.page(ByteArray(0), intArrayOf(), granule = -1L, sequence = 1L),
        )) assertEmbeddedFatal(readEmbedded(binary.join(id, page)), EmbeddedLyricsIssue.INVALID_CONTAINER)
    }

    @Test fun missingContinuationFlagCannotJoinAnIncompletePacket() = runBlocking {
        val tags = ogg.opusTags(listOf(binary.lyrics("x".repeat(600))))
        val bytes = binary.join(
            ogg.identification(ogg.opusId()),
            ogg.page(tags.copyOfRange(0, 255), intArrayOf(255), granule = -1L, sequence = 1L),
            ogg.page(tags.copyOfRange(255, tags.size), flags = 0, sequence = 2L),
        )
        assertEmbeddedFatal(readEmbedded(bytes), EmbeddedLyricsIssue.INVALID_CONTAINER)
    }

    @Test fun skippedDuplicateOrReversedPageSequencesAreNotReassembled() = runBlocking {
        for ((first, second) in listOf(0L to 2L, 0L to 0L, 3L to 2L)) {
            val bytes = binary.join(
                ogg.identification(ogg.opusId(), sequence = first),
                ogg.page(ogg.opusTags(emptyList()), sequence = second),
            )
            assertEmbeddedFatal(readEmbedded(bytes), EmbeddedLyricsIssue.INVALID_CONTAINER)
        }
    }

    @Test fun anotherLogicalSerialIsUnsupportedRatherThanGuessedOrMixed() = runBlocking {
        val bytes = binary.join(
            ogg.identification(ogg.opusId()),
            ogg.page(ogg.opusTags(listOf(binary.lyrics("other"))), serial = 7L, sequence = 1L),
        )
        assertEmbeddedFatal(readEmbedded(bytes), EmbeddedLyricsIssue.UNSUPPORTED_CONTAINER)
    }

    @Test fun headerPageGranulesFollowTheCodecInsteadOfBecomingLyricTimes() = runBlocking {
        val vorbis = binary.vorbisPacket(listOf(binary.lyrics("x".repeat(600))))
        val opus = ogg.opusTags(listOf(binary.lyrics("x".repeat(600))))
        val cases = listOf(
            binary.join(ogg.identification(ogg.vorbisId()), ogg.page(vorbis.copyOfRange(0, 255), intArrayOf(255), granule = -1L, sequence = 1L)),
            binary.join(ogg.identification(ogg.opusId()), ogg.page(opus.copyOfRange(0, 255), intArrayOf(255), granule = 0L, sequence = 1L)),
            binary.join(ogg.identification(ogg.opusId()), ogg.page(ogg.opusTags(emptyList()), granule = 1L, sequence = 1L)),
        )
        for (bytes in cases) assertEmbeddedFatal(readEmbedded(bytes), EmbeddedLyricsIssue.INVALID_CONTAINER)
    }

    @Test fun eosCannotCompleteAnUnterminatedCommentPacketOrEmptyContinuationPage() = runBlocking {
        val tags = ogg.opusTags(listOf(binary.lyrics("x".repeat(600))))
        val id = ogg.identification(ogg.opusId())
        val first = ogg.page(tags.copyOfRange(0, 255), intArrayOf(255), granule = -1L, sequence = 1L)
        val ended = ogg.page(tags.copyOfRange(0, 255), intArrayOf(255), flags = 4, granule = -1L, sequence = 1L)
        val empty = ogg.page(ByteArray(0), intArrayOf(), flags = 4, granule = -1L, sequence = 2L)
        assertEmbeddedFatal(readEmbedded(binary.join(id, ended)), EmbeddedLyricsIssue.TRUNCATED_METADATA)
        assertEmbeddedFatal(readEmbedded(binary.join(id, first, empty)), EmbeddedLyricsIssue.TRUNCATED_METADATA)
    }

    @Test fun missingHeadersLacesBodiesAndCommentsStayTruncatedWithoutPartialCandidates() = runBlocking {
        val id = ogg.identification(ogg.opusId())
        val bytes = ogg.opus(listOf(binary.lyrics("text")))
        for (length in listOf(20, 27, id.size - 1, id.size, id.size + 5, bytes.size - 1)) {
            assertEmbeddedFatal(readEmbedded(bytes.copyOf(length)), EmbeddedLyricsIssue.TRUNCATED_METADATA)
        }
    }

    @Test fun absentCommentMagicOrWrongCodecHeaderIsInvalidContainer() = runBlocking {
        val id = ogg.identification(ogg.opusId())
        val wrong = binary.vorbisPacket(listOf(binary.lyrics("text")))
        assertEmbeddedFatal(readEmbedded(binary.join(id, ogg.page(wrong, sequence = 1L))), EmbeddedLyricsIssue.INVALID_CONTAINER)
        val vorbis = binary.vorbisPacket(emptyList()).also { it[0] = 1 }
        assertEmbeddedFatal(readEmbedded(binary.join(ogg.identification(ogg.vorbisId()), ogg.page(vorbis, sequence = 1L))), EmbeddedLyricsIssue.INVALID_CONTAINER)
    }

    @Test fun missingVendorCountAndDeclaredFieldBytesDoNotBorrowAnotherPacket() = runBlocking {
        val payloads = listOf(
            binary.join(binary.uint32(100, true), byteArrayOf(1)),
            binary.join(binary.uint32(0, true), byteArrayOf(1)),
            binary.join(binary.uint32(0, true), binary.uint32(1, true), binary.uint32(100, true), byteArrayOf(1)),
        )
        for (payload in payloads) {
            val tags = binary.join("OpusTags".toByteArray(), payload)
            assertEmbeddedFatal(readEmbedded(ogg.metadata(ogg.opusId(), tags, opus = true)), EmbeddedLyricsIssue.TRUNCATED_METADATA)
        }
    }

    @Test fun invalidKeysMissingEqualsAndEmptyKeysFailBeforeValueParsing() = runBlocking {
        for (field in listOf(
            "LYRICS".toByteArray(), "=text".toByteArray(), binary.join(byteArrayOf(0x1f), "=text".toByteArray()),
            binary.lyrics("text", "K".repeat(129)),
        )) assertEmbeddedFatal(readEmbedded(ogg.opus(listOf(field))), EmbeddedLyricsIssue.INVALID_CONTAINER)
    }

    @Test fun invalidUtf8AndForbiddenTextEndTheAcquisitionAndClearEarlierLyrics() = runBlocking {
        for ((bytes, issue) in listOf(
            byteArrayOf(0xc3.toByte(), 0x28) to EmbeddedLyricsIssue.INVALID_ENCODING,
            byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x61, 0) to EmbeddedLyricsIssue.INVALID_ENCODING,
            "second\u0001bad".toByteArray() to EmbeddedLyricsIssue.INVALID_TEXT,
        )) {
            val fields = listOf(binary.lyrics("first"), binary.join("LYRICS=".toByteArray(), bytes))
            val result = readEmbedded(ogg.opus(fields))
            assertEmbeddedFatal(result, issue)
            assertNull(result.accountedBytes)
        }
    }

    @Test fun vorbisRequiresExactlyItsFramingByteAndThenThePacketEnd() = runBlocking {
        val valid = binary.vorbisPacket(listOf(binary.lyrics("text")))
        val packets = listOf(
            binary.vorbisPacket(listOf(binary.lyrics("text")), framing = 0),
            binary.vorbisPacket(listOf(binary.lyrics("text")), framing = 2),
            binary.vorbisPacket(listOf(binary.lyrics("text")), trailing = byteArrayOf(0)),
        )
        for (packet in packets) assertEmbeddedFatal(readEmbedded(ogg.metadata(ogg.vorbisId(), packet)), EmbeddedLyricsIssue.INVALID_CONTAINER)
        assertEmbeddedFatal(readEmbedded(ogg.metadata(ogg.vorbisId(), valid.copyOf(valid.size - 1))), EmbeddedLyricsIssue.TRUNCATED_METADATA)
    }

    @Test fun opusCommentEndMustBeTheFinalPacketBoundaryOnItsPageEvenForAnEmptyNextPacket() = runBlocking {
        val tags = ogg.opusTags(listOf(binary.lyrics("text")))
        for (following in listOf(byteArrayOf(8, 9), ByteArray(0))) {
            val result = readEmbedded(ogg.metadata(ogg.opusId(), tags, opus = true, finalPacket = following))
            assertEmbeddedFatal(result, EmbeddedLyricsIssue.INVALID_CONTAINER)
        }
    }

    @Test fun aMissingZeroLaceCannotPublishAnOtherwiseParsed255ByteComment() = runBlocking {
        val comments = binary.vorbisPacket(listOf(binary.lyrics("x".repeat(221))))
        assertEquals(255, comments.size)
        val bytes = binary.join(ogg.identification(ogg.vorbisId()), ogg.page(comments, intArrayOf(255), sequence = 1L))
        val result = readEmbedded(bytes)
        assertEmbeddedFatal(result, EmbeddedLyricsIssue.TRUNCATED_METADATA)
        assertEquals(221, result.cumulativePayloadBytes)
    }
}
