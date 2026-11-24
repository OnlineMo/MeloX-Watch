package io.github.onlinemo.meloxwatch.data.fileoperations

import io.github.onlinemo.meloxwatch.playback.PlaybackEngineReleaseGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** 进程内服务挂接及启动/恢复围栏；所有状态修改在 Main 顺序执行。 */
class FileOperationPlaybackHost {
    private var owner: Any? = null
    private var closeOwner: (suspend () -> Unit)? = null
    private var loaded = false
    private var recoveryBlocked = false
    private var reserved = false
    private var quiescenceUnconfirmed = false

    // 日志 I/O 结束前可创建空后端；读取保存的本地项仍须经过
    // Runtime.assertPlaybackReadable 和服务播放命令的独立围栏。
    internal fun mayAllocate(): Boolean = !recoveryBlocked && !quiescenceUnconfirmed && !reserved && owner == null
    internal fun allowsOwner(identity: Any): Boolean = owner === identity && !recoveryBlocked && !quiescenceUnconfirmed && !reserved
    internal fun isReadBlocked(): Boolean = !loaded || recoveryBlocked || quiescenceUnconfirmed || reserved
    internal fun isReserved(): Boolean = reserved
    internal fun hasUnconfirmedClosure(): Boolean = quiescenceUnconfirmed

    internal fun attach(identity: Any, close: suspend () -> Unit): Boolean {
        if (!mayAllocate()) return false
        owner = identity
        closeOwner = close
        return true
    }

    internal fun detach(identity: Any) {
        if (owner === identity) { owner = null
            closeOwner = null
            quiescenceUnconfirmed = false }
    }

    internal suspend fun updateRecoveryBlock(blocked: Boolean) = withContext(Dispatchers.Main.immediate) {
        recoveryBlocked = blocked
        loaded = true
    }

    internal suspend fun reserve() = withContext(Dispatchers.Main.immediate) {
        if (reserved) throw FileOperationException(FileOperationIssue.BUSY)
        reserved = true
        try {
            // 关闭意图已接受后，取消不能跳过原生释放与 checkpoint 收尾。
            withContext(NonCancellable) {
                val completed = kotlinx.coroutines.withTimeoutOrNull(15_000L) { closeOwner?.invoke()
                    true }
                if (completed != true) throw FileOperationException(FileOperationIssue.PLAYBACK_RELEASE_UNCONFIRMED)
            }
        } catch (failure: Throwable) {
            // 日志刷新不能清除仍持有已接受命令或 checkpoint 写者的服务；
            // 只有实际成功的关闭 hook 才能摘除该所有者。
            quiescenceUnconfirmed = true
            reserved = false
            throw failure
        }
    }

    internal suspend fun leave() = withContext(NonCancellable + Dispatchers.Main.immediate) { reserved = false }
}

/** 服务确认释放后取得的从未分配播放器租约，才可构造此资源关闭证明。 */
class FilePlaybackReservation internal constructor(
    private val guard: PlaybackEngineReleaseGuard,
    private val lease: PlaybackEngineReleaseGuard.Lease,
    private val host: FileOperationPlaybackHost,
) {
    internal fun requireClosedResources() {
        if (!host.isReserved() || !guard.isRetained(lease) || guard.hasUnconfirmedRelease() || guard.releaseStarted(lease)) {
            throw FileOperationException(FileOperationIssue.PLAYBACK_RELEASE_UNCONFIRMED)
        }
    }
}
