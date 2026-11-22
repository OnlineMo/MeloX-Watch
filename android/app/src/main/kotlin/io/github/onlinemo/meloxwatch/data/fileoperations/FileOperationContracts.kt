package io.github.onlinemo.meloxwatch.data.fileoperations

import android.os.CancellationSignal
import io.github.onlinemo.meloxwatch.data.library.LibrarySource
import io.github.onlinemo.meloxwatch.data.library.LocalReadDescriptor
import io.github.onlinemo.meloxwatch.model.TrackKey

/** 仅使用短音乐数据库事务；外部文件打开与访问验证不在事务内执行。 */
interface FileOperationLibrary {
    suspend fun snapshot(selected: FileSelectedTrack, kind: FileOperationKind): FileOperationTarget
    suspend fun impacts(target: FileOperationTarget, after: String? = null): FileImpactPage
    suspend fun check(target: FileOperationTarget, destination: FileOperationDestination? = null, afterExternalApply: Boolean = false)
    suspend fun cancelScans(sourceIds: List<String>)
    suspend fun reconcile(item: FileOperationItem, command: FileOperationCommand, impacts: List<FileOperationImpact>, routes: List<FilePublishedRoute>)
    suspend fun source(sourceId: String): LibrarySource
    suspend fun sourceSelection(sourceId: String, expectedAuthEpoch: Long): FileSourceSelection
    suspend fun sourceTrackPage(sourceId: String, afterTrackId: String? = null): FileSourceTrackPage
    suspend fun retainedRead(trackKey: TrackKey, locationId: String?, expectedContentRevision: Long? = null): LocalReadDescriptor?
    suspend fun removeSource(sourceId: String, expectedAuthEpoch: Long)
    suspend fun restoreIndex(removed: FileOperationTarget)
}

/** 全部调用须持有应用的唯一物理 I/O 租约。 */
interface FileOperationPhysicalAdapter {
    fun inspect(target: FileOperationTarget, kind: FileOperationKind, cancellation: CancellationSignal): PhysicalFileProof
    fun readTagFields(target: FileOperationTarget, cancellation: CancellationSignal): Map<AudioTagField, List<String>?> =
        throw FileOperationException(FileOperationIssue.UNSUPPORTED_FORMAT)
    fun apply(item: FileOperationItem, command: FileOperationCommand, sourceDeletionConfirmed: Boolean,
        cancellation: CancellationSignal, save: (FileOperationItem) -> Unit): FileOperationItem
    /** 先观察确切原件、暂存和发布对象，再选择恢复；此入口不重放删除。 */
    fun recover(item: FileOperationItem, command: FileOperationCommand, cancellation: CancellationSignal,
        save: (FileOperationItem) -> Unit): FileOperationItem
    fun restoreRetainedOriginal(item: FileOperationItem, command: FileOperationCommand, cancellation: CancellationSignal,
        save: (FileOperationItem) -> Unit): FileOperationItem
    fun discardStaging(item: FileOperationItem, command: FileOperationCommand, cancellation: CancellationSignal,
        save: (FileOperationItem) -> Unit): FileOperationItem
    /** 只有仍在执行的原标签确认可补偿它自己中断的替换，不能据此恢复历史草稿。 */
    fun compensateInterruptedTag(item: FileOperationItem, command: FileOperationCommand, cancellation: CancellationSignal,
        save: (FileOperationItem) -> Unit): FileOperationItem = item
    fun finishArtifacts(item: FileOperationItem, cancellation: CancellationSignal): FileOperationItem
    fun verifyPublishedRoute(source: LibrarySource, document: WrittenDocument, cancellation: CancellationSignal): FilePublishedRoute
}

/** 成功进入后持续排除新播放器分配，直到文件操作及已接受的收尾真正结束。 */
interface FileOperationPlaybackGate {
    suspend fun <T> withClosedResources(action: suspend (FilePlaybackReservation) -> T): T
}

interface FileOperationQueueReferences {
    suspend fun reconcile(item: FileOperationItem, command: FileOperationCommand, reservation: FilePlaybackReservation)
}
