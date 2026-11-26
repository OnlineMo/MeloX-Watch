package io.github.onlinemo.meloxwatch.testhost

import android.system.ErrnoException
import android.system.OsConstants
import android.system.StructStat
import java.io.FileDescriptor
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter
import org.robolectric.shadows.ShadowLinux

/**
 * Robolectric 4.16.1的默认Linux shadow用RandomAccessFile打开目录，并返回零inode。
 * JDK17/Linux测试桥逐字传递旗标，持有真实主机FD并执行真实同步。
 * 桥接仅用于Test JVM；生产Android IO及原生库依赖保持原路径。
 */
@Implements(className = "libcore.io.Linux", minSdk = 26, isInAndroidSdk = false)
class HostLinuxDirectoryShadow : ShadowLinux() {
    @Implementation
    override fun lstat(path: String): StructStat = HostDirectoryPosix.call("lstat") {
        HostDirectoryPosix.stat(HostDirectoryPosix.pathAttributes.invoke(null, Path.of(path), false))
    }

    @Implementation
    override fun fstat(fd: FileDescriptor): StructStat = HostDirectoryPosix.call("fstat") {
        HostDirectoryPosix.stat(HostDirectoryPosix.fdAttributes.invoke(null, HostDirectoryPosix.number(fd, "fstat")))
    }

    @Implementation
    override fun open(path: String, flags: Int, mode: Int): FileDescriptor = HostDirectoryPosix.call("open") {
        beforeOpen.getAndSet(null)?.invoke()
        lastOpenFlags.set(flags)
        val number = HostDirectoryPosix.nativeOpen.invoke(null, Path.of(path), flags, mode) as Int
        val descriptor = FileDescriptor()
        HostDirectoryPosix.descriptorNumber.setInt(descriptor, number)
        try {
            val access = flags and OsConstants.O_ACCMODE
            val channel = HostDirectoryPosix.channelOpen.invoke(null, descriptor, null,
                access != OsConstants.O_WRONLY, access != OsConstants.O_RDONLY, false, null) as FileChannel
            synchronized(handles) { check(handles.put(descriptor, channel) == null) }
            descriptor
        } catch (failure: Throwable) {
            HostDirectoryPosix.nativeClose.invoke(null, number)
            HostDirectoryPosix.descriptorNumber.setInt(descriptor, -1)
            throw failure
        }
    }

    @Implementation
    protected fun fsync(fd: FileDescriptor) {
        beforeSync.getAndSet(null)?.invoke(fd)
        HostDirectoryPosix.number(fd, "fsync")
        HostDirectoryPosix.call("fsync") {
            val channel = synchronized(handles) { handles[fd] }
            if (channel != null) channel.force(true)
            else {
                // 提供方可独立返回真实主机FD，同步仍作用于该FD。
                var status: Int
                do { status = HostDirectoryPosix.force.invoke(null, fd, true) as Int } while (status == HostDirectoryPosix.JDK_INTERRUPTED)
                if (status < 0) throw ErrnoException("fsync", OsConstants.EIO)
            }
        }
    }

    @Implementation
    protected fun close(fd: FileDescriptor) = HostDirectoryPosix.call("close") {
        HostDirectoryPosix.number(fd, "close")
        val channel = synchronized(handles) { handles[fd] }
        if (channel == null) {
            // JDK-owned descriptors must retire their registered cleaner before the native number is freed.
            HostDirectoryPosix.descriptorClose.invoke(fd)
        } else {
            channel.close()
            synchronized(handles) { handles.remove(fd) }
        }
        Unit
    }

    companion object {
        private val handles = IdentityHashMap<FileDescriptor, FileChannel>()
        private val beforeOpen = AtomicReference<(() -> Unit)?>(null)
        private val beforeSync = AtomicReference<((FileDescriptor) -> Unit)?>(null)
        internal val lastOpenFlags = AtomicInteger()
        internal val activeDescriptors: Int get() = synchronized(handles) { handles.size }
        internal fun beforeNextOpen(action: () -> Unit) { check(beforeOpen.compareAndSet(null, action)) }
        internal fun beforeNextSync(action: (FileDescriptor) -> Unit) { check(beforeSync.compareAndSet(null, action)) }
        internal fun createFifo(path: Path) = HostDirectoryPosix.call("mknod") {
            HostDirectoryPosix.nativeMknod.invoke(null, path, OsConstants.S_IFIFO or 0x180, 0L)
            Unit
        }

        @JvmStatic
        @Resetter
        fun reset() {
            beforeOpen.set(null)
            beforeSync.set(null)
            lastOpenFlags.set(0)
            val remaining = synchronized(handles) { handles.values.toList() }
            try { remaining.forEach(FileChannel::close) }
            finally { synchronized(handles) { handles.clear() } }
            check(remaining.isEmpty()) { "A host directory descriptor was not closed by its owner" }
        }
    }
}

/** 固定JDK17方法契约；主机API不可用时，正常路径明确失败。 */
private object HostDirectoryPosix {
    // JDK17以IOStatus.INTERRUPTED报告EINTR；其他原生失败仍抛出异常。
    const val JDK_INTERRUPTED = -3
    private val unixPath = Class.forName("sun.nio.fs.UnixPath")
    private val unixAttributes = Class.forName("sun.nio.fs.UnixFileAttributes")
    private val dispatcher = Class.forName("sun.nio.fs.UnixNativeDispatcher")
    private val unixException = Class.forName("sun.nio.fs.UnixException")
    val descriptorNumber = FileDescriptor::class.java.getDeclaredField("fd").apply { setAccessible(true) }
    val descriptorClose = FileDescriptor::class.java.accessibleMethod("close")
    val nativeOpen = dispatcher.accessibleMethod("open", unixPath, Integer.TYPE, Integer.TYPE)
    val nativeClose = dispatcher.accessibleMethod("close", Integer.TYPE)
    val nativeMknod = dispatcher.accessibleMethod("mknod", unixPath, Integer.TYPE, java.lang.Long.TYPE)
    val pathAttributes = unixAttributes.accessibleMethod("get", unixPath, java.lang.Boolean.TYPE)
    val fdAttributes = unixAttributes.accessibleMethod("get", Integer.TYPE)
    val channelOpen = Class.forName("sun.nio.ch.FileChannelImpl").accessibleMethod("open", FileDescriptor::class.java,
        String::class.java, java.lang.Boolean.TYPE, java.lang.Boolean.TYPE, java.lang.Boolean.TYPE, Any::class.java)
    val force = Class.forName("sun.nio.ch.FileDispatcherImpl").accessibleMethod("force0", FileDescriptor::class.java, java.lang.Boolean.TYPE)
    private val errno = unixException.accessibleMethod("errno")
    private val values = listOf("dev", "ino", "mode", "nlink", "uid", "gid", "rdev", "size", "lastAccessTime", "lastModifiedTime", "ctime")
        .associateWith { unixAttributes.accessibleMethod(it) }

    private fun Class<*>.accessibleMethod(name: String, vararg types: Class<*>): Method =
        getDeclaredMethod(name, *types).apply { setAccessible(true) }

    fun number(descriptor: FileDescriptor, operation: String): Int {
        val value = descriptorNumber.getInt(descriptor)
        if (!descriptor.valid() || value < 0) throw ErrnoException(operation, OsConstants.EBADF)
        return value
    }

    fun stat(attributes: Any?): StructStat {
        val actual = checkNotNull(attributes)
        fun number(name: String) = (values.getValue(name).invoke(actual) as Number).toLong()
        fun seconds(name: String) = (values.getValue(name).invoke(actual) as FileTime).to(TimeUnit.SECONDS)
        // JDK属性覆盖备份合同使用的身份与时间字段，块计数未作为证据。
        return StructStat(number("dev"), number("ino"), number("mode").toInt(), number("nlink"), number("uid").toInt(),
            number("gid").toInt(), number("rdev"), number("size"), seconds("lastAccessTime"), seconds("lastModifiedTime"),
            seconds("ctime"), 0L, 0L)
    }

    fun <T> call(operation: String, action: () -> T): T = try { action() }
    catch (failure: InvocationTargetException) {
        val cause = failure.targetException
        val code = if (unixException.isInstance(cause)) errno.invoke(cause) as Int else OsConstants.EIO
        // 主机异常文本可能含私有路径，桥接只传递固定操作名和errno。
        throw ErrnoException(operation, code)
    } catch (_: IOException) { throw ErrnoException(operation, OsConstants.EIO) }
}
