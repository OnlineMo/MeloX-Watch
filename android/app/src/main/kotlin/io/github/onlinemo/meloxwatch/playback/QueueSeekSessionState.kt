package io.github.onlinemo.meloxwatch.playback

import android.os.Bundle
import java.util.UUID

/** 只发布不可解释的短期身份；完整来源、队列和播放轮次留在服务内核验。 */
data class QueueSeekSessionState(
    val ownerId: String? = null,
    val previewId: String? = null,
) {
    init {
        require(ownerId == null || canonicalUuid(ownerId))
        require(previewId == null || ownerId != null && canonicalUuid(previewId))
    }

    fun toSessionExtras(): Bundle = Bundle().apply {
        putInt(PREFIX + "schema", 1)
        putString(PREFIX + "owner", ownerId)
        putString(PREFIX + "preview", previewId)
    }

    companion object {
        private const val PREFIX = "io.github.onlinemo.meloxwatch.seek."
        private val FIELDS = setOf(PREFIX + "schema", PREFIX + "owner", PREFIX + "preview")

        fun fromSessionExtras(extras: Bundle): QueueSeekSessionState? = try {
            require(extras.keySet().filter { it.startsWith(PREFIX) }.toSet() == FIELDS)
            val schema = extras.rawValue(PREFIX + "schema")
            require(schema is Int && schema == 1)
            QueueSeekSessionState(extras.optionalId("owner"), extras.optionalId("preview"))
        } catch (_: RuntimeException) {
            null
        }

        private fun Bundle.optionalId(field: String): String? {
            val item = rawValue(PREFIX + field) ?: return null
            require(item is String)
            return item
        }

        private fun canonicalUuid(value: String): Boolean =
            value.length == 36 && try { UUID.fromString(value).toString() == value } catch (_: IllegalArgumentException) { false }

        // 不能以带默认值的读取把缺失或错误 IPC 类型当成可用的控制身份。
        @Suppress("DEPRECATION")
        private fun Bundle.rawValue(key: String): Any? = get(key)
    }
}
