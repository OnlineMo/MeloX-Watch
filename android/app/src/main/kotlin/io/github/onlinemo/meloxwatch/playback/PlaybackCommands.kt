package io.github.onlinemo.meloxwatch.playback

import android.content.Context
import android.content.Intent
import android.icu.text.BreakIterator
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.session.SessionCommand
import androidx.media3.common.util.UnstableApi
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistFolderBinding
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistKind
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistLimits
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistQueueCatalogToken
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistSnapshot
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistViewOwner
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistViewToken
import io.github.onlinemo.meloxwatch.data.queue.QueueInputPage
import io.github.onlinemo.meloxwatch.data.queue.QueuePageSource
import io.github.onlinemo.meloxwatch.data.queue.QueueReplacementRequest
import io.github.onlinemo.meloxwatch.model.FrozenQueueView
import io.github.onlinemo.meloxwatch.model.LocalAccess
import io.github.onlinemo.meloxwatch.model.LocalLocationReference
import io.github.onlinemo.meloxwatch.model.QueueEntry
import io.github.onlinemo.meloxwatch.model.QueueMode
import io.github.onlinemo.meloxwatch.model.QueueMutation
import io.github.onlinemo.meloxwatch.model.QueueMutationRequest
import io.github.onlinemo.meloxwatch.model.QueueRepeatMode
import io.github.onlinemo.meloxwatch.model.QueueSeekBinding
import io.github.onlinemo.meloxwatch.model.QueueSeekRequest
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
    val playlistQueue = SessionCommand(PREFIX + "PLAYLIST_QUEUE", Bundle.EMPTY)
    val selectEntry = SessionCommand(PREFIX + "SELECT_ENTRY", Bundle.EMPTY)
    val retryRestore = SessionCommand(PREFIX + "RETRY_RESTORE", Bundle.EMPTY)
    val mutateQueue = SessionCommand(PREFIX + "MUTATE_QUEUE", Bundle.EMPTY)
    val beginSeek = SessionCommand(PREFIX + "BEGIN_SEEK", Bundle.EMPTY)
    val seekQueue = SessionCommand(PREFIX + "SEEK_QUEUE", Bundle.EMPTY)
    const val FAILURE = "failure"
    const val STORE_ISSUE = "store_issue"
    private const val SCHEMA = "schema"
    private const val SCHEMA_VERSION = 2
    private const val SEEK_SCHEMA_VERSION = 3
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
    private const val VIEW_REVISION = "view_revision"
    private const val VIEW_COUNT = "view_count"
    private const val SELECTED_ORDER = "selected_order"
    private const val CURRENT_ENTRY = "current_entry"
    private const val ACTION = "action"
    private const val SHUFFLE = "shuffle"
    private const val REPEAT = "repeat"
    private const val CYCLE_LIMIT = "cycle_limit"
    private const val CONFIRMED_CURRENT = "confirmed_current"
    private const val DESTINATION_ORDER = "destination_order"
    private const val PREVIEW = "preview"
    private const val SESSION = "session"
    private const val BACKEND_GENERATION = "backend_generation"
    private const val CONTENT_REVISION = "content_revision"
    private const val PLAYBACK_CYCLE = "playback_cycle"
    private const val POSITION = "position"
    private const val EXPLICIT_PLAY = "explicit_play"
    private const val PLAYLIST = "playlist"
    private const val PLAYLIST_GENERATION = "playlist_generation"
    private const val PLAYLIST_KIND = "playlist_kind"
    private const val PLAYLIST_NAME = "playlist_name"
    private const val PLAYLIST_CREATED = "playlist_created"
    private const val PLAYLIST_UPDATED = "playlist_updated"
    private const val PLAYLIST_OWNER = "playlist_owner"
    private const val FOLDER_SOURCE = "folder_source"
    private const val FOLDER_NODE = "folder_node"
    private const val FOLDER_SUBTREE = "folder_subtree"
    private const val CATALOG = "catalog"
    private const val CATALOG_REVISION = "catalog_revision"
    private const val RULES_REVISION = "rules_revision"
    private const val MEMBER = "member"
    private val PLAYLIST_FIELDS = setOf(
        SCHEMA, VIEW, VIEW_REVISION, VIEW_COUNT, GENERATION, REVISION, CURRENT_ENTRY, OPERATION, ACTION,
        SHUFFLE, REPEAT, CYCLE_LIMIT, PLAYLIST, PLAYLIST_GENERATION, PLAYLIST_KIND, PLAYLIST_NAME,
        PLAYLIST_CREATED, PLAYLIST_UPDATED, PLAYLIST_OWNER, FOLDER_SOURCE, FOLDER_NODE, FOLDER_SUBTREE,
        CATALOG, CATALOG_REVISION, RULES_REVISION, MEMBER, SELECTED_ORDER,
    )
    private val SEEK_BINDING_FIELDS = setOf(
        SCHEMA, PREVIEW, SESSION, BACKEND_GENERATION, GENERATION, REVISION, ENTRY, LOCATION,
        CONTENT_REVISION, DURATION, PLAYBACK_CYCLE,
    )
    private val SEEK_REQUEST_FIELDS = SEEK_BINDING_FIELDS + setOf(POSITION, EXPLICIT_PLAY)

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

    /** Intent identity uses the visible lease, not the fresh producer lease created by the service. */
    fun libraryArguments(command: LibraryQueueCommand): Bundle = Bundle().apply {
        putInt(SCHEMA, SCHEMA_VERSION)
        putString(VIEW, command.viewId)
        putLong(VIEW_REVISION, command.viewRevision)
        putLong(VIEW_COUNT, command.viewCount)
        putString(GENERATION, command.expectedGenerationId)
        putLong(REVISION, command.expectedQueueRevision)
        putString(CURRENT_ENTRY, command.expectedCurrentEntryId)
        putString(OPERATION, command.operationId)
        putString(ACTION, command.action.name)
        writeMode(command.mode)
        command.selected?.let { putString(TRACK, it.trackId) }
        command.selectedOriginalOrder?.let { putLong(SELECTED_ORDER, it) }
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
            viewRevision = arguments.value(VIEW_REVISION),
            viewCount = arguments.value(VIEW_COUNT),
            selectedOriginalOrder = if (arguments.containsKey(SELECTED_ORDER)) arguments.value(SELECTED_ORDER) else null,
            expectedCurrentEntryId = arguments.optionalIdentifier(CURRENT_ENTRY),
            mode = arguments.readMode(),
            action = LibraryQueueAction.valueOf(arguments.text(ACTION, 64)),
        )
    }

    /** 完整快照只包含冻结身份与元数据；成员和文件授权仍由服务逐页读取。 */
    fun playlistArguments(command: PlaylistQueueCommand): Bundle = Bundle().apply {
        val snapshot = command.view.snapshot
        putInt(SCHEMA, SCHEMA_VERSION)
        putString(VIEW, command.view.viewId)
        putLong(VIEW_REVISION, snapshot.revision)
        putLong(VIEW_COUNT, snapshot.memberCount)
        putString(PLAYLIST, snapshot.playlistId)
        putString(PLAYLIST_GENERATION, snapshot.generationId)
        putString(PLAYLIST_KIND, snapshot.kind.name)
        putString(PLAYLIST_NAME, snapshot.name)
        putLong(PLAYLIST_CREATED, snapshot.createdEpochMs)
        putLong(PLAYLIST_UPDATED, snapshot.updatedEpochMs)
        putString(PLAYLIST_OWNER, command.view.owner.name)
        snapshot.folderBinding?.let { binding ->
            putString(FOLDER_SOURCE, binding.sourceId)
            putString(FOLDER_NODE, binding.nodeId)
            putBoolean(FOLDER_SUBTREE, binding.subtree)
        }
        putString(CATALOG, command.catalog.id)
        putLong(CATALOG_REVISION, command.catalog.revision)
        putLong(RULES_REVISION, command.catalog.rulesRevision)
        putString(GENERATION, command.expectedGenerationId)
        putLong(REVISION, command.expectedQueueRevision)
        putString(CURRENT_ENTRY, command.expectedCurrentEntryId)
        putString(OPERATION, command.operationId)
        putString(ACTION, command.action.name)
        writeMode(command.mode)
        command.selection?.let { selection ->
            putString(MEMBER, selection.memberId)
            putLong(SELECTED_ORDER, selection.ordinal)
        }
    }

    fun readPlaylist(arguments: Bundle): PlaylistQueueCommand {
        require(arguments.value<Int>(SCHEMA) == SCHEMA_VERSION)
        require(arguments.keySet().all { it in PLAYLIST_FIELDS })
        val owner = PlaylistViewOwner.valueOf(arguments.text(PLAYLIST_OWNER, 64))
        require(owner == PlaylistViewOwner.UI)
        val kind = PlaylistKind.valueOf(arguments.text(PLAYLIST_KIND, 64))
        val hasFolder = arguments.containsKey(FOLDER_SOURCE) || arguments.containsKey(FOLDER_NODE) || arguments.containsKey(FOLDER_SUBTREE)
        val binding = if (kind == PlaylistKind.FOLDER) {
            PlaylistFolderBinding(arguments.identifier(FOLDER_SOURCE), arguments.identifier(FOLDER_NODE), arguments.value(FOLDER_SUBTREE))
        } else {
            require(!hasFolder)
            null
        }
        val selection = if (arguments.containsKey(MEMBER) || arguments.containsKey(SELECTED_ORDER)) {
            require(arguments.containsKey(MEMBER) && arguments.containsKey(SELECTED_ORDER))
            PlaylistQueueSelection(arguments.identifier(MEMBER), arguments.value(SELECTED_ORDER))
        } else null
        val snapshot = PlaylistSnapshot(
            playlistId = arguments.identifier(PLAYLIST),
            generationId = arguments.identifier(PLAYLIST_GENERATION),
            revision = arguments.value<Long>(VIEW_REVISION).also { require(it >= 0L) },
            name = arguments.text(PLAYLIST_NAME, PlaylistLimits.MAX_FIELD_CHARACTERS),
            kind = kind,
            memberCount = arguments.value<Long>(VIEW_COUNT).also { require(it > 0L) },
            createdEpochMs = arguments.value<Long>(PLAYLIST_CREATED).also { require(it >= 0L) },
            updatedEpochMs = arguments.value<Long>(PLAYLIST_UPDATED).also { require(it >= 0L) },
            folderBinding = binding,
        )
        return PlaylistQueueCommand(
            view = PlaylistViewToken(arguments.identifier(VIEW), snapshot, owner),
            catalog = PlaylistQueueCatalogToken(arguments.identifier(CATALOG),
                arguments.value<Long>(CATALOG_REVISION).also { require(it >= 0L) },
                arguments.value<Long>(RULES_REVISION).also { require(it >= 0L) }),
            operationId = arguments.identifier(OPERATION),
            expectedGenerationId = arguments.optionalIdentifier(GENERATION),
            expectedQueueRevision = arguments.value(REVISION),
            expectedCurrentEntryId = arguments.optionalIdentifier(CURRENT_ENTRY),
            selection = selection,
            mode = arguments.readMode(),
            action = PlaylistQueueAction.valueOf(arguments.text(ACTION, 64)),
        )
    }

    /** 成功和失败结果都回同一operation；客户端不能把另一次操作的结果当作ACK。 */
    fun operationReceiptArguments(operationId: String): Bundle = Bundle().apply {
        require(operationId.isNotBlank() && operationId.length <= 128)
        putInt(SCHEMA, SCHEMA_VERSION)
        putString(OPERATION, operationId)
    }

    fun readReceiptOperation(arguments: Bundle): String {
        require(arguments.value<Int>(SCHEMA) == SCHEMA_VERSION)
        return arguments.identifier(OPERATION)
    }

    fun readFailureCode(arguments: Bundle): String? = arguments.optionalText(FAILURE, 64)?.also {
        require(it.isNotBlank())
    }

    /** Insertions carry a library lease through libraryArguments; management edits carry no URI. */
    fun mutationArguments(request: QueueMutationRequest): Bundle = Bundle().apply {
        putInt(SCHEMA, SCHEMA_VERSION)
        putString(OPERATION, request.operationId)
        putString(GENERATION, request.expectedGenerationId)
        putLong(REVISION, request.expectedQueueRevision)
        putString(CURRENT_ENTRY, request.expectedCurrentEntryId)
        when (val edit = request.mutation) {
            is QueueMutation.Remove -> {
                putString(ACTION, "REMOVE")
                putString(ENTRY, edit.entryId)
                putBoolean(CONFIRMED_CURRENT, edit.confirmedCurrent)
            }
            is QueueMutation.Move -> {
                putString(ACTION, "MOVE")
                putString(ENTRY, edit.entryId)
                putLong(DESTINATION_ORDER, edit.destinationPlaybackOrder)
            }
            is QueueMutation.SetMode -> {
                putString(ACTION, "MODE")
                writeMode(edit.mode)
            }
            is QueueMutation.InsertNext -> throw IllegalArgumentException("A library insertion requires its producer lease")
        }
    }

    fun readMutation(arguments: Bundle): QueueMutationRequest {
        require(arguments.value<Int>(SCHEMA) == SCHEMA_VERSION)
        val edit = when (arguments.text(ACTION, 64)) {
            "REMOVE" -> QueueMutation.Remove(arguments.identifier(ENTRY), arguments.value(CONFIRMED_CURRENT))
            "MOVE" -> QueueMutation.Move(arguments.identifier(ENTRY), arguments.value(DESTINATION_ORDER))
            "MODE" -> QueueMutation.SetMode(arguments.readMode())
            else -> throw IllegalArgumentException("Invalid queue mutation")
        }
        return QueueMutationRequest(
            arguments.identifier(OPERATION), arguments.optionalIdentifier(GENERATION),
            arguments.value(REVISION), arguments.optionalIdentifier(CURRENT_ENTRY), edit,
        )
    }

    fun beginSeekArguments(): Bundle = Bundle().apply { putInt(SCHEMA, SEEK_SCHEMA_VERSION) }

    fun readBeginSeek(arguments: Bundle) {
        require(arguments.keySet() == setOf(SCHEMA))
        require(arguments.value<Int>(SCHEMA) == SEEK_SCHEMA_VERSION)
    }

    fun seekBindingArguments(binding: QueueSeekBinding): Bundle = Bundle().apply {
        putInt(SCHEMA, SEEK_SCHEMA_VERSION)
        putString(PREVIEW, binding.previewId)
        putString(SESSION, binding.sessionId)
        putLong(BACKEND_GENERATION, binding.backendGeneration)
        putString(GENERATION, binding.generationId)
        putLong(REVISION, binding.queueRevision)
        putString(ENTRY, binding.queueEntryId)
        putString(LOCATION, binding.locationId)
        putLong(CONTENT_REVISION, binding.contentRevision)
        putLong(DURATION, binding.durationMs)
        putLong(PLAYBACK_CYCLE, binding.playbackCycle)
    }

    fun readSeekBinding(arguments: Bundle): QueueSeekBinding = readSeekBinding(arguments, SEEK_BINDING_FIELDS)

    private fun readSeekBinding(arguments: Bundle, fields: Set<String>): QueueSeekBinding {
        require(arguments.keySet() == fields)
        require(arguments.value<Int>(SCHEMA) == SEEK_SCHEMA_VERSION)
        return QueueSeekBinding(
            previewId = arguments.identifier(PREVIEW),
            sessionId = arguments.identifier(SESSION),
            backendGeneration = arguments.value(BACKEND_GENERATION),
            generationId = arguments.identifier(GENERATION),
            queueRevision = arguments.value(REVISION),
            queueEntryId = arguments.identifier(ENTRY),
            locationId = arguments.optionalIdentifier(LOCATION),
            contentRevision = arguments.value(CONTENT_REVISION),
            durationMs = arguments.value(DURATION),
            playbackCycle = arguments.value(PLAYBACK_CYCLE),
        )
    }

    fun seekArguments(request: QueueSeekRequest): Bundle = seekBindingArguments(request.binding).apply {
        putLong(POSITION, request.positionMs)
        putBoolean(EXPLICIT_PLAY, request.explicitPlay)
    }

    fun readSeek(arguments: Bundle): QueueSeekRequest = QueueSeekRequest(
        readSeekBinding(arguments, SEEK_REQUEST_FIELDS), arguments.value<Long>(POSITION).also { require(it >= 0L) }, arguments.value(EXPLICIT_PLAY),
    )

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
        require(arguments.value<Int>(SCHEMA) in 1..SCHEMA_VERSION)
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
        require(arguments.value<Int>(SCHEMA) in 1..SCHEMA_VERSION)
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

    private fun Bundle.optionalIdentifier(key: String): String? = optionalText(key, 128)?.also {
        require(it.isNotBlank())
    }

    private fun Bundle.writeMode(mode: QueueMode) {
        putBoolean(SHUFFLE, mode.shuffle)
        putString(REPEAT, mode.repeat.name)
        mode.cycleLimit?.let { putInt(CYCLE_LIMIT, it) }
    }

    private fun Bundle.readMode(): QueueMode = QueueMode(
        shuffle = value(SHUFFLE),
        repeat = QueueRepeatMode.valueOf(text(REPEAT, 64)),
        cycleLimit = if (containsKey(CYCLE_LIMIT)) value(CYCLE_LIMIT) else null,
    )

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
    val viewRevision: Long,
    val viewCount: Long,
    val selectedOriginalOrder: Long?,
    val expectedCurrentEntryId: String?,
    val mode: QueueMode,
    val action: LibraryQueueAction,
) {
    init {
        require(viewId.isNotBlank() && viewId.length <= 128)
        require(operationId.isNotBlank() && operationId.length <= 128)
        require(viewRevision >= 0L && viewCount > 0L && expectedQueueRevision >= 0L)
        require(expectedGenerationId == null || (expectedGenerationId.isNotBlank() && expectedGenerationId.length <= 128))
        require(expectedCurrentEntryId == null || (expectedCurrentEntryId.isNotBlank() && expectedCurrentEntryId.length <= 128))
        require(expectedGenerationId != null || (expectedQueueRevision == 0L && expectedCurrentEntryId == null))
        require((selected == null) == (selectedOriginalOrder == null))
        require(selected == null || selected.providerId == TrackKey.LOCAL_PROVIDER)
        require(selectedOriginalOrder == null || selectedOriginalOrder in 0L until viewCount)
    }
}

enum class LibraryQueueAction { REPLACE_COMPLETE_LIST, INSERT_NEXT, INSERT_AND_PLAY }
