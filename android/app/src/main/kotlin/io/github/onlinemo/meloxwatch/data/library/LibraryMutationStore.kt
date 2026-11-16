package io.github.onlinemo.meloxwatch.data.library

import io.github.onlinemo.meloxwatch.data.local.LocalGrantObservation
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.SourceReadIssue
import io.github.onlinemo.meloxwatch.data.scan.MetadataOutcome
import io.github.onlinemo.meloxwatch.data.scan.ScanCounters
import io.github.onlinemo.meloxwatch.data.scan.ScanCoverage
import io.github.onlinemo.meloxwatch.data.scan.ScanMode
import io.github.onlinemo.meloxwatch.data.scan.ScanPhase
import io.github.onlinemo.meloxwatch.data.scan.ScanState
import io.github.onlinemo.meloxwatch.data.scan.ScanToken
import kotlinx.coroutines.flow.Flow

/** The caller owns provider I/O. Every write rechecks the source, authorization and rules fences. */
interface LibraryMutationStore {
    suspend fun registerSource(spec: LocalSourceSpec, grant: LocalGrantObservation): LibrarySource
    suspend fun reauthorizeSource(sourceId: String, expectedAuthEpoch: Long, selectedSpec: LocalSourceSpec, grant: LocalGrantObservation): LibrarySource
    suspend fun setSourceIssue(sourceId: String, expectedAuthEpoch: Long, issue: SourceReadIssue): LibrarySource
    suspend fun removeSource(sourceId: String, expectedAuthEpoch: Long): LibrarySource
    suspend fun beginScan(sourceId: String, expectedAuthEpoch: Long, expectedRulesRevision: Long, mode: ScanMode): ScanToken
    suspend fun commitSeen(token: ScanToken, candidates: List<ObservedCandidate>): SeenBatchCommit
    suspend fun commitMetadata(token: ScanToken, outcomes: List<MetadataOutcome>): MetadataBatchCommit
    suspend fun nextDirectory(token: ScanToken): ObservedCandidate?
    suspend fun completeDirectory(token: ScanToken, objectId: String, successful: Boolean): ScanState
    suspend fun isExcluded(token: ScanToken, objectId: String, parentObjectId: String? = null): Boolean
    suspend fun setCoverage(token: ScanToken, coverage: ScanCoverage): ScanState
    suspend fun setWatermark(token: ScanToken, watermark: String?): ScanState
    suspend fun stageAbsence(token: ScanToken, afterAliasId: String? = null, limit: Int = LibraryLimits.PAGE_SIZE): AbsencePage
    suspend fun finishScan(token: ScanToken, counters: ScanCounters, coverage: ScanCoverage): ScanState
    suspend fun setScanState(token: ScanToken, phase: ScanPhase, issue: SourceReadIssue? = null): ScanState
    suspend fun latestScan(sourceId: String): ScanState?
    fun observeScan(scanId: String): Flow<ScanState?>
}
