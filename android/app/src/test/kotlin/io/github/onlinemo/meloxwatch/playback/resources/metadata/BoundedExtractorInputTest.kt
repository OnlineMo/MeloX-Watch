package io.github.onlinemo.meloxwatch.playback.resources.metadata

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorInput
import java.io.InterruptedIOException
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
class BoundedExtractorInputTest {
    @Test fun allPeekOverloadsRejectGrowthBeforeDefaultExtractorInputCanAllocateOrRead() {
        val bytes = ByteArray(1)
        val length = MetadataLimits.PEEK_WINDOW + 1
        val operations: List<(BoundedExtractorInput) -> Unit> = listOf(
            { it.peek(bytes, 0, length) },
            { it.peekFully(bytes, 0, length) },
            { it.peekFully(bytes, 0, length, true) },
            { it.advancePeekPosition(length) },
            { it.advancePeekPosition(length, true) },
        )
        for (operation in operations) {
            var reads = 0
            val source = DefaultExtractorInput(DataReader { _, _, _ ->
                reads++
                throw AssertionError("The upstream must not receive an oversized peek")
            }, 0, C.LENGTH_UNSET.toLong())
            val input = BoundedExtractorInput(source, Id3Admission(MetadataBudget()), object : ContainerAdmission {})
            try {
                operation(input)
                fail("Expected the window gate before upstream growth")
            } catch (error: MetadataAdmissionException) {
                assertEquals(MetadataAdmissionIssue.RESOURCE_LIMIT, error.issue)
            }
            assertEquals(0, reads)
            assertEquals(0L, source.peekPosition)
        }
    }

    @Test fun cancellationArrivingWithEofOrFalseIsStillPropagatedByEveryReadShape() {
        val bytes = ByteArray(1)
        val operations: List<(BoundedExtractorInput) -> Unit> = listOf(
            { it.read(bytes, 0, 1) },
            { it.readFully(bytes, 0, 1, true) },
            { it.peek(bytes, 0, 1) },
            { it.peekFully(bytes, 0, 1, true) },
            { it.advancePeekPosition(1, true) },
            { it.skip(1) },
            { it.skipFully(1, true) },
        )
        for (operation in operations) {
            val source = DefaultExtractorInput(DataReader { _, _, _ ->
                Thread.currentThread().interrupt()
                C.RESULT_END_OF_INPUT
            }, 0, C.LENGTH_UNSET.toLong())
            val input = BoundedExtractorInput(source, Id3Admission(MetadataBudget()), object : ContainerAdmission {})
            try {
                operation(input)
                fail("A cancelled EOF must not become ordinary completion")
            } catch (_: InterruptedIOException) {
                assertTrue(Thread.currentThread().isInterrupted)
            } finally {
                Thread.interrupted()
            }
        }
    }
}
