package io.github.onlinemo.meloxwatch.testhost

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.SQLiteMode

/** Real JNI transactions and reopen across both SDKs and distinct instrumentation configurations. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BundledSqliteHostIsolationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test(timeout = 30_000L)
    fun globalHostShadowLoadsRealSqlAndClosesBeforeReopening() {
        verifyNativeSqlAndClose()
    }

    @Test(timeout = 30_000L)
    @Config(shadows = [HostIsolationMarkerShadow::class])
    fun anotherInstrumentationConfigLoadsRealSqlAndClosesBeforeReopening() {
        assertEquals(2, HostIsolationMarker().value())
        verifyNativeSqlAndClose()
    }

    private fun verifyNativeSqlAndClose() {
        assertSame(javaClass.classLoader, BundledSQLiteDriver::class.java.classLoader)
        val file = temporaryFolder.newFile("native-host-scope.db")
        val driver = BundledSQLiteDriver()
        driver.open(file.absolutePath).use { connection ->
            execute(connection, "CREATE TABLE host_scope_probe(value INTEGER NOT NULL)")
            execute(connection, "BEGIN IMMEDIATE")
            execute(connection, "INSERT INTO host_scope_probe VALUES (101)")
            execute(connection, "ROLLBACK")
            connection.prepare("SELECT COUNT(*) FROM host_scope_probe").use { statement ->
                assertTrue(statement.step())
                assertEquals(0L, statement.getLong(0))
                assertFalse(statement.step())
            }
            execute(connection, "INSERT INTO host_scope_probe VALUES (42)")
        }
        driver.open(file.absolutePath).use { connection ->
            connection.prepare("SELECT value FROM host_scope_probe").use { statement ->
                assertTrue(statement.step())
                assertEquals(42L, statement.getLong(0))
                assertFalse(statement.step())
            }
            connection.prepare("PRAGMA integrity_check").use { statement ->
                assertTrue(statement.step())
                assertEquals("ok", statement.getText(0))
                assertFalse(statement.step())
            }
        }
    }

    private fun execute(connection: SQLiteConnection, sql: String) {
        connection.prepare(sql).use { assertFalse(it.step()) }
    }
}

/** An extra instrumented class gives the same SDK another actual sandbox cache key. */
class HostIsolationMarker {
    fun value(): Int = 1
}

@Implements(HostIsolationMarker::class, isInAndroidSdk = false)
class HostIsolationMarkerShadow {
    @Implementation protected fun value(): Int = 2
}
