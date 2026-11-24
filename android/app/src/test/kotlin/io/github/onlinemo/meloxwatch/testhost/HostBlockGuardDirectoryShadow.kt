package io.github.onlinemo.meloxwatch.testhost

import java.io.FileDescriptor
import java.lang.reflect.InvocationTargetException
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowBlockGuardOs

/**
 * Robolectric 4.16.1的BlockGuard close为空，真实目录FD不能由此确认关闭。
 * 沿原ForwardingOs委托进入Linux桥；JDK没有Android的isSocket$，不执行该前置检查。
 */
@Implements(className = "libcore.io.BlockGuardOs", minSdk = 26, isInAndroidSdk = false)
class HostBlockGuardDirectoryShadow : ShadowBlockGuardOs() {
    @field:RealObject private lateinit var realOs: Any

    @Implementation
    protected override fun close(fd: FileDescriptor) {
        val delegate = delegateField.get(realOs)
        try { delegateClose.invoke(delegate, fd) }
        catch (failure: InvocationTargetException) {
            // 反射只传递原失败，保留fsync主异常与close suppressed的原身份。
            throw failure.targetException
        }
    }

    companion object {
        private val delegateField = Class.forName("libcore.io.ForwardingOs")
            .getDeclaredField("os").apply { setAccessible(true) }
        private val delegateClose = Class.forName("libcore.io.Os")
            .getMethod("close", FileDescriptor::class.java)
    }
}
