package io.github.onlinemo.meloxwatch.data

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine

/** 调用方取消不能提前释放物理读取；后续请求不会组成等待队列。 */
internal class ProbeReadChannel(private val dispatcher: CoroutineDispatcher) {
    private val occupied = AtomicBoolean(false)

    suspend fun <T> read(onCancellation: () -> Unit, action: () -> T): T = suspendCancellableCoroutine { caller ->
        if (!occupied.compareAndSet(false, true)) {
            caller.resumeWithException(ProbeReadBusyException())
            return@suspendCancellableCoroutine
        }
        caller.invokeOnCancellation { onCancellation() }
        val worker = Runnable {
            val result = try {
                Result.success(action())
            } catch (error: Exception) {
                Result.failure(error)
            } catch (error: OutOfMemoryError) {
                Result.failure(ProbeReadAllocationException(error))
            } finally {
                occupied.set(false)
            }
            // 顺序调用可能在当前worker内联恢复，成功和失败都必须先释放物理占用。
            if (caller.isActive) {
                result.fold(
                    onSuccess = { caller.resume(it) },
                    onFailure = { caller.resumeWithException(it) },
                )
            }
        }
        try {
            dispatcher.dispatch(caller.context, worker)
        } catch (error: RuntimeException) {
            occupied.set(false)
            if (caller.isActive) caller.resumeWithException(error)
        }
    }
}

internal class ProbeReadBusyException : IllegalStateException()
internal class ProbeReadAllocationException(cause: OutOfMemoryError) : RuntimeException("SOURCE_UNAVAILABLE", cause)
