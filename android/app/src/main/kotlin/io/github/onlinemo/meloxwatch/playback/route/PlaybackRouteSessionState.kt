package io.github.onlinemo.meloxwatch.playback.route

import android.os.Bundle
import java.util.UUID

/** 会话只传短期控制身份；实际播放对象和设备身份始终留在后端内存中。 */
data class PlaybackRouteSessionState(
    val ownerId: String? = null,
    val known: Boolean = false,
) {
    init {
        require(known == (ownerId != null))
        require(ownerId == null || validOwnerId(ownerId))
    }

    fun toSessionExtras(): Bundle = Bundle().apply {
        putInt(PREFIX + "schema", 1)
        putString(PREFIX + "owner", ownerId)
        putBoolean(PREFIX + "known", known)
    }

    companion object {
        private const val PREFIX = "io.github.onlinemo.meloxwatch.route."
        private val FIELDS = setOf(PREFIX + "schema", PREFIX + "owner", PREFIX + "known")

        fun fromSessionExtras(extras: Bundle): PlaybackRouteSessionState? = try {
            require(extras.keySet().filter { it.startsWith(PREFIX) }.toSet() == FIELDS)
            require(extras.rawValue(PREFIX + "schema") is Int && extras.rawValue(PREFIX + "schema") == 1)
            val owner = extras.rawValue(PREFIX + "owner")
            require(owner == null || owner is String)
            val known = extras.rawValue(PREFIX + "known")
            require(known is Boolean)
            PlaybackRouteSessionState(owner as String?, known)
        } catch (_: RuntimeException) {
            null
        }

        internal fun validOwnerId(value: String): Boolean = value.length == 36 && try {
            UUID.fromString(value).toString() == value
        } catch (_: IllegalArgumentException) {
            false
        }

        @Suppress("DEPRECATION")
        private fun Bundle.rawValue(key: String): Any? = get(key)
    }
}
