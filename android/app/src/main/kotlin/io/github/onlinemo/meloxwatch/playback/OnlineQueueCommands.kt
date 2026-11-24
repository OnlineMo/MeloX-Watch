package io.github.onlinemo.meloxwatch.playback

import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.SessionCommand
import io.github.onlinemo.meloxwatch.model.QueueMode
import io.github.onlinemo.meloxwatch.model.QueueRepeatMode
import io.github.onlinemo.meloxwatch.playback.resources.StreamingQuality
import java.util.UUID

enum class OnlineQueueAction { REPLACE_COMPLETE_LIST, INSERT_NEXT, INSERT_AND_PLAY }

data class OnlineQueueCommand(
    val ticketId: String,
    val operationId: String,
    val expectedGenerationId: String?,
    val expectedQueueRevision: Long,
    val expectedCurrentEntryId: String?,
    val selectedOrder: Long,
    val action: OnlineQueueAction,
    val quality: StreamingQuality,
    val mode: QueueMode,
) {
    init {
        require(UUID.fromString(ticketId).toString() == ticketId && UUID.fromString(operationId).toString() == operationId)
        require(expectedQueueRevision >= 0L && selectedOrder >= 0L)
        require(expectedGenerationId != null || (expectedQueueRevision == 0L && expectedCurrentEntryId == null))
        require(expectedGenerationId == null || expectedGenerationId.length in 1..128)
        require(expectedCurrentEntryId == null || expectedCurrentEntryId.length in 1..128)
    }
    override fun toString(): String = "OnlineQueueCommand(action=$action, redacted)"
}

/** Only an opaque app-owned ticket crosses MediaController; its producer never comes from a parcel. */
@UnstableApi
object OnlineQueueCommands {
    val play = SessionCommand("io.github.onlinemo.meloxwatch.ONLINE_QUEUE", Bundle.EMPTY)
    val cancel = SessionCommand("io.github.onlinemo.meloxwatch.CANCEL_ONLINE_QUEUE", Bundle.EMPTY)
    private val fields = setOf("schema", "ticket", "operation", "generation", "revision", "current", "selected", "action", "quality", "shuffle", "repeat", "cycles")
    fun arguments(command: OnlineQueueCommand): Bundle = Bundle().apply {
        putInt("schema", 1)
        putString("ticket", command.ticketId)
        putString("operation", command.operationId)
        putString("generation", command.expectedGenerationId)
        putLong("revision", command.expectedQueueRevision)
        putString("current", command.expectedCurrentEntryId)
        putLong("selected", command.selectedOrder)
        putString("action", command.action.name)
        putString("quality", command.quality.name)
        putBoolean("shuffle", command.mode.shuffle)
        putString("repeat", command.mode.repeat.name)
        command.mode.cycleLimit?.let { putInt("cycles", it) }
    }
    fun read(arguments: Bundle): OnlineQueueCommand {
        require(arguments.keySet().all { it in fields } && arguments.getInt("schema", -1) == 1)
        fun text(key: String): String = requireNotNull(arguments.getString(key)).also { require(it.length in 1..128) }
        require(arguments.containsKey("revision") && arguments.containsKey("selected") && arguments.containsKey("shuffle"))
        return OnlineQueueCommand(text("ticket"), text("operation"), arguments.getString("generation"),
            arguments.getLong("revision"), arguments.getString("current"), arguments.getLong("selected"),
            OnlineQueueAction.valueOf(text("action")), StreamingQuality.valueOf(text("quality")),
            QueueMode(arguments.getBoolean("shuffle"), QueueRepeatMode.valueOf(text("repeat")),
                if (arguments.containsKey("cycles")) arguments.getInt("cycles") else null))
    }
    fun cancelArguments(operationId: String): Bundle = Bundle().apply {
        require(UUID.fromString(operationId).toString() == operationId)
        putInt("schema", 1)
        putString("operation", operationId)
    }
    fun readCancellation(arguments: Bundle): String {
        require(arguments.keySet() == setOf("schema", "operation") && arguments.getInt("schema", -1) == 1)
        return requireNotNull(arguments.getString("operation")).also { require(UUID.fromString(it).toString() == it) }
    }
}
