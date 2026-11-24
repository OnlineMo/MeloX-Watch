package io.github.onlinemo.meloxwatch.playback

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import io.github.onlinemo.meloxwatch.playback.route.MediaVolumeCommands
import io.github.onlinemo.meloxwatch.playback.route.MediaVolumeResult
import io.github.onlinemo.meloxwatch.playback.route.MediaVolumeSetRequest

/**
 * 在服务主线程同步处理媒体音量命令，先核对线程、调用 UID 和参数再读写平台。
 *
 * RESULT_SUCCESS 只表示已处理协议；实际音量变更仍以结构化结果的 accepted 与 changed 为准。
 */
@UnstableApi
internal class ServiceMediaVolumeCommandHandler(
    private val ownerUid: Int,
    private val read: () -> MediaVolumeResult,
    private val set: (MediaVolumeSetRequest) -> MediaVolumeResult,
    private val adjust: (Int) -> MediaVolumeResult,
    private val onResult: () -> Unit,
) {
    fun handle(callerUid: Int, action: String, arguments: Bundle): SessionResult? {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!handles(action)) return null
        if (callerUid != ownerUid) return SessionResult(SessionError.ERROR_PERMISSION_DENIED)
        val operation: () -> MediaVolumeResult = try {
            when (action) {
                MediaVolumeCommands.read.customAction -> {
                    MediaVolumeCommands.readRead(arguments)
                    read
                }
                MediaVolumeCommands.set.customAction -> {
                    val request = MediaVolumeCommands.readSet(arguments)
                    val execute = { set(request) }
                    execute
                }
                else -> {
                    val delta = MediaVolumeCommands.readAdjust(arguments)
                    val execute = { adjust(delta) }
                    execute
                }
            }
        } catch (_: RuntimeException) {
            return SessionResult(SessionError.ERROR_BAD_VALUE)
        }
        val result = operation()
        try {
            onResult()
        } catch (_: RuntimeException) {
            // 会话销毁不能掩盖已写入并真实读回的结果；下次命令仍重新核对所有者、路由及音量。
        }
        return SessionResult(SessionResult.RESULT_SUCCESS, MediaVolumeCommands.resultArguments(result))
    }

    companion object {
        fun handles(action: String): Boolean = action == MediaVolumeCommands.read.customAction ||
            action == MediaVolumeCommands.set.customAction || action == MediaVolumeCommands.adjust.customAction
    }
}
