package io.github.onlinemo.meloxwatch.data.transfer

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.UUID

/** Engineering limits for LP-13; none of these values represents device acceptance. */
object TransferLimits {
    const val SCHEMA = 3
    // Control-frame extensions must not change durable mutation or preview identities.
    const val PAYLOAD_IDENTITY_SCHEMA = 2
    const val CONNECTIONS = 2
    const val FRAME_BYTES = 64 * 1024
    const val CHUNK_BYTES = 60 * 1024
    const val PAGE_BYTES = 6 * 1024 * 1024
    const val PAGE_SIZE = 100
    const val FIELD_CHARACTERS = 4096
    const val FIELD_BYTES = 16 * 1024
    const val IDENTIFIER_CHARACTERS = 128
    const val CURSOR_CHARACTERS = 512
    const val AUDIO_BYTES = 1024L * 1024 * 1024
    const val THEME_BYTES = 16L * 1024 * 1024
    // The LP-12 total includes old/candidate resources, the package and catalog metadata.
    const val THEME_STORED_BYTES = 2 * 32L * 1024 * 1024 + THEME_BYTES + 1024 * 1024
    const val FREE_RESERVE_BYTES = 512L * 1024 * 1024
    const val SESSION_MS = 10 * 60 * 1000L
    const val PAIRING_MS = 90 * 1000L
    const val TOKEN_MS = 2 * 60 * 1000L
    const val PAIRING_FAILURES = 3
    const val HANDSHAKE_MS = 5000L
    const val IDLE_MS = 10_000L
    const val OPERATION_MS = 60_000L
    const val ACCEPTS_PER_MINUTE = 8
    const val CONTROLS_PER_SECOND = 12
    const val CHUNKS_PER_SECOND = 32
}

enum class TransferIssue {
    BUSY, CLOSED, EXPIRED, UNAUTHENTICATED, NOT_APPROVED, PERMISSION_DENIED,
    WRONG_TARGET, INVALID_INPUT, INCOMPATIBLE_SCHEMA, PAIRING_FAILED, PAIRING_LOCKED,
    RATE_LIMITED, CONFLICT, NOT_FOUND, INVALID_OFFSET, QUOTA_EXCEEDED, STORAGE_FULL,
    STORAGE_UNAVAILABLE, INTEGRITY_FAILED, UNSUPPORTED_TYPE, NOT_AUDIO, INTERRUPTED,
    RECOVERY_REQUIRED, IDENTITY_UNAVAILABLE, TRANSPORT_FAILED,
}

/** Transport/provider exception text can contain addresses; the public error retains only a code. */
class TransferException(val issue: TransferIssue) : Exception(issue.name)

enum class TransferCapability(val bit: Int) {
    UPLOAD_AUDIO(1), READ_LIBRARY(2), WRITE_PLAYLISTS(4), WRITE_DISPLAY_METADATA(8), UPLOAD_THEME(16),
}

internal fun capabilityMask(capabilities: Set<TransferCapability>): Int = capabilities.fold(0) { mask, value -> mask or value.bit }

internal fun <T> transferFrozenSet(values: Set<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(values))
internal fun <T> transferFrozenList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))

internal fun capabilitiesFromMask(mask: Int): Set<TransferCapability> {
    require(mask != 0 && mask and 31 == mask)
    return TransferCapability.entries.filterTo(linkedSetOf()) { mask and it.bit != 0 }
}

/** A certificate/public-key fingerprint is local identity data, never a diagnostic label. */
class TransferFingerprint private constructor(private val value: ByteArray) {
    fun forLocalDisplay(): String = value.joinToString("") { "%02x".format(it.toInt() and 255) }
    internal fun wireBytes(): ByteArray = value.copyOf()
    override fun equals(other: Any?): Boolean = other is TransferFingerprint && MessageDigest.isEqual(value, other.value)
    override fun hashCode(): Int = value.contentHashCode()
    override fun toString(): String = "<local-fingerprint>"

    companion object {
        fun digest(bytes: ByteArray): TransferFingerprint = TransferFingerprint(MessageDigest.getInstance("SHA-256").digest(bytes))
        fun fromWire(bytes: ByteArray): TransferFingerprint {
            require(bytes.size == 32)
            return TransferFingerprint(bytes.copyOf())
        }
    }
}

class TransferHash private constructor(private val value: ByteArray) {
    internal fun wireBytes(): ByteArray = value.copyOf()
    fun forLocalStorage(): String = value.joinToString("") { "%02x".format(it.toInt() and 255) }
    override fun equals(other: Any?): Boolean = other is TransferHash && MessageDigest.isEqual(value, other.value)
    override fun hashCode(): Int = value.contentHashCode()
    override fun toString(): String = "<content-hash>"

    companion object {
        fun digest(bytes: ByteArray): TransferHash = fromWire(MessageDigest.getInstance("SHA-256").digest(bytes))
        fun fromWire(bytes: ByteArray): TransferHash {
            require(bytes.size == 32)
            return TransferHash(bytes.copyOf())
        }
    }
}

class TransferBearer private constructor(private val value: ByteArray) {
    internal fun wireBytes(): ByteArray = value.copyOf()
    internal fun sameAs(other: TransferBearer): Boolean = MessageDigest.isEqual(value, other.value)
    internal fun erase() { value.fill(0) }
    override fun toString(): String = "<session-token>"

    companion object {
        internal fun generate(random: SecureRandom): TransferBearer = TransferBearer(ByteArray(32).also(random::nextBytes))
        fun fromWire(bytes: ByteArray): TransferBearer {
            require(bytes.size == 32)
            return TransferBearer(bytes.copyOf())
        }
    }
}

class TransferScope(val target: TransferFingerprint, val sessionId: String, val bearer: TransferBearer) {
    init { requireTransferUuid(sessionId) }
    override fun toString(): String = "<authenticated-local-scope>"
}

enum class TransferUploadKind(val wire: Int, val capability: TransferCapability, val maximumBytes: Long) {
    AUDIO(1, TransferCapability.UPLOAD_AUDIO, TransferLimits.AUDIO_BYTES),
    THEME(2, TransferCapability.UPLOAD_THEME, TransferLimits.THEME_BYTES),
}

class TransferUploadDeclaration(
    val kind: TransferUploadKind,
    val displayName: String,
    val mediaType: String,
    val declaredBytes: Long?,
    val sha256: TransferHash,
    val expectedRevision: Long,
) {
    init {
        require(displayName.isNotBlank() && displayName.codePointCount(0, displayName.length) <= 255)
        require(displayName.toByteArray(StandardCharsets.UTF_8).size <= 1024)
        require(displayName !in setOf(".", "..") && displayName.none { it == '/' || it == '\\' || it.isISOControl() })
        require(mediaType in if (kind == TransferUploadKind.THEME) THEME_TYPES else AUDIO_TYPES)
        require(declaredBytes == null || declaredBytes in 1..kind.maximumBytes)
        require(expectedRevision >= 0L)
    }
    override fun toString(): String = "<selected-upload>"

    companion object {
        private val AUDIO_TYPES = setOf("audio/wav", "audio/x-wav", "audio/mpeg", "audio/flac", "audio/mp4", "audio/ogg", "audio/aac", "audio/x-aiff")
        private val THEME_TYPES = setOf("application/zip", "application/vnd.melox.theme+zip")
    }
}

enum class TransferCatalogKind(val wire: Int) {
    TRACKS(1), ALBUMS(2), ARTISTS(3), PLAYLISTS(4), PLAYLIST_MEMBERS(5), ALBUM_TRACKS(6), ARTIST_TRACKS(7),
}
enum class TransferDisplayKind(val wire: Int) { ALBUM(1), ARTIST(2) }

/** The real catalog/rules fence is separate from the page lease and playlist revisions. */
class TransferLibraryRevision(val catalogRevision: Long, val rulesRevision: Long) {
    init { require(catalogRevision >= 0L && rulesRevision >= 0L) }
    fun sameAs(other: TransferLibraryRevision): Boolean = catalogRevision == other.catalogRevision && rulesRevision == other.rulesRevision
}

sealed interface TransferPlaylistEdit {
    class Create(val name: String) : TransferPlaylistEdit { init { requireTransferPlaylistName(name) } }
    class Rename(val name: String) : TransferPlaylistEdit { init { requireTransferPlaylistName(name) } }
    class Delete(val confirmed: Boolean) : TransferPlaylistEdit
    class Add(trackIds: List<String>) : TransferPlaylistEdit {
        val trackIds: List<String> = transferFrozenList(trackIds)
        init { require(trackIds.isNotEmpty() && trackIds.size <= TransferLimits.PAGE_SIZE); trackIds.forEach(::requireTransferIdentifier) }
    }
    class Remove(val memberId: String, val confirmed: Boolean) : TransferPlaylistEdit { init { requireTransferIdentifier(memberId) } }
    class Move(val memberId: String, val destinationOrdinal: Long) : TransferPlaylistEdit {
        init { requireTransferIdentifier(memberId); require(destinationOrdinal >= 0L) }
    }
}

/** Bounded changes to one exact display group; a batch limit is never a group membership cap. */
sealed interface TransferDisplayPatch {
    class Rename(val displayName: String) : TransferDisplayPatch {
        init { require(displayName.isNotBlank()); requireTransferField(displayName) }
    }
    class Add(trackIds: List<String>) : TransferDisplayPatch {
        val trackIds: List<String> = transferFrozenList(trackIds)
        init { require(trackIds.isNotEmpty() && trackIds.size <= TransferLimits.PAGE_SIZE && trackIds.distinct().size == trackIds.size); trackIds.forEach(::requireTransferIdentifier) }
    }
    class Remove(trackIds: List<String>) : TransferDisplayPatch {
        val trackIds: List<String> = transferFrozenList(trackIds)
        init { require(trackIds.isNotEmpty() && trackIds.size <= TransferLimits.PAGE_SIZE && trackIds.distinct().size == trackIds.size); trackIds.forEach(::requireTransferIdentifier) }
    }
    fun sameAs(other: TransferDisplayPatch): Boolean = when (this) {
        is Rename -> other is Rename && displayName == other.displayName
        is Add -> other is Add && trackIds == other.trackIds
        is Remove -> other is Remove && trackIds == other.trackIds
    }
}

/** Minimum authoritative state needed by WRITE_DISPLAY_METADATA, without granting a library read. */
class TransferDisplayState(
    val kind: TransferDisplayKind, val entityId: String, val revision: Long,
    val libraryRevision: TransferLibraryRevision, val currentName: String, val trackCount: Long,
) {
    init {
        requireTransferIdentifier(entityId); require(revision >= 0L && revision == libraryRevision.catalogRevision)
        requireTransferField(currentName); require(trackCount >= 0L)
    }
    override fun toString(): String = "<authorized-display-state>"
}

sealed interface TransferRequest {
    val requestId: String

    class Pair(
        override val requestId: String,
        val target: TransferFingerprint,
        val sessionId: String,
        val code: String,
        requested: Set<TransferCapability>,
    ) : TransferRequest {
        val requested: Set<TransferCapability> = transferFrozenSet(requested)
        init { requireTransferUuid(requestId); requireTransferUuid(sessionId); require(code.matches(Regex("[0-9]{8}"))); require(requested.isNotEmpty()) }
        override fun toString(): String = "<pair-request>"
    }
    class Authenticate(override val requestId: String, val target: TransferFingerprint, val sessionId: String) : TransferRequest {
        init { requireTransferUuid(requestId); requireTransferUuid(sessionId) }
    }
    sealed interface Authorized : TransferRequest { val scope: TransferScope }
    class ReadReceipt(
        override val requestId: String, override val scope: TransferScope,
        val operationId: String, val kind: TransferOperationKind, val payloadHash: TransferHash,
    ) : Authorized {
        init { requireTransferUuid(requestId); requireTransferUuid(operationId) }
    }
    class CloseCatalogView(
        override val requestId: String, override val scope: TransferScope,
        val kind: TransferCatalogKind, val viewId: String,
    ) : Authorized {
        init { requireTransferUuid(requestId); requireTransferUuid(viewId) }
    }
    class ReadPage(
        override val requestId: String,
        override val scope: TransferScope,
        val kind: TransferCatalogKind,
        val viewId: String?,
        val expectedRevision: Long?,
        val expectedLibraryRevision: TransferLibraryRevision?,
        val cursor: String?,
        val parentId: String?,
        val search: String,
        val limit: Int,
    ) : Authorized {
        init {
            requireTransferUuid(requestId); viewId?.let(::requireTransferUuid); parentId?.let(::requireTransferIdentifier)
            require((viewId == null) == (expectedRevision == null))
            require((viewId == null) == (expectedLibraryRevision == null))
            require(expectedRevision == null || expectedRevision >= 0L)
            require(cursor == null || viewId != null)
            cursor?.let(::requireTransferCursor)
            require((kind in setOf(TransferCatalogKind.PLAYLIST_MEMBERS, TransferCatalogKind.ALBUM_TRACKS, TransferCatalogKind.ARTIST_TRACKS)) == (parentId != null))
            requireTransferField(search)
            require(limit in 1..TransferLimits.PAGE_SIZE)
        }
    }
    class EditPlaylist(
        override val requestId: String,
        override val scope: TransferScope,
        val playlistId: String,
        val expectedRevision: Long,
        val expectedLibraryRevision: TransferLibraryRevision?,
        val edit: TransferPlaylistEdit,
    ) : Authorized {
        init {
            requireTransferUuid(requestId); requireTransferIdentifier(playlistId); require(expectedRevision >= 0L)
            require(edit !is TransferPlaylistEdit.Add || expectedLibraryRevision != null)
            require(edit !is TransferPlaylistEdit.Create || expectedRevision == 0L)
        }
    }
    class ReadDisplay(
        override val requestId: String, override val scope: TransferScope,
        val kind: TransferDisplayKind, val entityId: String,
    ) : Authorized {
        init { requireTransferUuid(requestId); requireTransferIdentifier(entityId) }
    }
    class PreviewDisplay(
        override val requestId: String,
        override val scope: TransferScope,
        val kind: TransferDisplayKind,
        val entityId: String,
        val expectedRevision: Long,
        val expectedLibraryRevision: TransferLibraryRevision,
        val patch: TransferDisplayPatch,
    ) : Authorized {
        init {
            requireTransferUuid(requestId); requireTransferIdentifier(entityId); require(expectedRevision >= 0L)
            require(expectedRevision == expectedLibraryRevision.catalogRevision)
        }
    }
    class ApplyDisplay(
        override val requestId: String,
        override val scope: TransferScope,
        val previewId: String,
        val expectedRevision: Long,
        val confirmed: Boolean,
    ) : Authorized {
        init { requireTransferUuid(requestId); requireTransferUuid(previewId); require(expectedRevision >= 0L) }
    }
    class BeginUpload(override val requestId: String, override val scope: TransferScope, val declaration: TransferUploadDeclaration) : Authorized {
        init { requireTransferUuid(requestId) }
    }
    class UploadChunk(
        override val requestId: String,
        override val scope: TransferScope,
        val ticket: String,
        val offset: Long,
        bytes: ByteArray,
    ) : Authorized {
        private val payload = bytes.copyOf()
        val bytes: ByteArray get() = payload.copyOf()
        init { requireTransferUuid(requestId); requireTransferUuid(ticket); require(offset >= 0L); require(bytes.isNotEmpty() && bytes.size <= TransferLimits.CHUNK_BYTES) }
    }
    class FinishUpload(override val requestId: String, override val scope: TransferScope, val ticket: String) : Authorized {
        init { requireTransferUuid(requestId); requireTransferUuid(ticket) }
    }
    class CancelUpload(override val requestId: String, override val scope: TransferScope, val ticket: String) : Authorized {
        init { requireTransferUuid(requestId); requireTransferUuid(ticket) }
    }
}

enum class TransferOperationKind(val wire: Int, val capability: TransferCapability) {
    AUDIO(1, TransferCapability.UPLOAD_AUDIO), THEME(2, TransferCapability.UPLOAD_THEME),
    PLAYLIST(3, TransferCapability.WRITE_PLAYLISTS), DISPLAY(4, TransferCapability.WRITE_DISPLAY_METADATA),
}

/** Persist this binding locally alongside the real operation; credentials/session tokens are excluded. */
class TransferOperationBinding(
    val operationId: String,
    val kind: TransferOperationKind,
    val target: TransferFingerprint,
    val peer: TransferFingerprint,
    val payloadHash: TransferHash,
) {
    init { requireTransferUuid(operationId) }
    fun sameOperation(other: TransferOperationBinding): Boolean =
        operationId == other.operationId && kind == other.kind && target == other.target && peer == other.peer && payloadHash == other.payloadHash
    override fun toString(): String = "<local-operation-binding>"
}

enum class TransferReceiptKind(val wire: Int) { AUDIO_COMMITTED(1), THEME_PENDING_CONFIRMATION(2), PLAYLIST_COMMITTED(3), DISPLAY_COMMITTED(4) }

class TransferReceipt(
    val binding: TransferOperationBinding,
    val result: TransferReceiptKind,
    val revision: Long,
    val resultId: String?,
    val actualBytes: Long?,
) {
    init {
        require(revision >= 0L); resultId?.let(::requireTransferIdentifier)
        require(result == when (binding.kind) {
            TransferOperationKind.AUDIO -> TransferReceiptKind.AUDIO_COMMITTED
            TransferOperationKind.THEME -> TransferReceiptKind.THEME_PENDING_CONFIRMATION
            TransferOperationKind.PLAYLIST -> TransferReceiptKind.PLAYLIST_COMMITTED
            TransferOperationKind.DISPLAY -> TransferReceiptKind.DISPLAY_COMMITTED
        })
        val maximum = when (binding.kind) {
            TransferOperationKind.AUDIO -> TransferLimits.AUDIO_BYTES
            TransferOperationKind.THEME -> TransferLimits.THEME_BYTES
            else -> null
        }
        if (maximum == null) require(actualBytes == null)
        else require(resultId != null && actualBytes != null && actualBytes in 1..maximum)
    }
    override fun toString(): String = "<committed-local-operation>"
}

/** Explicit whitelist: no source URI, location, filename, credentials or arbitrary entity serialization. */
sealed interface TransferCatalogRow {
    class Track(val trackId: String, val title: String, val artist: String, val album: String, val durationMs: Long?, val available: Boolean) : TransferCatalogRow {
        init { requireTransferIdentifier(trackId); listOf(title, artist, album).forEach(::requireTransferField); require(durationMs == null || durationMs > 0L) }
    }
    class Group(val entityId: String, val title: String, val subtitle: String, val trackCount: Long, val revision: Long) : TransferCatalogRow {
        init { requireTransferIdentifier(entityId); requireTransferField(title); requireTransferField(subtitle); require(trackCount >= 0L && revision >= 0L) }
    }
    class Playlist(val playlistId: String, val name: String, val automatic: Boolean, val revision: Long, val memberCount: Long) : TransferCatalogRow {
        init { requireTransferIdentifier(playlistId); requireTransferField(name); require(revision >= 0L && memberCount >= 0L) }
    }
    class Member(val memberId: String, val ordinal: Long, val trackId: String?, val titleHint: String, val artistHint: String, val available: Boolean) : TransferCatalogRow {
        init { requireTransferIdentifier(memberId); require(ordinal >= 0L); trackId?.let(::requireTransferIdentifier); requireTransferField(titleHint); requireTransferField(artistHint) }
    }
}

class TransferCatalogPage(
    val kind: TransferCatalogKind,
    val viewId: String,
    val revision: Long,
    val libraryRevision: TransferLibraryRevision,
    val totalCount: Long,
    val firstOrdinal: Long,
    rows: List<TransferCatalogRow>,
    val nextCursor: String?,
) {
    val rows: List<TransferCatalogRow> = transferFrozenList(rows)
    init {
        requireTransferUuid(viewId); require(revision >= 0L && firstOrdinal >= 0L && totalCount >= firstOrdinal)
        require(rows.size.toLong() <= totalCount - firstOrdinal)
        require(rows.size <= TransferLimits.PAGE_SIZE); nextCursor?.let(::requireTransferCursor)
        require(rows.all { row -> when (kind) {
            TransferCatalogKind.TRACKS, TransferCatalogKind.ALBUM_TRACKS, TransferCatalogKind.ARTIST_TRACKS -> row is TransferCatalogRow.Track
            TransferCatalogKind.ALBUMS, TransferCatalogKind.ARTISTS -> row is TransferCatalogRow.Group && row.revision == libraryRevision.catalogRevision
            TransferCatalogKind.PLAYLISTS -> row is TransferCatalogRow.Playlist
            TransferCatalogKind.PLAYLIST_MEMBERS -> row is TransferCatalogRow.Member
        } })
    }
}

class TransferDisplayPreview(
    val previewId: String,
    val kind: TransferDisplayKind,
    val entityId: String,
    val revision: Long,
    val currentName: String,
    val proposedName: String,
    val patch: TransferDisplayPatch,
    val libraryRevision: TransferLibraryRevision,
    val currentTrackCount: Long,
) {
    init {
        requireTransferUuid(previewId); requireTransferIdentifier(entityId); require(revision >= 0L && revision == libraryRevision.catalogRevision && currentTrackCount >= 0L)
        requireTransferField(currentName); requireTransferField(proposedName)
        require(proposedName == if (patch is TransferDisplayPatch.Rename) patch.displayName else currentName)
    }
}

sealed interface TransferResponse {
    val requestId: String
    class Greeting(override val requestId: String, val target: TransferFingerprint, val sessionId: String, val remainingMs: Long) : TransferResponse
    class PairPending(override val requestId: String) : TransferResponse
    class Authenticated(override val requestId: String, val bearer: TransferBearer, capabilities: Set<TransferCapability>, val remainingMs: Long) : TransferResponse {
        val capabilities: Set<TransferCapability> = transferFrozenSet(capabilities)
    }
    class Page(override val requestId: String, val page: TransferCatalogPage) : TransferResponse
    class PageBegin(
        override val requestId: String, val kind: TransferCatalogKind, val viewId: String,
        val revision: Long, val libraryRevision: TransferLibraryRevision,
        val totalCount: Long, val firstOrdinal: Long, val rowCount: Int, val nextCursor: String?,
    ) : TransferResponse
    class PageRow(
        override val requestId: String, val viewId: String, val revision: Long, val ordinal: Long, val row: TransferCatalogRow,
    ) : TransferResponse
    class PageEnd(
        override val requestId: String, val viewId: String, val revision: Long,
        val rowCount: Int, val encodedBytes: Long, val streamHash: TransferHash,
    ) : TransferResponse
    class DisplayPreview(override val requestId: String, val preview: TransferDisplayPreview) : TransferResponse
    class DisplayState(override val requestId: String, val state: TransferDisplayState) : TransferResponse
    class UploadReady(override val requestId: String, val ticket: String) : TransferResponse
    class ChunkAccepted(override val requestId: String, val ticket: String, val nextOffset: Long) : TransferResponse
    class Completed(override val requestId: String, val receipt: TransferReceipt, val replay: Boolean) : TransferResponse
    class ReceiptState(override val requestId: String, val receipt: TransferReceipt?) : TransferResponse
    class Cancelled(override val requestId: String) : TransferResponse
    class Failure(override val requestId: String, val issue: TransferIssue) : TransferResponse
}

internal fun requireTransferUuid(value: String) { require(value.length == 36 && UUID.fromString(value).toString() == value) }
internal fun requireTransferIdentifier(value: String) { require(value.matches(Regex("[A-Za-z0-9:_-]{1,128}"))) }
internal fun requireTransferCursor(value: String) { require(value.isNotEmpty() && value.length <= TransferLimits.CURSOR_CHARACTERS && value.matches(Regex("[A-Za-z0-9=_-]+"))) }
internal fun requireTransferField(value: String) {
    require(value.length <= TransferLimits.FIELD_CHARACTERS)
    require(value.toByteArray(StandardCharsets.UTF_8).size <= TransferLimits.FIELD_BYTES)
    require(value.none { it == '\u0000' })
}
internal fun requireTransferPlaylistName(value: String) {
    require(value.isNotBlank() && value.codePointCount(0, value.length) <= 80)
    requireTransferField(value)
}
