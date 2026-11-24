package io.github.onlinemo.meloxwatch.data.fileoperations

import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.library.LibraryMutationStore
import io.github.onlinemo.meloxwatch.data.library.LibrarySource
import io.github.onlinemo.meloxwatch.data.local.LocalGrantObservation
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.local.SourceReadException
import io.github.onlinemo.meloxwatch.data.local.LocalProviderIo
import io.github.onlinemo.meloxwatch.data.scan.MetadataOutcome
import io.github.onlinemo.meloxwatch.data.scan.ScanCounters
import io.github.onlinemo.meloxwatch.data.scan.ScanCoverage
import io.github.onlinemo.meloxwatch.data.scan.ScanMode
import io.github.onlinemo.meloxwatch.data.scan.ScanPhase
import io.github.onlinemo.meloxwatch.data.scan.ScanState
import io.github.onlinemo.meloxwatch.data.scan.ScanToken
import kotlinx.coroutines.sync.Mutex

/** 原件所有者与扫描/来源门面共享同一围栏，不建立无界等待队列。 */
class FileOperationMutationGuard(private val journal: FileOperationJournal, private val io: LocalProviderIo) {
    private val mutex = Mutex()

    suspend fun <T> originalMutation(action: suspend () -> T): T {
        if (!mutex.tryLock()) throw FileOperationException(FileOperationIssue.BUSY)
        try { return action() } finally { mutex.unlock() }
    }

    internal suspend fun <T> scanMutation(sourceId: String, permitRecoveryGrant: Boolean = false, action: suspend () -> T): T {
        if (!mutex.tryLock()) throw LibraryException(LibraryIssue.BUSY)
        try {
            val pending = if (permitRecoveryGrant) false else try { io.read { journal.hasPendingSource(sourceId) } }
                catch (failure: SourceReadException) {
                    throw LibraryException(if (failure.issue == SourceReadIssue.BUSY) LibraryIssue.BUSY else LibraryIssue.RECOVERY_REQUIRED, failure)
                }
                catch (failure: FileOperationException) { throw LibraryException(LibraryIssue.RECOVERY_REQUIRED, failure) }
            if (pending) {
                throw LibraryException(LibraryIssue.SOURCE_CHANGED)
            }
            return action()
        } finally { mutex.unlock() }
    }
}

/** LocalScanEngine 和来源命令须实际使用此门面，且绑定同一个运行时 guard。 */
class FileOperationGuardedMutationStore(
    private val delegate: LibraryMutationStore,
    private val resolveGuard: suspend () -> FileOperationMutationGuard,
) : LibraryMutationStore by delegate {
    constructor(delegate: LibraryMutationStore, guard: FileOperationMutationGuard) : this(delegate, { guard })
    private suspend fun <T> mutate(sourceId: String, permitRecoveryGrant: Boolean = false, action: suspend () -> T): T =
        resolveGuard().scanMutation(sourceId, permitRecoveryGrant, action)
    override suspend fun registerSource(spec: LocalSourceSpec, grant: LocalGrantObservation): LibrarySource =
        mutate(spec.sourceId) { delegate.registerSource(spec, grant) }
    override suspend fun reauthorizeSource(sourceId: String, expectedAuthEpoch: Long, selectedSpec: LocalSourceSpec,
        grant: LocalGrantObservation): LibrarySource = mutate(sourceId, permitRecoveryGrant = true) {
        delegate.reauthorizeSource(sourceId, expectedAuthEpoch, selectedSpec, grant)
    }
    override suspend fun setSourceIssue(sourceId: String, expectedAuthEpoch: Long, issue: SourceReadIssue): LibrarySource =
        mutate(sourceId, permitRecoveryGrant = true) { delegate.setSourceIssue(sourceId, expectedAuthEpoch, issue) }
    override suspend fun removeSource(sourceId: String, expectedAuthEpoch: Long): LibrarySource =
        mutate(sourceId) { delegate.removeSource(sourceId, expectedAuthEpoch) }
    override suspend fun beginScan(sourceId: String, expectedAuthEpoch: Long, expectedRulesRevision: Long, mode: ScanMode): ScanToken =
        mutate(sourceId) { delegate.beginScan(sourceId, expectedAuthEpoch, expectedRulesRevision, mode) }
    override suspend fun commitSeen(token: ScanToken, candidates: List<ObservedCandidate>) =
        mutate(token.sourceId) { delegate.commitSeen(token, candidates) }
    override suspend fun commitMetadata(token: ScanToken, outcomes: List<MetadataOutcome>) =
        mutate(token.sourceId) { delegate.commitMetadata(token, outcomes) }
    override suspend fun completeDirectory(token: ScanToken, objectId: String, successful: Boolean): ScanState =
        mutate(token.sourceId) { delegate.completeDirectory(token, objectId, successful) }
    override suspend fun setCoverage(token: ScanToken, coverage: ScanCoverage): ScanState =
        mutate(token.sourceId) { delegate.setCoverage(token, coverage) }
    override suspend fun setWatermark(token: ScanToken, watermark: String?): ScanState =
        mutate(token.sourceId) { delegate.setWatermark(token, watermark) }
    override suspend fun stageAbsence(token: ScanToken, afterAliasId: String?, limit: Int) =
        mutate(token.sourceId) { delegate.stageAbsence(token, afterAliasId, limit) }
    override suspend fun finishScan(token: ScanToken, counters: ScanCounters, coverage: ScanCoverage): ScanState =
        mutate(token.sourceId) { delegate.finishScan(token, counters, coverage) }
    override suspend fun setScanState(token: ScanToken, phase: ScanPhase, issue: SourceReadIssue?): ScanState =
        if (phase in setOf(ScanPhase.CANCELLED, ScanPhase.FAILED, ScanPhase.PAUSED_BACKGROUND, ScanPhase.STOPPING)) {
            delegate.setScanState(token, phase, issue)
        } else mutate(token.sourceId) { delegate.setScanState(token, phase, issue) }
}
