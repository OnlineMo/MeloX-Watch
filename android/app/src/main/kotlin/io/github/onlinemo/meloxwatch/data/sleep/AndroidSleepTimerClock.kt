package io.github.onlinemo.meloxwatch.data.sleep

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * BOOT_COUNT 是系统提供的本机启动周期证据；不取硬件 ID、不依赖 root、不输出日志。
 * 缺少或拒绝读取时返回 null，恢复由定时 reducer 安全结清，不能猜一个启动标识。
 */
internal suspend fun readSleepTimerBootId(context: Context): String? = withContext(Dispatchers.IO) {
    try {
        val count = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
        if (count >= 0) "boot-count:$count" else null
    } catch (_: Settings.SettingNotFoundException) {
        null
    } catch (_: SecurityException) {
        null
    }
}
