package io.github.onlinemo.meloxwatch.data.lyrics

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedId3LyricsTest {
    private val binary = EmbeddedLyricsBinaryFixture

    @Test fun checkedInV23Latin1UsltHasRealUnicodeAndLiteralBracketText() = runBlocking {
        val result = readEmbedded(binary.resource("id3-v23-uslt-latin1.bin"))
        val candidate = result.defaultCandidate!!
        assertTrue(result.metadataComplete)
        assertTrue(result.diagnostics.isEmpty())
        assertEquals(LyricsKind.PLAIN_TEXT, candidate.document.kind)
        assertEquals(listOf("Café", "[00:01]x"), candidate.document.sourceLines.map { it.text })
        assertEquals(0, candidate.document.timeline.size)
        assertEquals(EmbeddedLyricsWireEncoding.ISO_8859_1, candidate.source.wireEncoding)
        assertEquals(LyricsEncoding.UTF8, candidate.document.encoding)
        assertEquals("eng", candidate.source.language)
    }

    @Test fun usltDoesNotInterpretOffsetMetadataOrAngleWordTags() = runBlocking {
        val text = "[offset:1200]\n[00:01]<00:01>literal<00:02>"
        val result = readEmbedded(binary.id3(binary.frame("USLT", binary.uslt(text))))
        val document = result.defaultCandidate!!.document
        assertEquals(text.split('\n'), document.sourceLines.map { it.text })
        assertEquals(0L, document.sourceOffsetMs)
        assertFalse(document.hasWordTimings)
        assertEquals(LyricsKind.PLAIN_TEXT, document.kind)
    }

    @Test fun utf16LittleEndianWithAnEmptyNullDescriptorDecodesRealChinese() = runBlocking {
        val result = readEmbedded(binary.id3(binary.frame("USLT", binary.uslt("星空\r\n音乐", encoding = 1))))
        assertEquals(listOf("星空", "音乐"), result.defaultCandidate!!.document.sourceLines.map { it.text })
        assertEquals("", result.defaultCandidate!!.source.descriptor)
        assertEquals(EmbeddedLyricsWireEncoding.UTF16_BOM, result.defaultCandidate!!.source.wireEncoding)
    }

    @Test fun utf16BigEndianBomDescriptorAndBodyKeepTheDeclaredSource() = runBlocking {
        val payload = binary.uslt("原文", encoding = 1, descriptor = "中文", littleEndian = false)
        val result = readEmbedded(binary.id3(binary.frame("USLT", payload)))
        assertEquals("中文", result.defaultCandidate!!.source.descriptor)
        assertEquals("原文", result.defaultCandidate!!.document.sourceLines.single().text)
    }

    @Test fun v24Utf8AndUtf16BeWithoutBomHaveNormalPaths() = runBlocking {
        for (encoding in listOf(2, 3)) {
            val payload = binary.uslt("夜空🌙", encoding = encoding)
            val result = readEmbedded(binary.id3(binary.frame("USLT", payload, version = 4), version = 4))
            assertEquals("夜空🌙", result.defaultCandidate!!.document.sourceLines.single().text)
            assertTrue(result.diagnostics.isEmpty())
        }
    }

    @Test fun optionalOneFinalBodyTerminatorIsNotAnEmbeddedNul() = runBlocking {
        for (encoding in listOf(0, 1, 2, 3)) {
            val version = if (encoding >= 2) 4 else 3
            val payload = binary.uslt("music", encoding = encoding, terminateBody = true)
            val result = readEmbedded(binary.id3(binary.frame("USLT", payload, version), version = version))
            assertEquals("music", result.defaultCandidate!!.document.sourceLines.single().text)
        }
    }

    @Test fun utf16BomFollowedByNullCanRepresentAnEmptyBodyWithoutUtf32Guessing() = runBlocking {
        val result = readEmbedded(binary.id3(binary.frame("USLT", binary.uslt("", encoding = 1, terminateBody = true))))
        assertTrue(result.metadataComplete)
        assertEquals(LyricsKind.EMPTY, result.candidates.single().document.kind)
        assertNull(result.defaultCandidate)
    }

    @Test fun utf16FieldByteOrderCannotChangeBetweenDescriptorAndBody() = runBlocking {
        val payload = binary.join(byteArrayOf(1), "eng".toByteArray(), binary.terminated("desc", 1),
            byteArrayOf(0xfe.toByte(), 0xff.toByte()), "body".toByteArray(Charsets.UTF_16BE))
        assertEmbeddedCandidateFailure(readEmbedded(binary.id3(binary.frame("USLT", payload))), EmbeddedLyricsIssue.INVALID_ENCODING)
    }

    @Test fun nonEmptyUtf16WithoutBomIsRejectedRatherThanDecodedByGuessing() = runBlocking {
        val payload = binary.join(byteArrayOf(1), "eng".toByteArray(), byteArrayOf(0, 0), "body".toByteArray(Charsets.UTF_16LE))
        assertEmbeddedCandidateFailure(readEmbedded(binary.id3(binary.frame("USLT", payload))), EmbeddedLyricsIssue.INVALID_ENCODING)
    }

    @Test fun invalidUtf8AndLoneUtf16SurrogateAreRejected() = runBlocking {
        val utf8 = binary.join(byteArrayOf(3), "eng".toByteArray(), byteArrayOf(0, 0xc3.toByte(), 0x28))
        val utf16 = binary.join(byteArrayOf(1), "eng".toByteArray(), byteArrayOf(0, 0, 0xff.toByte(), 0xfe.toByte(), 0, 0xd8.toByte()))
        assertEmbeddedCandidateFailure(readEmbedded(binary.id3(binary.frame("USLT", utf8, 4), version = 4)), EmbeddedLyricsIssue.INVALID_ENCODING)
        assertEmbeddedCandidateFailure(readEmbedded(binary.id3(binary.frame("USLT", utf16))), EmbeddedLyricsIssue.INVALID_ENCODING)
    }

    @Test fun v23DoesNotAcceptTheV24OnlyEncodingValues() = runBlocking {
        for (encoding in listOf(2, 3)) {
            assertEmbeddedCandidateFailure(readEmbedded(binary.id3(binary.frame("USLT", binary.uslt("text", encoding)))), EmbeddedLyricsIssue.INVALID_ENCODING)
        }
    }

    @Test fun controlsAndInteriorNulAreNotSilentlyRemovedFromUslt() = runBlocking {
        for (text in listOf("a\u0000b", "a\u0001b")) {
            assertEmbeddedCandidateFailure(readEmbedded(binary.id3(binary.frame("USLT", binary.uslt(text)))), EmbeddedLyricsIssue.INVALID_TEXT)
        }
    }

    @Test fun missingDescriptorTerminatorAndInvalidLanguageAreBoundedFailures() = runBlocking {
        val missing = binary.join(byteArrayOf(0), "engdescription".toByteArray())
        assertEmbeddedCandidateFailure(readEmbedded(binary.id3(binary.frame("USLT", missing))), EmbeddedLyricsIssue.INVALID_TEXT)
        assertEmbeddedCandidateFailure(readEmbedded(binary.id3(binary.frame("USLT", binary.uslt("text", language = "e1g")))), EmbeddedLyricsIssue.INVALID_LANGUAGE)
    }

    @Test fun distinctLanguageOrDescriptionSourcesRemainAvailableAndOrdered() = runBlocking {
        val result = readEmbedded(binary.id3(
            binary.frame("USLT", binary.uslt("first", descriptor = "original")),
            binary.frame("USLT", binary.uslt("第二", encoding = 1, language = "zho", descriptor = "translation")),
        ))
        assertEquals(listOf(1, 2), result.candidates.map { it.source.ordinal })
        assertEquals(listOf("eng", "zho"), result.candidates.map { it.source.language })
        assertEquals("first", result.defaultCandidate!!.document.sourceLines.single().text)
    }

    @Test fun duplicateId3IdentityExcludesBothAmbiguousSources() = runBlocking {
        val result = readEmbedded(binary.id3(
            binary.frame("USLT", binary.uslt("first", language = "ENG")),
            binary.frame("USLT", binary.uslt("second", language = "eng")),
        ))
        assertTrue(result.metadataComplete)
        assertTrue(result.candidates.isEmpty())
        assertEquals(listOf(1, 2), result.diagnostics.map { it.sourceOrdinal })
        assertTrue(result.diagnostics.all { it.issue == EmbeddedLyricsIssue.DUPLICATE_ID3_SOURCE })
    }

    @Test fun synchronizedSourceWinsBeforePlainTextRegardlessOfMetadataOrder() = runBlocking {
        val result = readEmbedded(binary.id3(
            binary.frame("USLT", binary.uslt("plain"), 4),
            binary.frame("SYLT", binary.sylt(listOf("timed" to 1_000L)), 4), version = 4,
        ))
        assertEquals(2, result.defaultCandidate!!.source.ordinal)
        assertEquals(LyricsKind.SYNCHRONIZED, result.defaultCandidate!!.document.kind)
    }

    @Test fun contentVersionChangesWithPayloadWhileCandidateOrdinalIsSeparate() = runBlocking {
        val first = readEmbedded(binary.id3(binary.frame("USLT", binary.uslt("first")))).candidates.single()
        val changed = readEmbedded(binary.id3(binary.frame("USLT", binary.uslt("changed")))).candidates.single()
        val repeated = readEmbedded(binary.id3(binary.frame("USLT", binary.uslt("first")))).candidates.single()
        assertNotEquals(first.source.contentSha256, changed.source.contentSha256)
        assertNotEquals(first.document.documentId, changed.document.documentId)
        assertEquals(first.source.contentSha256, repeated.source.contentSha256)
        assertEquals(first.document.documentId, repeated.document.documentId)
        assertEquals(64, first.source.contentSha256.length)
    }

    @Test fun malformedCandidateDoesNotPreventADifferentWellFramedSource() = runBlocking {
        val result = readEmbedded(binary.id3(
            binary.frame("USLT", binary.uslt("bad", language = "e1g")),
            binary.frame("USLT", binary.uslt("valid", descriptor = "other")),
        ))
        assertTrue(result.metadataComplete)
        assertEquals("valid", result.defaultCandidate!!.document.sourceLines.single().text)
        assertEquals(listOf(EmbeddedLyricsDiagnostic(EmbeddedLyricsIssue.INVALID_LANGUAGE, 1)), result.diagnostics)
    }

    @Test fun preservationAndReadOnlyFlagsDoNotChangeLiteralReadBehavior() = runBlocking {
        for ((version, status) in listOf(3 to 0xe0, 4 to 0x70)) {
            val result = readEmbedded(binary.id3(binary.frame("USLT", binary.uslt("read only"), version, status), version = version))
            assertEquals("read only", result.defaultCandidate!!.document.sourceLines.single().text)
        }
    }

    @Test fun completeResultKeepsNoMutableInputOrCollectionAlias() = runBlocking {
        val bytes = binary.resource("id3-v24-sylt-utf8.bin")
        val result = readEmbedded(bytes)
        val document = result.defaultCandidate!!.document
        val version = result.defaultCandidate!!.source.contentSha256
        bytes.fill(0)
        assertEquals("Hello world", document.sourceLines.single().text)
        assertEquals(version, result.defaultCandidate!!.source.contentSha256)
        expectEmbeddedImmutable { (result.candidates as MutableList<EmbeddedLyricsCandidate>).clear() }
        expectEmbeddedImmutable { (result.diagnostics as MutableList<EmbeddedLyricsDiagnostic>).clear() }
        expectEmbeddedImmutable { (document.sourceLines as MutableList<LyricsTextLine>).clear() }
        expectEmbeddedImmutable { (document.sourceLines.single().wordTimings as MutableList<LyricWordTiming>).clear() }
    }

    @Test fun compressionEncryptionGroupingAndUnsyncFrameFlagsAreNeverGuessed() = runBlocking {
        for ((version, flags) in listOf(3 to 0x80, 3 to 0x40, 3 to 0x20, 4 to 0x08, 4 to 0x04, 4 to 0x02, 4 to 0x01, 4 to 0x40)) {
            val result = readEmbedded(binary.id3(binary.frame("USLT", binary.uslt("encoded"), version, format = flags), version = version))
            assertEmbeddedCandidateFailure(result, EmbeddedLyricsIssue.UNSUPPORTED_ID3_FRAME_FLAGS)
        }
    }
}
