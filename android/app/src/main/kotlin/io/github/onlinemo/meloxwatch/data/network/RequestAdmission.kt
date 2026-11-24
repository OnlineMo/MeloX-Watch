package io.github.onlinemo.meloxwatch.data.network

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Semaphore

/** A cancelled socket retains its active lease until its terminal callback has closed the body. */
internal class RequestAdmission {
    private val admitted = AtomicInteger()
    private val active = Semaphore(NetworkLimits.ACTIVE_REQUESTS)

    /**
     * 只读观察当前活动与等待的 admission 总数，不表示 socket 数。
     * 测试先固定活动调用，再读取此值；并发状态变化时它只是一次原子快照。
     */
    internal fun admittedRequestCount(): Int = admitted.get()

    suspend fun enter(): Lease {
        while (true) {
            val count = admitted.get()
            if (count >= NetworkLimits.ACTIVE_REQUESTS + NetworkLimits.WAITING_REQUESTS) {
                throw NetworkException(NetworkIssue.BUSY)
            }
            if (admitted.compareAndSet(count, count + 1)) break
        }
        try {
            active.acquire()
        } catch (error: Throwable) {
            admitted.decrementAndGet()
            throw error
        }
        return Lease {
            active.release()
            admitted.decrementAndGet()
        }
    }

    internal class Lease(private val release: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean()
        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }
}
