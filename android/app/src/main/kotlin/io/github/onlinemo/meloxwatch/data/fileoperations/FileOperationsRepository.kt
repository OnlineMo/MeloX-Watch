package io.github.onlinemo.meloxwatch.data.fileoperations

import android.os.CancellationSignal
import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.library.LibrarySourceState
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 冻结完整选择的 main-safe 所有者；外部调用前先写日志，之后协调实际结果，
 * 包括取消后才返回的结果。构造和 UI 状态恢复不执行、删除或写入草稿。
 */
class FileOperationsRepository(
    private val library: FileOperationLibrary,
    private val journal: FileOperationJournal,
    private val io: LocalProviderIo,
    private val guard: FileOperationMutationGuard,
    private val physical: FileOperationPhysicalAdapter,
    private val playback: FileOperationPlaybackGate,
    private val references: FileOperationQueueReferences,
    private val requireFileMutationReady: () -> Unit,
    private val onRecoveryStateChanged: suspend () -> Unit,
) {
    private val mutableProgress = MutableStateFlow<FileOperationProgress?>(null)
    val progress = mutableProgress.asStateFlow()

    suspend fun prepare(operationId: String, selection: FileSelectionToken, command: FileOperationCommand,
        producer: FileSelectionPageSource): FileOperationPreview = guard.originalMutation {
        var primaryFailure: Throwable? = null
        try {
            requireMutationReady()
            fileOperationId(operationId)
            val existing = worker { journal.record(operationId) }
            if (existing != null) return@originalMutation preview(existing)
            if (selection.totalCount == 0L && command.kind != FileOperationKind.REMOVE_SOURCE) fail(FileOperationIssue.EMPTY_SELECTION)
            val record = FileOperationRecord(operationId, selection, command, FileOperationCodec.commandBinding(selection, command))
            worker { journal.create(record) }
            command.removedSourceId?.let { sourceId ->
                requireUnoccupiedSource(sourceId)
                worker { journal.appendSources(operationId, listOf(sourceId)) }
            }
            command.destination?.let { destination ->
                val source = library.source(destination.sourceId)
                if (source.authEpoch != destination.expectedAuthEpoch || source.spec.uri != destination.treeUri) fail(FileOperationIssue.SOURCE_CHANGED)
                requireUnoccupiedSource(source.spec.sourceId)
                worker { journal.appendSources(operationId, listOf(source.spec.sourceId)) }
            }
            var offset = 0L
            while (offset < selection.totalCount) {
                currentCoroutineContext().ensureActive()
                val page = producer.read(offset, FileOperationLimits.PAGE_SIZE)
                val next = Math.addExact(offset, page.tracks.size.toLong())
                if (page.selection != selection || page.offset != offset || page.tracks.size !in 1..FileOperationLimits.PAGE_SIZE ||
                    next > selection.totalCount || page.complete != (next == selection.totalCount)) fail(FileOperationIssue.INCOMPLETE_SCOPE)
                for (selected in page.tracks) {
                    var target = library.snapshot(selected, command.kind)
                    if (command.kind == FileOperationKind.REMOVE_SOURCE) target = target.copy(removedSourceId = command.removedSourceId)
                    target.descriptor?.let { requireUnoccupiedSource(it.source.spec.sourceId) }
                    library.check(target, command.destination)
                    val earlier = worker { journal.unit(operationId, target.unitKey) }
                    if (target.descriptor != null) {
                        target = target.copy(proof = earlier?.target?.proof ?: worker { signal -> physical.inspect(target, command.kind, signal) })
                    }
                    val newUnit = worker { journal.appendSelection(operationId, offset, selected, target) }
                    if (newUnit) {
                        freezeImpacts(operationId, target)
                        command.destination?.let { destination ->
                            val source = library.source(destination.sourceId)
                            val impact = FileOperationImpact("S:${source.spec.sourceId}", FileImpactKind.SOURCE, null,
                                source.spec.sourceId, null, null, null, source = source)
                            worker { journal.appendImpacts(operationId, target.unitKey, listOf(impact)) }
                        }
                    }
                    offset = Math.addExact(offset, 1L)
                }
            }
            val sealed = worker { journal.sealPreview(operationId) }
            publish(operationId)
            preview(sealed)
        } catch (failure: CancellationException) {
            primaryFailure = failure
            cleanupPreserving(failure) { stopPreparation(operationId, FileOperationPhase.CANCELLED) }
            throw failure
        } catch (failure: Exception) {
            primaryFailure = failure
            cleanupPreserving(failure) { stopPreparation(operationId, FileOperationPhase.FAILED) }
            throw map(failure)
        } finally {
            cleanupPreserving(primaryFailure) {
                io.awaitIdle()
                producer.close()
                onRecoveryStateChanged()
            }
        }
    }

    suspend fun prepareSourceRemoval(operationId: String, sourceId: String, expectedAuthEpoch: Long): FileOperationPreview {
        val existing = worker { journal.record(operationId) }
        if (existing != null) return preview(existing)
        val scope = library.sourceSelection(sourceId, expectedAuthEpoch)
        val token = FileSelectionToken("source:$operationId", scope.source.revision, scope.totalCount)
        var after: String? = null
        var nextOffset = 0L
        val producer = FileSelectionPageSource { offset, limit ->
            if (offset != nextOffset || limit != FileOperationLimits.PAGE_SIZE) fail(FileOperationIssue.INVALID_INPUT)
            val current = library.sourceSelection(sourceId, expectedAuthEpoch)
            if (current.source != scope.source || current.totalCount != scope.totalCount) fail(FileOperationIssue.SOURCE_CHANGED)
            val page = library.sourceTrackPage(sourceId, after)
            after = page.nextTrackId
            nextOffset = Math.addExact(nextOffset, page.tracks.size.toLong())
            FileSelectionPage(token, offset, page.tracks, nextOffset == token.totalCount && after == null)
        }
        return prepare(operationId, token, FileOperationCommand.removeSource(sourceId, expectedAuthEpoch), producer)
    }

    suspend fun confirm(confirmation: FileOperationConfirmation): FileOperationProgress = guard.originalMutation {
        requireMutationReady()
        val record = worker { journal.record(confirmation.operationId) } ?: fail(FileOperationIssue.INVALID_INPUT)
        if (confirmation.kind == FileOperationKind.DELETE_COMPLETED_MOVE_SOURCE) return@originalMutation confirmSourceDeletion(record, confirmation)
        val shown = preview(record)
        validateConfirmation(shown, confirmation)
        if (record.phase != FileOperationPhase.PREVIEW) return@originalMutation publish(record.operationId)
        if (!confirmation.accepted) {
            cancelRemaining(record.operationId)
            worker { journal.updateRecord(record.copy(phase = FileOperationPhase.CANCELLED)) }
            return@originalMutation publish(record.operationId)
        }
        worker { journal.updateRecord(record.copy(phase = FileOperationPhase.CONFIRMED)) }
        execute(record.copy(phase = FileOperationPhase.CONFIRMED))
    }

    /** 明确重试只用原来绑定的行，不向新 producer 取得另一批曲目。 */
    suspend fun retry(confirmation: FileOperationConfirmation): FileOperationProgress = guard.originalMutation {
        requireMutationReady()
        val record = worker { journal.record(confirmation.operationId) } ?: fail(FileOperationIssue.INVALID_INPUT)
        validateConfirmation(preview(record), confirmation)
        if (!confirmation.accepted || record.phase !in setOf(FileOperationPhase.FAILED, FileOperationPhase.PARTIAL, FileOperationPhase.WAITING_SOURCE_CONFIRMATION)) {
            return@originalMutation publish(record.operationId)
        }
        if (record.sourceDeletionConfirmed && worker { journal.progress(record.operationId).copiedWithSourceRetained > 0L }) {
            fail(FileOperationIssue.SOURCE_DELETE_CONFIRMATION_REQUIRED)
        }
        visit(record.operationId) { item ->
            if (item.phase == FileItemPhase.FAILED && item.artifacts == FileOperationArtifacts()) {
                worker { journal.updateItem(item.copy(phase = FileItemPhase.REQUESTED, issue = null)) }
            }
        }
        execute(record)
    }

    suspend fun sourceDeletionPreview(operationId: String): FileOperationPreview = guard.originalMutation {
        requireMutationReady()
        val record = worker { journal.record(operationId) } ?: fail(FileOperationIssue.INVALID_INPUT)
        visit(operationId) { item -> if (item.phase == FileItemPhase.COPY_INDEX_RECONCILED) {
            // 新 UI 票据仍受原冻结范围约束；授权或别名改变时须另建原件预览，
            // 旧票据不能继承新权限。
            library.check(item.target, record.command.destination)
            verifyFrozenScope(item, record.command)
        } }
        worker { journal.sourceDeletionPreview(operationId) }
    }

    /** 只做明确要求的观察和协调；取消未完成草稿及未执行项，不自动重放。 */
    suspend fun recover(operationId: String): FileOperationProgress = guard.originalMutation {
        requireMutationReady()
        val record = worker { journal.record(operationId) } ?: fail(FileOperationIssue.INVALID_INPUT)
        if (record.phase in setOf(FileOperationPhase.REQUESTED, FileOperationPhase.PREVIEW)) {
            cancelRemaining(operationId)
            worker { journal.updateRecord(record.copy(phase = FileOperationPhase.CANCELLED)) }
            return@originalMutation publish(operationId)
        }
        if (record.phase in TERMINAL && !worker { journal.hasUncoordinatedWork(operationId) }) return@originalMutation publish(operationId)
        if (record.command.kind in INDEX_KINDS) {
            recoverIndex(record)
            finish(record.operationId, cancelled = true)
        } else playback.withClosedResources { reservation ->
            withContext(NonCancellable) {
                io.awaitIdle()
                if (record.phase !in setOf(FileOperationPhase.REQUESTED, FileOperationPhase.PREVIEW)) {
                    worker { journal.updateRecord(record.copy(phase = FileOperationPhase.RUNNING)) }
                    reconcileObserved(record, reservation)
                }
                cancelRemaining(operationId)
                finish(operationId, cancelled = true)
                onRecoveryStateChanged()
            }
        }
        publish(operationId)
    }

    suspend fun recoveryBinding(operationId: String, unitKey: String, action: FileRecoveryAction): String = worker {
        val item = journal.unit(operationId, unitKey) ?: fail(FileOperationIssue.INVALID_INPUT)
        if (item.phase != FileItemPhase.NEEDS_RECOVERY || action == FileRecoveryAction.RESTORE_RETAINED_ORIGINAL &&
            (item.artifacts.originalBackup == null || item.artifacts.published != null) || action == FileRecoveryAction.DISCARD_STAGING &&
            (item.artifacts.staged == null || item.artifacts.originalBackup != null || item.artifacts.published != null && !item.artifacts.restoredOriginal)) {
            fail(FileOperationIssue.RECOVERY_REQUIRED)
        }
        recoveryBinding(item, action)
    }

    suspend fun restoreOriginal(confirmation: FileRecoveryConfirmation): FileOperationProgress = guard.originalMutation {
        requireMutationReady()
        val record = worker { journal.record(confirmation.operationId) } ?: fail(FileOperationIssue.INVALID_INPUT)
        val item = worker { journal.unit(record.operationId, confirmation.unitKey) } ?: fail(FileOperationIssue.INVALID_INPUT)
        if (!confirmation.accepted || confirmation.action != FileRecoveryAction.RESTORE_RETAINED_ORIGINAL || !confirmation.playbackPauseAndCloseAccepted ||
            confirmation.recoveryBinding != recoveryBinding(item, confirmation.action)) fail(FileOperationIssue.CONFIRMATION_REQUIRED)
        playback.withClosedResources { reservation ->
            var primaryFailure: Throwable? = null
            try {
                worker { journal.updateRecord(record.copy(phase = FileOperationPhase.RUNNING)) }
                val restored = worker { signal -> physical.restoreRetainedOriginal(item, record.command, signal, journal::updateItem) }
                reconcile(restored, record.command, reservation)
                finish(record.operationId, cancelled = true)
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                cleanupPreserving(primaryFailure) { io.awaitIdle()
                    reconcileObserved(record, reservation)
                    onRecoveryStateChanged() }
            }
        }
        publish(record.operationId)
    }

    suspend fun discardStaging(confirmation: FileRecoveryConfirmation): FileOperationProgress = guard.originalMutation {
        requireMutationReady()
        val record = worker { journal.record(confirmation.operationId) } ?: fail(FileOperationIssue.INVALID_INPUT)
        val item = worker { journal.unit(record.operationId, confirmation.unitKey) } ?: fail(FileOperationIssue.INVALID_INPUT)
        if (!confirmation.accepted || confirmation.action != FileRecoveryAction.DISCARD_STAGING || !confirmation.playbackPauseAndCloseAccepted ||
            confirmation.recoveryBinding != recoveryBinding(item, confirmation.action)) fail(FileOperationIssue.CONFIRMATION_REQUIRED)
        playback.withClosedResources {
            var primaryFailure: Throwable? = null
            try {
                worker { journal.updateRecord(record.copy(phase = FileOperationPhase.RUNNING)) }
                worker { signal -> physical.discardStaging(item, record.command, signal, journal::updateItem) }
                finish(record.operationId, cancelled = true)
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally { cleanupPreserving(primaryFailure) { io.awaitIdle()
                onRecoveryStateChanged() } }
        }
        publish(record.operationId)
    }

    suspend fun restoreIndex(operationId: String): FileOperationProgress = guard.originalMutation {
        requireMutationReady()
        val record = worker { journal.record(operationId) } ?: fail(FileOperationIssue.INVALID_INPUT)
        if (record.command.kind != FileOperationKind.REMOVE_FROM_INDEX || record.phase != FileOperationPhase.COMPLETED) fail(FileOperationIssue.INVALID_INPUT)
        visit(operationId) { item ->
            val ignored = worker { journal.indexIgnore(item.target.trackKey) }
            if (ignored == FileIndexIgnore(item.target.trackKey, operationId, item.target.unitKey)) {
                library.restoreIndex(item.target)
                worker { journal.clearIndexIgnore(checkNotNull(ignored)) }
            }
        }
        publish(operationId)
    }

    suspend fun readProgress(operationId: String): FileOperationProgress = publish(operationId)
    suspend fun operationPreview(operationId: String): FileOperationPreview = preview(worker { journal.record(operationId) }
        ?: fail(FileOperationIssue.INVALID_INPUT))
    suspend fun tagDifferences(operationId: String, unitKey: String): List<FileTagDifference> {
        val record = worker { journal.record(operationId) } ?: fail(FileOperationIssue.INVALID_INPUT)
        if (record.phase != FileOperationPhase.PREVIEW || record.command.kind != FileOperationKind.WRITE_TAGS) fail(FileOperationIssue.INVALID_INPUT)
        val target = worker { journal.unit(operationId, unitKey)?.target } ?: fail(FileOperationIssue.INVALID_INPUT)
        val actual = worker { signal -> physical.readTagFields(target, signal) }
        return checkNotNull(record.command.tags).values.map { (field, after) -> FileTagDifference(field, actual[field], after) }
    }
    suspend fun items(operationId: String, afterOrdinal: Long? = null, limit: Int = FileOperationLimits.PAGE_SIZE): List<FileOperationItem> = worker { journal.items(operationId, afterOrdinal, limit) }
    suspend fun pending(afterOperationId: String? = null, limit: Int = FileOperationLimits.PAGE_SIZE): List<FileOperationRecord> = worker { journal.pending(afterOperationId, limit) }

    internal suspend fun <T> withExclusiveLibraryMutation(requireNoPending: Boolean, action: suspend () -> T): T = guard.originalMutation {
        if (requireNoPending && worker { journal.pending(limit = 1).isNotEmpty() }) fail(FileOperationIssue.RECOVERY_REQUIRED)
        action()
    }
    internal suspend fun <T> withClosedPlayback(action: suspend (FilePlaybackReservation) -> T): T = playback.withClosedResources(action)
    internal suspend fun clearIndexUndoAfterLibraryReplacement() = worker { journal.clearIndexUndoAfterLibraryReplacement() }

    private suspend fun execute(original: FileOperationRecord): FileOperationProgress = try { executeAccepted(original) }
        catch (failure: CancellationException) {
            withContext(NonCancellable) {
                try {
                    io.awaitIdle()
                    cancelRemaining(original.operationId)
                    finish(original.operationId, cancelled = true)
                    onRecoveryStateChanged()
                } catch (cleanup: Exception) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
            }
            throw failure
        } catch (failure: Exception) {
            withContext(NonCancellable) {
                try {
                    io.awaitIdle()
                    val current = worker { journal.record(original.operationId) }
                    if (current?.phase == FileOperationPhase.CONFIRMED) {
                        visit(original.operationId) { item -> if (item.phase == FileItemPhase.REQUESTED)
                            worker { journal.updateItem(item.copy(phase = FileItemPhase.FAILED, issue = map(failure).issue)) } }
                        worker { journal.updateRecord(current.copy(phase = FileOperationPhase.FAILED)) }
                    } else finish(original.operationId, cancelled = false)
                    onRecoveryStateChanged()
                } catch (cleanup: Exception) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            }
            throw map(failure)
        }

    private suspend fun executeAccepted(original: FileOperationRecord): FileOperationProgress {
        worker { journal.updateRecord(original.copy(phase = FileOperationPhase.RUNNING)) }
        ensureSourcesAvailable(original.operationId)
        if (original.command.kind in INDEX_KINDS) {
            var primaryFailure: Throwable? = null
            try {
                if (original.command.kind == FileOperationKind.REMOVE_SOURCE) {
                    val scope = library.sourceSelection(checkNotNull(original.command.removedSourceId), checkNotNull(original.command.removedSourceAuthEpoch))
                    if (scope.totalCount != original.selection.totalCount || original.phase == FileOperationPhase.CONFIRMED &&
                        scope.source.revision != original.selection.revision) fail(FileOperationIssue.SOURCE_CHANGED)
                    visit(original.operationId) { item -> if (item.phase == FileItemPhase.REQUESTED) verifyFrozenScope(item, original.command) }
                }
                stopScans(original.operationId)
                if (original.command.kind == FileOperationKind.REMOVE_SOURCE) {
                    visit(original.operationId) { item -> if (item.phase == FileItemPhase.REQUESTED) worker { journal.updateItem(item.copy(phase = FileItemPhase.APPLYING)) } }
                    library.removeSource(checkNotNull(original.command.removedSourceId), checkNotNull(original.command.removedSourceAuthEpoch))
                    visit(original.operationId) { item -> worker { journal.updateItem(item.copy(phase = FileItemPhase.INDEX_RECONCILED)) } }
                } else visit(original.operationId) { item -> if (item.phase == FileItemPhase.REQUESTED) reconcileIndex(item, original.command) }
                finish(original.operationId, cancelled = false)
            } catch (failure: CancellationException) {
                primaryFailure = failure
                withContext(NonCancellable) {
                    try {
                        io.awaitIdle()
                        recoverIndex(original)
                        cancelRemaining(original.operationId)
                        finish(original.operationId, cancelled = true)
                    } catch (cleanup: Exception) {
                        if (cleanup !== failure) failure.addSuppressed(cleanup)
                    }
                }
                throw failure
            } catch (failure: Exception) {
                primaryFailure = failure
                withContext(NonCancellable) {
                    try {
                        io.awaitIdle()
                        recoverIndex(original)
                    } catch (cleanup: Exception) {
                        if (cleanup !== failure) failure.addSuppressed(cleanup)
                    }
                    try {
                        val issue = map(failure).issue
                        visit(original.operationId) { item ->
                            if (item.phase in setOf(FileItemPhase.REQUESTED, FileItemPhase.APPLYING)) recordFailure(item, issue)
                        }
                        finish(original.operationId, cancelled = false)
                    } catch (cleanup: Exception) {
                        if (cleanup !== failure) failure.addSuppressed(cleanup)
                    }
                }
                throw map(failure)
            } finally { cleanupPreserving(primaryFailure) { onRecoveryStateChanged() } }
        } else playback.withClosedResources { reservation ->
            var cancelled = false
            var primaryFailure: Throwable? = null
            try {
                stopScans(original.operationId)
                io.awaitIdle()
                visit(original.operationId) { item ->
                    currentCoroutineContext().ensureActive()
                    if (item.phase == FileItemPhase.REQUESTED || item.phase == FileItemPhase.COPY_INDEX_RECONCILED && original.sourceDeletionConfirmed) {
                        try {
                            library.check(item.target, original.command.destination)
                            verifyFrozenScope(item, original.command)
                            val applied = worker { signal -> physical.apply(item, original.command, original.sourceDeletionConfirmed, signal, journal::updateItem) }
                            reconcile(applied, original.command, reservation)
                        } catch (failure: CancellationException) { throw failure }
                        catch (failure: Exception) {
                            val issue = map(failure).issue
                            recordFailure(item, issue)
                            if (issue in STOP_ISSUES) throw FileOperationException(issue, failure)
                        }
                        publish(original.operationId)
                    }
                }
            } catch (failure: CancellationException) {
                cancelled = true
                primaryFailure = failure
                throw failure
            } catch (failure: Exception) {
                primaryFailure = failure
                throw failure
            }
            finally {
                cleanupPreserving(primaryFailure) {
                    // LocalProviderIo 的取消不能提前释放写入或原生资源围栏。
                    io.awaitIdle()
                    reconcileObserved(original, reservation, compensateInterruptedTag = true)
                    if (cancelled) cancelRemaining(original.operationId)
                    finish(original.operationId, cancelled)
                    onRecoveryStateChanged()
                }
            }
        }
        return publish(original.operationId)
    }

    private suspend fun confirmSourceDeletion(record: FileOperationRecord, confirmation: FileOperationConfirmation): FileOperationProgress {
        val shown = worker { journal.sourceDeletionPreview(record.operationId) }
        validateConfirmation(shown, confirmation)
        if (!confirmation.accepted) {
            worker { journal.updateRecord(record.copy(phase = FileOperationPhase.CANCELLED)) }
            onRecoveryStateChanged()
            return publish(record.operationId)
        }
        val next = record.copy(phase = FileOperationPhase.RUNNING, sourceDeletionConfirmed = true)
        worker { journal.updateRecord(next) }
        return execute(next)
    }

    private suspend fun freezeImpacts(operationId: String, target: FileOperationTarget) {
        var after: String? = null
        do {
            val page = library.impacts(target, after)
            requireImpactPage(page, after)
            if (page.rows.isNotEmpty()) worker {
                journal.appendImpacts(operationId, target.unitKey, page.rows)
                val sources = page.rows.mapNotNull { it.sourceId }.distinct()
                if (sources.isNotEmpty()) journal.appendSources(operationId, sources)
            }
            after = page.next
        } while (after != null)
    }

    private suspend fun verifyFrozenScope(item: FileOperationItem, command: FileOperationCommand) {
        val expected = FileImpactCursor { after -> worker { journal.impacts(item.operationId, item.target.unitKey, after) } }
        val observed = FileImpactCursor { after -> library.impacts(item.target, after) }
        var actual = observed.next()
        while (true) {
            val original = expected.next() ?: break
            if (original.kind == FileImpactKind.SOURCE && original.sourceId == command.destination?.sourceId && original.impactKey != actual?.impactKey) continue
            if (actual == null || !sameImpact(original, actual)) fail(FileOperationIssue.INCOMPLETE_SCOPE)
            actual = observed.next()
        }
        if (actual != null) fail(FileOperationIssue.INCOMPLETE_SCOPE)
    }

    private fun sameImpact(original: FileOperationImpact, current: FileOperationImpact): Boolean {
        if (original.copy(source = null) != current.copy(source = null)) return false
        val left = original.source
        val right = current.source
        if (left == null || right == null) return left == right
        // 本所有者取消扫描造成的记账变化不改变授权；身份和授权代次仍须一致。
        return left.spec == right.spec && left.grant == right.grant && left.grantId == right.grantId &&
            left.authEpoch == right.authEpoch && left.state == right.state
    }

    private suspend fun reconcile(item: FileOperationItem, command: FileOperationCommand, reservation: FilePlaybackReservation) {
        if (item.phase in setOf(FileItemPhase.INDEX_RECONCILED, FileItemPhase.COPY_INDEX_RECONCILED, FileItemPhase.CANCELLED)) return
        if (item.phase !in setOf(FileItemPhase.EXTERNAL_APPLIED, FileItemPhase.COPY_VALIDATED)) fail(FileOperationIssue.RECOVERY_REQUIRED)
        val document = item.artifacts.published
        if (document != null) {
            var after: String? = null
            do {
                val page = worker { journal.impacts(item.operationId, item.target.unitKey, after) }
                for (impact in page.rows) if (impact.kind == FileImpactKind.SOURCE) {
                    val source = library.source(checkNotNull(impact.sourceId))
                    val route = worker { signal -> physical.verifyPublishedRoute(source, document, signal) }
                    worker { journal.saveRoute(item.operationId, item.target.unitKey, route) }
                }
                after = page.next
            } while (after != null)
        }
        var current = worker { journal.unit(item.operationId, item.target.unitKey) } ?: fail(FileOperationIssue.RECOVERY_REQUIRED)
        var after = current.reconcileAfter
        do {
            val page = worker { journal.impacts(item.operationId, item.target.unitKey, after) }
            if (page.rows.isNotEmpty()) {
                val sourceIds = (page.rows.mapNotNull { it.sourceId } + listOfNotNull(document?.sourceId)).distinct()
                val routes = worker { sourceIds.mapNotNull { journal.route(item.operationId, item.target.unitKey, it) } }
                library.reconcile(current, command, page.rows, routes)
                after = page.rows.last().impactKey
                current = current.copy(reconcileAfter = after)
                worker { journal.updateItem(current) }
            }
            if (page.next == null) break
        } while (true)
        // 修复完整持久队列，保留实例 ID、顺序、checkpoint 及暂停状态。
        references.reconcile(current, command, reservation)
        current = current.copy(phase = if (item.phase == FileItemPhase.COPY_VALIDATED) FileItemPhase.COPY_INDEX_RECONCILED else FileItemPhase.INDEX_RECONCILED)
        worker { journal.updateItem(current) }
        if (current.phase == FileItemPhase.INDEX_RECONCILED) {
            val retired = worker { signal -> physical.finishArtifacts(current, signal) }
            worker { journal.updateItem(retired) }
        }
    }

    private suspend fun reconcileIndex(item: FileOperationItem, command: FileOperationCommand) {
        worker { journal.updateItem(item.copy(phase = FileItemPhase.APPLYING)) }
        val impacts = worker { journal.impacts(item.operationId, item.target.unitKey).rows }
        library.reconcile(item, command, impacts, emptyList())
        worker {
            journal.saveIndexIgnore(FileIndexIgnore(item.target.trackKey, item.operationId, item.target.unitKey))
            journal.updateItem(item.copy(phase = FileItemPhase.INDEX_RECONCILED))
        }
    }

    private suspend fun reconcileObserved(record: FileOperationRecord, reservation: FilePlaybackReservation, compensateInterruptedTag: Boolean = false) {
        visit(record.operationId) { item ->
            if (item.phase in setOf(FileItemPhase.STAGING, FileItemPhase.VALIDATED, FileItemPhase.APPLYING, FileItemPhase.EXTERNAL_APPLIED,
                    FileItemPhase.COPY_VALIDATED, FileItemPhase.NEEDS_RECOVERY) || item.phase == FileItemPhase.INDEX_RECONCILED && item.artifacts.originalBackup != null) {
                try {
                    var observed = worker { signal -> physical.recover(item, record.command, signal, journal::updateItem) }
                    if (compensateInterruptedTag) observed = worker { signal ->
                        physical.compensateInterruptedTag(observed, record.command, signal, journal::updateItem)
                    }
                    if (observed.phase in setOf(FileItemPhase.EXTERNAL_APPLIED, FileItemPhase.COPY_VALIDATED)) reconcile(observed, record.command, reservation)
                    else if (observed.phase == FileItemPhase.INDEX_RECONCILED && observed.artifacts.originalBackup != null) {
                        val retired = worker { signal -> physical.finishArtifacts(observed, signal) }
                        worker { journal.updateItem(retired) }
                    }
                } catch (failure: Exception) { recordFailure(item, map(failure).issue) }
            }
        }
    }

    private suspend fun recoverIndex(record: FileOperationRecord) {
        if (record.command.kind == FileOperationKind.REMOVE_SOURCE) {
            val source = library.source(checkNotNull(record.command.removedSourceId))
            visit(record.operationId) { item ->
                if (item.phase in setOf(FileItemPhase.APPLYING, FileItemPhase.NEEDS_RECOVERY) ||
                    source.state == LibrarySourceState.REMOVED && item.phase == FileItemPhase.REQUESTED) {
                    val removed = source.state == LibrarySourceState.REMOVED
                    worker {
                        journal.updateItem(item.copy(phase = if (removed) FileItemPhase.INDEX_RECONCILED else FileItemPhase.CANCELLED,
                            issue = if (removed) null else item.issue ?: FileOperationIssue.CANCELLED))
                    }
                }
            }
        } else {
            // 仅已确认且正在协调的索引操作可重写 member=false；
            // 目标行仍固定，此路径不触碰外部字节。
            if (record.phase !in setOf(FileOperationPhase.REQUESTED, FileOperationPhase.PREVIEW)) visit(record.operationId) { item ->
                if (item.phase in setOf(FileItemPhase.APPLYING, FileItemPhase.NEEDS_RECOVERY)) reconcileIndex(item, record.command)
            }
        }
    }

    private suspend fun stopScans(operationId: String) {
        var after: String? = null
        while (true) {
            val page = worker { journal.sources(operationId, after) }
            if (page.isEmpty()) break
            library.cancelScans(page)
            after = page.last()
        }
    }

    private suspend fun ensureSourcesAvailable(operationId: String) {
        var after: String? = null
        while (true) {
            val page = worker { journal.sources(operationId, after) }
            if (page.isEmpty()) break
            for (source in page) if (worker { journal.hasPendingSource(source, operationId) }) fail(FileOperationIssue.RECOVERY_REQUIRED)
            after = page.last()
        }
    }

    private suspend fun recordFailure(item: FileOperationItem, issue: FileOperationIssue) {
        val current = worker { journal.unit(item.operationId, item.target.unitKey) } ?: return
        val phase = when (current.phase) {
            FileItemPhase.REQUESTED -> FileItemPhase.FAILED
            FileItemPhase.COPY_INDEX_RECONCILED -> current.phase
            else -> FileItemPhase.NEEDS_RECOVERY
        }
        worker { journal.updateItem(current.copy(phase = phase, issue = issue)) }
    }

    private suspend fun cancelRemaining(operationId: String) { visit(operationId) { item -> if (item.phase == FileItemPhase.REQUESTED)
        worker { journal.updateItem(item.copy(phase = FileItemPhase.CANCELLED, issue = FileOperationIssue.CANCELLED)) } } }

    private suspend fun finish(operationId: String, cancelled: Boolean) {
        val record = worker { journal.record(operationId) } ?: return
        val counts = worker { journal.progress(operationId) }
        val uncoordinated = worker { journal.hasUncoordinatedWork(operationId) }
        // 空来源也有实际移除结果；不能由零条曲目推定命令成功。
        val sourceNotRemoved = record.command.kind == FileOperationKind.REMOVE_SOURCE &&
            library.source(checkNotNull(record.command.removedSourceId)).state != LibrarySourceState.REMOVED
        val phase = when {
            uncoordinated || counts.needsRecovery > 0L -> FileOperationPhase.NEEDS_RECOVERY
            counts.waitingSourceDeletion > 0L && !record.sourceDeletionConfirmed && !cancelled -> FileOperationPhase.WAITING_SOURCE_CONFIRMATION
            cancelled -> FileOperationPhase.CANCELLED
            sourceNotRemoved -> FileOperationPhase.FAILED
            counts.waitingSourceDeletion > 0L && record.sourceDeletionConfirmed -> FileOperationPhase.PARTIAL
            counts.failed > 0L && counts.completed == 0L && counts.notExecuted == 0L -> FileOperationPhase.FAILED
            counts.failed > 0L || counts.notExecuted > 0L -> FileOperationPhase.PARTIAL
            else -> FileOperationPhase.COMPLETED
        }
        worker { journal.updateRecord(record.copy(phase = phase)) }
        publish(operationId)
    }

    /** 共享原件围栏已取得；同步检查不先占备份 lease，服务仍可完成 checkpoint。 */
    private fun requireMutationReady() {
        try {
            requireFileMutationReady()
        } catch (failure: FileOperationException) {
            throw failure
        } catch (failure: Exception) {
            throw FileOperationException(FileOperationIssue.RECOVERY_REQUIRED, failure)
        }
    }

    /** 必须等待真实收尾；日志 BUSY/FULL 作为 suppressed 保留，不能改写原取消或失败。 */
    private suspend fun cleanupPreserving(primary: Throwable?, action: suspend () -> Unit) = withContext(NonCancellable) {
        try {
            action()
        } catch (cleanup: Throwable) {
            if (primary == null) throw cleanup
            if (cleanup !== primary) primary.addSuppressed(cleanup)
        }
    }

    private suspend fun stopPreparation(operationId: String, phase: FileOperationPhase) {
        io.awaitIdle()
        val record = worker { journal.record(operationId) } ?: return
        if (record.phase == FileOperationPhase.REQUESTED) worker { journal.updateRecord(record.copy(phase = phase)) }
    }

    private suspend fun requireUnoccupiedSource(sourceId: String) { if (worker { journal.hasPendingSource(sourceId) }) fail(FileOperationIssue.RECOVERY_REQUIRED) }
    private fun preview(record: FileOperationRecord): FileOperationPreview = FileOperationPreview(record.operationId, record.command.kind,
        record.previewBinding ?: fail(FileOperationIssue.INCOMPLETE_SCOPE), record.selectedCount, record.physicalCount,
        record.trackImpactCount, record.aliasImpactCount, record.cueImpactCount, record.command.kind !in INDEX_KINDS)
    private fun validateConfirmation(preview: FileOperationPreview, confirmation: FileOperationConfirmation) {
        if (confirmation.operationId != preview.operationId || confirmation.previewBinding != preview.binding || confirmation.kind != preview.kind) {
            fail(FileOperationIssue.CONFIRMATION_REQUIRED)
        }
        if (confirmation.accepted && preview.mayPauseAndClosePlayback && !confirmation.playbackPauseAndCloseAccepted) fail(FileOperationIssue.PLAYBACK_CONFIRMATION_REQUIRED)
        if (confirmation.accepted && preview.mayPauseAndClosePlayback && (preview.affectedAliases > 1L || preview.affectedCueSegments > 0L || preview.affectedTracks > preview.selectedCount) &&
            !confirmation.wholePhysicalFileImpactAccepted) fail(FileOperationIssue.SHARED_FILE_CONFIRMATION_REQUIRED)
    }
    private fun recoveryBinding(item: FileOperationItem, action: FileRecoveryAction): String = fileOperationDigest(
        ("${action.name}:v1:${item.operationId}:${item.target.unitKey}:" + FileOperationCodec.target(item.target) + FileOperationCodec.artifacts(item.artifacts)).toByteArray(Charsets.UTF_8))
    private suspend fun publish(operationId: String): FileOperationProgress = worker { journal.progress(operationId) }.also { mutableProgress.value = it }
    private suspend fun visit(operationId: String, action: suspend (FileOperationItem) -> Unit) {
        var after: Long? = null
        while (true) {
            val page = worker { journal.items(operationId, after) }
            if (page.isEmpty()) break
            for (item in page) action(item)
            after = page.last().ordinal
        }
    }
    private suspend fun <T> worker(action: (CancellationSignal) -> T): T = try { io.read(action = action) }
        catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { throw map(failure) }
    private fun map(failure: Exception): FileOperationException = when (failure) {
        is FileOperationException -> failure
        is LibraryException -> FileOperationException(when (failure.issue) {
            LibraryIssue.BUSY -> FileOperationIssue.BUSY
            LibraryIssue.CONFLICT, LibraryIssue.SOURCE_CHANGED, LibraryIssue.VIEW_EXPIRED -> FileOperationIssue.SOURCE_CHANGED
            LibraryIssue.PERMISSION_REVOKED -> FileOperationIssue.PERMISSION_REVOKED
            LibraryIssue.FILE_MISSING -> FileOperationIssue.FILE_MISSING
            LibraryIssue.STORAGE_FULL -> FileOperationIssue.STORAGE_FULL
            LibraryIssue.STORAGE_UNAVAILABLE -> FileOperationIssue.STORAGE_UNAVAILABLE
            LibraryIssue.INCOMPLETE -> FileOperationIssue.INCOMPLETE_SCOPE
            LibraryIssue.INVALID_INPUT -> FileOperationIssue.INVALID_INPUT
            else -> FileOperationIssue.RECOVERY_REQUIRED
        }, failure)
        is SourceReadException -> FileOperationException(when (failure.issue) {
            SourceReadIssue.BUSY -> FileOperationIssue.BUSY
            SourceReadIssue.PERMISSION_DENIED -> FileOperationIssue.PERMISSION_DENIED
            SourceReadIssue.PERMISSION_REVOKED -> FileOperationIssue.PERMISSION_REVOKED
            SourceReadIssue.STORAGE_FULL -> FileOperationIssue.STORAGE_FULL
            SourceReadIssue.SOURCE_CHANGED -> FileOperationIssue.SOURCE_CHANGED
            SourceReadIssue.FILE_MISSING -> FileOperationIssue.FILE_MISSING
            else -> FileOperationIssue.SOURCE_UNAVAILABLE
        }, failure)
        is IllegalArgumentException -> FileOperationException(FileOperationIssue.INVALID_INPUT, failure)
        else -> FileOperationException(FileOperationIssue.RECOVERY_REQUIRED, failure)
    }
    private fun fail(issue: FileOperationIssue): Nothing = throw FileOperationException(issue)
    private companion object {
        val INDEX_KINDS = setOf(FileOperationKind.REMOVE_FROM_INDEX, FileOperationKind.REMOVE_SOURCE)
        val TERMINAL = setOf(FileOperationPhase.COMPLETED, FileOperationPhase.CANCELLED, FileOperationPhase.FAILED)
        val STOP_ISSUES = setOf(FileOperationIssue.STORAGE_FULL, FileOperationIssue.STORAGE_UNAVAILABLE, FileOperationIssue.BUSY,
            FileOperationIssue.RECOVERY_REQUIRED, FileOperationIssue.PLAYBACK_RELEASE_UNCONFIRMED)
    }
}

private class FileImpactCursor(private val load: suspend (String?) -> FileImpactPage) {
    private var rows: Iterator<FileOperationImpact> = emptyList<FileOperationImpact>().iterator()
    private var after: String? = null
    private var exhausted = false

    suspend fun next(): FileOperationImpact? {
        while (!rows.hasNext()) {
            if (exhausted) return null
            val page = load(after)
            requireImpactPage(page, after)
            rows = page.rows.iterator()
            after = page.next
            exhausted = page.next == null
        }
        return rows.next()
    }
}

private fun requireImpactPage(page: FileImpactPage, after: String?) {
    if (page.rows.size > FileOperationLimits.PAGE_SIZE || page.next != null &&
        (page.rows.isEmpty() || page.next != page.rows.last().impactKey)) throw FileOperationException(FileOperationIssue.INCOMPLETE_SCOPE)
    var previous = after
    for (row in page.rows) {
        if (previous != null && row.impactKey <= previous) throw FileOperationException(FileOperationIssue.INCOMPLETE_SCOPE)
        previous = row.impactKey
    }
}
