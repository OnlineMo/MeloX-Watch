package io.github.onlinemo.meloxwatch.data.fileoperations

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import android.system.StructStatVfs
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter

/** 限 LP10 云端套件；stat/seek/sync/flags/空间来自精确 Linux FD，不是设备 POSIX 验收。 */
@Implements(Os::class)
class FileOperationFixtureOsShadow {
    private data class Descriptor(val number: Int, val channel: FileChannel)

    companion object {
        // Linux O_PATH is 010000000; Android's public OsConstants has no matching field.
        private const val LINUX_O_PATH = 0x200000
        private val descriptors = IdentityHashMap<FileDescriptor, Descriptor>()

        fun register(descriptor: ParcelFileDescriptor, writable: Boolean) {
            val fd = descriptor.fileDescriptor
            check(fd.valid())
            synchronized(descriptors) {
                descriptors.entries.removeIf { !it.key.valid() }
                check(fd !in descriptors)
                val channel = if (writable) FileOutputStream(fd).channel else FileInputStream(fd).channel
                descriptors[fd] = Descriptor(descriptor.fd, channel)
            }
        }

        @JvmStatic
        @Resetter
        fun resetFixtureDescriptors() = synchronized(descriptors) {
            // 仅退休测试 FD 映射，不触碰进程播放器 release/quarantine。
            descriptors.clear()
        }

        @JvmStatic
        @Implementation
        fun fstat(fd: FileDescriptor): StructStat = exact("fstat", fd) { known ->
            val attributes = Files.readAttributes(Paths.get("/proc/self/fd", known.number.toString()),
                "unix:dev,ino,mode,size,ctime,lastModifiedTime")
            fun number(name: String): Long = (attributes[name] as? Number)?.toLong() ?: throw IOException("Native FD attribute absent")
            fun seconds(name: String): Long = (attributes[name] as? FileTime)?.to(TimeUnit.SECONDS)
                ?: throw IOException("Native FD timestamp absent")
            // 其余未被生产合同读取的 stat 字段不构造测试证据。
            StructStat(number("dev"), number("ino"), number("mode").toInt(), 0L, 0, 0, 0L, number("size"),
                0L, seconds("lastModifiedTime"), seconds("ctime"), 0L, 0L)
        }

        @JvmStatic
        @Implementation
        fun lseek(fd: FileDescriptor, offset: Long, whence: Int): Long = exact("lseek", fd) { known ->
            val base = when (whence) {
                OsConstants.SEEK_SET -> 0L
                OsConstants.SEEK_CUR -> known.channel.position()
                OsConstants.SEEK_END -> known.channel.size()
                else -> throw ErrnoException("lseek", OsConstants.EINVAL)
            }
            val position = try { Math.addExact(base, offset) } catch (_: ArithmeticException) {
                throw ErrnoException("lseek", OsConstants.EINVAL)
            }
            if (position < 0L) throw ErrnoException("lseek", OsConstants.EINVAL)
            known.channel.position(position)
            position
        }

        @JvmStatic
        @Implementation
        fun fsync(fd: FileDescriptor) = exact("fsync", fd) { fd.sync() }

        @JvmStatic
        @Implementation
        fun read(fd: FileDescriptor, bytes: ByteArray, offset: Int, count: Int): Int =
            probeAccess("read", fd, bytes, offset, count, writable = false)

        @JvmStatic
        @Implementation
        fun write(fd: FileDescriptor, bytes: ByteArray, offset: Int, count: Int): Int =
            probeAccess("write", fd, bytes, offset, count, writable = true)

        @JvmStatic
        @Implementation(minSdk = 30)
        fun fcntlInt(fd: FileDescriptor, command: Int, argument: Int): Int = exact("fcntl", fd) { known ->
            if (command != OsConstants.F_GETFL || argument != 0) throw ErrnoException("fcntl", OsConstants.EINVAL)
            val flags = Files.readAllLines(Paths.get("/proc/self/fdinfo", known.number.toString()))
                .singleOrNull { it.startsWith("flags:") }?.substringAfter(':')?.trim()
                ?: throw IOException("Native descriptor flags absent")
            flags.toLong(8).toInt()
        }

        private fun probeAccess(operation: String, fd: FileDescriptor, bytes: ByteArray, offset: Int,
            count: Int, writable: Boolean): Int {
            if (bytes.size != 1 || offset != 0 || count != 0) throw ErrnoException(operation, OsConstants.EINVAL)
            val flags = fcntlInt(fd, OsConstants.F_GETFL, 0)
            val mode = flags and OsConstants.O_ACCMODE
            val allowed = mode == OsConstants.O_RDWR || mode == if (writable) OsConstants.O_WRONLY else OsConstants.O_RDONLY
            if (!allowed || flags and LINUX_O_PATH != 0) throw ErrnoException(operation, OsConstants.EBADF)
            return 0
        }

        @JvmStatic
        @Implementation
        fun fstatvfs(fd: FileDescriptor): StructStatVfs = exact("fstatvfs", fd) { known ->
            val store = Files.getFileStore(Paths.get("/proc/self/fd", known.number.toString()))
            val block = store.blockSize
            check(block > 0L)
            StructStatVfs(block, block, store.totalSpace / block, store.unallocatedSpace / block,
                store.usableSpace / block, 0L, 0L, 0L, 0L, 0L, 0L)
        }

        private fun <T> exact(operation: String, fd: FileDescriptor, action: (Descriptor) -> T): T {
            val known = synchronized(descriptors) {
                descriptors[fd]?.takeIf { fd.valid() && it.channel.isOpen } ?: throw ErrnoException(operation, OsConstants.EBADF)
            }
            try {
                return action(known).also { if (!fd.valid()) throw ErrnoException(operation, OsConstants.EBADF) }
            } catch (failure: IOException) {
                throw ErrnoException(operation, OsConstants.EIO, failure)
            }
        }
    }
}
