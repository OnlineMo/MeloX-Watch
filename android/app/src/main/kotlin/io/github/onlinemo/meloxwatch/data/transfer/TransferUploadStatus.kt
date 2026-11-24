package io.github.onlinemo.meloxwatch.data.transfer

enum class TransferUploadPhase {
    PREPARING, RECEIVING, VERIFYING, COMMITTING, RETIRING,
    COMPLETED, CANCELLED, FAILED, RECOVERY_REQUIRED,
}

/**
 * Local UI state for the sole upload or its last result. It is never a log/analytics payload.
 * receivedBytes counts complete physically written blocks; null means no measured count.
 * Replays use the actual persisted receipt length, never a fabricated zero or a fresh upload.
 * COMPLETED requires both a real durable receipt and physical removal of the transfer partial.
 * RECOVERY_REQUIRED may contain a committed receipt when only cleanup failed; keep that result.
 */
class TransferUploadStatus internal constructor(
    val sessionId: String,
    val binding: TransferOperationBinding,
    val declaration: TransferUploadDeclaration,
    val phase: TransferUploadPhase,
    val receivedBytes: Long?,
    val receipt: TransferReceipt?,
    val replay: Boolean,
    val issue: TransferIssue?,
) {
    override fun toString(): String = "<local-upload-status>"
}
