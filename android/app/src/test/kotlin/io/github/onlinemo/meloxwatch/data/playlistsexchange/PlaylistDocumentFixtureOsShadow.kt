package io.github.onlinemo.meloxwatch.data.playlistsexchange

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Paths
import java.util.IdentityHashMap
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter
import org.robolectric.shadows.ShadowParcelFileDescriptor

/**
 * 仅供歌单文档的云端套件；Robolectric 4.16.1 的默认 fstat(fd) 不读取该描述符。
 * 通过公开 PFD getter 登记精确 FD，再从 Linux /proc/self/fd 读取实际 dev/inode/mode/size。
 * seek、零长度截断和同步作用于这个实际 FD，不重开 URI 或目标路径，也不是设备 POSIX 验收。
 */
@Implements(Os::class)
class PlaylistDocumentFixtureOsShadow {
    private class Descriptor(
        val number: Int,
        val writable: Boolean,
        val channel: FileChannel,
    )

    companion object {
        private val lock = Any()
        private val descriptors = IdentityHashMap<FileDescriptor, Descriptor>()

        fun register(descriptor: ParcelFileDescriptor, writable: Boolean) {
            val fd = descriptor.fileDescriptor
            check(fd.valid()) { "The fixture must register an open descriptor" }
            val number = descriptor.fd
            synchronized(lock) {
                check(!descriptors.containsKey(fd)) { "The fixture descriptor was already registered" }
                descriptors.entries.removeIf { !it.key.valid() }
                // 流附着于同一 FD；最终 PFD/RandomAccessFile 所有者关闭时一起关闭通道。
                val channel = if (writable) FileOutputStream(fd).channel else FileInputStream(fd).channel
                descriptors[fd] = Descriptor(number, writable, channel)
            }
        }

        internal fun allKnownDescriptorsClosed(): Boolean = synchronized(lock) {
            descriptors.keys.none { it.valid() }
        }

        internal fun duplicateForCleanup(fd: FileDescriptor): ParcelFileDescriptor {
            var allocated: ParcelFileDescriptor? = null
            var delivered = false
            try {
                val duplicate = descriptorIo("dup", fd) { registered ->
                    // 生产 cleanup 已 seek 到 0，且只读取副本；其它 dup 用法不由本夹具覆盖。
                    if (registered.channel.position() != 0L) throw ErrnoException("dup", OsConstants.EINVAL)
                    ParcelFileDescriptor.open(
                        File("/proc/self/fd", registered.number.toString()),
                        ParcelFileDescriptor.MODE_READ_ONLY,
                    ).also {
                        allocated = it
                        register(it, writable = false)
                    }
                }
                delivered = true
                return duplicate
            } finally {
                if (!delivered) allocated?.close()
            }
        }

        @JvmStatic
        @Resetter
        fun reset() {
            synchronized(lock) { descriptors.clear() }
        }

        @JvmStatic
        @Implementation
        fun fstat(fd: FileDescriptor): StructStat = descriptorIo("fstat", fd) { registered ->
            val attributes = Files.readAttributes(
                Paths.get("/proc/self/fd", registered.number.toString()),
                "unix:dev,ino,mode,size",
            )
            // 生产合同只读取这四个字段；其它未覆盖的 stat 字段不构造测试证据。
            StructStat(
                attributes.number("dev"),
                attributes.number("ino"),
                attributes.number("mode").toInt(),
                0L,
                0,
                0,
                0L,
                attributes.number("size"),
                0L,
                0L,
                0L,
                0L,
                0L,
            )
        }

        @JvmStatic
        @Implementation
        fun lseek(fd: FileDescriptor, offset: Long, whence: Int): Long = descriptorIo("lseek", fd) { registered ->
            val base = when (whence) {
                OsConstants.SEEK_SET -> 0L
                OsConstants.SEEK_CUR -> registered.channel.position()
                OsConstants.SEEK_END -> registered.channel.size()
                else -> throw ErrnoException("lseek", OsConstants.EINVAL)
            }
            val position = try {
                Math.addExact(base, offset)
            } catch (_: ArithmeticException) {
                throw ErrnoException("lseek", OsConstants.EINVAL)
            }
            if (position < 0L) throw ErrnoException("lseek", OsConstants.EINVAL)
            registered.channel.position(position)
            position
        }

        @JvmStatic
        @Implementation
        fun ftruncate(fd: FileDescriptor, length: Long) {
            descriptorIo("ftruncate", fd) { registered ->
                if (!registered.writable) throw ErrnoException("ftruncate", OsConstants.EBADF)
                // 本套件只覆盖生产清理的长度 0；不冒充 FileChannel 未实现的 POSIX 扩展语义。
                if (length != 0L) throw ErrnoException("ftruncate", OsConstants.EINVAL)
                registered.channel.truncate(0L)
            }
        }

        @JvmStatic
        @Implementation
        fun fsync(fd: FileDescriptor) {
            descriptorIo("fsync", fd) { fd.sync() }
        }

        private fun <T> descriptorIo(operation: String, fd: FileDescriptor, action: (Descriptor) -> T): T {
            val registered = synchronized(lock) {
                descriptors[fd]?.takeIf { fd.valid() && it.channel.isOpen }
                    ?: throw ErrnoException(operation, OsConstants.EBADF)
            }
            return try {
                val value = action(registered)
                if (!fd.valid()) throw ErrnoException(operation, OsConstants.EBADF)
                value
            } catch (error: IOException) {
                throw ErrnoException(operation, OsConstants.EIO, error)
            }
        }

        private fun Map<String, Any>.number(name: String): Long =
            (get(name) as? Number)?.toLong() ?: throw IOException("Fixture Unix descriptor metadata unavailable")
    }
}

/**
 * 默认静态 PFD.dup 在 Robolectric 4.16.1 复制字节到别的临时文件；cleanup 需读同一真实 inode。
 * 此子类沿用固定 PFD shadow 的实际 RandomAccessFile 打开/关闭，只替换有界、只读的 cleanup 副本。
 * /proc 重开有独立 offset；这里只覆盖生产的零起点读取与关闭，不是 POSIX dup 共享 offset 验收。
 */
@Implements(ParcelFileDescriptor::class)
class PlaylistDocumentFixtureParcelShadow : ShadowParcelFileDescriptor() {
    companion object {
        @JvmStatic
        @Implementation
        fun dup(fd: FileDescriptor): ParcelFileDescriptor = PlaylistDocumentFixtureOsShadow.duplicateForCleanup(fd)
    }
}
