package io.github.onlinemo.meloxwatch.data.database

import android.database.SQLException
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class SQLiteFailuresTest {
    @Test fun thePinnedPrefixAcceptsBareAndExtendedCodesAndRejectsMalformedNumbers() {
        assertEquals(13, bundledSQLiteErrorCode("Error code: 13"))
        assertEquals(787, bundledSQLiteErrorCode("Error code: 787, message: FOREIGN KEY constraint failed"))
        for (message in listOf(null, "Error code: ", "Error code: -13", "Error code: 13 ",
            "Error code: 2147483648", "Error code: 13, other: failure", "failure Error code: 13")) {
            assertNull(bundledSQLiteErrorCode(message))
        }
    }

    @Test fun anExtendedConstraintCodeKeepsItsTypeWhenErrorProseContainsAnotherCode() {
        val original = SQLException("Error code: 787, message: Error code: 13")
        val typed = original.toPlatformSQLiteException()
        assertTrue(typed is SQLiteConstraintException)
        assertFalse(typed is SQLiteFullException)
        assertSame(original, typed.cause)
        assertEquals(original.message, typed.message)
    }

    @Test fun diskFullKeepsTheOriginalAndDirectCleanupEvidence() {
        val original = SQLException("Error code: 13, message: database or disk is full")
        val cleanup = SQLException("Error code: 1, message: cannot rollback - no transaction is active")
        original.addSuppressed(cleanup)
        val typed = original.toPlatformSQLiteException()
        assertTrue(typed is SQLiteFullException)
        assertSame(original, typed.cause)
        assertEquals(1, typed.suppressed.size)
        assertTrue(typed.suppressed.single() is SQLiteException)
        assertSame(cleanup, typed.suppressed.single().cause)
        assertSame(cleanup, original.suppressed.single())
    }

    @Test fun frameworkIdentityAndUnknownNativeFailuresRemainIntact() {
        val original = SQLiteFullException("framework disk full")
        val cleanup = IllegalStateException("framework cleanup")
        original.addSuppressed(cleanup)
        assertSame(original, original.toPlatformSQLiteException())
        assertSame(cleanup, original.toPlatformSQLiteException().suppressed.single())
        for (message in listOf("Error code: 10, message: disk I/O error", "Error code: 13x", "unavailable")) {
            val unknown = SQLException(message)
            val typed = unknown.toPlatformSQLiteException()
            assertEquals(SQLiteException::class.java, typed.javaClass)
            assertSame(unknown, typed.cause)
        }
    }
}
