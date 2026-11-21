package io.github.onlinemo.meloxwatch.companion.data

import io.github.onlinemo.meloxwatch.data.transfer.TransferException
import io.github.onlinemo.meloxwatch.data.transfer.TransferIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** 调用 prepareIcon 共用的尺寸消费检查；不等同于真实 Bitmap/provider 或设备性能验收。 */
class CompanionImageBoundsTest {
    @Test fun decimalSixteenMillionPixelsAreAcceptedAtTheDeclaredBoundary() {
        assertEquals(16_000_000, CompanionLimits.IMAGE_PIXELS)
        validateCompanionImageBounds(4000, 4000)
        validateCompanionImageBounds(16_000_000, 1)
    }

    @Test fun onePixelOverAndTheOldBinaryBoundaryAreRejected() {
        for ((width, height) in listOf(16_000_001 to 1, 4096 to 4096)) {
            issue(TransferIssue.QUOTA_EXCEEDED) { validateCompanionImageBounds(width, height) }
        }
    }

    @Test fun nonpositiveDecodedDimensionsAreInvalidInput() {
        for ((width, height) in listOf(0 to 1, 1 to 0, -1 to 1, 1 to -1, 0 to 0, Int.MIN_VALUE to 1)) {
            issue(TransferIssue.INVALID_INPUT) { validateCompanionImageBounds(width, height) }
        }
    }

    @Test fun largePositiveDimensionsCannotBypassTheQuotaThroughIntegerMultiplicationOverflow() {
        for ((width, height) in listOf(Int.MAX_VALUE to Int.MAX_VALUE, Int.MAX_VALUE to 2)) {
            issue(TransferIssue.QUOTA_EXCEEDED) { validateCompanionImageBounds(width, height) }
        }
    }

    private fun issue(expected: TransferIssue, action: () -> Unit) {
        try {
            action()
            fail("Expected rejected image bounds")
        } catch (error: TransferException) { assertEquals(expected, error.issue) }
    }
}
