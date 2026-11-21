package io.github.onlinemo.meloxwatch.data.transfer

import io.github.onlinemo.meloxwatch.data.imports.LocalImportManager

/** Production audio creation uses the existing import and Room owners, never a synthetic URI. */
class ImportedAudioTransferBackend(private val imports: LocalImportManager) : TransferBackend {
    override suspend fun committed(binding: TransferOperationBinding, permit: TransferPermit): TransferReceipt? =
        imports.committedTransfer(binding, permit)

    override suspend fun commitUpload(binding: TransferOperationBinding, upload: TransferVerifiedUpload,
        permit: TransferPermit): TransferReceipt = imports.importTransferredAudio(binding, upload, permit)

    // These domains retain their full LP-14 scope. The first integration may approve UPLOAD_AUDIO
    // only; it cannot grant a permission whose concrete repository path has not been connected.
    override suspend fun readPage(request: TransferRequest.ReadPage, permit: TransferPermit): TransferCatalogPage = unavailable(permit)
    override suspend fun readDisplay(request: TransferRequest.ReadDisplay, permit: TransferPermit): TransferDisplayState = unavailable(permit)
    override suspend fun editPlaylist(binding: TransferOperationBinding, request: TransferRequest.EditPlaylist,
        permit: TransferPermit): TransferReceipt = unavailable(permit)
    override suspend fun previewDisplay(request: TransferRequest.PreviewDisplay, permit: TransferPermit): TransferDisplayPreview = unavailable(permit)
    override suspend fun applyDisplay(binding: TransferOperationBinding, request: TransferRequest.ApplyDisplay,
        permit: TransferPermit): TransferReceipt = unavailable(permit)

    override suspend fun closePeerViews(peer: TransferFingerprint, sessionId: String) {
        // This backend creates no catalog or preview leases. Server.stop still joins its uploads.
    }

    private fun unavailable(permit: TransferPermit): Nothing {
        permit.checkActive()
        throw TransferException(TransferIssue.PERMISSION_DENIED)
    }
}
