package io.github.onlinemo.meloxwatch.data.imports

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportSpacePolicyTest {
    @Test fun quotaAndReserveAllowTheirExactBoundaries() {
        ImportSpacePolicy.requireRoom(0, 0, ImportLimits.QUOTA_BYTES,
            ImportLimits.FREE_RESERVE_BYTES + ImportLimits.QUOTA_BYTES)
        ImportSpacePolicy.requireRoom(ImportLimits.QUOTA_BYTES, 0, 0, ImportLimits.FREE_RESERVE_BYTES)
        ImportSpacePolicy.requireRoom(0, ImportLimits.QUOTA_BYTES - 1, 1, ImportLimits.FREE_RESERVE_BYTES + 1)
    }

    @Test fun quotaAndReserveRejectOneByteOverflowAndCannotOverflowLongArithmetic() {
        expect(ImportIssue.QUOTA_EXCEEDED) { ImportSpacePolicy.requireRoom(ImportLimits.QUOTA_BYTES, 0, 1, Long.MAX_VALUE) }
        expect(ImportIssue.QUOTA_EXCEEDED) { ImportSpacePolicy.requireRoom(1, ImportLimits.QUOTA_BYTES, 0, Long.MAX_VALUE) }
        expect(ImportIssue.QUOTA_EXCEEDED) { ImportSpacePolicy.requireRoom(Long.MAX_VALUE, 0, Long.MAX_VALUE, Long.MAX_VALUE) }
        expect(ImportIssue.STORAGE_FULL) { ImportSpacePolicy.requireRoom(0, 0, 1, ImportLimits.FREE_RESERVE_BYTES) }
        expect(ImportIssue.STORAGE_FULL) { ImportSpacePolicy.requireRoom(0, 0, 0, ImportLimits.FREE_RESERVE_BYTES - 1) }
        expect(ImportIssue.INVALID_INPUT) { ImportSpacePolicy.requireRoom(0, -1, 0, Long.MAX_VALUE) }
    }

    private fun expect(issue: ImportIssue, action: () -> Unit) {
        try { action(); throw AssertionError("Expected $issue") }
        catch (error: ImportException) { assertEquals(issue, error.issue) }
    }
}
