package io.github.onlinemo.meloxwatch.data.backup

import android.app.Application
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import io.github.onlinemo.meloxwatch.testhost.HostIsolationMarker
import io.github.onlinemo.meloxwatch.testhost.HostIsolationMarkerShadow
import io.github.onlinemo.meloxwatch.testhost.HostLinuxDirectoryShadow
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 30], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BackupHostDirectoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val flags get() = OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK

    // Config.NONE在Build System API下不隔离Application；目录故障hook只属于本套件。
    @After fun retireHostDirectoryHooks() {
        HostLinuxDirectoryShadow.reset()
    }

    @Test(timeout = 30_000L)
    fun realDirectoryDescriptorSyncsAndClosesWithAllProductionFlags() {
        syncBackupDirectory(temporary.newFolder())
        assertEquals(flags, HostLinuxDirectoryShadow.lastOpenFlags.get())
        assertEquals(0, HostLinuxDirectoryShadow.activeDescriptors)
    }

    @Test(timeout = 30_000L)
    @Config(shadows = [HostIsolationMarkerShadow::class])
    fun anAdditionalSandboxShadowKeepsBothRealDirectoryIoAndTheNativeJournal() {
        assertEquals(2, HostIsolationMarker().value())
        val directory = BackupPrivateDirectory.open(temporary.newFolder())
        BackupRestoreJournal.open(directory).use { assertEquals(null, it.readRun()) }
        BackupRestoreJournal.open(directory).use { assertEquals(null, it.readRun()) }
        assertEquals(0, HostLinuxDirectoryShadow.activeDescriptors)
    }

    @Test(timeout = 30_000L)
    fun nofollowAndDirectoryTypeAreEnforcedByHostIoAndTheProductionProof() {
        val directory = temporary.newFolder().toPath()
        val link = temporary.root.toPath().resolve("synthetic-directory-link")
        Files.createSymbolicLink(link, directory)
        try {
            Os.open(link.toString(), flags, 0)
            throw AssertionError("A final symlink must not yield a directory descriptor")
        } catch (failure: ErrnoException) {
            assertEquals(OsConstants.ELOOP, failure.errno)
        }
        expectBackupIssue(BackupIssue.PATH_ESCAPE) { syncBackupDirectory(temporary.newFile()) }
        assertEquals(0, HostLinuxDirectoryShadow.activeDescriptors)
    }

    @Test(timeout = 30_000L)
    fun fstatKeepsTheOpenedDirectoryIdentityAfterItsOriginalNameIsReplaced() {
        val directory = temporary.newFolder().toPath()
        val descriptor = Os.open(directory.toString(), flags, 0)
        try {
            val before = Os.fstat(descriptor)
            Files.move(directory, directory.resolveSibling("synthetic-held-directory"))
            Files.createDirectory(directory)
            val opened = Os.fstat(descriptor)
            val replacement = Os.lstat(directory.toString())
            assertTrue(before.st_ino > 0L && before.st_dev >= 0L)
            assertTrue(before.st_dev == opened.st_dev && before.st_ino == opened.st_ino)
            assertFalse(before.st_dev == replacement.st_dev && before.st_ino == replacement.st_ino)
            Os.fsync(descriptor)
        } finally { Os.close(descriptor) }
        assertFalse(descriptor.valid())
        try {
            Os.fstat(descriptor)
            throw AssertionError("A closed descriptor has no valid identity")
        }
        catch (failure: ErrnoException) { assertEquals(OsConstants.EBADF, failure.errno) }
    }

    @Test(timeout = 30_000L)
    fun replacementBetweenIdentityCaptureAndOpenCannotBeAcknowledgedAsTheOriginalDirectory() {
        val directory = temporary.newFolder()
        HostLinuxDirectoryShadow.beforeNextOpen {
            Files.move(directory.toPath(), directory.toPath().resolveSibling("synthetic-before-open"))
            Files.createDirectory(directory.toPath())
        }
        expectBackupIssue(BackupIssue.INTEGRITY_MISMATCH) { syncBackupDirectory(directory) }
        assertEquals(0, HostLinuxDirectoryShadow.activeDescriptors)
    }

    @Test(timeout = 30_000L)
    fun aDirectoryReplacedByARealFifoCannotBlockOpenOrReachFsync() {
        val directory = temporary.newFolder()
        val synced = AtomicBoolean(false)
        HostLinuxDirectoryShadow.beforeNextOpen {
            Files.move(directory.toPath(), directory.toPath().resolveSibling("synthetic-before-fifo"))
            HostLinuxDirectoryShadow.createFifo(directory.toPath())
        }
        HostLinuxDirectoryShadow.beforeNextSync { synced.set(true) }
        expectBackupIssue(BackupIssue.INTEGRITY_MISMATCH) { syncBackupDirectory(directory) }
        assertFalse(synced.get())
        assertEquals(0, HostLinuxDirectoryShadow.activeDescriptors)
    }

    @Test(timeout = 30_000L)
    fun aRealClosedFdSyncFailureStaysPrimaryWhenCloseAlsoRejectsTheFd() {
        HostLinuxDirectoryShadow.beforeNextSync { Os.close(it) }
        try {
            syncBackupDirectory(temporary.newFolder())
            throw AssertionError("Syncing a closed descriptor must fail")
        } catch (failure: ErrnoException) {
            assertTrue(failure.stackTrace.any { it.methodName.endsWith("fsync") })
            assertEquals(OsConstants.EBADF, failure.errno)
            assertEquals(1, failure.suppressed.size)
            assertTrue(failure.suppressed.single() is ErrnoException)
        }
        assertEquals(0, HostLinuxDirectoryShadow.activeDescriptors)
    }
}
