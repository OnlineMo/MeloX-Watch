package io.github.onlinemo.meloxwatch.companion.transfer

import io.github.onlinemo.meloxwatch.data.transfer.TransferCatalogPage
import io.github.onlinemo.meloxwatch.data.transfer.TransferCatalogRow
import io.github.onlinemo.meloxwatch.companion.data.companionRowIdentity
import io.github.onlinemo.meloxwatch.data.transfer.TransferException
import io.github.onlinemo.meloxwatch.data.transfer.TransferFingerprint
import io.github.onlinemo.meloxwatch.data.transfer.TransferIssue
import io.github.onlinemo.meloxwatch.data.transfer.TransferLimits
import io.github.onlinemo.meloxwatch.data.transfer.TransferOperationBinding
import io.github.onlinemo.meloxwatch.data.transfer.TransferOperationKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferReceipt
import io.github.onlinemo.meloxwatch.data.transfer.TransferReceiptKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferRequest
import io.github.onlinemo.meloxwatch.data.transfer.TransferResponse
import java.util.UUID

/** Reject before exposing or persisting a response. Socket authenticity does not replace message binding. */
internal object CompanionResponseValidator {
    fun greeting(response: TransferResponse, target: TransferFingerprint, expectedSession: String?): TransferResponse.Greeting {
        val result = response as? TransferResponse.Greeting ?: fail(TransferIssue.INVALID_INPUT)
        if (result.requestId != UUID(0L, 0L).toString() || result.target != target) fail(TransferIssue.WRONG_TARGET)
        if (expectedSession != null && result.sessionId != expectedSession) fail(TransferIssue.EXPIRED)
        if (result.remainingMs !in 1..TransferLimits.SESSION_MS) fail(TransferIssue.EXPIRED)
        return result
    }

    fun response(requestId: String, response: TransferResponse): TransferResponse {
        if (response.requestId != requestId) {
            if (response is TransferResponse.Authenticated) response.bearer.erase()
            fail(TransferIssue.WRONG_TARGET)
        }
        if (response is TransferResponse.Failure) fail(response.issue)
        return response
    }

    fun completed(requestId: String, binding: TransferOperationBinding, response: TransferResponse, actualBytes: Long? = null): TransferReceipt {
        val result = response(requestId, response) as? TransferResponse.Completed ?: fail(TransferIssue.INVALID_INPUT)
        val receipt = result.receipt
        if (!binding.sameOperation(receipt.binding)) fail(TransferIssue.CONFLICT)
        val expectedKind = when (binding.kind) {
            TransferOperationKind.AUDIO -> TransferReceiptKind.AUDIO_COMMITTED
            TransferOperationKind.THEME -> TransferReceiptKind.THEME_PENDING_CONFIRMATION
            TransferOperationKind.PLAYLIST -> TransferReceiptKind.PLAYLIST_COMMITTED
            TransferOperationKind.DISPLAY -> TransferReceiptKind.DISPLAY_COMMITTED
        }
        if (receipt.result != expectedKind || actualBytes != null && receipt.actualBytes != actualBytes) fail(TransferIssue.INTEGRITY_FAILED)
        return receipt
    }

    fun page(request: TransferRequest.ReadPage, page: TransferCatalogPage, expectedOrdinal: Long, expectedTotal: Long?): TransferCatalogPage {
        if (page.rows.map(::companionRowIdentity).distinct().size != page.rows.size) fail(TransferIssue.CONFLICT)
        if (page.kind != request.kind || page.rows.size > request.limit || page.firstOrdinal != expectedOrdinal) fail(TransferIssue.CONFLICT)
        page.rows.forEachIndexed { index, row ->
            if (row is TransferCatalogRow.Member && row.ordinal != page.firstOrdinal + index) fail(TransferIssue.CONFLICT)
        }
        if (request.viewId != null && (page.viewId != request.viewId || page.revision != request.expectedRevision ||
                !page.libraryRevision.sameAs(checkNotNull(request.expectedLibraryRevision)))) fail(TransferIssue.CONFLICT)
        if (expectedTotal != null && page.totalCount != expectedTotal) fail(TransferIssue.CONFLICT)
        val finished = page.firstOrdinal + page.rows.size == page.totalCount
        if (finished != (page.nextCursor == null) || !finished && page.rows.isEmpty() || page.nextCursor != null && page.nextCursor == request.cursor) fail(TransferIssue.INVALID_INPUT)
        return page
    }

    fun preview(request: TransferRequest.PreviewDisplay, response: TransferResponse): TransferResponse.DisplayPreview {
        val result = response(request.requestId, response) as? TransferResponse.DisplayPreview ?: fail(TransferIssue.INVALID_INPUT)
        val preview = result.preview
        if (preview.previewId != request.requestId || preview.kind != request.kind || preview.entityId != request.entityId || preview.revision != request.expectedRevision ||
                !preview.libraryRevision.sameAs(request.expectedLibraryRevision) || !preview.patch.sameAs(request.patch)) fail(TransferIssue.CONFLICT)
        return result
    }

    fun displayState(request: TransferRequest.ReadDisplay, response: TransferResponse): TransferResponse.DisplayState {
        val result = response(request.requestId, response) as? TransferResponse.DisplayState ?: fail(TransferIssue.INVALID_INPUT)
        if (result.state.kind != request.kind || result.state.entityId != request.entityId) fail(TransferIssue.CONFLICT)
        return result
    }

    private fun fail(issue: TransferIssue): Nothing = throw TransferException(issue)
}
