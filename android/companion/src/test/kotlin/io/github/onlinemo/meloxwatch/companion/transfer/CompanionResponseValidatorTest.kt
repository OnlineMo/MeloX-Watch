package io.github.onlinemo.meloxwatch.companion.transfer

import io.github.onlinemo.meloxwatch.data.transfer.TransferBearer
import io.github.onlinemo.meloxwatch.data.transfer.TransferCapability
import io.github.onlinemo.meloxwatch.data.transfer.TransferCatalogKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferCatalogPage
import io.github.onlinemo.meloxwatch.data.transfer.TransferCatalogRow
import io.github.onlinemo.meloxwatch.data.transfer.TransferDisplayKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferDisplayPatch
import io.github.onlinemo.meloxwatch.data.transfer.TransferDisplayPreview
import io.github.onlinemo.meloxwatch.data.transfer.TransferDisplayState
import io.github.onlinemo.meloxwatch.data.transfer.TransferException
import io.github.onlinemo.meloxwatch.data.transfer.TransferFingerprint
import io.github.onlinemo.meloxwatch.data.transfer.TransferHash
import io.github.onlinemo.meloxwatch.data.transfer.TransferIssue
import io.github.onlinemo.meloxwatch.data.transfer.TransferLibraryRevision
import io.github.onlinemo.meloxwatch.data.transfer.TransferLimits
import io.github.onlinemo.meloxwatch.data.transfer.TransferOperationBinding
import io.github.onlinemo.meloxwatch.data.transfer.TransferPlaylistEdit
import io.github.onlinemo.meloxwatch.data.transfer.TransferProtocolCodec
import io.github.onlinemo.meloxwatch.data.transfer.TransferReceipt
import io.github.onlinemo.meloxwatch.data.transfer.TransferReceiptKind
import io.github.onlinemo.meloxwatch.data.transfer.TransferRequest
import io.github.onlinemo.meloxwatch.data.transfer.TransferResponse
import io.github.onlinemo.meloxwatch.data.transfer.TransferScope
import io.github.onlinemo.meloxwatch.data.transfer.TransferUploadDeclaration
import io.github.onlinemo.meloxwatch.data.transfer.TransferUploadKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Synthetic identities and content only; these definitions are executed by cloud CI. */
class CompanionResponseValidatorTest {
    private val target = TransferFingerprint.digest(byteArrayOf(1))
    private val peer = TransferFingerprint.digest(byteArrayOf(2))
    private val fence = TransferLibraryRevision(7L, 3L)
    private fun id(number: Long): String = UUID(0L, number).toString()
    private fun scope(session: Long = 1L, token: Byte = 9): TransferScope = TransferScope(target, id(session), TransferBearer.fromWire(ByteArray(32) { token }))
    private fun track(number: Int): TransferCatalogRow.Track = TransferCatalogRow.Track("track:$number", "Title", "Artist", "Album", 1000L, true)
    private fun encoded(request: TransferRequest): ByteArray = ByteArrayOutputStream().also { TransferProtocolCodec.writeRequest(it, request) }.toByteArray()
    private fun encoded(response: TransferResponse): ByteArray = ByteArrayOutputStream().also { TransferProtocolCodec.writeResponse(it, response) }.toByteArray()

    @Test fun schema3ReadDisplayHasItsOwnTagsAndRoundTripsMinimumState() {
        val request = TransferRequest.ReadDisplay(id(2), scope(), TransferDisplayKind.ALBUM, "album:fixture")
        val wire = encoded(request)
        assertEquals(3, wire[5].toInt())
        assertEquals(11, wire[6].toInt())
        assertEquals("4d58545200030b000000000000000000000000000000000200000065" +
            "4bf5122f344554c53bde2ebb8cd2b7e3d1600ad631c385a5d7cce23c7785459a" +
            "000000000000000000000000000000010909090909090909090909090909090909090909090909090909090909090909" +
            "000000010000000d616c62756d3a66697874757265", hex(wire))
        val decoded = TransferProtocolCodec.readRequest(ByteArrayInputStream(wire)) as TransferRequest.ReadDisplay
        assertEquals(request.requestId, decoded.requestId)
        assertEquals(request.entityId, decoded.entityId)
        assertEquals(request.kind, decoded.kind)
        val state = TransferDisplayState(request.kind, request.entityId, 7L, fence, "", 20001L)
        val responseWire = encoded(TransferResponse.DisplayState(request.requestId, state))
        assertEquals(76, responseWire[6].toInt())
        assertEquals("4d58545200034c000000000000000000000000000000000200000039" +
            "000000010000000d616c62756d3a66697874757265000000000000000700000000000000070000000000000003" +
            "000000000000000000004e21", hex(responseWire))
        val response = TransferProtocolCodec.readResponse(ByteArrayInputStream(responseWire))
        val verified = CompanionResponseValidator.displayState(request, response).state
        assertEquals("", verified.currentName)
        assertEquals(20001L, verified.trackCount)
        assertTrue(verified.libraryRevision.sameAs(fence))
    }

    @Test fun schema1IsRejectedBeforeParsingACompatibleLookingBody() {
        val wire = encoded(TransferRequest.ReadDisplay(id(2), scope(), TransferDisplayKind.ARTIST, "artist:fixture"))
        wire[5] = 1
        issue(TransferIssue.INCOMPATIBLE_SCHEMA) { TransferProtocolCodec.readRequest(ByteArrayInputStream(wire)) }
    }

    @Test fun playlistWriteOnlyCreateAndRenameRoundTripWithoutLibraryReadFence() {
        val edits = listOf(TransferPlaylistEdit.Create("Fixture"), TransferPlaylistEdit.Rename("Renamed"))
        for (edit in edits) {
            val request = TransferRequest.EditPlaylist(id(2), scope(), "playlist:fixture", 0L, null, edit)
            val decoded = TransferProtocolCodec.readRequest(ByteArrayInputStream(encoded(request))) as TransferRequest.EditPlaylist
            assertNull(decoded.expectedLibraryRevision)
            assertEquals(0L, decoded.expectedRevision)
            assertEquals(edit.javaClass, decoded.edit.javaClass)
        }
        val add = TransferRequest.EditPlaylist(id(3), scope(), "playlist:fixture", 4L, fence, TransferPlaylistEdit.Add(listOf("track:1")))
        val decoded = TransferProtocolCodec.readRequest(ByteArrayInputStream(encoded(add))) as TransferRequest.EditPlaylist
        assertTrue(checkNotNull(decoded.expectedLibraryRevision).sameAs(fence))
        assertEquals(listOf("track:1"), (decoded.edit as TransferPlaylistEdit.Add).trackIds)
        invalid { TransferRequest.EditPlaylist(id(3), scope(), "playlist:fixture", 4L, null, TransferPlaylistEdit.Add(listOf("track:1"))) }
        invalid { TransferRequest.EditPlaylist(id(3), scope(), "playlist:fixture", 1L, null, TransferPlaylistEdit.Create("Fixture")) }
    }

    @Test fun allDisplayPatchesRoundTripWithTheCompleteRealFence() {
        val patches = listOf(TransferDisplayPatch.Rename("New name"), TransferDisplayPatch.Add(listOf("track:2", "track:1")), TransferDisplayPatch.Remove(listOf("track:1")))
        for (patch in patches) {
            val request = TransferRequest.PreviewDisplay(id(2), scope(), TransferDisplayKind.ARTIST, "artist:fixture", 7L, fence, patch)
            val decoded = TransferProtocolCodec.readRequest(ByteArrayInputStream(encoded(request))) as TransferRequest.PreviewDisplay
            assertTrue(decoded.patch.sameAs(patch))
            assertTrue(decoded.expectedLibraryRevision.sameAs(fence))
            val proposed = if (patch is TransferDisplayPatch.Rename) patch.displayName else ""
            val preview = TransferDisplayPreview(id(2), request.kind, request.entityId, 7L, "", proposed, patch, fence, 20001L)
            val response = TransferProtocolCodec.readResponse(ByteArrayInputStream(encoded(TransferResponse.DisplayPreview(id(2), preview))))
            assertEquals(20001L, CompanionResponseValidator.preview(request, response).preview.currentTrackCount)
        }
    }

    @Test fun previewHashExcludesTransientAuthorizationAndBindsEveryBusinessField() {
        val patch = TransferDisplayPatch.Add(listOf("track:1", "track:2"))
        fun request(selectedTarget: TransferFingerprint = target, kind: TransferDisplayKind = TransferDisplayKind.ALBUM,
            entity: String = "album:fixture", selectedFence: TransferLibraryRevision = fence,
            selectedPatch: TransferDisplayPatch = patch, requestId: Long = 2L, session: Long = 1L, token: Byte = 9): TransferRequest.PreviewDisplay =
            TransferRequest.PreviewDisplay(id(requestId), TransferScope(selectedTarget, id(session), TransferBearer.fromWire(ByteArray(32) { token })), kind, entity, selectedFence.catalogRevision, selectedFence, selectedPatch)
        val original = TransferProtocolCodec.displayPreviewHash(request())
        assertEquals("2141e76f6587f63b9de2299b1b235823352b80786a5a74dfe2228fa6bc4f6468", original.forLocalStorage())
        assertEquals(original, TransferProtocolCodec.displayPreviewHash(request(requestId = 8L, session = 9L, token = 3)))
        assertEquals(original, TransferProtocolCodec.displayPreviewHash(target, TransferDisplayKind.ALBUM, "album:fixture", 7L, fence, patch))
        assertNotEquals(original, TransferProtocolCodec.displayPreviewHash(request(selectedTarget = peer)))
        assertNotEquals(original, TransferProtocolCodec.displayPreviewHash(request(kind = TransferDisplayKind.ARTIST)))
        assertNotEquals(original, TransferProtocolCodec.displayPreviewHash(request(entity = "album:other")))
        assertNotEquals(original, TransferProtocolCodec.displayPreviewHash(request(selectedFence = TransferLibraryRevision(8L, 3L))))
        assertNotEquals(original, TransferProtocolCodec.displayPreviewHash(request(selectedFence = TransferLibraryRevision(7L, 4L))))
        assertNotEquals(original, TransferProtocolCodec.displayPreviewHash(request(selectedPatch = TransferDisplayPatch.Add(listOf("track:2", "track:1")))))
        assertNotEquals(original, TransferProtocolCodec.displayPreviewHash(request(selectedPatch = TransferDisplayPatch.Remove(listOf("track:1", "track:2")))))
        assertNotEquals(original, TransferProtocolCodec.displayPreviewHash(request(selectedPatch = TransferDisplayPatch.Rename("Name"))))
    }

    @Test fun operationBindingSurvivesNewSessionButRejectsChangedPeerTargetAndDeclaration() {
        val declaration = TransferUploadDeclaration(TransferUploadKind.AUDIO, "fixture.wav", "audio/wav", 3L, TransferHash.digest(byteArrayOf(1, 2, 3)), 0L)
        val request = TransferRequest.BeginUpload(id(2), scope(), declaration)
        val binding = TransferProtocolCodec.operationBinding(peer, request)
        val newSession = TransferProtocolCodec.operationBinding(peer, TransferRequest.BeginUpload(id(2), scope(7L, 4), declaration))
        assertTrue(binding.sameOperation(newSession))
        assertFalse(binding.sameOperation(TransferProtocolCodec.operationBinding(target, request)))
        val otherTarget = TransferRequest.BeginUpload(id(2), TransferScope(peer, id(1), TransferBearer.fromWire(ByteArray(32))), declaration)
        assertFalse(binding.sameOperation(TransferProtocolCodec.operationBinding(peer, otherTarget)))
        val changed = TransferUploadDeclaration(TransferUploadKind.AUDIO, "changed.wav", "audio/wav", 3L, declaration.sha256, 0L)
        assertFalse(binding.sameOperation(TransferProtocolCodec.operationBinding(peer, TransferRequest.BeginUpload(id(2), scope(), changed))))
    }

    @Test fun wrongResponseRequestErasesAnUnadoptedBearer() {
        val bearer = TransferBearer.fromWire(ByteArray(32) { 5 })
        issue(TransferIssue.WRONG_TARGET) {
            CompanionResponseValidator.response(id(2), TransferResponse.Authenticated(id(3), bearer, setOf(TransferCapability.READ_LIBRARY), 1000L))
        }
        assertArrayEquals(ByteArray(32), bearer.wireBytes())
    }

    @Test fun receiptMustBindTheSamePeerTargetPayloadOperationAndSize() {
        val request = TransferRequest.BeginUpload(id(2), scope(), TransferUploadDeclaration(TransferUploadKind.AUDIO, "fixture.wav", "audio/wav", 3L, TransferHash.digest(byteArrayOf(1, 2, 3)), 0L))
        val binding = TransferProtocolCodec.operationBinding(peer, request)
        fun completed(candidate: TransferOperationBinding = binding, bytes: Long = 3L): TransferResponse.Completed =
            TransferResponse.Completed(id(9), TransferReceipt(candidate, TransferReceiptKind.AUDIO_COMMITTED, 8L, "track:fixture", bytes), true)
        assertSame(binding, CompanionResponseValidator.completed(id(9), binding, completed(), 3L).binding)
        for (changed in listOf(
            TransferOperationBinding(id(3), binding.kind, target, peer, binding.payloadHash),
            TransferOperationBinding(id(2), binding.kind, peer, peer, binding.payloadHash),
            TransferOperationBinding(id(2), binding.kind, target, target, binding.payloadHash),
            TransferOperationBinding(id(2), binding.kind, target, peer, TransferHash.digest(byteArrayOf(8))),
        )) issue(TransferIssue.CONFLICT) { CompanionResponseValidator.completed(id(9), binding, completed(changed), 3L) }
        issue(TransferIssue.INTEGRITY_FAILED) { CompanionResponseValidator.completed(id(9), binding, completed(bytes = 2L), 3L) }
    }

    @Test fun previewRejectsAnotherPatchPreviewIdOrFenceBeforeDraftPersistence() {
        val patch = TransferDisplayPatch.Add(listOf("track:1"))
        val request = TransferRequest.PreviewDisplay(id(2), scope(), TransferDisplayKind.ALBUM, "album:fixture", 7L, fence, patch)
        fun response(previewId: Long = 2L, selectedPatch: TransferDisplayPatch = patch, selectedFence: TransferLibraryRevision = fence): TransferResponse.DisplayPreview =
            TransferResponse.DisplayPreview(id(2), TransferDisplayPreview(id(previewId), request.kind, request.entityId, selectedFence.catalogRevision, "", "", selectedPatch, selectedFence, 3L))
        issue(TransferIssue.CONFLICT) { CompanionResponseValidator.preview(request, response(previewId = 3L)) }
        issue(TransferIssue.CONFLICT) { CompanionResponseValidator.preview(request, response(selectedPatch = TransferDisplayPatch.Add(listOf("track:2")))) }
        issue(TransferIssue.CONFLICT) { CompanionResponseValidator.preview(request, response(selectedFence = TransferLibraryRevision(7L, 4L))) }
        val wrong = TransferDisplayState(TransferDisplayKind.ARTIST, "artist:other", 7L, fence, "", 3L)
        issue(TransferIssue.CONFLICT) { CompanionResponseValidator.displayState(TransferRequest.ReadDisplay(id(2), scope(), TransferDisplayKind.ALBUM, "album:fixture"), TransferResponse.DisplayState(id(2), wrong)) }
    }

    @Test fun pageRejectsDuplicatesDiscontinuitiesAndPrematureCompletion() {
        val first = TransferRequest.ReadPage(id(2), scope(), TransferCatalogKind.TRACKS, null, null, null, null, null, "", 100)
        fun page(rows: List<TransferCatalogRow> = listOf(track(1)), total: Long = 2L, ordinal: Long = 0L, next: String? = "cursor_1"): TransferCatalogPage =
            TransferCatalogPage(TransferCatalogKind.TRACKS, id(4), 5L, fence, total, ordinal, rows, next)
        assertEquals(1, CompanionResponseValidator.page(first, page(), 0L, null).rows.size)
        issue(TransferIssue.CONFLICT) { CompanionResponseValidator.page(first, page(rows = listOf(track(1), track(1)), total = 3L), 0L, null) }
        issue(TransferIssue.CONFLICT) { CompanionResponseValidator.page(first, page(ordinal = 1L), 0L, null) }
        issue(TransferIssue.INVALID_INPUT) { CompanionResponseValidator.page(first, page(next = null), 0L, null) }
        issue(TransferIssue.INVALID_INPUT) { CompanionResponseValidator.page(first, page(rows = emptyList()), 0L, null) }
        val continuation = TransferRequest.ReadPage(id(3), scope(), TransferCatalogKind.TRACKS, id(4), 5L, fence, "cursor_1", null, "", 100)
        issue(TransferIssue.CONFLICT) { CompanionResponseValidator.page(continuation, page(ordinal = 1L, total = 3L), 1L, 2L) }
        issue(TransferIssue.INVALID_INPUT) { CompanionResponseValidator.page(continuation, page(ordinal = 1L, total = 3L), 1L, 3L) }
        assertNull(CompanionResponseValidator.page(continuation, page(ordinal = 1L, next = null), 1L, 2L).nextCursor)
    }

    @Test fun continuationPageCannotChangeLeaseOrLibraryFence() {
        val request = TransferRequest.ReadPage(id(2), scope(), TransferCatalogKind.TRACKS, id(4), 5L, fence, "cursor_1", null, "", 100)
        for (page in listOf(
            TransferCatalogPage(request.kind, id(5), 5L, fence, 2L, 1L, listOf(track(2)), null),
            TransferCatalogPage(request.kind, id(4), 6L, fence, 2L, 1L, listOf(track(2)), null),
            TransferCatalogPage(request.kind, id(4), 5L, TransferLibraryRevision(7L, 4L), 2L, 1L, listOf(track(2)), null),
        )) issue(TransferIssue.CONFLICT) { CompanionResponseValidator.page(request, page, 1L, 2L) }
    }

    @Test fun playlistMembersUseContinuousAbsoluteOrdinalsIncludingUnavailableRows() {
        val request = TransferRequest.ReadPage(id(2), scope(), TransferCatalogKind.PLAYLIST_MEMBERS, id(4), 5L, fence, "cursor_1", "playlist:fixture", "", 100)
        fun page(ordinal: Long): TransferCatalogPage = TransferCatalogPage(request.kind, id(4), 5L, fence, 101L, 100L,
            listOf(TransferCatalogRow.Member("member:missing", ordinal, null, "Missing", "", false)), null)
        assertEquals(100L, (CompanionResponseValidator.page(request, page(100L), 100L, 101L).rows.single() as TransferCatalogRow.Member).ordinal)
        issue(TransferIssue.CONFLICT) { CompanionResponseValidator.page(request, page(1L), 100L, 101L) }
    }

    @Test fun streamingGroupRevisionIsRejectedBeforeStagingTheRow() {
        val output = ByteArrayOutputStream()
        TransferProtocolCodec.writeResponse(output, TransferResponse.PageBegin(id(2), TransferCatalogKind.ALBUMS, id(4), 5L, fence, 1L, 0L, 1, null))
        TransferProtocolCodec.writeResponse(output, TransferResponse.PageRow(id(2), id(4), 5L, 0L, TransferCatalogRow.Group("album:fixture", "", "", 3L, 8L)))
        var staged = 0
        issue(TransferIssue.CONFLICT) { TransferProtocolCodec.readPage(ByteArrayInputStream(output.toByteArray()), id(2)) { staged++ } }
        assertEquals(0, staged)
    }

    @Test fun streamingPageNeedsVerifiedEndHashBeforeCompletion() {
        val page = TransferCatalogPage(TransferCatalogKind.TRACKS, id(4), 5L, fence, 2L, 0L, listOf(track(1), track(2)), null)
        val wire = encoded(TransferResponse.Page(id(2), page))
        val staged = ArrayList<TransferCatalogRow>()
        val result = TransferProtocolCodec.readPage(ByteArrayInputStream(wire), id(2)) { staged += it.row }
        assertEquals(2, result.rowCount)
        assertEquals(wire.size.toLong(), result.encodedBytes)
        assertEquals(2, staged.size)
        wire[wire.lastIndex] = (wire.last().toInt() xor 1).toByte()
        issue(TransferIssue.INTEGRITY_FAILED) { TransferProtocolCodec.readPage(ByteArrayInputStream(wire), id(2)) {} }
    }

    @Test fun parentIsMandatoryExactlyForMemberCatalogs() {
        for (kind in TransferCatalogKind.entries) {
            val memberKind = kind in setOf(TransferCatalogKind.PLAYLIST_MEMBERS, TransferCatalogKind.ALBUM_TRACKS, TransferCatalogKind.ARTIST_TRACKS)
            val parent = if (memberKind) "parent:fixture" else null
            TransferRequest.ReadPage(id(2), scope(), kind, null, null, null, null, parent, "", TransferLimits.PAGE_SIZE)
            invalid { TransferRequest.ReadPage(id(2), scope(), kind, null, null, null, null, if (memberKind) null else "parent:fixture", "", TransferLimits.PAGE_SIZE) }
        }
    }

    private fun issue(expected: TransferIssue, action: () -> Unit) {
        try { action(); fail("Expected a protocol issue") } catch (error: TransferException) { assertEquals(expected, error.issue) }
    }
    private fun invalid(action: () -> Unit) {
        try { action(); fail("Expected invalid contract input") } catch (_: IllegalArgumentException) { }
    }
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
