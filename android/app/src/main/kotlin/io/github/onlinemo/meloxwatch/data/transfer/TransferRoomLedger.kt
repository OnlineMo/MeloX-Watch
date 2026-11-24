package io.github.onlinemo.meloxwatch.data.transfer

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteFullException
import androidx.sqlite.SQLiteException
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.database.hasCurrentWriterTransaction
import io.github.onlinemo.meloxwatch.data.database.toPlatformSQLiteException

/**
 * Participate in the real owner's short Room commit. Binding a pending operation does not
 * publish a result. The owner claims its permit immediately before its durable mutation,
 * then records the original receipt in that same transaction.
 */
class TransferRoomLedger(private val database: MeloXDatabase) {
    private val dao = database.transferRoomDao()

    suspend fun bindInCurrentTransaction(binding: TransferOperationBinding, permit: TransferPermit) = storage {
        requireTransaction()
        checkPermit(binding, permit)
        val proposed = binding.record()
        dao.insertOperation(proposed)
        requireBinding(dao.operation(binding.operationId) ?: fail(TransferIssue.RECOVERY_REQUIRED), binding)
    }

    suspend fun committedInCurrentTransaction(binding: TransferOperationBinding, permit: TransferPermit): TransferReceipt? = storage {
        requireTransaction()
        checkPermit(binding, permit)
        val row = dao.operation(binding.operationId) ?: return@storage null
        requireBinding(row, binding)
        row.receipt(binding)
    }

    suspend fun recordInCurrentTransaction(receipt: TransferReceipt, claim: TransferCommitClaim): TransferReceipt = storage {
        requireTransaction()
        if (!claim.binding.sameOperation(receipt.binding)) fail(TransferIssue.CONFLICT)
        if (receipt.resultId == null) fail(TransferIssue.RECOVERY_REQUIRED)
        val row = dao.operation(receipt.binding.operationId) ?: fail(TransferIssue.RECOVERY_REQUIRED)
        requireBinding(row, receipt.binding)
        row.receipt(receipt.binding)?.let {
            if (!sameReceipt(it, receipt)) fail(TransferIssue.CONFLICT)
            return@storage it
        }
        if (dao.updateOperation(row.copy(resultKind = receipt.result.wire, appliedRevision = receipt.revision,
                resultId = receipt.resultId, actualBytes = receipt.actualBytes)) != 1) fail(TransferIssue.RECOVERY_REQUIRED)
        receipt
    }

    /** A delegated owner may have finished its already claimed commit after revocation. */
    internal suspend fun requireRecordedInCurrentTransaction(receipt: TransferReceipt): TransferReceipt = storage {
        requireTransaction()
        val row = dao.operation(receipt.binding.operationId) ?: fail(TransferIssue.RECOVERY_REQUIRED)
        requireBinding(row, receipt.binding)
        val actual = row.receipt(receipt.binding) ?: fail(TransferIssue.RECOVERY_REQUIRED)
        if (!sameReceipt(actual, receipt)) fail(TransferIssue.RECOVERY_REQUIRED)
        actual
    }

    private suspend fun requireTransaction() {
        if (!database.hasCurrentWriterTransaction()) fail(TransferIssue.RECOVERY_REQUIRED)
    }

    private fun checkPermit(binding: TransferOperationBinding, permit: TransferPermit) {
        permit.checkActive()
        if (binding.target != permit.target || binding.peer != permit.peer || binding.kind.capability != permit.capability) {
            fail(TransferIssue.WRONG_TARGET)
        }
    }

    private fun requireBinding(row: TransferRoomOperationEntity, binding: TransferOperationBinding) {
        if (row.protocolSchema !in 1..TransferLimits.SCHEMA) fail(TransferIssue.RECOVERY_REQUIRED)
        if (row.operationId != binding.operationId || row.operationKind != binding.kind.wire ||
            row.targetFingerprint != binding.target.forLocalDisplay() || row.peerFingerprint != binding.peer.forLocalDisplay() ||
            row.payloadHash != binding.payloadHash.forLocalStorage()) fail(TransferIssue.CONFLICT)
    }

    private fun TransferOperationBinding.record() = TransferRoomOperationEntity(operationId, TransferLimits.SCHEMA,
        kind.wire, target.forLocalDisplay(), peer.forLocalDisplay(), payloadHash.forLocalStorage(), null, null, null, null)

    private fun TransferRoomOperationEntity.receipt(binding: TransferOperationBinding): TransferReceipt? {
        if (resultKind == null) {
            if (appliedRevision != null || resultId != null || actualBytes != null) fail(TransferIssue.RECOVERY_REQUIRED)
            return null
        }
        val result = TransferReceiptKind.entries.firstOrNull { it.wire == resultKind } ?: fail(TransferIssue.RECOVERY_REQUIRED)
        return try {
            TransferReceipt(binding, result, appliedRevision ?: fail(TransferIssue.RECOVERY_REQUIRED),
                resultId ?: fail(TransferIssue.RECOVERY_REQUIRED), actualBytes)
        } catch (_: IllegalArgumentException) { fail(TransferIssue.RECOVERY_REQUIRED) }
    }

    private fun sameReceipt(first: TransferReceipt, second: TransferReceipt) = first.binding.sameOperation(second.binding) &&
        first.result == second.result && first.revision == second.revision && first.resultId == second.resultId && first.actualBytes == second.actualBytes

    // These writes contain validated internal rows. A constraint failure is receipt/storage
    // recovery, not malformed peer input; catch it before the generic library error mapping.
    private suspend fun <T> storage(action: suspend () -> T): T = try { action() }
    catch (failure: SQLiteException) {
        val platform = failure.toPlatformSQLiteException()
        val issue = when (platform) {
            is SQLiteFullException -> TransferIssue.STORAGE_FULL
            is SQLiteConstraintException -> TransferIssue.RECOVERY_REQUIRED
            else -> TransferIssue.STORAGE_UNAVAILABLE
        }
        throw TransferException(issue).apply { initCause(platform) }
    }

    private fun fail(issue: TransferIssue): Nothing = throw TransferException(issue)
}
