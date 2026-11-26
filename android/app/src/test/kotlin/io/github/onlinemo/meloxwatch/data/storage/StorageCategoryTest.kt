package io.github.onlinemo.meloxwatch.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class StorageCategoryTest {
    @Test fun anUnmeasuredReferenceWithoutAnyMeasuredFileDoesNotBecomeZeroBytes() {
        val value = combineStorage(StorageCategory(0L, 0L), StorageCategory(null, 0L, 1L, StorageIssue.PERMISSION_REVOKED))
        assertNull(value.bytes)
        assertNull(value.observedBytes)
        assertEquals(1L, value.unknownFiles)
        assertFalse(value.complete)
    }

    @Test fun anAccessibleFileBesideAnUnknownReferenceRemainsAnExplicitSubtotal() {
        val value = combineStorage(StorageCategory(37L, 1L), StorageCategory(null, 0L, 1L, StorageIssue.INCOMPLETE))
        assertEquals(37L, value.observedBytes)
        assertEquals(1L, value.files)
        assertEquals(1L, value.unknownFiles)
        assertFalse(value.complete)
    }

    @Test fun anAppendableEmptyStageIsMeasuredAsAFileWithoutClaimingACompleteCapacity() {
        val value = StorageCategory(0L, 1L, issue = StorageIssue.INCOMPLETE)
        assertEquals(0L, value.observedBytes)
        assertFalse(value.complete)
        assertNull(StorageCategory(0L, 0L, issue = StorageIssue.UNAVAILABLE).observedBytes)
    }
}
