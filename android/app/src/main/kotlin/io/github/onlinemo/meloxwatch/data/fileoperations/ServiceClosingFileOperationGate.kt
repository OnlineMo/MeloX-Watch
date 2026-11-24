package io.github.onlinemo.meloxwatch.data.fileoperations

import io.github.onlinemo.meloxwatch.playback.PlaybackEngineReleaseGuard
import io.github.onlinemo.meloxwatch.playback.PlaybackReleaseUnconfirmedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 确认范围包含关闭应用播放资源；仍绑定服务的 hook 负责 checkpoint、暂停、句柄及原生释放。
 * 随后用从未分配播放器的租约排除新引擎，不清除其他所有者的 quarantine。
 * 结束不重启服务，也不自动发声。
 */
internal class ServiceClosingFileOperationGate(
    private val host: FileOperationPlaybackHost,
    private val guard: PlaybackEngineReleaseGuard = PlaybackEngineReleaseGuard.process,
) : FileOperationPlaybackGate {
    override suspend fun <T> withClosedResources(action: suspend (FilePlaybackReservation) -> T): T {
        if (guard.hasUnconfirmedRelease()) throw FileOperationException(FileOperationIssue.PLAYBACK_RELEASE_UNCONFIRMED)
        host.reserve()
        var acquired: PlaybackEngineReleaseGuard.Lease? = null
        try {
            currentCoroutineContext().ensureActive()
            val reservation = withContext(NonCancellable + Dispatchers.Main.immediate) {
                withTimeoutOrNull(RELEASE_WAIT_MS) {
                    var lease: PlaybackEngineReleaseGuard.Lease? = null
                    while (lease == null) {
                        if (guard.hasUnconfirmedRelease()) throw FileOperationException(FileOperationIssue.PLAYBACK_RELEASE_UNCONFIRMED)
                        try { lease = guard.acquire()
                            acquired = lease }
                        catch (_: PlaybackReleaseUnconfirmedException) { delay(RELEASE_OBSERVATION_MS) }
                    }
                    lease
                }
            } ?: throw FileOperationException(FileOperationIssue.PLAYBACK_RELEASE_UNCONFIRMED)
            currentCoroutineContext().ensureActive()
            return action(FilePlaybackReservation(guard, reservation, host))
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                try { acquired?.let { guard.confirmReleased(it) } }
                finally { host.leave() }
            }
        }
    }

    private companion object {
        const val RELEASE_WAIT_MS = 15_000L
        const val RELEASE_OBSERVATION_MS = 50L
    }
}
