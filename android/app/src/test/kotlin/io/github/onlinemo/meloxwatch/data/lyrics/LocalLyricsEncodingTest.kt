package io.github.onlinemo.meloxwatch.data.lyrics

import java.nio.charset.Charset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalLyricsEncodingTest {
    @Test fun utf8WithoutBomKeepsMultilingualAndSupplementaryCharacters() {
        runBlocking {
            val document = parseLyrics("[00:01]中文 café \uD83D\uDE03")
            assertEquals(LyricsEncoding.UTF8, document.encoding)
            assertEquals("中文 café \uD83D\uDE03", document.timeline.line(0).text)
        }
    }

    @Test fun utf8BomIsRemovedBeforeTimeTagParsing() {
        runBlocking {
            val bytes = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "[00:01]One".toByteArray(Charsets.UTF_8)
            val document = LocalLyricsParser().parse(bytes)
            assertEquals(LyricsEncoding.UTF8, document.encoding)
            assertEquals("One", document.timeline.line(0).text)
        }
    }

    @Test fun utf16LittleEndianBomSelectsTheStrictDecoder() {
        runBlocking {
            val bytes = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + "[00:01]中文 \uD83D\uDE03".toByteArray(Charsets.UTF_16LE)
            val document = LocalLyricsParser().parse(bytes)
            assertEquals(LyricsEncoding.UTF16_LE, document.encoding)
            assertEquals("中文 \uD83D\uDE03", document.timeline.line(0).text)
        }
    }

    @Test fun utf16BigEndianBomSelectsTheStrictDecoder() {
        runBlocking {
            val bytes = byteArrayOf(0xfe.toByte(), 0xff.toByte()) + "[00:01]中文 \uD83D\uDE03".toByteArray(Charsets.UTF_16BE)
            val document = LocalLyricsParser().parse(bytes)
            assertEquals(LyricsEncoding.UTF16_BE, document.encoding)
            assertEquals("中文 \uD83D\uDE03", document.timeline.line(0).text)
        }
    }

    @Test fun gb18030RequiresExplicitSelectionAndAutoDoesNotGuess() {
        runBlocking {
            val bytes = "[00:01]中文 \uD83D\uDE03".toByteArray(Charset.forName("GB18030"))
            val document = LocalLyricsParser().parse(bytes, LyricsEncodingChoice.GB18030)
            assertEquals(LyricsEncoding.GB18030, document.encoding)
            assertEquals("中文 \uD83D\uDE03", document.timeline.line(0).text)
            expectLyricsIssue(LyricsParseIssue.INVALID_ENCODING) { LocalLyricsParser().parse(bytes) }
        }
    }

    @Test fun anExplicitEncodingCannotOverrideAnIncompatibleBom() {
        runBlocking {
            val utf16 = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + "One".toByteArray(Charsets.UTF_16LE)
            val utf8 = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "One".toByteArray(Charsets.UTF_8)
            expectLyricsIssue(LyricsParseIssue.ENCODING_MISMATCH) {
                LocalLyricsParser().parse(utf16, LyricsEncodingChoice.UTF8)
            }
            expectLyricsIssue(LyricsParseIssue.ENCODING_MISMATCH) {
                LocalLyricsParser().parse(utf8, LyricsEncodingChoice.GB18030)
            }
        }
    }

    @Test fun utf32BomIsAnUnsupportedEncodingRatherThanUtf16OrUtf8() {
        runBlocking {
            for (bytes in listOf(byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0, 0, 0x61, 0, 0, 0),
                byteArrayOf(0, 0, 0xfe.toByte(), 0xff.toByte(), 0, 0, 0, 0x61))) {
                expectLyricsIssue(LyricsParseIssue.UNSUPPORTED_ENCODING) { LocalLyricsParser().parse(bytes) }
            }
        }
    }

    @Test fun malformedUtf8AndUtf16NeverIntroduceReplacementCharactersOrPartialLyrics() {
        runBlocking {
            val malformed = listOf(
                "[00:01]prefix".toByteArray(Charsets.UTF_8) + byteArrayOf(0xc3.toByte()),
                byteArrayOf(0xc0.toByte(), 0xaf.toByte()),
                byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
                byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x61),
                byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0, 0xd8.toByte()),
                byteArrayOf(0xfe.toByte(), 0xff.toByte(), 0xdc.toByte(), 0),
            )
            for (bytes in malformed) {
                expectLyricsIssue(LyricsParseIssue.INVALID_ENCODING) { LocalLyricsParser().parse(bytes) }
            }
        }
    }

    @Test fun aTruncatedGb18030SequenceIsRejectedWithTheSelectedEncoding() {
        runBlocking {
            expectLyricsIssue(LyricsParseIssue.INVALID_ENCODING) {
                LocalLyricsParser().parse(byteArrayOf(0x81.toByte(), 0x30, 0x81.toByte()), LyricsEncodingChoice.GB18030)
            }
        }
    }

    @Test fun utf16WithoutBomIsNotSilentlyGuessedAndErrorsDoNotContainInputText() {
        runBlocking {
            expectLyricsIssue(LyricsParseIssue.INVALID_TEXT, 1) {
                LocalLyricsParser().parse("[00:01]One".toByteArray(Charsets.UTF_16LE))
            }
            val marker = "SAMPLE_ONLY_PRIVATE_VALUE"
            val error = expectLyricsIssue(LyricsParseIssue.INVALID_ENCODING) {
                LocalLyricsParser().parse(marker.toByteArray(Charsets.UTF_8) + byteArrayOf(0xc3.toByte()))
            }
            assertEquals("INVALID_ENCODING", error.message)
            assertFalse(error.stackTraceToString().contains(marker))
        }
    }
}
