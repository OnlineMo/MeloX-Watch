package io.github.onlinemo.meloxwatch.data.playlistsexchange

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 核验测试夹具对真实云端 FD 的效果，避免把任意 stat/截断成功值当作生产回归证据。 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [26, 30],
    manifest = Config.NONE,
    shadows = [PlaylistDocumentFixtureOsShadow::class, PlaylistDocumentFixtureParcelShadow::class],
)
class PlaylistDocumentFixtureOsShadowTest {
    @Rule
    @JvmField
    val temporary = TemporaryFolder()

    @Test
    fun independentReopensKeepTheActualInodeAndObserveCurrentFileLength() {
        val fixture = fixture(ByteArray(64) { it.toByte() })
        fixture.open("rw").use { first ->
            fixture.open("r").use { second ->
                val before = Os.fstat(first.fileDescriptor)
                val reopened = Os.fstat(second.fileDescriptor)
                assertTrue(OsConstants.S_ISREG(before.st_mode))
                assertEquals(before.st_dev, reopened.st_dev)
                assertEquals(before.st_ino, reopened.st_ino)
                assertEquals(64L, before.st_size)
                FileOutputStream(fixture.file, true).use { it.write(byteArrayOf(1, 2, 3)) }
                assertEquals(67L, Os.fstat(first.fileDescriptor).st_size)
                assertEquals(67L, Os.fstat(second.fileDescriptor).st_size)
                assertEquals(67L, second.statSize)
            }
        }
        fixture.assertClosed()
    }

    @Test
    fun seekChangesTheActualInputPositionInsteadOfAnOffsetCache() {
        val fixture = fixture(ByteArray(64) { it.toByte() })
        fixture.open("r").use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val fd = descriptor.fileDescriptor
                assertEquals(17L, Os.lseek(fd, 17L, OsConstants.SEEK_SET))
                assertEquals(17, input.read())
                assertEquals(18L, Os.lseek(fd, 0L, OsConstants.SEEK_CUR))
                assertEquals(63L, Os.lseek(fd, -1L, OsConstants.SEEK_END))
                assertEquals(63, input.read())
                assertErrno(OsConstants.EINVAL) { Os.lseek(fd, -1L, OsConstants.SEEK_SET) }
            }
        }
        fixture.assertClosed()
    }

    @Test
    fun truncationAfterUriRemappingChangesOnlyTheAlreadyOpenedObject() {
        val original = ByteArray(128) { (it % 97).toByte() }
        val fixture = fixture(original)
        val foreign = temporary.newFile("foreign.txt").apply { writeBytes(original) }
        fixture.open("rw").use { descriptor ->
            val fd = descriptor.fileDescriptor
            val identity = Os.fstat(fd)
            fixture.provider.document = foreign
            fixture.open("rw").use { replacement ->
                val foreignIdentity = Os.fstat(replacement.fileDescriptor)
                assertNotEquals(identity.st_ino, foreignIdentity.st_ino)
                assertEquals(identity.st_ino, Os.fstat(fd).st_ino)
                Os.ftruncate(fd, 0L)
                Os.fsync(fd)
                assertEquals(0L, fixture.file.length())
                assertEquals(0L, descriptor.statSize)
                assertEquals(0L, Os.fstat(fd).st_size)
                assertTrue(foreign.readBytes().contentEquals(original))
                assertEquals(128L, Os.fstat(replacement.fileDescriptor).st_size)
            }
        }
        fixture.assertClosed()
    }

    @Test
    fun unknownClosedAndReadOnlyDescriptorsCannotAuthorizeTruncation() {
        val original = byteArrayOf(1, 2, 3, 4)
        val fixture = fixture(original)
        assertErrno(OsConstants.EBADF) { Os.fstat(FileDescriptor()) }
        RandomAccessFile(fixture.file, "rw").use { unrelated ->
            assertTrue(unrelated.fd.valid())
            assertErrno(OsConstants.EBADF) { Os.fstat(unrelated.fd) }
            assertErrno(OsConstants.EBADF) { Os.ftruncate(unrelated.fd, 0L) }
        }
        val descriptor = fixture.open("r")
        val fd = descriptor.fileDescriptor
        descriptor.use {
            assertErrno(OsConstants.EBADF) { Os.ftruncate(fd, 0L) }
            assertTrue(fixture.file.readBytes().contentEquals(original))
        }
        assertFalse(fd.valid())
        assertErrno(OsConstants.EBADF) { Os.fstat(fd) }
        assertErrno(OsConstants.EBADF) { Os.lseek(fd, 0L, OsConstants.SEEK_SET) }
        assertErrno(OsConstants.EBADF) { Os.ftruncate(fd, 0L) }
        assertErrno(OsConstants.EBADF) { Os.fsync(fd) }
        assertTrue(fixture.file.readBytes().contentEquals(original))
        fixture.assertClosed()
    }

    @Test
    fun renamingTheFileAndReplacingItsOldPathCannotChangeTheHeldDescriptorIdentity() {
        val original = ByteArray(64) { it.toByte() }
        val fixture = fixture(original)
        fixture.open("rw").use { descriptor ->
            val fd = descriptor.fileDescriptor
            val before = Os.fstat(fd)
            val moved = File(temporary.root, "moved.txt")
            Files.move(fixture.file.toPath(), moved.toPath())
            fixture.file.writeBytes(original)
            fixture.open("rw").use { replacement ->
                val after = Os.fstat(fd)
                assertEquals(before.st_dev, after.st_dev)
                assertEquals(before.st_ino, after.st_ino)
                assertNotEquals(before.st_ino, Os.fstat(replacement.fileDescriptor).st_ino)
                Os.ftruncate(fd, 0L)
                assertEquals(0L, moved.length())
                assertTrue(fixture.file.readBytes().contentEquals(original))
            }
        }
        fixture.assertClosed()
    }

    @Test
    fun cleanupDuplicateReadsTheHeldInodeAndClosingItKeepsTheOriginalDescriptorOpen() {
        val original = ByteArray(64) { it.toByte() }
        val fixture = fixture(original)
        val foreign = temporary.newFile("foreign.txt").apply { writeText("Foreign bytes") }
        fixture.open("rw").use { descriptor ->
            val fd = descriptor.fileDescriptor
            val identity = Os.fstat(fd)
            fixture.provider.document = foreign
            val duplicate = ParcelFileDescriptor.dup(fd)
            val duplicateFd = duplicate.fileDescriptor
            assertEquals(identity.st_dev, Os.fstat(duplicateFd).st_dev)
            assertEquals(identity.st_ino, Os.fstat(duplicateFd).st_ino)
            assertErrno(OsConstants.EBADF) { Os.ftruncate(duplicateFd, 0L) }
            ParcelFileDescriptor.AutoCloseInputStream(duplicate).use { input ->
                assertTrue(input.readBytes().contentEquals(original))
            }
            assertFalse(duplicateFd.valid())
            assertTrue(fd.valid())
            assertFalse(fixture.provider.allDescriptorsClosed)
            assertEquals(0L, Os.lseek(fd, 0L, OsConstants.SEEK_CUR))
            assertEquals(identity.st_ino, Os.fstat(fd).st_ino)
            Os.ftruncate(fd, 0L)
            assertEquals(0L, fixture.file.length())
            assertEquals("Foreign bytes", foreign.readText())
            assertEquals(1L, Os.lseek(fd, 1L, OsConstants.SEEK_SET))
            assertErrno(OsConstants.EINVAL) { ParcelFileDescriptor.dup(fd) }
        }
        fixture.assertClosed()
    }

    private fun fixture(bytes: ByteArray): Fixture {
        val context: Context = RuntimeEnvironment.getApplication()
        val file = temporary.newFile("document.txt").apply { writeBytes(bytes) }
        val authority = "${context.packageName}.playlist-os-fixture"
        val provider = fixturePlaylistDocumentProvider(context, authority, file)
        val uri = DocumentsContract.buildDocumentUri(authority, FixturePlaylistDocumentProvider.DOCUMENT_ID)
        return Fixture(context, file, provider, uri)
    }

    private class Fixture(
        val context: Context,
        val file: File,
        val provider: FixturePlaylistDocumentProvider,
        val uri: Uri,
    ) {
        fun open(mode: String): ParcelFileDescriptor = checkNotNull(context.contentResolver.openFileDescriptor(uri, mode))

        fun assertClosed() {
            assertTrue(provider.allDescriptorsClosed)
            assertEquals(0, provider.deleteCount.get())
            assertEquals(0, provider.childQueryCount.get())
        }
    }

    private fun assertErrno(expected: Int, action: () -> Unit) {
        try {
            action()
            throw AssertionError("Expected fixture errno $expected")
        } catch (error: ErrnoException) {
            assertEquals(expected, error.errno)
        }
    }
}
