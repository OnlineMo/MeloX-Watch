package io.github.onlinemo.meloxwatch.data.fileoperations

import io.github.onlinemo.meloxwatch.data.cue.CueGroupCommit
import io.github.onlinemo.meloxwatch.data.cue.CueIssue
import io.github.onlinemo.meloxwatch.data.cue.CuePlaybackRead
import io.github.onlinemo.meloxwatch.data.cue.CueScanContext
import io.github.onlinemo.meloxwatch.data.cue.CueScanStore
import io.github.onlinemo.meloxwatch.data.cue.PreparedCueGroup
import io.github.onlinemo.meloxwatch.data.library.LibraryTrack
import io.github.onlinemo.meloxwatch.data.library.LibraryViewToken
import io.github.onlinemo.meloxwatch.model.CuePcmWaveFacts
import io.github.onlinemo.meloxwatch.model.LocalCueReference
import io.github.onlinemo.meloxwatch.model.TrackKey
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

/** 原件所有者与扫描/来源门面共享同一围栏，不建立无界等待队列。 */
class FileOperationMutationGuard(private val journal: FileOperationJournal, private val io: LocalProviderIo) {
    private val mutex = Mutex()

    suspend fun <T> originalMutation(action: suspend () -> T): T {
        if (!mutex.tryLock()) throw FileOperationException(FileOperationIssue.BUSY)
        try { return action() } finally { mutex.unlock() }
    }

    /**
     * 失败结清只更新已接纳的 CUE frontier、错误与计数，不激活段组或改来源。
     * 同一原件 mutex 仍排斥并行原件写入；Room 在原恢复 barrier 和真实 writer 中
     * 重核 scanId/objectId、source/authEpoch/rules 围栏与 CUE_RUNNING 状态。
     * 这里不再读取文件日志，避免仍在物理退休的同一 IO 槽把原失败换成 BUSY。
     */
    internal suspend fun finalizeAcceptedCueFailure(
        token: ScanToken,
        objectId: String,
        issue: CueIssue,
        store: CueScanStore,
    ): ScanState {
        currentCoroutineContext().ensureActive()
        if (!mutex.tryLock()) throw LibraryException(LibraryIssue.BUSY)
        try {
            currentCoroutineContext().ensureActive()
            return store.completeCueDocument(token, objectId, issue)
        } finally {
            mutex.unlock()
        }
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
) : LibraryMutationStore by delegate, CueScanStore {
    constructor(delegate: LibraryMutationStore, guard: FileOperationMutationGuard) : this(delegate, { guard })
    private val cues: CueScanStore get() = delegate as? CueScanStore ?: throw LibraryException(LibraryIssue.SOURCE_UNAVAILABLE)
    override suspend fun nextCueDocument(token: ScanToken): ObservedCandidate? =
        mutate(token.sourceId) { cues.nextCueDocument(token) }
    override suspend fun cueScanContext(token: ScanToken, candidate: ObservedCandidate): CueScanContext =
        mutate(token.sourceId) { cues.cueScanContext(token, candidate) }
    override suspend fun cueAudioDescriptor(token: ScanToken, candidate: ObservedCandidate) =
        mutate(token.sourceId) { cues.cueAudioDescriptor(token, candidate) }
    override suspend fun commitCueGroup(token: ScanToken, prepared: PreparedCueGroup, pcmFacts: Map<Int, CuePcmWaveFacts>): CueGroupCommit =
        mutate(token.sourceId) { cues.commitCueGroup(token, prepared, pcmFacts) }
    override suspend fun completeCueDocument(token: ScanToken, objectId: String, issue: CueIssue?): ScanState {
        if (issue == null) {
            return mutate(token.sourceId) { cues.completeCueDocument(token, objectId, null) }
        }
        return resolveGuard().finalizeAcceptedCueFailure(token, objectId, issue, cues)
    }
    override suspend fun resolveCueRead(trackKey: TrackKey, expected: LocalCueReference?): CuePlaybackRead = cues.resolveCueRead(trackKey, expected)
    override suspend fun cueRelativeMatches(view: LibraryViewToken, rootNodeId: String, components: List<String>, trackNumber: Int): List<TrackKey> =
        cues.cueRelativeMatches(view, rootNodeId, components, trackNumber)
    override suspend fun cueRelativePath(view: LibraryViewToken, track: LibraryTrack): String? = cues.cueRelativePath(view, track)
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
