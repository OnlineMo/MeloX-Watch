package io.github.onlinemo.meloxwatch.data.transfer

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteException as PlatformSQLiteException
import android.os.SystemClock
import io.github.onlinemo.meloxwatch.data.database.MeloXDatabase
import io.github.onlinemo.meloxwatch.data.database.withPreservedTransaction
import io.github.onlinemo.meloxwatch.data.library.DisplayAlbumLinkEntity
import io.github.onlinemo.meloxwatch.data.library.DisplayArtistLinkEntity
import io.github.onlinemo.meloxwatch.data.library.DisplayGroupEntity
import io.github.onlinemo.meloxwatch.data.library.DisplayNameEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryAvailability
import io.github.onlinemo.meloxwatch.data.library.LibraryCursor
import io.github.onlinemo.meloxwatch.data.library.LibraryException
import io.github.onlinemo.meloxwatch.data.library.LibraryGroupKind
import io.github.onlinemo.meloxwatch.data.library.LibraryGroupRecord
import io.github.onlinemo.meloxwatch.data.library.LibraryIssue
import io.github.onlinemo.meloxwatch.data.library.LibraryLimits
import io.github.onlinemo.meloxwatch.data.library.LibraryMetaEntity
import io.github.onlinemo.meloxwatch.data.library.LibraryQuery
import io.github.onlinemo.meloxwatch.data.library.LibraryRowRecord
import io.github.onlinemo.meloxwatch.data.library.LibrarySort
import io.github.onlinemo.meloxwatch.data.library.LibrarySortField
import io.github.onlinemo.meloxwatch.data.library.LibrarySortKeys
import io.github.onlinemo.meloxwatch.data.library.LibrarySql
import io.github.onlinemo.meloxwatch.data.library.LibraryViewOwner
import io.github.onlinemo.meloxwatch.data.library.LibraryViewToken
import io.github.onlinemo.meloxwatch.data.library.RoomLocalLibraryRepository
import io.github.onlinemo.meloxwatch.data.library.cursor
import io.github.onlinemo.meloxwatch.data.library.model as libraryModel
import io.github.onlinemo.meloxwatch.data.playlists.FrozenPlaylistInput
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCatalogCursor
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCatalogView
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCommit
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistCommitParticipant
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistException
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistInputPage
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistIssue
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistKind
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistLibraryRevision
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutation
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistMutationRequest
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistPageSource
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistReference
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistViewOwner
import io.github.onlinemo.meloxwatch.data.playlists.PlaylistViewToken
import io.github.onlinemo.meloxwatch.data.playlists.RoomPlaylistRepository
import io.github.onlinemo.meloxwatch.data.playlists.model as playlistModel
import io.github.onlinemo.meloxwatch.model.TrackKey
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Real Room domains for LP-14. Upload owners keep their physical worker and publication transaction. */
class RoomCompanionTransferBackend(
    private val database: MeloXDatabase,
    private val library: RoomLocalLibraryRepository,
    private val playlists: RoomPlaylistRepository,
    private val uploads: TransferBackend,
    private val clock: TransferClock = TransferClock { SystemClock.elapsedRealtime() },
    private val processId: String = UUID.randomUUID().toString(),
) : TransferBackend {
    private val dao = database.libraryDao()
    private val display = database.displayDao()
    private val transfer = database.transferRoomDao()
    private val ledger = TransferRoomLedger(database)
    private val viewMutex = Mutex()
    private val views = linkedMapOf<String, CatalogLease>()

    init {
        require(library.database === database && playlists.database === database)
        require(processId.isNotBlank() && processId.length <= TransferLimits.IDENTIFIER_CHARACTERS)
    }

    override suspend fun readReceipt(binding: TransferOperationBinding, permit: TransferPermit): TransferReceipt? = storage {
        library.transferTransaction { ledger.committedInCurrentTransaction(binding, permit) }
    }

    override suspend fun committed(binding: TransferOperationBinding, permit: TransferPermit): TransferReceipt? = storage {
        val original = library.transferTransaction { ledger.committedInCurrentTransaction(binding, permit) }
        if (original != null) return@storage original
        if (binding.kind != TransferOperationKind.AUDIO && binding.kind != TransferOperationKind.THEME) return@storage null
        // Installed input can resume only through its actual owner with fresh exact authority.
        // An old completion without its canonical receipt is rejected, never supplemented here.
        val resumed = uploads.committed(binding, permit) ?: return@storage null
        requireOwnerReceipt(binding, resumed)
    }

    override suspend fun commitUpload(binding: TransferOperationBinding, upload: TransferVerifiedUpload,
        permit: TransferPermit): TransferReceipt = storage {
        val kind = if (upload.declaration.kind == TransferUploadKind.AUDIO) TransferOperationKind.AUDIO else TransferOperationKind.THEME
        if (binding.kind != kind) fail(TransferIssue.CONFLICT)
        val replay = library.transferTransaction {
            ledger.bindInCurrentTransaction(binding, permit)
            ledger.committedInCurrentTransaction(binding, permit)
        }
        if (replay != null) return@storage replay
        val receipt = uploads.commitUpload(binding, upload, permit)
        requireOwnerReceipt(binding, receipt)
    }

    private suspend fun requireOwnerReceipt(binding: TransferOperationBinding, receipt: TransferReceipt): TransferReceipt {
        if (!receipt.binding.sameOperation(binding)) fail(TransferIssue.CONFLICT)
        // This checks the delegate's actual receipt, without manufacturing a later completion.
        return withContext(NonCancellable) {
            database.withPreservedTransaction { ledger.requireRecordedInCurrentTransaction(receipt) }
        }
    }

    override suspend fun readPage(request: TransferRequest.ReadPage, permit: TransferPermit): TransferCatalogPage = storage {
        checkScope(request.scope, permit)
        checkPageCapability(request.kind, permit)
        if (request.kind == TransferCatalogKind.PLAYLIST_MEMBERS && request.search.isNotEmpty()) {
            // Member ordinals are the real complete order used by Move, including unavailable entries.
            fail(TransferIssue.INVALID_INPUT)
        }
        viewMutex.withLock {
            retireExpiredViews()
            val fresh = request.viewId == null
            val lease = if (fresh) {
                if (views.size >= LibraryLimits.TRANSFER_VIEWS) fail(TransferIssue.BUSY)
                CatalogLease(UUID.randomUUID().toString(), request, permit, deadline()).also { views[it.id] = it }
            } else views[request.viewId] ?: fail(TransferIssue.EXPIRED)
            try {
                if (fresh) openLease(lease, request, permit) else checkLease(lease, request, permit)
                val checkpoint = if (request.cursor == null) Checkpoint(0L, null)
                    else lease.checkpoints[request.cursor] ?: fail(TransferIssue.CONFLICT)
                val view = lease.library ?: fail(TransferIssue.RECOVERY_REQUIRED)
                val slice = library.transferTransaction(view) {
                    permit.checkActive()
                    readSlice(lease, checkpoint, request.limit)
                }
                permit.checkActive()
                val end = add(checkpoint.ordinal, slice.rows.size.toLong())
                if (slice.rows.size != minOf(request.limit.toLong(), lease.totalCount - checkpoint.ordinal).toInt() ||
                    (slice.next != null) != (end < lease.totalCount)) fail(TransferIssue.RECOVERY_REQUIRED)
                val next = slice.next?.let { remember(lease, Checkpoint(end, it)) }
                TransferCatalogPage(lease.kind, lease.id, lease.revision, fence(view), lease.totalCount,
                    checkpoint.ordinal, slice.rows, next)
            } catch (error: Throwable) {
                if (fresh || error is LibraryException && error.issue == LibraryIssue.VIEW_EXPIRED ||
                    error is PlaylistException && error.issue == PlaylistIssue.VIEW_EXPIRED) {
                    lease.retiring = true
                    try { withContext(NonCancellable) { releaseLease(lease) } }
                    catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
                }
                throw error
            }
        }
    }

    /** Retire the exact peer-owned view before its successor may acquire a slot. */
    override suspend fun closeCatalogView(request: TransferRequest.CloseCatalogView, permit: TransferPermit) = storage {
        checkScope(request.scope, permit)
        checkPageCapability(request.kind, permit)
        viewMutex.withLock {
            val lease = views[request.viewId] ?: return@withLock
            if (lease.kind != request.kind || !lease.permit.sameGrant(permit)) fail(TransferIssue.WRONG_TARGET)
            lease.retiring = true
            withContext(NonCancellable) { releaseLease(lease) }
        }
        permit.checkActive()
    }

    /** Both native snapshots open under the same writer; a later scan cannot replace either fence. */
    private suspend fun openLease(lease: CatalogLease, request: TransferRequest.ReadPage, permit: TransferPermit) {
        library.transferTransaction { meta ->
            permit.checkActive()
            val group = when (request.kind) {
                TransferCatalogKind.ALBUM_TRACKS -> currentGroup(TransferDisplayKind.ALBUM, checkNotNull(request.parentId), meta).first
                TransferCatalogKind.ARTIST_TRACKS -> currentGroup(TransferDisplayKind.ARTIST, checkNotNull(request.parentId), meta).first
                else -> null
            }
            val query = LibraryQuery(
                search = if (request.kind in LIBRARY_KINDS) request.search else "",
                albumKey = group?.groupKey?.takeIf { request.kind == TransferCatalogKind.ALBUM_TRACKS },
                artistKey = group?.groupKey?.takeIf { request.kind == TransferCatalogKind.ARTIST_TRACKS },
            )
            val sort = LibrarySort(if (request.kind == TransferCatalogKind.ALBUM_TRACKS) LibrarySortField.ALBUM_TRACK else LibrarySortField.TITLE)
            val view = library.openTransferViewUnlocked(query, sort).also { lease.library = it }
            when (request.kind) {
                TransferCatalogKind.PLAYLISTS -> {
                    val catalog = playlists.openCatalogView(PlaylistViewOwner.TRANSFER).also { lease.catalog = it }
                    lease.revision = catalog.revision
                    lease.totalCount = transfer.playlistCatalogCount(catalog.revision, searchPattern(request.search))
                }
                TransferCatalogKind.PLAYLIST_MEMBERS -> {
                    val members = playlists.openView(checkNotNull(request.parentId), PlaylistViewOwner.TRANSFER)
                        .also { lease.members = it }
                    lease.revision = members.snapshot.revision
                    lease.totalCount = members.snapshot.memberCount
                }
                TransferCatalogKind.ALBUMS, TransferCatalogKind.ARTISTS -> {
                    lease.revision = view.revision
                    lease.totalCount = dao.scalar(LibrarySql.groupCount(view, groupKind(request.kind)))
                }
                else -> { lease.revision = view.revision; lease.totalCount = view.totalCount }
            }
            permit.checkActive()
        }
    }

    private fun checkLease(lease: CatalogLease, request: TransferRequest.ReadPage, permit: TransferPermit) {
        if (lease.retiring || lease.expires <= now()) fail(TransferIssue.EXPIRED)
        if (!lease.permit.sameGrant(permit) || request.kind != lease.kind || request.parentId != lease.parentId ||
            request.search != lease.search || request.limit != lease.limit || request.expectedRevision != lease.revision ||
            request.expectedLibraryRevision?.sameAs(fence(lease.library ?: fail(TransferIssue.RECOVERY_REQUIRED))) != true) {
            fail(TransferIssue.CONFLICT)
        }
    }

    private suspend fun readSlice(lease: CatalogLease, checkpoint: Checkpoint, limit: Int): PageSlice {
        if (checkpoint.ordinal !in 0..lease.totalCount) fail(TransferIssue.CONFLICT)
        val view = lease.library ?: fail(TransferIssue.RECOVERY_REQUIRED)
        return when (lease.kind) {
            TransferCatalogKind.TRACKS, TransferCatalogKind.ALBUM_TRACKS, TransferCatalogKind.ARTIST_TRACKS -> {
                val after = (checkpoint.position as? Position.Tracks)?.cursor
                if (checkpoint.position != null && after == null) fail(TransferIssue.CONFLICT)
                val records = library.readOrderedRowsUnlocked(view, after, limit)
                val last = records.lastOrNull()?.cursor(view)
                val next = last?.takeIf { library.hasRowsBeyondUnlocked(view, it, before = false) }
                PageSlice(records.map(::trackRow), next?.let { Position.Tracks(it) })
            }
            TransferCatalogKind.ALBUMS, TransferCatalogKind.ARTISTS -> {
                val after = (checkpoint.position as? Position.Groups)?.key
                if (checkpoint.position != null && after == null) fail(TransferIssue.CONFLICT)
                val kind = groupKind(lease.kind)
                val records = dao.groupRows(LibrarySql.groups(view, kind, after, limit))
                val rows = records.map { record ->
                    val entity = registerGroup(kind, record)
                    TransferCatalogRow.Group(entity.entityId, groupName(kind, record.title), safeField(record.subtitle), record.trackCount, view.revision)
                }
                val last = records.lastOrNull()?.groupKey
                val next = last?.takeIf { dao.groupRows(LibrarySql.groups(view, kind, it, 1)).isNotEmpty() }
                PageSlice(rows, next?.let { Position.Groups(it) })
            }
            TransferCatalogKind.PLAYLISTS -> {
                val catalog = lease.catalog ?: fail(TransferIssue.RECOVERY_REQUIRED)
                val after = (checkpoint.position as? Position.Playlists)?.id
                if (checkpoint.position != null && after == null) fail(TransferIssue.CONFLICT)
                playlists.catalogPage(catalog, after?.let { PlaylistCatalogCursor(catalog.viewId, catalog.revision, it) }, 1)
                val pattern = searchPattern(lease.search)
                val records = transfer.playlistCatalogRows(catalog.revision, pattern, after, limit)
                val rows = records.map { record ->
                    val snapshot = record.playlistModel()
                    TransferCatalogRow.Playlist(snapshot.playlistId, snapshot.name, snapshot.kind == PlaylistKind.FOLDER,
                        snapshot.revision, snapshot.memberCount)
                }
                val last = records.lastOrNull()?.playlistId
                val next = last?.takeIf { transfer.playlistCatalogHasNext(catalog.revision, pattern, it) }
                PageSlice(rows, next?.let { Position.Playlists(it) })
            }
            TransferCatalogKind.PLAYLIST_MEMBERS -> {
                if (checkpoint.position != null && checkpoint.position !is Position.Members) fail(TransferIssue.CONFLICT)
                if (checkpoint.position is Position.Members && checkpoint.position.firstOrdinal != checkpoint.ordinal) fail(TransferIssue.CONFLICT)
                val members = lease.members ?: fail(TransferIssue.RECOVERY_REQUIRED)
                val page = playlists.page(members, checkpoint.ordinal, limit)
                val keys = page.members.mapNotNull { it.reference.trackKey }.distinct()
                val tracks = dao.rows(LibrarySql.rowsForKeys(view, keys)).associateBy { it.libraryModel().trackKey }
                val rows = page.members.map { member ->
                    val actual = member.reference.trackKey?.let(tracks::get)
                    TransferCatalogRow.Member(member.memberId, member.ordinal, member.reference.trackKey?.trackId,
                        safeField(actual?.track?.displayTitle ?: member.reference.titleHint, "Unknown track"),
                        safeField(actual?.displayArtist ?: member.reference.artistHint, "Unknown artist"),
                        actual?.availabilityRank == LibraryAvailability.AVAILABLE.ordinal)
                }
                PageSlice(rows, page.nextOrdinal?.let { Position.Members(it) })
            }
        }
    }

    private fun remember(lease: CatalogLease, checkpoint: Checkpoint): String {
        lease.checkpoints.entries.firstOrNull { it.value == checkpoint }?.let { return it.key }
        while (lease.checkpoints.size >= CURSORS_PER_VIEW) lease.checkpoints.remove(lease.checkpoints.keys.first())
        return UUID.randomUUID().toString().also { lease.checkpoints[it] = checkpoint }
    }

    override suspend fun editPlaylist(binding: TransferOperationBinding, request: TransferRequest.EditPlaylist,
        permit: TransferPermit): TransferReceipt = storage {
        checkScope(request.scope, permit)
        checkBinding(binding, request, permit)
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val replay = library.transferTransaction {
            ledger.bindInCurrentTransaction(binding, permit)
            ledger.committedInCurrentTransaction(binding, permit)
        }
        if (replay != null) return@storage replay
        val references = if (request.edit is TransferPlaylistEdit.Add) library.transferTransaction { meta ->
            checkFence(meta, checkNotNull(request.expectedLibraryRevision))
            permit.checkActive()
            val records = selectedTracks(currentView(meta), request.edit.trackIds)
                .associateBy { it.track.trackId }
            request.edit.trackIds.map { id ->
                val track = records.getValue(id).libraryModel()
                PlaylistReference(trackKey = track.trackKey, titleHint = safeField(track.title),
                    artistHint = safeField(track.displayArtist), albumHint = safeField(track.displayAlbum), durationMs = track.metadata.durationMs)
            }
        } else emptyList()
        val input = if (references.isEmpty()) null else FrozenPlaylistInput(binding.operationId,
            checkNotNull(request.expectedLibraryRevision).catalogRevision, references.size.toLong())
        val source = input?.let { frozen -> PlaylistPageSource { offset, limit ->
            currentCoroutineContext().ensureActive()
            if (offset !in 0..references.size.toLong() || limit !in 1..TransferLimits.PAGE_SIZE) fail(TransferIssue.INVALID_INPUT)
            val page = references.drop(offset.toInt()).take(limit)
            PlaylistInputPage(frozen, offset, page, offset + page.size == references.size.toLong())
        } }
        val mutation = when (val edit = request.edit) {
            is TransferPlaylistEdit.Create -> PlaylistMutation.Create(edit.name)
            is TransferPlaylistEdit.Rename -> PlaylistMutation.Rename(edit.name)
            is TransferPlaylistEdit.Delete -> PlaylistMutation.Delete(edit.confirmed)
            is TransferPlaylistEdit.Add -> PlaylistMutation.Add(checkNotNull(input))
            is TransferPlaylistEdit.Remove -> PlaylistMutation.Remove(edit.memberId, edit.confirmed)
            is TransferPlaylistEdit.Move -> PlaylistMutation.Move(edit.memberId, edit.destinationOrdinal)
        }
        var receipt: TransferReceipt? = null
        var claim: TransferCommitClaim? = null
        val participant = object : PlaylistCommitParticipant {
            override fun checkActive() { caller.ensureActive(); permit.checkActive() }
            override suspend fun verifyReplay(commit: PlaylistCommit) {
                val actual = ledger.committedInCurrentTransaction(binding, permit) ?: fail(TransferIssue.CONFLICT)
                if (actual.revision != commit.appliedRevision || actual.resultId != commit.playlistId) fail(TransferIssue.RECOVERY_REQUIRED)
                receipt = actual
            }
            override suspend fun claimCommit() {
                if (claim != null) fail(TransferIssue.RECOVERY_REQUIRED)
                checkActive()
                ledger.bindInCurrentTransaction(binding, permit)
                request.expectedLibraryRevision?.let { checkFence(dao.meta() ?: fail(TransferIssue.RECOVERY_REQUIRED), it) }
                checkActive()
                claim = permit.claimCommit(binding)
            }
            override suspend fun recordCommit(commit: PlaylistCommit) {
                receipt = ledger.recordInCurrentTransaction(TransferReceipt(binding, TransferReceiptKind.PLAYLIST_COMMITTED,
                    commit.appliedRevision, commit.playlistId, null), claim ?: fail(TransferIssue.RECOVERY_REQUIRED))
            }
        }
        playlists.applyAuthorized(PlaylistMutationRequest(binding.operationId, request.playlistId, request.expectedRevision, mutation,
            request.expectedLibraryRevision?.let { PlaylistLibraryRevision(it.catalogRevision, it.rulesRevision) }), source, participant)
        receipt ?: fail(TransferIssue.RECOVERY_REQUIRED)
    }

    override suspend fun readDisplay(request: TransferRequest.ReadDisplay, permit: TransferPermit): TransferDisplayState = storage {
        checkScope(request.scope, permit)
        requireDisplayCapability(permit)
        library.transferTransaction { meta ->
            permit.checkActive()
            val (_, record) = currentGroup(request.kind, request.entityId, meta)
            TransferDisplayState(request.kind, request.entityId, meta.revision, fence(meta),
                groupName(groupKind(request.kind), record.title), record.trackCount).also { permit.checkActive() }
        }
    }

    override suspend fun previewDisplay(request: TransferRequest.PreviewDisplay, permit: TransferPermit): TransferDisplayPreview = storage {
        checkScope(request.scope, permit)
        requireDisplayCapability(permit)
        library.transferTransaction { meta ->
            val hash = TransferProtocolCodec.displayPreviewHash(request).forLocalStorage()
            val existing = transfer.preview(request.requestId)
            existing?.let {
                // Explicit revalidation can refresh an expired ticket, only with its original input.
                val preview = decodePreview(it, permit, checkExpiry = false)
                if (it.payloadHash != hash || preview.kind != request.kind || preview.entityId != request.entityId ||
                    preview.revision != request.expectedRevision || !preview.libraryRevision.sameAs(request.expectedLibraryRevision) ||
                    !preview.patch.sameAs(request.patch)) fail(TransferIssue.CONFLICT)
            }
            checkFence(meta, request.expectedLibraryRevision)
            val (entity, record) = currentGroup(request.kind, request.entityId, meta)
            validatePatch(entity, request.patch, meta)
            if (existing != null && transfer.deletePreview(existing.previewId) != 1) fail(TransferIssue.RECOVERY_REQUIRED)
            transfer.expirePreviews(processId, now())
            if (transfer.previewCount() >= MAX_PREVIEWS) fail(TransferIssue.BUSY)
            val currentName = groupName(groupKind(request.kind), record.title)
            val row = TransferDisplayPreviewEntity(request.requestId, TransferLimits.SCHEMA, permit.target.forLocalDisplay(),
                permit.peer.forLocalDisplay(), permit.sessionId, permit.grant.grantId, permit.grant.epoch, processId,
                add(now(), TransferLimits.TOKEN_MS), request.kind.wire, entity.entityId, meta.revision, meta.rulesRevision,
                currentName, if (request.patch is TransferDisplayPatch.Rename) request.patch.displayName else currentName,
                record.trackCount, patchKind(request.patch), (request.patch as? TransferDisplayPatch.Rename)?.displayName,
                patchTracks(request.patch).joinToString("\n"), hash)
            permit.checkActive()
            transfer.insertPreview(row)
            decodePreview(row, permit).also { permit.checkActive() }
        }
    }

    override suspend fun applyDisplay(binding: TransferOperationBinding, request: TransferRequest.ApplyDisplay,
        permit: TransferPermit): TransferReceipt = storage {
        checkScope(request.scope, permit)
        checkBinding(binding, request, permit)
        val caller = currentCoroutineContext()
        caller.ensureActive()
        // Retain the actual result after claim; cancellation before claim still rolls back the writer.
        library.libraryTransaction(finalPublication = true) {
            val meta = dao.meta() ?: fail(TransferIssue.RECOVERY_REQUIRED)
            ledger.bindInCurrentTransaction(binding, permit)
            ledger.committedInCurrentTransaction(binding, permit)?.let { return@libraryTransaction it }
            if (!request.confirmed) fail(TransferIssue.NOT_APPROVED)
            val row = transfer.preview(request.previewId) ?: fail(TransferIssue.NOT_FOUND)
            val preview = decodePreview(row, permit)
            if (request.expectedRevision != preview.revision) fail(TransferIssue.CONFLICT)
            checkFence(meta, preview.libraryRevision)
            val (entity, record) = currentGroup(preview.kind, preview.entityId, meta)
            if (groupName(groupKind(preview.kind), record.title) != preview.currentName || record.trackCount != preview.currentTrackCount) {
                fail(TransferIssue.CONFLICT)
            }
            validatePatch(entity, preview.patch, meta)
            val changes = changes(entity, preview.patch)
            val changed = changes.needed || entity.editedFrom == null
            val revision = if (changed) add(meta.revision, 1L) else meta.revision
            caller.ensureActive()
            val claim = permit.claimCommit(binding)
            if (display.markEdited(entity.entityId, revision) != 1) fail(TransferIssue.RECOVERY_REQUIRED)
            changes.name?.let { name ->
                display.closeName(entity.entityId, revision)
                display.insertName(DisplayNameEntity(UUID.randomUUID().toString(), entity.entityId, name,
                    LibrarySortKeys.text(name), LibrarySortKeys.section(name), LibrarySortKeys.normalize(name), revision, null))
            }
            for (id in changes.trackIds) {
                if (changes.albumGroupId != null) {
                    display.closeAlbum(id, revision)
                    display.insertAlbum(DisplayAlbumLinkEntity(UUID.randomUUID().toString(), id, changes.albumGroupId, revision, null))
                } else {
                    display.closeArtist(id, entity.entityId, revision)
                    display.insertArtist(DisplayArtistLinkEntity(UUID.randomUUID().toString(), id, entity.entityId,
                        changes.artistIncluded ?: fail(TransferIssue.RECOVERY_REQUIRED), revision, null))
                }
            }
            if (changed && dao.updateMeta(meta.copy(revision = revision)) != 1) fail(TransferIssue.RECOVERY_REQUIRED)
            if (transfer.deletePreview(row.previewId) != 1) fail(TransferIssue.RECOVERY_REQUIRED)
            ledger.recordInCurrentTransaction(TransferReceipt(binding, TransferReceiptKind.DISPLAY_COMMITTED,
                revision, entity.entityId, null), claim)
        }
    }

    private suspend fun changes(entity: DisplayGroupEntity, patch: TransferDisplayPatch): DisplayChanges {
        if (patch is TransferDisplayPatch.Rename) return DisplayChanges(
            name = patch.displayName.takeUnless { display.currentName(entity.entityId)?.displayName == it })
        val tracks = patchTracks(patch)
        if (entity.kind == LibraryGroupKind.ALBUM.name) {
            val target = if (patch is TransferDisplayPatch.Remove) registerGroup(LibraryGroupKind.ALBUM,
                LibraryGroupRecord("UNKNOWN", null, null, 0L)).entityId else entity.entityId
            val changed = ArrayList<String>(tracks.size)
            for (id in tracks) if (display.currentAlbum(id)?.groupId != target) changed += id
            return DisplayChanges(albumGroupId = target, trackIds = changed)
        }
        val included = patch is TransferDisplayPatch.Add
        val changed = ArrayList<String>(tracks.size)
        for (id in tracks) if (display.currentArtist(id, entity.entityId)?.included != included) changed += id
        return DisplayChanges(artistIncluded = included, trackIds = changed)
    }

    private suspend fun validatePatch(entity: DisplayGroupEntity, patch: TransferDisplayPatch, meta: LibraryMetaEntity) {
        if (patch is TransferDisplayPatch.Rename) { validateName(patch.displayName); return }
        if (entity.groupKey == "UNKNOWN" || entity.groupKey == "4:") fail(TransferIssue.INVALID_INPUT)
        val scoped = if (patch is TransferDisplayPatch.Remove) currentView(meta, LibraryQuery(
            albumKey = entity.groupKey.takeIf { entity.kind == LibraryGroupKind.ALBUM.name },
            artistKey = entity.groupKey.takeIf { entity.kind == LibraryGroupKind.ARTIST.name },
        )) else currentView(meta)
        selectedTracks(scoped, patchTracks(patch))
    }

    private suspend fun selectedTracks(view: LibraryViewToken, ids: List<String>): List<LibraryRowRecord> {
        if (ids.isEmpty() || ids.size > TransferLimits.PAGE_SIZE) fail(TransferIssue.INVALID_INPUT)
        val keys = ids.distinct().map { TrackKey(TrackKey.LOCAL_PROVIDER, it) }
        val rows = dao.rows(LibrarySql.rowsForKeys(view, keys))
        if (rows.size != keys.size || rows.map { it.track.trackId }.toSet() != ids.toSet()) fail(TransferIssue.NOT_FOUND)
        return rows
    }

    private suspend fun currentGroup(kind: TransferDisplayKind, id: String, meta: LibraryMetaEntity): Pair<DisplayGroupEntity, LibraryGroupRecord> {
        val entity = display.group(id) ?: fail(TransferIssue.NOT_FOUND)
        if (entity.kind != groupKind(kind).name || entity.entityId != groupId(groupKind(kind), entity.groupKey)) fail(TransferIssue.CONFLICT)
        val records = dao.groupRows(LibrarySql.group(currentView(meta), groupKind(kind), entity.groupKey))
        if (records.size > 1) fail(TransferIssue.RECOVERY_REQUIRED)
        return entity to (records.singleOrNull() ?: fail(TransferIssue.NOT_FOUND))
    }

    private suspend fun registerGroup(kind: LibraryGroupKind, record: LibraryGroupRecord): DisplayGroupEntity {
        val id = groupId(kind, record.groupKey)
        display.insertGroup(DisplayGroupEntity(id, kind.name, record.groupKey, record.title, record.subtitle,
            LibrarySortKeys.text(record.title), LibrarySortKeys.section(record.title), LibrarySortKeys.normalize(record.title.orEmpty())))
        val entity = display.group(id) ?: fail(TransferIssue.RECOVERY_REQUIRED)
        if (entity.kind != kind.name || entity.groupKey != record.groupKey) fail(TransferIssue.CONFLICT)
        return entity
    }

    private fun decodePreview(row: TransferDisplayPreviewEntity, permit: TransferPermit, checkExpiry: Boolean = true): TransferDisplayPreview {
        permit.checkActive()
        if (row.targetFingerprint != permit.target.forLocalDisplay() || row.peerFingerprint != permit.peer.forLocalDisplay() ||
            row.sessionId != permit.sessionId || row.grantId != permit.grant.grantId || row.grantEpoch != permit.grant.epoch) {
            fail(TransferIssue.WRONG_TARGET)
        }
        if (row.processId != processId || checkExpiry && row.expiresElapsedMs <= now()) fail(TransferIssue.EXPIRED)
        if (row.protocolSchema != TransferLimits.SCHEMA || row.patchTrackIds.length > TransferLimits.PAGE_SIZE * 129) {
            fail(TransferIssue.RECOVERY_REQUIRED)
        }
        return try {
            val kind = TransferDisplayKind.entries.firstOrNull { it.wire == row.kind } ?: fail(TransferIssue.RECOVERY_REQUIRED)
            val ids = if (row.patchTrackIds.isEmpty()) emptyList() else row.patchTrackIds.split('\n')
            val patch = when (row.patchKind) {
                1 -> { if (ids.isNotEmpty()) fail(TransferIssue.RECOVERY_REQUIRED); TransferDisplayPatch.Rename(row.patchName ?: fail(TransferIssue.RECOVERY_REQUIRED)) }
                2 -> { if (row.patchName != null) fail(TransferIssue.RECOVERY_REQUIRED); TransferDisplayPatch.Add(ids) }
                3 -> { if (row.patchName != null) fail(TransferIssue.RECOVERY_REQUIRED); TransferDisplayPatch.Remove(ids) }
                else -> fail(TransferIssue.RECOVERY_REQUIRED)
            }
            val fence = TransferLibraryRevision(row.libraryRevision, row.rulesRevision)
            if (TransferProtocolCodec.displayPreviewHash(permit.target, kind, row.entityId, row.libraryRevision, fence, patch)
                    .forLocalStorage() != row.payloadHash) fail(TransferIssue.RECOVERY_REQUIRED)
            TransferDisplayPreview(row.previewId, kind, row.entityId, row.libraryRevision, row.currentName,
                row.proposedName, patch, fence, row.currentTrackCount)
        } catch (_: IllegalArgumentException) { fail(TransferIssue.RECOVERY_REQUIRED) }
    }

    override suspend fun closePeerViews(peer: TransferFingerprint, sessionId: String) = storage {
        withContext(NonCancellable) {
            viewMutex.withLock {
                val selected = views.values.filter { it.permit.peer == peer && it.permit.sessionId == sessionId }
                selected.forEach { it.retiring = true }
                var failure = false
                for (lease in selected) try { releaseLease(lease) } catch (_: Exception) { failure = true }
                try {
                    library.transferTransaction {
                        if (transfer.deletePeerPreviews(peer.forLocalDisplay(), sessionId) >= 100) failure = true
                    }
                } catch (_: Exception) { failure = true }
                if (failure) fail(TransferIssue.RECOVERY_REQUIRED)
            }
        }
    }

    override suspend fun closePeerPreviews(peer: TransferFingerprint, sessionId: String) = storage {
        withContext(NonCancellable) {
            library.transferTransaction {
                if (transfer.deletePeerPreviews(peer.forLocalDisplay(), sessionId) >= 100) fail(TransferIssue.RECOVERY_REQUIRED)
            }
        }
    }

    private suspend fun retireExpiredViews() {
        for (lease in views.values.toList()) {
            if (lease.retiring || lease.expires <= now()) { lease.retiring = true; withContext(NonCancellable) { releaseLease(lease) } }
        }
    }

    /** Keep a failed retirement occupying its bounded slot so a fresh close can finish it. */
    private suspend fun releaseLease(lease: CatalogLease) {
        var failure = false
        if (!lease.libraryReleased) try {
            lease.library?.let { library.closeTransferView(it) }
            lease.libraryReleased = true
        } catch (_: Exception) { failure = true }
        if (!lease.playlistReleased) try {
            val id = lease.catalog?.viewId ?: lease.members?.viewId
            if (id != null) playlists.closeView(id)
            lease.playlistReleased = true
        } catch (_: Exception) { failure = true }
        if (failure) fail(TransferIssue.RECOVERY_REQUIRED)
        lease.checkpoints.clear()
        views.remove(lease.id)
    }

    private fun checkScope(scope: TransferScope, permit: TransferPermit) {
        permit.checkActive()
        if (scope.target != permit.target || scope.sessionId != permit.sessionId) fail(TransferIssue.WRONG_TARGET)
    }

    private fun checkBinding(binding: TransferOperationBinding, request: TransferRequest.Authorized, permit: TransferPermit) {
        if (!binding.sameOperation(TransferProtocolCodec.operationBinding(permit.peer, request))) fail(TransferIssue.CONFLICT)
        if (permit.capability != binding.kind.capability) fail(TransferIssue.PERMISSION_DENIED)
    }

    private fun checkPageCapability(kind: TransferCatalogKind, permit: TransferPermit) {
        val allowed = when (kind) {
            TransferCatalogKind.TRACKS -> setOf(TransferCapability.READ_LIBRARY)
            TransferCatalogKind.PLAYLISTS, TransferCatalogKind.PLAYLIST_MEMBERS -> setOf(TransferCapability.READ_LIBRARY, TransferCapability.WRITE_PLAYLISTS)
            else -> setOf(TransferCapability.READ_LIBRARY, TransferCapability.WRITE_DISPLAY_METADATA)
        }
        if (permit.capability !in allowed) fail(TransferIssue.PERMISSION_DENIED)
    }

    private fun requireDisplayCapability(permit: TransferPermit) {
        if (permit.capability != TransferCapability.WRITE_DISPLAY_METADATA) fail(TransferIssue.PERMISSION_DENIED)
    }

    private fun checkFence(meta: LibraryMetaEntity, expected: TransferLibraryRevision) {
        if (meta.revision != expected.catalogRevision || meta.rulesRevision != expected.rulesRevision) fail(TransferIssue.CONFLICT)
    }

    private fun currentView(meta: LibraryMetaEntity, query: LibraryQuery = LibraryQuery()) = LibraryViewToken(
        "transfer-internal", meta.revision, meta.rulesRevision, 0L, query, LibrarySort(), meta.shortAudioFilter,
        LibraryViewOwner.TRANSFER, statisticsEpoch = meta.statisticsEpoch)
    private fun fence(meta: LibraryMetaEntity) = TransferLibraryRevision(meta.revision, meta.rulesRevision)
    private fun fence(view: LibraryViewToken) = TransferLibraryRevision(view.revision, view.rulesRevision)
    private fun groupKind(kind: TransferCatalogKind) = if (kind == TransferCatalogKind.ALBUMS) LibraryGroupKind.ALBUM else LibraryGroupKind.ARTIST
    private fun groupKind(kind: TransferDisplayKind) = if (kind == TransferDisplayKind.ALBUM) LibraryGroupKind.ALBUM else LibraryGroupKind.ARTIST
    private fun groupId(kind: LibraryGroupKind, key: String) = kind.name.lowercase(java.util.Locale.ROOT) + ":" + LibrarySortKeys.fingerprint(key)
    private fun groupName(kind: LibraryGroupKind, name: String?) = safeField(name, if (kind == LibraryGroupKind.ALBUM) "Unknown album" else "Unknown artist")
    private fun patchKind(patch: TransferDisplayPatch) = when (patch) { is TransferDisplayPatch.Rename -> 1; is TransferDisplayPatch.Add -> 2; is TransferDisplayPatch.Remove -> 3 }
    private fun patchTracks(patch: TransferDisplayPatch): List<String> = when (patch) { is TransferDisplayPatch.Rename -> emptyList(); is TransferDisplayPatch.Add -> patch.trackIds; is TransferDisplayPatch.Remove -> patch.trackIds }
    private fun searchPattern(search: String) = "%" + LibrarySortKeys.normalize(search).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    private fun now(): Long = clock.monotonicMs().also { if (it < 0L) fail(TransferIssue.RECOVERY_REQUIRED) }
    private fun deadline() = add(now(), TransferLimits.SESSION_MS)
    private fun add(first: Long, second: Long): Long = try { Math.addExact(first, second) } catch (_: ArithmeticException) { fail(TransferIssue.STORAGE_FULL) }

    private fun trackRow(record: LibraryRowRecord): TransferCatalogRow.Track {
        val track = record.libraryModel()
        return TransferCatalogRow.Track(track.trackKey.trackId, safeField(track.title, "Unknown track"),
            safeField(track.displayArtist, "Unknown artist"), safeField(track.displayAlbum, "Unknown album"),
            track.metadata.durationMs, track.availability == LibraryAvailability.AVAILABLE)
    }

    /** Only display values leave Room. Original scan payloads remain untouched, even when malformed. */
    private fun safeField(value: String?, fallback: String = ""): String {
        if (value.isNullOrBlank()) return fallback
        val result = StringBuilder(minOf(value.length, TransferLimits.FIELD_CHARACTERS))
        var index = 0
        while (index < value.length && result.length < TransferLimits.FIELD_CHARACTERS) {
            val character = value[index++]
            if (Character.isHighSurrogate(character)) {
                if (index < value.length && Character.isLowSurrogate(value[index]) && result.length < TransferLimits.FIELD_CHARACTERS - 1) {
                    result.append(character).append(value[index++])
                } else result.append('\uFFFD')
            } else result.append(if (Character.isLowSurrogate(character) || character.isISOControl()) '\uFFFD' else character)
        }
        return result.toString()
    }

    private fun validateName(name: String) {
        if (name.isBlank() || name.length > TransferLimits.FIELD_CHARACTERS || name.any { it.isISOControl() }) fail(TransferIssue.INVALID_INPUT)
        var index = 0
        while (index < name.length) {
            val character = name[index++]
            if (Character.isHighSurrogate(character)) {
                if (index == name.length || !Character.isLowSurrogate(name[index++])) fail(TransferIssue.INVALID_INPUT)
            } else if (Character.isLowSurrogate(character)) fail(TransferIssue.INVALID_INPUT)
        }
    }

    private suspend fun <T> storage(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { block() }
        catch (error: CancellationException) { throw error }
        catch (error: TransferException) { throw error }
        catch (error: LibraryException) { fail(when (error.issue) {
            LibraryIssue.BUSY -> TransferIssue.BUSY
            LibraryIssue.CONFLICT, LibraryIssue.SOURCE_CHANGED -> TransferIssue.CONFLICT
            LibraryIssue.INVALID_INPUT -> TransferIssue.INVALID_INPUT
            LibraryIssue.VIEW_EXPIRED -> TransferIssue.EXPIRED
            LibraryIssue.PERMISSION_REVOKED -> TransferIssue.PERMISSION_DENIED
            LibraryIssue.FILE_MISSING -> TransferIssue.NOT_FOUND
            LibraryIssue.STORAGE_FULL -> TransferIssue.STORAGE_FULL
            LibraryIssue.STORAGE_UNAVAILABLE, LibraryIssue.SOURCE_UNAVAILABLE -> TransferIssue.STORAGE_UNAVAILABLE
            LibraryIssue.INCOMPLETE, LibraryIssue.RECOVERY_REQUIRED -> TransferIssue.RECOVERY_REQUIRED
        }) }
        catch (error: PlaylistException) { fail(when (error.issue) {
            PlaylistIssue.BUSY -> TransferIssue.BUSY
            PlaylistIssue.CONFLICT, PlaylistIssue.SOURCE_CHANGED, PlaylistIssue.DUPLICATE_REFERENCE -> TransferIssue.CONFLICT
            PlaylistIssue.INVALID_INPUT -> TransferIssue.INVALID_INPUT
            PlaylistIssue.AUTOMATIC_MEMBERS -> TransferIssue.PERMISSION_DENIED
            PlaylistIssue.NOT_FOUND -> TransferIssue.NOT_FOUND
            PlaylistIssue.VIEW_EXPIRED -> TransferIssue.EXPIRED
            PlaylistIssue.STORAGE_FULL, PlaylistIssue.REVISION_EXHAUSTED -> TransferIssue.STORAGE_FULL
            PlaylistIssue.STORAGE_UNAVAILABLE, PlaylistIssue.SOURCE_UNAVAILABLE -> TransferIssue.STORAGE_UNAVAILABLE
            PlaylistIssue.INCOMPLETE, PlaylistIssue.RECOVERY_REQUIRED -> TransferIssue.RECOVERY_REQUIRED
        }) }
        catch (error: PlatformSQLiteException) { fail(when (error) {
            is SQLiteFullException -> TransferIssue.STORAGE_FULL
            is SQLiteConstraintException -> TransferIssue.RECOVERY_REQUIRED
            else -> TransferIssue.STORAGE_UNAVAILABLE
        }) }
        catch (_: IllegalArgumentException) { fail(TransferIssue.INVALID_INPUT) }
        catch (_: IllegalStateException) { fail(TransferIssue.RECOVERY_REQUIRED) }
    }

    private fun fail(issue: TransferIssue): Nothing = throw TransferException(issue)

    private class CatalogLease(val id: String, request: TransferRequest.ReadPage, val permit: TransferPermit, val expires: Long) {
        val kind = request.kind
        val parentId = request.parentId
        val search = request.search
        val limit = request.limit
        var library: LibraryViewToken? = null
        var catalog: PlaylistCatalogView? = null
        var members: PlaylistViewToken? = null
        var revision = 0L
        var totalCount = 0L
        var retiring = false
        var libraryReleased = false
        var playlistReleased = false
        val checkpoints = linkedMapOf<String, Checkpoint>()
    }
    private sealed interface Position {
        data class Tracks(val cursor: LibraryCursor) : Position
        data class Groups(val key: String) : Position
        data class Playlists(val id: String) : Position
        data class Members(val firstOrdinal: Long) : Position
    }
    private data class Checkpoint(val ordinal: Long, val position: Position?)
    private class PageSlice(val rows: List<TransferCatalogRow>, val next: Position?)
    private class DisplayChanges(val name: String? = null, val albumGroupId: String? = null,
        val artistIncluded: Boolean? = null, val trackIds: List<String> = emptyList()) {
        val needed get() = name != null || trackIds.isNotEmpty()
    }

    private companion object {
        const val CURSORS_PER_VIEW = 2
        const val MAX_PREVIEWS = 4L
        val LIBRARY_KINDS = setOf(TransferCatalogKind.TRACKS, TransferCatalogKind.ALBUMS, TransferCatalogKind.ARTISTS,
            TransferCatalogKind.ALBUM_TRACKS, TransferCatalogKind.ARTIST_TRACKS)
    }
}
