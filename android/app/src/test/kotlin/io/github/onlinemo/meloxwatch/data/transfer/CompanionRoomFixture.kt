package io.github.onlinemo.meloxwatch.data.transfer

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.library.LibraryCursor
import io.github.onlinemo.meloxwatch.data.library.LibraryLimits
import io.github.onlinemo.meloxwatch.data.library.LibraryQuery
import io.github.onlinemo.meloxwatch.data.library.LibrarySort
import io.github.onlinemo.meloxwatch.data.library.LibrarySource
import io.github.onlinemo.meloxwatch.data.library.LibraryTrack
import io.github.onlinemo.meloxwatch.data.library.LocalAudioMetadata
import io.github.onlinemo.meloxwatch.data.library.RoomLocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.library.RoomPlaylistFences
import io.github.onlinemo.meloxwatch.data.local.LocalGrantObservation
import io.github.onlinemo.meloxwatch.data.local.LocalNodeKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceKind
import io.github.onlinemo.meloxwatch.data.local.LocalSourceSpec
import io.github.onlinemo.meloxwatch.data.local.ObservedCandidate
import io.github.onlinemo.meloxwatch.data.local.ObservedCapability
import io.github.onlinemo.meloxwatch.data.playlists.FrozenPlaylistInput
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistDao
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistInputPage
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutation
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutationRequest
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistPageSource
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistReference
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistSnapshot
import io.github.onlinemo.meloxwatch.data.playlists.RoomPlaylistRepository
import io.github.onlinemo.meloxwatch.data.scan.MetadataOutcome
import io.github.onlinemo.meloxwatch.data.scan.ScanCounters
import io.github.onlinemo.meloxwatch.data.scan.ScanCoverage
import io.github.onlinemo.meloxwatch.data.scan.ScanMode
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals

/** Real Room owners over native SQLite. This fixture never supplies a successful upload receipt. */
internal class CompanionRoomFixture(private val context: Context, private val file: File) : AutoCloseable {
    var elapsed = 1_000L
    var epoch = 1_790_000_000_000L
    val clock = TransferClock { elapsed }
    val target = TransferFingerprint.digest("synthetic-room-watch-key".toByteArray())
    val peer = TransferFingerprint.digest("synthetic-room-phone-key".toByteArray())
    val authority = TransferSessionAuthority(clock)
    lateinit var scope: TransferScope
        private set
    var database = openDatabase()
        private set
    lateinit var library: RoomLocalLibraryRepository
        private set
    lateinit var playlists: RoomPlaylistRepository
        private set
    lateinit var backend: RoomCompanionTransferBackend
        private set

    suspend fun initialize() {
        val fences = RoomPlaylistFences(database)
        library = RoomLocalLibraryRepository(database, { elapsed }, { epoch })
        playlists = RoomPlaylistRepository(database, database.playlistDao(), { elapsed }, { epoch },
            processId = "room-fixture-playlists", folderFenceVerifier = fences, libraryFenceVerifier = fences)
        library.initialize()
        playlists.initialize()
        backend = RoomCompanionTransferBackend(database, library, playlists, NoUploadOwner, clock,
            processId = "room-fixture-backend")
        newSession()
    }

    fun newSession(capabilities: Set<TransferCapability> = READ_WRITE) {
        authority.stop()
        val opening = authority.begin(target)
        authority.pair(peer, TransferRequest.Pair(uuid(), target, opening.sessionId,
            checkNotNull(authority.pairingCodeForLocalUi()), capabilities))
        authority.approveForLocalUi(peer, capabilities)
        val authentication = authority.authenticate(peer, target, opening.sessionId)
        scope = TransferScope(target, opening.sessionId, authentication.bearer)
    }

    fun renewToken() {
        val authentication = authority.authenticate(peer, target, scope.sessionId)
        scope = TransferScope(target, scope.sessionId, authentication.bearer)
    }

    fun permit(capability: TransferCapability) = authority.authorize(peer, scope, capability)

    suspend fun source(id: String = "room-source"): LibrarySource = library.registerSource(
        LocalSourceSpec(id, LocalSourceKind.TREE, "content://companion-room-fixture/tree/$id",
            authority = "companion-room-fixture", rootObjectId = "root-$id", providerIncarnation = "fixture"),
        LocalGrantObservation(read = ObservedCapability.AVAILABLE, enumerate = ObservedCapability.AVAILABLE),
    )

    fun audio(source: LibrarySource, id: String, filename: String = "$id.mp3") = ObservedCandidate(
        source.spec.sourceId, id, "content://companion-room-fixture/document/${source.spec.sourceId}/$id",
        source.spec.rootObjectId, filename, "audio/mpeg", LocalNodeKind.AUDIO, 128L,
        providerContentToken = "native-fixture:$id:1",
    )

    suspend fun append(source: LibrarySource, metadata: List<Pair<String, LocalAudioMetadata>>) {
        val token = library.beginScan(source.spec.sourceId,
            checkNotNull(library.source(source.spec.sourceId)).authEpoch, library.rules().revision, ScanMode.INCREMENTAL)
        val root = ObservedCandidate(source.spec.sourceId, checkNotNull(source.spec.rootObjectId), source.spec.uri,
            null, source.spec.sourceId, "vnd.android.document/directory", LocalNodeKind.DIRECTORY)
        library.commitSeen(token, listOf(root))
        for (batch in metadata.chunked(LibraryLimits.PAGE_SIZE)) {
            library.commitSeen(token, batch.map { audio(source, it.first) })
            library.commitMetadata(token, batch.map { MetadataOutcome(it.first, it.second) })
        }
        while (true) {
            val directory = library.nextDirectory(token) ?: break
            library.completeDirectory(token, directory.objectId, true)
        }
        val coverage = ScanCoverage(true, true, true, true, false)
        library.setCoverage(token, coverage)
        library.finishScan(token, ScanCounters(), coverage)
    }

    suspend fun tracks(query: LibraryQuery = LibraryQuery(), sort: LibrarySort = LibrarySort()): List<LibraryTrack> {
        val view = library.openView(query, sort)
        try {
            val result = mutableListOf<LibraryTrack>()
            var cursor: LibraryCursor? = null
            do {
                val page = library.page(view, cursor)
                result += page.tracks
                cursor = page.nextCursor
            } while (cursor != null)
            return result
        } finally { library.closeView(view.viewId) }
    }

    suspend fun firstPage(kind: TransferCatalogKind = TransferCatalogKind.TRACKS, parentId: String? = null,
        search: String = "", limit: Int = 100, capability: TransferCapability = TransferCapability.READ_LIBRARY) =
        backend.readPage(TransferRequest.ReadPage(uuid(), scope, kind, null, null, null, null, parentId, search, limit), permit(capability))

    suspend fun nextPage(page: TransferCatalogPage, parentId: String? = null, search: String = "", limit: Int = 100,
        capability: TransferCapability = TransferCapability.READ_LIBRARY) = backend.readPage(
        TransferRequest.ReadPage(uuid(), scope, page.kind, page.viewId, page.revision, page.libraryRevision,
            checkNotNull(page.nextCursor), parentId, search, limit), permit(capability))

    /** Use raw page helpers when a test needs to retain a frozen lease or pending preview. */
    suspend fun rows(kind: TransferCatalogKind, parentId: String? = null, search: String = "",
        capability: TransferCapability = TransferCapability.READ_LIBRARY): List<TransferCatalogRow> {
        try {
            val result = mutableListOf<TransferCatalogRow>()
            var page = firstPage(kind, parentId, search, capability = capability)
            while (true) {
                result += page.rows
                if (page.nextCursor == null) break
                page = nextPage(page, parentId, search, capability = capability)
            }
            return result
        } finally { backend.closePeerViews(peer, scope.sessionId) }
    }

    suspend fun group(kind: TransferDisplayKind, name: String, subtitle: String? = null): TransferCatalogRow.Group =
        rows(if (kind == TransferDisplayKind.ALBUM) TransferCatalogKind.ALBUMS else TransferCatalogKind.ARTISTS,
            capability = TransferCapability.WRITE_DISPLAY_METADATA).filterIsInstance<TransferCatalogRow.Group>()
            .single { it.title == name && (subtitle == null || it.subtitle == subtitle) }

    suspend fun state(kind: TransferDisplayKind, id: String) = backend.readDisplay(
        TransferRequest.ReadDisplay(uuid(), scope, kind, id), permit(TransferCapability.WRITE_DISPLAY_METADATA))

    suspend fun preview(state: TransferDisplayState, patch: TransferDisplayPatch, requestId: String = uuid()) =
        backend.previewDisplay(TransferRequest.PreviewDisplay(requestId, scope, state.kind, state.entityId,
            state.revision, state.libraryRevision, patch), permit(TransferCapability.WRITE_DISPLAY_METADATA))

    suspend fun apply(preview: TransferDisplayPreview, operationId: String = uuid(), confirmed: Boolean = true): TransferReceipt {
        val request = TransferRequest.ApplyDisplay(operationId, scope, preview.previewId, preview.revision, confirmed)
        return backend.applyDisplay(TransferProtocolCodec.operationBinding(peer, request), request,
            permit(TransferCapability.WRITE_DISPLAY_METADATA))
    }

    suspend fun edit(playlistId: String, revision: Long, edit: TransferPlaylistEdit, operationId: String = uuid(),
        fence: TransferLibraryRevision? = null): TransferReceipt {
        val request = TransferRequest.EditPlaylist(operationId, scope, playlistId, revision, fence, edit)
        return backend.editPlaylist(TransferProtocolCodec.operationBinding(peer, request), request,
            permit(TransferCapability.WRITE_PLAYLISTS))
    }

    suspend fun nativePlaylist(id: String, references: List<PlaylistReference>, name: String = "Fixture playlist"): PlaylistSnapshot {
        val frozen = FrozenPlaylistInput(uuid(), library.overview().revision, references.size.toLong())
        val source = PlaylistPageSource { offset, limit ->
            val page = references.drop(offset.toInt()).take(limit)
            PlaylistInputPage(frozen, offset, page, offset + page.size == references.size.toLong())
        }
        return checkNotNull(playlists.apply(PlaylistMutationRequest(uuid(), id, 0L, PlaylistMutation.Create(name, frozen)), source).snapshot)
    }

    suspend fun reopen() {
        database.close()
        database = openDatabase()
        initialize()
    }

    fun backendWithPlaylistDao(dao: PlaylistDao, processId: String = "room-fixture-backend"): RoomCompanionTransferBackend {
        val fences = RoomPlaylistFences(database)
        val owner = RoomPlaylistRepository(database, dao, { elapsed }, { epoch },
            processId = "room-fixture-playlists", folderFenceVerifier = fences, libraryFenceVerifier = fences)
        return RoomCompanionTransferBackend(database, library, owner, NoUploadOwner, clock,
            processId = processId)
    }

    fun backendWithUploadOwner(owner: TransferBackend) = RoomCompanionTransferBackend(
        database, library, playlists, owner, clock, processId = "room-fixture-backend")

    override fun close() { authority.stop(); database.close() }

    private fun openDatabase() = Room.databaseBuilder(context, MeloXDatabase::class.java, file.absolutePath)
        .setDriver(BundledSQLiteDriver()).addMigrations(MeloXDatabase.MIGRATION_1_2, MeloXDatabase.MIGRATION_2_3,
            MeloXDatabase.MIGRATION_3_4, MeloXDatabase.MIGRATION_4_5, MeloXDatabase.MIGRATION_5_6).build()

    companion object {
        fun uuid() = UUID.randomUUID().toString()
        private val READ_WRITE = setOf(TransferCapability.READ_LIBRARY, TransferCapability.WRITE_PLAYLISTS,
            TransferCapability.WRITE_DISPLAY_METADATA)
    }

    private object NoUploadOwner : TransferBackend {
        override suspend fun committed(binding: TransferOperationBinding, permit: TransferPermit): TransferReceipt? = unavailable(permit)
        override suspend fun commitUpload(binding: TransferOperationBinding, upload: TransferVerifiedUpload, permit: TransferPermit): TransferReceipt = unavailable(permit)
        override suspend fun readPage(request: TransferRequest.ReadPage, permit: TransferPermit): TransferCatalogPage = unavailable(permit)
        override suspend fun readDisplay(request: TransferRequest.ReadDisplay, permit: TransferPermit): TransferDisplayState = unavailable(permit)
        override suspend fun editPlaylist(binding: TransferOperationBinding, request: TransferRequest.EditPlaylist, permit: TransferPermit): TransferReceipt = unavailable(permit)
        override suspend fun previewDisplay(request: TransferRequest.PreviewDisplay, permit: TransferPermit): TransferDisplayPreview = unavailable(permit)
        override suspend fun applyDisplay(binding: TransferOperationBinding, request: TransferRequest.ApplyDisplay, permit: TransferPermit): TransferReceipt = unavailable(permit)
        override suspend fun closePeerViews(peer: TransferFingerprint, sessionId: String) = Unit
        private fun unavailable(permit: TransferPermit): Nothing { permit.checkActive(); throw TransferException(TransferIssue.PERMISSION_DENIED) }
    }
}

internal suspend fun expectTransferIssue(issue: TransferIssue, action: suspend () -> Any?) {
    try { action() } catch (error: TransferException) { assertEquals(issue, error.issue); return }
    throw AssertionError("Expected $issue")
}
