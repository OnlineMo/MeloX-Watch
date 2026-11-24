package io.github.onlinemo.meloxwatch.data.playlistsexchange

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], manifest = Config.NONE)
class ExchangeFailureCancellationTest {
    @Test fun cancellationDuringTheFirstPhysicalReadCannotAppendOrContinueAfterTheBomProbe() {
        runBlocking {
            for (format in Format.entries) {
                val operationJob = Job(coroutineContext[Job])
                val physical = ExchangeBorrowedInput(validBytes(format))
                val input = object : InputStream() {
                    override fun read(): Int = physical.read()
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        val count = physical.read(buffer, offset, length)
                        operationJob.cancel(CancellationException("controlled read cancellation"))
                        return count
                    }
                    override fun close() {
                        physical.close()
                    }
                }
                var appended = 0
                try {
                    val operation = async(operationJob, start = CoroutineStart.UNDISPATCHED) {
                        parse(format, input, ExchangePageSink { appended++ })
                    }
                    operation.join()
                    assertTrue(operation.isCancelled)
                    assertEquals(0, appended)
                    assertEquals(4L, physical.readBytes)
                    assertEquals(0, physical.closeCount)
                } finally {
                    operationJob.cancel()
                }
            }
        }
    }

    @Test fun physicalCancellationExceptionAfterThePrefixProbeIsNeverMappedToMalformedJson() {
        runBlocking {
            for (format in Format.entries) {
                val failure = CancellationException("controlled physical cancellation")
                var calls = 0
                var closes = 0
                val physical = object : ByteArrayInputStream(validBytes(format)) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        calls++
                        if (calls > 1) throw failure
                        return super.read(buffer, offset, minOf(length, 4))
                    }
                    override fun close() {
                        closes++
                        super.close()
                    }
                }
                try {
                    parse(format, physical, ExchangePageSink { throw AssertionError("No complete page expected") })
                    throw AssertionError("Expected cancellation")
                } catch (error: CancellationException) {
                    assertEquals(failure.message, error.message)
                    assertTrue(error === failure || error.cause === failure)
                }
                assertEquals(2, calls)
                assertEquals(0, closes)
            }
        }
    }

    @Test fun cancellationByAnImportPageCallbackCannotReadTheNextPageOrReturnSuccess() {
        runBlocking {
            for (format in Format.entries) {
                val input = ExchangeBorrowedInput(validBytes(format, count = 101), chunkSize = 1)
                var appendCalls = 0
                var bytesAtCancellation = -1L
                val operation = async(start = CoroutineStart.UNDISPATCHED) {
                    parse(format, input, ExchangePageSink { page ->
                        assertEquals(100, page.size)
                        appendCalls++
                        bytesAtCancellation = input.readBytes
                        currentCoroutineContext().cancel()
                    })
                }
                operation.join()
                assertTrue(operation.isCancelled)
                assertEquals(1, appendCalls)
                assertEquals(bytesAtCancellation, input.readBytes)
                assertEquals(0, input.closeCount)
            }
        }
    }

    @Test fun cancellationByAnExportPageCallbackCannotSerializeItsLateReturnedMembers() {
        runBlocking {
            for (format in Format.entries) {
                val baseline = ExchangeFixtureSource(listOf(exchangeMember(0L)))
                val source = object : ExchangeExportSource by baseline {
                    override suspend fun page(offset: Long, limit: Int): List<ExchangeMember> {
                        currentCoroutineContext().cancel()
                        return baseline.page(offset, limit)
                    }
                }
                val output = ExchangeBorrowedOutput()
                val operation = async(start = CoroutineStart.UNDISPATCHED) { write(format, output, source) }
                operation.join()
                assertTrue(operation.isCancelled)
                assertFalse(output.toByteArray().toString(Charsets.UTF_8).contains("music/0.flac"))
                assertEquals(0, output.closeCount)
            }
        }
    }

    @Test fun stagingIoFailureAndCancellationPropagateWithoutParserTranslationOrNextPage() {
        runBlocking {
            for (format in Format.entries) {
                val failures = listOf(
                    IOException("controlled staging failure"),
                    CancellationException("controlled staging cancellation"),
                )
                for (failure in failures) {
                    val input = ExchangeBorrowedInput(validBytes(format, count = 101))
                    var appendCalls = 0
                    try {
                        parse(format, input, ExchangePageSink { page ->
                            assertEquals(100, page.size)
                            appendCalls++
                            throw failure
                        })
                        throw AssertionError("Expected staging failure")
                    } catch (error: Exception) {
                        // 测试明确核对取消/IO类型；应用解析器不能把它们改成自己的schema错误。
                        assertEquals(failure.javaClass, error.javaClass)
                        assertEquals(failure.message, error.message)
                        assertTrue(error === failure || error.cause === failure)
                    }
                    assertEquals(1, appendCalls)
                    assertEquals(0, input.closeCount)
                }
            }
        }
    }

    @Test fun readWriteAndFinalFlushFailuresHaveWhitelistedDiagnosticsAndKeepBorrowedStreamsOpen() {
        runBlocking {
            for (format in Format.entries) {
                val failure = IOException("not-for-diagnostics")
                var readCloses = 0
                val input = object : ByteArrayInputStream(validBytes(format)) {
                    var calls = 0
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        calls++
                        if (calls > 1) throw failure
                        return super.read(buffer, offset, minOf(length, 4))
                    }
                    override fun close() {
                        readCloses++
                        super.close()
                    }
                }
                val readError = expectExchangeIssue(PlaylistExchangeIssue.READ_FAILED) {
                    parse(format, input, ExchangePageSink {})
                }
                assertFalse(readError.stackTraceToString().contains("not-for-diagnostics"))
                assertEquals(0, readCloses)
                for (failDuringFlush in listOf(false, true)) {
                    var writeCloses = 0
                    val output = object : OutputStream() {
                        override fun write(value: Int) {
                            if (!failDuringFlush) throw failure
                        }
                        override fun write(buffer: ByteArray, offset: Int, length: Int) {
                            if (!failDuringFlush) throw failure
                        }
                        override fun flush() {
                            if (failDuringFlush) throw failure
                        }
                        override fun close() {
                            writeCloses++
                        }
                    }
                    val writeError = expectExchangeIssue(PlaylistExchangeIssue.WRITE_FAILED) {
                        write(format, output, ExchangeFixtureSource(listOf(exchangeMember(0L))))
                    }
                    assertFalse(writeError.stackTraceToString().contains("not-for-diagnostics"))
                    assertEquals(0, writeCloses)
                }
            }
        }
    }

    @Test fun initialSnapshotFailureOrInvalidExportCountCannotWriteBeforeSourceValidation() {
        runBlocking {
            for (format in Format.entries) {
                val baseline = ExchangeFixtureSource(listOf(exchangeMember(0L)))
                val failure = IOException("controlled source failure")
                val failingSource = object : ExchangeExportSource by baseline {
                    override suspend fun validateSnapshot() {
                        throw failure
                    }
                }
                val output = ExchangeBorrowedOutput()
                try {
                    write(format, output, failingSource)
                    throw AssertionError("Expected source failure")
                } catch (error: IOException) {
                    assertEquals(failure.message, error.message)
                    assertTrue(error === failure || error.cause === failure)
                }
                assertEquals(0, output.size())
                for (count in listOf(-1L, PlaylistExchangeLimits.MEMBERS + 1L)) {
                    val invalidCount = object : ExchangeExportSource by baseline {
                        override val memberCount: Long = count
                    }
                    expectExchangeIssue(PlaylistExchangeIssue.LIMIT_EXCEEDED) { write(format, output, invalidCount) }
                    assertEquals(0, output.size())
                }
                assertEquals(0, output.closeCount)
            }
        }
    }

    @Test fun snapshotInvalidatedDuringFlushCannotReturnACompletedExport() {
        runBlocking {
            for (format in Format.entries) {
                val baseline = ExchangeFixtureSource(listOf(exchangeMember(0L)))
                val failure = IllegalStateException("controlled snapshot invalidation")
                var invalid = false
                var closes = 0
                val source = object : ExchangeExportSource by baseline {
                    override suspend fun validateSnapshot() {
                        if (invalid) throw failure
                        baseline.validateSnapshot()
                    }
                }
                val output = object : ByteArrayOutputStream() {
                    override fun flush() {
                        super.flush()
                        invalid = true
                    }
                    override fun close() {
                        closes++
                        super.close()
                    }
                }
                try {
                    write(format, output, source)
                    throw AssertionError("Expected snapshot invalidation")
                } catch (error: IllegalStateException) {
                    assertEquals(failure.message, error.message)
                    assertTrue(error === failure || error.cause === failure)
                }
                assertTrue(output.size() > 0)
                assertEquals(0, closes)
            }
        }
    }

    @Test fun malformedPagesIdentitiesCueKindsAndBlockedStatesCannotBecomeCompletedExports() {
        runBlocking {
            for (format in Format.entries) {
                val baseline = ExchangeFixtureSource(listOf(exchangeMember(0L)))
                val badMembers = listOf(exchangeMember(0L).copy(ordinal = 1L),
                    exchangeMember(0L).copy(memberId = "not-a-member-id"),
                    exchangeMember(0L).copy(mediaKind = ExchangeMediaKind.CUE_TRACK),
                    exchangeMember(0L).copy(contentSha256 = "not-a-hash"),
                    exchangeMember(0L).copy(blockedReference = "../blocked.flac",
                        referenceIssue = ExchangeReferenceIssue.UNSAFE_RELATIVE_PATH, unresolved = false))
                val badPages = listOf(emptyList<ExchangeMember>()) + badMembers.map { listOf(it) }
                for (invalidPage in badPages) {
                    val source = object : ExchangeExportSource by baseline {
                        override suspend fun page(offset: Long, limit: Int): List<ExchangeMember> = invalidPage
                    }
                    val output = ExchangeBorrowedOutput()
                    expectExchangeIssue(PlaylistExchangeIssue.INVALID_VALUE) { write(format, output, source) }
                    assertEquals(0, output.closeCount)
                }
            }
        }
    }

    private enum class Format { M3U, JSON }

    private fun validBytes(format: Format, count: Int = 1): ByteArray = when (format) {
        Format.M3U -> exchangeUtf8(List(count) { "$it.flac" }.joinToString("\n"))
        Format.JSON -> exchangeUtf8(exchangeJson(List(count) { exchangeJsonMember() }.joinToString(",")))
    }

    private suspend fun parse(
        format: Format,
        input: InputStream,
        sink: ExchangePageSink,
    ): ExchangeParseResult = when (format) {
        Format.M3U -> M3uPlaylistExchange().parse(input, sink)
        Format.JSON -> JsonPlaylistExchange().parse(input, sink)
    }

    private suspend fun write(
        format: Format,
        output: OutputStream,
        source: ExchangeExportSource,
    ): ExchangeWriteResult = when (format) {
        Format.M3U -> M3uPlaylistExchange().write(output, source)
        Format.JSON -> JsonPlaylistExchange().write(output, source)
    }
}
