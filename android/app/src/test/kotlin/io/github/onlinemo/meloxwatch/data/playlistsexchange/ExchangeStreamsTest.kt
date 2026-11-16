package io.github.onlinemo.meloxwatch.data.playlistsexchange

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExchangeStreamsTest {
    @Test fun singleAndBulkReadsPreserveTheCompleteRawChecksumAndBorrowOwnership() {
        val bytes = exchangeUtf8("\ufeffCafé\r\nlast")
        val physical = ExchangeBorrowedInput(bytes, chunkSize = 2)
        val input = ExchangeInput(physical)
        repeat(3) { assertTrue(input.read() >= 0) }
        drain(input)
        assertEquals(bytes.size.toLong(), input.byteCount)
        assertEquals(exchangeTestSha256(bytes), input.checksum())
        assertEquals(input.checksum(), input.checksum())
        input.close()
        assertEquals(0, physical.closeCount)
        assertEquals(-1, input.read())
    }

    @Test fun exactByteBudgetRequiresEofAndOneExtraByteIsRejected() {
        runBlocking {
            val physical = ExchangeRepeatedInput(byteArrayOf(0x20), PlaylistExchangeLimits.BYTES)
            val input = ExchangeInput(physical)
            drain(input)
            assertEquals(PlaylistExchangeLimits.BYTES, input.byteCount)
            assertEquals(64, input.checksum().length)
            val oversized = ExchangeRepeatedInput(byteArrayOf(0x20), PlaylistExchangeLimits.BYTES + 2L)
            expectExchangeIssue(PlaylistExchangeIssue.LIMIT_EXCEEDED) { drain(ExchangeInput(oversized)) }
            assertEquals(PlaylistExchangeLimits.BYTES + 1L, oversized.position)
            assertEquals(0, oversized.closeCount)
        }
    }

    @Test fun providerZeroProgressCannotSpinOrProduceACompleteHash() {
        runBlocking {
            val physical = object : InputStream() {
                override fun read(): Int = 0
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
            }
            expectExchangeIssue(PlaylistExchangeIssue.READ_FAILED) { ExchangeInput(physical).read(ByteArray(8)) }
        }
    }

    @Test fun outputRejectsExcessBeforeWritingAndDoesNotCloseThePhysicalDestination() {
        runBlocking {
            var written = 0L
            var closes = 0
            val physical = object : OutputStream() {
                override fun write(value: Int) {
                    written++
                }
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    written += length
                }
                override fun close() {
                    closes++
                }
            }
            val output = ExchangeOutput(physical)
            val block = ByteArray(8192)
            repeat((PlaylistExchangeLimits.BYTES / block.size).toInt()) { output.write(block) }
            expectExchangeIssue(PlaylistExchangeIssue.LIMIT_EXCEEDED) { output.write(0x01) }
            assertEquals(PlaylistExchangeLimits.BYTES, written)
            assertEquals(PlaylistExchangeLimits.BYTES, output.byteCount)
            output.close()
            assertEquals(0, closes)
        }
    }

    @Test fun outputDigestIncludesSingleByteBulkAndFlushOperationsWithoutClosing() {
        val physical = ExchangeBorrowedOutput()
        val output = ExchangeOutput(physical)
        val bytes = exchangeUtf8("Café")
        output.write(bytes[0].toInt())
        output.write(bytes, 1, bytes.size - 1)
        output.flush()
        assertEquals(exchangeTestSha256(bytes), output.checksum())
        assertEquals(output.checksum(), output.checksum())
        assertEquals(bytes.toList(), physical.toByteArray().toList())
        output.close()
        assertEquals(0, physical.closeCount)
    }

    @Test fun providerMessagesCausesAndSuppressedTextCannotEnterExchangeDiagnostics() {
        runBlocking {
            val failure = IOException("not-for-diagnostics", IllegalStateException("not-for-cause"))
            failure.addSuppressed(IOException("not-for-suppressed"))
            val physical = object : InputStream() {
                override fun read(): Int = throw failure
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw failure
            }
            val error = expectExchangeIssue(PlaylistExchangeIssue.READ_FAILED) { ExchangeInput(physical).read() }
            assertEquals("READ_FAILED", error.message)
            assertEquals(IOException::class.java.name, error.cause?.message)
            assertNull(error.cause?.cause)
            assertTrue(error.cause?.suppressed?.isEmpty() == true)
            val diagnostic = error.stackTraceToString()
            assertFalse(diagnostic.contains("not-for-diagnostics"))
            assertFalse(diagnostic.contains("not-for-cause"))
            assertFalse(diagnostic.contains("not-for-suppressed"))
        }
    }

    @Test fun utf16Utf32AndWrongExplicitBomEncodingsAreRejectedBeforeTextParsing() {
        runBlocking {
            val prefixes = listOf(
                byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x61, 0),
                byteArrayOf(0xfe.toByte(), 0xff.toByte(), 0, 0x61),
                byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0, 0),
                byteArrayOf(0, 0, 0xfe.toByte(), 0xff.toByte()),
            )
            for (prefix in prefixes) {
                for (encoding in PlaylistExchangeEncoding.entries) {
                    expectExchangeIssue(PlaylistExchangeIssue.INVALID_ENCODING) {
                        exchangeReader(ExchangeInput(ByteArrayInputStream(prefix)), encoding)
                    }
                }
            }
            expectExchangeIssue(PlaylistExchangeIssue.INVALID_ENCODING) {
                val bytes = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + exchangeUtf8("a.flac")
                exchangeReader(ExchangeInput(ByteArrayInputStream(bytes)), PlaylistExchangeEncoding.GB18030)
            }
        }
    }

    @Test fun malformedUtf8TailIsAnEncodingFailureRatherThanReplacementText() {
        runBlocking {
            val bytes = exchangeUtf8("valid\n") + byteArrayOf(0xc3.toByte(), 0x28)
            val reader = exchangeReader(ExchangeInput(ByteArrayInputStream(bytes)), PlaylistExchangeEncoding.UTF8)
            expectExchangeIssue(PlaylistExchangeIssue.INVALID_ENCODING) {
                val lines = ExchangeLines(reader)
                while (lines.next() != null) Unit
            }
        }
    }

    @Test fun crlfCrLfAndAnUnterminatedLastLineKeepTheirLogicalOrder() {
        val lines = ExchangeLines(StringReader("one\r\ntwo\rthree\nfour"))
        val actual = mutableListOf<String>()
        while (true) {
            val line = lines.next() ?: break
            actual.add(line)
        }
        assertEquals(listOf("one", "two", "three", "four"), actual)
    }

    @Test fun lineLimitIsEnforcedBeforeAnOversizedLineIsAllocated() {
        runBlocking {
            val allowed = "a".repeat(PlaylistExchangeLimits.LINE_CHARACTERS)
            assertEquals(allowed, ExchangeLines(StringReader("$allowed\n")).next())
            expectExchangeIssue(PlaylistExchangeIssue.LIMIT_EXCEEDED) {
                ExchangeLines(StringReader("${allowed}a\n")).next()
            }
        }
    }

    @Test fun jsonEscapesAndScalarsRemainCorrectAcrossOneByteReadsAndBorrowedClose() {
        val text = """{"a":"\uD834\uDD1E\/\\","n":-12.3e+4,"b":true,"z":null}"""
        val physical = ExchangeBorrowedInput(exchangeUtf8(text), chunkSize = 1)
        val reader = exchangeReader(ExchangeInput(physical), PlaylistExchangeEncoding.UTF8)
        val guard = ExchangeJsonGuard(reader)
        assertEquals(text, guard.readText())
        guard.close()
        assertEquals(0, physical.closeCount)
    }

    @Test fun jsonUnknownEscapesAndUnescapedControlsCannotExploitAndroidLeniency() {
        runBlocking {
            val values = listOf("""{"a":"\x"}""", """{"a":"\'"}""", "{\"a\":\"line\nbreak\"}",
                "{\"a\":\"one\u0001two\"}")
            for (value in values) {
                expectExchangeIssue(PlaylistExchangeIssue.MALFORMED) {
                    ExchangeJsonGuard(StringReader(value)).readText()
                }
            }
        }
    }

    @Test fun jsonStringBudgetCountsDecodedEscapesAndRejectsPlainOrEscapedExcess() {
        runBlocking {
            val escaped = "\\u0061".repeat(PlaylistExchangeLimits.FIELD_CHARACTERS)
            val allowed = "{\"a\":\"$escaped\"}"
            assertEquals(allowed, ExchangeJsonGuard(StringReader(allowed)).readText())
            for (oversized in listOf("${escaped}\\u0061", "a".repeat(PlaylistExchangeLimits.FIELD_CHARACTERS + 1))) {
                expectExchangeIssue(PlaylistExchangeIssue.LIMIT_EXCEEDED) {
                    ExchangeJsonGuard(StringReader("{\"a\":\"$oversized\"}")).readText()
                }
            }
        }
    }

    @Test fun jsonDepthAndScalarLengthAreBoundedBeforeJsonReaderCanExpandThem() {
        runBlocking {
            val allowed = "[".repeat(16) + "0" + "]".repeat(16)
            assertEquals(allowed, ExchangeJsonGuard(StringReader(allowed)).readText())
            expectExchangeIssue(PlaylistExchangeIssue.LIMIT_EXCEEDED) {
                ExchangeJsonGuard(StringReader("[".repeat(17) + "0" + "]".repeat(17))).readText()
            }
            expectExchangeIssue(PlaylistExchangeIssue.LIMIT_EXCEEDED) {
                ExchangeJsonGuard(StringReader("{\"n\":" + "1".repeat(65) + "}")).readText()
            }
        }
    }

    @Test fun jsonSeparatorsCaseAndNumberGrammarFollowRfcJsonRatherThanReaderDefaults() {
        runBlocking {
            val values = listOf("""{"a":TRUE}""", """{"a":nUlL}""", """{"a":false;"b":true}""",
                """{"a":01}""", """{"a":+1}""", """{"a":.1}""", """{"a":NaN}""", "{}\ufeff")
            for (value in values) {
                expectExchangeIssue(PlaylistExchangeIssue.MALFORMED) {
                    ExchangeJsonGuard(StringReader(value)).readText()
                }
            }
        }
    }

    @Test fun jsonTruncatedStringsEscapesAndContainersNeverCompleteTheInput() {
        runBlocking {
            for (value in listOf("{", "[0", "{\"a\":\"unfinished", "{\"a\":\"\\u00", "]")) {
                expectExchangeIssue(PlaylistExchangeIssue.MALFORMED) {
                    ExchangeJsonGuard(StringReader(value)).readText()
                }
            }
        }
    }

    private fun drain(input: InputStream) {
        val buffer = ByteArray(8192)
        while (input.read(buffer) >= 0) Unit
    }
}
