package io.github.onlinemo.meloxwatch.data.cue

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CueParserTest {
    @Test fun realMultiFileCuePreservesFrameIndicesAndMetadataScopes() {
        val parsed = CueParser.parse("""
            REM GENRE Classical
            TITLE "Album"
            PERFORMER "Global Artist"
            FILE "disc/first.flac" WAVE
              TITLE "File Album"
              PERFORMER "File Artist"
              TRACK 01 AUDIO
                TITLE "First"
                PERFORMER "Solo"
                INDEX 00 00:00:00
                INDEX 01 00:02:00
              TRACK 02 AUDIO
                TITLE "Second"
                INDEX 00 00:59:00
                INDEX 01 01:00:01
            FILE "second.mp3" MP3
              TRACK 03 AUDIO
                INDEX 01 00:00:10
        """.trimIndent().toByteArray())
        assertEquals(CueMetadata("Album", "Global Artist"), parsed.metadata)
        assertEquals(CueMetadata("File Album", "File Artist"), parsed.files[0].metadata)
        assertEquals(CueMetadata(), parsed.files[1].metadata)
        assertEquals(CueFileType.WAVE, parsed.files[0].declaredType)
        assertEquals(CueFileType.MP3, parsed.files[1].declaredType)
        assertEquals(listOf("disc", "first.flac"), parsed.files[0].path.components)
        assertEquals(listOf(1, 2, 3), parsed.tracks.map { it.number })
        assertEquals(listOf(150L, 4501L, 10L), parsed.tracks.map { it.index01.frames })
        assertEquals(4425L, parsed.tracks[1].index00?.frames)
        assertEquals(CueMetadata("First", "Solo"), parsed.tracks[0].metadata)
        assertEquals(0, parsed.tracks[1].fileIndex)
        assertEquals(1, parsed.tracks[2].fileIndex)
    }

    @Test fun utf8BomIsIncludedInCompleteRawHashAndBorrowedStreamStaysOpen() {
        val bytes = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + cueText().toByteArray()
        var closed = false
        val input = object : ByteArrayInputStream(bytes) { override fun close() { closed = true; super.close() } }
        val parsed = CueParser.read(input, bytes.size.toLong())
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals("sha256:$digest", parsed.rawContentToken)
        assertEquals(CueEncoding.UTF8, parsed.encoding)
        assertFalse(closed)
        assertEquals(-1, input.read())
    }

    @Test fun gb18030RequiresExplicitChoiceAndPreservesChineseMetadata() {
        val text = "TITLE \"中文专辑\"\nPERFORMER \"演奏者\"\n${cueText("音乐.flac")}"
        val bytes = text.toByteArray(Charset.forName("GB18030"))
        expectCueIssue(CueIssue.INVALID_ENCODING) { CueParser.parse(bytes) }
        val parsed = CueParser.parse(bytes, CueEncoding.GB18030)
        assertEquals(CueEncoding.GB18030, parsed.encoding)
        assertEquals("中文专辑", parsed.metadata.title)
        assertEquals("演奏者", parsed.metadata.performer)
        assertEquals(listOf("音乐.flac"), parsed.files.single().path.components)
    }

    @Test fun invalidBytesAfterValidTracksNeverReturnAReplacementDecodedPrefix() {
        val bytes = (cueText() + "\nREM ").toByteArray() + byteArrayOf(0xc3.toByte(), 0x28)
        expectCueIssue(CueIssue.INVALID_ENCODING) { CueParser.parse(bytes) }
        expectCueIssue(CueIssue.INVALID_ENCODING) { CueParser.parse(cueText().toByteArray() + byteArrayOf(0xf0.toByte())) }
    }

    @Test fun unsupportedBomAndWrongExplicitBomEncodingAreRejected() {
        expectCueIssue(CueIssue.INVALID_ENCODING) { CueParser.parse(byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0, 0)) }
        expectCueIssue(CueIssue.INVALID_ENCODING) {
            CueParser.parse(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + cueText().toByteArray(), CueEncoding.GB18030)
        }
    }

    @Test fun lfCrLfAndCrProduceTheSameLogicalCue() {
        val lf = CueParser.parse(cueText().toByteArray())
        for (separator in listOf("\r\n", "\r")) {
            val other = CueParser.parse(cueText().replace("\n", separator).toByteArray())
            assertEquals(lf.files, other.files)
            assertEquals(lf.tracks, other.tracks)
        }
    }

    @Test fun missingRepeatedOrOutOfOrderIndicesFailTheWholeCue() {
        val header = "FILE \"a.flac\" WAVE\nTRACK 01 AUDIO\n"
        for (body in listOf("", "INDEX 00 00:00:00", "INDEX 01 00:00:00\nINDEX 01 00:00:01",
                "INDEX 01 00:00:01\nINDEX 00 00:00:00", "INDEX 00 00:00:00\nINDEX 00 00:00:00")) {
            expectCueIssue(CueIssue.INVALID_INDEX) { CueParser.parse((header + body).toByteArray()) }
        }
    }

    @Test fun timestampsRejectBadSecondsFramesSignsAndIntegerOverflow() {
        for (time in listOf("00:60:00", "00:00:75", "-1:00:00", "00:0:00", "00:00:0",
                "9223372036854775807:00:00", "999999999999999999999:00:00", "01:00:00:00")) {
            val text = cueText().replace("00:00:00", time)
            expectCueIssue(CueIssue.INVALID_TIME) { CueParser.parse(text.toByteArray()) }
        }
    }

    @Test fun equalDescendingOrOverlappingTrackTimesAreRejected() {
        val prefix = "FILE \"a.flac\" WAVE\nTRACK 01 AUDIO\nINDEX 01 00:10:00\nTRACK 02 AUDIO\n"
        for (body in listOf("INDEX 01 00:10:00", "INDEX 01 00:09:74",
                "INDEX 00 00:09:00\nINDEX 01 00:11:00", "INDEX 00 00:12:00\nINDEX 01 00:11:00")) {
            expectCueIssue(CueIssue.INVALID_BOUNDARIES) { CueParser.parse((prefix + body).toByteArray()) }
        }
    }

    @Test fun pregapAndPostgapNeverSilentlySynthesizeOrDiscardAudio() {
        for (directive in listOf("PREGAP", "POSTGAP")) {
            for (time in listOf("00:00:00", "00:02:00")) {
                expectCueIssue(CueIssue.UNSUPPORTED_GAP) { CueParser.parse((cueText() + "\n$directive $time").toByteArray()) }
            }
        }
    }

    @Test fun dataTracksUnknownDirectivesAndUnknownFileTypesAreExplicitErrors() {
        expectCueIssue(CueIssue.INVALID_TRACK) { CueParser.parse(cueText().replace("AUDIO", "MODE1/2352").toByteArray()) }
        expectCueIssue(CueIssue.UNSUPPORTED_FILE_TYPE) { CueParser.parse(cueText().replace("WAVE", "BINARY").toByteArray()) }
        expectCueIssue(CueIssue.UNSUPPORTED_DIRECTIVE) { CueParser.parse((cueText() + "\nFLAGS DCP").toByteArray()) }
        expectCueIssue(CueIssue.UNSUPPORTED_DIRECTIVE) { CueParser.parse((cueText() + "\nINDEX 02 00:10:00").toByteArray()) }
    }

    @Test fun tracksRequireAFileAndUniqueIncreasingNumbers() {
        expectCueIssue(CueIssue.INVALID_TRACK) { CueParser.parse("TRACK 01 AUDIO\nINDEX 01 00:00:00".toByteArray()) }
        for (number in listOf("00", "1", "01", "-1")) {
            val text = cueText() + "\nTRACK $number AUDIO\nINDEX 01 00:01:00"
            expectCueIssue(CueIssue.INVALID_TRACK) { CueParser.parse(text.toByteArray()) }
        }
        val descending = cueText().replace("TRACK 01", "TRACK 02") + "\nTRACK 01 AUDIO\nINDEX 01 00:01:00"
        expectCueIssue(CueIssue.INVALID_TRACK) { CueParser.parse(descending.toByteArray()) }
    }

    @Test fun repeatedFileReferencesAfterDotAndNfcNormalizationAreRejected() {
        for ((first, second) in listOf("folder/a.flac" to "./folder/a.flac", "Café.flac" to "Cafe\u0301.flac")) {
            val text = cueText(first) + "\nFILE \"$second\" WAVE\nTRACK 02 AUDIO\nINDEX 01 00:00:00"
            expectCueIssue(CueIssue.DUPLICATE_REFERENCE) { CueParser.parse(text.toByteArray()) }
        }
    }

    @Test fun fieldLineAndLineCountLimitsAcceptTheirBoundaryAndRejectBeyondIt() {
        val title = "x".repeat(CueLimits.FIELD_CHARACTERS)
        assertEquals(title, CueParser.parse(("TITLE \"$title\"\n" + cueText()).toByteArray()).metadata.title)
        expectCueIssue(CueIssue.LIMIT_EXCEEDED) { CueParser.parse(("TITLE \"${title}x\"\n" + cueText()).toByteArray()) }
        val comment = "REM " + "x".repeat(CueLimits.LINE_CHARACTERS - 4)
        assertEquals(1, CueParser.parse((comment + "\n" + cueText()).toByteArray()).tracks.size)
        expectCueIssue(CueIssue.LIMIT_EXCEEDED) { CueParser.parse((comment + "x\n" + cueText()).toByteArray()) }
        val lines = "REM\n".repeat(CueLimits.LINES - 3) + cueText() + "\n"
        assertEquals(1, CueParser.parse(lines.toByteArray()).tracks.size)
        expectCueIssue(CueIssue.LIMIT_EXCEEDED) { CueParser.parse(("REM\n" + lines).toByteArray()) }
    }

    @Test fun ninetyNineTracksAndFilesAreInputDialectLimitsNotLibraryCaps() {
        val text = (1..99).joinToString("\n") { number ->
            val token = number.toString().padStart(2, '0')
            "FILE \"disc$number.flac\" WAVE\nTRACK $token AUDIO\nINDEX 01 00:00:00"
        }
        val parsed = CueParser.parse(text.toByteArray())
        assertEquals(99, parsed.files.size)
        assertEquals(99, parsed.tracks.size)
        expectCueIssue(CueIssue.LIMIT_EXCEEDED) { CueParser.parse((text + "\nFILE \"disc100.flac\" WAVE").toByteArray()) }
        val sameFile = "FILE \"all.flac\" WAVE\n" + (1..99).joinToString("\n") { number ->
            "TRACK ${number.toString().padStart(2, '0')} AUDIO\nINDEX 01 ${number.toString().padStart(2, '0')}:00:00"
        }
        assertEquals(99, CueParser.parse(sameFile.toByteArray()).tracks.size)
        expectCueIssue(CueIssue.LIMIT_EXCEEDED) { CueParser.parse((sameFile + "\nTRACK 100 AUDIO\nINDEX 01 100:00:00").toByteArray()) }
    }

    @Test fun unknownLengthStreamStopsAfterAtMostOneByteBeyondTheRawBudget() {
        val first = (cueText() + "\n").toByteArray()
        val comment = ("REM " + "x".repeat(1000) + "\n").toByteArray()
        var readBytes = 0
        var largestRead = 0
        var closed = false
        val input = object : InputStream() {
            override fun read(): Int {
                val position = readBytes++
                return (if (position < first.size) first[position] else comment[(position - first.size) % comment.size]).toInt() and 255
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                largestRead = maxOf(largestRead, length)
                for (index in 0 until length) buffer[offset + index] = read().toByte()
                return length
            }
            override fun close() { closed = true }
        }
        expectCueIssue(CueIssue.LIMIT_EXCEEDED) { CueParser.read(input) }
        assertEquals(CueLimits.RAW_BYTES + 1, readBytes)
        assertTrue(largestRead <= 8192)
        assertFalse(closed)
    }

    @Test fun exactRawByteBudgetIsAcceptedAndOneExtraActualByteIsRejected() {
        val text = StringBuilder(CueLimits.RAW_BYTES).append(cueText()).append('\n')
        val comment = "REM " + "x".repeat(CueLimits.LINE_CHARACTERS - 4) + "\n"
        while (CueLimits.RAW_BYTES - text.length >= comment.length) text.append(comment)
        val remaining = CueLimits.RAW_BYTES - text.length
        if (remaining >= 4) text.append("REM ").append("x".repeat(remaining - 4))
        else text.append(" ".repeat(remaining))
        val bytes = text.toString().toByteArray()
        assertEquals(CueLimits.RAW_BYTES, bytes.size)
        assertEquals(1, CueParser.parse(bytes).tracks.size)
        expectCueIssue(CueIssue.LIMIT_EXCEEDED) { CueParser.read(ByteArrayInputStream(bytes + byteArrayOf('x'.code.toByte()))) }
    }

    @Test fun falseDeclaredLengthsFailAndOversizedDeclarationsDoNotRead() {
        val bytes = cueText().toByteArray()
        expectCueIssue(CueIssue.SOURCE_CHANGED) { CueParser.read(ByteArrayInputStream(bytes), bytes.size.toLong() - 1) }
        expectCueIssue(CueIssue.SOURCE_CHANGED) { CueParser.read(ByteArrayInputStream(bytes), bytes.size.toLong() + 1) }
        var touched = false
        val input = object : InputStream() { override fun read(): Int { touched = true; return -1 } }
        expectCueIssue(CueIssue.LIMIT_EXCEEDED) { CueParser.read(input, CueLimits.RAW_BYTES.toLong() + 1) }
        assertFalse(touched)
        expectCueIssue(CueIssue.INVALID_INPUT) { CueParser.read(input, -1L) }
        assertFalse(touched)
    }

    @Test fun cancellationPropagatesAfterTheProviderReturnsWithoutClosingBorrowedInput() {
        var returned = false
        var closed = false
        val input = object : ByteArrayInputStream(cueText().toByteArray()) {
            override fun read(): Int { returned = true; return super.read() }
            override fun close() { closed = true; super.close() }
        }
        val cancellation = CancellationException("cancelled")
        try {
            CueParser.read(input, checkCancelled = { if (returned) throw cancellation })
            throw AssertionError("Expected cancellation")
        } catch (error: CancellationException) { assertTrue(error === cancellation) }
        assertFalse(closed)
    }

    @Test fun unterminatedQuotesTrailingTokensAndRepeatedMetadataAreErrors() {
        for (text in listOf("FILE \"a.flac WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00",
                cueText() + " trailing", "TITLE \"A\"\nTITLE \"B\"\n" + cueText(),
                "TITLE \"A\" extra\n" + cueText())) {
            expectCueIssue(CueIssue.INVALID_SYNTAX) { CueParser.parse(text.toByteArray()) }
        }
    }
}
