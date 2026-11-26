package io.github.onlinemo.meloxwatch.data.fileoperations

import android.content.Context
import io.github.onlinemo.meloxwatch.data.cue.CueScanStore
import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.library.LibraryMutationStore
import io.github.onlinemo.meloxwatch.data.library.LocalReadDescriptor
import io.github.onlinemo.meloxwatch.data.library.RoomLocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.queue.RoomQueueStore
import io.github.onlinemo.meloxwatch.data.storage.FileStoragePhysical
import io.github.onlinemo.meloxwatch.data.storage.StorageCategory
import io.github.onlinemo.meloxwatch.data.storage.StorageIssue
import io.github.onlinemo.meloxwatch.data.storage.combineStorage
import io.github.onlinemo.meloxwatch.model.TrackKey
import io.github.onlinemo.meloxwatch.playback.PlaybackEngineReleaseGuard
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

sealed interface FileOperationsState {
    data object Loading : FileOperationsState
    data class Ready(val recoveryRequired: Boolean) : FileOperationsState
    data class Unavailable(val issue: FileOperationIssue, val priorJournalAbsent: Boolean) : FileOperationsState
}

/** 同一应用运行时向文件、扫描及来源命令提供同一日志与 guard。 */
class FileOperationsRuntime(
    private val context: Context,
    private val library: RoomLocalLibraryRepository,
    private val queues: RoomQueueStore,
    private val io: LocalProviderIo,
    private val host: FileOperationPlaybackHost,
    private val cancelScan: suspend (String) -> Unit,
    private val validateRead: suspend (LocalReadDescriptor) -> LocalReadDescriptor,
    private val requireFileMutationReady: () -> Unit,
    private val progressScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private data class Components(val journal: FileOperationJournal, val guard: FileOperationMutationGuard,
        val library: RoomFileOperationLibrary, val repository: FileOperationsRepository, val selections: FileSelectionDraftOwner)
    private val opening = Mutex()
    @Volatile private var components: Components? = null
    private val mutableState = MutableStateFlow<FileOperationsState>(FileOperationsState.Loading)
    val state = mutableState.asStateFlow()
    private val mutableProgress = MutableStateFlow<FileOperationProgress?>(null)
    val progress = mutableProgress.asStateFlow()
    private var progressObserver: Job? = null
    @Volatile private var closed = false
    val mutations: LibraryMutationStore = FileOperationGuardedMutationStore(library) { initialized().guard }
    val cueMutations: CueScanStore get() = mutations as CueScanStore

    /** 启动仅打开/校验私有日志，不枚举外部音乐目录，也不恢复执行草稿。 */
    suspend fun initialize(): FileOperationsState {
        initialized()
        refreshState()
        return mutableState.value
    }

    suspend fun prepare(operationId: String, selection: FileSelectionToken, command: FileOperationCommand, source: FileSelectionPageSource) =
        initialized().repository.prepare(operationId, selection, command, source)
    suspend fun prepareSourceRemoval(operationId: String, sourceId: String, expectedAuthEpoch: Long) =
        initialized().repository.prepareSourceRemoval(operationId, sourceId, expectedAuthEpoch)
    suspend fun confirm(confirmation: FileOperationConfirmation) = initialized().repository.confirm(confirmation)
    suspend fun retry(confirmation: FileOperationConfirmation) = initialized().repository.retry(confirmation)
    suspend fun sourceDeletionPreview(operationId: String) = initialized().repository.sourceDeletionPreview(operationId)
    suspend fun recover(operationId: String) = initialized().repository.recover(operationId)
    suspend fun recoveryBinding(operationId: String, unitKey: String, action: FileRecoveryAction) = initialized().repository.recoveryBinding(operationId, unitKey, action)
    suspend fun restoreOriginal(confirmation: FileRecoveryConfirmation) = initialized().repository.restoreOriginal(confirmation)
    suspend fun discardStaging(confirmation: FileRecoveryConfirmation) = initialized().repository.discardStaging(confirmation)
    suspend fun restoreIndex(operationId: String) = initialized().repository.restoreIndex(operationId)
    suspend fun readProgress(operationId: String) = initialized().repository.readProgress(operationId)
    suspend fun operationPreview(operationId: String) = initialized().repository.operationPreview(operationId)
    suspend fun tagDifferences(operationId: String, unitKey: String) = initialized().repository.tagDifferences(operationId, unitKey)
    suspend fun items(operationId: String, afterOrdinal: Long? = null, limit: Int = FileOperationLimits.PAGE_SIZE) = initialized().repository.items(operationId, afterOrdinal, limit)
    suspend fun pending(afterOperationId: String? = null, limit: Int = FileOperationLimits.PAGE_SIZE) = initialized().repository.pending(afterOperationId, limit)
    suspend fun ignoredTracks(afterTrackId: String? = null, limit: Int = FileOperationLimits.PAGE_SIZE) = initialized().library.ignoredTracks(afterTrackId, limit)
    suspend fun restoreIgnoredTrack(key: TrackKey) {
        val ready = initialized()
        ready.guard.originalMutation {
            requireFileMutationReady()
            ready.library.restoreIndex(ready.library.ignoredTarget(key))
            io.read { ready.journal.indexIgnore(key)?.let(ready.journal::clearIndexIgnore) }
        }
    }
    suspend fun openSelectionDraft(fromViewId: String) = initialized().selections.open(fromViewId)
    suspend fun toggleSelectionDraft(draft: FileSelectionDraft, key: TrackKey) = initialized().selections.toggle(draft, key)
    suspend fun selectAllDraft(draft: FileSelectionDraft, selected: Boolean) = initialized().selections.selectAll(draft, selected)
    suspend fun selectionMembership(draft: FileSelectionDraft, keys: List<TrackKey>) = initialized().selections.membership(draft, keys)
    suspend fun selectionProducer(draft: FileSelectionDraft) = initialized().selections.producer(draft)
    suspend fun closeSelectionDraft(draft: FileSelectionDraft) = initialized().selections.close(draft)
    suspend fun hasPendingSource(sourceId: String): Boolean {
        val ready = initialized()
        return io.read { ready.journal.hasPendingSource(sourceId) }
    }
    suspend fun storageReferencePage(afterLocationId: String? = null) = initialized().library.storageReferencePage(afterLocationId)

    /** 仅统计已记录的恢复角色；移动发布目标是已完成音频，不能统计成可清理暂存。 */
    suspend fun measureRecoveryStorage(): StorageCategory {
        val ready = initialized()
        var total = StorageCategory(0L, 0L)
        var afterOperation: String? = null
        while (true) {
            val records = io.read { ready.journal.pending(afterOperation) }
            if (records.isEmpty()) return total
            for (record in records) {
                var afterUnit: Long? = null
                while (true) {
                    val page = io.read { ready.journal.items(record.operationId, afterUnit) }
                    if (page.isEmpty()) break
                    for (item in page) {
                        val staged = item.artifacts.staged?.takeUnless { it.proof.inode == item.artifacts.published?.proof?.inode &&
                            it.proof.device == item.artifacts.published?.proof?.device }
                        val documents = listOfNotNull(staged, item.artifacts.originalBackup)
                        for (document in documents) {
                            val measurement = try {
                                val bytes = io.read { signal -> FileStoragePhysical.recovery(context, document, signal) }
                                if (bytes == null) StorageCategory(null, 0L, 1L, StorageIssue.INCOMPLETE) else StorageCategory(bytes, 1L)
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { StorageCategory(null, 0L, 1L, StorageIssue.UNAVAILABLE) }
                            total = combineStorage(total, measurement)
                        }
                        if (item.artifacts.stagingName != null && item.artifacts.staged == null && item.artifacts.published == null &&
                            item.phase in setOf(FileItemPhase.STAGING, FileItemPhase.APPLYING, FileItemPhase.NEEDS_RECOVERY)) {
                            total = combineStorage(total, StorageCategory(null, 0L, 1L, StorageIssue.INCOMPLETE))
                        }
                    }
                    afterUnit = page.last().ordinal
                }
            }
            afterOperation = records.last().operationId
        }
    }

    /** 持有实际接入本运行时的文件/扫描/来源写入围栏；其他数据库写者需各自接入恢复 barrier。 */
    suspend fun <T> withExclusiveLibraryMutation(requireNoPending: Boolean = true, action: suspend () -> T): T =
        initialized(initializeLibrary = false).repository.withExclusiveLibraryMutation(requireNoPending, action)
    /** 服务确认关闭后持有从未分配播放器的真实原生租约；结束不重建服务或自动发声。 */
    suspend fun <T> withClosedPlayback(action: suspend (FilePlaybackReservation) -> T): T = initialized(initializeLibrary = false).repository.withClosedPlayback(action)
    suspend fun clearIndexUndoAfterLibraryReplacement() = initialized(initializeLibrary = false).repository.clearIndexUndoAfterLibraryReplacement()

    /** Owner shutdown preserves the journal bytes and never closes an admitted original mutation. */
    internal suspend fun closeForOwner() {
        if (!opening.tryLock()) throw FileOperationException(FileOperationIssue.BUSY)
        try { withContext(NonCancellable) {
            val ready = components
            if (ready != null) ready.guard.originalMutation {
                io.read { ready.journal.close() }
                components = null
            }
            progressObserver?.cancelAndJoin(); progressObserver = null
            closed = true
            host.updateRecoveryBlock(true)
        } } finally { opening.unlock() }
    }

    /** 队列可保留明确忽略的曲目关系，但不能绕过来源、内容 revision 或授权校验。 */
    suspend fun retainedRead(trackKey: TrackKey, locationId: String, revision: Long): LocalReadDescriptor? =
        initialized().library.retainedRead(trackKey, locationId, revision)

    suspend fun assertPlaybackReadable() {
        try { initialize() }
        catch (failure: FileOperationException) {
            val known = mutableState.value
            if (known is FileOperationsState.Unavailable && known.priorJournalAbsent) return
            throw LibraryException(LibraryIssue.RECOVERY_REQUIRED, failure)
        }
        if ((mutableState.value as? FileOperationsState.Ready)?.recoveryRequired == true) throw LibraryException(LibraryIssue.RECOVERY_REQUIRED)
        withContext(Dispatchers.Main.immediate) {
            if (host.isReadBlocked()) throw LibraryException(LibraryIssue.RECOVERY_REQUIRED)
        }
    }

    private suspend fun initialized(initializeLibrary: Boolean = true): Components {
        if (closed) throw FileOperationException(FileOperationIssue.STORAGE_UNAVAILABLE)
        components?.let { return it }
        if (!opening.tryLock()) throw FileOperationException(FileOperationIssue.BUSY)
        var priorAbsent = false
        try {
            components?.let { return it }
            return withContext(NonCancellable) {
                // Pending LP-11 recovery must close/checkpoint playback before its exclusive ticket.
                // These hooks open the existing file journal/guard without an early main writer.
                if (initializeLibrary) library.initialize()
                val journal = io.read(abandoned = FileOperationJournal::close) {
                    val file = File(context.noBackupFilesDir, SqliteFileOperationJournal.FILE_NAME)
                    priorAbsent = try {
                        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                        false
                    } catch (_: NoSuchFileException) {
                        Files.readAttributes(context.noBackupFilesDir.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isDirectory
                    }
                    SqliteFileOperationJournal.open(context)
                }
                try {
                    val guard = FileOperationMutationGuard(journal, io)
                    val bridge = RoomFileOperationLibrary(library, cancelScan)
                    val queueReferences = RoomFileOperationQueueReferences(library, bridge, queues, validateRead, journal, io)
                    val repository = FileOperationsRepository(bridge, journal, io, guard,
                        RoutingFileOperationAdapter(SafOriginalFileAdapter(context), AppCopyDeletionAdapter(context)),
                        ServiceClosingFileOperationGate(host), queueReferences, requireFileMutationReady, ::refreshState)
                    Components(journal, guard, bridge, repository, FileSelectionDraftOwner(library, journal, io)).also {
                        components = it
                        progressObserver = progressScope.launch { repository.progress.collect { value -> mutableProgress.value = value } }
                    }
                } catch (failure: Throwable) {
                    io.read { journal.close() }
                    throw failure
                }
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) {
            val issue = (failure as? FileOperationException)?.issue ?: FileOperationIssue.RECOVERY_REQUIRED
            mutableState.value = FileOperationsState.Unavailable(issue, priorAbsent)
            host.updateRecoveryBlock(!priorAbsent || PlaybackEngineReleaseGuard.process.hasUnconfirmedRelease())
            throw FileOperationException(issue, failure)
        } finally { opening.unlock() }
    }

    private suspend fun refreshState() = withContext(NonCancellable) {
        val ready = components ?: return@withContext
        io.awaitIdle()
        try {
            val blocked = io.read { ready.journal.hasUncoordinatedWork() } || PlaybackEngineReleaseGuard.process.hasUnconfirmedRelease() ||
                withContext(Dispatchers.Main.immediate) { host.hasUnconfirmedClosure() }
            mutableState.value = FileOperationsState.Ready(blocked)
            host.updateRecoveryBlock(blocked)
        } catch (failure: Exception) {
            val issue = (failure as? FileOperationException)?.issue ?: FileOperationIssue.RECOVERY_REQUIRED
            mutableState.value = FileOperationsState.Unavailable(issue, priorJournalAbsent = false)
            host.updateRecoveryBlock(true)
            throw FileOperationException(issue, failure)
        }
    }
}
