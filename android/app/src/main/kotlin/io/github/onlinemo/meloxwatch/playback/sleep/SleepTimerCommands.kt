package io.github.onlinemo.meloxwatch.playback.sleep

import android.os.Bundle
import androidx.media3.session.SessionCommand
import java.util.UUID

/** 新启用/替换和取消均绑定用户看到的本次定时；重建不自动发命令。 */
object SleepTimerCommands {
    private const val PREFIX = "io.github.onlinemo.meloxwatch.sleep."
    val arm = SessionCommand(PREFIX + "ARM", Bundle.EMPTY)
    val cancel = SessionCommand(PREFIX + "CANCEL", Bundle.EMPTY)
    val retrySave = SessionCommand(PREFIX + "RETRY_SAVE", Bundle.EMPTY)
    val refresh = SessionCommand(PREFIX + "REFRESH", Bundle.EMPTY)
    private const val SCHEMA = "schema"
    private const val EXPECTED = "expected_generation"
    private const val MINUTES = "minutes"
    private const val MODE = "mode"

    fun armArguments(minutes: Int, mode: SleepTimerEndMode, expectedGeneration: String?): Bundle =
        expectedArguments(expectedGeneration).apply {
            require(minutes in 1..180)
            putInt(MINUTES, minutes)
            putString(MODE, mode.name)
        }

    fun expectedArguments(generationId: String?): Bundle = Bundle().apply {
        validateGeneration(generationId)
        putInt(SCHEMA, 1)
        putString(EXPECTED, generationId)
    }

    fun refreshArguments(): Bundle = Bundle().apply { putInt(SCHEMA, 1) }

    fun readArm(arguments: Bundle): SleepTimerArmCommand {
        val expected = readExpected(arguments)
        return SleepTimerArmCommand(arguments.value<Int>(MINUTES).also { require(it in 1..180) },
            SleepTimerEndMode.valueOf(arguments.value(MODE)), expected)
    }

    fun readExpected(arguments: Bundle): String? {
        readRefresh(arguments)
        require(arguments.containsKey(EXPECTED))
        val value = arguments.rawValue(EXPECTED)
        require(value == null || value is String)
        return (value as? String).also(::validateGeneration)
    }

    fun readRefresh(arguments: Bundle) {
        require(arguments.value<Int>(SCHEMA) == 1)
    }

    private fun validateGeneration(value: String?) {
        require(value == null || value.length == 36 && UUID.fromString(value).toString() == value)
    }

    private inline fun <reified T> Bundle.value(key: String): T = rawValue(key) as? T
        ?: throw IllegalArgumentException("Invalid sleep timer command")

    // 同PlaybackCommands按原类型读取，拒绝默认值吞掉错误；适用当前全部API26+。
    @Suppress("DEPRECATION")
    private fun Bundle.rawValue(key: String): Any? = get(key)
}

data class SleepTimerArmCommand(val minutes: Int, val mode: SleepTimerEndMode, val expectedGeneration: String?)
