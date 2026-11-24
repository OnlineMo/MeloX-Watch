package io.github.onlinemo.meloxwatch.testhost

import io.github.onlinemo.meloxwatch.MeloXApplication
import io.github.onlinemo.meloxwatch.data.backup.BackupStartupState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** 全局主机桥须在真实Application的异步启动前安装。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], application = MeloXApplication::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MeloXStorageStartupHostTest {
    @Test(timeout = 30_000L)
    fun actualApplicationStartupOpensItsRealJournalAndReachesTheAuthoritativeReadyState() = runBlocking {
        val application = RuntimeEnvironment.getApplication() as MeloXApplication
        withTimeout(20_000L) { application.requireBackupStorageReady() }
        assertTrue(application.backups.startup.value is BackupStartupState.Ready)
        application.backups.requireReady()
        assertEquals(0, HostLinuxDirectoryShadow.activeDescriptors)
    }
}
