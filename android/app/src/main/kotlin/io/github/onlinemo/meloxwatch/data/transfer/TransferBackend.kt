package io.github.onlinemo.meloxwatch.data.transfer

import android.os.CancellationSignal
import java.io.File

/**
 * The Android integration owns Room, original import phases and content/preview validation.
 * All calls are main-safe. A receipt is returned only after the real durable operation commits.
 * Replays compare every binding field, including target/peer/kind/hash, in the same ledger.
 */
interface TransferBackend {
    suspend fun committed(binding: TransferOperationBinding, permit: TransferPermit): TransferReceipt?
    /** Read only an existing canonical completion; missing/pending operations cannot resume uploads. */
    suspend fun readReceipt(binding: TransferOperationBinding, permit: TransferPermit): TransferReceipt? {
        throw TransferException(TransferIssue.UNSUPPORTED_TYPE)
    }
    suspend fun readPage(request: TransferRequest.ReadPage, permit: TransferPermit): TransferCatalogPage
    /** An exact logical view handoff; failure retains its slot and cannot authorize a fresh view. */
    suspend fun closeCatalogView(request: TransferRequest.CloseCatalogView, permit: TransferPermit) {
        throw TransferException(TransferIssue.UNSUPPORTED_TYPE)
    }
    suspend fun readDisplay(request: TransferRequest.ReadDisplay, permit: TransferPermit): TransferDisplayState
    suspend fun editPlaylist(binding: TransferOperationBinding, request: TransferRequest.EditPlaylist, permit: TransferPermit): TransferReceipt
    suspend fun previewDisplay(request: TransferRequest.PreviewDisplay, permit: TransferPermit): TransferDisplayPreview
    suspend fun applyDisplay(binding: TransferOperationBinding, request: TransferRequest.ApplyDisplay, permit: TransferPermit): TransferReceipt

    /**
     * Audio must pass actual metadata/decoder validation before the import ledger publishes it.
     * A theme must pass LP-12 validation and create a preview; this method cannot apply it.
     * Use upload.readOnPhysicalWorker for validation/copy, then the existing staged Room commit.
     * Recheck/claim the permit in the real transaction, not merely in this caller.
     * Cancellation after installation must retain the installed recovery record and file.
     */
    suspend fun commitUpload(binding: TransferOperationBinding, upload: TransferVerifiedUpload, permit: TransferPermit): TransferReceipt

    /** Release only this peer/session's bounded leases; never clear music, queues or other views. */
    suspend fun closePeerViews(peer: TransferFingerprint, sessionId: String)

    /** Socket retirement clears ephemeral confirmation tickets, while logical catalog views remain. */
    suspend fun closePeerPreviews(peer: TransferFingerprint, sessionId: String) = Unit
}

/** Other reservations exclude this transfer's own active .partial file. Never substitute unknown with zero. */
class TransferQuotaUsage(
    val ownedAudioBytes: Long,
    val otherReservedAudioBytes: Long,
    val ownedThemeBytes: Long,
    val otherReservedThemeBytes: Long,
) {
    init { require(listOf(ownedAudioBytes, otherReservedAudioBytes, ownedThemeBytes, otherReservedThemeBytes).all { it >= 0L }) }
}

interface TransferQuotaAccounting {
    /**
     * Atomically reserve this total, idempotently by ticket, with the same coordinator used by
     * SAF import/theme install. Returned usage excludes this ticket but includes all others.
     * Unknown length grows the reservation per block; known length reserves its full declaration.
     */
    suspend fun reserve(ticket: String, kind: TransferUploadKind, totalBytes: Long): TransferQuotaUsage
    /** Aggregate unwritten reservations are checked against real space on the physical worker. */
    fun requirePhysicalRoom(ticket: String, additionalBytes: Long, availableBytes: Long) {}
    /** Written bytes become real occupancy only after the worker confirms the complete write. */
    fun recordPhysicalBytes(ticket: String, totalBytes: Long) {}
    /** Release only after the physical .partial is removed; installation has its own durable accounting. */
    suspend fun release(ticket: String)
}

fun interface TransferContentValidator {
    /** Called on the one existing physical provider worker. No nested LocalProviderIo, Room or network. */
    fun validate(file: File, declaration: TransferUploadDeclaration, signal: CancellationSignal)
}
