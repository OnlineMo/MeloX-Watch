package io.github.onlinemo.meloxwatch.data.transfer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/** TLS-only binary protocol. No JSON entity serialization, line parser or whole-page buffer. */
object TransferProtocolCodec {
    private const val MAGIC = 0x4d585452
    private const val HEADER_BYTES = 28
    private const val PAIR = 1
    private const val AUTHENTICATE = 2
    private const val READ_PAGE = 3
    private const val EDIT_PLAYLIST = 4
    private const val PREVIEW_DISPLAY = 5
    private const val APPLY_DISPLAY = 6
    private const val BEGIN_UPLOAD = 7
    private const val UPLOAD_CHUNK = 8
    private const val FINISH_UPLOAD = 9
    private const val CANCEL_UPLOAD = 10
    private const val READ_DISPLAY = 11
    private const val READ_RECEIPT = 12
    private const val CLOSE_CATALOG_VIEW = 13
    private const val GREETING = 64
    private const val PAIR_PENDING = 65
    private const val AUTHENTICATED = 66
    private const val PAGE_BEGIN = 67
    private const val PAGE_ROW = 68
    private const val PAGE_END = 69
    private const val DISPLAY_PREVIEW = 70
    private const val UPLOAD_READY = 71
    private const val CHUNK_ACCEPTED = 72
    private const val COMPLETED = 73
    private const val CANCELLED = 74
    private const val FAILURE = 75
    private const val DISPLAY_STATE = 76
    private const val RECEIPT_STATE = 77

    private class Frame(val type: Int, val id: String, val body: ByteArray) {
        val encodedBytes: Int get() = HEADER_BYTES + body.size
        fun header(): ByteArray = ByteBuffer.allocate(HEADER_BYTES).putInt(MAGIC).putShort(TransferLimits.SCHEMA.toShort())
            .put(type.toByte()).put(0).putLong(UUID.fromString(id).mostSignificantBits)
            .putLong(UUID.fromString(id).leastSignificantBits).putInt(body.size).array()
        fun update(digest: MessageDigest) { digest.update(header()); digest.update(body) }
    }

    fun writeRequest(output: OutputStream, request: TransferRequest) { writeFrame(output, requestFrame(request)) }

    fun readRequest(input: InputStream): TransferRequest {
        val frame = readFrame(input)
        return parse(frame) { reader -> when (frame.type) {
            PAIR -> TransferRequest.Pair(frame.id, reader.fingerprint(), reader.uuid(), reader.string(8, 8), capabilitiesFromMask(reader.int()))
            AUTHENTICATE -> TransferRequest.Authenticate(frame.id, reader.fingerprint(), reader.uuid())
            READ_RECEIPT -> TransferRequest.ReadReceipt(frame.id, reader.scope(), reader.uuid(), reader.operationKind(), TransferHash.fromWire(reader.raw(32)))
            CLOSE_CATALOG_VIEW -> TransferRequest.CloseCatalogView(frame.id, reader.scope(), reader.catalogKind(), reader.uuid())
            READ_PAGE -> TransferRequest.ReadPage(frame.id, reader.scope(), reader.catalogKind(), reader.optionalUuid(), reader.optionalLong(), reader.optionalLibraryRevision(), reader.optionalCursor(), reader.optionalIdentifier(), reader.field(), reader.int())
            EDIT_PLAYLIST -> TransferRequest.EditPlaylist(frame.id, reader.scope(), reader.identifier(), reader.long(), reader.optionalLibraryRevision(), reader.playlistEdit())
            READ_DISPLAY -> TransferRequest.ReadDisplay(frame.id, reader.scope(), reader.displayKind(), reader.identifier())
            PREVIEW_DISPLAY -> TransferRequest.PreviewDisplay(frame.id, reader.scope(), reader.displayKind(), reader.identifier(), reader.long(), reader.libraryRevision(), reader.displayPatch())
            APPLY_DISPLAY -> TransferRequest.ApplyDisplay(frame.id, reader.scope(), reader.uuid(), reader.long(), reader.boolean())
            BEGIN_UPLOAD -> TransferRequest.BeginUpload(frame.id, reader.scope(), reader.declaration())
            UPLOAD_CHUNK -> TransferRequest.UploadChunk(frame.id, reader.scope(), reader.uuid(), reader.long(), reader.bytes(TransferLimits.CHUNK_BYTES))
            FINISH_UPLOAD -> TransferRequest.FinishUpload(frame.id, reader.scope(), reader.uuid())
            CANCEL_UPLOAD -> TransferRequest.CancelUpload(frame.id, reader.scope(), reader.uuid())
            else -> throw TransferException(TransferIssue.INVALID_INPUT)
        } }
    }

    /** Fresh authorization is checked before every independently bounded page frame. */
    fun writeResponse(output: OutputStream, response: TransferResponse, checkActive: () -> Unit = {}) {
        if (response is TransferResponse.Page) {
            val page = response.page
            val begin = TransferResponse.PageBegin(response.requestId, page.kind, page.viewId, page.revision, page.libraryRevision, page.totalCount, page.firstOrdinal, page.rows.size, page.nextCursor)
            val digest = MessageDigest.getInstance("SHA-256")
            var encoded = 0L
            fun send(item: TransferResponse) {
                checkActive()
                val frame = responseFrame(item)
                encoded = Math.addExact(encoded, frame.encodedBytes.toLong())
                if (encoded > TransferLimits.PAGE_BYTES) throw TransferException(TransferIssue.INVALID_INPUT)
                frame.update(digest)
                writeFrame(output, frame)
                checkActive()
            }
            send(begin)
            page.rows.forEachIndexed { index, row -> send(TransferResponse.PageRow(response.requestId, page.viewId, page.revision, Math.addExact(page.firstOrdinal, index.toLong()), row)) }
            val end = responseFrame(TransferResponse.PageEnd(response.requestId, page.viewId, page.revision, page.rows.size, encoded, TransferHash.fromWire(digest.digest())))
            if (encoded + end.encodedBytes > TransferLimits.PAGE_BYTES) throw TransferException(TransferIssue.INVALID_INPUT)
            checkActive(); writeFrame(output, end); checkActive()
        } else { checkActive(); writeFrame(output, responseFrame(response)); checkActive() }
    }

    fun readResponse(input: InputStream): TransferResponse = responseFromFrame(readFrame(input))

    class PageCompletion(val begin: TransferResponse.PageBegin, val rowCount: Int, val encodedBytes: Long)

    /**
     * onRow stages one row at a time. The consumer may publish/cache a page only after this
     * function returns its verified completion; exception/cancellation must discard that stage.
     * The frame reader retains at most one 64 KiB body, never a 6 MiB page.
     */
    fun readPage(input: InputStream, expectedRequestId: String, onRow: (TransferResponse.PageRow) -> Unit): PageCompletion {
        requireTransferUuid(expectedRequestId)
        val firstFrame = readFrame(input)
        val begin = responseFromFrame(firstFrame) as? TransferResponse.PageBegin ?: throw TransferException(TransferIssue.INVALID_INPUT)
        validateBegin(begin)
        if (begin.requestId != expectedRequestId) throw TransferException(TransferIssue.WRONG_TARGET)
        val digest = MessageDigest.getInstance("SHA-256")
        firstFrame.update(digest)
        var encoded = firstFrame.encodedBytes.toLong()
        repeat(begin.rowCount) { index ->
            val frame = readFrame(input)
            encoded = Math.addExact(encoded, frame.encodedBytes.toLong())
            if (encoded > TransferLimits.PAGE_BYTES) throw TransferException(TransferIssue.INVALID_INPUT)
            val row = responseFromFrame(frame) as? TransferResponse.PageRow ?: throw TransferException(TransferIssue.INVALID_INPUT)
            if (row.requestId != expectedRequestId || row.viewId != begin.viewId || row.revision != begin.revision || row.ordinal != begin.firstOrdinal + index) throw TransferException(TransferIssue.CONFLICT)
            if (!rowMatches(begin.kind, row.row)) throw TransferException(TransferIssue.INVALID_INPUT)
            if (row.row is TransferCatalogRow.Group && row.row.revision != begin.libraryRevision.catalogRevision) throw TransferException(TransferIssue.CONFLICT)
            frame.update(digest)
            onRow(row)
        }
        val endFrame = readFrame(input)
        if (encoded + endFrame.encodedBytes > TransferLimits.PAGE_BYTES) throw TransferException(TransferIssue.INVALID_INPUT)
        val end = responseFromFrame(endFrame) as? TransferResponse.PageEnd ?: throw TransferException(TransferIssue.INVALID_INPUT)
        if (end.requestId != expectedRequestId || end.viewId != begin.viewId || end.revision != begin.revision || end.rowCount != begin.rowCount || end.encodedBytes != encoded) throw TransferException(TransferIssue.CONFLICT)
        if (end.streamHash != TransferHash.fromWire(digest.digest())) throw TransferException(TransferIssue.INTEGRITY_FAILED)
        return PageCompletion(begin, begin.rowCount, encoded + endFrame.encodedBytes)
    }

    /** Canonical business payload excludes bearer/session so a newly authorized retry reads the original result. */
    fun operationBinding(peer: TransferFingerprint, request: TransferRequest.Authorized): TransferOperationBinding {
        val kind = when (request) {
            is TransferRequest.BeginUpload -> if (request.declaration.kind == TransferUploadKind.AUDIO) TransferOperationKind.AUDIO else TransferOperationKind.THEME
            is TransferRequest.EditPlaylist -> TransferOperationKind.PLAYLIST
            is TransferRequest.ApplyDisplay -> TransferOperationKind.DISPLAY
            else -> throw TransferException(TransferIssue.INVALID_INPUT)
        }
        val writer = Writer()
        writer.int(TransferLimits.PAYLOAD_IDENTITY_SCHEMA); writer.int(kind.wire)
        writer.fingerprint(request.scope.target); writer.fingerprint(peer); writer.uuid(request.requestId)
        when (request) {
            is TransferRequest.BeginUpload -> writer.declaration(request.declaration)
            is TransferRequest.EditPlaylist -> { writer.identifier(request.playlistId); writer.long(request.expectedRevision); writer.optionalLibraryRevision(request.expectedLibraryRevision); writer.playlistEdit(request.edit) }
            is TransferRequest.ApplyDisplay -> { writer.uuid(request.previewId); writer.long(request.expectedRevision); writer.boolean(request.confirmed) }
            else -> throw TransferException(TransferIssue.INVALID_INPUT)
        }
        return TransferOperationBinding(request.requestId, kind, request.scope.target, peer, TransferHash.digest(writer.finish()))
    }

    /** Peer/session/grant are stored separately with the immutable receiver preview record. */
    fun displayPreviewHash(request: TransferRequest.PreviewDisplay): TransferHash = displayPreviewHash(
        request.scope.target, request.kind, request.entityId, request.expectedRevision, request.expectedLibraryRevision, request.patch,
    )

    fun displayPreviewHash(
        target: TransferFingerprint, kind: TransferDisplayKind, entityId: String, expectedRevision: Long,
        expectedLibraryRevision: TransferLibraryRevision, patch: TransferDisplayPatch,
    ): TransferHash {
        requireTransferIdentifier(entityId)
        require(expectedRevision >= 0L && expectedRevision == expectedLibraryRevision.catalogRevision)
        val writer = Writer()
        writer.int(TransferLimits.PAYLOAD_IDENTITY_SCHEMA); writer.fingerprint(target)
        writer.int(kind.wire); writer.identifier(entityId); writer.long(expectedRevision)
        writer.libraryRevision(expectedLibraryRevision); writer.displayPatch(patch)
        return TransferHash.digest(writer.finish())
    }

    private fun requestFrame(request: TransferRequest): Frame {
        val writer = Writer()
        val type = when (request) {
            is TransferRequest.Pair -> { writer.fingerprint(request.target); writer.uuid(request.sessionId); writer.string(request.code, 8, 8); writer.int(capabilityMask(request.requested)); PAIR }
            is TransferRequest.Authenticate -> { writer.fingerprint(request.target); writer.uuid(request.sessionId); AUTHENTICATE }
            is TransferRequest.ReadReceipt -> { writer.scope(request.scope); writer.uuid(request.operationId); writer.int(request.kind.wire); writer.raw(request.payloadHash.wireBytes()); READ_RECEIPT }
            is TransferRequest.CloseCatalogView -> { writer.scope(request.scope); writer.int(request.kind.wire); writer.uuid(request.viewId); CLOSE_CATALOG_VIEW }
            is TransferRequest.ReadPage -> { writer.scope(request.scope); writer.int(request.kind.wire); writer.optionalUuid(request.viewId); writer.optionalLong(request.expectedRevision); writer.optionalLibraryRevision(request.expectedLibraryRevision); writer.optionalCursor(request.cursor); writer.optionalIdentifier(request.parentId); writer.field(request.search); writer.int(request.limit); READ_PAGE }
            is TransferRequest.EditPlaylist -> { writer.scope(request.scope); writer.identifier(request.playlistId); writer.long(request.expectedRevision); writer.optionalLibraryRevision(request.expectedLibraryRevision); writer.playlistEdit(request.edit); EDIT_PLAYLIST }
            is TransferRequest.ReadDisplay -> { writer.scope(request.scope); writer.int(request.kind.wire); writer.identifier(request.entityId); READ_DISPLAY }
            is TransferRequest.PreviewDisplay -> { writer.scope(request.scope); writer.int(request.kind.wire); writer.identifier(request.entityId); writer.long(request.expectedRevision); writer.libraryRevision(request.expectedLibraryRevision); writer.displayPatch(request.patch); PREVIEW_DISPLAY }
            is TransferRequest.ApplyDisplay -> { writer.scope(request.scope); writer.uuid(request.previewId); writer.long(request.expectedRevision); writer.boolean(request.confirmed); APPLY_DISPLAY }
            is TransferRequest.BeginUpload -> { writer.scope(request.scope); writer.declaration(request.declaration); BEGIN_UPLOAD }
            is TransferRequest.UploadChunk -> { writer.scope(request.scope); writer.uuid(request.ticket); writer.long(request.offset); writer.bytes(request.bytes, TransferLimits.CHUNK_BYTES); UPLOAD_CHUNK }
            is TransferRequest.FinishUpload -> { writer.scope(request.scope); writer.uuid(request.ticket); FINISH_UPLOAD }
            is TransferRequest.CancelUpload -> { writer.scope(request.scope); writer.uuid(request.ticket); CANCEL_UPLOAD }
        }
        return Frame(type, request.requestId, writer.finish())
    }

    private fun responseFrame(response: TransferResponse): Frame {
        val writer = Writer()
        val type = when (response) {
            is TransferResponse.Greeting -> { writer.fingerprint(response.target); writer.uuid(response.sessionId); writer.long(response.remainingMs); GREETING }
            is TransferResponse.PairPending -> PAIR_PENDING
            is TransferResponse.Authenticated -> { writer.raw(response.bearer.wireBytes()); writer.int(capabilityMask(response.capabilities)); writer.long(response.remainingMs); AUTHENTICATED }
            is TransferResponse.PageBegin -> { validateBegin(response); writer.int(response.kind.wire); writer.uuid(response.viewId); writer.long(response.revision); writer.libraryRevision(response.libraryRevision); writer.long(response.totalCount); writer.long(response.firstOrdinal); writer.int(response.rowCount); writer.optionalCursor(response.nextCursor); PAGE_BEGIN }
            is TransferResponse.PageRow -> { require(response.revision >= 0L && response.ordinal >= 0L); writer.uuid(response.viewId); writer.long(response.revision); writer.long(response.ordinal); writer.row(response.row); PAGE_ROW }
            is TransferResponse.PageEnd -> { require(response.revision >= 0L && response.rowCount in 0..TransferLimits.PAGE_SIZE && response.encodedBytes in 0..TransferLimits.PAGE_BYTES.toLong()); writer.uuid(response.viewId); writer.long(response.revision); writer.int(response.rowCount); writer.long(response.encodedBytes); writer.raw(response.streamHash.wireBytes()); PAGE_END }
            is TransferResponse.DisplayPreview -> { writer.preview(response.preview); DISPLAY_PREVIEW }
            is TransferResponse.DisplayState -> { writer.displayState(response.state); DISPLAY_STATE }
            is TransferResponse.UploadReady -> { writer.uuid(response.ticket); UPLOAD_READY }
            is TransferResponse.ChunkAccepted -> { require(response.nextOffset >= 0L); writer.uuid(response.ticket); writer.long(response.nextOffset); CHUNK_ACCEPTED }
            is TransferResponse.Completed -> { writer.receipt(response.receipt); writer.boolean(response.replay); COMPLETED }
            is TransferResponse.ReceiptState -> { writer.boolean(response.receipt != null); response.receipt?.let(writer::receipt); RECEIPT_STATE }
            is TransferResponse.Cancelled -> CANCELLED
            is TransferResponse.Failure -> { writer.int(response.issue.ordinal); FAILURE }
            is TransferResponse.Page -> throw TransferException(TransferIssue.INVALID_INPUT)
        }
        requireTransferUuid(response.requestId)
        return Frame(type, response.requestId, writer.finish())
    }

    private fun responseFromFrame(frame: Frame): TransferResponse = parse(frame) { reader -> when (frame.type) {
        GREETING -> TransferResponse.Greeting(frame.id, reader.fingerprint(), reader.uuid(), reader.nonNegativeLong())
        PAIR_PENDING -> TransferResponse.PairPending(frame.id)
        AUTHENTICATED -> TransferResponse.Authenticated(frame.id, TransferBearer.fromWire(reader.raw(32)), capabilitiesFromMask(reader.int()), reader.nonNegativeLong())
        PAGE_BEGIN -> TransferResponse.PageBegin(frame.id, reader.catalogKind(), reader.uuid(), reader.nonNegativeLong(), reader.libraryRevision(), reader.nonNegativeLong(), reader.nonNegativeLong(), reader.int(), reader.optionalCursor()).also(::validateBegin)
        PAGE_ROW -> TransferResponse.PageRow(frame.id, reader.uuid(), reader.nonNegativeLong(), reader.nonNegativeLong(), reader.row())
        PAGE_END -> TransferResponse.PageEnd(frame.id, reader.uuid(), reader.nonNegativeLong(), reader.int(), reader.nonNegativeLong(), TransferHash.fromWire(reader.raw(32)))
        DISPLAY_PREVIEW -> TransferResponse.DisplayPreview(frame.id, reader.preview())
        DISPLAY_STATE -> TransferResponse.DisplayState(frame.id, reader.displayState())
        UPLOAD_READY -> TransferResponse.UploadReady(frame.id, reader.uuid())
        CHUNK_ACCEPTED -> TransferResponse.ChunkAccepted(frame.id, reader.uuid(), reader.nonNegativeLong())
        COMPLETED -> TransferResponse.Completed(frame.id, reader.receipt(), reader.boolean())
        RECEIPT_STATE -> TransferResponse.ReceiptState(frame.id, if (reader.boolean()) reader.receipt() else null)
        CANCELLED -> TransferResponse.Cancelled(frame.id)
        FAILURE -> TransferResponse.Failure(frame.id, TransferIssue.entries.getOrNull(reader.int()) ?: throw TransferException(TransferIssue.INVALID_INPUT))
        else -> throw TransferException(TransferIssue.INVALID_INPUT)
    } }

    private fun readFrame(input: InputStream): Frame {
        val reader = DataInputStream(input)
        if (reader.readInt() != MAGIC) throw TransferException(TransferIssue.INVALID_INPUT)
        if (reader.readUnsignedShort() != TransferLimits.SCHEMA) throw TransferException(TransferIssue.INCOMPATIBLE_SCHEMA)
        val type = reader.readUnsignedByte()
        if (reader.readUnsignedByte() != 0) throw TransferException(TransferIssue.INVALID_INPUT)
        val id = UUID(reader.readLong(), reader.readLong()).toString()
        val length = reader.readInt()
        if (length !in 0..TransferLimits.FRAME_BYTES - HEADER_BYTES) throw TransferException(TransferIssue.INVALID_INPUT)
        return Frame(type, id, ByteArray(length).also(reader::readFully))
    }

    private fun writeFrame(output: OutputStream, frame: Frame) { output.write(frame.header()); output.write(frame.body); output.flush() }
    private fun <T> parse(frame: Frame, action: (Reader) -> T): T = try {
        val reader = Reader(frame.body)
        action(reader).also { reader.end() }
    } catch (_: IllegalArgumentException) { throw TransferException(TransferIssue.INVALID_INPUT) }
      catch (_: IOException) { throw TransferException(TransferIssue.INVALID_INPUT) }

    private fun validateBegin(begin: TransferResponse.PageBegin) {
        requireTransferUuid(begin.requestId); requireTransferUuid(begin.viewId)
        require(begin.revision >= 0L && begin.firstOrdinal >= 0L && begin.totalCount >= begin.firstOrdinal)
        require(begin.rowCount in 0..TransferLimits.PAGE_SIZE && begin.rowCount.toLong() <= begin.totalCount - begin.firstOrdinal)
        begin.nextCursor?.let(::requireTransferCursor)
    }

    private fun rowMatches(kind: TransferCatalogKind, row: TransferCatalogRow): Boolean = when (kind) {
        TransferCatalogKind.TRACKS, TransferCatalogKind.ALBUM_TRACKS, TransferCatalogKind.ARTIST_TRACKS -> row is TransferCatalogRow.Track
        TransferCatalogKind.ALBUMS, TransferCatalogKind.ARTISTS -> row is TransferCatalogRow.Group
        TransferCatalogKind.PLAYLISTS -> row is TransferCatalogRow.Playlist
        TransferCatalogKind.PLAYLIST_MEMBERS -> row is TransferCatalogRow.Member
    }

    private class LimitedOutput : ByteArrayOutputStream(1024) {
        override fun write(value: Int) { checkSize(1); super.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { checkSize(length); super.write(bytes, offset, length) }
        private fun checkSize(additional: Int) { if (additional < 0 || count > TransferLimits.FRAME_BYTES - HEADER_BYTES - additional) throw TransferException(TransferIssue.INVALID_INPUT) }
    }

    private class Writer {
        private val bytes = LimitedOutput()
        private val output = DataOutputStream(bytes)
        fun finish(): ByteArray { output.flush(); return bytes.toByteArray() }
        fun int(value: Int) { output.writeInt(value) }
        fun long(value: Long) { output.writeLong(value) }
        fun boolean(value: Boolean) { output.writeByte(if (value) 1 else 0) }
        fun raw(value: ByteArray) { output.write(value) }
        fun uuid(value: String) { requireTransferUuid(value); val id = UUID.fromString(value); long(id.mostSignificantBits); long(id.leastSignificantBits) }
        fun optionalUuid(value: String?) { boolean(value != null); value?.let(::uuid) }
        fun optionalLong(value: Long?) { boolean(value != null); value?.let(::long) }
        fun libraryRevision(value: TransferLibraryRevision) { long(value.catalogRevision); long(value.rulesRevision) }
        fun optionalLibraryRevision(value: TransferLibraryRevision?) { boolean(value != null); value?.let(::libraryRevision) }
        fun fingerprint(value: TransferFingerprint) { raw(value.wireBytes()) }
        fun scope(value: TransferScope) { fingerprint(value.target); uuid(value.sessionId); raw(value.bearer.wireBytes()) }
        fun string(value: String, maxCharacters: Int, maxBytes: Int) {
            require(value.length <= maxCharacters)
            val buffer = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
            require(buffer.remaining() <= maxBytes)
            val encoded = ByteArray(buffer.remaining()); buffer.get(encoded); int(encoded.size); raw(encoded)
        }
        fun field(value: String) { requireTransferField(value); string(value, TransferLimits.FIELD_CHARACTERS, TransferLimits.FIELD_BYTES) }
        fun identifier(value: String) { requireTransferIdentifier(value); string(value, TransferLimits.IDENTIFIER_CHARACTERS, TransferLimits.IDENTIFIER_CHARACTERS) }
        fun optionalIdentifier(value: String?) { boolean(value != null); value?.let(::identifier) }
        fun cursor(value: String) { requireTransferCursor(value); string(value, TransferLimits.CURSOR_CHARACTERS, TransferLimits.CURSOR_CHARACTERS) }
        fun optionalCursor(value: String?) { boolean(value != null); value?.let(::cursor) }
        fun identifiers(value: List<String>) { require(value.size <= TransferLimits.PAGE_SIZE); int(value.size); value.forEach(::identifier) }
        fun bytes(value: ByteArray, max: Int) { require(value.isNotEmpty() && value.size <= max); int(value.size); raw(value) }
        fun declaration(value: TransferUploadDeclaration) {
            int(value.kind.wire); string(value.displayName, 510, 1024); string(value.mediaType, 128, 128)
            long(value.declaredBytes ?: -1L); raw(value.sha256.wireBytes()); long(value.expectedRevision)
        }
        fun playlistEdit(value: TransferPlaylistEdit) { when (value) {
            is TransferPlaylistEdit.Create -> { int(1); field(value.name) }
            is TransferPlaylistEdit.Rename -> { int(2); field(value.name) }
            is TransferPlaylistEdit.Delete -> { int(3); boolean(value.confirmed) }
            is TransferPlaylistEdit.Add -> { int(4); identifiers(value.trackIds) }
            is TransferPlaylistEdit.Remove -> { int(5); identifier(value.memberId); boolean(value.confirmed) }
            is TransferPlaylistEdit.Move -> { int(6); identifier(value.memberId); long(value.destinationOrdinal) }
        } }
        fun displayPatch(value: TransferDisplayPatch) { when (value) {
            is TransferDisplayPatch.Rename -> { int(1); field(value.displayName) }
            is TransferDisplayPatch.Add -> { int(2); identifiers(value.trackIds) }
            is TransferDisplayPatch.Remove -> { int(3); identifiers(value.trackIds) }
        } }
        fun row(value: TransferCatalogRow) { when (value) {
            is TransferCatalogRow.Track -> { int(1); identifier(value.trackId); field(value.title); field(value.artist); field(value.album); optionalLong(value.durationMs); boolean(value.available) }
            is TransferCatalogRow.Group -> { int(2); identifier(value.entityId); field(value.title); field(value.subtitle); long(value.trackCount); long(value.revision) }
            is TransferCatalogRow.Playlist -> { int(3); identifier(value.playlistId); field(value.name); boolean(value.automatic); long(value.revision); long(value.memberCount) }
            is TransferCatalogRow.Member -> { int(4); identifier(value.memberId); long(value.ordinal); optionalIdentifier(value.trackId); field(value.titleHint); field(value.artistHint); boolean(value.available) }
        } }
        fun displayState(value: TransferDisplayState) { int(value.kind.wire); identifier(value.entityId); long(value.revision); libraryRevision(value.libraryRevision); field(value.currentName); long(value.trackCount) }
        fun preview(value: TransferDisplayPreview) { uuid(value.previewId); int(value.kind.wire); identifier(value.entityId); long(value.revision); field(value.currentName); field(value.proposedName); displayPatch(value.patch); libraryRevision(value.libraryRevision); long(value.currentTrackCount) }
        fun receipt(value: TransferReceipt) {
            val binding = value.binding
            uuid(binding.operationId); int(binding.kind.wire); fingerprint(binding.target); fingerprint(binding.peer); raw(binding.payloadHash.wireBytes())
            int(value.result.wire); long(value.revision); optionalIdentifier(value.resultId); optionalLong(value.actualBytes)
        }
    }

    private class Reader(body: ByteArray) {
        private val input = DataInputStream(ByteArrayInputStream(body))
        fun end() { if (input.available() != 0) throw TransferException(TransferIssue.INVALID_INPUT) }
        fun int(): Int = input.readInt()
        fun long(): Long = input.readLong()
        fun nonNegativeLong(): Long = long().also { require(it >= 0L) }
        fun boolean(): Boolean = when (input.readUnsignedByte()) { 0 -> false; 1 -> true; else -> throw TransferException(TransferIssue.INVALID_INPUT) }
        fun raw(length: Int): ByteArray { require(length >= 0 && length <= input.available()); return ByteArray(length).also(input::readFully) }
        fun uuid(): String = UUID(long(), long()).toString()
        fun optionalUuid(): String? = if (boolean()) uuid() else null
        fun optionalLong(): Long? = if (boolean()) long() else null
        fun libraryRevision(): TransferLibraryRevision = TransferLibraryRevision(nonNegativeLong(), nonNegativeLong())
        fun optionalLibraryRevision(): TransferLibraryRevision? = if (boolean()) libraryRevision() else null
        fun fingerprint(): TransferFingerprint = TransferFingerprint.fromWire(raw(32))
        fun scope(): TransferScope = TransferScope(fingerprint(), uuid(), TransferBearer.fromWire(raw(32)))
        fun string(maxCharacters: Int, maxBytes: Int): String {
            val length = int(); require(length in 0..maxBytes)
            val value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw(length))).toString()
            require(value.length <= maxCharacters); return value
        }
        fun field(): String = string(TransferLimits.FIELD_CHARACTERS, TransferLimits.FIELD_BYTES).also(::requireTransferField)
        fun identifier(): String = string(TransferLimits.IDENTIFIER_CHARACTERS, TransferLimits.IDENTIFIER_CHARACTERS).also(::requireTransferIdentifier)
        fun optionalIdentifier(): String? = if (boolean()) identifier() else null
        fun optionalCursor(): String? = if (boolean()) string(TransferLimits.CURSOR_CHARACTERS, TransferLimits.CURSOR_CHARACTERS).also(::requireTransferCursor) else null
        fun identifiers(): List<String> { val count = int(); require(count in 0..TransferLimits.PAGE_SIZE); return List(count) { identifier() } }
        fun bytes(max: Int): ByteArray { val count = int(); require(count in 1..max); return raw(count) }
        fun catalogKind(): TransferCatalogKind { val value = int(); return TransferCatalogKind.entries.firstOrNull { it.wire == value } ?: throw TransferException(TransferIssue.INVALID_INPUT) }
        fun displayKind(): TransferDisplayKind { val value = int(); return TransferDisplayKind.entries.firstOrNull { it.wire == value } ?: throw TransferException(TransferIssue.INVALID_INPUT) }
        fun operationKind(): TransferOperationKind { val value = int(); return TransferOperationKind.entries.firstOrNull { it.wire == value } ?: throw TransferException(TransferIssue.INVALID_INPUT) }
        fun declaration(): TransferUploadDeclaration {
            val kindCode = int()
            val kind = TransferUploadKind.entries.firstOrNull { it.wire == kindCode } ?: throw TransferException(TransferIssue.INVALID_INPUT)
            val name = string(510, 1024); val mediaType = string(128, 128); val declared = long()
            require(declared == -1L || declared > 0L)
            return TransferUploadDeclaration(kind, name, mediaType, if (declared == -1L) null else declared, TransferHash.fromWire(raw(32)), long())
        }
        fun playlistEdit(): TransferPlaylistEdit = when (int()) {
            1 -> TransferPlaylistEdit.Create(field())
            2 -> TransferPlaylistEdit.Rename(field())
            3 -> TransferPlaylistEdit.Delete(boolean())
            4 -> TransferPlaylistEdit.Add(identifiers())
            5 -> TransferPlaylistEdit.Remove(identifier(), boolean())
            6 -> TransferPlaylistEdit.Move(identifier(), long())
            else -> throw TransferException(TransferIssue.INVALID_INPUT)
        }
        fun displayPatch(): TransferDisplayPatch = when (int()) {
            1 -> TransferDisplayPatch.Rename(field())
            2 -> TransferDisplayPatch.Add(identifiers())
            3 -> TransferDisplayPatch.Remove(identifiers())
            else -> throw TransferException(TransferIssue.INVALID_INPUT)
        }
        fun row(): TransferCatalogRow = when (int()) {
            1 -> TransferCatalogRow.Track(identifier(), field(), field(), field(), optionalLong(), boolean())
            2 -> TransferCatalogRow.Group(identifier(), field(), field(), nonNegativeLong(), nonNegativeLong())
            3 -> TransferCatalogRow.Playlist(identifier(), field(), boolean(), nonNegativeLong(), nonNegativeLong())
            4 -> TransferCatalogRow.Member(identifier(), nonNegativeLong(), optionalIdentifier(), field(), field(), boolean())
            else -> throw TransferException(TransferIssue.INVALID_INPUT)
        }
        fun displayState(): TransferDisplayState = TransferDisplayState(displayKind(), identifier(), nonNegativeLong(), libraryRevision(), field(), nonNegativeLong())
        fun preview(): TransferDisplayPreview = TransferDisplayPreview(uuid(), displayKind(), identifier(), nonNegativeLong(), field(), field(), displayPatch(), libraryRevision(), nonNegativeLong())
        fun receipt(): TransferReceipt {
            val operationId = uuid(); val kindCode = int()
            val kind = TransferOperationKind.entries.firstOrNull { it.wire == kindCode } ?: throw TransferException(TransferIssue.INVALID_INPUT)
            val binding = TransferOperationBinding(operationId, kind, fingerprint(), fingerprint(), TransferHash.fromWire(raw(32)))
            val resultCode = int()
            val result = TransferReceiptKind.entries.firstOrNull { it.wire == resultCode } ?: throw TransferException(TransferIssue.INVALID_INPUT)
            return TransferReceipt(binding, result, nonNegativeLong(), optionalIdentifier(), optionalLong())
        }
    }
}
