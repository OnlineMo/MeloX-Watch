package io.github.onlinemo.meloxwatch.data.imports

import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.local.LocalGrantObservation
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.transfer.TransferException
import io.github.onlinemo.meloxwatch.data.transfer.TransferCommitClaim
import io.github.onlinemo.meloxwatch.data.transfer.TransferIssue
import io.github.onlinemo.meloxwatch.data.transfer.TransferOperationBinding
import io.github.onlinemo.meloxwatch.data.transfer.TransferOperationKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferPermit
import io.github.onlinemo.meloxwatch.data.transfer.TransferReceipt
import io.github.onlinemo.meloxwatch.data.transfer.TransferReceiptKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferUploadDeclaration
import io.github.onlinemo.meloxwatch.data.transfer.TransferUploadKind
import io.github.onlinemo.meloxwatch.model.TrackKey

/** A real authenticated private input, independent of a SAF selection or URI grant. */
class TransferImportSelection(val binding: TransferOperationBinding, val declaration: TransferUploadDeclaration) {
    init {
        if (binding.kind != TransferOperationKind.AUDIO || declaration.kind != TransferUploadKind.AUDIO) {
            throw TransferException(TransferIssue.UNSUPPORTED_TYPE)
        }
        // This operation creates a new immutable object; it never replaces an existing revision.
        if (declaration.expectedRevision != 0L) throw TransferException(TransferIssue.CONFLICT)
    }
    val operationId: String get() = binding.operationId
    val fileToken: String get() = operationId

    fun sameAs(other: TransferImportSelection): Boolean = binding.sameOperation(other.binding) &&
        declaration.kind == other.declaration.kind && declaration.displayName == other.declaration.displayName &&
        declaration.mediaType == other.declaration.mediaType && declaration.declaredBytes == other.declaration.declaredBytes &&
        declaration.sha256 == other.declaration.sha256 && declaration.expectedRevision == other.declaration.expectedRevision

    override fun toString(): String = "<private-transfer-selection>"
}

class TransferImportRecord(
    val selection: TransferImportSelection,
    val phase: ImportPhase,
    val actualBytes: Long = 0L,
    val sha256: String? = null,
    val metadata: LocalAudioMetadata? = null,
    val issue: TransferIssue? = null,
    val trackKey: TrackKey? = null,
    val committedRevision: Long? = null,
) {
    init {
        require(actualBytes in 0..ImportLimits.QUOTA_BYTES)
        require(sha256 == null || sha256.matches(Regex("[0-9a-f]{64}")))
        if (phase in setOf(ImportPhase.VERIFIED, ImportPhase.INSTALLED, ImportPhase.COMMITTED)) {
            require(actualBytes > 0L && sha256 == selection.declaration.sha256.forLocalStorage() && metadata != null)
            require(selection.declaration.declaredBytes == null || actualBytes == selection.declaration.declaredBytes)
        }
        require((phase == ImportPhase.COMMITTED) == (trackKey != null && committedRevision != null))
        require(committedRevision == null || committedRevision > 0L)
    }

    fun receipt(): TransferReceipt? = if (phase != ImportPhase.COMMITTED) null else TransferReceipt(
        selection.binding, TransferReceiptKind.AUDIO_COMMITTED, requireNotNull(committedRevision),
        requireNotNull(trackKey).trackId, actualBytes,
    )

    override fun toString(): String = "<private-transfer-import-record>"
}

class TransferImportRecoveryPage(val records: List<TransferImportRecord>, val nextOperationId: String?)

/**
 * The same import/library owner persists this separate origin. No credentials or path from a peer
 * are stored. File operations remain outside Room; claimCommit occurs in commitTransferred's real
 * transaction. Startup may retain installed input, but cannot publish it using an expired session.
 */
interface TransferImportLedger {
    suspend fun recordTransferred(operationId: String): TransferImportRecord?
    /** 只核验 canonical 收据；历史 COMMITTED 行不能单独证明操作完成。 */
    suspend fun requireCommittedTransferred(selection: TransferImportSelection): TransferReceipt
    suspend fun beginTransferred(selection: TransferImportSelection): TransferImportRecord
    /** Caller has physically removed old staging and proved that no installed object exists. */
    suspend fun restartTransferred(selection: TransferImportSelection): TransferImportRecord
    suspend fun verifyTransferred(selection: TransferImportSelection, actualBytes: Long, sha256: String,
        metadata: LocalAudioMetadata): TransferImportRecord
    suspend fun installedTransferred(selection: TransferImportSelection): TransferImportRecord
    suspend fun commitTransferred(selection: TransferImportSelection, permit: TransferPermit,
        ownedSpec: LocalSourceSpec, grant: LocalGrantObservation, ownedCandidate: ObservedCandidate,
        commitBoundary: () -> TransferCommitClaim): TransferImportRecord
    suspend fun unfinishedTransferred(selection: TransferImportSelection, phase: ImportPhase,
        issue: TransferIssue): TransferImportRecord
    suspend fun pendingTransferred(afterOperationId: String? = null,
        limit: Int = ImportLimits.RECOVERY_PAGE_SIZE): TransferImportRecoveryPage
}
