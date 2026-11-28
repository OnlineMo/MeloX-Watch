package io.github.onlinemo.meloxwatch.data.transfer

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class TransferProcessedResponse(val response: TransferResponse, val checkActive: () -> Unit)

/** One TLS connection, one sequential request loop. A disconnected upload restarts from byte zero. */
class TransferRequestProcessor(
    private val peer: TransferFingerprint,
    private val authority: TransferSessionAuthority,
    private val store: TransferUploadStore,
    private val backend: TransferBackend,
    private val clock: TransferClock = TransferClock { System.nanoTime() / 1_000_000L },
) {
    private var stage: TransferUploadStage? = null
    private var rateWindow = clock.monotonicMs()
    private var controls = 0
    private var chunks = 0

    suspend fun process(request: TransferRequest): TransferProcessedResponse {
        checkRate(request)
        return when (request) {
            is TransferRequest.Pair -> {
                authority.pair(peer, request)
                TransferProcessedResponse(TransferResponse.PairPending(request.requestId)) { checkOpening(request.target, request.sessionId) }
            }
            is TransferRequest.Authenticate -> {
                val authentication = authority.authenticate(peer, request.target, request.sessionId)
                TransferProcessedResponse(TransferResponse.Authenticated(request.requestId, authentication.bearer, authentication.capabilities, authentication.remainingMs)) {
                    authority.authorize(peer, TransferScope(request.target, request.sessionId, authentication.bearer), authentication.capabilities.first()).checkActive()
                }
            }
            is TransferRequest.Authorized -> processAuthorized(request)
        }
    }

    private suspend fun processAuthorized(request: TransferRequest.Authorized): TransferProcessedResponse {
        val allowed = when (request) {
            is TransferRequest.ReadPage -> catalogCapabilities(request.kind)
            is TransferRequest.CloseCatalogView -> catalogCapabilities(request.kind)
            is TransferRequest.ReadReceipt -> setOf(request.kind.capability)
            is TransferRequest.EditPlaylist -> setOf(TransferCapability.WRITE_PLAYLISTS)
            is TransferRequest.ReadDisplay, is TransferRequest.PreviewDisplay, is TransferRequest.ApplyDisplay ->
                setOf(TransferCapability.WRITE_DISPLAY_METADATA)
            is TransferRequest.BeginUpload -> setOf(request.declaration.kind.capability)
            is TransferRequest.UploadChunk, is TransferRequest.FinishUpload, is TransferRequest.CancelUpload ->
                setOf(stage?.declaration?.kind?.capability ?: TransferCapability.UPLOAD_AUDIO)
        }
        val permit = authority.authorizeOneOf(peer, request.scope, allowed)
        val response = try { when (request) {
            is TransferRequest.ReadReceipt -> {
                val binding = TransferOperationBinding(request.operationId, request.kind, permit.target, peer, request.payloadHash)
                val receipt = backend.readReceipt(binding, permit)
                receipt?.let { validateReceipt(binding, it) }
                TransferResponse.ReceiptState(request.requestId, receipt)
            }
            is TransferRequest.CloseCatalogView -> {
                backend.closeCatalogView(request, permit)
                TransferResponse.Cancelled(request.requestId)
            }
            is TransferRequest.ReadPage -> {
                val page = backend.readPage(request, permit)
                if (page.kind != request.kind || page.rows.size > request.limit || request.viewId != null && (page.viewId != request.viewId || page.revision != request.expectedRevision || !page.libraryRevision.sameAs(checkNotNull(request.expectedLibraryRevision)))) throw TransferException(TransferIssue.CONFLICT)
                TransferResponse.Page(request.requestId, page)
            }
            is TransferRequest.ReadDisplay -> {
                val actual = backend.readDisplay(request, permit)
                if (actual.kind != request.kind || actual.entityId != request.entityId) throw TransferException(TransferIssue.CONFLICT)
                TransferResponse.DisplayState(request.requestId, actual)
            }
            is TransferRequest.EditPlaylist -> {
                if (request.edit is TransferPlaylistEdit.Delete && !request.edit.confirmed || request.edit is TransferPlaylistEdit.Remove && !request.edit.confirmed) throw TransferException(TransferIssue.INVALID_INPUT)
                val binding = TransferProtocolCodec.operationBinding(peer, request)
                val previous = backend.committed(binding, permit)
                val receipt = previous ?: backend.editPlaylist(binding, request, permit)
                validateReceipt(binding, receipt)
                TransferResponse.Completed(request.requestId, receipt, previous != null)
            }
            is TransferRequest.PreviewDisplay -> {
                val preview = backend.previewDisplay(request, permit)
                if (preview.entityId != request.entityId || preview.kind != request.kind || preview.revision != request.expectedRevision ||
                    !preview.libraryRevision.sameAs(request.expectedLibraryRevision) || !preview.patch.sameAs(request.patch)) {
                    throw TransferException(TransferIssue.CONFLICT)
                }
                TransferResponse.DisplayPreview(request.requestId, preview)
            }
            is TransferRequest.ApplyDisplay -> {
                if (!request.confirmed) throw TransferException(TransferIssue.INVALID_INPUT)
                val binding = TransferProtocolCodec.operationBinding(peer, request)
                val previous = backend.committed(binding, permit)
                val receipt = previous ?: backend.applyDisplay(binding, request, permit)
                validateReceipt(binding, receipt)
                TransferResponse.Completed(request.requestId, receipt, previous != null)
            }
            is TransferRequest.BeginUpload -> {
                if (stage != null) throw TransferException(TransferIssue.BUSY)
                val binding = TransferProtocolCodec.operationBinding(peer, request)
                val previous = backend.committed(binding, permit)
                if (previous != null) {
                    validateReceipt(binding, previous)
                    store.recordReplay(permit, request.declaration, previous)
                    TransferResponse.Completed(request.requestId, previous, true)
                }
                else {
                    val opened = store.begin(binding, request.declaration, permit)
                    stage = opened
                    TransferResponse.UploadReady(request.requestId, opened.ticket)
                }
            }
            is TransferRequest.UploadChunk -> {
                val current = requireStage(request.ticket)
                TransferResponse.ChunkAccepted(request.requestId, current.ticket, current.append(permit, request.offset, request.bytes))
            }
            is TransferRequest.FinishUpload -> {
                val current = requireStage(request.ticket)
                val previous = backend.committed(current.binding, permit)
                val receipt = if (previous != null) previous else {
                    val verified = current.verify(permit)
                    current.markCommitting()
                    backend.commitUpload(current.binding, verified, permit)
                }
                validateReceipt(current.binding, receipt)
                current.recordCommitted(receipt, previous != null)
                retireStage()
                TransferResponse.Completed(request.requestId, receipt, previous != null)
            }
            is TransferRequest.CancelUpload -> { requireStage(request.ticket)
                    retireStage(TransferIssue.CLOSED)
                    TransferResponse.Cancelled(request.requestId) }
        } } catch (error: TransferException) {
            if (stage?.phase == TransferUploadPhase.COMMITTING || error.issue in setOf(TransferIssue.INTEGRITY_FAILED, TransferIssue.NOT_AUDIO, TransferIssue.UNSUPPORTED_TYPE, TransferIssue.STORAGE_FULL, TransferIssue.QUOTA_EXCEEDED, TransferIssue.STORAGE_UNAVAILABLE, TransferIssue.INTERRUPTED, TransferIssue.RECOVERY_REQUIRED)) retireStage(error.issue, cancelled = false)
            throw error
        }
        permit.checkActive()
        return TransferProcessedResponse(response, permit::checkActive)
    }

    suspend fun close() { retireStage() }

    private fun catalogCapabilities(kind: TransferCatalogKind): Set<TransferCapability> = when (kind) {
        TransferCatalogKind.TRACKS -> setOf(TransferCapability.READ_LIBRARY)
        TransferCatalogKind.PLAYLISTS, TransferCatalogKind.PLAYLIST_MEMBERS ->
            setOf(TransferCapability.READ_LIBRARY, TransferCapability.WRITE_PLAYLISTS)
        TransferCatalogKind.ALBUMS, TransferCatalogKind.ARTISTS,
        TransferCatalogKind.ALBUM_TRACKS, TransferCatalogKind.ARTIST_TRACKS ->
            setOf(TransferCapability.READ_LIBRARY, TransferCapability.WRITE_DISPLAY_METADATA)
    }

    private suspend fun retireStage(issue: TransferIssue = TransferIssue.INTERRUPTED, cancelled: Boolean = true) = withContext(NonCancellable) {
        val current = stage
        if (current != null) { current.discard(issue, cancelled)
            stage = null }
    }

    private fun requireStage(ticket: String): TransferUploadStage {
        val current = stage ?: throw TransferException(TransferIssue.NOT_FOUND)
        if (current.ticket != ticket) throw TransferException(TransferIssue.NOT_FOUND)
        return current
    }

    private fun validateReceipt(binding: TransferOperationBinding, receipt: TransferReceipt) {
        if (!receipt.binding.sameOperation(binding)) throw TransferException(TransferIssue.CONFLICT)
        val expected = when (binding.kind) {
            TransferOperationKind.AUDIO -> TransferReceiptKind.AUDIO_COMMITTED
            TransferOperationKind.THEME -> TransferReceiptKind.THEME_PENDING_CONFIRMATION
            TransferOperationKind.PLAYLIST -> TransferReceiptKind.PLAYLIST_COMMITTED
            TransferOperationKind.DISPLAY -> TransferReceiptKind.DISPLAY_COMMITTED
        }
        if (receipt.result != expected || expected in setOf(TransferReceiptKind.AUDIO_COMMITTED, TransferReceiptKind.THEME_PENDING_CONFIRMATION) && receipt.resultId == null) throw TransferException(TransferIssue.CONFLICT)
    }

    private fun checkOpening(target: TransferFingerprint, sessionId: String) {
        val opening = authority.currentOpening()
        if (opening.target != target || opening.sessionId != sessionId) throw TransferException(TransferIssue.WRONG_TARGET)
    }

    private fun checkRate(request: TransferRequest) {
        val now = clock.monotonicMs()
        if (now - rateWindow >= 1000L) { rateWindow = now
            controls = 0
            chunks = 0 }
        if (request is TransferRequest.UploadChunk) {
            if (++chunks > TransferLimits.CHUNKS_PER_SECOND) throw TransferException(TransferIssue.RATE_LIMITED)
        } else if (++controls > TransferLimits.CONTROLS_PER_SECOND) throw TransferException(TransferIssue.RATE_LIMITED)
    }
}
