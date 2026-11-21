package io.github.onlinemo.meloxwatch.data.transfer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TransferProtocolCodecTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun maximumAudioBlockAndItsAuthenticatedEnvelopeFitOneSixtyFourKiBFrame() {
        val auth = TransferTestFixtures.authorized()
        val bytes = ByteArray(TransferLimits.CHUNK_BYTES) { (it % 251).toByte() }
        val request = TransferRequest.UploadChunk(TransferTestFixtures.id(), auth.scope, TransferTestFixtures.id(), 7L, bytes)
        val encoded = ByteArrayOutputStream().also { TransferProtocolCodec.writeRequest(it, request) }.toByteArray()
        assertTrue(encoded.size <= TransferLimits.FRAME_BYTES)
        val decoded = TransferProtocolCodec.readRequest(ByteArrayInputStream(encoded)) as TransferRequest.UploadChunk
        assertTrue(decoded.bytes.contentEquals(bytes) && decoded.offset == 7L && decoded.requestId == request.requestId)
        assertTrue(decoded.scope.target == auth.scope.target && decoded.scope.sessionId == auth.scope.sessionId)
    }

    @Test fun controlBatchPreservesFullTextAndAllOneHundredIdentifiers() {
        val auth = TransferTestFixtures.authorized()
        val ids = List(100) { TransferTestFixtures.id() }
        val name = "音".repeat(4096)
        val fence = TransferLibraryRevision(1L, 3L)
        for (patch in listOf<TransferDisplayPatch>(TransferDisplayPatch.Rename(name), TransferDisplayPatch.Add(ids))) {
            val request = TransferRequest.PreviewDisplay(TransferTestFixtures.id(), auth.scope, TransferDisplayKind.ALBUM, TransferTestFixtures.id(), 1L, fence, patch)
            val bytes = ByteArrayOutputStream().also { TransferProtocolCodec.writeRequest(it, request) }.toByteArray()
            assertTrue(bytes.size <= TransferLimits.FRAME_BYTES)
            val decoded = TransferProtocolCodec.readRequest(ByteArrayInputStream(bytes)) as TransferRequest.PreviewDisplay
            assertTrue(decoded.expectedLibraryRevision.sameAs(fence))
            if (patch is TransferDisplayPatch.Rename) {
                assertTrue((decoded.patch as TransferDisplayPatch.Rename).displayName == name)
            } else {
                assertTrue((decoded.patch as TransferDisplayPatch.Add).trackIds == ids)
            }
        }
    }

    @Test fun oversizedDeclaredFrameIsRejectedBeforeAnyBodyIsRead() {
        val header = ByteArrayOutputStream()
        DataOutputStream(header).use { output ->
            output.writeInt(0x4d585452)
            output.writeShort(TransferLimits.SCHEMA)
            output.writeByte(1)
            output.writeByte(0)
            output.writeLong(0L)
            output.writeLong(0L)
            output.writeInt(TransferLimits.FRAME_BYTES)
        }
        expect(TransferIssue.INVALID_INPUT) { TransferProtocolCodec.readRequest(ByteArrayInputStream(header.toByteArray())) }
    }

    @Test fun futureSchemaAndUnconsumedPayloadBytesAreRejected() {
        val auth = TransferTestFixtures.authorized()
        val request = TransferRequest.Authenticate(TransferTestFixtures.id(), auth.scope.target, auth.scope.sessionId)
        val encoded = ByteArrayOutputStream().also { TransferProtocolCodec.writeRequest(it, request) }.toByteArray()
        val future = encoded.copyOf().also { ByteBuffer.wrap(it).putShort(4, (TransferLimits.SCHEMA + 1).toShort()) }
        expect(TransferIssue.INCOMPATIBLE_SCHEMA) { TransferProtocolCodec.readRequest(ByteArrayInputStream(future)) }
        val trailing = encoded + byteArrayOf(0)
        ByteBuffer.wrap(trailing).putInt(24, encoded.size - 28 + 1)
        expect(TransferIssue.INVALID_INPUT) { TransferProtocolCodec.readRequest(ByteArrayInputStream(trailing)) }
    }

    @Test fun malformedUtf8CannotBecomeASilentReplacementCharacter() {
        val auth = TransferTestFixtures.authorized()
        val request = TransferRequest.ReadPage(TransferTestFixtures.id(), auth.scope, TransferCatalogKind.TRACKS, null, null, null, null, null, "A", 1)
        val encoded = ByteArrayOutputStream().also { TransferProtocolCodec.writeRequest(it, request) }.toByteArray()
        encoded[encoded.size - Integer.BYTES - 1] = 0xc0.toByte()
        expect(TransferIssue.INVALID_INPUT) { TransferProtocolCodec.readRequest(ByteArrayInputStream(encoded)) }
    }

    @Test fun operationBindingSurvivesNewSessionAuthorizationButChangesWithIntent() {
        val auth = TransferTestFixtures.authorized()
        val operation = TransferTestFixtures.id()
        val playlist = TransferTestFixtures.id()
        fun binding(scope: TransferScope, revision: Long, name: String): TransferOperationBinding = TransferProtocolCodec.operationBinding(auth.peer, TransferRequest.EditPlaylist(operation, scope, playlist, revision, TransferLibraryRevision(7L, 3L), TransferPlaylistEdit.Rename(name)))
        val first = binding(auth.scope, 1L, "First")
        val renewed = TransferScope(auth.scope.target, TransferTestFixtures.id(), TransferBearer.generate(java.security.SecureRandom()))
        assertTrue(first.sameOperation(binding(renewed, 1L, "First")))
        assertFalse(first.sameOperation(binding(renewed, 2L, "First")))
        assertFalse(first.sameOperation(binding(renewed, 1L, "Changed")))
    }

    @Test fun realCatalogAndRulesFencesRoundTripAndEachChangeInvalidatesPlaylistReplay() {
        val auth = TransferTestFixtures.authorized(setOf(TransferCapability.WRITE_PLAYLISTS))
        val operation = TransferTestFixtures.id()
        val playlist = TransferTestFixtures.id()
        val tracks = mutableListOf(TransferTestFixtures.id())
        val edit = TransferPlaylistEdit.Add(tracks)
        val selected = tracks.single()
        tracks.clear()
        fun request(catalog: Long, rules: Long) = TransferRequest.EditPlaylist(operation, auth.scope, playlist, 9L, TransferLibraryRevision(catalog, rules), edit)
        val original = request(20L, 4L)
        val encoded = ByteArrayOutputStream().also { TransferProtocolCodec.writeRequest(it, original) }
        val decoded = TransferProtocolCodec.readRequest(ByteArrayInputStream(encoded.toByteArray())) as TransferRequest.EditPlaylist
        assertTrue(checkNotNull(decoded.expectedLibraryRevision).sameAs(checkNotNull(original.expectedLibraryRevision)) && (decoded.edit as TransferPlaylistEdit.Add).trackIds == listOf(selected))
        val binding = TransferProtocolCodec.operationBinding(auth.peer, original)
        assertFalse(binding.sameOperation(TransferProtocolCodec.operationBinding(auth.peer, request(21L, 4L))))
        assertFalse(binding.sameOperation(TransferProtocolCodec.operationBinding(auth.peer, request(20L, 5L))))
    }

    @Test fun paginatedReadRequiresTheExactCatalogAndRulesFenceWithItsView() {
        val auth = TransferTestFixtures.authorized(setOf(TransferCapability.READ_LIBRARY))
        val fence = TransferLibraryRevision(30L, 8L)
        val request = TransferRequest.ReadPage(TransferTestFixtures.id(), auth.scope, TransferCatalogKind.TRACKS, TransferTestFixtures.id(), 3L, fence, "cursor", null, "Selected", 100)
        val encoded = ByteArrayOutputStream().also { TransferProtocolCodec.writeRequest(it, request) }
        val decoded = TransferProtocolCodec.readRequest(ByteArrayInputStream(encoded.toByteArray())) as TransferRequest.ReadPage
        assertTrue(decoded.viewId == request.viewId && decoded.expectedRevision == request.expectedRevision && checkNotNull(decoded.expectedLibraryRevision).sameAs(fence))
        try { TransferRequest.ReadPage(TransferTestFixtures.id(), auth.scope, TransferCatalogKind.TRACKS, request.viewId, 3L, null, "cursor", null, "", 100)
            fail("Expected a missing real fence rejection") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun oneHundredLongRowsStreamWithExactRevisionOrdinalCountAndDigest() {
        val file = temporary.newFile()
        val text = "长".repeat(4096)
        val rows = List(100) { TransferCatalogRow.Track(TransferTestFixtures.id(), text, text, text, 1000L, true) }
        val page = TransferCatalogPage(TransferCatalogKind.TRACKS, TransferTestFixtures.id(), 7L, TransferLibraryRevision(20L, 4L), 1_000_000L, 500L, rows, "next_cursor")
        val requestId = TransferTestFixtures.id()
        var maximumWrite = 0
        var flushes = 0
        file.outputStream().use { actual ->
            val measuring = object : FilterOutputStream(actual) {
                override fun write(bytes: ByteArray, offset: Int, length: Int) { maximumWrite = maxOf(maximumWrite, length)
                    out.write(bytes, offset, length) }
                override fun write(bytes: ByteArray) { write(bytes, 0, bytes.size) }
                override fun flush() { flushes++
                    out.flush() }
            }
            TransferProtocolCodec.writeResponse(measuring, TransferResponse.Page(requestId, page))
        }
        assertTrue(maximumWrite <= TransferLimits.FRAME_BYTES && file.length() <= TransferLimits.PAGE_BYTES && flushes == 102)
        var seen = 0
        val completed = file.inputStream().use { input -> TransferProtocolCodec.readPage(input, requestId) { item ->
            assertTrue(item.revision == 7L && item.ordinal == 500L + seen)
            val row = item.row as TransferCatalogRow.Track
            assertTrue(row.title == text && row.artist == text && row.album == text)
            seen++
        } }
        assertTrue(seen == 100 && completed.rowCount == 100 && completed.encodedBytes == file.length())
        assertTrue(completed.begin.libraryRevision.sameAs(page.libraryRevision))
    }

    @Test fun anEmptyCompletePageHasATerminalDigest() {
        val requestId = TransferTestFixtures.id()
        val page = TransferCatalogPage(TransferCatalogKind.TRACKS, TransferTestFixtures.id(), 1L, TransferLibraryRevision(1L, 0L), 0L, 0L, emptyList(), null)
        val output = ByteArrayOutputStream().also { TransferProtocolCodec.writeResponse(it, TransferResponse.Page(requestId, page)) }
        val end = TransferProtocolCodec.readPage(ByteArrayInputStream(output.toByteArray()), requestId) { fail("No row expected") }
        assertTrue(end.rowCount == 0 && end.encodedBytes == output.size().toLong())
    }

    @Test fun badTerminalDigestNeverPublishesStagedRowsAsACompletePage() {
        val requestId = TransferTestFixtures.id()
        val file = temporary.newFile()
        val page = TransferCatalogPage(TransferCatalogKind.TRACKS, TransferTestFixtures.id(), 1L, TransferLibraryRevision(1L, 0L), 1L, 0L, listOf(TransferCatalogRow.Track(TransferTestFixtures.id(), "Track", "Artist", "Album", 1000L, true)), null)
        file.outputStream().use { TransferProtocolCodec.writeResponse(it, TransferResponse.Page(requestId, page)) }
        RandomAccessFile(file, "rw").use { it.seek(it.length() - 1)
            val last = it.read()
            it.seek(it.length() - 1)
            it.write(last xor 1) }
        var staged = 0
        var published = false
        expect(TransferIssue.INTEGRITY_FAILED) {
            file.inputStream().use { TransferProtocolCodec.readPage(it, requestId) { staged++ } }
            published = true
        }
        assertTrue(staged == 1)
        assertFalse(published)
    }

    @Test fun incorrectRowOrdinalStopsBeforeDelivery() {
        val requestId = TransferTestFixtures.id()
        val view = TransferTestFixtures.id()
        val output = ByteArrayOutputStream()
        TransferProtocolCodec.writeResponse(output, TransferResponse.PageBegin(requestId, TransferCatalogKind.TRACKS, view, 1L, TransferLibraryRevision(1L, 0L), 11L, 10L, 1, null))
        TransferProtocolCodec.writeResponse(output, TransferResponse.PageRow(requestId, view, 1L, 11L, TransferCatalogRow.Track(TransferTestFixtures.id(), "Track", "Artist", "Album", 1000L, true)))
        var delivered = false
        expect(TransferIssue.CONFLICT) { TransferProtocolCodec.readPage(ByteArrayInputStream(output.toByteArray()), requestId) { delivered = true } }
        assertFalse(delivered)
    }

    @Test fun cancelledStreamContainsNoSuccessfulTerminalPage() {
        val requestId = TransferTestFixtures.id()
        val file = temporary.newFile()
        val page = TransferCatalogPage(TransferCatalogKind.TRACKS, TransferTestFixtures.id(), 1L, TransferLibraryRevision(1L, 0L), 10L, 0L, List(10) { TransferCatalogRow.Track(TransferTestFixtures.id(), "Track", "Artist", "Album", 1000L, true) }, null)
        var checks = 0
        expect(TransferIssue.CLOSED) { file.outputStream().use { TransferProtocolCodec.writeResponse(it, TransferResponse.Page(requestId, page)) { if (++checks == 6) throw TransferException(TransferIssue.CLOSED) } } }
        try { file.inputStream().use { TransferProtocolCodec.readPage(it, requestId) {} }
            fail("Expected an interrupted page") }
        catch (_: IOException) { }
    }

    private fun expect(issue: TransferIssue, action: () -> Unit) {
        try { action()
            fail("Expected a protocol rejection") }
        catch (error: TransferException) { assertTrue(error.issue == issue) }
    }
}
