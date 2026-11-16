package io.github.onlinemo.meloxwatch.playback

import android.content.Context
import android.content.Intent
import android.icu.text.BreakIterator
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.session.SessionCommand
import androidx.media3.common.util.UnstableApi
import io.github.onlinemo.meloxwatch.data.queue.QueueInputPage
import io.github.onlinemo.meloxwatch.data.queue.QueuePageSource
import io.github.onlinemo.meloxwatch.data.queue.QueueReplacementRequest
import io.github.onlinemo.meloxwatch.model.FrozenQueueView
import io.github.onlinemo.meloxwatch.model.LocalAccess
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueSnapshot
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.util.Locale

/** 唯一会话内的命令协议；只有同 UID 控制器可提交来源，媒体键只操作已有队列。 */
@androidx.annotation.OptIn(UnstableApi::class)
object PlaybackCommands {
    private const val PREFIX = "io.github.onlinemo.meloxwatch."
    private const val ACTION_OPEN_PLAYBACK = PREFIX + "OPEN_PLAYBACK"
    // 组件包取运行时 applicationId，类名属于固定 namespace，不能跟随 .dev 后缀改变。
    private const val PLAYBACK_ACTIVITY_CLASS = PREFIX + "ui.MainActivity"
    private const val DESTINATION = "destination"
    private const val PLAYBACK_DESTINATION = "playback"
    val replaceLocal = SessionCommand(PREFIX + "REPLACE_LOCAL", Bundle.EMPTY)
    val replaceLibraryView = SessionCommand(PREFIX + "REPLACE_LIBRARY_VIEW", Bundle.EMPTY)
    val selectEntry = SessionCommand(PREFIX + "SELECT_ENTRY", Bundle.EMPTY)
    val retryRestore = SessionCommand(PREFIX + "RETRY_RESTORE", Bundle.EMPTY)
    const val FAILURE = "failure"
    const val STORE_ISSUE = "store_issue"
    private const val SCHEMA = "schema"
    private const val SCHEMA_VERSION = 1
    private const val GENERATION = "generation"
    private const val REVISION = "revision"
    private const val ENTRY = "entry"
    private const val OPERATION = "operation"
    private const val TRACK = "track"
    private const val LOCATION = "location"
    private const val URI = "uri"
    private const val GRANT_URI = "grant_uri"
    private const val FLAGS = "flags"
    private const val ACCESS = "access"
    private const val TITLE = "title"
    private const val TITLE_CHARACTERS = 2048
    private const val DURATION = "duration"
    private const val VIEW = "view"

    /** 通知只导航到已有播放状态；独立 action 使它不复用普通启动的 PendingIntent。 */
    fun playbackActivityIntent(context: Context): Intent = Intent().setClassName(context, PLAYBACK_ACTIVITY_CLASS).apply {
        action = ACTION_OPEN_PLAYBACK
        putExtra(DESTINATION, PLAYBACK_DESTINATION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    fun opensPlayback(source: Intent?): Boolean {
        if (source?.action != ACTION_OPEN_PLAYBACK) return false
        return try {
            val destination = source.extras?.rawValue(DESTINATION)
            destination is String && destination == PLAYBACK_DESTINATION
        } catch (_: RuntimeException) {
            false
        }
    }

    /** The service adopts this database lease; a Bundle never carries the visible page as a queue. */
    fun libraryArguments(viewId: String, selected: TrackKey?, expected: QueueSnapshot?, operationId: String): Bundle {
        require(selected == null || selected.providerId == TrackKey.LOCAL_PROVIDER)
        return Bundle().apply {
            putInt(SCHEMA, SCHEMA_VERSION)
            putString(VIEW, viewId)
            putString(GENERATION, expected?.generationId)
            putLong(REVISION, expected?.queueRevision ?: 0L)
            putString(OPERATION, operationId)
            selected?.let { putString(TRACK, it.trackId) }
        }
    }

    fun readLibrary(arguments: Bundle): LibraryQueueCommand {
        require(arguments.value<Int>(SCHEMA) == SCHEMA_VERSION)
        val track = arguments.optionalText(TRACK, 128)?.also { require(it.isNotBlank()) }
        return LibraryQueueCommand(
            viewId = arguments.identifier(VIEW),
            operationId = arguments.identifier(OPERATION),
            expectedGenerationId = arguments.optionalText(GENERATION, 128)?.also { require(it.isNotBlank()) },
            expectedQueueRevision = arguments.value<Long>(REVISION).also { require(it >= 0L) },
            selected = track?.let { TrackKey(TrackKey.LOCAL_PROVIDER, it) },
        )
    }

    fun localArguments(entry: QueueEntry, expected: QueueSnapshot?, operationId: String): Bundle {
        val location = requireNotNull(entry.location)
        require(entry.trackKey.providerId == TrackKey.LOCAL_PROVIDER)
        return Bundle().apply {
            putInt(SCHEMA, SCHEMA_VERSION)
            putString(GENERATION, expected?.generationId)
            putLong(REVISION, expected?.queueRevision ?: 0L)
            putString(OPERATION, operationId)
            putString(ENTRY, entry.queueEntryId)
            putString(TRACK, entry.trackKey.trackId)
            putString(LOCATION, location.locationId)
            putString(URI, location.uri)
            putString(GRANT_URI, location.grantUri)
            putInt(FLAGS, location.grantFlags)
            putString(ACCESS, location.access.name)
            putString(TITLE, boundedTitle(entry.displayName))
            entry.durationMs?.let { putLong(DURATION, it) }
        }
    }

    fun selectionArguments(snapshot: QueueSnapshot, entryId: String): Bundle = Bundle().apply {
        putInt(SCHEMA, SCHEMA_VERSION)
        putString(GENERATION, snapshot.generationId)
        putLong(REVISION, snapshot.queueRevision)
        putString(ENTRY, entryId)
    }

    fun readLocal(arguments: Bundle): LocalQueueCommand {
        require(arguments.value<Int>(SCHEMA) == SCHEMA_VERSION)
        val operation = arguments.identifier(OPERATION)
        val uri = arguments.text(URI, 16384)
        val parsedUri = uri.toUri()
        require(parsedUri.scheme == "content" && !parsedUri.authority.isNullOrBlank())
        val grantUri = arguments.text(GRANT_URI, 16384)
        val parsedGrant = grantUri.toUri()
        require(parsedGrant.scheme == "content" && !parsedGrant.authority.isNullOrBlank())
        val duration = if (arguments.containsKey(DURATION)) arguments.value<Long>(DURATION) else null
        val title = arguments.optionalText(TITLE, TITLE_CHARACTERS)
        val entry = QueueEntry(
            queueEntryId = arguments.identifier(ENTRY),
            trackKey = TrackKey(TrackKey.LOCAL_PROVIDER, arguments.identifier(TRACK)),
            originalOrder = 0L,
            playbackOrder = 0L,
            displayName = title,
            durationMs = duration,
            location = LocalLocationReference(
                locationId = arguments.identifier(LOCATION),
                uri = uri,
                grantUri = grantUri,
                grantFlags = arguments.value(FLAGS),
                access = LocalAccess.valueOf(arguments.text(ACCESS, 64)),
            ),
        )
        val view = FrozenQueueView("single:$operation", 0L, 1L)
        val request = QueueReplacementRequest(
            operationId = operation,
            expectedGenerationId = arguments.optionalText(GENERATION, 128),
            expectedQueueRevision = arguments.value(REVISION),
            view = view,
        )
        return LocalQueueCommand(request, QueuePageSource { offset, limit ->
            require(offset == 0L && limit == 1)
            QueueInputPage(view, offset, listOf(entry))
        })
    }

    fun readSelection(arguments: Bundle): QueueSelectionCommand {
        require(arguments.value<Int>(SCHEMA) == SCHEMA_VERSION)
        return QueueSelectionCommand(
            generationId = arguments.identifier(GENERATION),
            queueRevision = arguments.value<Long>(REVISION).also { require(it >= 0L) },
            entryId = arguments.identifier(ENTRY),
        )
    }

    // 完整元数据留在来源/库中；单项 IPC 只传展示摘要，不能拆开代理对或组合字符。
    private fun boundedTitle(title: String?): String? {
        if (title == null || title.length <= TITLE_CHARACTERS) return title
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(title) }
        val end = iterator.preceding(TITLE_CHARACTERS + 1)
        return if (end > 0) title.substring(0, end) else null
    }

    private fun Bundle.identifier(key: String): String = text(key, 128)

    private fun Bundle.text(key: String, limit: Int): String = value<String>(key).also {
        require(it.isNotBlank() && it.length <= limit)
    }

    private fun Bundle.optionalText(key: String, limit: Int): String? {
        if (!containsKey(key)) return null
        val item = rawValue(key) ?: return null
        require(item is String && item.length <= limit)
        return item
    }

    private inline fun <reified T> Bundle.value(key: String): T =
        rawValue(key) as? T ?: throw IllegalArgumentException("Invalid playback command field")

    // 类型核验不能用 Bundle 的带默认值读取，否则错误类型会变成合法 revision=0。
    @Suppress("DEPRECATION")
    private fun Bundle.rawValue(key: String): Any? = get(key)
}

data class LocalQueueCommand(val request: QueueReplacementRequest, val source: QueuePageSource)
data class QueueSelectionCommand(val generationId: String, val queueRevision: Long, val entryId: String)

data class LibraryQueueCommand(
    val viewId: String,
    val operationId: String,
    val expectedGenerationId: String?,
    val expectedQueueRevision: Long,
    val selected: TrackKey?,
)
