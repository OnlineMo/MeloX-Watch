package io.github.onlinemo.meloxwatch.data.fileoperations

import io.github.onlinemo.meloxwatch.data.library.LocalReadDescriptor
import io.github.onlinemo.meloxwatch.data.library.LibrarySource
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID

/** 工作与输入预算；不是音频文件容量或完整选择条数的产品上限。 */
object FileOperationLimits {
    const val PAGE_SIZE = 100
    const val BUFFER_BYTES = 64 * 1024
    const val FREE_RESERVE_BYTES = 512L * 1024 * 1024
    const val TAG_METADATA_BYTES = 16 * 1024 * 1024
    const val TAG_RECORDS = 4096
    const val FIELD_CHARACTERS = 4096
    const val ARTIST_VALUES = 128
    const val JOURNAL_ROW_BYTES = 128 * 1024
    const val JOURNAL_REQUEST_BYTES = 4 * 1024 * 1024
}

enum class FileOperationKind {
    REMOVE_FROM_INDEX, REMOVE_SOURCE, DELETE_ORIGINAL, DELETE_APP_COPY, WRITE_TAGS, RENAME, MOVE,
    DELETE_COMPLETED_MOVE_SOURCE,
}

enum class FileOperationPhase {
    REQUESTED, PREVIEW, CONFIRMED, RUNNING, WAITING_SOURCE_CONFIRMATION,
    COMPLETED, PARTIAL, CANCELLED, FAILED, NEEDS_RECOVERY,
}

enum class FileItemPhase {
    REQUESTED, STAGING, VALIDATED, APPLYING, EXTERNAL_APPLIED, INDEX_RECONCILED,
    COPY_VALIDATED, COPY_INDEX_RECONCILED, CANCELLED, FAILED, NEEDS_RECOVERY,
}

enum class FileOperationIssue {
    BUSY, EMPTY_SELECTION, INVALID_INPUT, CONFLICT, CONFIRMATION_REQUIRED,
    SHARED_FILE_CONFIRMATION_REQUIRED, PLAYBACK_CONFIRMATION_REQUIRED, SOURCE_DELETE_CONFIRMATION_REQUIRED,
    PERMISSION_DENIED, PERMISSION_REVOKED, FILE_MISSING, SOURCE_UNAVAILABLE, UNSUPPORTED_PROVIDER, UNSUPPORTED_FORMAT,
    INVALID_FORMAT, SOURCE_CHANGED, TARGET_EXISTS, INCOMPLETE_SCOPE, STORAGE_FULL,
    INTEGRITY_FAILED, PLAYBACK_RELEASE_UNCONFIRMED, PARTIAL_RESULT, CANCELLED, RECOVERY_REQUIRED, STORAGE_UNAVAILABLE,
}

/** 对外消息只含稳定错误码，不包含路径、URI、标题或提供方异常正文。 */
class FileOperationException(val issue: FileOperationIssue, cause: Throwable? = null) : Exception(issue.name, cause)

enum class AudioTagField { TITLE, ARTISTS, ALBUM, ALBUM_ARTIST, TRACK_NUMBER, DISC_NUMBER }
data class FileTagDifference(val field: AudioTagField, val before: List<String>?, val after: List<String>?)

/** 缺键保留字段，null 移除字段；绑定日志前复制列表，防止调用方后续改变确认内容。 */
class AudioTagPatch(values: Map<AudioTagField, List<String>?>) {
    val values: Map<AudioTagField, List<String>?> = Collections.unmodifiableMap(values.mapValues { (field, original) ->
        original?.toList()?.also { copied ->
            require(copied.isNotEmpty())
            require(copied.size <= if (field == AudioTagField.ARTISTS) FileOperationLimits.ARTIST_VALUES else 1)
            require(copied.all { it.isNotBlank() && it.length <= FileOperationLimits.FIELD_CHARACTERS &&
                it.none(Character::isISOControl) && StandardCharsets.UTF_8.newEncoder().canEncode(it) })
            if (field == AudioTagField.TRACK_NUMBER || field == AudioTagField.DISC_NUMBER) {
                val parts = copied.single().split('/')
                require(parts.size in 1..2 && parts.all { part -> part.isNotEmpty() &&
                    part.all { it in '0'..'9' } && part.toIntOrNull()?.let { it >= 0 } == true })
            }
        }?.let { Collections.unmodifiableList(it) }
    })

    init { require(values.isNotEmpty()) }
}

/** 目标是独立授权的文档身份，不由 URI 猜测磁盘路径。 */
data class FileOperationDestination(
    val sourceId: String,
    val treeUri: String,
    val parentObjectId: String,
    val expectedAuthEpoch: Long,
    val displayName: String? = null,
) {
    init {
        require(sourceId.isNotBlank() && sourceId.length <= 128)
        require(treeUri.isNotBlank() && treeUri.length <= 8192)
        require(parentObjectId.isNotBlank() && parentObjectId.length <= 8192)
        require(expectedAuthEpoch > 0L)
        displayName?.let(::requireFileDisplayName)
    }
}

class FileOperationCommand private constructor(
    val kind: FileOperationKind,
    val tags: AudioTagPatch? = null,
    val displayName: String? = null,
    val destination: FileOperationDestination? = null,
    val removedSourceId: String? = null,
    val removedSourceAuthEpoch: Long? = null,
) {
    companion object {
        fun removeFromIndex() = FileOperationCommand(FileOperationKind.REMOVE_FROM_INDEX)
        fun removeSource(sourceId: String, expectedAuthEpoch: Long): FileOperationCommand {
            require(sourceId.isNotBlank() && sourceId.length <= 128 && expectedAuthEpoch > 0L)
            return FileOperationCommand(FileOperationKind.REMOVE_SOURCE,
                removedSourceId = sourceId, removedSourceAuthEpoch = expectedAuthEpoch)
        }
        fun deleteOriginal() = FileOperationCommand(FileOperationKind.DELETE_ORIGINAL)
        fun deleteAppCopy() = FileOperationCommand(FileOperationKind.DELETE_APP_COPY)
        fun writeTags(patch: AudioTagPatch) = FileOperationCommand(FileOperationKind.WRITE_TAGS, tags = patch)
        fun rename(name: String): FileOperationCommand {
            requireFileDisplayName(name)
            return FileOperationCommand(FileOperationKind.RENAME, displayName = name)
        }
        fun move(destination: FileOperationDestination) = FileOperationCommand(FileOperationKind.MOVE, destination = destination)
    }
}

data class FileSelectionToken(val viewId: String, val revision: Long, val totalCount: Long) {
    init { require(viewId.isNotBlank() && viewId.length <= 128 && revision >= 0L && totalCount >= 0L) }
}

data class FileSelectedTrack(val trackKey: TrackKey, val locationId: String?, val contentRevision: Long?)
data class FileSelectionPage(val selection: FileSelectionToken, val offset: Long, val tracks: List<FileSelectedTrack>, val complete: Boolean)
fun interface FileSelectionPageSource {
    suspend fun read(offset: Long, limit: Int): FileSelectionPage
    suspend fun close() = Unit
}
data class FileSourceSelection(val source: LibrarySource, val totalCount: Long)
data class FileSourceTrackPage(val tracks: List<FileSelectedTrack>, val nextTrackId: String?)

enum class FileResourceOwnership { EXTERNAL_ORIGINAL, APP_COPY }

/** 仅保存在 noBackup 私有日志；摘要来自完整文件，不以名称或大小推定字节相同。 */
data class PhysicalFileProof(
    val uri: String,
    val objectId: String,
    val displayName: String,
    val mimeType: String?,
    val device: Long,
    val inode: Long,
    val changedTime: Long,
    val bytes: Long,
    val sha256: String,
) {
    init {
        require(uri.isNotBlank() && uri.length <= 8192 && objectId.isNotBlank() && objectId.length <= 8192)
        require(bytes >= 0L && inode > 0L && changedTime >= 0L && sha256.matches(Regex("[0-9a-f]{64}")))
        requireFileDisplayName(displayName)
    }
}

/** 索引操作不持文件句柄或文件写入证明，不能用它批准原件操作。 */
data class FileOperationTarget(
    val unitKey: String,
    val trackKey: TrackKey,
    val trackVersionId: String,
    val descriptor: LocalReadDescriptor?,
    val parentObjectId: String?,
    val ownership: FileResourceOwnership?,
    val proof: PhysicalFileProof?,
    val removedSourceId: String? = null,
    val displayName: String? = null,
)

enum class FileImpactKind { TRACK, ALIAS, CUE_SEGMENT, SOURCE }
data class FileOperationImpact(
    val impactKey: String,
    val kind: FileImpactKind,
    val trackKey: TrackKey?,
    val sourceId: String?,
    val aliasId: String?,
    val nodeId: String?,
    val objectId: String?,
    val versionId: String? = null,
    val contentRevision: Long? = null,
    val source: LibrarySource? = null,
)

data class FileImpactPage(val rows: List<FileOperationImpact>, val next: String?)

data class WrittenDocument(
    val sourceId: String,
    val treeUri: String,
    val parentObjectId: String,
    val proof: PhysicalFileProof,
)

/** 发布后逐个独立复核已确认来源根的访问能力，不能从另一来源的成功推导。 */
data class FilePublishedRoute(
    val sourceId: String,
    val authEpoch: Long,
    val grantId: String,
    val proof: PhysicalFileProof?,
    val issue: FileOperationIssue?,
)

/** 外部资源须先有角色和确切观察身份，才可能获准清理；名称本身不授予删除权。 */
data class FileOperationArtifacts(
    val stagingName: String? = null,
    val staged: WrittenDocument? = null,
    val backupName: String? = null,
    val originalBackup: WrittenDocument? = null,
    val published: WrittenDocument? = null,
    val tagValues: Map<AudioTagField, List<String>?>? = null,
    val audioSha256: String? = null,
    val originalDeleted: Boolean = false,
    val actualNameDiffers: Boolean = false,
    val restoredOriginal: Boolean = false,
    val sourceDeletionStarted: Boolean = false,
)

data class FileOperationItem(
    val operationId: String,
    val ordinal: Long,
    val target: FileOperationTarget,
    val phase: FileItemPhase = FileItemPhase.REQUESTED,
    val artifacts: FileOperationArtifacts = FileOperationArtifacts(),
    val issue: FileOperationIssue? = null,
    val reconcileAfter: String? = null,
)

data class FileOperationRecord(
    val operationId: String,
    val selection: FileSelectionToken,
    val command: FileOperationCommand,
    val commandBinding: String,
    val phase: FileOperationPhase = FileOperationPhase.REQUESTED,
    val selectedCount: Long = 0L,
    val physicalCount: Long = 0L,
    val trackImpactCount: Long = 0L,
    val aliasImpactCount: Long = 0L,
    val cueImpactCount: Long = 0L,
    val previewBinding: String? = null,
    val sourceDeletionConfirmed: Boolean = false,
)

data class FileOperationPreview(
    val operationId: String,
    val kind: FileOperationKind,
    val binding: String,
    val selectedCount: Long,
    val physicalCount: Long,
    val affectedTracks: Long,
    val affectedAliases: Long,
    val affectedCueSegments: Long,
    val mayPauseAndClosePlayback: Boolean,
    val relatedLyricsMoved: Boolean = false,
)

/** 不同意图区分符隔离索引、改名、标签与原件删除票据。 */
data class FileOperationConfirmation(
    val operationId: String,
    val previewBinding: String,
    val kind: FileOperationKind,
    val accepted: Boolean,
    val wholePhysicalFileImpactAccepted: Boolean,
    val playbackPauseAndCloseAccepted: Boolean,
)

/** 恢复保留原件是独立且绑定对象的恢复动作，不是继续执行草稿。 */
data class FileRecoveryConfirmation(
    val operationId: String,
    val unitKey: String,
    val recoveryBinding: String,
    val action: FileRecoveryAction,
    val accepted: Boolean,
    val playbackPauseAndCloseAccepted: Boolean,
)
enum class FileRecoveryAction { RESTORE_RETAINED_ORIGINAL, DISCARD_STAGING }

data class FileOperationProgress(
    val operationId: String,
    val phase: FileOperationPhase,
    val completed: Long,
    val failed: Long,
    val notExecuted: Long,
    val waitingSourceDeletion: Long,
    val needsRecovery: Long,
    val copiedWithSourceRetained: Long = 0L,
)

data class FileIndexIgnore(val trackKey: TrackKey, val operationId: String, val unitKey: String)
data class FileIgnoredTrack(val trackKey: TrackKey, val title: String)
data class FileIgnoredPage(val tracks: List<FileIgnoredTrack>, val nextTrackId: String?)
enum class FileQueueRepairRole { PUBLISH, RESTORE_ORIGINAL, DELETE_MOVE_SOURCE }

/** 私有日志绑定队列提交边界；主音乐库 schema 不为文件操作日志迁移。 */
data class FileQueueRepairBinding(
    val operationId: String,
    val unitKey: String,
    val role: FileQueueRepairRole,
    val generationId: String?,
    val baseRevision: Long,
    val finalRevision: Long,
    val completed: Boolean = false,
) {
    init {
        fileOperationId(operationId)
        require(unitKey.isNotBlank() && unitKey.length <= 8192)
        require(baseRevision >= 0L && finalRevision >= baseRevision)
        require(if (generationId == null) finalRevision == baseRevision else
            baseRevision < Long.MAX_VALUE && finalRevision == baseRevision + 1L)
    }
}

/** UI 草稿保存持久行或全选例外，不保存可执行确认，也不在内存物化完整列表。 */
data class FileSelectionDraft(
    val draftId: String,
    val view: FileSelectionToken,
    val allSelected: Boolean = false,
    val generation: Long = 0L,
    val revision: Long = 0L,
    val ordinalSequence: Long = 0L,
    val selectedCount: Long = 0L,
) {
    init {
        fileOperationId(draftId)
        require(generation >= 0L && revision >= 0L && ordinalSequence >= 0L && selectedCount in 0L..view.totalCount)
    }
    val selection: FileSelectionToken get() = FileSelectionToken("draft:$draftId:$revision", view.revision, selectedCount)
}
data class FileDraftTrack(val ordinal: Long, val selected: FileSelectedTrack)

/** 构造、服务重建和草稿恢复均不重放文件操作。 */
interface FileOperationJournal : AutoCloseable {
    fun record(operationId: String): FileOperationRecord?
    fun create(record: FileOperationRecord)
    fun appendSelection(operationId: String, ordinal: Long, selected: FileSelectedTrack, target: FileOperationTarget): Boolean
    fun appendImpacts(operationId: String, unitKey: String, impacts: List<FileOperationImpact>)
    fun appendSources(operationId: String, sourceIds: List<String>)
    fun sources(operationId: String, after: String? = null, limit: Int = FileOperationLimits.PAGE_SIZE): List<String>
    fun saveRoute(operationId: String, unitKey: String, route: FilePublishedRoute)
    fun route(operationId: String, unitKey: String, sourceId: String): FilePublishedRoute?
    fun sealPreview(operationId: String): FileOperationRecord
    fun updateRecord(record: FileOperationRecord)
    fun updateItem(item: FileOperationItem)
    fun unit(operationId: String, unitKey: String): FileOperationItem?
    fun items(operationId: String, afterOrdinal: Long? = null, limit: Int = FileOperationLimits.PAGE_SIZE): List<FileOperationItem>
    fun impacts(operationId: String, unitKey: String, after: String? = null, limit: Int = FileOperationLimits.PAGE_SIZE): FileImpactPage
    fun progress(operationId: String): FileOperationProgress
    fun pending(afterOperationId: String? = null, limit: Int = FileOperationLimits.PAGE_SIZE): List<FileOperationRecord>
    fun hasPendingSource(sourceId: String, excludingOperationId: String? = null): Boolean
    fun hasUncoordinatedWork(operationId: String? = null): Boolean
    fun sourceDeletionPreview(operationId: String): FileOperationPreview
    fun saveIndexIgnore(ignore: FileIndexIgnore)
    fun indexIgnore(trackKey: TrackKey): FileIndexIgnore?
    fun clearIndexIgnore(ignore: FileIndexIgnore)
    fun clearIndexUndoAfterLibraryReplacement()
    fun queueRepair(operationId: String, unitKey: String, role: FileQueueRepairRole): FileQueueRepairBinding?
    fun bindQueueRepair(binding: FileQueueRepairBinding): FileQueueRepairBinding
    fun completeQueueRepair(binding: FileQueueRepairBinding)
    fun createSelectionDraft(draft: FileSelectionDraft)
    fun selectionDraft(draftId: String): FileSelectionDraft?
    fun toggleSelectionDraft(draftId: String, expectedRevision: Long, selected: FileSelectedTrack): FileSelectionDraft
    fun selectAllDraft(draftId: String, expectedRevision: Long, selected: Boolean): FileSelectionDraft
    fun draftExceptions(draftId: String, expectedRevision: Long, keys: List<TrackKey>): Set<TrackKey>
    fun selectedDraftPage(draftId: String, expectedRevision: Long, afterOrdinal: Long?, limit: Int = FileOperationLimits.PAGE_SIZE): List<FileDraftTrack>
    fun retireSelectionDraft(draftId: String)
    fun retireOrphanDraftPage(activeDraftIds: Set<String>, afterDraftId: String?, limit: Int = FileOperationLimits.PAGE_SIZE): String?
    fun reclaimRetiredDraftPage(limit: Int = FileOperationLimits.PAGE_SIZE): Boolean
}

internal fun fileOperationId(value: String) {
    require(value.length == 36 && UUID.fromString(value).toString() == value)
}

internal fun requireFileDisplayName(value: String) {
    require(value.isNotBlank() && value.length <= 255 && value !in setOf(".", ".."))
    require(value.none { it == '/' || it == '\\' || it == '\u0000' || Character.isISOControl(it) })
    require(StandardCharsets.UTF_8.newEncoder().canEncode(value))
}

internal fun fileOperationDigest(value: ByteArray): String = fileOperationHex(MessageDigest.getInstance("SHA-256").digest(value))
internal fun fileOperationHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
    val digits = "0123456789abcdef"
    for (byte in bytes) { val value = byte.toInt() and 255
        append(digits[value ushr 4])
        append(digits[value and 15]) }
}
